<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { Attachment, MaterialExcerpt } from '../../api/material'
import { materialContentUrl } from '../../api/material'
import type { MaterialReferenceSource } from '../../api/materialReferences'
import { materialReferenceSourceValid } from '../../api/materialReferences'
import { useWorkspaceStore } from '../../state/workspace'
import MaterialReferences from './MaterialReferences.vue'
const props = defineProps<{ id: string }>()
const store = useWorkspaceStore()
const excerpt = useQuery({ queryKey: computed(() => ['material', store.sessionEpoch, 'excerpt', props.id]), enabled: computed(() => store.sessionActive), queryFn: () => read<MaterialExcerpt>(`/materials/excerpts/${props.id}`) })
const attachment = useQuery({ queryKey: computed(() => ['material', store.sessionEpoch, 'attachment', excerpt.data.value?.attachmentId]), enabled: computed(() => store.sessionActive && Boolean(excerpt.data.value?.attachmentId)), queryFn: () => read<Attachment>(`/materials/attachments/${excerpt.data.value!.attachmentId}`) })
const referenceSource = computed<MaterialReferenceSource | undefined>(() => {
  const value = excerpt.data.value
  if (!value || value.id !== props.id) return undefined
  const source = { excerptId: value.id, attachmentId: value.attachmentId, origin: value.origin,
    runId: value.runId, segmentId: value.segmentId, createdAt: value.createdAt }
  return materialReferenceSourceValid(source, props.id, value.attachmentId) ? source : undefined
})
</script>

<template>
  <article class="excerpt-readout" aria-label="保存的来源原文">
    <p v-if="excerpt.isPending.value" class="field-help" role="status">正在读取确切摘录…</p><p v-if="excerpt.error.value" class="notice error" role="alert">{{ errorText(excerpt.error.value) }}<NButton text @click="excerpt.refetch()">重试读取</NButton></p>
    <template v-if="excerpt.data.value"><header><strong>{{ excerpt.data.value.origin === 'PARSED' ? '解析片段摘录' : '用户手工摘录' }}</strong><span class="status-pill">保存后不可修改</span></header><p class="field-help">{{ attachment.data.value?.fileName || '来源附件' }} · {{ excerpt.data.value.locator }}</p><p v-if="excerpt.data.value.origin === 'MANUAL'" class="field-help">定位和原文由用户手工提供，不冒充自动解析，也不代表事实已核实。</p><blockquote>{{ excerpt.data.value.text }}</blockquote><dl><div><dt>附件</dt><dd>{{ excerpt.data.value.attachmentId }}</dd></div><div v-if="attachment.data.value"><dt>SHA-256</dt><dd>{{ attachment.data.value.sha256 }}</dd></div><div v-if="excerpt.data.value.runId"><dt>解析执行</dt><dd>{{ excerpt.data.value.runId }}</dd></div><div v-if="excerpt.data.value.segmentId"><dt>片段／范围</dt><dd>{{ excerpt.data.value.segmentId }} · UTF-16 [{{ excerpt.data.value.startOffset }}, {{ excerpt.data.value.endOffset }})</dd></div><div><dt>保存时间</dt><dd>{{ new Date(excerpt.data.value.createdAt).toLocaleString('zh-CN') }}</dd></div></dl><div class="row-actions"><RouterLink class="text-link" :to="{ name: 'materials', query: { attachment: excerpt.data.value.attachmentId, run: excerpt.data.value.runId ?? undefined, excerpt: excerpt.data.value.id } }">在附件资料中查看</RouterLink><a v-if="attachment.data.value?.status === 'READY'" class="text-link" :href="materialContentUrl(attachment.data.value.id)" download>下载鉴权原件</a></div><p v-if="attachment.error.value" class="notice error">{{ errorText(attachment.error.value) }}<NButton text @click="attachment.refetch()">重试附件信息</NButton></p><MaterialReferences v-if="referenceSource" :key="referenceSource.excerptId" :excerpt-id="id" :attachment-id="referenceSource.attachmentId" :source="referenceSource" /></template>
  </article>
</template>

<style scoped>
.excerpt-readout { display: grid; gap: 11px; min-width: 0; padding: 15px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
.excerpt-readout > header { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; font-size: 13px; }
.excerpt-readout blockquote { margin: 0; padding: 8px 0 8px 14px; border-left: 2px solid var(--brand); white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; max-height: 340px; overflow: auto; }
.excerpt-readout dl { display: grid; gap: 5px; margin: 0; font-size: 11px; }
.excerpt-readout dl > div { display: grid; grid-template-columns: 78px minmax(0, 1fr); gap: 10px; }
.excerpt-readout dt { color: var(--muted); }
.excerpt-readout dd { margin: 0; overflow-wrap: anywhere; }
</style>
