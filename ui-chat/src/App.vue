<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useQuasar } from 'quasar'
import {
  ArrowRight,
  CheckCircle2,
  LoaderCircle,
  LockKeyhole,
  RefreshCw,
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
import type {
  AgentRouteNotice as RouteNotice,
  ChatMessage,
  FormState,
  ResultCard,
  TaskEvent,
} from './types/chat'

interface VirtualScrollPayload {
  index: number
  from: number
  to: number
  direction: 'increase' | 'decrease'
  ref: { $el: HTMLElement }
}

type TimelineEntry =
  | { kind: 'route'; notice: RouteNotice; key: string }
  | { kind: 'message'; message: ChatMessage; key: string }
  | { kind: 'typing'; key: string }
  | { kind: 'cards'; cards: ResultCard[]; key: string }
  | { kind: 'tasks'; tasks: TaskEvent[]; key: string }
  | { kind: 'form'; form: FormState; key: string }

const $q = useQuasar()
const runtime = useChatRuntime()

const content = ref('')
const sidebarOpen = ref(false)
const timelineRef = ref<{ scrollTo: (index: number, edge?: string) => void } | null>(null)
const stickToBottom = ref(true)
let autoScrolling = false
const freshStart = ref(-1)
let prevTimelineLength = 0
let lastTailKey = ''

const isCompact = computed(() => $q.screen.width <= 800)
const darkMode = computed(() => $q.dark.isActive)
const confirmPosition = computed(() => (isCompact.value ? 'bottom' : 'standard'))
const confirmTransitionShow = computed(() => (isCompact.value ? 'slide-up' : 'scale'))
const confirmTransitionHide = computed(() => (isCompact.value ? 'slide-down' : 'scale'))
const canSend = computed(() => content.value.trim().length > 0 && !runtime.loading.value && !!runtime.activeId.value)

// 路由提示 / 消息 / 打字指示 / 业务卡片 / 任务 / 补参表单，
// 统一拍平成一条时间线交给 QVirtualScroll 虚拟滚动渲染。
const timeline = computed<TimelineEntry[]>(() => {
  const entries: TimelineEntry[] = []
  runtime.routes.value.forEach((notice) => {
    entries.push({ kind: 'route', notice, key: `route-${notice.sequence}` })
  })
  runtime.messages.value.forEach((message, index) => {
    entries.push({
      kind: 'message',
      message,
      key: message.sequence !== undefined ? `msg-${message.sequence}` : `local-${index}`,
    })
  })
  if (runtime.loading.value) entries.push({ kind: 'typing', key: 'typing' })
  if (runtime.cards.value.length > 0) entries.push({ kind: 'cards', cards: runtime.cards.value, key: 'cards' })
  if (runtime.tasks.value.length > 0) entries.push({ kind: 'tasks', tasks: runtime.tasks.value, key: 'tasks' })
  if (runtime.form.value) entries.push({ kind: 'form', form: runtime.form.value, key: 'form' })
  return entries
})

watch(timeline, (entries) => {
  const tailKey = entries.length > 0 ? entries[entries.length - 1].key : ''
  const tailChanged = tailKey !== lastTailKey
  lastTailKey = tailKey
  const grew = entries.length - prevTimelineLength
  prevTimelineLength = entries.length
  // 仅当列表小幅度增长（新消息/新卡片/打字指示）或尾部元素替换（打字 -> 回复）时，
  // 给尾部新元素入场动画
  const animated = grew > 0 || tailChanged
  freshStart.value = animated && grew <= 4 ? Math.max(0, entries.length - Math.max(grew, 1)) : -1
  if (animated && stickToBottom.value) {
    void nextTick(() => scrollToBottom())
  }
})

watch(() => runtime.activeId.value, () => {
  prevTimelineLength = 0
  stickToBottom.value = true
  void nextTick(() => scrollToBottom())
})

function isFresh(index: number): boolean {
  return freshStart.value >= 0 && index >= freshStart.value
}

function scrollToBottom(): void {
  const virtualScroll = timelineRef.value
  const count = timeline.value.length
  if (!virtualScroll || count <= 0) return
  autoScrolling = true
  // 先定位到尾部切片，让虚拟列表渲染并测量最后一批条目
  virtualScroll.scrollTo(count - 1, 'end-force')
  // 测量按 rAF/去抖异步推进，做几次限时贴底对齐（最多约 700ms）
  const el = (virtualScroll as unknown as { $el: HTMLElement }).$el
  const align = (attempt: number): void => {
    if (attempt > 5) {
      autoScrolling = false
      return
    }
    el.scrollTop = el.scrollHeight
    window.setTimeout(() => align(attempt + 1), 140)
  }
  align(0)
}

// 用户上滑查看历史时停止跟随底部，回到底部后自动恢复跟随
function onVirtualScroll(payload: VirtualScrollPayload): void {
  if (autoScrolling) return
  const el = payload.ref.$el
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 120
}

function sendText(value: string): void {
  const text = value.trim()
  if (!text || !canSend.value) return
  stickToBottom.value = true
  runtime.send(text)
  content.value = ''
  sidebarOpen.value = false
  void nextTick(() => scrollToBottom())
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
    EXPIRED: '确认已过期',
    UNKNOWN: '结果确认中',
    MANUAL: '等待人工处理',
  }
  return labels[status] || status
}

function taskStatusClass(status: string): string {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED' || status === 'EXPIRED') return 'danger'
  if (status.includes('WAITING') || status === 'UNKNOWN' || status === 'MANUAL') return 'warning'
  return 'progress'
}

async function openConversation(id: string): Promise<void> {
  sidebarOpen.value = false
  stickToBottom.value = true
  try {
    await runtime.open(id)
  } catch (cause) {
    runtime.errorMessage.value = cause instanceof Error ? cause.message : '会话切换失败。'
  }
  void nextTick(() => scrollToBottom())
}

// QDialog 由用户主动关闭（点遮罩 / 按 Esc / 取消）时视为拒绝；
// Runtime 因任务状态推进而清除 confirm 时不会重复拒绝。
function onConfirmHide(): void {
  if (runtime.confirm.value) runtime.decide('REJECTED')
}

function toggleDark(): void {
  $q.dark.set(!$q.dark.isActive)
}
</script>

<template>
  <q-layout view="lHh LpR lFf" class="chat-shell">
    <q-drawer
      v-model="sidebarOpen"
      side="left"
      :width="276"
      :breakpoint="800"
      show-if-above
      class="chat-drawer"
    >
      <AgentSidebar
        :bootstrap="runtime.bootstrap.value"
        :conversations="runtime.conversations.value"
        :active-id="runtime.activeId.value"
        :creating="runtime.creating.value"
        :loading="runtime.loading.value"
        @close="sidebarOpen = false"
        @create="runtime.createConversation"
        @open-conversation="openConversation"
        @select-agent="(code) => { runtime.selectAgent(code); sidebarOpen = false }"
      />
    </q-drawer>

    <q-page-container>
      <div class="chat-page">
        <ChatTopbar
          :bootstrap="runtime.bootstrap.value"
          :active-agent="runtime.activeAgent.value"
          :dark="darkMode"
          @menu="sidebarOpen = true"
          @toggle-dark="toggleDark"
        />

        <div v-if="!runtime.hasContent.value" class="message-viewport welcome-viewport">
          <div class="message-content">
            <WelcomePanel :bootstrap="runtime.bootstrap.value" @prompt="sendText" />
          </div>
        </div>

        <q-virtual-scroll
          v-else
          ref="timelineRef"
          :items="timeline"
          :virtual-scroll-item-size="64"
          :virtual-scroll-slice-size="12"
          :virtual-scroll-slice-ratio-before="1.2"
          :virtual-scroll-slice-ratio-after="1.2"
          class="timeline-scroll"
          @virtual-scroll="onVirtualScroll"
        >
          <template #default="{ item, index }">
            <div class="timeline-row" :class="{ fresh: isFresh(index) }">
              <div class="message-content">
                <AgentRouteNotice
                  v-if="item.kind === 'route'"
                  :notice="item.notice"
                  :loading="runtime.loading.value"
                  @select="runtime.selectAgent"
                />

                <article
                  v-else-if="item.kind === 'message'"
                  :class="['message', item.message.role === 'USER' ? 'user' : 'assistant']"
                >
                  <MessageAvatar
                    v-if="item.message.role !== 'USER'"
                    kind="assistant"
                    :agent="item.message.agent"
                    :bootstrap="runtime.bootstrap.value"
                  />
                  <div class="message-body">
                    <span class="message-role">{{ item.message.role === 'USER' ? '你' : (item.message.agent?.name || 'Agent Pro') }}</span>
                    <small
                      v-if="item.message.role !== 'USER' && item.message.agent?.code !== 'group-assistant'"
                      class="coordinator-note"
                    >由 Agent Pro 协调</small>
                    <div class="bubble"><p>{{ item.message.content }}</p></div>
                  </div>
                  <MessageAvatar
                    v-if="item.message.role === 'USER'"
                    kind="user"
                    :bootstrap="runtime.bootstrap.value"
                  />
                </article>

                <article v-else-if="item.kind === 'typing'" class="message assistant" aria-label="智能体正在处理">
                  <MessageAvatar
                    kind="assistant"
                    :agent="runtime.activeAgent.value"
                    :bootstrap="runtime.bootstrap.value"
                  />
                  <div class="message-body">
                    <span class="message-role">{{ runtime.activeAgent.value.name }}</span>
                    <div class="bubble typing"><i /><i /><i /></div>
                  </div>
                </article>

                <section v-else-if="item.kind === 'cards'" class="result-list" aria-label="业务结果">
                  <article v-for="card in item.cards" :key="card.sequence" class="result-card">
                    <header>
                      <span><CheckCircle2 :size="16" /></span>
                      <div><small>{{ card.agent?.name || '业务结果' }}</small><strong>{{ card.cardType }}</strong></div>
                    </header>
                    <dl>
                      <template v-for="(value, key) in card.data" :key="key">
                        <dt>{{ key }}</dt><dd>{{ value }}</dd>
                      </template>
                    </dl>
                  </article>
                </section>

                <section v-else-if="item.kind === 'tasks'" class="task-list" aria-label="任务状态">
                  <article v-for="task in item.tasks" :key="task.taskId" class="task-item">
                    <span :class="['task-indicator', taskStatusClass(task.status)]">
                      <LoaderCircle v-if="taskStatusClass(task.status) === 'progress'" class="spin" :size="15" />
                      <CheckCircle2 v-else :size="15" />
                    </span>
                    <div>
                      <small>{{ task.agent?.name || '业务任务' }} · {{ task.taskId.slice(0, 12) }}</small>
                      <strong>{{ taskStatusLabel(task.status) }}</strong>
                      <span v-if="task.externalRef">业务编号 {{ task.externalRef }}</span>
                    </div>
                  </article>
                </section>

                <q-form v-else-if="item.kind === 'form'" class="action-sheet" @submit.prevent="runtime.submitForm">
                  <header>
                    <span><Sparkles :size="17" /></span>
                    <div><small>{{ item.form.agent?.name || '信息补全' }}</small><h2>完善操作信息</h2></div>
                  </header>
                  <div class="field-grid">
                    <q-input
                      v-for="field in item.form.fields"
                      :key="field.name"
                      v-model="item.form.values[field.name]"
                      :name="field.name"
                      :label="field.label"
                      stack-label
                      dense
                      outlined
                      autocomplete="off"
                      lazy-rules
                      :rules="[(value: string | null) => Boolean(value && String(value).trim()) || `请填写${field.label}`]"
                    />
                  </div>
                  <q-btn
                    class="form-submit"
                    type="submit"
                    unelevated
                    color="primary"
                    :disable="runtime.loading.value"
                    :loading="runtime.loading.value"
                  >继续处理<ArrowRight :size="15" /></q-btn>
                </q-form>
              </div>
            </div>
          </template>
        </q-virtual-scroll>

        <Transition name="banner">
          <div v-if="runtime.errorMessage.value" class="error-banner" role="alert">
            <span>{{ runtime.errorMessage.value }}</span>
            <button type="button" aria-label="重新加载" :disabled="runtime.restoring.value" @click="runtime.retryRecovery">
              <LoaderCircle v-if="runtime.restoring.value" class="spin" :size="14" />
              <RefreshCw v-else :size="14" />
            </button>
            <button type="button" aria-label="关闭错误提示" @click="runtime.errorMessage.value = ''"><X :size="14" /></button>
          </div>
        </Transition>

        <footer class="composer-wrap">
          <div class="composer">
            <q-input
              v-model="content"
              class="composer-input"
              type="textarea"
              autogrow
              borderless
              :input-style="{ minHeight: '38px', maxHeight: '120px', lineHeight: '20px' }"
              :maxlength="4000"
              placeholder="描述你希望完成的事情..."
              :disable="runtime.loading.value || !runtime.activeId.value"
              aria-label="输入消息"
              @keydown.enter.exact.prevent="sendText(content)"
            />
            <q-btn
              class="composer-send"
              round
              unelevated
              color="primary"
              :disable="!canSend"
              :loading="runtime.loading.value"
              :aria-label="runtime.loading.value ? '处理中' : '发送消息'"
              @click="sendText(content)"
            >
              <Send v-if="!runtime.loading.value" :size="18" />
            </q-btn>
          </div>
          <p><LockKeyhole :size="12" />关键业务操作会在执行前请你确认</p>
        </footer>

        <q-dialog
          :model-value="Boolean(runtime.confirm.value)"
          :position="confirmPosition"
          :transition-show="confirmTransitionShow"
          :transition-hide="confirmTransitionHide"
          @hide="onConfirmHide"
        >
          <div v-if="runtime.confirm.value" class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
            <header>
              <span><LockKeyhole :size="19" /></span>
              <div><small>{{ runtime.confirm.value.agent?.name || '操作确认' }}</small><h2 id="confirm-title">{{ runtime.confirm.value.title }}</h2></div>
            </header>
            <p>请核对以下信息。确认后系统才会执行本次操作。</p>
            <dl>
              <template v-for="(value, key) in runtime.confirm.value.summary" :key="key">
                <dt>{{ key }}</dt><dd>{{ value }}</dd>
              </template>
            </dl>
            <div class="dialog-actions">
              <q-btn
                class="confirm-secondary"
                outline
                unelevated
                color="grey-8"
                :disable="runtime.loading.value"
                @click="runtime.decide('REJECTED')"
              >取消</q-btn>
              <q-btn
                class="confirm-primary"
                unelevated
                color="primary"
                :loading="runtime.loading.value"
                @click="runtime.decide('CONFIRMED')"
              >确认执行</q-btn>
            </div>
          </div>
        </q-dialog>
      </div>
    </q-page-container>
  </q-layout>
</template>

<style scoped>
/* 补参表单（QForm + QInput）：保持原「label 在上、白底圆角输入框」的视觉 */
.field-grid :deep(.q-field--outlined .q-field__control) {
  min-height: 40px;
  border-radius: 6px;
  background: var(--surface);
  color: var(--ink);
}
.field-grid :deep(.q-field--outlined .q-field__label) {
  color: #4e544e;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
}
.field-grid :deep(.q-field--outlined .q-field__native) {
  font-size: 11px;
  color: var(--ink);
}
.field-grid :deep(.q-field--focused .q-field__label) {
  color: var(--coral);
}
.field-grid :deep(.q-field--error .q-field__bottom) {
  font-size: 9px;
}

.form-submit {
  margin-top: 14px;
  min-height: 40px;
  padding: 0 14px;
  font-size: 11px;
  font-weight: 750;
}

/* 输入框（QInput autogrow）：沿用原 textarea 字号与占位色 */
.composer-input {
  flex: 1;
}
.composer-input :deep(.q-field__control) {
  min-height: 38px;
  height: auto;
}
.composer-input :deep(.q-field__native) {
  padding: 9px 2px 9px 0;
  font-size: 12px;
  line-height: 20px;
  color: var(--ink);
}
.composer-input :deep(.q-field__native::placeholder) {
  color: #999e96;
}

.composer-send {
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
}
.composer-send:active:not(.q-btn--disabled) {
  transform: scale(0.94);
}
.composer-send.q-btn--disabled {
  opacity: 0.72;
}

.confirm-secondary,
.confirm-primary {
  min-height: 40px;
  padding: 0 14px;
  font-size: 11px;
  font-weight: 750;
}
.confirm-primary:active:not(.q-btn--disabled) {
  transform: scale(0.97);
}
</style>
