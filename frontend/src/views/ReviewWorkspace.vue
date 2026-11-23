<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NSelect } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import { queryClient } from '../api/queryClient'
import type { AiEvaluationAccess, AiEvaluationInput, AiEvaluationPage, AiTask } from '../api/ai'
import { aiTaskStatusLabel } from '../api/ai'
import type { ReviewActionCandidate, ReviewAnswerLink, ReviewFeedback, ReviewFeedbackPage, ReviewSourceRequest } from '../api/review'
import { reviewAnswerKey, reviewAnswerMatches, reviewAnswerQuery, reviewDate, reviewIdValid, reviewKindLabels } from '../api/review'
import type { RelationAiFeedbackEvidenceSource } from '../api/relationAiFeedbackEvidence'
import { relationAiFeedbackEvidencePaths, relationAiFeedbackEvidenceSourceValid } from '../api/relationAiFeedbackEvidence'
import type { TrainingAnswerPage, TrainingSession, TrainingSessionPage } from '../api/training'
import { notifyTrainingChanged, trainingModeLabels, trainingStatusLabels } from '../api/training'
import { useCommand } from '../composables/useCommand'
import { useRecentVisit } from '../composables/useRecentVisit'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import ReviewFeedbackEditor from '../components/review/ReviewFeedbackEditor.vue'
import AiEvaluationReport from '../components/ai/AiEvaluationReport.vue'
import RIcon from '../replica/RIcon.vue'

const ReviewActions = defineAsyncComponent(() => import('../components/review/ReviewActions.vue'))
const ReviewedObservationWorkspace = defineAsyncComponent(() => import('../components/review/ReviewedObservationWorkspace.vue'))
const route = useRoute(), router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore()
const writer = useCommand()
const routeText = (key: string) => typeof route.query[key] === 'string' ? route.query[key] as string : undefined
const sessionId = computed(() => routeText('sessionId'))
const questionId = computed(() => routeText('questionId'))
const answerId = computed(() => routeText('answerId'))
const feedbackId = computed(() => routeText('feedbackId'))
const taskId = computed(() => routeText('taskId'))
const strictAiRequested = computed(() => route.query.aiAttemptId !== undefined || route.query.aiReportHash !== undefined)
const actionId = computed(() => routeText('action'))
const reviewedObservationId = computed(() => routeText('reviewedObservationId'))
const reviewedRevision = computed(() => {
  const text = routeText('reviewedRevision')
  return text && /^(0|[1-9]\d{0,15})$/.test(text) && Number.isSafeInteger(Number(text)) ? Number(text) : undefined
})
const feedbackRevision = computed(() => {
  const text = routeText('feedbackRevision') ?? routeText('revision')
  return text && /^[1-9]\d*$/.test(text) && Number(text) <= 100 ? Number(text) : undefined
})
const routeProblem = computed(() => {
  if (['sessionId', 'questionId', 'answerId', 'feedbackId', 'taskId', 'action', 'reviewedObservationId'].some(key => route.query[key] !== undefined && !reviewIdValid(route.query[key]))) return '复盘链接的来源标识无效，请从下方训练历史重新选择。'
  if ((questionId.value && !sessionId.value) || (answerId.value && !questionId.value) || ((feedbackId.value || taskId.value || reviewedObservationId.value) && !answerId.value)) return '复盘链接缺少确切提交信息，请重新选择训练、题目与提交。'
  if ((route.query.feedbackRevision !== undefined || route.query.revision !== undefined) && feedbackRevision.value === undefined) return '反馈修订须为 1–100 的整数。'
  if (route.query.observationId !== undefined && (typeof route.query.observationId !== 'string' || !/^f([1-9]|1[0-2])$/.test(route.query.observationId) || !taskId.value)) return 'AI 观察链接不完整，请先选择对应报告。'
  if (strictAiRequested.value && (!reviewIdValid(route.query.aiAttemptId) || typeof route.query.aiReportHash !== 'string'
    || !/^[0-9a-f]{64}$/.test(route.query.aiReportHash) || !taskId.value || !routeText('observationId') || !answerId.value)) return 'AI 搜索来源链接须完整包含成功执行、报告摘要、观察编号及确切提交，请回搜索结果重新打开。'
  if (route.query.reviewedRevision !== undefined && (!reviewedObservationId.value || reviewedRevision.value === undefined)) return '受审观察修订须为非负安全整数，并同时指定观察记录。'
  return ''
})
const link = computed<ReviewAnswerLink | undefined>(() => !routeProblem.value && sessionId.value && questionId.value && answerId.value
  ? { sessionId: sessionId.value, questionId: questionId.value, answerId: answerId.value } : undefined)
const sourceIdentity = computed(() => link.value ? reviewAnswerKey(link.value) : `${sessionId.value ?? ''}/${questionId.value ?? ''}`)
const strictAiLink = computed(() => strictAiRequested.value && !routeProblem.value && link.value ? {
  taskId: taskId.value!, attemptId: routeText('aiAttemptId')!, reportHash: routeText('aiReportHash')!,
  feedbackId: routeText('observationId')!, answer: { ...link.value },
} : undefined)
const strictAiIdentity = computed(() => {
  const selected = strictAiLink.value
  return selected ? `${reviewAnswerKey(selected.answer)}/${selected.taskId}/${selected.attemptId}/${selected.reportHash}/${selected.feedbackId}` : undefined
})
const readable = computed(() => store.sessionActive && !access.checking && !access.challenge && !access.restricted && !store.changes.get('training-access-command')?.locked)
const recordRecentVisit = useRecentVisit('TRAINING', sessionId, readable)
const tab = ref<'feedback' | 'actions'>(route.query.action ? 'actions' : 'feedback')
const sessionCursor = ref<string>(), sessionCursors = ref<(string | undefined)[]>([])
const answerBefore = ref<number>(), answerCursors = ref<(number | undefined)[]>([])
const feedbackCursor = ref<string>(), feedbackCursors = ref<(string | undefined)[]>([])
const taskCursor = ref<string>(), taskCursors = ref<(string | undefined)[]>([])
const creating = ref(false)
const accessConfirmed = ref(false), earlyConfirmed = ref(false)
const currentAccess = shallowRef<AiEvaluationAccess>()
const currentInput = shallowRef<AiEvaluationInput>()
const currentTask = shallowRef<AiTask>()
const strictAiSource = shallowRef<RelationAiFeedbackEvidenceSource>()
const strictAiLoading = ref(false), strictAiError = ref('')
const selectedObservation = ref(routeText('observationId') ?? '')
const selectedSource = shallowRef<ReviewSourceRequest | null>(null)
const actionsState = ref({ dirty: false, locked: false })
const aiLocked = ref(false)
const receiptMismatch = ref(false)
const deferred = shallowRef<() => void>()
const message = ref('')
const draftState = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, strictAiGeneration = 0, commandEpoch = store.sessionEpoch
const ownLocked = computed(() => writer.locked.value || Boolean(deferred.value))
const feedbackLocked = computed(() => Boolean(store.changes.get('review-feedback-command')?.locked || store.changes.get('review-decision-command')?.locked))
const observationKeys = computed(() => [...store.changes.keys()].filter(key => key.startsWith('reviewed-observation:')))
const observationLocked = computed(() => observationKeys.value.some(key => store.changes.get(key)?.locked))
const peerLocked = computed(() => actionsState.value.locked || aiLocked.value || feedbackLocked.value || observationLocked.value)
const blocked = computed(() => !readable.value || ownLocked.value || peerLocked.value)
const allowed = computed(() => Boolean(link.value && currentAccess.value?.allowed && reviewAnswerMatches(currentAccess.value, link.value)))
const sessionList = useQuery({
  queryKey: computed(() => ['review', store.sessionEpoch, 'sessions', sessionCursor.value]), enabled: readable,
  queryFn: async () => {
    const epoch = store.sessionEpoch, cursor = sessionCursor.value, stamp = generation
    const value = await read<TrainingSessionPage>(`/training/sessions?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
    requireCurrent(epoch, stamp)
    if (cursor !== sessionCursor.value) throw changedContext()
    return value
  },
})
const session = useQuery({
  queryKey: computed(() => ['review', store.sessionEpoch, 'session', sessionId.value]),
  enabled: computed(() => readable.value && !routeProblem.value && Boolean(sessionId.value)),
  queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, id = sessionId.value
    const value = await read<TrainingSession>(`/training/sessions/${id}`)
    requireCurrent(epoch, stamp)
    if (id !== sessionId.value || value.id !== id) throw new ApiError(502, 'REVIEW_SESSION_MISMATCH', '训练记录与当前选择不一致。')
    return value
  },
})
const questionOptions = computed(() => session.data.value?.questions.map(question => ({ value: question.id, label: `${question.title} · ${question.answerCount} 次提交` })) ?? [])
const knownQuestion = computed(() => Boolean(session.data.value?.questions.some(question => question.id === questionId.value)))
const answers = useQuery({
  queryKey: computed(() => ['review', store.sessionEpoch, 'answers', sessionId.value, questionId.value, answerBefore.value]),
  enabled: computed(() => readable.value && !routeProblem.value && knownQuestion.value),
  queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, sid = sessionId.value, qid = questionId.value, before = answerBefore.value
    const value = await read<TrainingAnswerPage>(`/training/sessions/${sid}/questions/${qid}/answers?limit=20${before === undefined ? '' : `&before=${before}`}`)
    requireCurrent(epoch, stamp)
    if (sid !== sessionId.value || qid !== questionId.value || before !== answerBefore.value) throw changedContext()
    return value
  },
})
const accessPath = computed(() => `/training/sessions/${sessionId.value}/questions/${questionId.value}/evaluations/access`)
const accessQuery = useQuery({
  queryKey: computed(() => ['review', store.sessionEpoch, 'access', sourceIdentity.value]),
  enabled: computed(() => readable.value && Boolean(link.value)),
  queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, selected = link.value!
    const value = await read<AiEvaluationAccess>(`${accessPath.value}?answerId=${selected.answerId}`)
    requireCurrent(epoch, stamp)
    if (!reviewAnswerMatches(value, selected) || !reviewAnswerMatches(selected, link.value)) throw new ApiError(502, 'REVIEW_ACCESS_MISMATCH', '访问记录与当前提交不一致。')
    return value
  },
})
const context = useQuery({
  queryKey: computed(() => ['review', store.sessionEpoch, 'context', sourceIdentity.value]),
  enabled: computed(() => readable.value && allowed.value),
  queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, selected = link.value!
    const value = await read<AiEvaluationInput>(`/review/context?${reviewAnswerQuery(selected)}`)
    requireCurrent(epoch, stamp)
    if (!reviewAnswerMatches(value, selected) || !reviewAnswerMatches(selected, link.value) || !value.question || typeof value.question.prompt !== 'string'
      || !value.answer || typeof value.answer.text !== 'string' || !Array.isArray(value.answer.nodes) || !Array.isArray(value.answer.edges)) throw new ApiError(502, 'REVIEW_CONTEXT_MISMATCH', '复盘原文与当前提交不一致。')
    return value
  },
})
const feedback = useQuery({
  queryKey: computed(() => ['review', store.sessionEpoch, 'feedback-list', sourceIdentity.value, feedbackCursor.value]),
  enabled: computed(() => readable.value && allowed.value),
  queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, selected = link.value!, cursor = feedbackCursor.value
    const parameters = reviewAnswerQuery(selected); parameters.set('limit', '20'); if (cursor) parameters.set('cursor', cursor)
    const value = await read<ReviewFeedbackPage>(`/review/feedback?${parameters}`)
    requireCurrent(epoch, stamp)
    if (!reviewAnswerMatches(selected, link.value) || cursor !== feedbackCursor.value || value.items.some(item => !reviewAnswerMatches(item.answer, selected))) throw changedContext()
    return value
  },
})
const tasks = useQuery({
  queryKey: computed(() => ['review', store.sessionEpoch, 'evaluations', sourceIdentity.value, taskCursor.value]),
  enabled: computed(() => readable.value && allowed.value),
  queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, selected = link.value!, cursor = taskCursor.value
    const value = await read<AiEvaluationPage>(`/training/sessions/${selected.sessionId}/evaluations?questionId=${selected.questionId}&limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
    requireCurrent(epoch, stamp)
    if (!reviewAnswerMatches(selected, link.value) || cursor !== taskCursor.value) throw changedContext()
    return value
  },
})
const exactTasks = computed(() => tasks.data.value?.items.filter(item => item.answerId === answerId.value && item.questionId === questionId.value) ?? [])
const task = useQuery({
  queryKey: computed(() => ['review', store.sessionEpoch, 'ai-task', taskId.value, sourceIdentity.value]),
  enabled: computed(() => readable.value && allowed.value && !routeProblem.value && !strictAiRequested.value && Boolean(taskId.value)),
  queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, id = taskId.value, selected = link.value!
    const value = await read<AiTask>(`/ai/tasks/${id}`)
    requireCurrent(epoch, stamp)
    if (strictAiRequested.value || routeProblem.value || id !== taskId.value || !reviewAnswerMatches(selected, link.value) || value.task.id !== id || value.task.purpose !== 'ANSWER_EVALUATION' || !reviewAnswerMatches(value.manifest.evaluation, selected)) throw new ApiError(502, 'REVIEW_AI_MISMATCH', 'AI 报告与所选提交不一致。')
    return value
  },
})
const early = computed(() => session.data.value?.status !== 'ENDED' && session.data.value?.config.feedbackTiming === 'AFTER_SESSION')
const answerConditions = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
const observationOptions = computed(() => currentTask.value?.evaluationReport?.observations.map(item => ({ value: item.id, label: `${item.id} · ${item.observation}` })) ?? [])
const verifiedAiObservation = computed(() => readable.value && allowed.value && strictAiRequested.value && strictAiSource.value && strictAiLink.value
  && currentTask.value && strictTaskMatches(currentTask.value, strictAiSource.value) ? strictAiLink.value.feedbackId : undefined)

// 最近访问对应提交复盘中已接纳的训练会话，动作页隐藏的详情须等用户切回后才接纳。
watch([session.data, session.dataUpdatedAt, readable, sessionId, () => store.sessionEpoch, routeProblem, tab], ([, , canRead, id, epoch]) => {
  if (!alive || !canRead || routeProblem.value || tab.value !== 'feedback' || !id || epoch !== store.sessionEpoch) return
  const value = queryClient.getQueryData<TrainingSession>(['review', epoch, 'session', id])
  if (value?.id === id) recordRecentVisit(value.id)
}, { immediate: true })

// 缓存可能先于观察者存在；访问恢复时按当前会话和确切来源取数，不能依赖正文引用变化。
watch([accessQuery.data, accessQuery.dataUpdatedAt, readable, sourceIdentity, () => store.sessionEpoch], ([, , canRead, identity, epoch]) => {
  const selected = link.value
  if (!alive || !canRead || !selected || identity !== reviewAnswerKey(selected) || epoch !== store.sessionEpoch) return
  const value = queryClient.getQueryData<AiEvaluationAccess>(['review', epoch, 'access', identity])
  if (value && reviewAnswerMatches(value, selected)) currentAccess.value = value
}, { immediate: true })
watch([context.data, context.dataUpdatedAt, readable, allowed, sourceIdentity, () => store.sessionEpoch], ([, , canRead, canOpen, identity, epoch]) => {
  const selected = link.value
  if (!alive || !canRead || !canOpen || !selected || identity !== reviewAnswerKey(selected) || epoch !== store.sessionEpoch) return
  const value = queryClient.getQueryData<AiEvaluationInput>(['review', epoch, 'context', identity])
  if (value && reviewAnswerMatches(value, selected)) currentInput.value = value
}, { immediate: true })
watch([task.data, task.dataUpdatedAt, readable, allowed, sourceIdentity, taskId, () => store.sessionEpoch, strictAiRequested], ([, , canRead, canOpen, identity, id, epoch]) => {
  const selected = link.value
  if (!alive || !canRead || !canOpen || strictAiRequested.value || routeProblem.value || !selected || !id || identity !== reviewAnswerKey(selected) || epoch !== store.sessionEpoch) return
  const value = queryClient.getQueryData<AiTask>(['review', epoch, 'ai-task', id, identity])
  if (value && value.task.id === id && value.task.purpose === 'ANSWER_EVALUATION' && reviewAnswerMatches(value.manifest.evaluation, selected)) currentTask.value = value
}, { immediate: true })
watch(sourceIdentity, () => {
  currentAccess.value = undefined; currentInput.value = undefined; currentTask.value = undefined
  accessConfirmed.value = false; earlyConfirmed.value = false; receiptMismatch.value = false; creating.value = false
  feedbackCursor.value = undefined; feedbackCursors.value = []; taskCursor.value = undefined; taskCursors.value = []
  selectedSource.value = null; message.value = ''
  if (!actionId.value) tab.value = 'feedback'
}, { flush: 'sync' })
watch([sessionId, questionId], () => { answerBefore.value = undefined; answerCursors.value = [] }, { flush: 'sync' })
watch(taskId, () => { currentTask.value = undefined; selectedSource.value = null }, { flush: 'sync' })
watch(() => route.query.observationId, () => { selectedObservation.value = routeText('observationId') ?? ''; selectedSource.value = null }, { flush: 'sync' })
watch(feedbackId, () => { creating.value = false; selectedSource.value = null; if (feedbackId.value && !actionId.value) tab.value = 'feedback' }, { flush: 'sync' })
watch([taskId, () => route.query.observationId, feedbackRevision], () => { if (!actionId.value) tab.value = 'feedback' }, { flush: 'sync' })
watch(() => route.query.action, value => { if (value) tab.value = 'actions' })
watch([reviewedObservationId, reviewedRevision], () => { if (reviewedObservationId.value && !actionId.value) tab.value = 'feedback' })
watch([readable, () => store.sessionEpoch], () => { generation++; accessConfirmed.value = false; earlyConfirmed.value = false; selectedSource.value = null }, { flush: 'sync' })
watch([strictAiIdentity, strictAiRequested, readable, allowed, () => store.sessionEpoch], ([, requested, canRead, canOpen], previous) => {
  strictAiGeneration++
  strictAiSource.value = undefined; strictAiError.value = ''; strictAiLoading.value = false
  if (requested || previous?.[1]) currentTask.value = undefined
  if (requested && strictAiLink.value && canRead && canOpen) void loadStrictAi()
}, { immediate: true, flush: 'sync' })
watch([accessConfirmed, earlyConfirmed, ownLocked], ([confirmed, early, waiting]) => {
  draftState.dirty = confirmed || early; commandState.locked = waiting
  store.changes.set('review-access', draftState); store.changes.set('review-access-command', commandState)
}, { immediate: true, flush: 'sync' })
onUnmounted(() => {
  alive = false; generation++; strictAiGeneration++
  strictAiSource.value = undefined
  if (store.changes.get('review-access') === draftState) store.changes.delete('review-access')
  if (store.changes.get('review-access-command') === commandState) store.changes.delete('review-access-command')
})
function changedContext() { return new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '复盘来源或访问条件已变化，请重新读取。') }
function requireCurrent(epoch: number, stamp: number) { if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation) throw changedContext() }
function strictTaskMatches(value: AiTask, source: RelationAiFeedbackEvidenceSource): boolean {
  try {
    const exact = strictAiLink.value, input = value.manifest.evaluation, report = value.evaluationReport
    const successful = value.attempts.filter(attempt => attempt.status === 'SUCCEEDED')
    const observation = report?.observations.find(item => item.id === source.feedbackId)
    return Boolean(exact && source.taskId === exact.taskId && source.attemptId === exact.attemptId && source.reportHash === exact.reportHash
      && source.feedbackId === exact.feedbackId && reviewAnswerMatches(source.answer, exact.answer)
      && value.task.id === source.taskId && value.task.purpose === 'ANSWER_EVALUATION' && value.task.status === 'SUCCEEDED'
      && successful.length === 1 && successful[0]!.id === source.attemptId && reviewAnswerMatches(input, source.answer)
      && input?.answerNumber === source.answerNumber && input.answerCondition === source.answerCondition
      && report && report.level === source.level && report.limitations === source.reportLimitations && report.validationNote === source.validationNote
      && observation && observation.dimension === source.dimension && observation.coverage === source.coverage
      && observation.observation === source.observation && observation.condition === source.condition && observation.basis === source.basis
      && observation.limitation === source.limitation && observation.suggestion === source.suggestion)
  } catch { return false }
}
function verifiedStrictTask(value: AiTask): boolean {
  return Boolean(readable.value && allowed.value && strictAiSource.value && strictTaskMatches(value, strictAiSource.value))
}
async function loadStrictAi() {
  const exact = strictAiLink.value
  if (!readable.value || !allowed.value || !exact) return
  const request = ++strictAiGeneration, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), identity = strictAiIdentity.value
  strictAiSource.value = undefined; currentTask.value = undefined; strictAiError.value = ''; strictAiLoading.value = true
  const current = () => alive && readable.value && allowed.value && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
    && request === strictAiGeneration && identity === strictAiIdentity.value
  try {
    // 严格回链先核原报告字节摘要与成功执行；普通任务缓存不能替代这次来源确认。
    const source = await read<RelationAiFeedbackEvidenceSource>(relationAiFeedbackEvidencePaths.source(exact.taskId, exact.feedbackId))
    if (!current()) return
    if (!relationAiFeedbackEvidenceSourceValid(source, exact.taskId, exact.feedbackId,
      { taskId: exact.taskId, feedbackId: exact.feedbackId, answer: exact.answer })
      || source.attemptId !== exact.attemptId || source.reportHash !== exact.reportHash) {
      throw new ApiError(409, 'REVIEW_AI_SOURCE_CHANGED', 'AI 观察的成功执行、报告摘要或原答与搜索来源不一致，请回搜索重新核对。')
    }
    const value = await read<AiTask>(`/ai/tasks/${exact.taskId}`)
    if (!current()) return
    if (!strictTaskMatches(value, source)) throw new ApiError(409, 'REVIEW_AI_SOURCE_CHANGED', '报告与已核对的确切观察不一致，未展示其他报告，请重新核对来源。')
    strictAiSource.value = source
    currentTask.value = value
  } catch (reason) { if (current()) strictAiError.value = errorText(reason) }
  finally { if (request === strictAiGeneration) strictAiLoading.value = false }
}
function selectSession(id: string) { if (!blocked.value) void router.push({ name: 'review', query: { sessionId: id } }) }
function selectQuestion(id: string) { if (!blocked.value && knownSession()) void router.push({ name: 'review', query: { sessionId: sessionId.value, questionId: id } }) }
function knownSession() { return session.data.value?.id === sessionId.value }
function selectAnswer(id: string) {
  if (!blocked.value && !answers.isFetching.value && !answers.error.value && answers.data.value?.items.some(item => item.id === id)) void router.push({ name: 'review', query: { sessionId: sessionId.value, questionId: questionId.value, answerId: id } })
}
function openFeedback(id: string) { if (!blocked.value && link.value) void router.push({ name: 'review', query: { ...link.value, feedbackId: id } }) }
function closeFeedback() {
  if (blocked.value || !store.canLeave(['review-feedback', 'review-feedback-command', 'review-decision', 'review-decision-command'])) return
  if (creating.value) creating.value = false
  else if (link.value) void router.push({ name: 'review', query: { ...link.value } })
}
function newFeedback() {
  if (blocked.value || !currentInput.value || !store.canLeave(['review-feedback', 'review-feedback-command', 'review-decision', 'review-decision-command'])) return
  creating.value = true
}
function createdFeedback(value: ReviewFeedback) { creating.value = false; openFeedback(value.id) }
function openAction(id: string) { if (readable.value && reviewIdValid(id)) void router.push({ name: 'review', query: { ...route.query, action: id } }) }
function closeAction() { if (readable.value) { const query = { ...route.query }; delete query.action; void router.push({ name: 'review', query }) } }
function openTask(id: string) { if (!blocked.value && link.value) void router.push({ name: 'review', query: { ...link.value, taskId: id } }) }
function createFactDraft() {
  if (blocked.value || !allowed.value || !link.value || !currentInput.value || context.isFetching.value || context.error.value
    || !reviewAnswerMatches(currentInput.value, link.value)) return
  // 入口只固定确切提交身份；题面、评价与个人参考由下一页明确排除。
  void router.push({ name: 'ai', query: { create: '1', purpose: 'FACT_DRAFT', factSourceKind: 'TRAINING', ...link.value } })
}
function createRewrite() {
  if (blocked.value || !allowed.value || !link.value || !currentInput.value || context.isFetching.value || context.error.value
    || !reviewAnswerMatches(currentInput.value, link.value)) return
  void router.push({ name: 'ai', query: { create: '1', purpose: 'ANSWER_REWRITE', ...link.value } })
}
function refreshedFeedback() { selectedSource.value = null; void feedback.refetch() }
function aiChanged(value: AiTask) {
  if (value.task.id !== taskId.value || !reviewAnswerMatches(value.manifest.evaluation, link.value)) return
  if (strictAiRequested.value) {
    if (!readable.value || !allowed.value || !strictAiSource.value) return
    if (!strictTaskMatches(value, strictAiSource.value)) {
      currentTask.value = undefined; strictAiSource.value = undefined
      strictAiError.value = '决定回执与已确认的报告来源不一致，请重新核对确切报告。'
      return
    }
    currentTask.value = value; selectedSource.value = null
    return
  }
  currentTask.value = value; selectedSource.value = null
  queryClient.setQueryData(['review', store.sessionEpoch, 'ai-task', value.task.id, sourceIdentity.value], value)
}
function chooseSource(candidate: ReviewActionCandidate) {
  if (blocked.value || !reviewAnswerMatches(candidate.answer, link.value)) return
  selectedSource.value = { ...candidate.source, verification: '' }; tab.value = 'actions'
}
function chooseAiSource() {
  const saved = currentTask.value, observation = saved?.evaluationReport?.observations.find(item => item.id === selectedObservation.value)
  if (blocked.value || !saved || saved.task.status !== 'SUCCEEDED' || !observation || !reviewAnswerMatches(saved.manifest.evaluation, link.value)) return
  const revision = Math.max(0, ...(saved.evaluationDecisions ?? []).filter(item => item.feedbackId === observation.id).map(item => item.revision))
  selectedSource.value = { kind: 'AI_EVALUATION', taskId: saved.task.id, observationId: observation.id, feedbackId: null, feedbackRevision: null, expectedDecisionRevision: revision, verification: '' }
  tab.value = 'actions'
}
function grantAccess() {
  if (blocked.value || receiptMismatch.value || !link.value || !session.data.value || !accessConfirmed.value || (early.value && !earlyConfirmed.value)) return
  const selected = { ...link.value }, expected = session.data.value.revision
  commandEpoch = store.sessionEpoch; message.value = ''
  void writer.run<AiEvaluationAccess>(accessPath.value, 'POST', { answerId: selected.answerId, expectedSessionRevision: expected, confirmed: true, earlyFeedbackConfirmed: earlyConfirmed.value }, value => {
    if (!alive) return
    if (!reviewAnswerMatches(value, selected) || !value.allowed) {
      receiptMismatch.value = true
      throw new ApiError(502, 'REVIEW_ACCESS_MISMATCH', '访问确认回执与所选提交不一致，原请求保留，请用原请求重试。')
    }
    receiptMismatch.value = false
    const apply = () => {
      currentAccess.value = value; accessConfirmed.value = false; earlyConfirmed.value = false
      message.value = '已确认打开这次提交的复盘原文与个人参考，尚未调用模型。'
      queryClient.setQueryData(['review', store.sessionEpoch, 'access', reviewAnswerKey(selected)], value)
      void session.refetch(); notifyTrainingChanged()
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = apply
    else apply()
  })
}
async function recheckAccess() {
  if (blocked.value) return
  const result = await accessQuery.refetch()
  await session.refetch()
  if (!result.error && result.data && reviewAnswerMatches(result.data, link.value)) { currentAccess.value = result.data; receiptMismatch.value = false; writer.error.value = undefined; accessConfirmed.value = false; earlyConfirmed.value = false }
}
function retryAccess() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const apply = deferred.value; deferred.value = undefined; apply() } }
function nextSessions() { if (!blocked.value && !sessionList.isFetching.value && sessionList.data.value?.nextCursor) { sessionCursors.value.push(sessionCursor.value); sessionCursor.value = sessionList.data.value.nextCursor } }
function nextAnswers() { if (!blocked.value && !answers.isFetching.value && answers.data.value?.nextCursor != null) { answerCursors.value.push(answerBefore.value); answerBefore.value = answers.data.value.nextCursor } }
function nextFeedback() { if (!blocked.value && !feedback.isFetching.value && feedback.data.value?.nextCursor) { feedbackCursors.value.push(feedbackCursor.value); feedbackCursor.value = feedback.data.value.nextCursor } }
function nextTasks() { if (!blocked.value && !tasks.isFetching.value && tasks.data.value?.nextCursor) { taskCursors.value.push(taskCursor.value); taskCursor.value = tasks.data.value.nextCursor } }
</script>

<template>
  <div class="review-product">
  <header class="page-heading"><div><h1>复盘</h1><p class="muted">从一次确切提交出发，保留观察依据，明确下一步如何验证。</p></div></header>
  <nav class="review-tabs" aria-label="当前复盘内容"><button :class="{ active: tab === 'feedback' }" :aria-pressed="tab === 'feedback'" @click="tab = 'feedback'">回答与反馈</button><button :class="{ active: tab === 'actions' }" :aria-pressed="tab === 'actions'" @click="tab = 'actions'">改进动作</button><RouterLink v-if="link && session.data.value?.status === 'ENDED'" class="text-link" :to="{ name: 'repractice', query: { ...link } }">用这次回答建立复练 →</RouterLink></nav>
  <p v-if="!readable" class="review-note notice" role="status">正在核对登录与资料访问条件。当前输入保留，复盘资料暂不可读写。</p>
  <p v-if="routeProblem" class="review-note notice error" role="alert">{{ routeProblem }}</p>
  <div class="review-body" :class="{ 'has-answer': tab === 'feedback' && link && allowed && currentInput }">
  <div v-show="tab === 'feedback'" class="review-workspace" :class="{ focused: link, 'has-session': sessionId }" :inert="!readable || undefined">
    <details class="review-catalog" :open="!link" aria-label="训练历史">
      <summary>训练记录</summary>
      <p class="field-help">选择一场训练，再查看题目与已提交的回答。</p>
      <p v-if="sessionList.isPending.value" role="status">正在读取训练历史…</p><p v-if="sessionList.error.value" class="notice error" role="alert">{{ errorText(sessionList.error.value) }}<NButton text :disabled="blocked" @click="sessionList.refetch()">重新读取</NButton></p>
      <p v-if="sessionList.data.value && !sessionList.data.value.items.length" class="field-help">还没有训练记录。先完成一次文字提交，再回到这里复盘。</p>
      <div class="session-list"><button v-for="item in sessionList.data.value?.items" :key="item.id" :disabled="blocked" :class="{ selected: item.id === sessionId }" :aria-current="item.id === sessionId ? 'true' : undefined" @click="selectSession(item.id)"><span class="review-record-content"><strong>{{ item.title }}</strong><span>{{ trainingModeLabels[item.mode] }} · {{ trainingStatusLabels[item.status] }}</span></span><small>{{ reviewDate(item.createdAt) }}</small><RIcon name="chevron-right" :size="16" /></button></div>
      <div class="row-actions"><NButton v-if="sessionCursors.length" size="small" :disabled="blocked || sessionList.isFetching.value" @click="sessionCursor = sessionCursors.pop()">上一页</NButton><NButton v-if="sessionList.data.value?.nextCursor" size="small" :disabled="blocked || sessionList.isFetching.value" @click="nextSessions">下一页</NButton></div>
      <RouterLink class="text-link" :to="{ name: 'training' }">进入文字训练</RouterLink>
    </details>
    <section class="review-stage">
      <template v-if="sessionId && !routeProblem">
        <p v-if="session.isPending.value" role="status">正在读取所选训练…</p><p v-if="session.error.value" class="notice error" role="alert">{{ errorText(session.error.value) }}<NButton text :disabled="blocked" @click="session.refetch()">重新读取训练</NButton></p>
        <details class="review-source-details" :open="!link"><summary>训练、题目与提交版本</summary>
        <template v-if="session.data.value"><header><h2>{{ session.data.value.title }}</h2><p class="field-help">{{ trainingModeLabels[session.data.value.mode] }} · {{ trainingStatusLabels[session.data.value.status] }}</p></header><label class="question-select">选择题目<NSelect :value="questionId" :options="questionOptions" :disabled="blocked" placeholder="选择一题，查看它的提交版本" @update:value="selectQuestion" /></label><p v-if="questionId && !knownQuestion" class="notice error" role="alert">这道题不属于所选训练，请重新选择。</p></template>
        <section v-if="knownQuestion" class="answer-picker" aria-label="选择确切提交"><h3>提交版本</h3><p v-if="answers.isPending.value" role="status">正在读取提交…</p><p v-if="answers.error.value" class="notice error" role="alert">{{ errorText(answers.error.value) }}<NButton text :disabled="blocked" @click="answers.refetch()">重新读取提交</NButton></p><p v-if="answers.data.value && !answers.data.value.items.length" class="field-help">这页没有提交记录。可回到训练明确提交回答。</p><div class="answer-list"><button v-for="item in answers.data.value?.items" :key="item.id" :disabled="blocked || answers.isFetching.value" :class="{ selected: item.id === answerId }" :aria-current="item.id === answerId ? 'true' : undefined" @click="selectAnswer(item.id)"><strong>第 {{ item.number }} 版</strong><span>{{ answerConditions[item.condition] }}</span><small>{{ reviewDate(item.createdAt) }}</small></button></div><div class="row-actions"><NButton v-if="answerCursors.length" size="small" :disabled="blocked || answers.isFetching.value" @click="answerBefore = answerCursors.pop()">上一页</NButton><NButton v-if="answers.data.value?.nextCursor != null" size="small" :disabled="blocked || answers.isFetching.value" @click="nextAnswers">更早提交</NButton></div></section>
        </details>
        <template v-if="link">
          <p v-if="accessQuery.isPending.value" role="status">正在核对这次提交的访问记录…</p><p v-if="accessQuery.error.value" class="notice error" role="alert">{{ errorText(accessQuery.error.value) }}<NButton text :disabled="blocked" @click="recheckAccess">重新核对访问</NButton></p>
          <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="!readable" :loading="writer.busy.value" @click="retryAccess">用原请求重试</NButton><NButton v-else text :disabled="blocked" @click="recheckAccess">核对访问和训练状态</NButton></p><p v-if="deferred" class="notice" role="status">已确认访问暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已确认结果</NButton></p><p v-if="message" class="notice" role="status">{{ message }}</p>
          <section v-if="!allowed && session.data.value && !accessQuery.isPending.value && !accessQuery.error.value" class="review-access"><h3>确认打开复盘资料</h3><p>这里会打开所选原答、固定题目及个人未审参考，并可进入评价与改写参考。确认会保留接触记录；尚未结束的独立训练后续提交会按已确认辅助条件记录，不表示已读过某份改写。</p><NCheckbox v-model:checked="accessConfirmed" :disabled="blocked || receiptMismatch">确认打开这次提交的复盘资料并记录访问条件</NCheckbox><NCheckbox v-if="early" v-model:checked="earlyConfirmed" :disabled="blocked || receiptMismatch">本场约定结束后反馈，我确认提前查看并保留记录</NCheckbox><NButton :disabled="blocked || receiptMismatch || !accessConfirmed || (early && !earlyConfirmed)" :loading="writer.busy.value" @click="grantAccess">确认打开复盘</NButton><p class="field-help">确认访问不调用模型；AI 未配置时仍可自检和手工记录意见。</p></section>
          <template v-if="allowed"><p v-if="context.isPending.value && !currentInput" role="status">正在读取确切原文…</p><p v-if="context.error.value" class="notice error" role="alert">{{ errorText(context.error.value) }}<NButton text :disabled="blocked" @click="context.refetch()">重新读取原文</NButton></p>
            <template v-if="currentInput"><section class="original-answer"><header><h3>第 {{ currentInput.answerNumber }} 版原答</h3><p class="field-help">{{ answerConditions[currentInput.answerCondition] }} · 当前内容固定于这次提交</p></header><details><summary>题目与个人参考</summary><h4>{{ currentInput.question.title }}</h4><MarkdownPreview :value="currentInput.question.prompt" /><p v-if="currentInput.question.conditions" class="preserve">{{ currentInput.question.conditions }}</p><section v-if="currentInput.standard"><h4>个人未审参考</h4><MarkdownPreview :value="currentInput.standard.referenceAnswer" /><ul><li v-for="point in currentInput.standard.checkpoints" :key="point.id">{{ point.text }}</li></ul></section><p v-else class="field-help">本题没有个人参考标准。</p></details><MarkdownPreview :value="currentInput.answer.text" /><details v-if="currentInput.answer.nodes.length"><summary>答案图的 {{ currentInput.answer.nodes.length }} 个节点与 {{ currentInput.answer.edges.length }} 条连接</summary><ol><li v-for="node in currentInput.answer.nodes" :key="node.id"><strong>{{ node.label || '未命名节点' }}</strong><p class="preserve">{{ node.description }}</p></li></ol><ul><li v-for="edge in currentInput.answer.edges" :key="edge.id">{{ currentInput.answer.nodes.find(node => node.id === edge.source)?.label || '未命名节点' }} → {{ currentInput.answer.nodes.find(node => node.id === edge.target)?.label || '未命名节点' }}<p class="preserve">{{ edge.label }}</p></li></ul></details><RouterLink class="text-link" :to="{ name: 'training', query: { item: link.sessionId, question: link.questionId, answer: link.answerId } }">回到这次提交与原始结构图</RouterLink></section>
              <section class="feedback-catalog"><header class="inline-heading"><h3>自检与外部意见</h3><NButton size="small" :disabled="blocked" @click="newFeedback">新增反馈</NButton></header><p class="field-help">这些是个人记录，不代表平台认证的技术判断。每次修改与决定分别保留。</p><p v-if="feedback.isFetching.value" role="status">正在读取反馈记录…</p><p v-if="feedback.error.value" class="notice error" role="alert">{{ errorText(feedback.error.value) }}<NButton text :disabled="blocked" @click="feedback.refetch()">重新读取反馈</NButton></p><p v-if="feedback.data.value && !feedback.data.value.items.length" class="field-help">这页还没有手工反馈。可以从一次观察和对应原文开始。</p><div class="feedback-list"><button v-for="item in feedback.data.value?.items" :key="item.id" :disabled="blocked" :class="{ selected: item.id === feedbackId && !creating }" @click="openFeedback(item.id)"><strong>{{ reviewKindLabels[item.kind] }} · 修订 {{ item.revision }}</strong><span>{{ item.observation }}</span></button></div><div class="row-actions"><NButton v-if="feedbackCursors.length" size="small" :disabled="blocked || feedback.isFetching.value" @click="feedbackCursor = feedbackCursors.pop()">上一页</NButton><NButton v-if="feedback.data.value?.nextCursor" size="small" :disabled="blocked || feedback.isFetching.value" @click="nextFeedback">更多反馈</NButton></div></section>
              <div class="row-actions"><NButton :disabled="blocked || context.isFetching.value || !!context.error.value" @click="createFactDraft">从这次回答片段整理事实候选</NButton><span class="field-help">下一页手选回答文字，不加入本页题面、参考或评价；采用后仍须人工核实。</span></div>
              <div class="row-actions"><NButton :disabled="blocked || context.isFetching.value || !!context.error.value" @click="createRewrite">生成或查看这次提交的改写参考</NButton><span class="field-help">先预览题目、原答与个人参考，再单独授权生成；改写独立保存，不写回原答。</span></div>
              <ReviewFeedbackEditor v-if="creating || feedbackId" :key="`${sourceIdentity}/${creating ? 'new' : feedbackId}`" :id="creating ? undefined : feedbackId" :revision="creating ? undefined : feedbackRevision" :input="currentInput" :disabled="!readable || ownLocked || actionsState.locked || aiLocked || observationLocked" @created="createdFeedback" @changed="refreshedFeedback" @source="chooseSource" @close="closeFeedback" />
              <section class="ai-records"><header class="inline-heading"><h3>这次提交的 AI 报告</h3><NButton text size="small" :disabled="blocked" @click="tasks.refetch()">刷新报告记录</NButton></header><p class="field-help">已有报告保持 AI 未审层级；手工复盘不依赖模型配置。</p><p v-if="tasks.error.value" class="notice error" role="alert">{{ errorText(tasks.error.value) }}</p><p v-if="tasks.data.value && !exactTasks.length" class="field-help">本页没有这次提交的评价任务；如有下一页，可继续查找。</p><ul><li v-for="item in exactTasks" :key="item.task.id"><span>{{ aiTaskStatusLabel(item.task.status, item.task.purpose) }} · {{ reviewDate(item.task.createdAt) }}</span><NButton text size="small" :disabled="blocked" @click="openTask(item.task.id)">查看这份记录</NButton></li></ul><div class="row-actions"><NButton v-if="taskCursors.length" size="small" :disabled="blocked || tasks.isFetching.value" @click="taskCursor = taskCursors.pop()">上一页</NButton><NButton v-if="tasks.data.value?.nextCursor" size="small" :disabled="blocked || tasks.isFetching.value" @click="nextTasks">下一页</NButton></div></section>
              <p v-if="!strictAiRequested && task.error.value" class="notice error" role="alert">{{ errorText(task.error.value) }}<NButton text :disabled="blocked" @click="task.refetch()">重新读取报告</NButton></p>
              <template v-if="currentTask || strictAiRequested && strictAiLink">
                <RouterLink v-if="currentTask" class="text-link" :to="{ name: 'ai', query: { item: currentTask.task.id } }">打开 AI 任务、授权和费用记录</RouterLink>
                <AiEvaluationReport v-if="!strictAiRequested && currentTask?.evaluationReport" :key="taskId" :task="currentTask"
                  :disabled="!readable || ownLocked || actionsState.locked || feedbackLocked || observationLocked"
                  @received="aiChanged" @refreshed="aiChanged" @locked="aiLocked = $event" />
                <div v-if="currentTask?.task.status === 'SUCCEEDED' && currentTask.evaluationReport && (!strictAiRequested || verifiedAiObservation)" class="ai-source-selection"><label>选择要形成动作的观察<NSelect v-model:value="selectedObservation" :options="observationOptions" :disabled="blocked" placeholder="选择一项观察" /></label><NButton :disabled="blocked || !selectedObservation" @click="chooseAiSource">以所选观察形成改进动作</NButton></div>
              </template>
            </template>
          </template>
          <p v-if="strictAiLoading" class="notice" role="status">正在核对搜索来源的成功执行、报告摘要和确切观察…</p>
          <p v-if="strictAiError" class="notice error" role="alert">{{ strictAiError }}<NButton text :disabled="!readable || !allowed || strictAiLoading" @click="loadStrictAi">重新核对确切报告</NButton></p>
          <p v-if="verifiedAiObservation" class="notice" role="status">已核对搜索来源的成功执行、报告摘要、原答及观察 {{ verifiedAiObservation }}。当前决定按报告的最新记录展示。</p>
          <!-- 严格回链失权时清空报告属性，同一实例仅保留原请求与未保存决定，恢复读取后才能重试。 -->
          <AiEvaluationReport v-if="strictAiRequested && strictAiLink" :key="`strict/${strictAiIdentity}`"
            :task="verifiedAiObservation ? currentTask : undefined" :source-task-id="taskId" :verify-task="verifiedStrictTask"
            :locate-observation-id="verifiedAiObservation"
            :disabled="!readable || !allowed || ownLocked || actionsState.locked || feedbackLocked || observationLocked || !verifiedAiObservation"
            @received="aiChanged" @refreshed="aiChanged" @locked="aiLocked = $event" />
          <!-- 访问重核只遮挡观察，原答查询重读不卸载保存请求与未加入引文。 -->
          <ReviewedObservationWorkspace v-if="currentInput" v-show="allowed" :key="sourceIdentity" :input="currentInput" :observation-id="reviewedObservationId" :revision="reviewedRevision" :disabled="!readable || !allowed || ownLocked || actionsState.locked || aiLocked || feedbackLocked" />
        </template>
      </template>
      <section v-else class="review-welcome"><RIcon name="refresh-cw" :size="24" /><h2>让每次回答都有收获</h2><p>从一份已提交的回答出发，找到下一次可以改进的地方。</p><dl><dt>回看原答</dt><dd>选择训练、题目与提交版本，保留当时的真实表达。</dd><dt>留下观察</dt><dd>记录自检或外部反馈，定位原文与需要核实的地方。</dd><dt>再次验证</dt><dd>训练结束后，从确切原答建立复练，检验下一次的变化。</dd></dl></section>
    </section>
  </div>
  <section v-show="tab === 'actions' || (link && allowed && currentInput)" class="review-actions-stage" :inert="!readable || undefined"><ReviewActions :source="selectedSource" :answer="link" :action-id="actionId && reviewIdValid(actionId) ? actionId : undefined" :disabled="!readable || ownLocked || feedbackLocked || aiLocked || observationLocked" @state="actionsState = $event" @consumed="selectedSource = null" @open="openAction" @close="closeAction" /></section>
  </div>
  <p class="workspace-footnote">仅在收到匹配的服务器回执后显示保存成功。未发送的输入与待确认请求保留在当前页，刷新不会自动恢复。</p>
  </div>
</template>

<style scoped>
.review-tabs, .review-note, .review-workspace, .review-actions-stage { max-width: 1280px; margin-left: auto; margin-right: auto; } .review-tabs { display: flex; gap: 10px; margin-bottom: 20px; } .review-note { margin-bottom: 14px; }
.review-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 288px; gap: 32px; align-items: start; }.review-workspace.has-session { grid-template-columns: 248px minmax(0, 1fr); gap: 24px; }.review-catalog { min-width: 0; }.review-catalog > .field-help { margin: 12px 0 20px; }.review-catalog > .text-link { display: inline-flex; margin-top: 20px; font-size: 13px; }.review-stage { min-width: 0; display: grid; align-content: start; gap: 24px; }.has-session .review-stage { padding: 24px; border: 1px solid var(--studio-divider); border-radius: 10px; background: var(--studio-pane); }
h2 { margin: 0 0 8px; font-size: 22px; overflow-wrap: anywhere; } h3 { margin: 0; font-size: 16px; } h4 { font-size: 14px; margin: 14px 0 8px; } .session-list, .feedback-list { display: grid; gap: 5px; min-width: 0; } .session-list button, .feedback-list button { display: grid; gap: 7px; min-width: 0; text-align: left; padding: 11px; border-radius: 5px; } .session-list button.selected, .session-list button:hover, .feedback-list button.selected, .feedback-list button:hover, .answer-list button.selected, .answer-list button:hover { background: var(--surface-deep); } .session-list strong, .feedback-list strong { font-size: 13px; overflow-wrap: anywhere; } .session-list span, .session-list small { font-size: 12px; color: var(--muted); } .feedback-list span { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.question-select, .ai-source-selection label { display: grid; gap: 8px; font-size: 13px; } .answer-picker, .review-access, .original-answer, .feedback-catalog, .ai-records, .ai-source-selection { display: grid; gap: 14px; min-width: 0; } .answer-list { display: flex; flex-wrap: wrap; gap: 7px; } .answer-list button { display: grid; gap: 5px; text-align: left; padding: 11px; border: 1px solid var(--line); border-radius: 5px; } .answer-list span, .answer-list small { color: var(--muted); font-size: 12px; }
.review-access { padding: 17px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 7px; } .review-access p { font-size: 13px; } .review-access > .n-button, .ai-source-selection > .n-button { justify-self: start; } .original-answer, .feedback-catalog, .ai-records { padding-top: 20px; border-top: 1px solid var(--line); } .inline-heading { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; } .preserve { white-space: pre-wrap; overflow-wrap: anywhere; } summary { cursor: pointer; font-size: 13px; color: var(--brand-strong); } .original-answer details { min-width: 0; } .original-answer li { margin: 10px 0; overflow-wrap: anywhere; font-size: 13px; } .ai-records ul { padding: 0; margin: 0; list-style: none; } .ai-records li { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; font-size: 13px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.review-welcome { display: grid; justify-items: start; gap: 16px; padding: 24px; border: 1px solid var(--studio-divider); border-radius: 10px; background: var(--studio-pane); }.review-welcome > svg { color: var(--brand); }.review-welcome h2 { margin: 0; font-size: 17px; }.review-welcome p { color: var(--muted); font-size: 13px; }.review-welcome dl { margin: 8px 0 0; padding-top: 20px; border-top: 1px solid var(--studio-divider); }.review-welcome dt { font-size: 13px; font-weight: 500; }.review-welcome dd { margin: 6px 0 20px; color: var(--muted); font-size: 12px; line-height: 1.8; }.review-welcome dd:last-child { margin-bottom: 0; }.review-actions-stage { min-width: 0; }
</style>

<style scoped>
.review-product .review-tabs { max-width: none; gap: 6px; margin-bottom: 18px; border: 0; }.review-tabs > :is(button,a) { min-height: 32px; padding: 5px 12px; font-size: 13px; border: 0; border-radius: 5px; color: var(--muted); }.review-tabs > .active { color: var(--brand-strong); background: var(--brand-soft); }
.review-body { display: grid; gap: 24px; align-items: start; }.review-product .review-workspace { width: 100%; max-width: none; margin-inline: 0; }.review-catalog > summary { padding: 8px 0; font-size: 16px; font-weight: 600; color: var(--ink); cursor: pointer; }.session-list { gap: 0; }.session-list button { grid-template-columns: minmax(0,1fr) auto 16px; align-items: center; gap: 16px; padding: 20px 12px; border-radius: 0; border-bottom: 1px solid var(--studio-divider); }.review-record-content { display: grid; gap: 6px; min-width: 0; }.session-list strong { color: var(--ink); font-size: 14px; font-weight: 500; }.session-list :is(span,small) { font-size: 12px; }.session-list button:hover { background: var(--studio-pane); }.session-list button.selected { background: var(--brand-soft); }.has-session .session-list { max-height: 480px; overflow-y: auto; }.has-session .session-list button { grid-template-columns: minmax(0, 1fr); gap: 6px; padding: 14px 8px; }.has-session .session-list button > svg { display: none; }
.review-workspace.focused { display: block; border: 0; background: none; }.review-workspace.focused .review-catalog { padding: 12px 16px; margin-bottom: 20px; border: 1px solid var(--studio-divider); border-radius: 8px; background: var(--studio-pane); }.review-workspace.focused .review-stage { padding: 0; border: 0; background: none; }.review-workspace.focused .review-catalog > summary { padding: 0; font-size: 13px; font-weight: 400; }
.review-source-details { padding: 12px 16px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }.review-source-details > summary { font-size: 13px; cursor: pointer; }.review-source-details[open] > :not(summary) { margin-top: 14px; }
.original-answer, .feedback-catalog, .ai-records { padding: 20px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }.original-answer :deep(.markdown-preview) { font-size: var(--studio-font-size); line-height: 1.8; }.feedback-list button { padding: 12px; gap: 6px; border-radius: 6px; }.feedback-list span { white-space: normal; font-size: 14px; line-height: 1.7; }
.review-body.has-answer { grid-template-columns: minmax(0,1fr) 280px; }.review-actions-stage { max-width: none; padding: 20px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); }.has-answer .review-actions-stage { position: sticky; top: 124px; }.review-actions-stage :deep(.review-actions) { padding: 0; }.has-answer .review-actions-stage :deep(.action-columns) { grid-template-columns: minmax(0,1fr); }
@container workspace (max-width:1100px) { .review-body.has-answer { grid-template-columns: minmax(0,1fr); }.has-answer .review-actions-stage { position: static; } }
@container workspace (max-width: 820px) { .review-workspace { grid-template-columns: minmax(0, 1fr); gap: 24px; }.review-workspace.has-session { grid-template-columns: 210px minmax(0, 1fr); gap: 20px; } }
@media(max-width:700px) { .review-workspace.has-session { grid-template-columns: minmax(0, 1fr); }.has-session .session-list { max-height: 210px; }.has-session .review-stage { padding: 20px 16px; }.session-list button { grid-template-columns: minmax(0, 1fr) 16px; gap: 8px; padding: 18px 4px; }.session-list button > small { grid-column: 1; grid-row: 2; }.session-list button > svg { grid-column: 2; grid-row: 1 / 3; } }
</style>
