<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import type { ReviewAction, ReviewActionHistoryPage, ReviewActionPage, ReviewMergeAction, ReviewSaveAction, ReviewSaveSource } from '../../api/review'
import { reviewDate, reviewIdValid } from '../../api/review'
import { questionTextLength } from '../../api/bank'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { actionSourceObservation, actionSourcesMatch, actionTextProblem, actionValid, sourceTextProblem } from './reviewActionData'
import ReviewActionSourceView from './ReviewActionSourceView.vue'

const props = defineProps<{ id: string; disabled?: boolean }>()
const emit = defineEmits<{ changed: []; close: []; open: [id: string]; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const current = shallowRef<ReviewAction>()
const latest = shallowRef<ReviewAction>()
const serverCopy = shallowRef<ReviewAction>()
const serverLoading = ref(false)
const readError = ref('')
const message = ref('')
const needsReview = ref(false)
const receiptMismatch = ref(false)
const deferred = shallowRef<() => void>()
const tab = ref<'description' | 'sources' | 'merge' | 'history'>('description')
const title = ref('')
const detailText = ref('')
const sourceId = ref('')
const verification = ref('')
const tracking = ref<ReviewSaveSource['tracking']>('TRACKING')
const trackingReason = ref('')
const mergeSource = shallowRef<ReviewAction>()
const mergeLoading = ref(false)
const mergeError = ref('')
const mergeConfirmed = ref(false)
const mergeCursor = ref<string>()
const mergeCursors = ref<(string | undefined)[]>([])
const historyBefore = ref<number>()
const historyCursors = ref<(number | undefined)[]>([])
const readable = computed(() => store.sessionActive && !props.disabled)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || serverLoading.value || mergeLoading.value)
const selectedSource = computed(() => current.value?.sources.find(source => source.id === sourceId.value))
const sourceOptions = computed(() => current.value?.sources.map((source, index) => ({ value: source.id,
  label: `${index + 1}. ${source.kind === 'RECOLLECTION' ? '回忆 · ' : ''}${actionSourceObservation(source).slice(0, 60)} · ${source.tracking === 'TRACKING' ? '跟踪中' : '停止跟踪'}` })) ?? [])
const dirty = computed(() => Boolean(current.value) && (tab.value === 'description' ? title.value !== current.value?.title || detailText.value !== current.value?.detail
  : tab.value === 'sources' ? Boolean(selectedSource.value && (verification.value !== selectedSource.value.verification || tracking.value !== selectedSource.value.tracking || trackingReason.value !== selectedSource.value.trackingReason))
  : tab.value === 'merge' && Boolean(mergeSource.value)))
const stale = computed(() => Boolean(current.value && latest.value && current.value.revision < latest.value.revision))
const editable = computed(() => !blocked.value && !receiptMismatch.value && !needsReview.value && !stale.value && current.value?.status === 'OPEN' && current.value.revision < 1000)
const descriptionProblem = computed(() => actionTextProblem(title.value, detailText.value))
const sourceProblem = computed(() => sourceTextProblem(verification.value, tracking.value, trackingReason.value))
const mergeProblem = computed(() => !mergeSource.value ? '选择要并入当前动作的另一条动作。'
  : mergeSource.value.status !== 'OPEN' || mergeSource.value.revision >= 1000 ? '所选动作已合并或已达修订上限，请重新选择。'
  : (current.value?.sources.length ?? 0) + mergeSource.value.sources.length > 20 ? '合并后超过 20 条来源，请选择其他动作。' : '')
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let detailSequence = 0
let comparisonSequence = 0
let mergeSequence = 0
let commandEpoch = store.sessionEpoch

const action = useQuery({ queryKey: computed(() => ['review-actions', store.sessionEpoch, 'detail', props.id]), enabled: computed(() => readable.value && reviewIdValid(props.id)),
  queryFn: async () => {
    const id = props.id, epoch = store.sessionEpoch, sequence = ++detailSequence
    const value = await read<ReviewAction>(`/review/actions/${id}`)
    if (!alive || !readable.value || id !== props.id || epoch !== store.sessionEpoch || sequence !== detailSequence) throw contextChanged()
    if (!actionValid(value) || value.id !== id) throw mismatch('动作记录与当前身份不一致。')
    return value
  } })
const history = useQuery({ queryKey: computed(() => ['review-actions', store.sessionEpoch, 'history', props.id, historyBefore.value]), enabled: computed(() => readable.value && tab.value === 'history'),
  queryFn: async () => {
    const id = props.id, epoch = store.sessionEpoch, before = historyBefore.value
    const value = await read<ReviewActionHistoryPage>(`/review/actions/${id}/history?limit=10${before === undefined ? '' : `&before=${before}`}`)
    if (!alive || !readable.value || id !== props.id || epoch !== store.sessionEpoch || before !== historyBefore.value || tab.value !== 'history') throw contextChanged()
    if (!value || !Array.isArray(value.items) || value.items.some(item => !actionValid(item.snapshot) || item.snapshot.id !== id || typeof item.change !== 'string'
      || (before !== undefined && item.snapshot.revision >= before)) || new Set(value.items.map(item => item.snapshot.revision)).size !== value.items.length
      || (value.nextBefore !== null && (!Number.isSafeInteger(value.nextBefore) || value.nextBefore < 0 || !value.items.some(item => item.snapshot.revision === value.nextBefore)))) throw mismatch('历史快照与当前动作不一致。')
    return value
  } })
const mergeList = useQuery({ queryKey: computed(() => ['review-actions', store.sessionEpoch, 'merge-list', mergeCursor.value]), enabled: computed(() => readable.value && tab.value === 'merge' && current.value?.status === 'OPEN'),
  queryFn: async () => {
    const epoch = store.sessionEpoch, cursor = mergeCursor.value
    const value = await read<ReviewActionPage>(`/review/actions?status=OPEN&limit=10${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || cursor !== mergeCursor.value || tab.value !== 'merge') throw contextChanged()
    if (!value || !Array.isArray(value.items) || value.items.some(item => !reviewIdValid(item.id) || item.status !== 'OPEN')) throw mismatch('可合并动作列表无效，请重新读取。')
    return value
  } })
const mergeOptions = computed(() => (mergeList.data.value?.items ?? []).filter(item => item.id !== props.id))
watch(action.data, value => { if (value && readable.value && recordLatest(value) && (!current.value || (!dirty.value && !locked.value && !receiptMismatch.value && !needsReview.value))) accept(value) }, { immediate: true })
watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed; commandState.locked = waiting
  store.changes.set('review-action-editor', changeState); store.changes.set('review-action-command', commandState)
  emit('state', { dirty: changed, locked: waiting })
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => {
  detailSequence++; comparisonSequence++; mergeSequence++
  serverLoading.value = false; mergeLoading.value = false; serverCopy.value = undefined; mergeConfirmed.value = false
  if (current.value) needsReview.value = true
}, { flush: 'sync' })
watch([writer.error, writer.pending], ([error, pending]) => {
  if (error instanceof ApiError && !pending) { mergeConfirmed.value = false; if (error.status === 409) needsReview.value = true }
}, { flush: 'sync' })
watch([stale, () => latest.value?.revision], () => { mergeConfirmed.value = false }, { flush: 'sync' })
onUnmounted(() => {
  alive = false; detailSequence++; comparisonSequence++; mergeSequence++
  if (store.changes.get('review-action-editor') === changeState) store.changes.delete('review-action-editor')
  if (store.changes.get('review-action-command') === commandState) store.changes.delete('review-action-command')
  emit('state', { dirty: false, locked: false })
})
function contextChanged() { return new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '读取条件已变化，本地输入继续保留。') }
function mismatch(text: string) { return new ApiError(502, 'REVIEW_ACTION_MISMATCH', text) }
function recordLatest(value: ReviewAction): boolean {
  if (value.id !== props.id || (latest.value && value.revision < latest.value.revision) || (current.value && value.revision < current.value.revision)) return false
  latest.value = value
  return true
}
function fillSource() {
  const source = selectedSource.value
  verification.value = source?.verification ?? ''; tracking.value = source?.tracking ?? 'TRACKING'; trackingReason.value = source?.trackingReason ?? ''
}
function accept(value: ReviewAction) {
  if (!recordLatest(value)) return
  current.value = value; title.value = value.title; detailText.value = value.detail
  if (!value.sources.some(source => source.id === sourceId.value)) sourceId.value = value.sources[0]?.id ?? ''
  fillSource(); needsReview.value = false; mergeSource.value = undefined; mergeConfirmed.value = false
}
function resetForm() { if (current.value) { title.value = current.value.title; detailText.value = current.value.detail; fillSource(); mergeSource.value = undefined; mergeConfirmed.value = false } }
function changeTab(value: typeof tab.value) {
  if (blocked.value || value === tab.value || (dirty.value && !window.confirm('切换会放弃此处尚未保存的内容或合并选择，继续？'))) return
  resetForm(); tab.value = value
}
function chooseSource(id: string) {
  if (blocked.value || id === sourceId.value || (dirty.value && !window.confirm('切换来源会放弃当前尚未保存的验证条件和跟踪修改，继续？'))) return
  sourceId.value = id; fillSource()
}
function refresh() { void queryClient.invalidateQueries({ queryKey: ['review-actions'] }); emit('changed') }
function receive(value: ReviewAction, text: string) {
  if (!recordLatest(value)) {
    needsReview.value = true; serverCopy.value = latest.value
    message.value = `本次操作已确认（修订 ${value.revision}），服务器已有后续修改。输入继续保留，请核对最新内容。`
  } else {
    accept(value); serverCopy.value = undefined; message.value = text
    queryClient.setQueryData(['review-actions', store.sessionEpoch, 'detail', props.id], value)
  }
  refresh()
}
function run(path: string, method: string, body: unknown, matches: (value: ReviewAction) => boolean, text: string) {
  if (!editable.value) return
  detailSequence++; message.value = ''; readError.value = ''; commandEpoch = store.sessionEpoch
  void writer.run<ReviewAction>(path, method, body, value => {
    if (!alive) return
    if (!actionValid(value) || value.id !== props.id || !matches(value)) {
      receiptMismatch.value = true; needsReview.value = true
      refresh()
      throw mismatch('回执与本次请求不一致，输入与原请求保留，请用原请求重试。')
    }
    receiptMismatch.value = false
    const apply = () => receive(value, text)
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = apply
    else apply()
  })
}
function saveDescription() {
  const before = current.value
  if (!before || !dirty.value || descriptionProblem.value) return
  const body: ReviewSaveAction = { expectedRevision: before.revision, title: title.value, detail: detailText.value }
  run(`/review/actions/${before.id}`, 'PUT', body, value => value.revision === before.revision + 1 && value.status === 'OPEN'
    && value.title === body.title && value.detail === body.detail && actionSourcesMatch(value.sources, before.sources), '已保存动作说明，历史保留。')
}
function saveSource() {
  const before = current.value, selected = selectedSource.value
  if (!before || !selected || !dirty.value || sourceProblem.value) return
  const body: ReviewSaveSource = { expectedRevision: before.revision, verification: verification.value, tracking: tracking.value, trackingReason: trackingReason.value }
  const expected = before.sources.map(source => source.id === selected.id ? { ...source, verification: body.verification, tracking: body.tracking, trackingReason: body.trackingReason } : source)
  run(`/review/actions/${before.id}/sources/${selected.id}`, 'PUT', body, value => value.revision === before.revision + 1 && value.status === 'OPEN'
    && value.title === before.title && value.detail === before.detail && actionSourcesMatch(value.sources, expected), '已保存这条来源的验证条件与跟踪记录。停止跟踪不产生完成结论。')
}
async function chooseMerge(id: string) {
  if (blocked.value || receiptMismatch.value || id === props.id) return
  const epoch = store.sessionEpoch, sequence = ++mergeSequence
  mergeLoading.value = true; mergeError.value = ''; mergeConfirmed.value = false; mergeSource.value = undefined
  try {
    const value = await read<ReviewAction>(`/review/actions/${id}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || sequence !== mergeSequence || tab.value !== 'merge') return
    if (!actionValid(value) || value.id !== id || value.status !== 'OPEN') throw mismatch('所选动作已变化，请重新读取可合并列表。')
    mergeSource.value = value
  } catch (error) { if (alive && epoch === store.sessionEpoch && sequence === mergeSequence) mergeError.value = errorText(error) }
  finally { if (alive && sequence === mergeSequence) mergeLoading.value = false }
}
function merge() {
  const before = current.value, source = mergeSource.value
  if (!before || !source || !mergeConfirmed.value || mergeProblem.value) return
  const body: ReviewMergeAction = { expectedRevision: before.revision, sourceActionId: source.id, sourceExpectedRevision: source.revision }
  // 两端修订与每条来源实例同时固定；重复反馈的不同验证条件也必须完整保留。
  const expected = [...before.sources, ...source.sources]
  run(`/review/actions/${before.id}/merge`, 'POST', body, value => value.revision === before.revision + 1 && value.status === 'OPEN'
    && value.title === before.title && value.detail === before.detail && actionSourcesMatch(value.sources, expected), '已合并来源。原动作保留去向，来源身份、验证条件、跟踪状态和历史继续保留。')
}
async function compareServer() {
  if (!readable.value || locked.value || serverLoading.value) return
  const epoch = store.sessionEpoch, sequence = ++comparisonSequence
  detailSequence++; serverLoading.value = true; readError.value = ''; mergeConfirmed.value = false
  try {
    const value = await read<ReviewAction>(`/review/actions/${props.id}`)
    if (!alive || !readable.value || locked.value || epoch !== store.sessionEpoch || sequence !== comparisonSequence) return
    if (!actionValid(value) || !recordLatest(value)) throw mismatch('服务器动作已出现更晚记录或身份不一致，请重新核对。')
    serverCopy.value = value
  } catch (error) { if (alive && epoch === store.sessionEpoch && sequence === comparisonSequence) readError.value = errorText(error) }
  finally { if (alive && sequence === comparisonSequence) serverLoading.value = false }
}
function useServer(keep: boolean) {
  const value = serverCopy.value
  if (blocked.value || !value || (latest.value && value.revision < latest.value.revision)) return
  if (keep && (value.status !== 'OPEN' || (tab.value === 'sources' && !value.sources.some(source => source.id === sourceId.value)))) return
  if (!window.confirm(keep ? '已核对服务器内容，确认保留本地文字并以此修订继续编辑？合并候选需要重新选择。' : '采用这份服务器记录并放弃当前本地修改？')) return
  const local = { title: title.value, detail: detailText.value, verification: verification.value, tracking: tracking.value, reason: trackingReason.value }
  accept(value)
  if (keep && tab.value === 'description') { title.value = local.title; detailText.value = local.detail }
  if (keep && tab.value === 'sources') { verification.value = local.verification; tracking.value = local.tracking; trackingReason.value = local.reason }
  receiptMismatch.value = false; serverCopy.value = undefined; writer.error.value = undefined
  message.value = keep ? '已核对修订，本地文字保留，尚未保存。' : '已采用服务器记录。'
}
function retry() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const apply = deferred.value; deferred.value = undefined; apply() } }
function olderHistory() { if (readable.value && !history.isFetching.value && history.data.value?.nextBefore != null) { historyCursors.value.push(historyBefore.value); historyBefore.value = history.data.value.nextBefore } }
function nextMergePage() { if (readable.value && !mergeList.isFetching.value && mergeList.data.value?.nextCursor) { mergeCursors.value.push(mergeCursor.value); mergeCursor.value = mergeList.data.value.nextCursor } }
</script>

<template>
  <section class="action-editor" aria-label="改进动作详情">
    <header class="heading"><div><p class="section-label">{{ current && readable ? `修订 ${current.revision} · ${current.status === 'MERGED' ? '已合并' : '待验证动作'}` : '改进与验证' }}</p><h3>{{ readable ? current?.title ?? '改进动作' : '改进动作' }}</h3></div><NButton text :disabled="locked" @click="emit('close')">关闭</NButton></header>
    <p v-if="!readable" class="notice">登录或资料访问条件变化，动作内容暂时遮挡，本地输入继续保留。</p>
    <template v-else>
      <p v-if="!current && action.isPending.value" role="status">正在读取动作及来源…</p>
      <p v-if="action.error.value" class="notice error" role="alert">{{ errorText(action.error.value) }}<NButton text :disabled="locked" @click="action.refetch()">重新读取</NButton></p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" @click="retry">用原请求重试</NButton><NButton v-else text :disabled="blocked" @click="compareServer">核对最新动作</NButton></p>
      <p v-if="deferred" class="notice" role="status">服务器已确认操作，访问条件变化后暂缓接收。<NButton text @click="receiveDeferred">接收已确认结果</NButton></p>
      <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="readError" class="notice error" role="alert">{{ readError }}</p>
      <template v-if="current">
        <p class="field-help">动作保留来源并安排后续验证。停止跟踪只记录取舍；完成情况由后续独立复练与明确判断产生。</p>
        <p v-if="current.status === 'MERGED'" class="notice">这条动作已合并，原历史继续保留。<NButton text :disabled="blocked" @click="emit('open', current.mergedInto!)">查看合并去向</NButton></p>
        <p v-if="current.revision >= 1000" class="notice">此动作已达 1000 次修订上限，可继续查看来源和历史。</p>
        <p v-if="needsReview || stale" class="notice">服务器修订或访问条件已变化。输入继续保留，核对后再保存。<NButton text :disabled="blocked" :loading="serverLoading" @click="compareServer">读取最新内容作对照</NButton></p>
        <section v-if="serverCopy" class="server-copy" aria-label="服务器动作对照"><h4>服务器修订 {{ serverCopy.revision }} · {{ serverCopy.status === 'OPEN' ? '待验证' : '已合并' }}</h4><strong>{{ serverCopy.title }}</strong><p class="preserve">{{ serverCopy.detail || '没有补充说明。' }}</p><details><summary>核对服务器全部来源（{{ serverCopy.sources.length }}）</summary><div v-for="source in serverCopy.sources" :key="source.id" class="source-card"><ReviewActionSourceView :observation="source.observation" :answer="source.answer" :decision="source.decision" :source="source" :saved="source" :disabled="blocked" /></div></details><div class="row-actions"><NButton :disabled="blocked || serverCopy.status !== 'OPEN' || (tab === 'sources' && !serverCopy.sources.some(source => source.id === sourceId))" @click="useServer(true)">核对后保留本地文字</NButton><NButton :disabled="blocked" @click="useServer(false)">采用服务器内容</NButton></div></section>
        <nav class="row-actions" aria-label="动作内容切换"><NButton :type="tab === 'description' ? 'primary' : 'default'" :disabled="blocked" @click="changeTab('description')">动作说明</NButton><NButton :type="tab === 'sources' ? 'primary' : 'default'" :disabled="blocked" @click="changeTab('sources')">来源与验证（{{ current.sources.length }}）</NButton><NButton v-if="current.status === 'OPEN'" :type="tab === 'merge' ? 'primary' : 'default'" :disabled="blocked" @click="changeTab('merge')">合并另一动作</NButton><NButton :type="tab === 'history' ? 'primary' : 'default'" :disabled="blocked" @click="changeTab('history')">完整历史</NButton></nav>
        <section v-if="tab === 'description'" class="form"><label>动作标题<NInput v-model:value="title" :disabled="blocked || receiptMismatch || current.status !== 'OPEN'" /><span class="field-help">{{ questionTextLength(title) }} / 200 字符</span></label><label>补充说明<NInput v-model:value="detailText" type="textarea" :autosize="{ minRows: 4, maxRows: 12 }" :disabled="blocked || receiptMismatch || current.status !== 'OPEN'" /><span class="field-help">{{ questionTextLength(detailText) }} / 2000 字符</span></label><p v-if="descriptionProblem" class="field-help">{{ descriptionProblem }}</p><NButton v-if="current.status === 'OPEN'" type="primary" :disabled="!editable || !dirty || !!descriptionProblem" :loading="writer.busy.value" @click="saveDescription">保存动作说明</NButton></section>
        <section v-else-if="tab === 'sources'" class="form"><p v-if="!current.sources.length" class="field-help">来源已移至合并去向，原记录仍可在历史中查看。</p><label v-else>选择来源<NSelect :value="sourceId" :options="sourceOptions" :disabled="blocked" @update:value="chooseSource" /></label>
          <template v-if="selectedSource"><ReviewActionSourceView :observation="selectedSource.observation" :answer="selectedSource.answer" :decision="selectedSource.decision" :source="selectedSource" :saved="selectedSource" :disabled="blocked" /><template v-if="current.status === 'OPEN'"><label>这条来源的验证条件<NInput v-model:value="verification" type="textarea" :autosize="{ minRows: 3, maxRows: 10 }" :disabled="blocked || receiptMismatch" placeholder="下一次独立回答中，出现什么内容才能判断这一条得到了处理？" /><span class="field-help">{{ questionTextLength(verification) }} / 2000 字符</span></label><label>跟踪状态<NSelect v-model:value="tracking" :options="[{ value: 'TRACKING', label: '继续跟踪' }, { value: 'DROPPED', label: '停止跟踪' }]" :disabled="blocked || receiptMismatch" /></label><label>跟踪原因{{ tracking === 'DROPPED' ? '（必填）' : '（可选，可记录恢复原因）' }}<NInput v-model:value="trackingReason" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" :disabled="blocked || receiptMismatch" /><span class="field-help">{{ questionTextLength(trackingReason) }} / 1000 字符</span></label><p v-if="sourceProblem" class="field-help">{{ sourceProblem }}</p><NButton type="primary" :disabled="!editable || !dirty || !!sourceProblem" :loading="writer.busy.value" @click="saveSource">保存本条来源</NButton></template></template>
        </section>
        <section v-else-if="tab === 'merge'" class="form"><h4>把另一条动作并入「{{ current.title }}」</h4><p class="field-help">两个动作的修订一起核对。原动作保留合并去向；相同反馈的不同来源实例、验证条件和停止跟踪原因全部保留。</p><p v-if="mergeList.isPending.value" role="status">正在读取可合并动作…</p><p v-if="mergeList.error.value" class="notice error" role="alert">{{ errorText(mergeList.error.value) }}<NButton text :disabled="blocked" @click="mergeList.refetch()">重读列表</NButton></p><p v-if="mergeList.data.value && !mergeOptions.length" class="field-help">本页没有其他开放动作{{ mergeList.data.value.nextCursor ? '，可以继续翻页' : '' }}。</p><article v-for="item in mergeOptions" :key="item.id" class="merge-option"><div><strong>{{ item.title }}</strong><p class="field-help">修订 {{ item.revision }} · {{ item.sourceCount }} 条来源</p></div><NButton size="small" :disabled="blocked || receiptMismatch" @click="chooseMerge(item.id)">核对并选择</NButton></article><div class="row-actions"><NButton size="small" :disabled="blocked || mergeList.isFetching.value || !mergeCursors.length" @click="mergeCursor = mergeCursors.pop()">上一页</NButton><NButton size="small" :disabled="blocked || mergeList.isFetching.value || !mergeList.data.value?.nextCursor" @click="nextMergePage">下一页</NButton></div><p v-if="mergeLoading" role="status">正在读取另一动作的全部来源…</p><p v-if="mergeError" class="notice error" role="alert">{{ mergeError }}</p>
          <section v-if="mergeSource" class="server-copy"><h4>被合并动作：{{ mergeSource.title }} · 修订 {{ mergeSource.revision }}</h4><p class="preserve">{{ mergeSource.detail || '没有补充说明。' }}</p><p class="field-help">当前动作 {{ current.sources.length }} 条 + 被合并动作 {{ mergeSource.sources.length }} 条 = {{ current.sources.length + mergeSource.sources.length }} 条来源。</p><details><summary>查看即将保留的来源</summary><div v-for="source in mergeSource.sources" :key="source.id" class="source-card"><ReviewActionSourceView :observation="source.observation" :answer="source.answer" :decision="source.decision" :source="source" :saved="source" :disabled="blocked" /></div></details><NCheckbox v-model:checked="mergeConfirmed" :disabled="!editable || !!mergeProblem">确认将这条动作的全部来源并入当前动作，保留原动作与历史</NCheckbox><NButton type="primary" :disabled="!editable || !mergeConfirmed || !!mergeProblem" :loading="writer.busy.value" @click="merge">确认合并</NButton></section><p v-if="mergeProblem" class="field-help">{{ mergeProblem }}</p>
        </section>
        <section v-else class="form" aria-label="动作完整历史"><p v-if="history.isPending.value" role="status">正在读取历史…</p><p v-if="history.error.value" class="notice error" role="alert">{{ errorText(history.error.value) }}<NButton text :disabled="blocked" @click="history.refetch()">重读历史</NButton></p><details v-for="entry in history.data.value?.items ?? []" :key="entry.snapshot.revision" class="history-entry"><summary>修订 {{ entry.snapshot.revision }} · {{ entry.change }} · {{ reviewDate(entry.snapshot.updatedAt) }}</summary><h4>{{ entry.snapshot.title }}</h4><p class="preserve">{{ entry.snapshot.detail || '没有补充说明。' }}</p><p class="field-help">状态：{{ entry.snapshot.status === 'OPEN' ? '待验证' : '已合并' }} · {{ entry.snapshot.sources.length }} 条来源</p><NButton v-if="entry.snapshot.mergedInto" text :disabled="blocked" @click="emit('open', entry.snapshot.mergedInto)">查看当时的合并去向</NButton><div v-for="source in entry.snapshot.sources" :key="source.id" class="source-card"><ReviewActionSourceView :observation="source.observation" :answer="source.answer" :decision="source.decision" :source="source" :saved="source" :disabled="blocked" /></div></details><div class="row-actions"><NButton :disabled="blocked || history.isFetching.value || !historyCursors.length" @click="historyBefore = historyCursors.pop()">较新一页</NButton><NButton :disabled="blocked || history.isFetching.value || history.data.value?.nextBefore == null" @click="olderHistory">较早一页</NButton></div></section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.action-editor, .form { display: grid; gap: 17px; min-width: 0; } .heading { display: flex; align-items: start; gap: 15px; justify-content: space-between; } h3 { margin: 4px 0 0; font-size: 20px; } h4 { font-size: 14px; margin: 0; } p { margin: 0; } label { display: grid; gap: 8px; font-size: 13px; } .row-actions { display: flex; flex-wrap: wrap; gap: 8px; } .form > .n-button { justify-self: start; } .source-card { margin-top: 15px; padding-top: 15px; border-top: 1px solid var(--line); } .server-copy { display: grid; gap: 13px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 6px; padding: 17px; min-width: 0; } .preserve { white-space: pre-wrap; overflow-wrap: anywhere; } .merge-option { display: flex; gap: 12px; align-items: start; justify-content: space-between; padding: 13px 0; border-top: 1px solid var(--line); } .merge-option strong { font-size: 14px; } .merge-option p { margin-top: 7px; } .history-entry { padding: 14px; border: 1px solid var(--line); border-radius: 6px; } .history-entry > h4, .history-entry > p { margin-top: 12px; } summary { cursor: pointer; color: var(--muted); font-size: 13px; }
@media (max-width: 560px) { .heading, .merge-option { flex-wrap: wrap; } }
</style>
