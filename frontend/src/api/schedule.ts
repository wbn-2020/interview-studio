import type { Schema } from './client'

export type ScheduleStatus = Schema['ScheduleStatus']
export type ScheduleTiming = Schema['ScheduleTiming']
export type ScheduleMaterialKind = Schema['ScheduleMaterialKind']
export type ScheduleSourceState = Schema['ScheduleReferenceState']
export type ScheduleMaterial = Schema['ScheduleMaterialLink']
export type ScheduleReference = Schema['ScheduleMaterialReference']
export type ScheduleTimeInput = Schema['ScheduleTimeInput']
export type ScheduleTime = Schema['ScheduleResolvedTime']
export type TimeResolution = Schema['ScheduleTimeResolution']
export type ScheduleInput = Schema['CreateScheduleEventRequest']
export type InterviewEvent = Schema['ScheduleInterviewEvent']
export type EventPage = Schema['ScheduleEventPage']
export type ReminderPage = Schema['ScheduleReminderPage']
export type ScheduleHistoryEntry = Schema['ScheduleHistorySummary']
export type ScheduleHistoryPage = Schema['ScheduleHistoryPage']
export type ReschedulePreview = Schema['ScheduleReschedulePreview']
export type ScheduleChoice = { kind: ScheduleMaterialKind | 'TARGET'; itemId: string; versionId: string; title: string; number: number }

export const scheduleStatusLabels: Record<ScheduleStatus, string> = { PLANNED: '待进行', COMPLETED: '已标记完成', CANCELLED: '已取消' }
export const scheduleTimingLabels: Record<ScheduleTiming, string> = { UPCOMING: '尚未到提醒时间', DUE: '准备提醒已到期', IN_PROGRESS: '计划时段内', PAST: '已过计划时间，尚未标记结果', CLOSED: '已处理' }
export const scheduleSourceLabels: Record<ScheduleSourceState, string> = { CURRENT: '当前有效', SUPERSEDED: '来源已改版', WITHDRAWN: '来源已撤回', ARCHIVED: '来源已归档', UNVERIFIED: '来源待核实', REVIEW_REQUIRED: '来源待复核' }
export const scheduleMaterialLabels: Record<ScheduleMaterialKind | 'TARGET', string> = { TARGET: '岗位目标', PROJECT: '项目', FACT: '已核实事实', EXPRESSION: '表达素材', KNOWLEDGE: '知识' }

export function browserZone(): string { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' }
export function validZone(zoneId: string): boolean { try { new Intl.DateTimeFormat('zh-CN', { timeZone: zoneId }).format(); return Boolean(zoneId.trim()) } catch { return false } }
export function dateKey(instant: string | number, zoneId: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zoneId, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(instant))
  const field = (type: string) => parts.find(part => part.type === type)?.value ?? ''
  return `${field('year')}-${field('month')}-${field('day')}`
}
export function formatScheduleTime(instant: string, zoneId: string): string {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: zoneId, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(instant))
}
export function emptySchedule(): ScheduleInput {
  return { title: '', targetId: null, round: '', location: '', meetingUrl: '', notes: '', time: { localStart: '', zoneId: browserZone(), offset: null, durationMinutes: 60 }, remindBeforeMinutes: 60, materials: [] }
}
export function cloneSchedule<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
export function scheduleInput(event: InterviewEvent): ScheduleInput {
  return { title: event.title, targetId: event.targetId, round: event.round, location: event.location, meetingUrl: event.meetingUrl, notes: event.notes, time: { localStart: event.time.localStart, zoneId: event.time.zoneId, offset: event.time.offset, durationMinutes: event.time.durationMinutes }, remindBeforeMinutes: event.remindBeforeMinutes, materials: event.materials.map(value => ({ ...value })) }
}
export function scheduleProblem(value: ScheduleInput): string {
  if (!value.title.trim() || value.title.length > 200) return '请填写不超过 200 字符的安排标题。'
  if (value.round.length > 100 || value.location.length > 500 || value.notes.length > 10000) return '轮次最多 100 字符，地点最多 500 字符，备注最多 10000 字符。'
  if (!Number.isInteger(value.remindBeforeMinutes) || value.remindBeforeMinutes < 0 || value.remindBeforeMinutes > 10080) return '提醒提前量应为 0–10080 的整数分钟。'
  if (value.meetingUrl) {
    try { const url = new URL(value.meetingUrl); if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || value.meetingUrl.length > 2000) return '会议链接须为不含账号信息的 HTTP(S) 地址，最多 2000 字符。' }
    catch { return '请检查会议链接格式。' }
  }
  if (value.materials.length > 50) return '每次安排最多关联 50 项材料版本。'
  return ''
}
