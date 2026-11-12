import type { Schema } from './client'
import type { PreparationItem } from './preparation'

export type ExpressionBasisReviewKind = Schema['ExpressionBasisReviewKind']
export type ExpressionBasisReviewBasis = Schema['ExpressionBasisReviewBasis']
export type ExpressionBasisReviewRecord = Schema['ExpressionBasisReviewRecord']
export type ExpressionBasisReviewState = Schema['ExpressionBasisReviewState']
export type ExpressionBasisReviewPage = Schema['ExpressionBasisReviewPage']
export type ExpressionBasisReviewConfirm = Schema['ExpressionBasisReviewConfirm']
type SourceCheck = Schema['PrepareSourceCheck']
export type ExpressionBasisReviewItem = Extract<PreparationItem, { kind: 'EXPRESSION' }>

export const expressionBasisReviewPaths = {
  state: (itemId: string) => `/prepare/items/${encodeURIComponent(itemId)}/expression-basis-review`,
  confirm: (itemId: string) => `/prepare/items/${encodeURIComponent(itemId)}/expression-basis-review/confirm`,
  page: (itemId: string, before?: number) => {
    const query = new URLSearchParams({ limit: '20' })
    if (before !== undefined) query.set('before', String(before))
    return `/prepare/items/${encodeURIComponent(itemId)}/expression-basis-reviews?${query}`
  },
}
export const expressionBasisReviewKindLabels: Record<ExpressionBasisReviewKind, string> = {
  SAVED_BASIS: '保存表达时建立', CONFIRMED_BASIS: '保留正文并确认',
}
export const expressionBasisReviewSourceLabels: Record<SourceCheck['state'], string> = {
  CURRENT: '当前有效', SUPERSEDED: '事实已改版', WITHDRAWN: '事实确认已撤回', ARCHIVED: '事实已归档',
  PROJECT_ARCHIVED: '所属项目已归档', UNVERIFIED: '事实待核实', UNAVAILABLE: '事实不可用',
}
const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
export const expressionBasisReviewIdValid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
// 固定历史标题保持存储语义；理由限制不能反过来改写历史。
const title = (value: unknown): value is string => typeof value === 'string' && value.length <= 200
// 与 Java String.strip 的 Character.isWhitespace 一致，保留 NBSP 和 FEFF。
export const expressionBasisReviewNormalizeReason = (value: string): string =>
  value.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g, '')
export const expressionBasisReviewReasonValid = (value: unknown): value is string => typeof value === 'string'
  && value === expressionBasisReviewNormalizeReason(value) && Array.from(value).length >= 1 && Array.from(value).length <= 1000
  && !/[\u0000\uD800-\uDFFF]/u.test(value) && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value)
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(value)) return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19)
}
const checkKey = (check: SourceCheck) => JSON.stringify([check.kind, check.itemId, check.versionId,
  check.state, check.sourceGeneration, check.projectGeneration])
const basisIdentityKey = (basis: readonly ExpressionBasisReviewBasis[]) => JSON.stringify(basis.map(value =>
  [value.itemId, value.versionId, value.title, value.versionNumber, value.projectId]))
const basisKey = (basis: readonly ExpressionBasisReviewBasis[]) => JSON.stringify(basis.map(value =>
  [value.itemId, value.versionId, value.title, value.versionNumber, value.projectId, checkKey(value.check)]))
function sourceCheckValid(value: unknown, current = false): value is SourceCheck {
  const check = value as SourceCheck
  return fields(check, ['kind', 'itemId', 'versionId', 'state', 'sourceGeneration', 'projectGeneration'])
    && check.kind === 'FACT' && expressionBasisReviewIdValid(check.itemId) && expressionBasisReviewIdValid(check.versionId)
    && typeof check.state === 'string' && Object.hasOwn(expressionBasisReviewSourceLabels, check.state)
    && (check.sourceGeneration === null || integer(check.sourceGeneration))
    && (check.projectGeneration === null || integer(check.projectGeneration))
    && (check.state !== 'CURRENT' || integer(check.sourceGeneration) && integer(check.projectGeneration))
    && (!current || check.state === 'CURRENT')
}
function basisValid(value: unknown, current = false): value is ExpressionBasisReviewBasis[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 50) return false
  const versions = new Set<string>(), items = new Set<string>()
  for (const basis of value) {
    if (!fields(basis, ['itemId', 'versionId', 'title', 'versionNumber', 'projectId', 'check'])
      || !expressionBasisReviewIdValid(basis.itemId) || !expressionBasisReviewIdValid(basis.versionId)
      || !expressionBasisReviewIdValid(basis.projectId) || !title(basis.title) || !integer(basis.versionNumber, 1, 2147483647)
      || !sourceCheckValid(basis.check, current) || basis.check.itemId !== basis.itemId || basis.check.versionId !== basis.versionId
      || versions.has(basis.versionId) || items.has(basis.itemId)) return false
    versions.add(basis.versionId); items.add(basis.itemId)
  }
  return true
}
export function expressionBasisReviewItemKey(value: PreparationItem | undefined): string {
  if (!value || value.kind !== 'EXPRESSION' || !expressionBasisReviewIdValid(value.id) || !integer(value.revision)
    || !['ACTIVE', 'ARCHIVED'].includes(value.status) || !value.currentVersion
    || !expressionBasisReviewIdValid(value.currentVersion.id) || !integer(value.currentVersion.number, 1, 2147483647)) return ''
  const versions = value.currentVersion.content?.factVersionIds
  if (!Array.isArray(versions) || versions.length < 1 || versions.length > 50
    || !versions.every(expressionBasisReviewIdValid) || new Set(versions).size !== versions.length) return ''
  return JSON.stringify([value.id, value.currentVersion.id, value.currentVersion.number, value.revision, value.status, versions])
}
export function expressionBasisReviewRecordValid(value: unknown, itemId: string): value is ExpressionBasisReviewRecord {
  const record = value as ExpressionBasisReviewRecord
  return expressionBasisReviewIdValid(itemId)
    && fields(record, ['id', 'itemId', 'versionId', 'versionNumber', 'revision', 'kind', 'reason', 'itemRevision', 'createdAt', 'basis'])
    && expressionBasisReviewIdValid(record.id) && record.itemId === itemId && expressionBasisReviewIdValid(record.versionId)
    && integer(record.versionNumber, 1, 2147483647) && integer(record.revision, 1) && integer(record.itemRevision)
    && timestamp(record.createdAt) && basisValid(record.basis, true)
    && (record.kind === 'SAVED_BASIS' ? record.reason === null
      : record.kind === 'CONFIRMED_BASIS' && expressionBasisReviewReasonValid(record.reason))
}
export function expressionBasisReviewStateValid(value: unknown, item: ExpressionBasisReviewItem): value is ExpressionBasisReviewState {
  const state = value as ExpressionBasisReviewState
  if (!expressionBasisReviewItemKey(item)
    || !fields(state, ['itemId', 'versionId', 'versionNumber', 'itemRevision', 'status', 'revision', 'latest', 'basis', 'reviewRequired', 'canConfirm'])
    || state.itemId !== item.id || state.versionId !== item.currentVersion.id || state.versionNumber !== item.currentVersion.number
    || state.itemRevision !== item.revision || state.status !== item.status || !integer(state.revision)
    || typeof state.reviewRequired !== 'boolean' || typeof state.canConfirm !== 'boolean' || !basisValid(state.basis)
    || state.basis.length !== item.currentVersion.content.factVersionIds.length
    || state.basis.some((basis, index) => basis.versionId !== item.currentVersion.content.factVersionIds[index])) return false
  const latest = state.latest
  if (latest !== null && (!expressionBasisReviewRecordValid(latest, item.id) || latest.versionId !== state.versionId
    || latest.versionNumber !== state.versionNumber || latest.revision > state.revision || latest.itemRevision > state.itemRevision
    || basisIdentityKey(latest.basis) !== basisIdentityKey(state.basis))) return false
  if (state.revision === 0 && latest !== null) return false
  const current = state.basis.every(basis => basis.check.state === 'CURRENT')
  const required = !latest || !current || basisKey(latest.basis) !== basisKey(state.basis)
  return state.reviewRequired === required && (!state.canConfirm || state.status === 'ACTIVE' && current
    && state.itemRevision < Number.MAX_SAFE_INTEGER && state.revision < Number.MAX_SAFE_INTEGER)
}
export function expressionBasisReviewPageValid(value: unknown, itemId: string, before?: number): value is ExpressionBasisReviewPage {
  const page = value as ExpressionBasisReviewPage
  if (!expressionBasisReviewIdValid(itemId) || before !== undefined && !integer(before, 1)
    || !fields(page, ['itemId', 'items', 'nextCursor']) || page.itemId !== itemId || !Array.isArray(page.items) || page.items.length > 20) return false
  const seen = new Set<string>(), versions = new Map<number, string>()
  let previous: ExpressionBasisReviewRecord | undefined
  for (const record of page.items) {
    if (!expressionBasisReviewRecordValid(record, itemId) || seen.has(record.id) || before !== undefined && record.revision >= before
      || previous && (record.revision >= previous.revision || record.itemRevision >= previous.itemRevision
        || record.versionNumber > previous.versionNumber)
      || versions.has(record.versionNumber) && versions.get(record.versionNumber) !== record.versionId
      || [...versions].some(([number, id]) => number !== record.versionNumber && id === record.versionId)) return false
    seen.add(record.id); versions.set(record.versionNumber, record.versionId); previous = record
  }
  return page.nextCursor === null || integer(page.nextCursor, 1) && page.items.length === 20 && page.nextCursor === previous?.revision
}
export function expressionBasisReviewConfirmValid(value: unknown, state: ExpressionBasisReviewState): value is ExpressionBasisReviewConfirm {
  const request = value as ExpressionBasisReviewConfirm
  return state.canConfirm && state.status === 'ACTIVE' && integer(state.itemRevision, 0, Number.MAX_SAFE_INTEGER - 1)
    && integer(state.revision, 0, Number.MAX_SAFE_INTEGER - 1) && basisValid(state.basis, true)
    && fields(request, ['expectedItemRevision', 'expectedVersionId', 'expectedRevision', 'basisChecks', 'reason', 'confirmed'])
    && request.expectedItemRevision === state.itemRevision && request.expectedVersionId === state.versionId
    && request.expectedRevision === state.revision && request.confirmed === true && expressionBasisReviewReasonValid(request.reason)
    && Array.isArray(request.basisChecks) && request.basisChecks.length === state.basis.length
    && request.basisChecks.every((check, index) => sourceCheckValid(check, true) && checkKey(check) === checkKey(state.basis[index]!.check))
    && new TextEncoder().encode(JSON.stringify(request)).length <= 65536
}
export function expressionBasisReviewReceiptMatches(value: unknown, itemId: string, state: ExpressionBasisReviewState,
  request: ExpressionBasisReviewConfirm): value is ExpressionBasisReviewRecord {
  return expressionBasisReviewConfirmValid(request, state) && expressionBasisReviewRecordValid(value, itemId)
    && state.itemId === itemId && value.kind === 'CONFIRMED_BASIS' && value.versionId === request.expectedVersionId
    && value.versionNumber === state.versionNumber && value.revision === request.expectedRevision + 1
    && value.itemRevision === request.expectedItemRevision + 1 && value.reason === request.reason && basisKey(value.basis) === basisKey(state.basis)
}
