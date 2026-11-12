import type { Schema } from './client'
import type { MaterialExcerpt } from './material'

export type RelationEvidenceTrainingSelection = Schema['RelationEvidenceTrainingSelection']
export type RelationEvidenceSelection = Schema['RelationEvidenceSelection']
export type RelationEvidenceCreate = Schema['CreateRelationEvidence']
export type RelationEvidenceTrainingInput = Schema['RelationEvidenceTrainingInput']
export type RelationEvidenceSnapshot = Schema['RelationEvidenceSnapshot']
export type RelationEvidenceEntry = Schema['RelationEvidenceEntry']
export type RelationEvidencePage = Schema['RelationEvidencePage']
export type RelationEvidenceDetail = Schema['RelationEvidenceDetail']
export type RelationEvidenceKind = RelationEvidenceSelection['kind']
export type RelationEvidenceFragment = RelationEvidenceTrainingSelection['fragments'][number]
export type RelationEvidenceMaterial = RelationEvidenceSnapshot['materials'][number]

export const relationEvidencePaths = {
  create: (relationId: string) => `/knowledge/relations/${encodeURIComponent(relationId)}/evidence`,
  page: (relationId: string, cursor?: string, limit = 20) => {
    const query = new URLSearchParams({ limit: String(limit) })
    if (cursor !== undefined) query.set('cursor', cursor)
    return `/knowledge/relations/${encodeURIComponent(relationId)}/evidence?${query}`
  },
  detail: (relationId: string, evidenceId: string) =>
    `/knowledge/relations/${encodeURIComponent(relationId)}/evidence/${encodeURIComponent(evidenceId)}`,
} as const

const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && names.every(name => Object.hasOwn(value, name)) && Object.keys(value).every(name => names.includes(name)))
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown, minimum = 0, maximum = 2147483647): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const text = (value: unknown, maximum: number): value is string => typeof value === 'string' && value.length <= maximum
  && !/[\u0000\uD800-\uDFFF]/u.test(value)
const reasonValid = (value: unknown): value is string => text(value, 1000) && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value)
// 资料与答案片段沿原存储的 UTF-16 长度及 Java isBlank 边界，不能套用新理由的空白规则。
const sourceText = (value: unknown, maximum: number): value is string => text(value, maximum)
  && /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const answerFields = ['ANSWER_TEXT', 'ANSWER_NODE_LABEL', 'ANSWER_NODE_DESCRIPTION', 'ANSWER_EDGE_LABEL']
const relationTypes = ['PRECEDES', 'PART_OF', 'APPLIED_IN', 'RELATED', 'CONTRASTS']
const entryIdentity = (entry: RelationEvidenceEntry) => JSON.stringify([entry.sourceId, entry.targetId, entry.type])

export function relationEvidenceFragmentsValid(value: unknown): value is RelationEvidenceFragment[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 8) return false
  const items = value as RelationEvidenceFragment[]
  return items.every((item, index) => fields(item, ['field', 'elementId', 'start', 'end', 'quote'])
    && answerFields.includes(item.field) && (item.field === 'ANSWER_TEXT' ? item.elementId === null : uuid(item.elementId))
    && integer(item.start) && integer(item.end, 1) && item.end > item.start && sourceText(item.quote, 8000)
    && item.end - item.start === item.quote.length
    && !items.slice(0, index).some(other => other.field === item.field && other.elementId === item.elementId
      && other.start < item.end && item.start < other.end))
}

export function relationEvidenceSelectionValid(value: unknown): value is RelationEvidenceSelection {
  const selection = value as RelationEvidenceSelection
  if (!fields(selection, ['kind', 'excerptIds', 'training']) || !Array.isArray(selection.excerptIds)) return false
  if (selection.kind === 'MATERIAL') return selection.training === null && selection.excerptIds.length >= 1 && selection.excerptIds.length <= 5
    && selection.excerptIds.every(uuid) && new Set(selection.excerptIds).size === selection.excerptIds.length
  const training = selection.training
  return selection.kind === 'TRAINING' && selection.excerptIds.length === 0 && training !== null
    && fields(training, ['sessionId', 'questionId', 'answerId', 'fragments'])
    && uuid(training.sessionId) && uuid(training.questionId) && uuid(training.answerId) && relationEvidenceFragmentsValid(training.fragments)
}

export function relationEvidenceCreateProblem(value: unknown): string {
  const request = value as RelationEvidenceCreate
  if (!fields(request, ['reason', 'source', 'confirmed'])) return '请完整提供补充理由、所选来源和确认状态。'
  if (!reasonValid(request.reason)) return '理由须为 1–1000 个 UTF-16 码元，不能只有空白，也不能含 NUL 或不完整字符。'
  if (!relationEvidenceSelectionValid(request.source)) return '请选择 1–5 条不同资料摘录，或同一确切提交的 1–8 个不重叠答案片段。'
  return request.confirmed === true ? '' : '请确认已审阅理由和本次固定保存的证据。'
}

export function relationEvidenceMaterialValid(value: unknown): value is RelationEvidenceMaterial {
  const item = value as RelationEvidenceMaterial
  if (!fields(item, ['excerptId', 'attachmentId', 'fileName', 'sha256', 'origin', 'runId', 'segmentId', 'locator', 'text', 'startOffset', 'endOffset'])
    || !uuid(item.excerptId) || !uuid(item.attachmentId) || !sourceText(item.fileName, 200)
    || typeof item.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(item.sha256)
    || !sourceText(item.locator, 500) || !sourceText(item.text, 10000)) return false
  if (item.origin === 'MANUAL') return item.runId === null && item.segmentId === null && item.startOffset === null && item.endOffset === null
  // 偏移属于原解析片段，不能把摘录自身的长度上限错当成原文绝对偏移上限。
  return item.origin === 'PARSED' && uuid(item.runId) && uuid(item.segmentId) && integer(item.startOffset) && integer(item.endOffset, 1)
    && item.endOffset > item.startOffset && item.endOffset - item.startOffset === item.text.length
}

export function relationEvidenceMaterialMatches(value: unknown, selected: MaterialExcerpt): value is RelationEvidenceMaterial {
  return relationEvidenceMaterialValid(value)
    && fields(selected, ['id', 'attachmentId', 'runId', 'segmentId', 'origin', 'locator', 'text', 'startOffset', 'endOffset', 'createdAt'])
    && timestamp(selected.createdAt) && value.excerptId === selected.id && value.attachmentId === selected.attachmentId
    && value.origin === selected.origin && value.runId === selected.runId && value.segmentId === selected.segmentId
    && value.locator === selected.locator && value.text === selected.text && value.startOffset === selected.startOffset && value.endOffset === selected.endOffset
}

export function relationEvidenceSnapshotValid(value: unknown): value is RelationEvidenceSnapshot {
  const snapshot = value as RelationEvidenceSnapshot
  if (!fields(snapshot, ['kind', 'materials', 'training']) || !Array.isArray(snapshot.materials)) return false
  if (snapshot.kind === 'MATERIAL') return snapshot.training === null && snapshot.materials.length >= 1 && snapshot.materials.length <= 5
    && snapshot.materials.every(relationEvidenceMaterialValid) && new Set(snapshot.materials.map(item => item.excerptId)).size === snapshot.materials.length
  const training = snapshot.training
  return snapshot.kind === 'TRAINING' && snapshot.materials.length === 0 && training !== null
    && fields(training, ['sessionId', 'questionId', 'answerId', 'answerNumber', 'answerCondition', 'fragments'])
    && uuid(training.sessionId) && uuid(training.questionId) && uuid(training.answerId) && integer(training.answerNumber, 1, 100)
    && ['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(training.answerCondition) && relationEvidenceFragmentsValid(training.fragments)
}

export function relationEvidenceEntryValid(value: unknown, relationId?: string, evidenceId?: string): value is RelationEvidenceEntry {
  const entry = value as RelationEvidenceEntry
  return fields(entry, ['id', 'relationId', 'sourceId', 'targetId', 'type', 'reason', 'sourceKind', 'sourceCount', 'createdAt'])
    && uuid(entry.id) && uuid(entry.relationId) && (relationId === undefined || entry.relationId === relationId)
    && (evidenceId === undefined || entry.id === evidenceId) && uuid(entry.sourceId) && uuid(entry.targetId)
    && entry.sourceId !== entry.targetId && relationTypes.includes(entry.type)
    && (!['RELATED', 'CONTRASTS'].includes(entry.type) || entry.sourceId < entry.targetId)
    && reasonValid(entry.reason) && timestamp(entry.createdAt) && ['MATERIAL', 'TRAINING'].includes(entry.sourceKind)
    && integer(entry.sourceCount, 1, entry.sourceKind === 'MATERIAL' ? 5 : 8)
}

export function relationEvidenceEntryFingerprint(entry: RelationEvidenceEntry): string {
  return JSON.stringify([entry.id, entry.relationId, entry.sourceId, entry.targetId, entry.type,
    entry.reason, entry.sourceKind, entry.sourceCount, entry.createdAt])
}

export function relationEvidenceEntryMatches(value: unknown, relationId: string, request: RelationEvidenceCreate): value is RelationEvidenceEntry {
  if (relationEvidenceCreateProblem(request) || !relationEvidenceEntryValid(value, relationId)) return false
  // 创建回执只有元数据，核对理由、来源类别和数量；具体原文须由用户展开详情后另行读取。
  return value.reason === request.reason && value.sourceKind === request.source.kind
    && value.sourceCount === (request.source.kind === 'MATERIAL' ? request.source.excerptIds.length : request.source.training!.fragments.length)
}

export function relationEvidencePageValid(value: unknown, relationId: string, cursor?: string, limit = 20): value is RelationEvidencePage {
  const page = value as RelationEvidencePage
  if (!uuid(relationId) || cursor !== undefined && !uuid(cursor) || !integer(limit, 1, 20)
    || !fields(page, ['relationId', 'relationExists', 'items', 'nextCursor']) || page.relationId !== relationId
    || typeof page.relationExists !== 'boolean' || !Array.isArray(page.items) || page.items.length > limit
    || page.nextCursor !== null && !uuid(page.nextCursor)) return false
  let previous = cursor, tuple: string | undefined
  for (const entry of page.items) {
    if (!relationEvidenceEntryValid(entry, relationId) || previous !== undefined && entry.id <= previous) return false
    const identity = entryIdentity(entry)
    if (tuple !== undefined && tuple !== identity) return false
    tuple = identity; previous = entry.id
  }
  return (page.relationExists || cursor !== undefined || page.items.length > 0)
    && (page.nextCursor === null || page.items.length === limit && page.nextCursor === page.items.at(-1)?.id)
}

export function relationEvidenceDetailValid(value: unknown, relationId: string, evidenceId: string, expectedEntry?: RelationEvidenceEntry): value is RelationEvidenceDetail {
  const detail = value as RelationEvidenceDetail
  if (!uuid(relationId) || !uuid(evidenceId) || !fields(detail, ['entry', 'relationExists', 'source', 'sourceAvailable'])
    || !relationEvidenceEntryValid(detail.entry, relationId, evidenceId) || typeof detail.relationExists !== 'boolean'
    || typeof detail.sourceAvailable !== 'boolean' || !relationEvidenceSnapshotValid(detail.source)
    || expectedEntry !== undefined && (!relationEvidenceEntryValid(expectedEntry, relationId, evidenceId)
      || relationEvidenceEntryFingerprint(detail.entry) !== relationEvidenceEntryFingerprint(expectedEntry))) return false
  // 可用性只是本次读取的来源状态；不替代原文身份，不认证理由或关系语义。
  return detail.source.kind === detail.entry.sourceKind && detail.entry.sourceCount
    === (detail.source.kind === 'MATERIAL' ? detail.source.materials.length : detail.source.training!.fragments.length)
}
