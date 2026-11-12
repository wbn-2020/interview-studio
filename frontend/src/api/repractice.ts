import type { Schema } from './client'
import type { AiEvaluationInput } from './ai'
import type { ReviewActionSource, ReviewAnswerLink } from './review'
import { reviewAnswerMatches, reviewCitationValid, reviewEvidenceFields, reviewIdValid } from './review'
import { questionTextLength } from './bank'
import type { TrainingDiagramInput } from './training'
import { recollectionSourceLabels, recollectionVersionValid } from './recollection'

export type RepracticeQuestionMode = Schema['RepracticeQuestionMode']
export type RepracticeOutcome = Schema['RepracticeOutcome']
export type RepracticePick = Schema['RepracticePick']
export type RepracticeCreate = Schema['RepracticeCreate']
export type RepracticeCreateRecollection = Schema['RepracticeCreateRecollection']
export type RepracticeBaselineKind = Schema['RepracticeBaselineKind']
export type RepracticeRecollectionBaseline = Schema['RepracticeRecollectionBaseline']
export type RepracticeCompare = Schema['RepracticeCompare']
export type RepracticeJudge = Schema['RepracticeJudge']
export type RepracticeSourceSnapshot = Schema['RepracticeSourceSnapshot']
export type RepracticeSummary = Schema['RepracticeSummary']
export type RepracticePage = Schema['RepracticePage']
export type Repractice = Schema['Repractice']
export type RepracticeComparisonSummary = Schema['RepracticeComparisonSummary']
export type RepracticeSide = Schema['RepracticeSide']
export type RepracticeDifference = Schema['RepracticeDifference']
export type RepracticeComparison = Schema['RepracticeComparison']
export type RepracticeJudgment = Schema['RepracticeJudgment']
export type RepracticeJudgmentPage = Schema['RepracticeJudgmentPage']
export type RepracticeSourceProgress = Schema['RepracticeSourceProgress']
export type RepracticeProgressPage = Schema['RepracticeProgressPage']
export type RepracticeCitation = RepracticeJudge['citations'][number]

export const repracticeModeLabels: Record<RepracticeQuestionMode, string> = { SAME: '相同题面', VARIANT: '选择变式' }
export const repracticeOutcomeLabels: Record<RepracticeOutcome, string> = { HANDLED: '已处理', PARTIAL: '部分处理', UNHANDLED: '未处理', UNCERTAIN: '无法判断' }
export const repracticeConditionLabels = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
export const repracticeBaselineLabels: Record<RepracticeBaselineKind, string> = { ANSWER: '正式原答', RECOLLECTION: '个人回忆片段', NONE: '无旧答' }
export const repracticeProgressLabel = (progress: RepracticeSourceProgress) => progress.tracking === 'DROPPED' ? '已停止跟踪'
  : progress.recollectionState && progress.recollectionState !== 'CURRENT' ? `${recollectionSourceLabels[progress.recollectionState]}，${progress.latest ? '原判断不再适用' : '暂不能新增判断'}`
  : !progress.latest ? '待复练判断' : !progress.applicable ? '验证条件已变化，待重新验证' : repracticeOutcomeLabels[progress.latest.outcome]
export const repracticeProgressKey = (progress?: RepracticeSourceProgress) => progress ? JSON.stringify([progress.sourceId, progress.actionId, progress.actionRevision,
  progress.verification, progress.tracking, progress.latest?.revision ?? 0, progress.recollectionState ?? null]) : ''

export function repracticeRecollectionBaselineValid(value: RepracticeRecollectionBaseline | null | undefined): value is RepracticeRecollectionBaseline {
  try { return Boolean(value && reviewIdValid(value.recollectionId) && Number.isSafeInteger(value.revision) && value.revision >= 0 && value.revision <= 1000
    && recollectionVersionValid(value.version) && value.version.number <= value.revision + 1 && Object.hasOwn(recollectionSourceLabels, value.state)) } catch { return false }
}
export function repracticeBaselineKind(value: Repractice): RepracticeBaselineKind | undefined {
  if (!value) return undefined
  // 旧正式回执可缺尾字段或返回 null；空答案不能据此推断为“无旧答”。
  if (value.baselineKind == null || value.baselineKind === 'ANSWER') return validAnswer(value.baseline) && value.recollectionBaseline == null ? 'ANSWER' : undefined
  if ((value.baselineKind === 'RECOLLECTION' || value.baselineKind === 'NONE') && value.baseline === null
    && repracticeRecollectionBaselineValid(value.recollectionBaseline) && value.recollectionBaseline.version.content.baselineKind === value.baselineKind) return value.baselineKind
  return undefined
}
export function repracticeSummaryBaselineKind(value: RepracticeSummary): RepracticeBaselineKind | undefined {
  if (!value) return undefined
  if (value.baselineKind == null || value.baselineKind === 'ANSWER') return validAnswer(value.baseline)
    && value.recollectionId == null && value.recollectionVersionId == null ? 'ANSWER' : undefined
  return (value.baselineKind === 'RECOLLECTION' || value.baselineKind === 'NONE') && value.baseline === null
    && reviewIdValid(value.recollectionId) && reviewIdValid(value.recollectionVersionId) ? value.baselineKind : undefined
}
export function repracticeSummaryValid(value: RepracticeSummary): boolean {
  return Boolean(value && repracticeSummaryBaselineKind(value) && reviewIdValid(value.id) && reviewIdValid(value.sessionId) && reviewIdValid(value.questionId)
    && repracticeTextValid(value.title, 200) && Object.hasOwn(repracticeModeLabels, value.questionMode) && ['DRAFT', 'RUNNING', 'PAUSED', 'ENDED'].includes(value.status)
    && Number.isInteger(value.sourceCount) && value.sourceCount >= 0 && value.sourceCount <= 10 && typeof value.createdAt === 'string')
}

export function repracticeInput(side: RepracticeSide): AiEvaluationInput {
  const question = side.question.content, answer = side.submission.content
  return { ...side.answer, answerNumber: side.submission.number, mode: side.mode, feedbackTiming: side.config.feedbackTiming,
    answerCondition: side.submission.condition,
    question: { title: question.title, prompt: question.prompt, conditions: question.conditions, technology: question.technology, scenario: question.scenario, type: question.type },
    answer: { text: answer.text, nodes: answer.diagram.nodes.map(node => ({ id: node.id, label: node.label, description: node.description })),
      edges: answer.diagram.edges.map(edge => ({ id: edge.id, source: edge.source, target: edge.target, label: edge.label })) }, standard: side.standard }
}
export function repracticeDiagram(side: RepracticeSide): TrainingDiagramInput {
  const diagram = side.submission.content.diagram
  return { ...diagram, nodes: diagram.nodes.map(node => ({ id: node.id, label: node.label, description: node.description, x: node.x, y: node.y,
    link: node.reference ? { kind: node.reference.kind, itemId: node.reference.itemId, versionId: node.reference.versionId } : null })) }
}
export function repracticeFields(side: RepracticeSide) { return reviewEvidenceFields(repracticeInput(side)).filter(field => field.kind.startsWith('ANSWER_')) }
export function repracticeCitationValid(side: RepracticeSide, citation: RepracticeCitation) { return citationShapeValid(citation) && reviewCitationValid(repracticeInput(side), citation) }
export function repracticeTextValid(value: unknown, maximum: number, required = true): value is string {
  return typeof value === 'string' && (!required || /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)) && questionTextLength(value) <= maximum && !/[\u0000\uD800-\uDFFF]/u.test(value)
}
export function repracticeJudgeProblem(outcome: RepracticeOutcome, reason: string, citations: RepracticeCitation[], side: RepracticeSide): string {
  if (!Object.hasOwn(repracticeOutcomeLabels, outcome)) return '请选择判断。'
  if (!repracticeTextValid(reason, 2000)) return '请填写 1–2000 个完整字符的判断依据。'
  if (citations.length > 6 || citations.some(citation => !repracticeCitationValid(side, citation))) return '请从本次新答案选择最多 6 处有效原文。'
  if (new Set(citations.map(citation => `${citation.kind}:${citation.elementId}:${citation.start}:${citation.end}`)).size !== citations.length) return '同一处引文不能重复加入。'
  if (['HANDLED', 'PARTIAL'].includes(outcome) && !citations.length) return '已处理或部分处理需要至少一处本次新答案引文。'
  return ''
}
export function repracticeValid(value: Repractice): boolean {
  try { return Boolean(value && reviewIdValid(value.id) && repracticeBaselineKind(value) && reviewIdValid(value.sessionId) && reviewIdValid(value.questionId)
    && value.session.id === value.sessionId && ['DRAFT', 'RUNNING', 'PAUSED', 'ENDED'].includes(value.session.status)
    && Object.hasOwn(repracticeModeLabels, value.questionMode) && Array.isArray(value.sources) && value.sources.length <= 10
    && value.sources.every(source => reviewIdValid(source.sourceId) && reviewIdValid(source.actionId) && source.sourceId === source.source.id
      && sourceMatchesBaseline(source.source, value) && source.source.tracking === 'TRACKING' && Number.isSafeInteger(source.actionRevision)
      && source.actionRevision >= 0 && source.actionRevision <= 1000 && repracticeTextValid(source.source.verification, 2000))
    && new Set(value.sources.map(source => source.sourceId)).size === value.sources.length && Array.isArray(value.comparisons)
    && value.comparisons.length <= 20 && value.comparisons.every(item => comparisonSummaryValid(item) && item.repracticeId === value.id)
    && new Set(value.comparisons.map(item => item.id)).size === value.comparisons.length) } catch { return false }
}
export function comparisonSummaryValid(value: RepracticeComparisonSummary): boolean {
  return Boolean(value && reviewIdValid(value.id) && reviewIdValid(value.repracticeId) && reviewIdValid(value.answerId)
    && Number.isInteger(value.answerNumber) && value.answerNumber > 0 && typeof value.createdAt === 'string')
}
export function repracticeJudgmentValid(value: RepracticeJudgment, sourceId: string): boolean {
  return Boolean(value && reviewIdValid(value.id) && value.sourceId === sourceId && Number.isInteger(value.revision) && value.revision >= 1 && value.revision <= 1000
    && reviewIdValid(value.comparisonId) && reviewIdValid(value.answerId) && Object.hasOwn(repracticeOutcomeLabels, value.outcome)
    && repracticeTextValid(value.verification, 2000) && repracticeTextValid(value.reason, 2000) && Array.isArray(value.citations)
    && value.citations.length <= 6 && value.citations.every(citationShapeValid) && typeof value.createdAt === 'string'
    && (!['HANDLED', 'PARTIAL'].includes(value.outcome) || value.citations.length > 0)
    && new Set(value.citations.map(citation => `${citation.kind}:${citation.elementId}:${citation.start}:${citation.end}`)).size === value.citations.length)
}
export function repracticeProgressValid(value: RepracticeSourceProgress, source?: ReviewActionSource): boolean {
  return Boolean(value && reviewIdValid(value.sourceId) && reviewIdValid(value.actionId) && Number.isSafeInteger(value.actionRevision) && value.actionRevision >= 0
    && value.actionRevision <= 1000 && repracticeTextValid(value.verification, 2000) && ['TRACKING', 'DROPPED'].includes(value.tracking)
    && (value.recollectionState == null || Object.hasOwn(recollectionSourceLabels, value.recollectionState))
    && (!source || (value.sourceId === source.id && (source.kind === 'RECOLLECTION' ? value.recollectionState != null : value.recollectionState == null)))
    && (value.latest === null || repracticeJudgmentValid(value.latest, value.sourceId)) && typeof value.applicable === 'boolean'
    && value.applicable === Boolean(value.tracking === 'TRACKING' && value.latest && value.latest.verification === value.verification
      && (value.recollectionState == null || value.recollectionState === 'CURRENT')))
}
export function repracticeComparisonValid(value: RepracticeComparison, id: string): boolean {
  try { return Boolean(value && value.comparison.id === id && comparisonSummaryValid(value.comparison) && repracticeValid(value.repractice)
    && value.comparison.repracticeId === value.repractice.id && value.repractice.session.status === 'ENDED'
    && (repracticeBaselineKind(value.repractice) === 'ANSWER' ? value.baseline && reviewAnswerMatches(value.baseline.answer, value.repractice.baseline) && validSide(value.baseline)
      : value.baseline === null) && validSide(value.current)
    && value.current.answer.sessionId === value.repractice.sessionId && value.current.answer.questionId === value.repractice.questionId
    && value.current.answer.answerId === value.comparison.answerId && value.current.submission.number === value.comparison.answerNumber
    && Array.isArray(value.differences) && (repracticeBaselineKind(value.repractice) === 'ANSWER' ? value.differences.length === differenceKinds.length
      && value.differences.every((item, index) => item.kind === differenceKinds[index] && typeof item.changed === 'boolean' && typeof item.explanation === 'string') : value.differences.length === 0)
    && Array.isArray(value.progress) && value.progress.length === value.repractice.sources.length
    && new Set(value.progress.map(item => item.sourceId)).size === value.progress.length
    && value.progress.every(item => value.repractice.sources.some(source => source.sourceId === item.sourceId && repracticeProgressValid(item, source.source)))) } catch { return false }
}
const differenceKinds = ['QUESTION', 'REFERENCE', 'CONTEXT', 'MODE', 'ASSISTANCE', 'EXPOSURE', 'TIME_BUDGET', 'SESSION_TIME']
function validAnswer(value: ReviewAnswerLink | null | undefined): value is ReviewAnswerLink { return Boolean(value && reviewIdValid(value.sessionId) && reviewIdValid(value.questionId) && reviewIdValid(value.answerId)) }
function sourceMatchesBaseline(source: ReviewActionSource, value: Repractice): boolean {
  if (repracticeBaselineKind(value) === 'ANSWER') return ['MANUAL', 'AI_EVALUATION'].includes(source.kind) && source.recollection == null
    && validAnswer(source.answer) && reviewAnswerMatches(source.answer, value.baseline)
  const baseline = value.recollectionBaseline, recollection = source.recollection
  return Boolean(baseline && recollection && source.kind === 'RECOLLECTION' && source.answer === null && source.feedbackId === null && source.feedbackRevision === null
    && source.taskId === null && source.observationId === null && source.manualKind === null && source.authorLabel === null && source.observation === null && source.decision === null
    && recollection.recollectionId === baseline.recollectionId && recollection.versionId === baseline.version.id && recollection.versionNumber === baseline.version.number
    && recollection.baselineKind === value.baselineKind && repracticeTextValid(recollection.observation, 5000))
}
function citationShapeValid(value: RepracticeCitation) {
  return Boolean(value && ['ANSWER_TEXT', 'ANSWER_NODE_LABEL', 'ANSWER_NODE_DESCRIPTION', 'ANSWER_EDGE_LABEL'].includes(value.kind)
    && (value.kind === 'ANSWER_TEXT' ? value.elementId === null : reviewIdValid(value.elementId))
    && Number.isSafeInteger(value.start) && Number.isSafeInteger(value.end) && value.start >= 0 && value.end > value.start && repracticeTextValid(value.quote, 500))
}
function validSide(value: RepracticeSide) {
  return validAnswer(value.answer) && value.submission.id === value.answer.answerId && value.submission.questionId === value.answer.questionId
    && typeof value.question.content.prompt === 'string' && typeof value.submission.content.text === 'string' && Array.isArray(value.submission.content.diagram.nodes)
    && Array.isArray(value.submission.content.diagram.edges) && Array.isArray(value.context) && Boolean(value.config && value.timing)
}
