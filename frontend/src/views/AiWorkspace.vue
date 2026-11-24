<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQuery } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../api/client'
import type { AiExpressionDraftPreviewRequest, AiPreparationPlanPreviewRequest, AiPreviewRequest, AiSettings, AiTask, AiTaskPage, AiTrainingDraftPreviewRequest } from '../api/ai'
import type { MemoryKind } from '../api/memory'
import type { AiFactDraftKind } from '../api/aiFactDraft'
import { aiDate, aiPurposeLabels, aiTaskStatusLabel, formatAiAmount } from '../api/ai'
import { queryClient } from '../api/queryClient'
import { useWorkspaceNavigation } from '../navigation/workspaceNavigation'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useAiUpdatesStore } from '../state/aiUpdates'
import RIcon from '../replica/RIcon.vue'

const AiTaskCreate = defineAsyncComponent(() => import('../components/ai/AiTaskCreate.vue'))
const AiMaterialQaCreate = defineAsyncComponent(() => import('../components/ai/AiMaterialQaCreate.vue'))
const AiExpressionCreate = defineAsyncComponent(() => import('../components/ai/AiExpressionCreate.vue'))
const AiPreparationPlanCreate = defineAsyncComponent(() => import('../components/ai/AiPreparationPlanCreate.vue'))
const AiTrainingDraftCreate = defineAsyncComponent(() => import('../components/ai/AiTrainingDraftCreate.vue'))
const AiReviewDraftCreate = defineAsyncComponent(() => import('../components/ai/AiReviewDraftCreate.vue'))
const AiCardDraftCreate = defineAsyncComponent(() => import('../components/ai/AiCardDraftCreate.vue'))
const AiJdRequirementsCreate = defineAsyncComponent(() => import('../components/ai/AiJdRequirementsCreate.vue'))
const AiFactDraftCreate = defineAsyncComponent(() => import('../components/ai/AiFactDraftCreate.vue'))
const AiRewriteCreate = defineAsyncComponent(() => import('../components/ai/AiRewriteCreate.vue'))
const AiRelationCreate = defineAsyncComponent(() => import('../components/ai/AiRelationCreate.vue'))
const AiRelationEvidenceCreate = defineAsyncComponent(() => import('../components/ai/AiRelationEvidenceCreate.vue'))
const AiRelationReviewCreate = defineAsyncComponent(() => import('../components/ai/AiRelationReviewCreate.vue'))
const AiTaskDetail = defineAsyncComponent(() => import('../components/ai/AiTaskDetail.vue'))
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const aiUpdates = useAiUpdatesStore()
const route = useRoute()
const router = useRouter()
const { returnTo, withReturn } = useWorkspaceNavigation()
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const configurationOpen = ref(false)
const creationOpen = ref(false)
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const creating = computed(() => route.query.create === '1')
const creatingSourceQa = computed(() => creating.value && (route.query.purpose === 'SOURCE_QA'
  || Array.isArray(route.query.purpose) && route.query.purpose.includes('SOURCE_QA')))
const creatingMaterialQa = computed(() => creating.value && route.query.purpose === 'MATERIAL_QA')
const creatingExpression = computed(() => creating.value && route.query.purpose === 'EXPRESSION_DRAFT')
const creatingPreparationPlan = computed(() => creating.value && route.query.purpose === 'PREPARATION_PLAN')
const creatingTrainingDraft = computed(() => creating.value && route.query.purpose === 'TRAINING_DRAFT')
const creatingReviewDraft = computed(() => creating.value && route.query.purpose === 'REVIEW_DRAFT')
const creatingCardDraft = computed(() => creating.value && route.query.purpose === 'MEMORY_CARD_DRAFT')
const creatingJdRequirements = computed(() => creating.value && route.query.purpose === 'JD_REQUIREMENT_DRAFT')
const creatingFactDraft = computed(() => creating.value && route.query.purpose === 'FACT_DRAFT')
const creatingRewrite = computed(() => creating.value && (route.query.purpose === 'ANSWER_REWRITE'
  || Array.isArray(route.query.purpose) && route.query.purpose.includes('ANSWER_REWRITE')))
const creatingRelation = computed(() => creating.value && route.query.purpose === 'RELATION_DRAFT')
const creatingRelationEvidence = computed(() => creatingRelation.value && route.query.relationMode === 'EVIDENCE')
const creatingRelationReview = computed(() => creatingRelation.value && route.query.relationMode === 'REVIEW')
const creationIdentity = computed(() => createIdentity(route.query))
const expressionFact = computed<AiExpressionDraftPreviewRequest['factSources'][number] | undefined>(() =>
  typeof route.query.factItemId === 'string' && typeof route.query.factVersionId === 'string'
    && route.query.factItemId && route.query.factVersionId
    ? { itemId: route.query.factItemId, versionId: route.query.factVersionId } : undefined)
const preparationTarget = computed<AiPreparationPlanPreviewRequest['target'] | undefined>(() =>
  typeof route.query.targetItemId === 'string' && typeof route.query.targetVersionId === 'string'
    && route.query.targetItemId && route.query.targetVersionId
    ? { itemId: route.query.targetItemId, versionId: route.query.targetVersionId } : undefined)
const questionTarget = computed(() => route.query.purpose === 'QUESTION_DRAFT' ? preparationTarget.value : undefined)
const jdTarget = computed(() => {
  if (route.query.targetItemId === undefined && route.query.targetVersionId === undefined) return undefined
  return { itemId: typeof route.query.targetItemId === 'string' ? route.query.targetItemId : '',
    versionId: typeof route.query.targetVersionId === 'string' ? route.query.targetVersionId : '' }
})
const trainingSource = computed<(Pick<AiTrainingDraftPreviewRequest, 'sessionId' | 'questionId' | 'answerId'> & { evaluationTaskId?: string }) | undefined>(() => {
  const { sessionId, questionId, answerId, evaluationTaskId } = route.query
  if (typeof sessionId !== 'string' || !sessionId || typeof questionId !== 'string' || !questionId || typeof answerId !== 'string' || !answerId) return undefined
  return { sessionId, questionId, answerId, evaluationTaskId: typeof evaluationTaskId === 'string' && evaluationTaskId ? evaluationTaskId : undefined }
})
const reviewSource = computed<{ kind: 'RECOLLECTION'; recollectionId: string; versionId: string; expectedRevision: number }
  | { kind: 'SELF_CHECK' | 'EXTERNAL_FEEDBACK'; feedbackId: string; feedbackRevision: number; answer: { sessionId: string; questionId: string; answerId: string } } | undefined>(() => {
  const value = (key: string) => typeof route.query[key] === 'string' ? route.query[key] : undefined
  const kind = value('reviewKind')
  if (kind === 'RECOLLECTION') {
    const recollectionId = value('recollectionId'), versionId = value('versionId'), revision = value('sourceRevision')
    if (!recollectionId || !versionId || revision === undefined || !/^\d+$/.test(revision)) return undefined
    return { kind, recollectionId, versionId, expectedRevision: Number(revision) }
  }
  if (kind !== 'SELF_CHECK' && kind !== 'EXTERNAL_FEEDBACK') return undefined
  const feedbackId = value('feedbackId'), revision = value('feedbackRevision')
  const sessionId = value('sessionId'), questionId = value('questionId'), answerId = value('answerId')
  if (!feedbackId || revision === undefined || !/^\d+$/.test(revision) || !sessionId || !questionId || !answerId) return undefined
  return { kind, feedbackId, feedbackRevision: Number(revision), answer: { sessionId, questionId, answerId } }
})
const cardKind = computed<MemoryKind | undefined>(() => route.query.cardKind === 'QA' || route.query.cardKind === 'CLOZE' ? route.query.cardKind : undefined)
const cardSource = computed(() => {
  if (route.query.sourceItemId === undefined && route.query.sourceVersionId === undefined) return undefined
  return { itemId: typeof route.query.sourceItemId === 'string' ? route.query.sourceItemId : '',
    versionId: typeof route.query.sourceVersionId === 'string' ? route.query.sourceVersionId : '' }
})
const factSource = computed<{ kind?: AiFactDraftKind; training?: { sessionId: string; questionId: string; answerId: string }; error: string }>(() => {
  const query = route.query, allowed = ['create', 'purpose', 'factSourceKind', 'sessionId', 'questionId', 'answerId', 'from']
  if (Object.keys(query).some(key => !allowed.includes(key))) return { error: '事实候选入口含未支持的参数，请从资料或确切提交重新进入。' }
  const hasTraining = ['sessionId', 'questionId', 'answerId'].some(key => query[key] !== undefined)
  // 仅完全没有训练身份时允许默认资料模式；残缺或非法训练链接不能静默退回资料创建。
  if (query.factSourceKind === undefined && !hasTraining || query.factSourceKind === 'MATERIAL' && !hasTraining) return { kind: 'MATERIAL', error: '' }
  if (query.factSourceKind !== 'TRAINING') return { error: '请明确指定资料模式，或用 TRAINING 模式携带完整确切提交身份。' }
  const { sessionId, questionId, answerId } = query
  const id = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
  if (!id(sessionId) || !id(questionId) || !id(answerId)) return { error: '训练事实候选需要有效的训练、题实例和确切已提交回答三个标识，请从提交记录重新进入。' }
  return { kind: 'TRAINING', training: { sessionId, questionId, answerId }, error: '' }
})
const rewriteSource = computed<{ source?: { sessionId: string; questionId: string; answerId: string }; error: string }>(() => {
  const query = route.query, allowed = ['create', 'purpose', 'sessionId', 'questionId', 'answerId', 'from']
  if (query.create !== '1' || query.purpose !== 'ANSWER_REWRITE' || Object.keys(query).some(key => !allowed.includes(key))) {
    return { error: '改写入口含重复或未支持的参数，请从确切提交重新进入。' }
  }
  const { sessionId, questionId, answerId } = query
  const id = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
  if (!id(sessionId) || !id(questionId) || !id(answerId)) return { error: '改写需要有效的训练、题实例和确切已提交回答三个标识，请从提交记录重新进入。' }
  return { source: { sessionId, questionId, answerId }, error: '' }
})
const initialPurpose = computed<AiPreviewRequest['purpose']>(() => route.query.purpose === 'QUESTION_DRAFT' ? 'QUESTION_DRAFT' : 'KNOWLEDGE_DRAFT')
const guarded = computed(() => access.checking || access.restricted || access.challenge)
const readable = computed(() => store.sessionActive && !guarded.value)
const settings = useQuery({
  queryKey: computed(() => ['ai', store.sessionEpoch, 'settings']),
  enabled: readable,
  queryFn: () => read<AiSettings>('/ai/settings'),
})
const tasks = useQuery({
  queryKey: computed(() => ['ai', store.sessionEpoch, 'tasks', cursor.value]),
  enabled: readable,
  queryFn: () => read<AiTaskPage>(`/ai/tasks?limit=20${cursor.value ? `&cursor=${encodeURIComponent(cursor.value)}` : ''}`),
})
const ready = computed(() => Boolean(settings.data.value?.configuration.enabled && settings.data.value.configuration.configured))
const configLabel = computed(() => !settings.data.value ? '正在读取配置' : !settings.data.value.configuration.enabled ? 'AI 未启用' : !settings.data.value.configuration.configured ? '配置尚未完整' : '已配置，供应商兼容待实际请求确认')
watch(() => store.sessionEpoch, () => { cursor.value = undefined; cursors.value = [] })
watch([() => route.query.create, () => route.query.item], () => { creationOpen.value = false })
function createForm(purpose: unknown) {
  if (purpose === 'SOURCE_QA' || Array.isArray(purpose) && purpose.includes('SOURCE_QA')) return 'SOURCE_QA'
  if (purpose === 'ANSWER_REWRITE' || Array.isArray(purpose) && purpose.includes('ANSWER_REWRITE')) return 'ANSWER_REWRITE'
  return purpose === 'MATERIAL_QA' || purpose === 'EXPRESSION_DRAFT' || purpose === 'PREPARATION_PLAN' || purpose === 'TRAINING_DRAFT' || purpose === 'REVIEW_DRAFT' || purpose === 'MEMORY_CARD_DRAFT' || purpose === 'JD_REQUIREMENT_DRAFT' || purpose === 'FACT_DRAFT' || purpose === 'RELATION_DRAFT' ? purpose : 'CANDIDATE'
}
function createIdentity(query: LocationQuery) {
  const form = createForm(query.purpose)
  const value = (key: string) => typeof query[key] === 'string' ? query[key] : null
  // TRAINING_DRAFT / REVIEW_DRAFT 仅选择页面表单，服务端任务用途仍为 KNOWLEDGE_DRAFT。
  const sources = form === 'TRAINING_DRAFT' ? ['sessionId', 'questionId', 'answerId', 'evaluationTaskId']
    : form === 'ANSWER_REWRITE' ? ['sessionId', 'questionId', 'answerId']
    : form === 'FACT_DRAFT' ? ['factSourceKind', 'sessionId', 'questionId', 'answerId']
    : form === 'RELATION_DRAFT' ? ['relationMode']
    : form === 'REVIEW_DRAFT' ? ['reviewKind', 'feedbackId', 'feedbackRevision', 'sessionId', 'questionId', 'answerId', 'recollectionId', 'versionId', 'sourceRevision']
      : form === 'MEMORY_CARD_DRAFT' ? ['cardKind', 'sourceItemId', 'sourceVersionId']
      : form === 'EXPRESSION_DRAFT' ? ['factItemId', 'factVersionId']
      : form === 'PREPARATION_PLAN' || form === 'JD_REQUIREMENT_DRAFT' ? ['targetItemId', 'targetVersionId']
      : form === 'CANDIDATE' ? ['targetItemId', 'targetVersionId'] : []
  return JSON.stringify([form, ...sources.map(value)])
}
function create(purpose: AiPreviewRequest['purpose'] | 'MATERIAL_QA' | 'EXPRESSION_DRAFT' | 'PREPARATION_PLAN' | 'RELATION_DRAFT' | 'MEMORY_CARD_DRAFT' | 'JD_REQUIREMENT_DRAFT' | 'FACT_DRAFT' = 'KNOWLEDGE_DRAFT') { if (readable.value) void router.push({ name: 'ai', query: withReturn({ create: '1', purpose, ...(purpose === 'FACT_DRAFT' ? { factSourceKind: 'MATERIAL' } : {}) }) }) }
function open(id: string) { if (readable.value) void router.push({ name: 'ai', query: withReturn({ item: id }) }) }
function createRelationEvidence() { if (readable.value) void router.push({ name: 'ai', query: withReturn({ create: '1', purpose: 'RELATION_DRAFT', relationMode: 'EVIDENCE' }) }) }
function createRelationReview() { if (readable.value) void router.push({ name: 'ai', query: withReturn({ create: '1', purpose: 'RELATION_DRAFT', relationMode: 'REVIEW' }) }) }
function close() { returnTo({ name: 'ai' }) }
function refresh() {
  void queryClient.invalidateQueries({ queryKey: ['ai', store.sessionEpoch, 'settings'] })
  void queryClient.invalidateQueries({ queryKey: ['ai', store.sessionEpoch, 'tasks'] })
}
function created(task: AiTask) { refresh(); open(task.task.id) }
function next() { if (tasks.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = tasks.data.value.nextCursor } }
</script>

<template>
  <div class="ai-product">
  <header class="page-heading"><div><h1>AI 工作台</h1><p class="muted"><RIcon name="shield-check" />任务、费用与外发授权集中核对</p></div><div class="row-actions"><NButton type="primary" :disabled="!readable" :aria-expanded="creationOpen" aria-controls="ai-create-panel" @click="creationOpen = !creationOpen"><RIcon name="plus" />新建 AI 任务</NButton><NButton :disabled="!readable" @click="refresh"><RIcon name="refresh-cw" />刷新</NButton></div></header>
  <section v-if="creationOpen" id="ai-create-panel" class="ai-create-panel" aria-label="新建 AI 任务用途"><header><div><h2>选择任务用途</h2><p class="field-help">先选择来源并预览完整外发内容，再确认供应商、费用与本次授权。</p></div><NButton text :disabled="!readable" @click="creationOpen = false">收起</NButton></header><div class="create-purpose-grid"><NButton :disabled="!readable" @click="create('FACT_DRAFT')">从资料整理事实候选</NButton><NButton :disabled="!readable" @click="create('JD_REQUIREMENT_DRAFT')">整理 JD 要求</NButton><NButton :disabled="!readable" @click="create('MEMORY_CARD_DRAFT')">生成回忆卡候选</NButton><NButton :disabled="!readable" @click="create('PREPARATION_PLAN')">生成准备建议</NButton><NButton :disabled="!readable" @click="create('EXPRESSION_DRAFT')">生成表达素材</NButton><NButton :disabled="!readable" @click="create('MATERIAL_QA')">向资料提问</NButton><NButton type="primary" :disabled="!readable" @click="create()">新建整理／出题候选</NButton></div></section>
  <details class="ai-config-collapse"><summary>高级与配置 · {{ configLabel }}</summary>
  <section class="ai-settings" aria-label="安全配置与预算"><header class="settings-heading"><div><strong>{{ configLabel }}</strong><p v-if="settings.data.value" class="field-help">{{ settings.data.value.configuration.provider || '供应商未配置' }} · {{ settings.data.value.configuration.model || '模型未配置' }}。这里只读服务器的公开配置，页面不填写密钥。</p></div><div class="row-actions"><NButton size="small" :disabled="!readable" @click="settings.refetch()">刷新配置与预算</NButton><NButton size="small" :disabled="!settings.data.value" @click="configurationOpen = !configurationOpen">{{ configurationOpen ? '收起配置' : '查看配置' }}</NButton></div></header><p v-if="settings.error.value" class="notice error" role="alert">{{ errorText(settings.error.value) }}</p><template v-if="settings.data.value"><div class="budget-grid"><div><span>当前可用</span><strong>{{ formatAiAmount(settings.data.value.budget.remainingMicros, settings.data.value.budget.currency) }}</strong></div><div><span>已报告／用户核对</span><strong>{{ formatAiAmount(settings.data.value.budget.reportedMicros, settings.data.value.budget.currency) }}</strong></div><div><span>待结算预留</span><strong>{{ formatAiAmount(settings.data.value.budget.reservedMicros, settings.data.value.budget.currency) }}</strong></div><div><span>未知费用占用</span><strong>{{ formatAiAmount(settings.data.value.budget.unknownMicros, settings.data.value.budget.currency) }}</strong></div></div><p class="field-help">UTC {{ settings.data.value.budget.date }} 日预算 {{ formatAiAmount(settings.data.value.budget.limitMicros, settings.data.value.budget.currency) }}。预留和未知费用跨日期占用；按报告用量估算的金额不等于供应商账单实扣。</p><dl v-if="configurationOpen" class="configuration-grid"><div><dt>接口协议</dt><dd>{{ settings.data.value.configuration.protocol === 'RESPONSES' ? 'Responses' : settings.data.value.configuration.protocol === 'CHAT_COMPLETIONS' ? 'Chat Completions' : '未配置' }}</dd></div><div class="full"><dt>HTTPS 请求端点</dt><dd>{{ settings.data.value.configuration.endpoint || '未配置' }}</dd></div><div><dt>配置修订／价格修订</dt><dd>{{ settings.data.value.configuration.configVersion || '未配置' }} / {{ settings.data.value.configuration.priceVersion || '未配置' }}</dd></div><div><dt>输入价格／百万 token</dt><dd>{{ formatAiAmount(settings.data.value.configuration.inputPriceMicrosPerMillion, settings.data.value.configuration.currency) }}</dd></div><div><dt>输出价格／百万 token</dt><dd>{{ formatAiAmount(settings.data.value.configuration.outputPriceMicrosPerMillion, settings.data.value.configuration.currency) }}</dd></div><div><dt>最大输出／超时</dt><dd>{{ settings.data.value.configuration.maxOutputTokens }} token / {{ settings.data.value.configuration.timeoutSeconds }} 秒</dd></div><div><dt>资料与要求上限</dt><dd>{{ settings.data.value.configuration.maxInputBytes }} 字节</dd></div><div><dt>单任务尝试上限</dt><dd>{{ settings.data.value.configuration.maxAttempts }} 次</dd></div><div><dt>输出额度字段</dt><dd>{{ settings.data.value.configuration.maxTokensField || '未配置' }}</dd></div><div><dt>JSON mode</dt><dd>{{ settings.data.value.configuration.jsonMode ? '服务器配置为启用' : '服务器配置为关闭' }}</dd></div></dl></template></section>
    <p class="authorization-entry"><RouterLink class="text-link" :to="{ name: 'ai-authorizations' }">管理与撤回持续范围授权</RouterLink><span class="field-help">安全元数据管理在独立训练期间仍可使用；完整清单从原任务核对。</span></p>
    <nav v-if="creatingRelation || !creating && !selectedId" class="relation-options" aria-label="关系建议来源">
      <h3>关系建议</h3>
      <p class="field-help">从已发布知识或确切资料、训练与反馈中选择来源，生成后仍需逐条审阅。</p>
      <div class="row-actions"><NButton :disabled="!readable" :type="creatingRelation && !creatingRelationEvidence && !creatingRelationReview ? 'primary' : 'default'" @click="create('RELATION_DRAFT')">从知识生成关系建议</NButton><NButton :disabled="!readable" :type="creatingRelationEvidence ? 'primary' : 'default'" @click="createRelationEvidence">结合资料或训练生成关系建议</NButton><NButton :disabled="!readable" :type="creatingRelationReview ? 'primary' : 'default'" @click="createRelationReview">结合人工／AI 反馈或回忆生成关系建议</NButton></div>
    </nav>
  </details>
  <div class="ai-workspace" :class="{ 'tasks-focused': !creating && !selectedId }">
    <aside class="task-catalog" aria-label="AI 任务记录">
      <header><h2>最近任务{{ tasks.data.value ? ` (${tasks.data.value.items.length})` : '' }}</h2><NButton text size="small" :disabled="!readable" @click="tasks.refetch()">刷新</NButton></header>
      <p class="field-help">每次外发、尝试和费用分别保留。</p>
      <p v-if="tasks.isPending.value" class="loading-block" role="status">正在读取任务…</p>
      <p v-else-if="tasks.error.value" class="notice error" role="alert">{{ errorText(tasks.error.value) }}<NButton text :disabled="!readable" @click="tasks.refetch()">重试</NButton></p>
      <div v-else-if="!tasks.data.value?.items.length" class="catalog-empty"><strong>还没有任务记录</strong><p>从资料生成知识、题目或问答，从事实组织表达，或选择目标生成准备建议。</p></div>
      <div class="task-list"><button v-for="task in tasks.data.value?.items" :key="task.id" :class="{ selected: !creating && selectedId === task.id }" :aria-current="!creating && selectedId === task.id ? 'true' : undefined" :disabled="!readable" @click="open(task.id)"><strong>{{ task.title }}</strong><span class="task-source">{{ aiPurposeLabels[task.purpose] }}</span><span class="task-status">{{ aiTaskStatusLabel(task.status, task.purpose) }}</span><small>{{ task.attemptCount }} 次尝试</small><small>{{ task.provider }} · {{ task.model }}</small><time>{{ aiDate(task.createdAt) }}</time></button></div>
      <div v-if="cursors.length || tasks.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!cursors.length || !readable" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!tasks.data.value?.nextCursor || !readable" @click="next">下一页</NButton></div>
    </aside>
    <section v-if="creatingSourceQa" class="ai-welcome"><h2>从搜索来源开始问答</h2><p>先打开确切来源并主动选择原文字段，再组合问题、核对完整外发内容与费用。</p><RouterLink class="text-link" :to="{ name: 'search' }">前往搜索并选择来源</RouterLink></section>
    <AiMaterialQaCreate v-else-if="creatingMaterialQa" key="ai-material-create" :settings="settings.data.value" :disabled="!readable" @created="created" @close="close" @refresh="refresh" />
    <AiExpressionCreate v-else-if="creatingExpression" :key="'expression:' + creationIdentity" :settings="settings.data.value" :disabled="!readable" :initial-fact="expressionFact" @created="created" @close="close" @refresh="refresh" />
    <AiPreparationPlanCreate v-else-if="creatingPreparationPlan" :key="'preparation-plan:' + creationIdentity" :settings="settings.data.value" :disabled="!readable" :initial-target="preparationTarget" @created="created" @close="close" @refresh="refresh" />
    <AiTrainingDraftCreate v-else-if="creatingTrainingDraft" :key="'training-draft:' + creationIdentity" :settings="settings.data.value" :disabled="!readable" :initial-source="trainingSource" @created="created" @close="close" @refresh="refresh" />
    <AiReviewDraftCreate v-else-if="creatingReviewDraft" :key="'review-draft:' + creationIdentity" :settings="settings.data.value" :disabled="!readable" :initial-source="reviewSource" @created="created" @close="close" @refresh="refresh" />
    <AiCardDraftCreate v-else-if="creatingCardDraft" :key="'card-draft:' + creationIdentity" :settings="settings.data.value" :disabled="!readable" :initial-kind="cardKind" :initial-source="cardSource" @created="created" @close="close" @refresh="refresh" />
    <AiJdRequirementsCreate v-else-if="creatingJdRequirements" :key="'jd-requirements:' + creationIdentity" :settings="settings.data.value" :disabled="!readable" :initial-target="jdTarget" @created="created" @close="close" @refresh="refresh" />
    <AiFactDraftCreate v-else-if="creatingFactDraft" :key="'fact-draft:' + creationIdentity" :settings="settings.data.value" :disabled="!readable" :source-kind="factSource.kind" :initial-training="factSource.training" :route-error="factSource.error" @created="created" @close="close" @refresh="refresh" />
    <AiRewriteCreate v-else-if="creatingRewrite" :key="'rewrite:' + creationIdentity" :settings="settings.data.value" :disabled="!readable" :initial-source="rewriteSource.source" :route-error="rewriteSource.error" @created="created" @close="close" @refresh="refresh" />
    <AiRelationEvidenceCreate v-else-if="creatingRelationEvidence" :key="'relation-evidence:' + creationIdentity" :settings="settings.data.value" :disabled="!readable" @created="created" @close="close" @refresh="refresh" />
    <AiRelationReviewCreate v-else-if="creatingRelationReview" :key="'relation-review:' + creationIdentity" :settings="settings.data.value" :disabled="!readable" @created="created" @close="close" @refresh="refresh" />
    <AiRelationCreate v-else-if="creatingRelation" :key="'relation:' + creationIdentity" :settings="settings.data.value" :disabled="!readable" @created="created" @close="close" @refresh="refresh" />
    <AiTaskCreate v-else-if="creating" key="ai-create" :settings="settings.data.value" :disabled="!readable" :initial-purpose="initialPurpose" :initial-preparation="questionTarget" @created="created" @close="close" @refresh="refresh" />
    <AiTaskDetail v-else-if="selectedId" :key="selectedId" :task-id="selectedId" :settings="settings.data.value" :disabled="!readable" @changed="refresh" @close="close" />
    <section v-else class="ai-welcome task-summary">
      <p class="summary-eyebrow">工作区摘要</p>
      <h2>从最近任务继续</h2>
      <p>选择左侧任务查看生成内容、外发授权、逐次尝试与费用。默认展示最近一页，需要新建时再展开用途。</p>
      <dl class="summary-grid"><div><dt>当前页任务</dt><dd>{{ tasks.data.value?.items.length || 0 }} 条</dd></div><div><dt>最近创建</dt><dd>{{ tasks.data.value?.items[0] ? aiDate(tasks.data.value.items[0].createdAt) : '暂无记录' }}</dd></div><div><dt>供应商配置</dt><dd>{{ configLabel }}</dd></div></dl>
      <p class="field-help">{{ ready ? '当前配置存在不代表供应商已连通，实际兼容和内容质量需在真实请求中确认。' : 'AI 未启用或配置不完整时不会外发请求，已有任务仍可读取。' }}</p>
      <p class="field-help">AI 回答与候选仍需人工检查技术正确性和语义依据；关系建议须逐条确认后才进入正式星图。</p>
      <p class="field-help">训练提交和评价记录可发起知识整理。<RouterLink class="text-link" :to="{ name: 'training' }">前往训练记录</RouterLink></p>
    </section>
  </div>
  <p class="workspace-footnote" role="status">{{ aiUpdates.message }} AI 回答与候选仍需要人工检查技术正确性和语义依据。</p>
  </div>
</template>

<style scoped>
.ai-product { min-width: 0; }
.page-heading .muted { display: flex; align-items: center; gap: 7px; }
.page-heading .muted svg { width: 17px; }
.ai-create-panel { display: grid; gap: 16px; margin-bottom: 20px; padding: 18px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-soft); }
.ai-create-panel > header { display: flex; justify-content: space-between; align-items: start; gap: 18px; }
.ai-create-panel h2 { margin: 0 0 6px; font-size: 16px; }
.create-purpose-grid { display: flex; flex-wrap: wrap; gap: 8px; }
.ai-config-collapse { margin-bottom: 20px; }
.ai-config-collapse > summary { cursor: pointer; font-size: 13px; color: var(--muted); }
.authorization-entry { display: flex; flex-wrap: wrap; align-items: baseline; gap: 10px 18px; margin: 0; padding-top: 18px; border-top: 1px solid var(--line); }
.relation-options { display: grid; gap: 10px; margin-top: 18px; padding-top: 18px; border-top: 1px solid var(--line); }
.relation-options h3 { margin: 0; font-size: 14px; }
.relation-options p { margin: 0; }
.ai-settings, .ai-workspace { max-width: none; margin-left: 0; margin-right: 0; }
.ai-settings { display: grid; gap: 16px; margin: 16px 0 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); padding: 18px 0; }
.settings-heading { display: flex; justify-content: space-between; align-items: start; gap: 18px; }
.settings-heading > div:first-child { display: grid; gap: 7px; min-width: 0; }
.settings-heading strong { font-size: 14px; }
.budget-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 18px; }
.budget-grid > div { display: grid; gap: 7px; }
.budget-grid span { font-size: 12px; color: var(--muted); }
.budget-grid strong { font-size: 17px; font-weight: 550; overflow-wrap: anywhere; }
.configuration-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 15px 20px; padding-top: 17px; border-top: 1px solid var(--line); margin: 0; }
.configuration-grid .full { grid-column: 1 / -1; }
dt { color: var(--muted); font-size: 12px; }
dd { margin: 5px 0 0; font-size: 13px; overflow-wrap: anywhere; }
.ai-workspace { display: grid; grid-template-columns: 280px minmax(0, 1fr); min-height: 480px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); }
.ai-workspace.tasks-focused { grid-template-columns: 320px minmax(0, 1fr); }
.task-catalog { padding: 16px 12px; border-right: 1px solid var(--line); background: var(--surface-soft); border-radius: 10px 0 0 10px; min-width: 0; }
.task-catalog > header { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 9px; }
.task-catalog h2 { font-size: 15px; margin: 0; }
.task-list { display: grid; gap: 4px; margin: 17px 0; max-height: 540px; overflow-y: auto; }
.task-list > button { display: grid; grid-template-columns: minmax(0, 1fr) auto; grid-template-areas: "title title" "source status" "attempts time" "provider provider"; align-items: baseline; gap: 5px 10px; padding: 11px; text-align: left; border-radius: 6px; min-width: 0; }
.task-list > button:hover, .task-list > button.selected { background: var(--surface-deep); }
.task-list strong { grid-area: title; font-size: 14px; overflow-wrap: anywhere; }
.task-list span, .task-list small, .task-list time { color: var(--muted); font-size: 11px; overflow-wrap: anywhere; }
.task-list .task-source { grid-area: source; }
.task-list .task-status { grid-area: status; text-align: right; }
.task-list small { grid-area: attempts; }
.task-list small + small { grid-area: provider; }
.task-list time { grid-area: time; text-align: right; }
.ai-welcome { display: grid; gap: 16px; justify-items: start; align-self: start; max-width: 760px; padding: 34px; }
.ai-welcome h2 { font-size: 21px; margin: 0; }
.ai-welcome > p { color: var(--muted); max-width: 68ch; font-size: 14px; }
.ai-welcome .summary-eyebrow { margin: 0; color: var(--muted); font-size: 12px; letter-spacing: .08em; }
.task-summary { max-width: none; }
.summary-grid { display: grid; gap: 14px; width: 100%; margin: 4px 0 0; }
.summary-grid > div { display: grid; gap: 5px; padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.summary-grid dd { margin: 0; }
@media (max-width: 1050px) {
  .ai-workspace { grid-template-columns: 225px minmax(0, 1fr); }
  .ai-workspace.tasks-focused { grid-template-columns: 280px minmax(0, 1fr); }
  .settings-heading { flex-wrap: wrap; }
  .budget-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .ai-workspace, .ai-workspace.tasks-focused { grid-template-columns: minmax(0, 1fr); }
  .task-catalog { border-right: 0; border-bottom: 1px solid var(--line); border-radius: 10px 10px 0 0; }
  .task-list { max-height: 230px; }
  .configuration-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .ai-welcome { padding: 24px 18px; }
  .ai-create-panel { padding: 16px; }
  .ai-create-panel > header { flex-wrap: wrap; }
}
</style>
