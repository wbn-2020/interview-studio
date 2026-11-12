<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { AiManifest as Manifest, AiPreview, AiSettings, AiTask, AiTaskPage } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate, aiPendingStatuses, aiStatusLabels, aiTaskStatusLabel, formatAiAmount } from '../../api/ai'
import type { AiComparisonCreate, AiComparisonDifferences, AiComparisonDraft, AiComparisonPreviewRequest, AiComparisonSide } from '../../api/aiComparison'
import { aiComparisonManifestMatches, aiComparisonTaskValid } from '../../api/aiComparison'
import type { RepracticeComparison, RepracticeSide } from '../../api/repractice'
import { repracticeBaselineKind, repracticeComparisonValid, repracticeInput, repracticeJudgeProblem } from '../../api/repractice'
import { reviewIdValid } from '../../api/review'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import { useAiUpdatesStore } from '../../state/aiUpdates'
import AiManifest from '../ai/AiManifest.vue'
import AiAuthorizationChoice from '../ai/AiAuthorizationChoice.vue'
import AiDraftPreview from '../ai/AiDraftPreview.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'
import AiComparisonCandidate from '../ai/AiComparisonCandidate.vue'

const props = defineProps<{ comparison: RepracticeComparison; disabled?: boolean }>()
const emit = defineEmits<{ fill: [draft: AiComparisonDraft]; refresh: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), updates = useAiUpdatesStore(), writer = useCommand()
const active = ref(true), selectedIds = ref<string[]>([]), selectedTaskId = ref('')
const cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const currentPage = shallowRef<AiTaskPage>(), currentTask = shallowRef<AiTask>()
const draftAttempt = computed(() => { const task = currentTask.value; return task?.attempts.find(attempt => attempt.number === task.task.attemptCount) })
const prepared = shallowRef<AiPreview>(), preparedRequest = ref(''), consent = ref(false), previewing = ref(false)
const authorization = ref<AiAuthorizationReference>()
const previewError = ref(''), detailError = ref(''), message = ref(''), receiptMismatch = ref(false)
const receiptCommand = shallowRef<NonNullable<typeof writer.pending.value>>()
const deferred = shallowRef<() => void>()
const state = reactive({ dirty: false, locked: false })
const stateKey = `repractice-ai-comparison-${props.comparison.comparison.id}`
let alive = true, generation = 0, previewSequence = 0, pageSequence = 0, taskSequence = 0, commandEpoch = store.sessionEpoch
const comparisonId = computed(() => props.comparison.comparison.id)
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge
  && !access.restricted && !store.changes.get('training-access-command')?.locked
  && repracticeBaselineKind(props.comparison.repractice) === 'ANSWER' && props.comparison.baseline !== null
  && repracticeComparisonValid(props.comparison, comparisonId.value))
const locked = computed(() => writer.locked.value || Boolean(deferred.value) || (receiptMismatch.value && Boolean(receiptCommand.value)))
const blocked = computed(() => !readable.value || locked.value || receiptMismatch.value)
const request = computed<AiComparisonPreviewRequest>(() => ({ sourceIds: [...selectedIds.value] }))
const requestSignature = computed(() => JSON.stringify(request.value))
const contextSignature = computed(() => JSON.stringify([comparisonId.value, repracticeBaselineKind(props.comparison.repractice), props.comparison.baseline, props.comparison.current,
  props.comparison.differences, props.comparison.repractice.sources, props.comparison.progress.map(item =>
    [item.sourceId, item.actionId, item.actionRevision, item.tracking, item.verification])]))
const path = computed(() => `/review/comparisons/${comparisonId.value}/ai`)
const pageKey = computed(() => ['repractice', store.sessionEpoch, 'ai-comparison-tasks', comparisonId.value, cursor.value])
const taskKey = computed(() => ['repractice', store.sessionEpoch, 'ai-comparison-task', comparisonId.value, selectedTaskId.value])
const settings = useQuery({ queryKey: computed(() => ['ai', store.sessionEpoch, 'settings']), enabled: readable, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation
  const value = await read<AiSettings>('/ai/settings')
  requireCurrent(epoch, stamp)
  if (!value?.configuration || typeof value.configuration.enabled !== 'boolean' || typeof value.configuration.configured !== 'boolean') throw mismatch('AI 配置不完整，请重新读取。')
  return value
} })
const tasks = useQuery({ queryKey: pageKey, enabled: readable, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, sequence = pageSequence, id = comparisonId.value, after = cursor.value
  const value = await read<AiTaskPage>(`${path.value}/tasks?limit=20${after ? `&cursor=${encodeURIComponent(after)}` : ''}`)
  requireCurrent(epoch, stamp)
  if (sequence !== pageSequence || id !== comparisonId.value || after !== cursor.value) throw changed()
  if (!pageValid(value)) throw mismatch('比较任务列表格式不一致，请重新读取。')
  return value
} })
const detail = useQuery({ queryKey: taskKey, enabled: computed(() => readable.value && reviewIdValid(selectedTaskId.value)), queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, sequence = taskSequence, id = comparisonId.value, taskId = selectedTaskId.value
  const value = await read<AiTask>(`/ai/tasks/${taskId}`)
  requireCurrent(epoch, stamp)
  if (sequence !== taskSequence || id !== comparisonId.value || taskId !== selectedTaskId.value) throw changed()
  if (!taskMatches(value, props.comparison, taskId)) throw mismatch('任务与这份对照、确切提交或所选来源不一致。')
  return value
} })
const configured = computed(() => Boolean(settings.data.value?.configuration.enabled && settings.data.value.configuration.configured && !settings.error.value))
const pendingTask = computed(() => currentPage.value?.items.some(item => aiPendingStatuses.includes(item.status)) ?? false)
const limitReached = computed(() => !cursor.value && (currentPage.value?.items.length ?? 0) >= 3)
const historyReady = computed(() => Boolean(currentPage.value && !tasks.error.value && !tasks.isFetching.value))
const problem = computed(() => {
  if (!selectedIds.value.length || selectedIds.value.length > 10) return '请选择本次需要核对的 1–10 条来源。'
  if (new Set(selectedIds.value).size !== selectedIds.value.length || selectedIds.value.some(id => !reviewIdValid(id) || sourceProblem(id))) return '所选来源的跟踪状态或验证条件已变化，请核对后重新选择。'
  return ''
})
const previewMatches = computed(() => Boolean(prepared.value && preparedRequest.value === requestSignature.value
  && manifestMatches(prepared.value.manifest, props.comparison, selectedIds.value, true)))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const fillAllowed = computed(() => Boolean(readable.value && !locked.value && !detail.isFetching.value && !detail.error.value && !detailError.value
  && currentTask.value?.sourcesCurrent && currentTask.value.task.status === 'SUCCEEDED'
  && manifestMatches(currentTask.value.manifest, props.comparison, currentTask.value.comparison!.sourceIds, true)))

watch([readable, () => store.sessionEpoch, comparisonId], () => {
  generation++; clearPreview(); currentPage.value = undefined; currentTask.value = undefined; detailError.value = ''
}, { flush: 'sync' })
watch([requestSignature, contextSignature, () => JSON.stringify(settings.data.value?.configuration)], clearPreview, { flush: 'sync' })
watch(cursor, () => { pageSequence++; currentPage.value = undefined }, { flush: 'sync' })
watch(selectedTaskId, () => { taskSequence++; currentTask.value = undefined; detailError.value = '' }, { flush: 'sync' })
watch([tasks.data, tasks.dataUpdatedAt, readable, pageKey], () => {
  if (!alive || !readable.value) return
  const value = queryClient.getQueryData<AiTaskPage>(pageKey.value)
  if (value && pageValid(value)) currentPage.value = value
}, { immediate: true })
watch([detail.data, detail.dataUpdatedAt, readable, taskKey, contextSignature], () => {
  if (!alive || !readable.value || !selectedTaskId.value) return
  const value = queryClient.getQueryData<AiTask>(taskKey.value)
  if (!value) return
  if (!taskMatches(value, props.comparison, selectedTaskId.value)) { detailError.value = '缓存任务与当前对照不一致，请重新读取。'; return }
  const saved = currentTask.value
  if (saved && (saved.task.revision > value.task.revision || aiCandidateManifestFingerprint(saved.manifest) !== aiCandidateManifestFingerprint(value.manifest))) {
    detailError.value = '任务修订或固定清单与已读取记录不一致，请重新读取。'; return
  }
  // 同值缓存也须在重新登录或资料守卫恢复后接纳；不能只监听 data 引用变化。
  currentTask.value = value; detailError.value = ''
}, { immediate: true })
watch([() => selectedIds.value.length > 0 || consent.value, locked], ([dirty, waiting]) => {
  state.dirty = dirty; state.locked = waiting; store.changes.set(stateKey, state)
}, { immediate: true, flush: 'sync' })
watch(writer.error, error => {
  if (!(error instanceof ApiError) || writer.pending.value || receiptMismatch.value) return
  clearPreview()
  if (readable.value) { void tasks.refetch(); void settings.refetch() }
  if (error.status === 409) emit('refresh')
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; generation++; previewSequence++
  if (store.changes.get(stateKey) === state) store.changes.delete(stateKey)
})

function changed() { return new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '比较任务的读取条件已变化，请恢复访问后继续。') }
function mismatch(text: string) { return new ApiError(502, 'AI_COMPARISON_MISMATCH', text) }
function requireCurrent(epoch: number, stamp: number) { if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation) throw changed() }
function pageValid(value: AiTaskPage): boolean {
  try { return Boolean(value && Array.isArray(value.items) && value.items.length <= 20
    && value.items.every(item => reviewIdValid(item.id) && item.purpose === 'ANSWER_COMPARISON' && Object.hasOwn(aiStatusLabels, item.status)
      && Number.isSafeInteger(item.revision) && item.revision >= 0 && Number.isSafeInteger(item.attemptCount) && item.attemptCount >= 1 && item.attemptCount <= 3
      && typeof item.title === 'string' && typeof item.createdAt === 'string')
    && new Set(value.items.map(item => item.id)).size === value.items.length && (value.nextCursor === null || reviewIdValid(value.nextCursor))) } catch { return false }
}
function sourceProblem(id: string, comparison = props.comparison): string {
  const source = comparison.repractice.sources.find(item => item.sourceId === id), progress = comparison.progress.find(item => item.sourceId === id)
  if (!source || !progress) return '来源记录需要重新读取'
  if (progress.tracking !== 'TRACKING') return '已停止跟踪'
  return progress.verification !== source.source.verification ? '验证条件已变化' : ''
}
function projectedSide(side: RepracticeSide): AiComparisonSide {
  const input = repracticeInput(side), exposure = side.submission.priorExposure
  if (side.timing.pendingUnknownSeconds !== 0 || side.timing.pendingPausedSeconds !== 0) throw mismatch('比较中的训练时间尚未固定，请重新读取已结束的提交。')
  return { identity: side.answer, answerNumber: input.answerNumber, mode: side.mode, feedbackTiming: input.feedbackTiming,
    answerCondition: input.answerCondition, priorExposure: { authoredCount: exposure.authoredCount, referenceViewCount: exposure.referenceViewCount,
      evaluationAccessCount: exposure.evaluationAccessCount ?? 0, lastExposureAt: exposure.lastExposureAt },
    timeBudgetMinutes: side.config.timeBudgetMinutes, timing: { ...side.timing, pendingUnknownSeconds: 0, pendingPausedSeconds: 0 },
    question: input.question, answer: input.answer }
}
function sideSignature(side: AiComparisonSide): string {
  const exposure = side.priorExposure, timing = side.timing, question = side.question
  return JSON.stringify([side.identity.sessionId, side.identity.questionId, side.identity.answerId, side.answerNumber,
    side.mode, side.feedbackTiming, side.answerCondition, side.timeBudgetMinutes,
    [exposure.authoredCount, exposure.referenceViewCount, exposure.evaluationAccessCount, exposure.lastExposureAt],
    [timing.observedSeconds, timing.unobservedSeconds, timing.pausedSeconds, timing.pendingUnknownSeconds, timing.pendingPausedSeconds, timing.budgetReached, timing.asOf],
    [question.title, question.prompt, question.conditions, question.technology, question.scenario, question.type],
    [side.answer.text, side.answer.nodes.map(node => [node.id, node.label, node.description]), side.answer.edges.map(edge => [edge.id, edge.source, edge.target, edge.label])]])
}
function manifestMatches(manifest: Manifest, comparison: RepracticeComparison, sourceIds: string[], checkCurrent: boolean): boolean {
  try {
    if (!aiComparisonManifestMatches(manifest) || !repracticeComparisonValid(comparison, comparison.comparison.id)
      || repracticeBaselineKind(comparison.repractice) !== 'ANSWER' || !comparison.baseline) return false
    const input = manifest.comparisonInput!, checks = manifest.comparisonChecks!, sorted = [...sourceIds].sort()
    if (input.comparisonId !== comparison.comparison.id || input.sources.length !== sorted.length
      || sideSignature(input.baseline) !== sideSignature(projectedSide(comparison.baseline))
      || sideSignature(input.current) !== sideSignature(projectedSide(comparison.current))) return false
    const kinds: Record<keyof AiComparisonDifferences, string> = { questionChanged: 'QUESTION', referenceChanged: 'REFERENCE', contextChanged: 'CONTEXT',
      modeChanged: 'MODE', assistanceChanged: 'ASSISTANCE', exposureChanged: 'EXPOSURE', timeBudgetChanged: 'TIME_BUDGET', sessionTimeChanged: 'SESSION_TIME' }
    if (comparison.differences.length !== 8 || new Set(comparison.differences.map(item => item.kind)).size !== 8
      || (Object.keys(kinds) as (keyof AiComparisonDifferences)[]).some(key => comparison.differences.find(item => item.kind === kinds[key])?.changed !== input.differences[key])) return false
    return input.sources.every((source, index) => {
      const fixed = comparison.repractice.sources.find(item => item.sourceId === source.sourceId), progress = comparison.progress.find(item => item.sourceId === source.sourceId)
      const check = checks.sources[index]!
      return source.sourceId === sorted[index] && fixed?.source.verification === source.verification
        && (!checkCurrent || (!sourceProblem(source.sourceId, comparison) && progress?.actionId === check.actionId && progress.actionRevision === check.actionRevision))
    })
  } catch { return false }
}
function taskMatches(value: AiTask, comparison: RepracticeComparison, taskId: string): boolean {
  return aiComparisonTaskValid(value, comparison.comparison.id) && value.task.id === taskId
    && manifestMatches(value.manifest, comparison, value.comparison!.sourceIds, false)
}
function selectSource(id: string, checked: boolean) {
  if (blocked.value || previewing.value) return
  if (checked && !selectedIds.value.includes(id) && selectedIds.value.length < 10 && !sourceProblem(id)) selectedIds.value.push(id)
  else if (!checked) selectedIds.value = selectedIds.value.filter(value => value !== id)
  message.value = ''
}
function clearPreview() { previewSequence++; previewing.value = false; prepared.value = undefined; preparedRequest.value = ''; consent.value = false; previewError.value = '' }
async function prepare() {
  if (blocked.value || previewing.value || !configured.value || !historyReady.value || pendingTask.value || limitReached.value || problem.value) return
  clearPreview()
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++previewSequence, signature = requestSignature.value, context = contextSignature.value
  const body = JSON.parse(signature) as AiComparisonPreviewRequest
  previewing.value = true; message.value = ''
  try {
    const value = await preview<AiPreview>(`${path.value}/preview`, body)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || stamp !== generation || sequence !== previewSequence
      || signature !== requestSignature.value || context !== contextSignature.value) return
    if (!value || !manifestMatches(value.manifest, props.comparison, body.sourceIds, true) || !/^[0-9a-f]{64}$/.test(value.manifestHash)
      || !value.budget || !Number.isSafeInteger(value.budget.remainingMicros) || value.budget.remainingMicros < 0
      || value.budget.currency !== value.manifest.configuration.currency) throw mismatch('预览与当前双答案、所选来源或费用记录不一致，请重读来源后核对。')
    prepared.value = value; preparedRequest.value = signature
  } catch (error) {
    if (alive && epoch === store.sessionEpoch && sequence === previewSequence) {
      previewError.value = errorText(error)
      if (error instanceof ApiError && error.status === 409) { message.value = previewError.value; emit('refresh') }
    }
  } finally { if (alive && sequence === previewSequence) previewing.value = false }
}
function create() {
  if (blocked.value || previewing.value || !configured.value || !historyReady.value || pendingTask.value || limitReached.value
    || problem.value || !prepared.value || !previewMatches.value || !consent.value || overBudget.value) return
  const snapshot = JSON.parse(JSON.stringify(props.comparison)) as RepracticeComparison
  const body: AiComparisonCreate = { ...(JSON.parse(preparedRequest.value) as AiComparisonPreviewRequest), expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  const fingerprint = aiCandidateManifestFingerprint(prepared.value.manifest), target = `${path.value}/tasks`
  commandEpoch = store.sessionEpoch; message.value = ''
  let originalCommand: typeof writer.pending.value
  void writer.run<AiTask>(target, 'POST', body, value => {
    if (!alive) return
    const matches = aiComparisonTaskValid(value, snapshot.comparison.id)
      && manifestMatches(value.manifest, snapshot, body.sourceIds, true) && aiCandidateManifestFingerprint(value.manifest) === fingerprint
    // HTTP 成功也可能携带异常回执；保留原命令核对，不能换键再创建付费任务。
    if (!matches) {
      receiptCommand.value = originalCommand; receiptMismatch.value = true
      writer.error.value = mismatch('创建回执与已授权的双答案、来源或费用不一致。选择继续保留，请用原请求核对。')
      return
    }
    const receive = () => receiveTask(value, snapshot.comparison.id)
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = receive
    else receive()
  })
  originalCommand = writer.pending.value
}
function receiveTask(value: AiTask, id: string) {
  receiptMismatch.value = false; receiptCommand.value = undefined; writer.error.value = undefined; clearPreview()
  selectedIds.value = []; message.value = '比较任务已保存并授权一次尝试。建议生成后可在下方逐条核对，再填入人工判断草稿。'
  if (id === comparisonId.value) {
    selectedTaskId.value = value.task.id
    const cached = queryClient.getQueryData<AiTask>(taskKey.value)
    if (!cached || !taskMatches(cached, props.comparison, value.task.id) || cached.task.revision <= value.task.revision) queryClient.setQueryData(taskKey.value, value)
    cursor.value = undefined; previous.value = []; void tasks.refetch(); void detail.refetch()
  }
  void settings.refetch()
}
function retryCommand() {
  if (!readable.value || writer.busy.value) return
  if (receiptMismatch.value && receiptCommand.value) writer.pending.value = receiptCommand.value
  commandEpoch = store.sessionEpoch; void writer.retry()
}
function receiveDeferred() { if (readable.value && deferred.value) { const receive = deferred.value; deferred.value = undefined; receive() } }
function chooseTask(id: string) { if (readable.value && !locked.value && reviewIdValid(id)) selectedTaskId.value = id }
function refreshTasks() { if (readable.value) { void tasks.refetch(); if (selectedTaskId.value) void detail.refetch() } }
function nextPage() { if (readable.value && !locked.value && !tasks.isFetching.value && currentPage.value?.nextCursor) { previous.value.push(cursor.value); cursor.value = currentPage.value.nextCursor } }
function previousPage() { if (readable.value && !locked.value && !tasks.isFetching.value && previous.value.length) cursor.value = previous.value.pop() }
function fill(draft: AiComparisonDraft) {
  const task = currentTask.value
  if (!fillAllowed.value || !task || draft.taskId !== task.task.id || draft.comparisonId !== comparisonId.value
    || draft.answerId !== props.comparison.current.answer.answerId || !task.comparison?.sourceIds.includes(draft.sourceId)) return
  try { if (repracticeJudgeProblem(draft.outcome, draft.reason, draft.citations, props.comparison.current)) return } catch { return }
  emit('fill', draft)
}
</script>

<template>
  <section class="ai-comparison" aria-label="AI 复练比较建议">
    <header><h3>让 AI 辅助核对这次复练</h3><p class="field-help">选择需要验证的来源，先核对双答案的完整外发内容与费用，再授权生成逐条建议。建议保留“AI 未审”身份，最终由你判断。</p></header>
    <p v-if="!readable" class="notice" role="status">登录或资料访问条件变化，比较任务暂时遮挡，来源选择和未决请求继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="panel-content">
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value || (receiptMismatch && receiptCommand)" text :disabled="!readable" :loading="writer.busy.value" @click="retryCommand">{{ receiptMismatch ? '用原请求核对已保存任务' : '用原请求重试' }}</NButton></p>
      <p v-if="deferred" class="notice" role="status">服务器已确认创建，访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已保存任务</NButton></p>
      <section class="source-selection" aria-label="本次比较来源">
        <div class="section-heading"><h4>选择本次来源 · {{ selectedIds.length }} / 10</h4><NButton text size="small" :disabled="blocked || previewing" @click="emit('refresh')">重读来源状态</NButton></div>
        <p v-if="!comparison.repractice.sources.length" class="field-help">本次复练没有绑定动作来源，可继续阅读双答案并人工对照。</p>
        <ul v-else class="source-list"><li v-for="source in comparison.repractice.sources" :key="source.sourceId">
          <NCheckbox :checked="selectedIds.includes(source.sourceId)" :disabled="blocked || previewing || (!selectedIds.includes(source.sourceId) && (!!sourceProblem(source.sourceId) || selectedIds.length >= 10))" @update:checked="selectSource(source.sourceId, $event)">{{ source.actionTitle }}</NCheckbox>
          <p class="source-verification">{{ source.source.verification }}</p><span v-if="sourceProblem(source.sourceId)" class="field-help">{{ sourceProblem(source.sourceId) }}</span>
        </li></ul>
        <p class="field-help">每条来源按创建复练时的验证条件比较；预览会核对当前动作是否仍开放、来源是否仍在跟踪。更改选择或来源状态后需重新预览。</p>
        <p v-if="settings.error.value" class="notice error" role="alert">{{ errorText(settings.error.value) }}<NButton text :disabled="blocked || settings.isFetching.value" @click="settings.refetch()">重读 AI 配置</NButton></p>
        <p v-if="!configured" class="field-help">{{ settings.isPending.value ? '正在读取 AI 配置…' : 'AI 尚未启用或配置不完整，可先保留来源选择，并继续人工判断。' }}</p>
        <p v-if="pendingTask" class="notice">这份对照已有在途比较任务，请先查看下方记录。</p><p v-else-if="limitReached" class="notice">这份对照已达到 3 个任务上限，可继续阅读已有候选并人工判断。</p>
        <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || !historyReady || pendingTask || limitReached || !!problem" @click="prepare">预览完整外发清单</NButton><span v-if="problem && comparison.repractice.sources.length" class="field-help">{{ problem }}</span></div>
        <p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
      </section>
      <section v-if="prepared && previewMatches" class="consent-panel" aria-label="本次比较外发授权">
        <AiManifest :manifest="prepared.manifest" :budget="prepared.budget" />
        <p v-if="overBudget" class="notice error">本次预留超过当前剩余预算，请处理未决费用或调整预算后重新预览。</p>
        <AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || previewing || overBudget">我已核对上方双答案原文、作答条件与所选验证条件，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，并为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice>
        <div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="blocked || previewing || !configured || !historyReady || pendingTask || limitReached || !consent || overBudget" @click="create">授权本次比较并创建任务</NButton><NButton :disabled="blocked" @click="clearPreview">关闭预览</NButton></div>
      </section>
      <section class="task-history" aria-label="本对照的比较任务">
        <div class="section-heading"><h4>本对照的比较记录</h4><NButton text size="small" :disabled="!readable || tasks.isFetching.value" @click="refreshTasks">刷新记录</NButton></div>
        <p class="field-help" role="status">{{ updates.message }}</p>
        <p v-if="tasks.isPending.value && !currentPage" class="field-help" role="status">正在读取比较记录…</p><p v-if="tasks.error.value" class="notice error" role="alert">{{ errorText(tasks.error.value) }}</p>
        <p v-if="currentPage && !currentPage.items.length" class="field-help">本页还没有比较任务。选择来源并核对外发清单后，可以授权一次生成。</p>
        <ul class="task-list"><li v-for="item in currentPage?.items ?? []" :key="item.id"><div><strong>{{ aiTaskStatusLabel(item.status, item.purpose) }}</strong><span>{{ aiDate(item.createdAt) }} · 第 {{ item.attemptCount }} 次尝试</span></div><div class="row-actions"><NButton size="small" :disabled="!readable || locked" :aria-pressed="selectedTaskId === item.id" @click="chooseTask(item.id)">{{ selectedTaskId === item.id ? '当前所选' : '读取候选' }}</NButton><RouterLink class="text-link" :to="{ name: 'ai', query: { item: item.id } }">任务与费用</RouterLink></div></li></ul>
        <div v-if="previous.length || currentPage?.nextCursor" class="row-actions"><NButton size="small" :disabled="!readable || locked || tasks.isFetching.value || !previous.length" @click="previousPage">上一页</NButton><NButton size="small" :disabled="!readable || locked || tasks.isFetching.value || !currentPage?.nextCursor" @click="nextPage">下一页</NButton></div>
      </section>
      <section v-if="selectedTaskId" class="task-detail" aria-label="所选比较候选">
        <div class="section-heading"><h4>所选比较候选</h4><NButton text size="small" :disabled="!readable || detail.isFetching.value" @click="detail.refetch()">重读候选</NButton></div>
        <p v-if="detail.isPending.value && !currentTask" class="field-help" role="status">正在读取这次比较…</p><p v-if="detail.error.value || detailError" class="notice error" role="alert">{{ detailError || errorText(detail.error.value) }}</p>
        <AiDraftPreview v-if="currentTask" :task-id="selectedTaskId" :attempt="draftAttempt" :task-status="currentTask.task.status" :streaming="currentTask.manifest.configuration.streaming" :disabled="!readable || currentTask.task.id !== selectedTaskId || !!detail.error.value || !!detailError" @refresh="detail.refetch()" />
        <template v-if="currentTask"><p class="field-help">{{ aiTaskStatusLabel(currentTask.task.status, currentTask.task.purpose) }} · {{ aiDate(currentTask.task.createdAt) }}</p><p v-if="currentTask.sourcesCurrent && !fillAllowed && !detail.isFetching.value && currentTask.task.status === 'SUCCEEDED'" class="notice">当前来源或读取条件需要重新核对，候选保留供阅读，暂不能填入判断草稿。</p><AiComparisonCandidate :task="currentTask" :allow-fill="fillAllowed" :disabled="!readable || locked || detail.isFetching.value || !!detail.error.value || !!detailError" @fill="fill" /><details class="saved-manifest"><summary>查看这次任务的完整授权清单</summary><AiManifest :manifest="currentTask.manifest" /></details></template>
        <RouterLink class="text-link" :to="{ name: 'ai', query: { item: selectedTaskId } }">进入任务记录，取消、重新授权或核对费用</RouterLink>
      </section>
      <p class="field-help">来源选择与授权勾选只保留在本页。候选填入后仍需在对应来源下方核对，并由你确认保存判断。</p>
    </div>
  </section>
</template>

<style scoped>
.ai-comparison, .panel-content, .source-selection, .task-history, .task-detail, .consent-panel { display: grid; gap: 16px; min-width: 0; }
.ai-comparison { border-top: 1px solid var(--line); padding-top: 24px; } .panel-content { gap: 24px; }
header { display: grid; gap: 9px; } h3 { margin: 0; font-size: 18px; } h4 { margin: 0; font-size: 15px; } p { margin: 0; }
.section-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.source-list, .task-list { list-style: none; margin: 0; padding: 0; } .source-list li { display: grid; gap: 8px; padding: 13px 0; border-bottom: 1px solid var(--line); }
.source-verification { padding-left: 25px; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.8; max-width: 75ch; }
.row-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; } .consent-panel, .task-detail { border-top: 1px solid var(--line); padding-top: 22px; }
.task-list li { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 14px; padding: 14px 0; border-bottom: 1px solid var(--line); }
.task-list li > div:first-child { display: grid; gap: 6px; min-width: 0; } .task-list strong { font-size: 13px; } .task-list span { font-size: 12px; color: var(--muted); }
.saved-manifest { min-width: 0; } .saved-manifest summary { cursor: pointer; font-size: 13px; padding: 10px 0; } .saved-manifest summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }
@media (max-width: 600px) { .source-verification { padding-left: 0; } .task-list li { align-items: start; } }
</style>
