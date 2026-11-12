<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { NButton, NCheckbox, NInput } from 'naive-ui'
import { ApiError, read } from '../../api/client'
import type { MemoryCard, MemoryReference } from '../../api/memory'
import { memoryCardValid, memoryIdValid, memoryPromptSignature, memoryReferenceValid } from '../../api/memory'
import type { MemoryRecallState as RecallState, MemoryReviewSettings } from '../../api/memoryReview'
import { memoryCurrentRecallStateValid, memoryRecallStateValid, memoryReviewSettingsValid, memorySourceReviewProblem, memoryReviewErrorText as errorText } from '../../api/memoryReview'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MarkdownPreview from '../MarkdownPreview.vue'
import MemoryScheduleEditor from './MemoryScheduleEditor.vue'
import MemoryReviewHistory from './MemoryReviewHistory.vue'

const props = defineProps<{ id: string; card?: MemoryCard; disabled?: boolean; mutationDisabled?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter(), writer = useCommand(), active = ref(true)
const current = shallowRef<RecallState>(), currentEpoch = ref(-1), settingsValue = shallowRef<MemoryReviewSettings>(), settingsEpoch = ref(-1)
const verified = ref(''), opened = ref(false), reviewBase = shallowRef<RecallState>(), reviewCard = shallowRef<MemoryCard>()
const reference = shallowRef<MemoryReference>(), reason = ref(''), confirmed = ref(false), needsReview = ref(false), message = ref('')
const deferred = shallowRef<() => void>(), changes = reactive({ dirty: false, locked: false }), stateKey = `memory-source-review-${props.id}`
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && memoryIdValid(props.id))
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const otherChanges = computed(() => [...store.changes].some(([key, value]) => key.startsWith('memory-') && key !== stateKey && (value.locked || value.dirty)))
const blocked = computed(() => !readable.value || locked.value || props.mutationDisabled || otherChanges.value)
const dirty = computed(() => opened.value && Boolean(reason.value || confirmed.value))
const key = computed(() => ['memory', store.sessionEpoch, 'recall-state', props.id])
// 设置编辑器独立核验读取代次；管理页使用独立查询，避免共享请求绕过各自的恢复检查。
const settingsKey = computed(() => ['memory', store.sessionEpoch, 'recall-settings', props.id])
const currentVisible = computed(() => readable.value && currentEpoch.value === store.sessionEpoch)
let alive = true, generation = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
watch([readable, () => store.sessionEpoch, () => props.id], () => {
  generation++; verified.value = ''; reference.value = undefined; confirmed.value = false
  if (opened.value) needsReview.value = true
}, { flush: 'sync' })
const stateQuery = useQuery({ queryKey: key, enabled: readable, staleTime: 0, queryFn: async () => {
  const id = props.id, epoch = store.sessionEpoch, stamp = generation
  const value = await read<RecallState>(`/memory/cards/${id}/recall-state`)
  requireCurrent(id, epoch, stamp)
  if (!memoryCurrentRecallStateValid(value, id)) throw mismatch('来源复核状态与当前卡片不一致，请重新读取。')
  verified.value = `${epoch}/${stamp}`
  return value
} })
const settings = useQuery({ queryKey: settingsKey, enabled: readable, staleTime: 0, queryFn: async () => {
  const id = props.id, epoch = store.sessionEpoch, stamp = generation
  const value = await read<MemoryReviewSettings>('/memory/review-settings')
  requireCurrent(id, epoch, stamp)
  if (!memoryReviewSettingsValid(value)) throw mismatch('复习设置格式异常，请重新读取。')
  return value
} })
watch([stateQuery.data, stateQuery.dataUpdatedAt, key, readable], () => {
  if (!readable.value) return
  const value = queryClient.getQueryData<RecallState>(key.value)
  if (value && memoryCurrentRecallStateValid(value, props.id)) { current.value = value; currentEpoch.value = store.sessionEpoch }
}, { immediate: true })
watch([settings.data, settings.dataUpdatedAt, settingsKey, readable], () => {
  if (!readable.value) return
  const value = queryClient.getQueryData<MemoryReviewSettings>(settingsKey.value)
  if (value && memoryReviewSettingsValid(value)) { settingsValue.value = value; settingsEpoch.value = store.sessionEpoch }
}, { immediate: true })
const fresh = computed(() => currentVisible.value && verified.value === `${store.sessionEpoch}/${generation}` && !stateQuery.isFetching.value && !stateQuery.error.value)
const freshSettings = computed(() => currentVisible.value && settingsEpoch.value === store.sessionEpoch && !settings.isFetching.value && !settings.error.value ? settingsValue.value : undefined)
const cardMatches = computed(() => Boolean(current.value && props.card && memoryCardValid(props.card, props.id)
  && props.card.revision === current.value.cardRevision && props.card.currentVersion.id === current.value.cardVersionId && props.card.status === current.value.cardStatus
  && JSON.stringify(props.card.currentVersion.content.sources) === JSON.stringify(current.value.sources.map(source => ({ itemId: source.itemId, versionId: source.versionId })))))
const stale = computed(() => Boolean(reviewBase.value && (!current.value || basisSignature(reviewBase.value) !== basisSignature(current.value))))
const referenceReady = computed(() => Boolean(reference.value && reviewCard.value && reviewBase.value && memoryReferenceValid(reference.value, props.id, reviewCard.value.currentVersion.id, reviewCard.value.currentVersion.number)
  && cardMatches.value && props.card && memoryPromptSignature(reviewCard.value.currentVersion.content) === memoryPromptSignature(props.card.currentVersion.content)))
const reasonProblem = computed(() => memorySourceReviewProblem({ expectedCardRevision: reviewBase.value?.cardRevision,
  expectedGateRevision: reviewBase.value?.gate?.revision, confirmed: true, reason: reason.value }))
const canReview = computed(() => !blocked.value && fresh.value && cardMatches.value && current.value?.eligibility.sourceReviewAllowed === true)
const canSubmit = computed(() => canReview.value && opened.value && referenceReady.value && !stale.value && !needsReview.value && !reasonProblem.value && confirmed.value)
watch([dirty, locked], ([changed, waiting]) => { changes.dirty = changed; changes.locked = waiting; store.changes.set(stateKey, changes) }, { immediate: true, flush: 'sync' })
watch([reason, () => current.value ? basisSignature(current.value) : '', () => props.card?.currentVersion.id], () => { confirmed.value = false }, { flush: 'sync' })
watch(writer.error, error => { if (error instanceof ApiError && !writer.pending.value) { confirmed.value = false; if (error.status === 409) needsReview.value = true } })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; reference.value = undefined; if (store.changes.get(stateKey) === changes) store.changes.delete(stateKey) })

const blockerLabels = { CARD_ARCHIVED: '卡片已归档，恢复后再核对来源。', GATE_MISSING: '缺少来源复核记录，暂不能修改排程。', REVIEW_REQUIRED: '此前影响仍待人工复核；当前正文相同也不会自动清除。', SOURCE_UNAVAILABLE: '存在无法读取的来源，请核对并修订卡片依据。', SOURCE_BODY_CHANGED: '所引来源正文与当前发布版不同，请先修订卡片或改绑来源。', SOURCE_UPSTREAM_REVIEW_REQUIRED: '来源知识的历史采用来源待核对，请先在知识详情处理来源更正。处理后仍需复核本卡并显式恢复排程。', ARCHIVED_NOTE_SOURCE: '笔记来源已归档，需恢复来源或修订卡片依据。', NO_VALID_SOURCE: '目前没有可用的来源依据。' }
const sourceLabels = { CURRENT: '当前发布版本', SUPERSEDED: '来源已有新版本', ARCHIVED: '来源已归档', UNAVAILABLE: '来源不可用' }
function basisSignature(value: RecallState) { return JSON.stringify([value.cardId, value.cardRevision, value.cardVersionId, value.cardStatus, value.gate, value.sources, value.eligibility]) }
function copy<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
function requireCurrent(id: string, epoch: number, stamp: number) { if (!alive || !readable.value || id !== props.id || epoch !== store.sessionEpoch || stamp !== generation) throw new ApiError(0, 'MEMORY_RECALL_CHANGED', '卡片或访问条件已变化，请重新核对；输入继续保留。') }
function mismatch(message: string) { return new ApiError(502, 'MEMORY_RECALL_INVALID', message) }
function refresh() {
  if (!readable.value || locked.value) return
  verified.value = ''; confirmed.value = false
  void stateQuery.refetch(); void settings.refetch(); emit('changed')
}
function readReference() {
  if (!canReview.value || !current.value || !props.card) return
  const fixed = copy(current.value), fixedCard = copy(props.card)
  commandEpoch = store.sessionEpoch; commandGeneration = generation; confirmed.value = false; reference.value = undefined; message.value = ''
  let original: typeof writer.pending.value
  void writer.run<MemoryReference>(`/memory/cards/${fixed.cardId}/versions/${fixed.cardVersionId}/reference`, 'POST', {}, value => {
    if (!alive) return
    if (!memoryReferenceValid(value, fixed.cardId, fixed.cardVersionId, fixedCard.currentVersion.number)) { writer.pending.value = original; writer.error.value = mismatch('参考回执与待复核卡片版本不一致，原请求已保留。'); return }
    // 参考正文不缓存或延迟展示；访问跨代次后仅保留原键，恢复权限后重新读取。
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) { writer.pending.value = original; writer.error.value = mismatch('参考读取期间访问条件有变化，请恢复访问后用原请求重读。'); return }
    if (props.id !== fixed.cardId || !current.value || current.value.cardRevision !== fixed.cardRevision || current.value.cardVersionId !== fixed.cardVersionId) { message.value = '旧版参考请求已确认，卡片已有变化。输入保留，请核对当前版后重新读取。'; return }
    reference.value = value; reviewBase.value = fixed; reviewCard.value = fixedCard; opened.value = true; needsReview.value = false; confirmed.value = false
    message.value = '已读取这份确切卡片版本的参考。请核对来源并填写本次复核说明。'
  })
  original = writer.pending.value
}
function submitReview() {
  if (!canSubmit.value || !reviewBase.value?.gate) return
  const fixed = copy(reviewBase.value), gate = fixed.gate!, explanation = reason.value
  const body = { expectedCardRevision: fixed.cardRevision, expectedGateRevision: gate.revision, confirmed: true, reason: explanation }
  if (memorySourceReviewProblem(body)) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; message.value = ''
  let original: typeof writer.pending.value
  void writer.run<RecallState>(`/memory/cards/${fixed.cardId}/source-review`, 'POST', body, value => {
    if (!alive) return
    if (!memoryRecallStateValid(value, fixed.cardId) || value.cardRevision !== fixed.cardRevision || value.cardVersionId !== fixed.cardVersionId || value.cardStatus !== 'ACTIVE'
      || !value.gate || value.gate.revision !== gate.revision + 1 || value.gate.reviewRequired || !memoryIdValid(value.gate.lastReviewDecisionId)
      || value.gate.lastReviewDecisionId === gate.lastReviewDecisionId || value.gate.lastHistorySequence !== gate.lastHistorySequence + 1
      || JSON.stringify(value.sources.map(source => [source.itemId, source.versionId])) !== JSON.stringify(fixed.sources.map(source => [source.itemId, source.versionId]))) {
      writer.pending.value = original; writer.error.value = mismatch('复核回执与本次卡片和门禁修订不一致，输入及原请求继续保留。'); return
    }
    const receive = () => {
      deferred.value = undefined; writer.error.value = undefined; reference.value = undefined; reviewBase.value = undefined; reviewCard.value = undefined
      reason.value = ''; opened.value = false; confirmed.value = false; needsReview.value = false; verified.value = ''
      message.value = `来源复核已确认（门禁修订 ${value.gate!.revision}）。排程需要另行启用或恢复。`
      void queryClient.invalidateQueries({ queryKey: ['memory'] }); emit('changed')
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) deferred.value = receive
    else receive()
  })
  original = writer.pending.value
}
function closeReview() {
  if (!readable.value || locked.value || !store.canLeave([stateKey])) return
  opened.value = false; reviewBase.value = undefined; reviewCard.value = undefined; reference.value = undefined; reason.value = ''; confirmed.value = false; needsReview.value = false
}
function retry() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) deferred.value() }
function openSource(itemId: string, versionId: string) { if (readable.value && !locked.value && memoryIdValid(itemId) && memoryIdValid(versionId)) void router.push({ name: 'knowledge', query: { item: itemId, version: versionId } }) }
</script>

<template>
  <section class="recall-management" aria-label="来源复核与排程">
    <header><div><h3>来源复核与排程</h3><p class="field-help">先确认卡片依据，再明确安排未来复习。这里的操作不产生作答或评分。</p></div><NButton text size="small" :disabled="!readable || locked || stateQuery.isFetching.value" @click="refresh">重读管理状态</NButton></header>
    <p v-if="!readable" class="notice" role="status">访问恢复后可继续管理；复核说明、排程输入和未决请求继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="management-content">
      <p v-if="stateQuery.isPending.value" role="status">正在读取来源门禁与排程…</p><p v-if="stateQuery.error.value" class="notice error" role="alert">{{ errorText(stateQuery.error.value) }}</p><p v-if="settings.error.value" class="notice error" role="alert">复习设置读取失败：{{ errorText(settings.error.value) }}</p>
      <template v-if="current && currentVisible">
        <p class="state-summary">{{ current.gate ? current.gate.reviewRequired ? '来源仍需复核' : '当前没有待复核影响' : '来源门禁缺失' }}<span class="field-help">卡片修订 {{ current.cardRevision }}<template v-if="current.gate"> · 门禁修订 {{ current.gate.revision }}</template></span></p>
        <ul v-if="current.eligibility.blockers.length" class="notice blocker-list"><li v-for="blocker in current.eligibility.blockers" :key="blocker">{{ blockerLabels[blocker] }}</li></ul>
        <p v-if="current.eligibility.warnings.includes('ARCHIVED_CONCEPT_WITH_OTHER_SOURCE')" class="notice">部分来源概念已归档，其他有效来源仍可作为依据。这个例外不清除此前留下的正文待复核记录。</p>
        <ol class="current-sources"><li v-for="source in current.sources" :key="`${source.itemId}/${source.versionId}`"><div><strong>{{ card?.sourceReferences.find(item => item.itemId === source.itemId && item.versionId === source.versionId)?.title ?? `知识来源 ${source.itemId.slice(0, 8)}` }}</strong><p class="field-help">{{ source.versionNumber === null ? '版本待核对' : `v${source.versionNumber}` }} · {{ sourceLabels[source.state] }}{{ source.bodyChanged ? ' · 正文不同，请核对' : '' }}{{ source.upstreamReviewRequired ? ' · 上游采用来源待复核' : '' }}</p></div><NButton text size="small" :disabled="locked || source.state === 'UNAVAILABLE'" @click="openSource(source.itemId, source.versionId)">查看确切来源</NButton></li></ol>
        <p v-if="!cardMatches" class="notice">卡片题面尚未读取，或管理状态已对应更晚修订。可查看历史与现有排程；复核前请重读卡片题面。</p><p v-if="mutationDisabled" class="field-help">请先处理卡片编辑草稿或正在进行的操作，再复核来源或调整排程。</p>
        <NButton class="prepare-review" :disabled="!canReview" :loading="writer.busy.value" @click="readReference">{{ opened ? '重新读取同版参考并核对' : '读取当前卡片参考，准备复核' }}</NButton>
      </template>
      <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="writer.busy.value" :loading="writer.busy.value" @click="retry">用原请求核对</NButton></p><p v-if="deferred" class="notice" role="status">此前的来源复核已收到有效回执。<NButton text @click="receiveDeferred">接收原复核结果</NButton></p>
      <section v-if="opened" class="review-form" aria-label="人工来源复核"><h4>本次来源复核</h4><p v-if="stale || needsReview || !referenceReady" class="notice">来源、卡片或访问条件有变化，复核说明保留。请重读状态和同版参考，再明确确认。</p><template v-if="reference && reviewCard"><p class="field-help">核对卡片 v{{ reviewCard.currentVersion.number }} · 卡片修订 {{ reviewBase?.cardRevision }} · 门禁修订 {{ reviewBase?.gate?.revision }}</p><h5>题面</h5><MarkdownPreview :value="reviewCard.currentVersion.content.prompt" /><h5>个人参考</h5><MarkdownPreview :value="reference.reference" /></template><label>复核说明<NInput v-model:value="reason" type="textarea" :autosize="{ minRows: 3, maxRows: 10 }" :disabled="blocked" placeholder="说明依据为何仍适用，或你已完成哪些修订与核对" /><span class="field-help">{{ Array.from(reason).length }} / 1000 字符</span></label><p v-if="reasonProblem" class="field-help">{{ reasonProblem }}</p><NCheckbox v-model:checked="confirmed" :disabled="!canReview || !referenceReady || stale || needsReview || !!reasonProblem">我已核对这份卡片题面、个人参考和当前来源，确认依据可用。此确认不代表复习完成，也不会自动恢复排程。</NCheckbox><div class="row-actions"><NButton type="primary" :disabled="!canSubmit" :loading="writer.busy.value" @click="submitReview">确认本次来源复核</NButton><NButton text :disabled="locked" @click="closeReview">关闭复核</NButton></div></section>
      <MemoryScheduleEditor v-if="current" v-show="currentVisible" :state="current" :settings="freshSettings" :verified="fresh" :disabled="!readable || !currentVisible" :mutation-disabled="mutationDisabled || dirty || locked" @changed="refresh" />
      <MemoryReviewHistory :id="id" :disabled="!readable" />
    </div>
  </section>
</template>

<style scoped>
.recall-management, .management-content, .review-form { display: grid; gap: 17px; min-width: 0; }.recall-management > header { display: flex; justify-content: space-between; gap: 14px; align-items: start; flex-wrap: wrap; }.recall-management > header > div { display: grid; gap: 8px; }h3, h4, h5, p { margin: 0; }h3 { font-size: 17px; }h4 { font-size: 15px; }h5 { font-size: 13px; }.state-summary { display: grid; gap: 7px; font-weight: 600; }.state-summary .field-help { font-weight: 400; }.blocker-list { display: grid; gap: 8px; margin: 0; padding-left: 30px; }.current-sources { list-style: none; margin: 0; padding: 0; }.current-sources li { display: flex; gap: 12px; align-items: baseline; justify-content: space-between; padding: 12px 0; border-bottom: 1px solid var(--line); flex-wrap: wrap; }.current-sources li > div { display: grid; gap: 6px; min-width: 0; }.current-sources strong { font-size: 13px; overflow-wrap: anywhere; }.prepare-review { justify-self: start; }.review-form { padding: 20px 0; border-top: 1px solid var(--line); }.review-form > label { display: grid; gap: 8px; font-size: 13px; }.review-form :deep(.markdown-preview) { max-width: 75ch; overflow-wrap: anywhere; }.review-form :deep(.n-checkbox__label) { white-space: normal; }.row-actions { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
</style>
