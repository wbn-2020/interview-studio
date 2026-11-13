<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
const props = defineProps<{ area: 'prepare' | 'practice' | 'review'; material?: boolean; catalog?: boolean }>()
const route = useRoute()
const tabs = computed(() => props.area === 'prepare' ? props.material ? [
  { label: '事实', to: '/prepare?kind=FACT', active: route.name === 'prepare' && route.query.kind !== 'EXPRESSION' },
  { label: '表达素材', to: '/prepare?kind=EXPRESSION', active: route.query.kind === 'EXPRESSION' },
  { label: '来源', to: '/prepare/materials', active: route.name === 'materials' },
] : [
  { label: '目标', to: '/prepare', active: route.name === 'prepare' },
  { label: '材料', to: '/prepare?kind=PROJECT', active: route.name === 'materials' },
  { label: '任务', to: '/prepare/tasks', active: route.name === 'preparation-tasks' },
  { label: '安排', to: '/prepare/schedule', active: route.name === 'schedule' },
] : props.area === 'practice' && props.catalog ? [
  { label: '题目', to: '/practice/questions', active: route.name === 'bank' },
  { label: '题单', to: '/practice/collections', active: route.name === 'collections' && route.query.kind !== 'ROUTE' },
  { label: '路线', to: '/practice/collections?kind=ROUTE', active: route.name === 'collections' && route.query.kind === 'ROUTE' },
] : props.area === 'practice' ? [
  { label: '题库', to: '/practice/questions', active: route.name === 'bank' },
  { label: '题单与路线', to: '/practice/collections', active: route.name === 'collections' },
  { label: '文字训练', to: '/practice/sessions', active: route.name === 'training' },
  { label: '主动回忆', to: '/knowledge/recall', active: route.name === 'memory-recall' },
] : [
  { label: '回答与反馈', to: '/review', active: route.name === 'review' },
  { label: '复练对照', to: '/review/repractice', active: route.name === 'repractice' },
  { label: '面后回忆', to: '/review/recollections', active: route.name === 'recollections' },
])
</script>
<template><nav class="studio-section-tabs" :aria-label="`${area === 'prepare' ? '准备' : area === 'practice' ? '训练' : '复盘'}工作区`"><RouterLink v-for="tab in tabs" :key="tab.to" :to="tab.to" :class="{ active: tab.active }">{{ tab.label }}</RouterLink></nav></template>
<style scoped>
.studio-section-tabs { display: flex; gap: 36px; margin: -4px 0 24px; padding: 0 5px; border-bottom: 1px solid var(--line); overflow-x: auto; }.studio-section-tabs a { position: relative; padding: 11px 18px 19px; min-height: 54px; font-size: 24px; color: var(--muted); white-space: nowrap; }.studio-section-tabs a.active { color: #f8ead7; font-weight: 600; }.studio-section-tabs a.active::after { position: absolute; content: ""; height: 3px; left: 4px; right: 4px; bottom: 0; border-radius: 3px; background: #edaf75; box-shadow: 0 0 9px #e6a56860; }
@media (max-width: 900px) { .studio-section-tabs { gap: 15px; }.studio-section-tabs a { font-size: 19px; padding-inline: 12px; } }
@media (max-width: 600px) { .studio-section-tabs { margin-bottom: 17px; gap: 10px; }.studio-section-tabs a { font-size: 17px; padding: 8px 11px 13px; min-height: 42px; } }
</style>
