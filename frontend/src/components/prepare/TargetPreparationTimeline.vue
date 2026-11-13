<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../../api/client'
import type { PreparationTask, PreparationTaskPage } from '../../api/preparationTask'
import { preparationTaskStatusLabels, validPreparationTaskDate } from '../../api/preparationTask'
import type { EventPage } from '../../api/schedule'
import { scheduleStatusLabels } from '../../api/schedule'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import RIcon from '../../replica/RIcon.vue'

const props = defineProps<{ targetId: string; versionId: string; capturedOn: string | null }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), generation = ref(0)
const readable = computed(() => store.sessionActive && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const anchor = ref(new Date().toISOString().slice(0, 10))
const window = computed(() => {
  const date = props.capturedOn && validPreparationTaskDate(props.capturedOn) ? props.capturedOn : anchor.value
  const start = Date.parse(`${date}T00:00:00Z`)
  return { from: new Date(Math.max(Date.UTC(1000, 0, 1), start - 7 * 86400000)).toISOString(),
    to: new Date(Math.min(Date.UTC(9999, 11, 31), start + 60 * 86400000)).toISOString() }
})
watch([readable, () => store.sessionEpoch, () => props.targetId, () => props.versionId, window,
  () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => generation.value++, { flush: 'sync' })
const query = useQuery({
  queryKey: computed(() => ['preparation', store.sessionEpoch, 'target-timeline', props.targetId, props.versionId, window.value, generation.value]),
  enabled: readable,
  queryFn: async () => {
    const stamp = generation.value, epoch = currentTrainingAccessEpoch(), targetId = props.targetId, versionId = props.versionId, range = window.value
    const live = () => readable.value && stamp === generation.value && epoch === currentTrainingAccessEpoch()
    const tasks: PreparationTask[] = [], seen = new Set<string>()
    let cursor: string | null = null
    // 任务接口按ID分页；有界读取后才按日期排序，不能把首20项伪称全部路线。
    for (let page = 0; page < 5; page++) {
      if (!live()) throw new ApiError(0, 'TARGET_TIMELINE_CHANGED', '目标或资料访问已变化。')
      const params = new URLSearchParams({ targetId, limit: '20' })
      if (cursor) params.set('cursor', cursor)
      const value = await read<PreparationTaskPage>(`/prepare/tasks?${params}`)
      tasks.push(...value.items.filter(task => task.target?.itemId === targetId && task.target.versionId === versionId))
      cursor = value.nextCursor
      if (!cursor) break
      if (seen.has(cursor)) throw new ApiError(502, 'TARGET_TIMELINE_CURSOR', '任务分页游标重复，请重新读取。')
      seen.add(cursor)
    }
    const params = new URLSearchParams({ targetId, from: range.from, to: range.to, limit: '50' })
    const events = await read<EventPage>(`/schedule/interviews?${params}`)
    if (!live()) throw new ApiError(0, 'TARGET_TIMELINE_CHANGED', '目标或资料访问已变化。')
    return { stamp, tasks: [...new Map(tasks.map(task => [task.id, task])).values()],
      events: events.items.filter(event => event.targetId === targetId && event.targetVersionId === versionId),
      partial: Boolean(cursor || events.nextCursor) }
  },
})
const data = computed(() => readable.value && query.data.value?.stamp === generation.value ? query.data.value : undefined)
const rows = computed(() => [
  ...(data.value?.tasks ?? []).filter(task => task.status !== 'CANCELLED').map(task => ({
    key: `task:${task.id}`, day: task.content.plannedDate, title: task.content.title, state: preparationTaskStatusLabels[task.status],
    status: task.status === 'DONE' ? 'done' : task.status === 'IN_PROGRESS' ? 'active' : 'todo',
    to: { name: 'preparation-tasks', query: { item: task.id } },
  })),
  ...(data.value?.events ?? []).filter(event => event.status !== 'CANCELLED').map(event => ({
    key: `event:${event.id}`, day: event.time.localStart.slice(0, 10), title: event.round || event.title,
    state: scheduleStatusLabels[event.status], status: event.status === 'COMPLETED' ? 'done' : 'todo',
    to: { name: 'schedule', query: { item: event.id, month: event.time.localStart.slice(0, 7), day: event.time.localStart.slice(0, 10), zone: event.time.zoneId } },
  })),
].sort((a, b) => (a.day ?? '9999-99-99').localeCompare(b.day ?? '9999-99-99') || a.key.localeCompare(b.key)))
const rangeLabel = computed(() => `任务为当前读取状态；仅展示固定关联此目标版本的记录。面试范围 ${window.value.from.slice(0, 10)} 至 ${window.value.to.slice(0, 10)}。`)
</script>

<template>
  <section class="target-timeline">
    <h3 :title="rangeLabel">准备路线</h3>
    <p v-if="!readable" class="field-help">请恢复资料访问后查看路线。</p>
    <p v-else-if="query.isFetching.value && !data" class="field-help" role="status">正在读取目标任务与面试安排…</p>
    <button v-if="readable && query.error.value" class="timeline-error" @click="query.refetch()">{{ errorText(query.error.value) }} · 重试</button>
    <ol v-if="data" :aria-label="rangeLabel"><li v-for="row in rows.slice(0, 6)" :key="row.key" :class="row.status"><RouterLink :to="row.to"><i><RIcon v-if="row.status === 'done'" name="check" :size="23" /><b v-else-if="row.status === 'active'" /></i><span><strong><time>{{ row.day ? row.day.slice(5).replace('-', '.') : '待安排' }}</time>{{ row.title }}</strong><small>{{ row.state }}</small></span></RouterLink></li></ol>
    <p v-if="data && !rows.length" class="field-help">此目标版本尚无准备任务或查询范围内的面试安排。</p>
    <p v-if="data?.partial" class="field-help">路线为已读任务和面试页，仍有后续记录。</p>
    <RouterLink v-if="data && (rows.length > 6 || data.partial)" class="timeline-all" :to="{ name: 'preparation-tasks' }">查看任务<RIcon name="arrow-right" :size="18" /></RouterLink>
  </section>
</template>

<style scoped>
.target-timeline { min-height: 495px; }.target-timeline h3 { font-size: 25px; margin: 0 0 25px; }.target-timeline ol { margin: 0; padding: 0; list-style: none; }.target-timeline li { position: relative; min-height: 103px; }.target-timeline li:last-child { min-height: 58px; }.target-timeline li:not(:last-child)::after { content: ''; position: absolute; top: 43px; bottom: 8px; left: 16px; width: 2px; background: #67635b; }.target-timeline li.done::after { background: #689b81; }.target-timeline a { display: flex; align-items: start; gap: 19px; text-decoration: none; }.target-timeline i { display: grid; place-items: center; width: 33px; height: 33px; flex: none; border-radius: 50%; border: 4px solid #77746b; font-style: normal; }.target-timeline .done i { color: #183b2b; border: 0; background: #9ed8bb; }.target-timeline .active i { border-color: #f4b77c; }.target-timeline .active i b { display: block; width: 14px; height: 14px; border-radius: 50%; background: #f4b77c; }.target-timeline li a > span { min-width: 0; }.target-timeline strong { display: flex; align-items: baseline; gap: 10px; font-size: 20px; line-height: 1.6; font-weight: 500; overflow-wrap: anywhere; }.target-timeline time { flex: none; font-size: 19px; }.target-timeline small { display: block; color: var(--muted); font-size: 19px; line-height: 1.7; margin-top: 3px; }.target-timeline .active small { color: #f6ad68; }.target-timeline .timeline-all { display: flex; gap: 10px; color: var(--muted); align-items: center; font-size: 15px; }.timeline-error { color: var(--danger); font-size: 14px; text-align: left; }
</style>
