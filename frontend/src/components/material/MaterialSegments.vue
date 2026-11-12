<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { MaterialSegment, MaterialSegmentPage } from '../../api/material'
import { useWorkspaceStore } from '../../state/workspace'
const props = defineProps<{ runId: string; disabled?: boolean }>()
const emit = defineEmits<{ choose: [segment: MaterialSegment, start: number, end: number] }>()
const store = useWorkspaceStore()
const after = ref(0)
const cursors = ref<number[]>([])
const ranges = reactive<Record<string, { start: number; end: number }>>({})
const page = useQuery({ queryKey: computed(() => ['material', store.sessionEpoch, 'segments', props.runId, after.value]), enabled: computed(() => store.sessionActive), queryFn: () => read<MaterialSegmentPage>(`/materials/runs/${props.runId}/segments?after=${after.value}&limit=20`) })
function sourceOffset(text: string, offset: number) {
  // textarea 将 CRLF／CR 规范为 LF，引用范围必须映射回不可变原文的 UTF-16 边界。
  let sourceIndex = 0
  for (let apiIndex = 0; apiIndex < offset && sourceIndex < text.length; apiIndex += 1) {
    sourceIndex += text[sourceIndex] === '\r' && text[sourceIndex + 1] === '\n' ? 2 : 1
  }
  return sourceIndex
}
function select(id: string, event: Event) {
  const segment = page.data.value?.items.find(item => item.id === id)
  if (props.disabled || !segment) return
  const input = event.target as HTMLTextAreaElement
  ranges[id] = { start: sourceOffset(segment.text, input.selectionStart), end: sourceOffset(segment.text, input.selectionEnd) }
}
function choose(value: MaterialSegment, whole = false) {
  if (props.disabled || !store.sessionActive) return
  const range = whole ? { start: 0, end: value.text.length } : ranges[value.id]
  if (range && range.end > range.start) emit('choose', value, range.start, range.end)
}
function next() { if (page.data.value?.nextCursor != null) { cursors.value.push(after.value); after.value = page.data.value.nextCursor } }
</script>

<template>
  <section class="material-segments" aria-label="解析文字片段"><h3>解析原文片段</h3><p class="field-help">在只读原文中选择范围后创建摘录。范围按 UTF-16 字符计数，保存时由服务器从原片段截取。</p><p v-if="page.error.value" class="notice error">{{ errorText(page.error.value) }}<NButton text @click="page.refetch()">重试</NButton></p><p v-if="page.isPending.value" class="field-help">正在读取片段…</p><article v-for="segment in page.data.value?.items" :key="segment.id"><header><strong>片段 {{ segment.ordinal }}</strong><span>{{ segment.locator }}</span></header><textarea :value="segment.text" readonly :disabled="disabled" :aria-label="`片段 ${segment.ordinal} 原文`" @select="select(segment.id, $event)" /><div class="row-actions"><NButton size="small" :disabled="disabled || !ranges[segment.id] || ranges[segment.id]!.end <= ranges[segment.id]!.start" @click="choose(segment)">摘录所选范围</NButton><NButton text size="small" :disabled="disabled || !segment.text" @click="choose(segment, true)">摘录整段</NButton><span v-if="ranges[segment.id]" class="field-help">[{{ ranges[segment.id]!.start }}, {{ ranges[segment.id]!.end }})</span></div></article><p v-if="page.data.value && !page.data.value.items.length" class="field-help">这次执行没有可用文字片段。扫描件可能没有文字层；当前不执行 OCR。</p><div v-if="cursors.length || page.data.value?.nextCursor != null" class="row-actions"><NButton size="small" :disabled="!cursors.length" @click="after = cursors.pop() ?? 0">上一页</NButton><NButton size="small" :disabled="page.data.value?.nextCursor == null" @click="next">下一页</NButton></div></section>
</template>

<style scoped>
.material-segments { display: grid; gap: 13px; }
.material-segments > article { display: grid; gap: 9px; padding: 14px 0; border-bottom: 1px solid var(--line); }
.material-segments article > header { display: flex; flex-wrap: wrap; gap: 8px 14px; font-size: 12px; }
.material-segments header > span { color: var(--muted); }
.material-segments textarea { width: 100%; min-height: 140px; max-height: 340px; resize: vertical; padding: 12px; border: 1px solid var(--line); border-radius: 5px; color: var(--ink); background: var(--surface-soft); font-family: inherit; font-size: 13px; line-height: 1.7; }
</style>
