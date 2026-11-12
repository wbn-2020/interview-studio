<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <nav class="memory-navigation" aria-label="回忆工作区">
    <RouterLink :to="{ name: 'memory-cards' }">卡片与来源</RouterLink>
    <RouterLink :to="{ name: 'memory-recall' }">到期回忆</RouterLink>
    <RouterLink :to="{ name: 'memory-cram' }">冲刺练习</RouterLink>
  </nav>
</template>

<style scoped>
.memory-navigation { display: flex; flex-wrap: wrap; gap: 6px 22px; max-width: 1280px; margin: 0 auto 20px; border-bottom: 1px solid var(--line); }
.memory-navigation a { padding: 10px 0; color: var(--muted); border-bottom: 2px solid transparent; font-size: 13px; text-decoration: none; }
.memory-navigation a.router-link-active { color: var(--brand); border-bottom-color: var(--brand); }
.memory-navigation a:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }
</style>
