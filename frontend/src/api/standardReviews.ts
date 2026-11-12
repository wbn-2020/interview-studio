import { ApiError, errorText } from './client'
import { memoryUtcMicros } from './memoryReview'
import { todayDateValid, todayIdValid, todayZoneValid } from './today'

export const recordStatuses = ['ACTIVE', 'WITHDRAWN'] as const
export const recordChanges = ['CREATED', 'REVISED', 'WITHDRAWN'] as const
export const checkpointDecisions = ['SUPPORTED_WITHIN_SCOPE', 'DISPUTED', 'INSUFFICIENT'] as const
export const evidenceRoles = ['REVIEW_RECORD', 'TECHNICAL_BASIS'] as const
export const basisKinds = ['KNOWLEDGE', 'MATERIAL_EXCERPT', 'EXTERNAL_QUOTE'] as const
export const directKinds = ['QUESTION', 'KNOWLEDGE', 'MATERIAL_ATTACHMENT'] as const
export const directChangeKinds = ['QUESTION_VERSION_CHANGED', 'QUESTION_ARCHIVED', 'QUESTION_RESTORED', 'KNOWLEDGE_BODY_CHANGED', 'KNOWLEDGE_ARCHIVED', 'KNOWLEDGE_RESTORED', 'MATERIAL_BASIS_CHANGED', 'KNOWLEDGE_UPSTREAM_CHANGED'] as const
export const basisStates = ['AVAILABLE', 'ARCHIVED', 'UNAVAILABLE', 'EXTERNAL_RECORDED'] as const
export const eligibilityReasons = ['RECORD_WITHDRAWN', 'RECORD_REVISED', 'STANDARD_WITHDRAWN', 'STANDARD_REVISED', 'CHECKPOINT_NOT_SUPPORTED', 'QUESTION_ARCHIVED', 'DIRECT_BASIS_CHANGED', 'BASIS_UNAVAILABLE', 'OBSERVATION_DISPUTED'] as const
export const reviewLimitations = ['USER_RECORDED_REVIEW', 'REVIEWER_IDENTITY_NOT_VERIFIED', 'TECHNICAL_SEMANTICS_NOT_VERIFIED', 'DIRECT_DEPENDENCIES_ONLY', 'EXTERNAL_CONTENT_NOT_FETCHED', 'MATERIAL_BYTES_NOT_RECHECKED'] as const
export type RecordStatus = typeof recordStatuses[number]
export type RecordChange = typeof recordChanges[number]
export type CheckpointDecision = typeof checkpointDecisions[number]
export type EvidenceRole = typeof evidenceRoles[number]
export type BasisKind = typeof basisKinds[number]
export type DirectKind = typeof directKinds[number]
export type DirectChangeKind = typeof directChangeKinds[number]
export type BasisState = typeof basisStates[number]
export type EligibilityReason = typeof eligibilityReasons[number]
export type ReviewLimitation = typeof reviewLimitations[number]
export type LineageCheck = 'PARTIAL'
export type QuestionVersionRef = { questionId: string; versionId: string }
export type RevisionRef = { id: string; revision: number }
export type ReviewerInfo = { label: string; description: string }
export type KnowledgeCitation = { itemId: string; versionId: string; start: number; end: number; quote: string }
export type MaterialCitation = { excerptId: string; start: number; end: number; quote: string }
export type ExternalQuote = { url: string; title: string; locator: string; quote: string; capturedOn: string; sourceDescription: string }
type EvidenceFields = { id: string; roles: EvidenceRole[]; provenanceNote: string; useReason: string }
export type ReviewEvidence = EvidenceFields & (
  { kind: 'KNOWLEDGE'; knowledge: KnowledgeCitation; material: null; external: null }
  | { kind: 'MATERIAL_EXCERPT'; knowledge: null; material: MaterialCitation; external: null }
  | { kind: 'EXTERNAL_QUOTE'; knowledge: null; material: null; external: ExternalQuote })
export type StandardCheckpointReview = { checkpointId: string; decision: CheckpointDecision; conditions: string; conclusion: string; limitations: string; evidenceIds: string[] }
export type StandardReviewContent = { reviewer: ReviewerInfo; reviewedOn: string; reviewZoneId: string; reviewContext: string; checkpoints: StandardCheckpointReview[]; evidence: ReviewEvidence[]; limitations: string }
export type DirectRef = { kind: DirectKind; id: string }
export type DirectStamp = { generation: number; fingerprint: string } & (
  { source: { kind: 'QUESTION' | 'KNOWLEDGE'; id: string }; currentVersionId: string; status: 'ACTIVE' | 'ARCHIVED' }
  | { source: { kind: 'MATERIAL_ATTACHMENT'; id: string }; currentVersionId: null; status: 'READY' | 'RESERVED' })
export type BasisSeal = { question: QuestionVersionRef; directSources: DirectStamp[] }
export type StandardReviewSnapshot = { id: string; question: QuestionVersionRef; revision: number; status: RecordStatus; content: StandardReviewContent; basis: BasisSeal; createdAt: string; revisedAt: string }
export type StandardReviewHistory = { change: RecordChange; snapshot: StandardReviewSnapshot; changeReason: string | null }
export type StandardReviewDraft = { id: string | null; expectedRevision: number | null; question: QuestionVersionRef; referenceExposureId: string; content: StandardReviewContent; changeReason: string | null }
export type SaveStandardReview = { draft: StandardReviewDraft; expectedPreviewHash: string }
export type WithdrawRecord = { expectedRevision: number; reason: string }
export type OpenStandardReview = { revision: number }
export type OpenedStandardReview = { history: StandardReviewHistory; eligibility: StandardEligibility; exposureId: string; recordedAt: string }
export type DirectChange = { generation: number; createdAt: string } & (
  { source: { kind: 'QUESTION'; id: string }; kind: 'QUESTION_VERSION_CHANGED' | 'QUESTION_ARCHIVED' | 'QUESTION_RESTORED'; previousVersionId: string; currentVersionId: string; previousStatus: 'ACTIVE' | 'ARCHIVED'; currentStatus: 'ACTIVE' | 'ARCHIVED' }
  | { source: { kind: 'KNOWLEDGE'; id: string }; kind: 'KNOWLEDGE_BODY_CHANGED' | 'KNOWLEDGE_ARCHIVED' | 'KNOWLEDGE_RESTORED' | 'KNOWLEDGE_UPSTREAM_CHANGED'; previousVersionId: string; currentVersionId: string; previousStatus: 'ACTIVE' | 'ARCHIVED'; currentStatus: 'ACTIVE' | 'ARCHIVED' }
  | { source: { kind: 'MATERIAL_ATTACHMENT'; id: string }; kind: 'MATERIAL_BASIS_CHANGED'; previousVersionId: null; currentVersionId: null; previousStatus: 'READY' | 'RESERVED' | null; currentStatus: 'READY' | 'RESERVED' | null })
export type BasisCheck = { source: DirectRef; recorded: DirectStamp; current: DirectStamp | null; state: Exclude<BasisState, 'EXTERNAL_RECORDED'>; firstUnreviewedChange: DirectChange | null; upstreamChangeCount: number }
export type EvidenceCheck = { evidenceId: string; firstUnreviewedUpstreamChange: DirectChange | null } & (
  { kind: 'EXTERNAL_QUOTE'; state: 'EXTERNAL_RECORDED'; quoteMatches: null }
  | { kind: 'KNOWLEDGE' | 'MATERIAL_EXCERPT'; state: 'AVAILABLE'; quoteMatches: true }
  | { kind: 'KNOWLEDGE' | 'MATERIAL_EXCERPT'; state: 'ARCHIVED' | 'UNAVAILABLE'; quoteMatches: null })
export type CheckpointEligibility = { checkpointId: string; eligible: boolean; reasons: EligibilityReason[] }
export type StandardEligibility = { currentRevision: number; currentStatus: RecordStatus; revisionCurrent: boolean; basisChecks: BasisCheck[]; evidenceChecks: EvidenceCheck[]; checkpoints: CheckpointEligibility[]; lineageCheck: LineageCheck; limitations: ReviewLimitation[] }
export type StandardReviewPreview = { draft: StandardReviewDraft; basis: BasisSeal; evidenceChecks: EvidenceCheck[]; checkpoints: CheckpointEligibility[]; previewHash: string; limitations: ReviewLimitation[] }
export type StandardReviewSummary = { id: string; question: QuestionVersionRef; revision: number; status: RecordStatus; reviewerLabel: string; reviewedOn: string; reviewZoneId: string; checkpointIds: string[]; createdAt: string; revisedAt: string }
export type StandardReviewDetail = { summary: StandardReviewSummary; eligibility: StandardEligibility }
export type StandardReviewPage = { items: StandardReviewDetail[]; nextCursor: string | null }
export type StandardRevisionSummary = { revision: number; change: RecordChange; status: RecordStatus; revisedAt: string }
export type StandardHistoryPage = { items: StandardRevisionSummary[]; nextBefore: number | null }
export type StandardReviewQuery = { questionId: string; versionId: string; status?: RecordStatus; cursor?: string; limit?: number }
export type StandardHistoryQuery = { before?: number; limit?: number }
export type StandardSnapshotIdentity = { id?: string; question?: QuestionVersionRef; revision?: number }

const fields = (value: unknown, names: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
const oneOf = <T extends string>(value: unknown, values: readonly T[]): value is T => typeof value === 'string' && values.includes(value as T)
const check = (rule: () => boolean): boolean => { try { return rule() } catch { return false } }
const bounded = (value: unknown, max = 1048576): boolean => check(() => new TextEncoder().encode(JSON.stringify(value)).length <= max)
const timestamp = (value: unknown): value is string => memoryUtcMicros(value) !== undefined
const beforeOrEqual = (left: string, right: string): boolean => memoryUtcMicros(left)! <= memoryUtcMicros(right)!
const sameTime = (left: string, right: string): boolean => memoryUtcMicros(left) === memoryUtcMicros(right)
const nullableId = (value: unknown): value is string | null => value === null || todayIdValid(value)
const hash = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const text = (value: unknown, max = 2000): value is string => typeof value === 'string' && Array.from(value).length <= max
  && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value) && !/[\u0000\uD800-\uDFFF]/u.test(value)
const list = <T>(value: unknown, valid: (item: unknown) => item is T, min: number, max: number): value is T[] => Array.isArray(value) && value.length >= min && value.length <= max && value.every(item => valid(item))
const uniqueIds = (value: unknown, min: number, max: number): value is string[] => list(value, todayIdValid, min, max) && new Set(value).size === value.length
const enumOrder = <T extends string>(value: unknown, choices: readonly T[], min = 0): value is T[] => list(value, (item): item is T => oneOf(item, choices), min, choices.length)
  && value.every((item, index) => index === 0 || choices.indexOf(value[index - 1]!) < choices.indexOf(item))
function equal(left: unknown, right: unknown): boolean {
  if (left === right) return true
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object' || Array.isArray(left) !== Array.isArray(right)) return false
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every(key => Object.hasOwn(right, key) && equal((left as Record<string, unknown>)[key], (right as Record<string, unknown>)[key]))
}
function dateInZone(instant: string, zone: string): string {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(instant))
  const part = (name: string) => parts.find(value => value.type === name)!.value
  return `${part('year').padStart(4, '0')}-${part('month')}-${part('day')}`
}
// 本地时钟不能替代服务器当天；只有拿到对应服务器时间时才校验未来日期。
function declaredDate(value: unknown, zone?: string, serverNow?: string): value is string {
  return todayDateValid(value) && (serverNow === undefined || zone !== undefined && timestamp(serverNow) && check(() => value <= dateInZone(serverNow, zone)))
}
export const standardReviewIdValid = todayIdValid
export const standardReviewDateValid = todayDateValid
export const standardReviewZoneValid = todayZoneValid
export const standardReviewTextValid = text
export const standardReviewRevisionValid = integer
export function questionVersionRefValid(value: unknown): value is QuestionVersionRef { return fields(value, ['questionId', 'versionId']) && todayIdValid(value.questionId) && todayIdValid(value.versionId) }
export function revisionRefValid(value: unknown): value is RevisionRef { return fields(value, ['id', 'revision']) && todayIdValid(value.id) && integer(value.revision) }
export function reviewerInfoValid(value: unknown): value is ReviewerInfo { return fields(value, ['label', 'description']) && text(value.label, 120) && text(value.description) }
function quoteShape(value: Record<string, unknown>): boolean { return integer(value.start, 0, 2147483647) && integer(value.end, 1, 2147483647) && value.end > value.start && text(value.quote) && value.end - value.start === value.quote.length }
export function knowledgeCitationValid(value: unknown): value is KnowledgeCitation { return fields(value, ['itemId', 'versionId', 'start', 'end', 'quote']) && todayIdValid(value.itemId) && todayIdValid(value.versionId) && quoteShape(value) }
export function materialCitationValid(value: unknown): value is MaterialCitation { return fields(value, ['excerptId', 'start', 'end', 'quote']) && todayIdValid(value.excerptId) && quoteShape(value) }
export function reviewCitationMatches(value: KnowledgeCitation | MaterialCitation, original: string): boolean {
  return (knowledgeCitationValid(value) || materialCitationValid(value)) && value.end <= original.length && original.slice(value.start, value.end) === value.quote
}
export function externalQuoteValid(value: unknown, zone?: string, serverNow?: string): value is ExternalQuote {
  return fields(value, ['url', 'title', 'locator', 'quote', 'capturedOn', 'sourceDescription']) && text(value.url) && text(value.title, 200) && text(value.locator, 500)
    && text(value.quote) && text(value.sourceDescription) && declaredDate(value.capturedOn, zone, serverNow) && check(() => {
      // URL 构造器会修正部分输入，先拒绝会被静默清理的空格、控制符与反斜杠。
      const url = value.url as string
      if (!/^https?:\/\//i.test(url) || /[\s\u0000-\u001f\u007f-\u009f\\]/u.test(url) || /%(?![0-9a-f]{2})/i.test(url)) return false
      const parsed = new URL(url)
      return Boolean(parsed.hostname) && parsed.username === '' && parsed.password === '' && !url.slice(url.indexOf('//') + 2).split(/[/?#]/, 1)[0]!.includes('@')
    })
}
export function reviewEvidenceValid(value: unknown, zone?: string, serverNow?: string): value is ReviewEvidence {
  if (!fields(value, ['id', 'roles', 'kind', 'knowledge', 'material', 'external', 'provenanceNote', 'useReason']) || !todayIdValid(value.id)
    || !enumOrder(value.roles, evidenceRoles, 1) || !text(value.provenanceNote) || !text(value.useReason)) return false
  if (value.kind === 'KNOWLEDGE') return knowledgeCitationValid(value.knowledge) && value.material === null && value.external === null
  if (value.kind === 'MATERIAL_EXCERPT') return materialCitationValid(value.material) && value.knowledge === null && value.external === null
  return value.kind === 'EXTERNAL_QUOTE' && externalQuoteValid(value.external, zone, serverNow) && value.knowledge === null && value.material === null
}
export function standardCheckpointReviewValid(value: unknown): value is StandardCheckpointReview {
  return fields(value, ['checkpointId', 'decision', 'conditions', 'conclusion', 'limitations', 'evidenceIds']) && todayIdValid(value.checkpointId)
    && oneOf(value.decision, checkpointDecisions) && text(value.conditions) && text(value.conclusion) && text(value.limitations) && uniqueIds(value.evidenceIds, 1, 12)
}
export function reviewEvidenceLocation(value: ReviewEvidence): string {
  if (value.kind === 'KNOWLEDGE') return JSON.stringify([value.kind, value.knowledge.itemId, value.knowledge.versionId, value.knowledge.start, value.knowledge.end])
  if (value.kind === 'MATERIAL_EXCERPT') return JSON.stringify([value.kind, value.material.excerptId, value.material.start, value.material.end])
  return JSON.stringify([value.kind, value.external.url, value.external.locator])
}
function checkpointOrderValid(ids: readonly string[], order?: readonly string[]): boolean {
  return new Set(ids).size === ids.length && (order === undefined || uniqueIds(order, 1, 30) && ids.every((id, index) => order.includes(id) && (index === 0 || order.indexOf(ids[index - 1]!) < order.indexOf(id))))
}
export function standardReviewContentValid(value: unknown, checkpointOrder?: readonly string[], serverNow?: string): value is StandardReviewContent {
  if (!fields(value, ['reviewer', 'reviewedOn', 'reviewZoneId', 'reviewContext', 'checkpoints', 'evidence', 'limitations']) || !reviewerInfoValid(value.reviewer)
    || !todayZoneValid(value.reviewZoneId) || !declaredDate(value.reviewedOn, value.reviewZoneId, serverNow) || !text(value.reviewContext) || !text(value.limitations)
    || !list(value.checkpoints, standardCheckpointReviewValid, 1, 30) || !list(value.evidence, (item): item is ReviewEvidence => reviewEvidenceValid(item, value.reviewZoneId as string, serverNow), 1, 12)) return false
  const evidence = value.evidence, checkpoints = value.checkpoints
  if (!checkpointOrderValid(checkpoints.map(item => item.checkpointId), checkpointOrder) || new Set(evidence.map(item => item.id)).size !== evidence.length
    || new Set(evidence.map(reviewEvidenceLocation)).size !== evidence.length || new Set(evidence.filter(item => item.kind === 'MATERIAL_EXCERPT').map(item => item.material.excerptId)).size > 5) return false
  const used = new Set<string>()
  for (const checkpoint of checkpoints) {
    const roles = new Set<EvidenceRole>()
    for (const id of checkpoint.evidenceIds) {
      const item = evidence.find(candidate => candidate.id === id)
      if (!item) return false
      used.add(id); item.roles.forEach(role => roles.add(role))
    }
    if (roles.size !== 2) return false
  }
  return used.size === evidence.length
}
export function standardReviewDraftValid(value: unknown, checkpointOrder?: readonly string[], serverNow?: string): value is StandardReviewDraft {
  return fields(value, ['id', 'expectedRevision', 'question', 'referenceExposureId', 'content', 'changeReason']) && bounded(value, 262144)
    && (value.id === null ? value.expectedRevision === null && value.changeReason === null : todayIdValid(value.id) && integer(value.expectedRevision) && text(value.changeReason))
    && questionVersionRefValid(value.question) && todayIdValid(value.referenceExposureId) && standardReviewContentValid(value.content, checkpointOrder, serverNow)
}
export function saveStandardReviewValid(value: unknown, preview?: StandardReviewPreview): value is SaveStandardReview {
  return fields(value, ['draft', 'expectedPreviewHash']) && bounded(value, 262144) && standardReviewDraftValid(value.draft) && hash(value.expectedPreviewHash)
    && (preview === undefined || equal(value.draft, preview.draft) && value.expectedPreviewHash === preview.previewHash)
}
export function withdrawRecordValid(value: unknown): value is WithdrawRecord { return fields(value, ['expectedRevision', 'reason']) && integer(value.expectedRevision) && text(value.reason) && bounded(value, 262144) }
export function openStandardReviewValid(value: unknown): value is OpenStandardReview { return fields(value, ['revision']) && integer(value.revision) }
export function directRefValid(value: unknown): value is DirectRef { return fields(value, ['kind', 'id']) && oneOf(value.kind, directKinds) && todayIdValid(value.id) }
export function directStampValid(value: unknown): value is DirectStamp {
  return fields(value, ['source', 'generation', 'currentVersionId', 'status', 'fingerprint']) && directRefValid(value.source) && integer(value.generation) && hash(value.fingerprint)
    && (value.source.kind === 'MATERIAL_ATTACHMENT' ? value.currentVersionId === null && oneOf(value.status, ['READY', 'RESERVED']) : todayIdValid(value.currentVersionId) && oneOf(value.status, ['ACTIVE', 'ARCHIVED']))
}
function directOrder(left: DirectRef, right: DirectRef): boolean { return directKinds.indexOf(left.kind) < directKinds.indexOf(right.kind) || left.kind === right.kind && left.id < right.id }
export function basisSealValid(value: unknown, question?: QuestionVersionRef): value is BasisSeal {
  return fields(value, ['question', 'directSources']) && questionVersionRefValid(value.question) && (question === undefined || equal(value.question, question))
    && list(value.directSources, directStampValid, 1, 13) && value.directSources.every((item, index, all) => index === 0 || directOrder(all[index - 1]!.source, item.source))
    && value.directSources.filter(item => item.source.kind === 'QUESTION').length === 1 && value.directSources[0]!.source.kind === 'QUESTION' && value.directSources[0]!.source.id === value.question.questionId
}
function basisContentMatches(basis: BasisSeal, content: StandardReviewContent): boolean {
  const knowledge = new Set(content.evidence.filter(item => item.kind === 'KNOWLEDGE').map(item => item.knowledge.itemId))
  const sources = basis.directSources.filter(item => item.source.kind === 'KNOWLEDGE')
  const excerptCount = new Set(content.evidence.filter(item => item.kind === 'MATERIAL_EXCERPT').map(item => item.material.excerptId)).size
  const attachmentCount = basis.directSources.filter(item => item.source.kind === 'MATERIAL_ATTACHMENT').length
  return knowledge.size === sources.length && sources.every(item => knowledge.has(item.source.id)) && (excerptCount === 0 ? attachmentCount === 0 : attachmentCount > 0 && attachmentCount <= excerptCount)
}
export function standardReviewSnapshotValid(value: unknown, expected: StandardSnapshotIdentity = {}): value is StandardReviewSnapshot {
  return fields(value, ['id', 'question', 'revision', 'status', 'content', 'basis', 'createdAt', 'revisedAt']) && bounded(value, 262144)
    && todayIdValid(value.id) && questionVersionRefValid(value.question) && integer(value.revision) && oneOf(value.status, recordStatuses)
    && timestamp(value.createdAt) && timestamp(value.revisedAt) && beforeOrEqual(value.createdAt, value.revisedAt)
    && (value.revision !== 0 || value.status === 'ACTIVE' && sameTime(value.createdAt, value.revisedAt))
    && standardReviewContentValid(value.content, undefined, value.revisedAt) && basisSealValid(value.basis, value.question) && basisContentMatches(value.basis, value.content)
    && (expected.id === undefined || value.id === expected.id) && (expected.question === undefined || equal(value.question, expected.question)) && (expected.revision === undefined || value.revision === expected.revision)
}
function historyChangeValid(change: unknown, revision: number, status: RecordStatus): change is RecordChange {
  return oneOf(change, recordChanges) && (change === 'CREATED' ? revision === 0 && status === 'ACTIVE' : revision > 0 && (change === 'WITHDRAWN') === (status === 'WITHDRAWN'))
}
export function standardReviewHistoryValid(value: unknown, expected: StandardSnapshotIdentity = {}): value is StandardReviewHistory {
  return fields(value, ['change', 'snapshot', 'changeReason']) && bounded(value, 262144) && standardReviewSnapshotValid(value.snapshot, expected)
    && historyChangeValid(value.change, value.snapshot.revision, value.snapshot.status) && (value.change === 'CREATED' ? value.changeReason === null : text(value.changeReason))
}
export function directChangeValid(value: unknown): value is DirectChange {
  if (!fields(value, ['source', 'generation', 'kind', 'previousVersionId', 'currentVersionId', 'previousStatus', 'currentStatus', 'createdAt']) || !directRefValid(value.source)
    || !integer(value.generation, 1) || !oneOf(value.kind, directChangeKinds) || !nullableId(value.previousVersionId) || !nullableId(value.currentVersionId) || !timestamp(value.createdAt)) return false
  if (value.source.kind === 'MATERIAL_ATTACHMENT') return value.kind === 'MATERIAL_BASIS_CHANGED' && value.previousVersionId === null && value.currentVersionId === null
    && (value.previousStatus === null || oneOf(value.previousStatus, ['READY', 'RESERVED'])) && (value.currentStatus === null || oneOf(value.currentStatus, ['READY', 'RESERVED']))
  if (!oneOf(value.previousStatus, ['ACTIVE', 'ARCHIVED']) || !oneOf(value.currentStatus, ['ACTIVE', 'ARCHIVED']) || !todayIdValid(value.previousVersionId) || !todayIdValid(value.currentVersionId)) return false
  if (value.source.kind === 'QUESTION' ? !oneOf(value.kind, ['QUESTION_VERSION_CHANGED', 'QUESTION_ARCHIVED', 'QUESTION_RESTORED']) : !oneOf(value.kind, ['KNOWLEDGE_BODY_CHANGED', 'KNOWLEDGE_ARCHIVED', 'KNOWLEDGE_RESTORED', 'KNOWLEDGE_UPSTREAM_CHANGED'])) return false
  if (value.kind === 'QUESTION_VERSION_CHANGED' || value.kind === 'KNOWLEDGE_BODY_CHANGED') return value.previousVersionId !== value.currentVersionId && value.previousStatus === 'ACTIVE' && value.currentStatus === 'ACTIVE'
  if (value.kind === 'KNOWLEDGE_UPSTREAM_CHANGED') return value.previousVersionId === value.currentVersionId && value.previousStatus === value.currentStatus
  return value.previousVersionId === value.currentVersionId && (value.kind.endsWith('_ARCHIVED') ? value.previousStatus === 'ACTIVE' && value.currentStatus === 'ARCHIVED' : value.previousStatus === 'ARCHIVED' && value.currentStatus === 'ACTIVE')
}
export function basisCheckValid(value: unknown): value is BasisCheck {
  if (!fields(value, ['source', 'recorded', 'current', 'state', 'firstUnreviewedChange', 'upstreamChangeCount']) || !directRefValid(value.source) || !directStampValid(value.recorded)
    || !equal(value.source, value.recorded.source) || !oneOf(value.state, ['AVAILABLE', 'ARCHIVED', 'UNAVAILABLE'])
    || !integer(value.upstreamChangeCount) || value.source.kind !== 'KNOWLEDGE' && value.upstreamChangeCount !== 0) return false
  if (value.current === null) { if (value.state !== 'UNAVAILABLE') return false }
  else if (!directStampValid(value.current) || !equal(value.source, value.current.source) || value.current.generation < value.recorded.generation
    || value.upstreamChangeCount > value.current.generation - value.recorded.generation
    || value.state === 'AVAILABLE' && !oneOf(value.current.status, ['ACTIVE', 'READY']) || value.state === 'ARCHIVED' && value.current.status !== 'ARCHIVED') return false
  if (value.firstUnreviewedChange === null) return value.upstreamChangeCount === 0 && (value.current === null || value.current.generation === value.recorded.generation)
  return directChangeValid(value.firstUnreviewedChange) && equal(value.source, value.firstUnreviewedChange.source) && value.recorded.generation < Number.MAX_SAFE_INTEGER && value.firstUnreviewedChange.generation === value.recorded.generation + 1
    && (value.firstUnreviewedChange.kind !== 'KNOWLEDGE_UPSTREAM_CHANGED' || value.upstreamChangeCount > 0)
    && (value.current === null || value.firstUnreviewedChange.generation <= value.current.generation)
}
export function evidenceCheckValid(value: unknown): value is EvidenceCheck {
  return fields(value, ['evidenceId', 'kind', 'state', 'quoteMatches', 'firstUnreviewedUpstreamChange']) && todayIdValid(value.evidenceId) && oneOf(value.kind, basisKinds)
    && (value.firstUnreviewedUpstreamChange === null || value.kind === 'KNOWLEDGE' && directChangeValid(value.firstUnreviewedUpstreamChange)
      && value.firstUnreviewedUpstreamChange.kind === 'KNOWLEDGE_UPSTREAM_CHANGED' && value.firstUnreviewedUpstreamChange.source.kind === 'KNOWLEDGE')
    && (value.kind === 'EXTERNAL_QUOTE' ? value.state === 'EXTERNAL_RECORDED' && value.quoteMatches === null
      : oneOf(value.state, ['AVAILABLE', 'ARCHIVED', 'UNAVAILABLE']) && (value.state === 'AVAILABLE' ? value.quoteMatches === true : value.quoteMatches === null))
}
export function checkpointEligibilityValid(value: unknown): value is CheckpointEligibility {
  return fields(value, ['checkpointId', 'eligible', 'reasons']) && todayIdValid(value.checkpointId) && typeof value.eligible === 'boolean'
    && enumOrder(value.reasons, eligibilityReasons) && value.eligible === (value.reasons.length === 0)
}
function limitationsValid(value: unknown, evidence: readonly { kind: BasisKind }[]): value is ReviewLimitation[] {
  const expected: ReviewLimitation[] = reviewLimitations.slice(0, 4)
  if (evidence.some(item => item.kind === 'EXTERNAL_QUOTE')) expected.push('EXTERNAL_CONTENT_NOT_FETCHED')
  if (evidence.some(item => item.kind === 'MATERIAL_EXCERPT')) expected.push('MATERIAL_BYTES_NOT_RECHECKED')
  return enumOrder(value, reviewLimitations, 4) && equal(value, expected)
}
function evidenceChecksMatch(checks: EvidenceCheck[], content?: StandardReviewContent): boolean {
  return new Set(checks.map(item => item.evidenceId)).size === checks.length && (content === undefined || checks.length === content.evidence.length
    && checks.every((item, index) => item.evidenceId === content.evidence[index]!.id && item.kind === content.evidence[index]!.kind))
}
function checkpointsMatch(checks: CheckpointEligibility[], content?: StandardReviewContent): boolean {
  return new Set(checks.map(item => item.checkpointId)).size === checks.length && (content === undefined || checks.length === content.checkpoints.length
    && checks.every((item, index) => item.checkpointId === content.checkpoints[index]!.checkpointId
      && item.reasons.includes('CHECKPOINT_NOT_SUPPORTED') === (content.checkpoints[index]!.decision !== 'SUPPORTED_WITHIN_SCOPE')))
}
function requiredBasisReasons(basis: BasisCheck, question: boolean): EligibilityReason[] {
  const reasons: EligibilityReason[] = []
  if (basis.current === null || basis.state === 'UNAVAILABLE') reasons.push('BASIS_UNAVAILABLE')
  else if (basis.state === 'ARCHIVED') reasons.push(question ? 'QUESTION_ARCHIVED' : 'BASIS_UNAVAILABLE')
  if (basis.firstUnreviewedChange !== null && basis.firstUnreviewedChange.kind !== 'KNOWLEDGE_UPSTREAM_CHANGED'
    || basis.current !== null && (basis.current.generation - basis.recorded.generation !== basis.upstreamChangeCount || basis.current.status !== basis.recorded.status
    || basis.current.fingerprint !== basis.recorded.fingerprint || basis.source.kind !== 'KNOWLEDGE' && basis.current.currentVersionId !== basis.recorded.currentVersionId)) reasons.push('DIRECT_BASIS_CHANGED')
  return reasons
}
export function standardEligibilityValid(value: unknown, snapshot?: StandardReviewSnapshot | StandardReviewSummary): value is StandardEligibility {
  if (!fields(value, ['currentRevision', 'currentStatus', 'revisionCurrent', 'basisChecks', 'evidenceChecks', 'checkpoints', 'lineageCheck', 'limitations']) || !integer(value.currentRevision)
    || !oneOf(value.currentStatus, recordStatuses) || typeof value.revisionCurrent !== 'boolean' || value.lineageCheck !== 'PARTIAL'
    || !list(value.basisChecks, basisCheckValid, 1, 13) || !list(value.evidenceChecks, evidenceCheckValid, 1, 12) || !list(value.checkpoints, checkpointEligibilityValid, 1, 30)
    || !limitationsValid(value.limitations, value.evidenceChecks) || !evidenceChecksMatch(value.evidenceChecks) || !checkpointsMatch(value.checkpoints)) return false
  const basis = value.basisChecks, checkpoints = value.checkpoints, evidenceChecks = value.evidenceChecks
  if (!basis.every((item, index) => index === 0 || directOrder(basis[index - 1]!.source, item.source)) || basis.filter(item => item.source.kind === 'QUESTION').length !== 1) return false
  if (!evidenceChecks.every(item => {
    const upstream = item.firstUnreviewedUpstreamChange
    if (upstream === null) return true
    const source = basis.find(entry => equal(entry.source, upstream.source))
    return source !== undefined && source.upstreamChangeCount > 0 && upstream.generation > source.recorded.generation
      && (source.current === null || upstream.generation <= source.current.generation)
  })) return false
  const questionReasons = requiredBasisReasons(basis[0]!, true)
  if (!checkpoints.every(item => !item.reasons.some(reason => ['STANDARD_WITHDRAWN', 'STANDARD_REVISED', 'OBSERVATION_DISPUTED'].includes(reason))
    && item.reasons.includes('RECORD_WITHDRAWN') === (value.currentStatus === 'WITHDRAWN') && item.reasons.includes('RECORD_REVISED') === !value.revisionCurrent
    && questionReasons.every(reason => item.reasons.includes(reason)))) return false
  if (snapshot === undefined) return true
  if (value.currentRevision < snapshot.revision || value.revisionCurrent !== (value.currentRevision === snapshot.revision)
    || value.revisionCurrent && value.currentStatus !== snapshot.status || basis[0]!.source.id !== snapshot.question.questionId) return false
  if ('content' in snapshot) {
    if (!equal(basis.map(item => item.recorded), snapshot.basis.directSources) || !evidenceChecksMatch(value.evidenceChecks, snapshot.content) || !checkpointsMatch(checkpoints, snapshot.content)) return false
    return checkpoints.every((item, index) => snapshot.content.checkpoints[index]!.evidenceIds.every(id => {
      const evidence = snapshot.content.evidence.find(entry => entry.id === id), evidenceCheck = evidenceChecks.find(entry => entry.evidenceId === id)
      if (!evidence || !evidenceCheck) return false
      if ((evidenceCheck.state === 'UNAVAILABLE' || evidenceCheck.state === 'ARCHIVED') && !item.reasons.includes('BASIS_UNAVAILABLE')) return false
      if (evidenceCheck.firstUnreviewedUpstreamChange !== null && !item.reasons.includes('DIRECT_BASIS_CHANGED')) return false
      if (evidence.kind !== 'KNOWLEDGE') return true
      const source = basis.find(entry => entry.source.kind === 'KNOWLEDGE' && entry.source.id === evidence.knowledge.itemId)
      return source !== undefined && (evidenceCheck.firstUnreviewedUpstreamChange === null
        || equal(source.source, evidenceCheck.firstUnreviewedUpstreamChange.source))
        && requiredBasisReasons(source, false).every(reason => item.reasons.includes(reason))
    }))
  }
  return equal(checkpoints.map(item => item.checkpointId), snapshot.checkpointIds)
}
export function standardReviewPreviewValid(value: unknown, expectedDraft?: StandardReviewDraft, checkpointOrder?: readonly string[]): value is StandardReviewPreview {
  return fields(value, ['draft', 'basis', 'evidenceChecks', 'checkpoints', 'previewHash', 'limitations']) && bounded(value)
    && standardReviewDraftValid(value.draft, checkpointOrder) && (expectedDraft === undefined || equal(value.draft, expectedDraft)) && basisSealValid(value.basis, value.draft.question)
    && basisContentMatches(value.basis, value.draft.content) && list(value.evidenceChecks, evidenceCheckValid, 1, 12) && evidenceChecksMatch(value.evidenceChecks, value.draft.content)
    && value.evidenceChecks.every(item => (item.state === 'AVAILABLE' || item.state === 'EXTERNAL_RECORDED') && item.firstUnreviewedUpstreamChange === null)
    && list(value.checkpoints, checkpointEligibilityValid, 1, 30) && checkpointsMatch(value.checkpoints, value.draft.content)
    && value.checkpoints.every(item => item.reasons.every(reason => reason === 'CHECKPOINT_NOT_SUPPORTED')) && hash(value.previewHash) && limitationsValid(value.limitations, value.evidenceChecks)
}
export function standardReviewSummaryValid(value: unknown): value is StandardReviewSummary {
  return fields(value, ['id', 'question', 'revision', 'status', 'reviewerLabel', 'reviewedOn', 'reviewZoneId', 'checkpointIds', 'createdAt', 'revisedAt']) && todayIdValid(value.id)
    && questionVersionRefValid(value.question) && integer(value.revision) && oneOf(value.status, recordStatuses) && text(value.reviewerLabel, 120) && todayZoneValid(value.reviewZoneId)
    && timestamp(value.createdAt) && timestamp(value.revisedAt) && beforeOrEqual(value.createdAt, value.revisedAt) && declaredDate(value.reviewedOn, value.reviewZoneId, value.revisedAt)
    && uniqueIds(value.checkpointIds, 1, 30) && (value.revision !== 0 || value.status === 'ACTIVE' && sameTime(value.createdAt, value.revisedAt))
}
export function standardReviewDetailValid(value: unknown, id?: string, revision?: number): value is StandardReviewDetail {
  return fields(value, ['summary', 'eligibility']) && bounded(value) && standardReviewSummaryValid(value.summary) && (id === undefined || value.summary.id === id)
    && (revision === undefined || value.summary.revision === revision) && standardEligibilityValid(value.eligibility, value.summary)
    && (revision !== undefined || value.eligibility.revisionCurrent)
}
// 回执属于原键原请求的历史结果。当前资格始终另行 GET，不能拿回执中的 ACTIVE 推断当前可用。
export function savedStandardReviewValid(value: unknown, request: SaveStandardReview, previous?: StandardReviewSnapshot, preview?: StandardReviewPreview): value is StandardReviewSnapshot {
  if (!saveStandardReviewValid(request, preview) || !standardReviewSnapshotValid(value, { question: request.draft.question }) || value.status !== 'ACTIVE'
    || !equal(value.content, request.draft.content)) return false
  if (request.draft.id === null) return value.revision === 0 && (preview === undefined || equal(value.basis, preview.basis))
  if (value.id !== request.draft.id || request.draft.expectedRevision === null) return false
  const revision = request.draft.expectedRevision
  if (value.revision === revision) return previous !== undefined && equal(value, previous)
  return revision < Number.MAX_SAFE_INTEGER && value.revision === revision + 1 && (preview === undefined || equal(value.basis, preview.basis))
    && (previous === undefined || previous.id === value.id && previous.revision === revision && equal(previous.question, value.question) && previous.status === 'ACTIVE'
      && sameTime(value.createdAt, previous.createdAt) && beforeOrEqual(previous.revisedAt, value.revisedAt))
}
export function openedStandardReviewValid(value: unknown, id?: string, request?: OpenStandardReview): value is OpenedStandardReview {
  return fields(value, ['history', 'eligibility', 'exposureId', 'recordedAt']) && bounded(value, 2097152)
    && (request === undefined || openStandardReviewValid(request)) && standardReviewHistoryValid(value.history, { id, revision: request?.revision })
    && standardEligibilityValid(value.eligibility, value.history.snapshot) && todayIdValid(value.exposureId) && timestamp(value.recordedAt) && beforeOrEqual(value.history.snapshot.revisedAt, value.recordedAt)
}
export function standardRevisionSummaryValid(value: unknown): value is StandardRevisionSummary {
  return fields(value, ['revision', 'change', 'status', 'revisedAt']) && integer(value.revision) && oneOf(value.status, recordStatuses) && timestamp(value.revisedAt) && historyChangeValid(value.change, value.revision, value.status)
}
export function withdrawnStandardReviewValid(value: unknown, request: WithdrawRecord, previous?: StandardReviewSnapshot | StandardReviewSummary): value is StandardRevisionSummary {
  if (!withdrawRecordValid(request) || !standardRevisionSummaryValid(value) || value.status !== 'WITHDRAWN' || value.change !== 'WITHDRAWN') return false
  if (previous !== undefined && (previous.revision !== request.expectedRevision || !beforeOrEqual(previous.revisedAt, value.revisedAt))) return false
  if (value.revision === request.expectedRevision) return previous !== undefined && previous.status === 'WITHDRAWN' && sameTime(value.revisedAt, previous.revisedAt)
  return request.expectedRevision < Number.MAX_SAFE_INTEGER && value.revision === request.expectedRevision + 1 && (previous === undefined || previous.status === 'ACTIVE')
}
function queryShape(value: unknown, required: readonly string[], optional: readonly string[]): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key))
    && Object.keys(value).every(key => (required.includes(key) || optional.includes(key)) && (value as Record<string, unknown>)[key] !== undefined))
}
export function standardReviewQueryValid(value: unknown): value is StandardReviewQuery {
  return queryShape(value, ['questionId', 'versionId'], ['status', 'cursor', 'limit']) && todayIdValid(value.questionId) && todayIdValid(value.versionId)
    && (value.status === undefined || oneOf(value.status, recordStatuses)) && (value.cursor === undefined || todayIdValid(value.cursor)) && (value.limit === undefined || integer(value.limit, 1, 50))
}
export function standardHistoryQueryValid(value: unknown): value is StandardHistoryQuery {
  return queryShape(value, [], ['before', 'limit']) && (value.before === undefined || integer(value.before)) && (value.limit === undefined || integer(value.limit, 1, 50))
}
export function standardReviewPageValid(value: unknown, query?: StandardReviewQuery): value is StandardReviewPage {
  if (!fields(value, ['items', 'nextCursor']) || !bounded(value) || query !== undefined && !standardReviewQueryValid(query)
    || !list(value.items, standardReviewDetailValid, 0, query?.limit ?? 50) || !nullableId(value.nextCursor)) return false
  const items = value.items
  return items.every((item, index) => (index === 0 || items[index - 1]!.summary.id < item.summary.id) && item.eligibility.revisionCurrent
    && (query === undefined || item.summary.question.questionId === query.questionId && item.summary.question.versionId === query.versionId
      && (query.status === undefined || item.summary.status === query.status) && (query.cursor === undefined || item.summary.id > query.cursor)))
    && (value.nextCursor === null || items.length === (query?.limit ?? 20) && value.nextCursor === items[items.length - 1]!.summary.id)
}
export function standardHistoryPageValid(value: unknown, query: StandardHistoryQuery = {}): value is StandardHistoryPage {
  if (!fields(value, ['items', 'nextBefore']) || !bounded(value) || !standardHistoryQueryValid(query) || !list(value.items, standardRevisionSummaryValid, 0, query.limit ?? 20)
    || value.nextBefore !== null && !integer(value.nextBefore)) return false
  const items = value.items
  return items.every((item, index) => (query.before === undefined || item.revision < query.before)
    && (index === 0 || items[index - 1]!.revision > item.revision && beforeOrEqual(item.revisedAt, items[index - 1]!.revisedAt) && item.status !== 'WITHDRAWN'))
    && (value.nextBefore === null || items.length === (query.limit ?? 20) && value.nextBefore === items[items.length - 1]!.revision && value.nextBefore > 0)
}
function invalidQuery(): never { throw new ApiError(400, 'INVALID_REQUEST', '审阅参数不符合约定，请核对确切题目版本、记录修订和分页位置。') }
function queryPath(path: string, query: object): string {
  const parameters = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) parameters.set(key, String(value))
  return parameters.size ? `${path}?${parameters}` : path
}
export function standardReviewPath(id: string): string { if (!todayIdValid(id)) invalidQuery(); return `/bank/standard-reviews/${id}` }
export function standardReviewListPath(query: StandardReviewQuery): string { if (!standardReviewQueryValid(query)) invalidQuery(); return queryPath('/bank/standard-reviews', query) }
export function standardReviewDetailPath(id: string, revision?: number): string {
  if (revision !== undefined && !integer(revision)) invalidQuery()
  return queryPath(standardReviewPath(id), revision === undefined ? {} : { revision })
}
export function standardReviewHistoryPath(id: string, query: StandardHistoryQuery = {}): string { if (!standardHistoryQueryValid(query)) invalidQuery(); return queryPath(`${standardReviewPath(id)}/history`, query) }
export const standardReviewPaths = {
  list: standardReviewListPath, detail: standardReviewDetailPath, preview: '/bank/standard-reviews/preview', create: '/bank/standard-reviews', update: standardReviewPath,
  withdraw: (id: string) => `${standardReviewPath(id)}/withdraw`, history: standardReviewHistoryPath, open: (id: string) => `${standardReviewPath(id)}/open`,
} as const

export function emptyStandardReviewDraft(question: QuestionVersionRef, referenceExposureId: string, reviewZoneId = 'UTC'): StandardReviewDraft {
  return { id: null, expectedRevision: null, question: { ...question }, referenceExposureId, changeReason: null,
    content: { reviewer: { label: '', description: '' }, reviewedOn: '', reviewZoneId, reviewContext: '', checkpoints: [], evidence: [], limitations: '' } }
}
export function emptyStandardCheckpointReview(checkpointId: string): StandardCheckpointReview {
  return { checkpointId, decision: 'INSUFFICIENT', conditions: '', conclusion: '', limitations: '', evidenceIds: [] }
}
export function emptyReviewEvidence(kind: BasisKind): ReviewEvidence {
  const common: EvidenceFields = { id: crypto.randomUUID(), roles: [], provenanceNote: '', useReason: '' }
  if (kind === 'KNOWLEDGE') return { ...common, kind, knowledge: { itemId: '', versionId: '', start: 0, end: 0, quote: '' }, material: null, external: null }
  if (kind === 'MATERIAL_EXCERPT') return { ...common, kind, knowledge: null, material: { excerptId: '', start: 0, end: 0, quote: '' }, external: null }
  return { ...common, kind, knowledge: null, material: null, external: { url: '', title: '', locator: '', quote: '', capturedOn: '', sourceDescription: '' } }
}
export const recordStatusLabels: Record<RecordStatus, string> = { ACTIVE: '记录未撤回', WITHDRAWN: '记录已撤回' }
export const recordChangeLabels: Record<RecordChange, string> = { CREATED: '首次录入', REVISED: '修订记录', WITHDRAWN: '撤回记录' }
export const checkpointDecisionLabels: Record<CheckpointDecision, string> = { SUPPORTED_WITHIN_SCOPE: '在所述范围内支持', DISPUTED: '存在争议', INSUFFICIENT: '审阅依据不足' }
export const evidenceRoleLabels: Record<EvidenceRole, string> = { REVIEW_RECORD: '实际审阅记录', TECHNICAL_BASIS: '技术依据' }
export const basisKindLabels: Record<BasisKind, string> = { KNOWLEDGE: '知识发布版本', MATERIAL_EXCERPT: '材料摘录', EXTERNAL_QUOTE: '外部摘录记录' }
export const basisStateLabels: Record<BasisState, string> = { AVAILABLE: '当前可用', ARCHIVED: '来源已归档', UNAVAILABLE: '来源不可用', EXTERNAL_RECORDED: '仅记录外部摘录' }
export const directKindLabels: Record<DirectKind, string> = { QUESTION: '题目', KNOWLEDGE: '知识', MATERIAL_ATTACHMENT: '材料附件' }
export const directChangeKindLabels: Record<DirectChangeKind, string> = { QUESTION_VERSION_CHANGED: '题目已发布新版本', QUESTION_ARCHIVED: '题目已归档', QUESTION_RESTORED: '题目已恢复', KNOWLEDGE_BODY_CHANGED: '知识发布正文已变化', KNOWLEDGE_ARCHIVED: '知识已归档', KNOWLEDGE_RESTORED: '知识已恢复', MATERIAL_BASIS_CHANGED: '材料依据已变化', KNOWLEDGE_UPSTREAM_CHANGED: '知识历史采用来源需核对' }
export const eligibilityReasonLabels: Record<EligibilityReason, string> = {
  RECORD_WITHDRAWN: '记录已撤回', RECORD_REVISED: '正在查看历史修订', STANDARD_WITHDRAWN: '所引用的审阅已撤回', STANDARD_REVISED: '所引用的审阅已有新修订',
  CHECKPOINT_NOT_SUPPORTED: '该检查项未记录为范围内支持', QUESTION_ARCHIVED: '题目已归档', DIRECT_BASIS_CHANGED: '直接依据已变化，需要新的审阅记录', BASIS_UNAVAILABLE: '直接依据当前不可用', OBSERVATION_DISPUTED: '观察结论存在争议',
}
export const reviewLimitationLabels: Record<ReviewLimitation, string> = {
  USER_RECORDED_REVIEW: '审阅信息由用户录入', REVIEWER_IDENTITY_NOT_VERIFIED: '平台未核实审阅者身份或资质', TECHNICAL_SEMANTICS_NOT_VERIFIED: '平台未验证技术语义',
  DIRECT_DEPENDENCIES_ONLY: '仅核对直接依据，间接来源仍待核对', EXTERNAL_CONTENT_NOT_FETCHED: '外部页面未抓取，摘录真实性及后续变化未核实', MATERIAL_BYTES_NOT_RECHECKED: '本次未重新核对附件文件内容',
}
export function standardReviewErrorText(error: unknown): string {
  if (!(error instanceof ApiError)) return errorText(error)
  const messages: Record<string, string> = {
    STANDARD_REVIEW_NOT_FOUND: '审阅记录或确切修订不存在或不可访问。', REVIEW_SOURCE_NOT_FOUND: '确切题目、知识或材料来源不存在或不可访问。',
    REVIEW_REVISION_CONFLICT: '记录已有新修订，请保留当前输入并重新读取。', REVIEW_RECORD_WITHDRAWN: '记录已撤回，请查看历史；新的实际审阅需要另建记录。',
    REVIEW_PREVIEW_CHANGED: '来源、记录或日期核对条件已变化，请保留输入并重新预览。', STANDARD_REVIEW_NOT_ELIGIBLE: '所选审阅范围当前不能支持新增观察。',
    REVIEW_REREVIEW_EVIDENCE_REQUIRED: '该检查项需要可追溯的新审阅记录；修改理由或重新勾选不能完成重核。', REVIEW_CITATION_INVALID: '摘录与确切来源、位置或范围不一致，请重新选取。',
    REVIEW_RECORD_LIMIT: '记录大小、条数或修订超过上限，请缩小范围。', REVIEW_BASIS_LIMIT: '直接来源或变更号超过上限，请核对来源范围。',
    REVIEW_CLOCK_CHANGED: '服务器时间早于最近修改时间，请保留输入并稍后重新读取。',
  }
  return messages[error.code] ?? errorText(error)
}
