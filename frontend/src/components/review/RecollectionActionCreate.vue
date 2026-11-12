<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import type { Recollection } from '../../api/recollection'
import { recollectionBaselineLabels, recollectionValid } from '../../api/recollection'
import type { ReviewAction, ReviewCreateRecollectionAction } from '../../api/review'
import { questionTextLength } from '../../api/bank'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import { actionTextProblem, actionTextValid, actionValid, sourceTextProblem } from './reviewActionData'

const props = defineProps<{ value: Recollection; disabled?: boolean }>()
const emit = defineEmits<{ created: [value: ReviewAction] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const active = ref(true), opened = ref(false), confirmed = ref(false), reviewing = ref(false)
const current = shallowRef<Recollection>(), source = shallowRef<Recollection>(), created = shallowRef<ReviewAction>()
const title = ref(''), detail = ref(''), observation = ref(''), verification = ref('')
const readError = ref(''), message = ref(''), reviewedEpoch = ref(-1), receiptMismatch = ref(false)
const receiptCommand = shallowRef<NonNullable<typeof writer.pending.value>>()
const deferred = shallowRef<() => void>()
const state = reactive({ dirty: false, locked: false }), stateKey = `recollection-action-${props.value.id}`
let alive = true, generation = 0, sequence = 0, commandEpoch = store.sessionEpoch
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && recollectionValid(props.value))
const locked = computed(() => writer.locked.value || Boolean(deferred.value) || (receiptMismatch.value && Boolean(receiptCommand.value)))
const blocked = computed(() => !readable.value || locked.value || receiptMismatch.value)
const dirty = computed(() => opened.value && Boolean(title.value || detail.value || observation.value || verification.value || confirmed.value))
const signature = computed(() => JSON.stringify([title.value, detail.value, observation.value, verification.value, current.value, source.value]))
const sourceStale = computed(() => !source.value || !current.value || source.value.id !== current.value.id
  || source.value.status !== 'ACTIVE' || current.value.status !== 'ACTIVE' || source.value.revision !== current.value.revision
  || source.value.currentVersion.id !== current.value.currentVersion.id || JSON.stringify(source.value.currentVersion) !== JSON.stringify(current.value.currentVersion)
  || reviewedEpoch.value !== store.sessionEpoch)
const problem = computed(() => actionTextProblem(title.value, detail.value)
  || (!actionTextValid(observation.value, 5000, true) ? '请填写自己观察到的情况，最多 5000 个完整字符。' : '')
  || sourceTextProblem(verification.value, 'TRACKING', ''))
const canCreate = computed(() => opened.value && !blocked.value && !reviewing.value && !readError.value && !sourceStale.value && !problem.value)

watch([() => props.value, readable], () => {
  if (!readable.value || !recollectionValid(props.value)) return
  const prior = current.value
  if (!prior || prior.id !== props.value.id || props.value.revision > prior.revision) current.value = copy(props.value)
  else if (prior.revision === props.value.revision && JSON.stringify(prior) !== JSON.stringify(props.value)) readError.value = '相同修订的回忆内容不一致，请重新核对；动作输入保留。'
}, { immediate: true })
watch([readable, () => store.sessionEpoch, () => props.value.id], () => {
  generation++; sequence++; reviewing.value = false; reviewedEpoch.value = -1; confirmed.value = false
}, { flush: 'sync' })
watch(signature, () => { confirmed.value = false }, { flush: 'sync' })
watch([dirty, locked], ([changed, waiting]) => { state.dirty = changed; state.locked = waiting; store.changes.set(stateKey, state) }, { immediate: true, flush: 'sync' })
watch(writer.error, error => {
  if (!(error instanceof ApiError) || writer.pending.value || receiptMismatch.value) return
  confirmed.value = false
  if (error.status === 409) reviewedEpoch.value = -1
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; generation++; sequence++
  if (store.changes.get(stateKey) === state) store.changes.delete(stateKey)
})

function copy(value: Recollection): Recollection { return JSON.parse(JSON.stringify(value)) as Recollection }
function mismatch(text: string) { return new ApiError(502, 'RECOLLECTION_ACTION_MISMATCH', text) }
function open() {
  if (blocked.value || reviewing.value || opened.value || !current.value || current.value.status !== 'ACTIVE' || readError.value) return
  source.value = copy(current.value)
  title.value = ''; detail.value = ''; observation.value = ''; verification.value = ''
  opened.value = true; reviewedEpoch.value = store.sessionEpoch; confirmed.value = false; message.value = ''
}
function close() {
  if (blocked.value || reviewing.value || !store.canLeave([stateKey])) return
  opened.value = false; source.value = undefined; confirmed.value = false; reviewedEpoch.value = -1
}
async function reviewCurrent() {
  if (blocked.value || reviewing.value) return
  const id = props.value.id, epoch = store.sessionEpoch, stamp = generation, request = ++sequence
  reviewing.value = true; readError.value = ''; confirmed.value = false
  try {
    const value = await read<Recollection>(`/review/recollections/${id}`)
    if (!alive || !readable.value || id !== props.value.id || epoch !== store.sessionEpoch || stamp !== generation || request !== sequence) return
    if (!recollectionValid(value, id) || (current.value && value.revision < current.value.revision)
      || (current.value?.revision === value.revision && JSON.stringify(current.value) !== JSON.stringify(value))) throw mismatch('重读的回忆修订或版本不一致，动作输入继续保留。')
    current.value = copy(value)
    reviewedEpoch.value = source.value?.revision === value.revision && source.value.currentVersion.id === value.currentVersion.id && value.status === 'ACTIVE' ? epoch : -1
    message.value = value.status === 'ARCHIVED' ? '回忆已归档，动作输入保留。请先在回忆页恢复，再核对来源。'
      : source.value && (source.value.revision !== value.revision || source.value.currentVersion.id !== value.currentVersion.id)
        ? '已读取当前回忆，请核对后明确采用当前修订，动作输入保持原样。' : '已重新核对回忆，动作输入保持原样。'
  } catch (error) { if (alive && epoch === store.sessionEpoch && request === sequence) readError.value = errorText(error) }
  finally { if (alive && request === sequence) reviewing.value = false }
}
function adoptCurrentSource() {
  if (blocked.value || reviewing.value || !opened.value || !current.value || current.value.status !== 'ACTIVE' || readError.value) return
  source.value = copy(current.value); reviewedEpoch.value = store.sessionEpoch; confirmed.value = false
  message.value = `已改用回忆 v${source.value.currentVersion.number}、修订 ${source.value.revision}，请重新核对人工观察与验证条件。`
}
function create() {
  if (!canCreate.value || !confirmed.value || !source.value) return
  const selected = copy(source.value)
  const body: ReviewCreateRecollectionAction = { expectedRevision: selected.revision, versionId: selected.currentVersion.id,
    title: title.value, detail: detail.value, observation: observation.value, verification: verification.value }
  commandEpoch = store.sessionEpoch; message.value = ''
  let originalCommand: typeof writer.pending.value
  void writer.run<ReviewAction>(`/review/recollections/${selected.id}/actions`, 'POST', body, value => {
    if (!alive) return
    if (!receiptValid(value, selected, body)) {
      receiptMismatch.value = true; receiptCommand.value = originalCommand; confirmed.value = false
      writer.error.value = mismatch('动作回执与本次回忆、人工观察或验证条件不一致。输入保留，请用原请求核对已保存结果。')
      return
    }
    const receive = () => receiveCreated(value, selected)
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = receive
    else receive()
  })
  originalCommand = writer.pending.value
}
function receiptValid(value: ReviewAction, selected: Recollection, body: ReviewCreateRecollectionAction): boolean {
  try {
    if (!actionValid(value) || value.status !== 'OPEN' || value.revision !== 0 || value.title !== body.title || value.detail !== body.detail || value.sources.length !== 1) return false
    const saved = value.sources[0]!, recollection = saved.recollection
    // 原键恢复只核对发出时的确切版本；后续补记不能把回执移到新回忆版本。
    return saved.originActionId === value.id && saved.kind === 'RECOLLECTION' && Boolean(recollection
      && recollection.recollectionId === selected.id && recollection.versionId === body.versionId && recollection.versionNumber === selected.currentVersion.number
      && recollection.baselineKind === selected.currentVersion.content.baselineKind && recollection.observation === body.observation)
      && saved.verification === body.verification && saved.tracking === 'TRACKING' && saved.trackingReason === ''
      && value.createdAt === value.updatedAt && saved.createdAt === value.createdAt && saved.updatedAt === saved.createdAt
  } catch { return false }
}
function receiveCreated(value: ReviewAction, selected: Recollection) {
  receiptMismatch.value = false; receiptCommand.value = undefined; writer.error.value = undefined; created.value = value
  queryClient.setQueryData(['review-actions', store.sessionEpoch, 'detail', value.id], value)
  void queryClient.invalidateQueries({ queryKey: ['review-actions', store.sessionEpoch] })
  void queryClient.invalidateQueries({ queryKey: ['repractice', store.sessionEpoch] })
  if (selected.id !== props.value.id || source.value?.currentVersion.id !== selected.currentVersion.id) {
    message.value = '原请求已创建动作，可从已保存入口核对；当前表单输入保留。'
    return
  }
  opened.value = false; source.value = undefined; confirmed.value = false; reviewedEpoch.value = -1
  message.value = '已创建改进动作并固定回忆来源。后续独立复练可按这条验证条件留下人工判断。'
  emit('created', value)
}
function retryCommand() {
  if (!readable.value || writer.busy.value) return
  if (receiptMismatch.value && receiptCommand.value) writer.pending.value = receiptCommand.value
  commandEpoch = store.sessionEpoch; void writer.retry()
}
function receiveDeferred() { if (readable.value && deferred.value) { const receive = deferred.value; deferred.value = undefined; receive() } }
</script>

<template>
  <section class="recollection-action" aria-label="从回忆创建改进动作">
    <header><h3>形成改进动作</h3><p class="field-help">记录你观察到的情况，以及下一次独立回答要达到的具体条件。动作保留这份回忆的确切版本，后续可与其他动作合并。</p></header>
    <p v-if="!readable" class="notice" role="status">当前访问条件限制资料读取，动作输入与未决请求继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="action-content">
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value || (receiptMismatch && receiptCommand)" text :disabled="!readable" :loading="writer.busy.value" @click="retryCommand">{{ receiptMismatch ? '用原请求核对已保存动作' : '用原请求重试' }}</NButton></p>
      <p v-if="deferred" class="notice" role="status">服务器已确认创建，访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已保存动作</NButton></p>
      <p v-if="readError" class="notice error" role="alert">{{ readError }}</p>
      <RouterLink v-if="created" class="text-link" :to="{ name: 'review', query: { action: created.id } }">查看已创建动作：{{ created.title }}</RouterLink>
      <div class="source-status"><p class="field-help">{{ opened && source ? `动作来源：回忆 v${source.currentVersion.number} · 对象修订 ${source.revision}` : current ? `当前回忆 v${current.currentVersion.number} · 对象修订 ${current.revision}` : '正在核对回忆版本' }}</p><NButton text size="small" :disabled="blocked || reviewing" :loading="reviewing" @click="reviewCurrent">重新核对当前回忆</NButton></div>
      <p v-if="current?.status === 'ARCHIVED'" class="notice">回忆已归档。新建动作前，请先恢复并核对当前版本。</p>
      <NButton v-if="!opened && current?.status === 'ACTIVE'" class="open-button" :disabled="blocked || reviewing || !!readError" @click="open">从当前回忆创建动作</NButton>
      <div v-if="opened" class="action-form">
        <p v-if="source" class="field-help">来源：{{ source.currentVersion.content.title }} · {{ recollectionBaselineLabels[source.currentVersion.content.baselineKind] }}。人工观察在创建后固定，动作说明和验证条件可另行修订。</p>
        <section v-if="sourceStale" class="source-review"><h4>核对来源后继续</h4><p class="field-help">回忆或访问条件已变化，四项输入保留。先重新读取当前回忆，版本有变化时明确采用新的来源。</p><template v-if="current"><p>当前：{{ current.currentVersion.content.title }} · v{{ current.currentVersion.number }} · 修订 {{ current.revision }}</p><details><summary>阅读当前问题与不确定性</summary><h5>记得的问题</h5><p class="preserve">{{ current.currentVersion.content.question }}</p><h5>不确定性</h5><p class="preserve">{{ current.currentVersion.content.uncertainty }}</p></details><NButton v-if="current.status === 'ACTIVE' && source && (source.id !== current.id || source.revision !== current.revision || source.currentVersion.id !== current.currentVersion.id)" :disabled="blocked || reviewing || !!readError" @click="adoptCurrentSource">采用当前回忆修订，保留动作输入</NButton></template></section>
        <label>动作标题<NInput v-model:value="title" :disabled="blocked || reviewing" placeholder="例如：补充事务超时后的补偿边界" /><span class="field-help">{{ questionTextLength(title) }} / 200 字符</span></label>
        <label>补充说明（可选）<NInput v-model:value="detail" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }" :disabled="blocked || reviewing" placeholder="具体要准备或练习什么" /><span class="field-help">{{ questionTextLength(detail) }} / 2000 字符</span></label>
        <label>我的观察<NInput v-model:value="observation" type="textarea" :autosize="{ minRows: 3, maxRows: 12 }" :disabled="blocked || reviewing" placeholder="根据这份回忆，你注意到了什么？请保留自己的不确定性。" /><span class="field-help">{{ questionTextLength(observation) }} / 5000 字符 · 这段文字会固定为本动作的回忆来源观察</span></label>
        <label>本条来源的验证条件<NInput v-model:value="verification" type="textarea" :autosize="{ minRows: 3, maxRows: 10 }" :disabled="blocked || reviewing" placeholder="下一次独立回答出现什么具体内容，才算处理了这条观察？" /><span class="field-help">{{ questionTextLength(verification) }} / 2000 字符</span></label>
        <p v-if="problem" class="field-help">{{ problem }}</p>
        <NCheckbox v-model:checked="confirmed" :disabled="!canCreate">我已核对确切回忆版本、人工观察和验证条件，确认创建这条动作。</NCheckbox>
        <div class="row-actions"><NButton type="primary" :disabled="!canCreate || !confirmed" :loading="writer.busy.value" @click="create">创建改进动作</NButton><NButton :disabled="blocked || reviewing" @click="close">关闭创建表单</NButton></div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.recollection-action, .action-content, .action-form { display: grid; gap: 18px; min-width: 0; }
.recollection-action { padding-top: 22px; border-top: 1px solid var(--line); }
header, .source-review { display: grid; gap: 10px; min-width: 0; }
h3 { margin: 0; font-size: 17px; } h4 { margin: 0; font-size: 14px; } h5 { margin: 15px 0 7px; font-size: 13px; } p { margin: 0; }
label { display: grid; gap: 8px; font-size: 13px; } .source-status, .row-actions { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; } .row-actions { justify-content: start; }
.open-button, .source-review > .n-button { justify-self: start; } .source-review { border: 1px solid var(--line); background: var(--surface-soft); padding: 15px; border-radius: 6px; font-size: 13px; }
.preserve { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; max-width: 75ch; } .text-link { justify-self: start; overflow-wrap: anywhere; }
summary { cursor: pointer; padding: 7px 0; } summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }
</style>
