import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import { advanceRequestSession } from '../api/client'

export const useWorkspaceStore = defineStore('workspace', () => {
  const changes = reactive(new Map<string, { dirty: boolean; locked: boolean }>())
  const sessionEpoch = ref(0)
  const sessionActive = ref(false)
  // 清理会话缓存后更换查询键，让保留在页面上的编辑器重新绑定查询，草稿仍留在组件内。
  function activateSession() { advanceRequestSession(); sessionEpoch.value += 1; sessionActive.value = true }
  function expireSession() { advanceRequestSession(); sessionActive.value = false }
  function canLeave(keys = [...changes.keys()]): boolean {
    const active = keys.map(key => changes.get(key)).filter(Boolean)
    if (active.some(value => value?.locked)) {
      window.alert('有请求正在保存或结果尚未确认。请先等待或用原请求重试，避免重复写入。')
      return false
    }
    return !active.some(value => value?.dirty) || window.confirm('存在尚未保存的内容或位置。离开会丢弃这些本地修改，确定离开？')
  }
  return { changes, canLeave, sessionEpoch, sessionActive, activateSession, expireSession }
})
