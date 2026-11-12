<script setup lang="ts">
import { computed } from 'vue'
import { NButton } from 'naive-ui'
import type { TodayContinuation, TodayItem, TodaySourceRef } from '../../api/today'
import { todayActionLabels, todayReasonLabels, todaySourceKindLabels, todayWarningLabels } from '../../api/today'
import { trainingModeLabels, trainingStatusLabels } from '../../api/training'
import { preparationTaskStatusLabels } from '../../api/preparationTask'
import { memoryBlockReasonLabels, memoryPauseReasonLabels } from '../../api/memoryReview'
import { scheduleStatusLabels, scheduleTimingLabels } from '../../api/schedule'

const props = defineProps<{ item: TodayItem; zoneId: string; disabled?: boolean; inspecting?: boolean; inspectable?: boolean }>()
const emit = defineEmits<{ open: [value: TodayContinuation]; inspect: [value: TodaySourceRef]; plan: [value: TodaySourceRef]; candidate: [value: TodaySourceRef] }>()
const stateLabel = computed(() => {
  const state = props.item.sourceStatus
  switch (state.kind) {
    case 'KNOWLEDGE_DRAFT': return `${state.knowledge.itemKind === 'NOTE' ? '笔记' : '概念'} · ${state.knowledge.status === 'ARCHIVED' ? '已归档' : state.knowledge.unpublishedDraft ? '有待发布草稿' : '已发布'}`
    case 'TRAINING_SESSION': return `${trainingModeLabels[state.training.mode]} · ${trainingStatusLabels[state.training.status]}${state.training.repracticeId ? ' · 复练训练' : ''}`
    case 'PREPARATION_TASK': return preparationTaskStatusLabels[state.task.status]
    case 'MEMORY_CARD': return state.memory.openAttempt ? `${state.memory.openAttempt.mode === 'CRAM' ? '冲刺' : '常规'}回忆 · ${state.memory.openAttempt.state === 'THINKING' ? '尚未揭示' : '已揭示待处理'}`
      : state.memory.cardStatus === 'ARCHIVED' ? '卡片已归档' : state.memory.pauseReason ? memoryPauseReasonLabels[state.memory.pauseReason] : state.memory.scheduleStatus === 'ACTIVE' ? '常规排程中' : '未启用常规排程'
    case 'REVIEW_ACTION': return state.action.status === 'MERGED' ? '已合并，请查看去向' : `跟踪 ${state.action.trackingCount} 个来源`
    case 'INTERVIEW_EVENT': return state.interview.status === 'PLANNED' ? scheduleTimingLabels[state.interview.timing] : scheduleStatusLabels[state.interview.status]
  }
})
const actions = computed(() => {
  const values = props.item.availableActions.length ? props.item.availableActions.map(action => ({ label: todayActionLabels[action.kind], continuation: action.continuation }))
    : [{ label: '打开来源', continuation: props.item.continuation }]
  return [...new Map(values.map(action => [JSON.stringify(action.continuation), action])).values()]
})
function time(value: string, zoneId: string) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: zoneId, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(value))
}
</script>

<template>
  <article class="today-source-row" :class="{ inspecting }">
    <div class="source-summary"><div class="source-kicker"><span>{{ todaySourceKindLabels[item.source.kind] }}</span><small>{{ stateLabel }}</small></div><h3>{{ item.title }}</h3>
      <p v-if="item.time.kind === 'LOCAL_DATE'" class="source-time">{{ item.time.zoneBasis === 'PLACEMENT' ? '安排偏好日期' : '原计划日期' }} {{ item.time.localDate }} · {{ item.time.zoneId }}</p>
      <p v-else-if="item.eventWindow" class="source-time">{{ time(item.eventWindow.startAt, zoneId) }} — {{ time(item.eventWindow.endAt, zoneId) }} · {{ zoneId }}<span v-if="item.eventWindow.zoneId !== zoneId">；原安排 {{ item.eventWindow.zoneId }}</span></p>
      <p v-else-if="item.time.kind === 'INSTANT'" class="source-time">实际到期 {{ time(item.time.instant, item.time.zoneId) }} · {{ item.time.zoneId }}<span v-if="item.time.zoneBasis === 'DISPLAY'">（显示时区）</span></p>
      <p v-if="item.estimate.basis !== 'UNKNOWN'" class="source-meta">{{ item.estimate.basis === 'USER' ? '用户预计' : '任务预计' }} {{ item.estimate.minutes }} 分钟</p>
      <template v-if="item.placement"><p class="source-meta">安排优先级 {{ item.placement.priority }} · 显示顺序 {{ item.placement.orderValue }}<span v-if="item.placement.visibility === 'PAUSED'"> · 安排推荐暂停</span><span v-if="item.placement.dateLocked"> · 日期已锁定</span></p><p v-if="item.placement.suppressedOn" class="source-meta">暂不推荐 {{ item.placement.suppressedOn.localDate }} · {{ item.placement.suppressedOn.zoneId }}</p></template>
      <p v-if="item.sourceStatus.kind === 'TRAINING_SESSION' && item.sourceStatus.training.savedDraftCount" class="source-meta">服务器已保存 {{ item.sourceStatus.training.savedDraftCount }} 题回答草稿</p>
      <p v-if="item.pendingCount !== null" class="source-meta">{{ item.pendingCount }} 个来源待复练或判断</p>
      <div v-if="item.reasons.length" class="source-reasons"><span v-for="reason in item.reasons" :key="reason">{{ todayReasonLabels[reason] }}</span></div>
      <p v-for="warning in item.warnings" :key="warning" class="source-warning">{{ todayWarningLabels[warning] }}</p>
      <p v-if="item.sourceStatus.kind === 'MEMORY_CARD' && item.sourceStatus.memory.eligibility.blockers.length" class="source-warning">{{ item.sourceStatus.memory.eligibility.blockers.map(reason => memoryBlockReasonLabels[reason]).join('；') }}</p>
    </div>
    <div class="source-actions"><NButton v-for="action in actions" :key="JSON.stringify(action.continuation)" size="small" :disabled="disabled" @click="emit('open', action.continuation)">{{ action.label }}</NButton><NButton text size="small" :disabled="disabled" @click="emit('plan', item.source)">安排偏好</NButton><NButton text size="small" :disabled="disabled" @click="emit('candidate', item.source)">加入建议候选</NButton><NButton v-if="inspectable !== false" text size="small" :disabled="disabled" @click="emit('inspect', item.source)">{{ inspecting ? '重新核对来源' : '核对当前状态' }}</NButton></div>
  </article>
</template>

<style scoped>
.today-source-row { display: flex; justify-content: space-between; align-items: start; gap: 20px; padding: 18px 0; border-top: 1px solid var(--line); }
.today-source-row.inspecting { border-left: 2px solid var(--brand); padding-left: 13px; }
.source-summary { min-width: 0; }.source-kicker { display: flex; flex-wrap: wrap; gap: 6px 12px; align-items: baseline; font-size: 11px; color: var(--brand); }.source-kicker small { font-size: 11px; color: var(--muted); }
h3 { margin: 7px 0; font-size: 14px; font-weight: 600; line-height: 1.55; overflow-wrap: anywhere; }
.source-time, .source-meta, .source-warning { margin: 5px 0; color: var(--muted); font-size: 11px; line-height: 1.65; overflow-wrap: anywhere; }
.source-warning { color: #88652f; }.source-reasons { display: flex; flex-wrap: wrap; gap: 5px 9px; margin-top: 8px; }.source-reasons span { padding: 2px 6px; border-radius: 3px; background: var(--surface-soft); color: var(--muted); font-size: 10px; }
.source-actions { display: grid; justify-items: end; gap: 8px; flex: 0 0 auto; padding-top: 4px; }
@media (max-width: 620px) { .today-source-row { flex-direction: column; gap: 8px; }.source-actions { display: flex; flex-wrap: wrap; justify-content: start; } }
</style>
