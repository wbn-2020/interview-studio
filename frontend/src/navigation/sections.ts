import type { RouteLocationNormalizedLoaded, RouteLocationRaw } from 'vue-router'

export type SectionKey = 'today' | 'prepare' | 'knowledge' | 'practice' | 'review' | 'growth' | 'recent' | 'ai' | 'settings' | 'search'
export type SectionLink = { key: SectionKey; label: string; icon: string; to: string }
export const primarySections: SectionLink[] = [
  { key: 'today', label: '今日', icon: 'house', to: '/today' },
  { key: 'prepare', label: '准备', icon: 'briefcase', to: '/prepare' },
  { key: 'knowledge', label: '知识', icon: 'book-open', to: '/knowledge?view=list' },
  { key: 'practice', label: '训练', icon: 'play', to: '/practice/sessions' },
  { key: 'review', label: '复盘', icon: 'refresh-cw', to: '/review' },
  { key: 'growth', label: '成长', icon: 'chart-no-axes-combined', to: '/growth' },
]
export const utilitySections: SectionLink[] = [
  { key: 'recent', label: '最近访问', icon: 'clock', to: '/recent' },
  { key: 'ai', label: 'AI 任务', icon: 'sparkles', to: '/ai' },
  { key: 'settings', label: '设置', icon: 'settings', to: '/settings' },
]
const searchSection: SectionLink = { key: 'search', label: '搜索', icon: 'search', to: '/search' }

export function sectionFor(route: RouteLocationNormalizedLoaded): SectionLink {
  return [...primarySections, ...utilitySections, searchSection].find(item => route.path === `/${item.key}` || route.path.startsWith(`/${item.key}/`)) ?? primarySections[0]!
}

export type WorkspaceTab = { label: string; to: RouteLocationRaw; active: boolean }
export function tabsFor(route: RouteLocationNormalizedLoaded): WorkspaceTab[] {
  const name = route.name, query = route.query
  switch (sectionFor(route).key) {
    case 'prepare': return [
      ...[['TARGET', '目标'], ['PROJECT', '项目'], ['FACT', '项目事实'], ['EXPRESSION', '表达素材']].map(([kind, label]) => ({
        label: label!, to: { name: 'prepare', query: { kind } }, active: name === 'prepare' && (query.kind ?? 'TARGET') === kind,
      })),
      { label: '附件资料', to: '/prepare/materials', active: name === 'materials' },
      { label: '准备任务', to: '/prepare/tasks', active: name === 'preparation-tasks' },
      { label: '面试安排', to: '/prepare/schedule', active: name === 'schedule' },
    ]
    case 'knowledge': {
      // 列表与星图只是同一份知识的不同视图；切换时保留当前对象，离开编辑仍经过既有保稿守卫。
      const knowledgeTo = (view: string): RouteLocationRaw => ({ name: 'knowledge', query: name === 'knowledge'
        ? { ...query, view, edit: undefined, panel: undefined, create: undefined } : { view } })
      return [
        { label: '知识目录', to: knowledgeTo('list'), active: name === 'knowledge' && (['list', 'search', 'detail'].includes(String(query.view)) || Boolean(query.create)) },
        { label: '知识星图', to: knowledgeTo('graph'), active: name === 'knowledge' && !['list', 'search', 'detail'].includes(String(query.view)) && !query.create },
        { label: '白板', to: '/knowledge/boards', active: name === 'boards' },
        { label: '回忆卡', to: '/knowledge/cards', active: ['memory-cards', 'memory-recall', 'memory-cram'].includes(String(name)) },
        { label: '收件箱', to: '/knowledge/inbox', active: name === 'knowledge-inbox' },
        { label: '知识包', to: '/knowledge/packages', active: name === 'knowledge-packages' },
        { label: '概念合并', to: '/knowledge/merge', active: name === 'knowledge-merge' },
      ]
    }
    case 'practice': return [
      { label: '文字训练', to: '/practice/sessions', active: name === 'training' },
      { label: '题库', to: '/practice/questions', active: name === 'bank' },
      { label: '题单', to: '/practice/collections', active: name === 'collections' && query.kind !== 'ROUTE' },
      { label: '学习路线', to: '/practice/collections?kind=ROUTE', active: name === 'collections' && query.kind === 'ROUTE' },
    ]
    case 'review': return [
      { label: '回答与反馈', to: '/review', active: name === 'review' },
      { label: '复练对照', to: '/review/repractice', active: name === 'repractice' },
      { label: '面后回忆', to: '/review/recollections', active: name === 'recollections' },
      ...(name === 'training-report' ? [{ label: '训练报告', to: route.fullPath, active: true }] : []),
    ]
    case 'ai': return [{ label: '任务记录', to: '/ai', active: name === 'ai' }, { label: '授权管理', to: '/ai/authorizations', active: name === 'ai-authorizations' }]
    case 'settings': return [{ label: '个人偏好', to: '/settings', active: name === 'settings' }, { label: '数据管理', to: '/settings/data', active: name === 'data-management' }]
    default: return []
  }
}
