import { useRoute, useRouter } from 'vue-router'
import type { RouteLocationRaw } from 'vue-router'

function internalPath(value: unknown): value is string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.includes('\\')
}

export function useWorkspaceNavigation() {
  const route = useRoute()
  const router = useRouter()

  function withReturn(query: Record<string, unknown> = {}) {
    const previous = window.history.state?.back
    const source = internalPath(route.query.from) && route.query.from !== route.fullPath ? route.query.from
      : internalPath(previous) && previous !== route.fullPath ? previous : route.fullPath
    return { ...query, from: source }
  }

  function returnTo(fallback: RouteLocationRaw) {
    if (internalPath(route.query.from) && route.query.from !== route.fullPath) {
      void router.push(route.query.from)
      return
    }
    // 深链接可能没有站内来源；只有历史记录明确指向站内路径时才使用浏览器后退。
    if (internalPath(window.history.state?.back) && window.history.state.back !== route.fullPath) {
      void router.back()
      return
    }
    void router.push(fallback)
  }

  return { returnTo, withReturn }
}
