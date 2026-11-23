<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton } from 'naive-ui'
import RIcon from '../../replica/RIcon.vue'
import type { TodayContinuation, TodayItem, TodayOverview, TodaySection, TodaySourceKind } from '../../api/today'
import { todaySourceKey, todaySourceKindLabels } from '../../api/today'
const props = defineProps<{ overview: TodayOverview; disabled?: boolean }>()
const emit = defineEmits<{ open: [value: TodayContinuation]; quick: [kind: 'note' | 'training' | 'target']; source: [kind: TodaySourceKind, section: TodaySection] }>()
const filter = ref('all')
const entries = computed(() => [...new Map(props.overview.sections.flatMap(section => section.items).map(item => [todaySourceKey(item.source), item])).values()])
const continuation = computed(() => props.overview.sections.find(section => section.section === 'CONTINUE')?.items[0])
const interview = computed(() => entries.value.find(item => item.source.kind === 'INTERVIEW_EVENT' && item.eventWindow))
const tasks = computed(() => entries.value.filter(item => filter.value === 'all' || (filter.value === 'due' ? item.reasons.some(reason => /OVERDUE|DUE|TODAY/.test(reason)) : item.source.kind === 'REVIEW_ACTION')))
const minutes = computed(() => entries.value.reduce((sum, item) => sum + (item.estimate.minutes ?? 0), 0))
function localDate(instant: string, options: Intl.DateTimeFormatOptions) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: props.overview.context.zoneId, ...options }).format(new Date(instant))
}
function due(item: TodayItem) {
  if (item.time.kind === 'LOCAL_DATE') return item.time.localDate === props.overview.context.localDate ? '今天' : item.time.localDate.slice(5).replace('-', '月') + '日'
  if (item.time.kind === 'INSTANT') return localDate(item.time.instant, { month: 'numeric', day: 'numeric' })
  return '待安排'
}
const icons: Record<TodaySourceKind, string> = { KNOWLEDGE_DRAFT: 'file-text', TRAINING_SESSION: 'play', PREPARATION_TASK: 'clipboard', MEMORY_CARD: 'clock', REVIEW_ACTION: 'refresh-cw', INTERVIEW_EVENT: 'calendar-days' }
</script>
<template>
  <div class="today-dashboard">
    <div class="today-summary-strip" aria-label="已载入事项摘要"><div><span>已载入事项</span><strong>{{ entries.length }}<small>项</small></strong></div><div><span>已估算时长</span><strong>{{ entries.some(item => item.estimate.minutes !== null) ? minutes : '—' }}<small>分钟</small></strong></div><div><span>来源类型</span><strong>{{ new Set(entries.map(item => item.source.kind)).size }}<small>类</small></strong></div></div>
    <div class="today-dashboard-grid">
      <section class="today-continuation">
        <h2>{{ continuation ? '接着上次的进度' : '从一次练习开始' }}</h2>
        <div class="continuation-body"><div class="continuation-ring"><RIcon :name="continuation ? icons[continuation.source.kind] : 'play'" :size="43" /></div><div><h3>{{ continuation?.title ?? '留一点时间，开始新的积累' }}</h3><p>{{ continuation ? todaySourceKindLabels[continuation.source.kind] : '知识、准备、训练与复盘' }}</p><div class="continuation-actions"><NButton type="primary" :disabled="disabled" @click="continuation ? emit('open', continuation.continuation) : emit('quick', 'training')"><RIcon name="play" :size="22" />{{ continuation ? '继续进行' : '开始训练' }}</NButton><RouterLink to="/recent">最近访问<RIcon name="chevron-right" :size="21" /></RouterLink></div></div></div>
      </section>
      <section class="today-task-panel">
        <header class="today-task-heading"><h2>今天要做</h2><div class="today-task-filters" aria-label="待办筛选"><button :class="{ active: filter === 'all' }" :aria-pressed="filter === 'all'" @click="filter = 'all'">全部 {{ entries.length }}</button><button :class="{ active: filter === 'due' }" :aria-pressed="filter === 'due'" @click="filter = 'due'">到期</button><button :class="{ active: filter === 'review' }" :aria-pressed="filter === 'review'" @click="filter = 'review'">待复练</button></div></header>
        <table><thead><tr><th>任务</th><th>来源</th><th>时长</th><th>到期</th><th>操作</th></tr></thead><tbody><tr v-for="item in tasks" :key="todaySourceKey(item.source)"><td><button class="today-task-title" :disabled="disabled" @click="emit('open', item.continuation)"><RIcon :name="icons[item.source.kind]" :size="22" />{{ item.title }}</button></td><td><span class="today-source-tag">{{ todaySourceKindLabels[item.source.kind] }}</span></td><td>{{ item.estimate.minutes !== null ? `${item.estimate.minutes} 分钟` : '—' }}</td><td>{{ due(item) }}</td><td><button class="studio-icon-button" title="查看来源与安排" :disabled="disabled" @click="emit('source', item.source.kind, item.section ?? 'TODAY')"><RIcon name="ellipsis" :size="22" /></button></td></tr></tbody></table>
        <div v-if="!tasks.length" class="today-dashboard-empty"><RIcon name="circle-check" :size="37" /><p>当前范围没有待办事项</p><NButton :disabled="disabled" @click="emit('quick', 'note')">记录新的想法</NButton></div>
      </section>
      <aside class="today-interview-panel">
        <h2><RIcon name="calendar-days" :size="28" />下一场面试</h2>
        <template v-if="interview?.eventWindow"><div class="interview-event-heading"><div class="interview-date-badge"><strong>{{ localDate(interview.eventWindow.startAt, { day: 'numeric' }).replace('日', '') }}</strong><span>{{ new Intl.DateTimeFormat('en', { timeZone: overview.context.zoneId, month: 'short' }).format(new Date(interview.eventWindow.startAt)).toUpperCase() }}</span></div><div><h3>{{ interview.title }}</h3><p><RIcon name="clock" :size="22" />{{ localDate(interview.eventWindow.startAt, { hour: '2-digit', minute: '2-digit', hour12: false }) }}</p><small>{{ overview.context.zoneId }}</small></div></div><NButton block :disabled="disabled" @click="emit('open', interview.continuation)">查看准备与安排<RIcon name="chevron-right" :size="21" /></NButton></template>
        <div v-else class="interview-empty"><RIcon name="calendar-days" :size="65" /><h3>为下一次对话做好准备</h3><p>把面试时间与准备材料留在一起</p><RouterLink to="/prepare/schedule" class="today-schedule-link">新建面试安排<RIcon name="plus" :size="22" /></RouterLink></div>
        <section v-if="overview.memoryQueue" class="today-memory-summary"><h3>回忆安排</h3><p>今日新卡 <strong>{{ overview.memoryQueue.budget.newCards }} / {{ overview.memoryQueue.budget.newLimit }}</strong></p><p>今日复习 <strong>{{ overview.memoryQueue.budget.reviewCards }} / {{ overview.memoryQueue.budget.reviewLimit }}</strong></p><div class="today-budget-track"><i :style="{ width: `${Math.min(100, (overview.memoryQueue.budget.newCards + overview.memoryQueue.budget.reviewCards) / Math.max(1, overview.memoryQueue.budget.newLimit + overview.memoryQueue.budget.reviewLimit) * 100)}%` }" /></div><RouterLink to="/knowledge/recall">进入回忆队列<RIcon name="arrow-right" :size="22" /></RouterLink></section>
        <button class="today-quick-record" :disabled="disabled" @click="emit('quick', 'note')"><RIcon name="pencil" :size="18" /><span>记下一个想法</span><RIcon name="arrow-right" :size="18" /></button>
      </aside>
    </div>
  </div>
</template>
<style scoped>
.today-summary-strip { display: flex; gap: 36px; padding: 24px 0; margin-bottom: 8px; }.today-summary-strip > div { display: flex; align-items: baseline; gap: 12px; min-width: 0; }.today-summary-strip > div > span { color: var(--muted); font-size: 12px; }.today-summary-strip strong { color: var(--ink); font-size: 24px; line-height: 1.2; font-weight: 500; font-variant-numeric: tabular-nums; }.today-summary-strip small { margin-left: 6px; color: var(--faint); font-size: 11px; font-weight: 400; }
.today-dashboard-grid { display: grid; grid-template-columns: minmax(0, 1fr) 264px; grid-template-rows: auto 1fr; gap: 32px; align-items: start; }
.today-dashboard-grid > :is(section, aside) { min-width: 0; }
.today-continuation { padding: 28px; border: 1px solid var(--line); border-radius: 10px; background: var(--studio-pane); }.today-dashboard .today-continuation > h2 { color: var(--muted); font-size: 13px; font-weight: 400; }
.today-dashboard h2 { display: flex; align-items: center; gap: 9px; font-size: 16px; font-weight: 600; }.today-dashboard h2 > svg { width: 19px; height: 19px; color: var(--brand); }
.continuation-body { display: flex; align-items: start; gap: 18px; padding-top: 20px; }.continuation-ring { display: grid; place-items: center; width: 40px; height: 44px; flex: none; border-radius: 8px; color: var(--brand); background: var(--brand-soft); }.continuation-ring svg { width: 22px; height: 22px; }
.continuation-body > div:last-child { min-width: 0; }.continuation-body h3 { font-size: 20px; line-height: 1.5; font-weight: 600; overflow-wrap: anywhere; }.continuation-body p { margin-top: 6px; color: var(--muted); font-size: 13px; }
.continuation-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 18px; margin-top: 16px; }.continuation-actions a { display: flex; align-items: center; gap: 4px; color: var(--muted); font-size: 12px; }
.today-task-panel { grid-column: 1; grid-row: 2; }.today-task-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }.today-task-filters { display: flex; gap: 4px; }.today-task-filters button { min-height: 32px; padding: 5px 10px; border-radius: 5px; color: var(--muted); font-size: 12px; }.today-task-filters button.active { background: var(--surface-soft); color: var(--ink); }
.today-task-panel table { width: 100%; border-collapse: collapse; text-align: left; font-size: 12px; }.today-task-panel th { padding: 10px 8px; color: var(--faint); font-weight: 400; border-bottom: 1px solid var(--studio-divider); }.today-task-panel td { padding: 16px 8px; border-bottom: 1px solid var(--studio-divider); }.today-task-panel th:first-child { width: 48%; }.today-task-panel th:first-child, .today-task-panel td:first-child { padding-left: 0; }.today-task-panel th:last-child, .today-task-panel td:last-child { text-align: right; padding-right: 0; }
.today-task-title { display: flex; align-items: center; gap: 10px; padding: 0; text-align: left; font-size: 14px; line-height: 1.6; overflow-wrap: anywhere; }.today-task-title:hover { color: var(--brand-strong); }.today-task-title svg { width: 17px; color: var(--muted); flex: none; }
.today-source-tag { font-size: 11px; color: var(--muted); white-space: nowrap; }
.today-dashboard-empty { min-height: 160px; display: grid; align-content: center; justify-items: start; gap: 12px; color: var(--muted); font-size: 13px; }.today-dashboard-empty > svg { display: none; }
.today-interview-panel { grid-column: 2; grid-row: 1 / 3; display: flex; flex-direction: column; padding: 4px 0 4px 24px; border-left: 1px solid var(--studio-divider); }.interview-event-heading { display: flex; gap: 14px; align-items: center; margin: 24px 0; }.interview-date-badge { flex: none; width: 52px; height: 60px; display: grid; align-content: center; justify-items: center; border: 1px solid var(--line); border-radius: 8px; background: var(--brand-soft); color: var(--brand-strong); }.interview-date-badge strong { font-size: 26px; line-height: 1.2; font-weight: 600; }.interview-date-badge span { font-size: 11px; }
.interview-event-heading h3 { font-size: 15px; }.interview-event-heading p { display: flex; align-items: center; gap: 6px; margin-top: 8px; font-size: 13px; color: var(--muted); }.interview-event-heading small { color: var(--muted); font-size: 11px; }
.interview-empty { display: grid; justify-items: start; gap: 10px; padding: 22px 0 8px; }.interview-empty > svg { display: none; }.interview-empty h3 { font-size: 15px; }.interview-empty p { font-size: 12px; color: var(--muted); }.today-schedule-link { display: flex; align-items: center; gap: 6px; color: var(--brand); font-size: 13px; }
.today-memory-summary { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--line); }.today-memory-summary h3 { font-size: 15px; margin-bottom: 12px; }.today-memory-summary p { display: flex; justify-content: space-between; margin: 9px 0; color: var(--muted); font-size: 12px; }.today-memory-summary strong { color: var(--ink); font-weight: 500; }
.today-budget-track { height: 5px; border-radius: 3px; background: var(--surface-deep); margin-top: 14px; overflow: hidden; }.today-budget-track i { display: block; height: 100%; border-radius: inherit; background: var(--brand); }
.today-memory-summary a { display: flex; align-items: center; justify-content: space-between; margin-top: 14px; font-size: 12px; color: var(--brand); }
.today-quick-record { margin-top: 24px; display: flex; align-items: center; gap: 8px; padding: 10px; border: 1px solid var(--line); border-radius: 6px; color: var(--muted); text-align: left; font-size: 12px; }.today-quick-record span { flex: 1; }.today-quick-record svg { width: 17px; height: 17px; }
@container workspace (max-width: 960px) { .today-task-panel th:nth-child(3), .today-task-panel td:nth-child(3) { display: none; }.today-dashboard-grid { gap: 24px; grid-template-columns: minmax(0, 1fr) 240px; } }
@container workspace (max-width: 760px) { .today-dashboard-grid { grid-template-columns: minmax(0,1fr); }.today-interview-panel { grid-column: 1; grid-row: 3; padding: 24px 0 0; border-left: 0; border-top: 1px solid var(--studio-divider); }.today-summary-strip { justify-content: space-between; gap: 16px; }.today-summary-strip > div { display: grid; gap: 6px; } }
@media(max-width:500px) { .today-task-panel th:nth-child(4), .today-task-panel td:nth-child(4) { display: none; }.today-continuation { padding: 22px 20px; }.continuation-body { gap: 12px; }.continuation-body h3 { font-size: 17px; }.continuation-ring { width: 32px; height: 36px; }.today-summary-strip { padding: 20px 0; }.today-summary-strip strong { font-size: 22px; }.today-summary-strip > div > span { font-size: 11px; }.today-task-heading { gap: 8px; }.today-task-filters button { padding-inline: 8px; } }
</style>
