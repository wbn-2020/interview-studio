<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, errorText, read } from '../../api/client'
import type { QuestionReference } from '../../api/bank'
import { questionTypeLabels } from '../../api/bank'
import type { TrainingAnswer, TrainingAnswerInput, TrainingAnswerPage, TrainingDiagramInput, TrainingQuestion, TrainingSession } from '../../api/training'
import { notifyTrainingChanged, trainingQuestionStatusLabels } from '../../api/training'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MarkdownEditor from '../MarkdownEditor.vue'
import MarkdownPreview from '../MarkdownPreview.vue'
import QuestionReferenceReadout from '../bank/QuestionReference.vue'
import TrainingDiagram from './TrainingDiagram.vue'
import TrainingFollowUp from './TrainingFollowUp.vue'
import TrainingEvaluation from './TrainingEvaluation.vue'

type EditorState = { dirty: boolean; locked: boolean }
type BoardCandidate = { boardId: string; revision: number; diagram: TrainingDiagramInput }
const props = defineProps<{ session: TrainingSession; questionId: string; commandLocked?: boolean }>()
const emit = defineEmits<{ state: [questionId: string, value: EditorState]; changed: []; requestAssistance: []; selectQuestion: [id: string] }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const route = useRoute()
const router = useRouter()
const answerView = computed(() => route.query.panel === 'follow-up' ? 'follow-up' : route.query.panel === 'evaluation' ? 'evaluation' : 'answer')
function setAnswerView(panel: 'answer' | 'follow-up' | 'evaluation') {
  if (locked.value || props.commandLocked) return
  void router.replace({ query: { ...route.query, panel: panel === 'answer' ? undefined : panel } })
}
const writer = useCommand()
const active = ref(true)
const current = ref<TrainingQuestion>()
const latestServer = ref<TrainingQuestion>()
const content = ref<TrainingAnswerInput>(emptyAnswer())
const baseline = ref<TrainingAnswerInput>(emptyAnswer())
const sourceNodes = ref<TrainingAnswer['content']['diagram']['nodes']>([])
const serverCopy = ref<TrainingQuestion>()
const serverLoading = ref(false)
const serverError = ref('')
const message = ref('')
const reference = ref<QuestionReference>()
const boardCandidate = ref<BoardCandidate>()
const deferredReceipt = shallowRef<() => void>()
const historyOpen = ref(false)
const historyBefore = ref<number>()
const historyCursors = ref<(number | undefined)[]>([])
const historySelection = ref<string>()
const followUpState = ref<EditorState>({ dirty: false, locked: false })
const evaluationState = ref<EditorState>({ dirty: false, locked: false })
let alive = true
let serverRequest = 0
let commandEpoch = store.sessionEpoch
const path = computed(() => `/training/sessions/${props.session.id}/questions/${props.questionId}`)
const dirty = computed(() => JSON.stringify(content.value) !== JSON.stringify(baseline.value))
const reviewState = computed(() => reference.value ? store.changes.get(`standard-review:${reference.value.questionId}:${reference.value.versionId}`) : undefined)
const reviewProtected = computed(() => Boolean(reviewState.value?.dirty || reviewState.value?.locked))
const answerLocked = computed(() => writer.locked.value || Boolean(deferredReceipt.value))
const locked = computed(() => answerLocked.value || followUpState.value.locked || evaluationState.value.locked || Boolean(reviewState.value?.locked))
type AnswerMode = 'diagram' | 'text' | 'versions'
const systemDesign = computed(() => current.value?.snapshot.content.type === 'SYSTEM_DESIGN' && ['RUNNING', 'PAUSED'].includes(props.session.status))
const answerMode = computed<AnswerMode>(() => route.query.answerMode === 'text' || route.query.answerMode === 'versions'
  ? route.query.answerMode : 'diagram')
function setAnswerMode(value: AnswerMode) {
  if (locked.value || props.commandLocked) return
  // 切换只改变可见视图，编辑器保持挂载，不能丢弃尚未保存的文字或图稿。
  void router.replace({ query: { ...route.query, answerMode: value } })
}
const referenceReadable = computed(() => active.value && store.sessionActive && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
// 子审阅自己的写入锁不能反向禁用其恢复入口；父级请求和访问变化仍立即遮挡、禁用。
const referenceBlocked = computed(() => !referenceReadable.value || answerLocked.value || Boolean(props.commandLocked) || followUpState.value.locked || evaluationState.value.locked)
const available = computed(() => active.value && store.sessionActive && !locked.value && !props.commandLocked)
const editable = computed(() => available.value && Boolean(current.value) && ['RUNNING', 'PAUSED'].includes(props.session.status))
const allowMaterials = computed(() => props.session.materialAccess || props.session.mode === 'LEARNING')
const conflict = computed(() => Boolean(serverCopy.value && current.value && serverCopy.value.revision > current.value.revision))
const problem = computed(() => answerProblem(content.value))
const answerLabels: Record<TrainingAnswer['condition'], string> = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '站内辅助确认前', ASSISTED: '已确认辅助条件' }
const detail = useQuery({
  queryKey: computed(() => ['training-question', store.sessionEpoch, props.session.id, props.questionId]),
  enabled: computed(() => active.value && store.sessionActive),
  queryFn: () => read<TrainingQuestion>(path.value),
})
const history = useQuery({
  queryKey: computed(() => ['training-answers', store.sessionEpoch, props.session.id, props.questionId, historyBefore.value]),
  enabled: computed(() => active.value && store.sessionActive && historyOpen.value),
  queryFn: () => read<TrainingAnswerPage>(`${path.value}/answers?limit=20${historyBefore.value === undefined ? '' : `&before=${historyBefore.value}`}`),
})
const historical = useQuery({
  queryKey: computed(() => ['training-answer', store.sessionEpoch, props.session.id, props.questionId, historySelection.value]),
  enabled: computed(() => active.value && store.sessionActive && historyOpen.value && Boolean(historySelection.value)),
  queryFn: () => read<TrainingAnswer>(`${path.value}/answers/${historySelection.value}`),
})
// 本地编辑基线可以因保稿而落后；只读记录必须另用最新服务器修订，收起对照面板也不能回退。
const confirmedQuestion = computed(() => {
  const revision = props.session.questions.find(question => question.id === props.questionId)?.revision
  return store.sessionActive && revision !== undefined && latestServer.value && latestServer.value.revision >= revision ? latestServer.value : undefined
})
const displayedAnswer = computed(() => historyOpen.value && historySelection.value ? historical.data.value : confirmedQuestion.value?.latestAnswer)
const displayedInput = computed(() => displayedAnswer.value ? inputOf(displayedAnswer.value.content) : emptyAnswer())
const endedDraft = computed(() => confirmedQuestion.value?.draft ? inputOf(confirmedQuestion.value.draft.content) : emptyAnswer())
const remoteDraft = computed(() => serverCopy.value?.draft ? inputOf(serverCopy.value.draft.content) : emptyAnswer())
const questionKindLabel = computed(() => current.value?.kind === 'REPLAY' ? '同题复练' : current.value?.kind === 'FOLLOW_UP' ? 'AI 追问' : '主问')
const questionOriginLabel = computed(() => {
  const question = current.value
  if (!question) return ''
  if (question.kind === 'FOLLOW_UP') return '题干固定于本次生成结果'
  const snapshot = question.snapshot
  // 追问或个人回忆题面没有题库版本，不能把内部编号显示成参考标准版本。
  const version = snapshot.sourceQuestionId && snapshot.sourceVersionId && snapshot.number > 0 ? `v${snapshot.number}` : undefined
  if (question.kind === 'REPLAY') return version ? `固定复练题面 · 原题库版本 ${version}` : '固定复练题面 · 无题库参考来源'
  return version ? `题目固定于 ${version}` : '题面固定于本次训练快照'
})

watch([dirty, () => followUpState.value.dirty, () => evaluationState.value.dirty, () => Boolean(reviewState.value?.dirty), locked], ([changed, followUpDirty, evaluationDirty, reviewDirty, waiting]) => emit('state', props.questionId, { dirty: changed || followUpDirty || evaluationDirty || reviewDirty, locked: waiting }), { immediate: true, flush: 'sync' })
watch(detail.data, value => {
  if (!value || !store.sessionActive || !rememberServer(value)) return
  if (!current.value || (!dirty.value && !locked.value && value.revision >= current.value.revision)) accept(value)
  else if (value.revision > current.value.revision) serverCopy.value = value
  if (value.sessionRevision > props.session.revision) emit('changed')
}, { immediate: true })
watch(() => props.session.questions.find(question => question.id === props.questionId)?.revision, revision => {
  if (active.value && store.sessionActive && revision !== undefined && revision > (current.value?.revision ?? -1)) void detail.refetch()
})
watch(() => props.session.revision, () => { if (active.value && store.sessionActive) void detail.refetch() })
watch(() => store.sessionEpoch, () => {
  serverRequest++
  latestServer.value = undefined
  serverCopy.value = undefined
  serverLoading.value = false
  serverError.value = ''
  // 审阅持有独立草稿与原请求，重登时保留子树；正文由当前资料访问条件遮挡。
  if (!reviewProtected.value) reference.value = undefined
  boardCandidate.value = undefined
  historySelection.value = undefined
  historyBefore.value = undefined
  historyCursors.value = []
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false; serverRequest++; serverLoading.value = false })
onUnmounted(() => { alive = false; serverRequest++ })

watch([() => route.query.answer, active, () => store.sessionEpoch], () => {
  if (active.value && route.query.item === props.session.id && route.query.question === props.questionId
      && typeof route.query.answer === 'string') {
    historyOpen.value = true
    historySelection.value = route.query.answer
  }
}, { immediate: true })

function emptyAnswer(): TrainingAnswerInput { return { text: '', diagram: { nodes: [], edges: [], boardSource: null } } }
function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
function inputOf(value: TrainingAnswer['content']): TrainingAnswerInput {
  return { text: value.text, diagram: { nodes: value.diagram.nodes.map(({ reference: link, ...node }) => ({ ...node, link: link ? { kind: link.kind, itemId: link.itemId, versionId: link.versionId } : null })), edges: clone(value.diagram.edges), boardSource: value.diagram.boardSource ? { ...value.diagram.boardSource } : null } }
}
function answerProblem(value: TrainingAnswerInput): string {
  if (value.text.length > 20000) return '回答正文最多 20000 个字符。'
  const { nodes, edges } = value.diagram
  if (nodes.length > 80 || edges.length > 160) return '每题最多保存 80 个图节点和 160 条连线。'
  const ids = [...nodes, ...edges].map(item => item.id)
  if (new Set(ids).size !== ids.length) return '节点和连线的标识不能重复，请重新整理结构图。'
  if (nodes.some(node => !node.label.trim() || node.label.length > 200 || node.description.length > 5000)) return '每个图节点需要名称（最多 200 字符），说明最多 5000 字符。'
  if (nodes.some(node => !Number.isFinite(node.x) || !Number.isFinite(node.y) || Math.abs(node.x) > 1000000 || Math.abs(node.y) > 1000000)) return '图坐标需为有限数值，范围为 −1000000 至 1000000。'
  const nodeIds = new Set(nodes.map(node => node.id))
  if (edges.some(edge => !nodeIds.has(edge.source) || !nodeIds.has(edge.target) || edge.label.length > 200)) return '连线两端必须存在，连线说明最多 200 字符。'
  if (new TextEncoder().encode(JSON.stringify(value)).byteLength > 192 * 1024) return '本题文字与结构图超过 192 KiB，请缩短说明后保存。'
  return ''
}
function rememberServer(value: TrainingQuestion): boolean {
  if (value.id !== props.questionId || value.sessionId !== props.session.id) return false
  const known = latestServer.value
  if (known && (value.revision < known.revision || (value.revision === known.revision && value.sessionRevision < known.sessionRevision))) return false
  latestServer.value = value
  return true
}
function accept(value: TrainingQuestion) {
  if (value.id !== props.questionId || value.sessionId !== props.session.id) return
  rememberServer(value)
  if (current.value && value.revision < current.value.revision) return
  current.value = value
  content.value = value.draft ? inputOf(value.draft.content) : emptyAnswer()
  baseline.value = clone(content.value)
  sourceNodes.value = value.draft?.content.diagram.nodes ?? []
  if (serverCopy.value && serverCopy.value.revision <= value.revision) serverCopy.value = undefined
}
function refresh() {
  void queryClient.invalidateQueries({ queryKey: ['training-answers', store.sessionEpoch, props.session.id, props.questionId] })
  emit('changed')
  notifyTrainingChanged()
}
function validateQuestionReceipt(value: TrainingQuestion) {
  if (value.id !== props.questionId || value.sessionId !== props.session.id) throw new ApiError(502, 'ANSWER_MISMATCH', '答题回执与当前题目不一致，请用原请求重试。')
}
function receive(value: TrainingQuestion, notice: string) {
  accept(value)
  boardCandidate.value = undefined
  message.value = notice
  queryClient.setQueryData(['training-question', store.sessionEpoch, props.session.id, props.questionId], value)
  historySelection.value = undefined
  historyBefore.value = undefined
  historyCursors.value = []
  refresh()
}
function run<T>(target: string, method: string, body: unknown, validate: (value: T) => void, apply: (value: T) => void) {
  if (!available.value) return
  serverRequest++
  serverLoading.value = false
  message.value = ''
  commandEpoch = store.sessionEpoch
  void writer.run<T>(target, method, body, value => {
    if (!alive) return
    // 校验属于原请求的一部分，必须先于延迟接收，失败时由写入器保留原幂等键。
    validate(value)
    // 重登后的旧回执必须显式接收，避免悄悄覆盖仍留在页面中的输入。
    if (!store.sessionActive || commandEpoch !== store.sessionEpoch) deferredReceipt.value = () => apply(value)
    else apply(value)
  })
}
function retry() {
  if (!active.value || !store.sessionActive || writer.busy.value || props.commandLocked || followUpState.value.locked || evaluationState.value.locked) return
  commandEpoch = store.sessionEpoch
  void writer.retry()
}
function receiveDeferred() { if (store.sessionActive && active.value && !followUpState.value.locked && !evaluationState.value.locked && deferredReceipt.value) { const apply = deferredReceipt.value; deferredReceipt.value = undefined; apply() } }
function save(submit: boolean) {
  if (!editable.value || !current.value || problem.value || conflict.value) return
  if (submit && (props.session.status !== 'RUNNING' || !content.value.text.trim() || current.value.status === 'SKIPPED')) return
  if (!submit && !dirty.value) return
  run<TrainingQuestion>(`${path.value}/${submit ? 'submit' : 'draft'}`, submit ? 'POST' : 'PUT', {
    expectedSessionRevision: props.session.revision, expectedRevision: current.value.revision, content: clone(content.value),
  }, validateQuestionReceipt, value => receive(value, submit ? `已确认提交第 ${value.latestAnswer?.number ?? ''} 版，原提交继续保留` : '服务器已确认保存草稿'))
}
function skip() {
  if (!available.value || !current.value || dirty.value || conflict.value || props.session.status !== 'RUNNING') return
  const skipped = current.value.status === 'SKIPPED'
  run<TrainingQuestion>(`${path.value}/${skipped ? 'unskip' : 'skip'}`, 'POST', { expectedSessionRevision: props.session.revision, expectedRevision: current.value.revision }, validateQuestionReceipt, value => receive(value, skipped ? '已恢复此题的作答状态' : '已跳过此题，服务器草稿与既有提交保留'))
}
function discardLocal() {
  if (!available.value || !dirty.value || !window.confirm('恢复到本题最后确认的服务器草稿？未保存的文字和图修改会被丢弃。')) return
  content.value = clone(baseline.value)
  sourceNodes.value = current.value?.draft?.content.diagram.nodes ?? []
  boardCandidate.value = undefined
  message.value = '已撤销本地修改，服务器草稿未改动'
}
function revise(value: TrainingAnswer) {
  if (!editable.value || (dirty.value && !window.confirm('用选定提交替换当前未保存输入？原提交会保留，之后提交产生同题的新修订。'))) return
  content.value = inputOf(value.content)
  sourceNodes.value = value.content.diagram.nodes
  boardCandidate.value = undefined
  message.value = `正在基于第 ${value.number} 版修订；这仍是本次训练的同题修订`
}
function insertOutline() {
  if (!editable.value) return
  const outline = '## 约束与容量假设\n\n## 服务边界\n\n## 数据流\n\n## 异常路径\n\n## 技术取舍\n\n## 验证方式\n'
  content.value.text += `${content.value.text.trim() ? '\n\n' : ''}${outline}`
}
async function compareServer() {
  if (!available.value || serverLoading.value) return
  const epoch = store.sessionEpoch
  const request = ++serverRequest
  serverLoading.value = true
  serverError.value = ''
  emit('changed')
  try {
    const value = await read<TrainingQuestion>(path.value)
    if (alive && active.value && request === serverRequest && store.sessionActive && epoch === store.sessionEpoch && !locked.value && value.revision >= (current.value?.revision ?? -1) && rememberServer(value)) serverCopy.value = value
  } catch (error) { if (alive && request === serverRequest && epoch === store.sessionEpoch) serverError.value = errorText(error) }
  finally { if (alive && request === serverRequest) serverLoading.value = false }
}
function adoptServer(keepLocal: boolean) {
  if (!available.value || !serverCopy.value || !window.confirm(keepLocal ? '保留当前本地输入，并以刚读取的服务器修订作为下一次保存的基础？再次保存会替换该服务器草稿。' : '采用刚读取的服务器草稿，丢弃本地未保存输入？')) return
  const local = clone(content.value)
  const nodes = sourceNodes.value
  accept(serverCopy.value)
  if (keepLocal) { content.value = local; sourceNodes.value = nodes }
  message.value = keepLocal ? '已明确对齐服务器修订；本地输入仍需重新保存或提交' : '已采用服务器草稿'
  writer.error.value = undefined
  emit('changed')
}
function loadReference() {
  if (!current.value || !available.value || props.session.status === 'DRAFT' || !current.value.snapshot.sourceQuestionId) return
  if (!allowMaterials.value && props.session.status !== 'ENDED') { emit('requestAssistance'); return }
  const snapshot = current.value.snapshot
  run<QuestionReference>(`${path.value}/reference`, 'POST', {}, value => {
    if (value.questionId !== snapshot.sourceQuestionId || value.versionId !== snapshot.sourceVersionId) throw new ApiError(502, 'REFERENCE_MISMATCH', '参考回执不属于本题固定版本，请用原请求重试。')
  }, value => {
    reference.value = value
    refresh()
  })
}
function importBoard(boardId: string, revision: number) {
  if (!editable.value || !allowMaterials.value) return
  run<BoardCandidate>(`/training/sessions/${props.session.id}/import-board`, 'POST', { boardId, expectedBoardRevision: revision }, value => {
    if (value.boardId !== boardId || value.revision !== revision) throw new ApiError(502, 'BOARD_MISMATCH', '白板候选与所选修订不一致，请用原请求重试。')
  }, value => {
    boardCandidate.value = value
    message.value = '已读取白板候选；明确采用后才进入当前答案'
    refresh()
  })
}
function adoptBoard() {
  if (!editable.value || !allowMaterials.value || !boardCandidate.value) return
  if (content.value.diagram.nodes.length && !window.confirm('用这份白板候选替换当前答案结构图？文字回答保留，采用后仍需保存。')) return
  content.value.diagram = clone(boardCandidate.value.diagram)
  sourceNodes.value = []
  boardCandidate.value = undefined
  message.value = '已将候选图放入本地答案，尚未保存到服务器'
}
function nextHistory() { if (history.data.value?.nextCursor !== null && history.data.value?.nextCursor !== undefined) { historyCursors.value.push(historyBefore.value); historyBefore.value = history.data.value.nextCursor } }
function download(kind: 'text' | 'diagram', value: TrainingAnswerInput | TrainingAnswer['content'], name: string) {
  const data = kind === 'text' ? value.text : JSON.stringify(value.diagram, null, 2)
  const url = URL.createObjectURL(new Blob([data], { type: kind === 'text' ? 'text/markdown;charset=utf-8' : 'application/json;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `wenjie-${props.questionId.slice(0, 8)}-${name}.${kind === 'text' ? 'md' : 'json'}`
  anchor.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <section class="training-answer" :class="{ 'follow-up-active': answerView === 'follow-up' }" aria-label="本题作答">
    <p v-if="!current && detail.isPending.value" class="loading-block" role="status">正在读取题目和服务器草稿…</p>
    <p v-if="detail.error.value" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text :disabled="!available" @click="detail.refetch()">重试读取</NButton></p>
    <template v-if="current">
      <nav class="answer-view-tabs" aria-label="作答与反馈"><button :class="{ active: answerView === 'answer' }" @click="setAnswerView('answer')">独立作答</button><button v-if="session.status !== 'DRAFT'" :class="{ active: answerView === 'follow-up' }" @click="setAnswerView('follow-up')">AI 追问</button><button v-if="session.status !== 'DRAFT'" :class="{ active: answerView === 'evaluation' }" @click="setAnswerView('evaluation')">评价记录</button></nav>
      <nav v-if="systemDesign && answerView === 'answer'" class="system-answer-tabs" aria-label="系统设计回答视图"><button v-for="item in ([['diagram', '架构图'], ['text', '说明'], ['versions', '版本']] as const)" :key="item[0]" :class="{ active: answerMode === item[0] }" :aria-pressed="answerMode === item[0]" :disabled="locked || commandLocked" @click="setAnswerMode(item[0])">{{ item[1] }}</button></nav>
      <header class="answer-heading"><div><p class="section-label">{{ current.snapshot.stage }} · {{ questionKindLabel }} · {{ questionTypeLabels[current.snapshot.content.type] }}</p><h3>{{ current.snapshot.title }}</h3><p class="field-help">{{ questionOriginLabel }} · {{ session.status === 'ENDED' ? (confirmedQuestion ? trainingQuestionStatusLabels[confirmedQuestion.status] : '正在核对最终状态') : trainingQuestionStatusLabels[current.status] }}</p><p v-if="current.kind === 'FOLLOW_UP' && current.followUp" class="field-help">追问自 {{ session.questions.find(question => question.id === current?.followUp?.parentQuestionId)?.title || '父题' }} 的确切提交。<NButton text size="small" :disabled="!available" @click="emit('selectQuestion', current.followUp.parentQuestionId)">返回父题</NButton><span class="answer-origin">父答案标识：{{ current.followUp.parentAnswerId }}</span></p></div><NButton size="small" :disabled="!available || serverLoading" @click="compareServer">读取服务器副本</NButton></header>
      <p v-if="current.kind === 'REPLAY'" class="field-help">这是沿用已保存题面的新尝试。<template v-if="current.snapshot.sourceQuestionId">题面可能对应已改版或停用的题库版本。</template>题面来源与接触情况见来源说明；本次从空白回答开始，之后保存的草稿和提交属于当前训练。</p>
      <div v-if="current.snapshot.warnings.length" class="source-warnings"><p v-for="warning in current.snapshot.warnings" :key="warning">{{ warning }}</p></div>
      <article class="question-prompt"><MarkdownPreview :value="current.snapshot.content.prompt" /><template v-for="field in ([['conditions', '适用条件'], ['technology', '技术与版本'], ['scenario', '场景']] as const)" :key="field[0]"><div v-if="current.snapshot.content[field[0]]"><h4>{{ field[1] }}</h4><p class="preserve">{{ current.snapshot.content[field[0]] }}</p></div></template></article>
      <p class="field-help">开始前接触记录：创作 {{ current.priorExposure.authoredCount }} 次、打开参考 {{ current.priorExposure.referenceViewCount }} 次、打开评价区 {{ current.priorExposure.evaluationAccessCount ?? 0 }} 次{{ current.priorExposure.lastExposureAt ? `，最近记录于 ${date(current.priorExposure.lastExposureAt)}` : '' }}。这只是站内记录。</p>
      <div v-if="session.status !== 'ENDED'" class="answer-save-state"><span :class="{ unsaved: dirty }">{{ dirty ? '有未保存的本地输入' : current.draft ? `草稿已保存于 ${date(current.draft.savedAt)}` : '没有未提交的服务器草稿' }}</span><span>本地编辑基线 · 题目修订 {{ current.revision }}</span></div>
      <div v-else class="answer-save-state"><span>{{ confirmedQuestion ? (confirmedQuestion.draft ? `结束时保留的草稿保存于 ${date(confirmedQuestion.draft.savedAt)}` : '结束时没有未提交的服务器草稿') : '正在核对结束时的服务器记录…' }}</span><span v-if="confirmedQuestion">题目修订 {{ confirmedQuestion.revision }}</span></div>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!store.sessionActive || !!commandLocked" @click="retry">用原请求重试</NButton><NButton v-else text :disabled="!available" @click="compareServer">读取服务器副本</NButton></p>
      <p v-if="deferredReceipt" class="notice" role="status">已有服务器确认的结果在会话变化后到达。<NButton text :disabled="!store.sessionActive" @click="receiveDeferred">接收已确认结果</NButton></p>
      <p v-if="serverLoading" class="field-help" role="status">正在读取服务器副本，本地输入继续保留…</p><p v-if="serverError" class="notice error" role="alert">{{ serverError }}</p>
      <section v-if="serverCopy" class="answer-panel server-copy"><h4>服务器副本 · 题目修订 {{ serverCopy.revision }}</h4><p class="field-help">{{ trainingQuestionStatusLabels[serverCopy.status] }} · {{ serverCopy.draft ? `草稿保存于 ${date(serverCopy.draft.savedAt)}` : '没有未提交草稿' }}{{ serverCopy.latestAnswer ? ` · 最近提交第 ${serverCopy.latestAnswer.number} 版` : '' }}</p><MarkdownPreview v-if="serverCopy.draft?.content.text" :value="serverCopy.draft.content.text" /><TrainingDiagram v-if="remoteDraft.diagram.nodes.length" :model-value="remoteDraft.diagram" disabled :allow-materials="false" :references="serverCopy.draft?.content.diagram.nodes" /><p class="field-help">采用副本会替换本地输入。保留本地输入时，需明确对齐此修订后再次保存。</p><div class="row-actions"><NButton size="small" :disabled="!available" @click="adoptServer(false)">采用服务器草稿</NButton><NButton v-if="session.status !== 'ENDED' && dirty" size="small" :disabled="!available" @click="adoptServer(true)">保留本地并对齐修订</NButton><NButton size="small" :disabled="!available" @click="serverCopy = undefined">收起副本</NButton></div></section>

      <section v-if="session.status === 'RUNNING' || session.status === 'PAUSED'" v-show="answerView === 'answer' && (!systemDesign || answerMode !== 'versions')" class="answer-panel">
        <header class="panel-heading"><h4>本题回答</h4><span class="field-help">{{ content.text.length }} / 20000 字符</span></header>
        <p v-if="session.status === 'PAUSED'" class="notice">训练已暂停，可以继续保存草稿；恢复训练后再提交或跳题。</p>
        <div v-show="!systemDesign || answerMode === 'text'" class="answer-text"><MarkdownEditor v-model="content.text" :disabled="!editable" /><div v-if="systemDesign" class="row-actions"><NButton size="small" :disabled="!editable" @click="insertOutline">追加系统设计提纲</NButton><span class="field-help">提供空提纲，具体内容由你填写。</span></div></div>
        <section v-if="systemDesign" v-show="answerMode === 'diagram'" class="answer-architecture" aria-label="架构图"><TrainingDiagram v-model="content.diagram" :disabled="!editable" :allow-materials="allowMaterials" :references="sourceNodes" @import-board="importBoard" /></section>
        <details v-else class="answer-diagram"><summary>架构图 <span class="field-help">{{ content.diagram.nodes.length }} 个节点 · {{ content.diagram.edges.length }} 条连线</span></summary><TrainingDiagram v-model="content.diagram" :disabled="!editable" :allow-materials="allowMaterials" :references="sourceNodes" @import-board="importBoard" /></details>
        <section v-if="boardCandidate" class="board-candidate"><h4>待采用的白板候选</h4><p>{{ boardCandidate.diagram.nodes.length }} 个节点、{{ boardCandidate.diagram.edges.length }} 条连线 · 修订 {{ boardCandidate.revision }}</p><TrainingDiagram :model-value="boardCandidate.diagram" disabled :allow-materials="false" /><div class="row-actions"><NButton size="small" :disabled="!editable || !allowMaterials" @click="adoptBoard">采用候选图</NButton><NButton size="small" :disabled="!available" @click="boardCandidate = undefined">放弃候选</NButton></div></section>
        <p v-if="problem" class="field-help unsaved" role="status">{{ problem }}</p><p v-if="conflict" class="notice">服务器已有更新，请先核对上方服务器副本。</p>
        <div class="row-actions"><NButton :loading="writer.busy.value" :disabled="!editable || !dirty || !!problem || conflict" @click="save(false)">保存草稿</NButton><NButton type="primary" :disabled="!editable || session.status !== 'RUNNING' || !content.text.trim() || !!problem || conflict || current.status === 'SKIPPED'" @click="save(true)">{{ current.latestAnswer ? '提交同题新修订' : '提交回答' }}</NButton><NButton v-if="dirty" :disabled="!available" @click="discardLocal">撤销本地修改</NButton><NButton quaternary :disabled="!available || dirty || conflict || session.status !== 'RUNNING'" @click="skip">{{ current.status === 'SKIPPED' ? '恢复此题作答' : '跳过此题' }}</NButton></div>
        <template v-if="dirty"><div class="row-actions"><NButton size="small" @click="download('text', content, 'local-unsaved')">另存本地文字</NButton><NButton size="small" @click="download('diagram', content, 'local-unsaved')">另存本地图</NButton></div><p class="field-help">另存不会写入服务器，也不会解除未保存提示。若记录容量已满，请先确认文字和图均已另存，再明确撤销本地修改后结束训练。</p></template>
        <p class="field-help">提交会保存这次明确发送的文字与图，并清空本题服务器草稿。切换题目保留页面内输入；刷新只能恢复已确认的服务器草稿。</p><p v-if="current.status === 'SKIPPED'" class="field-help">本题已跳过，可保存草稿；先恢复作答状态再提交。</p>
      </section>
      <section v-else-if="session.status === 'ENDED' && confirmedQuestion?.draft" class="answer-panel"><h4>结束时保留的未提交草稿</h4><MarkdownPreview :value="confirmedQuestion.draft.content.text" /><TrainingDiagram v-if="endedDraft.diagram.nodes.length" :model-value="endedDraft.diagram" disabled :allow-materials="false" :references="confirmedQuestion.draft.content.diagram.nodes" /><div class="row-actions"><NButton size="small" @click="download('text', confirmedQuestion.draft.content, 'draft')">另存草稿文字</NButton><NButton size="small" @click="download('diagram', confirmedQuestion.draft.content, 'draft')">另存草稿结构图</NButton></div></section>
      <section v-if="session.status === 'ENDED' && dirty" class="answer-panel"><h4>页面内尚未保存的副本</h4><p class="field-help">训练已经结束，这些输入未被提交或写入服务器。请先另存，刷新会丢失。</p><MarkdownPreview :value="content.text" /><TrainingDiagram v-if="content.diagram.nodes.length" :model-value="content.diagram" disabled :allow-materials="false" :references="sourceNodes" /><div class="row-actions"><NButton size="small" @click="download('text', content, 'local-unsaved')">另存本地文字</NButton><NButton size="small" @click="download('diagram', content, 'local-unsaved')">另存本地图</NButton><NButton size="small" :disabled="!available" @click="discardLocal">已另存，撤销本地副本</NButton></div></section>

      <section v-if="session.status !== 'DRAFT'" v-show="!systemDesign || answerView !== 'answer' || answerMode === 'versions'" class="answer-panel submission-panel">
        <header class="panel-heading"><h4>提交记录</h4><NButton size="small" :disabled="!available" @click="historyOpen = !historyOpen">{{ historyOpen ? '收起历史列表' : '查看全部修订' }}</NButton></header>
        <template v-if="historyOpen">
          <p v-if="history.isPending.value" class="field-help" role="status">正在读取修订历史…</p>
          <p v-if="history.error.value" class="notice error" role="alert">{{ errorText(history.error.value) }}<NButton text @click="history.refetch()">重试</NButton></p>
          <div class="history-list"><button v-for="answer in history.data.value?.items" :key="answer.id" :disabled="!available" :class="{ selected: historySelection === answer.id }" @click="historySelection = answer.id"><strong>第 {{ answer.number }} 版</strong><span>{{ answerLabels[answer.condition] }} · {{ date(answer.createdAt) }}</span></button></div>
          <div v-if="historyCursors.length || history.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!historyCursors.length" @click="historyBefore = historyCursors.pop()">上一页</NButton><NButton size="small" :disabled="history.data.value?.nextCursor == null" @click="nextHistory">下一页</NButton></div>
          <p v-if="historySelection && historical.isPending.value" class="field-help" role="status">正在读取这次提交…</p>
          <p v-if="historical.error.value && historySelection" class="notice error" role="alert">{{ errorText(historical.error.value) }}<NButton text @click="historical.refetch()">重试</NButton></p>
        </template>
        <article v-if="displayedAnswer" class="saved-answer">
          <h4>第 {{ displayedAnswer.number }} 版 · {{ answerLabels[displayedAnswer.condition] }}</h4>
          <p class="field-help">{{ date(displayedAnswer.createdAt) }} · 提交时辅助事件序号 {{ displayedAnswer.assistanceEventSequence }}</p>
          <MarkdownPreview :value="displayedAnswer.content.text" />
          <TrainingDiagram v-if="displayedInput.diagram.nodes.length" :model-value="displayedInput.diagram" disabled :allow-materials="false" :references="displayedAnswer.content.diagram.nodes" />
          <div class="row-actions"><NButton v-if="session.status !== 'ENDED'" size="small" :disabled="!editable" @click="revise(displayedAnswer)">以此提交为基础修订</NButton><NButton size="small" @click="download('text', displayedAnswer.content, `answer-${displayedAnswer.number}`)">另存文字</NButton><NButton size="small" @click="download('diagram', displayedAnswer.content, `answer-${displayedAnswer.number}`)">另存结构图</NButton></div>
          <RouterLink class="text-link" :to="{ name: 'review', query: { sessionId: session.id, questionId, answerId: displayedAnswer.id } }">复盘这次提交：自检、外部意见与改进动作</RouterLink>
          <RouterLink v-if="session.status === 'ENDED'" class="text-link" :to="{ name: 'repractice', query: { sessionId: session.id, questionId, answerId: displayedAnswer.id } }">以这次提交为基线，创建空白独立复练</RouterLink>
          <p class="field-help">提交内容及当时条件保留。同题修订不计作新的独立复练；反馈与复盘均绑定这次确切提交。</p>
        </article>
        <p v-else-if="!(historyOpen && historySelection) && !confirmedQuestion" class="field-help" role="status">尚未核对最新服务器提交，请等待读取或重试；本地输入继续保留。</p>
        <p v-else-if="!(historyOpen && historySelection)" class="field-help">{{ session.status === 'ENDED' ? '本题没有正式提交。结束时保留的草稿不构成新答案，也不能用于建立新旧对照。' : '还没有提交记录。' }}</p>
      </section>
      <div v-show="answerView === 'follow-up'" class="answer-follow-up"><TrainingFollowUp v-if="session.status !== 'DRAFT'" :session="session" :question-id="questionId" :answer="displayedAnswer" :disabled="!active || answerLocked || commandLocked || evaluationState.locked || reviewState?.locked" @state="followUpState = $event" @changed="refresh" @select-question="emit('selectQuestion', $event)" /></div>
      <div v-show="answerView === 'evaluation'" class="answer-evaluation"><TrainingEvaluation v-if="session.status !== 'DRAFT'" :session="session" :question-id="questionId" :answer="displayedAnswer" :disabled="!active || answerLocked || commandLocked || followUpState.locked || reviewState?.locked" @state="evaluationState = $event" @changed="refresh" /></div>
      <p v-if="!current.snapshot.sourceQuestionId" class="field-help">这道题没有题库参考答案，比较时不借用其他题目的标准。</p>
      <section v-else-if="session.status !== 'DRAFT'" class="reference-panel"><h4>本题固定版本的参考</h4><QuestionReferenceReadout v-if="reference" v-show="referenceReadable" :reference="reference" :disabled="referenceBlocked" /><p v-if="reference && !referenceReadable" class="field-help" role="status">资料访问暂不可用，参考正文已遮挡，审阅输入和原请求继续保留。</p><template v-if="!reference"><p class="field-help">{{ session.status === 'ENDED' || allowMaterials ? '显式打开会记录一次参考查看。' : '查看参考前需明确确认辅助条件，此后的提交会保留该条件。' }}文字模拟不会自动展开参考。</p><NButton :disabled="!available" @click="loadReference">{{ session.status !== 'ENDED' && !allowMaterials ? '先确认辅助条件' : '打开参考并记录查看' }}</NButton></template></section>
    </template>
  </section>
</template>

<style scoped>
.training-answer { display: grid; gap: 19px; min-width: 0; }
.answer-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; }
.answer-heading h3 { margin: 6px 0; font-size: 21px; overflow-wrap: anywhere; }
.answer-heading > div { min-width: 0; } h4 { margin: 0; font-size: 14px; }
.question-prompt, .saved-answer { display: grid; gap: 16px; min-width: 0; }
.question-prompt h4 { margin-bottom: 7px; } .preserve { white-space: pre-wrap; overflow-wrap: anywhere; }
.answer-save-state { display: flex; flex-wrap: wrap; gap: 8px 15px; color: var(--muted); font-size: 12px; }
.unsaved, .source-warnings { color: var(--warm); }
.source-warnings { display: grid; gap: 5px; font-size: 12px; }
.answer-panel, .reference-panel { display: grid; gap: 16px; padding-top: 20px; border-top: 1px solid var(--line); min-width: 0; }
.server-copy, .board-candidate { padding: 17px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
.board-candidate { display: grid; gap: 13px; }
.reference-panel { justify-items: start; } .reference-panel > * { max-width: 100%; }
.history-list { display: grid; gap: 4px; }
.history-list > button { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; padding: 10px; text-align: left; border-radius: 5px; }
.history-list > button:hover, .history-list > button.selected { background: var(--surface-soft); }
.history-list strong { font-size: 13px; } .history-list span { font-size: 12px; color: var(--muted); }
.answer-origin { display: block; overflow-wrap: anywhere; font-size: 11px; }
@media (max-width: 700px) { .answer-heading { flex-wrap: wrap; } .answer-heading h3 { font-size: 19px; } }
</style>

<style scoped>
.answer-view-tabs { display: flex; gap: 20px; border-bottom: 1px solid var(--line); padding-bottom: 12px; }.answer-view-tabs button { padding: 8px 12px; color: var(--muted); font-size: 18px; border-bottom: 2px solid transparent; }.answer-view-tabs .active { color: var(--ink); border-color: var(--brand); }
.system-answer-tabs { display: flex; gap: 28px; border-bottom: 1px solid var(--line); }.system-answer-tabs button { padding: 14px 20px; border-bottom: 2px solid transparent; color: var(--muted); font-size: 21px; }.system-answer-tabs button.active { border-color: var(--brand); color: var(--ink); }.answer-text { display: grid; gap: 16px; min-width: 0; }.answer-text > .row-actions { justify-content: end; }.answer-architecture { min-width: 0; }
.follow-up-active { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 20px; }.follow-up-active > * { grid-column: 1/-1; }.follow-up-active .submission-panel { grid-column: 1; align-self: start; padding: 22px; border: 1px solid var(--line); border-radius: 18px; background: var(--surface); }.follow-up-active .answer-follow-up { grid-column: 2; align-self: start; }.answer-follow-up { min-width: 0; padding: 22px; border: 1px solid var(--line); border-radius: 18px; background: var(--surface); }.answer-follow-up :deep(.follow-up) { padding: 0; border: 0; }.answer-follow-up :deep(h4) { font-size: 22px; }.answer-follow-up :deep(.follow-up-form) { border-radius: 14px; }.answer-follow-up :deep(.attempt-card) { padding: 18px; border-radius: 13px; }.answer-evaluation { min-width: 0; }
@media(max-width:1200px) { .follow-up-active { grid-template-columns: minmax(0,1fr); }.follow-up-active .submission-panel, .follow-up-active .answer-follow-up { grid-column: 1; } }
</style>

<style scoped>
.training-answer { min-width: 0; border: 1px solid var(--line); border-radius: 22px; padding: 28px 27px; background: linear-gradient(115deg,#ffffff03,transparent),var(--surface); }.answer-heading h3 { font-size: 29px; line-height: 1.55; }.question-prompt { border: 0; padding: 10px 0 20px; font-size: 21px; line-height: 1.9; }.question-prompt :deep(.markdown-preview) { font-size: 21px; }.answer-panel { padding-top: 20px; }.panel-heading h4 { font-size: 22px; }.training-answer :deep(.markdown-editor) { border-radius: 16px; overflow: hidden; }.training-answer :deep(.cm-editor) { min-height: 420px; font-size: 20px; line-height: 1.9; background: #ffffff03; }.training-answer :deep(.cm-content) { padding: 18px 20px; min-height: 420px; }.training-answer :deep(.cm-scroller) { min-height: 420px; }.answer-save-state { font-size: 15px; }.answer-diagram { margin-block: 18px; }.answer-diagram > summary { display: flex; align-items: center; gap: 15px; padding: 12px 0; cursor: pointer; font-size: 21px; color: var(--brand-strong); }.answer-panel > .row-actions { justify-content: end; margin-top: 15px; }.saved-answer { font-size: 20px; line-height: 1.9; }.training-answer :deep(.saved-answer .markdown-preview) { font-size: 20px; line-height: 1.9; }.reference-panel h4 { font-size: 21px; }
@media(max-width:600px) { .training-answer { padding: 20px 16px; }.answer-heading { flex-wrap: wrap; }.answer-heading h3 { font-size: 24px; }.question-prompt,.question-prompt :deep(.markdown-preview) { font-size: 18px; } }
</style>
