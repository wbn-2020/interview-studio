import { computed, onActivated, onDeactivated, onUnmounted, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { useRoute } from 'vue-router'
import { recentIdValid, type RecentKind } from '../api/recent'
import { useRecentStore } from '../state/recent'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useWorkspaceStore } from '../state/workspace'

const destinations: Record<RecentKind, readonly [string, string]> = {
  KNOWLEDGE: ['knowledge', 'item'],
  PREPARATION: ['prepare', 'item'], MATERIAL: ['materials', 'attachment'], QUESTION: ['bank', 'item'],
  COLLECTION: ['collections', 'item'], TRAINING: ['training', 'item'], RECOLLECTION: ['recollections', 'item'],
  MEMORY_CARD: ['memory-cards', 'item'], AI_TASK: ['ai', 'item'], BOARD: ['boards', 'board'],
  INTERVIEW: ['schedule', 'item'], PREPARATION_TASK: ['preparation-tasks', 'item'], REPRACTICE: ['repractice', 'item'],
}

export function useRecentVisit(kind: RecentKind, id: MaybeRefOrGetter<string | undefined>, readable: MaybeRefOrGetter<boolean> = true) {
  const route = useRoute(), workspace = useWorkspaceStore(), access = useTrainingAccessStore(), recent = useRecentStore()
  let active = true, observed = false
  const routeId = computed(() => {
    if (kind === 'TRAINING' && (route.name === 'review' || route.name === 'training-report')) return route.query.sessionId
    const [name, key] = destinations[kind]
    return route.name === name && route.query.create !== '1' ? route.query[key] : undefined
  })
  // 重登及命令回填不构成重新打开；版本、题目与答案切换也不能让轮询生成新访问。
  watch([() => toValue(id), () => route.name, routeId], () => { observed = false }, { flush: 'sync' })
  onActivated(() => { if (!active) observed = false; active = true })
  onDeactivated(() => { active = false })
  onUnmounted(() => { active = false })

  // 调用方只能在已校验的顶层详情接纳处调用，不能从选择器、命令回执或引用揭示触发。
  return (acceptedId: string): boolean => {
    if (!active || observed || acceptedId !== toValue(id) || acceptedId !== routeId.value
      || !recentIdValid(acceptedId)) return false
    // 隐藏期间的首次接纳也消耗本次机会，恢复可见后的轮询不能补记为用户访问。
    observed = true
    if (!workspace.sessionActive || !toValue(readable) || document.visibilityState !== 'visible'
      || access.checking || access.challenge || access.restricted || workspace.changes.get('training-access-command')?.locked) return false
    return recent.enqueue(kind, acceptedId)
  }
}
