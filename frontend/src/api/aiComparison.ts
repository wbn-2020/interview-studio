import type { Schema } from './client'
import type { AiManifest, AiTask } from './ai'
import { aiFeeLabels, aiPreparationPlanTextValid, aiStatusLabels, aiTrainingDraftRangeMatches } from './ai'
import { questionTypeLabels } from './bank'
import { reviewIdValid } from './review'
import type { RepracticeCitation, RepracticeOutcome } from './repractice'
import { repracticeOutcomeLabels } from './repractice'
import { trainingFeedbackLabels, trainingModeLabels } from './training'

export type AiComparisonEvidenceSide = Schema['AiComparisonEvidenceSide']
export type AiComparisonPreviewRequest = Schema['AiComparisonPreviewRequest']
export type AiComparisonCreate = Schema['AiComparisonCreate']
export type AiComparisonSide = Schema['AiComparisonSide']
export type AiComparisonSource = Schema['AiComparisonSource']
export type AiComparisonDifferences = Schema['AiComparisonDifferences']
export type AiComparisonInput = Schema['AiComparisonInput']
export type AiComparisonSourceCheck = Schema['AiComparisonSourceCheck']
export type AiComparisonChecks = Schema['AiComparisonChecks']
export type AiComparisonCitation = Schema['AiComparisonCitation']
export type AiComparisonSuggestion = Schema['AiComparisonSuggestion']
export type AiComparisonCandidate = Schema['AiComparisonCandidate']
export type AiComparisonLink = Schema['AiComparisonLink']
export type AiComparisonDraft = {
  taskId: string; comparisonId: string; answerId: string; sourceId: string
  outcome: RepracticeOutcome; reason: string; citations: RepracticeCitation[]
}

export const aiComparisonSideLabels: Record<AiComparisonEvidenceSide, string> = { BASELINE: '旧答', CURRENT: '新答' }
export const aiComparisonEvidenceLabels = {
  ANSWER_TEXT: '回答文字', ANSWER_NODE_LABEL: '图节点名称', ANSWER_NODE_DESCRIPTION: '图节点说明', ANSWER_EDGE_LABEL: '图连线说明',
} as const
export const aiComparisonDifferenceLabels: Record<keyof AiComparisonDifferences, string> = {
  questionChanged: '题面', referenceChanged: '个人参考', contextChanged: '固定背景', modeChanged: '训练模式与反馈时机',
  assistanceChanged: '辅助条件', exposureChanged: '站内接触记录', timeBudgetChanged: '时间预算', sessionTimeChanged: '整场时间',
}
const number = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const timestamp = (value: unknown): value is string => typeof value === 'string' && value.endsWith('Z') && Number.isFinite(Date.parse(value))
const text = (value: unknown, maximum: number, required = true): value is string => typeof value === 'string'
  && aiPreparationPlanTextValid(value, maximum) && (!required || Boolean(value.trim()))
// 固定历史字段沿用 Java isBlank，NBSP 等已保存字符不能被 trim 改判为空。
const codePointText = (value: unknown, maximum: number, required = true): value is string => text(value, maximum * 2, false)
  && Array.from(value).length <= maximum && (!required || /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value))
const fields = (value: unknown, required: readonly string[], optional: readonly string[] = []): boolean => Boolean(value && typeof value === 'object'
  && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
const specializedManifestFields = ['training', 'evaluation', 'contextChecks', 'materialQa', 'preparationChecks', 'materialDraftSources',
  'expressionType', 'preparationPlan', 'trainingDraft', 'expressionChecks', 'reviewDraft', 'cardDraftType', 'factDraft', 'relationEvidence', 'relationAiFeedback', 'materialChecks', 'knowledgeMaterialChecks'] as const
const otherTaskFields = ['candidate', 'decision', 'questionCandidate', 'questionDecision', 'followUp', 'evaluation', 'evaluationReport',
  'evaluationDecisions', 'materialAnswer', 'expressionCandidate', 'expressionDecision', 'preparationPlanCandidate', 'preparationPlanDecision',
  'relationCandidate', 'relationDecisions', 'cardCandidate', 'cardDecision', 'jdRequirementsCandidate', 'jdRequirementsDecision', 'factCandidate', 'factDecision', 'rewrite', 'rewriteCandidate'] as const

function sideValid(side: AiComparisonSide): boolean {
  if (!fields(side, ['identity', 'answerNumber', 'mode', 'feedbackTiming', 'answerCondition', 'priorExposure', 'timeBudgetMinutes', 'timing', 'question', 'answer'])
    || !fields(side.identity, ['sessionId', 'questionId', 'answerId']) || !Object.values(side.identity).every(reviewIdValid)
    || !number(side.answerNumber, 1, 100) || !Object.hasOwn(trainingModeLabels, side.mode) || !Object.hasOwn(trainingFeedbackLabels, side.feedbackTiming)
    || !['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(side.answerCondition) || (side.mode === 'LEARNING') !== (side.answerCondition === 'LEARNING')
    || (side.timeBudgetMinutes !== null && !number(side.timeBudgetMinutes, 1, 240))) return false
  const exposure = side.priorExposure, timing = side.timing, question = side.question, answer = side.answer
  if (!fields(exposure, ['authoredCount', 'referenceViewCount', 'evaluationAccessCount', 'lastExposureAt'])
    || !number(exposure.authoredCount) || !number(exposure.referenceViewCount) || !number(exposure.evaluationAccessCount)
    || (exposure.lastExposureAt !== null && !timestamp(exposure.lastExposureAt))
    || !fields(timing, ['observedSeconds', 'unobservedSeconds', 'pausedSeconds', 'pendingUnknownSeconds', 'pendingPausedSeconds', 'budgetReached', 'asOf'])
    || ![timing.observedSeconds, timing.unobservedSeconds, timing.pausedSeconds].every(value => number(value))
    || timing.pendingUnknownSeconds !== 0 || timing.pendingPausedSeconds !== 0 || typeof timing.budgetReached !== 'boolean' || !timestamp(timing.asOf)
    || (side.timeBudgetMinutes === null && timing.budgetReached)
    || !fields(question, ['title', 'prompt', 'conditions', 'technology', 'scenario', 'type']) || !Object.hasOwn(questionTypeLabels, question.type)
    || !codePointText(question.title, 200) || !codePointText(question.prompt, 10000) || !codePointText(question.conditions, 5000, false)
    || !codePointText(question.technology, 2000, false) || !codePointText(question.scenario, 2000, false)
    || !fields(answer, ['text', 'nodes', 'edges']) || !codePointText(answer.text, 20000)
    || !Array.isArray(answer.nodes) || answer.nodes.length > 80 || !Array.isArray(answer.edges) || answer.edges.length > 160) return false
  const ids = new Set<string>(), nodes = new Set<string>()
  for (const node of answer.nodes) {
    if (!fields(node, ['id', 'label', 'description']) || !reviewIdValid(node.id) || ids.has(node.id)
      || !codePointText(node.label, 200) || !codePointText(node.description, 5000, false)) return false
    ids.add(node.id); nodes.add(node.id)
  }
  for (const edge of answer.edges) {
    if (!fields(edge, ['id', 'source', 'target', 'label']) || !reviewIdValid(edge.id) || ids.has(edge.id)
      || !nodes.has(edge.source) || !nodes.has(edge.target) || !codePointText(edge.label, 200, false)) return false
    ids.add(edge.id)
  }
  return true
}

export function aiComparisonInputValid(input: AiComparisonInput): boolean {
  try {
    if (!fields(input, ['comparisonId', 'baseline', 'current', 'sources', 'differences']) || !reviewIdValid(input.comparisonId)
      || !sideValid(input.baseline) || !sideValid(input.current) || input.baseline.identity.sessionId === input.current.identity.sessionId
      || input.baseline.identity.questionId === input.current.identity.questionId || input.baseline.identity.answerId === input.current.identity.answerId
      || input.current.mode !== 'FOCUSED' || input.current.feedbackTiming !== 'AFTER_SESSION'
      || (input.current.timeBudgetMinutes !== null && input.current.timeBudgetMinutes > 180)
      || !Array.isArray(input.sources) || input.sources.length < 1 || input.sources.length > 10
      || input.sources.some((source, index) => !fields(source, ['sourceId', 'verification']) || !reviewIdValid(source.sourceId)
        || !codePointText(source.verification, 2000) || (index > 0 && input.sources[index - 1]!.sourceId >= source.sourceId))
      || !fields(input.differences, Object.keys(aiComparisonDifferenceLabels)) || Object.values(input.differences).some(value => typeof value !== 'boolean')) return false
    const left = input.baseline, right = input.current, differences = input.differences
    const questionValues = (side: AiComparisonSide) => [side.question.title, side.question.prompt, side.question.conditions, side.question.technology, side.question.scenario, side.question.type]
    const exposureValues = (side: AiComparisonSide) => [side.priorExposure.authoredCount, side.priorExposure.referenceViewCount, side.priorExposure.evaluationAccessCount, side.priorExposure.lastExposureAt]
    return (differences.questionChanged || JSON.stringify(questionValues(left)) === JSON.stringify(questionValues(right)))
      && differences.modeChanged === (left.mode !== right.mode || left.feedbackTiming !== right.feedbackTiming)
      && differences.assistanceChanged === (left.answerCondition !== right.answerCondition)
      && differences.exposureChanged === (JSON.stringify(exposureValues(left)) !== JSON.stringify(exposureValues(right)))
      && differences.timeBudgetChanged === (left.timeBudgetMinutes !== right.timeBudgetMinutes)
      && differences.sessionTimeChanged === (left.timing.observedSeconds !== right.timing.observedSeconds
        || left.timing.unobservedSeconds !== right.timing.unobservedSeconds || left.timing.pausedSeconds !== right.timing.pausedSeconds)
  } catch { return false }
}

export function aiComparisonManifestMatches(manifest: AiManifest): boolean {
  try {
    if (!fields(manifest, ['purpose', 'instruction', 'sources', 'configuration', 'inputTokenAllowance', 'reservedMicros', 'promptVersion', 'comparisonInput', 'comparisonChecks'], specializedManifestFields)
      || manifest.purpose !== 'ANSWER_COMPARISON' || manifest.promptVersion !== 'answer-comparison-1' || manifest.instruction !== ''
      || !Array.isArray(manifest.sources) || manifest.sources.length !== 0 || specializedManifestFields.some(key => manifest[key] != null)
      || !manifest.configuration || !number(manifest.inputTokenAllowance, 1) || !number(manifest.reservedMicros)
      || !manifest.comparisonInput || !aiComparisonInputValid(manifest.comparisonInput)) return false
    const input = manifest.comparisonInput, checks = manifest.comparisonChecks
    if (!checks || !fields(checks, ['comparisonId', 'baselineGeneration', 'currentGeneration', 'sources']) || checks.comparisonId !== input.comparisonId
      || !number(checks.baselineGeneration) || !number(checks.currentGeneration) || !Array.isArray(checks.sources) || checks.sources.length !== input.sources.length) return false
    const actionRevisions = new Map<string, number>()
    return checks.sources.every((check, index) => {
      if (!fields(check, ['sourceId', 'actionId', 'actionRevision']) || check.sourceId !== input.sources[index]!.sourceId
        || !reviewIdValid(check.actionId) || !number(check.actionRevision, 0, 1000)
        || (actionRevisions.has(check.actionId) && actionRevisions.get(check.actionId) !== check.actionRevision)) return false
      actionRevisions.set(check.actionId, check.actionRevision)
      return true
    })
  } catch { return false }
}

export function aiComparisonCitationText(input: AiComparisonInput, citation: AiComparisonCitation): string | undefined {
  const answer = citation.side === 'BASELINE' ? input.baseline.answer : citation.side === 'CURRENT' ? input.current.answer : undefined
  if (!answer) return undefined
  if (citation.kind === 'ANSWER_TEXT') return citation.elementId === null ? answer.text : undefined
  if (citation.kind === 'ANSWER_EDGE_LABEL') return answer.edges.find(edge => edge.id === citation.elementId)?.label
  const node = answer.nodes.find(item => item.id === citation.elementId)
  return citation.kind === 'ANSWER_NODE_LABEL' ? node?.label : citation.kind === 'ANSWER_NODE_DESCRIPTION' ? node?.description : undefined
}

export function aiComparisonCandidateValid(candidate: AiComparisonCandidate, input: AiComparisonInput): boolean {
  try {
    if (!aiComparisonInputValid(input) || !fields(candidate, ['level', 'summary', 'limitations', 'suggestions', 'validationNote'])
      || candidate.level !== 'AI_UNREVIEWED' || !text(candidate.summary, 1000) || !text(candidate.limitations, 2000)
      || !text(candidate.validationNote, Number.MAX_SAFE_INTEGER) || !Array.isArray(candidate.suggestions) || candidate.suggestions.length !== input.sources.length) return false
    const selected = new Set(input.sources.map(source => source.sourceId)), seen = new Set<string>()
    return candidate.suggestions.every(suggestion => {
      if (!fields(suggestion, ['sourceId', 'suggestedOutcome', 'reason', 'limitations', 'citations']) || !selected.has(suggestion.sourceId) || seen.has(suggestion.sourceId)
        || !Object.hasOwn(repracticeOutcomeLabels, suggestion.suggestedOutcome) || !text(suggestion.reason, 1400) || !text(suggestion.limitations, 500)
        || !Array.isArray(suggestion.citations) || suggestion.citations.length > 6) return false
      seen.add(suggestion.sourceId)
      const positions = new Set<string>()
      for (const citation of suggestion.citations) {
        if (!fields(citation, ['side', 'kind', 'elementId', 'start', 'end', 'quote']) || !Object.hasOwn(aiComparisonSideLabels, citation.side)
          || !Object.hasOwn(aiComparisonEvidenceLabels, citation.kind) || !text(citation.quote, 1000) || Array.from(citation.quote).length > 500
          || (citation.kind === 'ANSWER_TEXT' ? citation.elementId !== null : !reviewIdValid(citation.elementId))) return false
        const original = aiComparisonCitationText(input, citation), key = JSON.stringify([citation.side, citation.kind, citation.elementId, citation.start, citation.end])
        if (original === undefined || !aiTrainingDraftRangeMatches(original, citation.start, citation.end, citation.quote) || positions.has(key)) return false
        positions.add(key)
      }
      return !['HANDLED', 'PARTIAL'].includes(suggestion.suggestedOutcome) || suggestion.citations.some(citation => citation.side === 'CURRENT')
    })
  } catch { return false }
}

export function aiComparisonTaskValid(task: AiTask, comparisonId?: string): boolean {
  try {
    if (!task || !task.task || !reviewIdValid(task.task.id) || task.task.purpose !== 'ANSWER_COMPARISON'
      || !number(task.task.revision) || !Object.hasOwn(aiStatusLabels, task.task.status) || typeof task.sourcesCurrent !== 'boolean'
      || !aiComparisonManifestMatches(task.manifest) || otherTaskFields.some(key => task[key] != null)
      || !task.comparison || !fields(task.comparison, ['comparisonId', 'sourceIds'])
      || task.comparison.comparisonId !== task.manifest.comparisonInput!.comparisonId
      || (comparisonId !== undefined && task.comparison.comparisonId !== comparisonId)
      || !Array.isArray(task.comparison.sourceIds) || task.comparison.sourceIds.length !== task.manifest.comparisonInput!.sources.length
      || task.comparison.sourceIds.some((id, index) => id !== task.manifest.comparisonInput!.sources[index]!.sourceId)
      || !number(task.task.attemptCount, 1, 3) || !Array.isArray(task.attempts) || task.attempts.length !== task.task.attemptCount
      || task.attempts.some(attempt => !reviewIdValid(attempt.id) || !number(attempt.number, 1, 3) || !Object.hasOwn(aiStatusLabels, attempt.status)
        || !Object.hasOwn(aiFeeLabels, attempt.feeStatus) || !number(attempt.reservedMicros)
        || (attempt.accountedMicros !== null && !number(attempt.accountedMicros)))
      || new Set(task.attempts.map(attempt => attempt.id)).size !== task.attempts.length
      || new Set(task.attempts.map(attempt => attempt.number)).size !== task.attempts.length) return false
    return task.task.status === 'SUCCEEDED' ? Boolean(task.comparisonCandidate && aiComparisonCandidateValid(task.comparisonCandidate, task.manifest.comparisonInput!))
      : task.comparisonCandidate == null
  } catch { return false }
}
