<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import type { AiOriginPage, AiOriginState, AiTrainingDraftOrigin } from '../api/ai'
import { aiDate, aiMaterialOriginRoute, aiOriginSourceRoute, aiOriginStateLabels, aiSourceKindLabel } from '../api/ai'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { aiReviewDraftKindLabels, aiReviewDraftOriginRoute, aiReviewDraftOriginValid } from '../api/aiReviewDraft'

const props = defineProps<{ itemId: string; versionId: string; versionNumber: number; editing?: boolean }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const opened = ref(false)
const cursor = ref<number>()
const cursors = ref<(number | undefined)[]>([])
const readable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const reviewStateLabels: Partial<Record<AiOriginState, string>> = { CURRENT: '确切历史来源当前可用', SUPERSEDED: '来源已有新修订，保留当时版本', ARCHIVED: '来源已归档，保留当时版本', REVIEW_REQUIRED: '来源条件或决定已变化，待复核', UNAVAILABLE: '来源当前不可用，保留历史身份' }
const decisionLabels = { ACCEPTED: '已接受', DISPUTED: '有争议', IGNORED: '已忽略' }
let viewGeneration = 0
let alive = true
watch([() => props.itemId, () => props.versionId, () => store.sessionEpoch, readable, opened, cursor], () => { viewGeneration++ }, { flush: 'sync' })
const origins = useQuery({
  queryKey: computed(() => ['ai', store.sessionEpoch, 'origins', props.itemId, props.versionId, cursor.value]),
  enabled: computed(() => opened.value && readable.value),
  queryFn: async () => {
    const generation = viewGeneration
    const epoch = store.sessionEpoch
    const value = await read<AiOriginPage>(`/ai/knowledge/${props.itemId}/versions/${props.versionId}/origins?limit=20${cursor.value !== undefined ? `&before=${cursor.value}` : ''}`)
    // 历史来源可能含训练内容，切换发布版本、身份或访问条件后不能接纳旧请求正文。
    if (!alive || !readable.value || !opened.value || generation !== viewGeneration || epoch !== store.sessionEpoch) {
      throw new ApiError(0, 'SOURCE_VIEW_CHANGED', '来源查看条件已变化，请重新读取当前版本的采用记录。')
    }
    if (!originPageValid(value)) throw new ApiError(502, 'AI_ORIGIN_MISMATCH', '采用来源的身份、类型或分页与当前发布版本不一致，请重新读取。')
    return value
  },
})
watch([() => props.itemId, () => props.versionId, () => store.sessionEpoch], () => { cursor.value = undefined; cursors.value = [] }, { flush: 'sync' })
onUnmounted(() => { alive = false; viewGeneration++ })
function next() {
  if (!readable.value || origins.data.value?.nextCursor == null) return
  cursors.value.push(cursor.value)
  cursor.value = origins.data.value.nextCursor
}
function trainingSourceValid(source: AiTrainingDraftOrigin): boolean {
  const uuid = (value: string | null) => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
  return uuid(source.sessionId) && uuid(source.questionId) && uuid(source.answerId)
    && Number.isSafeInteger(source.answerNumber) && source.answerNumber > 0 && source.answerNumber <= 100
    && (source.feedbackTaskId === null ? source.feedbackIds.length === 0 : uuid(source.feedbackTaskId)
      && source.feedbackIds.length > 0 && source.feedbackIds.length <= 5 && new Set(source.feedbackIds).size === source.feedbackIds.length
      && source.feedbackIds.every(id => /^f([1-9]|1[0-2])$/.test(id)))
}
function originPageValid(value: AiOriginPage): boolean {
  const object = (item: unknown): item is Record<string, unknown> => Boolean(item && typeof item === 'object' && !Array.isArray(item))
  const keys = (item: unknown, required: string[], optional: string[] = []) => object(item)
    && required.every(key => Object.hasOwn(item, key)) && Object.keys(item).every(key => required.includes(key) || optional.includes(key))
  const id = (item: unknown) => typeof item === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(item)
  const positive = (item: unknown): item is number => typeof item === 'number' && Number.isSafeInteger(item) && item > 0
  if (!keys(value, ['items', 'nextCursor']) || !Array.isArray(value.items) || value.items.length > 20 || value.nextCursor !== null && !positive(value.nextCursor)) return false
  if (!value.items.every(item => object(item)) || new Set(value.items.map(item => item.taskId)).size !== value.items.length) return false
  for (const [index, origin] of value.items.entries()) {
    if (!keys(origin, ['taskId', 'targetVersionId', 'targetVersionNumber', 'adoptedAt', 'direct', 'sources'], ['materialSources', 'trainingSource', 'reviewSource'])
      || !id(origin.taskId) || !id(origin.targetVersionId) || !positive(origin.targetVersionNumber) || origin.targetVersionNumber > props.versionNumber
      || origin.direct !== (origin.targetVersionId === props.versionId) || origin.direct !== (origin.targetVersionNumber === props.versionNumber)
      || typeof origin.adoptedAt !== 'string' || !Number.isFinite(Date.parse(origin.adoptedAt)) || !Array.isArray(origin.sources)
      || cursor.value !== undefined && origin.targetVersionNumber >= cursor.value
      || index > 0 && value.items[index - 1]!.targetVersionNumber <= origin.targetVersionNumber) return false
    const materials = origin.materialSources ?? []
    if (!Array.isArray(materials) || materials.length > 5 || origin.sources.length > 5) return false
    if (origin.reviewSource != null) {
      // 历史状态独立于固定来源；新分支不与旧知识、资料或训练来源混装。
      if (!aiReviewDraftOriginValid(origin.reviewSource) || origin.sources.length || materials.length || origin.trainingSource != null) return false
    } else if (origin.trainingSource != null) {
      if (!keys(origin.trainingSource, ['sessionId', 'questionId', 'answerId', 'answerNumber', 'questionTitle', 'feedbackTaskId', 'feedbackIds', 'state'])
        || !Array.isArray(origin.trainingSource.feedbackIds) || !trainingSourceValid(origin.trainingSource)
        || !['CURRENT', 'UNAVAILABLE'].includes(origin.trainingSource.state) || origin.sources.length || materials.length) return false
    } else if (origin.sources.length + materials.length < 1 || origin.sources.length + materials.length > 5) return false
    if (!origin.sources.every(source => keys(source, ['itemId', 'versionId', 'number', 'title', 'state'], ['kind'])
      && id(source.itemId) && id(source.versionId) && positive(source.number) && typeof source.title === 'string'
      && Object.hasOwn(aiOriginStateLabels, source.state) && (source.kind == null || ['NOTE', 'CONCEPT', 'TARGET', 'PROJECT', 'FACT', 'EXPRESSION'].includes(source.kind)))) return false
    if (!materials.every(source => keys(source, ['excerptId', 'attachmentId', 'fileName', 'sha256', 'origin', 'runId', 'segmentId', 'locator', 'startOffset', 'endOffset', 'state'])
      && id(source.excerptId) && id(source.attachmentId) && typeof source.fileName === 'string' && typeof source.locator === 'string'
      && /^[0-9a-f]{64}$/.test(source.sha256) && ['CURRENT', 'UNAVAILABLE'].includes(source.state)
      && (source.origin === 'MANUAL' ? source.runId === null && source.segmentId === null && source.startOffset === null && source.endOffset === null
        : source.origin === 'PARSED' && id(source.runId) && id(source.segmentId) && typeof source.startOffset === 'number' && Number.isSafeInteger(source.startOffset)
          && source.startOffset >= 0 && positive(source.endOffset) && source.endOffset > source.startOffset))) return false
  }
  return value.nextCursor === null || value.items.length === 20 && value.nextCursor === value.items.at(-1)?.targetVersionNumber
}
</script>

<template>
  <section class="knowledge-origins" :aria-label="`发布版本 ${versionNumber} 的 AI 采用来源`">
    <header><div><h3>AI 采用来源 · 发布 v{{ versionNumber }}</h3><p class="field-help">查看本版本及更早版本发生的采用记录。</p></div><NButton size="small" :disabled="!readable" :aria-expanded="opened" @click="opened = !opened">{{ opened ? '收起来源' : '查看采用来源' }}</NButton></header>
    <template v-if="opened && readable">
      <p v-if="editing" class="notice">上方含草稿或本地改写。下列记录属于发布 v{{ versionNumber }}，不作为当前改写的逐句依据。</p>
      <p class="field-help">采用记录说明内容整理的历史来源，不证明技术结论正确，也不证明后来编辑的每一句文字都由这些材料支持。来源状态基于当前检查，不证明接入前的更正历史完整，也不能据此恢复当时的外发授权。</p>
      <p v-if="origins.isPending.value" role="status">正在读取采用来源…</p><p v-if="origins.error.value" class="notice error" role="alert">{{ errorText(origins.error.value) }}<NButton text @click="origins.refetch()">重新读取来源</NButton></p>
      <p v-else-if="origins.data.value && !origins.data.value.items.length" class="field-help">截至所选发布版本，尚无 AI 候选采用记录。</p>
      <article v-for="origin in origins.data.value?.items" :key="origin.taskId" class="origin-record">
        <div class="origin-heading"><strong>{{ origin.direct ? '本版本直接采用' : `历史采用 · 发布于 v${origin.targetVersionNumber}` }}</strong><time>{{ aiDate(origin.adoptedAt) }}</time></div>
        <p v-if="!origin.direct" class="field-help">这次采用发生在更早版本，所选版本可能经过后续人工编辑。</p>
        <div class="origin-links"><RouterLink class="text-link" :to="{ name: 'ai', query: { item: origin.taskId } }">查看原任务、候选与引用</RouterLink><RouterLink class="text-link" :to="{ name: 'knowledge', query: { item: itemId, version: origin.targetVersionId } }">查看采用时发布的 v{{ origin.targetVersionNumber }}</RouterLink></div>
        <template v-if="origin.sources.length"><h4>当次外发的知识与准备版本</h4>
          <ul class="origin-sources"><li v-for="source in origin.sources" :key="`${source.itemId}/${source.versionId}`"><RouterLink v-if="aiOriginSourceRoute(source)" class="text-link" :to="aiOriginSourceRoute(source)!">{{ source.title }} · v{{ source.number }}</RouterLink><span v-else>{{ source.title }} · v{{ source.number }}</span><span class="source-state">{{ source.kind == null ? '历史知识来源' : aiSourceKindLabel(source.kind) }} · {{ aiOriginStateLabels[source.state] }}</span>
            <span v-if="source.kind === 'EXPRESSION'" class="source-state">这是用户保存的表达素材，不作为项目事实确认；其关联事实仅参与授权核对，未单独选择的事实原文不会随表达自动外发。</span>
            <span v-if="source.kind === 'EXPRESSION' && source.state === 'REVIEW_REQUIRED'" class="source-state">表达依据待复核，或表达自身、事实依据及所属项目的代次与当时不一致。请回到确切表达核对；历史正文与引用保留在原任务中，原授权不会恢复。</span>
            <span v-if="source.state !== 'UNAVAILABLE' && !aiOriginSourceRoute(source)" class="source-state">来源类型无法识别，请重新读取来源后核对。<NButton text :disabled="!readable" @click="origins.refetch()">重新读取来源</NButton></span></li></ul>
        </template>
        <template v-if="origin.materialSources?.length"><h4>当次外发的附件摘录</h4><p class="field-help">这里保留采用时的来源标识；完整固定正文及引用区间见原任务。</p>
          <ul class="origin-sources"><li v-for="source in origin.materialSources ?? []" :key="source.excerptId">
            <RouterLink v-if="aiMaterialOriginRoute(source)" class="text-link" :to="aiMaterialOriginRoute(source)!">{{ source.fileName }} · 查看确切摘录</RouterLink><span v-else>{{ source.fileName }}</span>
            <span class="source-state">{{ source.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ source.locator }} · {{ source.state === 'CURRENT' ? '当前可用' : '当前不可用，保留历史来源' }}</span>
            <span class="source-state">摘录 {{ source.excerptId }} · 附件 {{ source.attachmentId }}</span><span class="source-state">原件 SHA-256 {{ source.sha256 }}</span>
            <span v-if="source.origin === 'PARSED'" class="source-state">原解析执行 {{ source.runId }} · 片段 {{ source.segmentId }} · 原片段内 UTF-16 [{{ source.startOffset }}, {{ source.endOffset }})</span><span v-else class="source-state">原文和定位由用户手工提供，不代表自动解析或事实确认。</span>
            <span v-if="source.state === 'CURRENT' && !aiMaterialOriginRoute(source)" class="source-state">来源标识不完整，请重新读取来源后核对。<NButton text :disabled="!readable" @click="origins.refetch()">重新读取来源</NButton></span>
          </li></ul>
        </template>
        <section v-if="origin.trainingSource" class="training-origin" aria-label="采用时的确切训练来源">
          <h4>训练片段来源 · {{ origin.trainingSource.questionTitle }}</h4>
          <p class="field-help">第 {{ origin.trainingSource.answerNumber }} 版提交 · {{ origin.trainingSource.state === 'CURRENT' ? '确切历史来源当前可用' : '当前不可用，保留历史来源' }}。采用记录不表示回答已经改正、反馈已经接受或知识已经掌握。</p>
          <dl><div><dt>训练会话</dt><dd>{{ origin.trainingSource.sessionId }}</dd></div><div><dt>训练题目</dt><dd>{{ origin.trainingSource.questionId }}</dd></div><div><dt>确切提交</dt><dd>{{ origin.trainingSource.answerId }}</dd></div><template v-if="origin.trainingSource.feedbackTaskId"><div><dt>原评价任务</dt><dd>{{ origin.trainingSource.feedbackTaskId }}</dd></div><div><dt>选定反馈</dt><dd>{{ origin.trainingSource.feedbackIds.join('、') }} · AI 未审</dd></div></template></dl>
          <p v-if="!origin.trainingSource.feedbackTaskId" class="field-help">这次整理未附带评价反馈。</p>
          <p v-if="!trainingSourceValid(origin.trainingSource)" class="notice" role="status">训练来源标识不完整，请重新读取采用记录后核对。<NButton text :disabled="!readable" @click="origins.refetch()">重新读取来源</NButton></p>
          <div v-else-if="origin.trainingSource.state === 'CURRENT'" class="origin-links"><RouterLink class="text-link" :to="{ name: 'training', query: { item: origin.trainingSource.sessionId, question: origin.trainingSource.questionId, answer: origin.trainingSource.answerId } }">查看第 {{ origin.trainingSource.answerNumber }} 版回答</RouterLink><RouterLink v-if="origin.trainingSource.feedbackTaskId" class="text-link" :to="{ name: 'ai', query: { item: origin.trainingSource.feedbackTaskId } }">查看原评价报告</RouterLink></div>
          <p class="field-help">完整的固定题面、所选片段、反馈及其原引文保留在原任务中；后续新提交不替换这次历史来源。</p>
        </section>
        <section v-if="origin.reviewSource" class="training-origin" aria-label="采用时的确切复盘来源">
          <h4>{{ aiReviewDraftKindLabels[origin.reviewSource.kind] }}整理来源</h4>
          <p class="field-help">{{ reviewStateLabels[origin.reviewSource.state] }}。当前可用性与当时保存内容分开，历史版本不会替换为最新记录。</p>
          <template v-if="origin.reviewSource.feedback">
            <dl><div><dt>反馈身份</dt><dd>{{ origin.reviewSource.feedback.feedbackId }} · 修订 {{ origin.reviewSource.feedback.feedbackRevision }}</dd></div><div><dt>确切提交</dt><dd>{{ origin.reviewSource.feedback.answer.answerId }} · 第 {{ origin.reviewSource.feedback.answerNumber }} 版</dd></div><div><dt>训练会话</dt><dd>{{ origin.reviewSource.feedback.answer.sessionId }}</dd></div><div><dt>训练题目</dt><dd>{{ origin.reviewSource.feedback.answer.questionId }}</dd></div><div><dt>当时反馈决定</dt><dd>修订 {{ origin.reviewSource.feedback.decisionRevision }} · {{ origin.reviewSource.feedback.decisionChoice ? decisionLabels[origin.reviewSource.feedback.decisionChoice] : '尚未决定' }}</dd></div></dl>
            <p class="field-help">{{ origin.reviewSource.kind === 'SELF_CHECK' ? '这是本人保存的自检，反映当时的个人观察。' : '这是手工记录的外部意见，保留意见来源的性质。' }}接受反馈或采用候选均不证明技术结论已经核实。</p>
          </template>
          <template v-else-if="origin.reviewSource.recollection">
            <h4>{{ origin.reviewSource.recollection.title }}</h4>
            <dl><div><dt>回忆身份</dt><dd>{{ origin.reviewSource.recollection.recollectionId }}</dd></div><div><dt>确切版本</dt><dd>{{ origin.reviewSource.recollection.versionId }} · v{{ origin.reviewSource.recollection.versionNumber }}</dd></div><div><dt>当时来源修订</dt><dd>{{ origin.reviewSource.recollection.sourceRevision }}</dd></div><div><dt>回答基线</dt><dd>{{ origin.reviewSource.recollection.baselineKind === 'NONE' ? '未保存当时回答片段' : '用户回忆的回答片段' }}</dd></div></dl>
            <p class="field-help">面后回忆包含记忆与不确定性，不能作为当时正式原答或已核实事实。完整不确定性及本次片段保留在原任务中。</p>
          </template>
          <RouterLink v-if="aiReviewDraftOriginRoute(origin.reviewSource)" class="text-link" :to="aiReviewDraftOriginRoute(origin.reviewSource)!">查看确切{{ origin.reviewSource.kind === 'RECOLLECTION' ? '回忆版本' : '反馈修订' }}</RouterLink>
          <p class="field-help">采用记录只说明整理出处，不证明后来人工改写的每句话均由这些片段支持。</p>
        </section>
      </article>
      <div v-if="cursors.length || origins.data.value?.nextCursor != null" class="row-actions"><NButton size="small" :disabled="!cursors.length" @click="cursor = cursors.pop()">较新记录</NButton><NButton size="small" :disabled="origins.data.value?.nextCursor == null" @click="next">更早记录</NButton></div>
    </template>
  </section>
</template>

<style scoped>
.knowledge-origins { display: grid; gap: 15px; margin-top: 24px; padding-top: 21px; border-top: 1px solid var(--line); min-width: 0; }
header, .origin-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; }
header > div, .origin-record { display: grid; gap: 9px; min-width: 0; }
h3 { margin: 0; font-size: 16px; } h4 { margin: 5px 0 0; font-size: 13px; }
.origin-record { padding-top: 16px; border-top: 1px solid var(--line); } .origin-heading { flex-wrap: wrap; } .origin-heading strong { font-size: 13px; }
time, .source-state { color: var(--muted); font-size: 12px; }
.origin-links { display: flex; gap: 9px 17px; flex-wrap: wrap; font-size: 13px; }
.origin-sources { display: grid; gap: 11px; padding-left: 20px; margin: 3px 0; }
.origin-sources li { padding-left: 3px; font-size: 13px; overflow-wrap: anywhere; } .source-state { display: block; margin-top: 4px; }
.training-origin { display: grid; gap: 10px; min-width: 0; } .training-origin h4, .training-origin p { overflow-wrap: anywhere; }
.training-origin dl { display: grid; gap: 8px; margin: 0; font-size: 12px; } .training-origin dl > div { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 10px; } .training-origin dt { color: var(--muted); } .training-origin dd { margin: 0; overflow-wrap: anywhere; }
@media (max-width: 650px) { header { flex-wrap: wrap; } }
</style>
