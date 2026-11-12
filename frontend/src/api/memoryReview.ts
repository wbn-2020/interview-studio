import { ApiError, errorText } from './client'
import type { Schema } from './client'
import { memoryIdValid } from './memory'

export type MemoryTimeMode = Schema['MemoryTimeMode']
export type MemoryResumePolicy = Schema['MemoryResumePolicy']
export type MemoryScheduleStatus = Schema['MemoryScheduleStatus']
export type MemoryPauseReason = Schema['MemoryPauseReason']
export type MemoryFsrsPhase = Schema['MemoryFsrsPhase']
export type MemoryReviewSourceState = Schema['MemoryReviewSourceState']
export type MemoryActorKind = Schema['MemoryActorKind']
export type MemorySourceHistoryKind = Schema['MemorySourceHistoryKind']
export type MemoryImpactReason = Schema['MemoryImpactReason']
export type MemoryScheduleChangeKind = Schema['MemoryScheduleChangeKind']
export type MemoryBlockReason = Schema['MemoryBlockReason']
export type MemoryWarning = Schema['MemoryWarning']
export type MemorySourceReview = Schema['MemorySourceReview']
export type MemoryEnableSchedule = Schema['MemoryEnableSchedule']
export type MemoryDeferSchedule = Schema['MemoryDeferSchedule']
export type MemoryPauseSchedule = Schema['MemoryPauseSchedule']
export type MemoryResumeSchedule = Schema['MemoryResumeSchedule']
export type MemoryCreateSettings = Schema['MemoryCreateSettings']
export type MemorySaveSettings = Schema['MemorySaveSettings']
export type MemoryActor = Schema['MemoryActor']
export type MemoryGate = Schema['MemoryGate']
export type MemorySourceSnapshot = Schema['MemorySourceSnapshot']
export type MemoryEligibility = Schema['MemoryEligibility']
export type MemoryRecallState = Schema['MemoryRecallState']
export type MemorySourceChange = Schema['MemorySourceChange']
export type MemoryImpact = Schema['MemoryImpact']
export type MemorySourceReviewDecision = Schema['MemorySourceReviewDecision']
export type MemorySourceHistoryEntry = Schema['MemorySourceHistoryEntry']
export type MemoryImpactPage = Schema['MemoryImpactPage']
export type MemoryFsrsState = Schema['MemoryFsrsState']
export type MemorySchedule = Schema['MemorySchedule']
export type MemoryScheduleChange = Schema['MemoryScheduleChange']
export type MemoryScheduleHistoryPage = Schema['MemoryScheduleHistoryPage']
export type MemoryReviewSettings = Schema['MemorySettings']

export const memoryScheduleStatusLabels: Record<MemoryScheduleStatus, string> = { ACTIVE: '排程中', PAUSED: '已暂停' }
export const memoryPauseReasonLabels: Record<MemoryPauseReason, string> = { MANUAL: '人工暂停', SOURCE_REVIEW: '来源待复核', CARD_ARCHIVED: '卡片已归档' }
export const memoryReviewSourceStateLabels: Record<MemoryReviewSourceState, string> = { CURRENT: '当前版本', SUPERSEDED: '来源已有新版本', ARCHIVED: '来源已归档', UNAVAILABLE: '来源不可用' }
export const memoryBlockReasonLabels: Record<MemoryBlockReason, string> = {
  CARD_ARCHIVED: '卡片已归档', GATE_MISSING: '缺少来源观察基线', REVIEW_REQUIRED: '需要明确复核来源', SOURCE_UNAVAILABLE: '来源不可用',
  SOURCE_BODY_CHANGED: '来源正文已变化', SOURCE_UPSTREAM_REVIEW_REQUIRED: '上游采用来源待复核',
  ARCHIVED_NOTE_SOURCE: '作为依据的笔记已归档', NO_VALID_SOURCE: '没有当前有效依据',
}
export const memoryImpactReasonLabels: Record<MemoryImpactReason, string> = {
  BASELINE_REVIEW_REQUIRED: '既有卡片首次复核', SOURCE_BODY_CHANGED: '来源正文变化', SOURCE_ARCHIVED: '来源归档', SOURCE_RESTORED: '来源恢复',
  SOURCE_UPSTREAM_CHANGED: '上游采用来源待复核',
  CARD_BASIS_CHANGED: '卡片依据变化', CARD_ARCHIVED: '卡片归档', CARD_RESTORED: '卡片恢复',
}
export const memoryScheduleChangeKindLabels: Record<MemoryScheduleChangeKind, string> = {
  ENABLE: '首次启用', DEFER: '延期', PAUSE: '人工暂停', RESUME: '沿用原周期恢复', RESET: '作为新卡恢复', SOURCE_PAUSE: '来源变化暂停', CARD_PAUSE: '卡片归档暂停', RATE: '常规评分',
}
export const memoryReviewErrorLabels: Readonly<Record<string, string>> = {
  REVISION_CONFLICT: '卡片修订已变化，请保留输入并重新核对卡片。',
  MEMORY_GATE_CONFLICT: '来源门禁修订已变化，请重新核对来源。',
  MEMORY_SCHEDULE_CONFLICT: '排程修订已变化，请重新核对排程。',
  MEMORY_SETTINGS_CONFLICT: '回忆设置已变化，请对照服务器设置后再保存。',
  MEMORY_CARD_NOT_FOUND: '这张回忆卡不存在或不可访问。', MEMORY_SCHEDULE_NOT_FOUND: '这张卡片尚无排程。',
  MEMORY_GATE_UNAVAILABLE: '缺少来源观察基线，暂时无法操作。', MEMORY_SETTINGS_REQUIRED: '请先明确保存回忆时区和每日建议数量。',
  MEMORY_REVIEW_REQUIRED: '请先核对来源与卡片参考，再明确确认本次复核。', MEMORY_SCHEDULE_PAUSED: '排程已经暂停，请通过恢复操作调整。',
  MEMORY_TIME_CONFLICT: '指定时间已不满足未来条件，请保留输入并重新选择。',
  SOURCE_UNAVAILABLE: '来源不存在或不可访问，请核对卡片的确切来源版本。', SOURCE_CHANGED: '来源已变化，请重新核对。',
  KNOWLEDGE_REVALIDATION_REQUIRED: '来源知识的历史采用来源仍待核对，请先在知识详情处理来源更正，再重新复核卡片。',
  MEMORY_LIMIT: '内容或记录超过允许范围，请缩减后再提交。', REVISION_LIMIT: '修订或历史序号已达到上限。', INVALID_REQUEST: '请求字段不符合要求，请核对后重试。',
}
const conflictSubjects: Readonly<Record<string, string>> = { REVISION_CONFLICT: '卡片', MEMORY_GATE_CONFLICT: '来源门禁', MEMORY_SCHEDULE_CONFLICT: '排程', MEMORY_SETTINGS_CONFLICT: '设置' }
export function memoryReviewErrorText(error: unknown): string {
  if (!(error instanceof ApiError) || !Object.hasOwn(memoryReviewErrorLabels, error.code)) return errorText(error)
  const subject = conflictSubjects[error.code]
  return memoryReviewErrorLabels[error.code]! + (subject && revision(error.currentRevision) ? ` 当前${subject}修订为 ${error.currentRevision}。` : '')
}

const MAX = Number.MAX_SAFE_INTEGER
const ALGORITHM = 'java-fsrs:1.0.0@c561ee6c56239b621a0a45b16a6c2e688441eae5'
const PARAMETERS = 'fsrs-java-1.0.0-default-unfuzzed-1'
const blockOrder: MemoryBlockReason[] = ['CARD_ARCHIVED', 'GATE_MISSING', 'REVIEW_REQUIRED', 'SOURCE_UNAVAILABLE', 'SOURCE_BODY_CHANGED', 'SOURCE_UPSTREAM_REVIEW_REQUIRED', 'ARCHIVED_NOTE_SOURCE', 'NO_VALID_SOURCE']
const fields = (value: unknown, names: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown, min: number, max = MAX): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
const revision = (value: unknown): value is number => integer(value, 0)
const reasonValid = (value: unknown): value is string => typeof value === 'string' && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value)
  && Array.from(value).length <= 1000 && !/[\u0000\uD800-\uDFFF]/u.test(value)
const bytes = (value: unknown) => new TextEncoder().encode(JSON.stringify(value)).length
const check = (rule: () => boolean): boolean => { try { return rule() } catch { return false } }
const sameTime = (left: unknown, right: unknown) => memoryUtcMicros(left) !== undefined && memoryUtcMicros(left) === memoryUtcMicros(right)

// Date 只校验日历和整秒；微秒比较单独使用整数，避免截断后误判同一回执或未来时间。
export function memoryUtcMicros(value: unknown): bigint | undefined {
  if (typeof value !== 'string') return undefined
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?Z$/.exec(value)
  if (!match || Number(value.slice(0, 4)) < 1000) return undefined
  const milliseconds = Date.parse(`${match[1]}Z`)
  if (!Number.isFinite(milliseconds) || new Date(milliseconds).toISOString().slice(0, 19) !== match[1]) return undefined
  return BigInt(milliseconds) * 1000n + BigInt((match[2] ?? '').padEnd(6, '0'))
}
const timestamp = (value: unknown): value is string => memoryUtcMicros(value) !== undefined
export function memoryReviewZoneValid(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > 1000 || !/^[A-Za-z][A-Za-z0-9._+-]*(?:\/[A-Za-z0-9._+-]+)*$/.test(value)
    || /^(?:UTC|GMT)[+-]/i.test(value)) return false
  try { new Intl.DateTimeFormat('zh-CN', { timeZone: value }).format(); return true } catch { return false }
}

function actorValid(value: unknown): value is MemoryActor {
  return fields(value, ['kind', 'ownerId']) && (value.kind === 'OWNER' ? memoryIdValid(value.ownerId) : value.kind === 'SYSTEM' && value.ownerId === null)
}
function gateValid(value: unknown, cardId: string): value is MemoryGate {
  if (!fields(value, ['cardId', 'revision', 'reviewRequired', 'lastReviewDecisionId', 'lastHistorySequence', 'updatedAt']) || value.cardId !== cardId
    || !revision(value.revision) || typeof value.reviewRequired !== 'boolean' || !revision(value.lastHistorySequence) || !timestamp(value.updatedAt)
    || (value.lastReviewDecisionId !== null && !memoryIdValid(value.lastReviewDecisionId))) return false
  if (value.revision === 0) return value.lastReviewDecisionId === null && value.lastHistorySequence === (value.reviewRequired ? 1 : 0)
  return value.lastHistorySequence >= value.revision && value.lastHistorySequence - value.revision <= 1
}
function sourceValid(value: unknown): value is MemorySourceSnapshot {
  const names = ['itemId', 'versionId', 'kind', 'versionNumber', 'state', 'currentVersionId', 'bodyChanged']
  if (!(fields(value, [...names, 'upstreamReviewRequired']) || fields(value, names))
    || !memoryIdValid(value.itemId) || !memoryIdValid(value.versionId)) return false
  const upstream = value.upstreamReviewRequired
  if (value.state === 'UNAVAILABLE') return value.kind === null && value.versionNumber === null && value.currentVersionId === null
    && value.bodyChanged === null && (upstream === null || upstream === undefined)
  if (typeof upstream !== 'boolean' && upstream !== undefined && upstream !== null) return false
  if ((value.kind !== 'NOTE' && value.kind !== 'CONCEPT') || !integer(value.versionNumber, 1, 2147483647)
    || !memoryIdValid(value.currentVersionId) || typeof value.bodyChanged !== 'boolean') return false
  if (value.versionId === value.currentVersionId && value.bodyChanged) return false
  return value.state === 'ARCHIVED' || (value.state === 'CURRENT' ? value.versionId === value.currentVersionId && !value.bodyChanged
    : value.state === 'SUPERSEDED' && value.versionId !== value.currentVersionId)
}
function sourcesValid(value: unknown): value is MemorySourceSnapshot[] {
  return Array.isArray(value) && value.length >= 1 && value.length <= 10 && value.every(source => sourceValid(source))
    && new Set(value.map(source => `${source.itemId}/${source.versionId}`)).size === value.length
}
function usable(source: MemorySourceSnapshot): boolean {
  return ['CURRENT', 'SUPERSEDED'].includes(source.state) && source.bodyChanged === false && source.upstreamReviewRequired !== true
}
function sourcesReviewable(sources: MemorySourceSnapshot[]): boolean {
  return sources.some(usable) && sources.every(source => source.state !== 'UNAVAILABLE' && source.bodyChanged === false
    && source.upstreamReviewRequired !== true && !(source.kind === 'NOTE' && source.state === 'ARCHIVED'))
}
function eligibilityValid(value: unknown, cardStatus: string, gate: MemoryGate | null, sources: MemorySourceSnapshot[]): value is MemoryEligibility {
  if (!fields(value, ['sourceReviewAllowed', 'basisReady', 'blockers', 'warnings']) || !Array.isArray(value.blockers) || !Array.isArray(value.warnings)) return false
  const conditions = [cardStatus === 'ARCHIVED', gate === null, gate?.reviewRequired === true, sources.some(source => source.state === 'UNAVAILABLE'),
    sources.some(source => source.bodyChanged === true), sources.some(source => source.upstreamReviewRequired === true),
    sources.some(source => source.kind === 'NOTE' && source.state === 'ARCHIVED'), !sources.some(usable)]
  const blockers = blockOrder.filter((_, index) => conditions[index])
  const warnings = sources.some(source => source.kind === 'CONCEPT' && source.state === 'ARCHIVED') && sources.some(usable) ? ['ARCHIVED_CONCEPT_WITH_OTHER_SOURCE'] : []
  return JSON.stringify(value.blockers) === JSON.stringify(blockers) && JSON.stringify(value.warnings) === JSON.stringify(warnings)
    && value.basisReady === (blockers.length === 0) && value.sourceReviewAllowed === (cardStatus === 'ACTIVE' && gate !== null && sourcesReviewable(sources))
}
function fsrsStateValid(value: unknown): value is MemoryFsrsState {
  if (!fields(value, ['phase', 'step', 'stability', 'difficulty', 'dueAt', 'lastReviewAt']) || !timestamp(value.dueAt)) return false
  if (value.lastReviewAt === null) return value.phase === 'LEARNING' && value.step === 0 && value.stability === null && value.difficulty === null
  if (!timestamp(value.lastReviewAt) || memoryUtcMicros(value.dueAt)! <= memoryUtcMicros(value.lastReviewAt)!
    || typeof value.stability !== 'number' || !Number.isFinite(value.stability) || value.stability < 0.001
    || typeof value.difficulty !== 'number' || !Number.isFinite(value.difficulty) || value.difficulty < 1 || value.difficulty > 10) return false
  return value.phase === 'LEARNING' ? value.step === 0 || value.step === 1 : value.phase === 'RELEARNING' ? value.step === 0 : value.phase === 'REVIEW' && value.step === null
}
export function memoryScheduleValid(value: unknown, cardId?: string): value is MemorySchedule {
  return check(() => {
    if (!fields(value, ['cardId', 'revision', 'cycleId', 'status', 'pauseReason', 'algorithmVersion', 'parameterVersion', 'state', 'suggestedDueAt', 'deferUntil', 'effectiveDueAt', 'lastRatedEventId', 'lastHistorySequence', 'createdAt', 'updatedAt'])
      || !memoryIdValid(value.cardId) || (cardId !== undefined && value.cardId !== cardId) || !revision(value.revision) || !memoryIdValid(value.cycleId)
      || !integer(value.lastHistorySequence, 1) || value.lastHistorySequence - value.revision !== 1 || !fsrsStateValid(value.state)
      || value.algorithmVersion !== ALGORITHM || value.parameterVersion !== PARAMETERS
      || (value.state.lastReviewAt === null ? value.lastRatedEventId !== null : !memoryIdValid(value.lastRatedEventId))
      || !timestamp(value.suggestedDueAt) || !sameTime(value.suggestedDueAt, value.state.dueAt) || !timestamp(value.effectiveDueAt)
      || (value.deferUntil !== null && !timestamp(value.deferUntil)) || !timestamp(value.createdAt) || !timestamp(value.updatedAt)) return false
    if (value.status === 'ACTIVE' ? value.pauseReason !== null : value.status !== 'PAUSED' || (value.pauseReason !== 'MANUAL' && value.pauseReason !== 'SOURCE_REVIEW' && value.pauseReason !== 'CARD_ARCHIVED')) return false
    if (value.revision === 0 && (value.status !== 'ACTIVE' || value.state.lastReviewAt !== null || value.deferUntil !== null || !sameTime(value.createdAt, value.updatedAt))) return false
    const effective = value.deferUntil !== null && memoryUtcMicros(value.deferUntil)! > memoryUtcMicros(value.suggestedDueAt)! ? value.deferUntil : value.suggestedDueAt
    return sameTime(value.effectiveDueAt, effective) && bytes(value) <= 65536
  })
}
export function memoryRecallStateValid(value: unknown, cardId?: string): value is MemoryRecallState {
  return check(() => fields(value, ['cardId', 'cardRevision', 'cardVersionId', 'cardStatus', 'gate', 'sources', 'schedule', 'eligibility', 'asOf'])
    && memoryIdValid(value.cardId) && (cardId === undefined || value.cardId === cardId) && integer(value.cardRevision, 0, 1000) && memoryIdValid(value.cardVersionId)
    && (value.cardStatus === 'ACTIVE' || value.cardStatus === 'ARCHIVED') && (value.gate === null || gateValid(value.gate, value.cardId))
    && sourcesValid(value.sources) && (value.schedule === null || memoryScheduleValid(value.schedule, value.cardId))
    && eligibilityValid(value.eligibility, value.cardStatus, value.gate, value.sources) && timestamp(value.asOf) && bytes(value) <= 65536)
}
export function memoryCurrentRecallStateValid(value: unknown, cardId?: string): value is MemoryRecallState {
  // 当前 GET 必须明确返回上游状态；旧幂等回执的尾字段兼容仅由上面的快照校验承接。
  return memoryRecallStateValid(value, cardId) && value.sources.every(source => Object.hasOwn(source, 'upstreamReviewRequired')
    && (source.state === 'UNAVAILABLE' ? source.upstreamReviewRequired === null : typeof source.upstreamReviewRequired === 'boolean'))
}
export function memoryReviewSettingsValid(value: unknown): value is MemoryReviewSettings {
  return check(() => {
    if (!fields(value, ['configured', 'revision', 'zoneId', 'dailyNewBudget', 'dailyReviewBudget', 'createdAt', 'updatedAt'])
      || !integer(value.dailyNewBudget, 0, 1000) || !integer(value.dailyReviewBudget, 0, 1000) || bytes(value) > 65536) return false
    if (value.configured === false) return value.revision === null && value.zoneId === null && value.createdAt === null && value.updatedAt === null
      && value.dailyNewBudget === 10 && value.dailyReviewBudget === 50
    return value.configured === true && revision(value.revision) && memoryReviewZoneValid(value.zoneId) && timestamp(value.createdAt) && timestamp(value.updatedAt)
      && (value.revision !== 0 || sameTime(value.createdAt, value.updatedAt))
  })
}

function sourceChangeValid(value: unknown): value is MemorySourceChange {
  return fields(value, ['itemId', 'kind', 'referencedVersionIds', 'previousVersionId', 'currentVersionId', 'previousStatus', 'currentStatus'])
    && memoryIdValid(value.itemId) && (value.kind === 'NOTE' || value.kind === 'CONCEPT') && Array.isArray(value.referencedVersionIds)
    && value.referencedVersionIds.length >= 1 && value.referencedVersionIds.length <= 10 && value.referencedVersionIds.every(memoryIdValid)
    && new Set(value.referencedVersionIds).size === value.referencedVersionIds.length && memoryIdValid(value.previousVersionId) && memoryIdValid(value.currentVersionId)
    && (value.previousStatus === 'ACTIVE' || value.previousStatus === 'ARCHIVED') && (value.currentStatus === 'ACTIVE' || value.currentStatus === 'ARCHIVED')
}
function impactValid(value: unknown, cardId?: string): value is MemoryImpact {
  const names = ['id', 'cardId', 'cardVersionId', 'gateRevision', 'reason', 'source', 'reviewRequired', 'actor', 'createdAt']
  if (!(fields(value, [...names, 'upstreamImpactId']) || fields(value, names))
    || !memoryIdValid(value.id) || !memoryIdValid(value.cardId) || (cardId !== undefined && value.cardId !== cardId) || !memoryIdValid(value.cardVersionId)
    || !revision(value.gateRevision) || typeof value.reviewRequired !== 'boolean' || !actorValid(value.actor) || !timestamp(value.createdAt)
    || typeof value.reason !== 'string' || !Object.hasOwn(memoryImpactReasonLabels, value.reason)) return false
  if (value.reason !== 'SOURCE_UPSTREAM_CHANGED' && value.upstreamImpactId !== undefined && value.upstreamImpactId !== null) return false
  if (value.reason === 'BASELINE_REVIEW_REQUIRED') return value.source === null && value.gateRevision === 0 && value.reviewRequired && value.actor.kind === 'SYSTEM'
  if (value.actor.kind !== 'OWNER' || value.gateRevision < 1) return false
  if (value.reason === 'SOURCE_UPSTREAM_CHANGED') return value.source === null && value.reviewRequired && memoryIdValid(value.upstreamImpactId)
  if (value.reason.startsWith('SOURCE_')) {
    if (!sourceChangeValid(value.source)) return false
    if (value.reason === 'SOURCE_BODY_CHANGED') return value.reviewRequired && value.source.previousVersionId !== value.source.currentVersionId
    return value.reason === 'SOURCE_ARCHIVED' ? value.source.previousStatus === 'ACTIVE' && value.source.currentStatus === 'ARCHIVED' && (value.source.kind !== 'NOTE' || value.reviewRequired)
      : value.source.previousStatus === 'ARCHIVED' && value.source.currentStatus === 'ACTIVE'
  }
  return value.source === null && (value.reason !== 'CARD_BASIS_CHANGED' || value.reviewRequired)
}
function decisionValid(value: unknown, cardId?: string): value is MemorySourceReviewDecision {
  return fields(value, ['id', 'cardId', 'cardVersionId', 'cardRevision', 'reviewedGateRevision', 'resultingGateRevision', 'sources', 'reason', 'actor', 'createdAt'])
    && memoryIdValid(value.id) && memoryIdValid(value.cardId) && (cardId === undefined || value.cardId === cardId) && memoryIdValid(value.cardVersionId)
    && integer(value.cardRevision, 0, 1000) && revision(value.reviewedGateRevision) && integer(value.resultingGateRevision, 1)
    && value.resultingGateRevision - value.reviewedGateRevision === 1 && sourcesValid(value.sources) && sourcesReviewable(value.sources)
    && reasonValid(value.reason) && actorValid(value.actor) && value.actor.kind === 'OWNER' && timestamp(value.createdAt)
}
function sourceHistoryValid(value: unknown, cardId?: string): value is MemorySourceHistoryEntry {
  if (!fields(value, ['sequence', 'kind', 'impact', 'decision']) || !integer(value.sequence, 1) || bytes(value) > 16384) return false
  if (value.kind === 'IMPACT') return value.decision === null && impactValid(value.impact, cardId)
    && (value.impact.reason !== 'BASELINE_REVIEW_REQUIRED' || value.sequence === 1)
    && value.sequence >= value.impact.gateRevision && value.sequence - value.impact.gateRevision <= 1
  return value.kind === 'SOURCE_REVIEW' && value.impact === null && decisionValid(value.decision, cardId)
    && value.sequence >= value.decision.resultingGateRevision && value.sequence - value.decision.resultingGateRevision <= 1
}
function sameState(left: MemorySchedule, right: MemorySchedule): boolean {
  return left.state.phase === right.state.phase && left.state.step === right.state.step && left.state.stability === right.state.stability
    && left.state.difficulty === right.state.difficulty && sameTime(left.state.dueAt, right.state.dueAt) && left.state.lastReviewAt === right.state.lastReviewAt
    && sameTime(left.suggestedDueAt, right.suggestedDueAt) && left.lastRatedEventId === right.lastRatedEventId
}
function scheduleChangeValid(value: unknown, cardId?: string): value is MemoryScheduleChange {
  if (!fields(value, ['id', 'sequence', 'cardId', 'cardVersionId', 'cardRevision', 'gateRevision', 'kind', 'before', 'after', 'impactId', 'actor', 'reason', 'createdAt'])
    || !memoryIdValid(value.id) || !integer(value.sequence, 1) || !memoryIdValid(value.cardId) || (cardId !== undefined && value.cardId !== cardId)
    || !memoryIdValid(value.cardVersionId) || !integer(value.cardRevision, 0, 1000) || !revision(value.gateRevision) || !actorValid(value.actor) || value.actor.kind !== 'OWNER'
    || typeof value.kind !== 'string' || !Object.hasOwn(memoryScheduleChangeKindLabels, value.kind) || !timestamp(value.createdAt)
    || !memoryScheduleValid(value.after, value.cardId) || value.after.lastHistorySequence !== value.sequence || !sameTime(value.createdAt, value.after.updatedAt) || bytes(value) > 16384) return false
  const automatic = value.kind === 'SOURCE_PAUSE' || value.kind === 'CARD_PAUSE'
  if (automatic ? !memoryIdValid(value.impactId) : value.impactId !== null) return false
  if (automatic || value.kind === 'ENABLE' || value.kind === 'RATE' ? value.reason !== '' : !reasonValid(value.reason)) return false
  const after = value.after
  if (value.kind === 'ENABLE') return value.before === null && value.sequence === 1 && after.revision === 0 && after.status === 'ACTIVE'
    && after.deferUntil === null && after.lastRatedEventId === null && after.state.lastReviewAt === null && sameTime(after.createdAt, after.updatedAt)
  if (!memoryScheduleValid(value.before, value.cardId)) return false
  const before = value.before
  if (after.revision - before.revision !== 1 || after.lastHistorySequence - before.lastHistorySequence !== 1
    || !sameTime(before.createdAt, after.createdAt) || before.algorithmVersion !== after.algorithmVersion || before.parameterVersion !== after.parameterVersion) return false
  if (value.kind === 'RESET') return before.status === 'PAUSED' && after.status === 'ACTIVE' && before.cycleId !== after.cycleId && after.deferUntil === null
    && after.lastRatedEventId === null && after.state.lastReviewAt === null
  // 评分历史以 lastRatedEventId 连接真实事件；延期和恢复仍须完整保留原算法状态。
  if (value.kind === 'RATE') return before.status === 'ACTIVE' && after.status === 'ACTIVE' && before.cycleId === after.cycleId
    && after.deferUntil === null && memoryIdValid(after.lastRatedEventId) && after.lastRatedEventId !== before.lastRatedEventId
    && memoryUtcMicros(before.effectiveDueAt)! <= memoryUtcMicros(value.createdAt)! && sameTime(after.state.lastReviewAt, value.createdAt)
    && sameTime(after.effectiveDueAt, after.suggestedDueAt)
  if (before.cycleId !== after.cycleId || !sameState(before, after)) return false
  if (value.kind === 'DEFER') return before.status === 'ACTIVE' && after.status === 'ACTIVE' && after.deferUntil !== null
    && memoryUtcMicros(after.deferUntil)! > memoryUtcMicros(before.effectiveDueAt)!
  if (value.kind === 'RESUME') return before.status === 'PAUSED' && after.status === 'ACTIVE' && after.deferUntil !== null
  const pauseReason = value.kind === 'SOURCE_PAUSE' ? 'SOURCE_REVIEW' : value.kind === 'CARD_PAUSE' ? 'CARD_ARCHIVED' : 'MANUAL'
  return after.status === 'PAUSED' && after.pauseReason === pauseReason && (before.status !== 'PAUSED' || before.pauseReason !== pauseReason)
    && (value.kind !== 'PAUSE' || before.status === 'ACTIVE') && (before.deferUntil === null ? after.deferUntil === null : sameTime(before.deferUntil, after.deferUntil))
    && sameTime(before.effectiveDueAt, after.effectiveDueAt)
}
function pageValid(value: unknown, entry: (value: unknown) => value is { sequence: number }, before: number | undefined, limit: number): boolean {
  if (!fields(value, ['items', 'nextBefore']) || !Array.isArray(value.items) || !integer(limit, 1, 50) || value.items.length > limit
    || (before !== undefined && !integer(before, 1)) || bytes(value) > 1048576) return false
  if (!value.items.every(entry) || !value.items.every((item, index, items) => (before === undefined || item.sequence < before) && (index === 0 || item.sequence < items[index - 1]!.sequence))) return false
  return value.nextBefore === null || (integer(value.nextBefore, 1) && value.items.length === limit && value.nextBefore === value.items.at(-1)?.sequence)
}
export function memoryImpactPageValid(value: unknown, cardId?: string, before?: number, limit = 20): value is MemoryImpactPage {
  const ids = new Set<string>()
  let expectedId = cardId
  return (cardId === undefined || memoryIdValid(cardId)) && check(() => pageValid(value, (entry): entry is MemorySourceHistoryEntry => {
    if (!sourceHistoryValid(entry, expectedId)) return false
    const record = entry.impact ?? entry.decision!
    if (ids.has(record.id)) return false
    expectedId ??= record.cardId; ids.add(record.id)
    return true
  }, before, limit))
}
export function memoryScheduleHistoryPageValid(value: unknown, cardId?: string, before?: number, limit = 20): value is MemoryScheduleHistoryPage {
  const ids = new Set<string>()
  let expectedId = cardId
  return (cardId === undefined || memoryIdValid(cardId)) && check(() => pageValid(value, (entry): entry is MemoryScheduleChange => {
    if (!scheduleChangeValid(entry, expectedId) || ids.has(entry.id)) return false
    expectedId ??= entry.cardId; ids.add(entry.id)
    return true
  }, before, limit))
}

function requestProblem(value: unknown, names: string[], validate: (value: Record<string, unknown>) => string): string {
  try {
    if (!fields(value, names)) return '请求字段不完整或含未定义字段，请重新核对。'
    if (bytes(value) > 16384) return '完整请求超过 16 KiB，请缩减后保存；内容不会截断。'
    return validate(value)
  } catch { return '请求内容无法读取，请重新核对。' }
}
function cardGateProblem(value: Record<string, unknown>): string {
  if (!integer(value.expectedCardRevision, 0, 1000)) return '缺少有效的卡片修订，请重新读取卡片。'
  return revision(value.expectedGateRevision) ? '' : '缺少有效的来源门禁修订，请重新读取来源状态。'
}
function scheduleRevisionProblem(value: unknown): string { return revision(value) ? '' : '缺少有效的排程修订，请重新读取排程。' }
function reasonProblem(value: unknown): string { return reasonValid(value) ? '' : '理由需为 1–1000 个完整字符，不能全为空白或包含 NUL。' }
function futureProblem(value: unknown, asOf?: string): string {
  const target = memoryUtcMicros(value)
  if (target === undefined) return '请提供有效的 UTC 时间，以 Z 结尾、最多 6 位小数，年份为 1000–9999。'
  if (asOf !== undefined && (memoryUtcMicros(asOf) === undefined || target <= memoryUtcMicros(asOf)!)) return '指定时间必须晚于本次读取的服务器时间；提交时服务器会再次核对。'
  return ''
}
function timingProblem(value: Record<string, unknown>, asOf?: string): string {
  if (value.timeMode === 'NOW') return value.at === null ? '' : '立即生效须保留 at=null，由服务器确定时间。'
  return value.timeMode === 'AT' ? futureProblem(value.at, asOf) : '请选择立即生效或指定未来时间。'
}
function settingsProblem(value: Record<string, unknown>): string {
  if (!memoryReviewZoneValid(value.zoneId)) return '请明确填写 IANA 时区（如 Asia/Shanghai）或 UTC，不能使用固定偏移。'
  return integer(value.dailyNewBudget, 0, 1000) && integer(value.dailyReviewBudget, 0, 1000) ? '' : '每日新卡和复习建议数量须为 0–1000 的整数。'
}
export function memorySourceReviewProblem(value: unknown): string {
  return requestProblem(value, ['expectedCardRevision', 'expectedGateRevision', 'confirmed', 'reason'], request => cardGateProblem(request)
    || (request.confirmed === true ? '' : '请明确确认已经核对本版本参考与来源。') || reasonProblem(request.reason))
}
export function memoryEnableScheduleProblem(value: unknown, asOf?: string): string {
  return requestProblem(value, ['expectedCardRevision', 'expectedGateRevision', 'timeMode', 'at', 'confirmed'], request => cardGateProblem(request)
    || timingProblem(request, asOf) || (request.confirmed === true ? '' : '请明确确认启用排程。'))
}
export function memoryDeferScheduleProblem(value: unknown, effectiveDueAt?: string, asOf?: string): string {
  return requestProblem(value, ['expectedScheduleRevision', 'deferUntil', 'reason'], request => scheduleRevisionProblem(request.expectedScheduleRevision)
    || futureProblem(request.deferUntil, asOf) || (effectiveDueAt !== undefined && (memoryUtcMicros(effectiveDueAt) === undefined
      || memoryUtcMicros(request.deferUntil)! <= memoryUtcMicros(effectiveDueAt)!) ? '延期时间须晚于当前实际到期时间。' : '') || reasonProblem(request.reason))
}
export function memoryPauseScheduleProblem(value: unknown): string {
  return requestProblem(value, ['expectedScheduleRevision', 'reason'], request => scheduleRevisionProblem(request.expectedScheduleRevision) || reasonProblem(request.reason))
}
export function memoryResumeScheduleProblem(value: unknown, asOf?: string): string {
  return requestProblem(value, ['expectedCardRevision', 'expectedGateRevision', 'expectedScheduleRevision', 'policy', 'timeMode', 'at', 'confirmed', 'reason'], request => cardGateProblem(request)
    || scheduleRevisionProblem(request.expectedScheduleRevision) || (request.policy === 'KEEP' || request.policy === 'RESET_AS_NEW' ? '' : '请选择沿用原周期或作为新卡重新启用。')
    || timingProblem(request, asOf) || (request.confirmed === true ? '' : '请明确确认恢复排程。') || reasonProblem(request.reason))
}
export function memoryCreateSettingsProblem(value: unknown): string {
  return requestProblem(value, ['zoneId', 'dailyNewBudget', 'dailyReviewBudget'], settingsProblem)
}
export function memorySaveSettingsProblem(value: unknown): string {
  return requestProblem(value, ['expectedRevision', 'zoneId', 'dailyNewBudget', 'dailyReviewBudget'], request => (revision(request.expectedRevision) ? '' : '缺少已保存设置的修订，请重新读取。') || settingsProblem(request))
}

export { sourceValid as memorySourceSnapshotValid, sourcesValid as memorySourceSnapshotsValid, sourcesReviewable as memorySourcesReviewable, fsrsStateValid as memoryFsrsStateValid }
