<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { QuestionInput } from '../../api/bank'
import { cloneQuestion, emptyQuestion, questionProblem } from '../../api/bank'
import type { Recollection, RecollectionQuestionAdoption, RecollectionQuestionLookup } from '../../api/recollection'
import { recollectionQuestionAdoptionValid, recollectionQuestionLookupValid, recollectionValid } from '../../api/recollection'
import { reviewDate } from '../../api/review'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const QuestionForm = defineAsyncComponent(() => import('../bank/QuestionForm.vue'))
const props = defineProps<{ value: Recollection; disabled?: boolean }>()
const emit = defineEmits<{ created: [value: RecollectionQuestionAdoption] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const active = ref(true), opened = ref(false), confirmed = ref(false), reviewing = ref(false)
const current = shallowRef<Recollection>(), source = shallowRef<Recollection>()
const content = ref<QuestionInput>(emptyQuestion()), initialContent = ref(''), reviewedEpoch = ref(-1)
const known = shallowRef<RecollectionQuestionLookup>(), created = shallowRef<RecollectionQuestionAdoption>()
const readError = ref(''), lookupError = ref(''), message = ref(''), receiptMismatch = ref(false)
const receiptCommand = shallowRef<NonNullable<typeof writer.pending.value>>()
const deferred = shallowRef<() => void>()
const state = reactive({ dirty: false, locked: false }), stateKey = `recollection-question-${props.value.id}`
let alive = true, generation = 0, reviewSequence = 0, commandEpoch = store.sessionEpoch
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && recollectionValid(props.value))
const locked = computed(() => writer.locked.value || Boolean(deferred.value) || (receiptMismatch.value && Boolean(receiptCommand.value)))
const blocked = computed(() => !readable.value || locked.value || receiptMismatch.value)
const versionId = computed(() => opened.value && source.value ? source.value.currentVersion.id : current.value?.currentVersion.id ?? props.value.currentVersion.id)
const contentSignature = computed(() => JSON.stringify(content.value))
const currentSignature = computed(() => JSON.stringify(current.value)), sourceSignature = computed(() => JSON.stringify(source.value))
const sourceStale = computed(() => !source.value || !current.value || source.value.id !== current.value.id || source.value.status !== 'ACTIVE'
  || current.value.status !== 'ACTIVE' || source.value.revision !== current.value.revision || source.value.currentVersion.id !== current.value.currentVersion.id
  || JSON.stringify(source.value.currentVersion) !== JSON.stringify(current.value.currentVersion) || reviewedEpoch.value !== store.sessionEpoch)
const dirty = computed(() => opened.value && (contentSignature.value !== initialContent.value || confirmed.value))
const problem = computed(() => {
  try {
    const message = questionProblem(content.value)
    if (message) return message
    return containsInvalidText(content.value) ? '题目字段不能包含 NUL 或不完整字符，请核对后再保存。' : ''
  } catch { return '题目字段不完整，请核对最终内容。' }
})
const key = computed(() => ['recollections', store.sessionEpoch, 'question-adoption', props.value.id, versionId.value])
const lookup = useQuery({ queryKey: key, enabled: readable, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, id = props.value.id, version = versionId.value
  const value = await read<RecollectionQuestionLookup>(`/review/recollections/${id}/versions/${version}/question`)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation || id !== props.value.id || version !== versionId.value) throw changed()
  if (!recollectionQuestionLookupValid(value, id, version)) throw mismatch('已采用题目记录与当前回忆版本不一致，请重新读取。')
  return value
} })
const canAdopt = computed(() => opened.value && !blocked.value && !reviewing.value && !readError.value && !sourceStale.value && !problem.value
  && Boolean(known.value) && known.value!.question === null && !lookup.error.value && !lookupError.value && !lookup.isFetching.value)

watch([() => props.value, readable], () => {
  if (!readable.value || !recollectionValid(props.value)) return
  const prior = current.value
  if (!prior || prior.id !== props.value.id || props.value.revision > prior.revision) current.value = copy(props.value)
  else if (prior.revision === props.value.revision && JSON.stringify(prior) !== JSON.stringify(props.value)) readError.value = '相同修订的回忆内容不一致，请重新核对；题目输入保留。'
}, { immediate: true })
watch([readable, () => store.sessionEpoch, () => props.value.id], () => {
  generation++; reviewSequence++; reviewing.value = false; reviewedEpoch.value = -1; confirmed.value = false; known.value = undefined; lookupError.value = ''
}, { flush: 'sync' })
watch(versionId, () => { generation++; known.value = undefined; lookupError.value = ''; confirmed.value = false }, { flush: 'sync' })
watch([lookup.data, lookup.dataUpdatedAt, key, readable], () => {
  if (!alive || !readable.value) return
  const value = queryClient.getQueryData<RecollectionQuestionLookup>(key.value)
  if (!value) return
  if (!recollectionQuestionLookupValid(value, props.value.id, versionId.value)) { lookupError.value = '缓存中的题目绑定不一致，请重新读取。'; return }
  if (known.value?.question && JSON.stringify(known.value.question) !== JSON.stringify(value.question)) { lookupError.value = '本版本已采用的绑定发生异常变化，请重新核对。'; return }
  known.value = value; lookupError.value = ''
}, { immediate: true })
watch([contentSignature, currentSignature, sourceSignature], () => { confirmed.value = false }, { flush: 'sync' })
watch([dirty, locked], ([changed, waiting]) => { state.dirty = changed; state.locked = waiting; store.changes.set(stateKey, state) }, { immediate: true, flush: 'sync' })
watch(writer.error, error => {
  if (!(error instanceof ApiError) || writer.pending.value || receiptMismatch.value) return
  confirmed.value = false
  if (error.status === 409) reviewedEpoch.value = -1
  if (readable.value) void lookup.refetch()
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; generation++; reviewSequence++
  if (store.changes.get(stateKey) === state) store.changes.delete(stateKey)
})

function copy(value: Recollection): Recollection { return JSON.parse(JSON.stringify(value)) as Recollection }
function changed() { return new ApiError(0, 'RECOLLECTION_CONTEXT_CHANGED', '回忆版本或访问条件已变化，请重新核对。') }
function mismatch(text: string) { return new ApiError(502, 'RECOLLECTION_QUESTION_MISMATCH', text) }
function containsInvalidText(value: unknown): boolean {
  if (typeof value === 'string') return /[\u0000\uD800-\uDFFF]/u.test(value)
  if (Array.isArray(value)) return value.some(containsInvalidText)
  return value !== null && typeof value === 'object' && Object.values(value).some(containsInvalidText)
}
function open() {
  if (blocked.value || reviewing.value || opened.value || !current.value || current.value.status !== 'ACTIVE' || readError.value || known.value?.question) return
  source.value = copy(current.value)
  const saved = source.value.currentVersion.content
  // 回忆只提供问题初值；回答片段、反馈与自述缺口不能自动升级为个人参考。
  content.value = { ...emptyQuestion(), title: saved.title, prompt: saved.question, type: saved.type, conditions: saved.conditions }
  initialContent.value = JSON.stringify(content.value); opened.value = true; reviewedEpoch.value = store.sessionEpoch
  confirmed.value = false; message.value = ''
}
function close() {
  if (blocked.value || reviewing.value || !store.canLeave([stateKey])) return
  opened.value = false; source.value = undefined; confirmed.value = false; reviewedEpoch.value = -1
}
async function reviewCurrent() {
  if (blocked.value || reviewing.value) return
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++reviewSequence, id = props.value.id
  reviewing.value = true; readError.value = ''; confirmed.value = false
  try {
    const value = await read<Recollection>(`/review/recollections/${id}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation || sequence !== reviewSequence || id !== props.value.id) return
    if (!recollectionValid(value, id) || (current.value && value.revision < current.value.revision)
      || (current.value?.revision === value.revision && JSON.stringify(current.value) !== JSON.stringify(value))) throw mismatch('重读的回忆修订或版本不一致，题目输入继续保留。')
    current.value = copy(value)
    reviewedEpoch.value = source.value?.revision === value.revision && source.value.currentVersion.id === value.currentVersion.id && value.status === 'ACTIVE' ? epoch : -1
    message.value = value.status === 'ARCHIVED' ? '回忆已归档，题目输入继续保留。请先在回忆页恢复，再核对来源。'
      : source.value && (source.value.revision !== value.revision || source.value.currentVersion.id !== value.currentVersion.id)
        ? '已读取当前回忆，请核对下方来源版本后明确采用当前修订；题目输入保持原样。' : '已重新核对回忆，题目输入保持原样。'
    await nextTick()
    if (alive && readable.value && epoch === store.sessionEpoch && sequence === reviewSequence && id === props.value.id) void lookup.refetch()
  } catch (error) { if (alive && epoch === store.sessionEpoch && sequence === reviewSequence) readError.value = errorText(error) }
  finally { if (alive && sequence === reviewSequence) reviewing.value = false }
}
function adoptCurrentSource() {
  if (blocked.value || reviewing.value || !opened.value || !current.value || current.value.status !== 'ACTIVE' || readError.value) return
  const oldVersion = versionId.value
  source.value = copy(current.value); reviewedEpoch.value = store.sessionEpoch; confirmed.value = false
  message.value = `已明确改用回忆 v${source.value.currentVersion.number}、修订 ${source.value.revision} 作为来源，题目输入保持原样。`
  if (oldVersion === versionId.value) void lookup.refetch()
}
function adopt() {
  if (!canAdopt.value || !confirmed.value || !source.value) return
  const selected = copy(source.value), snapshot = cloneQuestion(content.value)
  const body = { expectedRevision: selected.revision, versionId: selected.currentVersion.id, content: snapshot }
  commandEpoch = store.sessionEpoch; message.value = ''
  let originalCommand: typeof writer.pending.value
  void writer.run<RecollectionQuestionAdoption>(`/review/recollections/${selected.id}/question`, 'POST', body, value => {
    if (!alive) return
    // 回执只按发出时的确切来源和题面核对，后来的补记不能替换原请求或生成第二道题。
    if (!recollectionQuestionAdoptionValid(value, selected.id, selected.currentVersion.id, snapshot)) {
      receiptMismatch.value = true; receiptCommand.value = originalCommand
      writer.error.value = mismatch('题目采用回执与本次来源、题面或首版身份不一致。输入保留，请用原请求核对已保存结果。'); return
    }
    const receive = () => receiveAdoption(value)
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = receive
    else receive()
  })
  originalCommand = writer.pending.value
}
function receiveAdoption(value: RecollectionQuestionAdoption) {
  receiptMismatch.value = false; receiptCommand.value = undefined; writer.error.value = undefined; created.value = value
  queryClient.setQueryData(['recollections', store.sessionEpoch, 'question-adoption', value.link.recollectionId, value.link.recollectionVersionId], { question: value.link })
  void queryClient.invalidateQueries({ queryKey: ['bank', store.sessionEpoch] })
  if (value.link.recollectionId !== props.value.id) { message.value = '原请求对应的回忆已整理为题目，可从已保存入口核对；当前表单输入保留。'; return }
  opened.value = false; source.value = undefined; confirmed.value = false; reviewedEpoch.value = -1
  message.value = '已创建题库首版并保留确切回忆来源，个人参考仍为未审状态。'
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
  <section class="recollection-adoption" aria-label="把回忆问题整理为题目">
    <header><h3>整理为题库题目</h3><p class="field-help">每个已保存回忆版本最多整理为一道题。你确认最终内容后创建题库首版，记录编写曝光并保留确切回忆来源；个人参考继续标为未审。</p></header>
    <p v-if="!readable" class="notice" role="status">当前访问条件限制资料读取，题目输入与未决请求继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="adoption-content">
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value || (receiptMismatch && receiptCommand)" text :disabled="!readable" :loading="writer.busy.value" @click="retryCommand">{{ receiptMismatch ? '用原请求核对已保存题目' : '用原请求重试' }}</NButton></p>
      <p v-if="deferred" class="notice" role="status">服务器已确认采用，访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已保存题目</NButton></p>
      <p v-if="readError" class="notice error" role="alert">{{ readError }}</p>
      <div class="source-status"><p class="field-help">{{ opened && source ? `整理来源：回忆 v${source.currentVersion.number} · 对象修订 ${source.revision}` : current ? `当前回忆 v${current.currentVersion.number} · 对象修订 ${current.revision}` : '正在核对回忆版本' }}</p><NButton text size="small" :disabled="blocked || reviewing" :loading="reviewing" @click="reviewCurrent">重新核对当前回忆</NButton></div>
      <p v-if="current?.status === 'ARCHIVED'" class="notice">回忆已归档。可阅读已采用题目；如需整理当前版本，请先在回忆页恢复。</p>
      <p v-if="lookup.isPending.value && !known" class="field-help" role="status">正在核对本版本是否已整理为题目…</p>
      <p v-if="lookup.error.value || lookupError" class="notice error" role="alert">{{ lookupError || errorText(lookup.error.value) }}<NButton text :disabled="!readable || lookup.isFetching.value" @click="lookup.refetch()">重读本版本采用记录</NButton></p>
      <div v-if="known?.question" class="adopted-record"><strong>本回忆版本已整理为题目</strong><span class="field-help">{{ reviewDate(known.question.createdAt) }}</span><RouterLink class="text-link" :to="{ name: 'bank', query: { item: known.question.questionId, version: known.question.questionVersionId } }">打开当时创建的题库首版</RouterLink><p v-if="opened" class="field-help">你的题目输入仍在下方保留，本回忆版本不能重复创建。</p></div>
      <RouterLink v-if="created && created.link.recollectionVersionId !== versionId" class="text-link" :to="{ name: 'bank', query: { item: created.link.questionId, version: created.link.questionVersionId } }">打开刚才已保存的题目</RouterLink>
      <NButton v-if="!opened && current?.status === 'ACTIVE' && !known?.question" class="open-button" type="primary" :disabled="blocked || reviewing || !!readError" @click="open">以当前回忆版本整理题目</NButton>
      <div v-if="opened" class="adoption-form">
        <p class="field-help">表单初值仅来自回忆标题、问题、分类与约束。请独立核对并补充个人参考和检查点；回忆片段、回忆反馈及自述缺口未自动填入。</p>
        <section v-if="sourceStale" class="source-review"><h4>核对来源后继续</h4><p class="field-help">回忆修订或访问条件已变化，现有题目输入保留。先重新读取当前回忆；版本有变化时明确选择新的来源。</p><template v-if="current"><p>当前：{{ current.currentVersion.content.title }} · v{{ current.currentVersion.number }} · 修订 {{ current.revision }}</p><details><summary>阅读当前回忆的问题与不确定性</summary><h5>记得的问题</h5><p class="preserve">{{ current.currentVersion.content.question }}</p><h5>记得的条件</h5><p class="preserve">{{ current.currentVersion.content.conditions || '未记录额外条件。' }}</p><h5>不确定性</h5><p class="preserve">{{ current.currentVersion.content.uncertainty }}</p></details><NButton v-if="current.status === 'ACTIVE' && source && (source.revision !== current.revision || source.currentVersion.id !== current.currentVersion.id)" :disabled="blocked || reviewing || !!readError" @click="adoptCurrentSource">采用当前回忆修订，保留题目输入</NButton></template></section>
        <QuestionForm v-if="readable" v-model="content" :disabled="blocked || reviewing || !!known?.question" />
        <p v-if="problem" class="field-help">{{ problem }}</p>
        <NCheckbox v-model:checked="confirmed" :disabled="!canAdopt">我已核对这份确切回忆来源及最终题目，确认创建新题与首版；个人参考和检查点仍未经过技术审阅。</NCheckbox>
        <div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!canAdopt || !confirmed" @click="adopt">确认整理为题目</NButton><NButton :disabled="blocked || reviewing" @click="close">关闭整理表单</NButton></div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.recollection-adoption, .adoption-content, .adoption-form { display: grid; gap: 19px; min-width: 0; }
.recollection-adoption { padding-top: 22px; border-top: 1px solid var(--line); }
header, .adopted-record, .source-review { display: grid; gap: 10px; min-width: 0; }
h3 { margin: 0; font-size: 17px; } h4 { margin: 0; font-size: 14px; } h5 { margin: 15px 0 7px; font-size: 13px; } p { margin: 0; }
.source-status, .row-actions { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; } .row-actions { justify-content: start; }
.open-button, .source-review > .n-button { justify-self: start; } .source-review { border: 1px solid var(--line); background: var(--surface-soft); padding: 15px; border-radius: 6px; font-size: 13px; }
.preserve { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; max-width: 75ch; } .adopted-record { padding: 15px 0; border-block: 1px solid var(--line); }
summary { cursor: pointer; padding: 7px 0; } summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }
</style>
