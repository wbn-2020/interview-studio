<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput } from 'naive-ui'
import { errorText, read } from '../api/client'
import type { Attachment, AttachmentPage, MaterialStatus } from '../api/material'
import { attachmentLabels, bytesLabel, materialKindLabels } from '../api/material'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'
import RIcon from '../replica/RIcon.vue'
const MaterialUpload = defineAsyncComponent(() => import('../components/material/MaterialUpload.vue'))
const MaterialDetail = defineAsyncComponent(() => import('../components/material/MaterialDetail.vue'))
const store = useWorkspaceStore()
const route = useRoute()
const router = useRouter()
const selectedId = computed(() => typeof route.query.attachment === 'string' ? route.query.attachment : undefined)
const input = ref('')
const search = ref('')
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const status = useQuery({ queryKey: computed(() => ['material', store.sessionEpoch, 'status']), enabled: computed(() => store.sessionActive), queryFn: () => read<MaterialStatus>('/materials/status') })
const attachments = useQuery({ queryKey: computed(() => ['material', store.sessionEpoch, 'attachments', search.value, cursor.value]), enabled: computed(() => store.sessionActive && Boolean(status.data.value?.enabled)), queryFn: () => read<AttachmentPage>(`/materials/attachments?limit=20&q=${encodeURIComponent(search.value)}${cursor.value ? `&cursor=${encodeURIComponent(cursor.value)}` : ''}`) })
function open(id: string) { void router.push({ name: 'materials', query: { attachment: id } }) }
function create() { void router.push({ name: 'materials' }) }
function uploaded(value: Attachment) { open(value.id) }
function refresh() { void queryClient.invalidateQueries({ queryKey: ['material'] }) }
function submit() { search.value = input.value.trim(); cursor.value = undefined; cursors.value = [] }
function next() { if (attachments.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = attachments.data.value.nextCursor } }
</script>

<template>
  <header class="page-heading"><div><h1>附件资料</h1><p class="muted">保留原始文件，把需要引用的内容整理到准备材料中。</p></div><div class="row-actions"><RouterLink class="text-link" :to="{ name: 'knowledge', query: { view: 'list' } }">知识笔记</RouterLink><NButton type="primary" :disabled="!status.data.value?.enabled" @click="create"><RIcon name="upload" :size="18" />上传资料</NButton></div></header>
  <p v-if="status.error.value" class="notice error" role="alert">{{ errorText(status.error.value) }}<NButton text @click="status.refetch()">重试资料状态</NButton></p><p v-if="status.data.value && !status.data.value.enabled" class="notice" role="status">服务器尚未启用附件资料。需配置私有存储目录与容量后启用；当前页面不会把文件写到其他位置。</p><p v-if="status.data.value?.enabled && !status.data.value.parserEnabled" class="notice">服务器尚未启用解析。可以上传原件和保存手工摘录，文字提取与 PNG 预览暂不可用。</p>
  <div class="materials-workspace"><aside class="materials-catalog" aria-label="附件目录"><h2>文件库</h2><form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" :disabled="!status.data.value?.enabled" placeholder="搜索文件..." aria-label="筛选附件文件名" /><NButton attr-type="submit" :disabled="!status.data.value?.enabled" aria-label="搜索文件"><RIcon name="search" :size="21" /></NButton></form><p class="field-help">只筛选文件名，不扫描原件或未保存输入。</p><p v-if="status.data.value?.enabled && attachments.isPending.value" class="loading-block" role="status">正在读取附件…</p><p v-if="attachments.error.value" class="notice error">{{ errorText(attachments.error.value) }}<NButton text @click="attachments.refetch()">重试目录</NButton></p><div class="materials-list"><button v-for="item in attachments.data.value?.items" :key="item.id" :class="{ selected: selectedId === item.id }" :aria-current="selectedId === item.id ? 'true' : undefined" @click="open(item.id)"><RIcon :name="item.kind === 'PDF' ? 'file-text' : 'file'" :size="30" /><div><strong>{{ item.fileName }}</strong><span>{{ materialKindLabels[item.kind] }} · {{ bytesLabel(item.byteSize) }}</span><small>{{ attachmentLabels[item.status] }}</small></div></button></div><p v-if="attachments.data.value && !attachments.data.value.items.length" class="materials-empty">{{ search ? '没有匹配文件名。调整关键词后再试。' : '还没有附件。先保留一份原始资料，再选择需要引用的内容。' }}</p><div v-if="cursors.length || attachments.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!attachments.data.value?.nextCursor" @click="next">下一页</NButton></div></aside><MaterialDetail v-if="selectedId" :key="selectedId" :id="selectedId" :status="status.data.value" @changed="refresh" @close="create" /><MaterialUpload v-else :status="status.data.value" @changed="refresh" @ready="uploaded" /></div>
  <details class="material-overview"><summary>容量与存储</summary><p class="field-help">原件、解析与摘录分别留痕，来源始终可找回。</p><div v-if="status.data.value" class="material-capacity"><label for="material-quota">已预留 {{ bytesLabel(status.data.value.usedBytes) }} / {{ bytesLabel(status.data.value.quotaBytes) }}</label><meter id="material-quota" :min="0" :max="Math.max(status.data.value.quotaBytes, 1)" :value="Math.min(status.data.value.usedBytes, Math.max(status.data.value.quotaBytes, 1))" /><small>包括原件尝试和解析输出的保留额度</small></div></details>
  <p class="workspace-footnote">原件、上传尝试、解析执行与来源摘录分别留痕。默认不外发模型，引用不等于核实，图片不执行 OCR。</p>
</template>

<style scoped>
.material-overview, .materials-workspace { max-width: 1280px; margin-left: auto; margin-right: auto; }
.material-overview { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 18px; margin-bottom: 23px; }
.material-capacity { display: grid; grid-template-columns: auto 140px; align-items: center; gap: 6px 13px; font-size: 12px; }
.material-capacity small { grid-column: 1 / -1; text-align: right; color: var(--muted); font-size: 11px; }
.material-capacity meter { width: 140px; height: 10px; accent-color: var(--brand); }
.materials-workspace { display: grid; grid-template-columns: 248px minmax(0, 1fr); gap: 28px; align-items: start; max-width: none; }
.materials-catalog { min-width: 0; }.materials-catalog h2 { font-size: 16px; margin-bottom: 16px; }
.materials-catalog > .field-help { margin: 8px 0 13px; }
.materials-list { display: grid; max-height: 560px; overflow-y: auto; }
.materials-list > button { display: flex; align-items: start; gap: 12px; width: 100%; padding: 16px 8px; text-align: left; border-bottom: 1px solid var(--studio-divider); }.materials-list > button > svg { flex: none; width: 18px; height: 22px; color: var(--muted); }.materials-list > button > div { min-width: 0; display: grid; gap: 5px; }
.materials-list > button:hover { background: var(--studio-pane); }.materials-list > button.selected { background: var(--brand-soft); }
.materials-list strong { overflow-wrap: anywhere; font-size: 14px; font-weight: 500; }
.materials-list span, .materials-list small { color: var(--muted); font-size: 12px; }
.materials-empty { padding: 28px 6px; font-size: 12px; color: var(--muted); }
.materials-workspace > :deep(.material-upload), .materials-workspace > :deep(.material-detail) { min-width: 0; padding: 28px; border: 1px solid var(--studio-divider); border-radius: 10px; background: var(--studio-pane); }
@container workspace (max-width: 720px) { .materials-workspace { grid-template-columns: minmax(0,1fr); gap: 24px; }.materials-list { max-height: 230px; }.materials-workspace > :deep(.material-upload), .materials-workspace > :deep(.material-detail) { padding: 22px 18px; } }
@media(max-width:700px) { .material-capacity { grid-template-columns: 1fr; }.material-capacity small { text-align: left; } }
</style>
