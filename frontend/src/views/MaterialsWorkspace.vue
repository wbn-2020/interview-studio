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
  <header class="page-heading"><div><p class="section-label">准备 / 附件资料</p><h1>让资料成为可追溯的依据</h1><p class="muted">保留原件，显式处理，选择原文，再引用到项目与事实。</p></div><NButton type="primary" :disabled="!status.data.value?.enabled" @click="create">＋ 上传新附件</NButton></header>
  <div class="material-overview"><p class="field-help">原件、解析与摘录分别留痕，来源始终可找回。</p><div v-if="status.data.value" class="material-capacity"><label for="material-quota">已预留 {{ bytesLabel(status.data.value.usedBytes) }} / {{ bytesLabel(status.data.value.quotaBytes) }}</label><meter id="material-quota" :min="0" :max="Math.max(status.data.value.quotaBytes, 1)" :value="Math.min(status.data.value.usedBytes, Math.max(status.data.value.quotaBytes, 1))" /><small>包括原件尝试和解析输出的保留额度</small></div></div>
  <p v-if="status.error.value" class="notice error" role="alert">{{ errorText(status.error.value) }}<NButton text @click="status.refetch()">重试资料状态</NButton></p><p v-if="status.data.value && !status.data.value.enabled" class="notice" role="status">服务器尚未启用附件资料。需配置私有存储目录与容量后启用；当前页面不会把文件写到其他位置。</p><p v-if="status.data.value?.enabled && !status.data.value.parserEnabled" class="notice">服务器尚未启用解析。可以上传原件和保存手工摘录，文字提取与 PNG 预览暂不可用。</p>
  <div class="materials-workspace"><aside class="materials-catalog" aria-label="附件目录"><form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" :disabled="!status.data.value?.enabled" placeholder="筛选文件名" aria-label="筛选附件文件名" /><NButton attr-type="submit" :disabled="!status.data.value?.enabled">查找</NButton></form><p class="field-help">只筛选文件名，不扫描原件或未保存输入。</p><p v-if="status.data.value?.enabled && attachments.isPending.value" class="loading-block" role="status">正在读取附件…</p><p v-if="attachments.error.value" class="notice error">{{ errorText(attachments.error.value) }}<NButton text @click="attachments.refetch()">重试目录</NButton></p><div class="materials-list"><button v-for="item in attachments.data.value?.items" :key="item.id" :class="{ selected: selectedId === item.id }" :aria-current="selectedId === item.id ? 'true' : undefined" @click="open(item.id)"><strong>{{ item.fileName }}</strong><span>{{ materialKindLabels[item.kind] }} · {{ bytesLabel(item.byteSize) }}</span><small>{{ attachmentLabels[item.status] }}</small></button></div><p v-if="attachments.data.value && !attachments.data.value.items.length" class="materials-empty">{{ search ? '没有匹配文件名。调整关键词后再试。' : '还没有附件。先保留一份原始资料，再选择需要引用的内容。' }}</p><div v-if="cursors.length || attachments.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!attachments.data.value?.nextCursor" @click="next">下一页</NButton></div></aside><MaterialDetail v-if="selectedId" :key="selectedId" :id="selectedId" :status="status.data.value" @changed="refresh" @close="create" /><MaterialUpload v-else :status="status.data.value" @changed="refresh" @ready="uploaded" /></div>
  <p class="workspace-footnote">原件、上传尝试、解析执行与来源摘录分别留痕。默认不外发模型，引用不等于核实，图片不执行 OCR。</p>
</template>

<style scoped>
.material-overview, .materials-workspace { max-width: 1280px; margin-left: auto; margin-right: auto; }
.material-overview { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 18px; margin-bottom: 23px; }
.material-capacity { display: grid; grid-template-columns: auto 140px; align-items: center; gap: 6px 13px; font-size: 12px; }
.material-capacity small { grid-column: 1 / -1; text-align: right; color: var(--muted); font-size: 11px; }
.material-capacity meter { width: 140px; height: 10px; accent-color: var(--brand); }
.materials-workspace { display: grid; grid-template-columns: 265px minmax(0, 1fr); min-height: 600px; align-items: start; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.materials-catalog { align-self: stretch; min-width: 0; padding: 18px 13px; border-right: 1px solid var(--line); background: var(--surface-soft); border-radius: 8px 0 0 8px; }
.materials-catalog > .field-help { margin: 8px 0 13px; }
.materials-list { display: grid; gap: 3px; }
.materials-list > button { display: grid; gap: 5px; width: 100%; padding: 12px 9px; text-align: left; border-radius: 5px; }
.materials-list > button:hover, .materials-list > button.selected { background: var(--surface-deep); }
.materials-list strong { overflow-wrap: anywhere; font-size: 13px; }
.materials-list span, .materials-list small { color: var(--muted); font-size: 11px; }
.materials-empty { padding: 28px 6px; font-size: 12px; color: var(--muted); }
@media (max-width: 1000px) { .materials-workspace { grid-template-columns: 220px minmax(0, 1fr); } }
@media (max-width: 700px) { .materials-workspace { grid-template-columns: 1fr; } .materials-catalog { border-right: 0; border-bottom: 1px solid var(--line); border-radius: 8px 8px 0 0; } .materials-list { max-height: 250px; overflow: auto; } .material-capacity { grid-template-columns: 1fr; } .material-capacity small { text-align: left; } }
</style>
