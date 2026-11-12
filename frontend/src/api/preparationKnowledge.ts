import type { Schema } from './client'
import type { KnowledgeRevalidationChoice, KnowledgeRevalidationResolve, KnowledgeRevalidationState, KnowledgeRevalidationTarget } from './knowledgeRevalidation'
import { knowledgeRevalidationChoiceLabels, revalidationIdValid, revalidationReasonValid, revalidationStateValid } from './knowledgeRevalidation'
import type { PreparationKind } from './preparation'

export type PreparationKnowledgeObservation = Schema['PreparationKnowledgeObservation']
export type PreparationKnowledgeRoot = Schema['PreparationKnowledgeRoot']
export type PreparationKnowledgeChange = PreparationKnowledgeRoot['changeKind']
export type PreparationKnowledgeFact = Schema['PreparationKnowledgeFact']
export type PreparationKnowledgeSource = Schema['PreparationKnowledgeSource']
export type PreparationKnowledgeImpact = Schema['PreparationKnowledgeImpact']
export type PreparationKnowledgePage = Schema['PreparationKnowledgePage']
export type PreparationKnowledgeContext = PreparationKnowledgePage['context']

export const preparationKnowledgeChangeLabels: Record<PreparationKnowledgeChange, string> = {
  CONTENT_SAVED: '保存了新版本', STATUS_SET: '设置了归档／恢复状态',
  FACT_DECIDED: '作出了事实核实决定', EXPRESSION_BASIS_CONFIRMED: '重新确认了表达依据',
}
export const preparationKnowledgeDependencyLabels: Record<PreparationKnowledgeSource['dependencyKind'], string> = {
  DIRECT: '准备来源直接采用', PROJECT_FACT: '经项目所属事实采用', FACT_EXPRESSION: '经事实依据表达采用',
  PROJECT_EXPRESSION: '经项目事实及表达采用', KNOWLEDGE: '经上游知识采用',
}
export const preparationKnowledgePaths = {
  page: (itemId: string, context: PreparationKnowledgeContext, state: KnowledgeRevalidationState, cursor?: string) => {
    const query = new URLSearchParams({ state, limit: '20' })
    if (cursor !== undefined) query.set('cursor', cursor)
    return `${context === 'PREPARATION' ? `/lifecycle/preparation/${encodeURIComponent(itemId)}/knowledge-impacts`
      : `/lifecycle/knowledge/${encodeURIComponent(itemId)}/preparation-impacts`}?${query}`
  },
  detail: (impactId: string) => `/lifecycle/preparation-knowledge-impacts/${encodeURIComponent(impactId)}`,
  resolve: (itemId: string, impactId: string) =>
    `/lifecycle/knowledge/${encodeURIComponent(itemId)}/preparation-impacts/${encodeURIComponent(impactId)}/resolve`,
}

const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const title = (value: unknown): value is string =>
  typeof value === 'string' && value.length > 0 && Array.from(value).length <= 200 && !/[\uD800-\uDFFF]/u.test(value)
const kind = (value: unknown): value is PreparationKind =>
  typeof value === 'string' && ['TARGET', 'PROJECT', 'FACT', 'EXPRESSION'].includes(value)
const choice = (value: unknown): value is KnowledgeRevalidationChoice =>
  typeof value === 'string' && Object.hasOwn(knowledgeRevalidationChoiceLabels, value)
export const preparationKnowledgeContextValid = (value: unknown): value is PreparationKnowledgeContext => value === 'PREPARATION' || value === 'KNOWLEDGE'
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(value)) return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19)
}
const micros = (value: string) => `${value.slice(0, 19)}.${value.slice(19, -1).replace('.', '').padEnd(6, '0')}Z`
function observation(value: unknown, rootKind: PreparationKind): value is PreparationKnowledgeObservation {
  const current = value as PreparationKnowledgeObservation
  return fields(current, ['versionId', 'versionNumber', 'sourceGeneration', 'revision', 'status', 'verification'])
    && revalidationIdValid(current.versionId) && integer(current.versionNumber, 1, 2147483647)
    && integer(current.sourceGeneration) && integer(current.revision) && ['ACTIVE', 'ARCHIVED'].includes(current.status)
    && (rootKind === 'FACT' ? ['UNVERIFIED', 'CONFIRMED', 'WITHDRAWN'].includes(current.verification ?? '') : current.verification === null)
}
function rootValid(value: unknown): value is PreparationKnowledgeRoot {
  const root = value as PreparationKnowledgeRoot
  if (!fields(root, ['kind', 'itemId', 'title', 'projectId', 'changeKind', 'before', 'after'])
    || !kind(root.kind) || !revalidationIdValid(root.itemId) || !title(root.title)
    || !(root.kind === 'FACT' ? revalidationIdValid(root.projectId) : root.projectId === null)
    || !Object.hasOwn(preparationKnowledgeChangeLabels, root.changeKind)
    || !observation(root.before, root.kind) || !observation(root.after, root.kind)
    || root.before.sourceGeneration === Number.MAX_SAFE_INTEGER || root.after.sourceGeneration !== root.before.sourceGeneration + 1
    || root.before.revision === Number.MAX_SAFE_INTEGER || root.after.revision !== root.before.revision + 1) return false
  const before = root.before, after = root.after
  if (root.changeKind === 'CONTENT_SAVED') return before.status === 'ACTIVE' && after.status === 'ACTIVE'
    && after.versionNumber === before.versionNumber + 1 && after.versionId !== before.versionId
    && (root.kind === 'FACT' ? after.verification === 'UNVERIFIED' : after.verification === null)
  if (before.versionId !== after.versionId || before.versionNumber !== after.versionNumber) return false
  if (root.changeKind === 'STATUS_SET') return before.verification === after.verification
  if (root.changeKind === 'FACT_DECIDED') return root.kind === 'FACT' && before.status === after.status
    && ['CONFIRMED', 'WITHDRAWN'].includes(after.verification ?? '') && (after.verification !== 'CONFIRMED' || after.status === 'ACTIVE')
  return root.kind === 'EXPRESSION' && before.status === 'ACTIVE' && after.status === 'ACTIVE'
}
function factValid(value: unknown): value is PreparationKnowledgeFact {
  const fact = value as PreparationKnowledgeFact
  return fields(fact, ['itemId', 'title', 'versionId', 'versionNumber']) && revalidationIdValid(fact.itemId)
    && title(fact.title) && revalidationIdValid(fact.versionId) && integer(fact.versionNumber, 1, 2147483647)
}
function sourceValid(source: PreparationKnowledgeSource, root: PreparationKnowledgeRoot, depth: number, parent: string | null, id: string): boolean {
  if (!fields(source, ['domain', 'kind', 'itemId', 'title', 'versionId', 'versionNumber', 'dependencyKind', 'viaFact'])
    || !revalidationIdValid(source.itemId) || !title(source.title) || !revalidationIdValid(source.versionId)
    || !integer(source.versionNumber, 1, 2147483647)) return false
  if (depth > 1) return source.domain === 'KNOWLEDGE' && source.kind === 'KNOWLEDGE'
    && source.dependencyKind === 'KNOWLEDGE' && source.viaFact === null && revalidationIdValid(parent) && parent !== id
  if (parent !== null || source.domain !== 'PREPARATION' || !kind(source.kind)) return false
  if (source.dependencyKind === 'DIRECT') return source.itemId === root.itemId && source.kind === root.kind && source.viaFact === null
    && source.versionNumber <= root.before.versionNumber
    && (source.versionId === root.before.versionId) === (source.versionNumber === root.before.versionNumber)
  if (source.dependencyKind === 'PROJECT_FACT') return root.kind === 'PROJECT' && source.kind === 'FACT' && source.viaFact === null
  if (source.kind !== 'EXPRESSION' || !factValid(source.viaFact)) return false
  if (source.dependencyKind === 'FACT_EXPRESSION') return root.kind === 'FACT' && source.viaFact.itemId === root.itemId
    && source.viaFact.versionNumber <= root.before.versionNumber
    && (source.viaFact.versionId === root.before.versionId) === (source.viaFact.versionNumber === root.before.versionNumber)
  return source.dependencyKind === 'PROJECT_EXPRESSION' && root.kind === 'PROJECT'
}
export function preparationKnowledgeImpactValid(value: unknown): value is PreparationKnowledgeImpact {
  const impact = value as PreparationKnowledgeImpact
  if (!fields(impact, ['id', 'revision', 'eventId', 'root', 'source', 'taskId', 'targetItemId', 'targetTitle',
    'targetVersionId', 'targetVersionNumber', 'depth', 'parentImpactId', 'createdAt', 'resolution'])
    || ![impact.id, impact.eventId, impact.taskId, impact.targetItemId, impact.targetVersionId].every(revalidationIdValid)
    || !integer(impact.revision, 0, 1) || !title(impact.targetTitle) || !integer(impact.targetVersionNumber, 1, 2147483647)
    || !integer(impact.depth, 1, 2147483647) || !timestamp(impact.createdAt) || !rootValid(impact.root)
    || !sourceValid(impact.source, impact.root, impact.depth, impact.parentImpactId, impact.id)) return false
  if (impact.revision === 0) return impact.resolution === null
  const result = impact.resolution
  return fields(result, ['choice', 'reason', 'targetVersionId', 'targetRevision', 'decidedAt']) && Boolean(result
    && choice(result.choice) && revalidationReasonValid(result.reason) && revalidationIdValid(result.targetVersionId)
    && integer(result.targetRevision) && timestamp(result.decidedAt) && micros(result.decidedAt) >= micros(impact.createdAt))
}
const observationKey = (value: PreparationKnowledgeObservation) =>
  [value.versionId, value.versionNumber, value.sourceGeneration, value.revision, value.status, value.verification]
const rootKey = (impact: PreparationKnowledgeImpact) => JSON.stringify([impact.eventId, impact.root.kind, impact.root.itemId,
  impact.root.title, impact.root.projectId, impact.root.changeKind, observationKey(impact.root.before), observationKey(impact.root.after), micros(impact.createdAt)])
const sourceKey = (source: PreparationKnowledgeSource) => [source.domain, source.kind, source.itemId, source.title,
  source.versionId, source.versionNumber, source.dependencyKind, source.viaFact
    ? [source.viaFact.itemId, source.viaFact.title, source.viaFact.versionId, source.viaFact.versionNumber] : null]
export const preparationKnowledgeSameImpact = (left: PreparationKnowledgeImpact, right: PreparationKnowledgeImpact) =>
  rootKey(left) === rootKey(right) && JSON.stringify([left.id, sourceKey(left.source), left.taskId, left.targetItemId, left.targetTitle,
    left.targetVersionId, left.targetVersionNumber, left.depth, left.parentImpactId])
  === JSON.stringify([right.id, sourceKey(right.source), right.taskId, right.targetItemId, right.targetTitle,
    right.targetVersionId, right.targetVersionNumber, right.depth, right.parentImpactId])

type Position = { owner: string; time: string; id: string }
function cursorPosition(value: unknown, itemId: string, context: PreparationKnowledgeContext, state: KnowledgeRevalidationState): Position | undefined {
  if (typeof value !== 'string' || value.length > 200) return undefined
  const parts = value.split('~')
  if (parts.length !== 6 || !revalidationIdValid(parts[0]) || parts[1] !== context || parts[2] !== itemId || parts[3] !== state
    || !timestamp(parts[4]) || micros(parts[4]) !== parts[4] || !revalidationIdValid(parts[5])) return undefined
  return { owner: parts[0], time: parts[4], id: parts[5] }
}
const compare = (left: { time: string; id: string }, right: { time: string; id: string }) =>
  left.time < right.time ? -1 : left.time > right.time ? 1 : left.id < right.id ? -1 : left.id > right.id ? 1 : 0
export function preparationKnowledgePageValid(value: unknown, itemId: string, context: PreparationKnowledgeContext,
  state: KnowledgeRevalidationState, cursor?: string): value is PreparationKnowledgePage {
  const page = value as PreparationKnowledgePage, before = cursor === undefined ? undefined : cursorPosition(cursor, itemId, context, state)
  if (!revalidationIdValid(itemId) || !preparationKnowledgeContextValid(context) || !revalidationStateValid(state)
    || cursor !== undefined && !before || !fields(page, ['context', 'itemId', 'state', 'items', 'nextCursor'])
    || page.context !== context || page.itemId !== itemId || page.state !== state || !Array.isArray(page.items) || page.items.length > 20) return false
  let previous: { time: string; id: string } | undefined = before
  const ids = new Set<string>(), edges = new Set<string>(), events = new Map<string, string>()
  for (const impact of page.items) {
    if (!preparationKnowledgeImpactValid(impact) || ids.has(impact.id)
      || (context === 'PREPARATION' ? impact.root.itemId : impact.targetItemId) !== itemId
      || state === 'PENDING' && impact.resolution !== null) return false
    const position = { time: micros(impact.createdAt), id: impact.id }
    const edge = JSON.stringify([impact.eventId, impact.source.domain, impact.taskId, impact.source.itemId, impact.source.versionId])
    if (previous && compare(position, previous) >= 0 || edges.has(edge)
      || events.has(impact.eventId) && events.get(impact.eventId) !== rootKey(impact)) return false
    ids.add(impact.id); edges.add(edge); events.set(impact.eventId, rootKey(impact)); previous = position
  }
  if (page.nextCursor === null) return true
  const next = cursorPosition(page.nextCursor, itemId, context, state)
  return page.items.length === 20 && Boolean(next && previous && compare(next, previous) === 0 && (!before || next.owner === before.owner))
}
export function preparationKnowledgeTraceValid(value: unknown, original: PreparationKnowledgeImpact,
  previous: readonly PreparationKnowledgeImpact[]): value is PreparationKnowledgeImpact {
  if (!preparationKnowledgeImpactValid(original) || !preparationKnowledgeImpactValid(value)) return false
  const seen = new Set<string>()
  let child: PreparationKnowledgeImpact | undefined
  for (const step of [...previous, value]) {
    if (!preparationKnowledgeImpactValid(step) || seen.has(step.id) || rootKey(step) !== rootKey(original)) return false
    if (!child) { if (!preparationKnowledgeSameImpact(step, original)) return false }
    else if (child.parentImpactId !== step.id || child.depth !== step.depth + 1
      || child.source.domain !== 'KNOWLEDGE' || child.source.itemId !== step.targetItemId
      || child.source.versionNumber < step.targetVersionNumber
      || (child.source.versionId === step.targetVersionId) !== (child.source.versionNumber === step.targetVersionNumber)) return false
    seen.add(step.id); child = step
  }
  return true
}
export function preparationKnowledgeResolveValid(value: unknown, impact: PreparationKnowledgeImpact,
  target?: KnowledgeRevalidationTarget): value is KnowledgeRevalidationResolve {
  const request = value as KnowledgeRevalidationResolve
  if (!preparationKnowledgeImpactValid(impact) || impact.revision !== 0 || impact.resolution !== null
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
export function preparationKnowledgeReceiptMatches(value: unknown, impact: PreparationKnowledgeImpact,
  request: KnowledgeRevalidationResolve): value is PreparationKnowledgeImpact {
  return preparationKnowledgeResolveValid(request, impact) && preparationKnowledgeImpactValid(value)
    && preparationKnowledgeSameImpact(value, impact) && value.revision === 1 && Boolean(value.resolution
      && value.resolution.choice === request.choice && value.resolution.reason === request.reason
      && value.resolution.targetVersionId === request.expectedTargetVersionId
      && value.resolution.targetRevision === request.expectedTargetRevision)
}
