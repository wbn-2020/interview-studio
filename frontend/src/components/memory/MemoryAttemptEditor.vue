<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, read } from '../../api/client'
import type { MemoryReference } from '../../api/memory'
import { memoryIdValid } from '../../api/memory'
import type { MemoryRecallState } from '../../api/memoryReview'
import { memoryCurrentRecallStateValid, memoryUtcMicros } from '../../api/memoryReview'
import type { MemoryAttempt, MemoryAttemptDetail, MemoryActiveAttempt, MemoryResponseMode, MemoryRating, MemoryRevealedAttempt, MemoryRatedAttempt } from '../../api/memoryAttempt'
import { memoryAttemptValid, memoryAttemptDetailValid, memoryActiveAttemptValid, memoryAttemptReferenceValid, memoryRevealedAttemptValid, memoryRatedAttemptValid,
  memorySaveResponseProblem, memoryRevealProblem, memoryRateProblem, memorySkipProblem, memoryAttemptErrorText as errorText,
  memoryAttemptModeLabels, memoryAttemptStateLabels, memoryResponseModeLabels, memoryRatingLabels, memoryInvalidationReasonLabels } from '../../api/memoryAttempt'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MarkdownPreview from '../MarkdownPreview.vue'
import MemoryAttemptHistory from './MemoryAttemptHistory.vue'

const props = defineProps<{ id: string; disabled?: boolean }>()
const emit = defineEmits<{ changed: []; close: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter(), writer = useCommand(), active = ref(true)
type CurrentRead = { detail: MemoryAttemptDetail; active: MemoryActiveAttempt; recall: MemoryRecallState }
type Draft = { responseMode: MemoryResponseMode; response: string }
const current = shallowRef<CurrentRead>(), currentEpoch = ref(-1), editBase = shallowRef<MemoryAttempt>()
const draft = reactive<Draft>({ responseMode: 'THOUGHT', response: '' }), initialSignature = ref(signature(draft))
const verified = ref(''), needsReview = ref(false), replaceConfirmed = ref(false), clearTextConfirmed = ref(false), thoughtConfirmed = ref(false)
const rating = ref<MemoryRating | null>(null), ratingConfirmed = ref(false), skipOpen = ref(false), skipReason = ref(''), skipConfirmed = ref(false)
const reference = shallowRef<MemoryReference>(), referenceEpoch = ref(-1), referenceGeneration = ref(-1), referenceLoading = ref(false), referenceError = ref('')
const message = ref(''), readError = ref(''), tab = ref<'answer' | 'history'>('answer')
const receiptMismatch = ref(false), receiptCommand = shallowRef<NonNullable<typeof writer.pending.value>>(), deferred = shallowRef<() => void>()
const stateKey = `memory-recall-attempt-${props.id}`, changes = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, referenceSequence = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && memoryIdValid(props.id))
const locked = computed(() => writer.locked.value || receiptMismatch.value || Boolean(deferred.value))
const otherLocked = computed(() => [...store.changes].some(([key, value]) => key.startsWith('memory-recall-') && key !== stateKey && value.locked))
const blocked = computed(() => !readable.value || locked.value || otherLocked.value)
const responseDirty = computed(() => signature(draft) !== initialSignature.value)
const dirty = computed(() => responseDirty.value || Boolean(rating.value || skipReason.value))
const visible = computed(() => readable.value && currentEpoch.value === store.sessionEpoch && Boolean(current.value))
const attempt = computed(() => current.value?.detail.attempt)
const stale = computed(() => Boolean(editBase.value && (!attempt.value || !same(editBase.value, attempt.value))))
const open = computed(() => attempt.value?.state === 'THINKING' || attempt.value?.state === 'REVEALED')
const slotMatches = computed(() => !open.value || Boolean(attempt.value && current.value?.active.attempt?.id === props.id
  && current.value.active.attempt.revision === attempt.value.revision && current.value.active.attempt.state === attempt.value.state))
const basisMatches = computed(() => {
  const value = attempt.value, recall = current.value?.recall
  return Boolean(value && recall && recall.cardStatus === 'ACTIVE' && recall.eligibility.basisReady && recall.cardVersionId === value.start.cardVersionId
    && recall.cardRevision === value.start.cardRevision && recall.gate?.revision === value.start.gateRevision
    && (!value.start.countRegular || (recall.schedule?.status === 'ACTIVE' && recall.schedule.revision === value.start.schedule?.revision && recall.schedule.cycleId === value.start.schedule?.cycleId)))
})
const key = computed(() => ['memory', store.sessionEpoch, 'recall-attempt', props.id])
watch([readable, () => store.sessionEpoch, () => props.id], () => {
  generation++; referenceSequence++; verified.value = ''; reference.value = undefined; referenceEpoch.value = -1; referenceGeneration.value = -1
  referenceLoading.value = false; referenceError.value = ''; replaceConfirmed.value = false; clearTextConfirmed.value = false; thoughtConfirmed.value = false; ratingConfirmed.value = false; skipConfirmed.value = false
  if (editBase.value) needsReview.value = true
}, { flush: 'sync' })
const detail = useQuery({ queryKey: key, enabled: readable, staleTime: 0, queryFn: async () => {
  const id = props.id, epoch = store.sessionEpoch, stamp = generation
  const value = await read<MemoryAttemptDetail>(`/memory/attempts/${id}`)
  requireCurrent(id, epoch, stamp)
  if (!readValid(value)) throw mismatch('尝试身份、固定版本或修订与已读取记录不一致，请重新核对。')
  const [activeAttempt, recall] = await Promise.all([
    read<MemoryActiveAttempt>(`/memory/cards/${value.attempt.cardId}/active-attempt`),
    read<MemoryRecallState>(`/memory/cards/${value.attempt.cardId}/recall-state`),
  ])
  requireCurrent(id, epoch, stamp)
  if (!memoryActiveAttemptValid(activeAttempt, value.attempt.cardId) || !memoryCurrentRecallStateValid(recall, value.attempt.cardId)) throw mismatch('未决目录或来源状态格式异常，请重新读取。')
  verified.value = `${epoch}/${stamp}`
  return { detail: value, active: activeAttempt, recall }
} })
watch([detail.data, detail.dataUpdatedAt, key, readable], () => {
  if (!readable.value || verified.value !== `${store.sessionEpoch}/${generation}`) return
  const value = queryClient.getQueryData<CurrentRead>(key.value)
  if (!value || !readValid(value.detail)) { if (value) readError.value = '服务器记录与当前固定尝试不一致，本地输入继续保留。'; return }
  current.value = value; currentEpoch.value = store.sessionEpoch; readError.value = ''
  const saved = value.detail.attempt
  // 后台读取只推进服务器对照；有本地改写时不替换原 CAS 基线。
  if (!editBase.value || (!dirty.value && !locked.value && !needsReview.value)) adopt(saved, false)
  else if (!locked.value && same(editBase.value, saved)) needsReview.value = false
  else if (!same(editBase.value, saved)) needsReview.value = true
}, { immediate: true })
const fresh = computed(() => visible.value && verified.value === `${store.sessionEpoch}/${generation}` && !detail.isFetching.value && !detail.error.value && !readError.value)
const editable = computed(() => !blocked.value && visible.value && attempt.value?.state === 'THINKING' && Boolean(editBase.value))
const mutationReady = computed(() => !blocked.value && fresh.value && slotMatches.value && !stale.value && !needsReview.value)
const responseProblem = computed(() => memorySaveResponseProblem(responseBody()))
const needsClearText = computed(() => draft.responseMode === 'THOUGHT' && Boolean(draft.response || editBase.value?.response))
const canSave = computed(() => mutationReady.value && attempt.value?.state === 'THINKING' && responseDirty.value && !responseProblem.value && (!needsClearText.value || clearTextConfirmed.value))
const canReveal = computed(() => mutationReady.value && attempt.value?.state === 'THINKING' && basisMatches.value && !responseProblem.value
  && thoughtConfirmed.value && (!needsClearText.value || clearTextConfirmed.value))
const referenceReady = computed(() => Boolean(visible.value && reference.value && attempt.value && referenceEpoch.value === store.sessionEpoch
  && referenceGeneration.value === generation && memoryAttemptReferenceValid(reference.value, attempt.value)))
const canRate = computed(() => mutationReady.value && attempt.value?.state === 'REVEALED' && basisMatches.value && referenceReady.value && !responseDirty.value
  && rating.value !== null && ratingConfirmed.value && !memoryRateProblem({ expectedRevision: attempt.value.revision, rating: rating.value }))
const skipProblem = computed(() => memorySkipProblem({ expectedRevision: editBase.value?.revision, reason: skipReason.value }))
const canSkip = computed(() => mutationReady.value && open.value && skipOpen.value && skipConfirmed.value && !skipProblem.value)
watch([dirty, locked], ([changed, waiting]) => { changes.dirty = changed; changes.locked = waiting; store.changes.set(stateKey, changes) }, { immediate: true, flush: 'sync' })
watch(draft, () => { thoughtConfirmed.value = false; clearTextConfirmed.value = false }, { deep: true, flush: 'sync' })
watch(rating, () => { ratingConfirmed.value = false }, { flush: 'sync' })
watch(skipReason, () => { skipConfirmed.value = false }, { flush: 'sync' })
watch(() => attempt.value?.revision, () => { thoughtConfirmed.value = false; ratingConfirmed.value = false; skipConfirmed.value = false; replaceConfirmed.value = false })
watch([writer.error, writer.pending], ([error, pending]) => {
  if (!(error instanceof ApiError) || pending || receiptMismatch.value) return
  if (error.status === 409) { needsReview.value = true; if (readable.value) void detail.refetch() }
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; referenceSequence++; reference.value = undefined; if (store.changes.get(stateKey) === changes) store.changes.delete(stateKey) })

function copy<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
function same(left: unknown, right: unknown): boolean {
  if (left === right) return true
  if (Array.isArray(left)) return Array.isArray(right) && left.length === right.length && left.every((item, index) => same(item, right[index]))
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object' || Array.isArray(right)) return false
  return Object.keys(left).length === Object.keys(right).length && Object.entries(left).every(([key, value]) => Object.hasOwn(right, key) && same(value, (right as Record<string, unknown>)[key]))
}
function signature(value: Draft) { return JSON.stringify([value.responseMode, value.response]) }
function formOf(value: MemoryAttempt): Draft { return { responseMode: value.responseMode, response: value.response } }
function identityMatches(value: MemoryAttempt, fixed: MemoryAttempt) { return value.id === fixed.id && value.cardId === fixed.cardId && value.sequence === fixed.sequence
  && value.startedAt === fixed.startedAt && same(value.start, fixed.start) }
function readValid(value: unknown): value is MemoryAttemptDetail {
  if (!memoryAttemptDetailValid(value, props.id)) return false
  const known = current.value?.detail
  if (!known) return true
  const next = value.attempt, prior = known.attempt
  if (!identityMatches(next, prior) || !same(value.version, known.version) || next.revision < prior.revision) return false
  if (next.revision === prior.revision) return same(value, known)
  if (prior.state !== 'THINKING' && prior.state !== 'REVEALED') return false
  return prior.state !== 'REVEALED' || (next.state !== 'THINKING' && next.revealedAt === prior.revealedAt && next.responseMode === prior.responseMode && next.response === prior.response)
}
function mismatch(text: string) { return new ApiError(502, 'MEMORY_ATTEMPT_RECEIPT_INVALID', text) }
function requireCurrent(id: string, epoch: number, stamp: number) { if (!alive || !readable.value || id !== props.id || epoch !== store.sessionEpoch || stamp !== generation) throw new ApiError(0, 'MEMORY_ATTEMPT_CONTEXT_CHANGED', '尝试或访问条件已变化，输入保留，请重新核对。') }
function adopt(value: MemoryAttempt, keepLocal: boolean) {
  editBase.value = copy(value); initialSignature.value = signature(formOf(value))
  if (!keepLocal) Object.assign(draft, formOf(value))
  needsReview.value = false; replaceConfirmed.value = false; thoughtConfirmed.value = false; clearTextConfirmed.value = false; ratingConfirmed.value = false; skipConfirmed.value = false
}
function adoptCurrent(keepLocal: boolean) {
  if (blocked.value || !fresh.value || !attempt.value || (!keepLocal && dirty.value && !replaceConfirmed.value)) return
  adopt(attempt.value, keepLocal); writer.error.value = undefined
  if (!keepLocal && !open.value) { rating.value = null; skipOpen.value = false; skipReason.value = '' }
  message.value = keepLocal ? '已采用当前尝试修订，本地文字继续保留。请核对后再保存或揭示。' : '已采用服务器保存的回答与尝试修订。'
}
function updateMode(value: MemoryResponseMode) { if (editable.value && Object.hasOwn(memoryResponseModeLabels, value)) draft.responseMode = value }
function updateResponse(value: string) { if (editable.value) draft.response = value }
function responseBody() { return { expectedRevision: editBase.value?.revision, responseMode: draft.responseMode, response: draft.responseMode === 'THOUGHT' ? '' : draft.response } }
function refresh() { if (readable.value && !locked.value) { verified.value = ''; void detail.refetch() } }
function afterCommand() { verified.value = ''; void queryClient.invalidateQueries({ queryKey: ['memory'] }); emit('changed') }
function run<T>(path: string, method: string, body: unknown, validate: (value: unknown) => value is T, accept: (value: T, allowReference: boolean) => void, deferAccept?: (value: T) => () => void) {
  if (blocked.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; message.value = ''
  let original: typeof writer.pending.value
  void writer.run<unknown>(path, method, body, value => {
    if (!alive) return
    let valid = false
    try { valid = validate(value) } catch { valid = false }
    if (!valid) { receiptMismatch.value = true; receiptCommand.value = original; writer.error.value = mismatch('回执与原尝试、版本或本次提交不一致。输入及原请求保留，请核对原请求结果。'); return }
    const changed = !readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation
    const apply = changed && deferAccept ? deferAccept(value as T) : () => accept(value as T, !changed)
    const receive = () => {
      deferred.value = undefined; receiptMismatch.value = false; receiptCommand.value = undefined; writer.error.value = undefined
      apply(); afterCommand()
    }
    // 迟到回执只确认原命令；恢复后重读当前状态，参考正文不跨访问代次展示。
    if (changed) deferred.value = receive
    else receive()
  })
  original = writer.pending.value
}
function receiptAttempt(value: unknown, fixed: MemoryAttempt): value is MemoryAttempt {
  return memoryAttemptValid(value, fixed.id, fixed.cardId) && identityMatches(value, fixed)
    && memoryUtcMicros(value.updatedAt)! >= memoryUtcMicros(fixed.updatedAt)!
}
function save() {
  if (!canSave.value || !editBase.value) return
  const fixed = copy(editBase.value), body = responseBody(), submitted = { responseMode: body.responseMode, response: body.response }
  if (memorySaveResponseProblem(body)) return
  const unchanged = fixed.responseMode === submitted.responseMode && fixed.response === submitted.response
  run<MemoryAttempt>(`/memory/attempts/${fixed.id}/response`, 'PUT', body,
    (value): value is MemoryAttempt => receiptAttempt(value, fixed) && value.state === 'THINKING' && value.lastEventSequence === fixed.lastEventSequence
      && value.responseMode === submitted.responseMode && value.response === submitted.response
      && (unchanged ? same(value, fixed) : value.revision === fixed.revision + 1 && value.responseSavedAt === value.updatedAt),
    value => { adopt(value, false); needsReview.value = true; message.value = '原请求已确认保存草稿，正在核对服务器当前尝试。保存不会揭示参考。' })
}
function reveal() {
  if (!canReveal.value || !editBase.value) return
  const fixed = copy(editBase.value), body = { ...responseBody(), thoughtConfirmed: true }
  if (memoryRevealProblem(body)) return
  run<MemoryRevealedAttempt>(`/memory/attempts/${fixed.id}/reveal`, 'POST', body,
    (value): value is MemoryRevealedAttempt => memoryRevealedAttemptValid(value, fixed.id, fixed.cardId) && receiptAttempt(value.detail.attempt, fixed)
      && same(value.detail.version, current.value?.detail.version) && value.detail.attempt.revision === fixed.revision + 1
      && value.detail.attempt.lastEventSequence === fixed.lastEventSequence + 1 && value.detail.attempt.responseMode === body.responseMode && value.detail.attempt.response === body.response,
    (value, allowReference) => {
      adopt(value.detail.attempt, false); needsReview.value = true
      if (allowReference) { reference.value = value.reference; referenceEpoch.value = store.sessionEpoch; referenceGeneration.value = generation }
      message.value = allowReference ? '揭示请求已确认。原回答已固定，当前状态核对后可对照参考并自评。' : '原揭示请求已确认，回答已固定。恢复当前状态后，请明确重新读取这次参考。'
    }, value => {
      const confirmedAttempt = copy(value.detail.attempt)
      return () => { adopt(confirmedAttempt, false); needsReview.value = true; message.value = '原揭示请求已确认，回答已固定。恢复当前状态后，请明确重新读取这次参考。' }
    })
}
function rate() {
  if (!canRate.value || !editBase.value || !rating.value) return
  const fixed = copy(editBase.value), body = { expectedRevision: fixed.revision, rating: rating.value }
  if (memoryRateProblem(body)) return
  run<MemoryRatedAttempt>(`/memory/attempts/${fixed.id}/rate`, 'POST', body,
    (value): value is MemoryRatedAttempt => memoryRatedAttemptValid(value, fixed.id, fixed.cardId) && receiptAttempt(value.attempt, fixed)
      && value.attempt.revision === fixed.revision + 1 && value.attempt.lastEventSequence === fixed.lastEventSequence + 1 && value.attempt.rating === body.rating
      && value.attempt.responseMode === fixed.responseMode && value.attempt.response === fixed.response && value.attempt.revealedAt === fixed.revealedAt,
    value => { adopt(value.attempt, false); needsReview.value = true; rating.value = null; ratingConfirmed.value = false
      message.value = `自评“${memoryRatingLabels[value.attempt.rating!]}”已确认。${value.attempt.start.countRegular ? '排程按本次评分更新。' : '本次额外练习已记录，常规排程未调整。'}` })
}
function skip() {
  if (!canSkip.value || !editBase.value) return
  const fixed = copy(editBase.value), body = { expectedRevision: fixed.revision, reason: skipReason.value }
  if (memorySkipProblem(body)) return
  run<MemoryAttempt>(`/memory/attempts/${fixed.id}/skip`, 'POST', body,
    (value): value is MemoryAttempt => receiptAttempt(value, fixed) && value.state === 'SKIPPED' && value.revision === fixed.revision + 1
      && value.lastEventSequence === fixed.lastEventSequence + 1 && value.skipReason === body.reason && value.responseMode === fixed.responseMode
      && value.response === fixed.response && value.responseSavedAt === fixed.responseSavedAt && value.revealedAt === fixed.revealedAt,
    value => { editBase.value = copy(value); needsReview.value = true; skipOpen.value = false; skipReason.value = ''; skipConfirmed.value = false; rating.value = null
      message.value = '本次已跳过，没有生成自评或推进排程。未提交的本地回答仍保留，可复制或明确采用服务器记录后离开。' })
}
async function recoverReference() {
  const fixed = attempt.value ? copy(attempt.value) : undefined
  if (blocked.value || !fresh.value || !fixed?.revealedAt || referenceLoading.value) return
  const id = props.id, epoch = store.sessionEpoch, stamp = generation, sequence = ++referenceSequence
  referenceLoading.value = true; referenceError.value = ''; reference.value = undefined
  try {
    const value = await read<MemoryReference>(`/memory/attempts/${id}/reference`)
    requireCurrent(id, epoch, stamp)
    if (sequence !== referenceSequence) return
    if (!attempt.value || !identityMatches(attempt.value, fixed) || attempt.value.revealedAt !== fixed.revealedAt || !memoryAttemptReferenceValid(value, fixed)) throw mismatch('参考与这次已揭示的确切卡片版本不一致，请重新核对。')
    reference.value = value; referenceEpoch.value = epoch; referenceGeneration.value = stamp
  } catch (error) { if (alive && epoch === store.sessionEpoch && stamp === generation && sequence === referenceSequence) referenceError.value = errorText(error) }
  finally { if (alive && sequence === referenceSequence) referenceLoading.value = false }
}
function retry() { if (readable.value && !writer.busy.value && !otherLocked.value) { if (receiptMismatch.value && receiptCommand.value) writer.pending.value = receiptCommand.value
  commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry() } }
function receiveDeferred() { if (readable.value && !otherLocked.value && deferred.value) deferred.value() }
function selectHistory(id: string) { if (!blocked.value && memoryIdValid(id)) void router.push({ name: 'memory-recall', query: { attempt: id } }) }
function openCard() { if (!blocked.value && attempt.value) void router.push({ name: 'memory-cards', query: { item: attempt.value.cardId, versionId: attempt.value.start.cardVersionId } }) }
function time(value: string | null) { if (!value) return '尚未发生'; try { return new Date(value).toLocaleString('zh-CN', attempt.value?.start.zoneId ? { timeZone: attempt.value.start.zoneId } : undefined) } catch { return value } }
</script>

<template>
  <section class="attempt-editor" aria-label="独立回忆与自评">
    <header class="editor-heading"><div><p class="section-label">先回忆，再对照</p><h2>{{ visible ? current?.detail.version.content.title : '主动回忆' }}</h2><p v-if="visible && attempt" class="field-help">{{ memoryAttemptModeLabels[attempt.start.mode] }} · 第 {{ attempt.sequence }} 次 · v{{ attempt.start.cardVersionNumber }} · {{ memoryAttemptStateLabels[attempt.state] }}</p></div><NButton text :disabled="locked || otherLocked" @click="emit('close')">返回队列</NButton></header>
    <p v-if="!readable" class="notice" role="status">资料访问暂未恢复，题面、回答和参考已遮挡。本地输入与原请求继续保留。</p>
    <template v-else>
      <p v-if="detail.isPending.value && !visible" role="status">正在核对当前尝试、未决占位与来源…</p>
      <p v-if="detail.error.value || readError" class="notice error" role="alert">{{ readError || errorText(detail.error.value) }}<NButton text :disabled="locked || detail.isFetching.value" @click="refresh">重新读取当前尝试</NButton></p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value || (receiptMismatch && receiptCommand)" text :loading="writer.busy.value" :disabled="otherLocked" @click="retry">核对原请求结果</NButton></p>
      <p v-if="deferred" class="notice" role="status">原命令已返回有效回执。<NButton text :disabled="otherLocked" @click="receiveDeferred">接收回执并重读当前状态</NButton></p>
      <template v-if="visible && current && attempt">
        <nav class="editor-tabs" aria-label="本次回忆与历史"><button :aria-pressed="tab === 'answer'" :disabled="locked" @click="tab = 'answer'">本次回忆{{ responseDirty ? ' · 本地输入待处理' : '' }}</button><button :aria-pressed="tab === 'history'" :disabled="locked" @click="tab = 'history'">本卡历史</button></nav>
        <div v-show="tab === 'answer'" class="answer-panel">
          <p class="field-help">{{ attempt.start.countRegular ? '本次自评会更新常规排程。' : '本次属于额外练习，自评仅记录此次结果。' }} 开始于 {{ time(attempt.startedAt) }}。</p>
          <p v-if="open && !slotMatches" class="notice">尝试与未决目录在读取期间发生变化，暂不能提交。请重新核对。<NButton text :disabled="locked" @click="refresh">重读当前状态</NButton></p>
          <p v-if="open && !basisMatches" class="notice">当前卡片、来源或排程与开始时依据不一致，暂不能揭示或自评。请重新读取当前记录，必要时处理这次尝试后再开始。</p>
          <section class="prompt"><h3>题面</h3><MarkdownPreview :value="current.detail.version.content.prompt" /></section>
          <section v-if="stale || needsReview" class="server-comparison" aria-label="尝试冲突对照"><h3>核对服务器当前记录</h3><p class="field-help">服务器修订 {{ attempt.revision }} · {{ memoryAttemptStateLabels[attempt.state] }}；本地输入不会被后台刷新替换。</p><NButton text :disabled="locked || detail.isFetching.value" @click="refresh">重新读取服务器记录</NButton><p v-if="attempt.responseMode === 'THOUGHT'" class="field-help">服务器记录：心中回忆，没有书面答案。</p><MarkdownPreview v-else :value="attempt.response" /><NCheckbox v-if="dirty" v-model:checked="replaceConfirmed" :disabled="blocked">允许采用服务器回答，放弃无法继续提交的本地输入</NCheckbox><div class="row-actions"><NButton :disabled="blocked || !fresh" @click="adoptCurrent(true)">保留本地输入，采用当前修订</NButton><NButton :disabled="blocked || !fresh || (dirty && !replaceConfirmed)" @click="adoptCurrent(false)">采用服务器回答与修订</NButton></div></section>
          <section v-if="attempt.state === 'THINKING'" class="response-form" aria-label="独立回答">
            <h3>先独立回忆</h3><p class="field-help">可以在心中形成答案，也可以写下来。保存草稿不会打开参考；揭示后本次回答固定。</p>
            <label>回答方式<NSelect :value="draft.responseMode" :options="Object.entries(memoryResponseModeLabels).map(([value, label]) => ({ value, label }))" :disabled="!editable" @update:value="updateMode" /></label>
            <label v-if="draft.responseMode === 'WRITTEN'">我的回答<NInput :value="readable ? draft.response : ''" type="textarea" :autosize="{ minRows: 7, maxRows: 22 }" :disabled="!editable" placeholder="先写下你能独立回忆的内容。" @update:value="updateResponse" /><span class="field-help">{{ Array.from(draft.response).length }} / 10000 字符 · {{ responseDirty ? '当前改写只保存在本页内存' : attempt.responseSavedAt ? `服务器已存于 ${time(attempt.responseSavedAt)}` : '尚未提交回答' }}</span></label>
            <div v-if="needsClearText" class="notice"><p>已切换为心中回忆。文字暂留本地；保存或揭示时会明确提交空回答。</p><NCheckbox v-model:checked="clearTextConfirmed" :disabled="blocked">确认清除这次已存或未存的文字回答，采用心中回忆方式</NCheckbox><details v-if="draft.response"><summary>查看暂留文字</summary><pre>{{ draft.response }}</pre></details></div>
            <p v-if="responseProblem" class="field-help">{{ responseProblem }}</p><NButton class="save-draft" :disabled="!canSave" :loading="writer.busy.value" @click="save">保存当前回答草稿</NButton>
            <div class="reveal-action"><NCheckbox v-model:checked="thoughtConfirmed" :disabled="!mutationReady || !basisMatches || !!responseProblem || (needsClearText && !clearTextConfirmed)">我已完成这次独立回忆，确认固定当前回答并揭示个人参考</NCheckbox><NButton type="primary" :disabled="!canReveal" :loading="writer.busy.value" @click="reveal">固定回答并揭示参考</NButton></div>
          </section>
          <section v-else class="fixed-answer"><h3>本次固定回答</h3><p v-if="attempt.responseMode === 'THOUGHT'" class="field-help">{{ attempt.revealedAt ? '本次明确选择了心中回忆，没有书面答案。' : '本次没有提交书面答案。' }}</p><MarkdownPreview v-else :value="attempt.response" /><p v-if="attempt.invalidation" class="notice">{{ memoryInvalidationReasonLabels[attempt.invalidation.reason] }}。这次尝试已失效，原记录继续保留。</p><p v-if="attempt.skipReason" class="reason">跳过理由：{{ attempt.skipReason }}</p><p v-if="attempt.rating" class="notice">本次用户自评：{{ memoryRatingLabels[attempt.rating] }} · {{ time(attempt.ratedAt) }}</p></section>
          <section v-if="(attempt.state !== 'THINKING' && responseDirty) || (!open && dirty)" class="local-draft"><h3>仍保留的本地输入</h3><p class="field-help">这些输入未替换服务器记录，也不能覆盖已经揭示或结束的回答。</p><pre v-if="responseDirty">{{ draft.response || '本地选择：心中回忆' }}</pre><p v-if="rating" class="field-help">尚未提交的自评选择：{{ memoryRatingLabels[rating] }}</p><pre v-if="skipReason">跳过理由草稿：{{ skipReason }}</pre><NCheckbox v-model:checked="replaceConfirmed" :disabled="blocked">允许丢弃本地输入，采用服务器记录</NCheckbox><NButton :disabled="blocked || !fresh || !replaceConfirmed" @click="adoptCurrent(false)">采用服务器记录并清除本地输入</NButton></section>
          <section v-if="attempt.revealedAt" class="reference-panel" aria-label="本次揭示的个人参考"><header><h3>个人参考 · v{{ attempt.start.cardVersionNumber }}</h3><NButton v-if="referenceReady" text size="small" :disabled="locked" @click="reference = undefined">收起参考</NButton></header><p class="field-help">此参考属于本次固定版本，由用户整理。请据此对照自己的回答。</p><MarkdownPreview v-if="referenceReady && reference" :value="reference.reference" /><NButton v-else :disabled="blocked || !fresh" :loading="referenceLoading" @click="recoverReference">明确读取本次已揭示参考</NButton><p v-if="referenceError" class="notice error" role="alert">{{ referenceError }}</p></section>
          <section v-if="attempt.state === 'REVEALED'" class="rating-panel" aria-label="明确自评"><h3>这次回忆怎么样</h3><p class="field-help">对照个人参考后，按此次实际回忆选择。选择和提交都由你完成。</p><div class="rating-options" role="group" aria-label="选择本次自评"><button v-for="(label, value) in memoryRatingLabels" :key="value" :aria-pressed="rating === value" :disabled="!mutationReady || !referenceReady || responseDirty || !basisMatches" @click="rating = value"><strong>{{ label }}</strong><span>{{ value === 'AGAIN' ? '未能回想起来' : value === 'HARD' ? '费力才想起' : value === 'GOOD' ? '能独立回想' : '回想顺畅且熟悉' }}</span></button></div><NCheckbox v-model:checked="ratingConfirmed" :disabled="!mutationReady || !rating || !referenceReady || !basisMatches || responseDirty">确认提交这次自评{{ rating ? `“${memoryRatingLabels[rating]}”` : '' }}</NCheckbox><NButton type="primary" :disabled="!canRate" :loading="writer.busy.value" @click="rate">提交自评</NButton></section>
          <section v-if="open" class="skip-panel"><NButton text :disabled="blocked" @click="skipOpen = !skipOpen">{{ skipOpen ? '收起跳过说明' : '暂不继续，填写跳过理由' }}</NButton><template v-if="skipOpen"><label>跳过理由<NInput v-model:value="skipReason" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" :disabled="blocked" placeholder="记录为什么结束这次尝试" /></label><p v-if="skipProblem" class="field-help">{{ skipProblem }}</p><NCheckbox v-model:checked="skipConfirmed" :disabled="!mutationReady || !!skipProblem">确认结束这次尝试；跳过不生成评分，也不会提交尚未保存的回答</NCheckbox><NButton :disabled="!canSkip" :loading="writer.busy.value" @click="skip">确认跳过本次</NButton></template></section>
          <div class="row-actions"><NButton text :disabled="blocked" @click="openCard">查看本次卡片版本与来源</NButton><NButton text :disabled="locked || detail.isFetching.value" @click="refresh">核对当前尝试</NButton><NButton v-if="current.active.attempt && current.active.attempt.id !== id" :disabled="blocked" @click="selectHistory(current.active.attempt.id)">恢复这张卡的新一次回忆</NButton></div>
        </div>
        <MemoryAttemptHistory v-show="tab === 'history'" :card-id="attempt.cardId" :disabled="!readable || tab !== 'history'" @select="selectHistory" />
      </template>
    </template>
  </section>
</template>

<style scoped>
.attempt-editor, .answer-panel, .response-form, .fixed-answer, .reference-panel, .rating-panel, .skip-panel, .server-comparison, .local-draft { display: grid; gap: 17px; min-width: 0; }.attempt-editor { padding: 26px 30px; align-content: start; }.editor-heading, .reference-panel > header, .row-actions { display: flex; gap: 12px; flex-wrap: wrap; align-items: baseline; }.editor-heading, .reference-panel > header { justify-content: space-between; }.editor-heading > div { min-width: 0; display: grid; gap: 7px; }h2, h3, p { margin: 0; }h2 { font-size: 23px; overflow-wrap: anywhere; }h3 { font-size: 16px; }.editor-tabs { display: flex; gap: 23px; border-bottom: 1px solid var(--line); }.editor-tabs button { padding: 10px 0; border-bottom: 2px solid transparent; color: var(--muted); font-size: 13px; }.editor-tabs button[aria-pressed=true] { color: var(--brand-strong); border-bottom-color: var(--brand); font-weight: 650; }.editor-tabs button:focus-visible, .rating-options button:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }.prompt, .fixed-answer { display: grid; gap: 13px; padding: 5px 0 20px; border-bottom: 1px solid var(--line); }.response-form > label, .skip-panel > label { display: grid; gap: 8px; font-size: 13px; }.save-draft, .reference-panel > .n-button, .rating-panel > .n-button, .skip-panel > .n-button, .local-draft > .n-button { justify-self: start; }.reveal-action { display: grid; gap: 15px; justify-items: start; padding-top: 17px; border-top: 1px solid var(--line); }.reference-panel, .server-comparison, .local-draft { padding: 18px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 7px; }.rating-options { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 9px; }.rating-options button { display: grid; gap: 8px; padding: 15px 11px; text-align: left; border: 1px solid var(--line); border-radius: 6px; }.rating-options button[aria-pressed=true] { border-color: var(--brand); background: var(--surface-soft); }.rating-options strong { font-size: 14px; }.rating-options span { font-size: 12px; color: var(--muted); }.skip-panel { padding-top: 18px; border-top: 1px solid var(--line); }pre, .reason { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; font-size: 13px; }pre { margin: 0; max-height: 320px; overflow-y: auto; }.attempt-editor :deep(.markdown-preview) { max-width: 78ch; overflow-wrap: anywhere; }.attempt-editor :deep(.n-checkbox) { align-items: start; }.attempt-editor :deep(.n-checkbox__label) { white-space: normal; }@media (max-width: 800px) { .attempt-editor { padding: 22px 17px; }.rating-options { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
