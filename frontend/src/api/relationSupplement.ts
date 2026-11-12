import type { Relation, Schema } from './client'

export type RelationSupplementCreate = Schema['CreateRelationSupplement']
export type RelationSupplementSourceLink = Schema['RelationSupplementSourceLink']
export type RelationSupplementSource = Schema['RelationSupplementSource']
export type RelationSupplement = Schema['RelationSupplement']
export type RelationSupplementPage = Schema['RelationSupplementPage']

export const relationSupplementPaths = {
  match: (sourceId: string, targetId: string, type: Relation['type']) =>
    `/knowledge/relations/match?${new URLSearchParams({ sourceId, targetId, type })}`,
  create: (relationId: string) => `/knowledge/relations/${encodeURIComponent(relationId)}/supplements`,
  page: (relationId: string, cursor?: string, limit = 20) => {
    const query = new URLSearchParams({ limit: String(limit) })
    if (cursor !== undefined) query.set('cursor', cursor)
    return `/knowledge/relations/${encodeURIComponent(relationId)}/supplements?${query}`
  },
} as const

const fields = (value: unknown, keys: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && keys.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => keys.includes(key)))
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown, minimum: number, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const types = ['PRECEDES', 'PART_OF', 'APPLIED_IN', 'RELATED', 'CONTRASTS']
const symmetric = (type: string) => type === 'RELATED' || type === 'CONTRASTS'
const tupleValid = (sourceId: unknown, targetId: unknown, type: unknown): boolean =>
  uuid(sourceId) && uuid(targetId) && sourceId !== targetId && typeof type === 'string' && types.includes(type)
  && (!symmetric(type) || sourceId < targetId)
const reasonValid = (value: unknown): value is string => typeof value === 'string' && value.length <= 1000
  && !/[\u0000\uD800-\uDFFF]/u.test(value) && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value)
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))

export function relationMatchValid(value: unknown, sourceId: string, targetId: string, type: Relation['type']): value is Relation | null {
  const swap = symmetric(type) && sourceId > targetId
  const source = swap ? targetId : sourceId, target = swap ? sourceId : targetId
  if (!tupleValid(source, target, type)) return false
  if (value === null) return true
  const relation = value as Relation
  return fields(relation, ['id', 'sourceId', 'targetId', 'type']) && uuid(relation.id)
    && relation.sourceId === source && relation.targetId === target && relation.type === type
}

export function relationSupplementProblem(value: unknown): string {
  const request = value as RelationSupplementCreate
  if (!fields(request, ['reason', 'sources', 'confirmed'])) return '补充请求的字段不完整，请重新核对。'
  if (!reasonValid(request.reason)) return '理由须为 1–1000 个 UTF-16 码元，不能只有空白，也不能含 NUL 或不完整字符。'
  if (!Array.isArray(request.sources) || request.sources.length > 5
    || request.sources.some(source => !fields(source, ['itemId', 'versionId']) || !uuid(source.itemId) || !uuid(source.versionId))
    || new Set(request.sources.map(source => source.itemId)).size !== request.sources.length) return '最多选择 5 条不同知识，并固定各自的已发布版本。'
  return request.confirmed === true ? '' : '请确认已审阅补充理由与所选来源。'
}

export function relationSupplementSourceValid(value: unknown): value is RelationSupplementSource {
  const source = value as RelationSupplementSource
  // 来源标题沿用知识发布时的 UTF-16 长度与 Java isBlank 规则，不能以新理由规则否定旧版本。
  return fields(source, ['itemId', 'versionId', 'number', 'title', 'kind', 'state']) && uuid(source.itemId) && uuid(source.versionId)
    && integer(source.number, 1, 2147483647) && typeof source.title === 'string' && source.title.length <= 200
    && /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(source.title)
    && ['NOTE', 'CONCEPT'].includes(source.kind) && ['CURRENT', 'SUPERSEDED', 'ARCHIVED', 'UNAVAILABLE'].includes(source.state)
}

export function relationSupplementValid(value: unknown, relationId?: string): value is RelationSupplement {
  const entry = value as RelationSupplement
  if (!fields(entry, ['id', 'relationId', 'sourceId', 'targetId', 'type', 'origin', 'reason', 'sources', 'taskId', 'candidateId', 'createdAt'])
    || !uuid(entry.id) || !uuid(entry.relationId) || relationId !== undefined && entry.relationId !== relationId
    || !tupleValid(entry.sourceId, entry.targetId, entry.type) || !reasonValid(entry.reason) || !timestamp(entry.createdAt)
    || !Array.isArray(entry.sources) || entry.sources.length > 5 || !entry.sources.every(relationSupplementSourceValid)
    || new Set(entry.sources.map(source => source.itemId)).size !== entry.sources.length) return false
  if (entry.origin === 'MANUAL') return entry.taskId === null && entry.candidateId === null
  return entry.origin === 'AI' && uuid(entry.taskId) && typeof entry.candidateId === 'string' && /^r([1-9]|10)$/.test(entry.candidateId)
    && entry.sources.length >= 2 && [entry.sourceId, entry.targetId].every(id => entry.sources.some(source => source.itemId === id && source.kind === 'CONCEPT'))
}

export function relationSupplementPageValid(value: unknown, relationId: string, cursor?: string, limit = 20): value is RelationSupplementPage {
  const page = value as RelationSupplementPage
  if (!uuid(relationId) || cursor !== undefined && !uuid(cursor) || !integer(limit, 1, 20)
    || !fields(page, ['relationId', 'relationExists', 'items', 'nextCursor']) || page.relationId !== relationId
    || typeof page.relationExists !== 'boolean' || !Array.isArray(page.items) || page.items.length > limit
    || page.nextCursor !== null && !uuid(page.nextCursor)) return false
  let previous = cursor
  let relation: string | undefined
  for (const entry of page.items) {
    if (!relationSupplementValid(entry, relationId) || previous !== undefined && entry.id <= previous) return false
    const identity = JSON.stringify([entry.sourceId, entry.targetId, entry.type])
    if (relation !== undefined && identity !== relation) return false
    relation = identity; previous = entry.id
  }
  // 删除边后仍可读取固定台账；翻到末页允许空页，但后续游标必须来自一整页的最后一项。
  return (page.relationExists || cursor !== undefined || page.items.length > 0)
    && (page.nextCursor === null || page.items.length === limit && page.nextCursor === page.items.at(-1)?.id)
}

export function relationSupplementMatches(value: unknown, relationId: string, request: RelationSupplementCreate): value is RelationSupplement {
  if (relationSupplementProblem(request) || !relationSupplementValid(value, relationId)) return false
  // 幂等恢复核对原请求；来源后来改版或归档不改变已完成命令的身份。
  return value.origin === 'MANUAL' && value.reason === request.reason && value.sources.length === request.sources.length
    && value.sources.every((source, index) => source.itemId === request.sources[index]!.itemId && source.versionId === request.sources[index]!.versionId)
}
