<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError } from '../../api/client'
import type { MemoryRecallState, MemoryReviewSettings, MemorySchedule } from '../../api/memoryReview'
import { memoryScheduleValid, memoryUtcMicros as micros, memoryReviewErrorText as errorText, memoryEnableScheduleProblem,
  memoryDeferScheduleProblem, memoryPauseScheduleProblem, memoryResumeScheduleProblem } from '../../api/memoryReview'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ state: MemoryRecallState; settings?: MemoryReviewSettings; verified?: boolean; disabled?: boolean; mutationDisabled?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), active = ref(true)
type Action = 'ENABLE' | 'DEFER' | 'PAUSE' | 'RESUME'
const action = ref<Action>(), base = shallowRef<MemoryRecallState>(), reason = ref(''), at = ref('')
const timeMode = ref<'NOW' | 'AT' | null>(null), policy = ref<'KEEP' | 'RESET_AS_NEW' | null>(null), confirmed = ref(false)
const needsReview = ref(false), message = ref(''), deferred = shallowRef<() => void>()
const stateKey = `memory-schedule-editor-${props.state.cardId}`, changes = reactive({ dirty: false, locked: false })
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const otherLocked = computed(() => [...store.changes].some(([key, value]) => key.startsWith('memory-') && key !== stateKey && value.locked))
const blocked = computed(() => !readable.value || locked.value || otherLocked.value || props.mutationDisabled)
const dirty = computed(() => Boolean(action.value && (reason.value || at.value || timeMode.value || policy.value || confirmed.value)))
const stale = computed(() => Boolean(base.value && (action.value === 'PAUSE' || action.value === 'DEFER'
  ? JSON.stringify(base.value.schedule) !== JSON.stringify(props.state.schedule)
  : basisSignature(base.value) !== basisSignature(props.state))))
const formApplicable = computed(() => Boolean(action.value && allowed(action.value)))
const problem = computed(() => {
  if (!action.value) return ''
  if (!formApplicable.value) return '当前卡片或排程状态不再适合这项操作，请先核对最新状态。'
  if (!base.value) return '请重新核对排程基线。'
  return requestProblem(action.value, commandBody(action.value, base.value))
})
const canSubmit = computed(() => !blocked.value && props.verified && !needsReview.value && !stale.value && Boolean(action.value) && !problem.value && confirmed.value)
const pauseLabels = { MANUAL: '人工暂停', SOURCE_REVIEW: '来源需复核', CARD_ARCHIVED: '卡片归档' }
const actionLabels: Record<Action, string> = { ENABLE: '启用排程', DEFER: '延后实际到期', PAUSE: '暂停排程', RESUME: '恢复排程' }
const timeOptions = [{ label: '立即生效（由服务器确定时间）', value: 'NOW' }, { label: '指定未来时间', value: 'AT' }]
const policyOptions = [{ label: '沿用原周期与算法状态', value: 'KEEP' }, { label: '作为新卡重新启用', value: 'RESET_AS_NEW' }]
let alive = true, generation = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
watch([dirty, locked], ([changed, waiting]) => { changes.dirty = changed; changes.locked = waiting; store.changes.set(stateKey, changes) }, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => { generation++; confirmed.value = false; if (action.value) needsReview.value = true }, { flush: 'sync' })
watch([reason, at, timeMode, policy, () => basisSignature(props.state)], () => { confirmed.value = false }, { flush: 'sync' })
watch(writer.error, error => { if (error instanceof ApiError && !writer.pending.value) { confirmed.value = false; if (error.status === 409) needsReview.value = true } })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; if (store.changes.get(stateKey) === changes) store.changes.delete(stateKey) })

function basisSignature(value: MemoryRecallState) { return JSON.stringify([value.cardId, value.cardRevision, value.cardVersionId, value.cardStatus, value.gate, value.sources, value.schedule, value.eligibility]) }
function copy(value: MemoryRecallState): MemoryRecallState { return JSON.parse(JSON.stringify(value)) as MemoryRecallState }
function allowed(value: Action) {
  if (!props.state.gate) return false
  if (value === 'PAUSE' || value === 'DEFER') return props.state.schedule?.status === 'ACTIVE'
  return props.settings?.configured === true && props.state.cardStatus === 'ACTIVE' && props.state.eligibility.basisReady
    && (value === 'ENABLE' ? props.state.schedule === null : props.state.schedule?.status === 'PAUSED')
}
function open(value: Action) {
  if (blocked.value || !props.verified || !allowed(value) || (dirty.value && !store.canLeave([stateKey]))) return
  action.value = value; base.value = copy(props.state); reason.value = ''; at.value = ''; timeMode.value = null; policy.value = null
  confirmed.value = false; needsReview.value = false; message.value = ''; writer.error.value = undefined
}
function close() {
  if (!readable.value || locked.value || !store.canLeave([stateKey])) return
  action.value = undefined; base.value = undefined; reason.value = ''; at.value = ''; timeMode.value = null; policy.value = null; confirmed.value = false; needsReview.value = false
}
function adoptCurrent() {
  if (blocked.value || !props.verified || !action.value || !allowed(action.value)) return
  base.value = copy(props.state); needsReview.value = false; confirmed.value = false; writer.error.value = undefined
  message.value = '已采用当前修订，时间与理由保留。请重新核对后确认。'
}
function refresh() { if (readable.value && !locked.value) emit('changed') }
function commandBody(operation: Action, fixed: MemoryRecallState) {
  const common = { expectedCardRevision: fixed.cardRevision, expectedGateRevision: fixed.gate?.revision }
  return operation === 'ENABLE' ? { ...common, timeMode: timeMode.value, at: timeMode.value === 'AT' ? at.value : null, confirmed: true }
    : operation === 'DEFER' ? { expectedScheduleRevision: fixed.schedule?.revision, deferUntil: at.value, reason: reason.value }
      : operation === 'PAUSE' ? { expectedScheduleRevision: fixed.schedule?.revision, reason: reason.value }
        : { ...common, expectedScheduleRevision: fixed.schedule?.revision, policy: policy.value, timeMode: timeMode.value, at: timeMode.value === 'AT' ? at.value : null, confirmed: true, reason: reason.value }
}
function requestProblem(operation: Action, body: unknown) {
  if (operation === 'ENABLE') return memoryEnableScheduleProblem(body, props.state.asOf)
  if (operation === 'DEFER') return memoryDeferScheduleProblem(body, props.state.schedule?.effectiveDueAt, props.state.asOf)
  return operation === 'PAUSE' ? memoryPauseScheduleProblem(body) : memoryResumeScheduleProblem(body, props.state.asOf)
}
function submit() {
  if (!canSubmit.value || !action.value || !base.value) return
  const operation = action.value, fixed = copy(base.value), mode = timeMode.value, resumePolicy = policy.value, target = at.value
  const body = commandBody(operation, fixed)
  if (requestProblem(operation, body)) return
  const path = `/memory/cards/${fixed.cardId}/schedule${operation === 'ENABLE' ? '' : `/${operation.toLowerCase()}`}`
  commandEpoch = store.sessionEpoch; commandGeneration = generation; message.value = ''
  let original: typeof writer.pending.value
  void writer.run<MemorySchedule>(path, 'POST', body, value => {
    if (!alive) return
    if (!receiptValid(value, fixed, operation, mode, target, resumePolicy)) {
      writer.pending.value = original; writer.error.value = new ApiError(502, 'MEMORY_SCHEDULE_RECEIPT_INVALID', '排程回执与本次操作不一致，原请求及输入保留，请用原请求核对。'); return
    }
    const receive = () => {
      deferred.value = undefined; writer.error.value = undefined; action.value = undefined; base.value = undefined; reason.value = ''; at.value = ''; timeMode.value = null; policy.value = null; confirmed.value = false; needsReview.value = false
      message.value = `${actionLabels[operation]}已确认（排程修订 ${value.revision}）。本次没有产生复习或评分记录。`
      void queryClient.invalidateQueries({ queryKey: ['memory'] }); emit('changed')
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) deferred.value = receive
    else receive()
  })
  original = writer.pending.value
}
function receiptValid(value: MemorySchedule, fixed: MemoryRecallState, operation: Action, mode: typeof timeMode.value, target: string, resumePolicy: typeof policy.value): boolean {
  try {
    if (!memoryScheduleValid(value, fixed.cardId)) return false
    const previous = fixed.schedule, effectiveAt = mode === 'NOW' ? value.updatedAt : target
    if (operation === 'ENABLE') return previous === null && value.revision === 0 && value.lastHistorySequence === 1 && value.status === 'ACTIVE'
      && value.createdAt === value.updatedAt && initial(value, effectiveAt)
    if (!previous || value.revision !== previous.revision + 1 || value.lastHistorySequence !== previous.lastHistorySequence + 1 || value.createdAt !== previous.createdAt
      || value.algorithmVersion !== previous.algorithmVersion || value.parameterVersion !== previous.parameterVersion) return false
    if (operation === 'RESUME' && resumePolicy === 'RESET_AS_NEW') return value.status === 'ACTIVE' && value.cycleId !== previous.cycleId && initial(value, effectiveAt)
    if (value.cycleId !== previous.cycleId || JSON.stringify(value.state) !== JSON.stringify(previous.state) || value.suggestedDueAt !== previous.suggestedDueAt || value.lastRatedEventId !== previous.lastRatedEventId) return false
    if (operation === 'PAUSE') return value.status === 'PAUSED' && value.pauseReason === 'MANUAL' && value.deferUntil === previous.deferUntil && value.effectiveDueAt === previous.effectiveDueAt
    const until = operation === 'DEFER' ? target : effectiveAt
    return value.status === 'ACTIVE' && value.pauseReason === null && sameTime(value.deferUntil, until)
      && micros(value.effectiveDueAt) === maximum(micros(previous.suggestedDueAt), micros(until))
  } catch { return false }
}
function initial(value: MemorySchedule, dueAt: string) { return value.pauseReason === null && value.deferUntil === null && value.lastRatedEventId === null
  && value.state.phase === 'LEARNING' && value.state.step === 0 && value.state.stability === null && value.state.difficulty === null && value.state.lastReviewAt === null
  && sameTime(value.state.dueAt, dueAt) && sameTime(value.suggestedDueAt, dueAt) && sameTime(value.effectiveDueAt, dueAt) }
function retry() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) deferred.value() }
function sameTime(left: string | null, right: string) { return left !== null && micros(left) !== undefined && micros(left) === micros(right) }
function maximum(left: bigint | undefined, right: bigint | undefined) { return left === undefined || right === undefined ? undefined : left > right ? left : right }
function time(value: string | null) {
  if (!value) return '未设置'
  try { return new Date(value).toLocaleString('zh-CN', props.settings?.zoneId ? { timeZone: props.settings.zoneId } : undefined) }
  catch { return value }
}
</script>

<template>
  <section class="schedule-editor" aria-label="回忆卡排程管理">
    <header><h3>排程</h3><p class="field-help">启用、延期与恢复只调整未来安排，实际复习需要另行作答与自评。</p></header>
    <p v-if="!readable" class="notice" role="status">访问恢复后可继续处理排程，未保存的时间与理由继续保留。</p>
    <template v-else>
      <p v-if="!settings" class="field-help">复习设置待读取。已有排程仍可查看；来源基线完整时，可暂停或延期。</p><p v-else-if="!settings.configured" class="notice">尚未保存复习设置。请先在回忆卡页面设置时区与每日预算，再启用或恢复排程。</p>
      <dl v-if="state.schedule" class="schedule-status"><dt>状态</dt><dd>{{ state.schedule.status === 'ACTIVE' ? '已启用' : `已暂停 · ${pauseLabels[state.schedule.pauseReason!]}` }}</dd><dt>算法建议到期</dt><dd>{{ time(state.schedule.suggestedDueAt) }}</dd><dt>人工延后至</dt><dd>{{ time(state.schedule.deferUntil) }}</dd><dt>实际到期</dt><dd>{{ time(state.schedule.effectiveDueAt) }}</dd><dt>当前排程</dt><dd>修订 {{ state.schedule.revision }} · {{ settings?.zoneId ?? '浏览器本地时区' }}</dd></dl><p v-else class="field-help">尚未启用这张卡的排程。</p>
      <div class="row-actions"><NButton v-if="!state.schedule" :disabled="blocked || !verified || !allowed('ENABLE')" @click="open('ENABLE')">设置首次到期</NButton><template v-else-if="state.schedule.status === 'ACTIVE'"><NButton :disabled="blocked || !verified || !allowed('DEFER')" @click="open('DEFER')">延期</NButton><NButton :disabled="blocked || !verified || !allowed('PAUSE')" @click="open('PAUSE')">暂停排程</NButton></template><NButton v-else :disabled="blocked || !verified || !allowed('RESUME')" @click="open('RESUME')">选择恢复方式</NButton></div>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="writer.busy.value" :loading="writer.busy.value" @click="retry">用原请求核对排程</NButton></p>
      <p v-if="deferred" class="notice" role="status">此前操作已收到有效回执。<NButton text @click="receiveDeferred">接收原操作结果</NButton></p>
      <section v-if="action" class="schedule-form" :aria-label="actionLabels[action]"><h4>{{ actionLabels[action] }}</h4>
        <p v-if="stale || needsReview" class="notice">状态或访问条件有变化，时间与理由保留。<NButton text :disabled="locked" @click="refresh">读取最新状态</NButton><NButton text :disabled="blocked || !verified || !formApplicable" @click="adoptCurrent">采用当前修订，保留输入</NButton></p>
        <label v-if="action === 'RESUME'">恢复方式<NSelect v-model:value="policy" :options="policyOptions" :disabled="blocked" placeholder="明确选择恢复方式" /></label>
        <p v-if="action === 'RESUME' && policy === 'KEEP'" class="field-help">保留原周期和算法状态；仍在未来的建议到期不会提前。</p><p v-if="action === 'RESUME' && policy === 'RESET_AS_NEW'" class="notice">将建立新周期，按新卡初态重新安排；旧周期和历史继续保留。</p>
        <label v-if="action === 'ENABLE' || action === 'RESUME'">生效时间<NSelect v-model:value="timeMode" :options="timeOptions" :disabled="blocked" placeholder="选择立即或指定未来时间" /></label>
        <label v-if="action === 'DEFER' || (action !== 'PAUSE' && timeMode === 'AT')">{{ action === 'DEFER' ? '延后至（UTC）' : '未来时间（UTC）' }}<NInput v-model:value="at" :disabled="blocked" placeholder="YYYY-MM-DDTHH:mm:ssZ" /><span class="field-help">使用 Z 结尾的 UTC 时间，最多 6 位小数。服务器本次读取时间：{{ state.asOf }}。</span></label>
        <p v-if="action === 'DEFER'" class="field-help">新时间须晚于现在及当前实际到期，不改算法建议到期。</p>
        <label v-if="action !== 'ENABLE'">操作理由<NInput v-model:value="reason" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }" :disabled="blocked" placeholder="说明这次调整的原因" /><span class="field-help">{{ Array.from(reason).length }} / 1000 字符</span></label>
        <p v-if="problem" class="field-help">{{ problem }}</p>
        <NCheckbox v-model:checked="confirmed" :disabled="blocked || !verified || stale || needsReview || !!problem">{{ action === 'RESUME' && policy === 'RESET_AS_NEW' ? '我确认将此卡作为新卡重新启用，并采用上面明确选择的时间。' : '我已核对当前排程、操作方式与时间，确认执行本次调整。' }}</NCheckbox>
        <div class="row-actions"><NButton type="primary" :disabled="!canSubmit" :loading="writer.busy.value" @click="submit">确认{{ actionLabels[action] }}</NButton><NButton text :disabled="locked" @click="close">关闭调整</NButton></div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.schedule-editor, .schedule-form { display: grid; gap: 16px; min-width: 0; }.schedule-editor { padding-top: 22px; border-top: 1px solid var(--line); }header { display: grid; gap: 8px; }h3, h4, p { margin: 0; }h3 { font-size: 16px; }h4 { font-size: 14px; }.schedule-status { display: grid; grid-template-columns: minmax(100px, auto) minmax(0, 1fr); gap: 10px 16px; margin: 0; font-size: 13px; }dt { color: var(--muted); }dd { margin: 0; overflow-wrap: anywhere; }.row-actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }.schedule-form { padding: 18px 0; border-top: 1px solid var(--line); }.schedule-form label { display: grid; gap: 8px; font-size: 13px; }.field-help { overflow-wrap: anywhere; }.schedule-form :deep(.n-checkbox) { align-items: start; }.schedule-form :deep(.n-checkbox__label) { white-space: normal; }@media (max-width: 520px) { .schedule-status { grid-template-columns: 1fr; gap: 6px; }dd { margin-bottom: 9px; } }
</style>
