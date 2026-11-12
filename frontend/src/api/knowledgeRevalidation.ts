import type { ItemDetail, Schema } from './client'

export type KnowledgeRevalidationDirection = Schema['KnowledgeRevalidationDirection']
export type KnowledgeRevalidationState = Schema['KnowledgeRevalidationState']
export type KnowledgeRevalidationChangeKind = Schema['KnowledgeRevalidationChangeKind']
export type KnowledgeRevalidationChoice = Schema['KnowledgeRevalidationChoice']
export type KnowledgeRevalidationResolve = Schema['KnowledgeRevalidationResolve']
export type KnowledgeRevalidationResolution = Schema['KnowledgeRevalidationResolution']
export type KnowledgeRevalidationImpact = Schema['KnowledgeRevalidationImpact']
export type KnowledgeRevalidationPage = Schema['KnowledgeRevalidationPage']
export type KnowledgeRevalidationLineage = Schema['KnowledgeRevalidationLineage']
export type KnowledgeRevalidationTarget = {
  itemId: string; revision: number; versionId: string; versionNumber: number
  status: ItemDetail['status']; draftRevision: number; unpublished: boolean
}

export const knowledgeRevalidationDirectionLabels: Record<KnowledgeRevalidationDirection, string> = {
  TARGET: '作为采用目标', SOURCE: '作为变更源头',
}
export const knowledgeRevalidationStateLabels: Record<KnowledgeRevalidationState, string> = { PENDING: '待处理', ALL: '全部记录' }
export const knowledgeRevalidationChoiceLabels: Record<KnowledgeRevalidationChoice, string> = {
  KEEP_CURRENT: '核对后保留当前内容', PUBLISHED_UPDATE: '已发布正文更新', ARCHIVED: '目标已归档',
}
export const knowledgeRevalidationPaths = {
  page: (itemId: string, direction: KnowledgeRevalidationDirection, state: KnowledgeRevalidationState, cursor?: string) => {
    const query = new URLSearchParams({ direction, state, limit: '20' })
    if (cursor !== undefined) query.set('cursor', cursor)
    return `/lifecycle/knowledge/${encodeURIComponent(itemId)}/revalidation-impacts?${query}`
  },
  resolve: (itemId: string, impactId: string) =>
    `/lifecycle/knowledge/${encodeURIComponent(itemId)}/revalidation-impacts/${encodeURIComponent(impactId)}/resolve`,
  lineage: (itemId: string, impactId: string) =>
    `/lifecycle/knowledge/${encodeURIComponent(itemId)}/revalidation-impacts/${encodeURIComponent(impactId)}/lineage`,
}

const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
export const revalidationIdValid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
// 历史标题只作纯文本展示，不套用处理理由的空白和 NUL 限制。
const title = (value: unknown): value is string =>
  typeof value === 'string' && value.length > 0 && Array.from(value).length <= 200 && !/[\uD800-\uDFFF]/u.test(value)
export const revalidationReasonValid = (value: unknown): value is string => typeof value === 'string'
  && Array.from(value).length <= 1000 && !/[\u0000\uD800-\uDFFF]/u.test(value)
  && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value)
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(value)) return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19)
}
const micros = (value: string) => `${value.slice(0, 19)}.${(value.slice(19, -1).replace('.', '')).padEnd(6, '0')}Z`
const choiceValid = (value: unknown): value is KnowledgeRevalidationChoice =>
  typeof value === 'string' && Object.hasOwn(knowledgeRevalidationChoiceLabels, value)
export const revalidationDirectionValid = (value: unknown): value is KnowledgeRevalidationDirection =>
  typeof value === 'string' && Object.hasOwn(knowledgeRevalidationDirectionLabels, value)
export const revalidationStateValid = (value: unknown): value is KnowledgeRevalidationState =>
  typeof value === 'string' && Object.hasOwn(knowledgeRevalidationStateLabels, value)

type Position = { owner: string; time: string; id: string }
function cursorPosition(value: unknown, itemId: string, direction: KnowledgeRevalidationDirection, state: KnowledgeRevalidationState): Position | undefined {
  if (typeof value !== 'string' || value.length > 200) return undefined
  const parts = value.split('~')
  if (parts.length !== 6 || !revalidationIdValid(parts[0]) || parts[1] !== itemId || parts[2] !== direction || parts[3] !== state
    || !timestamp(parts[4]) || micros(parts[4]) !== parts[4] || !revalidationIdValid(parts[5])) return undefined
  return { owner: parts[0], time: parts[4], id: parts[5] }
}
const compare = (left: { time: string; id: string }, right: { time: string; id: string }) =>
  left.time < right.time ? -1 : left.time > right.time ? 1 : left.id < right.id ? -1 : left.id > right.id ? 1 : 0

export function knowledgeRevalidationImpactValid(value: unknown): value is KnowledgeRevalidationImpact {
  const impact = value as KnowledgeRevalidationImpact
  if (!fields(impact, ['id', 'revision', 'eventId', 'rootItemId', 'rootTitle', 'rootPreviousVersionId', 'rootCurrentVersionId',
    'changeKind', 'sourceVersionId', 'taskId', 'targetItemId', 'targetTitle', 'targetVersionId', 'targetVersionNumber', 'createdAt', 'resolution'])
    || ![impact.id, impact.eventId, impact.rootItemId, impact.rootPreviousVersionId, impact.rootCurrentVersionId,
      impact.sourceVersionId, impact.taskId, impact.targetItemId, impact.targetVersionId].every(revalidationIdValid)
    || !title(impact.rootTitle) || !title(impact.targetTitle) || !integer(impact.revision, 0, 1)
    || !integer(impact.targetVersionNumber, 1, 2147483647) || !timestamp(impact.createdAt)
    || !['BODY_CHANGED', 'ARCHIVED'].includes(impact.changeKind)
    || (impact.changeKind === 'ARCHIVED') !== (impact.rootPreviousVersionId === impact.rootCurrentVersionId)) return false
  if (impact.revision === 0) return impact.resolution === null
  const decision = impact.resolution
  return fields(decision, ['choice', 'reason', 'targetVersionId', 'targetRevision', 'decidedAt']) && Boolean(decision
    && choiceValid(decision.choice) && revalidationReasonValid(decision.reason) && revalidationIdValid(decision.targetVersionId)
    && integer(decision.targetRevision) && timestamp(decision.decidedAt) && micros(decision.decidedAt) >= micros(impact.createdAt))
}

export function knowledgeRevalidationPageValid(value: unknown, itemId: string, direction: KnowledgeRevalidationDirection,
  state: KnowledgeRevalidationState, cursor?: string): value is KnowledgeRevalidationPage {
  const page = value as KnowledgeRevalidationPage
  const before = cursor === undefined ? undefined : cursorPosition(cursor, itemId, direction, state)
  if (!revalidationIdValid(itemId) || !revalidationDirectionValid(direction) || !revalidationStateValid(state)
    || cursor !== undefined && !before || !fields(page, ['itemId', 'direction', 'state', 'items', 'nextCursor'])
    || page.itemId !== itemId || page.direction !== direction || page.state !== state
    || !Array.isArray(page.items) || page.items.length > 20) return false
  const seen = new Set<string>(), paths = new Set<string>(), events = new Map<string, string>()
  let previous: { time: string; id: string } | undefined = before
  for (const impact of page.items) {
    if (!knowledgeRevalidationImpactValid(impact) || seen.has(impact.id)
      || (direction === 'SOURCE' ? impact.rootItemId : impact.targetItemId) !== itemId
      || state === 'PENDING' && impact.resolution !== null) return false
    const position = { time: micros(impact.createdAt), id: impact.id }
    if (previous && compare(position, previous) >= 0) return false
    const path = JSON.stringify([impact.eventId, impact.taskId, impact.rootItemId, impact.sourceVersionId])
    if (paths.has(path)) return false
    const event = JSON.stringify([impact.rootItemId, impact.rootPreviousVersionId, impact.rootCurrentVersionId, impact.changeKind, position.time])
    if (events.has(impact.eventId) && events.get(impact.eventId) !== event) return false
    events.set(impact.eventId, event); seen.add(impact.id); paths.add(path); previous = position
  }
  if (page.nextCursor === null) return true
  const next = cursorPosition(page.nextCursor, itemId, direction, state)
  return page.items.length === 20 && Boolean(next && previous && compare(next, previous) === 0 && (!before || next.owner === before.owner))
}

export function knowledgeRevalidationTarget(value: ItemDetail | undefined, itemId: string): KnowledgeRevalidationTarget | undefined {
  if (!value || !revalidationIdValid(itemId) || value.id !== itemId || !integer(value.revision)
    || !['ACTIVE', 'ARCHIVED'].includes(value.status) || !revalidationIdValid(value.currentVersionId)
    || !integer(value.versionNumber, 1, 2147483647) || !value.published || !value.draft
    || value.published.id !== value.currentVersionId || value.published.number !== value.versionNumber || !integer(value.draft.revision)
    || !title(value.published.title) || !title(value.draft.title)
    || typeof value.published.body !== 'string' || value.published.body.length > 100000
    || typeof value.draft.body !== 'string' || value.draft.body.length > 100000) return undefined
  return { itemId, revision: value.revision, versionId: value.currentVersionId, versionNumber: value.versionNumber,
    status: value.status, draftRevision: value.draft.revision,
    unpublished: value.draft.title !== value.published.title || value.draft.body !== value.published.body }
}
export const knowledgeRevalidationTargetKey = (value: KnowledgeRevalidationTarget | undefined) => value ? JSON.stringify(value) : ''

export function knowledgeRevalidationResolveValid(value: unknown, impact: KnowledgeRevalidationImpact,
  target?: KnowledgeRevalidationTarget): value is KnowledgeRevalidationResolve {
  const request = value as KnowledgeRevalidationResolve
  if (!knowledgeRevalidationImpactValid(impact) || impact.revision !== 0 || impact.resolution !== null
    || !fields(request, ['expectedRevision', 'expectedTargetRevision', 'expectedTargetVersionId', 'choice', 'reason', 'confirmed'])
    || request.expectedRevision !== impact.revision || !integer(request.expectedTargetRevision) || !revalidationIdValid(request.expectedTargetVersionId)
    || !choiceValid(request.choice) || !revalidationReasonValid(request.reason) || request.confirmed !== true) return false
  if (!target) return true
  return target.itemId === impact.targetItemId && !target.unpublished
    && request.expectedTargetRevision === target.revision && request.expectedTargetVersionId === target.versionId
    && target.versionNumber >= impact.targetVersionNumber
    && (target.versionId === impact.targetVersionId) === (target.versionNumber === impact.targetVersionNumber)
    && (request.choice === 'ARCHIVED' ? target.status === 'ARCHIVED'
      : target.status === 'ACTIVE' && (request.choice !== 'PUBLISHED_UPDATE' || target.versionNumber > impact.targetVersionNumber))
}

export function knowledgeRevalidationSameImpact(left: KnowledgeRevalidationImpact, right: KnowledgeRevalidationImpact): boolean {
  return (['id', 'eventId', 'rootItemId', 'rootTitle', 'rootPreviousVersionId', 'rootCurrentVersionId', 'changeKind',
    'sourceVersionId', 'taskId', 'targetItemId', 'targetTitle', 'targetVersionId', 'targetVersionNumber'] as const)
    .every(key => left[key] === right[key]) && micros(left.createdAt) === micros(right.createdAt)
}
function lineageStepValid(value: unknown): value is KnowledgeRevalidationLineage {
  const step = value as KnowledgeRevalidationLineage
  if (!fields(step, ['impact', 'sourceItemId', 'sourceTitle', 'sourceVersionNumber', 'depth', 'parentImpactId'])
    || !knowledgeRevalidationImpactValid(step.impact) || !revalidationIdValid(step.sourceItemId) || !title(step.sourceTitle)
    || !integer(step.sourceVersionNumber, 1, 2147483647) || !integer(step.depth, 1, 2147483647)) return false
  return step.depth === 1 ? step.parentImpactId === null && step.sourceItemId === step.impact.rootItemId
    : revalidationIdValid(step.parentImpactId) && step.parentImpactId !== step.impact.id
}
export function knowledgeRevalidationLineageValid(value: unknown, itemId: string, impactId: string,
  original: KnowledgeRevalidationImpact, previous: readonly KnowledgeRevalidationLineage[] = []): value is KnowledgeRevalidationLineage {
  if (!revalidationIdValid(itemId) || !revalidationIdValid(impactId) || !knowledgeRevalidationImpactValid(original)
    || !lineageStepValid(value) || value.impact.id !== impactId
    || itemId !== value.impact.rootItemId && itemId !== value.impact.targetItemId) return false
  const seen = new Set<string>()
  let child: KnowledgeRevalidationLineage | undefined
  // 只按影响身份防环；知识对象可在真实循环或自来源采用中重复出现。
  for (const step of [...previous, value]) {
    if (!lineageStepValid(step) || seen.has(step.impact.id)) return false
    if (!child) {
      if (!knowledgeRevalidationSameImpact(step.impact, original)) return false
    } else if (child.parentImpactId !== step.impact.id || step.depth !== child.depth - 1
      || step.impact.targetItemId !== child.sourceItemId || child.sourceVersionNumber < step.impact.targetVersionNumber
      || (child.impact.sourceVersionId === step.impact.targetVersionId) !== (child.sourceVersionNumber === step.impact.targetVersionNumber)
      || !(['eventId', 'rootItemId', 'rootTitle', 'rootPreviousVersionId', 'rootCurrentVersionId', 'changeKind'] as const)
        .every(key => step.impact[key] === original[key]) || micros(step.impact.createdAt) !== micros(original.createdAt)) return false
    seen.add(step.impact.id); child = step
  }
  return previous.length === 0 || itemId === previous.at(-1)!.sourceItemId
}
export function knowledgeRevalidationReceiptMatches(value: unknown, itemId: string, impact: KnowledgeRevalidationImpact,
  request: KnowledgeRevalidationResolve): value is KnowledgeRevalidationImpact {
  if (!knowledgeRevalidationResolveValid(request, impact) || impact.targetItemId !== itemId
    || !knowledgeRevalidationImpactValid(value) || !knowledgeRevalidationSameImpact(value, impact)
    || value.revision !== 1 || !value.resolution) return false
  return value.resolution.choice === request.choice && value.resolution.reason === request.reason
    && value.resolution.targetVersionId === request.expectedTargetVersionId && value.resolution.targetRevision === request.expectedTargetRevision
}
