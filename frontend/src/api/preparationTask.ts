import type { Schema } from './client'
import { validZone } from './schedule'

export type PreparationTaskStatus = Schema['PlanningStatus']
export type PreparationTaskTargetLink = Schema['PlanningTargetLink']
export type PreparationTaskTarget = Schema['PlanningTargetSnapshot']
export type PreparationTaskContent = Schema['PlanningContent']
export type CreatePreparationTask = Schema['PlanningCreateTask']
export type SavePreparationTask = Schema['PlanningSaveTask']
export type ChangePreparationTaskStatus = Schema['PlanningChangeStatus']
export type PreparationTask = Schema['PlanningPreparationTask']
export type PreparationTaskDetail = Schema['PlanningTaskDetail']
export type PreparationTaskPage = Schema['PlanningTaskPage']
export type PreparationTaskRevision = Omit<Schema['PlanningRevision'], 'action'> & { action: Schema['PlanningRevision']['action'] | 'RESCHEDULE' }
export type PreparationTaskHistoryPage = Omit<Schema['PlanningHistoryPage'], 'items'> & { items: PreparationTaskRevision[] }

export const preparationTaskStatusLabels: Record<PreparationTaskStatus, string> = {
  TODO: '待开始', IN_PROGRESS: '进行中', PAUSED: '已暂停', DONE: '已完成', CANCELLED: '已取消',
}
export const preparationTaskActionLabels: Record<string, string> = { CREATE: '创建任务', EDIT: '修改内容', STATUS: '变更状态', RESCHEDULE: '面试联动改期' }
export function emptyPreparationTask(): PreparationTaskContent {
  return { title: '', detail: '', minutes: 30, plannedDate: null, zoneId: 'Asia/Shanghai' }
}
export function clonePreparationTaskContent(value: PreparationTaskContent): PreparationTaskContent { return { ...value } }
export function preparationTaskClosed(task: PreparationTask): boolean { return task.status === 'DONE' || task.status === 'CANCELLED' }
export function preparationTaskTransitions(task: PreparationTask): PreparationTaskStatus[] {
  if (task.status === 'TODO') return ['IN_PROGRESS', 'PAUSED', 'DONE', 'CANCELLED']
  if (task.status === 'IN_PROGRESS') return ['PAUSED', 'DONE', 'CANCELLED']
  if (task.status === 'PAUSED') return [...(task.startedAt === null ? ['TODO' as const] : []), 'IN_PROGRESS', 'DONE', 'CANCELLED']
  return []
}
export function preparationTaskTransitionLabel(status: PreparationTaskStatus, started: boolean): string {
  return status === 'IN_PROGRESS' ? started ? '恢复进行' : '开始任务'
    : status === 'PAUSED' ? '暂停任务' : status === 'TODO' ? '恢复待开始' : status === 'DONE' ? '标记完成' : '取消任务'
}
export function validPreparationTaskDate(value: string): boolean {
  if (!/^[1-9]\d{3}-\d{2}-\d{2}$/.test(value)) return false
  const [year, month, day] = value.split('-').map(Number) as [number, number, number]
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1]!
}
export function preparationTaskDateRangeProblem(from: string, to: string): string {
  if (!from && !to) return ''
  if (!from || !to || !validPreparationTaskDate(from) || !validPreparationTaskDate(to)) return '日期筛选需同时填写两个有效日期，年份为 1000–9999。'
  if (from > to) return '结束日期不能早于开始日期。'
  // UTC 仅用于计算日历天数；任务日期始终保留原字符串，不作时区换算。
  const ordinal = (value: string) => { const [year, month, day] = value.split('-').map(Number) as [number, number, number]; return Date.UTC(year, month - 1, day) / 86_400_000 }
  return ordinal(to) - ordinal(from) + 1 > 366 ? '单次日期筛选最多包含 366 天。' : ''
}
function invalidText(value: string): boolean {
  if (value.includes('\0')) return true
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index)
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(++index)
      if (!(next >= 0xdc00 && next <= 0xdfff)) return true
    } else if (code >= 0xdc00 && code <= 0xdfff) return true
  }
  return false
}
export function preparationTaskContentProblem(value: PreparationTaskContent): string {
  if (!value.title.trim() || value.title.length > 200) return '请填写任务标题，长度不能超过 200。'
  if (value.detail.length > 1000) return '任务说明长度不能超过 1000。'
  if (invalidText(value.title) || invalidText(value.detail)) return '标题或说明包含无法保存的字符，请检查复制内容。'
  if (!Number.isInteger(value.minutes) || value.minutes < 1 || value.minutes > 1440) return '预计工作量须为 1–1440 的整数分钟。'
  if (value.plannedDate !== null && !validPreparationTaskDate(value.plannedDate)) return '计划日期需为有效的 yyyy-MM-dd，年份为 1000–9999；也可清空日期。'
  if (!value.zoneId || value.zoneId.trim() !== value.zoneId || /^[+-]/.test(value.zoneId) || !validZone(value.zoneId)) return '请填写 IANA 时区（如 Asia/Shanghai）或 UTC，不能使用固定偏移。'
  return ''
}
