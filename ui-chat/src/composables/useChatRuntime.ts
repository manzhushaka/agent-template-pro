import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { chatApi, streamChat } from '../api/chat'
import type {
  AgentIdentity,
  AgentRouteNotice,
  Bootstrap,
  ChatMessage,
  ConfirmState,
  Conversation,
  FormState,
  ResultCard,
  StreamEvent,
  TaskEvent,
  TaskSnapshot,
  TimelineItem,
} from '../types/chat'

export function useChatRuntime() {
  const bootstrap = ref<Bootstrap | null>(null)
  const conversations = ref<Conversation[]>([])
  const activeId = ref('')
  const messages = ref<ChatMessage[]>([])
  const routes = ref<AgentRouteNotice[]>([])
  const cards = ref<ResultCard[]>([])
  const tasks = ref<TaskEvent[]>([])
  const form = ref<FormState | null>(null)
  const confirm = ref<ConfirmState | null>(null)
  const loading = ref(false)
  const restoring = ref(false)
  const creating = ref(false)
  const errorMessage = ref('')
  const lastSequence = ref(0)
  const receivedSequences = new Set<number>()
  let taskPollTimer: number | undefined
  let recoveryPromise: Promise<void> | null = null
  const PAGE_SIZE = 200
  const ACTIVE_TASK_STATUSES = new Set([
    'CREATED',
    'COLLECTING_INPUT',
    'WAITING_CONFIRMATION',
    'DISPATCHED',
    'WAITING_EXTERNAL_RESULT',
    'UNKNOWN',
  ])

  const activeConversation = computed(() => conversations.value.find((item) => item.id === activeId.value))
  const activeAgent = computed<AgentIdentity>(() => {
    const conversation = activeConversation.value
    if (conversation?.activeAgentCode && conversation.activeAgentName) {
      return { code: conversation.activeAgentCode, name: conversation.activeAgentName }
    }
    return {
      code: bootstrap.value?.coordinator.code || 'group-assistant',
      name: bootstrap.value?.coordinator.displayName || '集团总智能体',
    }
  })
  const hasContent = computed(() => messages.value.length > 0 || routes.value.length > 0
    || cards.value.length > 0 || tasks.value.length > 0)

  async function initialize(): Promise<void> {
    try {
      const [bootstrapResult, conversationResult] = await Promise.all([
        chatApi.bootstrap(),
        chatApi.conversations(),
      ])
      bootstrap.value = bootstrapResult
      conversations.value = conversationResult
      if (conversations.value[0]) await open(conversations.value[0].id)
      else await createConversation()
    } catch (cause) {
      errorMessage.value = cause instanceof Error ? cause.message : '初始化失败，请刷新页面。'
    }
  }

  async function refreshConversations(): Promise<void> {
    conversations.value = await chatApi.conversations()
  }

  async function createConversation(): Promise<void> {
    if (creating.value || loading.value) return
    creating.value = true
    errorMessage.value = ''
    try {
      const conversation = await chatApi.createConversation()
      conversations.value.unshift(conversation)
      await open(conversation.id)
    } catch (cause) {
      errorMessage.value = cause instanceof Error ? cause.message : '创建会话失败。'
    } finally {
      creating.value = false
    }
  }

  async function open(id: string): Promise<void> {
    activeId.value = id
    resetTimeline()
    restoring.value = true
    try {
      await loadTimeline(id)
      await loadEvents(id, 0, true)
      await reconcileTasks()
      scheduleTaskPoll()
    } finally {
      restoring.value = false
    }
  }

  async function loadTimeline(conversationId: string): Promise<void> {
    let cursor = 0
    while (true) {
      const page = await chatApi.timeline(conversationId, cursor, PAGE_SIZE)
      const normalized = normalizeTimeline(page)
      normalized.forEach(applyTimeline)
      if (page.length < PAGE_SIZE) return
      const nextCursor = page.reduce((maximum, item) => Math.max(maximum, item.sequence), cursor)
      if (nextCursor <= cursor) return
      cursor = nextCursor
    }
  }

  async function loadEvents(conversationId: string, afterSequence: number, restored: boolean): Promise<void> {
    let cursor = afterSequence
    while (true) {
      const page = await chatApi.events(conversationId, cursor, PAGE_SIZE)
      page.sort((left, right) => left.sequence - right.sequence)
        .forEach((event) => applyEvent(event, restored))
      if (page.length < PAGE_SIZE) return
      const nextCursor = page.reduce((maximum, event) => Math.max(maximum, event.sequence), cursor)
      if (nextCursor <= cursor) return
      cursor = nextCursor
    }
  }

  function normalizeTimeline(page: TimelineItem[]): TimelineItem[] {
    const bySequence = new Map<number, TimelineItem>()
    page.sort((left, right) => left.sequence - right.sequence).forEach((item) => {
      const current = bySequence.get(item.sequence)
      if (!current || item.kind === 'EVENT' || current.role === 'SYSTEM') {
        bySequence.set(item.sequence, item)
      }
    })
    return [...bySequence.values()].sort((left, right) => left.sequence - right.sequence)
  }

  function resetTimeline(): void {
    messages.value = []
    routes.value = []
    cards.value = []
    tasks.value = []
    form.value = null
    confirm.value = null
    lastSequence.value = 0
    receivedSequences.clear()
  }

  function applyTimeline(item: TimelineItem): void {
    lastSequence.value = Math.max(lastSequence.value, item.sequence)
    receivedSequences.add(item.sequence)
    if (item.kind === 'MESSAGE' && item.role !== 'SYSTEM') {
      messages.value.push({
        role: item.role === 'USER' ? 'USER' : 'ASSISTANT',
        content: item.content,
        sequence: item.sequence,
        agent: item.role === 'USER' ? undefined : identity(item.agentCode, item.agentName),
      })
      return
    }
    applyEvent({
      type: item.eventType,
      conversationId: activeId.value,
      requestId: item.requestId || '',
      sequence: item.sequence,
      timestamp: item.createdAt,
      payload: item.payload,
    }, true)
  }

  async function recover(): Promise<void> {
    if (!activeId.value) return
    if (recoveryPromise) return recoveryPromise
    recoveryPromise = (async () => {
      await loadEvents(activeId.value, lastSequence.value, false)
      await reconcileTasks()
    })()
    try {
      await recoveryPromise
    } finally {
      recoveryPromise = null
    }
  }

  async function recoverWithRetry(): Promise<void> {
    const delays = [0, 300, 900, 1800]
    let failure: unknown
    for (const delay of delays) {
      if (delay > 0) await wait(delay)
      try {
        await recover()
        errorMessage.value = ''
        return
      } catch (cause) {
        failure = cause
      }
    }
    throw failure
  }

  async function run(path: string, body: unknown): Promise<void> {
    loading.value = true
    errorMessage.value = ''
    try {
      await streamChat(path, body, (event) => applyEvent(event))
      await refreshConversations()
    } catch (cause) {
      try {
        await recoverWithRetry()
      } catch {
        errorMessage.value = cause instanceof Error ? cause.message : '连接失败，请重试。'
      }
    } finally {
      loading.value = false
    }
  }

  function applyEvent(event: StreamEvent, restored = false): void {
    if (!restored && (event.sequence <= lastSequence.value || receivedSequences.has(event.sequence))) return
    receivedSequences.add(event.sequence)
    lastSequence.value = Math.max(lastSequence.value, event.sequence)
    const payload = event.payload
    const agent = payloadAgent(payload)
    const actionCode = optionalString(payload.actionCode)
    if (event.type === 'agent.route') {
      routes.value.push({
        sequence: event.sequence,
        routeType: String(payload.routeType || ''),
        targetAgentCode: optionalString(payload.targetAgentCode),
        targetAgentName: optionalString(payload.targetAgentName),
        reasonCode: String(payload.reasonCode || ''),
        candidates: Array.isArray(payload.candidates)
          ? payload.candidates as AgentRouteNotice['candidates']
          : [],
      })
    } else if (event.type === 'message.final' && !restored) {
      messages.value.push({
        role: 'ASSISTANT',
        content: String(payload.content || ''),
        sequence: event.sequence,
        agent,
      })
    } else if (event.type === 'form.request') {
      if (!payload.pendingActionId) return
      confirm.value = null
      form.value = {
        pendingActionId: String(payload.pendingActionId),
        fields: Array.isArray(payload.fields) ? payload.fields as FormState['fields'] : [],
        values: {},
        agent,
      }
    } else if (event.type === 'action.confirm') {
      if (!payload.taskId || !payload.confirmationVersion) return
      form.value = null
      confirm.value = {
        taskId: String(payload.taskId),
        confirmationVersion: Number(payload.confirmationVersion),
        title: String(payload.title || '确认操作'),
        summary: (payload.summary || {}) as Record<string, string>,
        agent,
      }
    } else if (event.type === 'card.render') {
      if (cards.value.some((card) => card.sequence === event.sequence)) return
      cards.value.push({
        cardType: String(payload.cardType || '业务结果'),
        data: (payload.data || {}) as Record<string, string | number>,
        agent,
        actionCode,
        sequence: event.sequence,
      })
    } else if (event.type === 'task.status') {
      if (!payload.taskId) return
      upsertTask({
        taskId: String(payload.taskId),
        status: String(payload.status),
        externalRef: optionalString(payload.externalRef),
        agent,
        actionCode,
        sequence: event.sequence,
      })
    }
  }

  function upsertTask(task: TaskEvent): void {
    const index = tasks.value.findIndex((item) => item.taskId === task.taskId)
    if (index < 0) tasks.value.push(task)
    else if (tasks.value[index] && tasks.value[index].sequence <= task.sequence) tasks.value[index] = task
    if (confirm.value?.taskId === task.taskId && task.status !== 'WAITING_CONFIRMATION') {
      confirm.value = null
    }
  }

  async function reconcileTasks(): Promise<void> {
    const taskIds = new Set(tasks.value.map((task) => task.taskId))
    if (confirm.value?.taskId) taskIds.add(confirm.value.taskId)
    const snapshots = await Promise.all([...taskIds].map(async (taskId) => {
      try {
        return await chatApi.task(taskId)
      } catch {
        return null
      }
    }))
    snapshots.filter((task): task is TaskSnapshot => task !== null).forEach((task) => upsertTask({
      taskId: task.id,
      status: task.status,
      externalRef: task.externalRef,
      agent: identity(task.domainCode, task.agentName),
      actionCode: task.actionCode,
      sequence: lastSequence.value,
    }))
  }

  function scheduleTaskPoll(): void {
    if (taskPollTimer !== undefined) window.clearTimeout(taskPollTimer)
    if (!tasks.value.some((task) => ACTIVE_TASK_STATUSES.has(task.status)) && !confirm.value) return
    taskPollTimer = window.setTimeout(async () => {
      if (document.visibilityState === 'visible' && navigator.onLine) {
        await reconcileTasks()
      }
      scheduleTaskPoll()
    }, 4000)
  }

  async function retryRecovery(): Promise<void> {
    errorMessage.value = ''
    restoring.value = true
    try {
      await recoverWithRetry()
      scheduleTaskPoll()
    } catch (cause) {
      errorMessage.value = cause instanceof Error ? cause.message : '会话恢复失败，请稍后重试。'
    } finally {
      restoring.value = false
    }
  }

  function wait(milliseconds: number): Promise<void> {
    return new Promise((resolve) => window.setTimeout(resolve, milliseconds))
  }

  function resumeFromBrowser(): void {
    if (document.visibilityState !== 'visible' || !navigator.onLine || !activeId.value) return
    void retryRecovery()
  }

  function send(text: string): void {
    const content = text.trim()
    if (!content || !activeId.value || loading.value) return
    messages.value.push({ role: 'USER', content })
    form.value = null
    void run(`/conversations/${activeId.value}/messages:stream`, { content })
  }

  function selectAgent(code: string): void {
    const conversation = activeConversation.value
    if (!conversation || loading.value) return
    void run(`/conversations/${conversation.id}/agent:select`, {
      targetAgentCode: code,
      expectedRoutingVersion: conversation.routingVersion,
    })
  }

  function submitForm(): void {
    if (!form.value || loading.value) return
    const state = form.value
    form.value = null
    void run(`/pending-actions/${state.pendingActionId}/input`, { input: state.values })
  }

  function decide(decision: 'CONFIRMED' | 'REJECTED'): void {
    if (!confirm.value || loading.value) return
    const state = confirm.value
    confirm.value = null
    void run(`/tasks/${state.taskId}/confirm`, {
      confirmationVersion: state.confirmationVersion,
      decision,
    })
  }

  function identity(code?: string, name?: string): AgentIdentity | undefined {
    if (!code && !name) return undefined
    return { code: code || 'group-assistant', name: name || '集团总智能体' }
  }

  function payloadAgent(payload: Record<string, unknown>): AgentIdentity | undefined {
    if (!payload.agent || typeof payload.agent !== 'object') return undefined
    const value = payload.agent as Record<string, unknown>
    return identity(optionalString(value.code), optionalString(value.name))
  }

  function optionalString(value: unknown): string | undefined {
    return value === null || value === undefined || value === '' ? undefined : String(value)
  }

  onMounted(() => {
    window.addEventListener('online', resumeFromBrowser)
    document.addEventListener('visibilitychange', resumeFromBrowser)
    void initialize()
  })
  onBeforeUnmount(() => {
    window.removeEventListener('online', resumeFromBrowser)
    document.removeEventListener('visibilitychange', resumeFromBrowser)
    if (taskPollTimer !== undefined) window.clearTimeout(taskPollTimer)
  })

  return {
    bootstrap,
    conversations,
    activeId,
    activeConversation,
    activeAgent,
    messages,
    routes,
    cards,
    tasks,
    form,
    confirm,
    loading,
    restoring,
    creating,
    errorMessage,
    hasContent,
    createConversation,
    open,
    send,
    selectAgent,
    submitForm,
    decide,
    retryRecovery,
    nextTick,
  }
}
