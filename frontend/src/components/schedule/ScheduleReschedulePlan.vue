<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCheckbox, NInput, NInputNumber } from 'naive-ui'
import { ApiError, preview as requestPreview } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import type { InterviewEvent, ScheduleTimeInput } from '../../api/schedule'
import { formatScheduleTime, scheduleInput } from '../../api/schedule'
import type { AppliedReschedulePlan, ApplyReschedulePlan, LinkEntry, LinkStatus, RescheduleLinkCheck, ReschedulePlanPreview, ReschedulePlanRequest } from '../../api/schedulePreparation'
import { appliedReschedulePlanValid, applyReschedulePlanValid, preparationLinkKindLabels, preparationMoveReasonLabels, preparationMoveWarningLabels,
  rescheduleLimitationLabels, reschedulePlanPreviewValid, reschedulePlanRequestValid, schedulePreparationErrorText as errorText } from '../../api/schedulePreparation'
import { useWorkspaceStore } from '../../state/workspace'
import { copy, readPlanSource, same, usePlanCommand } from '../today/planUi'
import { preparationPath, preparationSourceRoute, preparationStateLabel, preparationTargetsLabel, usePreparationLinks } from './schedulePreparationUi'
import ScheduleTimeField from './ScheduleTimeField.vue'

const props = defineProps<{ event: InterviewEvent; active: boolean; disabled: boolean; refreshToken: number }>()
const emit = defineEmits<{ changed: [] }>()
const store = useWorkspaceStore(), router = useRouter(), readable = computed(() => store.sessionActive && !props.disabled), enabled = computed(() => readable.value && props.active)
const basis = shallowRef(copy(props.event)), time = ref<ScheduleTimeInput>(copy(scheduleInput(props.event).time)), remindBeforeMinutes = ref(props.event.remindBeforeMinutes)
const selected = ref<LinkEntry[]>([]), overrides = ref<Record<string, string>>({}), reason = ref(''), confirmed = ref(false), timeReady = ref(false)
const result = shallowRef<ReschedulePlanPreview>(), frozenRequest = shallowRef<ReschedulePlanRequest>(), overrideChecks = shallowRef<Record<string, RescheduleLinkCheck>>({})
const busy = ref(false), error = shallowRef<unknown>(), message = ref(''), rereadError = ref(''), awaitingRevision = ref<number>()
const dirty = computed(() => !same(time.value, scheduleInput(basis.value).time) || remindBeforeMinutes.value !== basis.value.remindBeforeMinutes || selected.value.length > 0 || Boolean(reason.value) || confirmed.value)
const writer = usePlanCommand(readable, 'schedule-reschedule-plan', dirty)
const filter = ref<LinkStatus | ''>('ACTIVE'), localRefresh = ref(0)
const directory = usePreparationLinks(computed(() => props.event), enabled, filter, computed(() => props.refreshToken + localRefresh.value))
const request = computed<ReschedulePlanRequest>(() => ({ expectedEventRevision: basis.value.revision, time: copy(time.value), remindBeforeMinutes: remindBeforeMinutes.value,
  selectedLinkIds: selected.value.map(entry => entry.link.id), dateOverrides: selected.value.flatMap(entry => overrides.value[entry.link.id] ? [{ linkId: entry.link.id, plannedDate: overrides.value[entry.link.id]! }] : []) }))
const requestKey = computed(() => JSON.stringify(request.value)), currentBasis = computed(() => basis.value.revision === props.event.revision)
const canEdit = computed(() => enabled.value && !writer.locked.value && basis.value.status === 'PLANNED' && awaitingRevision.value === undefined)
const activePreview = computed(() => frozenRequest.value && same(frozenRequest.value, request.value) ? result.value : undefined)
const allMovable = computed(() => Boolean(activePreview.value?.linkChecks.every(movable)))
const applyBody = computed<ApplyReschedulePlan | undefined>(() => activePreview.value && frozenRequest.value ? { request: copy(frozenRequest.value), expectedPreviewHash: activePreview.value.previewHash, confirmed: true, reason: reason.value } : undefined)
const reasonCount = computed(() => Array.from(reason.value).length)
let alive = true, generation = 0, rereadGeneration = 0
function movable(check: RescheduleLinkCheck) { return check.reasons.length === 1 && check.reasons[0] === 'MOVABLE' }
function invalidate() { generation++; busy.value = false; result.value = undefined; frozenRequest.value = undefined; confirmed.value = false }
watch(requestKey, invalidate, { flush: 'sync' })
watch(reason, () => { confirmed.value = false }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => { rereadGeneration++; invalidate(); overrideChecks.value = {}; timeReady.value = false }, { flush: 'sync' })
watch(() => props.refreshToken, () => { invalidate(); overrideChecks.value = {} }, { flush: 'sync' })
watch(() => props.event, value => {
  if (!dirty.value && !writer.locked.value && (awaitingRevision.value === undefined || value.revision >= awaitingRevision.value)) {
    basis.value = copy(value); time.value = copy(scheduleInput(value).time); remindBeforeMinutes.value = value.remindBeforeMinutes; awaitingRevision.value = undefined
  } else if (value.revision !== basis.value.revision) invalidate()
})
watch(() => writer.error.value, value => {
  if (!value) return
  confirmed.value = false
  if (!writer.pending.value) { invalidate(); if (value instanceof ApiError && value.status === 409) emit('changed') }
})
onUnmounted(() => { alive = false; generation++; rereadGeneration++ })
function toggle(entry: LinkEntry, checked: boolean) {
  if (!canEdit.value || entry.link.kind === 'COLLECTION' || entry.link.status !== 'ACTIVE') return
  if (!checked) { selected.value = selected.value.filter(value => value.link.id !== entry.link.id); delete overrides.value[entry.link.id]; return }
  if (selected.value.some(value => value.link.id === entry.link.id)) return
  if (selected.value.length >= 50) { error.value = new Error('一次最多选择 50 个准备事项。'); return }
  selected.value.push(copy(entry)); error.value = undefined
}
function remove(id: string) { if (canEdit.value) { selected.value = selected.value.filter(value => value.link.id !== id); delete overrides.value[id] } }
function override(id: string, value: string) {
  if (!canEdit.value || !overrideChecks.value[id] || !movable(overrideChecks.value[id]!)) return
  if (value) overrides.value[id] = value; else delete overrides.value[id]
}
function adoptLatest() {
  if (!canEdit.value || currentBasis.value) return
  basis.value = copy(props.event); overrideChecks.value = {}; invalidate(); error.value = undefined
  message.value = '已采用最新面试修订作为原时间；拟改时间、选择与原因保留，请重新核对时刻并预览。'; timeReady.value = false
}
function discard() {
  if (!enabled.value || writer.locked.value || dirty.value && !window.confirm('放弃尚未提交的联动时间、选择、日期覆盖和原因？')) return
  basis.value = copy(props.event); time.value = copy(scheduleInput(props.event).time); remindBeforeMinutes.value = props.event.remindBeforeMinutes
  selected.value = []; overrides.value = {}; reason.value = ''; overrideChecks.value = {}; invalidate(); error.value = undefined; timeReady.value = false
}
function refreshDirectory() { if (enabled.value) { invalidate(); overrideChecks.value = {}; directory.refresh() } }
async function generate() {
  if (!canEdit.value || !currentBasis.value || !timeReady.value || busy.value) return
  const body = copy(request.value)
  if (!reschedulePlanRequestValid(body)) { error.value = new Error('请检查面试时刻、提醒分钟数、所选事项和覆盖日期后重试。'); return }
  const number = ++generation, epoch = store.sessionEpoch, key = requestKey.value
  busy.value = true; error.value = undefined; result.value = undefined; frozenRequest.value = undefined; confirmed.value = false
  try {
    const value = await requestPreview<unknown>(`${preparationPath(basis.value.id)}/reschedule-plan-preview`, body)
    if (!alive || !enabled.value || number !== generation || epoch !== store.sessionEpoch || key !== requestKey.value) return
    if (!reschedulePlanPreviewValid(value, body, basis.value)) throw new ApiError(0, 'RESCHEDULE_PLAN_RESPONSE_INVALID', '联动预览无法核对，请重新预览。')
    result.value = value; frozenRequest.value = body; overrideChecks.value = Object.fromEntries(value.linkChecks.map(check => [check.link.id, check]))
  } catch (value) { if (alive && number === generation && epoch === store.sessionEpoch) error.value = value }
  finally { if (alive && number === generation) busy.value = false }
}
async function rereadSources(entries: LinkEntry[]) {
  const number = ++rereadGeneration, epoch = store.sessionEpoch, zone = props.event.time.zoneId
  const values = await Promise.allSettled(entries.filter(entry => entry.link.kind !== 'COLLECTION').map(entry => readPlanSource({ kind: entry.link.kind as 'PREPARATION_TASK' | 'REVIEW_ACTION', id: entry.link.sourceId }, zone)))
  if (!alive || !readable.value || number !== rereadGeneration || epoch !== store.sessionEpoch) return
  const failure = values.find(value => value.status === 'rejected')
  rereadError.value = failure?.status === 'rejected' ? `联动命令已成功，但当前来源重读尚未完成：${errorText(failure.reason)}。进入来源工作台可继续核对。` : ''
}
function apply() {
  if (!canEdit.value || !currentBasis.value || !confirmed.value || !timeReady.value || !activePreview.value || !allMovable.value || !applyBody.value) return
  const body = copy(applyBody.value), preview = copy(activePreview.value), sources = copy(selected.value), previous = copy(basis.value), id = previous.id
  if (!applyReschedulePlanValid(body, preview)) { error.value = new Error('请填写 1–2000 个有效字符的改期原因；空白或无效字符无法保存。'); return }
  // 原始正文、服务端摘要和幂等键交给命令封装保留；未知结果禁止重新预览并另造一次提交。
  writer.run(`${preparationPath(id)}/reschedule-plan`, 'POST', body, (value): value is AppliedReschedulePlan => appliedReschedulePlanValid(value, body, preview, previous), value => {
    awaitingRevision.value = value.event.revision; selected.value = []; overrides.value = {}; reason.value = ''; confirmed.value = false
    time.value = copy(scheduleInput(basis.value).time); remindBeforeMinutes.value = basis.value.remindBeforeMinutes; overrideChecks.value = {}; invalidate(); timeReady.value = false
    rereadError.value = ''; message.value = `联动改期已保存：任务日期变更 ${value.changedTasks.length} 项，动作安排变更 ${value.changedPlacements.length} 项。当前状态以重新读取结果为准。`
    // 回执是当时的成功结果，不能覆盖之后可能再次变更的事件、任务或偏好。
    for (const key of ['schedule', 'preparation-tasks', 'today', 'today-plan', 'today-placement-directory']) void queryClient.invalidateQueries({ queryKey: [key] })
    localRefresh.value++; emit('changed'); void rereadSources(sources)
  })
}
function title(id: string) { return selected.value.find(entry => entry.link.id === id)?.title ?? '已选择的准备事项' }
</script>

<template>
  <section class="reschedule-plan" aria-label="面试与准备事项联动改期">
    <header><h3>联动调整准备日期</h3><p class="field-help">先明确选择，再核对整批变化。默认不选任何事项；题单和路线保留为版本引用。</p></header>
    <p v-if="disabled" class="notice">请先处理其他分区的未保存输入或未确认请求，再编辑联动改期。</p>
    <p v-if="basis.status !== 'PLANNED'" class="notice">这次面试已关闭，不能提交联动改期。</p>
    <div v-if="!currentBasis" class="notice"><p>面试已从修订 {{ basis.revision }} 更新到 {{ event.revision }}。现有输入保留，旧预览已失效。</p><NButton size="small" :disabled="!canEdit" @click="adoptLatest">保留输入，采用最新面试修订</NButton></div>
    <p v-if="awaitingRevision !== undefined" class="notice" role="status">命令已保存，等待读取当前面试状态。<NButton text :disabled="!enabled || writer.locked.value" @click="emit('changed')">重新读取当前安排</NButton></p>
    <p class="basis-time">原面试：{{ basis.time.localStart.replace('T', ' ') }} · {{ basis.time.zoneId }} · 修订 {{ basis.revision }}</p>
    <ScheduleTimeField v-model="time" :disabled="!canEdit" @ready="timeReady = $event" />
    <label class="reminder">新提醒提前分钟数<NInputNumber :value="remindBeforeMinutes" :disabled="!canEdit" :min="0" :max="10080" :precision="0" @update:value="value => remindBeforeMinutes = value ?? -1" /></label>
    <section class="candidate-list"><div class="section-heading"><h4>有效准备关联</h4><NButton size="small" :disabled="!enabled || writer.locked.value || directory.busy.value" @click="refreshDirectory">从首页重读</NButton></div><p class="field-help">当前目录分批读取，不代表全部关联。服务器预览会检查完整关系，包括其他待进行面试。</p><p v-if="directory.busy.value" class="field-help" role="status">正在读取准备关联…</p><p v-if="directory.error.value" class="notice error" role="alert">{{ errorText(directory.error.value) }}</p>
      <article v-for="entry in directory.entries.value" v-show="enabled" :key="entry.link.id"><NCheckbox :checked="selected.some(value => value.link.id === entry.link.id)" :disabled="!canEdit || entry.link.kind === 'COLLECTION'" :aria-label="`选择联动：${entry.title}`" @update:checked="value => toggle(entry, value)" /><div><strong>{{ entry.title }}</strong><p>{{ preparationLinkKindLabels[entry.link.kind] }} · {{ preparationStateLabel(entry.sourceStatus, entry.pendingCount) }}</p><p>{{ preparationTargetsLabel(entry.targetRefs, directory.page.value?.event ?? event) }}</p><p v-if="entry.link.kind === 'COLLECTION'">仅保留确切版本引用，不参与改日。</p></div><NButton text size="small" :disabled="!enabled || writer.locked.value" @click="router.push(preparationSourceRoute(entry.link, entry.sourceStatus.collection?.kind))">查看来源</NButton></article>
      <p v-if="directory.page.value && !directory.entries.value.length" class="field-help">尚无有效准备关联。可先在准备事项分区添加。</p><NButton v-if="directory.page.value?.nextCursor" size="small" :disabled="!enabled || writer.locked.value || directory.busy.value" @click="directory.load(directory.page.value?.nextCursor ?? undefined)">继续读取关联</NButton>
    </section>
    <section class="selection"><h4>本次明确选择 · {{ selected.length }}/50</h4><p v-if="!selected.length" class="field-help">本次只调整面试时间和提醒，不修改任何准备事项日期。</p><article v-for="entry in selected" :key="entry.link.id"><div class="section-heading"><strong>{{ entry.title }}</strong><NButton text size="small" :disabled="!canEdit" @click="remove(entry.link.id)">移出本次选择</NButton></div><label v-if="overrideChecks[entry.link.id] && movable(overrideChecks[entry.link.id]!)">明确覆盖提议日期（{{ overrideChecks[entry.link.id]?.from?.zoneId }}）<input type="date" :value="overrides[entry.link.id] ?? ''" min="1000-01-01" max="9999-12-31" :disabled="!canEdit" @input="override(entry.link.id, ($event.target as HTMLInputElement).value)" /></label><p v-else class="field-help">服务器确认可移动后，才可指定这个事项的覆盖日期。</p><p v-if="overrides[entry.link.id]" class="field-help">已填写覆盖日期 {{ overrides[entry.link.id] }}，须重新预览。<NButton text size="tiny" :disabled="!canEdit" @click="delete overrides[entry.link.id]">清除覆盖</NButton></p></article></section>
    <div class="row-actions"><NButton :disabled="!canEdit || !currentBasis || !timeReady" :loading="busy" @click="generate">生成整批联动预览</NButton><NButton text :disabled="!enabled || writer.locked.value" @click="discard">放弃本次联动输入</NButton></div>
    <p v-if="error" class="notice error" role="alert">{{ errorText(error) }}</p>
    <section v-if="activePreview" class="plan-preview"><h4>核对这次变化</h4><p>新面试：{{ activePreview.to.localStart.replace('T', ' ') }} · {{ activePreview.to.zoneId }}</p><p>新提醒：{{ formatScheduleTime(activePreview.remindAt, activePreview.to.zoneId) }}</p><p class="field-help">实际改变准备日期 {{ activePreview.changes.length }} 项；同日未变化的事项保留检查记录。</p>
      <article v-for="check in activePreview.linkChecks" :key="check.link.id"><strong>{{ title(check.link.id) }}</strong><p>{{ check.reasons.map(value => preparationMoveReasonLabels[value]).join('；') }}</p><p v-if="check.from">原日期：{{ check.from.plannedDate }} · {{ check.from.zoneId }}</p><p v-else>没有原安排日期</p><p v-if="check.proposed">提议日期：{{ check.proposed.plannedDate }} · {{ check.proposed.zoneId }}</p><p v-if="check.started" class="notice">已有真实开始记录，保留原日期。</p><p v-if="check.placement?.dateLocked" class="notice">日期已锁定，需要先到来源安排中处理。</p><p v-for="warning in check.warnings" :key="warning" class="notice">{{ preparationMoveWarningLabels[warning] }}。请确认该日期符合你的准备计划。</p><div v-if="check.otherInterviews.length" class="other-interviews"><p>同时关联以下待进行面试，不能强制联动：</p><div v-for="other in check.otherInterviews" :key="other.eventId"><span>{{ other.title }} · {{ formatScheduleTime(other.startAt, other.zoneId) }} · {{ other.zoneId }} · 修订 {{ other.eventRevision }}</span><NButton text size="small" :disabled="writer.locked.value" @click="router.push({ name: 'schedule', query: { item: other.eventId } })">查看该面试</NButton></div></div></article>
      <p v-if="!allMovable" class="notice error">存在不能移动的已选事项。整批不会提交；请移出该项或到来源处理后重新预览。</p><ul class="limitations"><li v-for="limitation in activePreview.limitations" :key="limitation">{{ rescheduleLimitationLabels[limitation] }}</li></ul>
    </section>
    <label class="reason-field">本次联动改期原因<NInput v-model:value="reason" type="textarea" :disabled="!canEdit" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="说明为什么同时调整这次面试和所选准备日期" /><span class="field-help">{{ reasonCount }}/2000 字符，按原文保存。</span></label>
    <NCheckbox v-model:checked="confirmed" :disabled="!canEdit || !activePreview || !allMovable || !applyBody || !applyReschedulePlanValid(applyBody)">已核对面试时间、每项原时区与提议日期，并理解上面的警告</NCheckbox>
    <NButton type="primary" :loading="writer.busy.value" :disabled="!canEdit || !currentBasis || !timeReady || !confirmed || !activePreview || !allMovable || !applyBody || !applyReschedulePlanValid(applyBody)" @click="apply">整批确认改期</NButton>
    <div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><template v-if="writer.pending.value"><p>结果尚未确认，完整请求与原键已保留。恢复访问后沿原请求核对，不会重新创建一次改期。</p><NButton v-if="!writer.busy.value" size="small" :disabled="!readable" @click="writer.retry">用原请求核对结果</NButton></template><p v-else>输入保留；请核对当前面试修订并重新预览。</p></div>
    <p v-if="message" class="success-text" role="status">{{ message }}</p><p v-if="rereadError" class="notice error" role="alert">{{ rereadError }}</p>
  </section>
</template>

<style scoped>
.reschedule-plan { display: grid; gap: 16px; }h3 { font-size: 15px; }h4 { font-size: 13px; margin: 0; }.basis-time { padding: 11px 0; border-bottom: 1px solid var(--line); font-size: 12px; }.reminder, .reason-field, .selection label { display: grid; gap: 7px; font-size: 12px; color: var(--muted); }.reminder { max-width: 280px; }.section-heading { display: flex; justify-content: space-between; gap: 14px; align-items: center; }.candidate-list, .selection, .plan-preview { display: grid; gap: 12px; }.candidate-list article { display: flex; gap: 12px; align-items: start; padding: 13px 0; border-bottom: 1px solid var(--line); }.candidate-list article > div { flex: 1; min-width: 0; }.candidate-list strong, .selection strong, .plan-preview strong { font-size: 13px; overflow-wrap: anywhere; }.candidate-list article p, .plan-preview p { font-size: 12px; margin-top: 7px; color: var(--muted); }.selection article { display: grid; gap: 9px; padding: 13px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 5px; }.selection input { max-width: 230px; box-sizing: border-box; min-height: 34px; padding: 5px 9px; border: 1px solid var(--line); border-radius: 4px; color: var(--ink); background: var(--surface); }.plan-preview { padding: 16px; border: 1px solid var(--brand); border-radius: 6px; }.plan-preview article { padding-top: 13px; border-top: 1px solid var(--line); }.other-interviews > div { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; font-size: 12px; }.limitations { margin: 0; padding-left: 18px; font-size: 12px; color: var(--muted); line-height: 1.8; }.reschedule-plan > button { justify-self: start; }@media (max-width: 650px) { .candidate-list article { flex-wrap: wrap; }.candidate-list article > button { margin-left: 27px; }.section-heading { align-items: start; } }
</style>
