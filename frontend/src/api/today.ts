import { ApiError, errorText } from './client'
import { memoryIdValid } from './memory'
import { memoryDailyBudgetValid, memoryLocalDateValid } from './memoryAttempt'
import { memoryReviewZoneValid, memoryUtcMicros } from './memoryReview'
import type { Placement, PlacementContent } from './todayPlan'

export const todaySourceKinds = ['KNOWLEDGE_DRAFT', 'TRAINING_SESSION', 'PREPARATION_TASK', 'MEMORY_CARD', 'REVIEW_ACTION', 'INTERVIEW_EVENT'] as const
export const todaySections = ['CONTINUE', 'ATTENTION', 'TODAY', 'BACKLOG'] as const
export type TodaySourceKind = typeof todaySourceKinds[number]
export type TodaySection = typeof todaySections[number]
export type TodayAvailability = 'READY' | 'DEPENDENCY_PENDING' | 'NOT_CONFIGURED' | 'ERROR'
export type TodayAvailabilityReason = 'NONE' | 'QUERY_NOT_INTEGRATED' | 'MEMORY_SETTINGS_REQUIRED' | 'SOURCE_READ_FAILED' | 'SOURCE_INTEGRITY_FAILED'
export type TodayCount = { value: number; mode: 'EXACT' | 'LOWER_BOUND' } | { value: null; mode: 'UNAVAILABLE' }
export type TodayCapability = { status: TodayAvailability; reason: TodayAvailabilityReason }
export type TodaySourceRef = { kind: TodaySourceKind; id: string }
export type TodayTargetRef = { itemId: string; versionId: string }
export type TodayFilter = { targetId: string | null; includeOverBudget: boolean }
export type TodayDayContext = { zoneId: string; localDate: string; startAt: string; endAt: string; asOf: string; serverTime: string }
export type TodayCalendarWindow = { from: string; to: string; startAt: string; endAt: string }
export type TodayOverviewQuery = { zoneId: string; targetId?: string | null; includeOverBudget?: boolean }
export type TodayItemsQuery = TodayOverviewQuery & { kind: TodaySourceKind; section: TodaySection; cursor?: string | null; limit?: number }
export type TodayCalendarQuery = { from: string; to: string; zoneId: string; kind: TodaySourceKind; targetId?: string | null; cursor?: string | null; limit?: number }
export type TodaySourceQuery = { zoneId: string }

type EmptyPayload = { knowledge: null; training: null; task: null; memory: null; action: null; interview: null }
type Payload<K extends TodaySourceKind, T> = { kind: K } & Omit<EmptyPayload, keyof T> & T
export type TodayKnowledgeStamp = { itemRevision: number; draftRevision: number; currentVersionId: string }
export type TodayTrainingStamp = { revision: number; generation: number; questionsHash: string }
export type TodayTaskStamp = { revision: number }
export type TodayMemoryStamp = { cardRevision: number; cardVersionId: string; gateRevision: number | null; scheduleRevision: number | null; attemptId: string | null; attemptRevision: number | null; settingsRevision: number | null }
export type TodayActionStamp = { revision: number; progressHash: string }
export type TodayInterviewStamp = { revision: number }
export type TodaySourceStamp = Payload<'KNOWLEDGE_DRAFT', { knowledge: TodayKnowledgeStamp }>
  | Payload<'TRAINING_SESSION', { training: TodayTrainingStamp }> | Payload<'PREPARATION_TASK', { task: TodayTaskStamp }>
  | Payload<'MEMORY_CARD', { memory: TodayMemoryStamp }> | Payload<'REVIEW_ACTION', { action: TodayActionStamp }>
  | Payload<'INTERVIEW_EVENT', { interview: TodayInterviewStamp }>
export type TodayKnowledgeState = { itemKind: 'NOTE' | 'CONCEPT'; status: 'ACTIVE' | 'ARCHIVED'; unpublishedDraft: boolean; draftSavedAt: string }
export type TodayTrainingState = { mode: 'LEARNING' | 'FOCUSED' | 'MOCK'; status: 'DRAFT' | 'RUNNING' | 'PAUSED' | 'ENDED'; savedDraftCount: number; lastDraftSavedAt: string | null; lastActivityAt: string; repracticeId: string | null }
export type TodayTaskState = { status: 'TODO' | 'IN_PROGRESS' | 'PAUSED' | 'DONE' | 'CANCELLED'; startedAt: string | null; completedAt: string | null }
export type TodayMemoryBlockReason = 'CARD_ARCHIVED' | 'GATE_MISSING' | 'REVIEW_REQUIRED' | 'SOURCE_UNAVAILABLE' | 'SOURCE_BODY_CHANGED' | 'SOURCE_UPSTREAM_REVIEW_REQUIRED' | 'ARCHIVED_NOTE_SOURCE' | 'NO_VALID_SOURCE'
export type TodayMemoryEligibility = { sourceReviewAllowed: boolean; basisReady: boolean; blockers: TodayMemoryBlockReason[]; warnings: 'ARCHIVED_CONCEPT_WITH_OTHER_SOURCE'[] }
export type TodayOpenMemoryAttempt = { id: string; revision: number; mode: 'REGULAR' | 'CRAM'; countRegular: boolean; state: 'THINKING' | 'REVEALED'; startedAt: string; updatedAt: string }
export type TodayQueueRecommendation = { budgetKind: 'NEW' | 'REVIEW'; countedToday: boolean; recommended: boolean; requiresBudgetConfirmation: boolean }
export type TodayMemoryState = { cardStatus: 'ACTIVE' | 'ARCHIVED'; scheduleStatus: 'ACTIVE' | 'PAUSED' | null; pauseReason: 'MANUAL' | 'SOURCE_REVIEW' | 'CARD_ARCHIVED' | null; phase: 'LEARNING' | 'REVIEW' | 'RELEARNING' | null; eligibility: TodayMemoryEligibility; openAttempt: TodayOpenMemoryAttempt | null; recommendation: TodayQueueRecommendation | null }
export type TodayActionState = { status: 'OPEN' | 'MERGED'; mergedInto: string | null; sourceCount: number; trackingCount: number }
export type TodayInterviewState = { status: 'PLANNED' | 'COMPLETED' | 'CANCELLED'; timing: 'UPCOMING' | 'DUE' | 'IN_PROGRESS' | 'PAST' | 'CLOSED'; reminderPending: boolean }
export type TodaySourceStatus = Payload<'KNOWLEDGE_DRAFT', { knowledge: TodayKnowledgeState }>
  | Payload<'TRAINING_SESSION', { training: TodayTrainingState }> | Payload<'PREPARATION_TASK', { task: TodayTaskState }>
  | Payload<'MEMORY_CARD', { memory: TodayMemoryState }> | Payload<'REVIEW_ACTION', { action: TodayActionState }>
  | Payload<'INTERVIEW_EVENT', { interview: TodayInterviewState }>
export type TodaySourceTime = { kind: 'NONE'; instant: null; localDate: null; zoneId: null; zoneBasis: null }
  | { kind: 'INSTANT'; instant: string; localDate: null; zoneId: string; zoneBasis: 'SOURCE' | 'DISPLAY' }
  | { kind: 'LOCAL_DATE'; instant: null; localDate: string; zoneId: string; zoneBasis: 'SOURCE' | 'PLACEMENT' }
export type TodayEventWindow = { startAt: string; endAt: string; zoneId: string; remindAt: string }
export type TodayEstimate = { minutes: number; basis: 'SOURCE' | 'USER' } | { minutes: null; basis: 'UNKNOWN' }
export type TodayRoute = 'KNOWLEDGE' | 'TRAINING' | 'PREPARATION_TASKS' | 'MEMORY_CARDS' | 'MEMORY_RECALL' | 'REVIEW' | 'SCHEDULE'
export type TodayContinuation = { route: TodayRoute; item: { item: string } | null; action: { action: string } | null; attempt: { attempt: string } | null }
export type TodayActionKind = 'OPEN_SOURCE' | 'CONTINUE_TRAINING' | 'CONTINUE_MEMORY_ATTEMPT' | 'OPEN_MEMORY_QUEUE' | 'REVIEW_SOURCE' | 'OPEN_REVIEW_ACTION' | 'OPEN_INTERVIEW'
export type TodayAvailableAction = { kind: TodayActionKind; continuation: TodayContinuation }
export type TodayItem = { source: TodaySourceRef; stamp: TodaySourceStamp; title: string; section: TodaySection | null; sourceStatus: TodaySourceStatus;
  reasons: TodayReason[]; targetRefs: TodayTargetRef[]; time: TodaySourceTime; eventWindow: TodayEventWindow | null; estimate: TodayEstimate;
  placement: Placement | null; continuation: TodayContinuation; pendingCount: number | null; availableActions: TodayAvailableAction[]; warnings: TodayWarning[] }
export type TodaySourceSectionCapability = { kind: TodaySourceKind; section: TodaySection; capability: TodayCapability; count: TodayCount; scanLimited: boolean }
export type TodaySectionPreview = { section: TodaySection; items: TodayItem[] }
export type TodayDailyBudget = { settingsRevision: number; zoneId: string; localDate: string; windowStart: string; windowEnd: string; newLimit: number; reviewLimit: number; newCards: number; reviewCards: number; ratedEvents: number }
export type TodayMemoryQueueInfo = { budget: TodayDailyBudget; eligibleDueCards: TodayCount; recommendedCards: TodayCount; nextDueAt: string | null; includeOverBudget: boolean }
export type TodayOverview = { context: TodayDayContext; filter: TodayFilter; capabilities: TodaySourceSectionCapability[]; sections: TodaySectionPreview[]; memoryQueue: TodayMemoryQueueInfo | null }
export type TodayPage = { context: TodayDayContext; filter: TodayFilter; kind: TodaySourceKind; section: TodaySection; capability: TodayCapability; count: TodayCount; scanLimited: boolean; items: TodayItem[]; nextCursor: string | null; memoryQueue: TodayMemoryQueueInfo | null }
export type TodayCalendarPage = { context: TodayDayContext; window: TodayCalendarWindow; kind: TodaySourceKind; targetId: string | null; capability: TodayCapability; count: TodayCount; scanLimited: boolean; items: TodayItem[]; nextCursor: string | null }
export type TodaySourceDetail = { context: TodayDayContext; source: TodaySourceRef; capability: TodayCapability; item: TodayItem | null; exclusionReason: TodayExclusionReason | null }

export const todaySourceKindLabels: Record<TodaySourceKind, string> = { KNOWLEDGE_DRAFT: '知识草稿', TRAINING_SESSION: '文字训练', PREPARATION_TASK: '准备任务', MEMORY_CARD: '主动回忆', REVIEW_ACTION: '改进动作', INTERVIEW_EVENT: '面试安排' }
export const todaySectionLabels: Record<TodaySection, string> = { CONTINUE: '继续进行', ATTENTION: '需要处理', TODAY: '今日安排', BACKLOG: '待安排与近期' }
export const todayAvailabilityLabels: Record<TodayAvailability, string> = { READY: '本次读取可用', DEPENDENCY_PENDING: '查询尚未接入', NOT_CONFIGURED: '尚需配置', ERROR: '本次读取失败' }
export const todayReasonLabels = {
  UNPUBLISHED_DRAFT: '知识草稿尚未发布', TRAINING_DRAFT: '训练尚未开始', TRAINING_RUNNING: '训练进行中', TRAINING_PAUSED: '训练已暂停', SAVED_ANSWER_DRAFT: '有已保存的回答草稿',
  TASK_IN_PROGRESS: '任务进行中', TASK_OVERDUE: '已过原计划日期', TASK_TODAY: '任务原计划日期是今天', TASK_UNSCHEDULED: '任务尚未安排日期',
  MEMORY_OPEN_ATTEMPT: '有尚未结束的回忆', MEMORY_DUE: '常规排程已到期', MEMORY_NOT_RECOMMENDED: '本次未列入建议队列', MEMORY_BUDGET_CONFIRMATION: '开始前需确认超过建议数量',
  MEMORY_SOURCE_REVIEW: '卡片或来源需要处理', ACTION_PENDING: '有待处理的动作来源', INTERVIEW_REMINDER: '面试提醒尚未确认', INTERVIEW_IN_PROGRESS: '处于计划面试时段',
  INTERVIEW_TODAY: '面试时段与今天相交', INTERVIEW_UPCOMING: '近期面试安排', INTERVIEW_TIME_PASSED: '已过计划时间，尚未标记结果',
  PLACEMENT_TODAY: '偏好安排在今天', PLACEMENT_OVERDUE: '已过偏好安排日期',
} as const
export type TodayReason = keyof typeof todayReasonLabels
export const todayWarningLabels = { SOURCE_ZONE_DIFFERS: '来源时区与当前显示时区不同', ARCHIVED_CONCEPT_WITH_OTHER_SOURCE: '有已归档概念，仍有其他有效依据', MEMORY_QUEUE_RECHECK_REQUIRED: '进入回忆队列后重新核对条件', PLACEMENT_CONTINUATION_RETAINED: '安排限制已保存，仍保留正在进行事项或必要提醒的入口' } as const
export type TodayWarning = keyof typeof todayWarningLabels
export const todayExclusionReasonLabels = {
  KNOWLEDGE_ARCHIVED: '知识已归档', NO_UNPUBLISHED_DRAFT: '没有待发布草稿', TRAINING_ENDED: '训练已结束', TASK_PAUSED: '任务已暂停', TASK_DONE: '任务已完成', TASK_CANCELLED: '任务已取消', TASK_FUTURE: '任务安排在未来日期',
  MEMORY_NO_SCHEDULE: '尚无常规排程', MEMORY_PAUSED: '常规排程已暂停', MEMORY_BLOCKED: '卡片或来源条件阻断', MEMORY_FUTURE_DUE: '排程尚未到期', MEMORY_NOT_RECOMMENDED: '本次不在建议队列中',
  ACTION_MERGED: '动作已合并', ACTION_NO_PENDING: '当前没有待处理来源', INTERVIEW_CLOSED: '面试已标记结果', INTERVIEW_OUTSIDE_WINDOW: '面试不在今日及近期范围',
  PLACEMENT_PAUSED: '安排推荐已暂停', PLACEMENT_SUPPRESSED_TODAY: '本显示日暂不推荐', PLACEMENT_FUTURE: '偏好安排在未来日期',
} as const
export type TodayExclusionReason = keyof typeof todayExclusionReasonLabels
export const todayActionLabels: Record<TodayActionKind, string> = { OPEN_SOURCE: '打开来源', CONTINUE_TRAINING: '进入训练', CONTINUE_MEMORY_ATTEMPT: '恢复这次回忆', OPEN_MEMORY_QUEUE: '前往回忆队列', REVIEW_SOURCE: '核对卡片与来源', OPEN_REVIEW_ACTION: '查看改进动作', OPEN_INTERVIEW: '查看面试安排' }

const MAX = Number.MAX_SAFE_INTEGER
const unionFields = ['kind', 'knowledge', 'training', 'task', 'memory', 'action', 'interview'] as const
const payloadKeys: Record<TodaySourceKind, keyof EmptyPayload> = { KNOWLEDGE_DRAFT: 'knowledge', TRAINING_SESSION: 'training', PREPARATION_TASK: 'task', MEMORY_CARD: 'memory', REVIEW_ACTION: 'action', INTERVIEW_EVENT: 'interview' }
const blockReasons: readonly TodayMemoryBlockReason[] = ['CARD_ARCHIVED', 'GATE_MISSING', 'REVIEW_REQUIRED', 'SOURCE_UNAVAILABLE', 'SOURCE_BODY_CHANGED', 'SOURCE_UPSTREAM_REVIEW_REQUIRED', 'ARCHIVED_NOTE_SOURCE', 'NO_VALID_SOURCE']
const sectionSources: Record<TodaySection, readonly TodaySourceKind[]> = { CONTINUE: ['TRAINING_SESSION', 'MEMORY_CARD', 'PREPARATION_TASK', 'KNOWLEDGE_DRAFT'], ATTENTION: ['INTERVIEW_EVENT', 'MEMORY_CARD', 'PREPARATION_TASK', 'REVIEW_ACTION', 'TRAINING_SESSION', 'KNOWLEDGE_DRAFT'], TODAY: ['INTERVIEW_EVENT', 'PREPARATION_TASK', 'MEMORY_CARD', 'TRAINING_SESSION', 'REVIEW_ACTION', 'KNOWLEDGE_DRAFT'], BACKLOG: ['INTERVIEW_EVENT', 'PREPARATION_TASK'] }
const fields = (value: unknown, names: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown, min = 0, max = MAX): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
const oneOf = <T extends string>(value: unknown, options: readonly T[]): value is T => typeof value === 'string' && options.includes(value as T)
const nullableId = (value: unknown): value is string | null => value === null || memoryIdValid(value)
const nullableRevision = (value: unknown): value is number | null => value === null || integer(value)
const timestamp = (value: unknown): value is string => memoryUtcMicros(value) !== undefined
const nullableTime = (value: unknown): value is string | null => value === null || timestamp(value)
const beforeOrEqual = (left: string, right: string) => memoryUtcMicros(left)! <= memoryUtcMicros(right)!
const sameTime = (left: string, right: string) => memoryUtcMicros(left) === memoryUtcMicros(right)
const bytes = (value: unknown) => new TextEncoder().encode(JSON.stringify(value)).length
const check = (rule: () => boolean): boolean => { try { return rule() } catch { return false } }
const cursorValid = (value: unknown): value is string | null => value === null || (typeof value === 'string' && /^[\x21-\x7e]{1,4096}$/.test(value))
const hashValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
// 沿用 Java String.isBlank 的空白集合；NBSP、U+2007、U+202F 不属于该集合，不能误拒来源合法标题。
const titleTextValid = (value: unknown): value is string => typeof value === 'string' && /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
  && !/[\uD800-\uDFFF]/u.test(value)
const titleValid = (value: unknown): value is string => titleTextValid(value) && Array.from(value).length <= 200 && !value.includes('\u0000')
// 兼容范围限于旧面试来源；其他摘要与新生成文本继续使用原严格标题规则。
export const todaySourceTitleValid = (value: unknown, kind: TodaySourceKind): value is string => kind === 'INTERVIEW_EVENT'
  ? titleTextValid(value) && value.length <= 200 : titleValid(value)
const orderedEnums = <T extends string>(value: unknown, order: readonly T[]): value is T[] => Array.isArray(value)
  && value.every((item, index) => oneOf(item, order) && (index === 0 || order.indexOf(value[index - 1]) < order.indexOf(item)))

// 浏览器仅预检查，原名提交由服务端确认支持；不规范化别名改变预算身份。
export const todayZoneValid = memoryReviewZoneValid
export const todayDateValid = memoryLocalDateValid
export const todayIdValid = memoryIdValid
export function todaySourceRefValid(value: unknown): value is TodaySourceRef { return fields(value, ['kind', 'id']) && oneOf(value.kind, todaySourceKinds) && memoryIdValid(value.id) }
export function todaySourceKey(source: TodaySourceRef): string { return `${source.kind}/${source.id}` }
export function todayPlacementContentValid(value: unknown, kind: TodaySourceKind): value is PlacementContent {
  if (!oneOf(kind, todaySourceKinds) || !fields(value, ['plannedDate', 'planZoneId', 'estimatedMinutes', 'priority', 'orderValue', 'dateLocked', 'visibility', 'suppressedOn'])
    || !integer(value.priority, 1, 5) || !integer(value.orderValue, 0, 1000000) || typeof value.dateLocked !== 'boolean'
    || !oneOf(value.visibility, ['VISIBLE', 'PAUSED'])) return false
  const dated = ['KNOWLEDGE_DRAFT', 'TRAINING_SESSION', 'REVIEW_ACTION'].includes(kind)
  if (value.plannedDate === null ? value.planZoneId !== null : !dated || !todayDateValid(value.plannedDate) || !todayZoneValid(value.planZoneId)) return false
  if (value.estimatedMinutes !== null && (!(dated || kind === 'MEMORY_CARD') || !integer(value.estimatedMinutes, 1, 1440))) return false
  if (value.dateLocked && !(dated ? value.plannedDate !== null : kind === 'PREPARATION_TASK') || value.visibility === 'PAUSED' && !dated) return false
  return value.suppressedOn === null || fields(value.suppressedOn, ['localDate', 'zoneId']) && todayDateValid(value.suppressedOn.localDate) && todayZoneValid(value.suppressedOn.zoneId)
}
export function todayPlacementValid(value: unknown, source?: TodaySourceRef): value is Placement {
  return check(() => {
    if (!fields(value, ['id', 'source', 'revision', 'plannedDate', 'planZoneId', 'estimatedMinutes', 'priority', 'orderValue', 'dateLocked', 'visibility', 'suppressedOn', 'createdAt', 'updatedAt'])
      || !memoryIdValid(value.id) || !todaySourceRefValid(value.source) || !integer(value.revision) || !timestamp(value.createdAt) || !timestamp(value.updatedAt)
      || !beforeOrEqual(value.createdAt, value.updatedAt) || value.revision === 0 && !sameTime(value.createdAt, value.updatedAt)
      || source !== undefined && (!todaySourceRefValid(source) || todaySourceKey(value.source) !== todaySourceKey(source)) || bytes(value) > 32768) return false
    const { plannedDate, planZoneId, estimatedMinutes, priority, orderValue, dateLocked, visibility, suppressedOn } = value
    return todayPlacementContentValid({ plannedDate, planZoneId, estimatedMinutes, priority, orderValue, dateLocked, visibility, suppressedOn }, value.source.kind)
  })
}
export function todayDateRangeProblem(from: unknown, to: unknown): string {
  return !todayDateValid(from) || !todayDateValid(to) || from > to
    || (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000 >= 31
    ? '日期须为 1000–9999 年的有效日期，结束日期不早于开始日期，包含首尾最多 31 天。' : ''
}

function dateAt(instant: string, zoneId: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zoneId, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(instant))
  const part = (type: string) => parts.find(item => item.type === type)?.value ?? ''
  return `${part('year').padStart(4, '0')}-${part('month')}-${part('day')}`
}
function nextDate(value: string): string | undefined {
  const result = new Date(Date.parse(`${value}T00:00:00Z`) + 86400000).toISOString().slice(0, 10)
  return todayDateValid(result) ? result : undefined
}
function dayBoundaryValid(instant: string, date: string, zoneId: string): boolean {
  const micros = memoryUtcMicros(instant)!
  if (micros % 1000n !== 0n) return false
  // 比较边界两侧的当地日期，允许夏令时午夜缺口及整日跳过，不把一天固定为 24 小时。
  const previous = new Date(Number(micros / 1000n) - 1).toISOString()
  return dateAt(previous, zoneId) < date && dateAt(instant, zoneId) >= date
}
function contextValid(value: unknown): value is TodayDayContext {
  return fields(value, ['zoneId', 'localDate', 'startAt', 'endAt', 'asOf', 'serverTime']) && todayZoneValid(value.zoneId) && todayDateValid(value.localDate)
    && timestamp(value.startAt) && timestamp(value.endAt) && timestamp(value.asOf) && timestamp(value.serverTime)
    && beforeOrEqual(value.startAt, value.asOf) && memoryUtcMicros(value.asOf)! < memoryUtcMicros(value.endAt)!
    && beforeOrEqual(value.asOf, value.serverTime) && dateAt(value.asOf, value.zoneId) === value.localDate && dateAt(value.serverTime, value.zoneId) === value.localDate
    && dayBoundaryValid(value.startAt, value.localDate, value.zoneId) && nextDate(value.localDate) !== undefined && dayBoundaryValid(value.endAt, nextDate(value.localDate)!, value.zoneId)
}
function filterValid(value: unknown): value is TodayFilter { return fields(value, ['targetId', 'includeOverBudget']) && nullableId(value.targetId) && typeof value.includeOverBudget === 'boolean' }
function capabilityValid(value: unknown): value is TodayCapability {
  if (!fields(value, ['status', 'reason'])) return false
  return value.status === 'READY' ? value.reason === 'NONE' : value.status === 'DEPENDENCY_PENDING' ? value.reason === 'QUERY_NOT_INTEGRATED'
    : value.status === 'NOT_CONFIGURED' ? value.reason === 'MEMORY_SETTINGS_REQUIRED' : value.status === 'ERROR' && oneOf(value.reason, ['SOURCE_READ_FAILED', 'SOURCE_INTEGRITY_FAILED'])
}
export function todayCountValid(value: unknown): value is TodayCount {
  return fields(value, ['value', 'mode']) && (value.mode === 'UNAVAILABLE' ? value.value === null : oneOf(value.mode, ['EXACT', 'LOWER_BOUND']) && integer(value.value))
}
function availableCount(capability: TodayCapability, count: TodayCount, scanLimited: boolean, itemCount: number, cursor: string | null): boolean {
  if (capability.status !== 'READY') return count.mode === 'UNAVAILABLE' && itemCount === 0 && !scanLimited && cursor === null
  // 扫描额度限制本页摘要，不否定源域独立 COUNT；有后续候选时仍须给出可继续读取的游标。
  return count.mode !== 'UNAVAILABLE' && count.value >= itemCount && (!scanLimited || cursor !== null)
}
function unionValid(value: unknown, kind: TodaySourceKind): value is Record<string, unknown> {
  return fields(value, unionFields) && value.kind === kind && unionFields.slice(1).every(key => key === payloadKeys[kind] ? value[key] !== null : value[key] === null)
}
function stampValid(value: unknown, kind: TodaySourceKind): value is TodaySourceStamp {
  if (!unionValid(value, kind)) return false
  const payload = value[payloadKeys[kind]]
  if (kind === 'KNOWLEDGE_DRAFT') return fields(payload, ['itemRevision', 'draftRevision', 'currentVersionId']) && integer(payload.itemRevision) && integer(payload.draftRevision) && memoryIdValid(payload.currentVersionId)
  if (kind === 'TRAINING_SESSION') return fields(payload, ['revision', 'generation', 'questionsHash']) && integer(payload.revision) && integer(payload.generation) && hashValid(payload.questionsHash)
  if (kind === 'REVIEW_ACTION') return fields(payload, ['revision', 'progressHash']) && integer(payload.revision, 0, 1000) && hashValid(payload.progressHash)
  if (kind === 'MEMORY_CARD') return fields(payload, ['cardRevision', 'cardVersionId', 'gateRevision', 'scheduleRevision', 'attemptId', 'attemptRevision', 'settingsRevision'])
    && integer(payload.cardRevision, 0, 1000) && memoryIdValid(payload.cardVersionId) && nullableRevision(payload.gateRevision) && nullableRevision(payload.scheduleRevision)
    && nullableId(payload.attemptId) && nullableRevision(payload.attemptRevision) && (payload.attemptId === null) === (payload.attemptRevision === null) && nullableRevision(payload.settingsRevision)
  return fields(payload, ['revision']) && integer(payload.revision)
}
function memoryStateValid(value: unknown, stamp: TodayMemoryStamp): value is TodayMemoryState {
  if (!fields(value, ['cardStatus', 'scheduleStatus', 'pauseReason', 'phase', 'eligibility', 'openAttempt', 'recommendation']) || !oneOf(value.cardStatus, ['ACTIVE', 'ARCHIVED'])) return false
  if (stamp.scheduleRevision === null ? value.scheduleStatus !== null || value.pauseReason !== null || value.phase !== null
    : !oneOf(value.phase, ['LEARNING', 'REVIEW', 'RELEARNING']) || (value.scheduleStatus === 'ACTIVE' ? value.pauseReason !== null
      : value.scheduleStatus !== 'PAUSED' || !oneOf(value.pauseReason, ['MANUAL', 'SOURCE_REVIEW', 'CARD_ARCHIVED']))) return false
  const eligibility = value.eligibility
  if (!fields(eligibility, ['sourceReviewAllowed', 'basisReady', 'blockers', 'warnings']) || !orderedEnums(eligibility.blockers, blockReasons)
    || !orderedEnums(eligibility.warnings, ['ARCHIVED_CONCEPT_WITH_OTHER_SOURCE']) || typeof eligibility.sourceReviewAllowed !== 'boolean'
    || eligibility.basisReady !== (eligibility.blockers.length === 0) || eligibility.blockers.includes('CARD_ARCHIVED') !== (value.cardStatus === 'ARCHIVED')
    || eligibility.blockers.includes('GATE_MISSING') !== (stamp.gateRevision === null) || (stamp.gateRevision === null && eligibility.blockers.includes('REVIEW_REQUIRED'))
    || eligibility.sourceReviewAllowed !== eligibility.blockers.every(reason => reason === 'REVIEW_REQUIRED')) return false
  const open = value.openAttempt
  if (open === null ? stamp.attemptId !== null : !fields(open, ['id', 'revision', 'mode', 'countRegular', 'state', 'startedAt', 'updatedAt'])
    || !memoryIdValid(open.id) || !integer(open.revision) || open.id !== stamp.attemptId || open.revision !== stamp.attemptRevision || value.cardStatus !== 'ACTIVE'
    || !oneOf(open.mode, ['REGULAR', 'CRAM']) || typeof open.countRegular !== 'boolean'
    || (open.mode === 'REGULAR' && !open.countRegular) || !oneOf(open.state, ['THINKING', 'REVEALED']) || (open.state === 'REVEALED' && !integer(open.revision, 1))
    || !timestamp(open.startedAt) || !timestamp(open.updatedAt) || !beforeOrEqual(open.startedAt, open.updatedAt)) return false
  const recommendation = value.recommendation
  return recommendation === null || (fields(recommendation, ['budgetKind', 'countedToday', 'recommended', 'requiresBudgetConfirmation'])
    && oneOf(recommendation.budgetKind, ['NEW', 'REVIEW']) && typeof recommendation.countedToday === 'boolean' && typeof recommendation.recommended === 'boolean'
    && typeof recommendation.requiresBudgetConfirmation === 'boolean' && stamp.settingsRevision !== null && value.scheduleStatus === 'ACTIVE'
    && value.cardStatus === 'ACTIVE' && eligibility.basisReady === true && open === null
    && (!recommendation.countedToday || (recommendation.recommended && !recommendation.requiresBudgetConfirmation))
    && (!recommendation.requiresBudgetConfirmation || !recommendation.recommended))
}
function statusValid(value: unknown, stamp: TodaySourceStamp): value is TodaySourceStatus {
  if (!unionValid(value, stamp.kind)) return false
  const payload = value[payloadKeys[stamp.kind]]
  switch (stamp.kind) {
    case 'KNOWLEDGE_DRAFT': return fields(payload, ['itemKind', 'status', 'unpublishedDraft', 'draftSavedAt']) && oneOf(payload.itemKind, ['NOTE', 'CONCEPT'])
      && oneOf(payload.status, ['ACTIVE', 'ARCHIVED']) && typeof payload.unpublishedDraft === 'boolean' && timestamp(payload.draftSavedAt)
    case 'TRAINING_SESSION': return fields(payload, ['mode', 'status', 'savedDraftCount', 'lastDraftSavedAt', 'lastActivityAt', 'repracticeId'])
      && oneOf(payload.mode, ['LEARNING', 'FOCUSED', 'MOCK']) && oneOf(payload.status, ['DRAFT', 'RUNNING', 'PAUSED', 'ENDED']) && integer(payload.savedDraftCount, 0, 60)
      && (payload.savedDraftCount === 0 ? payload.lastDraftSavedAt === null : timestamp(payload.lastDraftSavedAt)) && timestamp(payload.lastActivityAt)
      && (payload.lastDraftSavedAt === null || beforeOrEqual(payload.lastDraftSavedAt as string, payload.lastActivityAt)) && nullableId(payload.repracticeId)
    case 'PREPARATION_TASK': return fields(payload, ['status', 'startedAt', 'completedAt']) && oneOf(payload.status, ['TODO', 'IN_PROGRESS', 'PAUSED', 'DONE', 'CANCELLED'])
      && nullableTime(payload.startedAt) && nullableTime(payload.completedAt) && (payload.status === 'TODO' ? payload.startedAt === null : payload.status !== 'IN_PROGRESS' || payload.startedAt !== null)
      && (payload.status === 'DONE') === (payload.completedAt !== null) && (payload.startedAt === null || payload.completedAt === null || beforeOrEqual(payload.startedAt, payload.completedAt))
    case 'MEMORY_CARD': return memoryStateValid(payload, stamp.memory)
    case 'REVIEW_ACTION': return fields(payload, ['status', 'mergedInto', 'sourceCount', 'trackingCount']) && integer(payload.sourceCount, 0, 20)
      && integer(payload.trackingCount, 0, payload.sourceCount) && (payload.status === 'OPEN' ? payload.mergedInto === null : payload.status === 'MERGED' && memoryIdValid(payload.mergedInto))
    case 'INTERVIEW_EVENT': return fields(payload, ['status', 'timing', 'reminderPending']) && oneOf(payload.status, ['PLANNED', 'COMPLETED', 'CANCELLED'])
      && oneOf(payload.timing, ['UPCOMING', 'DUE', 'IN_PROGRESS', 'PAST', 'CLOSED']) && typeof payload.reminderPending === 'boolean'
      && (payload.status === 'PLANNED' ? payload.timing !== 'CLOSED' : payload.timing === 'CLOSED' && !payload.reminderPending)
  }
}

// 计划契约复用同一套封闭来源校验；today 对计划仅作类型引用，避免运行时循环依赖。
export { contextValid as todayDayContextValid, stampValid as todaySourceStampValid, statusValid as todaySourceStatusValid, titleValid as todayTitleValid }

export function todayContinuationValid(value: unknown): value is TodayContinuation {
  if (!fields(value, ['route', 'item', 'action', 'attempt'])) return false
  if (oneOf(value.route, ['KNOWLEDGE', 'TRAINING', 'PREPARATION_TASKS', 'MEMORY_CARDS', 'SCHEDULE'])) return fields(value.item, ['item']) && memoryIdValid(value.item.item) && value.action === null && value.attempt === null
  if (value.route === 'REVIEW') return value.item === null && fields(value.action, ['action']) && memoryIdValid(value.action.action) && value.attempt === null
  return value.route === 'MEMORY_RECALL' && value.item === null && value.action === null && (value.attempt === null || fields(value.attempt, ['attempt']) && memoryIdValid(value.attempt.attempt))
}
export type TodayRouteLocation = { name: 'knowledge' | 'training' | 'preparation-tasks' | 'memory-cards' | 'memory-recall' | 'review' | 'schedule'; query: { item?: string; action?: string; attempt?: string } }
const routeNames: Record<TodayRoute, TodayRouteLocation['name']> = { KNOWLEDGE: 'knowledge', TRAINING: 'training', PREPARATION_TASKS: 'preparation-tasks', MEMORY_CARDS: 'memory-cards', MEMORY_RECALL: 'memory-recall', REVIEW: 'review', SCHEDULE: 'schedule' }
export function todayContinuationRoute(value: unknown): TodayRouteLocation | undefined {
  if (!todayContinuationValid(value)) return undefined
  // 导航只由白名单生成；来源响应不能提供 URL、额外查询参数或任何写命令。
  return { name: routeNames[value.route], query: value.item ? { item: value.item.item } : value.action ? { action: value.action.action } : value.attempt ? { attempt: value.attempt.attempt } : {} }
}
function continuationForSource(value: TodayContinuation, source: TodaySourceRef, status: TodaySourceStatus): boolean {
  const routes: Partial<Record<TodaySourceKind, TodayRoute>> = { KNOWLEDGE_DRAFT: 'KNOWLEDGE', TRAINING_SESSION: 'TRAINING', PREPARATION_TASK: 'PREPARATION_TASKS', REVIEW_ACTION: 'REVIEW', INTERVIEW_EVENT: 'SCHEDULE' }
  if (source.kind !== 'MEMORY_CARD') return value.route === routes[source.kind] && (source.kind === 'REVIEW_ACTION' ? value.action?.action === source.id : value.item?.item === source.id)
  if (status.kind !== 'MEMORY_CARD') return false
  return value.route === 'MEMORY_CARDS' ? value.item?.item === source.id : value.route === 'MEMORY_RECALL'
    && (value.attempt === null ? status.memory.openAttempt === null && status.memory.recommendation !== null : value.attempt.attempt === status.memory.openAttempt?.id)
}
export function todaySourceContinuationValid(value: unknown, source: TodaySourceRef, status: TodaySourceStatus): value is TodayContinuation {
  return todayContinuationValid(value) && continuationForSource(value, source, status)
}
function actionValid(value: unknown, source: TodaySourceRef, status: TodaySourceStatus): value is TodayAvailableAction {
  if (!fields(value, ['kind', 'continuation']) || !oneOf(value.kind, Object.keys(todayActionLabels) as TodayActionKind[]) || !todayContinuationValid(value.continuation)
    || !continuationForSource(value.continuation, source, status)) return false
  switch (value.kind) {
    case 'OPEN_SOURCE': return value.continuation.route !== 'MEMORY_RECALL'
    case 'CONTINUE_TRAINING': return status.kind === 'TRAINING_SESSION' && status.training.status !== 'ENDED'
    case 'CONTINUE_MEMORY_ATTEMPT': return status.kind === 'MEMORY_CARD' && status.memory.openAttempt !== null && value.continuation.attempt !== null
    case 'OPEN_MEMORY_QUEUE': return status.kind === 'MEMORY_CARD' && status.memory.openAttempt === null && status.memory.recommendation !== null && value.continuation.route === 'MEMORY_RECALL' && value.continuation.attempt === null
    case 'REVIEW_SOURCE': return status.kind === 'MEMORY_CARD' && value.continuation.route === 'MEMORY_CARDS'
    case 'OPEN_REVIEW_ACTION': return status.kind === 'REVIEW_ACTION'
    case 'OPEN_INTERVIEW': return status.kind === 'INTERVIEW_EVENT'
  }
}
function sourceTimeValid(value: unknown): value is TodaySourceTime {
  if (!fields(value, ['kind', 'instant', 'localDate', 'zoneId', 'zoneBasis'])) return false
  if (value.kind === 'NONE') return value.instant === null && value.localDate === null && value.zoneId === null && value.zoneBasis === null
  if (!todayZoneValid(value.zoneId)) return false
  return value.kind === 'INSTANT' ? timestamp(value.instant) && value.localDate === null && oneOf(value.zoneBasis, ['SOURCE', 'DISPLAY'])
    : value.kind === 'LOCAL_DATE' && value.instant === null && todayDateValid(value.localDate) && oneOf(value.zoneBasis, ['SOURCE', 'PLACEMENT'])
}
function eventWindowValid(value: unknown): value is TodayEventWindow {
  return fields(value, ['startAt', 'endAt', 'zoneId', 'remindAt']) && timestamp(value.startAt) && timestamp(value.endAt) && timestamp(value.remindAt)
    && todayZoneValid(value.zoneId) && memoryUtcMicros(value.startAt)! < memoryUtcMicros(value.endAt)! && beforeOrEqual(value.remindAt, value.startAt)
}
function targetsValid(value: unknown, kind: TodaySourceKind): value is TodayTargetRef[] {
  if (!Array.isArray(value) || !value.every(target => fields(target, ['itemId', 'versionId']) && memoryIdValid(target.itemId) && memoryIdValid(target.versionId))) return false
  if (kind === 'KNOWLEDGE_DRAFT' || kind === 'MEMORY_CARD') return value.length === 0
  return new Set(value.map(target => `${target.itemId}/${target.versionId}`)).size === value.length
}

function itemFieldsValid(value: unknown): value is TodayItem {
  if (!fields(value, ['source', 'stamp', 'title', 'section', 'sourceStatus', 'reasons', 'targetRefs', 'time', 'eventWindow', 'estimate', 'placement', 'continuation', 'pendingCount', 'availableActions', 'warnings'])
    || !todaySourceRefValid(value.source) || !stampValid(value.stamp, value.source.kind) || !statusValid(value.sourceStatus, value.stamp) || !todaySourceTitleValid(value.title, value.source.kind)
    || !(value.section === null || oneOf(value.section, todaySections)) || !orderedEnums(value.reasons, Object.keys(todayReasonLabels) as TodayReason[])
    || !orderedEnums(value.warnings, Object.keys(todayWarningLabels) as TodayWarning[]) || !targetsValid(value.targetRefs, value.source.kind) || !sourceTimeValid(value.time)
    || value.placement !== null && !todayPlacementValid(value.placement, value.source) || !todayContinuationValid(value.continuation) || !continuationForSource(value.continuation, value.source, value.sourceStatus)
    || !Array.isArray(value.availableActions) || !value.availableActions.every(action => actionValid(action, value.source as TodaySourceRef, value.sourceStatus as TodaySourceStatus))
    || new Set(value.availableActions.map(action => action.kind)).size !== value.availableActions.length || bytes(value) > 32768) return false
  const placement = value.placement as Placement | null
  if (!fields(value.estimate, ['minutes', 'basis']) || (value.source.kind === 'PREPARATION_TASK' ? value.estimate.basis !== 'SOURCE' || !integer(value.estimate.minutes, 1, 1440)
    : placement?.estimatedMinutes != null ? value.estimate.basis !== 'USER' || value.estimate.minutes !== placement.estimatedMinutes
      : value.estimate.basis !== 'UNKNOWN' || value.estimate.minutes !== null)) return false
  if (value.sourceStatus.kind === 'REVIEW_ACTION' ? !integer(value.pendingCount, 0, value.sourceStatus.action.trackingCount) || value.sourceStatus.action.mergedInto === value.source.id : value.pendingCount !== null) return false
  if (value.source.kind === 'INTERVIEW_EVENT' ? !eventWindowValid(value.eventWindow) : value.eventWindow !== null) return false
  if (value.section !== null && (!sectionSources[value.section].includes(value.source.kind) || value.reasons.length === 0)) return false
  if (value.source.kind === 'PREPARATION_TASK') return value.time.kind === 'NONE' || value.time.kind === 'LOCAL_DATE' && value.time.zoneBasis === 'SOURCE'
  if (value.source.kind === 'INTERVIEW_EVENT') return value.time.kind === 'INSTANT' && value.time.zoneBasis === 'SOURCE'
    && sameTime(value.time.instant, (value.eventWindow as TodayEventWindow).startAt) && value.time.zoneId === (value.eventWindow as TodayEventWindow).zoneId
  if (value.sourceStatus.kind === 'MEMORY_CARD') return value.sourceStatus.memory.scheduleStatus === null ? value.time.kind === 'NONE'
    : value.time.kind === 'INSTANT' && value.time.zoneBasis === 'DISPLAY'
  return placement?.plannedDate == null ? value.time.kind === 'NONE' : value.time.kind === 'LOCAL_DATE' && value.time.zoneBasis === 'PLACEMENT'
    && value.time.localDate === placement.plannedDate && value.time.zoneId === placement.planZoneId
}

function sourceSection(item: TodayItem, context: TodayDayContext, includeOverBudget: boolean): TodaySection | null {
  const status = item.sourceStatus
  switch (status.kind) {
    case 'KNOWLEDGE_DRAFT': return status.knowledge.status === 'ACTIVE' && status.knowledge.unpublishedDraft ? 'CONTINUE' : null
    case 'TRAINING_SESSION': return status.training.status === 'ENDED' ? null : 'CONTINUE'
    case 'PREPARATION_TASK': {
      if (status.task.status === 'IN_PROGRESS') return 'CONTINUE'
      if (status.task.status !== 'TODO') return null
      if (item.time.kind === 'NONE') return 'BACKLOG'
      if (item.time.kind !== 'LOCAL_DATE') return null
      const today = dateAt(context.asOf, item.time.zoneId)
      return item.time.localDate < today ? 'ATTENTION' : item.time.localDate === today ? 'TODAY' : null
    }
    case 'MEMORY_CARD': return status.memory.openAttempt ? 'CONTINUE' : status.memory.cardStatus !== 'ACTIVE' ? null : !status.memory.eligibility.basisReady ? 'ATTENTION'
      : status.memory.recommendation && (includeOverBudget || status.memory.recommendation.recommended) ? 'TODAY' : null
    case 'REVIEW_ACTION': return status.action.status === 'OPEN' && item.pendingCount! > 0 ? 'ATTENTION' : null
    case 'INTERVIEW_EVENT': {
      if (status.interview.status !== 'PLANNED') return null
      if (status.interview.reminderPending || status.interview.timing === 'PAST') return 'ATTENTION'
      const event = item.eventWindow!
      if (memoryUtcMicros(event.startAt)! < memoryUtcMicros(context.endAt)! && memoryUtcMicros(event.endAt)! > memoryUtcMicros(context.startAt)!) return 'TODAY'
      const days = (Date.parse(`${dateAt(event.startAt, context.zoneId)}T00:00:00Z`) - Date.parse(`${context.localDate}T00:00:00Z`)) / 86400000
      return days >= 1 && days <= 7 ? 'BACKLOG' : null
    }
  }
}
function protectedContinuation(item: TodayItem): boolean {
  const status = item.sourceStatus
  return status.kind === 'TRAINING_SESSION' ? status.training.status === 'RUNNING' || status.training.status === 'PAUSED'
    : status.kind === 'PREPARATION_TASK' ? status.task.status === 'IN_PROGRESS'
      : status.kind === 'MEMORY_CARD' ? status.memory.openAttempt !== null
        : status.kind === 'INTERVIEW_EVENT' && status.interview.status === 'PLANNED'
          && (status.interview.reminderPending || status.interview.timing === 'PAST' || status.interview.timing === 'IN_PROGRESS')
}
function placementExclusion(item: TodayItem, context: TodayDayContext): TodayExclusionReason | null {
  const placement = item.placement
  if (placement === null) return null
  if (placement.visibility === 'PAUSED') return 'PLACEMENT_PAUSED'
  if (placement.suppressedOn?.localDate === context.localDate && placement.suppressedOn.zoneId === context.zoneId) return 'PLACEMENT_SUPPRESSED_TODAY'
  return placement.plannedDate !== null && placement.plannedDate > dateAt(context.asOf, placement.planZoneId!) ? 'PLACEMENT_FUTURE' : null
}
function classifiedSection(item: TodayItem, context: TodayDayContext, includeOverBudget: boolean): TodaySection | null {
  const source = sourceSection(item, context, includeOverBudget)
  // 先守住源域资格；偏好不能复活归档/结束事项，也不能隐藏正在进行的续作与必要面试提醒。
  if (source === null || protectedContinuation(item)) return source
  if (placementExclusion(item, context) !== null) return null
  const placement = item.placement
  if (placement?.plannedDate != null) return placement.plannedDate < dateAt(context.asOf, placement.planZoneId!) ? 'ATTENTION' : 'TODAY'
  return source
}
function reasonsValid(item: TodayItem, context: TodayDayContext): boolean {
  const status = item.sourceStatus
  return item.reasons.every(reason => {
    if (reason === 'PLACEMENT_TODAY' || reason === 'PLACEMENT_OVERDUE') return item.placement?.plannedDate != null
      && (reason === 'PLACEMENT_TODAY' ? item.placement.plannedDate === dateAt(context.asOf, item.placement.planZoneId!) : item.placement.plannedDate < dateAt(context.asOf, item.placement.planZoneId!))
    if (reason === 'UNPUBLISHED_DRAFT') return status.kind === 'KNOWLEDGE_DRAFT' && status.knowledge.unpublishedDraft
    if (reason === 'SAVED_ANSWER_DRAFT') return status.kind === 'TRAINING_SESSION' && status.training.savedDraftCount > 0
    if (reason.startsWith('TRAINING_')) return status.kind === 'TRAINING_SESSION' && reason === `TRAINING_${status.training.status}`
    if (reason.startsWith('TASK_')) {
      if (status.kind !== 'PREPARATION_TASK') return false
      if (reason === 'TASK_IN_PROGRESS') return status.task.status === 'IN_PROGRESS'
      if (reason === 'TASK_UNSCHEDULED') return item.time.kind === 'NONE'
      return item.time.kind === 'LOCAL_DATE' && (reason === 'TASK_OVERDUE' ? item.time.localDate < dateAt(context.asOf, item.time.zoneId) : item.time.localDate === dateAt(context.asOf, item.time.zoneId))
    }
    if (reason.startsWith('MEMORY_')) {
      if (status.kind !== 'MEMORY_CARD') return false
      if (reason === 'MEMORY_OPEN_ATTEMPT') return status.memory.openAttempt !== null
      if (reason === 'MEMORY_SOURCE_REVIEW') return !status.memory.eligibility.basisReady
      if (reason === 'MEMORY_NOT_RECOMMENDED') return status.memory.recommendation?.recommended === false
      if (reason === 'MEMORY_BUDGET_CONFIRMATION') return status.memory.recommendation?.requiresBudgetConfirmation === true
      return item.time.kind === 'INSTANT' && beforeOrEqual(item.time.instant, context.asOf)
    }
    if (reason === 'ACTION_PENDING') return status.kind === 'REVIEW_ACTION' && status.action.status === 'OPEN' && item.pendingCount! > 0
    if (status.kind !== 'INTERVIEW_EVENT') return false
    if (reason === 'INTERVIEW_REMINDER') return status.interview.reminderPending
    if (reason === 'INTERVIEW_IN_PROGRESS') return status.interview.timing === 'IN_PROGRESS'
    if (reason === 'INTERVIEW_TIME_PASSED') return status.interview.timing === 'PAST'
    if (reason === 'INTERVIEW_TODAY') return memoryUtcMicros(item.eventWindow!.startAt)! < memoryUtcMicros(context.endAt)! && memoryUtcMicros(item.eventWindow!.endAt)! > memoryUtcMicros(context.startAt)!
    return beforeOrEqual(context.asOf, item.eventWindow!.startAt)
  })
}
function itemValid(value: unknown, context: TodayDayContext, targetId: string | null, includeOverBudget: boolean): value is TodayItem {
  if (!itemFieldsValid(value) || (targetId !== null && !value.targetRefs.some(target => target.itemId === targetId)) || !reasonsValid(value, context)) return false
  const status = value.sourceStatus
  // 读取时钟可能早于数据库建立一致性快照；并发提交的记录时间不能以 serverTime 为上界拒绝。
  if (status.kind === 'MEMORY_CARD') {
    if (value.time.kind === 'INSTANT' && value.time.zoneId !== context.zoneId) return false
    if (status.memory.recommendation && (value.time.kind !== 'INSTANT' || !beforeOrEqual(value.time.instant, context.asOf))) return false
  }
  if (status.kind === 'INTERVIEW_EVENT') {
    const event = value.eventWindow!, now = memoryUtcMicros(context.asOf)!
    const timing = status.interview.status !== 'PLANNED' ? 'CLOSED' : now >= memoryUtcMicros(event.endAt)! ? 'PAST'
      : now >= memoryUtcMicros(event.startAt)! ? 'IN_PROGRESS' : now < memoryUtcMicros(event.remindAt)! ? 'UPCOMING' : 'DUE'
    if (status.interview.timing !== timing || (status.interview.reminderPending && now < memoryUtcMicros(event.remindAt)!)) return false
  }
  const sourceZoneDiffers = value.time.kind !== 'NONE' && value.time.zoneBasis !== 'DISPLAY' && value.time.zoneId !== context.zoneId
  if (value.warnings.includes('SOURCE_ZONE_DIFFERS') !== sourceZoneDiffers) return false
  if (value.warnings.includes('ARCHIVED_CONCEPT_WITH_OTHER_SOURCE') !== (status.kind === 'MEMORY_CARD' && status.memory.eligibility.warnings.includes('ARCHIVED_CONCEPT_WITH_OTHER_SOURCE'))) return false
  if (value.warnings.includes('MEMORY_QUEUE_RECHECK_REQUIRED') && status.kind !== 'MEMORY_CARD') return false
  if (value.warnings.includes('PLACEMENT_CONTINUATION_RETAINED') !== (protectedContinuation(value) && placementExclusion(value, context) !== null)) return false
  return value.section === classifiedSection(value, context, includeOverBudget)
}

function uniqueItems(items: TodayItem[]): boolean { return new Set(items.map(item => todaySourceKey(item.source))).size === items.length }
function pageOrder(items: TodayItem[], kind: TodaySourceKind, section: TodaySection | null): boolean {
  const compare = (left: TodayItem, right: TodayItem): number | undefined => {
    const id = left.source.id < right.source.id ? -1 : left.source.id > right.source.id ? 1 : 0
    const time = (a: string, b: string) => memoryUtcMicros(a)! < memoryUtcMicros(b)! ? -1 : memoryUtcMicros(a)! > memoryUtcMicros(b)! ? 1 : 0
    const order = (left.placement?.orderValue ?? 500000) - (right.placement?.orderValue ?? 500000)
    const date = (a: string | null, b: string | null) => a === b ? 0 : a === null ? 1 : b === null ? -1 : a < b ? -1 : 1
    // 日历先守住实际日期/Instant，展示顺序只在同一时间键内生效。
    if (section === null) return (left.time.kind === 'LOCAL_DATE' && right.time.kind === 'LOCAL_DATE' ? date(left.time.localDate, right.time.localDate)
      : left.time.kind === 'INSTANT' && right.time.kind === 'INSTANT' ? time(left.time.instant, right.time.instant) : 0) || order || id
    if (order !== 0) return order
    if ((section === 'ATTENTION' || section === 'TODAY') && ['KNOWLEDGE_DRAFT', 'TRAINING_SESSION', 'REVIEW_ACTION'].includes(kind)) {
      const dates = date(left.placement?.plannedDate ?? null, right.placement?.plannedDate ?? null)
      if (dates !== 0) return dates
    }
    if (kind === 'KNOWLEDGE_DRAFT') return time(right.sourceStatus.knowledge!.draftSavedAt, left.sourceStatus.knowledge!.draftSavedAt) || id
    if (kind === 'TRAINING_SESSION') return time(right.sourceStatus.training!.lastActivityAt, left.sourceStatus.training!.lastActivityAt) || id
    if (kind === 'PREPARATION_TASK' && (section === 'ATTENTION' || section === 'TODAY')) return date(left.time.localDate, right.time.localDate) || id
    if (kind === 'INTERVIEW_EVENT') return time(section === 'ATTENTION' ? left.eventWindow!.remindAt : left.eventWindow!.startAt, section === 'ATTENTION' ? right.eventWindow!.remindAt : right.eventWindow!.startAt) || id
    if (kind === 'MEMORY_CARD' && section === 'CONTINUE') {
      const a = left.sourceStatus.memory!.openAttempt!, b = right.sourceStatus.memory!.openAttempt!
      return time(b.startedAt, a.startedAt) || (b.id < a.id ? -1 : b.id > a.id ? 1 : 0)
    }
    if (kind === 'MEMORY_CARD' && section === 'TODAY') {
      const rank = (item: TodayItem) => item.sourceStatus.memory!.phase === 'REVIEW' ? 1 : 0
      return rank(left) - rank(right) || time(left.time.instant!, right.time.instant!) || id
    }
    // 这些来源的完整排序键未出现在 DTO 中；不靠来源 ID 虚构 updatedAt 排序证据。
    return undefined
  }
  return items.every((item, index) => index === 0 || (compare(items[index - 1]!, item) ?? -1) < 0)
}
function queueInfoValid(value: unknown, context: TodayDayContext, includeOverBudget: boolean): value is TodayMemoryQueueInfo {
  return fields(value, ['budget', 'eligibleDueCards', 'recommendedCards', 'nextDueAt', 'includeOverBudget']) && memoryDailyBudgetValid(value.budget, context.serverTime)
    && todayCountValid(value.eligibleDueCards) && value.eligibleDueCards.mode === 'EXACT' && todayCountValid(value.recommendedCards) && value.recommendedCards.mode === 'EXACT'
    && value.recommendedCards.value <= value.eligibleDueCards.value && nullableTime(value.nextDueAt) && value.includeOverBudget === includeOverBudget
}
function queueItemsValid(items: TodayItem[], queue: TodayMemoryQueueInfo | null): boolean {
  return items.every(item => {
    if (item.sourceStatus.kind !== 'MEMORY_CARD' || item.sourceStatus.memory.recommendation === null) return true
    if (queue === null || item.stamp.kind !== 'MEMORY_CARD' || item.stamp.memory.settingsRevision !== queue.budget.settingsRevision) return false
    const recommendation = item.sourceStatus.memory.recommendation
    const used = recommendation.budgetKind === 'NEW' ? queue.budget.newCards : queue.budget.reviewCards
    const limit = recommendation.budgetKind === 'NEW' ? queue.budget.newLimit : queue.budget.reviewLimit
    return recommendation.requiresBudgetConfirmation === (!recommendation.countedToday && used >= limit)
  })
}
function scopeKnownEmpty(kind: TodaySourceKind, section: TodaySection | null, targetId: string | null): boolean {
  return (targetId !== null && (kind === 'KNOWLEDGE_DRAFT' || kind === 'MEMORY_CARD')) || (section !== null && !sectionSources[section].includes(kind))
}
function emptyReady(capability: TodayCapability, count: TodayCount, scanLimited: boolean, items: TodayItem[], cursor: string | null): boolean {
  return capability.status === 'READY' && count.mode === 'EXACT' && count.value === 0 && !scanLimited && items.length === 0 && cursor === null
}
function expectedFilter(context: TodayDayContext, filter: TodayFilter, query?: TodayOverviewQuery): boolean {
  return query === undefined || context.zoneId === query.zoneId && filter.targetId === (query.targetId ?? null) && filter.includeOverBudget === (query.includeOverBudget ?? false)
}

export function todayOverviewValid(value: unknown, expected?: TodayOverviewQuery): value is TodayOverview {
  return check(() => {
    if (!fields(value, ['context', 'filter', 'capabilities', 'sections', 'memoryQueue']) || !contextValid(value.context) || !filterValid(value.filter)
      || !expectedFilter(value.context, value.filter, expected) || !Array.isArray(value.capabilities) || value.capabilities.length !== 24 || !Array.isArray(value.sections) || value.sections.length !== 4 || bytes(value) > 1048576) return false
    const context = value.context, filter = value.filter
    if (value.memoryQueue !== null && (filter.targetId !== null || !queueInfoValid(value.memoryQueue, context, filter.includeOverBudget))) return false
    const capabilities: TodaySourceSectionCapability[] = []
    for (const item of value.capabilities) {
      if (!fields(item, ['kind', 'section', 'capability', 'count', 'scanLimited']) || !oneOf(item.kind, todaySourceKinds) || !oneOf(item.section, todaySections)
        || !capabilityValid(item.capability) || !todayCountValid(item.count) || typeof item.scanLimited !== 'boolean') return false
      if (item.capability.status === 'NOT_CONFIGURED' && (item.kind !== 'MEMORY_CARD' || item.section !== 'TODAY')) return false
      if (scopeKnownEmpty(item.kind, item.section, filter.targetId) && !emptyReady(item.capability, item.count, item.scanLimited, [], null)) return false
      if (item.capability.status === 'READY' ? item.count.mode === 'UNAVAILABLE' : item.count.mode !== 'UNAVAILABLE' || item.scanLimited) return false
      capabilities.push(item as TodaySourceSectionCapability)
    }
    if (new Set(capabilities.map(item => `${item.kind}/${item.section}`)).size !== 24) return false
    const all: TodayItem[] = []
    for (const preview of value.sections) {
      if (!fields(preview, ['section', 'items']) || !oneOf(preview.section, todaySections) || !Array.isArray(preview.items) || preview.items.length > 5
        || !preview.items.every(item => itemValid(item, context, filter.targetId, filter.includeOverBudget) && item.section === preview.section)) return false
      const items = preview.items as TodayItem[], section = preview.section
      for (const kind of todaySourceKinds) {
        const subset = items.filter(item => item.source.kind === kind), capability = capabilities.find(item => item.kind === kind && item.section === section)!
        if (capability.capability.status !== 'READY' ? subset.length !== 0 : capability.count.value === null || subset.length > capability.count.value) return false
        if (!pageOrder(subset, kind, section)) return false
      }
      const sequence: TodayItem[] = [], orders = [...new Set(items.map(item => item.placement?.orderValue ?? 500000))].sort((a, b) => a - b)
      for (const order of orders) {
        const queues = sectionSources[section].map(kind => items.filter(item => item.source.kind === kind && (item.placement?.orderValue ?? 500000) === order))
        for (let index = 0; index < 5; index++) for (const queue of queues) if (queue[index]) sequence.push(queue[index]!)
      }
      if (sequence.some((item, index) => item !== items[index])) return false
      all.push(...items)
    }
    if (new Set(value.sections.map(section => section.section)).size !== 4 || !uniqueItems(all) || !queueItemsValid(all, value.memoryQueue as TodayMemoryQueueInfo | null)) return false
    const due = capabilities.find(item => item.kind === 'MEMORY_CARD' && item.section === 'TODAY')!
    if (value.memoryQueue === null) return !all.some(item => item.sourceStatus.memory?.recommendation !== null && item.sourceStatus.kind === 'MEMORY_CARD')
    const queue = value.memoryQueue as TodayMemoryQueueInfo, count = filter.includeOverBudget ? queue.eligibleDueCards : queue.recommendedCards
    // 今日计数已应用偏好过滤；原 B 队列仍报告完整资格与推荐总数，不能再要求二者相等。
    return due.capability.status === 'READY' && due.count.mode !== 'UNAVAILABLE' && due.count.value <= count.value!
  })
}

export function todayPageValid(value: unknown, expected?: TodayItemsQuery): value is TodayPage {
  return check(() => {
    if (!fields(value, ['context', 'filter', 'kind', 'section', 'capability', 'count', 'scanLimited', 'items', 'nextCursor', 'memoryQueue']) || !contextValid(value.context)
      || !filterValid(value.filter) || !oneOf(value.kind, todaySourceKinds) || !oneOf(value.section, todaySections) || !capabilityValid(value.capability) || !todayCountValid(value.count)
      || typeof value.scanLimited !== 'boolean' || !Array.isArray(value.items) || value.items.length > (expected?.limit ?? 50) || !cursorValid(value.nextCursor) || bytes(value) > 1048576
      || !expectedFilter(value.context, value.filter, expected) || expected !== undefined && (value.kind !== expected.kind || value.section !== expected.section || value.nextCursor !== null && value.nextCursor === expected.cursor)
      || value.filter.includeOverBudget && (value.kind !== 'MEMORY_CARD' || value.section !== 'TODAY')) return false
    const context = value.context, filter = value.filter, kind = value.kind, section = value.section
    if (!value.items.every(item => itemValid(item, context, filter.targetId, filter.includeOverBudget) && item.source.kind === kind && item.section === section)) return false
    const items = value.items as TodayItem[]
    if (!uniqueItems(items) || !pageOrder(items, kind, section) || !availableCount(value.capability, value.count, value.scanLimited, items.length, value.nextCursor)) return false
    if (value.capability.status === 'NOT_CONFIGURED' && (kind !== 'MEMORY_CARD' || section !== 'TODAY')) return false
    if (scopeKnownEmpty(kind, section, filter.targetId) && !emptyReady(value.capability, value.count, value.scanLimited, items, value.nextCursor)) return false
    if (value.memoryQueue === null) return queueItemsValid(items, null)
    if (kind !== 'MEMORY_CARD' || section !== 'TODAY' || filter.targetId !== null || value.capability.status !== 'READY'
      || !queueInfoValid(value.memoryQueue, context, filter.includeOverBudget) || !queueItemsValid(items, value.memoryQueue)) return false
    const count = filter.includeOverBudget ? value.memoryQueue.eligibleDueCards : value.memoryQueue.recommendedCards
    return value.count.mode !== 'UNAVAILABLE' && value.count.value <= count.value!
  })
}
function calendarWindowValid(value: unknown, zoneId: string): value is TodayCalendarWindow {
  return fields(value, ['from', 'to', 'startAt', 'endAt']) && !todayDateRangeProblem(value.from, value.to) && timestamp(value.startAt) && timestamp(value.endAt)
    && memoryUtcMicros(value.startAt)! < memoryUtcMicros(value.endAt)!
    && dayBoundaryValid(value.startAt, value.from as string, zoneId) && nextDate(value.to as string) !== undefined && dayBoundaryValid(value.endAt, nextDate(value.to as string)!, zoneId)
}
export function todayCalendarPageValid(value: unknown, expected?: TodayCalendarQuery): value is TodayCalendarPage {
  return check(() => {
    if (!fields(value, ['context', 'window', 'kind', 'targetId', 'capability', 'count', 'scanLimited', 'items', 'nextCursor']) || !contextValid(value.context) || !calendarWindowValid(value.window, value.context.zoneId)
      || !oneOf(value.kind, todaySourceKinds) || !nullableId(value.targetId) || !capabilityValid(value.capability) || !todayCountValid(value.count) || typeof value.scanLimited !== 'boolean'
      || !Array.isArray(value.items) || value.items.length > (expected?.limit ?? 50) || !cursorValid(value.nextCursor) || bytes(value) > 1048576
      || value.capability.status === 'NOT_CONFIGURED' || expected !== undefined && (value.context.zoneId !== expected.zoneId || value.kind !== expected.kind || value.targetId !== (expected.targetId ?? null)
        || value.window.from !== expected.from || value.window.to !== expected.to || value.nextCursor !== null && value.nextCursor === expected.cursor)) return false
    const context = value.context, targetId = value.targetId, kind = value.kind, window = value.window
    // 日历读取真实排程或已保存的偏好日期；暂停/抑制不抹掉日历事实，回忆资格仍取实际队列。
    if (!value.items.every(item => itemValid(item, context, targetId, false) && item.source.kind === kind)) return false
    const items = value.items as TodayItem[]
    if (!uniqueItems(items) || !pageOrder(items, kind, null) || !availableCount(value.capability, value.count, value.scanLimited, items.length, value.nextCursor)) return false
    if (scopeKnownEmpty(kind, null, targetId)) return emptyReady(value.capability, value.count, value.scanLimited, items, value.nextCursor)
    return items.every(item => ['PREPARATION_TASK', 'KNOWLEDGE_DRAFT', 'TRAINING_SESSION', 'REVIEW_ACTION'].includes(kind) ? item.time.kind === 'LOCAL_DATE' && item.time.localDate >= window.from && item.time.localDate <= window.to
      : kind === 'INTERVIEW_EVENT' ? memoryUtcMicros(item.eventWindow!.startAt)! < memoryUtcMicros(window.endAt)! && memoryUtcMicros(item.eventWindow!.endAt)! > memoryUtcMicros(window.startAt)!
        : item.sourceStatus.memory?.cardStatus === 'ACTIVE' && item.time.kind === 'INSTANT' && beforeOrEqual(window.startAt, item.time.instant) && memoryUtcMicros(item.time.instant)! < memoryUtcMicros(window.endAt)!)
  })
}
function exclusionValid(reason: TodayExclusionReason, item: TodayItem, context: TodayDayContext): boolean {
  const status = item.sourceStatus
  let source: TodayExclusionReason | null
  switch (status.kind) {
    case 'KNOWLEDGE_DRAFT': source = status.knowledge.status === 'ARCHIVED' ? 'KNOWLEDGE_ARCHIVED' : !status.knowledge.unpublishedDraft ? 'NO_UNPUBLISHED_DRAFT' : null; break
    case 'TRAINING_SESSION': source = status.training.status === 'ENDED' ? 'TRAINING_ENDED' : null; break
    case 'PREPARATION_TASK': source = status.task.status === 'PAUSED' ? 'TASK_PAUSED' : status.task.status === 'DONE' ? 'TASK_DONE' : status.task.status === 'CANCELLED' ? 'TASK_CANCELLED'
      : status.task.status === 'TODO' && item.time.kind === 'LOCAL_DATE' && item.time.localDate > dateAt(context.asOf, item.time.zoneId) ? 'TASK_FUTURE' : null; break
    case 'MEMORY_CARD': source = status.memory.cardStatus === 'ARCHIVED' ? 'MEMORY_BLOCKED' : status.memory.openAttempt !== null || !status.memory.eligibility.basisReady ? null
      : status.memory.scheduleStatus === null ? 'MEMORY_NO_SCHEDULE' : status.memory.scheduleStatus === 'PAUSED' ? 'MEMORY_PAUSED'
        : item.time.kind === 'INSTANT' && memoryUtcMicros(item.time.instant)! > memoryUtcMicros(context.asOf)! ? 'MEMORY_FUTURE_DUE'
          : status.memory.recommendation?.recommended !== true ? 'MEMORY_NOT_RECOMMENDED' : null; break
    case 'REVIEW_ACTION': source = status.action.status === 'MERGED' ? 'ACTION_MERGED' : item.pendingCount === 0 ? 'ACTION_NO_PENDING' : null; break
    case 'INTERVIEW_EVENT': source = status.interview.status !== 'PLANNED' ? 'INTERVIEW_CLOSED' : sourceSection(item, context, false) === null ? 'INTERVIEW_OUTSIDE_WINDOW' : null; break
  }
  return source !== null ? reason === source : reason === placementExclusion(item, context)
}
export function todaySourceDetailValid(value: unknown, source?: TodaySourceRef, expected?: TodaySourceQuery): value is TodaySourceDetail {
  return check(() => {
    if (!fields(value, ['context', 'source', 'capability', 'item', 'exclusionReason']) || !contextValid(value.context) || !todaySourceRefValid(value.source)
      || !capabilityValid(value.capability) || bytes(value) > 1048576 || source !== undefined && todaySourceKey(value.source) !== todaySourceKey(source)
      || expected !== undefined && value.context.zoneId !== expected.zoneId) return false
    if (value.capability.status !== 'READY') return value.item === null && value.exclusionReason === null && (value.capability.status !== 'NOT_CONFIGURED' || value.source.kind === 'MEMORY_CARD')
    if (!itemValid(value.item, value.context, null, false) || todaySourceKey(value.item.source) !== todaySourceKey(value.source)) return false
    return value.item.section !== null ? value.exclusionReason === null : oneOf(value.exclusionReason, Object.keys(todayExclusionReasonLabels) as TodayExclusionReason[]) && exclusionValid(value.exclusionReason, value.item, value.context)
  })
}

function queryFields(value: unknown, required: readonly string[], optional: readonly string[]): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value) && required.every(name => Object.hasOwn(value, name))
    && Object.keys(value).every(name => required.includes(name) || optional.includes(name)))
}
function baseQueryValid(value: Record<string, unknown>): boolean { return todayZoneValid(value.zoneId) && (value.targetId === undefined || nullableId(value.targetId)) }
function listQueryValid(value: Record<string, unknown>): boolean { return (value.cursor === undefined || cursorValid(value.cursor)) && (value.limit === undefined || integer(value.limit, 1, 50)) }
function invalidQuery(): never { throw new ApiError(400, 'INVALID_REQUEST', '今日筛选参数不符合约定，请核对时区、来源、区段与日期。') }
function queryPath(path: string, value: Record<string, unknown>): string {
  const parameters = new URLSearchParams()
  for (const [key, item] of Object.entries(value)) if (item !== undefined && item !== null) parameters.set(key, String(item))
  return `${path}?${parameters.toString()}`
}
export function todayOverviewPath(query: TodayOverviewQuery): string {
  if (!queryFields(query, ['zoneId'], ['targetId', 'includeOverBudget']) || !baseQueryValid(query) || query.includeOverBudget !== undefined && typeof query.includeOverBudget !== 'boolean') invalidQuery()
  return queryPath('/today/overview', query)
}
export function todayItemsPath(query: TodayItemsQuery): string {
  if (!queryFields(query, ['zoneId', 'kind', 'section'], ['targetId', 'includeOverBudget', 'cursor', 'limit']) || !baseQueryValid(query) || !listQueryValid(query)
    || !oneOf(query.kind, todaySourceKinds) || !oneOf(query.section, todaySections) || query.includeOverBudget !== undefined && typeof query.includeOverBudget !== 'boolean'
    || query.includeOverBudget === true && (query.kind !== 'MEMORY_CARD' || query.section !== 'TODAY')) invalidQuery()
  return queryPath('/today/items', query)
}
export function todayCalendarPath(query: TodayCalendarQuery): string {
  if (!queryFields(query, ['from', 'to', 'zoneId', 'kind'], ['targetId', 'cursor', 'limit']) || !baseQueryValid(query) || !listQueryValid(query)
    || !oneOf(query.kind, todaySourceKinds) || todayDateRangeProblem(query.from, query.to)) invalidQuery()
  return queryPath('/today/calendar', query)
}
export function todaySourcePath(source: TodaySourceRef, query: TodaySourceQuery): string {
  if (!todaySourceRefValid(source) || !queryFields(query, ['zoneId'], []) || !todayZoneValid(query.zoneId)) invalidQuery()
  return queryPath(`/today/sources/${source.kind}/${source.id}`, query)
}
export function todayErrorText(error: unknown): string {
  if (!(error instanceof ApiError)) return errorText(error)
  if (error.code === 'TODAY_CURSOR_CHANGED') return '日期、时区、来源条件、安排顺序或分页有效期已变化。筛选保留，请从首页重新读取。'
  if (error.code === 'TODAY_SOURCE_NOT_FOUND') return '这个来源不存在或当前不可访问，请返回原工作区核对。'
  if (error.code === 'TODAY_RESPONSE_INVALID') return '本次今日数据与来源、筛选或计数契约不一致，请重新读取。'
  if (error.code === 'TODAY_LIMIT') return '本次读取超过允许范围，请缩小筛选后重试。'
  if (error.code === 'PLACEMENT_EXISTS') return '这个来源已有安排偏好。请保留输入，读取已有安排后再明确修改。'
  if (error.code === 'PLACEMENT_NOT_FOUND') return '安排偏好不存在或当前不可访问，请重新读取来源。'
  if (error.code === 'PLACEMENT_REVISION_CONFLICT') return '安排已被其他操作修改。请保留输入，核对最新安排后再提交。'
  if (error.code === 'DAY_BUDGET_EXISTS') return '该日期与时区已有分钟预算，请读取当前预算后再修改。'
  if (error.code === 'DAY_BUDGET_NOT_FOUND') return '该日期与时区尚未保存分钟预算，请先明确创建。'
  if (error.code === 'DAY_BUDGET_REVISION_CONFLICT') return '分钟预算已变化。请保留输入，核对最新预算并重新预览。'
  if (error.code === 'PLAN_SOURCE_CHANGED') return '来源内容或当前资格已变化。请保留输入，重新读取完整来源修订。'
  if (error.code === 'PLAN_PREVIEW_CHANGED') return '候选、预算、来源或提议已变化，请重新预览并明确选择后采用。'
  if (error.code === 'PLAN_DAY_CHANGED') return '所选时区的当前日期已变化，请刷新日期并重新预览；输入保留。'
  if (error.code === 'DEPENDENCY_NOT_READY') return '本次操作需要的来源查询尚未接入，请保留输入后稍后重试。'
  if (error.code === 'TODAY_RESPONSE_LIMIT') return '本次摘要超过响应范围，请缩小候选或分页后重新读取。'
  return errorText(error)
}
