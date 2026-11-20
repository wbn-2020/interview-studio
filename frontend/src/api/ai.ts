import type { Schema } from './client'
import type { RouteLocationRaw } from 'vue-router'
import type { ExpressionContent, FactContent, PreparationReference } from './preparation'
import { expressionOptions } from './preparation'
import { validPreparationTaskDate } from './preparationTask'
import { validZone } from './schedule'
import { questionDimensionLabels, questionTypeLabels } from './bank'
import { aiReviewDraftInputFingerprint, aiReviewDraftInputValid } from './aiReviewDraft'
import { aiFactDraftInputFingerprint } from './aiFactDraft'
import { aiRelationEvidenceInputValid, aiRelationEvidenceSuggestionCitationsValid, aiRelationEvidenceValidationNote } from './aiRelationEvidence'
import { aiRelationReviewSuggestionCitationsValid, aiRelationReviewValidationNote } from './aiRelationReview'
import { aiRelationAiFeedbackInputValid, aiRelationAiFeedbackSuggestionCitationsValid, aiRelationAiFeedbackValidationNote } from './aiRelationAiFeedback'
import { relationAiFeedbackEvidenceSnapshotFingerprint } from './relationAiFeedbackEvidence'

export type AiConfiguration = Schema['AiConfiguration']
export type AiBudget = Schema['AiBudget']
export type AiSettings = Schema['AiSettings']
export type AiSource = Schema['AiSource']
export type AiPreviewRequest = Schema['AiPreviewRequest']
export type AiPreview = Schema['AiPreview']
export type AiManifest = Schema['AiManifest']
export type AiMaterialCheck = Schema['MaterialLifecycleCheck']
export type AiTask = Schema['AiTask']
export type AiTaskPage = Schema['AiTaskPage']
export type AiStatus = Schema['AiStatus']
export type AiFeeStatus = Schema['AiFeeStatus']
export type AiAttempt = Schema['AiAttempt']
export type AiCitation = Schema['AiCitation']
export type AiAdoptionMode = Schema['AiAdoptionMode']
export type AiAdoptCandidate = Schema['AiAdoptCandidate']
export type AiOriginPage = Schema['AiOriginPage']
export type AiOriginState = Schema['AiOriginState']
export type AiPurpose = Schema['AiPurpose']
export type AiQuestionCandidate = Schema['AiQuestionCandidate']
export type AiAdoptQuestion = Schema['AiAdoptQuestion']
export type AiFollowUpTask = Schema['AiFollowUpTask']
export type AiFollowUpPage = Schema['AiFollowUpPage']
export type AiFollowUpInput = Schema['AiFollowUpInput']
export type AiEvaluationAccess = Schema['AiEvaluationAccess']
export type AiEvaluationInput = Schema['AiEvaluationInput']
export type AiEvaluationCitation = Schema['AiEvaluationCitation']
export type AiEvaluationObservation = Schema['AiEvaluationObservation']
export type AiEvaluationPage = Schema['AiEvaluationPage']
export type AiEvaluationSessionCoverage = Schema['AiEvaluationSessionCoverage']
export type AiFeedbackDecision = Schema['AiFeedbackDecision']
export type AiFeedbackChoice = Schema['AiFeedbackChoice']
export type AiMaterialQaPreviewRequest = Schema['AiMaterialQaPreviewRequest']
export type AiCreateMaterialQa = Schema['AiCreateMaterialQa']
export type AiMaterialQaMode = Schema['AiMaterialQaMode']
export type AiMaterialQaInput = Schema['AiMaterialQaInput']
export type MaterialAiExcerpt = Schema['MaterialAiExcerpt']
export type AiMaterialAnswerState = Schema['AiMaterialAnswerState']
export type AiMaterialAnswer = Schema['AiMaterialAnswer']
export type AiMaterialCitation = Schema['AiMaterialCitation']
export type AiPreparationLink = Schema['AiPreparationLink']
export type AiPreparationDraftPreviewRequest = Schema['AiPreparationDraftPreviewRequest']
export type AiCreatePreparationDraft = Schema['AiCreatePreparationDraft']
export type AiPreparationCheck = Schema['AiPreparationCheck']
export type AiExpressionCheck = Schema['PrepareExpressionCheck']
export type AiExpressionSourceContent = Pick<ExpressionContent, 'type' | 'body'>
export type AiPreparationSelection = AiPreparationLink & {
  title: string; number: number; responsibility?: FactContent['responsibility']
  expression?: AiExpressionSourceContent & { facts: PreparationReference[] }
}
export type AiMaterialDraftPreviewRequest = Schema['AiMaterialDraftPreviewRequest']
export type AiCreateMaterialDraft = Schema['AiCreateMaterialDraft']
export type AiMaterialOriginSource = NonNullable<AiOriginPage['items'][number]['materialSources']>[number]
export type AiExpressionType = Schema['PrepareExpressionContent']['type']
export type AiExpressionDraftPreviewRequest = Schema['AiExpressionDraftPreviewRequest']
export type AiCreateExpressionDraft = Schema['AiCreateExpressionDraft']
export type AiAdoptExpression = Schema['AiAdoptExpression']
export type AiExpressionCandidate = Schema['AiExpressionCandidate']
export type AiExpressionDecision = Schema['AiExpressionDecision']
export type AiPreparationPlanInput = Schema['AiPreparationPlanInput']
export type AiPreparationPlanPreviewRequest = Schema['AiPreparationPlanPreviewRequest']
export type AiCreatePreparationPlan = Schema['AiCreatePreparationPlan']
export type AiPreparationPlanStep = Schema['AiPreparationPlanStep']
export type AiPreparationPlanContent = Schema['AiPreparationPlanContent']
export type AiPreparationPlanCandidate = Schema['AiPreparationPlanCandidate']
export type AiAdoptPreparationPlan = Schema['AiAdoptPreparationPlan']
export type AiPreparationPlanTaskLink = Schema['AiPreparationPlanTaskLink']
export type AiPreparationPlanDecision = Schema['AiPreparationPlanDecision']
export type AiTrainingDraftField = Schema['AiTrainingDraftField']
export type AiTrainingDraftFragment = Schema['AiTrainingDraftFragment']
export type AiTrainingDraftFeedback = Schema['AiTrainingDraftFeedback']
export type AiTrainingDraftPreviewRequest = Schema['AiTrainingDraftPreviewRequest']
export type AiCreateTrainingDraft = Schema['AiCreateTrainingDraft']
export type AiTrainingDraftInput = Schema['AiTrainingDraftInput']
export type AiTrainingDraftCitation = Schema['AiTrainingDraftCitation']
export type AiTrainingDraftOrigin = Schema['AiTrainingDraftOrigin']
export type AiRelationDraftPreviewRequest = Schema['AiRelationDraftPreviewRequest']
export type AiCreateRelationDraft = Schema['AiCreateRelationDraft']
export type AiRelationSuggestion = Schema['AiRelationSuggestion']
export type AiRelationCandidate = Schema['AiRelationCandidate']
export type AiRelationContent = Schema['AiRelationContent']
export type AiAdoptRelation = Schema['AiAdoptRelation']
export type AiSupplementRelation = Schema['AiSupplementRelation']
export type AiRelationDecision = Schema['AiRelationDecision']
export type AiRelationOrigin = Schema['AiRelationOrigin']

export const aiTrainingDraftFieldLabels: Record<AiTrainingDraftField, string> = {
  QUESTION_PROMPT: '固定题干', QUESTION_CONDITIONS: '题目条件', ANSWER_TEXT: '回答文字',
  ANSWER_NODE_LABEL: '图节点名称', ANSWER_NODE_DESCRIPTION: '图节点说明', ANSWER_EDGE_LABEL: '图连线说明',
  FEEDBACK_OBSERVATION: '反馈观察', FEEDBACK_CONDITION: '反馈条件', FEEDBACK_BASIS: '反馈依据',
  FEEDBACK_LIMITATION: '反馈限制', FEEDBACK_SUGGESTION: '反馈建议',
}

export const aiPurposeLabels: Record<AiPurpose, string> = {
  KNOWLEDGE_DRAFT: '知识整理', QUESTION_DRAFT: '生成题目', FOLLOW_UP: '训练追问', ANSWER_EVALUATION: '答案评价', MATERIAL_QA: '资料问答', SOURCE_QA: '所选来源问答', EXPRESSION_DRAFT: '生成表达素材', PREPARATION_PLAN: '准备建议', RELATION_DRAFT: '关系建议', ANSWER_COMPARISON: '复练比较', MEMORY_CARD_DRAFT: '生成回忆卡', JD_REQUIREMENT_DRAFT: '提取 JD 要求', FACT_DRAFT: '整理事实候选', ANSWER_REWRITE: '改写参考',
}
export const aiSourceKindLabels: Record<AiSource['kind'], string> = {
  NOTE: '笔记', CONCEPT: '概念', TARGET: '目标与 JD', PROJECT: '项目概况', FACT: '用户确认的项目事实', EXPRESSION: '表达素材',
}
export const aiOriginStateLabels: Record<AiOriginState, string> = {
  CURRENT: '仍为当前可用版本', SUPERSEDED: '来源已有新版本', ARCHIVED: '来源已归档', UNAVAILABLE: '来源不可用',
  WITHDRAWN: '事实确认已撤回', PROJECT_ARCHIVED: '所属项目已归档', UNVERIFIED: '事实待用户核实', REVIEW_REQUIRED: '来源代次或条件已变化，待复核',
}
export const aiMaterialModeLabels: Record<AiMaterialQaMode, string> = { MATERIAL: '依据所选资料', GENERAL: '通用解释' }
export const aiMaterialAnswerLabels: Record<AiMaterialAnswerState, string> = {
  SUPPORTED: '附资料引用 · AI 未审', INSUFFICIENT: '所选资料不足 · AI 未审', GENERAL: '通用解释 · AI 未审',
}

export const aiStatusLabels: Record<AiStatus, string> = {
  QUEUED: '排队中', RUNNING: '请求处理中', VALIDATING: '正在核对结果', SUCCEEDED: '候选已生成',
  FAILED: '未完成', UNKNOWN: '结果未知', CANCEL_REQUESTED: '已请求取消', CANCELED: '已取消',
}
export const aiFeeLabels: Record<AiFeeStatus, string> = {
  RESERVED: '已预留', REPORTED: '按已报告用量估算', UNKNOWN: '费用未知，保留预留', RELEASED: '预留已释放', RECONCILED: '用户已核对账单',
}
export const aiPendingStatuses: AiStatus[] = ['QUEUED', 'RUNNING', 'VALIDATING', 'CANCEL_REQUESTED']

export function isAiPreparationKind(kind: string | null | undefined): kind is AiPreparationLink['kind'] {
  return kind === 'TARGET' || kind === 'PROJECT' || kind === 'FACT' || kind === 'EXPRESSION'
}

export function aiSourceKindLabel(kind: string | null | undefined): string {
  return aiSourceKindLabels[kind as AiSource['kind']] ?? '来源类型待核对'
}

export function aiSourceRoute(source: Pick<AiSource, 'itemId' | 'versionId' | 'kind'> | undefined): RouteLocationRaw | undefined {
  if (!source) return undefined
  if (source.kind === 'NOTE' || source.kind === 'CONCEPT') return { name: 'knowledge', query: { item: source.itemId, version: source.versionId } }
  if (isAiPreparationKind(source.kind)) return { name: 'prepare', query: { kind: source.kind, item: source.itemId, version: source.versionId } }
  return undefined
}

export function aiOriginSourceRoute(source: AiOriginPage['items'][number]['sources'][number]): RouteLocationRaw | undefined {
  if (source.state === 'UNAVAILABLE') return undefined
  // 只有旧采用回执缺省 kind 才按知识来源兼容；候选或未知新类型不能据此猜测领域。
  return aiSourceRoute({ ...source, kind: source.kind ?? 'NOTE' })
}

export function aiMaterialSourceRoute(source: Pick<MaterialAiExcerpt, 'excerptId' | 'attachmentId' | 'origin' | 'runId' | 'segmentId'> | undefined): RouteLocationRaw | undefined {
  if (!source?.excerptId || !source.attachmentId) return undefined
  if (source.origin === 'PARSED' ? !source.runId || !source.segmentId : source.origin !== 'MANUAL' || source.runId !== null || source.segmentId !== null) return undefined
  return { name: 'materials', query: { attachment: source.attachmentId, run: source.runId ?? undefined, excerpt: source.excerptId } }
}

export function aiMaterialOriginRoute(source: AiMaterialOriginSource): RouteLocationRaw | undefined {
  return source.state === 'CURRENT' ? aiMaterialSourceRoute(source) : undefined
}

export function aiDraftPromptVersion(manifest: AiManifest): string | undefined {
  if (manifest.trainingDraft != null || manifest.comparisonInput != null || manifest.comparisonChecks != null || manifest.reviewDraft != null || manifest.cardDraftType != null || manifest.factDraft != null || manifest.relationEvidence != null || manifest.relationAiFeedback != null
    || (manifest.purpose !== 'KNOWLEDGE_DRAFT' && manifest.purpose !== 'QUESTION_DRAFT')) return undefined
  const suffix = manifest.purpose === 'KNOWLEDGE_DRAFT' ? 'knowledge-draft-1' : 'question-draft-1'
  const expression = manifest.sources.some(source => source.kind === 'EXPRESSION')
  const material = Boolean(manifest.materialDraftSources?.length)
  if (expression) return `expression-source-${material ? 'material-' : ''}${suffix}`
  return `${material ? 'material-' : manifest.sources.some(source => isAiPreparationKind(source.kind)) ? 'preparation-' : ''}${suffix}`
}

export function aiExpressionSourceContent(source: Pick<AiSource, 'kind' | 'body'> | undefined): AiExpressionSourceContent | undefined {
  if (source?.kind !== 'EXPRESSION') return undefined
  try {
    const value = JSON.parse(source.body) as AiExpressionSourceContent | null
    if (!value || Array.isArray(value) || Object.keys(value).join(',') !== 'type,body'
      || !expressionOptions.some(option => option.value === value.type)
      || !aiPreparationPlanTextValid(value.body, 50000) || !value.body.trim()) return undefined
    return { type: value.type, body: value.body }
  } catch { return undefined }
}

const validSourceId = (value: string) => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const validSourceGeneration = (value: number | null) => value !== null && Number.isSafeInteger(value) && value >= 0
const preparationCheckValues = (check: AiPreparationCheck) => [check.kind, check.itemId, check.versionId, check.state, check.sourceGeneration, check.projectGeneration]

export function aiManifestHasKnowledgeRoots(manifest: AiManifest): boolean {
  const sourceQaSources = manifest.purpose === 'SOURCE_QA' ? manifest.sourceQa?.sources : undefined
  return Array.isArray(manifest.sources) && manifest.sources.some(source => source?.kind === 'NOTE' || source?.kind === 'CONCEPT')
    || Array.isArray(sourceQaSources) && sourceQaSources.some(source => source?.ref?.domain === 'KNOWLEDGE')
}

export function aiKnowledgeMaterialChecksMatch(manifest: AiManifest, requireChecks = false): boolean {
  try {
    const checks = manifest.knowledgeMaterialChecks
    if (checks == null) return !requireChecks
    // 各用途均可登记祖先摘录；前端只核形状，完整来源和当前许可由服务器重建。
    return Array.isArray(checks) && checks.length <= 200
      && checks.every((check, index) => check != null && typeof check === 'object' && !Array.isArray(check)
        && Object.keys(check).length === 2 && Object.hasOwn(check, 'excerptId') && Object.hasOwn(check, 'revision')
        && validSourceId(check.excerptId) && Number.isSafeInteger(check.revision) && check.revision >= 0
        && (index === 0 || checks[index - 1]!.excerptId < check.excerptId))
  } catch { return false }
}

export function aiMaterialChecksMatch(manifest: AiManifest, requireChecks = false): boolean {
  try {
    let ids: string[] = []
    if (manifest.purpose === 'KNOWLEDGE_DRAFT' || manifest.purpose === 'QUESTION_DRAFT') {
      ids = (manifest.materialDraftSources ?? []).map(source => source.excerptId)
    } else if (manifest.purpose === 'MATERIAL_QA') {
      if (!manifest.materialQa || manifest.materialQa.mode !== 'MATERIAL' && manifest.materialQa.mode !== 'GENERAL') return false
      ids = manifest.materialQa.excerpts.map(source => source.excerptId)
      if (manifest.materialQa.mode === 'GENERAL' && ids.length !== 0) return false
    } else if (manifest.purpose === 'FACT_DRAFT') {
      ids = manifest.factDraft?.kind === 'MATERIAL' ? manifest.factDraft.materials.map(source => source.excerptId) : []
    } else if (manifest.purpose === 'RELATION_DRAFT') {
      ids = manifest.relationEvidence?.kind === 'MATERIAL' ? manifest.relationEvidence.materials.map(source => source.excerptId) : []
    } else if (manifest.purpose === 'SOURCE_QA') {
      ids = (manifest.sourceQa?.sources ?? []).filter(source => source.ref.domain === 'MATERIAL_EXCERPT').map(source => source.ref.id)
    }
    if (ids.some(id => !validSourceId(id))) return false
    // 跨域问答可选同一摘录的多个片段；本地生命周期只固定一次摘录代次。
    const expected = [...new Set(ids)].sort()
    const checks = manifest.materialChecks
    if (!expected.length) return checks == null
    if (expected.length > 5 || checks == null) return !requireChecks && checks == null && expected.length <= 5
    return Array.isArray(checks) && checks.length === expected.length && checks.every((check, index) =>
      check != null && typeof check === 'object' && !Array.isArray(check) && Object.keys(check).length === 2
      && Object.hasOwn(check, 'excerptId') && Object.hasOwn(check, 'revision')
      && check.excerptId === expected[index] && Number.isSafeInteger(check.revision) && check.revision >= 0)
  } catch { return false }
}

export function aiExpressionSourceChecksMatch(manifest: AiManifest): boolean {
  if (manifest.comparisonInput != null || manifest.comparisonChecks != null || manifest.reviewDraft != null || manifest.cardDraftType != null || manifest.factDraft != null || manifest.relationEvidence != null || manifest.relationAiFeedback != null
    || manifest.purpose === 'MEMORY_CARD_DRAFT' || manifest.purpose === 'JD_REQUIREMENT_DRAFT' || manifest.purpose === 'FACT_DRAFT'
    || !aiKnowledgeMaterialChecksMatch(manifest)) return false
  const sources = manifest.sources.filter(source => source.kind === 'EXPRESSION')
  if (!sources.length) return manifest.expressionChecks == null && !manifest.promptVersion.startsWith('expression-source-')
  const checks = manifest.expressionChecks
  if (!Array.isArray(checks) || checks.length !== sources.length || !Array.isArray(manifest.preparationChecks)
    || manifest.promptVersion !== aiDraftPromptVersion(manifest)
    || manifest.sources.length + (manifest.materialDraftSources?.length ?? 0) > 5
    || manifest.training != null || manifest.evaluation != null || manifest.contextChecks != null
    || manifest.materialQa != null || manifest.expressionType != null || manifest.preparationPlan != null || manifest.trainingDraft != null) return false
  if (new Set(sources.map(source => source.itemId)).size !== sources.length
    || new Set(sources.map(source => source.versionId)).size !== sources.length) return false
  return sources.every((source, index) => {
    const group = checks[index]
    if (!group) return false
    const own = group.expression
    const prepared = manifest.preparationChecks?.filter(check => check?.kind === 'EXPRESSION' && check.itemId === source.itemId && check.versionId === source.versionId) ?? []
    if (!validSourceId(source.itemId) || !validSourceId(source.versionId) || !Number.isSafeInteger(source.number) || source.number < 1
      || typeof source.title !== 'string' || !source.title.trim() || !aiExpressionSourceContent(source)
      || !own || own.kind !== 'EXPRESSION' || own.itemId !== source.itemId || own.versionId !== source.versionId
      || own.state !== 'CURRENT' || !validSourceGeneration(own.sourceGeneration) || own.projectGeneration !== null
      || prepared.length !== 1 || JSON.stringify(preparationCheckValues(own)) !== JSON.stringify(preparationCheckValues(prepared[0]!))
      || !Array.isArray(group.factChecks) || group.factChecks.length < 1 || group.factChecks.length > 50) return false
    // 依据是本地授权快照，不加入 sources，也不能把多个事实版本混成一个有效身份。
    const facts = group.factChecks
    return facts.every(check => check != null && check.kind === 'FACT' && validSourceId(check.itemId) && validSourceId(check.versionId)
        && check.state === 'CURRENT' && validSourceGeneration(check.sourceGeneration) && validSourceGeneration(check.projectGeneration))
      && new Set(facts.map(check => check.itemId)).size === facts.length
      && new Set(facts.map(check => check.versionId)).size === facts.length
  })
}

export function aiCandidateManifestFingerprint(manifest: AiManifest): string {
  // 固定正文、依据顺序、代次、配置与额度一起比较；旧缺省字段按 null 兼容，不重算服务器摘要。
  return JSON.stringify([manifest.purpose, manifest.promptVersion, manifest.instruction,
    Object.entries(manifest.configuration).sort(([left], [right]) => left.localeCompare(right)), manifest.inputTokenAllowance, manifest.reservedMicros,
    manifest.sources.map(source => [source.kind, source.itemId, source.versionId, source.number, source.title, source.body]),
    manifest.preparationChecks?.map(preparationCheckValues) ?? null,
    manifest.expressionChecks?.map(check => [preparationCheckValues(check.expression), check.factChecks.map(preparationCheckValues)]) ?? null,
    manifest.materialDraftSources?.map(source => [source.excerptId, source.attachmentId, source.fileName, source.sha256, source.origin,
      source.runId, source.segmentId, source.locator, source.text, source.startOffset, source.endOffset]) ?? null,
    manifest.training ?? null, manifest.evaluation ?? null, manifest.contextChecks ?? null, manifest.materialQa ?? null,
    manifest.expressionType ?? null, manifest.preparationPlan ?? null, manifest.trainingDraft ?? null,
    manifest.comparisonInput ?? null, manifest.comparisonChecks ?? null,
    manifest.reviewDraft == null ? null : aiReviewDraftInputFingerprint(manifest.reviewDraft), manifest.cardDraftType ?? null,
    ...(manifest.factDraft == null ? [] : [aiFactDraftInputFingerprint(manifest.factDraft)]),
    ...(manifest.relationEvidence == null ? [] : [['relationEvidence', aiFactDraftInputFingerprint(manifest.relationEvidence)]]),
    ...(manifest.relationAiFeedback == null ? [] : [['relationAiFeedback', relationAiFeedbackEvidenceSnapshotFingerprint(manifest.relationAiFeedback)]]),
    ...(manifest.sourceQa == null ? [] : [['sourceQa', manifest.sourceQa]]),
    ...(manifest.materialChecks == null ? [] : [['materialChecks', manifest.materialChecks.map(check => [check.excerptId, check.revision])]]),
    ...(manifest.knowledgeMaterialChecks == null ? [] : [['knowledgeMaterialChecks', manifest.knowledgeMaterialChecks.map(check => [check.excerptId, check.revision])]])])
}

export function aiRelationManifestMatches(manifest: AiManifest, requireChecks = false): boolean {
  const fields = ['purpose', 'instruction', 'sources', 'configuration', 'inputTokenAllowance', 'reservedMicros', 'promptVersion',
    'training', 'evaluation', 'contextChecks', 'materialQa', 'preparationChecks', 'materialDraftSources',
    'expressionType', 'preparationPlan', 'trainingDraft', 'expressionChecks', 'comparisonInput', 'comparisonChecks', 'reviewDraft', 'cardDraftType', 'factDraft', 'relationEvidence', 'relationAiFeedback', 'materialChecks', 'knowledgeMaterialChecks']
  if (!manifest || Array.isArray(manifest) || Object.keys(manifest).some(key => !fields.includes(key))
    || manifest.purpose !== 'RELATION_DRAFT'
    || (manifest.relationAiFeedback != null ? manifest.reviewDraft != null || manifest.relationEvidence != null
        || manifest.promptVersion !== 'relation-ai-feedback-draft-1' || !aiRelationAiFeedbackInputValid(manifest.relationAiFeedback)
      : manifest.reviewDraft != null ? manifest.relationEvidence != null || manifest.promptVersion !== 'relation-review-draft-1' || !aiReviewDraftInputValid(manifest.reviewDraft)
        : manifest.relationEvidence == null ? manifest.promptVersion !== 'relation-draft-1'
          : manifest.promptVersion !== 'relation-evidence-draft-1' || !aiRelationEvidenceInputValid(manifest.relationEvidence))
    || !aiPreparationPlanTextValid(manifest.instruction, 1000)
    || manifest.relationAiFeedback == null && manifest.relationEvidence == null && manifest.reviewDraft == null && /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]$/u.test(manifest.instruction)
    || !manifest.configuration || typeof manifest.configuration !== 'object' || Array.isArray(manifest.configuration)
    || !Number.isSafeInteger(manifest.inputTokenAllowance) || manifest.inputTokenAllowance < 1
    || !Number.isSafeInteger(manifest.reservedMicros) || manifest.reservedMicros < 0
    || !Array.isArray(manifest.sources) || manifest.sources.length < 2 || manifest.sources.length > 5
    || manifest.training != null || manifest.evaluation != null || manifest.contextChecks != null || manifest.materialQa != null
    || manifest.preparationChecks != null || manifest.materialDraftSources != null || manifest.expressionType != null
    || manifest.preparationPlan != null || manifest.trainingDraft != null || manifest.expressionChecks != null
    || manifest.comparisonInput != null || manifest.comparisonChecks != null || manifest.cardDraftType != null || manifest.factDraft != null
    || !aiMaterialChecksMatch(manifest) || !aiKnowledgeMaterialChecksMatch(manifest, requireChecks)) return false
  // 知识端点与直接依据分别核验；新增用途字段不能自动扩大外发范围。
  const sourceFields = ['itemId', 'versionId', 'number', 'kind', 'title', 'body']
  if (!manifest.sources.every(source => source != null && Object.keys(source).every(key => sourceFields.includes(key))
    && (source.kind === 'CONCEPT' || source.kind === 'NOTE') && validSourceId(source.itemId) && validSourceId(source.versionId)
    && Number.isSafeInteger(source.number) && source.number > 0
    && relationText(source.title, 200) && relationText(source.body, 100000))) return false
  return new Set(manifest.sources.map(source => source.itemId)).size === manifest.sources.length
    && new Set(manifest.sources.map(source => source.versionId)).size === manifest.sources.length
    && manifest.sources.filter(source => source.kind === 'CONCEPT').length >= 2
}

const relationFields = (value: unknown, required: readonly string[], optional: readonly string[] = []): boolean =>
  Boolean(value && typeof value === 'object' && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key))
    && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
const relationTypes = ['PRECEDES', 'PART_OF', 'APPLIED_IN', 'RELATED', 'CONTRASTS']
const relationTimestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
// 旧候选与初建决定沿用 Java isBlank；补充理由在写入时另按 Unicode 空白全集检查。
const relationText = (value: unknown, maximum: number): value is string => typeof value === 'string'
  && aiPreparationPlanTextValid(value, maximum)
  && /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
const relationPairValid = (source: string, target: string, type: string): boolean => validSourceId(source) && validSourceId(target)
  && source !== target && relationTypes.includes(type) && (!['RELATED', 'CONTRASTS'].includes(type) || source < target)

export function aiRelationDecisionValid(value: unknown): value is AiRelationDecision {
  const decision = value as AiRelationDecision
  if (!relationFields(decision, ['candidateId', 'status', 'relationId', 'content', 'createdAt'], ['supplementId'])
    || typeof decision.candidateId !== 'string' || !/^r([1-9]|10)$/.test(decision.candidateId) || !relationTimestamp(decision.createdAt)
    || decision.supplementId != null && !validSourceId(decision.supplementId)) return false
  if (decision.status === 'IGNORED') return decision.relationId === null && decision.content === null && decision.supplementId == null
  const content = decision.content
  return decision.status === 'ADOPTED' && typeof decision.relationId === 'string' && validSourceId(decision.relationId)
    && content !== null && relationFields(content, ['sourceItemId', 'targetItemId', 'type', 'reason'])
    && relationPairValid(content.sourceItemId, content.targetItemId, content.type) && relationText(content.reason, 1000)
    && (decision.supplementId == null || /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(content.reason))
}

export function aiRelationDecisionFingerprint(decision: AiRelationDecision): string {
  const content = decision.content
  // 缺省尾字段与显式 null 等价，历史初建回执不会因升级增加字段而被误判为改写。
  return JSON.stringify([decision.candidateId, decision.status, decision.relationId,
    content == null ? null : [content.sourceItemId, content.targetItemId, content.type, content.reason], decision.createdAt, decision.supplementId ?? null])
}

export function aiRelationCandidateFingerprint(candidate: AiRelationCandidate | null | undefined): string {
  // 仅用于严格校验后的快照比较；旧引用尾字段缺省与 null 等价，数组顺序和完整引文必须保留。
  return JSON.stringify(candidate == null ? null : [candidate.summary, candidate.limitations, candidate.validationNote,
    candidate.relations.map(item => [item.id, item.sourceItemId, item.targetItemId, item.type, item.reason,
      item.citations.map(citation => [citation.itemId, citation.versionId, citation.start, citation.end, citation.quote]),
      item.materialCitations?.map(citation => [citation.excerptId, citation.start, citation.end, citation.quote]) ?? null,
      item.trainingCitations?.map(citation => [citation.field, citation.elementId, citation.start, citation.end, citation.quote]) ?? null,
      ...(item.reviewCitations == null ? [] : [item.reviewCitations.map(citation => [citation.field, citation.start, citation.end, citation.quote])])])])
}

function relationCandidateValid(candidate: AiRelationCandidate, manifest: AiManifest): boolean {
  const direct = manifest.relationEvidence, review = manifest.reviewDraft, aiFeedback = manifest.relationAiFeedback
  const candidateText = (value: unknown, maximum: number) => relationText(value, maximum)
    && (direct == null && review == null && aiFeedback == null || /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value))
  if (!relationFields(candidate, ['summary', 'limitations', 'relations', 'validationNote'])
    || !candidateText(candidate.summary, 1000) || !candidateText(candidate.limitations, 2000)
    || candidate.validationNote !== (aiFeedback != null ? aiRelationAiFeedbackValidationNote : review != null ? aiRelationReviewValidationNote : direct == null ? 'AI 未审关系候选，结构、所选概念端点与引用原文区间已核对；不证明关系语义正确或原文充分支持。候选需逐项审阅，不会自动进入正式星图。' : aiRelationEvidenceValidationNote)
    || !Array.isArray(candidate.relations) || candidate.relations.length > 10) return false
  const concepts = new Set(manifest.sources.filter(source => source.kind === 'CONCEPT').map(source => source.itemId))
  const identities = new Set<string>()
  return candidate.relations.every((suggestion, index) => {
    if (!relationFields(suggestion, ['id', 'sourceItemId', 'targetItemId', 'type', 'reason', 'citations'], ['materialCitations', 'trainingCitations', 'reviewCitations'])
      || suggestion.id !== `r${index + 1}` || !relationPairValid(suggestion.sourceItemId, suggestion.targetItemId, suggestion.type)
      || !concepts.has(suggestion.sourceItemId) || !concepts.has(suggestion.targetItemId) || !candidateText(suggestion.reason, 1000)
      || !Array.isArray(suggestion.citations) || suggestion.citations.length > 6
      || (aiFeedback != null ? !aiRelationAiFeedbackSuggestionCitationsValid(suggestion, aiFeedback) : review != null ? !aiRelationReviewSuggestionCitationsValid(suggestion, review)
        : suggestion.reviewCitations != null || (direct == null ? suggestion.materialCitations != null || suggestion.trainingCitations != null || suggestion.citations.length < 1
          : !aiRelationEvidenceSuggestionCitationsValid(suggestion, direct)))) return false
    const identity = JSON.stringify([suggestion.sourceItemId, suggestion.targetItemId, suggestion.type])
    if (identities.has(identity)) return false
    identities.add(identity)
    const positions = new Set<string>()
    return suggestion.citations.every(citation => {
      if (!relationFields(citation, ['itemId', 'versionId', 'start', 'end', 'quote']) || !validSourceId(citation.itemId) || !validSourceId(citation.versionId)
        || !candidateText(citation.quote, 1000) || Array.from(citation.quote).length > 500) return false
      const source = manifest.sources.find(item => item.itemId === citation.itemId && item.versionId === citation.versionId)
      const position = JSON.stringify([citation.itemId, citation.versionId, citation.start, citation.end])
      if (!source || !aiTrainingDraftRangeMatches(source.body, citation.start, citation.end, citation.quote) || positions.has(position)) return false
      positions.add(position)
      return true
    })
  })
}

export function aiRelationTaskMatches(value: unknown): value is AiTask {
  try {
    const task = value as AiTask
    const other = ['decision', 'questionCandidate', 'questionDecision', 'followUp', 'evaluation', 'evaluationReport', 'evaluationDecisions',
      'materialAnswer', 'expressionCandidate', 'expressionDecision', 'preparationPlanCandidate', 'preparationPlanDecision',
      'comparison', 'comparisonCandidate', 'cardCandidate', 'cardDecision', 'jdRequirementsCandidate', 'jdRequirementsDecision',
      'factCandidate', 'factDecision', 'rewrite', 'rewriteCandidate'] as const
    if (!relationFields(task, ['task', 'manifest', 'attempts', 'candidate', 'sourcesCurrent', 'relationCandidate', 'relationDecisions'], other)
      || !aiRelationManifestMatches(task.manifest) || typeof task.sourcesCurrent !== 'boolean'
      || task.candidate !== null || other.some(key => task[key] != null) || !Array.isArray(task.relationDecisions)) return false
    const summary = task.task
    if (!relationFields(summary, ['id', 'purpose', 'status', 'revision', 'title', 'provider', 'model', 'currency', 'attemptCount', 'createdAt', 'updatedAt', 'errorCode'])
      || !validSourceId(summary.id) || summary.purpose !== 'RELATION_DRAFT' || !Object.hasOwn(aiStatusLabels, summary.status)
      || !Number.isSafeInteger(summary.revision) || summary.revision < 0 || !relationText(summary.title, 200)
      || summary.title !== task.manifest.sources[0]?.title || !relationText(summary.provider, 240)
      || !relationText(summary.model, 400) || typeof summary.currency !== 'string' || !/^[A-Z]{3}$/.test(summary.currency)
      || summary.provider !== task.manifest.configuration.provider || summary.model !== task.manifest.configuration.model
      || summary.currency !== task.manifest.configuration.currency || !Number.isSafeInteger(summary.attemptCount)
      || summary.attemptCount < 1 || summary.attemptCount > 3 || !relationTimestamp(summary.createdAt) || !relationTimestamp(summary.updatedAt)
      || summary.errorCode !== null && !relationText(summary.errorCode, Number.MAX_SAFE_INTEGER)
      || !Array.isArray(task.attempts) || task.attempts.length !== summary.attemptCount
      || task.attempts.some(attempt => !attempt || !validSourceId(attempt.id) || !Number.isSafeInteger(attempt.number)
        || attempt.number < 1 || attempt.number > summary.attemptCount || !Object.hasOwn(aiStatusLabels, attempt.status)
        || !Object.hasOwn(aiFeeLabels, attempt.feeStatus) || !Number.isSafeInteger(attempt.reservedMicros) || attempt.reservedMicros < 0
        || attempt.accountedMicros !== null && (!Number.isSafeInteger(attempt.accountedMicros) || attempt.accountedMicros < 0))
      || new Set(task.attempts.map(attempt => attempt.id)).size !== task.attempts.length
      || new Set(task.attempts.map(attempt => attempt.number)).size !== task.attempts.length) return false
    if (summary.status !== 'SUCCEEDED') return task.relationCandidate === null && task.relationDecisions.length === 0
    const candidate = task.relationCandidate
    if (!candidate || !relationCandidateValid(candidate, task.manifest)) return false
    const decided = new Set<string>(), supplements = new Set<string>()
    return task.relationDecisions.every(decision => {
      if (!aiRelationDecisionValid(decision) || decided.has(decision.candidateId)) return false
      const suggestion = candidate.relations.find(item => item.id === decision.candidateId)
      if (!suggestion) return false
      decided.add(decision.candidateId)
      if (decision.supplementId != null) {
        if (supplements.has(decision.supplementId)) return false
        supplements.add(decision.supplementId)
      }
      const content = decision.content
      return content === null || (content.sourceItemId === suggestion.sourceItemId && content.targetItemId === suggestion.targetItemId)
        || (content.sourceItemId === suggestion.targetItemId && content.targetItemId === suggestion.sourceItemId)
    })
  } catch { return false }
}

export function aiMaterialDraftSourcesMatch(manifest: AiManifest, requireChecks = false): boolean {
  if (manifest.trainingDraft != null || manifest.comparisonInput != null || manifest.comparisonChecks != null || manifest.reviewDraft != null || manifest.cardDraftType != null || manifest.factDraft != null || manifest.relationEvidence != null || manifest.relationAiFeedback != null
    || manifest.purpose === 'MEMORY_CARD_DRAFT' || manifest.purpose === 'JD_REQUIREMENT_DRAFT' || manifest.purpose === 'FACT_DRAFT'
    || !aiMaterialChecksMatch(manifest, requireChecks) || !aiKnowledgeMaterialChecksMatch(manifest, requireChecks)) return false
  const excerpts = manifest.materialDraftSources ?? []
  const prompt = aiDraftPromptVersion(manifest)
  const materialPrompt = ['material-knowledge-draft-1', 'material-question-draft-1', 'expression-source-material-knowledge-draft-1', 'expression-source-material-question-draft-1'].includes(manifest.promptVersion)
  if (!excerpts.length) return !materialPrompt
  if (!prompt || manifest.promptVersion !== prompt || manifest.sources.length + excerpts.length > 5) return false
  if (new Set(excerpts.map(source => source.excerptId)).size !== excerpts.length) return false
  return excerpts.every(source => Boolean(aiMaterialSourceRoute(source)) && Boolean(source.text.trim())
    && (source.origin === 'MANUAL' ? source.startOffset === null && source.endOffset === null
      : source.startOffset !== null && source.endOffset !== null && Number.isSafeInteger(source.startOffset)
        && Number.isSafeInteger(source.endOffset) && source.startOffset >= 0 && source.endOffset > source.startOffset
        && source.endOffset - source.startOffset === source.text.length))
}

export function aiPreparationChecksMatch(manifest: AiManifest): boolean {
  if (manifest.trainingDraft != null || manifest.reviewDraft != null || manifest.cardDraftType != null || manifest.factDraft != null || manifest.relationEvidence != null || manifest.relationAiFeedback != null || !aiExpressionSourceChecksMatch(manifest)) return false
  if (new Set(manifest.sources.map(source => source.itemId)).size !== manifest.sources.length) return false
  if (manifest.sources.some(source => !aiSourceRoute(source))) return false
  const preparations = manifest.sources.filter(source => isAiPreparationKind(source.kind))
  if (!preparations.length) return !manifest.preparationChecks?.length
  const checks = manifest.preparationChecks
  if (!checks || checks.length !== preparations.length) return false
  return preparations.every((source, index) => {
    const check = checks[index]
    return check?.kind === source.kind && check.itemId === source.itemId && check.versionId === source.versionId
      && check.state === 'CURRENT' && validSourceGeneration(check.sourceGeneration)
      && (check.kind === 'FACT' ? validSourceGeneration(check.projectGeneration) : check.projectGeneration === null)
  })
}

export function aiFactResponsibility(source: Pick<AiSource, 'kind' | 'body'> | undefined): Exclude<FactContent['responsibility'], 'UNVERIFIED'> | undefined {
  if (source?.kind !== 'FACT') return undefined
  try {
    const value = JSON.parse(source.body) as { responsibility?: unknown } | null
    const responsibility = value?.responsibility
    return responsibility === 'OWNER' || responsibility === 'PARTICIPANT' || responsibility === 'FAMILIAR' ? responsibility : undefined
  } catch { return undefined }
}

export function aiExpressionManifestMatches(manifest: AiManifest): boolean {
  return manifest.purpose === 'EXPRESSION_DRAFT' && manifest.promptVersion === 'expression-draft-1'
    && expressionOptions.some(option => option.value === manifest.expressionType)
    && manifest.sources.length >= 1 && manifest.sources.length <= 5 && aiPreparationChecksMatch(manifest)
    && manifest.sources.every(source => source.kind === 'FACT' && Boolean(source.itemId && source.versionId && source.title.trim())
      && Number.isSafeInteger(source.number) && source.number > 0 && aiFactResponsibility(source) !== undefined)
    && manifest.training == null && manifest.evaluation == null && manifest.contextChecks == null
    && manifest.materialQa == null && manifest.materialDraftSources == null && manifest.preparationPlan == null && manifest.trainingDraft == null && manifest.expressionChecks == null
    && manifest.comparisonInput == null && manifest.comparisonChecks == null && manifest.reviewDraft == null && manifest.cardDraftType == null && manifest.factDraft == null && manifest.relationEvidence == null && manifest.relationAiFeedback == null
    && manifest.materialChecks == null
}

export function aiPreparationPlanDays(startDate: string, endDate: string): number {
  if (!validPreparationTaskDate(startDate) || !validPreparationTaskDate(endDate)) return 0
  // 仅计算日历天数；用户的日期字符串与时区保持原样，不换算成实际日程。
  const ordinal = (date: string) => {
    const [year, month, day] = date.split('-').map(Number) as [number, number, number]
    return Date.UTC(year, month - 1, day) / 86_400_000
  }
  return ordinal(endDate) - ordinal(startDate) + 1
}

export function aiPreparationPlanTextValid(value: string, maximum: number): boolean {
  return typeof value === 'string' && value.length <= maximum && !value.includes('\0')
    && !Array.from(value).some(character => { const code = character.codePointAt(0)!; return code >= 0xd800 && code <= 0xdfff })
}

export function aiPreparationPlanInputProblem(plan: AiPreparationPlanInput): string {
  const days = aiPreparationPlanDays(plan.startDate, plan.endDate)
  if (days < 1 || days > 90) return '请填写有效的起止日期，年份为 1000–9999，包含首尾 1–90 天。'
  if (typeof plan.zoneId !== 'string' || !plan.zoneId || plan.zoneId.trim() !== plan.zoneId || /^[+-]/.test(plan.zoneId) || !validZone(plan.zoneId)) return '请填写 IANA 时区（如 Asia/Shanghai）或 UTC，不能使用固定偏移。'
  if (!Number.isSafeInteger(plan.totalMinutes) || plan.totalMinutes < 1 || plan.totalMinutes > Math.min(days * 1440, 129600)) return `整个区间的预计投入须为 1–${Math.min(days * 1440, 129600)} 的整数分钟。`
  if (!aiPreparationPlanTextValid(plan.gapSummary, 2000)) return '自述缺口最多 2000 个 UTF-16 码元，不能含 NUL 或不完整的 Unicode 字符。'
  return ''
}

export function aiPreparationPlanManifestMatches(manifest: AiManifest): boolean {
  const source = manifest.sources[0]
  const uuid = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
  return manifest.purpose === 'PREPARATION_PLAN' && manifest.promptVersion === 'preparation-plan-1'
    && aiPreparationPlanTextValid(manifest.instruction, 1000)
    && manifest.sources.length === 1 && source?.kind === 'TARGET' && uuid(source.itemId) && uuid(source.versionId)
    && Number.isSafeInteger(source.number) && source.number > 0
    && typeof source.title === 'string' && Boolean(source.title.trim()) && typeof source.body === 'string' && Boolean(source.body.trim())
    && aiPreparationChecksMatch(manifest) && manifest.preparationChecks?.length === 1
    && manifest.preparationPlan != null && !aiPreparationPlanInputProblem(manifest.preparationPlan)
    && manifest.training == null && manifest.evaluation == null && manifest.contextChecks == null
    && manifest.materialQa == null && manifest.materialDraftSources == null && manifest.expressionType == null && manifest.trainingDraft == null && manifest.expressionChecks == null
    && manifest.comparisonInput == null && manifest.comparisonChecks == null && manifest.reviewDraft == null && manifest.cardDraftType == null && manifest.factDraft == null && manifest.relationEvidence == null && manifest.relationAiFeedback == null
    && manifest.materialChecks == null
}

export function aiTrainingDraftRangeMatches(text: string, start: number, end: number, quote: string): boolean {
  const splitPair = (position: number) => position > 0 && position < text.length
    && /[\uD800-\uDBFF]/.test(text.charAt(position - 1)) && /[\uDC00-\uDFFF]/.test(text.charAt(position))
  return Number.isSafeInteger(start) && Number.isSafeInteger(end) && start >= 0 && end > start && end <= text.length
    && !splitPair(start) && !splitPair(end) && text.slice(start, end) === quote
}

export function aiTrainingDraftManifestMatches(manifest: AiManifest): boolean {
  const input = manifest.trainingDraft
  const uuid = (value: string | null) => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
  const text = (value: string, maximum: number, nonblank = false) => aiPreparationPlanTextValid(value, maximum * 2)
    && Array.from(value).length <= maximum && (!nonblank || Boolean(value.trim()))
  if (manifest.purpose !== 'KNOWLEDGE_DRAFT' || manifest.promptVersion !== 'training-knowledge-draft-1'
    || !aiPreparationPlanTextValid(manifest.instruction, 1000) || !Array.isArray(manifest.sources) || manifest.sources.length !== 0 || !input
    || manifest.training != null || manifest.evaluation != null || manifest.contextChecks != null || manifest.materialQa != null
    || manifest.preparationChecks != null || manifest.materialDraftSources != null || manifest.expressionType != null || manifest.preparationPlan != null || manifest.expressionChecks != null
    || manifest.comparisonInput != null || manifest.comparisonChecks != null || manifest.reviewDraft != null || manifest.cardDraftType != null || manifest.factDraft != null || manifest.relationEvidence != null || manifest.relationAiFeedback != null
    || manifest.materialChecks != null || !aiKnowledgeMaterialChecksMatch(manifest)
    || !uuid(input.sessionId) || !uuid(input.questionId) || !uuid(input.answerId)
    || !Number.isSafeInteger(input.answerNumber) || input.answerNumber < 1 || input.answerNumber > 100
    || !['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(input.answerCondition)
    || (input.sourceQuestionId === null ? input.sourceVersionId !== null : !uuid(input.sourceQuestionId) || !uuid(input.sourceVersionId))) return false
  const question = input.question
  if (!question || !Object.hasOwn(questionTypeLabels, question.type) || !text(question.title, 200, true)
    || !text(question.prompt, 10000, true) || !text(question.conditions, 5000) || !text(question.technology, 2000) || !text(question.scenario, 2000)
    || !Array.isArray(input.fragments) || input.fragments.length < 1 || input.fragments.length > 8 || !Array.isArray(input.feedback)) return false
  for (const [index, fragment] of input.fragments.entries()) {
    if (!fragment || !['ANSWER_TEXT', 'ANSWER_NODE_LABEL', 'ANSWER_NODE_DESCRIPTION', 'ANSWER_EDGE_LABEL'].includes(fragment.field)
      || !Number.isSafeInteger(fragment.start) || !Number.isSafeInteger(fragment.end) || fragment.start < 0 || fragment.end <= fragment.start
      || !aiPreparationPlanTextValid(fragment.quote, 8000) || !fragment.quote.trim() || fragment.end - fragment.start !== fragment.quote.length
      || (fragment.field === 'ANSWER_TEXT' ? fragment.elementId !== null : !uuid(fragment.elementId))) return false
    if (input.fragments.slice(0, index).some(other => other.field === fragment.field && other.elementId === fragment.elementId
      && other.start < fragment.end && fragment.start < other.end)) return false
  }
  if (input.feedbackTaskId === null) return input.feedbackLevel === null && input.feedbackLimitations === null && input.feedback.length === 0
  if (!uuid(input.feedbackTaskId) || input.feedbackLevel !== 'AI_UNREVIEWED' || !text(input.feedbackLimitations ?? '', 2000, true)
    || input.feedback.length < 1 || input.feedback.length > 5) return false
  const feedbackIds = new Set<string>()
  let total = Array.from(input.feedbackLimitations!).length
  for (const item of input.feedback) {
    if (!item || !/^f([1-9]|1[0-2])$/.test(item.id) || feedbackIds.has(item.id) || !Object.hasOwn(questionDimensionLabels, item.dimension)
      || !['MENTIONED', 'NOT_MENTIONED', 'UNCERTAIN'].includes(item.coverage)
      || !text(item.observation, 2000, true) || !text(item.condition, 1000) || !text(item.basis, 1000, true)
      || !text(item.limitation, 1000, true) || !text(item.suggestion, 1000, true) || !Array.isArray(item.citations) || item.citations.length > 6) return false
    feedbackIds.add(item.id)
    const positions = new Set<string>()
    let answerEvidence = false
    for (const citation of item.citations) {
      if (!citation || !['ANSWER_TEXT', 'ANSWER_NODE_LABEL', 'ANSWER_NODE_DESCRIPTION', 'ANSWER_EDGE_LABEL', 'QUESTION_PROMPT', 'QUESTION_CONDITIONS', 'REFERENCE', 'CHECKPOINT'].includes(citation.kind)
        || !Number.isSafeInteger(citation.start) || !Number.isSafeInteger(citation.end) || citation.start < 0 || citation.end <= citation.start
        || !text(citation.quote, 500, true) || citation.end - citation.start !== citation.quote.length) return false
      const element = ['ANSWER_NODE_LABEL', 'ANSWER_NODE_DESCRIPTION', 'ANSWER_EDGE_LABEL', 'CHECKPOINT'].includes(citation.kind)
      const position = JSON.stringify([citation.kind, citation.elementId, citation.start, citation.end])
      if ((element ? !uuid(citation.elementId) : citation.elementId !== null) || positions.has(position)) return false
      positions.add(position)
      answerEvidence ||= citation.kind.startsWith('ANSWER_')
      total += Array.from(citation.quote).length
    }
    if (item.coverage === 'MENTIONED' && !answerEvidence || item.coverage === 'NOT_MENTIONED' && (answerEvidence || !item.citations.length)) return false
    total += [item.observation, item.condition, item.basis, item.limitation, item.suggestion].reduce((sum, value) => sum + Array.from(value).length, 0)
  }
  return total <= 24000
}

export function aiTrainingDraftInputFingerprint(input: AiTrainingDraftInput): string {
  // 比较固定原文和顺序，不能只核对来源 ID 后接纳被替换的题面、反馈或引用。
  return JSON.stringify([input.sessionId, input.questionId, input.answerId, input.answerNumber, input.answerCondition,
    input.sourceQuestionId, input.sourceVersionId,
    [input.question.title, input.question.prompt, input.question.conditions, input.question.technology, input.question.scenario, input.question.type],
    input.fragments.map(item => [item.field, item.elementId, item.start, item.end, item.quote]),
    input.feedbackTaskId, input.feedbackLevel, input.feedbackLimitations,
    input.feedback.map(item => [item.id, item.dimension, item.coverage, item.observation, item.condition, item.basis, item.limitation, item.suggestion,
      item.citations.map(citation => [citation.kind, citation.elementId, citation.start, citation.end, citation.quote])])])
}

export function aiTaskStatusLabel(status: AiStatus, purpose: AiPurpose): string {
  if (status === 'SUCCEEDED' && purpose === 'FOLLOW_UP') return '已加入训练'
  if (status === 'SUCCEEDED' && purpose === 'ANSWER_EVALUATION') return '证据报告已生成'
  if (status === 'SUCCEEDED' && purpose === 'ANSWER_REWRITE') return '改写参考已生成'
  if (status === 'SUCCEEDED' && purpose === 'ANSWER_COMPARISON') return '比较候选已生成'
  if (status === 'SUCCEEDED' && purpose === 'MEMORY_CARD_DRAFT') return '回忆卡候选已生成'
  if (status === 'SUCCEEDED' && purpose === 'JD_REQUIREMENT_DRAFT') return '岗位要求候选已生成'
  if (status === 'SUCCEEDED' && purpose === 'FACT_DRAFT') return '事实候选已生成'
  if (status === 'SUCCEEDED' && (purpose === 'MATERIAL_QA' || purpose === 'SOURCE_QA')) return '问答结果已生成'
  return aiStatusLabels[status]
}

export function formatAiAmount(micros: number, currency: string): string {
  if (!Number.isSafeInteger(micros)) return '金额超出可显示范围，请核对服务器记录'
  if (micros < 0) return '未配置'
  // 金额始终按整数微单位处理，避免小额费用被浮点运算或两位小数显示抹掉。
  const amount = BigInt(micros)
  const absolute = amount < 0n ? -amount : amount
  const fraction = (absolute % 1000000n).toString().padStart(6, '0').replace(/0+$/, '')
  return `${currency || '未配置币种'} ${amount < 0n ? '−' : ''}${absolute / 1000000n}${fraction ? `.${fraction}` : '.00'}`
}

export function parseAiMicros(value: string): number | null {
  const input = value.trim()
  if (!/^\d+$/.test(input)) return null
  const amount = Number(input)
  return Number.isSafeInteger(amount) && amount >= 0 ? amount : null
}

export const aiDate = (value: string) => new Date(value).toLocaleString('zh-CN')
