<script setup lang="ts">
import { computed } from 'vue'
import type { AiBudget, AiManifest, AiSource } from '../../api/ai'
import { aiExpressionManifestMatches, aiExpressionSourceChecksMatch, aiExpressionSourceContent, aiFactResponsibility, aiKnowledgeMaterialChecksMatch, aiManifestHasKnowledgeRoots, aiMaterialChecksMatch, aiMaterialDraftSourcesMatch, aiMaterialModeLabels, aiMaterialSourceRoute, aiOriginStateLabels, aiPreparationChecksMatch, aiPreparationPlanManifestMatches, aiPurposeLabels, aiRelationManifestMatches, aiSourceKindLabel, aiSourceRoute, aiTrainingDraftManifestMatches, formatAiAmount, isAiPreparationKind } from '../../api/ai'
import { trainingContextLabels, trainingContextStateLabels } from '../../api/training'
import { questionTypeLabels } from '../../api/bank'
import { expressionOptions, responsibilityLabels } from '../../api/preparation'
import AiTrainingDraftInput from './AiTrainingDraftInput.vue'
import { aiComparisonManifestMatches } from '../../api/aiComparison'
import AiComparisonInput from './AiComparisonInput.vue'
import { aiReviewDraftManifestMatches } from '../../api/aiReviewDraft'
import AiReviewDraftInput from './AiReviewDraftInput.vue'
import { aiCardDraftManifestMatches } from '../../api/aiCardDraft'
import { aiJdRequirementsManifestMatches } from '../../api/aiJdRequirements'
import { aiFactDraftManifestMatches } from '../../api/aiFactDraft'
import AiFactDraftInput from './AiFactDraftInput.vue'
import AiRelationEvidenceInput from './AiRelationEvidenceInput.vue'
import AiRelationAiFeedbackInput from './AiRelationAiFeedbackInput.vue'
import { aiRewriteManifestMatches } from '../../api/aiRewrite'
import { aiSourceQaManifestValid } from '../../api/aiSourceQa'
import AiSourceQaInput from './AiSourceQaInput.vue'

const props = defineProps<{ manifest: AiManifest; budget?: AiBudget }>()
const sourceQa = computed(() => props.manifest.purpose === 'SOURCE_QA' || props.manifest.sourceQa != null)
const sourceQaValid = computed(() => aiSourceQaManifestValid(props.manifest))
const excludedContext = computed(() => props.manifest.contextChecks?.filter(check => check.state !== 'CURRENT') ?? [])
const hasPreparations = computed(() => props.manifest.sources.some(source => isAiPreparationKind(source.kind)))
const preparationChecksValid = computed(() => aiPreparationChecksMatch(props.manifest))
const expressionSourceChecksValid = computed(() => aiExpressionSourceChecksMatch(props.manifest))
const materialSources = computed(() => props.manifest.materialDraftSources ?? [])
const materialSourcesValid = computed(() => aiMaterialDraftSourcesMatch(props.manifest))
const materialChecksValid = computed(() => aiMaterialChecksMatch(props.manifest))
const hasKnowledgeRoots = computed(() => aiManifestHasKnowledgeRoots(props.manifest))
const knowledgeMaterialChecksValid = computed(() => aiKnowledgeMaterialChecksMatch(props.manifest))
const expression = computed(() => props.manifest.purpose === 'EXPRESSION_DRAFT')
const expressionManifestValid = computed(() => aiExpressionManifestMatches(props.manifest))
const expressionTypeLabel = computed(() => expressionOptions.find(option => option.value === props.manifest.expressionType)?.label ?? '表达类型待核对')
const preparationPlan = computed(() => props.manifest.purpose === 'PREPARATION_PLAN')
const preparationPlanManifestValid = computed(() => aiPreparationPlanManifestMatches(props.manifest))
const trainingDraft = computed(() => props.manifest.trainingDraft != null || props.manifest.promptVersion === 'training-knowledge-draft-1')
const trainingDraftManifestValid = computed(() => aiTrainingDraftManifestMatches(props.manifest))
const reviewDraft = computed(() => props.manifest.purpose === 'KNOWLEDGE_DRAFT'
  && (props.manifest.reviewDraft != null || props.manifest.promptVersion === 'review-knowledge-draft-1'))
const reviewDraftManifestValid = computed(() => aiReviewDraftManifestMatches(props.manifest))
const cardDraft = computed(() => props.manifest.purpose === 'MEMORY_CARD_DRAFT' || props.manifest.cardDraftType != null || props.manifest.promptVersion === 'memory-card-draft-1')
const cardDraftManifestValid = computed(() => aiCardDraftManifestMatches(props.manifest))
const jdRequirements = computed(() => props.manifest.purpose === 'JD_REQUIREMENT_DRAFT' || props.manifest.promptVersion === 'jd-requirements-1')
const jdRequirementsManifestValid = computed(() => aiJdRequirementsManifestMatches(props.manifest))
const factDraft = computed(() => props.manifest.purpose === 'FACT_DRAFT' || props.manifest.factDraft != null || props.manifest.promptVersion === 'fact-draft-1')
const factDraftManifestValid = computed(() => aiFactDraftManifestMatches(props.manifest))
const relation = computed(() => props.manifest.purpose === 'RELATION_DRAFT')
const relationManifestValid = computed(() => aiRelationManifestMatches(props.manifest))
const relationEvidence = computed(() => props.manifest.relationEvidence != null || props.manifest.promptVersion === 'relation-evidence-draft-1')
const relationReview = computed(() => relation.value && (props.manifest.reviewDraft != null || props.manifest.promptVersion === 'relation-review-draft-1'))
const relationAiFeedback = computed(() => relation.value && (props.manifest.relationAiFeedback != null || props.manifest.promptVersion === 'relation-ai-feedback-draft-1'))
const comparison = computed(() => props.manifest.purpose === 'ANSWER_COMPARISON')
const comparisonManifestValid = computed(() => aiComparisonManifestMatches(props.manifest))

function factResponsibilityLabel(source: AiSource): string {
  const responsibility = aiFactResponsibility(source)
  return responsibility ? responsibilityLabels[responsibility] : '责任边界缺失，请重新核对清单'
}

function expressionSourceTypeLabel(source: AiSource): string {
  const type = aiExpressionSourceContent(source)?.type
  return expressionOptions.find(option => option.value === type)?.label ?? '表达类型待核对'
}

function expressionCheckFor(source: AiSource) {
  // 本地依据只展示授权时的身份与代次，不在查看清单时额外读取或外发事实正文。
  if (!expressionSourceChecksValid.value) return undefined
  return props.manifest.expressionChecks?.find(check => check.expression.itemId === source.itemId && check.expression.versionId === source.versionId)
}

function backgroundText(body: string): string {
  const names: Record<string, string> = { company: '公司', jd: '岗位要求', requirements: '考察要求', text: '内容', dimensions: '维度', priority: '优先级', overview: '项目背景', technology: '技术范围', scenario: '场景', problem: '问题', action: '行动', result: '结果', responsibility: '职责' }
  try {
    const value = JSON.parse(body) as Record<string, unknown>
    return Object.entries(value).map(([key, text]) => `${names[key] ?? key}：${typeof text === 'string' ? text : JSON.stringify(text, null, 2)}`).join('\n\n')
  } catch { return body }
}
</script>

<template>
  <section class="ai-manifest" aria-label="完整外发清单">
    <p class="field-help">接口协议：{{ manifest.configuration.protocol === 'RESPONSES' ? 'Responses' : 'Chat Completions' }}。{{ manifest.configuration.protocol === 'RESPONSES' ? '本次请求要求不保存响应，不附带远端会话或前次响应标识；实际数据保留规则仍以供应商政策为准。' : '本次仅发送下列固定内容与用途。' }}</p>
    <header><h3>本次外发清单</h3><p class="field-help">用途：{{ aiPurposeLabels[manifest.purpose] }}。{{ sourceQa ? '只依据下面明确选择的跨域原文片段及完整必要限定回答，保留原来源身份，不自动补检索或采用内容。' : factDraft ? '将下方明确选择的完整资料摘录或确切回答片段及本次指令发送给模型，整理一条待核实事实候选。' : jdRequirements ? '将单个目标的完整 JD 原文和本次指令发送给模型，提取待审要求；已有目标要求及其他准备内容不在本次投影中。' : cardDraft ? '将下面明确选择的完整知识正文、卡片类型与制卡要求发送给模型，生成一张待审回忆卡。' : reviewDraft ? '根据本次确切自检、外部意见或面后回忆的已选片段整理知识候选；完整限制与来源修订也会发送，请逐项核对。' : comparison ? '按选定来源的固定验证条件比较下面两份确切提交，生成带旧答、新答依据的逐来源 AI 未审建议。' : trainingDraft ? '根据这次确切提交的已选片段整理知识候选；固定题面、选定反馈及其原引文和完整整理要求都会发送，请逐项核对。' : preparationPlan ? '按下列目标版本、明确日期、预计投入和用户自述生成准备建议；完整目标正文、自述和要求都会发送，请逐项核对。' : expression ? '根据下列确切事实与责任边界生成表达候选；完整生成要求和固定事实正文都会发送，请逐项核对。' : manifest.purpose === 'FOLLOW_UP' ? '根据确切已提交回答生成一道追问，通过校验后直接加入当前训练；下面完整列出实际发送的题干、答案和固定背景。' : manifest.purpose === 'ANSWER_REWRITE' ? '根据下面这次确切提交、固定题干与个人未审参考生成只读改写，供与原答并排核对。' : manifest.purpose === 'ANSWER_EVALUATION' ? '只分析下面这次确切提交、固定题干与个人未审参考，保存带原文依据的 AI 未审报告。' : manifest.purpose === 'MATERIAL_QA' ? '按下面明确的模式、问题和摘录清单回答，保留 AI 未审结果及引用。' : materialSources.length ? '以下逐字列出所选知识、准备版本及附件摘录的完整外发正文，按知识、准备、摘录顺序展示。字段中的私人文字也会发送，请一并核对。' : hasPreparations ? '以下逐字列出所选知识与准备版本的完整外发正文。字段中的私人文字也会发送，请一并核对。' : '以下为固定知识版本和完整用户正文，发布后的草稿修改不在此清单内。' }}</p></header>
    <dl class="manifest-summary"><div><dt>供应商</dt><dd>{{ manifest.configuration.provider }}</dd></div><div><dt>模型</dt><dd>{{ manifest.configuration.model }}</dd></div><div class="full"><dt>请求端点</dt><dd class="endpoint">{{ manifest.configuration.endpoint }}</dd></div><div><dt>配置修订</dt><dd>{{ manifest.configuration.configVersion }}</dd></div><div><dt>价格修订</dt><dd>{{ manifest.configuration.priceVersion }}</dd></div><div><dt>输入价格／百万 token</dt><dd>{{ formatAiAmount(manifest.configuration.inputPriceMicrosPerMillion, manifest.configuration.currency) }}</dd></div><div><dt>输出价格／百万 token</dt><dd>{{ formatAiAmount(manifest.configuration.outputPriceMicrosPerMillion, manifest.configuration.currency) }}</dd></div><div><dt>保守输入额度</dt><dd>{{ manifest.inputTokenAllowance }} token</dd></div><div><dt>最大输出</dt><dd>{{ manifest.configuration.maxOutputTokens }} token</dd></div><div><dt>本次费用预留</dt><dd class="reservation">{{ formatAiAmount(manifest.reservedMicros, manifest.configuration.currency) }}</dd></div><div><dt>生成规则版本</dt><dd>{{ manifest.promptVersion }}</dd></div></dl>
    <p class="field-help">输入额度按正文大小和协议余量估算，费用预留用于限制新任务；它不是供应商实际计费承诺，也不保证远端收费绝不超出预算。</p>
    <div v-if="budget" class="preview-budget"><strong>当前可用预算 {{ formatAiAmount(budget.remainingMicros, budget.currency) }}</strong><p class="field-help">UTC {{ budget.date }} 日预算 {{ formatAiAmount(budget.limitMicros, budget.currency) }}；已报告／人工核对 {{ formatAiAmount(budget.reportedMicros, budget.currency) }}，跨日期待结算预留 {{ formatAiAmount(budget.reservedMicros, budget.currency) }}，未知费用占用 {{ formatAiAmount(budget.unknownMicros, budget.currency) }}。创建时再次检查预算。</p></div>
    <p v-if="!materialChecksValid" class="notice error" role="alert">材料生命周期核对记录与本次完整直接材料身份不一致，不能使用这份清单授权新的尝试。请重新读取任务或重新预览。</p>
    <details v-else-if="manifest.materialChecks?.length" class="training-source">
      <summary>直接材料生命周期核对 · 不发送给模型</summary>
      <p class="field-help">以下是清单生成时全部不同直接摘录的本地代次，按标识排序；同摘录的多个片段只登记一次。这些元数据不计入模型正文或输入费用，发送和采用前仍由服务器核验。归档后恢复、撤回后重新允许均不能恢复旧授权。</p>
      <ul class="preparation-checks"><li v-for="check in manifest.materialChecks" :key="check.excerptId"><strong>确切摘录 {{ check.excerptId }}</strong><span>清单生成时可供 AI 使用 · 生命周期代次 {{ check.revision }}</span></li></ul>
    </details>
    <p v-if="!knowledgeMaterialChecksValid" class="notice error" role="alert">知识采用链的摘录核对记录与来源范围或字段形状不一致，不能使用这份清单授权新的尝试。请重新读取任务或重新预览。</p>
    <section v-else-if="hasKnowledgeRoots" class="training-source" aria-label="知识采用链的摘录祖先核对">
      <h4>知识采用链的摘录祖先核对 · 不发送给模型</h4>
      <p class="field-help">当前核验范围含实际知识采用的直接摘录、所选确切准备版本和表达最终事实的直接摘录，以及这些表达、事实此前实际 AI 采用的材料来源。表达采用时删去的原事实、事实后续移除的原材料仍会保守继承。知识整理和事实整理采用过的确切答案，还会保守继承整张固定答案图中的知识、项目、事实引用，即使当时只选了一段文字；合法历史或未核实图引用不因此升级为已核实事实。知识整理还核对固定题面及确切题库版本，继承题库原 AI 出题任务的全部知识、准备和材料来源，后续手工修改不抹去原来源。明确选用评价时，服务器另核成功评价及原输入、本题确切个人参考与完整报告，再逐项核所选反馈；原记录未保存报告摘要，不能证明整份历史报告字节从未改变，也不代表 AI 的语义判断已获确认。无题库追问会核对 v2 成功生成任务、确切父题、整张父答图及原任务实际发送的背景；当时已排除的背景不纳入。旧版或未知版本缺少已核生成规则时，新的祖先用途核验会拒绝，历史任务仍按原权限读取。同题复练会核对真实复制绑定并继续沿确切原题面追溯；基线原答只核身份，不额外继承未复制的答案图或背景。个人回忆补练只核确切历史版本及题面投影，原回忆回答、反馈和面试安排不纳入。回答片段整理事实不额外加入题面或评价。原事实检查明确保存父项目代次时，服务器以完全未变的代次或真实更正事件核对当时的确切项目版及其材料许可；缺少历史见证会阻止新的使用，历史任务仍可读取。父项目是用途资格依赖，不代表原事实复制过项目正文。没有捕获父项目代次的图引用、手工表达关系和事实首版目标，以及未明确选定的评价或参考、隐式训练背景及白板原文等路径仍未覆盖。这里只显示摘录身份与生成时的代次，不展开祖先原文，也不计入模型正文或输入费用。历史清单按生成时范围保存；当前完整集合与使用资格仍由服务器重建，集合扩大后可能需要重新预览。</p>
      <p v-if="manifest.knowledgeMaterialChecks == null" class="notice" role="status">这份历史清单未记录知识采用链的摘录核对元数据。当前资格须由服务器另行核验，不能凭历史摘要认定仍获准使用。</p>
      <p v-else-if="manifest.knowledgeMaterialChecks.length === 0" class="field-help">这份清单在生成时登记的摘录祖先集合为空，不代表今天扩展后的核验范围仍为空或当前仍获许可。</p>
      <details v-else>
        <summary>清单生成时登记的 {{ manifest.knowledgeMaterialChecks.length }} 条摘录元数据</summary>
        <ul class="preparation-checks"><li v-for="check in manifest.knowledgeMaterialChecks" :key="check.excerptId"><strong>确切摘录 {{ check.excerptId }}</strong><span>清单生成时可供 AI 使用 · 生命周期代次 {{ check.revision }}</span></li></ul>
      </details>
    </section>
    <template v-if="sourceQa">
      <AiSourceQaInput v-if="sourceQaValid && manifest.sourceQa" :input="manifest.sourceQa" />
      <p v-else class="notice error" role="alert">来源问答用途、固定片段或必要限定不一致，不能使用这份清单。</p>
    </template>
    <template v-else-if="factDraft">
      <template v-if="factDraftManifestValid && manifest.factDraft">
        <section class="manifest-instruction"><h4>本次事实整理要求</h4><pre v-if="manifest.instruction" class="exact-text">{{ manifest.instruction }}</pre><p v-else class="field-help">没有附加要求，按所选原文整理一条待审事实候选。</p><p class="field-help">本次外发内容由用途 FACT_DRAFT、以上完整指令及下方 factDraft 输入组成。所属项目在采用时选择；生成与采用均不确认经历或本人责任。</p></section>
        <AiFactDraftInput :input="manifest.factDraft" />
      </template>
      <p v-else class="notice" role="status">事实整理用途、输入模式或确切来源不一致，当前不能授权新的尝试。请重新读取任务或重新选择来源预览。</p>
    </template>
    <template v-else-if="jdRequirements">
      <template v-if="jdRequirementsManifestValid">
        <section class="manifest-instruction"><h4>本次 JD 要求提取指令</h4><pre v-if="manifest.instruction" class="exact-text">{{ manifest.instruction }}</pre><p v-else class="field-help">没有附加要求，按下方 JD 原文整理待审要求。</p><p class="field-help">候选维度与优先级均待人工核对；原文依据不足时可以没有候选。确认采用后，仅将选定要求追加到原目标的新版本。</p></section>
        <article v-for="source in manifest.sources" :key="source.versionId" class="manifest-source"><header><h4>目标与 JD · {{ source.title }}</h4><RouterLink v-if="aiSourceRoute(source)" class="text-link" :to="aiSourceRoute(source)!">查看目标确切版本 · v{{ source.number }}</RouterLink></header><p class="source-identity">对象 {{ source.itemId }}<br />版本 {{ source.versionId }}</p><h4>本次外发的完整 JD 原文</h4><pre class="exact-text">{{ source.body }}</pre></article>
        <details class="training-source"><summary>核对完整外发内容</summary><pre class="exact-text">{{ JSON.stringify({ purpose: manifest.purpose, instruction: manifest.instruction, sources: manifest.sources }, null, 2) }}</pre></details>
        <details class="training-source"><summary>本地来源核对记录</summary><p class="field-help">目标身份、来源代次与状态用于核对本次授权。下列记录不发送给模型；发送与采用前仍由服务器重新检查。</p><pre class="exact-text">{{ JSON.stringify(manifest.preparationChecks, null, 2) }}</pre></details>
      </template>
      <p v-else class="notice" role="status">JD 用途、目标版本或来源核对记录不一致，当前不能授权新的尝试。请重新读取任务或重新选择目标预览。</p>
    </template>
    <template v-else-if="cardDraft">
      <template v-if="cardDraftManifestValid">
        <section class="manifest-instruction"><h4>卡片类型 · {{ manifest.cardDraftType === 'CLOZE' ? '填空卡' : '问答卡' }}</h4><p class="field-help">按下列完整知识正文生成一张待审候选卡。采用前可人工改写题面与参考；保存卡片后需另行启用排程。</p><h4>本次制卡要求</h4><pre v-if="manifest.instruction" class="exact-text">{{ manifest.instruction }}</pre><p v-else class="field-help">没有附加要求，按明确选择的卡片类型整理候选。</p></section>
        <article v-for="(source, index) in manifest.sources" :key="`${source.itemId}/${source.versionId}`" class="manifest-source"><header><h4>{{ index + 1 }}. {{ source.title }}</h4><RouterLink v-if="aiSourceRoute(source)" class="text-link" :to="aiSourceRoute(source)!">{{ aiSourceKindLabel(source.kind) }} · v{{ source.number }} · 查看确切知识版本</RouterLink></header><p class="source-identity">对象 {{ source.itemId }}<br />版本 {{ source.versionId }}</p><pre class="exact-text">{{ source.body }}</pre></article>
        <details class="training-source"><summary>核对完整外发内容</summary><pre class="exact-text">{{ JSON.stringify({ purpose: manifest.purpose, instruction: manifest.instruction, cardDraftType: manifest.cardDraftType, sources: manifest.sources }, null, 2) }}</pre></details>
      </template>
      <p v-else class="notice" role="status">卡片类型、专用用途或确切知识清单不一致，当前不能授权新的尝试。请重新读取任务或重新选择来源预览。</p>
    </template>
    <template v-else-if="reviewDraft">
      <section class="manifest-instruction"><h4>本次复盘片段整理要求</h4><pre v-if="manifest.instruction" class="exact-text">{{ manifest.instruction }}</pre><p v-else class="field-help">没有附加要求，按已选复盘片段整理待审知识候选。</p></section>
      <section class="manifest-source">
        <AiReviewDraftInput v-if="reviewDraftManifestValid && manifest.reviewDraft" :input="manifest.reviewDraft" />
        <p v-else class="notice" role="status">复盘用途、来源或片段清单不完整，当前不能采用或授权新的尝试。请重新读取任务；新任务需重新选择确切来源并预览。</p>
      </section>
    </template>
    <section v-else-if="comparison" class="manifest-source">
      <template v-if="comparisonManifestValid && manifest.comparisonInput && manifest.comparisonChecks">
        <AiComparisonInput :input="manifest.comparisonInput" />
        <details class="training-source"><summary>完整外发业务 JSON</summary><pre class="exact-text">{{ JSON.stringify({ purpose: manifest.purpose, comparisonInput: manifest.comparisonInput }, null, 2) }}</pre></details>
        <details class="training-source"><summary>本地授权核对记录 · 不发送给模型</summary><p class="field-help">两场训练的代次、所选来源当前归属及动作修订只用于本地授权核对。归属、修订或训练代次变化使旧授权失效，条件改后改回也需要重新预览。</p><pre class="exact-text">{{ JSON.stringify(manifest.comparisonChecks, null, 2) }}</pre></details>
      </template>
      <p v-else class="notice" role="status">比较用途、双端输入或本地来源核对记录不一致，当前不能授权新的尝试。请重新读取任务，或回到对照重新选择来源并预览。</p>
    </section>
    <section v-else-if="manifest.purpose === 'FOLLOW_UP' && manifest.training" class="manifest-source">
      <h4>本次确切提交 · 第 {{ manifest.training.answerNumber }} 版</h4>
      <p class="field-help">结构图只发送节点名称、说明和连线；固定背景按已显示字段发送，不展开资料链接。页面尚未提交的输入不在清单中。</p>
      <p class="field-help">题干、原答和图中文字中的既有内容仍会发送，排除失效背景不会自动删除其中的事实。需要更正时，请先提交新答并选择那次提交，再预览授权。</p>
      <p v-if="manifest.contextChecks == null" class="notice" role="status">这份历史清单没有背景可用性核对记录，不能继续授权新发送。请重新预览；原任务与费用记录仍保留。</p>
      <section v-else-if="excludedContext.length" class="excluded-context" aria-label="本地背景排除说明">
        <h5>清单生成时排除的背景</h5>
        <p class="field-help">以下为系统内的排除说明；这些条目不会作为固定背景发送，也不会自动替换成新版本。题干、原答和图中文字仍以完整发送预览为准。</p>
        <ul><li v-for="context in excludedContext" :key="`${context.itemId}/${context.versionId}`"><strong>{{ trainingContextLabels[context.kind] }} · {{ context.title }} · v{{ context.number }}</strong><span>{{ trainingContextStateLabels[context.state] }}</span></li></ul>
      </section>
      <article class="training-source"><h4>父题 · {{ manifest.training.question.title }}</h4><pre class="exact-text">{{ manifest.training.question.prompt }}</pre><template v-for="field in ([['conditions', '条件'], ['technology', '技术范围'], ['scenario', '场景']] as const)" :key="field[0]"><h5 v-if="manifest.training.question[field[0]]">{{ field[1] }}</h5><pre v-if="manifest.training.question[field[0]]" class="exact-text">{{ manifest.training.question[field[0]] }}</pre></template></article>
      <article class="training-source"><h4>已提交的回答原文</h4><pre class="exact-text">{{ manifest.training.answer.text }}</pre><div v-if="manifest.training.answer.nodes.length"><h5>答案结构图</h5><pre class="exact-text">{{ JSON.stringify({ nodes: manifest.training.answer.nodes, edges: manifest.training.answer.edges }, null, 2) }}</pre></div></article>
      <article v-for="context in manifest.training.context" :key="context.versionId" class="training-source"><h4>{{ trainingContextLabels[context.kind as keyof typeof trainingContextLabels] ?? context.kind }} · {{ context.title }} · v{{ context.number }}</h4><pre class="exact-text">{{ backgroundText(context.body) }}</pre></article>
      <p v-if="!manifest.training.context.length" class="field-help">本次没有额外固定背景。</p>
      <details class="training-source"><summary>核对来源标识与完整发送数据</summary><pre class="exact-text">{{ JSON.stringify(manifest.training, null, 2) }}</pre></details>
    </section>
    <section v-else-if="(manifest.purpose === 'ANSWER_EVALUATION' || manifest.purpose === 'ANSWER_REWRITE') && manifest.evaluation" class="manifest-source">
      <h4>{{ manifest.purpose === 'ANSWER_REWRITE' ? '确切改写输入' : '确切评价输入' }} · 第 {{ manifest.evaluation.answerNumber }} 版提交</h4>
      <template v-if="manifest.purpose === 'ANSWER_REWRITE'"><p class="field-help">本次用途为 ANSWER_REWRITE，只发送该用途与下方 evaluation 的完整固定内容，生成并展示独立的只读改写参考。个人参考保持未审身份，缺少依据的内容应列为待补问题。</p><p v-if="!aiRewriteManifestMatches(manifest)" class="notice error" role="alert">这份改写清单的用途、固定来源或字段不一致，不能据此授权新尝试。</p></template>
      <p class="field-help">答案标识 {{ manifest.evaluation.answerId }}。页面内草稿、训练背景、附件、知识正文和结构图外链均不在本次清单中；提交文字中已有的个人材料仍会发送，请逐项核对。</p>
      <p class="field-help">训练模式：{{ manifest.evaluation.mode }} · 反馈约定：{{ manifest.evaluation.feedbackTiming }} · 提交时条件：{{ manifest.evaluation.answerCondition }}。</p>
      <article class="training-source"><h4>{{ manifest.evaluation.question.title }} · {{ questionTypeLabels[manifest.evaluation.question.type] }}</h4><pre class="exact-text">{{ manifest.evaluation.question.prompt }}</pre><template v-for="field in ([['conditions', '适用条件'], ['technology', '技术与版本'], ['scenario', '场景']] as const)" :key="field[0]"><h5 v-if="manifest.evaluation.question[field[0]]">{{ field[1] }}</h5><pre v-if="manifest.evaluation.question[field[0]]" class="exact-text">{{ manifest.evaluation.question[field[0]] }}</pre></template></article>
      <article class="training-source"><h4>已提交回答原文</h4><pre class="exact-text">{{ manifest.evaluation.answer.text }}</pre><template v-if="manifest.evaluation.answer.nodes.length || manifest.evaluation.answer.edges.length"><h5>答案图节点与连线</h5><pre class="exact-text">{{ JSON.stringify({ nodes: manifest.evaluation.answer.nodes, edges: manifest.evaluation.answer.edges }, null, 2) }}</pre></template></article>
      <article v-if="manifest.evaluation.standard" class="training-source"><h4>确切题库版本的个人参考 · 未审</h4><p class="source-identity">题目 {{ manifest.evaluation.standard.questionId }}<br />版本 {{ manifest.evaluation.standard.versionId }} · {{ manifest.evaluation.standard.status }}</p><pre v-if="manifest.evaluation.standard.referenceAnswer" class="exact-text">{{ manifest.evaluation.standard.referenceAnswer }}</pre><p v-else class="field-help">个人参考正文为空。</p><div v-for="point in manifest.evaluation.standard.checkpoints" :key="point.id"><h5>检查点 {{ point.id }}</h5><pre class="exact-text">{{ point.text }}</pre></div></article>
      <p v-else class="field-help">这次题实例没有个人参考和检查点，结果只能基于固定题干与提交作有限整理。</p>
      <details class="training-source"><summary>核对来源标识与完整发送数据</summary><pre class="exact-text">{{ JSON.stringify(manifest.evaluation, null, 2) }}</pre></details>
    </section>
    <section v-else-if="manifest.purpose === 'MATERIAL_QA'" class="manifest-source">
      <template v-if="manifest.materialQa">
        <h4>{{ aiMaterialModeLabels[manifest.materialQa.mode] }} · 本次问答</h4>
        <p v-if="manifest.materialQa.mode === 'GENERAL'" class="notice" role="status">本次为通用解释，不附带摘录，不声称来自私人材料。你手工粘贴在下方问题中的资料文字仍会发送。</p>
        <p v-else class="field-help">仅发送下列 {{ manifest.materialQa.excerpts.length }} 条已保存摘录与问题；资料不足时应说明。不附带整份原件、其他命中、附件说明或历史问答。问题中的手工输入也会发送。</p>
        <article class="training-source"><h4>完整问题</h4><pre class="exact-text">{{ manifest.materialQa.question }}</pre></article>
        <article v-for="(excerpt, index) in manifest.materialQa.excerpts" :key="excerpt.excerptId" class="training-source">
          <h4>{{ index + 1 }}. {{ excerpt.fileName }} · {{ excerpt.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }}</h4>
          <p class="field-help">定位：{{ excerpt.locator }}</p>
          <p v-if="excerpt.origin === 'MANUAL'" class="field-help">原文与定位由用户手工提供，不代表自动解析或项目事实已经核实。</p>
          <pre class="exact-text">{{ excerpt.text }}</pre>
          <dl class="excerpt-provenance"><div><dt>确切摘录</dt><dd>{{ excerpt.excerptId }}</dd></div><div><dt>来源附件</dt><dd>{{ excerpt.attachmentId }}</dd></div><div><dt>原件 SHA-256</dt><dd>{{ excerpt.sha256 }}</dd></div><template v-if="excerpt.origin === 'PARSED'"><div><dt>原解析执行</dt><dd>{{ excerpt.runId }}</dd></div><div><dt>原解析片段</dt><dd>{{ excerpt.segmentId }}</dd></div><div><dt>片段内范围</dt><dd>UTF-16 [{{ excerpt.startOffset }}, {{ excerpt.endOffset }})</dd></div></template></dl>
        </article>
        <p v-if="!manifest.materialQa.excerpts.length" class="field-help">摘录来源：0 条。</p>
        <details class="training-source"><summary>核对问题、来源标识与完整发送数据</summary><pre class="exact-text">{{ JSON.stringify(manifest.materialQa, null, 2) }}</pre></details>
      </template>
      <p v-else class="notice" role="status">这份记录缺少资料问答的确切输入，无法核对完整外发范围。请重新读取任务；新问答需要重新选择模式与来源并预览。</p>
    </section>
    <template v-else-if="trainingDraft">
      <section class="manifest-instruction"><h4>本次训练片段整理要求</h4><pre v-if="manifest.instruction" class="exact-text">{{ manifest.instruction }}</pre><p v-else class="field-help">没有附加要求，按已选训练片段整理待审知识候选。</p></section>
      <section class="manifest-source">
        <AiTrainingDraftInput v-if="trainingDraftManifestValid && manifest.trainingDraft" :input="manifest.trainingDraft" />
        <template v-else><p class="notice" role="status">训练题面、确切提交、片段或反馈清单不完整，当前不能采用或授权新的尝试。请重新读取任务；新任务需重新选择片段并预览。</p><pre v-if="manifest.trainingDraft" class="exact-text">{{ JSON.stringify(manifest.trainingDraft, null, 2) }}</pre></template>
      </section>
    </template>
    <template v-else-if="manifest.purpose === 'KNOWLEDGE_DRAFT' || manifest.purpose === 'QUESTION_DRAFT' || expression || preparationPlan || relation">
      <section v-if="relation" class="manifest-source">
        <h4>{{ relationAiFeedback ? '关系建议 · 知识版本与 AI 未审反馈依据' : relationReview ? '关系建议 · 知识版本与复盘原文依据' : relationEvidence ? '关系建议 · 知识版本与直接原文依据' : '关系建议 · 仅使用下列已发布知识' }}</h4>
        <p v-if="relationAiFeedback" class="field-help">下列 2–5 个确切知识版本、完整要求和明确选中的 AI 反馈片段都会发送。原评价任务、成功尝试、观察身份、报告 SHA-256、确切提交身份与作答条件，以及 AI 未审等级、完整报告限制与说明、维度、覆盖、当时决定、完整适用条件、依据和限制，也包含在固定输入中。关系两端只取自已选概念；每条候选须引用已选 AI 反馈片段，完整限定不扩大可引用范围。</p>
        <p v-else-if="relationReview" class="field-help">下列 2–5 个确切知识版本、完整要求，以及明确选中的人工反馈或面后回忆片段都会发送。反馈的确切提交身份、作答条件、维度、覆盖、当次最新决定及完整适用条件、依据、限制，或回忆的版本、标题、类型、回答基线及完整记忆不确定性，也包含在固定输入中。关系两端只取自已选概念；每条候选须引用已选复盘片段。完整限制提供语境，不扩大可引用范围。</p>
        <p v-else-if="relationEvidence" class="field-help">下列 2–5 个确切知识版本、完整要求，以及明确选中的资料摘录或训练回答片段都会发送。关系两端只取自已选概念；每条候选须引用直接依据，仍需人工逐项审阅。未选正文、题面、评价和参考不会自动加入。</p>
        <p v-else class="field-help">下列 2–5 个确切知识版本与完整要求都会发送。关系两端只能取自已选概念；笔记只作依据，不自动展开关联笔记、主题、现有关系、准备材料或训练记录。候选先留在任务中，逐条采用后才创建正式关系；没有足够依据时可以返回零条候选。</p>
        <p v-if="!relationManifestValid" class="notice" role="status">关系用途、来源或授权清单不完整，不能授权新的尝试。请重新读取任务；新任务需重新选择至少两个当前已发布的概念并预览。</p>
        <template v-else-if="relationAiFeedback && manifest.relationAiFeedback">
          <AiRelationAiFeedbackInput :input="manifest.relationAiFeedback" />
          <p class="field-help">本次不自动加入完整原答、题面、参考、评价原引文、其他观察或决定原因；知识正文和手选文字本身包含的内容仍会发送。原观察保持 AI 未审等级，关系候选须另行逐项审阅。</p>
          <details class="training-source"><summary>核对完整外发内容</summary><pre class="exact-text">{{ JSON.stringify({ purpose: manifest.purpose, instruction: manifest.instruction, sources: manifest.sources, relationAiFeedback: manifest.relationAiFeedback }, null, 2) }}</pre></details>
        </template>
        <template v-else-if="relationReview && manifest.reviewDraft">
          <AiReviewDraftInput :input="manifest.reviewDraft" />
          <p class="field-help">本次不自动加入完整原答、题面、评价报告、反馈原引文、决定原因、意见提供者称呼或面试安排；知识正文和手选文字本身包含的内容仍会发送。原反馈与回忆保持原有性质，候选仍需逐项审阅。</p>
          <details class="training-source"><summary>核对完整外发内容</summary><pre class="exact-text">{{ JSON.stringify({ purpose: manifest.purpose, instruction: manifest.instruction, sources: manifest.sources, reviewDraft: manifest.reviewDraft }, null, 2) }}</pre></details>
        </template>
        <template v-else-if="relationEvidence && manifest.relationEvidence">
          <AiRelationEvidenceInput :input="manifest.relationEvidence" />
          <details class="training-source"><summary>核对完整外发内容</summary><pre class="exact-text">{{ JSON.stringify({ purpose: manifest.purpose, instruction: manifest.instruction, sources: manifest.sources, relationEvidence: manifest.relationEvidence }, null, 2) }}</pre></details>
        </template>
      </section>
      <section v-if="preparationPlan" class="manifest-source">
        <h4>本次准备范围与用户自述</h4>
        <template v-if="manifest.preparationPlan">
          <dl class="manifest-summary"><div><dt>开始日期</dt><dd>{{ manifest.preparationPlan.startDate }}</dd></div><div><dt>结束日期（含当天）</dt><dd>{{ manifest.preparationPlan.endDate }}</dd></div><div><dt>安排时区</dt><dd>{{ manifest.preparationPlan.zoneId }}</dd></div><div><dt>整个区间的预计投入</dt><dd>{{ manifest.preparationPlan.totalMinutes }} 分钟</dd></div></dl>
          <p class="field-help">这是完整区间的名义工作量预算，与每周投入及 AI 调用费用分别记录。原日期与时区固定保留；过去日期不自动顺延，也不表示已完成。</p>
          <h4>用户自述缺口 · 完整原文</h4><pre v-if="manifest.preparationPlan.gapSummary" class="exact-text">{{ manifest.preparationPlan.gapSummary }}</pre><p v-else class="field-help">未填写自述缺口。</p>
          <p class="field-help">自述和岗位要求不证明个人能力。此次没有读取日历、历史训练、回忆卡或改进行动，建议未核验每日空闲与日程冲突；只有显式采用后才创建普通准备任务。</p>
        </template>
        <p v-if="!preparationPlanManifestValid" class="notice" role="status">目标、时间条件或授权核对记录不完整，不能授权新的尝试。请重新读取任务；新建议需重新选择目标与条件并预览。</p>
      </section>
      <section v-if="expression" class="manifest-source"><h4>表达用途：{{ expressionTypeLabel }}</h4><p class="field-help">固定类型：{{ manifest.expressionType ?? '缺失' }}。事实中的“参与”与“仅了解”保持原责任边界，不改写为本人负责。所选事实可能属于不同项目，不能擅自合并为同一经历或补造因果。</p><p v-if="!expressionManifestValid" class="notice" role="status">表达类型、事实清单或授权核对记录不完整，不能授权新的尝试。请重新读取任务；新任务需重新选择事实并预览。</p></section>
      <section class="manifest-instruction"><h4>本次{{ relation ? '关系建议' : preparationPlan ? '准备建议' : expression ? '表达生成' : manifest.purpose === 'QUESTION_DRAFT' ? '出题' : '整理' }}要求</h4><pre v-if="manifest.instruction" class="exact-text">{{ manifest.instruction }}</pre><p v-else class="field-help">没有附加要求，按{{ aiPurposeLabels[manifest.purpose] }}用途生成候选。</p></section>
      <template v-if="hasPreparations">
        <p class="field-help">{{ preparationPlan ? '仅发送所选目标的固定字段及本次时间、自述和要求；目标中的关联知识、事实、附件及其他计划不自动展开。手贴在这些字段中的私人文字仍会发送。' : expression ? '仅发送下列事实的固定字段；核实原因、来源备注、附件、项目概况、旧表达和其他事实均不自动附带。允许字段与要求中手贴的私人文字仍会发送。' : '准备正文只包含下面列出的字段，不展开来源网址、附件、未选关联项或核实说明。目标／JD 和项目概况是用户提供的背景；表达素材是用户写作内容，事实确认不改变原本的职责边界。' }}</p>
        <p v-if="!preparationChecksValid" class="notice" role="status">这份清单的准备授权核对记录缺失或不一致，不能授权新的尝试。请重新选择来源并预览；原记录与费用继续保留。</p>
        <details v-if="manifest.preparationChecks?.length" class="training-source"><summary>本地授权核对记录 · 不发送给模型</summary><p class="field-help">以下保留清单生成时的状态。来源改版、撤回／重新确认、归档／恢复或所属项目更正会使原授权失效；发送和采用前仍由服务器核验。</p><ul class="preparation-checks"><li v-for="check in manifest.preparationChecks" :key="`${check.kind}/${check.itemId}/${check.versionId}`"><strong>{{ aiSourceKindLabel(check.kind) }} · {{ manifest.sources.find(source => source.itemId === check.itemId && source.versionId === check.versionId)?.title ?? check.itemId }}</strong><span>{{ check.state === 'CURRENT' ? '清单生成时可用' : aiOriginStateLabels[check.state] }} · 来源代次 {{ check.sourceGeneration ?? '缺失' }}{{ check.kind === 'FACT' ? ` · 所属项目代次 ${check.projectGeneration ?? '缺失'}` : '' }}</span></li></ul></details>
      </template>
      <p v-if="!relation && !expressionSourceChecksValid" class="notice" role="status">表达来源的正文、事实依据或授权核对记录不完整，当前不能采用或授权新的尝试。请重新读取任务；新任务需重新选择来源并预览。</p>
      <article v-for="(source, index) in manifest.sources" :key="`${source.itemId}/${source.versionId}`" class="manifest-source">
        <header><h4>{{ index + 1 }}. {{ source.title }}</h4><RouterLink v-if="aiSourceRoute(source)" class="text-link" :to="aiSourceRoute(source)!">{{ aiSourceKindLabel(source.kind) }} · v{{ source.number }} · 查看确切版本</RouterLink><p v-else class="notice">来源类型无法识别，请重新读取记录后核对。</p></header>
        <p class="source-identity">对象 {{ source.itemId }}<br />版本 {{ source.versionId }}</p>
        <p v-if="expression && source.kind === 'FACT'" class="field-help">该事实责任：{{ factResponsibilityLabel(source) }}。已由用户确认，不代表 AI 核实过经历。</p>
        <p v-if="source.kind === 'EXPRESSION'" class="field-help">{{ expressionSourceTypeLabel(source) }} · 用户表达。下方完整类型与正文会发送；表达不能作为经历或职责已经核实的证明。关联事实只做本地检查，不会自动展开外发；已写入表达正文或要求中的文字仍会发送。</p>
        <p v-if="isAiPreparationKind(source.kind)" class="field-help">以下为实际外发的完整固定文本。{{ preparationPlan ? '目标原文是安排建议的背景，不作为能力评估或日程可行性的证明。' : '候选引用的区间以此文本为准。' }}</p>
        <pre class="exact-text">{{ source.body }}</pre>
        <details v-if="source.kind === 'EXPRESSION' && expressionCheckFor(source)" class="training-source">
          <summary>表达的事实依据核对 · 不自动发送事实正文</summary>
          <p class="field-help">以下为本次授权固定的事实身份和代次，按表达保存的依据顺序排列。它们不额外占用来源名额，也不是模型直接引用；发送和采用前仍需由服务器核验。</p>
          <ol class="preparation-checks">
            <li v-for="(check, factIndex) in expressionCheckFor(source)?.factChecks ?? []" :key="check.versionId">
              <RouterLink v-if="aiSourceRoute(check)" class="text-link" :to="aiSourceRoute(check)!">事实依据 {{ factIndex + 1 }} · 查看确切版本</RouterLink>
              <span>对象 {{ check.itemId }} · 版本 {{ check.versionId }}</span>
              <span>清单生成时可用 · 事实代次 {{ check.sourceGeneration }} · 所属项目代次 {{ check.projectGeneration }}</span>
            </li>
          </ol>
        </details>
      </article>
      <p v-if="!relation && !materialSourcesValid" class="notice" role="status">资料候选的固定摘录清单缺失或不完整，不能授权新的尝试。请重新读取任务；新任务需重新选择来源并预览。</p>
      <section v-if="materialSources.length" class="manifest-source" aria-label="本次外发的资料摘录">
        <h4>资料摘录 · {{ materialSources.length }} 条</h4>
        <p class="field-help">仅发送以下确切摘录及其来源字段，不附带整份原件、附件说明、其他解析片段或历史问答。后续新解析不会替换本次固定原文；摘录本身不表示事实已经核实。</p>
        <article v-for="(excerpt, index) in materialSources" :key="excerpt.excerptId" class="training-source">
          <h4>{{ manifest.sources.length + index + 1 }}. {{ excerpt.fileName }} · {{ excerpt.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }}</h4>
          <p class="field-help">定位：{{ excerpt.locator }}</p>
          <p v-if="excerpt.origin === 'MANUAL'" class="field-help">原文与定位由用户手工提供，不代表自动解析或项目事实已经核实。</p>
          <pre class="exact-text">{{ excerpt.text }}</pre>
          <dl class="excerpt-provenance"><div><dt>确切摘录</dt><dd>{{ excerpt.excerptId }}</dd></div><div><dt>来源附件</dt><dd>{{ excerpt.attachmentId }}</dd></div><div><dt>原件 SHA-256</dt><dd>{{ excerpt.sha256 }}</dd></div><template v-if="excerpt.origin === 'PARSED'"><div><dt>原解析执行</dt><dd>{{ excerpt.runId }}</dd></div><div><dt>原解析片段</dt><dd>{{ excerpt.segmentId }}</dd></div><div><dt>原片段内范围</dt><dd>UTF-16 [{{ excerpt.startOffset }}, {{ excerpt.endOffset }})</dd></div></template></dl>
          <RouterLink v-if="aiMaterialSourceRoute(excerpt)" class="text-link" :to="aiMaterialSourceRoute(excerpt)!">在附件资料中查看确切来源</RouterLink>
          <p v-else class="notice">来源标识不完整，请重新读取任务核对。</p>
        </article>
      </section>
    </template>
  </section>
</template>

<style scoped>
.ai-manifest { display: grid; gap: 19px; min-width: 0; } h3 { margin: 0 0 7px; font-size: 17px; } h4 { margin: 0 0 8px; font-size: 14px; overflow-wrap: anywhere; }
.manifest-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px 22px; margin: 0; } .manifest-summary .full { grid-column: 1 / -1; } dt { font-size: 12px; color: var(--muted); } dd { margin: 5px 0 0; font-size: 13px; overflow-wrap: anywhere; } .reservation { font-size: 17px; color: var(--brand-strong); } .endpoint, .source-identity { font-family: Consolas, monospace; }
.preview-budget { display: grid; gap: 8px; padding: 13px 15px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); } .preview-budget strong { font-size: 14px; }
.manifest-instruction, .manifest-source { border-top: 1px solid var(--line); padding-top: 17px; min-width: 0; } .manifest-source > header { display: grid; gap: 5px; } .manifest-source a { font-size: 12px; } .source-identity { margin: 9px 0; color: var(--muted); font-size: 11px; overflow-wrap: anywhere; }
.exact-text { margin: 10px 0 0; padding: 15px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }
.training-source { margin-top: 18px; } .training-source h5 { margin: 14px 0 0; font-size: 12px; color: var(--muted); } .training-source summary { cursor: pointer; font-size: 12px; color: var(--muted); }
.excluded-context { display: grid; gap: 9px; margin: 15px 0; padding: 13px 15px; border: 1px solid var(--line); border-radius: 6px; }
.excluded-context h5 { margin: 0; font-size: 13px; } .excluded-context ul { display: grid; gap: 10px; margin: 0; padding-left: 19px; }
.excluded-context li { overflow-wrap: anywhere; font-size: 13px; } .excluded-context li strong { font-weight: 550; } .excluded-context li span { display: block; margin-top: 4px; color: var(--muted); font-size: 12px; }
.excerpt-provenance { display: grid; gap: 9px; margin: 14px 0 0; } .excerpt-provenance > div { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 10px; } .excerpt-provenance dd { margin: 0; font-size: 12px; }
.preparation-checks { display: grid; gap: 10px; padding-left: 20px; font-size: 12px; overflow-wrap: anywhere; } .preparation-checks span { display: block; margin-top: 4px; color: var(--muted); }
@media (max-width: 650px) { .manifest-summary { grid-template-columns: 1fr; } }
</style>
