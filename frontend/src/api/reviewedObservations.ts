import { ApiError } from './client'
import { aiTrainingDraftRangeMatches } from './ai'
import type { AiEvaluationCitation, AiEvaluationInput } from './ai'
import { reviewAnswerMatches } from './review'
import type { ReviewAnswerLink } from './review'
import type { TrainingAnswerCondition, TrainingMode, TrainingPriorExposure } from './training'
import { memoryUtcMicros } from './memoryReview'
import {
  eligibilityReasons, questionVersionRefValid, recordChanges, recordStatuses, reviewLimitations, revisionRefValid,
  standardEligibilityValid, standardHistoryQueryValid, standardReviewDateValid, standardReviewErrorText,
  standardReviewIdValid, standardReviewRevisionValid, standardReviewTextValid, standardReviewZoneValid, withdrawRecordValid,
} from './standardReviews'
import type { EligibilityReason, LineageCheck, QuestionVersionRef, RecordChange, RecordStatus, ReviewLimitation, RevisionRef, StandardEligibility, StandardHistoryQuery, WithdrawRecord } from './standardReviews'

export type AnswerLink = ReviewAnswerLink
export const observationConclusions = ['SUPPORTS_CHECKPOINT', 'CONTRADICTS_CHECKPOINT', 'INSUFFICIENT', 'DISPUTED'] as const
export const observationCoverages = ['MENTIONED', 'NOT_MENTIONED', 'UNCERTAIN'] as const
export const observationCitationKinds = ['ANSWER_TEXT', 'ANSWER_NODE_LABEL', 'ANSWER_NODE_DESCRIPTION', 'ANSWER_EDGE_LABEL', 'CHECKPOINT'] as const
export type ObservationConclusion = typeof observationConclusions[number]
export type ObservationCoverage = typeof observationCoverages[number]
export type ObservationCitationKind = typeof observationCitationKinds[number]
export type ObservationCitation = Omit<AiEvaluationCitation, 'kind' | 'elementId'> & (
  { kind: 'ANSWER_TEXT'; elementId: null } | { kind: Exclude<ObservationCitationKind, 'ANSWER_TEXT'>; elementId: string })
export type ObservationContent = { standardReview: RevisionRef; coverage: ObservationCoverage; conclusion: ObservationConclusion; statement: string; conditions: string;
  limitations: string; observerDescription: string; observedOn: string; observationZoneId: string; citations: ObservationCitation[] }
export type ObservationDraft = { id: string | null; expectedRevision: number | null; answer: AnswerLink; checkpointId: string; content: ObservationContent; changeReason: string | null }
export type SaveObservation = { draft: ObservationDraft; expectedPreviewHash: string }
export type ObservationPriorExposure = Omit<TrainingPriorExposure, 'evaluationAccessCount'> & { evaluationAccessCount: number }
export type RecordedAnswerConditions = { mode: TrainingMode; answerCondition: TrainingAnswerCondition; assistanceEventSequence: number; priorExposure: ObservationPriorExposure;
  questionKind: 'MAIN' | 'REPLAY'; answerNumber: number; answerCreatedAt: string; technology: string; scenario: string; questionConditions: string }
export type ObservationBinding = { answer: AnswerLink; question: QuestionVersionRef; checkpointId: string; answerHash: string; questionSnapshotHash: string; standardReview: RevisionRef; standardBasisHash: string }
export type ReviewedObservation = { id: string; revision: number; status: RecordStatus; answer: AnswerLink; question: QuestionVersionRef; checkpointId: string;
  content: ObservationContent; binding: ObservationBinding; createdAt: string; revisedAt: string }
export type ObservationEligibility = { currentRevision: number; currentStatus: RecordStatus; revisionCurrent: boolean; standardCurrentRevision: number;
  standardCurrentStatus: RecordStatus; eligibleAsReviewedObservation: boolean; reasons: EligibilityReason[]; lineageCheck: LineageCheck; limitations: ReviewLimitation[] }
export type ObservationPreview = { draft: ObservationDraft; binding: ObservationBinding; answerConditions: RecordedAnswerConditions; standardEligibility: StandardEligibility; previewHash: string; limitations: ReviewLimitation[] }
export type ObservationDetail = { snapshot: ReviewedObservation; answerConditions: RecordedAnswerConditions; eligibility: ObservationEligibility }
export type ObservationHistory = { change: RecordChange; snapshot: ReviewedObservation; changeReason: string | null }
export type ObservationSummary = { id: string; revision: number; status: RecordStatus; answer: AnswerLink; question: QuestionVersionRef; checkpointId: string; standardReview: RevisionRef;
  coverage: ObservationCoverage; conclusion: ObservationConclusion; observedOn: string; observationZoneId: string; createdAt: string; revisedAt: string; eligibility: ObservationEligibility }
export type ObservationPage = { items: ObservationSummary[]; nextCursor: string | null }
export type ObservationHistoryPage = { items: ObservationHistory[]; nextBefore: number | null }
export type RecordedFields = { technology: boolean; scenario: boolean; conditions: boolean }
export type ObservationAnswerMeta = Omit<RecordedAnswerConditions, 'technology' | 'scenario' | 'questionConditions'> & { recordedFields: RecordedFields }
export type ObservationProjection = { ref: RevisionRef; change: RecordChange; recordedStatus: RecordStatus; answer: AnswerLink; question: QuestionVersionRef; checkpointId: string;
  standardReview: RevisionRef; recordedAt: string; observedOn: string; observationZoneId: string; coverage: ObservationCoverage; conclusion: ObservationConclusion; answerMeta: ObservationAnswerMeta; eligibility: ObservationEligibility }
export type ObservationQuery = AnswerLink & { status?: RecordStatus; cursor?: string; limit?: number }
export type ObservationHistoryQuery = StandardHistoryQuery
export type ObservationIdentity = { id?: string; revision?: number; answer?: AnswerLink; question?: QuestionVersionRef; checkpointId?: string }
export type ObservationCitationField = { key: string; kind: ObservationCitationKind; elementId: string | null; label: string; text: string }

const id = standardReviewIdValid, integer = standardReviewRevisionValid, text = standardReviewTextValid
const fields = (value: unknown, names: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const oneOf = <T extends string>(value: unknown, choices: readonly T[]): value is T => typeof value === 'string' && choices.includes(value as T)
const list = <T>(value: unknown, valid: (item: unknown) => item is T, min: number, max: number): value is T[] => Array.isArray(value) && value.length >= min && value.length <= max && value.every(item => valid(item))
const check = (rule: () => boolean): boolean => { try { return rule() } catch { return false } }
const bounded = (value: unknown, maximum = 1048576): boolean => check(() => new TextEncoder().encode(JSON.stringify(value)).length <= maximum)
const timestamp = (value: unknown): value is string => memoryUtcMicros(value) !== undefined
const beforeOrEqual = (left: string, right: string): boolean => memoryUtcMicros(left)! <= memoryUtcMicros(right)!
const sameTime = (left: string, right: string): boolean => memoryUtcMicros(left) === memoryUtcMicros(right)
const hash = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
// 固定题库条件沿用原字段的码点上限；历史允许的 NUL 保持原文，不套用新审阅输入规则。
const sourceText = (value: unknown, maximum: number): value is string => typeof value === 'string' && Array.from(value).length <= maximum
const enumOrder = <T extends string>(value: unknown, choices: readonly T[], min = 0): value is T[] => list(value, (item): item is T => oneOf(item, choices), min, choices.length)
  && value.every((item, index) => index === 0 || choices.indexOf(value[index - 1]!) < choices.indexOf(item))
function equal(left: unknown, right: unknown): boolean {
  if (left === right) return true
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object' || Array.isArray(left) !== Array.isArray(right)) return false
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every(key => Object.hasOwn(right, key) && equal((left as Record<string, unknown>)[key], (right as Record<string, unknown>)[key]))
}
function localDate(instant: string, zone: string): string {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(instant))
  const part = (name: string) => parts.find(item => item.type === name)!.value
  return `${part('year').padStart(4, '0')}-${part('month')}-${part('day')}`
}
function observedDateValid(value: unknown, zone: string, serverNow?: string, answerCreatedAt?: string): value is string {
  return standardReviewDateValid(value) && standardReviewZoneValid(zone) && check(() => (serverNow === undefined || timestamp(serverNow) && value <= localDate(serverNow, zone))
    && (answerCreatedAt === undefined || timestamp(answerCreatedAt) && value >= localDate(answerCreatedAt, zone)))
}
function limitsValid(value: unknown): value is ReviewLimitation[] { return enumOrder(value, reviewLimitations, 4) && reviewLimitations.slice(0, 4).every(item => value.includes(item)) }
export function observationAnswerValid(value: unknown): value is AnswerLink { return fields(value, ['sessionId', 'questionId', 'answerId']) && id(value.sessionId) && id(value.questionId) && id(value.answerId) }
export function observationCoverageConclusionValid(coverage: unknown, conclusion: unknown): boolean {
  return oneOf(coverage, observationCoverages) && oneOf(conclusion, observationConclusions)
    && (coverage === 'MENTIONED' || conclusion === 'INSUFFICIENT' || coverage === 'UNCERTAIN' && conclusion === 'DISPUTED')
}
export function observationCitationFields(input: AiEvaluationInput, checkpointId: string): ObservationCitationField[] {
  const result: ObservationCitationField[] = []
  const add = (kind: ObservationCitationKind, elementId: string | null, value: string, suffix = '') => {
    result.push({ key: `${kind}:${elementId ?? ''}`, kind, elementId, label: `${observationCitationKindLabels[kind]}${suffix}`, text: value })
  }
  add('ANSWER_TEXT', null, input.answer.text)
  input.answer.nodes.forEach((node, index) => { add('ANSWER_NODE_LABEL', node.id, node.label, ` · ${index + 1}`); add('ANSWER_NODE_DESCRIPTION', node.id, node.description, ` · ${index + 1}`) })
  input.answer.edges.forEach((edge, index) => add('ANSWER_EDGE_LABEL', edge.id, edge.label, ` · ${index + 1}`))
  const checkpoint = input.standard?.checkpoints.find(item => item.id === checkpointId)
  if (checkpoint) add('CHECKPOINT', checkpoint.id, checkpoint.text)
  return result
}
export function observationCitationValid(value: unknown, input?: AiEvaluationInput, checkpointId?: string): value is ObservationCitation {
  if (!fields(value, ['kind', 'elementId', 'start', 'end', 'quote']) || !oneOf(value.kind, observationCitationKinds)
    || !integer(value.start, 0, 2147483647) || !integer(value.end, 1, 2147483647) || value.end <= value.start || !text(value.quote, 500) || value.end - value.start !== value.quote.length
    || (value.kind === 'ANSWER_TEXT' ? value.elementId !== null : !id(value.elementId)) || value.kind === 'CHECKPOINT' && checkpointId !== undefined && value.elementId !== checkpointId) return false
  if (input === undefined) return true
  return check(() => {
    const citation = value as ObservationCitation
    const actual = observationCitationFields(input, checkpointId ?? (citation.kind === 'CHECKPOINT' ? citation.elementId : '')).find(item => item.kind === citation.kind && item.elementId === citation.elementId)
    return actual !== undefined && aiTrainingDraftRangeMatches(actual.text, citation.start, citation.end, citation.quote)
  })
}
export function observationContentValid(value: unknown, checkpointId: string, input?: AiEvaluationInput, serverNow?: string, answerCreatedAt?: string): value is ObservationContent {
  if (!fields(value, ['standardReview', 'coverage', 'conclusion', 'statement', 'conditions', 'limitations', 'observerDescription', 'observedOn', 'observationZoneId', 'citations'])
    || !id(checkpointId) || !revisionRefValid(value.standardReview) || !observationCoverageConclusionValid(value.coverage, value.conclusion) || !text(value.statement)
    || !text(value.conditions) || !text(value.limitations) || !text(value.observerDescription) || !standardReviewZoneValid(value.observationZoneId)
    || !observedDateValid(value.observedOn, value.observationZoneId, serverNow, answerCreatedAt)
    || !list(value.citations, (item): item is ObservationCitation => observationCitationValid(item, input, checkpointId), 1, 6)) return false
  const citations = value.citations, answers = citations.some(item => item.kind !== 'CHECKPOINT')
  return citations.some(item => item.kind === 'CHECKPOINT') && new Set(citations.map(item => `${item.kind}:${item.elementId}:${item.start}:${item.end}`)).size === citations.length
    && (value.coverage !== 'MENTIONED' || answers) && (value.coverage !== 'NOT_MENTIONED' || !answers)
}
export function observationDraftValid(value: unknown, input?: AiEvaluationInput, serverNow?: string): value is ObservationDraft {
  return fields(value, ['id', 'expectedRevision', 'answer', 'checkpointId', 'content', 'changeReason']) && bounded(value, 262144)
    && (value.id === null ? value.expectedRevision === null && value.changeReason === null : id(value.id) && integer(value.expectedRevision) && text(value.changeReason))
    && observationAnswerValid(value.answer) && id(value.checkpointId) && observationContentValid(value.content, value.checkpointId, input, serverNow)
    && (input === undefined || reviewAnswerMatches(value.answer, input) && input.standard !== null && input.standard.checkpoints.some(item => item.id === value.checkpointId))
}
export function saveObservationValid(value: unknown, preview?: ObservationPreview, input?: AiEvaluationInput): value is SaveObservation {
  return fields(value, ['draft', 'expectedPreviewHash']) && bounded(value, 262144) && observationDraftValid(value.draft, input) && hash(value.expectedPreviewHash)
    && (preview === undefined || value.expectedPreviewHash === preview.previewHash && equal(value.draft, preview.draft))
}
export function observationPriorExposureValid(value: unknown): value is ObservationPriorExposure {
  return fields(value, ['authoredCount', 'referenceViewCount', 'lastExposureAt', 'evaluationAccessCount']) && integer(value.authoredCount) && integer(value.referenceViewCount)
    && integer(value.evaluationAccessCount) && (value.lastExposureAt === null || timestamp(value.lastExposureAt))
}
function answerConditionFieldsValid(value: Record<string, unknown>): boolean {
  return oneOf(value.mode, ['LEARNING', 'FOCUSED', 'MOCK']) && oneOf(value.answerCondition, ['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'])
    && integer(value.assistanceEventSequence, 0, 2000) && observationPriorExposureValid(value.priorExposure) && oneOf(value.questionKind, ['MAIN', 'REPLAY'])
    && integer(value.answerNumber, 1, 100) && timestamp(value.answerCreatedAt) && (value.mode === 'LEARNING') === (value.answerCondition === 'LEARNING')
    && (value.answerCondition !== 'ASSISTED' || value.assistanceEventSequence > 0)
    && (value.priorExposure.lastExposureAt === null || beforeOrEqual(value.priorExposure.lastExposureAt, value.answerCreatedAt))
}
export function recordedAnswerConditionsValid(value: unknown, input?: AiEvaluationInput): value is RecordedAnswerConditions {
  return fields(value, ['mode', 'answerCondition', 'assistanceEventSequence', 'priorExposure', 'questionKind', 'answerNumber', 'answerCreatedAt', 'technology', 'scenario', 'questionConditions'])
    && answerConditionFieldsValid(value) && sourceText(value.technology, 2000) && sourceText(value.scenario, 2000) && sourceText(value.questionConditions, 5000)
    && (input === undefined || value.mode === input.mode && value.answerCondition === input.answerCondition && value.answerNumber === input.answerNumber
      && value.technology === input.question.technology && value.scenario === input.question.scenario && value.questionConditions === input.question.conditions)
}
export function observationBindingValid(value: unknown, draft?: ObservationDraft, input?: AiEvaluationInput): value is ObservationBinding {
  return fields(value, ['answer', 'question', 'checkpointId', 'answerHash', 'questionSnapshotHash', 'standardReview', 'standardBasisHash']) && observationAnswerValid(value.answer)
    && questionVersionRefValid(value.question) && id(value.checkpointId) && hash(value.answerHash) && hash(value.questionSnapshotHash) && revisionRefValid(value.standardReview) && hash(value.standardBasisHash)
    && (draft === undefined || equal(value.answer, draft.answer) && value.checkpointId === draft.checkpointId && equal(value.standardReview, draft.content.standardReview))
    && (input === undefined || reviewAnswerMatches(value.answer, input) && input.standard !== null && value.question.questionId === input.standard.questionId && value.question.versionId === input.standard.versionId)
}
export function reviewedObservationValid(value: unknown, expected: ObservationIdentity = {}, input?: AiEvaluationInput): value is ReviewedObservation {
  return fields(value, ['id', 'revision', 'status', 'answer', 'question', 'checkpointId', 'content', 'binding', 'createdAt', 'revisedAt']) && bounded(value, 262144)
    && id(value.id) && integer(value.revision) && oneOf(value.status, recordStatuses) && observationAnswerValid(value.answer) && questionVersionRefValid(value.question) && id(value.checkpointId)
    && timestamp(value.createdAt) && timestamp(value.revisedAt) && beforeOrEqual(value.createdAt, value.revisedAt)
    && (value.revision !== 0 || value.status === 'ACTIVE' && sameTime(value.createdAt, value.revisedAt)) && observationContentValid(value.content, value.checkpointId, input, value.revisedAt)
    && observationBindingValid(value.binding, undefined, input) && equal(value.binding.answer, value.answer) && equal(value.binding.question, value.question)
    && value.binding.checkpointId === value.checkpointId && equal(value.binding.standardReview, value.content.standardReview)
    && (expected.id === undefined || value.id === expected.id) && (expected.revision === undefined || value.revision === expected.revision)
    && (expected.answer === undefined || equal(value.answer, expected.answer)) && (expected.question === undefined || equal(value.question, expected.question))
    && (expected.checkpointId === undefined || value.checkpointId === expected.checkpointId)
}
type EligibilitySnapshot = Pick<ReviewedObservation, 'revision' | 'status'> & { standardReview: RevisionRef; conclusion: ObservationConclusion }
function eligibilityMatches(value: ObservationEligibility, snapshot: EligibilitySnapshot): boolean {
  return value.currentRevision >= snapshot.revision && value.revisionCurrent === (value.currentRevision === snapshot.revision)
    && (!value.revisionCurrent || value.currentStatus === snapshot.status) && (snapshot.status !== 'WITHDRAWN' || value.revisionCurrent && value.currentStatus === 'WITHDRAWN')
    && value.standardCurrentRevision >= snapshot.standardReview.revision && value.reasons.includes('STANDARD_REVISED') === (value.standardCurrentRevision > snapshot.standardReview.revision)
    && value.reasons.includes('OBSERVATION_DISPUTED') === (snapshot.conclusion === 'DISPUTED')
}
export function observationEligibilityValid(value: unknown, snapshot?: ReviewedObservation | ObservationSummary): value is ObservationEligibility {
  if (!fields(value, ['currentRevision', 'currentStatus', 'revisionCurrent', 'standardCurrentRevision', 'standardCurrentStatus', 'eligibleAsReviewedObservation', 'reasons', 'lineageCheck', 'limitations'])
    || !integer(value.currentRevision) || !oneOf(value.currentStatus, recordStatuses) || typeof value.revisionCurrent !== 'boolean'
    || !integer(value.standardCurrentRevision) || !oneOf(value.standardCurrentStatus, recordStatuses) || typeof value.eligibleAsReviewedObservation !== 'boolean'
    || !enumOrder(value.reasons, eligibilityReasons) || value.lineageCheck !== 'PARTIAL' || !limitsValid(value.limitations)
    || value.eligibleAsReviewedObservation !== (value.reasons.length === 0) || value.reasons.includes('RECORD_WITHDRAWN') !== (value.currentStatus === 'WITHDRAWN')
    || value.reasons.includes('RECORD_REVISED') !== !value.revisionCurrent || value.reasons.includes('STANDARD_WITHDRAWN') !== (value.standardCurrentStatus === 'WITHDRAWN')) return false
  return snapshot === undefined || eligibilityMatches(value as ObservationEligibility, { revision: snapshot.revision, status: snapshot.status,
    standardReview: 'content' in snapshot ? snapshot.content.standardReview : snapshot.standardReview, conclusion: 'content' in snapshot ? snapshot.content.conclusion : snapshot.conclusion })
}
export function observationPreviewValid(value: unknown, expectedDraft?: ObservationDraft, input?: AiEvaluationInput): value is ObservationPreview {
  if (!fields(value, ['draft', 'binding', 'answerConditions', 'standardEligibility', 'previewHash', 'limitations']) || !bounded(value)
    || !observationDraftValid(value.draft, input) || expectedDraft !== undefined && !equal(value.draft, expectedDraft) || !observationBindingValid(value.binding, value.draft, input)
    || !recordedAnswerConditionsValid(value.answerConditions, input) || !standardEligibilityValid(value.standardEligibility) || !hash(value.previewHash) || !limitsValid(value.limitations)) return false
  const draft = value.draft, eligibility = value.standardEligibility
  return observedDateValid(draft.content.observedOn, draft.content.observationZoneId, undefined, value.answerConditions.answerCreatedAt)
    && eligibility.currentRevision === draft.content.standardReview.revision && eligibility.currentStatus === 'ACTIVE' && eligibility.revisionCurrent
    && eligibility.checkpoints.some(item => item.checkpointId === draft.checkpointId && item.eligible)
    && eligibility.basisChecks[0]!.source.id === value.binding.question.questionId && equal(value.limitations, eligibility.limitations)
}
export function observationDetailValid(value: unknown, observationId?: string, revision?: number, input?: AiEvaluationInput): value is ObservationDetail {
  return fields(value, ['snapshot', 'answerConditions', 'eligibility']) && bounded(value) && reviewedObservationValid(value.snapshot, { id: observationId, revision }, input)
    && recordedAnswerConditionsValid(value.answerConditions, input) && beforeOrEqual(value.answerConditions.answerCreatedAt, value.snapshot.createdAt)
    && observedDateValid(value.snapshot.content.observedOn, value.snapshot.content.observationZoneId, value.snapshot.revisedAt, value.answerConditions.answerCreatedAt)
    && observationEligibilityValid(value.eligibility, value.snapshot) && (revision !== undefined || value.eligibility.revisionCurrent)
}
function historyChangeValid(change: unknown, revision: number, status: RecordStatus): change is RecordChange {
  return oneOf(change, recordChanges) && (change === 'CREATED' ? revision === 0 && status === 'ACTIVE' : revision > 0 && (change === 'WITHDRAWN') === (status === 'WITHDRAWN'))
}
export function observationHistoryValid(value: unknown, expected: ObservationIdentity = {}, input?: AiEvaluationInput): value is ObservationHistory {
  return fields(value, ['change', 'snapshot', 'changeReason']) && bounded(value, 262144) && reviewedObservationValid(value.snapshot, expected, input)
    && historyChangeValid(value.change, value.snapshot.revision, value.snapshot.status) && (value.change === 'CREATED' ? value.changeReason === null : text(value.changeReason))
}
export function observationSummaryValid(value: unknown, answer?: AnswerLink): value is ObservationSummary {
  if (!fields(value, ['id', 'revision', 'status', 'answer', 'question', 'checkpointId', 'standardReview', 'coverage', 'conclusion', 'observedOn', 'observationZoneId', 'createdAt', 'revisedAt', 'eligibility'])
    || !id(value.id) || !integer(value.revision) || !oneOf(value.status, recordStatuses) || !observationAnswerValid(value.answer) || answer !== undefined && !equal(value.answer, answer)
    || !questionVersionRefValid(value.question) || !id(value.checkpointId) || !revisionRefValid(value.standardReview) || !observationCoverageConclusionValid(value.coverage, value.conclusion)
    || !standardReviewZoneValid(value.observationZoneId) || !timestamp(value.createdAt) || !timestamp(value.revisedAt) || !beforeOrEqual(value.createdAt, value.revisedAt)
    || !observedDateValid(value.observedOn, value.observationZoneId, value.revisedAt) || value.revision === 0 && (value.status !== 'ACTIVE' || !sameTime(value.createdAt, value.revisedAt))) return false
  return observationEligibilityValid(value.eligibility, value as ObservationSummary)
}
// 保存回执只证明原请求已提交；调用方随后单独读取当前资格，避免把旧 ACTIVE 回执作为当前状态。
export function savedObservationValid(value: unknown, request: SaveObservation, previous?: ReviewedObservation, preview?: ObservationPreview, input?: AiEvaluationInput): value is ReviewedObservation {
  if (!saveObservationValid(request, preview, input) || !reviewedObservationValid(value, { answer: request.draft.answer, checkpointId: request.draft.checkpointId }, input)
    || value.status !== 'ACTIVE' || !equal(value.content, request.draft.content) || preview !== undefined && (!equal(value.binding, preview.binding)
      || !beforeOrEqual(preview.answerConditions.answerCreatedAt, value.createdAt) || !observedDateValid(value.content.observedOn, value.content.observationZoneId, value.revisedAt, preview.answerConditions.answerCreatedAt))) return false
  if (request.draft.id === null) return value.revision === 0
  if (value.id !== request.draft.id || request.draft.expectedRevision === null) return false
  const revision = request.draft.expectedRevision
  if (value.revision === revision) return previous !== undefined && equal(value, previous)
  return revision < Number.MAX_SAFE_INTEGER && value.revision === revision + 1 && (previous === undefined || previous.id === value.id && previous.revision === revision
    && previous.status === 'ACTIVE' && equal(value.answer, previous.answer) && equal(value.question, previous.question) && value.checkpointId === previous.checkpointId
    && value.binding.answerHash === previous.binding.answerHash && value.binding.questionSnapshotHash === previous.binding.questionSnapshotHash
    && (!equal(value.binding.standardReview, previous.binding.standardReview) || value.binding.standardBasisHash === previous.binding.standardBasisHash)
    && sameTime(value.createdAt, previous.createdAt) && beforeOrEqual(previous.revisedAt, value.revisedAt))
}
export function withdrawnObservationValid(value: unknown, request: WithdrawRecord, previous: ReviewedObservation, input?: AiEvaluationInput): value is ObservationHistory {
  if (!withdrawRecordValid(request) || !reviewedObservationValid(previous, {}, input) || previous.revision !== request.expectedRevision
    || !observationHistoryValid(value, { id: previous.id, answer: previous.answer, question: previous.question, checkpointId: previous.checkpointId }, input)
    || value.change !== 'WITHDRAWN' || value.snapshot.status !== 'WITHDRAWN') return false
  const snapshot = value.snapshot
  if (snapshot.revision === previous.revision) return previous.status === 'WITHDRAWN' && equal(snapshot, previous) && value.changeReason === request.reason
  return previous.status === 'ACTIVE' && previous.revision < Number.MAX_SAFE_INTEGER && snapshot.revision === previous.revision + 1 && value.changeReason === request.reason
    && equal(snapshot.content, previous.content) && equal(snapshot.binding, previous.binding) && sameTime(snapshot.createdAt, previous.createdAt) && beforeOrEqual(previous.revisedAt, snapshot.revisedAt)
}
function queryShape(value: unknown, required: readonly string[], optional: readonly string[]): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key))
    && Object.keys(value).every(key => (required.includes(key) || optional.includes(key)) && (value as Record<string, unknown>)[key] !== undefined))
}
export function observationQueryValid(value: unknown): value is ObservationQuery {
  return queryShape(value, ['sessionId', 'questionId', 'answerId'], ['status', 'cursor', 'limit']) && id(value.sessionId) && id(value.questionId) && id(value.answerId)
    && (value.status === undefined || oneOf(value.status, recordStatuses)) && (value.cursor === undefined || id(value.cursor)) && (value.limit === undefined || integer(value.limit, 1, 50))
}
export const observationHistoryQueryValid = standardHistoryQueryValid
export function observationPageValid(value: unknown, query?: ObservationQuery): value is ObservationPage {
  if (!fields(value, ['items', 'nextCursor']) || !bounded(value) || query !== undefined && !observationQueryValid(query)
    || !list(value.items, observationSummaryValid, 0, query?.limit ?? 20) || value.nextCursor !== null && !id(value.nextCursor)) return false
  const items = value.items
  return items.every((item, index) => item.eligibility.revisionCurrent && (index === 0 || items[index - 1]!.id < item.id)
    && (query === undefined || reviewAnswerMatches(item.answer, query) && (query.status === undefined || item.status === query.status) && (query.cursor === undefined || item.id > query.cursor)))
    && (value.nextCursor === null || items.length === (query?.limit ?? 20) && value.nextCursor === items[items.length - 1]!.id)
}
export function observationHistoryPageValid(value: unknown, observationId?: string, query: ObservationHistoryQuery = {}, answer?: AnswerLink, input?: AiEvaluationInput): value is ObservationHistoryPage {
  if (!fields(value, ['items', 'nextBefore']) || !bounded(value) || !standardHistoryQueryValid(query)
    || !list(value.items, (item): item is ObservationHistory => observationHistoryValid(item, { id: observationId, answer }, input), 0, query.limit ?? 20)
    || value.nextBefore !== null && !integer(value.nextBefore)) return false
  const items = value.items, first = items[0]?.snapshot, standardHashes = new Map<string, string>()
  return items.every((item, index) => (query.before === undefined || item.snapshot.revision < query.before)
    && (first === undefined || item.snapshot.id === first.id && equal(item.snapshot.answer, first.answer) && equal(item.snapshot.question, first.question)
      && item.snapshot.checkpointId === first.checkpointId && sameTime(item.snapshot.createdAt, first.createdAt) && item.snapshot.binding.answerHash === first.binding.answerHash
      && item.snapshot.binding.questionSnapshotHash === first.binding.questionSnapshotHash)
    && (index === 0 || items[index - 1]!.snapshot.revision === item.snapshot.revision + 1 && beforeOrEqual(item.snapshot.revisedAt, items[index - 1]!.snapshot.revisedAt) && item.snapshot.status !== 'WITHDRAWN')
    && check(() => {
      const binding = item.snapshot.binding, key = `${binding.standardReview.id}/${binding.standardReview.revision}`, recorded = standardHashes.get(key)
      if (recorded !== undefined && recorded !== binding.standardBasisHash) return false
      standardHashes.set(key, binding.standardBasisHash); return true
    }))
    && (value.nextBefore === null ? items.length === 0 || items[items.length - 1]!.snapshot.revision === 0
      : items.length === (query.limit ?? 20) && value.nextBefore > 0 && value.nextBefore === items[items.length - 1]!.snapshot.revision)
}
export function recordedFieldsValid(value: unknown): value is RecordedFields { return fields(value, ['technology', 'scenario', 'conditions']) && typeof value.technology === 'boolean' && typeof value.scenario === 'boolean' && typeof value.conditions === 'boolean' }
export function observationAnswerMetaValid(value: unknown): value is ObservationAnswerMeta {
  return fields(value, ['mode', 'answerCondition', 'assistanceEventSequence', 'priorExposure', 'questionKind', 'answerNumber', 'answerCreatedAt', 'recordedFields']) && answerConditionFieldsValid(value) && recordedFieldsValid(value.recordedFields)
}
export function observationProjectionValid(value: unknown): value is ObservationProjection {
  if (!fields(value, ['ref', 'change', 'recordedStatus', 'answer', 'question', 'checkpointId', 'standardReview', 'recordedAt', 'observedOn', 'observationZoneId', 'coverage', 'conclusion', 'answerMeta', 'eligibility'])
    || !revisionRefValid(value.ref) || !oneOf(value.recordedStatus, recordStatuses) || !historyChangeValid(value.change, value.ref.revision, value.recordedStatus)
    || !observationAnswerValid(value.answer) || !questionVersionRefValid(value.question) || !id(value.checkpointId) || !revisionRefValid(value.standardReview)
    || !timestamp(value.recordedAt) || !standardReviewZoneValid(value.observationZoneId) || !observationCoverageConclusionValid(value.coverage, value.conclusion)
    || !oneOf(value.conclusion, observationConclusions) || !observationAnswerMetaValid(value.answerMeta) || !beforeOrEqual(value.answerMeta.answerCreatedAt, value.recordedAt)
    || !observedDateValid(value.observedOn, value.observationZoneId, value.recordedAt, value.answerMeta.answerCreatedAt) || !observationEligibilityValid(value.eligibility)) return false
  return eligibilityMatches(value.eligibility, { revision: value.ref.revision, status: value.recordedStatus, standardReview: value.standardReview, conclusion: value.conclusion })
}
function invalidQuery(): never { throw new ApiError(400, 'INVALID_REQUEST', '观察参数不符合约定，请核对确切答案、记录修订及分页位置。') }
function queryPath(path: string, query: object): string {
  const parameters = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) parameters.set(key, String(value))
  return parameters.size ? `${path}?${parameters}` : path
}
export function observationPath(observationId: string): string { if (!id(observationId)) invalidQuery(); return `/review/observations/${observationId}` }
export function observationListPath(query: ObservationQuery): string { if (!observationQueryValid(query)) invalidQuery(); return queryPath('/review/observations', query) }
export function observationDetailPath(observationId: string, revision?: number): string {
  if (revision !== undefined && !integer(revision)) invalidQuery()
  return queryPath(observationPath(observationId), revision === undefined ? {} : { revision })
}
export function observationHistoryPath(observationId: string, query: ObservationHistoryQuery = {}): string {
  if (!standardHistoryQueryValid(query)) invalidQuery()
  return queryPath(`${observationPath(observationId)}/history`, query)
}
export const observationPaths = { list: observationListPath, detail: observationDetailPath, preview: '/review/observations/preview', create: '/review/observations', update: observationPath,
  withdraw: (observationId: string) => `${observationPath(observationId)}/withdraw`, history: observationHistoryPath } as const
export function emptyObservationContent(standardReview: RevisionRef, observationZoneId = 'UTC'): ObservationContent {
  return { standardReview: { ...standardReview }, coverage: 'UNCERTAIN', conclusion: 'INSUFFICIENT', statement: '', conditions: '', limitations: '', observerDescription: '', observedOn: '', observationZoneId, citations: [] }
}
export function emptyObservationDraft(answer: AnswerLink, checkpointId: string, standardReview: RevisionRef, observationZoneId = 'UTC'): ObservationDraft {
  return { id: null, expectedRevision: null, answer: { ...answer }, checkpointId, content: emptyObservationContent(standardReview, observationZoneId), changeReason: null }
}
export const observationConclusionLabels: Record<ObservationConclusion, string> = { SUPPORTS_CHECKPOINT: '在所述条件下支持检查项', CONTRADICTS_CHECKPOINT: '在所述条件下与检查项矛盾', INSUFFICIENT: '现有原文不足以判断', DISPUTED: '结论存在争议' }
export const observationCoverageLabels: Record<ObservationCoverage, string> = { MENTIONED: '原文有提及', NOT_MENTIONED: '人工观察：本次原文未提及', UNCERTAIN: '暂无法判断是否提及' }
export const observationCitationKindLabels: Record<ObservationCitationKind, string> = { ANSWER_TEXT: '确切回答原文', ANSWER_NODE_LABEL: '答案图节点名称', ANSWER_NODE_DESCRIPTION: '答案图节点说明', ANSWER_EDGE_LABEL: '答案图连线说明', CHECKPOINT: '所选确切检查项' }
export function observationErrorText(error: unknown): string {
  if (!(error instanceof ApiError)) return standardReviewErrorText(error)
  const messages: Record<string, string> = {
    REVIEWED_OBSERVATION_NOT_FOUND: '观察记录或确切修订不存在或不可访问。', OBSERVATION_STANDARD_MISMATCH: '这次答案自身的题目版本或检查项与所选审阅不一致，请核对原来源。',
    AI_EVALUATION_ACCESS_REQUIRED: '请先在原评价入口明确打开这次确切提交，再查看或记录观察。', STANDARD_REVIEW_NOT_ELIGIBLE: '所选审阅修订或检查项当前不具备观察资格，请重新核对。',
    REVIEW_CITATION_INVALID: '引文与确切答案、所选检查项或覆盖范围不一致，请重新选择原文。', REVIEW_RECORD_WITHDRAWN: '观察已撤回；新的观察需要另建记录，原历史仍可追溯。',
  }
  return messages[error.code] ?? standardReviewErrorText(error)
}
