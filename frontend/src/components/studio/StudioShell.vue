<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import RBrand from '../../replica/RBrand.vue'
import RIcon from '../../replica/RIcon.vue'
const props = defineProps<{ username?: string | null; unsaved: number; unresolved: number; busy: boolean }>()
const emit = defineEmits<{ command: []; signout: [] }>()
const route = useRoute()
const userMenu = ref(false)
const avatarLabel = computed(() => Array.from(props.username?.trim() || '我')[0]!.toLocaleUpperCase())
const screen = computed(() => {
  if (route.name === 'knowledge-inbox') return 'inbox'
  if (route.name === 'prepare') return route.query.kind === 'EXPRESSION' ? 'expression' : route.query.kind === 'FACT' || route.query.kind === 'PROJECT' ? 'facts' : 'prepare'
  if (route.name === 'knowledge') return route.query.view === 'detail' ? 'concept' : route.query.edit === '1' ? 'note' : route.query.view === 'list' ? 'library' : route.query.view === 'search' ? 'search' : 'galaxy'
  const names: Record<string, string> = { today: 'today', materials: 'materials', 'preparation-tasks': 'tasks', schedule: 'schedule', boards: 'boards', bank: 'bank', collections: 'collections', training: 'training', 'training-report': 'report', 'memory-cards': 'memory', 'memory-recall': 'recall', 'memory-cram': 'recall', review: 'review', repractice: 'comparison', recollections: 'recollection', growth: 'growth', recent: 'recent', ai: 'ai', 'ai-authorizations': 'authorizations', settings: 'settings', 'data-management': 'data', 'knowledge-merge': 'merge', 'knowledge-packages': 'packages', search: 'search' }
  return names[String(route.name)] ?? 'today'
})
const compact = computed(() => screen.value === 'galaxy')
const navigationBase = [
  { path: '/today', label: '今日', icon: 'house' }, { path: '/prepare', label: '准备', icon: 'play' },
  { path: '/knowledge', label: '知识', icon: 'book-open' }, { path: '/practice/sessions', prefix: '/practice', label: '训练', icon: 'dumbbell' },
  { path: '/review', label: '复盘', icon: 'refresh-cw' }, { path: '/growth', label: '成长', icon: 'chart-no-axes-combined' },
]
const navigation = computed(() => screen.value === 'recent' ? [
  ...navigationBase.slice(0, 4), { path: '/recent', label: '最近访问', icon: 'clock' },
  { ...navigationBase[4]!, icon: 'chart-no-axes-column' }, { ...navigationBase[5]!, icon: 'sprout' },
] : screen.value === 'ai' ? [
  ...navigationBase.slice(0, 4), { path: '/ai', label: 'AI 任务', icon: 'sparkles' },
  { ...navigationBase[4]!, icon: 'chart-no-axes-column' }, { ...navigationBase[5]!, icon: 'sprout' },
] : screen.value === 'settings' ? navigationBase.map(item => ({ ...item, icon: settingsIcons[item.path] ?? item.icon })) : navigationBase)
const galaxyIcons: Record<string, string> = {
  '/today': 'house', '/prepare': 'layers', '/knowledge': 'knowledge-network',
  '/practice/sessions': 'play', '/review': 'chart-no-axes-column', '/growth': 'trending-up',
}
const inboxIcons: Record<string, string> = { '/review': 'chart-no-axes-column', '/growth': 'leaf' }
const noteIcons: Record<string, string> = { '/today': 'house', '/prepare': 'book-open', '/knowledge': 'layers', '/practice/sessions': 'play', '/review': 'chart-no-axes-column', '/growth': 'sprout', '/practice/questions': 'layout-grid', '/ai': 'zap' }
const conceptIcons: Record<string, string> = { '/practice/sessions': 'chart-no-axes-column', '/growth': 'sprout' }
const bankIcons: Record<string, string> = { '/prepare': 'briefcase' }
const settingsIcons: Record<string, string> = { '/prepare': 'file-text', '/practice/sessions': 'play', '/review': 'history', '/growth': 'trending-up' }
const iconVariant = computed(() => screen.value === 'inbox' ? 'inbox' : screen.value === 'library' ? 'library' : screen.value === 'note' ? 'note' : screen.value === 'concept' ? 'detail' : screen.value === 'bank' ? 'bank' : screen.value === 'recent' ? 'recent' : screen.value === 'ai' ? 'authorization' : screen.value === 'settings' ? 'settings' : undefined)
const secondaryBase = [
  { path: '/practice/questions', label: '题库', icon: 'database' }, { path: '/knowledge/cards', label: '回忆卡', icon: 'credit-card' },
  { path: '/recent', label: '最近访问', icon: 'clock' }, { path: '/ai', label: 'AI 任务', icon: 'sparkles' },
]
const secondary = computed(() => screen.value === 'recent' ? secondaryBase.filter(item => item.path !== '/recent')
  .map(item => ({ ...item, icon: item.path === '/practice/questions' ? 'layout-grid' : item.path === '/ai' ? 'zap' : item.icon }))
  : screen.value === 'ai' ? secondaryBase.map(item => ({ ...item, icon: item.path === '/practice/questions' ? 'layers' : item.path === '/ai' ? 'settings' : item.icon })) : secondaryBase)
const breadcrumb = computed(() => {
  const labels: Record<string, string> = { today: '', prepare: '准备 / 目标', facts: '准备 / 项目事实', expression: '准备 / 表达素材', materials: '准备 / 附件资料', tasks: '准备 / 任务', schedule: '准备 / 面试安排', galaxy: '知识 › 知识星图', library: '知识 / 全部笔记', note: '知识 / 笔记', boards: '知识 › 白板', bank: '训练 / 题库', collections: '训练 / 题单与路线', training: '训练 / 文字训练', report: '训练 / 结束报告', memory: '回忆卡 / 今日队列', recall: '回忆卡 / 主动回忆', review: '复盘 / 回答与反馈', comparison: '复盘 / 独立复练 / 新旧对照', recollection: '复盘 / 面后回忆', growth: '成长 / 证据', recent: '工作台 / 最近访问', ai: 'AI / 任务与授权', authorizations: 'AI / 授权管理', settings: '', data: '设置 / 数据管理', merge: '知识 / 概念合并', packages: '知识 / 知识包导入', search: '工作台 / 全局搜索' }
  return screen.value === 'inbox' ? '知识 / 收件箱' : screen.value === 'concept' ? '知识 / 知识详情' : labels[screen.value]
})
const saveLabel = computed(() => props.unresolved ? '保存待确认' : props.unsaved ? `${props.unsaved} 处未保存` : '已保存')
function active(item: { path: string; prefix?: string }) {
  if (item.path === '/knowledge' && ['/knowledge/cards', '/knowledge/recall', '/knowledge/cram'].includes(route.path)) return false
  if (item.path === '/practice/sessions' && route.path === '/practice/questions') return true
  if (item.path === '/practice/sessions' && route.path === '/practice/collections') return false
  return route.path === item.path || Boolean(item.prefix && route.path.startsWith(item.prefix))
    || (['/prepare', '/review', '/knowledge'].includes(item.path) && route.path.startsWith(`${item.path}/`))
}
watch(() => route.fullPath, () => { userMenu.value = false })
</script>
<template>
  <div class="app-shell studio-product" :class="[`screen-${screen}`, { 'studio-compact': compact }]">
    <aside class="app-nav studio-sidebar">
      <RouterLink class="studio-brand" to="/today" aria-label="问阶 · 今日"><RBrand :variant="compact ? 9 : screen === 'inbox' ? 30 : screen === 'library' ? 10 : screen === 'note' ? 11 : screen === 'concept' ? 12 : screen === 'bank' ? 14 : screen === 'recent' ? 26 : screen === 'ai' ? 27 : screen === 'settings' ? 28 : 2" /></RouterLink>
      <nav class="studio-primary-nav" aria-label="主导航">
        <RouterLink v-for="item in navigation" :key="item.path" :to="item.path" class="studio-nav-item" :class="{ 'studio-selected': active(item) }" :aria-current="active(item) ? 'page' : undefined"><RIcon :name="compact ? galaxyIcons[item.path]! : screen === 'note' ? noteIcons[item.path] ?? item.icon : screen === 'concept' ? conceptIcons[item.path] ?? item.icon : screen === 'bank' ? bankIcons[item.path] ?? item.icon : screen === 'inbox' ? inboxIcons[item.path] ?? item.icon : ['today', 'library'].includes(screen) && item.path === '/today' ? 'calendar-days' : item.icon" :variant="iconVariant" :size="compact ? 34 : ['ai', 'inbox'].includes(screen) ? 30 : 28" :stroke-width="screen === 'bank' ? 2.1 : screen === 'ai' ? 1.9 : 1.7" /><span>{{ item.label }}</span></RouterLink>
        <template v-if="!compact"><div class="studio-nav-rule" /><RouterLink v-for="item in secondary" :key="item.path" :to="item.path" class="studio-nav-item studio-secondary" :class="{ 'studio-selected': route.path === item.path && !['bank', 'ai'].includes(screen) }"><RIcon :name="screen === 'note' ? noteIcons[item.path] ?? item.icon : screen === 'inbox' && item.path === '/practice/questions' ? 'layers' : item.icon" :variant="iconVariant" :size="['ai', 'inbox'].includes(screen) ? 29 : 27" :stroke-width="screen === 'bank' ? 2.1 : screen === 'ai' ? 1.9 : 1.7" /><span>{{ item.label }}</span></RouterLink></template>
      </nav>
      <button class="studio-avatar studio-sidebar-avatar" title="账户与设置" aria-label="账户与设置" :aria-expanded="userMenu" @click="userMenu = !userMenu">{{ avatarLabel }}</button>
    </aside>
    <header class="studio-topbar">
      <RouterLink v-if="screen === 'inbox'" class="studio-breadcrumb studio-inbox-breadcrumb" to="/knowledge?view=list"><span>{{ breadcrumb }}</span><RIcon name="chevron-down" :size="23" /></RouterLink>
      <div v-else-if="screen === 'recent'" class="studio-breadcrumb"><RIcon class="studio-recent-back" name="chevron-left" :size="19" /><RIcon name="house" variant="recent" :size="25" /><span class="muted">工作台</span><span>/</span><span>最近访问</span></div>
      <div v-else-if="screen === 'ai'" class="studio-breadcrumb"><RIcon name="house" variant="authorization" :size="28" /><span>AI</span><span>/</span><span>任务与授权</span></div>
      <div v-else-if="breadcrumb" class="studio-breadcrumb"><RIcon v-if="compact || ['recent', 'review', 'comparison', 'library'].includes(screen)" name="house" :size="24" /><span>{{ breadcrumb }}</span></div>
      <button class="studio-global-search" aria-haspopup="dialog" @click="emit('command')"><RIcon name="search" :size="25" /><span>{{ compact ? '搜索知识点、或 ⌘K' : screen === 'inbox' ? '搜索知识...' : screen === 'library' ? '搜索笔记、概念、标签...' : screen === 'today' ? '搜索题目、知识点或任务...' : screen === 'recent' ? '搜索知识、训练、问题...' : screen === 'ai' ? '搜索...' : screen === 'settings' ? '搜索知识、概念或问题...' : '搜索知识、笔记、题目...' }}</span><kbd v-if="!compact && screen !== 'recent'">⌘K</kbd></button>
      <div class="studio-top-status"><span class="studio-saved" :class="{ pending: unsaved || unresolved }" role="status"><RIcon :name="unsaved || unresolved ? 'clock' : screen === 'recent' ? 'bookmark' : 'check'" :size="26" />{{ saveLabel }}</span><RouterLink to="/ai" class="studio-icon-button" title="任务与通知"><RIcon name="bell" :variant="screen === 'library' ? 'library' : screen === 'note' ? 'note' : screen === 'concept' ? 'detail' : screen === 'recent' ? 'recent' : undefined" :size="screen === 'recent' ? 32 : 28" /></RouterLink><button class="studio-avatar" title="账户与设置" aria-label="账户与设置" :aria-expanded="userMenu" @click="userMenu = !userMenu">{{ avatarLabel }}</button></div>
    </header>
    <div v-if="userMenu" class="studio-account-backdrop" @click.self="userMenu = false" @keydown.esc="userMenu = false"><section class="studio-account-menu" aria-label="账户与设置"><strong>{{ username }}</strong><RouterLink to="/settings"><RIcon name="settings" />设置与数据</RouterLink><RouterLink to="/settings/data"><RIcon name="database" />数据管理</RouterLink><RouterLink to="/knowledge/inbox"><RIcon name="inbox" />知识收件箱</RouterLink><RouterLink to="/knowledge/packages"><RIcon name="folder" />知识包导入</RouterLink><RouterLink to="/knowledge/merge"><RIcon name="share-2" />概念合并</RouterLink><RouterLink to="/ai/authorizations"><RIcon name="shield-check" />AI 授权管理</RouterLink><button :disabled="busy" @click="emit('signout')"><RIcon name="log-out" />退出登录</button></section></div>
    <main id="main-content" class="app-main studio-main"><slot /></main>
    <nav class="studio-mobile-nav" aria-label="移动端主导航"><RouterLink v-for="item in navigationBase" :key="item.path" :to="item.path" :class="{ selected: active(item) }"><RIcon :name="item.icon" :size="22" /><span>{{ item.label }}</span></RouterLink></nav>
  </div>
</template>
