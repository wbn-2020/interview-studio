import type { Relation, Schema } from './client'
import { aiRelationEvidenceValidationNote } from './aiRelationEvidence'
import { aiRelationReviewValidationNote } from './aiRelationReview'
import { aiRelationAiFeedbackValidationNote } from './aiRelationAiFeedback'

export type RelationGraphEdge = Schema['RelationGraphEdge']
export type RelationGraphProjection = Schema['RelationGraphProjection']
export type RelationGraphTaskPage = Schema['AiTaskPage']
export type RelationGraphTaskSummary = Schema['AiSummary']
export type RelationGraphSource = RelationGraphProjection['sources'][number]
export type RelationGraphCandidateIdentity = { taskId: string; candidateId: string }
export type RelationGraphCandidateEdge = RelationGraphCandidateIdentity & { sourceId: string; targetId: string; type: Relation['type'] }

export const relationGraphPaths = {
  tasks: (cursor?: string, limit = 20) => {
    const query = new URLSearchParams({ limit: String(limit) })
    if (cursor !== undefined) query.set('cursor', cursor)
    return `/ai/relation-drafts/tasks?${query}`
  },
  projection: (taskId: string) => `/ai/tasks/${encodeURIComponent(taskId)}/relation-graph`,
} as const

const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && names.every(name => Object.hasOwn(value, name)) && Object.keys(value).every(name => names.includes(name)))
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
// 已存来源和候选沿原 Java isBlank／UTF-16 规则，不因只读星图投影重新裁剪原文。
const text = (value: unknown, maximum: number): value is string => typeof value === 'string' && value.length <= maximum
  && !/[\u0000\uD800-\uDFFF]/u.test(value)
  && /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const candidateId = (value: unknown): value is string => typeof value === 'string' && /^r([1-9]|10)$/.test(value)
const relationTypes = ['PRECEDES', 'PART_OF', 'APPLIED_IN', 'RELATED', 'CONTRASTS']
const validationNote = 'AI 未审关系候选，结构、所选概念端点与引用原文区间已核对；不证明关系语义正确或原文充分支持。候选需逐项审阅，不会自动进入正式星图。'

export function relationGraphSummaryValid(value: unknown, taskId?: string): value is RelationGraphTaskSummary {
  const task = value as RelationGraphTaskSummary
  return fields(task, ['id', 'purpose', 'status', 'revision', 'title', 'provider', 'model', 'currency', 'attemptCount', 'createdAt', 'updatedAt', 'errorCode'])
    && uuid(task.id) && (taskId === undefined || task.id === taskId) && task.purpose === 'RELATION_DRAFT' && task.status === 'SUCCEEDED'
    && integer(task.revision) && text(task.title, 200) && text(task.provider, 240) && Array.from(task.provider).length <= 120
    && text(task.model, 400) && Array.from(task.model).length <= 200 && typeof task.currency === 'string' && /^[A-Z]{3}$/.test(task.currency)
    && integer(task.attemptCount, 1, 3) && timestamp(task.createdAt) && timestamp(task.updatedAt)
    && (task.errorCode === null || text(task.errorCode, Number.MAX_SAFE_INTEGER))
}

export function relationGraphPageValid(value: unknown, cursor?: string, limit = 20): value is RelationGraphTaskPage {
  const page = value as RelationGraphTaskPage
  if (cursor !== undefined && !uuid(cursor) || !integer(limit, 1, 20) || !fields(page, ['items', 'nextCursor'])
    || !Array.isArray(page.items) || page.items.length > limit || page.nextCursor !== null && !uuid(page.nextCursor)) return false
  let previous = cursor
  for (const task of page.items) {
    if (!relationGraphSummaryValid(task) || previous !== undefined && task.id <= previous) return false
    previous = task.id
  }
  return page.nextCursor === null || page.items.length === limit && page.nextCursor === page.items.at(-1)?.id
}

function sourceValid(value: unknown): value is RelationGraphSource {
  const source = value as RelationGraphSource
  return fields(source, ['itemId', 'versionId', 'number', 'title', 'state', 'kind']) && uuid(source.itemId) && uuid(source.versionId)
    && integer(source.number, 1, 2147483647) && text(source.title, 200) && (source.kind === 'NOTE' || source.kind === 'CONCEPT')
    && ['CURRENT', 'SUPERSEDED', 'ARCHIVED', 'UNAVAILABLE'].includes(source.state)
}

export function relationGraphProjectionValid(value: unknown, taskId: string): value is RelationGraphProjection {
  const projection = value as RelationGraphProjection
  const candidateText = (value: unknown, maximum: number) => text(value, maximum)
    && (projection.validationNote !== aiRelationEvidenceValidationNote && projection.validationNote !== aiRelationReviewValidationNote
      && projection.validationNote !== aiRelationAiFeedbackValidationNote
      || /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value))
  if (!uuid(taskId) || !fields(projection, ['task', 'sourcesCurrent', 'sources', 'summary', 'limitations', 'validationNote', 'totalCandidates', 'decidedCount', 'relations'])
    || !relationGraphSummaryValid(projection.task, taskId) || typeof projection.sourcesCurrent !== 'boolean'
    || !candidateText(projection.summary, 1000) || !candidateText(projection.limitations, 2000)
    || ![validationNote, aiRelationEvidenceValidationNote, aiRelationReviewValidationNote, aiRelationAiFeedbackValidationNote].includes(projection.validationNote)
    || !Array.isArray(projection.sources) || projection.sources.length < 2 || projection.sources.length > 5 || !projection.sources.every(sourceValid)
    || projection.sourcesCurrent && projection.sources.some(source => source.state !== 'CURRENT')
    || new Set(projection.sources.map(source => source.itemId)).size !== projection.sources.length
    || new Set(projection.sources.map(source => source.versionId)).size !== projection.sources.length
    || projection.task.title !== projection.sources[0]?.title || projection.sources.filter(source => source.kind === 'CONCEPT').length < 2
    || !integer(projection.totalCandidates, 0, 10) || !integer(projection.decidedCount, 0, 10)
    || !Array.isArray(projection.relations) || projection.relations.length > 10
    || projection.totalCandidates !== projection.decidedCount + projection.relations.length) return false
  const concepts = new Set(projection.sources.filter(source => source.kind === 'CONCEPT').map(source => source.itemId))
  const identities = new Set<string>()
  let previousOrdinal = 0
  for (const edge of projection.relations) {
    if (!fields(edge, ['id', 'sourceItemId', 'targetItemId', 'type', 'reason', 'citationCount']) || !candidateId(edge.id)
      || !concepts.has(edge.sourceItemId) || !concepts.has(edge.targetItemId) || edge.sourceItemId === edge.targetItemId
      || !relationTypes.includes(edge.type) || !candidateText(edge.reason, 1000) || !integer(edge.citationCount, 1, 6)
      || ['RELATED', 'CONTRASTS'].includes(edge.type) && edge.sourceItemId > edge.targetItemId) return false
    const ordinal = Number(edge.id.slice(1)), identity = JSON.stringify([edge.sourceItemId, edge.targetItemId, edge.type])
    // 过滤已决定项后保留原 rN；顺序可以有空缺，不能按剩余项重新编号或重复投影。
    if (ordinal <= previousOrdinal || ordinal > projection.totalCandidates || identities.has(identity)) return false
    previousOrdinal = ordinal; identities.add(identity)
  }
  return true
}

export function relationGraphCandidateIdentityValid(value: unknown): value is RelationGraphCandidateIdentity {
  const identity = value as RelationGraphCandidateIdentity
  return fields(identity, ['taskId', 'candidateId']) && uuid(identity.taskId) && candidateId(identity.candidateId)
}

export function relationGraphEdgeId(taskId: string, id: string): string {
  // 候选仅有本地显示身份，不能与正式关系 UUID 共用命名空间。
  return `candidate:${taskId}:${id}`
}

export function relationGraphCandidateEdges(projection: RelationGraphProjection): RelationGraphCandidateEdge[] {
  return projection.relations.map(edge => ({ taskId: projection.task.id, candidateId: edge.id,
    sourceId: edge.sourceItemId, targetId: edge.targetItemId, type: edge.type }))
}

export function relationGraphProjectionFingerprint(projection: RelationGraphProjection): string {
  const task = projection.task
  // 保留来源顺序和确切版本；可用性只参与显示刷新，不代表候选已采用或语义已核实。
  return JSON.stringify([[task.id, task.purpose, task.status, task.revision, task.title, task.provider, task.model, task.currency,
    task.attemptCount, task.createdAt, task.updatedAt, task.errorCode], projection.sourcesCurrent,
  projection.sources.map(source => [source.itemId, source.versionId, source.number, source.title, source.state, source.kind]),
  projection.summary, projection.limitations, projection.validationNote, projection.totalCandidates, projection.decidedCount,
  projection.relations.map(edge => [edge.id, edge.sourceItemId, edge.targetItemId, edge.type, edge.reason, edge.citationCount])])
}
