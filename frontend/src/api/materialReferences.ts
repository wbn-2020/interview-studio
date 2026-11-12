import type { RouteLocationRaw } from 'vue-router'
import type { Schema } from './client'
import { aiStatusLabels, aiTaskStatusLabel } from './ai'
import type { AiPurpose, AiStatus } from './ai'
import { recordStatusLabels } from './standardReviews'

export type MaterialReferenceKind = Schema['MaterialReferenceKind']
export type MaterialReferenceSource = Schema['MaterialReferenceSource']
export type MaterialReferenceEntry = Schema['MaterialReferenceEntry']
export type MaterialReferencePage = Schema['MaterialReferencePage']

export const materialReferenceLabels: Record<MaterialReferenceKind, string> = {
  PREPARATION_VERSIONS: '准备版本引用',
  AI_KNOWLEDGE_INPUTS: 'AI 知识整理任务输入',
  AI_QUESTION_INPUTS: 'AI 出题任务输入',
  AI_MATERIAL_QA_INPUTS: 'AI 资料问答任务输入',
  AI_FACT_INPUTS: 'AI 事实候选任务输入',
  AI_RELATION_INPUTS: 'AI 关系建议任务输入',
  AI_SOURCE_QA_INPUTS: 'AI 来源问答任务输入',
  RELATION_EVIDENCE: '正式关系材料证据',
  STANDARD_REVIEW_EVIDENCE: '标准审阅材料证据',
}
const purposes = {
  AI_KNOWLEDGE_INPUTS: 'KNOWLEDGE_DRAFT', AI_QUESTION_INPUTS: 'QUESTION_DRAFT',
  AI_MATERIAL_QA_INPUTS: 'MATERIAL_QA', AI_FACT_INPUTS: 'FACT_DRAFT',
  AI_RELATION_INPUTS: 'RELATION_DRAFT', AI_SOURCE_QA_INPUTS: 'SOURCE_QA',
} satisfies Partial<Record<MaterialReferenceKind, AiPurpose>>
export const materialReferenceOptions = Object.entries(materialReferenceLabels).map(([value, label]) => ({
  value: value as MaterialReferenceKind, label,
}))
export const materialReferenceIdValid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
export const materialReferenceKindValid = (value: unknown): value is MaterialReferenceKind =>
  typeof value === 'string' && Object.hasOwn(materialReferenceLabels, value)
export function materialReferenceAiPurpose(kind: MaterialReferenceKind | undefined): AiPurpose | undefined {
  return kind && Object.hasOwn(purposes, kind) ? purposes[kind as keyof typeof purposes] : undefined
}
export const materialReferencePath = (excerptId: string, kind: MaterialReferenceKind, cursor?: string) => {
  const query = new URLSearchParams({ kind, limit: '20' })
  if (cursor !== undefined) query.set('cursor', cursor)
  return `/materials/excerpts/${encodeURIComponent(excerptId)}/references?${query}`
}
const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const positiveVersion = (value: unknown): value is number => integer(value) && value > 0 && value <= 2147483647
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(value)) return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19)
}
export function materialReferenceSourceValid(value: unknown, excerptId: string, attachmentId: string): value is MaterialReferenceSource {
  const source = value as MaterialReferenceSource
  return materialReferenceIdValid(excerptId) && materialReferenceIdValid(attachmentId)
    && fields(source, ['excerptId', 'attachmentId', 'origin', 'runId', 'segmentId', 'createdAt'])
    && source.excerptId === excerptId && source.attachmentId === attachmentId && timestamp(source.createdAt)
    && (source.origin === 'MANUAL' ? source.runId === null && source.segmentId === null
      : source.origin === 'PARSED' && materialReferenceIdValid(source.runId) && materialReferenceIdValid(source.segmentId))
}
export const materialReferenceSourceKey = (source: MaterialReferenceSource): string =>
  JSON.stringify([source.excerptId, source.attachmentId, source.origin, source.runId, source.segmentId,
    `${source.createdAt.slice(0, 19)}.${source.createdAt.slice(19, -1).replace('.', '').padEnd(6, '0')}Z`])
type Position = { referenceId: string; referenceRevision: number }
export function materialReferenceCursor(value: unknown, excerptId: string, kind: MaterialReferenceKind): Position | undefined {
  if (typeof value !== 'string' || value.length > 160) return undefined
  const parts = value.split('~')
  if (!materialReferenceIdValid(excerptId) || !materialReferenceKindValid(kind) || parts.length !== 4
    || parts[0] !== excerptId || parts[1] !== kind || !materialReferenceIdValid(parts[2])
    || !/^(?:0|[1-9]\d{0,15})$/.test(parts[3] ?? '')) return undefined
  const revision = Number(parts[3])
  if (!integer(revision) || kind !== 'STANDARD_REVIEW_EVIDENCE' && revision !== 0) return undefined
  return { referenceId: parts[2], referenceRevision: revision }
}
const compare = (left: Position, right: Position): number => left.referenceId < right.referenceId ? -1
  : left.referenceId > right.referenceId ? 1 : left.referenceRevision - right.referenceRevision
export function materialReferenceEntryValid(value: unknown, kind: MaterialReferenceKind): value is MaterialReferenceEntry {
  const entry = value as MaterialReferenceEntry
  if (!materialReferenceKindValid(kind)
    || !fields(entry, ['referenceId', 'referenceRevision', 'targetKind', 'targetId', 'targetVersionId', 'targetVersionNumber',
      'targetRevision', 'status', 'targetCurrent', 'anchorItemId', 'otherItemId', 'anchorVersionId'])
    || !materialReferenceIdValid(entry.referenceId) || !integer(entry.referenceRevision)
    || !materialReferenceIdValid(entry.targetId) || typeof entry.status !== 'string' || typeof entry.targetCurrent !== 'boolean') return false
  if (kind === 'STANDARD_REVIEW_EVIDENCE') return entry.targetKind === 'STANDARD_REVIEW'
    && entry.referenceId === entry.targetId && entry.targetRevision === entry.referenceRevision
    && entry.targetVersionId === null && entry.targetVersionNumber === null && Object.hasOwn(recordStatusLabels, entry.status)
    && materialReferenceIdValid(entry.anchorItemId) && materialReferenceIdValid(entry.anchorVersionId) && entry.otherItemId === null
  if (entry.referenceRevision !== 0 || entry.targetRevision !== null || entry.anchorVersionId !== null) return false
  if (kind === 'RELATION_EVIDENCE') return entry.targetKind === 'RELATION'
    && entry.targetVersionId === null && entry.targetVersionNumber === null && ['ACTIVE', 'REMOVED'].includes(entry.status)
    && entry.targetCurrent === (entry.status === 'ACTIVE') && materialReferenceIdValid(entry.anchorItemId)
    && materialReferenceIdValid(entry.otherItemId) && entry.anchorItemId !== entry.otherItemId
  if (entry.anchorItemId !== null || entry.otherItemId !== null) return false
  if (kind === 'PREPARATION_VERSIONS') return entry.targetKind === 'PREPARATION'
    && materialReferenceIdValid(entry.targetVersionId) && entry.referenceId === entry.targetVersionId
    && positiveVersion(entry.targetVersionNumber) && ['ACTIVE', 'ARCHIVED'].includes(entry.status)
  return Boolean(materialReferenceAiPurpose(kind)) && entry.targetKind === 'AI_TASK' && entry.referenceId === entry.targetId
    && entry.targetVersionId === null && entry.targetVersionNumber === null && entry.targetCurrent
    && Object.hasOwn(aiStatusLabels, entry.status)
}
export function materialReferencePageValid(value: unknown, excerptId: string, attachmentId: string,
  kind: MaterialReferenceKind, cursor?: string, expectedSource?: MaterialReferenceSource): value is MaterialReferencePage {
  try {
    const page = value as MaterialReferencePage
    const after = cursor === undefined ? undefined : materialReferenceCursor(cursor, excerptId, kind)
    if (!fields(page, ['kind', 'coverage', 'source', 'items', 'nextCursor']) || !materialReferenceKindValid(kind)
      || page.kind !== kind || page.coverage !== 'REGISTERED_DIRECT_ONLY'
      || !materialReferenceSourceValid(page.source, excerptId, attachmentId) || !Array.isArray(page.items)
      || page.items.length > 20 || cursor !== undefined && !after) return false
    if (expectedSource && (!materialReferenceSourceValid(expectedSource, excerptId, attachmentId)
      || materialReferenceSourceKey(expectedSource) !== materialReferenceSourceKey(page.source))) return false
    let previous = after
    // 同一审阅的不同修订必须分别保留；只按完整复合位置去重和排序。
    for (const entry of page.items) {
      if (!materialReferenceEntryValid(entry, kind) || previous && compare(previous, entry) >= 0) return false
      previous = entry
    }
    if (page.nextCursor === null) return true
    const next = materialReferenceCursor(page.nextCursor, excerptId, kind)
    return page.items.length === 20 && Boolean(next && previous && compare(previous, next) === 0)
  } catch { return false }
}
export function materialReferenceTitle(entry: MaterialReferenceEntry, kind: MaterialReferenceKind): string {
  const purpose = materialReferenceAiPurpose(kind)
  if (entry.targetKind === 'AI_TASK' && purpose) return materialReferenceLabels[kind]
  if (entry.targetKind === 'PREPARATION') return `准备版本 v${entry.targetVersionNumber}`
  return entry.targetKind === 'RELATION' ? '正式关系的材料证据' : `标准审阅修订 ${entry.targetRevision}`
}
export function materialReferenceStatusLabel(entry: MaterialReferenceEntry, kind: MaterialReferenceKind): string {
  const purpose = materialReferenceAiPurpose(kind)
  if (entry.targetKind === 'AI_TASK' && purpose && Object.hasOwn(aiStatusLabels, entry.status)) return aiTaskStatusLabel(entry.status as AiStatus, purpose)
  if (entry.targetKind === 'STANDARD_REVIEW' && Object.hasOwn(recordStatusLabels, entry.status)) {
    return `审阅当前状态：${recordStatusLabels[entry.status as keyof typeof recordStatusLabels]}`
  }
  if (entry.targetKind === 'RELATION') return entry.status === 'ACTIVE' ? '正式关系仍存在' : '正式关系已移除，保留证据'
  return entry.status === 'ARCHIVED' ? '准备对象已归档' : '准备对象有效'
}
export function materialReferenceRoute(entry: MaterialReferenceEntry): RouteLocationRaw | undefined {
  if (!materialReferenceIdValid(entry.targetId)) return undefined
  if (entry.targetKind === 'PREPARATION' && materialReferenceIdValid(entry.targetVersionId)) {
    return { name: 'prepare', query: { item: entry.targetId, version: entry.targetVersionId } }
  }
  if (entry.targetKind === 'AI_TASK') return { name: 'ai', query: { item: entry.targetId } }
  if (entry.targetKind === 'RELATION' && materialReferenceIdValid(entry.anchorItemId)) {
    return { name: 'knowledge', query: { item: entry.anchorItemId } }
  }
  if (entry.targetKind === 'STANDARD_REVIEW' && materialReferenceIdValid(entry.anchorItemId) && materialReferenceIdValid(entry.anchorVersionId)) {
    return { name: 'bank', query: { item: entry.anchorItemId, version: entry.anchorVersionId } }
  }
  return undefined
}
