<script setup lang="ts">
import {
  ApiOutlined,
  AppstoreOutlined,
  ArrowRightOutlined,
  BarChartOutlined,
  CaretDownFilled,
  CaretRightFilled,
  DashboardOutlined,
  DeploymentUnitOutlined,
  DollarOutlined,
  EditOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  HddOutlined,
  KeyOutlined,
  LockOutlined,
  LogoutOutlined,
  MessageOutlined,
  MonitorOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SearchOutlined,
  SettingOutlined,
  TagsOutlined,
  UserOutlined,
  WarningFilled,
} from '@vicons/antd'
import { NButton, NProgress, NTag, dateZhCN, zhCN, type DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, reactive, ref, type Component } from 'vue'
import { ConsoleApiError, consoleApi, MAX_KNOWLEDGE_DOCUMENT_BYTES, streamWorkflowEvents } from './api/console'
import AppIcon from './components/AppIcon.vue'
import { dialog, themeOverrides } from './naive'
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
  ToolExecution,
  TaskDetail,
  AuditRecord,
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

interface ConsoleMenuItem {
  key: ConsoleSection
  label: string
  icon: Component
}

interface ConsoleMenuGroup {
  key: string
  label: string
  icon: Component
  items: ConsoleMenuItem[]
}

const menuGroups: ConsoleMenuGroup[] = [
  {
    key: 'runtime',
    label: '运行与监控',
    icon: DashboardOutlined,
    items: [
      { key: 'conversations', label: '会话事件', icon: MessageOutlined },
      { key: 'tasks', label: '任务记录', icon: TagsOutlined },
      { key: 'traces', label: 'Trace 与指标', icon: MonitorOutlined },
    ],
  },
  {
    key: 'capability',
    label: 'Agent 能力',
    icon: ApiOutlined,
    items: [
      { key: 'agents', label: '领域 Agent', icon: ApiOutlined },
      { key: 'applications', label: 'Agent 应用', icon: FileTextOutlined },
      { key: 'workflows', label: 'Workflow 编排', icon: DeploymentUnitOutlined },
      { key: 'workflowRuns', label: 'Workflow 运行', icon: PlayCircleOutlined },
      { key: 'knowledge', label: '知识库', icon: FolderOpenOutlined },
    ],
  },
  {
    key: 'resource',
    label: '模型与工具',
    icon: HddOutlined,
    items: [
      { key: 'models', label: '模型管理', icon: HddOutlined },
      { key: 'prompts', label: 'Prompt 管理', icon: EditOutlined },
      { key: 'secrets', label: 'Secret 引用', icon: KeyOutlined },
      { key: 'mcpServers', label: 'MCP Server', icon: ApiOutlined },
      { key: 'mcpTools', label: '工具目录', icon: AppstoreOutlined },
    ],
  },
  {
    key: 'quality',
    label: '质量评测',
    icon: DollarOutlined,
    items: [
      { key: 'datasets', label: '数据集', icon: FileTextOutlined },
      { key: 'evaluators', label: '评估器', icon: TagsOutlined },
      { key: 'experiments', label: '评估实验', icon: DollarOutlined },
    ],
  },
  {
    key: 'system',
    label: '系统设置',
    icon: SettingOutlined,
    items: [
      { key: 'config', label: '运行配置', icon: SettingOutlined },
    ],
  },
]

/** 顶栏面包屑：分组内的页面显示「分组 / 页面」，顶层页面只显示页面名。 */
const breadcrumb = computed(() => {
  const group = menuGroups.find((g) => g.items.some((item) => item.key === active.value))
  return group ? { group: group.label, page: title.value } : { page: title.value }
})

const openGroups = reactive(new Set(menuGroups.map(group => group.key)))

function toggleGroup(key: string): void {
  if (openGroups.has(key)) {
    openGroups.delete(key)
  } else {
    openGroups.add(key)
  }
}

interface ConfirmOptions {
  title: string
  content: string
  positiveText?: string
  negativeText?: string
}

/** 基于 Naive UI 离散 Dialog 的二次确认；返回用户是否确认。 */
function confirmAction(options: ConfirmOptions): Promise<boolean> {
  return new Promise(resolve => {
    dialog.warning({
      title: options.title,
      content: options.content,
      positiveText: options.positiveText || '确定',
      negativeText: options.negativeText || '取消',
      onPositiveClick: () => resolve(true),
      onNegativeClick: () => resolve(false),
      onClose: () => resolve(false),
      onMaskClick: () => resolve(false),
    })
  })
}

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
  const confirmed = await confirmAction({
    title: '删除文档',
    content: `将删除文档“${document.name}”及其当前索引数据，此操作不可撤销。`,
    positiveText: '删除',
    negativeText: '取消',
  })
  if (!confirmed) return
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
  const confirmed = await confirmAction({
    title: '确认归档',
    content: `归档应用 ${application.code}？归档后不能发布新版本；仍被有效 API Key 引用时会被拒绝。`,
  })
  if (!confirmed) return
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
  const confirmed = await confirmAction({
    title: '确认回滚',
    content: `将应用 ${application.code} 回滚到版本 ${version.version}？将记录新的回滚审计。`,
  })
  if (!confirmed) return
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
  const confirmed = await confirmAction({
    title: '确认轮换',
    content: `轮换 API Key ${key.keyPrefix}？旧 Key 将立即失效。`,
  })
  if (!confirmed) return
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
  const confirmed = await confirmAction({
    title: '确认撤销',
    content: `撤销 API Key ${key.keyPrefix}？撤销后立即失效。`,
  })
  if (!confirmed) return
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
  const confirmed = await confirmAction({
    title: '归档确认',
    content: `确定归档工作流「${selectedWorkflow.value.displayName}」？归档后不能创建新版本。`,
    positiveText: '归档',
    negativeText: '取消',
  })
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
  const confirmed = await confirmAction({
    title: '回滚确认',
    content: `确定回滚工作流「${workflow.displayName}」到上一个已发布版本？`,
    positiveText: '回滚',
    negativeText: '取消',
  })
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

function workflowNodeTagType(status: string): 'success' | 'warning' | 'info' | 'error' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'error'
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

function statusType(status: string): 'success' | 'warning' | 'error' | 'info' | 'primary' {
  if (status === 'SUCCEEDED' || status === 'READY') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED' || status === 'EXPIRED') return 'error'
  if (status.includes('WAITING') || status === 'UNKNOWN' || status === 'MANUAL') return 'warning'
  return 'info'
}

function summary(value: Record<string, unknown>): string {
  return Object.keys(value).length === 0 ? '-' : JSON.stringify(value)
}

// ---- Naive UI 选择项与表格列定义 ----
const TASK_STATUS_OPTIONS = ['WAITING_CONFIRMATION', 'WAITING_EXTERNAL_RESULT', 'UNKNOWN', 'MANUAL', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED']
  .map(value => ({ label: value, value }))
const TRACE_TYPE_OPTIONS = ['REQUEST', 'ROUTE', 'TASK', 'ACTION', 'TOOL', 'MODEL', 'RETRIEVAL', 'WORKFLOW', 'EVALUATION']
  .map(value => ({ label: value, value }))
const TRACE_STATUS_OPTIONS = ['OK', 'ERROR', 'TIMEOUT', 'UNKNOWN', 'SKIPPED']
  .map(value => ({ label: value, value }))
const EXPERIMENT_STATUS_OPTIONS = ['DRAFT', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'STOPPED']
  .map(value => ({ label: value, value }))
const WORKFLOW_RUN_STATUS_OPTIONS = ['RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED', 'STOPPED']
  .map(value => ({ label: value, value }))
const DATASET_CASE_CATEGORY_OPTIONS = ['intent-route', 'param-extraction', 'clarification', 'deny', 'confirmation-gate', 'tool-selection', 'knowledge-citation', 'trace-generated', 'manual']
  .map(value => ({ label: value, value }))
const KNOWLEDGE_BASE_STATUS_OPTIONS = [
  { label: '启用', value: 'ACTIVE' },
  { label: '停用', value: 'DISABLED' },
]

function renderCellActions(nodes: Array<import('vue').VNodeChild>): ReturnType<typeof h> {
  return h('div', { class: 'cell-actions' }, nodes)
}

const conversationColumns: DataTableColumns<RuntimeConversation> = [
  { title: '会话', key: 'title', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '会话 ID', key: 'id', minWidth: 190, ellipsis: { tooltip: true } },
  { title: '访客摘要', key: 'visitorRef', minWidth: 160 },
  { title: '当前 Agent', key: 'activeAgentName', minWidth: 140 },
  { title: '最近活动', key: 'lastMessageAt', minWidth: 180, render: row => formatTime(row.lastMessageAt) },
  { title: '操作', key: 'actions', width: 90, render: row => h(NButton, { text: true, type: 'primary', onClick: () => void openConversation(row) }, { default: () => '详情' }) },
]

const taskColumns: DataTableColumns<RuntimeTask> = [
  { title: '任务 ID', key: 'id', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '动作', key: 'actionCode', minWidth: 190 },
  { title: '状态', key: 'status', width: 190, render: row => h(NTag, { type: statusType(row.status) }, { default: () => row.status }) },
  { title: '外部引用', key: 'externalRef', minWidth: 160 },
  { title: '更新时间', key: 'updatedAt', minWidth: 180, render: row => formatTime(row.updatedAt) },
  { title: '操作', key: 'actions', width: 90, render: row => h(NButton, { text: true, type: 'primary', onClick: () => void openTask(row) }, { default: () => '详情' }) },
]

const modelColumns: DataTableColumns<ControlResource> = [
  { title: '编码', key: 'code', minWidth: 160 },
  { title: '类型', key: 'modelType', width: 110 },
  { title: '模型', key: 'modelName', minWidth: 180 },
  { title: 'Secret', key: 'secretConfigured', width: 140, render: row => h(NTag, { type: row.secretConfigured ? 'success' : 'info' }, { default: () => row.secretConfigured ? '已配置' : '未配置' }) },
  { title: '更新时间', key: 'updatedAt', minWidth: 170, render: row => formatTime(row.updatedAt) },
  {
    title: '操作', key: 'actions', width: 100,
    render: row => can('model:test')
      ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void testModel(row) }, { default: () => '连接测试' })
      : null,
  },
]

const secretColumns: DataTableColumns<ControlResource> = [
  { title: '名称', key: 'name', minWidth: 180 },
  { title: '类型', key: 'secretRefType', width: 140 },
  { title: '状态', key: 'configured', width: 140, render: row => h(NTag, { type: row.configured ? 'success' : 'info' }, { default: () => row.configured ? '已配置' : '未配置' }) },
  { title: '更新时间', key: 'updatedAt', minWidth: 170, render: row => formatTime(row.updatedAt) },
]

const promptColumns: DataTableColumns<ControlResource> = [
  { title: '编码', key: 'code', minWidth: 180 },
  { title: '名称', key: 'displayName', minWidth: 180 },
  { title: '发布状态', key: 'publishedVersionId', minWidth: 180, render: row => h(NTag, { type: row.publishedVersionId ? 'success' : 'info' }, { default: () => row.publishedVersionId ? '已发布' : '草稿' }) },
  {
    title: '操作', key: 'actions', width: 120,
    render: row => can('prompt:publish')
      ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void versionAndPublish(row) }, { default: () => '创建并发布' })
      : null,
  },
]

const mcpServerColumns: DataTableColumns<McpServer> = [
  { title: '编码', key: 'code', minWidth: 150 },
  { title: 'Transport', key: 'transport', width: 150 },
  { title: '状态', key: 'enabled', width: 135, render: row => h(NTag, { type: row.enabled ? 'success' : 'info' }, { default: () => row.enabled ? '启用' : '停用' }) },
  { title: '健康', key: 'healthStatus', width: 150 },
  { title: '最近同步', key: 'lastSyncedAt', minWidth: 170, render: row => formatTime(row.lastSyncedAt) },
  {
    title: '操作', key: 'actions', width: 160,
    render: row => renderCellActions([
      can('mcp:test') ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void testMcpServer(row) }, { default: () => '测试' }) : null,
      can('mcp:sync') ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void syncMcpServer(row) }, { default: () => '同步' }) : null,
    ]),
  },
]

const mcpToolColumns: DataTableColumns<McpTool> = [
  { title: 'Tool', key: 'name', minWidth: 190 },
  {
    title: '风险', key: 'riskLevel', width: 110,
    render: row => h(NTag, {
      type: row.riskLevel === 'HIGH' ? 'error' : row.riskLevel === 'MEDIUM' ? 'warning' : 'success',
    }, { default: () => row.riskLevel }),
  },
  { title: '类型', key: 'writeTool', width: 110, render: row => row.writeTool ? '写操作' : '只读' },
  { title: '状态', key: 'enabled', width: 110, render: row => h(NTag, { type: row.enabled ? 'success' : 'info' }, { default: () => row.enabled ? '启用' : '停用' }) },
  {
    title: '操作', key: 'actions', width: 180,
    render: row => renderCellActions([
      can('mcp:write') ? h(NButton, { text: true, type: row.enabled ? 'error' : 'primary', loading: controlSubmitting.value, onClick: () => void setMcpToolEnabled(row, !row.enabled) }, { default: () => row.enabled ? '停用' : '启用' }) : null,
      can('mcp:test') ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void debugMcpTool(row) }, { default: () => 'Debug' }) : null,
    ]),
  },
]

const knowledgeRetrievalColumns: DataTableColumns<KnowledgeRetrievalMatch> = [
  { title: '分数', key: 'score', width: 100, render: row => row.score.toFixed(3) },
  { title: '重排分数', key: 'rerankScore', width: 120, render: row => row.rerankScore?.toFixed(3) || '-' },
  { title: '文档', key: 'document', minWidth: 150, render: row => row.citation.documentName || row.citation.documentId },
  {
    title: '引用定位', key: 'citation', minWidth: 190, ellipsis: { tooltip: true },
    render: row => `${row.citation.chunkId}${row.citation.chunkIndex !== undefined ? ` · #${row.citation.chunkIndex}` : ''}`,
  },
]

const knowledgeDocumentColumns: DataTableColumns<KnowledgeDocument> = [
  { title: '文件', key: 'name', minWidth: 170, ellipsis: { tooltip: true } },
  { title: '格式', key: 'contentType', minWidth: 120, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 130, render: row => h(NTag, { type: statusType(row.status) }, { default: () => row.status }) },
  {
    title: '操作', key: 'actions', width: 210,
    render: row => renderCellActions([
      h(NButton, { text: true, type: 'primary', onClick: () => void openKnowledgeChunks(row) }, { default: () => '切片' }),
      can('knowledge:write') ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void reindexKnowledgeDocument(row) }, { default: () => '重建' }) : null,
      can('knowledge:write') ? h(NButton, { text: true, type: 'error', loading: controlSubmitting.value, onClick: () => void deleteKnowledgeDocument(row) }, { default: () => '删除' }) : null,
    ]),
  },
]

const knowledgeJobColumns: DataTableColumns<KnowledgeIndexJob> = [
  { title: '任务', key: 'id', minWidth: 160, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 125, render: row => h(NTag, { type: statusType(row.status) }, { default: () => row.status }) },
  { title: '尝试', key: 'attempts', width: 80 },
  { title: '失败原因', key: 'lastErrorCode', minWidth: 120, ellipsis: { tooltip: true } },
  {
    title: '操作', key: 'actions', width: 85,
    render: row => row.status === 'FAILED' && can('knowledge:write')
      ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void retryKnowledgeJob(row) }, { default: () => '重试' })
      : null,
  },
]

const knowledgeChunkColumns: DataTableColumns<KnowledgeChunk> = [
  { title: '序号', key: 'chunkIndex', width: 80 },
  { title: 'Token', key: 'tokenCount', width: 100 },
  { title: '状态', key: 'enabled', width: 120, render: row => h(NTag, { type: row.enabled ? 'success' : 'info' }, { default: () => row.enabled ? '启用' : '停用' }) },
  {
    title: '操作', key: 'actions', width: 110,
    render: row => can('knowledge:write')
      ? h(NButton, { text: true, type: row.enabled ? 'error' : 'primary', loading: controlSubmitting.value, onClick: () => void setKnowledgeChunkEnabled(row, !row.enabled) }, { default: () => row.enabled ? '停用' : '启用' })
      : null,
  },
]

const applicationColumns: DataTableColumns<AgentApplication> = [
  { title: '编码', key: 'code', minWidth: 160, ellipsis: { tooltip: true } },
  { title: '名称', key: 'displayName', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 120, render: row => h(NTag, { type: statusType(row.status) }, { default: () => row.status }) },
  { title: '已发布版本', key: 'currentVersionId', width: 120, render: row => row.currentVersionId ? versionLabel(row) : '-' },
  { title: '更新时间', key: 'updatedAt', minWidth: 170, render: row => formatTime(row.updatedAt) },
  {
    title: '操作', key: 'actions', width: 180,
    render: row => renderCellActions([
      h(NButton, { text: true, type: 'primary', onClick: () => void openApplicationDrawer(row) }, { default: () => '管理' }),
      can('agentapp:write') ? h(NButton, { text: true, type: 'error', loading: controlSubmitting.value, onClick: () => void archiveApplication(row) }, { default: () => '归档' }) : null,
    ]),
  },
]

const traceColumns: DataTableColumns<TraceSpan> = [
  { title: 'Trace ID', key: 'traceId', minWidth: 200, ellipsis: { tooltip: true } },
  { title: '类型', key: 'spanType', width: 110, render: row => h(NTag, { size: 'small' }, { default: () => row.spanType }) },
  { title: '名称', key: 'name', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 110, render: row => h(NTag, { type: statusType(row.status), size: 'small' }, { default: () => row.status }) },
  { title: '开始时间', key: 'startedAt', minWidth: 170, render: row => formatTime(row.startedAt) },
  {
    title: '操作', key: 'actions', width: 110,
    render: row => h(NButton, { text: true, type: 'primary', onClick: () => void openTraceDetail(row.traceId) }, { default: () => '完整 Trace' }),
  },
]

const datasetColumns: DataTableColumns<EvalDataset> = [
  { title: '编码', key: 'code', minWidth: 160, ellipsis: { tooltip: true } },
  { title: '名称', key: 'displayName', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '用例数', key: 'caseCount', width: 100 },
  { title: '状态', key: 'status', width: 110, render: row => h(NTag, { type: statusType(row.status) }, { default: () => row.status }) },
  { title: '更新时间', key: 'updatedAt', minWidth: 170, render: row => formatTime(row.updatedAt) },
  { title: '操作', key: 'actions', width: 110, render: row => h(NButton, { text: true, type: 'primary', onClick: () => void openDatasetDrawer(row) }, { default: () => '管理' }) },
]

const evaluatorColumns: DataTableColumns<EvalEvaluator> = [
  { title: '编码', key: 'code', minWidth: 160, ellipsis: { tooltip: true } },
  { title: '名称', key: 'displayName', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '类型', key: 'evaluatorType', width: 170, render: row => h(NTag, { size: 'small' }, { default: () => row.evaluatorType }) },
  { title: '版本数', key: 'versions', width: 100, render: row => row.versions.length },
  { title: '状态', key: 'status', width: 110, render: row => h(NTag, { type: statusType(row.status) }, { default: () => row.status }) },
  {
    title: '操作', key: 'actions', width: 150,
    render: row => can('eval:write')
      ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void createEvaluatorVersion(row) }, { default: () => '新建版本' })
      : null,
  },
]

const experimentColumns: DataTableColumns<EvalExperiment> = [
  { title: '编码', key: 'code', minWidth: 150, ellipsis: { tooltip: true } },
  { title: '名称', key: 'displayName', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 120, render: row => h(NTag, { type: statusType(row.status) }, { default: () => row.status }) },
  {
    title: '进度', key: 'progress', minWidth: 150,
    render: row => h('div', { style: 'display:grid;gap:6px' }, [
      `${row.completedCases}/${row.totalCases}`,
      row.totalCases ? h(NProgress, { type: 'line', percentage: Math.round(row.completedCases * 100 / row.totalCases), height: 6, style: 'width:100%' }) : null,
    ]),
  },
  { title: '通过率', key: 'passRate', width: 110, render: row => row.passRate != null ? `${(Number(row.passRate) * 100).toFixed(1)}%` : '-' },
  { title: '成本', key: 'costMicros', width: 100, render: row => `${(Number(row.costMicros || 0) / 1000).toFixed(1)}m$` },
  {
    title: '操作', key: 'actions', width: 260,
    render: row => renderCellActions([
      h(NButton, { text: true, type: 'primary', onClick: () => void openExperimentRuns(row) }, { default: () => '结果' }),
      row.status === 'DRAFT' && can('eval:run') ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void startExperiment(row) }, { default: () => '启动' }) : null,
      row.status === 'RUNNING' && can('eval:run') ? h(NButton, { text: true, type: 'warning', loading: controlSubmitting.value, onClick: () => void stopExperiment(row) }, { default: () => '停止' }) : null,
      (row.status === 'STOPPED' || row.status === 'PARTIAL') && can('eval:run') ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void retryExperiment(row) }, { default: () => '重试' }) : null,
    ]),
  },
]

const workflowColumns: DataTableColumns<WorkflowDefinition> = [
  { title: '编码', key: 'code', minWidth: 150, ellipsis: { tooltip: true } },
  { title: '名称', key: 'displayName', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 120, render: row => h(NTag, { type: statusType(row.status) }, { default: () => row.status }) },
  { title: '当前版本', key: 'currentVersionId', width: 160, render: row => row.currentVersionId ? `已发布 v${workflowVersionNo(row)}` : '未发布' },
  { title: '创建人', key: 'createdBy', width: 110 },
  { title: '更新时间', key: 'updatedAt', minWidth: 170, render: row => formatTime(row.updatedAt) },
  {
    title: '操作', key: 'actions', width: 190,
    render: row => renderCellActions([
      h(NButton, { text: true, type: 'primary', onClick: () => void openWorkflowEditor(row) }, { default: () => '编辑' }),
      can('workflow:write') && row.status === 'ACTIVE' ? h(NButton, { text: true, type: 'warning', loading: controlSubmitting.value, onClick: () => void rollbackWorkflowFromList(row) }, { default: () => '回滚' }) : null,
      can('workflow:write') && row.status !== 'ARCHIVED' ? h(NButton, { text: true, type: 'error', onClick: () => void archiveWorkflowFromList(row) }, { default: () => '归档' }) : null,
    ]),
  },
]

const workflowRunColumns: DataTableColumns<WorkflowRunView> = [
  { title: '运行 ID', key: 'id', minWidth: 200, ellipsis: { tooltip: true } },
  { title: '流程', key: 'code', minWidth: 140, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 130, render: row => h(NTag, { type: statusType(row.status) }, { default: () => row.status }) },
  { title: '当前节点', key: 'currentNodeId', minWidth: 140, render: row => row.currentNodeId || '-' },
  { title: '开始时间', key: 'startedAt', minWidth: 170, render: row => formatTime(row.startedAt) },
  { title: '操作', key: 'actions', width: 100, render: row => h(NButton, { text: true, type: 'primary', onClick: () => void openWorkflowRun(row) }, { default: () => '详情' }) },
]

const toolExecutionColumns: DataTableColumns<ToolExecution> = [
  { title: '工具', key: 'toolCode', minWidth: 170 },
  { title: '状态', key: 'status', width: 120, render: row => h(NTag, { type: statusType(row.status), size: 'small' }, { default: () => row.status }) },
  { title: '输入摘要', key: 'inputSummary', minWidth: 180, ellipsis: { tooltip: true }, render: row => summary(row.inputSummary) },
  { title: '输出摘要', key: 'outputSummary', minWidth: 180, ellipsis: { tooltip: true }, render: row => summary(row.outputSummary) },
]

const auditColumns: DataTableColumns<AuditRecord> = [
  { title: '事件', key: 'eventType', minWidth: 180 },
  { title: '操作者', key: 'actorType', width: 110 },
  { title: '请求 ID', key: 'requestId', minWidth: 170, ellipsis: { tooltip: true } },
  { title: '时间', key: 'createdAt', minWidth: 180, render: row => formatTime(row.createdAt) },
]

const spanColumns: DataTableColumns<TraceSpan> = [
  { title: '类型', key: 'spanType', width: 110, render: row => h(NTag, { size: 'small' }, { default: () => row.spanType }) },
  { title: '名称', key: 'name', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 100, render: row => h(NTag, { type: statusType(row.status), size: 'small' }, { default: () => row.status }) },
  { title: '耗时', key: 'durationMs', width: 90, render: row => `${row.durationMs}ms` },
  { title: 'Token', key: 'totalTokens', width: 80, render: row => row.totalTokens },
  { title: '错误码', key: 'errorCode', width: 130, ellipsis: { tooltip: true } },
  { title: '开始', key: 'startedAt', minWidth: 170, render: row => formatTime(row.startedAt) },
]

const datasetCaseColumns: DataTableColumns<EvalCase> = [
  { title: '用例键', key: 'caseKey', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '分类', key: 'category', minWidth: 160, ellipsis: { tooltip: true } },
  { title: '输入', key: 'input', minWidth: 200, ellipsis: { tooltip: true }, render: row => String(row.input?.text || '') },
  { title: '期望', key: 'expected', minWidth: 160, ellipsis: { tooltip: true }, render: row => JSON.stringify(row.expected || {}) },
  { title: '来源', key: 'source', width: 90 },
  { title: '创建时间', key: 'createdAt', minWidth: 160, render: row => formatTime(row.createdAt) },
]

const experimentRunColumns: DataTableColumns<EvalExperimentRun> = [
  { title: '用例键', key: 'caseKey', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 110, render: row => h(NTag, { type: statusType(row.status), size: 'small' }, { default: () => row.status }) },
  { title: '通过', key: 'passed', width: 80, render: row => row.passed === true ? '是' : row.passed === false ? '否' : '-' },
  { title: '评分', key: 'score', width: 90, render: row => row.score != null ? Number(row.score).toFixed(2) : '-' },
  { title: 'Token', key: 'tokensUsed', width: 80 },
  { title: '错误码', key: 'errorCode', width: 150, ellipsis: { tooltip: true } },
  { title: '输出摘要', key: 'outputSummary', minWidth: 220, ellipsis: { tooltip: true }, render: row => row.outputSummary || '-' },
]

const applicationVersionColumns: DataTableColumns<AgentApplicationVersion> = [
  { title: '版本', key: 'version', width: 80 },
  { title: '状态', key: 'status', width: 120, render: row => h(NTag, { type: statusType(row.status), size: 'small' }, { default: () => row.status }) },
  { title: '模型', key: 'modelCode', minWidth: 130, ellipsis: { tooltip: true } },
  { title: 'Prompt', key: 'promptVersionId', minWidth: 120, ellipsis: { tooltip: true }, render: row => promptLabel(row.promptVersionId) },
  { title: '知识库', key: 'knowledgeBaseId', minWidth: 110, render: row => row.knowledgeBaseId ? '已绑定' : '-' },
  { title: '工具绑定', key: 'bindings', minWidth: 100, render: row => `${(applicationBindings.value[row.id] || []).length} 项` },
  { title: '发布时间', key: 'publishedAt', minWidth: 170, render: row => formatTime(row.publishedAt) || '-' },
  {
    title: '操作', key: 'actions', width: 190,
    render: row => renderCellActions([
      row.status === 'DRAFT' && can('agentapp:publish') ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void validateAndPublish(row) }, { default: () => '校验发布' }) : null,
      row.status === 'PUBLISHED' && can('agentapp:publish') ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void rollbackApplication(row) }, { default: () => '回滚至此' }) : null,
      row.status === 'DRAFT' && can('agentapp:read') ? h(NButton, { text: true, type: 'info', loading: controlSubmitting.value, onClick: () => void validateApplicationVersion(row) }, { default: () => '校验' }) : null,
    ]),
  },
]

const apiKeyColumns: DataTableColumns<AgentAppApiKey> = [
  { title: '前缀', key: 'keyPrefix', minWidth: 130 },
  { title: '状态', key: 'status', width: 110, render: row => h(NTag, { type: statusType(row.status), size: 'small' }, { default: () => row.status }) },
  { title: '作用域', key: 'scopes', minWidth: 150, render: row => (row.scopes || []).join(', ') },
  { title: '过期', key: 'expiresAt', minWidth: 160, render: row => formatTime(row.expiresAt) || '永不过期' },
  { title: '最近使用', key: 'lastUsedAt', minWidth: 160, render: row => formatTime(row.lastUsedAt) || '-' },
  {
    title: '操作', key: 'actions', width: 150,
    render: row => renderCellActions([
      row.status === 'ACTIVE' && can('apikey:write') ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void rotateApiKey(row) }, { default: () => '轮换' }) : null,
      row.status === 'ACTIVE' && can('apikey:write') ? h(NButton, { text: true, type: 'error', loading: controlSubmitting.value, onClick: () => void revokeApiKey(row) }, { default: () => '撤销' }) : null,
    ]),
  },
]

const publishRecordColumns: DataTableColumns<AgentAppPublishRecord> = [
  { title: '动作', key: 'action', width: 110, render: row => h(NTag, { type: row.action === 'ROLLBACK' ? 'warning' : 'success', size: 'small' }, { default: () => row.action }) },
  { title: '版本 ID', key: 'versionId', minWidth: 220, ellipsis: { tooltip: true } },
  { title: '上一版本', key: 'previousVersionId', minWidth: 220, ellipsis: { tooltip: true } },
  { title: '操作者', key: 'actor', width: 110 },
  { title: '时间', key: 'createdAt', minWidth: 170, render: row => formatTime(row.createdAt) },
]

const workflowVersionColumns: DataTableColumns<WorkflowVersionView> = [
  { title: '版本', key: 'versionNo', width: 70 },
  { title: '状态', key: 'status', width: 110, render: row => h(NTag, { type: statusType(row.status) }, { default: () => row.status }) },
  {
    title: '操作', key: 'actions', width: 240,
    render: row => renderCellActions([
      h(NButton, { text: true, type: 'primary', loading: workflowVersionLoading.value, onClick: () => void validateWorkflowVersion(row) }, { default: () => '校验' }),
      can('workflow:write') && row.status === 'DRAFT' ? h(NButton, { text: true, type: 'primary', loading: workflowVersionLoading.value, onClick: () => void publishWorkflowVersion(row) }, { default: () => '发布' }) : null,
      can('workflow:run') ? h(NButton, { text: true, type: 'primary', loading: controlSubmitting.value, onClick: () => void debugRunVersion(row) }, { default: () => '调试运行' }) : null,
    ]),
  },
]

const workflowNodeRunColumns: DataTableColumns<WorkflowNodeRunView> = [
  { title: '节点', key: 'nodeId', minWidth: 120 },
  { title: '类型', key: 'nodeType', minWidth: 140 },
  { title: '状态', key: 'status', width: 170, render: row => h(NTag, { type: workflowNodeTagType(row.status) }, { default: () => row.status }) },
  { title: '重试', key: 'retryCount', width: 70 },
  { title: '错误', key: 'errorCode', minWidth: 120, render: row => row.errorCode || '-' },
  {
    title: '操作', key: 'actions', width: 150,
    render: row => row.status === 'WAITING_CONFIRMATION' && can('workflow:run')
      ? h(NButton, { text: true, type: 'primary', onClick: () => void openWorkflowConfirm(row) }, { default: () => '确认' })
      : null,
  },
]

onMounted(() => {
  if (sessionToken.value) {
    void load()
  } else {
    void refreshCaptcha()
  }
})
</script>

<template>
  <n-config-provider :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
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
              <div><n-icon :size="18"><HddOutlined /></n-icon><small>Runtime</small><strong>CODE-FIRST</strong></div>
              <div><n-icon :size="18"><LockOutlined /></n-icon><small>高风险动作</small><strong>二次确认</strong></div>
              <div><n-icon :size="18"><DollarOutlined /></n-icon><small>运行数据</small><strong>SERVER MANAGED</strong></div>
            </div>
            <div class="preview-list">
              <div><span>访客身份</span><b>签名 Cookie 隔离</b></div>
              <div><span>动作执行</span><b>确定性代码校验</b></div>
              <div><span>状态追踪</span><b>任务与事件贯穿</b></div>
            </div>
          </section>
        </div>

        <footer class="login-intro-footer">
          <span><n-icon :size="14"><LockOutlined /></n-icon>管理端与匿名访客会话严格隔离</span>
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
          <n-alert v-if="error" type="error" :closable="false" :show-icon="false">
            <template #header><span class="alert-title"><n-icon :size="16"><WarningFilled /></n-icon>{{ error }}</span></template>
          </n-alert>
          <div class="field-group">
            <label for="username">用户名</label>
            <n-input id="username" v-model:value="username" autocomplete="username" placeholder="请输入管理员用户名" :disabled="loading">
              <template #prefix><n-icon :size="17"><UserOutlined /></n-icon></template>
            </n-input>
          </div>
          <div class="field-group">
            <label for="password">密码</label>
            <n-input id="password" v-model:value="password" type="password" show-password-on="click" autocomplete="current-password" placeholder="请输入管理员密码" :disabled="loading">
              <template #prefix><n-icon :size="17"><LockOutlined /></n-icon></template>
            </n-input>
          </div>
          <div class="field-group">
            <label for="captcha-code">图片验证码</label>
            <div class="captcha-field">
              <n-input id="captcha-code" v-model:value="captchaCode" :maxlength="4" autocomplete="off" placeholder="请输入验证码" :disabled="loading">
                <template #prefix><n-icon :size="17"><KeyOutlined /></n-icon></template>
              </n-input>
              <button class="captcha-image" type="button" aria-label="刷新图片验证码" :disabled="captchaLoading" @click="refreshCaptcha">
                <img v-if="captchaImage" :src="captchaImage" alt="图片验证码" width="132" height="44">
                <n-icon v-else class="captcha-placeholder" :class="{ spin: captchaLoading }" :size="18"><ReloadOutlined /></n-icon>
              </button>
            </div>
          </div>
          <n-button attr-type="submit" type="primary" :disabled="loading || captchaLoading">
            进入控制台 <n-icon :class="{ spin: loading }" :size="16"><ReloadOutlined v-if="loading" /><ArrowRightOutlined v-else /></n-icon>
          </n-button>
          <p class="security-note"><n-icon :size="15"><LockOutlined /></n-icon><span>本地演示账号仅用于开发验证；部署环境必须替换默认密码并接入正式管理员权限体系。</span></p>
        </form>
        <footer class="auth-footer"><span>Agent Template Pro</span><span>简体中文</span></footer>
      </section>
    </main>

    <div v-else class="console-shell">
      <aside class="sidebar">
        <div class="sidebar-brand"><span><AppIcon :size="36" /></span><b>Agent<br>Template</b></div>
        <nav>
          <button :class="{ active: active === 'overview' }" type="button" @click="navigate('overview')">
            <n-icon :size="17"><BarChartOutlined /></n-icon><span>运行总览</span>
          </button>
          <template v-for="group in menuGroups" :key="group.key">
            <button class="menu-group" :class="{ open: openGroups.has(group.key) }" type="button" @click="toggleGroup(group.key)">
              <n-icon :size="17"><component :is="group.icon" /></n-icon><span>{{ group.label }}</span>
              <n-icon class="menu-caret" :size="12"><CaretDownFilled v-if="openGroups.has(group.key)" /><CaretRightFilled v-else /></n-icon>
            </button>
            <div class="menu-children" :class="{ collapsed: !openGroups.has(group.key) }">
              <button v-for="item in group.items" :key="item.key" :class="{ active: active === item.key }" type="button" @click="navigate(item.key)">
                <n-icon :size="17"><component :is="item.icon" /></n-icon><span>{{ item.label }}</span>
              </button>
            </div>
          </template>
        </nav>
      </aside>

      <header class="top-header">
        <div class="top-header-context">
          <template v-if="breadcrumb.group"><span>{{ breadcrumb.group }}</span><i>/</i></template>
          <strong>{{ breadcrumb.page }}</strong>
        </div>
        <div class="top-header-actions">
          <div class="admin-profile">
            <span class="admin-avatar"><n-icon :size="18"><UserOutlined /></n-icon></span>
            <span class="admin-copy"><strong>{{ username }}</strong><small>系统管理员</small></span>
          </div>
          <n-tooltip trigger="hover" placement="bottom">
            <template #trigger>
              <n-button text circle aria-label="退出登录" @click="logout"><n-icon :size="17"><LogoutOutlined /></n-icon></n-button>
            </template>
            退出登录
          </n-tooltip>
        </div>
      </header>

      <nav class="mobile-nav" aria-label="控制台导航">
        <button :class="{ active: active === 'overview' }" type="button" @click="navigate('overview')"><n-icon :size="16"><BarChartOutlined /></n-icon><span>总览</span></button>
        <button :class="{ active: active === 'conversations' }" type="button" @click="navigate('conversations')"><n-icon :size="16"><MessageOutlined /></n-icon><span>会话</span></button>
        <button :class="{ active: active === 'agents' }" type="button" @click="navigate('agents')"><n-icon :size="16"><ApiOutlined /></n-icon><span>Agent</span></button>
        <button :class="{ active: active === 'tasks' }" type="button" @click="navigate('tasks')"><n-icon :size="16"><TagsOutlined /></n-icon><span>任务</span></button>
        <button :class="{ active: active === 'config' }" type="button" @click="navigate('config')"><n-icon :size="16"><SettingOutlined /></n-icon><span>配置</span></button>
        <button :class="{ active: active === 'models' }" type="button" @click="navigate('models')"><n-icon :size="16"><HddOutlined /></n-icon><span>模型</span></button>
        <button :class="{ active: active === 'secrets' }" type="button" @click="navigate('secrets')"><n-icon :size="16"><KeyOutlined /></n-icon><span>Secret</span></button>
        <button :class="{ active: active === 'prompts' }" type="button" @click="navigate('prompts')"><n-icon :size="16"><ApiOutlined /></n-icon><span>Prompt</span></button>
        <button :class="{ active: active === 'mcpServers' }" type="button" @click="navigate('mcpServers')"><n-icon :size="16"><ApiOutlined /></n-icon><span>MCP</span></button>
        <button :class="{ active: active === 'mcpTools' }" type="button" @click="navigate('mcpTools')"><n-icon :size="16"><SettingOutlined /></n-icon><span>工具</span></button>
        <button :class="{ active: active === 'knowledge' }" type="button" @click="navigate('knowledge')"><n-icon :size="16"><FolderOpenOutlined /></n-icon><span>知识库</span></button>
        <button :class="{ active: active === 'applications' }" type="button" @click="navigate('applications')"><n-icon :size="16"><FileTextOutlined /></n-icon><span>应用</span></button>
        <button :class="{ active: active === 'traces' }" type="button" @click="navigate('traces')"><n-icon :size="16"><MonitorOutlined /></n-icon><span>Trace</span></button>
        <button :class="{ active: active === 'datasets' }" type="button" @click="navigate('datasets')"><n-icon :size="16"><FileTextOutlined /></n-icon><span>数据集</span></button>
        <button :class="{ active: active === 'evaluators' }" type="button" @click="navigate('evaluators')"><n-icon :size="16"><TagsOutlined /></n-icon><span>评估器</span></button>
        <button :class="{ active: active === 'experiments' }" type="button" @click="navigate('experiments')"><n-icon :size="16"><DollarOutlined /></n-icon><span>实验</span></button>
        <button :class="{ active: active === 'workflows' }" type="button" @click="navigate('workflows')"><n-icon :size="16"><DeploymentUnitOutlined /></n-icon><span>工作流</span></button>
        <button :class="{ active: active === 'workflowRuns' }" type="button" @click="navigate('workflowRuns')"><n-icon :size="16"><PlayCircleOutlined /></n-icon><span>运行</span></button>
      </nav>

      <main class="workspace">
        <n-spin :show="loading && !overview">
          <p v-if="error" class="page-error">{{ error }}</p>

          <Transition name="section-switch" mode="out-in">
            <div :key="active" class="workspace-view">
              <section v-if="active === 'overview'" class="overview">
                <div class="metric-grid">
                  <article><span>运行状态</span><strong :class="{ healthy: overview?.runtimeStatus === 'READY', degraded: overview?.runtimeStatus === 'DEGRADED' }">{{ overview?.runtimeStatus || '-' }}</strong><small>存储模式 · {{ overview?.storageMode || '-' }}</small></article>
                  <article><span>会话总数</span><strong>{{ overview?.conversationTotal ?? '-' }}</strong><small>当前运行期会话</small></article>
                  <article><span>任务总数</span><strong>{{ overview?.taskTotal ?? '-' }}</strong><small>含等待、执行与终态</small></article>
                  <article><span>等待中</span><strong>{{ overview?.activeTasks ?? '-' }}</strong><small>等待确认或外部结果</small></article>
                  <article><span>结果未知</span><strong>{{ overview?.unknownTasks ?? '-' }}</strong><small>需查单或人工介入</small></article>
                  <article><span>领域 Agent</span><strong>{{ overview?.agentTotal ?? '-' }}</strong><small>运行时注册</small></article>
                </div>
                <section class="operation-panel">
                  <div class="panel-header"><div><h2>运行边界</h2><p>模型提出意图，确定性动作负责校验、确认和执行。</p></div><n-tag type="success">{{ overview?.storageMode || 'loading' }}</n-tag></div>
                  <div class="boundary-grid"><div><span>身份</span><b>签名访客 Cookie</b></div><div><span>会话</span><b>服务端归属校验</b></div><div><span>高风险动作</span><b>强制二次确认</b></div></div>
                </section>
              </section>

              <section v-if="active === 'conversations'" class="table-section">
                <div class="table-toolbar">
                  <div><h2>会话与事件</h2><p>访客标识仅显示不可逆摘要。</p></div>
                  <div class="filter-row">
                    <n-input v-model:value="conversationQuery" clearable placeholder="会话 ID、标题或 Agent" @keyup.enter="searchConversations">
                      <template #prefix><n-icon><SearchOutlined /></n-icon></template>
                    </n-input>
                    <n-button type="primary" :disabled="loading" @click="searchConversations">查询</n-button>
                  </div>
                </div>
                <n-data-table :columns="conversationColumns" :data="conversationPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无会话" /></template>
                </n-data-table>
                <div class="pagination-row">
                  <span>共 {{ conversationPage.total }} 条</span>
                  <n-pagination :page="conversationPage.page" :page-size="conversationPage.size" :item-count="conversationPage.total" @update:page="changeConversationPage" />
                </div>
              </section>

              <section v-if="active === 'agents'" class="agent-section">
                <div class="table-toolbar"><div><h2>领域 Agent 注册表</h2><p>只读展示运行时真实注册信息，不提供在线注入动作能力。</p></div><n-tag type="success">REGISTRY READY</n-tag></div>
                <div class="agent-grid">
                  <article v-for="agent in agents" :key="agent.code">
                    <header><div><small>{{ agent.code }}</small><h3>{{ agent.displayName }}</h3></div><n-tag :type="agent.enabled ? 'success' : 'info'">{{ agent.enabled ? '启用' : '停用' }}</n-tag></header>
                    <dl><div><dt>C 端可见</dt><dd>{{ agent.visibleToVisitor ? '是' : '否' }}</dd></div><div><dt>注册动作</dt><dd>{{ agent.actionCount }}</dd></div><div><dt>路由器</dt><dd>{{ agent.routerStatus }}</dd></div></dl>
                    <footer><n-tag v-for="(count, mode) in agent.actionModes" :key="mode" size="small">{{ mode }} {{ count }}</n-tag></footer>
                    <p class="agent-metrics">路由 {{ agent.routeTotal }} · 澄清 {{ agent.ambiguousTotal }} · 失败 {{ agent.failureTotal }}</p>
                  </article>
                </div>
              </section>

              <section v-if="active === 'tasks'" class="table-section">
                <div class="table-toolbar">
                  <div><h2>任务执行记录</h2><p>确认、调用和异步状态均以任务为追踪入口。</p></div>
                  <div class="filter-row task-filters">
                    <n-input v-model:value="taskQuery" clearable placeholder="任务、会话或外部引用" @keyup.enter="searchTasks"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input>
                    <n-input v-model:value="taskActionCode" clearable placeholder="动作码" @keyup.enter="searchTasks" />
                    <n-select v-model:value="taskStatus" clearable placeholder="全部状态" :options="TASK_STATUS_OPTIONS" />
                    <n-button type="primary" :disabled="loading" @click="searchTasks">查询</n-button>
                  </div>
                </div>
                <n-data-table :columns="taskColumns" :data="taskPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无任务记录" /></template>
                </n-data-table>
                <div class="pagination-row">
                  <span>共 {{ taskPage.total }} 条</span>
                  <n-pagination :page="taskPage.page" :page-size="taskPage.size" :item-count="taskPage.total" @update:page="changeTaskPage" />
                </div>
              </section>

              <section v-if="active === 'config'" class="config-section">
                <div class="panel-header"><div><h2>非敏感运行配置</h2><p>密钥和值不会通过控制台 API 返回。</p></div><n-icon class="monitor" :size="24"><MonitorOutlined /></n-icon></div>
                <dl v-if="config"><template v-for="(value, key) in config" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl>
              </section>

              <section v-if="active === 'models'" class="table-section">
                <div class="table-toolbar"><div><h2>模型实例</h2><p>仅展示非敏感配置状态；连接测试不会回显凭据。</p></div><div class="filter-row"><n-input v-model:value="controlKeyword" clearable placeholder="编码或模型名称" @keyup.enter="load"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input><n-button :disabled="loading" @click="load">查询</n-button><n-button v-if="can('model:write')" :loading="controlSubmitting" type="primary" @click="createModel">新增模型</n-button></div></div>
                <n-alert v-if="controlResult" :title="controlResult" type="warning" closable @close="controlResult = ''" />
                <n-data-table :columns="modelColumns" :data="modelPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无模型实例" /></template>
                </n-data-table>
              </section>

              <section v-if="active === 'secrets'" class="table-section">
                <div class="table-toolbar"><div><h2>Secret 引用</h2><p>仅管理引用与配置状态，永不显示真实值或引用定位。</p></div><n-button v-if="can('secret:write')" :loading="controlSubmitting" type="primary" @click="createSecretRef">新增引用</n-button></div>
                <n-data-table :columns="secretColumns" :data="secretRefs" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无 Secret 引用" /></template>
                </n-data-table>
              </section>

              <section v-if="active === 'prompts'" class="table-section">
                <div class="table-toolbar"><div><h2>Prompt 草稿与版本</h2><p>发布固定版本快照；既有运行记录不会被新草稿修改。</p></div><div class="filter-row"><n-input v-model:value="controlKeyword" clearable placeholder="编码或名称" @keyup.enter="load"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input><n-button :disabled="loading" @click="load">查询</n-button><n-button v-if="can('prompt:write')" :loading="controlSubmitting" type="primary" @click="createPrompt">新增 Prompt</n-button></div></div>
                <n-data-table :columns="promptColumns" :data="promptPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无 Prompt" /></template>
                </n-data-table>
              </section>

              <section v-if="active === 'mcpServers'" class="table-section">
                <div class="table-toolbar"><div><h2>MCP Server</h2><p>仅允许部署白名单内的 HTTPS endpoint；Secret 仅以引用状态显示。</p></div><div class="filter-row"><n-input v-model:value="mcpKeyword" clearable placeholder="编码或名称" @keyup.enter="load"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input><n-button :disabled="loading" @click="load">查询</n-button><n-button v-if="can('mcp:write')" :loading="controlSubmitting" type="primary" @click="createMcpServer">新增 Server</n-button></div></div>
                <n-alert v-if="controlResult" :title="controlResult" type="warning" closable @close="controlResult = ''" />
                <n-data-table :columns="mcpServerColumns" :data="mcpServerPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无 MCP Server" /></template>
                </n-data-table>
                <div class="pagination-row"><span>共 {{ mcpServerPage.total }} 条</span><n-pagination :page="mcpServerPage.page" :page-size="mcpServerPage.size" :item-count="mcpServerPage.total" @update:page="changeMcpServerPage" /></div>
              </section>

              <section v-if="active === 'mcpTools'" class="table-section">
                <div class="table-toolbar"><div><h2>统一工具目录</h2><p>Schema 变更会创建新版本；写类型 Tool 只能通过 Runtime 任务和确认门禁执行。</p></div><div class="filter-row"><n-input v-model:value="mcpKeyword" clearable placeholder="Tool 名称或风险等级" @keyup.enter="load"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input><n-button :disabled="loading" @click="load">查询</n-button></div></div>
                <n-alert v-if="controlResult" :title="controlResult" type="warning" closable @close="controlResult = ''" />
                <n-data-table :columns="mcpToolColumns" :data="mcpToolPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无已发现的 MCP Tool" /></template>
                </n-data-table>
                <div class="pagination-row"><span>共 {{ mcpToolPage.total }} 条</span><n-pagination :page="mcpToolPage.page" :page-size="mcpToolPage.size" :item-count="mcpToolPage.total" @update:page="changeMcpToolPage" /></div>
              </section>

              <section v-if="active === 'knowledge'" class="knowledge-section">
                <div class="knowledge-layout">
                  <aside class="knowledge-sidebar">
                    <div class="knowledge-sidebar-header">
                      <div class="panel-header"><div><h3>知识库目录</h3><p>选择左侧知识库，右侧查看其文档、索引队列与检索结果。</p></div></div>
                      <div class="knowledge-sidebar-actions">
                        <div class="knowledge-sidebar-search">
                          <n-input v-model:value="knowledgeKeyword" clearable placeholder="搜索编码或名称" @keyup.enter="searchKnowledgeBases"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input>
                          <n-button :disabled="loading" @click="searchKnowledgeBases">查询</n-button>
                        </div>
                        <n-button v-if="can('knowledge:write')" type="primary" :loading="controlSubmitting" @click="openKnowledgeBaseDialog()">新建知识库</n-button>
                      </div>
                    </div>
                    <n-alert v-if="controlResult" :title="controlResult" type="warning" closable @close="controlResult = ''" />
                    <n-spin :show="loading">
                      <div class="knowledge-directory">
                        <div
                          v-for="item in knowledgeBasePage.items"
                          :key="item.id"
                          class="knowledge-directory-item"
                          :class="{ active: selectedKnowledgeBase?.id === item.id }"
                          role="button"
                          tabindex="0"
                          @click="selectKnowledgeBase(item)"
                          @keydown.enter.prevent="selectKnowledgeBase(item)"
                        >
                          <span class="knowledge-directory-item-head">
                            <span class="knowledge-directory-name">{{ item.displayName }}</span>
                            <n-tag size="small" :type="statusType(item.status)">{{ item.status }}</n-tag>
                          </span>
                          <span class="knowledge-directory-code">{{ item.code }}</span>
                          <span class="knowledge-directory-meta">
                            <span>{{ item.documentCount ?? 0 }} 文档</span>
                            <span>{{ item.chunkCount ?? 0 }} 切片</span>
                            <span class="knowledge-directory-actions">
                              <n-button v-if="can('knowledge:write')" text type="primary" size="small" @click.stop="openKnowledgeBaseDialog(item)">配置</n-button>
                            </span>
                          </span>
                        </div>
                        <n-empty v-if="!loading && knowledgeBasePage.items.length === 0" description="暂无知识库" />
                      </div>
                    </n-spin>
                    <div class="pagination-row knowledge-sidebar-pagination"><span>共 {{ knowledgeBasePage.total }} 条</span><n-pagination size="small" :page="knowledgeBasePage.page" :page-size="knowledgeBasePage.size" :item-count="knowledgeBasePage.total" @update:page="changeKnowledgeBasePage" /></div>
                  </aside>

                  <section class="knowledge-content">
                    <n-spin :show="knowledgeLoading">
                      <template v-if="selectedKnowledgeBase">
                        <header class="knowledge-detail-header"><div><p class="eyebrow">KNOWLEDGE BASE / {{ selectedKnowledgeBase.code }}</p><h2>{{ selectedKnowledgeBase.displayName }}</h2></div><n-tag :type="statusType(selectedKnowledgeBase.status)">{{ selectedKnowledgeBase.status }}</n-tag></header>
                        <div class="knowledge-detail-grid">
                          <section class="knowledge-panel">
                            <div class="panel-header"><div><h3>检索测试</h3><p>结果仅显示排序分数和可追溯 citation，不显示切片正文。</p></div></div>
                            <div class="retrieval-form">
                              <n-input v-model:value="knowledgeRetrievalQuery" clearable placeholder="输入检索问题" @keyup.enter="runKnowledgeRetrieval" />
                              <n-input-number v-model:value="knowledgeTopK" :min="1" :max="20" button-placement="right" aria-label="返回数量" />
                              <n-input-number v-model:value="knowledgeThreshold" :min="0" :max="1" :step="0.05" :precision="2" button-placement="right" aria-label="相似度阈值" />
                              <n-button v-if="can('knowledge:read')" type="primary" :loading="knowledgeRetrievalLoading" @click="runKnowledgeRetrieval">检索</n-button>
                            </div>
                            <n-data-table :columns="knowledgeRetrievalColumns" :data="knowledgeMatches" :loading="knowledgeRetrievalLoading" :bordered="false">
                              <template #empty><n-empty description="尚未运行检索测试" /></template>
                            </n-data-table>
                          </section>

                          <section class="knowledge-panel knowledge-documents">
                            <div class="panel-header"><div><h3>文档与队列</h3><p>支持 TXT、Markdown、PDF、DOCX，单文件最大 10 MB。</p></div><n-button v-if="can('knowledge:write')" type="primary" :loading="controlSubmitting" @click="selectKnowledgeDocument">上传文档</n-button></div>
                            <input ref="knowledgeDocumentInput" class="visually-hidden" type="file" accept="text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,.txt,.md,.pdf,.docx" @change="uploadKnowledgeDocument">
                            <n-data-table :columns="knowledgeDocumentColumns" :data="knowledgeDocumentPage.items" :bordered="false">
                              <template #empty><n-empty description="暂无文档" /></template>
                            </n-data-table>
                            <div class="pagination-row"><span>共 {{ knowledgeDocumentPage.total }} 条</span><n-pagination :page="knowledgeDocumentPage.page" :page-size="knowledgeDocumentPage.size" :item-count="knowledgeDocumentPage.total" @update:page="changeKnowledgeDocumentPage" /></div>
                            <n-data-table :columns="knowledgeJobColumns" :data="knowledgeJobPage.items" :bordered="false" class="knowledge-job-table">
                              <template #empty><n-empty description="暂无索引任务" /></template>
                            </n-data-table>
                            <div class="pagination-row"><span>共 {{ knowledgeJobPage.total }} 条</span><n-pagination :page="knowledgeJobPage.page" :page-size="knowledgeJobPage.size" :item-count="knowledgeJobPage.total" @update:page="changeKnowledgeJobPage" /></div>
                          </section>
                        </div>
                      </template>
                      <n-empty v-else-if="!loading" class="knowledge-empty" description="从左侧目录选择一个知识库，查看其文档、索引队列和检索结果" :size="72" />
                    </n-spin>
                  </section>
                </div>
              </section>

              <section v-if="active === 'applications'" class="table-section">
                <div class="table-toolbar">
                  <div><h2>Agent 应用</h2><p>版本发布后不可变；API Key 仅以状态与前缀展示，明文只在新创建/轮换时返回一次。</p></div>
                  <div class="filter-row">
                    <n-input v-model:value="applicationKeyword" clearable placeholder="编码或名称" @keyup.enter="searchApplications"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input>
                    <n-button :disabled="loading" @click="searchApplications">查询</n-button>
                    <n-button v-if="can('agentapp:write')" type="primary" :loading="controlSubmitting" @click="createApplication">新增应用</n-button>
                  </div>
                </div>
                <n-alert v-if="controlResult" :title="controlResult" type="warning" closable @close="controlResult = ''" />
                <n-data-table :columns="applicationColumns" :data="applicationPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无 Agent 应用" /></template>
                </n-data-table>
                <div class="pagination-row"><span>共 {{ applicationPage.total }} 条</span><n-pagination :page="applicationPage.page" :page-size="applicationPage.size" :item-count="applicationPage.total" @update:page="changeApplicationPage" /></div>
              </section>

              <section v-if="active === 'traces'" class="trace-section">
                <div class="metric-grid" v-if="observabilityOverview">
                  <article><span>模型调用</span><strong>{{ observabilityOverview.model.calls }}</strong><small>平均 {{ observabilityOverview.model.avgLatencyMs }}ms · P95 {{ observabilityOverview.model.p95LatencyMs }}ms</small></article>
                  <article><span>模型 Token</span><strong>{{ observabilityOverview.model.totalTokens }}</strong><small>错误 {{ observabilityOverview.model.errorCount }} · 超时 {{ observabilityOverview.model.timeoutCount }}</small></article>
                  <article><span>工具调用</span><strong>{{ observabilityOverview.tool.calls }}</strong><small>错误 {{ observabilityOverview.tool.errorCount }} · 未知 {{ observabilityOverview.tool.unknownCount }}</small></article>
                  <article><span>确认门禁</span><strong>{{ (observabilityOverview.task.confirmationRate * 100).toFixed(1) }}%</strong><small>通过 {{ observabilityOverview.task.confirmationConfirmed }} · 拒绝 {{ observabilityOverview.task.confirmationRejected }}</small></article>
                  <article><span>任务异常</span><strong>{{ observabilityOverview.task.unknownTasks + observabilityOverview.task.timeoutTasks }}</strong><small>结果未知 {{ observabilityOverview.task.unknownTasks }} · 待恢复 {{ observabilityOverview.task.timeoutTasks }}</small></article>
                  <article><span>Span 总数</span><strong>{{ observabilityOverview.totalSpans }}</strong><small>{{ observabilityOverview.totalTraces }} 条请求 Trace</small></article>
                </div>
                <div class="table-section">
                  <div class="table-toolbar">
                    <div><h2>Trace 检索</h2><p>Span 仅保存白名单字段与脱敏元数据。</p></div>
                    <div class="filter-row">
                      <n-select v-model:value="traceType" placeholder="类型" clearable :options="TRACE_TYPE_OPTIONS" />
                      <n-select v-model:value="traceStatus" placeholder="状态" clearable :options="TRACE_STATUS_OPTIONS" />
                      <n-input v-model:value="traceKeyword" clearable placeholder="traceId / 会话 / 任务 / 动作" @keyup.enter="searchTraces"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input>
                      <n-button :disabled="loading" @click="searchTraces">查询</n-button>
                    </div>
                  </div>
                  <n-data-table :columns="traceColumns" :data="tracePage.items" :loading="loading" :bordered="false">
                    <template #empty><n-empty description="暂无 Trace，先发送一条 Chat 消息会产生请求/Tool/模型 span" /></template>
                  </n-data-table>
                  <div class="pagination-row"><span>共 {{ tracePage.total }} 条</span><n-pagination :page="tracePage.page" :page-size="tracePage.size" :item-count="tracePage.total" @update:page="changeTracePage" /></div>
                </div>
              </section>

              <section v-if="active === 'datasets'" class="table-section">
                <div class="table-toolbar">
                  <div><h2>数据集</h2><p>用例按版本管理；Trace 生成的是候选样本，输入需人工补充。</p></div>
                  <div class="filter-row">
                    <n-input v-model:value="datasetKeyword" clearable placeholder="编码或名称" @keyup.enter="load"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input>
                    <n-button :disabled="loading" @click="load">查询</n-button>
                    <n-button v-if="can('eval:write')" type="primary" :loading="controlSubmitting" @click="createDataset">新建数据集</n-button>
                  </div>
                </div>
                <n-alert v-if="controlResult" :title="controlResult" type="warning" closable @close="controlResult = ''" />
                <n-data-table :columns="datasetColumns" :data="datasetPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无数据集" /></template>
                </n-data-table>
                <div class="pagination-row"><span>共 {{ datasetPage.total }} 条</span><n-pagination :page="datasetPage.page" :page-size="datasetPage.size" :item-count="datasetPage.total" @update:page="changeDatasetPage" /></div>
              </section>

              <section v-if="active === 'evaluators'" class="table-section">
                <div class="table-toolbar">
                  <div><h2>评估器</h2><p>每个评估器以版本发布配置；实验绑定的是评估器版本 ID。</p></div>
                  <div class="filter-row">
                    <n-input v-model:value="evaluatorKeyword" clearable placeholder="编码或类型" @keyup.enter="load"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input>
                    <n-button :disabled="loading" @click="load">查询</n-button>
                    <n-button v-if="can('eval:write')" type="primary" :loading="controlSubmitting" @click="createEvaluator">新建评估器</n-button>
                  </div>
                </div>
                <n-alert v-if="controlResult" :title="controlResult" type="warning" closable @close="controlResult = ''" />
                <n-data-table :columns="evaluatorColumns" :data="evaluatorPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无评估器" /></template>
                </n-data-table>
                <div class="pagination-row"><span>共 {{ evaluatorPage.total }} 条</span><n-pagination :page="evaluatorPage.page" :page-size="evaluatorPage.size" :item-count="evaluatorPage.total" @update:page="changeEvaluatorPage" /></div>
              </section>

              <section v-if="active === 'experiments'" class="table-section">
                <div class="table-toolbar">
                  <div><h2>评估实验</h2><p>实验绑定数据集、Agent 与评估器版本，结果可复现。</p></div>
                  <div class="filter-row">
                    <n-select v-model:value="experimentStatus" placeholder="状态" clearable :options="EXPERIMENT_STATUS_OPTIONS" />
                    <n-input v-model:value="experimentKeyword" clearable placeholder="编码或名称" @keyup.enter="load"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input>
                    <n-button :disabled="loading" @click="load">查询</n-button>
                    <n-button v-if="can('eval:write')" type="primary" :loading="controlSubmitting" @click="createExperiment">新建实验</n-button>
                  </div>
                </div>
                <n-alert v-if="controlResult" :title="controlResult" type="warning" closable @close="controlResult = ''" />
                <n-data-table :columns="experimentColumns" :data="experimentPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无评估实验" /></template>
                </n-data-table>
                <div class="pagination-row"><span>共 {{ experimentPage.total }} 条</span><n-pagination :page="experimentPage.page" :page-size="experimentPage.size" :item-count="experimentPage.total" @update:page="changeExperimentPage" /></div>
              </section>

              <section v-if="active === 'workflows'" class="table-section">
                <div class="table-toolbar">
                  <div><h2>Workflow 编排</h2><p>版本化 DSL 受控编排，写节点强制经 Runtime 确认门禁。</p></div>
                  <div class="filter-row">
                    <n-input v-model:value="workflowKeyword" clearable placeholder="编码或名称" @keyup.enter="searchWorkflows"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input>
                    <n-button :disabled="loading" @click="searchWorkflows">查询</n-button>
                    <n-button v-if="can('workflow:write')" type="primary" @click="workflowDialogOpen = true">新建工作流</n-button>
                  </div>
                </div>
                <n-data-table :columns="workflowColumns" :data="workflowPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无工作流" /></template>
                </n-data-table>
                <div class="pagination-row"><span>共 {{ workflowPage.total }} 条</span><n-pagination :page="workflowPage.page" :page-size="workflowPage.size" :item-count="workflowPage.total" @update:page="changeWorkflowPage" /></div>
              </section>

              <section v-if="active === 'workflowRuns'" class="table-section">
                <div class="table-toolbar">
                  <div><h2>Workflow 运行</h2><p>运行归属发起管理员；暂停、恢复、确认、停止与重试均校验归属与权限。</p></div>
                  <div class="filter-row">
                    <n-select v-model:value="workflowRunStatus" placeholder="状态" clearable :options="WORKFLOW_RUN_STATUS_OPTIONS" />
                    <n-input v-model:value="workflowRunKeyword" clearable placeholder="编码或运行 ID" @keyup.enter="searchWorkflowRuns"><template #prefix><n-icon><SearchOutlined /></n-icon></template></n-input>
                    <n-button :disabled="loading" @click="searchWorkflowRuns">查询</n-button>
                  </div>
                </div>
                <n-alert v-if="controlResult" :title="controlResult" type="warning" closable @close="controlResult = ''" />
                <n-data-table :columns="workflowRunColumns" :data="workflowRunPage.items" :loading="loading" :bordered="false">
                  <template #empty><n-empty description="暂无工作流运行" /></template>
                </n-data-table>
                <div class="pagination-row"><span>共 {{ workflowRunPage.total }} 条</span><n-pagination :page="workflowRunPage.page" :page-size="workflowRunPage.size" :item-count="workflowRunPage.total" @update:page="changeWorkflowRunPage" /></div>
              </section>
            </div>
          </Transition>
        </n-spin>
      </main>

      <n-drawer v-model:show="conversationDrawerOpen" :width="680" class="runtime-drawer">
        <n-drawer-content title="会话事件" closable>
          <n-spin :show="detailLoading && conversationEvents.items.length === 0">
            <div v-if="selectedConversation" class="drawer-content">
              <n-descriptions :column="1" bordered label-placement="left">
                <n-descriptions-item label="会话 ID">{{ selectedConversation.id }}</n-descriptions-item>
                <n-descriptions-item label="访客摘要">{{ selectedConversation.visitorRef }}</n-descriptions-item>
                <n-descriptions-item label="当前 Agent">{{ selectedConversation.activeAgentName }}</n-descriptions-item>
                <n-descriptions-item label="最近活动">{{ formatTime(selectedConversation.lastMessageAt) }}</n-descriptions-item>
              </n-descriptions>
              <n-alert v-if="detailError" type="error" :closable="false" :title="detailError" />
              <n-timeline v-if="conversationEvents.items.length" class="event-timeline">
                <n-timeline-item v-for="event in conversationEvents.items" :key="`${event.sequence}-${event.type}`" :time="formatTime(event.timestamp)" placement="top">
                  <div class="event-row"><n-tag size="small">{{ event.type }}</n-tag><code>#{{ event.sequence }}</code></div>
                  <dl>
                    <template v-if="event.taskId"><dt>任务</dt><dd>{{ event.taskId }}</dd></template>
                    <template v-if="event.status"><dt>状态</dt><dd>{{ event.status }}</dd></template>
                    <template v-if="event.actionCode"><dt>动作</dt><dd>{{ event.actionCode }}</dd></template>
                    <dt>请求</dt><dd>{{ event.requestId || '-' }}</dd>
                  </dl>
                </n-timeline-item>
              </n-timeline>
              <n-empty v-else-if="!detailLoading && !detailError" description="暂无事件" />
              <n-button v-if="conversationEvents.hasMore" :loading="detailLoading" @click="loadConversationEvents(true)">加载更多</n-button>
            </div>
          </n-spin>
        </n-drawer-content>
      </n-drawer>

      <n-drawer v-model:show="taskDrawerOpen" :width="720" class="runtime-drawer">
        <n-drawer-content title="任务详情" closable>
          <div class="drawer-content">
            <n-spin :show="detailLoading">
              <n-alert v-if="detailError" type="error" :closable="false" :title="detailError" />
              <template v-if="selectedTask">
                <n-descriptions :column="1" bordered label-placement="left">
                  <n-descriptions-item label="任务 ID">{{ selectedTask.task.id }}</n-descriptions-item>
                  <n-descriptions-item label="动作">{{ selectedTask.task.actionCode }}</n-descriptions-item>
                  <n-descriptions-item label="状态"><n-tag :type="statusType(selectedTask.task.status)">{{ selectedTask.task.status }}</n-tag></n-descriptions-item>
                  <n-descriptions-item label="会话">{{ selectedTask.task.conversationId }}</n-descriptions-item>
                  <n-descriptions-item label="访客摘要">{{ selectedTask.task.visitorRef }}</n-descriptions-item>
                  <n-descriptions-item label="外部引用">{{ selectedTask.task.externalRef || '-' }}</n-descriptions-item>
                  <n-descriptions-item label="恢复次数">{{ selectedTask.task.recoveryAttempts }}</n-descriptions-item>
                  <n-descriptions-item label="错误码">{{ selectedTask.task.lastErrorCode || '-' }}</n-descriptions-item>
                  <n-descriptions-item label="更新时间">{{ formatTime(selectedTask.task.updatedAt) }}</n-descriptions-item>
                </n-descriptions>

                <section class="drawer-section">
                  <h3>工具执行</h3>
                  <n-data-table :columns="toolExecutionColumns" :data="selectedTask.toolExecutions" :bordered="false">
                    <template #empty><n-empty description="暂无工具执行记录" /></template>
                  </n-data-table>
                </section>

                <section class="drawer-section">
                  <h3>审计记录</h3>
                  <n-data-table :columns="auditColumns" :data="selectedTask.audits" :bordered="false">
                    <template #empty><n-empty description="暂无审计记录" /></template>
                  </n-data-table>
                </section>
              </template>
              <n-empty v-else-if="!detailLoading && !detailError" description="任务不存在" />
            </n-spin>
          </div>
        </n-drawer-content>
      </n-drawer>

      <n-drawer v-model:show="traceDrawerOpen" :width="860" class="runtime-drawer">
        <n-drawer-content title="Trace 详情" closable>
          <n-spin :show="evalLoading">
            <div v-if="selectedTrace" class="drawer-content">
              <n-descriptions :column="2" bordered label-placement="left">
                <n-descriptions-item label="Trace ID" :span="2">{{ selectedTrace.traceId }}</n-descriptions-item>
                <n-descriptions-item label="Span 数">{{ selectedTrace.spanCount }}</n-descriptions-item>
                <n-descriptions-item label="总 Token">{{ selectedTrace.totalTokens }}</n-descriptions-item>
                <n-descriptions-item label="会话" :span="2">{{ selectedTrace.conversationIds.join('、') || '-' }}</n-descriptions-item>
                <n-descriptions-item label="任务" :span="2">{{ selectedTrace.taskIds.join('、') || '-' }}</n-descriptions-item>
                <n-descriptions-item label="Agent">{{ selectedTrace.agentCodes.join('、') || '-' }}</n-descriptions-item>
                <n-descriptions-item label="动作">{{ selectedTrace.actionCodes.join('、') || '-' }}</n-descriptions-item>
                <n-descriptions-item label="工具">{{ selectedTrace.toolCodes.join('、') || '-' }}</n-descriptions-item>
                <n-descriptions-item label="模型">{{ selectedTrace.modelNames.join('、') || '-' }}</n-descriptions-item>
              </n-descriptions>
              <n-data-table :columns="spanColumns" :data="selectedTrace.spans" :bordered="false" class="drawer-table">
                <template #empty><n-empty description="暂无 span" /></template>
              </n-data-table>
            </div>
          </n-spin>
        </n-drawer-content>
      </n-drawer>

      <n-drawer v-model:show="datasetDrawerOpen" :width="820" class="runtime-drawer">
        <n-drawer-content :title="selectedDataset ? `数据集 · ${selectedDataset.displayName}` : '数据集'" closable>
          <n-spin :show="evalLoading">
            <div v-if="selectedDataset" class="drawer-content">
              <n-alert v-if="controlResult" :title="controlResult" type="warning" closable @close="controlResult = ''" />
              <n-descriptions :column="2" bordered label-placement="left">
                <n-descriptions-item label="编码">{{ selectedDataset?.code }}</n-descriptions-item>
                <n-descriptions-item label="当前版本">{{ datasetVersions.find(version => version.id === selectedDataset?.currentVersionId)?.versionNo || '-' }}</n-descriptions-item>
                <n-descriptions-item label="版本数">{{ datasetVersions.length }}</n-descriptions-item>
                <n-descriptions-item label="用例总数">{{ selectedDataset?.caseCount }}</n-descriptions-item>
              </n-descriptions>
              <div class="drawer-toolbar">
                <div><h3>最新版本用例（{{ datasetVersions.length ? datasetVersions[datasetVersions.length - 1].versionNo : '-' }} 版）</h3><p>输入与期望会脱敏展示；Trace 生成样本不包含消息原文。</p></div>
                <div class="filter-row">
                  <n-select v-model:value="datasetCaseCategory" placeholder="分类" clearable style="width: 160px" :options="DATASET_CASE_CATEGORY_OPTIONS" @update:value="loadDatasetCases" />
                  <n-button v-if="can('eval:write')" text type="primary" :loading="controlSubmitting" @click="addDatasetCase">新增用例</n-button>
                  <n-button v-if="can('eval:write')" text type="primary" :loading="controlSubmitting" @click="importDatasetCases">批量导入</n-button>
                  <n-button v-if="can('eval:write')" text type="primary" :loading="controlSubmitting" @click="generateCaseFromTrace">从 Trace 生成</n-button>
                </div>
              </div>
              <n-data-table :columns="datasetCaseColumns" :data="datasetVersionCases.items" :bordered="false">
                <template #empty><n-empty description="暂无用例" /></template>
              </n-data-table>
              <div class="pagination-row"><span>共 {{ datasetVersionCases.total }} 条</span><n-pagination :page="datasetCasePage" :page-size="datasetVersionCases.size" :item-count="datasetVersionCases.total" @update:page="changeDatasetCasePage" /></div>
            </div>
          </n-spin>
        </n-drawer-content>
      </n-drawer>

      <n-drawer v-model:show="experimentRunsOpen" :width="860" class="runtime-drawer">
        <n-drawer-content :title="selectedExperiment ? `实验结果 · ${selectedExperiment.displayName}` : '实验结果'" closable>
          <n-spin :show="evalLoading">
            <div v-if="selectedExperiment" class="drawer-content">
              <n-descriptions :column="3" bordered label-placement="left">
                <n-descriptions-item label="状态"><n-tag :type="statusType(selectedExperiment.status)">{{ selectedExperiment.status }}</n-tag></n-descriptions-item>
                <n-descriptions-item label="进度">{{ selectedExperiment.completedCases }}/{{ selectedExperiment.totalCases }}</n-descriptions-item>
                <n-descriptions-item label="通过率">{{ selectedExperiment.passRate != null ? (Number(selectedExperiment.passRate) * 100).toFixed(1) + '%' : '-' }}</n-descriptions-item>
                <n-descriptions-item label="通过">{{ selectedExperiment.passedCases }}</n-descriptions-item>
                <n-descriptions-item label="失败">{{ selectedExperiment.failedCases }}</n-descriptions-item>
                <n-descriptions-item label="错误">{{ selectedExperiment.errorCases }}</n-descriptions-item>
                <n-descriptions-item label="成本">{{ (Number(selectedExperiment.costMicros || 0) / 1000).toFixed(1) }}m$</n-descriptions-item>
                <n-descriptions-item label="阈值">{{ selectedExperiment.thresholdPassRate != null ? (Number(selectedExperiment.thresholdPassRate) * 100).toFixed(1) + '%' : '-' }}</n-descriptions-item>
                <n-descriptions-item label="达标">{{ evalSummary?.passesThreshold === true ? '是' : '否' }}</n-descriptions-item>
              </n-descriptions>
              <n-data-table :columns="experimentRunColumns" :data="experimentRuns.items" :bordered="false">
                <template #empty><n-empty description="实验尚未运行或暂无结果" /></template>
              </n-data-table>
              <div class="pagination-row"><span>共 {{ experimentRuns.total }} 条</span><n-pagination :page="experimentRunsPage" :page-size="experimentRuns.size" :item-count="experimentRuns.total" @update:page="changeExperimentRunsPage" /></div>
            </div>
          </n-spin>
        </n-drawer-content>
      </n-drawer>

      <n-drawer v-model:show="applicationDrawerOpen" :width="860" class="runtime-drawer">
        <n-drawer-content title="Agent 应用详情" closable>
          <n-spin :show="applicationDetailLoading">
            <div v-if="selectedApplication" class="drawer-content">
              <n-alert v-if="detailError" type="error" :closable="false" :title="detailError" />
              <n-descriptions :column="2" bordered label-placement="left">
                <n-descriptions-item label="编码">{{ selectedApplication.code }}</n-descriptions-item>
                <n-descriptions-item label="名称">{{ selectedApplication.displayName }}</n-descriptions-item>
                <n-descriptions-item label="状态"><n-tag :type="statusType(selectedApplication.status)">{{ selectedApplication.status }}</n-tag></n-descriptions-item>
                <n-descriptions-item label="OpenAPI"><n-button text type="primary" @click="showOpenApiSpec">查看受控 OpenAPI</n-button></n-descriptions-item>
              </n-descriptions>

              <section class="drawer-section">
                <div class="drawer-section-head">
                  <h3>版本与发布</h3>
                  <div class="filter-row">
                    <n-button v-if="can('agentapp:write')" type="primary" size="small" :loading="controlSubmitting" @click="createApplicationVersion">创建草稿版本</n-button>
                  </div>
                </div>
                <n-data-table :columns="applicationVersionColumns" :data="applicationVersions" :bordered="false">
                  <template #empty><n-empty description="暂无版本" /></template>
                </n-data-table>
              </section>

              <section class="drawer-section">
                <div class="drawer-section-head">
                  <h3>API Key</h3>
                  <n-button v-if="can('apikey:write')" type="primary" size="small" :loading="controlSubmitting" @click="createApiKey">创建 API Key</n-button>
                </div>
                <n-data-table :columns="apiKeyColumns" :data="applicationApiKeys" :bordered="false">
                  <template #empty><n-empty description="暂无 API Key" /></template>
                </n-data-table>
              </section>

              <section class="drawer-section">
                <h3>发布记录</h3>
                <n-data-table :columns="publishRecordColumns" :data="applicationRecords" :bordered="false">
                  <template #empty><n-empty description="暂无发布记录" /></template>
                </n-data-table>
              </section>
            </div>
          </n-spin>
        </n-drawer-content>
      </n-drawer>

      <n-modal v-model:show="openApiDialogOpen" preset="dialog" title="受控 OpenAPI" :style="{ width: 'min(720px, calc(100vw - 32px))' }">
        <pre class="openapi-pre">{{ openApiSpecText }}</pre>
        <template #action><n-button @click="openApiDialogOpen = false">关闭</n-button></template>
      </n-modal>

      <n-drawer v-model:show="knowledgeChunksOpen" :width="760" class="runtime-drawer">
        <n-drawer-content :title="selectedKnowledgeDocument ? `切片预览 · ${selectedKnowledgeDocument.name}` : '切片预览'" closable>
          <n-spin :show="knowledgeLoading">
            <div class="drawer-content">
              <p class="drawer-note">为避免在管理端扩散原文，这里只显示切片元数据与启停状态。</p>
              <n-data-table :columns="knowledgeChunkColumns" :data="knowledgeChunkPage.items" :bordered="false">
                <template #empty><n-empty description="暂无可预览切片" /></template>
              </n-data-table>
              <div class="pagination-row"><span>共 {{ knowledgeChunkPage.total }} 条</span><n-pagination :page="knowledgeChunkPage.page" :page-size="knowledgeChunkPage.size" :item-count="knowledgeChunkPage.total" @update:page="changeKnowledgeChunkPage" /></div>
            </div>
          </n-spin>
        </n-drawer-content>
      </n-drawer>

      <n-modal v-model:show="knowledgeDialogOpen" preset="dialog" :title="knowledgeBaseForm.id ? '配置知识库' : '新建知识库'" :mask-closable="false" :style="{ width: 'min(520px, calc(100vw - 32px))' }">
        <n-form label-placement="top" @submit.prevent="saveKnowledgeBase">
          <n-form-item label="知识库编码" required><n-input v-model:value="knowledgeBaseForm.code" :disabled="Boolean(knowledgeBaseForm.id)" :maxlength="80" autocomplete="off" /></n-form-item>
          <n-form-item label="名称" required><n-input v-model:value="knowledgeBaseForm.displayName" :maxlength="120" autocomplete="off" /></n-form-item>
          <n-form-item label="说明"><n-input v-model:value="knowledgeBaseForm.description" type="textarea" :rows="3" :maxlength="500" show-count /></n-form-item>
          <n-form-item label="状态"><n-select v-model:value="knowledgeBaseForm.status" :options="KNOWLEDGE_BASE_STATUS_OPTIONS" /></n-form-item>
        </n-form>
        <template #action>
          <n-button @click="knowledgeDialogOpen = false">取消</n-button>
          <n-button type="primary" :loading="controlSubmitting" @click="saveKnowledgeBase">保存</n-button>
        </template>
      </n-modal>

      <n-modal v-model:show="workflowDialogOpen" preset="dialog" title="新建工作流" :mask-closable="false" :style="{ width: 'min(520px, calc(100vw - 32px))' }">
        <n-form label-placement="top" @submit.prevent="createWorkflow">
          <n-form-item label="流程编码" required><n-input v-model:value="workflowForm.code" :maxlength="120" placeholder="如 hotel-booking" autocomplete="off" /></n-form-item>
          <n-form-item label="名称" required><n-input v-model:value="workflowForm.displayName" :maxlength="160" placeholder="如 酒店预订流程" autocomplete="off" /></n-form-item>
          <n-form-item label="说明"><n-input v-model:value="workflowForm.description" type="textarea" :rows="3" :maxlength="500" show-count /></n-form-item>
        </n-form>
        <template #action>
          <n-button @click="workflowDialogOpen = false">取消</n-button>
          <n-button type="primary" :loading="controlSubmitting" @click="createWorkflow">创建</n-button>
        </template>
      </n-modal>

      <n-drawer v-model:show="workflowEditorOpen" :width="900" class="runtime-drawer">
        <n-drawer-content :title="selectedWorkflow ? `Workflow 编辑器 · ${selectedWorkflow.displayName}` : 'Workflow 编辑器'" closable>
          <n-spin :show="workflowVersionLoading">
            <div v-if="selectedWorkflow" class="drawer-content">
              <n-descriptions :column="2" bordered label-placement="left">
                <n-descriptions-item label="编码">{{ selectedWorkflow.code }}</n-descriptions-item>
                <n-descriptions-item label="状态"><n-tag :type="statusType(selectedWorkflow.status)">{{ selectedWorkflow.status }}</n-tag></n-descriptions-item>
              </n-descriptions>
              <div class="workflow-editor-grid">
                <div>
                  <div class="panel-header"><div><h3>DSL（schema 1.0）</h3><p>校验在保存与发布前统一执行；发布固定资源版本。</p></div></div>
                  <n-input v-model:value="workflowDslJson" type="textarea" :rows="18" spellcheck="false" class="code-input" />
                  <div class="panel-header"><div><h3>资源绑定 JSON</h3><p>modelVersionId / promptVersionId / knowledgeBaseVersionId / toolVersionIds</p></div></div>
                  <n-input v-model:value="workflowBindingsJson" type="textarea" :rows="6" spellcheck="false" class="code-input" />
                  <div class="workflow-editor-actions">
                    <n-button v-if="can('workflow:write')" type="primary" :loading="workflowVersionLoading" @click="saveWorkflowVersion">保存为新版本</n-button>
                    <n-button v-if="can('workflow:write') && selectedWorkflow.status !== 'ARCHIVED'" type="warning" :loading="workflowVersionLoading" @click="rollbackWorkflow">回滚到上一发布</n-button>
                    <n-button v-if="can('workflow:write') && selectedWorkflow.status !== 'ARCHIVED'" type="error" :loading="workflowVersionLoading" @click="archiveWorkflow">归档</n-button>
                  </div>
                </div>
                <div>
                  <div class="panel-header"><div><h3>版本快照</h3><p>版本不可变；运行固定到发布版本。</p></div></div>
                  <n-data-table :columns="workflowVersionColumns" :data="workflowVersions" :bordered="false" :max-height="520">
                    <template #empty><n-empty description="暂无版本" /></template>
                  </n-data-table>
                  <n-alert v-if="workflowValidation" :title="workflowValidation.valid ? '校验通过：DSL 与绑定均有效。' : '校验未通过，请先修复。'" :type="workflowValidation.valid ? 'success' : 'error'" closable @close="workflowValidation = null" />
                  <ul v-if="workflowValidation && !workflowValidation.valid" class="issue-list">
                    <li v-for="(issue, index) in workflowValidation.issues" :key="index">{{ issue.resourceType }}<template v-if="issue.resourceId">[{{ issue.resourceId }}]</template>：{{ issue.message }}</li>
                  </ul>
                </div>
              </div>
            </div>
          </n-spin>
        </n-drawer-content>
      </n-drawer>

      <n-drawer v-model:show="workflowRunDrawerOpen" :width="880" class="runtime-drawer">
        <n-drawer-content :title="selectedWorkflowRun ? `运行详情 · ${selectedWorkflowRun.id}` : '运行详情'" closable>
          <n-spin :show="detailLoading">
            <div v-if="selectedWorkflowRun" class="drawer-content">
              <div class="drawer-actions">
                <n-tag :type="statusType(selectedWorkflowRun.status)">{{ selectedWorkflowRun.status }}</n-tag>
                <span v-if="workflowEventStreaming" class="streaming-tag">SSE 连接中…</span>
                <n-button v-if="selectedWorkflowRun.status === 'PAUSED' && can('workflow:run')" type="primary" :loading="controlSubmitting" @click="resumeWorkflowRun">恢复</n-button>
                <n-button v-if="selectedWorkflowRun.status === 'PAUSED' && selectedWorkflowRun.currentNodeId && can('workflow:run')" type="primary" secondary :loading="controlSubmitting" @click="openWorkflowInput">提交输入</n-button>
                <n-button v-if="selectedWorkflowRun.status === 'FAILED' && can('workflow:run')" type="primary" :loading="controlSubmitting" @click="retryWorkflowRun">重试</n-button>
                <n-button v-if="!terminalWorkflowStatus(selectedWorkflowRun.status) && can('workflow:run')" type="error" secondary :loading="controlSubmitting" @click="stopWorkflowRun">停止</n-button>
              </div>
              <n-descriptions :column="2" bordered label-placement="left">
                <n-descriptions-item label="流程">{{ selectedWorkflowRun.code }}</n-descriptions-item>
                <n-descriptions-item label="版本">{{ selectedWorkflowRun.workflowVersionId }}</n-descriptions-item>
                <n-descriptions-item label="当前节点">{{ selectedWorkflowRun.currentNodeId || '-' }}</n-descriptions-item>
                <n-descriptions-item label="错误码">{{ selectedWorkflowRun.errorCode || '-' }}</n-descriptions-item>
                <n-descriptions-item label="请求 ID">{{ selectedWorkflowRun.requestId || '-' }}</n-descriptions-item>
                <n-descriptions-item label="开始时间">{{ formatTime(selectedWorkflowRun.startedAt) }}</n-descriptions-item>
              </n-descriptions>
              <div class="panel-header"><div><h3>节点执行</h3><p>写节点仅在确认后执行；RESULT_UNKNOWN 禁止自动重试。</p></div></div>
              <n-data-table :columns="workflowNodeRunColumns" :data="workflowNodeRuns" :bordered="false">
                <template #empty><n-empty description="暂无节点运行记录" /></template>
              </n-data-table>
              <div class="panel-header"><div><h3>事件流（SSE）</h3><p>按 sequence 排序；终端状态后自动断流。</p></div></div>
              <div class="workflow-event-stream" ref="workflowEventStreamRef">
                <div v-if="workflowEvents.length === 0" class="empty-state">暂无事件</div>
                <article v-for="event in workflowEvents" :key="event.id" class="workflow-event-item">
                  <span class="event-seq">#{{ event.sequence }}</span>
                  <n-tag size="small">{{ event.type }}</n-tag>
                  <code>{{ JSON.stringify(event.payload) }}</code>
                </article>
              </div>
            </div>
          </n-spin>
        </n-drawer-content>
      </n-drawer>

      <n-modal v-model:show="workflowInputOpen" preset="dialog" title="提交输入" :mask-closable="false" :style="{ width: 'min(520px, calc(100vw - 32px))' }">
        <p class="dialog-hint">节点 <b>{{ selectedWorkflowRun?.currentNodeId }}</b> 等待输入，提交 JSON 对象作为变量。</p>
        <n-input v-model:value="workflowInputValues" type="textarea" :rows="6" spellcheck="false" class="code-input" placeholder='{"city": "上海"}' />
        <template #action>
          <n-button @click="workflowInputOpen = false">取消</n-button>
          <n-button type="primary" :loading="controlSubmitting" @click="submitWorkflowInput">提交</n-button>
        </template>
      </n-modal>

      <n-modal v-model:show="workflowConfirmOpen" preset="dialog" title="确认门禁" :mask-closable="false" :style="{ width: 'min(520px, calc(100vw - 32px))' }">
        <p class="dialog-hint">节点 <b>{{ workflowConfirmNodeId }}</b> 为写操作，必须经确认门禁执行。确认版本 <b>v{{ workflowConfirmVersion }}</b> 由服务端快照校验。</p>
        <template #action>
          <n-button @click="workflowConfirmOpen = false">取消</n-button>
          <n-button type="error" :loading="controlSubmitting" @click="confirmWorkflowNode('REJECTED')">拒绝</n-button>
          <n-button type="primary" :loading="controlSubmitting" @click="confirmWorkflowNode('CONFIRMED')">确认执行</n-button>
        </template>
      </n-modal>
    </div>
  </n-config-provider>
</template>
