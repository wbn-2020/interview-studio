<script setup lang="ts">
import { computed } from 'vue'
import { NButton } from 'naive-ui'
import type { AiReviewDraftCitation, AiReviewDraftInput } from '../../api/aiReviewDraft'
import { aiReviewDraftCitationValid, aiReviewDraftFieldLabels, aiReviewDraftInputRoute, aiReviewDraftInputValid, aiReviewDraftKindLabels } from '../../api/aiReviewDraft'
import type { RelationAiFeedbackEvidenceSnapshot } from '../../api/relationAiFeedbackEvidence'
import { relationAiFeedbackEvidenceFieldLabels, relationAiFeedbackEvidenceSnapshotValid } from '../../api/relationAiFeedbackEvidence'
import { aiRelationAiFeedbackCitationValid, aiRelationAiFeedbackInputRoute } from '../../api/aiRelationAiFeedback'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ citations?: AiReviewDraftCitation[] | null; input?: AiReviewDraftInput | null; aiInput?: RelationAiFeedbackEvidenceSnapshot | null; disabled?: boolean }>()
const emit = defineEmits<{ refresh: [] }>()
const store = useWorkspaceStore()
const readable = computed(() => store.sessionActive && !props.disabled)
const input = computed(() => props.aiInput == null && props.input && aiReviewDraftInputValid(props.input) ? props.input : undefined)
const aiInput = computed(() => props.input == null && props.aiInput && relationAiFeedbackEvidenceSnapshotValid(props.aiInput) ? props.aiInput : undefined)
const fieldLabels = computed<Partial<Record<AiReviewDraftCitation['field'], string>>>(() => aiInput.value ? relationAiFeedbackEvidenceFieldLabels : aiReviewDraftFieldLabels)
const rows = computed(() => Array.isArray(props.citations) ? props.citations.map(citation => {
  const selected = input.value, aiSelected = aiInput.value
  // AI 原观察保留独立快照与历史空白口径，不能伪装成人工复盘输入后扩大可引用范围。
  if (aiSelected ? !aiRelationAiFeedbackCitationValid(citation, aiSelected) : !selected || !aiReviewDraftCitationValid(selected, citation)) return null
  const fragments = aiSelected?.fragments ?? selected?.feedback?.fragments ?? selected?.recollection?.fragments ?? []
  const matches = fragments.filter(fragment => fragment.field === citation.field && citation.start >= fragment.start && citation.end <= fragment.end)
  // 候选只能引用单个已选片段；完整限制字段用于保留上下文，不扩大引用授权。
  if (matches.length !== 1) return null
  const fragment = matches[0]!
  return { citation, fragment, before: fragment.quote.slice(0, citation.start - fragment.start), after: fragment.quote.slice(citation.end - fragment.start) }
}) : [])
const sourceRoute = computed(() => aiInput.value ? aiRelationAiFeedbackInputRoute(aiInput.value) : input.value ? aiReviewDraftInputRoute(input.value) : undefined)
</script>

<template>
  <section v-if="rows.length" class="review-citations" aria-label="候选的复盘原文依据">
    <h5>复盘片段引用<span v-if="aiInput"> · AI 未审反馈</span><span v-else-if="input"> · {{ aiReviewDraftKindLabels[input.kind] }}</span></h5>
    <p class="field-help">引用位置只对应当次明确选择的原文片段。{{ aiInput ? '原 AI 观察保持未审性质，报告限制、固定条件和当时决定仅提供语境；引文相符不证明原观察或关系判断正确。' : '自检、外部意见与面后回忆各自保留原有性质，整理结果仍需技术核实。' }}</p>
    <figure v-for="(row, index) in rows" :key="index">
      <template v-if="row && (input || aiInput)">
        <blockquote>{{ row.citation.quote }}</blockquote>
        <figcaption><strong>{{ fieldLabels[row.citation.field] }}</strong><span>原字段内 UTF-16 [{{ row.citation.start }}, {{ row.citation.end }})</span><span v-if="aiInput">原 AI 观察 {{ aiInput.feedbackId }} · 第 {{ aiInput.answerNumber }} 版提交 · 评价任务 {{ aiInput.taskId }} · 成功尝试 {{ aiInput.attemptId }}</span><span v-else-if="input?.feedback">反馈修订 {{ input.feedback.feedbackRevision }} · 第 {{ input.feedback.answerNumber }} 版提交</span><span v-else-if="input?.recollection">回忆版本 {{ input.recollection.versionNumber }} · {{ input.recollection.baselineKind === 'NONE' ? '未保存当时回答片段' : '用户回忆的回答片段' }}</span></figcaption>
        <details><summary>在本次已选完整片段中定位</summary><pre class="exact-text"><span>{{ row.before }}</span><mark>{{ row.citation.quote }}</mark><span>{{ row.after }}</span></pre><p class="field-help">已选片段从原字段 UTF-16 位置 {{ row.fragment.start }} 开始。</p></details>
        <RouterLink v-if="readable && sourceRoute" class="text-link" :to="sourceRoute">{{ aiInput ? '查看原评价任务的这条观察' : input?.kind === 'RECOLLECTION' ? '查看确切回忆版本' : '查看确切反馈修订' }}</RouterLink>
      </template>
      <p v-else class="notice" role="status">本条引用无法逐字匹配唯一的已选片段。<NButton text :disabled="!readable" @click="emit('refresh')">重新读取任务</NButton></p>
    </figure>
  </section>
</template>

<style scoped>
.review-citations { display: grid; gap: 12px; min-width: 0; } h5 { margin: 0; color: var(--muted); font-size: 12px; }
figure { display: grid; gap: 10px; min-width: 0; margin: 0; padding-top: 12px; border-top: 1px solid var(--line); }
blockquote { margin: 0; max-width: 75ch; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.75; }
figcaption { display: grid; gap: 5px; color: var(--muted); font-size: 12px; overflow-wrap: anywhere; } figcaption strong { color: var(--ink); font-weight: 550; }
summary { cursor: pointer; color: var(--muted); font-size: 12px; } .exact-text { margin: 10px 0; padding: 13px; border-radius: 5px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); } mark { color: inherit; background: var(--highlight, #fff1b8); }
.text-link { justify-self: start; font-size: 12px; }
</style>
