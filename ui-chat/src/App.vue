<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import {
  ArrowRight,
  CheckCircle2,
  LoaderCircle,
  LockKeyhole,
  Send,
  Sparkles,
  X,
} from '@lucide/vue'
import AgentRouteNotice from './components/AgentRouteNotice.vue'
import AgentSidebar from './components/AgentSidebar.vue'
import ChatTopbar from './components/ChatTopbar.vue'
import MessageAvatar from './components/MessageAvatar.vue'
import WelcomePanel from './components/WelcomePanel.vue'
import { useChatRuntime } from './composables/useChatRuntime'

const runtime = useChatRuntime()
const content = ref('')
const sidebarOpen = ref(false)
const messageViewport = ref<HTMLElement | null>(null)
const composerInput = ref<HTMLTextAreaElement | null>(null)

function sendText(value: string): void {
  if (!value.trim()) return
  runtime.send(value)
  content.value = ''
  sidebarOpen.value = false
  void nextTick(() => {
    resizeComposer()
    scrollToLatest()
  })
}

function resizeComposer(): void {
  const input = composerInput.value
  if (!input) return
  input.style.height = '0px'
  input.style.height = `${Math.min(input.scrollHeight, 120)}px`
}

function scrollToLatest(): void {
  void nextTick(() => messageViewport.value?.scrollTo({
    top: messageViewport.value.scrollHeight,
    behavior: 'smooth',
  }))
}

function taskStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    CREATED: '已创建',
    WAITING_INPUT: '待补充信息',
    WAITING_CONFIRMATION: '待确认',
    DISPATCHED: '执行中',
    WAITING_EXTERNAL_RESULT: '等待外部结果',
    SUCCEEDED: '已完成',
    FAILED: '未完成',
    CANCELLED: '已取消',
  }
  return labels[status] || status
}

function taskStatusClass(status: string): string {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  if (status.includes('WAITING')) return 'warning'
  return 'progress'
}

async function openConversation(id: string): Promise<void> {
  sidebarOpen.value = false
  try {
    await runtime.open(id)
  } catch (cause) {
    runtime.errorMessage.value = cause instanceof Error ? cause.message : '会话切换失败。'
  }
  scrollToLatest()
}

watch([
  () => runtime.messages.value.length,
  () => runtime.cards.value.length,
  () => runtime.tasks.value.length,
  () => runtime.routes.value.length,
], scrollToLatest)
watch(content, () => void nextTick(resizeComposer))
</script>

<template>
  <main class="chat-shell">
    <AgentSidebar
      :bootstrap="runtime.bootstrap.value"
      :conversations="runtime.conversations.value"
      :active-id="runtime.activeId.value"
      :open="sidebarOpen"
      :creating="runtime.creating.value"
      :loading="runtime.loading.value"
      @close="sidebarOpen = false"
      @create="runtime.createConversation"
      @open-conversation="openConversation"
      @select-agent="(code) => { runtime.selectAgent(code); sidebarOpen = false }"
    />
    <button v-if="sidebarOpen" class="sidebar-scrim" type="button" aria-label="关闭侧栏" @click="sidebarOpen = false" />

    <section class="chat-main">
      <ChatTopbar :bootstrap="runtime.bootstrap.value" :active-agent="runtime.activeAgent.value" @menu="sidebarOpen = true" />

      <div ref="messageViewport" class="message-viewport">
        <div class="message-content">
          <WelcomePanel v-if="!runtime.hasContent.value" :bootstrap="runtime.bootstrap.value" @prompt="sendText" />

          <div v-else class="message-list">
            <template v-for="notice in runtime.routes.value" :key="notice.sequence">
              <AgentRouteNotice :notice="notice" :loading="runtime.loading.value" @select="runtime.selectAgent" />
            </template>

            <article v-for="(message, index) in runtime.messages.value" :key="message.sequence || `local-${index}`" :class="['message', message.role === 'USER' ? 'user' : 'assistant']">
              <MessageAvatar v-if="message.role !== 'USER'" kind="assistant" :agent="message.agent" :bootstrap="runtime.bootstrap.value" />
              <div class="message-body">
                <span class="message-role">{{ message.role === 'USER' ? '你' : (message.agent?.name || 'Agent Pro') }}</span>
                <small v-if="message.role !== 'USER' && message.agent?.code !== 'group-assistant'" class="coordinator-note">由 Agent Pro 协调</small>
                <div class="bubble"><p>{{ message.content }}</p></div>
              </div>
              <MessageAvatar v-if="message.role === 'USER'" kind="user" :bootstrap="runtime.bootstrap.value" />
            </article>

            <article v-if="runtime.loading.value" class="message assistant" aria-label="智能体正在处理">
              <MessageAvatar kind="assistant" :agent="runtime.activeAgent.value" :bootstrap="runtime.bootstrap.value" />
              <div class="message-body"><span class="message-role">{{ runtime.activeAgent.value.name }}</span><div class="bubble typing"><i /><i /><i /></div></div>
            </article>

            <section v-if="runtime.cards.value.length" class="result-list" aria-label="业务结果">
              <article v-for="card in runtime.cards.value" :key="card.sequence" class="result-card">
                <header><span><CheckCircle2 :size="16" /></span><div><small>{{ card.agent?.name || '业务结果' }}</small><strong>{{ card.cardType }}</strong></div></header>
                <dl><template v-for="(value, key) in card.data" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl>
              </article>
            </section>

            <section v-if="runtime.tasks.value.length" class="task-list" aria-label="任务状态">
              <article v-for="task in runtime.tasks.value" :key="task.sequence" class="task-item">
                <span :class="['task-indicator', taskStatusClass(task.status)]"><LoaderCircle v-if="taskStatusClass(task.status) === 'progress'" class="spin" :size="15" /><CheckCircle2 v-else :size="15" /></span>
                <div><small>{{ task.agent?.name || '业务任务' }} · {{ task.taskId.slice(0, 12) }}</small><strong>{{ taskStatusLabel(task.status) }}</strong><span v-if="task.externalRef">业务编号 {{ task.externalRef }}</span></div>
              </article>
            </section>

            <form v-if="runtime.form.value" class="action-sheet" @submit.prevent="runtime.submitForm">
              <header><span><Sparkles :size="17" /></span><div><small>{{ runtime.form.value.agent?.name || '信息补全' }}</small><h2>完善操作信息</h2></div></header>
              <div class="field-grid">
                <label v-for="field in runtime.form.value.fields" :key="field.name"><span>{{ field.label }}</span><input v-model="runtime.form.value.values[field.name]" :name="field.name" autocomplete="off" required /></label>
              </div>
              <button class="primary" type="submit" :disabled="runtime.loading.value">继续处理<ArrowRight :size="15" /></button>
            </form>
          </div>
        </div>
      </div>

      <div v-if="runtime.errorMessage.value" class="error-banner" role="alert"><span>{{ runtime.errorMessage.value }}</span><button type="button" aria-label="关闭错误提示" @click="runtime.errorMessage.value = ''"><X :size="14" /></button></div>

      <footer class="composer-wrap">
        <form class="composer" @submit.prevent="sendText(content)">
          <textarea ref="composerInput" v-model="content" placeholder="描述你希望完成的事情..." rows="1" :disabled="runtime.loading.value || !runtime.activeId.value" aria-label="输入消息" @keydown.enter.exact.prevent="sendText(content)" />
          <button class="send" type="submit" :disabled="!content.trim() || runtime.loading.value || !runtime.activeId.value" aria-label="发送消息"><LoaderCircle v-if="runtime.loading.value" class="spin" :size="18" /><Send v-else :size="18" /></button>
        </form>
        <p><LockKeyhole :size="12" />关键业务操作会在执行前请你确认</p>
      </footer>

      <div v-if="runtime.confirm.value" class="confirm-layer" @mousedown.self="runtime.decide('REJECTED')">
        <section class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
          <header><span><LockKeyhole :size="19" /></span><div><small>{{ runtime.confirm.value.agent?.name || '操作确认' }}</small><h2 id="confirm-title">{{ runtime.confirm.value.title }}</h2></div></header>
          <p>请核对以下信息。确认后系统才会执行本次操作。</p>
          <dl><template v-for="(value, key) in runtime.confirm.value.summary" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl>
          <div class="dialog-actions"><button class="secondary" type="button" :disabled="runtime.loading.value" @click="runtime.decide('REJECTED')">取消</button><button class="primary" type="button" :disabled="runtime.loading.value" @click="runtime.decide('CONFIRMED')">确认执行</button></div>
        </section>
      </div>
    </section>
  </main>
</template>
