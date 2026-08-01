<script setup lang="ts">
withDefaults(defineProps<{ size?: number }>(), {
  size: 16,
})
</script>

<template>
  <img
    class="app-icon"
    src="/agent-pro-icon.png"
    alt=""
    :width="size"
    :height="size"
    aria-hidden="true"
    draggable="false"
  >
</template>
