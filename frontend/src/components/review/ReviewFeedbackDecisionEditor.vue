<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiEvaluationInput } from '../../api/ai'
import type { ReviewDecideFeedback, ReviewDecision, ReviewFeedback } from '../../api/review'
import { reviewChoiceLabels, reviewContentSignature, reviewDate, reviewDecisionRevision, reviewFeedbackValid } from '../../api/review'
import { questionTextLength } from '../../api/bank'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ feedback: ReviewFeedback; input: AiEvaluationInput; disabled?: boolean }>()
const emit = defineEmits<{ changed: [value: ReviewFeedback]; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const current = shallowRef(props.feedback)
const opened = ref(false)
const choice = ref<ReviewDecision['choice']>('ACCEPTED')
const reason = ref('')
const baseline = ref('')
const expectedRevision = ref<number>()
const confirmed = ref(false)
const reading = ref(false)
const message = ref('')
const readError = ref('')
const receiptMismatch = ref(false)
const deferred = shallowRef<ReviewFeedback>()
const draftState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, commandEpoch = store.sessionEpoch
const readable = computed(() => store.sessionActive && !props.disabled)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || reading.value)
const signature = computed(() => JSON.stringify([choice.value, reason.value]))
const dirty = computed(() => opened.value && (signature.value !== baseline.value || confirmed.value))
const decisions = computed(() => [...current.value.decisions].sort((left, right) => right.revision - left.revision))
const revision = computed(() => reviewDecisionRevision(current.value))
const stale = computed(() => expectedRevision.value !== revision.value)
const problem = computed(() => questionTextLength(reason.value) > 1000 ? '原因最多 1000 个字符。' : choice.value === 'DISPUTED' && !reason.value.trim() ? '提出争议时请说明原因。' : '')
const options = Object.entries(reviewChoiceLabels).map(([value, label]) => ({ value, label }))

watch(() => props.feedback, value => {
  if (value.id === current.value.id && value.revision === current.value.revision && reviewDecisionRevision(value) >= revision.value) current.value = value
})
watch([dirty, locked], ([changed, waiting]) => {
  draftState.dirty = changed; commandState.locked = waiting
  store.changes.set('review-decision', draftState); store.changes.set('review-decision-command', commandState)
  emit('state', { dirty: changed, locked: waiting })
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => { generation++; reading.value = false; confirmed.value = false; expectedRevision.value = undefined }, { flush: 'sync' })
watch(writer.error, error => { if (error instanceof ApiError && !writer.pending.value) { confirmed.value = false; expectedRevision.value = undefined } })
onUnmounted(() => {
  alive = false; generation++
  if (store.changes.get('review-decision') === draftState) store.changes.delete('review-decision')
  if (store.changes.get('review-decision-command') === commandState) store.changes.delete('review-decision-command')
  emit('state', { dirty: false, locked: false })
})

function open() {
  if (blocked.value || revision.value >= 20) return
  choice.value = decisions.value[0]?.choice ?? 'ACCEPTED'; reason.value = decisions.value[0]?.reason ?? ''
  baseline.value = signature.value; expectedRevision.value = revision.value; confirmed.value = false; opened.value = true
}
function close() {
  if (blocked.value || !store.canLeave(['review-decision'])) return
  opened.value = false; confirmed.value = false
}
async function reload() {
  if (blocked.value) return
  const epoch = store.sessionEpoch, request = ++generation
  reading.value = true; readError.value = ''; confirmed.value = false
  try {
    const value = await read<ReviewFeedback>(`/review/feedback/${props.feedback.id}?revision=${props.feedback.revision}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || request !== generation) return
    if (!reviewFeedbackValid(value, props.feedback.answer, props.input) || value.id !== props.feedback.id || value.revision !== props.feedback.revision
      || reviewContentSignature(value.content) !== reviewContentSignature(props.feedback.content) || reviewDecisionRevision(value) < revision.value) {
      readError.value = '决定记录与当前反馈修订不一致，请重新读取。'; return
    }
    current.value = value; expectedRevision.value = reviewDecisionRevision(value); receiptMismatch.value = false; writer.error.value = undefined
    emit('changed', value)
    message.value = '已核对这版反馈的最新决定，选择和原因继续保留，请重新确认。'
  } catch (error) { if (alive && request === generation && epoch === store.sessionEpoch) readError.value = errorText(error) }
  finally { if (alive && request === generation) reading.value = false }
}
function receive(value: ReviewFeedback) {
  if (reviewDecisionRevision(value) >= revision.value) current.value = value
  opened.value = false; confirmed.value = false; expectedRevision.value = undefined
  message.value = '已追加这版反馈的用户决定，原反馈和此前决定保留。'
  emit('changed', value)
}
function save() {
  if (blocked.value || receiptMismatch.value || !opened.value || stale.value || !confirmed.value || problem.value || revision.value >= 20 || expectedRevision.value === undefined) return
  const body: ReviewDecideFeedback = { feedbackRevision: current.value.revision, expectedRevision: expectedRevision.value, choice: choice.value, reason: reason.value }
  const signature = reviewContentSignature(current.value.content)
  commandEpoch = store.sessionEpoch; readError.value = ''; message.value = ''
  void writer.run<ReviewFeedback>(`/review/feedback/${current.value.id}/decisions`, 'POST', body, value => {
    if (!alive) return
    const valid = reviewFeedbackValid(value, props.feedback.answer, props.input)
    const decision = valid ? value.decisions.find(item => item.revision === body.expectedRevision + 1) : undefined
    if (!valid || value.id !== props.feedback.id || value.revision !== body.feedbackRevision
      || reviewContentSignature(value.content) !== signature || reviewDecisionRevision(value) !== body.expectedRevision + 1
      || !decision || decision.choice !== body.choice || decision.reason !== body.reason) {
      receiptMismatch.value = true; expectedRevision.value = undefined
      throw new ApiError(502, 'REVIEW_DECISION_MISMATCH', '决定回执与本次请求不一致，输入与原请求保留，请用原请求重试。')
    }
    receiptMismatch.value = false
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = value
    else receive(value)
  })
}
function retry() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="feedback-decisions" aria-label="本版反馈的用户决定">
    <h4>这版反馈的决定</h4>
    <p class="field-help">{{ decisions.length ? `当前：${reviewChoiceLabels[decisions[0]!.choice]} · 第 ${revision} 次决定` : '尚未记录决定；这不代表已确认缺陷。' }} 新反馈修订不会继承这里的决定。</p>
    <details v-if="decisions.length"><summary>查看全部决定与原因</summary><ol><li v-for="item in decisions" :key="item.id"><strong>第 {{ item.revision }} 次 · {{ reviewChoiceLabels[item.choice] }}</strong><time>{{ reviewDate(item.createdAt) }}</time><p>{{ item.reason || '未填写原因' }}</p></li></ol></details>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="readError" class="notice error" role="alert">{{ readError }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retry">用原请求重试</NButton><NButton v-else text :disabled="blocked" @click="reload">核对决定历史并保留输入</NButton></p>
    <p v-if="deferred" class="notice" role="status">登录或资料条件变化后，已确认决定暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已确认决定</NButton></p>
    <NButton v-if="!opened && revision < 20" size="small" :disabled="blocked || receiptMismatch" @click="open">{{ decisions.length ? '记录新的决定' : '接受、争议或忽略' }}</NButton>
    <p v-else-if="revision >= 20" class="field-help">这版反馈已有 20 次决定，历史继续保留。</p>
    <div v-if="opened" class="decision-form"><label>本次决定<NSelect v-model:value="choice" :options="options" :disabled="blocked || receiptMismatch" /></label><label>原因{{ choice === 'DISPUTED' ? '（必填）' : '（可选）' }}<NInput v-model:value="reason" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="blocked || receiptMismatch" /><span class="field-help">{{ questionTextLength(reason) }} / 1000 字符</span></label><p v-if="problem" class="field-help">{{ problem }}</p><p v-if="stale" class="notice">记录或访问条件已变化。请核对决定历史，再确认本次选择。<NButton text :disabled="blocked" :loading="reading" @click="reload">核对历史并保留输入</NButton></p><NCheckbox v-model:checked="confirmed" :disabled="blocked || stale || receiptMismatch || !!problem">确认追加决定并保留原反馈和全部历史</NCheckbox><div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="blocked || receiptMismatch || stale || !confirmed || !!problem || revision >= 20" @click="save">保存决定</NButton><NButton :disabled="blocked" @click="close">收起</NButton></div></div>
  </section>
</template>

<style scoped>
.feedback-decisions, .decision-form { display: grid; gap: 12px; min-width: 0; } .feedback-decisions { border-top: 1px solid var(--line); padding-top: 18px; } h4 { margin: 0; font-size: 14px; } label { display: grid; gap: 7px; font-size: 13px; } .feedback-decisions > .n-button { justify-self: start; } li { margin: 12px 0; } li p { white-space: pre-wrap; overflow-wrap: anywhere; margin-top: 6px; } time { display: block; color: var(--muted); font-size: 12px; } summary { cursor: pointer; font-size: 13px; }
</style>
