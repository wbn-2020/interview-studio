import type { Schema } from './client'
import type { AiCitation, AiManifest, AiOriginPage, AiSource, AiTask } from './ai'
import { aiKnowledgeMaterialChecksMatch } from './ai'
import type { MemoryContent } from './memory'
import { memoryContentProblem, memoryIdValid, memoryKindLabels } from './memory'
import { aiJdRequirementsTaskValid } from './aiJdRequirements'

export type AiCardDraftPreviewRequest = Schema['AiCardDraftPreviewRequest']
export type AiCardDraftCreateRequest = Schema['AiCardDraftCreateRequest']
export type AiCardDraftCandidate = Schema['AiCardDraftCandidate']
export type AiCardDraftAdopt = Schema['AiCardDraftAdopt']
export type AiCardDraftDecision = Schema['AiCardDraftDecision']

export const aiCardDraftKindLabels = memoryKindLabels
export const aiCardDraftValidationNote: AiCardDraftCandidate['validationNote'] = 'AI 未审回忆卡候选，结构、授权卡片类型与所选知识确切版本的原文区间已核对；不证明技术正确、引用充分支持参考或用户已经掌握。候选仅为单卡建议，采用前请核对题面、参考、出处与适用条件。'
export const aiCardDraftPaths = {
  preview: '/ai/card-drafts/preview', create: '/ai/card-drafts/tasks',
  adopt: (taskId: string) => `/ai/tasks/${encodeURIComponent(taskId)}/adopt-card`,
  origins: (cardId: string, versionId: string, before?: number) =>
    `/ai/memory-cards/${encodeURIComponent(cardId)}/versions/${encodeURIComponent(versionId)}/origins?limit=20${before === undefined ? '' : `&before=${before}`}`,
} as const
const specializedManifestFields = ['training', 'evaluation', 'contextChecks', 'materialQa', 'preparationChecks', 'materialDraftSources',
  'expressionType', 'preparationPlan', 'trainingDraft', 'expressionChecks', 'comparisonInput', 'comparisonChecks', 'reviewDraft', 'factDraft', 'relationEvidence', 'relationAiFeedback', 'materialChecks'] as const
const otherTaskFields = ['decision', 'questionCandidate', 'questionDecision', 'followUp', 'evaluation', 'evaluationReport',
  'materialAnswer', 'expressionCandidate', 'expressionDecision', 'preparationPlanCandidate', 'preparationPlanDecision',
  'relationCandidate', 'relationDecisions', 'comparison', 'comparisonCandidate', 'jdRequirementsCandidate', 'jdRequirementsDecision', 'factCandidate', 'factDecision', 'rewrite', 'rewriteCandidate'] as const
const statuses = ['QUEUED', 'RUNNING', 'VALIDATING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCEL_REQUESTED', 'CANCELED']
const feeStatuses = ['RESERVED', 'REPORTED', 'UNKNOWN', 'RELEASED', 'RECONCILED']
const fields = (value: unknown, required: readonly string[], optional: readonly string[] = []): boolean => Boolean(value && typeof value === 'object'
  && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const text = (value: unknown, maximum: number, required = true): value is string => typeof value === 'string' && value.length <= maximum
  && !/[\u0000\uD800-\uDFFF]/u.test(value) && (!required || /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value))
const codePointText = (value: unknown, maximum: number): value is string => text(value, maximum * 2) && Array.from(value).length <= maximum
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const sourceValues = (source: AiSource) => [source.itemId, source.versionId, source.number, source.kind, source.title, source.body]
const linksMatch = (left: AiCardDraftPreviewRequest['sources'], right: AiCardDraftPreviewRequest['sources']) => left.length === right.length
  && left.every((item, index) => fields(item, ['itemId', 'versionId']) && item.itemId === right[index]!.itemId && item.versionId === right[index]!.versionId)

export function aiCardDraftSourceValid(source: AiSource): boolean {
  return fields(source, ['itemId', 'versionId', 'number', 'kind', 'title', 'body']) && memoryIdValid(source.itemId) && memoryIdValid(source.versionId)
    && integer(source.number, 1, 2147483647) && ['NOTE', 'CONCEPT'].includes(source.kind) && text(source.title, 200) && text(source.body, 100000)
}
function sourcesValid(sources: AiSource[]): boolean {
  return Array.isArray(sources) && sources.length >= 1 && sources.length <= 5 && sources.every(aiCardDraftSourceValid)
    && new Set(sources.map(source => source.itemId)).size === sources.length && new Set(sources.map(source => source.versionId)).size === sources.length
}
export function aiCardDraftRequestValid(request: AiCardDraftPreviewRequest): boolean {
  try {
    return fields(request, ['kind', 'instruction', 'sources']) && Object.hasOwn(memoryKindLabels, request.kind) && text(request.instruction, 1000, false)
      && Array.isArray(request.sources) && request.sources.length >= 1 && request.sources.length <= 5
      && request.sources.every(source => fields(source, ['itemId', 'versionId']) && memoryIdValid(source.itemId) && memoryIdValid(source.versionId))
      && new Set(request.sources.map(source => source.itemId)).size === request.sources.length
      && new Set(request.sources.map(source => source.versionId)).size === request.sources.length
  } catch { return false }
}

export function aiCardDraftManifestMatches(manifest: AiManifest): boolean {
  try {
    return fields(manifest, ['purpose', 'instruction', 'sources', 'configuration', 'inputTokenAllowance', 'reservedMicros', 'promptVersion', 'cardDraftType'], [...specializedManifestFields, 'knowledgeMaterialChecks'])
      && manifest.purpose === 'MEMORY_CARD_DRAFT' && manifest.promptVersion === 'memory-card-draft-1'
      && manifest.cardDraftType != null && Object.hasOwn(memoryKindLabels, manifest.cardDraftType) && text(manifest.instruction, 1000, false)
      && sourcesValid(manifest.sources) && specializedManifestFields.every(key => manifest[key] == null) && aiKnowledgeMaterialChecksMatch(manifest)
      && Boolean(manifest.configuration && typeof manifest.configuration === 'object' && !Array.isArray(manifest.configuration))
      && integer(manifest.inputTokenAllowance, 1) && integer(manifest.reservedMicros)
  } catch { return false }
}
export function aiCardDraftSourcesMatch(manifest: AiManifest, request: AiCardDraftPreviewRequest, selected: AiSource[]): boolean {
  try {
    return aiCardDraftManifestMatches(manifest) && aiKnowledgeMaterialChecksMatch(manifest, true) && aiCardDraftRequestValid(request) && sourcesValid(selected)
      && manifest.cardDraftType === request.kind && manifest.instruction === request.instruction
      && linksMatch(request.sources, manifest.sources) && selected.length === manifest.sources.length
      && selected.every((source, index) => JSON.stringify(sourceValues(source)) === JSON.stringify(sourceValues(manifest.sources[index]!)))
  } catch { return false }
}

export function aiCardDraftCitationValid(citation: AiCitation, manifest: AiManifest): boolean {
  try {
    if (!aiCardDraftManifestMatches(manifest) || !fields(citation, ['itemId', 'versionId', 'start', 'end', 'quote'])
      || !memoryIdValid(citation.itemId) || !memoryIdValid(citation.versionId) || !integer(citation.start) || !integer(citation.end)
      || citation.end <= citation.start || !text(citation.quote, 1000)) return false
    const source = manifest.sources.find(item => item.itemId === citation.itemId && item.versionId === citation.versionId)
    if (!source || citation.end > source.body.length) return false
    const split = (position: number) => position > 0 && position < source.body.length
      && /[\uD800-\uDBFF]/.test(source.body.charAt(position - 1)) && /[\uDC00-\uDFFF]/.test(source.body.charAt(position))
    return !split(citation.start) && !split(citation.end) && source.body.slice(citation.start, citation.end) === citation.quote
  } catch { return false }
}
export function aiCardDraftCandidateValid(candidate: AiCardDraftCandidate, manifest: AiManifest): boolean {
  try {
    if (!aiCardDraftManifestMatches(manifest) || !fields(candidate, ['kind', 'title', 'prompt', 'reference', 'citations', 'validationNote'])
      || candidate.kind !== manifest.cardDraftType || !codePointText(candidate.title, 200) || !codePointText(candidate.prompt, 10000)
      || !codePointText(candidate.reference, 20000) || candidate.validationNote !== aiCardDraftValidationNote
      || !Array.isArray(candidate.citations) || candidate.citations.length < 1 || candidate.citations.length > 10) return false
    const positions = new Set<string>()
    return candidate.citations.every(citation => {
      if (!aiCardDraftCitationValid(citation, manifest)) return false
      const key = JSON.stringify([citation.itemId, citation.versionId, citation.start, citation.end])
      if (positions.has(key)) return false
      positions.add(key)
      return true
    })
  } catch { return false }
}

export function aiCardDraftContentProblem(content: MemoryContent, manifest: AiManifest): string {
  if (!aiCardDraftManifestMatches(manifest)) return '制卡清单不完整，请重新读取任务并核对来源。'
  const problem = memoryContentProblem(content)
  if (problem) return problem
  if (content.kind !== manifest.cardDraftType) return '最终卡片类型必须与本次授权的类型一致。'
  // 制卡来源完整保留原清单和顺序；相关概念独立选择，不据模型文本补造来源。
  if (!linksMatch(content.sources, manifest.sources)) return '卡片来源必须完整保留本次清单的确切版本和原顺序。'
  return ''
}
export function aiCardDraftDecisionValid(decision: AiCardDraftDecision): boolean {
  return fields(decision, ['status', 'targetCardId', 'targetVersionId', 'createdAt']) && timestamp(decision.createdAt)
    && (decision.status === 'IGNORED' ? decision.targetCardId === null && decision.targetVersionId === null
      : decision.status === 'ADOPTED' && memoryIdValid(decision.targetCardId) && memoryIdValid(decision.targetVersionId))
}

export function aiCardDraftTaskValid(task: AiTask): boolean {
  try {
    if (!task?.task || !task.manifest || !aiJdRequirementsTaskValid(task)) return false
    const card = task.task.purpose === 'MEMORY_CARD_DRAFT' || task.manifest.purpose === 'MEMORY_CARD_DRAFT'
      || task.manifest.promptVersion === 'memory-card-draft-1'
    if (!card) return task.manifest.cardDraftType == null && task.cardCandidate == null && task.cardDecision == null
    // 新用途必须完整通过专用校验；旧任务只兼容缺省的新增尾字段。
    if (!fields(task, ['task', 'manifest', 'attempts', 'candidate', 'evaluationDecisions', 'sourcesCurrent', 'cardCandidate', 'cardDecision'], otherTaskFields)
      || task.task.purpose !== 'MEMORY_CARD_DRAFT' || !memoryIdValid(task.task.id) || !integer(task.task.revision) || !statuses.includes(task.task.status)
      || !integer(task.task.attemptCount, 1, 3) || typeof task.sourcesCurrent !== 'boolean' || !aiCardDraftManifestMatches(task.manifest)
      || task.candidate !== null || task.evaluationDecisions !== null || otherTaskFields.some(key => task[key] != null)
      || !Array.isArray(task.attempts) || task.attempts.length !== task.task.attemptCount
      || task.attempts.some(attempt => !attempt || !memoryIdValid(attempt.id) || !integer(attempt.number, 1, task.task.attemptCount)
        || !statuses.includes(attempt.status) || !feeStatuses.includes(attempt.feeStatus) || !integer(attempt.reservedMicros)
        || attempt.accountedMicros !== null && !integer(attempt.accountedMicros))
      || new Set(task.attempts.map(attempt => attempt.id)).size !== task.attempts.length
      || new Set(task.attempts.map(attempt => attempt.number)).size !== task.attempts.length
      || task.cardDecision != null && !aiCardDraftDecisionValid(task.cardDecision)) return false
    return task.task.status === 'SUCCEEDED' ? Boolean(task.cardCandidate && aiCardDraftCandidateValid(task.cardCandidate, task.manifest))
      : task.cardCandidate === null && task.cardDecision === null
  } catch { return false }
}

export function aiCardDraftOriginPageValid(value: AiOriginPage, versionId: string, versionNumber: number, before?: number): boolean {
  try {
    if (!memoryIdValid(versionId) || !integer(versionNumber, 1, 1000) || before !== undefined && !integer(before, 1, 2147483647)
      || !fields(value, ['items', 'nextCursor']) || !Array.isArray(value.items) || value.items.length > 20
      || value.nextCursor !== null && !integer(value.nextCursor, 1, 1000)) return false
    const tasks = new Set<string>(), versions = new Set<string>()
    return value.items.every((origin, index) => {
      if (!fields(origin, ['taskId', 'targetVersionId', 'targetVersionNumber', 'adoptedAt', 'direct', 'sources'], ['materialSources', 'trainingSource', 'reviewSource'])
        || !memoryIdValid(origin.taskId) || tasks.has(origin.taskId) || !memoryIdValid(origin.targetVersionId) || versions.has(origin.targetVersionId)
        || !integer(origin.targetVersionNumber, 1, versionNumber) || before !== undefined && origin.targetVersionNumber >= before
        || index > 0 && origin.targetVersionNumber >= value.items[index - 1]!.targetVersionNumber
        || origin.direct !== (origin.targetVersionId === versionId) || origin.direct !== (origin.targetVersionNumber === versionNumber)
        || !timestamp(origin.adoptedAt) || !Array.isArray(origin.sources) || origin.sources.length < 1 || origin.sources.length > 5
        || origin.materialSources != null && (!Array.isArray(origin.materialSources) || origin.materialSources.length !== 0)
        || origin.trainingSource != null || origin.reviewSource != null) return false
      tasks.add(origin.taskId); versions.add(origin.targetVersionId)
      return origin.sources.every(source => fields(source, ['itemId', 'versionId', 'number', 'title', 'state', 'kind'])
        && memoryIdValid(source.itemId) && memoryIdValid(source.versionId) && integer(source.number, 1, 2147483647)
        && text(source.title, 200) && ['NOTE', 'CONCEPT'].includes(source.kind ?? '')
        && ['CURRENT', 'SUPERSEDED', 'ARCHIVED', 'UNAVAILABLE'].includes(source.state))
        && new Set(origin.sources.map(source => source.itemId)).size === origin.sources.length
        && new Set(origin.sources.map(source => source.versionId)).size === origin.sources.length
    }) && (value.nextCursor === null || value.items.length === 20 && value.nextCursor === value.items.at(-1)?.targetVersionNumber)
  } catch { return false }
}
