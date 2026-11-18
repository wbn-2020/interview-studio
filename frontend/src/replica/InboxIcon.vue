<script setup lang="ts">
import { computed } from 'vue'
import RIcon from './RIcon.vue'

const props = withDefaults(defineProps<{ name: string; size?: number; strokeWidth?: number; context?: 'entry' }>(), { size: 24, strokeWidth: 1.7 })
// 参考页的局部轮廓独立保存，避免改动正式收件箱也在使用的 RIcon 变体。
const calibratedPaths: Record<string, string[]> = {
  dumbbell: ['M6 6.5 2 10.5v5l4 4V6.5ZM17 6.5l3 4v5l-3 4V6.5Z', 'M6 12h11v2H6'],
  zap: ['M13.8 3c.1-.5-.5-.8-.8-.4L5.3 13.1c-.4.5 0 1.1.5 1.1H11L9.8 22c-.1.5.5.8.8.4l8.1-10.7c.4-.5.1-1.2-.5-1.2h-5.3Z'],
  'file-text': ['M4 2h10l5 5v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1Z', 'M14 2v6h5M7 6v7M7 15h4M7 18h9'],
  'file-entry': ['M5 3h10l5 5v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z', 'M15 3v6h5M8 8v9h6M8 12h5v4H8'],
  'cloud-upload': ['M7 21H6c-3 0-5-2-5-5 0-2.5 1.5-4.5 4-5C5 7 7.5 4 11 4c4 0 7 3 7 7 2.5.8 4.5 3 4.5 5.8 0 2.5-2 4.2-4.5 4.2h-2', 'M12 14v9.5m-3-6.5 3-3 3 3'],
  'external-link': ['M14 2h8v8M22 2 11 13', 'M10 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7'],
}
// 来源栏与列表中的文档轮廓有差异；按来源身份选择，筛选和排序不改变图标。
const paths = computed(() => calibratedPaths[props.name === 'file-text' && props.context === 'entry' ? 'file-entry' : props.name])
</script>

<template>
  <svg v-if="paths" :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" :stroke-width="strokeWidth" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" class="r-icon">
    <path v-for="(d, index) in paths" :key="index" :d="d" />
  </svg>
  <RIcon v-else :name="name" :size="size" :stroke-width="strokeWidth" variant="inbox" />
</template>
