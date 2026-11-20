<script setup lang="ts">
import { computed, defineAsyncComponent, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox } from 'naive-ui'
import { ApiError, read } from '../api/client'
import { memoryIdValid, memoryKindLabels } from '../api/memory'
import { memoryUtcMicros } from '../api/memoryReview'
import type { MemoryQueueItem, MemoryReviewQueue, MemoryOpenAttemptPage, MemoryAttemptSummary, MemoryAttemptDetail, MemoryActiveAttempt, MemoryStartAttempt } from '../api/memoryAttempt'
import { memoryReviewQueueValid, memoryOpenAttemptPageValid, memoryAttemptDetailValid, memoryActiveAttemptValid, memoryStartAttemptProblem,
  memoryAttemptErrorText as errorText, memoryAttemptStateLabels, memoryAttemptModeLabels, memoryBudgetKindLabels } from '../api/memoryAttempt'
import { queryClient } from '../api/queryClient'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import MemoryNavigation from '../components/memory/MemoryNavigation.vue'
const MemoryAttemptEditor = defineAsyncComponent(() => import('../components/memory/MemoryAttemptEditor.vue'))
const MemoryReviewSettings = defineAsyncComponent(() => import('../components/memory/MemoryReviewSettings.vue'))

const route = useRoute(), router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), active = ref(true)
const selectedId = computed(() => typeof route.query.attempt === 'string' ? route.query.attempt : undefined)
const routeProblem = computed(() => Object.keys(route.query).some(key => key !== 'attempt') || (route.query.attempt !== undefined && !memoryIdValid(route.query.attempt))
  ? '回忆入口需要一个有效的尝试标识，请从队列或未结束目录重新进入。' : '')
const readable = computed(() => active.value && store.sessionActive && !access.checking && !access.challenge && !access.restricted && !store.changes.get('training-access-command')?.locked)
const includeOverBudget = ref(false), queueCursor = ref<string>(), queueRound = ref(0), queueItems = shallowRef<MemoryQueueItem[]>([]), queueMeta = shallowRef<MemoryReviewQueue>()
const openCursor = ref<string>(), openRound = ref(0), openItems = shallowRef<MemoryAttemptSummary[]>([]), openMeta = shallowRef<MemoryOpenAttemptPage>()
const queueVerified = ref(''), openVerified = ref(''), selectedStart = shallowRef<MemoryQueueItem>(), overConfirmed = ref(false), needsReview = ref(false)
const conflictAttempt = shallowRef<MemoryActiveAttempt>(), conflictLoading = ref(false), conflictError = ref(''), message = ref('')
const receiptMismatch = ref(false), receiptCommand = shallowRef<NonNullable<typeof writer.pending.value>>(), deferred = shallowRef<() => void>()
const stateKey = 'memory-recall-queue-command', changes = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, conflictSequence = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
let boundaryTimer: number | undefined, boundarySequence = 0
const minimumBoundaryDelay = 1000, maximumBoundaryDelay = 60_000
const locked = computed(() => writer.locked.value || receiptMismatch.value || Boolean(deferred.value))
const otherChanges = computed(() => [...store.changes].some(([key, value]) => key.startsWith('memory-recall-') && key !== stateKey && (value.dirty || value.locked)))
const blocked = computed(() => !readable.value || locked.value || otherChanges.value)
const dirty = computed(() => Boolean(selectedStart.value && overConfirmed.value))
const queueKey = computed(() => ['memory', store.sessionEpoch, 'recall-queue', queueRound.value, includeOverBudget.value, queueCursor.value])
const openKey = computed(() => ['memory', store.sessionEpoch, 'recall-open', openRound.value, openCursor.value])
watch([readable, () => store.sessionEpoch], () => {
  generation++; conflictSequence++; resetLists()
  conflictAttempt.value = undefined; conflictLoading.value = false; conflictError.value = ''; overConfirmed.value = false
}, { flush: 'sync' })
watch(queueKey, () => { queueVerified.value = '' }, { flush: 'sync' })
watch(openKey, () => { openVerified.value = '' }, { flush: 'sync' })
const queue = useQuery({ queryKey: queueKey, enabled: readable, staleTime: 0, refetchOnWindowFocus: false, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, cursor = queueCursor.value, round = queueRound.value, include = includeOverBudget.value
  const value = await read<MemoryReviewQueue>(`/memory/review-queue?limit=20&includeOverBudget=${include}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
  requireCurrent(epoch, stamp)
  if (cursor !== queueCursor.value || round !== queueRound.value || include !== includeOverBudget.value) throw contextChanged()
  if (!memoryReviewQueueValid(value, include, 20)) throw mismatch('到期队列、预算或排序字段异常，请从首页重新读取。')
  if (cursor && queueMeta.value && (queueMeta.value.asOf !== value.asOf || queueMeta.value.budget.localDate !== value.budget.localDate
    || queueMeta.value.budget.zoneId !== value.budget.zoneId || queueMeta.value.budget.settingsRevision !== value.budget.settingsRevision)) throw new ApiError(409, 'MEMORY_QUEUE_CHANGED', '队列条件已经变化，请重新读取首页。')
  queueVerified.value = JSON.stringify(queueKey.value)
  scheduleBoundaryRefresh(value)
  return value
} })
const settingsRequired = computed(() => queue.error.value instanceof ApiError && queue.error.value.code === 'MEMORY_SETTINGS_REQUIRED')
const openList = useQuery({ queryKey: openKey, enabled: readable, staleTime: 0, refetchOnWindowFocus: false, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, cursor = openCursor.value, round = openRound.value
  const value = await read<MemoryOpenAttemptPage>(`/memory/attempts/open?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
  requireCurrent(epoch, stamp)
  if (cursor !== openCursor.value || round !== openRound.value) throw contextChanged()
  if (!memoryOpenAttemptPageValid(value, 20)) throw mismatch('未结束目录的身份、状态或顺序异常，请重新读取。')
  openVerified.value = JSON.stringify(openKey.value)
  return value
} })
watch([queue.data, queue.dataUpdatedAt, queueKey, readable], () => {
  if (!readable.value || queueVerified.value !== JSON.stringify(queueKey.value)) return
  const value = queryClient.getQueryData<MemoryReviewQueue>(queueKey.value)
  if (!value || !memoryReviewQueueValid(value, includeOverBudget.value, 20)) return
  // 跨页数据可变化；按业务身份去重，不把追加页当成跨请求冻结快照。
  queueItems.value = queueCursor.value ? [...new Map([...queueItems.value, ...value.items].map(item => [item.cardId, item])).values()] : value.items
  queueMeta.value = value
  if (selectedStart.value && !same(queueItems.value.find(item => item.cardId === selectedStart.value!.cardId), selectedStart.value)) needsReview.value = true
}, { immediate: true })
watch([openList.data, openList.dataUpdatedAt, openKey, readable], () => {
  if (!readable.value || openVerified.value !== JSON.stringify(openKey.value)) return
  const value = queryClient.getQueryData<MemoryOpenAttemptPage>(openKey.value)
  if (value && memoryOpenAttemptPageValid(value, 20)) { openItems.value = openCursor.value ? [...new Map([...openItems.value, ...value.items].map(item => [item.id, item])).values()] : value.items; openMeta.value = value }
}, { immediate: true })
const queueReady = computed(() => readable.value && queueVerified.value === JSON.stringify(queueKey.value) && !queue.isFetching.value && !queue.error.value)
const currentSelection = computed(() => selectedStart.value ? queueItems.value.find(item => item.cardId === selectedStart.value!.cardId) : undefined)
const selectionStale = computed(() => Boolean(selectedStart.value && (!currentSelection.value || !same(selectedStart.value, currentSelection.value))))
const requiresBudget = computed(() => selectedStart.value?.requiresBudgetConfirmation === true)
const canStart = computed(() => !blocked.value && queueReady.value && Boolean(selectedStart.value) && !needsReview.value && !selectionStale.value && (!requiresBudget.value || overConfirmed.value))
watch([dirty, locked], ([changed, waiting]) => { changes.dirty = changed; changes.locked = waiting; store.changes.set(stateKey, changes) }, { immediate: true, flush: 'sync' })
watch([writer.error, writer.pending], ([error, pending]) => {
  if (!(error instanceof ApiError) || pending || receiptMismatch.value || error.status !== 409) return
  needsReview.value = true; overConfirmed.value = false
  if (readable.value) { refreshLists(); if (error.code === 'MEMORY_ATTEMPT_OPEN') void findActiveAttempt() }
})
onMounted(() => { window.addEventListener('focus', onFocus); document.addEventListener('visibilitychange', onVisibility) })
onActivated(() => { active.value = true; refreshLists() })
onDeactivated(() => { active.value = false; clearBoundaryTimer() })
onUnmounted(() => { alive = false; generation++; conflictSequence++; clearBoundaryTimer(); window.removeEventListener('focus', onFocus); document.removeEventListener('visibilitychange', onVisibility); if (store.changes.get(stateKey) === changes) store.changes.delete(stateKey) })

function same(left: unknown, right: unknown): boolean {
  if (left === right) return true
  if (Array.isArray(left)) return Array.isArray(right) && left.length === right.length && left.every((item, index) => same(item, right[index]))
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object' || Array.isArray(right)) return false
  return Object.keys(left).length === Object.keys(right).length && Object.entries(left).every(([key, value]) => Object.hasOwn(right, key) && same(value, (right as Record<string, unknown>)[key]))
}
function copy<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
function contextChanged() { return new ApiError(0, 'MEMORY_QUEUE_CONTEXT_CHANGED', '队列或访问条件已变化，请重新读取。') }
function mismatch(message: string) { return new ApiError(502, 'MEMORY_RECALL_QUEUE_INVALID', message) }
function requireCurrent(epoch: number, stamp: number) { if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation) throw contextChanged() }
function resetLists() {
  clearBoundaryTimer()
  // 清空已读页时同时废弃两个游标及读取轮次；权限恢复不能从旧第二页开始。
  queueCursor.value = undefined; queueRound.value++; queueItems.value = []; queueMeta.value = undefined; queueVerified.value = ''
  openCursor.value = undefined; openRound.value++; openItems.value = []; openMeta.value = undefined; openVerified.value = ''
  if (selectedStart.value) needsReview.value = true
}
function refreshLists() { if (readable.value) resetLists() }
function clearBoundaryTimer() {
  boundarySequence++
  if (boundaryTimer !== undefined) { window.clearTimeout(boundaryTimer); boundaryTimer = undefined }
}
function scheduleBoundaryRefresh(value: MemoryReviewQueue) {
  clearBoundaryTimer()
  if (!alive || !readable.value || document.visibilityState !== 'visible') return
  const serverTime = memoryUtcMicros(value.serverTime), dayEnd = memoryUtcMicros(value.budget.windowEnd)
  const nextDue = value.nextDueAt === null ? undefined : memoryUtcMicros(value.nextDueAt)
  if (serverTime === undefined || dayEnd === undefined) return
  // asOf 只固定本轮到期上界；下一页的 nextDueAt 可能已早于本次 serverTime，需回首页。
  const boundary = nextDue !== undefined && nextDue < dayEnd ? nextDue : dayEnd
  const remainingMicros = boundary - serverTime
  const delay = remainingMicros > 0n ? Math.max(minimumBoundaryDelay, Number((remainingMicros + 999n) / 1000n)) : minimumBoundaryDelay
  const receivedAt = performance.now(), sequence = boundarySequence, epoch = store.sessionEpoch, stamp = generation, round = queueRound.value
  // 用服务器相对间隔和单调计时避开本机时钟偏差；向上取整并限频，避免微秒边界紧循环。
  const tick = () => {
    if (!alive || !readable.value || document.visibilityState !== 'visible' || sequence !== boundarySequence
      || epoch !== store.sessionEpoch || stamp !== generation || round !== queueRound.value) return
    boundaryTimer = undefined
    const remaining = delay - (performance.now() - receivedAt)
    if (remaining > 0) boundaryTimer = window.setTimeout(tick, Math.min(maximumBoundaryDelay, Math.max(minimumBoundaryDelay, Math.ceil(remaining))))
    else refreshLists()
  }
  boundaryTimer = window.setTimeout(tick, Math.min(maximumBoundaryDelay, delay))
}
function onFocus() { if (readable.value && document.visibilityState === 'visible') refreshLists() }
function onVisibility() { if (document.visibilityState !== 'visible') clearBoundaryTimer(); else if (readable.value) refreshLists() }
function changed() { refreshLists() }
function setInclude(value: boolean) { if (!blocked.value) { includeOverBudget.value = value; refreshLists() } }
function choose(item: MemoryQueueItem) {
  if (blocked.value || !queueReady.value || (dirty.value && !store.canLeave([stateKey]))) return
  selectedStart.value = copy(item); overConfirmed.value = false; needsReview.value = false; conflictAttempt.value = undefined; conflictError.value = ''; message.value = ''; writer.error.value = undefined
}
function cancelSelection() { if (!blocked.value && store.canLeave([stateKey])) { selectedStart.value = undefined; overConfirmed.value = false; needsReview.value = false; conflictAttempt.value = undefined } }
function adoptSelection() {
  if (blocked.value || !queueReady.value || !currentSelection.value) return
  selectedStart.value = copy(currentSelection.value); overConfirmed.value = false; needsReview.value = false; writer.error.value = undefined
}
function start() {
  if (!canStart.value || !selectedStart.value) return
  const fixed = copy(selectedStart.value)
  const body: MemoryStartAttempt = { expectedCardRevision: fixed.cardRevision, expectedGateRevision: fixed.gateRevision, expectedScheduleRevision: fixed.scheduleRevision,
    mode: 'REGULAR', countRegular: true, cramBatchId: null, continueBeyondBudget: overConfirmed.value }
  if (memoryStartAttemptProblem(body)) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; message.value = ''
  let original: typeof writer.pending.value
  void writer.run<unknown>(`/memory/cards/${fixed.cardId}/attempts`, 'POST', body, value => {
    if (!alive) return
    if (!startReceiptValid(value, fixed, body)) { receiptMismatch.value = true; receiptCommand.value = original; writer.error.value = mismatch('开始回执与所选卡片、排程或预算确认不一致，原请求保留，请核对原请求结果。'); return }
    const id = value.attempt.id
    const accept = () => {
      receiptMismatch.value = false; receiptCommand.value = undefined; deferred.value = undefined; writer.error.value = undefined
      selectedStart.value = undefined; overConfirmed.value = false; needsReview.value = false; conflictAttempt.value = undefined
      message.value = '开始请求已确认。正在重新读取这次尝试的当前状态。'
      refreshLists(); void queryClient.invalidateQueries({ queryKey: ['memory'] }); openAttempt(id)
    }
    // 原 START 回执可能早于后续揭示或失效；只拿身份进入编辑器，由编辑器重读当前事实。
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) deferred.value = accept
    else accept()
  })
  original = writer.pending.value
}
function startReceiptValid(value: unknown, fixed: MemoryQueueItem, body: MemoryStartAttempt): value is MemoryAttemptDetail {
  if (!memoryAttemptDetailValid(value, undefined, fixed.cardId)) return false
  const attempt = value.attempt, snapshot = attempt.start
  return attempt.state === 'THINKING' && attempt.revision === 0 && attempt.lastEventSequence === 1 && attempt.responseSavedAt === null
    && snapshot.mode === 'REGULAR' && snapshot.countRegular && snapshot.cramBatchId === null && snapshot.cardVersionId === fixed.cardVersionId
    && snapshot.cardVersionNumber === fixed.cardVersionNumber && snapshot.cardRevision === fixed.cardRevision && snapshot.gateRevision === fixed.gateRevision
    && snapshot.schedule?.revision === fixed.scheduleRevision && snapshot.schedule.cycleId === fixed.cycleId
    && snapshot.admission?.continueBeyondBudget === body.continueBeyondBudget && value.version.content.title === fixed.title && value.version.content.kind === fixed.kind
}
async function findActiveAttempt() {
  if (!readable.value || !selectedStart.value || conflictLoading.value) return
  const cardId = selectedStart.value.cardId, epoch = store.sessionEpoch, stamp = generation, sequence = ++conflictSequence
  conflictLoading.value = true; conflictError.value = ''
  try {
    const value = await read<MemoryActiveAttempt>(`/memory/cards/${cardId}/active-attempt`)
    requireCurrent(epoch, stamp)
    if (cardId !== selectedStart.value?.cardId || sequence !== conflictSequence) return
    if (!memoryActiveAttemptValid(value, cardId)) throw mismatch('未决尝试与所选卡片不一致，请重新读取。')
    conflictAttempt.value = value
  } catch (error) { if (alive && epoch === store.sessionEpoch && stamp === generation && sequence === conflictSequence) conflictError.value = errorText(error) }
  finally { if (alive && sequence === conflictSequence) conflictLoading.value = false }
}
function retry() { if (readable.value && !writer.busy.value && !otherChanges.value) { if (receiptMismatch.value && receiptCommand.value) writer.pending.value = receiptCommand.value
  commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry() } }
function receiveDeferred() { if (readable.value && !otherChanges.value && deferred.value) deferred.value() }
function openAttempt(id: string) { if (readable.value && !locked.value && memoryIdValid(id)) void router.push({ name: 'memory-recall', query: { attempt: id } }) }
function close() { if (readable.value && !locked.value) void router.push({ name: 'memory-recall' }) }
function settings() { if (readable.value && !locked.value) void router.push({ name: 'memory-cards' }) }
function moreQueue() { if (queueReady.value && queueMeta.value?.nextCursor) queueCursor.value = queueMeta.value.nextCursor }
function moreOpen() { if (readable.value && !openList.isFetching.value && !openList.error.value && openMeta.value?.nextCursor) openCursor.value = openMeta.value.nextCursor }
function time(value: string | null) { if (!value) return '尚无安排'; try { return new Date(value).toLocaleString('zh-CN', queueMeta.value?.budget.zoneId ? { timeZone: queueMeta.value.budget.zoneId } : undefined) } catch { return value } }
</script>

<template>
  <div class="recall-product">
  <header class="page-heading"><div><h1>回忆卡</h1><p class="muted">{{ queueMeta ? `${queueMeta.budget.localDate} · ${queueMeta.recommendedCards} 张待回忆` : '今日队列' }}</p></div><NButton :disabled="!readable || locked" @click="settings">卡片与复习设置</NButton></header>
  <p v-if="!readable" class="notice recall-notice" role="status">资料访问暂未恢复。题面和回答已遮挡，未保存输入与原请求仍在当前页面保留。</p>
  <details class="recall-management"><summary>复习设置与今日预算</summary><MemoryNavigation />
  <div v-if="readable && queueMeta" class="daily-budget" aria-label="当日复习建议"><div><span>复习日</span><strong>{{ queueMeta.budget.localDate }}</strong><small>{{ queueMeta.budget.zoneId }}</small></div><div><span>新卡已计入 / 建议</span><strong>{{ queueMeta.budget.newCards }} / {{ queueMeta.budget.newLimit }}</strong><small>按不同卡片计数</small></div><div><span>复习卡已计入 / 建议</span><strong>{{ queueMeta.budget.reviewCards }} / {{ queueMeta.budget.reviewLimit }}</strong><small>同日重复步骤不另占名额</small></div><div><span>当前可回忆 / 推荐</span><strong>{{ queueMeta.eligibleDueCards }} / {{ queueMeta.recommendedCards }}</strong><small>打开列表不计为完成</small></div></div>
  </details>
  <section v-if="readable && (selectedStart || writer.error.value || deferred || message)" class="start-panel" aria-label="开始回忆确认">
    <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value || (receiptMismatch && receiptCommand)" text :loading="writer.busy.value" :disabled="otherChanges" @click="retry">核对原开始请求</NButton></p><p v-if="deferred" class="notice" role="status">原开始请求已返回有效回执。<NButton text :disabled="otherChanges" @click="receiveDeferred">接收回执并恢复当前尝试</NButton></p>
    <template v-if="selectedStart"><h2>{{ selectedStart.title }}</h2><p class="field-help">{{ memoryBudgetKindLabels[selectedStart.budgetKind] }} · v{{ selectedStart.cardVersionNumber }} · 到期 {{ time(selectedStart.effectiveDueAt) }}。开始后先独立回答，参考不会预先读取。</p><p v-if="needsReview || selectionStale" class="notice">队列或访问条件已变化，请核对当前列表后再开始。<NButton text :disabled="locked || queue.isFetching.value" @click="refreshLists">重读队列首页</NButton><NButton text :disabled="blocked || !queueReady || !currentSelection" @click="adoptSelection">采用列表当前版本</NButton><NButton text :disabled="locked" :loading="conflictLoading" @click="findActiveAttempt">查找这张卡的未结束尝试</NButton></p><NCheckbox v-if="requiresBudget" v-model:checked="overConfirmed" :disabled="blocked || !queueReady || needsReview || selectionStale">当前类别已达到今日建议数量，我明确选择继续这张卡</NCheckbox><p v-else-if="!selectedStart.recommended" class="field-help">这张卡在本轮推荐之外。是否需要超出建议确认，仍由服务器在开始时核对。</p><div class="row-actions"><NButton type="primary" :disabled="!canStart" :loading="writer.busy.value" @click="start">开始这次独立回忆</NButton><NButton text :disabled="blocked" @click="cancelSelection">取消选择</NButton></div></template>
    <p v-if="conflictError" class="notice error" role="alert">{{ conflictError }}</p><p v-if="conflictAttempt" class="notice">{{ conflictAttempt.attempt ? '这张卡已有未结束尝试，请恢复原记录。' : '当前没有未结束尝试，请重新核对到期队列。' }}<NButton v-if="conflictAttempt.attempt" text :disabled="locked" @click="openAttempt(conflictAttempt.attempt.id)">恢复原尝试</NButton></p>
  </section>
  <p v-if="routeProblem" class="notice error recall-notice" role="alert">{{ routeProblem }}<NButton text :disabled="!readable || locked" @click="close">返回队列</NButton></p>
  <div class="recall-workspace">
    <aside class="recall-catalog" aria-label="到期与未结束回忆"><header><h2>接着回忆</h2><NButton text size="small" :disabled="!readable || queue.isFetching.value || openList.isFetching.value" @click="refreshLists">刷新首页</NButton></header>
      <template v-if="readable">
        <section class="open-attempts"><h3>尚未结束</h3><p class="field-help">离开页面不会替你跳过或评分。</p><p v-if="openList.isPending.value" role="status">正在读取未结束目录…</p><p v-if="openList.error.value" class="notice error" role="alert">{{ errorText(openList.error.value) }}</p><p v-if="openMeta && !openItems.length && !openList.error.value" class="field-help">当前没有未结束尝试。</p><div class="catalog-list"><button v-for="item in openItems" :key="item.id" :aria-current="selectedId === item.id ? 'true' : undefined" :disabled="locked" @click="openAttempt(item.id)"><strong>{{ item.title }}</strong><span>{{ memoryAttemptStateLabels[item.state] }} · {{ memoryAttemptModeLabels[item.mode] }}</span><small>第 {{ item.sequence }} 次 · v{{ item.cardVersionNumber }}</small></button></div><NButton v-if="openMeta?.nextCursor" text size="small" :disabled="openList.isFetching.value || !!openList.error.value" @click="moreOpen">读取更多未结束尝试</NButton></section>
        <section class="due-cards"><h3>到期队列</h3><template v-if="!settingsRequired"><NCheckbox :checked="includeOverBudget" :disabled="blocked" @update:checked="setInclude">也查看本轮推荐之外的到期卡</NCheckbox><p class="field-help">显示更多不等于允许超出建议；需要时会在开始前单独确认。</p></template><p v-if="queue.isPending.value" role="status">正在读取到期队列…</p><p v-if="settingsRequired" class="notice" role="status">先设置复习时区，再为卡片启用排程。<NButton v-if="selectedId" text :disabled="blocked" @click="settings">去设置</NButton></p><p v-else-if="queue.error.value" class="notice error" role="alert">{{ errorText(queue.error.value) }}<NButton text :disabled="queue.isFetching.value" @click="refreshLists">重新读取首页</NButton></p><div v-if="queueMeta && !queueItems.length && !queue.error.value" class="catalog-empty"><strong>{{ queueMeta.eligibleDueCards ? '当前推荐中没有待开始的卡' : '当前没有符合条件的到期卡' }}</strong><p>{{ queueMeta.eligibleDueCards ? '可以明确显示推荐之外的到期卡。' : '可先恢复未结束尝试，或在卡片中核对来源和排程。' }}</p><p v-if="queueMeta.nextDueAt">下一次到期：{{ time(queueMeta.nextDueAt) }}</p></div><div class="catalog-list"><button v-for="item in queueItems" :key="item.cardId" :aria-pressed="selectedStart?.cardId === item.cardId" :disabled="blocked || !queueReady" @click="choose(item)"><strong>{{ item.title }}</strong><span>{{ memoryKindLabels[item.kind] }} · {{ memoryBudgetKindLabels[item.budgetKind] }} · v{{ item.cardVersionNumber }}</span><small>{{ item.countedToday ? '今日已计入，继续到期步骤' : item.requiresBudgetConfirmation ? '开始前需确认超过今日建议' : item.recommended ? '本轮推荐' : '本轮推荐之外' }}</small><small v-if="item.warnings.length">部分概念来源已归档，仍有其他有效依据</small></button></div><NButton v-if="queueMeta?.nextCursor" text size="small" :disabled="!queueReady" @click="moreQueue">读取更多到期卡</NButton></section>
      </template><p v-else class="field-help">恢复资料访问后可继续读取队列。</p>
    </aside>
    <MemoryAttemptEditor v-if="selectedId && !routeProblem" :key="selectedId" :id="selectedId" :disabled="!readable" @changed="changed" @close="close" />
    <section v-else-if="readable && settingsRequired" class="recall-onboarding" aria-label="首次回忆设置"><header><h2>开始安排每日回忆</h2><p>先确认时区和每天的建议数量，设置会用于安排常规回忆。</p></header><MemoryReviewSettings :disabled="!readable || locked" @saved="changed" /><p class="setup-next">保存后，进入“管理卡片与排程”，核对卡片来源并启用排程；到期卡片就会出现在这里。</p></section>
    <section v-else class="recall-welcome"><p class="section-label">一次只处理一份独立回答</p><h2>从左侧选择一张到期卡</h2><p>先在心中回忆或写下答案，明确揭示后再对照个人参考，最后选择这一次的自评。</p><p>已经开始的尝试留在“尚未结束”中。来源、卡片或排程发生变化时，旧记录保留，并按实际状态恢复。</p><NButton :disabled="!readable || locked" @click="settings">管理卡片与排程</NButton></section>
  </div>
  </div>
</template>

<style scoped>
.daily-budget, .recall-workspace, .start-panel, .recall-notice { max-width: 1320px; margin-left: auto; margin-right: auto; }.daily-budget { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin-bottom: 23px; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }.daily-budget > div { display: grid; gap: 9px; padding: 18px 20px; border-right: 1px solid var(--line); }.daily-budget > div:first-child { padding-left: 0; }.daily-budget > div:last-child { border-right: 0; }.daily-budget span, .daily-budget small { font-size: 12px; color: var(--muted); }.daily-budget strong { font-size: 22px; font-variant-numeric: tabular-nums; }.start-panel { display: grid; gap: 13px; margin-bottom: 22px; padding: 20px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }.start-panel h2 { margin: 0; font-size: 19px; }.recall-notice { margin-bottom: 20px; }.row-actions { display: flex; gap: 11px; flex-wrap: wrap; align-items: center; }.recall-workspace { display: grid; grid-template-columns: 300px minmax(0, 1fr); min-height: 620px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }.recall-catalog { min-width: 0; padding: 19px 16px; border-right: 1px solid var(--line); background: var(--surface-soft); border-radius: 8px 0 0 8px; }.recall-catalog > header { display: flex; gap: 12px; align-items: baseline; justify-content: space-between; margin-bottom: 19px; }.recall-catalog h2 { font-size: 16px; margin: 0; }.recall-catalog h3 { font-size: 14px; margin: 0; }.open-attempts, .due-cards { display: grid; gap: 11px; }.due-cards { padding-top: 20px; margin-top: 20px; border-top: 1px solid var(--line); }.catalog-list { display: grid; gap: 5px; }.catalog-list button { display: grid; gap: 7px; padding: 12px 9px; text-align: left; width: 100%; min-width: 0; border-radius: 5px; overflow-wrap: anywhere; }.catalog-list button:hover, .catalog-list button[aria-current=true], .catalog-list button[aria-pressed=true] { background: var(--surface-deep); }.catalog-list strong { font-size: 13px; }.catalog-list span, .catalog-list small { font-size: 12px; color: var(--muted); }.catalog-list button:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }.catalog-empty { padding: 14px 0; display: grid; gap: 9px; font-size: 13px; }.catalog-empty p { color: var(--muted); margin: 0; }.recall-welcome { display: grid; gap: 20px; justify-items: start; align-content: center; max-width: 740px; padding: 45px; }.recall-welcome h2 { font-size: 25px; margin: 0; text-wrap: balance; }.recall-welcome > p:not(.section-label) { line-height: 1.8; color: var(--muted); margin: 0; }.start-panel :deep(.n-checkbox__label), .due-cards :deep(.n-checkbox__label) { white-space: normal; }@media (max-width: 1050px) { .recall-workspace { grid-template-columns: 250px minmax(0, 1fr); }.daily-budget { grid-template-columns: repeat(2, minmax(0, 1fr)); }.daily-budget > div:nth-child(2) { border-right: 0; }.daily-budget > div:nth-child(3) { padding-left: 0; } }@media (max-width: 760px) { .recall-workspace { grid-template-columns: 1fr; }.recall-catalog { border-right: 0; border-bottom: 1px solid var(--line); border-radius: 8px 8px 0 0; }.catalog-list { max-height: 270px; overflow-y: auto; }.recall-welcome { padding: 30px 22px; }.daily-budget strong { font-size: 19px; }.daily-budget > div { padding: 15px 12px; } }
</style>

<style scoped>
.recall-product { min-width: 0; }.recall-management { margin-bottom: 20px; }.recall-management > summary { font-size: 16px; color: var(--muted); cursor: pointer; padding: 8px 0; }.recall-management[open] { padding: 20px; border: 1px solid var(--line); border-radius: 16px; background: var(--surface); }.recall-management :deep(.memory-navigation) { margin: 16px 0; max-width: none; }
.recall-workspace { max-width: none; grid-template-columns: 300px minmax(0, 1fr); gap: 12px; border: 0; background: none; min-height: 690px; align-items: start; }
.recall-catalog { padding: 27px 17px; border: 1px solid var(--line); border-radius: 20px; background: var(--surface); min-height: 690px; }.recall-catalog h2 { font-size: 23px; }.recall-catalog h3 { font-size: 20px; }.open-attempts, .due-cards { gap: 16px; }
.catalog-list { gap: 8px; }.catalog-list button { padding: 18px 15px; border: 1px solid transparent; border-radius: 13px; gap: 10px; }.catalog-list button[aria-current=true], .catalog-list button[aria-pressed=true] { border-color: var(--brand); background: var(--brand-soft); }.catalog-list strong { font-size: 22px; font-weight: 500; }.catalog-list span { font-size: 17px; }.catalog-list small { font-size: 15px; }.recall-catalog :deep(.n-checkbox__label) { font-size: 15px; }.catalog-empty { font-size: 18px; line-height: 1.7; }
.recall-welcome { max-width: none; min-height: 690px; padding: 45px; border: 1px solid var(--line); border-radius: 20px; background: var(--surface); }.recall-welcome h2 { font-size: 30px; }.recall-welcome .section-label { display: none; }.start-panel { max-width: none; padding: 24px; border-radius: 18px; }.start-panel h2 { font-size: 25px; }
.recall-onboarding { min-width: 0; border: 1px solid var(--line); border-radius: 20px; background: var(--surface); overflow-wrap: anywhere; }.recall-onboarding > header { display: grid; gap: 12px; padding: 28px 26px 0; }.recall-onboarding h2 { margin: 0; font-size: 26px; }.recall-onboarding p { margin: 0; line-height: 1.7; }.recall-onboarding :deep(.memory-settings) { border-bottom: 0; }.setup-next { padding: 0 26px 26px; color: var(--muted); }
@media(max-width:1100px) { .recall-workspace { grid-template-columns: 245px minmax(0, 1fr); } }
@media(max-width:760px) { .recall-workspace { grid-template-columns: minmax(0, 1fr); }.recall-catalog { min-height: auto; }.recall-welcome { padding: 28px; min-height: 400px; } }
</style>
