import type {
  CaptchaResponse,
  CursorPageResponse,
  LoginResponse,
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
  ModelConnectionResult,
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
  WorkflowDefinition,
  WorkflowVersionView,
  WorkflowRunView,
  WorkflowNodeRunView,
  WorkflowEventView,
  WorkflowValidationResult,
  EvalDataset,
  EvalDatasetVersion,
  EvalCase,
  EvalEvaluator,
  EvalEvaluatorVersion,
  EvalExperiment,
  EvalExperimentRun,
} from '../types/console'

interface ApiError {
  code?: string
  message?: string
  references?: McpBindingReference[]
}

export interface McpBindingReference {
  agentCode?: string
  toolVersionId?: string
}

export class ConsoleApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly references: McpBindingReference[] = [],
  ) {
    super(message)
  }
}

const baseUrl = import.meta.env.VITE_API_BASE || '/api/console/v1'

export const MAX_KNOWLEDGE_DOCUMENT_BYTES = 10 * 1024 * 1024

export interface WorkflowEventsPage {
  items: WorkflowEventView[]
  nextSequence: number
  done: boolean
}

export interface KnowledgeDocumentUpload {
  name: string
  contentType: string
  content?: string
  contentBase64?: string
}

async function request<T>(path: string, init: RequestInit = {}, fallback = '请求失败。'): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    cache: 'no-store',
  })
  if (!response.ok) {
    let body: ApiError = {}
    try {
      body = await response.json() as ApiError
    } catch {
      // The status and stable fallback remain actionable when a proxy returns a non-JSON error.
    }
    throw new ConsoleApiError(body.message || fallback, response.status, body.code, body.references || [])
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

function query(params: Record<string, string | number | undefined>): string {
  const values = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') values.set(key, String(value))
  })
  const encoded = values.toString()
  return encoded ? `?${encoded}` : ''
}

function authorized(token: string): HeadersInit {
  return { Authorization: `Bearer ${token}` }
}

export const consoleApi = {
  captcha: () => request<CaptchaResponse>('/auth/captcha', {}, '图片验证码加载失败。'),
  login: (payload: {
    username: string
    password: string
    captchaId: string
    captchaCode: string
  }) => request<LoginResponse>('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }, '登录失败，请检查登录信息。'),
  logout: (token: string) => request<void>('/auth/logout', {
    method: 'POST',
    headers: authorized(token),
  }),
  overview: (token: string) => request<Overview>('/overview', { headers: authorized(token) }),
  agents: (token: string) => request<RuntimeAgent[]>('/agents', { headers: authorized(token) }),
  runtimeConfig: (token: string) => request<RuntimeConfig>('/runtime-config', { headers: authorized(token) }),
  conversations: (token: string, page: number, size: number, search?: string) =>
    request<PageResponse<RuntimeConversation>>(`/conversations${query({ page, size, query: search })}`, {
      headers: authorized(token),
    }),
  conversationEvents: (token: string, conversationId: string, afterSequence = 0, limit = 100) =>
    request<CursorPageResponse<RuntimeEvent>>(
      `/conversations/${encodeURIComponent(conversationId)}/events${query({ afterSequence, limit })}`,
      { headers: authorized(token) },
    ),
  tasks: (
    token: string,
    page: number,
    size: number,
    filters: { status?: string; actionCode?: string; query?: string },
  ) => request<PageResponse<RuntimeTask>>(`/tasks${query({ page, size, ...filters })}`, {
    headers: authorized(token),
  }),
  task: (token: string, taskId: string) => request<TaskDetail>(
    `/tasks/${encodeURIComponent(taskId)}`,
    { headers: authorized(token) },
  ),
  me: (token: string) => request<ControlPrincipal>('/control-plane/me', { headers: authorized(token) }),
  models: (token: string, page: number, size: number, keyword?: string, modelType?: string) =>
    request<PageResponse<ControlResource>>(`/models${query({ page, size, keyword, modelType })}`, { headers: authorized(token) }),
  createModel: (token: string, payload: Record<string, unknown>) => request<ControlResource>('/models', { method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }),
  testModel: (token: string, id: string) => request<ModelConnectionResult>(`/models/${encodeURIComponent(id)}:test`, { method: 'POST', headers: authorized(token) }),
  secretRefs: (token: string) => request<ControlResource[]>('/secret-refs', { headers: authorized(token) }),
  createSecretRef: (token: string, payload: Record<string, unknown>) => request<ControlResource>('/secret-refs', { method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }),
  prompts: (token: string, page: number, size: number, keyword?: string) => request<PageResponse<ControlResource>>(`/prompts${query({ page, size, keyword })}`, { headers: authorized(token) }),
  createPrompt: (token: string, payload: Record<string, unknown>) => request<ControlResource>('/prompts', { method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }),
  createPromptVersion: (token: string, id: string) => request<ControlResource>(`/prompts/${encodeURIComponent(id)}/versions`, { method: 'POST', headers: authorized(token) }),
  publishPromptVersion: (token: string, id: string) => request<ControlResource>(`/prompt-versions/${encodeURIComponent(id)}:publish`, { method: 'POST', headers: authorized(token) }),
  mcpServers: (token: string, page: number, size: number, keyword?: string) => request<PageResponse<McpServer>>(`/mcp-servers${query({ page, size, keyword })}`, { headers: authorized(token) }),
  createMcpServer: (token: string, payload: Record<string, unknown>) => request<McpServer>('/mcp-servers', { method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }),
  testMcpServer: (token: string, id: string) => request<{ status: string; message: string }>(`/mcp-servers/${encodeURIComponent(id)}:test`, { method: 'POST', headers: authorized(token) }),
  syncMcpServer: (token: string, id: string) => request<{ toolCount: number; createdVersionCount: number }>(`/mcp-servers/${encodeURIComponent(id)}:sync`, { method: 'POST', headers: authorized(token) }),
  mcpTools: (token: string, page: number, size: number, keyword?: string, serverId?: string) => request<PageResponse<McpTool>>(`/mcp-tools${query({ page, size, keyword, serverId })}`, { headers: authorized(token) }),
  setMcpToolEnabled: (token: string, id: string, enabled: boolean) => request<McpTool>(`/mcp-tools/${encodeURIComponent(id)}:${enabled ? 'enable' : 'disable'}`, { method: 'POST', headers: authorized(token) }),
  debugMcpTool: (token: string, id: string) => request<{ status: string; traceId: string }>(`/mcp-tools/${encodeURIComponent(id)}:debug`, { method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: '{}' }),
  knowledgeBases: (token: string, page: number, size: number, keyword?: string) =>
    request<PageResponse<KnowledgeBase>>(`/knowledge-bases${query({ page, size, keyword })}`, { headers: authorized(token) }),
  createKnowledgeBase: (token: string, payload: Record<string, unknown>) => request<KnowledgeBase>('/knowledge-bases', {
    method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
  }),
  updateKnowledgeBase: (token: string, id: string, payload: Record<string, unknown>) => request<KnowledgeBase>(`/knowledge-bases/${encodeURIComponent(id)}`, {
    method: 'PUT', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
  }),
  knowledgeDocuments: (token: string, knowledgeBaseId: string, page: number, size: number, keyword?: string) =>
    request<PageResponse<KnowledgeDocument>>(`/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents${query({ page, size, keyword })}`, { headers: authorized(token) }),
  uploadKnowledgeDocument: (token: string, knowledgeBaseId: string, payload: KnowledgeDocumentUpload) =>
    request<KnowledgeDocument>(`/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  deleteKnowledgeDocument: (token: string, id: string) => request<void>(`/documents/${encodeURIComponent(id)}`, {
    method: 'DELETE', headers: authorized(token),
  }),
  reindexKnowledgeDocument: (token: string, id: string) => request<KnowledgeDocument>(`/documents/${encodeURIComponent(id)}:reindex`, {
    method: 'POST', headers: authorized(token),
  }),
  knowledgeIndexJobs: (token: string, page: number, size: number, filters: { knowledgeBaseId?: string; status?: string }) =>
    request<PageResponse<KnowledgeIndexJob>>(`/knowledge-index-jobs${query({ page, size, ...filters })}`, { headers: authorized(token) }),
  retryKnowledgeIndexJob: (token: string, id: string) => request<KnowledgeIndexJob>(`/knowledge-index-jobs/${encodeURIComponent(id)}:retry`, {
    method: 'POST', headers: authorized(token),
  }),
  knowledgeChunks: (token: string, documentId: string, page: number, size: number) =>
    request<PageResponse<KnowledgeChunk>>(`/documents/${encodeURIComponent(documentId)}/chunks:preview${query({ page, size })}`, { headers: authorized(token) }),
  updateKnowledgeChunk: (token: string, documentId: string, payload: { chunkId: string; enabled: boolean }) =>
    request<KnowledgeChunk>(`/documents/${encodeURIComponent(documentId)}/chunks`, {
      method: 'PUT', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  retrieveKnowledge: (token: string, knowledgeBaseId: string, payload: { query: string; topK: number; threshold: number }) =>
    request<KnowledgeRetrievalMatch[]>(`/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}:retrieve-test`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  applications: (token: string, page: number, size: number, keyword?: string) =>
    request<PageResponse<AgentApplication>>(`/applications${query({ page, size, keyword })}`, { headers: authorized(token) }),
  createApplication: (token: string, payload: Record<string, unknown>) => request<AgentApplication>('/applications', {
    method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
  }),
  updateApplication: (token: string, id: string, payload: Record<string, unknown>) => request<AgentApplication>(`/applications/${encodeURIComponent(id)}`, {
    method: 'PUT', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
  }),
  archiveApplication: (token: string, id: string) => request<void>(`/applications/${encodeURIComponent(id)}:archive`, {
    method: 'POST', headers: authorized(token),
  }),
  applicationVersions: (token: string, applicationId: string) =>
    request<AgentApplicationVersion[]>(`/applications/${encodeURIComponent(applicationId)}/versions`, { headers: authorized(token) }),
  applicationVersionBindings: (token: string, applicationId: string, versionId: string) =>
    request<AgentAppBinding[]>(`/applications/${encodeURIComponent(applicationId)}/versions/${encodeURIComponent(versionId)}/bindings`, { headers: authorized(token) }),
  createApplicationVersion: (token: string, applicationId: string, payload: Record<string, unknown>) =>
    request<AgentApplicationVersion>(`/applications/${encodeURIComponent(applicationId)}/versions`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  validateApplicationVersion: (token: string, versionId: string) =>
    request<{ versionId: string; valid: boolean; issues: Array<{ resourceType: string; resourceId: string; message: string }>; validatedAt: string }>(
      `/application-versions/${encodeURIComponent(versionId)}:validate`, { method: 'POST', headers: authorized(token) }),
  publishApplicationVersion: (token: string, versionId: string) =>
    request<AgentApplicationVersion>(`/application-versions/${encodeURIComponent(versionId)}:publish`, { method: 'POST', headers: authorized(token) }),
  rollbackApplication: (token: string, applicationId: string, versionId: string) =>
    request<AgentApplicationVersion>(`/applications/${encodeURIComponent(applicationId)}:rollback`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify({ versionId }),
    }),
  applicationPublishRecords: (token: string, applicationId: string) =>
    request<AgentAppPublishRecord[]>(`/applications/${encodeURIComponent(applicationId)}/publish-records`, { headers: authorized(token) }),
  applicationApiKeys: (token: string, applicationId: string) =>
    request<AgentAppApiKey[]>(`/applications/${encodeURIComponent(applicationId)}/api-keys`, { headers: authorized(token) }),
  createApplicationApiKey: (token: string, applicationId: string, payload: Record<string, unknown>) =>
    request<AgentAppApiKeyCreated>(`/applications/${encodeURIComponent(applicationId)}/api-keys`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  rotateApplicationApiKey: (token: string, applicationId: string, keyId: string) =>
    request<AgentAppApiKeyCreated>(`/applications/${encodeURIComponent(applicationId)}/api-keys/${encodeURIComponent(keyId)}:rotate`, {
      method: 'POST', headers: authorized(token),
    }),
  revokeApplicationApiKey: (token: string, applicationId: string, keyId: string) =>
    request<void>(`/applications/${encodeURIComponent(applicationId)}/api-keys/${encodeURIComponent(keyId)}:revoke`, {
      method: 'POST', headers: authorized(token),
    }),
  applicationOpenApiSpec: (token: string, applicationId: string) =>
    request<Record<string, unknown>>(`/applications/${encodeURIComponent(applicationId)}:openapi`, { headers: authorized(token) }),
  observabilityOverview: (token: string) =>
    request<ObservabilityOverview>('/observability/overview', { headers: authorized(token) }),
  traces: (token: string, page: number, size: number, options: { type?: string; status?: string; query?: string } = {}) =>
    request<PageResponse<TraceSpan>>(`/observability/traces${query({ page, size, ...options })}`, { headers: authorized(token) }),
  traceDetail: (token: string, traceId: string) =>
    request<TraceDetail>(`/observability/traces/${encodeURIComponent(traceId)}`, { headers: authorized(token) }),
  evalDatasets: (token: string, page: number, size: number, queryText?: string) =>
    request<PageResponse<EvalDataset>>(`/evaluation/datasets${query({ page, size, query: queryText })}`, { headers: authorized(token) }),
  createEvalDataset: (token: string, payload: Record<string, unknown>) =>
    request<EvalDataset>('/evaluation/datasets', {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  evalDataset: (token: string, id: string) =>
    request<EvalDataset>(`/evaluation/datasets/${encodeURIComponent(id)}`, { headers: authorized(token) }),
  evalDatasetVersions: (token: string, id: string) =>
    request<EvalDatasetVersion[]>(`/evaluation/datasets/${encodeURIComponent(id)}/versions`, { headers: authorized(token) }),
  createEvalDatasetVersion: (token: string, id: string, payload: Record<string, unknown>) =>
    request<EvalDatasetVersion>(`/evaluation/datasets/${encodeURIComponent(id)}/versions`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  evalCases: (token: string, versionId: string, page: number, size: number, options: { category?: string; query?: string } = {}) =>
    request<PageResponse<EvalCase>>(`/evaluation/datasets/versions/${encodeURIComponent(versionId)}/cases${query({ page, size, ...options })}`, { headers: authorized(token) }),
  addEvalCase: (token: string, versionId: string, payload: Record<string, unknown>) =>
    request<EvalCase>(`/evaluation/datasets/versions/${encodeURIComponent(versionId)}/cases`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  importEvalCases: (token: string, versionId: string, cases: Record<string, unknown>[]) =>
    request<{ imported: number }>(`/evaluation/datasets/versions/${encodeURIComponent(versionId)}/cases:import`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(cases),
    }),
  generateEvalCaseFromTrace: (token: string, versionId: string, payload: Record<string, unknown>) =>
    request<EvalCase>(`/evaluation/datasets/versions/${encodeURIComponent(versionId)}/cases:generate-from-trace`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  evalEvaluators: (token: string, page: number, size: number, queryText?: string) =>
    request<PageResponse<EvalEvaluator>>(`/evaluation/evaluators${query({ page, size, query: queryText })}`, { headers: authorized(token) }),
  createEvalEvaluator: (token: string, payload: Record<string, unknown>) =>
    request<EvalEvaluator>('/evaluation/evaluators', {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  createEvalEvaluatorVersion: (token: string, id: string, payload: Record<string, unknown>) =>
    request<EvalEvaluatorVersion>(`/evaluation/evaluators/${encodeURIComponent(id)}/versions`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  evalExperiments: (token: string, page: number, size: number, options: { status?: string; query?: string } = {}) =>
    request<PageResponse<EvalExperiment>>(`/evaluation/experiments${query({ page, size, ...options })}`, { headers: authorized(token) }),
  createEvalExperiment: (token: string, payload: Record<string, unknown>) =>
    request<EvalExperiment>('/evaluation/experiments', {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  evalExperiment: (token: string, id: string) =>
    request<EvalExperiment>(`/evaluation/experiments/${encodeURIComponent(id)}`, { headers: authorized(token) }),
  startEvalExperiment: (token: string, id: string) =>
    request<EvalExperiment>(`/evaluation/experiments/${encodeURIComponent(id)}:start`, { method: 'POST', headers: authorized(token) }),
  stopEvalExperiment: (token: string, id: string) =>
    request<EvalExperiment>(`/evaluation/experiments/${encodeURIComponent(id)}:stop`, { method: 'POST', headers: authorized(token) }),
  retryEvalExperiment: (token: string, id: string) =>
    request<EvalExperiment>(`/evaluation/experiments/${encodeURIComponent(id)}:retry`, { method: 'POST', headers: authorized(token) }),
  evalExperimentRuns: (token: string, id: string, page: number, size: number) =>
    request<PageResponse<EvalExperimentRun>>(`/evaluation/experiments/${encodeURIComponent(id)}/results${query({ page, size })}`, { headers: authorized(token) }),
  evalExperimentSummary: (token: string, id: string) =>
    request<Record<string, unknown>>(`/evaluation/experiments/${encodeURIComponent(id)}/summary`, { headers: authorized(token) }),
  workflows: (token: string, page: number, size: number, keyword?: string) =>
    request<PageResponse<WorkflowDefinition>>(`/workflows${query({ page, size, keyword })}`, { headers: authorized(token) }),
  workflow: (token: string, id: string) =>
    request<WorkflowDefinition>(`/workflows/${encodeURIComponent(id)}`, { headers: authorized(token) }),
  createWorkflow: (token: string, payload: Record<string, unknown>) =>
    request<WorkflowDefinition>('/workflows', {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  updateWorkflow: (token: string, id: string, payload: Record<string, unknown>) =>
    request<WorkflowDefinition>(`/workflows/${encodeURIComponent(id)}`, {
      method: 'PUT', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  archiveWorkflow: (token: string, id: string) =>
    request<WorkflowDefinition>(`/workflows/${encodeURIComponent(id)}:archive`, { method: 'POST', headers: authorized(token) }),
  rollbackWorkflow: (token: string, id: string) =>
    request<WorkflowVersionView>(`/workflows/${encodeURIComponent(id)}:rollback`, { method: 'POST', headers: authorized(token) }),
  workflowVersions: (token: string, id: string) =>
    request<WorkflowVersionView[]>(`/workflows/${encodeURIComponent(id)}/versions`, { headers: authorized(token) }),
  workflowVersion: (token: string, versionId: string) =>
    request<WorkflowVersionView>(`/workflow-versions/${encodeURIComponent(versionId)}`, { headers: authorized(token) }),
  createWorkflowVersion: (token: string, id: string, payload: Record<string, unknown>) =>
    request<WorkflowVersionView>(`/workflows/${encodeURIComponent(id)}/versions`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  validateWorkflowVersion: (token: string, versionId: string) =>
    request<WorkflowValidationResult>(`/workflow-versions/${encodeURIComponent(versionId)}:validate`, {
      method: 'POST', headers: authorized(token),
    }),
  publishWorkflowVersion: (token: string, versionId: string) =>
    request<WorkflowVersionView>(`/workflow-versions/${encodeURIComponent(versionId)}:publish`, {
      method: 'POST', headers: authorized(token),
    }),
  startWorkflowRun: (token: string, payload: Record<string, unknown>) =>
    request<WorkflowRunView>('/workflow-runs', {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  workflowRuns: (token: string, page: number, size: number, options: { keyword?: string; status?: string } = {}) =>
    request<PageResponse<WorkflowRunView>>(`/workflow-runs${query({ page, size, ...options })}`, { headers: authorized(token) }),
  workflowRun: (token: string, runId: string) =>
    request<WorkflowRunView>(`/workflow-runs/${encodeURIComponent(runId)}`, { headers: authorized(token) }),
  resumeWorkflowRun: (token: string, runId: string) =>
    request<WorkflowRunView>(`/workflow-runs/${encodeURIComponent(runId)}:resume`, { method: 'POST', headers: authorized(token) }),
  submitWorkflowInput: (token: string, runId: string, payload: Record<string, unknown>) =>
    request<WorkflowRunView>(`/workflow-runs/${encodeURIComponent(runId)}:input`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  confirmWorkflowNode: (token: string, runId: string, payload: Record<string, unknown>) =>
    request<WorkflowRunView>(`/workflow-runs/${encodeURIComponent(runId)}:confirm`, {
      method: 'POST', headers: { ...authorized(token), 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    }),
  stopWorkflowRun: (token: string, runId: string) =>
    request<WorkflowRunView>(`/workflow-runs/${encodeURIComponent(runId)}:stop`, { method: 'POST', headers: authorized(token) }),
  retryWorkflowRun: (token: string, runId: string) =>
    request<WorkflowRunView>(`/workflow-runs/${encodeURIComponent(runId)}:retry`, { method: 'POST', headers: authorized(token) }),
  workflowNodeRuns: (token: string, runId: string) =>
    request<WorkflowNodeRunView[]>(`/workflow-runs/${encodeURIComponent(runId)}/node-runs`, { headers: authorized(token) }),
}

/**
 * Consumes the Workflow run SSE stream with fetch + ReadableStream. The stream closes when the
 * run reaches a terminal state and the event buffer is drained, so the returned promise resolves.
 */
export async function streamWorkflowEvents(
  token: string,
  runId: string,
  afterSequence: number,
  receive: (event: WorkflowEventView) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(
    `${baseUrl}/workflow-runs/${encodeURIComponent(runId)}/events${query({ afterSequence, timeoutSeconds: 120 })}`,
    { headers: { ...authorized(token), Accept: 'text/event-stream' }, cache: 'no-store', signal },
  )
  if (!response.ok || !response.body) {
    throw new Error(`工作流事件流连接失败（${response.status}）。`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() || ''
    blocks.forEach((block) => {
      const raw = block.match(/^data:\s*(.+)$/m)?.[1]
      if (raw) {
        try {
          receive(JSON.parse(raw) as WorkflowEventView)
        } catch {
          // A malformed frame must not tear down the whole stream.
        }
      }
    })
  }
  if (buffer.trim()) {
    const raw = buffer.match(/^data:\s*(.+)$/m)?.[1]
    if (raw) {
      try {
        receive(JSON.parse(raw) as WorkflowEventView)
      } catch {
        // Ignore a trailing partial frame.
      }
    }
  }
}
