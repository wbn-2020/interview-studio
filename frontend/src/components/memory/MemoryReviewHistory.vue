<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import { ApiError, read } from '../../api/client'
import { memoryIdValid } from '../../api/memory'
import type { MemoryImpactPage, MemoryScheduleHistoryPage, MemorySourceSnapshot } from '../../api/memoryReview'
import { memoryImpactPageValid, memoryScheduleHistoryPageValid, memoryScheduleChangeKindLabels as scheduleLabels, memoryReviewErrorText as errorText } from '../../api/memoryReview'
import { queryClient } from '../../api/queryClient'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ id: string; disabled?: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter(), active = ref(true)
const tab = ref<'source' | 'schedule'>('source')
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && memoryIdValid(props.id))
const sourceBefore = ref<number>(), sourceCursors = ref<(number | undefined)[]>([])
const scheduleBefore = ref<number>(), scheduleCursors = ref<(number | undefined)[]>([])
const sourcePage = shallowRef<MemoryImpactPage>(), schedulePage = shallowRef<MemoryScheduleHistoryPage>()
const sourceKey = computed(() => ['memory', store.sessionEpoch, 'impacts', props.id, sourceBefore.value])
const scheduleKey = computed(() => ['memory', store.sessionEpoch, 'schedule-history', props.id, scheduleBefore.value])
let alive = true, generation = 0
watch([readable, () => store.sessionEpoch, () => props.id], () => { generation++; sourcePage.value = undefined; schedulePage.value = undefined }, { flush: 'sync' })
watch(sourceBefore, () => { sourcePage.value = undefined }, { flush: 'sync' })
watch(scheduleBefore, () => { schedulePage.value = undefined }, { flush: 'sync' })
const source = useQuery({ queryKey: sourceKey, enabled: computed(() => readable.value && tab.value === 'source'), staleTime: 0, queryFn: async () => {
  const id = props.id, epoch = store.sessionEpoch, stamp = generation, before = sourceBefore.value
  const value = await read<MemoryImpactPage>(`/memory/cards/${id}/impacts?limit=10${before === undefined ? '' : `&before=${before}`}`)
  if (!alive || !readable.value || id !== props.id || epoch !== store.sessionEpoch || stamp !== generation || before !== sourceBefore.value) throw contextChanged()
  if (!memoryImpactPageValid(value, id, before, 10)) throw invalid('来源历史格式或序号不一致，请重新读取。')
  return value
} })
const schedule = useQuery({ queryKey: scheduleKey, enabled: computed(() => readable.value && tab.value === 'schedule'), staleTime: 0, queryFn: async () => {
  const id = props.id, epoch = store.sessionEpoch, stamp = generation, before = scheduleBefore.value
  let value: MemoryScheduleHistoryPage
  try { value = await read<MemoryScheduleHistoryPage>(`/memory/cards/${id}/schedule/history?limit=10${before === undefined ? '' : `&before=${before}`}`) }
  catch (error) {
    if (!(error instanceof ApiError) || error.status !== 404 || error.code !== 'MEMORY_SCHEDULE_NOT_FOUND') throw error
    value = { items: [], nextBefore: null }
  }
  if (!alive || !readable.value || id !== props.id || epoch !== store.sessionEpoch || stamp !== generation || before !== scheduleBefore.value) throw contextChanged()
  if (!memoryScheduleHistoryPageValid(value, id, before, 10)) throw invalid('排程历史格式或序号不一致，请重新读取。')
  return value
} })
watch([source.data, source.dataUpdatedAt, sourceKey, readable], () => {
  if (!readable.value) return
  const value = queryClient.getQueryData<MemoryImpactPage>(sourceKey.value)
  if (value && memoryImpactPageValid(value, props.id, sourceBefore.value, 10)) sourcePage.value = value
}, { immediate: true })
watch([schedule.data, schedule.dataUpdatedAt, scheduleKey, readable], () => {
  if (!readable.value) return
  const value = queryClient.getQueryData<MemoryScheduleHistoryPage>(scheduleKey.value)
  if (value && memoryScheduleHistoryPageValid(value, props.id, scheduleBefore.value, 10)) schedulePage.value = value
}, { immediate: true })
watch([() => store.sessionEpoch, () => props.id], () => { sourceBefore.value = undefined; sourceCursors.value = []; scheduleBefore.value = undefined; scheduleCursors.value = [] })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++ })

const impactLabels = { BASELINE_REVIEW_REQUIRED: '部署基线需复核', SOURCE_BODY_CHANGED: '来源已发布正文变化', SOURCE_UPSTREAM_CHANGED: '上游采用来源待复核', SOURCE_ARCHIVED: '来源归档', SOURCE_RESTORED: '来源恢复', CARD_BASIS_CHANGED: '卡片依据变化', CARD_ARCHIVED: '卡片归档', CARD_RESTORED: '卡片恢复' }
const sourceLabels = { CURRENT: '当前发布版', SUPERSEDED: '已有新版本', ARCHIVED: '已归档', UNAVAILABLE: '不可用' }
const phaseLabels = { LEARNING: '学习', REVIEW: '复习', RELEARNING: '重新学习' }
function contextChanged() { return new ApiError(0, 'MEMORY_HISTORY_CHANGED', '卡片或访问条件已变化，请重新读取。') }
function invalid(message: string) { return new ApiError(502, 'MEMORY_REVIEW_HISTORY_INVALID', message) }
function time(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN') : '未设置' }
function sourceDescription(value: MemorySourceSnapshot) { return `${value.kind === 'NOTE' ? '笔记' : value.kind === 'CONCEPT' ? '概念' : '知识来源'} ${value.itemId.slice(0, 8)}${value.versionNumber === null ? '' : ` · v${value.versionNumber}`} · ${sourceLabels[value.state]}${value.bodyChanged ? ' · 正文不同' : ''}${value.upstreamReviewRequired ? ' · 上游采用来源待复核' : ''}` }
function openVersion(versionId: string) { if (readable.value && memoryIdValid(versionId)) void router.push({ name: 'memory-cards', query: { item: props.id, versionId } }) }
function openSource(itemId: string, versionId: string) { if (readable.value && memoryIdValid(itemId) && memoryIdValid(versionId)) void router.push({ name: 'knowledge', query: { item: itemId, version: versionId } }) }
function olderSource() { if (readable.value && !source.isFetching.value && !source.error.value && sourcePage.value?.nextBefore != null) { sourceCursors.value.push(sourceBefore.value); sourceBefore.value = sourcePage.value.nextBefore } }
function olderSchedule() { if (readable.value && !schedule.isFetching.value && !schedule.error.value && schedulePage.value?.nextBefore != null) { scheduleCursors.value.push(scheduleBefore.value); scheduleBefore.value = schedulePage.value.nextBefore } }
</script>

<template>
  <section class="review-history" aria-label="来源与排程操作历史">
    <header><h3>操作历史</h3><p class="field-help">记录每次来源影响、人工复核和排程调整。历史按当时状态保存，浏览不会替换卡片编辑草稿。</p></header>
    <p v-if="!readable" class="notice" role="status">资料访问恢复后可查看历史。</p>
    <template v-else>
      <div class="history-tabs" role="group" aria-label="选择历史类别"><button :aria-pressed="tab === 'source'" @click="tab = 'source'">来源与复核</button><button :aria-pressed="tab === 'schedule'" @click="tab = 'schedule'">排程调整</button></div>
      <div v-if="tab === 'source'" class="history-section">
        <NButton text size="small" :disabled="source.isFetching.value" @click="source.refetch()">重读来源历史</NButton>
        <p v-if="source.isPending.value" role="status">正在读取来源历史…</p><p v-if="source.error.value" class="notice error" role="alert">{{ errorText(source.error.value) }}</p>
        <p v-if="sourcePage && !sourcePage.items.length && !source.error.value" class="field-help">本页没有来源影响或复核决定。</p>
        <ol class="history-entries"><li v-for="entry in sourcePage?.items" :key="entry.sequence">
          <template v-if="entry.kind === 'IMPACT' && entry.impact"><div class="entry-heading"><strong>{{ impactLabels[entry.impact.reason] }}</strong><small>记录 {{ entry.sequence }} · {{ time(entry.impact.createdAt) }}</small></div><p class="field-help history-identity">历史事件 {{ entry.impact.id }}</p><p class="field-help">{{ entry.impact.actor.kind === 'OWNER' ? '用户操作触发' : '系统维护记录' }} · 门禁修订 {{ entry.impact.gateRevision }} · {{ entry.impact.reviewRequired ? '影响后仍需复核' : '影响后无需复核' }}</p><div v-if="entry.impact.source" class="source-change"><p>{{ entry.impact.source.kind === 'NOTE' ? '笔记' : '概念' }} {{ entry.impact.source.itemId.slice(0, 8) }} · {{ entry.impact.source.previousStatus === 'ACTIVE' ? '有效' : '归档' }} → {{ entry.impact.source.currentStatus === 'ACTIVE' ? '有效' : '归档' }}</p><div class="row-actions"><NButton text size="small" @click="openSource(entry.impact.source.itemId, entry.impact.source.previousVersionId)">变更前发布版</NButton><NButton text size="small" @click="openSource(entry.impact.source.itemId, entry.impact.source.currentVersionId)">变更后发布版</NButton></div><details><summary>当时依赖的确切版本</summary><ul><li v-for="versionId in entry.impact.source.referencedVersionIds" :key="versionId"><NButton text size="small" @click="openSource(entry.impact.source!.itemId, versionId)">{{ versionId }}</NButton></li></ul></details></div><NButton text size="small" @click="openVersion(entry.impact.cardVersionId)">查看当时卡片版本</NButton></template>
          <template v-else-if="entry.kind === 'SOURCE_REVIEW' && entry.decision"><div class="entry-heading"><strong>人工来源复核</strong><small>记录 {{ entry.sequence }} · {{ time(entry.decision.createdAt) }}</small></div><p class="field-help history-identity">历史事件 {{ entry.decision.id }}</p><p class="reason">{{ entry.decision.reason }}</p><p class="field-help">门禁修订 {{ entry.decision.reviewedGateRevision }} → {{ entry.decision.resultingGateRevision }} · 卡片修订 {{ entry.decision.cardRevision }}。本次确认保留在历史中，不代替后续变化的复核。</p><details><summary>确认时的来源快照</summary><ul class="snapshot-list"><li v-for="item in entry.decision.sources" :key="`${item.itemId}/${item.versionId}`"><span>{{ sourceDescription(item) }}</span><NButton text size="small" @click="openSource(item.itemId, item.versionId)">查看确切来源</NButton></li></ul></details><NButton text size="small" @click="openVersion(entry.decision.cardVersionId)">查看确认的卡片版本</NButton></template>
          <p v-if="entry.kind === 'IMPACT' && entry.impact?.upstreamImpactId" class="field-help history-identity">上游采用影响 {{ entry.impact.upstreamImpactId }}。此记录提示历史采用来源待核对，不表示卡片所引知识正文已经改变。</p>
        </li></ol>
        <div class="row-actions"><NButton size="small" :disabled="!sourceCursors.length || source.isFetching.value" @click="sourceBefore = sourceCursors.pop()">较新记录</NButton><NButton size="small" :disabled="sourcePage?.nextBefore == null || source.isFetching.value || !!source.error.value" @click="olderSource">更早记录</NButton></div>
      </div>
      <div v-else class="history-section">
        <NButton text size="small" :disabled="schedule.isFetching.value" @click="schedule.refetch()">重读排程历史</NButton>
        <p v-if="schedule.isPending.value" role="status">正在读取排程历史…</p><p v-if="schedule.error.value" class="notice error" role="alert">{{ errorText(schedule.error.value) }}</p>
        <p v-if="schedulePage && !schedulePage.items.length && !schedule.error.value" class="field-help">本页没有排程调整记录。</p>
        <ol class="history-entries"><li v-for="entry in schedulePage?.items" :key="entry.sequence"><div class="entry-heading"><strong>{{ scheduleLabels[entry.kind] }}</strong><small>记录 {{ entry.sequence }} · {{ time(entry.createdAt) }}</small></div><p v-if="entry.reason" class="reason">{{ entry.reason }}</p><p class="field-help">{{ entry.actor.kind === 'OWNER' ? '用户操作' : '系统维护' }} · 卡片修订 {{ entry.cardRevision }} · 门禁修订 {{ entry.gateRevision }}</p><div class="table-scroll"><table><caption class="sr-only">本次排程调整的前后状态</caption><thead><tr><th scope="col">项目</th><th scope="col">调整前</th><th scope="col">调整后</th></tr></thead><tbody><tr><th scope="row">状态</th><td>{{ entry.before ? entry.before.status === 'ACTIVE' ? '启用' : '暂停' : '尚无排程' }}</td><td>{{ entry.after.status === 'ACTIVE' ? '启用' : '暂停' }}</td></tr><tr><th scope="row">算法建议到期</th><td>{{ time(entry.before?.suggestedDueAt ?? null) }}</td><td>{{ time(entry.after.suggestedDueAt) }}</td></tr><tr><th scope="row">人工延后至</th><td>{{ time(entry.before?.deferUntil ?? null) }}</td><td>{{ time(entry.after.deferUntil) }}</td></tr><tr><th scope="row">实际到期</th><td>{{ time(entry.before?.effectiveDueAt ?? null) }}</td><td>{{ time(entry.after.effectiveDueAt) }}</td></tr></tbody></table></div><details><summary>周期与算法记录</summary><dl><dt>周期</dt><dd>{{ entry.before?.cycleId ?? '首次建立' }} → {{ entry.after.cycleId }}</dd><dt>排程修订</dt><dd>{{ entry.before?.revision ?? '尚无' }} → {{ entry.after.revision }}</dd><dt>阶段</dt><dd>{{ phaseLabels[entry.after.state.phase] }}；上次实际评分：{{ time(entry.after.state.lastReviewAt) }}</dd><dt>实现及参数</dt><dd>{{ entry.after.algorithmVersion }}<br />{{ entry.after.parameterVersion }}</dd><template v-if="entry.impactId"><dt>对应来源影响</dt><dd>{{ entry.impactId }}</dd></template></dl></details><NButton text size="small" @click="openVersion(entry.cardVersionId)">查看当时卡片版本</NButton></li></ol>
        <div class="row-actions"><NButton size="small" :disabled="!scheduleCursors.length || schedule.isFetching.value" @click="scheduleBefore = scheduleCursors.pop()">较新记录</NButton><NButton size="small" :disabled="schedulePage?.nextBefore == null || schedule.isFetching.value || !!schedule.error.value" @click="olderSchedule">更早记录</NButton></div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.review-history, .history-section { display: grid; gap: 16px; min-width: 0; }.review-history { padding-top: 22px; border-top: 1px solid var(--line); }header { display: grid; gap: 8px; }h3, p { margin: 0; }h3 { font-size: 16px; }.history-tabs { display: flex; gap: 20px; border-bottom: 1px solid var(--line); }.history-tabs button { padding: 9px 0; border-bottom: 2px solid transparent; font-size: 13px; color: var(--muted); }.history-tabs button[aria-pressed=true] { border-color: var(--brand); color: var(--brand-strong); font-weight: 650; }
.history-section > .n-button { justify-self: start; }.history-entries { display: grid; list-style: none; padding: 0; margin: 0; }.history-entries > li { display: grid; gap: 11px; min-width: 0; padding: 18px 0; border-top: 1px solid var(--line); }.entry-heading, .row-actions { display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: baseline; }.entry-heading { justify-content: space-between; }.entry-heading strong { font-size: 14px; }small { font-size: 12px; color: var(--muted); }.reason { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; max-width: 75ch; }.source-change { display: grid; gap: 8px; font-size: 13px; }details { font-size: 12px; min-width: 0; }summary { cursor: pointer; padding: 6px 0; }summary:focus-visible, .history-tabs button:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }.snapshot-list { display: grid; gap: 8px; padding-left: 18px; }.snapshot-list li { overflow-wrap: anywhere; }.table-scroll { overflow-x: auto; }table { width: 100%; border-collapse: collapse; font-size: 12px; }th, td { text-align: left; vertical-align: top; padding: 9px 10px; border-bottom: 1px solid var(--line); }th { font-weight: 550; }td { min-width: 140px; }dl { display: grid; gap: 7px; }dt { color: var(--muted); }dd { margin: 0 0 7px; overflow-wrap: anywhere; }.history-entries > li > .n-button { justify-self: start; }.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.history-identity { overflow-wrap: anywhere; }
</style>
