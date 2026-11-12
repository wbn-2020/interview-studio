import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import { advanceTrainingAccess, ApiError, errorText, read } from '../api/client'
import type { Schema } from '../api/client'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from './workspace'

function checkedActive(response: unknown): Schema['TrainingSessionSummary'] | null {
  const invalid = () => new ApiError(502, 'TRAINING_ACCESS_RESPONSE_INVALID', '服务器返回的训练状态无法核实，资料继续保持遮挡，请重新检查连接。')
  if (!response || typeof response !== 'object' || Array.isArray(response) || !Object.hasOwn(response, 'session')) throw invalid()
  const session = (response as Record<string, unknown>).session
  if (session === null) return null
  if (!session || typeof session !== 'object' || Array.isArray(session)) throw invalid()
  const value = session as Record<string, unknown>
  // 仅明确的 null 表示没有活动训练；影响资料放行、路由与辅助确认的字段必须保持真实类型。
  if (typeof value.id !== 'string' || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value.id)
    || typeof value.mode !== 'string' || !['LEARNING', 'FOCUSED', 'MOCK'].includes(value.mode)
    || typeof value.status !== 'string' || !['RUNNING', 'PAUSED'].includes(value.status) || typeof value.materialAccess !== 'boolean'
    || typeof value.revision !== 'number' || !Number.isSafeInteger(value.revision) || value.revision < 0) throw invalid()
  return session as Schema['TrainingSessionSummary']
}

export const useTrainingAccessStore = defineStore('training-access', () => {
  const workspace = useWorkspaceStore()
  const active = shallowRef<Schema['TrainingSessionSummary'] | null>(null)
  const checking = ref(true)
  const error = ref('')
  const challenge = ref(false)
  let generation = 0
  let flight: Promise<boolean> | undefined
  const restricted = computed(() => Boolean(active.value && active.value.mode !== 'LEARNING' && !active.value.materialAccess))
  const trainingQueries = new Set(['training', 'training-session', 'training-events', 'training-question', 'training-answers', 'training-answer'])

  function invalidate() {
    generation++
    checking.value = true
    flight = undefined
    advanceTrainingAccess()
    // 保留观察者和编辑器的输入；遮挡期间停止资料读取，恢复授权后重新取数。
    void queryClient.cancelQueries({ predicate: query => !trainingQueries.has(String(query.queryKey[0])) })
    void queryClient.invalidateQueries({ refetchType: 'none' })
  }

  async function refresh(): Promise<boolean> {
    if (flight) return flight
    if (!workspace.sessionActive) return false
    checking.value = true
    error.value = ''
    const request = generation
    const sessionEpoch = workspace.sessionEpoch
    const pending = (async () => {
      try {
        const response = await read<unknown>('/training/active')
        if (request !== generation || sessionEpoch !== workspace.sessionEpoch || !workspace.sessionActive) return false
        const result = checkedActive(response)
        const before = active.value
        const changed = before?.id !== result?.id || before?.materialAccess !== result?.materialAccess || before?.mode !== result?.mode
        if (changed) {
          advanceTrainingAccess()
          void queryClient.cancelQueries({ predicate: query => !trainingQueries.has(String(query.queryKey[0])) })
          void queryClient.invalidateQueries({ refetchType: 'none' })
        }
        active.value = result
        checking.value = false
        if (!restricted.value) {
          challenge.value = false
          void queryClient.refetchQueries({ type: 'active' })
        }
        return true
      } catch (reason) {
        if (request === generation && sessionEpoch === workspace.sessionEpoch) error.value = errorText(reason)
        return false
      } finally {
        if (request === generation) flight = undefined
      }
    })()
    flight = pending
    return pending
  }

  function needsGuard(routeName: unknown, item: unknown, creating: unknown): boolean {
    if (routeName === 'ai-authorizations') return false
    if (routeName !== 'training') return true
    if (!restricted.value) return false
    return creating === '1' || (typeof item === 'string' && item !== active.value?.id)
  }

  return { active, checking, error, challenge, restricted, invalidate, refresh, needsGuard }
})
