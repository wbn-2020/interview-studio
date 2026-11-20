import type { Schema } from './client'
import type { AiManifest, AiTask } from './ai'
import { aiKnowledgeMaterialChecksMatch } from './ai'
import { questionTypeLabels } from './bank'

export type AiRewritePreviewRequest = Schema['AiRewritePreviewRequest']
export type AiCreateRewrite = Schema['AiCreateRewrite']
export type AiRewriteCandidate = Schema['AiRewriteCandidate']
export type AiRewriteChange = Schema['AiRewriteChange']
export type AiRewriteCitation = Schema['AiEvaluationCitation']
export type AiRewriteLink = Schema['AiEvaluationLink']
export type AiRewritePage = Schema['AiEvaluationPage']
export type AiRewriteInput = Schema['AiEvaluationInput']

export const aiRewriteValidationNote: AiRewriteCandidate['validationNote'] = 'AI 改写仅供参考，不代表原答、真实经历或已验证能力；新增内容请先核实，再独立作答。'
export const aiRewriteEvidenceLabels: Record<AiRewriteCitation['kind'], string> = {
  ANSWER_TEXT: '回答原文', ANSWER_NODE_LABEL: '答案图节点名称', ANSWER_NODE_DESCRIPTION: '答案图节点说明', ANSWER_EDGE_LABEL: '答案图连线说明',
  QUESTION_PROMPT: '固定题干', QUESTION_CONDITIONS: '题目条件', REFERENCE: '个人未审参考', CHECKPOINT: '个人未审检查点',
}
export const aiRewritePaths = {
  preview: '/ai/answer-rewrites/preview', create: '/ai/answer-rewrites/tasks',
  tasks: (sessionId: string, questionId?: string, answerId?: string, cursor?: string, limit = 20) => {
    const query = new URLSearchParams({ sessionId, limit: String(limit) })
    if (questionId !== undefined) query.set('questionId', questionId)
    if (answerId !== undefined) query.set('answerId', answerId)
    if (cursor !== undefined) query.set('cursor', cursor)
    return `/ai/answer-rewrites/tasks?${query}`
  },
} as const

const otherManifestFields = ['training', 'contextChecks', 'materialQa', 'preparationChecks', 'materialDraftSources',
  'expressionType', 'preparationPlan', 'trainingDraft', 'expressionChecks', 'comparisonInput', 'comparisonChecks',
  'reviewDraft', 'cardDraftType', 'factDraft', 'relationEvidence', 'relationAiFeedback', 'materialChecks'] as const
const otherTaskFields = ['decision', 'questionCandidate', 'questionDecision', 'followUp', 'evaluation', 'evaluationReport',
  'materialAnswer', 'expressionCandidate', 'expressionDecision', 'preparationPlanCandidate', 'preparationPlanDecision',
  'relationCandidate', 'relationDecisions', 'comparison', 'comparisonCandidate', 'cardCandidate', 'cardDecision',
  'jdRequirementsCandidate', 'jdRequirementsDecision', 'factCandidate', 'factDecision'] as const
const statuses = ['QUEUED', 'RUNNING', 'VALIDATING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCEL_REQUESTED', 'CANCELED']
const feeStatuses = ['RESERVED', 'REPORTED', 'UNKNOWN', 'RELEASED', 'RECONCILED']
const fields = (value: unknown, required: readonly string[], optional: readonly string[] = []): boolean => Boolean(value && typeof value === 'object'
  && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const text = (value: unknown, maximum: number, required = true): value is string => typeof value === 'string' && value.length <= maximum
  && !/[\u0000\uD800-\uDFFF]/u.test(value) && (!required || /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value))
// 历史题面与提交按原 Java 码点／isBlank 规则读取，不将新候选的长度或空白规则套回旧内容。
const sourceText = (value: unknown, maximum: number, required = true): value is string => text(value, maximum * 2, false)
  && Array.from(value).length <= maximum && (!required || /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value))
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))

export function aiRewriteRequestValid(request: AiRewritePreviewRequest): boolean {
  return fields(request, ['sessionId', 'questionId', 'answerId'])
    && uuid(request.sessionId) && uuid(request.questionId) && uuid(request.answerId)
}

export function aiRewriteInputValid(input: AiRewriteInput): boolean {
  try {
    if (!fields(input, ['sessionId', 'questionId', 'answerId', 'answerNumber', 'mode', 'feedbackTiming', 'answerCondition', 'question', 'answer', 'standard'])
      || !uuid(input.sessionId) || !uuid(input.questionId) || !uuid(input.answerId) || !integer(input.answerNumber, 1, 100)
      || !['LEARNING', 'FOCUSED', 'MOCK'].includes(input.mode) || !['AFTER_ANSWER', 'AFTER_SESSION'].includes(input.feedbackTiming)
      || !['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(input.answerCondition)
      || (input.mode === 'LEARNING') !== (input.answerCondition === 'LEARNING')) return false
    const question = input.question, answer = input.answer, standard = input.standard
    if (!fields(question, ['title', 'prompt', 'conditions', 'technology', 'scenario', 'type']) || !Object.hasOwn(questionTypeLabels, question.type)
      || !sourceText(question.title, 200) || !sourceText(question.prompt, 10000) || !sourceText(question.conditions, 5000, false)
      || !sourceText(question.technology, 2000, false) || !sourceText(question.scenario, 2000, false)
      || !fields(answer, ['text', 'nodes', 'edges']) || !sourceText(answer.text, 20000)
      || !Array.isArray(answer.nodes) || answer.nodes.length > 80 || !Array.isArray(answer.edges) || answer.edges.length > 160) return false
    const ids = new Set<string>(), nodes = new Set<string>()
    for (const node of answer.nodes) {
      if (!fields(node, ['id', 'label', 'description']) || !uuid(node.id) || ids.has(node.id)
        || !sourceText(node.label, 200) || !sourceText(node.description, 5000, false)) return false
      ids.add(node.id); nodes.add(node.id)
    }
    for (const edge of answer.edges) {
      if (!fields(edge, ['id', 'source', 'target', 'label']) || !uuid(edge.id) || ids.has(edge.id)
        || !uuid(edge.source) || !uuid(edge.target) || !nodes.has(edge.source) || !nodes.has(edge.target) || !sourceText(edge.label, 200, false)) return false
      ids.add(edge.id)
    }
    return standard === null || fields(standard, ['questionId', 'versionId', 'status', 'referenceAnswer', 'checkpoints'])
      && uuid(standard.questionId) && uuid(standard.versionId) && standard.status === 'PERSONAL_UNREVIEWED'
      && sourceText(standard.referenceAnswer, 20000, false) && Array.isArray(standard.checkpoints) && standard.checkpoints.length <= 30
      && standard.checkpoints.every(point => fields(point, ['id', 'text']) && uuid(point.id) && sourceText(point.text, 500))
      && new Set(standard.checkpoints.map(point => point.id)).size === standard.checkpoints.length
  } catch { return false }
}

export function aiRewriteManifestMatches(manifest: AiManifest): boolean {
  try {
    return fields(manifest, ['purpose', 'instruction', 'sources', 'configuration', 'inputTokenAllowance', 'reservedMicros', 'promptVersion', 'evaluation'], [...otherManifestFields, 'knowledgeMaterialChecks'])
      && manifest.purpose === 'ANSWER_REWRITE' && manifest.promptVersion === 'answer-rewrite-1' && manifest.instruction === ''
      && Array.isArray(manifest.sources) && manifest.sources.length === 0 && otherManifestFields.every(key => manifest[key] == null)
      && Boolean(manifest.configuration && typeof manifest.configuration === 'object' && !Array.isArray(manifest.configuration))
      && integer(manifest.inputTokenAllowance, 1) && integer(manifest.reservedMicros)
      && Boolean(manifest.evaluation && aiRewriteInputValid(manifest.evaluation))
      && aiKnowledgeMaterialChecksMatch(manifest)
  } catch { return false }
}

export function aiRewriteEvidenceText(input: AiRewriteInput, citation: AiRewriteCitation): string | undefined {
  if (citation.kind === 'ANSWER_TEXT') return citation.elementId === null ? input.answer.text : undefined
  if (citation.kind === 'QUESTION_PROMPT') return citation.elementId === null ? input.question.prompt : undefined
  if (citation.kind === 'QUESTION_CONDITIONS') return citation.elementId === null ? input.question.conditions : undefined
  if (citation.kind === 'REFERENCE') return citation.elementId === null ? input.standard?.referenceAnswer : undefined
  if (citation.kind === 'CHECKPOINT') return input.standard?.checkpoints.find(point => point.id === citation.elementId)?.text
  if (citation.kind === 'ANSWER_EDGE_LABEL') return input.answer.edges.find(edge => edge.id === citation.elementId)?.label
  const node = input.answer.nodes.find(item => item.id === citation.elementId)
  return citation.kind === 'ANSWER_NODE_LABEL' ? node?.label : citation.kind === 'ANSWER_NODE_DESCRIPTION' ? node?.description : undefined
}

export function aiRewriteCitationValid(citation: AiRewriteCitation, input: AiRewriteInput): boolean {
  try {
    if (!fields(citation, ['kind', 'elementId', 'start', 'end', 'quote']) || !Object.hasOwn(aiRewriteEvidenceLabels, citation.kind)
      || !text(citation.quote, 500) || !integer(citation.start, 0, 2147483647) || !integer(citation.end, 1, 2147483647)
      || citation.end <= citation.start || (['ANSWER_TEXT', 'QUESTION_PROMPT', 'QUESTION_CONDITIONS', 'REFERENCE'].includes(citation.kind)
        ? citation.elementId !== null : !uuid(citation.elementId))) return false
    const original = aiRewriteEvidenceText(input, citation)
    if (original === undefined || citation.end > original.length) return false
    const split = (at: number) => at > 0 && at < original.length
      && /[\uD800-\uDBFF]/.test(original.charAt(at - 1)) && /[\uDC00-\uDFFF]/.test(original.charAt(at))
    return !split(citation.start) && !split(citation.end) && original.slice(citation.start, citation.end) === citation.quote
  } catch { return false }
}

export function aiRewriteCandidateValid(candidate: AiRewriteCandidate, manifest: AiManifest): boolean {
  try {
    if (!aiRewriteManifestMatches(manifest) || !fields(candidate, ['level', 'summary', 'answer', 'changes', 'questions', 'limitations', 'validationNote'])
      || candidate.level !== 'AI_UNREVIEWED' || candidate.validationNote !== aiRewriteValidationNote
      || !text(candidate.summary, 2000) || !text(candidate.answer, 20000) || !text(candidate.limitations, 2000)
      || !Array.isArray(candidate.changes) || candidate.changes.length < 1 || candidate.changes.length > 12
      || !Array.isArray(candidate.questions) || candidate.questions.length > 8 || !candidate.questions.every(question => text(question, 1000))) return false
    let total = candidate.summary.length + candidate.answer.length + candidate.limitations.length
      + candidate.questions.reduce((sum, question) => sum + question.length, 0)
    for (const change of candidate.changes) {
      if (!fields(change, ['explanation', 'citations']) || !text(change.explanation, 1000)
        || !Array.isArray(change.citations) || change.citations.length < 1 || change.citations.length > 6) return false
      const positions = new Set<string>()
      for (const citation of change.citations) {
        if (!aiRewriteCitationValid(citation, manifest.evaluation!)) return false
        const key = JSON.stringify([citation.kind, citation.elementId, citation.start, citation.end])
        if (positions.has(key)) return false
        positions.add(key)
        total += citation.quote.length
      }
      total += change.explanation.length
    }
    return total <= 32000
  } catch { return false }
}

function summaryValid(summary: AiTask['task']): boolean {
  return fields(summary, ['id', 'purpose', 'status', 'revision', 'title', 'provider', 'model', 'currency', 'attemptCount', 'createdAt', 'updatedAt', 'errorCode'])
    && uuid(summary.id) && summary.purpose === 'ANSWER_REWRITE' && statuses.includes(summary.status) && integer(summary.revision)
    && sourceText(summary.title, 200) && text(summary.provider, Number.MAX_SAFE_INTEGER) && text(summary.model, Number.MAX_SAFE_INTEGER)
    && text(summary.currency, Number.MAX_SAFE_INTEGER) && integer(summary.attemptCount, 1, 3)
    && timestamp(summary.createdAt) && timestamp(summary.updatedAt) && (summary.errorCode === null || text(summary.errorCode, Number.MAX_SAFE_INTEGER))
}

export function aiRewriteTaskMatches(value: AiTask, sessionId?: string, questionId?: string, answerId?: string): boolean {
  try {
    if (!value?.task || !value.manifest) return false
    if (value.manifest.relationEvidence != null && (value.task.purpose !== 'RELATION_DRAFT'
      || value.manifest.purpose !== 'RELATION_DRAFT' || value.manifest.promptVersion !== 'relation-evidence-draft-1')) return false
    if (value.manifest.relationAiFeedback != null && (value.task.purpose !== 'RELATION_DRAFT'
      || value.manifest.purpose !== 'RELATION_DRAFT' || value.manifest.promptVersion !== 'relation-ai-feedback-draft-1')) return false
    const rewrite = value.task.purpose === 'ANSWER_REWRITE' || value.manifest.purpose === 'ANSWER_REWRITE' || value.manifest.promptVersion === 'answer-rewrite-1'
    if (!rewrite) return sessionId === undefined && questionId === undefined && answerId === undefined && value.rewrite == null && value.rewriteCandidate == null
    if (!fields(value, ['task', 'manifest', 'attempts', 'candidate', 'evaluationDecisions', 'sourcesCurrent', 'rewrite', 'rewriteCandidate'], otherTaskFields)
      || !summaryValid(value.task) || !aiRewriteManifestMatches(value.manifest) || typeof value.sourcesCurrent !== 'boolean'
      || value.candidate !== null || value.evaluationDecisions !== null || otherTaskFields.some(key => value[key] != null)) return false
    const input = value.manifest.evaluation!, link = value.rewrite
    if (!link || !fields(link, ['sessionId', 'questionId', 'answerId', 'answerNumber']) || link.sessionId !== input.sessionId
      || link.questionId !== input.questionId || link.answerId !== input.answerId || link.answerNumber !== input.answerNumber
      || sessionId !== undefined && link.sessionId !== sessionId || questionId !== undefined && link.questionId !== questionId
      || answerId !== undefined && link.answerId !== answerId || value.task.title !== input.question.title
      || value.task.provider !== value.manifest.configuration.provider || value.task.model !== value.manifest.configuration.model
      || value.task.currency !== value.manifest.configuration.currency
      || !Array.isArray(value.attempts) || value.attempts.length !== value.task.attemptCount
      || value.attempts.some(attempt => !attempt || !uuid(attempt.id) || !integer(attempt.number, 1, value.task.attemptCount)
        || !statuses.includes(attempt.status) || !feeStatuses.includes(attempt.feeStatus) || !integer(attempt.reservedMicros)
        || attempt.accountedMicros !== null && !integer(attempt.accountedMicros))
      || new Set(value.attempts.map(attempt => attempt.id)).size !== value.attempts.length
      || new Set(value.attempts.map(attempt => attempt.number)).size !== value.attempts.length) return false
    return value.task.status === 'SUCCEEDED' ? Boolean(value.rewriteCandidate && aiRewriteCandidateValid(value.rewriteCandidate, value.manifest)) : value.rewriteCandidate === null
  } catch { return false }
}

export function aiRewritePageValid(page: AiRewritePage, sessionId: string, questionId?: string, answerId?: string, cursor?: string, limit = 20): boolean {
  try {
    if (!uuid(sessionId) || questionId !== undefined && !uuid(questionId) || answerId !== undefined && (!uuid(answerId) || questionId === undefined)
      || cursor !== undefined && !uuid(cursor) || !integer(limit, 1, 20) || !fields(page, ['items', 'nextCursor'])
      || !Array.isArray(page.items) || page.items.length > limit || page.nextCursor !== null && !uuid(page.nextCursor)) return false
    const seen = new Set<string>()
    return page.items.every((item, index) => {
      if (!fields(item, ['task', 'questionId', 'answerId', 'answerNumber']) || !summaryValid(item.task)
        || !uuid(item.questionId) || !uuid(item.answerId) || !integer(item.answerNumber, 1, 100)
        || questionId !== undefined && item.questionId !== questionId || answerId !== undefined && item.answerId !== answerId
        || seen.has(item.task.id) || cursor !== undefined && item.task.id <= cursor
        || index > 0 && item.task.id <= page.items[index - 1]!.task.id) return false
      seen.add(item.task.id)
      return true
    }) && (page.nextCursor === null || page.items.length === limit && page.nextCursor === page.items.at(-1)?.task.id)
  } catch { return false }
}
