import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { read } from '../api/client'
import type { Schema } from '../api/client'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from './workspace'
import { useTrainingAccessStore } from './trainingAccess'

type Page = 'ai' | 'training' | 'comparison'
type ConnectionState = 'idle' | 'connecting' | 'live' | 'polling' | 'offline'
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const trainingKeys = new Set([
  'training-session', 'training-question', 'training-events', 'training-follow-up-settings',
  'training-follow-ups', 'training-follow-up', 'training-evaluations', 'training-evaluation-coverage',
])

export const useAiUpdatesStore = defineStore('ai-updates', () => {
  const workspace = useWorkspaceStore()
  const access = useTrainingAccessStore()
  const state = ref<ConnectionState>('idle')
  const enabled = computed(() => state.value !== 'idle')
  const message = computed(() => ({
    idle: '任务同步已暂停。',
    connecting: '正在连接任务同步，期间按定时查询更新。',
    live: '任务状态实时同步。',
    polling: '任务状态按定时查询更新，正在恢复实时同步。',
    offline: '网络已离线，恢复连接后会自动同步任务状态。',
  })[state.value])
  let page: Page | undefined
  let sessionId: string | undefined
  let comparisonId: string | undefined
  let epoch = -1
  let generation = 0
  let source: EventSource | undefined
  let timer: number | undefined
  let cursor: string | undefined
  let lastSignal = 0
  let lastRead = 0
  let nextConnect = 0
  let failures = 0
  let refreshPending = false
  let authChecking = false

  function current(expected: number) {
    return expected === generation && Boolean(page) && workspace.sessionActive
      && workspace.sessionEpoch === epoch && document.visibilityState === 'visible'
  }

  function closeConnection() {
    const previous = source
    source = undefined
    previous?.close()
  }

  function setContext(target?: Page, trainingSessionId?: string, reviewComparisonId?: string) {
    const nextPage = workspace.sessionActive && document.visibilityState === 'visible' ? target : undefined
    if (nextPage === page && sessionId === trainingSessionId && comparisonId === reviewComparisonId
        && epoch === workspace.sessionEpoch) return
    generation++
    closeConnection()
    if (timer !== undefined) window.clearInterval(timer)
    timer = undefined
    if (epoch !== workspace.sessionEpoch || !workspace.sessionActive) cursor = undefined
    page = nextPage
    sessionId = trainingSessionId
    comparisonId = reviewComparisonId
    epoch = workspace.sessionEpoch
    failures = 0
    nextConnect = 0
    lastRead = 0
    authChecking = false
    refreshPending = Boolean(page)
    state.value = page ? 'connecting' : 'idle'
    if (!page) return
    timer = window.setInterval(tick, 1000)
    tick()
  }

  function refreshQueries() {
    if (!current(generation) || !navigator.onLine || access.checking || access.challenge
        || workspace.changes.get('training-access-command')?.locked) return
    if (access.restricted && (page !== 'training' || (sessionId && sessionId !== access.active?.id))) return
    const expectedEpoch = epoch
    // 只刷新已启用且属于当前页面的查询，通知不能绕过资料守卫或复活隐藏题目的读取。
    void queryClient.invalidateQueries({
      predicate: query => {
        if (!query.isActive() || query.queryKey[1] !== expectedEpoch) return false
        const name = String(query.queryKey[0])
        if (name === 'ai') return !access.restricted && (page === 'ai' || query.queryKey[2] === 'settings')
        // 比较任务通知只刷新当前比较的候选目录与详情，不重载人工判断草稿。
        if (page === 'comparison') return !access.restricted && name === 'repractice'
          && Boolean(comparisonId) && query.queryKey[3] === comparisonId
          && ['ai-comparison-tasks', 'ai-comparison-task'].includes(String(query.queryKey[2]))
        if (page !== 'training') return false
        if (name === 'training') return true
        return trainingKeys.has(name) && Boolean(sessionId) && query.queryKey[2] === sessionId
      },
      refetchType: 'active',
    }, { cancelRefetch: false })
    refreshPending = false
    lastRead = performance.now()
  }

  async function checkSession(expected: number) {
    if (!current(expected) || authChecking) return
    authChecking = true
    try {
      const session = await read<Schema['Session']>('/auth/session')
      // 旧连接的检查可以晚于重登完成，不能失活新会话或清掉保留的输入。
      if (current(expected) && !session.authenticated) window.dispatchEvent(new Event('studio:session-expired'))
    } catch { /* 接口读取保留自己的错误状态，连接恢复不改变任何写命令。 */ }
    finally { if (expected === generation) authChecking = false }
  }

  function recover(expected: number, connection: EventSource, rotating = false) {
    if (!current(expected) || source !== connection) return
    closeConnection()
    state.value = navigator.onLine ? 'polling' : 'offline'
    refreshPending = true
    if (rotating) nextConnect = performance.now() + 1000
    else {
      failures++
      nextConnect = performance.now() + Math.min(30_000, 2000 * 2 ** Math.min(failures - 1, 4))
      void checkSession(expected)
    }
  }

  function open() {
    const expected = generation
    if (!current(expected) || source || !navigator.onLine) return
    if (!('EventSource' in window)) { state.value = 'polling'; return }
    let connection: EventSource
    try {
      connection = new EventSource(`/api/v1/ai/events${cursor ? `?after=${encodeURIComponent(cursor)}` : ''}`)
    } catch {
      state.value = 'polling'
      nextConnect = performance.now() + 30_000
      return
    }
    source = connection
    state.value = 'connecting'
    lastSignal = performance.now()
    let receivedSnapshot = false
    const matches = () => current(expected) && source === connection
    const payload = (event: MessageEvent<string>) => {
      if (typeof event.data !== 'string' || event.data.length > 256) throw new Error('invalid signal')
      const value: unknown = JSON.parse(event.data)
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid signal')
      return value as Record<string, unknown>
    }
    const receive = (event: MessageEvent<string>) => {
      if (!matches()) return
      try {
        const value = payload(event)
        if (Object.keys(value).length !== 1 || typeof value.cursor !== 'string' || !uuid.test(value.cursor)
            || event.lastEventId !== value.cursor || (event.type === 'changed' && !receivedSnapshot)) throw new Error('invalid cursor')
        const changed = cursor !== value.cursor
        cursor = value.cursor
        receivedSnapshot = true
        lastSignal = performance.now()
        failures = 0
        state.value = 'live'
        // 快照总要重新同步；游标是变化标记，不能按大小排序，也不能跳过恢复后的同值快照。
        if (event.type === 'snapshot' || changed) refreshPending = true
      } catch { recover(expected, connection) }
    }
    connection.addEventListener('snapshot', receive)
    connection.addEventListener('changed', receive)
    connection.addEventListener('heartbeat', event => {
      if (!matches()) return
      try {
        if (!receivedSnapshot || Object.keys(payload(event)).length !== 0) throw new Error('invalid heartbeat')
        lastSignal = performance.now()
      } catch { recover(expected, connection) }
    })
    connection.addEventListener('reconnect', event => {
      if (!matches()) return
      try {
        if (!receivedSnapshot || Object.keys(payload(event)).length !== 0) throw new Error('invalid reconnect')
        recover(expected, connection, true)
      } catch { recover(expected, connection) }
    })
    connection.onerror = () => recover(expected, connection)
  }

  function tick() {
    if (!current(generation)) return
    const now = performance.now()
    if (!navigator.onLine) {
      closeConnection()
      state.value = 'offline'
      return
    }
    if (source && now - lastSignal > 35_000) recover(generation, source)
    if (!source && now >= nextConnect) open()
    if (refreshPending || now - lastRead >= (state.value === 'live' ? 30_000 : 5000)) refreshQueries()
  }

  watch([() => access.checking, () => access.challenge, () => access.restricted, () => access.active?.id], () => {
    refreshPending = true
  })

  return { enabled, message, setContext }
})
