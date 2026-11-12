<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { NButton, NCheckbox, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiEvaluationAccess } from '../../api/ai'
import type { Repractice, RepracticeComparisonSummary } from '../../api/repractice'
import { comparisonSummaryValid, repracticeBaselineKind, repracticeBaselineLabels, repracticeConditionLabels, repracticeModeLabels } from '../../api/repractice'
import { reviewAnswerMatches, reviewDate, reviewIdValid } from '../../api/review'
import type { TrainingAnswerPage, TrainingSession } from '../../api/training'
import { notifyTrainingChanged, trainingStatusLabels } from '../../api/training'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import RecollectionBaselineReadout from './RecollectionBaselineReadout.vue'

const props = defineProps<{ value: Repractice; disabled?: boolean }>()
const emit = defineEmits<{ comparison: [id: string]; refresh: [] }>()
const store = useWorkspaceStore(), router = useRouter(), writer = useCommand()
const answerId = ref<string | null>(null), before = ref<number>(), previous = ref<(number | undefined)[]>([])
const accessConfirmed = ref(false), compareConfirmed = ref(false), receiptMismatch = ref(false), message = ref('')
const receiptCommand = shallowRef<NonNullable<typeof writer.pending.value>>()
const deferred = shallowRef<() => void>(), created = shallowRef<RepracticeComparisonSummary>()
const state = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, commandEpoch = store.sessionEpoch
const readable = computed(() => store.sessionActive && !props.disabled)
const locked = computed(() => writer.locked.value || Boolean(deferred.value) || (receiptMismatch.value && Boolean(receiptCommand.value)))
const blocked = computed(() => !readable.value || locked.value || receiptMismatch.value)
const ended = computed(() => props.value.session.status === 'ENDED')
const baselineKind = computed(() => repracticeBaselineKind(props.value))
const staleDraft = computed(() => props.value.session.status === 'DRAFT' && baselineKind.value !== 'ANSWER' && props.value.recollectionBaseline?.state !== 'CURRENT')
const existing = computed(() => props.value.comparisons.find(item => item.answerId === answerId.value))
const selectedAnswer = computed(() => answers.data.value?.items.find(item => item.id === answerId.value))
const answerLink = computed(() => answerId.value ? { sessionId: props.value.sessionId, questionId: props.value.questionId, answerId: answerId.value } : undefined)
const path = computed(() => `/training/sessions/${props.value.sessionId}/questions/${props.value.questionId}`)
const session = useQuery({ queryKey: computed(() => ['repractice', store.sessionEpoch, 'new-session', props.value.sessionId]), enabled: computed(() => readable.value && ended.value), queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, id = props.value.sessionId
  const value = await read<TrainingSession>(`/training/sessions/${id}`)
  requireCurrent(epoch, stamp)
  if (!value || value.id !== id || id !== props.value.sessionId || value.status !== 'ENDED' || !Number.isSafeInteger(value.revision) || value.revision < 0) throw changed()
  return value
} })
const answers = useQuery({ queryKey: computed(() => ['repractice', store.sessionEpoch, 'new-answers', props.value.sessionId, props.value.questionId, before.value]), enabled: computed(() => readable.value && ended.value), queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, source = path.value, cursor = before.value
  const value = await read<TrainingAnswerPage>(`${source}/answers?limit=20${cursor === undefined ? '' : `&before=${cursor}`}`)
  requireCurrent(epoch, stamp)
  if (source !== path.value || cursor !== before.value) throw changed()
  if (!value || !Array.isArray(value.items) || value.items.length > 20 || value.items.some(item => !reviewIdValid(item.id) || !Number.isSafeInteger(item.number) || item.number < 1
    || (cursor !== undefined && item.number >= cursor) || !Object.hasOwn(repracticeConditionLabels, item.condition) || typeof item.createdAt !== 'string')
    || new Set(value.items.map(item => item.id)).size !== value.items.length || new Set(value.items.map(item => item.number)).size !== value.items.length
    || (value.nextCursor !== null && (!Number.isSafeInteger(value.nextCursor) || value.nextCursor < 1 || value.items.at(-1)?.number !== value.nextCursor))) throw new ApiError(502, 'REVIEW_ANSWERS_MISMATCH', '新答列表与所选分页不一致，请重新读取。')
  return value
} })
const accessKey = computed(() => ['repractice', store.sessionEpoch, 'comparison-access', props.value.id, answerId.value])
const access = useQuery({ queryKey: accessKey, enabled: computed(() => readable.value && ended.value && Boolean(answerLink.value)), queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, source = answerLink.value!
  const value = await read<AiEvaluationAccess>(`${path.value}/evaluations/access?answerId=${source.answerId}`)
  requireCurrent(epoch, stamp)
  if (!value || typeof value.allowed !== 'boolean' || !reviewAnswerMatches(value, source) || !reviewAnswerMatches(source, answerLink.value)) throw changed()
  return value
} })
const allowed = computed(() => Boolean(readable.value && access.data.value?.allowed && reviewAnswerMatches(access.data.value, answerLink.value)))
watch([accessConfirmed, compareConfirmed, locked], ([first, second, waiting]) => { state.dirty = first || second; state.locked = waiting; store.changes.set('repractice-comparison-create', state) }, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => { generation++; accessConfirmed.value = false; compareConfirmed.value = false }, { flush: 'sync' })
watch(answerId, () => { accessConfirmed.value = false; compareConfirmed.value = false; message.value = '' }, { flush: 'sync' })
watch(writer.error, error => { if (error instanceof ApiError && !writer.pending.value) { accessConfirmed.value = false; compareConfirmed.value = false; if (error.status === 409) emit('refresh') } })
onUnmounted(() => { alive = false; generation++; if (store.changes.get('repractice-comparison-create') === state) store.changes.delete('repractice-comparison-create') })
function changed() { return new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '复练读取条件已变化。') }
function requireCurrent(epoch: number, stamp: number) { if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation) throw changed() }
function selectAnswer(value: string) { if (!blocked.value && store.canLeave(['repractice-comparison-create'])) answerId.value = value }
function continueTraining() { if (!blocked.value && !staleDraft.value) void router.push({ name: 'training', query: { item: props.value.sessionId } }) }
function grantAccess() {
  const link = answerLink.value, current = session.data.value
  if (blocked.value || !ended.value || !link || !current || current.status !== 'ENDED' || !selectedAnswer.value || !accessConfirmed.value) return
  const id = props.value.id, source = path.value; commandEpoch = store.sessionEpoch
  let originalCommand: typeof writer.pending.value
  void writer.run<AiEvaluationAccess>(`${source}/evaluations/access`, 'POST', { answerId: link.answerId, expectedSessionRevision: current.revision, confirmed: true, earlyFeedbackConfirmed: false }, value => {
    if (!alive) return
    if (value?.allowed !== true || !reviewAnswerMatches(value, link)) { receiptCommand.value = originalCommand; receiptMismatch.value = true; writer.error.value = new ApiError(502, 'REVIEW_ACCESS_MISMATCH', '访问确认回执与本次新答不一致，请用原请求核对。'); return }
    const apply = () => { receiptMismatch.value = false; receiptCommand.value = undefined; writer.error.value = undefined; queryClient.setQueryData(['repractice', store.sessionEpoch, 'comparison-access', id, link.answerId], value); accessConfirmed.value = false; notifyTrainingChanged(); message.value = '已确认打开这次新答；访问状态恢复后可创建对照。' }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = apply
    else apply()
  })
  originalCommand = writer.pending.value
}
function compare() {
  const selected = selectedAnswer.value
  if (blocked.value || !ended.value || !allowed.value || !compareConfirmed.value || !selected || !answerId.value || existing.value || props.value.comparisons.length >= 20) return
  const id = props.value.id, exactAnswerId = answerId.value, answerNumber = selected.number
  commandEpoch = store.sessionEpoch
  let originalCommand: typeof writer.pending.value
  void writer.run<RepracticeComparisonSummary>(`/review/repractices/${id}/comparisons`, 'POST', { answerId: exactAnswerId }, value => {
    if (!alive) return
    if (!comparisonSummaryValid(value) || value.repracticeId !== id || value.answerId !== exactAnswerId || value.answerNumber !== answerNumber) {
      receiptCommand.value = originalCommand; receiptMismatch.value = true; writer.error.value = new ApiError(502, 'REVIEW_COMPARISON_MISMATCH', '对照回执与本次选中提交不一致，请用原请求核对已保存结果。'); emit('refresh'); return
    }
    const apply = () => { receiptMismatch.value = false; receiptCommand.value = undefined; writer.error.value = undefined; compareConfirmed.value = false; created.value = value; void queryClient.invalidateQueries({ queryKey: ['repractice'] }); emit('refresh'); emit('comparison', value.id) }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = apply
    else apply()
  })
  originalCommand = writer.pending.value
}
function retry() {
  if (!readable.value || writer.busy.value) return
  // 回执核对继续使用原命令，禁止把已存在的对照再次作为新创建发送。
  if (receiptMismatch.value && receiptCommand.value) writer.pending.value = receiptCommand.value
  commandEpoch = store.sessionEpoch; void writer.retry()
}
function receive() { if (readable.value && deferred.value) { const apply = deferred.value; deferred.value = undefined; apply() } }
function refresh() { if (!readable.value) return; emit('refresh'); void session.refetch(); void answers.refetch(); if (answerId.value) void access.refetch() }
function nextPage() { if (!blocked.value && !answers.isFetching.value && answers.data.value?.nextCursor && store.canLeave(['repractice-comparison-create'])) { previous.value.push(before.value); before.value = answers.data.value.nextCursor; answerId.value = null } }
function previousPage() { if (!blocked.value && !answers.isFetching.value && previous.value.length && store.canLeave(['repractice-comparison-create'])) { before.value = previous.value.pop(); answerId.value = null } }
</script>

<template>
  <section class="repractice-detail" aria-label="复练记录">
    <header><p class="section-label">{{ baselineKind ? repracticeBaselineLabels[baselineKind] : '基线待核对' }} · {{ repracticeModeLabels[value.questionMode] }} · {{ trainingStatusLabels[value.session.status] }}</p><h2>{{ value.session.title }}</h2><p class="field-help">创建于 {{ reviewDate(value.createdAt) }} · 一道新题 · {{ value.sources.length }} 条固定来源</p></header>
    <p v-if="staleDraft" class="notice">这份回忆已补记或归档，当前草稿不能开始。请从使用中的当前回忆版本重新创建复练；本条记录继续保留。</p>
    <div class="row-actions"><NButton type="primary" :disabled="blocked || staleDraft" @click="continueTraining">{{ ended ? '查看本次训练记录' : value.session.status === 'DRAFT' ? '核对条件并开始空白作答' : '继续本次训练' }}</NButton><NButton text :disabled="blocked" @click="refresh">刷新记录</NButton></div>
    <RecollectionBaselineReadout v-if="value.recollectionBaseline" :baseline="value.recollectionBaseline" />
    <p v-if="!ended" class="notice">本次答案尚未进入结束后的对照阶段。独立作答时隐藏旧答、参考和动作依据；结束并保留真实提交后再返回。</p>
    <details v-if="value.sources.length"><summary>查看本次固定的验证条件</summary><article v-for="source in value.sources" :key="source.sourceId" class="fixed-source"><h3>{{ source.actionTitle }}</h3><p>{{ source.source.verification }}</p><p class="field-help">固定于动作修订 {{ source.actionRevision }}；后来的修改、合并或停止跟踪不会改写本次依据。</p></article></details>
    <section v-if="ended" class="compare-create"><h3>选择本次要对照的新答</h3><p class="field-help">选择真实提交版本，系统不会自动替换为最近一版；每次对照保留固定基线和确切新答。</p><p v-if="answers.isPending.value" role="status">正在读取本次提交…</p><p v-if="session.error.value || answers.error.value || access.error.value" class="notice error" role="alert">{{ errorText(session.error.value || answers.error.value || access.error.value) }}<NButton text :disabled="blocked" @click="refresh">重读</NButton></p><p v-if="answers.data.value && !answers.data.value.items.length" class="notice">本场没有已提交答案，不能创建对照或判断改进。未提交草稿仍可从训练记录查看。</p><NSelect :value="answerId" :options="(answers.data.value?.items ?? []).map(item => ({ value: item.id, label: `第 ${item.number} 版 · ${repracticeConditionLabels[item.condition]} · ${reviewDate(item.createdAt)}` }))" :disabled="blocked || answers.isFetching.value || !answers.data.value?.items.length" placeholder="选择一次确切新提交" @update:value="selectAnswer" /><div v-if="previous.length || answers.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="blocked || answers.isFetching.value || !previous.length" @click="previousPage">上一页</NButton><NButton size="small" :disabled="blocked || answers.isFetching.value || !answers.data.value?.nextCursor" @click="nextPage">下一页</NButton></div>
      <template v-if="selectedAnswer"><p v-if="existing" class="notice">这版提交已有对照。<NButton text :disabled="blocked" @click="emit('comparison', existing.id)">打开已有对照</NButton></p><template v-else><div v-if="!allowed" class="access-confirm"><NCheckbox v-model:checked="accessConfirmed" :disabled="blocked || !session.data.value || session.data.value.status !== 'ENDED'">确认打开本次新答案、题面与个人参考，保留一次访问记录；不调用模型</NCheckbox><NButton :disabled="blocked || !accessConfirmed || !session.data.value || session.data.value.status !== 'ENDED'" :loading="writer.busy.value" @click="grantAccess">确认访问本次新答</NButton></div><template v-else><NCheckbox v-model:checked="compareConfirmed" :disabled="blocked || value.comparisons.length >= 20">确认以当前所选提交建立固定对照，之后逐来源判断</NCheckbox><NButton :disabled="blocked || !compareConfirmed || value.comparisons.length >= 20" :loading="writer.busy.value" @click="compare">建立基线与新答对照</NButton></template></template></template>
    </section>
    <section><h3>已保存对照</h3><p v-if="!value.comparisons.length" class="field-help">尚未建立对照，不以“已结束”推断改进。</p><ul class="comparison-list"><li v-for="item in value.comparisons" :key="item.id"><button :disabled="blocked" @click="emit('comparison', item.id)"><strong>第 {{ item.answerNumber }} 版新答</strong><span>{{ reviewDate(item.createdAt) }}</span></button></li></ul></section>
    <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value || (receiptMismatch && receiptCommand)" text :disabled="!readable" :loading="writer.busy.value" @click="retry">{{ receiptMismatch ? '用原请求核对已保存结果' : '用原请求重试' }}</NButton></p><p v-if="deferred" class="notice">服务器已确认操作。<NButton text :disabled="!readable" @click="receive">接收已保存结果</NButton></p><p v-if="created" class="notice">本次对照已保存。<NButton text :disabled="!readable" @click="emit('comparison', created.id)">查看已保存对照</NButton></p>
  </section>
</template>

<style scoped>
.repractice-detail { display: grid; gap: 22px; } h2 { margin: 6px 0 10px; font-size: 25px; } h3 { margin: 0 0 12px; font-size: 16px; } p { margin: 0; } summary { cursor: pointer; font-size: 13px; color: var(--muted); } .compare-create, .access-confirm { display: grid; gap: 14px; } .compare-create { padding: 20px; border: 1px solid var(--line); background: var(--surface-soft); border-radius: 7px; } .compare-create h3 { margin: 0; }
.fixed-source { padding: 16px; margin-top: 12px; border: 1px solid var(--line); border-radius: 6px; display: grid; gap: 10px; } .fixed-source h3 { margin: 0; font-size: 14px; } .fixed-source p { white-space: pre-wrap; overflow-wrap: anywhere; } .row-actions { display: flex; gap: 10px; flex-wrap: wrap; } .comparison-list { list-style: none; padding: 0; margin: 0; display: grid; gap: 8px; } .comparison-list button { padding: 13px 16px; display: flex; justify-content: space-between; gap: 12px; text-align: left; width: 100%; border: 1px solid var(--line); border-radius: 5px; font-size: 13px; } .comparison-list span { font-size: 12px; color: var(--muted); } .compare-create .n-button, .access-confirm .n-button { justify-self: start; }
</style>
