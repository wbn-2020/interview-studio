<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NInputNumber, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiEvaluationAccess, AiEvaluationInput } from '../../api/ai'
import type { ReviewAction, ReviewActionSource, ReviewAnswerLink } from '../../api/review'
import { reviewAnswerKey, reviewAnswerMatches, reviewAnswerQuery, reviewIdValid } from '../../api/review'
import type { Repractice, RepracticeCreate, RepracticePick, RepracticeQuestionMode } from '../../api/repractice'
import { repracticeBaselineKind, repracticeTextValid, repracticeValid } from '../../api/repractice'
import type { TrainingContextKind, TrainingContextLink, TrainingSession } from '../../api/training'
import { notifyTrainingChanged, trainingContextLabels } from '../../api/training'
import type { CollectionQuestionChoice } from '../../api/collections'
import type { PreparationItem, PreparationPage, PreparationSummary } from '../../api/preparation'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import CollectionQuestionPicker from '../bank/CollectionQuestionPicker.vue'
import { actionAnswerValid, actionSourceObservation, actionSourceSignature, actionValid } from './reviewActionData'

const props = defineProps<{ baseline?: ReviewAnswerLink; actionId?: string; disabled?: boolean }>()
const emit = defineEmits<{ created: [value: Repractice] }>()
const store = useWorkspaceStore(), writer = useCommand(), accessWriter = useCommand()
const localBaseline = ref('')
const title = ref(''), timeBudget = ref<number | null>(null), mode = ref<RepracticeQuestionMode>('SAME')
const variant = shallowRef<CollectionQuestionChoice>(), questionPicker = ref(false)
const selectedSources = ref<{ pick: RepracticePick; source: ReviewActionSource }[]>([])
const contexts = ref<{ link: TrainingContextLink; title: string; number: number }[]>([])
const contextPicker = ref(false), contextKind = ref<TrainingContextKind>('TARGET'), contextInput = ref(''), contextSearch = ref('')
const contextCursor = ref<string>(), contextPrevious = ref<(string | undefined)[]>([]), contextBusy = ref(false), contextError = ref('')
const confirmed = ref(false), accessConfirmed = ref(false), receiptMismatch = ref(false), message = ref('')
const createReceipt = shallowRef<NonNullable<typeof writer.pending.value>>(), accessReceipt = shallowRef<NonNullable<typeof accessWriter.pending.value>>()
const deferred = shallowRef<() => void>(), accessDeferred = shallowRef<() => void>(), createdResult = shallowRef<Repractice>()
const state = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, contextRequest = 0, commandEpoch = store.sessionEpoch, accessEpoch = store.sessionEpoch
const readable = computed(() => store.sessionActive && !props.disabled)
const locked = computed(() => writer.locked.value || accessWriter.locked.value || Boolean(deferred.value) || Boolean(accessDeferred.value)
  || (receiptMismatch.value && Boolean(createReceipt.value || accessReceipt.value)))
const blocked = computed(() => !readable.value || locked.value || receiptMismatch.value || contextBusy.value)
const action = useQuery({ queryKey: computed(() => ['repractice', store.sessionEpoch, 'create-action', props.actionId]),
  enabled: computed(() => readable.value && Boolean(props.actionId)), queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, id = props.actionId!
    const value = await read<ReviewAction>(`/review/actions/${id}`)
    requireCurrent(epoch, stamp)
    if (id !== props.actionId || value.id !== id || !actionValid(value)) throw new ApiError(502, 'REVIEW_ACTION_MISMATCH', '动作内容与所选动作不一致。')
    return value
  } })
const answerGroups = computed(() => {
  const groups = new Map<string, { answer: ReviewAnswerLink; count: number }>()
  action.data.value?.sources.forEach(source => {
    if (source.kind === 'RECOLLECTION' || !actionAnswerValid(source.answer)) return
    const key = reviewAnswerKey(source.answer), group = groups.get(key)
    if (group) group.count++; else groups.set(key, { answer: source.answer, count: 1 })
  })
  return [...groups.entries()]
})
const baseline = computed(() => props.baseline ?? answerGroups.value.find(([key]) => key === localBaseline.value)?.[1].answer)
const identity = computed(() => baseline.value ? reviewAnswerKey(baseline.value) : '')
const sourceOptions = computed(() => action.data.value?.sources.filter(source => reviewAnswerMatches(source.answer, baseline.value)) ?? [])
const sourcesStale = computed(() => selectedSources.value.some(chosen => !action.data.value || chosen.pick.actionId !== action.data.value.id
  || chosen.pick.expectedActionRevision !== action.data.value.revision || action.data.value.status !== 'OPEN'
  || !action.data.value.sources.some(source => source.id === chosen.pick.sourceId && source.tracking === 'TRACKING' && reviewAnswerMatches(source.answer, baseline.value)
    && actionSourceSignature(source) === actionSourceSignature(chosen.source))))
const baselineSession = useQuery({ queryKey: computed(() => ['repractice', store.sessionEpoch, 'baseline-session', baseline.value?.sessionId]),
  enabled: computed(() => readable.value && Boolean(baseline.value)), queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, id = baseline.value!.sessionId
    const value = await read<TrainingSession>(`/training/sessions/${id}`)
    requireCurrent(epoch, stamp)
    if (id !== baseline.value?.sessionId || value.id !== id) throw changed()
    return value
  } })
const accessKey = computed(() => ['repractice', store.sessionEpoch, 'baseline-access', identity.value])
const accessQuery = useQuery({ queryKey: accessKey, enabled: computed(() => readable.value && Boolean(baseline.value)), queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, answer = baseline.value!
  const value = await read<AiEvaluationAccess>(`${evaluationPath(answer)}?answerId=${answer.answerId}`)
  requireCurrent(epoch, stamp)
  if (!value || typeof value.allowed !== 'boolean' || !reviewAnswerMatches(value, answer) || !reviewAnswerMatches(answer, baseline.value)) throw changed()
  return value
} })
const allowed = computed(() => Boolean(readable.value && accessQuery.data.value?.allowed && reviewAnswerMatches(accessQuery.data.value, baseline.value)))
const context = useQuery({ queryKey: computed(() => ['repractice', store.sessionEpoch, 'baseline-context', identity.value]), enabled: computed(() => allowed.value), queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, answer = baseline.value!
  const value = await read<AiEvaluationInput>(`/review/context?${reviewAnswerQuery(answer)}`)
  requireCurrent(epoch, stamp)
  if (!allowed.value || !reviewAnswerMatches(value, answer) || !reviewAnswerMatches(answer, baseline.value)) throw changed()
  return value
} })
const materials = useQuery({ queryKey: computed(() => ['repractice', store.sessionEpoch, 'context-picker', contextKind.value, contextSearch.value, contextCursor.value]), enabled: computed(() => readable.value && contextPicker.value), queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, kind = contextKind.value, search = contextSearch.value, cursor = contextCursor.value
  const parameters = new URLSearchParams({ kind, status: 'ACTIVE', q: search, limit: '20' }); if (kind === 'FACT') parameters.set('verification', 'CONFIRMED'); if (cursor) parameters.set('cursor', cursor)
  const value = await read<PreparationPage>(`/prepare/items?${parameters}`)
  requireCurrent(epoch, stamp)
  if (kind !== contextKind.value || search !== contextSearch.value || cursor !== contextCursor.value) throw changed()
  if (!value || !Array.isArray(value.items) || value.items.some(item => !reviewIdValid(item.id) || item.kind !== kind || item.status !== 'ACTIVE'
    || !reviewIdValid(item.versionId) || typeof item.title !== 'string' || !Number.isSafeInteger(item.versionNumber) || item.versionNumber < 1
    || (item.kind === 'FACT' && item.verification !== 'CONFIRMED')) || (value.nextCursor !== null && !reviewIdValid(value.nextCursor))) throw new ApiError(502, 'REVIEW_CONTEXT_MISMATCH', '背景列表与当前选择条件不一致，请重新读取。')
  return value
} })
const dirty = computed(() => Boolean(title.value || timeBudget.value !== null || mode.value !== 'SAME' || variant.value || selectedSources.value.length || contexts.value.length || confirmed.value || accessConfirmed.value))
const problem = computed(() => !baseline.value ? '请选择一份确切原答。' : baselineSession.data.value?.status !== 'ENDED' ? '请先结束原训练，再从确切原答开始复练。'
  : !allowed.value ? '请先确认打开原答的复盘上下文。' : !repracticeTextValid(title.value, 200) ? '请填写 1–200 个完整字符的复练名称。'
  : timeBudget.value !== null && (!Number.isInteger(timeBudget.value) || timeBudget.value < 1 || timeBudget.value > 180) ? '时间预算应为空或 1–180 的整数分钟。'
  : mode.value === 'VARIANT' && !variant.value ? '请选择本次变式的题库版本。' : sourcesStale.value ? '所选来源已变化，请核对最新来源后重新确认。' : '')
watch([dirty, locked], ([changed, waiting]) => { state.dirty = changed; state.locked = waiting; store.changes.set('repractice-create', state) }, { immediate: true, flush: 'sync' })
watch([title, timeBudget, mode, variant, contexts, selectedSources], () => { confirmed.value = false }, { deep: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => { generation++; contextRequest++; contextBusy.value = false; confirmed.value = false; accessConfirmed.value = false }, { flush: 'sync' })
watch(contextKind, () => { contextCursor.value = undefined; contextPrevious.value = []; contextRequest++ }, { flush: 'sync' })
watch(writer.error, error => { if (error instanceof ApiError && !writer.pending.value) { confirmed.value = false; if (error.status === 409 && readable.value) { if (props.actionId) void action.refetch(); if (baseline.value) void baselineSession.refetch() } } })
watch(accessWriter.error, error => { if (error instanceof ApiError && !accessWriter.pending.value) { accessConfirmed.value = false; if (error.status === 409 && readable.value) void baselineSession.refetch() } })
onUnmounted(() => { alive = false; generation++; contextRequest++; if (store.changes.get('repractice-create') === state) store.changes.delete('repractice-create') })
function changed() { return new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '复练来源或访问条件已变化，请重新读取。') }
function requireCurrent(epoch: number, stamp: number) { if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation) throw changed() }
function evaluationPath(answer: ReviewAnswerLink) { return `/training/sessions/${answer.sessionId}/questions/${answer.questionId}/evaluations/access` }
function chooseBaseline(value: string) {
  if (blocked.value || !store.canLeave(['repractice-create'])) return
  localBaseline.value = value; selectedSources.value = []; confirmed.value = false; accessConfirmed.value = false
}
function toggleSource(source: ReviewActionSource) {
  if (blocked.value || !action.data.value || action.data.value.status !== 'OPEN' || source.tracking !== 'TRACKING' || !reviewAnswerMatches(source.answer, baseline.value)) return
  const index = selectedSources.value.findIndex(item => item.pick.sourceId === source.id)
  if (index >= 0) selectedSources.value.splice(index, 1)
  else if (selectedSources.value.length < 10) selectedSources.value.push({ pick: { actionId: action.data.value.id, expectedActionRevision: action.data.value.revision, sourceId: source.id }, source: JSON.parse(JSON.stringify(source)) as ReviewActionSource })
}
function refreshSelectedSources() {
  if (blocked.value || !action.data.value || action.data.value.status !== 'OPEN') return
  const value = action.data.value
  if (selectedSources.value.some(chosen => !value.sources.some(source => source.id === chosen.pick.sourceId && source.tracking === 'TRACKING' && reviewAnswerMatches(source.answer, baseline.value)))) { message.value = '有来源已移出或停止跟踪，请取消对应选择后再核对。'; return }
  selectedSources.value = selectedSources.value.map(chosen => ({ pick: { ...chosen.pick, expectedActionRevision: value.revision }, source: JSON.parse(JSON.stringify(value.sources.find(source => source.id === chosen.pick.sourceId)!)) as ReviewActionSource }))
  message.value = '已采用当前显示的来源与验证条件，请再次确认。'
}
function removeChosen(id: string) { if (!blocked.value) selectedSources.value = selectedSources.value.filter(item => item.pick.sourceId !== id) }
function chooseVariant(value: CollectionQuestionChoice) { if (!blocked.value) { variant.value = value; questionPicker.value = false } }
async function chooseContext(summary: PreparationSummary) {
  if (blocked.value || contexts.value.some(item => item.link.itemId === summary.id) || contexts.value.length >= 12) return
  const kind = contextKind.value, maximum = kind === 'FACT' ? 10 : 1
  if (contexts.value.filter(item => item.link.kind === kind).length >= maximum) { contextError.value = `${trainingContextLabels[kind]}最多 ${maximum} 项。`; return }
  const epoch = store.sessionEpoch, stamp = generation, request = ++contextRequest
  contextBusy.value = true; contextError.value = ''
  try {
    const value = await read<PreparationItem>(`/prepare/items/${summary.id}`)
    requireCurrent(epoch, stamp)
    if (request !== contextRequest || kind !== contextKind.value) return
    if (!value || value.id !== summary.id || value.kind !== kind || value.status !== 'ACTIVE' || value.currentVersion?.id !== summary.versionId || (kind === 'FACT' && value.verification !== 'CONFIRMED')) throw new ApiError(409, 'SOURCE_CHANGED', '这项背景已改版、归档或待核实，请重读后选择。')
    contexts.value.push({ link: { kind, itemId: value.id, versionId: value.currentVersion.id }, title: value.currentVersion.content.title, number: value.currentVersion.number })
  } catch (error) { if (alive && request === contextRequest) contextError.value = errorText(error) }
  finally { if (alive && request === contextRequest) contextBusy.value = false }
}
function grantAccess() {
  const answer = baseline.value, session = baselineSession.data.value
  if (blocked.value || !answer || !session || session.status !== 'ENDED' || !accessConfirmed.value) return
  accessEpoch = store.sessionEpoch
  let originalCommand: typeof accessWriter.pending.value
  void accessWriter.run<AiEvaluationAccess>(evaluationPath(answer), 'POST', { answerId: answer.answerId, expectedSessionRevision: session.revision, confirmed: true, earlyFeedbackConfirmed: false }, value => {
    if (!alive) return
    if (value?.allowed !== true || !reviewAnswerMatches(value, answer)) { accessReceipt.value = originalCommand; receiptMismatch.value = true; accessWriter.error.value = new ApiError(502, 'REVIEW_ACCESS_MISMATCH', '访问回执与原答不一致，请用原请求核对确认结果。'); return }
    const apply = () => { receiptMismatch.value = false; accessReceipt.value = undefined; accessWriter.error.value = undefined; queryClient.setQueryData(['repractice', store.sessionEpoch, 'baseline-access', reviewAnswerKey(answer)], value); accessConfirmed.value = false; notifyTrainingChanged() }
    if (!readable.value || accessEpoch !== store.sessionEpoch) accessDeferred.value = apply
    else apply()
  })
  originalCommand = accessWriter.pending.value
}
function create() {
  const answer = baseline.value
  if (blocked.value || !answer || !confirmed.value || problem.value) return
  const fixedSources = JSON.parse(JSON.stringify(selectedSources.value)) as typeof selectedSources.value
  const body: RepracticeCreate = { baseline: { ...answer }, title: title.value, timeBudgetMinutes: timeBudget.value, questionMode: mode.value,
    variant: mode.value === 'VARIANT' && variant.value ? { sourceQuestionId: variant.value.id, sourceVersionId: variant.value.versionId, stage: '独立复练' } : null,
    contextLinks: contexts.value.map(item => ({ ...item.link })), sources: fixedSources.map(item => ({ ...item.pick })) }
  commandEpoch = store.sessionEpoch
  let originalCommand: typeof writer.pending.value
  void writer.run<Repractice>('/review/repractices', 'POST', body, value => {
    if (!alive) return
    if (!repracticeValid(value) || repracticeBaselineKind(value) !== 'ANSWER' || !reviewAnswerMatches(value.baseline, body.baseline) || value.questionMode !== body.questionMode || value.session.title !== body.title
      || value.session.status !== 'DRAFT' || value.session.mode !== 'FOCUSED' || value.sources.length !== fixedSources.length || value.comparisons.length
      || value.sources.some(source => !fixedSources.some(chosen => chosen.pick.sourceId === source.sourceId && chosen.pick.actionId === source.actionId
        && chosen.pick.expectedActionRevision === source.actionRevision && actionSourceSignature(chosen.source) === actionSourceSignature(source.source)))) {
      createReceipt.value = originalCommand; receiptMismatch.value = true; confirmed.value = false; writer.error.value = new ApiError(502, 'REVIEW_REPRACTICE_MISMATCH', '创建回执与本次复练条件不一致。输入保留，请用原请求核对已创建复练。'); return
    }
    const apply = () => {
      receiptMismatch.value = false; createReceipt.value = undefined; writer.error.value = undefined
      title.value = ''; timeBudget.value = null; mode.value = 'SAME'; variant.value = undefined; contexts.value = []; selectedSources.value = []; confirmed.value = false
      // 新建复练由导航后的详情 GET 建立首次访问依据。
      createdResult.value = value
      void queryClient.invalidateQueries({ queryKey: ['repractice'] }); emit('created', value)
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = apply
    else apply()
  })
  originalCommand = writer.pending.value
}
function retry(kind: 'create' | 'access') {
  if (!readable.value || writer.busy.value || accessWriter.busy.value) return
  // 异常回执后只重放原正文和原幂等键，不能以当前表单另建一份复练。
  if (kind === 'create') { if (receiptMismatch.value && createReceipt.value) writer.pending.value = createReceipt.value; commandEpoch = store.sessionEpoch; void writer.retry() }
  else { if (receiptMismatch.value && accessReceipt.value) accessWriter.pending.value = accessReceipt.value; accessEpoch = store.sessionEpoch; void accessWriter.retry() }
}
function receive(kind: 'create' | 'access') { if (!readable.value) return; const saved = kind === 'create' ? deferred : accessDeferred; if (saved.value) { const apply = saved.value; saved.value = undefined; apply() } }
function nextContext() { if (!materials.isFetching.value && materials.data.value?.nextCursor) { contextPrevious.value.push(contextCursor.value); contextCursor.value = materials.data.value.nextCursor } }
function refreshBaseline() { if (!readable.value || !baseline.value) return; void baselineSession.refetch(); void accessQuery.refetch(); if (allowed.value) void context.refetch() }
</script>

<template>
  <section class="repractice-create" aria-label="创建空白独立复练">
    <header><p class="section-label">新的独立尝试</p><h2>带着明确问题，再答一次</h2><p class="field-help">新文字和图从空白开始。保留原答与当时条件，结束后再逐条验证改进。</p></header>
    <p v-if="action.error.value" class="notice error" role="alert">{{ errorText(action.error.value) }}<NButton text @click="action.refetch()">重读动作</NButton></p>
    <label v-if="!props.baseline && actionId">先选择这次复练的确切原答<NSelect :value="localBaseline || null" :options="answerGroups.map(([value, group], index) => ({ value, label: `原答 ${index + 1} · ${group.count} 条来源 · ${group.answer.answerId.slice(0, 8)}` }))" :disabled="blocked" placeholder="合并动作的不同原答分次复练" @update:value="chooseBaseline" /></label>
    <p v-if="!baseline" class="notice">从原答复盘或一条动作进入，选择确切提交后创建；不会自动把第一条来源当作基线。</p>
    <template v-else>
      <p v-if="baselineSession.isPending.value" role="status">正在读取原训练状态…</p><p v-if="baselineSession.error.value || accessQuery.error.value || context.error.value" class="notice error" role="alert">{{ errorText(baselineSession.error.value || accessQuery.error.value || context.error.value) }}<NButton text :disabled="blocked" @click="refreshBaseline">重新读取</NButton></p>
      <p v-if="baselineSession.data.value && baselineSession.data.value.status !== 'ENDED'" class="notice">原训练尚未结束。请先结束并保留确切提交，再开始独立复练。</p>
      <section v-if="!allowed" class="access-panel"><p>打开原答复盘上下文会读取原题、原答及个人参考，并保留一次访问记录；不发送模型请求。</p><NCheckbox v-model:checked="accessConfirmed" :disabled="blocked || baselineSession.data.value?.status !== 'ENDED'">确认打开这份确切提交的复盘上下文</NCheckbox><NButton :disabled="blocked || !accessConfirmed || baselineSession.data.value?.status !== 'ENDED'" :loading="accessWriter.busy.value" @click="grantAccess">打开原答上下文</NButton></section>
      <p v-if="context.data.value && allowed" class="baseline-title">原题：{{ context.data.value.question.title }}<span>第 {{ context.data.value.answerNumber }} 版提交 · 原训练 {{ baselineSession.data.value?.title }}</span></p>
      <label>复练名称<NInput v-model:value="title" :disabled="blocked" placeholder="例如：重新说明事务消息的补偿边界" /></label>
      <div class="form-pair"><label>本次题面<NSelect v-model:value="mode" :options="[{ value: 'SAME', label: '保留原题确切题面' }, { value: 'VARIANT', label: '从题库选择变式' }]" :disabled="blocked" /></label><label>时间预算（分钟，可留空）<NInputNumber v-model:value="timeBudget" :min="1" :max="180" :precision="0" :disabled="blocked" placeholder="只提醒，不自动提交" /></label></div>
      <template v-if="mode === 'VARIANT'"><p v-if="variant" class="field-help">已选 {{ variant.title }} · v{{ variant.versionNumber }}<NButton text size="small" :disabled="blocked" @click="questionPicker = true">更换</NButton></p><NButton v-else :disabled="blocked" @click="questionPicker = true">选择变式题</NButton><CollectionQuestionPicker v-if="questionPicker" :disabled="blocked" :exclude-question-ids="[]" @choose="chooseVariant" @close="questionPicker = false" /></template>
      <p v-else class="field-help">保留原题面与对应参考版本；历史题或原 AI 追问仍按原来源标记，既往曝光不会清零。</p>
      <section v-if="actionId" class="source-selection"><header><h3>本次要验证的来源（{{ selectedSources.length }} / 10）</h3><NButton text :disabled="blocked || action.isFetching.value" @click="action.refetch()">重读动作</NButton></header><p v-if="action.data.value?.status === 'MERGED'" class="notice">此动作已合并，请前往当前动作重新选择来源。</p><p v-if="action.data.value?.status === 'OPEN' && !sourceOptions.length" class="field-help">这个动作没有与当前原答完全一致的来源。可不绑定来源，或选择其他原答。</p><label v-for="source in sourceOptions" :key="source.id" class="source-option"><NCheckbox :checked="selectedSources.some(item => item.pick.sourceId === source.id)" :disabled="blocked || source.tracking !== 'TRACKING' || action.data.value?.status !== 'OPEN' || (selectedSources.length >= 10 && !selectedSources.some(item => item.pick.sourceId === source.id))" @update:checked="toggleSource(source)">{{ source.verification }}</NCheckbox><span class="field-help">{{ actionSourceObservation(source) }}{{ source.tracking === 'DROPPED' ? ' · 已停止跟踪' : '' }}</span></label><div v-if="sourcesStale" class="notice"><p>所选来源或验证条件已变化；旧选择保留。</p><ul><li v-for="item in selectedSources" :key="item.pick.sourceId">{{ item.source.verification }}<NButton text size="small" :disabled="blocked" @click="removeChosen(item.pick.sourceId)">取消选择</NButton></li></ul><NButton :disabled="blocked" @click="refreshSelectedSources">采用当前显示的条件并重新确认</NButton></div></section>
      <section class="background"><header><h3>本次背景（{{ contexts.length }} / 12）</h3><NButton text :disabled="blocked" @click="contextPicker = !contextPicker">{{ contextPicker ? '收起选择' : '选择当前背景' }}</NButton></header><p class="field-help">重新选择最多 1 个目标、1 个项目、10 条已确认事实；不静默复制原场背景。</p><ul v-if="contexts.length"><li v-for="(item, index) in contexts" :key="item.link.itemId"><span>{{ trainingContextLabels[item.link.kind] }} · {{ item.title }} · v{{ item.number }}</span><NButton text size="small" :disabled="blocked" @click="contexts.splice(index, 1)">移出</NButton></li></ul><div v-if="contextPicker" class="context-picker"><NSelect v-model:value="contextKind" :options="Object.entries(trainingContextLabels).map(([value, label]) => ({ value, label }))" :disabled="blocked" aria-label="背景类型" /><form class="title-search" @submit.prevent="contextSearch = contextInput.trim(); contextCursor = undefined; contextPrevious = []"><NInput v-model:value="contextInput" :disabled="blocked" placeholder="查找背景标题" /><NButton attr-type="submit" :disabled="blocked">查找</NButton></form><p v-if="contextError || materials.error.value" class="notice error">{{ contextError || errorText(materials.error.value) }}</p><button v-for="item in materials.data.value?.items ?? []" :key="item.id" :disabled="blocked || contexts.some(selected => selected.link.itemId === item.id)" class="context-choice" @click="chooseContext(item)">{{ item.title }} · v{{ item.versionNumber }}</button><p v-if="materials.data.value && !materials.data.value.items.length" class="field-help">没有匹配的当前背景。</p><div class="row-actions"><NButton size="small" :disabled="blocked || materials.isFetching.value || !contextPrevious.length" @click="contextCursor = contextPrevious.pop()">上一页</NButton><NButton size="small" :disabled="blocked || materials.isFetching.value || !materials.data.value?.nextCursor" @click="nextContext">下一页</NButton></div></div></section>
      <p v-if="problem" class="field-help">{{ problem }}</p><NCheckbox v-model:checked="confirmed" :disabled="blocked || !!problem">已核对原答、题面和所选来源；创建新的空白尝试，结束后再比较</NCheckbox><NButton type="primary" :disabled="blocked || !confirmed || !!problem" :loading="writer.busy.value" @click="create">创建空白复练</NButton>
    </template>
    <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value || (receiptMismatch && createReceipt)" text :disabled="!readable" :loading="writer.busy.value" @click="retry('create')">{{ receiptMismatch ? '用原请求核对已创建复练' : '用原请求重试' }}</NButton></p><p v-if="accessWriter.error.value" class="notice error" role="alert">{{ errorText(accessWriter.error.value) }}<NButton v-if="accessWriter.pending.value || (receiptMismatch && accessReceipt)" text :disabled="!readable" :loading="accessWriter.busy.value" @click="retry('access')">{{ receiptMismatch ? '用原请求核对访问' : '恢复原访问请求' }}</NButton></p>
    <p v-if="deferred" class="notice">创建已由服务器确认。<NButton text :disabled="!readable" @click="receive('create')">接收已创建复练</NButton></p><p v-if="accessDeferred" class="notice">访问已由服务器确认。<NButton text :disabled="!readable" @click="receive('access')">接收访问确认</NButton></p><p v-if="createdResult" class="notice">空白复练已保存。<NButton text :disabled="!readable" @click="emit('created', createdResult)">查看这次复练</NButton></p>
  </section>
</template>

<style scoped>
.repractice-create { display: grid; gap: 20px; min-width: 0; } header h2 { margin: 5px 0 10px; font-size: 25px; } h3 { margin: 0; font-size: 16px; } p { margin: 0; } label { display: grid; gap: 8px; font-size: 13px; } .form-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
.access-panel, .source-selection, .background, .context-picker { display: grid; gap: 14px; padding: 18px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); } .source-selection header, .background header { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: baseline; gap: 12px; }
.source-option { padding: 14px 0; border-top: 1px solid var(--line); } .source-option .field-help { padding-left: 26px; } .baseline-title { font-weight: 600; } .baseline-title span { display: block; margin-top: 6px; color: var(--muted); font-size: 12px; font-weight: 400; }
ul { margin: 0; padding: 0; list-style: none; display: grid; gap: 9px; } li { display: flex; align-items: baseline; justify-content: space-between; gap: 14px; font-size: 13px; } .context-choice { padding: 10px; text-align: left; border: 1px solid var(--line); border-radius: 4px; background: var(--surface); font-size: 13px; } .context-choice:disabled { opacity: .5; } .row-actions { display: flex; gap: 9px; flex-wrap: wrap; } .repractice-create > .n-button, .access-panel > .n-button { justify-self: start; }
@media (max-width: 650px) { .form-pair { grid-template-columns: 1fr; } .access-panel, .source-selection, .background { padding: 14px; } }
</style>
