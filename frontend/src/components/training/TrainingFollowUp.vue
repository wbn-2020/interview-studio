<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRoute } from 'vue-router'
import { NButton, NCheckbox, NInput } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { AiAttempt, AiFollowUpPage, AiFollowUpTask, AiPreview, AiSettings } from '../../api/ai'
import { aiDate, aiFeeLabels, aiKnowledgeMaterialChecksMatch, aiPendingStatuses, aiTaskStatusLabel, formatAiAmount, parseAiMicros } from '../../api/ai'
import type { TrainingAnswer, TrainingSession } from '../../api/training'
import { notifyTrainingChanged, trainingContextFingerprint } from '../../api/training'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import AiManifest from '../ai/AiManifest.vue'
import AiAuthorizationChoice from '../ai/AiAuthorizationChoice.vue'
import AiAuthorizationScope from '../ai/AiAuthorizationScope.vue'
import AiDraftPreview from '../ai/AiDraftPreview.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

const props = defineProps<{ session: TrainingSession; questionId: string; answer?: TrainingAnswer | null; disabled?: boolean }>()
const emit = defineEmits<{ state: [value: { dirty: boolean; locked: boolean }]; changed: []; selectQuestion: [id: string] }>()
const store = useWorkspaceStore()
const route = useRoute()
const writer = useCommand()
const active = ref(true)
const prepared = shallowRef<AiPreview>()
const consent = ref(false)
const authorization = ref<AiAuthorizationReference>(), retryAuthorization = ref<AiAuthorizationReference>()
const authorizationState = reactive({ dirty: false, locked: false })
const authorizationTask = shallowRef<AiFollowUpTask>()
const previewing = ref(false)
const actionError = ref('')
const message = ref('')
const selectedId = ref(route.query.item === props.session.id && route.query.question === props.questionId && typeof route.query.followUp === 'string' ? route.query.followUp : '')
const current = shallowRef<AiFollowUpTask>()
const deferred = shallowRef<() => void>()
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const manifestOpen = ref(false)
const retryOpen = ref(false)
const retryRevision = ref<number>()
const retryConsent = ref(false)
const acknowledgeUnknown = ref(false)
const reconcileId = ref('')
const reconcileRevision = ref<number>()
const amountInput = ref('')
const note = ref('')
const reconcileConfirmed = ref(false)
const refreshingAction = ref(false)
let alive = true
let requestGeneration = 0
let restoreRequest = 0
let commandEpoch = store.sessionEpoch
const base = computed(() => `/training/sessions/${props.session.id}/follow-ups`)
const questionPath = computed(() => `/training/sessions/${props.session.id}/questions/${props.questionId}/follow-ups`)
const readable = computed(() => active.value && store.sessionActive)
const locked = computed(() => writer.locked.value || Boolean(deferred.value) || previewing.value || refreshingAction.value || authorizationState.locked)
const blocked = computed(() => !readable.value || Boolean(props.disabled) || locked.value)
const dirty = computed(() => Boolean(consent.value || retryConsent.value || acknowledgeUnknown.value || amountInput.value || note.value || reconcileConfirmed.value || authorizationState.dirty))
const rootId = computed(() => props.session.questions.find(question => question.id === props.questionId)?.followUp?.rootQuestionId ?? props.questionId)
const used = computed(() => props.session.questions.filter(question => question.kind === 'FOLLOW_UP' && question.followUp?.rootQuestionId === rootId.value).length)
const contextFingerprint = computed(() => trainingContextFingerprint(props.session.contextChecks))
const preparedContextCurrent = computed(() => contextFingerprint.value !== null
  && prepared.value?.manifest.expressionChecks == null && prepared.value?.manifest.sources.length === 0
  && aiKnowledgeMaterialChecksMatch(prepared.value.manifest)
  && trainingContextFingerprint(prepared.value?.manifest.contextChecks) === contextFingerprint.value)
const taskContextCurrent = computed(() => contextFingerprint.value !== null
  && current.value?.manifest.expressionChecks == null && current.value?.manifest.sources.length === 0
  && aiKnowledgeMaterialChecksMatch(current.value.manifest)
  && trainingContextFingerprint(current.value?.manifest.contextChecks) === contextFingerprint.value)
const canCreate = computed(() => props.session.status === 'RUNNING' && Boolean(props.answer) && contextFingerprint.value !== null
  && used.value < props.session.config.followUpLimit && !reconcileId.value && !retryOpen.value && !authorizationState.dirty)
const settings = useQuery({
  queryKey: computed(() => ['training-follow-up-settings', store.sessionEpoch, props.session.id]),
  enabled: readable,
  queryFn: () => read<AiSettings>(`${base.value}/settings`),
})
const configured = computed(() => Boolean(settings.data.value?.configuration.enabled && settings.data.value.configuration.configured))
const tasks = useQuery({
  queryKey: computed(() => ['training-follow-ups', store.sessionEpoch, props.session.id, cursor.value]),
  enabled: readable,
  queryFn: () => read<AiFollowUpPage>(`${base.value}?limit=20${cursor.value ? `&cursor=${cursor.value}` : ''}`),
})
const detail = useQuery({
  queryKey: computed(() => ['training-follow-up', store.sessionEpoch, props.session.id, selectedId.value]),
  enabled: computed(() => readable.value && !props.disabled && Boolean(selectedId.value)),
  queryFn: () => read<AiFollowUpTask>(`${base.value}/${selectedId.value}`),
})
const selectedAttempt = computed(() => current.value?.attempts.find(attempt => attempt.id === reconcileId.value))
const draftAttempt = computed(() => { const task = current.value; return task?.attempts.find(attempt => attempt.number === task.task.attemptCount) })
const unknownAttempts = computed(() => current.value?.attempts.filter(attempt => attempt.feeStatus === 'UNKNOWN') ?? [])
const amount = computed(() => parseAiMicros(amountInput.value))
const retryStale = computed(() => retryRevision.value !== current.value?.task.revision)
const reconciliationStale = computed(() => reconcileRevision.value !== current.value?.task.revision || selectedAttempt.value?.feeStatus !== 'UNKNOWN')
const canCancel = computed(() => Boolean(current.value && ['QUEUED', 'RUNNING', 'VALIDATING', 'UNKNOWN'].includes(current.value.task.status)))
const canRetry = computed(() => Boolean(current.value && configured.value && current.value.eligible && taskContextCurrent.value && props.session.status === 'RUNNING'
  && current.value.manifest.training?.generation === props.session.generation
  && ['FAILED', 'UNKNOWN', 'CANCELED'].includes(current.value.task.status)
  && current.value.task.attemptCount < Math.min(3, current.value.manifest.configuration.maxAttempts)))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))

watch([dirty, locked], ([changed, waiting]) => emit('state', { dirty: changed, locked: waiting }), { immediate: true, flush: 'sync' })
watch([current, () => authorizationState.dirty, () => authorizationState.locked], () => {
  // 登录代次清空任务查询时，仍有输入或原请求的授权子树不能随目录一起卸载。
  if (current.value && !authorizationState.dirty && !authorizationState.locked) authorizationTask.value = current.value
}, { flush: 'sync' })
watch(detail.data, () => {
  // 只接受当前登录代次的查询缓存；旧观察者迟到通知不能带回上一代会话的内容。
  const value = queryClient.getQueryData<AiFollowUpTask>(taskKey())
  if (value && readable.value && !props.disabled) accept(value)
}, { immediate: true })
watch([readable, () => props.disabled, selectedId, () => store.sessionEpoch], () => {
  restoreRequest++
  if (readable.value && !props.disabled && selectedId.value) void restoreDetail()
}, { immediate: true, flush: 'post' })
watch(tasks.data, (value, previous) => {
  if (!value || !readable.value) return
  // 列表变化只重读服务器题单，不切题、不改变本地答案或费用核对表单。
  if (previous && value.items.some(task => task.revision !== previous.items.find(saved => saved.id === task.id)?.revision)) emit('changed')
})
watch([() => props.answer?.id, () => props.session.generation, () => props.session.status, () => store.sessionEpoch, () => store.sessionActive], () => {
  requestGeneration++
  previewing.value = false
  refreshingAction.value = false
  prepared.value = undefined
  consent.value = false
  retryConsent.value = false
  acknowledgeUnknown.value = false
  reconcileConfirmed.value = false
  retryRevision.value = undefined
  reconcileRevision.value = undefined
}, { flush: 'sync' })
watch(contextFingerprint, () => {
  const hadPreview = Boolean(prepared.value) || previewing.value
  requestGeneration++
  previewing.value = false
  refreshingAction.value = false
  prepared.value = undefined
  consent.value = false
  retryConsent.value = false
  acknowledgeUnknown.value = false
  // 来源变化只使外发授权失效；原键未决命令、迟到回执和费用核对输入仍按各自流程恢复。
  if (hadPreview) actionError.value = '训练背景的可用性已变化，先前预览与授权已失效，请重新预览。'
  if (readable.value && !props.disabled && selectedId.value) void restoreDetail()
}, { flush: 'sync' })
watch(() => store.sessionEpoch, () => { current.value = undefined; cursor.value = undefined; cursors.value = [] }, { flush: 'sync' })
watch(() => current.value?.task.revision, (revision, previous) => {
  if (previous !== undefined && revision !== previous) {
    retryConsent.value = false
    acknowledgeUnknown.value = false
    reconcileConfirmed.value = false
  }
})
watch(writer.error, error => {
  if (error instanceof ApiError && !writer.pending.value) {
    prepared.value = undefined
    consent.value = false
    retryConsent.value = false
    reconcileConfirmed.value = false
    retryRevision.value = undefined
    reconcileRevision.value = undefined
    if (error.code === 'AI_SOURCE_CHANGED') message.value = '训练背景已变化，请重读背景状态后重新预览并创建追问任务。已有任务与费用记录继续保留。'
    refresh()
  }
})
onActivated(() => { active.value = true })
onDeactivated(() => {
  active.value = false
  requestGeneration++
  previewing.value = false
  refreshingAction.value = false
  prepared.value = undefined
  consent.value = false
  retryConsent.value = false
  acknowledgeUnknown.value = false
  reconcileConfirmed.value = false
})
onUnmounted(() => { alive = false; requestGeneration++; restoreRequest++ })

function taskKey() { return ['training-follow-up', store.sessionEpoch, props.session.id, selectedId.value] }
async function restoreDetail() {
  const id = selectedId.value
  const epoch = store.sessionEpoch
  const request = ++restoreRequest
  const target = `${base.value}/${id}`
  try {
    // 恢复可读时强制重读，即使缓存未过期或组件错过数据变化；这里只刷新查询，不执行待接收命令。
    const value = await queryClient.fetchQuery({ queryKey: taskKey(), queryFn: () => read<AiFollowUpTask>(target), staleTime: 0 })
    if (alive && request === restoreRequest && epoch === store.sessionEpoch && id === selectedId.value && readable.value && !props.disabled) accept(value)
  } catch { /* 查询错误由共用 detail 观察者展示，费用核对输入保持原样。 */ }
}

function validTask(value: AiFollowUpTask): boolean {
  return value.task.purpose === 'FOLLOW_UP' && value.manifest.purpose === 'FOLLOW_UP' && value.manifest.expressionChecks == null
    && value.manifest.relationAiFeedback == null && Array.isArray(value.manifest.sources) && value.manifest.sources.length === 0
    && aiKnowledgeMaterialChecksMatch(value.manifest) && value.manifest.training?.sessionId === props.session.id
}
function accept(value: AiFollowUpTask): boolean {
  if (!validTask(value) || value.task.id !== selectedId.value || (current.value && value.task.revision < current.value.task.revision)) return false
  const changed = value.task.revision !== current.value?.task.revision
  current.value = value
  if (changed && value.generatedQuestionId) emit('changed')
  return true
}
function refresh() {
  void queryClient.invalidateQueries({ queryKey: ['training-follow-ups', store.sessionEpoch, props.session.id] })
  void settings.refetch()
  emit('changed')
  notifyTrainingChanged()
}
function receive(value: AiFollowUpTask, notice: string) {
  if (selectedId.value !== value.task.id) { selectedId.value = value.task.id; current.value = undefined }
  accept(value)
  prepared.value = undefined
  consent.value = false
  message.value = notice
  queryClient.setQueryData(['training-follow-up', store.sessionEpoch, props.session.id, value.task.id], current.value)
  refresh()
  void detail.refetch()
}
function run(path: string, body: unknown, apply: (value: AiFollowUpTask) => void, validate?: (value: AiFollowUpTask) => void) {
  if (blocked.value) return
  const expectedTaskId = path.startsWith(`${base.value}/`) ? path.slice(base.value.length + 1).split('/')[0] : undefined
  commandEpoch = store.sessionEpoch
  actionError.value = ''
  message.value = ''
  void writer.run<AiFollowUpTask>(path, 'POST', body, value => {
    if (!alive) return
    // 校验必须在延迟接收之前完成，异常交由写入器保留原正文与幂等键。
    if (!validTask(value) || (expectedTaskId && value.task.id !== expectedTaskId)) {
      throw new ApiError(502, 'AI_TASK_MISMATCH', '服务器回执与本次追问命令不一致，请用原请求重试。')
    }
    validate?.(value)
    const receiveValue = () => apply(value)
    if (!readable.value || commandEpoch !== store.sessionEpoch || props.disabled) deferred.value = receiveValue
    else receiveValue()
  })
}
function retryCommand() {
  if (!readable.value || props.disabled || writer.busy.value) return
  commandEpoch = store.sessionEpoch
  void writer.retry()
}
function receiveDeferred() {
  if (!readable.value || props.disabled || !deferred.value) return
  const apply = deferred.value
  deferred.value = undefined
  apply()
}
async function prepare() {
  if (blocked.value || !canCreate.value || !configured.value || !props.answer) return
  const answerId = props.answer.id
  const generation = props.session.generation
  const epoch = store.sessionEpoch
  const request = ++requestGeneration
  previewing.value = true
  prepared.value = undefined
  consent.value = false
  actionError.value = ''
  try {
    const value = await preview<AiPreview>(`${questionPath.value}/preview`, { answerId })
    if (!alive || !readable.value || request !== requestGeneration || epoch !== store.sessionEpoch || props.disabled || props.session.generation !== generation) return
    const input = value.manifest.training
    if (value.manifest.purpose !== 'FOLLOW_UP' || value.manifest.trainingDraft != null || value.manifest.expressionChecks != null
        || !Array.isArray(value.manifest.sources) || value.manifest.sources.length !== 0 || !aiKnowledgeMaterialChecksMatch(value.manifest)
        || value.manifest.relationAiFeedback != null || input?.sessionId !== props.session.id || input.questionId !== props.questionId || input.answerId !== answerId
        || input.generation !== props.session.generation || input.rootQuestionId !== rootId.value) {
      actionError.value = '外发预览与所选提交不一致，请重新核对。'
      emit('changed')
      return
    }
    if (value.manifest.contextChecks == null || trainingContextFingerprint(value.manifest.contextChecks) !== contextFingerprint.value) {
      actionError.value = '预览中的背景核对与当前训练状态不一致，正在重读背景状态；请重新预览后再授权。'
      emit('changed')
      return
    }
    prepared.value = value
  } catch (error) { if (alive && request === requestGeneration && epoch === store.sessionEpoch) actionError.value = errorText(error) }
  finally { if (alive && request === requestGeneration) previewing.value = false }
}
function create() {
  if (blocked.value || !canCreate.value || !configured.value || !consent.value || overBudget.value || !prepared.value || !preparedContextCurrent.value || !props.answer
      || prepared.value.manifest.relationAiFeedback != null || prepared.value.manifest.training?.answerId !== props.answer.id || prepared.value.manifest.training.generation !== props.session.generation) return
  const answerId = props.answer.id
  const questionId = props.questionId
  run(questionPath.value, { answerId, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) },
    value => receive(value, '追问任务已获授权，成功后会直接加入训练'), value => {
    if (value.manifest.training?.answerId !== answerId || value.manifest.training.questionId !== questionId) {
      throw new ApiError(502, 'AI_TASK_MISMATCH', '追问任务没有绑定本次选中的提交，请用原请求重试。')
    }
  })
}
function selectTask(id: string) {
  if (blocked.value || (dirty.value && !window.confirm('切换任务会丢弃尚未保存的授权勾选或费用核对输入，继续？'))) return
  selectedId.value = id
  current.value = undefined
  authorizationTask.value = undefined; authorizationState.dirty = false; authorizationState.locked = false
  resetForms()
}
function resetForms() {
  prepared.value = undefined
  consent.value = false
  retryOpen.value = false
  retryConsent.value = false
  acknowledgeUnknown.value = false
  reconcileId.value = ''
  amountInput.value = ''
  note.value = ''
  reconcileConfirmed.value = false
  actionError.value = ''
}
function cancel() {
  if (blocked.value || !current.value || !canCancel.value || !window.confirm('取消追问任务？已经可能发送的调用仍可能收费，未知费用预留继续保留。')) return
  run(`${base.value}/${current.value.task.id}/cancel`, { expectedRevision: current.value.task.revision }, value => receive(value, '服务器已记录取消命令，请继续核对任务和费用状态'))
}
function openRetry() {
  if (blocked.value || !current.value || !canRetry.value) return
  retryOpen.value = true
  retryRevision.value = current.value.task.revision
  retryConsent.value = false
  acknowledgeUnknown.value = false
}
function retryTask() {
  if (blocked.value || !current.value || !canRetry.value || retryStale.value || !retryConsent.value || (unknownAttempts.value.length && !acknowledgeUnknown.value)) return
  run(`${base.value}/${current.value.task.id}/retry`, { expectedRevision: retryRevision.value, consentConfirmed: true, acknowledgeUnknownCost: acknowledgeUnknown.value, ...aiAuthorizationRequestFields(retryAuthorization.value) }, value => {
    retryOpen.value = false
    retryConsent.value = false
    acknowledgeUnknown.value = false
    receive(value, '已授权新增一次尝试；旧未知费用继续保留')
  })
}
function openReconciliation(attempt: AiAttempt) {
  if (blocked.value || !current.value || attempt.feeStatus !== 'UNKNOWN') return
  if (reconcileId.value !== attempt.id && (amountInput.value || note.value) && !window.confirm('切换费用核对对象会丢弃尚未保存的金额和说明，继续？')) return
  if (reconcileId.value !== attempt.id) { amountInput.value = ''; note.value = '' }
  reconcileId.value = attempt.id
  reconcileRevision.value = current.value.task.revision
  reconcileConfirmed.value = false
}
function closeReconciliation() {
  if (blocked.value || ((amountInput.value || note.value) && !window.confirm('关闭费用核对并丢弃未保存金额和说明？'))) return
  reconcileId.value = ''
  amountInput.value = ''
  note.value = ''
  reconcileConfirmed.value = false
}
async function reloadAction() {
  if (blocked.value || !current.value) return
  const id = current.value.task.id
  const request = ++requestGeneration
  const epoch = store.sessionEpoch
  refreshingAction.value = true
  actionError.value = ''
  retryConsent.value = false
  acknowledgeUnknown.value = false
  reconcileConfirmed.value = false
  try {
    const value = await read<AiFollowUpTask>(`${base.value}/${id}`)
    if (!alive || !readable.value || props.disabled || request !== requestGeneration || epoch !== store.sessionEpoch) return
    if (!accept(value)) { actionError.value = '服务器记录已继续变化，请再次核对。'; return }
    if (retryOpen.value) retryRevision.value = value.task.revision
    if (reconcileId.value) reconcileRevision.value = value.task.revision
    refresh()
  } catch (error) { if (alive && request === requestGeneration && epoch === store.sessionEpoch) actionError.value = errorText(error) }
  finally { if (alive && request === requestGeneration) refreshingAction.value = false }
}
function reconcile() {
  if (blocked.value || !current.value || !selectedAttempt.value || reconciliationStale.value || amount.value === null || !note.value.trim() || [...note.value].length > 1000 || !reconcileConfirmed.value) return
  run(`${base.value}/${current.value.task.id}/attempts/${selectedAttempt.value.id}/reconcile`, { expectedRevision: reconcileRevision.value, amountMicros: amount.value, note: note.value }, value => {
    reconcileId.value = ''
    amountInput.value = ''
    note.value = ''
    reconcileConfirmed.value = false
    receive(value, '已保存用户账单核对；不会恢复丢失的模型结果')
  })
}
function next() { if (!blocked.value && tasks.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = tasks.data.value.nextCursor } }
</script>

<template>
  <section class="follow-up" aria-label="训练 AI 追问">
    <header><h4>基于这次提交继续追问</h4><p class="field-help">此主问已加入 {{ used }} / {{ session.config.followUpLimit }} 道追问。主问与它的所有追问共用额度，修订答案不会重置。</p></header>
    <p v-if="answer" class="field-help">当前选择：第 {{ answer.number }} 版提交 · {{ aiDate(answer.createdAt) }}。将使用这一版已提交的文字与结构图；下方可核对完整外发内容。</p>
    <p v-else class="field-help">先提交一版回答，再选择该次提交预览追问。</p>
    <p v-if="session.status !== 'RUNNING'" class="field-help">{{ session.status === 'ENDED' ? '训练已结束，可以查看任务与核对费用。' : '训练运行时才能授权新的追问。' }}暂停或结束会使正在等待的插题授权失效。</p>
    <p v-if="contextFingerprint === null" class="notice" role="status">尚未核对固定背景的当前可用性。新追问需要先重读背景状态，已有任务和费用仍可处理。<NButton text :disabled="blocked" @click="emit('changed')">重读背景状态</NButton></p>
    <p v-if="settings.error.value" class="notice error" role="alert">{{ errorText(settings.error.value) }}<NButton text :disabled="blocked" @click="settings.refetch()">重读 AI 配置</NButton></p>
    <p v-else-if="settings.data.value && !configured" class="field-help">AI 未启用或配置不完整，已有任务仍可查询和处理费用。</p>
    <NButton :loading="previewing" :disabled="blocked || !canCreate || !configured" @click="prepare">预览第 {{ answer?.number ?? '—' }} 版回答的外发内容</NButton>
    <section v-if="prepared" class="follow-up-form">
      <AiManifest :manifest="prepared.manifest" :budget="prepared.budget" />
      <p v-if="overBudget" class="notice error">本次预留超过当前剩余预算，请核对费用后重新预览。</p>
      <AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || !canCreate || !preparedContextCurrent">我已核对完整外发内容和费用，同意生成一道追问，并在校验通过后直接加入当前训练</AiAuthorizationChoice>
      <NButton type="primary" :disabled="blocked || !consent || overBudget || !canCreate || !preparedContextCurrent" @click="create">生成并加入当前训练</NButton>
      <p class="field-help">追问不包含参考答案或评价。模型问题的技术质量仍需判断；成功后可作答或跳过。</p>
    </section>
    <p v-if="actionError" class="notice error" role="alert">{{ actionError }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="!readable || !!disabled || writer.busy.value" @click="retryCommand">用原请求重试</NButton><NButton v-else-if="current" text :disabled="blocked" @click="reloadAction">核对最新状态</NButton></p>
    <p v-if="deferred" class="notice" role="status">已确认结果在登录或页面条件变化后到达。<NButton text :disabled="!readable || !!disabled" @click="receiveDeferred">接收已确认结果</NButton></p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>

    <section class="follow-up-history"><header class="panel-heading"><h4>本场追问任务</h4><NButton text size="small" :disabled="blocked" @click="tasks.refetch()">刷新记录</NButton></header>
      <p v-if="tasks.isPending.value" class="field-help" role="status">正在读取追问任务…</p><p v-if="tasks.error.value" class="notice error" role="alert">{{ errorText(tasks.error.value) }}</p>
      <p v-if="tasks.data.value && !tasks.data.value.items.length" class="field-help">本场还没有追问任务。</p>
      <div class="task-list"><button v-for="task in tasks.data.value?.items" :key="task.id" :disabled="blocked" :class="{ selected: selectedId === task.id }" @click="selectTask(task.id)"><strong>{{ task.title }}</strong><span>{{ aiTaskStatusLabel(task.status, task.purpose) }} · {{ task.attemptCount }} 次尝试</span><small>{{ aiDate(task.createdAt) }}</small></button></div>
      <div v-if="cursors.length || tasks.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="blocked || !cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="blocked || !tasks.data.value?.nextCursor" @click="next">下一页</NButton></div>
    </section>
    <p v-if="selectedId && detail.isPending.value" class="field-help" role="status">正在读取任务详情…</p><p v-if="detail.error.value" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text :disabled="blocked" @click="detail.refetch()">重读任务</NButton></p>
    <section v-if="current" class="follow-up-form">
      <header><h4>{{ current.task.title }} · {{ aiTaskStatusLabel(current.task.status, current.task.purpose) }}</h4><p class="field-help">绑定第 {{ current.manifest.training?.answerNumber }} 版提交 · {{ current.task.provider }} / {{ current.task.model }}</p></header>
      <AiDraftPreview :task-id="current.task.id" :attempt="draftAttempt" :task-status="current.task.status" :streaming="current.manifest.configuration.streaming" :disabled="!readable || disabled || current.task.id !== selectedId || current.manifest.training?.sessionId !== session.id" @refresh="detail.refetch()" />
      <p v-if="aiPendingStatuses.includes(current.task.status)" class="notice" role="status">任务状态会自动更新。成功后仅更新题单，你当前的输入会继续保留。</p>
      <p v-if="current.task.errorCode" class="field-help">状态说明：{{ current.task.errorCode }}</p>
      <p v-if="current.manifest.contextChecks == null" class="notice" role="status">这项历史任务缺少背景核对记录，不能授权新的尝试。需要新追问时请重新预览并创建任务；历史结果和费用记录仍保留。</p>
      <p v-else-if="(contextFingerprint !== null && !taskContextCurrent) || current.task.errorCode === 'AI_SOURCE_CHANGED'" class="notice" role="status">这项任务的背景使用条件已变化。{{ current.generatedQuestionId ? '已加入的追问继续保留，作答前请复核依据。' : '旧授权不能继续用于发送或插题，请重新预览并创建任务。' }}已发生的调用仍可能收费，费用记录继续保留。</p>
      <p v-else-if="!current.eligible && !current.generatedQuestionId" class="field-help">训练条件或额度已不满足这项任务的插题条件。仍可核对已有费用。</p>
      <NButton v-if="current.generatedQuestionId" :disabled="blocked" @click="emit('selectQuestion', current.generatedQuestionId)">转到已加入的追问</NButton>
      <div class="row-actions"><NButton :disabled="blocked" @click="detail.refetch()">刷新任务</NButton><NButton v-if="canCancel" :disabled="blocked" @click="cancel">取消任务</NButton><NButton v-if="canRetry" :disabled="blocked" @click="openRetry">重新核对并授权尝试</NButton><NButton :disabled="blocked" @click="manifestOpen = !manifestOpen">{{ manifestOpen ? '收起' : '查看' }}已授权清单</NButton></div>
      <AiManifest v-if="manifestOpen" :manifest="current.manifest" />
      <article v-for="attempt in current.attempts" :key="attempt.id" class="attempt-card"><h5>第 {{ attempt.number }} 次尝试 · {{ aiFeeLabels[attempt.feeStatus] }}</h5><p>预留 {{ formatAiAmount(attempt.reservedMicros, current.task.currency) }}；{{ attempt.accountedMicros === null ? '金额尚未确定' : `${attempt.feeStatus === 'RECONCILED' ? '用户核对' : '用量估算'} ${formatAiAmount(attempt.accountedMicros, current.task.currency)}` }}</p><p class="field-help">输入／输出 {{ attempt.promptTokens ?? '未知' }} / {{ attempt.completionTokens ?? '未知' }} token · {{ aiDate(attempt.createdAt) }}</p><p v-if="attempt.errorCode" class="field-help">{{ attempt.errorCode }}</p><p v-if="attempt.providerRequestId" class="field-help">供应商请求标识：{{ attempt.providerRequestId }}</p><p v-if="attempt.reconciliationNote" class="preserve">用户核对依据：{{ attempt.reconciliationNote }}</p><p v-if="attempt.feeStatus === 'UNKNOWN'" class="field-help">未知费用继续占用预算，取消和跨日期不会自动释放。</p><NButton v-if="attempt.feeStatus === 'UNKNOWN'" size="small" :disabled="blocked" @click="openReconciliation(attempt)">根据账单核对费用</NButton></article>
      <section v-if="reconcileId" class="follow-up-form"><h5>核对第 {{ selectedAttempt?.number }} 次尝试</h5><p v-if="reconciliationStale" class="notice">任务状态已变化。输入继续保留，请重读状态后再确认。</p><label>金额（整数微单位）<NInput v-model:value="amountInput" :disabled="blocked" :maxlength="16" inputmode="numeric" placeholder="1000000 微单位等于 1 个当前币种单位" /></label><p class="field-help">{{ amount === null ? '请输入非负整数。' : formatAiAmount(amount, current.task.currency) }}</p><label>账单核对依据<NInput v-model:value="note" :disabled="blocked" type="textarea" :maxlength="1000" :count-graphemes="value => [...value].length" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="填写账单条目和核对依据，不要填写凭据。" /></label><NCheckbox v-model:checked="reconcileConfirmed" :disabled="blocked || reconciliationStale">我已核对外部账单，理解此处保存的是人工记录</NCheckbox><div class="row-actions"><NButton v-if="reconciliationStale" :disabled="blocked" @click="reloadAction">重读状态并保留输入</NButton><NButton type="primary" :disabled="blocked || reconciliationStale || amount === null || !note.trim() || !reconcileConfirmed" @click="reconcile">保存费用核对</NButton><NButton :disabled="blocked" @click="closeReconciliation">关闭核对</NButton></div></section>
      <section v-if="retryOpen" class="follow-up-form"><h5>重新授权一次尝试</h5><AiManifest :manifest="current.manifest" :budget="settings.data.value?.budget" /><p v-if="retryStale || !canRetry" class="notice">状态、背景或训练条件已变化，请重新核对。背景变化或暂停后恢复，需要关闭本次重试，再重新预览创建任务。</p><NCheckbox v-if="unknownAttempts.length" v-model:checked="acknowledgeUnknown" :disabled="blocked || retryStale || !canRetry">我理解旧未知费用仍占用预算，新尝试将额外预留费用</NCheckbox><AiAuthorizationChoice v-model:checked="retryConsent" v-model:authorization="retryAuthorization" :manifest="current.manifest" :disabled="blocked || retryStale || !canRetry">我再次确认以上外发清单，同意新尝试并在校验通过后加入训练</AiAuthorizationChoice><div class="row-actions"><NButton v-if="retryStale" :disabled="blocked" @click="reloadAction">重新核对</NButton><NButton type="primary" :disabled="blocked || retryStale || !canRetry || !retryConsent || (!!unknownAttempts.length && !acknowledgeUnknown)" @click="retryTask">授权新尝试</NButton><NButton :disabled="blocked" @click="retryOpen = false; retryConsent = false; acknowledgeUnknown = false">暂不重试</NButton></div></section>
    </section>
    <AiAuthorizationScope v-if="authorizationTask" :key="authorizationTask.task.id" :task-id="authorizationTask.task.id" :manifest="authorizationTask.manifest" :disabled="disabled || !readable || !current || writer.locked.value || !!deferred || previewing || refreshingAction" @state="authorizationState.dirty = $event.dirty; authorizationState.locked = $event.locked" />
  </section>
</template>

<style scoped>
.follow-up { display: grid; gap: 16px; padding-top: 20px; border-top: 1px solid var(--line); min-width: 0; }
.follow-up h4, .follow-up h5 { margin: 0 0 8px; font-size: 14px; overflow-wrap: anywhere; }
.follow-up > .n-button { justify-self: start; }
.follow-up-form { display: grid; gap: 15px; padding: 17px; border: 1px solid var(--line); border-radius: 6px; min-width: 0; }
.follow-up-form label { display: grid; gap: 7px; font-size: 13px; color: var(--muted); }
.follow-up-history { display: grid; gap: 11px; }
.panel-heading { display: flex; justify-content: space-between; gap: 12px; align-items: center; }
.task-list { display: grid; gap: 5px; }
.task-list button { display: grid; gap: 5px; padding: 10px; text-align: left; border: 1px solid var(--line); border-radius: 5px; }
.task-list button.selected { border-color: var(--brand); background: var(--surface-soft); }
.task-list strong { font-size: 13px; font-weight: 550; overflow-wrap: anywhere; } .task-list span, .task-list small { font-size: 12px; color: var(--muted); }
.attempt-card { display: grid; gap: 8px; padding-top: 15px; border-top: 1px solid var(--line); font-size: 13px; overflow-wrap: anywhere; }
.preserve { white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 650px) { .follow-up-form { padding: 13px; } }
</style>
