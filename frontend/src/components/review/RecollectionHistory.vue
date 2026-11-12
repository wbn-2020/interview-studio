<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { questionTypeLabels } from '../../api/bank'
import type { Recollection, RecollectionVersion, RecollectionVersionPage } from '../../api/recollection'
import { recollectionBaselineLabels, recollectionValid, recollectionVersionValid } from '../../api/recollection'
import { reviewIdValid } from '../../api/review'
import { scheduleStatusLabels } from '../../api/schedule'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ id: string; currentVersion: RecollectionVersion; versionId?: string; disabled?: boolean }>()
const emit = defineEmits<{ select: [id: string] }>()
const store = useWorkspaceStore()
const router = useRouter()
const access = useTrainingAccessStore()
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.restricted && !access.challenge)
const selectedId = computed(() => props.versionId ?? props.currentVersion.id)
const before = ref<number>()
const cursors = ref<(number | undefined)[]>([])
const draftOpening = ref(false)
const draftError = ref('')
let alive = true
let generation = 0
let draftGeneration = 0
const list = useQuery({
  queryKey: computed(() => ['recollections', store.sessionEpoch, 'versions', props.id, before.value]),
  enabled: readable,
  queryFn: async () => {
    const epoch = store.sessionEpoch, request = generation, id = props.id, cursor = before.value
    const value = await read<RecollectionVersionPage>(`/review/recollections/${id}/versions?limit=10${cursor === undefined ? '' : `&before=${cursor}`}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || request !== generation || id !== props.id || cursor !== before.value) throw new ApiError(0, 'RECOLLECTION_CONTEXT_CHANGED', '版本读取条件已变化，请重新读取。')
    if (!value || !Array.isArray(value.items) || value.items.length > 10 || (value.nextBefore !== null && (!Number.isInteger(value.nextBefore) || value.nextBefore < 1))
      || value.items.some(item => !reviewIdValid(item.id) || !Number.isInteger(item.number) || item.number < 1 || item.number > 1000 || (cursor !== undefined && item.number >= cursor) || typeof item.title !== 'string')
      || new Set(value.items.map(item => item.id)).size !== value.items.length) throw new ApiError(502, 'RECOLLECTION_HISTORY_INVALID', '版本列表格式异常，请重新读取。')
    return value
  },
})
const selected = useQuery({
  queryKey: computed(() => ['recollections', store.sessionEpoch, 'version', props.id, selectedId.value]),
  enabled: computed(() => readable.value && reviewIdValid(selectedId.value)),
  queryFn: async () => {
    const epoch = store.sessionEpoch, request = generation, id = props.id, versionId = selectedId.value
    const value = await read<RecollectionVersion>(`/review/recollections/${id}/versions/${versionId}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || request !== generation || id !== props.id || versionId !== selectedId.value) throw new ApiError(0, 'RECOLLECTION_CONTEXT_CHANGED', '所选版本已变化，请重新读取。')
    if (!recollectionVersionValid(value) || value.id !== versionId) throw new ApiError(502, 'RECOLLECTION_VERSION_INVALID', '回忆版本与本次选择不一致，请重新读取。')
    return value
  },
})
watch([readable, () => store.sessionEpoch], () => { generation++ }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.id, selectedId], () => { draftGeneration++; draftOpening.value = false; draftError.value = '' }, { flush: 'sync' })
watch(() => store.sessionEpoch, () => { before.value = undefined; cursors.value = [] })
onUnmounted(() => { alive = false; generation++; draftGeneration++ })
async function createKnowledgeDraft() {
  if (!readable.value || draftOpening.value || selected.isFetching.value || selected.error.value || !selected.data.value
    || selected.data.value.id !== selectedId.value || selectedId.value !== props.currentVersion.id) return
  const id = props.id, versionId = selectedId.value, epoch = store.sessionEpoch, request = ++draftGeneration
  draftOpening.value = true; draftError.value = ''
  try {
    // 历史版本只带正文；进入创建页前另读当前 head，不能把旧版入口默默换成最新版。
    const current = await read<Recollection>(`/review/recollections/${id}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || request !== draftGeneration || id !== props.id || versionId !== selectedId.value) return
    if (!recollectionValid(current, id)) throw new ApiError(502, 'RECOLLECTION_SOURCE_INVALID', '当前回忆记录不完整，请重新读取。')
    if (current.status !== 'ACTIVE' || current.currentVersion.id !== versionId) throw new ApiError(409, 'AI_SOURCE_CHANGED', '所选版本已不是使用中的当前回忆，请回到回忆记录核对后重新选择。')
    if (JSON.stringify(current.currentVersion) !== JSON.stringify(selected.data.value)) throw new ApiError(409, 'AI_SOURCE_CHANGED', '所选版本内容与当前记录不一致，请重新读取版本。')
    await router.push({ name: 'ai', query: { create: '1', purpose: 'REVIEW_DRAFT', reviewKind: 'RECOLLECTION', recollectionId: id, versionId, sourceRevision: String(current.revision) } })
  } catch (error) { if (alive && request === draftGeneration) draftError.value = errorText(error) }
  finally { if (alive && request === draftGeneration) draftOpening.value = false }
}
function older() { if (readable.value && !list.isFetching.value && list.data.value?.nextBefore != null) { cursors.value.push(before.value); before.value = list.data.value.nextBefore } }
function displayTime(instant: string, zone?: string) {
  try { return new Date(instant).toLocaleString('zh-CN', zone ? { timeZone: zone } : undefined) }
  catch { return instant }
}
</script>

<template>
  <section class="recollection-history" aria-label="回忆的历史版本">
    <p class="field-help">每一版保留当时的补记和面试安排。阅读历史不会替换编辑中的输入，也不会把后来的补记改成面试当时的回答。</p>
    <p v-if="!readable" class="field-help" role="status">资料访问恢复后可继续阅读历史。</p>
    <template v-else>
      <p v-if="list.isPending.value" role="status">正在读取历史版本…</p>
      <p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text :disabled="list.isFetching.value" @click="list.refetch()">重新读取列表</NButton></p>
      <div class="history-list"><button v-for="version in list.data.value?.items" :key="version.id" class="history-choice" :aria-current="version.id === selectedId ? 'true' : undefined" @click="emit('select', version.id)"><strong>v{{ version.number }} · {{ version.title }}</strong><small>{{ displayTime(version.createdAt) }}</small></button></div>
      <p v-if="list.data.value && !list.data.value.items.length && !list.error.value" class="field-help">本页没有更早的版本。</p>
      <div class="row-actions"><NButton size="small" :disabled="!cursors.length || list.isFetching.value" @click="before = cursors.pop()">较新版本</NButton><NButton size="small" :disabled="list.data.value?.nextBefore == null || list.isFetching.value" @click="older">更早版本</NButton></div>
      <p v-if="!reviewIdValid(selectedId)" class="notice error" role="alert">历史版本地址无效，请从上方列表重新选择。</p>
      <p v-else-if="selected.isPending.value" role="status">正在读取所选版本…</p>
      <p v-if="selected.error.value" class="notice error" role="alert">{{ errorText(selected.error.value) }}<NButton text :disabled="selected.isFetching.value" @click="selected.refetch()">重新读取版本</NButton></p>
      <article v-if="selected.data.value && selected.data.value.id === selectedId" class="history-content">
        <header><span class="field-help">个人回忆 · v{{ selected.data.value.number }} · {{ displayTime(selected.data.value.createdAt) }}</span><h3>{{ selected.data.value.content.title }}</h3></header>
        <p class="field-help">{{ questionTypeLabels[selected.data.value.content.type] }} · {{ recollectionBaselineLabels[selected.data.value.content.baselineKind] }}</p>
        <dl>
          <dt>记得的问题</dt><dd>{{ selected.data.value.content.question }}</dd>
          <dt>记得的条件或上下文</dt><dd>{{ selected.data.value.content.conditions || '未补记' }}</dd>
          <dt>{{ selected.data.value.content.baselineKind === 'RECOLLECTION' ? '回答回忆片段' : '旧回答' }}</dt><dd>{{ selected.data.value.content.baselineKind === 'RECOLLECTION' ? selected.data.value.content.answerFragment : '没有旧回答。不能作为确切提交与新回答进行双答案比较。' }}</dd>
          <dt>回忆的反馈</dt><dd>{{ selected.data.value.content.feedback || '未补记' }}</dd>
          <dt>记忆的不确定性</dt><dd>{{ selected.data.value.content.uncertainty }}</dd>
          <dt>自己认为的待补内容</dt><dd>{{ selected.data.value.content.gap || '未补记' }}</dd>
        </dl>
        <div v-if="selected.data.value.interview" class="historical-interview"><strong>当时关联的面试：{{ selected.data.value.interview.title }}</strong><p>{{ displayTime(selected.data.value.interview.startAt, selected.data.value.interview.zoneId) }} · {{ selected.data.value.interview.zoneId }}</p><p class="field-help">{{ selected.data.value.interview.round || '未填写轮次' }} · {{ scheduleStatusLabels[selected.data.value.interview.status] }} · 安排修订 {{ selected.data.value.interview.revision }}</p><RouterLink class="text-link" :to="{ name: 'schedule', query: { item: selected.data.value.interview.eventId, version: String(selected.data.value.interview.revision) } }">查看这份安排历史</RouterLink></div>
        <p v-else class="field-help">这一版未关联面试安排。</p>
        <div class="row-actions"><NButton :loading="draftOpening" :disabled="!readable || selected.isFetching.value || !!selected.error.value || selectedId !== currentVersion.id" @click="createKnowledgeDraft">整理所选回忆为知识</NButton></div><p class="field-help">仅使用当前且未归档的回忆版本。下一页明确选择片段并核对完整记忆不确定性；面试安排不在外发清单中。<span v-if="selectedId !== currentVersion.id">当前展示历史版本，请先明确选择当前版。</span></p><p v-if="draftError" class="notice error" role="alert">{{ draftError }}</p>
      </article>
    </template>
  </section>
</template>

<style scoped>
.recollection-history, .history-list, .history-content { display: grid; gap: 14px; min-width: 0; }
.history-list { gap: 4px; }
.history-choice { display: grid; gap: 5px; padding: 10px; text-align: left; border: 1px solid transparent; border-radius: 5px; overflow-wrap: anywhere; }
.history-choice:hover, .history-choice[aria-current=true] { background: var(--surface-soft); }
.history-choice[aria-current=true] { border-color: var(--brand); }
.history-choice strong { font-size: 13px; }.history-choice small { font-size: 12px; color: var(--muted); }
.history-content { padding-top: 18px; border-top: 1px solid var(--line); }.history-content h3 { margin-top: 8px; overflow-wrap: anywhere; }
dl { display: grid; gap: 8px; margin: 0; }dt { margin-top: 10px; font-size: 12px; font-weight: 650; color: var(--muted); }dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 14px; }
.historical-interview { display: grid; gap: 8px; padding-top: 16px; border-top: 1px solid var(--line); font-size: 13px; overflow-wrap: anywhere; }
</style>
