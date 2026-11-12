<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { NButton, NCheckbox, NInput } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiEvaluationSessionCoverage } from '../../api/ai'
import type { TrainingAnswerPage, TrainingContextSnapshot, TrainingEndChecklist, TrainingEventPage, TrainingSession, TrainingTiming } from '../../api/training'
import { formatTrainingDuration, notifyTrainingChanged, trainingContextFingerprint, trainingContextLabels, trainingContextStateLabels, trainingFeedbackLabels, trainingModeLabels, trainingQuestionStatusLabels, trainingStatusLabels } from '../../api/training'
import { responsibilityLabels } from '../../api/preparation'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useRecentVisit } from '../../composables/useRecentVisit'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import { useAiUpdatesStore } from '../../state/aiUpdates'
import TrainingAnswerEditor from './TrainingAnswerEditor.vue'

type QuestionState = { dirty: boolean; locked: boolean }
type EndReview = { revision: number; token: string; checklist: TrainingEndChecklist }
const props = defineProps<{ sessionId: string }>()
const emit = defineEmits<{ changed: [] }>()
const store = useWorkspaceStore()
const route = useRoute()
const router = useRouter()
const access = useTrainingAccessStore()
const recordRecentVisit = useRecentVisit('TRAINING', () => props.sessionId)
const aiUpdates = useAiUpdatesStore()
const writer = useCommand()
const pulseWriter = useCommand()
const current = ref<TrainingSession>()
const timing = ref<TrainingTiming>()
const selectedId = ref(typeof route.query.question === 'string' && route.query.item === props.sessionId ? route.query.question : '')
const questionStates = reactive(new Map<string, QuestionState>())
const deferredReceipt = shallowRef<() => void>()
const deferredPulse = shallowRef<TrainingTiming>()
const previewConfirmed = ref(false)
const contextOpen = ref(false)
const assistanceOpen = ref(false)
const assistanceConfirmed = ref(false)
const assistanceReason = ref('')
const endOpen = ref(false)
const endLoading = ref(false)
const endError = ref('')
const endReview = ref<EndReview>()
const endConfirmed = ref(false)
const eventsOpen = ref(false)
const eventBefore = ref<number>()
const eventCursors = ref<(number | undefined)[]>([])
const knowledgeOpen = ref(false)
const knowledgeBefore = ref<number>()
const knowledgeCursors = ref<(number | undefined)[]>([])
const message = ref('')
const visible = ref(document.visibilityState === 'visible')
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let requestGeneration = 0
let knowledgeGeneration = 0
let commandEpoch = store.sessionEpoch
let pulseEpoch = store.sessionEpoch
let timer: number | undefined
const path = computed(() => `/training/sessions/${props.sessionId}`)
const sessionLocked = computed(() => writer.locked.value || Boolean(deferredReceipt.value))
const questionsLocked = computed(() => [...questionStates.values()].some(value => value.locked))
const locked = computed(() => sessionLocked.value || questionsLocked.value)
const dirtyQuestions = computed(() => [...questionStates.entries()].filter(([, value]) => value.dirty).map(([id]) => id))
const available = computed(() => store.sessionActive && !locked.value && !endLoading.value)
const hasOtherActive = computed(() => Boolean(access.active && access.active.id !== props.sessionId))
const endStale = computed(() => !endReview.value || !current.value || endReview.value.revision !== current.value.revision || endReview.value.token !== current.value.checklistToken)
const mainQuestions = computed(() => current.value?.questions.filter(question => question.kind !== 'FOLLOW_UP') ?? [])
const replayQuestions = computed(() => mainQuestions.value.filter(question => question.kind === 'REPLAY'))
const contextChecksKnown = computed(() => Boolean(current.value?.contextChecks
  && current.value.context.every(context => contextCheck(context))))
const changedContextCount = computed(() => current.value?.context.filter(context => {
  const check = contextCheck(context)
  return check && check.state !== 'CURRENT'
}).length ?? 0)
const stages = computed(() => current.value?.config.stages.map(name => ({ name, questions: mainQuestions.value.filter(question => question.stage === name).flatMap(question => [question, ...current.value!.questions.filter(child => child.kind === 'FOLLOW_UP' && child.followUp?.rootQuestionId === question.id)]) })) ?? [])
const detail = useQuery({
  queryKey: computed(() => ['training-session', store.sessionEpoch, props.sessionId]),
  enabled: computed(() => store.sessionActive),
  queryFn: () => read<TrainingSession>(path.value),
  // 会话级查询覆盖其他标签、其他任务分页中的新追问；题编辑器只在可见时查询自己的记录。
  refetchInterval: computed(() => store.sessionActive && visible.value && current.value && ['RUNNING', 'PAUSED'].includes(current.value.status) ? 3000 : false),
  refetchIntervalInBackground: false,
})
const events = useQuery({
  queryKey: computed(() => ['training-events', store.sessionEpoch, props.sessionId, eventBefore.value]),
  enabled: computed(() => store.sessionActive && eventsOpen.value),
  queryFn: () => read<TrainingEventPage>(`${path.value}/events?limit=20${eventBefore.value === undefined ? '' : `&before=${eventBefore.value}`}`),
})
const evaluationCoverage = useQuery({
  queryKey: computed(() => ['training-evaluation-coverage', store.sessionEpoch, props.sessionId]),
  enabled: computed(() => store.sessionActive && current.value?.status === 'ENDED'),
  queryFn: () => read<AiEvaluationSessionCoverage>(`${path.value}/evaluations/coverage`),
})
const knowledgeHistory = useQuery({
  queryKey: computed(() => ['training-answers', store.sessionEpoch, props.sessionId, selectedId.value, knowledgeBefore.value]),
  enabled: computed(() => store.sessionActive && knowledgeOpen.value && Boolean(current.value?.questions.some(item => item.id === selectedId.value))),
  queryFn: async () => {
    const sessionId = props.sessionId, questionId = selectedId.value, before = knowledgeBefore.value
    const epoch = store.sessionEpoch, generation = knowledgeGeneration
    const value = await read<TrainingAnswerPage>(`/training/sessions/${encodeURIComponent(sessionId)}/questions/${encodeURIComponent(questionId)}/answers?limit=20${before === undefined ? '' : `&before=${before}`}`)
    if (!alive || !store.sessionActive || epoch !== store.sessionEpoch || generation !== knowledgeGeneration
      || sessionId !== props.sessionId || questionId !== selectedId.value || before !== knowledgeBefore.value) {
      throw new ApiError(0, 'TRAINING_SOURCE_CHANGED', '提交记录的上下文已变化，请重新读取当前题目的记录。')
    }
    return value
  },
})
const answerConditionLabels: Record<TrainingAnswerPage['items'][number]['condition'], string> = {
  LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助',
}
const eventLabels: Record<TrainingEventPage['items'][number]['kind'], string> = {
  CREATED: '保存预览', STARTED: '开始', PAUSED: '暂停', RESUMED: '恢复', ENDED: '结束', MATERIAL_ACCESS: '确认辅助',
  DRAFT_SAVED: '保存草稿', ANSWER_SUBMITTED: '提交回答', SKIPPED: '跳过题目', UNSKIPPED: '恢复作答',
  UNOBSERVED_INTERVAL: '未知间隔', REFERENCE_VIEW: '查看参考', BOARD_IMPORT: '读取白板候选', FOLLOW_UP_ADDED: '加入 AI 追问',
}

watch([dirtyQuestions, locked], ([dirty, waiting]) => {
  changeState.dirty = dirty.length > 0
  commandState.locked = waiting
  store.changes.set('training-editor', changeState)
  store.changes.set('training-command', commandState)
}, { immediate: true, flush: 'sync' })
watch(detail.data, value => {
  if (value && store.sessionActive && accept(value)) recordRecentVisit(value.id)
}, { immediate: true })
watch(() => current.value?.revision, () => { previewConfirmed.value = false; assistanceConfirmed.value = false })
watch(() => trainingContextFingerprint(current.value?.contextChecks), () => { previewConfirmed.value = false })
watch(endStale, stale => { if (stale) endConfirmed.value = false })
onBeforeRouteUpdate((to, from) => {
  if (to.name !== 'training' || to.query.item !== props.sessionId
      || (to.query.question === from.query.question && to.query.answer === from.query.answer)) return true
  return !locked.value && !endLoading.value
})
watch([() => route.query.item, () => route.query.question, () => route.query.answer], ([sessionId, questionId]) => {
  // 历史来源导航复用会话组件；切换激活题目，KeepAlive 中各题草稿和原请求继续保留。
  if (sessionId === props.sessionId && typeof questionId === 'string'
      && (!current.value || current.value.questions.some(question => question.id === questionId))) selectQuestion(questionId)
}, { flush: 'sync' })
watch([selectedId, () => props.sessionId, () => store.sessionEpoch, () => store.sessionActive], () => {
  knowledgeGeneration++
  knowledgeOpen.value = false
  knowledgeBefore.value = undefined
  knowledgeCursors.value = []
}, { flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['CHECKLIST_CHANGED', 'REVISION_CONFLICT', 'SESSION_STATE_CONFLICT'].includes(error.code)) {
    endConfirmed.value = false
    endReview.value = undefined
  }
})
watch(pulseWriter.error, error => {
  if (error instanceof ApiError && error.code === 'SESSION_STATE_CONFLICT') refreshSession()
})
watch(() => store.sessionEpoch, () => {
  requestGeneration++
  endLoading.value = false
  endConfirmed.value = false
  endReview.value = undefined
  endError.value = ''
  previewConfirmed.value = false
  assistanceConfirmed.value = false
  eventBefore.value = undefined
  eventCursors.value = []
})
onMounted(() => {
  timer = window.setInterval(() => void pulse(), 20000)
  document.addEventListener('visibilitychange', visibilityChanged)
  window.addEventListener('focus', refreshSession)
  window.addEventListener('studio:training-changed', refreshSession)
})
onUnmounted(() => {
  alive = false
  requestGeneration++
  if (timer !== undefined) window.clearInterval(timer)
  document.removeEventListener('visibilitychange', visibilityChanged)
  window.removeEventListener('focus', refreshSession)
  window.removeEventListener('studio:training-changed', refreshSession)
  if (store.changes.get('training-editor') === changeState) store.changes.delete('training-editor')
  if (store.changes.get('training-command') === commandState) store.changes.delete('training-command')
})

function accept(value: TrainingSession): boolean {
  if (value.id !== props.sessionId || (current.value && value.revision < current.value.revision)) return false
  // 会话与题目分别使用修订号；迟到的会话查询不能让较新的草稿清单倒退。
  if (current.value && value.questions.some(question => question.revision < (current.value!.questions.find(saved => saved.id === question.id)?.revision ?? -1))) return false
  const previousChecks = current.value?.contextChecks
  // 来源代次独立于会话修订；旧回执和迟到查询不能把已知的来源变化倒退。
  const contextChecks = value.contextChecks?.map(check => {
    const previous = previousChecks?.find(saved => saved.kind === check.kind && saved.itemId === check.itemId && saved.versionId === check.versionId)
    if (previous && ((previous.sourceGeneration !== null && check.sourceGeneration !== null && check.sourceGeneration < previous.sourceGeneration)
        || (previous.projectGeneration !== null && check.projectGeneration !== null && check.projectGeneration < previous.projectGeneration))) return previous
    return check
  }) ?? previousChecks?.filter(check => value.context.some(context => context.kind === check.kind && context.itemId === check.itemId && context.versionId === check.versionId)) ?? null
  current.value = { ...value, contextChecks }
  receiveTiming(value.timing)
  if (!value.questions.some(question => question.id === selectedId.value)) selectedId.value = value.questions[0]?.id ?? ''
  if (value.status === 'ENDED') { endOpen.value = false; assistanceOpen.value = false }
  return true
}
function receiveTiming(value: TrainingTiming) {
  if (!timing.value || Date.parse(value.asOf) >= Date.parse(timing.value.asOf)) timing.value = value
}
function refreshSession() {
  if (!alive || !store.sessionActive || document.visibilityState !== 'visible') return
  void detail.refetch()
}
function changed() {
  refreshSession()
  void queryClient.invalidateQueries({ queryKey: ['training-events', store.sessionEpoch, props.sessionId] })
  emit('changed')
}
function receive(value: TrainingSession, notice: string) {
  accept(value)
  message.value = notice
  queryClient.setQueryData(['training-session', store.sessionEpoch, props.sessionId], current.value)
  changed()
  notifyTrainingChanged()
}
function run(action: 'start' | 'pause' | 'resume' | 'end' | 'assistance', body: unknown, notice: string) {
  if (!available.value || !current.value) return
  requestGeneration++
  message.value = ''
  commandEpoch = store.sessionEpoch
  void writer.run<TrainingSession>(`${path.value}/${action}`, 'POST', body, value => {
    if (!alive) return
    // 先核对原命令回执；包括迟到结果在内，校验失败都交给写入器保留原键。
    if (value.id !== props.sessionId) throw new ApiError(502, 'SESSION_MISMATCH', '服务器回执与当前训练不一致，请用原请求重试。')
    const apply = () => {
      receive(value, notice)
      if (action === 'assistance') { assistanceOpen.value = false; assistanceReason.value = '' }
      if (action === 'end') endOpen.value = false
    }
    if (!store.sessionActive || commandEpoch !== store.sessionEpoch) deferredReceipt.value = apply
    else apply()
  })
}
function retry() { if (store.sessionActive && !writer.busy.value && !questionsLocked.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (store.sessionActive && deferredReceipt.value) { const apply = deferredReceipt.value; deferredReceipt.value = undefined; apply() } }
function changeStatus(action: 'start' | 'pause' | 'resume') {
  if (!current.value || !available.value) return
  if (action === 'start' && (!previewConfirmed.value || hasOtherActive.value || current.value.status !== 'DRAFT')) return
  if (action === 'pause' && current.value.status !== 'RUNNING') return
  if (action === 'resume' && current.value.status !== 'PAUSED') return
  run(action, { expectedRevision: current.value.revision }, { start: '训练已开始，题目与背景按确认版本固定', pause: '训练已暂停，可继续保存草稿', resume: '训练已恢复，可以继续提交回答' }[action])
}
function selectQuestion(id: string) { if (!locked.value && !endLoading.value) selectedId.value = id }
function setQuestionState(id: string, value: QuestionState) { questionStates.set(id, value) }
async function prepareEnd() {
  if (!available.value || !current.value || current.value.status === 'ENDED') return
  endOpen.value = true
  endLoading.value = true
  endConfirmed.value = false
  endReview.value = undefined
  endError.value = ''
  const epoch = store.sessionEpoch
  const request = ++requestGeneration
  try {
    const value = await read<TrainingSession>(path.value)
    if (!alive || request !== requestGeneration || epoch !== store.sessionEpoch || !store.sessionActive) return
    if (!accept(value)) { endError.value = '服务器状态已继续变化，请重新读取结束清单。'; return }
    if (value.status !== 'ENDED') endReview.value = { revision: value.revision, token: value.checklistToken, checklist: { ...value.endChecklist } }
  } catch (error) { if (alive && request === requestGeneration && epoch === store.sessionEpoch) endError.value = errorText(error) }
  finally { if (alive && request === requestGeneration) endLoading.value = false }
}
function end() {
  if (!available.value || !current.value || !endReview.value || !endConfirmed.value || endStale.value || dirtyQuestions.value.length) return
  run('end', { expectedRevision: endReview.value.revision, checklistConfirmed: true, expectedChecklistToken: endReview.value.token }, '训练已结束，提交与结束时的服务器草稿均已保留')
}
function requestAssistance() {
  if (!current.value || !['RUNNING', 'PAUSED'].includes(current.value.status) || current.value.materialAccess || current.value.mode === 'LEARNING') return
  assistanceOpen.value = true
  assistanceConfirmed.value = false
}
function confirmAssistance() {
  if (!available.value || !current.value || !assistanceConfirmed.value || !assistanceReason.value.trim() || assistanceReason.value.length > 1000) return
  if (current.value.materialAccess || !['RUNNING', 'PAUSED'].includes(current.value.status)) return
  run('assistance', { expectedRevision: current.value.revision, reason: assistanceReason.value }, '辅助条件已确认，此后的提交会记录已确认辅助条件')
}
async function pulse(manual = false) {
  if (!alive || !store.sessionActive || !visible.value || pulseWriter.busy.value || deferredPulse.value) return
  if (!manual && current.value?.status !== 'RUNNING') return
  pulseEpoch = store.sessionEpoch
  if (pulseWriter.pending.value) { await pulseWriter.retry(); return }
  if (current.value?.status !== 'RUNNING') return
  await pulseWriter.run<TrainingTiming>(`${path.value}/pulse`, 'POST', {}, value => {
    if (!alive) return
    if (!store.sessionActive || pulseEpoch !== store.sessionEpoch) deferredPulse.value = value
    else receiveTiming(value)
  })
}
function receiveDeferredPulse() { if (store.sessionActive && deferredPulse.value) { receiveTiming(deferredPulse.value); deferredPulse.value = undefined } }
function visibilityChanged() {
  visible.value = document.visibilityState === 'visible'
  if (visible.value) { refreshSession(); void pulse() }
}
function nextEvents() { if (events.data.value?.nextCursor !== null && events.data.value?.nextCursor !== undefined) { eventCursors.value.push(eventBefore.value); eventBefore.value = events.data.value.nextCursor } }
function nextKnowledgeAnswers() {
  if (!available.value || knowledgeHistory.isFetching.value || knowledgeHistory.error.value || knowledgeHistory.data.value?.nextCursor == null) return
  knowledgeCursors.value.push(knowledgeBefore.value)
  knowledgeBefore.value = knowledgeHistory.data.value.nextCursor
}
function createKnowledge(answerId: string) {
  if (!available.value || !knowledgeOpen.value || knowledgeHistory.isFetching.value || knowledgeHistory.error.value
    || !knowledgeHistory.data.value?.items.some(item => item.id === answerId)) return
  // 只传递用户所选提交的身份；导航继续经过未保存输入和独立训练的资料守卫。
  void router.push({ name: 'ai', query: { create: '1', purpose: 'TRAINING_DRAFT', sessionId: props.sessionId, questionId: selectedId.value, answerId } })
}
function createFactDraft(answerId: string) {
  if (!available.value || !knowledgeOpen.value || knowledgeHistory.isFetching.value || knowledgeHistory.error.value
    || !current.value || current.value.id !== props.sessionId || !current.value.questions.some(item => item.id === selectedId.value)
    || !knowledgeHistory.data.value?.items.some(item => item.id === answerId)) return
  // 入口仅固定已提交回答三元组；下一页手选片段，题面、评价和参考不加入事实候选。
  void router.push({ name: 'ai', query: { create: '1', purpose: 'FACT_DRAFT', factSourceKind: 'TRAINING', sessionId: props.sessionId, questionId: selectedId.value, answerId } })
}
function reviewAnswer(answerId: string) {
  if (!available.value || !knowledgeOpen.value || knowledgeHistory.isFetching.value || knowledgeHistory.error.value
    || !knowledgeHistory.data.value?.items.some(item => item.id === answerId)) return
  void router.push({ name: 'review', query: { sessionId: props.sessionId, questionId: selectedId.value, answerId } })
}
function contextCheck(context: TrainingContextSnapshot) {
  return current.value?.contextChecks?.find(check => check.kind === context.kind && check.itemId === context.itemId && check.versionId === context.versionId)
}
function contextStateLabel(context: TrainingContextSnapshot): string {
  const check = contextCheck(context)
  return check ? trainingContextStateLabels[check.state] : '尚未核对当前可用性'
}
function contextFields(snapshot: TrainingContextSnapshot): { label: string; text: string }[] {
  if (!snapshot.content || typeof snapshot.content !== 'object') return []
  const content = snapshot.content as Record<string, unknown>
  const fields: [string, string][] = snapshot.kind === 'TARGET' ? [['company', '公司'], ['jd', '职位描述']]
    : snapshot.kind === 'PROJECT' ? [['overview', '项目概述'], ['technology', '技术与版本']]
      : [['scenario', '场景'], ['problem', '问题'], ['action', '本人动作'], ['result', '结果'], ['technology', '技术与版本']]
  const result = fields.flatMap(([key, label]) => typeof content[key] === 'string' && content[key] ? [{ label, text: content[key] as string }] : [])
  if (snapshot.kind === 'TARGET' && Array.isArray(content.requirements)) {
    const requirements = content.requirements.flatMap(value => value && typeof value === 'object' && 'text' in value && typeof value.text === 'string' ? [value.text] : [])
    if (requirements.length) result.push({ label: '当时的要求', text: requirements.join('\n') })
  }
  if (snapshot.kind === 'FACT' && typeof content.responsibility === 'string' && content.responsibility in responsibilityLabels) result.unshift({ label: '责任范围', text: responsibilityLabels[content.responsibility as keyof typeof responsibilityLabels] })
  return result
}
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="training-session" aria-label="训练会话详情">
    <p v-if="!current && detail.isPending.value" class="loading-block" role="status">正在读取训练记录…</p>
    <p v-if="detail.error.value" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text :disabled="!store.sessionActive" @click="refreshSession">重试读取</NButton></p>
    <template v-if="current">
      <header class="session-heading"><p class="section-label">{{ trainingModeLabels[current.mode] }} · {{ trainingStatusLabels[current.status] }}</p><h2>{{ current.title }}</h2><p class="field-help">创建于 {{ date(current.createdAt) }}{{ current.startedAt ? ` · 开始于 ${date(current.startedAt)}` : '' }}{{ current.endedAt ? ` · 结束于 ${date(current.endedAt)}` : '' }}</p></header>
      <p class="field-help" role="status">{{ aiUpdates.message }}</p>
      <div class="session-conditions"><span>{{ current.mode === 'LEARNING' ? '学习条件' : current.materialAccess ? '已确认辅助条件' : '尚未确认站内辅助' }}</span><span><template v-if="mainQuestions.length > replayQuestions.length">{{ mainQuestions.length - replayQuestions.length }} 道主问 · </template><template v-if="replayQuestions.length">{{ replayQuestions.length }} 道同题复练 · </template>{{ current.questions.length - mainQuestions.length }} 道追问</span><span>{{ current.config.timeBudgetMinutes ? `时间预算 ${current.config.timeBudgetMinutes} 分钟` : '未设时间预算' }}</span></div>
      <p class="field-help">资料条件用于记录训练背景。系统只能记录站内操作，不能判断你此前记住、复制或在站外看过的内容。</p>
      <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!store.sessionActive || questionsLocked" @click="retry">用原请求重试</NButton><NButton v-else text :disabled="!store.sessionActive" @click="refreshSession">刷新训练状态</NButton></p><p v-if="deferredReceipt" class="notice" role="status">服务器已确认的结果在会话变化后到达。<NButton text :disabled="!store.sessionActive" @click="receiveDeferred">接收已确认结果</NButton></p>

      <section v-if="current.status === 'DRAFT'" class="preview-confirm"><h3>开始前核对</h3><p>{{ replayQuestions.length ? '同题复练沿用原训练保存的历史题面；开始时核对原答绑定及本次选定背景，历史题面不表示题库当前发布状态。' : '下方题干与选定背景来自保存预览时的确切版本。开始时会再检查来源是否仍可使用。' }}同一时间只能保留一场进行中或暂停的训练。</p><p v-if="hasOtherActive" class="notice">当前另有训练「{{ access.active?.title }}」，请先结束那场训练。</p><NCheckbox v-model:checked="previewConfirmed" :disabled="!available">我已核对题目、阶段、背景与练习条件</NCheckbox><div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!available || !previewConfirmed || hasOtherActive" @click="changeStatus('start')">确认并开始训练</NButton><NButton :disabled="!available" @click="prepareEnd">结束这个预览</NButton></div></section>
      <div v-else-if="current.status !== 'ENDED'" class="row-actions"><NButton v-if="current.status === 'RUNNING'" :disabled="!available" @click="changeStatus('pause')">暂停训练</NButton><NButton v-else type="primary" :disabled="!available" @click="changeStatus('resume')">恢复训练</NButton><NButton v-if="current.mode !== 'LEARNING' && !current.materialAccess" :disabled="!available" @click="requestAssistance">确认辅助条件</NButton><NButton :disabled="!available" @click="prepareEnd">核对结束清单</NButton><NButton text :disabled="!available" @click="refreshSession">刷新状态</NButton></div>

      <section v-if="timing && current.status !== 'DRAFT'" class="timing-panel" aria-label="服务器计时"><div class="timing-grid"><div><span>已记录可观察时间</span><strong>{{ formatTrainingDuration(timing.observedSeconds) }}</strong></div><div><span>已记录未知间隔</span><strong>{{ formatTrainingDuration(timing.unobservedSeconds) }}</strong></div><div><span>已记录暂停</span><strong>{{ formatTrainingDuration(timing.pausedSeconds) }}</strong></div></div><p class="field-help">服务器更新于 {{ date(timing.asOf) }}{{ timing.pendingUnknownSeconds ? `；距上次记录仍有 ${formatTrainingDuration(timing.pendingUnknownSeconds)} 尚未归类` : '' }}{{ timing.pendingPausedSeconds ? `；当前暂停已持续 ${formatTrainingDuration(timing.pendingPausedSeconds)}` : '' }}。页面可见时每 20 秒发送一次记录，长间隔按未知时间保留；可观察时间不代表持续专注。</p><p v-if="timing.budgetReached" class="notice">时间预算已到，训练不会自动结束；请自行决定继续或核对结束清单。</p><p v-if="pulseWriter.error.value" class="field-help" role="status">计时请求尚未恢复：{{ errorText(pulseWriter.error.value) }}<NButton v-if="pulseWriter.pending.value" text :disabled="!store.sessionActive || pulseWriter.busy.value" @click="pulse(true)">原键重试计时记录</NButton><NButton v-else text :disabled="!store.sessionActive" @click="refreshSession">刷新训练状态</NButton></p><p v-if="deferredPulse" class="field-help">计时回执在会话变化后到达。<NButton text :disabled="!store.sessionActive" @click="receiveDeferredPulse">接收服务器计时</NButton></p></section>

      <section v-if="assistanceOpen && current.status !== 'ENDED' && !current.materialAccess" class="assistance-panel"><h3>确认本次训练使用辅助资料</h3><p>确认后可查阅参考、知识和材料，也可导入白板。本场后续提交立即按辅助条件记录，不能恢复成确认前条件；既有提交保留原来的条件。</p><label>查阅目的<NInput v-model:value="assistanceReason" :disabled="!available" type="textarea" :maxlength="1000" placeholder="例如：核对事务隔离级别，再继续完成回答" :autosize="{ minRows: 2, maxRows: 5 }" /></label><NCheckbox v-model:checked="assistanceConfirmed" :disabled="!available">我理解此次确认不可撤销，并同意记录后续答案的辅助条件</NCheckbox><div class="row-actions"><NButton type="primary" :disabled="!available || !assistanceConfirmed || !assistanceReason.trim()" @click="confirmAssistance">确认使用辅助资料</NButton><NButton :disabled="!available" @click="assistanceOpen = false">继续当前条件作答</NButton></div></section>

      <section v-if="endOpen" class="end-panel"><h3>结束前的服务器清单</h3><p v-if="endLoading" role="status">正在读取最新清单…</p><p v-if="endError" class="notice error" role="alert">{{ endError }}</p><dl v-if="endReview" class="checklist"><div><dt>已答题</dt><dd>{{ endReview.checklist.answered }}</dd></div><div><dt>已跳过</dt><dd>{{ endReview.checklist.skipped }}</dd></div><div><dt>仍未答</dt><dd>{{ endReview.checklist.unanswered }}</dd></div><div><dt>未提交草稿</dt><dd>{{ endReview.checklist.unsubmittedDrafts }}</dd></div></dl><p>结束会保留服务器上的提交和草稿，不会代为提交草稿，结束后只能读取或另存。</p><p v-if="dirtyQuestions.length" class="notice">另有 {{ dirtyQuestions.length }} 道题存在本地未保存输入，它们不在服务器清单中。请先切回对应题目，保存答案或处理费用核对表单；若答案无法保存，先另存文字和图，再明确撤销本地修改，最后结束训练。</p><p v-if="endStale && !endLoading" class="notice">需要重新读取清单后再确认。题目状态或草稿变化会使先前确认失效。</p><NCheckbox v-model:checked="endConfirmed" :disabled="!available || endStale || !!dirtyQuestions.length">我已核对清单，理解结束后本场不能继续作答</NCheckbox><div class="row-actions"><NButton :disabled="!available" @click="prepareEnd">重新读取清单</NButton><NButton type="primary" :disabled="!available || !endConfirmed || endStale || !!dirtyQuestions.length" @click="end">确认结束训练</NButton><NButton :disabled="!available" @click="endOpen = false">继续处理作答</NButton></div></section>
      <section v-if="current.status === 'ENDED'" class="ended-panel"><h3>结束时保留的记录</h3><dl class="checklist"><div><dt>已答题</dt><dd>{{ current.endChecklist.answered }}</dd></div><div><dt>已跳过</dt><dd>{{ current.endChecklist.skipped }}</dd></div><div><dt>仍未答</dt><dd>{{ current.endChecklist.unanswered }}</dd></div><div><dt>未提交草稿</dt><dd>{{ current.endChecklist.unsubmittedDrafts }}</dd></div></dl><p class="field-help">结束清单已固定。下方可读取提交、未提交草稿和同题历史，也可另存文字与结构图。</p><p v-if="current.questions.every(question => question.answerCount === 0)" class="field-help">本场没有正式提交，保留的草稿不能作为复练基线或新旧对照的新答案。</p><p class="field-help">独立复练须在结束后明确选择提交建立对照，再逐来源记录判断；结束本身不会生成改善结论。</p><RouterLink class="text-link" :to="{ name: 'training-report', query: { sessionId: current.id } }">查看完整结束报告</RouterLink><RouterLink class="text-link" :to="{ name: 'repractice' }">查看独立复练与对照记录</RouterLink></section>

      <section class="context-panel">
        <header class="panel-heading"><h3>固定背景与训练配置</h3><NButton size="small" @click="contextOpen = !contextOpen">{{ contextOpen ? '收起' : '展开核对' }}</NButton></header>
        <p class="field-help">阶段：{{ current.config.stages.join(' → ') }} · 反馈时机：{{ trainingFeedbackLabels[current.config.feedbackTiming] }} · 每个根主问追问额度：{{ current.config.followUpLimit }}。提交后可明确授权追问或评价；提前打开评价区须确认反馈时机及之后的辅助条件。</p>
        <p v-if="!contextChecksKnown" class="notice" role="status">当前记录尚未提供完整的背景可用性核对。<NButton text :disabled="!store.sessionActive || detail.isFetching.value" @click="refreshSession">重读背景状态</NButton></p>
        <p v-if="changedContextCount" class="notice" role="status">有 {{ changedContextCount }} 项背景当前不用于新追问。原背景、题目和答案仍保留，可继续手工作答；已有追问的背景依据请重新核对。</p>
        <div v-if="contextOpen" class="context-list">
          <p v-if="!current.context.length" class="field-help">这次训练没有选择岗位、项目或事实背景。</p>
          <article v-for="context in current.context" :key="context.versionId">
            <h4>{{ trainingContextLabels[context.kind] }} · {{ context.title }} · 当时版本 v{{ context.number }}</h4>
            <p class="field-help">当前核对：{{ contextStateLabel(context) }}</p>
            <div v-for="field in contextFields(context)" :key="field.label" class="context-field"><h5>{{ field.label }}</h5><p>{{ field.text }}</p></div>
          </article>
          <p class="field-help">上方状态表示现在能否用于新追问，不能替代当时的核实记录。正文保留明确选入训练的固定版本；资料引用、附件与关联知识通过辅助授权后再查阅。</p>
        </div>
      </section>

      <section v-if="current.status === 'ENDED'" class="evaluation-coverage" aria-label="最新提交评价覆盖">
        <header class="panel-heading"><h3>本场评价覆盖</h3><NButton size="small" :disabled="!store.sessionActive" @click="evaluationCoverage.refetch()">刷新</NButton></header>
        <p class="field-help">逐题统计最新提交，旧修订报告保留在该题任务记录中。成功表示报告已生成，不能据此判断技术正确或能力。</p>
        <p v-if="evaluationCoverage.isPending.value" role="status" class="field-help">正在读取评价记录…</p>
        <p v-if="evaluationCoverage.error.value" role="alert" class="notice error">{{ errorText(evaluationCoverage.error.value) }}</p>
        <ul><li v-for="item in evaluationCoverage.data.value?.items" :key="item.questionId">
          <NButton text :disabled="locked || endLoading" @click="selectQuestion(item.questionId)">{{ item.title }}</NButton>
          <span v-if="!item.latestAnswerId">尚未提交{{ item.status === 'SKIPPED' ? ' · 已跳过' : '' }}</span>
          <span v-else-if="!item.taskCount">最新提交尚未评价</span>
          <span v-else>已生成 {{ item.completedCount }} · 处理中 {{ item.pendingCount }} · 未完成 {{ item.failedCount }}</span>
        </li></ul>
      </section>

      <section class="questions-panel"><nav class="question-navigation" aria-label="训练题目"><section v-for="stage in stages" :key="stage.name"><h3>{{ stage.name }}</h3><div class="question-buttons"><button v-for="question in stage.questions" :key="question.id" :disabled="locked || endLoading" :class="{ selected: selectedId === question.id, followup: question.kind === 'FOLLOW_UP' }" :aria-current="selectedId === question.id ? 'step' : undefined" @click="selectQuestion(question.id)"><strong>{{ question.kind === 'REPLAY' ? '同题复练 · ' : question.kind === 'FOLLOW_UP' ? '追问 · ' : '主问 · ' }}{{ question.title }}</strong><span>{{ trainingQuestionStatusLabels[question.status] }}{{ question.hasDraft ? ' · 有服务器草稿' : '' }}{{ questionStates.get(question.id)?.dirty ? ' · 本地未保存' : '' }}</span></button></div></section></nav><p v-if="dirtyQuestions.length" class="field-help">{{ dirtyQuestions.length }} 道题的未保存输入保留在本页，切题不会丢弃；刷新前请逐题保存。</p><KeepAlive :max="60"><TrainingAnswerEditor v-if="selectedId" :key="selectedId" :session="current" :question-id="selectedId" :command-locked="sessionLocked || endLoading" @state="setQuestionState" @changed="changed" @request-assistance="requestAssistance" @select-question="selectQuestion" /></KeepAlive></section>

      <section v-if="selectedId && current.status !== 'DRAFT'" class="knowledge-panel" aria-label="从确切提交复盘与整理知识">
        <header class="panel-heading"><h3>复盘与知识整理</h3><NButton size="small" :disabled="!available" :aria-expanded="knowledgeOpen" @click="knowledgeOpen = !knowledgeOpen">{{ knowledgeOpen ? '收起版本选择' : '选择提交版本' }}</NButton></header>
        <p class="field-help">当前题目：{{ current.questions.find(item => item.id === selectedId)?.title }}。选择一版已提交回答后，再手选要整理的文字或图节点片段；此入口不会直接调用模型。</p>
        <template v-if="knowledgeOpen">
          <p v-if="knowledgeHistory.isPending.value" class="field-help" role="status">正在读取当前题目的提交版本…</p>
          <p v-if="knowledgeHistory.error.value" class="notice error" role="alert">{{ errorText(knowledgeHistory.error.value) }}<NButton text :disabled="!available || knowledgeHistory.isFetching.value" @click="knowledgeHistory.refetch()">重新读取提交</NButton></p>
          <template v-else-if="knowledgeHistory.data.value">
            <p v-if="!knowledgeHistory.data.value.items.length" class="field-help">当前页没有提交记录。草稿需先明确提交，再选择其保存版本。</p>
            <ul class="knowledge-answer-list"><li v-for="answer in knowledgeHistory.data.value.items" :key="answer.id"><div><strong>第 {{ answer.number }} 版提交</strong><span>{{ answerConditionLabels[answer.condition] }} · {{ date(answer.createdAt) }}</span></div><div class="row-actions"><NButton size="small" :disabled="!available || knowledgeHistory.isFetching.value" @click="reviewAnswer(answer.id)">复盘本次提交</NButton><NButton size="small" :disabled="!available || knowledgeHistory.isFetching.value" @click="createKnowledge(answer.id)">整理为知识</NButton><NButton size="small" :disabled="!available || knowledgeHistory.isFetching.value" @click="createFactDraft(answer.id)">从回答片段整理事实候选</NButton></div></li></ul>
            <div class="row-actions"><NButton size="small" :disabled="!available || knowledgeHistory.isFetching.value" @click="knowledgeHistory.refetch()">刷新提交</NButton><NButton v-if="knowledgeCursors.length" size="small" :disabled="!available || knowledgeHistory.isFetching.value" @click="knowledgeBefore = knowledgeCursors.pop()">上一页</NButton><NButton v-if="knowledgeHistory.data.value.nextCursor !== null" size="small" :disabled="!available || knowledgeHistory.isFetching.value" @click="nextKnowledgeAnswers">下一页</NButton></div>
          </template>
        </template>
      </section>

      <section class="events-panel"><header class="panel-heading"><h3>训练过程记录</h3><NButton size="small" @click="eventsOpen = !eventsOpen">{{ eventsOpen ? '收起记录' : '查看记录' }}</NButton></header><template v-if="eventsOpen"><p v-if="events.isPending.value" class="field-help" role="status">正在读取过程记录…</p><p v-if="events.error.value" class="notice error" role="alert">{{ errorText(events.error.value) }}<NButton text @click="events.refetch()">重试</NButton></p><ol class="events-list"><li v-for="event in events.data.value?.items" :key="event.id"><div><strong>{{ eventLabels[event.kind] }}</strong><time>{{ date(event.createdAt) }}</time></div><p>{{ event.detail }}{{ event.durationSeconds !== null ? ` · ${formatTrainingDuration(event.durationSeconds)}` : '' }}</p><span v-if="event.questionId" class="field-help">{{ current.questions.find(question => question.id === event.questionId)?.title || '题目记录' }}</span></li></ol><div v-if="eventCursors.length || events.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!eventCursors.length" @click="eventBefore = eventCursors.pop()">上一页</NButton><NButton size="small" :disabled="events.data.value?.nextCursor == null" @click="nextEvents">下一页</NButton></div></template></section>
    </template>
  </section>
</template>

<style scoped>
.training-session { display: grid; gap: 21px; padding: 18px 27px 30px; min-width: 0; }
.evaluation-coverage { display: grid; gap: 14px; padding-top: 20px; border-top: 1px solid var(--line); }
.evaluation-coverage ul { list-style: none; margin: 0; padding: 0; }
.evaluation-coverage li { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.evaluation-coverage li span { color: var(--muted); font-size: 12px; }
.session-heading { display: grid; gap: 8px; } .session-heading h2 { font-size: 25px; overflow-wrap: anywhere; }
.session-conditions { display: flex; flex-wrap: wrap; gap: 8px; } .session-conditions > span { padding: 5px 9px; border: 1px solid var(--line); border-radius: 5px; color: var(--muted); font-size: 12px; }
h3 { margin: 0; font-size: 16px; } h4 { font-size: 14px; margin: 0; }
.preview-confirm, .assistance-panel, .end-panel, .ended-panel { display: grid; gap: 15px; padding: 20px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
.preview-confirm > p, .assistance-panel > p, .end-panel > p { color: var(--muted); font-size: 13px; max-width: 75ch; }
.assistance-panel label { display: grid; gap: 7px; color: var(--muted); font-size: 13px; }
.timing-panel { display: grid; gap: 14px; border-top: 1px solid var(--line); padding-top: 18px; }
.timing-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 15px; } .timing-grid > div { display: grid; gap: 6px; } .timing-grid span { font-size: 12px; color: var(--muted); } .timing-grid strong { font-size: 17px; font-weight: 550; }
.panel-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.checklist { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin: 0; } .checklist dt { color: var(--muted); font-size: 12px; } .checklist dd { margin: 7px 0 0; font-size: 24px; }
.context-panel, .events-panel, .knowledge-panel { display: grid; gap: 15px; padding-top: 20px; border-top: 1px solid var(--line); }
.knowledge-answer-list { list-style: none; margin: 0; padding: 0; }
.knowledge-answer-list li { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--line); }
.knowledge-answer-list li > div { display: grid; gap: 6px; } .knowledge-answer-list strong { font-size: 13px; font-weight: 550; } .knowledge-answer-list span { color: var(--muted); font-size: 12px; }
.context-list { display: grid; gap: 22px; } .context-list article { display: grid; gap: 14px; } .context-field h5 { margin: 0 0 5px; color: var(--muted); font-size: 12px; } .context-field p { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
.questions-panel { display: grid; gap: 22px; border-top: 1px solid var(--line); padding-top: 22px; min-width: 0; }
.question-navigation { display: grid; gap: 15px; } .question-navigation h3 { color: var(--muted); font-size: 12px; margin-bottom: 8px; }
.question-buttons { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px; }
.question-buttons button { display: grid; gap: 7px; padding: 12px; text-align: left; border: 1px solid var(--line); border-radius: 6px; }
.question-buttons button.selected { border-color: var(--brand); background: var(--surface-soft); } .question-buttons strong { overflow-wrap: anywhere; font-size: 13px; font-weight: 550; } .question-buttons span { color: var(--muted); font-size: 11px; }
.question-buttons button.followup { margin-left: 15px; border-left-width: 3px; }
.events-list { display: grid; gap: 15px; list-style: none; margin: 0; padding: 0; } .events-list li { border-bottom: 1px solid var(--line); padding-bottom: 13px; } .events-list li > div { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; } .events-list time { color: var(--muted); font-size: 12px; } .events-list p { margin: 8px 0; font-size: 13px; overflow-wrap: anywhere; }
@media (max-width: 800px) { .training-session { padding: 18px 17px 26px; } .session-heading h2 { font-size: 22px; } .question-buttons { grid-template-columns: 1fr; } .timing-grid { grid-template-columns: 1fr; gap: 11px; } .timing-grid > div { display: flex; justify-content: space-between; } .checklist { grid-template-columns: repeat(2, 1fr); } .events-list li > div { flex-wrap: wrap; } }
</style>
