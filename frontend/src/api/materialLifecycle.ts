import type { Schema } from './client'
import { materialReferenceIdValid } from './materialReferences'

export type MaterialLifecycleState = Schema['MaterialLifecycleState']
export type MaterialLifecycleChange = Schema['MaterialLifecycleChange']
export type MaterialLifecycleEvent = Schema['MaterialLifecycleEvent']
export type MaterialLifecycleHistoryPage = Schema['MaterialLifecycleHistoryPage']
export type MaterialLifecycleCheck = Schema['MaterialLifecycleCheck']
export type MaterialLifecycleStatus = Schema['MaterialLifecycleStatus']
export type MaterialAiUse = Schema['MaterialAiUse']
export type MaterialLifecycleAction = Schema['MaterialLifecycleAction']

export const materialLifecycleStatusLabels: Record<MaterialLifecycleStatus, string> = { ACTIVE: '有效', ARCHIVED: '已归档' }
export const materialAiUseLabels: Record<MaterialAiUse, string> = { ALLOWED: '允许 AI 用途', REVOKED: '已撤回 AI 用途' }
export const materialLifecycleActionLabels: Record<MaterialLifecycleAction, string> = {
  ARCHIVE: '归档摘录', RESTORE: '恢复摘录', REVOKE_AI: '撤回 AI 用途', ALLOW_AI: '重新允许 AI 用途',
}
export const materialLifecycleActions: MaterialLifecycleAction[] = ['ARCHIVE', 'RESTORE', 'REVOKE_AI', 'ALLOW_AI']
export const materialLifecycleActionHelp: Record<MaterialLifecycleAction, string> = {
  ARCHIVE: '阻止新的本地选用及 AI 使用，保留原文与历史。',
  RESTORE: '恢复本地选用；AI 用途保持原状态，旧清单、预览与授权不会恢复。',
  REVOKE_AI: '阻止直接及经准备依据的 AI 使用，并拒绝旧任务的后续资格；本地读取与依据保存仍可进行。',
  ALLOW_AI: '重新允许 AI 用途；归档状态保持原样，旧清单、预览与授权不会恢复。',
}
export const materialLifecycleBasePath = (excerptId: string): string => `/materials/excerpts/${encodeURIComponent(excerptId)}/lifecycle`
export const materialLifecycleHistoryPath = (excerptId: string, after?: string): string => {
  const query = new URLSearchParams({ limit: '20' })
  if (after !== undefined) query.set('after', after)
  return `${materialLifecycleBasePath(excerptId)}/history?${query}`
}
const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const statusValid = (value: unknown): value is MaterialLifecycleStatus => typeof value === 'string' && Object.hasOwn(materialLifecycleStatusLabels, value)
const aiUseValid = (value: unknown): value is MaterialAiUse => typeof value === 'string' && Object.hasOwn(materialAiUseLabels, value)
const actionValid = (value: unknown): value is MaterialLifecycleAction => typeof value === 'string' && Object.hasOwn(materialLifecycleActionLabels, value)
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(value)) return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19)
}
const timestampKey = (value: string): string => `${value.slice(0, 19)}.${value.slice(19, -1).replace('.', '').padEnd(6, '0')}Z`
export function normalizeMaterialLifecycleReason(value: string): string {
  // 与服务端 String.strip 的空白集合一致，保留 NBSP、窄不换行空格和 BOM 等合法正文。
  return value.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g, '')
}
export function materialLifecycleReasonValid(value: unknown): value is string {
  if (typeof value !== 'string' || value.includes('\0')) return false
  const normalized = normalizeMaterialLifecycleReason(value)
  if (!normalized || normalized.length > 1000) return false
  for (let index = 0; index < value.length; index++) {
    const unit = value.charCodeAt(index)
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(++index)
      if (!(next >= 0xdc00 && next <= 0xdfff)) return false
    } else if (unit >= 0xdc00 && unit <= 0xdfff) return false
  }
  return true
}
export function materialLifecycleStateValid(value: unknown, excerptId: string, attachmentId: string): value is MaterialLifecycleState {
  const state = value as MaterialLifecycleState
  return materialReferenceIdValid(excerptId) && materialReferenceIdValid(attachmentId)
    && fields(state, ['excerptId', 'attachmentId', 'status', 'aiUse', 'revision', 'createdAt', 'updatedAt', 'hasReferences'])
    && state.excerptId === excerptId && state.attachmentId === attachmentId && statusValid(state.status) && aiUseValid(state.aiUse)
    && integer(state.revision) && timestamp(state.createdAt) && timestamp(state.updatedAt)
    && timestampKey(state.updatedAt) >= timestampKey(state.createdAt) && typeof state.hasReferences === 'boolean'
}
export function materialLifecycleChangeValid(value: unknown): value is MaterialLifecycleChange {
  const change = value as MaterialLifecycleChange
  return fields(change, ['expectedRevision', 'action', 'reason', 'confirmed']) && integer(change.expectedRevision)
    && actionValid(change.action) && materialLifecycleReasonValid(change.reason) && change.confirmed === true
}
export function materialLifecycleActionAvailable(state: MaterialLifecycleState, action: MaterialLifecycleAction): boolean {
  return action === 'ARCHIVE' ? state.status === 'ACTIVE' : action === 'RESTORE' ? state.status === 'ARCHIVED'
    : action === 'REVOKE_AI' ? state.aiUse === 'ALLOWED' : state.aiUse === 'REVOKED'
}
function transition(previousStatus: MaterialLifecycleStatus, previousAiUse: MaterialAiUse, status: MaterialLifecycleStatus,
  aiUse: MaterialAiUse, action: MaterialLifecycleAction): boolean {
  return action === 'ARCHIVE' ? previousStatus === 'ACTIVE' && status === 'ARCHIVED' && aiUse === previousAiUse
    : action === 'RESTORE' ? previousStatus === 'ARCHIVED' && status === 'ACTIVE' && aiUse === previousAiUse
    : action === 'REVOKE_AI' ? previousAiUse === 'ALLOWED' && aiUse === 'REVOKED' && status === previousStatus
    : previousAiUse === 'REVOKED' && aiUse === 'ALLOWED' && status === previousStatus
}
export function materialLifecycleReceiptValid(value: unknown, before: MaterialLifecycleState, change: MaterialLifecycleChange): value is MaterialLifecycleState {
  return materialLifecycleChangeValid(change) && materialLifecycleStateValid(before, before.excerptId, before.attachmentId)
    && materialLifecycleStateValid(value, before.excerptId, before.attachmentId) && change.expectedRevision === before.revision
    && before.revision < Number.MAX_SAFE_INTEGER && value.revision === before.revision + 1
    && timestampKey(value.createdAt) === timestampKey(before.createdAt) && timestampKey(value.updatedAt) >= timestampKey(before.updatedAt)
    && transition(before.status, before.aiUse, value.status, value.aiUse, change.action)
}
export function materialLifecycleEventValid(value: unknown, excerptId: string): value is MaterialLifecycleEvent {
  const event = value as MaterialLifecycleEvent
  return materialReferenceIdValid(excerptId)
    && fields(event, ['id', 'excerptId', 'action', 'previousStatus', 'previousAiUse', 'previousRevision', 'status', 'aiUse', 'revision', 'reason', 'createdAt'])
    && materialReferenceIdValid(event.id) && event.excerptId === excerptId && actionValid(event.action)
    && statusValid(event.previousStatus) && aiUseValid(event.previousAiUse) && statusValid(event.status) && aiUseValid(event.aiUse)
    && integer(event.previousRevision) && event.previousRevision < Number.MAX_SAFE_INTEGER && integer(event.revision)
    && event.revision === event.previousRevision + 1 && materialLifecycleReasonValid(event.reason)
    && event.reason === normalizeMaterialLifecycleReason(event.reason)
    && timestamp(event.createdAt) && transition(event.previousStatus, event.previousAiUse, event.status, event.aiUse, event.action)
}
export function materialLifecycleCursor(value: unknown, excerptId: string): number | undefined {
  if (typeof value !== 'string' || value.length > 60 || !materialReferenceIdValid(excerptId)) return undefined
  const parts = value.split('~')
  if (parts.length !== 2 || parts[0] !== excerptId || !/^[1-9]\d{0,15}$/.test(parts[1] ?? '')) return undefined
  const revision = Number(parts[1])
  return integer(revision) && revision > 0 ? revision : undefined
}
export function materialLifecycleHistoryValid(value: unknown, excerptId: string, after?: string): value is MaterialLifecycleHistoryPage {
  try {
    const page = value as MaterialLifecycleHistoryPage, position = after === undefined ? 0 : materialLifecycleCursor(after, excerptId)
    if (!materialReferenceIdValid(excerptId) || position === undefined || !fields(page, ['excerptId', 'items', 'nextCursor'])
      || page.excerptId !== excerptId || !Array.isArray(page.items) || page.items.length > 20) return false
    let previous = position
    const identities = new Set<string>()
    for (const event of page.items) {
      if (!materialLifecycleEventValid(event, excerptId) || event.revision <= previous || identities.has(event.id)) return false
      previous = event.revision; identities.add(event.id)
    }
    return page.nextCursor === null || page.items.length === 20 && materialLifecycleCursor(page.nextCursor, excerptId) === previous
  } catch { return false }
}
export function materialLifecycleCheckValid(value: unknown): value is MaterialLifecycleCheck {
  const check = value as MaterialLifecycleCheck
  return fields(check, ['excerptId', 'revision']) && materialReferenceIdValid(check.excerptId) && integer(check.revision)
}
