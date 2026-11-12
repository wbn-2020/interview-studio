import { computed, onUnmounted, reactive, shallowRef, watch } from 'vue'
import type { ComputedRef } from 'vue'
import { ApiError, read } from '../../api/client'
import type { TodayItem, TodaySourceRef } from '../../api/today'
import { todaySourceDetailValid, todaySourcePath } from '../../api/today'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'

export const dateKinds = new Set(['KNOWLEDGE_DRAFT', 'TRAINING_SESSION', 'REVIEW_ACTION'])
export const estimateKinds = new Set([...dateKinds, 'MEMORY_CARD'])
export const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
export function same(left: unknown, right: unknown): boolean {
  if (left === right) return true
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object' || Array.isArray(left) !== Array.isArray(right)) return false
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every(key => Object.hasOwn(right, key)
    && same((left as Record<string, unknown>)[key], (right as Record<string, unknown>)[key]))
}
export function sourceWritable(item: TodayItem): boolean {
  const state = item.sourceStatus
  switch (state.kind) {
    case 'KNOWLEDGE_DRAFT': return state.knowledge.status === 'ACTIVE' && state.knowledge.unpublishedDraft
    case 'TRAINING_SESSION': return state.training.status !== 'ENDED'
    case 'PREPARATION_TASK': return !['DONE', 'CANCELLED'].includes(state.task.status)
    case 'MEMORY_CARD': return state.memory.cardStatus === 'ACTIVE'
    case 'REVIEW_ACTION': return state.action.status === 'OPEN' && (item.pendingCount ?? 0) > 0
    case 'INTERVIEW_EVENT': return state.interview.status === 'PLANNED'
  }
}
export async function readPlanSource(source: TodaySourceRef, zoneId: string): Promise<TodayItem> {
  const value = await read<unknown>(todaySourcePath(source, { zoneId }))
  if (!todaySourceDetailValid(value, source, { zoneId }) || value.capability.status !== 'READY' || !value.item) {
    throw new ApiError(0, 'TODAY_RESPONSE_INVALID', '无法核对来源的当前状态，请重新读取后操作。')
  }
  return value.item
}

export function usePlanCommand(readable: ComputedRef<boolean>, stateKey: string, dirty: ComputedRef<boolean>) {
  const store = useWorkspaceStore(), writer = useCommand(), state = reactive({ dirty: false, locked: false })
  const saved = shallowRef<NonNullable<typeof writer.pending.value>>()
  let alive = true, generation = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
  const locked = computed(() => writer.locked.value)
  watch([readable, () => store.sessionEpoch], () => { generation++ }, { flush: 'sync' })
  watch([dirty, locked], ([changed, waiting]) => { state.dirty = changed; state.locked = waiting; store.changes.set(stateKey, state) }, { immediate: true, flush: 'sync' })
  onUnmounted(() => { alive = false; generation++; if (store.changes.get(stateKey) === state) store.changes.delete(stateKey) })
  function run<T>(path: string, method: string, body: unknown, valid: (value: unknown) => value is T, apply: (value: T) => void) {
    if (!readable.value || locked.value) return
    commandEpoch = store.sessionEpoch; commandGeneration = generation
    void writer.run<unknown>(path, method, body, value => {
      // 回执来自旧权限窗口或内容异常时，保留完整正文与原键；不能因成功 HTTP 就清空表单。
      let accepted = false
      try { accepted = alive && readable.value && commandEpoch === store.sessionEpoch && commandGeneration === generation && valid(value) } catch { accepted = false }
      if (!accepted) {
        writer.pending.value = saved.value
        writer.error.value = new ApiError(0, 'TODAY_PLAN_RECEIPT_UNCERTAIN', '访问条件变化或回执未能核对。输入与原请求已保留，恢复后请用原请求确认结果。')
        return
      }
      const acceptedCommand = saved.value
      // 应用失败仍保留原请求；回调若发起新命令，不清除新命令的恢复记录。
      apply(value as T)
      if (saved.value === acceptedCommand) saved.value = undefined
    })
    saved.value = writer.pending.value
  }
  function retry() {
    if (!readable.value || writer.busy.value || !writer.pending.value) return
    commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry()
  }
  return { ...writer, locked, run, retry }
}
