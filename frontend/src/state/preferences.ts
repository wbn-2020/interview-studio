import { defineStore } from 'pinia'
import { computed, ref, shallowRef, watch } from 'vue'
import type { WatchStopHandle } from 'vue'
import { ApiError, currentTrainingAccessEpoch, errorText } from '../api/client'
import { defaultPreferences, preferencesValid, readPreferences, samePreferences } from '../api/preferences'
import type { Preferences } from '../api/preferences'
import { useWorkspaceStore } from './workspace'
import { useTrainingAccessStore } from './trainingAccess'

export const usePreferencesStore = defineStore('preferences', () => {
  const workspace = useWorkspaceStore(), access = useTrainingAccessStore()
  const current = shallowRef<Preferences>({ ...defaultPreferences })
  const systemDark = ref(false), loading = ref(false), error = ref('')
  const isDark = computed(() => current.value.theme === 'DARK' || (current.value.theme === 'SYSTEM' && systemDark.value))
  const readable = computed(() => workspace.sessionActive && !access.checking && !access.challenge && !access.restricted
    && !workspace.changes.get('training-access-command')?.locked)
  let generation = 0, started = false
  let media: MediaQueryList | undefined, stopSession: WatchStopHandle | undefined, stopAppearance: WatchStopHandle | undefined
  function onSystemTheme() { systemDark.value = media?.matches === true }

  function applyDocument() {
    const root = document.documentElement
    root.dataset.studioTheme = isDark.value ? 'dark' : 'light'
    root.dataset.studioMotion = current.value.motion.toLowerCase()
    root.style.setProperty('--studio-font-size', `${current.value.fontSize}px`)
    root.style.setProperty('--studio-content-width', `${current.value.contentWidth}px`)
  }

  function reset() { generation++; current.value = { ...defaultPreferences }; loading.value = false; error.value = '' }
  function apply(value: Preferences): boolean {
    if (!preferencesValid(value)) throw new ApiError(502, 'PREFERENCES_RESPONSE_INVALID', '个人设置响应无法核实，请重新读取。')
    if (value.revision < current.value.revision) return false
    if (value.revision === current.value.revision && !samePreferences(value, current.value))
      throw new ApiError(502, 'PREFERENCES_RESPONSE_INVALID', '同一设置修订出现不同内容，请核对服务器设置。')
    current.value = { ...value }; error.value = ''
    return true
  }

  async function refresh(): Promise<void> {
    if (!readable.value) return
    const sequence = ++generation, epoch = workspace.sessionEpoch, training = currentTrainingAccessEpoch()
    loading.value = true; error.value = ''
    try {
      const value = await readPreferences()
      if (sequence !== generation || epoch !== workspace.sessionEpoch || training !== currentTrainingAccessEpoch() || !readable.value) return
      if (!apply(value)) throw new ApiError(502, 'PREFERENCES_RESPONSE_INVALID', '读取到旧设置修订，已保留较新的显示设置。')
    } catch (reason) {
      if (sequence === generation && epoch === workspace.sessionEpoch && readable.value) error.value = errorText(reason)
    } finally { if (sequence === generation) loading.value = false }
  }

  function start() {
    if (started) return
    started = true
    media = window.matchMedia('(prefers-color-scheme: dark)')
    onSystemTheme(); media.addEventListener('change', onSystemTheme)
    stopAppearance = watch([current, isDark], applyDocument, { immediate: true, flush: 'sync' })
    stopSession = watch([() => workspace.sessionEpoch, () => workspace.sessionActive, readable], (value, previous) => {
      generation++; loading.value = false
      // 会话边界重置全局偏好，组件中的未保存输入由各自草稿继续保留。
      if (!previous || value[0] !== previous[0] || !value[1]) reset()
      if (readable.value) void refresh()
    }, { immediate: true, flush: 'sync' })
  }

  function stop() {
    started = false; generation++; loading.value = false
    stopSession?.(); stopAppearance?.(); stopSession = undefined; stopAppearance = undefined
    media?.removeEventListener('change', onSystemTheme); media = undefined
  }

  function formatDate(value: string): string {
    const date = new Date(value)
    if (!Number.isFinite(date.getTime())) return '时间不可用'
    try {
      return `${new Intl.DateTimeFormat('zh-CN', { timeZone: current.value.timeZone, dateStyle: 'medium', timeStyle: 'medium' }).format(date)}（${current.value.timeZone}）`
    }
    catch { return `${date.toISOString()}（UTC）` }
  }

  return { current, isDark, loading, error, start, stop, reset, refresh, apply, formatDate }
})
