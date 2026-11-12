<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiAttempt, AiCitation, AiSettings, AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate, aiDraftPromptVersion, aiExpressionManifestMatches, aiFeeLabels, aiKnowledgeMaterialChecksMatch, aiMaterialDraftSourcesMatch, aiPendingStatuses, aiPreparationChecksMatch, aiPreparationPlanManifestMatches, aiPurposeLabels, aiRelationManifestMatches, aiSourceKindLabel, aiSourceRoute, aiTaskStatusLabel, aiTrainingDraftManifestMatches, formatAiAmount, parseAiMicros } from '../../api/ai'
import { aiRelationCandidateFingerprint, aiRelationDecisionFingerprint, aiRelationTaskMatches } from '../../api/ai'
import { questionDimensionLabels, questionTypeLabels } from '../../api/bank'
import { dimensionOptions, expressionOptions } from '../../api/preparation'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useRecentVisit } from '../../composables/useRecentVisit'
import { useWorkspaceStore } from '../../state/workspace'
import MarkdownPreview from '../MarkdownPreview.vue'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import AiAuthorizationScope from './AiAuthorizationScope.vue'
import AiDraftPreview from './AiDraftPreview.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'
import AiCandidateAdoption from './AiCandidateAdoption.vue'
import AiQuestionAdoption from './AiQuestionAdoption.vue'
import AiExpressionAdoption from './AiExpressionAdoption.vue'
import AiPreparationPlanAdoption from './AiPreparationPlanAdoption.vue'
import AiEvaluationReport from './AiEvaluationReport.vue'
import AiMaterialAnswer from './AiMaterialAnswer.vue'
import AiMaterialCitations from './AiMaterialCitations.vue'
import AiTrainingCitations from './AiTrainingCitations.vue'
import AiRelationCandidates from './AiRelationCandidates.vue'
import { aiComparisonManifestMatches, aiComparisonTaskValid } from '../../api/aiComparison'
import AiComparisonCandidate from './AiComparisonCandidate.vue'
import { aiReviewDraftKindLabels, aiReviewDraftManifestMatches, aiReviewDraftTaskValid } from '../../api/aiReviewDraft'
import AiReviewCitations from './AiReviewCitations.vue'
import { aiCardDraftCandidateValid, aiCardDraftCitationValid, aiCardDraftManifestMatches, aiCardDraftTaskValid } from '../../api/aiCardDraft'
import AiCardDraftAdoption from './AiCardDraftAdoption.vue'
import { aiJdRequirementsCandidateValid, aiJdRequirementsCitationValid, aiJdRequirementsManifestMatches, aiJdRequirementsTaskValid } from '../../api/aiJdRequirements'
import AiJdRequirementsAdoption from './AiJdRequirementsAdoption.vue'
import { aiFactDraftCandidateValid, aiFactDraftManifestMatches, aiFactDraftMaterialCitationValid, aiFactDraftTaskValid, aiFactDraftTrainingCitationValid } from '../../api/aiFactDraft'
import { aiTrainingDraftFieldLabels } from '../../api/ai'
import AiFactDraftAdoption from './AiFactDraftAdoption.vue'
import { aiRewriteManifestMatches, aiRewriteTaskMatches } from '../../api/aiRewrite'
import AiRewriteCandidate from './AiRewriteCandidate.vue'
import { aiSourceQaManifestValid, aiSourceQaTaskValid } from '../../api/aiSourceQa'
import AiSourceQaAnswer from './AiSourceQaAnswer.vue'

const props = defineProps<{ taskId: string; disabled?: boolean; settings?: AiSettings }>()
const emit = defineEmits<{ changed: []; close: [] }>()
const store = useWorkspaceStore()
const recordRecentVisit = useRecentVisit('AI_TASK', () => props.taskId, () => !props.disabled)
const writer = useCommand()
const current = shallowRef<AiTask>()
const sourceQaTask = ref(false)
const sourceQaManifestHash = ref<string>()
const sourceQaChecking = ref(false)
const currentEpoch = ref(store.sessionEpoch)
const deferred = shallowRef<() => void>()
const manifestOpen = ref(false)
const retryOpen = ref(false)
const retryRevision = ref<number>()
const retryConsent = ref(false)
const retryAuthorization = ref<AiAuthorizationReference>()
const authorizationState = reactive({ dirty: false, locked: false })
const acknowledgeUnknown = ref(false)
const reconcileId = ref('')
const reconcileRevision = ref<number>()
const amountInput = ref('')
const note = ref('')
const reconcileConfirmed = ref(false)
const reconcilingRefresh = ref(false)
const actionError = ref('')
const message = ref('')
const adoptionLocked = ref(false)
const detailState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let actionRequest = 0
let commandEpoch = store.sessionEpoch
let commandRequest = 0
const path = computed(() => `/ai/tasks/${props.taskId}`)
const locked = computed(() => writer.locked.value || Boolean(deferred.value) || authorizationState.locked || sourceQaChecking.value)
const blocked = computed(() => !store.sessionActive || Boolean(props.disabled) || locked.value || adoptionLocked.value || reconcilingRefresh.value)
const dirty = computed(() => Boolean(amountInput.value || note.value || retryConsent.value || acknowledgeUnknown.value || reconcileConfirmed.value || authorizationState.dirty))
const amount = computed(() => parseAiMicros(amountInput.value))
const unknownAttempts = computed(() => current.value?.attempts.filter(attempt => attempt.feeStatus === 'UNKNOWN') ?? [])
const unknownAmount = computed(() => unknownAttempts.value.reduce((sum, attempt) => sum + attempt.reservedMicros, 0))
const selectedAttempt = computed(() => current.value?.attempts.find(attempt => attempt.id === reconcileId.value))
const draftAttempt = computed(() => { const task = current.value; return task?.attempts.find(attempt => attempt.number === task.task.attemptCount) })
const reconciliationStale = computed(() => reconcileRevision.value !== current.value?.task.revision || selectedAttempt.value?.feeStatus !== 'UNKNOWN')
const retryStale = computed(() => retryRevision.value !== current.value?.task.revision)
const expressionTypeLabel = computed(() => expressionOptions.find(option => option.value === current.value?.expressionCandidate?.type)?.label ?? '表达类型待核对')
const requirementDimensionLabel = (value: string) => dimensionOptions.find(option => option.value === value)?.label ?? value
const trainingDraft = computed(() => current.value?.manifest.trainingDraft != null || current.value?.manifest.promptVersion === 'training-knowledge-draft-1')
const reviewDraft = computed(() => current.value?.task.purpose === 'KNOWLEDGE_DRAFT'
  && (current.value.manifest.reviewDraft != null || current.value.manifest.promptVersion === 'review-knowledge-draft-1'))
const cardDraft = computed(() => current.value?.task.purpose === 'MEMORY_CARD_DRAFT')
const jdRequirements = computed(() => current.value?.task.purpose === 'JD_REQUIREMENT_DRAFT')
const factDraft = computed(() => current.value?.task.purpose === 'FACT_DRAFT')
const rewrite = computed(() => current.value?.task.purpose === 'ANSWER_REWRITE')
const sourceQa = computed(() => current.value?.task.purpose === 'SOURCE_QA')
const relationEvidence = computed(() => current.value?.manifest.relationEvidence != null || current.value?.manifest.promptVersion === 'relation-evidence-draft-1')
const relationReview = computed(() => current.value?.task.purpose === 'RELATION_DRAFT'
  && (current.value.manifest.reviewDraft != null || current.value.manifest.promptVersion === 'relation-review-draft-1'))
const relationAiFeedback = computed(() => current.value?.task.purpose === 'RELATION_DRAFT'
  && (current.value.manifest.relationAiFeedback != null || current.value.manifest.promptVersion === 'relation-ai-feedback-draft-1'))
const candidateVisible = computed(() => !cardDraft.value && !jdRequirements.value && !factDraft.value && !rewrite.value && !sourceQaTask.value && current.value?.task.purpose !== 'RELATION_DRAFT'
  || store.sessionActive && !props.disabled && currentEpoch.value === store.sessionEpoch)
const factFields = [{ key: 'scenario', label: '场景' }, { key: 'problem', label: '问题' }, { key: 'action', label: '动作' }, { key: 'result', label: '结果' }, { key: 'technology', label: '技术与适用版本' }] as const
const factMaterialCitations = computed(() => current.value?.factCandidate?.materialCitations.filter(citation => aiFactDraftMaterialCitationValid(citation, current.value!.manifest)) ?? [])
const factTrainingCitations = computed(() => (current.value?.factCandidate?.trainingCitations ?? []).map(citation => {
  const input = current.value?.manifest.factDraft?.training
  const source = current.value && input && aiFactDraftTrainingCitationValid(citation, current.value.manifest)
    ? input.fragments.find(fragment => fragment.field === citation.field && fragment.elementId === citation.elementId && fragment.start <= citation.start && fragment.end >= citation.end) : undefined
  return { citation, source, before: source?.quote.slice(0, citation.start - source.start), after: source?.quote.slice(citation.end - source.start) }
}))
const hasExpressionSources = computed(() => current.value?.manifest.sources.some(source => source.kind === 'EXPRESSION') ?? false)
const candidateSourceChecksValid = computed(() => !current.value || aiKnowledgeMaterialChecksMatch(current.value.manifest) && (sourceQa.value ? aiSourceQaManifestValid(current.value.manifest)
  : rewrite.value ? aiRewriteManifestMatches(current.value.manifest)
  : factDraft.value ? aiFactDraftManifestMatches(current.value.manifest)
  : jdRequirements.value ? aiJdRequirementsManifestMatches(current.value.manifest)
  : cardDraft.value ? aiCardDraftManifestMatches(current.value.manifest)
  : reviewDraft.value ? aiReviewDraftManifestMatches(current.value.manifest)
  : current.value.task.purpose === 'ANSWER_COMPARISON' ? aiComparisonManifestMatches(current.value.manifest)
  : current.value.task.purpose === 'RELATION_DRAFT' ? aiRelationManifestMatches(current.value.manifest)
  : trainingDraft.value ? aiTrainingDraftManifestMatches(current.value.manifest) : current.value.task.purpose === 'EXPRESSION_DRAFT'
  ? aiExpressionManifestMatches(current.value.manifest) : current.value.task.purpose === 'PREPARATION_PLAN'
    ? aiPreparationPlanManifestMatches(current.value.manifest) : aiPreparationChecksMatch(current.value.manifest) && aiMaterialDraftSourcesMatch(current.value.manifest)
      && (!['KNOWLEDGE_DRAFT', 'QUESTION_DRAFT'].includes(current.value.task.purpose) || current.value.manifest.promptVersion === aiDraftPromptVersion(current.value.manifest))))
const canCancel = computed(() => Boolean(current.value && ['QUEUED', 'RUNNING', 'VALIDATING', 'UNKNOWN'].includes(current.value.task.status)))
const canRetry = computed(() => Boolean(current.value && current.value.task.purpose !== 'FOLLOW_UP' && ['FAILED', 'UNKNOWN', 'CANCELED'].includes(current.value.task.status)
  && aiKnowledgeMaterialChecksMatch(current.value.manifest)
  && (current.value.task.purpose !== 'MATERIAL_QA' || current.value.manifest.materialQa != null)
  && (!['MATERIAL_QA', 'ANSWER_EVALUATION'].includes(current.value.task.purpose) || current.value.manifest.expressionChecks == null)
  && (!['KNOWLEDGE_DRAFT', 'QUESTION_DRAFT', 'EXPRESSION_DRAFT', 'PREPARATION_PLAN', 'RELATION_DRAFT', 'ANSWER_COMPARISON', 'MEMORY_CARD_DRAFT', 'JD_REQUIREMENT_DRAFT', 'FACT_DRAFT', 'ANSWER_REWRITE', 'SOURCE_QA'].includes(current.value.task.purpose) || candidateSourceChecksValid.value)
  && comparisonFieldsMatch(current.value)
  && (current.value.task.purpose !== 'EXPRESSION_DRAFT' || !current.value.expressionDecision)
  && (current.value.task.purpose !== 'PREPARATION_PLAN' || !current.value.preparationPlanDecision)
  && (current.value.task.purpose !== 'MEMORY_CARD_DRAFT' || !current.value.cardDecision)
  && (current.value.task.purpose !== 'JD_REQUIREMENT_DRAFT' || !current.value.jdRequirementsDecision)
  && (current.value.task.purpose !== 'FACT_DRAFT' || !current.value.factDecision)
  && (current.value.task.purpose === 'RELATION_DRAFT' ? !(current.value.relationDecisions?.length)
    : current.value.relationCandidate == null && current.value.relationDecisions == null)
  && current.value.sourcesCurrent && current.value.task.attemptCount < Math.min(3, current.value.manifest.configuration.maxAttempts)
  && props.settings?.configuration.enabled && props.settings.configuration.configured))
const detail = useQuery({
  queryKey: computed(() => ['ai', store.sessionEpoch, 'task', props.taskId]),
  enabled: computed(() => store.sessionActive && !props.disabled),
  queryFn: async () => {
    const epoch = store.sessionEpoch, id = props.taskId, request = actionRequest
    let value: AiTask
    try { value = await read<AiTask>(`/ai/tasks/${id}`) }
    catch (error) {
      // 原答专用读取许可不一定触发全局资料遮挡；权威拒读后不能继续展示缓存的来源正文。
      if (sourceQaTask.value && epoch === store.sessionEpoch && id === props.taskId && request === actionRequest
        && error instanceof ApiError && (error.status === 403 || error.status === 404 || /(?:ACCESS|SOURCE|BINDING|FEEDBACK|MANIFEST)/.test(error.code))) {
        clearSourceQaBody(false)
      }
      throw error
    }
    if (!alive || !store.sessionActive || props.disabled || epoch !== store.sessionEpoch || id !== props.taskId || request !== actionRequest) {
      throw new ApiError(0, 'AI_TASK_CONTEXT_CHANGED', '任务或资料访问条件已变化，请重新读取。')
    }
    if (value?.task?.id !== id || !comparisonFieldsMatch(value)) {
      if (sourceQaTask.value) clearSourceQaBody(false)
      throw new ApiError(502, 'AI_TASK_MISMATCH', '任务用途、固定来源或结果与当前记录不一致。')
    }
    return value
  },
})

// 缓存恢复可能复用原对象；重新取得当前登录会话和任务的缓存，避免只监听 data 引用。
watch([detail.data, detail.dataUpdatedAt, () => store.sessionEpoch, () => props.taskId, () => props.disabled, () => store.sessionActive], ([, , epoch, id, disabled, active]) => {
  if (!alive || !active || disabled) return
  const value = queryClient.getQueryData<AiTask>(['ai', epoch, 'task', id])
  if (value && accept(value)) recordRecentVisit(value.task.id)
}, { immediate: true })
watch([dirty, locked], ([changed, waiting]) => {
  detailState.dirty = changed
  commandState.locked = waiting
  store.changes.set('ai-detail', detailState)
  store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch(() => current.value?.task.revision, (revision, previous) => {
  if (previous !== undefined && revision !== previous) {
    retryConsent.value = false
    acknowledgeUnknown.value = false
    reconcileConfirmed.value = false
    emit('changed')
  }
})
watch([() => current.value?.sourcesCurrent, candidateSourceChecksValid,
  () => current.value && candidateSourceChecksValid.value ? aiCandidateManifestFingerprint(current.value.manifest) : '',
  () => JSON.stringify(props.settings?.configuration)], () => {
  retryConsent.value = false
  acknowledgeUnknown.value = false
}, { flush: 'sync' })
watch([() => store.sessionEpoch, () => props.disabled, () => store.sessionActive], () => {
  actionRequest++
  if (sourceQa.value) {
    // 来源问答失权后清掉当前正文及查询数据；原键命令单独保留不可逆摘要和状态元数据。
    clearSourceQaBody()
  }
  reconcilingRefresh.value = false
  retryConsent.value = false
  acknowledgeUnknown.value = false
  reconcileConfirmed.value = false
  retryRevision.value = undefined
  reconcileRevision.value = undefined
}, { flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['REVISION_CONFLICT', 'AI_STATE_CONFLICT', 'AI_CONFIG_CHANGED', 'AI_SOURCE_CHANGED', 'AI_BUDGET_EXCEEDED'].includes(error.code)) {
    retryConsent.value = false
    reconcileConfirmed.value = false
    retryRevision.value = undefined
    reconcileRevision.value = undefined
    emit('changed')
  }
})
onUnmounted(() => {
  alive = false
  actionRequest++
  if (sourceQaTask.value) {
    clearSourceQaBody()
  }
  if (store.changes.get('ai-detail') === detailState) store.changes.delete('ai-detail')
  if (store.changes.get('ai-command') === commandState) store.changes.delete('ai-command')
})

function clearSourceQaBody(cancel = true) {
  actionRequest++
  const query = queryClient.getQueryCache().find({ queryKey: ['ai', currentEpoch.value, 'task', props.taskId], exact: true })
  current.value = undefined; manifestOpen.value = false; retryOpen.value = false; retryConsent.value = false; retryAuthorization.value = undefined
  if (!query) return
  if (cancel) { void query.cancel({ silent: true }); query.reset() }
  else query.setState({ data: undefined, dataUpdatedAt: 0 })
}
function accept(value: AiTask, commandReceipt = false): boolean {
  if (value?.task?.id !== props.taskId || !comparisonFieldsMatch(value) || (current.value && value.task.purpose !== current.value.task.purpose)) return false
  if (currentEpoch.value !== store.sessionEpoch) current.value = undefined
  sourceQaTask.value = value.task.purpose === 'SOURCE_QA'
  if (sourceQaTask.value) sourceQaManifestHash.value = value.sourceQaManifestHash
  const relationDecisions = new Map((current.value?.relationDecisions ?? []).map(item => [item.candidateId, item]))
  for (const item of value.relationDecisions ?? []) if (!relationDecisions.has(item.candidateId)) relationDecisions.set(item.candidateId, item)
  if (current.value && (value.task.revision < current.value.task.revision
    || (commandReceipt && value.task.purpose === 'RELATION_DRAFT' && value.task.revision === current.value.task.revision))) {
    // 原键回执可能早于最新查询；相同修订的来源状态也可能已变化，只补充不可逆的逐项决定。
    if (value.task.purpose !== 'RELATION_DRAFT' || relationDecisions.size === (current.value.relationDecisions?.length ?? 0)) return false
    current.value = { ...current.value, relationCandidate: current.value.relationCandidate ?? value.relationCandidate ?? null,
      relationDecisions: [...relationDecisions.values()] }
    return true
  }
  // 查询只更新服务器记录；人工账单核对输入和预期修订独立保留。
  // 旧幂等回执可缺少新增字段；已读到的终态决定不能被旧快照清空。
  const decisions = new Map((current.value?.evaluationDecisions ?? []).map(item => [item.id, item]))
  for (const item of value.evaluationDecisions ?? []) decisions.set(item.id, item)
  current.value = { ...value, decision: value.decision ?? current.value?.decision ?? null,
    questionDecision: value.questionDecision ?? current.value?.questionDecision ?? null,
    expressionCandidate: value.expressionCandidate ?? current.value?.expressionCandidate ?? null,
    expressionDecision: value.expressionDecision ?? current.value?.expressionDecision ?? null,
    preparationPlanCandidate: value.preparationPlanCandidate ?? current.value?.preparationPlanCandidate ?? null,
    preparationPlanDecision: value.preparationPlanDecision ?? current.value?.preparationPlanDecision ?? null,
    cardCandidate: value.cardCandidate ?? current.value?.cardCandidate ?? null,
    cardDecision: value.cardDecision ?? current.value?.cardDecision ?? null,
    jdRequirementsCandidate: value.jdRequirementsCandidate ?? current.value?.jdRequirementsCandidate ?? null,
    jdRequirementsDecision: value.jdRequirementsDecision ?? current.value?.jdRequirementsDecision ?? null,
    factCandidate: value.factCandidate ?? current.value?.factCandidate ?? null,
    factDecision: value.factDecision ?? current.value?.factDecision ?? null,
    rewrite: value.rewrite ?? current.value?.rewrite ?? null,
    rewriteCandidate: value.rewriteCandidate ?? current.value?.rewriteCandidate ?? null,
    relationCandidate: value.relationCandidate ?? current.value?.relationCandidate ?? null,
    relationDecisions: value.task.purpose === 'RELATION_DRAFT' ? [...relationDecisions.values()]
      : value.relationDecisions ?? current.value?.relationDecisions ?? null,
    evaluationDecisions: ['RELATION_DRAFT', 'ANSWER_COMPARISON', 'MEMORY_CARD_DRAFT', 'JD_REQUIREMENT_DRAFT', 'FACT_DRAFT', 'ANSWER_REWRITE', 'SOURCE_QA'].includes(value.task.purpose) ? null : [...decisions.values()], materialAnswer: value.materialAnswer ?? current.value?.materialAnswer ?? null }
  currentEpoch.value = store.sessionEpoch
  return true
}
function comparisonFieldsMatch(value: AiTask): boolean {
  if (!value?.task || !value.manifest || value.task.purpose !== value.manifest.purpose || !aiReviewDraftTaskValid(value)
    || !aiCardDraftTaskValid(value) || !aiJdRequirementsTaskValid(value) || !aiFactDraftTaskValid(value) || !aiRewriteTaskMatches(value) || !aiSourceQaTaskValid(value)
    || !aiKnowledgeMaterialChecksMatch(value.manifest)
    || ['FOLLOW_UP', 'ANSWER_EVALUATION', 'MATERIAL_QA'].includes(value.task.purpose) && value.manifest.knowledgeMaterialChecks != null) return false
  if (sourceQaTask.value && (value.task.purpose !== 'SOURCE_QA' || value.sourceQaManifestHash !== sourceQaManifestHash.value)) return false
  const saved = currentEpoch.value === store.sessionEpoch ? current.value : undefined
  if (saved?.task.id === value.task.id && knowledgeMaterialChecksFingerprint(saved.manifest) !== knowledgeMaterialChecksFingerprint(value.manifest)) return false
  if ((value.task.purpose === 'SOURCE_QA' || saved?.task.purpose === 'SOURCE_QA') && saved?.task.id === value.task.id
    && (saved.task.purpose !== value.task.purpose || saved.sourceQaManifestHash !== value.sourceQaManifestHash
      || aiCandidateManifestFingerprint(saved.manifest) !== aiCandidateManifestFingerprint(value.manifest)
      || saved.sourceQaAnswer != null && JSON.stringify(saved.sourceQaAnswer) !== JSON.stringify(value.sourceQaAnswer))) return false
  if (value.task.purpose === 'RELATION_DRAFT') {
    if (!aiRelationTaskMatches(value)) return false
    if (saved?.task.id === value.task.id) {
      if (saved.task.purpose !== 'RELATION_DRAFT'
        || aiCandidateManifestFingerprint(saved.manifest) !== aiCandidateManifestFingerprint(value.manifest)
        || saved.relationCandidate != null && value.relationCandidate != null
          && aiRelationCandidateFingerprint(saved.relationCandidate) !== aiRelationCandidateFingerprint(value.relationCandidate)
        || saved.relationCandidate != null && value.relationCandidate == null && value.task.revision >= saved.task.revision) return false
      const returned = new Map((value.relationDecisions ?? []).map(item => [item.candidateId, item]))
      // 决定终态不可改写；较旧回执可缺少后来条目，新查询不能让已读决定消失。
      for (const previous of saved.relationDecisions ?? []) {
        const next = returned.get(previous.candidateId)
        if (next ? aiRelationDecisionFingerprint(previous) !== aiRelationDecisionFingerprint(next)
          : value.task.revision >= saved.task.revision) return false
      }
    }
  } else if (value.relationCandidate != null || value.relationDecisions != null || saved?.task.id === value.task.id && saved.task.purpose === 'RELATION_DRAFT') return false
  if ((value.task.purpose === 'ANSWER_REWRITE' || saved?.task.purpose === 'ANSWER_REWRITE') && saved?.task.id === value.task.id
    && (aiCandidateManifestFingerprint(saved.manifest) !== aiCandidateManifestFingerprint(value.manifest)
      || JSON.stringify(saved.rewrite) !== JSON.stringify(value.rewrite)
      || saved.rewriteCandidate != null && JSON.stringify(saved.rewriteCandidate) !== JSON.stringify(value.rewriteCandidate))) return false
  if ((value.task.purpose === 'MEMORY_CARD_DRAFT' || saved?.task.purpose === 'MEMORY_CARD_DRAFT') && saved?.task.id === value.task.id
    && (aiCandidateManifestFingerprint(saved.manifest) !== aiCandidateManifestFingerprint(value.manifest)
      || saved.cardCandidate != null && JSON.stringify(saved.cardCandidate) !== JSON.stringify(value.cardCandidate)
      || saved.cardDecision != null && JSON.stringify(saved.cardDecision) !== JSON.stringify(value.cardDecision))) return false
  if ((value.task.purpose === 'JD_REQUIREMENT_DRAFT' || saved?.task.purpose === 'JD_REQUIREMENT_DRAFT') && saved?.task.id === value.task.id
    && (aiCandidateManifestFingerprint(saved.manifest) !== aiCandidateManifestFingerprint(value.manifest)
      || saved.jdRequirementsCandidate != null && JSON.stringify(saved.jdRequirementsCandidate) !== JSON.stringify(value.jdRequirementsCandidate)
      || saved.jdRequirementsDecision != null && JSON.stringify(saved.jdRequirementsDecision) !== JSON.stringify(value.jdRequirementsDecision))) return false
  if ((value.task.purpose === 'FACT_DRAFT' || saved?.task.purpose === 'FACT_DRAFT') && saved?.task.id === value.task.id
    && (aiCandidateManifestFingerprint(saved.manifest) !== aiCandidateManifestFingerprint(value.manifest)
      || saved.factCandidate != null && JSON.stringify(saved.factCandidate) !== JSON.stringify(value.factCandidate)
      || saved.factDecision != null && JSON.stringify(saved.factDecision) !== JSON.stringify(value.factDecision))) return false
  // 同一任务的固定复盘来源不能被后续读取降为普通整理或换成另一份清单。
  if ((value.manifest.reviewDraft != null || value.manifest.promptVersion === 'review-knowledge-draft-1'
      || saved?.manifest.reviewDraft != null || saved?.manifest.promptVersion === 'review-knowledge-draft-1') && saved?.task.id === value.task.id
    && aiCandidateManifestFingerprint(saved.manifest) !== aiCandidateManifestFingerprint(value.manifest)) return false
  if (value.task.purpose !== 'ANSWER_COMPARISON') return value.comparison == null && value.comparisonCandidate == null
    && value.manifest.comparisonInput == null && value.manifest.comparisonChecks == null
  if (!aiComparisonTaskValid(value)) return false
  return !saved || saved.task.id !== value.task.id || (saved.comparison?.comparisonId === value.comparison?.comparisonId
    && aiCandidateManifestFingerprint(saved.manifest) === aiCandidateManifestFingerprint(value.manifest))
}
function receive(value: AiTask, notice: string, verifiedCommandReceipt = false) {
  if (!verifiedCommandReceipt && !comparisonFieldsMatch(value)) { writer.error.value = new ApiError(502, 'AI_TASK_MISMATCH', '任务用途、固定来源或结果不一致，请重新读取记录。'); return }
  // 已按发出时快照核对的原键回执只结束该命令；较新查询中的候选、决定和当前来源状态继续保留。
  const preserveCurrent = verifiedCommandReceipt && currentEpoch.value === store.sessionEpoch && current.value?.task.id === value.task.id
    && current.value.task.revision >= value.task.revision
  if (!preserveCurrent) accept(value, true)
  message.value = notice
  queryClient.setQueryData(['ai', store.sessionEpoch, 'task', props.taskId], current.value)
  emit('changed')
  void detail.refetch()
}
function refreshCandidate(value: AiTask) {
  if (!accept(value)) return
  queryClient.setQueryData(['ai', store.sessionEpoch, 'task', props.taskId], current.value)
  emit('changed')
}
async function refreshTask(): Promise<AiTask | undefined> {
  if (!alive || !store.sessionActive || props.disabled) return
  const epoch = store.sessionEpoch
  const taskId = props.taskId
  const queryKey = ['ai', epoch, 'task', taskId]
  // 与手动刷新、SSE 共用查询；显式刷新取消旧读取，候选不再直接回填独立 GET 的结果。
  const result = await detail.refetch({ cancelRefetch: true, throwOnError: true })
  if (!alive || !store.sessionActive || props.disabled || epoch !== store.sessionEpoch || taskId !== props.taskId) return
  const latest = queryClient.getQueryState<AiTask>(queryKey)
  if (!result.data || !latest || latest.fetchStatus !== 'idle' || latest.status !== 'success' || latest.data !== result.data) return
  if (!accept(result.data)) return
  return current.value
}
function receiveDecision(value: AiTask) {
  if (value.task.purpose === 'FACT_DRAFT') { receive(value, value.factDecision?.status === 'ADOPTED' ? '已创建新的待核实事实，责任边界仍待核实' : '事实候选的忽略决定已保存', true); return }
  if (value.task.purpose === 'JD_REQUIREMENT_DRAFT') { receive(value, value.jdRequirementsDecision?.status === 'ADOPTED' ? '选定要求已追加为目标新版本，原 JD 与既有要求保留' : 'JD 要求候选的忽略决定已保存'); return }
  if (value.task.purpose === 'MEMORY_CARD_DRAFT') { receive(value, value.cardDecision?.status === 'ADOPTED' ? '回忆卡及首个版本已保存，排程尚未启用' : '回忆卡候选的忽略决定已保存'); return }
  if (value.task.purpose === 'ANSWER_EVALUATION') { receive(value, '本项用户决定已追加保存，原报告及历史决定保留'); return }
  if (value.task.purpose === 'RELATION_DRAFT') { receive(value, '关系候选的逐项决定已保存，原候选和其他条目继续保留'); return }
  const decision = value.task.purpose === 'QUESTION_DRAFT' ? value.questionDecision
    : value.task.purpose === 'EXPRESSION_DRAFT' ? value.expressionDecision
      : value.task.purpose === 'PREPARATION_PLAN' ? value.preparationPlanDecision : value.decision
  const notice = value.task.purpose === 'QUESTION_DRAFT' ? '采用决定与题库首版已保存'
    : value.task.purpose === 'EXPRESSION_DRAFT' ? '采用决定、表达版本与事实依据已保存'
      : value.task.purpose === 'PREPARATION_PLAN' ? '采用决定与最终安排已保存，对应普通准备任务已创建' : '采用决定与知识发布版本已保存'
  receive(value, decision?.status === 'ADOPTED' ? notice : '忽略决定已保存')
}
function candidateCommandReceiptMatches(value: AiTask, issued: AiTask, target: string, body: unknown): boolean {
  try {
    const request = body as { expectedRevision?: unknown; amountMicros?: unknown }
    if (!aiCardDraftTaskValid(value) || !aiJdRequirementsTaskValid(value) || !aiFactDraftTaskValid(value) || !aiRewriteTaskMatches(value)
      || !aiKnowledgeMaterialChecksMatch(value.manifest) || value.task.id !== issued.task.id || value.task.purpose !== issued.task.purpose
      || !['MEMORY_CARD_DRAFT', 'JD_REQUIREMENT_DRAFT', 'FACT_DRAFT', 'ANSWER_REWRITE', 'RELATION_DRAFT'].includes(issued.task.purpose)
      || request.expectedRevision !== issued.task.revision || value.task.revision !== issued.task.revision + 1
      || aiCandidateManifestFingerprint(value.manifest) !== aiCandidateManifestFingerprint(issued.manifest)
      || JSON.stringify(value.rewrite ?? null) !== JSON.stringify(issued.rewrite ?? null)) return false
    // 任务级命令不能改写逐项候选与决定；直接证据也必须与发出时的完整清单一致。
    if (issued.task.purpose === 'RELATION_DRAFT' && (!aiRelationTaskMatches(value)
      || aiRelationCandidateFingerprint(value.relationCandidate) !== aiRelationCandidateFingerprint(issued.relationCandidate)
      || JSON.stringify(value.relationDecisions?.map(aiRelationDecisionFingerprint)) !== JSON.stringify(issued.relationDecisions?.map(aiRelationDecisionFingerprint)))) return false
    const basePath = `/ai/tasks/${issued.task.id}`
    const sameAttempts = issued.attempts.every(before => value.attempts.some(after => after.id === before.id && after.number === before.number))
    if (!sameAttempts) return false
    if (target === `${basePath}/retry`) {
      const next = value.attempts.filter(attempt => !issued.attempts.some(before => before.id === attempt.id))
      return ['FAILED', 'UNKNOWN', 'CANCELED'].includes(issued.task.status) && value.task.status === 'QUEUED'
        && value.task.attemptCount === issued.task.attemptCount + 1 && next.length === 1
        && next[0]!.number === value.task.attemptCount && next[0]!.status === 'QUEUED' && next[0]!.feeStatus === 'RESERVED'
    }
    if (value.task.attemptCount !== issued.task.attemptCount) return false
    if (target === `${basePath}/cancel`) {
      const expectedStatus = issued.task.status === 'QUEUED' || issued.task.status === 'UNKNOWN' ? 'CANCELED'
        : ['RUNNING', 'VALIDATING'].includes(issued.task.status) ? 'CANCEL_REQUESTED' : undefined
      return expectedStatus !== undefined && value.task.status === expectedStatus
        && value.attempts.some(attempt => attempt.number === issued.task.attemptCount && attempt.status === expectedStatus)
    }
    const attempt = issued.attempts.find(item => target === `${basePath}/attempts/${item.id}/reconcile`)
    const recorded = attempt && value.attempts.find(item => item.id === attempt.id)
    return Boolean(attempt && attempt.feeStatus === 'UNKNOWN' && recorded?.feeStatus === 'RECONCILED'
      && recorded.accountedMicros === request.amountMicros && value.task.status === issued.task.status
      && JSON.stringify(value.cardCandidate) === JSON.stringify(issued.cardCandidate)
      && JSON.stringify(value.cardDecision) === JSON.stringify(issued.cardDecision)
      && JSON.stringify(value.jdRequirementsCandidate) === JSON.stringify(issued.jdRequirementsCandidate)
      && JSON.stringify(value.jdRequirementsDecision) === JSON.stringify(issued.jdRequirementsDecision)
      && JSON.stringify(value.factCandidate) === JSON.stringify(issued.factCandidate)
      && JSON.stringify(value.factDecision) === JSON.stringify(issued.factDecision)
      && JSON.stringify(value.rewriteCandidate ?? null) === JSON.stringify(issued.rewriteCandidate ?? null))
  } catch { return false }
}
function knowledgeMaterialChecksFingerprint(manifest: AiTask['manifest']): string {
  // 原键回执比较发出时的元数据；历史缺省与 null 等价，不套用新预览的必填要求。
  return JSON.stringify(manifest.knowledgeMaterialChecks?.map(check => [check.excerptId, check.revision]) ?? null)
}
function run(target: string, body: unknown, apply: (value: AiTask, verifiedCommandReceipt: boolean) => void) {
  if (blocked.value) return
  if (sourceQa.value) { void runSourceQa(target, body, apply); return }
  commandEpoch = store.sessionEpoch
  commandRequest = actionRequest
  message.value = ''
  actionError.value = ''
  const specializedCommand = cardDraft.value || jdRequirements.value || factDraft.value || rewrite.value || current.value?.task.purpose === 'RELATION_DRAFT'
  const issued = specializedCommand && current.value ? JSON.parse(JSON.stringify(current.value)) as AiTask : undefined
  const issuedKnowledgeMaterialChecks = current.value ? knowledgeMaterialChecksFingerprint(current.value.manifest) : undefined
  let original: typeof writer.pending.value
  void writer.run<AiTask>(target, 'POST', body, value => {
    if (!alive) return
    // 回执校验必须在命令回调内完成，延后接收时也能由命令层保留原幂等键。
    const matches = issued ? candidateCommandReceiptMatches(value, issued, target, body) : comparisonFieldsMatch(value)
    if (value?.task?.id !== props.taskId || !matches || knowledgeMaterialChecksFingerprint(value.manifest) !== issuedKnowledgeMaterialChecks) {
      if (specializedCommand) writer.pending.value = original
      throw new ApiError(502, 'AI_TASK_MISMATCH', '任务回执的用途、固定来源或结果与当前任务不一致，原请求继续保留，请用原请求重试。')
    }
    const receiveValue = () => apply(value, Boolean(issued))
    if (commandEpoch !== store.sessionEpoch || commandRequest !== actionRequest || !store.sessionActive || props.disabled) deferred.value = receiveValue
    else receiveValue()
  })
  original = writer.pending.value
}
async function digest(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
  return [...new Uint8Array(bytes)].map(byte => byte.toString(16).padStart(2, '0')).join('')
}
async function sourceQaCommandSnapshot(value: AiTask) {
  return { id: value.task.id, revision: value.task.revision, status: value.task.status, attemptCount: value.task.attemptCount,
    serverHash: value.sourceQaManifestHash,
    manifestHash: await digest(aiCandidateManifestFingerprint(value.manifest)), answerHash: await digest(JSON.stringify(value.sourceQaAnswer ?? null)),
    attempts: value.attempts.map(attempt => ({ id: attempt.id, number: attempt.number, feeStatus: attempt.feeStatus })) }
}
async function runSourceQa(target: string, body: unknown, apply: (value: AiTask, verified: boolean) => void) {
  if (blocked.value || !current.value || !aiSourceQaTaskValid(current.value)) return
  const epoch = store.sessionEpoch, generation = actionRequest
  sourceQaChecking.value = true; actionError.value = ''; message.value = ''
  try {
    const issued = await sourceQaCommandSnapshot(current.value)
    if (!alive || !store.sessionActive || props.disabled || epoch !== store.sessionEpoch || generation !== actionRequest || current.value?.task.revision !== issued.revision) return
    commandEpoch = epoch; commandRequest = generation
    // 闭包只持有不可逆摘要、修订和尝试标识；权限变化后不延后缓存整份任务。
    await writer.run<AiTask>(target, 'POST', body, async value => {
      sourceQaChecking.value = true
      try {
        const request = body as { expectedRevision?: number; amountMicros?: number }
        const accessible = () => alive && store.sessionActive && !props.disabled && commandEpoch === store.sessionEpoch && commandRequest === actionRequest
        if (!accessible()) throw new ApiError(0, 'AI_TASK_CONTEXT_CHANGED', '访问条件变化，任务正文未接收；恢复后请用原请求核对。')
        if (!aiSourceQaTaskValid(value) || value.task.purpose !== 'SOURCE_QA' || value.task.id !== issued.id
          || value.sourceQaManifestHash !== issued.serverHash || request.expectedRevision !== issued.revision || value.task.revision !== issued.revision + 1
          || await digest(aiCandidateManifestFingerprint(value.manifest)) !== issued.manifestHash
          || await digest(JSON.stringify(value.sourceQaAnswer ?? null)) !== issued.answerHash
          || !issued.attempts.every(before => value.attempts.some(after => after.id === before.id && after.number === before.number))) {
          throw new ApiError(502, 'AI_TASK_MISMATCH', '来源问答回执与原任务的固定清单、回答或修订不一致，原请求继续保留。')
        }
        const base = `/ai/tasks/${issued.id}`
        let matches = false
        if (target === `${base}/retry`) {
          const added = value.attempts.filter(attempt => !issued.attempts.some(before => before.id === attempt.id))
          matches = ['FAILED', 'UNKNOWN', 'CANCELED'].includes(issued.status) && value.task.status === 'QUEUED'
            && value.task.attemptCount === issued.attemptCount + 1 && added.length === 1 && added[0]!.number === value.task.attemptCount
            && added[0]!.status === 'QUEUED' && added[0]!.feeStatus === 'RESERVED'
        } else if (value.task.attemptCount === issued.attemptCount) {
          if (target === `${base}/cancel`) {
            const status = ['QUEUED', 'UNKNOWN'].includes(issued.status) ? 'CANCELED' : ['RUNNING', 'VALIDATING'].includes(issued.status) ? 'CANCEL_REQUESTED' : undefined
            matches = status !== undefined && value.task.status === status && value.attempts.some(attempt => attempt.number === issued.attemptCount && attempt.status === status)
          } else {
            const before = issued.attempts.find(attempt => target === `${base}/attempts/${attempt.id}/reconcile`)
            const after = before && value.attempts.find(attempt => attempt.id === before.id)
            matches = before?.feeStatus === 'UNKNOWN' && after?.feeStatus === 'RECONCILED' && after.accountedMicros === request.amountMicros && value.task.status === issued.status
          }
        }
        if (!matches) throw new ApiError(502, 'AI_TASK_MISMATCH', '任务命令回执状态不一致，请用原请求核对。')
        if (!accessible()) throw new ApiError(0, 'AI_TASK_CONTEXT_CHANGED', '核对期间访问条件变化，未接收任务正文；恢复后请用原请求核对。')
        apply(value, true)
      } finally { sourceQaChecking.value = false }
    })
  } catch (error) { if (alive) actionError.value = errorText(error) }
  finally { sourceQaChecking.value = false }
}
function retryCommand() { if (store.sessionActive && !props.disabled && !adoptionLocked.value && !sourceQaChecking.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandRequest = actionRequest; void writer.retry() } }
function receiveDeferred() { if (store.sessionActive && !props.disabled && !adoptionLocked.value && deferred.value) { const apply = deferred.value; deferred.value = undefined; apply() } }
function cancel() {
  if (blocked.value || !current.value || !canCancel.value) return
  if (!window.confirm('确认取消这项任务？取消会阻止后续发送或接纳结果，但已可能发送的调用仍可能被供应商计费，未知费用预留继续保留。')) return
  run(`${path.value}/cancel`, { expectedRevision: current.value.task.revision }, (value, cardReceipt) => receive(value, '服务器已记录取消命令，请继续留意执行状态与费用', cardReceipt))
}
function openRetry() {
  if (blocked.value || !current.value || !canRetry.value) return
  retryOpen.value = true
  retryRevision.value = current.value.task.revision
  retryConsent.value = false
  acknowledgeUnknown.value = false
}
function retryTask() {
  if (blocked.value || !current.value || !canRetry.value || retryStale.value || !retryConsent.value || (unknownAttempts.value.length && !acknowledgeUnknown.value)) return
  run(`${path.value}/retry`, { expectedRevision: retryRevision.value, consentConfirmed: true, acknowledgeUnknownCost: acknowledgeUnknown.value, ...aiAuthorizationRequestFields(retryAuthorization.value) }, (value, cardReceipt) => {
    retryOpen.value = false
    retryConsent.value = false
    acknowledgeUnknown.value = false
    receive(value, '新的一次尝试已获授权；既有未知费用继续保留', cardReceipt)
  })
}
function openReconciliation(attempt: AiAttempt) {
  if (blocked.value || !current.value || attempt.feeStatus !== 'UNKNOWN') return
  if (reconcileId.value !== attempt.id && (amountInput.value || note.value) && !window.confirm('切换核对对象会丢弃当前未保存金额和说明，继续？')) return
  if (reconcileId.value !== attempt.id) { amountInput.value = ''; note.value = '' }
  reconcileId.value = attempt.id
  reconcileRevision.value = current.value.task.revision
  reconcileConfirmed.value = false
  actionError.value = ''
}
function closeReconciliation() {
  if (blocked.value || ((amountInput.value || note.value) && !window.confirm('关闭核对表单并丢弃未保存金额和说明？'))) return
  reconcileId.value = ''
  amountInput.value = ''
  note.value = ''
  reconcileConfirmed.value = false
}
async function reloadForAction() {
  if (blocked.value) return
  const epoch = store.sessionEpoch
  const request = ++actionRequest
  reconcilingRefresh.value = true
  actionError.value = ''
  retryConsent.value = false
  acknowledgeUnknown.value = false
  reconcileConfirmed.value = false
  try {
    const value = await refreshTask()
    if (!alive || props.disabled || !store.sessionActive || request !== actionRequest || epoch !== store.sessionEpoch) return
    if (!value) { actionError.value = '任务状态已继续变化，请再次核对服务器状态。'; return }
    if (retryOpen.value) retryRevision.value = value.task.revision
    if (reconcileId.value) reconcileRevision.value = value.task.revision
    emit('changed')
  } catch (error) { if (alive && request === actionRequest && epoch === store.sessionEpoch) actionError.value = errorText(error) }
  finally { if (alive && request === actionRequest) reconcilingRefresh.value = false }
}
function reconcile() {
  if (blocked.value || !selectedAttempt.value || selectedAttempt.value.feeStatus !== 'UNKNOWN' || reconciliationStale.value || amount.value === null || !note.value.trim() || note.value.length > 1000 || !reconcileConfirmed.value) return
  run(`${path.value}/attempts/${selectedAttempt.value.id}/reconcile`, { expectedRevision: reconcileRevision.value, amountMicros: amount.value, note: note.value }, (value, cardReceipt) => {
    reconcileId.value = ''
    amountInput.value = ''
    note.value = ''
    reconcileConfirmed.value = false
    receive(value, '已保存用户账单核对记录；该操作不会恢复丢失的模型结果', cardReceipt)
  })
}
function sourceFor(citation: AiCitation) {
  if (current.value?.task.purpose === 'JD_REQUIREMENT_DRAFT' && !aiJdRequirementsCitationValid(citation, current.value.manifest)) return undefined
  if (current.value?.task.purpose === 'MEMORY_CARD_DRAFT' && !aiCardDraftCitationValid(citation, current.value.manifest)) return undefined
  const matches = current.value?.manifest.sources.filter(source => source.itemId === citation.itemId && source.versionId === citation.versionId) ?? []
  // 引用未带领域类型，只有清单中唯一匹配的来源才能决定跳转，不能默认当作知识。
  return matches.length === 1 ? matches[0] : undefined
}
function routeFor(citation: AiCitation) { return aiSourceRoute(sourceFor(citation)) }
</script>

<template>
  <p v-if="!candidateVisible" class="notice" role="status">候选资料暂不可读。恢复资料访问并重新核对任务后，可继续原改稿或未决请求。</p>
  <section v-show="candidateVisible" class="ai-detail" aria-label="AI 任务详情" :inert="!candidateVisible || undefined">
    <p v-if="sourceQaTask && !current && writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="!store.sessionActive || disabled || sourceQaChecking" :loading="writer.busy.value" @click="retryCommand">用原请求核对</NButton></p>
    <p v-if="!current && detail.isPending.value" class="loading-block" role="status">正在读取任务记录…</p><p v-if="detail.error.value" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text :disabled="blocked" @click="detail.refetch()">重新读取</NButton></p>
    <template v-if="current">
      <header class="detail-heading"><div><p class="section-label">{{ aiPurposeLabels[current.task.purpose] }} · {{ aiTaskStatusLabel(current.task.status, current.task.purpose) }}</p><h2>{{ current.task.title }}</h2><p class="field-help">{{ current.task.provider }} · {{ current.task.model }} · 创建于 {{ aiDate(current.task.createdAt) }}</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
      <AiDraftPreview :task-id="taskId" :attempt="draftAttempt" :task-status="current.task.status" :streaming="current.manifest.configuration.streaming" :disabled="disabled || !candidateVisible || currentEpoch !== store.sessionEpoch || current.task.id !== taskId" @refresh="detail.refetch()" />
      <p v-if="aiPendingStatuses.includes(current.task.status)" class="notice" role="status">{{ current.task.status === 'CANCEL_REQUESTED' ? '取消已请求，等待服务器记录最终状态。已发请求仍可能产生费用。' : '任务状态会自动更新，重新进入页面后可继续查看。' }}</p><p v-if="current.task.status === 'UNKNOWN'" class="notice">服务器无法确认这次调用的完整结果。请先检查尝试记录与账单；重新尝试会新增一次授权和费用预留。</p><p v-if="current.task.errorCode" class="field-help">任务状态说明：{{ current.task.errorCode }}</p><p v-if="(current.task.purpose === 'KNOWLEDGE_DRAFT' || current.task.purpose === 'QUESTION_DRAFT') && !current.sourcesCurrent" class="notice">{{ trainingDraft ? '当时固定的训练题面、所选原答片段或评价反馈当前不可用，不能采用或再次授权。原候选、人工改写与费用继续保留，仍可忽略候选。追加新答案本身不会替换历史来源。' : '知识、准备或资料来源当前不可用，或原授权已失效。这里保留当时的版本与摘录；需要新任务时请重新选择当前来源和预览。候选改写与费用记录继续保留。' }}</p><p v-if="current.task.purpose === 'ANSWER_EVALUATION' && !current.sourcesCurrent" class="notice">这份评价的确切输入当前不可用。请回到绑定提交核对记录，当前不能授权再次尝试。</p>
      <p v-if="current.task.purpose === 'EXPRESSION_DRAFT' && !current.sourcesCurrent" class="notice">本次生成使用的事实或所属项目已变化，当前不能采用或重新授权。候选与本地改写继续保留，仍可忽略；需要新候选时请重新选择当前已确认的事实。</p>
      <p v-if="current.task.purpose === 'PREPARATION_PLAN' && !current.sourcesCurrent" class="notice">本次准备建议使用的目标或授权条件当前已变化，不能采用或授权再次尝试。原建议、人工改写及费用记录继续保留，也可以忽略候选。</p>
      <p v-if="current.task.purpose === 'RELATION_DRAFT' && !current.sourcesCurrent" class="notice">{{ relationAiFeedback ? '本次关系建议的知识版本、原 AI 反馈、当时决定或访问条件当前无法全部核对，不能采用或授权再次尝试。原候选、完整限定、已选片段和人工改写继续保留，仍可逐项忽略。' : relationReview ? '本次关系建议的知识版本、反馈修订与决定或回忆版本及其使用条件当前无法全部核对，不能采用或授权再次尝试。原候选、固定限制与片段、人工改写继续保留，仍可逐项忽略。' : relationEvidence ? '本次关系建议的知识版本或所选资料／训练依据当前不可用，不能采用或授权再次尝试。原候选、确切来源和人工改写保留，仍可逐项忽略。' : '本次关系建议使用的知识版本当前已变化或归档，不能采用或授权再次尝试。原候选和人工改写保留，仍可逐项忽略。' }}</p>
      <p v-if="cardDraft && !current.sourcesCurrent" class="notice">制卡使用的知识来源已有改版、归档或不可用，当前不能采用或再次授权。原候选、人工改稿与费用记录保留，仍可忽略候选。</p>
      <p v-if="cardDraft && !candidateSourceChecksValid" class="notice">卡片类型、专用用途或完整知识清单不一致，当前不能授权新的尝试。请重新核对任务。</p>
      <p v-if="jdRequirements && !current.sourcesCurrent && !current.jdRequirementsDecision" class="notice">目标 JD 版本或来源条件已变化，当前不能采用或重新授权。原候选、人工改稿与费用记录保留，仍可忽略候选。</p>
      <p v-if="jdRequirements && !candidateSourceChecksValid" class="notice">JD 专用用途、目标版本或来源核对记录不一致，当前不能授权新的尝试。请重新核对任务。</p>
      <p v-if="factDraft && !current.sourcesCurrent && !current.factDecision" class="notice">所选资料或训练片段当前不可用，不能采用或重新授权。原候选、人工改稿与费用记录保留，仍可忽略。</p>
      <p v-if="factDraft && !candidateSourceChecksValid" class="notice">事实整理用途、输入模式或确切来源不一致，当前不能授权新的尝试。请重新核对任务。</p>
      <p v-if="current.task.purpose === 'ANSWER_COMPARISON' && !current.sourcesCurrent" class="notice">所选来源的归属、验证条件、动作修订或训练授权已变化，当前不能再次发送。原比较候选与费用记录保留，回到对照核对后可重新预览。</p>
      <p v-if="['KNOWLEDGE_DRAFT', 'QUESTION_DRAFT', 'EXPRESSION_DRAFT', 'PREPARATION_PLAN', 'RELATION_DRAFT'].includes(current.task.purpose) && !candidateSourceChecksValid" class="notice">这份清单的授权用途、来源身份、表达依据、准备条件、确切摘录、训练或复盘片段不完整，暂不能重新授权。请刷新任务后核对完整清单。</p>
      <p v-if="current.task.purpose === 'ANSWER_COMPARISON' && !candidateSourceChecksValid" class="notice">比较清单的双端提交、固定验证条件或本地授权核对不完整，不能重新授权。请返回对照重新核对来源。</p>
      <p v-if="current.task.purpose === 'MATERIAL_QA' && !current.sourcesCurrent" class="notice">这次问答的资料来源或使用条件当前不可用，不能授权再次尝试。当时的清单、回答和费用记录继续保留；需要新问答时请重新选择模式与来源并预览。</p>
      <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!store.sessionActive || !!disabled" @click="retryCommand">用原请求重试</NButton><NButton v-else text :disabled="blocked" @click="reloadForAction">核对最新状态</NButton></p><p v-if="deferred" class="notice" role="status">已有服务器确认的命令结果在登录或资料条件变化后到达。<NButton text :disabled="!store.sessionActive || !!disabled" @click="receiveDeferred">接收已确认结果</NButton></p><p v-if="actionError" class="notice error" role="alert">{{ actionError }}</p>
      <div class="row-actions"><NButton :disabled="blocked" @click="detail.refetch()">刷新任务</NButton><NButton v-if="canCancel" :disabled="blocked" @click="cancel">取消任务</NButton><NButton v-if="canRetry" :disabled="blocked" @click="openRetry">检查并授权再次尝试</NButton></div>

      <section v-if="rewrite && current.rewrite" class="evaluation-link"><h3>改写绑定的确切提交</h3><p class="field-help">本任务保留第 {{ current.rewrite.answerNumber }} 版原答与独立的改写参考。生成完成不会创建新答案、复练判断或完成记录。</p><div class="row-actions"><RouterLink class="text-link" :to="{ name: 'review', query: { sessionId: current.rewrite.sessionId, questionId: current.rewrite.questionId, answerId: current.rewrite.answerId } }">回到这次提交的复盘</RouterLink><RouterLink class="text-link" :to="{ name: 'training', query: { item: current.rewrite.sessionId, question: current.rewrite.questionId, answer: current.rewrite.answerId } }">查看原始提交与结构图</RouterLink></div><p v-if="!current.sourcesCurrent" class="notice" role="status">当时的确切输入当前不可用，旧改写保留，不能继续授权发送。</p></section>
      <section v-else-if="current.task.purpose === 'ANSWER_COMPARISON' && current.comparison" class="evaluation-link"><h3>绑定的双答案对照</h3><p class="field-help">本任务为选中的 {{ current.comparison.sourceIds.length }} 条来源提供未审建议。返回对照核对旧答、新答和当前来源后，可明确填入一条判断草稿，最后人工确认保存。</p><RouterLink class="text-link" :to="{ name: 'repractice', query: { comparison: current.comparison.comparisonId } }">返回双答案对照与逐来源判断</RouterLink></section>
      <section v-else-if="current.task.purpose === 'FOLLOW_UP'" class="follow-up-link"><h3>训练内追问</h3><p class="field-help">这项任务绑定一次确切提交。成功后题目已经加入训练；重新授权需返回原训练核对当时条件。</p><RouterLink v-if="current.followUp" class="text-link" :to="{ name: 'training', query: { item: current.followUp.sessionId, question: current.followUp.questionId, followUp: current.task.id } }">返回绑定回答与追问记录</RouterLink></section>
      <section v-else-if="current.task.purpose === 'ANSWER_EVALUATION' && current.evaluation" class="evaluation-link">
        <h3>绑定的确切提交</h3><p class="field-help">本任务只评价第 {{ current.evaluation.answerNumber }} 版提交，重新评价会保留另一份报告。后续答案修订不替换本任务输入。</p><RouterLink class="text-link" :to="{ name: 'training', query: { item: current.evaluation.sessionId, question: current.evaluation.questionId, answer: current.evaluation.answerId } }">返回第 {{ current.evaluation.answerNumber }} 版回答</RouterLink>
        <template v-if="current.task.status === 'SUCCEEDED' && current.evaluationReport"><div class="row-actions"><RouterLink v-if="!blocked && current.sourcesCurrent" class="text-link" :to="{ name: 'ai', query: { create: '1', purpose: 'TRAINING_DRAFT', sessionId: current.evaluation.sessionId, questionId: current.evaluation.questionId, answerId: current.evaluation.answerId, evaluationTaskId: current.task.id } }">从这次回答整理为知识</RouterLink><NButton v-else size="small" disabled>从这次回答整理为知识</NButton></div><p class="field-help">先明确选择回答片段与可选反馈，再预览和授权生成知识候选。此入口只打开整理表单，原报告及反馈决定继续保留。</p></template>
      </section>
      <section class="attempts-panel"><h3>每次尝试与费用</h3><p class="field-help">费用与任务结果独立。用量估算和用户账单核对均保留来源说明；失败或取消不自动等于免费。</p><article v-for="attempt in current.attempts" :key="attempt.id" class="attempt-card"><header><h4>第 {{ attempt.number }} 次尝试 · {{ aiTaskStatusLabel(attempt.status, current.task.purpose) }}</h4><span :class="['fee-status', { unknown: attempt.feeStatus === 'UNKNOWN' }]">{{ aiFeeLabels[attempt.feeStatus] }}</span></header><dl><div><dt>当次预留</dt><dd>{{ formatAiAmount(attempt.reservedMicros, current.task.currency) }}</dd></div><div><dt>{{ attempt.feeStatus === 'RECONCILED' ? '用户核对金额' : '已报告用量估算' }}</dt><dd>{{ attempt.accountedMicros === null ? '尚无可靠金额' : formatAiAmount(attempt.accountedMicros, current.task.currency) }}</dd></div><div><dt>输入／输出用量</dt><dd>{{ attempt.promptTokens ?? '未知' }} / {{ attempt.completionTokens ?? '未知' }} token</dd></div><div><dt>创建时间</dt><dd>{{ aiDate(attempt.createdAt) }}</dd></div></dl><p class="field-help">{{ attempt.sentAt ? `可能发送边界记录于 ${aiDate(attempt.sentAt)}` : '尚无发送边界记录' }}{{ attempt.finishedAt ? ` · 处理记录于 ${aiDate(attempt.finishedAt)}` : '' }}</p><p v-if="attempt.errorCode" class="field-help">状态说明：{{ attempt.errorCode }}</p><p v-if="attempt.providerRequestId" class="field-help request-id">供应商请求标识：{{ attempt.providerRequestId }}</p><p v-if="attempt.feeStatus === 'UNKNOWN'" class="unknown-note">这次费用尚未知，{{ formatAiAmount(attempt.reservedMicros, current.task.currency) }} 预留跨日期继续占用预算。取消和再次尝试均不会自动释放。</p><p v-if="attempt.reconciliationNote" class="reconciliation-note">用户核对说明：{{ attempt.reconciliationNote }}</p><NButton v-if="attempt.feeStatus === 'UNKNOWN'" size="small" :disabled="blocked" @click="openReconciliation(attempt)">根据外部账单核对费用</NButton></article></section>

      <section v-if="reconcileId" class="reconcile-panel"><h3>核对第 {{ selectedAttempt?.number }} 次尝试费用</h3><p>请根据供应商账单填写金额与依据。记录会标记为“用户核对”，不会作为供应商证明，也不会恢复丢失结果。</p><p v-if="reconciliationStale" class="notice">任务或这次尝试已有变化，输入继续保留。先核对最新状态，再重新确认；仅费用仍为未知时可以保存。</p><label>账单金额（整数微单位）<NInput v-model:value="amountInput" :disabled="blocked" :maxlength="16" placeholder="例如：125000 表示 0.125 个当前币种单位" inputmode="numeric" /><span class="field-help">1 个 {{ current.task.currency }} 货币单位 = 1000000 微单位。{{ amount !== null ? `本次输入为 ${formatAiAmount(amount, current.task.currency)}` : '请输入非负整数，不接受小数或科学计数法。' }}</span></label><label>账单核对依据<NInput v-model:value="note" :disabled="blocked" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="记录账单日期、条目或请求标识，以及如何确认这次费用；不要填写密钥或账号凭据。" /></label><NCheckbox v-model:checked="reconcileConfirmed" :disabled="blocked || reconciliationStale">我已核对外部账单，并理解这是人工记录</NCheckbox><div class="row-actions"><NButton v-if="reconciliationStale" :loading="reconcilingRefresh" :disabled="blocked" @click="reloadForAction">核对最新状态并保留输入</NButton><NButton type="primary" :disabled="blocked || reconciliationStale || amount === null || !note.trim() || !reconcileConfirmed" @click="reconcile">保存用户费用核对</NButton><NButton :disabled="blocked" @click="closeReconciliation">关闭核对</NButton></div></section>

      <section v-if="retryOpen" class="retry-panel"><h3>重新授权一次尝试</h3><p>使用当时固定的清单、用途和配置再次请求。服务器会重新检查来源、配置和预算；新尝试单独预留费用。</p><p v-if="retryStale || !canRetry" class="notice">当前记录已变化或暂不满足重试条件，请核对最新状态后再确认。</p><AiManifest :manifest="current.manifest" :budget="settings?.budget" /><p v-if="unknownAttempts.length" class="unknown-note">旧尝试仍有 {{ unknownAttempts.length }} 笔未知费用，合计占用 {{ formatAiAmount(unknownAmount, current.task.currency) }}。本次还将新增 {{ formatAiAmount(current.manifest.reservedMicros, current.task.currency) }} 预留。</p><NCheckbox v-if="unknownAttempts.length" v-model:checked="acknowledgeUnknown" :disabled="blocked || retryStale">我确认旧未知费用继续占用预算，并接受新尝试可能额外计费</NCheckbox><AiAuthorizationChoice v-model:checked="retryConsent" v-model:authorization="retryAuthorization" :manifest="current.manifest" :disabled="blocked || retryStale || !canRetry">我已再次核对清单，同意向上述供应商与模型授权一次新尝试</AiAuthorizationChoice><div class="row-actions"><NButton v-if="retryStale" :disabled="blocked" @click="reloadForAction">重新核对状态</NButton><NButton type="primary" :disabled="blocked || retryStale || !canRetry || !retryConsent || (!!unknownAttempts.length && !acknowledgeUnknown)" @click="retryTask">授权并新增一次尝试</NButton><NButton :disabled="blocked" @click="retryOpen = false; retryConsent = false; acknowledgeUnknown = false">暂不重试</NButton></div></section>

      <section v-if="factDraft" class="candidate-panel">
        <template v-if="current.factCandidate && aiFactDraftCandidateValid(current.factCandidate, current.manifest)">
          <header><p class="section-label">事实候选 · AI 未审</p><h3>{{ current.factCandidate.content.title }}</h3><p class="field-help">{{ current.factDecision?.status === 'ADOPTED' ? '这条候选已采用为新的待核实事实，下方保留原候选与采用记录。' : current.factDecision?.status === 'IGNORED' ? '这条候选已忽略，保留供回看。' : '候选尚未写入项目事实，采用前可审阅内容并选择所属项目。' }}</p><p class="field-help">{{ current.factCandidate.validationNote }}</p></header>
          <article v-for="field in factFields" :key="field.key" class="candidate-section"><h4>{{ field.label }}</h4><p v-if="current.factCandidate.content[field.key]" class="plan-copy">{{ current.factCandidate.content[field.key] }}</p><p v-else class="field-help">原候选此项留空，采用前可按依据补充。</p></article>
          <p v-if="current.factCandidate.content.tags.length" class="field-help">候选标签：{{ current.factCandidate.content.tags.join('、') }}</p>
          <article class="candidate-section"><h4>模型说明与限制</h4><p class="plan-copy">{{ current.factCandidate.limitations }}</p></article>
          <AiMaterialCitations v-if="current.manifest.factDraft?.kind === 'MATERIAL'" :citations="factMaterialCitations" :sources="current.manifest.factDraft.materials" :disabled="blocked" @refresh="reloadForAction" />
          <section v-if="current.manifest.factDraft?.training" class="card-citations" aria-label="事实候选的已选回答原文"><h4>已选回答原文引用</h4><p class="field-help">区间对应原回答字段的绝对 UTF-16 位置，只展示当次明确选择的片段。训练假设与自述仍需人工核实。</p><figure v-for="({ citation, source, before, after }, index) in factTrainingCitations" :key="`${citation.field}/${citation.elementId}/${citation.start}/${index}`"><template v-if="source"><blockquote>{{ citation.quote }}</blockquote><figcaption><strong>{{ aiTrainingDraftFieldLabels[citation.field] }}</strong><span>第 {{ current.manifest.factDraft.training.answerNumber }} 版提交 · {{ current.manifest.factDraft.training.answerId }}</span><span v-if="citation.elementId">图元素 {{ citation.elementId }}</span><span>原字段 UTF-16 [{{ citation.start }}, {{ citation.end }})</span></figcaption><details><summary>在本次已选完整片段中定位</summary><pre class="fact-exact-text"><span>{{ before }}</span><mark>{{ citation.quote }}</mark><span>{{ after }}</span></pre><p class="field-help">所选片段从原字段位置 {{ source.start }} 开始。</p></details><RouterLink class="text-link" :to="{ name: 'training', query: { item: current.manifest.factDraft.training.sessionId, question: current.manifest.factDraft.training.questionId, answer: current.manifest.factDraft.training.answerId } }">查看这次确切提交</RouterLink></template><p v-else class="notice" role="status">这条引用无法逐字匹配当次已选回答片段。<NButton text :disabled="blocked" @click="reloadForAction">重新读取任务</NButton></p></figure></section>
          <p class="field-help">引用只定位整理出处，不确认经历、本人责任或人工改写后的内容。</p>
        </template>
        <template v-else><h3>尚无可展示的事实候选</h3><p class="field-help">{{ aiPendingStatuses.includes(current.task.status) ? '等待本次尝试完成，随后展示通过结构与原文引用核对的候选。' : '当前没有可核对的事实候选，请查看任务状态或重新读取。' }}</p></template>
      </section>
      <section v-else-if="jdRequirements" class="candidate-panel">
        <template v-if="current.jdRequirementsCandidate && aiJdRequirementsCandidateValid(current.jdRequirementsCandidate, current.manifest)">
          <header><p class="section-label">JD 要求候选 · AI 未审</p><h3>从这份 JD 提取的要求</h3><p class="field-help">{{ current.jdRequirementsDecision?.status === 'ADOPTED' ? '所选要求已经采用，下方保留原候选与正式要求身份映射。' : current.jdRequirementsDecision?.status === 'IGNORED' ? '这份候选已忽略，保留供回看。' : '候选尚未写入目标。你可以逐项勾选、改写并核对维度与优先级。' }}</p><p class="field-help">{{ current.jdRequirementsCandidate.validationNote }}</p></header>
          <article class="candidate-section"><h4>模型说明与限制</h4><p class="plan-copy">{{ current.jdRequirementsCandidate.limitations }}</p></article>
          <p v-if="!current.jdRequirementsCandidate.requirements.length" class="notice">本次未提取出可定位到 JD 原文的要求。零项结果不会修改目标。</p>
          <article v-for="requirement in current.jdRequirementsCandidate.requirements" :key="requirement.number" class="candidate-section"><h4>候选 {{ requirement.number }}</h4><p class="plan-copy">{{ requirement.text }}</p><p class="field-help">AI 未审维度：{{ requirement.dimensions.map(requirementDimensionLabel).join('、') }} · AI 未审优先级：{{ requirement.priority }} / 5（5 最高）</p><section class="card-citations" :aria-label="`候选 ${requirement.number} 的 JD 原文引用`"><h5>JD 原文引用</h5><figure v-for="(citation, index) in requirement.citations" :key="`${citation.start}/${citation.end}/${index}`"><template v-if="sourceFor(citation)"><blockquote>{{ citation.quote }}</blockquote><figcaption><RouterLink v-if="routeFor(citation)" class="text-link" :to="routeFor(citation)!">{{ sourceFor(citation)?.title }} · v{{ sourceFor(citation)?.number }} · 查看确切目标版本</RouterLink><span>原 JD 正文 UTF-16 [{{ citation.start }}, {{ citation.end }})</span></figcaption></template><p v-else class="notice" role="status">这条引用无法逐字匹配本次固定 JD 原文。<NButton text :disabled="blocked" @click="reloadForAction">重新读取任务</NButton></p></figure></section></article>
          <p class="field-help">引用定位只保留模型提取的出处；维度、优先级和人工改写后的语义仍需你审阅。</p>
        </template>
        <template v-else><h3>尚无可展示的 JD 要求候选</h3><p class="field-help">{{ aiPendingStatuses.includes(current.task.status) ? '等待本次尝试完成，随后展示通过结构与 JD 引用核对的结果。' : '当前没有可核对的要求候选，请查看任务状态或重新读取。' }}</p></template>
      </section>
      <section v-else-if="cardDraft" class="candidate-panel">
        <template v-if="current.cardCandidate && aiCardDraftCandidateValid(current.cardCandidate, current.manifest)">
          <header><p class="section-label">{{ current.cardCandidate.kind === 'CLOZE' ? '填空卡' : '问答卡' }}候选 · AI 未审</p><h3>{{ current.cardCandidate.title }}</h3><p class="field-help">{{ current.cardDecision?.status === 'ADOPTED' ? '这张原候选已采用，保存版本见下方决定记录。' : current.cardDecision?.status === 'IGNORED' ? '这张候选已忽略，保留供回看。' : '候选尚未创建卡片，可在下方审阅并改写。' }}生成与保存均不代表已经复习或掌握。</p><p class="field-help">{{ current.cardCandidate.validationNote }}</p></header>
          <article class="candidate-section"><h4>模型原题面</h4><MarkdownPreview :value="current.cardCandidate.prompt" /></article>
          <article class="candidate-section"><h4>模型原参考 · 未审</h4><MarkdownPreview :value="current.cardCandidate.reference" /></article>
          <section class="card-citations" aria-label="回忆卡候选的知识原文引用"><h4>生成引用</h4><p class="field-help">引用仅在当次完整知识正文中定位，不证明技术结论正确，也不证明人工改稿逐句由这些原文支持。</p><figure v-for="(citation, index) in current.cardCandidate.citations" :key="`${citation.itemId}/${citation.versionId}/${citation.start}/${index}`"><template v-if="sourceFor(citation)"><blockquote>{{ citation.quote }}</blockquote><figcaption><RouterLink v-if="routeFor(citation)" class="text-link" :to="routeFor(citation)!">{{ sourceFor(citation)?.title }} · v{{ sourceFor(citation)?.number }} · 查看确切版本</RouterLink><span>原知识正文 UTF-16 [{{ citation.start }}, {{ citation.end }})</span></figcaption></template><p v-else class="notice" role="status">本条引用无法逐字匹配唯一的授权知识正文。<NButton text :disabled="blocked" @click="reloadForAction">重新读取任务</NButton></p></figure></section>
        </template>
        <template v-else><h3>尚无可展示的回忆卡候选</h3><p class="field-help">{{ aiPendingStatuses.includes(current.task.status) ? '等待本次尝试完成，随后展示通过类型与引用核对的候选。' : '当前记录没有可核对的单张候选卡。请查看任务状态或重新读取记录。' }}</p></template>
      </section>
      <section v-else-if="current.task.purpose === 'KNOWLEDGE_DRAFT' && current.candidate" class="candidate-panel">
        <header><p class="section-label">{{ reviewDraft && current.manifest.reviewDraft ? aiReviewDraftKindLabels[current.manifest.reviewDraft.kind] + '整理候选 · AI 未审' : trainingDraft ? '训练片段整理候选 · AI 未审' : 'AI 候选，内容仍需审阅' }}</p><h3>{{ current.candidate.title }}</h3><p class="field-help">引用位置已核对，只说明原文区间有效，不证明技术结论正确或每项结论都由引用支持。{{ current.decision?.status === 'ADOPTED' ? '这份原候选已完成采用，实际发布正文见下方记录。' : current.decision?.status === 'IGNORED' ? '这份候选已忽略，保留供回看。' : '候选尚未写入知识正文。' }}</p><p v-if="current.candidate.validationNote" class="field-help">{{ current.candidate.validationNote }}</p></header>
        <p v-if="hasExpressionSources" class="field-help">表达素材是用户写作内容，引用相符不证明经历真实。表达引用的区间对应完整固定 JSON 文本；关联事实不会自动展开外发，原表达正文或要求中已包含的文字仍在本次外发范围内。</p>
        <article v-for="(section, index) in current.candidate.sections" :key="index" class="candidate-section">
          <h4>{{ section.heading }}</h4><MarkdownPreview :value="section.body" />
          <div v-if="section.citations.length" class="citations"><h5>本节知识与准备依据</h5><figure v-for="(citation, citationIndex) in section.citations" :key="citationIndex"><blockquote>{{ citation.quote }}</blockquote><figcaption><RouterLink v-if="routeFor(citation)" class="text-link" :to="routeFor(citation)!">{{ aiSourceKindLabel(sourceFor(citation)?.kind) }} · {{ sourceFor(citation)?.title }} · v{{ sourceFor(citation)?.number }} · 查看确切版本</RouterLink><p v-else class="notice">本条引用无法匹配清单中的唯一来源。<NButton text :disabled="blocked" @click="reloadForAction">重新读取任务</NButton></p><span>UTF-16 原文区间 [{{ citation.start }}, {{ citation.end }})</span></figcaption></figure></div>
          <AiMaterialCitations :citations="section.materialCitations" :sources="current.manifest.materialDraftSources" :disabled="blocked" @refresh="reloadForAction" />
          <AiTrainingCitations :citations="section.trainingCitations" :input="trainingDraft && candidateSourceChecksValid ? current.manifest.trainingDraft : null" :disabled="blocked" @refresh="reloadForAction" />
          <AiReviewCitations :citations="section.reviewCitations" :input="reviewDraft && candidateSourceChecksValid ? current.manifest.reviewDraft : null" :disabled="blocked" @refresh="reloadForAction" />
          <p v-if="reviewDraft && !section.reviewCitations?.length" class="notice" role="status">本节缺少复盘片段引用，请重新读取任务核对。<NButton text :disabled="blocked" @click="reloadForAction">重新读取任务</NButton></p>
          <p v-if="trainingDraft && !section.trainingCitations?.length" class="notice" role="status">本节缺少训练原文引用，请重新读取任务核对。<NButton text :disabled="blocked" @click="reloadForAction">重新读取任务</NButton></p>
        </article>
      </section>
      <section v-else-if="current.task.purpose === 'QUESTION_DRAFT' && current.questionCandidate" class="candidate-panel">
        <header><p class="section-label">模型原候选 · 一道题</p><h3>{{ current.questionCandidate.title }}</h3><p class="field-help">{{ current.questionCandidate.validationNote }}</p><p class="field-help">{{ current.questionDecision?.status === 'ADOPTED' ? '这道原候选已采用，最终保存内容见下方题库记录。' : current.questionDecision?.status === 'IGNORED' ? '这道候选题已忽略，保留供回看。' : '题干与个人参考均待你审阅，候选尚未写入题库。' }}</p></header>
        <p class="field-help">{{ questionTypeLabels[current.questionCandidate.type] }} · {{ current.questionCandidate.difficulty === null ? '未标个人难度' : `个人难度建议 ${current.questionCandidate.difficulty}` }} · {{ current.questionCandidate.dimensions.map(value => questionDimensionLabels[value]).join('、') }}</p>
        <article class="candidate-section"><h4>候选题干</h4><MarkdownPreview :value="current.questionCandidate.prompt" /><dl class="candidate-context"><template v-if="current.questionCandidate.conditions"><dt>适用条件</dt><dd>{{ current.questionCandidate.conditions }}</dd></template><template v-if="current.questionCandidate.technology"><dt>技术与版本</dt><dd>{{ current.questionCandidate.technology }}</dd></template><template v-if="current.questionCandidate.scenario"><dt>场景</dt><dd>{{ current.questionCandidate.scenario }}</dd></template></dl></article>
        <article class="candidate-section"><h4>模型参考／未审</h4><p class="field-help">参考和检查点尚未经过技术审阅。你可以在最终表单中修改或清空。</p><MarkdownPreview v-if="current.questionCandidate.referenceAnswer" :value="current.questionCandidate.referenceAnswer" /><p v-else class="field-help">模型未提供参考正文。</p><h5>参考检查点</h5><ol v-if="current.questionCandidate.checkpoints.length" class="candidate-checkpoints"><li v-for="(point, index) in current.questionCandidate.checkpoints" :key="index">{{ point }}</li></ol><p v-else class="field-help">模型未提供检查点。</p></article>
        <p class="field-help">知识、准备与资料摘录引用保留生成依据，不自动成为题目的正式知识关联，也不证明题干和参考的技术正确性。</p>
        <p v-if="hasExpressionSources" class="field-help">表达素材是用户写作内容，引用相符不证明经历真实。表达引用的区间对应完整固定 JSON 文本；关联事实不会自动展开外发，原表达正文或要求中已包含的文字仍在本次外发范围内。</p>
        <div v-if="current.questionCandidate.citations.length" class="citations"><h5>生成所引用的知识与准备原文</h5><figure v-for="(citation, index) in current.questionCandidate.citations" :key="index"><blockquote>{{ citation.quote }}</blockquote><figcaption><RouterLink v-if="routeFor(citation)" class="text-link" :to="routeFor(citation)!">{{ aiSourceKindLabel(sourceFor(citation)?.kind) }} · {{ sourceFor(citation)?.title }} · v{{ sourceFor(citation)?.number }} · 查看确切版本</RouterLink><p v-else class="notice">本条引用无法匹配清单中的唯一来源。<NButton text :disabled="blocked" @click="reloadForAction">重新读取任务</NButton></p><span>UTF-16 原文区间 [{{ citation.start }}, {{ citation.end }})</span></figcaption></figure></div>
        <AiMaterialCitations :citations="current.questionCandidate.materialCitations" :sources="current.manifest.materialDraftSources" :disabled="blocked" @refresh="reloadForAction" />
      </section>
      <section v-else-if="current.task.purpose === 'EXPRESSION_DRAFT' && current.expressionCandidate" class="candidate-panel">
        <header><p class="section-label">模型原候选 · {{ expressionTypeLabel }}</p><h3>{{ current.expressionCandidate.title }}</h3><p class="field-help">{{ current.expressionCandidate.validationNote }}</p><p class="field-help">{{ current.expressionDecision?.status === 'ADOPTED' ? '这份原候选已采用，最终内容见下方表达版本。' : current.expressionDecision?.status === 'IGNORED' ? '这份表达候选已忽略，保留供回看。' : '候选尚未写入表达素材，请核对本人职责、经历与结果后再采用。' }}</p></header>
        <MarkdownPreview :value="current.expressionCandidate.body" />
        <div class="citations"><h5>生成所引用的确切事实原文</h5><p class="field-help">引用范围对应本次外发的固定事实正文；定位相符不证明经历真实，也不证明人工改写的每一句话都有事实支持。</p><figure v-for="(citation, index) in current.expressionCandidate.citations" :key="`${citation.itemId}/${citation.versionId}/${citation.start}/${index}`"><blockquote>{{ citation.quote }}</blockquote><figcaption><RouterLink v-if="sourceFor(citation)?.kind === 'FACT' && routeFor(citation)" class="text-link" :to="routeFor(citation)!">{{ sourceFor(citation)?.title }} · v{{ sourceFor(citation)?.number }} · 查看确切事实版本</RouterLink><p v-else class="notice">本条引用无法匹配清单中的唯一事实版本。<NButton text :disabled="blocked" @click="reloadForAction">重新读取任务</NButton></p><span>固定事实正文（JSON 文本）内 UTF-16 [{{ citation.start }}, {{ citation.end }})</span></figcaption></figure></div>
      </section>
      <section v-else-if="current.task.purpose === 'PREPARATION_PLAN' && current.preparationPlanCandidate" class="candidate-panel">
        <header><p class="section-label">模型原建议 · 准备安排</p><h3>{{ current.preparationPlanCandidate.title }}</h3><p class="field-help">{{ current.preparationPlanCandidate.validationNote }}</p><p class="field-help">{{ current.preparationPlanDecision?.status === 'ADOPTED' ? '这份原建议已采用，实际保存的安排与任务链接见下方记录。' : current.preparationPlanDecision?.status === 'IGNORED' ? '这份建议已忽略，保留供回看。' : '这些步骤尚未创建准备任务，请先核对内容、日期与工作量。' }}</p></header>
        <p v-if="current.preparationPlanCandidate.summary" class="plan-copy">{{ current.preparationPlanCandidate.summary }}</p>
        <p v-if="current.manifest.preparationPlan" class="field-help">原授权条件：{{ current.manifest.preparationPlan.startDate }} 至 {{ current.manifest.preparationPlan.endDate }}（含首尾）· {{ current.manifest.preparationPlan.zoneId }} · 总工作量最多 {{ current.manifest.preparationPlan.totalMinutes }} 分钟。过去日期保持原值，不自动顺延；分钟不代表日历空闲或已完成学习。</p>
        <ol class="plan-candidate-steps"><li v-for="(step, index) in current.preparationPlanCandidate.steps" :key="index"><header><h4>{{ index + 1 }}. {{ step.title }}</h4><span>{{ step.date }} · {{ step.minutes }} 分钟</span></header><p v-if="step.detail" class="plan-copy">{{ step.detail }}</p></li></ol>
        <article class="candidate-section"><h4>建议的限制</h4><p class="plan-copy">{{ current.preparationPlanCandidate.limitations }}</p></article>
        <p class="field-help">目标要求和用户提供的缺口摘要是建议背景，不构成能力诊断或掌握证明。采用将保存你审阅的最终步骤并创建普通任务，由你后续逐项开始和完成。</p>
      </section>
      <AiRewriteCandidate v-else-if="rewrite && current.rewriteCandidate" :key="current.task.id" :task="current" :disabled="!store.sessionActive || disabled || currentEpoch !== store.sessionEpoch" />
      <AiComparisonCandidate v-else-if="current.task.purpose === 'ANSWER_COMPARISON' && current.comparisonCandidate" :task="current" :disabled="!store.sessionActive || disabled || currentEpoch !== store.sessionEpoch" />
      <AiRelationCandidates v-else-if="current.task.purpose === 'RELATION_DRAFT' && current.relationCandidate" :task="current" :refresh-task="refreshTask" :disabled="!store.sessionActive || disabled || currentEpoch !== store.sessionEpoch" :peer-locked="locked || reconcilingRefresh" @locked="adoptionLocked = $event" @received="receiveDecision" />
      <AiEvaluationReport v-else-if="current.task.purpose === 'ANSWER_EVALUATION' && current.evaluationReport" :task="current" :disabled="disabled" :peer-locked="locked || reconcilingRefresh" @locked="adoptionLocked = $event" @refreshed="refreshCandidate" @received="receiveDecision" />
      <AiMaterialAnswer v-else-if="current.task.purpose === 'MATERIAL_QA' && current.materialAnswer" :task="current" :disabled="disabled" />
      <AiSourceQaAnswer v-else-if="sourceQa && current.sourceQaAnswer && current.manifest.sourceQa" :answer="current.sourceQaAnswer" :input="current.manifest.sourceQa" />
      <section v-else class="candidate-empty"><h3>{{ current.task.purpose === 'ANSWER_EVALUATION' ? '尚无可展示证据报告' : current.task.purpose === 'MATERIAL_QA' ? '尚无可展示问答结果' : '尚无可展示候选' }}</h3><p class="field-help">{{ aiPendingStatuses.includes(current.task.status) ? '等待本次尝试处理完成，再展示通过结构与引用定位核对的结果。' : '当前记录没有可接纳的结果。可查看状态与费用，必要时重新选择来源或明确授权再次尝试。' }}</p></section>
      <AiFactDraftAdoption v-if="factDraft && (current.factCandidate || current.factDecision)" :key="current.task.id" :task="current" :refresh-task="refreshTask" :disabled="disabled" :peer-locked="locked || reconcilingRefresh" @locked="adoptionLocked = $event" @received="receiveDecision" />
      <AiJdRequirementsAdoption v-else-if="jdRequirements && (current.jdRequirementsCandidate || current.jdRequirementsDecision)" :key="current.task.id" :task="current" :refresh-task="refreshTask" :disabled="disabled" :peer-locked="locked || reconcilingRefresh" @locked="adoptionLocked = $event" @received="receiveDecision" />
      <AiCardDraftAdoption v-else-if="cardDraft && (current.cardCandidate || current.cardDecision)" :key="current.task.id" :task="current" :refresh-task="refreshTask" :disabled="disabled" :peer-locked="locked || reconcilingRefresh" @locked="adoptionLocked = $event" @received="receiveDecision" />
      <AiCandidateAdoption v-else-if="current.task.purpose === 'KNOWLEDGE_DRAFT' && current.candidate" :task="current" :disabled="disabled" :peer-locked="locked || reconcilingRefresh" @locked="adoptionLocked = $event" @refreshed="refreshCandidate" @received="receiveDecision" />
      <AiQuestionAdoption v-else-if="current.task.purpose === 'QUESTION_DRAFT' && current.questionCandidate" :task="current" :disabled="disabled" :peer-locked="locked || reconcilingRefresh" @locked="adoptionLocked = $event" @refreshed="refreshCandidate" @received="receiveDecision" />
      <AiExpressionAdoption v-else-if="current.task.purpose === 'EXPRESSION_DRAFT' && current.expressionCandidate" :task="current" :disabled="disabled" :peer-locked="locked || reconcilingRefresh" @locked="adoptionLocked = $event" @refreshed="refreshCandidate" @received="receiveDecision" />
      <AiPreparationPlanAdoption v-else-if="current.task.purpose === 'PREPARATION_PLAN' && (current.preparationPlanCandidate || current.preparationPlanDecision)" :task="current" :disabled="disabled" :peer-locked="locked || reconcilingRefresh" @locked="adoptionLocked = $event" @refreshed="refreshCandidate" @received="receiveDecision" />
      <section class="saved-manifest"><header class="panel-heading"><h3>已授权的固定外发清单</h3><NButton size="small" @click="manifestOpen = !manifestOpen">{{ manifestOpen ? '收起清单' : '查看完整清单' }}</NButton></header><AiManifest v-if="manifestOpen" :manifest="current.manifest" /></section>
      <AiAuthorizationScope v-if="!sourceQaTask" :task-id="current.task.id" :manifest="current.manifest" :disabled="disabled || writer.locked.value || !!deferred || adoptionLocked || reconcilingRefresh" @state="authorizationState.dirty = $event.dirty; authorizationState.locked = $event.locked" />
    </template>
  </section>
  <AiAuthorizationScope v-if="sourceQaTask" source-qa :task-id="taskId" :manifest="current?.manifest" :manifest-hash="current?.sourceQaManifestHash"
    :disabled="disabled || !store.sessionActive || writer.locked.value || sourceQaChecking || reconcilingRefresh"
    @state="authorizationState.dirty = $event.dirty; authorizationState.locked = $event.locked" />
</template>

<style scoped>
.ai-detail { display: grid; gap: 22px; padding: 25px 27px 30px; min-width: 0; } .detail-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; } .detail-heading > div { min-width: 0; } h2 { margin: 7px 0 9px; font-size: 24px; overflow-wrap: anywhere; } h3 { margin: 0; font-size: 17px; } h4 { margin: 0; font-size: 14px; } h5 { margin: 0; font-size: 12px; color: var(--muted); }
.attempts-panel, .candidate-panel, .saved-manifest { display: grid; gap: 16px; border-top: 1px solid var(--line); padding-top: 21px; min-width: 0; } .attempt-card { display: grid; gap: 12px; border: 1px solid var(--line); border-radius: 7px; padding: 17px; } .attempt-card > header { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 9px; } .fee-status { font-size: 12px; color: var(--muted); } .unknown { color: var(--warm); } .attempt-card dl { margin: 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 13px 20px; } dt { font-size: 12px; color: var(--muted); } dd { margin: 5px 0 0; font-size: 13px; overflow-wrap: anywhere; } .attempt-card > .n-button { justify-self: start; } .request-id { overflow-wrap: anywhere; }
.unknown-note { font-size: 13px; color: var(--warm); } .reconciliation-note { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; padding: 12px; background: var(--surface-soft); }
.reconcile-panel, .retry-panel { display: grid; gap: 17px; padding: 19px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); } .reconcile-panel > p, .retry-panel > p { font-size: 13px; color: var(--muted); } .reconcile-panel label { display: grid; gap: 8px; font-size: 13px; color: var(--muted); }
.candidate-panel > header { display: grid; gap: 9px; } .candidate-section { display: grid; gap: 14px; padding-top: 19px; border-top: 1px solid var(--line); } .candidate-section > h4 { font-size: 16px; } .citations { display: grid; gap: 12px; } .citations figure { margin: 0; border-left: 2px solid var(--brand); padding-left: 13px; } blockquote { margin: 0 0 8px; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; } figcaption { display: grid; gap: 4px; font-size: 12px; } figcaption span { color: var(--muted); font-size: 11px; } .candidate-empty { display: grid; gap: 10px; padding: 24px 0; border-top: 1px solid var(--line); }
.candidate-context { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 10px 14px; margin: 0; } .candidate-context dd { margin: 0; white-space: pre-wrap; } .candidate-checkpoints { display: grid; gap: 9px; margin: 0; padding-left: 24px; font-size: 13px; white-space: pre-wrap; overflow-wrap: anywhere; }
.evaluation-link { display: grid; gap: 12px; }
.plan-copy { margin: 0; font-size: 13px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.fact-exact-text { margin: 10px 0; padding: 13px; border-radius: 5px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); }.fact-exact-text mark { color: inherit; background: var(--highlight, #fff1b8); }
.plan-candidate-steps { display: grid; gap: 15px; margin: 0; padding: 0; list-style: none; }
.plan-candidate-steps > li { display: grid; gap: 10px; padding-top: 15px; border-top: 1px solid var(--line); }
.plan-candidate-steps header { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 9px 15px; }
.plan-candidate-steps h4 { overflow-wrap: anywhere; } .plan-candidate-steps header > span { font-size: 12px; color: var(--muted); }
.card-citations { display: grid; gap: 13px; min-width: 0; }.card-citations figure { display: grid; gap: 10px; margin: 0; border-top: 1px solid var(--line); padding-top: 13px; min-width: 0; }.card-citations blockquote { max-width: 75ch; line-height: 1.75; }.card-citations figcaption { font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 700px) { .ai-detail { padding: 22px 17px; } h2 { font-size: 21px; } .attempt-card dl { grid-template-columns: 1fr; } .reconcile-panel, .retry-panel { padding: 15px; } }
</style>
