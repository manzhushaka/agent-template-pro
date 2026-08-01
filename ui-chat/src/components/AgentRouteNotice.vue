<script setup lang="ts">
import { ArrowRight, Bot } from '@lucide/vue'
import type { AgentRouteNotice } from '../types/chat'

defineProps<{ notice: AgentRouteNotice; loading: boolean }>()
defineEmits<{ select: [code: string] }>()
</script>

<template>
  <section class="route-notice">
    <Bot :size="14" />
    <span v-if="notice.targetAgentName">Agent Pro 已为你转接至 <strong>{{ notice.targetAgentName }}</strong></span>
    <div v-else-if="notice.candidates.length" class="route-clarification">
      <span>请选择希望先处理的专业服务</span>
      <button v-for="candidate in notice.candidates" :key="candidate.code" type="button" :disabled="loading" @click="$emit('select', candidate.code)">
        {{ candidate.displayName }}<ArrowRight :size="13" />
      </button>
    </div>
    <span v-else>由 <strong>Agent Pro</strong> 处理集团公共服务</span>
  </section>
</template>
