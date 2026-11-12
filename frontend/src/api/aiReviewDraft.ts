import type { RouteLocationRaw } from 'vue-router'
import type { Schema } from './client'
import type { AiManifest, AiTask } from './ai'
import { aiRelationTaskMatches } from './ai'
import { questionDimensionLabels, questionTypeLabels } from './bank'
import { aiCardDraftTaskValid } from './aiCardDraft'

export type AiReviewDraftKind = Schema['AiReviewDraftKind']
export type AiReviewDraftField = Schema['AiReviewDraftField']
export type AiReviewDraftFragment = Schema['AiReviewDraftFragment']
export type AiReviewDraftFeedbackSelection = Schema['AiReviewDraftFeedbackSelection']
export type AiReviewDraftRecollectionSelection = Schema['AiReviewDraftRecollectionSelection']
export type AiReviewDraftSourceSelection = Schema['AiReviewDraftSourceSelection']
export type AiReviewDraftPreviewRequest = Schema['AiReviewDraftPreviewRequest']
export type AiReviewDraftCreateRequest = Schema['AiReviewDraftCreateRequest']
export type AiReviewDraftFeedbackInput = Schema['AiReviewDraftFeedbackInput']
export type AiReviewDraftRecollectionInput = Schema['AiReviewDraftRecollectionInput']
export type AiReviewDraftInput = Schema['AiReviewDraftInput']
export type AiReviewDraftCitation = Schema['AiReviewDraftCitation']
export type AiReviewDraftFeedbackOrigin = Schema['AiReviewDraftFeedbackOrigin']
export type AiReviewDraftRecollectionOrigin = Schema['AiReviewDraftRecollectionOrigin']
export type AiReviewDraftOrigin = Schema['AiReviewDraftOrigin']

export const aiReviewDraftPaths = { preview: '/ai/review-drafts/preview', create: '/ai/review-drafts/tasks' } as const
export const aiReviewDraftKindLabels: Record<AiReviewDraftKind, string> = {
  SELF_CHECK: '自检片段', EXTERNAL_FEEDBACK: '手工录入的外部反馈片段', RECOLLECTION: '面后回忆片段',
}
export const aiReviewDraftFieldLabels: Record<AiReviewDraftField, string> = {
  OBSERVATION: '反馈观察', CONDITION: '反馈适用条件', BASIS: '反馈依据', LIMITATION: '反馈限制', SUGGESTION: '反馈建议',
  QUESTION: '回忆问题', CONDITIONS: '回忆条件', ANSWER_FRAGMENT: '回忆回答片段', FEEDBACK: '回忆中的反馈', UNCERTAINTY: '回忆不确定性', GAP: '回忆缺口',
}
const feedbackFields: AiReviewDraftField[] = ['OBSERVATION', 'CONDITION', 'BASIS', 'LIMITATION', 'SUGGESTION']
const recollectionFields: AiReviewDraftField[] = ['QUESTION', 'CONDITIONS', 'ANSWER_FRAGMENT', 'FEEDBACK', 'UNCERTAINTY', 'GAP']
const specializedManifestFields = ['training', 'evaluation', 'contextChecks', 'materialQa', 'preparationChecks', 'materialDraftSources',
  'expressionType', 'preparationPlan', 'trainingDraft', 'expressionChecks', 'comparisonInput', 'comparisonChecks', 'cardDraftType', 'factDraft', 'relationEvidence', 'relationAiFeedback', 'materialChecks', 'knowledgeMaterialChecks'] as const
const otherTaskFields = ['questionCandidate', 'questionDecision', 'followUp', 'evaluation', 'evaluationReport', 'materialAnswer',
  'expressionCandidate', 'expressionDecision', 'preparationPlanCandidate', 'preparationPlanDecision', 'relationCandidate', 'relationDecisions',
  'comparison', 'comparisonCandidate', 'cardCandidate', 'cardDecision', 'jdRequirementsCandidate', 'jdRequirementsDecision', 'factCandidate', 'factDecision', 'rewrite', 'rewriteCandidate'] as const
const statuses = ['QUEUED', 'RUNNING', 'VALIDATING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCEL_REQUESTED', 'CANCELED']
const feeStatuses = ['RESERVED', 'REPORTED', 'UNKNOWN', 'RELEASED', 'RECONCILED']
const fields = (value: unknown, required: readonly string[], optional: readonly string[] = []): boolean => Boolean(value && typeof value === 'object'
  && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
// 复盘原文、选区及旧知识候选都沿后端 Java isBlank；不能用 JS trim 拒绝历史合法文本。
const text = (value: unknown, maximum: number, required = true): value is string => typeof value === 'string' && value.length <= maximum
  && !/[\u0000\uD800-\uDFFF]/u.test(value)
  && (!required || /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value))
const codePointText = (value: unknown, maximum: number, required = true): value is string => text(value, maximum * 2, required) && Array.from(value).length <= maximum
const timestamp = (value: unknown): value is string => typeof value === 'string' && value.endsWith('Z') && Number.isFinite(Date.parse(value))
const answerValid = (value: AiReviewDraftFeedbackInput['answer']): boolean => fields(value, ['sessionId', 'questionId', 'answerId']) && Object.values(value).every(uuid)
const answerValues = (value: AiReviewDraftFeedbackInput['answer']) => [value.sessionId, value.questionId, value.answerId]
const decisionValid = (revision: number, choice: AiReviewDraftFeedbackInput['decisionChoice']): boolean => integer(revision, 0, 20)
  && (revision === 0 ? choice === null : typeof choice === 'string' && ['ACCEPTED', 'DISPUTED', 'IGNORED'].includes(choice))
const fragmentValues = (items: AiReviewDraftFragment[]) => items.map(item => [item.field, item.start, item.end, item.quote])

export function aiReviewDraftRangeMatches(original: string, start: number, end: number, quote: string): boolean {
  if (!text(original, Number.MAX_SAFE_INTEGER, false) || !text(quote, Number.MAX_SAFE_INTEGER)
    || !integer(start) || !integer(end) || end <= start || end > original.length) return false
  const split = (position: number) => position > 0 && position < original.length
    && /[\uD800-\uDBFF]/.test(original.charAt(position - 1)) && /[\uDC00-\uDFFF]/.test(original.charAt(position))
  return !split(start) && !split(end) && original.slice(start, end) === quote
}

function fragmentsValid(items: AiReviewDraftFragment[], allowed: AiReviewDraftField[]): boolean {
  return Array.isArray(items) && items.length >= 1 && items.length <= 8 && items.every((item, index) =>
    fields(item, ['field', 'start', 'end', 'quote']) && allowed.includes(item.field)
    && integer(item.start, 0, 2147483647) && integer(item.end, 1, 2147483647) && item.end > item.start
    && text(item.quote, 8000) && item.end - item.start === item.quote.length
    && !items.slice(0, index).some(previous => previous.field === item.field && previous.start < item.end && item.start < previous.end))
}

export function aiReviewDraftSourceSelectionValid(source: AiReviewDraftSourceSelection): boolean {
  try {
    if (!fields(source, ['kind', 'feedback', 'recollection']) || !Object.hasOwn(aiReviewDraftKindLabels, source.kind)) return false
    if (source.kind === 'RECOLLECTION') {
      const selected = source.recollection
      return source.feedback === null && Boolean(selected && fields(selected, ['recollectionId', 'versionId', 'expectedRevision', 'fragments'])
        && uuid(selected.recollectionId) && uuid(selected.versionId) && integer(selected.expectedRevision, 0, 1000)
        && fragmentsValid(selected.fragments, recollectionFields))
    }
    const selected = source.feedback
    return source.recollection === null && Boolean(selected && fields(selected, ['feedbackId', 'feedbackRevision', 'fragments'])
      && uuid(selected.feedbackId) && integer(selected.feedbackRevision, 1, 100) && fragmentsValid(selected.fragments, feedbackFields))
  } catch { return false }
}

export function aiReviewDraftInputValid(input: AiReviewDraftInput): boolean {
  try {
    if (!fields(input, ['kind', 'feedback', 'recollection']) || !Object.hasOwn(aiReviewDraftKindLabels, input.kind)) return false
    if (input.kind === 'RECOLLECTION') {
      const value = input.recollection
      return input.feedback === null && Boolean(value && fields(value, ['recollectionId', 'versionId', 'versionNumber', 'sourceRevision', 'title', 'type', 'baselineKind', 'uncertainty', 'fragments'])
        && uuid(value.recollectionId) && uuid(value.versionId) && integer(value.versionNumber, 1, 1000) && integer(value.sourceRevision, 0, 1000)
        && value.versionNumber <= value.sourceRevision + 1 && codePointText(value.title, 200) && Object.hasOwn(questionTypeLabels, value.type)
        && ['RECOLLECTION', 'NONE'].includes(value.baselineKind) && codePointText(value.uncertainty, 2000)
        && fragmentsValid(value.fragments, recollectionFields) && value.fragments.every(item =>
          (value.baselineKind !== 'NONE' || item.field !== 'ANSWER_FRAGMENT')
          && (item.field !== 'UNCERTAINTY' || aiReviewDraftRangeMatches(value.uncertainty, item.start, item.end, item.quote))))
    }
    const value = input.feedback
    return input.recollection === null && Boolean(value && fields(value, ['feedbackId', 'feedbackRevision', 'answer', 'answerNumber', 'answerCondition',
      'dimension', 'coverage', 'decisionRevision', 'decisionChoice', 'condition', 'basis', 'limitation', 'fragments'])
      && uuid(value.feedbackId) && integer(value.feedbackRevision, 1, 100) && answerValid(value.answer) && integer(value.answerNumber, 1, 100)
      && ['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(value.answerCondition) && Object.hasOwn(questionDimensionLabels, value.dimension)
      && ['MENTIONED', 'NOT_MENTIONED', 'UNCERTAIN'].includes(value.coverage) && decisionValid(value.decisionRevision, value.decisionChoice)
      && codePointText(value.condition, 1000, false) && codePointText(value.basis, 1000) && codePointText(value.limitation, 1000)
      && fragmentsValid(value.fragments, feedbackFields) && value.fragments.every(item => {
        const original = item.field === 'CONDITION' ? value.condition : item.field === 'BASIS' ? value.basis : item.field === 'LIMITATION' ? value.limitation : undefined
        return original === undefined || aiReviewDraftRangeMatches(original, item.start, item.end, item.quote)
      }))
  } catch { return false }
}

export function aiReviewDraftSourceMatches(input: AiReviewDraftInput, source: AiReviewDraftSourceSelection): boolean {
  if (!aiReviewDraftInputValid(input) || !aiReviewDraftSourceSelectionValid(source) || input.kind !== source.kind) return false
  const fixed = input.feedback, selected = source.feedback
  if (fixed && selected) return fixed.feedbackId === selected.feedbackId && fixed.feedbackRevision === selected.feedbackRevision
    && JSON.stringify(fragmentValues(fixed.fragments)) === JSON.stringify(fragmentValues(selected.fragments))
  const recall = input.recollection, selectedRecall = source.recollection
  return Boolean(recall && selectedRecall && recall.recollectionId === selectedRecall.recollectionId && recall.versionId === selectedRecall.versionId
    && recall.sourceRevision === selectedRecall.expectedRevision && JSON.stringify(fragmentValues(recall.fragments)) === JSON.stringify(fragmentValues(selectedRecall.fragments)))
}

export function aiReviewDraftInputFingerprint(input: AiReviewDraftInput): string {
  // 固定限制、最新决定和选区顺序一起参与比较，防止仅凭对象身份复用旧的外发同意。
  const feedback = input.feedback, recollection = input.recollection
  return JSON.stringify([input.kind, feedback ? [feedback.feedbackId, feedback.feedbackRevision, answerValues(feedback.answer), feedback.answerNumber,
    feedback.answerCondition, feedback.dimension, feedback.coverage, feedback.decisionRevision, feedback.decisionChoice,
    feedback.condition, feedback.basis, feedback.limitation, fragmentValues(feedback.fragments)] : null,
  recollection ? [recollection.recollectionId, recollection.versionId, recollection.versionNumber, recollection.sourceRevision, recollection.title,
    recollection.type, recollection.baselineKind, recollection.uncertainty, fragmentValues(recollection.fragments)] : null])
}

export function aiReviewDraftManifestMatches(manifest: AiManifest): boolean {
  try {
    return fields(manifest, ['purpose', 'instruction', 'sources', 'configuration', 'inputTokenAllowance', 'reservedMicros', 'promptVersion', 'reviewDraft'], specializedManifestFields)
      && manifest.purpose === 'KNOWLEDGE_DRAFT' && manifest.promptVersion === 'review-knowledge-draft-1' && text(manifest.instruction, 1000, false)
      && Array.isArray(manifest.sources) && manifest.sources.length === 0 && specializedManifestFields.every(key => manifest[key] == null)
      && Boolean(manifest.configuration && typeof manifest.configuration === 'object' && !Array.isArray(manifest.configuration))
      && integer(manifest.inputTokenAllowance, 1) && integer(manifest.reservedMicros) && Boolean(manifest.reviewDraft && aiReviewDraftInputValid(manifest.reviewDraft))
  } catch { return false }
}

export function aiReviewDraftCitationValid(input: AiReviewDraftInput, citation: AiReviewDraftCitation): boolean {
  try {
    if (!aiReviewDraftInputValid(input) || !fields(citation, ['field', 'start', 'end', 'quote']) || !Object.hasOwn(aiReviewDraftFieldLabels, citation.field)
      || !integer(citation.start, 0, 2147483647) || !integer(citation.end, 1, 2147483647) || citation.end <= citation.start || !text(citation.quote, 1000)) return false
    const selected = input.feedback?.fragments ?? input.recollection!.fragments
    // 自动伴随外发的完整限制只提供语境；引用权限始终局限于用户明确选中的范围。
    return selected.some(fragment => fragment.field === citation.field && citation.start >= fragment.start && citation.end <= fragment.end
      && aiReviewDraftRangeMatches(fragment.quote, citation.start - fragment.start, citation.end - fragment.start, citation.quote))
  } catch { return false }
}

export function aiReviewDraftCandidateValid(candidate: AiTask['candidate'], input: AiReviewDraftInput): boolean {
  try {
    if (!aiReviewDraftInputValid(input) || !candidate || !fields(candidate, ['title', 'sections', 'validationNote'])
      || !codePointText(candidate.title, 200) || !text(candidate.validationNote, Number.MAX_SAFE_INTEGER)
      || !Array.isArray(candidate.sections) || candidate.sections.length < 1 || candidate.sections.length > 12) return false
    let total = 0
    return candidate.sections.every(section => {
      if (!fields(section, ['heading', 'body', 'citations', 'reviewCitations'], ['materialCitations', 'trainingCitations'])
        || !codePointText(section.heading, 200) || !codePointText(section.body, 3000) || !Array.isArray(section.citations) || section.citations.length !== 0
        || section.materialCitations != null || section.trainingCitations != null
        || !Array.isArray(section.reviewCitations) || section.reviewCitations.length < 1 || section.reviewCitations.length > 10) return false
      total += Array.from(section.body).length
      const positions = new Set<string>()
      return total <= 24000 && section.reviewCitations.every(citation => {
        if (!aiReviewDraftCitationValid(input, citation)) return false
        const key = JSON.stringify([citation.field, citation.start, citation.end])
        if (positions.has(key)) return false
        positions.add(key)
        return true
      })
    })
  } catch { return false }
}

export function aiReviewDraftTaskValid(task: AiTask): boolean {
  try {
    if (!task || !task.manifest || !aiCardDraftTaskValid(task)) return false
    // 同一复盘输入可服务于关系候选；仍按完整用途校验，不能落入知识整理的宽松旁路。
    if (task.task.purpose === 'RELATION_DRAFT' || task.manifest.purpose === 'RELATION_DRAFT'
      || task.manifest.promptVersion === 'relation-review-draft-1') return aiRelationTaskMatches(task)
    if (task.manifest.reviewDraft == null && task.manifest.promptVersion !== 'review-knowledge-draft-1') {
      // 旧回执缺少新增尾字段时按 null 处理，仍由原用途校验器检查其余内容。
      return task.candidate == null || Array.isArray(task.candidate.sections) && task.candidate.sections.every(section => section && section.reviewCitations == null)
    }
    if (!fields(task, ['task', 'manifest', 'attempts', 'candidate', 'sourcesCurrent'], ['decision', 'evaluationDecisions', ...otherTaskFields])
      || !task.task || !uuid(task.task.id) || task.task.purpose !== 'KNOWLEDGE_DRAFT' || !integer(task.task.revision)
      || !statuses.includes(task.task.status) || !integer(task.task.attemptCount, 1, 3) || typeof task.sourcesCurrent !== 'boolean'
      || !aiReviewDraftManifestMatches(task.manifest) || otherTaskFields.some(key => task[key] != null)
      || task.evaluationDecisions != null && (!Array.isArray(task.evaluationDecisions) || task.evaluationDecisions.length !== 0)
      || !Array.isArray(task.attempts) || task.attempts.length !== task.task.attemptCount
      || task.attempts.some(attempt => !attempt || !uuid(attempt.id) || !integer(attempt.number, 1, task.task.attemptCount)
        || !statuses.includes(attempt.status) || !feeStatuses.includes(attempt.feeStatus) || !integer(attempt.reservedMicros)
        || attempt.accountedMicros !== null && !integer(attempt.accountedMicros))
      || new Set(task.attempts.map(attempt => attempt.id)).size !== task.attempts.length
      || new Set(task.attempts.map(attempt => attempt.number)).size !== task.attempts.length) return false
    const decision = task.decision
    if (decision != null && (!fields(decision, ['status', 'mode', 'targetItemId', 'targetVersionId', 'createdAt']) || !timestamp(decision.createdAt)
      || (decision.status === 'IGNORED' ? decision.mode !== null || decision.targetItemId !== null || decision.targetVersionId !== null
        : decision.status !== 'ADOPTED' || !['NEW', 'EXISTING'].includes(decision.mode ?? '') || !uuid(decision.targetItemId) || !uuid(decision.targetVersionId)))) return false
    return task.task.status === 'SUCCEEDED' ? aiReviewDraftCandidateValid(task.candidate, task.manifest.reviewDraft!) : task.candidate === null && decision == null
  } catch { return false }
}

export function aiReviewDraftOriginValid(origin: AiReviewDraftOrigin): boolean {
  try {
    if (!fields(origin, ['kind', 'feedback', 'recollection', 'state']) || !Object.hasOwn(aiReviewDraftKindLabels, origin.kind)
      || !['CURRENT', 'SUPERSEDED', 'ARCHIVED', 'UNAVAILABLE', 'REVIEW_REQUIRED'].includes(origin.state)) return false
    if (origin.kind === 'RECOLLECTION') {
      const value = origin.recollection
      return origin.feedback === null && Boolean(value && fields(value, ['recollectionId', 'versionId', 'versionNumber', 'sourceRevision', 'title', 'baselineKind'])
        && uuid(value.recollectionId) && uuid(value.versionId) && integer(value.versionNumber, 1, 1000) && integer(value.sourceRevision, 0, 1000)
        && value.versionNumber <= value.sourceRevision + 1 && codePointText(value.title, 200) && ['RECOLLECTION', 'NONE'].includes(value.baselineKind))
    }
    const value = origin.feedback
    return origin.recollection === null && ['CURRENT', 'SUPERSEDED', 'UNAVAILABLE', 'REVIEW_REQUIRED'].includes(origin.state)
      && Boolean(value && fields(value, ['feedbackId', 'feedbackRevision', 'answer', 'answerNumber', 'decisionRevision', 'decisionChoice'])
      && uuid(value.feedbackId) && integer(value.feedbackRevision, 1, 100) && answerValid(value.answer) && integer(value.answerNumber, 1, 100)
      && decisionValid(value.decisionRevision, value.decisionChoice))
  } catch { return false }
}

export function aiReviewDraftOriginMatches(origin: AiReviewDraftOrigin, input: AiReviewDraftInput): boolean {
  if (!aiReviewDraftOriginValid(origin) || !aiReviewDraftInputValid(input) || origin.kind !== input.kind) return false
  const source = origin.feedback, feedback = input.feedback
  if (source && feedback) return source.feedbackId === feedback.feedbackId && source.feedbackRevision === feedback.feedbackRevision
    && JSON.stringify(answerValues(source.answer)) === JSON.stringify(answerValues(feedback.answer)) && source.answerNumber === feedback.answerNumber
    && source.decisionRevision === feedback.decisionRevision && source.decisionChoice === feedback.decisionChoice
  const recall = origin.recollection, fixed = input.recollection
  return Boolean(recall && fixed && recall.recollectionId === fixed.recollectionId && recall.versionId === fixed.versionId
    && recall.versionNumber === fixed.versionNumber && recall.sourceRevision === fixed.sourceRevision && recall.title === fixed.title && recall.baselineKind === fixed.baselineKind)
}

function sourceRoute(source: AiReviewDraftInput | AiReviewDraftOrigin): RouteLocationRaw {
  if (source.recollection) return { name: 'recollections', query: { item: source.recollection.recollectionId, versionId: source.recollection.versionId } }
  const feedback = source.feedback!
  return { name: 'review', query: { ...feedback.answer, feedbackId: feedback.feedbackId, revision: String(feedback.feedbackRevision) } }
}
export function aiReviewDraftInputRoute(input: AiReviewDraftInput): RouteLocationRaw | undefined {
  return aiReviewDraftInputValid(input) ? sourceRoute(input) : undefined
}
export function aiReviewDraftOriginRoute(origin: AiReviewDraftOrigin): RouteLocationRaw | undefined {
  return aiReviewDraftOriginValid(origin) && origin.state !== 'UNAVAILABLE' ? sourceRoute(origin) : undefined
}
