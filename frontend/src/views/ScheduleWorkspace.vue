<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useInfiniteQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { errorText, read } from '../api/client'
import { queryClient } from '../api/queryClient'
import type { EventPage, InterviewEvent, ReminderPage, ScheduleStatus } from '../api/schedule'
import { browserZone, dateKey, formatScheduleTime, scheduleStatusLabels, scheduleTimingLabels, validZone } from '../api/schedule'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
const ScheduleEditor = defineAsyncComponent(() => import('../components/schedule/ScheduleEditor.vue'))

const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const writer = useCommand()
const viewZone = ref(browserZone())
const zoneInput = ref(viewZone.value)
const month = ref(dateKey(Date.now(), viewZone.value).slice(0, 7))
const monthInput = ref(month.value)
const filterError = ref('')
const status = ref<ScheduleStatus | 'ALL'>('ALL')
const view = ref<'calendar' | 'list'>('calendar')
const selectedDay = ref<string>()
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const creating = computed(() => route.query.create === '1')
const range = computed(() => {
  const [year, number] = month.value.split('-').map(Number)
  // 查询边界覆盖全球时区，展示再按所选时区分日，不把浏览器时区当作月历时区。
  return { from: new Date(Date.UTC(year, number - 1, -1)).toISOString(), to: new Date(Date.UTC(year, number, 3)).toISOString() }
})
const list = useInfiniteQuery({
  queryKey: computed(() => ['schedule', store.sessionEpoch, 'month', month.value, status.value]),
  enabled: computed(() => store.sessionActive),
  initialPageParam: undefined as string | undefined,
  queryFn: ({ pageParam }) => {
    const parameters = new URLSearchParams({ from: range.value.from, to: range.value.to, limit: '50' })
    if (status.value !== 'ALL') parameters.set('status', status.value)
    if (pageParam) parameters.set('cursor', pageParam)
    return read<EventPage>(`/schedule/interviews?${parameters}`)
  },
  getNextPageParam: page => page.nextCursor ?? undefined,
  // timing 由服务器按读取时刻计算；持续停留时也要刷新，避免月历与提醒长期显示不同阶段。
  refetchInterval: 60_000,
})
const reminders = useInfiniteQuery({
  queryKey: computed(() => ['schedule', store.sessionEpoch, 'reminders']),
  enabled: computed(() => store.sessionActive),
  initialPageParam: undefined as string | undefined,
  queryFn: ({ pageParam }) => read<ReminderPage>(`/schedule/reminders?limit=20${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ''}`),
  getNextPageParam: page => page.nextCursor ?? undefined,
  refetchInterval: 60_000,
})
function uniqueEvents(pages?: EventPage[]): InterviewEvent[] { return [...new Map((pages ?? []).flatMap(page => page.items).map(item => [item.id, item])).values()] }
const allEvents = computed(() => uniqueEvents(list.data.value?.pages))
const reminderEvents = computed(() => uniqueEvents(reminders.data.value?.pages))
function includesDay(event: InterviewEvent, day: string) { return dateKey(event.time.startAt, viewZone.value) <= day && dateKey(Date.parse(event.time.endAt) - 1, viewZone.value) >= day }
const monthEvents = computed(() => allEvents.value.filter(event => dateKey(event.time.startAt, viewZone.value).slice(0, 7) <= month.value && dateKey(Date.parse(event.time.endAt) - 1, viewZone.value).slice(0, 7) >= month.value))
const visibleEvents = computed(() => selectedDay.value ? monthEvents.value.filter(event => includesDay(event, selectedDay.value!)) : monthEvents.value)
const days = computed(() => {
  const [year, number] = month.value.split('-').map(Number)
  const offset = (new Date(Date.UTC(year, number - 1, 1)).getUTCDay() + 6) % 7
  const count = new Date(Date.UTC(year, number, 0)).getUTCDate()
  return Array.from({ length: Math.ceil((offset + count) / 7) * 7 }, (_, index) => {
    const day = index - offset + 1
    const key = day > 0 && day <= count ? `${month.value}-${String(day).padStart(2, '0')}` : undefined
    return { day, key, events: key ? monthEvents.value.filter(event => includesDay(event, key)) : [] }
  })
})
const statusOptions = [{ label: '全部安排', value: 'ALL' }, { label: '待进行', value: 'PLANNED' }, { label: '已标记完成', value: 'COMPLETED' }, { label: '已取消', value: 'CANCELLED' }]
const changeState = reactive({ dirty: false, locked: false })
watch(writer.locked, locked => { changeState.locked = locked; store.changes.set('schedule-command', changeState) }, { immediate: true })
onUnmounted(() => { if (store.changes.get('schedule-command') === changeState) store.changes.delete('schedule-command') })

function applyFilters() {
  filterError.value = ''
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(monthInput.value) || monthInput.value < '1000-02' || monthInput.value > '9999-11') { filterError.value = '请选择有效月份。'; return }
  if (!validZone(zoneInput.value)) { filterError.value = '请填写有效的 IANA 时区，例如 Asia/Shanghai。'; return }
  month.value = monthInput.value
  viewZone.value = zoneInput.value
  selectedDay.value = undefined
}
function shiftMonth(amount: number) {
  const [year, number] = month.value.split('-').map(Number)
  const value = new Date(Date.UTC(year, number - 1 + amount, 1))
  monthInput.value = `${String(value.getUTCFullYear()).padStart(4, '0')}-${String(value.getUTCMonth() + 1).padStart(2, '0')}`
  applyFilters()
}
function open(id: string) { void router.push({ name: 'schedule', query: { item: id } }) }
function create() { void router.push({ name: 'schedule', query: { create: '1' } }) }
function close() { void router.push({ name: 'schedule' }) }
function refresh() { void queryClient.invalidateQueries({ queryKey: ['schedule'] }) }
function acknowledge(event: InterviewEvent) {
  if (writer.locked.value || !store.sessionActive) return
  void writer.run<InterviewEvent>(`/schedule/interviews/${event.id}/acknowledge`, 'POST', { expectedRevision: event.revision }, () => {
    // 列表提醒确认只刷新目录；首次详情访问由打开后的 GET 接纳。
    refresh()
  })
}
</script>

<template>
  <header class="page-heading"><div><p class="section-label">为下一次对话留出时间</p><h1>面试安排</h1><p class="muted">把时间、准备材料与每一次改期留在一起。</p></div><NButton type="primary" @click="create">＋ 新建安排</NButton></header>
  <section class="reminder-panel" aria-labelledby="reminder-title"><div class="section-heading"><h2 id="reminder-title">待处理提醒</h2><NButton size="small" text :disabled="!store.sessionActive" @click="reminders.refetch()">刷新</NButton></div><p class="field-help">站内保留尚未确认的提醒；浏览器关闭后不保证推送。时间已过不会自动标记缺席。</p><p v-if="reminders.isPending.value" class="field-help" role="status">正在读取提醒…</p><p v-if="reminders.error.value" class="notice error" role="alert">{{ errorText(reminders.error.value) }}</p><p v-if="reminders.data.value && !reminderEvents.length" class="field-help">目前没有到期且未确认的提醒。</p><div v-for="event in reminderEvents" :key="event.id" class="reminder-row"><button class="text-link" @click="open(event.id)"><strong>{{ event.title }}</strong><span>{{ formatScheduleTime(event.time.startAt, viewZone) }} · {{ scheduleTimingLabels[event.timing] }}</span></button><NButton size="small" :disabled="writer.locked.value || !store.sessionActive" @click="acknowledge(event)">已看过提醒</NButton></div><NButton v-if="reminders.hasNextPage.value" size="small" :loading="reminders.isFetchingNextPage.value" @click="reminders.fetchNextPage()">加载更多未处理提醒</NButton><p v-if="reminders.data.value?.pages[0]" class="field-help">提醒状态截至 {{ new Date(reminders.data.value.pages[0].asOf).toLocaleString('zh-CN') }}</p></section>
  <div v-if="writer.error.value" class="notice error command-error" role="alert"><p>{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" size="small" @click="writer.retry">用原请求重试</NButton><NButton v-else size="small" @click="refresh">读取最新安排</NButton></div>
  <div class="schedule-layout" :class="{ 'has-editor': selectedId || creating }">
    <section class="schedule-overview" aria-label="安排列表与月历">
      <form class="calendar-filters" @submit.prevent="applyFilters"><label>月份<input v-model="monthInput" type="month" min="1000-02" max="9999-11" /></label><label>显示时区<NInput v-model:value="zoneInput" placeholder="Asia/Shanghai" :maxlength="100" /></label><NButton attr-type="submit">应用</NButton></form>
      <p v-if="filterError" class="notice error" role="alert">{{ filterError }}</p>
      <div class="calendar-toolbar"><div class="row-actions"><NButton size="small" @click="shiftMonth(-1)">上月</NButton><strong>{{ month }}</strong><NButton size="small" @click="shiftMonth(1)">下月</NButton></div><div class="view-switch" role="group" aria-label="显示方式"><button :aria-pressed="view === 'calendar'" @click="view = 'calendar'">月历</button><button :aria-pressed="view === 'list'" @click="view = 'list'; selectedDay = undefined">列表</button></div></div>
      <NSelect v-model:value="status" :options="statusOptions" aria-label="安排状态筛选" />
      <p v-if="list.isPending.value" class="loading-block" role="status">正在读取当月安排…</p><p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重试读取</NButton></p>
      <p v-if="list.hasNextPage.value" class="notice" role="status">还有安排未载入，月历与列表目前仅显示已读取的 {{ monthEvents.length }} 条。<NButton text :loading="list.isFetchingNextPage.value" @click="list.fetchNextPage()">继续载入</NButton></p>
      <div v-if="view === 'calendar'" class="calendar-grid" aria-label="按显示时区分组的月历"><span v-for="day in ['一', '二', '三', '四', '五', '六', '日']" :key="day" class="weekday">{{ day }}</span><template v-for="(day, index) in days" :key="day.key ?? `empty-${index}`"><button v-if="day.key" class="calendar-day" :class="{ selected: selectedDay === day.key, occupied: day.events.length }" :aria-pressed="selectedDay === day.key" :aria-label="`${day.key}，${day.events.length} 项已载入安排`" @click="selectedDay = selectedDay === day.key ? undefined : day.key"><span>{{ day.day }}</span><small v-if="day.events.length">{{ day.events.length }} 项</small></button><span v-else class="calendar-blank" /></template></div>
      <div class="section-heading"><h2>{{ selectedDay ?? '当月安排' }}</h2><NButton v-if="selectedDay" text size="small" @click="selectedDay = undefined">显示整月</NButton></div>
      <div v-if="list.data.value && !visibleEvents.length" class="schedule-empty"><strong>{{ selectedDay ? '这一天暂无已载入安排' : '当月暂无已载入安排' }}</strong><p>{{ list.hasNextPage.value ? '继续载入后再查看完整安排。' : '可以添加面试时间，也可以调整月份或状态筛选。' }}</p></div>
      <div class="event-list"><button v-for="event in visibleEvents" :key="event.id" class="event-card" :class="{ selected: selectedId === event.id && !creating }" :aria-current="selectedId === event.id && !creating ? 'true' : undefined" @click="open(event.id)"><span class="event-when">{{ formatScheduleTime(event.time.startAt, viewZone) }}<small>{{ event.time.durationMinutes }} 分钟</small></span><span class="event-description"><strong>{{ event.title }}</strong><small>{{ event.round || '未填写轮次' }} · {{ event.status === 'PLANNED' ? scheduleTimingLabels[event.timing] : scheduleStatusLabels[event.status] }}</small></span></button></div>
      <NButton v-if="list.hasNextPage.value" size="small" :loading="list.isFetchingNextPage.value" @click="list.fetchNextPage()">继续载入当月安排</NButton><p class="field-help">列表与月历使用相同记录，当前按 {{ viewZone }} 显示；详情保留原安排时区。</p>
      <p v-if="list.data.value?.pages[0]" class="field-help">安排状态截至 {{ new Date(list.data.value.pages[0].asOf).toLocaleString('zh-CN', { timeZone: viewZone }) }} · {{ viewZone }}。<NButton size="small" text :disabled="!store.sessionActive" :loading="list.isRefetching.value" @click="list.refetch()">刷新安排</NButton></p>
    </section>
    <ScheduleEditor v-if="selectedId || creating" :key="creating ? 'new' : selectedId" :id="creating ? undefined : selectedId" @close="close" @created="event => open(event.id)" @changed="refresh" />
  </div>
</template>

<style scoped>
.reminder-panel, .command-error, .schedule-layout { max-width: 1280px; margin-left: auto; margin-right: auto; }
.reminder-panel { display: grid; gap: 9px; margin-bottom: 23px; padding: 16px 20px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 7px; }
.section-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.section-heading h2 { font-size: 14px; }
.reminder-row { display: flex; justify-content: space-between; align-items: center; gap: 14px; border-top: 1px solid var(--line); padding: 9px 0 2px; }
.reminder-row > button:first-child { display: grid; gap: 5px; min-width: 0; text-align: left; overflow-wrap: anywhere; }
.reminder-row strong { font-size: 13px; }
.reminder-row span { color: var(--muted); font-size: 11px; }
.command-error { margin-bottom: 18px; }
.schedule-layout { display: grid; border: 1px solid var(--line); background: var(--surface); border-radius: 8px; }
.schedule-layout.has-editor { grid-template-columns: minmax(310px, .85fr) minmax(0, 1.25fr); }
.schedule-overview { min-width: 0; display: grid; align-content: start; gap: 15px; padding: 22px; }
.has-editor .schedule-overview { border-right: 1px solid var(--line); }
.calendar-filters { display: grid; grid-template-columns: minmax(110px, .8fr) minmax(120px, 1fr) auto; align-items: end; gap: 9px; }
.calendar-filters label { display: grid; gap: 5px; color: var(--muted); font-size: 11px; min-width: 0; }
.calendar-filters input { width: 100%; box-sizing: border-box; min-height: 34px; padding: 5px 8px; border: 1px solid var(--line); border-radius: 4px; background: var(--surface); color: var(--ink); }
.calendar-toolbar { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 10px; }
.calendar-toolbar strong { font-size: 14px; }
.view-switch { display: flex; padding: 2px; border: 1px solid var(--line); border-radius: 5px; }
.view-switch button { padding: 4px 10px; font-size: 12px; color: var(--muted); border-radius: 3px; }
.view-switch button[aria-pressed=true] { background: var(--surface-deep); color: var(--brand); }
.calendar-grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 3px; }
.weekday { text-align: center; padding: 5px 0; font-size: 11px; color: var(--muted); }
.calendar-day { display: flex; flex-direction: column; align-items: center; justify-content: space-between; gap: 6px; min-height: 56px; padding: 8px 3px; border: 1px solid transparent; border-radius: 5px; font-size: 12px; }
.calendar-day:hover, .calendar-day.occupied { background: var(--surface-soft); }
.calendar-day.selected { border-color: var(--brand); background: var(--surface-deep); color: var(--brand); }
.calendar-day small { font-size: 10px; color: var(--brand); }
.event-list { display: grid; gap: 5px; }
.event-card { display: flex; gap: 15px; padding: 13px 10px; text-align: left; border-radius: 6px; border: 1px solid var(--line); min-width: 0; }
.event-card.selected, .event-card:hover { background: var(--surface-soft); }
.event-card.selected { border-color: var(--brand); }
.event-when { flex: 0 0 103px; font-size: 12px; color: var(--brand); }
.event-when small, .event-description small { display: block; margin-top: 6px; font-size: 11px; color: var(--muted); }
.event-description { min-width: 0; overflow-wrap: anywhere; font-size: 13px; }
.schedule-empty { padding: 30px 5px; color: var(--muted); font-size: 12px; }
.schedule-empty strong { display: block; margin-bottom: 8px; color: var(--ink); }
@media (max-width: 1080px) { .schedule-layout.has-editor { grid-template-columns: 1fr; } .has-editor .schedule-overview { border-right: 0; border-bottom: 1px solid var(--line); } .calendar-day { min-height: 54px; } }
@media (max-width: 600px) { .schedule-overview { padding: 16px 12px; } .calendar-filters { grid-template-columns: 1fr 1fr; } .calendar-filters > button { justify-self: start; } .reminder-panel { padding: 14px; } .reminder-row { align-items: start; } .event-card { gap: 10px; } .event-when { flex-basis: 90px; } }
</style>
