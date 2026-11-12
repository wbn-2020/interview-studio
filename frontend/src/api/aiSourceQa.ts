import type { RouteLocationRaw } from 'vue-router'
import type { Schema } from './client'
import type { AiManifest, AiTask } from './ai'
import { aiKnowledgeMaterialChecksMatch, aiMaterialChecksMatch } from './ai'
import type { SearchDomain, SearchHit } from './search'
import { searchDomains } from './search'
import { questionDimensionLabels, questionTypeLabels } from './bank'

export type AiSourceQaRef = Schema['AiSourceQaRef']
export type AiSourceQaRange = Schema['AiSourceQaRange']
export type AiSourceQaSelection = Schema['AiSourceQaSelection']
export type AiSourceQaPreviewRequest = Schema['AiSourceQaPreviewRequest']
export type AiSourceQaCreateRequest = Schema['AiSourceQaCreateRequest']
export type AiSourceQaInput = Schema['AiSourceQaInput']
export type AiSourceQaSource = Schema['AiSourceQaSource']
export type AiSourceQaCitation = Schema['AiSourceQaCitation']
export type AiSourceQaAnswer = Schema['AiSourceQaAnswer']

const statuses = ['QUEUED', 'RUNNING', 'VALIDATING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCEL_REQUESTED', 'CANCELED']
const fees = ['RESERVED', 'REPORTED', 'UNKNOWN', 'RELEASED', 'RECONCILED']
const manifestTails = ['training', 'evaluation', 'contextChecks', 'materialQa', 'preparationChecks', 'materialDraftSources', 'expressionType',
  'preparationPlan', 'trainingDraft', 'expressionChecks', 'comparisonInput', 'comparisonChecks', 'reviewDraft', 'cardDraftType', 'factDraft', 'relationEvidence', 'relationAiFeedback'] as const
const taskTails = ['candidate', 'decision', 'questionCandidate', 'questionDecision', 'followUp', 'evaluation', 'evaluationReport', 'evaluationDecisions',
  'materialAnswer', 'expressionCandidate', 'expressionDecision', 'preparationPlanCandidate', 'preparationPlanDecision', 'relationCandidate', 'relationDecisions',
  'comparison', 'comparisonCandidate', 'cardCandidate', 'cardDecision', 'jdRequirementsCandidate', 'jdRequirementsDecision', 'factCandidate', 'factDecision', 'rewrite', 'rewriteCandidate'] as const
const contextKeys = ['training', 'feedback', 'recollection', 'aiFeedback', 'question', 'fact', 'expression', 'material'] as const
const fields = (value: unknown, required: readonly string[], optional: readonly string[] = []): boolean => Boolean(value && typeof value === 'object'
  && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const hash = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const hasText = (value: string) => /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
export const aiSourceQaTextValid = (value: unknown, maximum: number, required = false): value is string => typeof value === 'string'
  && value.length <= maximum && !/[\u0000\uD800-\uDFFF]/u.test(value) && (!required || hasText(value))
const points = (value: unknown, maximum: number, required = true): value is string => aiSourceQaTextValid(value, maximum * 2, required) && Array.from(value).length <= maximum
const timestamp = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
export const aiSourceQaQuestion = (value: string) => value.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/gu, '')
export const aiSourceQaRefKey = (value: AiSourceQaRef) => JSON.stringify([value.domain, value.id, value.versionId, value.revision, value.feedbackId, value.reportHash])

export function aiSourceQaRef(hit: SearchHit): AiSourceQaRef {
  return { domain: hit.domain, id: hit.id, versionId: hit.versionId, revision: hit.domain === 'REVIEW_FEEDBACK' ? hit.versionNumber : null,
    feedbackId: hit.domain === 'AI_FEEDBACK' ? hit.aiFeedback?.feedbackId ?? null : null, reportHash: hit.domain === 'AI_FEEDBACK' ? hit.aiFeedback?.reportHash ?? null : null }
}
export function aiSourceQaRefValid(ref: AiSourceQaRef): boolean {
  if (!fields(ref, ['domain', 'id', 'versionId', 'revision', 'feedbackId', 'reportHash']) || !searchDomains.some(item => item.value === ref.domain) || !uuid(ref.id) || !uuid(ref.versionId)) return false
  if (ref.domain === 'REVIEW_FEEDBACK') return ref.id === ref.versionId && integer(ref.revision, 1, 100) && ref.feedbackId === null && ref.reportHash === null
  if (ref.revision !== null) return false
  if (ref.domain === 'AI_FEEDBACK') return typeof ref.feedbackId === 'string' && /^f([1-9]|1[0-2])$/.test(ref.feedbackId) && hash(ref.reportHash)
  return ref.feedbackId === null && ref.reportHash === null && (!['MATERIAL_EXCERPT', 'TRAINING_ANSWER'].includes(ref.domain) || ref.id === ref.versionId)
}
export function aiSourceQaFieldAllowed(domain: SearchDomain, kind: string, field: string): boolean {
  const tag = /^tags\.([0-9]|1[0-9])$/.test(field)
  if (domain === 'KNOWLEDGE') return ['title', 'body'].includes(field)
  if (domain === 'PREPARATION') {
    if (kind === 'TARGET') return ['title', 'company', 'jd'].includes(field) || /^requirements\.([0-9]|[1-4][0-9])\.text$/.test(field)
    if (kind === 'PROJECT') return ['title', 'overview', 'technology'].includes(field)
    if (kind === 'FACT') return ['title', 'scenario', 'problem', 'action', 'result', 'technology'].includes(field) || tag
    return kind === 'EXPRESSION' && (['title', 'body'].includes(field) || tag)
  }
  if (domain === 'QUESTION') return ['title', 'prompt', 'conditions', 'technology', 'scenario'].includes(field) || tag
  if (domain === 'MATERIAL_EXCERPT') return ['locator', 'text'].includes(field)
  if (domain === 'TRAINING_ANSWER') return field === 'text' || /^diagram\.(nodes\.([0-9]|[1-7][0-9])\.(label|description)|edges\.([0-9]|[1-9][0-9]|1[0-5][0-9])\.label)$/.test(field)
  if (domain === 'REVIEW_FEEDBACK' || domain === 'AI_FEEDBACK') return ['observation', 'condition', 'basis', 'limitation', 'suggestion'].includes(field)
  return domain === 'RECOLLECTION' && ['title', 'question', 'conditions', 'answerFragment', 'feedback', 'uncertainty', 'gap'].includes(field)
}
export function aiSourceQaRangeValid(range: AiSourceQaRange, original?: string): boolean {
  if (!fields(range, ['field', 'start', 'end']) || !/^[A-Za-z][A-Za-z0-9.]{0,99}$/.test(range.field)
    || !integer(range.start, 0, 2147483647) || !integer(range.end, range.start + 1, 2147483647) || range.end - range.start > 8000) return false
  // substring 后再次校验 Unicode，可同时拒绝两端切开代理对；原文从不 trim。
  return original === undefined || range.end <= original.length && aiSourceQaTextValid(original.slice(range.start, range.end), 8000)
}
export function aiSourceQaRequestValid(request: AiSourceQaPreviewRequest): boolean {
  try {
    if (!fields(request, ['question', 'selections']) || !aiSourceQaTextValid(request.question, 2000, true) || aiSourceQaQuestion(request.question) !== request.question
      || !Array.isArray(request.selections) || request.selections.length < 1 || request.selections.length > 5) return false
    const seen = new Set<string>(); let total = 0
    return request.selections.every(selection => {
      if (!fields(selection, ['source', 'ranges']) || !aiSourceQaRefValid(selection.source) || seen.has(aiSourceQaRefKey(selection.source))
        || !Array.isArray(selection.ranges) || selection.ranges.length < 1 || selection.ranges.length > 8) return false
      seen.add(aiSourceQaRefKey(selection.source)); total += selection.ranges.length
      return selection.ranges.every((range, index) => aiSourceQaRangeValid(range) && !selection.ranges.slice(0, index)
        .some(other => other.field === range.field && other.start < range.end && range.start < other.end))
    }) && total <= 8
  } catch { return false }
}

function trainingValid(value: AiSourceQaSource['training']): boolean {
  if (!value || !fields(value, ['answer', 'feedbackTiming', 'conditions']) || !fields(value.answer, ['sessionId', 'questionId', 'answerId'])
    || ![value.answer.sessionId, value.answer.questionId, value.answer.answerId].every(uuid) || !['AFTER_ANSWER', 'AFTER_SESSION'].includes(value.feedbackTiming)) return false
  const c = value.conditions, e = c?.priorExposure
  return fields(c, ['mode', 'answerCondition', 'assistanceEventSequence', 'priorExposure', 'questionKind', 'answerNumber', 'answerCreatedAt', 'technology', 'scenario', 'questionConditions'])
    && ['LEARNING', 'FOCUSED', 'MOCK'].includes(c.mode) && ['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(c.answerCondition)
    && ['MAIN', 'FOLLOW_UP', 'REPLAY'].includes(c.questionKind) && integer(c.answerNumber, 1, 100) && integer(c.assistanceEventSequence, 0, 2000)
    && (c.mode === 'LEARNING') === (c.answerCondition === 'LEARNING') && (c.answerCondition !== 'BEFORE_ASSISTANCE' || c.assistanceEventSequence === 0)
    && (c.answerCondition !== 'ASSISTED' || c.assistanceEventSequence > 0) && timestamp(c.answerCreatedAt)
    && fields(e, ['authoredCount', 'referenceViewCount', 'evaluationAccessCount', 'lastExposureAt']) && integer(e.authoredCount) && integer(e.referenceViewCount)
    && integer(e.evaluationAccessCount) && (e.lastExposureAt === null || timestamp(e.lastExposureAt))
    && points(c.technology, 2000, false) && points(c.scenario, 2000, false) && points(c.questionConditions, 5000, false)
}
const decisionValid = (revision: number, choice: string | null) => integer(revision, 0, 20)
  && (revision === 0 ? choice === null : choice !== null && ['ACCEPTED', 'DISPUTED', 'IGNORED'].includes(choice))
function completeField(source: AiSourceQaSource, field: string): string | undefined {
  if (field === 'title') return source.title
  if (source.question && ['conditions', 'technology', 'scenario'].includes(field)) return source.question[field as keyof typeof source.question]
  if (['condition', 'basis', 'limitation'].includes(field)) {
    const context = source.feedback ?? source.aiFeedback
    if (context) return context[field as 'condition' | 'basis' | 'limitation']
  }
  if (field === 'uncertainty') return source.recollection?.uncertainty
  if (field === 'locator') return source.material?.locator
  return undefined
}
function sourceValid(source: AiSourceQaSource): boolean {
  if (!fields(source, ['sourceKey', 'ref', 'kind', 'title', 'versionNumber', 'fragments'], contextKeys) || !aiSourceQaRefValid(source.ref)
    || contextKeys.some(key => Object.hasOwn(source, key) && source[key] == null)
    || !points(source.title, 200) || !Array.isArray(source.fragments) || source.fragments.length < 1 || source.fragments.length > 8
    || (['AI_FEEDBACK', 'MATERIAL_EXCERPT'].includes(source.ref.domain) ? source.versionNumber !== null : !integer(source.versionNumber, 1, 2147483647))) return false
  if (!source.fragments.every((fragment, index) => fields(fragment, ['field', 'label', 'start', 'end', 'text'])
    && aiSourceQaFieldAllowed(source.ref.domain, source.kind, fragment.field) && aiSourceQaRangeValid({ field: fragment.field, start: fragment.start, end: fragment.end })
    && aiSourceQaTextValid(fragment.label, 200, true) && aiSourceQaTextValid(fragment.text, 8000) && fragment.end - fragment.start === fragment.text.length
    && (completeField(source, fragment.field) === undefined || completeField(source, fragment.field)!.slice(fragment.start, fragment.end) === fragment.text)
    && !source.fragments.slice(0, index).some(other => other.field === fragment.field && other.start < fragment.end && fragment.start < other.end))) return false
  const count = contextKeys.filter(key => source[key] != null).length
  switch (source.ref.domain) {
    case 'KNOWLEDGE': return count === 0 && ['NOTE', 'CONCEPT'].includes(source.kind)
    case 'PREPARATION':
      if (['TARGET', 'PROJECT'].includes(source.kind)) return count === 0
      if (source.kind === 'FACT') return count === 1 && Boolean(source.fact && fields(source.fact, ['responsibility', 'verification', 'projectStatus'])
        && ['OWNER', 'PARTICIPANT', 'FAMILIAR'].includes(source.fact.responsibility) && source.fact.verification === 'CONFIRMED' && source.fact.projectStatus === 'ACTIVE')
      return source.kind === 'EXPRESSION' && count === 1 && Boolean(source.expression && fields(source.expression, ['basisReviewed']) && source.expression.basisReviewed === true)
    case 'QUESTION': return count === 1 && Object.hasOwn(questionTypeLabels, source.kind) && Boolean(source.question && fields(source.question, ['conditions', 'technology', 'scenario'])
      && points(source.question.conditions, 5000, false) && points(source.question.technology, 2000, false) && points(source.question.scenario, 2000, false))
    case 'TRAINING_ANSWER': return count === 1 && source.kind === 'ANSWER' && trainingValid(source.training) && source.training!.answer.answerId === source.ref.id && source.training!.conditions.answerNumber === source.versionNumber
    case 'REVIEW_FEEDBACK': {
      const f = source.feedback
      return count === 2 && trainingValid(source.training) && source.ref.revision === source.versionNumber && Boolean(f
        && fields(f, ['kind', 'authorLabel', 'dimension', 'coverage', 'condition', 'basis', 'limitation', 'decisionRevision', 'decisionChoice'])
        && ['SELF_CHECK', 'EXTERNAL_FEEDBACK'].includes(f.kind) && source.kind === f.kind && points(f.authorLabel, 120, f.kind === 'EXTERNAL_FEEDBACK')
        && (f.kind !== 'SELF_CHECK' || f.authorLabel === '') && Object.hasOwn(questionDimensionLabels, f.dimension)
        && ['MENTIONED', 'NOT_MENTIONED', 'UNCERTAIN'].includes(f.coverage) && points(f.condition, 1000, false) && points(f.basis, 1000) && points(f.limitation, 1000)
        && decisionValid(f.decisionRevision, f.decisionChoice))
    }
    case 'AI_FEEDBACK': {
      const f = source.aiFeedback
      return count === 2 && source.kind === 'AI_FEEDBACK' && trainingValid(source.training) && Boolean(f
        && fields(f, ['feedbackId', 'reportHash', 'level', 'reportLimitations', 'validationNote', 'dimension', 'coverage', 'condition', 'basis', 'limitation', 'decisionRevision', 'decisionChoice'])
        && f.feedbackId === source.ref.feedbackId && f.reportHash === source.ref.reportHash && f.level === 'AI_UNREVIEWED'
        && points(f.reportLimitations, 2000) && f.validationNote === 'AI 未审评价，结构与确切输入的原文定位已核对；仅表示结构和定位合法，不证明语义、技术正确性或用户经历真实。个人参考与检查点仍为未审标准，内容需人工审阅。' && Object.hasOwn(questionDimensionLabels, f.dimension)
        && ['MENTIONED', 'NOT_MENTIONED', 'UNCERTAIN'].includes(f.coverage) && points(f.condition, 1000, false) && points(f.basis, 1000) && points(f.limitation, 1000)
        && decisionValid(f.decisionRevision, f.decisionChoice))
    }
    case 'RECOLLECTION': return count === 1 && source.kind === 'RECOLLECTION' && integer(source.versionNumber, 1, 1000) && Boolean(source.recollection
      && fields(source.recollection, ['baselineKind', 'questionType', 'uncertainty']) && ['RECOLLECTION', 'NONE'].includes(source.recollection.baselineKind)
      && Object.hasOwn(questionTypeLabels, source.recollection.questionType) && points(source.recollection.uncertainty, 2000)
      && (source.recollection.baselineKind !== 'NONE' || !source.fragments.some(fragment => fragment.field === 'answerFragment')))
    case 'MATERIAL_EXCERPT': {
      const m = source.material
      return count === 1 && source.kind === 'EXCERPT' && Boolean(m && fields(m, ['attachmentId', 'origin', 'sha256', 'runId', 'segmentId', 'locator', 'startOffset', 'endOffset'])
        && uuid(m.attachmentId) && hash(m.sha256) && aiSourceQaTextValid(m.locator, 500, true)
        && (m.origin === 'MANUAL' ? m.runId === null && m.segmentId === null && m.startOffset === null && m.endOffset === null
          : m.origin === 'PARSED' && uuid(m.runId) && uuid(m.segmentId) && integer(m.startOffset, 0, 2147483647) && integer(m.endOffset, m.startOffset + 1, 2147483647)))
    }
  }
}
function preparationValid(value: Schema['PrepareSourceCheck'] | null): boolean {
  return Boolean(value && fields(value, ['kind', 'itemId', 'versionId', 'state', 'sourceGeneration', 'projectGeneration'])
    && ['TARGET', 'PROJECT', 'FACT', 'EXPRESSION'].includes(value.kind) && uuid(value.itemId) && uuid(value.versionId) && value.state === 'CURRENT'
    && integer(value.sourceGeneration) && (value.kind === 'FACT' ? integer(value.projectGeneration) : value.projectGeneration === null))
}
function checkValid(source: AiSourceQaSource, check: AiSourceQaInput['checks'][number]): boolean {
  if (!fields(check, ['sourceKey', 'metadataRevision', 'sourceRevision', 'preparation', 'expression']) || check.sourceKey !== source.sourceKey) return false
  if (['KNOWLEDGE', 'QUESTION'].includes(source.ref.domain)) return integer(check.metadataRevision) && check.sourceRevision === null && check.preparation === null && check.expression === null
  if (source.ref.domain === 'RECOLLECTION') return check.metadataRevision === null && integer(check.sourceRevision, 0, 1000) && source.versionNumber! <= check.sourceRevision + 1 && check.preparation === null && check.expression === null
  if (check.metadataRevision !== null || check.sourceRevision !== null) return false
  if (source.ref.domain !== 'PREPARATION') return check.preparation === null && check.expression === null
  const same = (value: Schema['PrepareSourceCheck']) => value.itemId === source.ref.id && value.versionId === source.ref.versionId && value.kind === source.kind
  if (source.kind !== 'EXPRESSION') return check.expression === null && preparationValid(check.preparation) && same(check.preparation!)
  const e = check.expression
  return check.preparation === null && Boolean(e && fields(e, ['expression', 'factChecks']) && preparationValid(e.expression) && same(e.expression)
    && Array.isArray(e.factChecks) && e.factChecks.length >= 1 && e.factChecks.length <= 50 && e.factChecks.every(f => preparationValid(f) && f.kind === 'FACT')
    && new Set(e.factChecks.map(f => f.versionId)).size === e.factChecks.length)
}
export function aiSourceQaInputValid(input: AiSourceQaInput): boolean {
  try {
    if (!fields(input, ['question', 'sources', 'checks']) || !Array.isArray(input.sources) || !Array.isArray(input.checks)
      || input.sources.length !== input.checks.length || !aiSourceQaRequestValid(aiSourceQaRequest(input))) return false
    return input.sources.every((source, index) => source.sourceKey === `s${index + 1}` && sourceValid(source) && checkValid(source, input.checks[index]!))
      && new TextEncoder().encode(JSON.stringify({ question: input.question, sources: input.sources })).length <= 32768
  } catch { return false }
}
export function aiSourceQaRequest(input: AiSourceQaInput): AiSourceQaPreviewRequest {
  return { question: input.question, selections: input.sources.map(source => ({ source: source.ref,
    ranges: source.fragments.map(({ field, start, end }) => ({ field, start, end })) })) }
}
export function aiSourceQaManifestValid(manifest: AiManifest): boolean {
  try {
    const config = manifest.configuration
    return fields(manifest, ['purpose', 'instruction', 'sources', 'configuration', 'inputTokenAllowance', 'reservedMicros', 'promptVersion', 'sourceQa'], [...manifestTails, 'materialChecks', 'knowledgeMaterialChecks'])
      && manifest.purpose === 'SOURCE_QA' && manifest.promptVersion === 'source-qa-1' && manifest.instruction === ''
      && Array.isArray(manifest.sources) && manifest.sources.length === 0 && manifestTails.every(key => manifest[key] == null)
      && fields(config, ['enabled', 'configured', 'protocol', 'provider', 'endpoint', 'model', 'configVersion', 'priceVersion', 'currency', 'inputPriceMicrosPerMillion',
        'outputPriceMicrosPerMillion', 'dailyBudgetMicros', 'maxOutputTokens', 'timeoutSeconds', 'maxInputBytes', 'maxAttempts', 'maxTokensField', 'jsonMode'], ['streaming'])
      && config.enabled === true && config.configured === true && ['CHAT_COMPLETIONS', 'RESPONSES'].includes(config.protocol)
      && [config.provider, config.endpoint, config.model, config.configVersion, config.priceVersion].every(value => aiSourceQaTextValid(value, 4000, true))
      && /^[A-Z]{3}$/.test(config.currency) && integer(config.inputPriceMicrosPerMillion) && integer(config.outputPriceMicrosPerMillion)
      && integer(config.dailyBudgetMicros) && integer(config.maxOutputTokens, 1) && integer(config.timeoutSeconds, 1) && integer(config.maxInputBytes, 1, 32768)
      && integer(config.maxAttempts, 1, 3) && typeof config.jsonMode === 'boolean' && (config.streaming == null || config.streaming === true)
      && (config.protocol === 'RESPONSES' ? config.maxTokensField === 'max_output_tokens' : ['max_tokens', 'max_completion_tokens'].includes(config.maxTokensField))
      && integer(manifest.inputTokenAllowance, 1) && integer(manifest.reservedMicros)
      && Boolean(manifest.sourceQa && aiSourceQaInputValid(manifest.sourceQa)) && aiMaterialChecksMatch(manifest) && aiKnowledgeMaterialChecksMatch(manifest)
  } catch { return false }
}
export function aiSourceQaRequestMatches(manifest: AiManifest, request: AiSourceQaPreviewRequest): boolean {
  return aiSourceQaManifestValid(manifest) && aiMaterialChecksMatch(manifest, true) && aiKnowledgeMaterialChecksMatch(manifest, true)
    && JSON.stringify(aiSourceQaRequest(manifest.sourceQa!)) === JSON.stringify(request)
}
export function aiSourceQaCitationFragment(citation: AiSourceQaCitation, input: AiSourceQaInput) {
  if (!fields(citation, ['sourceKey', 'field', 'start', 'end', 'quote']) || !points(citation.quote, 500) || !integer(citation.start, 0, 2147483647)
    || !integer(citation.end, citation.start + 1, 2147483647)) return undefined
  const source = input.sources.find(value => value.sourceKey === citation.sourceKey)
  const fragment = source?.fragments.find(value => value.field === citation.field && citation.start >= value.start && citation.end <= value.end
    && value.text.slice(citation.start - value.start, citation.end - value.start) === citation.quote)
  return source && fragment ? { source, fragment } : undefined
}
export const aiSourceQaValidationNotes = {
  SUPPORTED: 'AI 未审来源回答，结构与本次所选片段的原文位置已核对；保留各来源身份、责任、条件、决定和局限。不证明引用蕴含回答、技术正确、经历真实或用户能力，请结合确切原文审阅。',
  INSUFFICIENT: 'AI 未审来源回答：模型认为本次明确选择的片段不足以支持回答；已核对结构，不证明资料完整或结论正确，不会自动扩大检索、改用通用解释或再次调用模型。',
} as const
export function aiSourceQaAnswerValid(answer: AiSourceQaAnswer, input: AiSourceQaInput): boolean {
  try {
    if (!fields(answer, ['state', 'answer', 'limitations', 'citations', 'validationNote']) || !['SUPPORTED', 'INSUFFICIENT'].includes(answer.state)
      || answer.validationNote !== aiSourceQaValidationNotes[answer.state] || !points(answer.answer, 12000) || !points(answer.limitations, 2000) || !Array.isArray(answer.citations)
      || (answer.state === 'SUPPORTED' ? answer.citations.length < 1 || answer.citations.length > 10 : answer.citations.length !== 0)) return false
    const keys = new Set<string>()
    return answer.citations.every(citation => {
      const key = JSON.stringify([citation.sourceKey, citation.field, citation.start, citation.end])
      if (!aiSourceQaCitationFragment(citation, input) || keys.has(key)) return false
      keys.add(key); return true
    }) && [answer.answer, answer.limitations, ...answer.citations.map(c => c.quote)].reduce((sum, value) => sum + Array.from(value).length, 0) <= 24000
  } catch { return false }
}
export function aiSourceQaTaskValid(value: AiTask): boolean {
  try {
    if (value?.task?.purpose !== 'SOURCE_QA') return value?.manifest?.sourceQa == null && value?.sourceQaAnswer == null && value?.sourceQaManifestHash == null && value?.manifest?.purpose !== 'SOURCE_QA'
    if (!fields(value, ['task', 'manifest', 'attempts', 'candidate', 'sourcesCurrent', 'sourceQaManifestHash'], [...taskTails, 'sourceQaAnswer'])
      || !aiSourceQaManifestValid(value.manifest) || !hash(value.sourceQaManifestHash) || typeof value.sourcesCurrent !== 'boolean' || taskTails.some(key => value[key] != null)) return false
    const t = value.task
    if (!fields(t, ['id', 'purpose', 'status', 'revision', 'title', 'provider', 'model', 'currency', 'attemptCount', 'createdAt', 'updatedAt', 'errorCode'])
      || !uuid(t.id) || !statuses.includes(t.status) || !integer(t.revision) || !aiSourceQaTextValid(t.title, 400, true)
      || t.title !== Array.from(value.manifest.sourceQa!.question).slice(0, 200).join('')
      || t.provider !== value.manifest.configuration.provider || t.model !== value.manifest.configuration.model || t.currency !== value.manifest.configuration.currency
      || !integer(t.attemptCount, 1, 3) || !timestamp(t.createdAt) || !timestamp(t.updatedAt) || t.errorCode !== null && !aiSourceQaTextValid(t.errorCode, 2000, true)
      || !Array.isArray(value.attempts) || value.attempts.length !== t.attemptCount || value.attempts.some(a => !uuid(a.id) || !integer(a.number, 1, t.attemptCount)
        || !statuses.includes(a.status) || !fees.includes(a.feeStatus) || !integer(a.reservedMicros) || a.accountedMicros !== null && !integer(a.accountedMicros))
      || new Set(value.attempts.map(a => a.id)).size !== value.attempts.length || new Set(value.attempts.map(a => a.number)).size !== value.attempts.length) return false
    return t.status === 'SUCCEEDED' ? Boolean(value.sourceQaAnswer && aiSourceQaAnswerValid(value.sourceQaAnswer, value.manifest.sourceQa!)) : value.sourceQaAnswer == null
  } catch { return false }
}
export function aiSourceQaSourceRoute(source: AiSourceQaSource): RouteLocationRaw | undefined {
  const r = source.ref
  if (!aiSourceQaRefValid(r)) return undefined
  if (r.domain === 'KNOWLEDGE') return { name: 'knowledge', query: { item: r.id, version: r.versionId } }
  if (r.domain === 'PREPARATION') return { name: 'prepare', query: { kind: source.kind, item: r.id, version: r.versionId } }
  if (r.domain === 'QUESTION') return { name: 'bank', query: { item: r.id, version: r.versionId } }
  if (r.domain === 'RECOLLECTION') return { name: 'recollections', query: { item: r.id, versionId: r.versionId } }
  if (r.domain === 'MATERIAL_EXCERPT' && source.material) return { name: 'materials', query: { attachment: source.material.attachmentId, excerpt: r.id, run: source.material.runId ?? undefined } }
  const a = source.training?.answer
  if (!a) return undefined
  if (r.domain === 'TRAINING_ANSWER') return { name: 'training', query: { item: a.sessionId, question: a.questionId, answer: a.answerId } }
  if (r.domain === 'REVIEW_FEEDBACK') return { name: 'review', query: { ...a, feedbackId: r.id, feedbackRevision: r.revision! } }
  if (r.domain === 'AI_FEEDBACK') return { name: 'review', query: { ...a, taskId: r.id, observationId: r.feedbackId!, aiAttemptId: r.versionId, aiReportHash: r.reportHash! } }
  return undefined
}
