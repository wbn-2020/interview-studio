import type { MaterialCorrectionStatement } from './materialCorrection'
import { materialCorrectionStatementValid } from './materialCorrection'
import { revalidationIdValid, revalidationReasonValid } from './knowledgeRevalidation'

export type MaterialDependencyState = 'PENDING' | 'ALL'
export type MaterialDependencyDomain = 'PREPARATION' | 'QUESTION' | 'RELATION' | 'STANDARD_REVIEW'
export type MaterialDependencyBinding = 'PREPARATION_REFERENCE' | 'QUESTION_ADOPTION' | 'FACT_ADOPTION'
  | 'RELATION_ADOPTION' | 'RELATION_EVIDENCE' | 'REVIEW_EVIDENCE'
export type MaterialDependencyChoice = 'KEEP_CURRENT' | 'UPDATED' | 'ARCHIVED' | 'WITHDRAWN' | 'REMOVED'
export type MaterialDependencyTarget = {
  domain: MaterialDependencyDomain; id: string; versionId: string | null; versionNumber: number | null
  revision: number | null; title: string
}
export type MaterialDependencyCurrentTarget = MaterialDependencyTarget & { status: 'ACTIVE' | 'ARCHIVED' | 'WITHDRAWN' | 'REMOVED' }
export type MaterialDependencyResolution = {
  choice: MaterialDependencyChoice; reason: string; targetVersionId: string | null; targetRevision: number | null; decidedAt: string
}
export type MaterialDependencyImpact = {
  id: string; revision: number; correction: MaterialCorrectionStatement; binding: MaterialDependencyBinding
  bindingId: string; taskId: string | null; target: MaterialDependencyTarget; currentTarget: MaterialDependencyCurrentTarget
  createdAt: string; resolution: MaterialDependencyResolution | null
}
export type MaterialDependencyImpactPage = {
  excerptId: string; state: MaterialDependencyState; items: MaterialDependencyImpact[]; nextCursor: string | null
}
export type MaterialDependencyResolve = {
  expectedRevision: number; expectedTargetVersionId: string | null; expectedTargetRevision: number | null
  choice: MaterialDependencyChoice; reason: string; confirmed: boolean
}

export const materialDependencyStateLabels: Record<MaterialDependencyState, string> = { PENDING: '待处理', ALL: '全部记录' }
export const materialDependencyDomainLabels: Record<MaterialDependencyDomain, string> = {
  PREPARATION: '准备材料', QUESTION: '题目', RELATION: '知识关系', STANDARD_REVIEW: '标准审阅',
}
export const materialDependencyBindingLabels: Record<MaterialDependencyBinding, string> = {
  PREPARATION_REFERENCE: '准备材料直接引用', QUESTION_ADOPTION: 'AI 题目采用', FACT_ADOPTION: 'AI 项目事实采用',
  RELATION_ADOPTION: 'AI 关系采用', RELATION_EVIDENCE: '关系证据', REVIEW_EVIDENCE: '标准审阅证据',
}
export const materialDependencyChoiceLabels: Record<MaterialDependencyChoice, string> = {
  KEEP_CURRENT: '核对后保留当前内容', UPDATED: '目标已完成实际更新', ARCHIVED: '目标已归档',
  WITHDRAWN: '审阅已撤回', REMOVED: '关系已移除',
}
export const materialDependencyStatusLabels = { ACTIVE: '有效', ARCHIVED: '已归档', WITHDRAWN: '已撤回', REMOVED: '已移除' }
export const materialDependencyChoices: MaterialDependencyChoice[] = ['KEEP_CURRENT', 'UPDATED', 'ARCHIVED', 'WITHDRAWN', 'REMOVED']
export const materialDependencyImpactPaths = {
  page: (excerptId: string, state: MaterialDependencyState, cursor?: string): string => {
    const query = new URLSearchParams({ state, limit: '20' })
    if (cursor !== undefined) query.set('cursor', cursor)
    return `/lifecycle/material-excerpts/${encodeURIComponent(excerptId)}/dependency-impacts?${query}`
  },
  detail: (impactId: string): string => `/lifecycle/material-dependency-impacts/${encodeURIComponent(impactId)}`,
  resolve: (impactId: string): string => `/lifecycle/material-dependency-impacts/${encodeURIComponent(impactId)}/resolve`,
}

const fields = (value: unknown, names: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object'
  && !Array.isArray(value) && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const title = (value: unknown): value is string =>
  typeof value === 'string' && value.length > 0 && Array.from(value).length <= 200 && !/[\uD800-\uDFFF]/u.test(value)
const binding = (value: unknown): value is MaterialDependencyBinding => typeof value === 'string' && Object.hasOwn(materialDependencyBindingLabels, value)
const domain = (value: unknown): value is MaterialDependencyDomain => typeof value === 'string' && Object.hasOwn(materialDependencyDomainLabels, value)
const choice = (value: unknown): value is MaterialDependencyChoice => typeof value === 'string' && Object.hasOwn(materialDependencyChoiceLabels, value)
export const materialDependencyStateValid = (value: unknown): value is MaterialDependencyState => value === 'PENDING' || value === 'ALL'
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(value)) return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19)
}
const micros = (value: string) => `${value.slice(0, 19)}.${value.slice(19, -1).replace('.', '').padEnd(6, '0')}Z`
function instant(value: string): string {
  const fraction = micros(value).slice(20, 26)
  return `${value.slice(0, 19)}${fraction === '000000' ? '' : `.${fraction.endsWith('000') ? fraction.slice(0, 3) : fraction}`}Z`
}
function targetValid(value: unknown): value is MaterialDependencyTarget {
  if (!fields(value, ['domain', 'id', 'versionId', 'versionNumber', 'revision', 'title'])
    || !domain(value.domain) || !revalidationIdValid(value.id) || !title(value.title)) return false
  if (value.domain === 'PREPARATION' || value.domain === 'QUESTION')
    return revalidationIdValid(value.versionId) && integer(value.versionNumber, 1, 2147483647) && value.revision === null
  return value.versionId === null && value.versionNumber === null && (value.domain === 'RELATION' ? value.revision === null : integer(value.revision))
}
function currentTargetValid(value: unknown, original: MaterialDependencyTarget): value is MaterialDependencyCurrentTarget {
  if (!fields(value, ['domain', 'id', 'versionId', 'versionNumber', 'revision', 'status', 'title'])
    || value.domain !== original.domain || value.id !== original.id || !title(value.title)) return false
  if (value.domain === 'PREPARATION' || value.domain === 'QUESTION')
    return revalidationIdValid(value.versionId) && integer(value.versionNumber, original.versionNumber!, 2147483647)
      && integer(value.revision) && (value.status === 'ACTIVE' || value.status === 'ARCHIVED')
      && (value.versionId === original.versionId) === (value.versionNumber === original.versionNumber)
  return value.versionId === null && value.versionNumber === null && (value.domain === 'RELATION'
    ? value.revision === null && (value.status === 'ACTIVE' || value.status === 'REMOVED')
    : integer(value.revision, original.revision!) && (value.status === 'ACTIVE' || value.status === 'WITHDRAWN'))
}
function bindingValid(value: MaterialDependencyImpact): boolean {
  if (typeof value.bindingId !== 'string' || !value.bindingId || value.bindingId.length > 120
    || /[\u0000\uD800-\uDFFF]/u.test(value.bindingId)) return false
  switch (value.binding) {
    case 'PREPARATION_REFERENCE': return value.target.domain === 'PREPARATION' && value.taskId === null && value.bindingId === value.target.versionId
    case 'QUESTION_ADOPTION': return value.target.domain === 'QUESTION' && revalidationIdValid(value.taskId) && value.bindingId === value.taskId
    case 'FACT_ADOPTION': return value.target.domain === 'PREPARATION' && revalidationIdValid(value.taskId) && value.bindingId === value.taskId
    case 'RELATION_ADOPTION': return value.target.domain === 'RELATION' && revalidationIdValid(value.taskId)
      && value.bindingId.startsWith(`${value.taskId}~`) && value.bindingId.length > value.taskId.length + 1
    case 'RELATION_EVIDENCE': return value.target.domain === 'RELATION' && value.taskId === null && revalidationIdValid(value.bindingId)
    case 'REVIEW_EVIDENCE': {
      const prefix = `${value.target.id}~${value.target.revision}~`
      return value.target.domain === 'STANDARD_REVIEW' && value.taskId === null && value.bindingId.startsWith(prefix) && value.bindingId.length > prefix.length
    }
  }
}
function resolutionValid(value: unknown, impact: MaterialDependencyImpact): value is MaterialDependencyResolution {
  if (!fields(value, ['choice', 'reason', 'targetVersionId', 'targetRevision', 'decidedAt']) || !choice(value.choice)
    || !revalidationReasonValid(value.reason) || !timestamp(value.decidedAt) || micros(value.decidedAt) < micros(impact.createdAt)) return false
  switch (impact.target.domain) {
    case 'PREPARATION': case 'QUESTION': return ['KEEP_CURRENT', 'UPDATED', 'ARCHIVED'].includes(value.choice)
      && revalidationIdValid(value.targetVersionId) && integer(value.targetRevision) && value.targetRevision <= impact.currentTarget.revision!
    case 'RELATION': return ['KEEP_CURRENT', 'REMOVED'].includes(value.choice) && value.targetVersionId === null && value.targetRevision === null
    case 'STANDARD_REVIEW': return ['KEEP_CURRENT', 'UPDATED', 'WITHDRAWN'].includes(value.choice) && value.targetVersionId === null
      && integer(value.targetRevision, impact.target.revision!) && value.targetRevision <= impact.currentTarget.revision!
  }
}
export function materialDependencyImpactValid(value: unknown, excerptId?: string): value is MaterialDependencyImpact {
  const impact = value as MaterialDependencyImpact
  if (!fields(impact, ['id', 'revision', 'correction', 'binding', 'bindingId', 'taskId', 'target', 'currentTarget', 'createdAt', 'resolution'])
    || !revalidationIdValid(impact.id) || !integer(impact.revision, 0, 1) || !binding(impact.binding)
    || !targetValid(impact.target) || !currentTargetValid(impact.currentTarget, impact.target) || !timestamp(impact.createdAt)
    || !impact.correction || !materialCorrectionStatementValid(impact.correction, excerptId ?? impact.correction.excerptId)
    || micros(impact.createdAt) !== micros(impact.correction.createdAt) || !bindingValid(impact)) return false
  return impact.revision === 0 ? impact.resolution === null : resolutionValid(impact.resolution, impact)
}
const correctionKey = (value: MaterialCorrectionStatement) => JSON.stringify([value.id, value.excerptId, value.replacementExcerptId,
  value.sourceRevision, value.replacementRevision, value.reason, micros(value.createdAt)])
const targetKey = (value: MaterialDependencyTarget) =>
  [value.domain, value.id, value.versionId, value.versionNumber, value.revision, value.title]
export const materialDependencyCurrentKey = (value: MaterialDependencyCurrentTarget): string => JSON.stringify([...targetKey(value), value.status])
export function materialDependencySameImpact(left: MaterialDependencyImpact, right: MaterialDependencyImpact): boolean {
  return materialDependencyImpactValid(left) && materialDependencyImpactValid(right) && correctionKey(left.correction) === correctionKey(right.correction)
    && JSON.stringify([left.id, left.binding, left.bindingId, left.taskId, targetKey(left.target), micros(left.createdAt)])
    === JSON.stringify([right.id, right.binding, right.bindingId, right.taskId, targetKey(right.target), micros(right.createdAt)])
}
type Position = { owner: string; time: string; id: string }
function cursorPosition(value: unknown, excerptId: string, state: MaterialDependencyState): Position | undefined {
  if (typeof value !== 'string' || value.length > 200) return undefined
  const parts = value.split('~')
  if (parts.length !== 5 || !revalidationIdValid(parts[0]) || parts[1] !== excerptId || parts[2] !== state
    || !timestamp(parts[3]) || instant(parts[3]) !== parts[3] || !revalidationIdValid(parts[4])) return undefined
  return { owner: parts[0], time: micros(parts[3]), id: parts[4] }
}
const compare = (left: { time: string; id: string }, right: { time: string; id: string }) =>
  left.time < right.time ? -1 : left.time > right.time ? 1 : left.id < right.id ? -1 : left.id > right.id ? 1 : 0
export function materialDependencyImpactPageValid(value: unknown, excerptId: string, state: MaterialDependencyState,
  cursor?: string): value is MaterialDependencyImpactPage {
  const page = value as MaterialDependencyImpactPage, before = cursor === undefined ? undefined : cursorPosition(cursor, excerptId, state)
  if (!revalidationIdValid(excerptId) || !materialDependencyStateValid(state) || cursor !== undefined && !before
    || !fields(page, ['excerptId', 'state', 'items', 'nextCursor']) || page.excerptId !== excerptId || page.state !== state
    || !Array.isArray(page.items) || page.items.length > 20) return false
  const ids = new Set<string>(), bindings = new Set<string>()
  let previous: { time: string; id: string } | undefined = before, root: string | undefined
  for (const impact of page.items) {
    if (!materialDependencyImpactValid(impact, excerptId) || ids.has(impact.id) || state === 'PENDING' && impact.revision !== 0) return false
    const position = { time: micros(impact.createdAt), id: impact.id }, bindingKey = `${impact.binding}~${impact.bindingId}`
    const nextRoot = correctionKey(impact.correction)
    if (previous && compare(position, previous) >= 0 || bindings.has(bindingKey) || root !== undefined && root !== nextRoot) return false
    ids.add(impact.id); bindings.add(bindingKey); root = nextRoot; previous = position
  }
  if (page.nextCursor === null) return true
  const next = cursorPosition(page.nextCursor, excerptId, state)
  return page.items.length === 20 && Boolean(next && previous && compare(next, previous) === 0 && (!before || next.owner === before.owner))
}
export function materialDependencyChoiceAvailable(impact: MaterialDependencyImpact, value: MaterialDependencyChoice): boolean {
  const target = impact.target, current = impact.currentTarget
  if (impact.revision !== 0 || impact.resolution !== null) return false
  if (target.domain === 'PREPARATION' || target.domain === 'QUESTION')
    return value === 'KEEP_CURRENT' && current.status === 'ACTIVE' || value === 'ARCHIVED' && current.status === 'ARCHIVED'
      || value === 'UPDATED' && current.status === 'ACTIVE' && current.versionNumber! > target.versionNumber!
  if (target.domain === 'RELATION') return value === 'KEEP_CURRENT' && current.status === 'ACTIVE' || value === 'REMOVED' && current.status === 'REMOVED'
  return value === 'KEEP_CURRENT' && current.status === 'ACTIVE' || value === 'WITHDRAWN' && current.status === 'WITHDRAWN'
    || value === 'UPDATED' && current.status === 'ACTIVE' && current.revision! > target.revision!
}
export function materialDependencyResolveValid(value: unknown, original: MaterialDependencyImpact): value is MaterialDependencyResolve {
  const request = value as MaterialDependencyResolve
  return materialDependencyImpactValid(original) && fields(request,
    ['expectedRevision', 'expectedTargetVersionId', 'expectedTargetRevision', 'choice', 'reason', 'confirmed'])
    && request.expectedRevision === 0 && request.expectedRevision === original.revision && original.resolution === null
    && request.expectedTargetVersionId === original.currentTarget.versionId && request.expectedTargetRevision === original.currentTarget.revision
    && choice(request.choice) && materialDependencyChoiceAvailable(original, request.choice) && revalidationReasonValid(request.reason) && request.confirmed === true
}
export function materialDependencyReceiptMatches(value: unknown, original: MaterialDependencyImpact,
  request: MaterialDependencyResolve): value is MaterialDependencyImpact {
  return materialDependencyResolveValid(request, original) && materialDependencyImpactValid(value, original.correction.excerptId)
    && materialDependencySameImpact(value, original) && value.revision === 1 && value.resolution !== null
    && materialDependencyCurrentKey(value.currentTarget) === materialDependencyCurrentKey(original.currentTarget)
    && value.resolution.choice === request.choice && value.resolution.reason === request.reason
    && value.resolution.targetVersionId === request.expectedTargetVersionId && value.resolution.targetRevision === request.expectedTargetRevision
}
