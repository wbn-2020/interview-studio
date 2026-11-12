<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiEvaluationPage, AiTask } from '../../api/ai'
import type { ReviewAnswerLink, ReviewFeedbackPage, ReviewSourceRequest } from '../../api/review'
import { reviewAnswerKey, reviewAnswerMatches, reviewAnswerQuery, reviewIdValid, reviewKindLabels } from '../../api/review'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ answer: ReviewAnswerLink; disabled?: boolean }>()
const emit = defineEmits<{ add: [source: ReviewSourceRequest] }>()
const store = useWorkspaceStore()
const readable = computed(() => store.sessionActive && !props.disabled)
const manualCursor = ref<string>()
const aiCursor = ref<string>()
const manualCursors = ref<(string | undefined)[]>([])
const aiCursors = ref<(string | undefined)[]>([])
const selected = shallowRef<AiTask>()
const loading = ref(false)
const problem = ref('')
let alive = true
let generation = 0
const manual = useQuery({ queryKey: computed(() => ['review-source-picker', store.sessionEpoch, reviewAnswerKey(props.answer), 'manual', manualCursor.value]), enabled: readable,
  queryFn: async () => {
    const epoch = store.sessionEpoch, answer = { ...props.answer }, cursor = manualCursor.value
    const query = reviewAnswerQuery(answer); query.set('limit', '10'); if (cursor) query.set('cursor', cursor)
    const value = await read<ReviewFeedbackPage>(`/review/feedback?${query}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || !reviewAnswerMatches(answer, props.answer) || cursor !== manualCursor.value) throw changed()
    if (!value || !Array.isArray(value.items) || value.items.some(item => !reviewIdValid(item.id) || !reviewAnswerMatches(item.answer, answer))) throw changed()
    return value
  } })
const ai = useQuery({ queryKey: computed(() => ['review-source-picker', store.sessionEpoch, reviewAnswerKey(props.answer), 'ai', aiCursor.value]), enabled: readable,
  queryFn: async () => {
    const epoch = store.sessionEpoch, answer = { ...props.answer }, cursor = aiCursor.value
    const query = new URLSearchParams({ questionId: answer.questionId, limit: '10' }); if (cursor) query.set('cursor', cursor)
    const value = await read<AiEvaluationPage>(`/training/sessions/${answer.sessionId}/evaluations?${query}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || !reviewAnswerMatches(answer, props.answer) || cursor !== aiCursor.value) throw changed()
    if (!value || !Array.isArray(value.items) || value.items.some(item => item.questionId !== answer.questionId || !reviewIdValid(item.task.id))) throw changed()
    return value
  } })
const reports = computed(() => (ai.data.value?.items ?? []).filter(item => item.answerId === props.answer.answerId && item.task.status === 'SUCCEEDED' && item.task.purpose === 'ANSWER_EVALUATION'))
watch(aiCursor, () => { generation++; loading.value = false; selected.value = undefined }, { flush: 'sync' })
watch([() => reviewAnswerKey(props.answer), () => store.sessionEpoch, readable], () => {
  generation++; loading.value = false; selected.value = undefined; problem.value = ''
  manualCursor.value = undefined; aiCursor.value = undefined; manualCursors.value = []; aiCursors.value = []
}, { flush: 'sync' })
onUnmounted(() => { alive = false; generation++ })
function changed() { return new ApiError(0, 'REVIEW_SOURCE_CHANGED', '来源读取条件已变化，请重新读取。') }
function addManual(id: string, revision: number) {
  if (readable.value) emit('add', { kind: 'MANUAL', feedbackId: id, feedbackRevision: revision, taskId: null, observationId: null, expectedDecisionRevision: 0, verification: '' })
}
async function openReport(id: string) {
  if (!readable.value || loading.value) return
  const epoch = store.sessionEpoch, sequence = ++generation, answer = { ...props.answer }
  loading.value = true; problem.value = ''; selected.value = undefined
  try {
    const task = await read<AiTask>(`/ai/tasks/${id}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || sequence !== generation || !reviewAnswerMatches(answer, props.answer)) return
    if (task?.task?.id !== id || task.task.status !== 'SUCCEEDED' || task.task.purpose !== 'ANSWER_EVALUATION' || !task.evaluationReport || !reviewAnswerMatches(task.manifest.evaluation, answer)) throw changed()
    selected.value = task
  } catch (error) { if (alive && epoch === store.sessionEpoch && sequence === generation) problem.value = errorText(error) }
  finally { if (alive && sequence === generation) loading.value = false }
}
function addAi(id: string) {
  if (readable.value && selected.value) emit('add', { kind: 'AI_EVALUATION', feedbackId: null, feedbackRevision: null, taskId: selected.value.task.id, observationId: id, expectedDecisionRevision: 0, verification: '' })
}
function nextManual() { if (readable.value && !manual.isFetching.value && manual.data.value?.nextCursor) { manualCursors.value.push(manualCursor.value); manualCursor.value = manual.data.value.nextCursor } }
function nextAi() { if (readable.value && !ai.isFetching.value && ai.data.value?.nextCursor) { aiCursors.value.push(aiCursor.value); aiCursor.value = ai.data.value.nextCursor; selected.value = undefined; generation++; loading.value = false } }
</script>

<template>
  <section class="source-picker" aria-label="选择同次提交的反馈来源">
    <p class="field-help">当前选择固定于提交 {{ answer.answerId }}。加入前会读取确切观察和最新已保存决定；历史反馈版本可从复盘历史入口加入。</p>
    <section><h4>自检与手工意见</h4><p v-if="manual.isPending.value" role="status">正在读取反馈…</p><p v-if="manual.error.value" class="notice error" role="alert">{{ errorText(manual.error.value) }}<NButton text :disabled="!readable" @click="manual.refetch()">重读反馈</NButton></p><p v-if="manual.data.value && !manual.data.value.items.length" class="field-help">这次提交还没有人工反馈。</p>
      <article v-for="item in manual.data.value?.items ?? []" :key="item.id"><div><p>{{ item.observation }}</p><span class="field-help">{{ reviewKindLabels[item.kind] }} · 修订 {{ item.revision }}</span></div><NButton size="small" :disabled="!readable" @click="addManual(item.id, item.revision)">加入</NButton></article>
      <div class="row-actions"><NButton size="small" :disabled="!readable || manual.isFetching.value || !manualCursors.length" @click="manualCursor = manualCursors.pop()">上一页</NButton><NButton size="small" :disabled="!readable || manual.isFetching.value || !manual.data.value?.nextCursor" @click="nextManual">下一页</NButton></div>
    </section>
    <section><h4>AI 未审报告</h4><p v-if="ai.isPending.value" role="status">正在读取已有报告…</p><p v-if="ai.error.value" class="notice error" role="alert">{{ errorText(ai.error.value) }}<NButton text :disabled="!readable" @click="ai.refetch()">重读报告</NButton></p><p v-if="ai.data.value && !reports.length" class="field-help">本页没有属于这次提交的成功报告{{ ai.data.value.nextCursor ? '，可以继续翻页查看' : '' }}。</p>
      <article v-for="item in reports" :key="item.task.id"><div><p>第 {{ item.answerNumber }} 版提交 · AI 评价</p><span class="field-help">{{ item.task.id }}</span></div><NButton size="small" :disabled="!readable || loading" @click="openReport(item.task.id)">选择观察</NButton></article>
      <div class="row-actions"><NButton size="small" :disabled="!readable || ai.isFetching.value || !aiCursors.length" @click="aiCursor = aiCursors.pop(); selected = undefined">上一页</NButton><NButton size="small" :disabled="!readable || ai.isFetching.value || !ai.data.value?.nextCursor" @click="nextAi">下一页</NButton></div>
      <p v-if="loading" role="status">正在读取确切报告…</p><p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
      <article v-for="item in selected?.evaluationReport?.observations ?? []" :key="item.id"><div><p>{{ item.observation }}</p><span class="field-help">{{ item.id }} · AI 未审</span></div><NButton size="small" :disabled="!readable" @click="addAi(item.id)">加入此项</NButton></article>
    </section>
  </section>
</template>

<style scoped>
.source-picker, section { display: grid; gap: 12px; min-width: 0; } .source-picker { padding: 16px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); } h4, p { margin: 0; } h4 { font-size: 14px; } article { display: flex; gap: 14px; align-items: start; justify-content: space-between; padding: 10px 0; border-top: 1px solid var(--line); } article > div { min-width: 0; } article p { font-size: 13px; white-space: pre-wrap; overflow-wrap: anywhere; margin-bottom: 5px; } .row-actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
