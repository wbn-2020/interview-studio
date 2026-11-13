<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import type { AiComparisonDraft } from '../../api/aiComparison'
import type { RepracticeComparison } from '../../api/repractice'
import { repracticeBaselineKind, repracticeComparisonValid, repracticeJudgeProblem, repracticeModeLabels } from '../../api/repractice'
import { reviewDate, reviewIdValid } from '../../api/review'
import { useWorkspaceStore } from '../../state/workspace'
import RepracticeJudgmentEditor from './RepracticeJudgmentEditor.vue'
import RepracticeSideReadout from './RepracticeSideReadout.vue'
import RepracticeAiComparison from './RepracticeAiComparison.vue'
import RecollectionBaselineReadout from './RecollectionBaselineReadout.vue'
import RepracticeSelectedJudgment from './RepracticeSelectedJudgment.vue'

const props = defineProps<{ comparisonId: string; selectedJudgmentId?: string; selectedSourceId?: string; selectedJudgmentRevision?: number; disabled?: boolean }>()
const store = useWorkspaceStore()
const router = useRouter()
const current = shallowRef<RepracticeComparison>()
const candidateDrafts = ref<Record<string, AiComparisonDraft>>({})
const fillMessage = ref('')
let alive = true
let generation = 0
const readable = computed(() => store.sessionActive && !props.disabled)
const key = computed(() => ['repractice', store.sessionEpoch, 'comparison', props.comparisonId])
const comparison = useQuery({ queryKey: key, enabled: readable, queryFn: async () => {
  const id = props.comparisonId, epoch = store.sessionEpoch, stamp = generation
  const value = await read<RepracticeComparison>(`/review/comparisons/${id}`)
  if (!alive || !readable.value || stamp !== generation || epoch !== store.sessionEpoch || id !== props.comparisonId) throw new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '对照的读取条件已变化。')
  if (!repracticeComparisonValid(value, id)) throw new ApiError(502, 'REVIEW_COMPARISON_MISMATCH', '对照内容与确切提交不一致，请重新读取。')
  return value
} })
watch([comparison.data, comparison.dataUpdatedAt, readable, () => props.comparisonId, () => store.sessionEpoch], () => {
  if (!alive || !readable.value) return
  const value = queryClient.getQueryData<RepracticeComparison>(key.value)
  if (value && repracticeComparisonValid(value, props.comparisonId)) current.value = value
}, { immediate: true })
watch([readable, () => store.sessionEpoch, () => props.comparisonId], () => { generation++ }, { flush: 'sync' })
onUnmounted(() => { alive = false; generation++ })
function refresh() { if (readable.value) void comparison.refetch() }
function openRepractice() { if (current.value && readable.value) void router.push({ name: 'repractice', query: { item: current.value.repractice.id } }) }
function proposeDraft(draft: AiComparisonDraft) {
  const value = current.value
  if (!readable.value || comparison.isFetching.value || comparison.error.value || !value || repracticeBaselineKind(value.repractice) !== 'ANSWER'
    || draft.comparisonId !== value.comparison.id || draft.answerId !== value.current.answer.answerId
    || !reviewIdValid(draft.taskId) || !value.repractice.sources.some(source => source.sourceId === draft.sourceId)) return
  try { if (repracticeJudgeProblem(draft.outcome, draft.reason, draft.citations, value.current)) return } catch { return }
  candidateDrafts.value[draft.sourceId] = { ...draft, citations: draft.citations.map(citation => ({ ...citation })) }
  fillMessage.value = '建议已放在对应来源下方，请核对后选择“填入本条判断草稿”。现有判断输入保持原样。'
  void nextTick(() => { if (alive && readable.value && current.value?.comparison.id === draft.comparisonId) document.getElementById(`repractice-source-${draft.sourceId}`)?.scrollIntoView({ block: 'nearest' }) })
}
function resolveCandidate(sourceId: string, taskId: string) { if (candidateDrafts.value[sourceId]?.taskId === taskId) delete candidateDrafts.value[sourceId] }
</script>

<template>
  <section class="repractice-comparison" aria-label="固定基线与新答对照">
    <p v-if="!readable" class="notice">访问条件变化，对照暂时遮挡，未保存的判断继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="comparison-content">
      <p v-if="comparison.isPending.value && !current" role="status">正在读取固定基线与确切新提交…</p>
      <p v-if="comparison.error.value" class="notice error" role="alert">{{ errorText(comparison.error.value) }}<NButton text :disabled="!readable || comparison.isFetching.value" @click="refresh">重新读取</NButton></p>
      <template v-if="current && current.comparison.id === comparisonId">
        <RepracticeSelectedJudgment v-if="selectedJudgmentId && selectedSourceId && selectedJudgmentRevision !== undefined" :comparison="current" :judgment-id="selectedJudgmentId" :source-id="selectedSourceId" :revision="selectedJudgmentRevision" :disabled="!readable || comparison.isFetching.value || !!comparison.error.value" />
        <header class="comparison-heading"><div><p class="section-label">独立复练对照 · {{ repracticeModeLabels[current.repractice.questionMode] }}</p><h2>{{ current.repractice.session.title }}</h2><p class="field-help">对照创建于 {{ reviewDate(current.comparison.createdAt) }}，新答固定为第 {{ current.comparison.answerNumber }} 版提交。</p></div><div class="row-actions"><NButton text :disabled="comparison.isFetching.value" @click="refresh">重读来源进度</NButton><NButton text @click="openRepractice">返回这次复练</NButton></div></header>
        <div class="comparison-triptych">
        <section class="condition-differences" aria-label="作答条件说明"><h3>条件变化</h3><template v-if="repracticeBaselineKind(current.repractice) === 'ANSWER'"><p class="field-help">同题也可能有曝光和辅助差异。下面记录事实，不自动计算提升分数。</p><ul><li v-for="difference in current.differences" :key="difference.kind"><span :class="{ changed: difference.changed }">{{ difference.changed ? '有差异' : '无差异' }}</span><p>{{ difference.explanation }}</p></li></ul></template><p v-else class="field-help">旧侧是用户补记的回忆，其作答模式、计时、参考和辅助条件未知。空白差异项不表示“没有差异”；本次正式新答按实际条件展示。</p></section>
        <div class="answer-columns"><RepracticeSideReadout v-if="repracticeBaselineKind(current.repractice) === 'ANSWER' && current.baseline" :side="current.baseline" label="原答 · 固定基线" /><RecollectionBaselineReadout v-else-if="current.repractice.recollectionBaseline" :baseline="current.repractice.recollectionBaseline" /><RepracticeSideReadout :side="current.current" label="新答 · 本次独立尝试" /></div>
        </div>
        <RepracticeAiComparison v-if="repracticeBaselineKind(current.repractice) === 'ANSWER'" :key="`ai:${current.comparison.id}`" :comparison="current" :disabled="!readable || comparison.isFetching.value || !!comparison.error.value" @fill="proposeDraft" @refresh="refresh" />
        <p v-else class="notice">回忆片段与无旧答记录不参加 AI 双答比较。可在本次正式新答的复盘中单独申请评价；下方仍由你结合新答原文记录来源判断。</p>
        <section class="source-judgments"><header><h3>逐条来源判断</h3><p class="field-help">只记录本次选择并实际判断的来源；读过材料或完成一场训练不会自动关闭动作。</p></header><p v-if="fillMessage" class="notice" role="status">{{ fillMessage }}</p><p v-if="!current.repractice.sources.length" class="notice">本次复练没有绑定动作来源，可以阅读基线与新答；系统不会生成虚构动作或自动结论。</p><RepracticeJudgmentEditor v-for="source in current.repractice.sources" :id="`repractice-source-${source.sourceId}`" :key="`${current.comparison.id}:${source.sourceId}`" :comparison-id="current.comparison.id" :source="source" :side="current.current" :progress="current.progress.find(item => item.sourceId === source.sourceId)" :candidate="candidateDrafts[source.sourceId]" :disabled="!readable || comparison.isFetching.value || !!comparison.error.value" @saved="refresh" @refresh="refresh" @candidate-resolved="resolveCandidate(source.sourceId, $event)" /></section>
      </template>
    </div>
  </section>
</template>

<style scoped>
.repractice-comparison, .comparison-content, .source-judgments { display: grid; gap: 24px; min-width: 0; } .comparison-heading { display: flex; align-items: start; justify-content: space-between; flex-wrap: wrap; gap: 18px; } h2 { margin: 6px 0 10px; font-size: 25px; } h3 { margin: 0 0 10px; font-size: 17px; } p { margin: 0; }
.condition-differences { padding: 18px 22px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); } ul { padding: 0; margin: 14px 0 0; list-style: none; display: grid; gap: 10px; } li { display: grid; grid-template-columns: 60px minmax(0, 1fr); gap: 12px; align-items: baseline; font-size: 13px; } li span { color: var(--muted); font-size: 11px; } li span.changed { color: var(--brand-strong); font-weight: 600; }
.answer-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; align-items: start; } .row-actions { display: flex; gap: 10px; flex-wrap: wrap; }
@media (max-width: 1000px) { .answer-columns { grid-template-columns: 1fr; } }
</style>

<style scoped>
.comparison-triptych { display: grid; grid-template-columns: minmax(0,1fr) 288px minmax(0,1.08fr); gap: 20px; align-items: stretch; }.answer-columns { display: contents; }.answer-columns > :first-child { grid-column: 1; grid-row: 1; }.answer-columns > :last-child { grid-column: 3; grid-row: 1; }.condition-differences { grid-column: 2; grid-row: 1; padding: 26px; border-radius: 21px; background: var(--surface); }.condition-differences h3 { font-size: 26px; padding-bottom: 18px; border-bottom: 1px solid var(--line); }.condition-differences ul { gap: 22px; }.condition-differences li { grid-template-columns: 1fr; gap: 8px; font-size: 19px; line-height: 1.7; }.condition-differences li span { font-size: 16px; }.condition-differences li p { padding: 10px 14px; border-radius: 14px; background: #ffffff06; }.comparison-triptych :deep(.comparison-side) { border-radius: 21px; padding: 27px; gap: 22px; }.comparison-triptych :deep(.comparison-side > header) { padding-bottom: 16px; border-bottom: 1px solid var(--line); }.comparison-triptych :deep(.comparison-side h3) { font-size: 25px; }.comparison-triptych :deep(.comparison-side h4) { font-size: 22px; }.comparison-triptych :deep(.comparison-side .markdown-preview) { font-size: 21px; line-height: 1.85; }.comparison-triptych :deep(.condition-tags span) { font-size: 15px; padding: 6px 12px; border-radius: 18px; }.source-judgments { border: 1px solid var(--line); border-radius: 22px; padding: 25px; background: var(--surface); }.source-judgments h3 { font-size: 26px; }
@media(max-width:1250px) { .comparison-triptych { grid-template-columns: minmax(0,1fr) minmax(0,1fr); }.condition-differences { grid-column: 1/-1; grid-row: 2; }.answer-columns > :last-child { grid-column: 2; }.condition-differences ul { grid-template-columns: repeat(2,minmax(0,1fr)); } }@media(max-width:700px) { .comparison-triptych { display: flex; flex-direction: column; }.answer-columns { display: grid; grid-template-columns: 1fr; }.answer-columns > :last-child { grid-column: 1; grid-row: auto; }.condition-differences ul { grid-template-columns: 1fr; } }
</style>
