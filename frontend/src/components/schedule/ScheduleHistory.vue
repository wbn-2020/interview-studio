<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { InterviewEvent, ScheduleHistoryPage } from '../../api/schedule'
import { useWorkspaceStore } from '../../state/workspace'
import ScheduleReadout from './ScheduleReadout.vue'

const props = defineProps<{ id: string; currentRevision: number }>()
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const before = ref<number>()
const cursors = ref<(number | undefined)[]>([])
const selectedRevision = ref(props.currentRevision)
const comparisonRevision = ref<number>()
const actionLabels: Record<string, string> = { CREATED: '创建', UPDATED: '编辑', RESCHEDULED: '改期', CANCELLED: '取消', COMPLETED: '标记完成', ACKNOWLEDGED: '确认提醒' }
const list = useQuery({ queryKey: computed(() => ['schedule', store.sessionEpoch, 'history', props.id, before.value]), enabled: computed(() => store.sessionActive), queryFn: () => read<ScheduleHistoryPage>(`/schedule/interviews/${props.id}/history?limit=20${before.value !== undefined ? `&before=${before.value}` : ''}`) })
const selected = useQuery({ queryKey: computed(() => ['schedule', store.sessionEpoch, 'snapshot', props.id, selectedRevision.value]), enabled: computed(() => store.sessionActive), queryFn: () => read<InterviewEvent>(`/schedule/interviews/${props.id}/history/${selectedRevision.value}`) })
const comparison = useQuery({ queryKey: computed(() => ['schedule', store.sessionEpoch, 'snapshot', props.id, comparisonRevision.value]), enabled: computed(() => store.sessionActive && comparisonRevision.value !== undefined), queryFn: () => read<InterviewEvent>(`/schedule/interviews/${props.id}/history/${comparisonRevision.value}`) })
watch([() => route.query.version, () => props.currentRevision], ([version, current]) => {
  const parsed = typeof version === 'string' && /^\d+$/.test(version) ? Number(version) : current
  selectedRevision.value = Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : current
}, { immediate: true })
function choose(revision: number) { void router.push({ name: 'schedule', query: { ...route.query, item: props.id, version: String(revision) } }) }
function older() { if (list.data.value?.nextCursor != null) { cursors.value.push(before.value); before.value = list.data.value.nextCursor } }
const changes = computed(() => {
  if (!selected.data.value || !comparison.data.value) return []
  const labels: [keyof InterviewEvent, string][] = [['title', '标题'], ['targetVersionId', '目标版本'], ['round', '轮次'], ['time', '时间与时区'], ['remindBeforeMinutes', '提醒提前量'], ['reminderAcknowledgedAt', '提醒确认'], ['status', '状态'], ['location', '地点'], ['meetingUrl', '会议链接'], ['notes', '备注'], ['materials', '材料版本']]
  return labels.filter(([key]) => JSON.stringify(selected.data.value![key]) !== JSON.stringify(comparison.data.value![key])).map(([, label]) => label)
})
</script>

<template>
  <section class="schedule-history">
    <p class="field-help">历史只读，保留当时的时间、材料和处理记录。查看或对照不会替换编辑中的输入。</p>
    <p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重试</NButton></p>
    <p v-if="list.isPending.value" class="field-help" role="status">正在读取历史…</p>
    <div v-for="entry in list.data.value?.items" :key="entry.revision" class="history-row"><button class="text-link" @click="choose(entry.revision)">修订 {{ entry.revision }} · {{ actionLabels[entry.action] ?? entry.action }}<small>{{ new Date(entry.createdAt).toLocaleString('zh-CN') }}{{ entry.reason ? ` · ${entry.reason}` : '' }}</small></button><NButton size="small" text :disabled="entry.revision === selectedRevision" @click="comparisonRevision = entry.revision">设为对照</NButton></div>
    <div class="row-actions"><NButton size="small" :disabled="!cursors.length" @click="before = cursors.pop()">较新</NButton><NButton size="small" :disabled="list.data.value?.nextCursor == null" @click="older">更早</NButton><NButton v-if="comparisonRevision !== undefined" size="small" @click="comparisonRevision = undefined">关闭对照</NButton></div>
    <p v-if="selected.error.value || comparison.error.value" class="notice error" role="alert">{{ errorText(selected.error.value ?? comparison.error.value) }}<NButton text @click="selected.refetch(); comparisonRevision !== undefined && comparison.refetch()">重试读取</NButton></p>
    <p v-if="selected.isFetching.value || comparison.isFetching.value" class="field-help" role="status">正在读取快照…</p>
    <p v-if="selected.data.value && comparison.data.value && comparisonRevision !== undefined" class="notice">{{ changes.length ? `变化内容：${changes.join('、')}` : '这两个快照的安排内容相同。' }}</p>
    <div class="snapshot-columns" :class="{ paired: comparisonRevision !== undefined }"><article v-if="selected.data.value"><p class="section-label">所选修订 {{ selectedRevision }}</p><ScheduleReadout :event="selected.data.value" /></article><article v-if="comparison.data.value && comparisonRevision !== undefined"><p class="section-label">对照修订 {{ comparisonRevision }}</p><ScheduleReadout :event="comparison.data.value" /></article></div>
  </section>
</template>

<style scoped>
.schedule-history { display: grid; gap: 13px; }
.history-row { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--line); }
.history-row > button:first-child { min-width: 0; text-align: left; overflow-wrap: anywhere; }
.history-row small { display: block; margin-top: 4px; color: var(--muted); font-size: 11px; }
.snapshot-columns { display: grid; gap: 24px; }
.snapshot-columns.paired { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.snapshot-columns > article { min-width: 0; }
.snapshot-columns .section-label { margin-bottom: 13px; }
@media (max-width: 1100px) { .snapshot-columns.paired { grid-template-columns: 1fr; } }
</style>
