import type { Schema } from './client'
import { aiRewriteTaskMatches } from './aiRewrite'
import type { AiManifest, AiMaterialCitation, AiTask, AiTrainingDraftCitation, AiTrainingDraftFragment, MaterialAiExcerpt } from './ai'
import { aiMaterialChecksMatch } from './ai'
import type { MaterialExcerpt } from './material'
import type { PreparationItem } from './preparation'

export type AiFactDraftKind = Schema['AiFactDraftKind']
export type AiFactDraftTrainingSelection = Schema['AiFactDraftTrainingSelection']
export type AiFactDraftSelection = Schema['AiFactDraftSelection']
export type AiFactDraftPreviewRequest = Schema['AiFactDraftPreviewRequest']
export type AiFactDraftCreateRequest = Schema['AiFactDraftCreateRequest']
export type AiFactDraftTrainingInput = Schema['AiFactDraftTrainingInput']
export type AiFactDraftInput = Schema['AiFactDraftInput']
export type AiFactDraftContent = Schema['AiFactDraftContent']
export type AiFactDraftCandidate = Schema['AiFactDraftCandidate']
export type AiFactDraftAdopt = Schema['AiFactDraftAdopt']
export type AiFactDraftDecision = Schema['AiFactDraftDecision']
export type AiFactDraftTrainingOrigin = Schema['AiFactDraftTrainingOrigin']
export type AiFactDraftOrigin = Schema['AiFactDraftOrigin']
export type AiFactDraftOriginPage = Schema['AiFactDraftOriginPage']

export const aiFactDraftKindLabels: Record<AiFactDraftKind, string> = { MATERIAL: '资料摘录', TRAINING: '训练回答片段' }
export const aiFactDraftValidationNote: AiFactDraftCandidate['validationNote'] = 'AI 事实候选仅整理所选原文，不证明经历或责任；采用后仍须人工核实。'
export const aiFactDraftPaths = {
  preview: '/ai/fact-drafts/preview', create: '/ai/fact-drafts/tasks',
  adopt: (taskId: string) => `/ai/tasks/${encodeURIComponent(taskId)}/adopt-fact`,
  origins: (factId: string, versionId: string, before?: number) =>
    `/ai/facts/${encodeURIComponent(factId)}/versions/${encodeURIComponent(versionId)}/origins?limit=20${before === undefined ? '' : `&before=${before}`}`,
} as const
const otherManifestFields = ['training', 'evaluation', 'contextChecks', 'materialQa', 'preparationChecks', 'materialDraftSources',
  'expressionType', 'preparationPlan', 'trainingDraft', 'expressionChecks', 'comparisonInput', 'comparisonChecks', 'reviewDraft', 'cardDraftType', 'relationEvidence', 'relationAiFeedback', 'knowledgeMaterialChecks'] as const
const otherTaskFields = ['decision', 'questionCandidate', 'questionDecision', 'followUp', 'evaluation', 'evaluationReport', 'materialAnswer',
  'expressionCandidate', 'expressionDecision', 'preparationPlanCandidate', 'preparationPlanDecision', 'relationCandidate', 'relationDecisions',
  'comparison', 'comparisonCandidate', 'cardCandidate', 'cardDecision', 'jdRequirementsCandidate', 'jdRequirementsDecision', 'rewrite', 'rewriteCandidate'] as const
const answerFields = ['ANSWER_TEXT', 'ANSWER_NODE_LABEL', 'ANSWER_NODE_DESCRIPTION', 'ANSWER_EDGE_LABEL']
const answerConditions = ['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED']
const statuses = ['QUEUED', 'RUNNING', 'VALIDATING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCEL_REQUESTED', 'CANCELED']
const feeStatuses = ['RESERVED', 'REPORTED', 'UNKNOWN', 'RELEASED', 'RECONCILED']
const fields = (value: unknown, required: readonly string[], optional: readonly string[] = []): boolean => Boolean(value && typeof value === 'object'
  && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const text = (value: unknown, maximum: number, required = true): value is string => typeof value === 'string' && value.length <= maximum
  && !/[\u0000\uD800-\uDFFF]/u.test(value) && (!required || /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value))
// 固定旧来源沿用 Java isBlank；新候选与人工内容采用更严格的 Unicode 空白规则。
const sourceText = (value: unknown, maximum: number): value is string => text(value, maximum, false)
  && /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
const stripTag = (value: string) => value.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/gu, '')
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const fragmentValues = (items: AiTrainingDraftFragment[]) => items.map(item => [item.field, item.elementId, item.start, item.end, item.quote])
const materialValues = (item: MaterialAiExcerpt) => [item.excerptId, item.attachmentId, item.fileName, item.sha256, item.origin,
  item.runId, item.segmentId, item.locator, item.text, item.startOffset, item.endOffset]
const trainingValues = (item: AiFactDraftTrainingInput) => [item.sessionId, item.questionId, item.answerId, item.answerNumber, item.answerCondition, fragmentValues(item.fragments)]
const rangeMatches = (original: string, start: number, end: number, quote: string): boolean => {
  const split = (at: number) => at > 0 && at < original.length && /[\uD800-\uDBFF]/.test(original.charAt(at - 1)) && /[\uDC00-\uDFFF]/.test(original.charAt(at))
  return integer(start, 0, 2147483647) && integer(end, 1, 2147483647) && end > start && end <= original.length
    && !split(start) && !split(end) && original.slice(start, end) === quote
}
function fragmentsValid(items: AiTrainingDraftFragment[]): boolean {
  return Array.isArray(items) && items.length >= 1 && items.length <= 8 && items.every((item, index) =>
    fields(item, ['field', 'elementId', 'start', 'end', 'quote']) && answerFields.includes(item.field)
    && (item.field === 'ANSWER_TEXT' ? item.elementId === null : uuid(item.elementId))
    && integer(item.start, 0, 2147483647) && integer(item.end, 1, 2147483647) && item.end > item.start
    && sourceText(item.quote, 8000) && item.end - item.start === item.quote.length
    && !items.slice(0, index).some(other => other.field === item.field && other.elementId === item.elementId && other.start < item.end && item.start < other.end))
}
function materialValid(item: MaterialAiExcerpt): boolean {
  return fields(item, ['excerptId', 'attachmentId', 'fileName', 'sha256', 'origin', 'runId', 'segmentId', 'locator', 'text', 'startOffset', 'endOffset'])
    && uuid(item.excerptId) && uuid(item.attachmentId) && sourceText(item.fileName, 200) && typeof item.sha256 === 'string' && /^[0-9a-f]{64}$/.test(item.sha256)
    && sourceText(item.locator, 500) && sourceText(item.text, 10000)
    && (item.origin === 'MANUAL' ? item.runId === null && item.segmentId === null && item.startOffset === null && item.endOffset === null
      : item.origin === 'PARSED' && uuid(item.runId) && uuid(item.segmentId) && integer(item.startOffset, 0, 9999)
        && integer(item.endOffset, 1, 10000) && item.endOffset - item.startOffset === item.text.length)
}
export function aiFactDraftMaterialMatches(snapshot: MaterialAiExcerpt, selected: MaterialExcerpt): boolean {
  try {
    return materialValid(snapshot) && fields(selected, ['id', 'attachmentId', 'runId', 'segmentId', 'origin', 'locator', 'text', 'startOffset', 'endOffset', 'createdAt'])
      && timestamp(selected.createdAt) && snapshot.excerptId === selected.id && snapshot.attachmentId === selected.attachmentId
      && snapshot.origin === selected.origin && snapshot.runId === selected.runId && snapshot.segmentId === selected.segmentId
      && snapshot.locator === selected.locator && snapshot.text === selected.text && snapshot.startOffset === selected.startOffset && snapshot.endOffset === selected.endOffset
  } catch { return false }
}
export function aiFactDraftRequestValid(request: AiFactDraftPreviewRequest): boolean {
  try {
    if (!fields(request, ['instruction', 'source']) || !text(request.instruction, 1000, false)
      || !fields(request.source, ['kind', 'excerptIds', 'training']) || !Array.isArray(request.source.excerptIds)) return false
    const source = request.source
    if (source.kind === 'MATERIAL') return source.training === null && source.excerptIds.length >= 1 && source.excerptIds.length <= 5
      && source.excerptIds.every(uuid) && new Set(source.excerptIds).size === source.excerptIds.length
    const input = source.training
    return source.kind === 'TRAINING' && source.excerptIds.length === 0 && Boolean(input
      && fields(input, ['sessionId', 'questionId', 'answerId', 'fragments']) && uuid(input.sessionId) && uuid(input.questionId) && uuid(input.answerId) && fragmentsValid(input.fragments))
  } catch { return false }
}
export function aiFactDraftInputValid(input: AiFactDraftInput): boolean {
  try {
    if (!fields(input, ['kind', 'materials', 'training']) || !Array.isArray(input.materials)) return false
    if (input.kind === 'MATERIAL') return input.training === null && input.materials.length >= 1 && input.materials.length <= 5
      && input.materials.every(materialValid) && new Set(input.materials.map(item => item.excerptId)).size === input.materials.length
    const training = input.training
    return input.kind === 'TRAINING' && input.materials.length === 0 && Boolean(training
      && fields(training, ['sessionId', 'questionId', 'answerId', 'answerNumber', 'answerCondition', 'fragments'])
      && uuid(training.sessionId) && uuid(training.questionId) && uuid(training.answerId) && integer(training.answerNumber, 1, 100)
      && answerConditions.includes(training.answerCondition) && fragmentsValid(training.fragments))
  } catch { return false }
}
export function aiFactDraftInputFingerprint(input: AiFactDraftInput): string {
  return JSON.stringify([input.kind, input.materials.map(materialValues), input.training === null ? null : trainingValues(input.training)])
}
export function aiFactDraftManifestMatches(manifest: AiManifest): boolean {
  try {
    return fields(manifest, ['purpose', 'instruction', 'sources', 'configuration', 'inputTokenAllowance', 'reservedMicros', 'promptVersion', 'factDraft'], [...otherManifestFields, 'materialChecks'])
      && manifest.purpose === 'FACT_DRAFT' && manifest.promptVersion === 'fact-draft-1' && text(manifest.instruction, 1000, false)
      && Array.isArray(manifest.sources) && manifest.sources.length === 0 && otherManifestFields.every(key => manifest[key] == null)
      && Boolean(manifest.configuration && typeof manifest.configuration === 'object' && !Array.isArray(manifest.configuration))
      && integer(manifest.inputTokenAllowance, 1) && integer(manifest.reservedMicros) && Boolean(manifest.factDraft && aiFactDraftInputValid(manifest.factDraft))
      && aiMaterialChecksMatch(manifest)
  } catch { return false }
}
export function aiFactDraftSourcesMatch(manifest: AiManifest, request: AiFactDraftPreviewRequest): boolean {
  try {
    if (!aiFactDraftManifestMatches(manifest) || !aiMaterialChecksMatch(manifest, true)
      || !aiFactDraftRequestValid(request) || manifest.instruction !== request.instruction) return false
    const input = manifest.factDraft!, source = request.source
    if (input.kind !== source.kind) return false
    if (input.kind === 'MATERIAL') return JSON.stringify(input.materials.map(item => item.excerptId)) === JSON.stringify(source.excerptIds)
    const selected = source.training!, fixed = input.training!
    return selected.sessionId === fixed.sessionId && selected.questionId === fixed.questionId && selected.answerId === fixed.answerId
      && JSON.stringify(fragmentValues(selected.fragments)) === JSON.stringify(fragmentValues(fixed.fragments))
  } catch { return false }
}
export function aiFactDraftMaterialCitationValid(citation: AiMaterialCitation, manifest: AiManifest): boolean {
  try {
    if (!aiFactDraftManifestMatches(manifest) || manifest.factDraft!.kind !== 'MATERIAL'
      || !fields(citation, ['excerptId', 'start', 'end', 'quote']) || !uuid(citation.excerptId) || !text(citation.quote, 1000)) return false
    const source = manifest.factDraft!.materials.find(item => item.excerptId === citation.excerptId)
    return Boolean(source && rangeMatches(source.text, citation.start, citation.end, citation.quote))
  } catch { return false }
}
export function aiFactDraftTrainingCitationValid(citation: AiTrainingDraftCitation, manifest: AiManifest): boolean {
  try {
    if (!aiFactDraftManifestMatches(manifest) || manifest.factDraft!.kind !== 'TRAINING'
      || !fields(citation, ['field', 'elementId', 'start', 'end', 'quote']) || !answerFields.includes(citation.field)
      || (citation.field === 'ANSWER_TEXT' ? citation.elementId !== null : !uuid(citation.elementId))
      || !integer(citation.start, 0, 2147483647) || !integer(citation.end, 1, 2147483647) || !text(citation.quote, 1000)) return false
    return manifest.factDraft!.training!.fragments.some(fragment => fragment.field === citation.field && fragment.elementId === citation.elementId
      && citation.start >= fragment.start && citation.end <= fragment.end
      && rangeMatches(fragment.quote, citation.start - fragment.start, citation.end - fragment.start, citation.quote))
  } catch { return false }
}
export function aiFactDraftContentProblem(content: AiFactDraftContent): string {
  try {
    if (!fields(content, ['title', 'scenario', 'problem', 'action', 'result', 'technology', 'tags'])) return '事实内容须完整保留七个字段，不能提交责任、来源或核实状态。'
    if (!text(content.title, 200)) return '标题需为 1–200 个 UTF-16 码元，不能全空白或含不完整字符。'
    if (![content.scenario, content.problem, content.action, content.result].every(value => text(value, 10000, false)) || !text(content.technology, 2000, false)) return '场景、问题、动作、结果各最多 10000 个 UTF-16 码元，技术最多 2000；证据不足可留空。'
    if (!Array.isArray(content.tags) || content.tags.length > 20 || content.tags.some(value => !text(value, 40)) || new Set(content.tags.map(stripTag)).size !== content.tags.length) return '标签最多 20 个，每个 1–40 个 UTF-16 码元；去除首尾空白后不能重复。'
    return ''
  } catch { return '事实内容结构无法读取，请重新核对人工改稿。' }
}
export function aiFactDraftCandidateValid(candidate: AiFactDraftCandidate, manifest: AiManifest): boolean {
  try {
    if (!aiFactDraftManifestMatches(manifest) || !fields(candidate, ['content', 'materialCitations', 'trainingCitations', 'limitations', 'validationNote'])
      || aiFactDraftContentProblem(candidate.content) || !text(candidate.limitations, 2000) || candidate.validationNote !== aiFactDraftValidationNote
      || !Array.isArray(candidate.materialCitations) || !Array.isArray(candidate.trainingCitations)) return false
    if (manifest.factDraft!.kind === 'MATERIAL') return candidate.trainingCitations.length === 0 && candidate.materialCitations.length >= 1 && candidate.materialCitations.length <= 12
      && candidate.materialCitations.every(item => aiFactDraftMaterialCitationValid(item, manifest))
      && new Set(candidate.materialCitations.map(item => JSON.stringify([item.excerptId, item.start, item.end]))).size === candidate.materialCitations.length
    return candidate.materialCitations.length === 0 && candidate.trainingCitations.length >= 1 && candidate.trainingCitations.length <= 12
      && candidate.trainingCitations.every(item => aiFactDraftTrainingCitationValid(item, manifest))
      && new Set(candidate.trainingCitations.map(item => JSON.stringify([item.field, item.elementId, item.start, item.end]))).size === candidate.trainingCitations.length
  } catch { return false }
}
export function aiFactDraftAdoptValid(request: AiFactDraftAdopt): boolean {
  try { return fields(request, ['expectedRevision', 'projectId', 'expectedProjectVersionId', 'expectedProjectRevision', 'content', 'confirmed'])
    && integer(request.expectedRevision) && uuid(request.projectId) && uuid(request.expectedProjectVersionId) && integer(request.expectedProjectRevision)
    && request.confirmed === true && !aiFactDraftContentProblem(request.content) } catch { return false }
}
export function aiFactDraftProjectValid(item: PreparationItem, projectId?: string, versionId?: string): boolean {
  try {
    if (!fields(item, ['id', 'kind', 'projectId', 'status', 'revision', 'verification', 'favorite', 'createdAt', 'updatedAt', 'currentVersion', 'references', 'reviewRequired'])
      || item.kind !== 'PROJECT' || item.status !== 'ACTIVE' || !uuid(item.id) || projectId !== undefined && item.id !== projectId
      || item.projectId !== null || item.verification !== null || !integer(item.revision) || typeof item.favorite !== 'boolean'
      || typeof item.reviewRequired !== 'boolean' || !timestamp(item.createdAt) || !timestamp(item.updatedAt)
      || !Array.isArray(item.references) || item.references.length !== 0) return false
    const version = item.currentVersion, content = version.content
    const excerptIds = content.source.excerptIds === undefined ? [] : content.source.excerptIds
    return fields(version, ['id', 'number', 'createdAt', 'content']) && uuid(version.id) && (versionId === undefined || version.id === versionId)
      && integer(version.number, 1, 2147483647) && timestamp(version.createdAt)
      && fields(content, ['title', 'overview', 'technology', 'source']) && typeof content.title === 'string'
      && typeof content.overview === 'string' && typeof content.technology === 'string'
      && fields(content.source, ['title', 'url', 'note'], ['excerptIds']) && typeof content.source.title === 'string'
      && typeof content.source.url === 'string' && typeof content.source.note === 'string' && Array.isArray(excerptIds)
      && excerptIds.length <= 20 && excerptIds.every(uuid) && new Set(excerptIds).size === excerptIds.length
  } catch { return false }
}
export function aiFactDraftDecisionValid(decision: AiFactDraftDecision, expectedProjectId?: string): boolean {
  try {
    if (!fields(decision, ['status', 'targetProjectId', 'targetItemId', 'targetVersionId', 'createdAt']) || !timestamp(decision.createdAt)) return false
    if (decision.status === 'IGNORED') return decision.targetProjectId === null && decision.targetItemId === null && decision.targetVersionId === null
    return decision.status === 'ADOPTED' && uuid(decision.targetProjectId) && uuid(decision.targetItemId) && uuid(decision.targetVersionId)
      && (expectedProjectId === undefined || decision.targetProjectId === expectedProjectId)
  } catch { return false }
}
export function aiFactDraftTaskValid(task: AiTask): boolean {
  try {
    if (!task?.task || !task.manifest || !aiRewriteTaskMatches(task)) return false
    const fact = task.task.purpose === 'FACT_DRAFT' || task.manifest.purpose === 'FACT_DRAFT' || task.manifest.promptVersion === 'fact-draft-1'
    if (!fact) return task.manifest.factDraft == null && task.factCandidate == null && task.factDecision == null
    if (!fields(task, ['task', 'manifest', 'attempts', 'candidate', 'evaluationDecisions', 'sourcesCurrent', 'factCandidate', 'factDecision'], otherTaskFields)
      || task.task.purpose !== 'FACT_DRAFT' || !uuid(task.task.id) || !integer(task.task.revision) || !statuses.includes(task.task.status)
      || !integer(task.task.attemptCount, 1, 3) || typeof task.sourcesCurrent !== 'boolean' || !aiFactDraftManifestMatches(task.manifest)
      || task.candidate !== null || task.evaluationDecisions !== null || otherTaskFields.some(key => task[key] != null)
      || !Array.isArray(task.attempts) || task.attempts.length !== task.task.attemptCount
      || task.attempts.some(attempt => !attempt || !uuid(attempt.id) || !integer(attempt.number, 1, task.task.attemptCount)
        || !statuses.includes(attempt.status) || !feeStatuses.includes(attempt.feeStatus) || !integer(attempt.reservedMicros)
        || attempt.accountedMicros !== null && !integer(attempt.accountedMicros))
      || new Set(task.attempts.map(attempt => attempt.id)).size !== task.attempts.length
      || new Set(task.attempts.map(attempt => attempt.number)).size !== task.attempts.length) return false
    if (task.task.status !== 'SUCCEEDED') return task.factCandidate === null && task.factDecision === null
    const decision = task.factDecision
    return Boolean(task.factCandidate && aiFactDraftCandidateValid(task.factCandidate, task.manifest))
      && (decision === null || decision != null && aiFactDraftDecisionValid(decision))
  } catch { return false }
}
function materialOriginValid(source: AiFactDraftOrigin['materialSources'][number]): boolean {
  return fields(source, ['excerptId', 'attachmentId', 'fileName', 'sha256', 'origin', 'runId', 'segmentId', 'locator', 'startOffset', 'endOffset', 'state'])
    && uuid(source.excerptId) && uuid(source.attachmentId) && sourceText(source.fileName, 200) && typeof source.sha256 === 'string' && /^[0-9a-f]{64}$/.test(source.sha256)
    && sourceText(source.locator, 500) && ['CURRENT', 'UNAVAILABLE'].includes(source.state)
    && (source.origin === 'MANUAL' ? source.runId === null && source.segmentId === null && source.startOffset === null && source.endOffset === null
      : source.origin === 'PARSED' && uuid(source.runId) && uuid(source.segmentId) && integer(source.startOffset, 0, 9999)
        && integer(source.endOffset, 1, 10000) && source.endOffset > source.startOffset)
}
export function aiFactDraftOriginPageValid(page: AiFactDraftOriginPage, versionId: string, versionNumber: number, before?: number, factId?: string, projectId?: string): boolean {
  try {
    if (!uuid(versionId) || !integer(versionNumber, 1, 2147483647) || before !== undefined && !integer(before, 1, 2147483647)
      || factId !== undefined && !uuid(factId) || projectId !== undefined && !uuid(projectId)
      || !fields(page, ['items', 'nextCursor']) || !Array.isArray(page.items) || page.items.length > 20
      || page.nextCursor !== null && !integer(page.nextCursor, 1, 2147483647)) return false
    const tasks = new Set<string>(), versions = new Set<string>()
    return page.items.every((origin, index) => {
      if (!fields(origin, ['taskId', 'targetProjectId', 'targetItemId', 'targetVersionId', 'targetVersionNumber', 'adoptedAt', 'direct', 'sourceKind', 'materialSources', 'trainingSource'])
        || !uuid(origin.taskId) || tasks.has(origin.taskId) || !uuid(origin.targetProjectId) || !uuid(origin.targetItemId) || !uuid(origin.targetVersionId) || versions.has(origin.targetVersionId)
        || origin.targetItemId !== (factId ?? page.items[0]?.targetItemId) || origin.targetProjectId !== (projectId ?? page.items[0]?.targetProjectId)
        || origin.targetVersionNumber !== 1 || before !== undefined && origin.targetVersionNumber >= before
        || index > 0 && origin.targetVersionNumber >= page.items[index - 1]!.targetVersionNumber
        || origin.direct !== (origin.targetVersionId === versionId) || origin.direct !== (origin.targetVersionNumber === versionNumber)
        || !timestamp(origin.adoptedAt) || !Array.isArray(origin.materialSources)) return false
      tasks.add(origin.taskId); versions.add(origin.targetVersionId)
      if (origin.sourceKind === 'MATERIAL') return origin.trainingSource === null && origin.materialSources.length >= 1 && origin.materialSources.length <= 5
        && origin.materialSources.every(materialOriginValid) && new Set(origin.materialSources.map(source => source.excerptId)).size === origin.materialSources.length
      const source = origin.trainingSource
      return origin.sourceKind === 'TRAINING' && origin.materialSources.length === 0 && Boolean(source
        && fields(source, ['sessionId', 'questionId', 'answerId', 'answerNumber', 'answerCondition', 'state'])
        && uuid(source.sessionId) && uuid(source.questionId) && uuid(source.answerId) && integer(source.answerNumber, 1, 100)
        && answerConditions.includes(source.answerCondition) && ['CURRENT', 'UNAVAILABLE'].includes(source.state))
    }) && (page.nextCursor === null || page.items.length === 20 && page.nextCursor === page.items.at(-1)?.targetVersionNumber)
  } catch { return false }
}
