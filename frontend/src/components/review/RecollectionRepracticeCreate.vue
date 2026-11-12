<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NInputNumber, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { Recollection } from '../../api/recollection'
import { recollectionValid } from '../../api/recollection'
import type { ReviewAction, ReviewActionSource } from '../../api/review'
import { reviewIdValid } from '../../api/review'
import type { Repractice, RepracticeCreateRecollection, RepracticePick, RepracticeQuestionMode, RepracticeRecollectionBaseline } from '../../api/repractice'
import { repracticeBaselineKind, repracticeTextValid, repracticeValid } from '../../api/repractice'
import type { CollectionQuestionChoice } from '../../api/collections'
import type { PreparationItem, PreparationPage, PreparationSummary } from '../../api/preparation'
import type { TrainingContextKind, TrainingContextLink } from '../../api/training'
import { trainingContextLabels } from '../../api/training'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import CollectionQuestionPicker from '../bank/CollectionQuestionPicker.vue'
import RecollectionBaselineReadout from './RecollectionBaselineReadout.vue'
import { actionSourceObservation, actionSourceSignature, actionValid } from './reviewActionData'

const props = defineProps<{ recollectionId: string; versionId: string; actionId?: string; disabled?: boolean }>()
const emit = defineEmits<{ created: [value: Repractice] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const active = ref(true), current = shallowRef<Recollection>(), baseline = shallowRef<Recollection>()
const freshEpoch = ref(-1), readError = ref(''), title = ref(''), timeBudget = ref<number | null>(null), mode = ref<RepracticeQuestionMode>('SAME')
const variant = shallowRef<CollectionQuestionChoice>(), questionPicker = ref(false)
const selectedSources = ref<{ pick: RepracticePick; source: ReviewActionSource }[]>([])
const contexts = ref<{ link: TrainingContextLink; title: string; number: number }[]>([])
const contextPicker = ref(false), contextKind = ref<TrainingContextKind>('TARGET'), contextInput = ref(''), contextSearch = ref('')
const contextCursor = ref<string>(), contextPrevious = ref<(string | undefined)[]>([]), contextBusy = ref(false), contextError = ref('')
const confirmed = ref(false), receiptMismatch = ref(false), message = ref('')
const receiptCommand = shallowRef<NonNullable<typeof writer.pending.value>>(), deferred = shallowRef<() => void>(), created = shallowRef<Repractice>()
const state = reactive({ dirty: false, locked: false }), stateKey = `repractice-recollection-create-${props.recollectionId}-${props.versionId}`
let alive = true, generation = 0, contextRequest = 0, commandEpoch = store.sessionEpoch
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && reviewIdValid(props.recollectionId) && reviewIdValid(props.versionId))
const locked = computed(() => writer.locked.value || Boolean(deferred.value) || (receiptMismatch.value && Boolean(receiptCommand.value)))
const blocked = computed(() => !readable.value || locked.value || receiptMismatch.value || contextBusy.value)
const key = computed(() => ['repractice', store.sessionEpoch, 'recollection-baseline', props.recollectionId])
const recollection = useQuery({ queryKey: key, enabled: readable, staleTime: 0, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, id = props.recollectionId
  const value = await read<Recollection>(`/review/recollections/${id}`)
  requireCurrent(epoch, stamp)
  if (id !== props.recollectionId || !sourceReadValid(value)) throw mismatch('回忆修订或固定版本与已读取记录不一致，输入保留，请重新核对。')
  freshEpoch.value = epoch
  return value
} })
const action = useQuery({ queryKey: computed(() => ['repractice', store.sessionEpoch, 'recollection-action', props.actionId]),
  enabled: computed(() => readable.value && Boolean(props.actionId)), queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, id = props.actionId!
    const value = await read<ReviewAction>(`/review/actions/${id}`)
    requireCurrent(epoch, stamp)
    if (id !== props.actionId || value.id !== id || !actionValid(value)) throw mismatch('动作内容与所选动作不一致，请重新读取。')
    return value
  } })
const sourceOptions = computed(() => action.data.value?.sources.filter(sourceMatches) ?? [])
const sourcesStale = computed(() => selectedSources.value.some(chosen => !action.data.value || action.error.value || action.isFetching.value
  || chosen.pick.actionId !== action.data.value.id || chosen.pick.expectedActionRevision !== action.data.value.revision || action.data.value.status !== 'OPEN'
  || !action.data.value.sources.some(source => source.id === chosen.pick.sourceId && source.tracking === 'TRACKING'
    && sourceMatches(source) && actionSourceSignature(source) === actionSourceSignature(chosen.source))))
const sourceStale = computed(() => !baseline.value || !current.value || current.value.status !== 'ACTIVE' || baseline.value.status !== 'ACTIVE'
  || baseline.value.revision !== current.value.revision || baseline.value.currentVersion.id !== props.versionId || current.value.currentVersion.id !== props.versionId
  || versionSignature(baseline.value) !== versionSignature(current.value))
const baselineView = computed<RepracticeRecollectionBaseline | undefined>(() => baseline.value ? { recollectionId: baseline.value.id,
  revision: baseline.value.revision, version: baseline.value.currentVersion,
  state: current.value?.status === 'ARCHIVED' ? 'ARCHIVED' : current.value?.currentVersion.id === baseline.value.currentVersion.id ? 'CURRENT' : 'SUPERSEDED' } : undefined)
const materials = useQuery({ queryKey: computed(() => ['repractice', store.sessionEpoch, 'recollection-context-picker', contextKind.value, contextSearch.value, contextCursor.value]),
  enabled: computed(() => readable.value && contextPicker.value), queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, kind = contextKind.value, search = contextSearch.value, cursor = contextCursor.value
    const parameters = new URLSearchParams({ kind, status: 'ACTIVE', q: search, limit: '20' })
    if (kind === 'FACT') parameters.set('verification', 'CONFIRMED')
    if (cursor) parameters.set('cursor', cursor)
    const value = await read<PreparationPage>(`/prepare/items?${parameters}`)
    requireCurrent(epoch, stamp)
    if (kind !== contextKind.value || search !== contextSearch.value || cursor !== contextCursor.value) throw changed()
    if (!value || !Array.isArray(value.items) || value.items.length > 20 || value.items.some(item => !reviewIdValid(item.id) || item.kind !== kind || item.status !== 'ACTIVE'
      || !reviewIdValid(item.versionId) || typeof item.title !== 'string' || !Number.isSafeInteger(item.versionNumber) || item.versionNumber < 1
      || (kind === 'FACT' && item.verification !== 'CONFIRMED')) || (value.nextCursor !== null && !reviewIdValid(value.nextCursor))) throw mismatch('背景列表与当前选择条件不一致，请重新读取。')
    return value
  } })
const dirty = computed(() => Boolean(title.value || timeBudget.value !== null || mode.value !== 'SAME' || variant.value || selectedSources.value.length || contexts.value.length || confirmed.value))
const problem = computed(() => !current.value || !baseline.value ? '请先读取并核对所选回忆版本。'
  : readError.value || recollection.error.value ? '当前回忆读取尚未核对成功，请重读；表单输入保留。'
  : recollection.isFetching.value || freshEpoch.value !== store.sessionEpoch ? '正在重新核对当前回忆。'
  : current.value.status !== 'ACTIVE' ? '回忆已归档，请先回到回忆页恢复并重新核对。'
  : sourceStale.value ? '回忆修订或版本已变化，请核对当前来源；不会自动替换本次基线。'
  : !repracticeTextValid(title.value, 200) ? '请填写 1–200 个完整字符的复练名称。'
  : timeBudget.value !== null && (!Number.isInteger(timeBudget.value) || timeBudget.value < 1 || timeBudget.value > 180) ? '时间预算应为空或 1–180 的整数分钟。'
  : mode.value === 'VARIANT' && !variant.value ? '请选择本次变式的题库版本。'
  : sourcesStale.value ? '所选来源已变化或尚未读完，请核对后重新确认。' : '')

watch([recollection.data, recollection.dataUpdatedAt, readable, key], () => {
  if (!alive || !readable.value) return
  const value = queryClient.getQueryData<Recollection>(key.value)
  if (!value) return
  if (!sourceReadValid(value)) { readError.value = '缓存回忆与已读取版本不一致，请重读；输入保留。'; return }
  current.value = copy(value); readError.value = ''
  if (!baseline.value && value.currentVersion.id === props.versionId) baseline.value = copy(value)
}, { immediate: true })
watch([dirty, locked], ([changed, waiting]) => { state.dirty = changed; state.locked = waiting; store.changes.set(stateKey, state) }, { immediate: true, flush: 'sync' })
watch([title, timeBudget, mode, variant, contexts, selectedSources, current, baseline], () => { confirmed.value = false }, { deep: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => { generation++; contextRequest++; contextBusy.value = false; freshEpoch.value = -1; confirmed.value = false }, { flush: 'sync' })
watch(contextKind, () => { contextCursor.value = undefined; contextPrevious.value = []; contextRequest++ }, { flush: 'sync' })
watch(writer.error, error => {
  if (!(error instanceof ApiError) || writer.pending.value || receiptMismatch.value) return
  confirmed.value = false
  if (error.status === 409 && readable.value) { void recollection.refetch(); if (props.actionId) void action.refetch() }
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; contextRequest++; if (store.changes.get(stateKey) === state) store.changes.delete(stateKey) })

function copy(value: Recollection): Recollection { return JSON.parse(JSON.stringify(value)) as Recollection }
function versionSignature(value: Recollection): string {
  const version = value.currentVersion, content = version.content, interview = version.interview
  return JSON.stringify([version.id, version.number, version.createdAt, content.title, content.question, content.type, content.conditions, content.baselineKind,
    content.answerFragment, content.feedback, content.uncertainty, content.gap, interview ? [interview.eventId, interview.revision, interview.title,
      interview.round, interview.startAt, interview.zoneId, interview.status, interview.targetId, interview.targetVersionId] : null])
}
function sourceReadValid(value: Recollection): boolean {
  if (!recollectionValid(value, props.recollectionId)) return false
  const saved = current.value
  return !saved || (value.revision >= saved.revision && (value.revision !== saved.revision || (value.status === saved.status
    && value.createdAt === saved.createdAt && value.updatedAt === saved.updatedAt && versionSignature(value) === versionSignature(saved)))
    && (value.currentVersion.id !== saved.currentVersion.id || versionSignature(value) === versionSignature(saved)))
}
function changed() { return new ApiError(0, 'RECOLLECTION_CONTEXT_CHANGED', '回忆版本或访问条件已变化，请重新读取。') }
function mismatch(text: string) { return new ApiError(502, 'REVIEW_RECOLLECTION_REPRACTICE_MISMATCH', text) }
function requireCurrent(epoch: number, stamp: number) { if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation) throw changed() }
function sourceMatches(source: ReviewActionSource): boolean {
  const selected = baseline.value, identity = source.recollection
  return Boolean(selected && source.kind === 'RECOLLECTION' && source.answer === null && identity && identity.recollectionId === props.recollectionId
    && identity.versionId === props.versionId && identity.versionNumber === selected.currentVersion.number && identity.baselineKind === selected.currentVersion.content.baselineKind)
}
function adoptCurrentRevision() {
  const value = current.value
  if (blocked.value || !value || value.status !== 'ACTIVE' || value.currentVersion.id !== props.versionId || recollection.isFetching.value
    || recollection.error.value || readError.value || freshEpoch.value !== store.sessionEpoch) return
  // 仅允许明确采用同一版本的新对象修订；新补记版本必须从其自己的入口创建。
  baseline.value = copy(value); confirmed.value = false; message.value = `已采用回忆 v${value.currentVersion.number}、对象修订 ${value.revision}，复练输入保留。`
}
function toggleSource(source: ReviewActionSource) {
  const value = action.data.value
  if (blocked.value || !value || action.isFetching.value || action.error.value || value.status !== 'OPEN' || source.tracking !== 'TRACKING' || !sourceMatches(source)) return
  const index = selectedSources.value.findIndex(item => item.pick.sourceId === source.id)
  if (index >= 0) selectedSources.value.splice(index, 1)
  else if (selectedSources.value.length < 10) selectedSources.value.push({ pick: { actionId: value.id, expectedActionRevision: value.revision, sourceId: source.id }, source: JSON.parse(JSON.stringify(source)) as ReviewActionSource })
}
function refreshSelectedSources() {
  const value = action.data.value
  if (blocked.value || !value || action.isFetching.value || action.error.value || value.status !== 'OPEN') return
  if (selectedSources.value.some(chosen => !value.sources.some(source => source.id === chosen.pick.sourceId && source.tracking === 'TRACKING' && sourceMatches(source)))) {
    message.value = '有来源已移出或停止跟踪，请先取消对应选择。'; return
  }
  selectedSources.value = selectedSources.value.map(chosen => ({ pick: { ...chosen.pick, expectedActionRevision: value.revision }, source: JSON.parse(JSON.stringify(value.sources.find(source => source.id === chosen.pick.sourceId)!)) as ReviewActionSource }))
  confirmed.value = false; message.value = '已采用当前显示的来源与验证条件，请再次确认。'
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
    if (!value || value.id !== summary.id || value.kind !== kind || value.status !== 'ACTIVE' || value.currentVersion?.id !== summary.versionId
      || (kind === 'FACT' && value.verification !== 'CONFIRMED')) throw new ApiError(409, 'SOURCE_CHANGED', '这项背景已改版、归档或待核实，请重读后选择。')
    contexts.value.push({ link: { kind, itemId: value.id, versionId: value.currentVersion.id }, title: value.currentVersion.content.title, number: value.currentVersion.number })
  } catch (error) { if (alive && request === contextRequest) contextError.value = errorText(error) }
  finally { if (alive && request === contextRequest) contextBusy.value = false }
}
function create() {
  if (blocked.value || !baseline.value || !confirmed.value || problem.value) return
  const fixed = copy(baseline.value), fixedSources = JSON.parse(JSON.stringify(selectedSources.value)) as typeof selectedSources.value
  const body: RepracticeCreateRecollection = { expectedRevision: fixed.revision, versionId: fixed.currentVersion.id, title: title.value,
    timeBudgetMinutes: timeBudget.value, questionMode: mode.value,
    variant: mode.value === 'VARIANT' && variant.value ? { sourceQuestionId: variant.value.id, sourceVersionId: variant.value.versionId, stage: '独立复练' } : null,
    contextLinks: contexts.value.map(item => ({ ...item.link })), sources: fixedSources.map(item => ({ ...item.pick })) }
  commandEpoch = store.sessionEpoch
  let originalCommand: typeof writer.pending.value
  void writer.run<Repractice>(`/review/recollections/${fixed.id}/repractices`, 'POST', body, value => {
    if (!alive) return
    const saved = value?.recollectionBaseline
    if (!repracticeValid(value) || repracticeBaselineKind(value) !== fixed.currentVersion.content.baselineKind || value.baseline !== null
      || !saved || saved.recollectionId !== fixed.id || saved.revision !== fixed.revision || saved.state !== 'CURRENT'
      || versionSignature({ ...fixed, currentVersion: saved.version }) !== versionSignature(fixed)
      || value.questionMode !== body.questionMode || value.session.title !== body.title || value.session.status !== 'DRAFT'
      || value.session.mode !== 'FOCUSED' || value.session.materialAccess !== false || value.session.questionCount !== 1 || value.session.revision !== 0 || value.comparisons.length
      || value.sources.length !== fixedSources.length || value.sources.some(source => !fixedSources.some(chosen => chosen.pick.sourceId === source.sourceId
        && chosen.pick.actionId === source.actionId && chosen.pick.expectedActionRevision === source.actionRevision
        && actionSourceSignature(chosen.source) === actionSourceSignature(source.source)))) {
      receiptCommand.value = originalCommand; receiptMismatch.value = true; confirmed.value = false
      writer.error.value = mismatch('创建回执与本次回忆版本或复练条件不一致。输入保留，请用原请求核对已创建记录。'); return
    }
    const apply = () => {
      receiptMismatch.value = false; receiptCommand.value = undefined; writer.error.value = undefined
      title.value = ''; timeBudget.value = null; mode.value = 'SAME'; variant.value = undefined; contexts.value = []; selectedSources.value = []; confirmed.value = false
      // 新建复练由导航后的详情 GET 建立首次访问依据。
      created.value = value
      void queryClient.invalidateQueries({ queryKey: ['repractice'] }); emit('created', value)
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = apply
    else apply()
  })
  originalCommand = writer.pending.value
}
function retry() {
  if (!readable.value || writer.busy.value) return
  // 未决回执继续使用原正文与原幂等键，后来的回忆补记不能替换原命令。
  if (receiptMismatch.value && receiptCommand.value) writer.pending.value = receiptCommand.value
  commandEpoch = store.sessionEpoch; void writer.retry()
}
function receive() { if (readable.value && deferred.value) { const apply = deferred.value; deferred.value = undefined; apply() } }
function refreshBaseline() { if (readable.value && !locked.value) void recollection.refetch() }
function nextContext() { if (!blocked.value && !materials.isFetching.value && materials.data.value?.nextCursor) { contextPrevious.value.push(contextCursor.value); contextCursor.value = materials.data.value.nextCursor } }
</script>

<template>
  <section class="recollection-repractice" aria-label="从确切回忆版本创建复练">
    <header><p class="section-label">从面后回忆开始</p><h2>把记得的问题，独立答一次</h2><p class="field-help">新文字和图从空白开始；回忆片段、补记反馈和个人待补内容留在基线中，训练题面只使用你选择的问题。</p></header>
    <p v-if="!readable" class="notice">访问条件变化，资料暂时遮挡；已有输入和未决请求继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="form-content">
      <div class="row-actions"><NButton text :disabled="blocked || recollection.isFetching.value" @click="refreshBaseline">重读当前回忆</NButton><RouterLink class="text-link" :to="{ name: 'recollections', query: { item: recollectionId, versionId } }">返回这份回忆</RouterLink></div>
      <p v-if="recollection.isPending.value && !current" role="status">正在核对回忆来源…</p>
      <p v-if="recollection.error.value || readError" class="notice error" role="alert">{{ readError || errorText(recollection.error.value) }}</p>
      <p v-if="current && current.currentVersion.id !== versionId" class="notice">所选回忆版本已被后续补记替代。本页输入保留，请返回回忆页，从当前版本重新创建；不会把旧来源迁移到新版本。</p>
      <p v-else-if="current?.status === 'ARCHIVED'" class="notice">回忆已归档，暂不能创建新的复练。</p>
      <p v-else-if="baseline && current && sourceStale" class="notice">同一回忆版本的对象修订已变化。<NButton text :disabled="blocked || recollection.isFetching.value || !!recollection.error.value || !!readError" @click="adoptCurrentRevision">采用当前修订并保留输入</NButton></p>
      <RecollectionBaselineReadout v-if="baselineView" :baseline="baselineView" />
      <label>复练名称<NInput v-model:value="title" :disabled="blocked" placeholder="例如：独立说明这次没答清楚的事务边界" /></label>
      <div class="form-pair"><label>本次题面<NSelect v-model:value="mode" :options="[{ value: 'SAME', label: '使用这份回忆的问题与条件' }, { value: 'VARIANT', label: '从题库选择变式' }]" :disabled="blocked" /></label><label>时间预算（分钟，可留空）<NInputNumber v-model:value="timeBudget" :min="1" :max="180" :precision="0" :disabled="blocked" placeholder="只提醒，不自动提交" /></label></div>
      <template v-if="mode === 'VARIANT'"><p v-if="variant" class="field-help">已选 {{ variant.title }} · v{{ variant.versionNumber }}<NButton text size="small" :disabled="blocked" @click="questionPicker = true">更换</NButton></p><NButton v-else :disabled="blocked" @click="questionPicker = true">选择变式题</NButton><CollectionQuestionPicker v-if="questionPicker" :disabled="blocked" :exclude-question-ids="[]" @choose="chooseVariant" @close="questionPicker = false" /></template>
      <p v-else class="field-help">本题标为个人回忆来源、曾接触题面。只投影问题标题、问题文字、分类与记得的条件；没有旧答时也不会虚构基线答案或参考。</p>
      <section v-if="actionId" class="source-selection"><header><h3>本次要验证的来源（{{ selectedSources.length }} / 10）</h3><NButton text :disabled="blocked || action.isFetching.value" @click="action.refetch()">重读动作</NButton></header><p class="field-help">只选择这份回忆的同一确切版本；可以不绑定动作来源。</p><p v-if="action.error.value" class="notice error" role="alert">{{ errorText(action.error.value) }}</p><p v-if="action.data.value?.status === 'MERGED'" class="notice">动作已合并，请从当前动作重新选择来源。</p><p v-if="action.data.value && !sourceOptions.length" class="field-help">这个动作没有与所选回忆版本一致的来源。</p><label v-for="source in sourceOptions" :key="source.id" class="source-option"><NCheckbox :checked="selectedSources.some(item => item.pick.sourceId === source.id)" :disabled="blocked || action.isFetching.value || !!action.error.value || source.tracking !== 'TRACKING' || action.data.value?.status !== 'OPEN' || (selectedSources.length >= 10 && !selectedSources.some(item => item.pick.sourceId === source.id))" @update:checked="toggleSource(source)">{{ source.verification }}</NCheckbox><span class="field-help">{{ actionSourceObservation(source) }}{{ source.tracking === 'DROPPED' ? ' · 已停止跟踪' : '' }}</span></label><div v-if="sourcesStale" class="notice"><p>所选来源状态或验证条件待核对；旧选择保留。</p><ul><li v-for="item in selectedSources" :key="item.pick.sourceId">{{ item.source.verification }}<NButton text size="small" :disabled="blocked" @click="removeChosen(item.pick.sourceId)">取消选择</NButton></li></ul><NButton :disabled="blocked || action.isFetching.value || !!action.error.value" @click="refreshSelectedSources">采用当前显示的条件并重新确认</NButton></div></section>
      <section class="background"><header><h3>本次背景（{{ contexts.length }} / 12）</h3><NButton text :disabled="blocked" @click="contextPicker = !contextPicker">{{ contextPicker ? '收起选择' : '选择当前背景' }}</NButton></header><p class="field-help">明确选择最多 1 个目标、1 个项目和 10 条已确认事实。本次背景独立固定。</p><ul v-if="contexts.length"><li v-for="(item, index) in contexts" :key="item.link.itemId"><span>{{ trainingContextLabels[item.link.kind] }} · {{ item.title }} · v{{ item.number }}</span><NButton text size="small" :disabled="blocked" @click="contexts.splice(index, 1)">移出</NButton></li></ul><div v-if="contextPicker" class="context-picker"><NSelect v-model:value="contextKind" :options="Object.entries(trainingContextLabels).map(([value, label]) => ({ value, label }))" :disabled="blocked" aria-label="背景类型" /><form class="title-search" @submit.prevent="contextSearch = contextInput.trim(); contextCursor = undefined; contextPrevious = []"><NInput v-model:value="contextInput" :disabled="blocked" placeholder="查找背景标题" /><NButton attr-type="submit" :disabled="blocked">查找</NButton></form><p v-if="contextError || materials.error.value" class="notice error">{{ contextError || errorText(materials.error.value) }}</p><button v-for="item in materials.data.value?.items ?? []" :key="item.id" :disabled="blocked || materials.isFetching.value || !!materials.error.value || contexts.some(selected => selected.link.itemId === item.id)" class="context-choice" @click="chooseContext(item)">{{ item.title }} · v{{ item.versionNumber }}</button><p v-if="materials.data.value && !materials.data.value.items.length" class="field-help">没有匹配的当前背景。</p><div class="row-actions"><NButton size="small" :disabled="blocked || materials.isFetching.value || !contextPrevious.length" @click="contextCursor = contextPrevious.pop()">上一页</NButton><NButton size="small" :disabled="blocked || materials.isFetching.value || !materials.data.value?.nextCursor" @click="nextContext">下一页</NButton></div></div></section>
      <p v-if="problem" class="field-help">{{ problem }}</p><NCheckbox v-model:checked="confirmed" :disabled="blocked || !!problem">已核对确切回忆版本、基线身份、题面和所选来源；创建空白新尝试，结束后再选择正式提交</NCheckbox><NButton type="primary" :disabled="blocked || !confirmed || !!problem" :loading="writer.busy.value" @click="create">创建空白复练</NButton>
      <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value || (receiptMismatch && receiptCommand)" text :disabled="!readable" :loading="writer.busy.value" @click="retry">{{ receiptMismatch ? '用原请求核对已创建复练' : '用原请求重试' }}</NButton></p><p v-if="deferred" class="notice">服务器已确认创建，访问恢复后可接收。<NButton text :disabled="!readable" @click="receive">接收已创建复练</NButton></p><p v-if="created" class="notice">空白复练已保存。<NButton text :disabled="!readable" @click="emit('created', created)">查看这次复练</NButton></p>
    </div>
  </section>
</template>

<style scoped>
.recollection-repractice, .form-content { display: grid; gap: 20px; min-width: 0; } header h2 { margin: 5px 0 10px; font-size: 25px; } h3 { margin: 0; font-size: 16px; } p { margin: 0; } label { display: grid; gap: 8px; font-size: 13px; } .form-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
.source-selection, .background, .context-picker { display: grid; gap: 14px; padding: 18px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); } .source-selection header, .background header { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: baseline; gap: 12px; }
.source-option { padding: 14px 0; border-top: 1px solid var(--line); } .source-option .field-help { padding-left: 26px; white-space: pre-wrap; overflow-wrap: anywhere; }
ul { margin: 0; padding: 0; list-style: none; display: grid; gap: 9px; } li { display: flex; align-items: baseline; justify-content: space-between; gap: 14px; font-size: 13px; } .context-choice { padding: 10px; text-align: left; border: 1px solid var(--line); border-radius: 4px; background: var(--surface); font-size: 13px; } .context-choice:disabled { opacity: .5; } .row-actions { display: flex; gap: 9px; flex-wrap: wrap; } .form-content > .n-button { justify-self: start; }
@media (max-width: 650px) { .form-pair { grid-template-columns: 1fr; } .source-selection, .background { padding: 14px; } }
</style>
