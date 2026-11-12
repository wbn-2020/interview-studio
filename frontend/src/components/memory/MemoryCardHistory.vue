<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { MemoryReference, MemoryVersion, MemoryVersionDetail, MemoryVersionPage } from '../../api/memory'
import { memoryIdValid, memoryKindLabels, memoryPromptSignature, memoryReferenceValid, memoryVersionDetailValid } from '../../api/memory'
import { questionTextLength } from '../../api/bank'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MarkdownPreview from '../MarkdownPreview.vue'
import MemorySources from './MemorySources.vue'
import AiCardDraftOrigins from '../ai/AiCardDraftOrigins.vue'

const props = defineProps<{ id: string; currentVersion: MemoryVersion; versionId?: string; disabled?: boolean }>()
const emit = defineEmits<{ select: [versionId: string] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const active = ref(true)
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked && memoryIdValid(props.id))
const selectedId = computed(() => props.versionId ?? props.currentVersion.id)
const before = ref<number>(), cursors = ref<(number | undefined)[]>([])
const currentPage = shallowRef<MemoryVersionPage>(), current = shallowRef<MemoryVersionDetail>()
const reference = shallowRef<MemoryReference>(), referenceEpoch = ref(-1), message = ref('')
const requested = shallowRef<{ cardId: string; versionId: string; versionNumber: number }>()
const state = reactive({ dirty: false, locked: false }), stateKey = `memory-history-reference-${props.id}`
const listKey = computed(() => ['memory', store.sessionEpoch, 'versions', props.id, before.value])
const selectedKey = computed(() => ['memory', store.sessionEpoch, 'version', props.id, selectedId.value])
const visibleReference = computed(() => readable.value && referenceEpoch.value === store.sessionEpoch && current.value
  && reference.value?.cardId === props.id && reference.value.versionId === current.value.version.id ? reference.value : undefined)
const canRetry = computed(() => readable.value && !writer.busy.value && Boolean(writer.pending.value))
let alive = true, generation = 0, listGeneration = 0, accessGeneration = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0

watch([readable, () => store.sessionEpoch, () => props.id, selectedId], () => {
  generation++; current.value = undefined; reference.value = undefined; referenceEpoch.value = -1; message.value = ''
  if (!writer.locked.value) { writer.error.value = undefined; requested.value = undefined }
}, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.id], () => { accessGeneration++ }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.id, before], () => { listGeneration++; currentPage.value = undefined }, { flush: 'sync' })
const list = useQuery({ queryKey: listKey, enabled: readable, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = listGeneration, id = props.id, cursor = before.value
  const value = await read<MemoryVersionPage>(`/memory/cards/${id}/versions?limit=10${cursor === undefined ? '' : `&before=${cursor}`}`)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== listGeneration || id !== props.id || cursor !== before.value) throw contextChanged()
  if (!pageValid(value)) throw mismatch('历史版本列表格式异常，请重新读取。')
  return value
} })
const selected = useQuery({ queryKey: selectedKey, enabled: computed(() => readable.value && memoryIdValid(selectedId.value)), queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, id = props.id, versionId = selectedId.value
  const value = await read<MemoryVersionDetail>(`/memory/cards/${id}/versions/${versionId}`)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation || id !== props.id || versionId !== selectedId.value) throw contextChanged()
  if (!detailValid(value)) throw mismatch('历史题面与所选版本不一致，请重新读取。')
  return value
} })
watch([list.data, list.dataUpdatedAt, listKey, readable], () => {
  if (!alive || !readable.value) return
  const value = queryClient.getQueryData<MemoryVersionPage>(listKey.value)
  if (value && pageValid(value)) currentPage.value = value
}, { immediate: true })
watch([selected.data, selected.dataUpdatedAt, selectedKey, readable], () => {
  if (!alive || !readable.value) return
  const value = queryClient.getQueryData<MemoryVersionDetail>(selectedKey.value)
  if (value && detailValid(value)) current.value = value
}, { immediate: true })
watch(writer.locked, value => { state.locked = value; store.changes.set(stateKey, state) }, { immediate: true, flush: 'sync' })
watch([writer.pending, writer.busy], ([pending, busy]) => { if (!pending && !busy) requested.value = undefined })
watch([() => store.sessionEpoch, () => props.id], () => { before.value = undefined; cursors.value = [] })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; listGeneration++; reference.value = undefined; if (store.changes.get(stateKey) === state) store.changes.delete(stateKey) })

function contextChanged() { return new ApiError(0, 'MEMORY_HISTORY_CHANGED', '版本或访问条件已变化，请重新读取。') }
function mismatch(text: string) { return new ApiError(502, 'MEMORY_HISTORY_INVALID', text) }
function pageValid(value: MemoryVersionPage): boolean {
  try {
    const fields = ['id', 'number', 'kind', 'title', 'createdAt']
    return Boolean(value && Object.keys(value).length === 2 && Object.hasOwn(value, 'items') && Object.hasOwn(value, 'nextBefore')
      && Array.isArray(value.items) && value.items.length <= 10 && value.items.every((item, index) => item && Object.keys(item).length === fields.length && fields.every(field => Object.hasOwn(item, field))
        && memoryIdValid(item.id) && Object.hasOwn(memoryKindLabels, item.kind) && Number.isSafeInteger(item.number) && item.number >= 1 && item.number <= 1000
        && (before.value === undefined || item.number < before.value) && (!index || item.number < value.items[index - 1]!.number)
        && typeof item.title === 'string' && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(item.title) && questionTextLength(item.title) <= 200 && !/[\u0000\uD800-\uDFFF]/u.test(item.title)
        && typeof item.createdAt === 'string' && Number.isFinite(Date.parse(item.createdAt)))
      && new Set(value.items.map(item => item.id)).size === value.items.length
      && (value.nextBefore === null || (value.items.length === 10 && Number.isSafeInteger(value.nextBefore) && value.nextBefore >= 1 && value.items.at(-1)?.number === value.nextBefore)))
  } catch { return false }
}
function detailValid(value: MemoryVersionDetail): boolean {
  if (!memoryVersionDetailValid(value, selectedId.value)) return false
  return value.version.id !== props.currentVersion.id || (value.version.number === props.currentVersion.number
    && value.version.createdAt === props.currentVersion.createdAt && memoryPromptSignature(value.version.content) === memoryPromptSignature(props.currentVersion.content))
}
function choose(versionId: string) { if (readable.value && !writer.locked.value && memoryIdValid(versionId)) emit('select', versionId) }
function older() { if (readable.value && !list.isFetching.value && !list.error.value && currentPage.value?.nextBefore != null) { cursors.value.push(before.value); before.value = currentPage.value.nextBefore } }
function newer() { if (readable.value && !list.isFetching.value && cursors.value.length) before.value = cursors.value.pop() }
function showReference() {
  const version = current.value?.version
  if (!readable.value || writer.locked.value || selected.isFetching.value || selected.error.value || !version || version.id !== selectedId.value) return
  const identity = { cardId: props.id, versionId: version.id, versionNumber: version.number }
  requested.value = identity; commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; message.value = ''
  let original: typeof writer.pending.value
  void writer.run<MemoryReference>(`/memory/cards/${identity.cardId}/versions/${identity.versionId}/reference`, 'POST', {}, value => {
    if (!alive) return
    if (!memoryReferenceValid(value, identity.cardId, identity.versionId, identity.versionNumber)) {
      writer.pending.value = original; writer.error.value = mismatch('参考回执与请求的确切版本不一致，原请求已保留，请用原请求重新核对。'); return
    }
    // 参考不进入普通查询缓存。访问期间发生变化时丢弃正文，恢复权限后仍须用原键重新读取。
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) {
      writer.pending.value = original; writer.error.value = contextChanged(); return
    }
    // 背景刷新可能推进当前内容版；已确认的旧参考可丢弃，不能因无法切回版本而卡住原键恢复。
    if (props.id !== identity.cardId || selectedId.value !== identity.versionId || current.value?.version.id !== identity.versionId) {
      requested.value = undefined; message.value = `v${identity.versionNumber} 的参考请求已确认；当前选择已变化，本次未显示旧版本正文。`; return
    }
    reference.value = value; referenceEpoch.value = store.sessionEpoch; requested.value = undefined
  })
  original = writer.pending.value
}
function retryReference() { if (canRetry.value) { commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; void writer.retry() } }
function hideReference() { reference.value = undefined; referenceEpoch.value = -1 }
function displayTime(value: string) { return new Date(value).toLocaleString('zh-CN') }
</script>

<template>
  <section class="memory-history" aria-label="回忆卡历史版本">
    <p class="field-help">每一版保留当时的题面、个人参考、来源版本与概念关联。阅读历史不会替换编辑中的输入。</p>
    <p v-if="!readable" class="notice" role="status">资料访问恢复后可继续阅读历史；已显示的参考暂时清空，原请求继续保留。</p>
    <template v-else>
      <div class="history-heading"><h3>内容版本</h3><NButton text size="small" :disabled="list.isFetching.value" @click="list.refetch()">刷新版本列表</NButton></div>
      <p v-if="list.isPending.value" role="status">正在读取历史版本…</p>
      <p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text :disabled="list.isFetching.value" @click="list.refetch()">重新读取列表</NButton></p>
      <div class="history-list"><button v-for="version in currentPage?.items" :key="version.id" class="history-choice" :disabled="writer.locked.value" :aria-current="version.id === selectedId ? 'true' : undefined" @click="choose(version.id)"><strong>v{{ version.number }} · {{ version.title }}</strong><small>{{ memoryKindLabels[version.kind] }} · {{ displayTime(version.createdAt) }}{{ version.id === currentVersion.id ? ' · 当前内容版' : '' }}</small></button></div>
      <p v-if="currentPage && !currentPage.items.length && !list.error.value" class="field-help">本页没有更早的版本。</p>
      <div class="row-actions"><NButton size="small" :disabled="!cursors.length || list.isFetching.value" @click="newer">较新版本</NButton><NButton size="small" :disabled="currentPage?.nextBefore == null || list.isFetching.value || !!list.error.value" @click="older">更早版本</NButton><NButton v-if="selectedId !== currentVersion.id" text size="small" :disabled="writer.locked.value" @click="choose(currentVersion.id)">查看当前内容版</NButton></div>
      <p v-if="!memoryIdValid(selectedId)" class="notice error" role="alert">历史版本地址无效，请从列表重新选择。</p>
      <p v-else-if="selected.isPending.value" role="status">正在读取所选题面与来源…</p>
      <p v-if="selected.error.value" class="notice error" role="alert">{{ errorText(selected.error.value) }}<NButton text :disabled="selected.isFetching.value" @click="selected.refetch()">重新读取版本</NButton></p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<span v-if="requested">原请求对应 v{{ requested.versionNumber }}。</span><NButton v-if="writer.pending.value" text :disabled="!canRetry" :loading="writer.busy.value" @click="retryReference">用原请求重读参考</NButton></p>
      <article v-if="current && current.version.id === selectedId" class="history-content">
        <header><p class="field-help">{{ memoryKindLabels[current.version.content.kind] }} · v{{ current.version.number }} · {{ displayTime(current.version.createdAt) }}</p><h3>{{ current.version.content.title }}</h3></header>
        <section class="historical-prompt" aria-label="历史题面"><h4>题面</h4><MarkdownPreview :value="current.version.content.prompt" /></section>
        <section class="historical-reference" aria-label="历史个人参考">
          <div class="history-heading"><h4>个人参考</h4><NButton v-if="visibleReference" text size="small" @click="hideReference">收起参考</NButton></div>
          <MarkdownPreview v-if="visibleReference" :value="visibleReference.reference" />
          <template v-else><p class="field-help">参考单独读取，只对应这一确切版本。查看不会产生正式复习、评分或排程记录。</p><NButton size="small" :disabled="writer.locked.value || selected.isFetching.value || !!selected.error.value" :loading="writer.busy.value" @click="showReference">查看 v{{ current.version.number }} 参考</NButton></template>
        </section>
        <MemorySources :references="current.sourceReferences" :concepts="current.relatedConcepts" :disabled="!readable" />
        <AiCardDraftOrigins :card-id="id" :version-id="current.version.id" :version-number="current.version.number" :disabled="!readable || writer.locked.value || selected.isFetching.value || !!selected.error.value" />
      </article>
    </template>
  </section>
</template>

<style scoped>
.memory-history, .history-list, .history-content { display: grid; gap: 15px; min-width: 0; }.history-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.history-heading h3, h4 { margin: 0; font-size: 14px; }.history-list { gap: 4px; }
.history-choice { display: grid; gap: 6px; padding: 11px; text-align: left; border: 1px solid transparent; border-radius: 5px; overflow-wrap: anywhere; min-width: 0; }.history-choice:hover:not(:disabled), .history-choice[aria-current=true] { background: var(--surface-soft); }.history-choice[aria-current=true] { border-color: var(--brand); }.history-choice:disabled { opacity: .65; cursor: not-allowed; }.history-choice:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }.history-choice strong { font-size: 13px; }.history-choice small { font-size: 12px; color: var(--muted); }
.history-content { padding-top: 20px; border-top: 1px solid var(--line); gap: 24px; }.history-content > header { display: grid; gap: 8px; }.history-content h3 { margin: 0; overflow-wrap: anywhere; text-wrap: balance; }.historical-prompt, .historical-reference { display: grid; gap: 12px; min-width: 0; }.historical-reference { padding-top: 20px; border-top: 1px solid var(--line); }.historical-reference > .n-button { justify-self: start; }.historical-prompt :deep(.markdown-preview), .historical-reference :deep(.markdown-preview) { max-width: 75ch; overflow-wrap: anywhere; }.row-actions { display: flex; flex-wrap: wrap; gap: 10px; }
</style>
