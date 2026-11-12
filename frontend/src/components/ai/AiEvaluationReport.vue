<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { useRouter } from 'vue-router'
import { ApiError, errorText, read } from '../../api/client'
import type { AiEvaluationCitation, AiFeedbackChoice, AiTask } from '../../api/ai'
import { aiDate } from '../../api/ai'
import { questionDimensionLabels, questionTextLength } from '../../api/bank'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ task?: AiTask; disabled?: boolean; peerLocked?: boolean; sourceTaskId?: string;
  verifyTask?: (value: AiTask) => boolean; locateObservationId?: string }>()
const emit = defineEmits<{ received: [task: AiTask]; refreshed: [task: AiTask]; locked: [value: boolean] }>()
const store = useWorkspaceStore()
const router = useRouter()
const writer = useCommand()
const editorId = ref('')
const choice = ref<AiFeedbackChoice>('ACCEPTED')
const reason = ref('')
const expectedRevision = ref<number>()
const confirmed = ref(false)
const baseline = ref('')
const reading = ref(false)
const readError = ref('')
const message = ref('')
const deferred = shallowRef<AiTask>()
const selectedEvidence = ref<{ feedbackId: string; index: number }>()
const evidenceMark = ref<HTMLElement>()
const observationElements = new Map<string, HTMLElement>()
const draftState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let readSequence = 0
let locateSequence = 0
let commandEpoch = store.sessionEpoch
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const reportReady = computed(() => Boolean(props.task && (!props.verifyTask || props.verifyTask(props.task))))
const blocked = computed(() => !store.sessionActive || !reportReady.value || Boolean(props.disabled) || Boolean(props.peerLocked) || locked.value || reading.value)
const signature = computed(() => JSON.stringify({ choice: choice.value, reason: reason.value }))
const dirty = computed(() => Boolean(editorId.value) && (signature.value !== baseline.value || confirmed.value))
const input = computed(() => props.task?.manifest.evaluation)
const report = computed(() => props.task?.evaluationReport)
const currentRevision = computed(() => revisionOf(props.task, editorId.value))
const stale = computed(() => expectedRevision.value !== currentRevision.value)
const problem = computed(() => questionTextLength(reason.value) > 1000 ? '原因最多 1000 个字符。' : choice.value === 'DISPUTED' && !reason.value.trim() ? '提出争议时请说明原因。' : '')
const choices: Record<AiFeedbackChoice, string> = { ACCEPTED: '接受', DISPUTED: '提出争议', IGNORED: '忽略' }
const choiceOptions = Object.entries(choices).map(([value, label]) => ({ value, label }))
const coverageLabels = { MENTIONED: '原文有提及', NOT_MENTIONED: '可能未提及', UNCERTAIN: '暂无法判断' }
const evidenceNames: Record<AiEvaluationCitation['kind'], string> = {
  ANSWER_TEXT: '回答原文', ANSWER_NODE_LABEL: '答案图节点名称', ANSWER_NODE_DESCRIPTION: '答案图节点说明',
  ANSWER_EDGE_LABEL: '答案图连线说明', QUESTION_PROMPT: '固定题干', QUESTION_CONDITIONS: '题目适用条件',
  REFERENCE: '个人未审参考', CHECKPOINT: '个人未审检查点',
}
const selected = computed(() => {
  const selection = selectedEvidence.value
  const citation = selection && report.value?.observations.find(item => item.id === selection.feedbackId)?.citations[selection.index]
  if (!citation) return undefined
  const text = fieldText(citation)
  if (text === undefined || !Number.isInteger(citation.start) || !Number.isInteger(citation.end) || citation.start < 0
      || citation.end <= citation.start || citation.end > text.length || text.slice(citation.start, citation.end) !== citation.quote
      || insideSurrogate(text, citation.start) || insideSurrogate(text, citation.end)) return { citation, valid: false, before: '', quote: '', after: '' }
  return { citation, valid: true, before: text.slice(0, citation.start), quote: text.slice(citation.start, citation.end), after: text.slice(citation.end) }
})

watch([dirty, locked], ([changed, waiting]) => {
  draftState.dirty = changed
  commandState.locked = waiting
  store.changes.set('ai-feedback', draftState)
  store.changes.set('ai-feedback-command', commandState)
  emit('locked', waiting)
}, { immediate: true, flush: 'sync' })
watch([signature, currentRevision], () => { confirmed.value = false }, { flush: 'sync' })
watch([() => store.sessionEpoch, () => store.sessionActive, () => props.disabled], () => {
  readSequence++
  reading.value = false
  expectedRevision.value = undefined
  confirmed.value = false
}, { flush: 'sync' })
watch(() => props.task, value => {
  if (props.sourceTaskId && !value) {
    readSequence++; selectedEvidence.value = undefined; expectedRevision.value = undefined; confirmed.value = false
    reading.value = false; message.value = ''; readError.value = ''; deferred.value = undefined
  }
}, { flush: 'sync' })
watch([() => props.locateObservationId, () => props.task?.task.id, () => props.disabled, () => store.sessionActive], async () => {
  const sequence = ++locateSequence, id = props.locateObservationId
  if (!id || !store.sessionActive || props.disabled || !reportReady.value || !report.value?.observations.some(item => item.id === id)) return
  await nextTick()
  if (!alive || sequence !== locateSequence || props.locateObservationId !== id || !store.sessionActive || props.disabled || !reportReady.value) return
  const element = observationElements.get(id)
  element?.focus({ preventScroll: true })
  element?.scrollIntoView({ block: 'center', behavior: 'auto' })
}, { immediate: true, flush: 'post' })
watch(writer.error, error => {
  if (error instanceof ApiError && !writer.pending.value) { confirmed.value = false; expectedRevision.value = undefined }
})
onUnmounted(() => {
  alive = false
  readSequence++; locateSequence++
  observationElements.clear()
  if (store.changes.get('ai-feedback') === draftState) store.changes.delete('ai-feedback')
  if (store.changes.get('ai-feedback-command') === commandState) store.changes.delete('ai-feedback-command')
  emit('locked', false)
})

function history(id: string) { return (props.task?.evaluationDecisions ?? []).filter(item => item.feedbackId === id).sort((a, b) => b.revision - a.revision) }
function revisionOf(task: AiTask | undefined, id: string) { return Math.max(0, ...(task?.evaluationDecisions ?? []).filter(item => item.feedbackId === id).map(item => item.revision)) }
function rememberObservation(id: string, element: unknown) {
  if (element instanceof HTMLElement) observationElements.set(id, element)
  else observationElements.delete(id)
}
function open(id: string) {
  if (blocked.value || editorId.value === id || revisionOf(props.task, id) >= 20) return
  if (dirty.value && !window.confirm('切换反馈项会放弃当前未保存的决定和原因，继续？')) return
  const saved = history(id)[0]
  editorId.value = id
  choice.value = saved?.choice ?? 'ACCEPTED'
  reason.value = saved?.reason ?? ''
  baseline.value = signature.value
  expectedRevision.value = revisionOf(props.task, id)
  confirmed.value = false
  message.value = ''
  readError.value = ''
}
function close() {
  if (blocked.value || (dirty.value && !window.confirm('关闭表单并放弃当前未保存的决定和原因？'))) return
  editorId.value = ''
  reason.value = ''
  confirmed.value = false
}
function formAction(id: string) {
  const answer = input.value
  if (blocked.value || !answer || !props.task || props.task.task.status !== 'SUCCEEDED' || !report.value?.observations.some(item => item.id === id)) return
  // 全局路由守卫先处理未保存决定；入口只传身份，复盘页重读确切报告和决定。
  void router.push({ path: '/review', query: { sessionId: answer.sessionId, questionId: answer.questionId, answerId: answer.answerId,
    taskId: props.task.task.id, observationId: id } })
}
async function reload() {
  if (blocked.value || !props.task || !editorId.value) return
  const epoch = store.sessionEpoch
  const id = editorId.value, taskId = props.task.task.id
  const request = ++readSequence
  reading.value = true
  readError.value = ''
  confirmed.value = false
  try {
    const value = await read<AiTask>(`/ai/tasks/${taskId}`)
    if (!alive || !store.sessionActive || props.disabled || props.peerLocked || epoch !== store.sessionEpoch || request !== readSequence || !props.task) return
    if (value.task.id !== props.task.task.id || value.task.purpose !== 'ANSWER_EVALUATION' || !value.evaluationReport
        || props.verifyTask && !props.verifyTask(value)
        || !value.evaluationReport.observations.some(item => item.id === id) || revisionOf(value, id) < currentRevision.value) {
      readError.value = '服务器记录与当前报告不一致，请重新读取任务。'
      return
    }
    emit('refreshed', value)
    expectedRevision.value = revisionOf(value, id)
    message.value = '已读取最新决定历史，你的选择和原因继续保留，请再次确认后保存。'
  } catch (error) { if (alive && epoch === store.sessionEpoch && request === readSequence) readError.value = errorText(error) }
  finally { if (alive && request === readSequence) reading.value = false }
}
function receive(value: AiTask) {
  if (!props.task || value.task.id !== props.task.task.id || value.task.purpose !== 'ANSWER_EVALUATION' || !value.evaluationReport
    || props.verifyTask && !props.verifyTask(value)) {
    readError.value = '决定回执与当前报告不一致，请核对最新任务状态。'
    expectedRevision.value = undefined
    return
  }
  editorId.value = ''
  reason.value = ''
  confirmed.value = false
  message.value = '已追加保存这项用户决定，原报告和此前决定保留。'
  emit('received', value)
}
function save() {
  if (blocked.value || !props.task || !editorId.value || stale.value || !confirmed.value || problem.value || expectedRevision.value === undefined || currentRevision.value >= 20) return
  commandEpoch = store.sessionEpoch
  message.value = ''
  readError.value = ''
  const taskId = props.task.task.id, feedbackId = editorId.value
  const request = { expectedRevision: expectedRevision.value, choice: choice.value, reason: reason.value }
  void writer.run<AiTask>(`/ai/tasks/${taskId}/feedback/${feedbackId}/decisions`, 'POST', request, value => {
    if (!alive) return
    const saved = value?.evaluationDecisions?.filter(item => item.feedbackId === feedbackId && item.revision === request.expectedRevision + 1)
    // 反馈修订独立递增；先核对本次追加的项和选择，再允许清空编辑器或延后接收。
    if (value?.task?.id !== taskId || value.task.purpose !== 'ANSWER_EVALUATION'
      || !value.evaluationReport?.observations.some(item => item.id === feedbackId)
      || saved?.length !== 1 || saved[0]!.choice !== request.choice) {
      expectedRevision.value = undefined
      throw new ApiError(502, 'AI_TASK_MISMATCH', '反馈决定回执与本次反馈项、修订或选择不一致，输入和原请求继续保留，请用原请求重试。')
    }
    // 严格回链失权后的成功回执只保留原请求待重核，不把含报告的回执存成可读来源。
    if (props.sourceTaskId && (commandEpoch !== store.sessionEpoch || !store.sessionActive || props.disabled || props.peerLocked
      || !reportReady.value || props.sourceTaskId !== taskId || !props.verifyTask?.(value))) {
      throw new ApiError(0, 'AI_SOURCE_RECHECK_REQUIRED', '报告来源或访问条件已变化，原请求继续保留；重新核对确切来源后可用原请求重试。')
    }
    if (commandEpoch !== store.sessionEpoch || !store.sessionActive || props.disabled || props.peerLocked) deferred.value = value
    else receive(value)
  })
}
function retryCommand() { if (store.sessionActive && !props.disabled && !props.peerLocked && reportReady.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (store.sessionActive && !props.disabled && !props.peerLocked && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
function fieldText(citation: AiEvaluationCitation): string | undefined {
  const value = input.value
  if (!value) return undefined
  switch (citation.kind) {
    case 'ANSWER_TEXT': return citation.elementId === null ? value.answer.text : undefined
    case 'QUESTION_PROMPT': return citation.elementId === null ? value.question.prompt : undefined
    case 'QUESTION_CONDITIONS': return citation.elementId === null ? value.question.conditions : undefined
    case 'REFERENCE': return citation.elementId === null ? value.standard?.referenceAnswer : undefined
    case 'ANSWER_NODE_LABEL': return value.answer.nodes.find(node => node.id === citation.elementId)?.label
    case 'ANSWER_NODE_DESCRIPTION': return value.answer.nodes.find(node => node.id === citation.elementId)?.description
    case 'ANSWER_EDGE_LABEL': return value.answer.edges.find(edge => edge.id === citation.elementId)?.label
    case 'CHECKPOINT': return value.standard?.checkpoints.find(point => point.id === citation.elementId)?.text
  }
}
function insideSurrogate(text: string, offset: number): boolean {
  return offset > 0 && offset < text.length && text.charCodeAt(offset - 1) >= 0xd800 && text.charCodeAt(offset - 1) <= 0xdbff
    && text.charCodeAt(offset) >= 0xdc00 && text.charCodeAt(offset) <= 0xdfff
}
async function locate(feedbackId: string, index: number) {
  selectedEvidence.value = { feedbackId, index }
  await nextTick()
  evidenceMark.value?.focus({ preventScroll: true })
  evidenceMark.value?.scrollIntoView({ behavior: 'auto', block: 'center' })
}
</script>

<template>
  <section class="evaluation-report" aria-label="确切答案证据报告">
    <header v-if="report && input"><p class="section-label">AI 未审报告 · 第 {{ input.answerNumber }} 版提交</p><h3>原文依据与逐项观察</h3><p class="field-help">这份报告固定于答案 {{ input.answerId }}，后续修订不会改变此报告。接受一项观察只记录你的决定。</p><p class="report-text">{{ report.summary }}</p><p class="report-text report-limit">{{ report.limitations }}</p><p class="field-help">{{ report.validationNote }}</p></header>
    <p v-if="sourceTaskId && !task" class="notice" role="status">确切报告正在核对或暂不可读，报告原文已清除。未保存决定和待确认的原请求继续保留；恢复来源核对后可继续。</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!store.sessionActive || !!disabled || !!peerLocked || !reportReady" @click="retryCommand">用原请求重试</NButton><NButton v-else text :disabled="blocked || !editorId" @click="reload">核对决定历史并保留输入</NButton></p>
    <p v-if="deferred" class="notice" role="status">已确认的决定在登录或资料条件变化后到达。<NButton text :disabled="!store.sessionActive || !!disabled || !!peerLocked" @click="receiveDeferred">接收已确认决定</NButton></p>
    <p v-if="readError" class="notice error" role="alert">{{ readError }}</p>
    <template v-if="report && input && task">
    <section v-if="selected" class="evidence-locator" aria-label="引用原文定位"><header><h4>{{ evidenceNames[selected.citation.kind] }}</h4><p class="field-help">第 {{ input.answerNumber }} 版提交 · {{ selected.citation.elementId ? `元素 ${selected.citation.elementId} · ` : '' }}UTF-16 [{{ selected.citation.start }}, {{ selected.citation.end }})</p></header><p v-if="!selected.valid" role="alert" class="notice error">引用与固定输入不吻合，无法安全定位，请核对服务器报告。</p><pre v-else class="exact-evidence"><span>{{ selected.before }}</span><mark ref="evidenceMark" tabindex="-1">{{ selected.quote }}</mark><span>{{ selected.after }}</span></pre><p class="field-help">高亮直接来自本报告的固定字段原文；图元素只定位当时的名称或说明，参考与检查点保持个人未审身份。</p></section>
    <article v-for="item in report.observations" :key="item.id" :ref="element => rememberObservation(item.id, element)" tabindex="-1"
      class="observation" :class="{ 'located-observation': locateObservationId === item.id }" :aria-label="`AI 观察 ${item.id}`">
      <header><h4>{{ questionDimensionLabels[item.dimension] }} · {{ coverageLabels[item.coverage] }}</h4><span class="field-help">{{ item.id }}</span></header>
      <p class="report-text">{{ item.observation }}</p>
      <p v-if="item.coverage === 'NOT_MENTIONED'" class="field-help">这是对本次所查范围可能缺项的观察，不代表技术错误或能力缺陷。</p>
      <dl><template v-if="item.condition"><dt>适用条件</dt><dd>{{ item.condition }}</dd></template><dt>观察依据</dt><dd>{{ item.basis }}</dd><dt>判断限制</dt><dd>{{ item.limitation }}</dd><dt>建议行动</dt><dd>{{ item.suggestion }}</dd></dl>
      <div class="evidence-list"><h5>本项原文依据</h5><p v-if="!item.citations.length" class="field-help">没有可定位引用，请结合上述限制判断这项不确定观察。</p><figure v-for="(citation, index) in item.citations" :key="index"><blockquote>{{ citation.quote }}</blockquote><figcaption><NButton text size="small" @click="locate(item.id, index)">定位{{ evidenceNames[citation.kind] }}</NButton><span>{{ citation.elementId ? `元素 ${citation.elementId} · ` : '' }}UTF-16 [{{ citation.start }}, {{ citation.end }})</span></figcaption></figure></div>
      <div class="decision-history"><h5>你的决定</h5><p v-if="!history(item.id).length" class="field-help">尚未记录决定。</p><p v-else class="field-help">当前：{{ choices[history(item.id)[0]!.choice] }} · 第 {{ history(item.id)[0]!.revision }} 次决定</p><details v-if="history(item.id).length"><summary>查看全部决定与原因</summary><ol><li v-for="decision in history(item.id)" :key="decision.id"><strong>第 {{ decision.revision }} 次 · {{ choices[decision.choice] }}</strong><time>{{ aiDate(decision.createdAt) }}</time><p>{{ decision.reason || '未填写原因。' }}</p></li></ol></details><p v-if="revisionOf(task, item.id) >= 20" class="field-help">此项已有 20 次决定记录，已达上限；历史继续保留。</p><NButton v-else-if="editorId !== item.id" size="small" :disabled="blocked" @click="open(item.id)">{{ history(item.id).length ? '记录新的决定' : '接受、争议或忽略' }}</NButton></div>
      <div v-if="editorId === item.id" class="decision-form"><label>本次决定<NSelect v-model:value="choice" :options="choiceOptions" :disabled="blocked" /></label><label>原因{{ choice === 'DISPUTED' ? '（必填）' : '（可选）' }}<NInput v-model:value="reason" type="textarea" :disabled="blocked" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="说明采用、争议或忽略的理由，原反馈继续保留。" /><span class="field-help">{{ questionTextLength(reason) }} / 1000 字符</span></label><p v-if="problem" class="field-help">{{ problem }}</p><p v-if="stale" class="notice">决定历史或访问条件已变化。选择与原因继续保留，请核对最新记录后再次确认。</p><NCheckbox v-model:checked="confirmed" :disabled="blocked || stale || !!problem || currentRevision >= 20">确认追加本次用户决定，保留原报告及此前决定</NCheckbox><div class="row-actions"><NButton v-if="stale" :disabled="blocked" :loading="reading" @click="reload">核对历史并保留输入</NButton><NButton type="primary" :disabled="blocked || stale || !confirmed || !!problem || currentRevision >= 20" :loading="writer.busy.value" @click="save">保存本项决定</NButton><NButton :disabled="blocked" @click="close">关闭决定表单</NButton></div></div>
      <div class="row-actions"><NButton size="small" :disabled="blocked || task.task.status !== 'SUCCEEDED'" @click="formAction(item.id)">形成改进动作</NButton><span class="field-help">单独记录行动与验证条件，保留这项观察及已有决定。</span></div>
    </article>
    </template>
  </section>
</template>

<style scoped>
.evaluation-report { display: grid; gap: 20px; border-top: 1px solid var(--line); padding-top: 22px; min-width: 0; }
header, .observation, .evidence-list, .decision-history, .decision-form { display: grid; gap: 13px; min-width: 0; } h3 { margin: 0; font-size: 18px; } h4 { margin: 0; font-size: 15px; } h5 { margin: 0; font-size: 13px; }
.report-text, dd, blockquote, .decision-history li p { white-space: pre-wrap; overflow-wrap: anywhere; } .report-limit { color: var(--muted); font-size: 13px; }
.observation { border-top: 1px solid var(--line); padding-top: 20px; } .observation > header { display: flex; gap: 15px; justify-content: space-between; align-items: center; }
.located-observation { border-left: 3px solid var(--brand); padding-left: 14px; } .observation:focus { outline: 2px solid var(--brand); outline-offset: 5px; }
dl { display: grid; grid-template-columns: 82px minmax(0, 1fr); gap: 10px 15px; margin: 0; font-size: 13px; } dt { color: var(--muted); } dd { margin: 0; }
figure { margin: 0; border-left: 2px solid var(--brand); padding-left: 13px; } blockquote { margin: 0 0 8px; font-size: 13px; } figcaption { display: grid; gap: 5px; justify-items: start; } figcaption span, time { color: var(--muted); font-size: 11px; overflow-wrap: anywhere; }
.evidence-locator { display: grid; gap: 12px; padding: 16px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); min-width: 0; }
.exact-evidence { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.8 var(--font-body, sans-serif); max-height: 500px; overflow-y: auto; } mark { background: oklch(91% .08 85); color: var(--text); } mark:focus { outline: 2px solid var(--brand); outline-offset: 3px; }
.decision-history > .n-button { justify-self: start; } summary { cursor: pointer; font-size: 13px; color: var(--muted); } ol { margin: 12px 0 0; padding-left: 22px; } li { padding: 8px 0; font-size: 13px; } li time { display: block; margin: 5px 0; }
.decision-form { padding: 16px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); } label { display: grid; gap: 7px; color: var(--muted); font-size: 13px; }
@media (max-width: 650px) { dl { grid-template-columns: 1fr; gap: 7px; } dd { margin-bottom: 7px; } .observation > header { align-items: start; } }
</style>
