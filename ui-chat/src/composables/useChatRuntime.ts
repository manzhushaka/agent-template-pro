import { computed, nextTick, onMounted, ref } from 'vue'
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
  const creating = ref(false)
  const errorMessage = ref('')
  const lastSequence = ref(0)
  const receivedSequences = new Set<number>()

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
    const timeline = await chatApi.timeline(id)
    timeline.sort((left, right) => left.sequence - right.sequence).forEach(applyTimeline)
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
    const events = await chatApi.events(activeId.value, lastSequence.value)
    events.forEach((event) => applyEvent(event))
  }

  async function run(path: string, body: unknown): Promise<void> {
    loading.value = true
    errorMessage.value = ''
    try {
      await streamChat(path, body, (event) => applyEvent(event))
      await refreshConversations()
    } catch (cause) {
      try {
        await recover()
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
      confirm.value = null
      form.value = {
        pendingActionId: String(payload.pendingActionId),
        fields: Array.isArray(payload.fields) ? payload.fields as FormState['fields'] : [],
        values: {},
        agent,
      }
    } else if (event.type === 'action.confirm') {
      form.value = null
      confirm.value = {
        taskId: String(payload.taskId),
        confirmationVersion: Number(payload.confirmationVersion),
        title: String(payload.title || '确认操作'),
        summary: (payload.summary || {}) as Record<string, string>,
        agent,
      }
    } else if (event.type === 'card.render') {
      cards.value.push({
        cardType: String(payload.cardType || '业务结果'),
        data: (payload.data || {}) as Record<string, string | number>,
        agent,
        actionCode,
        sequence: event.sequence,
      })
    } else if (event.type === 'task.status') {
      tasks.value.push({
        taskId: String(payload.taskId),
        status: String(payload.status),
        externalRef: optionalString(payload.externalRef),
        agent,
        actionCode,
        sequence: event.sequence,
      })
    }
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

  onMounted(() => void initialize())

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
    creating,
    errorMessage,
    hasContent,
    createConversation,
    open,
    send,
    selectAgent,
    submitForm,
    decide,
    nextTick,
  }
}
