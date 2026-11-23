<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import { questionTextLength } from '../api/bank'
import type { Recollection, RecollectionPage } from '../api/recollection'
import { recollectionBaselineLabels, recollectionStatusLabels } from '../api/recollection'
import { reviewIdValid } from '../api/review'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
const RecollectionEditor = defineAsyncComponent(() => import('../components/review/RecollectionEditor.vue'))

const route = useRoute(), router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore()
const readable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge)
const creating = computed(() => route.query.create === '1')
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const eventId = computed(() => typeof route.query.eventId === 'string' ? route.query.eventId : undefined)
const eventRevision = computed(() => typeof route.query.eventRevision === 'string' && /^(0|[1-9]\d*)$/.test(route.query.eventRevision) ? Number(route.query.eventRevision) : undefined)
const editorKey = computed(() => creating.value ? `new:${eventId.value ?? ''}:${route.query.eventRevision ?? ''}` : selectedId.value)
const routeProblem = computed(() => {
  if ((route.query.item !== undefined && (!selectedId.value || !reviewIdValid(selectedId.value))) || (creating.value && selectedId.value)) return '回忆入口无效，请从列表重新选择或新建。'
  if ((route.query.eventId !== undefined || route.query.eventRevision !== undefined)
    && (!creating.value || !eventId.value || !reviewIdValid(eventId.value) || eventRevision.value === undefined || !Number.isSafeInteger(eventRevision.value))) return '面试入口缺少有效的历史修订，请从面试安排重新进入。'
  if (route.query.versionId !== undefined && (!selectedId.value || typeof route.query.versionId !== 'string' || !reviewIdValid(route.query.versionId))) return '历史版本入口无效，请重新选择回忆后查看历史。'
  return ''
})
type Filters = { status: Recollection['status']; query: string; interviewId: string | null }
const input = reactive<Filters>({ status: 'ACTIVE', query: '', interviewId: null })
const applied = ref<Filters>({ ...input })
const signature = computed(() => JSON.stringify(applied.value))
const filterChanged = computed(() => JSON.stringify(input) !== signature.value)
const filterProblem = computed(() => questionTextLength(input.query) > 100 || /[\u0000\uD800-\uDFFF]/u.test(input.query) ? '标题筛选最多 100 个完整字符。' : '')
const knownInterviews = reactive(new Map<string, string>())
const interviewOptions = computed(() => [...knownInterviews].map(([value, label]) => ({ value, label })))
const statusOptions = Object.entries(recollectionStatusLabels).map(([value, label]) => ({ value, label }))
const cursor = ref<string>(), cursors = ref<(string | undefined)[]>([])
let alive = true, generation = 0
const list = useQuery({
  queryKey: computed(() => ['recollections', store.sessionEpoch, 'list', signature.value, cursor.value]),
  enabled: readable,
  queryFn: async () => {
    const epoch = store.sessionEpoch, request = ++generation, filters = signature.value, pageCursor = cursor.value
    const parameters = new URLSearchParams({ status: applied.value.status, limit: '20' })
    if (applied.value.query) parameters.set('query', applied.value.query)
    if (applied.value.interviewId) parameters.set('interviewId', applied.value.interviewId)
    if (pageCursor) parameters.set('cursor', pageCursor)
    const value = await read<RecollectionPage>(`/review/recollections?${parameters}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || request !== generation || filters !== signature.value || pageCursor !== cursor.value) throw new ApiError(0, 'RECOLLECTION_LIST_CHANGED', '列表筛选或访问条件已变化，请重新读取。')
    if (!value || !Array.isArray(value.items) || value.items.length > 20 || (value.nextCursor !== null && !reviewIdValid(value.nextCursor))
      || value.items.some(item => !reviewIdValid(item.id) || !reviewIdValid(item.versionId) || typeof item.title !== 'string'
        || !Object.hasOwn(recollectionStatusLabels, item.status) || item.status !== applied.value.status || !Object.hasOwn(recollectionBaselineLabels, item.baselineKind)
        || !Number.isInteger(item.revision) || item.revision < 0 || item.revision > 1000 || !Number.isInteger(item.versionNumber) || item.versionNumber < 1 || item.versionNumber > item.revision + 1
        || (item.interviewId === null ? item.interviewTitle !== null : !reviewIdValid(item.interviewId) || typeof item.interviewTitle !== 'string')
        || (applied.value.interviewId !== null && item.interviewId !== applied.value.interviewId))
      || new Set(value.items.map(item => item.id)).size !== value.items.length) throw new ApiError(502, 'RECOLLECTION_LIST_INVALID', '回忆列表格式异常，请重新读取。')
    return value
  },
})
watch(list.data, value => { if (readable.value) for (const item of value?.items ?? []) if (item.interviewId && item.interviewTitle) knownInterviews.set(item.interviewId, item.interviewTitle) })
watch([readable, () => store.sessionEpoch], () => { generation++ }, { flush: 'sync' })
watch(() => store.sessionEpoch, () => { cursor.value = undefined; cursors.value = []; knownInterviews.clear(); if (input.interviewId) knownInterviews.set(input.interviewId, '已选面试安排') })
onUnmounted(() => { alive = false; generation++ })
function applyFilters() { if (readable.value && !filterProblem.value) { applied.value = { ...input }; cursor.value = undefined; cursors.value = [] } }
function clearFilters() { if (readable.value) { Object.assign(input, { status: 'ACTIVE', query: '', interviewId: null }); applyFilters() } }
function filterInterview(id: string, title: string) { if (readable.value) { knownInterviews.set(id, title); input.interviewId = id; applyFilters() } }
function open(id: string) { if (readable.value) void router.push({ name: 'recollections', query: { item: id } }) }
function create() { if (readable.value) void router.push({ name: 'recollections', query: { create: '1' } }) }
function close() { if (readable.value) void router.push({ name: 'recollections' }) }
function saved(value: Recollection) { void queryClient.invalidateQueries({ queryKey: ['recollections'] }); if (creating.value) open(value.id) }
function next() { if (readable.value && !list.isFetching.value && list.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = list.data.value.nextCursor } }
</script>

<template>
  <header class="page-heading"><div><p class="section-label">把面试后的记忆留在原处</p><h1>面后回忆</h1><p class="muted">记下问题、当时的表达与不确定之处，逐步补记，也可整理为待审题目。</p></div><NButton type="primary" :disabled="!readable" @click="create">＋ 记录一个问题</NButton></header>
  <details class="recollection-filter-disclosure"><summary>回忆列表筛选</summary>
  <form v-show="readable" class="recollection-filters" aria-label="筛选面后回忆" @submit.prevent="applyFilters"><label>状态<NSelect v-model:value="input.status" :options="statusOptions" :disabled="!readable" aria-label="回忆状态筛选" /></label><label>标题<NInput v-model:value="input.query" :disabled="!readable" placeholder="按回忆标题查找" /></label><label>关联面试<NSelect v-model:value="input.interviewId" :options="interviewOptions" clearable filterable :disabled="!readable" placeholder="全部面试与未关联记录" aria-label="面试筛选" /></label><div class="row-actions"><NButton attr-type="submit" :disabled="!readable || !!filterProblem">应用筛选</NButton><NButton text :disabled="!readable" @click="clearFilters">清空</NButton></div><p class="field-help filter-help">面试选项来自已载入的回忆，也可在列表中选择“只看这次面试”；筛选后会查询该面试的全部匹配记录。</p><p v-if="filterProblem" class="notice error filter-help" role="alert">{{ filterProblem }}</p><p v-else-if="filterChanged" class="field-help filter-help" role="status">筛选条件尚未应用，列表仍显示上一次结果。</p></form>
  </details>
  <p v-if="routeProblem" class="notice error route-error" role="alert">{{ routeProblem }}<NButton text :disabled="!readable" @click="close">返回回忆列表</NButton></p>
  <div class="recollections-workspace" :class="{ focused: selectedId || creating }">
    <aside class="recollection-catalog" aria-label="回忆列表"><header><h2>{{ recollectionStatusLabels[applied.status] }}的回忆</h2><NButton text size="small" :disabled="!readable || list.isFetching.value" @click="list.refetch()">刷新</NButton></header><p v-if="!readable" class="field-help" role="status">资料访问恢复后可读取回忆，本地输入继续保留。</p><template v-else><p v-if="applied.interviewId" class="field-help">已限定面试：{{ knownInterviews.get(applied.interviewId) ?? '已选面试安排' }}</p><p v-if="list.isPending.value" class="loading-block" role="status">正在读取回忆…</p><p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text :disabled="list.isFetching.value" @click="list.refetch()">重新读取</NButton></p><div v-if="list.data.value && !list.data.value.items.length && !list.error.value" class="catalog-empty"><strong>当前条件下没有回忆</strong><p>可调整筛选，或先记下这次面试中印象最深的一个问题。</p></div><div class="recollection-list"><article v-for="item in list.data.value?.items" :key="item.id" :class="{ selected: !creating && selectedId === item.id }"><button class="recollection-select" :aria-current="!creating && selectedId === item.id ? 'true' : undefined" @click="open(item.id)"><strong>{{ item.title }}</strong><span>{{ recollectionBaselineLabels[item.baselineKind] }} · v{{ item.versionNumber }}</span><small>{{ item.interviewTitle || '独立回忆 · 未关联面试' }}</small></button><button v-if="item.interviewId && item.interviewTitle && item.interviewId !== applied.interviewId" class="interview-filter-link" @click="filterInterview(item.interviewId, item.interviewTitle)">只看这次面试</button></article></div><div v-if="cursors.length || list.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!cursors.length || list.isFetching.value" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!list.data.value?.nextCursor || list.isFetching.value" @click="next">下一页</NButton></div></template></aside>
    <RecollectionEditor v-if="!routeProblem && (creating || selectedId)" :key="editorKey" :id="creating ? undefined : selectedId" :event-id="creating ? eventId : undefined" :event-revision="creating ? eventRevision : undefined" :disabled="!readable" @saved="saved" @close="close" />
    <section v-else class="recollection-welcome"><h2>先记录你还记得的</h2><p>一个问题、一段回答，或一句不太确定的反馈。记不清的部分可以直接写下来，不必急着补成完整答案。</p><NButton type="primary" :disabled="!readable" @click="create">记录一个问题</NButton><div class="welcome-notes"><p>明确没有旧回答的记录也能保存，不会被当作完整作答或进入双答案 AI 比较。</p><p>每次补记产生新版本。整理为题目需要另行审阅，回忆的回答与反馈不会自动成为参考答案。</p></div></section>
  </div>
</template>

<style scoped>
.recollection-filters, .route-error, .recollections-workspace { max-width: 1280px; margin-left: auto; margin-right: auto; }
.recollection-filters { display: grid; grid-template-columns: minmax(120px, .65fr) minmax(180px, 1.1fr) minmax(180px, 1.1fr) auto; align-items: end; gap: 12px; padding: 17px 0; margin-bottom: 22px; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }.recollection-filters label { display: grid; gap: 7px; min-width: 0; color: var(--muted); font-size: 12px; }.filter-help { grid-column: 1 / -1; }.route-error { margin-bottom: 18px; }
.recollections-workspace { display: grid; grid-template-columns: 285px minmax(0, 1fr); min-height: 620px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.recollection-catalog { min-width: 0; padding: 18px 14px; border-right: 1px solid var(--line); background: var(--surface-soft); border-radius: 8px 0 0 8px; }.recollection-catalog > header { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 12px; }.recollection-catalog h2 { font-size: 15px; }.recollection-catalog > .field-help { overflow-wrap: anywhere; }
.recollection-list { display: grid; gap: 5px; margin: 17px 0; }.recollection-list > article { min-width: 0; padding: 11px 9px; border-radius: 5px; }.recollection-list > article:hover, .recollection-list > article.selected { background: var(--surface-deep); }.recollection-select { display: grid; gap: 7px; width: 100%; text-align: left; overflow-wrap: anywhere; }.recollection-select strong { font-size: 13px; }.recollection-select span, .recollection-select small { color: var(--muted); font-size: 12px; }.interview-filter-link { margin-top: 9px; color: var(--brand); text-align: left; font-size: 12px; }
.recollection-welcome { display: grid; gap: 19px; justify-items: start; align-self: center; max-width: 710px; padding: 42px 42px; }.recollection-welcome h2 { font-size: 25px; }.recollection-welcome > p, .welcome-notes { color: var(--muted); }.welcome-notes { display: grid; gap: 12px; margin-top: 10px; padding-top: 20px; border-top: 1px solid var(--line); font-size: 13px; }
@media (max-width: 1050px) { .recollections-workspace { grid-template-columns: 235px minmax(0, 1fr); }.recollection-filters { grid-template-columns: repeat(3, minmax(0, 1fr)); }.recollection-filters > .row-actions { grid-column: 1 / -1; } }
@media (max-width: 760px) { .recollections-workspace { grid-template-columns: 1fr; }.recollection-catalog { border-right: 0; border-bottom: 1px solid var(--line); border-radius: 8px 8px 0 0; }.recollection-list { max-height: 280px; overflow-y: auto; }.recollection-filters { grid-template-columns: 1fr 1fr; }.recollection-filters > label:last-of-type { grid-column: 1 / -1; }.recollection-welcome { padding: 34px 22px; } }
</style>

<style scoped>
.recollection-filter-disclosure { margin-bottom: 16px; }.recollection-filter-disclosure > summary { cursor: pointer; color: var(--muted); font-size: 13px; }.recollections-workspace { max-width: none; min-height: 480px; border-radius: 10px; }.recollection-catalog { padding: 16px 12px; }.recollection-catalog h2 { font-size: 15px; }.recollection-list { max-height: 520px; overflow-y: auto; }.recollection-select strong { font-size: 14px; }.recollection-select :is(span,small) { font-size: 12px; }.recollection-list > article { padding: 11px 10px; border-radius: 6px; }.recollections-workspace.focused { display: block; }.recollections-workspace.focused > .recollection-catalog { display: none; }.recollection-welcome { align-self: start; padding: 34px; }.recollection-welcome h2 { font-size: 21px; }
@media(max-width:760px) { .recollection-list { max-height: 230px; }.recollection-welcome { padding: 24px 18px; } }
</style>
