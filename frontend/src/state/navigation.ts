import { ref } from 'vue'
import type { Router } from 'vue-router'

export const pendingNavigation = ref('')
export const navigationError = ref('')

export function installNavigationFeedback(router: Router) {
  // 先显示导航进度，再等待离开保护、训练条件及异步页面；失败时保留当前工作区。
  router.beforeEach((to, from) => {
    navigationError.value = ''
    pendingNavigation.value = to.fullPath === from.fullPath ? '' : to.fullPath
  })
  router.afterEach((to, from, failure) => {
    pendingNavigation.value = ''
    if (!failure && to.path !== from.path) document.getElementById('main-content')?.focus({ preventScroll: true })
  })
  router.onError(() => {
    pendingNavigation.value = ''
    navigationError.value = '页面未能打开，当前内容已保留。请检查连接后再次选择菜单。'
  })
}
