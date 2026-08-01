export interface SuggestedPrompt {
  title: string
  prompt: string
}

export interface PublicAgent {
  code: string
  displayName: string
  description: string
  iconKey: string
  suggestedPrompts: SuggestedPrompt[]
}

export interface Bootstrap {
  application: { code: string; displayName: string }
  coordinator: { code: string; displayName: string; description: string }
  agents: PublicAgent[]
}

export interface Conversation {
  id: string
  title: string
  activeAgentCode?: string
  activeAgentName?: string
  routingVersion: number
  lastMessageAt: string
}

export interface AgentIdentity {
  code: string
  name: string
}

export interface ChatMessage {
  role: 'USER' | 'ASSISTANT'
  content: string
  sequence?: number
  agent?: AgentIdentity
}

export interface StreamEvent {
  type: string
  conversationId: string
  requestId: string
  sequence: number
  timestamp: string
  payload: Record<string, unknown>
}

export interface TimelineItem {
  sequence: number
  kind: 'MESSAGE' | 'EVENT'
  role: string
  content: string
  eventType: string
  requestId?: string
  agentCode?: string
  agentName?: string
  actionCode?: string
  createdAt: string
  payload: Record<string, unknown>
}

export interface AgentRouteNotice {
  sequence: number
  routeType: string
  targetAgentCode?: string
  targetAgentName?: string
  reasonCode: string
  candidates: Array<{ code: string; displayName: string; iconKey: string }>
}

export interface FormState {
  pendingActionId: string
  fields: Array<{ name: string; label: string }>
  values: Record<string, string>
  agent?: AgentIdentity
}

export interface ConfirmState {
  taskId: string
  confirmationVersion: number
  title: string
  summary: Record<string, string>
  agent?: AgentIdentity
}

export interface ResultCard {
  cardType: string
  data: Record<string, string | number>
  agent?: AgentIdentity
  actionCode?: string
  sequence: number
}

export interface TaskEvent {
  taskId: string
  status: string
  externalRef?: string
  agent?: AgentIdentity
  actionCode?: string
  sequence: number
}
