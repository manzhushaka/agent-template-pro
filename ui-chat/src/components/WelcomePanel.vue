<script setup lang="ts">
import {
  ArrowRight,
  Bot,
  Building2,
  CheckCircle2,
  Landmark,
  LockKeyhole,
  PackageSearch,
  ShoppingBag,
  Sparkles,
  Ticket,
  Trophy,
} from '@lucide/vue'
import type { Component } from 'vue'
import type { Bootstrap, SuggestedPrompt } from '../types/chat'

defineProps<{ bootstrap: Bootstrap | null }>()
defineEmits<{ prompt: [value: string] }>()

function prompts(bootstrap: Bootstrap | null): Array<SuggestedPrompt & { agentName: string; iconKey: string }> {
  return (bootstrap?.agents || []).flatMap((agent) => agent.suggestedPrompts
    .slice(0, 1)
    .map((prompt) => ({ ...prompt, agentName: agent.displayName, iconKey: agent.iconKey })))
}

const icons: Record<string, Component> = {
  bot: Bot,
  hotel: Building2,
  ticket: Ticket,
  landmark: Landmark,
  'shopping-bag': ShoppingBag,
  activity: Trophy,
}

function promptIcon(item: { iconKey: string }): Component {
  return icons[item.iconKey] || Bot
}

</script>

<template>
  <section class="welcome">
    <p class="welcome-kicker"><Sparkles :size="14" />{{ bootstrap?.coordinator.displayName || '集团总智能体' }}</p>
    <h1>今天想完成什么？</h1>
    <p>{{ bootstrap?.coordinator.description || '直接描述需求，我会协调专业服务。' }}</p>
    <div class="prompt-grid">
      <button v-for="item in prompts(bootstrap)" :key="item.prompt" type="button" @click="$emit('prompt', item.prompt)">
        <span><component :is="promptIcon(item)" :size="19" /></span><strong>{{ item.title }}</strong><small>{{ item.agentName }}</small><ArrowRight class="prompt-arrow" :size="15" />
      </button>
    </div>
    <div class="welcome-meta"><span><PackageSearch :size="13" />信息补全</span><span><LockKeyhole :size="13" />操作确认</span><span><CheckCircle2 :size="13" />任务追踪</span></div>
  </section>
</template>
