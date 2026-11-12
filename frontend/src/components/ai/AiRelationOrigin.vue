<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiRelationContent, AiRelationOrigin as AiRelationOriginRecord } from '../../api/ai'
import { aiDate, aiOriginStateLabels, aiSourceKindLabel } from '../../api/ai'
import { aiRelationEvidenceOriginValid } from '../../api/aiRelationEvidence'
import { aiRelationReviewOriginValid } from '../../api/aiRelationReview'
import { aiRelationAiFeedbackOriginValid } from '../../api/aiRelationAiFeedback'
import AiRelationEvidenceInput from './AiRelationEvidenceInput.vue'
import AiReviewDraftInput from './AiReviewDraftInput.vue'
import AiRelationAiFeedbackInput from './AiRelationAiFeedbackInput.vue'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ relationId: string }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const readable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const opened = ref(false)
const state = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const origin = shallowRef<AiRelationOriginRecord | null>()
const problem = ref('')
const panelId = computed(() => `relation-origin-${props.relationId}`)
const relationLabels: Record<AiRelationContent['type'], string> = {
  PRECEDES: '前置于', PART_OF: '属于（组成部分）', APPLIED_IN: '应用于', RELATED: '相关（无方向）', CONTRASTS: '对比（无方向）',
}
const reviewStateLabels = {
  CURRENT: '本次读取时，复盘来源与当次固定输入仍一致。',
  SUPERSEDED: '反馈或回忆已有新版本，以下保留当次固定输入。',
  ARCHIVED: '回忆已归档，以下保留当次固定输入。',
  REVIEW_REQUIRED: '反馈决定或来源使用条件已变化，需要重新核对；以下保留当次固定输入。',
  UNAVAILABLE: '当前无法核对复盘原出处，以下保留当次固定输入。',
}
const aiFeedbackStateLabels = {
  CURRENT: '本次读取时，原 AI 反馈与当次固定输入及决定仍一致。',
  REVIEW_REQUIRED: '原 AI 反馈的决定或使用条件需要重新核对；以下保留当次固定输入。',
  UNAVAILABLE: '当前无法核对原 AI 反馈，以下保留当次固定输入。',
}
const comparisons = computed(() => {
  const value = origin.value
  return value?.decision.content ? [
    { key: 'model', title: '模型原建议', content: value.candidate },
    { key: 'adopted', title: '人工最终采用', content: value.decision.content },
  ] : []
})
let generation = 0
let alive = true

function reset() {
  generation++
  opened.value = false
  state.value = 'idle'
  origin.value = undefined
  problem.value = ''
}
watch([() => props.relationId, () => store.sessionEpoch, readable,
  () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], reset, { flush: 'sync' })
onUnmounted(() => { alive = false; generation++ })

const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
// 历史标题与首次创建理由保留 Java isBlank 口径，不用较新的补充规则重新判定旧记录。
const hasRelationText = (value: string) => /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
function contentValid(content: AiRelationContent | null | undefined): content is AiRelationContent {
  return Boolean(content && uuid(content.sourceItemId) && uuid(content.targetItemId) && content.sourceItemId !== content.targetItemId
    && Object.hasOwn(relationLabels, content.type) && typeof content.reason === 'string' && hasRelationText(content.reason))
}
function originValid(value: AiRelationOriginRecord, relationId: string): boolean {
  if (!value || !uuid(value.taskId) || typeof value.relationExists !== 'boolean'
    || !Array.isArray(value.sources) || value.sources.length < 2 || value.sources.length > 5
    || !(value.relationAiFeedback != null ? aiRelationAiFeedbackOriginValid(value)
      : value.reviewDraft != null ? aiRelationReviewOriginValid(value) : aiRelationEvidenceOriginValid(value))) return false
  // 新关系只允许确切知识版本与四种可用状态，不能把未知来源或读取故障解释成普通失效。
  if (!value.sources.every(source => source && uuid(source.itemId) && uuid(source.versionId)
    && Number.isSafeInteger(source.number) && source.number > 0 && typeof source.title === 'string' && hasRelationText(source.title)
    && (source.kind === 'NOTE' || source.kind === 'CONCEPT')
    && ['CURRENT', 'SUPERSEDED', 'ARCHIVED', 'UNAVAILABLE'].includes(source.state))
    || new Set(value.sources.map(source => source.itemId)).size !== value.sources.length
    || new Set(value.sources.map(source => source.versionId)).size !== value.sources.length) return false
  const candidate = value.candidate
  const decision = value.decision
  if (!candidate || !/^r([1-9]|10)$/.test(candidate.id) || !contentValid(candidate)
    || !decision || decision.status !== 'ADOPTED' || decision.relationId !== relationId || decision.supplementId != null
    || decision.candidateId !== candidate.id || !contentValid(decision.content)
    || typeof decision.createdAt !== 'string' || !Number.isFinite(Date.parse(decision.createdAt))) return false
  const originalPair = [candidate.sourceItemId, candidate.targetItemId]
  return originalPair.every(id => value.sources.some(source => source.itemId === id && source.kind === 'CONCEPT'))
    && originalPair.includes(decision.content.sourceItemId) && originalPair.includes(decision.content.targetItemId)
}

async function load() {
  if (!opened.value || !readable.value || state.value === 'loading') return
  const request = ++generation
  const epoch = store.sessionEpoch
  const relationId = props.relationId
  const current = () => alive && opened.value && readable.value && request === generation
    && epoch === store.sessionEpoch && relationId === props.relationId
  state.value = 'loading'
  origin.value = undefined
  problem.value = ''
  try {
    const value = await read<AiRelationOriginRecord | null>(`/ai/relations/${encodeURIComponent(relationId)}/origin`)
    // 不缓存到关系列表；收起、切换身份或训练资料权限变化后，迟到结果不能重新展开正文。
    if (!current()) return
    if (value !== null && !originValid(value, relationId)) {
      throw new ApiError(0, 'RELATION_ORIGIN_INVALID', '关系来源记录不完整或与当前关系不一致，请重新读取后核对。')
    }
    origin.value = value
    state.value = 'ready'
  } catch (error) {
    if (!current()) return
    problem.value = errorText(error)
    state.value = 'error'
  }
}
function toggle() {
  if (!readable.value) return
  if (opened.value) { reset(); return }
  opened.value = true
  void load()
}
function sourceName(id: string): string {
  const source = origin.value?.sources.find(item => item.itemId === id)
  return source ? `${source.title} · v${source.number}` : id
}
function arrow(type: AiRelationContent['type']): string { return type === 'RELATED' || type === 'CONTRASTS' ? '↔' : '→' }
</script>

<template>
  <section class="relation-origin" aria-label="语义关系最初创建时的 AI 来源">
    <NButton text size="small" :disabled="!readable" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起 AI 首次创建来源' : '查看 AI 首次创建来源' }}</NButton>
    <div v-if="opened && readable" :id="panelId" class="origin-content" :aria-busy="state === 'loading'">
      <p v-if="state === 'loading'" role="status">正在读取关系最初创建时的来源…</p>
      <div v-else-if="state === 'error'" class="notice error" role="alert"><p>{{ problem }}</p><NButton text size="small" :disabled="!readable" @click="load">重新读取来源</NButton></div>
      <template v-else-if="state === 'ready'">
        <p v-if="origin === null" class="field-help">没有这条关系最初创建时的 AI 候选记录；这不表示关系已经人工核实。后续追加的理由和出处请查看补充记录。</p>
        <template v-else-if="origin">
          <p v-if="!origin.relationExists" class="notice" role="status">正式关系已移除。以下保留当时的候选、采用决定和来源。</p>
          <div class="origin-heading"><strong>首次创建记录</strong><time :datetime="origin.decision.createdAt">{{ aiDate(origin.decision.createdAt) }}</time></div>
          <p class="field-help">这里保留创建关系时的选择；后续补充单独列入补充记录。标题和版本保持当时内容，来源状态按本次读取核对，采用不证明关系语义正确。</p>
          <article v-for="comparison in comparisons" :key="comparison.key" class="relation-comparison">
            <h4>{{ comparison.title }}</h4>
            <p class="relation-endpoints"><span>{{ sourceName(comparison.content.sourceItemId) }}</span><strong>{{ relationLabels[comparison.content.type] }} {{ arrow(comparison.content.type) }}</strong><span>{{ sourceName(comparison.content.targetItemId) }}</span></p>
            <p class="relation-reason">{{ comparison.content.reason }}</p>
          </article>
          <RouterLink class="text-link task-link" :to="{ name: 'ai', query: { item: origin.taskId } }">查看原任务、候选引用与限制</RouterLink>
          <h4>当次外发的全部知识版本</h4>
          <ul class="origin-sources"><li v-for="source in origin.sources" :key="`${source.itemId}/${source.versionId}`">
            <RouterLink v-if="source.state !== 'UNAVAILABLE'" class="text-link" :to="{ name: 'knowledge', query: { item: source.itemId, version: source.versionId } }">{{ source.title }} · v{{ source.number }}</RouterLink><span v-else>{{ source.title }} · v{{ source.number }}</span>
            <span class="source-state">{{ aiSourceKindLabel(source.kind) }} · {{ aiOriginStateLabels[source.state] }}</span>
          </li></ul>
          <section v-if="origin.relationEvidence" class="origin-evidence" aria-label="首次创建关系时的直接原文依据">
            <h4>当次固定的直接原文依据</h4>
            <p :class="origin.relationEvidenceState === 'CURRENT' ? 'field-help' : 'notice'" role="status">{{ origin.relationEvidenceState === 'CURRENT' ? '本次读取已核对所选资料或确切训练片段与固定输入一致。' : '当前无法核对直接原出处，以下继续保留当时的固定输入。' }} 来源可核对不证明关系语义或训练自述正确。</p>
            <AiRelationEvidenceInput :input="origin.relationEvidence" />
          </section>
          <section v-else-if="origin.reviewDraft" class="origin-evidence" aria-label="首次创建关系时的复盘原文依据">
            <h4>当次固定的复盘原文依据</h4>
            <p :class="origin.reviewDraftState === 'CURRENT' ? 'field-help' : 'notice'" role="status">{{ origin.reviewDraftState ? reviewStateLabels[origin.reviewDraftState] : '' }} 来源状态不证明关系语义、反馈判断或个人回忆正确。</p>
            <AiReviewDraftInput :input="origin.reviewDraft" />
          </section>
          <section v-else-if="origin.relationAiFeedback" class="origin-evidence" aria-label="首次创建关系时的 AI 反馈依据">
            <h4>当次固定的 AI 反馈依据</h4>
            <p :class="origin.relationAiFeedbackState === 'CURRENT' ? 'field-help' : 'notice'" role="status">{{ origin.relationAiFeedbackState ? aiFeedbackStateLabels[origin.relationAiFeedbackState] : '' }} 来源状态不改变 AI 未审等级，也不证明原观察或关系语义正确。</p>
            <AiRelationAiFeedbackInput :input="origin.relationAiFeedback" />
          </section>
        </template>
        <div class="row-actions"><NButton text size="small" :disabled="!readable" @click="load">重新读取来源</NButton></div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.relation-origin { margin-top: 8px; min-width: 0; }
.origin-content { display: grid; gap: 12px; min-width: 0; margin-top: 12px; padding-top: 14px; border-top: 1px solid var(--line); }
.origin-evidence { display: grid; gap: 12px; min-width: 0; padding-top: 14px; border-top: 1px solid var(--line); }
.origin-content p, h4 { margin: 0; overflow-wrap: anywhere; }
.origin-heading { display: flex; justify-content: space-between; align-items: baseline; flex-wrap: wrap; gap: 8px 15px; font-size: 13px; }
.origin-heading time, .source-state { font-size: 12px; color: var(--muted); }
.relation-comparison { display: grid; gap: 8px; min-width: 0; }
h4 { font-size: 13px; }
.relation-endpoints { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px 10px; font-size: 13px; }
.relation-endpoints > * { min-width: 0; overflow-wrap: anywhere; }
.relation-reason { white-space: pre-wrap; font-size: 13px; }
.task-link { justify-self: start; font-size: 13px; }
.origin-sources { display: grid; gap: 10px; margin: 0; padding-left: 20px; }
.origin-sources li { padding-left: 3px; overflow-wrap: anywhere; font-size: 13px; }
.source-state { display: block; margin-top: 4px; }
</style>
