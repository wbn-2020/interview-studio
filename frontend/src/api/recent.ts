import type { RouteLocationRaw } from 'vue-router'
import type { Schema } from './client'
import { aiPurposeLabels, aiTaskStatusLabel } from './ai'
import { questionTypeLabels } from './bank'

export type RecentKind = Schema['RecentKind']
export type RecentEntry = Schema['RecentEntry']
export type RecentPage = Schema['RecentPage']
export type RecentVisitRequest = Schema['RecentVisitRequest']
export type RecentVisit = Schema['RecentVisit']
export const recentKindLabels: Record<RecentKind, string> = {
  KNOWLEDGE: '知识', PREPARATION: '准备材料', MATERIAL: '附件资料', QUESTION: '题库', COLLECTION: '题单与路线',
  TRAINING: '文字训练', RECOLLECTION: '面后回忆', MEMORY_CARD: '回忆卡', AI_TASK: 'AI 任务', BOARD: '脑图白板',
  INTERVIEW: '面试安排', PREPARATION_TASK: '准备任务', REPRACTICE: '独立复练',
}
export const recentKindOptions = Object.entries(recentKindLabels).map(([value, label]) => ({ value: value as RecentKind, label }))
export const recentIdValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
export const recentKindValid = (value: unknown): value is RecentKind => typeof value === 'string' && Object.hasOwn(recentKindLabels, value)
const fields = (value: unknown, keys: readonly string[]) => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)))
// 目录沿用已保存标题；合法历史 NUL 不构成整页损坏，子类型及状态另受封闭枚举约束。
const text = (value: unknown, maximum: number): value is string => typeof value === 'string' && Array.from(value).length <= maximum
  && !/[\uD800-\uDFFF]/u.test(value) && /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)

export function recentTimeKey(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const match = /^([1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{3}|\d{6}))?Z$/.exec(value)
  if (!match || match[2] === '000' || match[2]?.length === 6 && match[2].endsWith('000')) return undefined
  const milliseconds = Date.parse(`${match[1]}.${(match[2] ?? '').padEnd(3, '0').slice(0, 3)}Z`)
  if (!Number.isFinite(milliseconds) || new Date(milliseconds).toISOString().slice(0, 19) !== match[1]) return undefined
  // 排序必须保留微秒；Date 的毫秒精度不能决定同一毫秒内的页边界。
  return `${match[1]}.${(match[2] ?? '').padEnd(6, '0')}Z`
}
export function recentCursor(value: unknown, filter?: RecentKind): RecentVisit | undefined {
  if (typeof value !== 'string' || value.length > 100) return undefined
  const parts = value.split('~')
  if (parts.length !== 3 || !recentTimeKey(parts[0]) || !recentKindValid(parts[1]) || !recentIdValid(parts[2]) || filter !== undefined && parts[1] !== filter) return undefined
  return { visitedAt: parts[0]!, kind: parts[1], id: parts[2] }
}
export function recentCompare(left: RecentVisit, right: RecentVisit): number {
  const a = recentTimeKey(left.visitedAt)!, b = recentTimeKey(right.visitedAt)!
  return a === b ? left.kind === right.kind ? left.id < right.id ? -1 : left.id === right.id ? 0 : 1
    : left.kind < right.kind ? -1 : 1 : a > b ? -1 : 1
}
export function recentVisitValid(value: unknown, request: RecentVisitRequest): value is RecentVisit {
  const visit = value as RecentVisit
  return fields(visit, ['kind', 'id', 'visitedAt']) && visit.kind === request.kind && visit.id === request.id && Boolean(recentTimeKey(visit.visitedAt))
}
const activeStatuses = ['ACTIVE', 'ARCHIVED']
const trainingStatuses = ['DRAFT', 'RUNNING', 'PAUSED', 'ENDED']
function entryTypeValid(entry: RecentEntry): boolean {
  const { kind, subtype, status } = entry
  if (kind === 'KNOWLEDGE') return ['NOTE', 'CONCEPT'].includes(subtype) && activeStatuses.includes(status)
  if (kind === 'PREPARATION') return ['TARGET', 'PROJECT', 'FACT', 'EXPRESSION'].includes(subtype) && activeStatuses.includes(status)
  if (kind === 'MATERIAL') return ['PDF', 'DOCX', 'PNG', 'JPEG'].includes(subtype) && ['RESERVED', 'READY'].includes(status)
  if (kind === 'QUESTION') return Object.hasOwn(questionTypeLabels, subtype) && activeStatuses.includes(status)
  if (kind === 'COLLECTION') return ['LIST', 'ROUTE'].includes(subtype) && activeStatuses.includes(status)
  if (kind === 'TRAINING') return ['LEARNING', 'FOCUSED', 'MOCK'].includes(subtype) && trainingStatuses.includes(status)
  if (kind === 'RECOLLECTION') return ['RECOLLECTION', 'NONE'].includes(subtype) && activeStatuses.includes(status)
  if (kind === 'MEMORY_CARD') return ['QA', 'CLOZE'].includes(subtype) && activeStatuses.includes(status)
  if (kind === 'AI_TASK') return Object.hasOwn(aiPurposeLabels, subtype) && ['QUEUED', 'RUNNING', 'VALIDATING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCEL_REQUESTED', 'CANCELED'].includes(status)
  if (kind === 'BOARD') return ['MIND_MAP', 'BOARD'].includes(subtype) && status === 'AVAILABLE'
  if (kind === 'INTERVIEW') return subtype === 'INTERVIEW' && ['PLANNED', 'COMPLETED', 'CANCELLED'].includes(status)
  if (kind === 'PREPARATION_TASK') return subtype === 'PREPARATION_TASK' && ['TODO', 'IN_PROGRESS', 'PAUSED', 'DONE', 'CANCELLED'].includes(status)
  return kind === 'REPRACTICE' && ['SAME', 'VARIANT'].includes(subtype) && trainingStatuses.includes(status)
}
export function recentEntryValid(value: unknown): value is RecentEntry {
  const entry = value as RecentEntry
  return fields(entry, ['kind', 'id', 'title', 'subtype', 'status', 'visitedAt']) && recentKindValid(entry.kind) && recentIdValid(entry.id)
    && text(entry.title, 200) && text(entry.subtype, 80) && text(entry.status, 40) && Boolean(recentTimeKey(entry.visitedAt)) && entryTypeValid(entry)
}
export function recentPageValid(value: unknown, filter?: RecentKind, cursor?: string, limit = 20): value is RecentPage {
  try {
    const page = value as RecentPage, after = cursor === undefined ? undefined : recentCursor(cursor, filter)
    if (!fields(page, ['items', 'nextCursor', 'scanLimited']) || !Array.isArray(page.items) || page.items.length > limit
      || typeof page.scanLimited !== 'boolean' || !Number.isSafeInteger(limit) || limit < 1 || limit > 20 || cursor !== undefined && !after) return false
    const seen = new Set<string>()
    let previous = after
    for (const entry of page.items) {
      const key = `${entry.kind}/${entry.id}`
      if (!recentEntryValid(entry) || filter !== undefined && entry.kind !== filter || seen.has(key) || previous && recentCompare(previous, entry) >= 0) return false
      seen.add(key); previous = entry
    }
    if (page.nextCursor === null) return !page.scanLimited
    const next = recentCursor(page.nextCursor, filter)
    if (!next || after && recentCompare(after, next) >= 0 || previous && recentCompare(previous, next) > 0) return false
    // 未填满但有继续游标时，只能是扫描上限；页满游标必须等于最后已展示行。
    return page.scanLimited ? page.items.length < limit : page.items.length === limit && Boolean(previous && recentCompare(previous, next) === 0)
  } catch { return false }
}
export function recentEntryRoute(entry: RecentEntry): RouteLocationRaw | undefined {
  if (!recentEntryValid(entry)) return undefined
  const id = entry.id
  switch (entry.kind) {
    case 'KNOWLEDGE': return { name: 'knowledge', query: { item: id } }
    case 'PREPARATION': return { name: 'prepare', query: { kind: entry.subtype, item: id } }
    case 'MATERIAL': return { name: 'materials', query: { attachment: id } }
    case 'QUESTION': return { name: 'bank', query: { item: id } }
    case 'COLLECTION': return { name: 'collections', query: { item: id } }
    case 'TRAINING': return { name: 'training', query: { item: id } }
    case 'RECOLLECTION': return { name: 'recollections', query: { item: id } }
    case 'MEMORY_CARD': return { name: 'memory-cards', query: { item: id } }
    case 'AI_TASK': return { name: 'ai', query: { item: id } }
    case 'BOARD': return { name: 'boards', query: { board: id } }
    case 'INTERVIEW': return { name: 'schedule', query: { item: id } }
    case 'PREPARATION_TASK': return { name: 'preparation-tasks', query: { item: id } }
    case 'REPRACTICE': return { name: 'repractice', query: { item: id } }
  }
}
const subtypes: Record<string, string> = { NOTE: '笔记', CONCEPT: '概念', TARGET: '目标与 JD', PROJECT: '项目', FACT: '项目事实', EXPRESSION: '表达素材',
  LIST: '题单', ROUTE: '路线', LEARNING: '学习训练', FOCUSED: '专项训练', MOCK: '模拟面试', RECOLLECTION: '有个人回忆片段', NONE: '无旧回答',
  QA: '问答卡', CLOZE: '填空卡', MIND_MAP: '脑图', BOARD: '白板', INTERVIEW: '面试安排', PREPARATION_TASK: '普通准备任务', SAME: '相同题面', VARIANT: '变式题面' }
export function recentSubtypeLabel(entry: RecentEntry): string {
  if (entry.kind === 'QUESTION') return questionTypeLabels[entry.subtype as keyof typeof questionTypeLabels]
  if (entry.kind === 'AI_TASK') return aiPurposeLabels[entry.subtype as keyof typeof aiPurposeLabels]
  return subtypes[entry.subtype] ?? entry.subtype
}
const statuses: Record<string, string> = { ACTIVE: '未归档', ARCHIVED: '已归档', RESERVED: '尚未上传完成', READY: '原件已保存', FAILED: '失败',
  DRAFT: '配置预览', RUNNING: '进行中', PAUSED: '已暂停', ENDED: '已结束', AVAILABLE: '可打开', PLANNED: '待进行', COMPLETED: '已标记完成',
  CANCELLED: '已取消', TODO: '待开始', IN_PROGRESS: '进行中', DONE: '已完成' }
export function recentStatusLabel(entry: RecentEntry): string {
  return entry.kind === 'AI_TASK' ? aiTaskStatusLabel(entry.status as Schema['AiStatus'], entry.subtype as Schema['AiPurpose']) : statuses[entry.status] ?? entry.status
}
export const recentTimeLabel = (value: string) => new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
