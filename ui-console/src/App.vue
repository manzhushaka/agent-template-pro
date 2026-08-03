<script setup lang="ts">
import {
  ArrowRight,
  ChatDotRound,
  Coin,
  Connection,
  Cpu,
  DataAnalysis,
  Document,
  FolderOpened,
  Hide,
  Key,
  Lock,
  Monitor,
  Operation,
  Refresh,
  Search,
  Setting,
  SwitchButton,
  Tickets,
  User,
  UserFilled,
  VideoPlay,
  View,
  WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { ConsoleApiError, consoleApi, MAX_KNOWLEDGE_DOCUMENT_BYTES, streamWorkflowEvents } from './api/console'
import AppIcon from './components/AppIcon.vue'
import { SessionFence, type SessionLease } from './sessionFence'
import type {
  ConsoleSection,
  CursorPageResponse,
  Overview,
  PageResponse,
  RuntimeAgent,
  RuntimeConfig,
  RuntimeConversation,
  RuntimeEvent,
  RuntimeTask,
  TaskDetail,
  ControlResource,
  ControlPrincipal,
  McpServer,
  McpTool,
  KnowledgeBase,
  KnowledgeChunk,
  KnowledgeDocument,
  KnowledgeIndexJob,
  KnowledgeRetrievalMatch,
  AgentApplication,
  AgentApplicationVersion,
  AgentAppBinding,
  AgentAppPublishRecord,
  AgentAppApiKey,
  AgentAppApiKeyCreated,
  TraceSpan,
  TraceDetail,
  ObservabilityOverview,
  EvalDataset,
  EvalDatasetVersion,
  EvalCase,
  EvalEvaluator,
  EvalExperiment,
  EvalExperimentRun,
  WorkflowDefinition,
  WorkflowVersionView,
  WorkflowRunView,
  WorkflowNodeRunView,
  WorkflowEventView,
  WorkflowValidationResult,
} from './types/console'

const PAGE_SIZE = 20
const sessionFence = new SessionFence()
let loginRequestId = 0
const emptyConversations = (): PageResponse<RuntimeConversation> => ({
  items: [], page: 1, size: PAGE_SIZE, total: 0, totalPages: 0,
})
const emptyTasks = (): PageResponse<RuntimeTask> => ({
  items: [], page: 1, size: PAGE_SIZE, total: 0, totalPages: 0,
})
const sessionToken = ref(sessionStorage.getItem('console-token') || '')
const principal = ref<ControlPrincipal | null>(null)
const username = ref(sessionStorage.getItem('console-username') || 'admin')
const password = ref('')
const passwordVisible = ref(false)
const captchaId = ref('')
const captchaCode = ref('')
const captchaImage = ref('')
const captchaLoading = ref(false)
const active = ref<ConsoleSection>('overview')
const error = ref('')
const loading = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const overview = ref<Overview | null>(null)
const agents = ref<RuntimeAgent[]>([])
const config = ref<RuntimeConfig | null>(null)
const conversationPage = ref<PageResponse<RuntimeConversation>>(emptyConversations())
const conversationQuery = ref('')
const taskPage = ref<PageResponse<RuntimeTask>>(emptyTasks())
const taskQuery = ref('')
const taskStatus = ref('')
const taskActionCode = ref('')
const selectedConversation = ref<RuntimeConversation | null>(null)
const conversationEvents = ref<CursorPageResponse<RuntimeEvent>>({
  items: [], nextSequence: 0, hasMore: false,
})
const conversationDrawerOpen = ref(false)
const selectedTask = ref<TaskDetail | null>(null)
const taskDrawerOpen = ref(false)
const modelPage = ref<PageResponse<ControlResource>>(emptyControlResources())
const promptPage = ref<PageResponse<ControlResource>>(emptyControlResources())
const mcpServerPage = ref<PageResponse<McpServer>>(emptyControlResources() as PageResponse<McpServer>)
const mcpToolPage = ref<PageResponse<McpTool>>(emptyControlResources() as PageResponse<McpTool>)
const mcpKeyword = ref('')
const knowledgeBasePage = ref<PageResponse<KnowledgeBase>>(emptyControlResources() as PageResponse<KnowledgeBase>)
const knowledgeDocumentPage = ref<PageResponse<KnowledgeDocument>>(emptyControlResources() as PageResponse<KnowledgeDocument>)
const knowledgeJobPage = ref<PageResponse<KnowledgeIndexJob>>(emptyControlResources() as PageResponse<KnowledgeIndexJob>)
const knowledgeChunkPage = ref<PageResponse<KnowledgeChunk>>(emptyControlResources() as PageResponse<KnowledgeChunk>)
const knowledgeKeyword = ref('')
const selectedKnowledgeBase = ref<KnowledgeBase | null>(null)
const selectedKnowledgeDocument = ref<KnowledgeDocument | null>(null)
const knowledgeDialogOpen = ref(false)
const knowledgeChunksOpen = ref(false)
const knowledgeLoading = ref(false)
const knowledgeRetrievalLoading = ref(false)
const knowledgeRetrievalQuery = ref('')
const knowledgeTopK = ref(5)
const knowledgeThreshold = ref(0.2)
const knowledgeMatches = ref<KnowledgeRetrievalMatch[]>([])
const knowledgeBaseForm = ref({ id: '', code: '', displayName: '', description: '', status: 'ACTIVE' })
const knowledgeDocumentInput = ref<HTMLInputElement | null>(null)
const applicationPage = ref<PageResponse<AgentApplication>>(emptyControlResources() as PageResponse<AgentApplication>)
const applicationKeyword = ref('')
const selectedApplication = ref<AgentApplication | null>(null)
const applicationDrawerOpen = ref(false)
const applicationVersions = ref<AgentApplicationVersion[]>([])
const applicationRecords = ref<AgentAppPublishRecord[]>([])
const applicationApiKeys = ref<AgentAppApiKey[]>([])
const applicationBindings = ref<Record<string, AgentAppBinding[]>>({})
const applicationDetailLoading = ref(false)
const tracePage = ref<PageResponse<TraceSpan>>(emptyControlResources() as PageResponse<TraceSpan>)
const traceKeyword = ref('')
const traceType = ref('')
const traceStatus = ref('')
const observabilityOverview = ref<ObservabilityOverview | null>(null)
const selectedTrace = ref<TraceDetail | null>(null)
const traceDrawerOpen = ref(false)
const datasetPage = ref<PageResponse<EvalDataset>>(emptyControlResources() as PageResponse<EvalDataset>)
const datasetKeyword = ref('')
const selectedDataset = ref<EvalDataset | null>(null)
const datasetDrawerOpen = ref(false)
const datasetVersions = ref<EvalDatasetVersion[]>([])
const datasetVersionCases = ref<PageResponse<EvalCase>>(emptyControlResources() as PageResponse<EvalCase>)
const datasetCasePage = ref(1)
const datasetCaseCategory = ref('')
const evaluatorPage = ref<PageResponse<EvalEvaluator>>(emptyControlResources() as PageResponse<EvalEvaluator>)
const evaluatorKeyword = ref('')
const experimentPage = ref<PageResponse<EvalExperiment>>(emptyControlResources() as PageResponse<EvalExperiment>)
const experimentKeyword = ref('')
const experimentStatus = ref('')
const selectedExperiment = ref<EvalExperiment | null>(null)
const workflowPage = ref<PageResponse<WorkflowDefinition>>(emptyControlResources() as PageResponse<WorkflowDefinition>)
const workflowKeyword = ref('')
const workflowDialogOpen = ref(false)
const workflowForm = reactive({ code: '', displayName: '', description: '' })
const workflowEditorOpen = ref(false)
const selectedWorkflow = ref<WorkflowDefinition | null>(null)
const workflowVersions = ref<WorkflowVersionView[]>([])
const workflowDslJson = ref('')
const workflowBindingsJson = ref('{}')
const workflowValidation = ref<WorkflowValidationResult | null>(null)
const workflowVersionLoading = ref(false)
const workflowRunPage = ref<PageResponse<WorkflowRunView>>(emptyControlResources() as PageResponse<WorkflowRunView>)
const workflowRunKeyword = ref('')
const workflowRunStatus = ref('')
const selectedWorkflowRun = ref<WorkflowRunView | null>(null)
const workflowRunDrawerOpen = ref(false)
const workflowNodeRuns = ref<WorkflowNodeRunView[]>([])
const workflowEvents = ref<WorkflowEventView[]>([])
const workflowEventStreaming = ref(false)
const workflowEventAbort = ref<AbortController | null>(null)
const workflowInputOpen = ref(false)
const workflowInputValues = ref('{}')
const workflowConfirmOpen = ref(false)
const workflowConfirmNodeId = ref('')
const workflowConfirmVersion = ref(0)
const workflowEventStreamRef = ref<HTMLElement | null>(null)
const experimentRuns = ref<PageResponse<EvalExperimentRun>>(emptyControlResources() as PageResponse<EvalExperimentRun>)
const experimentRunsOpen = ref(false)
const experimentRunsPage = ref(1)
const evalSummary = ref<Record<string, unknown> | null>(null)
const evalLoading = ref(false)
const openApiSpec = ref<Record<string, unknown> | null>(null)
const openApiDialogOpen = ref(false)
const secretRefs = ref<ControlResource[]>([])
const controlKeyword = ref('')
const controlSubmitting = ref(false)
const controlResult = ref('')

function emptyControlResources(): PageResponse<ControlResource> {
  return { items: [], page: 1, size: PAGE_SIZE, total: 0, totalPages: 0 }
}

function beginSessionRequest(): SessionLease | undefined {
  return sessionFence.begin(sessionToken.value)
}

function isCurrentSession(lease: SessionLease | undefined): lease is SessionLease {
  return lease !== undefined && sessionFence.isCurrent(lease, sessionToken.value)
}

function sessionExpired(lease: SessionLease): void {
  if (!isCurrentSession(lease)) {
    return
  }
  clearSession()
  error.value = '登录会话已失效，请重新登录。'
  void refreshCaptcha()
}

async function requestForSession<T>(
  lease: SessionLease,
  request: (token: string) => Promise<T>,
): Promise<T | undefined> {
  if (!isCurrentSession(lease)) {
    return undefined
  }
  try {
    const result = await request(lease.token)
    return isCurrentSession(lease) ? result : undefined
  } catch (cause) {
    if (isCurrentSession(lease) && cause instanceof ConsoleApiError && cause.status === 401) {
      sessionExpired(lease)
      return undefined
    }
    if (!isCurrentSession(lease)) {
      return undefined
    }
    throw cause
  }
}

function controlFailure(cause: unknown, fallback: string, lease: SessionLease): void {
  if (!isCurrentSession(lease)) {
    return
  }
  controlResult.value = cause instanceof Error ? cause.message : fallback
}

const title = computed(() => ({
  overview: '运行总览',
  conversations: '会话与事件',
  agents: '领域 Agent',
  tasks: '任务执行记录',
  config: '运行配置',
  models: '模型管理',
  secrets: 'Secret 引用',
  prompts: 'Prompt 管理',
  mcpServers: 'MCP Server',
  mcpTools: '工具目录',
  knowledge: '知识库',
  applications: 'Agent 应用',
  traces: 'Trace 与指标',
  datasets: '数据集',
  evaluators: '评估器',
  experiments: '评估实验',
  workflows: 'Workflow 编排',
  workflowRuns: 'Workflow 运行',
})[active.value])

async function refreshCaptcha(): Promise<void> {
  const currentLoginRequest = loginRequestId
  captchaLoading.value = true
  try {
    const captcha = await consoleApi.captcha()
    if (currentLoginRequest !== loginRequestId || sessionToken.value) {
      return
    }
    captchaId.value = captcha.captchaId
    captchaImage.value = captcha.imageData
    captchaCode.value = ''
  } catch (cause) {
    if (currentLoginRequest !== loginRequestId || sessionToken.value) {
      return
    }
    error.value = cause instanceof Error ? cause.message : '图片验证码加载失败。'
  } finally {
    if (currentLoginRequest === loginRequestId && !sessionToken.value) {
      captchaLoading.value = false
    }
  }
}

async function login(): Promise<void> {
  if (!username.value.trim() || !password.value || !captchaCode.value.trim()) {
    error.value = '请完整填写用户名、密码和图片验证码。'
    return
  }
  const currentLoginRequest = ++loginRequestId
  loading.value = true
  error.value = ''
  try {
    const session = await consoleApi.login({
      username: username.value.trim(),
      password: password.value,
      captchaId: captchaId.value,
      captchaCode: captchaCode.value.trim(),
    })
    if (currentLoginRequest !== loginRequestId || sessionToken.value) {
      return
    }
    sessionFence.activate()
    resetConsoleState()
    sessionToken.value = session.token
    username.value = session.username
    sessionStorage.setItem('console-token', session.token)
    sessionStorage.setItem('console-username', session.username)
    password.value = ''
    captchaCode.value = ''
    await load()
  } catch (cause) {
    if (currentLoginRequest !== loginRequestId) {
      return
    }
    error.value = cause instanceof Error ? cause.message : '登录失败。'
    await refreshCaptcha()
  } finally {
    if (currentLoginRequest === loginRequestId) {
      loading.value = false
    }
  }
}

async function load(): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  loading.value = true
  error.value = ''
  try {
    if (!principal.value) {
      const currentPrincipal = await requestForSession(lease, consoleApi.me)
      if (!isCurrentSession(lease) || !currentPrincipal) return
      principal.value = currentPrincipal
    }
    if (active.value === 'overview') {
      const result = await requestForSession(lease, consoleApi.overview)
      if (!isCurrentSession(lease) || !result) return
      overview.value = result
    } else if (active.value === 'conversations') {
      const result = await requestForSession(lease, token => consoleApi.conversations(
        token,
        conversationPage.value.page,
        PAGE_SIZE,
        conversationQuery.value.trim(),
      ))
      if (!isCurrentSession(lease) || !result) return
      conversationPage.value = result
    } else if (active.value === 'agents') {
      const result = await requestForSession(lease, consoleApi.agents)
      if (!isCurrentSession(lease) || !result) return
      agents.value = result
    } else if (active.value === 'tasks') {
      const result = await requestForSession(lease, token => consoleApi.tasks(token, taskPage.value.page, PAGE_SIZE, {
        status: taskStatus.value,
        actionCode: taskActionCode.value.trim(),
        query: taskQuery.value.trim(),
      }))
      if (!isCurrentSession(lease) || !result) return
      taskPage.value = result
    } else if (active.value === 'config') {
      const result = await requestForSession(lease, consoleApi.runtimeConfig)
      if (!isCurrentSession(lease) || !result) return
      config.value = result
    } else if (active.value === 'models') {
      const result = await requestForSession(lease, token => consoleApi.models(token, 1, PAGE_SIZE, controlKeyword.value.trim()))
      if (!isCurrentSession(lease) || !result) return
      modelPage.value = result
    } else if (active.value === 'secrets') {
      const result = await requestForSession(lease, consoleApi.secretRefs)
      if (!isCurrentSession(lease) || !result) return
      secretRefs.value = result
    } else if (active.value === 'mcpServers') {
      const result = await requestForSession(lease, token => consoleApi.mcpServers(token, mcpServerPage.value.page, PAGE_SIZE, mcpKeyword.value.trim()))
      if (!isCurrentSession(lease) || !result) return
      mcpServerPage.value = result
    } else if (active.value === 'mcpTools') {
      const result = await requestForSession(lease, token => consoleApi.mcpTools(token, mcpToolPage.value.page, PAGE_SIZE, mcpKeyword.value.trim()))
      if (!isCurrentSession(lease) || !result) return
      mcpToolPage.value = result
    } else if (active.value === 'knowledge') {
      const result = await requestForSession(lease, token => consoleApi.knowledgeBases(token, knowledgeBasePage.value.page, PAGE_SIZE, knowledgeKeyword.value.trim()))
      if (!isCurrentSession(lease) || !result) return
      knowledgeBasePage.value = result
    } else if (active.value === 'applications') {
      const result = await requestForSession(lease, token => consoleApi.applications(token, applicationPage.value.page, PAGE_SIZE, applicationKeyword.value.trim()))
      if (!isCurrentSession(lease) || !result) return
      applicationPage.value = result
    } else if (active.value === 'traces') {
      const result = await requestForSession(lease, token => consoleApi.traces(token, tracePage.value.page, PAGE_SIZE, {
        type: traceType.value,
        status: traceStatus.value,
        query: traceKeyword.value.trim(),
      }))
      if (!isCurrentSession(lease) || !result) return
      tracePage.value = result
      const metrics = await requestForSession(lease, consoleApi.observabilityOverview)
      if (isCurrentSession(lease) && metrics) {
        observabilityOverview.value = metrics
      }
    } else if (active.value === 'datasets') {
      const result = await requestForSession(lease, token => consoleApi.evalDatasets(token, datasetPage.value.page, PAGE_SIZE, datasetKeyword.value.trim()))
      if (!isCurrentSession(lease) || !result) return
      datasetPage.value = result
    } else if (active.value === 'evaluators') {
      const result = await requestForSession(lease, token => consoleApi.evalEvaluators(token, evaluatorPage.value.page, PAGE_SIZE, evaluatorKeyword.value.trim()))
      if (!isCurrentSession(lease) || !result) return
      evaluatorPage.value = result
    } else if (active.value === 'experiments') {
      const result = await requestForSession(lease, token => consoleApi.evalExperiments(token, experimentPage.value.page, PAGE_SIZE, {
        status: experimentStatus.value,
        query: experimentKeyword.value.trim(),
      }))
      if (!isCurrentSession(lease) || !result) return
      experimentPage.value = result
    } else if (active.value === 'workflows') {
      const result = await requestForSession(lease, token => consoleApi.workflows(token, workflowPage.value.page, PAGE_SIZE, workflowKeyword.value.trim()))
      if (!isCurrentSession(lease) || !result) return
      workflowPage.value = result
    } else if (active.value === 'workflowRuns') {
      const result = await requestForSession(lease, token => consoleApi.workflowRuns(token, workflowRunPage.value.page, PAGE_SIZE, {
        keyword: workflowRunKeyword.value.trim(),
        status: workflowRunStatus.value,
      }))
      if (!isCurrentSession(lease) || !result) return
      workflowRunPage.value = result
    } else {
      const result = await requestForSession(lease, token => consoleApi.prompts(token, 1, PAGE_SIZE, controlKeyword.value.trim()))
      if (!isCurrentSession(lease) || !result) return
      promptPage.value = result
    }
  } catch (cause) {
    await handleApiError(cause, '控制台数据加载失败。', lease)
  } finally {
    if (isCurrentSession(lease)) {
      loading.value = false
    }
  }
}

async function createModel(): Promise<void> {
  const code = window.prompt('模型编码')?.trim()
  const modelName = window.prompt('模型名称')?.trim()
  const baseUrl = window.prompt('受控 HTTPS API 地址（可留空）', '')?.trim()
  if (!code || !modelName) return
  const lease = beginSessionRequest()
  if (!lease) return
  let loadedSecretRefs: ControlResource[] | undefined
  try {
    loadedSecretRefs = secretRefs.value.length || !can('secret:read')
      ? secretRefs.value
      : await requestForSession(lease, consoleApi.secretRefs)
  } catch (cause) {
    controlFailure(cause, 'Secret 引用加载失败。', lease)
    return
  }
  if (!isCurrentSession(lease) || !loadedSecretRefs) return
  const availableSecretRefs = loadedSecretRefs
  secretRefs.value = availableSecretRefs
  const secretName = window.prompt('Secret 引用名称（可留空）', '')?.trim()
  const secretRefId = secretName ? availableSecretRefs.find(item => item.name === secretName)?.id : undefined
  if (secretName && !secretRefId) {
    controlResult.value = '未找到指定的 Secret 引用。'
    return
  }
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.createModel(token, {
      code, displayName: code, modelType: 'CHAT', modelName, baseUrl, secretRefId, enabled: true,
    }))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    controlFailure(cause, '保存失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function createSecretRef(): Promise<void> {
  const name = window.prompt('引用名称')?.trim()
  const type = window.prompt('引用类型（ENV/K8S/KMS）', 'ENV')?.trim()
  const reference = window.prompt('引用定位（提交后不会回显）')?.trim()
  if (!name || !type || !reference) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.createSecretRef(token, { name, secretRefType: type, reference }))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    controlFailure(cause, '保存失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function createPrompt(): Promise<void> {
  const code = window.prompt('Prompt 编码')?.trim()
  const draftContent = window.prompt('草稿内容')
  if (!code || draftContent === null) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.createPrompt(token, { code, displayName: code, draftContent }))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    controlFailure(cause, '保存失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function versionAndPublish(prompt: ControlResource): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    const version = await requestForSession(lease, token => consoleApi.createPromptVersion(token, prompt.id))
    if (!isCurrentSession(lease) || !version) return
    await requestForSession(lease, token => consoleApi.publishPromptVersion(token, version.id))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    controlFailure(cause, '发布失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function testModel(model: ControlResource): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    const result = await requestForSession(lease, token => consoleApi.testModel(token, model.id))
    if (!isCurrentSession(lease) || !result) return
    controlResult.value = `${result.status}: ${result.message}`
    await load()
  } catch (cause) {
    controlFailure(cause, '连接测试失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function createMcpServer(): Promise<void> {
  const code = window.prompt('MCP Server 编码')?.trim()
  const endpoint = window.prompt('受控 HTTPS MCP 地址')?.trim()
  if (!code || !endpoint) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.createMcpServer(token, { code, displayName: code, transport: 'STREAMABLE_HTTP', endpoint, enabled: true }))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    controlFailure(cause, 'MCP Server 保存失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function testMcpServer(server: McpServer): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    const result = await requestForSession(lease, token => consoleApi.testMcpServer(token, server.id))
    if (!isCurrentSession(lease) || !result) return
    controlResult.value = `${result.status}: ${result.message}`
    await load()
  } catch (cause) {
    controlFailure(cause, '连接测试失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function syncMcpServer(server: McpServer): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    const result = await requestForSession(lease, token => consoleApi.syncMcpServer(token, server.id))
    if (!isCurrentSession(lease) || !result) return
    controlResult.value = `同步完成：${result.toolCount} 个 Tool，新增 ${result.createdVersionCount} 个版本。`
    await load()
  } catch (cause) {
    controlFailure(cause, '同步失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function setMcpToolEnabled(tool: McpTool, enabled: boolean): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.setMcpToolEnabled(token, tool.id, enabled))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    if (!isCurrentSession(lease)) return
    if (cause instanceof ConsoleApiError && cause.references.length > 0) {
      const references = cause.references
        .map(reference => `${reference.agentCode || '未知 Agent'} (${reference.toolVersionId || '未知版本'})`)
        .join('、')
      controlResult.value = `${cause.message} 引用方：${references}`
    } else {
      controlFailure(cause, 'Tool 状态更新失败。', lease)
    }
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function debugMcpTool(tool: McpTool): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    const result = await requestForSession(lease, token => consoleApi.debugMcpTool(token, tool.id))
    if (!isCurrentSession(lease) || !result) return
    controlResult.value = `${result.status}: ${result.traceId}`
  } catch (cause) {
    controlFailure(cause, 'Debug 请求失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

function openKnowledgeBaseDialog(knowledgeBase?: KnowledgeBase): void {
  knowledgeBaseForm.value = knowledgeBase
    ? {
        id: knowledgeBase.id,
        code: knowledgeBase.code,
        displayName: knowledgeBase.displayName,
        description: knowledgeBase.description || '',
        status: knowledgeBase.status,
      }
    : { id: '', code: '', displayName: '', description: '', status: 'ACTIVE' }
  knowledgeDialogOpen.value = true
}

async function saveKnowledgeBase(): Promise<void> {
  if (controlSubmitting.value) return
  const lease = beginSessionRequest()
  if (!lease) return
  if (!knowledgeBaseForm.value.code.trim() || !knowledgeBaseForm.value.displayName.trim()) {
    controlResult.value = '请填写知识库编码和名称。'
    return
  }
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    const payload = {
      code: knowledgeBaseForm.value.code.trim(),
      displayName: knowledgeBaseForm.value.displayName.trim(),
      description: knowledgeBaseForm.value.description.trim(),
      status: knowledgeBaseForm.value.status,
    }
    const saved = knowledgeBaseForm.value.id
      ? await requestForSession(lease, token => consoleApi.updateKnowledgeBase(token, knowledgeBaseForm.value.id, payload))
      : await requestForSession(lease, token => consoleApi.createKnowledgeBase(token, payload))
    if (!isCurrentSession(lease) || !saved) return
    knowledgeDialogOpen.value = false
    await load()
    // Re-check the lease after the refresh so a stale save cannot drive
    // navigation or detail fetches with a different administrator session.
    if (!isCurrentSession(lease)) return
    await selectKnowledgeBase(saved)
  } catch (cause) {
    controlFailure(cause, '知识库保存失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function selectKnowledgeBase(knowledgeBase: KnowledgeBase): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  selectedKnowledgeBase.value = knowledgeBase
  selectedKnowledgeDocument.value = null
  knowledgeMatches.value = []
  knowledgeDocumentPage.value = emptyControlResources() as PageResponse<KnowledgeDocument>
  knowledgeJobPage.value = emptyControlResources() as PageResponse<KnowledgeIndexJob>
  await loadKnowledgeDetails(lease)
}

async function loadKnowledgeDetails(existingLease?: SessionLease): Promise<void> {
  const lease = existingLease || beginSessionRequest()
  if (!lease) return
  if (!selectedKnowledgeBase.value) return
  knowledgeLoading.value = true
  try {
    const knowledgeBaseId = selectedKnowledgeBase.value.id
    const [documents, jobs] = await Promise.all([
      requestForSession(lease, token => consoleApi.knowledgeDocuments(token, knowledgeBaseId, knowledgeDocumentPage.value.page, PAGE_SIZE)),
      requestForSession(lease, token => consoleApi.knowledgeIndexJobs(token, knowledgeJobPage.value.page, PAGE_SIZE, { knowledgeBaseId })),
    ])
    if (!isCurrentSession(lease) || !documents || !jobs) return
    knowledgeDocumentPage.value = documents
    knowledgeJobPage.value = jobs
  } catch (cause) {
    controlFailure(cause, '知识库详情加载失败。', lease)
  } finally {
    if (isCurrentSession(lease)) knowledgeLoading.value = false
  }
}

function selectKnowledgeDocument(): void {
  knowledgeDocumentInput.value?.click()
}

async function uploadKnowledgeDocument(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.item(0)
  input.value = ''
  if (!file || !selectedKnowledgeBase.value) return
  const lease = beginSessionRequest()
  if (!lease) return
  if (file.size === 0 || file.size > MAX_KNOWLEDGE_DOCUMENT_BYTES) {
    controlResult.value = '文档为空或超过 10 MB 限制。'
    return
  }
  const contentType = file.type === 'text/markdown' || file.name.toLowerCase().endsWith('.md')
    ? 'text/markdown'
    : file.type === 'text/plain' || file.name.toLowerCase().endsWith('.txt')
      ? 'text/plain'
      : file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')
        ? 'application/pdf'
        : file.type === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' || file.name.toLowerCase().endsWith('.docx')
          ? 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
      : ''
  if (!contentType) {
    controlResult.value = '仅支持 TXT、Markdown、PDF 或 DOCX 文档。'
    return
  }
  controlSubmitting.value = true
  try {
    const payload = {
      name: file.name,
      contentType,
    }
    if (contentType === 'text/plain' || contentType === 'text/markdown') {
      const content = await file.text()
      if (!isCurrentSession(lease) || !selectedKnowledgeBase.value) return
      await requestForSession(lease, token => consoleApi.uploadKnowledgeDocument(token, selectedKnowledgeBase.value!.id, {
        ...payload, content,
      }))
    } else {
      const contentBase64 = await fileAsBase64(file)
      if (!isCurrentSession(lease) || !selectedKnowledgeBase.value) return
      await requestForSession(lease, token => consoleApi.uploadKnowledgeDocument(token, selectedKnowledgeBase.value!.id, {
        ...payload, contentBase64,
      }))
    }
    if (!isCurrentSession(lease)) return
    controlResult.value = '文档已进入索引队列。'
    await loadKnowledgeDetails(lease)
  } catch (cause) {
    controlFailure(cause, '文档上传失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function fileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(reader.error || new Error('文档读取失败。'))
    reader.onload = () => {
      if (typeof reader.result !== 'string') {
        reject(new Error('文档读取失败。'))
        return
      }
      const separator = reader.result.indexOf(',')
      if (separator < 0) {
        reject(new Error('文档内容编码失败。'))
        return
      }
      resolve(reader.result.slice(separator + 1))
    }
    reader.readAsDataURL(file)
  })
}

async function reindexKnowledgeDocument(document: KnowledgeDocument): Promise<void> {
  if (controlSubmitting.value) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.reindexKnowledgeDocument(token, document.id))
    if (!isCurrentSession(lease)) return
    controlResult.value = '重建索引任务已入队。'
    await loadKnowledgeDetails(lease)
  } catch (cause) {
    controlFailure(cause, '重建索引失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function deleteKnowledgeDocument(document: KnowledgeDocument): Promise<void> {
  if (controlSubmitting.value) return
  const lease = beginSessionRequest()
  if (!lease) return
  try {
    await ElMessageBox.confirm(
      `将删除文档“${document.name}”及其当前索引数据，此操作不可撤销。`,
      '删除文档',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  if (!isCurrentSession(lease)) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    await requestForSession(lease, token => consoleApi.deleteKnowledgeDocument(token, document.id))
    if (!isCurrentSession(lease)) return
    knowledgeMatches.value = []
    if (selectedKnowledgeDocument.value?.id === document.id) {
      knowledgeChunksOpen.value = false
      selectedKnowledgeDocument.value = null
      knowledgeChunkPage.value = emptyControlResources() as PageResponse<KnowledgeChunk>
    }
    controlResult.value = '文档已删除，索引与源对象将由受控补偿流程清理。'
    await loadKnowledgeDetails(lease)
  } catch (cause) {
    controlFailure(cause, '文档删除失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function retryKnowledgeJob(job: KnowledgeIndexJob): Promise<void> {
  if (controlSubmitting.value) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.retryKnowledgeIndexJob(token, job.id))
    if (!isCurrentSession(lease)) return
    controlResult.value = '失败任务已重新进入索引队列。'
    await loadKnowledgeDetails(lease)
  } catch (cause) {
    controlFailure(cause, '重试索引任务失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function runKnowledgeRetrieval(): Promise<void> {
  if (!selectedKnowledgeBase.value || !knowledgeRetrievalQuery.value.trim()) {
    controlResult.value = '请输入检索问题。'
    return
  }
  const lease = beginSessionRequest()
  if (!lease) return
  knowledgeRetrievalLoading.value = true
  controlResult.value = ''
  try {
    const result = await requestForSession(lease, token => consoleApi.retrieveKnowledge(token, selectedKnowledgeBase.value!.id, {
      query: knowledgeRetrievalQuery.value.trim(),
      topK: knowledgeTopK.value,
      threshold: knowledgeThreshold.value,
    }))
    if (!isCurrentSession(lease) || !result) return
    knowledgeMatches.value = result
  } catch (cause) {
    controlFailure(cause, '检索测试失败。', lease)
  } finally {
    if (isCurrentSession(lease)) knowledgeRetrievalLoading.value = false
  }
}

async function openKnowledgeChunks(document: KnowledgeDocument): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  selectedKnowledgeDocument.value = document
  knowledgeChunkPage.value = emptyControlResources() as PageResponse<KnowledgeChunk>
  knowledgeChunksOpen.value = true
  await loadKnowledgeChunks(lease)
}

async function loadKnowledgeChunks(existingLease?: SessionLease): Promise<void> {
  const lease = existingLease || beginSessionRequest()
  if (!lease) return
  if (!selectedKnowledgeDocument.value) return
  knowledgeLoading.value = true
  try {
    const result = await requestForSession(lease, token => consoleApi.knowledgeChunks(
      token,
      selectedKnowledgeDocument.value!.id,
      knowledgeChunkPage.value.page,
      PAGE_SIZE,
    ))
    if (!isCurrentSession(lease) || !result) return
    knowledgeChunkPage.value = result
  } catch (cause) {
    controlFailure(cause, '切片预览加载失败。', lease)
  } finally {
    if (isCurrentSession(lease)) knowledgeLoading.value = false
  }
}

async function setKnowledgeChunkEnabled(chunk: KnowledgeChunk, enabled: boolean): Promise<void> {
  if (!selectedKnowledgeDocument.value || controlSubmitting.value) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.updateKnowledgeChunk(token, selectedKnowledgeDocument.value!.id, { chunkId: chunk.id, enabled }))
    if (!isCurrentSession(lease)) return
    knowledgeMatches.value = []
    await loadKnowledgeChunks(lease)
  } catch (cause) {
    controlFailure(cause, '切片状态更新失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function searchKnowledgeBases(): Promise<void> {
  knowledgeBasePage.value.page = 1
  await load()
}

async function searchApplications(): Promise<void> {
  applicationPage.value.page = 1
  await load()
}

async function changeApplicationPage(page: number): Promise<void> {
  applicationPage.value.page = page
  await load()
}

async function createApplication(): Promise<void> {
  const code = window.prompt('应用编码（唯一）')?.trim()
  const displayName = window.prompt('应用名称')?.trim()
  if (!code || !displayName) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    const saved = await requestForSession(lease, token => consoleApi.createApplication(token, {
      code,
      displayName,
      status: 'DRAFT',
    }))
    if (!isCurrentSession(lease) || !saved) return
    controlResult.value = '应用已创建。'
    applicationKeyword.value = ''
    await load()
  } catch (cause) {
    controlFailure(cause, '应用创建失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function archiveApplication(application: AgentApplication): Promise<void> {
  if (application.status === 'ARCHIVED') return
  try {
    await ElMessageBox.confirm(`归档应用 ${application.code}？归档后不能发布新版本；仍被有效 API Key 引用时会被拒绝。`, '确认归档', { type: 'warning' })
  } catch {
    return
  }
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    await requestForSession(lease, token => consoleApi.archiveApplication(token, application.id))
    if (!isCurrentSession(lease)) return
    controlResult.value = '应用已归档。'
    await load()
  } catch (cause) {
    controlFailure(cause, '归档失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function openApplicationDrawer(application: AgentApplication): Promise<void> {
  selectedApplication.value = application
  applicationVersions.value = []
  applicationRecords.value = []
  applicationApiKeys.value = []
  applicationBindings.value = {}
  applicationDrawerOpen.value = true
  detailError.value = ''
  const lease = beginSessionRequest()
  if (!lease) return
  applicationDetailLoading.value = true
  try {
    const versions = await requestForSession(lease, token => consoleApi.applicationVersions(token, application.id))
    const records = await requestForSession(lease, token => consoleApi.applicationPublishRecords(token, application.id))
    const keys = await requestForSession(lease, token => consoleApi.applicationApiKeys(token, application.id))
    if (!isCurrentSession(lease)) return
    applicationVersions.value = versions || []
    applicationRecords.value = records || []
    applicationApiKeys.value = keys || []
    const bindings: Record<string, AgentAppBinding[]> = {}
    for (const version of applicationVersions.value) {
      const items = await requestForSession(lease, token => consoleApi.applicationVersionBindings(token, application.id, version.id))
      if (!isCurrentSession(lease)) return
      bindings[version.id] = items || []
    }
    applicationBindings.value = bindings
  } catch (cause) {
    detailError.value = cause instanceof Error ? cause.message : '应用详情加载失败。'
  } finally {
    if (isCurrentSession(lease)) applicationDetailLoading.value = false
  }
}

async function createApplicationVersion(): Promise<void> {
  const application = selectedApplication.value
  if (!application) return
  const modelCode = window.prompt('模型编码（须与已连接模型一致，例如 model-demo）')?.trim()
  const promptId = window.prompt('Prompt ID')?.trim()
  const promptVersionId = window.prompt('Prompt 版本 ID（须为已发布版本）')?.trim()
  const knowledgeBaseId = window.prompt('知识库 ID（可留空）', '')?.trim()
  if (!modelCode || !promptId || !promptVersionId) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    await requestForSession(lease, token => consoleApi.createApplicationVersion(token, application.id, {
      modelCode,
      promptId,
      promptVersionId,
      knowledgeBaseId: knowledgeBaseId || null,
      bindings: [],
    }))
    if (!isCurrentSession(lease)) return
    controlResult.value = '草稿版本已创建。'
    await openApplicationDrawer(application)
  } catch (cause) {
    controlFailure(cause, '版本创建失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function validateApplicationVersion(version: AgentApplicationVersion): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    const result = await requestForSession(lease, token => consoleApi.validateApplicationVersion(token, version.id))
    if (!isCurrentSession(lease) || !result) return
    controlResult.value = result.valid
      ? `版本 ${version.version} 校验通过。`
      : `版本 ${version.version} 校验失败：${result.issues.map(item => `${item.resourceType}/${item.resourceId}: ${item.message}`).join('；')}`
  } catch (cause) {
    controlFailure(cause, '版本校验失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function validateAndPublish(version: AgentApplicationVersion): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    const validation = await requestForSession(lease, token => consoleApi.validateApplicationVersion(token, version.id))
    if (!isCurrentSession(lease)) return
    if (!validation?.valid) {
      controlResult.value = `发布被拒绝：${(validation?.issues || []).map(item => `${item.resourceType}/${item.resourceId}: ${item.message}`).join('；')}`
      return
    }
    await requestForSession(lease, token => consoleApi.publishApplicationVersion(token, version.id))
    if (!isCurrentSession(lease)) return
    controlResult.value = `版本 ${version.version} 已发布（不可变）。`
    if (selectedApplication.value) {
      await openApplicationDrawer(selectedApplication.value)
    }
    await load()
  } catch (cause) {
    controlFailure(cause, '版本发布失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function rollbackApplication(version: AgentApplicationVersion): Promise<void> {
  const application = selectedApplication.value
  if (!application) return
  try {
    await ElMessageBox.confirm(`将应用 ${application.code} 回滚到版本 ${version.version}？将记录新的回滚审计。`, '确认回滚', { type: 'warning' })
  } catch {
    return
  }
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    await requestForSession(lease, token => consoleApi.rollbackApplication(token, application.id, version.id))
    if (!isCurrentSession(lease)) return
    controlResult.value = `已回滚到版本 ${version.version}。`
    await openApplicationDrawer(application)
    await load()
  } catch (cause) {
    controlFailure(cause, '回滚失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function createApiKey(): Promise<void> {
  const application = selectedApplication.value
  if (!application) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    const created = await requestForSession(lease, token => consoleApi.createApplicationApiKey(token, application.id, {}))
    if (!isCurrentSession(lease) || !created) return
    controlResult.value = `API Key 已创建，明文仅本次展示：${created.key}`
    await openApplicationDrawer(application)
  } catch (cause) {
    controlFailure(cause, 'API Key 创建失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function rotateApiKey(key: AgentAppApiKey): Promise<void> {
  const application = selectedApplication.value
  if (!application) return
  try {
    await ElMessageBox.confirm(`轮换 API Key ${key.keyPrefix}？旧 Key 将立即失效。`, '确认轮换', { type: 'warning' })
  } catch {
    return
  }
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    const created = await requestForSession(lease, token => consoleApi.rotateApplicationApiKey(token, application.id, key.id))
    if (!isCurrentSession(lease) || !created) return
    controlResult.value = `新 API Key 明文仅本次展示：${created.key}`
    await openApplicationDrawer(application)
  } catch (cause) {
    controlFailure(cause, 'API Key 轮换失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function revokeApiKey(key: AgentAppApiKey): Promise<void> {
  const application = selectedApplication.value
  if (!application) return
  try {
    await ElMessageBox.confirm(`撤销 API Key ${key.keyPrefix}？撤销后立即失效。`, '确认撤销', { type: 'warning' })
  } catch {
    return
  }
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    await requestForSession(lease, token => consoleApi.revokeApplicationApiKey(token, application.id, key.id))
    if (!isCurrentSession(lease)) return
    controlResult.value = 'API Key 已撤销并立即失效。'
    await openApplicationDrawer(application)
  } catch (cause) {
    controlFailure(cause, 'API Key 撤销失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function showOpenApiSpec(): Promise<void> {
  const application = selectedApplication.value
  if (!application) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  controlResult.value = ''
  try {
    const spec = await requestForSession(lease, token => consoleApi.applicationOpenApiSpec(token, application.id))
    if (!isCurrentSession(lease)) return
    openApiSpec.value = spec || null
    openApiDialogOpen.value = true
  } catch (cause) {
    controlFailure(cause, 'OpenAPI 加载失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

function versionLabel(application: AgentApplication): string {
  const version = applicationVersions.value.find(item => item.id === application.currentVersionId)
  return version ? `v${version.version}` : '-'
}

function promptLabel(promptVersionId: string): string {
  return `pv:${promptVersionId.slice(0, 8)}`
}

const openApiSpecText = computed(() => {
  try {
    return JSON.stringify(openApiSpec.value, null, 2)
  } catch {
    return ''
  }
})

async function changeKnowledgeBasePage(page: number): Promise<void> {
  knowledgeBasePage.value.page = page
  await load()
}

async function changeKnowledgeDocumentPage(page: number): Promise<void> {
  knowledgeDocumentPage.value.page = page
  await loadKnowledgeDetails()
}

async function changeKnowledgeJobPage(page: number): Promise<void> {
  knowledgeJobPage.value.page = page
  await loadKnowledgeDetails()
}

async function changeKnowledgeChunkPage(page: number): Promise<void> {
  knowledgeChunkPage.value.page = page
  await loadKnowledgeChunks()
}

async function changeMcpServerPage(page: number): Promise<void> {
  mcpServerPage.value.page = page
  await load()
}

async function changeMcpToolPage(page: number): Promise<void> {
  mcpToolPage.value.page = page
  await load()
}

async function searchTraces(): Promise<void> {
  tracePage.value.page = 1
  await load()
}

async function changeTracePage(page: number): Promise<void> {
  tracePage.value.page = page
  await load()
}

async function changeDatasetPage(page: number): Promise<void> {
  datasetPage.value.page = page
  await load()
}

async function changeEvaluatorPage(page: number): Promise<void> {
  evaluatorPage.value.page = page
  await load()
}

async function changeExperimentPage(page: number): Promise<void> {
  experimentPage.value.page = page
  await load()
}

async function openTraceDetail(traceId: string): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  evalLoading.value = true
  try {
    const detail = await requestForSession(lease, token => consoleApi.traceDetail(token, traceId))
    if (!isCurrentSession(lease) || !detail) return
    selectedTrace.value = detail
    traceDrawerOpen.value = true
  } catch (cause) {
    controlFailure(cause, 'Trace 详情加载失败。', lease)
  } finally {
    if (isCurrentSession(lease)) evalLoading.value = false
  }
}

async function createDataset(): Promise<void> {
  const code = window.prompt('数据集编码')?.trim()
  const displayName = window.prompt('数据集名称')?.trim()
  if (!code || !displayName) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.createEvalDataset(token, { code, displayName }))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    controlFailure(cause, '数据集创建失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function openDatasetDrawer(dataset: EvalDataset): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  evalLoading.value = true
  try {
    const versions = await requestForSession(lease, token => consoleApi.evalDatasetVersions(token, dataset.id))
    if (!isCurrentSession(lease) || !versions) return
    selectedDataset.value = dataset
    datasetVersions.value = versions
    const current = versions.find(version => version.id === dataset.currentVersionId) || versions[versions.length - 1]
    if (current) {
      const cases = await requestForSession(lease, token => consoleApi.evalCases(token, current.id, 1, PAGE_SIZE, {}))
      if (!isCurrentSession(lease) || !cases) return
      datasetVersionCases.value = cases
      datasetCasePage.value = 1
    } else {
      datasetVersionCases.value = emptyControlResources() as PageResponse<EvalCase>
    }
    datasetDrawerOpen.value = true
  } catch (cause) {
    controlFailure(cause, '数据集详情加载失败。', lease)
  } finally {
    if (isCurrentSession(lease)) evalLoading.value = false
  }
}

async function loadDatasetCases(): Promise<void> {
  const version = datasetVersions.value[datasetVersions.value.length - 1]
  if (!version) return
  const lease = beginSessionRequest()
  if (!lease) return
  evalLoading.value = true
  try {
    const cases = await requestForSession(lease, token => consoleApi.evalCases(token, version.id, datasetCasePage.value, PAGE_SIZE, {
      category: datasetCaseCategory.value,
    }))
    if (!isCurrentSession(lease) || !cases) return
    datasetVersionCases.value = cases
  } catch (cause) {
    controlFailure(cause, '用例加载失败。', lease)
  } finally {
    if (isCurrentSession(lease)) evalLoading.value = false
  }
}

async function changeDatasetCasePage(page: number): Promise<void> {
  datasetCasePage.value = page
  await loadDatasetCases()
}

async function addDatasetCase(): Promise<void> {
  const version = datasetVersions.value[datasetVersions.value.length - 1]
  if (!version) return
  const caseKey = window.prompt('用例键（唯一）')?.trim()
  const category = window.prompt('分类（如 intent-route / confirmation-gate）')?.trim() || 'manual'
  const inputText = window.prompt('输入文本')?.trim()
  if (!caseKey || inputText === null) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.addEvalCase(token, version.id, {
      caseKey,
      category,
      input: { text: inputText },
      expected: JSON.parse(window.prompt('期望 JSON（如 {"agentCode":"hotel"}）', '{}') || '{}'),
    }))
    if (!isCurrentSession(lease)) return
    await loadDatasetCases()
  } catch (cause) {
    controlFailure(cause, '用例创建失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function importDatasetCases(): Promise<void> {
  const version = datasetVersions.value[datasetVersions.value.length - 1]
  if (!version) return
  const raw = window.prompt('批量导入 JSON 数组（caseKey/category/input/expected）')
  if (!raw) return
  let cases: Record<string, unknown>[]
  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) throw new Error('必须是数组')
    cases = parsed
  } catch {
    controlResult.value = 'JSON 解析失败。'
    return
  }
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    const result = await requestForSession(lease, token => consoleApi.importEvalCases(token, version.id, cases))
    if (!isCurrentSession(lease) || !result) return
    controlResult.value = `已导入 ${result.imported} 条用例。`
    await loadDatasetCases()
  } catch (cause) {
    controlFailure(cause, '批量导入失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function generateCaseFromTrace(): Promise<void> {
  const version = datasetVersions.value[datasetVersions.value.length - 1]
  if (!version) return
  const traceId = window.prompt('Trace ID（来自任务或 Trace 列表）')?.trim()
  const inputText = window.prompt('用例输入文本（Trace 不保存消息原文，需补充）')?.trim()
  if (!traceId) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.generateEvalCaseFromTrace(token, version.id, {
      traceId,
      inputText: inputText || '(trace 生成候选，请补充输入)',
      category: 'trace-generated',
    }))
    if (!isCurrentSession(lease)) return
    controlResult.value = '已从 Trace 生成候选用例，请核对期望后运行。'
    await loadDatasetCases()
  } catch (cause) {
    controlFailure(cause, 'Trace 生成失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function createEvaluator(): Promise<void> {
  const code = window.prompt('评估器编码')?.trim()
  const displayName = window.prompt('评估器名称')?.trim()
  const evaluatorType = window.prompt('类型（INTENT_ROUTE/PARAM_EXTRACTION/CLARIFICATION/DENY/CONFIRMATION_GATE/TOOL_SELECTION/KNOWLEDGE_CITATION/RULE/STRUCTURE/SENSITIVE/LLM_JUDGE）')?.trim()
  if (!code || !displayName || !evaluatorType) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.createEvalEvaluator(token, {
      code,
      displayName,
      evaluatorType,
      config: {},
    }))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    controlFailure(cause, '评估器创建失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function createEvaluatorVersion(evaluator: EvalEvaluator): Promise<void> {
  const raw = window.prompt(`评估器版本配置 JSON（${evaluator.code}）`) || '{}'
  let config: Record<string, unknown>
  try {
    config = JSON.parse(raw)
  } catch {
    controlResult.value = '配置 JSON 解析失败。'
    return
  }
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.createEvalEvaluatorVersion(token, evaluator.id, { config }))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    controlFailure(cause, '评估器版本创建失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function createExperiment(): Promise<void> {
  const code = window.prompt('实验编码')?.trim()
  const displayName = window.prompt('实验名称')?.trim()
  const datasetVersionId = window.prompt('数据集版本 ID')?.trim()
  const agentVersionId = window.prompt('Agent 发布版本 ID')?.trim()
  const evaluatorVersionIds = window.prompt('评估器版本 ID（逗号分隔）')?.trim()
  if (!code || !displayName || !datasetVersionId || !agentVersionId || !evaluatorVersionIds) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.createEvalExperiment(token, {
      code,
      displayName,
      datasetVersionId,
      agentVersionId,
      evaluatorVersionIds: evaluatorVersionIds.split(',').map(item => item.trim()).filter(Boolean),
    }))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    controlFailure(cause, '实验创建失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function startExperiment(experiment: EvalExperiment): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.startEvalExperiment(token, experiment.id))
    if (!isCurrentSession(lease)) return
    controlResult.value = '实验已启动，后台 Worker 将按批次执行。'
    await load()
  } catch (cause) {
    controlFailure(cause, '实验启动失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function stopExperiment(experiment: EvalExperiment): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.stopEvalExperiment(token, experiment.id))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    controlFailure(cause, '实验停止失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function retryExperiment(experiment: EvalExperiment): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.retryEvalExperiment(token, experiment.id))
    if (!isCurrentSession(lease)) return
    controlResult.value = '实验已重置未通过用例并重新运行。'
    await load()
  } catch (cause) {
    controlFailure(cause, '实验重试失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function openExperimentRuns(experiment: EvalExperiment): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  evalLoading.value = true
  try {
    const runs = await requestForSession(lease, token => consoleApi.evalExperimentRuns(token, experiment.id, 1, PAGE_SIZE))
    const summary = await requestForSession(lease, token => consoleApi.evalExperimentSummary(token, experiment.id))
    if (!isCurrentSession(lease) || !runs) return
    selectedExperiment.value = experiment
    experimentRuns.value = runs
    experimentRunsPage.value = 1
    evalSummary.value = summary || null
    experimentRunsOpen.value = true
  } catch (cause) {
    controlFailure(cause, '实验结果加载失败。', lease)
  } finally {
    if (isCurrentSession(lease)) evalLoading.value = false
  }
}

async function changeExperimentRunsPage(page: number): Promise<void> {
  if (!selectedExperiment.value) return
  const lease = beginSessionRequest()
  if (!lease) return
  try {
    const runs = await requestForSession(lease, token => consoleApi.evalExperimentRuns(token, selectedExperiment.value!.id, page, PAGE_SIZE))
    if (!isCurrentSession(lease) || !runs) return
    experimentRuns.value = runs
    experimentRunsPage.value = page
  } catch (cause) {
    controlFailure(cause, '实验结果加载失败。', lease)
  }
}

function emptyWorkflowDsl(code: string): string {
  return JSON.stringify({
    schemaVersion: '1.0',
    code,
    displayName: '新建流程',
    nodes: [
      { id: 'start', type: 'START', displayName: '开始', config: {} },
      { id: 'end', type: 'END', displayName: '结束', config: {} },
    ],
    edges: [{ from: 'start', to: 'end' }],
  }, null, 2)
}

async function searchWorkflows(): Promise<void> {
  workflowPage.value = emptyControlResources() as PageResponse<WorkflowDefinition>
  await load()
}

async function changeWorkflowPage(page: number): Promise<void> {
  workflowPage.value = { ...workflowPage.value, page }
  await load()
}

async function createWorkflow(): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    const created = await requestForSession(lease, token => consoleApi.createWorkflow(token, {
      code: workflowForm.code.trim(),
      displayName: workflowForm.displayName.trim(),
      description: workflowForm.description.trim(),
    }))
    if (!isCurrentSession(lease) || !created) return
    workflowDialogOpen.value = false
    workflowForm.code = ''
    workflowForm.displayName = ''
    workflowForm.description = ''
    await load()
    openWorkflowEditor(created)
  } catch (cause) {
    controlFailure(cause, '工作流创建失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function openWorkflowEditor(workflow: WorkflowDefinition): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  workflowVersionLoading.value = true
  workflowValidation.value = null
  try {
    const versions = await requestForSession(lease, token => consoleApi.workflowVersions(token, workflow.id))
    if (!isCurrentSession(lease) || !versions) return
    selectedWorkflow.value = workflow
    workflowVersions.value = versions
    const latest = versions[versions.length - 1]
    workflowDslJson.value = latest
      ? JSON.stringify(latest.dsl, null, 2)
      : emptyWorkflowDsl(workflow.code)
    workflowBindingsJson.value = latest
      ? JSON.stringify(latest.resourceBindings || {}, null, 2)
      : '{}'
    workflowEditorOpen.value = true
  } catch (cause) {
    controlFailure(cause, '工作流详情加载失败。', lease)
  } finally {
    if (isCurrentSession(lease)) workflowVersionLoading.value = false
  }
}

async function saveWorkflowVersion(): Promise<void> {
  if (!selectedWorkflow.value) return
  const lease = beginSessionRequest()
  if (!lease) return
  workflowVersionLoading.value = true
  workflowValidation.value = null
  try {
    let dsl: unknown
    try {
      dsl = JSON.parse(workflowDslJson.value)
    } catch {
      throw new Error('DSL JSON 不是合法 JSON。')
    }
    let resourceBindings: Record<string, unknown>
    try {
      resourceBindings = JSON.parse(workflowBindingsJson.value) as Record<string, unknown>
    } catch {
      throw new Error('资源绑定 JSON 不是合法 JSON。')
    }
    const version = await requestForSession(lease, token => consoleApi.createWorkflowVersion(token, selectedWorkflow.value!.id, {
      dsl,
      resourceBindings,
      description: `版本 ${workflowVersions.value.length + 1}`,
    }))
    if (!isCurrentSession(lease) || !version) return
    workflowVersions.value = [...workflowVersions.value, version]
    workflowDslJson.value = JSON.stringify(version.dsl, null, 2)
    workflowBindingsJson.value = JSON.stringify(version.resourceBindings || {}, null, 2)
    workflowValidation.value = { versionId: version.id, valid: false, issues: [] }
    await validateWorkflowVersion(version)
  } catch (cause) {
    controlFailure(cause, '工作流版本保存失败。', lease)
  } finally {
    if (isCurrentSession(lease)) workflowVersionLoading.value = false
  }
}

async function validateWorkflowVersion(version: WorkflowVersionView): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  workflowVersionLoading.value = true
  try {
    const result = await requestForSession(lease, token => consoleApi.validateWorkflowVersion(token, version.id))
    if (isCurrentSession(lease) && result) {
      workflowValidation.value = result
    }
  } catch (cause) {
    controlFailure(cause, '工作流版本校验失败。', lease)
  } finally {
    if (isCurrentSession(lease)) workflowVersionLoading.value = false
  }
}

async function publishWorkflowVersion(version: WorkflowVersionView): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  workflowVersionLoading.value = true
  try {
    const published = await requestForSession(lease, token => consoleApi.publishWorkflowVersion(token, version.id))
    if (!isCurrentSession(lease) || !published) return
    const refreshed = await requestForSession(lease, token => consoleApi.workflowVersions(token, published.workflowId))
    if (isCurrentSession(lease) && refreshed) {
      workflowVersions.value = refreshed
      selectedWorkflow.value = await requestForSession(lease, token => consoleApi.workflow(token, published.workflowId))
        || selectedWorkflow.value
    }
  } catch (cause) {
    controlFailure(cause, '工作流版本发布失败。', lease)
  } finally {
    if (isCurrentSession(lease)) workflowVersionLoading.value = false
  }
}

async function rollbackWorkflow(): Promise<void> {
  if (!selectedWorkflow.value) return
  const lease = beginSessionRequest()
  if (!lease) return
  workflowVersionLoading.value = true
  try {
    await requestForSession(lease, token => consoleApi.rollbackWorkflow(token, selectedWorkflow.value!.id))
    if (!isCurrentSession(lease)) return
    const refreshed = await requestForSession(lease, token => consoleApi.workflowVersions(token, selectedWorkflow.value!.id))
    if (isCurrentSession(lease) && refreshed) {
      workflowVersions.value = refreshed
    }
  } catch (cause) {
    controlFailure(cause, '工作流回滚失败。', lease)
  } finally {
    if (isCurrentSession(lease)) workflowVersionLoading.value = false
  }
}

async function archiveWorkflow(): Promise<void> {
  if (!selectedWorkflow.value) return
  const confirmed = await ElMessageBox.confirm(`确定归档工作流「${selectedWorkflow.value.displayName}」？归档后不能创建新版本。`, '归档确认', {
    confirmButtonText: '归档',
    cancelButtonText: '取消',
    type: 'warning',
  }).catch(() => false)
  if (!confirmed) return
  const lease = beginSessionRequest()
  if (!lease) return
  try {
    await requestForSession(lease, token => consoleApi.archiveWorkflow(token, selectedWorkflow.value!.id))
    if (!isCurrentSession(lease)) return
    workflowEditorOpen.value = false
    await load()
  } catch (cause) {
    controlFailure(cause, '工作流归档失败。', lease)
  }
}

function workflowVersionNo(workflow: WorkflowDefinition): number | string {
  const version = workflowVersions.value.find(item => item.id === workflow.currentVersionId)
  return version ? version.versionNo : '-'
}

async function rollbackWorkflowFromList(workflow: WorkflowDefinition): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定回滚工作流「${workflow.displayName}」到上一个已发布版本？`, '回滚确认', {
    confirmButtonText: '回滚',
    cancelButtonText: '取消',
    type: 'warning',
  }).catch(() => false)
  if (!confirmed) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    await requestForSession(lease, token => consoleApi.rollbackWorkflow(token, workflow.id))
    if (!isCurrentSession(lease)) return
    await load()
  } catch (cause) {
    controlFailure(cause, '工作流回滚失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function archiveWorkflowFromList(workflow: WorkflowDefinition): Promise<void> {
  selectedWorkflow.value = workflow
  await archiveWorkflow()
}

async function debugRunVersion(version: WorkflowVersionView): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    const run = await requestForSession(lease, token => consoleApi.startWorkflowRun(token, {
      versionId: version.id,
      initialVariables: {},
    }))
    if (!isCurrentSession(lease) || !run) return
    controlResult.value = `运行已启动：${run.id}`
    active.value = 'workflowRuns'
    workflowRunPage.value = emptyControlResources() as PageResponse<WorkflowRunView>
    await load()
    void openWorkflowRun(run)
  } catch (cause) {
    controlFailure(cause, '工作流运行启动失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function searchWorkflowRuns(): Promise<void> {
  workflowRunPage.value = emptyControlResources() as PageResponse<WorkflowRunView>
  await load()
}

async function changeWorkflowRunPage(page: number): Promise<void> {
  workflowRunPage.value = { ...workflowRunPage.value, page }
  await load()
}

async function openWorkflowRun(run: WorkflowRunView): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  detailLoading.value = true
  detailError.value = ''
  try {
    const [detail, nodeRuns] = await Promise.all([
      requestForSession(lease, token => consoleApi.workflowRun(token, run.id)),
      requestForSession(lease, token => consoleApi.workflowNodeRuns(token, run.id)),
    ])
    if (!isCurrentSession(lease) || !detail) return
    selectedWorkflowRun.value = detail
    workflowNodeRuns.value = nodeRuns || []
    workflowRunDrawerOpen.value = true
    disconnectWorkflowEvents()
    workflowEvents.value = []
    void connectWorkflowEvents(detail.id)
  } catch (cause) {
    detailError.value = cause instanceof Error ? cause.message : '工作流运行详情加载失败。'
    controlFailure(cause, '工作流运行详情加载失败。', lease)
  } finally {
    if (isCurrentSession(lease)) detailLoading.value = false
  }
}

async function connectWorkflowEvents(runId: string): Promise<void> {
  const token = sessionToken.value
  if (!token) return
  const controller = new AbortController()
  workflowEventAbort.value = controller
  workflowEventStreaming.value = true
  let lastSequence = 0
  try {
    await streamWorkflowEvents(token, runId, 0, (event) => {
      if (!isCurrentSession(undefined)) return
      workflowEvents.value = [...workflowEvents.value, event]
      lastSequence = Math.max(lastSequence, event.sequence)
    }, controller.signal)
  } catch {
    // The stream closes on terminal state; a network error only stops live updates.
  } finally {
    workflowEventStreaming.value = false
    void refreshWorkflowRun(runId)
  }
}

function disconnectWorkflowEvents(): void {
  workflowEventAbort.value?.abort()
  workflowEventAbort.value = null
  workflowEventStreaming.value = false
}

async function refreshWorkflowRun(runId: string): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  try {
    const [detail, nodeRuns] = await Promise.all([
      requestForSession(lease, token => consoleApi.workflowRun(token, runId)),
      requestForSession(lease, token => consoleApi.workflowNodeRuns(token, runId)),
    ])
    if (!isCurrentSession(lease) || !detail) return
    selectedWorkflowRun.value = detail
    workflowNodeRuns.value = nodeRuns || []
  } catch {
    // A refresh failure must not interrupt the open drawer.
  }
}

async function resumeWorkflowRun(): Promise<void> {
  if (!selectedWorkflowRun.value) return
  await runWorkflowAction(token => consoleApi.resumeWorkflowRun(token, selectedWorkflowRun.value!.id), '运行恢复失败。')
}

async function stopWorkflowRun(): Promise<void> {
  if (!selectedWorkflowRun.value) return
  await runWorkflowAction(token => consoleApi.stopWorkflowRun(token, selectedWorkflowRun.value!.id), '运行停止失败。')
}

async function retryWorkflowRun(): Promise<void> {
  if (!selectedWorkflowRun.value) return
  await runWorkflowAction(token => consoleApi.retryWorkflowRun(token, selectedWorkflowRun.value!.id), '运行重试失败。')
}

async function runWorkflowAction(action: (token: string) => Promise<WorkflowRunView>, failureMessage: string): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    const detail = await requestForSession(lease, action)
    if (!isCurrentSession(lease) || !detail) return
    selectedWorkflowRun.value = detail
    workflowEvents.value = [...workflowEvents.value, ...(detail.events || [])]
    await refreshWorkflowRun(detail.id)
    if (detail.status === 'PAUSED') {
      disconnectWorkflowEvents()
      workflowEvents.value = []
      void connectWorkflowEvents(detail.id)
    }
    await load()
  } catch (cause) {
    controlFailure(cause, failureMessage, lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function submitWorkflowInput(): Promise<void> {
  if (!selectedWorkflowRun.value || !selectedWorkflowRun.value.currentNodeId) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    let values: Record<string, unknown>
    try {
      values = JSON.parse(workflowInputValues.value) as Record<string, unknown>
    } catch {
      throw new Error('输入值必须是合法 JSON 对象。')
    }
    const detail = await requestForSession(lease, token => consoleApi.submitWorkflowInput(token, selectedWorkflowRun.value!.id, {
      nodeId: selectedWorkflowRun.value!.currentNodeId,
      values,
    }))
    if (!isCurrentSession(lease) || !detail) return
    workflowInputOpen.value = false
    workflowInputValues.value = '{}'
    selectedWorkflowRun.value = detail
    await refreshWorkflowRun(detail.id)
  } catch (cause) {
    controlFailure(cause, '工作流输入提交失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

async function confirmWorkflowNode(decision: 'CONFIRMED' | 'REJECTED'): Promise<void> {
  if (!selectedWorkflowRun.value) return
  const lease = beginSessionRequest()
  if (!lease) return
  controlSubmitting.value = true
  try {
    const detail = await requestForSession(lease, token => consoleApi.confirmWorkflowNode(token, selectedWorkflowRun.value!.id, {
      nodeId: workflowConfirmNodeId.value,
      confirmationVersion: workflowConfirmVersion.value,
      decision,
    }))
    if (!isCurrentSession(lease) || !detail) return
    workflowConfirmOpen.value = false
    selectedWorkflowRun.value = detail
    await refreshWorkflowRun(detail.id)
  } catch (cause) {
    controlFailure(cause, '工作流确认提交失败。', lease)
  } finally {
    if (isCurrentSession(lease)) controlSubmitting.value = false
  }
}

function openWorkflowInput(): void {
  workflowInputValues.value = '{}'
  workflowInputOpen.value = true
}

function openWorkflowConfirm(nodeRun: WorkflowNodeRunView): void {
  workflowConfirmNodeId.value = nodeRun.nodeId
  workflowConfirmVersion.value = nodeRun.confirmationVersion
  workflowConfirmOpen.value = true
}

function terminalWorkflowStatus(status: string): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'STOPPED'
}

function workflowNodeTagType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'WAITING_CONFIRMATION' || status === 'WAITING_INPUT') return 'warning'
  return 'info'
}

function can(permission: string): boolean {
  return principal.value?.permissions.includes(permission) === true
}

function navigate(value: ConsoleSection): void {
  active.value = value
  void load()
}

function resetConsoleState(): void {
  principal.value = null
  active.value = 'overview'
  error.value = ''
  loading.value = false
  detailLoading.value = false
  detailError.value = ''
  overview.value = null
  agents.value = []
  config.value = null
  conversationPage.value = emptyConversations()
  conversationQuery.value = ''
  taskPage.value = emptyTasks()
  taskQuery.value = ''
  taskStatus.value = ''
  taskActionCode.value = ''
  selectedConversation.value = null
  conversationEvents.value = { items: [], nextSequence: 0, hasMore: false }
  conversationDrawerOpen.value = false
  selectedTask.value = null
  taskDrawerOpen.value = false
  modelPage.value = emptyControlResources()
  promptPage.value = emptyControlResources()
  mcpServerPage.value = emptyControlResources() as PageResponse<McpServer>
  mcpToolPage.value = emptyControlResources() as PageResponse<McpTool>
  mcpKeyword.value = ''
  secretRefs.value = []
  controlKeyword.value = ''
  controlSubmitting.value = false
  controlResult.value = ''
  knowledgeBasePage.value = emptyControlResources() as PageResponse<KnowledgeBase>
  knowledgeDocumentPage.value = emptyControlResources() as PageResponse<KnowledgeDocument>
  knowledgeJobPage.value = emptyControlResources() as PageResponse<KnowledgeIndexJob>
  knowledgeChunkPage.value = emptyControlResources() as PageResponse<KnowledgeChunk>
  knowledgeKeyword.value = ''
  selectedKnowledgeBase.value = null
  selectedKnowledgeDocument.value = null
  knowledgeDialogOpen.value = false
  knowledgeChunksOpen.value = false
  knowledgeLoading.value = false
  knowledgeRetrievalLoading.value = false
  knowledgeRetrievalQuery.value = ''
  knowledgeTopK.value = 5
  knowledgeThreshold.value = 0.2
  knowledgeMatches.value = []
  knowledgeBaseForm.value = { id: '', code: '', displayName: '', description: '', status: 'ACTIVE' }
  applicationPage.value = emptyControlResources() as PageResponse<AgentApplication>
  applicationKeyword.value = ''
  selectedApplication.value = null
  applicationDrawerOpen.value = false
  applicationVersions.value = []
  applicationRecords.value = []
  applicationApiKeys.value = []
  applicationBindings.value = {}
  applicationDetailLoading.value = false
  tracePage.value = emptyControlResources() as PageResponse<TraceSpan>
  workflowPage.value = emptyControlResources() as PageResponse<WorkflowDefinition>
  workflowKeyword.value = ''
  workflowDialogOpen.value = false
  workflowForm.code = ''
  workflowForm.displayName = ''
  workflowForm.description = ''
  workflowEditorOpen.value = false
  selectedWorkflow.value = null
  workflowVersions.value = []
  workflowDslJson.value = ''
  workflowBindingsJson.value = '{}'
  workflowValidation.value = null
  workflowRunPage.value = emptyControlResources() as PageResponse<WorkflowRunView>
  workflowRunKeyword.value = ''
  workflowRunStatus.value = ''
  selectedWorkflowRun.value = null
  workflowRunDrawerOpen.value = false
  workflowNodeRuns.value = []
  workflowEvents.value = []
  disconnectWorkflowEvents()
  traceKeyword.value = ''
  traceType.value = ''
  traceStatus.value = ''
  observabilityOverview.value = null
  selectedTrace.value = null
  traceDrawerOpen.value = false
  datasetPage.value = emptyControlResources() as PageResponse<EvalDataset>
  datasetKeyword.value = ''
  selectedDataset.value = null
  datasetDrawerOpen.value = false
  datasetVersions.value = []
  datasetVersionCases.value = emptyControlResources() as PageResponse<EvalCase>
  datasetCasePage.value = 1
  datasetCaseCategory.value = ''
  evaluatorPage.value = emptyControlResources() as PageResponse<EvalEvaluator>
  evaluatorKeyword.value = ''
  experimentPage.value = emptyControlResources() as PageResponse<EvalExperiment>
  experimentKeyword.value = ''
  experimentStatus.value = ''
  selectedExperiment.value = null
  experimentRuns.value = emptyControlResources() as PageResponse<EvalExperimentRun>
  experimentRunsOpen.value = false
  experimentRunsPage.value = 1
  evalSummary.value = null
  evalLoading.value = false
  openApiSpec.value = null
  openApiDialogOpen.value = false
  knowledgeDocumentInput.value = null
}

function clearSession(): void {
  sessionFence.invalidate()
  loginRequestId += 1
  sessionStorage.removeItem('console-token')
  sessionStorage.removeItem('console-username')
  sessionToken.value = ''
  username.value = ''
  password.value = ''
  passwordVisible.value = false
  captchaId.value = ''
  captchaCode.value = ''
  captchaImage.value = ''
  captchaLoading.value = false
  resetConsoleState()
}

async function logout(): Promise<void> {
  const token = sessionToken.value
  clearSession()
  error.value = ''
  try {
    await consoleApi.logout(token)
  } catch {
    // Local session invalidation is authoritative even when network logout cannot complete.
  } finally {
    await refreshCaptcha()
  }
}

async function searchConversations(): Promise<void> {
  conversationPage.value.page = 1
  await load()
}

async function changeConversationPage(page: number): Promise<void> {
  conversationPage.value.page = page
  await load()
}

async function searchTasks(): Promise<void> {
  taskPage.value.page = 1
  await load()
}

async function changeTaskPage(page: number): Promise<void> {
  taskPage.value.page = page
  await load()
}

async function openConversation(conversation: RuntimeConversation): Promise<void> {
  selectedConversation.value = conversation
  conversationEvents.value = { items: [], nextSequence: 0, hasMore: false }
  conversationDrawerOpen.value = true
  await loadConversationEvents(false)
}

async function loadConversationEvents(append: boolean): Promise<void> {
  if (!selectedConversation.value || detailLoading.value) return
  const lease = beginSessionRequest()
  if (!lease) return
  detailLoading.value = true
  detailError.value = ''
  try {
    const afterSequence = append ? conversationEvents.value.nextSequence : 0
    const result = await requestForSession(lease, token => consoleApi.conversationEvents(
      token,
      selectedConversation.value!.id,
      afterSequence,
    ))
    if (!isCurrentSession(lease) || !result) return
    conversationEvents.value = {
      ...result,
      items: append ? [...conversationEvents.value.items, ...result.items] : result.items,
    }
  } catch (cause) {
    await handleDetailError(cause, lease)
  } finally {
    if (isCurrentSession(lease)) detailLoading.value = false
  }
}

async function openTask(task: RuntimeTask): Promise<void> {
  const lease = beginSessionRequest()
  if (!lease) return
  selectedTask.value = null
  taskDrawerOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  try {
    const result = await requestForSession(lease, token => consoleApi.task(token, task.id))
    if (!isCurrentSession(lease) || !result) return
    selectedTask.value = result
  } catch (cause) {
    await handleDetailError(cause, lease)
  } finally {
    if (isCurrentSession(lease)) detailLoading.value = false
  }
}

async function handleApiError(cause: unknown, fallback: string, lease: SessionLease): Promise<void> {
  if (!isCurrentSession(lease)) {
    return
  }
  if (cause instanceof ConsoleApiError && cause.status === 401) {
    sessionExpired(lease)
    return
  }
  error.value = cause instanceof Error ? cause.message : fallback
}

async function handleDetailError(cause: unknown, lease: SessionLease): Promise<void> {
  if (!isCurrentSession(lease)) {
    return
  }
  if (cause instanceof ConsoleApiError && cause.status === 401) {
    await handleApiError(cause, '详情加载失败。', lease)
    return
  }
  detailError.value = cause instanceof Error ? cause.message : '详情加载失败。'
}

function formatTime(value?: string): string {
  if (!value) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date(value))
}

function statusType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'SUCCEEDED' || status === 'READY') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED' || status === 'EXPIRED') return 'danger'
  if (status.includes('WAITING') || status === 'UNKNOWN' || status === 'MANUAL') return 'warning'
  return 'info'
}

function summary(value: Record<string, unknown>): string {
  return Object.keys(value).length === 0 ? '-' : JSON.stringify(value)
}

onMounted(() => {
  if (sessionToken.value) {
    void load()
  } else {
    void refreshCaptcha()
  }
})
</script>

<template>
  <main v-if="!sessionToken" class="login-page">
    <section class="login-intro">
      <header class="login-brand">
        <span class="brand-mark"><AppIcon :size="36" /></span>
        <div class="brand-copy">
          <strong>Agent Console</strong>
          <small>AGENT TEMPLATE PRO</small>
        </div>
        <span class="environment-badge"><i />ADMIN ACCESS</span>
      </header>

      <div class="login-intro-content">
        <div class="context-copy">
          <p class="eyebrow">CONTROL PLANE</p>
          <h1>把自然语言交互，置于可控运行之中。</h1>
          <p>集中查看会话任务、动作确认与运行状态，让模型理解和确定性执行保持清晰边界。</p>
        </div>

        <section class="workspace-preview" aria-label="控制台工作区预览">
          <header class="preview-head">
            <strong>运行边界</strong>
            <span><i />READY</span>
          </header>
          <div class="preview-metrics">
            <div><el-icon :size="18"><Cpu /></el-icon><small>Runtime</small><strong>CODE-FIRST</strong></div>
            <div><el-icon :size="18"><Lock /></el-icon><small>高风险动作</small><strong>二次确认</strong></div>
            <div><el-icon :size="18"><Coin /></el-icon><small>运行数据</small><strong>SERVER MANAGED</strong></div>
          </div>
          <div class="preview-list">
            <div><span>访客身份</span><b>签名 Cookie 隔离</b></div>
            <div><span>动作执行</span><b>确定性代码校验</b></div>
            <div><span>状态追踪</span><b>任务与事件贯穿</b></div>
          </div>
        </section>
      </div>

      <footer class="login-intro-footer">
        <span><el-icon :size="14"><Lock /></el-icon>管理端与匿名访客会话严格隔离</span>
        <span>SECURE CONSOLE</span>
      </footer>
    </section>

    <section class="auth-pane">
      <form class="login-form" @submit.prevent="login">
        <div class="mobile-brand">
          <span class="brand-mark"><AppIcon :size="36" /></span>
          <div class="brand-copy"><strong>Agent Console</strong><small>AGENT TEMPLATE PRO</small></div>
        </div>
        <header class="form-header">
          <p class="eyebrow">身份验证 · AUTHENTICATION</p>
          <h2>登录管理后台</h2>
          <p>使用管理员账号登录，图片验证码不区分大小写。</p>
        </header>
        <el-alert v-if="error" type="error" :closable="false">
          <template #title><span class="alert-title"><el-icon :size="16"><WarningFilled /></el-icon>{{ error }}</span></template>
        </el-alert>
        <div class="field-group">
          <label for="username">用户名</label>
          <el-input id="username" v-model="username" autocomplete="username" placeholder="请输入管理员用户名" :disabled="loading">
            <template #prefix><el-icon :size="17"><User /></el-icon></template>
          </el-input>
        </div>
        <div class="field-group">
          <label for="password">密码</label>
          <el-input id="password" v-model="password" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入管理员密码" :disabled="loading">
            <template #prefix><el-icon :size="17"><Lock /></el-icon></template>
            <template #suffix>
              <button class="password-toggle" type="button" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" @click="passwordVisible = !passwordVisible">
                <el-icon :size="17"><Hide v-if="passwordVisible" /><View v-else /></el-icon>
              </button>
            </template>
          </el-input>
        </div>
        <div class="field-group">
          <label for="captcha-code">图片验证码</label>
          <div class="captcha-field">
            <el-input id="captcha-code" v-model="captchaCode" maxlength="4" autocomplete="off" placeholder="请输入验证码" :disabled="loading">
              <template #prefix><el-icon :size="17"><Key /></el-icon></template>
            </el-input>
            <button class="captcha-image" type="button" aria-label="刷新图片验证码" :disabled="captchaLoading" @click="refreshCaptcha">
              <img v-if="captchaImage" :src="captchaImage" alt="图片验证码" width="132" height="44">
              <el-icon v-else class="captcha-placeholder" :class="{ spin: captchaLoading }" :size="18"><Refresh /></el-icon>
            </button>
          </div>
        </div>
        <el-button native-type="submit" type="primary" :disabled="loading || captchaLoading">
          进入控制台 <el-icon :class="{ spin: loading }" :size="16"><Refresh v-if="loading" /><ArrowRight v-else /></el-icon>
        </el-button>
        <p class="security-note"><el-icon :size="15"><Lock /></el-icon><span>本地演示账号仅用于开发验证；部署环境必须替换默认密码并接入正式管理员权限体系。</span></p>
      </form>
      <footer class="auth-footer"><span>Agent Template Pro</span><span>简体中文</span></footer>
    </section>
  </main>

  <div v-else class="console-shell">
    <aside class="sidebar">
      <div class="sidebar-brand"><span><AppIcon :size="36" /></span><b>Agent<br>Template</b></div>
      <nav>
        <button :class="{ active: active === 'overview' }" @click="navigate('overview')"><el-icon :size="17"><DataAnalysis /></el-icon><span>运行总览</span></button>
        <button :class="{ active: active === 'conversations' }" @click="navigate('conversations')"><el-icon :size="17"><ChatDotRound /></el-icon><span>会话事件</span></button>
        <button :class="{ active: active === 'agents' }" @click="navigate('agents')"><el-icon :size="17"><Connection /></el-icon><span>领域 Agent</span></button>
        <button :class="{ active: active === 'tasks' }" @click="navigate('tasks')"><el-icon :size="17"><Tickets /></el-icon><span>任务记录</span></button>
        <button :class="{ active: active === 'config' }" @click="navigate('config')"><el-icon :size="17"><Setting /></el-icon><span>运行配置</span></button>
        <button :class="{ active: active === 'models' }" @click="navigate('models')"><el-icon :size="17"><Cpu /></el-icon><span>模型管理</span></button>
        <button :class="{ active: active === 'secrets' }" @click="navigate('secrets')"><el-icon :size="17"><Key /></el-icon><span>Secret 引用</span></button>
        <button :class="{ active: active === 'prompts' }" @click="navigate('prompts')"><el-icon :size="17"><Connection /></el-icon><span>Prompt 管理</span></button>
        <button :class="{ active: active === 'mcpServers' }" @click="navigate('mcpServers')"><el-icon :size="17"><Connection /></el-icon><span>MCP Server</span></button>
        <button :class="{ active: active === 'mcpTools' }" @click="navigate('mcpTools')"><el-icon :size="17"><Setting /></el-icon><span>工具目录</span></button>
        <button :class="{ active: active === 'knowledge' }" @click="navigate('knowledge')"><el-icon :size="17"><FolderOpened /></el-icon><span>知识库</span></button>
        <button :class="{ active: active === 'applications' }" @click="navigate('applications')"><el-icon :size="17"><Document /></el-icon><span>Agent 应用</span></button>
        <button :class="{ active: active === 'traces' }" @click="navigate('traces')"><el-icon :size="17"><Monitor /></el-icon><span>Trace 与指标</span></button>
        <button :class="{ active: active === 'datasets' }" @click="navigate('datasets')"><el-icon :size="17"><Document /></el-icon><span>数据集</span></button>
        <button :class="{ active: active === 'evaluators' }" @click="navigate('evaluators')"><el-icon :size="17"><Tickets /></el-icon><span>评估器</span></button>
        <button :class="{ active: active === 'experiments' }" @click="navigate('experiments')"><el-icon :size="17"><Coin /></el-icon><span>评估实验</span></button>
        <button :class="{ active: active === 'workflows' }" @click="navigate('workflows')"><el-icon :size="17"><Operation /></el-icon><span>Workflow 编排</span></button>
        <button :class="{ active: active === 'workflowRuns' }" @click="navigate('workflowRuns')"><el-icon :size="17"><VideoPlay /></el-icon><span>Workflow 运行</span></button>
      </nav>
      <div class="sidebar-bottom"><span><i />{{ overview?.storageMode || 'runtime' }}</span></div>
    </aside>

    <header class="top-header">
      <div class="top-header-context"><span>Agent Console</span><i>/</i><strong>{{ title }}</strong></div>
      <div class="top-header-actions">
        <span class="runtime-status"><i />{{ overview?.runtimeStatus || '连接中' }}</span>
        <div class="admin-profile">
          <span class="admin-avatar"><el-icon :size="18"><UserFilled /></el-icon></span>
          <span class="admin-copy"><strong>{{ username }}</strong><small>系统管理员</small></span>
        </div>
        <el-tooltip content="退出登录" placement="bottom">
          <el-button text circle aria-label="退出登录" @click="logout"><el-icon :size="17"><SwitchButton /></el-icon></el-button>
        </el-tooltip>
      </div>
    </header>

    <nav class="mobile-nav" aria-label="控制台导航">
      <button :class="{ active: active === 'overview' }" type="button" @click="navigate('overview')"><el-icon :size="16"><DataAnalysis /></el-icon><span>总览</span></button>
      <button :class="{ active: active === 'conversations' }" type="button" @click="navigate('conversations')"><el-icon :size="16"><ChatDotRound /></el-icon><span>会话</span></button>
      <button :class="{ active: active === 'agents' }" type="button" @click="navigate('agents')"><el-icon :size="16"><Connection /></el-icon><span>Agent</span></button>
      <button :class="{ active: active === 'tasks' }" type="button" @click="navigate('tasks')"><el-icon :size="16"><Tickets /></el-icon><span>任务</span></button>
      <button :class="{ active: active === 'config' }" type="button" @click="navigate('config')"><el-icon :size="16"><Setting /></el-icon><span>配置</span></button>
      <button :class="{ active: active === 'models' }" type="button" @click="navigate('models')"><el-icon :size="16"><Cpu /></el-icon><span>模型</span></button>
      <button :class="{ active: active === 'secrets' }" type="button" @click="navigate('secrets')"><el-icon :size="16"><Key /></el-icon><span>Secret</span></button>
      <button :class="{ active: active === 'prompts' }" type="button" @click="navigate('prompts')"><el-icon :size="16"><Connection /></el-icon><span>Prompt</span></button>
      <button :class="{ active: active === 'mcpServers' }" type="button" @click="navigate('mcpServers')"><el-icon :size="16"><Connection /></el-icon><span>MCP</span></button>
      <button :class="{ active: active === 'mcpTools' }" type="button" @click="navigate('mcpTools')"><el-icon :size="16"><Setting /></el-icon><span>工具</span></button>
      <button :class="{ active: active === 'knowledge' }" type="button" @click="navigate('knowledge')"><el-icon :size="16"><FolderOpened /></el-icon><span>知识库</span></button>
      <button :class="{ active: active === 'applications' }" type="button" @click="navigate('applications')"><el-icon :size="16"><Document /></el-icon><span>应用</span></button>
      <button :class="{ active: active === 'traces' }" type="button" @click="navigate('traces')"><el-icon :size="16"><Monitor /></el-icon><span>Trace</span></button>
      <button :class="{ active: active === 'datasets' }" type="button" @click="navigate('datasets')"><el-icon :size="16"><Document /></el-icon><span>数据集</span></button>
      <button :class="{ active: active === 'evaluators' }" type="button" @click="navigate('evaluators')"><el-icon :size="16"><Tickets /></el-icon><span>评估器</span></button>
      <button :class="{ active: active === 'experiments' }" type="button" @click="navigate('experiments')"><el-icon :size="16"><Coin /></el-icon><span>实验</span></button>
      <button :class="{ active: active === 'workflows' }" type="button" @click="navigate('workflows')"><el-icon :size="16"><Operation /></el-icon><span>工作流</span></button>
      <button :class="{ active: active === 'workflowRuns' }" type="button" @click="navigate('workflowRuns')"><el-icon :size="16"><VideoPlay /></el-icon><span>运行</span></button>
    </nav>

    <main class="workspace" v-loading="loading && !overview">
      <header class="workspace-header">
        <div><p class="eyebrow">AGENT RUNTIME / {{ active.toUpperCase() }}</p><h1>{{ title }}</h1></div>
        <div class="header-actions">
          <el-button circle aria-label="刷新" :disabled="loading" @click="load"><el-icon :class="{ spin: loading }" :size="17"><Refresh /></el-icon></el-button>
          <el-button type="primary" @click="navigate('tasks')">查看任务</el-button>
        </div>
      </header>
      <p v-if="error" class="page-error">{{ error }}</p>

      <Transition name="section-switch" mode="out-in">
        <div :key="active" class="workspace-view">
          <section v-if="active === 'overview'" class="overview">
            <div class="metric-grid">
              <article><span>运行状态</span><strong class="healthy">{{ overview?.runtimeStatus || '-' }}</strong><small>{{ overview?.storageMode || '-' }}</small></article>
              <article><span>会话总数</span><strong>{{ overview?.conversationTotal ?? '-' }}</strong><small>持久化会话</small></article>
              <article><span>任务总数</span><strong>{{ overview?.taskTotal ?? '-' }}</strong><small>全部任务记录</small></article>
              <article><span>等待中</span><strong>{{ overview?.activeTasks ?? '-' }}</strong><small>等待外部结果或用户确认</small></article>
              <article><span>结果未知</span><strong>{{ overview?.unknownTasks ?? '-' }}</strong><small>需查单或人工处理</small></article>
              <article><span>领域 Agent</span><strong>{{ overview?.agentTotal ?? '-' }}</strong><small>运行时注册</small></article>
            </div>
            <section class="operation-panel">
              <div class="panel-header"><div><h2>运行边界</h2><p>模型提出意图，确定性动作负责校验、确认和执行。</p></div><el-tag type="success" effect="plain">{{ overview?.storageMode || 'loading' }}</el-tag></div>
              <div class="boundary-grid"><div><span>身份</span><b>签名访客 Cookie</b></div><div><span>会话</span><b>服务端归属校验</b></div><div><span>高风险动作</span><b>强制二次确认</b></div></div>
            </section>
          </section>

          <section v-if="active === 'conversations'" class="table-section">
            <div class="table-toolbar">
              <div><h2>会话与事件</h2><p>访客标识仅显示不可逆摘要。</p></div>
              <div class="filter-row">
                <el-input v-model="conversationQuery" clearable placeholder="会话 ID、标题或 Agent" @keyup.enter="searchConversations">
                  <template #prefix><el-icon><Search /></el-icon></template>
                </el-input>
                <el-button type="primary" :disabled="loading" @click="searchConversations">查询</el-button>
              </div>
            </div>
            <el-table :data="conversationPage.items" v-loading="loading" empty-text="暂无会话">
              <el-table-column prop="title" label="会话" min-width="180" show-overflow-tooltip />
              <el-table-column prop="id" label="会话 ID" min-width="190" show-overflow-tooltip />
              <el-table-column prop="visitorRef" label="访客摘要" min-width="160" />
              <el-table-column prop="activeAgentName" label="当前 Agent" min-width="140" />
              <el-table-column label="最近活动" min-width="180"><template #default="scope">{{ formatTime(scope.row.lastMessageAt) }}</template></el-table-column>
              <el-table-column label="操作" width="90" fixed="right"><template #default="scope"><el-button text type="primary" @click="openConversation(scope.row)">详情</el-button></template></el-table-column>
            </el-table>
            <div class="pagination-row">
              <span>共 {{ conversationPage.total }} 条</span>
              <el-pagination
                background
                layout="prev, pager, next"
                :current-page="conversationPage.page"
                :page-size="conversationPage.size"
                :total="conversationPage.total"
                @current-change="changeConversationPage"
              />
            </div>
          </section>

          <section v-if="active === 'agents'" class="agent-section">
            <div class="table-toolbar"><div><h2>领域 Agent 注册表</h2><p>只读展示运行时真实注册信息，不提供在线注入动作能力。</p></div><el-tag type="success" effect="plain">REGISTRY READY</el-tag></div>
            <div class="agent-grid">
              <article v-for="agent in agents" :key="agent.code">
                <header><div><small>{{ agent.code }}</small><h3>{{ agent.displayName }}</h3></div><el-tag :type="agent.enabled ? 'success' : 'info'" effect="plain">{{ agent.enabled ? '启用' : '停用' }}</el-tag></header>
                <dl><div><dt>C 端可见</dt><dd>{{ agent.visibleToVisitor ? '是' : '否' }}</dd></div><div><dt>注册动作</dt><dd>{{ agent.actionCount }}</dd></div><div><dt>路由器</dt><dd>{{ agent.routerStatus }}</dd></div></dl>
                <footer><el-tag v-for="(count, mode) in agent.actionModes" :key="mode" size="small" effect="plain">{{ mode }} {{ count }}</el-tag></footer>
                <p class="agent-metrics">路由 {{ agent.routeTotal }} · 澄清 {{ agent.ambiguousTotal }} · 失败 {{ agent.failureTotal }}</p>
              </article>
            </div>
          </section>

          <section v-if="active === 'tasks'" class="table-section">
            <div class="table-toolbar">
              <div><h2>任务执行记录</h2><p>确认、调用和异步状态均以任务为追踪入口。</p></div>
              <div class="filter-row task-filters">
                <el-input v-model="taskQuery" clearable placeholder="任务、会话或外部引用" @keyup.enter="searchTasks"><template #prefix><el-icon><Search /></el-icon></template></el-input>
                <el-input v-model="taskActionCode" clearable placeholder="动作码" @keyup.enter="searchTasks" />
                <el-select v-model="taskStatus" clearable placeholder="全部状态">
                  <el-option v-for="value in ['WAITING_CONFIRMATION', 'WAITING_EXTERNAL_RESULT', 'UNKNOWN', 'MANUAL', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED']" :key="value" :label="value" :value="value" />
                </el-select>
                <el-button type="primary" :disabled="loading" @click="searchTasks">查询</el-button>
              </div>
            </div>
            <el-table :data="taskPage.items" v-loading="loading" empty-text="暂无任务记录">
              <el-table-column prop="id" label="任务 ID" min-width="180" show-overflow-tooltip />
              <el-table-column prop="actionCode" label="动作" min-width="190" />
              <el-table-column label="状态" width="190"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
              <el-table-column prop="externalRef" label="外部引用" min-width="160" />
              <el-table-column label="更新时间" min-width="180"><template #default="scope">{{ formatTime(scope.row.updatedAt) }}</template></el-table-column>
              <el-table-column label="操作" width="90" fixed="right"><template #default="scope"><el-button text type="primary" @click="openTask(scope.row)">详情</el-button></template></el-table-column>
            </el-table>
            <div class="pagination-row">
              <span>共 {{ taskPage.total }} 条</span>
              <el-pagination background layout="prev, pager, next" :current-page="taskPage.page" :page-size="taskPage.size" :total="taskPage.total" @current-change="changeTaskPage" />
            </div>
          </section>

          <section v-if="active === 'config'" class="config-section">
            <div class="panel-header"><div><h2>非敏感运行配置</h2><p>密钥和值不会通过控制台 API 返回。</p></div><el-icon class="monitor" :size="24"><Monitor /></el-icon></div>
            <dl v-if="config"><template v-for="(value, key) in config" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl>
          </section>

          <section v-if="active === 'models'" class="table-section">
            <div class="table-toolbar"><div><h2>模型实例</h2><p>仅展示非敏感配置状态；连接测试不会回显凭据。</p></div><div class="filter-row"><el-input v-model="controlKeyword" clearable placeholder="编码或模型名称" @keyup.enter="load" /><el-button v-if="can('model:write')" :loading="controlSubmitting" type="primary" @click="createModel">新增模型</el-button></div></div>
            <el-alert v-if="controlResult" :title="controlResult" type="warning" :closable="true" @close="controlResult = ''" />
            <el-table :data="modelPage.items" v-loading="loading" empty-text="暂无模型实例"><el-table-column prop="code" label="编码" min-width="160" /><el-table-column prop="modelType" label="类型" width="110" /><el-table-column prop="modelName" label="模型" min-width="180" /><el-table-column label="Secret" width="140"><template #default="scope"><el-tag :type="scope.row.secretConfigured ? 'success' : 'info'">{{ scope.row.secretConfigured ? '已配置' : '未配置' }}</el-tag></template></el-table-column><el-table-column prop="updatedAt" label="更新时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.updatedAt) }}</template></el-table-column><el-table-column v-if="can('model:test')" label="操作" width="100"><template #default="scope"><el-button text type="primary" :loading="controlSubmitting" @click="testModel(scope.row)">连接测试</el-button></template></el-table-column></el-table>
          </section>

          <section v-if="active === 'secrets'" class="table-section">
            <div class="table-toolbar"><div><h2>Secret 引用</h2><p>仅管理引用与配置状态，永不显示真实值或引用定位。</p></div><el-button v-if="can('secret:write')" :loading="controlSubmitting" type="primary" @click="createSecretRef">新增引用</el-button></div>
            <el-table :data="secretRefs" v-loading="loading" empty-text="暂无 Secret 引用"><el-table-column prop="name" label="名称" min-width="180" /><el-table-column prop="secretRefType" label="类型" width="140" /><el-table-column label="状态" width="140"><template #default="scope"><el-tag :type="scope.row.configured ? 'success' : 'info'">{{ scope.row.configured ? '已配置' : '未配置' }}</el-tag></template></el-table-column><el-table-column prop="updatedAt" label="更新时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.updatedAt) }}</template></el-table-column></el-table>
          </section>

          <section v-if="active === 'prompts'" class="table-section">
            <div class="table-toolbar"><div><h2>Prompt 草稿与版本</h2><p>发布固定版本快照；既有运行记录不会被新草稿修改。</p></div><div class="filter-row"><el-input v-model="controlKeyword" clearable placeholder="编码或名称" @keyup.enter="load" /><el-button v-if="can('prompt:write')" :loading="controlSubmitting" type="primary" @click="createPrompt">新增 Prompt</el-button></div></div>
            <el-table :data="promptPage.items" v-loading="loading" empty-text="暂无 Prompt"><el-table-column prop="code" label="编码" min-width="180" /><el-table-column prop="displayName" label="名称" min-width="180" /><el-table-column label="发布状态" min-width="180"><template #default="scope"><el-tag :type="scope.row.publishedVersionId ? 'success' : 'info'">{{ scope.row.publishedVersionId ? '已发布' : '草稿' }}</el-tag></template></el-table-column><el-table-column v-if="can('prompt:publish')" label="操作" width="120"><template #default="scope"><el-button text type="primary" :loading="controlSubmitting" @click="versionAndPublish(scope.row)">创建并发布</el-button></template></el-table-column></el-table>
          </section>

          <section v-if="active === 'mcpServers'" class="table-section">
            <div class="table-toolbar"><div><h2>MCP Server</h2><p>仅允许部署白名单内的 HTTPS endpoint；Secret 仅以引用状态显示。</p></div><div class="filter-row"><el-input v-model="mcpKeyword" clearable placeholder="编码或名称" @keyup.enter="load" /><el-button v-if="can('mcp:write')" :loading="controlSubmitting" type="primary" @click="createMcpServer">新增 Server</el-button></div></div>
            <el-alert v-if="controlResult" :title="controlResult" type="warning" :closable="true" @close="controlResult = ''" />
            <el-table :data="mcpServerPage.items" v-loading="loading" empty-text="暂无 MCP Server"><el-table-column prop="code" label="编码" min-width="150" /><el-table-column prop="transport" label="Transport" width="150" /><el-table-column label="状态" width="135"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column><el-table-column prop="healthStatus" label="健康" width="150" /><el-table-column label="最近同步" min-width="170"><template #default="scope">{{ formatTime(scope.row.lastSyncedAt) }}</template></el-table-column><el-table-column label="操作" width="160" fixed="right"><template #default="scope"><el-button v-if="can('mcp:test')" text type="primary" :loading="controlSubmitting" @click="testMcpServer(scope.row)">测试</el-button><el-button v-if="can('mcp:sync')" text type="primary" :loading="controlSubmitting" @click="syncMcpServer(scope.row)">同步</el-button></template></el-table-column></el-table>
            <div class="pagination-row"><span>共 {{ mcpServerPage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="mcpServerPage.page" :page-size="mcpServerPage.size" :total="mcpServerPage.total" @current-change="changeMcpServerPage" /></div>
          </section>

          <section v-if="active === 'mcpTools'" class="table-section">
            <div class="table-toolbar"><div><h2>统一工具目录</h2><p>Schema 变更会创建新版本；写类型 Tool 只能通过 Runtime 任务和确认门禁执行。</p></div><div class="filter-row"><el-input v-model="mcpKeyword" clearable placeholder="Tool 名称或风险等级" @keyup.enter="load" /></div></div>
            <el-alert v-if="controlResult" :title="controlResult" type="warning" :closable="true" @close="controlResult = ''" />
            <el-table :data="mcpToolPage.items" v-loading="loading" empty-text="暂无已发现的 MCP Tool"><el-table-column prop="name" label="Tool" min-width="190" /><el-table-column prop="riskLevel" label="风险" width="110"><template #default="scope"><el-tag :type="scope.row.riskLevel === 'HIGH' ? 'danger' : scope.row.riskLevel === 'MEDIUM' ? 'warning' : 'success'">{{ scope.row.riskLevel }}</el-tag></template></el-table-column><el-table-column label="类型" width="110"><template #default="scope">{{ scope.row.writeTool ? '写操作' : '只读' }}</template></el-table-column><el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="180" fixed="right"><template #default="scope"><el-button v-if="can('mcp:write')" text :type="scope.row.enabled ? 'danger' : 'primary'" :loading="controlSubmitting" @click="setMcpToolEnabled(scope.row, !scope.row.enabled)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button><el-button v-if="can('mcp:test')" text type="primary" :loading="controlSubmitting" @click="debugMcpTool(scope.row)">Debug</el-button></template></el-table-column></el-table>
            <div class="pagination-row"><span>共 {{ mcpToolPage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="mcpToolPage.page" :page-size="mcpToolPage.size" :total="mcpToolPage.total" @current-change="changeMcpToolPage" /></div>
          </section>

          <section v-if="active === 'knowledge'" class="knowledge-section">
            <div class="table-section knowledge-table">
              <div class="table-toolbar">
                <div><h2>知识库</h2><p>文档与切片仅在受控索引链路中处理；控制台不展示原始正文。</p></div>
                <div class="filter-row">
                  <el-input v-model="knowledgeKeyword" clearable placeholder="编码或名称" @keyup.enter="searchKnowledgeBases"><template #prefix><el-icon><Search /></el-icon></template></el-input>
                  <el-button type="primary" :disabled="loading" @click="searchKnowledgeBases">查询</el-button>
                  <el-button v-if="can('knowledge:write')" type="primary" :loading="controlSubmitting" @click="openKnowledgeBaseDialog()">新建知识库</el-button>
                </div>
              </div>
              <el-alert v-if="controlResult" :title="controlResult" type="warning" :closable="true" @close="controlResult = ''" />
              <el-table :data="knowledgeBasePage.items" v-loading="loading" empty-text="暂无知识库">
                <el-table-column prop="code" label="编码" min-width="160" show-overflow-tooltip />
                <el-table-column prop="displayName" label="名称" min-width="180" show-overflow-tooltip />
                <el-table-column label="状态" width="130"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
                <el-table-column prop="documentCount" label="文档" width="100" />
                <el-table-column prop="chunkCount" label="切片" width="100" />
                <el-table-column label="更新时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.updatedAt) }}</template></el-table-column>
                <el-table-column label="操作" width="170" fixed="right"><template #default="scope"><el-button text type="primary" @click="selectKnowledgeBase(scope.row)">管理</el-button><el-button v-if="can('knowledge:write')" text type="primary" @click="openKnowledgeBaseDialog(scope.row)">配置</el-button></template></el-table-column>
              </el-table>
              <div class="pagination-row"><span>共 {{ knowledgeBasePage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="knowledgeBasePage.page" :page-size="knowledgeBasePage.size" :total="knowledgeBasePage.total" @current-change="changeKnowledgeBasePage" /></div>
            </div>

            <section v-if="selectedKnowledgeBase" class="knowledge-detail" v-loading="knowledgeLoading">
              <header class="knowledge-detail-header"><div><p class="eyebrow">KNOWLEDGE BASE / {{ selectedKnowledgeBase.code }}</p><h2>{{ selectedKnowledgeBase.displayName }}</h2></div><el-tag :type="statusType(selectedKnowledgeBase.status)">{{ selectedKnowledgeBase.status }}</el-tag></header>
              <div class="knowledge-detail-grid">
                <section class="knowledge-panel">
                  <div class="panel-header"><div><h3>检索测试</h3><p>结果仅显示排序分数和可追溯 citation，不显示切片正文。</p></div></div>
                  <div class="retrieval-form">
                    <el-input v-model="knowledgeRetrievalQuery" clearable placeholder="输入检索问题" @keyup.enter="runKnowledgeRetrieval" />
                    <el-input-number v-model="knowledgeTopK" :min="1" :max="20" controls-position="right" aria-label="返回数量" />
                    <el-input-number v-model="knowledgeThreshold" :min="0" :max="1" :step="0.05" :precision="2" controls-position="right" aria-label="相似度阈值" />
                    <el-button v-if="can('knowledge:read')" type="primary" :loading="knowledgeRetrievalLoading" @click="runKnowledgeRetrieval">检索</el-button>
                  </div>
                  <el-table :data="knowledgeMatches" v-loading="knowledgeRetrievalLoading" empty-text="尚未运行检索测试">
                    <el-table-column label="分数" width="100"><template #default="scope">{{ scope.row.score.toFixed(3) }}</template></el-table-column>
                    <el-table-column label="重排分数" width="120"><template #default="scope">{{ scope.row.rerankScore?.toFixed(3) || '-' }}</template></el-table-column>
                    <el-table-column label="文档" min-width="150"><template #default="scope">{{ scope.row.citation.documentName || scope.row.citation.documentId }}</template></el-table-column>
                    <el-table-column label="引用定位" min-width="190" show-overflow-tooltip><template #default="scope">{{ scope.row.citation.chunkId }}<span v-if="scope.row.citation.chunkIndex !== undefined"> · #{{ scope.row.citation.chunkIndex }}</span></template></el-table-column>
                  </el-table>
                </section>

                <section class="knowledge-panel knowledge-documents">
                  <div class="panel-header"><div><h3>文档与队列</h3><p>支持 TXT、Markdown、PDF、DOCX，单文件最大 10 MB。</p></div><el-button v-if="can('knowledge:write')" type="primary" :loading="controlSubmitting" @click="selectKnowledgeDocument">上传文档</el-button></div>
                  <input ref="knowledgeDocumentInput" class="visually-hidden" type="file" accept="text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,.txt,.md,.pdf,.docx" @change="uploadKnowledgeDocument">
                  <el-table :data="knowledgeDocumentPage.items" empty-text="暂无文档">
                    <el-table-column prop="name" label="文件" min-width="170" show-overflow-tooltip />
                    <el-table-column prop="contentType" label="格式" min-width="120" show-overflow-tooltip />
                    <el-table-column label="状态" width="130"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
                    <el-table-column label="操作" width="210" fixed="right"><template #default="scope"><el-button text type="primary" @click="openKnowledgeChunks(scope.row)">切片</el-button><el-button v-if="can('knowledge:write')" text type="primary" :loading="controlSubmitting" @click="reindexKnowledgeDocument(scope.row)">重建</el-button><el-button v-if="can('knowledge:write')" text type="danger" :loading="controlSubmitting" @click="deleteKnowledgeDocument(scope.row)">删除</el-button></template></el-table-column>
                  </el-table>
                  <div class="pagination-row"><span>共 {{ knowledgeDocumentPage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="knowledgeDocumentPage.page" :page-size="knowledgeDocumentPage.size" :total="knowledgeDocumentPage.total" @current-change="changeKnowledgeDocumentPage" /></div>
                  <el-table :data="knowledgeJobPage.items" empty-text="暂无索引任务" class="knowledge-job-table">
                    <el-table-column label="任务" min-width="160" show-overflow-tooltip><template #default="scope">{{ scope.row.id }}</template></el-table-column>
                    <el-table-column label="状态" width="125"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
                    <el-table-column prop="attempts" label="尝试" width="80" />
                    <el-table-column prop="lastErrorCode" label="失败原因" min-width="120" show-overflow-tooltip />
                    <el-table-column label="操作" width="85"><template #default="scope"><el-button v-if="scope.row.status === 'FAILED' && can('knowledge:write')" text type="primary" :loading="controlSubmitting" @click="retryKnowledgeJob(scope.row)">重试</el-button></template></el-table-column>
                  </el-table>
                  <div class="pagination-row"><span>共 {{ knowledgeJobPage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="knowledgeJobPage.page" :page-size="knowledgeJobPage.size" :total="knowledgeJobPage.total" @current-change="changeKnowledgeJobPage" /></div>
                </section>
              </div>
            </section>

            <el-empty v-else-if="!loading" class="knowledge-empty" description="选择一个知识库后查看文档、索引队列和检索结果" :image-size="72" />
          </section>

          <section v-if="active === 'applications'" class="table-section">
            <div class="table-toolbar">
              <div><h2>Agent 应用</h2><p>版本发布后不可变；API Key 仅以状态与前缀展示，明文只在新创建/轮换时返回一次。</p></div>
              <div class="filter-row">
                <el-input v-model="applicationKeyword" clearable placeholder="编码或名称" @keyup.enter="searchApplications" />
                <el-button :disabled="loading" @click="searchApplications">查询</el-button>
                <el-button v-if="can('agentapp:write')" type="primary" :loading="controlSubmitting" @click="createApplication">新增应用</el-button>
              </div>
            </div>
            <el-alert v-if="controlResult" :title="controlResult" type="warning" :closable="true" @close="controlResult = ''" />
            <el-table :data="applicationPage.items" v-loading="loading" empty-text="暂无 Agent 应用">
              <el-table-column prop="code" label="编码" min-width="160" show-overflow-tooltip />
              <el-table-column prop="displayName" label="名称" min-width="180" show-overflow-tooltip />
              <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
              <el-table-column label="已发布版本" width="120"><template #default="scope">{{ scope.row.currentVersionId ? versionLabel(scope.row) : '-' }}</template></el-table-column>
              <el-table-column label="更新时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.updatedAt) }}</template></el-table-column>
              <el-table-column label="操作" width="180" fixed="right">
                <template #default="scope">
                  <el-button text type="primary" @click="openApplicationDrawer(scope.row)">管理</el-button>
                  <el-button v-if="can('agentapp:write')" text type="danger" :loading="controlSubmitting" @click="archiveApplication(scope.row)">归档</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="pagination-row"><span>共 {{ applicationPage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="applicationPage.page" :page-size="applicationPage.size" :total="applicationPage.total" @current-change="changeApplicationPage" /></div>
          </section>

          <section v-if="active === 'traces'" class="table-section">
            <div class="metric-grid compact-metrics" v-if="observabilityOverview">
              <article><span>模型调用</span><strong>{{ observabilityOverview.model.calls }}</strong><small>平均 {{ observabilityOverview.model.avgLatencyMs }}ms · P95 {{ observabilityOverview.model.p95LatencyMs }}ms</small></article>
              <article><span>模型 Token</span><strong>{{ observabilityOverview.model.totalTokens }}</strong><small>错误 {{ observabilityOverview.model.errorCount }} · 超时 {{ observabilityOverview.model.timeoutCount }}</small></article>
              <article><span>工具调用</span><strong>{{ observabilityOverview.tool.calls }}</strong><small>错误 {{ observabilityOverview.tool.errorCount }} · 未知 {{ observabilityOverview.tool.unknownCount }}</small></article>
              <article><span>确认门禁</span><strong>{{ (observabilityOverview.task.confirmationRate * 100).toFixed(1) }}%</strong><small>通过 {{ observabilityOverview.task.confirmationConfirmed }} · 拒绝 {{ observabilityOverview.task.confirmationRejected }}</small></article>
              <article><span>任务异常</span><strong>{{ observabilityOverview.task.unknownTasks + observabilityOverview.task.timeoutTasks }}</strong><small>结果未知 {{ observabilityOverview.task.unknownTasks }} · 恢复中 {{ observabilityOverview.task.timeoutTasks }}</small></article>
              <article><span>Span 总数</span><strong>{{ observabilityOverview.totalSpans }}</strong><small>{{ observabilityOverview.totalTraces }} 条请求 Trace</small></article>
            </div>
            <div class="table-toolbar">
              <div><h2>Trace 检索</h2><p>Span 只保存白名单字段与脱敏元数据；从任务详情可按 requestId 跳转到这里。</p></div>
              <div class="filter-row">
                <el-select v-model="traceType" placeholder="类型" clearable style="width: 140px"><el-option v-for="type in ['REQUEST','ROUTE','TASK','ACTION','TOOL','MODEL','RETRIEVAL','WORKFLOW','EVALUATION']" :key="type" :label="type" :value="type" /></el-select>
                <el-select v-model="traceStatus" placeholder="状态" clearable style="width: 120px"><el-option v-for="status in ['OK','ERROR','TIMEOUT','UNKNOWN','SKIPPED']" :key="status" :label="status" :value="status" /></el-select>
                <el-input v-model="traceKeyword" clearable placeholder="traceId / 会话 / 任务 / 动作" @keyup.enter="searchTraces"><template #prefix><el-icon><Search /></el-icon></template></el-input>
                <el-button :disabled="loading" @click="searchTraces">查询</el-button>
              </div>
            </div>
            <el-table :data="tracePage.items" v-loading="loading" empty-text="暂无 Trace，先发送一条 Chat 消息会产生请求/Tool/模型 span">
              <el-table-column prop="traceId" label="Trace ID" min-width="200" show-overflow-tooltip />
              <el-table-column prop="spanType" label="类型" width="110"><template #default="scope"><el-tag size="small">{{ scope.row.spanType }}</el-tag></template></el-table-column>
              <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
              <el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="statusType(scope.row.status)" size="small">{{ scope.row.status }}</el-tag></template></el-table-column>
              <el-table-column label="耗时" width="100"><template #default="scope">{{ scope.row.durationMs }}ms</template></el-table-column>
              <el-table-column label="Token" width="90"><template #default="scope">{{ scope.row.totalTokens }}</template></el-table-column>
              <el-table-column label="开始时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.startedAt) }}</template></el-table-column>
              <el-table-column label="操作" width="110" fixed="right"><template #default="scope"><el-button text type="primary" @click="openTraceDetail(scope.row.traceId)">完整 Trace</el-button></template></el-table-column>
            </el-table>
            <div class="pagination-row"><span>共 {{ tracePage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="tracePage.page" :page-size="tracePage.size" :total="tracePage.total" @current-change="changeTracePage" /></div>
          </section>

          <section v-if="active === 'datasets'" class="table-section">
            <div class="table-toolbar">
              <div><h2>数据集</h2><p>用例按版本管理；Trace 生成的是候选样本，输入需人工补充。</p></div>
              <div class="filter-row">
                <el-input v-model="datasetKeyword" clearable placeholder="编码或名称" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input>
                <el-button :disabled="loading" @click="load">查询</el-button>
                <el-button v-if="can('eval:write')" type="primary" :loading="controlSubmitting" @click="createDataset">新建数据集</el-button>
              </div>
            </div>
            <el-alert v-if="controlResult" :title="controlResult" type="warning" :closable="true" @close="controlResult = ''" />
            <el-table :data="datasetPage.items" v-loading="loading" empty-text="暂无数据集">
              <el-table-column prop="code" label="编码" min-width="160" show-overflow-tooltip />
              <el-table-column prop="displayName" label="名称" min-width="180" show-overflow-tooltip />
              <el-table-column prop="caseCount" label="用例数" width="100" />
              <el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
              <el-table-column label="更新时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.updatedAt) }}</template></el-table-column>
              <el-table-column label="操作" width="110" fixed="right"><template #default="scope"><el-button text type="primary" @click="openDatasetDrawer(scope.row)">管理</el-button></template></el-table-column>
            </el-table>
            <div class="pagination-row"><span>共 {{ datasetPage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="datasetPage.page" :page-size="datasetPage.size" :total="datasetPage.total" @current-change="changeDatasetPage" /></div>
          </section>

          <section v-if="active === 'evaluators'" class="table-section">
            <div class="table-toolbar">
              <div><h2>评估器</h2><p>每个评估器以版本发布配置；实验绑定的是评估器版本 ID。</p></div>
              <div class="filter-row">
                <el-input v-model="evaluatorKeyword" clearable placeholder="编码或类型" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input>
                <el-button :disabled="loading" @click="load">查询</el-button>
                <el-button v-if="can('eval:write')" type="primary" :loading="controlSubmitting" @click="createEvaluator">新建评估器</el-button>
              </div>
            </div>
            <el-alert v-if="controlResult" :title="controlResult" type="warning" :closable="true" @close="controlResult = ''" />
            <el-table :data="evaluatorPage.items" v-loading="loading" empty-text="暂无评估器">
              <el-table-column prop="code" label="编码" min-width="160" show-overflow-tooltip />
              <el-table-column prop="displayName" label="名称" min-width="180" show-overflow-tooltip />
              <el-table-column prop="evaluatorType" label="类型" width="170"><template #default="scope"><el-tag size="small">{{ scope.row.evaluatorType }}</el-tag></template></el-table-column>
              <el-table-column label="版本数" width="100"><template #default="scope">{{ scope.row.versions.length }}</template></el-table-column>
              <el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="150" fixed="right"><template #default="scope"><el-button v-if="can('eval:write')" text type="primary" :loading="controlSubmitting" @click="createEvaluatorVersion(scope.row)">新建版本</el-button></template></el-table-column>
            </el-table>
            <div class="pagination-row"><span>共 {{ evaluatorPage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="evaluatorPage.page" :page-size="evaluatorPage.size" :total="evaluatorPage.total" @current-change="changeEvaluatorPage" /></div>
          </section>

          <section v-if="active === 'experiments'" class="table-section">
            <div class="table-toolbar">
              <div><h2>评估实验</h2><p>实验绑定数据集版本、已发布 Agent 版本与评估器版本；结果可复现，重启后可恢复。</p></div>
              <div class="filter-row">
                <el-select v-model="experimentStatus" placeholder="状态" clearable style="width: 140px"><el-option v-for="status in ['DRAFT','RUNNING','SUCCEEDED','PARTIAL','STOPPED']" :key="status" :label="status" :value="status" /></el-select>
                <el-input v-model="experimentKeyword" clearable placeholder="编码或名称" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input>
                <el-button :disabled="loading" @click="load">查询</el-button>
                <el-button v-if="can('eval:write')" type="primary" :loading="controlSubmitting" @click="createExperiment">新建实验</el-button>
              </div>
            </div>
            <el-alert v-if="controlResult" :title="controlResult" type="warning" :closable="true" @close="controlResult = ''" />
            <el-table :data="experimentPage.items" v-loading="loading" empty-text="暂无评估实验">
              <el-table-column prop="code" label="编码" min-width="150" show-overflow-tooltip />
              <el-table-column prop="displayName" label="名称" min-width="180" show-overflow-tooltip />
              <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
              <el-table-column label="进度" min-width="150"><template #default="scope">{{ scope.row.completedCases }}/{{ scope.row.totalCases }}<el-progress v-if="scope.row.totalCases" :percentage="Math.round(scope.row.completedCases * 100 / scope.row.totalCases)" :stroke-width="6" /></template></el-table-column>
              <el-table-column label="通过率" width="110"><template #default="scope">{{ scope.row.passRate != null ? (Number(scope.row.passRate) * 100).toFixed(1) + '%' : '-' }}</template></el-table-column>
              <el-table-column label="成本" width="100"><template #default="scope">{{ (Number(scope.row.costMicros || 0) / 1000).toFixed(1) }}m$</template></el-table-column>
              <el-table-column label="操作" width="260" fixed="right">
                <template #default="scope">
                  <el-button text type="primary" @click="openExperimentRuns(scope.row)">结果</el-button>
                  <el-button v-if="scope.row.status === 'DRAFT' && can('eval:run')" text type="primary" :loading="controlSubmitting" @click="startExperiment(scope.row)">启动</el-button>
                  <el-button v-if="scope.row.status === 'RUNNING' && can('eval:run')" text type="warning" :loading="controlSubmitting" @click="stopExperiment(scope.row)">停止</el-button>
                  <el-button v-if="(scope.row.status === 'STOPPED' || scope.row.status === 'PARTIAL') && can('eval:run')" text type="primary" :loading="controlSubmitting" @click="retryExperiment(scope.row)">重试</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="pagination-row"><span>共 {{ experimentPage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="experimentPage.page" :page-size="experimentPage.size" :total="experimentPage.total" @current-change="changeExperimentPage" /></div>
          </section>

          <section v-if="active === 'workflows'" class="table-section">
            <div class="table-toolbar">
              <div><h2>Workflow 编排</h2><p>版本化 DSL 受控编排：发布固定模型、Prompt、知识库与 MCP 工具版本，写节点强制经 Runtime 确认门禁。</p></div>
              <div class="filter-row">
                <el-input v-model="workflowKeyword" clearable placeholder="编码或名称" @keyup.enter="searchWorkflows"><template #prefix><el-icon><Search /></el-icon></template></el-input>
                <el-button :disabled="loading" @click="searchWorkflows">查询</el-button>
                <el-button v-if="can('workflow:write')" type="primary" @click="workflowDialogOpen = true">新建工作流</el-button>
              </div>
            </div>
            <el-table :data="workflowPage.items" v-loading="loading" empty-text="暂无工作流">
              <el-table-column prop="code" label="编码" min-width="150" show-overflow-tooltip />
              <el-table-column prop="displayName" label="名称" min-width="180" show-overflow-tooltip />
              <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
              <el-table-column label="当前版本" width="160"><template #default="scope">{{ scope.row.currentVersionId ? '已发布 v' + workflowVersionNo(scope.row) : '未发布' }}</template></el-table-column>
              <el-table-column prop="createdBy" label="创建人" width="110" />
              <el-table-column label="更新时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.updatedAt) }}</template></el-table-column>
              <el-table-column label="操作" width="190" fixed="right">
                <template #default="scope">
                  <el-button text type="primary" @click="openWorkflowEditor(scope.row)">编辑</el-button>
                  <el-button v-if="can('workflow:write') && scope.row.status === 'ACTIVE'" text type="warning" :loading="controlSubmitting" @click="rollbackWorkflowFromList(scope.row)">回滚</el-button>
                  <el-button v-if="can('workflow:write') && scope.row.status !== 'ARCHIVED'" text type="danger" @click="archiveWorkflowFromList(scope.row)">归档</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="pagination-row"><span>共 {{ workflowPage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="workflowPage.page" :page-size="workflowPage.size" :total="workflowPage.total" @current-change="changeWorkflowPage" /></div>
          </section>

          <section v-if="active === 'workflowRuns'" class="table-section">
            <div class="table-toolbar">
              <div><h2>Workflow 运行</h2><p>运行归属发起管理员；暂停、恢复、确认、停止与重试均校验归属与权限。</p></div>
              <div class="filter-row">
                <el-select v-model="workflowRunStatus" placeholder="状态" clearable style="width: 150px"><el-option v-for="status in ['RUNNING','PAUSED','SUCCEEDED','FAILED','STOPPED']" :key="status" :label="status" :value="status" /></el-select>
                <el-input v-model="workflowRunKeyword" clearable placeholder="编码或运行 ID" @keyup.enter="searchWorkflowRuns"><template #prefix><el-icon><Search /></el-icon></template></el-input>
                <el-button :disabled="loading" @click="searchWorkflowRuns">查询</el-button>
              </div>
            </div>
            <el-alert v-if="controlResult" :title="controlResult" type="warning" :closable="true" @close="controlResult = ''" />
            <el-table :data="workflowRunPage.items" v-loading="loading" empty-text="暂无工作流运行">
              <el-table-column prop="id" label="运行 ID" min-width="200" show-overflow-tooltip />
              <el-table-column prop="code" label="流程" min-width="140" show-overflow-tooltip />
              <el-table-column label="状态" width="130"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
              <el-table-column label="当前节点" min-width="140"><template #default="scope">{{ scope.row.currentNodeId || '-' }}</template></el-table-column>
              <el-table-column label="开始时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.startedAt) }}</template></el-table-column>
              <el-table-column label="操作" width="100" fixed="right"><template #default="scope"><el-button text type="primary" @click="openWorkflowRun(scope.row)">详情</el-button></template></el-table-column>
            </el-table>
            <div class="pagination-row"><span>共 {{ workflowRunPage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="workflowRunPage.page" :page-size="workflowRunPage.size" :total="workflowRunPage.total" @current-change="changeWorkflowRunPage" /></div>
          </section>
        </div>
      </Transition>
    </main>

    <el-drawer v-model="conversationDrawerOpen" title="会话事件" size="680px" class="runtime-drawer">
      <div v-if="selectedConversation" class="drawer-content" v-loading="detailLoading && conversationEvents.items.length === 0">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="会话 ID">{{ selectedConversation.id }}</el-descriptions-item>
          <el-descriptions-item label="访客摘要">{{ selectedConversation.visitorRef }}</el-descriptions-item>
          <el-descriptions-item label="当前 Agent">{{ selectedConversation.activeAgentName }}</el-descriptions-item>
          <el-descriptions-item label="最近活动">{{ formatTime(selectedConversation.lastMessageAt) }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="detailError" type="error" :closable="false" :title="detailError" />
        <el-timeline v-if="conversationEvents.items.length" class="event-timeline">
          <el-timeline-item v-for="event in conversationEvents.items" :key="`${event.sequence}-${event.type}`" :timestamp="formatTime(event.timestamp)" placement="top">
            <div class="event-row"><el-tag size="small" effect="plain">{{ event.type }}</el-tag><code>#{{ event.sequence }}</code></div>
            <dl><template v-if="event.taskId"><dt>任务</dt><dd>{{ event.taskId }}</dd></template><template v-if="event.status"><dt>状态</dt><dd>{{ event.status }}</dd></template><template v-if="event.actionCode"><dt>动作</dt><dd>{{ event.actionCode }}</dd></template><dt>请求</dt><dd>{{ event.requestId || '-' }}</dd></dl>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else-if="!detailLoading && !detailError" description="暂无事件" :image-size="72" />
        <el-button v-if="conversationEvents.hasMore" :loading="detailLoading" @click="loadConversationEvents(true)">加载更多</el-button>
      </div>
    </el-drawer>

    <el-drawer v-model="taskDrawerOpen" title="任务详情" size="720px" class="runtime-drawer">
      <div class="drawer-content" v-loading="detailLoading">
        <el-alert v-if="detailError" type="error" :closable="false" :title="detailError" />
        <template v-if="selectedTask">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="任务 ID">{{ selectedTask.task.id }}</el-descriptions-item>
            <el-descriptions-item label="动作">{{ selectedTask.task.actionCode }}</el-descriptions-item>
            <el-descriptions-item label="状态"><el-tag :type="statusType(selectedTask.task.status)">{{ selectedTask.task.status }}</el-tag></el-descriptions-item>
            <el-descriptions-item label="会话">{{ selectedTask.task.conversationId }}</el-descriptions-item>
            <el-descriptions-item label="访客摘要">{{ selectedTask.task.visitorRef }}</el-descriptions-item>
            <el-descriptions-item label="外部引用">{{ selectedTask.task.externalRef || '-' }}</el-descriptions-item>
            <el-descriptions-item label="恢复次数">{{ selectedTask.task.recoveryAttempts }}</el-descriptions-item>
            <el-descriptions-item label="错误码">{{ selectedTask.task.lastErrorCode || '-' }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatTime(selectedTask.task.updatedAt) }}</el-descriptions-item>
          </el-descriptions>

          <section class="drawer-section">
            <h3>工具执行</h3>
            <el-table :data="selectedTask.toolExecutions" empty-text="暂无工具执行记录">
              <el-table-column prop="toolCode" label="工具" min-width="170" />
              <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="statusType(scope.row.status)" size="small">{{ scope.row.status }}</el-tag></template></el-table-column>
              <el-table-column label="输入摘要" min-width="180" show-overflow-tooltip><template #default="scope">{{ summary(scope.row.inputSummary) }}</template></el-table-column>
              <el-table-column label="输出摘要" min-width="180" show-overflow-tooltip><template #default="scope">{{ summary(scope.row.outputSummary) }}</template></el-table-column>
            </el-table>
          </section>

          <section class="drawer-section">
            <h3>审计记录</h3>
            <el-table :data="selectedTask.audits" empty-text="暂无审计记录">
              <el-table-column prop="eventType" label="事件" min-width="180" />
              <el-table-column prop="actorType" label="操作者" width="110" />
              <el-table-column prop="requestId" label="请求 ID" min-width="170" show-overflow-tooltip />
              <el-table-column label="时间" min-width="180"><template #default="scope">{{ formatTime(scope.row.createdAt) }}</template></el-table-column>
            </el-table>
          </section>
        </template>
        <el-empty v-else-if="!detailLoading && !detailError" description="任务不存在" :image-size="72" />
      </div>
    </el-drawer>

    <el-drawer v-model="traceDrawerOpen" title="Trace 详情" size="860px" class="runtime-drawer">
      <div v-if="selectedTrace" class="drawer-content" v-loading="evalLoading">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="Trace ID" :span="2">{{ selectedTrace.traceId }}</el-descriptions-item>
          <el-descriptions-item label="Span 数">{{ selectedTrace.spanCount }}</el-descriptions-item>
          <el-descriptions-item label="总 Token">{{ selectedTrace.totalTokens }}</el-descriptions-item>
          <el-descriptions-item label="会话" :span="2">{{ selectedTrace.conversationIds.join('、') || '-' }}</el-descriptions-item>
          <el-descriptions-item label="任务" :span="2">{{ selectedTrace.taskIds.join('、') || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Agent">{{ selectedTrace.agentCodes.join('、') || '-' }}</el-descriptions-item>
          <el-descriptions-item label="动作">{{ selectedTrace.actionCodes.join('、') || '-' }}</el-descriptions-item>
          <el-descriptions-item label="工具">{{ selectedTrace.toolCodes.join('、') || '-' }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ selectedTrace.modelNames.join('、') || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="selectedTrace.spans" empty-text="暂无 span" class="drawer-table">
          <el-table-column prop="spanType" label="类型" width="110"><template #default="scope"><el-tag size="small">{{ scope.row.spanType }}</el-tag></template></el-table-column>
          <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="statusType(scope.row.status)" size="small">{{ scope.row.status }}</el-tag></template></el-table-column>
          <el-table-column label="耗时" width="90"><template #default="scope">{{ scope.row.durationMs }}ms</template></el-table-column>
          <el-table-column label="Token" width="80"><template #default="scope">{{ scope.row.totalTokens }}</template></el-table-column>
          <el-table-column prop="errorCode" label="错误码" width="130" show-overflow-tooltip />
          <el-table-column label="开始" min-width="170"><template #default="scope">{{ formatTime(scope.row.startedAt) }}</template></el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <el-drawer v-model="datasetDrawerOpen" :title="selectedDataset ? `数据集 · ${selectedDataset.displayName}` : '数据集'" size="820px" class="runtime-drawer">
      <div v-if="selectedDataset" class="drawer-content" v-loading="evalLoading">
        <el-alert v-if="controlResult" :title="controlResult" type="warning" :closable="true" @close="controlResult = ''" />
        <el-descriptions :column="2" border>
          <el-descriptions-item label="编码">{{ selectedDataset?.code }}</el-descriptions-item>
          <el-descriptions-item label="当前版本">{{ datasetVersions.find(version => version.id === selectedDataset?.currentVersionId)?.versionNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="版本数">{{ datasetVersions.length }}</el-descriptions-item>
          <el-descriptions-item label="用例总数">{{ selectedDataset?.caseCount }}</el-descriptions-item>
        </el-descriptions>
        <div class="drawer-toolbar">
          <div><h3>最新版本用例（{{ datasetVersions.length ? datasetVersions[datasetVersions.length - 1].versionNo : '-' }} 版）</h3><p>输入与期望会脱敏展示；Trace 生成样本不包含消息原文。</p></div>
          <div class="filter-row">
            <el-select v-model="datasetCaseCategory" placeholder="分类" clearable style="width: 160px" @change="loadDatasetCases"><el-option v-for="category in ['intent-route','param-extraction','clarification','deny','confirmation-gate','tool-selection','knowledge-citation','trace-generated','manual']" :key="category" :label="category" :value="category" /></el-select>
            <el-button v-if="can('eval:write')" text type="primary" :loading="controlSubmitting" @click="addDatasetCase">新增用例</el-button>
            <el-button v-if="can('eval:write')" text type="primary" :loading="controlSubmitting" @click="importDatasetCases">批量导入</el-button>
            <el-button v-if="can('eval:write')" text type="primary" :loading="controlSubmitting" @click="generateCaseFromTrace">从 Trace 生成</el-button>
          </div>
        </div>
        <el-table :data="datasetVersionCases.items" empty-text="暂无用例">
          <el-table-column prop="caseKey" label="用例键" min-width="180" show-overflow-tooltip />
          <el-table-column prop="category" label="分类" width="160" show-overflow-tooltip />
          <el-table-column label="输入" min-width="200" show-overflow-tooltip><template #default="scope">{{ String(scope.row.input?.text || '') }}</template></el-table-column>
          <el-table-column label="期望" min-width="160" show-overflow-tooltip><template #default="scope">{{ JSON.stringify(scope.row.expected || {}) }}</template></el-table-column>
          <el-table-column prop="source" label="来源" width="90" />
          <el-table-column label="创建时间" min-width="160"><template #default="scope">{{ formatTime(scope.row.createdAt) }}</template></el-table-column>
        </el-table>
        <div class="pagination-row"><span>共 {{ datasetVersionCases.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="datasetCasePage" :page-size="datasetVersionCases.size" :total="datasetVersionCases.total" @current-change="changeDatasetCasePage" /></div>
      </div>
    </el-drawer>

    <el-drawer v-model="experimentRunsOpen" :title="selectedExperiment ? `实验结果 · ${selectedExperiment.displayName}` : '实验结果'" size="860px" class="runtime-drawer">
      <div v-if="selectedExperiment" class="drawer-content" v-loading="evalLoading">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="状态"><el-tag :type="statusType(selectedExperiment.status)">{{ selectedExperiment.status }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="进度">{{ selectedExperiment.completedCases }}/{{ selectedExperiment.totalCases }}</el-descriptions-item>
          <el-descriptions-item label="通过率">{{ selectedExperiment.passRate != null ? (Number(selectedExperiment.passRate) * 100).toFixed(1) + '%' : '-' }}</el-descriptions-item>
          <el-descriptions-item label="通过">{{ selectedExperiment.passedCases }}</el-descriptions-item>
          <el-descriptions-item label="失败">{{ selectedExperiment.failedCases }}</el-descriptions-item>
          <el-descriptions-item label="错误">{{ selectedExperiment.errorCases }}</el-descriptions-item>
          <el-descriptions-item label="成本">{{ (Number(selectedExperiment.costMicros || 0) / 1000).toFixed(1) }}m$</el-descriptions-item>
          <el-descriptions-item label="阈值">{{ selectedExperiment.thresholdPassRate != null ? (Number(selectedExperiment.thresholdPassRate) * 100).toFixed(1) + '%' : '-' }}</el-descriptions-item>
          <el-descriptions-item label="达标">{{ evalSummary?.passesThreshold === true ? '是' : '否' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="experimentRuns.items" empty-text="实验尚未运行或暂无结果">
          <el-table-column prop="caseKey" label="用例键" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="statusType(scope.row.status)" size="small">{{ scope.row.status }}</el-tag></template></el-table-column>
          <el-table-column label="通过" width="80"><template #default="scope">{{ scope.row.passed === true ? '是' : scope.row.passed === false ? '否' : '-' }}</template></el-table-column>
          <el-table-column label="评分" width="90"><template #default="scope">{{ scope.row.score != null ? Number(scope.row.score).toFixed(2) : '-' }}</template></el-table-column>
          <el-table-column label="Token" width="80"><template #default="scope">{{ scope.row.tokensUsed }}</template></el-table-column>
          <el-table-column prop="errorCode" label="错误码" width="150" show-overflow-tooltip />
          <el-table-column label="输出摘要" min-width="220" show-overflow-tooltip><template #default="scope">{{ scope.row.outputSummary || '-' }}</template></el-table-column>
        </el-table>
        <div class="pagination-row"><span>共 {{ experimentRuns.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="experimentRunsPage" :page-size="experimentRuns.size" :total="experimentRuns.total" @current-change="changeExperimentRunsPage" /></div>
      </div>
    </el-drawer>

    <el-drawer v-model="applicationDrawerOpen" title="Agent 应用详情" size="860px" class="runtime-drawer">
      <div v-if="selectedApplication" class="drawer-content" v-loading="applicationDetailLoading">
        <el-alert v-if="detailError" type="error" :closable="false" :title="detailError" />
        <el-descriptions :column="2" border>
          <el-descriptions-item label="编码">{{ selectedApplication.code }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ selectedApplication.displayName }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="statusType(selectedApplication.status)">{{ selectedApplication.status }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="OpenAPI"><el-button text type="primary" @click="showOpenApiSpec">查看受控 OpenAPI</el-button></el-descriptions-item>
        </el-descriptions>

        <section class="drawer-section">
          <div class="drawer-section-head">
            <h3>版本与发布</h3>
            <div class="filter-row">
              <el-button v-if="can('agentapp:write')" type="primary" size="small" :loading="controlSubmitting" @click="createApplicationVersion">创建草稿版本</el-button>
            </div>
          </div>
          <el-table :data="applicationVersions" empty-text="暂无版本">
            <el-table-column prop="version" label="版本" width="80" />
            <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="statusType(scope.row.status)" size="small">{{ scope.row.status }}</el-tag></template></el-table-column>
            <el-table-column prop="modelCode" label="模型" min-width="130" show-overflow-tooltip />
            <el-table-column label="Prompt" min-width="120" show-overflow-tooltip><template #default="scope">{{ promptLabel(scope.row.promptVersionId) }}</template></el-table-column>
            <el-table-column label="知识库" min-width="110"><template #default="scope">{{ scope.row.knowledgeBaseId ? '已绑定' : '-' }}</template></el-table-column>
            <el-table-column label="工具绑定" min-width="100"><template #default="scope">{{ (applicationBindings[scope.row.id] || []).length }} 项</template></el-table-column>
            <el-table-column label="发布时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.publishedAt) || '-' }}</template></el-table-column>
            <el-table-column label="操作" width="190" fixed="right">
              <template #default="scope">
                <el-button v-if="scope.row.status === 'DRAFT' && can('agentapp:publish')" text type="primary" :loading="controlSubmitting" @click="validateAndPublish(scope.row)">校验发布</el-button>
                <el-button v-if="scope.row.status === 'PUBLISHED' && can('agentapp:publish')" text type="primary" :loading="controlSubmitting" @click="rollbackApplication(scope.row)">回滚至此</el-button>
                <el-button v-if="scope.row.status === 'DRAFT' && can('agentapp:read')" text type="info" :loading="controlSubmitting" @click="validateApplicationVersion(scope.row)">校验</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section class="drawer-section">
          <div class="drawer-section-head">
            <h3>API Key</h3>
            <el-button v-if="can('apikey:write')" type="primary" size="small" :loading="controlSubmitting" @click="createApiKey">创建 API Key</el-button>
          </div>
          <el-table :data="applicationApiKeys" empty-text="暂无 API Key">
            <el-table-column prop="keyPrefix" label="前缀" min-width="130" />
            <el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="statusType(scope.row.status)" size="small">{{ scope.row.status }}</el-tag></template></el-table-column>
            <el-table-column label="作用域" min-width="150"><template #default="scope">{{ (scope.row.scopes || []).join(', ') }}</template></el-table-column>
            <el-table-column label="过期" min-width="160"><template #default="scope">{{ formatTime(scope.row.expiresAt) || '永不过期' }}</template></el-table-column>
            <el-table-column label="最近使用" min-width="160"><template #default="scope">{{ formatTime(scope.row.lastUsedAt) || '-' }}</template></el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="scope">
                <el-button v-if="scope.row.status === 'ACTIVE' && can('apikey:write')" text type="primary" :loading="controlSubmitting" @click="rotateApiKey(scope.row)">轮换</el-button>
                <el-button v-if="scope.row.status === 'ACTIVE' && can('apikey:write')" text type="danger" :loading="controlSubmitting" @click="revokeApiKey(scope.row)">撤销</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section class="drawer-section">
          <h3>发布记录</h3>
          <el-table :data="applicationRecords" empty-text="暂无发布记录">
            <el-table-column label="动作" width="110"><template #default="scope"><el-tag :type="scope.row.action === 'ROLLBACK' ? 'warning' : 'success'" size="small">{{ scope.row.action }}</el-tag></template></el-table-column>
            <el-table-column prop="versionId" label="版本 ID" min-width="220" show-overflow-tooltip />
            <el-table-column prop="previousVersionId" label="上一版本" min-width="220" show-overflow-tooltip />
            <el-table-column prop="actor" label="操作者" width="110" />
            <el-table-column label="时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.createdAt) }}</template></el-table-column>
          </el-table>
        </section>
      </div>
    </el-drawer>

    <el-dialog v-model="openApiDialogOpen" title="受控 OpenAPI" width="min(720px, calc(100vw - 32px))">
      <pre class="openapi-pre">{{ openApiSpecText }}</pre>
      <template #footer><el-button @click="openApiDialogOpen = false">关闭</el-button></template>
    </el-dialog>

    <el-drawer v-model="knowledgeChunksOpen" :title="selectedKnowledgeDocument ? `切片预览 · ${selectedKnowledgeDocument.name}` : '切片预览'" size="760px" class="runtime-drawer">
      <div class="drawer-content" v-loading="knowledgeLoading">
        <p class="drawer-note">为避免在管理端扩散原文，这里只显示切片元数据与启停状态。</p>
        <el-table :data="knowledgeChunkPage.items" empty-text="暂无可预览切片">
          <el-table-column prop="chunkIndex" label="序号" width="80" />
          <el-table-column prop="tokenCount" label="Token" width="100" />
          <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="110"><template #default="scope"><el-button v-if="can('knowledge:write')" text :type="scope.row.enabled ? 'danger' : 'primary'" :loading="controlSubmitting" @click="setKnowledgeChunkEnabled(scope.row, !scope.row.enabled)">{{ scope.row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
        </el-table>
        <div class="pagination-row"><span>共 {{ knowledgeChunkPage.total }} 条</span><el-pagination background layout="prev, pager, next" :current-page="knowledgeChunkPage.page" :page-size="knowledgeChunkPage.size" :total="knowledgeChunkPage.total" @current-change="changeKnowledgeChunkPage" /></div>
      </div>
    </el-drawer>

    <el-dialog v-model="knowledgeDialogOpen" :title="knowledgeBaseForm.id ? '配置知识库' : '新建知识库'" width="min(520px, calc(100vw - 32px))" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent="saveKnowledgeBase">
        <el-form-item label="知识库编码" required><el-input v-model="knowledgeBaseForm.code" :disabled="Boolean(knowledgeBaseForm.id)" maxlength="80" autocomplete="off" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="knowledgeBaseForm.displayName" maxlength="120" autocomplete="off" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="knowledgeBaseForm.description" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
        <el-form-item label="状态"><el-select v-model="knowledgeBaseForm.status"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="knowledgeDialogOpen = false">取消</el-button><el-button type="primary" :loading="controlSubmitting" @click="saveKnowledgeBase">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="workflowDialogOpen" title="新建工作流" width="min(520px, calc(100vw - 32px))" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent="createWorkflow">
        <el-form-item label="流程编码" required><el-input v-model="workflowForm.code" maxlength="120" placeholder="如 hotel-booking" autocomplete="off" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="workflowForm.displayName" maxlength="160" placeholder="如 酒店预订流程" autocomplete="off" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="workflowForm.description" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="workflowDialogOpen = false">取消</el-button><el-button type="primary" :loading="controlSubmitting" @click="createWorkflow">创建</el-button></template>
    </el-dialog>

    <el-drawer v-model="workflowEditorOpen" :title="selectedWorkflow ? `Workflow 编辑器 · ${selectedWorkflow.displayName}` : 'Workflow 编辑器'" size="900px" class="runtime-drawer">
      <div v-if="selectedWorkflow" class="drawer-content" v-loading="workflowVersionLoading">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="编码">{{ selectedWorkflow.code }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="statusType(selectedWorkflow.status)">{{ selectedWorkflow.status }}</el-tag></el-descriptions-item>
        </el-descriptions>
        <div class="workflow-editor-grid">
          <div>
            <div class="panel-header"><div><h3>DSL（schema 1.0）</h3><p>校验在保存与发布前统一执行；发布固定资源版本。</p></div></div>
            <el-input v-model="workflowDslJson" type="textarea" :rows="18" spellcheck="false" class="code-input" />
            <div class="panel-header"><div><h3>资源绑定 JSON</h3><p>modelVersionId / promptVersionId / knowledgeBaseVersionId / toolVersionIds</p></div></div>
            <el-input v-model="workflowBindingsJson" type="textarea" :rows="6" spellcheck="false" class="code-input" />
            <div class="workflow-editor-actions">
              <el-button v-if="can('workflow:write')" type="primary" :loading="workflowVersionLoading" @click="saveWorkflowVersion">保存为新版本</el-button>
              <el-button v-if="can('workflow:write') && selectedWorkflow.status !== 'ARCHIVED'" type="warning" :loading="workflowVersionLoading" @click="rollbackWorkflow">回滚到上一发布</el-button>
              <el-button v-if="can('workflow:write') && selectedWorkflow.status !== 'ARCHIVED'" type="danger" :loading="workflowVersionLoading" @click="archiveWorkflow">归档</el-button>
            </div>
          </div>
          <div>
            <div class="panel-header"><div><h3>版本快照</h3><p>版本不可变；运行固定到发布版本。</p></div></div>
            <el-table :data="workflowVersions" empty-text="暂无版本" max-height="520">
              <el-table-column prop="versionNo" label="版本" width="70" />
              <el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="240" fixed="right">
                <template #default="scope">
                  <el-button text type="primary" :loading="workflowVersionLoading" @click="validateWorkflowVersion(scope.row)">校验</el-button>
                  <el-button v-if="can('workflow:write') && scope.row.status === 'DRAFT'" text type="primary" :loading="workflowVersionLoading" @click="publishWorkflowVersion(scope.row)">发布</el-button>
                  <el-button v-if="can('workflow:run')" text type="primary" :loading="controlSubmitting" @click="debugRunVersion(scope.row)">调试运行</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-alert v-if="workflowValidation" :title="workflowValidation.valid ? '校验通过：DSL 与绑定均有效。' : '校验未通过，请先修复。'" :type="workflowValidation.valid ? 'success' : 'error'" :closable="true" @close="workflowValidation = null" />
            <ul v-if="workflowValidation && !workflowValidation.valid" class="issue-list">
              <li v-for="(issue, index) in workflowValidation.issues" :key="index">{{ issue.resourceType }}<template v-if="issue.resourceId">[{{ issue.resourceId }}]</template>：{{ issue.message }}</li>
            </ul>
          </div>
        </div>
      </div>
    </el-drawer>

    <el-drawer v-model="workflowRunDrawerOpen" :title="selectedWorkflowRun ? `运行详情 · ${selectedWorkflowRun.id}` : '运行详情'" size="880px" class="runtime-drawer">
      <div v-if="selectedWorkflowRun" class="drawer-content" v-loading="detailLoading">
        <div class="drawer-actions">
          <el-tag :type="statusType(selectedWorkflowRun.status)">{{ selectedWorkflowRun.status }}</el-tag>
          <span v-if="workflowEventStreaming" class="streaming-tag">SSE 连接中…</span>
          <el-button v-if="selectedWorkflowRun.status === 'PAUSED' && can('workflow:run')" type="primary" :loading="controlSubmitting" @click="resumeWorkflowRun">恢复</el-button>
          <el-button v-if="selectedWorkflowRun.status === 'PAUSED' && selectedWorkflowRun.currentNodeId && can('workflow:run')" type="primary" plain :loading="controlSubmitting" @click="openWorkflowInput">提交输入</el-button>
          <el-button v-if="selectedWorkflowRun.status === 'FAILED' && can('workflow:run')" type="primary" :loading="controlSubmitting" @click="retryWorkflowRun">重试</el-button>
          <el-button v-if="!terminalWorkflowStatus(selectedWorkflowRun.status) && can('workflow:run')" type="danger" plain :loading="controlSubmitting" @click="stopWorkflowRun">停止</el-button>
        </div>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="流程">{{ selectedWorkflowRun.code }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ selectedWorkflowRun.workflowVersionId }}</el-descriptions-item>
          <el-descriptions-item label="当前节点">{{ selectedWorkflowRun.currentNodeId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="错误码">{{ selectedWorkflowRun.errorCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="请求 ID">{{ selectedWorkflowRun.requestId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ formatTime(selectedWorkflowRun.startedAt) }}</el-descriptions-item>
        </el-descriptions>
        <div class="panel-header"><div><h3>节点执行</h3><p>写节点仅在确认后执行；RESULT_UNKNOWN 禁止自动重试。</p></div></div>
        <el-table :data="workflowNodeRuns" empty-text="暂无节点运行记录">
          <el-table-column prop="nodeId" label="节点" min-width="120" />
          <el-table-column prop="nodeType" label="类型" min-width="140" />
          <el-table-column label="状态" width="170"><template #default="scope"><el-tag :type="workflowNodeTagType(scope.row.status)">{{ scope.row.status }}</el-tag></template></el-table-column>
          <el-table-column prop="retryCount" label="重试" width="70" />
          <el-table-column label="错误" min-width="120"><template #default="scope">{{ scope.row.errorCode || '-' }}</template></el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="scope">
              <el-button v-if="scope.row.status === 'WAITING_CONFIRMATION' && can('workflow:run')" text type="primary" @click="openWorkflowConfirm(scope.row)">确认</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="panel-header"><div><h3>事件流（SSE）</h3><p>按 sequence 排序；终端状态后自动断流。</p></div></div>
        <div class="workflow-event-stream" ref="workflowEventStreamRef">
          <div v-if="workflowEvents.length === 0" class="empty-state">暂无事件</div>
          <article v-for="event in workflowEvents" :key="event.id" class="workflow-event-item">
            <span class="event-seq">#{{ event.sequence }}</span>
            <el-tag size="small" effect="plain">{{ event.type }}</el-tag>
            <code>{{ JSON.stringify(event.payload) }}</code>
          </article>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="workflowInputOpen" title="提交输入" width="min(520px, calc(100vw - 32px))" :close-on-click-modal="false">
      <p class="dialog-hint">节点 <b>{{ selectedWorkflowRun?.currentNodeId }}</b> 等待输入，提交 JSON 对象作为变量。</p>
      <el-input v-model="workflowInputValues" type="textarea" :rows="6" spellcheck="false" class="code-input" placeholder='{"city": "上海"}' />
      <template #footer><el-button @click="workflowInputOpen = false">取消</el-button><el-button type="primary" :loading="controlSubmitting" @click="submitWorkflowInput">提交</el-button></template>
    </el-dialog>

    <el-dialog v-model="workflowConfirmOpen" title="确认门禁" width="min(520px, calc(100vw - 32px))" :close-on-click-modal="false">
      <p class="dialog-hint">节点 <b>{{ workflowConfirmNodeId }}</b> 为写操作，必须经确认门禁执行。确认版本 <b>v{{ workflowConfirmVersion }}</b> 由服务端快照校验。</p>
      <template #footer>
        <el-button @click="workflowConfirmOpen = false">取消</el-button>
        <el-button type="danger" :loading="controlSubmitting" @click="confirmWorkflowNode('REJECTED')">拒绝</el-button>
        <el-button type="primary" :loading="controlSubmitting" @click="confirmWorkflowNode('CONFIRMED')">确认执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>
