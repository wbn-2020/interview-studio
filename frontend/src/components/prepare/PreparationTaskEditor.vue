<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import type { ChangePreparationTaskStatus, CreatePreparationTask, PreparationTask, PreparationTaskContent, PreparationTaskDetail, PreparationTaskHistoryPage, PreparationTaskStatus, PreparationTaskTarget, SavePreparationTask } from '../../api/preparationTask'
import { clonePreparationTaskContent, emptyPreparationTask, preparationTaskActionLabels, preparationTaskClosed, preparationTaskContentProblem, preparationTaskStatusLabels, preparationTaskTransitionLabel, preparationTaskTransitions } from '../../api/preparationTask'
import { useCommand } from '../../composables/useCommand'
import { useRecentVisit } from '../../composables/useRecentVisit'
import { useWorkspaceStore } from '../../state/workspace'
import PreparationTaskContentForm from './PreparationTaskContentForm.vue'
import PreparationTaskTargetPicker from './PreparationTaskTargetPicker.vue'

const props = defineProps<{ id?: string; disabled?: boolean }>()
const emit = defineEmits<{ changed: []; created: [task: PreparationTask]; close: [] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const current = shallowRef<PreparationTaskDetail>()
const latest = shallowRef<PreparationTaskDetail>()
const initial = emptyPreparationTask()
const content = ref(clonePreparationTaskContent(initial))
const baseline = ref(JSON.stringify(initial))
const target = shallowRef<PreparationTaskTarget | null>(null)
const pickerOpen = ref(false)
const pickerBusy = ref(false)
const pickerGeneration = ref(0)
const tab = ref<'edit' | 'history'>('edit')
const createdResult = shallowRef<PreparationTask>()
const serverCopy = shallowRef<PreparationTaskDetail>()
const serverLoading = ref(false)
const serverError = ref('')
const needsReview = ref(false)
const savedMessage = ref('')
const receiptMismatch = ref(false)
const deferred = shallowRef<() => void>()
const historyBefore = ref<number>()
const historyCursors = ref<(number | undefined)[]>([])
const readable = computed(() => store.sessionActive && !props.disabled)
const recordRecentVisit = useRecentVisit('PREPARATION_TASK', () => props.id, readable)
const creating = computed(() => !props.id)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || Boolean(createdResult.value))
const dirty = computed(() => JSON.stringify(content.value) !== baseline.value || (creating.value && target.value !== null))
const closed = computed(() => Boolean(current.value && preparationTaskClosed(current.value.task)))
const stale = computed(() => Boolean(current.value && latest.value && latest.value.task.revision > current.value.task.revision))
const editable = computed(() => !blocked.value && !closed.value && !receiptMismatch.value)
const problem = computed(() => preparationTaskContentProblem(content.value))
const nextStatuses = computed(() => current.value ? preparationTaskTransitions(current.value.task) : [])
const checkLabels: Record<string, string> = { CURRENT: '目标仍为当前有效版本', SUPERSEDED: '目标已有新版本', ARCHIVED: '目标已归档', UNAVAILABLE: '目标当前不可用' }
const targetCheck = computed(() => latest.value?.targetCheck ?? current.value?.targetCheck)
const targetState = computed(() => {
  const saved = current.value?.task.target
  const check = targetCheck.value
  if (!saved) return ''
  if (!check || check.kind !== 'TARGET' || check.itemId !== saved.itemId || check.versionId !== saved.versionId) return '目标当前状态尚未核对'
  return checkLabels[check.state] ?? '目标当前不可用'
})
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let readGeneration = 0
let comparisonGeneration = 0
let commandEpoch = store.sessionEpoch

const detail = useQuery({
  queryKey: computed(() => ['preparation-tasks', store.sessionEpoch, 'item', props.id]),
  enabled: computed(() => readable.value && Boolean(props.id)),
  queryFn: async () => {
    const epoch = store.sessionEpoch
    const generation = ++readGeneration
    const value = await read<PreparationTaskDetail>(`/prepare/tasks/${props.id}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || generation !== readGeneration) throw new ApiError(0, 'PREPARATION_TASK_CONTEXT_CHANGED', '读取条件已变化，本地输入继续保留。')
    if (value.task.id !== props.id) throw new ApiError(502, 'PREPARATION_TASK_MISMATCH', '任务身份与当前页面不一致，请重新读取。')
    return value
  },
})
const history = useQuery({
  queryKey: computed(() => ['preparation-tasks', store.sessionEpoch, 'history', props.id, historyBefore.value]),
  enabled: computed(() => readable.value && Boolean(props.id) && tab.value === 'history'),
  queryFn: async () => {
    const epoch = store.sessionEpoch
    const before = historyBefore.value
    const value = await read<PreparationTaskHistoryPage>(`/prepare/tasks/${props.id}/history?limit=20${before !== undefined ? `&before=${before}` : ''}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || before !== historyBefore.value || tab.value !== 'history') throw new ApiError(0, 'PREPARATION_TASK_HISTORY_CHANGED', '历史读取条件已变化，请重新读取。')
    if (value.items.some(entry => entry.snapshot.id !== props.id)) throw new ApiError(502, 'PREPARATION_TASK_MISMATCH', '历史快照与当前任务不一致，请重新读取。')
    return value
  },
})
watch(detail.data, value => {
  if (!value || !readable.value || !recordLatest(value)) return
  // 刷新只推进干净正文；来源状态可以更新，但不能抬升尚未保存的编辑基线。
  if (!current.value || (!dirty.value && !locked.value)) {
    accept(value)
    recordRecentVisit(value.task.id)
  }
}, { immediate: true })
watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed
  commandState.locked = waiting
  store.changes.set('preparation-task-editor', changeState)
  store.changes.set('preparation-task-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => {
  readGeneration++
  comparisonGeneration++
  serverCopy.value = undefined
  serverLoading.value = false
  serverError.value = ''
  pickerGeneration.value++
  pickerOpen.value = false
  pickerBusy.value = false
  if (current.value) needsReview.value = true
}, { flush: 'sync' })
watch(writer.error, error => { if (error instanceof ApiError && error.status === 409) needsReview.value = Boolean(current.value) }, { flush: 'sync' })
onUnmounted(() => {
  alive = false
  readGeneration++
  comparisonGeneration++
  if (store.changes.get('preparation-task-editor') === changeState) store.changes.delete('preparation-task-editor')
  if (store.changes.get('preparation-task-command') === commandState) store.changes.delete('preparation-task-command')
})

function recordLatest(value: PreparationTaskDetail): boolean {
  if (value.task.id !== props.id || (latest.value && value.task.revision < latest.value.task.revision)
    || (current.value && value.task.revision < current.value.task.revision)) return false
  latest.value = value
  return true
}
function accept(value: PreparationTaskDetail) {
  if (!recordLatest(value)) return
  current.value = value
  content.value = clonePreparationTaskContent(value.task.content)
  baseline.value = JSON.stringify(content.value)
  target.value = value.task.target
  needsReview.value = false
}
function refresh() { void queryClient.invalidateQueries({ queryKey: ['preparation-tasks'] }); emit('changed') }
function receive(value: PreparationTaskDetail, message: string) {
  savedMessage.value = message
  if (!recordLatest(value)) {
    serverCopy.value = latest.value
    needsReview.value = true
    savedMessage.value = `本次操作已确认（修订 ${value.task.revision}），服务器已有后续修改。本地输入继续保留，请核对最新任务。`
  } else {
    accept(value)
    serverCopy.value = undefined
    queryClient.setQueryData(['preparation-tasks', store.sessionEpoch, 'item', value.task.id], value)
  }
  refresh()
}
function sameContent(left: PreparationTaskContent, right: PreparationTaskContent): boolean {
  return Boolean(left && left.title === right.title && left.detail === right.detail && left.minutes === right.minutes
    && left.plannedDate === right.plannedDate && left.zoneId === right.zoneId)
}
function run(path: string, method: string, body: unknown, apply: (value: PreparationTaskDetail) => void, matches: (value: PreparationTaskDetail) => boolean = () => true) {
  if (blocked.value || serverLoading.value || receiptMismatch.value) return
  readGeneration++
  pickerOpen.value = false
  savedMessage.value = ''
  commandEpoch = store.sessionEpoch
  void writer.run<PreparationTaskDetail>(path, method, body, value => {
    if (!alive) return
    if (!value?.task || !/^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/.test(value.task.id)
      || (props.id && value.task.id !== props.id) || !Number.isSafeInteger(value.task.revision) || value.task.revision < 0 || !matches(value)) {
      receiptMismatch.value = true
      needsReview.value = Boolean(current.value)
      writer.error.value = new ApiError(502, 'PREPARATION_TASK_MISMATCH', '保存回执与本次请求不一致，输入继续保留，暂不重复提交。请从列表核对；已有任务可核对服务器内容。')
      refresh()
      return
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = () => apply(value)
    else apply(value)
  })
}
function save() {
  if (!editable.value || problem.value || pickerBusy.value || serverLoading.value || needsReview.value || stale.value || (!creating.value && !dirty.value)) return
  const snapshot = clonePreparationTaskContent(content.value)
  if (creating.value) {
    const chosen = target.value
    const body: CreatePreparationTask = { target: chosen ? { itemId: chosen.itemId, versionId: chosen.versionId } : null, content: snapshot }
    run('/prepare/tasks', 'POST', body, value => {
      createdResult.value = value.task
      content.value = clonePreparationTaskContent(initial)
      baseline.value = JSON.stringify(content.value)
      target.value = null
      changeState.dirty = false
      commandState.locked = false
      savedMessage.value = '准备任务已保存。'
      refresh()
      emit('created', value.task)
    }, value => value.task.revision === 0 && value.task.status === 'TODO'
      && value.task.sourceAiTaskId === null && value.task.sourceStep === null
      && value.task.startedAt === null && value.task.completedAt === null
      && (chosen ? value.task.target?.itemId === chosen.itemId && value.task.target.versionId === chosen.versionId : value.task.target === null)
      && sameContent(value.task.content, snapshot))
  } else if (current.value) {
    const body: SavePreparationTask = { expectedRevision: current.value.task.revision, content: snapshot }
    run(`/prepare/tasks/${current.value.task.id}`, 'PUT', body, value => receive(value, `已保存任务修订 ${value.task.revision}，原内容保留在历史中。`),
      value => value.task.revision === body.expectedRevision + 1 && sameContent(value.task.content, snapshot))
  }
}
function changeStatus(status: PreparationTaskStatus) {
  if (blocked.value || receiptMismatch.value || !current.value || dirty.value || needsReview.value || stale.value || serverLoading.value || !nextStatuses.value.includes(status)) return
  const label = preparationTaskTransitionLabel(status, current.value.task.startedAt !== null)
  const effect = status === 'DONE' ? '完成只作用于本任务，不代表已掌握知识或完成复练。完成后不再编辑或切换状态。'
    : status === 'CANCELLED' ? '取消后保留历史，不再编辑或切换状态。' : status === 'IN_PROGRESS' ? '首次开始会留下记录，不启动自动计时。' : '只变更本任务状态，保留内容和原计划日期。'
  if (!window.confirm(`确认${label}？${effect}`)) return
  const body: ChangePreparationTaskStatus = { expectedRevision: current.value.task.revision, status }
  run(`/prepare/tasks/${current.value.task.id}/status`, 'POST', body, value => receive(value, `任务状态：${preparationTaskStatusLabels[value.task.status]}，历史保留。`),
    value => value.task.revision === body.expectedRevision + 1 && value.task.status === status)
}
async function compareServer() {
  if (blocked.value || !props.id || serverLoading.value) return
  const epoch = store.sessionEpoch
  const generation = ++comparisonGeneration
  readGeneration++
  serverLoading.value = true
  serverError.value = ''
  try {
    const value = await read<PreparationTaskDetail>(`/prepare/tasks/${props.id}`)
    if (!alive || !readable.value || locked.value || epoch !== store.sessionEpoch || generation !== comparisonGeneration) return
    if (!recordLatest(value)) { serverError.value = '服务器已出现更晚的任务记录，请重新核对。'; return }
    serverCopy.value = value
    if (current.value?.task.revision === value.task.revision && !receiptMismatch.value) needsReview.value = false
  } catch (error) { if (alive && generation === comparisonGeneration && epoch === store.sessionEpoch) serverError.value = errorText(error) }
  finally { if (alive && generation === comparisonGeneration) serverLoading.value = false }
}
function useServer(keepDraft: boolean) {
  if (blocked.value || !serverCopy.value || serverLoading.value) return
  const value = serverCopy.value
  if (latest.value && value.task.revision < latest.value.task.revision) { serverError.value = '比较副本之后又有更新，请重新读取最新任务。'; return }
  if (keepDraft && preparationTaskClosed(value.task)) return
  if (!window.confirm(keepDraft ? '已对照服务器内容，确认保留本地改写并使用这份服务器修订继续编辑？再次保存会写入完整本地内容。' : '采用服务器内容并放弃本地未保存修改？')) return
  const local = clonePreparationTaskContent(content.value)
  accept(value)
  if (keepDraft) content.value = local
  serverCopy.value = undefined
  receiptMismatch.value = false
  writer.error.value = undefined
  savedMessage.value = keepDraft ? '已核对最新修订，本地改写继续保留，尚未保存。' : '已采用服务器内容。'
}
function chooseTarget(value: PreparationTaskTarget) { if (creating.value && editable.value) { target.value = value; pickerOpen.value = false } }
function retry() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const apply = deferred.value; deferred.value = undefined; apply() } }
function olderHistory() { if (readable.value && !history.isFetching.value && history.data.value?.nextBefore != null) { historyCursors.value.push(historyBefore.value); historyBefore.value = history.data.value.nextBefore } }
</script>

<template>
  <section class="preparation-task-editor" aria-label="准备任务详情">
    <header class="task-heading"><div><p class="section-label">{{ creating ? '手工准备任务' : `修订 ${current?.task.revision ?? '读取中'}` }}</p><h2>{{ creating ? '新建准备任务' : current?.task.content.title ?? '读取任务' }}</h2></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!creating && !current && detail.isPending.value" role="status">正在读取任务…</p>
    <p v-if="detail.error.value" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text :disabled="!readable || locked" @click="detail.refetch()">重新读取</NButton></p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retry">用原请求重试</NButton><NButton v-else-if="current" text :disabled="blocked" @click="compareServer">核对最新任务</NButton></p>
    <p v-if="deferred" class="notice" role="status">服务器已确认操作，登录或资料访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已确认结果</NButton></p>
    <p v-if="savedMessage" class="notice" role="status">{{ savedMessage }}</p><p v-if="createdResult"><NButton text @click="emit('created', createdResult)">打开已创建任务</NButton></p>
    <template v-if="(creating || current) && !createdResult">
      <div class="task-state" aria-live="polite"><span>{{ writer.busy.value ? '正在提交…' : dirty ? '有本地修改，尚未保存' : creating ? '填写后保存' : '当前输入与读取的任务一致' }}</span><span v-if="current" class="status-pill">{{ preparationTaskStatusLabels[current.task.status] }}</span></div>
      <p v-if="stale || needsReview" class="notice">任务或访问条件已变化，本地输入继续保留。<NButton text :loading="serverLoading" :disabled="blocked" @click="compareServer">核对最新任务</NButton></p>
      <p v-if="current?.task.target" class="field-help">{{ targetState }}。目标变化仅作提示，不阻止处理本任务。</p>
      <div v-if="current" class="task-provenance"><RouterLink v-if="current.task.target" class="text-link" :to="{ name: 'prepare', query: { kind: 'TARGET', item: current.task.target.itemId, version: current.task.target.versionId } }">目标：{{ current.task.target.title }} · v{{ current.task.target.number }}</RouterLink><span v-else class="field-help">本任务未关联目标。</span><RouterLink v-if="current.task.sourceAiTaskId" class="text-link" :to="{ name: 'ai', query: { item: current.task.sourceAiTaskId } }">查看最初 AI 建议与采用记录</RouterLink><span class="field-help">目标与 AI 来源创建后固定。首次开始：{{ current.task.startedAt ?? '尚未显式开始' }}；完成：{{ current.task.completedAt ?? '尚未完成' }}。</span></div>
      <p v-if="serverError" class="notice error" role="alert">{{ serverError }}</p>
      <section v-if="serverCopy" class="server-comparison"><h3>服务器副本 · 修订 {{ serverCopy.task.revision }}</h3><strong>{{ serverCopy.task.content.title }}</strong><p class="preserve">{{ serverCopy.task.content.detail || '未填写说明' }}</p><p class="field-help">{{ preparationTaskStatusLabels[serverCopy.task.status] }} · {{ serverCopy.task.content.minutes }} 分钟 · {{ serverCopy.task.content.plannedDate ?? '未安排日期' }} · {{ serverCopy.task.content.zoneId }}</p><div class="row-actions"><NButton size="small" :disabled="blocked || serverLoading" @click="useServer(false)">采用服务器内容</NButton><NButton v-if="!preparationTaskClosed(serverCopy.task)" size="small" :disabled="blocked || serverLoading" @click="useServer(true)">保留改写并核对新修订</NButton><NButton text size="small" :disabled="blocked" @click="serverCopy = undefined">收起比较</NButton></div></section>
      <nav v-if="current" class="task-tabs" aria-label="任务内容"><button :class="{ selected: tab === 'edit' }" :aria-current="tab === 'edit' ? 'page' : undefined" @click="tab = 'edit'">任务内容</button><button :class="{ selected: tab === 'history' }" :aria-current="tab === 'history' ? 'page' : undefined" @click="tab = 'history'">变更历史</button></nav>
      <div v-show="tab === 'edit' || creating" class="task-edit-body">
        <section v-if="creating" class="target-selection"><div class="row-actions"><h3>关联目标（可选）</h3><NButton size="small" :disabled="!editable || pickerBusy" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起选择' : target ? '更换目标' : '选择目标' }}</NButton></div><div v-if="target" class="row-actions"><span>{{ target.title }} · v{{ target.number }}</span><NButton text size="small" :disabled="!editable || pickerBusy" @click="target = null">取消关联</NButton></div><PreparationTaskTargetPicker v-if="pickerOpen" :key="pickerGeneration" :disabled="!editable" @busy="pickerBusy = $event" @choose="chooseTarget" /></section>
        <p v-if="closed" class="notice">本任务已{{ current?.task.status === 'DONE' ? '完成' : '取消' }}，内容与状态保持只读；原记录可在历史中查看。</p>
        <PreparationTaskContentForm v-model="content" :disabled="!editable" />
        <p v-if="problem && !closed" class="field-help" role="status">{{ problem }}</p>
        <div v-if="!closed" class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!editable || !!problem || pickerBusy || serverLoading || stale || needsReview || (!creating && !dirty)" @click="save">{{ creating ? '创建准备任务' : '保存任务修改' }}</NButton></div>
        <section v-if="current && !closed" class="task-status-actions"><h3>处理任务</h3><p class="field-help">先保存内容，再明确开始、暂停、完成或取消。本任务的完成不会改变知识、复练或面试安排。</p><div class="row-actions"><NButton v-for="status in nextStatuses" :key="status" size="small" :disabled="blocked || dirty || stale || needsReview || serverLoading" @click="changeStatus(status)">{{ preparationTaskTransitionLabel(status, current.task.startedAt !== null) }}</NButton></div></section>
      </div>
      <section v-if="tab === 'history' && current" class="task-history">
        <p class="field-help">历史显示当时保存的不可变快照；目标标题、任务状态和计划日期均取当时记录，不附带目标的当前可用性结论。</p>
        <p v-if="history.isFetching.value" class="field-help" role="status">正在读取变更历史…</p><p v-if="history.error.value" class="notice error" role="alert">{{ errorText(history.error.value) }}<NButton text :disabled="!readable" @click="history.refetch()">重新读取历史</NButton></p>
        <details v-for="entry in history.data.value?.items" :key="entry.snapshot.revision" class="history-entry"><summary>修订 {{ entry.snapshot.revision }} · {{ preparationTaskActionLabels[entry.action] ?? entry.action }} · {{ preparationTaskStatusLabels[entry.snapshot.status] }}</summary><div class="history-snapshot"><strong>{{ entry.snapshot.content.title }}</strong><p class="preserve">{{ entry.snapshot.content.detail || '未填写说明' }}</p><p class="field-help">{{ entry.snapshot.content.minutes }} 分钟 · {{ entry.snapshot.content.plannedDate ?? '未安排日期' }} · {{ entry.snapshot.content.zoneId }}</p><RouterLink v-if="entry.snapshot.target" class="text-link" :to="{ name: 'prepare', query: { kind: 'TARGET', item: entry.snapshot.target.itemId, version: entry.snapshot.target.versionId } }">当时目标：{{ entry.snapshot.target.title }} · v{{ entry.snapshot.target.number }}</RouterLink><p v-else class="field-help">当时未关联目标。</p><RouterLink v-if="entry.snapshot.sourceAiTaskId" class="text-link" :to="{ name: 'ai', query: { item: entry.snapshot.sourceAiTaskId } }">查看最初 AI 建议 · 步骤 {{ entry.snapshot.sourceStep }}</RouterLink><p class="field-help">记录时间：{{ entry.snapshot.updatedAt }}；首次开始：{{ entry.snapshot.startedAt ?? '未开始' }}；完成：{{ entry.snapshot.completedAt ?? '未完成' }}。</p></div></details>
        <p v-if="history.data.value && !history.data.value.items.length" class="field-help">当前范围没有历史记录。</p><div class="row-actions"><NButton size="small" :disabled="!readable || history.isFetching.value || !historyCursors.length" @click="historyBefore = historyCursors.pop()">较新记录</NButton><NButton size="small" :disabled="!readable || history.isFetching.value || history.data.value?.nextBefore == null" @click="olderHistory">更早记录</NButton></div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.preparation-task-editor { display: grid; align-content: start; gap: 17px; min-width: 0; padding: 24px 27px 30px; }
.task-heading { display: flex; justify-content: space-between; align-items: start; gap: 14px; } .task-heading > div { min-width: 0; } h2 { margin-top: 5px; overflow-wrap: anywhere; }
.task-state { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 9px; color: var(--muted); font-size: 12px; }
.task-provenance, .task-edit-body, .target-selection, .task-history, .history-snapshot { display: grid; gap: 14px; min-width: 0; }
.task-provenance { padding-bottom: 15px; border-bottom: 1px solid var(--line); font-size: 12px; overflow-wrap: anywhere; }
.task-tabs { display: flex; gap: 22px; border-bottom: 1px solid var(--line); }
.task-tabs button { padding: 9px 0; border-bottom: 2px solid transparent; color: var(--muted); font-size: 13px; }
.task-tabs button.selected { border-color: var(--brand); color: var(--brand-strong); font-weight: 650; }
.server-comparison { display: grid; gap: 11px; padding: 15px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
.preserve { white-space: pre-wrap; overflow-wrap: anywhere; } .server-comparison strong, .history-snapshot strong { overflow-wrap: anywhere; }
.task-status-actions { display: grid; gap: 12px; padding-top: 18px; border-top: 1px solid var(--line); }
.history-entry { border-bottom: 1px solid var(--line); padding-bottom: 14px; } .history-entry summary { cursor: pointer; font-size: 13px; } .history-snapshot { padding-top: 14px; font-size: 13px; }
@media (max-width: 700px) { .preparation-task-editor { padding: 22px 17px; } }
</style>
