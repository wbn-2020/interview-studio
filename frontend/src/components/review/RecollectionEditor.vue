<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import { questionTypeOptions } from '../../api/bank'
import type { Recollection, RecollectionContent, RecollectionInterviewSnapshot } from '../../api/recollection'
import { recollectionBaselineLabels, recollectionContentProblem, recollectionEmptyContent, recollectionInterviewValid, recollectionStatusLabels, recollectionValid } from '../../api/recollection'
import { reviewIdValid } from '../../api/review'
import type { EventPage, InterviewEvent, ScheduleHistoryPage } from '../../api/schedule'
import { browserZone, dateKey, scheduleStatusLabels } from '../../api/schedule'
import { useCommand } from '../../composables/useCommand'
import { useRecentVisit } from '../../composables/useRecentVisit'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import RecollectionHistory from './RecollectionHistory.vue'
const RecollectionQuestionAdoption = defineAsyncComponent(() => import('./RecollectionQuestionAdoption.vue'))
const RecollectionActionCreate = defineAsyncComponent(() => import('./RecollectionActionCreate.vue'))

const props = defineProps<{ id?: string; eventId?: string; eventRevision?: number; disabled?: boolean }>()
const emit = defineEmits<{ saved: [value: Recollection]; close: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const route = useRoute(), router = useRouter(), writer = useCommand()
const current = shallowRef<Recollection>(), latest = shallowRef<Recollection>(), serverCopy = shallowRef<Recollection>()
const content = ref<RecollectionContent>(recollectionEmptyContent())
const interview = shallowRef<RecollectionInterviewSnapshot | null>(null)
const baseline = ref(signature(content.value, interview.value))
const kindChosen = ref(false), needsReview = ref(false), serverLoading = ref(false), created = ref(false)
const savedMessage = ref(''), serverError = ref('')
const tab = ref<'edit' | 'history' | 'question' | 'action'>('edit')
const deferred = shallowRef<() => void>()
const initialLinkRequired = ref(!props.id && (props.eventId !== undefined || props.eventRevision !== undefined))
const interviewBusy = ref(false), interviewError = ref(''), pickerOpen = ref(false)
const scheduleMonthInput = ref(dateKey(Date.now(), browserZone()).slice(0, 7)), scheduleMonth = ref(scheduleMonthInput.value)
const scheduleCursor = ref<string>(), scheduleCursors = ref<(string | undefined)[]>([])
const chosenEvent = shallowRef<InterviewEvent>(), historyBefore = ref<number>(), historyCursors = ref<(number | undefined)[]>([])
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.restricted && !access.challenge)
const recordRecentVisit = useRecentVisit('RECOLLECTION', () => props.id, readable)
const creating = computed(() => !props.id)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const dirty = computed(() => signature(content.value, interview.value) !== baseline.value || (creating.value && kindChosen.value))
const stale = computed(() => Boolean(current.value && latest.value && latest.value.revision > current.value.revision))
const otherChanges = computed(() => [...store.changes].some(([key, value]) => key.startsWith('recollection-') && !['recollection-editor', 'recollection-command'].includes(key) && (value.dirty || value.locked)))
const editable = computed(() => readable.value && !locked.value && !serverLoading.value && !created.value && !otherChanges.value && (creating.value || current.value?.status === 'ACTIVE'))
const problem = computed(() => !kindChosen.value ? '请明确选择有回答回忆片段，或没有旧回答。' : recollectionContentProblem(content.value))
const detailKey = computed(() => ['recollections', store.sessionEpoch, 'item', props.id])
const historyVersion = computed(() => typeof route.query.versionId === 'string' ? route.query.versionId : undefined)
const baselineOptions = Object.entries(recollectionBaselineLabels).map(([value, label]) => ({ value, label }))
const changeState = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
let alive = true, readGeneration = 0, compareGeneration = 0, interviewGeneration = 0, commandEpoch = store.sessionEpoch

const detail = useQuery({
  queryKey: detailKey,
  enabled: computed(() => readable.value && Boolean(props.id)),
  queryFn: async () => {
    const epoch = store.sessionEpoch, generation = ++readGeneration, id = props.id
    const value = await read<Recollection>(`/review/recollections/${id}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || generation !== readGeneration || id !== props.id) throw new ApiError(0, 'RECOLLECTION_CONTEXT_CHANGED', '读取条件已变化，本地输入继续保留。')
    if (!recollectionValid(value, id)) throw new ApiError(502, 'RECOLLECTION_INVALID', '回忆记录与当前页面不一致，请重新读取。')
    return value
  },
})
const scheduleRange = computed(() => {
  const [year, month] = scheduleMonth.value.split('-').map(Number)
  return { from: new Date(Date.UTC(year!, month! - 1, -1)).toISOString(), to: new Date(Date.UTC(year!, month!, 3)).toISOString() }
})
const events = useQuery({
  queryKey: computed(() => ['schedule', store.sessionEpoch, 'recollection-picker', scheduleMonth.value, scheduleCursor.value]),
  enabled: computed(() => readable.value && pickerOpen.value),
  queryFn: async () => {
    const epoch = store.sessionEpoch, generation = interviewGeneration, month = scheduleMonth.value, cursor = scheduleCursor.value
    const parameters = new URLSearchParams({ ...scheduleRange.value, limit: '20' })
    if (cursor) parameters.set('cursor', cursor)
    const value = await read<EventPage>(`/schedule/interviews?${parameters}`)
    if (!alive || !readable.value || !pickerOpen.value || epoch !== store.sessionEpoch || generation !== interviewGeneration || month !== scheduleMonth.value || cursor !== scheduleCursor.value) throw new ApiError(0, 'RECOLLECTION_CONTEXT_CHANGED', '面试选择条件已变化，请重新读取。')
    if (!value || !Array.isArray(value.items) || value.items.some(event => !eventSnapshot(event)) || (value.nextCursor !== null && !reviewIdValid(value.nextCursor))) throw new ApiError(502, 'RECOLLECTION_INTERVIEW_INVALID', '面试列表格式异常，请重新读取。')
    return value
  },
})
const eventHistory = useQuery({
  queryKey: computed(() => ['schedule', store.sessionEpoch, 'history', chosenEvent.value?.id, historyBefore.value]),
  enabled: computed(() => readable.value && pickerOpen.value && Boolean(chosenEvent.value)),
  queryFn: async () => {
    const epoch = store.sessionEpoch, generation = interviewGeneration, id = chosenEvent.value?.id, before = historyBefore.value
    const value = await read<ScheduleHistoryPage>(`/schedule/interviews/${id}/history?limit=20${before === undefined ? '' : `&before=${before}`}`)
    if (!alive || !readable.value || !pickerOpen.value || epoch !== store.sessionEpoch || generation !== interviewGeneration || id !== chosenEvent.value?.id || before !== historyBefore.value) throw new ApiError(0, 'RECOLLECTION_CONTEXT_CHANGED', '面试历史选择已变化，请重新读取。')
    if (!value || !Array.isArray(value.items) || value.items.some(item => !Number.isSafeInteger(item.revision) || item.revision < 0)) throw new ApiError(502, 'RECOLLECTION_INTERVIEW_INVALID', '面试历史格式异常，请重新读取。')
    return value
  },
})
watch([detail.data, detail.dataUpdatedAt, readable, () => store.sessionEpoch, detailKey], () => {
  const value = queryClient.getQueryData<Recollection>(detailKey.value)
  if (!value || !readable.value || !recordLatest(value)) return
  // 后台刷新只能推进干净正文；不能把本地改写悄悄绑定到服务器的新修订。
  if (!current.value || (!dirty.value && !locked.value && !otherChanges.value && !needsReview.value)) {
    accept(value)
    recordRecentVisit(value.id)
  }
}, { immediate: true })
watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed; commandState.locked = waiting
  store.changes.set('recollection-editor', changeState); store.changes.set('recollection-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => {
  readGeneration++; compareGeneration++; interviewGeneration++
  serverCopy.value = undefined; serverLoading.value = false; serverError.value = ''
  interviewBusy.value = false; chosenEvent.value = undefined; pickerOpen.value = false
  if (current.value) needsReview.value = true
}, { flush: 'sync' })
watch(readable, value => { if (value && initialLinkRequired.value) void loadInitialInterview() }, { immediate: true })
watch(writer.error, value => { if (value instanceof ApiError && value.status === 409) needsReview.value = Boolean(current.value) }, { flush: 'sync' })
watch(historyVersion, value => { if (value && props.id) tab.value = 'history' }, { immediate: true })
onUnmounted(() => {
  alive = false; readGeneration++; compareGeneration++; interviewGeneration++
  if (store.changes.get('recollection-editor') === changeState) store.changes.delete('recollection-editor')
  if (store.changes.get('recollection-command') === commandState) store.changes.delete('recollection-command')
})

function clone(value: RecollectionContent): RecollectionContent { return { ...value } }
function link(value: RecollectionInterviewSnapshot | null) { return value ? { eventId: value.eventId, revision: value.revision } : null }
function signature(value: RecollectionContent, associated: RecollectionInterviewSnapshot | null) { return JSON.stringify([value.title, value.question, value.type, value.conditions, value.baselineKind, value.answerFragment, value.feedback, value.uncertainty, value.gap, associated ? [associated.eventId, associated.revision, associated.title, associated.round, associated.startAt, associated.zoneId, associated.status, associated.targetId, associated.targetVersionId] : null]) }
function recordLatest(value: Recollection): boolean {
  if (!recollectionValid(value, props.id) || (latest.value && value.revision < latest.value.revision) || (current.value && value.revision < current.value.revision)) return false
  if ([latest.value, current.value].some(previous => previous?.revision === value.revision && (previous.status !== value.status
    || previous.currentVersion.id !== value.currentVersion.id || previous.currentVersion.number !== value.currentVersion.number
    || previous.currentVersion.createdAt !== value.currentVersion.createdAt || previous.createdAt !== value.createdAt || previous.updatedAt !== value.updatedAt
    || signature(previous.currentVersion.content, previous.currentVersion.interview) !== signature(value.currentVersion.content, value.currentVersion.interview)))) return false
  latest.value = value; return true
}
function accept(value: Recollection, keepDraft = false) {
  if (!recordLatest(value)) return
  current.value = value
  baseline.value = signature(value.currentVersion.content, value.currentVersion.interview)
  if (!keepDraft) { content.value = clone(value.currentVersion.content); interview.value = value.currentVersion.interview }
  kindChosen.value = true; needsReview.value = false
}
function refresh() { void queryClient.invalidateQueries({ queryKey: ['recollections'] }) }
function receive(value: Recollection, message: string, keepDraft = false) {
  if (!recordLatest(value)) { serverCopy.value = latest.value; needsReview.value = true; savedMessage.value = `本次操作已确认（修订 ${value.revision}），之后还有更新。输入继续保留，请核对最新记录。` }
  else { accept(value, keepDraft); serverCopy.value = undefined; savedMessage.value = message; queryClient.setQueryData(['recollections', store.sessionEpoch, 'item', value.id], value) }
  refresh(); emit('saved', value)
}
function run(path: string, method: string, body: unknown, matches: (value: Recollection) => boolean, apply: (value: Recollection) => void) {
  if (!readable.value || locked.value || serverLoading.value || interviewBusy.value || otherChanges.value) return
  readGeneration++; savedMessage.value = ''; commandEpoch = store.sessionEpoch
  let original: typeof writer.pending.value
  const request = writer.run<Recollection>(path, method, body, value => {
    if (!alive) return
    if (!recollectionValid(value, props.id) || !matches(value)) {
      // 格式异常的成功响应也不能视为未写入，保留原命令与原键才能安全恢复。
      writer.pending.value = original
      writer.error.value = new ApiError(502, 'RECOLLECTION_RECEIPT_INVALID', '保存回执与请求不一致。输入及原请求继续保留，请用原请求重试，避免重复创建。')
      return
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = () => apply(value)
    else apply(value)
  })
  original = writer.pending.value
  void request
}
function save() {
  if (!editable.value || problem.value || needsReview.value || stale.value || initialLinkRequired.value || interviewBusy.value || (!creating.value && !dirty.value)) return
  const fixed = clone(content.value), associated = interview.value, expected = signature(fixed, associated)
  if (creating.value) run('/review/recollections', 'POST', { interview: link(associated), content: fixed },
    value => value.revision === 0 && value.status === 'ACTIVE' && value.currentVersion.number === 1 && signature(value.currentVersion.content, value.currentVersion.interview) === expected,
    value => { created.value = true; kindChosen.value = false; baseline.value = expected; savedMessage.value = '回忆已保存。'; refresh(); emit('saved', value) })
  else if (current.value) {
    const previous = current.value
    run(`/review/recollections/${previous.id}`, 'PUT', { expectedRevision: previous.revision, interview: link(associated), content: fixed },
      value => value.revision === previous.revision + 1 && value.status === 'ACTIVE' && value.currentVersion.number === previous.currentVersion.number + 1 && value.currentVersion.id !== previous.currentVersion.id && signature(value.currentVersion.content, value.currentVersion.interview) === expected,
      value => receive(value, `已保存为 v${value.currentVersion.number}，原版本继续保留。`))
  }
}
function changeStatus() {
  if (!readable.value || locked.value || !current.value || stale.value || needsReview.value || serverLoading.value || interviewBusy.value || otherChanges.value) return
  const previous = current.value, restoring = previous.status === 'ARCHIVED'
  if (!restoring && dirty.value) return
  if (!window.confirm(restoring ? '恢复这条回忆？原版本和题目来源继续保留。' : '归档这条回忆？历史与已整理的题目来源继续保留，归档后需先恢复才能补记。')) return
  run(`/review/recollections/${previous.id}/${restoring ? 'restore' : 'archive'}`, 'POST', { expectedRevision: previous.revision },
    value => value.revision === previous.revision + 1 && value.status === (restoring ? 'ACTIVE' : 'ARCHIVED') && value.currentVersion.id === previous.currentVersion.id && value.currentVersion.number === previous.currentVersion.number && value.currentVersion.createdAt === previous.currentVersion.createdAt && signature(value.currentVersion.content, value.currentVersion.interview) === signature(previous.currentVersion.content, previous.currentVersion.interview),
    value => receive(value, restoring ? '回忆已恢复。' : '回忆已归档。', restoring && dirty.value))
}
async function compareServer() {
  if (!readable.value || locked.value || !props.id || serverLoading.value || otherChanges.value) return
  const epoch = store.sessionEpoch, request = ++compareGeneration
  readGeneration++; serverLoading.value = true; serverError.value = ''
  try {
    const value = await read<Recollection>(`/review/recollections/${props.id}`)
    if (!alive || !readable.value || locked.value || epoch !== store.sessionEpoch || request !== compareGeneration) return
    if (!recordLatest(value)) { serverError.value = '返回内容异常或服务器已有更新，请重新读取。'; return }
    serverCopy.value = value
    if (value.revision === current.value?.revision) needsReview.value = false
  } catch (error) { if (alive && request === compareGeneration && epoch === store.sessionEpoch) serverError.value = errorText(error) }
  finally { if (alive && request === compareGeneration) serverLoading.value = false }
}
function useServer(keepDraft: boolean) {
  if (!readable.value || locked.value || serverLoading.value || !serverCopy.value || otherChanges.value) return
  const value = serverCopy.value
  if (latest.value && value.revision < latest.value.revision) { serverError.value = '这份比较副本之后又有更新，请重新读取。'; return }
  if (!window.confirm(keepDraft ? '已对照服务器版本，确认保留本地改写，并使用这份最新修订继续编辑？再次保存将写入完整本地内容。' : '采用服务器版本并放弃本地未保存的修改？')) return
  accept(value, keepDraft); serverCopy.value = undefined; writer.error.value = undefined
  savedMessage.value = keepDraft ? '已核对修订，本地改写继续保留，尚未保存。' : '已采用服务器内容。'
}
function chooseKind(value: RecollectionContent['baselineKind']) {
  if (!editable.value) return
  if (value === 'NONE' && content.value.answerFragment && !window.confirm('选择“没有旧回答”需要清空本地回答片段。确认清空？已保存的历史版本不受影响。')) return
  if (value === 'NONE') content.value.answerFragment = ''
  content.value.baselineKind = value; kindChosen.value = true
}
function eventSnapshot(event: InterviewEvent): RecollectionInterviewSnapshot | undefined {
  try {
    const value = { eventId: event.id, revision: event.revision, title: event.title, round: event.round, startAt: event.time.startAt, zoneId: event.time.zoneId, status: event.status, targetId: event.targetId, targetVersionId: event.targetVersionId }
    return recollectionInterviewValid(value) ? value : undefined
  } catch { return undefined }
}
async function chooseInterview(eventId: string, revision: number) {
  if (!editable.value || interviewBusy.value || !reviewIdValid(eventId) || !Number.isSafeInteger(revision) || revision < 0) return
  const epoch = store.sessionEpoch, request = ++interviewGeneration
  interviewBusy.value = true; interviewError.value = ''
  try {
    const event = await read<InterviewEvent>(`/schedule/interviews/${eventId}/history/${revision}`)
    if (!alive || !readable.value || locked.value || epoch !== store.sessionEpoch || request !== interviewGeneration) return
    const snapshot = eventSnapshot(event)
    if (!snapshot || snapshot.eventId !== eventId || snapshot.revision !== revision) throw new ApiError(502, 'RECOLLECTION_INTERVIEW_INVALID', '返回的安排历史与选择不一致，请重新读取。')
    interview.value = snapshot; initialLinkRequired.value = false; pickerOpen.value = false; chosenEvent.value = undefined
  } catch (error) { if (alive && request === interviewGeneration && epoch === store.sessionEpoch) interviewError.value = errorText(error) }
  finally { if (alive && request === interviewGeneration) interviewBusy.value = false }
}
function loadInitialInterview() {
  if (!props.eventId || props.eventRevision === undefined || !reviewIdValid(props.eventId) || !Number.isSafeInteger(props.eventRevision) || props.eventRevision < 0) { interviewError.value = '面试入口缺少有效的历史修订，请重新选择安排或明确不关联。'; return }
  return chooseInterview(props.eventId, props.eventRevision)
}
function clearInterview() { if (editable.value && !interviewBusy.value) { interview.value = null; initialLinkRequired.value = false; interviewError.value = ''; pickerOpen.value = false } }
function applyMonth() {
  if (!editable.value || interviewBusy.value) return
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(scheduleMonthInput.value) || scheduleMonthInput.value < '1000-02' || scheduleMonthInput.value > '9999-11') { interviewError.value = '请选择有效月份。'; return }
  scheduleMonth.value = scheduleMonthInput.value; scheduleCursor.value = undefined; scheduleCursors.value = []; chosenEvent.value = undefined; interviewError.value = ''
}
function selectEvent(event: InterviewEvent) { if (editable.value && !interviewBusy.value) { chosenEvent.value = event; historyBefore.value = undefined; historyCursors.value = [] } }
function nextEvents() { if (editable.value && !interviewBusy.value && !events.isFetching.value && events.data.value?.nextCursor) { scheduleCursors.value.push(scheduleCursor.value); scheduleCursor.value = events.data.value.nextCursor; chosenEvent.value = undefined } }
function olderEventHistory() { if (editable.value && !interviewBusy.value && !eventHistory.isFetching.value && eventHistory.data.value?.nextCursor != null) { historyCursors.value.push(historyBefore.value); historyBefore.value = eventHistory.data.value.nextCursor } }
function retry() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const apply = deferred.value; deferred.value = undefined; apply() } }
function selectVersion(versionId: string) { if (readable.value && props.id) void router.push({ name: 'recollections', query: { ...route.query, item: props.id, versionId } }) }
function openRepractice() {
  if (!readable.value || locked.value || dirty.value || stale.value || needsReview.value || serverLoading.value
    || interviewBusy.value || otherChanges.value || current.value?.status !== 'ACTIVE') return
  void router.push({ name: 'repractice', query: { recollectionId: current.value.id, versionId: current.value.currentVersion.id } })
}
function displayTime(instant: string, zone?: string) { try { return new Date(instant).toLocaleString('zh-CN', zone ? { timeZone: zone } : undefined) } catch { return instant } }
</script>

<template>
  <section class="recollection-editor" aria-label="面后回忆编辑器">
    <header class="editor-heading"><div><span class="field-help">个人回忆<span v-if="readable"> · {{ creating ? '手工补记' : `${recollectionStatusLabels[current?.status ?? 'ACTIVE']} · v${current?.currentVersion.number ?? '…'}` }}</span></span><h2>{{ !readable ? '面后回忆' : creating ? '记录记得的问题' : current?.currentVersion.content.title ?? '读取回忆' }}</h2></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!readable" class="notice" role="status">登录或资料访问尚未恢复，回忆内容暂时遮挡，本地输入继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="editor-content">
      <p class="field-help">问题、回答片段和反馈均为你的回忆，可能不完整。它们不是逐字实录、已确认的项目事实或正式训练提交。</p>
      <p v-if="!creating && !current && detail.isPending.value" role="status">正在读取回忆…</p>
      <p v-if="detail.error.value" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text :disabled="locked || detail.isFetching.value" @click="detail.refetch()">重新读取</NButton></p>
      <p v-if="savedMessage" class="notice" role="status">{{ savedMessage }}</p>
      <div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value" :disabled="writer.busy.value" :loading="writer.busy.value" @click="retry">用原请求重试</NButton><NButton v-else-if="current" :disabled="locked || serverLoading || otherChanges" @click="compareServer">读取服务器版本并对照</NButton></div>
      <div v-if="deferred" class="notice" role="status"><p>此前的保存已收到回执。请确认接收后再继续。</p><NButton @click="receiveDeferred">接收已确认回执</NButton></div>
      <div v-if="stale || needsReview" class="notice"><p>服务器版本或访问条件有变化。输入继续保留，继续保存前请核对服务器版本。</p><NButton :disabled="locked || serverLoading || otherChanges" :loading="serverLoading" @click="compareServer">读取最新版本</NButton></div>
      <p v-if="serverError" class="notice error" role="alert">{{ serverError }}</p>
      <section v-if="serverCopy" class="server-comparison"><h3>服务器 v{{ serverCopy.currentVersion.number }} · 修订 {{ serverCopy.revision }} · {{ recollectionStatusLabels[serverCopy.status] }}</h3><strong>{{ serverCopy.currentVersion.content.title }}</strong><dl><template v-for="field in [{ key: 'question', label: '问题' }, { key: 'conditions', label: '条件' }, { key: 'answerFragment', label: '回答片段' }, { key: 'feedback', label: '回忆的反馈' }, { key: 'uncertainty', label: '不确定性' }, { key: 'gap', label: '待补内容' }] as const" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ serverCopy.currentVersion.content[field.key] || '未填写' }}</dd></template></dl><p class="field-help">{{ recollectionBaselineLabels[serverCopy.currentVersion.content.baselineKind] }}；关联面试：{{ serverCopy.currentVersion.interview ? `${serverCopy.currentVersion.interview.title} · 修订 ${serverCopy.currentVersion.interview.revision}` : '未关联' }}</p><div class="row-actions"><NButton :disabled="locked || otherChanges" @click="useServer(true)">保留本地改写，采用最新修订</NButton><NButton :disabled="locked || otherChanges" @click="useServer(false)">采用服务器内容</NButton></div></section>
      <div v-if="current" class="editor-tabs" role="group" aria-label="回忆内容与历史"><button :aria-pressed="tab === 'edit'" @click="tab = 'edit'">当前补记</button><button :aria-pressed="tab === 'history'" @click="tab = 'history'">历史版本</button><button :aria-pressed="tab === 'question'" @click="tab = 'question'">整理为题目</button><button :aria-pressed="tab === 'action'" @click="tab = 'action'">改进动作</button></div>
      <div v-if="current && tab !== 'history'" class="row-actions"><NButton size="small" :disabled="!readable || locked || dirty || stale || needsReview || serverLoading || interviewBusy || otherChanges || current.status !== 'ACTIVE'" @click="openRepractice">从当前回忆开始空白补练</NButton><span class="field-help">使用已保存 v{{ current.currentVersion.number }} 的题面，本次回答从空白开始。</span></div>
      <div v-show="tab === 'edit'" v-if="creating || current" class="edit-body">
        <p v-if="current?.status === 'ARCHIVED'" class="notice">这条回忆已归档。历史和来源继续保留，恢复后可再次补记。</p>
        <p v-if="otherChanges" class="notice">题目整理或改进动作有未保存输入或未决请求，请先回到对应页签处理，再修改回忆。</p>
        <label>回忆标题<NInput v-model:value="content.title" :disabled="!editable" placeholder="例如：缓存更新失败时，我如何回答补偿方案" /></label>
        <label>记得的问题<NInput v-model:value="content.question" type="textarea" :autosize="{ minRows: 4, maxRows: 12 }" :disabled="!editable" placeholder="保留你记得的提问，记不清的部分可在下方说明。" /></label>
        <label>问题分类<NSelect v-model:value="content.type" :options="questionTypeOptions" :disabled="!editable" aria-label="回忆问题分类" /></label>
        <label>记得的条件或上下文（可选）<NInput v-model:value="content.conditions" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }" :disabled="!editable" /></label>
        <label>旧回答的情况<NSelect :value="kindChosen ? content.baselineKind : null" :options="baselineOptions" :disabled="!editable" placeholder="请明确选择" aria-label="旧回答的情况" @update:value="chooseKind" /></label>
        <label v-if="kindChosen && content.baselineKind === 'RECOLLECTION'">回答回忆片段<NInput v-model:value="content.answerFragment" type="textarea" :autosize="{ minRows: 5, maxRows: 16 }" :disabled="!editable" placeholder="写下你记得自己当时如何回答，不把后来学到的内容补写为当时的原答。" /></label>
        <p v-else-if="kindChosen" class="field-help">本记录明确没有旧回答。保留问题，之后可以重新作答；本条不会进入正式双答案比较。</p>
        <label>回忆的反馈（可选）<NInput v-model:value="content.feedback" type="textarea" :autosize="{ minRows: 3, maxRows: 10 }" :disabled="!editable" placeholder="这是你记得的反馈，不默认是面试官逐字原话。" /></label>
        <label>记忆的不确定性<NInput v-model:value="content.uncertainty" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="!editable" placeholder="例如：问题大意记得，但追问顺序与具体措辞不确定。若认为确定，也请写明自己的判断。" /></label>
        <label>自己认为的待补内容（可选）<NInput v-model:value="content.gap" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="!editable" placeholder="可以记录还想查证或补充的内容，不自动认定为能力缺陷。" /></label>
        <section class="interview-section" aria-label="关联面试安排"><h3>关联面试（可选）</h3><div v-if="interview" class="interview-summary"><strong>{{ interview.title }}</strong><p>{{ displayTime(interview.startAt, interview.zoneId) }} · {{ interview.zoneId }}</p><p class="field-help">{{ interview.round || '未填写轮次' }} · {{ scheduleStatusLabels[interview.status] }} · 安排修订 {{ interview.revision }}</p><RouterLink class="text-link" :to="{ name: 'schedule', query: { item: interview.eventId, version: String(interview.revision) } }">查看所选历史安排</RouterLink></div><p v-else class="field-help">{{ initialLinkRequired ? '正在核对面试入口指定的历史修订。' : '当前未关联面试，也可以独立保存回忆。' }}</p><p class="field-help">只保留所选历史的标题、轮次、时间与状态。安排之后改期或取消不会改写这份关联。</p>
          <p v-if="interviewBusy" role="status">正在读取确切面试历史…</p><p v-if="interviewError" class="notice error" role="alert">{{ interviewError }}<NButton v-if="initialLinkRequired" text :disabled="!editable || interviewBusy" @click="loadInitialInterview">重读指定历史</NButton></p>
          <div class="row-actions"><NButton size="small" :disabled="!editable || interviewBusy" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起选择' : interview ? '重新选择面试历史' : '选择面试' }}</NButton><NButton v-if="interview || initialLinkRequired" text size="small" :disabled="!editable || interviewBusy" @click="clearInterview">不关联面试</NButton></div>
          <div v-if="pickerOpen" class="interview-picker"><div class="month-filter"><label>安排月份<input v-model="scheduleMonthInput" type="month" min="1000-02" max="9999-11" :disabled="!editable || interviewBusy" /></label><NButton size="small" :disabled="!editable || interviewBusy" @click="applyMonth">读取这个月</NButton></div><p class="field-help">包含月界附近的安排，时间保留各安排的原时区。选中后还需选择确切修订。</p><p v-if="events.isPending.value" role="status">正在读取面试安排…</p><p v-if="events.error.value" class="notice error" role="alert">{{ errorText(events.error.value) }}<NButton text :disabled="events.isFetching.value || interviewBusy" @click="events.refetch()">重读安排</NButton></p><div class="picker-list"><button v-for="event in events.data.value?.items" :key="event.id" :disabled="!editable || interviewBusy" :aria-pressed="chosenEvent?.id === event.id" @click="selectEvent(event)"><strong>{{ event.title }}</strong><small>{{ displayTime(event.time.startAt, event.time.zoneId) }} · {{ event.round || '未填写轮次' }} · {{ scheduleStatusLabels[event.status] }}</small></button></div><p v-if="events.data.value && !events.data.value.items.length && !events.error.value" class="field-help">这个月份没有安排，可以更换月份或不关联。</p><div class="row-actions"><NButton size="small" :disabled="!scheduleCursors.length || events.isFetching.value || interviewBusy" @click="scheduleCursor = scheduleCursors.pop(); chosenEvent = undefined">上一页</NButton><NButton size="small" :disabled="!events.data.value?.nextCursor || events.isFetching.value || interviewBusy" @click="nextEvents">下一页</NButton></div>
            <div v-if="chosenEvent" class="interview-revisions"><strong>{{ chosenEvent.title }} · 选择历史修订</strong><NButton size="small" :disabled="!editable || interviewBusy" @click="chooseInterview(chosenEvent.id, chosenEvent.revision)">采用列表中的修订 {{ chosenEvent.revision }}</NButton><p v-if="eventHistory.isPending.value" role="status">正在读取历史修订…</p><p v-if="eventHistory.error.value" class="notice error" role="alert">{{ errorText(eventHistory.error.value) }}<NButton text :disabled="interviewBusy" @click="eventHistory.refetch()">重读历史</NButton></p><div class="picker-list"><button v-for="entry in eventHistory.data.value?.items" :key="entry.revision" :disabled="!editable || interviewBusy" @click="chooseInterview(chosenEvent.id, entry.revision)">修订 {{ entry.revision }} · {{ displayTime(entry.createdAt) }}</button></div><div class="row-actions"><NButton size="small" :disabled="!historyCursors.length || eventHistory.isFetching.value || interviewBusy" @click="historyBefore = historyCursors.pop()">较新修订</NButton><NButton size="small" :disabled="eventHistory.data.value?.nextCursor == null || eventHistory.isFetching.value || interviewBusy" @click="olderEventHistory">更早修订</NButton></div></div>
          </div>
        </section>
        <p v-if="problem && current?.status !== 'ARCHIVED'" class="field-help" role="status">{{ problem }}</p>
        <div class="row-actions"><NButton v-if="creating || current?.status === 'ACTIVE'" type="primary" :loading="writer.busy.value" :disabled="!editable || !!problem || stale || needsReview || interviewBusy || initialLinkRequired || (!creating && !dirty)" @click="save">{{ creating ? '保存回忆' : '保存为新版本' }}</NButton><NButton v-if="current" :disabled="locked || stale || needsReview || serverLoading || interviewBusy || otherChanges || (current.status === 'ACTIVE' && dirty)" @click="changeStatus">{{ current.status === 'ARCHIVED' ? '恢复回忆' : '归档回忆' }}</NButton><NButton v-if="current" text :disabled="locked || serverLoading || otherChanges" :loading="serverLoading" @click="compareServer">核对服务器版本</NButton></div>
      </div>
      <RecollectionHistory v-if="current && tab === 'history'" :id="current.id" :current-version="current.currentVersion" :version-id="historyVersion" :disabled="!readable" @select="selectVersion" />
      <RecollectionQuestionAdoption v-if="current" v-show="tab === 'question'" :value="latest ?? current" :disabled="!readable || locked || dirty || serverLoading || interviewBusy || tab !== 'question'" @created="refresh" />
      <RecollectionActionCreate v-if="current" v-show="tab === 'action'" :value="latest ?? current" :disabled="!readable || locked || dirty || serverLoading || interviewBusy || tab !== 'action'" @created="refresh" />
    </div>
  </section>
</template>

<style scoped>
.recollection-editor { display: grid; align-content: start; gap: 17px; min-width: 0; padding: 24px 27px 30px; }
.editor-heading { display: flex; justify-content: space-between; align-items: start; gap: 14px; }.editor-heading > div { min-width: 0; }.editor-heading h2 { margin-top: 7px; overflow-wrap: anywhere; }
.editor-tabs { display: flex; flex-wrap: wrap; gap: 20px; border-bottom: 1px solid var(--line); }.editor-tabs button { padding: 9px 0; color: var(--muted); border-bottom: 2px solid transparent; font-size: 13px; }.editor-tabs button[aria-pressed=true] { color: var(--brand-strong); border-color: var(--brand); font-weight: 650; }
.editor-content, .edit-body, .interview-section, .interview-summary, .interview-picker, .interview-revisions { display: grid; gap: 14px; min-width: 0; }
.edit-body > label, .month-filter label { display: grid; gap: 7px; color: var(--ink); font-size: 13px; }.interview-section { padding-top: 18px; border-top: 1px solid var(--line); }.interview-section h3 { font-size: 14px; }
.interview-summary { gap: 7px; font-size: 13px; overflow-wrap: anywhere; }.interview-picker { padding: 14px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); }
.month-filter { display: flex; gap: 12px; align-items: end; flex-wrap: wrap; }.month-filter input { padding: 6px 9px; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: 4px; }
.picker-list { display: grid; gap: 4px; }.picker-list > button { display: grid; gap: 5px; padding: 10px; border-radius: 5px; text-align: left; overflow-wrap: anywhere; font-size: 13px; }.picker-list > button:hover, .picker-list > button[aria-pressed=true] { background: var(--surface-deep); }.picker-list small { color: var(--muted); font-size: 12px; }.interview-revisions { border-top: 1px solid var(--line); padding-top: 14px; font-size: 13px; }.interview-revisions > button { justify-self: start; }
.server-comparison { display: grid; gap: 12px; padding: 15px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); overflow-wrap: anywhere; }.server-comparison h3 { font-size: 14px; }dl { display: grid; gap: 7px; margin: 0; }dt { color: var(--muted); font-size: 12px; }dd { margin: 0 0 9px; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
@media (max-width: 700px) { .recollection-editor { padding: 22px 16px; }.editor-tabs { gap: 16px; } }
</style>
