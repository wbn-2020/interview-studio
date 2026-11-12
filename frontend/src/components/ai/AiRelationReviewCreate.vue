<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { ItemDetail, ItemSummary } from '../../api/client'
import type { AiManifest as AiManifestRecord, AiPreview, AiSettings, AiSource, AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiSourceKindLabel, aiSourceRoute, formatAiAmount } from '../../api/ai'
import { aiRelationEvidenceKnowledgeSourceValid } from '../../api/aiRelationEvidence'
import type { AiRelationReviewCreateRequest, AiRelationReviewPreviewRequest } from '../../api/aiRelationReview'
import { aiRelationReviewInputValid, aiRelationReviewPaths, aiRelationReviewRequestProblem, aiRelationReviewRequestValid, aiRelationReviewSourcesMatch, aiRelationReviewTaskMatches } from '../../api/aiRelationReview'
import type { AiRelationAiFeedbackCreateRequest, AiRelationAiFeedbackPreviewRequest } from '../../api/aiRelationAiFeedback'
import { aiRelationAiFeedbackInputFingerprint, aiRelationAiFeedbackInputValid, aiRelationAiFeedbackPaths, aiRelationAiFeedbackRequestProblem, aiRelationAiFeedbackRequestValid, aiRelationAiFeedbackSourcesMatch, aiRelationAiFeedbackTaskMatches } from '../../api/aiRelationAiFeedback'
import type { AiReviewDraftFeedbackInput, AiReviewDraftField, AiReviewDraftFragment, AiReviewDraftInput, AiReviewDraftRecollectionInput } from '../../api/aiReviewDraft'
import { aiReviewDraftFieldLabels, aiReviewDraftInputFingerprint, aiReviewDraftKindLabels } from '../../api/aiReviewDraft'
import type { RelationFeedbackChoice, RelationFeedbackEvidenceSource } from '../../api/relationFeedbackEvidence'
import { relationFeedbackChoiceValid, relationFeedbackEvidencePaths, relationFeedbackEvidenceRangeMatches, relationFeedbackEvidenceSourceValid } from '../../api/relationFeedbackEvidence'
import type { RelationAiFeedbackChoice, RelationAiFeedbackEvidenceSnapshot, RelationAiFeedbackEvidenceSource } from '../../api/relationAiFeedbackEvidence'
import { relationAiFeedbackChoiceValid, relationAiFeedbackEvidencePaths, relationAiFeedbackEvidenceRangeMatches, relationAiFeedbackEvidenceSourceValid } from '../../api/relationAiFeedbackEvidence'
import type { Recollection, RecollectionPage } from '../../api/recollection'
import { recollectionBaselineLabels, recollectionValid } from '../../api/recollection'
import { reviewChoiceLabels, reviewCoverageLabels, reviewIdValid } from '../../api/review'
import { questionDimensionLabels, questionTypeLabels } from '../../api/bank'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import KnowledgePicker from '../KnowledgePicker.vue'
import RelationFeedbackPicker from '../RelationFeedbackPicker.vue'
import RelationAiFeedbackPicker from '../RelationAiFeedbackPicker.vue'
import RecollectionPicker from '../review/RecollectionPicker.vue'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

type RecollectionChoice = Pick<RecollectionPage['items'][number], 'id' | 'title' | 'versionId' | 'versionNumber' | 'revision' | 'baselineKind'>
type SourceIdentity = RelationFeedbackChoice | { kind: 'RECOLLECTION'; recollection: RecollectionChoice }
  | { kind: 'AI_FEEDBACK'; feedback: RelationAiFeedbackChoice }
type SourceKind = 'FEEDBACK' | 'RECOLLECTION' | 'AI_FEEDBACK'
type SourceField = { field: AiReviewDraftField; text: string }
type SavedSource = { kind: 'SELF_CHECK' | 'EXTERNAL_FEEDBACK'; fixed: Omit<AiReviewDraftFeedbackInput, 'fragments'>; fields: SourceField[] }
  | { kind: 'RECOLLECTION'; fixed: Omit<AiReviewDraftRecollectionInput, 'fragments'>; fields: SourceField[] }
  | { kind: 'AI_FEEDBACK'; fixed: Omit<RelationAiFeedbackEvidenceSnapshot, 'fragments'>; fields: SourceField[] }
type PreviewRequest = AiRelationReviewPreviewRequest | AiRelationAiFeedbackPreviewRequest
type FixedInput = AiReviewDraftInput | RelationAiFeedbackEvidenceSnapshot
const feedbackFragment = (value: AiReviewDraftFragment): value is AiReviewDraftFeedbackInput['fragments'][number] => ['OBSERVATION', 'CONDITION', 'BASIS', 'LIMITATION', 'SUGGESTION'].includes(value.field)
const recollectionFragment = (value: AiReviewDraftFragment): value is AiReviewDraftRecollectionInput['fragments'][number] => ['QUESTION', 'CONDITIONS', 'ANSWER_FRAGMENT', 'FEEDBACK', 'UNCERTAINTY', 'GAP'].includes(value.field)
const props = defineProps<{ settings?: AiSettings; disabled?: boolean }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), viewId = useId()
const stateKey = `ai-relation-review:${viewId}`, active = ref(true), instruction = ref('')
const sources = ref<AiSource[]>([]), verifiedKnowledge = ref<string[]>([]), knowledgePicker = ref<'CONCEPT' | 'NOTE' | null>(null)
const sourceKind = ref<SourceKind>('FEEDBACK'), sourceIdentity = shallowRef<SourceIdentity>(), savedSource = shallowRef<SavedSource>()
const sourceGeneration = ref(-1), sourcePicker = ref(false), pickerGeneration = ref(0), selecting = ref(false), selectionError = ref('')
const fragments = ref<AiReviewDraftFragment[]>([]), fieldKey = ref<AiReviewDraftField>(), rangeStart = ref(''), rangeEnd = ref('')
const previewing = ref(false), previewError = ref(''), prepared = shallowRef<AiPreview>(), preparedRequest = ref(''), consent = ref(false)
const authorization = ref<AiAuthorizationReference>()
const deferred = shallowRef<AiTask>(), createdResult = shallowRef<AiTask>(), message = ref('')
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.restricted
  && !access.challenge && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || Boolean(createdResult.value))
const editingBlocked = computed(() => blocked.value || selecting.value || previewing.value)
const configured = computed(() => Boolean(props.settings?.configuration.enabled && props.settings.configuration.configured))
const sourceReady = computed(() => Boolean(savedSource.value) && sourceGeneration.value === accessGeneration)
const paths = computed(() => sourceKind.value === 'AI_FEEDBACK' ? aiRelationAiFeedbackPaths : aiRelationReviewPaths)
const rangeMatches = computed(() => sourceKind.value === 'AI_FEEDBACK' ? relationAiFeedbackEvidenceRangeMatches : relationFeedbackEvidenceRangeMatches)
const conceptCount = computed(() => sources.value.filter(source => source.kind === 'CONCEPT').length)
const fields = computed(() => savedSource.value?.fields ?? [])
const selectedField = computed(() => fields.value.find(field => field.field === fieldKey.value))
const range = computed<AiReviewDraftFragment | undefined>(() => {
  const field = selectedField.value
  if (!field || !/^\d+$/.test(rangeStart.value) || !/^\d+$/.test(rangeEnd.value)) return undefined
  const start = Number(rangeStart.value), end = Number(rangeEnd.value)
  return { field: field.field, start, end, quote: field.text.slice(start, end) }
})
const rangeProblem = computed(() => {
  const value = range.value, field = selectedField.value
  if (!value || !field) return '请选取原文或填写原字段中的起止位置。'
  if (!rangeMatches.value(field.text, value.start, value.end, value.quote)) return '请选择 1–8000 个 UTF-16 码元的非空白片段，不能含 NUL，边界不能拆开完整字符。'
  if (fragments.value.length >= 8) return '最多选择 8 个原文片段。'
  return fragments.value.some(item => item.field === value.field && item.start < value.end && value.start < item.end) ? '同一字段的片段不能重叠。' : ''
})
const request = computed<PreviewRequest | undefined>(() => {
  const identity = sourceIdentity.value, selected = fragments.value.map(item => ({ ...item }))
  if (!identity) return undefined
  const common = { instruction: instruction.value, sources: sources.value.map(source => ({ itemId: source.itemId, versionId: source.versionId })) }
  if (identity.kind === 'AI_FEEDBACK') {
    const source = savedSource.value
    if (source?.kind !== 'AI_FEEDBACK' || !selected.every(feedbackFragment)) return undefined
    return { ...common, source: { taskId: source.fixed.taskId, attemptId: source.fixed.attemptId, feedbackId: source.fixed.feedbackId,
      reportHash: source.fixed.reportHash, expectedDecisionRevision: source.fixed.decisionRevision, answer: { ...source.fixed.answer }, fragments: selected } }
  }
  if (identity.kind === 'RECOLLECTION') {
    if (!selected.every(recollectionFragment)) return undefined
    return { ...common, source: { kind: identity.kind, feedback: null, recollection: { recollectionId: identity.recollection.id,
      versionId: identity.recollection.versionId, expectedRevision: identity.recollection.revision, fragments: selected } } }
  }
  if (!selected.every(feedbackFragment)) return undefined
  return { ...common, source: { kind: identity.kind, feedback: { feedbackId: identity.feedbackId, feedbackRevision: identity.feedbackRevision, fragments: selected }, recollection: null } }
})
const expectedInput = computed<FixedInput | undefined>(() => {
  const source = savedSource.value, selected = fragments.value.map(item => ({ ...item }))
  if (!source) return undefined
  if (source.kind === 'AI_FEEDBACK') return selected.every(feedbackFragment) ? { ...source.fixed, answer: { ...source.fixed.answer }, fragments: selected } : undefined
  if (source.kind === 'RECOLLECTION') return selected.every(recollectionFragment) ? { kind: source.kind, feedback: null, recollection: { ...source.fixed, fragments: selected } } : undefined
  return selected.every(feedbackFragment) ? { kind: source.kind, feedback: { ...source.fixed, answer: { ...source.fixed.answer }, fragments: selected }, recollection: null } : undefined
})
const requestSignature = computed(() => request.value ? JSON.stringify(request.value) : '')
const knowledgeSignature = computed(() => JSON.stringify(sources.value)), sourceSignature = computed(() => JSON.stringify(savedSource.value))
const inputSignature = computed(() => expectedInput.value ? 'taskId' in expectedInput.value
  ? aiRelationAiFeedbackInputFingerprint(expectedInput.value) : aiReviewDraftInputFingerprint(expectedInput.value) : '')
const problem = computed(() => {
  if (sources.value.length < 2 || sources.value.length > 5 || conceptCount.value < 2 || sources.value.some(source => !aiRelationEvidenceKnowledgeSourceValid(source))) return '请选择 2–5 个当前发布的知识，其中至少两个为概念。'
  if (sources.value.some(source => !verifiedKnowledge.value.includes(source.itemId))) return '访问条件或来源状态已变化，请重新核对所选知识版本。'
  if (!sourceReady.value) return '请选择或重新核对一条人工反馈、AI 未审反馈或使用中的当前回忆。'
  if (fragments.value.some(item => { const field = fields.value.find(field => field.field === item.field); return !field || !rangeMatches.value(field.text, item.start, item.end, item.quote) })) return '已选片段与固定原文字段不一致，请重新核对。'
  if (!expectedInput.value || !(sourceKind.value === 'AI_FEEDBACK' ? aiRelationAiFeedbackInputValid(expectedInput.value) : aiRelationReviewInputValid(expectedInput.value))) return '请明确选择 1–8 个不重叠的原文片段，并核对完整固定背景。'
  return request.value ? sourceKind.value === 'AI_FEEDBACK' ? aiRelationAiFeedbackRequestProblem(request.value) : aiRelationReviewRequestProblem(request.value) : '请选择有效的复盘来源。'
})
const previewMatches = computed(() => Boolean(prepared.value && request.value && expectedInput.value && preparedRequest.value === requestSignature.value
  && sourcesMatch(prepared.value.manifest, request.value, sources.value, expectedInput.value)))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const dirty = computed(() => !createdResult.value && Boolean(instruction.value || sources.value.length || sourceIdentity.value || fragments.value.length || rangeStart.value || rangeEnd.value || consent.value))
const state = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
let alive = true, accessGeneration = 0, sourceSequence = 0, previewSequence = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0

watch([dirty, locked], ([changed, pending]) => {
  state.dirty = changed; state.locked = pending; commandState.locked = pending
  store.changes.set(stateKey, state); store.changes.set('ai-create', state); store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([requestSignature, knowledgeSignature, sourceSignature, inputSignature, instruction, fieldKey, rangeStart, rangeEnd], invalidatePreview, { flush: 'sync' })
watch(fieldKey, () => { rangeStart.value = ''; rangeEnd.value = '' }, { flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  accessGeneration++; sourceSequence++; selecting.value = false; verifiedKnowledge.value = []; sourceGeneration.value = -1
  knowledgePicker.value = null; sourcePicker.value = false; pickerGeneration.value++; invalidatePreview()
  // 访问重核只作废授权；固定原文、所选字段和尚未加入的选区保留，恢复后显式核对。
}, { flush: 'sync' })
watch([writer.error, writer.pending], ([error, pending]) => {
  if (!error) return
  invalidatePreview()
  if (!pending) { verifiedKnowledge.value = []; sourceGeneration.value = -1 }
  emit('refresh')
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; sourceSequence++; previewSequence++
  if (store.changes.get(stateKey) === state) store.changes.delete(stateKey)
  if (store.changes.get('ai-create') === state) store.changes.delete('ai-create')
  if (store.changes.get('ai-command') === commandState) store.changes.delete('ai-command')
})
function invalidatePreview() { previewSequence++; previewing.value = false; prepared.value = undefined; preparedRequest.value = ''; consent.value = false }
function sourcesMatch(manifest: AiManifestRecord, request: PreviewRequest, knowledge: AiSource[], input: FixedInput): boolean {
  if ('taskId' in input) return aiRelationAiFeedbackRequestValid(request)
    && aiRelationAiFeedbackSourcesMatch(manifest, request, knowledge, input)
  return aiRelationReviewRequestValid(request) && aiRelationReviewSourcesMatch(manifest, request, knowledge, input)
}
async function readCurrent<T>(path: string): Promise<T> {
  const epoch = store.sessionEpoch, generation = accessGeneration
  const value = await read<T>(path)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || generation !== accessGeneration) throw new ApiError(0, 'RELATION_REVIEW_ACCESS_CHANGED', '访问条件已变化，已有输入与选择继续保留。')
  return value
}
function publishedSource(value: ItemDetail, itemId: string, expected?: AiSource, summary?: ItemSummary): AiSource {
  const published = value?.published
  if (!value || value.id !== itemId || value.status !== 'ACTIVE' || !published || !['NOTE', 'CONCEPT'].includes(value.kind)
    || published.id !== value.currentVersionId || published.number !== value.versionNumber
    || summary && (value.kind !== summary.kind || published.number !== summary.versionNumber)) throw new ApiError(409, 'AI_SOURCE_CHANGED', '所选知识已归档或发布版本已变化，请重新选择；不会自动换成新版本。')
  const result: AiSource = { itemId: value.id, versionId: published.id, number: published.number, kind: value.kind, title: published.title, body: published.body }
  if (!aiRelationEvidenceKnowledgeSourceValid(result)) throw new ApiError(502, 'RELATION_KNOWLEDGE_INVALID', '知识发布版本的身份或完整原文无法核对。')
  if (expected && JSON.stringify(result) !== JSON.stringify(expected)) throw new ApiError(409, 'AI_SOURCE_CHANGED', '知识的固定发布版本或正文已变化，原选择保留，请移出后重新选择。')
  return result
}
async function chooseKnowledge(summary: ItemSummary) {
  if (editingBlocked.value || !knowledgePicker.value || summary.kind !== knowledgePicker.value || summary.status !== 'ACTIVE'
    || !reviewIdValid(summary.id) || sources.value.length >= 5 || sources.value.some(source => source.itemId === summary.id)) return
  const ticket = ++sourceSequence; selecting.value = true; selectionError.value = ''; invalidatePreview()
  try {
    const value = await readCurrent<ItemDetail>(`/knowledge/items/${summary.id}`)
    if (!alive || ticket !== sourceSequence) return
    const selected = publishedSource(value, summary.id, undefined, summary)
    sources.value = [...sources.value, selected]; verifiedKnowledge.value.push(selected.itemId); knowledgePicker.value = null
  } catch (error) { if (alive && ticket === sourceSequence) selectionError.value = errorText(error) }
  finally { if (alive && ticket === sourceSequence) selecting.value = false }
}
async function recheckKnowledge() {
  if (editingBlocked.value || !sources.value.length) return
  const ticket = ++sourceSequence, selected = sources.value.map(source => ({ ...source }))
  selecting.value = true; selectionError.value = ''; verifiedKnowledge.value = []; invalidatePreview()
  try {
    await Promise.all(selected.map(async source => publishedSource(await readCurrent<ItemDetail>(`/knowledge/items/${source.itemId}`), source.itemId, source)))
    if (alive && ticket === sourceSequence) verifiedKnowledge.value = selected.map(source => source.itemId)
  } catch (error) { if (alive && ticket === sourceSequence) selectionError.value = errorText(error) }
  finally { if (alive && ticket === sourceSequence) selecting.value = false }
}
function removeKnowledge(id: string) { if (!editingBlocked.value) { sources.value = sources.value.filter(source => source.itemId !== id); verifiedKnowledge.value = verifiedKnowledge.value.filter(value => value !== id) } }
function clearReviewSource() {
  sourceIdentity.value = undefined; savedSource.value = undefined; sourceGeneration.value = -1; fragments.value = []
  fieldKey.value = undefined; rangeStart.value = ''; rangeEnd.value = ''; sourcePicker.value = false; selectionError.value = ''; invalidatePreview()
}
function chooseKind(value: SourceKind) {
  if (editingBlocked.value || value === sourceKind.value) return
  if ((sourceIdentity.value || rangeStart.value || rangeEnd.value) && !window.confirm('切换来源类别会移出当前反馈或回忆及其片段，知识选择与整理要求保留。确定切换？')) return
  clearReviewSource(); sourceKind.value = value
}
async function readReviewSource(identity: SourceIdentity): Promise<SavedSource> {
  if (identity.kind === 'AI_FEEDBACK') {
    const selected = identity.feedback
    const value = await readCurrent<RelationAiFeedbackEvidenceSource>(relationAiFeedbackEvidencePaths.source(selected.taskId, selected.feedbackId))
    if (!relationAiFeedbackEvidenceSourceValid(value, selected.taskId, selected.feedbackId, selected)) throw new ApiError(502, 'RELATION_AI_FEEDBACK_INVALID', 'AI 反馈与所选任务、单项编号或确切提交不一致，请重新核对。')
    // 固定输入仅含报告身份、完整限定和手选片段；观察与建议全文只用于当前页面选区。
    return { kind: 'AI_FEEDBACK', fixed: { taskId: value.taskId, attemptId: value.attemptId, feedbackId: value.feedbackId, reportHash: value.reportHash,
      answer: { ...value.answer }, answerNumber: value.answerNumber, answerCondition: value.answerCondition, level: value.level,
      reportLimitations: value.reportLimitations, validationNote: value.validationNote, dimension: value.dimension, coverage: value.coverage,
      decisionRevision: value.decisionRevision, decisionChoice: value.decisionChoice, condition: value.condition, basis: value.basis, limitation: value.limitation }, fields: [
      { field: 'OBSERVATION', text: value.observation }, { field: 'CONDITION', text: value.condition }, { field: 'BASIS', text: value.basis },
      { field: 'LIMITATION', text: value.limitation }, { field: 'SUGGESTION', text: value.suggestion } ] }
  }
  if (identity.kind === 'RECOLLECTION') {
    const expected = identity.recollection, current = await readCurrent<Recollection>(`/review/recollections/${expected.id}`)
    if (!recollectionValid(current, expected.id)) throw new ApiError(502, 'RELATION_RECOLLECTION_INVALID', '回忆来源读取不完整，请重新核对。')
    const version = current.currentVersion, content = version.content
    if (current.status !== 'ACTIVE' || current.revision !== expected.revision || version.id !== expected.versionId || version.number !== expected.versionNumber
      || content.title !== expected.title || content.baselineKind !== expected.baselineKind) throw new ApiError(409, 'AI_SOURCE_CHANGED', '回忆已归档或当前版本与目录不一致，原选择保留，请重新选择。')
    // 详情读取可能包含面试安排；只保留白名单固定背景与可手选字段，安排不会进入草稿或外发。
    return { kind: 'RECOLLECTION', fixed: { recollectionId: current.id, versionId: version.id, versionNumber: version.number, sourceRevision: current.revision,
      title: content.title, type: content.type, baselineKind: content.baselineKind, uncertainty: content.uncertainty }, fields: [
      { field: 'QUESTION', text: content.question }, { field: 'CONDITIONS', text: content.conditions },
      ...(content.baselineKind === 'RECOLLECTION' ? [{ field: 'ANSWER_FRAGMENT' as const, text: content.answerFragment }] : []),
      { field: 'FEEDBACK', text: content.feedback }, { field: 'UNCERTAINTY', text: content.uncertainty }, { field: 'GAP', text: content.gap } ] }
  }
  const value = await readCurrent<RelationFeedbackEvidenceSource>(relationFeedbackEvidencePaths.source(identity.feedbackId, identity.feedbackRevision))
  if (!relationFeedbackEvidenceSourceValid(value, identity.feedbackId, identity.feedbackRevision, identity)) throw new ApiError(502, 'RELATION_FEEDBACK_INVALID', '反馈修订、确切提交或固定背景与所选来源不一致，请重新核对。')
  return { kind: value.kind, fixed: { feedbackId: value.feedbackId, feedbackRevision: value.feedbackRevision, answer: { ...value.answer },
    answerNumber: value.answerNumber, answerCondition: value.answerCondition, dimension: value.dimension, coverage: value.coverage,
    decisionRevision: value.decisionRevision, decisionChoice: value.decisionChoice, condition: value.condition, basis: value.basis, limitation: value.limitation }, fields: [
    { field: 'OBSERVATION', text: value.observation }, { field: 'CONDITION', text: value.condition }, { field: 'BASIS', text: value.basis },
    { field: 'LIMITATION', text: value.limitation }, { field: 'SUGGESTION', text: value.suggestion } ] }
}
async function chooseSource(identity: SourceIdentity, recheck = false) {
  if (editingBlocked.value || !recheck && !sourcePicker.value) return
  if (identity.kind === 'RECOLLECTION' ? sourceKind.value !== 'RECOLLECTION' || !reviewIdValid(identity.recollection.id) || !reviewIdValid(identity.recollection.versionId)
    : identity.kind === 'AI_FEEDBACK' ? sourceKind.value !== 'AI_FEEDBACK' || !relationAiFeedbackChoiceValid(identity.feedback)
    : sourceKind.value !== 'FEEDBACK' || !relationFeedbackChoiceValid(identity)) return
  if (!recheck && savedSource.value && !window.confirm('重新选择会移出当前来源、片段和选区，知识选择与整理要求保留。确定替换？')) return
  const ticket = ++sourceSequence, fixed = savedSource.value
  selecting.value = true; selectionError.value = ''; sourceGeneration.value = -1; invalidatePreview()
  try {
    const value = await readReviewSource(identity)
    if (!alive || ticket !== sourceSequence) return
    if (recheck && (!fixed || JSON.stringify(value) !== JSON.stringify(fixed))) throw new ApiError(409, 'AI_SOURCE_CHANGED', '来源固定文字或决定已变化。原片段和选区保留，请重新选择来源后授权。')
    if (!recheck) { fragments.value = []; fieldKey.value = value.fields[0]?.field; rangeStart.value = ''; rangeEnd.value = '' }
    sourceIdentity.value = identity; savedSource.value = value; sourceGeneration.value = accessGeneration; sourcePicker.value = false
  } catch (error) { if (alive && ticket === sourceSequence) selectionError.value = errorText(error) }
  finally { if (alive && ticket === sourceSequence) selecting.value = false }
}
function chooseRecollection(item: RecollectionPage['items'][number]) {
  void chooseSource({ kind: 'RECOLLECTION', recollection: { id: item.id, title: item.title, versionId: item.versionId, versionNumber: item.versionNumber, revision: item.revision, baselineKind: item.baselineKind } })
}
function chooseAiFeedback(value: RelationAiFeedbackChoice) { void chooseSource({ kind: 'AI_FEEDBACK', feedback: { ...value, answer: { ...value.answer } } }) }
function removeSource() {
  if (editingBlocked.value || !window.confirm('移出当前来源会同时移出所选片段和未加入选区。确定移出？')) return
  clearReviewSource()
}
function captureSelection(event: Event) {
  if (editingBlocked.value || !sourceReady.value || !selectedField.value) return
  const area = event.target as HTMLTextAreaElement, original = selectedField.value.text
  if (area.value !== original.replace(/\r\n?/g, '\n') || area.selectionEnd <= area.selectionStart) return
  // textarea 会归一换行，提交范围必须恢复成保存原文的 UTF-16 下标。
  const offset = (display: number) => { let raw = 0, shown = 0; while (raw < original.length && shown < display) { raw += original[raw] === '\r' && original[raw + 1] === '\n' ? 2 : 1; shown++ }; return raw }
  rangeStart.value = String(offset(area.selectionStart)); rangeEnd.value = String(offset(area.selectionEnd))
}
function addFragment() { if (!editingBlocked.value && sourceReady.value && !rangeProblem.value && range.value) { fragments.value = [...fragments.value, { ...range.value }]; rangeStart.value = ''; rangeEnd.value = '' } }
function removeFragment(index: number) { if (!editingBlocked.value) fragments.value = fragments.value.filter((_, position) => position !== index) }
function previewEnvelopeValid(value: AiPreview) {
  const budget = value?.budget, manifest = value?.manifest
  return Boolean(manifest && typeof value.manifestHash === 'string' && /^[0-9a-f]{64}$/.test(value.manifestHash)
    && budget && /^\d{4}-\d{2}-\d{2}$/.test(budget.date) && budget.currency === manifest.configuration.currency
    && [budget.limitMicros, budget.reportedMicros, budget.reservedMicros, budget.unknownMicros, budget.remainingMicros].every(item => Number.isSafeInteger(item) && item >= 0))
}
async function prepare() {
  if (editingBlocked.value || !configured.value || problem.value || !request.value || !expectedInput.value
    || !(sourceKind.value === 'AI_FEEDBACK' ? aiRelationAiFeedbackRequestValid(request.value) : aiRelationReviewRequestValid(request.value))) return
  invalidatePreview()
  const epoch = store.sessionEpoch, generation = accessGeneration, ticket = ++previewSequence, signature = requestSignature.value
  const snapshot = JSON.parse(signature) as PreviewRequest, selected = sources.value.map(source => ({ ...source }))
  const input = JSON.parse(JSON.stringify(expectedInput.value)) as FixedInput, path = paths.value.preview
  previewing.value = true; previewError.value = ''; message.value = ''
  const current = () => alive && !blocked.value && epoch === store.sessionEpoch && generation === accessGeneration && ticket === previewSequence && signature === requestSignature.value
  try {
    const value = await preview<AiPreview>(path, snapshot)
    if (!current()) return
    if (!sourcesMatch(value?.manifest, snapshot, selected, input) || !previewEnvelopeValid(value)) throw new ApiError(502, 'RELATION_REVIEW_PREVIEW_INVALID', '外发预览与知识版本、所选原文、固定背景、要求或预算信息不一致，请重新核对。')
    if (JSON.stringify(value.manifest.configuration) !== JSON.stringify(props.settings?.configuration)) { emit('refresh'); throw new ApiError(409, 'AI_CONFIG_CHANGED', '服务器配置已变化，请刷新配置后重新预览。') }
    prepared.value = value; preparedRequest.value = signature
  } catch (error) { if (current()) previewError.value = errorText(error) }
  finally { if (current()) previewing.value = false }
}
function receive(value: AiTask) {
  createdResult.value = value; state.dirty = false; state.locked = false; commandState.locked = false
  message.value = '关系候选任务已创建。请结合固定知识、复盘原文与判断限制逐项审阅。'; emit('refresh'); emit('created', value)
}
function initialReceiptMatches(value: AiTask, authorized: AiManifestRecord): boolean {
  const summary = value.task, attempt = value.attempts[0]
  // 创建与原键重放均返回首次落库回执，必须核对初始状态及本次授权的预留费用。
  return summary.status === 'QUEUED' && summary.revision === 0 && summary.attemptCount === 1
    && summary.errorCode === null && summary.createdAt === summary.updatedAt && value.attempts.length === 1
    && Boolean(attempt && reviewIdValid(attempt.id) && attempt.id !== summary.id && attempt.number === 1
      && attempt.status === 'QUEUED' && attempt.feeStatus === 'RESERVED' && attempt.reservedMicros === authorized.reservedMicros
      && attempt.accountedMicros === null && attempt.promptTokens === null && attempt.completionTokens === null
      && attempt.providerRequestId === null && attempt.errorCode === null && attempt.reconciliationNote === null
      && attempt.createdAt === summary.createdAt && attempt.sentAt === null && attempt.finishedAt === null)
}
function create() {
  if (editingBlocked.value || !configured.value || !prepared.value || !previewMatches.value || !consent.value || overBudget.value || problem.value || !expectedInput.value) return
  const snapshot = JSON.parse(preparedRequest.value) as PreviewRequest
  const selected = sources.value.map(source => ({ ...source })), input = JSON.parse(JSON.stringify(expectedInput.value)) as FixedInput
  const authorized = JSON.parse(JSON.stringify(prepared.value.manifest)) as AiManifestRecord, fingerprint = aiCandidateManifestFingerprint(authorized)
  const body: AiRelationReviewCreateRequest | AiRelationAiFeedbackCreateRequest = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  const taskMatches = sourceKind.value === 'AI_FEEDBACK' ? aiRelationAiFeedbackTaskMatches : aiRelationReviewTaskMatches
  knowledgePicker.value = null; sourcePicker.value = false; commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration
  let submitted: typeof writer.pending.value
  void writer.run<AiTask>(paths.value.create, 'POST', body, value => {
    if (!alive) return
    let valid = false
    try { valid = taskMatches(value, authorized) && initialReceiptMatches(value, authorized)
      && sourcesMatch(value.manifest, snapshot, selected, input) && aiCandidateManifestFingerprint(value.manifest) === fingerprint } catch { valid = false }
    // 回执异常仍可能已建立任务，固定原命令和幂等键，避免重复授权及费用预留。
    if (!valid) { writer.pending.value = submitted; writer.error.value = new ApiError(502, 'RELATION_REVIEW_RECEIPT_INVALID', '任务回执与原授权的知识、复盘来源、初始状态或费用不一致。输入和原请求保留，请用原请求恢复。'); return }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) deferred.value = value
    else receive(value)
  })
  submitted = writer.pending.value
}
function retryCommand() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
function close() {
  if (locked.value || !store.canLeave([stateKey])) return
  sourceSequence++; selecting.value = false; invalidatePreview(); sources.value = []; instruction.value = ''; clearReviewSource(); emit('close')
}
</script>

<template>
  <section class="relation-review-create" aria-label="从复盘原文生成知识关系候选">
    <header class="create-heading"><div><p class="section-label">新建 AI 任务</p><h2>从复盘原文提出知识关系</h2><p class="field-help">选择概念端点，再从一条人工反馈、AI 未审反馈或面后回忆中明确选取片段，核对外发清单后授权。</p></div><NButton text size="small" :disabled="locked" @click="close">关闭</NButton></header>
    <p v-if="!configured" class="notice">AI 未启用或配置未完整，仍可填写要求和选择来源。</p><p v-if="!readable" class="notice" role="status">登录或资料访问待确认，所选来源、字段和未加入选区继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="create-content">
      <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="createdResult" class="notice"><NButton text :disabled="!readable" @click="emit('created', createdResult)">打开已创建关系任务</NButton></p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable || writer.busy.value" @click="retryCommand">用原请求恢复</NButton></p><p v-if="deferred" class="notice" role="status">任务已创建，访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建任务</NButton></p>
      <template v-if="!createdResult">
        <section class="source-panel" aria-label="关系端点与知识依据"><header class="panel-heading"><h3>本次知识 · {{ sources.length }} / 5 · {{ conceptCount }} 个概念</h3><div class="row-actions"><NButton size="small" :disabled="editingBlocked || sources.length >= 5" :aria-expanded="knowledgePicker === 'CONCEPT'" @click="knowledgePicker = knowledgePicker === 'CONCEPT' ? null : 'CONCEPT'">选择概念</NButton><NButton size="small" :disabled="editingBlocked || sources.length >= 5" :aria-expanded="knowledgePicker === 'NOTE'" @click="knowledgePicker = knowledgePicker === 'NOTE' ? null : 'NOTE'">选择笔记依据</NButton></div></header><p class="field-help">选择 2–5 个当前发布知识，其中至少两个概念作为候选端点。完整知识正文与所选复盘来源一同进入外发清单。</p>
          <article v-for="source in sources" :key="source.itemId" class="selected-source"><header class="panel-heading"><div><RouterLink class="text-link" :to="aiSourceRoute(source)!">{{ source.title }} · v{{ source.number }}</RouterLink><p class="field-help">{{ aiSourceKindLabel(source.kind) }} · {{ source.body.length }} 个 UTF-16 码元</p></div><NButton text size="small" :disabled="editingBlocked" @click="removeKnowledge(source.itemId)">移出</NButton></header><details><summary>核对完整发布正文</summary><pre class="exact-text">{{ source.body }}</pre></details></article>
          <KnowledgePicker v-if="knowledgePicker && readable && !locked" :key="`${pickerGeneration}:${knowledgePicker}`" :kind="knowledgePicker" :disabled="editingBlocked || sources.length >= 5" :exclude-ids="sources.map(source => source.itemId)" @choose="chooseKnowledge" />
          <NButton v-if="sources.some(source => !verifiedKnowledge.includes(source.itemId))" size="small" :disabled="editingBlocked" @click="recheckKnowledge">重新核对所选知识版本</NButton>
        </section>
        <section class="source-panel" aria-label="复盘原文来源"><h3>复盘来源</h3><div class="row-actions"><NButton size="small" :disabled="editingBlocked" :aria-pressed="sourceKind === 'FEEDBACK'" @click="chooseKind('FEEDBACK')">自检／外部反馈</NButton><NButton size="small" :disabled="editingBlocked" :aria-pressed="sourceKind === 'AI_FEEDBACK'" @click="chooseKind('AI_FEEDBACK')">AI 未审反馈</NButton><NButton size="small" :disabled="editingBlocked" :aria-pressed="sourceKind === 'RECOLLECTION'" @click="chooseKind('RECOLLECTION')">面后回忆</NButton><NButton size="small" :disabled="editingBlocked" :aria-expanded="sourcePicker" @click="sourcePicker = !sourcePicker">{{ sourcePicker ? '收起来源目录' : savedSource ? '重新选择来源' : '选择来源' }}</NButton></div>
          <p class="field-help">{{ sourceKind === 'FEEDBACK' ? '仅读取所选反馈的当前修订文字、确切提交标识与当时决定。外部意见仍是待核实判断。' : sourceKind === 'AI_FEEDBACK' ? '明确选择后读取成功报告中的这一项反馈，保留报告身份、未审等级和当时决定。完整报告限制与反馈限定将随手选片段外发。' : '选择后会读取当前回忆详情；详情可能附带面试安排，外发仅含下列固定背景与手选原文。回忆保留记忆的不确定性。' }}</p>
          <RelationFeedbackPicker v-if="sourcePicker && sourceKind === 'FEEDBACK' && readable && !locked" :key="pickerGeneration" :disabled="editingBlocked" @select="chooseSource($event)" />
          <RelationAiFeedbackPicker v-if="sourcePicker && sourceKind === 'AI_FEEDBACK' && readable && !locked" :key="pickerGeneration" :disabled="editingBlocked" @select="chooseAiFeedback" />
          <RecollectionPicker v-if="sourcePicker && sourceKind === 'RECOLLECTION' && readable && !locked" :key="pickerGeneration" :disabled="editingBlocked" @select="chooseRecollection" />
          <template v-if="savedSource"><header class="panel-heading"><h4>{{ savedSource.kind === 'AI_FEEDBACK' ? 'AI 未审反馈' : aiReviewDraftKindLabels[savedSource.kind] }}</h4><div class="row-actions"><NButton v-if="sourceIdentity" text size="small" :disabled="editingBlocked" @click="chooseSource(sourceIdentity, true)">重新核对来源</NButton><NButton text size="small" :disabled="editingBlocked" @click="removeSource">移出来源</NButton></div></header><p v-if="!sourceReady" class="notice">来源需重新核对，原字段、片段与选区继续保留。</p>
            <template v-if="savedSource.kind === 'RECOLLECTION'"><p class="field-help">{{ savedSource.fixed.title }} · {{ questionTypeLabels[savedSource.fixed.type] }} · {{ recollectionBaselineLabels[savedSource.fixed.baselineKind] }}</p><p class="field-help">回忆 {{ savedSource.fixed.recollectionId }} · 版本 {{ savedSource.fixed.versionId }} · v{{ savedSource.fixed.versionNumber }} · 来源修订 {{ savedSource.fixed.sourceRevision }}</p><h4>固定外发：完整记忆不确定性</h4><pre class="exact-text">{{ savedSource.fixed.uncertainty }}</pre></template>
            <template v-else-if="savedSource.kind === 'AI_FEEDBACK'"><p class="field-help">任务 {{ savedSource.fixed.taskId }} · 成功尝试 {{ savedSource.fixed.attemptId }} · 单项 {{ savedSource.fixed.feedbackId }} · {{ savedSource.fixed.level }}</p><p class="field-help">固定报告标识 {{ savedSource.fixed.reportHash }} · {{ questionDimensionLabels[savedSource.fixed.dimension] }} · {{ reviewCoverageLabels[savedSource.fixed.coverage] }}</p><p class="field-help">固定外发的当时决定：修订 {{ savedSource.fixed.decisionRevision }} · {{ savedSource.fixed.decisionChoice ? reviewChoiceLabels[savedSource.fixed.decisionChoice] : '尚未决定' }}</p><p v-if="savedSource.fixed.decisionChoice === 'DISPUTED' || savedSource.fixed.decisionChoice === 'IGNORED'" class="notice" role="status">这项反馈当时{{ savedSource.fixed.decisionChoice === 'DISPUTED' ? '已被提出争议' : '已被忽略' }}，该决定随原文保留，生成关系候选仍保持 AI 未审。</p><div v-for="field in (['reportLimitations', 'validationNote', 'condition', 'basis', 'limitation'] as const)" :key="field" class="source-field"><h4>{{ ({ reportLimitations: '固定外发：完整报告限制', validationNote: '固定外发：报告验证说明', condition: '固定外发：适用条件', basis: '固定外发：观察依据', limitation: '固定外发：判断限制' })[field] }}</h4><pre class="exact-text">{{ savedSource.fixed[field] || '（空）' }}</pre></div></template>
            <template v-else><p class="field-help">反馈 {{ savedSource.fixed.feedbackId }} · 修订 {{ savedSource.fixed.feedbackRevision }} · 提交 {{ savedSource.fixed.answer.answerId }} · 第 {{ savedSource.fixed.answerNumber }} 版</p><p class="field-help">固定外发的当时决定：修订 {{ savedSource.fixed.decisionRevision }} · {{ savedSource.fixed.decisionChoice ? reviewChoiceLabels[savedSource.fixed.decisionChoice] : '尚未决定' }}</p><div v-for="field in (['condition', 'basis', 'limitation'] as const)" :key="field" class="source-field"><h4>{{ ({ condition: '固定外发：适用条件', basis: '固定外发：观察依据', limitation: '固定外发：判断限制' })[field] }}</h4><pre class="exact-text">{{ savedSource.fixed[field] || '（空）' }}</pre></div></template>
          </template>
        </section>
        <p v-if="selecting" class="field-help" role="status">正在核对确切来源，已有选择继续保留…</p><p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p>
        <section v-if="savedSource" class="source-panel" aria-label="手选复盘原文片段"><h3>明确选择片段 · {{ fragments.length }} / 8</h3><label class="form-field">原文字段<NSelect v-model:value="fieldKey" :options="fields.map(field => ({ value: field.field, label: aiReviewDraftFieldLabels[field.field] }))" :disabled="editingBlocked || !sourceReady" aria-label="选择复盘原文字段" /></label><label v-if="selectedField" class="form-field" :for="`${viewId}-source`">{{ aiReviewDraftFieldLabels[selectedField.field] }}<textarea :id="`${viewId}-source`" :value="selectedField.text" readonly rows="8" :disabled="editingBlocked || !sourceReady" :aria-describedby="`${viewId}-range-help`" @select="captureSelection" @keyup="captureSelection" @mouseup="captureSelection" /></label><p :id="`${viewId}-range-help`" class="field-help">拖选文字或使用 Shift + 方向键，点击“加入片段”。位置按原文 UTF-16 计数，从 0 开始，结束位置不包含在片段中。</p><div class="range-fields"><label class="form-field">起始位置<NInput v-model:value="rangeStart" :disabled="editingBlocked || !sourceReady" :input-props="{ inputmode: 'numeric', 'aria-label': '复盘片段起始位置' }" /></label><label class="form-field">结束位置<NInput v-model:value="rangeEnd" :disabled="editingBlocked || !sourceReady" :input-props="{ inputmode: 'numeric', 'aria-label': '复盘片段结束位置' }" /></label><NButton size="small" :disabled="editingBlocked || !sourceReady || !!rangeProblem" @click="addFragment">加入片段</NButton></div><p v-if="rangeProblem" class="field-help">{{ rangeProblem }}</p><pre v-else-if="range" class="exact-text selected-quote">{{ range.quote }}</pre>
          <article v-for="(fragment, index) in fragments" :key="`${fragment.field}/${fragment.start}/${fragment.end}`" class="selected-source"><header class="panel-heading"><h4>{{ aiReviewDraftFieldLabels[fragment.field] }} · [{{ fragment.start }}, {{ fragment.end }})</h4><NButton text size="small" :disabled="editingBlocked" @click="removeFragment(index)">移出片段</NButton></header><pre class="exact-text">{{ fragment.quote }}</pre></article>
        </section>
        <label class="form-field">关系整理要求<NInput v-model:value="instruction" :disabled="editingBlocked" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 8 }" :input-props="{ 'aria-label': '复盘关系整理要求' }" placeholder="例如：根据我选中的观察解释两个概念的关系，并保留反馈限制与回忆的不确定性。" /><span class="field-help">可留空，最多 1000 个 UTF-16 码元，按原文发送。候选可为零条，生成不会自动建立正式关系。</span></label>
        <div class="row-actions"><NButton :loading="previewing" :disabled="editingBlocked || !configured || !!problem" @click="prepare">预览完整外发清单</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div><p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
        <section v-if="prepared && previewMatches" class="consent-panel"><AiManifest :manifest="prepared.manifest" :budget="prepared.budget" /><p v-if="overBudget" class="notice error">本次预留超过当前剩余预算，请核对预算后重新预览。</p><AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="editingBlocked || overBudget || !configured">我已核对完整知识正文、固定复盘背景、手选片段与整理要求，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice><NButton type="primary" :loading="writer.busy.value" :disabled="editingBlocked || !consent || overBudget || !configured || !!problem" @click="create">授权本次外发并生成关系候选</NButton><p class="field-help">来源、片段、要求、配置或访问条件变化后需重新预览。结果未知时保留原请求和原幂等键；当前输入只保存在本页。</p></section>
      </template>
    </div>
  </section>
</template>

<style scoped>
.relation-review-create, .create-content { display: grid; gap: 23px; min-width: 0; }.relation-review-create { padding: 25px 27px 30px; }.create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; flex-wrap: wrap; }.create-heading > div, .panel-heading > div { min-width: 0; }h2 { margin: 7px 0 9px; font-size: 24px; text-wrap: balance; }h3 { margin: 0; font-size: 16px; text-wrap: balance; }h4 { margin: 0; font-size: 13px; overflow-wrap: anywhere; }.source-panel, .selected-source, .source-field, .form-field { display: grid; gap: 12px; min-width: 0; }.source-panel, .selected-source { padding-top: 16px; border-top: 1px solid var(--line); }.form-field { color: var(--muted); font-size: 13px; }.form-field > textarea { box-sizing: border-box; width: 100%; min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); color: var(--ink); font: 13px/1.75 var(--font-body, sans-serif); resize: vertical; }textarea:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }.range-fields { display: grid; grid-template-columns: 1fr 1fr auto; align-items: end; gap: 12px; }.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }.selected-quote { border: 1px solid var(--brand); }.consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); min-width: 0; }.consent-panel > .n-button { justify-self: start; }.row-actions { display: flex; flex-wrap: wrap; gap: 9px 13px; align-items: center; }.field-help, .text-link, .notice { overflow-wrap: anywhere; }summary { cursor: pointer; font-size: 12px; color: var(--muted); }details > pre { margin-top: 10px; }@media (max-width: 700px) { .relation-review-create { padding: 22px 17px; }h2 { font-size: 21px; }.range-fields { grid-template-columns: 1fr 1fr; }.range-fields > .n-button { justify-self: start; } }
</style>
