<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NConfigProvider, NMessageProvider, NModal, zhCN, dateZhCN, darkTheme } from 'naive-ui'
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
import StudioShell from './components/studio/StudioShell.vue'
import StudioLogin from './components/studio/StudioLogin.vue'

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
const routeSurface = ref<HTMLElement>()
const guardedHeight = ref(0)
const retainingLayout = computed(() => trainingBlocked.value && trainingAccess.checking && !trainingAccess.error)
watch(trainingBlocked, blocked => {
  // 内容立即遮挡，但保留核对前的占位高度，避免窗口恢复时文档塌陷和滚动位置被重置。
  if (blocked && routeSurface.value) guardedHeight.value = routeSurface.value.getBoundingClientRect().height
}, { flush: 'sync' })
let trainingChannel: BroadcastChannel | undefined
const commandPalette = ref<InstanceType<typeof GlobalCommandPalette>>()
const theme = computed(() => ({
  common: {
    primaryColor: preferences.isDark ? '#dda16a' : '#a36429', primaryColorHover: '#efb77e', primaryColorPressed: '#bb7d44',
    primaryColorSuppl: '#dda16a', borderRadius: '6px', fontFamily: '"Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif',
    fontSize: '14px', bodyColor: preferences.isDark ? '#0b0a08' : '#f1eee8',
    cardColor: preferences.isDark ? '#211c16' : '#fffdf9', modalColor: preferences.isDark ? '#211c16' : '#fffdf9',
    popoverColor: preferences.isDark ? '#282119' : '#fffdf9', tableColor: preferences.isDark ? '#1b1814' : '#fffdf9',
    borderColor: preferences.isDark ? '#4a3d2e' : '#d9cdbc', textColorBase: preferences.isDark ? '#f3eee5' : '#30271e',
  },
  Button: { heightMedium: '34px', heightSmall: '28px', heightLarge: '40px', fontSizeMedium: '13px', fontSizeSmall: '12px', borderRadiusMedium: '6px', borderRadiusSmall: '5px', borderRadiusLarge: '7px', textColorPrimary: '#201307', textColorHoverPrimary: '#201307', textColorPressedPrimary: '#201307' },
  Input: { heightMedium: '34px', fontSizeMedium: '14px', color: preferences.isDark ? '#171410' : '#fffdf9', borderRadius: '6px' },
  Select: { peers: { InternalSelection: { heightMedium: '34px', fontSizeMedium: '14px', borderRadius: '6px' } } },
  Card: { borderRadius: '10px', titleFontSizeMedium: '18px' },
}))

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
let windowActive = document.visibilityState === 'visible' && document.hasFocus()
let windowSuspended = false
function leaveWindow() { windowActive = false }
function suspendTraining() {
  leaveWindow()
  if (windowSuspended) return
  windowSuspended = true
  trainingAccess.invalidate({ refreshData: false })
}
function checkTraining() {
  if (document.visibilityState !== 'visible') { suspendTraining(); return }
  if (!document.hasFocus() || windowActive) return
  // 一次前台恢复只核对一次；后续 focus/pageshow 不再次遮挡已经核实的页面。
  windowActive = true
  if (!windowSuspended && !trainingAccess.checking) trainingAccess.invalidate({ refreshData: false })
  windowSuspended = false
  if (workspace.sessionActive) void trainingAccess.refresh()
}
function invalidateTraining() {
  trainingAccess.invalidate()
  if (workspace.sessionActive && document.visibilityState === 'visible') void trainingAccess.refresh()
}
function trainingChanged() { trainingChannel?.postMessage('changed'); invalidateTraining() }
function assistanceRequired() { trainingAccess.challenge = true; invalidateTraining() }
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
    trainingChannel.onmessage = invalidateTraining
  }
  window.addEventListener('studio:session-expired', expired)
  window.addEventListener('studio:training-changed', trainingChanged)
  window.addEventListener('studio:training-access-required', assistanceRequired)
  window.addEventListener('focus', checkTraining)
  window.addEventListener('blur', leaveWindow)
  window.addEventListener('pageshow', checkTraining)
  window.addEventListener('pagehide', suspendTraining)
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
  window.removeEventListener('blur', leaveWindow)
  window.removeEventListener('pageshow', checkTraining)
  window.removeEventListener('pagehide', suspendTraining)
  document.removeEventListener('visibilitychange', checkTraining)
  document.removeEventListener('visibilitychange', syncAiUpdates)
  window.removeEventListener('pageshow', syncAiUpdates)
  window.removeEventListener('pagehide', pauseAiUpdates)
})
</script>

<template>
  <NConfigProvider :theme="preferences.isDark ? darkTheme : null" :theme-overrides="theme" :locale="zhCN" :date-locale="dateZhCN">
    <NMessageProvider>
      <StudioShell v-if="entered" :inert="!session?.authenticated || undefined" :username="session?.username" :unsaved="unsavedCount" :unresolved="unresolvedCount" :busy="busy" @command="commandPalette?.open()" @signout="signOut">
          <div v-if="preferences.current.saveRecoveryNotice && (unsavedCount || unresolvedCount)" class="preference-save-notice" role="status">{{ unsavedCount }} 处未保存输入，{{ unresolvedCount }} 个保存中或结果待确认操作。请完成保存或用原请求恢复；关闭页面可能丢失尚未保存的内容。</div>
          <p v-if="recent.message" class="notice" role="status">{{ recent.message }}</p>
          <div class="studio-route-area">
          <TrainingAccessNotice v-show="!authorizationManagement" :class="{ 'studio-access-refresh': retainingLayout && guardedHeight > 0 }" />
          <div ref="routeSurface" v-show="!trainingBlocked || retainingLayout" class="studio-route-surface" :class="{ 'studio-content-checking': retainingLayout }" :style="retainingLayout ? { minHeight: `${guardedHeight}px` } : undefined" :aria-hidden="trainingBlocked || undefined" :inert="trainingBlocked || undefined">
            <NConfigProvider :namespace="trainingBlocked ? 'studio-material-layer-hidden' : 'studio-material-layer'"><RouterView /></NConfigProvider>
          </div>
          </div>
      </StudioShell>
      <GlobalCommandPalette ref="commandPalette" :show-trigger="false" :enabled="Boolean(session?.authenticated && workspace.sessionActive && !loading && !busy)" />
      <!-- 登录弹层完全离场后再恢复地址初态，避免命令面板被并存弹层守卫拦下。 -->
      <NModal :show="!session?.authenticated" :mask-closable="false" :close-on-esc="false" :z-index="50" aria-labelledby="login-title" @after-leave="commandPalette?.openFromEntry()">
        <StudioLogin v-model:username="username" v-model:password="password" :busy="busy" :loading="loading" :error="error" @submit="signIn" @reconnect="refreshSession" />
      </NModal>
      <div v-if="session?.authenticated && error" class="global-notice" role="alert">{{ error }} <button @click="error = ''">关闭</button></div>
    </NMessageProvider>
  </NConfigProvider>
</template>
