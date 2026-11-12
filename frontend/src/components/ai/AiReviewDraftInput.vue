<script setup lang="ts">
import { computed } from 'vue'
import type { AiReviewDraftInput } from '../../api/aiReviewDraft'
import { aiReviewDraftFieldLabels, aiReviewDraftKindLabels } from '../../api/aiReviewDraft'
import { questionDimensionLabels, questionTypeLabels } from '../../api/bank'
import { recollectionBaselineLabels } from '../../api/recollection'
import { reviewChoiceLabels, reviewCoverageLabels } from '../../api/review'

const props = defineProps<{ input: AiReviewDraftInput }>()
const fragments = computed(() => props.input.feedback?.fragments ?? props.input.recollection?.fragments ?? [])
const answerLabels: Record<string, string> = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
</script>

<template>
  <section class="review-draft-input" aria-label="复盘原文的完整固定输入">
    <header><h4>{{ aiReviewDraftKindLabels[input.kind] }} · 完整固定输入</h4><p class="field-help">下面保留当次固定背景与手选片段，用于核对原文与限制。自检和外部意见保持待核实身份，个人回忆保留记忆的不确定性；整理不会证明技术正确、经历真实或已经掌握。</p></header>
    <template v-if="input.feedback">
      <dl><dt>反馈与确切修订</dt><dd>{{ input.feedback.feedbackId }} · 修订 {{ input.feedback.feedbackRevision }}</dd><dt>训练会话</dt><dd>{{ input.feedback.answer.sessionId }}</dd><dt>训练题目</dt><dd>{{ input.feedback.answer.questionId }}</dd><dt>确切提交</dt><dd>{{ input.feedback.answer.answerId }} · 第 {{ input.feedback.answerNumber }} 版</dd><dt>提交条件</dt><dd>{{ answerLabels[input.feedback.answerCondition] }} · {{ input.feedback.answerCondition }}</dd><dt>维度／覆盖</dt><dd>{{ questionDimensionLabels[input.feedback.dimension] }} · {{ input.feedback.dimension }} / {{ reviewCoverageLabels[input.feedback.coverage] }} · {{ input.feedback.coverage }}</dd><dt>当时决定</dt><dd>修订 {{ input.feedback.decisionRevision }} · {{ input.feedback.decisionChoice ? reviewChoiceLabels[input.feedback.decisionChoice] : '尚未决定' }} · {{ input.feedback.decisionChoice ?? 'null' }}</dd></dl>
      <RouterLink class="text-link" :to="{ name: 'review', query: { ...input.feedback.answer, feedbackId: input.feedback.feedbackId, revision: String(input.feedback.feedbackRevision) } }">查看这版反馈与确切提交</RouterLink>
      <div v-for="field in (['condition', 'basis', 'limitation'] as const)" :key="field" class="source-field"><h5>{{ ({ condition: '固定适用条件', basis: '固定观察依据', limitation: '固定判断限制' })[field] }}</h5><pre class="exact-text">{{ input.feedback[field] || '（空）' }}</pre></div>
      <p class="field-help">固定输入未包含决定原因、意见提供者称呼与原反馈中的引文；观察和建议仅保留当次明确选中的片段。</p>
    </template>
    <template v-else-if="input.recollection">
      <dl><dt>回忆</dt><dd>{{ input.recollection.recollectionId }}</dd><dt>确切版本</dt><dd>{{ input.recollection.versionId }} · v{{ input.recollection.versionNumber }}</dd><dt>来源修订</dt><dd>{{ input.recollection.sourceRevision }}</dd><dt>标题</dt><dd>{{ input.recollection.title }}</dd><dt>类型</dt><dd>{{ questionTypeLabels[input.recollection.type] }} · {{ input.recollection.type }}</dd><dt>旧回答情况</dt><dd>{{ recollectionBaselineLabels[input.recollection.baselineKind] }} · {{ input.recollection.baselineKind }}</dd></dl>
      <RouterLink class="text-link" :to="{ name: 'recollections', query: { item: input.recollection.recollectionId, versionId: input.recollection.versionId } }">查看这版回忆</RouterLink>
      <div class="source-field"><h5>固定记忆不确定性 · 完整原文</h5><pre class="exact-text">{{ input.recollection.uncertainty }}</pre></div>
      <p class="field-help">固定输入未包含面试安排及其标题、时间、轮次。没有旧回答时不包含回答回忆片段。</p>
    </template>
    <section class="fragments"><h4>明确选择的片段 · {{ fragments.length }} 段</h4><article v-for="(fragment, index) in fragments" :key="`${fragment.field}/${fragment.start}/${fragment.end}/${index}`"><h5>{{ index + 1 }}. {{ aiReviewDraftFieldLabels[fragment.field] }} · {{ fragment.field }}</h5><p class="field-help">原字段内 UTF-16 [{{ fragment.start }}, {{ fragment.end }})</p><pre class="exact-text">{{ fragment.quote }}</pre></article></section>
    <details><summary>核对完整固定 Input</summary><pre class="exact-text">{{ JSON.stringify(input, null, 2) }}</pre></details>
  </section>
</template>

<style scoped>
.review-draft-input, header, .source-field, .fragments, article { display: grid; gap: 10px; min-width: 0; }.review-draft-input { gap: 16px; }
h4 { margin: 0; font-size: 14px; overflow-wrap: anywhere; }h5 { margin: 0; color: var(--muted); font-size: 12px; }
dl { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 9px 12px; margin: 0; font-size: 12px; }dt { color: var(--muted); }dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.fragments { padding-top: 16px; border-top: 1px solid var(--line); }.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }
summary { cursor: pointer; color: var(--muted); font-size: 12px; }details > pre { margin-top: 10px; }.text-link { justify-self: start; font-size: 12px; }.field-help { overflow-wrap: anywhere; }
@media (max-width: 550px) { dl { grid-template-columns: 1fr; gap: 5px; }dd { margin-bottom: 7px; } }
</style>
