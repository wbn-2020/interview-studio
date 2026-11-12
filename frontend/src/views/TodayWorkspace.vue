<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, read } from '../api/client'
import type { TodayCalendarPage, TodayCalendarQuery, TodayCapability, TodayContinuation, TodayCount, TodayDayContext, TodayItem,
  TodayItemsQuery, TodayOverview, TodayOverviewQuery, TodayPage, TodaySection, TodaySourceDetail, TodaySourceKind, TodaySourceRef } from '../api/today'
import { todayAvailabilityLabels, todayCalendarPageValid, todayCalendarPath, todayContinuationRoute, todayDateRangeProblem, todayDateValid,
  todayErrorText, todayExclusionReasonLabels, todayItemsPath, todayOverviewPath, todayOverviewValid, todayPageValid, todaySectionLabels,
  todaySections, todaySourceDetailValid, todaySourceKey, todaySourceKindLabels, todaySourceKinds, todaySourcePath, todayZoneValid } from '../api/today'
import { memoryUtcMicros } from '../api/memoryReview'
import { browserZone, dateKey } from '../api/schedule'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import TodaySourceRow from '../components/today/TodaySourceRow.vue'
import TodayTargetPicker from '../components/today/TodayTargetPicker.vue'
import TodayQuickNote from '../components/today/TodayQuickNote.vue'
import TodayPlanWorkspace from '../components/today/TodayPlanWorkspace.vue'

type TargetFilter = { itemId: string; title: string }
type Filters = { zoneId: string; target: TargetFilter | null; includeOverBudget: boolean }
type View = 'overview' | 'source' | 'calendar' | 'plans'
const router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore()
const active = ref(true), visible = ref(document.visibilityState === 'visible')
const readable = computed(() => active.value && visible.value && store.sessionActive && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const initialZone = todayZoneValid(browserZone()) ? browserZone() : 'UTC'
const input = reactive<Filters>({ zoneId: initialZone, target: null, includeOverBudget: false })
const applied = shallowRef<Filters>({ ...input }), filterError = ref(''), targetPickerOpen = ref(false), noteOpen = ref(false), noteId = ref('')
const filterChanged = computed(() => JSON.stringify(input) !== JSON.stringify(applied.value))
const view = ref<View>('overview'), notice = ref('')
const overview = shallowRef<TodayOverview>(), overviewBusy = ref(false), overviewError = shallowRef<unknown>()
const planningContext = shallowRef<TodayDayContext>(), planningEpoch = ref(-1), planMounted = ref(false), planRefresh = ref(0)
const planIntent = shallowRef<{ source: TodaySourceRef; mode: 'edit' | 'candidate'; sequence: number }>()
const planReadable = computed(() => readable.value && planningEpoch.value === store.sessionEpoch && planningContext.value?.zoneId === applied.value.zoneId)
const sourceKind = ref<TodaySourceKind>('TRAINING_SESSION'), section = ref<TodaySection>('CONTINUE')
const items = shallowRef<TodayItem[]>([]), page = shallowRef<TodayPage>(), pageBusy = ref(false), pageError = shallowRef<unknown>()
const calendarKind = ref<TodaySourceKind>('INTERVIEW_EVENT'), calendarInput = reactive({ from: '', to: '' })
const calendarRange = shallowRef<{ from: string; to: string }>(), calendarProblem = ref('')
const calendarItems = shallowRef<TodayItem[]>([]), calendarPage = shallowRef<TodayCalendarPage>(), calendarBusy = ref(false), calendarError = shallowRef<unknown>()
const selectedSource = shallowRef<TodaySourceRef>(), sourceDetail = shallowRef<TodaySourceDetail>(), sourceBusy = ref(false), sourceError = shallowRef<unknown>()
const sourceOptions = todaySourceKinds.map(value => ({ label: todaySourceKindLabels[value], value }))
const sectionOptions = todaySections.map(value => ({ label: todaySectionLabels[value], value }))
const sectionDescriptions: Record<TodaySection, string> = { CONTINUE: '接着完成已开始的工作与已保存草稿。', ATTENTION: '核对到期提醒、来源变化和待处理动作。', TODAY: '查看今天的实际安排与建议回忆队列。', BACKLOG: '为尚未安排的任务和近期面试留出时间。' }
const sourceQueryRoots = new Set(['knowledge', 'training', 'training-question', 'training-questions', 'training-session', 'preparation', 'preparation-tasks', 'schedule', 'memory', 'review', 'review-actions', 'repractice', 'recollections'])
let alive = true, generation = 0, overviewSequence = 0, pageSequence = 0, calendarSequence = 0, sourceSequence = 0
let pageCursors = new Set<string>(), calendarCursors = new Set<string>()
let boundaryTimer: number | undefined, boundarySequence = 0, writeRefreshTimer: number | undefined
let unsubscribe: (() => void) | undefined
let planSequence = 0

const calendarGroups = computed(() => {
  const groups = new Map<string, TodayItem[]>()
  for (const item of calendarItems.value) {
    const day = item.time.kind === 'LOCAL_DATE' ? item.time.localDate : item.time.kind === 'INSTANT' ? dateKey(item.time.instant, applied.value.zoneId) : '未注明日期'
    groups.set(day, [...(groups.get(day) ?? []), item])
  }
  return [...groups].map(([day, values]) => ({ day, items: values }))
})
const allKnownEmpty = computed(() => overview.value?.capabilities.every(value => value.capability.status === 'READY' && value.count.mode === 'EXACT' && value.count.value === 0) === true)

function countLabel(value: TodayCount): string { return value.mode === 'UNAVAILABLE' ? '数量未知' : `${value.mode === 'LOWER_BOUND' ? '至少 ' : ''}${value.value} 项` }
function capabilityText(value: TodayCapability): string {
  if (value.status === 'NOT_CONFIGURED') return '尚未保存回忆设置，可前往回忆卡管理配置。'
  if (value.status === 'DEPENDENCY_PENDING') return '这个来源的查询尚未接入，当前数量未知。'
  if (value.status === 'ERROR') return '这个来源本次读取失败，请刷新或返回来源工作区核对。'
  return '本次读取可用'
}
function time(value: string) { return new Intl.DateTimeFormat('zh-CN', { timeZone: applied.value.zoneId, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date(value)) }
function overviewRequest(): TodayOverviewQuery { return { zoneId: applied.value.zoneId, targetId: applied.value.target?.itemId ?? null, includeOverBudget: applied.value.includeOverBudget } }
function changed(): ApiError { return new ApiError(409, 'TODAY_CURSOR_CHANGED', '读取条件已经变化，请从首页重新读取。') }
function invalid(): ApiError { return new ApiError(0, 'TODAY_RESPONSE_INVALID', '本次读取内容与查询契约不一致。') }
function current(epoch: number, stamp: number): boolean { return alive && readable.value && epoch === store.sessionEpoch && stamp === generation }
function samePageContext(previous: TodayDayContext, next: TodayDayContext): boolean {
  return previous.zoneId === next.zoneId && previous.localDate === next.localDate && memoryUtcMicros(previous.asOf) === memoryUtcMicros(next.asOf)
}
function merge(previous: TodayItem[], next: TodayItem[]): TodayItem[] {
  // 跨页仍读取当前数据，以稳定来源身份去重；不把各页数量相加成精确总数。
  return [...new Map([...previous, ...next].map(item => [todaySourceKey(item.source), item])).values()]
}
function clearBoundary() { boundarySequence++; if (boundaryTimer !== undefined) window.clearTimeout(boundaryTimer); boundaryTimer = undefined }
function resetPage() { pageSequence++; page.value = undefined; items.value = []; pageError.value = undefined; pageBusy.value = false; pageCursors = new Set() }
function resetCalendar() { calendarSequence++; calendarPage.value = undefined; calendarItems.value = []; calendarError.value = undefined; calendarBusy.value = false; calendarCursors = new Set() }
function refreshAll(message = '') {
  generation++; overviewSequence++; sourceSequence++; clearBoundary()
  // 重读期间暂停日期相关写入，但保留编辑器、输入与原请求，直到当前日期重新核实。
  planningEpoch.value = -1
  planRefresh.value++
  if (writeRefreshTimer !== undefined) window.clearTimeout(writeRefreshTimer)
  writeRefreshTimer = undefined
  overview.value = undefined; overviewError.value = undefined; overviewBusy.value = false
  sourceDetail.value = undefined; sourceError.value = undefined; sourceBusy.value = false
  resetPage(); resetCalendar()
  if (message) notice.value = message
  if (!readable.value || !alive) return
  void loadOverview()
  if (view.value === 'source') void loadItems()
  if (view.value === 'calendar' && calendarRange.value) void loadCalendar()
  if (selectedSource.value) void loadSource()
}
function scheduleBoundary(value: TodayOverview | undefined, requestStarted: number) {
  clearBoundary()
  if (!alive || !readable.value) return
  // 预览没有覆盖全部来源和原时区日界，每 60 秒实际重读；已知更早的边界仍优先触发。
  let deadline = requestStarted + 60000
  if (value) {
    const server = memoryUtcMicros(value.context.serverTime)!
    const instants = [value.context.endAt, value.memoryQueue?.budget.windowEnd, value.memoryQueue?.nextDueAt]
    for (const preview of value.sections) for (const item of preview.items) if (item.eventWindow) instants.push(item.eventWindow.startAt, item.eventWindow.endAt, item.eventWindow.remindAt)
    const future = instants.flatMap(instant => instant && memoryUtcMicros(instant)! > server ? [memoryUtcMicros(instant)!] : [])
    if (future.length) {
      const earliest = future.reduce((left, right) => left < right ? left : right)
      deadline = Math.min(deadline, requestStarted + Math.max(1000, Number((earliest - server + 999n) / 1000n)))
    }
  }
  const token = boundarySequence, epoch = store.sessionEpoch, stamp = generation
  // serverTime 是请求时间；从发出请求的单调时刻计时，避免把读取耗时额外推迟到日界之后。
  boundaryTimer = window.setTimeout(() => {
    if (token !== boundarySequence || !current(epoch, stamp)) return
    boundaryTimer = undefined
    refreshAll()
  }, Math.max(0, deadline - performance.now()))
}
async function loadOverview() {
  if (!readable.value) return
  const epoch = store.sessionEpoch, stamp = generation, request = ++overviewSequence, query = overviewRequest(), requestStarted = performance.now()
  overviewBusy.value = true; overviewError.value = undefined
  try {
    const value = await read<unknown>(todayOverviewPath(query))
    if (!current(epoch, stamp) || request !== overviewSequence) return
    if (!todayOverviewValid(value, query)) throw invalid()
    overview.value = value; scheduleBoundary(value, requestStarted)
    // 列表轮询可以重置游标，安排编辑器仍保留原输入；日期只接受当前会话的已校验响应。
    planningContext.value = value.context; planningEpoch.value = epoch
    if (!calendarRange.value && !calendarInput.from && !calendarInput.to) {
      const start = value.context.localDate, end = new Date(Date.parse(`${start}T00:00:00Z`) + 6 * 86400000).toISOString().slice(0, 10)
      calendarInput.from = start; calendarInput.to = todayDateValid(end) ? end : start
      calendarRange.value = { ...calendarInput }
      if (view.value === 'calendar') void loadCalendar()
    }
  } catch (reason) { if (current(epoch, stamp) && request === overviewSequence) { planningEpoch.value = -1; overview.value = undefined; overviewError.value = reason; scheduleBoundary(undefined, requestStarted) } }
  finally { if (current(epoch, stamp) && request === overviewSequence) overviewBusy.value = false }
}
async function loadItems(cursor?: string) {
  if (!readable.value || pageBusy.value) return
  const epoch = store.sessionEpoch, stamp = generation, request = ++pageSequence
  const query: TodayItemsQuery = { zoneId: applied.value.zoneId, targetId: applied.value.target?.itemId ?? null, kind: sourceKind.value, section: section.value,
    includeOverBudget: sourceKind.value === 'MEMORY_CARD' && section.value === 'TODAY' && applied.value.includeOverBudget, limit: 20, ...(cursor ? { cursor } : {}) }
  pageBusy.value = true; pageError.value = undefined
  try {
    const value = await read<unknown>(todayItemsPath(query))
    if (!current(epoch, stamp) || request !== pageSequence) return
    if (!todayPageValid(value, query) || value.nextCursor && pageCursors.has(value.nextCursor)) throw invalid()
    if (cursor && page.value && (!samePageContext(page.value.context, value.context)
      || page.value.memoryQueue?.budget.settingsRevision !== value.memoryQueue?.budget.settingsRevision
      || page.value.memoryQueue?.budget.localDate !== value.memoryQueue?.budget.localDate
      || page.value.memoryQueue?.budget.zoneId !== value.memoryQueue?.budget.zoneId)) throw changed()
    if (value.capability.status !== 'READY') items.value = []
    else items.value = cursor ? merge(items.value, value.items) : value.items
    page.value = value; pageCursors.add(cursor ?? '')
  } catch (reason) {
    if (current(epoch, stamp) && request === pageSequence) { page.value = undefined; items.value = []; pageCursors = new Set(); pageError.value = reason }
  } finally { if (current(epoch, stamp) && request === pageSequence) pageBusy.value = false }
}
async function loadCalendar(cursor?: string) {
  if (!readable.value || calendarBusy.value || !calendarRange.value) return
  const epoch = store.sessionEpoch, stamp = generation, request = ++calendarSequence
  const query: TodayCalendarQuery = { ...calendarRange.value, zoneId: applied.value.zoneId, targetId: applied.value.target?.itemId ?? null, kind: calendarKind.value, limit: 20, ...(cursor ? { cursor } : {}) }
  calendarBusy.value = true; calendarError.value = undefined
  try {
    const value = await read<unknown>(todayCalendarPath(query))
    if (!current(epoch, stamp) || request !== calendarSequence) return
    if (!todayCalendarPageValid(value, query) || value.nextCursor && calendarCursors.has(value.nextCursor)) throw invalid()
    if (cursor && calendarPage.value && !samePageContext(calendarPage.value.context, value.context)) throw changed()
    calendarItems.value = value.capability.status !== 'READY' ? [] : cursor ? merge(calendarItems.value, value.items) : value.items
    calendarPage.value = value; calendarCursors.add(cursor ?? '')
  } catch (reason) {
    if (current(epoch, stamp) && request === calendarSequence) { calendarPage.value = undefined; calendarItems.value = []; calendarCursors = new Set(); calendarError.value = reason }
  } finally { if (current(epoch, stamp) && request === calendarSequence) calendarBusy.value = false }
}
async function loadSource() {
  if (!readable.value || !selectedSource.value) return
  const epoch = store.sessionEpoch, stamp = generation, request = ++sourceSequence, source = { ...selectedSource.value }, query = { zoneId: applied.value.zoneId }
  sourceBusy.value = true; sourceError.value = undefined; sourceDetail.value = undefined
  try {
    const value = await read<unknown>(todaySourcePath(source, query))
    if (!current(epoch, stamp) || request !== sourceSequence) return
    if (!todaySourceDetailValid(value, source, query)) throw invalid()
    sourceDetail.value = value
  } catch (reason) { if (current(epoch, stamp) && request === sourceSequence) sourceError.value = reason }
  finally { if (current(epoch, stamp) && request === sourceSequence) sourceBusy.value = false }
}
function applyFilters() {
  if (!readable.value) return
  filterError.value = todayZoneValid(input.zoneId) ? '' : '请填写有效的 IANA 时区或 UTC，例如 Asia/Shanghai。'
  if (filterError.value) return
  applied.value = { ...input, target: input.target ? { ...input.target } : null }; targetPickerOpen.value = false
  refreshAll('已应用显示时区和筛选条件。')
}
function chooseTarget(value: TargetFilter) { input.target = { ...value }; targetPickerOpen.value = false }
function changeView(value: View) {
  if (!readable.value || view.value === value) return
  view.value = value
  if (value === 'plans') planMounted.value = true
  if (value === 'source') { resetPage(); void loadItems() }
  if (value === 'calendar') { resetCalendar(); void loadCalendar() }
}
function showSource(kind: TodaySourceKind, selectedSection = section.value) {
  if (!readable.value) return
  sourceKind.value = kind; section.value = selectedSection; view.value = 'source'; resetPage(); void loadItems()
}
function changeSection(value: TodaySection) { showSource(sourceKind.value, value) }
function applyCalendar() {
  if (!readable.value) return
  calendarProblem.value = todayDateRangeProblem(calendarInput.from, calendarInput.to)
  if (calendarProblem.value) return
  calendarRange.value = { ...calendarInput }; resetCalendar(); void loadCalendar()
}
function changeCalendarKind(value: TodaySourceKind) { if (readable.value) { calendarKind.value = value; resetCalendar(); void loadCalendar() } }
function inspect(source: TodaySourceRef) { if (readable.value) { selectedSource.value = { ...source }; refreshAll('已重新读取当前清单与所选来源。') } }
function closeSource() { sourceSequence++; selectedSource.value = undefined; sourceDetail.value = undefined; sourceError.value = undefined; sourceBusy.value = false }
function open(value: TodayContinuation) { const route = todayContinuationRoute(value); if (readable.value && route) void router.push(route) }
function openPlan(source: TodaySourceRef, mode: 'edit' | 'candidate') {
  if (!readable.value) return
  planMounted.value = true; view.value = 'plans'; planIntent.value = { source: { ...source }, mode, sequence: ++planSequence }
}
function quick(kind: 'task' | 'training' | 'target' | 'memory' | 'note') {
  if (!readable.value) return
  if (kind === 'note') { noteOpen.value = true; return }
  if (kind === 'task') void router.push({ name: 'preparation-tasks', query: { create: '1' } })
  if (kind === 'training') void router.push({ name: 'training', query: { create: '1' } })
  if (kind === 'target') void router.push({ name: 'prepare', query: { kind: 'TARGET', create: '1' } })
  if (kind === 'memory') void router.push({ name: 'memory-cards' })
}
function noteCreated(id: string) { noteId.value = id; refreshAll('笔记已经保存到知识库，可继续整理或关联。') }
function onFocus() { if (readable.value) refreshAll() }
function onVisibility() { visible.value = document.visibilityState === 'visible' }
watch([readable, () => store.sessionEpoch], () => { if (!readable.value) targetPickerOpen.value = false; refreshAll() }, { immediate: true, flush: 'sync' })
watch(() => store.sessionEpoch, () => { noteId.value = '' }, { flush: 'sync' })
onMounted(() => {
  window.addEventListener('focus', onFocus); document.addEventListener('visibilitychange', onVisibility)
  unsubscribe = queryClient.getQueryCache().subscribe(event => {
    if (!readable.value || event.type !== 'updated' || event.action.type !== 'invalidate' || !sourceQueryRoots.has(String(event.query.queryKey[0]))) return
    if (writeRefreshTimer !== undefined) window.clearTimeout(writeRefreshTimer)
    // 同一来源写入常会失效多个查询，只在当前可见工作台合并刷新一次。
    writeRefreshTimer = window.setTimeout(() => { writeRefreshTimer = undefined; if (readable.value) refreshAll('来源记录已变化，已从首页重新读取。') }, 120)
  })
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; generation++; clearBoundary(); unsubscribe?.()
  if (writeRefreshTimer !== undefined) window.clearTimeout(writeRefreshTimer)
  window.removeEventListener('focus', onFocus); document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
  <header class="page-heading"><div><p class="section-label">把准备留在同一条路径上</p><h1>今日</h1><p class="muted">接着上次的进度，处理眼前的事项，再留下一点新的积累。</p></div><div class="row-actions"><NButton :disabled="!readable" @click="quick('task')">新建任务</NButton><NButton type="primary" :disabled="!readable" @click="quick('note')">＋ 快速记录</NButton></div></header>
  <div class="today-workspace">
    <form class="today-filters" aria-label="今日筛选条件" @submit.prevent="applyFilters"><div class="filter-primary"><label for="today-zone">显示时区<NInput id="today-zone" v-model:value="input.zoneId" :maxlength="100" :disabled="!readable" placeholder="Asia/Shanghai" /></label><div class="target-filter"><span>目标范围</span><strong>{{ input.target?.title ?? '全部目标与未关联事项' }}</strong><div class="row-actions"><NButton size="small" :disabled="!readable" @click="targetPickerOpen = !targetPickerOpen">{{ targetPickerOpen ? '收起目标选择' : '选择目标' }}</NButton><NButton v-if="input.target" text size="small" :disabled="!readable" @click="input.target = null">清除目标</NButton></div></div><NButton attr-type="submit" :disabled="!readable">应用筛选</NButton></div>
      <NCheckbox v-model:checked="input.includeOverBudget" :disabled="!readable">同时查看到期但未列入建议数量的回忆卡</NCheckbox><p class="field-help">这里只改变显示范围，进入回忆队列后仍需重新核对并明确开始。显示时区不改变回忆设置或来源的原计划日期。</p>
      <p v-if="filterError" class="notice error" role="alert">{{ filterError }}</p><p v-else-if="filterChanged" class="field-help" role="status">条件尚未应用，下方仍使用上一次筛选。</p>
    </form>
    <TodayTargetPicker v-if="targetPickerOpen" :disabled="!readable" class="today-picker" @choose="chooseTarget" />
    <TodayQuickNote v-if="noteOpen" :disabled="!readable" @close="noteOpen = false" @created="noteCreated" />
    <p v-if="noteId" class="note-result"><NButton text :disabled="!readable" @click="open({ route: 'KNOWLEDGE', item: { item: noteId }, action: null, attempt: null })">打开刚保存的笔记</NButton></p>
    <nav class="today-view-switch" aria-label="今日查看方式"><button type="button" :aria-current="view === 'overview' ? 'page' : undefined" :disabled="!readable" @click="changeView('overview')">今日概览</button><button type="button" :aria-current="view === 'source' ? 'page' : undefined" :disabled="!readable" @click="changeView('source')">按来源查看</button><button type="button" :aria-current="view === 'calendar' ? 'page' : undefined" :disabled="!readable" @click="changeView('calendar')">日期清单</button><button type="button" :aria-current="view === 'plans' ? 'page' : undefined" :disabled="!readable" @click="changeView('plans')">安排与建议</button><NButton text size="small" :disabled="!readable" :loading="overviewBusy" @click="refreshAll('已从首页重新读取当前范围。')">刷新当前范围</NButton></nav>
    <p v-if="!readable" class="notice" role="status">登录与资料访问恢复后重新读取。筛选、笔记、安排输入与未确认请求继续保留在当前页面。</p>
    <p v-else-if="notice" class="field-help" role="status">{{ notice }}</p>
    <p v-if="readable && overview" class="read-context">{{ overview.context.localDate }} · {{ overview.context.zoneId }}<span>状态截至 {{ time(overview.context.asOf) }}</span></p>
    <p v-if="readable && applied.target && view !== 'plans'" class="field-help">只显示实际关联「{{ applied.target.title }}」的记录，包含该目标的历史版本。知识草稿和回忆卡没有目标关联，当前筛选不纳入这两类。</p>
    <p v-if="readable && overviewError" class="notice error" role="alert">{{ todayErrorText(overviewError) }}<NButton text :disabled="overviewBusy" @click="refreshAll()">重新读取</NButton></p>
    <section v-if="readable && overview?.memoryQueue" class="today-memory" aria-label="实际回忆队列摘要"><div><h2>常规回忆</h2><p>到期且符合来源条件 {{ countLabel(overview.memoryQueue.eligibleDueCards) }} · 本次建议 {{ countLabel(overview.memoryQueue.recommendedCards) }}</p><p class="field-help">复习日 {{ overview.memoryQueue.budget.localDate }} · {{ overview.memoryQueue.budget.zoneId }}；已计入新卡 {{ overview.memoryQueue.budget.newCards }}/{{ overview.memoryQueue.budget.newLimit }}，复习卡 {{ overview.memoryQueue.budget.reviewCards }}/{{ overview.memoryQueue.budget.reviewLimit }}。这些是实际计入的张数，不表示知识掌握度。</p></div><NButton size="small" @click="open({ route: 'MEMORY_RECALL', item: null, action: null, attempt: null })">进入回忆队列</NButton></section>
    <template v-if="readable && view === 'overview'">
      <p v-if="overviewBusy" class="loading-block" role="status">正在读取六类来源…</p>
      <section v-if="allKnownEmpty" class="today-empty"><h2>从一个具体的起点开始</h2><p>当前范围没有可继续或待处理事项。记下一条笔记、练习一道题，或整理下一份岗位目标。</p><div class="row-actions"><NButton @click="quick('note')">写笔记</NButton><NButton @click="quick('training')">开始文字训练</NButton><NButton @click="quick('target')">建立目标</NButton></div></section>
      <div v-if="overview" class="today-sections"><section v-for="(preview, index) in overview.sections" :key="preview.section" class="today-section" :aria-labelledby="`today-section-${preview.section}`"><header><div class="section-number" aria-hidden="true">0{{ index + 1 }}</div><div><h2 :id="`today-section-${preview.section}`">{{ todaySectionLabels[preview.section] }}</h2><p>{{ sectionDescriptions[preview.section] }}</p></div></header>
        <div class="source-scopes"><button v-for="scope in overview.capabilities.filter(value => value.section === preview.section)" :key="scope.kind" type="button" @click="showSource(scope.kind, preview.section)"><span>{{ todaySourceKindLabels[scope.kind] }}</span><strong>{{ scope.capability.status === 'READY' ? countLabel(scope.count) : todayAvailabilityLabels[scope.capability.status] }}</strong><small v-if="scope.scanLimited">本次扫描有界</small></button></div>
        <p v-for="scope in overview.capabilities.filter(value => value.section === preview.section && value.capability.status !== 'READY')" :key="`notice-${scope.kind}`" class="source-availability">{{ todaySourceKindLabels[scope.kind] }}：{{ capabilityText(scope.capability) }}<NButton v-if="scope.capability.status === 'NOT_CONFIGURED'" text size="small" @click="quick('memory')">回忆卡管理</NButton></p>
        <TodaySourceRow v-for="item in preview.items" :key="todaySourceKey(item.source)" :item="item" :zone-id="applied.zoneId" :inspecting="selectedSource && todaySourceKey(selectedSource) === todaySourceKey(item.source)" @open="open" @inspect="inspect" @plan="openPlan($event, 'edit')" @candidate="openPlan($event, 'candidate')" />
        <p v-if="!preview.items.length" class="section-empty">本次没有读取到可展示事项。可从上方各来源查看数量、可用状态和分页。</p><p v-else class="preview-help">本区预览最多五项，先按已保存的显示顺序，同值时按来源交替显示；点击来源可从首页查看该范围。</p>
      </section></div>
    </template>
    <section v-if="readable && view === 'source'" class="today-panel" aria-labelledby="today-source-heading"><header class="panel-heading"><div><p class="section-label">来源目录</p><h2 id="today-source-heading">{{ todaySourceKindLabels[sourceKind] }} · {{ todaySectionLabels[section] }}</h2></div><NButton size="small" :disabled="pageBusy" @click="resetPage(); loadItems()">从首页重读</NButton></header><div class="source-list-filters"><label>来源<NSelect :value="sourceKind" :options="sourceOptions" aria-label="来源类型" @update:value="value => showSource(value)" /></label><label>区段<NSelect :value="section" :options="sectionOptions" aria-label="今日区段" @update:value="changeSection" /></label></div>
      <p v-if="pageBusy" class="field-help" role="status">正在读取来源清单…</p><p v-if="pageError" class="notice error" role="alert">{{ todayErrorText(pageError) }}<NButton text :disabled="pageBusy" @click="resetPage(); loadItems()">保留筛选，从首页重读</NButton></p>
      <template v-if="page"><p v-if="page.capability.status !== 'READY'" class="notice" role="status">{{ capabilityText(page.capability) }}<NButton v-if="page.capability.status === 'NOT_CONFIGURED'" text @click="quick('memory')">回忆卡管理</NButton></p><template v-else><p class="field-help">当前来源与区段：{{ countLabel(page.count) }}。已载入 {{ items.length }} 个不同来源；后续页读取期间，原记录仍可能变化。</p><p v-if="page.scanLimited" class="notice">本次扫描已达到额度。标注“至少”的数量是已确认下界；即使这一批没有结果，也可继续读取。</p><TodaySourceRow v-for="item in items" :key="todaySourceKey(item.source)" :item="item" :zone-id="applied.zoneId" :disabled="pageBusy" :inspecting="selectedSource && todaySourceKey(selectedSource) === todaySourceKey(item.source)" @open="open" @inspect="inspect" @plan="openPlan($event, 'edit')" @candidate="openPlan($event, 'candidate')" /><p v-if="!items.length" class="section-empty">{{ page.nextCursor ? '本批未读取到匹配项，可以继续读取后续来源。' : '当前来源与区段没有匹配事项。' }}</p><NButton v-if="page.nextCursor" :loading="pageBusy" @click="loadItems(page.nextCursor ?? undefined)">继续读取此来源</NButton></template><p class="read-context">状态截至 {{ time(page.context.asOf) }} · {{ page.context.zoneId }}</p></template>
    </section>
    <section v-if="readable && view === 'calendar'" class="today-panel" aria-labelledby="today-calendar-heading"><header class="panel-heading"><div><p class="section-label">真实日期与排程</p><h2 id="today-calendar-heading">日期清单</h2></div><NButton size="small" :disabled="calendarBusy || !calendarRange" @click="resetCalendar(); loadCalendar()">从首页重读</NButton></header>
      <form class="calendar-filters" @submit.prevent="applyCalendar"><label>开始日期<input v-model="calendarInput.from" type="date" min="1000-01-01" max="9999-12-31" /></label><label>结束日期<input v-model="calendarInput.to" type="date" min="1000-01-01" max="9999-12-31" /></label><label>来源<NSelect :value="calendarKind" :options="sourceOptions" aria-label="日期清单来源" @update:value="changeCalendarKind" /></label><NButton attr-type="submit">应用日期</NButton></form>
      <p class="field-help">包含首尾最多 31 天。任务保留原计划日期和时区；面试按与所选区间相交的时段读取；回忆只展示已保存排程，不推演未来评分。知识草稿、训练和动作展示已保存的安排偏好日期，暂停推荐仍保留日期记录。</p><p v-if="calendarProblem" class="notice error" role="alert">{{ calendarProblem }}</p><p v-else-if="calendarRange && (calendarInput.from !== calendarRange.from || calendarInput.to !== calendarRange.to)" class="field-help">日期尚未应用，当前读取 {{ calendarRange.from }} 至 {{ calendarRange.to }}。</p>
      <p v-if="calendarBusy" class="field-help" role="status">正在读取日期清单…</p><p v-if="calendarError" class="notice error" role="alert">{{ todayErrorText(calendarError) }}<NButton text :disabled="calendarBusy" @click="resetCalendar(); loadCalendar()">保留筛选，从首页重读</NButton></p>
      <template v-if="calendarPage"><p v-if="calendarPage.capability.status !== 'READY'" class="notice">{{ capabilityText(calendarPage.capability) }}</p><template v-else><p class="field-help">{{ calendarPage.window.from }} 至 {{ calendarPage.window.to }} · {{ todaySourceKindLabels[calendarKind] }}：{{ countLabel(calendarPage.count) }}；已载入 {{ calendarItems.length }} 个不同来源。包含来源各自的真实状态。</p><p v-if="calendarPage.scanLimited" class="notice">本次扫描已达到额度。标注“至少”的数量是已确认下界，可继续读取。</p><section v-for="group in calendarGroups" :key="group.day" class="calendar-group"><h3>{{ group.day }}<small v-if="calendarRange && group.day < calendarRange.from">在区间之前开始，时段仍与区间相交</small></h3><TodaySourceRow v-for="item in group.items" :key="todaySourceKey(item.source)" :item="item" :zone-id="applied.zoneId" :disabled="calendarBusy" @open="open" @inspect="inspect" @plan="openPlan($event, 'edit')" @candidate="openPlan($event, 'candidate')" /></section><p v-if="!calendarItems.length" class="section-empty">{{ calendarPage.nextCursor ? '本批没有匹配记录，可以继续读取后续来源。' : '这个来源在当前日期范围内没有记录。' }}</p><NButton v-if="calendarPage.nextCursor" :loading="calendarBusy" @click="loadCalendar(calendarPage.nextCursor ?? undefined)">继续读取日期清单</NButton></template><p class="read-context">状态截至 {{ time(calendarPage.context.asOf) }} · {{ calendarPage.context.zoneId }}</p></template>
    </section>
    <p v-if="readable && view === 'plans'" class="field-help">安排目录涵盖全部已保存偏好，不受上方目标筛选限制。候选由你逐项加入，切换分区会保留当前输入。</p>
    <p v-if="readable && view === 'plans' && !planReadable" class="notice" role="status">正在核对显示日期与时区，读取成功后可继续管理安排。</p>
    <TodayPlanWorkspace v-if="planMounted && planningContext" v-show="readable && view === 'plans'" :local-date="planningContext.localDate" :zone-id="planningContext.zoneId" :disabled="!planReadable || view !== 'plans'" :refresh-token="planRefresh" :intent="planIntent" @open="open" @changed="refreshAll('安排已更新，今日来源与目录已从首页重新读取。')" />
    <section v-if="readable && selectedSource && view !== 'plans'" class="today-panel source-inspection" aria-labelledby="today-inspection-heading"><header class="panel-heading"><div><p class="section-label">所选来源</p><h2 id="today-inspection-heading">核对当前状态</h2></div><div class="row-actions"><NButton size="small" :disabled="sourceBusy" @click="refreshAll()">重新核对</NButton><NButton text size="small" @click="closeSource">收起</NButton></div></header><p class="field-help">此处按缺省建议范围核对来源，不受目标筛选影响。退出今日清单后仍可前往原工作区查看。</p><p v-if="sourceBusy" class="field-help" role="status">正在读取所选来源…</p><p v-if="sourceError" class="notice error" role="alert">{{ todayErrorText(sourceError) }}</p><template v-if="sourceDetail"><p v-if="sourceDetail.capability.status !== 'READY'" class="notice">{{ capabilityText(sourceDetail.capability) }}</p><p v-if="sourceDetail.exclusionReason" class="notice">当前已退出缺省今日清单：{{ todayExclusionReasonLabels[sourceDetail.exclusionReason] }}。</p><TodaySourceRow v-if="sourceDetail.item" :item="sourceDetail.item" :zone-id="applied.zoneId" :inspectable="false" @open="open" @plan="openPlan($event, 'edit')" @candidate="openPlan($event, 'candidate')" /><p class="read-context">状态截至 {{ time(sourceDetail.context.asOf) }} · {{ sourceDetail.context.zoneId }}</p></template></section>
    <p class="workspace-footnote">事项保留原来源的身份和状态。完成任务、提交训练、处理动作和回忆评分均在来源页面执行。</p>
  </div>
</template>

<style scoped>
.today-workspace { max-width: 1280px; margin: 0 auto; }.today-filters { display: grid; gap: 12px; padding: 17px 0 20px; margin-bottom: 20px; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.filter-primary { display: grid; grid-template-columns: minmax(180px, 240px) minmax(0, 1fr) auto; gap: 22px; align-items: end; }.filter-primary label, .source-list-filters label, .calendar-filters label { display: grid; gap: 7px; min-width: 0; font-size: 12px; color: var(--muted); }.target-filter { display: grid; gap: 7px; min-width: 0; }.target-filter > span { color: var(--muted); font-size: 12px; }.target-filter strong { font-size: 12px; font-weight: 500; overflow-wrap: anywhere; }.target-filter .row-actions { gap: 12px; }.today-picker { margin-bottom: 20px; }
.today-view-switch { display: flex; flex-wrap: wrap; align-items: center; gap: 5px 22px; margin: 23px 0 14px; border-bottom: 1px solid var(--line); }.today-view-switch > button:not(:last-child) { padding: 12px 1px; color: var(--muted); font-size: 13px; border-bottom: 2px solid transparent; }.today-view-switch > button[aria-current=page] { color: var(--brand); border-bottom-color: var(--brand); font-weight: 600; }.today-view-switch > :last-child { margin-left: auto; }
.read-context { display: flex; gap: 8px 20px; flex-wrap: wrap; font-size: 11px; color: var(--muted); margin: 13px 0; }.read-context > span { margin-left: auto; }.today-memory { display: flex; gap: 20px; align-items: center; justify-content: space-between; margin: 22px 0; padding: 18px 22px; border-left: 3px solid var(--brand); background: var(--surface-soft); }.today-memory h2 { font-size: 15px; margin: 0 0 8px; }.today-memory p { font-size: 12px; }.today-memory .field-help { margin-top: 7px; }
.today-sections { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; gap: 22px; }.today-section { min-width: 0; padding: 23px; background: var(--surface); border: 1px solid var(--line); border-radius: 8px; }.today-section > header { display: flex; align-items: start; gap: 12px; margin-bottom: 18px; }.section-number { padding-top: 1px; font-size: 20px; line-height: 1.2; color: var(--brand); font-variant-numeric: tabular-nums; }.today-section h2 { font-size: 17px; margin: 0 0 7px; }.today-section header p { font-size: 12px; color: var(--muted); }
.source-scopes { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; padding-bottom: 15px; }.source-scopes button { display: grid; gap: 5px; align-content: start; text-align: left; padding: 9px; background: var(--surface-soft); border-radius: 5px; min-width: 0; }.source-scopes button:hover { background: var(--surface-deep); }.source-scopes span, .source-scopes small { font-size: 10px; color: var(--muted); }.source-scopes strong { font-size: 12px; font-weight: 500; color: var(--ink); overflow-wrap: anywhere; }.source-scopes small { color: #88652f; }
.source-availability { font-size: 11px; color: var(--muted); margin: 6px 0 10px; line-height: 1.7; }.section-empty { padding: 25px 0; color: var(--muted); font-size: 12px; line-height: 1.8; }.preview-help { margin-top: 8px; padding-top: 12px; border-top: 1px solid var(--line); color: var(--muted); font-size: 10px; }
.today-empty { display: grid; gap: 15px; justify-items: start; padding: 30px; margin: 20px 0; border: 1px solid var(--line); background: var(--surface); border-radius: 8px; }.today-empty h2 { font-size: 20px; margin: 0; }.today-empty p { color: var(--muted); font-size: 13px; }
.today-panel { display: grid; gap: 15px; padding: 24px; margin: 20px 0; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; }.panel-heading h2 { margin: 5px 0 0; font-size: 19px; }.source-list-filters { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px; max-width: 580px; }.calendar-filters { display: grid; grid-template-columns: repeat(2, minmax(140px, 1fr)) minmax(150px, 1fr) auto; align-items: end; gap: 13px; }.calendar-filters input { width: 100%; min-height: 34px; padding: 5px 10px; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: 4px; box-sizing: border-box; }
.calendar-group > h3 { display: flex; gap: 12px; align-items: baseline; font-size: 14px; font-variant-numeric: tabular-nums; margin: 10px 0; }.calendar-group > h3 small { color: var(--muted); font-size: 11px; font-weight: normal; }.source-inspection { border-top: 3px solid var(--brand); }.note-result { margin: 6px 0; }.today-panel > .n-button { justify-self: start; }
@media (max-width: 1100px) { .today-sections { grid-template-columns: 1fr; }.today-section { padding: 22px; }.source-scopes { grid-template-columns: repeat(6, minmax(0, 1fr)); }.filter-primary { grid-template-columns: 200px minmax(0, 1fr); }.filter-primary > button { justify-self: start; }.calendar-filters { grid-template-columns: repeat(3, minmax(0, 1fr)); }.calendar-filters > button { justify-self: start; } }
@media (max-width: 620px) { .filter-primary, .source-list-filters { grid-template-columns: 1fr; }.source-scopes { grid-template-columns: repeat(3, minmax(0, 1fr)); }.today-panel, .today-section { padding: 18px 14px; }.today-memory { flex-direction: column; align-items: start; padding: 16px; }.today-view-switch { gap: 3px 16px; }.today-view-switch > :last-child { margin: 0 0 7px auto; }.calendar-filters { grid-template-columns: 1fr 1fr; }.calendar-filters > label:last-of-type { grid-column: 1 / -1; }.panel-heading { align-items: start; flex-wrap: wrap; }.read-context > span { margin-left: 0; }.calendar-group > h3 { flex-direction: column; gap: 4px; } }
</style>
