import type { Schema } from './client'
import type { AiManifest, AiRelationOrigin, AiRelationSuggestion, AiSource, AiTask } from './ai'
import { aiCandidateManifestFingerprint, aiKnowledgeMaterialChecksMatch, aiRelationDecisionValid, aiRelationManifestMatches, aiRelationTaskMatches } from './ai'
import type { AiReviewDraftCitation, AiReviewDraftInput } from './aiReviewDraft'
import { aiReviewDraftCitationValid, aiReviewDraftInputFingerprint, aiReviewDraftInputValid, aiReviewDraftSourceMatches,
  aiReviewDraftSourceSelectionValid } from './aiReviewDraft'
import { aiRelationEvidenceKnowledgeSourceValid } from './aiRelationEvidence'

export type AiRelationReviewPreviewRequest = Schema['AiRelationReviewPreviewRequest']
export type AiRelationReviewCreateRequest = Schema['AiCreateRelationReviewDraft']
export const aiRelationReviewPaths = { preview: '/ai/relation-review-drafts/preview', create: '/ai/relation-review-drafts/tasks' } as const
export const aiRelationReviewValidationNote = 'AI 未审关系候选，结构、所选概念端点及知识、自检、用户录入外部反馈或面后回忆选区的原文区间已核对；保留原条件、依据、局限、当时决定与不确定性。不认证反馈者身份、来源真实性、关系语义、技术正确或用户已经掌握，回忆和无旧答不能替代正式提交。候选需逐项审阅，不会自动进入正式星图。'
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

export function aiRelationReviewInputValid(value: unknown): value is AiReviewDraftInput {
  return aiReviewDraftInputValid(value as AiReviewDraftInput)
}
export function aiRelationReviewInputFingerprint(input: AiReviewDraftInput): string { return aiReviewDraftInputFingerprint(input) }
export function aiRelationReviewRequestValid(value: unknown): value is AiRelationReviewPreviewRequest {
  const request = value as AiRelationReviewPreviewRequest
  return fields(request, ['instruction', 'sources', 'source']) && text(request.instruction, 1000, false)
    && aiReviewDraftSourceSelectionValid(request.source) && Array.isArray(request.sources) && request.sources.length >= 2 && request.sources.length <= 5
    && request.sources.every(source => fields(source, ['itemId', 'versionId']) && uuid(source.itemId) && uuid(source.versionId))
    && new Set(request.sources.map(source => source.itemId)).size === request.sources.length
    && new Set(request.sources.map(source => source.versionId)).size === request.sources.length
}
export function aiRelationReviewRequestProblem(value: unknown): string {
  return aiRelationReviewRequestValid(value) ? '' : '请选择 2–5 个不同知识版本，以及同一份自检、外部反馈或面后回忆的 1–8 个不重叠原文片段；整理要求最多 1000 个 UTF-16 码元。'
}
export function aiRelationReviewManifestMatches(manifest: AiManifest): boolean {
  return aiRelationManifestMatches(manifest) && manifest.reviewDraft != null && manifest.promptVersion === 'relation-review-draft-1'
}
export function aiRelationReviewSourcesMatch(manifest: AiManifest, request: AiRelationReviewPreviewRequest,
  selectedKnowledge: AiSource[], expectedInput: AiReviewDraftInput): boolean {
  try {
    return aiRelationReviewManifestMatches(manifest) && aiKnowledgeMaterialChecksMatch(manifest, true)
      && aiRelationReviewRequestValid(request) && aiRelationReviewInputValid(expectedInput)
      && Array.isArray(selectedKnowledge) && selectedKnowledge.every(aiRelationEvidenceKnowledgeSourceValid)
      && manifest.instruction === request.instruction && manifest.sources.length === request.sources.length
      && JSON.stringify(manifest.sources.map(sourceValues)) === JSON.stringify(selectedKnowledge.map(sourceValues))
      && manifest.sources.every((source, index) => source.itemId === request.sources[index]!.itemId && source.versionId === request.sources[index]!.versionId)
      && aiReviewDraftInputFingerprint(manifest.reviewDraft!) === aiReviewDraftInputFingerprint(expectedInput)
      && aiReviewDraftSourceMatches(manifest.reviewDraft!, request.source)
  } catch { return false }
}
export function aiRelationReviewTaskMatches(value: unknown, expectedManifest?: AiManifest): value is AiTask {
  return aiRelationTaskMatches(value) && aiRelationReviewManifestMatches(value.manifest)
    && (expectedManifest === undefined || aiRelationReviewManifestMatches(expectedManifest)
      && aiCandidateManifestFingerprint(value.manifest) === aiCandidateManifestFingerprint(expectedManifest))
}
export function aiRelationReviewCitationValid(value: unknown, input: AiReviewDraftInput): value is AiReviewDraftCitation {
  return aiReviewDraftCitationValid(input, value as AiReviewDraftCitation)
}
export function aiRelationReviewSuggestionCitationsValid(suggestion: AiRelationSuggestion, input: AiReviewDraftInput): boolean {
  if (!aiRelationReviewInputValid(input) || !suggestion || !Array.isArray(suggestion.citations)
    || suggestion.materialCitations !== null || suggestion.trainingCitations !== null || !Array.isArray(suggestion.reviewCitations)) return false
  const references = suggestion.reviewCitations
  return references.length >= 1 && references.length + suggestion.citations.length <= 6
    && references.every(citation => aiRelationReviewCitationValid(citation, input))
    && new Set(references.map(citation => JSON.stringify([citation.field, citation.start, citation.end]))).size === references.length
}
export function aiRelationReviewOriginValid(value: unknown): value is AiRelationOrigin {
  try {
    const origin = value as AiRelationOrigin
    if (!fields(origin, ['taskId', 'candidate', 'decision', 'sources', 'relationExists', 'reviewDraft', 'reviewDraftState'], ['relationEvidence', 'relationEvidenceState', 'relationAiFeedback', 'relationAiFeedbackState'])
      || !uuid(origin.taskId) || typeof origin.relationExists !== 'boolean' || origin.relationEvidence != null || origin.relationEvidenceState != null
      || origin.relationAiFeedback != null || origin.relationAiFeedbackState != null
      || !aiRelationReviewInputValid(origin.reviewDraft) || !Array.isArray(origin.sources) || origin.sources.length < 2 || origin.sources.length > 5) return false
    const input = origin.reviewDraft, state = origin.reviewDraftState
    if (!['CURRENT', 'SUPERSEDED', 'ARCHIVED', 'REVIEW_REQUIRED', 'UNAVAILABLE'].includes(state ?? '')
      || input.kind !== 'RECOLLECTION' && state === 'ARCHIVED'
      || !origin.sources.every(source => fields(source, ['itemId', 'versionId', 'number', 'title', 'kind', 'state'])
        && uuid(source.itemId) && uuid(source.versionId) && integer(source.number, 1) && sourceText(source.title, 200)
        && ['CONCEPT', 'NOTE'].includes(source.kind) && ['CURRENT', 'SUPERSEDED', 'ARCHIVED', 'UNAVAILABLE'].includes(source.state))
      || new Set(origin.sources.map(source => source.itemId)).size !== origin.sources.length
      || new Set(origin.sources.map(source => source.versionId)).size !== origin.sources.length) return false
    const candidate = origin.candidate, decision = origin.decision
    const concepts = new Set(origin.sources.filter(source => source.kind === 'CONCEPT').map(source => source.itemId))
    if (!fields(candidate, ['id', 'sourceItemId', 'targetItemId', 'type', 'reason', 'citations', 'materialCitations', 'trainingCitations', 'reviewCitations'])
      || !/^r([1-9]|10)$/.test(candidate.id) || concepts.size < 2 || !concepts.has(candidate.sourceItemId) || !concepts.has(candidate.targetItemId)
      || candidate.sourceItemId === candidate.targetItemId || !['PRECEDES', 'PART_OF', 'APPLIED_IN', 'RELATED', 'CONTRASTS'].includes(candidate.type)
      || ['RELATED', 'CONTRASTS'].includes(candidate.type) && candidate.sourceItemId > candidate.targetItemId
      || !text(candidate.reason, 1000) || !aiRelationReviewSuggestionCitationsValid(candidate, input)
      || !aiRelationDecisionValid(decision) || decision.status !== 'ADOPTED' || decision.supplementId != null || decision.candidateId !== candidate.id
      || !decision.content || ![candidate.sourceItemId, candidate.targetItemId].includes(decision.content.sourceItemId)
      || ![candidate.sourceItemId, candidate.targetItemId].includes(decision.content.targetItemId)) return false
    const positions = new Set<string>()
    // 出处不携带知识正文；这里只核知识身份与区间，复盘引文仍可直接核对固定选区。
    return candidate.citations.every(citation => {
      if (!fields(citation, ['itemId', 'versionId', 'start', 'end', 'quote']) || !integer(citation.start) || !integer(citation.end, 1)
        || !text(citation.quote, 1000) || Array.from(citation.quote).length > 500 || citation.end <= citation.start
        || citation.end - citation.start !== citation.quote.length
        || !origin.sources.some(source => source.itemId === citation.itemId && source.versionId === citation.versionId)) return false
      const position = JSON.stringify([citation.itemId, citation.versionId, citation.start, citation.end])
      if (positions.has(position)) return false
      positions.add(position); return true
    })
  } catch { return false }
}
