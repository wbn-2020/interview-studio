import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin } from '@tanstack/vue-query'
import { queryClient } from './api/queryClient'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import { useWorkspaceStore } from './state/workspace'
import { useTrainingAccessStore } from './state/trainingAccess'
import { installNavigationFeedback } from './state/navigation'
import './styles.css'
import './preferences.css'
import './workspace.css'

const pinia = createPinia()
const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.path !== from.path) return { top: 0 }
  },
  routes: [
    { path: '/', redirect: '/today' },
    { path: '/search', name: 'search', component: () => import('./views/SearchWorkspace.vue') },
    { path: '/recent', name: 'recent', component: () => import('./views/RecentWorkspace.vue') },
    { path: '/knowledge', name: 'knowledge', component: () => import('./views/KnowledgeWorkspace.vue') },
    { path: '/knowledge/inbox', name: 'knowledge-inbox', component: () => import('./views/KnowledgeInboxWorkspace.vue') },
    { path: '/knowledge/packages', name: 'knowledge-packages', component: () => import('./views/KnowledgePackagesWorkspace.vue') },
    { path: '/knowledge/merge', name: 'knowledge-merge', component: () => import('./views/KnowledgeMergeWorkspace.vue') },
    { path: '/settings', name: 'settings', component: () => import('./views/SettingsWorkspace.vue') },
    { path: '/settings/data', name: 'data-management', component: () => import('./views/DataManagementWorkspace.vue') },
    { path: '/knowledge/boards', name: 'boards', component: () => import('./views/BoardsWorkspace.vue') },
    { path: '/knowledge/cards', name: 'memory-cards', component: () => import('./views/MemoryWorkspace.vue') },
    { path: '/knowledge/recall', name: 'memory-recall', component: () => import('./views/MemoryRecallWorkspace.vue') },
    { path: '/knowledge/cram', name: 'memory-cram', component: () => import('./views/MemoryCramWorkspace.vue') },
    { path: '/prepare', name: 'prepare', component: () => import('./views/PreparationWorkspace.vue') },
    { path: '/prepare/materials', name: 'materials', component: () => import('./views/MaterialsWorkspace.vue') },
    { path: '/prepare/tasks', name: 'preparation-tasks', component: () => import('./views/PreparationTaskWorkspace.vue') },
    { path: '/prepare/schedule', name: 'schedule', component: () => import('./views/ScheduleWorkspace.vue') },
    { path: '/practice', redirect: '/practice/questions' },
    { path: '/practice/questions', name: 'bank', component: () => import('./views/BankWorkspace.vue') },
    { path: '/practice/collections', name: 'collections', component: () => import('./views/CollectionsWorkspace.vue') },
    { path: '/practice/sessions', name: 'training', component: () => import('./views/TrainingWorkspace.vue') },
    { path: '/ai', name: 'ai', component: () => import('./views/AiWorkspace.vue') },
    { path: '/ai/authorizations', name: 'ai-authorizations', component: () => import('./views/AiAuthorizationsWorkspace.vue') },
    { path: '/review', name: 'review', component: () => import('./views/ReviewWorkspace.vue') },
    { path: '/review/repractice', name: 'repractice', component: () => import('./views/RepracticeWorkspace.vue') },
    { path: '/review/report', name: 'training-report', component: () => import('./views/TrainingReportWorkspace.vue') },
    { path: '/review/recollections', name: 'recollections', component: () => import('./views/RecollectionWorkspace.vue') },
    { path: '/today', name: 'today', component: () => import('./views/TodayWorkspace.vue') },
    { path: '/growth', name: 'growth', component: () => import('./views/GrowthWorkspace.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/knowledge' },
  ],
})
installNavigationFeedback(router)
router.beforeEach((to, from) => {
  const workspace = useWorkspaceStore(pinia)
  if (to.fullPath === from.fullPath) return true
  // 搜索条件变化不会卸载来源问答创建器；离开搜索页仍保留默认草稿和原键保护。
  if (to.name === 'search' && from.name === 'search') return workspace.canLeave([])
  if (to.name === 'growth' && from.name === 'growth') {
    const keys = ['panel', 'targetId', 'targetVersionId', 'zoneId', 'from', 'to', 'topicId', 'dimension', 'linked', 'classified']
    // 地址恢复也遵守原标签的写请求边界；列勾选和记录条件不卸载独立证据编辑器。
    return workspace.canLeave(keys.some(key => JSON.stringify(to.query[key]) !== JSON.stringify(from.query[key]))
      ? ['today-placement-editor', 'growth-next-step-preference'] : [])
  }
  if (to.name === 'knowledge' && from.name === 'knowledge') {
    const keys: string[] = []
    if (to.query.item !== from.query.item) keys.push('editor', 'organization', 'connections', 'create')
    if (to.query.edit !== from.query.edit) keys.push('editor', 'organization', 'connections')
    if (to.query.create !== from.query.create) keys.push('create')
    if (to.query.view !== from.query.view) {
      keys.push('layout', 'search')
      if (to.query.view === 'detail' || from.query.view === 'detail') keys.push('editor', 'organization', 'connections', 'create')
    }
    return workspace.canLeave(keys)
  }
  if (to.name === 'boards' && from.name === 'boards') return workspace.canLeave(['board', 'board-create'])
  if (to.name === 'memory-cards' && from.name === 'memory-cards') {
    const identity = (query: typeof to.query): string | null => {
      const id = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
      if (Object.keys(query).some(key => !['item', 'create', 'sourceItemId', 'sourceVersionId', 'versionId'].includes(key))
        || (query.create !== undefined && query.create !== '1')) return null
      const creating = query.create === '1'
      if (query.item !== undefined && (!id(query.item) || creating)) return null
      if ((query.sourceItemId !== undefined || query.sourceVersionId !== undefined)
        && (!creating || !id(query.sourceItemId) || !id(query.sourceVersionId))) return null
      if (query.versionId !== undefined && (!id(query.item) || !id(query.versionId))) return null
      return creating ? `new:${query.sourceItemId ?? ''}:${query.sourceVersionId ?? ''}` : `item:${query.item ?? ''}`
    }
    const next = identity(to.query), previous = identity(from.query)
    // 同卡历史浏览保留表单；无效地址会卸载编辑器，必须处理草稿及未决参考请求。
    return workspace.canLeave(next !== null && previous !== null && next === previous ? []
      : [...workspace.changes.keys()].filter(key => key.startsWith('memory-')))
  }
  if (to.name === 'memory-recall' && from.name === 'memory-recall') {
    const identity = (query: typeof to.query): string | null => {
      if (Object.keys(query).some(key => key !== 'attempt')) return null
      if (query.attempt === undefined) return 'queue'
      return typeof query.attempt === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(query.attempt)
        ? query.attempt : null
    }
    const next = identity(to.query), previous = identity(from.query)
    return workspace.canLeave(next !== null && previous !== null && next === previous ? []
      : [...workspace.changes.keys()].filter(key => key.startsWith('memory-recall-')))
  }
  if (to.name === 'memory-cram' && from.name === 'memory-cram') {
    const identity = (query: typeof to.query): string | null => {
      if (Object.keys(query).some(key => !['item', 'create'].includes(key))
        || (query.create !== undefined && query.create !== '1')) return null
      if (query.item !== undefined && (query.create === '1' || typeof query.item !== 'string'
        || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(query.item))) return null
      return query.create === '1' ? 'new' : `item:${query.item ?? ''}`
    }
    const next = identity(to.query), previous = identity(from.query)
    // 无效入口会卸载表单，也必须处理固定范围草稿和未知的开始回执。
    return workspace.canLeave(next !== null && previous !== null && next === previous ? []
      : [...workspace.changes.keys()].filter(key => key.startsWith('memory-cram-')))
  }
  if (to.name === 'prepare' && from.name === 'prepare') {
    const editorKey = (query: typeof to.query) => {
      if (query.create === '1') {
        const kind = ['TARGET', 'PROJECT', 'FACT', 'EXPRESSION'].includes(query.kind as string) ? query.kind : 'TARGET'
        return `new-${kind}`
      }
      return typeof query.item === 'string' ? query.item : undefined
    }
    // 历史参数不会卸载同一材料的编辑器，只有更换编辑器才可能丢失本地输入。
    return workspace.canLeave(editorKey(to.query) === editorKey(from.query) ? [] : ['prepare-editor', 'prepare-focus'])
  }
  if (to.name === 'materials' && from.name === 'materials') {
    return workspace.canLeave(to.query.attachment === from.query.attachment ? [] : ['material-upload', 'material-editor'])
  }
  if (to.name === 'schedule' && from.name === 'schedule') {
    const editorKey = (query: typeof to.query) => query.create === '1' ? 'new' : typeof query.item === 'string' ? query.item : undefined
    // 查看同一安排的历史不会重建编辑器，保留当前草稿与未决请求。
    return workspace.canLeave(editorKey(to.query) === editorKey(from.query) ? [] : ['schedule-editor', 'schedule-command'])
  }
  if (to.name === 'preparation-tasks' && from.name === 'preparation-tasks') {
    const editorKey = (query: typeof to.query) => query.create === '1' ? 'new' : typeof query.item === 'string' ? query.item : undefined
    return workspace.canLeave(editorKey(to.query) === editorKey(from.query) ? [] : ['preparation-task-editor', 'preparation-task-command'])
  }
  if (to.name === 'bank' && from.name === 'bank') {
    const editorKey = (query: typeof to.query) => query.create === '1' ? 'new' : typeof query.item === 'string' ? query.item : undefined
    const sameEditor = editorKey(to.query) === editorKey(from.query)
    const reviewKeys = sameEditor && to.query.version === from.query.version ? []
      : [...workspace.changes.keys()].filter(key => key.startsWith('standard-review:'))
    return workspace.canLeave([...(!sameEditor ? ['bank-editor', 'bank-command', 'question-file-import'] : []), ...reviewKeys])
  }
  if (to.name === 'collections' && from.name === 'collections') {
    const editorKey = (query: typeof to.query) => query.create === '1'
      ? `new-${query.kind === 'ROUTE' ? 'ROUTE' : 'LIST'}`
      : typeof query.item === 'string' ? query.item : undefined
    return workspace.canLeave(editorKey(to.query) === editorKey(from.query) ? [] : ['collection-editor', 'collection-command'])
  }
  if (to.name === 'training' && from.name === 'training') {
    const identity = (query: typeof to.query) => query.create === '1' ? 'new' : query.item
    return workspace.canLeave(identity(to.query) === identity(from.query) ? [] : ['training-setup', 'training-workspace', 'training-editor', 'training-command'])
  }
  if (to.name === 'ai' && from.name === 'ai') {
    // 同一创建页切换来源修订也会卸载表单；未知结果与未加入的选区必须先经过离开检查。
    const identity = (query: typeof to.query) => query.create === '1'
      ? JSON.stringify(['new', ...['purpose', 'relationMode', 'reviewKind', 'feedbackId', 'feedbackRevision', 'sessionId', 'questionId', 'answerId', 'recollectionId', 'versionId', 'sourceRevision', 'evaluationTaskId', 'factItemId', 'factVersionId', 'targetItemId', 'targetVersionId', 'cardKind', 'sourceItemId', 'sourceVersionId', 'factSourceKind'].map(key => query[key] ?? null)])
      : query.item
    return workspace.canLeave(identity(to.query) === identity(from.query) ? [] : ['ai-create', 'ai-command', 'ai-detail', 'ai-adoption', 'ai-adoption-command', 'ai-feedback', 'ai-feedback-command'])
  }
  if (to.name === 'review' && from.name === 'review') {
    // 来源、反馈修订和动作都可能在同一路由内变化；未决命令必须先恢复，未保存输入明确处理。
    const sourceKeys = ['sessionId', 'questionId', 'answerId', 'feedbackId', 'feedbackRevision', 'revision', 'taskId', 'observationId', 'aiAttemptId', 'aiReportHash', 'reviewedObservationId', 'reviewedRevision']
    const actionValid = to.query.action === undefined || typeof to.query.action === 'string'
      && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(to.query.action)
    if (actionValid && sourceKeys.every(key => to.query[key] === from.query[key])) {
      return workspace.canLeave([...workspace.changes.keys()].filter(key => key.startsWith('review-action')))
    }
    return workspace.canLeave([...workspace.changes.keys()].filter(key => key.startsWith('review-') || key.startsWith('reviewed-observation:') || key.startsWith('ai-feedback')))
  }
  if (to.name === 'repractice' && from.name === 'repractice') {
    return workspace.canLeave([...workspace.changes.keys()].filter(key => key.startsWith('repractice-')))
  }
  if (to.name === 'recollections' && from.name === 'recollections') {
    const identity = (query: typeof to.query): string | null => {
      const id = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
      const creating = query.create === '1'
      if (query.item !== undefined && (!id(query.item) || creating)) return null
      if (query.eventId !== undefined || query.eventRevision !== undefined) {
        if (!creating || !id(query.eventId) || typeof query.eventRevision !== 'string'
          || !/^(0|[1-9]\d*)$/.test(query.eventRevision) || !Number.isSafeInteger(Number(query.eventRevision))) return null
      }
      if (query.versionId !== undefined && (!id(query.item) || !id(query.versionId))) return null
      return creating ? `new:${query.eventId ?? ''}:${query.eventRevision ?? ''}` : String(query.item ?? '')
    }
    const next = identity(to.query), prior = identity(from.query)
    // 无效参数会卸载编辑器，不能按“同一个 item”绕开保稿和未决命令守卫。
    return workspace.canLeave(next !== null && prior !== null && next === prior ? []
      : [...workspace.changes.keys()].filter(key => key.startsWith('recollection-')))
  }
  return workspace.canLeave()
})
router.beforeEach(async (to, from) => {
  const workspace = useWorkspaceStore(pinia)
  // 管理外壳在训练状态不可用时仍可进入；敏感详情由各自守卫遮挡，前一层离开保护保持。
  if (to.name === 'ai-authorizations' || to.name === 'data-management') return true
  if (!workspace.sessionActive || to.fullPath === from.fullPath) return true
  const access = useTrainingAccessStore(pinia)
  const ready = await access.refresh({ preservePage: true })
  if (!ready) return false
  if (access.restricted && access.needsGuard(to.name, to.query.item, to.query.create)) {
    access.challenge = true
    return false
  }
  access.challenge = false
  return true
})
window.addEventListener('beforeunload', event => {
  if ([...useWorkspaceStore(pinia).changes.values()].some(value => value.dirty || value.locked)) {
    event.preventDefault()
    event.returnValue = ''
  }
})
createApp(App).use(pinia).use(router).use(VueQueryPlugin, { queryClient }).mount('#app')
