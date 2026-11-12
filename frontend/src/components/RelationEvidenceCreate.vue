<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import { aiDate, aiPreparationPlanTextValid, aiTrainingDraftFieldLabels, aiTrainingDraftRangeMatches } from '../api/ai'
import type { Attachment, MaterialExcerpt, MaterialStatus } from '../api/material'
import type { TrainingAnswer } from '../api/training'
import type { RelationEvidenceCreate, RelationEvidenceEntry, RelationEvidenceFragment, RelationEvidenceKind, RelationEvidenceMaterial, RelationEvidenceTrainingInput } from '../api/relationEvidence'
import { relationEvidenceCreateProblem, relationEvidenceEntryMatches, relationEvidenceFragmentsValid, relationEvidenceMaterialMatches, relationEvidencePaths, relationEvidenceSnapshotValid } from '../api/relationEvidence'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import MaterialExcerptPicker from './material/MaterialExcerptPicker.vue'
import TrainingAnswerPicker from './TrainingAnswerPicker.vue'

type TrainingChoice = { sessionId: string; questionId: string; answerId: string; title: string; createdAt: string; condition: TrainingAnswer['condition'] }
type AnswerField = { key: string; field: RelationEvidenceFragment['field']; elementId: string | null; label: string; text: string }
type AnswerSource = { fixed: Omit<RelationEvidenceTrainingInput, 'fragments'>; fields: AnswerField[] }
const props = withDefaults(defineProps<{ relationId: string; canEdit?: boolean; disabled?: boolean }>(), { canEdit: false })
const emit = defineEmits<{ saved: [value: RelationEvidenceEntry]; state: [value: { dirty: boolean; locked: boolean }]; close: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), viewId = useId()
const key = `relation-evidence-create:${viewId}`, boundId = props.relationId
const active = ref(true), reason = ref(''), confirmed = ref(false), kind = ref<RelationEvidenceKind>('MATERIAL')
const materials = ref<RelationEvidenceMaterial[]>([]), materialVerified = ref<string[]>([])
const materialEnabled = ref(false), materialGeneration = ref(-1), materialLoading = ref(false), materialError = ref('')
const choice = shallowRef<TrainingChoice>(), answerSource = shallowRef<AnswerSource>(), answerGeneration = ref(-1)
const fragments = ref<RelationEvidenceFragment[]>([]), fieldKey = ref(''), rangeStart = ref(''), rangeEnd = ref('')
const pickerOpen = ref(false), pickerGeneration = ref(0), selecting = ref(false), selectionError = ref('')
const deferred = shallowRef<RelationEvidenceEntry>()
const idValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const identityValid = computed(() => idValid(boundId) && boundId === props.relationId)
const readable = computed(() => active.value && identityValid.value && store.sessionActive && !props.disabled
  && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || !props.canEdit || selecting.value || materialLoading.value)
const materialReady = computed(() => materialEnabled.value && materialGeneration.value === accessGeneration && !materialError.value)
const answerReady = computed(() => Boolean(answerSource.value) && answerGeneration.value === accessGeneration)
const fields = computed(() => answerSource.value?.fields ?? [])
const selectedField = computed(() => fields.value.find(field => field.key === fieldKey.value))
const range = computed<RelationEvidenceFragment | undefined>(() => {
  const field = selectedField.value
  if (!field || !/^\d+$/.test(rangeStart.value) || !/^\d+$/.test(rangeEnd.value)) return undefined
  const start = Number(rangeStart.value), end = Number(rangeEnd.value)
  return { field: field.field, elementId: field.elementId, start, end, quote: field.text.slice(start, end) }
})
const rangeProblem = computed(() => {
  const value = range.value, field = selectedField.value
  if (!value || !field) return '请在原文中选取一段文字，或填写起止位置。'
  if (!aiTrainingDraftRangeMatches(field.text, value.start, value.end, value.quote) || !relationEvidenceFragmentsValid([value])) return '片段须为 1–8000 个 UTF-16 码元，不能只有空白，也不能拆开完整字符。'
  if (!relationEvidenceFragmentsValid([...fragments.value, value])) return fragments.value.length >= 8 ? '每条证据最多选择 8 个片段。' : '同一原文字段中的片段不能重叠。'
  return ''
})
const request = computed(() => ({ reason: reason.value, confirmed: confirmed.value, source: kind.value === 'MATERIAL'
  ? { kind: 'MATERIAL', excerptIds: materials.value.map(item => item.excerptId), training: null }
  : { kind: 'TRAINING', excerptIds: [], training: choice.value ? { sessionId: choice.value.sessionId, questionId: choice.value.questionId,
    answerId: choice.value.answerId, fragments: fragments.value.map(item => ({ ...item })) } : null } }))
const sourceProblem = computed(() => {
  if (kind.value === 'MATERIAL') {
    if (!materialReady.value) return '请先核对资料模块当前可用。'
    if (materials.value.some(item => !materialVerified.value.includes(item.excerptId))) return '访问条件已变化，请重新核对所选摘录。'
    return relationEvidenceSnapshotValid({ kind: 'MATERIAL', materials: materials.value, training: null }) ? '' : '请选择 1–5 条完整的已保存摘录。'
  }
  if (!answerReady.value || !answerSource.value) return '请选择或重新核对一次确切提交的回答。'
  if (fragments.value.some(item => { const field = fields.value.find(field => field.field === item.field && field.elementId === item.elementId); return !field || !aiTrainingDraftRangeMatches(field.text, item.start, item.end, item.quote) })) return '所选片段与原文不一致，请重新核对。'
  return relationEvidenceSnapshotValid({ kind: 'TRAINING', materials: [], training: { ...answerSource.value.fixed, fragments: fragments.value } }) ? '' : '请从这次回答中选择 1–8 个不重叠的原文片段。'
})
const problem = computed(() => sourceProblem.value || relationEvidenceCreateProblem(request.value))
const dirty = computed(() => Boolean(reason.value || materials.value.length || choice.value || fragments.value.length || rangeStart.value || rangeEnd.value || confirmed.value))
const changeState = reactive({ dirty: false, locked: false })
const conditionLabels = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
let alive = true, accessGeneration = 0, sourceSequence = 0, statusSequence = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0

watch([dirty, locked], ([unsaved, pending]) => {
  changeState.dirty = unsaved; changeState.locked = pending; store.changes.set(key, changeState)
  emit('state', { dirty: unsaved, locked: pending })
}, { immediate: true, flush: 'sync' })
watch([reason, kind, () => JSON.stringify(materials.value), () => JSON.stringify(fragments.value), fieldKey, rangeStart, rangeEnd], () => { confirmed.value = false }, { flush: 'sync' })
watch(fieldKey, () => { rangeStart.value = ''; rangeEnd.value = '' }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  accessGeneration++; sourceSequence++; statusSequence++; selecting.value = false; materialLoading.value = false
  materialGeneration.value = -1; materialVerified.value = []; answerGeneration.value = -1
  confirmed.value = false; pickerOpen.value = false; pickerGeneration.value++
  // 权限变化只作废本次核对，不丢弃理由、确切来源和未加入的选区。
  if (readable.value && kind.value === 'MATERIAL' && !locked.value) void loadMaterialStatus()
}, { immediate: true, flush: 'sync' })
watch(writer.error, value => {
  if (value) confirmed.value = false
  if (value instanceof ApiError && !writer.pending.value && ['MATERIAL_NOT_FOUND', 'AI_TRAINING_SOURCE_NOT_FOUND', 'AI_SOURCE_CHANGED', 'AI_EVALUATION_ACCESS_REQUIRED', 'RELATION_SOURCE_CHANGED'].includes(value.code)) {
    materialVerified.value = []; answerGeneration.value = -1
  }
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; sourceSequence++; statusSequence++
  if (store.changes.get(key) === changeState) store.changes.delete(key)
  emit('state', { dirty: false, locked: false })
})

function resetDraft() {
  sourceSequence++; selecting.value = false; reason.value = ''; materials.value = []; materialVerified.value = []
  choice.value = undefined; answerSource.value = undefined; answerGeneration.value = -1; fragments.value = []
  fieldKey.value = ''; rangeStart.value = ''; rangeEnd.value = ''; confirmed.value = false; pickerOpen.value = false; selectionError.value = ''
}
function close() {
  if (locked.value || !store.canLeave([key])) return
  resetDraft(); emit('close')
}
function chooseKind(value: RelationEvidenceKind) {
  if (blocked.value || kind.value === value) return
  if ((materials.value.length || choice.value || rangeStart.value || rangeEnd.value) && !window.confirm('切换来源会移出当前已选来源和回答片段，补充理由保留。确定切换？')) return
  const savedReason = reason.value; resetDraft(); reason.value = savedReason; kind.value = value
  if (value === 'MATERIAL' && !materialReady.value) void loadMaterialStatus()
}
async function readCurrent<T>(path: string): Promise<T> {
  const epoch = store.sessionEpoch, generation = accessGeneration
  const value = await read<T>(path)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || generation !== accessGeneration) throw new ApiError(0, 'EVIDENCE_ACCESS_CHANGED', '访问条件已变化，输入和来源选择继续保留。')
  return value
}
async function loadMaterialStatus() {
  if (!readable.value || locked.value || selecting.value || kind.value !== 'MATERIAL') return
  const ticket = ++statusSequence
  materialLoading.value = true; materialError.value = ''; materialGeneration.value = -1; confirmed.value = false
  try {
    const value = await readCurrent<MaterialStatus>('/materials/status')
    if (!alive || ticket !== statusSequence) return
    if (!value || typeof value.enabled !== 'boolean') throw new ApiError(502, 'EVIDENCE_MATERIAL_STATUS_INVALID', '资料模块状态无法核对，请重新读取。')
    materialEnabled.value = value.enabled; materialGeneration.value = accessGeneration
  } catch (error) { if (alive && ticket === statusSequence) materialError.value = errorText(error) }
  finally { if (alive && ticket === statusSequence) materialLoading.value = false }
}
async function readMaterial(id: string, attachmentId: string) {
  const [excerpt, attachment] = await Promise.all([readCurrent<MaterialExcerpt>(`/materials/excerpts/${id}`), readCurrent<Attachment>(`/materials/attachments/${attachmentId}`)])
  if (!excerpt || !attachment || excerpt.id !== id || excerpt.attachmentId !== attachmentId || attachment.id !== attachmentId || attachment.status !== 'READY') throw new ApiError(409, 'SOURCE_CHANGED', '摘录或附件与所选来源不一致，请重新核对。')
  const snapshot: RelationEvidenceMaterial = { excerptId: excerpt.id, attachmentId: excerpt.attachmentId, fileName: attachment.fileName, sha256: attachment.sha256,
    origin: excerpt.origin, runId: excerpt.runId, segmentId: excerpt.segmentId, locator: excerpt.locator, text: excerpt.text, startOffset: excerpt.startOffset, endOffset: excerpt.endOffset }
  if (!relationEvidenceMaterialMatches(snapshot, excerpt)) throw new ApiError(502, 'EVIDENCE_MATERIAL_INVALID', '完整摘录或来源元数据无法核对。')
  return snapshot
}
async function chooseMaterial(selected: MaterialExcerpt) {
  if (blocked.value || !materialReady.value || !pickerOpen.value || kind.value !== 'MATERIAL' || materials.value.length >= 5
    || !idValid(selected.id) || !idValid(selected.attachmentId) || materials.value.some(item => item.excerptId === selected.id)) return
  const ticket = ++sourceSequence; selecting.value = true; selectionError.value = ''; confirmed.value = false
  try {
    const snapshot = await readMaterial(selected.id, selected.attachmentId)
    if (!alive || ticket !== sourceSequence || !readable.value) return
    if (!relationEvidenceMaterialMatches(snapshot, selected)) throw new ApiError(409, 'SOURCE_CHANGED', '摘录与选择列表不一致，请重新选择。')
    materials.value = [...materials.value, snapshot]; materialVerified.value.push(snapshot.excerptId); pickerOpen.value = false
  } catch (error) { if (alive && ticket === sourceSequence) selectionError.value = errorText(error) }
  finally { if (alive && ticket === sourceSequence) selecting.value = false }
}
async function recheckMaterials() {
  if (blocked.value || !materialReady.value || !materials.value.length || kind.value !== 'MATERIAL') return
  const ticket = ++sourceSequence, selected = materials.value.map(item => ({ ...item }))
  selecting.value = true; selectionError.value = ''; confirmed.value = false; materialVerified.value = []
  try {
    const actual = await Promise.all(selected.map(item => readMaterial(item.excerptId, item.attachmentId)))
    if (!alive || ticket !== sourceSequence || !readable.value) return
    if (JSON.stringify(actual) !== JSON.stringify(selected)) throw new ApiError(409, 'SOURCE_CHANGED', '所选摘录的固定原文或元数据发生变化。原选择继续保留，请移出后重新选择。')
    materialVerified.value = selected.map(item => item.excerptId)
  } catch (error) { if (alive && ticket === sourceSequence) selectionError.value = errorText(error) }
  finally { if (alive && ticket === sourceSequence) selecting.value = false }
}
function removeMaterial(id: string) {
  if (blocked.value) return
  materials.value = materials.value.filter(item => item.excerptId !== id); materialVerified.value = materialVerified.value.filter(value => value !== id)
}
function answerTextValid(value: unknown, maximum: number, required = false): value is string {
  // 整答沿保存端的码点上限；片段单独使用原始 UTF-16 范围，保留 Java isBlank 的历史语义。
  return typeof value === 'string' && aiPreparationPlanTextValid(value, maximum * 2) && Array.from(value).length <= maximum
    && (!required || /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value))
}
async function chooseAnswer(selected: TrainingChoice) {
  if (blocked.value || kind.value !== 'TRAINING' || ![selected.sessionId, selected.questionId, selected.answerId].every(idValid)) return
  const same = choice.value?.sessionId === selected.sessionId && choice.value?.questionId === selected.questionId && choice.value?.answerId === selected.answerId
  if (!same && (fragments.value.length || rangeStart.value || rangeEnd.value) && !window.confirm('选择另一份回答会移出当前回答片段和未加入的选区，补充理由保留。确定更换？')) return
  const ticket = ++sourceSequence; selecting.value = true; selectionError.value = ''; confirmed.value = false
  if (same) answerGeneration.value = -1
  try {
    const answer = await readCurrent<TrainingAnswer>(`/training/sessions/${selected.sessionId}/questions/${selected.questionId}/answers/${selected.answerId}`)
    if (!alive || ticket !== sourceSequence) return
    if (!answer || answer.id !== selected.answerId || answer.questionId !== selected.questionId || answer.condition !== selected.condition
      || answer.createdAt !== selected.createdAt || !Number.isSafeInteger(answer.number) || answer.number < 1 || answer.number > 100
      || !['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(answer.condition) || !answer.content || !answerTextValid(answer.content.text, 20000, true)
      || !answer.content.diagram || !Array.isArray(answer.content.diagram.nodes) || !Array.isArray(answer.content.diagram.edges)) throw new ApiError(502, 'EVIDENCE_ANSWER_INVALID', '读取结果与所选的确切提交不一致，请重新查询目录。')
    const { nodes, edges } = answer.content.diagram
    if (nodes.length > 80 || edges.length > 160 || nodes.some(node => !node || !idValid(node.id) || !answerTextValid(node.label, 200, true) || !answerTextValid(node.description, 5000))
      || edges.some(edge => !edge || !idValid(edge.id) || !answerTextValid(edge.label, 200))
      || new Set([...nodes, ...edges].map(item => item.id)).size !== nodes.length + edges.length) throw new ApiError(502, 'EVIDENCE_ANSWER_INVALID', '回答图的原文字段或标识不完整，请重新读取。')
    const field = (type: RelationEvidenceFragment['field'], elementId: string | null, label: string, text: string): AnswerField => ({ key: JSON.stringify([type, elementId]), field: type, elementId, label, text })
    const source: AnswerSource = { fixed: { sessionId: selected.sessionId, questionId: selected.questionId, answerId: selected.answerId, answerNumber: answer.number, answerCondition: answer.condition },
      fields: [field('ANSWER_TEXT', null, '回答文字', answer.content.text), ...nodes.flatMap((node, index) => [field('ANSWER_NODE_LABEL', node.id, `节点 ${index + 1} · 名称`, node.label), field('ANSWER_NODE_DESCRIPTION', node.id, `节点 ${index + 1} · 说明`, node.description)]),
        ...edges.map((edge, index) => field('ANSWER_EDGE_LABEL', edge.id, `连线 ${index + 1} · 说明`, edge.label))] }
    if (same && answerSource.value && JSON.stringify(source) !== JSON.stringify(answerSource.value)) throw new ApiError(409, 'SOURCE_CHANGED', '这次提交的原文字段与已选片段发生变化，原输入继续保留，请重新选择提交。')
    // 更换来源成功后才移出旧片段，读取失败不能损失原来的草稿。
    if (!same) { fragments.value = []; fieldKey.value = ''; rangeStart.value = ''; rangeEnd.value = '' }
    choice.value = { ...selected }; answerSource.value = source; answerGeneration.value = accessGeneration
    if (!fieldKey.value) fieldKey.value = source.fields[0]?.key ?? ''
    pickerOpen.value = false
  } catch (error) { if (alive && ticket === sourceSequence) selectionError.value = errorText(error) }
  finally { if (alive && ticket === sourceSequence) selecting.value = false }
}
function clearAnswer() {
  if (blocked.value || (fragments.value.length || rangeStart.value || rangeEnd.value) && !window.confirm('移出这次提交将同时移出已选片段和未加入的选区，补充理由保留。确定移出？')) return
  choice.value = undefined; answerSource.value = undefined; answerGeneration.value = -1
  fragments.value = []; fieldKey.value = ''; rangeStart.value = ''; rangeEnd.value = ''; confirmed.value = false; selectionError.value = ''
}
function captureSelection(event: Event) {
  if (blocked.value || !answerReady.value || !selectedField.value) return
  const area = event.target as HTMLTextAreaElement, original = selectedField.value.text
  if (area.value !== original.replace(/\r\n?/g, '\n') || area.selectionEnd <= area.selectionStart) return
  // textarea 会归一换行；提交范围必须还原为保存原文的 UTF-16 下标。
  const offset = (display: number) => { let raw = 0, shown = 0; while (raw < original.length && shown < display) { raw += original[raw] === '\r' && original[raw + 1] === '\n' ? 2 : 1; shown++ }; return raw }
  rangeStart.value = String(offset(area.selectionStart)); rangeEnd.value = String(offset(area.selectionEnd))
}
function addFragment() {
  if (blocked.value || !answerReady.value || rangeProblem.value || !range.value) return
  fragments.value = [...fragments.value, { ...range.value }]; rangeStart.value = ''; rangeEnd.value = ''
}
function removeFragment(index: number) { if (!blocked.value) fragments.value = fragments.value.filter((_, position) => position !== index) }
function receive(value: RelationEvidenceEntry) { resetDraft(); emit('saved', value) }
function save() {
  if (blocked.value || problem.value) return
  const body = JSON.parse(JSON.stringify(request.value)) as RelationEvidenceCreate
  commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; pickerOpen.value = false
  let submitted: typeof writer.pending.value
  void writer.run<RelationEvidenceEntry>(relationEvidencePaths.create(boundId), 'POST', body, value => {
    if (!alive) return
    // 元数据回执不匹配时可能已落库，保留原请求及幂等键，不能换键重复追加。
    if (!relationEvidenceEntryMatches(value, boundId, body)) {
      writer.pending.value = submitted
      writer.error.value = new ApiError(502, 'RELATION_EVIDENCE_RECEIPT_INVALID', '保存回执与原请求不一致。输入和原请求已保留，请用原请求核对。')
      return
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) deferred.value = value
    else receive(value)
  })
  submitted = writer.pending.value
}
function retry() {
  if (!readable.value || writer.busy.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; void writer.retry()
}
function receiveDeferred() {
  if (!readable.value || !deferred.value || deferred.value.relationId !== boundId) return
  const value = deferred.value; deferred.value = undefined; receive(value)
}
</script>

<template>
  <section class="evidence-create" aria-label="追加关系证据">
    <header><h4>追加关系证据</h4><NButton text size="small" :disabled="locked" @click="close">取消填写</NButton></header>
    <p class="field-help">为这条关系保存补充理由和确切出处。所选原文单独留存，记录出处不代表关系或项目事实已经核实。</p>
    <p v-if="!identityValid" class="notice error" role="alert">当前关系身份已变化。原输入和未决请求继续保留，请返回原关系后处理。</p>
    <p v-else-if="!readable" class="notice" role="status">访问条件待确认，已填写内容与来源选择保留在当前页面。</p>
    <div v-show="readable" :inert="!readable" class="evidence-fields">
      <p v-if="!canEdit" class="notice" role="status">当前关系暂时不能追加证据。输入继续保留，已发出的请求仍可用原请求核对。</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text size="small" :loading="writer.busy.value" :disabled="!readable || writer.busy.value" @click="retry">用原请求核对</NButton></p>
      <p v-if="deferred" class="notice" role="status">已保存回执在访问条件变化后到达。<NButton text size="small" :disabled="!readable" @click="receiveDeferred">接收已保存记录</NButton></p>
      <label>补充理由<NInput v-model:value="reason" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="blocked" aria-label="关系证据的补充理由" placeholder="说明这份原文怎样支持或限定这条关系。" /><span class="field-help">最多 1000 个 UTF-16 码元，按原文保存。</span></label>
      <fieldset :disabled="blocked"><legend>来源类别</legend><label><input type="radio" :name="`${viewId}-kind`" :checked="kind === 'MATERIAL'" @click.prevent="chooseKind('MATERIAL')" />已保存资料摘录</label><label><input type="radio" :name="`${viewId}-kind`" :checked="kind === 'TRAINING'" @click.prevent="chooseKind('TRAINING')" />训练回答片段</label></fieldset>
      <section v-if="kind === 'MATERIAL'" class="source-selection" aria-label="资料摘录来源">
        <header><h4>完整摘录 · {{ materials.length }} / 5</h4><NButton size="small" :disabled="blocked || !materialReady || materials.length >= 5" :aria-expanded="pickerOpen" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起选择' : '选择已保存摘录' }}</NButton></header>
        <p v-if="materialLoading" class="field-help" role="status">正在核对资料模块…</p>
        <p v-else-if="!materialReady" class="notice" role="status">{{ materialError || (materialGeneration >= 0 && !materialEnabled ? '资料模块当前未启用。所选摘录继续保留。' : '资料模块尚未核对。') }}<NButton text size="small" :disabled="blocked" @click="loadMaterialStatus">重新核对资料状态</NButton></p>
        <p class="field-help">每条证据可选择 1–5 条完整摘录；摘录的文件、定位和固定原文一并保存。</p>
        <article v-for="item in materials" :key="item.excerptId" class="selected-source">
          <header><strong>{{ item.fileName }}</strong><NButton text size="small" :disabled="blocked" @click="removeMaterial(item.excerptId)">移出摘录</NButton></header>
          <p class="field-help">{{ item.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ item.locator }}</p><pre class="exact-text">{{ item.text }}</pre>
        </article>
        <NButton v-if="materials.some(item => !materialVerified.includes(item.excerptId))" size="small" :disabled="blocked || !materialReady" @click="recheckMaterials">重新核对所选摘录</NButton>
        <MaterialExcerptPicker v-if="pickerOpen && readable" :key="pickerGeneration" :disabled="blocked || !materialReady || materials.length >= 5" :exclude-ids="materials.map(item => item.excerptId)" search-excerpts @choose="chooseMaterial" />
      </section>
      <section v-else class="source-selection" aria-label="训练回答来源">
        <header><h4>确切提交与原文片段</h4><NButton size="small" :disabled="blocked" :aria-expanded="pickerOpen" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起提交目录' : choice ? '选择另一份回答' : '选择已提交回答' }}</NButton></header>
        <TrainingAnswerPicker v-if="pickerOpen && readable" :key="pickerGeneration" :disabled="blocked" @choose="chooseAnswer" />
        <template v-if="choice">
          <header><p><strong>{{ choice.title }}</strong><span class="field-help"> · {{ aiDate(choice.createdAt) }} · {{ conditionLabels[choice.condition] }}</span></p><NButton text size="small" :disabled="blocked" @click="clearAnswer">移出这次提交</NButton></header>
          <p v-if="!answerReady" class="notice" role="status">这次提交需要重新核对。已选片段和选区继续保留。<NButton text size="small" :disabled="blocked" @click="chooseAnswer(choice)">重新核对这次提交</NButton></p>
          <template v-if="answerSource">
            <label>原文字段<NSelect v-model:value="fieldKey" :disabled="blocked || !answerReady" :options="fields.map(field => ({ label: field.label, value: field.key }))" aria-label="选择回答中的原文字段" /></label>
            <label v-if="selectedField" :for="`${viewId}-answer`">{{ selectedField.label }}<textarea :id="`${viewId}-answer`" :value="selectedField.text" readonly :disabled="blocked || !answerReady" rows="8" :aria-describedby="`${viewId}-range-help`" @select="captureSelection" @keyup="captureSelection" @mouseup="captureSelection" /></label>
            <p :id="`${viewId}-range-help`" class="field-help">拖选文字或使用 Shift + 方向键选择，再点击“加入片段”。也可填写原文 UTF-16 下标：从 0 开始，结束位置不包含在片段中。</p>
            <div class="range-fields"><label>起始位置<NInput v-model:value="rangeStart" :disabled="blocked || !answerReady" :input-props="{ inputmode: 'numeric', 'aria-label': '片段起始位置' }" /></label><label>结束位置<NInput v-model:value="rangeEnd" :disabled="blocked || !answerReady" :input-props="{ inputmode: 'numeric', 'aria-label': '片段结束位置' }" /></label><NButton size="small" :disabled="blocked || !answerReady || !!rangeProblem" @click="addFragment">加入片段</NButton></div>
            <p v-if="rangeProblem" class="field-help">{{ rangeProblem }}</p><pre v-else-if="range" class="exact-text selected-quote">{{ range.quote }}</pre>
          </template>
          <h4>已选片段 · {{ fragments.length }} / 8</h4>
          <article v-for="(item, index) in fragments" :key="`${item.field}/${item.elementId}/${item.start}/${item.end}`" class="selected-source">
            <header><strong>{{ aiTrainingDraftFieldLabels[item.field] }} · {{ item.start }}–{{ item.end }}</strong><NButton text size="small" :disabled="blocked" @click="removeFragment(index)">移出片段</NButton></header><pre class="exact-text">{{ item.quote }}</pre>
          </article>
        </template>
      </section>
      <p v-if="selecting" class="field-help" role="status">正在核对确切来源，原选择继续保留…</p><p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p>
      <NCheckbox v-model:checked="confirmed" :disabled="blocked || !!sourceProblem">我已核对补充理由和所选原文，确认单独保存这条关系证据</NCheckbox>
      <div class="row-actions"><NButton type="primary" size="small" :loading="writer.busy.value" :disabled="blocked || !!problem" @click="save">保存关系证据</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div>
      <p class="field-help">保存不会调用模型。未保存输入仅留在当前页面；提交结果未知时，请用原请求核对。</p>
    </div>
  </section>
</template>

<style scoped>
.evidence-create, .evidence-fields, .source-selection, .selected-source { display: grid; gap: 12px; min-width: 0; }.evidence-create { padding-top: 16px; border-top: 1px solid var(--line); }.source-selection, .selected-source { padding-top: 14px; border-top: 1px solid var(--line); }header { display: flex; justify-content: space-between; align-items: baseline; flex-wrap: wrap; gap: 8px 14px; }h4, p { margin: 0; }h4, strong { font-size: 13px; overflow-wrap: anywhere; }label { display: grid; gap: 8px; min-width: 0; font-size: 13px; }fieldset { display: flex; flex-wrap: wrap; gap: 12px 20px; margin: 0; padding: 10px 12px; border: 1px solid var(--line); border-radius: 5px; }fieldset label { display: flex; align-items: center; gap: 6px; }legend { font-size: 13px; }.field-help, .notice { overflow-wrap: anywhere; }textarea { box-sizing: border-box; width: 100%; min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); color: var(--ink); font: 13px/1.75 var(--font-body, sans-serif); resize: vertical; }textarea:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }.range-fields { display: grid; grid-template-columns: 1fr 1fr auto; align-items: end; gap: 12px; }.exact-text { margin: 0; padding: 12px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }.selected-quote { border-left: 3px solid var(--brand); }.row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 9px 13px; }@media (max-width: 700px) { .range-fields { grid-template-columns: 1fr 1fr; }.range-fields > .n-button { justify-self: start; } }
</style>
