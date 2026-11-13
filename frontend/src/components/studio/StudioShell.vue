<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import RBrand from '../../replica/RBrand.vue'
import RIcon from '../../replica/RIcon.vue'
const props = defineProps<{ username?: string | null; unsaved: number; unresolved: number; busy: boolean }>()
const emit = defineEmits<{ command: []; signout: [] }>()
const route = useRoute()
const userMenu = ref(false)
const screen = computed(() => {
  if (route.name === 'knowledge-inbox') return 'inbox'
  if (route.name === 'prepare') return route.query.kind === 'EXPRESSION' ? 'expression' : route.query.kind === 'FACT' || route.query.kind === 'PROJECT' ? 'facts' : 'prepare'
  if (route.name === 'knowledge') return route.query.view === 'detail' ? 'concept' : route.query.edit === '1' ? 'note' : route.query.view === 'list' ? 'library' : route.query.view === 'search' ? 'search' : 'galaxy'
  const names: Record<string, string> = { today: 'today', materials: 'materials', 'preparation-tasks': 'tasks', schedule: 'schedule', boards: 'boards', bank: 'bank', collections: 'collections', training: 'training', 'training-report': 'report', 'memory-cards': 'memory', 'memory-recall': 'recall', 'memory-cram': 'recall', review: 'review', repractice: 'comparison', recollections: 'recollection', growth: 'growth', recent: 'recent', ai: 'ai', 'ai-authorizations': 'authorizations', settings: 'settings', 'data-management': 'data', 'knowledge-merge': 'merge', 'knowledge-packages': 'packages', search: 'search' }
  return names[String(route.name)] ?? 'today'
})
const compact = computed(() => screen.value === 'galaxy')
const navigation = [
  { path: '/today', label: '今日', icon: 'house' }, { path: '/prepare', label: '准备', icon: 'play' },
  { path: '/knowledge', label: '知识', icon: 'book-open' }, { path: '/practice/sessions', prefix: '/practice', label: '训练', icon: 'dumbbell' },
  { path: '/review', label: '复盘', icon: 'refresh-cw' }, { path: '/growth', label: '成长', icon: 'chart-no-axes-combined' },
]
const galaxyIcons: Record<string, string> = {
  '/today': 'house', '/prepare': 'layers', '/knowledge': 'knowledge-network',
  '/practice/sessions': 'play', '/review': 'chart-no-axes-column', '/growth': 'trending-up',
}
const secondary = [
  { path: '/practice/questions', label: '题库', icon: 'database' }, { path: '/knowledge/cards', label: '回忆卡', icon: 'credit-card' },
  { path: '/recent', label: '最近访问', icon: 'clock' }, { path: '/ai', label: 'AI 任务', icon: 'sparkles' },
]
const breadcrumb = computed(() => {
  const labels: Record<string, string> = { today: '', prepare: '准备 / 目标', facts: '准备 / 项目事实', expression: '准备 / 表达素材', materials: '准备 / 附件资料', tasks: '准备 / 任务', schedule: '准备 / 面试安排', galaxy: '知识 › 知识星图', library: '知识 / 全部笔记', note: '知识 / 笔记', boards: '知识 › 白板', bank: '训练 / 题库', collections: '训练 / 题单与路线', training: '训练 / 文字训练', report: '训练 / 结束报告', memory: '回忆卡 / 今日队列', recall: '回忆卡 / 主动回忆', review: '复盘 / 回答与反馈', comparison: '复盘 / 独立复练 / 新旧对照', recollection: '复盘 / 面后回忆', growth: '成长 / 证据', recent: '工作台 / 最近访问', ai: 'AI / 任务与授权', authorizations: 'AI / 授权管理', settings: '', data: '设置 / 数据管理', merge: '知识 / 概念合并', packages: '知识 / 知识包导入', search: '工作台 / 全局搜索' }
  return screen.value === 'inbox' ? '知识 / 收件箱' : screen.value === 'concept' ? '知识 / 知识详情' : labels[screen.value]
})
const saveLabel = computed(() => props.unresolved ? '保存待确认' : props.unsaved ? `${props.unsaved} 处未保存` : '已保存')
function active(item: { path: string; prefix?: string }) {
  if (item.path === '/knowledge' && ['/knowledge/cards', '/knowledge/recall', '/knowledge/cram'].includes(route.path)) return false
  if (item.path === '/practice/sessions' && ['/practice/questions', '/practice/collections'].includes(route.path)) return false
  return route.path === item.path || Boolean(item.prefix && route.path.startsWith(item.prefix))
    || (['/prepare', '/review', '/knowledge'].includes(item.path) && route.path.startsWith(`${item.path}/`))
}
watch(() => route.fullPath, () => { userMenu.value = false })
</script>
<template>
  <div class="app-shell studio-product" :class="[`screen-${screen}`, { 'studio-compact': compact }]">
    <aside class="app-nav studio-sidebar">
      <RouterLink class="studio-brand" to="/today" aria-label="问阶 · 今日"><RBrand :variant="compact ? 9 : 2" /></RouterLink>
      <nav class="studio-primary-nav" aria-label="主导航">
        <RouterLink v-for="item in navigation" :key="item.path" :to="item.path" class="studio-nav-item" :class="{ 'studio-selected': active(item) }" :aria-current="active(item) ? 'page' : undefined"><RIcon :name="compact ? galaxyIcons[item.path]! : screen === 'today' && item.path === '/today' ? 'calendar-days' : item.icon" :size="compact ? 34 : 28" /><span>{{ item.label }}</span></RouterLink>
        <template v-if="!compact"><div class="studio-nav-rule" /><RouterLink v-for="item in secondary" :key="item.path" :to="item.path" class="studio-nav-item studio-secondary" :class="{ 'studio-selected': route.path === item.path }"><RIcon :name="item.icon" :size="27" /><span>{{ item.label }}</span></RouterLink></template>
      </nav>
      <button class="studio-avatar studio-sidebar-avatar" title="账户与设置" aria-label="账户与设置" :aria-expanded="userMenu" @click="userMenu = !userMenu">林</button>
    </aside>
    <header class="studio-topbar">
      <div v-if="breadcrumb" class="studio-breadcrumb"><RIcon v-if="compact || ['recent', 'review', 'comparison'].includes(screen)" name="house" :size="24" /><span>{{ breadcrumb }}</span></div>
      <button class="studio-global-search" aria-haspopup="dialog" @click="emit('command')"><RIcon name="search" :size="25" /><span>{{ compact ? '搜索知识点、或 ⌘K' : screen === 'today' ? '搜索题目、知识点或任务...' : '搜索知识、笔记、题目...' }}</span><kbd v-if="!compact">⌘K</kbd></button>
      <div class="studio-top-status"><span class="studio-saved" :class="{ pending: unsaved || unresolved }" role="status"><RIcon :name="unsaved || unresolved ? 'clock' : 'check'" :size="26" />{{ saveLabel }}</span><RouterLink to="/ai" class="studio-icon-button" title="任务与通知"><RIcon name="bell" :size="28" /></RouterLink><button class="studio-avatar" title="账户与设置" :aria-expanded="userMenu" @click="userMenu = !userMenu">林</button></div>
    </header>
    <div v-if="userMenu" class="studio-account-backdrop" @click.self="userMenu = false" @keydown.esc="userMenu = false"><section class="studio-account-menu" aria-label="账户与设置"><strong>{{ username }}</strong><RouterLink to="/settings"><RIcon name="settings" />设置与数据</RouterLink><RouterLink to="/settings/data"><RIcon name="database" />数据管理</RouterLink><RouterLink to="/knowledge/inbox"><RIcon name="inbox" />知识收件箱</RouterLink><RouterLink to="/knowledge/packages"><RIcon name="folder" />知识包导入</RouterLink><RouterLink to="/knowledge/merge"><RIcon name="share-2" />概念合并</RouterLink><RouterLink to="/ai/authorizations"><RIcon name="shield-check" />AI 授权管理</RouterLink><button :disabled="busy" @click="emit('signout')"><RIcon name="log-out" />退出登录</button></section></div>
    <main id="main-content" class="app-main studio-main"><slot /></main>
    <nav class="studio-mobile-nav" aria-label="移动端主导航"><RouterLink v-for="item in navigation.slice(0, 5)" :key="item.path" :to="item.path" :class="{ selected: active(item) }"><RIcon :name="item.icon" :size="24" /><span>{{ item.label }}</span></RouterLink></nav>
  </div>
</template>
