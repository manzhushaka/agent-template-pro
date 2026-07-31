<script setup lang="ts">
import { onMounted, ref } from 'vue'

type Message = { role: string; content: string; sequence?: number }
type FormState = { pendingActionId: string; fields: { name: string; label: string }[]; values: Record<string, string> }
type ConfirmState = { taskId: string; confirmationVersion: number; title: string; summary: Record<string, string> }
type Card = { cardType: string; data: Record<string, string> }
const api = import.meta.env.VITE_API_BASE || '/api/chat/v1'
const conversations = ref<any[]>([]); const activeId = ref(''); const messages = ref<Message[]>([]); const content = ref(''); const loading = ref(false)
const form = ref<FormState | null>(null); const confirm = ref<ConfirmState | null>(null); const cards = ref<Card[]>([]); const tasks = ref<any[]>([])
const requestId = () => crypto.randomUUID()
async function loadConversations() { const response = await fetch(`${api}/conversations`, { credentials: 'include' }); conversations.value = await response.json(); if (!activeId.value && conversations.value[0]) await open(conversations.value[0].id) }
async function createConversation() { const response = await fetch(`${api}/conversations`, { method: 'POST', credentials: 'include' }); const conversation = await response.json(); conversations.value.unshift(conversation); await open(conversation.id) }
async function open(id: string) { activeId.value = id; const response = await fetch(`${api}/conversations/${id}/messages`, { credentials: 'include' }); const history = await response.json(); messages.value = history.filter((item: any) => item.role !== 'SYSTEM').map((item: any) => ({ role: item.role, content: item.content, sequence: item.sequence })); cards.value = []; form.value = null; confirm.value = null }
async function stream(path: string, body: unknown) { loading.value = true; try { const response = await fetch(`${api}${path}`, { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json', 'X-Client-Request-Id': requestId(), Accept: 'text/event-stream' }, body: JSON.stringify(body) }); if (!response.ok || !response.body) throw new Error('服务暂时不可用'); const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''; while (true) { const { done, value } = await reader.read(); if (done) break; buffer += decoder.decode(value, { stream: true }); const blocks = buffer.split('\n\n'); buffer = blocks.pop() || ''; for (const block of blocks) { const event = block.match(/^event: (.+)$/m)?.[1]; const raw = block.match(/^data: (.+)$/m)?.[1]; if (event && raw) receive(event, JSON.parse(raw)); } } } catch (error) { messages.value.push({ role: 'ASSISTANT', content: error instanceof Error ? error.message : '连接失败，请重试。' }) } finally { loading.value = false } }
function receive(type: string, event: any) { const payload = event.payload || {}; if (type === 'message.final') messages.value.push({ role: 'ASSISTANT', content: payload.content || '' }); if (type === 'form.request') form.value = { pendingActionId: payload.pendingActionId, fields: payload.fields, values: {} }; if (type === 'action.confirm') confirm.value = payload; if (type === 'card.render') cards.value.unshift(payload); if (type === 'task.status') tasks.value.unshift(payload) }
function send() { const text = content.value.trim(); if (!text || !activeId.value || loading.value) return; messages.value.push({ role: 'USER', content: text }); content.value = ''; stream(`/conversations/${activeId.value}/messages:stream`, { content: text }) }
function submitForm() { if (form.value) stream(`/pending-actions/${form.value.pendingActionId}/input`, { input: form.value.values }) }
function decide(decision: string) { if (confirm.value) { const state = confirm.value; confirm.value = null; stream(`/tasks/${state.taskId}/confirm`, { confirmationVersion: state.confirmationVersion, decision }) } }
onMounted(async () => { await loadConversations(); if (!activeId.value) await createConversation() })
</script>

<template>
  <main class="chat-shell">
    <aside class="conversation-rail">
      <div class="brand"><span class="brand-mark">A</span><span>Agent Studio</span></div>
      <button class="new-chat" @click="createConversation">+ 新建会话</button>
      <nav><button v-for="item in conversations" :key="item.id" :class="{ active: item.id === activeId }" @click="open(item.id)"><span>{{ item.title }}</span><small>{{ item.lastMessageAt?.slice(0, 10) }}</small></button></nav>
      <p class="rail-note">匿名会话已受保护</p>
    </aside>
    <section class="chat-main">
      <header><div><span class="eyebrow">DEMO DOMAIN</span><h1>自然语言服务</h1></div><span class="connection"><i></i>服务在线</span></header>
      <div class="messages" aria-live="polite">
        <div v-if="messages.length === 0" class="empty"><span class="empty-mark">A</span><h2>从一个需求开始</h2><p>试试“查询上海天气”、“为张三预约明天”或“查询配送进度”。</p></div>
        <article v-for="(message, index) in messages" :key="`${message.sequence}-${index}`" :class="['bubble', message.role === 'USER' ? 'user' : 'assistant']"><span class="role">{{ message.role === 'USER' ? '你' : '助手' }}</span><p>{{ message.content }}</p></article>
        <article v-for="(card, index) in cards" :key="index" class="result-card"><span class="eyebrow">{{ card.cardType }}</span><dl><template v-for="(value, key) in card.data" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl></article>
        <article v-for="task in tasks" :key="task.taskId" class="task-line">任务 {{ task.taskId?.slice(0, 12) }} <strong>{{ task.status }}</strong></article>
      </div>
      <form v-if="form" class="action-sheet" @submit.prevent="submitForm"><div><span class="eyebrow">需要补充</span><h2>完善操作信息</h2></div><label v-for="field in form.fields" :key="field.name">{{ field.label }}<input v-model="form.values[field.name]" :name="field.name" required /></label><button class="primary" :disabled="loading">继续</button></form>
      <div v-if="confirm" class="confirm-layer"><section class="confirm-dialog"><span class="eyebrow">ACTION CONFIRMATION</span><h2>{{ confirm.title }}</h2><dl><template v-for="(value, key) in confirm.summary" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl><div class="dialog-actions"><button class="secondary" @click="decide('REJECTED')">取消</button><button class="primary" @click="decide('CONFIRMED')">确认提交</button></div></section></div>
      <form class="composer" @submit.prevent="send"><textarea v-model="content" placeholder="描述你希望完成的事情" rows="1" :disabled="loading" @keydown.enter.exact.prevent="send"/><button class="send" type="submit" :disabled="!content.trim() || loading" aria-label="发送消息">↑</button></form>
    </section>
  </main>
</template>
