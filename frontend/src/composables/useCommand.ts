import { computed, ref, shallowRef } from 'vue'
import { ApiError, write } from '../api/client'

type Command = { path: string; method: string; body: unknown; key: string; apply: (value: unknown) => void | Promise<void> }

export function useCommand() {
  const busy = ref(false)
  const error = shallowRef<unknown>()
  const pending = shallowRef<Command>()
  const locked = computed(() => busy.value || Boolean(pending.value))

  async function retry() {
    if (!pending.value || busy.value) return
    const command = pending.value
    busy.value = true
    error.value = undefined
    let result: unknown
    try {
      result = await write(command.path, command.method, command.body, command.key)
    } catch (reason) {
      error.value = reason
      // 响应未知时固定请求正文与幂等键；重新登录后重试也不能产生第二次创建。
      if (reason instanceof ApiError && reason.status >= 400 && reason.status < 500 && ![401, 403].includes(reason.status)
          && reason.code !== 'ASSISTANCE_CONFIRMATION_REQUIRED') {
        pending.value = undefined
      }
      busy.value = false
      return
    }
    pending.value = undefined
    busy.value = false
    try {
      await command.apply(result)
    } catch (reason) {
      // 先解锁供回调导航；成功响应若未通过本地校验，仍须保留原键，且不能覆盖回调已恢复或新建的命令。
      if (!pending.value) pending.value = command
      error.value = reason
    }
  }

  function run<T>(path: string, method: string, body: unknown, apply: (value: T) => void | Promise<void>) {
    if (locked.value) return
    pending.value = { path, method, body: JSON.parse(JSON.stringify(body)), key: crypto.randomUUID(), apply: apply as (value: unknown) => void | Promise<void> }
    return retry()
  }
  return { busy, error, pending, locked, retry, run }
}
