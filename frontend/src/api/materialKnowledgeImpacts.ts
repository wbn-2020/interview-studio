import type { Schema } from './client'
import type { KnowledgeRevalidationChoice, KnowledgeRevalidationResolve, KnowledgeRevalidationState, KnowledgeRevalidationTarget } from './knowledgeRevalidation'
import { knowledgeRevalidationChoiceLabels, revalidationIdValid, revalidationReasonValid, revalidationStateValid } from './knowledgeRevalidation'
import { materialCorrectionStatementValid } from './materialCorrection'

export type MaterialKnowledgeImpactContext = Schema['MaterialKnowledgeImpactContext']
export type MaterialKnowledgeImpactDomain = Schema['MaterialKnowledgeImpactDomain']
export type MaterialKnowledgeImpactSource = Schema['MaterialKnowledgeImpactSource']
export type MaterialKnowledgeImpact = Schema['MaterialKnowledgeImpact']
export type MaterialKnowledgeImpactPage = Schema['MaterialKnowledgeImpactPage']

export const materialKnowledgeImpactPaths = {
  page: (itemId: string, context: MaterialKnowledgeImpactContext, state: KnowledgeRevalidationState, cursor?: string) => {
    const query = new URLSearchParams({ state, limit: '20' })
    if (cursor !== undefined) query.set('cursor', cursor)
    return `${context === 'MATERIAL_EXCERPT' ? `/lifecycle/material-excerpts/${encodeURIComponent(itemId)}/knowledge-impacts`
      : `/lifecycle/knowledge/${encodeURIComponent(itemId)}/material-impacts`}?${query}`
  },
  detail: (impactId: string) => `/lifecycle/material-knowledge-impacts/${encodeURIComponent(impactId)}`,
  resolve: (itemId: string, impactId: string) =>
    `/lifecycle/knowledge/${encodeURIComponent(itemId)}/material-impacts/${encodeURIComponent(impactId)}/resolve`,
}
const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const title = (value: unknown): value is string =>
  typeof value === 'string' && value.length > 0 && Array.from(value).length <= 200 && !/[\uD800-\uDFFF]/u.test(value)
const choice = (value: unknown): value is KnowledgeRevalidationChoice =>
  typeof value === 'string' && Object.hasOwn(knowledgeRevalidationChoiceLabels, value)
export const materialKnowledgeImpactContextValid = (value: unknown): value is MaterialKnowledgeImpactContext =>
  value === 'MATERIAL_EXCERPT' || value === 'KNOWLEDGE'
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(value)) return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19)
}
const micros = (value: string) => `${value.slice(0, 19)}.${value.slice(19, -1).replace('.', '').padEnd(6, '0')}Z`
function sourceValid(value: unknown, impact: MaterialKnowledgeImpact): value is MaterialKnowledgeImpactSource {
  const source = value as MaterialKnowledgeImpactSource
  if (!fields(source, ['domain', 'itemId', 'versionId', 'versionNumber', 'title']) || !revalidationIdValid(source.itemId)) return false
  if (impact.depth === 1) return impact.parentImpactId === null && source.domain === 'MATERIAL_EXCERPT'
    && source.itemId === impact.correction.excerptId && source.versionId === null && source.versionNumber === null && source.title === null
  return source.domain === 'KNOWLEDGE' && revalidationIdValid(source.versionId)
    && integer(source.versionNumber, 1, 2147483647) && title(source.title)
    && revalidationIdValid(impact.parentImpactId) && impact.parentImpactId !== impact.id
}
export function materialKnowledgeImpactValid(value: unknown): value is MaterialKnowledgeImpact {
  const impact = value as MaterialKnowledgeImpact
  if (!fields(impact, ['id', 'revision', 'correction', 'source', 'taskId', 'targetItemId', 'targetTitle',
    'targetVersionId', 'targetVersionNumber', 'depth', 'parentImpactId', 'createdAt', 'resolution'])
    || ![impact.id, impact.taskId, impact.targetItemId, impact.targetVersionId].every(revalidationIdValid)
    || !integer(impact.revision, 0, 1) || !title(impact.targetTitle) || !integer(impact.targetVersionNumber, 1, 2147483647)
    || !integer(impact.depth, 1, 2147483647) || !timestamp(impact.createdAt) || !impact.correction
    || !materialCorrectionStatementValid(impact.correction, impact.correction.excerptId)
    || micros(impact.createdAt) !== micros(impact.correction.createdAt) || !sourceValid(impact.source, impact)) return false
  if (impact.revision === 0) return impact.resolution === null
  const result = impact.resolution
  return fields(result, ['choice', 'reason', 'targetVersionId', 'targetRevision', 'decidedAt']) && Boolean(result
    && choice(result.choice) && revalidationReasonValid(result.reason) && revalidationIdValid(result.targetVersionId)
    && integer(result.targetRevision) && timestamp(result.decidedAt) && micros(result.decidedAt) >= micros(impact.createdAt))
}
const rootKey = (impact: MaterialKnowledgeImpact) => {
  const root = impact.correction
  return JSON.stringify([root.id, root.excerptId, root.replacementExcerptId, root.sourceRevision,
    root.replacementRevision, root.reason, micros(root.createdAt), micros(impact.createdAt)])
}
const sourceKey = (source: MaterialKnowledgeImpactSource) =>
  [source.domain, source.itemId, source.versionId, source.versionNumber, source.title]
export function materialKnowledgeSameImpact(left: MaterialKnowledgeImpact, right: MaterialKnowledgeImpact): boolean {
  return materialKnowledgeImpactValid(left) && materialKnowledgeImpactValid(right) && rootKey(left) === rootKey(right)
    && JSON.stringify([left.id, sourceKey(left.source), left.taskId, left.targetItemId, left.targetTitle,
      left.targetVersionId, left.targetVersionNumber, left.depth, left.parentImpactId])
    === JSON.stringify([right.id, sourceKey(right.source), right.taskId, right.targetItemId, right.targetTitle,
      right.targetVersionId, right.targetVersionNumber, right.depth, right.parentImpactId])
}
type Position = { owner: string; time: string; id: string }
function cursorPosition(value: unknown, itemId: string, context: MaterialKnowledgeImpactContext,
  state: KnowledgeRevalidationState): Position | undefined {
  if (typeof value !== 'string' || value.length > 200) return undefined
  const parts = value.split('~')
  if (parts.length !== 6 || !revalidationIdValid(parts[0]) || parts[1] !== context || parts[2] !== itemId || parts[3] !== state
    || !timestamp(parts[4]) || micros(parts[4]) !== parts[4] || !revalidationIdValid(parts[5])) return undefined
  return { owner: parts[0], time: parts[4], id: parts[5] }
}
const compare = (left: { time: string; id: string }, right: { time: string; id: string }) =>
  left.time < right.time ? -1 : left.time > right.time ? 1 : left.id < right.id ? -1 : left.id > right.id ? 1 : 0
export function materialKnowledgeImpactPageValid(value: unknown, itemId: string, context: MaterialKnowledgeImpactContext,
  state: KnowledgeRevalidationState, cursor?: string): value is MaterialKnowledgeImpactPage {
  const page = value as MaterialKnowledgeImpactPage, before = cursor === undefined ? undefined : cursorPosition(cursor, itemId, context, state)
  if (!revalidationIdValid(itemId) || !materialKnowledgeImpactContextValid(context) || !revalidationStateValid(state)
    || cursor !== undefined && !before || !fields(page, ['context', 'itemId', 'state', 'items', 'nextCursor'])
    || page.context !== context || page.itemId !== itemId || page.state !== state || !Array.isArray(page.items) || page.items.length > 20) return false
  let previous: { time: string; id: string } | undefined = before
  const ids = new Set<string>(), edges = new Set<string>(), roots = new Map<string, string>(), excerpts = new Map<string, string>()
  for (const impact of page.items) {
    if (!materialKnowledgeImpactValid(impact) || ids.has(impact.id)
      || (context === 'MATERIAL_EXCERPT' ? impact.correction.excerptId : impact.targetItemId) !== itemId
      || state === 'PENDING' && impact.resolution !== null) return false
    const position = { time: micros(impact.createdAt), id: impact.id }, root = rootKey(impact)
    const edge = JSON.stringify([impact.correction.id, impact.source.domain, impact.taskId, impact.source.itemId, impact.source.versionId])
    if (previous && compare(position, previous) >= 0 || edges.has(edge)
      || roots.has(impact.correction.id) && roots.get(impact.correction.id) !== root
      || excerpts.has(impact.correction.excerptId) && excerpts.get(impact.correction.excerptId) !== root) return false
    ids.add(impact.id); edges.add(edge); roots.set(impact.correction.id, root); excerpts.set(impact.correction.excerptId, root); previous = position
  }
  if (page.nextCursor === null) return true
  const next = cursorPosition(page.nextCursor, itemId, context, state)
  return page.items.length === 20 && Boolean(next && previous && compare(next, previous) === 0 && (!before || next.owner === before.owner))
}
export function materialKnowledgeImpactTraceValid(value: unknown, original: MaterialKnowledgeImpact,
  previous: readonly MaterialKnowledgeImpact[]): value is MaterialKnowledgeImpact {
  if (!materialKnowledgeImpactValid(original) || !materialKnowledgeImpactValid(value)) return false
  const seen = new Set<string>()
  let child: MaterialKnowledgeImpact | undefined
  // 对象可在不同真实版本的循环中重现，只按影响ID去环，并逐跳核对采用版下限。
  for (const step of [...previous, value]) {
    if (!materialKnowledgeImpactValid(step) || seen.has(step.id) || rootKey(step) !== rootKey(original)) return false
    if (!child) { if (!materialKnowledgeSameImpact(step, original)) return false }
    else if (child.parentImpactId !== step.id || child.depth !== step.depth + 1 || child.source.domain !== 'KNOWLEDGE'
      || child.source.itemId !== step.targetItemId || child.source.versionNumber === null
      || child.source.versionNumber < step.targetVersionNumber
      || (child.source.versionId === step.targetVersionId) !== (child.source.versionNumber === step.targetVersionNumber)) return false
    seen.add(step.id); child = step
  }
  return true
}
export function materialKnowledgeImpactResolveValid(value: unknown, impact: MaterialKnowledgeImpact,
  target?: KnowledgeRevalidationTarget): value is KnowledgeRevalidationResolve {
  const request = value as KnowledgeRevalidationResolve
  if (!materialKnowledgeImpactValid(impact) || impact.revision !== 0 || impact.resolution !== null
    || !fields(request, ['expectedRevision', 'expectedTargetRevision', 'expectedTargetVersionId', 'choice', 'reason', 'confirmed'])
    || request.expectedRevision !== 0 || !integer(request.expectedTargetRevision) || !revalidationIdValid(request.expectedTargetVersionId)
    || !choice(request.choice) || !revalidationReasonValid(request.reason) || request.confirmed !== true) return false
  return !target || target.itemId === impact.targetItemId && !target.unpublished
    && target.revision === request.expectedTargetRevision && target.versionId === request.expectedTargetVersionId
    && target.versionNumber >= impact.targetVersionNumber
    && (target.versionId === impact.targetVersionId) === (target.versionNumber === impact.targetVersionNumber)
    && (request.choice === 'ARCHIVED' ? target.status === 'ARCHIVED'
      : target.status === 'ACTIVE' && (request.choice !== 'PUBLISHED_UPDATE' || target.versionNumber > impact.targetVersionNumber))
}
export function materialKnowledgeImpactReceiptMatches(value: unknown, impact: MaterialKnowledgeImpact,
  request: KnowledgeRevalidationResolve): value is MaterialKnowledgeImpact {
  return materialKnowledgeImpactResolveValid(request, impact) && materialKnowledgeImpactValid(value)
    && materialKnowledgeSameImpact(value, impact) && value.revision === 1 && Boolean(value.resolution
      && value.resolution.choice === request.choice && value.resolution.reason === request.reason
      && value.resolution.targetVersionId === request.expectedTargetVersionId
      && value.resolution.targetRevision === request.expectedTargetRevision)
}
