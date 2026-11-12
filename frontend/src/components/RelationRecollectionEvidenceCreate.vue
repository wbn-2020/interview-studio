<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import { questionTypeLabels } from '../api/bank'
import type { Recollection, RecollectionPage } from '../api/recollection'
import { recollectionBaselineLabels } from '../api/recollection'
import type { RelationRecollectionChoice, RelationRecollectionEvidenceCreate, RelationRecollectionEvidenceEntry, RelationRecollectionEvidenceFragment, RelationRecollectionEvidenceSource } from '../api/relationRecollectionEvidence'
import { relationRecollectionChoiceValid, relationRecollectionEvidenceCreateValid, relationRecollectionEvidenceEntryMatches, relationRecollectionEvidenceFieldLabels, relationRecollectionEvidenceFragmentsValid, relationRecollectionEvidencePaths, relationRecollectionEvidenceRangeMatches, relationRecollectionEvidenceSelectionValid, relationRecollectionEvidenceSourceFingerprint, relationRecollectionEvidenceSourceFrom } from '../api/relationRecollectionEvidence'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import RecollectionPicker from './review/RecollectionPicker.vue'

const props = withDefaults(defineProps<{ relationId: string; canEdit?: boolean; disabled?: boolean }>(), { canEdit: false })
const emit = defineEmits<{ saved: [value: RelationRecollectionEvidenceEntry]; close: []; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), viewId = useId()
const boundId = props.relationId, key = `relation-recollection-create:${viewId}`
const active = ref(true), reason = ref(''), confirmed = ref(false), pickerOpen = ref(false), pickerGeneration = ref(0)
const choice = shallowRef<RelationRecollectionChoice>(), source = shallowRef<RelationRecollectionEvidenceSource>(), sourceGeneration = ref(-1)
const loading = ref(false), problem = ref(''), fragments = ref<RelationRecollectionEvidenceFragment[]>([])
const fieldKey = ref<RelationRecollectionEvidenceFragment['field']>('QUESTION'), rangeStart = ref(''), rangeEnd = ref('')
const deferred = shallowRef<RelationRecollectionEvidenceEntry>()
const identityValid = computed(() => /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(boundId) && boundId === props.relationId)
const readable = computed(() => active.value && identityValid.value && store.sessionActive && !props.disabled
  && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || !props.canEdit || locked.value || loading.value)
const sourceReady = computed(() => Boolean(source.value && choice.value) && sourceGeneration.value === accessGeneration)
const fields = computed(() => source.value ? (Object.keys(relationRecollectionEvidenceFieldLabels) as RelationRecollectionEvidenceFragment['field'][])
  .filter(field => field !== 'ANSWER_FRAGMENT' || source.value!.baselineKind === 'RECOLLECTION')
  .map(field => ({ field, text: source.value!.fields[field] })) : [])
const selectedField = computed(() => fields.value.find(field => field.field === fieldKey.value))
const range = computed<RelationRecollectionEvidenceFragment | undefined>(() => {
  const field = selectedField.value
  if (!field || !/^\d+$/.test(rangeStart.value) || !/^\d+$/.test(rangeEnd.value)) return undefined
  const start = Number(rangeStart.value), end = Number(rangeEnd.value)
  return { field: field.field, start, end, quote: field.text.slice(start, end) }
})
const rangeProblem = computed(() => {
  const value = range.value, field = selectedField.value
  if (!value || !field) return '请在原文中选取一段文字，或填写起止位置。'
  if (!relationRecollectionEvidenceRangeMatches(field.text, value.start, value.end, value.quote) || !relationRecollectionEvidenceFragmentsValid([value])) return '请选择 1–8000 个 UTF-16 码元的非空白片段，边界不能拆开完整字符。'
  if (!relationRecollectionEvidenceFragmentsValid([...fragments.value, value])) return fragments.value.length >= 8 ? '每条证据最多选择 8 个片段。' : '同一原文字段内的片段不能重叠。'
  return ''
})
const selection = computed(() => source.value ? { recollectionId: source.value.recollectionId, versionId: source.value.versionId,
  expectedRevision: source.value.sourceRevision, fragments: fragments.value.map(fragment => ({ ...fragment })) } : undefined)
const request = computed(() => ({ reason: reason.value, source: selection.value, confirmed: confirmed.value }))
const sourceProblem = computed(() => !sourceReady.value || !source.value ? '请选择或重新核对一个确切回忆版本。'
  : relationRecollectionEvidenceSelectionValid(selection.value, source.value) ? '' : '请选择这个回忆版本中 1–8 个不重叠且与原文一致的片段。')
const inputProblem = computed(() => sourceProblem.value || (relationRecollectionEvidenceCreateValid(request.value, source.value) ? ''
  : !confirmed.value ? '请核对理由、完整不确定性与所选片段，再确认保存。' : '补充理由须为 1–1000 个 UTF-16 码元，不能只有空白或含不完整字符。'))
const dirty = computed(() => Boolean(reason.value || choice.value || fragments.value.length || rangeStart.value || rangeEnd.value || confirmed.value))
const changeState = reactive({ dirty: false, locked: false })
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
  // 访问变化只遮挡并使原核对失效；理由、版本、已选片段和未加入选区保留，恢复后显式重核。
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
  fragments.value = []; fieldKey.value = 'QUESTION'; rangeStart.value = ''; rangeEnd.value = ''; confirmed.value = false; problem.value = ''
}
function resetDraft() { clearSource(); reason.value = ''; pickerOpen.value = false }
function close() {
  if (locked.value || !store.canLeave([key])) return
  resetDraft(); emit('state', { dirty: false, locked: false }); emit('close')
}
function sameChoice(left: RelationRecollectionChoice | undefined, right: RelationRecollectionChoice) {
  return Boolean(left && left.id === right.id && left.versionId === right.versionId && left.versionNumber === right.versionNumber
    && left.revision === right.revision && left.title === right.title && left.baselineKind === right.baselineKind)
}
function pickSource(selected: RecollectionPage['items'][number]) {
  if (selected.status !== 'ACTIVE') return
  void loadSource({ id: selected.id, title: selected.title, versionId: selected.versionId, versionNumber: selected.versionNumber,
    revision: selected.revision, baselineKind: selected.baselineKind })
}
async function loadSource(selected: RelationRecollectionChoice, recheck = false) {
  if (blocked.value || !relationRecollectionChoiceValid(selected) || recheck && !sameChoice(choice.value, selected)) return
  const replacing = Boolean(source.value || fragments.value.length || rangeStart.value || rangeEnd.value)
  if (!recheck && replacing && !window.confirm('重新选择会移出当前片段与未加入的选区，并读取所选回忆版本。补充理由保留，确定继续？')) return
  const exact = { ...selected }, previousFingerprint = recheck && source.value ? relationRecollectionEvidenceSourceFingerprint(source.value) : undefined
  const epoch = store.sessionEpoch, stamp = accessGeneration, ticket = ++sequence
  loading.value = true; problem.value = ''; confirmed.value = false
  if (recheck || sameChoice(choice.value, selected)) sourceGeneration.value = -1
  const current = () => alive && readable.value && epoch === store.sessionEpoch && stamp === accessGeneration && ticket === sequence
  try {
    const value = await read<Recollection>(`/review/recollections/${encodeURIComponent(exact.id)}`)
    if (!current()) return
    const projected = relationRecollectionEvidenceSourceFrom(value, exact)
    if (!projected) throw new ApiError(409, 'RELATION_RECOLLECTION_SOURCE_CHANGED', '回忆状态、当前版本或目录内容已变化，无法沿原选择保存。输入继续保留，请重新查询目录并明确选择。')
    if (previousFingerprint !== undefined && relationRecollectionEvidenceSourceFingerprint(projected) !== previousFingerprint) throw new ApiError(409, 'RELATION_RECOLLECTION_SOURCE_CHANGED', '回忆原文或必要限定与原选择不一致。旧片段继续保留，请重新选择来源后核对。')
    // 新来源成功核对后才替换选区；重核同一来源时保留已选和未加入的片段。
    if (!recheck) { fragments.value = []; fieldKey.value = 'QUESTION'; rangeStart.value = ''; rangeEnd.value = '' }
    choice.value = exact; source.value = projected; sourceGeneration.value = accessGeneration; pickerOpen.value = false
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
  // textarea 归一换行后的选区须映射回持久原文，再核验 UTF-16 代理对边界。
  const offset = (display: number) => { let raw = 0, shown = 0; while (raw < original.length && shown < display) { raw += original[raw] === '\r' && original[raw + 1] === '\n' ? 2 : 1; shown++ }; return raw }
  rangeStart.value = String(offset(area.selectionStart)); rangeEnd.value = String(offset(area.selectionEnd))
}
function addFragment() {
  if (blocked.value || !sourceReady.value || rangeProblem.value || !range.value) return
  fragments.value = [...fragments.value, { ...range.value }]; rangeStart.value = ''; rangeEnd.value = ''
}
function removeFragment(index: number) { if (!blocked.value) fragments.value = fragments.value.filter((_, position) => position !== index) }
function receive(value: RelationRecollectionEvidenceEntry) {
  resetDraft(); emit('state', { dirty: false, locked: false }); emit('saved', value)
}
function save() {
  if (blocked.value || inputProblem.value || !source.value || !relationRecollectionEvidenceCreateValid(request.value, source.value)) return
  const body = JSON.parse(JSON.stringify(request.value)) as RelationRecollectionEvidenceCreate, versionNumber = source.value.versionNumber
  commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; pickerOpen.value = false
  void writer.run<RelationRecollectionEvidenceEntry>(relationRecollectionEvidencePaths.create(boundId), 'POST', body, value => {
    if (!alive) return
    // 先核元数据再延迟接收；校验异常由公共命令恢复原请求和原键，避免未知成功被重复追加。
    if (!relationRecollectionEvidenceEntryMatches(value, boundId, body, versionNumber)) throw new ApiError(502, 'RELATION_RECOLLECTION_RECEIPT_INVALID', '保存回执与原请求不一致。原输入和请求已保留，请用原请求核对。')
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) deferred.value = value
    else receive(value)
  })
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
  <section class="recollection-evidence-create" aria-label="追加面后回忆证据">
    <header><h4>追加回忆证据</h4><NButton text size="small" :disabled="locked" @click="close">取消填写</NButton></header>
    <p class="field-help">从一份回忆中选择原文，说明它与这条关系的联系。回忆是个人事后补记，原作答模式、计时与辅助条件未知。</p>
    <p v-if="!identityValid" class="notice error" role="alert">关系身份已变化。输入和原请求继续保留，请返回原关系后处理。</p>
    <p v-else-if="!readable" class="notice" role="status">访问条件待确认，理由、确切回忆版本和已选片段保留在当前页面。</p>
    <div v-show="readable" :inert="!readable || undefined" class="form-content">
      <p v-if="!canEdit" class="notice" role="status">当前关系暂时不能追加证据。输入继续保留，已发出的请求仍可用原请求核对。</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text size="small" :loading="writer.busy.value" :disabled="!readable || writer.busy.value" @click="retry">用原请求核对</NButton></p>
      <p v-if="deferred" class="notice" role="status">保存回执在访问条件变化后到达。<NButton text size="small" :disabled="!readable" @click="receiveDeferred">接收已保存记录</NButton></p>
      <label>补充理由<NInput v-model:value="reason" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="blocked" :input-props="{ 'aria-label': '关系回忆证据的补充理由' }" placeholder="说明这些回忆片段怎样支持或限定这条关系。" /><span class="field-help">最多 1000 个 UTF-16 码元，按原文保存。</span></label>
      <header><h4>选择确切回忆版本</h4><NButton size="small" :disabled="blocked" :aria-expanded="pickerOpen" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起来源目录' : choice ? '重新选择来源' : '选择面后回忆' }}</NButton></header>
      <RecollectionPicker v-if="pickerOpen && readable" :key="pickerGeneration" :disabled="blocked" @select="pickSource" />
      <p v-if="loading" class="field-help" role="status">正在核对这份回忆的当前版本，原选择继续保留…</p><p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
      <section v-if="source && choice" class="selected-source" aria-label="已选回忆原文与必要限定">
        <header><h4>{{ source.title }} · 第 {{ source.versionNumber }} 版</h4><NButton text size="small" :disabled="blocked" @click="removeSource">移出来源</NButton></header>
        <p v-if="!sourceReady" class="notice" role="status">当前选择需要重新核对。片段和未加入的选区继续保留。<NButton text size="small" :disabled="blocked" @click="loadSource(choice, true)">核对原选择</NButton></p>
        <dl><dt>确切回忆</dt><dd>{{ source.recollectionId }} · 对象修订 {{ source.sourceRevision }}</dd><dt>内容版本</dt><dd>{{ source.versionId }} · 第 {{ source.versionNumber }} 版</dd><dt>回忆性质</dt><dd>{{ recollectionBaselineLabels[source.baselineKind] }} · {{ questionTypeLabels[source.type] }}</dd></dl>
        <RouterLink class="text-link" :to="{ name: 'recollections', query: { item: source.recollectionId, versionId: source.versionId } }">查看这份回忆的确切版本</RouterLink>
        <section class="fixed-context" aria-label="随证据固定保存的完整不确定性"><h4>完整不确定之处 · 随证据保存</h4><p class="original-text">{{ source.uncertainty }}</p><p class="field-help">即使没有选取这一字段，以上完整说明也会随证据保留，避免片段脱离回忆的限制。</p></section>
        <p v-if="source.baselineKind === 'NONE'" class="field-help">这份回忆声明没有旧回答，因此不能选择回答片段。</p>
        <label>原文字段<NSelect v-model:value="fieldKey" :disabled="blocked || !sourceReady" :options="fields.map(field => ({ label: relationRecollectionEvidenceFieldLabels[field.field], value: field.field }))" aria-label="选择回忆中的原文字段" /></label>
        <label v-if="selectedField" :for="`${viewId}-text`">{{ relationRecollectionEvidenceFieldLabels[selectedField.field] }}<textarea :id="`${viewId}-text`" :value="selectedField.text" readonly :disabled="blocked || !sourceReady" rows="8" :aria-describedby="`${viewId}-range-help`" @select="captureSelection" @keyup="captureSelection" @mouseup="captureSelection" /></label>
        <p :id="`${viewId}-range-help`" class="field-help">拖选文字或使用 Shift + 方向键选择，再点击“加入片段”。也可填写原文 UTF-16 下标：从 0 开始，结束位置不包含在片段中。</p>
        <div class="range-fields"><label>起始位置<NInput v-model:value="rangeStart" :disabled="blocked || !sourceReady" :input-props="{ inputmode: 'numeric', 'aria-label': '回忆片段起始位置' }" /></label><label>结束位置<NInput v-model:value="rangeEnd" :disabled="blocked || !sourceReady" :input-props="{ inputmode: 'numeric', 'aria-label': '回忆片段结束位置' }" /></label><NButton size="small" :disabled="blocked || !sourceReady || !!rangeProblem" @click="addFragment">加入片段</NButton></div>
        <p v-if="rangeProblem" class="field-help">{{ rangeProblem }}</p><pre v-else-if="range" class="exact-text selected-quote">{{ range.quote }}</pre>
        <h4>已选片段 · {{ fragments.length }} / 8</h4>
        <article v-for="(fragment, index) in fragments" :key="`${fragment.field}/${fragment.start}/${fragment.end}`" class="fragment"><header><strong>{{ relationRecollectionEvidenceFieldLabels[fragment.field] }} · {{ fragment.start }}–{{ fragment.end }}</strong><NButton text size="small" :disabled="blocked" @click="removeFragment(index)">移出片段</NButton></header><pre class="exact-text">{{ fragment.quote }}</pre></article>
      </section>
      <NCheckbox v-model:checked="confirmed" :disabled="blocked || !!sourceProblem">我已核对理由、确切回忆版本、完整不确定性与所选片段，确认保存这条关系证据</NCheckbox>
      <div class="row-actions"><NButton type="primary" size="small" :loading="writer.busy.value" :disabled="blocked || !!inputProblem" @click="save">保存回忆证据</NButton><span v-if="inputProblem" class="field-help">{{ inputProblem }}</span></div>
      <p class="field-help">保存不调用模型，也不将回忆认证为事实或正式回答。未保存输入仅留在当前页面；结果未知时请用原请求核对。</p>
    </div>
  </section>
</template>

<style scoped>
.recollection-evidence-create, .form-content, .selected-source, .fixed-context, .fragment { display: grid; gap: 12px; min-width: 0; }
.recollection-evidence-create, .selected-source, .fragment { padding-top: 15px; border-top: 1px solid var(--line); }
header { display: flex; justify-content: space-between; align-items: baseline; flex-wrap: wrap; gap: 8px 14px; }h4, p { margin: 0; }h4, strong { font-size: 13px; overflow-wrap: anywhere; }
label { display: grid; gap: 8px; min-width: 0; font-size: 13px; }.field-help, .notice, .text-link { overflow-wrap: anywhere; }.text-link { font-size: 13px; }
textarea { box-sizing: border-box; width: 100%; min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); color: var(--ink); font: 13px/1.75 var(--font-body, sans-serif); resize: vertical; }
textarea:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }.range-fields { display: grid; grid-template-columns: 1fr 1fr auto; align-items: end; gap: 12px; }
.original-text, .exact-text { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); }
.exact-text, .fixed-context { padding: 12px; background: var(--surface-soft); border-radius: 5px; }.selected-quote { border: 1px solid var(--line); }.row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 9px 13px; }
dl { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 7px 12px; margin: 0; font-size: 12px; }dt { color: var(--muted); }dd { margin: 0; overflow-wrap: anywhere; }
@media (max-width: 700px) { .range-fields { grid-template-columns: 1fr 1fr; }.range-fields > .n-button { justify-self: start; } }
@media (max-width: 560px) { dl { grid-template-columns: 1fr; gap: 5px; }dd { margin-bottom: 6px; } }
</style>
