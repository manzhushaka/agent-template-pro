import type { Bootstrap, Conversation, StreamEvent, TimelineItem } from '../types/chat'

const apiBase = import.meta.env.VITE_API_BASE || '/api/chat/v1'

interface ApiError {
  message?: string
}

function requestId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function errorMessage(response: Response, fallback: string): Promise<string> {
  try {
    const body = await response.json() as ApiError
    return body.message || fallback
  } catch {
    return fallback
  }
}

async function json<T>(path: string, fallback: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBase}${path}`, { credentials: 'include', ...init })
  if (!response.ok) throw new Error(await errorMessage(response, fallback))
  return await response.json() as T
}

export const chatApi = {
  bootstrap: () => json<Bootstrap>('/bootstrap', '服务范围加载失败，请稍后重试。'),
  conversations: () => json<Conversation[]>('/conversations', '会话列表加载失败，请稍后重试。'),
  createConversation: () => json<Conversation>('/conversations', '创建会话失败，请稍后重试。', { method: 'POST' }),
  timeline: (conversationId: string) => json<TimelineItem[]>(
    `/conversations/${conversationId}/timeline?afterSequence=0&limit=200`,
    '会话记录加载失败，请稍后重试。',
  ),
  events: (conversationId: string, afterSequence: number) => json<StreamEvent[]>(
    `/conversations/${conversationId}/events?afterSequence=${afterSequence}`,
    '会话恢复失败，请稍后重试。',
  ),
}

export async function streamChat(
  path: string,
  body: unknown,
  receive: (event: StreamEvent) => void,
): Promise<void> {
  const response = await fetch(`${apiBase}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-Client-Request-Id': requestId(),
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(body),
  })
  if (!response.ok || !response.body) {
    throw new Error(await errorMessage(response, '服务暂时不可用，请稍后重试。'))
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
    blocks.forEach((block) => readBlock(block, receive))
  }
  if (buffer.trim()) readBlock(buffer, receive)
}

function readBlock(block: string, receive: (event: StreamEvent) => void): void {
  const raw = block.match(/^data:\s*(.+)$/m)?.[1]
  if (raw) receive(JSON.parse(raw) as StreamEvent)
}
