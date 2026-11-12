import { computed, ref, shallowRef, watch } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, currentTrainingAccessEpoch, write } from '../api/client'
import type { RecentKind, RecentVisitRequest } from '../api/recent'
import { recentIdValid, recentKindValid, recentVisitValid } from '../api/recent'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from './workspace'
import { useTrainingAccessStore } from './trainingAccess'

type PendingVisit = RecentVisitRequest & { key: string; state: 'QUEUED' | 'SENDING' | 'PAUSED' | 'FAILED' }
export const useRecentStore = defineStore('recent', () => {
  const workspace = useWorkspaceStore(), access = useTrainingAccessStore()
  const ownerId = ref<string>(), queueOwner = ref<string>(), queue = shallowRef<PendingVisit[]>([])
  const busy = ref(false), visible = ref(document.visibilityState === 'visible'), message = ref(''), changeSequence = ref(0)
  const pendingCount = computed(() => queue.value.length)
  const canSend = computed(() => Boolean(ownerId.value && ownerId.value === queueOwner.value && workspace.sessionActive && visible.value
    && !access.checking && !access.restricted && !access.challenge && !workspace.changes.get('training-access-command')?.locked))
  const canRetry = computed(() => canSend.value && !busy.value && queue.value.some(item => item.state === 'PAUSED' || item.state === 'FAILED'))
  let generation = 0, channel: BroadcastChannel | undefined

  function setOwner(owner?: string) {
    const next = recentIdValid(owner) ? owner : undefined
    if (next !== ownerId.value) generation++
    if (next && queueOwner.value && next !== queueOwner.value) {
      // 队列只属于已验证的 owner；账号切换不能向新账号重放旧对象访问。
      queue.value = []; message.value = ''; changeSequence.value++
    }
    ownerId.value = next
    if (next) queueOwner.value = next
    if (!next) pause()
  }
  function pause() {
    queue.value = queue.value.map(item => item.state === 'QUEUED' || item.state === 'SENDING' ? { ...item, state: 'PAUSED' } : item)
  }
  watch([canSend, () => workspace.sessionEpoch], ([allowed], previous) => {
    if (!allowed || previous && previous[1] !== workspace.sessionEpoch) { generation++; pause() }
  }, { flush: 'sync' })
  function enqueue(kind: RecentKind, id: string): boolean {
    if (!recentKindValid(kind) || !recentIdValid(id) || !canSend.value) return false
    if (queue.value.some(item => item.kind === kind && item.id === id)) return true
    if (queue.value.length >= 32) { message.value = '最近访问有 32 条记录尚未确认，本次未加入。可在最近访问页恢复待确认记录。'; return false }
    queue.value = [...queue.value, { kind, id, key: crypto.randomUUID(), state: 'QUEUED' }]
    void pump()
    return true
  }
  function signal() {
    changeSequence.value++
    try { channel?.postMessage('changed') } catch { /* 跨标签提示失败不影响已确认的访问记录。 */ }
  }
  async function pump() {
    if (busy.value || !canSend.value) return
    const pending = queue.value.find(item => item.state === 'QUEUED')
    if (!pending) return
    const owner = ownerId.value, epoch = workspace.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), ticket = generation
    const body: RecentVisitRequest = { kind: pending.kind, id: pending.id }
    const sameOwner = () => owner === queueOwner.value && queue.value.some(item => item.key === pending.key)
    busy.value = true
    queue.value = queue.value.map(item => item.key === pending.key ? { ...item, state: 'SENDING' } : item)
    try {
      const result = await write<unknown>('/recent/visits', 'POST', body, pending.key)
      if (!sameOwner()) return
      if (!recentVisitValid(result, body)) throw new ApiError(502, 'RECENT_RECEIPT_INVALID', '访问回执无法核对。')
      // 回执仅含固定身份与时间，迟到也只结束同 owner 的原请求，不保留来源正文。
      queue.value = queue.value.filter(item => item.key !== pending.key)
      if (owner === ownerId.value && workspace.sessionActive) signal()
      if (pending.kind === 'KNOWLEDGE' && canSend.value && ticket === generation && epoch === workspace.sessionEpoch
        && accessEpoch === currentTrainingAccessEpoch()) {
        // 本标签已确认的知识访问刷新原 RECENT 列表；跨标签通知只提示变化，不主动查询。
        void queryClient.invalidateQueries({ queryKey: ['knowledge', epoch, 'items'] })
      }
    } catch (error) {
      if (!sameOwner()) return
      const certain = error instanceof ApiError && error.status >= 400 && error.status < 500 && ![401, 403].includes(error.status)
        && error.code !== 'ASSISTANCE_CONFIRMATION_REQUIRED'
      if (certain) {
        queue.value = queue.value.filter(item => item.key !== pending.key)
        message.value = '一条最近访问未被服务器接纳，已结束这条辅助记录；原业务内容不受影响。'
      } else {
        queue.value = queue.value.map(item => item.key === pending.key ? { ...item, state: 'FAILED' } : item)
        message.value = '最近访问记录尚未确认。原请求已保留，请在资料可用后主动恢复；正常保存与页面切换仍可继续。'
      }
    } finally {
      busy.value = false
      // 只推进仍在同次许可下新加入的请求；恢复权限不会自动重放暂停或失败的记录。
      if (ticket === generation && epoch === workspace.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()) void pump()
      else if (canSend.value && queue.value.some(item => item.state === 'QUEUED')) void pump()
    }
  }
  function retryPending() {
    if (!canRetry.value) return
    message.value = ''
    queue.value = queue.value.map(item => item.state === 'FAILED' || item.state === 'PAUSED' ? { ...item, state: 'QUEUED' } : item)
    void pump()
  }
  function visibilityChanged() { visible.value = document.visibilityState === 'visible' }
  function start() {
    if ('BroadcastChannel' in window && !channel) {
      channel = new BroadcastChannel('wenjie-recent-visits')
      channel.onmessage = event => { if (event.data === 'changed' && ownerId.value && workspace.sessionActive) changeSequence.value++ }
    }
    document.addEventListener('visibilitychange', visibilityChanged)
  }
  function stop() { channel?.close(); channel = undefined; document.removeEventListener('visibilitychange', visibilityChanged); visible.value = false; pause() }
  return { ownerId, pendingCount, busy, canRetry, message, changeSequence, setOwner, enqueue, retryPending, start, stop }
})
