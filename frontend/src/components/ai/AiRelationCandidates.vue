<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { Relation } from '../../api/client'
import type { AiAdoptRelation, AiCitation, AiMaterialCitation, AiRelationContent, AiRelationSuggestion, AiSupplementRelation, AiTask, AiTrainingDraftCitation } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate, aiPreparationPlanTextValid, aiRelationCandidateFingerprint, aiRelationDecisionFingerprint, aiRelationDecisionValid, aiRelationManifestMatches, aiRelationTaskMatches, aiSourceKindLabel, aiSourceRoute, aiTrainingDraftFieldLabels } from '../../api/ai'
import { aiRelationEvidenceMaterialCitationValid, aiRelationEvidenceSuggestionCitationsValid, aiRelationEvidenceTrainingCitationValid } from '../../api/aiRelationEvidence'
import { aiRelationReviewSuggestionCitationsValid } from '../../api/aiRelationReview'
import { aiRelationAiFeedbackSuggestionCitationsValid } from '../../api/aiRelationAiFeedback'
import { relationMatchValid, relationSupplementProblem } from '../../api/relationSupplement'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import RelationSupplements from '../RelationSupplements.vue'
import AiRelationEvidenceInput from './AiRelationEvidenceInput.vue'
import AiReviewDraftInput from './AiReviewDraftInput.vue'
import AiReviewCitations from './AiReviewCitations.vue'
import AiRelationAiFeedbackInput from './AiRelationAiFeedbackInput.vue'

type Edit = { content: AiRelationContent; initial: string; confirmed: boolean; mode: 'create' | 'supplement';
  match?: Relation | null; matching: boolean; matchError: string; matchRequest: number }
type Action = 'adopt' | 'supplement' | 'ignore'
type Receipt = { task: AiTask; taskId: string; expectedRevision: number; candidateId: string; action: Action;
  content?: AiRelationContent; relationId?: string; manifestFingerprint?: string; candidateFingerprint: string;
  issuedDecisions: ReadonlyMap<string, string> }
const props = defineProps<{ task: AiTask; refreshTask: () => Promise<AiTask | undefined>; disabled?: boolean; peerLocked?: boolean }>()
const emit = defineEmits<{ received: [task: AiTask]; locked: [value: boolean] }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const writer = useCommand()
const edits = reactive(new Map<string, Edit>())
const deferred = shallowRef<Receipt>()
const reviewed = ref(taskSignature(props.task))
const taskLoading = ref(false)
const taskError = ref('')
const message = ref('')
const commandCandidateId = ref('')
const draftState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let taskRequest = 0
let commandEpoch = store.sessionEpoch
let accessGeneration = 0
let commandAccessGeneration = 0
let matchRequest = 0
let originalCommand: typeof writer.pending.value

const relationOptions: { label: string; value: AiRelationContent['type'] }[] = [
  { label: '前置于 →', value: 'PRECEDES' }, { label: '组成部分 →', value: 'PART_OF' },
  { label: '应用于 →', value: 'APPLIED_IN' }, { label: '相关（无方向）', value: 'RELATED' },
  { label: '对比（无方向）', value: 'CONTRASTS' },
]
const candidate = computed(() => props.task.relationCandidate ?? null)
const directEvidence = computed(() => props.task.manifest.relationEvidence ?? null)
const reviewEvidence = computed(() => props.task.manifest.reviewDraft ?? null)
const aiFeedbackEvidence = computed(() => props.task.manifest.relationAiFeedback ?? null)
const decisions = computed(() => new Map((props.task.relationDecisions ?? []).map(item => [item.candidateId, item])))
const rows = computed(() => (candidate.value?.relations ?? []).map(suggestion => ({ suggestion,
  edit: edits.get(suggestion.id), decision: decisions.value.get(suggestion.id), problem: suggestionProblem(suggestion) })))
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const blocked = computed(() => !readable.value || Boolean(props.peerLocked) || locked.value)
const sourceChecksValid = computed(() => aiRelationManifestMatches(props.task.manifest))
const taskStale = computed(() => reviewed.value !== taskSignature(props.task))
const dirty = computed(() => [...edits.values()].some(edit => edit.confirmed || JSON.stringify(edit.content) !== edit.initial))
const pendingCount = computed(() => rows.value.filter(row => !row.decision).length)

watch([dirty, locked], ([changed, waiting]) => {
  draftState.dirty = changed
  commandState.locked = waiting
  store.changes.set('ai-adoption', draftState)
  store.changes.set('ai-adoption-command', commandState)
  emit('locked', waiting)
}, { immediate: true, flush: 'sync' })
watch(() => JSON.stringify([...edits].map(([id, edit]) => [id, edit.content])), revokeConfirmation, { flush: 'sync' })
watch([() => taskSignature(props.task), () => JSON.stringify((props.task.relationDecisions ?? []).map(aiRelationDecisionFingerprint)),
  () => aiRelationCandidateFingerprint(props.task.relationCandidate)], revokeConfirmation, { flush: 'sync' })
watch([() => store.sessionEpoch, readable, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  accessGeneration++
  cancelRead()
  reviewed.value = ''
  revokeConfirmation()
}, { flush: 'sync' })
watch(() => props.peerLocked, value => { if (value) { cancelRead(); revokeConfirmation() } }, { flush: 'sync' })
watch([writer.error, writer.pending], ([error, pending]) => {
  if (!(error instanceof ApiError) || pending) return
  revokeConfirmation()
  if (['REVISION_CONFLICT', 'AI_STATE_CONFLICT', 'AI_CANDIDATE_DECIDED', 'AI_SOURCE_CHANGED', 'SOURCE_CHANGED',
    'ITEM_ARCHIVED', 'RELATION_SOURCE_CHANGED', 'RELATION_ENDPOINT_ARCHIVED'].includes(error.code)) reviewed.value = ''
})
onUnmounted(() => {
  alive = false
  cancelRead()
  if (store.changes.get('ai-adoption') === draftState) store.changes.delete('ai-adoption')
  if (store.changes.get('ai-adoption-command') === commandState) store.changes.delete('ai-adoption-command')
  emit('locked', false)
})

function taskSignature(task: AiTask): string {
  return JSON.stringify([task.task.id, task.task.revision, task.task.status, task.sourcesCurrent,
    aiRelationManifestMatches(task.manifest) ? aiCandidateManifestFingerprint(task.manifest) : null])
}
function revokeConfirmation() {
  for (const edit of edits.values()) {
    edit.confirmed = false
    edit.match = undefined
    edit.matching = false
    edit.matchError = ''
    edit.matchRequest = 0
  }
}
function cancelRead() { taskRequest++; taskLoading.value = false }
function symmetric(type: AiRelationContent['type']) { return type === 'RELATED' || type === 'CONTRASTS' }
function sourceFor(itemId: string) {
  const matches = props.task.manifest.sources.filter(source => source.itemId === itemId)
  return matches.length === 1 ? matches[0] : undefined
}
function citationSource(citation: AiCitation) {
  const source = sourceFor(citation.itemId)
  return source?.versionId === citation.versionId ? source : undefined
}
function materialCitationSource(citation: AiMaterialCitation) {
  const input = directEvidence.value
  return input && aiRelationEvidenceMaterialCitationValid(citation, input)
    ? input.materials.find(source => source.excerptId === citation.excerptId) : undefined
}
function trainingCitationFragment(citation: AiTrainingDraftCitation) {
  const input = directEvidence.value
  return input?.training && aiRelationEvidenceTrainingCitationValid(citation, input)
    ? input.training.fragments.find(fragment => fragment.field === citation.field && fragment.elementId === citation.elementId
      && fragment.start <= citation.start && fragment.end >= citation.end) : undefined
}
function citationCount(suggestion: AiRelationSuggestion) {
  return suggestion.citations.length + (suggestion.materialCitations?.length ?? 0) + (suggestion.trainingCitations?.length ?? 0)
    + (suggestion.reviewCitations?.length ?? 0)
}
function relationText(content: AiRelationContent): string {
  const source = sourceFor(content.sourceItemId)?.title ?? '来源概念待核对'
  const target = sourceFor(content.targetItemId)?.title ?? '目标概念待核对'
  const phrases: Record<AiRelationContent['type'], string> = { PRECEDES: '前置于', PART_OF: '组成部分', APPLIED_IN: '应用于', RELATED: '相关', CONTRASTS: '对比' }
  return `${source} ${symmetric(content.type) ? '↔' : '→'} ${target} · ${phrases[content.type] ?? '类型待核对'}`
}
function splitSurrogate(value: string, offset: number) {
  return offset > 0 && offset < value.length && /[\uD800-\uDBFF]/.test(value[offset - 1]!) && /[\uDC00-\uDFFF]/.test(value[offset]!)
}
// 历史关系正文沿用 Java isBlank；新补充理由另由补充契约执行更严格的空白检查。
const hasRelationText = (value: string) => /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
function suggestionProblem(suggestion: AiRelationSuggestion): string {
  if (!/^r([1-9]|10)$/.test(suggestion.id) || (candidate.value?.relations.filter(item => item.id === suggestion.id).length ?? 0) !== 1
    || sourceFor(suggestion.sourceItemId)?.kind !== 'CONCEPT' || sourceFor(suggestion.targetItemId)?.kind !== 'CONCEPT'
    || suggestion.sourceItemId === suggestion.targetItemId || !relationOptions.some(option => option.value === suggestion.type)
    || !aiPreparationPlanTextValid(suggestion.reason, 1000) || !hasRelationText(suggestion.reason)
    || !Array.isArray(suggestion.citations)) return '候选身份、端点或理由不完整，请重新读取任务核对。'
  const input = directEvidence.value
  if (aiFeedbackEvidence.value) {
    if (input || reviewEvidence.value || !aiRelationAiFeedbackSuggestionCitationsValid(suggestion, aiFeedbackEvidence.value)) return 'AI 反馈引用无法与固定原观察的已选片段对应，请重新读取任务核对。'
  } else if (reviewEvidence.value) {
    if (input || !aiRelationReviewSuggestionCitationsValid(suggestion, reviewEvidence.value)) return '复盘原文引用无法与固定反馈或回忆片段对应，请重新读取任务核对。'
  } else if (suggestion.reviewCitations != null) return '这条候选混入了未授权的复盘引用，请重新读取任务核对。'
  else if (input) {
    if (!aiRelationEvidenceSuggestionCitationsValid(suggestion, input)) return '直接原文引用无法与固定资料或训练片段对应，请重新读取任务核对。'
  } else if (suggestion.materialCitations != null || suggestion.trainingCitations != null) return '知识关系候选混入了未授权的直接来源引用，请重新读取任务核对。'
  if (citationCount(suggestion) < 1 || citationCount(suggestion) > 6) return '每条候选须有 1–6 条有效原文引用。'
  const positions = new Set<string>()
  for (const citation of suggestion.citations) {
    const source = citationSource(citation)
    const position = JSON.stringify([citation.itemId, citation.versionId, citation.start, citation.end])
    if (!source || !Number.isSafeInteger(citation.start) || !Number.isSafeInteger(citation.end) || citation.start < 0
      || citation.end <= citation.start || citation.end > source.body.length || splitSurrogate(source.body, citation.start)
      || splitSurrogate(source.body, citation.end) || citation.quote !== source.body.slice(citation.start, citation.end)
      || positions.has(position)) return '原引用无法与清单逐字对应，请重新读取任务核对。'
    positions.add(position)
  }
  return ''
}
function undecided(suggestion: AiRelationSuggestion) {
  return props.task.task.purpose === 'RELATION_DRAFT' && props.task.task.status === 'SUCCEEDED'
    && /^r([1-9]|10)$/.test(suggestion.id) && !decisions.value.has(suggestion.id)
}
function contentProblem(suggestion: AiRelationSuggestion, content: AiRelationContent): string {
  if (!((content.sourceItemId === suggestion.sourceItemId && content.targetItemId === suggestion.targetItemId)
    || (content.sourceItemId === suggestion.targetItemId && content.targetItemId === suggestion.sourceItemId))) return '两端只能使用这条原候选的两个概念。'
  if (!relationOptions.some(option => option.value === content.type)) return '请选择支持的关系类型。'
  if (!aiPreparationPlanTextValid(content.reason, 1000) || !hasRelationText(content.reason)) return '最终理由须为 1–1000 个 UTF-16 码元，且不含 NUL 或不完整字符。'
  return ''
}
function canAdopt(suggestion: AiRelationSuggestion, edit: Edit) {
  return !blocked.value && !taskLoading.value && !taskStale.value && undecided(suggestion) && props.task.sourcesCurrent
    && sourceChecksValid.value && !suggestionProblem(suggestion) && !contentProblem(suggestion, edit.content)
}
function canSupplement(suggestion: AiRelationSuggestion, edit: Edit) {
  const content = normalized(edit.content)
  return edit.mode === 'supplement' && canAdopt(suggestion, edit) && !edit.matching && Boolean(edit.match)
    && !supplementProblem(edit) && relationMatchValid(edit.match, content.sourceItemId, content.targetItemId, content.type)
}
function supplementProblem(edit: Edit): string {
  return relationSupplementProblem({ reason: edit.content.reason,
    sources: props.task.manifest.sources.map(source => ({ itemId: source.itemId, versionId: source.versionId })), confirmed: true })
}
function open(suggestion: AiRelationSuggestion) {
  if (blocked.value || taskLoading.value || !undecided(suggestion) || edits.has(suggestion.id) || suggestionProblem(suggestion)) return
  const content = { sourceItemId: suggestion.sourceItemId, targetItemId: suggestion.targetItemId, type: suggestion.type, reason: suggestion.reason }
  edits.set(suggestion.id, { content, initial: JSON.stringify(content), confirmed: false, mode: 'create',
    matching: false, matchError: '', matchRequest: 0 })
}
function setMode(edit: Edit, mode: unknown) {
  if (blocked.value || taskLoading.value || (mode !== 'create' && mode !== 'supplement')) return
  edit.mode = mode
  revokeConfirmation()
}
async function findExisting(suggestion: AiRelationSuggestion, edit: Edit) {
  if (!canAdopt(suggestion, edit) || edit.mode !== 'supplement' || edit.matching) return
  const content = normalized(edit.content)
  const signature = taskSignature(props.task)
  const contentSnapshot = JSON.stringify(edit.content)
  const epoch = store.sessionEpoch
  const request = ++matchRequest
  edit.confirmed = false
  edit.match = undefined
  edit.matchError = ''
  edit.matching = true
  edit.matchRequest = request
  const current = () => alive && !blocked.value && !taskLoading.value && !taskStale.value && epoch === store.sessionEpoch
    && edits.get(suggestion.id) === edit && edit.mode === 'supplement' && edit.matchRequest === request
    && signature === taskSignature(props.task) && contentSnapshot === JSON.stringify(edit.content)
  try {
    const params = new URLSearchParams({ sourceId: content.sourceItemId, targetId: content.targetItemId, type: content.type })
    const value = await read<Relation | null>(`/knowledge/relations/match?${params}`)
    if (!current()) return
    if (!relationMatchValid(value, content.sourceItemId, content.targetItemId, content.type)) {
      throw new ApiError(0, 'RELATION_MATCH_INVALID', '查询结果与当前端点、方向或类型不一致，请重新查找。')
    }
    edit.match = value
  } catch (error) {
    if (current()) edit.matchError = errorText(error)
  } finally {
    if (edit.matchRequest === request) edit.matching = false
  }
}
function close(candidateId: string) {
  if (blocked.value || taskLoading.value) return
  const edit = edits.get(candidateId)
  if (edit && (edit.confirmed || JSON.stringify(edit.content) !== edit.initial)
    && !window.confirm('关闭这条候选的编辑表单会丢弃当前未保存的改写，继续？')) return
  edits.delete(candidateId)
}
function swap(suggestion: AiRelationSuggestion, edit: Edit) {
  if (blocked.value || taskLoading.value || !undecided(suggestion) || symmetric(edit.content.type)) return
  const { sourceItemId, targetItemId } = edit.content
  edit.content = { ...edit.content, sourceItemId: targetItemId, targetItemId: sourceItemId }
}
async function reviewTask() {
  if (blocked.value || taskLoading.value) return
  const epoch = store.sessionEpoch
  const request = ++taskRequest
  taskLoading.value = true
  taskError.value = ''
  revokeConfirmation()
  try {
    const value = await props.refreshTask()
    // 等父查询把最新快照传入；候选不能再以独立读取覆盖同修订的较新来源状态。
    await nextTick()
    if (!alive || blocked.value || epoch !== store.sessionEpoch || request !== taskRequest) return
    if (!value || value.task.id !== props.task.task.id || value.task.purpose !== 'RELATION_DRAFT'
      || taskSignature(value) !== taskSignature(props.task)) {
      taskError.value = '任务状态已继续变化，请重新读取。'
      return
    }
    reviewed.value = taskSignature(value)
    message.value = '已核对最新任务与逐项决定，本地改写继续保留，请重新确认要采用的条目。'
  } catch (error) {
    if (alive && request === taskRequest && epoch === store.sessionEpoch) taskError.value = errorText(error)
  } finally { if (alive && request === taskRequest) taskLoading.value = false }
}
function normalized(content: AiRelationContent): AiRelationContent {
  const value = { ...content }
  if (symmetric(value.type) && value.sourceItemId > value.targetItemId) {
    const source = value.sourceItemId
    value.sourceItemId = value.targetItemId
    value.targetItemId = source
  }
  return value
}
function receive(receipt: Receipt) {
  const { task, taskId, expectedRevision, candidateId, action, content, relationId, manifestFingerprint, candidateFingerprint, issuedDecisions } = receipt
  const reject = (detail: string) => {
    reviewed.value = ''
    revokeConfirmation()
    taskError.value = detail
    // 回执无法核对时仍保留发出时的请求与键，不能把未知结果当作未执行后另发命令。
    writer.pending.value = originalCommand
    writer.error.value = new ApiError(0, 'RELATION_DECISION_INVALID', detail)
  }
  if (!aiRelationTaskMatches(task) || task.task.id !== taskId || taskId !== props.task.task.id
    || task.task.status !== 'SUCCEEDED' || task.task.revision !== expectedRevision + 1
    || aiRelationCandidateFingerprint(task.relationCandidate) !== candidateFingerprint) {
    reject('决定回执的任务或原候选与发出时不一致；本地改写与原请求继续保留，请恢复并核对。')
    return
  }
  // 决定回执必须对应发送时的固定清单；来源是否仍为当前版不改变历史身份。
  if (!manifestFingerprint || !aiRelationManifestMatches(task.manifest)
    || aiCandidateManifestFingerprint(task.manifest) !== manifestFingerprint
    || !aiRelationTaskMatches(props.task) || aiCandidateManifestFingerprint(props.task.manifest) !== manifestFingerprint
    || aiRelationCandidateFingerprint(props.task.relationCandidate) !== candidateFingerprint) {
    reject('决定回执的固定清单与原请求不一致；本地改写与原请求继续保留，请恢复并核对。')
    return
  }
  const matches = task.relationDecisions?.filter(item => item.candidateId === candidateId) ?? []
  const decision = matches[0]
  if (matches.length !== 1 || !decision || !aiRelationDecisionValid(decision)
    || decision.status !== (action !== 'ignore' ? 'ADOPTED' : 'IGNORED')
    || (action !== 'ignore' && (!decision.relationId
      || !decision.content || !content
      || decision.content.sourceItemId !== content.sourceItemId || decision.content.targetItemId !== content.targetItemId
      || decision.content.type !== content.type || decision.content.reason !== content.reason))
    || (action === 'supplement' && (decision.relationId !== relationId || !decision.supplementId))
    || (action !== 'supplement' && decision.supplementId != null)
    || (action === 'ignore' && (decision.content != null || decision.relationId != null))) {
    reject('服务器回执未包含与原操作、目标关系和内容一致的决定；本地改写与原请求继续保留，请恢复并核对。')
    return
  }
  const returned = new Map((task.relationDecisions ?? []).map(item => [item.candidateId, aiRelationDecisionFingerprint(item)]))
  // 一次决定只追加目标条目；先核完整发出快照与当前终态，再结束草稿和原请求。
  if (issuedDecisions.has(candidateId) || returned.size !== issuedDecisions.size + 1
    || [...issuedDecisions].some(([id, fingerprint]) => returned.get(id) !== fingerprint)
    || [...decisions.value].some(([id, previous]) => returned.has(id)
      ? returned.get(id) !== aiRelationDecisionFingerprint(previous) : task.task.revision >= props.task.task.revision)) {
    reject('回执中的既有决定与发出时或当前记录不一致；当前任务、改写与原请求继续保留，请恢复并核对。')
    return
  }
  // 一条命令只结束对应编辑；迟到回执交给详情按 candidateId 合并，不回退其他条目的决定。
  edits.delete(candidateId)
  revokeConfirmation()
  reviewed.value = task.task.revision < props.task.task.revision ? '' : taskSignature(task)
  emit('received', task)
  originalCommand = undefined
  message.value = action === 'supplement' ? `${candidateId} 已补充到原关系，理由与当次全部固定来源已保存。`
    : action === 'adopt' ? `${candidateId} 已采用，正式关系与采用记录已保存。` : `${candidateId} 已忽略，原候选与费用记录继续保留。`
  if (action !== 'ignore') void queryClient.invalidateQueries({ queryKey: ['knowledge', store.sessionEpoch] })
}
function run(candidateId: string, action: Action, payload: AiAdoptRelation | AiSupplementRelation | { expectedRevision: number }) {
  if (blocked.value || taskLoading.value) return
  const manifestFingerprint = aiRelationManifestMatches(props.task.manifest)
    ? aiCandidateManifestFingerprint(props.task.manifest) : undefined
  if (!manifestFingerprint || !aiRelationTaskMatches(props.task) || payload.expectedRevision !== props.task.task.revision) {
    reviewed.value = ''
    revokeConfirmation()
    taskError.value = '固定清单不完整，请重新读取任务核对；本地改写继续保留。'
    return
  }
  commandEpoch = store.sessionEpoch
  commandAccessGeneration = accessGeneration
  commandCandidateId.value = candidateId
  taskError.value = ''
  message.value = ''
  const taskId = props.task.task.id
  const expectedRevision = payload.expectedRevision
  const candidateFingerprint = aiRelationCandidateFingerprint(props.task.relationCandidate)
  const issuedDecisions = new Map((props.task.relationDecisions ?? []).map(item => [item.candidateId, aiRelationDecisionFingerprint(item)]))
  const content = action !== 'ignore' ? { ...(payload as AiAdoptRelation).content } : undefined
  const relationId = action === 'supplement' ? (payload as AiSupplementRelation).relationId : undefined
  void writer.run<AiTask>(`/ai/tasks/${taskId}/relations/${candidateId}/${action}`, 'POST', payload, task => {
    if (!alive) return
    const receipt = { task, taskId, expectedRevision, candidateId, action, content, relationId, manifestFingerprint, candidateFingerprint, issuedDecisions }
    if (commandEpoch !== store.sessionEpoch || commandAccessGeneration !== accessGeneration || !readable.value || props.peerLocked) deferred.value = receipt
    else receive(receipt)
  })
  originalCommand = writer.pending.value
}
function adopt(suggestion: AiRelationSuggestion, edit: Edit) {
  if (edit.mode !== 'create' || !canAdopt(suggestion, edit) || !edit.confirmed) return
  run(suggestion.id, 'adopt', { expectedRevision: props.task.task.revision, content: normalized(edit.content), confirmed: true })
}
function supplement(suggestion: AiRelationSuggestion, edit: Edit) {
  if (!canSupplement(suggestion, edit) || !edit.confirmed || !edit.match) return
  run(suggestion.id, 'supplement', { expectedRevision: props.task.task.revision, relationId: edit.match.id,
    content: normalized(edit.content), confirmed: true })
}
function ignore(suggestion: AiRelationSuggestion) {
  if (blocked.value || taskLoading.value || taskStale.value || !undecided(suggestion)) return
  const edit = edits.get(suggestion.id)
  if (!window.confirm(`忽略后这条候选不能再采用；原候选和费用记录保留。${edit && JSON.stringify(edit.content) !== edit.initial ? '该条未保存的改写会放弃。' : ''}确认忽略 ${suggestion.id}？`)) return
  run(suggestion.id, 'ignore', { expectedRevision: props.task.task.revision })
}
function retryCommand() {
  if (!readable.value || props.peerLocked || writer.busy.value) return
  commandEpoch = store.sessionEpoch
  commandAccessGeneration = accessGeneration
  void writer.retry()
}
function receiveDeferred() {
  if (!readable.value || props.peerLocked || !deferred.value) return
  const receipt = deferred.value
  deferred.value = undefined
  receive(receipt)
}
</script>

<template>
  <section v-if="candidate" v-show="readable" class="relation-candidates" aria-label="知识关系候选与逐项决定" :inert="!readable || undefined">
    <header><p class="section-label">模型原候选 · 知识关系</p><h3>逐条审阅关系建议</h3><p class="copy">{{ candidate.summary }}</p><p class="field-help">{{ candidate.validationNote }}</p><p class="field-help">共 {{ rows.length }} 条，待决定 {{ pendingCount }} 条。可明确新建关系，或将依据补充到已存在的同类关系；页面内改写保存在当前页面。</p></header>
    <section class="limitations"><h4>建议的限制</h4><p class="copy">{{ candidate.limitations }}</p></section>
    <details v-if="directEvidence && sourceChecksValid" class="evidence"><summary>本任务固定的全部直接原文依据</summary><AiRelationEvidenceInput :input="directEvidence" /><p class="field-help">采用或补充会保留本次全部固定输入，不只保存模型实际引用的片段。</p></details>
    <details v-else-if="reviewEvidence && sourceChecksValid" class="evidence"><summary>本任务固定的全部复盘原文依据</summary><AiReviewDraftInput :input="reviewEvidence" /><p class="field-help">采用或补充会保留本次全部固定输入，包括当次反馈决定与限制或回忆不确定性。引用只能落入已选片段，固定背景不扩大引用范围。</p></details>
    <details v-else-if="aiFeedbackEvidence && sourceChecksValid" class="evidence"><summary>本任务固定的全部 AI 反馈依据</summary><AiRelationAiFeedbackInput :input="aiFeedbackEvidence" /><p class="field-help">采用或补充会保留本次完整快照、原报告限制、当时决定和全部已选片段。原观察保持 AI 未审等级，完整限定不扩大可引用范围。</p></details>
    <p v-if="!rows.length" class="notice" role="status">本次分析已完成，模型没有提出可供采用的关系。请结合上方说明与限制判断是否需要补充知识内容后另建任务；本次结果与费用记录保留。</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ commandCandidateId }} · {{ writer.error.value instanceof ApiError && writer.error.value.code === 'RELATION_EXISTS' ? '同端点、同类型的正式关系已经存在，本条尚未采用。可在本条编辑中选择“补充到已有关系”，查找并核对后另行确认；也可继续修改或忽略。' : errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable || !!peerLocked" @click="retryCommand">用原请求恢复决定</NButton><NButton v-else text :disabled="blocked || taskLoading" @click="reviewTask">读取最新任务并保留改写</NButton></p>
    <p v-if="deferred" class="notice" role="status">{{ deferred.candidateId }} 已返回决定回执，登录或资料条件变化后暂缓核对。<NButton text :disabled="!readable || !!peerLocked" @click="receiveDeferred">核对返回的决定</NButton></p>
    <p v-if="taskError" class="notice error" role="alert">{{ taskError }}</p>
    <p v-if="pendingCount && !task.sourcesCurrent" class="notice">{{ aiFeedbackEvidence ? '生成时选中的知识版本、AI 反馈原观察与当时决定当前不可用于新采用。' : reviewEvidence ? '生成时选中的知识版本、反馈修订与决定或回忆版本当前不可用于新采用。' : directEvidence ? '生成时选中的知识版本或资料／训练依据当前不可用，暂不能采用。' : '生成时选中的知识版本当前已变化或归档，暂不能采用。' }}人工改写继续保留，仍可逐项忽略；采用不依赖 AI 当前是否启用。</p>
    <p v-if="pendingCount && !sourceChecksValid" class="notice">关系任务的授权用途或固定来源不完整，暂不能采用，请重新读取任务核对。</p>
    <p v-if="pendingCount && taskStale" class="notice">任务、来源或访问条件已变化。<NButton text :loading="taskLoading" :disabled="blocked || taskLoading" @click="reviewTask">重新核对并保留改写</NButton></p>
    <article v-for="row in rows" :key="row.suggestion.id" class="relation-card">
      <header><h4>{{ row.suggestion.id }} · {{ relationText(row.suggestion) }}</h4><span>{{ row.decision ? row.decision.status === 'ADOPTED' ? row.decision.supplementId ? '已补充依据' : '已新建关系' : '已忽略' : '待审阅' }}</span></header>
      <section><h5>模型原理由</h5><p class="copy">{{ row.suggestion.reason }}</p></section>
      <details class="evidence">
        <summary>原文依据 · {{ citationCount(row.suggestion) }} 条</summary><p class="field-help">引用位置对应固定知识正文、资料摘录或明确选中的回答、反馈及回忆片段；逐字相符不证明关系语义成立，也不证明人工改写后的理由。</p>
        <figure v-for="(citation, index) in row.suggestion.citations" :key="`knowledge:${index}`"><blockquote>{{ citation.quote }}</blockquote><figcaption><RouterLink v-if="aiSourceRoute(citationSource(citation))" class="text-link" :to="aiSourceRoute(citationSource(citation))!">{{ aiSourceKindLabel(citationSource(citation)?.kind) }} · {{ citationSource(citation)?.title }} · v{{ citationSource(citation)?.number }} · 查看确切版本</RouterLink><span v-else>来源身份不完整，请重新读取任务核对。</span><span>UTF-16 原文区间 [{{ citation.start }}, {{ citation.end }})</span></figcaption></figure>
        <figure v-for="(citation, index) in row.suggestion.materialCitations ?? []" :key="`material:${index}`">
          <template v-if="materialCitationSource(citation)"><blockquote>{{ citation.quote }}</blockquote><figcaption><RouterLink class="text-link" :to="{ name: 'materials', query: { attachment: materialCitationSource(citation)!.attachmentId, run: materialCitationSource(citation)!.runId ?? undefined, excerpt: citation.excerptId } }">{{ materialCitationSource(citation)!.fileName }} · {{ materialCitationSource(citation)!.locator }} · 查看确切摘录</RouterLink><span>摘录内 UTF-16 [{{ citation.start }}, {{ citation.end }})</span></figcaption></template>
          <p v-else class="notice" role="status">资料引用无法与本次固定摘录对应，请重新读取任务。</p>
        </figure>
        <figure v-for="(citation, index) in row.suggestion.trainingCitations ?? []" :key="`training:${index}`">
          <template v-if="directEvidence?.training && trainingCitationFragment(citation)"><blockquote>{{ citation.quote }}</blockquote><figcaption><RouterLink class="text-link" :to="{ name: 'training', query: { item: directEvidence.training.sessionId, question: directEvidence.training.questionId, answer: directEvidence.training.answerId } }">第 {{ directEvidence.training.answerNumber }} 版提交 · {{ aiTrainingDraftFieldLabels[citation.field] }} · 查看确切回答</RouterLink><span>{{ citation.elementId ? `图元素 ${citation.elementId} · ` : '' }}原字段 UTF-16 [{{ citation.start }}, {{ citation.end }}) · 位于已选片段 [{{ trainingCitationFragment(citation)!.start }}, {{ trainingCitationFragment(citation)!.end }})</span></figcaption></template>
          <p v-else class="notice" role="status">训练引用无法与本次选中的原答片段对应，请重新读取任务。</p>
        </figure>
        <AiReviewCitations :citations="row.suggestion.reviewCitations" :input="sourceChecksValid ? reviewEvidence : null" :ai-input="sourceChecksValid ? aiFeedbackEvidence : null" :disabled="blocked || taskLoading" @refresh="reviewTask" />
      </details>
      <p v-if="row.problem" class="notice">{{ row.problem }}</p>
      <section v-if="row.decision" class="decision-record">
        <h5>{{ row.decision.status === 'ADOPTED' ? row.decision.supplementId ? '补充时的最终理由' : '新建时的最终内容' : '本条已忽略' }} · {{ aiDate(row.decision.createdAt) }}</h5>
        <template v-if="row.decision.content"><strong>{{ relationText(row.decision.content) }}</strong><p class="copy">{{ row.decision.content.reason }}</p><p class="field-help">关系标识 {{ row.decision.relationId }}<template v-if="row.decision.supplementId"> · 补充记录 {{ row.decision.supplementId }}。本次追加依据保留了原关系身份。</template><template v-else>。本条采用创建了正式关系。</template>正式关系后续被删除时，本条决定与原候选仍保留。</p><RouterLink v-if="aiSourceRoute(sourceFor(row.decision.content.sourceItemId))" class="text-link" :to="aiSourceRoute(sourceFor(row.decision.content.sourceItemId))!">查看采用端点的确切概念版本</RouterLink><RelationSupplements v-if="row.decision.relationId" :relation-id="row.decision.relationId" :disabled="!readable" :refresh-key="task.task.revision" /></template>
        <p v-else class="field-help">没有创建正式关系；该条决定不会退款或改变原模型结果。</p>
        <p v-if="row.edit" class="notice">这条候选已有决定，本地改写仍保留供核对，关闭时会提示未保存修改。</p>
      </section>
      <div v-if="!row.decision" class="row-actions"><NButton v-if="!row.edit" type="primary" :disabled="blocked || taskLoading || !!row.problem || !undecided(row.suggestion)" @click="open(row.suggestion)">审阅并编辑本条</NButton><NButton :disabled="blocked || taskLoading || taskStale || !undecided(row.suggestion)" @click="ignore(row.suggestion)">忽略本条</NButton></div>
      <section v-if="row.edit" class="relation-edit" :aria-label="`编辑关系 ${row.suggestion.id}`">
        <label :for="`relation-type-${task.task.id}-${row.suggestion.id}`">最终关系类型<NSelect :id="`relation-type-${task.task.id}-${row.suggestion.id}`" v-model:value="row.edit.content.type" :options="relationOptions" :disabled="blocked || taskLoading || !!row.decision" /></label>
        <div class="direction"><p>{{ relationText(row.edit.content) }}</p><NButton v-if="!symmetric(row.edit.content.type)" size="small" :disabled="blocked || taskLoading || !!row.decision" @click="swap(row.suggestion, row.edit)">交换方向</NButton><p v-else class="field-help">这类关系没有方向；保存时统一两端顺序。</p></div>
        <p class="field-help">{{ row.edit.content.type === 'PRECEDES' ? '左侧概念是右侧概念的前置知识。' : row.edit.content.type === 'PART_OF' ? '左侧概念是右侧概念的组成部分。' : row.edit.content.type === 'APPLIED_IN' ? '左侧概念应用于右侧概念。' : '只表达这两个概念之间的关系，不表示前置或组成方向。' }}两端固定为原候选中的两个概念。</p>
        <label :for="`relation-reason-${task.task.id}-${row.suggestion.id}`">最终理由<NInput :id="`relation-reason-${task.task.id}-${row.suggestion.id}`" v-model:value="row.edit.content.reason" type="textarea" :autosize="{ minRows: 3, maxRows: 10 }" :disabled="blocked || taskLoading || !!row.decision" /><span class="field-help">{{ row.edit.content.reason.length }} / 1000 UTF-16 码元</span></label>
        <p v-if="contentProblem(row.suggestion, row.edit.content)" class="field-help">{{ contentProblem(row.suggestion, row.edit.content) }}</p>
        <template v-if="!row.decision">
          <label :for="`relation-mode-${task.task.id}-${row.suggestion.id}`">采用方式<NSelect :id="`relation-mode-${task.task.id}-${row.suggestion.id}`" :value="row.edit.mode" :options="[{ label: '新建正式关系', value: 'create' }, { label: '补充到已有关系', value: 'supplement' }]" :disabled="blocked || taskLoading" @update:value="setMode(row.edit, $event)" /></label>
          <template v-if="row.edit.mode === 'create'">
            <NCheckbox v-model:checked="row.edit.confirmed" :disabled="!canAdopt(row.suggestion, row.edit)">我已核对这两个概念、关系类型、方向与最终理由，确认新建本条关系，并理解原引用不证明关系语义正确。</NCheckbox>
            <div class="row-actions"><NButton type="primary" :loading="writer.busy.value && commandCandidateId === row.suggestion.id" :disabled="!canAdopt(row.suggestion, row.edit) || !row.edit.confirmed" @click="adopt(row.suggestion, row.edit)">确认创建本条关系</NButton></div>
          </template>
          <section v-else class="supplement-review" aria-label="核对已有关系并补充依据">
            <p class="field-help">按上方的端点、方向和类型查找已有关系，核对后追加本条理由及这次任务的全部固定来源。</p>
            <p v-if="supplementProblem(row.edit)" class="field-help">{{ supplementProblem(row.edit) }}</p>
            <div class="row-actions"><NButton :loading="row.edit.matching" :disabled="!canAdopt(row.suggestion, row.edit) || row.edit.matching" @click="findExisting(row.suggestion, row.edit)">{{ row.edit.match === undefined ? '查找已有关系' : '重新查找并核对' }}</NButton></div>
            <p v-if="row.edit.matchError" class="notice error" role="alert">{{ row.edit.matchError }}</p>
            <p v-else-if="row.edit.match === null" class="notice" role="status">没有找到同端点、同方向和同类型的关系。继续补充需要先有正式关系；也可主动切换到新建方式。</p>
            <template v-else-if="row.edit.match">
              <div class="matched-relation"><strong>{{ relationText(row.edit.content) }}</strong><p class="field-help">既有关系 {{ row.edit.match.id }}</p><p class="field-help">补充只追加本条理由与出处，关系身份、端点和类型保持原样。</p></div>
              <RelationSupplements :key="row.edit.match.id" :relation-id="row.edit.match.id" :disabled="!readable" :refresh-key="task.task.revision" />
              <div class="supplement-sources"><h5>本次追加的全部知识来源 · {{ task.manifest.sources.length }} 个</h5><ul><li v-for="source in task.manifest.sources" :key="source.itemId"><RouterLink v-if="aiSourceRoute(source)" class="text-link" :to="aiSourceRoute(source)!">{{ source.title }} · v{{ source.number }}</RouterLink><span v-else>{{ source.title }} · v{{ source.number }}</span><span> · {{ aiSourceKindLabel(source.kind) }}</span></li></ul></div>
              <p v-if="directEvidence" class="field-help">同时保留上方“本任务固定的全部直接原文依据”中的{{ directEvidence.kind === 'MATERIAL' ? '完整资料摘录' : '确切训练提交及全部已选片段' }}。补充不会改变原资料、训练提交或已有关系身份。</p>
              <p v-else-if="reviewEvidence" class="field-help">同时保留上方“本任务固定的全部复盘原文依据”中的{{ reviewEvidence.kind === 'RECOLLECTION' ? '确切回忆版本、回答基线、完整不确定性及全部已选片段' : '确切反馈修订、当次决定、完整条件与限制及全部已选片段' }}。反馈与回忆仍保持原有性质。</p>
              <p v-else-if="aiFeedbackEvidence" class="field-help">同时保留上方“本任务固定的全部 AI 反馈依据”中的原任务、成功尝试、报告身份、完整限定、当时决定和全部已选片段。原观察保持 AI 未审等级。</p>
              <NCheckbox v-model:checked="row.edit.confirmed" :disabled="!canSupplement(row.suggestion, row.edit)">我已核对既有关系 {{ row.edit.match.id }}、最终理由和以上全部来源，确认只追加本条依据。原候选和引用继续保留，补充不证明关系已经核实。</NCheckbox>
              <div class="row-actions"><NButton type="primary" :loading="writer.busy.value && commandCandidateId === row.suggestion.id" :disabled="!canSupplement(row.suggestion, row.edit) || !row.edit.confirmed" @click="supplement(row.suggestion, row.edit)">确认补充到这条关系</NButton></div>
            </template>
          </section>
        </template>
        <div class="row-actions"><NButton :disabled="blocked || taskLoading" @click="close(row.suggestion.id)">关闭本条编辑</NButton></div>
      </section>
    </article>
  </section>
</template>

<style scoped>
.relation-candidates { display: grid; gap: 20px; min-width: 0; border-top: 1px solid var(--line); padding-top: 21px; }
.relation-candidates > header, .limitations, .relation-card, .relation-edit, .decision-record { display: grid; gap: 12px; min-width: 0; }
h3 { margin: 0; font-size: 17px; } h4 { margin: 0; font-size: 14px; overflow-wrap: anywhere; } h5 { margin: 0 0 8px; font-size: 12px; color: var(--muted); }
.copy { margin: 0; font-size: 13px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.relation-card { padding: 18px 0; border-top: 1px solid var(--line); gap: 17px; }
.relation-card > header { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px 14px; }
.relation-card > header > span { font-size: 12px; color: var(--muted); }
.evidence { min-width: 0; } summary { cursor: pointer; font-size: 13px; color: var(--muted); }
.evidence > p { margin-top: 12px; } figure { margin: 14px 0 0; padding-left: 13px; border-left: 2px solid var(--brand); }
blockquote { margin: 0 0 8px; font-size: 13px; white-space: pre-wrap; overflow-wrap: anywhere; }
figcaption { display: grid; gap: 5px; font-size: 12px; } figcaption > span { color: var(--muted); }
.decision-record { padding: 15px; background: var(--surface-soft); border-radius: 7px; overflow-wrap: anywhere; }
.decision-record strong { font-size: 13px; } .decision-record > a { font-size: 12px; justify-self: start; }
.relation-edit { padding: 16px; border: 1px solid var(--line); border-radius: 7px; gap: 16px; }
.relation-edit > label { display: grid; gap: 8px; font-size: 13px; color: var(--muted); }
.direction { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 15px; min-width: 0; }
.direction > p { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
.supplement-review, .matched-relation, .supplement-sources { display: grid; gap: 12px; min-width: 0; }
.supplement-review { border-top: 1px solid var(--line); padding-top: 16px; }
.matched-relation { padding: 12px; background: var(--surface-soft); border-radius: 7px; overflow-wrap: anywhere; font-size: 13px; }
.matched-relation p, .supplement-review > p, .supplement-sources h5 { margin: 0; }
.supplement-sources ul { margin: 0; padding-left: 20px; display: grid; gap: 7px; font-size: 13px; overflow-wrap: anywhere; }
@media (max-width: 550px) { .relation-edit, .decision-record { padding: 12px; } }
</style>
