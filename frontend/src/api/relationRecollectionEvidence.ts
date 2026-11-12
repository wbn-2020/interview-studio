import type { Schema } from './client'
import { questionTypeLabels } from './bank'
import type { Recollection, RecollectionPage } from './recollection'
import { recollectionValid } from './recollection'

export type RelationRecollectionEvidenceSelection = Schema['AiReviewDraftRecollectionSelection']
export type RelationRecollectionEvidenceCreate = Schema['CreateRelationRecollectionEvidence']
export type RelationRecollectionEvidenceEntry = Schema['RelationRecollectionEvidenceEntry']
export type RelationRecollectionEvidencePage = Schema['RelationRecollectionEvidencePage']
export type RelationRecollectionEvidenceDetail = Schema['RelationRecollectionEvidenceDetail']
export type RelationRecollectionEvidenceField = Extract<Schema['AiReviewDraftField'], 'QUESTION' | 'CONDITIONS' | 'ANSWER_FRAGMENT' | 'FEEDBACK' | 'UNCERTAINTY' | 'GAP'>
export type RelationRecollectionEvidenceFragment = Omit<Schema['AiReviewDraftFragment'], 'field'> & { field: RelationRecollectionEvidenceField }
export type RelationRecollectionChoice = Pick<RecollectionPage['items'][number], 'id' | 'title' | 'versionId' | 'versionNumber' | 'revision' | 'baselineKind'>
export type RelationRecollectionEvidenceSource = Omit<RelationRecollectionEvidenceDetail['source']['recollection'], 'fragments'> & {
  fields: Record<RelationRecollectionEvidenceField, string>
}

export const relationRecollectionEvidenceFieldLabels: Record<RelationRecollectionEvidenceField, string> = {
  QUESTION: '回忆问题', CONDITIONS: '记得的条件', ANSWER_FRAGMENT: '回答回忆片段', FEEDBACK: '回忆的反馈', UNCERTAINTY: '不确定之处', GAP: '待补内容',
}
export const relationRecollectionEvidencePaths = {
  create: (relationId: string) => `/knowledge/relations/${encodeURIComponent(relationId)}/recollection-evidence`,
  page: (relationId: string, cursor?: string, limit = 20) => {
    const query = new URLSearchParams({ limit: String(limit) })
    if (cursor !== undefined) query.set('cursor', cursor)
    return `/knowledge/relations/${encodeURIComponent(relationId)}/recollection-evidence?${query}`
  },
  detail: (relationId: string, evidenceId: string) =>
    `/knowledge/relations/${encodeURIComponent(relationId)}/recollection-evidence/${encodeURIComponent(evidenceId)}`,
} as const

const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && names.every(name => Object.hasOwn(value, name)) && Object.keys(value).every(name => names.includes(name)))
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown, minimum = 0, maximum = 2147483647): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const text = (value: unknown, maximum: number): value is string => typeof value === 'string' && value.length <= maximum
  && !/[\u0000\uD800-\uDFFF]/u.test(value)
const hasText = (value: string) => /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
// 历史回忆沿用码点上限与 Java isBlank；新理由和原文选区按 UTF-16 校验，不裁剪或归一化。
const sourceText = (value: unknown, maximum: number, required = true): value is string => text(value, maximum * 2)
  && Array.from(value).length <= maximum && (!required || hasText(value))
const reasonValid = (value: unknown): value is string => text(value, 1000) && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value)
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const baselineValid = (value: unknown) => value === 'RECOLLECTION' || value === 'NONE'
const relationTypes = ['PRECEDES', 'PART_OF', 'APPLIED_IN', 'RELATED', 'CONTRASTS']
const fieldMaximum: Record<RelationRecollectionEvidenceField, number> = {
  QUESTION: 10000, CONDITIONS: 5000, ANSWER_FRAGMENT: 20000, FEEDBACK: 5000, UNCERTAINTY: 2000, GAP: 2000,
}

export function relationRecollectionChoiceValid(value: unknown): value is RelationRecollectionChoice {
  const choice = value as RelationRecollectionChoice
  return fields(choice, ['id', 'title', 'versionId', 'versionNumber', 'revision', 'baselineKind'])
    && uuid(choice.id) && uuid(choice.versionId) && integer(choice.revision, 0, 1000)
    && integer(choice.versionNumber, 1, 1000) && choice.versionNumber <= choice.revision + 1
    && sourceText(choice.title, 200) && baselineValid(choice.baselineKind)
}

export function relationRecollectionEvidenceSourceFrom(value: Recollection, choice: RelationRecollectionChoice): RelationRecollectionEvidenceSource | undefined {
  if (!relationRecollectionChoiceValid(choice) || !recollectionValid(value, choice.id) || value.status !== 'ACTIVE'
    || value.revision !== choice.revision || value.currentVersion.id !== choice.versionId || value.currentVersion.number !== choice.versionNumber
    || value.currentVersion.content.title !== choice.title || value.currentVersion.content.baselineKind !== choice.baselineKind) return undefined
  const content = value.currentVersion.content
  // 读取现有回忆接口后仅保留选区所需白名单；关联面试安排不进入表单来源或保存快照。
  return { recollectionId: value.id, versionId: value.currentVersion.id, versionNumber: value.currentVersion.number, sourceRevision: value.revision,
    title: content.title, type: content.type, baselineKind: content.baselineKind, uncertainty: content.uncertainty,
    fields: { QUESTION: content.question, CONDITIONS: content.conditions, ANSWER_FRAGMENT: content.answerFragment,
      FEEDBACK: content.feedback, UNCERTAINTY: content.uncertainty, GAP: content.gap } }
}

function metadataValid(value: RelationRecollectionEvidenceSource | RelationRecollectionEvidenceDetail['source']['recollection']): boolean {
  return uuid(value.recollectionId) && uuid(value.versionId) && integer(value.sourceRevision, 0, 1000)
    && integer(value.versionNumber, 1, 1000) && value.versionNumber <= value.sourceRevision + 1
    && sourceText(value.title, 200) && typeof value.type === 'string' && Object.hasOwn(questionTypeLabels, value.type)
    && baselineValid(value.baselineKind) && sourceText(value.uncertainty, 2000)
}

export function relationRecollectionEvidenceSourceValid(value: unknown): value is RelationRecollectionEvidenceSource {
  const source = value as RelationRecollectionEvidenceSource
  if (!fields(source, ['recollectionId', 'versionId', 'versionNumber', 'sourceRevision', 'title', 'type', 'baselineKind', 'uncertainty', 'fields'])
    || !metadataValid(source) || !fields(source.fields, Object.keys(relationRecollectionEvidenceFieldLabels))) return false
  return (Object.keys(fieldMaximum) as RelationRecollectionEvidenceField[]).every(field => sourceText(source.fields[field], fieldMaximum[field],
    field === 'QUESTION' || field === 'UNCERTAINTY' || field === 'ANSWER_FRAGMENT' && source.baselineKind === 'RECOLLECTION'))
    && source.fields.UNCERTAINTY === source.uncertainty && (source.baselineKind !== 'NONE' || source.fields.ANSWER_FRAGMENT === '')
}

export function relationRecollectionEvidenceSourceFingerprint(source: RelationRecollectionEvidenceSource): string {
  return JSON.stringify([source.recollectionId, source.versionId, source.versionNumber, source.sourceRevision, source.title, source.type,
    source.baselineKind, source.uncertainty, ...Object.keys(relationRecollectionEvidenceFieldLabels).map(field => source.fields[field as RelationRecollectionEvidenceField])])
}

export function relationRecollectionEvidenceRangeMatches(original: string, start: number, end: number, quote: string): boolean {
  if (!text(original, Number.MAX_SAFE_INTEGER) || !text(quote, 8000) || !hasText(quote)
    || !integer(start) || !integer(end, 1) || end <= start || end > original.length) return false
  const split = (position: number) => position > 0 && position < original.length
    && /[\uD800-\uDBFF]/.test(original.charAt(position - 1)) && /[\uDC00-\uDFFF]/.test(original.charAt(position))
  return !split(start) && !split(end) && original.slice(start, end) === quote
}

export function relationRecollectionEvidenceFragmentsValid(value: unknown): value is RelationRecollectionEvidenceFragment[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 8) return false
  const fragments = value as RelationRecollectionEvidenceFragment[]
  return fragments.every((fragment, index) => fields(fragment, ['field', 'start', 'end', 'quote'])
    && typeof fragment.field === 'string' && Object.hasOwn(relationRecollectionEvidenceFieldLabels, fragment.field)
    && integer(fragment.start) && integer(fragment.end, 1, fieldMaximum[fragment.field] * 2) && fragment.end > fragment.start
    && text(fragment.quote, 8000) && hasText(fragment.quote) && fragment.end - fragment.start === fragment.quote.length
    && !fragments.slice(0, index).some(other => other.field === fragment.field && other.start < fragment.end && fragment.start < other.end))
}

export function relationRecollectionEvidenceSelectionValid(value: unknown, expectedSource?: RelationRecollectionEvidenceSource): value is RelationRecollectionEvidenceSelection {
  const selection = value as RelationRecollectionEvidenceSelection
  if (!fields(selection, ['recollectionId', 'versionId', 'expectedRevision', 'fragments']) || !uuid(selection.recollectionId)
    || !uuid(selection.versionId) || !integer(selection.expectedRevision, 0, 1000) || !relationRecollectionEvidenceFragmentsValid(selection.fragments)) return false
  return expectedSource === undefined || relationRecollectionEvidenceSourceValid(expectedSource)
    && expectedSource.recollectionId === selection.recollectionId && expectedSource.versionId === selection.versionId
    && expectedSource.sourceRevision === selection.expectedRevision && selection.fragments.every(fragment =>
      (expectedSource.baselineKind !== 'NONE' || fragment.field !== 'ANSWER_FRAGMENT')
      && relationRecollectionEvidenceRangeMatches(expectedSource.fields[fragment.field], fragment.start, fragment.end, fragment.quote))
}

export function relationRecollectionEvidenceCreateValid(value: unknown, expectedSource?: RelationRecollectionEvidenceSource): value is RelationRecollectionEvidenceCreate {
  const request = value as RelationRecollectionEvidenceCreate
  return fields(request, ['reason', 'source', 'confirmed']) && reasonValid(request.reason) && request.confirmed === true
    && relationRecollectionEvidenceSelectionValid(request.source, expectedSource)
}

export function relationRecollectionEvidenceEntryValid(value: unknown, relationId?: string, evidenceId?: string): value is RelationRecollectionEvidenceEntry {
  const entry = value as RelationRecollectionEvidenceEntry
  return fields(entry, ['id', 'relationId', 'sourceId', 'targetId', 'type', 'reason', 'recollectionId', 'versionId', 'versionNumber', 'sourceRevision', 'sourceCount', 'createdAt'])
    && uuid(entry.id) && uuid(entry.relationId) && (relationId === undefined || relationId === entry.relationId)
    && (evidenceId === undefined || evidenceId === entry.id) && uuid(entry.sourceId) && uuid(entry.targetId) && entry.sourceId !== entry.targetId
    && relationTypes.includes(entry.type) && (!['RELATED', 'CONTRASTS'].includes(entry.type) || entry.sourceId < entry.targetId)
    && reasonValid(entry.reason) && uuid(entry.recollectionId) && uuid(entry.versionId) && integer(entry.sourceRevision, 0, 1000)
    && integer(entry.versionNumber, 1, 1000) && entry.versionNumber <= entry.sourceRevision + 1
    && integer(entry.sourceCount, 1, 8) && timestamp(entry.createdAt)
}

export function relationRecollectionEvidenceEntryFingerprint(entry: RelationRecollectionEvidenceEntry): string {
  return JSON.stringify([entry.id, entry.relationId, entry.sourceId, entry.targetId, entry.type, entry.reason,
    entry.recollectionId, entry.versionId, entry.versionNumber, entry.sourceRevision, entry.sourceCount, entry.createdAt])
}

export function relationRecollectionEvidenceEntryMatches(value: unknown, relationId: string, request: RelationRecollectionEvidenceCreate, versionNumber: number): value is RelationRecollectionEvidenceEntry {
  if (!relationRecollectionEvidenceCreateValid(request) || !relationRecollectionEvidenceEntryValid(value, relationId)) return false
  return value.reason === request.reason && value.recollectionId === request.source.recollectionId && value.versionId === request.source.versionId
    && value.sourceRevision === request.source.expectedRevision && value.versionNumber === versionNumber && value.sourceCount === request.source.fragments.length
}

export function relationRecollectionEvidencePageValid(value: unknown, relationId: string, cursor?: string, limit = 20): value is RelationRecollectionEvidencePage {
  const page = value as RelationRecollectionEvidencePage
  if (!uuid(relationId) || cursor !== undefined && !uuid(cursor) || !integer(limit, 1, 20)
    || !fields(page, ['relationId', 'relationExists', 'items', 'nextCursor']) || page.relationId !== relationId
    || typeof page.relationExists !== 'boolean' || !Array.isArray(page.items) || page.items.length > limit
    || page.nextCursor !== null && !uuid(page.nextCursor)) return false
  let previous = cursor, tuple: string | undefined
  for (const entry of page.items) {
    if (!relationRecollectionEvidenceEntryValid(entry, relationId) || previous !== undefined && entry.id <= previous) return false
    const identity = JSON.stringify([entry.sourceId, entry.targetId, entry.type])
    if (tuple !== undefined && tuple !== identity) return false
    tuple = identity; previous = entry.id
  }
  return (page.relationExists || cursor !== undefined || page.items.length > 0)
    && (page.nextCursor === null || page.items.length === limit && page.nextCursor === page.items.at(-1)?.id)
}

function snapshotValid(value: unknown): value is RelationRecollectionEvidenceDetail['source'] {
  const source = value as RelationRecollectionEvidenceDetail['source']
  if (!fields(source, ['kind', 'feedback', 'recollection']) || source.kind !== 'RECOLLECTION' || source.feedback !== null) return false
  const recollection = source.recollection
  return fields(recollection, ['recollectionId', 'versionId', 'versionNumber', 'sourceRevision', 'title', 'type', 'baselineKind', 'uncertainty', 'fragments'])
    && metadataValid(recollection) && relationRecollectionEvidenceFragmentsValid(recollection.fragments)
    && recollection.fragments.every(fragment => (recollection.baselineKind !== 'NONE' || fragment.field !== 'ANSWER_FRAGMENT')
      && (fragment.field !== 'UNCERTAINTY' || relationRecollectionEvidenceRangeMatches(recollection.uncertainty, fragment.start, fragment.end, fragment.quote)))
}

export function relationRecollectionEvidenceDetailValid(value: unknown, relationId: string, evidenceId: string, expectedEntry?: RelationRecollectionEvidenceEntry): value is RelationRecollectionEvidenceDetail {
  const detail = value as RelationRecollectionEvidenceDetail
  if (!uuid(relationId) || !uuid(evidenceId) || !fields(detail, ['entry', 'relationExists', 'source', 'sourceState'])
    || !relationRecollectionEvidenceEntryValid(detail.entry, relationId, evidenceId) || typeof detail.relationExists !== 'boolean'
    || !['CURRENT', 'SUPERSEDED', 'ARCHIVED', 'REVIEW_REQUIRED', 'UNAVAILABLE'].includes(detail.sourceState) || !snapshotValid(detail.source)
    || expectedEntry !== undefined && (!relationRecollectionEvidenceEntryValid(expectedEntry, relationId, evidenceId)
      || relationRecollectionEvidenceEntryFingerprint(detail.entry) !== relationRecollectionEvidenceEntryFingerprint(expectedEntry))) return false
  const recollection = detail.source.recollection
  return recollection.recollectionId === detail.entry.recollectionId && recollection.versionId === detail.entry.versionId
    && recollection.versionNumber === detail.entry.versionNumber && recollection.sourceRevision === detail.entry.sourceRevision
    && recollection.fragments.length === detail.entry.sourceCount
}
