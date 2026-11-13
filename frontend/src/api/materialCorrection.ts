import type { Schema } from './client'
import type { MaterialExcerpt } from './material'
import type { MaterialLifecycleState } from './materialLifecycle'
import { materialLifecycleReasonValid, materialLifecycleStateValid, normalizeMaterialLifecycleReason } from './materialLifecycle'
import { materialReferenceIdValid, materialReferenceSourceKey, materialReferenceSourceValid } from './materialReferences'

export type MaterialCorrectionCreate = Schema['MaterialCorrectionCreate']
export type MaterialCorrectionStatement = Schema['MaterialCorrectionStatement']
export type MaterialCorrectionState = Schema['MaterialCorrectionState']

export const materialCorrectionPath = (excerptId: string): string =>
  `/materials/excerpts/${encodeURIComponent(excerptId)}/correction`
const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(value)) return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19)
}
function source(excerpt: MaterialExcerpt) {
  return { excerptId: excerpt.id, attachmentId: excerpt.attachmentId, origin: excerpt.origin,
    runId: excerpt.runId, segmentId: excerpt.segmentId, createdAt: excerpt.createdAt }
}
export function materialCorrectionExcerptValid(value: unknown, excerptId: string, attachmentId?: string): value is MaterialExcerpt {
  const excerpt = value as MaterialExcerpt
  return fields(excerpt, ['id', 'attachmentId', 'runId', 'segmentId', 'origin', 'locator', 'text', 'startOffset', 'endOffset', 'createdAt'])
    && excerpt.id === excerptId && (attachmentId === undefined || excerpt.attachmentId === attachmentId)
    && materialReferenceSourceValid(source(excerpt), excerptId, excerpt.attachmentId)
    && typeof excerpt.locator === 'string' && excerpt.locator.length > 0 && excerpt.locator.length <= 500
    && typeof excerpt.text === 'string' && excerpt.text.length > 0 && excerpt.text.length <= 10000
    && (excerpt.origin === 'MANUAL' ? excerpt.startOffset === null && excerpt.endOffset === null
      : integer(excerpt.startOffset) && integer(excerpt.endOffset) && excerpt.endOffset <= 2147483647
        && excerpt.endOffset > excerpt.startOffset && excerpt.endOffset - excerpt.startOffset === excerpt.text.length)
}
export function materialCorrectionExcerptSame(left: MaterialExcerpt, right: MaterialExcerpt): boolean {
  return materialCorrectionExcerptValid(left, left.id) && materialCorrectionExcerptValid(right, left.id, left.attachmentId)
    && materialReferenceSourceKey(source(left)) === materialReferenceSourceKey(source(right))
    && left.locator === right.locator && left.text === right.text
    && left.startOffset === right.startOffset && left.endOffset === right.endOffset
}
export function materialCorrectionLifecycleMatches(value: unknown, excerpt: MaterialExcerpt): value is MaterialLifecycleState {
  return materialCorrectionExcerptValid(excerpt, excerpt.id)
    && materialLifecycleStateValid(value, excerpt.id, excerpt.attachmentId)
    && materialReferenceSourceKey(source(excerpt)) === materialReferenceSourceKey({ ...source(excerpt), createdAt: value.createdAt })
}
export function materialCorrectionStatementValid(value: unknown, excerptId: string): value is MaterialCorrectionStatement {
  const statement = value as MaterialCorrectionStatement
  return fields(statement, ['id', 'excerptId', 'replacementExcerptId', 'sourceRevision', 'replacementRevision', 'reason', 'createdAt'])
    && materialReferenceIdValid(statement.id) && statement.excerptId === excerptId && materialReferenceIdValid(excerptId)
    && materialReferenceIdValid(statement.replacementExcerptId) && statement.replacementExcerptId !== excerptId
    && integer(statement.sourceRevision) && integer(statement.replacementRevision)
    && materialLifecycleReasonValid(statement.reason) && statement.reason === normalizeMaterialLifecycleReason(statement.reason)
    && timestamp(statement.createdAt)
}
export function materialCorrectionStateValid(value: unknown, excerptId: string, attachmentId: string): value is MaterialCorrectionState {
  const state = value as MaterialCorrectionState
  return fields(state, ['excerptId', 'attachmentId', 'lifecycleRevision', 'correction'])
    && materialReferenceIdValid(excerptId) && materialReferenceIdValid(attachmentId)
    && state.excerptId === excerptId && state.attachmentId === attachmentId && integer(state.lifecycleRevision)
    && (state.correction === null || materialCorrectionStatementValid(state.correction, excerptId)
      && state.correction.sourceRevision <= state.lifecycleRevision)
}
export function materialCorrectionCreateValid(value: unknown, excerptId: string): value is MaterialCorrectionCreate {
  const create = value as MaterialCorrectionCreate
  return fields(create, ['expectedRevision', 'replacementExcerptId', 'expectedReplacementRevision', 'reason', 'confirmed'])
    && materialReferenceIdValid(excerptId) && materialReferenceIdValid(create.replacementExcerptId)
    && create.replacementExcerptId !== excerptId && integer(create.expectedRevision) && integer(create.expectedReplacementRevision)
    && materialLifecycleReasonValid(create.reason) && create.reason === normalizeMaterialLifecycleReason(create.reason)
    && create.confirmed === true
}
export function materialCorrectionReceiptValid(value: unknown, excerptId: string, attachmentId: string,
  create: MaterialCorrectionCreate): value is MaterialCorrectionState & { correction: MaterialCorrectionStatement } {
  return materialCorrectionCreateValid(create, excerptId) && materialCorrectionStateValid(value, excerptId, attachmentId)
    && value.lifecycleRevision === create.expectedRevision && value.correction !== null
    && value.correction.replacementExcerptId === create.replacementExcerptId && value.correction.sourceRevision === create.expectedRevision
    && value.correction.replacementRevision === create.expectedReplacementRevision && value.correction.reason === create.reason
}
