import type { Schema } from './client'
import { questionDimensionLabels } from './bank'

export type RelationFeedbackEvidenceSelection = Schema['RelationFeedbackEvidenceSelection']
export type RelationFeedbackEvidenceCreate = Schema['CreateRelationFeedbackEvidence']
export type RelationFeedbackEvidenceEntry = Schema['RelationFeedbackEvidenceEntry']
export type RelationFeedbackEvidencePage = Schema['RelationFeedbackEvidencePage']
export type RelationFeedbackEvidenceDetail = Schema['RelationFeedbackEvidenceDetail']
export type RelationFeedbackEvidenceSource = Schema['RelationFeedbackEvidenceSource']
export type RelationFeedbackEvidenceField = Extract<Schema['AiReviewDraftField'], 'OBSERVATION' | 'CONDITION' | 'BASIS' | 'LIMITATION' | 'SUGGESTION'>
export type RelationFeedbackEvidenceFragment = Omit<Schema['AiReviewDraftFragment'], 'field'> & { field: RelationFeedbackEvidenceField }
export type RelationFeedbackChoice = {
  kind: 'SELF_CHECK' | 'EXTERNAL_FEEDBACK'; feedbackId: string; feedbackRevision: number; answer: Schema['ReviewAnswerLink']
}

export const relationFeedbackEvidenceFieldLabels: Record<RelationFeedbackEvidenceField, string> = {
  OBSERVATION: '反馈观察', CONDITION: '适用条件', BASIS: '反馈依据', LIMITATION: '反馈限制', SUGGESTION: '反馈建议',
}
export const relationFeedbackEvidencePaths = {
  source: (feedbackId: string, revision: number) => `/knowledge/relation-feedback-sources/${encodeURIComponent(feedbackId)}?revision=${revision}`,
  create: (relationId: string) => `/knowledge/relations/${encodeURIComponent(relationId)}/feedback-evidence`,
  page: (relationId: string, cursor?: string, limit = 20) => {
    const query = new URLSearchParams({ limit: String(limit) })
    if (cursor !== undefined) query.set('cursor', cursor)
    return `/knowledge/relations/${encodeURIComponent(relationId)}/feedback-evidence?${query}`
  },
  detail: (relationId: string, evidenceId: string) =>
    `/knowledge/relations/${encodeURIComponent(relationId)}/feedback-evidence/${encodeURIComponent(evidenceId)}`,
} as const

const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && names.every(name => Object.hasOwn(value, name)) && Object.keys(value).every(name => names.includes(name)))
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown, minimum = 0, maximum = 2147483647): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const text = (value: unknown, maximum: number): value is string => typeof value === 'string' && value.length <= maximum
  && !/[\u0000\uD800-\uDFFF]/u.test(value)
const hasText = (value: string) => /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
// 反馈正文沿原码点上限与 Java isBlank；选区和新理由分别按 UTF-16 核对，不裁剪历史原文。
const sourceText = (value: unknown, maximum: number, required = true): value is string => text(value, maximum * 2)
  && Array.from(value).length <= maximum && (!required || hasText(value))
const reasonValid = (value: unknown): value is string => text(value, 1000) && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value)
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const kindValid = (value: unknown): value is RelationFeedbackChoice['kind'] => value === 'SELF_CHECK' || value === 'EXTERNAL_FEEDBACK'
const answerValid = (value: unknown): value is RelationFeedbackChoice['answer'] => {
  const answer = value as RelationFeedbackChoice['answer']
  return fields(answer, ['sessionId', 'questionId', 'answerId']) && uuid(answer.sessionId) && uuid(answer.questionId) && uuid(answer.answerId)
}
const answerFingerprint = (answer: RelationFeedbackChoice['answer']) => JSON.stringify([answer.sessionId, answer.questionId, answer.answerId])
const decisionValid = (revision: unknown, choice: unknown): boolean => integer(revision, 0, 20)
  && (revision === 0 ? choice === null : typeof choice === 'string' && ['ACCEPTED', 'DISPUTED', 'IGNORED'].includes(choice))
const answerConditions = ['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED']
const coverages = ['MENTIONED', 'NOT_MENTIONED', 'UNCERTAIN']
const relationTypes = ['PRECEDES', 'PART_OF', 'APPLIED_IN', 'RELATED', 'CONTRASTS']
const sourceFields: Record<RelationFeedbackEvidenceField, 'observation' | 'condition' | 'basis' | 'limitation' | 'suggestion'> = {
  OBSERVATION: 'observation', CONDITION: 'condition', BASIS: 'basis', LIMITATION: 'limitation', SUGGESTION: 'suggestion',
}

export function relationFeedbackChoiceValid(value: unknown): value is RelationFeedbackChoice {
  const choice = value as RelationFeedbackChoice
  return fields(choice, ['kind', 'feedbackId', 'feedbackRevision', 'answer']) && kindValid(choice.kind)
    && uuid(choice.feedbackId) && integer(choice.feedbackRevision, 1, 100) && answerValid(choice.answer)
}

export function relationFeedbackEvidenceSourceValid(value: unknown, feedbackId: string, revision: number, choice?: RelationFeedbackChoice): value is RelationFeedbackEvidenceSource {
  const source = value as RelationFeedbackEvidenceSource
  return uuid(feedbackId) && integer(revision, 1, 100)
    && fields(source, ['kind', 'feedbackId', 'feedbackRevision', 'answer', 'answerNumber', 'answerCondition', 'dimension', 'coverage',
      'decisionRevision', 'decisionChoice', 'observation', 'condition', 'basis', 'limitation', 'suggestion'])
    && kindValid(source.kind) && source.feedbackId === feedbackId && source.feedbackRevision === revision && answerValid(source.answer)
    && integer(source.answerNumber, 1, 100) && answerConditions.includes(source.answerCondition)
    && typeof source.dimension === 'string' && Object.hasOwn(questionDimensionLabels, source.dimension) && coverages.includes(source.coverage)
    && decisionValid(source.decisionRevision, source.decisionChoice) && sourceText(source.observation, 2000)
    && sourceText(source.condition, 1000, false) && sourceText(source.basis, 1000) && sourceText(source.limitation, 1000) && sourceText(source.suggestion, 1000)
    && (choice === undefined || relationFeedbackChoiceValid(choice) && source.kind === choice.kind && feedbackId === choice.feedbackId
      && revision === choice.feedbackRevision && answerFingerprint(source.answer) === answerFingerprint(choice.answer))
}

export function relationFeedbackEvidenceSourceFingerprint(source: RelationFeedbackEvidenceSource): string {
  return JSON.stringify([source.kind, source.feedbackId, source.feedbackRevision, answerFingerprint(source.answer), source.answerNumber,
    source.answerCondition, source.dimension, source.coverage, source.decisionRevision, source.decisionChoice,
    source.observation, source.condition, source.basis, source.limitation, source.suggestion])
}

export function relationFeedbackEvidenceRangeMatches(original: string, start: number, end: number, quote: string): boolean {
  if (!text(original, Number.MAX_SAFE_INTEGER) || !text(quote, 8000) || !hasText(quote)
    || !integer(start) || !integer(end, 1) || end <= start || end > original.length) return false
  const split = (position: number) => position > 0 && position < original.length
    && /[\uD800-\uDBFF]/.test(original.charAt(position - 1)) && /[\uDC00-\uDFFF]/.test(original.charAt(position))
  return !split(start) && !split(end) && original.slice(start, end) === quote
}

export function relationFeedbackEvidenceFragmentsValid(value: unknown): value is RelationFeedbackEvidenceFragment[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 8) return false
  const fragments = value as RelationFeedbackEvidenceFragment[]
  return fragments.every((fragment, index) => fields(fragment, ['field', 'start', 'end', 'quote'])
    && typeof fragment.field === 'string' && Object.hasOwn(relationFeedbackEvidenceFieldLabels, fragment.field) && integer(fragment.start) && integer(fragment.end, 1)
    && fragment.end > fragment.start && text(fragment.quote, 8000) && hasText(fragment.quote)
    && fragment.end - fragment.start === fragment.quote.length
    && !fragments.slice(0, index).some(other => other.field === fragment.field && other.start < fragment.end && fragment.start < other.end))
}

export function relationFeedbackEvidenceSelectionValid(value: unknown, expectedSource?: RelationFeedbackEvidenceSource): value is RelationFeedbackEvidenceSelection {
  const selection = value as RelationFeedbackEvidenceSelection
  if (!fields(selection, ['kind', 'feedbackId', 'feedbackRevision', 'expectedDecisionRevision', 'answer', 'fragments'])
    || !kindValid(selection.kind) || !uuid(selection.feedbackId) || !integer(selection.feedbackRevision, 1, 100)
    || !integer(selection.expectedDecisionRevision, 0, 20) || !answerValid(selection.answer) || !relationFeedbackEvidenceFragmentsValid(selection.fragments)) return false
  return expectedSource === undefined || relationFeedbackEvidenceSourceValid(expectedSource, selection.feedbackId, selection.feedbackRevision)
    && expectedSource.kind === selection.kind && expectedSource.decisionRevision === selection.expectedDecisionRevision
    && answerFingerprint(expectedSource.answer) === answerFingerprint(selection.answer)
    && selection.fragments.every(fragment => relationFeedbackEvidenceRangeMatches(expectedSource[sourceFields[fragment.field]], fragment.start, fragment.end, fragment.quote))
}

export function relationFeedbackEvidenceCreateValid(value: unknown, expectedSource?: RelationFeedbackEvidenceSource): value is RelationFeedbackEvidenceCreate {
  const request = value as RelationFeedbackEvidenceCreate
  return fields(request, ['reason', 'source', 'confirmed']) && reasonValid(request.reason) && request.confirmed === true
    && relationFeedbackEvidenceSelectionValid(request.source, expectedSource)
}

export function relationFeedbackEvidenceEntryValid(value: unknown, relationId?: string, evidenceId?: string): value is RelationFeedbackEvidenceEntry {
  const entry = value as RelationFeedbackEvidenceEntry
  return fields(entry, ['id', 'relationId', 'sourceId', 'targetId', 'type', 'reason', 'sourceKind', 'feedbackId', 'feedbackRevision', 'sourceCount', 'createdAt'])
    && uuid(entry.id) && uuid(entry.relationId) && (relationId === undefined || relationId === entry.relationId)
    && (evidenceId === undefined || evidenceId === entry.id) && uuid(entry.sourceId) && uuid(entry.targetId) && entry.sourceId !== entry.targetId
    && relationTypes.includes(entry.type) && (!['RELATED', 'CONTRASTS'].includes(entry.type) || entry.sourceId < entry.targetId)
    && reasonValid(entry.reason) && kindValid(entry.sourceKind) && uuid(entry.feedbackId) && integer(entry.feedbackRevision, 1, 100)
    && integer(entry.sourceCount, 1, 8) && timestamp(entry.createdAt)
}

export function relationFeedbackEvidenceEntryFingerprint(entry: RelationFeedbackEvidenceEntry): string {
  return JSON.stringify([entry.id, entry.relationId, entry.sourceId, entry.targetId, entry.type, entry.reason,
    entry.sourceKind, entry.feedbackId, entry.feedbackRevision, entry.sourceCount, entry.createdAt])
}

export function relationFeedbackEvidenceEntryMatches(value: unknown, relationId: string, request: RelationFeedbackEvidenceCreate): value is RelationFeedbackEvidenceEntry {
  if (!relationFeedbackEvidenceCreateValid(request) || !relationFeedbackEvidenceEntryValid(value, relationId)) return false
  // 回执只有元数据；保存成功后不为比对选区而自动读取历史详情或反馈正文。
  return value.reason === request.reason && value.sourceKind === request.source.kind && value.feedbackId === request.source.feedbackId
    && value.feedbackRevision === request.source.feedbackRevision && value.sourceCount === request.source.fragments.length
}

export function relationFeedbackEvidencePageValid(value: unknown, relationId: string, cursor?: string, limit = 20): value is RelationFeedbackEvidencePage {
  const page = value as RelationFeedbackEvidencePage
  if (!uuid(relationId) || cursor !== undefined && !uuid(cursor) || !integer(limit, 1, 20)
    || !fields(page, ['relationId', 'relationExists', 'items', 'nextCursor']) || page.relationId !== relationId
    || typeof page.relationExists !== 'boolean' || !Array.isArray(page.items) || page.items.length > limit
    || page.nextCursor !== null && !uuid(page.nextCursor)) return false
  let previous = cursor, tuple: string | undefined
  for (const entry of page.items) {
    if (!relationFeedbackEvidenceEntryValid(entry, relationId) || previous !== undefined && entry.id <= previous) return false
    const identity = JSON.stringify([entry.sourceId, entry.targetId, entry.type])
    if (tuple !== undefined && tuple !== identity) return false
    tuple = identity; previous = entry.id
  }
  return (page.relationExists || cursor !== undefined || page.items.length > 0)
    && (page.nextCursor === null || page.items.length === limit && page.nextCursor === page.items.at(-1)?.id)
}

function snapshotValid(value: unknown): value is RelationFeedbackEvidenceDetail['source'] {
  const source = value as RelationFeedbackEvidenceDetail['source']
  if (!fields(source, ['kind', 'feedback', 'recollection']) || !kindValid(source.kind) || source.recollection !== null) return false
  const feedback = source.feedback
  if (!feedback || !fields(feedback, ['feedbackId', 'feedbackRevision', 'answer', 'answerNumber', 'answerCondition', 'dimension', 'coverage',
    'decisionRevision', 'decisionChoice', 'condition', 'basis', 'limitation', 'fragments']) || !uuid(feedback.feedbackId)
    || !integer(feedback.feedbackRevision, 1, 100) || !answerValid(feedback.answer) || !integer(feedback.answerNumber, 1, 100)
    || !answerConditions.includes(feedback.answerCondition) || typeof feedback.dimension !== 'string' || !Object.hasOwn(questionDimensionLabels, feedback.dimension)
    || !coverages.includes(feedback.coverage) || !decisionValid(feedback.decisionRevision, feedback.decisionChoice)
    || !sourceText(feedback.condition, 1000, false) || !sourceText(feedback.basis, 1000) || !sourceText(feedback.limitation, 1000)
    || !relationFeedbackEvidenceFragmentsValid(feedback.fragments)) return false
  // 详情仅保留所选片段及完整条件、依据、限制；有完整语境的三种选区必须逐字对应。
  return feedback.fragments.every(fragment => {
    const original = fragment.field === 'CONDITION' ? feedback.condition : fragment.field === 'BASIS' ? feedback.basis
      : fragment.field === 'LIMITATION' ? feedback.limitation : undefined
    return original === undefined || relationFeedbackEvidenceRangeMatches(original, fragment.start, fragment.end, fragment.quote)
  })
}

export function relationFeedbackEvidenceDetailValid(value: unknown, relationId: string, evidenceId: string, expectedEntry?: RelationFeedbackEvidenceEntry): value is RelationFeedbackEvidenceDetail {
  const detail = value as RelationFeedbackEvidenceDetail
  if (!uuid(relationId) || !uuid(evidenceId) || !fields(detail, ['entry', 'relationExists', 'source', 'sourceState'])
    || !relationFeedbackEvidenceEntryValid(detail.entry, relationId, evidenceId) || typeof detail.relationExists !== 'boolean'
    || !['CURRENT', 'SUPERSEDED', 'REVIEW_REQUIRED', 'UNAVAILABLE'].includes(detail.sourceState) || !snapshotValid(detail.source)
    || expectedEntry !== undefined && (!relationFeedbackEvidenceEntryValid(expectedEntry, relationId, evidenceId)
      || relationFeedbackEvidenceEntryFingerprint(detail.entry) !== relationFeedbackEvidenceEntryFingerprint(expectedEntry))) return false
  const feedback = detail.source.feedback!
  return detail.source.kind === detail.entry.sourceKind && feedback.feedbackId === detail.entry.feedbackId
    && feedback.feedbackRevision === detail.entry.feedbackRevision && feedback.fragments.length === detail.entry.sourceCount
}
