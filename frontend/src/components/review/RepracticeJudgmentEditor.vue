<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import type { AiTask } from '../../api/ai'
import type { AiComparisonDraft } from '../../api/aiComparison'
import { aiComparisonTaskValid } from '../../api/aiComparison'
import type { RepracticeCitation, RepracticeJudge, RepracticeJudgment, RepracticeJudgmentPage, RepracticeOutcome, RepracticeSide, RepracticeSourceProgress, RepracticeSourceSnapshot } from '../../api/repractice'
import { repracticeCitationValid, repracticeFields, repracticeJudgeProblem, repracticeJudgmentValid, repracticeOutcomeLabels, repracticeProgressKey, repracticeProgressLabel, repracticeProgressValid } from '../../api/repractice'
import { reviewAnswerMatches, reviewDate, reviewEvidenceLabels, reviewIdValid } from '../../api/review'
import { questionTextLength } from '../../api/bank'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { actionAnswerValid, actionSourceObservation } from './reviewActionData'

const props = defineProps<{ comparisonId: string; source: RepracticeSourceSnapshot; side: RepracticeSide; progress?: RepracticeSourceProgress; candidate?: AiComparisonDraft; disabled?: boolean }>()
const emit = defineEmits<{ saved: [value: RepracticeJudgment]; refresh: []; candidateResolved: [taskId: string] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const baseline = shallowRef<RepracticeSourceProgress>()
const outcome = ref<RepracticeOutcome>('UNCERTAIN')
const reason = ref('')
const citations = ref<RepracticeCitation[]>([])
const confirmed = ref(false)
const selectedField = ref('ANSWER_TEXT:')
const selected = shallowRef<RepracticeCitation>()
const message = ref('')
const receiptMismatch = ref(false)
type JudgmentRequest = { comparisonId: string; sourceId: string; answerId: string; verification: string; body: RepracticeJudge; progress: RepracticeSourceProgress; side: RepracticeSide }
const unresolved = shallowRef<JudgmentRequest>()
const checkingReceipt = ref(false)
const checkingCandidate = ref(false)
const replaceDraftConfirmed = ref(false)
const candidateTaskId = ref('')
const deferred = shallowRef<() => void>()
const before = ref<number>()
const previous = ref<(number | undefined)[]>([])
const historyOpen = ref(false)
const state = reactive({ dirty: false, locked: false })
const stateKey = `repractice-judgment-${props.comparisonId}-${props.source.sourceId}`
let alive = true
let generation = 0
let receiptSequence = 0
let candidateSequence = 0
let commandEpoch = store.sessionEpoch
const readable = computed(() => store.sessionActive && !props.disabled)
const locked = computed(() => writer.locked.value || Boolean(deferred.value) || checkingReceipt.value || checkingCandidate.value)
// 异常成功回执需要保留原请求，导航锁独立于操作锁，避免同时封死核对入口。
const navigationLocked = computed(() => locked.value || (receiptMismatch.value && Boolean(unresolved.value)))
const dirty = computed(() => Boolean(reason.value || citations.value.length || outcome.value !== 'UNCERTAIN' || confirmed.value))
const stale = computed(() => repracticeProgressKey(baseline.value) !== repracticeProgressKey(props.progress))
const usable = computed(() => Boolean(baseline.value && repracticeProgressValid(baseline.value, props.source.source) && baseline.value.tracking === 'TRACKING'
  && baseline.value.verification === props.source.source.verification && (baseline.value.latest?.revision ?? 0) < 1000
  && (props.source.source.kind !== 'RECOLLECTION' || baseline.value.recollectionState === 'CURRENT')))
const blocked = computed(() => !readable.value || locked.value || receiptMismatch.value)
const problem = computed(() => repracticeJudgeProblem(outcome.value, reason.value, citations.value, props.side))
const candidateProblem = computed(() => {
  const draft = props.candidate
  if (!draft) return ''
  if (props.source.source.kind === 'RECOLLECTION' || !actionAnswerValid(props.source.source.answer)) return '回忆来源不接收 AI 双答比较候选。'
  if (!reviewIdValid(draft.taskId) || draft.comparisonId !== props.comparisonId || draft.answerId !== props.side.answer.answerId || draft.sourceId !== props.source.sourceId) return '候选与当前对照、新答案或来源不一致，不能填入。'
  try { return repracticeJudgeProblem(draft.outcome, draft.reason, draft.citations, props.side) }
  catch { return '候选的依据或新答引文不完整，请返回任务重新读取。' }
})
const fields = computed(() => repracticeFields(props.side))
const field = computed(() => fields.value.find(item => item.key === selectedField.value))
const history = useQuery({ queryKey: computed(() => ['repractice', store.sessionEpoch, 'judgments', props.source.sourceId, before.value]),
  enabled: computed(() => readable.value && historyOpen.value), queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, id = props.source.sourceId, cursor = before.value
    const value = await read<RepracticeJudgmentPage>(`/review/sources/${id}/judgments?limit=10${cursor === undefined ? '' : `&before=${cursor}`}`)
    if (!alive || !readable.value || stamp !== generation || epoch !== store.sessionEpoch || id !== props.source.sourceId || cursor !== before.value) throw new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '判断历史的读取条件已变化。')
    if (!value || !Array.isArray(value.items) || value.items.some(item => !repracticeJudgmentValid(item, id))
      || (value.nextBefore !== null && (!Number.isInteger(value.nextBefore) || value.nextBefore < 1 || value.nextBefore > 1000))) throw new ApiError(502, 'REVIEW_JUDGMENT_MISMATCH', '判断历史与当前来源不一致。')
    return value
  } })
watch([() => props.progress, readable, () => store.sessionEpoch], () => {
  if (readable.value && props.progress && repracticeProgressValid(props.progress, props.source.source) && !dirty.value && !locked.value && !receiptMismatch.value) baseline.value = JSON.parse(JSON.stringify(props.progress)) as RepracticeSourceProgress
}, { immediate: true })
watch([dirty, navigationLocked], ([changed, waiting]) => { state.dirty = changed; state.locked = waiting; store.changes.set(stateKey, state) }, { immediate: true, flush: 'sync' })
watch([outcome, reason, citations], () => { confirmed.value = false; replaceDraftConfirmed.value = false }, { deep: true, flush: 'sync' })
watch(() => props.candidate, () => { replaceDraftConfirmed.value = false }, { deep: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.comparisonId, () => props.source.sourceId, () => props.side.answer.sessionId, () => props.side.answer.questionId, () => props.side.answer.answerId], () => { generation++; receiptSequence++; candidateSequence++; checkingReceipt.value = false; checkingCandidate.value = false; confirmed.value = false; selected.value = undefined }, { flush: 'sync' })
watch([selectedField, () => props.side], () => { selected.value = undefined })
watch(writer.error, error => { if (error instanceof ApiError && !writer.pending.value) { confirmed.value = false; if (error.status === 409) emit('refresh') } })
onUnmounted(() => { alive = false; generation++; receiptSequence++; candidateSequence++; if (store.changes.get(stateKey) === state) store.changes.delete(stateKey) })

function adoptProgress() {
  if (blocked.value || !props.progress || !repracticeProgressValid(props.progress, props.source.source)) return
  baseline.value = JSON.parse(JSON.stringify(props.progress)) as RepracticeSourceProgress; confirmed.value = false
  message.value = '已核对最新来源与判断修订，你填写的依据和引文保留。'
}
function capture(event: Event) {
  if (blocked.value || !field.value) return
  const target = event.target as HTMLTextAreaElement, original = field.value.text
  selected.value = undefined
  if (target.value !== original.replace(/\r\n?/g, '\n') || target.selectionStart === target.selectionEnd) return
  // 文本框会规范换行，选择位置必须映射回不可变原文的 UTF-16 区间。
  const offset = (position: number) => { let raw = 0, shown = 0; while (shown < position && raw < original.length) { if (original[raw] === '\r' && original[raw + 1] === '\n') raw++; raw++; shown++ } return raw }
  const start = offset(target.selectionStart), end = offset(target.selectionEnd)
  const citation: RepracticeCitation = { kind: field.value.kind as RepracticeCitation['kind'], elementId: field.value.elementId, start, end, quote: original.slice(start, end) }
  if (repracticeCitationValid(props.side, citation)) selected.value = citation
  else message.value = '引文需为 1–500 个完整字符，请重新选择。'
}
function addCitation() {
  const citation = selected.value
  if (blocked.value || !citation || citations.value.length >= 6 || !repracticeCitationValid(props.side, citation)) return
  if (citations.value.some(item => item.kind === citation.kind && item.elementId === citation.elementId && item.start === citation.start && item.end === citation.end)) { message.value = '这处原文已加入。'; return }
  citations.value.push({ ...citation }); selected.value = undefined; message.value = ''
}
async function fillCandidate() {
  const draft = props.candidate
  const progress = baseline.value
  if (blocked.value || props.source.source.kind === 'RECOLLECTION' || !actionAnswerValid(props.source.source.answer)
    || !usable.value || stale.value || !draft || !progress || candidateProblem.value || (dirty.value && !replaceDraftConfirmed.value)) return
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++candidateSequence
  const candidateSnapshot = JSON.stringify(draft), progressSnapshot = repracticeProgressKey(progress)
  const inputSnapshot = () => JSON.stringify([outcome.value, reason.value, citations.value, confirmed.value, replaceDraftConfirmed.value])
  const originalInput = inputSnapshot()
  const stillCurrent = () => alive && readable.value && epoch === store.sessionEpoch && stamp === generation && sequence === candidateSequence
    && props.candidate === draft && JSON.stringify(draft) === candidateSnapshot && inputSnapshot() === originalInput
    && repracticeProgressKey(baseline.value) === progressSnapshot && repracticeProgressKey(props.progress) === progressSnapshot
    && usable.value && !stale.value && !receiptMismatch.value
  checkingCandidate.value = true
  message.value = '正在重新核对候选与当前来源，已有判断输入保留。'
  try {
    // 暂存候选不能跟随人工 CAS 自动升级；最终填入前重读任务，核对原清单的来源修订。
    const task = await read<AiTask>(`/ai/tasks/${draft.taskId}`)
    if (!stillCurrent()) {
      if (alive && sequence === candidateSequence) message.value = '核对期间来源或输入已变化，候选未填入，请重新核对。'
      return
    }
    if (!aiComparisonTaskValid(task, draft.comparisonId) || task.task.id !== draft.taskId || !task.sourcesCurrent || task.task.status !== 'SUCCEEDED') {
      throw new ApiError(409, 'AI_SOURCE_CHANGED', '候选来源已变化或任务尚不可采用，人工输入保留，请回到任务重新核对。')
    }
    const input = task.manifest.comparisonInput!, checks = task.manifest.comparisonChecks!
    const suggestion = task.comparisonCandidate?.suggestions.find(item => item.sourceId === draft.sourceId)
    const check = checks.sources.find(item => item.sourceId === draft.sourceId)
    if (!suggestion || !check || check.actionId !== progress.actionId || check.actionRevision !== progress.actionRevision
      || !reviewAnswerMatches(input.current.identity, props.side.answer) || !reviewAnswerMatches(input.baseline.identity, props.source.source.answer)
      || input.sources.find(item => item.sourceId === draft.sourceId)?.verification !== props.source.source.verification
      || suggestion.suggestedOutcome !== draft.outcome || `${suggestion.reason}\n\n限制：${suggestion.limitations}` !== draft.reason
      || !draft.citations.every(citation => suggestion.citations.some(original => original.side === 'CURRENT' && original.kind === citation.kind
        && original.elementId === citation.elementId && original.start === citation.start && original.end === citation.end && original.quote === citation.quote))) {
      throw new ApiError(409, 'AI_SOURCE_CHANGED', '候选与暂存建议、确切答案或当前来源修订不一致，尚未替换人工输入。')
    }
    // 只转入本条草稿，最终人工判断仍以现有来源／判断修订另行确认保存。
    outcome.value = draft.outcome; reason.value = draft.reason; citations.value = draft.citations.map(citation => ({ ...citation }))
    candidateTaskId.value = draft.taskId; confirmed.value = false; selected.value = undefined; replaceDraftConfirmed.value = false
    message.value = '已填入 AI 未审建议及新答引文，请检查和修改后重新确认；尚未保存判断。'
    emit('candidateResolved', draft.taskId)
  } catch (error) {
    if (stillCurrent()) { message.value = errorText(error); emit('refresh') }
  } finally { if (alive && sequence === candidateSequence) checkingCandidate.value = false }
}
function discardCandidate() { if (!locked.value && readable.value && props.candidate) emit('candidateResolved', props.candidate.taskId) }
function submit() {
  const current = baseline.value
  if (blocked.value || stale.value || !usable.value || !current || !confirmed.value || problem.value) return
  const comparisonId = props.comparisonId, sourceId = props.source.sourceId, answerId = props.side.answer.answerId, verification = props.source.source.verification
  const body: RepracticeJudge = { expectedRevision: current.latest?.revision ?? 0, currentActionId: current.actionId,
    expectedActionRevision: current.actionRevision, outcome: outcome.value, reason: reason.value, citations: citations.value.map(citation => ({ ...citation })) }
  const request: JudgmentRequest = { comparisonId, sourceId, answerId, verification, body, progress: current, side: props.side }
  commandEpoch = store.sessionEpoch; message.value = ''
  void writer.run<RepracticeJudgment>(`/review/comparisons/${comparisonId}/sources/${sourceId}/judgments`, 'POST', body, value => {
    if (!alive) return
    if (!judgmentMatches(value, request)) {
      unresolved.value = request; receiptMismatch.value = true; confirmed.value = false
      writer.error.value = new ApiError(502, 'REVIEW_JUDGMENT_MISMATCH', '回执与本次判断不一致。输入保留并暂停新提交，请核对已保存判断。')
      emit('refresh'); return
    }
    const apply = () => acceptJudgment(value, request)
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = apply
    else apply()
  })
}
function judgmentMatches(value: RepracticeJudgment, request: JudgmentRequest) {
  const { body } = request
  return repracticeJudgmentValid(value, request.sourceId) && value.comparisonId === request.comparisonId && value.answerId === request.answerId
    && value.revision === body.expectedRevision + 1 && value.verification === request.verification && value.outcome === body.outcome && value.reason === body.reason
    && value.citations.length === body.citations.length && value.citations.every((citation, index) => {
      const expected = body.citations[index]!
      return repracticeCitationValid(request.side, citation) && citation.kind === expected.kind && citation.elementId === expected.elementId
        && citation.start === expected.start && citation.end === expected.end && citation.quote === expected.quote
    })
}
function acceptJudgment(value: RepracticeJudgment, request: JudgmentRequest) {
  reason.value = ''; outcome.value = 'UNCERTAIN'; citations.value = []; confirmed.value = false
  const current = request.progress
  baseline.value = { ...current, latest: value, applicable: current.tracking === 'TRACKING' && current.verification === value.verification
    && (current.recollectionState == null || current.recollectionState === 'CURRENT') }
  receiptMismatch.value = false; unresolved.value = undefined; writer.error.value = undefined
  candidateTaskId.value = ''
  message.value = '已核对并接收本条来源判断。其他来源和原答保持各自记录。'
  void queryClient.invalidateQueries({ queryKey: ['repractice'] }); emit('saved', value)
}
async function verifySavedJudgment() {
  const request = unresolved.value
  if (!readable.value || locked.value || !receiptMismatch.value || !request) return
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++receiptSequence
  const stillCurrent = () => alive && readable.value && epoch === store.sessionEpoch && stamp === generation && sequence === receiptSequence
    && unresolved.value === request && props.comparisonId === request.comparisonId && props.source.sourceId === request.sourceId
    && reviewAnswerMatches(props.side.answer, request.side.answer) && props.source.source.verification === request.verification
  checkingReceipt.value = true
  try {
    // 按原请求的下一修订定位，后来的改判不能冒充这次保存，也不能用新键重复提交。
    const page = await read<RepracticeJudgmentPage>(`/review/sources/${request.sourceId}/judgments?before=${request.body.expectedRevision + 2}&limit=1`)
    if (!stillCurrent()) return
    const value = page?.items?.[0]
    if (!Array.isArray(page?.items) || page.items.length !== 1 || !value || !judgmentMatches(value, request)) throw new ApiError(502, 'REVIEW_JUDGMENT_MISMATCH', '未找到与原请求完全一致的判断，输入继续保留，提交仍暂停。')
    acceptJudgment(value, request)
  } catch (error) { if (stillCurrent()) writer.error.value = error }
  finally { if (alive && sequence === receiptSequence) checkingReceipt.value = false }
}
function retry() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receive() { if (readable.value && deferred.value) { const apply = deferred.value; deferred.value = undefined; apply() } }
function nextPage() { if (readable.value && !history.isFetching.value && history.data.value?.nextBefore) { previous.value.push(before.value); before.value = history.data.value.nextBefore } }
</script>

<template>
  <section class="judgment-editor" :aria-label="`来源判断：${source.actionTitle}`">
    <header><div><h4>{{ source.actionTitle }}</h4><p class="field-help">本次固定验证条件</p></div><span class="status">{{ progress ? repracticeProgressLabel(progress) : '读取当前来源中' }}</span></header>
    <p class="verification">{{ source.source.verification }}</p>
    <p class="field-help">{{ source.source.kind === 'RECOLLECTION' ? '回忆来源的个人观察' : '原观察' }}：{{ actionSourceObservation(source.source) }}</p>
    <p v-if="progress && progress.verification !== source.source.verification" class="notice">当前验证条件已改变，本次旧条件不能作为新条件的判断依据。已有历史保留，可重新创建复练。</p>
    <p v-if="progress?.tracking === 'DROPPED'" class="notice">当前来源已停止跟踪，暂不能追加复练判断。停止跟踪不是完成。</p>
    <p v-if="source.source.kind === 'RECOLLECTION' && progress?.recollectionState !== 'CURRENT'" class="notice">回忆已补记、归档或当前状态尚未确认，不能追加新判断；已有判断和未保存输入保留。请从使用中的当前版本重新创建复练。</p>
    <p v-if="stale && progress" class="notice">来源归属、条件或最新判断已变化。<NButton text :disabled="blocked" @click="adoptProgress">核对最新状态并保留输入</NButton></p>
    <p v-if="progress?.latest" class="field-help">最近判断：{{ repracticeOutcomeLabels[progress.latest.outcome] }} · 修订 {{ progress.latest.revision }} · {{ reviewDate(progress.latest.createdAt) }}</p>
    <section v-if="candidate" class="candidate-transfer" aria-label="待填入的 AI 比较建议">
      <h5>AI 未审建议 · 待你填入</h5><p>{{ repracticeOutcomeLabels[candidate.outcome] }}</p><p class="candidate-reason">{{ candidate.reason }}</p><p class="field-help">将填入 {{ candidate.citations.length }} 处新答引文；旧答引文保留在候选中阅读。</p>
      <p v-if="candidateProblem" class="notice error">{{ candidateProblem }}</p>
      <NCheckbox v-if="dirty" v-model:checked="replaceDraftConfirmed" :disabled="blocked || !usable || stale || !!candidateProblem">用这条候选替换本条尚未保存的结论、依据和引文，保留当前来源修订，之后重新核对</NCheckbox>
      <div class="row-actions"><NButton :disabled="blocked || !usable || stale || !!candidateProblem || (dirty && !replaceDraftConfirmed)" :loading="checkingCandidate" @click="fillCandidate">核对候选并填入本条草稿</NButton><NButton text :disabled="locked || !readable" @click="discardCandidate">暂不填入</NButton></div>
    </section>
    <p v-else-if="candidateTaskId" class="field-help">当前草稿曾填入 AI 未审建议（任务 {{ candidateTaskId.slice(0, 8) }}）；请自行核对原文和限制，保存不代表模型结论已获技术验证。</p>
    <div class="judgment-fields">
      <label>本次新答案对这条来源的处理情况<NSelect v-model:value="outcome" :options="Object.entries(repracticeOutcomeLabels).map(([value, label]) => ({ value, label }))" :disabled="blocked || !usable" /></label>
      <label>判断依据与限制<NInput v-model:value="reason" type="textarea" :autosize="{ minRows: 3, maxRows: 9 }" :disabled="blocked || !usable" placeholder="结合本次固定验证条件说明；条件不同或证据不足时明确写出限制。" /><span class="field-help">{{ questionTextLength(reason) }} / 2000 字符</span></label>
      <details><summary>从本次新答案选择原文依据（{{ citations.length }} / 6）</summary><div class="evidence-fields"><NSelect v-model:value="selectedField" :options="fields.map(item => ({ value: item.key, label: item.label, disabled: !item.text }))" :disabled="blocked || !usable" aria-label="新答案引用字段" /><textarea v-if="field" :value="field.text" readonly :disabled="blocked || !usable" rows="7" :aria-label="`${field.label}，选择引用`" @select="capture" @keyup="capture" @mouseup="capture" /><NButton size="small" :disabled="blocked || !usable || !selected || citations.length >= 6" @click="addCitation">加入所选引文</NButton></div></details>
      <ol v-if="citations.length" class="citations"><li v-for="(citation, index) in citations" :key="`${citation.kind}:${citation.elementId}:${citation.start}:${citation.end}`"><div><strong>{{ reviewEvidenceLabels[citation.kind] }}</strong><blockquote>{{ citation.quote }}</blockquote></div><NButton text size="small" :disabled="blocked" @click="citations.splice(index, 1)">移除</NButton></li></ol>
      <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="!readable" :loading="writer.busy.value" @click="retry">用原请求重试</NButton></p>
      <NButton v-if="receiptMismatch && unresolved" :disabled="!readable || locked" :loading="checkingReceipt" @click="verifySavedJudgment">核对已保存判断</NButton>
      <p v-if="deferred" class="notice">服务器已确认，访问恢复后可以接收本次记录。<NButton text :disabled="!readable" @click="receive">接收已保存判断</NButton></p>
      <p v-if="problem && dirty" class="field-help">{{ problem }}</p><NCheckbox v-model:checked="confirmed" :disabled="blocked || stale || !usable || !!problem">已核对新答案与本条固定条件，仅记录这条来源的判断</NCheckbox>
      <div class="row-actions"><NButton type="primary" :disabled="blocked || stale || !usable || !confirmed || !!problem" :loading="writer.busy.value" @click="submit">保存本条判断</NButton><NButton text :disabled="locked || !readable" @click="emit('refresh')">重读来源状态</NButton><NButton text :disabled="!readable" @click="historyOpen = !historyOpen">{{ historyOpen ? '收起判断历史' : '查看判断历史' }}</NButton></div>
    </div>
    <section v-if="historyOpen" class="judgment-history" aria-label="来源判断历史"><p v-if="history.isPending.value" role="status">正在读取历史…</p><p v-if="history.error.value" class="notice error" role="alert">{{ errorText(history.error.value) }}<NButton text @click="history.refetch()">重读</NButton></p><p v-if="history.data.value && !history.data.value.items.length" class="field-help">尚无已保存判断。</p><article v-for="item in history.data.value?.items ?? []" :key="item.id"><header><strong>修订 {{ item.revision }} · {{ repracticeOutcomeLabels[item.outcome] }}</strong><time>{{ reviewDate(item.createdAt) }}</time></header><p>{{ item.reason }}</p><details><summary>当时验证条件与 {{ item.citations.length }} 处引文</summary><p>{{ item.verification }}</p><blockquote v-for="(citation, index) in item.citations" :key="index">{{ citation.quote }}</blockquote></details></article><div class="row-actions"><NButton size="small" :disabled="history.isFetching.value || !previous.length" @click="before = previous.pop()">上一页</NButton><NButton size="small" :disabled="history.isFetching.value || !history.data.value?.nextBefore" @click="nextPage">下一页</NButton></div></section>
  </section>
</template>

<style scoped>
.judgment-editor { display: grid; gap: 14px; padding: 22px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
header { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; flex-wrap: wrap; } h4, p, blockquote { margin: 0; } .status, time { color: var(--muted); font-size: 12px; }
.verification { padding: 14px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 6px; font-weight: 600; }
.verification, blockquote, .judgment-history p { white-space: pre-wrap; overflow-wrap: anywhere; } .judgment-fields, .evidence-fields, .judgment-history { display: grid; gap: 12px; }
label { display: grid; gap: 7px; font-size: 13px; } summary { color: var(--muted); cursor: pointer; font-size: 13px; } .evidence-fields { margin-top: 10px; } textarea { width: 100%; padding: 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); color: var(--text); resize: vertical; font: inherit; font-size: 13px; }
.citations { display: grid; gap: 8px; margin: 0; padding-left: 24px; } .citations li { padding: 10px; border: 1px solid var(--line); border-radius: 5px; font-size: 13px; } .citations strong { font-size: 11px; color: var(--muted); } blockquote { margin-top: 6px; }
.judgment-history { padding-top: 16px; border-top: 1px solid var(--line); } .judgment-history article { display: grid; gap: 10px; padding: 12px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 5px; font-size: 13px; }
.candidate-transfer { display: grid; gap: 12px; padding: 16px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); } .candidate-transfer h5 { margin: 0; font-size: 14px; } .candidate-reason { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
@media (max-width: 650px) { .judgment-editor { padding: 14px; } }
</style>
