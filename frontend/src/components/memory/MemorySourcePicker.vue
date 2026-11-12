<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, watch } from 'vue'
import { NButton, NInput, NSelect } from 'naive-ui'
import type { ItemDetail, ItemSummary, Schema, Version } from '../../api/client'
import { ApiError, errorText, read } from '../../api/client'
import type { MemoryRelatedConcept, MemorySourceLink, MemorySourceReference } from '../../api/memory'
import { memoryIdValid, memorySourceStateLabels } from '../../api/memory'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ sources: MemorySourceLink[]; relatedConceptIds: string[]; retainedSources?: MemorySourceReference[];
  retainedConcepts?: MemoryRelatedConcept[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:sources': [value: MemorySourceLink[]]; 'update:relatedConceptIds': [value: string[]] }>()
type Purpose = 'sources' | 'concepts'
type Candidate = { purpose: Purpose; reference: MemorySourceReference; concept: MemoryRelatedConcept | null }
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const active = ref(true), purpose = ref<Purpose>(), kind = ref<'ALL' | 'NOTE' | 'CONCEPT'>('ALL')
const input = ref(''), search = ref(''), cursor = ref<string>(), nextCursor = ref<string | null>(null), previous = ref<(string | undefined)[]>([])
const items = shallowRef<ItemSummary[]>([]), candidate = shallowRef<Candidate>()
const listLoading = ref(false), choosing = ref(false), refreshing = ref(false), loaded = ref(false)
const listError = ref(''), choiceError = ref('')
const sourceInfo = shallowRef(new Map<string, MemorySourceReference>()), conceptInfo = shallowRef(new Map<string, MemoryRelatedConcept>())
const sourceErrors = shallowRef(new Map<string, string>()), conceptErrors = shallowRef(new Map<string, string>())
let alive = true, generation = 0, listSequence = 0, choiceSequence = 0, sourceSequence = 0
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const blocked = computed(() => !readable.value || choosing.value)
const selectionSignature = computed(() => JSON.stringify([props.sources, props.relatedConceptIds]))
const kindOptions = [{ value: 'ALL', label: '笔记与概念' }, { value: 'NOTE', label: '笔记' }, { value: 'CONCEPT', label: '概念' }]
const candidateExists = computed(() => Boolean(candidate.value && (candidate.value.purpose === 'sources'
  ? props.sources.some(source => source.itemId === candidate.value!.reference.itemId && source.versionId === candidate.value!.reference.versionId)
  : props.relatedConceptIds.includes(candidate.value.reference.itemId))))
const candidateFull = computed(() => candidate.value?.purpose === 'sources' ? props.sources.length >= 10 : props.relatedConceptIds.length >= 20)

watch([readable, () => store.sessionEpoch], () => {
  generation++; listSequence++; choiceSequence++; sourceSequence++
  items.value = []; candidate.value = undefined; loaded.value = false; listLoading.value = false; choosing.value = false; refreshing.value = false
  sourceInfo.value = new Map(); conceptInfo.value = new Map(); sourceErrors.value = new Map(); conceptErrors.value = new Map()
  if (readable.value) { void refreshSelections(); if (purpose.value) void loadPage(undefined) }
}, { flush: 'sync' })
watch(selectionSignature, () => {
  choiceSequence++; choosing.value = false
  if (readable.value) void refreshSelections()
}, { immediate: true })
watch(kind, () => { candidate.value = undefined; if (readable.value && purpose.value === 'sources') { previous.value = []; void loadPage(undefined) } })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; listSequence++; choiceSequence++; sourceSequence++ })

function identity(source: MemorySourceLink) { return `${source.itemId}/${source.versionId}` }
function retained(source: MemorySourceLink) { return Boolean(props.retainedSources?.some(value => value.itemId === source.itemId && value.versionId === source.versionId)) }
function retainedConcept(id: string) { return Boolean(props.retainedConcepts?.some(value => value.itemId === id)) }
function knowledgeTitle(value: unknown): value is string { return typeof value === 'string' && value.length >= 1 && value.length <= 200 }
function validVersion(value: Version): boolean { return Boolean(value && memoryIdValid(value.id) && Number.isSafeInteger(value.number) && value.number >= 1 && value.number <= 2147483647
  && knowledgeTitle(value.title) && typeof value.body === 'string') }
async function knowledge(id: string): Promise<ItemDetail> {
  if (!memoryIdValid(id)) throw mismatch('知识标识无效，请重新选择。')
  const value = await read<ItemDetail>(`/knowledge/items/${id}`)
  if (!value || value.id !== id || !['NOTE', 'CONCEPT'].includes(value.kind) || !['ACTIVE', 'ARCHIVED'].includes(value.status)
    || !validVersion(value.published) || value.currentVersionId !== value.published.id || value.versionNumber !== value.published.number) throw mismatch('知识与当前发布版本不一致，请重新读取。')
  return value
}
function asCandidate(value: ItemDetail, selectedPurpose: Purpose): Candidate {
  if (value.status !== 'ACTIVE' || (selectedPurpose === 'concepts' && value.kind !== 'CONCEPT')) throw mismatch('新增关联须选择有效的当前发布知识；相关概念须为概念。')
  return { purpose: selectedPurpose, reference: { itemId: value.id, versionId: value.published.id, kind: value.kind, title: value.published.title,
    number: value.published.number, state: 'CURRENT', bodyChanged: false },
  concept: value.kind === 'CONCEPT' ? { itemId: value.id, title: value.published.title, status: value.status } : null }
}
function mismatch(message: string) { return new ApiError(502, 'MEMORY_SOURCE_MISMATCH', message) }
function validContext(epoch: number, stamp: number) { return alive && readable.value && epoch === store.sessionEpoch && stamp === generation }

async function refreshSelections() {
  if (!readable.value) return
  const epoch = store.sessionEpoch, stamp = generation, request = ++sourceSequence, signature = selectionSignature.value
  const links = props.sources.map(source => ({ ...source })), ids = [...props.relatedConceptIds]
  if (links.length > 10 || ids.length > 20) return
  refreshing.value = true
  const details = new Map<string, Promise<ItemDetail>>()
  const detail = (id: string) => { if (!details.has(id)) details.set(id, knowledge(id)); return details.get(id)! }
  const sourceResults = links.map(async link => {
    const value = await detail(link.itemId)
    if (!validContext(epoch, stamp) || request !== sourceSequence || signature !== selectionSignature.value) throw new ApiError(0, 'MEMORY_SOURCE_CONTEXT_CHANGED', '来源读取条件已变化。')
    if (!memoryIdValid(link.versionId)) throw mismatch('所选来源缺少确切版本，请重新选择。')
    const version = value.published.id === link.versionId ? value.published : await read<Version>(`/knowledge/items/${link.itemId}/versions/${link.versionId}`)
    if (!validVersion(version) || version.id !== link.versionId || version.number > value.published.number) throw mismatch('来源版本与知识对象不一致，请重新核对。')
    // 旧版本不可变；只把确切标签与正文比较结果留在组件中，不向父级传递知识正文或草稿。
    return { itemId: link.itemId, versionId: link.versionId, kind: value.kind, title: version.title, number: version.number,
      state: value.status === 'ARCHIVED' ? 'ARCHIVED' : version.id === value.currentVersionId ? 'CURRENT' : 'SUPERSEDED', bodyChanged: version.body !== value.published.body } as MemorySourceReference
  })
  const conceptResults = ids.map(async id => {
    const value = await detail(id)
    if (value.kind !== 'CONCEPT') throw mismatch('关联条目不是概念，请移除后重新选择。')
    return { itemId: id, title: value.published.title, status: value.status } as MemoryRelatedConcept
  })
  const [sources, concepts] = await Promise.all([Promise.allSettled(sourceResults), Promise.allSettled(conceptResults)])
  if (!validContext(epoch, stamp) || request !== sourceSequence || signature !== selectionSignature.value) return
  const knownSources = new Map<string, MemorySourceReference>(), failedSources = new Map<string, string>()
  const knownConcepts = new Map<string, MemoryRelatedConcept>(), failedConcepts = new Map<string, string>()
  sources.forEach((result, index) => { const key = identity(links[index]!); if (result.status === 'fulfilled') knownSources.set(key, result.value); else failedSources.set(key, errorText(result.reason)) })
  concepts.forEach((result, index) => { if (result.status === 'fulfilled') knownConcepts.set(ids[index]!, result.value); else failedConcepts.set(ids[index]!, errorText(result.reason)) })
  sourceInfo.value = knownSources; sourceErrors.value = failedSources; conceptInfo.value = knownConcepts; conceptErrors.value = failedConcepts; refreshing.value = false
}
function open(value: Purpose) {
  if (blocked.value) return
  purpose.value = purpose.value === value ? undefined : value; candidate.value = undefined; choiceError.value = ''; previous.value = []
  if (purpose.value) void loadPage(undefined)
}
async function loadPage(page: string | undefined) {
  if (!readable.value || !purpose.value || choosing.value) return
  const epoch = store.sessionEpoch, stamp = generation, request = ++listSequence, selectedPurpose = purpose.value
  const selectedKind = selectedPurpose === 'concepts' ? 'CONCEPT' : kind.value, selectedSearch = search.value
  listLoading.value = true; listError.value = ''; items.value = []; loaded.value = false; candidate.value = undefined
  const params = new URLSearchParams({ status: 'ACTIVE', limit: '10', q: selectedSearch })
  if (selectedKind !== 'ALL') params.set('kind', selectedKind)
  if (page) params.set('cursor', page)
  try {
    const result = await read<Schema['LibraryPage']>(`/knowledge/library?${params}`)
    if (!validContext(epoch, stamp) || request !== listSequence || selectedPurpose !== purpose.value) return
    if (!result || !Array.isArray(result.items) || result.items.length > 10 || result.items.some(item => !item || !memoryIdValid(item.id)
      || !['NOTE', 'CONCEPT'].includes(item.kind) || item.status !== 'ACTIVE' || (selectedKind !== 'ALL' && item.kind !== selectedKind)
      || !knowledgeTitle(item.title) || !Number.isSafeInteger(item.versionNumber) || item.versionNumber < 1)
      || new Set(result.items.map(item => item.id)).size !== result.items.length
      || (result.nextCursor !== null && (!memoryIdValid(result.nextCursor) || result.nextCursor === page))) throw mismatch('知识列表格式不一致，请重新读取。')
    items.value = result.items; nextCursor.value = result.nextCursor; cursor.value = page; loaded.value = true
  } catch (error) { if (validContext(epoch, stamp) && request === listSequence) listError.value = errorText(error) }
  finally { if (alive && request === listSequence) listLoading.value = false }
}
function submitSearch() {
  if (blocked.value || listLoading.value) return
  if (input.value.length > 100 || /[\u0000\uD800-\uDFFF]/u.test(input.value)) { listError.value = '查找文字过长或包含不完整字符，请缩短后重试。'; return }
  search.value = input.value; previous.value = []; void loadPage(undefined)
}
async function choose(item: ItemSummary) {
  if (blocked.value || listLoading.value || !purpose.value || !items.value.some(value => value.id === item.id)) return
  const epoch = store.sessionEpoch, stamp = generation, request = ++choiceSequence, selectedPurpose = purpose.value
  choosing.value = true; choiceError.value = ''; candidate.value = undefined
  try {
    const value = await knowledge(item.id)
    if (!validContext(epoch, stamp) || request !== choiceSequence || selectedPurpose !== purpose.value) return
    candidate.value = asCandidate(value, selectedPurpose)
  } catch (error) { if (validContext(epoch, stamp) && request === choiceSequence) choiceError.value = errorText(error) }
  finally { if (alive && request === choiceSequence) choosing.value = false }
}
async function add() {
  const selected = candidate.value
  if (blocked.value || !selected || candidateExists.value || candidateFull.value) return
  const epoch = store.sessionEpoch, stamp = generation, request = ++choiceSequence
  choosing.value = true; choiceError.value = ''
  try {
    const value = asCandidate(await knowledge(selected.reference.itemId), selected.purpose)
    if (!validContext(epoch, stamp) || request !== choiceSequence || selected !== candidate.value) return
    if (JSON.stringify(value) !== JSON.stringify(selected)) { candidate.value = value; choiceError.value = '发布版本刚刚变化，已保留原有选择；请核对新的候选后再次确认。'; return }
    if (selected.purpose === 'sources') {
      if (props.sources.length >= 10 || props.sources.some(source => identity(source) === identity(selected.reference))) return
      emit('update:sources', [...props.sources.map(source => ({ ...source })), { itemId: selected.reference.itemId, versionId: selected.reference.versionId }])
    } else {
      if (props.relatedConceptIds.length >= 20 || props.relatedConceptIds.includes(selected.reference.itemId)) return
      emit('update:relatedConceptIds', [...props.relatedConceptIds, selected.reference.itemId])
    }
    candidate.value = undefined
  } catch (error) { if (validContext(epoch, stamp) && request === choiceSequence) choiceError.value = errorText(error) }
  finally { if (alive && request === choiceSequence) choosing.value = false }
}
function removeSource(index: number) { if (!blocked.value) emit('update:sources', props.sources.filter((_, position) => position !== index).map(source => ({ ...source }))) }
function removeConcept(index: number) { if (!blocked.value) emit('update:relatedConceptIds', props.relatedConceptIds.filter((_, position) => position !== index)) }
function moveSource(index: number, direction: -1 | 1) {
  if (blocked.value || index + direction < 0 || index + direction >= props.sources.length) return
  const values = props.sources.map(source => ({ ...source })); [values[index], values[index + direction]] = [values[index + direction]!, values[index]!]
  emit('update:sources', values)
}
function moveConcept(index: number, direction: -1 | 1) {
  if (blocked.value || index + direction < 0 || index + direction >= props.relatedConceptIds.length) return
  const values = [...props.relatedConceptIds]; [values[index], values[index + direction]] = [values[index + direction]!, values[index]!]
  emit('update:relatedConceptIds', values)
}
function nextPage() { if (!blocked.value && !listLoading.value && nextCursor.value) { previous.value.push(cursor.value); void loadPage(nextCursor.value) } }
function previousPage() { if (!blocked.value && !listLoading.value && previous.value.length) void loadPage(previous.value.pop()) }
</script>

<template>
  <section class="memory-source-picker" aria-label="选择回忆卡来源与相关概念">
    <p v-if="!readable" class="notice" role="status">当前访问条件限制资料读取，已选来源与概念保留。</p>
    <div v-else class="picker-content">
      <section class="selected-section"><header><h3>来源依据（{{ sources.length }} / 10）</h3><NButton size="small" :disabled="blocked || sources.length >= 10" @click="open('sources')">{{ purpose === 'sources' ? '收起选择' : '添加来源' }}</NButton></header><p class="field-help">选择笔记或概念的确切发布版本。已有旧版本会继续保留；添加新版会成为另一条来源。</p><p v-if="!sources.length" class="field-help">至少选择一条来源，再独立编写题面和个人参考。</p>
        <ol class="selected-list"><li v-for="(source, index) in sources" :key="identity(source)"><div><strong>{{ sourceInfo.get(identity(source))?.title ?? `来源 ${index + 1}` }}</strong><p v-if="sourceInfo.get(identity(source))" class="field-help">v{{ sourceInfo.get(identity(source))!.number }} · {{ memorySourceStateLabels[sourceInfo.get(identity(source))!.state] }}{{ sourceInfo.get(identity(source))!.bodyChanged ? ' · 正文有变化，请核对' : '' }}{{ retained(source) ? ' · 当前卡版本已保留' : '' }}</p><p v-else class="field-help">{{ source.itemId }} · {{ source.versionId }}</p><p v-if="sourceErrors.get(identity(source))" class="notice error" role="alert">{{ sourceErrors.get(identity(source)) }} 原选择保留。</p><p v-if="sourceInfo.get(identity(source)) && sourceInfo.get(identity(source))!.state !== 'CURRENT' && !retained(source)" class="notice">这条新增来源已不可直接采用，请移除后重新选择当前发布版。</p></div><div class="row-actions"><NButton text size="small" :disabled="blocked || index === 0" :aria-label="`上移来源 ${index + 1}`" @click="moveSource(index, -1)">上移</NButton><NButton text size="small" :disabled="blocked || index === sources.length - 1" :aria-label="`下移来源 ${index + 1}`" @click="moveSource(index, 1)">下移</NButton><NButton text size="small" :disabled="blocked" @click="removeSource(index)">移除</NButton></div></li></ol>
      </section>
      <section class="selected-section"><header><h3>相关概念（{{ relatedConceptIds.length }} / 20）</h3><NButton size="small" :disabled="blocked || relatedConceptIds.length >= 20" @click="open('concepts')">{{ purpose === 'concepts' ? '收起选择' : '关联概念' }}</NButton></header><p class="field-help">用于分类与星图导航，独立于来源依据。</p><p v-if="!relatedConceptIds.length" class="field-help">相关概念可以留空。</p><ul class="selected-list"><li v-for="(id, index) in relatedConceptIds" :key="id"><div><strong>{{ conceptInfo.get(id)?.title ?? `概念 ${index + 1}` }}</strong><p class="field-help">{{ conceptInfo.get(id) ? conceptInfo.get(id)!.status === 'ARCHIVED' ? '已归档' : '使用中' : id }}{{ retainedConcept(id) ? ' · 当前卡版本已关联' : '' }}</p><p v-if="conceptErrors.get(id)" class="notice error" role="alert">{{ conceptErrors.get(id) }} 原关联保留。</p><p v-if="conceptInfo.get(id)?.status === 'ARCHIVED' && !retainedConcept(id)" class="notice">新增关联须为有效概念，请移除或先恢复该概念。</p></div><div class="row-actions"><NButton text size="small" :disabled="blocked || index === 0" :aria-label="`上移概念 ${index + 1}`" @click="moveConcept(index, -1)">上移</NButton><NButton text size="small" :disabled="blocked || index === relatedConceptIds.length - 1" :aria-label="`下移概念 ${index + 1}`" @click="moveConcept(index, 1)">下移</NButton><NButton text size="small" :disabled="blocked" @click="removeConcept(index)">移除</NButton></div></li></ul></section>
      <div v-if="sources.length || relatedConceptIds.length" class="refresh-row"><NButton text size="small" :disabled="blocked || refreshing" :loading="refreshing" @click="refreshSelections">重新核对所选来源与概念</NButton><p class="field-help">正文有变化只表示当前发布文本不同，不表示内容错误。</p></div>
      <section v-if="purpose" class="choice-panel" :aria-label="purpose === 'sources' ? '查找来源' : '查找相关概念'">
        <h4>{{ purpose === 'sources' ? '查找来源知识' : '查找相关概念' }}</h4><NSelect v-if="purpose === 'sources'" v-model:value="kind" :options="kindOptions" :disabled="blocked || listLoading" aria-label="知识类型" />
        <form class="search-row" @submit.prevent="submitSearch"><NInput v-model:value="input" :disabled="blocked || listLoading" placeholder="按知识条目标题查找" aria-label="查找知识条目" /><NButton attr-type="submit" :disabled="blocked || listLoading">查找</NButton></form>
        <p class="field-help">查找结果用于定位条目，确认时核对它的已发布标题与版本；草稿正文不会带入卡片。</p>
        <p v-if="listLoading" class="field-help" role="status">正在读取知识条目…</p><p v-if="listError" class="notice error" role="alert">{{ listError }}<NButton text :disabled="blocked || listLoading" @click="loadPage(cursor)">重新读取</NButton></p>
        <div class="results"><button v-for="item in items" :key="item.id" type="button" :disabled="blocked || listLoading" @click="choose(item)"><strong>{{ item.title }}</strong><span>{{ item.kind === 'NOTE' ? '笔记' : '概念' }} · 核对当前发布版</span></button></div>
        <p v-if="loaded && !items.length && !listError" class="field-help">没有匹配的有效知识条目，可更换标题查找。</p><div class="row-actions"><NButton size="small" :disabled="blocked || listLoading || !previous.length" @click="previousPage">上一页</NButton><NButton size="small" :disabled="blocked || listLoading || !nextCursor" @click="nextPage">下一页</NButton></div>
        <p v-if="choosing" class="field-help" role="status">正在核对确切发布版本…</p><p v-if="choiceError" class="notice error" role="alert">{{ choiceError }}</p>
        <div v-if="candidate" class="candidate"><strong>{{ candidate.reference.title }}</strong><p class="field-help">{{ candidate.reference.kind === 'NOTE' ? '笔记' : '概念' }} · 当前发布 v{{ candidate.reference.number }}</p><p class="field-help">{{ candidate.purpose === 'sources' ? '只添加确切版本链接；题面和参考由你填写。' : '只添加概念关联，不将它自动列为来源依据。' }}</p><p v-if="candidateExists" class="field-help">已在当前选择中。</p><p v-else-if="candidateFull" class="field-help">已达数量上限，请先移除一项。</p><NButton type="primary" :disabled="blocked || candidateExists || candidateFull" :loading="choosing" @click="add">{{ candidate.purpose === 'sources' ? '添加这个发布版本' : '关联这个概念' }}</NButton></div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.memory-source-picker, .picker-content, .selected-section, .choice-panel { display: grid; gap: 14px; min-width: 0; }
.picker-content { gap: 23px; } header, .row-actions, .refresh-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; }header { justify-content: space-between; }
h3, h4, p { margin: 0; } h3, h4 { font-size: 14px; }.selected-list { display: grid; list-style: none; margin: 0; padding: 0; }
.selected-list > li { display: grid; gap: 9px; padding: 12px 0; border-bottom: 1px solid var(--line); min-width: 0; }.selected-list > li > div:first-child { display: grid; gap: 6px; }
strong { font-size: 13px; overflow-wrap: anywhere; }.selected-list p { overflow-wrap: anywhere; }.choice-panel { padding: 16px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); }
.search-row { display: flex; align-items: start; gap: 8px; }.search-row > .n-input { flex: 1; min-width: 0; }.results { display: grid; gap: 4px; max-height: 310px; overflow: auto; }
.results > button { display: grid; gap: 5px; padding: 10px; text-align: left; color: var(--ink); border-radius: 4px; }.results > button:hover { background: var(--surface-deep); }.results span { color: var(--muted); font-size: 12px; }
.results > button:focus-visible { outline: 2px solid var(--brand); outline-offset: -2px; }.results > button:disabled { opacity: .6; cursor: default; }.candidate { display: grid; gap: 10px; border-top: 1px solid var(--line); padding-top: 15px; }.candidate > .n-button { justify-self: start; }
@media (max-width: 560px) { .choice-panel { padding: 12px; }.search-row { flex-wrap: wrap; }.search-row > .n-input { flex-basis: 100%; } }
</style>
