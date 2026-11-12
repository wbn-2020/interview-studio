<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import type { RelationAiFeedbackChoice, RelationAiFeedbackEvidenceCreate, RelationAiFeedbackEvidenceEntry, RelationAiFeedbackEvidenceFragment, RelationAiFeedbackEvidenceSource } from '../api/relationAiFeedbackEvidence'
import { relationAiFeedbackChoiceValid, relationAiFeedbackEvidenceCreateValid, relationAiFeedbackEvidenceEntryMatches, relationAiFeedbackEvidenceFieldLabels, relationAiFeedbackEvidenceFragmentsValid, relationAiFeedbackEvidencePaths, relationAiFeedbackEvidenceRangeMatches, relationAiFeedbackEvidenceSelectionValid, relationAiFeedbackEvidenceSourceFingerprint, relationAiFeedbackEvidenceSourceValid } from '../api/relationAiFeedbackEvidence'
import { questionDimensionLabels } from '../api/bank'
import { reviewChoiceLabels, reviewCoverageLabels } from '../api/review'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import RelationAiFeedbackPicker from './RelationAiFeedbackPicker.vue'

const props = withDefaults(defineProps<{ relationId: string; canEdit?: boolean; disabled?: boolean }>(), { canEdit: false })
const emit = defineEmits<{ saved: [value: RelationAiFeedbackEvidenceEntry]; close: []; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), viewId = useId()
const boundId = props.relationId, key = `relation-ai-feedback-create:${viewId}`
const active = ref(true), reason = ref(''), confirmed = ref(false), pickerOpen = ref(false), pickerGeneration = ref(0)
const choice = shallowRef<RelationAiFeedbackChoice>(), source = shallowRef<RelationAiFeedbackEvidenceSource>(), sourceGeneration = ref(-1)
const loading = ref(false), problem = ref(''), fragments = ref<RelationAiFeedbackEvidenceFragment[]>([])
const fieldKey = ref<RelationAiFeedbackEvidenceFragment['field']>('OBSERVATION'), rangeStart = ref(''), rangeEnd = ref('')
const deferred = shallowRef<RelationAiFeedbackEvidenceEntry>()
const identityValid = computed(() => /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(boundId) && boundId === props.relationId)
const readable = computed(() => active.value && identityValid.value && store.sessionActive && !props.disabled
  && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || !props.canEdit || locked.value || loading.value)
const sourceReady = computed(() => Boolean(source.value && choice.value) && sourceGeneration.value === accessGeneration)
const fields = computed(() => source.value ? [
  { field: 'OBSERVATION' as const, text: source.value.observation }, { field: 'CONDITION' as const, text: source.value.condition },
  { field: 'BASIS' as const, text: source.value.basis }, { field: 'LIMITATION' as const, text: source.value.limitation },
  { field: 'SUGGESTION' as const, text: source.value.suggestion },
] : [])
const selectedField = computed(() => fields.value.find(field => field.field === fieldKey.value))
const range = computed<RelationAiFeedbackEvidenceFragment | undefined>(() => {
  const field = selectedField.value
  if (!field || !/^\d+$/.test(rangeStart.value) || !/^\d+$/.test(rangeEnd.value)) return undefined
  const start = Number(rangeStart.value), end = Number(rangeEnd.value)
  return { field: field.field, start, end, quote: field.text.slice(start, end) }
})
const rangeProblem = computed(() => {
  const value = range.value, field = selectedField.value
  if (!value || !field) return '请在原文中选取一段文字，或填写起止位置。'
  if (!relationAiFeedbackEvidenceRangeMatches(field.text, value.start, value.end, value.quote) || !relationAiFeedbackEvidenceFragmentsValid([value])) return '请选择 1–8000 个 UTF-16 码元的非空白片段，不能含 NUL，也不能拆开完整字符。'
  if (!relationAiFeedbackEvidenceFragmentsValid([...fragments.value, value])) return fragments.value.length >= 8 ? '每条证据最多选择 8 个片段。' : '同一原文字段内的片段不能重叠。'
  return ''
})
const selection = computed(() => source.value && choice.value ? { taskId: choice.value.taskId, attemptId: source.value.attemptId,
  feedbackId: choice.value.feedbackId, reportHash: source.value.reportHash, expectedDecisionRevision: source.value.decisionRevision,
  answer: { ...choice.value.answer }, fragments: fragments.value.map(fragment => ({ ...fragment })) } : undefined)
const request = computed(() => ({ reason: reason.value, source: selection.value, confirmed: confirmed.value }))
const sourceProblem = computed(() => !sourceReady.value || !source.value ? '请选择或重新核对一项确切 AI 反馈。'
  : relationAiFeedbackEvidenceSelectionValid(selection.value, source.value) ? '' : '请选择这一项反馈中 1–8 个不重叠且与原文一致的片段。')
const inputProblem = computed(() => sourceProblem.value || (relationAiFeedbackEvidenceCreateValid(request.value, source.value) ? ''
  : !confirmed.value ? '请核对理由、报告限制、当时决定和所选片段，再确认保存。' : '补充理由须为 1–1000 个 UTF-16 码元，不能只有空白或含不完整字符。'))
const dirty = computed(() => Boolean(reason.value || choice.value || fragments.value.length || rangeStart.value || rangeEnd.value || confirmed.value))
const changeState = reactive({ dirty: false, locked: false })
const conditionLabels = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
let alive = true, accessGeneration = 0, sequence = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0

watch([dirty, locked], ([unsaved, pending]) => {
  changeState.dirty = unsaved; changeState.locked = pending; store.changes.set(key, changeState)
  emit('state', { dirty: unsaved, locked: pending })
}, { immediate: true, flush: 'sync' })
watch([reason, () => JSON.stringify(fragments.value), fieldKey, rangeStart, rangeEnd], () => { confirmed.value = false }, { flush: 'sync' })
watch(fieldKey, () => { rangeStart.value = ''; rangeEnd.value = '' }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  accessGeneration++; sequence++; loading.value = false; sourceGeneration.value = -1; confirmed.value = false
  pickerOpen.value = false; pickerGeneration.value++
  // 权限变化只遮挡并作废核对代次；原报告身份、决定、片段及未加入选区仍保留，恢复后显式重核。
}, { flush: 'sync' })
watch([writer.error, writer.pending], ([error, pending]) => {
  if (error) confirmed.value = false
  if (error && !pending) sourceGeneration.value = -1
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; sequence++
  if (store.changes.get(key) === changeState) store.changes.delete(key)
  emit('state', { dirty: false, locked: false })
})

function clearSource() {
  sequence++; loading.value = false; choice.value = undefined; source.value = undefined; sourceGeneration.value = -1
  fragments.value = []; fieldKey.value = 'OBSERVATION'; rangeStart.value = ''; rangeEnd.value = ''; confirmed.value = false; problem.value = ''
}
function resetDraft() { clearSource(); reason.value = ''; pickerOpen.value = false }
function close() {
  if (locked.value || !store.canLeave([key])) return
  resetDraft(); emit('state', { dirty: false, locked: false }); emit('close')
}
function sameChoice(left: RelationAiFeedbackChoice | undefined, right: RelationAiFeedbackChoice) {
  return Boolean(left && left.taskId === right.taskId && left.feedbackId === right.feedbackId
    && left.answer.sessionId === right.answer.sessionId && left.answer.questionId === right.answer.questionId && left.answer.answerId === right.answer.answerId)
}
async function loadSource(selected: RelationAiFeedbackChoice, recheck = false) {
  if (blocked.value || !relationAiFeedbackChoiceValid(selected) || recheck && !sameChoice(choice.value, selected)) return
  const replacing = Boolean(source.value || fragments.value.length || rangeStart.value || rangeEnd.value)
  if (!recheck && replacing && !window.confirm('重新选择会移出当前片段与未加入选区，并重新读取所选报告、决定和原文。补充理由保留，确定继续？')) return
  const exact: RelationAiFeedbackChoice = { ...selected, answer: { ...selected.answer } }
  const previousFingerprint = recheck && source.value ? relationAiFeedbackEvidenceSourceFingerprint(source.value) : undefined
  const epoch = store.sessionEpoch, stamp = accessGeneration, ticket = ++sequence
  loading.value = true; problem.value = ''; confirmed.value = false
  if (recheck || sameChoice(choice.value, selected)) sourceGeneration.value = -1
  const current = () => alive && readable.value && epoch === store.sessionEpoch && stamp === accessGeneration && ticket === sequence
  try {
    const value = await read<RelationAiFeedbackEvidenceSource>(relationAiFeedbackEvidencePaths.source(exact.taskId, exact.feedbackId))
    if (!current()) return
    if (!relationAiFeedbackEvidenceSourceValid(value, exact.taskId, exact.feedbackId, exact)) throw new ApiError(502, 'RELATION_AI_FEEDBACK_SOURCE_INVALID', '反馈原文与所选任务、单项反馈或确切回答不一致，请重新查询目录。')
    if (previousFingerprint !== undefined && relationAiFeedbackEvidenceSourceFingerprint(value) !== previousFingerprint) throw new ApiError(409, 'RELATION_AI_FEEDBACK_SOURCE_CHANGED', '成功尝试、报告内容或处理决定已变化。原选择继续保留；请通过“重新选择来源”明确读取新状态后重新选取片段。')
    // 只在新来源读取成功后替换草稿；重核必须包含报告身份与单项决定，不能仅比较任务 ID。
    if (!recheck) { fragments.value = []; fieldKey.value = 'OBSERVATION'; rangeStart.value = ''; rangeEnd.value = '' }
    choice.value = exact; source.value = value; sourceGeneration.value = accessGeneration; pickerOpen.value = false
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (current()) loading.value = false }
}
function removeSource() {
  if (blocked.value || (fragments.value.length || rangeStart.value || rangeEnd.value) && !window.confirm('移出来源会同时移出已选片段和未加入选区，补充理由保留。确定移出？')) return
  clearSource()
}
function captureSelection(event: Event) {
  if (blocked.value || !sourceReady.value || !selectedField.value) return
  const area = event.target as HTMLTextAreaElement, original = selectedField.value.text
  if (area.value !== original.replace(/\r\n?/g, '\n') || area.selectionEnd <= area.selectionStart) return
  // textarea 的归一换行须映射回原文 UTF-16 下标；完整历史字段与所选片段使用各自校验规则。
  const offset = (display: number) => { let raw = 0, shown = 0; while (raw < original.length && shown < display) { raw += original[raw] === '\r' && original[raw + 1] === '\n' ? 2 : 1; shown++ }; return raw }
  rangeStart.value = String(offset(area.selectionStart)); rangeEnd.value = String(offset(area.selectionEnd))
}
function addFragment() {
  if (blocked.value || !sourceReady.value || rangeProblem.value || !range.value) return
  fragments.value = [...fragments.value, { ...range.value }]; rangeStart.value = ''; rangeEnd.value = ''
}
function removeFragment(index: number) { if (!blocked.value) fragments.value = fragments.value.filter((_, position) => position !== index) }
function receive(value: RelationAiFeedbackEvidenceEntry) {
  resetDraft(); emit('state', { dirty: false, locked: false }); emit('saved', value)
}
function save() {
  if (blocked.value || inputProblem.value || !source.value || !relationAiFeedbackEvidenceCreateValid(request.value, source.value)) return
  const body = JSON.parse(JSON.stringify(request.value)) as RelationAiFeedbackEvidenceCreate
  commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; pickerOpen.value = false
  let submitted: typeof writer.pending.value
  void writer.run<RelationAiFeedbackEvidenceEntry>(relationAiFeedbackEvidencePaths.create(boundId), 'POST', body, value => {
    if (!alive) return
    // 元数据回执不匹配也可能已经落库，恢复原命令与键，不能用新键重复追加。
    if (!relationAiFeedbackEvidenceEntryMatches(value, boundId, body)) {
      writer.pending.value = submitted
      writer.error.value = new ApiError(502, 'RELATION_AI_FEEDBACK_RECEIPT_INVALID', '保存回执与原请求不一致。原输入和请求已保留，请用原请求核对。')
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
  <section class="ai-feedback-evidence-create" aria-label="追加 AI 未审反馈证据">
    <header><h4>追加 AI 反馈证据</h4><NButton text size="small" :disabled="locked" @click="close">取消填写</NButton></header>
    <p class="field-help">保存手选 AI 反馈片段、当时的处理决定和报告限制。接受或引用一项观察仍保留“AI 未审”身份，不证明关系正确。</p>
    <p v-if="!identityValid" class="notice error" role="alert">关系身份已变化。输入和原请求继续保留，请返回原关系后处理。</p>
    <p v-else-if="!readable" class="notice" role="status">访问条件待确认，理由、报告身份和已选片段保留在当前页面。</p>
    <div v-show="readable" :inert="!readable || undefined" class="form-content">
      <p v-if="!canEdit" class="notice" role="status">当前关系暂时不能追加证据。输入继续保留，已发出的请求仍可用原请求核对。</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text size="small" :loading="writer.busy.value" :disabled="!readable || writer.busy.value" @click="retry">用原请求核对</NButton></p>
      <p v-if="deferred" class="notice" role="status">保存回执在访问条件变化后到达。<NButton text size="small" :disabled="!readable" @click="receiveDeferred">接收已保存记录</NButton></p>
      <label>补充理由<NInput v-model:value="reason" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="blocked" :input-props="{ 'aria-label': '关系 AI 反馈证据的补充理由' }" placeholder="说明这些反馈片段怎样支持或限定这条关系。" /><span class="field-help">最多 1000 个 UTF-16 码元，按原文保存。</span></label>
      <header><h4>选择单项 AI 反馈</h4><NButton size="small" :disabled="blocked" :aria-expanded="pickerOpen" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起来源目录' : choice ? '重新选择来源' : '选择 AI 未审反馈' }}</NButton></header>
      <RelationAiFeedbackPicker v-if="pickerOpen && readable" :key="pickerGeneration" :disabled="blocked" @select="loadSource($event)" />
      <p v-if="loading" class="field-help" role="status">正在核对成功报告中的这项反馈，原选择继续保留…</p><p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
      <section v-if="source && choice" class="selected-source" aria-label="已选 AI 反馈原文与条件">
        <header><h4>AI 未审反馈 · {{ source.feedbackId }}</h4><NButton text size="small" :disabled="blocked" @click="removeSource">移出来源</NButton></header>
        <p v-if="!sourceReady" class="notice" role="status">当前选择需要重新核对。片段和未加入选区继续保留。<NButton text size="small" :disabled="blocked" @click="loadSource(choice, true)">核对原选择</NButton></p>
        <dl><dt>AI 任务</dt><dd>{{ source.taskId }}</dd><dt>成功尝试</dt><dd>{{ source.attemptId }}</dd><dt>固定报告标识</dt><dd>{{ source.reportHash }}</dd><dt>原回答</dt><dd>第 {{ source.answerNumber }} 次提交 · {{ conditionLabels[source.answerCondition] }}</dd><dt>观察维度</dt><dd>{{ questionDimensionLabels[source.dimension] }} · {{ reviewCoverageLabels[source.coverage] }}</dd><dt>读取时的决定</dt><dd>{{ source.decisionChoice ? reviewChoiceLabels[source.decisionChoice] : '尚未决定' }} · 决定修订 {{ source.decisionRevision }}</dd></dl>
        <p v-if="source.decisionChoice === 'DISPUTED' || source.decisionChoice === 'IGNORED'" class="notice" role="status">这项反馈当时已{{ source.decisionChoice === 'DISPUTED' ? '被提出争议' : '被忽略' }}。可以保留为出处；保存会同时保留这个决定，不将其视为已核实内容。</p>
        <RouterLink class="text-link" :to="{ name: 'training', query: { item: source.answer.sessionId, question: source.answer.questionId, answer: source.answer.answerId } }">前往这份反馈对应的确切训练提交</RouterLink>
        <section class="fixed-context" aria-label="随证据固定保存的报告限制和说明"><h4>报告限制 · 随证据保存</h4><p class="original-text">{{ source.reportLimitations }}</p><h4>报告说明 · 随证据保存</h4><p class="original-text">{{ source.validationNote }}</p></section>
        <section class="fixed-context" aria-label="随证据固定保存的反馈条件依据与限制"><h4>适用条件 · 随证据保存</h4><p class="original-text">{{ source.condition || '未填写适用条件' }}</p><h4>依据 · 随证据保存</h4><p class="original-text">{{ source.basis }}</p><h4>限制 · 随证据保存</h4><p class="original-text">{{ source.limitation }}</p></section>
        <label>原文字段<NSelect v-model:value="fieldKey" :disabled="blocked || !sourceReady" :options="fields.map(field => ({ label: relationAiFeedbackEvidenceFieldLabels[field.field], value: field.field }))" aria-label="选择 AI 反馈中的原文字段" /></label>
        <label v-if="selectedField" :for="`${viewId}-text`">{{ relationAiFeedbackEvidenceFieldLabels[selectedField.field] }}<textarea :id="`${viewId}-text`" :value="selectedField.text" readonly :disabled="blocked || !sourceReady" rows="8" :aria-describedby="`${viewId}-range-help`" @select="captureSelection" @keyup="captureSelection" @mouseup="captureSelection" /></label>
        <p :id="`${viewId}-range-help`" class="field-help">拖选文字或使用 Shift + 方向键选择，再点击“加入片段”。也可填写原文 UTF-16 下标：从 0 开始，结束位置不包含在片段中。观察和建议仅保存手选片段。</p>
        <div class="range-fields"><label>起始位置<NInput v-model:value="rangeStart" :disabled="blocked || !sourceReady" :input-props="{ inputmode: 'numeric', 'aria-label': 'AI 反馈片段起始位置' }" /></label><label>结束位置<NInput v-model:value="rangeEnd" :disabled="blocked || !sourceReady" :input-props="{ inputmode: 'numeric', 'aria-label': 'AI 反馈片段结束位置' }" /></label><NButton size="small" :disabled="blocked || !sourceReady || !!rangeProblem" @click="addFragment">加入片段</NButton></div>
        <p v-if="rangeProblem" class="field-help">{{ rangeProblem }}</p><pre v-else-if="range" class="exact-text selected-quote">{{ range.quote }}</pre>
        <h4>已选片段 · {{ fragments.length }} / 8</h4>
        <article v-for="(fragment, index) in fragments" :key="`${fragment.field}/${fragment.start}/${fragment.end}`" class="fragment"><header><strong>{{ relationAiFeedbackEvidenceFieldLabels[fragment.field] }} · {{ fragment.start }}–{{ fragment.end }}</strong><NButton text size="small" :disabled="blocked" @click="removeFragment(index)">移出片段</NButton></header><pre class="exact-text">{{ fragment.quote }}</pre></article>
      </section>
      <NCheckbox v-model:checked="confirmed" :disabled="blocked || !!sourceProblem">我已核对原报告、处理决定、适用限制和手选片段，理解它仍为 AI 未审内容，确认保存</NCheckbox>
      <div class="row-actions"><NButton type="primary" size="small" :loading="writer.busy.value" :disabled="blocked || !!inputProblem" @click="save">保存 AI 反馈证据</NButton><span v-if="inputProblem" class="field-help">{{ inputProblem }}</span></div>
      <p class="field-help">保存不会调用模型或修改原反馈决定。未保存输入仅留在当前页面；结果未知时请用原请求核对。</p>
    </div>
  </section>
</template>

<style scoped>
.ai-feedback-evidence-create, .form-content, .selected-source, .fixed-context, .fragment { display: grid; gap: 12px; min-width: 0; }.ai-feedback-evidence-create, .selected-source, .fragment { padding-top: 15px; border-top: 1px solid var(--line); }header { display: flex; justify-content: space-between; align-items: baseline; flex-wrap: wrap; gap: 8px 14px; }h4, p { margin: 0; }h4, strong { font-size: 13px; overflow-wrap: anywhere; }label { display: grid; gap: 8px; min-width: 0; font-size: 13px; }.field-help, .notice, .text-link { overflow-wrap: anywhere; }textarea { box-sizing: border-box; width: 100%; min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); color: var(--ink); font: 13px/1.75 var(--font-body, sans-serif); resize: vertical; }textarea:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }.range-fields { display: grid; grid-template-columns: 1fr 1fr auto; align-items: end; gap: 12px; }.original-text, .exact-text { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); }.exact-text, .fixed-context { padding: 12px; background: var(--surface-soft); border-radius: 5px; }.selected-quote { border-left: 3px solid var(--brand); }.row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 9px 13px; }dl { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 7px 12px; margin: 0; font-size: 12px; }dt { color: var(--muted); }dd { margin: 0; overflow-wrap: anywhere; }@media (max-width: 700px) { .range-fields { grid-template-columns: 1fr 1fr; }.range-fields > .n-button { justify-self: start; } }@media (max-width: 560px) { dl { grid-template-columns: 1fr; gap: 5px; }dd { margin-bottom: 6px; } }
</style>
