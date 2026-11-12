<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { ItemDetail, ItemSummary } from '../../api/client'
import type { AiManifest as AiManifestRecord, AiPreview, AiSettings, AiSource, AiTask, AiTrainingDraftFragment, MaterialAiExcerpt } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate, aiPreparationPlanTextValid, aiSourceKindLabel, aiSourceRoute, aiTrainingDraftFieldLabels, aiTrainingDraftRangeMatches, formatAiAmount } from '../../api/ai'
import type { AiFactDraftInput, AiFactDraftKind, AiFactDraftTrainingInput } from '../../api/aiFactDraft'
import type { AiRelationEvidenceCreateRequest, AiRelationEvidencePreviewRequest } from '../../api/aiRelationEvidence'
import { aiRelationEvidenceInputFingerprint, aiRelationEvidenceInputValid, aiRelationEvidenceKnowledgeSourceValid, aiRelationEvidencePaths, aiRelationEvidenceRequestProblem, aiRelationEvidenceRequestValid, aiRelationEvidenceSourcesMatch, aiRelationEvidenceTaskMatches } from '../../api/aiRelationEvidence'
import type { Attachment, MaterialExcerpt, MaterialStatus } from '../../api/material'
import type { TrainingAnswer } from '../../api/training'
import { relationEvidenceFragmentsValid, relationEvidenceMaterialMatches } from '../../api/relationEvidence'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import KnowledgePicker from '../KnowledgePicker.vue'
import MaterialExcerptPicker from '../material/MaterialExcerptPicker.vue'
import TrainingAnswerPicker from '../TrainingAnswerPicker.vue'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

type TrainingChoice = { sessionId: string; questionId: string; answerId: string; title: string; createdAt: string; condition: TrainingAnswer['condition'] }
type AnswerField = { key: string; field: AiTrainingDraftFragment['field']; elementId: string | null; label: string; text: string }
type AnswerSource = { fixed: Omit<AiFactDraftTrainingInput, 'fragments'>; fields: AnswerField[] }
const props = defineProps<{ settings?: AiSettings; disabled?: boolean }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), viewId = useId()
const stateKey = `ai-relation-evidence:${viewId}`, active = ref(true), instruction = ref('')
const sources = ref<AiSource[]>([]), verifiedKnowledge = ref<string[]>([]), knowledgePicker = ref<'CONCEPT' | 'NOTE' | null>(null)
const kind = ref<AiFactDraftKind>('MATERIAL'), materials = ref<MaterialAiExcerpt[]>([]), verifiedMaterials = ref<string[]>([])
const materialEnabled = ref(false), materialGeneration = ref(-1), statusLoading = ref(false), statusError = ref('')
const trainingChoice = shallowRef<TrainingChoice>(), answerSource = shallowRef<AnswerSource>(), answerGeneration = ref(-1)
const fragments = ref<AiTrainingDraftFragment[]>([]), fieldKey = ref(''), rangeStart = ref(''), rangeEnd = ref('')
const sourcePicker = ref(false), pickerGeneration = ref(0), selecting = ref(false), selectionError = ref('')
const previewing = ref(false), previewError = ref(''), prepared = shallowRef<AiPreview>(), preparedRequest = ref(''), consent = ref(false)
const authorization = ref<AiAuthorizationReference>()
const deferred = shallowRef<AiTask>(), createdResult = shallowRef<AiTask>(), message = ref('')
const idValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.restricted
  && !access.challenge && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || Boolean(createdResult.value))
const editingBlocked = computed(() => blocked.value || selecting.value || statusLoading.value || previewing.value)
const configured = computed(() => Boolean(props.settings?.configuration.enabled && props.settings.configuration.configured))
const materialReady = computed(() => materialEnabled.value && materialGeneration.value === accessGeneration && !statusError.value)
const answerReady = computed(() => Boolean(answerSource.value) && answerGeneration.value === accessGeneration)
const conceptCount = computed(() => sources.value.filter(source => source.kind === 'CONCEPT').length)
const fields = computed(() => answerSource.value?.fields ?? [])
const selectedField = computed(() => fields.value.find(field => field.key === fieldKey.value))
const range = computed<AiTrainingDraftFragment | undefined>(() => {
  const field = selectedField.value
  if (!field || !/^\d+$/.test(rangeStart.value) || !/^\d+$/.test(rangeEnd.value)) return undefined
  const start = Number(rangeStart.value), end = Number(rangeEnd.value)
  return { field: field.field, elementId: field.elementId, start, end, quote: field.text.slice(start, end) }
})
const rangeProblem = computed(() => {
  const value = range.value, field = selectedField.value
  if (!value || !field) return '请选取一段原文或填写原字段中的起止位置。'
  if (!aiTrainingDraftRangeMatches(field.text, value.start, value.end, value.quote) || !relationEvidenceFragmentsValid([value])) return '请选择 1–8000 个 UTF-16 码元的非空白片段，边界不能拆开完整字符。'
  return relationEvidenceFragmentsValid([...fragments.value, value]) ? '' : fragments.value.length >= 8 ? '最多选择 8 个回答片段。' : '同一原文字段中的片段不能重叠。'
})
const request = computed<AiRelationEvidencePreviewRequest>(() => ({ instruction: instruction.value,
  sources: sources.value.map(source => ({ itemId: source.itemId, versionId: source.versionId })), source: kind.value === 'MATERIAL'
    ? { kind: 'MATERIAL', excerptIds: materials.value.map(item => item.excerptId), training: null }
    : { kind: 'TRAINING', excerptIds: [], training: trainingChoice.value ? { sessionId: trainingChoice.value.sessionId,
      questionId: trainingChoice.value.questionId, answerId: trainingChoice.value.answerId, fragments: fragments.value.map(item => ({ ...item })) } : null } }))
const expectedInput = computed<AiFactDraftInput | undefined>(() => kind.value === 'MATERIAL'
  ? { kind: 'MATERIAL', materials: materials.value.map(item => ({ ...item })), training: null }
  : answerSource.value ? { kind: 'TRAINING', materials: [], training: { ...answerSource.value.fixed, fragments: fragments.value.map(item => ({ ...item })) } } : undefined)
const requestSignature = computed(() => JSON.stringify(request.value)), knowledgeSignature = computed(() => JSON.stringify(sources.value))
const inputSignature = computed(() => expectedInput.value ? aiRelationEvidenceInputFingerprint(expectedInput.value) : '')
const problem = computed(() => {
  if (sources.value.length < 2 || sources.value.length > 5 || conceptCount.value < 2 || sources.value.some(source => !aiRelationEvidenceKnowledgeSourceValid(source))) return '请选择 2–5 个当前发布的知识，其中至少两个为概念。'
  if (sources.value.some(source => !verifiedKnowledge.value.includes(source.itemId))) return '访问条件或来源状态已变化，请重新核对已选知识版本。'
  if (kind.value === 'MATERIAL') {
    if (!materialReady.value) return '请先核对资料模块可用。'
    if (materials.value.some(item => !verifiedMaterials.value.includes(item.excerptId))) return '请重新核对所选摘录的固定原文与来源。'
  } else {
    if (!answerReady.value) return '请选择或重新核对一次确切训练提交。'
    if (fragments.value.some(item => { const field = fields.value.find(field => field.field === item.field && field.elementId === item.elementId); return !field || !aiTrainingDraftRangeMatches(field.text, item.start, item.end, item.quote) })) return '已选片段与原回答不一致，请重新核对。'
  }
  if (!expectedInput.value || !aiRelationEvidenceInputValid(expectedInput.value)) return kind.value === 'MATERIAL' ? '请选择 1–5 个完整已保存摘录。' : '请明确选择 1–8 个不重叠的回答片段。'
  return aiRelationEvidenceRequestProblem(request.value)
})
const previewMatches = computed(() => Boolean(prepared.value && expectedInput.value && preparedRequest.value === requestSignature.value
  && aiRelationEvidenceSourcesMatch(prepared.value.manifest, request.value, sources.value, expectedInput.value)))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const dirty = computed(() => !createdResult.value && Boolean(instruction.value || sources.value.length || materials.value.length || trainingChoice.value || fragments.value.length || rangeStart.value || rangeEnd.value || consent.value))
const state = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
const answerLabels = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
let alive = true, accessGeneration = 0, sourceSequence = 0, statusSequence = 0, previewSequence = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0

watch([dirty, locked], ([changed, pending]) => {
  state.dirty = changed; state.locked = pending; commandState.locked = pending
  store.changes.set(stateKey, state); store.changes.set('ai-create', state); store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([requestSignature, knowledgeSignature, inputSignature, instruction, fieldKey, rangeStart, rangeEnd], invalidatePreview, { flush: 'sync' })
watch(fieldKey, () => { rangeStart.value = ''; rangeEnd.value = '' }, { flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  accessGeneration++; sourceSequence++; statusSequence++; selecting.value = false; statusLoading.value = false
  verifiedKnowledge.value = []; verifiedMaterials.value = []; materialGeneration.value = -1; answerGeneration.value = -1
  knowledgePicker.value = null; sourcePicker.value = false; pickerGeneration.value++; invalidatePreview()
  // 权限重核只作废授权，不丢知识版本、依据原文和未加入选区；恢复后由用户显式重核。
}, { flush: 'sync' })
watch([writer.error, writer.pending], ([error, pending]) => {
  if (!error) return
  invalidatePreview()
  if (!pending) { verifiedKnowledge.value = []; verifiedMaterials.value = []; answerGeneration.value = -1 }
  emit('refresh')
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; sourceSequence++; statusSequence++; previewSequence++
  if (store.changes.get(stateKey) === state) store.changes.delete(stateKey)
  if (store.changes.get('ai-create') === state) store.changes.delete('ai-create')
  if (store.changes.get('ai-command') === commandState) store.changes.delete('ai-command')
})

function invalidatePreview() { previewSequence++; previewing.value = false; prepared.value = undefined; preparedRequest.value = ''; consent.value = false }
async function readCurrent<T>(path: string): Promise<T> {
  const epoch = store.sessionEpoch, generation = accessGeneration
  const value = await read<T>(path)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || generation !== accessGeneration) throw new ApiError(0, 'RELATION_EVIDENCE_ACCESS_CHANGED', '访问条件已变化，当前输入与选择继续保留。')
  return value
}
function publishedSource(value: ItemDetail, itemId: string, expected?: AiSource, summary?: ItemSummary): AiSource {
  const published = value?.published
  if (!value || value.id !== itemId || value.status !== 'ACTIVE' || !published || !['NOTE', 'CONCEPT'].includes(value.kind)
    || published.id !== value.currentVersionId || published.number !== value.versionNumber
    || summary && (value.kind !== summary.kind || published.number !== summary.versionNumber)) throw new ApiError(409, 'AI_SOURCE_CHANGED', '所选知识已归档或发布版本已变化，请重新选择；不会自动换成新版本。')
  const result: AiSource = { itemId: value.id, versionId: published.id, number: published.number, kind: value.kind, title: published.title, body: published.body }
  if (!aiRelationEvidenceKnowledgeSourceValid(result)) throw new ApiError(502, 'RELATION_KNOWLEDGE_INVALID', '知识发布版本的身份或完整原文不合法，请重新核对。')
  if (expected && JSON.stringify(result) !== JSON.stringify(expected)) throw new ApiError(409, 'AI_SOURCE_CHANGED', '知识的固定发布版本或正文已变化。原选择继续保留，请移出后重新选择。')
  return result
}
async function chooseKnowledge(summary: ItemSummary) {
  if (editingBlocked.value || !knowledgePicker.value || summary.kind !== knowledgePicker.value || summary.status !== 'ACTIVE'
    || !idValid(summary.id) || sources.value.length >= 5 || sources.value.some(source => source.itemId === summary.id)) return
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
function clearDirectSource() {
  materials.value = []; verifiedMaterials.value = []; trainingChoice.value = undefined; answerSource.value = undefined; answerGeneration.value = -1
  fragments.value = []; fieldKey.value = ''; rangeStart.value = ''; rangeEnd.value = ''; sourcePicker.value = false; selectionError.value = ''; invalidatePreview()
}
function chooseKind(value: AiFactDraftKind) {
  if (editingBlocked.value || value === kind.value) return
  if ((materials.value.length || trainingChoice.value || rangeStart.value || rangeEnd.value) && !window.confirm('切换依据类别会移出当前摘录或回答片段，知识选择与整理要求保留。确定切换？')) return
  clearDirectSource(); kind.value = value
}
async function loadMaterialStatus() {
  if (editingBlocked.value || kind.value !== 'MATERIAL') return
  const ticket = ++statusSequence; statusLoading.value = true; statusError.value = ''; materialGeneration.value = -1; invalidatePreview()
  try {
    const value = await readCurrent<MaterialStatus>('/materials/status')
    if (!alive || ticket !== statusSequence) return
    if (!value || typeof value.enabled !== 'boolean') throw new ApiError(502, 'RELATION_MATERIAL_STATUS_INVALID', '资料模块状态无法核对，请重新读取。')
    materialEnabled.value = value.enabled; materialGeneration.value = accessGeneration
  } catch (error) { if (alive && ticket === statusSequence) statusError.value = errorText(error) }
  finally { if (alive && ticket === statusSequence) statusLoading.value = false }
}
async function readMaterial(id: string, attachmentId: string): Promise<MaterialAiExcerpt> {
  const [excerpt, attachment] = await Promise.all([readCurrent<MaterialExcerpt>(`/materials/excerpts/${id}`), readCurrent<Attachment>(`/materials/attachments/${attachmentId}`)])
  if (!excerpt || !attachment || excerpt.id !== id || excerpt.attachmentId !== attachmentId || attachment.id !== attachmentId || attachment.status !== 'READY') throw new ApiError(409, 'AI_SOURCE_CHANGED', '摘录或附件与所选来源不一致，请重新核对。')
  const input: MaterialAiExcerpt = { excerptId: excerpt.id, attachmentId: excerpt.attachmentId, fileName: attachment.fileName, sha256: attachment.sha256,
    origin: excerpt.origin, runId: excerpt.runId, segmentId: excerpt.segmentId, locator: excerpt.locator, text: excerpt.text, startOffset: excerpt.startOffset, endOffset: excerpt.endOffset }
  if (!relationEvidenceMaterialMatches(input, excerpt)) throw new ApiError(502, 'RELATION_MATERIAL_INVALID', '完整摘录或固定来源元数据无法核对。')
  return input
}
async function chooseMaterial(selected: MaterialExcerpt) {
  if (editingBlocked.value || kind.value !== 'MATERIAL' || !sourcePicker.value || !materialReady.value || materials.value.length >= 5
    || !idValid(selected.id) || !idValid(selected.attachmentId) || materials.value.some(item => item.excerptId === selected.id)) return
  const ticket = ++sourceSequence; selecting.value = true; selectionError.value = ''; invalidatePreview()
  try {
    const value = await readMaterial(selected.id, selected.attachmentId)
    if (!alive || ticket !== sourceSequence) return
    if (!relationEvidenceMaterialMatches(value, selected)) throw new ApiError(409, 'AI_SOURCE_CHANGED', '所读摘录与本次点选的完整原文不一致，请重新选择。')
    materials.value = [...materials.value, value]; verifiedMaterials.value.push(value.excerptId); sourcePicker.value = false
  } catch (error) { if (alive && ticket === sourceSequence) selectionError.value = errorText(error) }
  finally { if (alive && ticket === sourceSequence) selecting.value = false }
}
async function recheckMaterials() {
  if (editingBlocked.value || kind.value !== 'MATERIAL' || !materialReady.value || !materials.value.length) return
  const ticket = ++sourceSequence, selected = materials.value.map(item => ({ ...item }))
  selecting.value = true; selectionError.value = ''; verifiedMaterials.value = []; invalidatePreview()
  try {
    const actual = await Promise.all(selected.map(item => readMaterial(item.excerptId, item.attachmentId)))
    if (!alive || ticket !== sourceSequence) return
    if (JSON.stringify(actual) !== JSON.stringify(selected)) throw new ApiError(409, 'AI_SOURCE_CHANGED', '所选摘录原文或来源元数据已变化，原输入继续保留，请移出后重新选择。')
    verifiedMaterials.value = selected.map(item => item.excerptId)
  } catch (error) { if (alive && ticket === sourceSequence) selectionError.value = errorText(error) }
  finally { if (alive && ticket === sourceSequence) selecting.value = false }
}
function removeMaterial(id: string) { if (!editingBlocked.value) { materials.value = materials.value.filter(item => item.excerptId !== id); verifiedMaterials.value = verifiedMaterials.value.filter(value => value !== id) } }
function answerTextValid(value: unknown, maximum: number, required = false): value is string {
  // 整答字段沿保存端码点上限和 Java isBlank，手选区间单独按 UTF-16 处理。
  return typeof value === 'string' && aiPreparationPlanTextValid(value, maximum * 2) && Array.from(value).length <= maximum
    && (!required || /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value))
}
async function chooseAnswer(selected: TrainingChoice, recheck = false) {
  if (editingBlocked.value || kind.value !== 'TRAINING' || ![selected.sessionId, selected.questionId, selected.answerId].every(idValid)) return
  const same = trainingChoice.value?.sessionId === selected.sessionId && trainingChoice.value?.questionId === selected.questionId && trainingChoice.value?.answerId === selected.answerId
  if (recheck && !same) return
  if (!recheck && (trainingChoice.value || fragments.value.length || rangeStart.value || rangeEnd.value) && !window.confirm('重新选择回答会移出已选片段和未加入的选区，知识选择与要求保留。确定继续？')) return
  const ticket = ++sourceSequence; selecting.value = true; selectionError.value = ''; invalidatePreview()
  if (same) answerGeneration.value = -1
  try {
    const answer = await readCurrent<TrainingAnswer>(`/training/sessions/${selected.sessionId}/questions/${selected.questionId}/answers/${selected.answerId}`)
    if (!alive || ticket !== sourceSequence) return
    if (!answer || answer.id !== selected.answerId || answer.questionId !== selected.questionId || answer.condition !== selected.condition
      || answer.createdAt !== selected.createdAt || !Number.isSafeInteger(answer.number) || answer.number < 1 || answer.number > 100
      || !['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(answer.condition) || !answer.content || !answerTextValid(answer.content.text, 20000, true)
      || !answer.content.diagram || !Array.isArray(answer.content.diagram.nodes) || !Array.isArray(answer.content.diagram.edges)) throw new ApiError(502, 'RELATION_ANSWER_INVALID', '读取结果不是所选的确切已提交回答，请重新查询目录。')
    const { nodes, edges } = answer.content.diagram
    if (nodes.length > 80 || edges.length > 160 || nodes.some(node => !node || !idValid(node.id) || !answerTextValid(node.label, 200, true) || !answerTextValid(node.description, 5000))
      || edges.some(edge => !edge || !idValid(edge.id) || !answerTextValid(edge.label, 200))
      || new Set([...nodes, ...edges].map(item => item.id)).size !== nodes.length + edges.length) throw new ApiError(502, 'RELATION_ANSWER_INVALID', '回答图的原文字段或元素身份不完整，请重新读取。')
    const field = (type: AiTrainingDraftFragment['field'], elementId: string | null, label: string, text: string): AnswerField => ({ key: JSON.stringify([type, elementId]), field: type, elementId, label, text })
    const actual: AnswerSource = { fixed: { sessionId: selected.sessionId, questionId: selected.questionId, answerId: selected.answerId, answerNumber: answer.number, answerCondition: answer.condition },
      fields: [field('ANSWER_TEXT', null, '回答文字', answer.content.text), ...nodes.flatMap((node, index) => [field('ANSWER_NODE_LABEL', node.id, `节点 ${index + 1} · 名称`, node.label), field('ANSWER_NODE_DESCRIPTION', node.id, `节点 ${index + 1} · 说明`, node.description)]),
        ...edges.map((edge, index) => field('ANSWER_EDGE_LABEL', edge.id, `连线 ${index + 1} · 说明`, edge.label))] }
    if (recheck && answerSource.value && JSON.stringify(actual) !== JSON.stringify(answerSource.value)) throw new ApiError(409, 'AI_SOURCE_CHANGED', '原回答或作答条件与当前选择不一致。原片段继续保留，请明确重新选择。')
    // 新来源核对成功后才替换旧片段；不以新答或题面补全当前来源。
    if (!recheck) { fragments.value = []; fieldKey.value = ''; rangeStart.value = ''; rangeEnd.value = '' }
    trainingChoice.value = { ...selected }; answerSource.value = actual; answerGeneration.value = accessGeneration
    if (!fieldKey.value) fieldKey.value = actual.fields[0]?.key ?? ''
    sourcePicker.value = false
  } catch (error) { if (alive && ticket === sourceSequence) selectionError.value = errorText(error) }
  finally { if (alive && ticket === sourceSequence) selecting.value = false }
}
function removeAnswer() {
  if (editingBlocked.value || (fragments.value.length || rangeStart.value || rangeEnd.value) && !window.confirm('移出回答会同时移出片段和未加入的选区，知识与要求保留。确定移出？')) return
  clearDirectSource()
}
function captureSelection(event: Event) {
  if (editingBlocked.value || !answerReady.value || !selectedField.value) return
  const area = event.target as HTMLTextAreaElement, original = selectedField.value.text
  if (area.value !== original.replace(/\r\n?/g, '\n') || area.selectionEnd <= area.selectionStart) return
  // textarea 会归一换行，提交范围必须恢复成保存原文的 UTF-16 下标。
  const offset = (display: number) => { let raw = 0, shown = 0; while (raw < original.length && shown < display) { raw += original[raw] === '\r' && original[raw + 1] === '\n' ? 2 : 1; shown++ }; return raw }
  rangeStart.value = String(offset(area.selectionStart)); rangeEnd.value = String(offset(area.selectionEnd))
}
function addFragment() { if (!editingBlocked.value && answerReady.value && !rangeProblem.value && range.value) { fragments.value = [...fragments.value, { ...range.value }]; rangeStart.value = ''; rangeEnd.value = '' } }
function removeFragment(index: number) { if (!editingBlocked.value) fragments.value = fragments.value.filter((_, position) => position !== index) }
function previewEnvelopeValid(value: AiPreview) {
  const budget = value?.budget, manifest = value?.manifest
  return Boolean(manifest && typeof value.manifestHash === 'string' && /^[0-9a-f]{64}$/.test(value.manifestHash)
    && budget && /^\d{4}-\d{2}-\d{2}$/.test(budget.date) && budget.currency === manifest.configuration.currency
    && [budget.limitMicros, budget.reportedMicros, budget.reservedMicros, budget.unknownMicros, budget.remainingMicros].every(item => Number.isSafeInteger(item) && item >= 0))
}
async function prepare() {
  if (editingBlocked.value || !configured.value || problem.value || !expectedInput.value || !aiRelationEvidenceRequestValid(request.value)) return
  invalidatePreview()
  const epoch = store.sessionEpoch, generation = accessGeneration, ticket = ++previewSequence, signature = requestSignature.value
  const snapshot = JSON.parse(signature) as AiRelationEvidencePreviewRequest, selected = sources.value.map(source => ({ ...source }))
  const input = JSON.parse(JSON.stringify(expectedInput.value)) as AiFactDraftInput
  previewing.value = true; previewError.value = ''; message.value = ''
  const current = () => alive && !blocked.value && epoch === store.sessionEpoch && generation === accessGeneration && ticket === previewSequence && signature === requestSignature.value
  try {
    const value = await preview<AiPreview>(aiRelationEvidencePaths.preview, snapshot)
    if (!current()) return
    if (!aiRelationEvidenceSourcesMatch(value?.manifest, snapshot, selected, input) || !previewEnvelopeValid(value)) throw new ApiError(502, 'RELATION_EVIDENCE_PREVIEW_INVALID', '外发预览与知识版本、所选完整原文、要求或预算信息不一致，请重新核对。')
    if (JSON.stringify(value.manifest.configuration) !== JSON.stringify(props.settings?.configuration)) { emit('refresh'); throw new ApiError(409, 'AI_CONFIG_CHANGED', '服务器配置已变化，请刷新配置后重新预览。') }
    prepared.value = value; preparedRequest.value = signature
  } catch (error) { if (current()) previewError.value = errorText(error) }
  finally { if (current()) previewing.value = false }
}
function receive(value: AiTask) {
  createdResult.value = value; state.dirty = false; state.locked = false; commandState.locked = false
  message.value = '关系候选任务已创建。请结合固定知识与原文依据逐项审阅。'; emit('refresh'); emit('created', value)
}
function initialReceiptMatches(value: AiTask, authorized: AiManifestRecord): boolean {
  const summary = value.task, attempt = value.attempts[0]
  // 创建与原键重放均返回首次落库回执，必须核对初始状态及本次授权的预留费用。
  return summary.status === 'QUEUED' && summary.revision === 0 && summary.attemptCount === 1
    && summary.errorCode === null && summary.createdAt === summary.updatedAt && value.attempts.length === 1
    && Boolean(attempt && idValid(attempt.id) && attempt.id !== summary.id && attempt.number === 1
      && attempt.status === 'QUEUED' && attempt.feeStatus === 'RESERVED' && attempt.reservedMicros === authorized.reservedMicros
      && attempt.accountedMicros === null && attempt.promptTokens === null && attempt.completionTokens === null
      && attempt.providerRequestId === null && attempt.errorCode === null && attempt.reconciliationNote === null
      && attempt.createdAt === summary.createdAt && attempt.sentAt === null && attempt.finishedAt === null)
}
function create() {
  if (editingBlocked.value || !configured.value || !prepared.value || !previewMatches.value || !consent.value || overBudget.value || problem.value || !expectedInput.value) return
  const snapshot = JSON.parse(preparedRequest.value) as AiRelationEvidencePreviewRequest
  const selected = sources.value.map(source => ({ ...source })), input = JSON.parse(JSON.stringify(expectedInput.value)) as AiFactDraftInput
  const authorized = JSON.parse(JSON.stringify(prepared.value.manifest)) as AiManifestRecord, fingerprint = aiCandidateManifestFingerprint(authorized)
  const body: AiRelationEvidenceCreateRequest = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  knowledgePicker.value = null; sourcePicker.value = false; commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration
  let submitted: typeof writer.pending.value
  void writer.run<AiTask>(aiRelationEvidencePaths.create, 'POST', body, value => {
    if (!alive) return
    let valid = false
    try { valid = aiRelationEvidenceTaskMatches(value, authorized) && initialReceiptMatches(value, authorized)
      && aiRelationEvidenceSourcesMatch(value.manifest, snapshot, selected, input)
      && aiCandidateManifestFingerprint(value.manifest) === fingerprint } catch { valid = false }
    // 不一致回执可能已经建立任务，保留发出的完整命令和原键，禁止换键重复授权。
    if (!valid) { writer.pending.value = submitted; writer.error.value = new ApiError(502, 'RELATION_EVIDENCE_RECEIPT_INVALID', '任务回执与原授权的知识、依据、初始状态或费用不一致。输入和原请求继续保留，请用原请求恢复。'); return }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) deferred.value = value
    else receive(value)
  })
  submitted = writer.pending.value
}
function retryCommand() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
function close() {
  if (locked.value || !store.canLeave([stateKey])) return
  sourceSequence++; statusSequence++; selecting.value = false; statusLoading.value = false; invalidatePreview()
  sources.value = []; instruction.value = ''; clearDirectSource(); emit('close')
}
</script>

<template>
  <section class="relation-evidence-create" aria-label="从材料或训练回答生成关系候选">
    <header class="create-heading"><div><p class="section-label">新建 AI 任务</p><h2>依据原文提出知识关系</h2><p class="field-help">选择概念端点、完整知识版本与原文依据，预览后再授权一次生成。</p></div><NButton text size="small" :disabled="locked" @click="close">关闭</NButton></header>
    <p v-if="!configured" class="notice">AI 未启用或配置未完整，仍可填写要求和选择来源。</p><p v-if="!readable" class="notice" role="status">登录或资料访问待确认，已选来源、要求与未加入选区继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="create-content">
      <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="createdResult" class="notice"><NButton text :disabled="!readable" @click="emit('created', createdResult)">打开已创建关系任务</NButton></p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable || writer.busy.value" @click="retryCommand">用原请求恢复</NButton></p><p v-if="deferred" class="notice" role="status">任务已创建，访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建任务</NButton></p>
      <template v-if="!createdResult">
        <section class="source-panel" aria-label="关系端点与知识依据"><header class="panel-heading"><h3>本次知识 · {{ sources.length }} / 5 · {{ conceptCount }} 个概念</h3><div class="row-actions"><NButton size="small" :disabled="editingBlocked || sources.length >= 5" :aria-expanded="knowledgePicker === 'CONCEPT'" @click="knowledgePicker = knowledgePicker === 'CONCEPT' ? null : 'CONCEPT'">选择概念</NButton><NButton size="small" :disabled="editingBlocked || sources.length >= 5" :aria-expanded="knowledgePicker === 'NOTE'" @click="knowledgePicker = knowledgePicker === 'NOTE' ? null : 'NOTE'">选择笔记依据</NButton></div></header><p class="field-help">选择 2–5 个当前发布知识，其中至少两个概念作为候选端点。完整知识正文与下面选中的原文依据一同进入外发清单。</p>
          <article v-for="source in sources" :key="source.itemId" class="selected-source"><header class="panel-heading"><div><RouterLink class="text-link" :to="aiSourceRoute(source)!">{{ source.title }} · v{{ source.number }}</RouterLink><p class="field-help">{{ aiSourceKindLabel(source.kind) }} · {{ source.body.length }} 个 UTF-16 码元</p></div><NButton text size="small" :disabled="editingBlocked" @click="removeKnowledge(source.itemId)">移出</NButton></header><details><summary>核对完整发布正文</summary><pre class="exact-text">{{ source.body }}</pre></details></article>
          <KnowledgePicker v-if="knowledgePicker && readable && !locked" :key="`${pickerGeneration}:${knowledgePicker}`" :kind="knowledgePicker" :disabled="editingBlocked || sources.length >= 5" :exclude-ids="sources.map(source => source.itemId)" @choose="chooseKnowledge" />
          <NButton v-if="sources.some(source => !verifiedKnowledge.includes(source.itemId))" size="small" :disabled="editingBlocked" @click="recheckKnowledge">重新核对所选知识版本</NButton>
        </section>
        <section class="source-panel" aria-label="直接原文依据"><h3>原文依据</h3><div class="row-actions"><NButton size="small" :disabled="editingBlocked" :aria-pressed="kind === 'MATERIAL'" @click="chooseKind('MATERIAL')">资料摘录</NButton><NButton size="small" :disabled="editingBlocked" :aria-pressed="kind === 'TRAINING'" @click="chooseKind('TRAINING')">训练回答片段</NButton></div>
          <template v-if="kind === 'MATERIAL'"><header class="panel-heading"><h4>完整已保存摘录 · {{ materials.length }} / 5</h4><NButton size="small" :disabled="editingBlocked || !materialReady || materials.length >= 5" :aria-expanded="sourcePicker" @click="sourcePicker = !sourcePicker">{{ sourcePicker ? '收起摘录选择' : '选择资料摘录' }}</NButton></header><p class="field-help">选取 1–5 个完整摘录，保留原文件、定位和原文。摘录不自动成为新的概念端点。</p><p v-if="statusLoading" role="status">正在核对资料模块…</p><p v-if="statusError" class="notice error" role="alert">{{ statusError }}</p><p v-else-if="!materialReady" class="notice">资料模块尚未核对或当前不可用，已选摘录继续保留。</p><NButton text size="small" :disabled="editingBlocked" @click="loadMaterialStatus">核对资料模块状态</NButton>
            <article v-for="item in materials" :key="item.excerptId" class="selected-source"><header class="panel-heading"><div><h4>{{ item.fileName }}</h4><p class="field-help">{{ item.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ item.locator }}</p></div><NButton text size="small" :disabled="editingBlocked" @click="removeMaterial(item.excerptId)">移出摘录</NButton></header><pre class="exact-text">{{ item.text }}</pre></article>
            <NButton v-if="materials.some(item => !verifiedMaterials.includes(item.excerptId))" size="small" :disabled="editingBlocked || !materialReady" @click="recheckMaterials">重新核对所选摘录</NButton><MaterialExcerptPicker v-if="sourcePicker && readable && materialReady && !locked" :key="pickerGeneration" search-excerpts for-ai :disabled="editingBlocked || materials.length >= 5" :exclude-ids="materials.map(item => item.excerptId)" @choose="chooseMaterial" />
          </template>
          <template v-else><header class="panel-heading"><h4>确切提交与手选片段</h4><NButton size="small" :disabled="editingBlocked" :aria-expanded="sourcePicker" @click="sourcePicker = !sourcePicker">{{ sourcePicker ? '收起提交目录' : trainingChoice ? '重新选择提交' : '选择已提交回答' }}</NButton></header><TrainingAnswerPicker v-if="sourcePicker && readable && !locked" :key="pickerGeneration" :disabled="editingBlocked" @choose="chooseAnswer($event)" />
            <template v-if="trainingChoice"><header class="panel-heading"><div><h4>{{ trainingChoice.title }}</h4><p class="field-help">{{ aiDate(trainingChoice.createdAt) }} · {{ answerLabels[trainingChoice.condition] }}</p></div><NButton text size="small" :disabled="editingBlocked" @click="removeAnswer">移出提交</NButton></header><p v-if="!answerReady" class="notice">这次提交需要重新核对，片段与选区保留。<NButton text size="small" :disabled="editingBlocked" @click="chooseAnswer(trainingChoice, true)">核对原提交</NButton></p>
              <template v-if="answerSource"><label class="form-field">回答原文字段<NSelect v-model:value="fieldKey" :disabled="editingBlocked || !answerReady" :options="fields.map(field => ({ label: field.label, value: field.key }))" aria-label="选择回答原文字段" /></label><label v-if="selectedField" class="form-field" :for="`${viewId}-answer`">{{ selectedField.label }}<textarea :id="`${viewId}-answer`" :value="selectedField.text" readonly :disabled="editingBlocked || !answerReady" rows="8" :aria-describedby="`${viewId}-range-help`" @select="captureSelection" @keyup="captureSelection" @mouseup="captureSelection" /></label><p :id="`${viewId}-range-help`" class="field-help">拖选文字或使用 Shift + 方向键，明确点击“加入片段”。位置按原文 UTF-16 计数，从 0 开始，结束位置不包含在片段中。</p><div class="range-fields"><label class="form-field">起始位置<NInput v-model:value="rangeStart" :disabled="editingBlocked || !answerReady" :input-props="{ inputmode: 'numeric', 'aria-label': '回答片段起始位置' }" /></label><label class="form-field">结束位置<NInput v-model:value="rangeEnd" :disabled="editingBlocked || !answerReady" :input-props="{ inputmode: 'numeric', 'aria-label': '回答片段结束位置' }" /></label><NButton size="small" :disabled="editingBlocked || !answerReady || !!rangeProblem" @click="addFragment">加入片段</NButton></div><p v-if="rangeProblem" class="field-help">{{ rangeProblem }}</p><pre v-else-if="range" class="exact-text selected-quote">{{ range.quote }}</pre></template>
              <h4>已选片段 · {{ fragments.length }} / 8</h4><article v-for="(fragment, index) in fragments" :key="`${fragment.field}/${fragment.elementId}/${fragment.start}/${fragment.end}`" class="selected-source"><header class="panel-heading"><h4>{{ aiTrainingDraftFieldLabels[fragment.field] }} · [{{ fragment.start }}, {{ fragment.end }})</h4><NButton text size="small" :disabled="editingBlocked" @click="removeFragment(index)">移出片段</NButton></header><pre class="exact-text">{{ fragment.quote }}</pre></article>
            </template><p class="field-help">仅手选回答文字、图节点名称与说明、连线说明。作答条件随来源保留，训练假设不能作为真实经历证明。</p>
          </template>
        </section>
        <p v-if="selecting" class="field-help" role="status">正在核对确切来源，已有选择继续保留…</p><p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p>
        <label class="form-field">关系整理要求<NInput v-model:value="instruction" :disabled="editingBlocked" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 8 }" :input-props="{ 'aria-label': '关系整理要求' }" placeholder="例如：结合所选材料解释两个概念的应用关系；依据不足时保留限制，不补造连接。" /><span class="field-help">可留空，最多 1000 个 UTF-16 码元，按原文发送。候选可以为零条，生成不自动建立正式关系。</span></label>
        <div class="row-actions"><NButton :loading="previewing" :disabled="editingBlocked || !configured || !!problem" @click="prepare">预览完整外发清单</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div><p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
        <section v-if="prepared && previewMatches" class="consent-panel"><AiManifest :manifest="prepared.manifest" :budget="prepared.budget" /><p v-if="overBudget" class="notice error">本次预留超过当前剩余预算，请核对预算后重新预览。</p><AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="editingBlocked || overBudget || !configured">我已核对完整知识正文、原文依据与整理要求，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice><NButton type="primary" :loading="writer.busy.value" :disabled="editingBlocked || !consent || overBudget || !configured || !!problem" @click="create">授权本次外发并生成关系候选</NButton><p class="field-help">来源、片段、要求、配置或访问条件变化后需重新预览。结果未知时保留原请求和原幂等键；当前输入只保存在本页。</p></section>
      </template>
    </div>
  </section>
</template>

<style scoped>
.relation-evidence-create, .create-content { display: grid; gap: 23px; min-width: 0; }.relation-evidence-create { padding: 25px 27px 30px; }.create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; flex-wrap: wrap; }.create-heading > div, .panel-heading > div { min-width: 0; }h2 { margin: 7px 0 9px; font-size: 24px; }h3 { margin: 0; font-size: 16px; }h4 { margin: 0; font-size: 13px; overflow-wrap: anywhere; }.source-panel, .selected-source, .form-field { display: grid; gap: 12px; min-width: 0; }.source-panel, .selected-source { padding-top: 16px; border-top: 1px solid var(--line); }.form-field { color: var(--muted); font-size: 13px; }.form-field > textarea { box-sizing: border-box; width: 100%; min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); color: var(--ink); font: 13px/1.75 var(--font-body, sans-serif); resize: vertical; }textarea:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }.range-fields { display: grid; grid-template-columns: 1fr 1fr auto; align-items: end; gap: 12px; }.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }.selected-quote { border: 1px solid var(--brand); }.consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); min-width: 0; }.consent-panel > .n-button { justify-self: start; }.row-actions { display: flex; flex-wrap: wrap; gap: 9px 13px; align-items: center; }.field-help, .text-link, .notice { overflow-wrap: anywhere; }summary { cursor: pointer; font-size: 12px; color: var(--muted); }details > pre { margin-top: 10px; }@media (max-width: 700px) { .relation-evidence-create { padding: 22px 17px; }h2 { font-size: 21px; }.range-fields { grid-template-columns: 1fr 1fr; }.range-fields > .n-button { justify-self: start; } }
</style>
