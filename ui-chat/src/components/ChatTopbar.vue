<script setup lang="ts">
import { Bot, Menu } from '@lucide/vue'
import type { AgentIdentity, Bootstrap } from '../types/chat'

defineProps<{ bootstrap: Bootstrap | null; activeAgent: AgentIdentity }>()
defineEmits<{ menu: [] }>()
</script>

<template>
  <header class="chat-topbar">
    <button class="icon-button menu-button" type="button" aria-label="打开侧栏" @click="$emit('menu')"><Menu :size="19" /></button>
    <div class="topbar-title"><strong>{{ bootstrap?.application.displayName || '集团智慧服务' }}</strong><span><i />服务在线</span></div>
    <div class="topbar-context"><Bot :size="14" /><span>当前：</span>{{ activeAgent.name }}</div>
  </header>
</template>
