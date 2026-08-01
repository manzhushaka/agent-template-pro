<script setup lang="ts">
import {
  Bot,
  Building2,
  Landmark,
  Map,
  ShoppingBag,
  Ticket,
  Trophy,
  UserRound,
} from '@lucide/vue'
import type { Component } from 'vue'
import type { AgentIdentity, Bootstrap } from '../types/chat'

const props = defineProps<{
  kind: 'assistant' | 'user'
  agent?: AgentIdentity
  bootstrap: Bootstrap | null
}>()

const icons: Record<string, Component> = {
  bot: Bot,
  hotel: Building2,
  ticket: Ticket,
  landmark: Landmark,
  'shopping-bag': ShoppingBag,
  activity: Trophy,
  map: Map,
}

function avatarIcon(): Component {
  if (props.kind === 'user') return UserRound
  const iconKey = props.bootstrap?.agents.find((agent) => agent.code === props.agent?.code)?.iconKey
  return icons[iconKey || 'bot'] || Bot
}
</script>

<template>
  <span role="img" :class="['message-avatar', kind]" :aria-label="kind === 'user' ? '你的头像' : `${agent?.name || 'Agent Pro'}头像`">
    <component :is="avatarIcon()" :size="17" :stroke-width="2.1" />
  </span>
</template>
