export interface CaptchaResponse {
  captchaId: string
  imageData: string
  expiresInSeconds: number
}

export interface LoginResponse {
  token: string
  username: string
  role: string
  expiresAt: string
}

export interface ControlPrincipal {
  username: string
  role: string
  permissions: string[]
}

export interface Overview {
  runtimeStatus: string
  storageMode: string
  conversationTotal: number
  taskTotal: number
  activeTasks: number
  unknownTasks: number
  agentTotal: number
}

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

export interface CursorPageResponse<T> {
  items: T[]
  nextSequence: number
  hasMore: boolean
}

export interface RuntimeConversation {
  id: string
  visitorRef: string
  title: string
  activeAgentCode?: string
  activeAgentName: string
  routingVersion: number
  createdAt: string
  lastMessageAt: string
}

export interface RuntimeEvent {
  type: string
  conversationId: string
  requestId: string
  sequence: number
  timestamp: string
  taskId?: string
  status?: string
  actionCode?: string
  agentCode?: string
}

export interface RuntimeTask {
  id: string
  visitorRef: string
  conversationId: string
  domainCode: string
  agentName: string
  actionCode: string
  status: string
  version: number
  confirmationVersion: number
  confirmationExpiresAt?: string
  externalRef?: string
  resultSummary?: string
  lastErrorCode?: string
  nextRecoveryAt?: string
  recoveryAttempts: number
  createdAt: string
  updatedAt: string
}

export interface ToolExecution {
  id: string
  toolCode: string
  toolVersionId?: string
  status: string
  inputSummary: Record<string, unknown>
  outputSummary: Record<string, unknown>
  externalRef?: string
  traceId?: string
  startedAt: string
  finishedAt?: string
}

export interface AuditRecord {
  id: string
  requestId?: string
  eventType: string
  actorType: string
  metadata: Record<string, unknown>
  createdAt: string
}

export interface TaskDetail {
  task: RuntimeTask
  toolExecutions: ToolExecution[]
  audits: AuditRecord[]
}

export interface RuntimeAgent {
  code: string
  displayName: string
  enabled: boolean
  visibleToVisitor: boolean
  iconKey: string
  actionCount: number
  actionModes: Record<string, number>
  routerStatus: string
  routeTotal: number
  ambiguousTotal: number
  failureTotal: number
  switchTotal: number
}

export type RuntimeConfig = Record<string, string | number | boolean>
export interface ControlResource {
  id: string
  code?: string
  name?: string
  displayName?: string
  providerType?: string
  modelType?: string
  modelName?: string
  secretRefType?: string
  configured?: boolean
  secretConfigured?: boolean
  enabled?: boolean
  draftContent?: string
  publishedVersionId?: string
  updatedAt?: string
}

export interface ModelConnectionResult {
  modelId: string
  status: 'CONNECTED' | 'CONNECTION_FAILED' | 'PROBE_UNAVAILABLE'
  testedAt: string
  message: string
}

export interface McpServer extends ControlResource {
  code: string
  displayName?: string
  transport: 'SSE' | 'STREAMABLE_HTTP' | 'STDIO'
  endpoint?: string
  enabled: boolean
  healthStatus?: string
  lastTestedAt?: string
  lastSyncedAt?: string
}

export interface McpTool extends ControlResource {
  serverId: string
  name: string
  latestVersionId?: string
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH'
  writeTool: boolean
  enabled: boolean
}

export interface KnowledgeBase extends ControlResource {
  code: string
  displayName: string
  description?: string
  status: string
  documentCount?: number
  chunkCount?: number
  embeddingModelId?: string
  updatedAt?: string
}

export interface KnowledgeDocument {
  id: string
  knowledgeBaseId: string
  name: string
  contentType: string
  sizeBytes?: number
  status: string
  currentVersionId?: string
  createdAt?: string
  updatedAt?: string
}

export interface KnowledgeIndexJob {
  id: string
  knowledgeBaseId: string
  documentId: string
  documentVersionId?: string
  status: string
  attempts: number
  lastErrorCode?: string
  createdAt?: string
  startedAt?: string
  finishedAt?: string
}

export interface KnowledgeChunk {
  id: string
  documentId: string
  documentVersionId?: string
  chunkIndex: number
  enabled: boolean
  tokenCount?: number
  contentPreview?: string
  updatedAt?: string
}

export interface KnowledgeCitation {
  knowledgeBaseId: string
  documentId: string
  documentVersionId?: string
  documentName?: string
  chunkId: string
  chunkIndex?: number
}

export interface KnowledgeRetrievalMatch {
  score: number
  rerankScore?: number
  citation: KnowledgeCitation
}

export interface AgentApplication {
  id: string
  code: string
  displayName: string
  description?: string
  status: string
  currentVersionId?: string
  createdAt?: string
  updatedAt?: string
}

export interface AgentApplicationVersion {
  id: string
  applicationId: string
  version: number
  status: string
  modelCode: string
  promptId: string
  promptVersionId: string
  knowledgeBaseId?: string
  config: Record<string, unknown>
  createdBy?: string
  publishedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface AgentAppBinding {
  id: string
  versionId: string
  resourceType: string
  resourceId: string
  resourceVersion?: string
  config: Record<string, unknown>
}

export interface AgentAppPublishRecord {
  id: string
  applicationId: string
  versionId: string
  previousVersionId?: string
  action: string
  actor: string
  createdAt: string
}

export interface AgentAppApiKey {
  id: string
  applicationId: string
  keyPrefix: string
  status: string
  scopes: string[]
  expiresAt?: string
  lastUsedAt?: string
  revokedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface AgentAppApiKeyCreated extends AgentAppApiKey {
  key: string
}

export type ConsoleSection = 'overview' | 'conversations' | 'agents' | 'tasks' | 'config' | 'models' | 'secrets' | 'prompts' | 'mcpServers' | 'mcpTools' | 'knowledge' | 'applications' | 'traces' | 'datasets' | 'evaluators' | 'experiments' | 'workflows' | 'workflowRuns'

export interface WorkflowDefinition {
  id: string
  code: string
  displayName: string
  description?: string
  status: string
  currentVersionId?: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowVersionView {
  id: string
  workflowId: string
  versionNo: number
  status: string
  schemaVersion: string
  dsl: unknown
  resourceBindings: Record<string, unknown>
  description?: string
  createdBy: string
  publishedAt?: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowRunView {
  id: string
  workflowId: string
  workflowVersionId: string
  code: string
  graphThreadId?: string
  status: string
  requestId?: string
  variables: Record<string, unknown>
  visitedNodeIds: string[]
  currentNodeId?: string
  errorCode?: string
  startedAt: string
  finishedAt?: string
  createdAt: string
  updatedAt: string
  events?: WorkflowEventView[]
}

export interface WorkflowNodeRunView {
  id: string
  nodeId: string
  nodeType: string
  status: string
  input: Record<string, unknown>
  output: Record<string, unknown>
  confirmationTaskId?: string
  confirmationVersion: number
  retryCount: number
  errorCode?: string
  startedAt?: string
  finishedAt?: string
}

export interface WorkflowEventView {
  id: string
  sequence: number
  type: string
  nodeId?: string
  payload: Record<string, unknown>
  createdAt: string
}

export interface WorkflowValidationResult {
  versionId: string
  valid: boolean
  issues: Array<{ resourceType: string; resourceId?: string; message: string }>
}

export interface TraceSpan {
  id: string
  traceId: string
  spanType: string
  name: string
  status: string
  visitorRef?: string
  conversationId?: string
  taskId?: string
  requestId?: string
  agentCode?: string
  actionCode?: string
  toolCode?: string
  modelProvider?: string
  modelName?: string
  inputTokens: number
  outputTokens: number
  totalTokens: number
  durationMs: number
  errorCode?: string
  resourceVersions: Record<string, unknown>
  metadata: Record<string, unknown>
  startedAt: string
  finishedAt: string
}

export interface ObservabilityOverview {
  totalTraces: number
  totalSpans: number
  spanTypeDistribution: Record<string, number>
  spanStatusDistribution: Record<string, number>
  model: {
    calls: number
    avgLatencyMs: number
    p95LatencyMs: number
    totalTokens: number
    errorCount: number
    timeoutCount: number
  }
  tool: {
    calls: number
    avgLatencyMs: number
    errorCount: number
    unknownCount: number
  }
  task: {
    statusDistribution: Record<string, number>
    confirmationCreated: number
    confirmationConfirmed: number
    confirmationRejected: number
    confirmationRate: number
    unknownTasks: number
    timeoutTasks: number
  }
}

export interface TraceDetail {
  traceId: string
  spanCount: number
  conversationIds: string[]
  taskIds: string[]
  requestIds: string[]
  agentCodes: string[]
  actionCodes: string[]
  toolCodes: string[]
  modelNames: string[]
  totalTokens: number
  totalDurationMs: number
  spans: TraceSpan[]
}

export interface EvalDataset {
  id: string
  code: string
  displayName: string
  description?: string
  currentVersionId?: string
  status: string
  caseCount: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface EvalDatasetVersion {
  id: string
  datasetId: string
  versionNo: number
  status: string
  description?: string
  caseCount: number
  createdAt: string
}

export interface EvalCase {
  id: string
  datasetVersionId: string
  caseKey: string
  category: string
  input: Record<string, unknown>
  expected: Record<string, unknown>
  tags: Record<string, unknown>
  source: string
  traceId?: string
  createdBy: string
  createdAt: string
}

export interface EvalEvaluatorVersion {
  id: string
  evaluatorId: string
  versionNo: number
  status: string
  config: Record<string, unknown>
  createdAt: string
}

export interface EvalEvaluator {
  id: string
  code: string
  displayName: string
  evaluatorType: string
  description?: string
  status: string
  currentVersionId?: string
  versions: EvalEvaluatorVersion[]
  createdAt: string
}

export interface EvalExperiment {
  id: string
  code: string
  displayName: string
  datasetId: string
  datasetVersionId: string
  agentApplicationId?: string
  agentVersionId: string
  evaluatorVersionIds: string[]
  status: string
  totalCases: number
  completedCases: number
  passedCases: number
  failedCases: number
  errorCases: number
  costMicros: number
  thresholdPassRate?: number
  passRate?: number
  startedAt?: string
  finishedAt?: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface EvalExperimentRun {
  id: string
  experimentId: string
  caseId: string
  caseKey: string
  status: string
  passed?: boolean
  score?: number
  outputSummary?: string
  evaluatorResults?: Array<{
    evaluatorVersionId: string
    evaluatorCode: string
    evaluatorType: string
    passed: boolean
    score?: number
    reason?: string
    details: Record<string, unknown>
  }>
  errorCode?: string
  tokensUsed: number
  costMicros: number
  startedAt?: string
  finishedAt?: string
}
