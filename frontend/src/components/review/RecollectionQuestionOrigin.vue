<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { RecollectionQuestionOrigin } from '../../api/recollection'
import { recollectionBaselineLabels, recollectionQuestionOriginValid, recollectionSourceLabels } from '../../api/recollection'
import { reviewDate, reviewIdValid } from '../../api/review'
import { formatScheduleTime, scheduleStatusLabels } from '../../api/schedule'
import { queryClient } from '../../api/queryClient'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ questionId: string; versionId: string; disabled?: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const active = ref(true), opened = ref(false)
const current = shallowRef<RecollectionQuestionOrigin>()
let alive = true, generation = 0
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && reviewIdValid(props.questionId) && reviewIdValid(props.versionId))
const key = computed(() => ['recollections', store.sessionEpoch, 'question-origin', props.questionId, props.versionId])
const origin = useQuery({ queryKey: key, enabled: computed(() => readable.value && opened.value), queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, questionId = props.questionId, versionId = props.versionId
  const value = await read<RecollectionQuestionOrigin>(`/bank/questions/${questionId}/versions/${versionId}/recollection-source`)
  if (!alive || !readable.value || !opened.value || epoch !== store.sessionEpoch || stamp !== generation
    || questionId !== props.questionId || versionId !== props.versionId) throw new ApiError(0, 'RECOLLECTION_CONTEXT_CHANGED', '题目版本或资料访问条件已变化，请重新读取来源。')
  if (!recollectionQuestionOriginValid(value, questionId, versionId)) throw new ApiError(502, 'RECOLLECTION_SOURCE_MISMATCH', '回忆来源与所选题目版本不一致，请重新读取。')
  return value
} })
watch([readable, opened, () => props.questionId, () => props.versionId, () => store.sessionEpoch], () => {
  generation++; current.value = undefined
}, { flush: 'sync' })
watch([origin.data, origin.dataUpdatedAt, key, readable, opened], () => {
  if (!alive || !readable.value || !opened.value) return
  const value = queryClient.getQueryData<RecollectionQuestionOrigin>(key.value)
  if (value && recollectionQuestionOriginValid(value, props.questionId, props.versionId)) current.value = value
}, { immediate: true })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++ })
function interviewTime(startAt: string, zoneId: string): string {
  try { return `${formatScheduleTime(startAt, zoneId)} · ${zoneId}` } catch { return `${startAt} · ${zoneId}` }
}
</script>

<template>
  <section class="recollection-origin" aria-label="所选题目版本的面后回忆来源">
    <header><div><h3>面后回忆来源</h3><p class="field-help">读取本题目版本采用时的确切回忆，保留个人回忆身份。</p></div><NButton size="small" :disabled="!readable" :aria-expanded="opened" @click="opened = !opened">{{ opened ? '收起来源' : '查看回忆来源' }}</NButton></header>
    <p v-if="opened && !readable" class="notice" role="status">当前登录或训练条件限制资料访问，恢复后可继续读取。</p>
    <template v-if="opened && readable">
      <p v-if="origin.isPending.value && !current" class="field-help" role="status">正在读取确切回忆版本…</p>
      <p v-if="origin.error.value" class="notice error" role="alert">{{ errorText(origin.error.value) }}<NButton text :disabled="!readable || origin.isFetching.value" @click="origin.refetch()">重新读取来源</NButton></p>
      <p v-if="current?.source === null && !origin.error.value" class="field-help">所选题目版本没有面后回忆来源记录。后续人工编辑的版本可从题目历史回溯初始版本。</p>
      <article v-if="current?.source" class="source-record">
        <div class="source-heading"><strong>{{ current.source.recollection.content.title }} · 回忆 v{{ current.source.recollection.number }}</strong><span>{{ recollectionSourceLabels[current.source.state] }}</span></div>
        <p class="field-help">题目整理于 {{ reviewDate(current.source.link.createdAt) }}；回忆版本保存于 {{ reviewDate(current.source.recollection.createdAt) }}。回忆不代表逐字实录、已确认项目事实或技术审阅结论。</p>
        <p v-if="current.source.state !== 'CURRENT'" class="notice">{{ current.source.state === 'ARCHIVED' ? '原回忆已归档，采用时的确切版本继续保留。' : '原回忆已有后续补记，当前展示仍为采用时的版本。' }}</p>
        <RouterLink class="text-link" :to="{ name: 'recollections', query: { item: current.source.link.recollectionId, versionId: current.source.link.recollectionVersionId } }">打开这份回忆的确切版本</RouterLink>
        <div v-if="current.source.recollection.interview" class="interview-record"><h4>当时关联的面试安排</h4><p>{{ current.source.recollection.interview.title }} · 修订 {{ current.source.recollection.interview.revision }}{{ current.source.recollection.interview.round ? ` · ${current.source.recollection.interview.round}` : '' }}</p><p class="field-help">{{ interviewTime(current.source.recollection.interview.startAt, current.source.recollection.interview.zoneId) }} · {{ scheduleStatusLabels[current.source.recollection.interview.status] }}</p><p class="field-help">这是保存回忆时选中的历史安排摘要，后来的改期或取消不会改写它。</p></div>
        <details class="saved-content"><summary>阅读采用时保存的回忆内容</summary><div class="recollection-fields">
          <section><h4>记得的问题</h4><p class="preserve">{{ current.source.recollection.content.question }}</p></section>
          <section v-if="current.source.recollection.content.conditions"><h4>记得的条件</h4><p class="preserve">{{ current.source.recollection.content.conditions }}</p></section>
          <section><h4>{{ recollectionBaselineLabels[current.source.recollection.content.baselineKind] }}</h4><p class="preserve">{{ current.source.recollection.content.baselineKind === 'RECOLLECTION' ? current.source.recollection.content.answerFragment : '本版没有记录旧回答，不据此生成双答案比较。' }}</p></section>
          <section v-if="current.source.recollection.content.feedback"><h4>回忆的反馈</h4><p class="preserve">{{ current.source.recollection.content.feedback }}</p></section>
          <section><h4>不确定性说明</h4><p class="preserve">{{ current.source.recollection.content.uncertainty }}</p></section>
          <section v-if="current.source.recollection.content.gap"><h4>用户提出的待补内容</h4><p class="preserve">{{ current.source.recollection.content.gap }}</p></section>
        </div></details>
      </article>
    </template>
  </section>
</template>

<style scoped>
.recollection-origin, .source-record, .recollection-fields { display: grid; gap: 16px; min-width: 0; }
.recollection-origin { padding-top: 21px; margin-top: 24px; border-top: 1px solid var(--line); }
header, .source-heading { display: flex; justify-content: space-between; align-items: start; flex-wrap: wrap; gap: 14px; }
header > div, .interview-record, .recollection-fields section { display: grid; gap: 8px; min-width: 0; }
h3 { margin: 0; font-size: 16px; } h4 { margin: 0; font-size: 13px; } p { margin: 0; }
.source-heading strong { font-size: 14px; overflow-wrap: anywhere; } .source-heading span { font-size: 12px; color: var(--muted); }
.preserve { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.85; font-size: 13px; max-width: 75ch; }
.interview-record { border-top: 1px solid var(--line); padding-top: 15px; font-size: 13px; }
.saved-content summary { cursor: pointer; font-size: 13px; padding: 10px 0; } .saved-content summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }
</style>
