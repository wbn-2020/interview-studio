import type { RouteLocationRaw } from 'vue-router'
import type { Schema } from './client'
import type { AiManifest, AiRelationOrigin, AiRelationSuggestion, AiSource, AiTask } from './ai'
import { aiCandidateManifestFingerprint, aiKnowledgeMaterialChecksMatch, aiRelationDecisionValid, aiRelationManifestMatches, aiRelationTaskMatches } from './ai'
import type { AiReviewDraftCitation } from './aiReviewDraft'
import type { RelationAiFeedbackEvidenceSelection, RelationAiFeedbackEvidenceSnapshot } from './relationAiFeedbackEvidence'
import { relationAiFeedbackEvidenceRangeMatches, relationAiFeedbackEvidenceSelectionValid,
  relationAiFeedbackEvidenceSnapshotFingerprint, relationAiFeedbackEvidenceSnapshotValid } from './relationAiFeedbackEvidence'
import { aiRelationEvidenceKnowledgeSourceValid } from './aiRelationEvidence'

export type AiRelationAiFeedbackPreviewRequest = Schema['AiRelationAiFeedbackPreviewRequest']
export type AiRelationAiFeedbackCreateRequest = Schema['AiCreateRelationAiFeedbackDraft']
export type AiRelationAiFeedbackInput = RelationAiFeedbackEvidenceSnapshot
export const aiRelationAiFeedbackPaths = { preview: '/ai/relation-ai-feedback-drafts/preview', create: '/ai/relation-ai-feedback-drafts/tasks' } as const
export const aiRelationAiFeedbackValidationNote = 'AI 未审关系候选，已核对结构、所选概念端点及知识与 AI 反馈选区的原文位置；原报告仍为 AI_UNREVIEWED，保留原作答条件、完整局限与当时决定。不认证原报告或来源真实性，不证明原报告引文逐字匹配、关系语义正确、技术正确或用户已经掌握。候选需逐项审阅，不会自动进入正式星图。'
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
const answerValues = (answer: RelationAiFeedbackEvidenceSnapshot['answer']) => [answer.sessionId, answer.questionId, answer.answerId]
const fragmentValues = (fragments: RelationAiFeedbackEvidenceSnapshot['fragments']) => fragments.map(item => [item.field, item.start, item.end, item.quote])

export function aiRelationAiFeedbackInputValid(value: unknown): value is RelationAiFeedbackEvidenceSnapshot {
  return relationAiFeedbackEvidenceSnapshotValid(value)
}
export function aiRelationAiFeedbackInputFingerprint(input: RelationAiFeedbackEvidenceSnapshot): string {
  return relationAiFeedbackEvidenceSnapshotFingerprint(input)
}
export function aiRelationAiFeedbackSourceMatches(input: RelationAiFeedbackEvidenceSnapshot, selection: RelationAiFeedbackEvidenceSelection): boolean {
  return aiRelationAiFeedbackInputValid(input) && relationAiFeedbackEvidenceSelectionValid(selection)
    && input.taskId === selection.taskId && input.attemptId === selection.attemptId && input.feedbackId === selection.feedbackId
    && input.reportHash === selection.reportHash && input.decisionRevision === selection.expectedDecisionRevision
    && JSON.stringify(answerValues(input.answer)) === JSON.stringify(answerValues(selection.answer))
    && JSON.stringify(fragmentValues(input.fragments)) === JSON.stringify(fragmentValues(selection.fragments))
}
export function aiRelationAiFeedbackRequestValid(value: unknown): value is AiRelationAiFeedbackPreviewRequest {
  const request = value as AiRelationAiFeedbackPreviewRequest
  return fields(request, ['instruction', 'sources', 'source']) && text(request.instruction, 1000, false)
    && relationAiFeedbackEvidenceSelectionValid(request.source) && Array.isArray(request.sources) && request.sources.length >= 2 && request.sources.length <= 5
    && request.sources.every(source => fields(source, ['itemId', 'versionId']) && uuid(source.itemId) && uuid(source.versionId))
    && new Set(request.sources.map(source => source.itemId)).size === request.sources.length
    && new Set(request.sources.map(source => source.versionId)).size === request.sources.length
}
export function aiRelationAiFeedbackRequestProblem(value: unknown): string {
  return aiRelationAiFeedbackRequestValid(value) ? '' : '请选择 2–5 个不同知识版本，以及同一条 AI 反馈的 1–8 个不重叠原文片段；整理要求最多 1000 个 UTF-16 码元。'
}
export function aiRelationAiFeedbackManifestMatches(manifest: AiManifest): boolean {
  return aiRelationManifestMatches(manifest) && manifest.relationAiFeedback != null && manifest.promptVersion === 'relation-ai-feedback-draft-1'
}
export function aiRelationAiFeedbackSourcesMatch(manifest: AiManifest, request: AiRelationAiFeedbackPreviewRequest,
  selectedKnowledge: AiSource[], expectedInput: RelationAiFeedbackEvidenceSnapshot): boolean {
  try {
    return aiRelationAiFeedbackManifestMatches(manifest) && aiKnowledgeMaterialChecksMatch(manifest, true)
      && aiRelationAiFeedbackRequestValid(request) && aiRelationAiFeedbackInputValid(expectedInput)
      && Array.isArray(selectedKnowledge) && selectedKnowledge.every(aiRelationEvidenceKnowledgeSourceValid)
      && manifest.instruction === request.instruction && manifest.sources.length === request.sources.length
      && JSON.stringify(manifest.sources.map(sourceValues)) === JSON.stringify(selectedKnowledge.map(sourceValues))
      && manifest.sources.every((source, index) => source.itemId === request.sources[index]!.itemId && source.versionId === request.sources[index]!.versionId)
      && aiRelationAiFeedbackInputFingerprint(manifest.relationAiFeedback!) === aiRelationAiFeedbackInputFingerprint(expectedInput)
      && aiRelationAiFeedbackSourceMatches(manifest.relationAiFeedback!, request.source)
  } catch { return false }
}
export function aiRelationAiFeedbackTaskMatches(value: unknown, expectedManifest?: AiManifest): value is AiTask {
  return aiRelationTaskMatches(value) && aiRelationAiFeedbackManifestMatches(value.manifest)
    && (expectedManifest === undefined || aiRelationAiFeedbackManifestMatches(expectedManifest)
      && aiCandidateManifestFingerprint(value.manifest) === aiCandidateManifestFingerprint(expectedManifest))
}
export function aiRelationAiFeedbackCitationValid(value: unknown, input: RelationAiFeedbackEvidenceSnapshot): value is AiReviewDraftCitation {
  const citation = value as AiReviewDraftCitation
  if (!aiRelationAiFeedbackInputValid(input) || !fields(citation, ['field', 'start', 'end', 'quote'])
    || !['OBSERVATION', 'CONDITION', 'BASIS', 'LIMITATION', 'SUGGESTION'].includes(citation.field)
    || !integer(citation.start) || !integer(citation.end, 1) || !sourceText(citation.quote, 1000)) return false
  // 完整报告限制只提供语境；引用仍须落在明确选中的同字段片段内。
  return input.fragments.some(fragment => fragment.field === citation.field && citation.start >= fragment.start && citation.end <= fragment.end
    && relationAiFeedbackEvidenceRangeMatches(fragment.quote, citation.start - fragment.start, citation.end - fragment.start, citation.quote))
}
export function aiRelationAiFeedbackSuggestionCitationsValid(suggestion: AiRelationSuggestion, input: RelationAiFeedbackEvidenceSnapshot): boolean {
  if (!aiRelationAiFeedbackInputValid(input) || !suggestion || !Array.isArray(suggestion.citations)
    || suggestion.materialCitations !== null || suggestion.trainingCitations !== null || !Array.isArray(suggestion.reviewCitations)) return false
  const references = suggestion.reviewCitations
  return references.length >= 1 && references.length + suggestion.citations.length <= 6
    && references.every(citation => aiRelationAiFeedbackCitationValid(citation, input))
    && new Set(references.map(citation => JSON.stringify([citation.field, citation.start, citation.end]))).size === references.length
}
export function aiRelationAiFeedbackInputRoute(input: RelationAiFeedbackEvidenceSnapshot): RouteLocationRaw | undefined {
  return aiRelationAiFeedbackInputValid(input)
    ? { name: 'review', query: { ...input.answer, taskId: input.taskId, observationId: input.feedbackId } } : undefined
}
export function aiRelationAiFeedbackOriginValid(value: unknown): value is AiRelationOrigin {
  try {
    const origin = value as AiRelationOrigin
    if (!fields(origin, ['taskId', 'candidate', 'decision', 'sources', 'relationExists', 'relationAiFeedback', 'relationAiFeedbackState'],
      ['relationEvidence', 'relationEvidenceState', 'reviewDraft', 'reviewDraftState'])
      || !uuid(origin.taskId) || typeof origin.relationExists !== 'boolean' || origin.relationEvidence != null || origin.relationEvidenceState != null
      || origin.reviewDraft != null || origin.reviewDraftState != null || !aiRelationAiFeedbackInputValid(origin.relationAiFeedback)
      || !['CURRENT', 'REVIEW_REQUIRED', 'UNAVAILABLE'].includes(origin.relationAiFeedbackState ?? '')
      || !Array.isArray(origin.sources) || origin.sources.length < 2 || origin.sources.length > 5) return false
    if (!origin.sources.every(source => fields(source, ['itemId', 'versionId', 'number', 'title', 'kind', 'state'])
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
      || !text(candidate.reason, 1000) || !aiRelationAiFeedbackSuggestionCitationsValid(candidate, origin.relationAiFeedback)
      || !aiRelationDecisionValid(decision) || decision.status !== 'ADOPTED' || decision.supplementId != null || decision.candidateId !== candidate.id
      || !decision.content || ![candidate.sourceItemId, candidate.targetItemId].includes(decision.content.sourceItemId)
      || ![candidate.sourceItemId, candidate.targetItemId].includes(decision.content.targetItemId)) return false
    const positions = new Set<string>()
    // 出处仅带知识元数据；知识原文逐字核验仍属于服务端或完整任务详情。
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
