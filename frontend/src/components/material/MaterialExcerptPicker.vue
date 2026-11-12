<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { AttachmentPage, AttachmentSummary, MaterialExcerpt, MaterialExcerptPage } from '../../api/material'
import { useWorkspaceStore } from '../../state/workspace'
import MaterialExcerptReadout from './MaterialExcerptReadout.vue'
const props = defineProps<{ disabled?: boolean; excludeIds?: string[]; searchExcerpts?: boolean; forAi?: boolean }>()
const emit = defineEmits<{ choose: [value: MaterialExcerpt] }>()
const store = useWorkspaceStore()
const input = ref('')
const search = ref('')
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const selected = ref<AttachmentSummary>()
const excerptCursor = ref<string>()
const excerptCursors = ref<(string | undefined)[]>([])
const excerptInput = ref('')
const excerptSearch = ref('')
const readout = ref<string>()
const readable = computed(() => store.sessionActive && !props.disabled)
const searchPending = computed(() => Boolean(props.searchExcerpts && excerptInput.value.trim() !== excerptSearch.value))
const attachments = useQuery({ queryKey: computed(() => ['material', store.sessionEpoch, 'picker', search.value, cursor.value]), enabled: readable, queryFn: () => read<AttachmentPage>(`/materials/attachments?limit=20&q=${encodeURIComponent(search.value)}${cursor.value ? `&cursor=${encodeURIComponent(cursor.value)}` : ''}`) })
const excerpts = useQuery({ queryKey: computed(() => ['material', store.sessionEpoch, 'excerpt-picker', selected.value?.id, Boolean(props.forAi), excerptSearch.value, excerptCursor.value]), enabled: computed(() => readable.value && Boolean(selected.value) && !searchPending.value), queryFn: () => read<MaterialExcerptPage>(`/materials/attachments/${selected.value!.id}/excerpts?limit=20&status=ACTIVE&aiUse=${props.forAi ? 'ALLOWED' : 'ALL'}${excerptSearch.value ? `&q=${encodeURIComponent(excerptSearch.value)}` : ''}${excerptCursor.value ? `&cursor=${encodeURIComponent(excerptCursor.value)}` : ''}`) })
watch(excerptInput, () => { excerptCursor.value = undefined; excerptCursors.value = []; readout.value = undefined }, { flush: 'sync' })
watch(() => props.forAi, () => { excerptCursor.value = undefined; excerptCursors.value = []; readout.value = undefined }, { flush: 'sync' })
watch([() => store.sessionEpoch, () => props.disabled], () => { readout.value = undefined })
function submit() { if (!readable.value) return; search.value = input.value.trim(); cursor.value = undefined; cursors.value = [] }
function searchSaved() { if (!readable.value || !props.searchExcerpts) return; excerptSearch.value = excerptInput.value.trim(); excerptCursor.value = undefined; excerptCursors.value = []; readout.value = undefined }
function select(value: AttachmentSummary) { if (!readable.value || value.status !== 'READY') return; selected.value = value; excerptInput.value = ''; excerptSearch.value = ''; excerptCursor.value = undefined; excerptCursors.value = []; readout.value = undefined }
function choose(value: MaterialExcerpt) { if (readable.value && !searchPending.value && !excerpts.isFetching.value && excerpts.data.value?.items.includes(value) && value.attachmentId === selected.value?.id && !props.excludeIds?.includes(value.id)) emit('choose', value) }
function nextAttachment() { if (readable.value && attachments.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = attachments.data.value.nextCursor } }
function nextExcerpt() { if (readable.value && !searchPending.value && excerpts.data.value?.nextCursor) { excerptCursors.value.push(excerptCursor.value); excerptCursor.value = excerpts.data.value.nextCursor } }
</script>

<template>
  <div class="material-excerpt-picker">
    <p class="field-help">选择已经保存的摘录；加入来源只保存摘录 ID，不改写原文，也不自动核实项目事实。</p>
    <p class="field-help">{{ forAi ? '本次只列出有效且允许 AI 用途的摘录；选择不等于外发授权，服务器仍会在预览和提交时核对最新状态与修订。' : '本地引用只列出有效摘录；已撤回 AI 用途的有效摘录仍可用于本地依据，AI 使用须另行核对。' }}</p>
    <template v-if="!selected"><form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :disabled="!readable" :maxlength="100" placeholder="查找附件文件名" aria-label="查找摘录来源附件" /><NButton attr-type="submit" :disabled="!readable">查找</NButton></form><p v-if="attachments.error.value" class="notice error" role="alert">{{ errorText(attachments.error.value) }}<NButton text :disabled="!readable" @click="attachments.refetch()">重试</NButton></p><p v-if="attachments.isPending.value" class="field-help" role="status">正在读取附件…</p><div class="excerpt-picker-results"><button v-for="item in attachments.data.value?.items" :key="item.id" :disabled="!readable || item.status !== 'READY'" @click="select(item)"><strong>{{ item.fileName }}</strong><small>{{ item.status === 'READY' ? '查看保存的摘录' : '尚未上传完成' }}</small></button></div><p v-if="attachments.data.value && !attachments.data.value.items.length" class="field-help">没有匹配附件。可以先在附件资料中上传并保存摘录。</p><div v-if="cursors.length || attachments.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!readable || !cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!readable || !attachments.data.value?.nextCursor" @click="nextAttachment">下一页</NButton></div></template>
    <template v-else>
      <div class="row-actions"><strong class="attachment-name">{{ selected.fileName }}</strong><NButton text size="small" :disabled="!readable" @click="selected = undefined; readout = undefined">换一个附件</NButton></div>
      <form v-if="searchExcerpts" class="title-search" @submit.prevent="searchSaved"><NInput v-model:value="excerptInput" :disabled="!readable" :maxlength="100" placeholder="查找摘录正文或定位关键词" aria-label="在当前附件的已保存摘录中查找" /><NButton attr-type="submit" :disabled="!readable">查找摘录</NButton></form>
      <p v-if="searchExcerpts" class="field-help">只查找这个附件已保存的摘录正文与定位说明，不搜索整份原件。关键词按字面匹配，命中结果需逐条选择。</p>
      <p v-if="searchPending" class="field-help" role="status">关键词已修改，请点击“查找摘录”或按回车更新结果。</p>
      <template v-else>
        <p v-if="excerpts.error.value" class="notice error" role="alert">{{ errorText(excerpts.error.value) }}<NButton text :disabled="!readable" @click="excerpts.refetch()">重试</NButton></p>
        <p v-if="excerpts.isPending.value" class="field-help" role="status">正在读取已保存摘录…</p>
        <div v-for="item in excerpts.data.value?.items" :key="item.id" class="excerpt-picker-row"><p>{{ item.text.slice(0, 160) }}{{ item.text.length > 160 ? '…' : '' }}</p><small>{{ item.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ item.locator }}</small><div class="row-actions"><NButton size="small" :disabled="!readable || excerpts.isFetching.value || excludeIds?.includes(item.id)" @click="choose(item)">{{ excludeIds?.includes(item.id) ? '已引用' : '引用此摘录' }}</NButton><NButton text size="small" :disabled="!readable" @click="readout = readout === item.id ? undefined : item.id">{{ readout === item.id ? '收起原文' : '查看原文' }}</NButton></div><MaterialExcerptReadout v-if="readable && readout === item.id" :id="item.id" /></div>
        <p v-if="excerpts.data.value && !excerpts.data.value.items.length" class="field-help" role="status">{{ excerptSearch ? '当前附件的已保存摘录中没有命中。可以换关键词、清空关键词查看已有摘录，或选择其他附件。' : '这个附件还没有保存摘录。请在附件详情中从解析片段或手工原文创建，或选择其他附件。' }}</p>
        <div v-if="excerptCursors.length || excerpts.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!readable || !excerptCursors.length" @click="excerptCursor = excerptCursors.pop()">上一页</NButton><NButton size="small" :disabled="!readable || !excerpts.data.value?.nextCursor" @click="nextExcerpt">下一页</NButton></div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.material-excerpt-picker { display: grid; gap: 11px; min-width: 0; padding: 13px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); font-size: 12px; }
.material-excerpt-picker .title-search { margin: 0; min-width: 0; }
.material-excerpt-picker .title-search > .n-input { min-width: 0; }
.attachment-name { overflow-wrap: anywhere; min-width: 0; }
.excerpt-picker-results { display: grid; gap: 3px; max-height: 240px; overflow: auto; }
.excerpt-picker-results > button { display: grid; gap: 4px; min-width: 0; padding: 9px; text-align: left; border-radius: 5px; }
.excerpt-picker-results > button:hover { background: var(--surface-deep); }
.excerpt-picker-results strong, .excerpt-picker-row p { overflow-wrap: anywhere; }
.excerpt-picker-results small, .excerpt-picker-row small { color: var(--muted); font-size: 11px; overflow-wrap: anywhere; }
.excerpt-picker-row { display: grid; gap: 7px; min-width: 0; padding: 12px 0; border-bottom: 1px solid var(--line); }
@media (max-width: 560px) { .material-excerpt-picker .title-search { flex-wrap: wrap; } }
</style>
