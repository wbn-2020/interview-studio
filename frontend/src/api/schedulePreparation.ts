import { ApiError, errorText } from './client'
import { memoryUtcMicros } from './memoryReview'
import { todayDateValid, todayIdValid, todayTitleValid, todayZoneValid } from './today'
import { placementValid } from './todayPlan'
import type { TodayActionStamp, TodayActionState, TodayTargetRef, TodayTaskStamp, TodayTaskState } from './today'
import type { Placement } from './todayPlan'
import type { InterviewEvent, ScheduleTime, ScheduleTimeInput } from './schedule'
import type { PreparationTask } from './preparationTask'

export const preparationLinkKinds = ['PREPARATION_TASK', 'REVIEW_ACTION', 'COLLECTION'] as const
export type LinkKind = typeof preparationLinkKinds[number]
export type LinkStatus = 'ACTIVE' | 'REMOVED'
export type LinkChange = 'CREATED' | 'REMOVED' | 'RESTORED' | 'VERSION_CHANGED'
export type CollectionStamp = { revision: number; versionId: string }
type PreparationPayload<K extends LinkKind, P> = { kind: K } & Omit<{ task: null; action: null; collection: null }, keyof P> & P
// 题单固定版本是面试准备的独立关系，不扩展今日来源，也不将当前版本偷偷替换为关联版本。
export type PreparationSourceStamp = PreparationPayload<'PREPARATION_TASK', { task: TodayTaskStamp }>
  | PreparationPayload<'REVIEW_ACTION', { action: TodayActionStamp }> | PreparationPayload<'COLLECTION', { collection: CollectionStamp }>
export type CollectionState = { kind: 'LIST' | 'ROUTE'; status: 'ACTIVE' | 'ARCHIVED'; currentVersionId: string; linkedVersionNumber: number; versionState: 'CURRENT' | 'SUPERSEDED' | 'ARCHIVED' }
export type PreparationSourceStatus = PreparationPayload<'PREPARATION_TASK', { task: TodayTaskState }>
  | PreparationPayload<'REVIEW_ACTION', { action: TodayActionState }> | PreparationPayload<'COLLECTION', { collection: CollectionState }>
export type EventReference = { id: string; revision: number; status: 'PLANNED' | 'COMPLETED' | 'CANCELLED'; targetId: string | null; targetVersionId: string | null }
export type PreparationLink = { id: string; eventId: string; revision: number; kind: LinkKind; sourceId: string; sourceVersionId: string | null;
  status: LinkStatus; createdAt: string; updatedAt: string }
export type LinkEntry = { link: PreparationLink; title: string; currentSource: PreparationSourceStamp; sourceStatus: PreparationSourceStatus; targetRefs: TodayTargetRef[]; pendingCount: number | null }
export type LinkPage = { event: EventReference; items: LinkEntry[]; nextCursor: string | null; asOf: string; serverTime: string }
export type LinkQuery = { status?: LinkStatus; cursor?: string; limit?: number }
export type LinkHistoryQuery = { before?: number; limit?: number }
export type CreatePreparationLink = { expectedEventRevision: number; kind: LinkKind; sourceId: string; sourceVersionId: string | null; expectedSource: PreparationSourceStamp }
export type ChangePreparationLinkStatus = { expectedEventRevision: number; expectedRevision: number; status: LinkStatus }
export type ChangeCollectionVersion = { expectedEventRevision: number; expectedRevision: number; sourceVersionId: string; expectedSource: PreparationSourceStamp }
export type LinkHistory = { change: LinkChange; snapshot: PreparationLink; eventRevision: number; sourceStamp: PreparationSourceStamp | null; createdAt: string }
export type LinkHistoryPage = { items: LinkHistory[]; nextBefore: number | null }
export type PlanDate = { plannedDate: string; zoneId: string }
export type DateOverride = { linkId: string; plannedDate: string }
export type ReschedulePlanRequest = { expectedEventRevision: number; time: ScheduleTimeInput; remindBeforeMinutes: number; selectedLinkIds: string[]; dateOverrides: DateOverride[] }
export type OtherInterview = { linkId: string; linkRevision: number; eventId: string; eventRevision: number; status: 'PLANNED' | 'COMPLETED' | 'CANCELLED'; title: string; startAt: string; zoneId: string }
export const preparationMoveReasons = ['MOVABLE', 'STARTED', 'DATE_LOCKED', 'NO_DATE', 'PAUSED', 'TERMINAL', 'NO_PENDING_WORK', 'SOURCE_CHANGED', 'REFERENCE_ONLY', 'MULTIPLE_EVENTS'] as const
export type MoveReason = typeof preparationMoveReasons[number]
export type MoveWarning = 'AFTER_INTERVIEW_DATE'
export const rescheduleLimitations = ['EXPLICIT_SELECTION_ONLY', 'ORIGINAL_ZONES_PRESERVED', 'NO_TIME_RESERVATION', 'COLLECTION_REFERENCE_ONLY'] as const
export type RescheduleLimitation = typeof rescheduleLimitations[number]
export type RescheduleLinkCheck = { link: PreparationLink; sourceStamp: PreparationSourceStamp; sourceStatus: PreparationSourceStatus; pendingCount: number | null;
  placement: Placement | null; from: PlanDate | null; proposed: PlanDate | null; started: boolean; otherInterviews: OtherInterview[]; reasons: MoveReason[]; warnings: MoveWarning[] }
export type PreparationDateChange = { linkId: string; from: PlanDate; to: PlanDate }
export type ReschedulePlanPreview = { from: ScheduleTime; to: ScheduleTime; remindAt: string; linkChecks: RescheduleLinkCheck[]; changes: PreparationDateChange[]; previewHash: string; limitations: RescheduleLimitation[] }
export type ApplyReschedulePlan = { request: ReschedulePlanRequest; expectedPreviewHash: string; confirmed: true; reason: string }
export type AppliedReschedulePlan = { event: InterviewEvent; changedTasks: PreparationTask[]; changedPlacements: Placement[]; linkChecks: RescheduleLinkCheck[] }

export const preparationLinkKindLabels: Record<LinkKind, string> = { PREPARATION_TASK: '普通准备任务', REVIEW_ACTION: '改进动作', COLLECTION: '题单或路线引用' }
export const preparationLinkStatusLabels: Record<LinkStatus, string> = { ACTIVE: '有效关联', REMOVED: '已移除' }
export const preparationLinkChangeLabels: Record<LinkChange, string> = { CREATED: '建立关联', REMOVED: '移除关联', RESTORED: '恢复原关联', VERSION_CHANGED: '明确采用当前题单版本' }
export const preparationMoveReasonLabels: Record<MoveReason, string> = {
  MOVABLE: '可以联动调整日期', STARTED: '准备事项已经开始', DATE_LOCKED: '安排日期已锁定', NO_DATE: '尚无原安排日期', PAUSED: '任务或安排推荐已暂停',
  TERMINAL: '来源已完成或取消', NO_PENDING_WORK: '没有待处理的动作来源', SOURCE_CHANGED: '来源已合并、归档或改版', REFERENCE_ONLY: '集合仅作确切版本引用', MULTIPLE_EVENTS: '同时关联其他待进行面试',
}
export const preparationMoveWarningLabels: Record<MoveWarning, string> = { AFTER_INTERVIEW_DATE: '提议日期晚于该事项时区中的新面试日期' }
export const rescheduleLimitationLabels: Record<RescheduleLimitation, string> = {
  EXPLICIT_SELECTION_ONLY: '只调整本次明确选择的准备事项', ORIGINAL_ZONES_PRESERVED: '每项保留原安排时区', NO_TIME_RESERVATION: '日期调整不表示可用时间已预留', COLLECTION_REFERENCE_ONLY: '题单和路线仅作引用，不拆成任务',
}

const fields = (value: unknown, names: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(key => Object.hasOwn(value, key)))
const integer = (value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
const oneOf = <T extends string>(value: unknown, values: readonly T[]): value is T => typeof value === 'string' && values.includes(value as T)
const check = (rule: () => boolean) => { try { return rule() } catch { return false } }
const bounded = (value: unknown, max = 1048576) => new TextEncoder().encode(JSON.stringify(value)).length <= max
const timestamp = (value: unknown): value is string => memoryUtcMicros(value) !== undefined
const nullableTime = (value: unknown): value is string | null => value === null || timestamp(value)
const nullableId = (value: unknown): value is string | null => value === null || todayIdValid(value)
const compareTime = (a: string, b: string) => memoryUtcMicros(a)! < memoryUtcMicros(b)! ? -1 : memoryUtcMicros(a)! > memoryUtcMicros(b)! ? 1 : 0
const hashValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const cursorValid = (value: unknown): value is string => check(() => {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,5462}$/.test(value)) return false
  const decoded = atob(value.replaceAll('-', '+').replaceAll('_', '/'))
  return decoded.length <= 4096 && btoa(decoded).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '') === value
})
const nonblank = (value: string) => /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
const textValid = (value: unknown, max: number, required = false): value is string => typeof value === 'string' && value.length <= max && (!required || nonblank(value)) && !/[\u0000\uD800-\uDFFF]/u.test(value)
const reasonValid = (value: unknown): value is string => typeof value === 'string' && Array.from(value).length <= 2000
  && /[^\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]/u.test(value) && !/[\u0000\uD800-\uDFFF]/u.test(value)
const orderedEnums = <T extends string>(value: unknown, values: readonly T[]): value is T[] => Array.isArray(value)
  && value.every((item, index) => oneOf(item, values) && (index === 0 || values.indexOf(value[index - 1]) < values.indexOf(item)))
function equal(left: unknown, right: unknown): boolean {
  if (left === right) return true
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object' || Array.isArray(left) !== Array.isArray(right)) return false
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every(key => Object.hasOwn(right, key)
    && equal((left as Record<string, unknown>)[key], (right as Record<string, unknown>)[key]))
}
function union(value: unknown, kind?: LinkKind): value is Record<string, unknown> {
  if (!fields(value, ['kind', 'task', 'action', 'collection']) || !oneOf(value.kind, preparationLinkKinds) || kind !== undefined && value.kind !== kind) return false
  const key = value.kind === 'PREPARATION_TASK' ? 'task' : value.kind === 'REVIEW_ACTION' ? 'action' : 'collection'
  return ['task', 'action', 'collection'].every(name => name === key ? value[name] !== null : value[name] === null)
}
export function preparationSourceStampValid(value: unknown, kind?: LinkKind): value is PreparationSourceStamp {
  if (!union(value, kind)) return false
  if (value.kind === 'PREPARATION_TASK') return fields(value.task, ['revision']) && integer(value.task.revision)
  if (value.kind === 'REVIEW_ACTION') return fields(value.action, ['revision', 'progressHash']) && integer(value.action.revision, 0, 1000) && hashValid(value.action.progressHash)
  return fields(value.collection, ['revision', 'versionId']) && integer(value.collection.revision) && todayIdValid(value.collection.versionId)
}
function taskStateValid(value: unknown): value is TodayTaskState {
  return fields(value, ['status', 'startedAt', 'completedAt']) && oneOf(value.status, ['TODO', 'IN_PROGRESS', 'PAUSED', 'DONE', 'CANCELLED'])
    && nullableTime(value.startedAt) && nullableTime(value.completedAt) && (value.status === 'TODO' ? value.startedAt === null : value.status !== 'IN_PROGRESS' || value.startedAt !== null)
    && (value.status === 'DONE') === (value.completedAt !== null) && (value.startedAt === null || value.completedAt === null || compareTime(value.startedAt, value.completedAt) <= 0)
}
export function preparationSourceStatusValid(value: unknown, stamp: PreparationSourceStamp, link?: PreparationLink): value is PreparationSourceStatus {
  if (!preparationSourceStampValid(stamp) || !union(value, stamp.kind) || link !== undefined && link.kind !== stamp.kind) return false
  if (stamp.kind === 'PREPARATION_TASK') return taskStateValid(value.task)
  if (stamp.kind === 'REVIEW_ACTION') return fields(value.action, ['status', 'mergedInto', 'sourceCount', 'trackingCount'])
    && integer(value.action.sourceCount, 0, 20) && integer(value.action.trackingCount, 0, value.action.sourceCount)
    && (value.action.status === 'OPEN' ? value.action.mergedInto === null : value.action.status === 'MERGED' && todayIdValid(value.action.mergedInto) && value.action.mergedInto !== link?.sourceId)
  const collection = value.collection
  if (!fields(collection, ['kind', 'status', 'currentVersionId', 'linkedVersionNumber', 'versionState']) || !oneOf(collection.kind, ['LIST', 'ROUTE'])
    || !oneOf(collection.status, ['ACTIVE', 'ARCHIVED']) || collection.currentVersionId !== stamp.collection.versionId || !integer(collection.linkedVersionNumber, 1, 2147483647)
    || !oneOf(collection.versionState, ['CURRENT', 'SUPERSEDED', 'ARCHIVED'])) return false
  return collection.status === 'ARCHIVED' ? collection.versionState === 'ARCHIVED' : collection.versionState !== 'ARCHIVED'
    && (link === undefined || collection.versionState === (link.sourceVersionId === collection.currentVersionId ? 'CURRENT' : 'SUPERSEDED'))
}
export function preparationEventReferenceValid(value: unknown, eventId?: string): value is EventReference {
  return fields(value, ['id', 'revision', 'status', 'targetId', 'targetVersionId']) && todayIdValid(value.id) && (eventId === undefined || value.id === eventId)
    && integer(value.revision) && oneOf(value.status, ['PLANNED', 'COMPLETED', 'CANCELLED']) && nullableId(value.targetId) && nullableId(value.targetVersionId)
    && (value.targetId === null) === (value.targetVersionId === null)
}
export function preparationLinkValid(value: unknown, eventId?: string, linkId?: string): value is PreparationLink {
  return check(() => fields(value, ['id', 'eventId', 'revision', 'kind', 'sourceId', 'sourceVersionId', 'status', 'createdAt', 'updatedAt'])
    && todayIdValid(value.id) && todayIdValid(value.eventId) && integer(value.revision, 0, 1000) && oneOf(value.kind, preparationLinkKinds) && todayIdValid(value.sourceId)
    && (value.kind === 'COLLECTION' ? todayIdValid(value.sourceVersionId) : value.sourceVersionId === null) && oneOf(value.status, ['ACTIVE', 'REMOVED'])
    && timestamp(value.createdAt) && timestamp(value.updatedAt) && compareTime(value.createdAt, value.updatedAt) <= 0
    && (value.revision !== 0 || value.status === 'ACTIVE' && compareTime(value.createdAt, value.updatedAt) === 0)
    && (eventId === undefined || value.eventId === eventId) && (linkId === undefined || value.id === linkId) && bounded(value, 32768))
}
function pendingValid(value: unknown, status: PreparationSourceStatus): value is number | null {
  return status.kind === 'REVIEW_ACTION' ? integer(value, 0, status.action.trackingCount) : value === null
}
function targetsValid(value: unknown, kind: LinkKind): value is TodayTargetRef[] {
  return Array.isArray(value) && value.length <= (kind === 'COLLECTION' ? 0 : kind === 'PREPARATION_TASK' ? 1 : 20)
    && value.every(item => fields(item, ['itemId', 'versionId']) && todayIdValid(item.itemId) && todayIdValid(item.versionId))
    && value.every((item, index) => index === 0 || `${value[index - 1].itemId}/${value[index - 1].versionId}` < `${item.itemId}/${item.versionId}`)
}
export function preparationLinkEntryValid(value: unknown, eventId?: string): value is LinkEntry {
  return check(() => fields(value, ['link', 'title', 'currentSource', 'sourceStatus', 'targetRefs', 'pendingCount']) && preparationLinkValid(value.link, eventId)
    && todayTitleValid(value.title) && preparationSourceStampValid(value.currentSource, value.link.kind) && preparationSourceStatusValid(value.sourceStatus, value.currentSource, value.link)
    && pendingValid(value.pendingCount, value.sourceStatus) && targetsValid(value.targetRefs, value.link.kind) && bounded(value, 32768))
}
export function preparationLinkPageValid(value: unknown, eventId?: string, expected?: LinkQuery): value is LinkPage {
  return check(() => {
    if (expected !== undefined && !preparationLinkQueryValid(expected) || !fields(value, ['event', 'items', 'nextCursor', 'asOf', 'serverTime']) || !preparationEventReferenceValid(value.event, eventId)
      || !timestamp(value.asOf) || !timestamp(value.serverTime) || compareTime(value.asOf, value.serverTime) > 0 || memoryUtcMicros(value.serverTime)! - memoryUtcMicros(value.asOf)! > 900000000n
      || expected !== undefined && expected.cursor === undefined && compareTime(value.asOf, value.serverTime) !== 0
      || !Array.isArray(value.items) || value.items.length > (expected === undefined ? 50 : expected.limit ?? 20) || !(value.nextCursor === null || cursorValid(value.nextCursor))
      || value.nextCursor !== null && value.nextCursor === expected?.cursor || !bounded(value)) return false
    const id = value.event.id
    if (!value.items.every(item => preparationLinkEntryValid(item, id) && (expected?.status === undefined || item.link.status === expected.status))) return false
    const items = value.items as LinkEntry[]
    // asOf 固定分页时刻而非数据库快照上界；当前来源和关联时间可来自随后提交的合法记录。
    return (value.nextCursor === null || items.length > 0 && (expected === undefined || items.length === (expected.limit ?? 20)))
      && items.every((item, index) => index === 0 || items[index - 1]!.link.id < item.link.id)
      && new Set(items.map(item => `${item.link.kind}/${item.link.sourceId}`)).size === items.length
  })
}
export function preparationLinkHistoryPageValid(value: unknown, eventId?: string, linkId?: string, before?: number, limit = 20): value is LinkHistoryPage {
  return check(() => {
    if (eventId !== undefined && !todayIdValid(eventId) || linkId !== undefined && !todayIdValid(linkId) || before !== undefined && !integer(before, 0, 1000) || !integer(limit, 1, 50)
      || !fields(value, ['items', 'nextBefore']) || !Array.isArray(value.items) || value.items.length > limit || value.items.length === 0 && before !== 0
      || !(value.nextBefore === null || integer(value.nextBefore, 1, 1000)) || !bounded(value)) return false
    let previous: LinkHistory | undefined
    for (const entry of value.items) {
      if (!fields(entry, ['change', 'snapshot', 'eventRevision', 'sourceStamp', 'createdAt']) || !preparationLinkValid(entry.snapshot, eventId, linkId)
        || !oneOf(entry.change, ['CREATED', 'REMOVED', 'RESTORED', 'VERSION_CHANGED']) || !integer(entry.eventRevision) || !timestamp(entry.createdAt)
        || compareTime(entry.createdAt, entry.snapshot.updatedAt) !== 0 || (entry.change === 'CREATED') !== (entry.snapshot.revision === 0)
        || (entry.change === 'REMOVED' ? entry.snapshot.status !== 'REMOVED' || entry.sourceStamp !== null
          : entry.snapshot.status !== 'ACTIVE' || !preparationSourceStampValid(entry.sourceStamp, entry.snapshot.kind))
        || entry.change === 'VERSION_CHANGED' && entry.snapshot.kind !== 'COLLECTION' || before !== undefined && entry.snapshot.revision >= before || !bounded(entry, 32768)) return false
      const item = entry as LinkHistory
      if (item.snapshot.kind === 'COLLECTION' && (item.change === 'CREATED' || item.change === 'VERSION_CHANGED') && item.snapshot.sourceVersionId !== item.sourceStamp?.collection?.versionId) return false
      if (previous !== undefined && (item.snapshot.revision !== previous.snapshot.revision - 1 || item.eventRevision > previous.eventRevision
        || item.snapshot.id !== previous.snapshot.id || item.snapshot.eventId !== previous.snapshot.eventId || item.snapshot.kind !== previous.snapshot.kind
        || item.snapshot.sourceId !== previous.snapshot.sourceId || item.snapshot.createdAt !== previous.snapshot.createdAt || compareTime(item.createdAt, previous.createdAt) > 0)) return false
      if (previous !== undefined && (previous.change === 'RESTORED' ? item.snapshot.status !== 'REMOVED' || item.snapshot.sourceVersionId !== previous.snapshot.sourceVersionId
        : previous.change === 'REMOVED' ? item.snapshot.status !== 'ACTIVE' || item.snapshot.sourceVersionId !== previous.snapshot.sourceVersionId
          : previous.change !== 'VERSION_CHANGED' || item.snapshot.status !== 'ACTIVE' || item.snapshot.sourceVersionId === previous.snapshot.sourceVersionId)) return false
      previous = item
    }
    return value.nextBefore === null ? previous === undefined || previous.snapshot.revision === 0
      : previous !== undefined && value.items.length === limit && value.nextBefore === previous.snapshot.revision
  })
}

export function createPreparationLinkValid(value: unknown): value is CreatePreparationLink {
  return check(() => fields(value, ['expectedEventRevision', 'kind', 'sourceId', 'sourceVersionId', 'expectedSource'])
    && integer(value.expectedEventRevision) && oneOf(value.kind, preparationLinkKinds) && todayIdValid(value.sourceId)
    && (value.kind === 'COLLECTION' ? todayIdValid(value.sourceVersionId) : value.sourceVersionId === null)
    && preparationSourceStampValid(value.expectedSource, value.kind)
    && (value.kind !== 'COLLECTION' || value.expectedSource.collection?.versionId === value.sourceVersionId) && bounded(value, 262144))
}
export function changePreparationLinkStatusValid(value: unknown): value is ChangePreparationLinkStatus {
  return check(() => fields(value, ['expectedEventRevision', 'expectedRevision', 'status']) && integer(value.expectedEventRevision)
    && integer(value.expectedRevision, 0, 1000) && oneOf(value.status, ['ACTIVE', 'REMOVED']) && bounded(value, 262144))
}
export function changeCollectionVersionValid(value: unknown): value is ChangeCollectionVersion {
  return check(() => fields(value, ['expectedEventRevision', 'expectedRevision', 'sourceVersionId', 'expectedSource']) && integer(value.expectedEventRevision)
    && integer(value.expectedRevision, 0, 1000) && todayIdValid(value.sourceVersionId) && preparationSourceStampValid(value.expectedSource, 'COLLECTION')
    && value.expectedSource.collection?.versionId === value.sourceVersionId && bounded(value, 262144))
}
export function createdPreparationLinkValid(value: unknown, eventId: string, request: CreatePreparationLink): value is PreparationLink {
  return createPreparationLinkValid(request) && preparationLinkValid(value, eventId) && value.revision === 0 && value.status === 'ACTIVE'
    && value.kind === request.kind && value.sourceId === request.sourceId && value.sourceVersionId === request.sourceVersionId
}
function sameLinkIdentity(value: PreparationLink, before: PreparationLink): boolean {
  return value.id === before.id && value.eventId === before.eventId && value.kind === before.kind && value.sourceId === before.sourceId
    && value.createdAt === before.createdAt && compareTime(before.updatedAt, value.updatedAt) <= 0
}
export function changedPreparationLinkStatusValid(value: unknown, before: PreparationLink, request: ChangePreparationLinkStatus): value is PreparationLink {
  return preparationLinkValid(before) && changePreparationLinkStatusValid(request) && before.revision === request.expectedRevision
    && preparationLinkValid(value, before.eventId, before.id) && sameLinkIdentity(value, before) && value.sourceVersionId === before.sourceVersionId
    && value.status === request.status && (before.status === request.status ? equal(value, before) : value.revision === before.revision + 1)
}
export function changedCollectionVersionValid(value: unknown, before: PreparationLink, request: ChangeCollectionVersion): value is PreparationLink {
  return preparationLinkValid(before) && before.kind === 'COLLECTION' && before.status === 'ACTIVE' && changeCollectionVersionValid(request)
    && before.revision === request.expectedRevision && preparationLinkValid(value, before.eventId, before.id) && sameLinkIdentity(value, before)
    && value.status === 'ACTIVE' && value.sourceVersionId === request.sourceVersionId
    && (before.sourceVersionId === request.sourceVersionId ? equal(value, before) : value.revision === before.revision + 1)
}

function offsetSeconds(value: unknown): number | undefined {
  if (value === 'Z') return 0
  if (typeof value !== 'string') return undefined
  const parts = /^([+-])(\d{1,2})$/.exec(value) ?? /^([+-])(\d{2})(\d{2})(\d{2})?$/.exec(value) ?? /^([+-])(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value)
  if (!parts) return undefined
  const hour = Number(parts[2]), minute = Number(parts[3] ?? 0), second = Number(parts[4] ?? 0)
  return hour > 18 || minute > 59 || second > 59 || hour === 18 && (minute !== 0 || second !== 0) ? undefined
    : (parts[1] === '-' ? -1 : 1) * (hour * 3600 + minute * 60 + second)
}
function canonicalOffset(seconds: number): string {
  if (seconds === 0) return 'Z'
  const absolute = Math.abs(seconds), part = (n: number) => String(n).padStart(2, '0')
  return `${seconds < 0 ? '-' : '+'}${part(Math.floor(absolute / 3600))}:${part(Math.floor(absolute % 3600 / 60))}${absolute % 60 ? `:${part(absolute % 60)}` : ''}`
}
function localMinuteValid(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d$/.test(value) && todayDateValid(value.slice(0, 10))
}
export function rescheduleTimeInputValid(value: unknown): value is ScheduleTimeInput {
  return fields(value, ['localStart', 'zoneId', 'offset', 'durationMinutes']) && localMinuteValid(value.localStart) && todayZoneValid(value.zoneId)
    && (value.offset === null || offsetSeconds(value.offset) !== undefined) && integer(value.durationMinutes, 1, 1440)
}
export function rescheduleTimeValid(value: unknown): value is ScheduleTime {
  if (!fields(value, ['localStart', 'zoneId', 'offset', 'startAt', 'endAt', 'durationMinutes']) || !localMinuteValid(value.localStart)
    || !todayZoneValid(value.zoneId) || !timestamp(value.startAt) || !timestamp(value.endAt) || !integer(value.durationMinutes, 1, 1440)) return false
  const offset = offsetSeconds(value.offset)
  // 已保存时间按原本地输入与原偏移核对 Instant，不用浏览器的现行时区规则重解旧记录。
  return offset !== undefined && value.offset === canonicalOffset(offset)
    && memoryUtcMicros(value.startAt) === BigInt(Date.parse(`${value.localStart}:00Z`)) * 1000n - BigInt(offset) * 1000000n
    && memoryUtcMicros(value.endAt)! - memoryUtcMicros(value.startAt)! === BigInt(value.durationMinutes) * 60000000n
}
function timeMatchesInput(time: ScheduleTime, input: ScheduleTimeInput): boolean {
  return time.localStart === input.localStart && time.zoneId === input.zoneId && time.durationMinutes === input.durationMinutes
    && (input.offset === null || offsetSeconds(time.offset) === offsetSeconds(input.offset))
}
export function reschedulePlanRequestValid(value: unknown): value is ReschedulePlanRequest {
  return check(() => {
    if (!fields(value, ['expectedEventRevision', 'time', 'remindBeforeMinutes', 'selectedLinkIds', 'dateOverrides']) || !integer(value.expectedEventRevision)
      || !rescheduleTimeInputValid(value.time) || !integer(value.remindBeforeMinutes, 0, 10080) || !Array.isArray(value.selectedLinkIds)
      || value.selectedLinkIds.length > 50 || !value.selectedLinkIds.every(todayIdValid) || new Set(value.selectedLinkIds).size !== value.selectedLinkIds.length
      || !Array.isArray(value.dateOverrides) || value.dateOverrides.length > 50 || !bounded(value, 262144)) return false
    const selected = value.selectedLinkIds
    let position = -1
    return value.dateOverrides.every(item => {
      if (!fields(item, ['linkId', 'plannedDate']) || !todayIdValid(item.linkId) || !todayDateValid(item.plannedDate)) return false
      const next = selected.indexOf(item.linkId)
      if (next <= position) return false
      position = next; return true
    })
  })
}
const inheritedTextValid = (value: unknown, max: number, required = false): value is string => typeof value === 'string' && value.length <= max && (!required || nonblank(value))
function meetingUrlValid(value: unknown): value is string {
  return check(() => {
    if (!inheritedTextValid(value, 2000)) return false
    if (value === '') return true
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) && Boolean(url.hostname) && !url.username && !url.password
  })
}
export function preparationInterviewEventValid(value: unknown, eventId?: string): value is InterviewEvent {
  return check(() => {
    if (!fields(value, ['id', 'targetId', 'targetVersionId', 'targetState', 'title', 'round', 'location', 'meetingUrl', 'notes', 'time', 'materials', 'materialReferences',
      'remindBeforeMinutes', 'remindAt', 'reminderAcknowledgedAt', 'status', 'timing', 'revision', 'createdAt', 'updatedAt']) || !todayIdValid(value.id)
      || eventId !== undefined && value.id !== eventId || !integer(value.revision) || !nullableId(value.targetId) || !nullableId(value.targetVersionId)
      || (value.targetId === null ? value.targetVersionId !== null || value.targetState !== null : value.targetVersionId === null || !oneOf(value.targetState, ['CURRENT', 'SUPERSEDED', 'ARCHIVED']))
      || !inheritedTextValid(value.title, 200, true) || !inheritedTextValid(value.round, 100) || !inheritedTextValid(value.location, 500)
      || !meetingUrlValid(value.meetingUrl) || !inheritedTextValid(value.notes, 10000) || !rescheduleTimeValid(value.time) || !integer(value.remindBeforeMinutes, 0, 10080)
      || !timestamp(value.remindAt) || memoryUtcMicros(value.time.startAt)! - memoryUtcMicros(value.remindAt)! !== BigInt(value.remindBeforeMinutes) * 60000000n
      || !nullableTime(value.reminderAcknowledgedAt) || !oneOf(value.status, ['PLANNED', 'COMPLETED', 'CANCELLED'])
      || !oneOf(value.timing, ['UPCOMING', 'DUE', 'IN_PROGRESS', 'PAST', 'CLOSED']) || (value.status === 'PLANNED') === (value.timing === 'CLOSED')
      || !timestamp(value.createdAt) || !timestamp(value.updatedAt) || compareTime(value.createdAt, value.updatedAt) > 0
      || value.reminderAcknowledgedAt !== null && (compareTime(value.reminderAcknowledgedAt, value.createdAt) < 0 || compareTime(value.reminderAcknowledgedAt, value.updatedAt) > 0)
      || !Array.isArray(value.materials) || value.materials.length > 50 || !Array.isArray(value.materialReferences) || value.materialReferences.length !== value.materials.length) return false
    const references = value.materialReferences
    const identities = new Set<string>()
    return value.materials.every((item, index) => {
      if (!fields(item, ['kind', 'itemId', 'versionId']) || !oneOf(item.kind, ['PROJECT', 'FACT', 'EXPRESSION', 'KNOWLEDGE']) || !todayIdValid(item.itemId) || !todayIdValid(item.versionId)) return false
      const key = `${item.kind}/${item.itemId}/${item.versionId}`, reference = references[index]
      if (identities.has(key)) return false
      identities.add(key)
      return fields(reference, ['kind', 'itemId', 'versionId', 'title', 'number', 'state']) && reference.kind === item.kind && reference.itemId === item.itemId && reference.versionId === item.versionId
        && inheritedTextValid(reference.title, 200, true) && integer(reference.number, 1, 2147483647)
        && oneOf(reference.state, ['CURRENT', 'SUPERSEDED', 'WITHDRAWN', 'ARCHIVED', 'UNVERIFIED', 'REVIEW_REQUIRED'])
    }) && bounded(value)
  })
}
export function preparationTaskSnapshotValid(value: unknown, taskId?: string): value is PreparationTask {
  return check(() => {
    if (!fields(value, ['id', 'revision', 'content', 'status', 'target', 'sourceAiTaskId', 'sourceStep', 'startedAt', 'completedAt', 'createdAt', 'updatedAt'])
      || !todayIdValid(value.id) || taskId !== undefined && value.id !== taskId || !integer(value.revision)
      || !taskStateValid({ status: value.status, startedAt: value.startedAt, completedAt: value.completedAt })
      || !timestamp(value.createdAt) || !timestamp(value.updatedAt) || compareTime(value.createdAt, value.updatedAt) > 0
      || !nullableTime(value.startedAt) || !nullableTime(value.completedAt)
      || [value.startedAt, value.completedAt].some(time => time !== null && (compareTime(time, value.createdAt as string) < 0 || compareTime(time, value.updatedAt as string) > 0))) return false
    const content = value.content, target = value.target
    return fields(content, ['title', 'detail', 'minutes', 'plannedDate', 'zoneId']) && textValid(content.title, 200, true) && textValid(content.detail, 1000)
      && integer(content.minutes, 1, 1440) && (content.plannedDate === null || todayDateValid(content.plannedDate)) && todayZoneValid(content.zoneId)
      && (target === null || fields(target, ['itemId', 'versionId', 'number', 'title']) && todayIdValid(target.itemId) && todayIdValid(target.versionId)
        && integer(target.number, 1, 2147483647) && inheritedTextValid(target.title, 200, true))
      && (value.sourceAiTaskId === null ? value.sourceStep === null : todayIdValid(value.sourceAiTaskId) && integer(value.sourceStep, 1, 20) && target !== null)
      && bounded(value, 32768)
  })
}

function planDateValid(value: unknown): value is PlanDate {
  return fields(value, ['plannedDate', 'zoneId']) && todayDateValid(value.plannedDate) && todayZoneValid(value.zoneId)
}
function otherInterviewValid(value: unknown, ownEventId: string, ownLinkId: string): value is OtherInterview {
  return fields(value, ['linkId', 'linkRevision', 'eventId', 'eventRevision', 'status', 'title', 'startAt', 'zoneId'])
    && todayIdValid(value.linkId) && value.linkId !== ownLinkId && integer(value.linkRevision, 0, 1000) && todayIdValid(value.eventId) && value.eventId !== ownEventId
    && integer(value.eventRevision) && value.status === 'PLANNED' && inheritedTextValid(value.title, 200, true) && timestamp(value.startAt) && todayZoneValid(value.zoneId)
}
function moveReasons(value: RescheduleLinkCheck): MoveReason[] {
  const reasons = new Set<MoveReason>()
  if (value.sourceStatus.kind === 'COLLECTION') {
    reasons.add('REFERENCE_ONLY')
    if (value.sourceStatus.collection.versionState !== 'CURRENT') reasons.add('SOURCE_CHANGED')
  } else {
    if (value.sourceStatus.kind === 'PREPARATION_TASK') {
      const status = value.sourceStatus.task.status
      if (status === 'IN_PROGRESS') reasons.add('STARTED')
      if (status === 'PAUSED') reasons.add('PAUSED')
      if (status === 'DONE' || status === 'CANCELLED') reasons.add('TERMINAL')
    } else {
      if (value.sourceStatus.action.status !== 'OPEN') reasons.add('SOURCE_CHANGED')
      if (value.pendingCount === 0) reasons.add('NO_PENDING_WORK')
      if (value.placement?.visibility === 'PAUSED') reasons.add('PAUSED')
    }
    if (value.started) reasons.add('STARTED')
    if (value.from === null) reasons.add('NO_DATE')
    if (value.placement?.dateLocked) reasons.add('DATE_LOCKED')
    if (value.otherInterviews.length) reasons.add('MULTIPLE_EVENTS')
  }
  return reasons.size === 0 ? ['MOVABLE'] : preparationMoveReasons.filter(reason => reasons.has(reason))
}
export function rescheduleLinkCheckValid(value: unknown, eventId?: string): value is RescheduleLinkCheck {
  return check(() => {
    if (!fields(value, ['link', 'sourceStamp', 'sourceStatus', 'pendingCount', 'placement', 'from', 'proposed', 'started', 'otherInterviews', 'reasons', 'warnings'])
      || !preparationLinkValid(value.link, eventId) || value.link.status !== 'ACTIVE' || !preparationSourceStampValid(value.sourceStamp, value.link.kind)
      || !preparationSourceStatusValid(value.sourceStatus, value.sourceStamp, value.link) || !pendingValid(value.pendingCount, value.sourceStatus)
      || typeof value.started !== 'boolean' || !(value.from === null || planDateValid(value.from)) || !(value.proposed === null || planDateValid(value.proposed))
      || !orderedEnums(value.reasons, preparationMoveReasons) || value.reasons.length === 0 || !orderedEnums(value.warnings, ['AFTER_INTERVIEW_DATE'])
      || !Array.isArray(value.otherInterviews) || value.otherInterviews.length > 499 || !bounded(value)) return false
    const link = value.link
    if (!value.otherInterviews.every(item => otherInterviewValid(item, link.eventId, link.id))) return false
    const others = value.otherInterviews as OtherInterview[]
    if (new Set(others.map(item => item.linkId)).size !== others.length || others.some((item, index) => index > 0 && others[index - 1]!.eventId >= item.eventId)) return false
    if (link.kind === 'COLLECTION') {
      if (value.placement !== null || value.from !== null || value.proposed !== null || value.started || others.length) return false
    } else {
      if (value.placement !== null && !placementValid(value.placement, { kind: link.kind, id: link.sourceId })) return false
      if (value.sourceStatus.kind === 'PREPARATION_TASK' && value.started !== (value.sourceStatus.task.startedAt !== null)) return false
      if (link.kind === 'REVIEW_ACTION') {
        const expected = value.placement === null || value.placement.plannedDate === null ? null : { plannedDate: value.placement.plannedDate, zoneId: value.placement.planZoneId }
        if (!equal(value.from, expected)) return false
      }
    }
    const item = value as RescheduleLinkCheck
    const movable = item.reasons.length === 1 && item.reasons[0] === 'MOVABLE'
    return equal(item.reasons, moveReasons(item)) && (movable ? item.from !== null && item.proposed !== null && item.proposed.zoneId === item.from.zoneId
      : item.proposed === null && item.warnings.length === 0)
  })
}
function checksValid(value: unknown, request?: ReschedulePlanRequest, eventId?: string): value is RescheduleLinkCheck[] {
  if (!Array.isArray(value) || value.length > 50 || !value.every(item => rescheduleLinkCheckValid(item, eventId))) return false
  const checks = value as RescheduleLinkCheck[]
  if (new Set(checks.map(item => item.link.id)).size !== checks.length || new Set(checks.map(item => `${item.link.kind}/${item.link.sourceId}`)).size !== checks.length
    || checks.some(item => item.link.eventId !== checks[0]!.link.eventId)) return false
  return request === undefined || request.selectedLinkIds.length === checks.length && checks.every((item, index) => {
    if (item.link.id !== request.selectedLinkIds[index]) return false
    const override = request.dateOverrides.find(entry => entry.linkId === item.link.id)
    return override === undefined || item.proposed === null || item.proposed.plannedDate === override.plannedDate
  })
}
function changesFor(checks: RescheduleLinkCheck[]): PreparationDateChange[] {
  return checks.filter(item => item.proposed !== null && !equal(item.from, item.proposed))
    .map(item => ({ linkId: item.link.id, from: item.from!, to: item.proposed! }))
}
function sameResolvedTime(left: ScheduleTime, right: ScheduleTime): boolean {
  return left.localStart === right.localStart && left.zoneId === right.zoneId && left.offset === right.offset && left.durationMinutes === right.durationMinutes
    && compareTime(left.startAt, right.startAt) === 0 && compareTime(left.endAt, right.endAt) === 0
}
export function reschedulePlanPreviewValid(value: unknown, request?: ReschedulePlanRequest, event?: string | InterviewEvent): value is ReschedulePlanPreview {
  return check(() => {
    const previous = typeof event === 'string' ? undefined : event, eventId = typeof event === 'string' ? event : event?.id
    if (eventId !== undefined && !todayIdValid(eventId) || request !== undefined && !reschedulePlanRequestValid(request) || previous !== undefined && (!preparationInterviewEventValid(previous) || previous.status !== 'PLANNED'
      || request !== undefined && previous.revision !== request.expectedEventRevision)
      || !fields(value, ['from', 'to', 'remindAt', 'linkChecks', 'changes', 'previewHash', 'limitations']) || !rescheduleTimeValid(value.from) || !rescheduleTimeValid(value.to)
      || !timestamp(value.remindAt) || !hashValid(value.previewHash) || !equal(value.limitations, rescheduleLimitations)
      || !checksValid(value.linkChecks, request, eventId) || !equal(value.changes, changesFor(value.linkChecks)) || !bounded(value)) return false
    const lead = memoryUtcMicros(value.to.startAt)! - memoryUtcMicros(value.remindAt)!
    if (lead < 0n || lead > 604800000000n || lead % 60000000n !== 0n || previous !== undefined && !sameResolvedTime(value.from, previous.time)) return false
    // 默认日期推进、夏令时与警告由服务器按原事项区域计算；客户端保留原值，不用另一套 tzdb 重算或替换预览摘要。
    return request === undefined || timeMatchesInput(value.to, request.time) && lead === BigInt(request.remindBeforeMinutes) * 60000000n
  })
}
export function applyReschedulePlanValid(value: unknown, preview?: ReschedulePlanPreview): value is ApplyReschedulePlan {
  return check(() => fields(value, ['request', 'expectedPreviewHash', 'confirmed', 'reason']) && reschedulePlanRequestValid(value.request)
    && hashValid(value.expectedPreviewHash) && value.confirmed === true && reasonValid(value.reason) && bounded(value, 262144)
    && (preview === undefined || reschedulePlanPreviewValid(preview, value.request) && preview.previewHash === value.expectedPreviewHash
      && preview.linkChecks.every(item => equal(item.reasons, ['MOVABLE']))))
}
function unchangedEventFields(value: InterviewEvent, previous: InterviewEvent): boolean {
  const keys = ['id', 'targetId', 'targetVersionId', 'title', 'round', 'location', 'meetingUrl', 'notes', 'materials', 'createdAt'] as const
  return keys.every(key => equal(value[key], previous[key])) && compareTime(previous.updatedAt, value.updatedAt) <= 0
    && value.materialReferences.every((item, index) => item.title === previous.materialReferences[index]!.title && item.number === previous.materialReferences[index]!.number)
}
function eventReceiptTimingValid(value: InterviewEvent): boolean {
  const timing = compareTime(value.updatedAt, value.time.endAt) >= 0 ? 'PAST' : compareTime(value.updatedAt, value.time.startAt) >= 0 ? 'IN_PROGRESS'
    : compareTime(value.updatedAt, value.remindAt) < 0 ? 'UPCOMING' : 'DUE'
  return value.timing === timing
}
export function appliedReschedulePlanValid(value: unknown, expected?: ApplyReschedulePlan, preview?: ReschedulePlanPreview,
  basis?: string | InterviewEvent, previousTasks?: readonly PreparationTask[]): value is AppliedReschedulePlan {
  return check(() => {
    const previous = typeof basis === 'string' ? undefined : basis, eventId = typeof basis === 'string' ? basis : basis?.id
    if (eventId !== undefined && !todayIdValid(eventId) || !fields(value, ['event', 'changedTasks', 'changedPlacements', 'linkChecks']) || !preparationInterviewEventValid(value.event, eventId)
      || value.event.status !== 'PLANNED' || !eventReceiptTimingValid(value.event) || !checksValid(value.linkChecks, expected?.request, value.event.id)
      || value.linkChecks.some(item => !equal(item.reasons, ['MOVABLE'])) || !Array.isArray(value.changedTasks) || !Array.isArray(value.changedPlacements)
      || !bounded(value, 2097152)) return false
    const event = value.event, checks = value.linkChecks
    const changed = checks.filter(item => !equal(item.from, item.proposed))
    const tasks = changed.filter(item => item.link.kind === 'PREPARATION_TASK'), placements = changed.filter(item => item.link.kind === 'REVIEW_ACTION')
    if (value.changedTasks.length !== tasks.length || value.changedPlacements.length !== placements.length) return false
    if (!value.changedTasks.every((task, index) => {
      const item = tasks[index]!
      if (!preparationTaskSnapshotValid(task, item.link.sourceId) || task.status !== 'TODO' || task.startedAt !== null || task.completedAt !== null
        || task.revision !== item.sourceStamp.task!.revision + 1 || task.content.plannedDate !== item.proposed!.plannedDate || task.content.zoneId !== item.from!.zoneId
        || compareTime(task.updatedAt, event.updatedAt) !== 0) return false
      if (previousTasks === undefined) return true
      const prior = previousTasks.find(entry => entry.id === task.id)
      return preparationTaskSnapshotValid(prior, task.id) && prior.revision === item.sourceStamp.task!.revision && prior.status === 'TODO' && prior.startedAt === null
        && equal(prior.content, { ...task.content, plannedDate: item.from!.plannedDate }) && equal(prior.target, task.target)
        && prior.sourceAiTaskId === task.sourceAiTaskId && prior.sourceStep === task.sourceStep && prior.createdAt === task.createdAt && compareTime(prior.updatedAt, task.updatedAt) <= 0
    }) || !value.changedPlacements.every((placement, index) => {
      const item = placements[index]!, prior = item.placement!
      return placementValid(placement, { kind: 'REVIEW_ACTION', id: item.link.sourceId }) && compareTime(placement.updatedAt, event.updatedAt) === 0
        && compareTime(prior.updatedAt, placement.updatedAt) <= 0
        && equal(placement, { ...prior, revision: prior.revision + 1, plannedDate: item.proposed!.plannedDate, updatedAt: placement.updatedAt })
    })) return false
    if (expected === undefined) return preview === undefined && previous === undefined
    if (!applyReschedulePlanValid(expected, preview) || event.revision !== expected.request.expectedEventRevision + 1
      || !timeMatchesInput(event.time, expected.request.time) || event.remindBeforeMinutes !== expected.request.remindBeforeMinutes) return false
    // 回执中的检查记录属于写入前；实际变更对象才携带新修订。恢复旧幂等回执后仍须另读当前状态。
    if (preview !== undefined && (!reschedulePlanPreviewValid(preview, expected.request, basis) || !sameResolvedTime(event.time, preview.to)
      || compareTime(event.remindAt, preview.remindAt) !== 0 || !equal(checks, preview.linkChecks))) return false
    if (previous === undefined) return true
    if (!preparationInterviewEventValid(previous, event.id) || previous.status !== 'PLANNED' || previous.revision !== expected.request.expectedEventRevision || !unchangedEventFields(event, previous)) return false
    const timeChanged = !sameResolvedTime(event.time, previous.time) || event.remindBeforeMinutes !== previous.remindBeforeMinutes
    return timeChanged ? event.reminderAcknowledgedAt === null : event.reminderAcknowledgedAt === previous.reminderAcknowledgedAt
  })
}

function queryValid(value: unknown, allowed: readonly string[]): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every(key => allowed.includes(key)))
}
export function preparationLinkQueryValid(value: unknown): value is LinkQuery {
  return queryValid(value, ['status', 'cursor', 'limit']) && (value.status === undefined || oneOf(value.status, ['ACTIVE', 'REMOVED']))
    && (value.cursor === undefined || cursorValid(value.cursor)) && (value.limit === undefined || integer(value.limit, 1, 50))
}
function invalidQuery(): never { throw new ApiError(400, 'INVALID_REQUEST', '准备关联参数不符合约定，请核对对象、状态及分页位置。') }
function queryPath(path: string, query: Record<string, unknown>): string {
  const parameters = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) if (value !== undefined) parameters.set(key, String(value))
  return parameters.size === 0 ? path : `${path}?${parameters}`
}
export function preparationEventPath(eventId: string): string { if (!todayIdValid(eventId)) invalidQuery(); return `/schedule/interviews/${eventId}` }
export function preparationLinksPath(eventId: string, query: LinkQuery = {}): string {
  if (!preparationLinkQueryValid(query)) invalidQuery()
  return queryPath(`${preparationEventPath(eventId)}/preparation-links`, query)
}
function preparationLinkPath(eventId: string, linkId: string): string {
  if (!todayIdValid(linkId)) invalidQuery()
  return `${preparationEventPath(eventId)}/preparation-links/${linkId}`
}
export const preparationLinkStatusPath = (eventId: string, linkId: string) => `${preparationLinkPath(eventId, linkId)}/status`
export const preparationCollectionVersionPath = (eventId: string, linkId: string) => `${preparationLinkPath(eventId, linkId)}/collection-version`
export function preparationLinkHistoryPath(eventId: string, linkId: string, query: LinkHistoryQuery = {}): string {
  if (!queryValid(query, ['before', 'limit']) || query.before !== undefined && !integer(query.before, 0, 1000) || query.limit !== undefined && !integer(query.limit, 1, 50)) invalidQuery()
  return queryPath(`${preparationLinkPath(eventId, linkId)}/history`, query)
}
export const reschedulePlanPreviewPath = (eventId: string) => `${preparationEventPath(eventId)}/reschedule-plan-preview`
export const reschedulePlanPath = (eventId: string) => `${preparationEventPath(eventId)}/reschedule-plan`
export function schedulePreparationErrorText(error: unknown): string {
  if (!(error instanceof ApiError)) return errorText(error)
  const messages: Record<string, string> = {
    PREPARATION_LINK_NOT_FOUND: '准备关联不存在或不属于当前面试，请重新读取关联目录。',
    PREPARATION_LINK_EXISTS: '该来源已有关联，请读取原关联，再选择恢复或明确采用题单新版本。',
    PREPARATION_LINK_REVISION_CONFLICT: '准备关联已被其他操作修改，已保留输入，请重新读取。',
    PREPARATION_LINK_SOURCE_CHANGED: '准备来源已变化或不可用，已保留输入，请重新读取来源。',
    PREPARATION_LINK_NOT_ACTIVE: '选中关联已移除，请先明确恢复，再重新预览。',
    PREPARATION_LINK_NOT_MOVABLE: '选中事项包含不可移动项，本次联动未保存，请调整选择后重新预览。',
    PREPARATION_LINK_CURSOR_CHANGED: '准备关联分页已失效，请从首页重新读取。',
    PREPARATION_LINK_LIMIT: '本次关联或联动载荷超过上限，请缩小范围；也可使用只改面试时间。',
    RESCHEDULE_PLAN_CHANGED: '面试、关联或准备事项已变化，本次联动未保存，请重新预览。',
    RESCHEDULE_CLOCK_CHANGED: '服务器时间早于最近修改，已保留输入，请恢复原请求或重新核对。',
  }
  return messages[error.code] ?? errorText(error)
}
