import type { Schema } from './client'
import type { AiEvaluationCitation, AiEvaluationInput } from './ai'
import { aiTrainingDraftRangeMatches } from './ai'
import { questionDimensionLabels, questionTextLength } from './bank'

export type ReviewAnswerLink = Schema['ReviewAnswerLink']
export type ReviewFeedbackContent = Schema['ReviewFeedbackContent']
export type ReviewCreateFeedback = Schema['ReviewCreateFeedback']
export type ReviewSaveFeedback = Schema['ReviewSaveFeedback']
export type ReviewDecideFeedback = Schema['ReviewDecideFeedback']
export type ReviewDecision = Schema['ReviewDecision']
export type ReviewFeedback = Schema['ReviewFeedback']
export type ReviewFeedbackSummary = Schema['ReviewFeedbackSummary']
export type ReviewFeedbackPage = Schema['ReviewFeedbackPage']
export type ReviewFeedbackHistoryPage = Schema['ReviewFeedbackHistoryPage']
export type ReviewSourceRequest = Schema['ReviewSourceRequest']
export type ReviewCreateAction = Schema['ReviewCreateAction']
export type ReviewCreateRecollectionAction = Schema['ReviewCreateRecollectionAction']
export type ReviewRecollectionSource = Schema['ReviewRecollectionSource']
export type ReviewSaveAction = Schema['ReviewSaveAction']
export type ReviewSaveSource = Schema['ReviewSaveSource']
export type ReviewMergeAction = Schema['ReviewMergeAction']
export type ReviewActionSource = Schema['ReviewActionSource']
export type ReviewAction = Schema['ReviewAction']
export type ReviewActionSummary = Schema['ReviewActionSummary']
export type ReviewActionPage = Schema['ReviewActionPage']
export type ReviewActionRevision = Schema['ReviewActionRevision']
export type ReviewActionHistoryPage = Schema['ReviewActionHistoryPage']

export type ReviewActionCandidate = {
  answer: ReviewAnswerLink
  source: Omit<ReviewSourceRequest, 'verification'>
  observation: Schema['AiEvaluationObservation']
  label: string
  decision: ReviewDecision | null
}

export const reviewKindLabels: Record<ReviewFeedbackContent['kind'], string> = { SELF_CHECK: '自检', EXTERNAL_FEEDBACK: '手工录入外部意见' }
export const reviewCoverageLabels: Record<ReviewFeedbackContent['coverage'], string> = { MENTIONED: '原文有提及', NOT_MENTIONED: '可能未提及', UNCERTAIN: '暂无法判断' }
export const reviewChoiceLabels: Record<ReviewDecision['choice'], string> = { ACCEPTED: '接受', DISPUTED: '提出争议', IGNORED: '忽略' }
export const reviewEvidenceLabels: Record<AiEvaluationCitation['kind'], string> = {
  ANSWER_TEXT: '回答原文', ANSWER_NODE_LABEL: '答案图节点名称', ANSWER_NODE_DESCRIPTION: '答案图节点说明', ANSWER_EDGE_LABEL: '答案图连线说明',
  QUESTION_PROMPT: '固定题干', QUESTION_CONDITIONS: '题目条件', REFERENCE: '个人未审参考', CHECKPOINT: '个人未审检查点',
}
export const reviewIdValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
export const reviewAnswerMatches = (left: ReviewAnswerLink | undefined | null, right: ReviewAnswerLink | undefined | null) => Boolean(left && right && left.sessionId === right.sessionId && left.questionId === right.questionId && left.answerId === right.answerId)
export const reviewAnswerKey = (answer: ReviewAnswerLink) => `${answer.sessionId}/${answer.questionId}/${answer.answerId}`
export const reviewAnswerQuery = (answer: ReviewAnswerLink) => new URLSearchParams({ sessionId: answer.sessionId, questionId: answer.questionId, answerId: answer.answerId })
export const reviewDecisionRevision = (feedback: ReviewFeedback) => Math.max(0, ...feedback.decisions.map(item => item.revision))
export const cloneReviewContent = (content: ReviewFeedbackContent): ReviewFeedbackContent => JSON.parse(JSON.stringify(content)) as ReviewFeedbackContent
export const reviewDate = (value: string) => new Date(value).toLocaleString('zh-CN')

export function emptyReviewFeedback(): ReviewFeedbackContent {
  return { kind: 'SELF_CHECK', authorLabel: '', dimension: 'EXPLAIN', coverage: 'UNCERTAIN', observation: '', condition: '', basis: '', limitation: '', suggestion: '', citations: [] }
}

export type ReviewEvidenceField = { key: string; kind: AiEvaluationCitation['kind']; elementId: string | null; label: string; text: string }
export function reviewEvidenceFields(input: AiEvaluationInput): ReviewEvidenceField[] {
  const fields: ReviewEvidenceField[] = []
  const add = (kind: AiEvaluationCitation['kind'], elementId: string | null, text: string, suffix = '') => {
    fields.push({ key: `${kind}:${elementId ?? ''}`, kind, elementId, label: `${reviewEvidenceLabels[kind]}${suffix}`, text })
  }
  add('ANSWER_TEXT', null, input.answer.text)
  add('QUESTION_PROMPT', null, input.question.prompt)
  add('QUESTION_CONDITIONS', null, input.question.conditions)
  input.answer.nodes.forEach((node, index) => { add('ANSWER_NODE_LABEL', node.id, node.label, ` · ${index + 1}`); add('ANSWER_NODE_DESCRIPTION', node.id, node.description, ` · ${node.label || index + 1}`) })
  input.answer.edges.forEach((edge, index) => add('ANSWER_EDGE_LABEL', edge.id, edge.label, ` · ${index + 1}`))
  if (input.standard) {
    add('REFERENCE', null, input.standard.referenceAnswer)
    input.standard.checkpoints.forEach((point, index) => add('CHECKPOINT', point.id, point.text, ` · ${index + 1}`))
  }
  return fields
}
export function reviewEvidenceText(input: AiEvaluationInput, citation: AiEvaluationCitation): string | undefined {
  return reviewEvidenceFields(input).find(field => field.kind === citation.kind && field.elementId === citation.elementId)?.text
}
export function reviewCitationValid(input: AiEvaluationInput, citation: AiEvaluationCitation): boolean {
  if (!citation || !Object.hasOwn(reviewEvidenceLabels, citation.kind) || typeof citation.quote !== 'string' || !citation.quote.trim()
    || questionTextLength(citation.quote) > 500 || /[\u0000\uD800-\uDFFF]/u.test(citation.quote)) return false
  const text = reviewEvidenceText(input, citation)
  return text !== undefined && aiTrainingDraftRangeMatches(text, citation.start, citation.end, citation.quote)
}
export function reviewFeedbackProblem(content: ReviewFeedbackContent, input?: AiEvaluationInput): string {
  const limits: [keyof ReviewFeedbackContent, string, number, boolean][] = [
    ['observation', '观察', 2000, true], ['condition', '适用条件', 1000, false], ['basis', '观察依据', 1000, true],
    ['limitation', '判断限制', 1000, true], ['suggestion', '建议行动', 1000, true], ['authorLabel', '外部意见提供者称呼', 120, content.kind === 'EXTERNAL_FEEDBACK'],
  ]
  if (!content || !Object.hasOwn(reviewKindLabels, content.kind) || !Object.hasOwn(reviewCoverageLabels, content.coverage) || !Object.hasOwn(questionDimensionLabels, content.dimension)) return '请选择有效的反馈类型、观察范围与维度。'
  for (const [key, label, limit, required] of limits) {
    const value = content[key]
    if (typeof value !== 'string' || (required && !value.trim()) || questionTextLength(value) > limit || /[\u0000\uD800-\uDFFF]/u.test(value)) return `${label}${required ? '必填，' : ''}最多 ${limit} 个字符，须为完整文字。`
  }
  if (content.kind === 'SELF_CHECK' && content.authorLabel !== '') return '自检不填写外部意见提供者。'
  if (!Array.isArray(content.citations) || content.citations.length > 6) return '每项反馈最多选择 6 处原文。'
  if (content.citations.some(citation => !citation || !Object.hasOwn(reviewEvidenceLabels, citation.kind) || typeof citation.quote !== 'string' || !citation.quote.trim()
    || questionTextLength(citation.quote) > 500 || /[\u0000\uD800-\uDFFF]/u.test(citation.quote) || !Number.isSafeInteger(citation.start) || !Number.isSafeInteger(citation.end)
    || citation.start < 0 || citation.end <= citation.start || (['ANSWER_TEXT', 'QUESTION_PROMPT', 'QUESTION_CONDITIONS', 'REFERENCE'].includes(citation.kind)
      ? citation.elementId !== null : !reviewIdValid(citation.elementId)))) return '引文需来自有效的原文字段与完整字符位置。'
  if (new Set(content.citations.map(citation => `${citation.kind}:${citation.elementId}:${citation.start}:${citation.end}`)).size !== content.citations.length) return '同一处引文不能重复加入。'
  if (input && content.citations.some(citation => !reviewCitationValid(input, citation))) return '引用与确切提交原文不一致，请重新选择。'
  const answers = content.citations.filter(citation => citation.kind.startsWith('ANSWER_'))
  if (content.coverage === 'MENTIONED' && !answers.length) return '“原文有提及”需要至少一处回答原文或答案图证据。'
  if (content.coverage === 'NOT_MENTIONED' && (answers.length || !content.citations.length)) return '“可能未提及”需选择题干、条件或个人参考作为依据，并移除答案引文。'
  return ''
}
export function reviewContentSignature(content: ReviewFeedbackContent): string {
  return JSON.stringify([content.kind, content.authorLabel, content.dimension, content.coverage, content.observation, content.condition, content.basis, content.limitation, content.suggestion,
    content.citations.map(citation => [citation.kind, citation.elementId, citation.start, citation.end, citation.quote])])
}
export function reviewFeedbackValid(value: ReviewFeedback, answer: ReviewAnswerLink, input?: AiEvaluationInput): boolean {
  try { return Boolean(value && reviewIdValid(value.id) && reviewAnswerMatches(value.answer, answer) && Number.isSafeInteger(value.revision) && value.revision >= 1 && value.revision <= 100
    && value.content && !reviewFeedbackProblem(value.content, input) && Array.isArray(value.decisions) && value.decisions.length <= 20
    && value.decisions.every(decision => decision && reviewIdValid(decision.id) && Number.isSafeInteger(decision.revision) && decision.revision >= 1 && decision.revision <= 20
      && Object.hasOwn(reviewChoiceLabels, decision.choice) && typeof decision.reason === 'string' && questionTextLength(decision.reason) <= 1000
      && (decision.choice !== 'DISPUTED' || Boolean(decision.reason.trim())) && !/[\u0000\uD800-\uDFFF]/u.test(decision.reason) && typeof decision.createdAt === 'string')
    && new Set(value.decisions.map(decision => decision.revision)).size === value.decisions.length
    && new Set(value.decisions.map(decision => decision.id)).size === value.decisions.length
    && value.decisions.map(decision => decision.revision).sort((left, right) => left - right).every((revision, index) => revision === index + 1)
    && typeof value.createdAt === 'string' && typeof value.revisedAt === 'string') } catch { return false }
}
export function reviewManualCandidate(feedback: ReviewFeedback): ReviewActionCandidate {
  const decision = [...feedback.decisions].sort((left, right) => right.revision - left.revision)[0] ?? null
  return { answer: { ...feedback.answer }, source: { kind: 'MANUAL', feedbackId: feedback.id, feedbackRevision: feedback.revision, taskId: null, observationId: null, expectedDecisionRevision: decision?.revision ?? 0 },
    observation: { id: feedback.id, ...cloneReviewContent(feedback.content) }, label: `${reviewKindLabels[feedback.content.kind]} · 修订 ${feedback.revision}`, decision }
}
