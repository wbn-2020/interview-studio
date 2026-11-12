<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { MemoryKind, MemoryPage, MemorySummary } from '../../api/memory'
import { memoryIdValid, memoryKindLabels } from '../../api/memory'
import { memoryUtcMicros } from '../../api/memoryReview'
import { queryClient } from '../../api/queryClient'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ selected: MemorySummary[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:selected': [value: MemorySummary[]] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), active = ref(true)
const readable = computed(() => active.value && store.sessionActive && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const blocked = computed(() => !readable.value || props.disabled)
type Filters = { query: string; kind: MemoryKind | null }
const input = reactive<Filters>({ query: '', kind: null }), applied = ref<Filters>({ query: '', kind: null })
const filterSignature = computed(() => JSON.stringify(applied.value)), cursor = ref<string>(), cursors = ref<(string | undefined)[]>([])
const page = shallowRef<MemoryPage>(), verified = ref('')
const key = computed(() => ['memory', store.sessionEpoch, 'cram-card-picker', filterSignature.value, cursor.value])
const queryProblem = computed(() => Array.from(input.query).length > 100 || /[\u0000\uD800-\uDFFF]/u.test(input.query) ? '标题最多 100 个完整字符，不能包含 NUL。' : '')
const filterChanged = computed(() => JSON.stringify(input) !== filterSignature.value)
const selectedIds = computed(() => new Set(props.selected.map(item => item.id)))
const kindOptions = Object.entries(memoryKindLabels).map(([value, label]) => ({ value, label }))
let alive = true, generation = 0
watch([readable, () => store.sessionEpoch, filterSignature, cursor], () => { generation++; page.value = undefined; verified.value = '' }, { flush: 'sync' })
const list = useQuery({ queryKey: key, enabled: computed(() => readable.value && !props.disabled), staleTime: 0, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, signature = filterSignature.value, before = cursor.value, kind = applied.value.kind
  const parameters = new URLSearchParams({ status: 'ACTIVE', limit: '20' })
  if (applied.value.query) parameters.set('query', applied.value.query)
  if (kind) parameters.set('kind', kind)
  if (before) parameters.set('cursor', before)
  const value = await read<MemoryPage>(`/memory/cards?${parameters}`)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation || signature !== filterSignature.value || before !== cursor.value) throw new ApiError(0, 'MEMORY_PICKER_CHANGED', '筛选或访问条件已变化，请重新读取卡片。')
  if (!pageValid(value, before, kind)) throw new ApiError(502, 'MEMORY_PICKER_INVALID', '卡片目录格式或顺序不一致，请重新读取。')
  verified.value = `${epoch}/${stamp}`
  return value
} })
watch([list.data, list.dataUpdatedAt, key, readable], () => {
  if (!readable.value || verified.value !== `${store.sessionEpoch}/${generation}`) return
  const value = queryClient.getQueryData<MemoryPage>(key.value)
  if (value && pageValid(value, cursor.value, applied.value.kind)) page.value = value
}, { immediate: true })
watch(() => store.sessionEpoch, () => { cursor.value = undefined; cursors.value = [] })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++ })

function pageValid(value: MemoryPage, before?: string, kind?: MemoryKind | null): boolean {
  try {
    const names = ['id', 'kind', 'title', 'status', 'revision', 'versionId', 'versionNumber', 'sourceCount', 'relatedConceptCount', 'updatedAt']
    return Boolean(value && Object.keys(value).length === 2 && Object.hasOwn(value, 'items') && Object.hasOwn(value, 'nextCursor')
      && Array.isArray(value.items) && value.items.length <= 20 && value.items.every((item, index, items) => item && Object.keys(item).length === names.length && names.every(name => Object.hasOwn(item, name))
        && memoryIdValid(item.id) && memoryIdValid(item.versionId) && (index ? item.id > items[index - 1]!.id : before === undefined || item.id > before)
        && Object.hasOwn(memoryKindLabels, item.kind) && (!kind || item.kind === kind) && item.status === 'ACTIVE'
        && typeof item.title === 'string' && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(item.title) && Array.from(item.title).length <= 200 && !/[\u0000\uD800-\uDFFF]/u.test(item.title)
        && Number.isSafeInteger(item.revision) && item.revision >= 0 && item.revision <= 1000
        && Number.isSafeInteger(item.versionNumber) && item.versionNumber >= 1 && item.versionNumber <= 1000 && item.versionNumber <= item.revision + 1
        && Number.isSafeInteger(item.sourceCount) && item.sourceCount >= 1 && item.sourceCount <= 10
        && Number.isSafeInteger(item.relatedConceptCount) && item.relatedConceptCount >= 0 && item.relatedConceptCount <= 20 && memoryUtcMicros(item.updatedAt) !== undefined)
      && (value.nextCursor === null || (value.items.length === 20 && memoryIdValid(value.nextCursor) && value.items.at(-1)?.id === value.nextCursor)))
  } catch { return false }
}
function applyFilters() { if (!blocked.value && !queryProblem.value) { applied.value = { ...input }; cursor.value = undefined; cursors.value = [] } }
function select(item: MemorySummary, checked: boolean) {
  if (blocked.value || list.isFetching.value || list.error.value || verified.value !== `${store.sessionEpoch}/${generation}`) return
  if (!checked) { remove(item.id); return }
  if (selectedIds.value.has(item.id) || props.selected.length >= 200 || !page.value?.items.some(value => value.id === item.id)) return
  emit('update:selected', [...props.selected, { ...item }])
}
function remove(id: string) { if (!blocked.value) emit('update:selected', props.selected.filter(item => item.id !== id)) }
function next() { if (!blocked.value && !list.isFetching.value && !list.error.value && page.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = page.value.nextCursor } }
function previous() { if (!blocked.value && !list.isFetching.value && cursors.value.length) cursor.value = cursors.value.pop() }
function refresh() { if (!blocked.value && !list.isFetching.value) void list.refetch() }
</script>

<template>
  <section class="memory-card-picker" aria-label="选择冲刺卡片">
    <header><h3>选择卡片</h3><span v-if="readable" class="field-help">已选 {{ selected.length }} / 200</span></header>
    <p v-if="!readable" class="notice" role="status">资料访问恢复后可继续选卡，已选范围保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="picker-content">
      <p class="field-help">按选择顺序保存固定范围。尚需复核或尚无排程的卡也能选入，开始练习时会检查当前条件。</p>
      <form class="picker-filters" @submit.prevent="applyFilters"><label>卡片标题<NInput v-model:value="input.query" :disabled="blocked" placeholder="查找想集中回忆的知识" /></label><label>类型<NSelect v-model:value="input.kind" :options="kindOptions" clearable :disabled="blocked" placeholder="全部类型" /></label><NButton attr-type="submit" :disabled="blocked || !!queryProblem">查找</NButton><NButton text :disabled="blocked || list.isFetching.value" @click="refresh">重读</NButton></form>
      <p v-if="queryProblem" class="notice error" role="alert">{{ queryProblem }}</p><p v-else-if="filterChanged" class="field-help">筛选尚未应用，下面仍是上次结果。</p>
      <p v-if="list.isPending.value" role="status">正在读取可选卡片…</p><p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}</p>
      <p v-if="page && !page.items.length && !list.error.value" class="field-help">当前条件没有使用中的卡片，可修改标题或类型后继续查找。</p>
      <ul class="picker-results"><li v-for="item in page?.items" :key="item.id"><NCheckbox :checked="selectedIds.has(item.id)" :disabled="blocked || list.isFetching.value || !!list.error.value || (!selectedIds.has(item.id) && selected.length >= 200)" @update:checked="select(item, $event)"><span class="card-choice"><strong>{{ item.title }}</strong><small>{{ memoryKindLabels[item.kind] }} · v{{ item.versionNumber }} · {{ item.sourceCount }} 个依据来源</small></span></NCheckbox></li></ul>
      <div class="row-actions"><NButton size="small" :disabled="blocked || !cursors.length || list.isFetching.value" @click="previous">上一页</NButton><NButton size="small" :disabled="blocked || !page?.nextCursor || list.isFetching.value || !!list.error.value" @click="next">下一页</NButton></div>
      <details :open="selected.length > 0"><summary>已选范围（{{ selected.length }} 张）</summary><ol class="selected-cards"><li v-for="item in selected" :key="item.id"><span>{{ item.title }} <small>选中时 v{{ item.versionNumber }}</small></span><NButton text size="small" :disabled="blocked" @click="remove(item.id)">移出范围</NButton></li></ol></details>
    </div>
  </section>
</template>

<style scoped>
.memory-card-picker, .picker-content { display: grid; gap: 15px; min-width: 0; }header, .row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }header { justify-content: space-between; }h3, p { margin: 0; }h3 { font-size: 16px; }.picker-filters { display: grid; grid-template-columns: minmax(150px, 1fr) 140px auto auto; gap: 12px; align-items: end; }.picker-filters label { display: grid; gap: 7px; font-size: 12px; }.picker-results { display: grid; list-style: none; margin: 0; padding: 0; }.picker-results li { padding: 10px 0; border-bottom: 1px solid var(--line); }.card-choice { display: grid; gap: 4px; white-space: normal; overflow-wrap: anywhere; }.card-choice strong { font-size: 13px; font-weight: 550; }small { color: var(--muted); font-size: 12px; }.selected-cards { display: grid; gap: 10px; max-height: 300px; overflow: auto; padding-left: 24px; }.selected-cards li { padding: 3px 0; font-size: 13px; overflow-wrap: anywhere; }.selected-cards .n-button { margin-left: 10px; }summary { cursor: pointer; padding: 8px 0; font-size: 13px; }summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }@media (max-width: 680px) { .picker-filters { grid-template-columns: 1fr 1fr; } }
</style>
