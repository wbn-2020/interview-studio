<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, read } from '../../api/client'
import { memoryIdValid } from '../../api/memory'
import type { MemoryAttemptDetail, MemoryAttemptPage, MemoryAttemptEventPage } from '../../api/memoryAttempt'
import { memoryAttemptDetailValid, memoryAttemptPageValid, memoryAttemptEventPageValid, memoryAttemptErrorText as errorText,
  memoryAttemptModeLabels, memoryAttemptStateLabels, memoryRatingLabels, memoryAttemptEventKindLabels, memoryInvalidationReasonLabels } from '../../api/memoryAttempt'
import { queryClient } from '../../api/queryClient'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MarkdownPreview from '../MarkdownPreview.vue'

const props = defineProps<{ cardId: string; disabled?: boolean }>()
const emit = defineEmits<{ select: [attemptId: string] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), active = ref(true)
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && memoryIdValid(props.cardId))
const before = ref<number>(), cursors = ref<(number | undefined)[]>([]), page = shallowRef<MemoryAttemptPage>()
const selected = ref<string>(), eventBefore = ref<number>(), eventCursors = ref<(number | undefined)[]>([])
const eventPage = shallowRef<MemoryAttemptEventPage>(), eventDetail = shallowRef<MemoryAttemptDetail>()
const key = computed(() => ['memory', store.sessionEpoch, 'recall-history', props.cardId, before.value])
const eventKey = computed(() => ['memory', store.sessionEpoch, 'recall-events', props.cardId, selected.value, eventBefore.value])
let alive = true, generation = 0
watch([readable, () => store.sessionEpoch, () => props.cardId], () => { generation++; page.value = undefined; eventPage.value = undefined; eventDetail.value = undefined }, { flush: 'sync' })
watch(before, () => { page.value = undefined; selected.value = undefined }, { flush: 'sync' })
watch(selected, () => { eventBefore.value = undefined; eventCursors.value = []; eventPage.value = undefined; eventDetail.value = undefined }, { flush: 'sync' })
watch(eventBefore, () => { eventPage.value = undefined; eventDetail.value = undefined }, { flush: 'sync' })
watch([() => store.sessionEpoch, () => props.cardId], () => { before.value = undefined; cursors.value = []; selected.value = undefined })
const attempts = useQuery({ queryKey: key, enabled: readable, staleTime: 0, queryFn: async () => {
  const cardId = props.cardId, epoch = store.sessionEpoch, stamp = generation, cursor = before.value
  const value = await read<MemoryAttemptPage>(`/memory/cards/${cardId}/attempts?limit=10${cursor === undefined ? '' : `&before=${cursor}`}`)
  requireCurrent(cardId, epoch, stamp)
  if (cursor !== before.value) throw contextChanged()
  if (!memoryAttemptPageValid(value, cardId, cursor, 10)) throw invalid('回忆历史的身份、顺序或状态异常，请重新读取。')
  return value
} })
const events = useQuery({ queryKey: eventKey, enabled: computed(() => readable.value && memoryIdValid(selected.value)), staleTime: 0, queryFn: async () => {
  const cardId = props.cardId, id = selected.value!, epoch = store.sessionEpoch, stamp = generation, cursor = eventBefore.value
  const [detail, value] = await Promise.all([
    read<MemoryAttemptDetail>(`/memory/attempts/${id}`),
    read<MemoryAttemptEventPage>(`/memory/attempts/${id}/events?limit=10${cursor === undefined ? '' : `&before=${cursor}`}`),
  ])
  requireCurrent(cardId, epoch, stamp)
  if (id !== selected.value || cursor !== eventBefore.value) throw contextChanged()
  if (!memoryAttemptDetailValid(detail, id, cardId) || !memoryAttemptEventPageValid(value, id, cursor, 10, detail.attempt)) {
    throw invalid('事件与这次回忆的固定版本或最新状态不一致，请重新读取。')
  }
  return { detail, page: value }
} })
watch([attempts.data, attempts.dataUpdatedAt, key, readable], () => {
  if (!readable.value) return
  const value = queryClient.getQueryData<MemoryAttemptPage>(key.value)
  if (value && memoryAttemptPageValid(value, props.cardId, before.value, 10)) page.value = value
}, { immediate: true })
watch([events.data, events.dataUpdatedAt, eventKey, readable], () => {
  if (!readable.value || !selected.value) return
  const value = queryClient.getQueryData<{ detail: MemoryAttemptDetail; page: MemoryAttemptEventPage }>(eventKey.value)
  if (value && memoryAttemptDetailValid(value.detail, selected.value, props.cardId)
    && memoryAttemptEventPageValid(value.page, selected.value, eventBefore.value, 10, value.detail.attempt)) {
    eventPage.value = value.page; eventDetail.value = value.detail
  }
}, { immediate: true })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++ })

function contextChanged() { return new ApiError(0, 'MEMORY_HISTORY_CHANGED', '卡片或访问条件已变化，请重新读取历史。') }
function invalid(message: string) { return new ApiError(502, 'MEMORY_ATTEMPT_HISTORY_INVALID', message) }
function requireCurrent(id: string, epoch: number, stamp: number) { if (!alive || !readable.value || props.cardId !== id || epoch !== store.sessionEpoch || stamp !== generation) throw contextChanged() }
function time(value: string | null, zoneId?: string) {
  if (!value) return '尚未发生'
  try { return new Date(value).toLocaleString('zh-CN', zoneId ? { timeZone: zoneId } : undefined) } catch { return value }
}
function select(id: string) { if (readable.value && memoryIdValid(id)) emit('select', id) }
function toggleEvents(id: string) { if (readable.value && memoryIdValid(id)) selected.value = selected.value === id ? undefined : id }
function older() { if (readable.value && !attempts.isFetching.value && !attempts.error.value && page.value?.nextBefore != null) { cursors.value.push(before.value); before.value = page.value.nextBefore } }
function olderEvents() { if (readable.value && !events.isFetching.value && !events.error.value && eventPage.value?.nextBefore != null) { eventCursors.value.push(eventBefore.value); eventBefore.value = eventPage.value.nextBefore } }
</script>

<template>
  <section class="attempt-history" aria-label="本卡回忆与事件历史">
    <header><div><h3>这张卡的回忆历史</h3><p class="field-help">每次尝试保留当时题面、回答和自评。来源或排程变化不会改写已结束记录。</p></div><NButton text size="small" :disabled="!readable || attempts.isFetching.value" @click="attempts.refetch()">重新读取</NButton></header>
    <p v-if="!readable" class="notice" role="status">恢复资料访问后可查看历史。</p>
    <template v-else>
      <p v-if="attempts.isPending.value" role="status">正在读取回忆历史…</p><p v-if="attempts.error.value" class="notice error" role="alert">{{ errorText(attempts.error.value) }}</p>
      <p v-if="page && !page.items.length && !attempts.error.value" class="field-help">当前页没有回忆记录。</p>
      <ol class="attempt-entries"><li v-for="item in page?.items" :key="item.id">
        <div class="entry-heading"><strong>第 {{ item.sequence }} 次 · {{ memoryAttemptStateLabels[item.state] }}</strong><small>{{ time(item.startedAt) }}</small></div>
        <p class="field-help">{{ memoryAttemptModeLabels[item.mode] }} · v{{ item.cardVersionNumber }} · {{ item.countRegular ? '计入常规排程' : '额外练习，不改常规排程' }}<template v-if="item.rating"> · 自评：{{ memoryRatingLabels[item.rating] }}</template></p>
        <p v-if="item.invalidationReason" class="field-help">失效原因：{{ memoryInvalidationReasonLabels[item.invalidationReason] }}</p>
        <div class="row-actions"><NButton size="small" @click="select(item.id)">{{ item.state === 'THINKING' || item.state === 'REVEALED' ? '恢复这次回忆' : '查看固定题面与回答' }}</NButton><NButton text size="small" :aria-expanded="selected === item.id" @click="toggleEvents(item.id)">{{ selected === item.id ? '收起事件' : '查看事件' }}</NButton></div>
        <section v-if="selected === item.id" class="events" aria-label="本次回忆事件">
          <p v-if="events.isPending.value" role="status">正在读取事件…</p><p v-if="events.error.value" class="notice error" role="alert">{{ errorText(events.error.value) }}<NButton text :disabled="events.isFetching.value" @click="events.refetch()">重新读取事件</NButton></p>
          <ol class="event-entries"><li v-for="event in eventPage?.items" :key="event.id">
            <div class="entry-heading"><strong>{{ memoryAttemptEventKindLabels[event.kind] }}</strong><small>事件 {{ event.sequence }} · {{ time(event.occurredAt, eventDetail?.attempt.start.zoneId) }}</small></div>
            <p class="field-help">{{ event.fromState ? memoryAttemptStateLabels[event.fromState] : '尚未开始' }} → {{ memoryAttemptStateLabels[event.toState] }} · 尝试修订 {{ event.attemptRevision }}</p>
            <template v-if="event.started"><p class="field-help">固定卡片 v{{ event.started.snapshot.cardVersionNumber }} · 卡片修订 {{ event.started.snapshot.cardRevision }} · 来源修订 {{ event.started.snapshot.gateRevision }}。</p><p v-if="event.started.snapshot.admission?.continueBeyondBudget" class="field-help">用户在开始前明确允许超过当日建议数量。</p></template>
            <template v-if="event.revealed"><p class="field-help">揭示时固定的{{ event.revealed.responseMode === 'THOUGHT' ? '心中回忆声明，没有书面答案。' : '书面回答：' }}</p><MarkdownPreview v-if="event.revealed.responseMode === 'WRITTEN'" :value="event.revealed.response" /></template>
            <template v-if="event.rated"><p>用户自评：{{ memoryRatingLabels[event.rated.rating] }}</p><p class="field-help">归属日 {{ event.rated.day.localDate }} · {{ event.rated.day.zoneId }}。{{ event.rated.budget ? event.rated.budget.firstForCardToday ? '这张卡当日首次计入建议数量。' : '这张卡当日已计入，不重复占用一个名额。' : '本次是额外练习，不计入每日建议数量。' }}</p><dl v-if="event.rated.schedule"><dt>此前实际到期</dt><dd>{{ time(event.rated.schedule.before.effectiveDueAt, event.rated.day.zoneId) }}</dd><dt>本次安排的下次到期</dt><dd>{{ time(event.rated.schedule.after.effectiveDueAt, event.rated.day.zoneId) }}</dd></dl></template>
            <p v-if="event.skipped" class="reason">跳过理由：{{ event.skipped.reason }}</p><p v-if="event.invalidated">{{ memoryInvalidationReasonLabels[event.invalidated.cause.reason] }}</p>
          </li></ol>
          <div v-if="eventCursors.length || eventPage?.nextBefore != null" class="row-actions"><NButton size="small" :disabled="!eventCursors.length || events.isFetching.value" @click="eventBefore = eventCursors.pop()">较新事件</NButton><NButton size="small" :disabled="eventPage?.nextBefore == null || events.isFetching.value || !!events.error.value" @click="olderEvents">更早事件</NButton></div>
        </section>
      </li></ol>
      <div class="row-actions"><NButton size="small" :disabled="!cursors.length || attempts.isFetching.value" @click="before = cursors.pop()">较新记录</NButton><NButton size="small" :disabled="page?.nextBefore == null || attempts.isFetching.value || !!attempts.error.value" @click="older">更早记录</NButton></div>
    </template>
  </section>
</template>

<style scoped>
.attempt-history, .events { display: grid; gap: 17px; min-width: 0; }header, .entry-heading, .row-actions { display: flex; gap: 10px 16px; flex-wrap: wrap; align-items: baseline; }header, .entry-heading { justify-content: space-between; }header > div { display: grid; gap: 8px; }h3, p, dl { margin: 0; }h3 { font-size: 17px; }.attempt-entries, .event-entries { list-style: none; padding: 0; margin: 0; display: grid; }.attempt-entries > li, .event-entries > li { display: grid; gap: 11px; padding: 18px 0; border-top: 1px solid var(--line); min-width: 0; }.entry-heading strong { font-size: 14px; }small, dt { color: var(--muted); font-size: 12px; }.events { margin-top: 5px; padding: 16px 18px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 6px; }.event-entries > li:first-child { border-top: 0; padding-top: 0; }.events p { font-size: 13px; }.reason { white-space: pre-wrap; overflow-wrap: anywhere; }dl { display: grid; gap: 6px; font-size: 13px; }dd { margin: 0 0 5px; }.events :deep(.markdown-preview) { max-width: 75ch; overflow-wrap: anywhere; }
</style>
