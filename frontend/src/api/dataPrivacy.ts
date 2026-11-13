import { preview, read } from './client'

export const privacyKinds = {
  KNOWLEDGE: '知识／独立笔记', PREPARATION: '准备材料与项目事实', QUESTION: '题目',
  MEMORY_CARD: '记忆卡', BOARD: '知识白板', MATERIAL: '私有附件',
  AI_TASK: 'AI任务', TRAINING: '训练记录', REVIEW_FEEDBACK: '复盘反馈',
  RECOLLECTION: '面试回忆', PREPARATION_TASK: '准备任务', REVIEW_ACTION: '改进动作',
  QUESTION_COLLECTION: '题单／学习路线', INTERVIEW_EVENT: '面试安排', AI_AUTHORIZATION: '模型范围授权',
} as const
export type PrivacyKind = keyof typeof privacyKinds
export type PrivacyActionableKind = Exclude<PrivacyKind, 'AI_AUTHORIZATION'>
export type PrivacyAction = 'TRASH' | 'RESTORE' | 'PURGE'
export type PrivacySelection = { objectKind: PrivacyKind; objectId: string }
export type PrivacyHead = { sequence: number; hash: string }
export type PrivacyStatus = {
  configured: boolean; initialized: boolean; synchronizedWithDatabase: boolean; latestHead: PrivacyHead
  appliedSequence: number | null; appliedHash: string | null
  state: 'NOT_CONFIGURED' | 'NOT_INITIALIZED' | 'READY' | 'RECONCILIATION_REQUIRED'
}
export type PrivacyObject = PrivacySelection & { title: string; status: string; revision: number; impact: string }
export type PrivacyStorage = { table: string; rows: number; fields: string[]; handling: string }
export type PrivacyPreview = PrivacySelection & {
  action: PrivacyAction; digest: string; objects: PrivacyObject[]; storage: PrivacyStorage[]
  limitations: string[]; ledgerRedactions: number
}
export type PrivacyResult = {
  action: PrivacyAction; objects: { kind: PrivacyKind; id: string }[]
  ledgerSequence: number; ledgerHash: string; state: string
}
export type PrivacyTrashEntry = PrivacySelection & {
  state: 'TRASHED'; revision: number; previousStatus: string | null; trashedAt: string; updatedAt: string
}
export type PrivacyTrashPage = { items: PrivacyTrashEntry[]; nextCursor: string | null }
export const privacyActions: Record<PrivacyAction, string> = { TRASH: '移入回收站', RESTORE: '从回收站恢复', PURGE: '彻底清除正文' }
export const privacyStates: Record<PrivacyStatus['state'], string> = {
  NOT_CONFIGURED: '服务器尚未配置独立账本', NOT_INITIALIZED: '独立账本尚未初始化',
  READY: '账本与业务检查点一致', RECONCILIATION_REQUIRED: '账本与业务检查点待对齐',
}
export const privacyImpacts: Record<string, string> = {
  SELECTED_OBJECT: '本次明确选择的对象', ROOT_ALL_VERSIONS: '根对象及其全部版本',
  WHOLE_MIXED_DERIVED_DOCUMENT: '整份混合派生正文',
}
export const privacyStorageHandling: Record<string, string> = {
  CLEAR_CONFIRMED_CONTENT_KEEP_IDENTITY: '清除所列正文，保留关联身份',
  INVALIDATE_AND_CLEAR_WHOLE_MIXED_EXPORT: '使历史完整导出失效，并清除整包副本',
}
const base = '/data-privacy'
const hash = /^[0-9a-f]{64}$/
export const privacyIdValid = (value: unknown): value is string => typeof value === 'string'
  && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
export const privacyKindValid = (value: unknown): value is PrivacyKind => typeof value === 'string' && Object.hasOwn(privacyKinds, value)
export const privacyActionableKindValid = (value: unknown): value is PrivacyActionableKind => privacyKindValid(value) && value !== 'AI_AUTHORIZATION'
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === 'object' && !Array.isArray(value)
const text = (value: unknown): value is string => typeof value === 'string'
const texts = (value: unknown): value is string[] => Array.isArray(value) && value.every(text)
const selectionValid = (value: unknown): value is PrivacySelection & Record<string, unknown> =>
  record(value) && privacyKindValid(value.objectKind) && privacyIdValid(value.objectId)
function invalid(message: string): never { throw new Error(message) }
export function checkedPrivacyStatus(value: unknown): PrivacyStatus {
  if (!record(value) || typeof value.configured !== 'boolean' || typeof value.initialized !== 'boolean'
    || typeof value.synchronizedWithDatabase !== 'boolean' || !record(value.latestHead)
    || !count(value.latestHead.sequence) || !text(value.latestHead.hash) || !hash.test(value.latestHead.hash)
    || value.appliedSequence !== null && !count(value.appliedSequence)
    || value.appliedHash !== null && (!text(value.appliedHash) || !hash.test(value.appliedHash))
    || (value.appliedSequence === null) !== (value.appliedHash === null)
    || value.initialized && (!value.configured || value.latestHead.sequence < 1)) invalid('独立账本状态回执无法核对，请重新读取；操作保持关闭。')
  const expected = !value.configured ? 'NOT_CONFIGURED' : !value.initialized ? 'NOT_INITIALIZED'
    : value.synchronizedWithDatabase ? 'READY' : 'RECONCILIATION_REQUIRED'
  if (value.state !== expected || value.synchronizedWithDatabase && (!value.configured || !value.initialized
    || value.appliedSequence !== value.latestHead.sequence || value.appliedHash !== value.latestHead.hash)) {
    invalid('账本状态、最新头与业务检查点不一致，请核对服务器；操作保持关闭。')
  }
  return value as PrivacyStatus
}
export function checkedPrivacyPreview(value: unknown, action: PrivacyAction, selected: PrivacySelection): PrivacyPreview {
  if (!record(value) || value.action !== action || !selectionValid(value) || value.objectKind !== selected.objectKind
    || value.objectId !== selected.objectId || !text(value.digest) || !hash.test(value.digest)
    || !Array.isArray(value.objects) || !value.objects.length || value.objects.length > 500
    || value.objects.some(item => !selectionValid(item) || !record(item) || !text(item.title)
      || !text(item.status) || !count(item.revision) || !text(item.impact))
    || !Array.isArray(value.storage) || value.storage.some(item => !record(item) || !text(item.table)
      || !count(item.rows) || !texts(item.fields) || !text(item.handling))
    || !texts(value.limitations) || !count(value.ledgerRedactions)) invalid('影响预览的对象、摘要或范围无法核对，请重新生成。')
  const identities = value.objects.map(item => `${item.objectKind}:${item.objectId}`)
  if (new Set(identities).size !== identities.length || !identities.includes(`${selected.objectKind}:${selected.objectId}`)
    || action !== 'PURGE' && identities.length !== 1) invalid('影响预览缺少根对象或含重复／意外范围，请重新生成。')
  return value as PrivacyPreview
}
export function checkedPrivacyResult(value: unknown, expected: PrivacyPreview): PrivacyResult {
  const state = { TRASH: 'TRASHED', RESTORE: 'RESTORED_WITHOUT_REACTIVATION', PURGE: 'PURGED_BODYLESS_TOMBSTONES' }[expected.action]
  if (!record(value) || value.action !== expected.action || value.state !== state
    || !count(value.ledgerSequence) || value.ledgerSequence < 1 || !text(value.ledgerHash) || !hash.test(value.ledgerHash)
    || !Array.isArray(value.objects) || value.objects.length !== expected.objects.length
    || value.objects.some(item => !record(item) || !privacyKindValid(item.kind) || !privacyIdValid(item.id))) {
    invalid('操作回执的动作、账本或对象范围无法核对，请用原请求恢复。')
  }
  const subjects = value.objects.map(item => `${item.kind}:${item.id}`)
  if (new Set(subjects).size !== subjects.length || expected.objects.some(item => !subjects.includes(`${item.objectKind}:${item.objectId}`))) {
    invalid('操作回执与已确认的完整对象范围不同，请用原请求恢复。')
  }
  return value as PrivacyResult
}
export function checkedPrivacyTrash(value: unknown): PrivacyTrashPage {
  if (!record(value) || !Array.isArray(value.items) || value.items.length > 50
    || value.items.some(item => !selectionValid(item) || !record(item) || item.state !== 'TRASHED'
      || !count(item.revision) || item.previousStatus !== null && !text(item.previousStatus)
      || !text(item.trashedAt) || !text(item.updatedAt))
    || value.nextCursor !== null && (!text(value.nextCursor) || !/^[A-Z_]{1,32}~[0-9a-f-]{36}$/.test(value.nextCursor))) {
    invalid('回收站清单或分页游标无法核对，请重新读取首页。')
  }
  const keys = value.items.map(item => `${item.objectKind}:${item.objectId}`)
  if (new Set(keys).size !== keys.length) invalid('回收站清单含重复身份，请重新读取首页。')
  if (value.nextCursor !== null) {
    const last = value.items.at(-1)
    if (!last || value.nextCursor !== `${last.objectKind}~${last.objectId}`) invalid('回收站游标与当前页最后一项身份不一致，请重新读取首页。')
  }
  return value as PrivacyTrashPage
}
export async function readPrivacyStatus(): Promise<PrivacyStatus> { return checkedPrivacyStatus(await read<unknown>(`${base}/status`)) }
export async function readPrivacyTrash(cursor?: string): Promise<PrivacyTrashPage> {
  return checkedPrivacyTrash(await read<unknown>(`${base}/trash?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`))
}
export async function previewPrivacy(action: PrivacyAction, selected: PrivacySelection): Promise<PrivacyPreview> {
  return checkedPrivacyPreview(await preview<unknown>(`${base}/${action.toLowerCase()}/preview`, selected), action, selected)
}
export function privacyUtcDate(value: string): string {
  // 回收站DTO使用无偏移的UTC LocalDateTime，不能让浏览器按本机时区再次解释。
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(value) ? `${value}Z` : value
}
