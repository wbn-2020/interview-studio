<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { AiEvaluationInput, AiPreview, AiSettings, AiTask } from '../../api/ai'
import { aiPreparationPlanTextValid, formatAiAmount } from '../../api/ai'
import type { AiReviewDraftCreateRequest, AiReviewDraftFeedbackInput, AiReviewDraftField, AiReviewDraftFragment, AiReviewDraftInput, AiReviewDraftPreviewRequest, AiReviewDraftRecollectionInput } from '../../api/aiReviewDraft'
import { aiReviewDraftFieldLabels, aiReviewDraftInputFingerprint, aiReviewDraftInputValid, aiReviewDraftKindLabels, aiReviewDraftManifestMatches, aiReviewDraftPaths, aiReviewDraftRangeMatches, aiReviewDraftSourceSelectionValid, aiReviewDraftTaskValid } from '../../api/aiReviewDraft'
import type { ReviewAnswerLink, ReviewFeedback } from '../../api/review'
import { reviewAnswerMatches, reviewAnswerQuery, reviewChoiceLabels, reviewFeedbackValid, reviewIdValid } from '../../api/review'
import type { Recollection } from '../../api/recollection'
import { recollectionBaselineLabels, recollectionValid } from '../../api/recollection'
import { questionTypeLabels } from '../../api/bank'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

type SourceIdentity = { kind: 'SELF_CHECK' | 'EXTERNAL_FEEDBACK'; feedbackId: string; feedbackRevision: number; answer: ReviewAnswerLink }
  | { kind: 'RECOLLECTION'; recollectionId: string; versionId: string; expectedRevision: number }
type SourceField = { field: AiReviewDraftField; text: string }
type SavedSource = { kind: 'SELF_CHECK' | 'EXTERNAL_FEEDBACK'; fixed: Omit<AiReviewDraftFeedbackInput, 'fragments'>; fields: SourceField[] }
  | { kind: 'RECOLLECTION'; fixed: Omit<AiReviewDraftRecollectionInput, 'fragments'>; fields: SourceField[] }
function feedbackFragment(value: AiReviewDraftFragment): value is AiReviewDraftFeedbackInput['fragments'][number] {
  return ['OBSERVATION', 'CONDITION', 'BASIS', 'LIMITATION', 'SUGGESTION'].includes(value.field)
}
function recollectionFragment(value: AiReviewDraftFragment): value is AiReviewDraftRecollectionInput['fragments'][number] {
  return ['QUESTION', 'CONDITIONS', 'ANSWER_FRAGMENT', 'FEEDBACK', 'UNCERTAINTY', 'GAP'].includes(value.field)
}
const props = defineProps<{ settings?: AiSettings; disabled?: boolean; initialSource?: SourceIdentity }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const source = shallowRef<SavedSource>()
const sourceEpoch = ref(-1)
const sourceLoading = ref(false)
const sourceError = ref('')
const instruction = ref('')
const fragments = ref<AiReviewDraftFragment[]>([])
const fieldKey = ref<AiReviewDraftField>()
const rangeStart = ref('')
const rangeEnd = ref('')
const selectionError = ref('')
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
  const value = props.initialSource
  if (!value) return false
  if (value.kind === 'RECOLLECTION') return reviewIdValid(value.recollectionId) && reviewIdValid(value.versionId)
    && Number.isSafeInteger(value.expectedRevision) && value.expectedRevision >= 0 && value.expectedRevision <= 1000
  return ['SELF_CHECK', 'EXTERNAL_FEEDBACK'].includes(value.kind) && reviewIdValid(value.feedbackId)
    && Number.isSafeInteger(value.feedbackRevision) && value.feedbackRevision >= 1 && value.feedbackRevision <= 100
    && [value.answer.sessionId, value.answer.questionId, value.answer.answerId].every(reviewIdValid)
})
const sourceReady = computed(() => readable.value && validIdentity.value && Boolean(source.value)
  && sourceEpoch.value === store.sessionEpoch && !sourceLoading.value && !sourceError.value)
const sourceRoute = computed(() => {
  const value = props.initialSource
  if (!value) return { name: 'review' }
  return value.kind === 'RECOLLECTION' ? { name: 'recollections', query: { item: value.recollectionId, versionId: value.versionId } }
    : { name: 'review', query: { ...value.answer, feedbackId: value.feedbackId, revision: String(value.feedbackRevision) } }
})
const fields = computed(() => source.value?.fields ?? [])
const selectedField = computed(() => fields.value.find(field => field.field === fieldKey.value))
const range = computed<AiReviewDraftFragment | undefined>(() => {
  const field = selectedField.value
  if (!field || !/^\d+$/.test(rangeStart.value) || !/^\d+$/.test(rangeEnd.value)) return undefined
  const start = Number(rangeStart.value), end = Number(rangeEnd.value)
  return { field: field.field, start, end, quote: field.text.slice(start, end) }
})
const rangeProblem = computed(() => {
  const value = range.value, field = selectedField.value
  if (!value || !field) return '请选择原文或填写起止位置。'
  if (!aiReviewDraftRangeMatches(field.text, value.start, value.end, value.quote)
    || !aiPreparationPlanTextValid(value.quote, 8000) || !value.quote.trim()) return '片段需非空且不超过 8000 个 UTF-16 码元，边界不能拆开完整字符。'
  if (fragments.value.some(item => item.field === value.field && item.start < value.end && value.start < item.end)) return '同一字段中的片段范围不可重叠。'
  return ''
})
const request = computed<AiReviewDraftPreviewRequest | undefined>(() => {
  const identity = props.initialSource
  if (!identity) return undefined
  const selected = fragments.value.map(item => ({ field: item.field, start: item.start, end: item.end, quote: item.quote }))
  if (identity.kind === 'RECOLLECTION') {
    if (!selected.every(recollectionFragment)) return undefined
    return { instruction: instruction.value, source: { kind: identity.kind, feedback: null,
      recollection: { recollectionId: identity.recollectionId, versionId: identity.versionId, expectedRevision: identity.expectedRevision, fragments: selected } } }
  }
  if (!selected.every(feedbackFragment)) return undefined
  return { instruction: instruction.value, source: { kind: identity.kind,
    feedback: { feedbackId: identity.feedbackId, feedbackRevision: identity.feedbackRevision, fragments: selected }, recollection: null } }
})
const requestSignature = computed(() => JSON.stringify(request.value))
const expectedInput = computed<AiReviewDraftInput | undefined>(() => {
  const value = source.value
  if (!sourceReady.value || !value) return undefined
  const selected = fragments.value.map(item => ({ ...item }))
  if (value.kind === 'RECOLLECTION') {
    if (!selected.every(recollectionFragment)) return undefined
    return { kind: value.kind, feedback: null, recollection: { ...value.fixed, fragments: selected } }
  }
  if (!selected.every(feedbackFragment)) return undefined
  return { kind: value.kind, feedback: { ...value.fixed, fragments: selected }, recollection: null }
})
const problem = computed(() => {
  if (!sourceReady.value) return '请先核对入口所指的当前反馈修订或回忆版本。'
  if (!request.value || !aiReviewDraftSourceSelectionValid(request.value.source)) return '请手动选择 1–8 个有效且互不重叠的原文片段。'
  if (fragments.value.some(item => { const field = fields.value.find(field => field.field === item.field); return !field || !aiReviewDraftRangeMatches(field.text, item.start, item.end, item.quote) })) return '已选片段与原文字段不一致，请核对范围。'
  if (!expectedInput.value || !aiReviewDraftInputValid(expectedInput.value)) return '来源固定背景或所选片段不完整，请重新读取。'
  if (!aiPreparationPlanTextValid(instruction.value, 1000)) return '整理要求最多 1000 个 UTF-16 码元，不能含 NUL 或不完整的字符。'
  return ''
})
const configured = computed(() => Boolean(props.settings?.configuration.enabled && props.settings.configuration.configured))
const dirty = computed(() => !createdResult.value && Boolean(fragments.value.length || instruction.value || rangeStart.value || rangeEnd.value))
const previewMatches = computed(() => Boolean(prepared.value && request.value && expectedInput.value
  && preparedRequest.value === requestSignature.value && matches(prepared.value.manifest, request.value, expectedInput.value)))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true, sourceSequence = 0, previewSequence = 0, accessGeneration = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0

watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed; commandState.locked = waiting
  store.changes.set('ai-create', changeState); store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([requestSignature, fieldKey, rangeStart, rangeEnd], invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch(fieldKey, () => { rangeStart.value = ''; rangeEnd.value = ''; selectionError.value = '' }, { flush: 'sync' })
watch([sourceKey, () => store.sessionEpoch, () => store.sessionActive, () => props.disabled], () => {
  accessGeneration++; sourceSequence++; sourceLoading.value = false; sourceEpoch.value = -1
  invalidatePreview()
  // 权限重新确认只使旧预览失效；已选片段及尚未加入的范围继续保留。
  if (readable.value && validIdentity.value && !locked.value) void loadSource()
}, { immediate: true, flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['AI_CONFIG_CHANGED', 'AI_MANIFEST_CHANGED', 'AI_SOURCE_CHANGED', 'AI_REVIEW_SOURCE_NOT_FOUND', 'AI_EVALUATION_ACCESS_REQUIRED', 'AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED', 'AI_DISABLED', 'AI_NOT_CONFIGURED', 'AI_BUDGET_EXCEEDED'].includes(error.code)) {
    invalidatePreview(); emit('refresh')
    if (['AI_SOURCE_CHANGED', 'AI_REVIEW_SOURCE_NOT_FOUND', 'AI_EVALUATION_ACCESS_REQUIRED', 'AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED'].includes(error.code)) sourceEpoch.value = -1
  }
})
onUnmounted(() => {
  alive = false; sourceSequence++; previewSequence++
  if (store.changes.get('ai-create') === changeState) store.changes.delete('ai-create')
  if (store.changes.get('ai-command') === commandState) store.changes.delete('ai-command')
})

async function readCurrent<T>(target: string): Promise<T> {
  const epoch = store.sessionEpoch, generation = accessGeneration, identity = sourceKey.value
  const value = await read<T>(target)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || generation !== accessGeneration || identity !== sourceKey.value)
    throw new ApiError(0, 'REVIEW_DRAFT_CONTEXT_CHANGED', '登录、资料权限或来源已变化，本地选择继续保留。')
  return value
}
async function loadSource() {
  if (!readable.value || locked.value || !validIdentity.value || !props.initialSource) return
  const identity = props.initialSource, sequence = ++sourceSequence, epoch = store.sessionEpoch
  sourceLoading.value = true; sourceError.value = ''; invalidatePreview()
  try {
    let value: SavedSource
    if (identity.kind === 'RECOLLECTION') {
      const current = await readCurrent<Recollection>(`/review/recollections/${identity.recollectionId}`)
      if (!recollectionValid(current, identity.recollectionId)) throw new ApiError(502, 'AI_REVIEW_SOURCE_MISMATCH', '回忆读取结果不完整，请重新读取。')
      if (current.status !== 'ACTIVE' || current.currentVersion.id !== identity.versionId || current.revision !== identity.expectedRevision)
        throw new ApiError(409, 'AI_SOURCE_CHANGED', '这版回忆已不是入口确认的使用中版本或来源修订已变化，请返回原回忆重新选择。页面内片段保留。')
      const content = current.currentVersion.content
      // 仅构造冻结契约中的固定背景和可手选文字，不保留或外发面试快照。
      value = { kind: identity.kind, fixed: { recollectionId: current.id, versionId: current.currentVersion.id, versionNumber: current.currentVersion.number,
        sourceRevision: current.revision, title: content.title, type: content.type, baselineKind: content.baselineKind, uncertainty: content.uncertainty },
        fields: [ { field: 'QUESTION', text: content.question }, { field: 'CONDITIONS', text: content.conditions },
          ...(content.baselineKind === 'RECOLLECTION' ? [{ field: 'ANSWER_FRAGMENT' as const, text: content.answerFragment }] : []),
          { field: 'FEEDBACK', text: content.feedback }, { field: 'UNCERTAINTY', text: content.uncertainty }, { field: 'GAP', text: content.gap } ] }
    } else {
      const [feedback, context] = await Promise.all([
        readCurrent<ReviewFeedback>(`/review/feedback/${identity.feedbackId}`),
        readCurrent<AiEvaluationInput>(`/review/context?${reviewAnswerQuery(identity.answer)}`),
      ])
      if (!reviewAnswerMatches(context, identity.answer) || !Number.isSafeInteger(context.answerNumber) || context.answerNumber < 1 || context.answerNumber > 100
        || !['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(context.answerCondition)
        || !reviewFeedbackValid(feedback, identity.answer, context) || feedback.id !== identity.feedbackId)
        throw new ApiError(502, 'AI_REVIEW_SOURCE_MISMATCH', '反馈与入口的确切提交不一致，请返回原反馈核对。')
      if (feedback.revision !== identity.feedbackRevision || feedback.content.kind !== identity.kind)
        throw new ApiError(409, 'AI_SOURCE_CHANGED', '这版反馈已不是当前修订或类型已变化，请返回原反馈重新选择。页面内片段保留。')
      const content = feedback.content, decision = [...feedback.decisions].sort((left, right) => right.revision - left.revision)[0]
      // 作者、原引文和决定原因留在复盘记录，不能因整个反馈对象序列化而外发。
      value = { kind: identity.kind, fixed: { feedbackId: feedback.id, feedbackRevision: feedback.revision,
        answer: { sessionId: identity.answer.sessionId, questionId: identity.answer.questionId, answerId: identity.answer.answerId },
        answerNumber: context.answerNumber, answerCondition: context.answerCondition, dimension: content.dimension, coverage: content.coverage,
        decisionRevision: decision?.revision ?? 0, decisionChoice: decision?.choice ?? null, condition: content.condition, basis: content.basis, limitation: content.limitation },
        fields: [ { field: 'OBSERVATION', text: content.observation }, { field: 'CONDITION', text: content.condition }, { field: 'BASIS', text: content.basis },
          { field: 'LIMITATION', text: content.limitation }, { field: 'SUGGESTION', text: content.suggestion } ] }
    }
    if (!alive || !readable.value || sequence !== sourceSequence || epoch !== store.sessionEpoch) return
    source.value = value; sourceEpoch.value = epoch
    if (!fieldKey.value) fieldKey.value = value.fields[0]?.field
  } catch (error) { if (alive && sequence === sourceSequence) sourceError.value = errorText(error) }
  finally { if (alive && sequence === sourceSequence) sourceLoading.value = false }
}
function invalidatePreview() { previewSequence++; previewing.value = false; prepared.value = undefined; preparedRequest.value = ''; consent.value = false }
function captureSelection(event: Event) {
  if (blocked.value || previewing.value || !sourceReady.value || !selectedField.value) return
  const area = event.target as HTMLTextAreaElement, original = selectedField.value.text
  if (area.value !== original.replace(/\r\n?/g, '\n') || area.selectionEnd <= area.selectionStart) return
  // textarea 将 CRLF/CR 归一成 LF；回写原始 UTF-16 下标后仍由范围校验拒绝代理对分割。
  const offset = (display: number) => {
    let raw = 0, shown = 0
    while (raw < original.length && shown < display) { raw += original[raw] === '\r' && original[raw + 1] === '\n' ? 2 : 1; shown++ }
    return raw
  }
  rangeStart.value = String(offset(area.selectionStart)); rangeEnd.value = String(offset(area.selectionEnd)); selectionError.value = ''
}
function addFragment() {
  if (blocked.value || previewing.value || !sourceReady.value || fragments.value.length >= 8) return
  if (rangeProblem.value || !range.value) { selectionError.value = rangeProblem.value; return }
  fragments.value.push({ ...range.value }); rangeStart.value = ''; rangeEnd.value = ''; selectionError.value = ''
}
function removeFragment(index: number) { if (!blocked.value && !previewing.value) fragments.value.splice(index, 1) }
function matches(manifest: AiPreview['manifest'], snapshot: AiReviewDraftPreviewRequest, expected: AiReviewDraftInput): boolean {
  return aiReviewDraftManifestMatches(manifest) && manifest.instruction === snapshot.instruction
    && Boolean(manifest.reviewDraft && aiReviewDraftInputFingerprint(manifest.reviewDraft) === aiReviewDraftInputFingerprint(expected))
}
async function prepare() {
  if (blocked.value || !configured.value || problem.value || previewing.value || !request.value || !expectedInput.value) return
  invalidatePreview()
  const epoch = store.sessionEpoch, sequence = ++previewSequence, generation = accessGeneration, signature = requestSignature.value!
  const snapshot = JSON.parse(signature) as AiReviewDraftPreviewRequest
  const expected = JSON.parse(JSON.stringify(expectedInput.value)) as AiReviewDraftInput
  previewing.value = true; previewError.value = ''
  try {
    const value = await preview<AiPreview>(aiReviewDraftPaths.preview, snapshot)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || sequence !== previewSequence || generation !== accessGeneration || signature !== requestSignature.value) return
    if (!matches(value.manifest, snapshot, expected)) { previewError.value = '预览来源、固定背景或所选片段与本次选择不一致，请重新读取原来源并核对。'; return }
    if (JSON.stringify(value.manifest.configuration) !== JSON.stringify(props.settings?.configuration)) { previewError.value = '服务器配置与页面配置已不一致，请刷新配置后重新预览。'; emit('refresh'); return }
    prepared.value = value; preparedRequest.value = signature
  } catch (error) { if (alive && sequence === previewSequence && epoch === store.sessionEpoch) previewError.value = errorText(error) }
  finally { if (alive && sequence === previewSequence) previewing.value = false }
}
function receive(value: AiTask) {
  createdResult.value = value; changeState.dirty = false; commandState.locked = false
  message.value = '知识整理任务已创建，候选需要人工审阅后采用。'; emit('refresh'); emit('created', value)
}
function create() {
  if (blocked.value || !configured.value || !prepared.value || !previewMatches.value || !consent.value || overBudget.value || problem.value || !expectedInput.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration
  const snapshot = JSON.parse(preparedRequest.value) as AiReviewDraftPreviewRequest
  const expected = JSON.parse(JSON.stringify(expectedInput.value)) as AiReviewDraftInput
  const authorized = prepared.value.manifest
  const body: AiReviewDraftCreateRequest = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  let submitted: typeof writer.pending.value
  void writer.run<AiTask>(aiReviewDraftPaths.create, 'POST', body, value => {
    if (!alive) return
    let valid = false
    try { valid = aiReviewDraftTaskValid(value) && matches(value.manifest, snapshot, expected)
      && value.manifest.reservedMicros === authorized.reservedMicros && value.manifest.inputTokenAllowance === authorized.inputTokenAllowance
      && JSON.stringify(value.manifest.configuration) === JSON.stringify(authorized.configuration) } catch { valid = false }
    if (!valid) {
      writer.pending.value = submitted
      writer.error.value = new ApiError(502, 'AI_TASK_MISMATCH', '任务回执与本次授权不一致，请用原请求恢复；输入继续保留。')
      emit('refresh'); return
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
  <section class="review-draft-create" aria-label="从复盘片段整理知识">
    <header class="create-heading"><div><p class="section-label">选择确切来源，保留判断边界</p><h2>从复盘片段整理知识</h2><p class="field-help">从一版当前自检、外部意见或使用中的当前回忆手选 1–8 个片段，核对完整外发清单后授权本次整理。</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!configured" class="notice">AI 未启用或配置尚未完整，仍可阅读和选择来源。也可到<RouterLink class="text-link" :to="{ name: 'knowledge' }">知识库手工新建笔记</RouterLink>，自行保留原来源及限制。</p>
    <p v-if="!readable" class="notice" role="status">登录或资料权限待确认，本地输入和尚未加入的选区继续保留。</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="createdResult" class="notice"><NButton text :disabled="!readable" @click="emit('created', createdResult)">打开已创建任务</NButton></p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retryCommand">用原请求重试</NButton></p>
    <p v-if="deferred" class="notice" role="status">服务器已确认创建，登录或资料权限变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建任务</NButton></p>
    <template v-if="!createdResult">
      <p v-if="!validIdentity" class="notice">请从已保存的当前反馈修订或回忆版本进入。<RouterLink class="text-link" :to="{ name: 'review' }">前往复盘</RouterLink> · <RouterLink class="text-link" :to="{ name: 'recollections' }">前往回忆</RouterLink></p>
      <section v-else class="source-panel"><header class="panel-heading"><h3>固定来源</h3><NButton size="small" :disabled="blocked || sourceLoading || previewing" @click="loadSource">重新读取并核对来源</NButton></header><RouterLink class="text-link" :to="sourceRoute">回到这版来源</RouterLink><p v-if="sourceLoading" role="status">正在核对来源版本、状态和访问条件…</p><p v-if="sourceError" class="notice error" role="alert">{{ sourceError }}</p>
        <template v-if="sourceReady && source"><h4>{{ aiReviewDraftKindLabels[source.kind] }}</h4>
          <template v-if="source.kind === 'RECOLLECTION'"><p class="field-help">{{ source.fixed.title }} · {{ questionTypeLabels[source.fixed.type] }} · {{ recollectionBaselineLabels[source.fixed.baselineKind] }}</p><p class="field-help">回忆 {{ source.fixed.recollectionId }} · 版本 {{ source.fixed.versionId }} · v{{ source.fixed.versionNumber }} · 来源修订 {{ source.fixed.sourceRevision }}</p><h4>固定外发：完整记忆不确定性</h4><pre class="exact-text">{{ source.fixed.uncertainty }}</pre></template>
          <template v-else><p class="field-help">反馈 {{ source.fixed.feedbackId }} · 修订 {{ source.fixed.feedbackRevision }} · 提交 {{ source.fixed.answer.answerId }} · 第 {{ source.fixed.answerNumber }} 版</p><p class="field-help">固定外发的最新决定：修订 {{ source.fixed.decisionRevision }} · {{ source.fixed.decisionChoice ? reviewChoiceLabels[source.fixed.decisionChoice] : '尚未决定' }}</p><div v-for="field in (['condition', 'basis', 'limitation'] as const)" :key="field"><h4>{{ ({ condition: '固定适用条件', basis: '固定观察依据', limitation: '固定判断限制' })[field] }}</h4><pre class="exact-text">{{ source.fixed[field] || '（空）' }}</pre></div></template>
        </template>
      </section>
      <section v-if="sourceReady" class="source-panel"><h3>手选原文片段 · {{ fragments.length }} / 8</h3><p class="field-help">先选择字段，再用鼠标或键盘选中文字，点击“加入片段”。也可填写原文字段中的起止位置。没有任何片段会自动选入。</p><label class="form-field">原文字段<NSelect v-model:value="fieldKey" :options="fields.map(field => ({ value: field.field, label: aiReviewDraftFieldLabels[field.field] }))" :disabled="blocked || previewing" /></label><label class="form-field">只读原文<textarea :value="selectedField?.text ?? ''" readonly rows="9" :disabled="blocked || previewing" @select="captureSelection" /></label><div class="range-fields"><label class="form-field">起点（含）<NInput v-model:value="rangeStart" :disabled="blocked || previewing" :input-props="{ inputmode: 'numeric' }" placeholder="从 0 开始" /></label><label class="form-field">终点（不含）<NInput v-model:value="rangeEnd" :disabled="blocked || previewing" :input-props="{ inputmode: 'numeric' }" placeholder="原文字段的位置" /></label><NButton :disabled="blocked || previewing || fragments.length >= 8 || !!rangeProblem" @click="addFragment">加入片段</NButton></div><p class="field-help">按原文 UTF-16 码元计数，每段最多 8000，同一字段范围不可重叠。{{ rangeProblem }}</p><pre v-if="range && !rangeProblem" class="exact-text selected-quote">{{ range.quote }}</pre><p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p></section>
      <section v-if="fragments.length" class="source-panel"><h3>已选片段</h3><article v-for="(fragment, index) in fragments" :key="`${fragment.field}/${fragment.start}/${fragment.end}`" class="selected-fragment"><header class="panel-heading"><h4>{{ index + 1 }}. {{ aiReviewDraftFieldLabels[fragment.field] }} · [{{ fragment.start }}, {{ fragment.end }})</h4><NButton size="small" quaternary :disabled="blocked || previewing" @click="removeFragment(index)">移出</NButton></header><pre class="exact-text">{{ fragment.quote }}</pre></article></section>
      <label class="form-field">整理要求<NInput v-model:value="instruction" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="blocked || previewing" placeholder="例如：整理为概念笔记，保留适用条件、判断限制和记不清之处。" /><span class="field-help">可留空，最多 1000 个 UTF-16 码元。知识候选需要人工核对后采用；整理不会改变原反馈决定或回忆。</span></label>
      <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || !!problem" @click="prepare">预览完整外发清单</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div><p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
      <section v-if="prepared && previewMatches" class="consent-panel"><AiManifest :manifest="prepared.manifest" :budget="prepared.budget" /><details><summary>核对完整供应商输入投影</summary><pre class="exact-text">{{ JSON.stringify({ purpose: prepared.manifest.purpose, instruction: prepared.manifest.instruction, reviewDraft: prepared.manifest.reviewDraft }, null, 2) }}</pre></details><p v-if="overBudget" class="notice error">本次费用预留超过当前可用预算，请核对后重新预览。</p><AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || !configured">我已核对确切来源、固定背景、所选片段及整理要求，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice><NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !consent || overBudget || !configured || !!problem" @click="create">授权本次外发并整理为知识候选</NButton><p class="field-help">片段、要求、配置或权限变化后需重新预览。结果未知时以原请求和原幂等键恢复，避免重复创建。</p></section>
    </template>
  </section>
</template>

<style scoped>
.review-draft-create { display: grid; gap: 23px; min-width: 0; padding: 25px 27px 30px; }.create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; }.create-heading > div { min-width: 0; }
h2 { margin: 6px 0 9px; font-size: 24px; text-wrap: balance; }h3 { margin: 0; font-size: 16px; }h4 { margin: 0; font-size: 13px; overflow-wrap: anywhere; }.source-panel, .form-field, .selected-fragment { display: grid; gap: 12px; min-width: 0; }.source-panel { padding-top: 19px; border-top: 1px solid var(--line); }.form-field { color: var(--muted); font-size: 13px; }
.form-field > textarea { width: 100%; min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); color: var(--ink); font: 13px/1.75 var(--font-body, sans-serif); resize: vertical; }.range-fields { display: grid; grid-template-columns: 1fr 1fr auto; align-items: end; gap: 12px; }.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }.selected-quote { border-left: 3px solid var(--brand); }.selected-fragment { padding-top: 14px; border-top: 1px solid var(--line); }.consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); min-width: 0; }.consent-panel > .n-button { justify-self: start; }summary { cursor: pointer; color: var(--muted); font-size: 12px; margin-bottom: 9px; }.field-help { overflow-wrap: anywhere; }
@media (max-width: 700px) { .review-draft-create { padding: 22px 17px; }h2 { font-size: 21px; }.panel-heading { flex-wrap: wrap; }.range-fields { grid-template-columns: 1fr 1fr; }.range-fields > .n-button { justify-self: start; } }
</style>
