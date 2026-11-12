import { ApiError } from './client'
import type { Schema } from './client'
import { memoryIdValid, memoryKindLabels, memoryPromptValid, memoryReferenceValid } from './memory'
import type { MemoryReference, MemoryVersion } from './memory'
import { memoryBlockReasonLabels, memoryReviewErrorText, memoryReviewZoneValid, memoryScheduleValid, memorySourceSnapshotValid, memorySourceSnapshotsValid,
  memorySourcesReviewable, memoryUtcMicros } from './memoryReview'
import type { MemorySchedule } from './memoryReview'

export type MemoryAttemptMode = Schema['MemoryMode']
export type MemoryAttemptState = Schema['MemoryState']
export type MemoryResponseMode = Schema['MemoryResponseMode']
export type MemoryRating = Schema['MemoryRating']
export type MemoryAttemptEventKind = Schema['MemoryEventKind']
export type MemoryBudgetKind = Schema['MemoryBudgetKind']
export type MemoryInvalidationReason = Schema['MemoryInvalidationReason']
export type MemoryStartAttempt = Schema['MemoryStartAttempt']
export type MemorySaveResponse = Schema['MemorySaveResponse']
export type MemoryReveal = Schema['MemoryReveal']
export type MemoryRate = Schema['MemoryRate']
export type MemorySkip = Schema['MemorySkip']
export type MemoryCreateCramBatch = Schema['MemoryCreateCramBatch']
export type MemoryDailyBudget = Schema['MemoryDailyBudget']
export type MemoryBudgetAdmission = Schema['MemoryBudgetAdmission']
export type MemoryStartSnapshot = Schema['MemoryStartSnapshot']
export type MemoryInvalidation = Schema['MemoryInvalidation']
export type MemoryAttempt = Schema['MemoryAttempt']
export type MemoryAttemptDetail = Schema['MemoryAttemptDetail']
export type MemoryRevealedAttempt = Schema['MemoryRevealedAttempt']
export type MemoryRatedAttempt = Schema['MemoryRatedAttempt']
export type MemoryAttemptSummary = Schema['MemoryAttemptSummary']
export type MemoryActiveAttempt = Schema['MemoryActiveAttempt']
export type MemoryAttemptPage = Schema['MemoryAttemptPage']
export type MemoryOpenAttemptPage = Schema['MemoryOpenAttemptPage']
export type MemoryStarted = Schema['MemoryStarted']
export type MemoryRevealed = Schema['MemoryRevealed']
export type MemoryRatingDay = Schema['MemoryRatingDay']
export type MemoryBudgetUse = Schema['MemoryBudgetUse']
export type MemoryScheduledRating = Schema['MemoryScheduledRating']
export type MemoryRated = Schema['MemoryRated']
export type MemorySkipped = Schema['MemorySkipped']
export type MemoryInvalidated = Schema['MemoryInvalidated']
export type MemoryAttemptEvent = Schema['MemoryAttemptEvent']
export type MemoryAttemptEventPage = Schema['MemoryEventPage']
export type MemoryQueueItem = Schema['MemoryQueueItem']
export type MemoryReviewQueue = Schema['MemoryReviewQueue']
export type MemoryCramSelection = Schema['MemoryCramSelection']
export type MemoryCramBatch = Schema['MemoryCramBatch']
export type MemoryCramSummary = Schema['MemoryCramSummary']
export type MemoryCramBatchPage = Schema['MemoryCramBatchPage']
export type MemoryCramSchedule = Schema['MemoryCramSchedule']
export type MemoryCramMember = Schema['MemoryCramMember']
export type MemoryCramMemberPage = Schema['MemoryCramMemberPage']

export const memoryAttemptModeLabels: Record<MemoryAttemptMode, string> = { REGULAR: '常规回忆', CRAM: '冲刺练习' }
export const memoryAttemptStateLabels: Record<MemoryAttemptState, string> = { THINKING: '独立回忆中', REVEALED: '已揭示，待自评', RATED: '已自评', SKIPPED: '已跳过', INVALIDATED: '依据已变化' }
export const memoryResponseModeLabels: Record<MemoryResponseMode, string> = { THOUGHT: '在心中回忆', WRITTEN: '写下回答' }
export const memoryRatingLabels: Record<MemoryRating, string> = { AGAIN: '忘记', HARD: '困难', GOOD: '记得', EASY: '熟悉' }
export const memoryBudgetKindLabels: Record<MemoryBudgetKind, string> = { NEW: '新卡', REVIEW: '复习卡' }
export const memoryAttemptEventKindLabels: Record<MemoryAttemptEventKind, string> = { START: '开始回忆', REVEAL: '揭示参考', RATE: '明确自评', SKIP: '跳过本次', INVALIDATE: '本次失效' }
export const memoryInvalidationReasonLabels: Record<MemoryInvalidationReason, string> = {
  CARD_VERSION_CHANGED: '卡片产生新版本', CARD_ARCHIVED: '卡片已归档', CARD_RESTORED: '卡片已恢复', SOURCE_BODY_CHANGED: '来源正文变化',
  SOURCE_ARCHIVED: '来源已归档', SOURCE_RESTORED: '来源已恢复', SOURCE_REVIEWED: '来源已经重新复核', SCHEDULE_DEFERRED: '排程已延期',
  SOURCE_UPSTREAM_CHANGED: '上游采用来源待复核',
  SCHEDULE_PAUSED: '排程已暂停', SCHEDULE_RESUMED: '排程已恢复', SCHEDULE_RESET: '排程重新开始',
}
export const memoryAttemptErrorLabels: Readonly<Record<string, string>> = {
  MEMORY_ATTEMPT_NOT_FOUND: '这次回忆不存在或不可访问。', MEMORY_CRAM_BATCH_NOT_FOUND: '冲刺批次不存在或不可访问。',
  MEMORY_ATTEMPT_OPEN: '这张卡片已有未结束的回忆，请先恢复原尝试。', MEMORY_ATTEMPT_CONFLICT: '本次回忆已变化，请保留输入并对照服务器记录。',
  MEMORY_ATTEMPT_CLOSED: '本次回忆已经结束，请核对历史记录。', MEMORY_NOT_REVEALED: '尚未明确揭示参考，请先完成独立回忆。',
  MEMORY_ALREADY_REVEALED: '参考已经揭示，请恢复这次回忆后继续自评。', MEMORY_RESPONSE_FROZEN: '参考已经揭示，原回答已固定。',
  MEMORY_ATTEMPT_STATE_UNAVAILABLE: '回忆占位状态异常，请保留当前输入并核对记录。', MEMORY_NOT_DUE: '这张卡片尚未真正到期。',
  MEMORY_BUDGET_CONFIRMATION_REQUIRED: '已达到每日建议数量；继续前需要你明确确认。', MEMORY_CRAM_SCOPE_CONFLICT: '这张卡片不在本冲刺批次的固定范围内。',
  MEMORY_QUEUE_CHANGED: '复习日、设置或筛选已变化，请从队列首页重新读取。', MEMORY_CLOCK_ORDER: '服务器时间早于已有记录，本次操作未提交。',
  MEMORY_SCHEDULER_INVALID: '复习算法状态或结果异常，本次评分未提交，已揭示回答继续保留。',
}
export function memoryAttemptErrorText(error: unknown): string {
  if (!(error instanceof ApiError) || !Object.hasOwn(memoryAttemptErrorLabels, error.code)) return memoryReviewErrorText(error)
  return memoryAttemptErrorLabels[error.code]! + (error.code === 'MEMORY_ATTEMPT_CONFLICT' && integer(error.currentRevision, 0)
    ? ` 当前尝试修订为 ${error.currentRevision}。` : '')
}

const MAX = Number.MAX_SAFE_INTEGER
const fields = (value: unknown, names: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown, min: number, max = MAX): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
const timestamp = (value: unknown): value is string => memoryUtcMicros(value) !== undefined
const sameTime = (left: unknown, right: unknown) => timestamp(left) && memoryUtcMicros(left) === memoryUtcMicros(right)
const timeOrder = (left: unknown, right: unknown) => timestamp(left) && timestamp(right) && memoryUtcMicros(left)! <= memoryUtcMicros(right)!
const nullableTime = (value: unknown): value is string | null => value === null || timestamp(value)
const bytes = (value: unknown) => new TextEncoder().encode(JSON.stringify(value)).length
const check = (rule: () => boolean): boolean => { try { return rule() } catch { return false } }
const text = (value: unknown, maximum: number): value is string => typeof value === 'string' && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value)
  && Array.from(value).length <= maximum && !/[\u0000\uD800-\uDFFF]/u.test(value)
const enumValue = <T extends string>(value: unknown, labels: Readonly<Record<T, string>>): value is T => typeof value === 'string' && Object.hasOwn(labels, value)
const nullableId = (value: unknown): value is string | null => value === null || memoryIdValid(value)
const cursorValid = (value: unknown): value is string | null => value === null || (typeof value === 'string' && /^[\x01-\x7f]{1,2048}$/.test(value))
const open = (state: unknown) => state === 'THINKING' || state === 'REVEALED'
const responseValid = (mode: unknown, response: unknown) => mode === 'THOUGHT' ? response === '' : mode === 'WRITTEN' && text(response, 10000)
const elapsed = (start: string, end: string): number => Number((memoryUtcMicros(end)! - memoryUtcMicros(start)!) / 1000n)
const ownerValid = (value: unknown) => fields(value, ['kind', 'ownerId']) && value.kind === 'OWNER' && memoryIdValid(value.ownerId)
function sameJson(left: unknown, right: unknown): boolean {
  if (left === right) return true
  if (Array.isArray(left)) return Array.isArray(right) && left.length === right.length && left.every((item, index) => sameJson(item, right[index]))
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object' || Array.isArray(right)) return false
  if (memorySourceSnapshotValid(left) && memorySourceSnapshotValid(right)) {
    // 旧快照缺字段或 null 与新快照 false 仅在比较时等价，不能修改已保存的事件或原键回执。
    return (left.upstreamReviewRequired === true) === (right.upstreamReviewRequired === true)
      && left.itemId === right.itemId && left.versionId === right.versionId && left.kind === right.kind
      && left.versionNumber === right.versionNumber && left.state === right.state
      && left.currentVersionId === right.currentVersionId && left.bodyChanged === right.bodyChanged
  }
  return Object.keys(left).length === Object.keys(right).length && Object.entries(left).every(([key, item]) => Object.hasOwn(right, key) && sameJson(item, (right as Record<string, unknown>)[key]))
}

export function memoryLocalDateValid(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || Number(value.slice(0, 4)) < 1000) return false
  const milliseconds = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(milliseconds) && new Date(milliseconds).toISOString().slice(0, 10) === value
}
function dateRangeValid(start: unknown, end: unknown): boolean {
  return memoryLocalDateValid(start) && memoryLocalDateValid(end) && end >= start
    && (Date.parse(`${end}T00:00:00Z`) - Date.parse(`${start}T00:00:00Z`)) / 86400000 < 90
}
function dayWindowValid(value: Record<string, unknown>, occurredAt?: string): boolean {
  if (!memoryReviewZoneValid(value.zoneId) || !memoryLocalDateValid(value.localDate) || !timestamp(value.windowStart) || !timestamp(value.windowEnd)) return false
  const start = memoryUtcMicros(value.windowStart)!, end = memoryUtcMicros(value.windowEnd)!
  // 日窗口随服务器事实保存；只核对范围与包含关系，不用浏览器当前时区数据重算历史标签。
  if (start >= end) return false
  return occurredAt === undefined || (timestamp(occurredAt) && start <= memoryUtcMicros(occurredAt)! && memoryUtcMicros(occurredAt)! < end)
}
export function memoryDailyBudgetValid(value: unknown, asOf?: string): value is MemoryDailyBudget {
  return check(() => fields(value, ['settingsRevision', 'zoneId', 'localDate', 'windowStart', 'windowEnd', 'newLimit', 'reviewLimit', 'newCards', 'reviewCards', 'ratedEvents'])
    && integer(value.settingsRevision, 0) && dayWindowValid(value, asOf) && integer(value.newLimit, 0, 1000) && integer(value.reviewLimit, 0, 1000)
    && integer(value.newCards, 0) && integer(value.reviewCards, 0) && integer(value.ratedEvents, 0)
    && value.newCards <= value.ratedEvents && value.reviewCards <= value.ratedEvents - value.newCards)
}
function modeValid(mode: unknown, countRegular: unknown, cramBatchId: unknown): boolean {
  return typeof countRegular === 'boolean' && (mode === 'REGULAR' ? countRegular && cramBatchId === null : mode === 'CRAM' && memoryIdValid(cramBatchId))
}
function admissionValid(value: unknown, schedule: MemorySchedule): value is MemoryBudgetAdmission {
  if (!fields(value, ['budget', 'kind', 'countedToday', 'overBudget', 'continueBeyondBudget']) || !memoryDailyBudgetValid(value.budget)
    || !enumValue(value.kind, memoryBudgetKindLabels) || typeof value.countedToday !== 'boolean' || typeof value.overBudget !== 'boolean' || typeof value.continueBeyondBudget !== 'boolean') return false
  const used = value.kind === 'NEW' ? value.budget.newCards : value.budget.reviewCards, limit = value.kind === 'NEW' ? value.budget.newLimit : value.budget.reviewLimit
  if (value.countedToday) return used > 0 && !value.overBudget
  return value.kind === (schedule.state.lastReviewAt === null ? 'NEW' : 'REVIEW') && value.overBudget === (used >= limit) && (!value.overBudget || value.continueBeyondBudget)
}
export function memoryStartSnapshotValid(value: unknown, cardId?: string): value is MemoryStartSnapshot {
  return check(() => {
    if (!fields(value, ['cardVersionId', 'cardVersionNumber', 'cardRevision', 'gateRevision', 'mode', 'countRegular', 'cramBatchId', 'zoneId', 'schedule', 'sources', 'admission'])
      || !memoryIdValid(value.cardVersionId) || !integer(value.cardVersionNumber, 1, 1000) || !integer(value.cardRevision, 0, 1000)
      || value.cardVersionNumber > value.cardRevision + 1 || !integer(value.gateRevision, 0) || !modeValid(value.mode, value.countRegular, value.cramBatchId)
      || !memoryReviewZoneValid(value.zoneId) || !memorySourceSnapshotsValid(value.sources) || !memorySourcesReviewable(value.sources) || bytes(value) > 32768) return false
    if (value.countRegular === false) return value.schedule === null && value.admission === null
    return memoryScheduleValid(value.schedule, cardId) && value.schedule.status === 'ACTIVE' && admissionValid(value.admission, value.schedule) && value.zoneId === value.admission.budget.zoneId
  })
}
function invalidationValid(value: unknown): value is MemoryInvalidation {
  if (!fields(value, ['reason', 'currentCardVersionId', 'currentCardRevision', 'currentGateRevision', 'currentScheduleRevision', 'sourceHistoryKind', 'sourceHistoryId', 'scheduleChangeId'])
    || !enumValue(value.reason, memoryInvalidationReasonLabels) || !memoryIdValid(value.currentCardVersionId) || !integer(value.currentCardRevision, 0, 1000)
    || !integer(value.currentGateRevision, 0) || (value.currentScheduleRevision !== null && !integer(value.currentScheduleRevision, 0)) || !nullableId(value.scheduleChangeId)) return false
  if (value.sourceHistoryKind === null ? value.sourceHistoryId !== null : (value.sourceHistoryKind !== 'IMPACT' && value.sourceHistoryKind !== 'SOURCE_REVIEW') || !memoryIdValid(value.sourceHistoryId)) return false
  if (value.scheduleChangeId !== null && value.currentScheduleRevision === null) return false
  if (value.reason.startsWith('SCHEDULE_')) return memoryIdValid(value.scheduleChangeId) && value.currentScheduleRevision !== null && value.sourceHistoryKind === null
  if (value.reason === 'SOURCE_REVIEWED') return value.sourceHistoryKind === 'SOURCE_REVIEW' && value.scheduleChangeId === null
  if (value.reason === 'CARD_VERSION_CHANGED') return value.sourceHistoryKind === 'IMPACT' || (value.sourceHistoryKind === null && value.scheduleChangeId === null)
  return value.sourceHistoryKind === 'IMPACT' && (!['CARD_RESTORED', 'SOURCE_RESTORED'].includes(value.reason) || value.scheduleChangeId === null)
}
function summaryStateValid(value: Record<string, unknown>): boolean {
  if (!enumValue(value.state, memoryAttemptStateLabels) || !timestamp(value.startedAt) || !timestamp(value.updatedAt) || !timeOrder(value.startedAt, value.updatedAt)
    || !nullableTime(value.revealedAt) || !nullableTime(value.ratedAt) || !nullableTime(value.closedAt) || (value.rating !== null && !enumValue(value.rating, memoryRatingLabels))) return false
  if (value.revealedAt !== null && (!timeOrder(value.startedAt, value.revealedAt) || !timeOrder(value.revealedAt, value.updatedAt))) return false
  if (value.state === 'THINKING') return value.revealedAt === null && value.ratedAt === null && value.closedAt === null && value.rating === null
  if (value.state === 'REVEALED') return value.revealedAt !== null && sameTime(value.updatedAt, value.revealedAt) && value.ratedAt === null && value.closedAt === null && value.rating === null
  if (!sameTime(value.closedAt, value.updatedAt)) return false
  return value.state === 'RATED' ? value.revealedAt !== null && sameTime(value.ratedAt, value.closedAt) && value.rating !== null
    : value.ratedAt === null && value.rating === null
}
export function memoryAttemptValid(value: unknown, attemptId?: string, cardId?: string): value is MemoryAttempt {
  return check(() => {
    if (!fields(value, ['id', 'cardId', 'sequence', 'start', 'revision', 'state', 'responseMode', 'response', 'responseSavedAt', 'startedAt', 'updatedAt', 'revealedAt', 'ratedAt', 'closedAt', 'thinkingElapsedMs', 'totalElapsedMs', 'rating', 'skipReason', 'invalidation', 'lastEventSequence'])
      || !memoryIdValid(value.id) || (attemptId !== undefined && value.id !== attemptId) || !memoryIdValid(value.cardId) || (cardId !== undefined && value.cardId !== cardId)
      || !integer(value.sequence, 1) || !integer(value.revision, 0) || !memoryStartSnapshotValid(value.start, value.cardId) || !summaryStateValid(value)
      || !responseValid(value.responseMode, value.response) || !nullableTime(value.responseSavedAt) || !integer(value.lastEventSequence, 1, 3)
      || value.revision < value.lastEventSequence - 1 || bytes(value) > 131072) return false
    const start = value.start
    if (start.countRegular && (!memoryDailyBudgetValid(start.admission!.budget, value.startedAt as string) || !timeOrder(start.schedule!.effectiveDueAt, value.startedAt))) return false
    if (value.responseSavedAt === null ? value.responseMode !== 'THOUGHT' || value.response !== '' || value.revision !== (value.state === 'THINKING' ? 0 : 1)
      : !timeOrder(value.startedAt, value.responseSavedAt) || !timeOrder(value.responseSavedAt, value.updatedAt)) return false
    if (value.revealedAt === null ? value.thinkingElapsedMs !== null : !integer(value.thinkingElapsedMs, 0) || value.thinkingElapsedMs !== elapsed(value.startedAt as string, value.revealedAt as string) || !sameTime(value.responseSavedAt, value.revealedAt)) return false
    if (value.state === 'THINKING') return value.lastEventSequence === 1 && value.totalElapsedMs === null && value.skipReason === null && value.invalidation === null
      && (value.revision !== 0 || (value.responseSavedAt === null && sameTime(value.startedAt, value.updatedAt)))
      && (value.responseSavedAt === null ? value.revision === 0 : sameTime(value.responseSavedAt, value.updatedAt))
    if (value.state === 'REVEALED') return value.lastEventSequence === 2 && value.totalElapsedMs === null && value.skipReason === null && value.invalidation === null
    if (value.lastEventSequence !== (value.revealedAt === null ? 2 : 3)) return false
    if (value.state === 'RATED') return integer(value.totalElapsedMs, 0) && value.totalElapsedMs === elapsed(value.startedAt as string, value.ratedAt as string)
      && value.skipReason === null && value.invalidation === null
    if (value.totalElapsedMs !== null) return false
    if (value.state === 'SKIPPED') return text(value.skipReason, 1000) && value.invalidation === null
    return value.skipReason === null && invalidationValid(value.invalidation) && (start.countRegular || !value.invalidation.reason.startsWith('SCHEDULE_'))
      && value.invalidation.currentCardRevision >= start.cardRevision && value.invalidation.currentGateRevision >= start.gateRevision
      && (value.invalidation.reason !== 'CARD_VERSION_CHANGED' || value.invalidation.currentCardVersionId !== start.cardVersionId)
  })
}
function versionValid(value: unknown): value is MemoryVersion {
  return fields(value, ['id', 'number', 'content', 'createdAt']) && memoryIdValid(value.id) && integer(value.number, 1, 1000)
    && memoryPromptValid(value.content as MemoryVersion['content']) && timestamp(value.createdAt)
}
export function memoryAttemptDetailValid(value: unknown, attemptId?: string, cardId?: string): value is MemoryAttemptDetail {
  return check(() => fields(value, ['attempt', 'version']) && memoryAttemptValid(value.attempt, attemptId, cardId) && versionValid(value.version)
    && value.version.id === value.attempt.start.cardVersionId && value.version.number === value.attempt.start.cardVersionNumber
    && sameJson(value.version.content.sources, value.attempt.start.sources.map(source => ({ itemId: source.itemId, versionId: source.versionId }))) && bytes(value) <= 393216)
}
export function memoryAttemptReferenceValid(value: unknown, attempt: MemoryAttempt): value is MemoryReference {
  return check(() => memoryAttemptValid(attempt) && attempt.revealedAt !== null && memoryReferenceValid(value as MemoryReference, attempt.cardId, attempt.start.cardVersionId, attempt.start.cardVersionNumber))
}
export function memoryRevealedAttemptValid(value: unknown, attemptId?: string, cardId?: string): value is MemoryRevealedAttempt {
  return check(() => fields(value, ['detail', 'reference']) && memoryAttemptDetailValid(value.detail, attemptId, cardId)
    && value.detail.attempt.state === 'REVEALED' && memoryAttemptReferenceValid(value.reference, value.detail.attempt) && bytes(value) <= 524288)
}
export function memoryAttemptSummaryValid(value: unknown, attemptId?: string, cardId?: string): value is MemoryAttemptSummary {
  return check(() => fields(value, ['id', 'cardId', 'sequence', 'cardVersionId', 'cardVersionNumber', 'kind', 'title', 'mode', 'countRegular', 'cramBatchId', 'state', 'revision', 'startedAt', 'updatedAt', 'revealedAt', 'ratedAt', 'closedAt', 'rating', 'invalidationReason'])
    && memoryIdValid(value.id) && (attemptId === undefined || value.id === attemptId) && memoryIdValid(value.cardId) && (cardId === undefined || value.cardId === cardId)
    && integer(value.sequence, 1) && memoryIdValid(value.cardVersionId) && integer(value.cardVersionNumber, 1, 1000) && enumValue(value.kind, memoryKindLabels) && text(value.title, 200)
    && modeValid(value.mode, value.countRegular, value.cramBatchId) && integer(value.revision, 0) && summaryStateValid(value)
    && value.revision >= (value.state === 'THINKING' ? 0 : value.state === 'REVEALED' || value.revealedAt === null ? 1 : 2)
    && (value.state === 'INVALIDATED' ? enumValue(value.invalidationReason, memoryInvalidationReasonLabels)
      && (value.countRegular === true || !value.invalidationReason.startsWith('SCHEDULE_')) : value.invalidationReason === null))
}
export function memoryActiveAttemptValid(value: unknown, cardId: string): value is MemoryActiveAttempt {
  return check(() => memoryIdValid(cardId) && fields(value, ['cardId', 'attempt', 'asOf']) && value.cardId === cardId && timestamp(value.asOf)
    && (value.attempt === null || (memoryAttemptSummaryValid(value.attempt, undefined, cardId) && open(value.attempt.state))) && bytes(value) <= 1048576)
}

function ratedPayloadValid(value: unknown, eventId: string, occurredAt: string): value is MemoryRated {
  if (!fields(value, ['rating', 'thinkingElapsedMs', 'totalElapsedMs', 'day', 'budget', 'schedule']) || !enumValue(value.rating, memoryRatingLabels)
    || !integer(value.thinkingElapsedMs, 0) || !integer(value.totalElapsedMs, 0) || value.totalElapsedMs < value.thinkingElapsedMs
    || !fields(value.day, ['zoneId', 'localDate', 'windowStart', 'windowEnd', 'settingsRevision']) || !dayWindowValid(value.day, occurredAt)) return false
  if (value.budget === null || value.schedule === null) return value.budget === null && value.schedule === null && value.day.settingsRevision === null
  if (!integer(value.day.settingsRevision, 0) || !fields(value.budget, ['kind', 'firstForCardToday', 'firstCountedEventId'])
    || !enumValue(value.budget.kind, memoryBudgetKindLabels) || typeof value.budget.firstForCardToday !== 'boolean' || !memoryIdValid(value.budget.firstCountedEventId)
    || value.budget.firstForCardToday !== (value.budget.firstCountedEventId === eventId)
    || !fields(value.schedule, ['changeId', 'sequence', 'before', 'after']) || !memoryIdValid(value.schedule.changeId) || !integer(value.schedule.sequence, 2)
    || !memoryScheduleValid(value.schedule.before) || !memoryScheduleValid(value.schedule.after, value.schedule.before.cardId)) return false
  const before = value.schedule.before, after = value.schedule.after
  if (before.status !== 'ACTIVE' || after.status !== 'ACTIVE' || after.revision - before.revision !== 1
    || after.lastHistorySequence - before.lastHistorySequence !== 1 || after.lastHistorySequence !== value.schedule.sequence
    || before.cycleId !== after.cycleId || !sameTime(before.createdAt, after.createdAt) || before.algorithmVersion !== after.algorithmVersion
    || before.parameterVersion !== after.parameterVersion || after.deferUntil !== null || after.lastRatedEventId !== eventId || before.lastRatedEventId === eventId
    || !timeOrder(before.effectiveDueAt, occurredAt) || !sameTime(after.updatedAt, occurredAt) || !sameTime(after.state.lastReviewAt, occurredAt)
    || !sameTime(after.suggestedDueAt, after.effectiveDueAt)) return false
  if (after.state.phase === 'REVIEW') {
    const interval = memoryUtcMicros(after.state.dueAt)! - memoryUtcMicros(occurredAt)!, day = 86400000000n
    if (interval < day || interval > 36500n * day || interval % day !== 0n) return false
  }
  return !value.budget.firstForCardToday || value.budget.kind === (before.state.lastReviewAt === null ? 'NEW' : 'REVIEW')
}
export function memoryAttemptEventValid(value: unknown, attemptId?: string, attempt?: MemoryAttempt): value is MemoryAttemptEvent {
  return check(() => {
    if (!fields(value, ['id', 'attemptId', 'sequence', 'attemptRevision', 'kind', 'fromState', 'toState', 'actor', 'occurredAt', 'started', 'revealed', 'rated', 'skipped', 'invalidated'])
      || !memoryIdValid(value.id) || !memoryIdValid(value.attemptId) || (attemptId !== undefined && value.attemptId !== attemptId)
      || !integer(value.sequence, 1, 3) || !integer(value.attemptRevision, 0) || value.attemptRevision < value.sequence - 1
      || !enumValue(value.kind, memoryAttemptEventKindLabels) || !ownerValid(value.actor) || !timestamp(value.occurredAt) || bytes(value) > 131072) return false
    const payload = { START: 'started', REVEAL: 'revealed', RATE: 'rated', SKIP: 'skipped', INVALIDATE: 'invalidated' }[value.kind]
    if (!['started', 'revealed', 'rated', 'skipped', 'invalidated'].every(key => key === payload ? value[key] !== null : value[key] === null)) return false
    if (value.kind === 'START') {
      if (value.fromState !== null || value.toState !== 'THINKING' || value.sequence !== 1 || value.attemptRevision !== 0
        || !fields(value.started, ['snapshot']) || !memoryStartSnapshotValid(value.started.snapshot, attempt?.cardId)) return false
      const snapshot = value.started.snapshot
      if (snapshot.countRegular && (!memoryDailyBudgetValid(snapshot.admission!.budget, value.occurredAt) || !timeOrder(snapshot.schedule!.effectiveDueAt, value.occurredAt))) return false
    } else if (value.kind === 'REVEAL') {
      if (value.fromState !== 'THINKING' || value.toState !== 'REVEALED' || value.sequence !== 2
        || !fields(value.revealed, ['responseMode', 'response', 'thinkingElapsedMs']) || !responseValid(value.revealed.responseMode, value.revealed.response)
        || !integer(value.revealed.thinkingElapsedMs, 0)) return false
    } else if (value.kind === 'RATE') {
      if (value.fromState !== 'REVEALED' || value.toState !== 'RATED' || value.sequence !== 3 || !ratedPayloadValid(value.rated, value.id, value.occurredAt)) return false
    } else {
      if (!open(value.fromState) || value.sequence !== (value.fromState === 'THINKING' ? 2 : 3)) return false
      if (value.kind === 'SKIP') {
        if (value.toState !== 'SKIPPED' || !fields(value.skipped, ['reason']) || !text(value.skipped.reason, 1000)) return false
      } else if (value.toState !== 'INVALIDATED' || !fields(value.invalidated, ['cause']) || !invalidationValid(value.invalidated.cause)) return false
    }
    if (attempt === undefined) return true
    if (!memoryAttemptValid(attempt, value.attemptId) || value.sequence > attempt.lastEventSequence || value.attemptRevision > attempt.revision
      || !timeOrder(attempt.startedAt, value.occurredAt) || !timeOrder(value.occurredAt, attempt.updatedAt)) return false
    if (value.kind === 'START') return sameJson((value.started as MemoryStarted).snapshot, attempt.start) && sameTime(value.occurredAt, attempt.startedAt)
    if (value.kind === 'REVEAL') {
      const revealed = value.revealed as MemoryRevealed
      return sameTime(value.occurredAt, attempt.revealedAt) && revealed.responseMode === attempt.responseMode && revealed.response === attempt.response
        && revealed.thinkingElapsedMs === attempt.thinkingElapsedMs && (attempt.state !== 'REVEALED' || value.attemptRevision === attempt.revision)
    }
    if (value.toState !== attempt.state || value.attemptRevision !== attempt.revision || !sameTime(value.occurredAt, attempt.closedAt)) return false
    if (value.kind === 'SKIP') return (value.skipped as MemorySkipped).reason === attempt.skipReason
    if (value.kind === 'INVALIDATE') return sameJson((value.invalidated as MemoryInvalidated).cause, attempt.invalidation)
    const rated = value.rated as MemoryRated
    if (rated.rating !== attempt.rating || rated.thinkingElapsedMs !== attempt.thinkingElapsedMs || rated.totalElapsedMs !== attempt.totalElapsedMs) return false
    return attempt.start.countRegular ? rated.schedule !== null && rated.budget !== null && sameJson(rated.schedule.before, attempt.start.schedule)
      && rated.schedule.after.cardId === attempt.cardId : rated.schedule === null && rated.budget === null && rated.day.zoneId === attempt.start.zoneId
  })
}
export function memoryRatedAttemptValid(value: unknown, attemptId?: string, cardId?: string): value is MemoryRatedAttempt {
  return check(() => fields(value, ['attempt', 'event']) && memoryAttemptValid(value.attempt, attemptId, cardId) && value.attempt.state === 'RATED'
    && memoryAttemptEventValid(value.event, value.attempt.id, value.attempt) && value.event.kind === 'RATE' && bytes(value) <= 393216)
}
function sequencePage(value: unknown, validate: (item: unknown) => item is { sequence: number; id: string }, before: number | undefined, limit: number, maximum: number): boolean {
  if (!fields(value, ['items', 'nextBefore']) || !Array.isArray(value.items) || !integer(limit, 1, 50) || value.items.length > limit
    || (before !== undefined && !integer(before, 1)) || bytes(value) > maximum || !value.items.every(validate)) return false
  const items = value.items as { sequence: number; id: string }[]
  return new Set(items.map(item => item.id)).size === items.length && items.every((item, index) => (before === undefined || item.sequence < before)
    && (index === 0 || item.sequence < items[index - 1]!.sequence))
    && (value.nextBefore === null || (integer(value.nextBefore, 1) && items.length === limit && value.nextBefore === items.at(-1)?.sequence))
}
export function memoryAttemptPageValid(value: unknown, cardId: string, before?: number, limit = 20): value is MemoryAttemptPage {
  return check(() => memoryIdValid(cardId) && sequencePage(value, (item): item is MemoryAttemptSummary => memoryAttemptSummaryValid(item, undefined, cardId), before, limit, 1048576))
}
export function memoryAttemptEventPageValid(value: unknown, attemptId: string, before?: number, limit = 20, attempt?: MemoryAttempt): value is MemoryAttemptEventPage {
  return check(() => {
    if (!memoryIdValid(attemptId) || !sequencePage(value, (item): item is MemoryAttemptEvent => memoryAttemptEventValid(item, attemptId, attempt), before, limit, 524288)) return false
    const page = value as MemoryAttemptEventPage
    if (page.items.length > 3 || !page.items.every((event, index) => index === 0 || (event.sequence === page.items[index - 1]!.sequence - 1
      && event.attemptRevision < page.items[index - 1]!.attemptRevision && timeOrder(event.occurredAt, page.items[index - 1]!.occurredAt)))) return false
    const startEvent = page.items.find(event => event.kind === 'START'), revealEvent = page.items.find(event => event.kind === 'REVEAL'), rateEvent = page.items.find(event => event.kind === 'RATE')
    const started = startEvent?.started?.snapshot, revealed = revealEvent?.revealed, rated = rateEvent?.rated
    if (startEvent && revealEvent && revealed && revealed.thinkingElapsedMs !== elapsed(startEvent.occurredAt, revealEvent.occurredAt)) return false
    if (startEvent && rateEvent && rated && rated.totalElapsedMs !== elapsed(startEvent.occurredAt, rateEvent.occurredAt)) return false
    return (!revealed || !rated || revealed.thinkingElapsedMs === rated.thinkingElapsedMs)
      && (!started || !rated || (started.countRegular ? rated.schedule !== null && sameJson(started.schedule, rated.schedule.before) : rated.schedule === null && rated.day.zoneId === started.zoneId))
  })
}
function descendingKey(leftTime: string, leftId: string, rightTime: string, rightId: string): boolean {
  const left = memoryUtcMicros(leftTime)!, right = memoryUtcMicros(rightTime)!
  return left > right || (left === right && leftId > rightId)
}
export function memoryOpenAttemptPageValid(value: unknown, limit = 20): value is MemoryOpenAttemptPage {
  return check(() => {
    if (!fields(value, ['items', 'nextCursor', 'asOf']) || !Array.isArray(value.items) || !integer(limit, 1, 50) || value.items.length > limit
      || !timestamp(value.asOf) || !cursorValid(value.nextCursor) || (value.nextCursor !== null && value.items.length !== limit) || bytes(value) > 1048576
      || !value.items.every(item => memoryAttemptSummaryValid(item) && open(item.state))) return false
    const items = value.items as MemoryAttemptSummary[]
    return new Set(items.map(item => item.id)).size === items.length && new Set(items.map(item => item.cardId)).size === items.length
      && items.every((item, index) => index === 0 || descendingKey(items[index - 1]!.startedAt, items[index - 1]!.id, item.startedAt, item.id))
  })
}

const phaseValid = (value: unknown) => value === 'LEARNING' || value === 'REVIEW' || value === 'RELEARNING'
function warningsValid(value: unknown): boolean { return Array.isArray(value) && (value.length === 0 || (value.length === 1 && value[0] === 'ARCHIVED_CONCEPT_WITH_OTHER_SOURCE')) }
function queueItemValid(value: unknown, budget: MemoryDailyBudget, asOf: string, includeOverBudget: boolean): value is MemoryQueueItem {
  if (!fields(value, ['cardId', 'kind', 'title', 'cardVersionId', 'cardVersionNumber', 'cardRevision', 'gateRevision', 'scheduleRevision', 'cycleId', 'phase', 'effectiveDueAt', 'budgetKind', 'countedToday', 'recommended', 'requiresBudgetConfirmation', 'warnings'])
    || !memoryIdValid(value.cardId) || !enumValue(value.kind, memoryKindLabels) || !text(value.title, 200) || !memoryIdValid(value.cardVersionId)
    || !integer(value.cardVersionNumber, 1, 1000) || !integer(value.cardRevision, 0, 1000) || value.cardVersionNumber > value.cardRevision + 1
    || !integer(value.gateRevision, 0) || !integer(value.scheduleRevision, 0) || !memoryIdValid(value.cycleId) || !phaseValid(value.phase)
    || !timeOrder(value.effectiveDueAt, asOf) || !enumValue(value.budgetKind, memoryBudgetKindLabels) || typeof value.countedToday !== 'boolean'
    || typeof value.recommended !== 'boolean' || typeof value.requiresBudgetConfirmation !== 'boolean' || !warningsValid(value.warnings)
    || (!includeOverBudget && !value.recommended)) return false
  const used = value.budgetKind === 'NEW' ? budget.newCards : budget.reviewCards, limit = value.budgetKind === 'NEW' ? budget.newLimit : budget.reviewLimit
  if (value.countedToday) return used > 0 && value.recommended && !value.requiresBudgetConfirmation
  return value.requiresBudgetConfirmation === (used >= limit) && (!value.recommended || !value.requiresBudgetConfirmation)
    && (value.phase === 'LEARNING' || value.budgetKind === 'REVIEW')
}
function queueOrdered(left: MemoryQueueItem, right: MemoryQueueItem): boolean {
  const leftRank = left.phase === 'REVIEW' ? 1 : 0, rightRank = right.phase === 'REVIEW' ? 1 : 0
  if (leftRank !== rightRank) return leftRank < rightRank
  const leftDue = memoryUtcMicros(left.effectiveDueAt)!, rightDue = memoryUtcMicros(right.effectiveDueAt)!
  return leftDue < rightDue || (leftDue === rightDue && left.cardId < right.cardId)
}
export function memoryReviewQueueValid(value: unknown, includeOverBudget?: boolean, limit = 20): value is MemoryReviewQueue {
  return check(() => {
    if (!fields(value, ['items', 'budget', 'asOf', 'serverTime', 'eligibleDueCards', 'recommendedCards', 'openAttempts', 'nextDueAt', 'includeOverBudget', 'nextCursor'])
      || !Array.isArray(value.items) || !integer(limit, 1, 50) || value.items.length > limit || !timestamp(value.asOf) || !timeOrder(value.asOf, value.serverTime)
      || !memoryDailyBudgetValid(value.budget, value.serverTime as string) || !integer(value.eligibleDueCards, 0) || !integer(value.recommendedCards, 0)
      || value.recommendedCards > value.eligibleDueCards || !integer(value.openAttempts, 0) || !nullableTime(value.nextDueAt)
      || (value.nextDueAt !== null && memoryUtcMicros(value.nextDueAt)! <= memoryUtcMicros(value.asOf)!)
      || typeof value.includeOverBudget !== 'boolean' || (includeOverBudget !== undefined && includeOverBudget !== value.includeOverBudget)
      || !cursorValid(value.nextCursor) || (value.nextCursor !== null && value.items.length !== limit) || bytes(value) > 1048576) return false
    const budget = value.budget, asOf = value.asOf, include = value.includeOverBudget
    if (!value.items.every(item => queueItemValid(item, budget, asOf, include))) return false
    const items = value.items as MemoryQueueItem[]
    return new Set(items.map(item => item.cardId)).size === items.length && items.every((item, index) => index === 0 || queueOrdered(items[index - 1]!, item))
      && items.length <= (include ? value.eligibleDueCards : value.recommendedCards) && items.filter(item => item.recommended).length <= value.recommendedCards
  })
}
function selectionValid(value: unknown): value is MemoryCramSelection {
  return fields(value, ['cardId', 'cardVersionId', 'cardVersionNumber', 'cardRevision']) && memoryIdValid(value.cardId) && memoryIdValid(value.cardVersionId)
    && integer(value.cardVersionNumber, 1, 1000) && integer(value.cardRevision, 0, 1000) && value.cardVersionNumber <= value.cardRevision + 1
}
function cramBasicsValid(value: Record<string, unknown>): boolean {
  return memoryIdValid(value.id) && text(value.title, 200) && dateRangeValid(value.startDate, value.endDate) && memoryReviewZoneValid(value.zoneId) && timestamp(value.createdAt)
}
export function memoryCramBatchValid(value: unknown, id?: string): value is MemoryCramBatch {
  return check(() => fields(value, ['id', 'title', 'startDate', 'endDate', 'zoneId', 'cards', 'createdAt']) && cramBasicsValid(value)
    && (id === undefined || value.id === id) && Array.isArray(value.cards) && value.cards.length >= 1 && value.cards.length <= 200 && value.cards.every(selectionValid)
    && new Set(value.cards.map(card => card.cardId)).size === value.cards.length && bytes(value) <= 131072)
}
export function memoryCramSummaryValid(value: unknown): value is MemoryCramSummary {
  return fields(value, ['id', 'title', 'startDate', 'endDate', 'zoneId', 'cardCount', 'createdAt']) && cramBasicsValid(value) && integer(value.cardCount, 1, 200)
}
export function memoryCramBatchPageValid(value: unknown, limit = 20): value is MemoryCramBatchPage {
  return check(() => {
    if (!fields(value, ['items', 'nextCursor']) || !Array.isArray(value.items) || !integer(limit, 1, 50) || value.items.length > limit || !value.items.every(memoryCramSummaryValid)
      || !cursorValid(value.nextCursor) || (value.nextCursor !== null && value.items.length !== limit) || bytes(value) > 1048576) return false
    const items = value.items as MemoryCramSummary[]
    return new Set(items.map(item => item.id)).size === items.length && items.every((item, index) => index === 0
      || descendingKey(items[index - 1]!.createdAt, items[index - 1]!.id, item.createdAt, item.id))
  })
}
function cardSummaryValid(value: unknown, cardId: string): value is Schema['MemorySummary'] {
  return fields(value, ['id', 'kind', 'title', 'status', 'revision', 'versionId', 'versionNumber', 'sourceCount', 'relatedConceptCount', 'updatedAt'])
    && value.id === cardId && enumValue(value.kind, memoryKindLabels) && text(value.title, 200) && (value.status === 'ACTIVE' || value.status === 'ARCHIVED')
    && integer(value.revision, 0, 1000) && memoryIdValid(value.versionId) && integer(value.versionNumber, 1, 1000) && value.versionNumber <= value.revision + 1
    && integer(value.sourceCount, 1, 10) && integer(value.relatedConceptCount, 0, 20) && timestamp(value.updatedAt)
}
function cramScheduleValid(value: unknown): value is MemoryCramSchedule {
  return fields(value, ['revision', 'status', 'pauseReason', 'phase', 'effectiveDueAt']) && integer(value.revision, 0) && phaseValid(value.phase) && timestamp(value.effectiveDueAt)
    && (value.status === 'ACTIVE' ? value.pauseReason === null : value.status === 'PAUSED' && (value.pauseReason === 'MANUAL' || value.pauseReason === 'SOURCE_REVIEW' || value.pauseReason === 'CARD_ARCHIVED'))
}
function memberEligibilityValid(value: unknown, cardStatus: string, gateRevision: number | null): boolean {
  if (!fields(value, ['sourceReviewAllowed', 'basisReady', 'blockers', 'warnings']) || !Array.isArray(value.blockers)
    || !value.blockers.every(blocker => enumValue(blocker, memoryBlockReasonLabels)) || !warningsValid(value.warnings)) return false
  const order = ['CARD_ARCHIVED', 'GATE_MISSING', 'REVIEW_REQUIRED', 'SOURCE_UNAVAILABLE', 'SOURCE_BODY_CHANGED',
    'SOURCE_UPSTREAM_REVIEW_REQUIRED', 'ARCHIVED_NOTE_SOURCE', 'NO_VALID_SOURCE'], blockers = value.blockers as string[]
  return blockers.every((blocker, index) => index === 0 || order.indexOf(blocker) > order.indexOf(blockers[index - 1]!))
    && blockers.includes('CARD_ARCHIVED') === (cardStatus === 'ARCHIVED') && blockers.includes('GATE_MISSING') === (gateRevision === null)
    && !(gateRevision === null && blockers.includes('REVIEW_REQUIRED')) && value.basisReady === (blockers.length === 0)
    && value.sourceReviewAllowed === blockers.every(blocker => blocker === 'REVIEW_REQUIRED')
}
function cramMemberValid(value: unknown, batch?: MemoryCramBatch): value is MemoryCramMember {
  if (!fields(value, ['position', 'selection', 'currentCard', 'gateRevision', 'eligibility', 'schedule', 'openAttempt']) || !integer(value.position, 1, 200)
    || !selectionValid(value.selection) || (batch !== undefined && !sameJson(value.selection, batch.cards[value.position - 1]))) return false
  if (value.currentCard === null) return value.gateRevision === null && value.eligibility === null && value.schedule === null && value.openAttempt === null
  if (!cardSummaryValid(value.currentCard, value.selection.cardId) || (value.gateRevision !== null && !integer(value.gateRevision, 0))
    || !memberEligibilityValid(value.eligibility, value.currentCard.status, value.gateRevision) || (value.schedule !== null && !cramScheduleValid(value.schedule))) return false
  return value.currentCard.revision >= value.selection.cardRevision && value.currentCard.versionNumber >= value.selection.cardVersionNumber
    && (value.currentCard.versionId === value.selection.cardVersionId) === (value.currentCard.versionNumber === value.selection.cardVersionNumber)
    && (value.currentCard.revision !== value.selection.cardRevision || (value.currentCard.status === 'ACTIVE' && value.currentCard.versionId === value.selection.cardVersionId))
    && (value.openAttempt === null || (memoryAttemptSummaryValid(value.openAttempt, undefined, value.selection.cardId) && open(value.openAttempt.state)))
}
export function memoryCramMemberPageValid(value: unknown, after?: number, limit = 20, batch?: MemoryCramBatch): value is MemoryCramMemberPage {
  return check(() => {
    if (!fields(value, ['items', 'nextAfter', 'asOf']) || !Array.isArray(value.items) || !integer(limit, 1, 50) || value.items.length > limit
      || (after !== undefined && !integer(after, 0, 200)) || (batch !== undefined && !memoryCramBatchValid(batch)) || !timestamp(value.asOf) || bytes(value) > 524288
      || !value.items.every(item => cramMemberValid(item, batch))) return false
    const items = value.items as MemoryCramMember[]
    if (!items.every((item, index) => item.position === (after ?? 0) + index + 1) || new Set(items.map(item => item.selection.cardId)).size !== items.length) return false
    if (batch !== undefined && items.length !== Math.min(limit, Math.max(0, batch.cards.length - (after ?? 0)))) return false
    return value.nextAfter === null ? batch === undefined || (items.at(-1)?.position ?? after ?? 0) >= batch.cards.length
      : integer(value.nextAfter, 1, 199) && items.length === limit && value.nextAfter === items.at(-1)?.position && (batch === undefined || value.nextAfter < batch.cards.length)
  })
}

function requestProblem(value: unknown, names: string[], maximum: number, validate: (request: Record<string, unknown>) => string): string {
  try {
    if (!fields(value, names)) return '请求字段不完整或包含未定义字段，请重新核对。'
    if (bytes(value) > maximum) return `完整请求超过 ${maximum / 1024} KiB，请缩减后提交；原文不会截断。`
    return validate(value)
  } catch { return '请求内容无法读取，请重新核对。' }
}
const revisionProblem = (value: unknown) => integer(value, 0) ? '' : '缺少有效的尝试修订，请先读取当前记录。'
const responseProblem = (value: Record<string, unknown>) => responseValid(value.responseMode, value.response) ? ''
  : value.responseMode === 'THOUGHT' ? '在心中回忆模式需要明确提交空串；已有文字只能由你明确清除。' : '书面回答需为 1–10000 个完整字符，不能全为空白或包含 NUL。'
export function memoryStartAttemptProblem(value: unknown): string {
  return requestProblem(value, ['expectedCardRevision', 'expectedGateRevision', 'expectedScheduleRevision', 'mode', 'countRegular', 'cramBatchId', 'continueBeyondBudget'], 16384, request => {
    if (!integer(request.expectedCardRevision, 0, 1000) || !integer(request.expectedGateRevision, 0)) return '请先核对当前卡片和来源门禁修订。'
    if (!modeValid(request.mode, request.countRegular, request.cramBatchId)) return '常规回忆须计入排程且无冲刺批次；冲刺练习须明确批次和是否计入。'
    if (typeof request.continueBeyondBudget !== 'boolean') return '是否继续超过每日建议需要明确选择。'
    if (request.countRegular) return integer(request.expectedScheduleRevision, 0) ? '' : '计入常规的回忆需要当前排程修订。'
    return request.expectedScheduleRevision === null && request.continueBeyondBudget === false ? '' : '未计入常规的冲刺须保留排程修订为 null，且不申请每日预算。'
  })
}
export function memorySaveResponseProblem(value: unknown): string {
  return requestProblem(value, ['expectedRevision', 'responseMode', 'response'], 131072, request => revisionProblem(request.expectedRevision) || responseProblem(request))
}
export function memoryRevealProblem(value: unknown): string {
  return requestProblem(value, ['expectedRevision', 'responseMode', 'response', 'thoughtConfirmed'], 131072, request => revisionProblem(request.expectedRevision)
    || responseProblem(request) || (request.thoughtConfirmed === true ? '' : '请明确确认已经完成本次独立回忆，再揭示参考。'))
}
export function memoryRateProblem(value: unknown): string {
  return requestProblem(value, ['expectedRevision', 'rating'], 16384, request => revisionProblem(request.expectedRevision)
    || (enumValue(request.rating, memoryRatingLabels) ? '' : '请根据这次独立回忆明确选择忘记、困难、记得或熟悉。'))
}
export function memorySkipProblem(value: unknown): string {
  return requestProblem(value, ['expectedRevision', 'reason'], 16384, request => revisionProblem(request.expectedRevision)
    || (text(request.reason, 1000) ? '' : '请填写 1–1000 个完整字符的跳过理由。'))
}
export function memoryCreateCramBatchProblem(value: unknown): string {
  return requestProblem(value, ['title', 'startDate', 'endDate', 'zoneId', 'cardIds'], 16384, request => {
    if (!text(request.title, 200)) return '批次标题需为 1–200 个完整字符，不能全为空白或包含 NUL。'
    if (!dateRangeValid(request.startDate, request.endDate)) return '请填写有效的开始和结束日期，包含首尾为 1–90 天，年份为 1000–9999。'
    if (!memoryReviewZoneValid(request.zoneId)) return '请明确填写 IANA 时区或 UTC。'
    return Array.isArray(request.cardIds) && request.cardIds.length >= 1 && request.cardIds.length <= 200 && request.cardIds.every(memoryIdValid)
      && new Set(request.cardIds).size === request.cardIds.length ? '' : '请选择 1–200 张不同的回忆卡，卡片顺序会保存到批次。'
  })
}
