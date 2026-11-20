<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import type { ReviewAction, ReviewActionPage, ReviewAnswerLink, ReviewCreateAction } from '../../api/review'
import { reviewAnswerKey, reviewDate, reviewIdValid } from '../../api/review'
import { questionTextLength } from '../../api/bank'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import type { ActionDraftSource, ActionEntry } from './reviewActionData'
import { actionCandidateMatches, actionSourceKey, actionTextProblem, actionValid, readActionCandidate, sourceTextProblem } from './reviewActionData'
import ReviewActionEditor from './ReviewActionEditor.vue'
import ReviewActionSourcePicker from './ReviewActionSourcePicker.vue'
import ReviewActionSourceView from './ReviewActionSourceView.vue'
import RepracticeActionProgress from './RepracticeActionProgress.vue'

const props = defineProps<{ source?: ActionEntry | null; answer?: ReviewAnswerLink; disabled?: boolean; actionId?: string }>()
const emit = defineEmits<{ consumed: []; open: [id: string]; close: []; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const status = ref<'ALL' | 'OPEN' | 'MERGED'>('OPEN')
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const selectedId = ref<string>()
const creating = ref(false)
const title = ref('')
const detailText = ref('')
const sources = ref<ActionDraftSource[]>([])
const confirmed = ref(false)
const needsSources = ref(false)
const receiptMismatch = ref(false)
const sourceLoading = ref(false)
const sourceError = ref('')
const failedEntry = shallowRef<ActionEntry>()
const message = ref('')
const deferred = shallowRef<() => void>()
const pickerOpen = ref(false)
const pickerKey = ref('')
const childState = ref({ dirty: false, locked: false })
const readable = computed(() => store.sessionActive && !props.disabled)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || sourceLoading.value || childState.value.locked)
const dirty = computed(() => Boolean(title.value || detailText.value || sources.value.length))
const signature = computed(() => JSON.stringify([title.value, detailText.value, sources.value.map(source => [actionSourceKey(source.source), source.verification, source.source.expectedDecisionRevision])]))
const problem = computed(() => actionTextProblem(title.value, detailText.value) || (sources.value.length < 1 || sources.value.length > 10 ? '请选择 1 至 10 条来源。'
  : sources.value.map(source => sourceTextProblem(source.verification, 'TRACKING', '')).find(Boolean) ?? ''))
const answers = computed(() => {
  const result = new Map<string, ReviewAnswerLink>()
  if (props.answer) result.set(reviewAnswerKey(props.answer), props.answer)
  sources.value.forEach(source => result.set(reviewAnswerKey(source.answer), source.answer))
  return [...result.entries()]
})
const pickerAnswer = computed(() => answers.value.find(([key]) => key === pickerKey.value)?.[1] ?? answers.value[0]?.[1])
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let sourceSequence = 0
let commandEpoch = store.sessionEpoch

const list = useQuery({ queryKey: computed(() => ['review-actions', store.sessionEpoch, 'list', status.value, cursor.value]), enabled: readable,
  queryFn: async () => {
    const epoch = store.sessionEpoch, selected = status.value, page = cursor.value
    const query = new URLSearchParams({ limit: '10' }); if (selected !== 'ALL') query.set('status', selected); if (page) query.set('cursor', page)
    const value = await read<ReviewActionPage>(`/review/actions?${query}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || selected !== status.value || page !== cursor.value) throw new ApiError(0, 'REVIEW_LIST_CHANGED', '动作列表读取条件已变化，请重新读取。')
    if (!value || !Array.isArray(value.items) || value.items.some(item => !reviewIdValid(item.id) || !['OPEN', 'MERGED'].includes(item.status) || (selected !== 'ALL' && item.status !== selected))
      || (value.nextCursor !== null && typeof value.nextCursor !== 'string')) throw new ApiError(502, 'REVIEW_LIST_MISMATCH', '动作列表格式不一致，请重新读取。')
    return value
  } })
watch(status, () => { cursor.value = undefined; cursors.value = [] })
watch([dirty, locked, childState], ([changed, waiting, child]) => {
  changeState.dirty = changed; commandState.locked = waiting
  store.changes.set('review-action-create', changeState); store.changes.set('review-actions-command', commandState)
  emit('state', { dirty: changed || child.dirty, locked: waiting || child.locked })
}, { immediate: true, flush: 'sync' })
watch(signature, () => { confirmed.value = false }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => {
  sourceSequence++; sourceLoading.value = false; confirmed.value = false; pickerOpen.value = false
  if (sources.value.length) needsSources.value = true
}, { flush: 'sync' })
watch([writer.error, writer.pending], ([error, pending]) => { if (error instanceof ApiError && !pending) { confirmed.value = false; if (error.status === 409) needsSources.value = true } }, { flush: 'sync' })
watch([() => props.source, readable, locked, sourceLoading, () => childState.value.locked], ([entry]) => { if (entry && !blocked.value && !receiptMismatch.value) void addSource(entry, true) }, { immediate: true })
watch(() => props.actionId, value => {
  const id = reviewIdValid(value) ? value : undefined
  if (id === selectedId.value) return
  // 路由已提交才切换详情；浏览器后退的空 ID 同样生效，不再二次询问或清空创建请求。
  selectedId.value = id
  if (!locked.value && !sourceLoading.value) creating.value = false
}, { immediate: true, flush: 'sync' })
onUnmounted(() => {
  alive = false; sourceSequence++
  if (store.changes.get('review-action-create') === changeState) store.changes.delete('review-action-create')
  if (store.changes.get('review-actions-command') === commandState) store.changes.delete('review-actions-command')
  emit('state', { dirty: false, locked: false })
})
function canSwitch() { return store.canLeave(['review-action-create', 'review-actions-command', 'review-action-editor', 'review-action-command']) }
function resetCreate() {
  title.value = ''; detailText.value = ''; sources.value = []; confirmed.value = false; needsSources.value = false
  receiptMismatch.value = false; writer.error.value = undefined; sourceError.value = ''; failedEntry.value = undefined; pickerOpen.value = false
}
function start() {
  if (blocked.value || !store.canLeave(['review-action-editor', 'review-action-command'])) return
  creating.value = true; message.value = ''
}
function open(id: string) {
  if (blocked.value || !reviewIdValid(id)) return
  // 已提交的同一动作只收起本地创建表单，草稿仍可继续编辑。
  if (selectedId.value === id) { creating.value = false; return }
  emit('open', id)
}
function close() {
  if (blocked.value) return
  if (creating.value) {
    if (!canSwitch()) return
    resetCreate(); creating.value = false
  } else if (selectedId.value) emit('close')
}
async function addSource(entry: ActionEntry, consume = false) {
  if (blocked.value || receiptMismatch.value) return
  if (!creating.value && !store.canLeave(['review-action-editor', 'review-action-command'])) { if (consume) emit('consumed'); return }
  const identity = 'source' in entry ? entry.source : entry
  if (sources.value.some(source => actionSourceKey(source.source) === actionSourceKey(identity))) { message.value = '这项确切反馈已在当前动作中，可直接完善它的验证条件。'; if (consume) emit('consumed'); return }
  if (sources.value.length >= 10) { sourceError.value = '创建动作最多选择 10 条来源。'; if (consume) emit('consumed'); return }
  const epoch = store.sessionEpoch, sequence = ++sourceSequence
  sourceLoading.value = true; sourceError.value = ''; failedEntry.value = undefined
  try {
    const candidate = await readActionCandidate(entry)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || sequence !== sourceSequence) return
    if (!creating.value) creating.value = true
    sources.value.push({ ...candidate, verification: 'verification' in entry ? entry.verification : '' })
    confirmed.value = false; message.value = '已加入确切来源，请填写这条来源的验证条件。'
  } catch (error) { if (alive && epoch === store.sessionEpoch && sequence === sourceSequence) { sourceError.value = errorText(error); failedEntry.value = entry } }
  finally {
    if (alive && sequence === sourceSequence) { sourceLoading.value = false; if (consume && props.source === entry) emit('consumed') }
  }
}
function removeSource(index: number) { if (!blocked.value && !receiptMismatch.value) sources.value.splice(index, 1) }
async function refreshSources() {
  if (blocked.value || receiptMismatch.value || !sources.value.length) return
  const epoch = store.sessionEpoch, sequence = ++sourceSequence, before = [...sources.value]
  sourceLoading.value = true; sourceError.value = ''; confirmed.value = false
  try {
    const values = await Promise.all(before.map(async source => ({ ...await readActionCandidate(source), verification: source.verification })))
    if (!alive || !readable.value || epoch !== store.sessionEpoch || sequence !== sourceSequence) return
    sources.value = values; needsSources.value = false
    message.value = '已重读各来源的已保存决定，验证条件保留。请核对后重新确认。'
  } catch (error) { if (alive && epoch === store.sessionEpoch && sequence === sourceSequence) sourceError.value = errorText(error) }
  finally { if (alive && sequence === sourceSequence) sourceLoading.value = false }
}
function refreshList() { void queryClient.invalidateQueries({ queryKey: ['review-actions'] }); void queryClient.invalidateQueries({ queryKey: ['repractice'] }) }
function create() {
  if (blocked.value || receiptMismatch.value || needsSources.value || !confirmed.value || problem.value) return
  const expected = JSON.parse(JSON.stringify(sources.value)) as ActionDraftSource[]
  const body: ReviewCreateAction = { title: title.value, detail: detailText.value, sources: expected.map(source => ({ ...source.source, verification: source.verification })) }
  commandEpoch = store.sessionEpoch; message.value = ''; sourceError.value = ''
  void writer.run<ReviewAction>('/review/actions', 'POST', body, value => {
    if (!alive) return
    if (!actionValid(value) || value.status !== 'OPEN' || value.revision !== 0 || value.title !== body.title || value.detail !== body.detail || value.sources.length !== expected.length
      || value.sources.some(source => source.originActionId !== value.id || !expected.some(candidate => actionCandidateMatches(source, candidate)))
      || new Set(value.sources.map(actionSourceKey)).size !== expected.length) {
      receiptMismatch.value = true; confirmed.value = false
      refreshList()
      throw new ApiError(502, 'REVIEW_ACTION_MISMATCH', '创建回执与本次来源或正文不一致，输入与原请求保留，请用原请求重试。')
    }
    receiptMismatch.value = false
    const apply = () => {
      resetCreate(); creating.value = false
      queryClient.setQueryData(['review-actions', store.sessionEpoch, 'detail', value.id], value)
      message.value = '已创建改进动作。每条来源保留独立验证条件，等待后续复练验证。'
      refreshList(); emit('open', value.id)
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = apply
    else apply()
  })
}
function retry() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const apply = deferred.value; deferred.value = undefined; apply() } }
function nextPage() { if (readable.value && !list.isFetching.value && list.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = list.data.value.nextCursor } }
</script>

<template>
  <section class="review-actions" aria-label="改进动作工作区">
    <header class="heading"><div><p class="section-label">改进与验证</p><h2>把反馈变成下一次的行动</h2></div><NButton :disabled="blocked" @click="start">{{ dirty ? '继续创建动作' : '新建动作' }}</NButton></header>
    <p v-if="!readable" class="notice">登录或资料访问条件变化，动作内容暂时遮挡，本地输入继续保留。</p>
    <div v-show="readable" class="actions-content" :inert="!readable || undefined">
      <p class="field-help">可以连续加入自检、外部意见和 AI 未审观察。每个来源保留采用时记录与独立验证条件。</p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="dirty && !creating" class="notice" role="status">未保存的创建草稿仍保留，可选择“继续创建动作”接着编辑。</p>
      <div class="action-columns"><aside class="action-list" aria-label="已保存动作"><label>动作状态<NSelect v-model:value="status" :options="[{ value: 'OPEN', label: '待验证动作' }, { value: 'MERGED', label: '已合并动作' }, { value: 'ALL', label: '全部动作' }]" /></label><p v-if="list.isPending.value" role="status">正在读取动作…</p><p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重新读取</NButton></p><p v-if="list.data.value && !list.data.value.items.length" class="field-help">当前没有符合条件的动作。</p>
        <button v-for="item in list.data.value?.items ?? []" :key="item.id" type="button" class="action-row" :class="{ selected: selectedId === item.id && !creating }" :disabled="blocked" @click="open(item.id)"><strong>{{ item.title }}</strong><span>{{ item.status === 'MERGED' ? '已合并' : '待验证' }} · {{ item.sourceCount }} 条来源 · {{ item.trackingCount }} 条跟踪中</span><time>{{ reviewDate(item.updatedAt) }}</time></button><div class="row-actions"><NButton size="small" :disabled="list.isFetching.value || !cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="list.isFetching.value || !list.data.value?.nextCursor" @click="nextPage">下一页</NButton><NButton text size="small" :disabled="list.isFetching.value" @click="list.refetch()">刷新</NButton></div>
      </aside><div class="action-main">
        <p v-if="sourceLoading" role="status">正在读取来源的确切观察与已保存决定…</p><p v-if="sourceError" class="notice error" role="alert">{{ sourceError }}<NButton v-if="failedEntry" text :disabled="blocked || receiptMismatch" @click="addSource(failedEntry)">重读这条来源</NButton></p>
        <section v-if="creating" class="creation" aria-label="创建改进动作"><header class="heading"><h3>新建改进动作</h3><NButton text :disabled="blocked" @click="close">关闭</NButton></header>
          <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" @click="retry">用原请求重试</NButton></p><p v-if="deferred" class="notice" role="status">服务器已确认创建，访问条件变化后暂缓接收。<NButton text @click="receiveDeferred">接收已确认动作</NButton></p>
          <label>动作标题<NInput v-model:value="title" :disabled="blocked || receiptMismatch" placeholder="例如：说明事务消息失败后的补偿边界" /><span class="field-help">{{ questionTextLength(title) }} / 200 字符</span></label><label>补充说明<NInput v-model:value="detailText" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="blocked || receiptMismatch" placeholder="下一次要准备或练习的具体内容" /><span class="field-help">{{ questionTextLength(detailText) }} / 2000 字符</span></label>
          <div class="heading"><h4>来源与验证条件（{{ sources.length }} / 10）</h4><NButton v-if="answers.length" text :disabled="blocked || receiptMismatch" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起来源选择' : '添加同次提交的其他反馈' }}</NButton></div><p v-if="!sources.length" class="field-help">从确切提交的自检、手工意见或 AI 报告中选择“形成动作”，可连续加入多条来源。</p>
          <section v-if="pickerOpen && pickerAnswer" class="creation"><label v-if="answers.length > 1">选择已加入来源对应的提交<NSelect v-model:value="pickerKey" :options="answers.map(([key, answer]) => ({ value: key, label: `提交 ${answer.answerId}` }))" :disabled="blocked || receiptMismatch" /></label><ReviewActionSourcePicker :answer="pickerAnswer" :disabled="blocked || receiptMismatch" @add="addSource($event)" /></section>
          <article v-for="(source, index) in sources" :key="actionSourceKey(source.source)" class="source-card"><header class="heading"><h4>来源 {{ index + 1 }}</h4><NButton text size="small" :disabled="blocked || receiptMismatch" @click="removeSource(index)">移出本次创建</NButton></header><ReviewActionSourceView :observation="source.observation" :answer="source.answer" :decision="source.decision" :source="source.source" :label="source.label" :disabled="blocked" /><label>本条来源的验证条件<NInput v-model:value="source.verification" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="blocked || receiptMismatch" placeholder="下一次独立回答中，怎样才能判断这条反馈得到了处理？" /><span class="field-help">{{ questionTextLength(source.verification) }} / 2000 字符</span></label></article>
          <p v-if="needsSources" class="notice">来源决定或访问条件已变化，验证条件保留，请重读后再次确认。</p><NButton v-if="sources.length" :disabled="blocked || receiptMismatch" @click="refreshSources">重读来源决定并保留验证条件</NButton><p v-if="problem" class="field-help">{{ problem }}</p><NCheckbox v-model:checked="confirmed" :disabled="blocked || receiptMismatch || needsSources || !!problem">已核对这些确切来源、采用时决定和每条验证条件，确认创建动作</NCheckbox><NButton type="primary" :disabled="blocked || receiptMismatch || needsSources || !confirmed || !!problem" :loading="writer.busy.value" @click="create">创建改进动作</NButton>
        </section>
        <template v-else-if="selectedId"><ReviewActionEditor :key="selectedId" :id="selectedId" :disabled="disabled || locked || sourceLoading" @state="childState = $event" @changed="refreshList" @open="open" @close="close" /><RepracticeActionProgress :key="`progress:${selectedId}`" :action-id="selectedId" :disabled="disabled || locked || sourceLoading || childState.locked" @open="open" /></template>
        <p v-else-if="!sourceLoading" class="empty-state">选择一条动作查看来源与历史，或从复盘反馈开始创建。</p>
      </div></div>
    </div>
  </section>
</template>

<style scoped>
.review-actions, .actions-content, .creation, .source-card { display: grid; gap: 17px; min-width: 0; } .heading { display: flex; justify-content: space-between; align-items: start; gap: 13px; } h2 { margin: 4px 0 0; font-size: 21px; } h3 { margin: 0; font-size: 19px; } h4 { margin: 0; font-size: 14px; } p { margin: 0; } .action-columns { display: grid; grid-template-columns: minmax(190px, .65fr) minmax(0, 1.6fr); gap: 24px; } .action-main, .action-list { min-width: 0; } .action-main { display: grid; gap: 16px; align-content: start; } .action-list { display: grid; gap: 12px; align-content: start; padding-right: 22px; border-right: 1px solid var(--line); } label { display: grid; gap: 8px; font-size: 13px; } .action-row { display: grid; gap: 8px; text-align: left; border: 1px solid var(--line); border-radius: 5px; padding: 14px; background: var(--surface); color: inherit; cursor: pointer; overflow-wrap: anywhere; } .action-row strong { font-size: 14px; font-weight: 550; } .action-row span, .action-row time { font-size: 11px; color: var(--muted); } .action-row.selected { border-color: var(--brand); background: var(--surface-soft); } .action-row:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; } .action-row:disabled { cursor: default; opacity: .6; } .row-actions { display: flex; flex-wrap: wrap; gap: 8px; } .source-card { border: 1px solid var(--line); border-radius: 6px; padding: 17px; } .creation > .n-button { justify-self: start; } .empty-state { padding: 36px 12px; color: var(--muted); font-size: 14px; }
@media (max-width: 1100px) { .action-columns { grid-template-columns: 1fr; } .action-list { border-right: 0; border-bottom: 1px solid var(--line); padding: 0 0 18px; } }
@media (max-width: 560px) { .heading { flex-wrap: wrap; } }
</style>
