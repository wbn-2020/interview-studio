import type { Schema } from './client'
import { questionDimensionLabels } from './bank'
import type { RelationFeedbackEvidenceField, RelationFeedbackEvidenceFragment } from './relationFeedbackEvidence'
import { relationFeedbackEvidenceFieldLabels, relationFeedbackEvidenceFragmentsValid } from './relationFeedbackEvidence'

export type RelationAiFeedbackEvidenceSelection = Schema['RelationAiFeedbackEvidenceSelection']
export type RelationAiFeedbackEvidenceCreate = Schema['CreateRelationAiFeedbackEvidence']
export type RelationAiFeedbackEvidenceSource = Schema['RelationAiFeedbackEvidenceSource']
export type RelationAiFeedbackEvidenceSnapshot = Schema['RelationAiFeedbackEvidenceSnapshot']
export type RelationAiFeedbackEvidenceEntry = Schema['RelationAiFeedbackEvidenceEntry']
export type RelationAiFeedbackEvidencePage = Schema['RelationAiFeedbackEvidencePage']
export type RelationAiFeedbackEvidenceDetail = Schema['RelationAiFeedbackEvidenceDetail']
export type RelationAiFeedbackEvidenceField = RelationFeedbackEvidenceField
export type RelationAiFeedbackEvidenceFragment = RelationFeedbackEvidenceFragment
export type RelationAiFeedbackChoice = { taskId: string; feedbackId: string; answer: Schema['ReviewAnswerLink'] }

export const relationAiFeedbackEvidenceFieldLabels = relationFeedbackEvidenceFieldLabels
export const relationAiFeedbackEvidenceFragmentsValid = relationFeedbackEvidenceFragmentsValid
export const relationAiFeedbackEvidencePaths = {
  source: (taskId: string, feedbackId: string) => `/knowledge/relation-ai-feedback-sources/${encodeURIComponent(taskId)}/feedback/${encodeURIComponent(feedbackId)}`,
  create: (relationId: string) => `/knowledge/relations/${encodeURIComponent(relationId)}/ai-feedback-evidence`,
  page: (relationId: string, cursor?: string, limit = 20) => {
    const query = new URLSearchParams({ limit: String(limit) })
    if (cursor !== undefined) query.set('cursor', cursor)
    return `/knowledge/relations/${encodeURIComponent(relationId)}/ai-feedback-evidence?${query}`
  },
  detail: (relationId: string, evidenceId: string) =>
    `/knowledge/relations/${encodeURIComponent(relationId)}/ai-feedback-evidence/${encodeURIComponent(evidenceId)}`,
} as const

const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && names.every(name => Object.hasOwn(value, name)) && Object.keys(value).every(name => names.includes(name)))
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const feedbackIdValid = (value: unknown): value is string => typeof value === 'string' && /^f([1-9]|1[0-2])$/.test(value)
const hashValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const integer = (value: unknown, minimum = 0, maximum = 2147483647): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const unicode = (value: unknown): value is string => typeof value === 'string' && !/[\uD800-\uDFFF]/u.test(value)
const hasText = (value: string) => /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
// 旧 AI 报告完整字段允许历史 NUL，保留码点和 Java isBlank 规则；新选区与理由单独禁止 NUL。
const sourceText = (value: unknown, maximum: number, required = true): value is string => unicode(value)
  && value.length <= maximum * 2 && Array.from(value).length <= maximum && (!required || hasText(value))
const reasonValid = (value: unknown): value is string => unicode(value) && value.length <= 1000 && !value.includes('\0')
  && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value)
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const answerValid = (value: unknown): value is RelationAiFeedbackChoice['answer'] => {
  const answer = value as RelationAiFeedbackChoice['answer']
  return fields(answer, ['sessionId', 'questionId', 'answerId']) && uuid(answer.sessionId) && uuid(answer.questionId) && uuid(answer.answerId)
}
const answerFingerprint = (answer: RelationAiFeedbackChoice['answer']) => JSON.stringify([answer.sessionId, answer.questionId, answer.answerId])
const decisionValid = (revision: unknown, choice: unknown): boolean => integer(revision, 0, 20)
  && (revision === 0 ? choice === null : typeof choice === 'string' && ['ACCEPTED', 'DISPUTED', 'IGNORED'].includes(choice))
const relationTypes = ['PRECEDES', 'PART_OF', 'APPLIED_IN', 'RELATED', 'CONTRASTS']
const validationNote = 'AI 未审评价，结构与确切输入的原文定位已核对；仅表示结构和定位合法，不证明语义、技术正确性或用户经历真实。个人参考与检查点仍为未审标准，内容需人工审阅。'
const sourceFields: Record<RelationAiFeedbackEvidenceField, 'observation' | 'condition' | 'basis' | 'limitation' | 'suggestion'> = {
  OBSERVATION: 'observation', CONDITION: 'condition', BASIS: 'basis', LIMITATION: 'limitation', SUGGESTION: 'suggestion',
}

function sourceMetadataValid(source: RelationAiFeedbackEvidenceSource | RelationAiFeedbackEvidenceSnapshot): boolean {
  return uuid(source.taskId) && uuid(source.attemptId) && feedbackIdValid(source.feedbackId) && hashValid(source.reportHash)
    && answerValid(source.answer) && integer(source.answerNumber, 1, 100) && typeof source.answerCondition === 'string'
    && ['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(source.answerCondition) && source.level === 'AI_UNREVIEWED'
    && sourceText(source.reportLimitations, 2000) && source.validationNote === validationNote
    && typeof source.dimension === 'string' && Object.hasOwn(questionDimensionLabels, source.dimension)
    && typeof source.coverage === 'string' && ['MENTIONED', 'NOT_MENTIONED', 'UNCERTAIN'].includes(source.coverage)
    && decisionValid(source.decisionRevision, source.decisionChoice) && sourceText(source.condition, 1000, false)
    && sourceText(source.basis, 1000) && sourceText(source.limitation, 1000)
}

export function relationAiFeedbackChoiceValid(value: unknown): value is RelationAiFeedbackChoice {
  const choice = value as RelationAiFeedbackChoice
  return fields(choice, ['taskId', 'feedbackId', 'answer']) && uuid(choice.taskId) && feedbackIdValid(choice.feedbackId) && answerValid(choice.answer)
}

export function relationAiFeedbackEvidenceSourceValid(value: unknown, taskId: string, feedbackId: string, choice?: RelationAiFeedbackChoice): value is RelationAiFeedbackEvidenceSource {
  const source = value as RelationAiFeedbackEvidenceSource
  return uuid(taskId) && feedbackIdValid(feedbackId)
    && fields(source, ['taskId', 'attemptId', 'feedbackId', 'reportHash', 'answer', 'answerNumber', 'answerCondition', 'level', 'reportLimitations',
      'validationNote', 'dimension', 'coverage', 'decisionRevision', 'decisionChoice', 'observation', 'condition', 'basis', 'limitation', 'suggestion'])
    && sourceMetadataValid(source) && source.taskId === taskId && source.feedbackId === feedbackId
    && sourceText(source.observation, 2000) && sourceText(source.suggestion, 1000)
    && (choice === undefined || relationAiFeedbackChoiceValid(choice) && choice.taskId === taskId && choice.feedbackId === feedbackId
      && answerFingerprint(choice.answer) === answerFingerprint(source.answer))
}

export function relationAiFeedbackEvidenceSourceFingerprint(source: RelationAiFeedbackEvidenceSource): string {
  // 固定成功尝试、报告原文摘要与本条决定，不用可因费用核对变化的任务 revision 代替证据身份。
  return JSON.stringify([source.taskId, source.attemptId, source.feedbackId, source.reportHash, answerFingerprint(source.answer), source.answerNumber,
    source.answerCondition, source.level, source.reportLimitations, source.validationNote, source.dimension, source.coverage,
    source.decisionRevision, source.decisionChoice, source.observation, source.condition, source.basis, source.limitation, source.suggestion])
}

export function relationAiFeedbackEvidenceRangeMatches(original: string, start: number, end: number, quote: string): boolean {
  if (!unicode(original) || !unicode(quote) || quote.includes('\0') || quote.length > 8000 || !hasText(quote)
    || !integer(start) || !integer(end, 1) || end <= start || end > original.length) return false
  const split = (position: number) => position > 0 && position < original.length
    && /[\uD800-\uDBFF]/.test(original.charAt(position - 1)) && /[\uDC00-\uDFFF]/.test(original.charAt(position))
  return !split(start) && !split(end) && original.slice(start, end) === quote
}

export function relationAiFeedbackEvidenceSelectionValid(value: unknown, expectedSource?: RelationAiFeedbackEvidenceSource): value is RelationAiFeedbackEvidenceSelection {
  const selection = value as RelationAiFeedbackEvidenceSelection
  if (!fields(selection, ['taskId', 'attemptId', 'feedbackId', 'reportHash', 'expectedDecisionRevision', 'answer', 'fragments'])
    || !uuid(selection.taskId) || !uuid(selection.attemptId) || !feedbackIdValid(selection.feedbackId) || !hashValid(selection.reportHash)
    || !integer(selection.expectedDecisionRevision, 0, 20) || !answerValid(selection.answer) || !relationAiFeedbackEvidenceFragmentsValid(selection.fragments)) return false
  return expectedSource === undefined || relationAiFeedbackEvidenceSourceValid(expectedSource, selection.taskId, selection.feedbackId)
    && selection.attemptId === expectedSource.attemptId && selection.reportHash === expectedSource.reportHash
    && selection.expectedDecisionRevision === expectedSource.decisionRevision && answerFingerprint(selection.answer) === answerFingerprint(expectedSource.answer)
    && selection.fragments.every(fragment => relationAiFeedbackEvidenceRangeMatches(expectedSource[sourceFields[fragment.field]], fragment.start, fragment.end, fragment.quote))
}

export function relationAiFeedbackEvidenceCreateValid(value: unknown, expectedSource?: RelationAiFeedbackEvidenceSource): value is RelationAiFeedbackEvidenceCreate {
  const request = value as RelationAiFeedbackEvidenceCreate
  return fields(request, ['reason', 'source', 'confirmed']) && reasonValid(request.reason) && request.confirmed === true
    && relationAiFeedbackEvidenceSelectionValid(request.source, expectedSource)
}

export function relationAiFeedbackEvidenceSnapshotValid(value: unknown): value is RelationAiFeedbackEvidenceSnapshot {
  const source = value as RelationAiFeedbackEvidenceSnapshot
  if (!fields(source, ['taskId', 'attemptId', 'feedbackId', 'reportHash', 'answer', 'answerNumber', 'answerCondition', 'level', 'reportLimitations',
    'validationNote', 'dimension', 'coverage', 'decisionRevision', 'decisionChoice', 'condition', 'basis', 'limitation', 'fragments'])
    || !sourceMetadataValid(source) || !relationAiFeedbackEvidenceFragmentsValid(source.fragments)) return false
  return source.fragments.every(fragment => {
    const original = fragment.field === 'CONDITION' ? source.condition : fragment.field === 'BASIS' ? source.basis
      : fragment.field === 'LIMITATION' ? source.limitation : undefined
    return original === undefined || relationAiFeedbackEvidenceRangeMatches(original, fragment.start, fragment.end, fragment.quote)
  })
}

export function relationAiFeedbackEvidenceSnapshotFingerprint(source: RelationAiFeedbackEvidenceSnapshot): string {
  // 完整背景与选区顺序共同固定外发范围；反馈决定变化不能复用上次确认。
  return JSON.stringify([source.taskId, source.attemptId, source.feedbackId, source.reportHash, answerFingerprint(source.answer), source.answerNumber,
    source.answerCondition, source.level, source.reportLimitations, source.validationNote, source.dimension, source.coverage,
    source.decisionRevision, source.decisionChoice, source.condition, source.basis, source.limitation,
    source.fragments.map(fragment => [fragment.field, fragment.start, fragment.end, fragment.quote])])
}

export function relationAiFeedbackEvidenceEntryValid(value: unknown, relationId?: string, evidenceId?: string): value is RelationAiFeedbackEvidenceEntry {
  const entry = value as RelationAiFeedbackEvidenceEntry
  return fields(entry, ['id', 'relationId', 'sourceId', 'targetId', 'type', 'reason', 'taskId', 'attemptId', 'feedbackId', 'sourceCount', 'createdAt'])
    && uuid(entry.id) && uuid(entry.relationId) && (relationId === undefined || entry.relationId === relationId)
    && (evidenceId === undefined || entry.id === evidenceId) && uuid(entry.sourceId) && uuid(entry.targetId) && entry.sourceId !== entry.targetId
    && typeof entry.type === 'string' && relationTypes.includes(entry.type) && (!['RELATED', 'CONTRASTS'].includes(entry.type) || entry.sourceId < entry.targetId)
    && reasonValid(entry.reason) && uuid(entry.taskId) && uuid(entry.attemptId) && feedbackIdValid(entry.feedbackId)
    && integer(entry.sourceCount, 1, 8) && timestamp(entry.createdAt)
}

export function relationAiFeedbackEvidenceEntryFingerprint(entry: RelationAiFeedbackEvidenceEntry): string {
  return JSON.stringify([entry.id, entry.relationId, entry.sourceId, entry.targetId, entry.type, entry.reason,
    entry.taskId, entry.attemptId, entry.feedbackId, entry.sourceCount, entry.createdAt])
}

export function relationAiFeedbackEvidenceEntryMatches(value: unknown, relationId: string, request: RelationAiFeedbackEvidenceCreate): value is RelationAiFeedbackEvidenceEntry {
  if (!relationAiFeedbackEvidenceCreateValid(request) || !relationAiFeedbackEvidenceEntryValid(value, relationId)) return false
  // 创建只返回元数据，核对固定任务、尝试、反馈和数量；不能据此声称已逐字比对保存的片段。
  return value.reason === request.reason && value.taskId === request.source.taskId && value.attemptId === request.source.attemptId
    && value.feedbackId === request.source.feedbackId && value.sourceCount === request.source.fragments.length
}

export function relationAiFeedbackEvidencePageValid(value: unknown, relationId: string, cursor?: string, limit = 20): value is RelationAiFeedbackEvidencePage {
  const page = value as RelationAiFeedbackEvidencePage
  if (!uuid(relationId) || cursor !== undefined && !uuid(cursor) || !integer(limit, 1, 20)
    || !fields(page, ['relationId', 'relationExists', 'items', 'nextCursor']) || page.relationId !== relationId
    || typeof page.relationExists !== 'boolean' || !Array.isArray(page.items) || page.items.length > limit
    || page.nextCursor !== null && !uuid(page.nextCursor)) return false
  let previous = cursor, tuple: string | undefined
  for (const entry of page.items) {
    if (!relationAiFeedbackEvidenceEntryValid(entry, relationId) || previous !== undefined && entry.id <= previous) return false
    const identity = JSON.stringify([entry.sourceId, entry.targetId, entry.type])
    if (tuple !== undefined && tuple !== identity) return false
    tuple = identity; previous = entry.id
  }
  return (page.relationExists || cursor !== undefined || page.items.length > 0)
    && (page.nextCursor === null || page.items.length === limit && page.nextCursor === page.items.at(-1)?.id)
}

export function relationAiFeedbackEvidenceDetailValid(value: unknown, relationId: string, evidenceId: string, expectedEntry?: RelationAiFeedbackEvidenceEntry): value is RelationAiFeedbackEvidenceDetail {
  const detail = value as RelationAiFeedbackEvidenceDetail
  if (!uuid(relationId) || !uuid(evidenceId) || !fields(detail, ['entry', 'relationExists', 'source', 'sourceState'])
    || !relationAiFeedbackEvidenceEntryValid(detail.entry, relationId, evidenceId) || typeof detail.relationExists !== 'boolean'
    || typeof detail.sourceState !== 'string' || !['CURRENT', 'REVIEW_REQUIRED', 'UNAVAILABLE'].includes(detail.sourceState)
    || !relationAiFeedbackEvidenceSnapshotValid(detail.source)
    || expectedEntry !== undefined && (!relationAiFeedbackEvidenceEntryValid(expectedEntry, relationId, evidenceId)
      || relationAiFeedbackEvidenceEntryFingerprint(detail.entry) !== relationAiFeedbackEvidenceEntryFingerprint(expectedEntry))) return false
  // CURRENT 仅说明该证据的固定源与决定仍一致，AI 未审等级和具体决定始终单独保留。
  return detail.source.taskId === detail.entry.taskId && detail.source.attemptId === detail.entry.attemptId
    && detail.source.feedbackId === detail.entry.feedbackId && detail.source.fragments.length === detail.entry.sourceCount
}
