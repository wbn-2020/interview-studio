<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { Schema } from '../../api/client'
import type { AiPreview, AiSettings, AiTask, AiTrainingDraftFragment } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiPreparationPlanTextValid, aiTrainingDraftFieldLabels, aiTrainingDraftRangeMatches, formatAiAmount } from '../../api/ai'
import type { AiFactDraftCreateRequest, AiFactDraftInput, AiFactDraftKind, AiFactDraftPreviewRequest, AiFactDraftTrainingInput } from '../../api/aiFactDraft'
import { aiFactDraftInputFingerprint, aiFactDraftInputValid, aiFactDraftKindLabels, aiFactDraftMaterialMatches, aiFactDraftPaths, aiFactDraftRequestValid, aiFactDraftSourcesMatch, aiFactDraftTaskValid } from '../../api/aiFactDraft'
import type { Attachment, MaterialExcerpt, MaterialStatus } from '../../api/material'
import type { TrainingAnswer } from '../../api/training'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MaterialExcerptPicker from '../material/MaterialExcerptPicker.vue'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

type TrainingIdentity = { sessionId: string; questionId: string; answerId: string }
type AnswerField = { key: string; field: AiTrainingDraftFragment['field']; elementId: string | null; label: string; text: string }
type AnswerSource = { fixed: Omit<AiFactDraftTrainingInput, 'fragments'>; fields: AnswerField[] }
const props = defineProps<{ settings?: AiSettings; disabled?: boolean; sourceKind?: AiFactDraftKind; initialTraining?: TrainingIdentity; routeError?: string }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const instruction = ref('')
const materials = ref<Schema['MaterialAiExcerpt'][]>([])
const pickerOpen = ref(false)
const pickerGeneration = ref(0)
const selecting = ref(false)
const selectionError = ref('')
const materialEnabled = ref(false)
const materialEpoch = ref(-1)
const materialLoading = ref(false)
const materialError = ref('')
const answerSource = shallowRef<AnswerSource>()
const answerEpoch = ref(-1)
const answerLoading = ref(false)
const answerError = ref('')
const fragments = ref<AiTrainingDraftFragment[]>([])
const fieldKey = ref('')
const rangeStart = ref('')
const rangeEnd = ref('')
const rangeError = ref('')
const previewing = ref(false)
const previewError = ref('')
const prepared = shallowRef<AiPreview>()
const preparedRequest = ref('')
const consent = ref(false)
const authorization = ref<AiAuthorizationReference>()
const deferred = shallowRef<AiTask>()
const createdResult = shallowRef<AiTask>()
const message = ref('')
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || Boolean(createdResult.value))
const configured = computed(() => Boolean(props.settings?.configuration.enabled && props.settings.configuration.configured))
const sourceIdentity = computed(() => JSON.stringify([props.sourceKind, props.initialTraining ?? null, props.routeError ?? '']))
const validMode = computed(() => !props.routeError && (props.sourceKind === 'MATERIAL' && !props.initialTraining
  || props.sourceKind === 'TRAINING' && Boolean(props.initialTraining && [props.initialTraining.sessionId, props.initialTraining.questionId, props.initialTraining.answerId].every(idValid))))
const materialReady = computed(() => readable.value && validMode.value && props.sourceKind === 'MATERIAL' && materialEnabled.value
  && materialEpoch.value === store.sessionEpoch && !materialLoading.value && !materialError.value)
const answerReady = computed(() => readable.value && validMode.value && props.sourceKind === 'TRAINING' && Boolean(answerSource.value)
  && answerEpoch.value === store.sessionEpoch && !answerLoading.value && !answerError.value)
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
  if (!value || !field) return '请选择一段原文或填写原文字段中的起止位置。'
  if (!aiTrainingDraftRangeMatches(field.text, value.start, value.end, value.quote) || !aiPreparationPlanTextValid(value.quote, 8000) || !sourceNonblank(value.quote)) return '请选择非空、最多 8000 个 UTF-16 码元的片段，边界不能拆开完整字符。'
  if (fragments.value.some(item => item.field === value.field && item.elementId === value.elementId && item.start < value.end && value.start < item.end)) return '同一字段或图元素的片段范围不可重叠。'
  return ''
})
const request = computed<AiFactDraftPreviewRequest | undefined>(() => {
  if (!validMode.value) return undefined
  if (props.sourceKind === 'MATERIAL') return { instruction: instruction.value, source: { kind: 'MATERIAL', excerptIds: materials.value.map(item => item.excerptId), training: null } }
  const identity = props.initialTraining!
  return { instruction: instruction.value, source: { kind: 'TRAINING', excerptIds: [], training: { sessionId: identity.sessionId, questionId: identity.questionId,
    answerId: identity.answerId, fragments: fragments.value.map(fragment => ({ ...fragment })) } } }
})
const expectedInput = computed<AiFactDraftInput | undefined>(() => {
  if (!validMode.value) return undefined
  if (props.sourceKind === 'MATERIAL') return { kind: 'MATERIAL', materials: materials.value.map(item => ({ ...item })), training: null }
  if (!answerReady.value || !answerSource.value) return undefined
  return { kind: 'TRAINING', materials: [], training: { ...answerSource.value.fixed, fragments: fragments.value.map(fragment => ({ ...fragment })) } }
})
const requestSignature = computed(() => JSON.stringify(request.value))
const inputSignature = computed(() => expectedInput.value ? aiFactDraftInputFingerprint(expectedInput.value) : '')
const problem = computed(() => {
  if (!validMode.value) return props.routeError || '事实候选入口的来源模式或确切提交参数无效，请从资料或训练重新进入。'
  if (props.sourceKind === 'MATERIAL' && !materialReady.value) return '请先核对资料模块当前可用。'
  if (props.sourceKind === 'TRAINING' && !answerReady.value) return '请先读取入口所指的确切已提交回答。'
  if (!aiPreparationPlanTextValid(instruction.value, 1000)) return '整理要求最多 1000 个 UTF-16 码元，不能含 NUL 或不完整字符。'
  if (!request.value || !aiFactDraftRequestValid(request.value)) return props.sourceKind === 'MATERIAL' ? '请明确选择 1–5 个不同的已保存摘录。' : '请手选 1–8 个有效且不重叠的回答片段。'
  if (props.sourceKind === 'TRAINING' && fragments.value.some(item => { const field = fields.value.find(field => field.field === item.field && field.elementId === item.elementId); return !field || !aiTrainingDraftRangeMatches(field.text, item.start, item.end, item.quote) })) return '已选片段与确切原文不一致，请重新核对范围。'
  if (!expectedInput.value || !aiFactDraftInputValid(expectedInput.value)) return '所选原文或来源元数据不完整，请重新核对。'
  return ''
})
const dirty = computed(() => !createdResult.value && Boolean(instruction.value || materials.value.length || fragments.value.length || rangeStart.value || rangeEnd.value))
const previewMatches = computed(() => Boolean(prepared.value && request.value && expectedInput.value && preparedRequest.value === requestSignature.value
  && matches(prepared.value.manifest, request.value, expectedInput.value)))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const changeState = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
const answerLabels: Record<string, string> = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
let alive = true, accessGeneration = 0, sourceSequence = 0, materialSequence = 0, selectionSequence = 0, previewSequence = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0

watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed; commandState.locked = waiting
  store.changes.set('ai-create', changeState); store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([requestSignature, inputSignature, instruction, fieldKey, rangeStart, rangeEnd], invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch(fieldKey, () => { rangeStart.value = ''; rangeEnd.value = ''; rangeError.value = '' }, { flush: 'sync' })
watch([() => store.sessionEpoch, readable, sourceIdentity], () => {
  accessGeneration++; sourceSequence++; materialSequence++; selectionSequence++
  answerLoading.value = false; materialLoading.value = false; selecting.value = false; answerEpoch.value = -1; materialEpoch.value = -1
  pickerOpen.value = false; pickerGeneration.value++; invalidatePreview()
  // 权限重核不清空原文选择与未加入的选区，也不把来源换成其他回答或自动选中全部原文。
  if (readable.value && validMode.value && !locked.value) {
    if (props.sourceKind === 'MATERIAL') void loadMaterialStatus()
    else void loadAnswer()
  }
}, { immediate: true, flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['AI_CONFIG_CHANGED', 'AI_MANIFEST_CHANGED', 'AI_SOURCE_CHANGED', 'AI_SOURCE_UNAVAILABLE', 'AI_DISABLED', 'AI_NOT_CONFIGURED', 'AI_BUDGET_EXCEEDED', 'MATERIAL_DISABLED'].includes(error.code)) { invalidatePreview(); emit('refresh') }
})
onUnmounted(() => {
  alive = false; sourceSequence++; materialSequence++; selectionSequence++; previewSequence++
  if (store.changes.get('ai-create') === changeState) store.changes.delete('ai-create')
  if (store.changes.get('ai-command') === commandState) store.changes.delete('ai-command')
})

function idValid(value: unknown): value is string { return typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value) }
function sourceNonblank(value: string): boolean {
  // 既有训练来源沿用 Java isBlank，NBSP 等历史合法字符不能被 trim 改判为空。
  return /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
}
function answerTextValid(value: string, maximum: number, required = false): boolean {
  // 保存端以 Unicode 码点计量整答字段；选区另按原始 UTF-16 下标与 8000 码元上限处理。
  return aiPreparationPlanTextValid(value, maximum * 2) && Array.from(value).length <= maximum && (!required || sourceNonblank(value))
}
function invalidatePreview() { previewSequence++; previewing.value = false; prepared.value = undefined; preparedRequest.value = ''; consent.value = false }
async function readCurrent<T>(path: string): Promise<T> {
  const epoch = store.sessionEpoch, generation = accessGeneration, identity = sourceIdentity.value
  const value = await read<T>(path)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || generation !== accessGeneration || identity !== sourceIdentity.value) throw new ApiError(0, 'FACT_SOURCE_CONTEXT_CHANGED', '来源或资料访问条件已变化，本地输入继续保留。')
  return value
}
async function loadMaterialStatus() {
  if (blocked.value || !validMode.value || props.sourceKind !== 'MATERIAL') return
  const sequence = ++materialSequence
  materialLoading.value = true; materialError.value = ''; materialEpoch.value = -1; invalidatePreview()
  try {
    const value = await readCurrent<MaterialStatus>('/materials/status')
    if (!alive || sequence !== materialSequence) return
    if (!value || typeof value.enabled !== 'boolean') throw new ApiError(502, 'FACT_MATERIAL_STATUS_INVALID', '资料模块状态无法核对，请重新读取。')
    materialEnabled.value = value.enabled; materialEpoch.value = store.sessionEpoch
  } catch (error) { if (alive && sequence === materialSequence) materialError.value = errorText(error) }
  finally { if (alive && sequence === materialSequence) materialLoading.value = false }
}
async function chooseMaterial(selected: MaterialExcerpt) {
  if (blocked.value || previewing.value || selecting.value || !materialReady.value || !pickerOpen.value || materials.value.length >= 5
    || !idValid(selected.id) || !idValid(selected.attachmentId) || materials.value.some(item => item.excerptId === selected.id)) return
  const sequence = ++selectionSequence, epoch = store.sessionEpoch
  selecting.value = true; selectionError.value = ''; invalidatePreview()
  try {
    const [excerpt, attachment] = await Promise.all([readCurrent<MaterialExcerpt>(`/materials/excerpts/${selected.id}`), readCurrent<Attachment>(`/materials/attachments/${selected.attachmentId}`)])
    if (!alive || sequence !== selectionSequence || epoch !== store.sessionEpoch || blocked.value || !materialReady.value) return
    if (!excerpt || !attachment || excerpt.id !== selected.id || excerpt.attachmentId !== selected.attachmentId || attachment.id !== excerpt.attachmentId || attachment.status !== 'READY') throw new ApiError(409, 'AI_SOURCE_CHANGED', '摘录或附件与本次选择不一致，请重新选择。')
    const input: Schema['MaterialAiExcerpt'] = { excerptId: excerpt.id, attachmentId: excerpt.attachmentId, fileName: attachment.fileName, sha256: attachment.sha256,
      origin: excerpt.origin, runId: excerpt.runId, segmentId: excerpt.segmentId, locator: excerpt.locator, text: excerpt.text, startOffset: excerpt.startOffset, endOffset: excerpt.endOffset }
    if (!aiFactDraftMaterialMatches(input, selected) || !aiFactDraftInputValid({ kind: 'MATERIAL', materials: [input], training: null })) throw new ApiError(502, 'FACT_MATERIAL_MISMATCH', '确切摘录原文或元数据无法核对，请重新读取选择列表。')
    materials.value.push(input)
  } catch (error) { if (alive && sequence === selectionSequence) selectionError.value = errorText(error) }
  finally { if (alive && sequence === selectionSequence) selecting.value = false }
}
function togglePicker() {
  if (blocked.value || previewing.value || selecting.value || !materialReady.value) return
  pickerOpen.value = !pickerOpen.value
}
function removeMaterial(id: string) { if (!blocked.value && !previewing.value && !selecting.value) materials.value = materials.value.filter(item => item.excerptId !== id) }
async function loadAnswer() {
  if (blocked.value || !validMode.value || props.sourceKind !== 'TRAINING' || !props.initialTraining) return
  const identity = { ...props.initialTraining }, sequence = ++sourceSequence
  answerLoading.value = true; answerError.value = ''; answerEpoch.value = -1; invalidatePreview()
  try {
    // 只读取确切已提交回答，训练题面、评价、个人参考与草稿均不作为事实整理来源。
    const answer = await readCurrent<TrainingAnswer>(`/training/sessions/${identity.sessionId}/questions/${identity.questionId}/answers/${identity.answerId}`)
    if (!alive || sequence !== sourceSequence) return
    if (!answer || answer.id !== identity.answerId || answer.questionId !== identity.questionId || !Number.isSafeInteger(answer.number) || answer.number < 1 || answer.number > 100
      || !['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(answer.condition) || !answer.content || !answerTextValid(answer.content.text, 20000, true)
      || !answer.content.diagram || !Array.isArray(answer.content.diagram.nodes) || !Array.isArray(answer.content.diagram.edges)) throw new ApiError(502, 'FACT_TRAINING_MISMATCH', '读取结果不是入口所指的完整提交，请返回训练重新选择。')
    const { nodes, edges } = answer.content.diagram
    if (nodes.length > 80 || edges.length > 160 || nodes.some(node => !node || !idValid(node.id) || !answerTextValid(node.label, 200, true) || !answerTextValid(node.description, 5000))
      || edges.some(edge => !edge || !idValid(edge.id) || !answerTextValid(edge.label, 200))
      || new Set([...nodes, ...edges].map(item => item.id)).size !== nodes.length + edges.length) throw new ApiError(502, 'FACT_TRAINING_MISMATCH', '回答图的原文字段或标识不完整，请重新读取。')
    const field = (type: AiTrainingDraftFragment['field'], elementId: string | null, label: string, text: string): AnswerField => ({ key: JSON.stringify([type, elementId]), field: type, elementId, label, text })
    answerSource.value = { fixed: { sessionId: identity.sessionId, questionId: identity.questionId, answerId: identity.answerId, answerNumber: answer.number, answerCondition: answer.condition },
      fields: [field('ANSWER_TEXT', null, '回答文字', answer.content.text),
        ...nodes.flatMap((node, index) => [field('ANSWER_NODE_LABEL', node.id, `节点 ${index + 1} · 名称`, node.label), field('ANSWER_NODE_DESCRIPTION', node.id, `节点 ${index + 1} · 说明`, node.description)]),
        ...edges.map((edge, index) => field('ANSWER_EDGE_LABEL', edge.id, `连线 ${index + 1} · 说明`, edge.label))] }
    answerEpoch.value = store.sessionEpoch
    if (!fieldKey.value) fieldKey.value = answerSource.value.fields[0]?.key ?? ''
  } catch (error) { if (alive && sequence === sourceSequence) answerError.value = errorText(error) }
  finally { if (alive && sequence === sourceSequence) answerLoading.value = false }
}
function captureSelection(event: Event) {
  if (blocked.value || previewing.value || !answerReady.value || !selectedField.value) return
  const area = event.target as HTMLTextAreaElement, original = selectedField.value.text
  if (area.value !== original.replace(/\r\n?/g, '\n') || area.selectionEnd <= area.selectionStart) return
  // textarea 的换行已归一，原文下标需恢复成保存的 UTF-16 区间，随后检查代理对边界。
  const offset = (display: number) => {
    let raw = 0, shown = 0
    while (raw < original.length && shown < display) { raw += original[raw] === '\r' && original[raw + 1] === '\n' ? 2 : 1; shown++ }
    return raw
  }
  rangeStart.value = String(offset(area.selectionStart)); rangeEnd.value = String(offset(area.selectionEnd)); rangeError.value = ''
}
function addFragment() {
  if (blocked.value || previewing.value || !answerReady.value || fragments.value.length >= 8) return
  if (!range.value || rangeProblem.value) { rangeError.value = rangeProblem.value; return }
  fragments.value.push({ ...range.value }); rangeStart.value = ''; rangeEnd.value = ''; rangeError.value = ''
}
function removeFragment(index: number) { if (!blocked.value && !previewing.value) fragments.value.splice(index, 1) }
function matches(manifest: AiPreview['manifest'], snapshot: AiFactDraftPreviewRequest, input: AiFactDraftInput): boolean {
  return aiFactDraftSourcesMatch(manifest, snapshot) && Boolean(manifest.factDraft
    && aiFactDraftInputFingerprint(manifest.factDraft) === aiFactDraftInputFingerprint(input))
}
async function prepare() {
  if (blocked.value || !configured.value || problem.value || selecting.value || previewing.value || !request.value || !expectedInput.value) return
  invalidatePreview()
  const epoch = store.sessionEpoch, generation = accessGeneration, sequence = ++previewSequence, signature = requestSignature.value!
  const snapshot = JSON.parse(signature) as AiFactDraftPreviewRequest, input = JSON.parse(JSON.stringify(expectedInput.value)) as AiFactDraftInput
  previewing.value = true; previewError.value = ''; message.value = ''
  try {
    const value = await preview<AiPreview>(aiFactDraftPaths.preview, snapshot)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== accessGeneration || sequence !== previewSequence || signature !== requestSignature.value) return
    if (!matches(value.manifest, snapshot, input)) { previewError.value = '预览中的模式、确切来源、完整选中原文或要求与本次选择不一致，请重新核对。'; return }
    if (JSON.stringify(value.manifest.configuration) !== JSON.stringify(props.settings?.configuration)) { previewError.value = '服务器配置与页面配置已变化，请刷新配置后重新预览。'; emit('refresh'); return }
    prepared.value = value; preparedRequest.value = signature
  } catch (error) { if (alive && sequence === previewSequence && epoch === store.sessionEpoch) previewError.value = errorText(error) }
  finally { if (alive && sequence === previewSequence) previewing.value = false }
}
function receive(value: AiTask) {
  createdResult.value = value; changeState.dirty = false; commandState.locked = false
  message.value = '事实候选任务已创建。请核对原文、改写内容后再选择项目采用，采用后仍须人工核实。'; emit('refresh'); emit('created', value)
}
function create() {
  if (blocked.value || !configured.value || !prepared.value || !previewMatches.value || !consent.value || overBudget.value || selecting.value || problem.value || !expectedInput.value) return
  pickerOpen.value = false; commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration
  const snapshot = JSON.parse(preparedRequest.value) as AiFactDraftPreviewRequest, input = JSON.parse(JSON.stringify(expectedInput.value)) as AiFactDraftInput
  const authorizedFingerprint = aiCandidateManifestFingerprint(prepared.value.manifest)
  const body: AiFactDraftCreateRequest = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  let submitted: typeof writer.pending.value
  void writer.run<AiTask>(aiFactDraftPaths.create, 'POST', body, value => {
    if (!alive) return
    let valid = false
    try { valid = value.task.purpose === 'FACT_DRAFT' && aiFactDraftTaskValid(value) && matches(value.manifest, snapshot, input)
      && aiCandidateManifestFingerprint(value.manifest) === authorizedFingerprint } catch { valid = false }
    // 回执无法核对时任务可能已经建立，保持原 body/key 锁定，不能放开表单重复创建。
    if (!valid) { writer.pending.value = submitted; writer.error.value = new ApiError(502, 'AI_TASK_MISMATCH', '任务回执与授权的来源、配置或费用不一致，请用原请求恢复；输入继续保留。'); emit('refresh'); return }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) deferred.value = value
    else receive(value)
  })
  submitted = writer.pending.value
}
function retryCommand() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="fact-draft-create" aria-label="从所选原文整理事实候选">
    <header class="create-heading"><div><p class="section-label">新建 AI 任务</p><h2>从所选原文整理事实候选</h2><p class="field-help">{{ validMode && sourceKind ? aiFactDraftKindLabels[sourceKind] : '来源待核对' }} · 只整理明确选中的原文。候选不证明实际经历、本人责任或技术正确性。</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!configured" class="notice">AI 未启用或配置尚未完整，仍可阅读并选择来源，输入保留在本页。也可在项目事实中手工记录待核实内容。</p><p v-if="!readable" class="notice" role="status">登录或资料访问待确认，本地输入和尚未加入的选区继续保留。</p><p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="createdResult" class="notice"><NButton text :disabled="!readable" @click="emit('created', createdResult)">打开已创建的事实候选任务</NButton></p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retryCommand">用原请求重试</NButton></p><p v-if="deferred" class="notice" role="status">服务器已确认创建，访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建任务</NButton></p>
    <template v-if="!createdResult">
      <p v-if="!validMode" class="notice error" role="alert">{{ problem }}<RouterLink class="text-link" :to="{ name: 'ai', query: { create: '1', purpose: 'FACT_DRAFT', factSourceKind: 'MATERIAL' } }">明确选择资料模式</RouterLink> · <RouterLink class="text-link" :to="{ name: 'training' }">从确切提交重新进入</RouterLink></p>
      <template v-else-if="readable">
        <section v-if="sourceKind === 'MATERIAL'" class="source-panel"><header class="panel-heading"><h3>本次资料摘录 · {{ materials.length }} / 5</h3><NButton size="small" :disabled="blocked || previewing || selecting || !materialReady || materials.length >= 5 && !pickerOpen" @click="togglePicker">{{ pickerOpen ? '收起摘录选择' : '选择已保存摘录' }}</NButton></header><p class="field-help">每次选择 1–5 个完整摘录。选择项目在采用时进行，项目内容不会参与本次生成。<RouterLink class="text-link" :to="{ name: 'training' }">也可从训练确切提交选择回答片段</RouterLink>。</p><p v-if="materialLoading" class="field-help" role="status">正在核对资料模块状态…</p><p v-if="materialError" class="notice error" role="alert">{{ materialError }}</p><p v-else-if="!materialReady && !materialLoading" class="notice">资料模块当前不可用，已选摘录继续保留。</p><NButton text size="small" :disabled="blocked || previewing || selecting || materialLoading" @click="loadMaterialStatus">重新核对资料状态</NButton><MaterialExcerptPicker v-if="pickerOpen && materialReady" :key="pickerGeneration" search-excerpts for-ai :disabled="blocked || previewing || selecting || materials.length >= 5" :exclude-ids="materials.map(item => item.excerptId)" @choose="chooseMaterial" /><p v-if="selecting" class="field-help" role="status">正在读取确切摘录与附件身份…</p><p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p><article v-for="(material, index) in materials" :key="material.excerptId" class="selected-source"><header class="panel-heading"><div><h4>{{ index + 1 }}. {{ material.fileName }}</h4><p class="field-help">{{ material.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ material.locator }} · {{ material.excerptId }}</p></div><NButton size="small" quaternary :disabled="blocked || previewing || selecting" @click="removeMaterial(material.excerptId)">移出</NButton></header><pre class="exact-text">{{ material.text }}</pre></article></section>
        <template v-else-if="sourceKind === 'TRAINING'"><section class="source-panel"><header class="panel-heading"><h3>固定训练提交</h3><NButton size="small" :disabled="blocked || answerLoading || previewing" @click="loadAnswer">重新读取确切提交</NButton></header><p class="field-help">提交 {{ initialTraining?.answerId }}<template v-if="answerReady && answerSource"> · 第 {{ answerSource.fixed.answerNumber }} 版 · {{ answerLabels[answerSource.fixed.answerCondition] }}</template></p><div class="row-actions"><RouterLink v-if="initialTraining" class="text-link" :to="{ name: 'training', query: { item: initialTraining.sessionId, question: initialTraining.questionId, answer: initialTraining.answerId } }">查看确切提交</RouterLink><RouterLink class="text-link" :to="{ name: 'ai', query: { create: '1', purpose: 'FACT_DRAFT', factSourceKind: 'MATERIAL' } }">切换为资料模式</RouterLink></div><p v-if="answerLoading" class="field-help" role="status">正在读取已提交回答的原文字段…</p><p v-if="answerError" class="notice error" role="alert">{{ answerError }}</p><p class="field-help">仅选择回答文字、图节点名称与说明、连线说明。题面、评价和个人参考不会自动加入；训练假设不能直接当作真实经历。</p></section>
          <section v-if="answerReady" class="source-panel"><h3>手选回答片段 · {{ fragments.length }} / 8</h3><label class="form-field">原文字段<NSelect v-model:value="fieldKey" :options="fields.map(field => ({ value: field.key, label: field.label }))" :disabled="blocked || previewing" /></label><label class="form-field">只读原文<textarea :value="selectedField?.text ?? ''" readonly rows="9" :disabled="blocked || previewing" @select="captureSelection" /></label><div class="range-fields"><label class="form-field">起点（含）<NInput v-model:value="rangeStart" :disabled="blocked || previewing" :input-props="{ inputmode: 'numeric' }" placeholder="从 0 开始" /></label><label class="form-field">终点（不含）<NInput v-model:value="rangeEnd" :disabled="blocked || previewing" :input-props="{ inputmode: 'numeric' }" placeholder="原文字段的位置" /></label><NButton :disabled="blocked || previewing || fragments.length >= 8 || !!rangeProblem" @click="addFragment">加入片段</NButton></div><p class="field-help">选区默认不加入，需明确点击“加入片段”。位置按原文 UTF-16 码元计数；每段最多 8000，同一字段不可重叠。{{ rangeProblem }}</p><pre v-if="range && !rangeProblem" class="exact-text selected-quote">{{ range.quote }}</pre><p v-if="rangeError" class="notice error" role="alert">{{ rangeError }}</p></section>
          <section v-if="fragments.length" class="source-panel"><h3>已选回答片段</h3><article v-for="(fragment, index) in fragments" :key="`${fragment.field}/${fragment.elementId}/${fragment.start}/${fragment.end}`" class="selected-source"><header class="panel-heading"><h4>{{ index + 1 }}. {{ aiTrainingDraftFieldLabels[fragment.field] }} · [{{ fragment.start }}, {{ fragment.end }})</h4><NButton size="small" quaternary :disabled="blocked || previewing" @click="removeFragment(index)">移出</NButton></header><p v-if="fragment.elementId" class="field-help">图元素 {{ fragment.elementId }}</p><pre class="exact-text">{{ fragment.quote }}</pre></article></section>
        </template>
        <label class="form-field">整理要求<NInput v-model:value="instruction" :disabled="blocked || previewing" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="例如：整理为一条待核实的项目事实，缺乏原文依据的字段留空，保留限制与不确定性。" /><span class="field-help">可留空，最多 1000 个 UTF-16 码元，按原文发送。模型不推断本人责任，不补造数字。</span></label><p class="field-help">生成后在任务详情人工编辑并选择项目采用为新的待核实事实，责任边界仍待核实。生成和采用都不记为训练或准备任务完成。</p>
        <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || !!problem || selecting" @click="prepare">预览完整外发清单</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div><p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
        <section v-if="prepared && previewMatches" class="consent-panel"><AiManifest :manifest="prepared.manifest" :budget="prepared.budget" /><p v-if="overBudget" class="notice error">本次预留超过当前剩余预算，请核对预算后重新预览。</p><AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || !configured || selecting">我已核对完整选中原文、来源身份与整理要求，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice><NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !consent || overBudget || !configured || !!problem || selecting" @click="create">授权本次外发并生成事实候选</NButton><p class="field-help">来源、选区、要求、配置或权限变化后需重新预览。结果未知时使用原请求和原幂等键恢复，本地输入继续保留。</p></section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.fact-draft-create { display: grid; gap: 23px; min-width: 0; padding: 25px 27px 30px; }.create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; }.create-heading > div, .panel-heading > div { min-width: 0; }h2 { margin: 7px 0 9px; font-size: 24px; }h3 { margin: 0; font-size: 16px; }h4 { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
.source-panel, .selected-source, .form-field { display: grid; gap: 12px; min-width: 0; }.source-panel, .selected-source { padding-top: 16px; border-top: 1px solid var(--line); }.form-field { color: var(--muted); font-size: 13px; }.form-field > textarea { width: 100%; min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); color: var(--ink); font: 13px/1.75 var(--font-body, sans-serif); resize: vertical; }.range-fields { display: grid; grid-template-columns: 1fr 1fr auto; align-items: end; gap: 12px; }.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }.selected-quote { border-left: 3px solid var(--brand); }.consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); min-width: 0; }.consent-panel > .n-button { justify-self: start; }.field-help { overflow-wrap: anywhere; }
@media (max-width: 700px) { .fact-draft-create { padding: 22px 17px; }h2 { font-size: 21px; }.panel-heading { flex-wrap: wrap; }.range-fields { grid-template-columns: 1fr 1fr; }.range-fields > .n-button { justify-self: start; } }
</style>
