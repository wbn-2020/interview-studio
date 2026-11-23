<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { sectionFor, tabsFor } from '../../navigation/sections'
const route = useRoute()
const tabs = computed(() => tabsFor(route))
const nav = ref<HTMLElement>()
watch(() => route.fullPath, async () => {
  await nextTick()
  const active = nav.value?.querySelector<HTMLElement>('[aria-current="page"]')
  // 仅滚动横向标签条，不能把整个页面的编辑区跳走。
  if (active && nav.value) nav.value.scrollTo({ left: Math.max(0, active.offsetLeft - nav.value.offsetLeft - 16), behavior: 'instant' })
}, { immediate: true })
</script>
<template>
  <nav v-if="tabs.length" ref="nav" class="studio-section-tabs" :aria-label="`${sectionFor(route).label}工作区`">
    <RouterLink v-for="tab in tabs" :key="tab.label" :to="tab.to" :class="{ active: tab.active }" :aria-current="tab.active ? 'page' : undefined">{{ tab.label }}</RouterLink>
  </nav>
</template>
