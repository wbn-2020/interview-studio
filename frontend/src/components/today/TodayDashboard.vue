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
// 概览只承载分区预览；首屏数字必须标注为“已载入”，不能冒充服务端全量计数。
const attentionItems = computed(() => entries.value.filter(item => item.section === 'ATTENTION'))
const plannedItems = computed(() => entries.value.filter(item => item.section === 'TODAY'))
// 目标关联只说明事项属于哪一版准备范围，不等同于能力评估或准备完成度。
const targetItems = computed(() => entries.value.filter(item => item.targetRefs.length > 0))
const targetAttentionItems = computed(() => targetItems.value.filter(item => item.section === 'ATTENTION'))
const hasTargetInterview = computed(() => targetItems.value.some(item => item.source.kind === 'INTERVIEW_EVENT'))
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
    <section class="today-focus" aria-labelledby="today-focus-heading">
      <div class="today-focus-main">
        <div class="continuation-body">
          <div class="continuation-ring"><RIcon :name="continuation ? icons[continuation.source.kind] : 'play'" :size="24" /></div>
          <div class="continuation-copy">
            <p class="today-eyebrow">{{ continuation ? '继续进行 · ' + todaySourceKindLabels[continuation.source.kind] : '开始新的练习' }}</p>
            <h3 id="today-focus-heading">{{ continuation?.title ?? '选择一道题，把理解变成表达' }}</h3>
            <p class="today-focus-description">{{ attentionItems.length ? `另有 ${attentionItems.length} 项已载入的待处理事项，可按紧急程度接着处理。` : '当前预览没有额外的待处理事项，可以从今日计划继续推进。' }}</p>
          </div>
          <div class="continuation-actions">
            <NButton type="primary" :disabled="disabled" @click="continuation ? emit('open', continuation.continuation) : emit('quick', 'training')"><RIcon name="play" :size="17" />{{ continuation ? '继续进行' : '开始训练' }}</NButton>
            <RouterLink to="/recent">最近访问<RIcon name="chevron-right" :size="16" /></RouterLink>
          </div>
        </div>
        <div class="today-focus-status" aria-label="已载入优先事项摘要">
          <div><span>待处理</span><strong>{{ attentionItems.length }}</strong><small>项已载入</small></div>
          <div><span>今日安排</span><strong>{{ plannedItems.length }}</strong><small>项已载入</small></div>
        </div>
      </div>
      <div v-if="attentionItems.length" class="today-attention-queue" aria-label="已载入待处理项">
        <div class="today-attention-label"><span>先处理这些</span><small>其余见下方清单</small></div>
        <div class="today-attention-items">
          <button v-for="item in attentionItems.slice(0, 3)" :key="todaySourceKey(item.source)" type="button" class="today-attention-item" :disabled="disabled" @click="emit('open', item.continuation)">
            <RIcon :name="icons[item.source.kind]" :size="18" />
            <span>{{ item.title }}</span>
            <small>{{ due(item) }}</small>
          </button>
        </div>
      </div>
    </section>

    <div class="today-dashboard-grid">
      <section class="today-task-panel" aria-labelledby="today-task-heading">
        <header class="today-task-heading">
          <div>
            <p class="today-section-label">今日计划</p>
            <h2 id="today-task-heading">计划与任务</h2>
            <p class="today-panel-description">先看到期与复练，再按当前范围安排投入。</p>
          </div>
          <div class="today-task-filters" aria-label="待办筛选">
            <button :class="{ active: filter === 'all' }" :aria-pressed="filter === 'all'" @click="filter = 'all'">全部 {{ entries.length }}</button>
            <button :class="{ active: filter === 'due' }" :aria-pressed="filter === 'due'" @click="filter = 'due'">到期</button>
            <button :class="{ active: filter === 'review' }" :aria-pressed="filter === 'review'" @click="filter = 'review'">待复练</button>
          </div>
        </header>
        <div class="today-summary-strip" aria-label="已载入事项摘要"><span>已载入 <strong>{{ entries.length }}</strong> 项</span><span>估算时长 <strong>{{ entries.some(item => item.estimate.minutes !== null) ? minutes + ' 分钟' : '未估算' }}</strong></span><span>{{ new Set(entries.map(item => item.source.kind)).size }} 类来源</span></div>
        <div class="today-task-table-wrap">
          <table aria-label="今日计划与任务清单">
            <thead><tr><th>任务</th><th>来源</th><th>时长</th><th>到期</th><th>操作</th></tr></thead>
            <tbody><tr v-for="item in tasks" :key="todaySourceKey(item.source)"><td><button class="today-task-title" :disabled="disabled" @click="emit('open', item.continuation)"><RIcon :name="icons[item.source.kind]" :size="22" />{{ item.title }}</button></td><td><span class="today-source-tag">{{ todaySourceKindLabels[item.source.kind] }}</span></td><td>{{ item.estimate.minutes !== null ? `${item.estimate.minutes} 分钟` : '—' }}</td><td>{{ due(item) }}</td><td><button class="studio-icon-button" title="查看来源与安排" :disabled="disabled" @click="emit('source', item.source.kind, item.section ?? 'TODAY')"><RIcon name="ellipsis" :size="22" /></button></td></tr></tbody>
          </table>
        </div>
        <div v-if="!tasks.length" class="today-dashboard-empty"><RIcon name="circle-check" :size="37" /><p>当前范围没有待办事项</p><NButton :disabled="disabled" @click="emit('quick', 'note')">记录新的想法</NButton></div>
      </section>

      <aside class="today-side-column" aria-label="面试安排与目标准备">
        <section class="today-side-panel today-interview-panel" aria-labelledby="today-interview-heading">
          <h2 id="today-interview-heading"><RIcon name="calendar-days" :size="28" />下一场面试</h2>
          <template v-if="interview?.eventWindow">
            <div class="interview-event-heading"><div class="interview-date-badge"><strong>{{ localDate(interview.eventWindow.startAt, { day: 'numeric' }).replace('日', '') }}</strong><span>{{ new Intl.DateTimeFormat('en', { timeZone: overview.context.zoneId, month: 'short' }).format(new Date(interview.eventWindow.startAt)).toUpperCase() }}</span></div><div><h3>{{ interview.title }}</h3><p><RIcon name="clock" :size="22" />{{ localDate(interview.eventWindow.startAt, { hour: '2-digit', minute: '2-digit', hour12: false }) }}</p><small>{{ overview.context.zoneId }}</small></div></div>
            <NButton block :disabled="disabled" @click="emit('open', interview.continuation)">查看准备与安排<RIcon name="chevron-right" :size="21" /></NButton>
          </template>
          <div v-else class="interview-empty"><RIcon name="calendar-days" :size="65" /><h3>为下一次对话做好准备</h3><p>把面试时间与准备材料留在一起</p><RouterLink to="/prepare/schedule" class="today-schedule-link">新建面试安排<RIcon name="plus" :size="22" /></RouterLink></div>
        </section>

        <section class="today-side-panel today-target-panel" aria-labelledby="today-target-heading">
          <div class="today-side-heading"><h2 id="today-target-heading"><RIcon name="target" :size="22" />目标准备度</h2><span class="today-scope-badge">{{ overview.filter.targetId ? '目标筛选中' : '全部目标' }}</span></div>
          <div class="today-target-metrics">
            <div><strong>{{ targetItems.length }}</strong><span>已载入关联事项</span></div>
            <div><strong>{{ targetAttentionItems.length }}</strong><span>其中待处理</span></div>
          </div>
          <p class="today-target-interview">{{ hasTargetInterview ? '已载入关联目标的面试安排。' : '当前预览未载入关联目标的面试安排。' }}</p>
          <p class="today-target-note">这里只汇总当前已载入的准备事项，不代表掌握程度或准备完成度。</p>
        </section>

        <details class="today-side-panel today-secondary-panel">
          <summary><span><RIcon name="pencil" :size="18" />回忆安排与快速记录</span><RIcon name="chevron-down" :size="18" /></summary>
          <div class="today-secondary-content">
            <section v-if="overview.memoryQueue" class="today-memory-summary" aria-labelledby="today-memory-heading">
              <h3 id="today-memory-heading">回忆安排</h3>
              <p>今日新卡 <strong>{{ overview.memoryQueue.budget.newCards }} / {{ overview.memoryQueue.budget.newLimit }}</strong></p>
              <p>今日复习 <strong>{{ overview.memoryQueue.budget.reviewCards }} / {{ overview.memoryQueue.budget.reviewLimit }}</strong></p>
              <div class="today-budget-track"><i :style="{ width: `${Math.min(100, (overview.memoryQueue.budget.newCards + overview.memoryQueue.budget.reviewCards) / Math.max(1, overview.memoryQueue.budget.newLimit + overview.memoryQueue.budget.reviewLimit) * 100)}%` }" /></div>
              <RouterLink to="/knowledge/recall">进入回忆队列<RIcon name="arrow-right" :size="22" /></RouterLink>
            </section>
            <button class="today-quick-record" :disabled="disabled" @click="emit('quick', 'note')"><RIcon name="pencil" :size="18" /><span>记下一个想法</span><RIcon name="arrow-right" :size="18" /></button>
          </div>
        </details>
      </aside>
    </div>
  </div>
</template>
<style scoped>
.today-dashboard { min-width: 0; }
.today-summary-strip { display: flex; flex-wrap: wrap; gap: 6px 20px; margin: 18px 0 14px; color: var(--faint); font-size: 12px; }.today-summary-strip strong { color: var(--muted); font-weight: 500; font-variant-numeric: tabular-nums; }
.today-focus { margin-top: 20px; overflow: hidden; border: 1px solid var(--line); border-radius: 10px; background: var(--studio-pane); box-shadow: inset 3px 0 0 var(--brand); }
.today-focus-main { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 28px; align-items: center; padding: 24px 26px; }
.today-dashboard h2 { display: flex; align-items: center; gap: 9px; margin: 0; font-size: 16px; font-weight: 600; }.today-dashboard h2 > svg { width: 19px; height: 19px; color: var(--brand); flex: none; }
.today-section-label, .today-eyebrow { margin: 0 0 6px; color: var(--brand); font-size: 11px; font-weight: 600; letter-spacing: .08em; }.today-eyebrow { display: flex; align-items: center; gap: 8px; }.today-eyebrow::before { content: ''; width: 6px; height: 6px; border-radius: 50%; background: var(--brand); }
.continuation-body { display: flex; align-items: center; gap: 16px; min-width: 0; }.continuation-ring { display: grid; place-items: center; width: 52px; height: 56px; flex: none; border-radius: 9px; color: var(--brand); background: var(--brand-soft); }.continuation-ring svg { width: 24px; height: 24px; }
.continuation-copy { flex: 1; min-width: 0; }.continuation-copy h3 { margin-top: 4px; font-size: 22px; line-height: 1.4; font-weight: 600; overflow-wrap: anywhere; }.today-focus-description { margin-top: 8px; color: var(--muted); font-size: 12px; line-height: 1.7; }
.continuation-actions { display: flex; flex-direction: column; align-items: end; gap: 8px; flex: none; }.continuation-actions a { display: flex; align-items: center; gap: 4px; color: var(--muted); font-size: 11px; }
.today-focus-status { display: grid; grid-template-columns: repeat(2, minmax(92px, 1fr)); border-left: 1px solid var(--studio-divider); }.today-focus-status > div { display: grid; gap: 2px; padding: 4px 18px; }.today-focus-status span, .today-focus-status small { color: var(--muted); font-size: 11px; }.today-focus-status strong { color: var(--ink); font-size: 28px; line-height: 1.2; font-weight: 600; font-variant-numeric: tabular-nums; }
.today-attention-queue { display: grid; grid-template-columns: 130px minmax(0, 1fr); gap: 16px; align-items: center; padding: 14px 26px; border-top: 1px solid var(--studio-divider); background: var(--surface-soft); }.today-attention-label { display: grid; gap: 4px; }.today-attention-label span { font-size: 12px; font-weight: 600; }.today-attention-label small { color: var(--muted); font-size: 10px; }
.today-attention-items { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }.today-attention-item { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 8px; align-items: center; min-width: 0; min-height: 42px; padding: 9px 10px; border: 1px solid var(--line); border-radius: 6px; background: var(--studio-pane); color: var(--ink); text-align: left; font: inherit; }.today-attention-item > svg { color: var(--brand); flex: none; }.today-attention-item > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }.today-attention-item > small { color: var(--muted); font-size: 10px; white-space: nowrap; }.today-attention-item:hover:not(:disabled) { border-color: var(--brand); }.today-attention-item:disabled { opacity: .55; cursor: not-allowed; }
.today-dashboard-grid { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 24px; align-items: start; margin-top: 24px; }.today-dashboard-grid > :is(section, aside) { min-width: 0; }
.today-task-panel { padding: 22px; border: 1px solid var(--line); border-radius: 9px; background: var(--studio-pane); }.today-task-heading { display: flex; align-items: start; justify-content: space-between; flex-wrap: wrap; gap: 12px; }.today-panel-description { margin-top: 7px; color: var(--muted); font-size: 12px; line-height: 1.6; }.today-task-filters { display: flex; gap: 4px; }.today-task-filters button { min-height: 32px; padding: 5px 10px; border-radius: 5px; color: var(--muted); font-size: 12px; }.today-task-filters button.active { background: var(--surface-soft); color: var(--ink); }
.today-task-table-wrap { overflow-x: auto; }.today-task-panel table { width: 100%; border-collapse: collapse; text-align: left; font-size: 12px; }.today-task-panel th { padding: 10px 8px; color: var(--faint); font-weight: 400; border-bottom: 1px solid var(--studio-divider); }.today-task-panel td { padding: 16px 8px; border-bottom: 1px solid var(--studio-divider); }.today-task-panel th:first-child { width: 46%; }.today-task-panel th:first-child, .today-task-panel td:first-child { padding-left: 0; }.today-task-panel th:last-child, .today-task-panel td:last-child { text-align: right; padding-right: 0; }
.today-task-title { display: flex; align-items: center; gap: 10px; padding: 0; text-align: left; font-size: 14px; line-height: 1.6; overflow-wrap: anywhere; }.today-task-title:hover { color: var(--brand-strong); }.today-task-title svg { width: 17px; color: var(--muted); flex: none; }.today-source-tag { font-size: 11px; color: var(--muted); white-space: nowrap; }
.today-dashboard-empty { min-height: 160px; display: grid; align-content: center; justify-items: start; gap: 12px; color: var(--muted); font-size: 13px; }.today-dashboard-empty > svg { display: none; }
.today-side-column { display: grid; gap: 16px; }.today-side-panel { min-width: 0; padding: 20px; border: 1px solid var(--line); border-radius: 9px; background: var(--studio-pane); }.today-side-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; }.today-scope-badge { padding: 4px 7px; border-radius: 4px; background: var(--brand-soft); color: var(--brand-strong); font-size: 10px; white-space: nowrap; }
.interview-event-heading { display: flex; gap: 14px; align-items: center; margin: 22px 0; }.interview-date-badge { flex: none; width: 52px; height: 60px; display: grid; align-content: center; justify-items: center; border: 1px solid var(--line); border-radius: 8px; background: var(--brand-soft); color: var(--brand-strong); }.interview-date-badge strong { font-size: 26px; line-height: 1.2; font-weight: 600; }.interview-date-badge span { font-size: 11px; }.interview-event-heading h3 { font-size: 15px; overflow-wrap: anywhere; }.interview-event-heading p { display: flex; align-items: center; gap: 6px; margin-top: 8px; font-size: 13px; color: var(--muted); }.interview-event-heading small { color: var(--muted); font-size: 11px; }
.interview-empty { display: grid; justify-items: start; gap: 10px; padding: 22px 0 8px; }.interview-empty > svg { display: none; }.interview-empty h3 { font-size: 15px; }.interview-empty p { font-size: 12px; color: var(--muted); line-height: 1.6; }.today-schedule-link { display: flex; align-items: center; gap: 6px; color: var(--brand); font-size: 13px; }
.today-target-metrics { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 18px; }.today-target-metrics > div { display: grid; gap: 5px; padding: 12px; border-radius: 6px; background: var(--surface-soft); }.today-target-metrics strong { font-size: 22px; line-height: 1.2; font-weight: 600; font-variant-numeric: tabular-nums; }.today-target-metrics span { color: var(--muted); font-size: 10px; line-height: 1.5; }.today-target-interview { margin-top: 14px; color: var(--muted); font-size: 12px; line-height: 1.6; }.today-target-note { margin-top: 8px; color: var(--faint); font-size: 10px; line-height: 1.7; }
.today-secondary-panel { padding: 0; }.today-secondary-panel > summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 52px; padding: 0 18px; cursor: pointer; list-style: none; color: var(--muted); font-size: 12px; }.today-secondary-panel > summary::-webkit-details-marker { display: none; }.today-secondary-panel > summary > span { display: flex; align-items: center; gap: 8px; }.today-secondary-panel > summary > svg { transition: transform .18s ease; }.today-secondary-panel[open] > summary > svg { transform: rotate(180deg); }.today-secondary-panel > summary:focus-visible, .today-attention-item:focus-visible, .today-quick-record:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
.today-secondary-content { display: grid; gap: 16px; padding: 0 18px 18px; }.today-memory-summary { padding-top: 18px; border-top: 1px solid var(--line); }.today-memory-summary h3 { font-size: 15px; margin-bottom: 12px; }.today-memory-summary p { display: flex; justify-content: space-between; margin: 9px 0; color: var(--muted); font-size: 12px; }.today-memory-summary strong { color: var(--ink); font-weight: 500; }.today-budget-track { height: 5px; border-radius: 3px; background: var(--surface-deep); margin-top: 14px; overflow: hidden; }.today-budget-track i { display: block; height: 100%; border-radius: inherit; background: var(--brand); }.today-memory-summary a { display: flex; align-items: center; justify-content: space-between; margin-top: 14px; font-size: 12px; color: var(--brand); }
.today-quick-record { width: 100%; box-sizing: border-box; display: flex; align-items: center; gap: 8px; padding: 11px 12px; border: 1px solid var(--line); border-radius: 6px; color: var(--muted); text-align: left; font: inherit; font-size: 12px; }.today-quick-record span { flex: 1; }.today-quick-record svg { width: 17px; height: 17px; flex: none; }.today-quick-record:disabled { opacity: .55; cursor: not-allowed; }
@container workspace (max-width: 1100px) { .today-task-panel th:nth-child(3), .today-task-panel td:nth-child(3) { display: none; }.today-dashboard-grid { gap: 20px; grid-template-columns: minmax(0, 1fr) 280px; }.today-focus-main { padding: 22px; }.today-focus-status > div { padding-inline: 14px; }.today-attention-queue { padding-inline: 22px; } }
@container workspace (max-width: 900px) { .today-dashboard-grid { grid-template-columns: minmax(0, 1fr); }.today-side-column { grid-template-columns: repeat(2, minmax(0, 1fr)); }.today-secondary-panel { grid-column: 1 / -1; } }
@container workspace (max-width: 620px) { .today-focus-main { grid-template-columns: minmax(0, 1fr); gap: 18px; }.today-focus-status { padding-top: 16px; border-left: 0; border-top: 1px solid var(--studio-divider); }.today-attention-queue { grid-template-columns: minmax(0, 1fr); }.today-attention-items { grid-template-columns: minmax(0, 1fr); }.today-side-column { grid-template-columns: minmax(0, 1fr); }.today-secondary-panel { grid-column: auto; } }
@media(max-width:500px) { .today-task-panel th:nth-child(4), .today-task-panel td:nth-child(4) { display: none; }.today-focus-main, .today-attention-queue { padding-inline: 16px; }.continuation-body { display: grid; grid-template-columns: 36px minmax(0, 1fr); gap: 12px; }.continuation-ring { width: 36px; height: 40px; }.continuation-copy h3 { font-size: 18px; }.continuation-actions { grid-column: 2; flex-direction: row; flex-wrap: wrap; align-items: center; gap: 12px; }.today-focus-status > div { padding-inline: 12px; }.today-task-panel, .today-side-panel { padding: 18px 16px; }.today-secondary-panel { padding: 0; }.today-task-heading { gap: 8px; }.today-task-filters button { padding-inline: 8px; } }
</style>
