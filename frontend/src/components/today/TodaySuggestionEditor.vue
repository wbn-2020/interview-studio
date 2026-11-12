<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInputNumber } from 'naive-ui'
import { ApiError, preview, read } from '../../api/client'
import type { TodayContinuation, TodayItem, TodaySourceRef } from '../../api/today'
import { todayErrorText, todaySourceKey, todaySourceKindLabels } from '../../api/today'
import type { AppliedSuggestion, ApplySuggestion, DayBudget, SuggestionPreview, SuggestionRequest } from '../../api/todayPlan'
import { appliedSuggestionValid, applySuggestionValid, budgetLookupPath, budgetLookupValid, createdPlacementValid, savedPlacementValid,
  suggestionLimitationLabels, suggestionPreviewValid, suggestionReasonLabels, suggestionRequestProblem, todayPlanCommandPaths } from '../../api/todayPlan'
import { useWorkspaceStore } from '../../state/workspace'
import { copy, estimateKinds, readPlanSource, usePlanCommand } from './planUi'

const props = defineProps<{ localDate: string; zoneId: string; disabled: boolean; addRequest?: { source: TodaySourceRef; sequence: number } }>()
const emit = defineEmits<{ changed: []; edit: [source: TodaySourceRef]; open: [value: TodayContinuation]; budget: [] }>()
const store = useWorkspaceStore(), readable = computed(() => store.sessionActive && !props.disabled)
const scope = shallowRef({ localDate: props.localDate, zoneId: props.zoneId })
const candidates = ref<{ item: TodayItem; estimatedMinutes: number | null }[]>([]), budget = shallowRef<DayBudget | null>(null)
const queued = ref<TodaySourceRef[]>([]), addPaused = ref(false)
const checked = ref(false), busy = ref(false), error = shallowRef<unknown>(), message = ref('')
const request = shallowRef<SuggestionRequest>(), result = shallowRef<SuggestionPreview>(), selectedKeys = ref<string[]>([]), confirmed = ref(false)
const dirty = computed(() => candidates.value.length > 0 || queued.value.length > 0 || confirmed.value)
const writer = usePlanCommand(readable, 'today-suggestion', dirty)
const currentDay = computed(() => scope.value.localDate === props.localDate && scope.value.zoneId === props.zoneId)
const chosen = computed(() => result.value?.items.filter(item => item.recommended && selectedKeys.value.includes(todaySourceKey(item.source))) ?? [])
const chosenMinutes = computed(() => chosen.value.reduce((total, item) => total + item.estimatedMinutes!, 0))
let alive = true, generation = 0, lastAddSequence = -1
watch([readable, () => store.sessionEpoch], () => {
  generation++; busy.value = false; checked.value = false; confirmed.value = false
  if (!writer.locked.value) invalidatePreview()
  if (readable.value) consumeAdd()
}, { flush: 'sync' })
watch([() => props.localDate, () => props.zoneId], () => {
  generation++; busy.value = false; checked.value = false; confirmed.value = false; invalidatePreview(); consumeAdd()
}, { flush: 'sync' })
watch(() => writer.error.value, value => { if (value) { checked.value = false; confirmed.value = false } })
watch(() => props.addRequest, queueAdd, { immediate: true })
onUnmounted(() => { alive = false; generation++ })
function current(epoch: number, sequence: number) { return alive && readable.value && epoch === store.sessionEpoch && sequence === generation }
function invalidatePreview() { if (!writer.locked.value) { request.value = undefined; result.value = undefined; selectedKeys.value = []; confirmed.value = false } }
function queueAdd() {
  const incoming = props.addRequest
  if (!incoming || incoming.sequence === lastAddSequence) return
  lastAddSequence = incoming.sequence
  const key = todaySourceKey(incoming.source)
  if (candidates.value.some(value => todaySourceKey(value.item.source) === key) || queued.value.some(source => todaySourceKey(source) === key)) { message.value = '该来源已加入本次候选或等待读取，保留原选择与基线。'; return }
  if (candidates.value.length + queued.value.length >= 100) { error.value = new Error('本次候选最多 100 项。'); return }
  queued.value.push(copy(incoming.source)); consumeAdd()
}
function consumeAdd() {
  if (!readable.value || !queued.value.length || addPaused.value || busy.value || writer.locked.value) return
  void add(queued.value[0]!)
}
function retryQueued() { if (readable.value && !busy.value && !writer.locked.value) { addPaused.value = false; consumeAdd() } }
function cancelQueued() { if (readable.value && !busy.value && !writer.locked.value) { queued.value = []; addPaused.value = false } }
async function add(source: TodaySourceRef) {
  const epoch = store.sessionEpoch, sequence = ++generation
  busy.value = true; error.value = undefined; checked.value = false; invalidatePreview()
  try {
    const item = await readPlanSource(source, scope.value.zoneId)
    if (!current(epoch, sequence)) return
    candidates.value.push({ item: copy(item), estimatedMinutes: null }); queued.value.shift(); message.value = `已将「${item.title}」加入本次候选。`
  } catch (reason) { if (current(epoch, sequence)) { error.value = reason; addPaused.value = true } }
  finally { if (current(epoch, sequence)) { busy.value = false; consumeAdd() } }
}
function remove(source: TodaySourceRef) {
  if (!readable.value || writer.locked.value || busy.value) return
  candidates.value = candidates.value.filter(value => todaySourceKey(value.item.source) !== todaySourceKey(source)); checked.value = false; invalidatePreview()
}
function estimate(source: TodaySourceRef, minutes: number | null) {
  if (!readable.value || writer.locked.value || busy.value) return
  const found = candidates.value.find(value => todaySourceKey(value.item.source) === todaySourceKey(source)); if (found) found.estimatedMinutes = minutes
  invalidatePreview()
}
function useCurrentDay() {
  if (!readable.value || writer.locked.value || busy.value) return
  scope.value = { localDate: props.localDate, zoneId: props.zoneId }; budget.value = null; checked.value = false; invalidatePreview()
}
async function recheck() {
  if (!readable.value || writer.locked.value || busy.value || queued.value.length || !candidates.value.length || !currentDay.value) return
  const epoch = store.sessionEpoch, sequence = ++generation, values = copy(candidates.value), selectedDay = copy(scope.value)
  busy.value = true; error.value = undefined; checked.value = false; invalidatePreview()
  try {
    const [lookup, items] = await Promise.all([read<unknown>(budgetLookupPath(selectedDay)), Promise.all(values.map(value => readPlanSource(value.item.source, selectedDay.zoneId)))])
    if (!current(epoch, sequence)) return
    if (!budgetLookupValid(lookup, selectedDay.localDate, selectedDay.zoneId)) throw new ApiError(0, 'TODAY_RESPONSE_INVALID', '预算身份无法核对。')
    budget.value = lookup.budget
    // 仅用户明确重核时替换基线；定时清单刷新不会改变这份候选请求。
    candidates.value = values.map((value, index) => ({ item: items[index]!, estimatedMinutes: value.estimatedMinutes })); checked.value = true
    message.value = '已重新核对全部候选与预算，填写本次估时后可预览。'
  } catch (reason) { if (current(epoch, sequence)) error.value = reason }
  finally { if (current(epoch, sequence)) { busy.value = false; consumeAdd() } }
}
async function makePreview() {
  if (!readable.value || writer.locked.value || busy.value || queued.value.length || !checked.value || !currentDay.value || !candidates.value.length) return
  if (candidates.value.some(value => value.estimatedMinutes !== null && (!Number.isInteger(value.estimatedMinutes) || value.estimatedMinutes < 1 || value.estimatedMinutes > 1440))) { error.value = new Error('本次估时须为空或 1–1440 的整数分钟。'); return }
  const epoch = store.sessionEpoch, sequence = ++generation
  const body: SuggestionRequest = { ...scope.value, expectedBudgetRevision: budget.value?.revision ?? null, items: candidates.value.map(value => ({ source: copy(value.item.source), expectedSource: copy(value.item.stamp), expectedPlacementRevision: value.item.placement?.revision ?? null, estimatedMinutes: estimateKinds.has(value.item.source.kind) ? value.estimatedMinutes : null })) }
  const problem = suggestionRequestProblem(body)
  if (problem) { error.value = new ApiError(400, 'INVALID_REQUEST', problem); return }
  busy.value = true; error.value = undefined; invalidatePreview()
  try {
    const value = await preview<unknown>(todayPlanCommandPaths.preview, body)
    if (!current(epoch, sequence)) return
    if (!suggestionPreviewValid(value, body)) throw new ApiError(0, 'TODAY_RESPONSE_INVALID', '建议预览与明确候选不一致。')
    request.value = copy(body); result.value = value; selectedKeys.value = []; confirmed.value = false
  } catch (reason) { if (current(epoch, sequence)) { error.value = reason; checked.value = false } }
  finally { if (current(epoch, sequence)) { busy.value = false; consumeAdd() } }
}
function choose(source: TodaySourceRef, value: boolean) {
  if (!readable.value || writer.locked.value || busy.value || !checked.value || !currentDay.value) return
  const key = todaySourceKey(source)
  if (!result.value?.items.some(item => item.recommended && todaySourceKey(item.source) === key)) return
  if (value && selectedKeys.value.length >= 50) { error.value = new Error('一次原子采用最多 50 项，请缩小所选子集。'); return }
  selectedKeys.value = value ? [...new Set([...selectedKeys.value, key])] : selectedKeys.value.filter(item => item !== key); confirmed.value = false
}
function apply() {
  if (!readable.value || !checked.value || !currentDay.value || writer.locked.value || busy.value || queued.value.length || !confirmed.value || !request.value || !result.value || !chosen.value.length || chosen.value.length > 50) return
  const displayed = copy(result.value), baselines = copy(candidates.value)
  const body: ApplySuggestion = { request: copy(request.value), expectedPreviewHash: displayed.previewHash, selectedSources: chosen.value.map(item => copy(item.source)), confirmed: true }
  if (!applySuggestionValid(body, displayed)) { error.value = new ApiError(400, 'INVALID_REQUEST', '请重新预览并确认所选推荐项。'); return }
  writer.run(todayPlanCommandPaths.apply, 'POST', body, (value): value is AppliedSuggestion => appliedSuggestionValid(value, body, displayed) && value.placements.every(placement => {
    const item = baselines.find(candidate => todaySourceKey(candidate.item.source) === todaySourceKey(placement.source))!.item
    const content = displayed.items.find(candidate => todaySourceKey(candidate.source) === todaySourceKey(placement.source))!.proposedContent!
    return item.placement ? savedPlacementValid(placement, item.placement.id, item.source, { expectedRevision: item.placement.revision, expectedSource: item.stamp, content, reason: null }, item.placement)
      : createdPlacementValid(placement, { source: item.source, expectedSource: item.stamp, content, reason: null })
  }), () => {
    const appliedKeys = new Set(body.selectedSources.map(todaySourceKey)); candidates.value = candidates.value.filter(value => !appliedKeys.has(todaySourceKey(value.item.source)))
    confirmed.value = false; checked.value = false; invalidatePreview(); message.value = `已整体采用 ${body.selectedSources.length} 项。未选择的候选继续保留，下一批请重新核对。`; emit('changed'); consumeAdd()
  })
}
function title(source: TodaySourceRef) { return candidates.value.find(value => todaySourceKey(value.item.source) === todaySourceKey(source))?.item.title ?? '所选来源' }
</script>

<template>
  <section class="suggestion-editor" aria-labelledby="today-suggestion-title">
    <header><h2 id="today-suggestion-title">本次候选建议</h2><NButton text :disabled="!readable || writer.locked.value" @click="emit('budget')">管理分钟预算</NButton></header>
    <p class="notice">本次候选建议上限；不累计扣除全日时间。只评估你加入的候选，未知估时不按 0 分钟入选。回忆卡张数预算独立，面试时长仅供参考。</p>
    <p class="field-help">候选日 {{ scope.localDate }} · {{ scope.zoneId }} · 已选 {{ candidates.length }}/100 项。采用一次最多 50 项，已采用的其他批次不会自动扣减本次分钟。</p>
    <div v-if="!currentDay" class="notice"><p>显示日期或时区已经变化。候选与估时保留，请切换到当前日后重新核对。</p><NButton :disabled="!readable || writer.locked.value || busy" @click="useCurrentDay">使用 {{ localDate }} · {{ zoneId }}</NButton></div>
    <div v-if="queued.length" class="notice" role="status"><p>还有 {{ queued.length }} 个已选来源等待读取。切换分区或访问中断时会保留这份选择。</p><div class="row-actions"><NButton v-if="addPaused" size="small" :disabled="!readable || busy || writer.locked.value" @click="retryQueued">重试读取待加入来源</NButton><NButton text size="small" :disabled="!readable || busy || writer.locked.value" @click="cancelQueued">取消待加入项</NButton></div></div>
    <p v-if="!candidates.length && !queued.length" class="field-help">从今日事项或安排目录点击“加入建议候选”，逐项选择要考虑的工作。</p>
    <article v-for="candidate in candidates" :key="todaySourceKey(candidate.item.source)" class="candidate-row">
      <div class="candidate-title"><strong>{{ candidate.item.title }}</strong><span>{{ todaySourceKindLabels[candidate.item.source.kind] }} · {{ candidate.item.placement ? `偏好修订 ${candidate.item.placement.revision}` : '尚无保存偏好' }}</span><span v-if="candidate.item.placement?.estimatedMinutes">已保存估时 {{ candidate.item.placement.estimatedMinutes }} 分钟</span></div>
      <label v-if="estimateKinds.has(candidate.item.source.kind)">本次估时（可空）<NInputNumber :value="candidate.estimatedMinutes" :min="1" :max="1440" :precision="0" clearable :disabled="!readable || busy || writer.locked.value" @update:value="value => estimate(candidate.item.source, value)" /></label><p v-else class="field-help">{{ candidate.item.source.kind === 'PREPARATION_TASK' ? `沿用任务 ${candidate.item.estimate.minutes ?? '未知'} 分钟` : '面试只作参考，不参与学习推荐' }}</p>
      <div class="candidate-actions"><NButton text size="small" :disabled="!readable || writer.locked.value || busy" @click="emit('edit', candidate.item.source)">安排偏好</NButton><NButton text size="small" :disabled="!readable || writer.locked.value || busy" @click="emit('open', candidate.item.continuation)">打开来源</NButton><NButton text size="small" :disabled="!readable || writer.locked.value || busy" @click="remove(candidate.item.source)">移出候选</NButton></div>
    </article>
    <p v-if="busy" class="field-help" role="status">正在读取候选、核对预算或生成建议预览…</p><p v-if="message" class="field-help" role="status">{{ message }}</p><p v-if="error" class="notice error" role="alert">{{ todayErrorText(error) }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ todayErrorText(writer.error.value) }}</p>
    <p v-if="checked" class="field-help">{{ budget ? `已读取本日预算：${budget.availableMinutes} 分钟 · 修订 ${budget.revision}` : '本日尚无保存预算，预览会保留未配置原因，不能采用。' }}</p>
    <div class="row-actions"><NButton :loading="busy" :disabled="!readable || busy || writer.locked.value || !currentDay || !candidates.length || !!queued.length" @click="recheck">核对全部候选与预算</NButton><NButton type="primary" :disabled="!readable || !checked || !currentDay || busy || writer.locked.value || !candidates.length || !!queued.length" @click="makePreview">预览本次建议</NButton></div>
    <section v-if="result" class="suggestion-preview">
      <h3>预览结果</h3><p class="field-help">推荐项合计 {{ result.selectedKnownMinutes }} 分钟；仍待估时 {{ result.unknownEstimateCount }} 项；本批未推荐 {{ result.remaining.count }} 项。</p>
      <article v-for="item in result.items" :key="todaySourceKey(item.source)" class="preview-row"><NCheckbox :checked="selectedKeys.includes(todaySourceKey(item.source))" :disabled="!readable || !checked || !item.recommended || writer.locked.value || busy || !currentDay" :aria-label="`选择采用：${title(item.source)}`" @update:checked="value => choose(item.source, value)" /><div><strong>{{ title(item.source) }}</strong><p>{{ item.recommended ? '可选采用' : '本次未推荐' }} · {{ item.estimatedMinutes === null ? '估时未知' : `${item.estimatedMinutes} 分钟` }}</p><p>{{ item.reasonCodes.map(reason => suggestionReasonLabels[reason]).join('；') }}</p><p v-if="item.proposedContent">拟采用：{{ item.proposedContent.plannedDate ? `${item.proposedContent.plannedDate} · ${item.proposedContent.planZoneId}` : '保留来源日期或未安排日期' }}，显示顺序 {{ item.proposedContent.orderValue }}</p></div></article>
      <details><summary>建议的依据与范围</summary><ul><li v-for="limitation in result.limitations" :key="limitation">{{ suggestionLimitationLabels[limitation] }}</li></ul></details>
      <p class="field-help">已选择 {{ chosen.length }}/50 项 · {{ chosenMinutes }} 分钟。只可缩小推荐子集；更换候选或估时后需重新预览。</p><NCheckbox v-model:checked="confirmed" :disabled="!readable || !checked || writer.locked.value || busy || !currentDay || !chosen.length">我确认将以上所选提议保存为安排偏好</NCheckbox>
      <div class="row-actions"><NButton v-if="writer.pending.value && !writer.busy.value" type="primary" :disabled="!readable" @click="writer.retry">用原请求核对采用结果</NButton><NButton v-else type="primary" :loading="writer.busy.value" :disabled="!readable || !checked || !currentDay || writer.locked.value || busy || !confirmed || !chosen.length" @click="apply">整体采用所选建议</NButton></div>
    </section>
    <div v-else-if="writer.pending.value" class="notice"><p>原采用请求及其确认范围仍保留，恢复访问后可核对结果。</p><NButton :disabled="!readable || writer.busy.value" @click="writer.retry">用原请求核对结果</NButton></div>
  </section>
</template>

<style scoped>
.suggestion-editor { display: grid; gap: 16px; padding: 22px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface); }header { display: flex; justify-content: space-between; align-items: center; gap: 15px; }h2 { font-size: 19px; margin: 0; }h3 { font-size: 16px; }.candidate-row { display: grid; grid-template-columns: minmax(0, 1fr) 180px auto; gap: 17px; align-items: start; padding: 17px 0; border-top: 1px solid var(--line); }.candidate-title { display: grid; gap: 7px; min-width: 0; overflow-wrap: anywhere; }.candidate-title strong, .preview-row strong { font-size: 13px; }.candidate-title span, label { font-size: 11px; color: var(--muted); }label { display: grid; gap: 6px; }.candidate-actions { display: grid; gap: 7px; }.suggestion-preview { display: grid; gap: 14px; border-top: 1px solid var(--line); padding-top: 18px; }.preview-row { display: flex; align-items: start; gap: 12px; padding: 14px 0; border-bottom: 1px solid var(--line); }.preview-row > div { min-width: 0; overflow-wrap: anywhere; }.preview-row p { margin-top: 6px; font-size: 11px; color: var(--muted); }@media (max-width: 700px) { .candidate-row { grid-template-columns: 1fr 140px; }.candidate-actions { grid-column: 1 / -1; display: flex; flex-wrap: wrap; }header { align-items: start; flex-wrap: wrap; } }@media (max-width: 450px) { .candidate-row { grid-template-columns: 1fr; } }
</style>
