import type { Schema } from './client'
import type { AiManifest, AiMaterialCitation, AiRelationOrigin, AiRelationSuggestion, AiSource, AiTask, AiTrainingDraftCitation } from './ai'
import { aiCandidateManifestFingerprint, aiKnowledgeMaterialChecksMatch, aiMaterialChecksMatch, aiRelationManifestMatches, aiRelationTaskMatches, aiTrainingDraftRangeMatches } from './ai'
import type { AiFactDraftInput } from './aiFactDraft'
import { aiFactDraftInputFingerprint, aiFactDraftRequestValid } from './aiFactDraft'
import { relationEvidenceSnapshotValid } from './relationEvidence'

export type AiRelationEvidencePreviewRequest = Schema['AiRelationEvidencePreviewRequest']
export type AiRelationEvidenceCreateRequest = Schema['AiCreateRelationEvidenceDraft']
export const aiRelationEvidencePaths = { preview: '/ai/relation-evidence-drafts/preview', create: '/ai/relation-evidence-drafts/tasks' } as const
export const aiRelationEvidenceValidationNote = 'AI 未审关系候选，结构、所选概念端点及知识、材料摘录或训练片段的原文区间已核对；原答仍是用户当时的表达。不证明关系语义正确、原文充分支持、技术正确、经历真实或用户已经掌握。候选需逐项审阅，不会自动进入正式星图。'
const fields = (value: unknown, required: readonly string[], optional: readonly string[] = []): boolean => Boolean(value && typeof value === 'object'
  && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown, minimum = 0, maximum = 2147483647): value is number => typeof value === 'number'
  && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const text = (value: unknown, maximum: number, required = true): value is string => typeof value === 'string' && value.length <= maximum
  && !/[\u0000\uD800-\uDFFF]/u.test(value) && (!required || /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value))
const sourceText = (value: unknown, maximum: number): value is string => text(value, maximum, false)
  && /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
const sourceValues = (source: AiSource) => [source.itemId, source.versionId, source.number, source.kind, source.title, source.body]
const fragmentValues = (input: NonNullable<AiFactDraftInput['training']>['fragments']) => input.map(item => [item.field, item.elementId, item.start, item.end, item.quote])

export function aiRelationEvidenceKnowledgeSourceValid(value: unknown): value is AiSource {
  const source = value as AiSource
  return fields(source, ['itemId', 'versionId', 'number', 'kind', 'title', 'body']) && uuid(source.itemId) && uuid(source.versionId)
    && integer(source.number, 1) && ['NOTE', 'CONCEPT'].includes(source.kind) && sourceText(source.title, 200) && sourceText(source.body, 100000)
}
export function aiRelationEvidenceInputValid(value: unknown): value is AiFactDraftInput {
  // 解析摘录的绝对位置属于原片段，不能拿摘录自身的长度上限限制它。
  return relationEvidenceSnapshotValid(value)
}
export function aiRelationEvidenceInputFingerprint(input: AiFactDraftInput): string { return aiFactDraftInputFingerprint(input) }
export function aiRelationEvidenceRequestValid(value: unknown): value is AiRelationEvidencePreviewRequest {
  const request = value as AiRelationEvidencePreviewRequest
  return fields(request, ['instruction', 'sources', 'source']) && aiFactDraftRequestValid({ instruction: request.instruction, source: request.source })
    && Array.isArray(request.sources) && request.sources.length >= 2 && request.sources.length <= 5
    && request.sources.every(source => fields(source, ['itemId', 'versionId']) && uuid(source.itemId) && uuid(source.versionId))
    && new Set(request.sources.map(source => source.itemId)).size === request.sources.length
    && new Set(request.sources.map(source => source.versionId)).size === request.sources.length
}
export function aiRelationEvidenceRequestProblem(value: unknown): string {
  return aiRelationEvidenceRequestValid(value) ? '' : '请选择 2–5 个不同的知识版本，并提供 1–5 个材料摘录或同一次提交的 1–8 个回答片段；整理要求最多 1000 个 UTF-16 码元。'
}
export function aiRelationEvidenceManifestMatches(manifest: AiManifest): boolean {
  return aiRelationManifestMatches(manifest) && manifest.relationEvidence != null && manifest.promptVersion === 'relation-evidence-draft-1'
}
export function aiRelationEvidenceSourcesMatch(manifest: AiManifest, request: AiRelationEvidencePreviewRequest,
  selectedKnowledge: AiSource[], expectedInput: AiFactDraftInput): boolean {
  try {
    if (!aiRelationEvidenceManifestMatches(manifest) || !aiMaterialChecksMatch(manifest, true) || !aiKnowledgeMaterialChecksMatch(manifest, true)
      || !aiRelationEvidenceRequestValid(request) || !aiRelationEvidenceInputValid(expectedInput)
      || !Array.isArray(selectedKnowledge) || !selectedKnowledge.every(aiRelationEvidenceKnowledgeSourceValid)
      || manifest.instruction !== request.instruction || manifest.sources.length !== request.sources.length
      || JSON.stringify(manifest.sources.map(sourceValues)) !== JSON.stringify(selectedKnowledge.map(sourceValues))
      || manifest.sources.some((source, index) => source.itemId !== request.sources[index]!.itemId || source.versionId !== request.sources[index]!.versionId)
      || aiRelationEvidenceInputFingerprint(manifest.relationEvidence!) !== aiRelationEvidenceInputFingerprint(expectedInput)) return false
    const source = request.source, input = manifest.relationEvidence!
    if (source.kind !== input.kind) return false
    if (input.kind === 'MATERIAL') return JSON.stringify(input.materials.map(item => item.excerptId)) === JSON.stringify(source.excerptIds)
    const selected = source.training!, fixed = input.training!
    return selected.sessionId === fixed.sessionId && selected.questionId === fixed.questionId && selected.answerId === fixed.answerId
      && JSON.stringify(fragmentValues(selected.fragments)) === JSON.stringify(fragmentValues(fixed.fragments))
  } catch { return false }
}
export function aiRelationEvidenceTaskMatches(value: unknown, expectedManifest?: AiManifest): value is AiTask {
  return aiRelationTaskMatches(value) && aiRelationEvidenceManifestMatches(value.manifest)
    && (expectedManifest === undefined || aiRelationEvidenceManifestMatches(expectedManifest)
      && aiCandidateManifestFingerprint(value.manifest) === aiCandidateManifestFingerprint(expectedManifest))
}
export function aiRelationEvidenceMaterialCitationValid(value: unknown, input: AiFactDraftInput): value is AiMaterialCitation {
  const citation = value as AiMaterialCitation
  if (!aiRelationEvidenceInputValid(input) || input.kind !== 'MATERIAL' || !fields(citation, ['excerptId', 'start', 'end', 'quote'])
    || !uuid(citation.excerptId) || !integer(citation.start) || !integer(citation.end, 1) || !text(citation.quote, 1000)) return false
  const source = input.materials.find(item => item.excerptId === citation.excerptId)
  return Boolean(source && aiTrainingDraftRangeMatches(source.text, citation.start, citation.end, citation.quote))
}
export function aiRelationEvidenceTrainingCitationValid(value: unknown, input: AiFactDraftInput): value is AiTrainingDraftCitation {
  const citation = value as AiTrainingDraftCitation
  if (!aiRelationEvidenceInputValid(input) || input.kind !== 'TRAINING' || !fields(citation, ['field', 'elementId', 'start', 'end', 'quote'])
    || !['ANSWER_TEXT', 'ANSWER_NODE_LABEL', 'ANSWER_NODE_DESCRIPTION', 'ANSWER_EDGE_LABEL'].includes(citation.field)
    || (citation.field === 'ANSWER_TEXT' ? citation.elementId !== null : !uuid(citation.elementId))
    || !integer(citation.start) || !integer(citation.end, 1) || !text(citation.quote, 1000)) return false
  return input.training!.fragments.some(fragment => fragment.field === citation.field && fragment.elementId === citation.elementId
    && citation.start >= fragment.start && citation.end <= fragment.end
    && aiTrainingDraftRangeMatches(fragment.quote, citation.start - fragment.start, citation.end - fragment.start, citation.quote))
}
export function aiRelationEvidenceSuggestionCitationsValid(suggestion: AiRelationSuggestion, input: AiFactDraftInput): boolean {
  if (!aiRelationEvidenceInputValid(input) || suggestion.reviewCitations != null || !Array.isArray(suggestion.citations)
    || !Array.isArray(suggestion.materialCitations) || !Array.isArray(suggestion.trainingCitations)) return false
  const materials = suggestion.materialCitations, training = suggestion.trainingCitations, directCount = materials.length + training.length
  return directCount >= 1 && directCount + suggestion.citations.length <= 6
    && (input.kind === 'MATERIAL' ? training.length === 0 : materials.length === 0)
    && materials.every(item => aiRelationEvidenceMaterialCitationValid(item, input))
    && training.every(item => aiRelationEvidenceTrainingCitationValid(item, input))
    && new Set(materials.map(item => JSON.stringify([item.excerptId, item.start, item.end]))).size === materials.length
    && new Set(training.map(item => JSON.stringify([item.field, item.elementId, item.start, item.end]))).size === training.length
}
export function aiRelationEvidenceOriginValid(value: unknown): value is AiRelationOrigin {
  try {
    const origin = value as AiRelationOrigin
    if (!fields(origin, ['taskId', 'candidate', 'decision', 'sources', 'relationExists'], ['relationEvidence', 'relationEvidenceState', 'reviewDraft', 'reviewDraftState', 'relationAiFeedback', 'relationAiFeedbackState'])
      || origin.reviewDraft != null || origin.reviewDraftState != null || origin.relationAiFeedback != null || origin.relationAiFeedbackState != null
      || !origin.candidate || !Array.isArray(origin.sources)) return false
    const candidate = origin.candidate, input = origin.relationEvidence
    if (!fields(candidate, ['id', 'sourceItemId', 'targetItemId', 'type', 'reason', 'citations'], ['materialCitations', 'trainingCitations', 'reviewCitations'])
      || candidate.reviewCitations != null || !Array.isArray(candidate.citations) || candidate.citations.length > 6) return false
    if (input == null ? origin.relationEvidenceState != null || candidate.materialCitations != null || candidate.trainingCitations != null || candidate.citations.length < 1
      : !aiRelationEvidenceInputValid(input) || !['CURRENT', 'UNAVAILABLE'].includes(origin.relationEvidenceState ?? '')
        || !text(candidate.reason, 1000) || !aiRelationEvidenceSuggestionCitationsValid(candidate, input)) return false
    const positions = new Set<string>()
    // 出处只含知识元数据；此处核身份与区间形状，完整知识原文仍由服务端和任务详情核对。
    return candidate.citations.every(citation => {
      if (!fields(citation, ['itemId', 'versionId', 'start', 'end', 'quote']) || !integer(citation.start) || !integer(citation.end, 1)
        || citation.end <= citation.start || citation.end - citation.start !== citation.quote?.length
        || !sourceText(citation.quote, 1000) || Array.from(citation.quote).length > 500
        || input != null && !text(citation.quote, 1000)
        || !origin.sources.some(source => source.itemId === citation.itemId && source.versionId === citation.versionId)) return false
      const position = JSON.stringify([citation.itemId, citation.versionId, citation.start, citation.end])
      if (positions.has(position)) return false
      positions.add(position); return true
    })
  } catch { return false }
}
