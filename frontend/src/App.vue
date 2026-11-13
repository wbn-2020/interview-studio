<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NButton, NConfigProvider, NInput, NMessageProvider, NModal, zhCN, dateZhCN, darkTheme } from 'naive-ui'
import { read, login, logout, errorText, clearCsrf } from './api/client'
import type { Schema } from './api/client'
import { queryClient } from './api/queryClient'
import { useWorkspaceStore } from './state/workspace'
import { useTrainingAccessStore } from './state/trainingAccess'
import { useAiUpdatesStore } from './state/aiUpdates'
import { useRecentStore } from './state/recent'
import { usePreferencesStore } from './state/preferences'
import { recentIdValid } from './api/recent'
import TrainingAccessNotice from './components/training/TrainingAccessNotice.vue'
import GlobalCommandPalette from './components/GlobalCommandPalette.vue'

const session = ref<Schema['Session'] | null>(null)
const entered = ref(false)
const loading = ref(true)
const busy = ref(false)
const username = ref('')
const password = ref('')
const error = ref('')
const workspace = useWorkspaceStore()
const trainingAccess = useTrainingAccessStore()
const aiUpdates = useAiUpdatesStore()
const recent = useRecentStore()
const preferences = usePreferencesStore()
const unsavedCount = computed(() => [...workspace.changes.values()].filter(value => value.dirty).length)
const unresolvedCount = computed(() => [...workspace.changes.values()].filter(value => value.locked).length)
const route = useRoute()
const authorizationManagement = computed(() => route.name === 'ai-authorizations')
const safeManagementShell = computed(() => authorizationManagement.value || route.name === 'data-management')
const trainingBlocked = computed(() => !safeManagementShell.value && (trainingAccess.checking || trainingAccess.challenge
  || workspace.changes.get('training-access-command')?.locked
  || (trainingAccess.restricted && trainingAccess.needsGuard(route.name, route.query.item, route.query.create))))
let trainingChannel: BroadcastChannel | undefined
const navigation = [
  { path: '/today', label: '今日', mark: '◷' }, { path: '/prepare', label: '准备', mark: '▤' },
  { path: '/knowledge', label: '知识', mark: '✧' }, { path: '/practice', label: '训练', mark: '▷' },
  { path: '/review', label: '复盘', mark: '↶' }, { path: '/growth', label: '成长', mark: '↗' },
]
const preparationNavigation = [
  { path: '/prepare', label: '准备材料' }, { path: '/prepare/materials', label: '附件资料' },
  { path: '/prepare/tasks', label: '准备任务' }, { path: '/prepare/schedule', label: '面试安排' },
]
const practiceNavigation = [
  { path: '/practice/questions', label: '题库' }, { path: '/practice/collections', label: '题单与路线' },
  { path: '/practice/sessions', label: '文字训练' },
]
const theme = computed(() => ({ common: { primaryColor: preferences.isDark ? '#8bcbb0' : '#246450', primaryColorHover: preferences.isDark ? '#a2ddc3' : '#357b65', primaryColorPressed: preferences.isDark ? '#74b298' : '#194e3e', borderRadius: '7px', fontFamily: '"Segoe UI", "Microsoft YaHei", sans-serif', fontSize: `${preferences.current.fontSize}px` } }))

async function refreshSession() {
  loading.value = true
  error.value = ''
  try {
    session.value = await read<Schema['Session']>('/auth/session')
    recent.setOwner(session.value.authenticated === true && recentIdValid(session.value.ownerId) ? session.value.ownerId : undefined)
    if (session.value.authenticated) { workspace.activateSession(); entered.value = true }
    else workspace.expireSession()
  } catch (reason) { error.value = errorText(reason) }
  finally { loading.value = false }
}
async function signIn() {
  busy.value = true
  error.value = ''
  try {
    await login(username.value, password.value)
    password.value = ''
    await refreshSession()
    await queryClient.invalidateQueries()
  } catch (reason) { error.value = errorText(reason); password.value = '' }
  finally { busy.value = false }
}
function expired() {
  clearCsrf()
  recent.setOwner()
  workspace.expireSession()
  queryClient.clear()
  session.value = { authenticated: false, username: null, ownerId: null }
  error.value = '会话已过期，请重新登录。当前编辑内容仍保留在此页面。'
}
async function signOut() {
  if (!workspace.canLeave()) return
  busy.value = true
  try {
    await logout()
    recent.setOwner()
    workspace.expireSession()
    queryClient.clear()
    entered.value = false
    session.value = { authenticated: false, username: null, ownerId: null }
  } catch (reason) { error.value = errorText(reason) }
  finally { busy.value = false }
}
function checkTraining() {
  trainingAccess.invalidate()
  if (workspace.sessionActive && document.visibilityState === 'visible') void trainingAccess.refresh()
}
function trainingChanged() { trainingChannel?.postMessage('changed'); checkTraining() }
function assistanceRequired() { trainingAccess.challenge = true; checkTraining() }
function syncAiUpdates() {
  const comparisonId = route.name === 'repractice' && typeof route.query.comparison === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(route.query.comparison)
    ? route.query.comparison : undefined
  const page = route.name === 'ai' ? 'ai' : route.name === 'training' ? 'training' : comparisonId ? 'comparison' : undefined
  aiUpdates.setContext(page, page === 'training' && route.query.create !== '1' && typeof route.query.item === 'string' ? route.query.item : undefined, comparisonId)
}
function pauseAiUpdates() { aiUpdates.setContext() }
watch(() => workspace.sessionActive, value => { trainingAccess.invalidate(); if (value) void trainingAccess.refresh() })
watch([() => workspace.sessionActive, () => workspace.sessionEpoch, () => route.name, () => route.query.item, () => route.query.create, () => route.query.comparison], syncAiUpdates, { immediate: true, flush: 'post' })
onMounted(() => {
  preferences.start()
  recent.start()
  if ('BroadcastChannel' in window) {
    trainingChannel = new BroadcastChannel('wenjie-training-condition')
    trainingChannel.onmessage = checkTraining
  }
  window.addEventListener('studio:session-expired', expired)
  window.addEventListener('studio:training-changed', trainingChanged)
  window.addEventListener('studio:training-access-required', assistanceRequired)
  window.addEventListener('focus', checkTraining)
  window.addEventListener('pageshow', checkTraining)
  window.addEventListener('pagehide', checkTraining)
  document.addEventListener('visibilitychange', checkTraining)
  document.addEventListener('visibilitychange', syncAiUpdates)
  window.addEventListener('pageshow', syncAiUpdates)
  window.addEventListener('pagehide', pauseAiUpdates)
  void refreshSession()
})
onUnmounted(() => {
  preferences.stop()
  recent.stop()
  pauseAiUpdates()
  trainingChannel?.close()
  window.removeEventListener('studio:session-expired', expired)
  window.removeEventListener('studio:training-changed', trainingChanged)
  window.removeEventListener('studio:training-access-required', assistanceRequired)
  window.removeEventListener('focus', checkTraining)
  window.removeEventListener('pageshow', checkTraining)
  window.removeEventListener('pagehide', checkTraining)
  document.removeEventListener('visibilitychange', checkTraining)
  document.removeEventListener('visibilitychange', syncAiUpdates)
  window.removeEventListener('pageshow', syncAiUpdates)
  window.removeEventListener('pagehide', pauseAiUpdates)
})
</script>

<template>
  <NConfigProvider :theme="preferences.isDark ? darkTheme : null" :theme-overrides="theme" :locale="zhCN" :date-locale="dateZhCN">
    <NMessageProvider>
      <div v-if="entered" class="app-shell" :inert="!session?.authenticated || undefined">
        <aside class="app-nav">
          <a class="brand" href="/knowledge" @click.prevent="$router.push('/knowledge')"><span class="brand-symbol">阶</span><span>问阶<small>Interview Studio</small></span></a>
          <nav aria-label="主导航">
            <RouterLink v-for="item in navigation" :key="item.path" :to="item.path" :class="{ 'router-link-active': ['/prepare', '/practice', '/knowledge'].includes(item.path) && $route.path.startsWith(item.path) }"><span aria-hidden="true">{{ item.mark }}</span>{{ item.label }}</RouterLink>
          </nav>
          <nav aria-label="资料与个人设置"><RouterLink :to="{ name: 'knowledge-packages' }">知识包导入</RouterLink><RouterLink :to="{ name: 'knowledge-merge' }">概念合并</RouterLink><RouterLink :to="{ name: 'settings' }">个人设置</RouterLink><RouterLink :to="{ name: 'data-management' }">数据管理</RouterLink></nav>
          <div class="nav-footer"><GlobalCommandPalette :enabled="Boolean(session?.authenticated && workspace.sessionActive && !loading && !busy)" /><RouterLink :to="{ name: 'search' }">全局搜索</RouterLink><RouterLink :to="{ name: 'recent' }">最近访问<span v-if="recent.pendingCount"> · {{ recent.pendingCount }} 待确认</span></RouterLink><span v-if="recent.message" role="status">{{ recent.message }}</span><RouterLink to="/ai">AI 任务与用量</RouterLink><RouterLink :to="{ name: 'ai-authorizations' }">AI 授权管理</RouterLink><span>个人知识与面试工作台</span><span class="muted">{{ session?.username }}</span><NButton quaternary size="small" :loading="busy" @click="signOut">退出登录</NButton></div>
        </aside>
        <main id="main-content" class="app-main">
          <div v-if="preferences.current.saveRecoveryNotice && (unsavedCount || unresolvedCount)" class="preference-save-notice" role="status">{{ unsavedCount }} 处未保存输入，{{ unresolvedCount }} 个保存中或结果待确认操作。请完成保存或用原请求恢复；关闭页面可能丢失尚未保存的内容。</div>
          <nav v-if="$route.path.startsWith('/prepare')" class="preparation-navigation" aria-label="准备工作区"><RouterLink v-for="item in preparationNavigation" :key="item.path" :to="item.path">{{ item.label }}</RouterLink></nav>
          <nav v-if="$route.path.startsWith('/practice')" class="preparation-navigation" aria-label="训练工作区"><RouterLink v-for="item in practiceNavigation" :key="item.path" :to="item.path">{{ item.label }}</RouterLink></nav>
          <TrainingAccessNotice v-show="!authorizationManagement" />
          <div v-show="!trainingBlocked" :inert="trainingBlocked || undefined">
            <NConfigProvider :namespace="trainingBlocked ? 'studio-material-layer-hidden' : 'studio-material-layer'"><RouterView /></NConfigProvider>
          </div>
        </main>
      </div>
      <NModal :show="!session?.authenticated" :mask-closable="false" :close-on-esc="false" :z-index="50" aria-labelledby="login-title">
        <form class="login-form" @submit.prevent="signIn">
          <div class="brand-symbol">阶</div>
          <h1 id="login-title">{{ entered ? '回到你的工作台' : '欢迎来到问阶' }}</h1>
          <p>把每一次思考，留在自己的知识宇宙。</p>
          <div v-if="loading" class="loading-block" role="status">正在确认会话…</div>
          <template v-else>
            <p v-if="error" class="notice error" role="alert">{{ error }}</p>
            <label for="username">登录名</label>
            <NInput id="username" v-model:value="username" :disabled="busy" :input-props="{ autocomplete: 'username', required: true }" placeholder="服务器配置的登录名" />
            <label for="password">密码</label>
            <NInput id="password" v-model:value="password" type="password" :disabled="busy" :input-props="{ autocomplete: 'current-password', required: true }" placeholder="输入密码" />
            <NButton type="primary" attr-type="submit" block :loading="busy" :disabled="!username || !password">进入工作台</NButton>
            <NButton v-if="error" text @click="refreshSession">重新检查连接</NButton>
            <small class="muted">个人部署 · 账号由服务器管理员配置</small>
          </template>
        </form>
      </NModal>
      <div v-if="session?.authenticated && error" class="global-notice" role="alert">{{ error }} <button @click="error = ''">关闭</button></div>
    </NMessageProvider>
  </NConfigProvider>
</template>

<style scoped>
.preparation-navigation { display: flex; gap: 24px; max-width: 1280px; margin: 0 auto 24px; border-bottom: 1px solid var(--line); overflow-x: auto; }
.preparation-navigation a { padding: 8px 0 12px; border-bottom: 2px solid transparent; color: var(--muted); white-space: nowrap; font-size: 13px; }
.preparation-navigation a.router-link-exact-active { color: var(--brand-strong); border-bottom-color: var(--brand); font-weight: 650; }
</style>
