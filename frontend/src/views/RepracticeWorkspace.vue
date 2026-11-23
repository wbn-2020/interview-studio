<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import { queryClient } from '../api/queryClient'
import type { Repractice, RepracticePage, RepracticeSummary } from '../api/repractice'
import { repracticeBaselineLabels, repracticeModeLabels, repracticeSummaryBaselineKind, repracticeSummaryValid, repracticeValid } from '../api/repractice'
import type { ReviewAnswerLink } from '../api/review'
import { reviewDate, reviewIdValid } from '../api/review'
import { trainingStatusLabels } from '../api/training'
import { useWorkspaceStore } from '../state/workspace'
import { useRecentVisit } from '../composables/useRecentVisit'
import { useTrainingAccessStore } from '../state/trainingAccess'
import RepracticeCreate from '../components/review/RepracticeCreate.vue'
import RepracticeDetail from '../components/review/RepracticeDetail.vue'
import RepracticeComparison from '../components/review/RepracticeComparison.vue'
import RecollectionRepracticeCreate from '../components/review/RecollectionRepracticeCreate.vue'

const route = useRoute(), router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore()
const cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const currentDetail = shallowRef<Repractice>()
let alive = true, generation = 0
const queryText = (key: string) => typeof route.query[key] === 'string' ? route.query[key] as string : undefined
const item = computed(() => queryText('item')), comparisonId = computed(() => queryText('comparison')), actionId = computed(() => queryText('actionId'))
const judgmentId = computed(() => queryText('judgment')), judgmentSourceId = computed(() => queryText('sourceId'))
const judgmentRevision = computed(() => {
  const value = queryText('judgmentRevision')
  return value && /^[1-9]\d{0,3}$/.test(value) && Number(value) <= 1000 ? Number(value) : undefined
})
const recollectionId = computed(() => queryText('recollectionId')), versionId = computed(() => queryText('versionId'))
const baseline = computed<ReviewAnswerLink | undefined>(() => {
  const sessionId = queryText('sessionId'), questionId = queryText('questionId'), answerId = queryText('answerId')
  return reviewIdValid(sessionId) && reviewIdValid(questionId) && reviewIdValid(answerId) ? { sessionId, questionId, answerId } : undefined
})
const routeProblem = computed(() => {
  for (const key of ['item', 'comparison', 'actionId', 'sessionId', 'questionId', 'answerId', 'recollectionId', 'versionId', 'judgment', 'sourceId']) if (route.query[key] !== undefined && !reviewIdValid(route.query[key])) return '复练链接包含无效标识，请返回复盘重新选择。'
  if (['judgment', 'sourceId', 'judgmentRevision'].some(key => route.query[key] !== undefined)
    && (!comparisonId.value || !judgmentId.value || !judgmentSourceId.value || judgmentRevision.value === undefined)) return '历史判断链接需要完整的对照、来源、判断与修订身份。'
  const count = ['sessionId', 'questionId', 'answerId'].filter(key => route.query[key] !== undefined).length
  const recollectionCount = ['recollectionId', 'versionId'].filter(key => route.query[key] !== undefined).length
  if (count && recollectionCount) return '一次复练只能选择正式原答或回忆版本，请从对应来源重新进入。'
  if (recollectionCount === 1) return '回忆链接需要完整的回忆与版本标识。'
  if (count !== 0 && count !== 3) return '原答链接需要完整的训练、题目和提交标识。'
  return (item.value && comparisonId.value) || ((item.value || comparisonId.value) && (count || recollectionCount || actionId.value)) ? '复练链接混用了记录与创建入口，请重新选择。' : ''
})
const readable = computed(() => store.sessionActive && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && !routeProblem.value)
const recordRecentVisit = useRecentVisit('REPRACTICE', item, readable)
const creating = computed(() => !item.value && !comparisonId.value && Boolean(baseline.value || actionId.value || (recollectionId.value && versionId.value)))
const createKey = computed(() => `${baseline.value?.sessionId ?? ''}:${baseline.value?.questionId ?? ''}:${baseline.value?.answerId ?? ''}:${recollectionId.value ?? ''}:${versionId.value ?? ''}:${actionId.value ?? ''}`)
const list = useQuery({ queryKey: computed(() => ['repractice', store.sessionEpoch, 'list', cursor.value]), enabled: readable, queryFn: async () => {
  const epoch = store.sessionEpoch, selected = cursor.value, stamp = generation
  const value = await read<RepracticePage>(`/review/repractices?limit=10${selected ? `&cursor=${selected}` : ''}`)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation || selected !== cursor.value) throw changed()
  if (!value || !Array.isArray(value.items) || value.items.length > 10 || value.items.some(row => !repracticeSummaryValid(row))
    || new Set(value.items.map(row => row.id)).size !== value.items.length || (value.nextCursor !== null && !reviewIdValid(value.nextCursor))) throw new ApiError(502, 'REVIEW_REPRACTICE_MISMATCH', '复练列表格式不一致。')
  return value
} })
const detail = useQuery({ queryKey: computed(() => ['repractice', store.sessionEpoch, 'detail', item.value]), enabled: computed(() => readable.value && Boolean(item.value) && !comparisonId.value), queryFn: async () => {
  const epoch = store.sessionEpoch, id = item.value!, stamp = generation
  const value = await read<Repractice>(`/review/repractices/${id}`)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation || id !== item.value) throw changed()
  if (value.id !== id || !repracticeValid(value)) throw new ApiError(502, 'REVIEW_REPRACTICE_MISMATCH', '复练详情与所选记录不一致。')
  return value
} })
// 重新登录会更换查询键，保留已挂载详情才能恢复原请求与未决回执。
watch([detail.data, detail.dataUpdatedAt, item, readable, () => store.sessionEpoch], () => {
  if (!alive || !readable.value || !item.value) return
  const value = queryClient.getQueryData<Repractice>(['repractice', store.sessionEpoch, 'detail', item.value])
  if (value?.id === item.value && repracticeValid(value)) {
    currentDetail.value = value
    recordRecentVisit(value.id)
  }
}, { immediate: true })
watch([readable, () => store.sessionEpoch, item, comparisonId, judgmentId, judgmentSourceId, judgmentRevision, createKey], () => { generation++ }, { flush: 'sync' })
onUnmounted(() => { alive = false; generation++ })
function changed() { return new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '复练读取条件已变化。') }
function baselineLabel(value: RepracticeSummary) { const kind = repracticeSummaryBaselineKind(value); return kind ? repracticeBaselineLabels[kind] : '基线待核对' }
function open(id: string) { if (readable.value) void router.push({ name: 'repractice', query: { item: id } }) }
function compare(id: string) { if (readable.value) void router.push({ name: 'repractice', query: { comparison: id } }) }
function back() { if (readable.value) void router.push({ name: 'review' }) }
function nextPage() { if (readable.value && !list.isFetching.value && list.data.value?.nextCursor) { previous.value.push(cursor.value); cursor.value = list.data.value.nextCursor } }
</script>

<template>
  <main class="repractice-workspace">
    <header class="workspace-heading"><div><h1>{{ comparisonId ? '新旧对照' : '独立复练' }}</h1><p class="field-help">保留固定基线、新答与每条来源的验证条件。</p></div><NButton :disabled="!readable" @click="back">查看固定来源</NButton></header>
    <p v-if="routeProblem" class="notice error" role="alert">{{ routeProblem }}</p><p v-else-if="!readable" class="notice">当前登录或独立训练条件限制了复盘资料访问。已有输入保留，结束训练或明确确认辅助后再继续。</p>
    <div v-show="readable" :inert="!readable || undefined" class="workspace-content">
      <RepracticeComparison v-if="comparisonId && reviewIdValid(comparisonId)" :key="comparisonId" :comparison-id="comparisonId" :selected-judgment-id="judgmentId" :selected-source-id="judgmentSourceId" :selected-judgment-revision="judgmentRevision" :disabled="!readable" />
      <div v-else class="workspace-columns"><aside class="repractice-list"><header><h2>复练记录</h2><NButton text size="small" :disabled="list.isFetching.value || !readable" @click="list.refetch()">刷新</NButton></header><p v-if="list.isPending.value" role="status" class="field-help">正在读取复练…</p><p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}</p><p v-if="list.data.value && !list.data.value.items.length" class="field-help">还没有复练。从已结束训练的原答、当前回忆版本或改进动作开始。</p><button v-for="entry in list.data.value?.items ?? []" :key="entry.id" class="repractice-row" :class="{ selected: item === entry.id }" :aria-current="item === entry.id ? 'page' : undefined" @click="open(entry.id)"><strong>{{ entry.title }}</strong><span>{{ baselineLabel(entry) }} · {{ trainingStatusLabels[entry.status] }}</span><span>{{ repracticeModeLabels[entry.questionMode] }} · {{ entry.sourceCount }} 条来源</span><time>{{ reviewDate(entry.createdAt) }}</time></button><div class="row-actions"><NButton size="small" :disabled="list.isFetching.value || !previous.length" @click="cursor = previous.pop()">上一页</NButton><NButton size="small" :disabled="list.isFetching.value || !list.data.value?.nextCursor" @click="nextPage">下一页</NButton></div></aside><section class="repractice-main">
        <RecollectionRepracticeCreate v-if="creating && recollectionId && versionId" :key="createKey" :recollection-id="recollectionId" :version-id="versionId" :action-id="actionId" :disabled="!readable" @created="open($event.id)" />
        <RepracticeCreate v-else-if="creating" :key="createKey" :baseline="baseline" :action-id="actionId" :disabled="!readable" @created="open($event.id)" />
        <template v-else-if="item"><p v-if="detail.isPending.value" role="status">正在读取这次复练…</p><p v-if="detail.error.value" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text :disabled="!readable || detail.isFetching.value" @click="detail.refetch()">重新读取</NButton></p><RepracticeDetail v-if="currentDetail?.id === item" :key="item" :value="currentDetail" :disabled="!readable || !!detail.error.value" @comparison="compare" @refresh="detail.refetch()" /></template>
        <div v-else class="empty-state"><h2>从一个具体问题开始复练</h2><p>在复盘里选正式原答、当前回忆版本，或从动作中选择要验证的同一基线来源。新尝试从空白文字和图开始。</p><NButton @click="back">选择基线或动作</NButton></div>
      </section></div>
    </div>
  </main>
</template>

<style scoped>
.repractice-workspace { display: grid; gap: 28px; max-width: 1560px; margin: 0 auto; padding: 34px 36px 64px; } .workspace-heading { display: flex; align-items: start; justify-content: space-between; gap: 24px; } h1 { margin: 8px 0 12px; font-size: clamp(25px, 3vw, 34px); letter-spacing: -.04em; } p { margin: 0; } .workspace-columns { display: grid; grid-template-columns: minmax(210px, 270px) minmax(0, 1fr); gap: 32px; }
.repractice-list { display: grid; align-content: start; gap: 12px; padding-right: 24px; border-right: 1px solid var(--line); } .repractice-list header { display: flex; justify-content: space-between; align-items: center; } .repractice-list h2 { font-size: 16px; margin: 0; } .repractice-row { display: grid; gap: 8px; padding: 14px; text-align: left; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); cursor: pointer; } .repractice-row.selected { border-color: var(--brand); background: var(--surface-soft); } .repractice-row strong { font-size: 14px; overflow-wrap: anywhere; } .repractice-row span, .repractice-row time { font-size: 11px; color: var(--muted); } .repractice-row:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; } .repractice-main { min-width: 0; } .row-actions { display: flex; gap: 8px; flex-wrap: wrap; } .empty-state { padding: 40px 20px; display: grid; justify-items: start; gap: 18px; max-width: 62ch; } .empty-state h2 { margin: 0; font-size: 24px; } .empty-state p { color: var(--muted); line-height: 1.8; }
@media (max-width: 1000px) { .workspace-columns { grid-template-columns: 1fr; } .repractice-list { border-right: 0; border-bottom: 1px solid var(--line); padding: 0 0 24px; } .repractice-workspace { padding: 24px 20px 48px; } }
@media (max-width: 600px) { .workspace-heading { flex-wrap: wrap; } .repractice-workspace { padding: 20px 14px 40px; } }
</style>

<style scoped>
.repractice-workspace { padding: 0; max-width: none; gap: 20px; }.workspace-heading h1 { margin: 0 0 6px; font-size: 26px; line-height: 1.35; }.workspace-heading > p { font-size: 13px; color: var(--muted); }.repractice-list { border: 1px solid var(--line); border-radius: 10px; padding: 18px; background: var(--surface); }.repractice-list h2 { font-size: 16px; }.repractice-row { padding: 12px; border-radius: 6px; }.repractice-row strong { font-size: 14px; }.repractice-row :is(span,time) { font-size: 12px; }.repractice-main { border: 1px solid var(--line); border-radius: 10px; background: var(--surface); padding: 22px; }
@media(max-width:600px) { .workspace-heading h1 { font-size: 24px; }.repractice-main { padding: 18px 14px; } }
</style>
