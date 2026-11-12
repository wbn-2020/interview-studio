<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, read } from '../../api/client'
import type { MemorySummary } from '../../api/memory'
import { memoryIdValid, memoryKindLabels } from '../../api/memory'
import type { MemoryActiveAttempt, MemoryAttemptDetail, MemoryAttemptSummary, MemoryCramBatch, MemoryCramMember,
  MemoryCramMemberPage, MemoryCreateCramBatch, MemoryStartAttempt } from '../../api/memoryAttempt'
import { memoryActiveAttemptValid, memoryAttemptDetailValid, memoryAttemptErrorText as errorText, memoryAttemptModeLabels,
  memoryAttemptStateLabels, memoryCramBatchValid, memoryCramMemberPageValid, memoryCreateCramBatchProblem, memoryStartAttemptProblem } from '../../api/memoryAttempt'
import { memoryBlockReasonLabels, memoryPauseReasonLabels, memoryUtcMicros as micros } from '../../api/memoryReview'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MemoryCardPicker from './MemoryCardPicker.vue'

const props = defineProps<{ id?: string; disabled?: boolean }>()
const emit = defineEmits<{ saved: [value: MemoryCramBatch]; close: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter(), writer = useCommand(), active = ref(true)
const draft = reactive({ title: '', startDate: '', endDate: '', zoneId: '' }), selected = ref<MemorySummary[]>([])
const initialSignature = ref(JSON.stringify([draft, []])), created = shallowRef<MemoryCramBatch>()
const current = shallowRef<MemoryCramBatch>(), detailVerified = ref(''), memberPage = shallowRef<MemoryCramMemberPage>(), membersVerified = ref('')
const after = ref<number>(), cursors = ref<(number | undefined)[]>([]), intent = shallowRef<MemoryCramMember>()
const mode = ref<'EXTRA' | 'REGULAR'>('EXTRA'), beyondBudget = ref(false), budgetRequired = ref(false), confirmed = ref(false), needsReview = ref(false)
const recovered = shallowRef<MemoryActiveAttempt>(), recoverCardId = ref<string>(), recoveryLoading = ref(false), recoveryError = ref('')
const readyAttemptId = ref<string>(), message = ref(''), deferred = shallowRef<() => void>()
const stateKey = `memory-cram-editor-${props.id ?? 'new'}`, changes = reactive({ dirty: false, locked: false })
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && (props.id === undefined || memoryIdValid(props.id)))
const creating = computed(() => props.id === undefined), locked = computed(() => writer.locked.value || Boolean(deferred.value))
const otherLocked = computed(() => [...store.changes].some(([key, state]) => key.startsWith('memory-') && key !== stateKey && state.locked))
const blocked = computed(() => !readable.value || locked.value || otherLocked.value || recoveryLoading.value)
const countRegular = computed(() => mode.value === 'REGULAR')
const dirty = computed(() => creating.value ? JSON.stringify([draft, selected.value.map(item => item.id)]) !== initialSignature.value
  : Boolean(intent.value && (countRegular.value || beyondBudget.value || confirmed.value)))
const createBody = computed<MemoryCreateCramBatch>(() => ({ ...draft, cardIds: selected.value.map(item => item.id) }))
const createProblem = computed(() => memoryCreateCramBatchProblem(createBody.value))
const key = computed(() => ['memory', store.sessionEpoch, 'cram-batch', props.id])
const membersKey = computed(() => ['memory', store.sessionEpoch, 'cram-members', props.id, after.value])
let alive = true, generation = 0, memberGeneration = 0, recoverySequence = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
watch([readable, () => store.sessionEpoch, () => props.id], () => {
  generation++; memberGeneration++; recoverySequence++; detailVerified.value = ''; membersVerified.value = ''; memberPage.value = undefined
  recovered.value = undefined; recoveryLoading.value = false; recoveryError.value = ''; confirmed.value = false; beyondBudget.value = false
  if (intent.value) needsReview.value = true
}, { flush: 'sync' })
watch(after, () => { memberGeneration++; memberPage.value = undefined; membersVerified.value = ''; confirmed.value = false }, { flush: 'sync' })
const detail = useQuery({ queryKey: key, enabled: computed(() => readable.value && !creating.value), staleTime: 0, queryFn: async () => {
  const id = props.id!, epoch = store.sessionEpoch, stamp = generation
  const value = await read<MemoryCramBatch>(`/memory/cram-batches/${id}`)
  requireCurrent(epoch, stamp)
  if (id !== props.id || !batchValid(value, id)) throw invalid('批次字段或固定范围与已读取记录不一致，请重新核对。')
  detailVerified.value = `${epoch}/${stamp}`
  return value
} })
watch([detail.data, detail.dataUpdatedAt, key, readable], () => {
  if (!readable.value || detailVerified.value !== `${store.sessionEpoch}/${generation}` || !props.id) return
  const value = queryClient.getQueryData<MemoryCramBatch>(key.value)
  if (value && batchValid(value, props.id)) current.value = copy(value)
}, { immediate: true })
const detailReady = computed(() => readable.value && Boolean(current.value) && detailVerified.value === `${store.sessionEpoch}/${generation}` && !detail.isFetching.value && !detail.error.value)
const members = useQuery({ queryKey: membersKey, enabled: computed(() => detailReady.value && !creating.value), staleTime: 0, queryFn: async () => {
  const id = props.id!, epoch = store.sessionEpoch, stamp = generation, pageStamp = memberGeneration, pageAfter = after.value, batch = current.value
  if (!batch) throw invalid('请先读取冲刺批次。')
  const value = await read<MemoryCramMemberPage>(`/memory/cram-batches/${id}/cards?limit=20${pageAfter === undefined ? '' : `&after=${pageAfter}`}`)
  requireCurrent(epoch, stamp)
  if (id !== props.id || pageStamp !== memberGeneration || pageAfter !== after.value) throw contextChanged()
  if (!memoryCramMemberPageValid(value, pageAfter, 20, batch)) throw invalid('成员身份、固定顺序或当前条件不一致，请重新读取。')
  membersVerified.value = `${epoch}/${stamp}/${pageStamp}`
  return value
} })
watch([members.data, members.dataUpdatedAt, membersKey, readable], () => {
  if (!readable.value || membersVerified.value !== `${store.sessionEpoch}/${generation}/${memberGeneration}` || !current.value) return
  const value = queryClient.getQueryData<MemoryCramMemberPage>(membersKey.value)
  if (value && memoryCramMemberPageValid(value, after.value, 20, current.value)) memberPage.value = value
}, { immediate: true })
const membersReady = computed(() => detailReady.value && Boolean(memberPage.value) && membersVerified.value === `${store.sessionEpoch}/${generation}/${memberGeneration}` && !members.isFetching.value && !members.error.value)
const latestMember = computed(() => intent.value ? memberPage.value?.items.find(item => item.position === intent.value!.position) : undefined)
const stale = computed(() => Boolean(intent.value && (!latestMember.value || JSON.stringify(intent.value) !== JSON.stringify(latestMember.value))))
const startProblem = computed(() => {
  if (!intent.value || !current.value) return '请先选择本次要回忆的卡片。'
  if (!basisReady(intent.value)) return '当前卡片或来源尚不满足开始条件，请先处理依据或恢复已有尝试。'
  if (countRegular.value && !regularReady(intent.value)) return '计入常规需要已启用且实际到期的排程，请核对条件或选择额外练习。'
  return memoryStartAttemptProblem(startBody(intent.value, current.value.id))
})
const canStart = computed(() => !blocked.value && membersReady.value && Boolean(intent.value) && !needsReview.value && !stale.value && !startProblem.value && confirmed.value
  && (!countRegular.value || !budgetRequired.value || beyondBudget.value))
const modeOptions = computed(() => [{ label: '额外练习，不更新常规排程', value: 'EXTRA' },
  { label: '计入常规排程与每日统计', value: 'REGULAR', disabled: !intent.value || !regularReady(intent.value) }])
watch([dirty, locked], ([changed, waiting]) => { changes.dirty = changed; changes.locked = waiting; store.changes.set(stateKey, changes) }, { immediate: true, flush: 'sync' })
watch(mode, () => { confirmed.value = false; beyondBudget.value = false; budgetRequired.value = false }, { flush: 'sync' })
watch([beyondBudget, () => latestMember.value ? JSON.stringify(latestMember.value) : ''], () => { confirmed.value = false }, { flush: 'sync' })
watch(writer.error, error => {
  if (!(error instanceof ApiError) || writer.pending.value || creating.value) return
  confirmed.value = false
  if (error.status === 409) {
    needsReview.value = true; beyondBudget.value = false
    if (error.code === 'MEMORY_BUDGET_CONFIRMATION_REQUIRED') budgetRequired.value = true
    if (readable.value && current.value) void members.refetch()
    if (error.code === 'MEMORY_ATTEMPT_OPEN' && recoverCardId.value && readable.value) void readOpenAttempt()
  }
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; memberGeneration++; recoverySequence++; if (store.changes.get(stateKey) === changes) store.changes.delete(stateKey) })

function copy<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
function contextChanged() { return new ApiError(0, 'MEMORY_CRAM_CONTEXT_CHANGED', '批次或访问条件已变化，输入保留，请重新核对。') }
function invalid(text: string) { return new ApiError(502, 'MEMORY_CRAM_RECEIPT_INVALID', text) }
function requireCurrent(epoch: number, stamp: number) { if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation) throw contextChanged() }
function batchSignature(value: MemoryCramBatch) { return JSON.stringify([value.id, value.title, value.startDate, value.endDate, value.zoneId, value.createdAt,
  value.cards.map(card => [card.cardId, card.cardVersionId, card.cardVersionNumber, card.cardRevision])]) }
function batchValid(value: unknown, id: string): value is MemoryCramBatch {
  return memoryCramBatchValid(value, id) && (!current.value || current.value.id !== id || batchSignature(value) === batchSignature(current.value))
}
function updateSelected(value: MemorySummary[]) { if (!blocked.value && !created.value) selected.value = value.map(item => ({ ...item })) }
function command<T>(path: string, body: unknown, valid: (value: unknown) => value is T, accept: (value: T) => void) {
  if (blocked.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; message.value = ''
  let original: typeof writer.pending.value
  void writer.run<T>(path, 'POST', body, value => {
    if (!alive) return
    let matches = false
    try { matches = valid(value) } catch { matches = false }
    if (!matches) { writer.pending.value = original; writer.error.value = invalid('回执与本次固定范围或开始条件不一致，原请求和输入保留，请用原请求核对。'); return }
    const receive = () => { deferred.value = undefined; writer.error.value = undefined; accept(value) }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) deferred.value = receive
    else receive()
  })
  original = writer.pending.value
}
function saveBatch() {
  if (blocked.value || !creating.value || created.value || createProblem.value) return
  const snapshot = copy(createBody.value), chosen = copy(selected.value)
  command<MemoryCramBatch>('/memory/cram-batches', snapshot, (value): value is MemoryCramBatch => memoryCramBatchValid(value)
    && value.title === snapshot.title && value.startDate === snapshot.startDate && value.endDate === snapshot.endDate && value.zoneId === snapshot.zoneId
    && value.cards.length === snapshot.cardIds.length && value.cards.every((card, index) => card.cardId === snapshot.cardIds[index]
      && card.cardRevision >= chosen[index]!.revision && card.cardVersionNumber >= chosen[index]!.versionNumber
      && (card.cardVersionId !== chosen[index]!.versionId || card.cardVersionNumber === chosen[index]!.versionNumber)), value => {
    created.value = value; initialSignature.value = JSON.stringify([draft, selected.value.map(item => item.id)])
    message.value = '冲刺批次已保存，范围和计划日期固定。可以逐张查看当前条件后开始练习。'
    void queryClient.invalidateQueries({ queryKey: ['memory'] }); emit('saved', value)
  })
}
function basisReady(member: MemoryCramMember) { return member.currentCard?.status === 'ACTIVE' && member.gateRevision !== null && member.eligibility?.basisReady === true && member.openAttempt === null }
function regularReady(member: MemoryCramMember) {
  const due = micros(member.schedule?.effectiveDueAt), now = micros(memberPage.value?.asOf)
  return basisReady(member) && member.schedule?.status === 'ACTIVE' && due !== undefined && now !== undefined && due <= now
}
function selectMember(member: MemoryCramMember) {
  if (blocked.value || !membersReady.value || !basisReady(member) || (dirty.value && !store.canLeave([stateKey]))) return
  clearIntent(); intent.value = copy(member); writer.error.value = undefined; message.value = ''
}
function clearIntent() {
  intent.value = undefined; mode.value = 'EXTRA'; confirmed.value = false; beyondBudget.value = false; budgetRequired.value = false; needsReview.value = false
  recovered.value = undefined; recoverCardId.value = undefined; recoveryError.value = ''; recoverySequence++
}
function closeIntent() { if (!blocked.value && store.canLeave([stateKey])) clearIntent() }
function adoptCurrent() {
  if (blocked.value || !membersReady.value || !latestMember.value || !basisReady(latestMember.value)) return
  intent.value = copy(latestMember.value); needsReview.value = false; confirmed.value = false; beyondBudget.value = false; writer.error.value = undefined
  message.value = '已采用当前卡片、来源和排程条件；练习方式保留，请重新确认。'
}
function startBody(member: MemoryCramMember, batchId: string): MemoryStartAttempt {
  return { expectedCardRevision: member.currentCard!.revision, expectedGateRevision: member.gateRevision!,
    expectedScheduleRevision: countRegular.value ? member.schedule?.revision ?? null : null, mode: 'CRAM', countRegular: countRegular.value,
    cramBatchId: batchId, continueBeyondBudget: countRegular.value && beyondBudget.value }
}
function start() {
  if (!canStart.value || !intent.value || !current.value) return
  const fixed = copy(intent.value), batch = copy(current.value), body = startBody(fixed, batch.id)
  if (memoryStartAttemptProblem(body)) return
  recoverCardId.value = fixed.selection.cardId; recovered.value = undefined; recoveryError.value = ''; readyAttemptId.value = undefined
  command<MemoryAttemptDetail>(`/memory/cards/${fixed.selection.cardId}/attempts`, body, (value): value is MemoryAttemptDetail => startReceiptValid(value, fixed, batch, body), value => {
    const id = value.attempt.id
    clearIntent(); readyAttemptId.value = id; message.value = '本次尝试已开始，进入回忆页后独立作答。'
    void queryClient.invalidateQueries({ queryKey: ['memory'] }); openReadyAttempt()
  })
}
function startReceiptValid(value: unknown, fixed: MemoryCramMember, batch: MemoryCramBatch, body: MemoryStartAttempt): value is MemoryAttemptDetail {
  if (!memoryAttemptDetailValid(value, undefined, fixed.selection.cardId) || !fixed.currentCard) return false
  const attempt = value.attempt, basis = attempt.start, card = fixed.currentCard
  if (attempt.state !== 'THINKING' || attempt.revision !== 0 || attempt.lastEventSequence !== 1 || attempt.responseMode !== 'THOUGHT' || attempt.response !== '' || attempt.responseSavedAt !== null
    || basis.cardVersionId !== card.versionId || basis.cardVersionNumber !== card.versionNumber || basis.cardRevision !== body.expectedCardRevision || basis.gateRevision !== body.expectedGateRevision
    || basis.mode !== 'CRAM' || basis.countRegular !== body.countRegular || basis.cramBatchId !== batch.id
    || value.version.content.kind !== card.kind || value.version.content.title !== card.title) return false
  if (!body.countRegular) return basis.schedule === null && basis.admission === null && basis.zoneId === batch.zoneId
  return basis.schedule !== null && basis.admission !== null && fixed.schedule !== null && basis.schedule.revision === body.expectedScheduleRevision
    && basis.schedule.status === 'ACTIVE' && basis.schedule.state.phase === fixed.schedule.phase
    && micros(basis.schedule.effectiveDueAt) === micros(fixed.schedule.effectiveDueAt) && basis.admission.continueBeyondBudget === body.continueBeyondBudget
}
async function readOpenAttempt() {
  const id = recoverCardId.value
  if (!readable.value || locked.value || otherLocked.value || recoveryLoading.value || !memoryIdValid(id)) return
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++recoverySequence
  recoveryLoading.value = true; recovered.value = undefined; recoveryError.value = ''
  try {
    const value = await read<MemoryActiveAttempt>(`/memory/cards/${id}/active-attempt`)
    requireCurrent(epoch, stamp)
    if (sequence !== recoverySequence || id !== recoverCardId.value) return
    if (!memoryActiveAttemptValid(value, id)) throw invalid('未决尝试与所选卡片不一致，请重新读取。')
    recovered.value = value
  } catch (error) { if (alive && readable.value && epoch === store.sessionEpoch && stamp === generation && sequence === recoverySequence) recoveryError.value = errorText(error) }
  finally { if (sequence === recoverySequence) recoveryLoading.value = false }
}
function resumeAttempt(attempt: MemoryAttemptSummary) {
  if (blocked.value || !['THINKING', 'REVEALED'].includes(attempt.state) || !memoryIdValid(attempt.id) || !store.canLeave([stateKey])) return
  clearIntent(); readyAttemptId.value = attempt.id; openReadyAttempt()
}
function openReadyAttempt() { if (!blocked.value && memoryIdValid(readyAttemptId.value)) void router.push({ name: 'memory-recall', query: { attempt: readyAttemptId.value } }) }
function retry() { if (readable.value && !writer.busy.value && !otherLocked.value) { commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry() } }
function receiveDeferred() { if (readable.value && !otherLocked.value && deferred.value) deferred.value() }
function refresh() { if (!blocked.value && !creating.value) { confirmed.value = false; void detail.refetch(); if (current.value) void members.refetch() } }
function next() { if (!blocked.value && !intent.value && membersReady.value && memberPage.value?.nextAfter !== null && memberPage.value?.nextAfter !== undefined) { cursors.value.push(after.value); after.value = memberPage.value.nextAfter } }
function previous() { if (!blocked.value && !intent.value && !members.isFetching.value && cursors.value.length) after.value = cursors.value.pop() }
function openCard(id: string) { if (!blocked.value && memoryIdValid(id)) void router.push({ name: 'memory-cards', query: { item: id } }) }
function time(value: string) { try { return new Date(value).toLocaleString('zh-CN', current.value ? { timeZone: current.value.zoneId } : undefined) } catch { return value } }
function close() { if (readable.value && !locked.value && !otherLocked.value) emit('close') }
</script>

<template>
  <section class="cram-editor" aria-label="冲刺批次管理">
    <header class="editor-heading"><div><p class="section-label">集中回忆</p><h2>{{ !readable ? '冲刺批次' : creating ? '选定这次的练习范围' : detailReady ? current?.title : '读取冲刺批次' }}</h2></div><NButton text size="small" :disabled="!readable || locked || otherLocked" @click="close">关闭</NButton></header>
    <p v-if="!readable" class="notice" role="status">资料访问暂未恢复，内容已遮挡；批次输入、练习方式和原请求继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="editor-content">
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="writer.busy.value || otherLocked" :loading="writer.busy.value" @click="retry">用原请求核对</NButton></p>
      <p v-if="deferred" class="notice" role="status">此前操作已返回有效结果。<NButton text :disabled="otherLocked" @click="receiveDeferred">接收原操作结果</NButton></p>
      <p v-if="readyAttemptId" class="notice" role="status">尝试已保存，可从同一记录继续。<NButton text :disabled="blocked" @click="openReadyAttempt">进入这次回忆</NButton></p>
      <template v-if="creating">
        <p class="field-help">保存后保留这份范围、顺序、日期和时区。需要调整时创建新的批次，原来的练习记录继续保留。</p>
        <label>批次标题<NInput v-model:value="draft.title" :disabled="blocked || !!created" placeholder="例如：下周项目面前的缓存与事务回忆" /></label>
        <div class="date-fields"><label>开始日期<NInput v-model:value="draft.startDate" :disabled="blocked || !!created" placeholder="YYYY-MM-DD" /></label><label>结束日期<NInput v-model:value="draft.endDate" :disabled="blocked || !!created" placeholder="YYYY-MM-DD" /></label></div>
        <label>计划时区<NInput v-model:value="draft.zoneId" :disabled="blocked || !!created" placeholder="明确填写 Asia/Shanghai 或 UTC" /><span class="field-help">日期按这里的时区理解，包含首尾 1–90 天。允许保存过去的计划日期，系统不会自动顺延。</span></label>
        <MemoryCardPicker :selected="selected" :disabled="blocked || !!created" @update:selected="updateSelected" />
        <p v-if="createProblem && !created" class="field-help">{{ createProblem }}</p><div class="row-actions"><NButton v-if="!created" type="primary" :disabled="blocked || !!createProblem" :loading="writer.busy.value" @click="saveBatch">保存固定范围</NButton><NButton v-else type="primary" :disabled="blocked" @click="emit('saved', created)">打开已保存批次</NButton></div>
      </template>
      <template v-else>
        <p v-if="detail.isPending.value" role="status">正在读取批次…</p><p v-if="detail.error.value" class="notice error" role="alert">{{ errorText(detail.error.value) }}</p><NButton class="refresh-button" text size="small" :disabled="blocked || detail.isFetching.value || members.isFetching.value" @click="refresh">重新读取批次与当前条件</NButton>
        <template v-if="current && detailReady"><dl class="batch-summary"><dt>固定范围</dt><dd>{{ current.cards.length }} 张卡片</dd><dt>计划日期</dt><dd>{{ current.startDate }} 至 {{ current.endDate }} · {{ current.zoneId }}</dd><dt>建立时间</dt><dd>{{ time(current.createdAt) }} · {{ current.zoneId }}</dd></dl><p class="field-help">计划日期不自动启停练习，也不表示批次已经完成。每次开始使用卡片的当前版本，选入时版本保留作对照。</p></template>
        <p v-if="members.isPending.value && detailReady" role="status">正在读取成员当前条件…</p><p v-if="members.error.value" class="notice error" role="alert">{{ errorText(members.error.value) }}</p>
        <p v-if="memberPage && membersReady" class="field-help">当前条件读取于 {{ time(memberPage.asOf) }} · {{ current?.zoneId }}。开始时服务器还会核对来源、排程与预算。</p>
        <ol v-if="membersReady" class="member-list" :start="(after ?? 0) + 1"><li v-for="member in memberPage?.items" :key="member.position" :class="{ selected: intent?.position === member.position }"><div class="member-heading"><strong>{{ member.currentCard?.title ?? `卡片 ${member.selection.cardId.slice(0, 8)} 暂不可用` }}</strong><small>选入 v{{ member.selection.cardVersionNumber }}<template v-if="member.currentCard"> · 当前 v{{ member.currentCard.versionNumber }}</template></small></div>
          <p v-if="member.currentCard && member.currentCard.versionId !== member.selection.cardVersionId" class="notice">选入后内容已有新版本，本次开始将使用当前 v{{ member.currentCard.versionNumber }}。</p><p v-if="!member.currentCard" class="notice">当前卡片不可读取，固定范围仍保留原身份；不能为它开始新的尝试。</p>
          <template v-else><p class="field-help">{{ memoryKindLabels[member.currentCard.kind] }} · {{ member.currentCard.status === 'ACTIVE' ? '使用中' : '已归档' }} · 卡片修订 {{ member.currentCard.revision }}<template v-if="member.gateRevision !== null"> · 来源修订 {{ member.gateRevision }}</template></p><ul v-if="member.eligibility?.blockers.length" class="blocker-list"><li v-for="blocker in member.eligibility.blockers" :key="blocker">{{ memoryBlockReasonLabels[blocker] }}</li></ul><p v-if="member.eligibility?.warnings.length" class="notice">部分来源概念已归档，仍有其他有效依据；开始前请核对适用范围。</p>
            <p class="field-help">{{ member.schedule ? member.schedule.status === 'ACTIVE' ? `常规排程已启用 · 实际到期 ${time(member.schedule.effectiveDueAt)}` : `常规排程暂停 · ${memoryPauseReasonLabels[member.schedule.pauseReason!]}` : '尚无常规排程' }}<span v-if="member.schedule"> · {{ current?.zoneId }}</span></p>
            <p v-if="member.openAttempt" class="notice">这张卡已有{{ memoryAttemptModeLabels[member.openAttempt.mode] }}：{{ memoryAttemptStateLabels[member.openAttempt.state] }}，{{ member.openAttempt.countRegular ? '计入常规排程' : '额外练习' }}。<NButton text :disabled="blocked" @click="resumeAttempt(member.openAttempt)">明确恢复已有尝试</NButton></p>
            <div class="row-actions"><NButton size="small" :disabled="blocked || !basisReady(member)" @click="selectMember(member)">选择这张卡练习</NButton><NButton text size="small" :disabled="blocked" @click="openCard(member.selection.cardId)">管理卡片与来源</NButton></div>
          </template>
        </li></ol>
        <div class="row-actions"><NButton size="small" :disabled="blocked || !!intent || !cursors.length || members.isFetching.value" @click="previous">上一页成员</NButton><NButton size="small" :disabled="blocked || !!intent || !membersReady || memberPage?.nextAfter == null" @click="next">下一页成员</NButton></div>
        <section v-if="intent" class="start-form" aria-label="确认本次冲刺练习"><h3>本次练习：{{ intent.currentCard?.title }}</h3><p class="field-help">固定本次选择的当前 v{{ intent.currentCard?.versionNumber }}。选择是否计入后再开始，之后不能把同一尝试改成另一种口径。</p>
          <p v-if="stale || needsReview" class="notice">卡片、排程或访问条件已变化，练习方式保留。<NButton text :disabled="blocked" @click="refresh">读取当前条件</NButton><NButton text :disabled="blocked || !membersReady || !latestMember || !basisReady(latestMember)" @click="adoptCurrent">采用当前条件，重新确认</NButton></p>
          <label>本次练习方式<NSelect v-model:value="mode" :options="modeOptions" :disabled="blocked" /></label><p v-if="countRegular" class="field-help">需要启用且到期的常规排程，自评成功后更新间隔，并按评分时的设置统计每日回忆。开始前服务器会检查预算。</p><p v-else class="field-help">作为额外练习保留独立回答与自评，不调用常规排程算法，也不消耗每日建议数量。</p>
          <p v-if="countRegular && budgetRequired" class="notice">上次开始请求提示这张卡会超出每日建议数量。当前输入保留；核对最新条件后，可以明确选择继续。</p><NCheckbox v-if="countRegular && budgetRequired" v-model:checked="beyondBudget" :disabled="blocked || stale || needsReview || !membersReady">我确认本次可以超出每日建议数量。</NCheckbox>
          <p v-if="startProblem" class="field-help">{{ startProblem }}</p><NCheckbox v-model:checked="confirmed" :disabled="blocked || !membersReady || stale || needsReview || !!startProblem || (countRegular && budgetRequired && !beyondBudget)">我已核对当前版本及练习方式，开始这次独立回忆。</NCheckbox>
          <div class="row-actions"><NButton type="primary" :disabled="!canStart" :loading="writer.busy.value" @click="start">开始本次回忆</NButton><NButton text :disabled="blocked" @click="closeIntent">关闭本次选择</NButton></div>
        </section>
        <section v-if="recoverCardId && !writer.pending.value" class="recovery-section" aria-label="核对已有尝试"><h3>已有尝试</h3><p class="field-help">同一卡片只能保留一次未决尝试。读取后可明确恢复，系统不会把新的练习方式套到旧尝试上。</p><NButton text :disabled="blocked" :loading="recoveryLoading" @click="readOpenAttempt">读取这张卡的未决尝试</NButton><p v-if="recoveryError" class="notice error" role="alert">{{ recoveryError }}</p><p v-if="recovered && !recovered.attempt" class="field-help">这次读取没有未决尝试，请重读成员条件后再选择开始。</p><template v-if="recovered?.attempt"><p>{{ recovered.attempt.title }} · v{{ recovered.attempt.cardVersionNumber }}</p><p class="field-help">{{ memoryAttemptModeLabels[recovered.attempt.mode] }} · {{ memoryAttemptStateLabels[recovered.attempt.state] }} · {{ recovered.attempt.countRegular ? '计入常规排程' : '额外练习' }}<template v-if="recovered.attempt.cramBatchId && recovered.attempt.cramBatchId !== id"> · 来自另一冲刺批次</template></p><NButton :disabled="blocked" @click="resumeAttempt(recovered.attempt)">恢复这次已有尝试</NButton></template></section>
      </template>
    </div>
  </section>
</template>

<style scoped>
.cram-editor, .editor-content, .start-form, .recovery-section { display: grid; align-content: start; gap: 17px; min-width: 0; }.cram-editor { padding: 24px 27px 30px; }.editor-heading, .member-heading, .row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 16px; }.editor-heading, .member-heading { justify-content: space-between; }.editor-heading > div { min-width: 0; }.editor-heading h2 { margin: 7px 0; font-size: 23px; overflow-wrap: anywhere; }h3, p { margin: 0; }h3 { font-size: 16px; }.editor-content > label, .date-fields label, .start-form > label { display: grid; gap: 8px; font-size: 13px; }.date-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }.batch-summary { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 10px; margin: 0; font-size: 13px; }dt { color: var(--muted); }dd { margin: 0; overflow-wrap: anywhere; }.refresh-button, .recovery-section > .n-button { justify-self: start; }.member-list { list-style: none; margin: 0; padding: 0; }.member-list > li { display: grid; gap: 11px; padding: 19px 0; border-top: 1px solid var(--line); }.member-list > li.selected { border-top-color: var(--brand); }.member-heading strong { font-size: 14px; overflow-wrap: anywhere; }small { color: var(--muted); font-size: 12px; }.blocker-list { margin: 0; padding-left: 20px; color: var(--muted); font-size: 13px; }.start-form, .recovery-section { border-top: 1px solid var(--line); padding-top: 22px; }.start-form :deep(.n-checkbox) { align-items: start; }.start-form :deep(.n-checkbox__label) { white-space: normal; }.field-help { overflow-wrap: anywhere; }@media (max-width: 700px) { .cram-editor { padding: 22px 16px; }.date-fields { grid-template-columns: 1fr; }.batch-summary { grid-template-columns: 1fr; gap: 6px; }dd { margin-bottom: 8px; } }
</style>
