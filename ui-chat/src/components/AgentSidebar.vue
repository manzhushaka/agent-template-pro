<script setup lang="ts">
import {
  Activity,
  Bot,
  Building2,
  History,
  MessageSquareText,
  Plus,
  ShieldCheck,
  ShoppingBag,
  Ticket,
  Trophy,
  X,
} from '@lucide/vue'
import type { Component } from 'vue'
import type { Bootstrap, Conversation, PublicAgent } from '../types/chat'
import AppIcon from './AppIcon.vue'

defineProps<{
  bootstrap: Bootstrap | null
  conversations: Conversation[]
  activeId: string
  open: boolean
  creating: boolean
  loading: boolean
}>()

const emit = defineEmits<{
  close: []
  create: []
  openConversation: [id: string]
  selectAgent: [code: string]
}>()

const icons: Record<string, Component> = {
  bot: Bot,
  hotel: Building2,
  ticket: Ticket,
  landmark: Activity,
  'shopping-bag': ShoppingBag,
  activity: Trophy,
}

function agentIcon(agent: PublicAgent): Component {
  return icons[agent.iconKey] || Bot
}

function conversationDate(value: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const today = new Date()
  if (date.toDateString() === today.toDateString()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
  }
  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}
</script>

<template>
  <aside :class="['conversation-rail', { open }]">
    <div class="brand">
      <span class="brand-mark"><AppIcon :size="32" /></span>
      <span><strong>Agent Pro</strong><small>{{ bootstrap?.coordinator.displayName || 'GROUP ASSISTANT' }}</small></span>
      <button class="icon-button rail-close" type="button" aria-label="关闭侧栏" @click="emit('close')"><X :size="18" /></button>
    </div>

    <button class="new-chat" type="button" :disabled="creating || loading" @click="emit('create')">
      <Plus :size="15" />新建会话
    </button>

    <section class="quick-services">
      <p><Bot :size="12" />总智能体</p>
      <button type="button" @click="emit('close')"><Bot :size="15" /><span>统一理解与协调</span></button>
      <p class="service-heading"><ShieldCheck :size="12" />专业服务</p>
      <button v-for="agent in bootstrap?.agents || []" :key="agent.code" type="button" @click="emit('selectAgent', agent.code)">
        <component :is="agentIcon(agent)" :size="15" /><span>{{ agent.displayName }}</span>
      </button>
    </section>

    <section class="conversation-history">
      <p><History :size="12" />历史会话</p>
      <nav v-if="conversations.length">
        <button v-for="item in conversations" :key="item.id" :class="{ active: item.id === activeId }" type="button" @click="emit('openConversation', item.id)">
          <MessageSquareText :size="14" /><span>{{ item.title }}</span><time>{{ conversationDate(item.lastMessageAt) }}</time>
        </button>
      </nav>
      <div v-else class="history-empty">暂无历史会话</div>
    </section>

    <div class="privacy-note"><ShieldCheck :size="15" /><span><strong>服务端身份隔离</strong><small>关键操作均需确认并保留任务记录</small></span></div>
  </aside>
</template>
