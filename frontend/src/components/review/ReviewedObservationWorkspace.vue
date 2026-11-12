<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect, NSkeleton } from 'naive-ui'
import { ApiError, read, preview as postPreview } from '../../api/client'
import type { AiEvaluationInput } from '../../api/ai'
import type { TrainingQuestion } from '../../api/training'
import type { ReviewAnswerLink } from '../../api/review'
import { reviewAnswerKey, reviewAnswerMatches, reviewDate } from '../../api/review'
import * as s from '../../api/standardReviews'
import * as o from '../../api/reviewedObservations'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import StandardReviewReadout from '../bank/StandardReviewReadout.vue'
import ReviewedObservationEvidenceEditor from './ReviewedObservationEvidenceEditor.vue'
import ReviewedObservationReadout from './ReviewedObservationReadout.vue'
import ReviewedObservationConditions from './ReviewedObservationConditions.vue'

const props = defineProps<{ input: AiEvaluationInput; disabled?: boolean; observationId?: string; revision?: number }>()
const emit = defineEmits<{ changed: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const answer = computed<ReviewAnswerLink>(() => ({ sessionId: props.input.sessionId, questionId: props.input.questionId, answerId: props.input.answerId }))
const scope = computed(() => reviewAnswerKey(answer.value)), stateKey = computed(() => `reviewed-observation:${scope.value}`)
const readable = computed(() => !props.disabled && store.sessionActive && !access.checking && !access.restricted && !access.challenge)
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const invalid = () => new ApiError(0, 'REVIEWED_OBSERVATION_RESPONSE_INVALID', '响应无法按确切答案与观察修订核对，请重新读取；未决命令须用原请求恢复。')
let alive = true, generation = 0, previewSequence = 0
function slot<T>() { return { data: shallowRef<T>(), error: shallowRef<unknown>(), busy: ref(false), sequence: 0 } }
const source = slot<TrainingQuestion>(), catalog = slot<o.ObservationPage>(), standardCatalog = slot<s.StandardReviewPage>()
const current = slot<o.ObservationDetail>(), viewed = slot<o.ObservationDetail>(), history = slot<o.ObservationHistoryPage>()
const standardCurrent = slot<s.StandardReviewDetail>(), standardViewed = slot<s.StandardReviewDetail>()
function clearSlot<T>(target: ReturnType<typeof slot<T>>) { target.sequence++; target.data.value = undefined; target.error.value = undefined; target.busy.value = false }
const validRead = (epoch: number, stamp: number, identity: string) => alive && readable.value && store.sessionEpoch === epoch && generation === stamp && scope.value === identity
async function load<T>(target: ReturnType<typeof slot<T>>, path: string, valid: (value: unknown) => value is T, merge?: (before: T | undefined, next: T) => T): Promise<T | undefined> {
  if (!readable.value) return
  const request = ++target.sequence, epoch = store.sessionEpoch, stamp = generation, identity = scope.value, before = target.data.value
  target.busy.value = true; target.error.value = undefined; if (!merge) target.data.value = undefined
  const active = () => validRead(epoch, stamp, identity) && target.sequence === request
  try {
    const value = await read<unknown>(path)
    if (!active()) return
    if (!valid(value)) throw invalid()
    target.data.value = merge ? merge(before, value) : value
    return value
  } catch (reason) { if (active()) { target.data.value = undefined; target.error.value = reason } }
  finally { if (active()) target.busy.value = false }
}

const selectedId = ref<string>(), selectedRevision = ref<number>(), pickedHistory = shallowRef<o.ObservationHistory>(), receipt = shallowRef<o.ReviewedObservation>()
const draft = ref<o.ObservationDraft>(), editingBase = shallowRef<o.ReviewedObservation>(), baseline = ref(''), quoteDirty = ref(false), editorGeneration = ref(0)
const preview = shallowRef<o.ObservationPreview>(), previewBusy = ref(false), previewError = shallowRef<unknown>(), confirmed = ref(false)
const openedStandard = shallowRef<s.OpenedStandardReview>()
const withdrawing = ref(false), withdrawReason = ref(''), withdrawConfirmed = ref(false), message = ref(''), problem = ref('')
const status = ref<'ALL' | s.RecordStatus>('ALL')
const dirty = computed(() => Boolean(draft.value && JSON.stringify(draft.value) !== baseline.value) || quoteDirty.value || Boolean(withdrawReason.value))
const state = reactive({ dirty: false, locked: false }), savedCommand = shallowRef<NonNullable<typeof writer.pending.value>>()
let commandEpoch = store.sessionEpoch, commandGeneration = 0, commandScope = scope.value
let appliedRoute = ''
const questionRef = computed<s.QuestionVersionRef | undefined>(() => {
  const value = source.data.value
  if (!value || value.kind === 'FOLLOW_UP' || !['MAIN', 'REPLAY'].includes(value.kind ?? '') || !value.snapshot.sourceQuestionId || !value.snapshot.sourceVersionId) return
  return { questionId: value.snapshot.sourceQuestionId, versionId: value.snapshot.sourceVersionId }
})
const sourceReady = computed(() => Boolean(questionRef.value && props.input.standard && questionRef.value.questionId === props.input.standard.questionId && questionRef.value.versionId === props.input.standard.versionId))
const sourceNotice = computed(() => !source.data.value ? '' : source.data.value.kind === 'FOLLOW_UP' ? '这是同场追问，不能借用根主问或父题的标准创建受审观察。'
  : !questionRef.value ? '这个题实例没有自身的确切题库来源，不能根据题意或历史父题补绑标准。'
    : !sourceReady.value ? '题实例来源与复盘中的标准身份不一致，请重新读取确切原文后核对。' : '')
const checkpoints = computed(() => props.input.standard?.checkpoints ?? [])
const pointLabel = (id: string) => checkpoints.value.find(row => row.id === id)?.text ?? '确切版本中的历史检查项'
const selectedStandard = computed(() => standardCatalog.data.value?.items.find(row => row.summary.id === draft.value?.content.standardReview.id && row.summary.revision === draft.value?.content.standardReview.revision))
const checkpointOptions = computed(() => checkpoints.value.filter(point => selectedStandard.value?.eligibility.checkpoints.some(row => row.checkpointId === point.id && row.eligible)).map(point => ({ value: point.id, label: point.text })))
const currentMatchesDraft = computed(() => Boolean(draft.value?.id && current.data.value && current.data.value.snapshot.id === draft.value.id && current.data.value.snapshot.revision === draft.value.expectedRevision && current.data.value.snapshot.status === 'ACTIVE'))
const displayed = computed(() => viewed.data.value?.snapshot ?? pickedHistory.value?.snapshot ?? receipt.value)
const displayConditions = computed(() => viewed.data.value?.answerConditions)
const displayReason = computed(() => pickedHistory.value?.snapshot.revision === displayed.value?.revision ? pickedHistory.value?.changeReason : undefined)
const conclusionOptions = computed(() => Object.entries(o.observationConclusionLabels).filter(([value]) => draft.value?.content.coverage === 'NOT_MENTIONED' ? value === 'INSUFFICIENT' : draft.value?.content.coverage === 'UNCERTAIN' ? ['INSUFFICIENT', 'DISPUTED'].includes(value) : true).map(([value,label]) => ({ value,label })))
const postAnswerReview = computed(() => {
  const selected = selectedStandard.value?.summary, saved = preview.value?.answerConditions
  if (!selected || !saved) return false
  const parts = new Intl.DateTimeFormat('en-US',{ timeZone:selected.reviewZoneId,year:'numeric',month:'2-digit',day:'2-digit' }).formatToParts(new Date(saved.answerCreatedAt))
  const part = (name:string) => parts.find(row=>row.type===name)?.value ?? ''
  return selected.reviewedOn > `${part('year').padStart(4,'0')}-${part('month')}-${part('day')}`
})

watch([dirty, () => writer.locked.value, stateKey], ([changed, locked, key], before) => {
  if (before?.[2] && before[2] !== key && store.changes.get(before[2]) === state) store.changes.delete(before[2])
  state.dirty = changed; state.locked = locked; store.changes.set(key, state)
}, { immediate: true, flush: 'sync' })
function invalidatePreview() { previewSequence++; preview.value = undefined; previewError.value = undefined; previewBusy.value = false; confirmed.value = false }
watch([() => JSON.stringify(draft.value), quoteDirty], invalidatePreview, { flush: 'sync' })
watch(() => writer.error.value, value => { if (value) invalidatePreview() })
function invalidateReads() {
  generation++; invalidatePreview()
  clearSlot(source); clearSlot(catalog); clearSlot(standardCatalog); clearSlot(current); clearSlot(viewed); clearSlot(history); clearSlot(standardCurrent); clearSlot(standardViewed)
  openedStandard.value = undefined
}
watch([readable, () => store.sessionEpoch, () => JSON.stringify(props.input)], () => {
  invalidateReads()
  if (readable.value) { void loadSource(); void loadCatalog(); if (selectedId.value) { void refreshSelected(); void loadHistory() }; applyRouteIntent() }
}, { flush: 'sync' })
watch(status, () => { if (readable.value) void loadCatalog() })
watch([() => props.observationId, () => props.revision], applyRouteIntent)
onUnmounted(() => { alive = false; invalidateReads(); if (store.changes.get(stateKey.value) === state) store.changes.delete(stateKey.value) })

function command<T>(path: string, method: string, body: unknown, valid: (value: unknown) => value is T, apply: (value: T) => void) {
  if (!readable.value || writer.locked.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; commandScope = scope.value
  void writer.run<unknown>(path, method, body, value => {
    let accepted = false
    try { accepted = validRead(commandEpoch, commandGeneration, commandScope) && valid(value) } catch { accepted = false }
    // 成功回执仍可能属于旧权限窗口；只保留原键和原正文恢复，不另建观察。
    if (!accepted) { writer.pending.value = savedCommand.value; writer.error.value = invalid(); return }
    savedCommand.value = undefined; apply(value as T)
  })
  savedCommand.value = writer.pending.value
}
function retry() {
  if (!readable.value || writer.busy.value || !writer.pending.value || commandScope !== scope.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry()
}
async function loadSource() {
  if (!readable.value) return
  const selected = clone(answer.value), expected = props.input
  const value = await load(source, `/training/sessions/${selected.sessionId}/questions/${selected.questionId}`, (row): row is TrainingQuestion => {
    const v = row as TrainingQuestion
    return Boolean(v && v.id === selected.questionId && v.sessionId === selected.sessionId && ['MAIN','FOLLOW_UP','REPLAY'].includes(v.kind ?? '') && v.snapshot
      && (v.snapshot.sourceQuestionId === null ? v.snapshot.sourceVersionId === null : s.standardReviewIdValid(v.snapshot.sourceQuestionId) && s.standardReviewIdValid(v.snapshot.sourceVersionId))
      && v.snapshot.content && ['title','prompt','conditions','technology','scenario','type'].every(key => v.snapshot.content[key as keyof typeof v.snapshot.content] === expected.question[key as keyof typeof expected.question]))
  })
  if (value && sourceReady.value) void loadStandards()
}
function loadCatalog(cursor?: string) {
  if (!readable.value) return
  const query: o.ObservationQuery = { ...answer.value, limit: 20, ...(status.value === 'ALL' ? {} : { status: status.value }), ...(cursor === undefined ? {} : { cursor }) }
  return load(catalog, o.observationPaths.list(query), (value): value is o.ObservationPage => o.observationPageValid(value, query), cursor === undefined ? undefined : (before,next) => ({ ...next, items: [...(before?.items ?? []), ...next.items] }))
}
function loadStandards(cursor?: string) {
  if (!readable.value || !sourceReady.value || !questionRef.value) return
  const query: s.StandardReviewQuery = { ...questionRef.value, limit: 20, ...(cursor === undefined ? {} : { cursor }) }
  return load(standardCatalog, s.standardReviewPaths.list(query), (value): value is s.StandardReviewPage => s.standardReviewPageValid(value, query), cursor === undefined ? undefined : (before,next) => ({ ...next, items: [...(before?.items ?? []), ...next.items] }))
}
function matchesAnswer(value: o.ObservationDetail) {
  return reviewAnswerMatches(value.snapshot.answer, answer.value) && (!source.data.value || value.answerConditions.questionKind === source.data.value.kind)
}
async function refreshSelected() {
  const id = selectedId.value, revision = selectedRevision.value
  if (!readable.value || !id) return
  // 先读取当前头，再读所选确切修订；历史正文与当前资格分别保存。
  clearSlot(viewed)
  const value = await load(current, o.observationPaths.detail(id), (v): v is o.ObservationDetail => o.observationDetailValid(v,id,undefined,props.input) && matchesAnswer(v))
  if (!value || selectedId.value !== id || selectedRevision.value !== revision) return
  const target = revision ?? value.snapshot.revision
  selectedRevision.value = target
  await load(viewed, o.observationPaths.detail(id,target), (v): v is o.ObservationDetail => {
    if (!o.observationDetailValid(v,id,target,props.input) || !matchesAnswer(v)) return false
    const known = current.data.value
    return !known || v.eligibility.currentRevision >= known.snapshot.revision
      && (v.eligibility.currentRevision !== known.snapshot.revision || v.eligibility.currentStatus === known.snapshot.status)
      && (known.snapshot.status !== 'WITHDRAWN' || v.eligibility.currentStatus === 'WITHDRAWN')
      && (target !== known.snapshot.revision || JSON.stringify(v.eligibility) === JSON.stringify(known.eligibility))
  })
}
function loadHistory(before?: number) {
  const id = selectedId.value
  if (!readable.value || !id) return
  const query: o.ObservationHistoryQuery = { limit: 20, ...(before === undefined ? {} : { before }) }, selected = clone(answer.value)
  return load(history, o.observationPaths.history(id,query), (v): v is o.ObservationHistoryPage => o.observationHistoryPageValid(v,id,query,selected,props.input), before === undefined ? undefined : (previous,next) => ({ ...next, items: [...(previous?.items ?? []), ...next.items] }))
}
function resetEditor() {
  editorGeneration.value++; draft.value = undefined; editingBase.value = undefined; baseline.value = ''; quoteDirty.value = false
  withdrawing.value = false; withdrawReason.value = ''; withdrawConfirmed.value = false; problem.value = ''; invalidatePreview()
}
function canChange() { return readable.value && !writer.locked.value && store.canLeave([stateKey.value]) }
function selectRecord(id: string, revision?: number, historical?: o.ObservationHistory) {
  if (!s.standardReviewIdValid(id) || revision !== undefined && !s.standardReviewRevisionValid(revision) || !canChange()) return
  resetEditor(); selectedId.value = id; selectedRevision.value = revision; pickedHistory.value = historical; receipt.value = undefined; message.value = ''
  clearSlot(current); clearSlot(viewed); clearSlot(history); void refreshSelected(); void loadHistory()
}
function applyRouteIntent() {
  const key = `${props.observationId ?? ''}:${props.revision ?? ''}`
  if (!readable.value || key === appliedRoute) return
  if (!props.observationId) { appliedRoute = key; return }
  selectRecord(props.observationId,props.revision)
  if (selectedId.value === props.observationId && selectedRevision.value === props.revision) appliedRoute = key
}
function create() {
  if (!sourceReady.value || !checkpoints.value.length || !canChange()) return
  resetEditor(); selectedId.value = undefined; selectedRevision.value = undefined; pickedHistory.value = undefined; receipt.value = undefined; clearSlot(current); clearSlot(viewed); clearSlot(history)
  draft.value = o.emptyObservationDraft(answer.value,'',{ id:'',revision:0 },Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC')
  baseline.value = JSON.stringify(draft.value); message.value = ''; void loadStandards()
}
function closeEditor() { if (canChange()) resetEditor() }
function editCurrent(keepInput = false) {
  const value = current.data.value?.snapshot
  if (!readable.value || writer.locked.value || !sourceReady.value || !value || value.status !== 'ACTIVE') return
  if (keepInput && (!draft.value || draft.value.id !== value.id || draft.value.checkpointId !== value.checkpointId)) return
  if (!keepInput && !store.canLeave([stateKey.value])) return
  if (!keepInput) resetEditor()
  editingBase.value = clone(value)
  draft.value = keepInput ? { ...draft.value!, expectedRevision:value.revision } : { id:value.id,expectedRevision:value.revision,answer:clone(value.answer),checkpointId:value.checkpointId,content:clone(value.content),changeReason:'' }
  if (!keepInput) baseline.value = JSON.stringify(draft.value)
  message.value = keepInput ? '已采用当前修订作为编辑基线；本地内容保留，请选择当前有效标准并重新预览。' : ''
  void loadStandards()
}
function reviewSelectable(row: s.StandardReviewDetail) {
  return row.summary.status === 'ACTIVE' && row.eligibility.revisionCurrent && row.eligibility.currentStatus === 'ACTIVE'
    && row.eligibility.checkpoints.some(point => point.eligible && (!draft.value?.id || point.checkpointId === draft.value.checkpointId))
}
function chooseStandard(row: s.StandardReviewDetail) {
  if (!readable.value || writer.locked.value || previewBusy.value || !draft.value || !reviewSelectable(row)) return
  draft.value.content.standardReview = { id:row.summary.id,revision:row.summary.revision }
}
function chooseCheckpoint(id: string) {
  if (!readable.value || writer.locked.value || previewBusy.value || !draft.value || draft.value.id || !checkpointOptions.value.some(row => row.value === id)) return
  if (draft.value.checkpointId === id) return
  if (quoteDirty.value || draft.value.content.citations.some(row => row.kind === 'CHECKPOINT')) {
    if (!window.confirm('更换检查项会清除未加入选区和旧检查项引文，回答引文保留。确定继续？')) return
  }
  draft.value.checkpointId = id; draft.value.content.citations = draft.value.content.citations.filter(row => row.kind !== 'CHECKPOINT'); quoteDirty.value = false; editorGeneration.value++
}
function changeCoverage(value: o.ObservationContent['coverage']) {
  if (!readable.value || writer.locked.value || previewBusy.value || !draft.value) return
  draft.value.content.coverage = value
  if (value === 'NOT_MENTIONED' || value === 'UNCERTAIN' && !['INSUFFICIENT','DISPUTED'].includes(draft.value.content.conclusion)) draft.value.content.conclusion = 'INSUFFICIENT'
}
async function refreshOpenedStandard() {
  const opened = openedStandard.value
  if (!readable.value || !opened) return
  clearSlot(standardViewed)
  const id = opened.history.snapshot.id, revision = opened.history.snapshot.revision
  const head = await load(standardCurrent,s.standardReviewPaths.detail(id),(v):v is s.StandardReviewDetail => s.standardReviewDetailValid(v,id)
    && v.summary.question.questionId === opened.history.snapshot.question.questionId && v.summary.question.versionId === opened.history.snapshot.question.versionId)
  if (!head || openedStandard.value !== opened) return
  await load(standardViewed,s.standardReviewPaths.detail(id,revision),(v):v is s.StandardReviewDetail => {
    if (!s.standardReviewDetailValid(v,id,revision) || !s.standardEligibilityValid(v.eligibility,opened.history.snapshot)) return false
    return v.eligibility.currentRevision >= head.summary.revision && (v.eligibility.currentRevision !== head.summary.revision || v.eligibility.currentStatus === head.summary.status)
      && (head.summary.status !== 'WITHDRAWN' || v.eligibility.currentStatus === 'WITHDRAWN')
      && (revision !== head.summary.revision || JSON.stringify(v.eligibility) === JSON.stringify(head.eligibility))
  })
}
function openStandard(value: s.RevisionRef) {
  if (!readable.value || writer.locked.value || !s.revisionRefValid(value)) return
  const ref = clone(value), body = { revision:ref.revision }
  command(s.standardReviewPaths.open(ref.id),'POST',body,(v):v is s.OpenedStandardReview => s.openedStandardReviewValid(v,ref.id,body)
    && Boolean(props.input.standard && v.history.snapshot.question.questionId === props.input.standard.questionId && v.history.snapshot.question.versionId === props.input.standard.versionId), v => {
    openedStandard.value = v; clearSlot(standardCurrent); clearSlot(standardViewed); void refreshOpenedStandard()
    message.value = '所引用的确切标准审阅已打开并记录曝光；其回执原文保留，当前资格另行读取。'
  })
}
function draftProblem() {
  const value = draft.value
  if (!value) return '请先创建或编辑观察。'
  if (!sourceReady.value) return '请先核对这个题实例自身的确切题库来源。'
  if (!value.checkpointId || !s.revisionRefValid(value.content.standardReview)) return '请选择一份当前可用的标准审阅和一个检查项。'
  if (['statement','conditions','limitations','observerDescription'].some(key => !s.standardReviewTextValid(value.content[key as 'statement']))) return '请补全观察陈述、观察条件、判断限制和记录者说明，每项 1–2000 个字符。'
  if (!s.standardReviewDateValid(value.content.observedOn) || !s.standardReviewZoneValid(value.content.observationZoneId)) return '请填写合法观察日期与 IANA 时区。'
  if (!value.content.citations.some(row => row.kind === 'CHECKPOINT' && row.elementId === value.checkpointId)) return '请加入所选检查项的确切原文引文。'
  const hasAnswer = value.content.citations.some(row => row.kind.startsWith('ANSWER_'))
  if (value.content.coverage === 'MENTIONED' && !hasAnswer) return '“原文有提及”至少需要一处回答文字或答案图引文。'
  if (value.content.coverage === 'NOT_MENTIONED' && hasAnswer) return '“未提及”只允许检查项引文，请手工移除回答引文。'
  if (value.id && !s.standardReviewTextValid(value.changeReason)) return '请填写这次修改理由。'
  return o.observationDraftValid(value,props.input) ? '' : '观察内容、结论组合或引文不符合契约，请核对完整文字、确切区间及重复引用。'
}
async function makePreview() {
  if (!readable.value || writer.locked.value || previewBusy.value || !draft.value) return
  if (quoteDirty.value) { problem.value = '还有未加入的原文选区，请加入或清除后再预览。'; return }
  problem.value = draftProblem(); if (problem.value) return
  if (draft.value.id && !currentMatchesDraft.value) { problem.value = '编辑基线与当前记录未核对，请独立读取当前状态并明确采用当前修订。'; return }
  const selected = clone(draft.value), epoch = store.sessionEpoch, stamp = generation, identity = scope.value, request = ++previewSequence
  previewBusy.value = true; preview.value = undefined; previewError.value = undefined; confirmed.value = false
  try {
    const value = await postPreview<unknown>(o.observationPaths.preview,selected)
    if (!validRead(epoch,stamp,identity) || request !== previewSequence || JSON.stringify(selected) !== JSON.stringify(draft.value)) return
    if (!o.observationPreviewValid(value,selected,props.input) || value.answerConditions.questionKind !== source.data.value?.kind) throw invalid()
    preview.value = value
  } catch (reason) { if (validRead(epoch,stamp,identity) && request === previewSequence) previewError.value = reason }
  finally { if (validRead(epoch,stamp,identity) && request === previewSequence) previewBusy.value = false }
}
function afterWrite(value: o.ReviewedObservation, reason?: o.ObservationHistory) {
  selectedId.value = value.id; selectedRevision.value = value.revision; receipt.value = clone(value); pickedHistory.value = reason
  clearSlot(current); clearSlot(viewed); clearSlot(history); clearSlot(catalog)
  void refreshSelected(); void loadHistory(); void loadCatalog(); emit('changed')
}
function save() {
  if (quoteDirty.value) { problem.value = '尚有未加入引文，请先加入或清除，并重新预览。'; return }
  if (!readable.value || writer.locked.value || !draft.value || !preview.value || !confirmed.value) return
  const selected = clone(preview.value), body:o.SaveObservation = { draft:clone(draft.value),expectedPreviewHash:selected.previewHash }, previous = editingBase.value ? clone(editingBase.value) : undefined
  if (!o.saveObservationValid(body,selected,props.input)) { problem.value = '输入在预览后已变化或不符合契约，请重新预览。'; return }
  command(body.draft.id ? o.observationPaths.update(body.draft.id) : o.observationPaths.create,body.draft.id ? 'PUT' : 'POST',body,(v):v is o.ReviewedObservation => o.savedObservationValid(v,body,previous,selected,props.input), v => {
    editingBase.value = clone(v); draft.value = { id:v.id,expectedRevision:v.revision,answer:clone(v.answer),checkpointId:v.checkpointId,content:clone(v.content),changeReason:'' }; baseline.value = JSON.stringify(draft.value)
    invalidatePreview(); message.value = `原请求保存回执已核对（修订 ${v.revision}），当前资格另行读取。`; afterWrite(v)
  })
}
function beginWithdraw() {
  if (!current.data.value || current.data.value.snapshot.status !== 'ACTIVE' || !canChange()) return
  resetEditor(); withdrawing.value = true
}
function withdraw() {
  const previous = current.data.value?.snapshot
  if (!readable.value || writer.locked.value || !previous || previous.status !== 'ACTIVE' || !withdrawConfirmed.value) return
  const body:s.WithdrawRecord = { expectedRevision:previous.revision,reason:withdrawReason.value }
  if (!s.withdrawRecordValid(body)) { problem.value = '撤回理由需为 1–2000 个字符。'; return }
  command(o.observationPaths.withdraw(previous.id),'POST',body,(v):v is o.ObservationHistory => o.withdrawnObservationValid(v,body,previous,props.input),v => {
    resetEditor(); message.value = `撤回回执已核对（修订 ${v.snapshot.revision}）。记录为终态，历史内容保留。`; afterWrite(v.snapshot,v)
  })
}
if (readable.value) { void loadSource(); void loadCatalog(); applyRouteIntent() }
</script>

<template>
  <section class="reviewed-observation-workspace" aria-label="确切回答的受审观察">
    <header><div><h3>受审观察</h3><p class="field-help">针对第 {{ input.answerNumber }} 版确切提交，记录一个检查项下的观察、原文与限定结论。它独立于自检、外部意见和 AI 未审评价。</p></div><NButton :disabled="!readable || writer.locked.value || !sourceReady || !checkpoints.length" @click="create">记录一条受审观察</NButton></header>
    <p v-if="!readable" class="notice" role="status">{{ disabled ? '复盘正在处理其他请求，受审观察操作暂时禁用。' : '当前资料访问尚不可用，请先恢复登录或通过原入口确认资料访问。' }}本地输入、未加入选区和未决命令保留。</p>
    <div v-show="readable" class="observation-body">
      <NSkeleton v-if="source.busy.value" text :repeat="2" /><p v-if="source.error.value" class="notice error" role="alert">{{ o.observationErrorText(source.error.value) }}<NButton text size="small" :disabled="!readable" @click="loadSource">重读题实例来源</NButton></p><p v-if="sourceNotice" class="notice">{{ sourceNotice }} 下方仍可查询已有目录及历史，读取失败会明确显示。当前可继续使用原自检或外部意见入口记录观察。</p>
      <p v-if="sourceReady" class="field-help">已核对题实例自身的题库版本。每条观察只绑定一个原检查项，后续题库版本不会替换这个来源。</p>
      <div class="catalog-toolbar"><NSelect v-model:value="status" :disabled="!readable || catalog.busy.value" :options="[{value:'ALL',label:'全部观察记录'},{value:'ACTIVE',label:'未撤回观察'},{value:'WITHDRAWN',label:'已撤回观察'}]" aria-label="观察记录状态筛选" /><NButton :disabled="!readable || catalog.busy.value" :loading="catalog.busy.value" @click="loadCatalog()">刷新观察目录</NButton></div>
      <NSkeleton v-if="catalog.busy.value && !catalog.data.value" text :repeat="3" /><p v-if="catalog.error.value" class="notice error" role="alert">{{ o.observationErrorText(catalog.error.value) }}</p><p v-if="catalog.data.value && !catalog.data.value.items.length" class="field-help">当前筛选下，这次确切提交尚无受审观察记录。</p>
      <article v-for="item in catalog.data.value?.items" :key="item.id" class="catalog-row"><div><strong>{{ pointLabel(item.checkpointId) }}</strong><p>{{ o.observationCoverageLabels[item.coverage] }} · {{ o.observationConclusionLabels[item.conclusion] }}</p><p class="field-help">修订 {{ item.revision }} · {{ s.recordStatusLabels[item.status] }} · {{ item.observedOn }} · {{ item.eligibility.eligibleAsReviewedObservation ? '本次读取具备限定观察资格' : item.eligibility.reasons.map(reason=>s.eligibilityReasonLabels[reason]).join('、') }}</p></div><NButton size="small" :disabled="!readable || writer.locked.value" @click="selectRecord(item.id)">查看这条记录</NButton></article>
      <NButton v-if="catalog.data.value?.nextCursor" :disabled="!readable || catalog.busy.value" @click="loadCatalog(catalog.data.value.nextCursor)">更多观察记录</NButton>

      <section v-if="selectedId" class="observation-section"><header><h4>当前记录与修订历史</h4><NButton size="small" :disabled="!readable || current.busy.value || viewed.busy.value" :loading="current.busy.value || viewed.busy.value" @click="refreshSelected">独立读取当前状态与本修订资格</NButton></header><p v-if="current.error.value || viewed.error.value" class="notice error" role="alert">{{ o.observationErrorText(current.error.value ?? viewed.error.value) }} 当前资格未确认，历史内容不能替代本次核对。</p>
        <template v-if="current.data.value"><p>当前修订 {{ current.data.value.snapshot.revision }} · {{ s.recordStatusLabels[current.data.value.snapshot.status] }}</p><div class="row-actions"><NButton :disabled="!readable || writer.locked.value || !sourceReady || current.data.value.snapshot.status !== 'ACTIVE'" @click="editCurrent()">编辑当前观察</NButton><NButton v-if="draft?.id && !currentMatchesDraft && current.data.value.snapshot.status === 'ACTIVE'" :disabled="!readable || writer.locked.value" @click="editCurrent(true)">保留输入，采用当前修订</NButton><NButton :disabled="!readable || writer.locked.value || current.data.value.snapshot.status !== 'ACTIVE'" @click="beginWithdraw">撤回这条观察</NButton></div></template>
        <p v-if="draft?.id && !currentMatchesDraft" class="notice">当前记录尚未核对或已发生修订，本地输入保留。请先读取当前状态，再明确采用编辑基线。</p>
        <details><summary>不可变修订历史</summary><NButton size="small" :disabled="!readable || history.busy.value" @click="loadHistory()">刷新历史</NButton><p v-if="history.error.value" class="notice error" role="alert">{{ o.observationErrorText(history.error.value) }}</p><div v-for="row in history.data.value?.items" :key="row.snapshot.revision" class="history-row"><span>修订 {{ row.snapshot.revision }} · {{ s.recordChangeLabels[row.change] }} · {{ reviewDate(row.snapshot.revisedAt) }}</span><NButton text size="small" :disabled="!readable || writer.locked.value" @click="selectRecord(row.snapshot.id,row.snapshot.revision,row)">查看本修订并核对当前资格</NButton></div><NButton v-if="history.data.value?.nextBefore != null" size="small" :disabled="!readable || history.busy.value" @click="loadHistory(history.data.value.nextBefore)">更早历史</NButton></details>
        <ReviewedObservationReadout v-if="displayed" :snapshot="displayed" :checkpoint-text="pointLabel(displayed.checkpointId)" :conditions="displayConditions" :eligibility="viewed.data.value?.eligibility" :change-reason="displayReason" :disabled="!readable || writer.locked.value" @open-standard="openStandard" />
      </section>

      <section v-if="draft" class="observation-section observation-editor" aria-label="受审观察编辑器"><header><h4>{{ draft.id ? `修改观察 · 基于修订 ${draft.expectedRevision}` : '记录一条新的受审观察' }}</h4><NButton size="small" :disabled="!readable || writer.locked.value" @click="closeEditor">取消本地编辑</NButton></header>
        <section class="standard-picker"><header><h5>选择当前可用的标准审阅</h5><NButton size="small" :disabled="!readable || standardCatalog.busy.value || !sourceReady" :loading="standardCatalog.busy.value" @click="loadStandards()">刷新标准目录</NButton></header><p class="field-help">这里只列确切来源版本的审阅摘要。不同审阅不合并；只有所选检查项当前具备支持资格的修订可用于新保存。</p><p v-if="standardCatalog.error.value" class="notice error" role="alert">{{ s.standardReviewErrorText(standardCatalog.error.value) }}</p><p v-if="standardCatalog.data.value && !standardCatalog.data.value.items.length" class="field-help">这个确切题库版本没有审阅记录。需先在题库记录实际审阅及依据，再回到此处。</p>
          <article v-for="row in standardCatalog.data.value?.items" :key="row.summary.id" class="standard-row"><div><strong>{{ row.summary.reviewerLabel }} · 修订 {{ row.summary.revision }}</strong><p class="field-help">{{ row.summary.reviewedOn }} · {{ s.recordStatusLabels[row.summary.status] }} · {{ row.eligibility.checkpoints.filter(point=>point.eligible).length }} 个可用检查项</p><p v-if="!reviewSelectable(row)" class="field-help">{{ [...new Set(row.eligibility.checkpoints.flatMap(point=>point.reasons))].map(reason=>s.eligibilityReasonLabels[reason]).join('、') || '这份审阅未支持本条观察的固定检查项' }}</p></div><div class="row-actions"><NButton size="small" :disabled="!readable || writer.locked.value || previewBusy || !reviewSelectable(row)" @click="chooseStandard(row)">{{ draft.content.standardReview.id === row.summary.id && draft.content.standardReview.revision === row.summary.revision ? '已选择本修订' : '选择本修订' }}</NButton><NButton text size="small" :disabled="!readable || writer.locked.value" @click="openStandard({id:row.summary.id,revision:row.summary.revision})">打开审阅原文并记录查看</NButton></div></article>
          <NButton v-if="standardCatalog.data.value?.nextCursor" size="small" :disabled="!readable || standardCatalog.busy.value" @click="loadStandards(standardCatalog.data.value.nextCursor)">更多标准审阅</NButton><p v-if="draft.content.standardReview.id" class="field-help">所选依据：{{ selectedStandard?.summary.reviewerLabel ?? '原保存的确切审阅' }} · 修订 {{ draft.content.standardReview.revision }}{{ selectedStandard ? '' : '；当前目录尚未确认其可用性，请刷新或选择当前有效修订' }}。</p>
        </section>
        <label>本条观察的检查项<NSelect :value="draft.checkpointId || null" :options="draft.id ? [{value:draft.checkpointId,label:pointLabel(draft.checkpointId)}] : checkpointOptions" :disabled="!readable || writer.locked.value || previewBusy || !!draft.id" placeholder="先选择审阅，再选择一个检查项" @update:value="chooseCheckpoint" /><span v-if="draft.id" class="field-help">已保存记录的回答身份与检查项固定；另一检查项应另建观察。</span></label>
        <div class="form-pair"><label>观察范围<NSelect :value="draft.content.coverage" :options="Object.entries(o.observationCoverageLabels).map(([value,label])=>({value,label}))" :disabled="!readable || writer.locked.value || previewBusy" @update:value="changeCoverage" /></label><label>限定结论<NSelect v-model:value="draft.content.conclusion" :options="conclusionOptions" :disabled="!readable || writer.locked.value || previewBusy" /></label></div>
        <p v-if="draft.content.coverage === 'NOT_MENTIONED'" class="notice">请明确说明这是对这次提交文字与图中标签／说明的人工未提及观察。结论只能为依据不足，不能据此判定技术错误。</p><p v-else-if="draft.content.coverage === 'UNCERTAIN'" class="field-help">无法确定时，只能记录依据不足或存在争议，并在限制中说明原因。</p>
        <label>观察陈述<NInput v-model:value="draft.content.statement" type="textarea" :maxlength="2000" :autosize="{minRows:3,maxRows:8}" :disabled="!readable || writer.locked.value || previewBusy" placeholder="针对这个检查项，确切回答体现或缺少了什么" /></label><label>本次观察的适用条件<NInput v-model:value="draft.content.conditions" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:6}" :disabled="!readable || writer.locked.value || previewBusy" /></label><label>判断限制与未知事项<NInput v-model:value="draft.content.limitations" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:6}" :disabled="!readable || writer.locked.value || previewBusy" /></label><label>记录者说明<NInput v-model:value="draft.content.observerDescription" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" :disabled="!readable || writer.locked.value || previewBusy" placeholder="说明记录者角色及这次观察的形成方式" /></label>
        <div class="form-pair"><label>实际观察日期<input v-model="draft.content.observedOn" type="date" min="1000-01-01" max="9999-12-31" :disabled="!readable || writer.locked.value || previewBusy" /></label><label>日期所属时区<NInput v-model:value="draft.content.observationZoneId" :maxlength="100" :disabled="!readable || writer.locked.value || previewBusy" placeholder="Asia/Shanghai 或 UTC" /></label></div>
        <ReviewedObservationEvidenceEditor :key="editorGeneration" v-model="draft.content.citations" :input="input" :checkpoint-id="draft.checkpointId" :disabled="!readable || writer.locked.value || previewBusy" @dirty="quoteDirty = $event" />
        <label v-if="draft.id">本次修改理由<NInput :value="draft.changeReason ?? ''" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" :disabled="!readable || writer.locked.value || previewBusy" @update:value="draft.changeReason = $event" /></label>
        <NButton :disabled="!readable || writer.locked.value || previewBusy || !sourceReady" :loading="previewBusy" @click="makePreview">预览确切回答、标准与引用</NButton><p v-if="previewError" class="notice error" role="alert">{{ o.observationErrorText(previewError) }} 输入保留；可重读当前记录和标准目录后再预览。</p>
        <div v-if="preview" class="observation-preview"><h5>本次保存预览</h5><ReviewedObservationConditions :value="preview.answerConditions" /><p class="field-help">标准审阅可晚于这次回答形成。补录依据不表示作答时已有受审标准；将保留确切标准修订与上述原答条件。</p><p v-if="postAnswerReview" class="notice">按审阅记录所属时区，所选审阅声明日期晚于原答日期，这是事后审阅依据。</p><p>{{ pointLabel(draft.checkpointId) }} · {{ o.observationCoverageLabels[draft.content.coverage] }} · {{ o.observationConclusionLabels[draft.content.conclusion] }}</p><p v-if="draft.content.conclusion === 'DISPUTED'" class="notice">这条观察记录争议，不会以当前有效受审观察展示。</p><ul><li v-for="limit in preview.limitations" :key="limit">{{ s.reviewLimitationLabels[limit] }}</li></ul><NCheckbox v-model:checked="confirmed" :disabled="!readable || writer.locked.value">我已核对确切提交、单项范围、引文及本次限定结论</NCheckbox><NButton type="primary" :disabled="!readable || writer.locked.value || !confirmed || quoteDirty" @click="save">确认保存观察</NButton></div>
      </section>

      <section v-if="withdrawing" class="observation-section"><h4>撤回这条观察</h4><p class="field-help">撤回追加不可变修订，状态不能恢复；原答、标准和旧历史保留。来源失效仍可说明理由后撤回。</p><label>撤回理由<NInput v-model:value="withdrawReason" type="textarea" :maxlength="2000" :autosize="{minRows:3,maxRows:6}" :disabled="!readable || writer.locked.value" /></label><NCheckbox v-model:checked="withdrawConfirmed" :disabled="!readable || writer.locked.value">我确认撤回这条记录并保留历史</NCheckbox><div class="row-actions"><NButton type="error" :disabled="!readable || writer.locked.value || !withdrawConfirmed" @click="withdraw">确认撤回</NButton><NButton :disabled="!readable || writer.locked.value" @click="closeEditor">取消撤回</NButton></div></section>
      <section v-if="openedStandard" class="observation-section"><header><h4>已明确打开的标准审阅</h4><NButton size="small" :disabled="!readable || standardCurrent.busy.value || standardViewed.busy.value" :loading="standardCurrent.busy.value || standardViewed.busy.value" @click="refreshOpenedStandard">重新核对当前资格</NButton></header><p v-if="standardCurrent.error.value || standardViewed.error.value" class="notice error" role="alert">{{ s.standardReviewErrorText(standardCurrent.error.value ?? standardViewed.error.value) }} 原回执资格只表示打开时历史状态。</p><StandardReviewReadout :value="openedStandard" :current-eligibility="standardViewed.data.value?.eligibility" :checkpoints="checkpoints" :disabled="!readable" /></section>
    </div>
    <p v-if="problem && readable" class="notice error" role="alert">{{ problem }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ o.observationErrorText(writer.error.value) }}{{ writer.pending.value ? ' 原请求与输入保留；当前状态读取不能替代原请求恢复。' : ' 本地输入保留，请核对当前修订和来源后重新预览。' }}</p><p v-if="message && readable" class="notice" role="status">{{ message }}</p><NButton v-if="writer.pending.value" type="primary" :disabled="!readable || writer.busy.value" :loading="writer.busy.value" @click="retry">用原请求核对结果</NButton>
  </section>
</template>

<style scoped>
.reviewed-observation-workspace,.observation-body,.observation-section,.standard-picker { display:grid; gap:17px; min-width:0; }.reviewed-observation-workspace { border-top:1px solid var(--line); padding-top:23px; }header { display:flex; justify-content:space-between; align-items:start; flex-wrap:wrap; gap:14px; }header>div { min-width:0; flex:1; }header p { margin-top:8px; max-width:75ch; }h3,h4,h5 { margin:0; }h3 { font-size:18px; }h4 { font-size:16px; }h5 { font-size:14px; }.observation-section { padding-top:20px; border-top:1px solid var(--line); }.catalog-toolbar { display:flex; gap:10px; align-items:center; }.catalog-toolbar>.n-select { width:190px; }.catalog-row,.standard-row { display:flex; align-items:start; justify-content:space-between; gap:14px; padding:14px 0; border-bottom:1px solid var(--line); }.catalog-row>div,.standard-row>div { min-width:0; }.catalog-row strong,.standard-row strong { font-size:13px; overflow-wrap:anywhere; }.catalog-row p,.standard-row p { margin-top:6px; font-size:13px; }.history-row { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px; padding:12px 0; border-bottom:1px solid var(--line); font-size:12px; }label { display:grid; gap:7px; font-size:13px; }.form-pair { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:15px; }.standard-picker { padding-bottom:15px; border-bottom:1px solid var(--line); }.observation-preview { display:grid; gap:15px; padding:16px; background:var(--surface-soft); border-radius:6px; }.observation-preview li { margin:6px 0; font-size:13px; }.observation-editor>.n-button,.observation-preview>.n-button,.observation-body>.n-button { justify-self:start; }details { font-size:12px; }summary { cursor:pointer; margin-bottom:12px; }input[type=date] { width:100%; box-sizing:border-box; padding:9px 11px; border:1px solid var(--line); border-radius:6px; background:var(--surface); color:var(--ink); font:inherit; }input:focus-visible { outline:2px solid var(--brand); outline-offset:2px; }@media(max-width:650px) { .form-pair { grid-template-columns:1fr; }.catalog-row,.standard-row { flex-direction:column; }.catalog-toolbar { flex-wrap:wrap; }.catalog-toolbar>.n-select { width:100%; } }
</style>
