import type { Schema } from './client'
import type { AiCitation, AiManifest, AiSource, AiTask } from './ai'
import { aiKnowledgeMaterialChecksMatch } from './ai'
import type { PreparationItem, Requirement } from './preparation'
import { dimensionOptions } from './preparation'
import { aiFactDraftTaskValid } from './aiFactDraft'

export type AiJdRequirementsPreviewRequest = Schema['AiJdRequirementsPreviewRequest']
export type AiJdRequirementsCreateRequest = Schema['AiJdRequirementsCreateRequest']
export type AiJdRequirementsSuggestion = Schema['AiJdRequirementsSuggestion']
export type AiJdRequirementsCandidate = Schema['AiJdRequirementsCandidate']
export type AiJdRequirementsSelection = Schema['AiJdRequirementsSelection']
export type AiJdRequirementsAdopt = Schema['AiJdRequirementsAdopt']
export type AiJdRequirementsRequirementLink = Schema['AiJdRequirementsRequirementLink']
export type AiJdRequirementsDecision = Schema['AiJdRequirementsDecision']
export type AiJdRequirementsOrigin = Schema['AiJdRequirementsOrigin']
export type AiJdRequirementsOriginPage = Schema['AiJdRequirementsOriginPage']

export const aiJdRequirementsValidationNote: AiJdRequirementsCandidate['validationNote'] = 'AI 未审岗位要求候选；引用仅核对所选 JD 原文位置，考察项与优先级仍需人工确认，不证明岗位实际考核、用户能力或全部要求已覆盖。'
export const aiJdRequirementsPaths = {
  preview: '/ai/jd-requirements/preview', create: '/ai/jd-requirements/tasks',
  adopt: (taskId: string) => `/ai/tasks/${encodeURIComponent(taskId)}/adopt-requirements`,
  origins: (targetId: string, versionId: string, before?: number) =>
    `/ai/targets/${encodeURIComponent(targetId)}/versions/${encodeURIComponent(versionId)}/requirement-origins?limit=20${before === undefined ? '' : `&before=${before}`}`,
} as const
const otherManifestFields = ['training', 'evaluation', 'contextChecks', 'materialQa', 'materialDraftSources', 'expressionType',
  'preparationPlan', 'trainingDraft', 'expressionChecks', 'comparisonInput', 'comparisonChecks', 'reviewDraft', 'cardDraftType', 'factDraft', 'relationEvidence', 'relationAiFeedback', 'materialChecks'] as const
const otherTaskFields = ['decision', 'questionCandidate', 'questionDecision', 'followUp', 'evaluation', 'evaluationReport',
  'materialAnswer', 'expressionCandidate', 'expressionDecision', 'preparationPlanCandidate', 'preparationPlanDecision',
  'relationCandidate', 'relationDecisions', 'comparison', 'comparisonCandidate', 'cardCandidate', 'cardDecision', 'factCandidate', 'factDecision', 'rewrite', 'rewriteCandidate'] as const
const statuses = ['QUEUED', 'RUNNING', 'VALIDATING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCEL_REQUESTED', 'CANCELED']
const feeStatuses = ['RESERVED', 'REPORTED', 'UNKNOWN', 'RELEASED', 'RECONCILED']
const fields = (value: unknown, required: readonly string[], optional: readonly string[] = []): boolean => Boolean(value && typeof value === 'object'
  && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const text = (value: unknown, maximum: number, required = true): value is string => typeof value === 'string' && value.length <= maximum
  && !/[\u0000\uD800-\uDFFF]/u.test(value) && (!required || /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value))
const existingText = (value: unknown, maximum: number, required = true): value is string => typeof value === 'string' && value.length <= maximum
  && (!required || /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value))
const sourceTitleValid = (value: unknown) => text(value, 200, false) && existingText(value, 200)
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const dimensionsValid = (value: unknown): value is string[] => Array.isArray(value) && value.length >= 1 && value.length <= 6
  && value.every(item => typeof item === 'string' && dimensionOptions.some(option => option.value === item)) && new Set(value).size === value.length
const sourceValues = (source: AiSource) => [source.kind, source.itemId, source.versionId, source.number, source.title, source.body]
// 既有手工要求沿用准备模块的原验证边界；追加候选的 UTF-16 与 Unicode 规则只约束本次新增项。
const requirementValid = (value: Requirement) => fields(value, ['id', 'text', 'dimensions', 'priority']) && uuid(value.id)
  && existingText(value.text, 2000) && dimensionsValid(value.dimensions) && integer(value.priority, 1, 5)
const citationShapeValid = (value: AiCitation) => fields(value, ['itemId', 'versionId', 'start', 'end', 'quote'])
  && uuid(value.itemId) && uuid(value.versionId) && integer(value.start) && integer(value.end) && value.end > value.start
  && text(value.quote, 500) && value.end - value.start === value.quote.length

export function aiJdRequirementsRequestValid(request: AiJdRequirementsPreviewRequest): boolean {
  return fields(request, ['targetItemId', 'targetVersionId', 'instruction']) && uuid(request.targetItemId) && uuid(request.targetVersionId)
    && text(request.instruction, 1000, false)
}
export function aiJdRequirementsSourceValid(source: AiSource): boolean {
  return fields(source, ['itemId', 'versionId', 'number', 'kind', 'title', 'body']) && source.kind === 'TARGET'
    && uuid(source.itemId) && uuid(source.versionId) && integer(source.number, 1, 2147483647) && sourceTitleValid(source.title) && text(source.body, 50000)
}
export function aiJdRequirementsManifestMatches(manifest: AiManifest): boolean {
  try {
    if (!fields(manifest, ['purpose', 'instruction', 'sources', 'configuration', 'inputTokenAllowance', 'reservedMicros', 'promptVersion', 'preparationChecks'], [...otherManifestFields, 'knowledgeMaterialChecks'])
      || manifest.purpose !== 'JD_REQUIREMENT_DRAFT' || manifest.promptVersion !== 'jd-requirements-1' || !text(manifest.instruction, 1000, false)
      || !Array.isArray(manifest.sources) || manifest.sources.length !== 1 || !aiJdRequirementsSourceValid(manifest.sources[0]!)
      || otherManifestFields.some(key => manifest[key] != null) || !Array.isArray(manifest.preparationChecks) || manifest.preparationChecks.length !== 1
      || !aiKnowledgeMaterialChecksMatch(manifest)
      || !manifest.configuration || typeof manifest.configuration !== 'object' || Array.isArray(manifest.configuration)
      || !integer(manifest.inputTokenAllowance, 1) || !integer(manifest.reservedMicros)) return false
    const source = manifest.sources[0]!, check = manifest.preparationChecks[0]!
    return fields(check, ['kind', 'itemId', 'versionId', 'state', 'sourceGeneration', 'projectGeneration']) && check.kind === 'TARGET'
      && check.itemId === source.itemId && check.versionId === source.versionId && check.state === 'CURRENT'
      && integer(check.sourceGeneration) && check.projectGeneration === null
  } catch { return false }
}
export function aiJdRequirementsSourcesMatch(manifest: AiManifest, request: AiJdRequirementsPreviewRequest, selected: AiSource): boolean {
  return aiJdRequirementsManifestMatches(manifest) && aiJdRequirementsRequestValid(request) && aiJdRequirementsSourceValid(selected)
    && manifest.instruction === request.instruction && selected.itemId === request.targetItemId && selected.versionId === request.targetVersionId
    && JSON.stringify(sourceValues(manifest.sources[0]!)) === JSON.stringify(sourceValues(selected))
}

export function aiJdRequirementsCitationValid(citation: AiCitation, manifest: AiManifest): boolean {
  try {
    if (!aiJdRequirementsManifestMatches(manifest) || !citationShapeValid(citation)) return false
    const source = manifest.sources[0]!
    if (citation.itemId !== source.itemId || citation.versionId !== source.versionId || citation.end > source.body.length) return false
    const split = (position: number) => position > 0 && position < source.body.length
      && /[\uD800-\uDBFF]/.test(source.body.charAt(position - 1)) && /[\uDC00-\uDFFF]/.test(source.body.charAt(position))
    return !split(citation.start) && !split(citation.end) && source.body.slice(citation.start, citation.end) === citation.quote
  } catch { return false }
}
function candidateShapeValid(candidate: AiJdRequirementsCandidate): boolean {
  return fields(candidate, ['requirements', 'limitations', 'validationNote']) && text(candidate.limitations, 2000)
    && candidate.validationNote === aiJdRequirementsValidationNote && Array.isArray(candidate.requirements) && candidate.requirements.length <= 20
    && candidate.requirements.every((suggestion, index) => fields(suggestion, ['number', 'text', 'dimensions', 'priority', 'citations'])
      && suggestion.number === index + 1 && text(suggestion.text, 2000) && dimensionsValid(suggestion.dimensions) && integer(suggestion.priority, 1, 5)
      && Array.isArray(suggestion.citations) && suggestion.citations.length >= 1 && suggestion.citations.length <= 5
      && suggestion.citations.every(citationShapeValid)
      && new Set(suggestion.citations.map(citation => JSON.stringify([citation.itemId, citation.versionId, citation.start, citation.end]))).size === suggestion.citations.length)
}
export function aiJdRequirementsCandidateValid(candidate: AiJdRequirementsCandidate, manifest: AiManifest): boolean {
  try { return aiJdRequirementsManifestMatches(manifest) && candidateShapeValid(candidate)
    && candidate.requirements.every(suggestion => suggestion.citations.every(citation => aiJdRequirementsCitationValid(citation, manifest))) } catch { return false }
}
export function aiJdRequirementsSelectionProblem(requirements: AiJdRequirementsSelection[], candidate: AiJdRequirementsCandidate, existingCount?: number): string {
  try {
    if (!candidateShapeValid(candidate)) return '原候选结构不完整，请重新读取任务。'
    if (!Array.isArray(requirements) || requirements.length < 1 || requirements.length > 20) return '请明确选择 1–20 项候选要求后采用。'
    if (existingCount !== undefined && (!integer(existingCount, 0, 50) || existingCount + requirements.length > 50)) return '追加后目标要求最多 50 项，请缩小本次选择。'
    if (requirements.some(item => !fields(item, ['candidateNumber', 'text', 'dimensions', 'priority'])
      || !integer(item.candidateNumber, 1, 20) || !candidate.requirements.some(source => source.number === item.candidateNumber))
      || new Set(requirements.map(item => item.candidateNumber)).size !== requirements.length) return '所选编号必须属于本次候选且不能重复，不能新增模型未提供的编号。'
    if (requirements.some(item => !text(item.text, 2000))) return '每项最终要求需为 1–2000 个 UTF-16 码元，不能全空白或含不完整字符。'
    if (requirements.some(item => !dimensionsValid(item.dimensions) || !integer(item.priority, 1, 5))) return '每项要求需选择 1–6 个不重复的有效维度，并填写 1–5 的整数优先级。'
    return ''
  } catch { return '所选要求结构无法读取，请核对原候选和最终改写。' }
}
export function aiJdRequirementsAdoptValid(request: AiJdRequirementsAdopt, candidate: AiJdRequirementsCandidate, existingCount?: number): boolean {
  return fields(request, ['expectedRevision', 'expectedTargetRevision', 'requirements', 'confirmed']) && integer(request.expectedRevision)
    && integer(request.expectedTargetRevision) && request.confirmed === true && !aiJdRequirementsSelectionProblem(request.requirements, candidate, existingCount)
}

export function aiJdRequirementsTargetMatches(target: PreparationItem, manifest: AiManifest): boolean {
  try {
    if (!aiJdRequirementsManifestMatches(manifest) || !fields(target, ['id', 'kind', 'projectId', 'status', 'revision', 'verification', 'favorite',
      'createdAt', 'updatedAt', 'currentVersion', 'references', 'reviewRequired']) || target.kind !== 'TARGET' || target.status !== 'ACTIVE'
      || target.projectId !== null || target.verification !== null || !integer(target.revision) || typeof target.favorite !== 'boolean'
      || typeof target.reviewRequired !== 'boolean' || !timestamp(target.createdAt) || !timestamp(target.updatedAt)) return false
    const source = manifest.sources[0]!, version = target.currentVersion, content = version.content
    if (target.id !== source.itemId || !fields(version, ['id', 'number', 'createdAt', 'content']) || version.id !== source.versionId
      || version.number !== source.number || !timestamp(version.createdAt) || !fields(content, ['title', 'company', 'jd', 'sourceUrl', 'capturedOn', 'weeklyMinutes', 'requirements', 'links'])
      || content.title !== source.title || content.jd !== source.body || !existingText(content.company, 200, false) || !existingText(content.sourceUrl, 2000, false)
      || content.weeklyMinutes !== null && !integer(content.weeklyMinutes, 0, 10080)
      || content.capturedOn !== null && (typeof content.capturedOn !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(content.capturedOn)
        || new Date(`${content.capturedOn}T00:00:00Z`).toISOString().slice(0, 10) !== content.capturedOn)
      || !Array.isArray(content.requirements) || content.requirements.length > 50 || !content.requirements.every(requirementValid)
      || new Set(content.requirements.map(item => item.id)).size !== content.requirements.length
      || !Array.isArray(content.links) || content.links.length > 100 || !content.links.every(link => fields(link, ['kind', 'itemId', 'versionId'])
        && ['PROJECT', 'FACT', 'KNOWLEDGE'].includes(link.kind) && uuid(link.itemId) && uuid(link.versionId))
      || new Set(content.links.map(link => JSON.stringify([link.kind, link.itemId, link.versionId]))).size !== content.links.length
      || !Array.isArray(target.references) || target.references.length !== content.links.length) return false
    if (content.sourceUrl) {
      const url = new URL(content.sourceUrl)
      if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password) return false
    }
    // 普通目标详情不提供授权代次；这里只核对当前版本与 CAS 基线，代次由服务端采用时复核。
    // 引用查询按来源身份排序，与正文 links 的用户顺序无关，必须比较完整集合。
    const references = new Set<string>()
    return target.references.every(reference => {
      const key = JSON.stringify([reference.kind, reference.itemId, reference.versionId])
      if (!fields(reference, ['kind', 'itemId', 'versionId', 'title', 'number', 'state']) || references.has(key)
        || !content.links.some(link => reference.kind === link.kind && reference.itemId === link.itemId && reference.versionId === link.versionId)
        || typeof reference.title !== 'string' || !integer(reference.number, 1, 2147483647)
        || !['CURRENT', 'SUPERSEDED', 'WITHDRAWN', 'ARCHIVED', 'UNVERIFIED'].includes(reference.state)) return false
      references.add(key)
      return true
    })
  } catch { return false }
}

function requirementLinksValid(links: AiJdRequirementsRequirementLink[], minimum = 1): boolean {
  return Array.isArray(links) && links.length >= minimum && links.length <= 20
    && links.every(link => fields(link, ['candidateNumber', 'requirementId']) && integer(link.candidateNumber, 1, 20) && uuid(link.requirementId))
    && new Set(links.map(link => link.candidateNumber)).size === links.length && new Set(links.map(link => link.requirementId)).size === links.length
}
export function aiJdRequirementsDecisionValid(decision: AiJdRequirementsDecision, manifest?: AiManifest, candidate?: AiJdRequirementsCandidate): boolean {
  try {
    if (!fields(decision, ['status', 'targetItemId', 'targetVersionId', 'requirements', 'createdAt']) || !timestamp(decision.createdAt)) return false
    if (decision.status === 'IGNORED') return decision.targetItemId === null && decision.targetVersionId === null
      && Array.isArray(decision.requirements) && decision.requirements.length === 0
    if (decision.status !== 'ADOPTED' || !uuid(decision.targetItemId) || !uuid(decision.targetVersionId) || !requirementLinksValid(decision.requirements)) return false
    return (manifest === undefined || aiJdRequirementsManifestMatches(manifest) && decision.targetItemId === manifest.sources[0]!.itemId
      && decision.targetVersionId !== manifest.sources[0]!.versionId)
      && (candidate === undefined || candidateShapeValid(candidate) && decision.requirements.every(link => candidate.requirements.some(item => item.number === link.candidateNumber)))
  } catch { return false }
}
export function aiJdRequirementsTaskValid(task: AiTask): boolean {
  try {
    if (!task?.task || !task.manifest || !aiFactDraftTaskValid(task)) return false
    const jd = task.task.purpose === 'JD_REQUIREMENT_DRAFT' || task.manifest.purpose === 'JD_REQUIREMENT_DRAFT' || task.manifest.promptVersion === 'jd-requirements-1'
    if (!jd) return task.jdRequirementsCandidate == null && task.jdRequirementsDecision == null
    if (!fields(task, ['task', 'manifest', 'attempts', 'candidate', 'evaluationDecisions', 'sourcesCurrent', 'jdRequirementsCandidate', 'jdRequirementsDecision'], otherTaskFields)
      || task.task.purpose !== 'JD_REQUIREMENT_DRAFT' || !uuid(task.task.id) || !integer(task.task.revision) || !statuses.includes(task.task.status)
      || !integer(task.task.attemptCount, 1, 3) || typeof task.sourcesCurrent !== 'boolean' || !aiJdRequirementsManifestMatches(task.manifest)
      || task.candidate !== null || task.evaluationDecisions !== null || otherTaskFields.some(key => task[key] != null)
      || !Array.isArray(task.attempts) || task.attempts.length !== task.task.attemptCount
      || task.attempts.some(attempt => !attempt || !uuid(attempt.id) || !integer(attempt.number, 1, task.task.attemptCount)
        || !statuses.includes(attempt.status) || !feeStatuses.includes(attempt.feeStatus) || !integer(attempt.reservedMicros)
        || attempt.accountedMicros !== null && !integer(attempt.accountedMicros))
      || new Set(task.attempts.map(attempt => attempt.id)).size !== task.attempts.length
      || new Set(task.attempts.map(attempt => attempt.number)).size !== task.attempts.length) return false
    if (task.task.status !== 'SUCCEEDED') return task.jdRequirementsCandidate === null && task.jdRequirementsDecision === null
    const decision = task.jdRequirementsDecision
    return Boolean(task.jdRequirementsCandidate && aiJdRequirementsCandidateValid(task.jdRequirementsCandidate, task.manifest))
      && (decision === null || decision != null && aiJdRequirementsDecisionValid(decision, task.manifest, task.jdRequirementsCandidate!))
  } catch { return false }
}

export function aiJdRequirementsOriginPageValid(value: AiJdRequirementsOriginPage, versionId: string, versionNumber: number, before?: number, targetItemId?: string): boolean {
  try {
    if (!uuid(versionId) || !integer(versionNumber, 1, 2147483647) || before !== undefined && !integer(before, 1, 2147483647)
      || targetItemId !== undefined && !uuid(targetItemId) || !fields(value, ['items', 'nextCursor']) || !Array.isArray(value.items) || value.items.length > 20
      || value.nextCursor !== null && !integer(value.nextCursor, 1, 2147483647)) return false
    const tasks = new Set<string>(), versions = new Set<string>(), requirements = new Set<string>()
    return value.items.every((origin, index) => {
      const source = origin.source
      if (!fields(origin, ['taskId', 'targetVersionId', 'targetVersionNumber', 'adoptedAt', 'direct', 'source', 'requirements'])
        || !uuid(origin.taskId) || tasks.has(origin.taskId) || !uuid(origin.targetVersionId) || versions.has(origin.targetVersionId)
        || !integer(origin.targetVersionNumber, 2, versionNumber) || before !== undefined && origin.targetVersionNumber >= before
        || index > 0 && origin.targetVersionNumber >= value.items[index - 1]!.targetVersionNumber
        || origin.direct !== (origin.targetVersionId === versionId) || origin.direct !== (origin.targetVersionNumber === versionNumber)
        || !timestamp(origin.adoptedAt) || !requirementLinksValid(origin.requirements)
        || origin.requirements.some(link => requirements.has(link.requirementId))
        || !fields(source, ['itemId', 'versionId', 'number', 'title', 'state', 'kind']) || source.kind !== 'TARGET'
        || !uuid(source.itemId) || source.itemId !== (targetItemId ?? value.items[0]?.source.itemId) || !uuid(source.versionId)
        || source.versionId === origin.targetVersionId || !integer(source.number, 1) || source.number !== origin.targetVersionNumber - 1
        || !sourceTitleValid(source.title) || !['CURRENT', 'SUPERSEDED', 'ARCHIVED', 'UNAVAILABLE'].includes(source.state)) return false
      tasks.add(origin.taskId); versions.add(origin.targetVersionId); origin.requirements.forEach(link => requirements.add(link.requirementId))
      return true
    }) && (value.nextCursor === null || value.items.length === 20 && value.nextCursor === value.items.at(-1)?.targetVersionNumber)
  } catch { return false }
}
