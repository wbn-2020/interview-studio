<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { ReviewAction, ReviewActionSource, ReviewAnswerLink, ReviewRecollectionSource } from '../../api/review'
import { reviewAnswerKey, reviewDate, reviewIdValid } from '../../api/review'
import type { RepracticeProgressPage } from '../../api/repractice'
import { repracticeOutcomeLabels, repracticeProgressLabel, repracticeProgressValid } from '../../api/repractice'
import { recollectionBaselineLabels } from '../../api/recollection'
import { queryClient } from '../../api/queryClient'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import { actionValid } from './reviewActionData'

const props = defineProps<{ actionId: string; disabled?: boolean }>()
const emit = defineEmits<{ open: [id: string] }>()
type ActionProgress = { action: ReviewAction; page: RepracticeProgressPage }
type BaselineGroup = { answer: ReviewAnswerLink | null; recollection: ReviewRecollectionSource | null; sources: ReviewActionSource[] }
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter()
const active = ref(true), current = shallowRef<ActionProgress>()
let alive = true, generation = 0
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && reviewIdValid(props.actionId))
const key = computed(() => ['repractice', store.sessionEpoch, 'action-progress', props.actionId])
const progress = useQuery({ queryKey: key, enabled: readable, queryFn: async () => {
  const id = props.actionId, epoch = store.sessionEpoch, stamp = generation
  const [action, page] = await Promise.all([read<ReviewAction>(`/review/actions/${id}`), read<RepracticeProgressPage>(`/review/actions/${id}/progress`)])
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation || id !== props.actionId) throw new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '来源进度读取条件已变化，请重新读取。')
  if (!actionValid(action) || action.id !== id || !page || !Array.isArray(page.items) || page.items.length !== action.sources.length
    || new Set(page.items.map(item => item.sourceId)).size !== page.items.length
    || page.items.some(item => !action.sources.some(source => source.id === item.sourceId && repracticeProgressValid(item, source)))) throw new ApiError(502, 'REVIEW_PROGRESS_MISMATCH', '动作与来源进度不一致，请重新读取。')
  // 动作与进度分别读取；并发改写时不能把两次读取拼成已确认的当前状态。
  if (page.items.some(item => item.actionId !== id || item.actionRevision !== action.revision
    || !action.sources.some(source => source.id === item.sourceId && source.verification === item.verification && source.tracking === item.tracking))) throw new ApiError(409, 'REVIEW_SOURCE_CHANGED', '动作来源刚刚发生变化，请重读后继续。')
  return { action, page }
} })
const groups = computed(() => {
  const values = new Map<string, BaselineGroup>()
  current.value?.action.sources.forEach(source => {
    const recollection = source.kind === 'RECOLLECTION' ? source.recollection ?? null : null
    const identity = recollection ? `recollection:${recollection.recollectionId}/${recollection.versionId}` : source.answer ? `answer:${reviewAnswerKey(source.answer)}` : ''
    if (!identity) return
    const existing = values.get(identity)
    if (existing) existing.sources.push(source)
    else values.set(identity, { answer: source.answer, recollection, sources: [source] })
  })
  return [...values.entries()]
})
const bySource = computed(() => new Map(current.value?.page.items.map(item => [item.sourceId, item]) ?? []))
watch([readable, () => store.sessionEpoch, () => props.actionId], () => { generation++; current.value = undefined }, { flush: 'sync' })
watch([progress.data, progress.dataUpdatedAt, key, readable], () => {
  if (!alive || !readable.value) return
  const value = queryClient.getQueryData<ActionProgress>(key.value)
  if (!value || value.action.id !== props.actionId || !actionValid(value.action) || !Array.isArray(value.page.items)
    || value.page.items.length !== value.action.sources.length || new Set(value.page.items.map(item => item.sourceId)).size !== value.page.items.length
    || value.page.items.some(item => item.actionId !== value.action.id || item.actionRevision !== value.action.revision
      || !value.action.sources.some(source => source.id === item.sourceId && repracticeProgressValid(item, source)
        && source.verification === item.verification && source.tracking === item.tracking))) return
  current.value = value
}, { immediate: true })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++ })
function canStart(group: BaselineGroup) {
  return readable.value && !progress.isFetching.value && !progress.error.value && current.value?.action.status === 'OPEN'
    && group.sources.some(source => source.tracking === 'TRACKING' && (source.kind !== 'RECOLLECTION' || bySource.value.get(source.id)?.recollectionState === 'CURRENT'))
}
function start(group: BaselineGroup) {
  if (!canStart(group)) return
  if (group.recollection) void router.push({ name: 'repractice', query: { recollectionId: group.recollection.recollectionId, versionId: group.recollection.versionId, actionId: props.actionId } })
  else if (group.answer) void router.push({ name: 'repractice', query: { ...group.answer, actionId: props.actionId } })
}
function compare(id: string) { if (readable.value) void router.push({ name: 'repractice', query: { comparison: id } }) }
</script>

<template>
  <section class="action-progress" aria-label="动作来源复练进度">
    <header><div><p class="section-label">独立验证</p><h3>逐条来源的复练记录</h3></div><NButton text size="small" :disabled="!readable || progress.isFetching.value" @click="progress.refetch()">重读进度</NButton></header>
    <p class="field-help">完成一场训练不会自动关闭动作。每条来源需要结合本次新答，留下自己的判断与依据。</p>
    <p v-if="!readable" class="notice" role="status">当前访问条件限制资料读取，来源进度暂时遮挡。</p>
    <p v-if="progress.isPending.value && readable" role="status">正在读取来源进度…</p>
    <p v-if="progress.error.value && readable" class="notice error" role="alert">{{ errorText(progress.error.value) }}</p>
    <div v-show="readable" :inert="!readable || undefined" class="progress-content">
      <p v-if="current?.action.status === 'MERGED'" class="notice">来源已转入合并后的动作，原动作保留历史。<NButton v-if="current.action.mergedInto" text @click="emit('open', current.action.mergedInto)">查看当前动作</NButton></p>
      <p v-if="groups.length > 1" class="field-help">来源按确切原答或回忆版本分组，每次独立复练选择一组。</p>
      <section v-for="([key, group], index) in groups" :key="key" class="answer-group">
        <header><div><h4>{{ group.recollection ? `回忆 ${index + 1} · v${group.recollection.versionNumber}` : `原答 ${index + 1}` }}</h4><p class="field-help">{{ group.recollection ? recollectionBaselineLabels[group.recollection.baselineKind] : `提交 ${group.answer?.answerId.slice(0, 8)}` }} · {{ group.sources.length }} 条来源</p></div><NButton size="small" :disabled="!canStart(group)" @click="start(group)">{{ group.recollection?.baselineKind === 'NONE' ? '开始空白补练' : '开始独立复练' }}</NButton></header>
        <article v-for="source in group.sources" :key="source.id" class="source-progress">
          <p class="verification">{{ source.verification }}</p>
          <p class="field-help">{{ bySource.get(source.id) ? repracticeProgressLabel(bySource.get(source.id)!) : '进度待读取' }}</p>
          <p v-if="source.tracking === 'DROPPED'" class="field-help">停止原因：{{ source.trackingReason }}。停止跟踪不代表已处理。</p>
          <template v-if="bySource.get(source.id)?.latest">
            <p class="field-help">最近记录：{{ repracticeOutcomeLabels[bySource.get(source.id)!.latest!.outcome] }} · {{ reviewDate(bySource.get(source.id)!.latest!.createdAt) }}</p>
            <p class="reason">{{ bySource.get(source.id)!.latest!.reason }}</p>
            <NButton text size="small" :disabled="!readable" @click="compare(bySource.get(source.id)!.latest!.comparisonId)">查看对应新旧对照</NButton>
          </template>
        </article>
      </section>
    </div>
  </section>
</template>

<style scoped>
.action-progress, .progress-content, .answer-group { display: grid; gap: 15px; min-width: 0; } .action-progress { padding: 20px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); } header { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 12px; } h3 { margin: 5px 0 0; font-size: 17px; } h4, p { margin: 0; } h4 { font-size: 14px; } .answer-group { padding-top: 15px; border-top: 1px solid var(--line); } .source-progress { display: grid; gap: 8px; padding: 14px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface); } .verification, .reason { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; } .verification { font-weight: 550; } .source-progress > .n-button { justify-self: start; }
@media (max-width: 600px) { .action-progress { padding: 14px; } }
</style>
