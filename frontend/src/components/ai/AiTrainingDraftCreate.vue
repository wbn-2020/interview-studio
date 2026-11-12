<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { AiCreateTrainingDraft, AiEvaluationAccess, AiEvaluationPage, AiPreview, AiSettings, AiTask, AiTrainingDraftFragment, AiTrainingDraftInput, AiTrainingDraftPreviewRequest } from '../../api/ai'
import { aiDate, aiPreparationPlanTextValid, aiTrainingDraftFieldLabels, aiTrainingDraftInputFingerprint, aiTrainingDraftManifestMatches, aiTrainingDraftRangeMatches, formatAiAmount } from '../../api/ai'
import type { TrainingAnswer, TrainingQuestion } from '../../api/training'
import { questionDimensionLabels, questionTypeLabels } from '../../api/bank'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

type SourceIdentity = Pick<AiTrainingDraftPreviewRequest, 'sessionId' | 'questionId' | 'answerId'> & { evaluationTaskId?: string }
type SavedSource = { question: TrainingQuestion['snapshot']; answer: TrainingAnswer }
type AnswerField = { key: string; field: AiTrainingDraftFragment['field']; elementId: string | null; label: string; text: string }
const props = defineProps<{ settings?: AiSettings; disabled?: boolean; initialSource?: SourceIdentity }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const source = shallowRef<SavedSource>()
const sourceEpoch = ref(-1)
const sourceLoading = ref(false)
const sourceError = ref('')
const instruction = ref('')
const fragments = ref<AiTrainingDraftFragment[]>([])
const fieldKey = ref('')
const rangeStart = ref('')
const rangeEnd = ref('')
const selectionError = ref('')
const feedbackOpen = ref(Boolean(props.initialSource?.evaluationTaskId))
const reportId = ref(props.initialSource?.evaluationTaskId ?? '')
const feedbackIds = ref<string[]>([])
const evaluationCursor = ref<string>()
const evaluationCursors = ref<(string | undefined)[]>([])
const previewing = ref(false)
const previewError = ref('')
const prepared = shallowRef<AiPreview>()
const preparedRequest = ref('')
const consent = ref(false)
const authorization = ref<AiAuthorizationReference>()
const deferred = shallowRef<AiTask>()
const createdResult = shallowRef<AiTask>()
const message = ref('')
const readable = computed(() => store.sessionActive && !props.disabled)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || Boolean(createdResult.value))
const sourceKey = computed(() => JSON.stringify(props.initialSource ?? null))
const validIdentity = computed(() => {
  const selected = props.initialSource
  const uuid = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
  return Boolean(selected && [selected.sessionId, selected.questionId, selected.answerId].every(uuid)
    && (!selected.evaluationTaskId || uuid(selected.evaluationTaskId)))
})
const sourceReady = computed(() => readable.value && validIdentity.value && Boolean(source.value)
  && sourceEpoch.value === store.sessionEpoch && !sourceLoading.value && !sourceError.value)
const path = computed(() => props.initialSource
  ? `/training/sessions/${encodeURIComponent(props.initialSource.sessionId)}/questions/${encodeURIComponent(props.initialSource.questionId)}` : '')
const sourceRoute = computed(() => props.initialSource ? { name: 'training', query: { item: props.initialSource.sessionId, question: props.initialSource.questionId, answer: props.initialSource.answerId } } : { name: 'training' })
const fields = computed<AnswerField[]>(() => {
  if (!source.value) return []
  const content = source.value.answer.content
  const field = (kind: AiTrainingDraftFragment['field'], elementId: string | null, label: string, text: string): AnswerField => ({ key: JSON.stringify([kind, elementId]), field: kind, elementId, label, text })
  return [field('ANSWER_TEXT', null, '回答文字', content.text),
    ...content.diagram.nodes.flatMap((node, index) => [field('ANSWER_NODE_LABEL', node.id, `节点 ${index + 1} · 名称`, node.label), field('ANSWER_NODE_DESCRIPTION', node.id, `节点 ${index + 1} · 说明`, node.description)]),
    ...content.diagram.edges.map((edge, index) => field('ANSWER_EDGE_LABEL', edge.id, `连线 ${index + 1} · 说明`, edge.label))]
})
const selectedField = computed(() => fields.value.find(field => field.key === fieldKey.value))
const range = computed<AiTrainingDraftFragment | undefined>(() => {
  const field = selectedField.value
  if (!field || !/^\d+$/.test(rangeStart.value) || !/^\d+$/.test(rangeEnd.value)) return undefined
  const start = Number(rangeStart.value), end = Number(rangeEnd.value)
  return { field: field.field, elementId: field.elementId, start, end, quote: field.text.slice(start, end) }
})
const rangeProblem = computed(() => {
  const value = range.value, field = selectedField.value
  if (!value || !field) return '请在原文中选择一段文字，或填写起止位置。'
  if (!aiTrainingDraftRangeMatches(field.text, value.start, value.end, value.quote)
    || !aiPreparationPlanTextValid(value.quote, 8000) || !value.quote.trim()) return '请选择非空白且不超过 8000 个 UTF-16 码元的完整片段，边界不能拆开字符。'
  if (fragments.value.some(item => item.field === value.field && item.elementId === value.elementId && item.start < value.end && value.start < item.end)) return '同一原文字段中的已选区间不能重叠，请调整选择范围。'
  return ''
})
const accessQuery = useQuery({
  queryKey: computed(() => ['training-evaluation-access', store.sessionEpoch, props.initialSource?.sessionId, props.initialSource?.questionId, props.initialSource?.answerId]),
  enabled: computed(() => sourceReady.value && feedbackOpen.value),
  staleTime: 0,
  queryFn: () => readCurrent<AiEvaluationAccess>(`${path.value}/evaluations/access?answerId=${encodeURIComponent(props.initialSource!.answerId)}`),
})
const feedbackAllowed = computed(() => sourceReady.value && !accessQuery.error.value && accessQuery.data.value?.allowed === true
  && accessQuery.data.value.sessionId === props.initialSource?.sessionId && accessQuery.data.value.questionId === props.initialSource?.questionId
  && accessQuery.data.value.answerId === props.initialSource?.answerId)
const evaluations = useQuery({
  queryKey: computed(() => ['training-evaluations', store.sessionEpoch, props.initialSource?.sessionId, props.initialSource?.questionId, evaluationCursor.value]),
  enabled: computed(() => feedbackOpen.value && feedbackAllowed.value),
  queryFn: () => readCurrent<AiEvaluationPage>(`/training/sessions/${encodeURIComponent(props.initialSource!.sessionId)}/evaluations?questionId=${encodeURIComponent(props.initialSource!.questionId)}&limit=20${evaluationCursor.value ? `&cursor=${encodeURIComponent(evaluationCursor.value)}` : ''}`),
})
const report = useQuery({
  queryKey: computed(() => ['ai', store.sessionEpoch, 'training-draft-report', sourceKey.value, reportId.value]),
  enabled: computed(() => feedbackOpen.value && feedbackAllowed.value && Boolean(reportId.value)),
  queryFn: async () => {
    const id = reportId.value
    const value = await readCurrent<AiTask>(`/ai/tasks/${encodeURIComponent(id)}`)
    if (id !== reportId.value || !feedbackAllowed.value || value.task.id !== id || value.task.purpose !== 'ANSWER_EVALUATION'
      || value.task.status !== 'SUCCEEDED' || !value.evaluationReport || value.evaluationReport.level !== 'AI_UNREVIEWED'
      || !value.evaluation || value.evaluation.sessionId !== props.initialSource?.sessionId || value.evaluation.questionId !== props.initialSource?.questionId
      || value.evaluation.answerId !== props.initialSource?.answerId || value.evaluation.answerNumber !== source.value?.answer.number) {
      throw new ApiError(409, 'AI_EVALUATION_SOURCE_MISMATCH', '评价任务与本次确切提交不一致或尚未成功，请重新选择。')
    }
    return { id, report: value.evaluationReport }
  },
})
const selectedReport = computed(() => {
  const value = report.data.value
  return feedbackAllowed.value && !report.error.value && value?.id === reportId.value ? value.report : undefined
})
const availableEvaluations = computed(() => evaluations.data.value?.items.filter(item => item.answerId === props.initialSource?.answerId && item.task.purpose === 'ANSWER_EVALUATION' && item.task.status === 'SUCCEEDED') ?? [])
const request = computed<AiTrainingDraftPreviewRequest>(() => ({
  sessionId: props.initialSource?.sessionId ?? '', questionId: props.initialSource?.questionId ?? '', answerId: props.initialSource?.answerId ?? '',
  instruction: instruction.value, fragments: fragments.value.map(item => ({ ...item })),
  feedback: feedbackIds.value.length ? { taskId: reportId.value, feedbackIds: [...feedbackIds.value] } : null,
}))
const requestSignature = computed(() => JSON.stringify(request.value))
const expectedInput = computed<AiTrainingDraftInput | undefined>(() => {
  if (!sourceReady.value || !source.value || !props.initialSource || (feedbackIds.value.length && !selectedReport.value)) return undefined
  const selected = feedbackIds.value.map(id => selectedReport.value?.observations.find(item => item.id === id))
  if (selected.some(item => !item)) return undefined
  const snapshot = source.value.question, answer = source.value.answer, question = snapshot.content
  return { sessionId: props.initialSource.sessionId, questionId: props.initialSource.questionId, answerId: answer.id, answerNumber: answer.number,
    answerCondition: answer.condition, sourceQuestionId: snapshot.sourceQuestionId, sourceVersionId: snapshot.sourceVersionId,
    question: { title: question.title, prompt: question.prompt, conditions: question.conditions, technology: question.technology, scenario: question.scenario, type: question.type },
    fragments: request.value.fragments, feedbackTaskId: request.value.feedback?.taskId ?? null,
    feedbackLevel: feedbackIds.value.length ? selectedReport.value!.level : null,
    feedbackLimitations: feedbackIds.value.length ? selectedReport.value!.limitations : null,
    feedback: selected.filter((item): item is NonNullable<typeof item> => Boolean(item)) }
})
const problem = computed(() => {
  if (!sourceReady.value) return '请先读取入口所指的确切提交。'
  if (!fragments.value.length || fragments.value.length > 8) return '请明确选择 1–8 个回答片段。'
  if (fragments.value.some(item => { const field = fields.value.find(field => field.field === item.field && field.elementId === item.elementId); return !field || !aiTrainingDraftRangeMatches(field.text, item.start, item.end, item.quote) })) return '已选片段与原文不一致，请重新读取并核对范围。'
  if (!aiPreparationPlanTextValid(instruction.value, 1000)) return '整理要求最多 1000 个 UTF-16 码元，不能含 NUL 或不完整的 Unicode 字符。'
  if (feedbackIds.value.length > 5 || feedbackIds.value.length && (!feedbackAllowed.value || !selectedReport.value || !expectedInput.value)) return '请核对评价访问与当前报告，最多明确选择 5 条反馈。'
  return ''
})
const configured = computed(() => Boolean(props.settings?.configuration.enabled && props.settings.configuration.configured))
const dirty = computed(() => !createdResult.value && Boolean(fragments.value.length || instruction.value || feedbackIds.value.length || rangeStart.value || rangeEnd.value))
const previewMatches = computed(() => Boolean(prepared.value && preparedRequest.value === requestSignature.value && expectedInput.value
  && prepared.value.manifest.trainingDraft && aiTrainingDraftInputFingerprint(prepared.value.manifest.trainingDraft) === aiTrainingDraftInputFingerprint(expectedInput.value)))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const coverageLabels = { MENTIONED: '原文有提及', NOT_MENTIONED: '可能未提及', UNCERTAIN: '暂无法判断' }
const answerLabels = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let sourceSequence = 0
let previewSequence = 0
let accessGeneration = 0
let commandEpoch = store.sessionEpoch
let commandGeneration = 0

watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed
  commandState.locked = waiting
  store.changes.set('ai-create', changeState)
  store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([requestSignature, fieldKey, rangeStart, rangeEnd], invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch(() => selectedReport.value ? JSON.stringify(selectedReport.value) : '', invalidatePreview, { flush: 'sync' })
watch(fields, value => { if (!value.some(field => field.key === fieldKey.value)) fieldKey.value = value[0]?.key ?? '' })
watch(fieldKey, () => { rangeStart.value = ''; rangeEnd.value = ''; selectionError.value = '' }, { flush: 'sync' })
watch([sourceKey, () => store.sessionEpoch, () => store.sessionActive, () => props.disabled], () => {
  accessGeneration++
  sourceSequence++
  sourceLoading.value = false
  sourceEpoch.value = -1
  invalidatePreview()
  if (readable.value && validIdentity.value) void loadSource()
}, { immediate: true, flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['AI_CONFIG_CHANGED', 'AI_MANIFEST_CHANGED', 'AI_SOURCE_CHANGED', 'AI_EVALUATION_ACCESS_REQUIRED', 'AI_DISABLED', 'AI_NOT_CONFIGURED', 'AI_BUDGET_EXCEEDED'].includes(error.code)) {
    invalidatePreview()
    emit('refresh')
  }
})
onUnmounted(() => {
  alive = false
  sourceSequence++
  previewSequence++
  if (store.changes.get('ai-create') === changeState) store.changes.delete('ai-create')
  if (store.changes.get('ai-command') === commandState) store.changes.delete('ai-command')
})

async function readCurrent<T>(target: string): Promise<T> {
  const epoch = store.sessionEpoch, generation = accessGeneration, identity = sourceKey.value
  const value = await read<T>(target)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || generation !== accessGeneration || identity !== sourceKey.value) {
    throw new ApiError(0, 'TRAINING_DRAFT_CONTEXT_CHANGED', '登录、资料权限或来源已变化，请重新读取当前提交。')
  }
  return value
}
async function loadSource() {
  if (!readable.value || locked.value || !validIdentity.value || !props.initialSource) return
  const identity = { ...props.initialSource }, sequence = ++sourceSequence, epoch = store.sessionEpoch
  sourceLoading.value = true
  sourceError.value = ''
  invalidatePreview()
  try {
    const [question, answer] = await Promise.all([readCurrent<TrainingQuestion>(path.value), readCurrent<TrainingAnswer>(`${path.value}/answers/${encodeURIComponent(identity.answerId)}`)])
    if (!alive || !readable.value || sequence !== sourceSequence || epoch !== store.sessionEpoch) return
    if (question.id !== identity.questionId || question.sessionId !== identity.sessionId || answer.id !== identity.answerId || answer.questionId !== identity.questionId) throw new ApiError(409, 'AI_TRAINING_SOURCE_MISMATCH', '读取结果与入口所指的确切提交不一致。')
    source.value = { question: question.snapshot, answer }
    sourceEpoch.value = epoch
  } catch (error) { if (alive && sequence === sourceSequence) sourceError.value = errorText(error) }
  finally { if (alive && sequence === sourceSequence) sourceLoading.value = false }
}
function invalidatePreview() { previewSequence++; previewing.value = false; prepared.value = undefined; preparedRequest.value = ''; consent.value = false }
function captureSelection(event: Event) {
  if (blocked.value || previewing.value || !sourceReady.value || !selectedField.value) return
  const area = event.target as HTMLTextAreaElement, original = selectedField.value.text
  if (area.selectionEnd <= area.selectionStart) return
  // textarea 将 CRLF 归一为 LF；选区需映射回保存原文的 UTF-16 位置。
  const offset = (display: number) => {
    let raw = 0, shown = 0
    while (raw < original.length && shown < display) { raw += original[raw] === '\r' && original[raw + 1] === '\n' ? 2 : 1; shown++ }
    return raw
  }
  rangeStart.value = String(offset(area.selectionStart))
  rangeEnd.value = String(offset(area.selectionEnd))
  selectionError.value = ''
}
function addFragment() {
  if (blocked.value || previewing.value || !sourceReady.value || fragments.value.length >= 8) return
  if (rangeProblem.value || !range.value) { selectionError.value = rangeProblem.value; return }
  fragments.value.push({ ...range.value })
  rangeStart.value = ''
  rangeEnd.value = ''
  selectionError.value = ''
}
function removeFragment(index: number) { if (!blocked.value && !previewing.value) fragments.value.splice(index, 1) }
function chooseReport(id: string) {
  if (blocked.value || previewing.value || !feedbackAllowed.value || id === reportId.value) return
  if (feedbackIds.value.length && !window.confirm('切换报告会清空已选反馈，回答片段继续保留。确定切换？')) return
  feedbackIds.value = []
  reportId.value = id
  invalidatePreview()
}
function selectFeedback(id: string, selected: boolean) {
  if (blocked.value || previewing.value || !feedbackAllowed.value || !selectedReport.value?.observations.some(item => item.id === id)) return
  if (selected && feedbackIds.value.length < 5 && !feedbackIds.value.includes(id)) feedbackIds.value.push(id)
  if (!selected) feedbackIds.value = feedbackIds.value.filter(value => value !== id)
}
function clearFeedback() { if (!blocked.value && !previewing.value) feedbackIds.value = [] }
function nextEvaluations() { if (feedbackAllowed.value && evaluations.data.value?.nextCursor) { evaluationCursors.value.push(evaluationCursor.value); evaluationCursor.value = evaluations.data.value.nextCursor } }
function matches(manifest: AiPreview['manifest'], snapshot: AiTrainingDraftPreviewRequest, expected: AiTrainingDraftInput): boolean {
  return aiTrainingDraftManifestMatches(manifest) && manifest.instruction === snapshot.instruction
    && Boolean(manifest.trainingDraft && aiTrainingDraftInputFingerprint(manifest.trainingDraft) === aiTrainingDraftInputFingerprint(expected))
}
async function prepare() {
  if (blocked.value || !configured.value || problem.value || previewing.value || !expectedInput.value) return
  invalidatePreview()
  const epoch = store.sessionEpoch, sequence = ++previewSequence, generation = accessGeneration, signature = requestSignature.value
  const snapshot = JSON.parse(signature) as AiTrainingDraftPreviewRequest
  const expected = JSON.parse(JSON.stringify(expectedInput.value)) as AiTrainingDraftInput
  previewing.value = true
  previewError.value = ''
  try {
    const value = await preview<AiPreview>('/ai/training-drafts/preview', snapshot)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || sequence !== previewSequence || generation !== accessGeneration || signature !== requestSignature.value) return
    if (!matches(value.manifest, snapshot, expected)) { previewError.value = '预览中的确切提交、题面、片段或评价原文与本次选择不一致，请重新核对。'; return }
    prepared.value = value
    preparedRequest.value = signature
  } catch (error) { if (alive && sequence === previewSequence && epoch === store.sessionEpoch) previewError.value = errorText(error) }
  finally { if (alive && sequence === previewSequence) previewing.value = false }
}
function receive(value: AiTask) {
  createdResult.value = value
  changeState.dirty = false
  commandState.locked = false
  message.value = '整理任务已创建。候选仍需人工审阅，采用后才保存为笔记或概念。'
  emit('refresh')
  emit('created', value)
}
function create() {
  if (blocked.value || !configured.value || !prepared.value || !previewMatches.value || !consent.value || overBudget.value || problem.value || !expectedInput.value) return
  commandEpoch = store.sessionEpoch
  commandGeneration = accessGeneration
  const snapshot = JSON.parse(preparedRequest.value) as AiTrainingDraftPreviewRequest
  const expected = JSON.parse(JSON.stringify(expectedInput.value)) as AiTrainingDraftInput
  const authorized = prepared.value.manifest
  const body: AiCreateTrainingDraft = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  let submitted: typeof writer.pending.value
  void writer.run<AiTask>('/ai/training-drafts/tasks', 'POST', body, value => {
    if (!alive) return
    const authorizationMatches = value.manifest.reservedMicros === authorized.reservedMicros && value.manifest.inputTokenAllowance === authorized.inputTokenAllowance
      && JSON.stringify(value.manifest.configuration) === JSON.stringify(authorized.configuration)
    if (value.task.purpose !== 'KNOWLEDGE_DRAFT' || !matches(value.manifest, snapshot, expected) || !authorizationMatches) {
      writer.pending.value = submitted
      writer.error.value = new ApiError(502, 'AI_TASK_MISMATCH', '任务回执与授权的确切原文不一致，请用原请求恢复；输入继续保留。')
      emit('refresh')
      return
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) deferred.value = value
    else receive(value)
  })
  submitted = writer.pending.value
}
function retryCommand() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="training-draft-create" aria-label="从训练片段整理知识">
    <header class="create-heading"><div><p class="section-label">保留原答，明确选择出处</p><h2>从训练片段整理知识</h2><p class="field-help">选择确切提交中的原文片段，可另选同答评价中的反馈，再预览完整清单并授权一次整理。</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!configured" class="notice">AI 未启用或配置尚未完整。可以先选择片段并保留页面内输入。</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="createdResult" class="notice"><NButton text @click="emit('created', createdResult)">打开已创建的整理任务</NButton></p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retryCommand">用原请求重试</NButton></p>
    <p v-if="deferred" class="notice" role="status">服务器已确认创建，登录或资料权限变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建任务</NButton></p>
    <template v-if="!createdResult">
      <p v-if="!validIdentity" class="notice">请从训练提交记录或评价任务中的“整理为知识”入口选择确切提交。<RouterLink class="text-link" :to="{ name: 'training' }">前往训练记录</RouterLink></p>
      <section v-else class="source-panel">
        <header class="panel-heading"><h3>固定来源</h3><NButton size="small" :disabled="blocked || sourceLoading || previewing" @click="loadSource">重新读取原答</NButton></header>
        <p class="field-help">提交 {{ initialSource?.answerId }} · <RouterLink class="text-link" :to="sourceRoute">回到原训练与确切提交</RouterLink></p>
        <p v-if="sourceLoading" role="status">正在读取确切题面和提交…</p><p v-if="sourceError" class="notice error" role="alert">{{ sourceError }}</p>
        <template v-if="sourceReady && source"><h4>{{ source.question.content.title }} · 第 {{ source.answer.number }} 版提交</h4><p class="field-help">{{ answerLabels[source.answer.condition] }} · {{ aiDate(source.answer.createdAt) }} · {{ questionTypeLabels[source.question.content.type] }}</p>
          <details><summary>核对本次会发送的固定题面</summary><pre class="exact-text">{{ source.question.content.prompt }}</pre><template v-for="field in ([['conditions', '条件'], ['technology', '技术范围'], ['scenario', '场景']] as const)" :key="field[0]"><h4>{{ field[1] }}</h4><pre class="exact-text">{{ source.question.content[field[0]] || '（空）' }}</pre></template></details>
        </template>
      </section>
      <section v-if="sourceReady" class="source-panel">
        <header class="panel-heading"><h3>手选回答片段 · {{ fragments.length }} / 8</h3></header>
        <p class="field-help">片段默认不选。选择字段后，用鼠标或键盘选中文字，再点击“加入片段”；也可填写原文中的起止位置。未提交草稿、图坐标和外链不会自动发送。</p>
        <label class="form-field">原文字段<NSelect v-model:value="fieldKey" :options="fields.map(field => ({ value: field.key, label: field.label }))" :disabled="blocked || previewing" /></label>
        <label class="form-field">只读原文<textarea :value="selectedField?.text ?? ''" readonly :disabled="blocked || previewing" rows="9" @select="captureSelection" /></label>
        <div class="range-fields"><label class="form-field">起点（含）<NInput v-model:value="rangeStart" :disabled="blocked || previewing" :input-props="{ inputmode: 'numeric' }" placeholder="从 0 开始" /></label><label class="form-field">终点（不含）<NInput v-model:value="rangeEnd" :disabled="blocked || previewing" :input-props="{ inputmode: 'numeric' }" placeholder="原文字段的位置" /></label><NButton :disabled="blocked || previewing || fragments.length >= 8 || !!rangeProblem" @click="addFragment">加入片段</NButton></div>
        <p class="field-help">位置按 UTF-16 码元计数；每段最多 8000，同一字段的范围不可重叠。{{ rangeProblem }}</p>
        <pre v-if="range && !rangeProblem" class="exact-text selected-quote">{{ range.quote }}</pre><p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p>
      </section>
      <section v-if="fragments.length" class="source-panel"><h3>已选片段</h3><article v-for="(fragment, index) in fragments" :key="`${fragment.field}/${fragment.elementId}/${fragment.start}/${fragment.end}`" class="selected-fragment"><header class="panel-heading"><h4>{{ index + 1 }}. {{ aiTrainingDraftFieldLabels[fragment.field] }} · [{{ fragment.start }}, {{ fragment.end }})</h4><NButton size="small" quaternary :disabled="blocked || previewing" @click="removeFragment(index)">移出</NButton></header><p v-if="fragment.elementId" class="field-help">图元素 {{ fragment.elementId }}</p><pre class="exact-text">{{ fragment.quote }}</pre></article></section>
      <section v-if="sourceReady" class="source-panel">
        <header class="panel-heading"><h3>可选：同次提交的 AI 评价</h3><NButton size="small" :disabled="blocked || previewing" :aria-expanded="feedbackOpen" @click="feedbackOpen = !feedbackOpen">{{ feedbackOpen ? '收起评价选择' : '查看可选评价' }}</NButton></header>
        <p class="field-help">已选择 {{ feedbackIds.length }} / 5 条。选择报告不会自动加入反馈；接受、争议、忽略的决定及原因不外发，也不会被知识采用更改。<NButton v-if="feedbackIds.length" text size="small" :disabled="blocked || previewing" @click="clearFeedback">移出全部反馈</NButton></p>
        <template v-if="feedbackOpen">
          <p v-if="accessQuery.isPending.value" role="status" class="field-help">正在核对该提交的评价访问…</p><p v-if="accessQuery.error.value" class="notice error" role="alert">{{ errorText(accessQuery.error.value) }}<NButton text :disabled="blocked" @click="accessQuery.refetch()">重新读取权限</NButton></p>
          <p v-if="accessQuery.data.value && !feedbackAllowed" class="notice">尚未确认访问这次提交的评价。请回到原评价区核对反馈时机和辅助条件。<RouterLink class="text-link" :to="sourceRoute">前往原评价区</RouterLink></p>
          <template v-if="feedbackAllowed">
            <p v-if="evaluations.isFetching.value" role="status" class="field-help">正在读取成功评价…</p><p v-if="evaluations.error.value" class="notice error" role="alert">{{ errorText(evaluations.error.value) }}<NButton text :disabled="blocked" @click="evaluations.refetch()">重读评价列表</NButton></p>
            <div class="report-options"><NButton v-for="item in availableEvaluations" :key="item.task.id" size="small" :disabled="blocked || previewing" :type="reportId === item.task.id ? 'primary' : 'default'" @click="chooseReport(item.task.id)">评价 · {{ aiDate(item.task.createdAt) }}</NButton></div>
            <p v-if="evaluations.data.value && !availableEvaluations.length" class="field-help">本页没有这次提交的成功评价；可翻页查找，或仅使用回答片段。</p>
            <div v-if="evaluationCursors.length || evaluations.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="blocked || !evaluationCursors.length" @click="evaluationCursor = evaluationCursors.pop()">上一页</NButton><NButton size="small" :disabled="blocked || !evaluations.data.value?.nextCursor" @click="nextEvaluations">下一页</NButton></div>
            <p v-if="reportId && report.isPending.value" class="field-help" role="status">正在读取所选报告…</p><p v-if="report.error.value" class="notice error" role="alert">{{ errorText(report.error.value) }}<NButton text :disabled="blocked" @click="report.refetch()">重新读取报告</NButton></p>
            <template v-if="selectedReport"><h4>报告限制 · AI 未审</h4><pre class="exact-text">{{ selectedReport.limitations }}</pre><p class="field-help">勾选反馈后，其下列完整内容与原引文会随报告限制一同发送。原引文可能包含你没有单独选入的回答或个人未审参考文字，请逐项核对。</p>
              <article v-for="item in selectedReport.observations" :key="item.id" class="feedback-item"><NCheckbox :checked="feedbackIds.includes(item.id)" :disabled="blocked || previewing || (!feedbackIds.includes(item.id) && feedbackIds.length >= 5)" @update:checked="selectFeedback(item.id, $event)">加入 {{ item.id }} · {{ questionDimensionLabels[item.dimension] }} · {{ coverageLabels[item.coverage] }}</NCheckbox><dl><template v-for="field in ([['observation', '观察'], ['condition', '条件'], ['basis', '依据'], ['limitation', '限制'], ['suggestion', '建议']] as const)" :key="field[0]"><dt>{{ field[1] }}</dt><dd>{{ item[field[0]] || '（空）' }}</dd></template></dl><details><summary>原引文 · {{ item.citations.length }} 条</summary><div v-for="(citation, index) in item.citations" :key="index"><p class="field-help">{{ citation.kind }} · {{ citation.elementId ?? '原文字段' }} · [{{ citation.start }}, {{ citation.end }})</p><pre class="exact-text">{{ citation.quote }}</pre></div><p v-if="!item.citations.length" class="field-help">这条反馈没有原引文。</p></details></article>
            </template>
          </template>
        </template>
      </section>
      <label class="form-field">整理要求<NInput v-model:value="instruction" :disabled="blocked || previewing" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="例如：将所选片段整理成一则概念笔记，保留适用条件与待核实之处。" /><span class="field-help">可留空，最多 1000 个 UTF-16 码元。原答是当时的表达，评价保持 AI 未审；整理与逐字引用不证明技术正确、经历真实或已经掌握。</span></label>
      <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || !!problem" @click="prepare">预览完整外发清单</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div>
      <p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
      <section v-if="prepared && previewMatches" class="consent-panel"><AiManifest :manifest="prepared.manifest" :budget="prepared.budget" /><p v-if="overBudget" class="notice error">本次费用预留超过当前可用预算，请核对后重新预览。</p><AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || !configured">我已核对固定题面、所选回答片段、所选反馈及其原引文与限制，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice><NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !consent || overBudget || !configured || !!problem" @click="create">授权本次外发并整理为知识候选</NButton><p class="field-help">改变来源、片段、反馈、要求或配置后需重新预览。未知结果使用原请求恢复；候选不会自动创建关系、事实、回忆卡或复练记录。</p></section>
    </template>
  </section>
</template>

<style scoped>
.training-draft-create { display: grid; gap: 23px; min-width: 0; padding: 25px 27px 30px; }
.create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; }
.create-heading > div { min-width: 0; } h2 { margin: 6px 0 9px; font-size: 24px; text-wrap: balance; } h3 { margin: 0; font-size: 16px; } h4 { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
.source-panel, .form-field, .feedback-item, .selected-fragment { display: grid; gap: 12px; min-width: 0; }
.source-panel { padding-top: 19px; border-top: 1px solid var(--line); }
.form-field { color: var(--muted); font-size: 13px; } .form-field > textarea { width: 100%; min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); color: var(--ink); font: 13px/1.75 var(--font-body, sans-serif); resize: vertical; }
.range-fields { display: grid; grid-template-columns: 1fr 1fr auto; align-items: end; gap: 12px; }
.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }
.selected-quote { border-left: 3px solid var(--brand); } .selected-fragment, .feedback-item { padding-top: 14px; border-top: 1px solid var(--line); }
.report-options { display: flex; flex-wrap: wrap; gap: 8px; } details { min-width: 0; } summary { cursor: pointer; color: var(--muted); font-size: 12px; margin-bottom: 9px; }
dl { display: grid; grid-template-columns: 45px minmax(0, 1fr); gap: 9px; margin: 0; font-size: 13px; } dt { color: var(--muted); } dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); min-width: 0; } .consent-panel > .n-button { justify-self: start; }
@media (max-width: 700px) { .training-draft-create { padding: 22px 17px; } h2 { font-size: 21px; } .panel-heading { flex-wrap: wrap; } .range-fields { grid-template-columns: 1fr 1fr; } .range-fields > .n-button { justify-self: start; } }
</style>
