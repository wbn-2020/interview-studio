import { ApiError } from './client'
import { memoryUtcMicros } from './memoryReview'
import {
  todayDateValid, todayDayContextValid, todayErrorText, todayIdValid, todayPlacementContentValid, todayPlacementValid,
  todaySourceContinuationValid, todaySourceKey, todaySourceKinds, todaySourceRefValid, todaySourceStampValid, todaySourceStatusValid,
  todaySourceTitleValid, todayZoneValid,
} from './today'
import type { TodayContinuation, TodayDayContext, TodaySourceKind, TodaySourceRef, TodaySourceStamp, TodaySourceStatus } from './today'

export type Visibility = 'VISIBLE' | 'PAUSED'
export type PlacementChangeKind = 'CREATED' | 'UPDATED' | 'ORDERED' | 'SUGGESTION_APPLIED' | 'INTERVIEW_RESCHEDULED'
export type BudgetChangeKind = 'CREATED' | 'UPDATED'
export type DayRef = { localDate: string; zoneId: string }
export type PlacementContent = { plannedDate: string | null; planZoneId: string | null; estimatedMinutes: number | null; priority: number;
  orderValue: number; dateLocked: boolean; visibility: Visibility; suppressedOn: DayRef | null }
export type Placement = PlacementContent & { id: string; source: TodaySourceRef; revision: number; createdAt: string; updatedAt: string }
export type CreatePlacement = { source: TodaySourceRef; expectedSource: TodaySourceStamp; content: PlacementContent; reason: string | null }
export type SavePlacement = { expectedRevision: number; expectedSource: TodaySourceStamp; content: PlacementContent; reason: string | null }
export type PlacementLookup = { source: TodaySourceRef; placement: Placement | null }
export type PlacementDirectoryQuery = { zoneId: string; kind?: TodaySourceKind | null; visibility?: Visibility | null; cursor?: string | null; limit?: number }
export type PlacementEntry = { placement: Placement; title: string; currentSource: TodaySourceStamp; sourceStatus: TodaySourceStatus; continuation: TodayContinuation }
export type PlacementPage = { context: TodayDayContext; items: PlacementEntry[]; nextCursor: string | null }
export type PlacementHistory = { change: PlacementChangeKind; snapshot: Placement; sourceStamp: TodaySourceStamp; reason: string | null; createdAt: string }
export type PlacementHistoryPage = { items: PlacementHistory[]; nextBefore: number | null }
export type OrderItem = { placementId: string; expectedRevision: number; expectedSource: TodaySourceStamp; orderValue: number }
export type OrderPlacements = { items: OrderItem[] }
export type OrderedPlacements = { placements: Placement[] }
export type DayBudget = DayRef & { revision: number; availableMinutes: number; createdAt: string; updatedAt: string }
export type CreateBudget = DayRef & { availableMinutes: number }
export type SaveBudget = { zoneId: string; expectedRevision: number; availableMinutes: number }
export type BudgetLookup = { budget: DayBudget | null }
export type BudgetHistory = { change: BudgetChangeKind; snapshot: DayBudget; createdAt: string }
export type BudgetHistoryPage = { items: BudgetHistory[]; nextBefore: number | null }
export type HistoryQuery = { before?: number | null; limit?: number }
export type BudgetHistoryQuery = HistoryQuery & { zoneId: string }
export type SuggestionCandidate = { source: TodaySourceRef; expectedSource: TodaySourceStamp; expectedPlacementRevision: number | null; estimatedMinutes: number | null }
export type SuggestionRequest = DayRef & { expectedBudgetRevision: number | null; items: SuggestionCandidate[] }
export const suggestionReasons = ['CONTINUE_WORK', 'SOURCE_DUE', 'PLACEMENT_DUE', 'UNSCHEDULED_WORK',
  'SOURCE_ARCHIVED', 'SOURCE_TERMINAL', 'NO_PENDING_WORK', 'SOURCE_PAUSED', 'SOURCE_DATE_REQUIRED', 'SOURCE_DATE_IN_FUTURE', 'MEMORY_SETTINGS_REQUIRED',
  'MEMORY_BLOCKED', 'MEMORY_NOT_DUE', 'MEMORY_NOT_RECOMMENDED', 'MEMORY_BUDGET_CONFIRMATION', 'PLACEMENT_PAUSED', 'SUPPRESSED_TODAY', 'DATE_LOCKED', 'ALREADY_STARTED',
  'ESTIMATE_REQUIRED', 'DAY_BUDGET_REQUIRED', 'OVER_MINUTES', 'INTERVIEW_REFERENCE_ONLY'] as const
export type SuggestionReason = typeof suggestionReasons[number]
export const suggestionLimitations = ['EXPLICIT_CANDIDATES_ONLY', 'NO_TIME_RESERVATION', 'ESTIMATES_ARE_NOT_ACTUAL_DURATION', 'MEMORY_BUDGET_SEPARATE', 'INTERVIEW_TIME_NOT_DEDUCTED'] as const
export type SuggestionLimitation = typeof suggestionLimitations[number]
export type SuggestionItem = { source: TodaySourceRef; sourceStamp: TodaySourceStamp; placementRevision: number | null; estimatedMinutes: number | null;
  recommended: boolean; reasonCodes: SuggestionReason[]; proposedContent: PlacementContent | null }
export type SuggestionRemaining = { sources: TodaySourceRef[]; count: number }
export type SuggestionPreview = { context: TodayDayContext; budget: DayBudget | null; items: SuggestionItem[]; selectedKnownMinutes: number; unknownEstimateCount: number;
  remaining: SuggestionRemaining; previewHash: string; limitations: SuggestionLimitation[] }
export type ApplySuggestion = { request: SuggestionRequest; expectedPreviewHash: string; selectedSources: TodaySourceRef[]; confirmed: true }
export type AppliedSuggestion = { placements: Placement[]; context: TodayDayContext }

export const placementChangeLabels: Record<PlacementChangeKind, string> = { CREATED: '建立安排偏好', UPDATED: '修改安排偏好', ORDERED: '调整展示顺序', SUGGESTION_APPLIED: '采用本次建议', INTERVIEW_RESCHEDULED: '面试联动改期' }
export const budgetChangeLabels: Record<BudgetChangeKind, string> = { CREATED: '建立分钟预算', UPDATED: '修改分钟预算' }
export const suggestionReasonLabels: Record<SuggestionReason, string> = {
  CONTINUE_WORK: '继续已有工作', SOURCE_DUE: '来源已到计划日或到期时间', PLACEMENT_DUE: '已到偏好安排日期', UNSCHEDULED_WORK: '可明确安排的待处理事项',
  SOURCE_ARCHIVED: '来源已归档', SOURCE_TERMINAL: '来源已结束或合并', NO_PENDING_WORK: '没有待处理工作', SOURCE_PAUSED: '来源已暂停',
  SOURCE_DATE_REQUIRED: '请先在原任务中安排日期', SOURCE_DATE_IN_FUTURE: '原来源日期尚未到达', MEMORY_SETTINGS_REQUIRED: '先配置常规回忆', MEMORY_BLOCKED: '卡片或来源条件阻断',
  MEMORY_NOT_DUE: '常规回忆尚未到期', MEMORY_NOT_RECOMMENDED: '未列入当前回忆建议', MEMORY_BUDGET_CONFIRMATION: '需在回忆队列确认超出建议数量',
  PLACEMENT_PAUSED: '安排推荐已暂停', SUPPRESSED_TODAY: '本显示日暂不推荐', DATE_LOCKED: '保留锁定日期', ALREADY_STARTED: '已开始的工作保留原日期',
  ESTIMATE_REQUIRED: '缺少预计学习分钟', DAY_BUDGET_REQUIRED: '尚未保存本显示日的分钟预算', OVER_MINUTES: '超出本次候选的分钟上限', INTERVIEW_REFERENCE_ONLY: '面试仅供参考，不计入学习分钟',
}
export const suggestionLimitationLabels: Record<SuggestionLimitation, string> = {
  EXPLICIT_CANDIDATES_ONLY: '只处理本次明确选择的候选', NO_TIME_RESERVATION: '预算不是全日预留或累计消耗账本', ESTIMATES_ARE_NOT_ACTUAL_DURATION: '预计分钟不代表实际学习时长',
  MEMORY_BUDGET_SEPARATE: '回忆张数额度仍由原回忆队列核对', INTERVIEW_TIME_NOT_DEDUCTED: '面试时长不自动扣减学习分钟',
}

const fields = (value: unknown, names: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
const nullableRevision = (value: unknown): value is number | null => value === null || integer(value)
const oneOf = <T extends string>(value: unknown, values: readonly T[]): value is T => typeof value === 'string' && values.includes(value as T)
const check = (rule: () => boolean): boolean => { try { return rule() } catch { return false } }
const bounded = (value: unknown, limit = 1048576) => new TextEncoder().encode(JSON.stringify(value)).length <= limit
const timestamp = (value: unknown): value is string => memoryUtcMicros(value) !== undefined
const compareTime = (left: string, right: string) => memoryUtcMicros(left)! < memoryUtcMicros(right)! ? -1 : memoryUtcMicros(left)! > memoryUtcMicros(right)! ? 1 : 0
const cursorValid = (value: unknown): value is string | null => value === null || typeof value === 'string' && /^[A-Za-z0-9_-]{1,4096}$/.test(value)
const hashValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const reasonValid = (value: unknown): value is string | null => value === null || typeof value === 'string' && Array.from(value).length >= 1
  && Array.from(value).length <= 2000 && /[^\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]/u.test(value) && !/[\u0000\uD800-\uDFFF]/u.test(value)
const estimateAllowed = (kind: TodaySourceKind) => kind !== 'PREPARATION_TASK' && kind !== 'INTERVIEW_EVENT'
const estimateValid = (value: unknown, kind: TodaySourceKind): value is number | null => value === null || estimateAllowed(kind) && integer(value, 1, 1440)
const dayValid = (value: unknown): value is DayRef => fields(value, ['localDate', 'zoneId']) && todayDateValid(value.localDate) && todayZoneValid(value.zoneId)
const sameSource = (left: TodaySourceRef, right: TodaySourceRef) => todaySourceKey(left) === todaySourceKey(right)
const sameDay = (left: DayRef, right: DayRef) => left.localDate === right.localDate && left.zoneId === right.zoneId
const uniqueSources = (sources: TodaySourceRef[]) => new Set(sources.map(todaySourceKey)).size === sources.length
function equal(left: unknown, right: unknown): boolean {
  if (left === right) return true
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object' || Array.isArray(left) !== Array.isArray(right)) return false
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every(key => Object.hasOwn(right, key)
    && equal((left as Record<string, unknown>)[key], (right as Record<string, unknown>)[key]))
}
function contentOf(value: Placement): PlacementContent {
  const { plannedDate, planZoneId, estimatedMinutes, priority, orderValue, dateLocked, visibility, suppressedOn } = value
  return { plannedDate, planZoneId, estimatedMinutes, priority, orderValue, dateLocked, visibility, suppressedOn }
}
function stampValid(value: unknown): value is TodaySourceStamp {
  return Boolean(value && typeof value === 'object' && 'kind' in value && oneOf(value.kind, todaySourceKinds) && todaySourceStampValid(value, value.kind))
}
export const placementValid = todayPlacementValid
export const placementContentValid = todayPlacementContentValid
export function placementContentProblem(value: unknown, kind: TodaySourceKind): string {
  return check(() => todayPlacementContentValid(value, kind)) ? '' : '请核对安排字段：优先级 1–5、顺序 0–1000000、估时 1–1440 分钟；日期与时区须成对填写，并遵守来源可编辑范围。'
}
export function placementLookupValid(value: unknown, source?: TodaySourceRef): value is PlacementLookup {
  return check(() => fields(value, ['source', 'placement']) && todaySourceRefValid(value.source) && (source === undefined || todaySourceRefValid(source) && sameSource(value.source, source))
    && (value.placement === null || placementValid(value.placement, value.source)) && bounded(value))
}
export function placementPageValid(value: unknown, expected?: PlacementDirectoryQuery): value is PlacementPage {
  return check(() => {
    if (!fields(value, ['context', 'items', 'nextCursor']) || !todayDayContextValid(value.context) || !Array.isArray(value.items)
      || value.items.length > (expected?.limit ?? 50) || !cursorValid(value.nextCursor) || !bounded(value)
      || expected !== undefined && (value.context.zoneId !== expected.zoneId || value.nextCursor !== null && value.nextCursor === expected.cursor)) return false
    const items: PlacementEntry[] = []
    for (const entry of value.items) {
      if (!fields(entry, ['placement', 'title', 'currentSource', 'sourceStatus', 'continuation']) || !placementValid(entry.placement)
        || !todaySourceTitleValid(entry.title, entry.placement.source.kind) || !todaySourceStampValid(entry.currentSource, entry.placement.source.kind) || !todaySourceStatusValid(entry.sourceStatus, entry.currentSource)
        || !todaySourceContinuationValid(entry.continuation, entry.placement.source, entry.sourceStatus)
        || expected?.kind != null && entry.placement.source.kind !== expected.kind || expected?.visibility != null && entry.placement.visibility !== expected.visibility) return false
      items.push(entry as PlacementEntry)
    }
    return new Set(items.map(item => item.placement.id)).size === items.length && uniqueSources(items.map(item => item.placement.source))
      && (value.nextCursor === null || items.length > 0 && (expected === undefined || items.length === (expected.limit ?? 20))) && items.every((item, index) => index === 0
        || compareTime(items[index - 1]!.placement.updatedAt, item.placement.updatedAt) > 0
        || compareTime(items[index - 1]!.placement.updatedAt, item.placement.updatedAt) === 0 && items[index - 1]!.placement.id < item.placement.id)
  })
}
function historyPageValid<T extends { snapshot: { revision: number; createdAt: string; updatedAt: string }; createdAt: string }>(
  value: unknown, entry: (item: unknown) => item is T, before: number | null | undefined, limit: number,
): value is { items: T[]; nextBefore: number | null } {
  if (!integer(limit, 1, 50) || before != null && !integer(before) || !fields(value, ['items', 'nextBefore'])
    || !Array.isArray(value.items) || value.items.length > limit || value.items.length === 0 && before !== 0 || !nullableRevision(value.nextBefore) || !bounded(value)) return false
  let previous: T | undefined
  for (const item of value.items) {
    if (!entry(item) || !bounded(item, 32768) || before != null && item.snapshot.revision >= before
      || previous !== undefined && (item.snapshot.revision !== previous.snapshot.revision - 1
        || compareTime(item.createdAt, previous.createdAt) > 0 || !equal(item.snapshot.createdAt, previous.snapshot.createdAt))) return false
    previous = item
  }
  // 历史是不可变修订链。下一页严格早于末项；修订 0 没有更旧记录，不能返回循环游标。
  return value.nextBefore === null ? previous === undefined || previous.snapshot.revision === 0
    : previous !== undefined && value.items.length === limit && previous.snapshot.revision > 0 && value.nextBefore === previous.snapshot.revision
}
export function placementHistoryPageValid(value: unknown, id?: string, source?: TodaySourceRef, before?: number | null, limit = 20): value is PlacementHistoryPage {
  return check(() => {
    if (id !== undefined && !todayIdValid(id) || source !== undefined && !todaySourceRefValid(source)) return false
    let expectedId = id, expectedSource = source
    return historyPageValid(value, (entry): entry is PlacementHistory => {
      if (!fields(entry, ['change', 'snapshot', 'sourceStamp', 'reason', 'createdAt']) || !placementValid(entry.snapshot, expectedSource)
        || !oneOf(entry.change, ['CREATED', 'UPDATED', 'ORDERED', 'SUGGESTION_APPLIED', 'INTERVIEW_RESCHEDULED']) || !todaySourceStampValid(entry.sourceStamp, entry.snapshot.source.kind)
        || !reasonValid(entry.reason) || !timestamp(entry.createdAt) || compareTime(entry.createdAt, entry.snapshot.updatedAt) !== 0
        || expectedId !== undefined && entry.snapshot.id !== expectedId || entry.change === 'CREATED' && entry.snapshot.revision !== 0
        || (entry.change === 'UPDATED' || entry.change === 'ORDERED') && entry.snapshot.revision === 0
        || entry.change === 'INTERVIEW_RESCHEDULED' && (entry.snapshot.revision === 0 || entry.snapshot.source.kind !== 'REVIEW_ACTION'
          || entry.snapshot.plannedDate === null || entry.snapshot.dateLocked || entry.snapshot.visibility !== 'VISIBLE' || entry.reason === null)
        || (entry.change === 'ORDERED' || entry.change === 'SUGGESTION_APPLIED') && entry.reason !== null) return false
      expectedId ??= entry.snapshot.id; expectedSource ??= entry.snapshot.source
      return true
    }, before, limit)
  })
}
export function dayBudgetValid(value: unknown, expected?: DayRef): value is DayBudget
export function dayBudgetValid(value: unknown, localDate: string, zoneId: string): value is DayBudget
export function dayBudgetValid(value: unknown, day?: DayRef | string, zoneId?: string): value is DayBudget {
  const expected = typeof day === 'string' ? { localDate: day, zoneId: zoneId ?? '' } : day
  return check(() => fields(value, ['localDate', 'zoneId', 'revision', 'availableMinutes', 'createdAt', 'updatedAt']) && todayDateValid(value.localDate)
    && todayZoneValid(value.zoneId) && integer(value.revision) && integer(value.availableMinutes, 0, 1440) && timestamp(value.createdAt) && timestamp(value.updatedAt)
    && compareTime(value.createdAt, value.updatedAt) <= 0 && (value.revision !== 0 || compareTime(value.createdAt, value.updatedAt) === 0)
    && (expected === undefined || dayValid(expected) && value.localDate === expected.localDate && value.zoneId === expected.zoneId) && bounded(value, 32768))
}
export function budgetLookupValid(value: unknown, expected?: DayRef): value is BudgetLookup
export function budgetLookupValid(value: unknown, localDate: string, zoneId: string): value is BudgetLookup
export function budgetLookupValid(value: unknown, day?: DayRef | string, zoneId?: string): value is BudgetLookup {
  const expected = typeof day === 'string' ? { localDate: day, zoneId: zoneId ?? '' } : day
  return check(() => fields(value, ['budget']) && (expected === undefined || dayValid(expected)) && (value.budget === null || dayBudgetValid(value.budget, expected)) && bounded(value))
}
export function budgetHistoryPageValid(value: unknown, expected?: DayRef, before?: number | null, limit?: number): value is BudgetHistoryPage
export function budgetHistoryPageValid(value: unknown, localDate: string, zoneId: string, before?: number | null, limit?: number): value is BudgetHistoryPage
export function budgetHistoryPageValid(value: unknown, dayOrDate?: DayRef | string, zoneOrBefore?: string | number | null, beforeOrLimit?: number | null, lastLimit = 20): value is BudgetHistoryPage {
  const expected = typeof dayOrDate === 'string' ? { localDate: dayOrDate, zoneId: typeof zoneOrBefore === 'string' ? zoneOrBefore : '' } : dayOrDate
  const before = typeof dayOrDate === 'string' ? beforeOrLimit : typeof zoneOrBefore === 'string' ? Number.NaN : zoneOrBefore
  const limit = typeof dayOrDate === 'string' ? lastLimit : beforeOrLimit ?? 20
  return check(() => {
    if (expected !== undefined && !dayValid(expected)) return false
    let day = expected
    return historyPageValid(value, (entry): entry is BudgetHistory => {
      if (!fields(entry, ['change', 'snapshot', 'createdAt']) || !dayBudgetValid(entry.snapshot, day) || !timestamp(entry.createdAt)
        || compareTime(entry.createdAt, entry.snapshot.updatedAt) !== 0 || (entry.change === 'CREATED' ? entry.snapshot.revision !== 0 : entry.change !== 'UPDATED' || entry.snapshot.revision === 0)) return false
      day ??= { localDate: entry.snapshot.localDate, zoneId: entry.snapshot.zoneId }
      return true
    }, before, limit)
  })
}

export function createPlacementValid(value: unknown): value is CreatePlacement {
  return check(() => fields(value, ['source', 'expectedSource', 'content', 'reason']) && todaySourceRefValid(value.source)
    && todaySourceStampValid(value.expectedSource, value.source.kind) && placementContentValid(value.content, value.source.kind) && reasonValid(value.reason) && bounded(value, 262144))
}
export function savePlacementValid(value: unknown, source?: TodaySourceRef): value is SavePlacement {
  return check(() => fields(value, ['expectedRevision', 'expectedSource', 'content', 'reason']) && integer(value.expectedRevision) && stampValid(value.expectedSource)
    && (source === undefined || todaySourceRefValid(source) && value.expectedSource.kind === source.kind)
    && placementContentValid(value.content, value.expectedSource.kind) && reasonValid(value.reason) && bounded(value, 262144))
}
export function orderPlacementsValid(value: unknown): value is OrderPlacements {
  return check(() => fields(value, ['items']) && Array.isArray(value.items) && value.items.length >= 1 && value.items.length <= 50 && value.items.every(item => fields(item, ['placementId', 'expectedRevision', 'expectedSource', 'orderValue'])
    && todayIdValid(item.placementId) && integer(item.expectedRevision) && stampValid(item.expectedSource) && integer(item.orderValue, 0, 1000000))
    && new Set(value.items.map(item => item.placementId)).size === value.items.length && bounded(value, 262144))
}
export function createBudgetValid(value: unknown): value is CreateBudget {
  return fields(value, ['localDate', 'zoneId', 'availableMinutes']) && todayDateValid(value.localDate) && todayZoneValid(value.zoneId) && integer(value.availableMinutes, 0, 1440)
}
export function saveBudgetValid(value: unknown): value is SaveBudget {
  return fields(value, ['zoneId', 'expectedRevision', 'availableMinutes']) && todayZoneValid(value.zoneId) && integer(value.expectedRevision) && integer(value.availableMinutes, 0, 1440)
}
export function suggestionRequestValid(value: unknown): value is SuggestionRequest {
  return check(() => fields(value, ['localDate', 'zoneId', 'expectedBudgetRevision', 'items']) && todayDateValid(value.localDate) && todayZoneValid(value.zoneId)
    && nullableRevision(value.expectedBudgetRevision) && Array.isArray(value.items) && value.items.length >= 1 && value.items.length <= 100
    && value.items.every(item => fields(item, ['source', 'expectedSource', 'expectedPlacementRevision', 'estimatedMinutes']) && todaySourceRefValid(item.source)
      && todaySourceStampValid(item.expectedSource, item.source.kind) && nullableRevision(item.expectedPlacementRevision) && estimateValid(item.estimatedMinutes, item.source.kind))
    && uniqueSources(value.items.map(item => item.source)) && bounded(value, 262144))
}
export function suggestionRequestProblem(value: unknown): string {
  return suggestionRequestValid(value) ? '' : '本次建议须明确选择 1–100 个不同来源，携带完整来源修订、安排修订或显式空值，并核对日期、时区与估时。'
}

const disqualifyingReasons: readonly SuggestionReason[] = ['SOURCE_ARCHIVED', 'SOURCE_TERMINAL', 'NO_PENDING_WORK', 'SOURCE_PAUSED', 'SOURCE_DATE_REQUIRED', 'SOURCE_DATE_IN_FUTURE',
  'MEMORY_SETTINGS_REQUIRED', 'MEMORY_BLOCKED', 'MEMORY_NOT_DUE', 'MEMORY_NOT_RECOMMENDED', 'MEMORY_BUDGET_CONFIRMATION', 'PLACEMENT_PAUSED', 'SUPPRESSED_TODAY', 'INTERVIEW_REFERENCE_ONLY']
function reasonForSource(reason: SuggestionReason, kind: TodaySourceKind): boolean {
  if (reason === 'CONTINUE_WORK') return ['TRAINING_SESSION', 'PREPARATION_TASK', 'MEMORY_CARD'].includes(kind)
  if (reason === 'SOURCE_DUE' || reason === 'SOURCE_PAUSED') return kind === 'PREPARATION_TASK' || kind === 'MEMORY_CARD'
  if (reason === 'PLACEMENT_DUE' || reason === 'PLACEMENT_PAUSED') return ['KNOWLEDGE_DRAFT', 'TRAINING_SESSION', 'REVIEW_ACTION'].includes(kind)
  if (reason === 'SOURCE_ARCHIVED') return kind === 'KNOWLEDGE_DRAFT' || kind === 'MEMORY_CARD'
  if (reason === 'SOURCE_TERMINAL') return ['TRAINING_SESSION', 'PREPARATION_TASK', 'REVIEW_ACTION', 'INTERVIEW_EVENT'].includes(kind)
  if (reason === 'NO_PENDING_WORK') return kind === 'KNOWLEDGE_DRAFT' || kind === 'REVIEW_ACTION'
  if (reason === 'SOURCE_DATE_REQUIRED' || reason === 'SOURCE_DATE_IN_FUTURE') return kind === 'PREPARATION_TASK'
  if (reason.startsWith('MEMORY_')) return kind === 'MEMORY_CARD'
  if (reason === 'DATE_LOCKED') return kind !== 'MEMORY_CARD' && kind !== 'INTERVIEW_EVENT'
  if (reason === 'ALREADY_STARTED') return ['TRAINING_SESSION', 'PREPARATION_TASK', 'REVIEW_ACTION'].includes(kind)
  if (reason === 'ESTIMATE_REQUIRED') return estimateAllowed(kind)
  return reason !== 'INTERVIEW_REFERENCE_ONLY' || kind === 'INTERVIEW_EVENT'
}
function suggestionItemValid(value: unknown, day: DayRef, index: number): value is SuggestionItem {
  if (!fields(value, ['source', 'sourceStamp', 'placementRevision', 'estimatedMinutes', 'recommended', 'reasonCodes', 'proposedContent'])
    || !todaySourceRefValid(value.source) || !todaySourceStampValid(value.sourceStamp, value.source.kind) || !nullableRevision(value.placementRevision)
    || !(value.estimatedMinutes === null || integer(value.estimatedMinutes, 1, 1440)) || typeof value.recommended !== 'boolean' || !Array.isArray(value.reasonCodes) || value.reasonCodes.length === 0
    || !value.reasonCodes.every((reason, at, all) => oneOf(reason, suggestionReasons) && (at === 0 || suggestionReasons.indexOf(all[at - 1] as SuggestionReason) < suggestionReasons.indexOf(reason)))) return false
  const reasons = value.reasonCodes as SuggestionReason[], proposal = value.proposedContent, kind = value.source.kind
  if (!reasons.every(reason => reasonForSource(reason, kind)) || kind === 'PREPARATION_TASK' && value.estimatedMinutes === null) return false
  if (value.source.kind === 'INTERVIEW_EVENT' && (value.estimatedMinutes !== null || value.recommended || !reasons.includes('INTERVIEW_REFERENCE_ONLY'))) return false
  if (proposal === null) return !value.recommended && !reasons.includes('ESTIMATE_REQUIRED') && !reasons.includes('DAY_BUDGET_REQUIRED') && !reasons.includes('OVER_MINUTES')
    && (reasons.some(reason => disqualifyingReasons.includes(reason)) || reasons.includes('DATE_LOCKED') || reasons.includes('ALREADY_STARTED'))
  if (!placementContentValid(proposal, value.source.kind) || reasons.some(reason => disqualifyingReasons.includes(reason)) || proposal.visibility !== 'VISIBLE'
    || proposal.suppressedOn !== null && sameDay(proposal.suppressedOn, day) || proposal.orderValue !== 1000 * (index + 1)
    || estimateAllowed(value.source.kind) && proposal.estimatedMinutes !== value.estimatedMinutes
    || reasons.includes('ESTIMATE_REQUIRED') !== (value.estimatedMinutes === null) || reasons.includes('DATE_LOCKED') !== proposal.dateLocked) return false
  if (value.placementRevision === null && (proposal.priority !== 3 || proposal.dateLocked || proposal.suppressedOn !== null)) return false
  if (['KNOWLEDGE_DRAFT', 'TRAINING_SESSION', 'REVIEW_ACTION'].includes(value.source.kind) && !proposal.dateLocked && !reasons.includes('ALREADY_STARTED')
    && (proposal.plannedDate !== day.localDate || proposal.planZoneId !== day.zoneId)) return false
  return !value.recommended || value.estimatedMinutes !== null && !reasons.includes('ESTIMATE_REQUIRED') && !reasons.includes('DAY_BUDGET_REQUIRED') && !reasons.includes('OVER_MINUTES')
}
export function suggestionPreviewValid(value: unknown, expected?: SuggestionRequest): value is SuggestionPreview {
  return check(() => {
    if (expected !== undefined && !suggestionRequestValid(expected) || !fields(value, ['context', 'budget', 'items', 'selectedKnownMinutes', 'unknownEstimateCount', 'remaining', 'previewHash', 'limitations'])
      || !todayDayContextValid(value.context) || !Array.isArray(value.items) || value.items.length < 1 || value.items.length > 100 || !integer(value.selectedKnownMinutes, 0, 1440)
      || !integer(value.unknownEstimateCount, 0, value.items.length) || !hashValid(value.previewHash) || !equal(value.limitations, suggestionLimitations) || !bounded(value)) return false
    const day = { localDate: value.context.localDate, zoneId: value.context.zoneId }
    if (value.budget !== null && !dayBudgetValid(value.budget, day)
      || !value.items.every((item, index) => suggestionItemValid(item, day, index))) return false
    const items = value.items as SuggestionItem[], budget = value.budget as DayBudget | null
    if (!uniqueSources(items.map(item => item.source))) return false
    const group = (item: SuggestionItem) => item.reasonCodes.includes('CONTINUE_WORK') ? 0 : item.reasonCodes.includes('SOURCE_DUE') || item.reasonCodes.includes('PLACEMENT_DUE') ? 1 : 2
    if (!items.every((item, index) => index === 0 || group(items[index - 1]!) <= group(item))) return false
    if (expected !== undefined && (!sameDay(day, expected) || items.length !== expected.items.length || (budget?.revision ?? null) !== expected.expectedBudgetRevision
      || !items.every(item => { const candidate = expected.items.find(entry => sameSource(entry.source, item.source)); return candidate !== undefined
        && equal(item.sourceStamp, candidate.expectedSource) && item.placementRevision === candidate.expectedPlacementRevision
        && (candidate.estimatedMinutes === null || item.estimatedMinutes === candidate.estimatedMinutes) }))) return false
    const selected = items.filter(item => item.recommended), remaining = items.filter(item => !item.recommended).map(item => item.source)
    if (!fields(value.remaining, ['sources', 'count']) || !Array.isArray(value.remaining.sources) || !integer(value.remaining.count, 0, 100)
      || value.remaining.count !== remaining.length || !equal(value.remaining.sources, remaining)) return false
    if (value.unknownEstimateCount !== items.filter(item => item.proposedContent !== null && item.estimatedMinutes === null).length
      || value.selectedKnownMinutes !== selected.reduce((total, item) => total + item.estimatedMinutes!, 0)
      || budget === null && selected.length !== 0 || budget !== null && value.selectedKnownMinutes > budget.availableMinutes) return false
    // 按服务器确定的候选顺序复算装入结果；未知估时不能作为零分钟，超额项也不能阻止检查后项。
    let used = 0
    return items.every(item => {
      if (item.proposedContent === null) return true
      const recommended = item.estimatedMinutes !== null && budget !== null && item.estimatedMinutes <= budget.availableMinutes - used
      if (item.recommended !== recommended || item.reasonCodes.includes('DAY_BUDGET_REQUIRED') !== (budget === null)
        || item.reasonCodes.includes('OVER_MINUTES') !== (item.estimatedMinutes !== null && budget !== null && !recommended)) return false
      if (recommended) used += item.estimatedMinutes!
      return true
    })
  })
}
export function applySuggestionValid(value: unknown, preview?: SuggestionPreview): value is ApplySuggestion {
  return check(() => {
    if (!fields(value, ['request', 'expectedPreviewHash', 'selectedSources', 'confirmed']) || !suggestionRequestValid(value.request)
      || !hashValid(value.expectedPreviewHash) || value.confirmed !== true || !Array.isArray(value.selectedSources) || value.selectedSources.length < 1 || value.selectedSources.length > 50
      || !value.selectedSources.every(todaySourceRefValid) || !uniqueSources(value.selectedSources) || !bounded(value, 262144)) return false
    const request = value.request
    if (!value.selectedSources.every(source => request.items.some(candidate => sameSource(candidate.source, source)))) return false
    if (preview === undefined) return true
    if (!suggestionPreviewValid(preview, value.request) || preview.previewHash !== value.expectedPreviewHash) return false
    let position = -1
    return value.selectedSources.every(source => { const next = preview.items.findIndex(item => sameSource(item.source, source));
      if (next <= position || !preview.items[next]?.recommended) return false; position = next; return true })
  })
}
export function createdPlacementValid(value: unknown, expected: CreatePlacement): value is Placement {
  return createPlacementValid(expected) && placementValid(value, expected.source) && value.revision === 0 && equal(contentOf(value), expected.content)
}
export function savedPlacementValid(value: unknown, id: string, source: TodaySourceRef, expected: SavePlacement, previous?: Placement): value is Placement {
  if (!todayIdValid(id) || !savePlacementValid(expected, source) || !placementValid(value, source) || value.id !== id || !equal(contentOf(value), expected.content)) return false
  if (previous === undefined) return value.revision === expected.expectedRevision || value.revision === expected.expectedRevision + 1
  return placementValid(previous, source) && previous.id === id && previous.revision === expected.expectedRevision && value.createdAt === previous.createdAt
    && compareTime(value.updatedAt, previous.updatedAt) >= 0 && (equal(contentOf(previous), expected.content) ? equal(value, previous) : value.revision === previous.revision + 1)
}
export function orderedPlacementsValid(value: unknown, expected?: OrderPlacements, previous?: Placement[]): value is OrderedPlacements {
  return check(() => {
    if (!fields(value, ['placements']) || !Array.isArray(value.placements) || value.placements.length < 1 || value.placements.length > 50
      || !value.placements.every(entry => placementValid(entry)) || !uniqueSources(value.placements.map(entry => entry.source))
      || new Set(value.placements.map(entry => entry.id)).size !== value.placements.length || !bounded(value, 2097152)) return false
    if (expected === undefined) return true
    if (!orderPlacementsValid(expected) || expected.items.length !== value.placements.length) return false
    return value.placements.every((placement: Placement, index: number) => {
      const item = expected.items[index]!
      if (placement.id !== item.placementId || placement.source.kind !== item.expectedSource.kind || placement.orderValue !== item.orderValue
        || placement.revision !== item.expectedRevision && placement.revision !== item.expectedRevision + 1) return false
      if (previous === undefined) return true
      const prior = previous.find(entry => entry.id === placement.id)
      return prior !== undefined && savedPlacementValid(placement, prior.id, prior.source,
        { expectedRevision: item.expectedRevision, expectedSource: item.expectedSource, content: { ...contentOf(prior), orderValue: item.orderValue }, reason: null }, prior)
    })
  })
}
export function createdBudgetValid(value: unknown, expected: CreateBudget): value is DayBudget {
  return createBudgetValid(expected) && dayBudgetValid(value, { localDate: expected.localDate, zoneId: expected.zoneId }) && value.revision === 0 && value.availableMinutes === expected.availableMinutes
}
export function savedBudgetValid(value: unknown, localDate: string, expected: SaveBudget, previous?: DayBudget): value is DayBudget {
  if (!todayDateValid(localDate) || !saveBudgetValid(expected) || !dayBudgetValid(value, { localDate, zoneId: expected.zoneId }) || value.availableMinutes !== expected.availableMinutes) return false
  if (previous === undefined) return value.revision === expected.expectedRevision || value.revision === expected.expectedRevision + 1
  return dayBudgetValid(previous, { localDate, zoneId: expected.zoneId }) && previous.revision === expected.expectedRevision && value.createdAt === previous.createdAt
    && compareTime(value.updatedAt, previous.updatedAt) >= 0 && (previous.availableMinutes === expected.availableMinutes ? equal(value, previous) : value.revision === previous.revision + 1)
}
export function appliedSuggestionValid(value: unknown, expected?: ApplySuggestion, preview?: SuggestionPreview): value is AppliedSuggestion {
  return check(() => {
    if (!fields(value, ['placements', 'context']) || !todayDayContextValid(value.context)) return false
    const context = value.context
    if (!Array.isArray(value.placements) || value.placements.length < 1 || value.placements.length > 50
      || !value.placements.every(entry => placementValid(entry) && compareTime(entry.updatedAt, context.serverTime) <= 0)
      || !uniqueSources(value.placements.map(entry => entry.source)) || new Set(value.placements.map(entry => entry.id)).size !== value.placements.length || !bounded(value, 2097152)) return false
    if (expected === undefined) return preview === undefined
    if (!applySuggestionValid(expected, preview) || !sameDay(value.context, expected.request) || value.placements.length !== expected.selectedSources.length) return false
    return value.placements.every((placement: Placement, index: number) => {
      if (!sameSource(placement.source, expected.selectedSources[index]!)) return false
      const candidate = expected.request.items.find(item => sameSource(item.source, placement.source))!
      if (candidate.expectedPlacementRevision === null ? placement.revision !== 0 : placement.revision !== candidate.expectedPlacementRevision && placement.revision !== candidate.expectedPlacementRevision + 1) return false
      return preview === undefined || equal(contentOf(placement), preview.items.find(item => sameSource(item.source, placement.source))!.proposedContent)
    })
  })
}

function queryValid(value: unknown, required: readonly string[], optional: readonly string[]): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key))
    && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
}
function historyQueryValid(value: Record<string, unknown>): boolean { return (value.before == null || integer(value.before)) && (value.limit === undefined || integer(value.limit, 1, 50)) }
function invalidQuery(): never { throw new ApiError(400, 'INVALID_REQUEST', '安排查询参数不符合约定，请核对对象标识、时区、日期与分页位置。') }
function queryPath(path: string, value: Record<string, unknown>): string {
  const query = new URLSearchParams()
  for (const [key, item] of Object.entries(value)) if (item !== undefined && item !== null) query.set(key, String(item))
  return query.size === 0 ? path : `${path}?${query}`
}
export function placementLookupPath(source: TodaySourceRef): string {
  if (!todaySourceRefValid(source)) invalidQuery()
  return queryPath('/today/placements/by-source', { kind: source.kind, sourceId: source.id })
}
export function placementsPath(query: PlacementDirectoryQuery): string {
  if (!queryValid(query, ['zoneId'], ['kind', 'visibility', 'cursor', 'limit']) || !todayZoneValid(query.zoneId) || query.kind != null && !oneOf(query.kind, todaySourceKinds)
    || query.visibility != null && !oneOf(query.visibility, ['VISIBLE', 'PAUSED']) || query.cursor !== undefined && !cursorValid(query.cursor) || query.limit !== undefined && !integer(query.limit, 1, 50)) invalidQuery()
  return queryPath('/today/placements', query)
}
export function placementPath(id: string): string { if (!todayIdValid(id)) invalidQuery(); return `/today/placements/${id}` }
export function placementHistoryPath(id: string, query: HistoryQuery = {}): string {
  if (!todayIdValid(id) || !queryValid(query, [], ['before', 'limit']) || !historyQueryValid(query)) invalidQuery()
  return queryPath(`${placementPath(id)}/history`, query)
}
export function budgetLookupPath(query: DayRef): string {
  if (!dayValid(query)) invalidQuery()
  return queryPath('/today/budgets', query)
}
export function budgetPath(localDate: string): string { if (!todayDateValid(localDate)) invalidQuery(); return `/today/budgets/${localDate}` }
export function budgetHistoryPath(localDate: string, query: BudgetHistoryQuery): string {
  if (!todayDateValid(localDate) || !queryValid(query, ['zoneId'], ['before', 'limit']) || !todayZoneValid(query.zoneId) || !historyQueryValid(query)) invalidQuery()
  return queryPath(`${budgetPath(localDate)}/history`, query)
}
export const todayPlanCommandPaths = { createPlacement: '/today/placements', order: '/today/order', createBudget: '/today/budgets', preview: '/today/suggestions/preview', apply: '/today/suggestions/apply' } as const
export const todayPlanErrorText = todayErrorText
