<script setup lang="ts">
import { Bot, Menu, Moon, Sun } from '@lucide/vue'
import type { AgentIdentity, Bootstrap } from '../types/chat'

defineProps<{
  bootstrap: Bootstrap | null
  activeAgent: AgentIdentity
  dark: boolean
}>()

defineEmits<{ menu: []; toggleDark: [] }>()
</script>

<template>
  <header class="chat-topbar">
    <q-btn flat round dense class="menu-button" aria-label="打开侧栏" @click="$emit('menu')">
      <Menu :size="19" />
    </q-btn>
    <div class="topbar-title">
      <strong>{{ bootstrap?.application.displayName || '集团智慧服务' }}</strong>
      <span><i />服务在线</span>
    </div>
    <div class="topbar-context"><Bot :size="14" /><span>当前：</span>{{ activeAgent.name }}</div>
    <q-btn
      flat
      round
      dense
      class="dark-toggle"
      :aria-label="dark ? '切换到浅色模式' : '切换到深色模式'"
      @click="$emit('toggleDark')"
    >
      <Moon v-if="!dark" :size="17" />
      <Sun v-else :size="17" />
    </q-btn>
  </header>
</template>
