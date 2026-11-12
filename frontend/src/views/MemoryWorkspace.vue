<script setup lang="ts">
import { computed, defineAsyncComponent, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import type { ItemSummary } from '../api/client'
import type { MemoryCard, MemoryKind, MemoryPage, MemoryStatus } from '../api/memory'
import { memoryIdValid, memoryKindLabels, memoryStatusLabels } from '../api/memory'
import { questionTextLength } from '../api/bank'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import MemoryNavigation from '../components/memory/MemoryNavigation.vue'
const MemoryCardEditor = defineAsyncComponent(() => import('../components/memory/MemoryCardEditor.vue'))
const MemoryReviewSettings = defineAsyncComponent(() => import('../components/memory/MemoryReviewSettings.vue'))
const KnowledgePicker = defineAsyncComponent(() => import('../components/KnowledgePicker.vue'))

const route = useRoute(), router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore()
const active = ref(true)
const readable = computed(() => active.value && store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const creating = computed(() => route.query.create === '1')
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const sourceItemId = computed(() => typeof route.query.sourceItemId === 'string' ? route.query.sourceItemId : undefined)
const sourceVersionId = computed(() => typeof route.query.sourceVersionId === 'string' ? route.query.sourceVersionId : undefined)
const routeProblem = computed(() => {
  const query = route.query
  if (Object.keys(query).some(key => !['item', 'create', 'sourceItemId', 'sourceVersionId', 'versionId'].includes(key))
    || (query.create !== undefined && query.create !== '1')) return '卡片入口参数无效，请从列表重新选择或新建。'
  if (query.item !== undefined && (!memoryIdValid(query.item) || creating.value)) return '卡片标识无效，请从列表重新选择。'
  if ((query.sourceItemId !== undefined || query.sourceVersionId !== undefined)
    && (!creating.value || !memoryIdValid(query.sourceItemId) || !memoryIdValid(query.sourceVersionId))) return '知识来源入口需要同一条知识的确切发布版本，请从知识页重新进入。'
  if (query.versionId !== undefined && (!memoryIdValid(query.item) || !memoryIdValid(query.versionId))) return '卡片历史入口无效，请重新选择卡片后查看历史。'
  return ''
})
// 同一卡片切换历史不重建编辑器；新建时来源二元组变化则必须经过路由保稿守卫。
const editorKey = computed(() => creating.value ? `new:${sourceItemId.value ?? ''}:${sourceVersionId.value ?? ''}` : `item:${selectedId.value ?? ''}`)
type Filters = { status: MemoryStatus; kind: MemoryKind | null; query: string; sourceItemId: string | null; relatedConceptId: string | null }
const emptyFilters = (): Filters => ({ status: 'ACTIVE', kind: null, query: '', sourceItemId: null, relatedConceptId: null })
const input = reactive<Filters>(emptyFilters()), applied = ref<Filters>(emptyFilters())
const signature = computed(() => JSON.stringify(applied.value))
const filterChanged = computed(() => JSON.stringify(input) !== signature.value)
const filterProblem = computed(() => questionTextLength(input.query) > 100 || /[\u0000\uD800-\uDFFF]/u.test(input.query) ? '标题筛选最多 100 个完整字符，不能包含 NUL。' : '')
const picker = ref<'source' | 'concept'>(), sourceChoice = shallowRef<ItemSummary>(), conceptChoice = shallowRef<ItemSummary>()
const statusOptions = Object.entries(memoryStatusLabels).map(([value, label]) => ({ value, label }))
const kindOptions = Object.entries(memoryKindLabels).map(([value, label]) => ({ value, label }))
const cursor = ref<string>(), cursors = ref<(string | undefined)[]>([]), currentPage = shallowRef<MemoryPage>()
const listKey = computed(() => ['memory', store.sessionEpoch, 'list', signature.value, cursor.value])
let alive = true, generation = 0
watch([readable, () => store.sessionEpoch, signature, cursor], () => { generation++; currentPage.value = undefined }, { flush: 'sync' })
const list = useQuery({ queryKey: listKey, enabled: readable, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, filters = signature.value, pageCursor = cursor.value
  const parameters = new URLSearchParams({ status: applied.value.status, limit: '20' })
  if (applied.value.kind) parameters.set('kind', applied.value.kind)
  if (applied.value.query) parameters.set('query', applied.value.query)
  if (applied.value.sourceItemId) parameters.set('sourceItemId', applied.value.sourceItemId)
  if (applied.value.relatedConceptId) parameters.set('relatedConceptId', applied.value.relatedConceptId)
  if (pageCursor) parameters.set('cursor', pageCursor)
  const value = await read<MemoryPage>(`/memory/cards?${parameters}`)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation || filters !== signature.value || pageCursor !== cursor.value) throw new ApiError(0, 'MEMORY_LIST_CHANGED', '筛选或访问条件已变化，请重新读取列表。')
  if (!pageValid(value)) throw new ApiError(502, 'MEMORY_LIST_INVALID', '回忆卡列表格式异常，请重新读取。')
  return value
} })
watch([list.data, list.dataUpdatedAt, listKey, readable], () => {
  if (!alive || !readable.value) return
  const value = queryClient.getQueryData<MemoryPage>(listKey.value)
  if (value && pageValid(value)) currentPage.value = value
}, { immediate: true })
watch(() => store.sessionEpoch, () => { cursor.value = undefined; cursors.value = []; picker.value = undefined; sourceChoice.value = undefined; conceptChoice.value = undefined })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++ })

function pageValid(value: MemoryPage): boolean {
  try {
    const fields = ['id', 'kind', 'title', 'status', 'revision', 'versionId', 'versionNumber', 'sourceCount', 'relatedConceptCount', 'updatedAt']
    return Boolean(value && Object.keys(value).length === 2 && Object.hasOwn(value, 'items') && Object.hasOwn(value, 'nextCursor')
      && Array.isArray(value.items) && value.items.length <= 20 && value.items.every((item, index) => item && Object.keys(item).length === fields.length && fields.every(field => Object.hasOwn(item, field))
        && memoryIdValid(item.id) && memoryIdValid(item.versionId) && (index ? item.id > value.items[index - 1]!.id : !cursor.value || item.id > cursor.value)
        && Object.hasOwn(memoryKindLabels, item.kind) && item.status === applied.value.status && (!applied.value.kind || item.kind === applied.value.kind)
        && typeof item.title === 'string' && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(item.title) && questionTextLength(item.title) <= 200 && !/[\u0000\uD800-\uDFFF]/u.test(item.title)
        && Number.isSafeInteger(item.revision) && item.revision >= 0 && item.revision <= 1000
        && Number.isSafeInteger(item.versionNumber) && item.versionNumber >= 1 && item.versionNumber <= 1000 && item.versionNumber <= item.revision + 1
        && Number.isSafeInteger(item.sourceCount) && item.sourceCount >= 1 && item.sourceCount <= 10
        && Number.isSafeInteger(item.relatedConceptCount) && item.relatedConceptCount >= 0 && item.relatedConceptCount <= 20
        && typeof item.updatedAt === 'string' && Number.isFinite(Date.parse(item.updatedAt)))
      && (value.nextCursor === null || (value.items.length === 20 && memoryIdValid(value.nextCursor) && value.items.at(-1)?.id === value.nextCursor)))
  } catch { return false }
}
function applyFilters() {
  if (!readable.value || filterProblem.value) return
  applied.value = { ...input }; cursor.value = undefined; cursors.value = []; picker.value = undefined
}
function clearFilters() { if (readable.value) { Object.assign(input, emptyFilters()); sourceChoice.value = undefined; conceptChoice.value = undefined; applyFilters() } }
function chooseFilter(item: ItemSummary) {
  if (!readable.value || !memoryIdValid(item.id) || !['NOTE', 'CONCEPT'].includes(item.kind)) return
  if (picker.value === 'source') { input.sourceItemId = item.id; sourceChoice.value = item }
  else if (picker.value === 'concept' && item.kind === 'CONCEPT') { input.relatedConceptId = item.id; conceptChoice.value = item }
  else return
  picker.value = undefined
}
function open(id: string) { if (readable.value && memoryIdValid(id)) void router.push({ name: 'memory-cards', query: { item: id } }) }
function create() { if (readable.value) void router.push({ name: 'memory-cards', query: { create: '1' } }) }
function createAiDraft() { if (readable.value) void router.push({ name: 'ai', query: { create: '1', purpose: 'MEMORY_CARD_DRAFT' } }) }
function close() { if (readable.value) void router.push({ name: 'memory-cards' }) }
function saved(value: MemoryCard) { void queryClient.invalidateQueries({ queryKey: ['memory'] }); if (creating.value) open(value.id) }
function next() { if (readable.value && !list.isFetching.value && !list.error.value && currentPage.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = currentPage.value.nextCursor } }
function previous() { if (readable.value && !list.isFetching.value && cursors.value.length) cursor.value = cursors.value.pop() }
</script>

<template>
  <MemoryNavigation />
  <header class="page-heading"><div><p class="section-label">把知识变成可以回想的问题</p><h1>回忆卡</h1><p class="muted">从笔记或概念整理问答与填空，保留每一版题面、个人参考和来源。</p></div><div class="row-actions"><NButton :disabled="!readable" @click="createAiDraft">AI 生成回忆卡候选</NButton><NButton type="primary" :disabled="!readable" @click="create">＋ 新建回忆卡</NButton></div></header>
  <section class="memory-settings-area" aria-label="复习偏好"><MemoryReviewSettings :disabled="!readable" /></section>
  <div v-if="readable" class="memory-filter-area">
    <form class="memory-filters" aria-label="筛选回忆卡" @submit.prevent="applyFilters">
      <label>状态<NSelect v-model:value="input.status" :options="statusOptions" aria-label="回忆卡状态筛选" /></label>
      <label>类型<NSelect v-model:value="input.kind" :options="kindOptions" clearable placeholder="全部类型" aria-label="回忆卡类型筛选" /></label>
      <label class="title-filter">标题<NInput v-model:value="input.query" placeholder="按卡片标题查找" /></label>
      <div class="row-actions"><NButton attr-type="submit" :disabled="!!filterProblem">应用筛选</NButton><NButton text @click="clearFilters">清空</NButton></div>
      <div class="relationship-filters">
        <div><span class="field-help">来源知识</span><NButton size="small" :aria-expanded="picker === 'source'" @click="picker = picker === 'source' ? undefined : 'source'">{{ input.sourceItemId ? sourceChoice?.title ?? '已选择来源知识' : '选择笔记或概念' }}</NButton><NButton v-if="input.sourceItemId" text size="small" @click="input.sourceItemId = null; sourceChoice = undefined">清除来源</NButton></div>
        <div><span class="field-help">相关概念</span><NButton size="small" :aria-expanded="picker === 'concept'" @click="picker = picker === 'concept' ? undefined : 'concept'">{{ input.relatedConceptId ? conceptChoice?.title ?? '已选择相关概念' : '选择概念' }}</NButton><NButton v-if="input.relatedConceptId" text size="small" @click="input.relatedConceptId = null; conceptChoice = undefined">清除概念</NButton></div>
      </div>
      <p v-if="filterProblem" class="notice error filter-help" role="alert">{{ filterProblem }}</p><p v-else-if="filterChanged" class="field-help filter-help" role="status">筛选条件尚未应用，列表仍显示上一次结果。</p>
    </form>
    <section v-if="picker" class="filter-picker" :aria-label="picker === 'source' ? '选择来源知识筛选' : '选择相关概念筛选'"><header><h2>{{ picker === 'source' ? '按来源知识查找' : '按相关概念查找' }}</h2><NButton text size="small" @click="picker = undefined">收起选择</NButton></header><p class="field-help">可查找已归档内容。选中后点击“应用筛选”，与其他条件一起查询。</p><KnowledgePicker :key="picker" :kind="picker === 'concept' ? 'CONCEPT' : undefined" include-archived @choose="chooseFilter" /></section>
  </div>
  <p v-if="routeProblem" class="notice error route-error" role="alert">{{ routeProblem }}<NButton text :disabled="!readable" @click="close">返回卡片列表</NButton></p>
  <div class="memory-workspace">
    <aside class="memory-catalog" aria-label="回忆卡列表">
      <header><h2>{{ memoryStatusLabels[applied.status] }}的卡片</h2><NButton text size="small" :disabled="!readable || list.isFetching.value" @click="list.refetch()">刷新</NButton></header>
      <p v-if="!readable" class="field-help" role="status">资料访问恢复后可读取卡片，本地编辑输入继续保留。</p>
      <template v-else>
        <p v-if="applied.sourceItemId || applied.relatedConceptId" class="field-help">已按{{ applied.sourceItemId ? '来源知识' : '' }}{{ applied.sourceItemId && applied.relatedConceptId ? '和' : '' }}{{ applied.relatedConceptId ? '相关概念' : '' }}筛选。</p>
        <p v-if="list.isPending.value" class="loading-block" role="status">正在读取回忆卡…</p>
        <p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text :disabled="list.isFetching.value" @click="list.refetch()">重新读取</NButton></p>
        <div v-if="currentPage && !currentPage.items.length && !list.error.value" class="catalog-empty"><strong>当前条件下没有卡片</strong><p>可调整筛选，或从一篇已发布的笔记开始整理。</p></div>
        <div class="memory-list"><button v-for="item in currentPage?.items" :key="item.id" class="memory-row" :aria-current="!creating && selectedId === item.id ? 'true' : undefined" @click="open(item.id)"><strong>{{ item.title }}</strong><span>{{ memoryKindLabels[item.kind] }} · v{{ item.versionNumber }}</span><small>{{ item.sourceCount }} 个来源 · {{ item.relatedConceptCount }} 个相关概念</small></button></div>
        <div v-if="cursors.length || currentPage?.nextCursor" class="row-actions"><NButton size="small" :disabled="!cursors.length || list.isFetching.value" @click="previous">上一页</NButton><NButton size="small" :disabled="!currentPage?.nextCursor || list.isFetching.value || !!list.error.value" @click="next">下一页</NButton></div>
      </template>
    </aside>
    <MemoryCardEditor v-if="!routeProblem && (creating || selectedId)" :key="editorKey" :id="creating ? undefined : selectedId" :source-item-id="creating ? sourceItemId : undefined" :source-version-id="creating ? sourceVersionId : undefined" :disabled="!readable" @saved="saved" @close="close" />
    <section v-else class="memory-welcome"><h2>用一个问题，留下一个知识点</h2><p>把想记住的内容写成问答，或用“____”标出填空。个人参考单独保存，打开卡片时先看题面。</p><div class="row-actions"><NButton type="primary" :disabled="!readable" @click="create">新建回忆卡</NButton><NButton :disabled="!readable" @click="createAiDraft">从知识生成 AI 候选</NButton></div><div class="welcome-notes"><p>卡片保留确切来源版本。知识后来有变化时，可以回到原文核对。</p><p>AI 候选在任务详情审阅并采用后才创建卡片，生成不启用排程，也不计为复习。</p><p>在卡片的“来源与排程”中核对依据、设置复习时间并查看调整记录。保存、浏览和调整时间均不计为完成复习。</p></div></section>
  </div>
</template>

<style scoped>
.memory-settings-area, .memory-filter-area, .route-error, .memory-workspace { max-width: 1280px; margin-left: auto; margin-right: auto; }
.memory-settings-area { margin-bottom: 22px; }
.memory-filter-area { margin-bottom: 22px; }.memory-filters { display: grid; grid-template-columns: minmax(110px, .65fr) minmax(130px, .8fr) minmax(180px, 1.4fr) auto; gap: 13px; align-items: end; padding: 17px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.memory-filters label { display: grid; gap: 7px; min-width: 0; color: var(--muted); font-size: 12px; }.relationship-filters, .filter-help { grid-column: 1 / -1; }.relationship-filters { display: flex; flex-wrap: wrap; gap: 12px 25px; }.relationship-filters > div { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-width: 0; max-width: 100%; }.relationship-filters :deep(.n-button__content) { white-space: normal; overflow-wrap: anywhere; }.relationship-filters :deep(.n-button) { max-width: 100%; height: auto; min-height: 28px; padding-top: 5px; padding-bottom: 5px; }
.filter-picker { display: grid; gap: 12px; padding: 18px 0; border-bottom: 1px solid var(--line); }.filter-picker > header, .memory-catalog > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.filter-picker h2, .memory-catalog h2 { font-size: 15px; }.route-error { margin-bottom: 18px; }
.memory-workspace { display: grid; grid-template-columns: 285px minmax(0, 1fr); min-height: 620px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }.memory-catalog { min-width: 0; padding: 18px 14px; border-right: 1px solid var(--line); background: var(--surface-soft); border-radius: 8px 0 0 8px; }.memory-catalog > header { margin-bottom: 14px; }
.memory-list { display: grid; gap: 5px; margin: 17px 0; }.memory-row { display: grid; gap: 7px; padding: 12px 10px; min-width: 0; width: 100%; text-align: left; overflow-wrap: anywhere; border-radius: 5px; }.memory-row:hover, .memory-row[aria-current=true] { background: var(--surface-deep); }.memory-row strong { font-size: 13px; }.memory-row span, .memory-row small { color: var(--muted); font-size: 12px; }.memory-row:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
.memory-welcome { display: grid; gap: 19px; justify-items: start; align-self: center; max-width: 710px; padding: 42px; }.memory-welcome h2 { font-size: 25px; text-wrap: balance; }.memory-welcome > p, .welcome-notes { color: var(--muted); line-height: 1.8; }.welcome-notes { display: grid; gap: 12px; margin-top: 10px; padding-top: 20px; border-top: 1px solid var(--line); font-size: 13px; }
@media (max-width: 1050px) { .memory-workspace { grid-template-columns: 235px minmax(0, 1fr); }.memory-filters { grid-template-columns: repeat(3, minmax(0, 1fr)); }.memory-filters > .row-actions { grid-column: 1 / -1; } }
@media (max-width: 760px) { .memory-workspace { grid-template-columns: 1fr; }.memory-catalog { border-right: 0; border-bottom: 1px solid var(--line); border-radius: 8px 8px 0 0; }.memory-list { max-height: 280px; overflow-y: auto; }.memory-filters { grid-template-columns: 1fr 1fr; }.title-filter { grid-column: 1 / -1; }.memory-welcome { padding: 34px 22px; } }
</style>
