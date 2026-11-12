import type { LocationQuery, RouteLocationRaw } from 'vue-router'
import type { SearchCriteria } from '../api/search'
import { searchDomains } from '../api/search'

export const SEARCH_PREFILL_EVENT = 'studio:search-prefill'
export type SearchPrefillResult = { kind: 'NONE' } | { kind: 'VALID'; criteria: SearchCriteria } | { kind: 'INVALID'; message: string }
export type WorkspaceCommand = {
  id: string
  label: string
  description: string
  keywords: readonly string[]
  group: 'PAGE' | 'CREATE' | 'SEARCH'
  target: RouteLocationRaw
}

export function readSearchPrefill(query: LocationQuery): SearchPrefillResult {
  const keys = Object.keys(query)
  if (!keys.length) return { kind: 'NONE' }
  if (keys.some(key => !['domain', 'status', 'q'].includes(key) || typeof query[key] !== 'string')) {
    return { kind: 'INVALID', message: '搜索链接只接受单值字符串 domain、status、q，请清除无效链接条件后重新选择。' }
  }
  const domain = query.domain ?? 'KNOWLEDGE', status = query.status ?? 'ACTIVE', q = query.q ?? ''
  const source = searchDomains.find(item => item.value === domain)
  if (!source || (status !== 'ACTIVE' && status !== 'ARCHIVED')) return { kind: 'INVALID', message: '搜索链接的来源或内容状态无效，请清除链接条件后重新选择。' }
  if (typeof q !== 'string' || q.length > 100 || /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(q)) {
    return { kind: 'INVALID', message: '搜索链接中的检索词过长或包含不完整字符，请缩短后重试。' }
  }
  return { kind: 'VALID', criteria: { domain: source.value, status, q } }
}

export const workspaceCommands: readonly WorkspaceCommand[] = [
  { id: 'today', label: '今日', description: '查看今天的准备与复习安排', keywords: ['today', '每日', '工作台'], group: 'PAGE', target: { name: 'today' } },
  { id: 'prepare', label: '准备材料', description: '整理目标、项目、事实与表达', keywords: ['prepare', '岗位', 'JD', '简历'], group: 'PAGE', target: { name: 'prepare' } },
  { id: 'knowledge', label: '知识库与星图', description: '浏览笔记、概念及知识连接', keywords: ['knowledge', '银河', '知识图谱'], group: 'PAGE', target: { name: 'knowledge' } },
  { id: 'training', label: '文字训练', description: '查看训练与正式提交', keywords: ['training', '练习', '模拟面试'], group: 'PAGE', target: { name: 'training' } },
  { id: 'review', label: '复盘', description: '查看自检、人工反馈与 AI 评价', keywords: ['review', '观察', '改进动作'], group: 'PAGE', target: { name: 'review' } },
  { id: 'growth', label: '成长', description: '查看有来源的成长记录', keywords: ['growth', '能力', '趋势'], group: 'PAGE', target: { name: 'growth' } },
  { id: 'search', label: '统一搜索', description: '主动查询并查看确切来源', keywords: ['search', '查找', '全局搜索'], group: 'PAGE', target: { name: 'search' } },
  { id: 'recent', label: '最近访问', description: '回到已经打开的内容对象', keywords: ['recent', '历史', '上次', '访问'], group: 'PAGE', target: { name: 'recent' } },
  { id: 'ai', label: 'AI 任务与用量', description: '查看模型任务、状态和费用记录', keywords: ['ai', '模型', '用量', '费用'], group: 'PAGE', target: { name: 'ai' } },
  { id: 'ai-authorizations', label: 'AI 授权管理', description: '查看外发授权与使用记录', keywords: ['ai', 'authorization', '撤回', '权限'], group: 'PAGE', target: { name: 'ai-authorizations' } },
  { id: 'boards', label: '脑图白板', description: '浏览独立白板与结构图', keywords: ['board', '脑图', '思维导图'], group: 'PAGE', target: { name: 'boards' } },
  { id: 'memory-cards', label: '回忆卡', description: '管理需要主动回忆的内容', keywords: ['memory', 'cards', '卡片'], group: 'PAGE', target: { name: 'memory-cards' } },
  { id: 'memory-recall', label: '主动回忆', description: '进入回忆队列与练习记录', keywords: ['recall', '记忆', '复习'], group: 'PAGE', target: { name: 'memory-recall' } },
  { id: 'memory-cram', label: '考前冲刺', description: '选择冲刺范围并查看安排', keywords: ['cram', '突击', '复习'], group: 'PAGE', target: { name: 'memory-cram' } },
  { id: 'materials', label: '附件资料', description: '管理附件、解析记录与摘录', keywords: ['materials', '上传', '文件', 'PDF'], group: 'PAGE', target: { name: 'materials' } },
  { id: 'preparation-tasks', label: '准备任务', description: '查看准备事项与进度', keywords: ['tasks', '计划', '待办'], group: 'PAGE', target: { name: 'preparation-tasks' } },
  { id: 'schedule', label: '面试安排', description: '管理面试日程与关联目标', keywords: ['schedule', '日历', '时间'], group: 'PAGE', target: { name: 'schedule' } },
  { id: 'bank', label: '题库', description: '浏览问题与题面条件', keywords: ['bank', 'questions', '题目'], group: 'PAGE', target: { name: 'bank' } },
  { id: 'collections', label: '题单与路线', description: '组织题目集合与练习路线', keywords: ['collections', '路线', '题单'], group: 'PAGE', target: { name: 'collections' } },
  { id: 'repractice', label: '复练与对照', description: '查看独立复练及新旧回答', keywords: ['repractice', '比较', '对照'], group: 'PAGE', target: { name: 'repractice' } },
  { id: 'training-report', label: '训练报告', description: '查看训练目录、提交与任务记录', keywords: ['report', '报告', '历史'], group: 'PAGE', target: { name: 'training-report' } },
  { id: 'recollections', label: '面后回忆', description: '整理面试后的问题与不确定性', keywords: ['recollection', '回忆', '补记'], group: 'PAGE', target: { name: 'recollections' } },
  { id: 'create-target', label: '新建目标', description: '打开岗位目标表单', keywords: ['create', '新建', 'JD', '岗位'], group: 'CREATE', target: { name: 'prepare', query: { kind: 'TARGET', create: '1' } } },
  { id: 'create-project', label: '新建项目', description: '打开项目概况表单', keywords: ['create', '新建', '项目经历'], group: 'CREATE', target: { name: 'prepare', query: { kind: 'PROJECT', create: '1' } } },
  { id: 'create-question', label: '新建题目', description: '打开空白题目表单', keywords: ['create', '新建', '题库'], group: 'CREATE', target: { name: 'bank', query: { create: '1' } } },
  { id: 'create-training', label: '新建训练配置', description: '打开配置表单，之后再确认开始', keywords: ['create', '新建', '文字训练'], group: 'CREATE', target: { name: 'training', query: { create: '1' } } },
  { id: 'create-schedule', label: '新建面试安排', description: '打开面试日程表单', keywords: ['create', '新建', '日程'], group: 'CREATE', target: { name: 'schedule', query: { create: '1' } } },
  { id: 'create-recollection', label: '新建面后回忆', description: '打开回忆补记表单', keywords: ['create', '新建', '回忆'], group: 'CREATE', target: { name: 'recollections', query: { create: '1' } } },
  { id: 'create-memory-card', label: '新建回忆卡', description: '打开空白卡片表单', keywords: ['create', '新建', '卡片'], group: 'CREATE', target: { name: 'memory-cards', query: { create: '1' } } },
]

export function filterWorkspaceCommands(input: string): WorkspaceCommand[] {
  const tokens = input.trim().toLocaleLowerCase().split(/\s+/u).filter(Boolean)
  return workspaceCommands.filter(command => {
    const searchable = [command.label, command.description, ...command.keywords].join(' ').toLocaleLowerCase()
    return tokens.every(token => searchable.includes(token))
  })
}

export function searchPrefillCommands(input: string): WorkspaceCommand[] {
  if (!input.trim() || readSearchPrefill({ q: input }).kind !== 'VALID') return []
  return searchDomains.map(domain => ({ id: `search-${domain.value}`, label: `预填搜索 · ${domain.label}`,
    description: '带入当前检索词，到搜索页后主动查询', keywords: [], group: 'SEARCH',
    target: { name: 'search', query: { domain: domain.value, status: 'ACTIVE', q: input } } }))
}
