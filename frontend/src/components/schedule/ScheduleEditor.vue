<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, toRaw, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NInputNumber } from 'naive-ui'
import { ApiError, errorText, preview as requestPreview, read } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import type { InterviewEvent, ReschedulePreview, ScheduleChoice, ScheduleInput } from '../../api/schedule'
import { cloneSchedule, emptySchedule, formatScheduleTime, scheduleInput, scheduleProblem, scheduleSourceLabels, scheduleStatusLabels, scheduleTimingLabels } from '../../api/schedule'
import { preparationInterviewEventValid } from '../../api/schedulePreparation'
import { useCommand } from '../../composables/useCommand'
import { useRecentVisit } from '../../composables/useRecentVisit'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import ScheduleHistory from './ScheduleHistory.vue'
import ScheduleMaterialPicker from './ScheduleMaterialPicker.vue'
import ScheduleReadout from './ScheduleReadout.vue'
import ScheduleTimeField from './ScheduleTimeField.vue'
import SchedulePreparationLinks from './SchedulePreparationLinks.vue'
import ScheduleReschedulePlan from './ScheduleReschedulePlan.vue'

const props = defineProps<{ id?: string }>()
const emit = defineEmits<{ close: []; created: [event: InterviewEvent]; changed: [] }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const route = useRoute()
const router = useRouter()
const writer = useCommand()
const initial = emptySchedule()
const current = ref<InterviewEvent>()
const draft = ref<ScheduleInput>(cloneSchedule(initial))
const timeReady = ref(false)
const tab = ref<'edit' | 'preparation' | 'plan' | 'read' | 'history'>('edit')
const preparationVisited = ref(false)
const planVisited = ref(false)
const preparationRefresh = ref(0)
const reason = ref('')
const targetPicker = ref(false)
const materialPicker = ref(false)
const choiceNames = reactive<Record<string, string>>({})
const targetName = ref('')
const rescheduling = ref(false)
const nextTime = ref(cloneSchedule(initial.time))
const nextRemind = ref(initial.remindBeforeMinutes)
const nextReady = ref(false)
const preview = ref<ReschedulePreview>()
const previewKey = ref('')
const previewBusy = ref(false)
const previewError = ref('')
const serverCopy = ref<InterviewEvent>()
const serverError = ref('')
const savedMessage = ref('')
let alive = true
let previewRequest = 0
const creating = computed(() => !props.id)
const formDirty = computed(() => JSON.stringify(draft.value) !== JSON.stringify(current.value ? scheduleInput(current.value) : initial))
const rescheduleDirty = computed(() => rescheduling.value && current.value && JSON.stringify([nextTime.value, nextRemind.value]) !== JSON.stringify([scheduleInput(current.value).time, current.value.remindBeforeMinutes]))
const ownDirty = computed(() => formDirty.value || Boolean(rescheduleDirty.value) || Boolean(reason.value))
const preparationState = computed(() => store.changes.get('schedule-preparation-links'))
const planState = computed(() => store.changes.get('schedule-reschedule-plan'))
const childDirty = computed(() => Boolean(preparationState.value?.dirty || planState.value?.dirty))
const childLocked = computed(() => Boolean(preparationState.value?.locked || planState.value?.locked))
const dirty = computed(() => ownDirty.value || childDirty.value)
const locked = computed(() => writer.locked.value || childLocked.value)
const readable = computed(() => store.sessionActive && !access.checking && !access.challenge && !access.restricted && !store.changes.get('training-access-command')?.locked)
const recordRecentVisit = useRecentVisit('INTERVIEW', () => props.id, readable)
const editable = computed(() => readable.value && !locked.value && !childDirty.value && (creating.value || current.value?.status === 'PLANNED'))
const problem = computed(() => scheduleProblem(draft.value))
const detail = useQuery({ queryKey: computed(() => ['schedule', store.sessionEpoch, 'event', props.id]), enabled: computed(() => readable.value && Boolean(props.id)), queryFn: readCurrent, refetchInterval: 60_000 })
const latest = computed(() => current.value?.revision === detail.data.value?.revision ? detail.data.value : current.value)
const canAcknowledge = computed(() => latest.value?.status === 'PLANNED' && latest.value.timing !== 'UPCOMING' && !latest.value.reminderAcknowledgedAt)
const canComplete = computed(() => latest.value?.status === 'PLANNED' && ['IN_PROGRESS', 'PAST'].includes(latest.value.timing))
const previewBody = computed(() => ({ expectedRevision: current.value?.revision, time: nextTime.value, remindBeforeMinutes: nextRemind.value }))
const requestKey = computed(() => JSON.stringify(previewBody.value))
const activePreview = computed(() => previewKey.value === requestKey.value ? preview.value : undefined)
const changeState = reactive({ dirty: false, locked: false })
// 同一日程路由切换面试时只检查父编辑器的键，子分区的输入和未知写结果必须汇总到这里。
watch([dirty, locked], ([changed, waiting]) => { changeState.dirty = changed; changeState.locked = waiting; store.changes.set('schedule-editor', changeState) }, { immediate: true, flush: 'sync' })
// 轮询可在未决命令期间读到新修订；结构共享会保留对象引用，成功读取与解除保护都需重新接纳。
watch([detail.data, detail.dataUpdatedAt, detail.isSuccess, readable, dirty, locked, () => store.sessionEpoch], () => {
  const value = detail.data.value
  const cached = queryClient.getQueryData<InterviewEvent>(['schedule', store.sessionEpoch, 'event', props.id])
  if (!alive || !readable.value || dirty.value || locked.value || !detail.isSuccess.value || !value || value.id !== props.id
    || !cached || toRaw(cached) !== toRaw(value) || current.value && value.revision < current.value.revision) return
  if (current.value !== value) accept(value)
  recordRecentVisit(value.id)
}, { immediate: true })
watch(tab, value => { if (value === 'preparation') preparationVisited.value = true; if (value === 'plan') planVisited.value = true })
watch(() => route.query.version, value => { if (typeof value === 'string' && props.id) tab.value = 'history' }, { immediate: true })
watch(() => store.sessionEpoch, () => { preview.value = undefined; previewKey.value = ''; previewRequest++ })
onUnmounted(() => { alive = false; previewRequest++; if (store.changes.get('schedule-editor') === changeState) store.changes.delete('schedule-editor') })

function accept(value: InterviewEvent) {
  if (draft.value.targetId !== value.targetId) targetName.value = ''
  current.value = value
  draft.value = scheduleInput(value)
  nextTime.value = scheduleInput(value).time
  nextRemind.value = value.remindBeforeMinutes
}
async function readCurrent() {
  const value = await read<unknown>(`/schedule/interviews/${props.id}`)
  if (!preparationInterviewEventValid(value, props.id)) throw new ApiError(0, 'SCHEDULE_RESPONSE_INVALID', '面试安排回执无法核对，请重新读取。')
  return value
}
function refresh() { preparationRefresh.value++; void queryClient.invalidateQueries({ queryKey: ['schedule'] }); emit('changed') }
function update(value: InterviewEvent, message: string) {
  const key = ['schedule', store.sessionEpoch, 'event', value.id]
  const cached = queryClient.getQueryData<InterviewEvent>(key)
  const newest = cached && cached.revision >= value.revision ? cached : value
  accept(current.value && current.value.revision > newest.revision ? current.value : newest)
  preview.value = undefined
  previewKey.value = ''
  rescheduling.value = false
  serverCopy.value = undefined
  savedMessage.value = message
  queryClient.setQueryData<InterviewEvent>(key, previous => previous && previous.revision >= value.revision ? previous : value)
  refresh()
}
function holdsPendingRequest(value: typeof tab.value) {
  // 同一面试的历史导航不会卸载子组件，必须始终允许回到保留原请求的分区。
  return value === 'edit' ? writer.locked.value : value === 'preparation' ? Boolean(preparationState.value?.locked)
    : value === 'plan' && Boolean(planState.value?.locked)
}
function save() {
  if (!editable.value || problem.value || rescheduling.value || (creating.value && !timeReady.value)) return
  if (creating.value) {
    void writer.run<InterviewEvent>('/schedule/interviews', 'POST', cloneSchedule(draft.value), value => {
      draft.value = cloneSchedule(initial)
      changeState.dirty = false
      changeState.locked = false
      refresh()
      emit('created', value)
    })
  } else if (current.value) {
    void writer.run<InterviewEvent>(`/schedule/interviews/${props.id}`, 'PUT', { expectedRevision: current.value.revision, ...cloneSchedule(draft.value) }, value => update(value, '安排内容已保存，时间保持原样'))
  }
}
function choose(value: ScheduleChoice) {
  if (!editable.value) return
  if (value.kind === 'TARGET') { draft.value.targetId = value.itemId; targetName.value = value.title; targetPicker.value = false; return }
  if (draft.value.materials.length >= 50 || draft.value.materials.some(item => item.versionId === value.versionId)) return
  choiceNames[value.versionId] = `${value.title} · v${value.number}`
  draft.value.materials.push({ kind: value.kind, itemId: value.itemId, versionId: value.versionId })
}
function materialName(versionId: string) {
  const value = latest.value?.materialReferences.find(item => item.versionId === versionId)
  return value ? `${value.title} · v${value.number}` : choiceNames[versionId] ?? '已选择的材料版本'
}
function materialState(versionId: string) { return latest.value?.materialReferences.find(item => item.versionId === versionId)?.state }
function startReschedule() {
  if (!current.value || !editable.value || formDirty.value) return
  nextTime.value = scheduleInput(current.value).time
  nextRemind.value = current.value.remindBeforeMinutes
  preview.value = undefined
  previewKey.value = ''
  previewError.value = ''
  rescheduling.value = true
}
function closeReschedule() {
  if (writer.locked.value || (rescheduleDirty.value && !window.confirm('放弃尚未提交的改期时间？'))) return
  rescheduling.value = false
  previewRequest++
  preview.value = undefined
  previewKey.value = ''
}
async function previewReschedule() {
  if (!current.value || !editable.value || !nextReady.value || previewBusy.value || !Number.isInteger(nextRemind.value) || nextRemind.value < 0 || nextRemind.value > 10080) return
  const body = cloneSchedule(previewBody.value)
  const key = requestKey.value
  const epoch = store.sessionEpoch
  const request = ++previewRequest
  previewBusy.value = true
  previewError.value = ''
  preview.value = undefined
  try {
    const value = await requestPreview<ReschedulePreview>(`/schedule/interviews/${props.id}/reschedule-preview`, body)
    if (!alive || !editable.value || !rescheduling.value || epoch !== store.sessionEpoch || request !== previewRequest || key !== requestKey.value) return
    preview.value = value
    previewKey.value = key
  } catch (error) { if (alive && epoch === store.sessionEpoch && request === previewRequest) previewError.value = errorText(error) }
  finally { if (alive) previewBusy.value = false }
}
function reschedule() {
  if (!editable.value || !current.value || !activePreview.value || !nextReady.value || !reason.value.trim() || reason.value.length > 2000 || formDirty.value) return
  void writer.run<InterviewEvent>(`/schedule/interviews/${props.id}/reschedule`, 'POST', { ...cloneSchedule(previewBody.value), reason: reason.value }, value => { reason.value = ''; update(value, '改期已保存，旧时间留在历史中，提醒按新时间重新计算') })
}
function decide(action: 'cancel' | 'complete' | 'acknowledge') {
  if (!current.value || !editable.value || formDirty.value || rescheduling.value) return
  if ((action === 'acknowledge' && !canAcknowledge.value) || (action === 'complete' && !canComplete.value)) return
  if (action === 'cancel' && !reason.value.trim()) return
  if (reason.value.length > 2000) return
  if (action !== 'acknowledge' && !window.confirm(action === 'cancel' ? '确认取消本次面试安排？原安排与取消原因会保留在历史中。' : '确认本次面试已结束，标记为完成？')) return
  const body = { expectedRevision: current.value.revision, ...(action === 'acknowledge' ? {} : { reason: reason.value }) }
  void writer.run<InterviewEvent>(`/schedule/interviews/${props.id}/${action}`, 'POST', body, value => {
    if (action !== 'acknowledge') reason.value = ''
    update(value, action === 'acknowledge' ? '已确认看过提醒，面试仍保持待进行' : action === 'cancel' ? '已取消安排，历史保留' : '已标记完成，原安排与处理记录保留')
  })
}
async function compareServer() {
  if (!props.id || !readable.value) return
  const epoch = store.sessionEpoch
  serverError.value = ''
  try { const value = await readCurrent(); if (alive && readable.value && store.sessionEpoch === epoch) serverCopy.value = value }
  catch (error) { if (alive && readable.value && store.sessionEpoch === epoch) serverError.value = errorText(error) }
}
function adoptServer() {
  if (!serverCopy.value || locked.value || !readable.value || !window.confirm('采用服务器安排并放弃编辑分区的未保存输入？准备事项与联动分区的输入继续保留，需重新核对。')) return
  accept(serverCopy.value)
  reason.value = ''
  rescheduling.value = false
  preview.value = undefined
  previewKey.value = ''
  serverCopy.value = undefined
  writer.error.value = undefined
  preparationRefresh.value++
  savedMessage.value = '已采用服务器最新安排'
}
function recordRecollection() {
  if (!current.value || tab.value === 'history' || !readable.value || locked.value || dirty.value) return
  // 固定用户正在阅读的安排修订；后续改期不改写回忆来源。
  void router.push({ name: 'recollections', query: { create: '1', eventId: current.value.id, eventRevision: String(current.value.revision) } })
}
</script>

<template>
  <section class="schedule-editor" aria-label="面试安排详情">
    <p v-if="!creating && !current && detail.isPending.value" class="loading-block" role="status">正在读取安排…</p>
    <div v-else-if="!creating && !current" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text @click="detail.refetch()">重试读取</NButton></div>
    <template v-else>
      <header class="detail-header"><div><p class="section-label">{{ creating ? '新建私人安排' : `安排 · 修订 ${current?.revision}` }}</p><h2>{{ current?.title ?? '留出准备与面试的时间' }}</h2></div><button class="icon-button" aria-label="关闭安排详情" @click="emit('close')">×</button></header>
      <div class="save-state" aria-live="polite"><span :class="{ unsaved: dirty }">{{ locked ? '正在保存或等待确认结果…' : dirty ? '有未保存输入' : creating ? '填写后创建安排' : '已读取服务器安排' }}</span><span v-if="current" class="status-pill">{{ scheduleStatusLabels[current.status] }}</span></div>
      <div v-if="childLocked" class="notice" role="status"><p>准备分区有请求正在保存或结果尚未确认，可以返回对应分区查看进度并沿原请求恢复。</p><div class="row-actions"><NButton v-if="preparationState?.locked" size="small" @click="tab = 'preparation'">返回准备事项请求</NButton><NButton v-if="planState?.locked" size="small" @click="tab = 'plan'">返回联动改期请求</NButton></div></div>
      <div v-if="current && tab !== 'history'" class="row-actions"><NButton size="small" :disabled="!readable || locked || dirty" @click="recordRecollection">记录面后回忆</NButton><span class="field-help">关联当前已保存的安排修订 {{ current.revision }}；每条记录保存一个问题。</span></div>
      <p v-if="latest?.timing === 'PAST'" class="notice">{{ scheduleTimingLabels.PAST }}。可以标记完成、改期或取消，不会自动认定缺席。</p>
      <p v-if="current && detail.error.value" class="notice error" role="alert">最新读取未完成，当前输入保留：{{ errorText(detail.error.value) }}<NButton text @click="detail.refetch()">重试</NButton></p>
      <p v-if="current && detail.data.value && detail.data.value.revision !== current.revision" class="notice">服务器安排已更新，本地输入仍使用原修订。<NButton text @click="compareServer">查看服务器副本</NButton></p>
      <div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" size="small" :disabled="!readable" @click="writer.retry">用原请求重试</NButton><NButton v-if="current && writer.error.value instanceof ApiError && writer.error.value.status === 409" size="small" :disabled="locked || !readable" @click="compareServer">查看服务器副本</NButton></div>
      <p v-if="savedMessage && !dirty" class="success-text" role="status">{{ savedMessage }}</p>
      <p v-if="serverError" class="notice error">{{ serverError }}</p>
      <section v-if="serverCopy" class="server-comparison"><h3>服务器副本 · 修订 {{ serverCopy.revision }}</h3><ScheduleReadout :event="serverCopy" /><div class="row-actions"><NButton size="small" :disabled="locked || !readable" @click="adoptServer">采用服务器副本</NButton><NButton size="small" @click="serverCopy = undefined">保留本地输入</NButton></div></section>
      <nav v-if="current" class="detail-tabs" aria-label="安排详情视图"><button v-for="option in ([['edit', '编辑与处理'], ['preparation', '准备事项'], ['plan', '联动改期'], ['read', '已保存安排'], ['history', '变更历史']] as const)" :key="option[0]" :disabled="locked && !holdsPendingRequest(option[0])" :class="{ active: tab === option[0] }" :aria-current="tab === option[0] ? 'page' : undefined" @click="tab = option[0]">{{ option[1] }}</button></nav>
      <div v-show="tab === 'edit' || creating" class="schedule-form">
        <p v-if="childDirty || childLocked" class="notice">准备事项或联动改期分区有尚未完成的操作，请先处理该分区后再编辑安排正文。</p>
        <p v-if="current && current.status !== 'PLANNED'" class="field-help">本安排已处理，正文与时间保持只读；可以从变更历史查看原记录。</p>
        <label>安排标题<NInput v-model:value="draft.title" :disabled="!editable" :maxlength="200" placeholder="例如 Java 后端一面" /></label>
        <div class="form-pair"><label>轮次<NInput v-model:value="draft.round" :disabled="!editable" :maxlength="100" placeholder="例如 技术一面" /></label><label>提前提醒分钟数<NInputNumber :value="draft.remindBeforeMinutes" :disabled="!editable || rescheduling" :min="0" :max="10080" :precision="0" @update:value="value => draft.remindBeforeMinutes = value ?? 0" /></label></div>
        <ScheduleTimeField v-if="creating" v-model="draft.time" :disabled="!editable" @ready="timeReady = $event" />
        <section v-else-if="current" class="saved-time"><h3>当前面试时间</h3><p>{{ current.time.localStart.replace('T', ' ') }} · {{ current.time.zoneId }} · {{ current.time.durationMinutes }} 分钟</p><NButton v-if="!rescheduling" size="small" :disabled="!editable || formDirty" @click="startReschedule">预览并改期</NButton><p class="field-help">修改时间请使用改期；如有正文编辑，请先保存。</p></section>
        <section v-if="rescheduling && current" class="reschedule-box"><h3>预览改期</h3><ScheduleTimeField v-model="nextTime" :disabled="!editable" @ready="nextReady = $event" /><label>新提醒提前分钟数<NInputNumber :value="nextRemind" :disabled="!editable" :min="0" :max="10080" :precision="0" @update:value="value => nextRemind = value ?? 0" /></label><div class="row-actions"><NButton size="small" :loading="previewBusy" :disabled="!editable || !nextReady" @click="previewReschedule">生成改期预览</NButton><NButton size="small" :disabled="writer.locked.value" @click="closeReschedule">收起改期</NButton></div><p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p><div v-if="activePreview" class="notice"><p>原时间：{{ activePreview.from.localStart.replace('T', ' ') }} · {{ activePreview.from.zoneId }}</p><p>新时间：{{ activePreview.to.localStart.replace('T', ' ') }} · {{ activePreview.to.zoneId }}</p><p>新提醒：{{ formatScheduleTime(activePreview.remindAt, activePreview.to.zoneId) }}</p><p class="field-help">只更新本次安排与提醒，旧时间继续保留在历史中。</p></div><label>改期原因<NInput v-model:value="reason" type="textarea" :disabled="!editable" :maxlength="2000" :autosize="{ minRows: 2, maxRows: 5 }" /></label><NButton type="primary" :loading="writer.busy.value" :disabled="!editable || !activePreview || !nextReady || !reason.trim() || formDirty" @click="reschedule">确认按预览改期</NButton></section>
        <section class="form-section"><div class="section-actions"><h3>关联目标</h3><NButton size="small" :disabled="!editable" @click="targetPicker = !targetPicker">{{ targetPicker ? '收起选择' : '选择目标' }}</NButton></div><div v-if="draft.targetId" class="chosen-row"><span>{{ targetName || '已关联目标' }}<span v-if="draft.targetId === current?.targetId && latest?.targetState" class="status-pill">{{ scheduleSourceLabels[latest.targetState] }}</span></span><div class="row-actions"><NButton v-if="draft.targetId === current?.targetId" text size="small" @click="router.push({ name: 'prepare', query: { kind: 'TARGET', item: draft.targetId, version: current?.targetVersionId ?? undefined } })">查看版本</NButton><NButton text size="small" :disabled="!editable" @click="draft.targetId = null; targetName = ''">取消关联</NButton></div></div><p v-else class="field-help">可不关联岗位目标，单独保存安排。</p><ScheduleMaterialPicker v-if="targetPicker" mode="target" :disabled="!editable" @choose="choose" /></section>
        <label>地点<NInput v-model:value="draft.location" :disabled="!editable" :maxlength="500" /></label><label>会议链接<NInput v-model:value="draft.meetingUrl" :disabled="!editable" :maxlength="2000" placeholder="可选 HTTP(S) 地址" /></label><label>私人备注<NInput v-model:value="draft.notes" type="textarea" :disabled="!editable" :maxlength="10000" :autosize="{ minRows: 4, maxRows: 12 }" placeholder="准备重点、联系人备注或需要提前确认的事项" /></label>
        <section class="form-section"><div class="section-actions"><h3>准备材料</h3><NButton size="small" :disabled="!editable || draft.materials.length >= 50" @click="materialPicker = !materialPicker">{{ materialPicker ? '收起选择' : '关联材料版本' }}</NButton></div><div v-for="(material, index) in draft.materials" :key="material.versionId" class="chosen-row"><span>{{ materialName(material.versionId) }}<small v-if="materialState(material.versionId)">{{ scheduleSourceLabels[materialState(material.versionId)!] }}</small></span><NButton text size="small" :disabled="!editable" @click="draft.materials.splice(index, 1)">移出</NButton></div><ScheduleMaterialPicker v-if="materialPicker" mode="materials" :disabled="!editable || draft.materials.length >= 50" :excluded-version-ids="draft.materials.map(value => value.versionId)" @choose="choose" /></section>
        <p v-if="problem" class="field-help" role="status">{{ problem }}</p><div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!editable || !!problem || rescheduling || (creating ? !timeReady : !formDirty)" @click="save">{{ creating ? '创建安排' : '保存安排内容' }}</NButton></div>
        <section v-if="current && current.status === 'PLANNED' && !rescheduling" class="form-section"><h3>处理安排</h3><p class="field-help">先保存正文。提醒到期后可确认看过，计划开始后可标记完成；完成或取消会保留独立历史。</p><label>处理备注／取消原因<NInput v-model:value="reason" type="textarea" :disabled="!editable" :maxlength="2000" :autosize="{ minRows: 2, maxRows: 5 }" placeholder="取消时必须填写原因" /></label><div class="row-actions"><NButton size="small" :disabled="!editable || formDirty || !canAcknowledge" @click="decide('acknowledge')">已看过提醒</NButton><NButton size="small" :disabled="!editable || formDirty || !canComplete" @click="decide('complete')">标记完成</NButton><NButton size="small" quaternary :disabled="!editable || formDirty || !reason.trim()" @click="decide('cancel')">取消安排</NButton><NButton v-if="reason" text size="small" :disabled="writer.locked.value" @click="reason = ''">清空未保存备注</NButton></div></section>
      </div>
      <SchedulePreparationLinks v-if="current && preparationVisited" v-show="tab === 'preparation'" :event="current" :active="tab === 'preparation'" :disabled="!readable || writer.locked.value || ownDirty || !!planState?.dirty || !!planState?.locked" :refresh-token="preparationRefresh" @changed="refresh" />
      <ScheduleReschedulePlan v-if="current && planVisited" v-show="tab === 'plan'" :event="current" :active="tab === 'plan'" :disabled="!readable || writer.locked.value || ownDirty || !!preparationState?.dirty || !!preparationState?.locked" :refresh-token="preparationRefresh" @changed="refresh" />
      <ScheduleReadout v-if="tab === 'read' && current" :event="latest ?? current" />
      <ScheduleHistory v-if="tab === 'history' && current" :id="current.id" :current-revision="current.revision" />
    </template>
  </section>
</template>

<style scoped>
.schedule-editor { min-width: 0; padding: 24px; }
.schedule-editor > .notice, .schedule-editor > .success-text { margin: 12px 0; }
.schedule-form { display: grid; gap: 16px; }
.schedule-form label { display: grid; gap: 7px; font-size: 12px; color: var(--muted); min-width: 0; }
.form-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.schedule-form h3, .server-comparison > h3 { font-size: 13px; }
.form-section, .saved-time { display: grid; gap: 11px; padding-top: 17px; border-top: 1px solid var(--line); }
.saved-time > p { font-size: 13px; }
.saved-time > button { justify-self: start; }
.section-actions, .chosen-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.chosen-row { font-size: 12px; }
.chosen-row > span { min-width: 0; overflow-wrap: anywhere; }
.chosen-row small { display: block; color: var(--muted); margin-top: 4px; }
.chosen-row .status-pill { margin-left: 9px; }
.reschedule-box { display: grid; gap: 13px; padding: 16px; border: 1px solid var(--brand); border-radius: 7px; background: var(--surface-soft); }
.reschedule-box > button { justify-self: start; }
.server-comparison { display: grid; gap: 15px; padding: 16px; margin: 16px 0; border: 1px solid var(--line); border-radius: 6px; }
@media (max-width: 740px) { .schedule-editor { padding: 20px 15px; } .form-pair { grid-template-columns: 1fr; } .section-actions, .chosen-row { align-items: start; } }
</style>
