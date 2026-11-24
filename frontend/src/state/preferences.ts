import { defineStore } from 'pinia'
import { computed, ref, shallowRef, watch } from 'vue'
import type { WatchStopHandle } from 'vue'
import { ApiError, currentTrainingAccessEpoch, errorText, write } from '../api/client'
import { defaultPreferences, preferenceReceiptValid, preferenceValuesValid, preferencesCacheValid, preferencesValid, readPreferences,
  samePreferenceValues, samePreferences, savePreferencesProblem } from '../api/preferences'
import type { PreferenceValues, Preferences, PreferencesCache, SavePreferences } from '../api/preferences'
import { useWorkspaceStore } from './workspace'
import { useTrainingAccessStore } from './trainingAccess'
import { useRecentStore } from './recent'

type PendingPreferenceWrite = { body: SavePreferences; key: string; base: PreferenceValues }
const cachePrefix = 'wenjie-preferences-cache-v1:'
const preferenceNames = ['theme', 'fontSize', 'contentWidth', 'motion', 'timeZone', 'saveRecoveryNotice'] as const

export const usePreferencesStore = defineStore('preferences', () => {
  const workspace = useWorkspaceStore(), access = useTrainingAccessStore(), recent = useRecentStore()
  const current = shallowRef<Preferences>({ ...defaultPreferences }), server = shallowRef<Preferences>()
  const local = shallowRef<PreferenceValues>(), pendingRequest = shallowRef<PendingPreferenceWrite>()
  const systemDark = ref(false), loading = ref(false), error = ref(''), saving = ref(false), syncError = shallowRef<unknown>()
  // 未保存过外观设置的账户先使用参考界面的深色；本地明确选择的主题优先于默认深色。
  const isDark = computed(() => current.value.theme === 'DARK'
    || current.value.theme === 'SYSTEM' && (current.value.revision === 0 || systemDark.value))
  const readable = computed(() => workspace.sessionActive && !access.checking && !access.challenge && !access.restricted
    && !workspace.changes.get('training-access-command')?.locked)
  const loaded = computed(() => Boolean(server.value))
  const localDirty = computed(() => Boolean(server.value && local.value && !samePreferenceValues(local.value, valuesOf(server.value))))
  const hasPending = computed(() => Boolean(local.value || pendingRequest.value))
  const requestPending = computed(() => Boolean(pendingRequest.value))
  let generation = 0, lifecycle = 0, started = false, pumping = false
  let refreshTask: Promise<Preferences | undefined> | undefined
  let syncTimer: ReturnType<typeof setTimeout> | undefined
  let media: MediaQueryList | undefined, stopSession: WatchStopHandle | undefined, stopAppearance: WatchStopHandle | undefined

  function valuesOf(value: Readonly<Preferences>): PreferenceValues {
    return { theme: value.theme, fontSize: value.fontSize, contentWidth: value.contentWidth, motion: value.motion,
      timeZone: value.timeZone, saveRecoveryNotice: value.saveRecoveryNotice }
  }
  function mergeConflict(latest: Preferences, request: PendingPreferenceWrite): PreferenceValues {
    const merged = valuesOf(latest), final = local.value
    for (const name of preferenceNames) {
      // 只保留本标签实际修改过的字段；请求期间的新输入继续优先于旧请求快照。
      if (request.body[name] !== request.base[name] || final && final[name] !== request.body[name])
        Object.assign(merged, { [name]: final ? final[name] : request.body[name] })
    }
    return merged
  }
  function onSystemTheme() { systemDark.value = media?.matches === true }
  function clearSyncTimer() {
    if (syncTimer !== undefined) clearTimeout(syncTimer)
    syncTimer = undefined
  }
  function clearCache(owner = recent.ownerId) {
    if (!owner) return
    try { sessionStorage.removeItem(`${cachePrefix}${owner}`) } catch { /* 隐私模式禁用存储时不影响页面内预览。 */ }
  }
  function persist() {
    if (!local.value) { clearCache(); return }
    if (!recent.ownerId) return
    const base = server.value ?? current.value
    const cached: PreferencesCache = { version: 1, values: { ...local.value }, revision: base.revision, updatedAt: base.updatedAt }
    if (pendingRequest.value) {
      cached.request = { ...pendingRequest.value.body, base: { ...pendingRequest.value.base } }
      cached.key = pendingRequest.value.key
    }
    // 按已验证 owner 隔离当前标签页缓存，同时保留原幂等键，刷新后不会重复写入。
    try { sessionStorage.setItem(`${cachePrefix}${recent.ownerId}`, JSON.stringify(cached)) }
    catch { /* 存储不可用时仍保留页面内输入。 */ }
  }
  function restore(owner = recent.ownerId) {
    if (!owner) return
    try {
      const raw = sessionStorage.getItem(`${cachePrefix}${owner}`)
      if (!raw) return
      const cached: unknown = JSON.parse(raw)
      if (!preferencesCacheValid(cached)) { clearCache(owner); return }
      local.value = { ...cached.values }
      current.value = { ...defaultPreferences, revision: cached.revision, updatedAt: cached.updatedAt, ...cached.values }
      if (cached.request && cached.key) {
        const { base, ...body } = cached.request
        pendingRequest.value = { body, key: cached.key, base: { ...base } }
      }
    } catch { clearCache(owner) }
  }
  function applyDocument() {
    const root = document.documentElement
    root.dataset.studioTheme = isDark.value ? 'dark' : 'light'
    root.dataset.studioMotion = current.value.motion.toLowerCase()
    root.style.setProperty('--studio-font-size', `${current.value.fontSize}px`)
    root.style.setProperty('--studio-content-width', `${current.value.contentWidth}px`)
  }
  function reset(cacheOwner = recent.ownerId) {
    generation++; lifecycle++; clearSyncTimer(); refreshTask = undefined
    current.value = { ...defaultPreferences }; server.value = undefined; local.value = undefined; pendingRequest.value = undefined
    loading.value = false; error.value = ''; saving.value = false; syncError.value = undefined
    clearCache(cacheOwner)
  }
  function acceptServer(value: Preferences): boolean {
    if (!preferencesValid(value)) throw new ApiError(502, 'PREFERENCES_RESPONSE_INVALID', '个人设置响应无法核实，请重新读取。')
    if (server.value && value.revision < server.value.revision) return false
    if (server.value && value.revision === server.value.revision && !samePreferences(value, server.value))
      throw new ApiError(502, 'PREFERENCES_RESPONSE_INVALID', '同一设置修订出现不同内容，请核对服务器设置。')
    const nextServer = { ...value }, keepLocal = Boolean(local.value && (pendingRequest.value || !samePreferenceValues(local.value, valuesOf(value))))
    // 保存回执只推进服务器基线；只要本地最终值不同，就不能用旧请求内容覆盖新输入。
    if (keepLocal && local.value) current.value = { ...nextServer, ...local.value }
    else {
      local.value = undefined
      current.value = { ...nextServer }
    }
    // 先更新显示值再发布服务器基线，避免同步监听读到上一帧内容。
    server.value = nextServer
    error.value = ''
    persist()
    return true
  }
  async function performRefresh(): Promise<Preferences | undefined> {
    const sequence = ++generation, epoch = workspace.sessionEpoch, training = currentTrainingAccessEpoch()
    loading.value = true; error.value = ''
    try {
      const value = await readPreferences()
      if (sequence !== generation || epoch !== workspace.sessionEpoch || training !== currentTrainingAccessEpoch() || !readable.value) return
      // 自动保存可能先于并行读取提交新修订；旧读取不再覆盖或误报为设置异常。
      if (server.value && value.revision < server.value.revision) return server.value
      if (!acceptServer(value)) throw new ApiError(502, 'PREFERENCES_RESPONSE_INVALID', '读取到旧设置修订，已保留较新的显示设置。')
      if (local.value) schedule()
      return value
    } catch (reason) {
      if (sequence === generation && epoch === workspace.sessionEpoch && training === currentTrainingAccessEpoch() && readable.value)
        error.value = errorText(reason)
      return undefined
    } finally { if (sequence === generation) loading.value = false }
  }
  function refresh(): Promise<Preferences | undefined> {
    if (!readable.value) return Promise.resolve(undefined)
    if (refreshTask) return refreshTask
    const task = performRefresh()
    refreshTask = task
    void task.then(() => { if (refreshTask === task) refreshTask = undefined })
    return task
  }
  function schedule() {
    if (!started || !readable.value || !local.value || syncTimer !== undefined) return
    // 短暂合并连续点击或输入；请求一旦发出，后续修改只保留最新最终值。
    syncTimer = setTimeout(() => { syncTimer = undefined; void pump() }, 150)
  }
  function definiteFailure(reason: unknown): boolean {
    return reason instanceof ApiError && reason.status >= 400 && reason.status < 500
      && ![401, 403, 408, 409, 429].includes(reason.status) && reason.code !== 'ASSISTANCE_CONFIRMATION_REQUIRED'
  }
  function writeContextCurrent(epoch: number, training: number, token: number): boolean {
    return token === lifecycle && started && workspace.sessionActive
      && epoch === workspace.sessionEpoch && training === currentTrainingAccessEpoch()
  }
  async function send(request: PendingPreferenceWrite, token: number, epoch: number, training: number) {
    saving.value = true; syncError.value = undefined
    try {
      const value = await write<unknown>('/preferences', 'PUT', request.body, request.key)
      if (!writeContextCurrent(epoch, training, token)) return
      if (!preferenceReceiptValid(value, request.body))
        throw new ApiError(502, 'PREFERENCES_RECEIPT_INVALID', '保存回执与原请求不一致。请保留输入，用原请求核对结果。')
      acceptServer(value)
      pendingRequest.value = undefined
      if (server.value && local.value && samePreferenceValues(local.value, valuesOf(server.value))) {
        local.value = undefined
        current.value = { ...server.value }
      }
      persist()
    } catch (reason) {
      if (!writeContextCurrent(epoch, training, token)) return
      if (reason instanceof ApiError && reason.status === 409 && reason.code === 'PREFERENCES_CONFLICT') {
        try {
          const latest = await readPreferences()
          if (!writeContextCurrent(epoch, training, token)) return
          if (server.value && latest.revision < server.value.revision)
            throw new ApiError(502, 'PREFERENCES_RESPONSE_INVALID', '读取到旧设置修订，已保留本地最终值。')
          // 409 已明确未写入：仅合并本标签修改过的字段，避免用旧快照覆盖他标签的无关设置。
          const merged = mergeConflict(latest, request)
          pendingRequest.value = undefined
          local.value = samePreferenceValues(merged, valuesOf(latest)) ? undefined : merged
          acceptServer(latest)
        } catch (refreshReason) { syncError.value = refreshReason }
      } else {
        // 响应未知时保留原正文和幂等键；明确校验失败则可丢弃旧请求，等待修正后的最终值。
        if (definiteFailure(reason)) pendingRequest.value = undefined
        syncError.value = reason
      }
    } finally {
      if (token === lifecycle) {
        saving.value = false
        persist()
      }
    }
  }
  async function pump() {
    if (pumping || saving.value || !started || !readable.value) return
    clearSyncTimer(); pumping = true
    try {
      while (started && readable.value && !saving.value && !syncError.value) {
        if (pendingRequest.value) {
          const request = pendingRequest.value, token = lifecycle
          await send(request, token, workspace.sessionEpoch, currentTrainingAccessEpoch())
          continue
        }
        if (!server.value || !local.value) break
        if (samePreferenceValues(local.value, valuesOf(server.value))) {
          local.value = undefined; persist(); break
        }
        const body: SavePreferences = { ...local.value, expectedRevision: server.value.revision }
        const problem = savePreferencesProblem(body)
        if (problem) { syncError.value = new ApiError(422, 'PREFERENCES_INVALID', problem); break }
        const request = { body, key: crypto.randomUUID(), base: valuesOf(server.value) }
        pendingRequest.value = request; persist()
        const token = lifecycle
        await send(request, token, workspace.sessionEpoch, currentTrainingAccessEpoch())
      }
    } finally { pumping = false }
  }
  function update(value: PreferenceValues): boolean {
    if (!started || !readable.value || !server.value || !preferenceValuesValid(value)) return false
    if (pendingRequest.value || !samePreferenceValues(value, valuesOf(server.value))) local.value = { ...value }
    else local.value = undefined
    current.value = { ...server.value, ...(local.value ?? value) }
    if (syncError.value && definiteFailure(syncError.value)) pendingRequest.value = undefined
    syncError.value = undefined
    persist()
    if (local.value) schedule()
    else clearSyncTimer()
    return true
  }
  function retry() {
    if (!readable.value || saving.value) return
    syncError.value = undefined
    if (local.value || pendingRequest.value) schedule()
  }
  function adopt(value: Preferences, keepLocal: boolean): boolean {
    if (!readable.value || saving.value || pendingRequest.value || !preferencesValid(value)) return false
    if (!keepLocal) { local.value = undefined; clearSyncTimer() }
    const applied = acceptServer(value)
    if (!applied) return false
    if (keepLocal && local.value) schedule()
    persist()
    return true
  }
  function start() {
    if (started) return
    started = true; restore()
    media = window.matchMedia('(prefers-color-scheme: dark)')
    onSystemTheme(); media.addEventListener('change', onSystemTheme)
    stopAppearance = watch([current, isDark], applyDocument, { immediate: true, flush: 'sync' })
    let previousOwner = recent.ownerId, previousEpoch = workspace.sessionEpoch, previousActive = workspace.sessionActive
    let skipEpochBoundary = false
    stopSession = watch([() => recent.ownerId, () => workspace.sessionEpoch, () => workspace.sessionActive, readable], value => {
      // 缓存按已验证 owner 隔离；退出、换号或会话边界都不能把旧账号输入重放到新账号。
      const ownerChanged = value[0] !== previousOwner, epochChanged = value[1] !== previousEpoch
      if (ownerChanged) {
        reset(previousOwner)
        if (value[0]) restore(value[0])
        skipEpochBoundary = true
      } else if (previousActive && (!value[2] || epochChanged && !skipEpochBoundary)) reset()
      if (epochChanged) skipEpochBoundary = false
      previousOwner = value[0]; previousEpoch = value[1]; previousActive = value[2]
      if (readable.value) { void refresh(); if (local.value) schedule() }
    }, { immediate: true, flush: 'sync' })
  }
  function stop() {
    started = false; generation++; loading.value = false; clearSyncTimer(); refreshTask = undefined
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

  return { current, server, isDark, loading, error, loaded, localDirty, hasPending, requestPending, saving, syncError,
    start, stop, reset, refresh, update, retry, adopt, formatDate }
})
