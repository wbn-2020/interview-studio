import { ApiError, read } from '../../api/client'
import type { AiEvaluationObservation, AiTask } from '../../api/ai'
import { questionDimensionLabels, questionTextLength } from '../../api/bank'
import type { ReviewAction, ReviewActionCandidate, ReviewActionSource, ReviewAnswerLink, ReviewDecision, ReviewFeedback, ReviewRecollectionSource, ReviewSourceRequest } from '../../api/review'
import { reviewAnswerMatches, reviewChoiceLabels, reviewCitationValid, reviewCoverageLabels, reviewFeedbackValid, reviewIdValid, reviewManualCandidate } from '../../api/review'

export type ActionEntry = ReviewSourceRequest | ReviewActionCandidate
export type ActionCandidate = ReviewActionCandidate & Pick<ReviewActionSource, 'manualKind' | 'authorLabel'>
export type ActionDraftSource = ActionCandidate & { verification: string }
export type ActionObservation = Omit<AiEvaluationObservation, 'id'> & { id: string | null }

export function actionTextValid(value: unknown, limit: number, required = false): value is string {
  return typeof value === 'string' && (!required || Boolean(value.trim())) && questionTextLength(value) <= limit && !/[\u0000\uD800-\uDFFF]/u.test(value)
}
export function actionTextProblem(title: string, detail: string): string {
  return !actionTextValid(title, 200, true) ? '动作标题必填，最多 200 个字符。'
    : !actionTextValid(detail, 2000) ? '动作说明最多 2000 个字符，须为完整文字。' : ''
}
export function sourceTextProblem(verification: string, tracking: string, reason: string): string {
  return !actionTextValid(verification, 2000, true) ? '每条来源都需要验证条件，最多 2000 个字符。'
    : !['TRACKING', 'DROPPED'].includes(tracking) ? '请选择有效的跟踪状态。'
    : !actionTextValid(reason, 1000, tracking === 'DROPPED') ? '停止跟踪需要填写原因，原因最多 1000 个字符。' : ''
}
export type ActionSourceIdentity = Pick<ReviewActionSource, 'kind' | 'feedbackId' | 'feedbackRevision' | 'taskId' | 'observationId' | 'recollection'>
export const actionSourceKey = (source: ActionSourceIdentity) => source.kind === 'MANUAL' ? `manual:${source.feedbackId}:${source.feedbackRevision}`
  : source.kind === 'AI_EVALUATION' ? `ai:${source.taskId}:${source.observationId}` : `recollection:${source.recollection?.recollectionId}:${source.recollection?.versionId}`
export const actionSourceObservation = (source: ReviewActionSource) => source.kind === 'RECOLLECTION' ? source.recollection?.observation ?? '' : source.observation?.observation ?? ''
export const actionAnswerValid = (answer: ReviewAnswerLink | null | undefined): answer is ReviewAnswerLink => Boolean(answer && reviewIdValid(answer.sessionId) && reviewIdValid(answer.questionId) && reviewIdValid(answer.answerId))
export const actionDecisionSignature = (value: ReviewDecision | null) => value === null ? 'null' : JSON.stringify([value.id, value.revision, value.choice, value.reason, value.createdAt])
export function actionObservationSignature(value: ActionObservation): string {
  return JSON.stringify([value.dimension, value.coverage, value.observation, value.condition, value.basis, value.limitation, value.suggestion,
    value.citations.map(citation => [citation.kind, citation.elementId, citation.start, citation.end, citation.quote])])
}
function decisionValid(value: ReviewDecision | null): boolean {
  return value === null || Boolean(value && reviewIdValid(value.id) && Number.isSafeInteger(value.revision) && value.revision >= 1 && value.revision <= 20
    && value.choice in reviewChoiceLabels && actionTextValid(value.reason, 1000, value.choice === 'DISPUTED') && typeof value.createdAt === 'string')
}
function observationValid(value: ActionObservation | null | undefined): value is ActionObservation {
  return Boolean(value && (value.id === null || actionTextValid(value.id, 36, true)) && value.dimension in questionDimensionLabels && value.coverage in reviewCoverageLabels
    && actionTextValid(value.observation, 2000, true) && actionTextValid(value.condition, 1000)
    && actionTextValid(value.basis, 1000, true) && actionTextValid(value.limitation, 1000, true) && actionTextValid(value.suggestion, 1000, true)
    && Array.isArray(value.citations) && value.citations.length <= 6
    && value.citations.every(citation => citation && typeof citation.kind === 'string' && (citation.elementId === null || reviewIdValid(citation.elementId))
      && Number.isSafeInteger(citation.start) && Number.isSafeInteger(citation.end) && citation.start >= 0 && citation.end > citation.start && actionTextValid(citation.quote, 500, true)))
}
export function actionSourceValid(source: ReviewActionSource): boolean {
  try {
    if (!source || !reviewIdValid(source.id) || !reviewIdValid(source.originActionId)
      || sourceTextProblem(source.verification, source.tracking, source.trackingReason)
      || typeof source.createdAt !== 'string' || typeof source.updatedAt !== 'string') return false
    if (source.kind === 'RECOLLECTION') return source.answer === null && source.feedbackId === null && source.feedbackRevision === null
      && source.taskId === null && source.observationId === null && source.manualKind === null && source.authorLabel === null
      && source.observation === null && source.decision === null && recollectionSourceValid(source.recollection)
    // 旧历史可缺少新增尾字段；只有正式答案三元组完整时才接受空回忆身份。
    return source.recollection == null && actionAnswerValid(source.answer) && observationValid(source.observation) && decisionValid(source.decision)
      && (source.kind === 'MANUAL' ? reviewIdValid(source.feedbackId) && Number.isInteger(source.feedbackRevision) && source.feedbackRevision! >= 1 && source.feedbackRevision! <= 100
        && source.taskId === null && source.observationId === null && ['SELF_CHECK', 'EXTERNAL_FEEDBACK'].includes(source.manualKind ?? '')
        && actionTextValid(source.authorLabel, 120, source.manualKind === 'EXTERNAL_FEEDBACK') && (source.manualKind !== 'SELF_CHECK' || source.authorLabel === '')
        : source.kind === 'AI_EVALUATION' && reviewIdValid(source.taskId) && /^f([1-9]|1[0-2])$/.test(source.observationId ?? '')
          && source.feedbackId === null && source.feedbackRevision === null && source.manualKind === null && source.authorLabel === null && source.observation.id === source.observationId)
  } catch { return false }
}
function recollectionSourceValid(value: ReviewRecollectionSource | null | undefined): boolean {
  return Boolean(value && Object.keys(value).length === 5
    && ['recollectionId', 'versionId', 'versionNumber', 'baselineKind', 'observation'].every(key => Object.hasOwn(value, key))
    && reviewIdValid(value.recollectionId) && reviewIdValid(value.versionId) && Number.isInteger(value.versionNumber) && value.versionNumber >= 1 && value.versionNumber <= 1000
    && ['RECOLLECTION', 'NONE'].includes(value.baselineKind) && actionTextValid(value.observation, 5000, true))
}
export function actionValid(value: ReviewAction): boolean {
  return Boolean(value && reviewIdValid(value.id) && Number.isSafeInteger(value.revision) && value.revision >= 0 && value.revision <= 1000
    && !actionTextProblem(value.title, value.detail) && (value.status === 'OPEN' ? value.mergedInto === null : value.status === 'MERGED' && reviewIdValid(value.mergedInto) && value.mergedInto !== value.id)
    && Array.isArray(value.sources) && value.sources.length <= 20 && (value.status !== 'OPEN' || value.sources.length > 0)
    && value.sources.every(actionSourceValid) && new Set(value.sources.map(source => source.id)).size === value.sources.length
    && typeof value.createdAt === 'string' && typeof value.updatedAt === 'string')
}
export function actionSourceSignature(source: ReviewActionSource): string {
  const recollection = source.recollection
  return JSON.stringify([source.id, source.originActionId, source.kind, source.answer?.sessionId ?? null, source.answer?.questionId ?? null, source.answer?.answerId ?? null,
    source.feedbackId, source.feedbackRevision, source.taskId, source.observationId, source.manualKind, source.authorLabel,
    source.observation?.id ?? null, source.observation ? actionObservationSignature(source.observation) : null, actionDecisionSignature(source.decision), source.verification, source.tracking, source.trackingReason, source.createdAt,
    recollection ? [recollection.recollectionId, recollection.versionId, recollection.versionNumber, recollection.baselineKind, recollection.observation] : null])
}
export function actionSourcesMatch(actual: ReviewActionSource[], expected: ReviewActionSource[]): boolean {
  return actual.length === expected.length && actual.every(source => expected.some(item => item.id === source.id && actionSourceSignature(item) === actionSourceSignature(source)))
}

export async function readActionCandidate(entry: ActionEntry): Promise<ActionCandidate> {
  const request = 'source' in entry ? entry.source : entry
  let candidate: ActionCandidate
  if (request.kind === 'MANUAL' && reviewIdValid(request.feedbackId) && Number.isInteger(request.feedbackRevision) && request.feedbackRevision! >= 1 && request.feedbackRevision! <= 100
      && request.taskId === null && request.observationId === null) {
    const feedback = await read<ReviewFeedback>(`/review/feedback/${request.feedbackId}?revision=${request.feedbackRevision}`)
    if (feedback?.id !== request.feedbackId || feedback.revision !== request.feedbackRevision || !actionAnswerValid(feedback.answer) || !reviewFeedbackValid(feedback, feedback.answer)) throw sourceMismatch()
    candidate = { ...reviewManualCandidate(feedback), manualKind: feedback.content.kind, authorLabel: feedback.content.authorLabel }
    if (feedback.content.kind === 'EXTERNAL_FEEDBACK') candidate.label += ` · ${feedback.content.authorLabel}（用户录入）`
  } else if (request.kind === 'AI_EVALUATION' && reviewIdValid(request.taskId) && /^f([1-9]|1[0-2])$/.test(request.observationId ?? '') && request.feedbackId === null && request.feedbackRevision === null) {
    const task = await read<AiTask>(`/ai/tasks/${request.taskId}`)
    const input = task?.manifest?.evaluation
    const observation = task?.evaluationReport?.observations.find(item => item.id === request.observationId)
    if (task?.task?.id !== request.taskId || task.task.status !== 'SUCCEEDED' || task.task.purpose !== 'ANSWER_EVALUATION'
      || !input || !actionAnswerValid(input) || !observation || !observationValid(observation)
      || observation.citations.some(citation => !reviewCitationValid(input, citation))) throw sourceMismatch()
    const saved = [...(task.evaluationDecisions ?? [])].filter(item => item.feedbackId === request.observationId).sort((left, right) => right.revision - left.revision)[0]
    const decision: ReviewDecision | null = saved ? { id: saved.id, revision: saved.revision, choice: saved.choice, reason: saved.reason, createdAt: saved.createdAt } : null
    if (!decisionValid(decision)) throw sourceMismatch()
    candidate = { answer: { sessionId: input.sessionId, questionId: input.questionId, answerId: input.answerId },
      source: { kind: 'AI_EVALUATION', taskId: request.taskId, observationId: request.observationId, feedbackId: null, feedbackRevision: null, expectedDecisionRevision: decision?.revision ?? 0 },
      observation, label: 'AI 未审观察', decision, manualKind: null, authorLabel: null }
  } else throw sourceMismatch()
  // 入口只用于定位；展示与写入的来源和决定以确切记录的本次读取为准。
  if ('answer' in entry && !reviewAnswerMatches(entry.answer, candidate.answer)) throw sourceMismatch()
  return candidate
}
export function actionCandidateMatches(source: ReviewActionSource, candidate: ActionDraftSource): boolean {
  return source.kind !== 'RECOLLECTION' && source.recollection == null && source.observation !== null
    && actionSourceKey(source) === actionSourceKey(candidate.source) && reviewAnswerMatches(source.answer, candidate.answer)
    && source.manualKind === candidate.manualKind && source.authorLabel === candidate.authorLabel
    && actionObservationSignature(source.observation) === actionObservationSignature(candidate.observation)
    && actionDecisionSignature(source.decision) === actionDecisionSignature(candidate.decision)
    && source.verification === candidate.verification && source.tracking === 'TRACKING' && source.trackingReason === ''
}
function sourceMismatch() { return new ApiError(502, 'REVIEW_SOURCE_MISMATCH', '来源身份、原观察或决定不符合确切记录，请重新选择来源。') }
