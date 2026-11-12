<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import type { AiEvaluationInput } from '../../api/ai'
import type { ReviewActionCandidate, ReviewCreateFeedback, ReviewFeedback, ReviewFeedbackHistoryPage, ReviewSaveFeedback } from '../../api/review'
import { cloneReviewContent, emptyReviewFeedback, reviewContentSignature, reviewCoverageLabels, reviewDate, reviewFeedbackProblem, reviewFeedbackValid, reviewKindLabels, reviewManualCandidate } from '../../api/review'
import { questionDimensionOptions, questionTextLength } from '../../api/bank'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import ReviewEvidenceEditor from './ReviewEvidenceEditor.vue'
import ReviewFeedbackReadout from './ReviewFeedbackReadout.vue'
import ReviewFeedbackDecisionEditor from './ReviewFeedbackDecisionEditor.vue'

const props = defineProps<{ id?: string; input: AiEvaluationInput; revision?: number; disabled?: boolean }>()
const emit = defineEmits<{ changed: []; created: [value: ReviewFeedback]; source: [value: ReviewActionCandidate]; close: [] }>()
const store = useWorkspaceStore()
const router = useRouter()
const writer = useCommand()
const current = shallowRef<ReviewFeedback>()
const latest = shallowRef<ReviewFeedback>()
const content = ref(emptyReviewFeedback())
const baseline = ref(reviewContentSignature(content.value))
const created = shallowRef<ReviewFeedback>()
const tab = ref<'edit' | 'history'>(props.revision ? 'history' : 'edit')
const chosenHistory = shallowRef<ReviewFeedback>()
const historyBefore = ref<number>()
const historyCursors = ref<(number | undefined)[]>([])
const serverCopy = shallowRef<ReviewFeedback>()
const serverLoading = ref(false)
const serverError = ref('')
const needsReview = ref(false)
const receiptMismatch = ref(false)
const message = ref('')
const deferred = shallowRef<() => void>()
const decisionState = ref({ dirty: false, locked: false })
const draftState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, comparisonGeneration = 0, commandEpoch = store.sessionEpoch
const readable = computed(() => store.sessionActive && !props.disabled)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || decisionState.value.locked || Boolean(created.value))
const dirty = computed(() => reviewContentSignature(content.value) !== baseline.value)
const stale = computed(() => Boolean(current.value && latest.value && latest.value.revision > current.value.revision))
const editable = computed(() => !blocked.value && !receiptMismatch.value && !serverLoading.value && (!current.value || current.value.revision < 100))
const problem = computed(() => reviewFeedbackProblem(content.value, props.input))
const kindOptions = Object.entries(reviewKindLabels).map(([value, label]) => ({ value, label }))
const coverageOptions = Object.entries(reviewCoverageLabels).map(([value, label]) => ({ value, label }))
const answer = computed(() => ({ sessionId: props.input.sessionId, questionId: props.input.questionId, answerId: props.input.answerId }))
const detail = useQuery({
  queryKey: computed(() => ['review', store.sessionEpoch, 'feedback', props.id]),
  enabled: computed(() => readable.value && Boolean(props.id)),
  queryFn: async () => {
    const epoch = store.sessionEpoch, request = ++generation, id = props.id
    const value = await read<ReviewFeedback>(`/review/feedback/${id}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || request !== generation || id !== props.id) throw new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '复盘读取条件变化，本地输入保留。')
    if (!reviewFeedbackValid(value, answer.value, props.input) || value.id !== id) throw new ApiError(502, 'REVIEW_FEEDBACK_MISMATCH', '反馈与当前提交不一致，请重新读取。')
    return value
  },
})
const requestedHistory = useQuery({
  queryKey: computed(() => ['review', store.sessionEpoch, 'feedback', props.id, 'revision', props.revision]),
  enabled: computed(() => readable.value && Boolean(props.id) && props.revision !== undefined),
  queryFn: async () => {
    const epoch = store.sessionEpoch, id = props.id, revision = props.revision
    const value = await read<ReviewFeedback>(`/review/feedback/${id}?revision=${revision}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || id !== props.id || revision !== props.revision) throw new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '历史读取条件变化。')
    if (!reviewFeedbackValid(value, answer.value, props.input) || value.id !== id || value.revision !== revision) throw new ApiError(502, 'REVIEW_FEEDBACK_MISMATCH', '反馈历史与所选修订不一致。')
    return value
  },
})
const history = useQuery({
  queryKey: computed(() => ['review', store.sessionEpoch, 'feedback-history', props.id, historyBefore.value]),
  enabled: computed(() => readable.value && Boolean(props.id) && tab.value === 'history'),
  queryFn: async () => {
    const epoch = store.sessionEpoch, id = props.id, before = historyBefore.value
    const value = await read<ReviewFeedbackHistoryPage>(`/review/feedback/${id}/history?limit=10${before === undefined ? '' : `&before=${before}`}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || id !== props.id || before !== historyBefore.value) throw new ApiError(0, 'REVIEW_CONTEXT_CHANGED', '历史读取条件变化。')
    if (!Array.isArray(value.items) || value.items.some(item => item.id !== id || !reviewFeedbackValid(item, answer.value, props.input))) throw new ApiError(502, 'REVIEW_FEEDBACK_MISMATCH', '历史记录与当前反馈不一致。')
    return value
  },
})
const displayed = computed(() => tab.value === 'history' ? chosenHistory.value ?? (props.revision ? requestedHistory.data.value : undefined) : current.value)
const draftSourceReady = computed(() => !blocked.value && !dirty.value && !decisionState.value.dirty && !receiptMismatch.value
  && !needsReview.value && !serverLoading.value && !detail.error.value && !detail.isFetching.value
  && Boolean(displayed.value && latest.value && displayed.value.revision === latest.value.revision && displayed.value.id === latest.value.id))

function createKnowledgeDraft() {
  const value = displayed.value
  if (!draftSourceReady.value || !value) return
  void router.push({ name: 'ai', query: { create: '1', purpose: 'REVIEW_DRAFT', reviewKind: value.content.kind,
    feedbackId: value.id, feedbackRevision: String(value.revision), ...value.answer } })
}

watch(detail.data, value => {
  if (!value || !readable.value || !recordLatest(value)) return
  // 后台刷新不能替换未保存正文、未决命令或正在编辑的逐版决定。
  if (!current.value || (!dirty.value && !locked.value && !decisionState.value.dirty && !decisionState.value.locked && !needsReview.value && !receiptMismatch.value)) accept(value)
}, { immediate: true })
watch([dirty, locked], ([changed, waiting]) => {
  draftState.dirty = changed; commandState.locked = waiting
  store.changes.set('review-feedback', draftState); store.changes.set('review-feedback-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => {
  generation++; comparisonGeneration++; serverLoading.value = false; serverCopy.value = undefined; serverError.value = ''
  if (current.value) needsReview.value = true
}, { flush: 'sync' })
watch(() => props.revision, value => { chosenHistory.value = undefined; tab.value = value ? 'history' : 'edit' })
watch(writer.error, error => { if (error instanceof ApiError && error.status === 409) needsReview.value = Boolean(current.value) })
watch(() => content.value.kind, kind => { if (kind === 'SELF_CHECK') content.value.authorLabel = '' })
onUnmounted(() => {
  alive = false; generation++; comparisonGeneration++
  if (store.changes.get('review-feedback') === draftState) store.changes.delete('review-feedback')
  if (store.changes.get('review-feedback-command') === commandState) store.changes.delete('review-feedback-command')
})

function recordLatest(value: ReviewFeedback): boolean {
  if (value.id !== props.id || (latest.value && value.revision < latest.value.revision) || (current.value && value.revision < current.value.revision)) return false
  latest.value = value; return true
}
function accept(value: ReviewFeedback) {
  if (!recordLatest(value)) return
  current.value = value; content.value = cloneReviewContent(value.content); baseline.value = reviewContentSignature(content.value); needsReview.value = false
}
function refresh() { void queryClient.invalidateQueries({ queryKey: ['review'] }); emit('changed') }
function receive(value: ReviewFeedback) {
  if (!recordLatest(value)) { needsReview.value = true; serverCopy.value = latest.value; message.value = `本次修订 ${value.revision} 已确认，服务器还有后续修订，请核对。` }
  else { accept(value); serverCopy.value = undefined; message.value = `已保存反馈修订 ${value.revision}，原答与旧反馈保留。`; queryClient.setQueryData(['review', store.sessionEpoch, 'feedback', value.id], value) }
  refresh()
}
function save() {
  if (!editable.value || problem.value || needsReview.value || stale.value || decisionState.value.dirty || (props.id && !dirty.value)) return
  const snapshot = cloneReviewContent(content.value), signature = reviewContentSignature(snapshot)
  const expected = current.value?.revision ?? 0
  const body: ReviewCreateFeedback | ReviewSaveFeedback = props.id ? { expectedRevision: expected, content: snapshot } : { answer: { ...answer.value }, content: snapshot }
  generation++; commandEpoch = store.sessionEpoch; message.value = ''; serverError.value = ''
  void writer.run<ReviewFeedback>(props.id ? `/review/feedback/${props.id}` : '/review/feedback', props.id ? 'PUT' : 'POST', body, value => {
    if (!alive) return
    if (!reviewFeedbackValid(value, answer.value, props.input) || (props.id && value.id !== props.id) || value.revision !== expected + 1
      || reviewContentSignature(value.content) !== signature || value.decisions.length !== 0) {
      receiptMismatch.value = true; needsReview.value = Boolean(current.value)
      refresh()
      throw new ApiError(502, 'REVIEW_FEEDBACK_MISMATCH', '保存回执与本次请求不一致，输入与原请求保留，请用原请求重试。')
    }
    receiptMismatch.value = false
    const apply = () => {
      if (props.id) receive(value)
      else { created.value = value; baseline.value = reviewContentSignature(content.value); message.value = '反馈已保存。'; refresh(); emit('created', value) }
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = apply
    else apply()
  })
}
async function compareServer() {
  if (blocked.value || !props.id || serverLoading.value) return
  const epoch = store.sessionEpoch, request = ++comparisonGeneration
  generation++; serverLoading.value = true; serverError.value = ''
  try {
    const value = await read<ReviewFeedback>(`/review/feedback/${props.id}`)
    if (!alive || !readable.value || locked.value || epoch !== store.sessionEpoch || request !== comparisonGeneration) return
    if (!reviewFeedbackValid(value, answer.value, props.input) || !recordLatest(value)) { serverError.value = '服务器记录与当前反馈不一致，请重新核对。'; return }
    serverCopy.value = value
    if (current.value?.revision === value.revision && !receiptMismatch.value) needsReview.value = false
  } catch (error) { if (alive && request === comparisonGeneration && epoch === store.sessionEpoch) serverError.value = errorText(error) }
  finally { if (alive && request === comparisonGeneration) serverLoading.value = false }
}
function useServer(keepDraft: boolean) {
  const value = serverCopy.value
  if (blocked.value || !value || serverLoading.value || decisionState.value.dirty || (latest.value && value.revision < latest.value.revision)) return
  if (!window.confirm(keepDraft ? '已对照服务器反馈，保留本地改写并以这份修订继续编辑？保存会提交完整本地内容。' : '采用服务器内容并放弃本地未保存修改？')) return
  const local = cloneReviewContent(content.value)
  accept(value); if (keepDraft) content.value = local
  serverCopy.value = undefined; receiptMismatch.value = false; writer.error.value = undefined
}
function changeTab(value: 'edit' | 'history') {
  if (blocked.value || !store.canLeave(['review-decision', 'review-decision-command'])) return
  decisionState.value = { dirty: false, locked: false }; tab.value = value
}
function chooseHistory(value: ReviewFeedback) {
  if (blocked.value || !store.canLeave(['review-decision', 'review-decision-command'])) return
  decisionState.value = { dirty: false, locked: false }; chosenHistory.value = value
}
function decisionChanged(value: ReviewFeedback) {
  if (current.value?.revision === value.revision) current.value = value
  if (chosenHistory.value?.revision === value.revision) chosenHistory.value = value
  queryClient.setQueryData(['review', store.sessionEpoch, 'feedback', value.id, 'revision', value.revision], value)
  refresh()
}
function selectSource() { if (displayed.value && !blocked.value && !dirty.value && !decisionState.value.dirty && !receiptMismatch.value) emit('source', reviewManualCandidate(displayed.value)) }
function retry() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const apply = deferred.value; deferred.value = undefined; apply() } }
function nextHistory() { if (!blocked.value && !history.isFetching.value && history.data.value?.nextBefore != null) { historyCursors.value.push(historyBefore.value); historyBefore.value = history.data.value.nextBefore } }
</script>

<template>
  <section class="feedback-editor" aria-label="自检与手工反馈">
    <header><div><h3>{{ id ? '反馈与修订' : '新增反馈' }}</h3><p class="field-help">第 {{ input.answerNumber }} 版提交 · 记录个人观察，原答保持不变。</p></div><NButton text size="small" :disabled="locked || decisionState.locked" @click="emit('close')">关闭反馈</NButton></header>
    <p v-if="id && !current && detail.isPending.value" role="status">正在读取反馈…</p>
    <p v-if="detail.error.value" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text :disabled="!readable || locked" @click="detail.refetch()">重新读取</NButton></p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retry">用原请求重试</NButton><NButton v-else-if="id" text :disabled="blocked" @click="compareServer">核对服务器记录</NButton></p>
    <p v-if="deferred" class="notice" role="status">访问条件变化后，已确认结果暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已确认结果</NButton></p>
    <p v-if="message" class="notice" role="status">{{ message }}</p><NButton v-if="created" text @click="emit('created', created)">打开已保存反馈</NButton>
    <template v-if="(current || !id) && !created">
      <nav v-if="current" class="feedback-tabs" aria-label="反馈视图"><NButton :type="tab === 'edit' ? 'primary' : 'default'" size="small" :disabled="blocked" @click="changeTab('edit')">当前反馈 · 修订 {{ current.revision }}</NButton><NButton :type="tab === 'history' ? 'primary' : 'default'" size="small" :disabled="blocked" @click="changeTab('history')">修订历史</NButton></nav>
      <section v-if="serverCopy" class="server-comparison"><h4>服务器副本 · 修订 {{ serverCopy.revision }}</h4><ReviewFeedbackReadout :content="serverCopy.content" :input="input" /><div class="row-actions"><NButton size="small" :disabled="blocked || serverLoading || decisionState.dirty" @click="useServer(false)">采用服务器内容</NButton><NButton size="small" :disabled="blocked || serverLoading || decisionState.dirty || serverCopy.revision >= 100" @click="useServer(true)">保留改写并核对新修订</NButton></div></section>
      <p v-if="serverError" class="notice error" role="alert">{{ serverError }}</p>
      <div v-show="tab === 'edit'" class="feedback-form">
        <p v-if="stale || needsReview" class="notice">反馈或访问条件已变化，本地输入保留。<NButton text :disabled="blocked" :loading="serverLoading" @click="compareServer">核对服务器记录</NButton></p>
        <p class="field-help">{{ dirty ? '有本地修改，尚未保存。' : current ? `当前修订 ${current.revision} · ${reviewDate(current.revisedAt)}` : '填写后明确保存；本地输入不会因刷新自动恢复。' }}</p>
        <div class="form-pair"><label>来源类型<NSelect v-model:value="content.kind" :options="kindOptions" :disabled="!editable" /></label><label>观察维度<NSelect v-model:value="content.dimension" :options="questionDimensionOptions" :disabled="!editable" /></label></div>
        <label v-if="content.kind === 'EXTERNAL_FEEDBACK'">意见提供者称呼<NInput v-model:value="content.authorLabel" :disabled="!editable" placeholder="例如：面试官、同事；这里不验证身份" /><span class="field-help">{{ questionTextLength(content.authorLabel) }} / 120 字符</span></label>
        <label>观察范围<NSelect v-model:value="content.coverage" :options="coverageOptions" :disabled="!editable" /></label>
        <label>观察<NInput v-model:value="content.observation" type="textarea" :autosize="{ minRows: 3, maxRows: 10 }" :disabled="!editable" placeholder="这次回答体现了什么，或需要核实什么？" /><span class="field-help">{{ questionTextLength(content.observation) }} / 2000 字符</span></label>
        <label>适用条件（可选）<NInput v-model:value="content.condition" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" :disabled="!editable" /><span class="field-help">最多 1000 字符</span></label>
        <label>观察依据<NInput v-model:value="content.basis" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }" :disabled="!editable" placeholder="说明为何得出这项观察，随后选择对应原文。" /></label>
        <label>判断限制<NInput v-model:value="content.limitation" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }" :disabled="!editable" placeholder="例如：仅依据本次文字回答，参考尚未核实。" /></label>
        <label>建议行动<NInput v-model:value="content.suggestion" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }" :disabled="!editable" placeholder="希望下一次回答具体改变什么？" /><span class="field-help">依据、限制和建议各最多 1000 字符。</span></label>
        <ReviewEvidenceEditor v-model="content.citations" :input="input" :disabled="!editable" />
        <p v-if="problem" class="field-help" role="status">{{ problem }}</p><p v-if="current && current.revision >= 100" class="notice">这项反馈已达 100 版，原文与决定历史继续可读。</p>
        <NButton type="primary" :loading="writer.busy.value" :disabled="!editable || !!problem || stale || needsReview || decisionState.dirty || (!!id && !dirty)" @click="save">{{ id ? '追加保存反馈修订' : '保存反馈' }}</NButton>
      </div>
      <section v-if="tab === 'history'" class="feedback-history"><p class="field-help">选择确切历史修订后查看或记录该版决定，历史内容保持只读。</p><p v-if="history.isFetching.value" role="status">正在读取修订历史…</p><p v-if="history.error.value" class="notice error" role="alert">{{ errorText(history.error.value) }}<NButton text :disabled="!readable || locked" @click="history.refetch()">重新读取历史</NButton></p><p v-if="requestedHistory.error.value" class="notice error" role="alert">{{ errorText(requestedHistory.error.value) }}<NButton text :disabled="!readable || locked" @click="requestedHistory.refetch()">重新读取指定修订</NButton></p><div class="history-list"><button v-for="item in history.data.value?.items" :key="item.revision" :disabled="blocked" :class="{ selected: displayed?.revision === item.revision }" @click="chooseHistory(item)"><strong>修订 {{ item.revision }} · {{ reviewKindLabels[item.content.kind] }}</strong><span>{{ item.content.observation }}</span><small>{{ reviewDate(item.revisedAt) }}</small></button></div><div class="row-actions"><NButton v-if="historyCursors.length" size="small" :disabled="blocked || history.isFetching.value" @click="historyBefore = historyCursors.pop()">上一页</NButton><NButton v-if="history.data.value?.nextBefore != null" size="small" :disabled="blocked || history.isFetching.value" @click="nextHistory">更早修订</NButton></div></section>
      <section v-if="displayed" class="saved-feedback"><h4>已保存的修订 {{ displayed.revision }}</h4><ReviewFeedbackReadout :content="displayed.content" :input="input" /><ReviewFeedbackDecisionEditor :key="`${displayed.id}/${displayed.revision}`" :feedback="displayed" :input="input" :disabled="!readable || locked || serverLoading || dirty || receiptMismatch" @changed="decisionChanged" @state="decisionState = $event" /><div class="row-actions"><NButton :disabled="blocked || dirty || decisionState.dirty || receiptMismatch" @click="selectSource">以这版反馈形成改进动作</NButton><NButton :disabled="!draftSourceReady" @click="createKnowledgeDraft">整理所选反馈为知识</NButton></div><p class="field-help">动作会保留这版观察和采用时的决定；待处理或有争议的反馈也可以成为核实行动。</p><p class="field-help">知识整理仅从当前反馈修订进入，下一页手选片段并核对外发清单。原答：<RouterLink class="text-link" :to="{ name: 'training', query: { item: displayed.answer.sessionId, question: displayed.answer.questionId, answer: displayed.answer.answerId } }">查看这次确切提交</RouterLink>。<span v-if="latest && displayed.revision !== latest.revision">当前展示历史修订，请先明确切换到当前版。</span></p></section>
    </template>
  </section>
</template>

<style scoped>
.feedback-editor, .feedback-form, .feedback-history, .saved-feedback { display: grid; gap: 16px; min-width: 0; } .feedback-editor { padding-top: 22px; border-top: 1px solid var(--line); } header { display: flex; align-items: start; justify-content: space-between; gap: 15px; } h3 { margin: 0 0 7px; font-size: 18px; } h4 { margin: 0; font-size: 14px; } label { display: grid; gap: 7px; font-size: 13px; } .form-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 15px; } .feedback-tabs { display: flex; gap: 10px; flex-wrap: wrap; } .feedback-form > .n-button { justify-self: start; } .server-comparison { display: grid; gap: 15px; padding: 16px; border: 1px solid var(--line); border-radius: 7px; } .history-list { display: grid; gap: 5px; } .history-list button { min-width: 0; display: grid; gap: 7px; text-align: left; padding: 12px; border-radius: 5px; } .history-list button:hover, .history-list button.selected { background: var(--surface-deep); } .history-list span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 13px; } .history-list small { color: var(--muted); } .saved-feedback { border-top: 1px solid var(--line); padding-top: 20px; }
@media (max-width: 560px) { .form-pair { grid-template-columns: 1fr; } header { flex-wrap: wrap; } }
</style>
