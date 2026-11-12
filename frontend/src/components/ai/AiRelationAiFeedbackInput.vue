<script setup lang="ts">
import { computed } from 'vue'
import type { RelationAiFeedbackEvidenceSnapshot } from '../../api/relationAiFeedbackEvidence'
import { relationAiFeedbackEvidenceFieldLabels } from '../../api/relationAiFeedbackEvidence'
import { aiRelationAiFeedbackInputRoute } from '../../api/aiRelationAiFeedback'
import { questionDimensionLabels } from '../../api/bank'
import { reviewChoiceLabels, reviewCoverageLabels } from '../../api/review'

const props = defineProps<{ input: RelationAiFeedbackEvidenceSnapshot }>()
const sourceRoute = computed(() => aiRelationAiFeedbackInputRoute(props.input))
const answerLabels = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
const fixedFields = [
  { key: 'reportLimitations', label: '完整报告限制' }, { key: 'validationNote', label: '报告验证说明' },
  { key: 'condition', label: '固定适用条件' }, { key: 'basis', label: '固定观察依据' }, { key: 'limitation', label: '固定判断限制' },
] as const
</script>

<template>
  <section class="ai-feedback-input" aria-label="AI 未审反馈的完整固定输入">
    <header><h4>AI 未审反馈 · 完整固定输入</h4><p class="field-help">下面保留当次成功报告身份、完整判断限制、当时决定与手选片段。原反馈及其派生候选均需人工核实，处理决定不会证明关系语义、技术正确或已经掌握。</p></header>
    <dl>
      <dt>AI 任务</dt><dd>{{ input.taskId }}</dd><dt>成功尝试</dt><dd>{{ input.attemptId }}</dd><dt>单项反馈</dt><dd>{{ input.feedbackId }}</dd><dt>固定报告标识</dt><dd>{{ input.reportHash }}</dd>
      <dt>未审等级</dt><dd>AI 未审 · {{ input.level }}</dd><dt>训练会话</dt><dd>{{ input.answer.sessionId }}</dd><dt>训练题目</dt><dd>{{ input.answer.questionId }}</dd><dt>确切提交</dt><dd>{{ input.answer.answerId }} · 第 {{ input.answerNumber }} 版</dd>
      <dt>提交条件</dt><dd>{{ answerLabels[input.answerCondition] }} · {{ input.answerCondition }}</dd><dt>维度／覆盖</dt><dd>{{ questionDimensionLabels[input.dimension] }} · {{ input.dimension }} / {{ reviewCoverageLabels[input.coverage] }} · {{ input.coverage }}</dd>
      <dt>当时决定</dt><dd>修订 {{ input.decisionRevision }} · {{ input.decisionChoice ? reviewChoiceLabels[input.decisionChoice] : '尚未决定' }} · {{ input.decisionChoice ?? 'null' }}</dd>
    </dl>
    <p v-if="input.decisionChoice === 'DISPUTED' || input.decisionChoice === 'IGNORED'" class="notice" role="status">这项反馈当时{{ input.decisionChoice === 'DISPUTED' ? '已被提出争议' : '已被忽略' }}。该决定随固定来源保留，反馈继续保持 AI 未审。</p>
    <RouterLink v-if="sourceRoute" class="text-link" :to="sourceRoute">查看这项 AI 反馈与确切提交</RouterLink>
    <div v-for="field in fixedFields" :key="field.key" class="source-field"><h5>{{ field.label }} · 完整原文</h5><pre class="exact-text">{{ input[field.key] || '（空）' }}</pre></div>
    <p class="field-help">观察和建议仅保留当次手选片段。固定输入未包含报告总结、原报告引文、原任务清单、题面和完整原答；完整限制用于保留判断背景，引用仍须落入已选片段。</p>
    <section class="fragments"><h4>明确选择的片段 · {{ input.fragments.length }} 段</h4><article v-for="(fragment, index) in input.fragments" :key="`${fragment.field}/${fragment.start}/${fragment.end}/${index}`"><h5>{{ index + 1 }}. {{ relationAiFeedbackEvidenceFieldLabels[fragment.field] }} · {{ fragment.field }}</h5><p class="field-help">原字段内 UTF-16 [{{ fragment.start }}, {{ fragment.end }})</p><pre class="exact-text">{{ fragment.quote }}</pre></article></section>
    <details><summary>核对完整固定 Input</summary><pre class="exact-text">{{ JSON.stringify(input, null, 2) }}</pre></details>
  </section>
</template>

<style scoped>
.ai-feedback-input, header, .source-field, .fragments, article { display: grid; gap: 10px; min-width: 0; }.ai-feedback-input { gap: 16px; }h4 { margin: 0; font-size: 14px; overflow-wrap: anywhere; }h5 { margin: 0; color: var(--muted); font-size: 12px; }dl { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 9px 12px; margin: 0; font-size: 12px; }dt { color: var(--muted); }dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }.fragments { padding-top: 16px; border-top: 1px solid var(--line); }.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }summary { cursor: pointer; color: var(--muted); font-size: 12px; }details > pre { margin-top: 10px; }.text-link { justify-self: start; font-size: 12px; }.field-help, .notice { overflow-wrap: anywhere; }@media (max-width: 550px) { dl { grid-template-columns: 1fr; gap: 5px; }dd { margin-bottom: 7px; } }
</style>
