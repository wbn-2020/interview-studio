<script setup lang="ts">
import type { AiEvaluationCitation, AiTrainingDraftInput as TrainingDraftInput } from '../../api/ai'
import { aiTrainingDraftFieldLabels } from '../../api/ai'
import { questionDimensionLabels, questionTypeLabels } from '../../api/bank'

defineProps<{ input: TrainingDraftInput }>()
const conditionLabels: Record<string, string> = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '站内辅助确认前', ASSISTED: '已确认辅助条件' }
const coverageLabels = { MENTIONED: '原文有提及', NOT_MENTIONED: '可能未提及', UNCERTAIN: '暂无法判断' }
const evidenceLabels: Record<AiEvaluationCitation['kind'], string> = {
  ANSWER_TEXT: '回答原文', ANSWER_NODE_LABEL: '答案图节点名称', ANSWER_NODE_DESCRIPTION: '答案图节点说明', ANSWER_EDGE_LABEL: '答案图连线说明',
  QUESTION_PROMPT: '固定题干', QUESTION_CONDITIONS: '题目适用条件', REFERENCE: '个人未审参考', CHECKPOINT: '个人未审检查点',
}
const questionFields = [['prompt', '固定题干'], ['conditions', '适用条件'], ['technology', '技术与版本'], ['scenario', '场景']] as const
const feedbackFields = [['observation', '观察'], ['condition', '条件'], ['basis', '依据'], ['limitation', '限制'], ['suggestion', '建议']] as const
</script>

<template>
  <section class="training-draft-input" aria-label="训练片段整理的完整固定输入">
    <header><h4>训练来源 · 第 {{ input.answerNumber }} 版提交</h4><p class="field-help">以下题面、已选片段和反馈原文都会发送。原答只记录当时的表达，AI 反馈保持未审身份，均不证明经历真实或已经掌握。</p></header>
    <dl class="source-identities">
      <div><dt>训练会话</dt><dd>{{ input.sessionId }}</dd></div><div><dt>训练题目</dt><dd>{{ input.questionId }}</dd></div><div><dt>确切提交</dt><dd>{{ input.answerId }}</dd></div>
      <div><dt>提交时条件</dt><dd>{{ conditionLabels[input.answerCondition] ?? '条件待核对' }} · {{ input.answerCondition }}</dd></div>
      <div><dt>题库来源</dt><dd>{{ input.sourceQuestionId ?? '无（训练追问）' }}</dd></div><div><dt>固定题库版本</dt><dd>{{ input.sourceVersionId ?? '无（训练追问）' }}</dd></div>
    </dl>
    <RouterLink class="text-link" :to="{ name: 'training', query: { item: input.sessionId, question: input.questionId, answer: input.answerId } }">查看这次确切提交</RouterLink>
    <article class="source-block">
      <h4>固定题面</h4><h5>题目标题</h5><pre class="exact-text">{{ input.question.title }}</pre><p class="field-help">题目类型：{{ questionTypeLabels[input.question.type] }} · {{ input.question.type }}</p>
      <div v-for="field in questionFields" :key="field[0]"><h5>{{ field[1] }}</h5><pre v-if="input.question[field[0]]" class="exact-text">{{ input.question[field[0]] }}</pre><p v-else class="field-help">该字段为空。</p></div>
    </article>
    <section class="source-block" aria-label="选中的回答片段">
      <h4>已选回答片段 · {{ input.fragments.length }} 段</h4><p class="field-help">保留原字段内的确切位置；没有展开未选择的回答正文、图坐标或外链。训练继续进行或追加新回答不会替换这些片段。</p>
      <article v-for="(fragment, index) in input.fragments" :key="`${fragment.field}/${fragment.elementId}/${fragment.start}/${index}`" class="fragment">
        <h5>{{ index + 1 }}. {{ aiTrainingDraftFieldLabels[fragment.field] }}</h5><p class="field-help">{{ fragment.elementId ? `图元素 ${fragment.elementId} · ` : '' }}原字段内 UTF-16 [{{ fragment.start }}, {{ fragment.end }})</p><pre class="exact-text">{{ fragment.quote }}</pre>
      </article>
    </section>
    <section v-if="input.feedbackTaskId" class="source-block" aria-label="选中的完整 AI 未审反馈">
      <h4>已选评价反馈 · {{ input.feedback.length }} 条</h4><p class="field-help">评价任务 {{ input.feedbackTaskId }} · {{ input.feedbackLevel === 'AI_UNREVIEWED' ? 'AI 未审' : '级别待核对' }} · {{ input.feedbackLevel }}</p>
      <RouterLink class="text-link" :to="{ name: 'ai', query: { item: input.feedbackTaskId } }">查看这份原评价报告</RouterLink>
      <h5>原报告限制 · 完整原文</h5><pre class="exact-text">{{ input.feedbackLimitations }}</pre>
      <p class="field-help">每条反馈自带的原引用也会外发，其中可能含个人参考或检查点摘句。这里完整列出这些文字；未展开个人参考全文。用户对反馈的决定与原因不在本次清单中。</p>
      <article v-for="item in input.feedback" :key="item.id" class="feedback">
        <h4>{{ item.id }} · {{ questionDimensionLabels[item.dimension] }} · {{ coverageLabels[item.coverage] }}</h4><p class="field-help">原维度 {{ item.dimension }} · 原覆盖状态 {{ item.coverage }}</p>
        <div v-for="field in feedbackFields" :key="field[0]"><h5>{{ field[1] }}</h5><pre v-if="item[field[0]]" class="exact-text">{{ item[field[0]] }}</pre><p v-else class="field-help">该字段为空。</p></div>
        <h5>该反馈所带的全部原引文</h5><p v-if="!item.citations.length" class="field-help">这条反馈没有原引文。</p>
        <figure v-for="(citation, index) in item.citations" :key="index"><blockquote>{{ citation.quote }}</blockquote><figcaption>{{ evidenceLabels[citation.kind] }} · {{ citation.kind }}{{ citation.elementId ? ` · 元素 ${citation.elementId}` : '' }} · 原字段内 UTF-16 [{{ citation.start }}, {{ citation.end }})</figcaption></figure>
      </article>
    </section>
    <p v-else class="field-help">本次未选评价反馈，不发送评价任务、报告限制或反馈正文。</p>
    <details><summary>核对完整固定输入</summary><pre class="exact-text">{{ JSON.stringify(input, null, 2) }}</pre></details>
  </section>
</template>

<style scoped>
.training-draft-input { display: grid; gap: 15px; min-width: 0; } header, .source-block, .fragment, .feedback { display: grid; gap: 10px; min-width: 0; }
h4 { margin: 0; font-size: 14px; overflow-wrap: anywhere; } h5 { margin: 7px 0 0; color: var(--muted); font-size: 12px; }
.source-block, .feedback { padding-top: 17px; border-top: 1px solid var(--line); }
.source-identities { display: grid; gap: 9px; margin: 0; font-size: 12px; } .source-identities > div { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 10px; } dt { color: var(--muted); } dd { margin: 0; overflow-wrap: anywhere; }
.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }
figure { display: grid; gap: 8px; margin: 0; padding-top: 12px; border-top: 1px solid var(--line); } blockquote { margin: 0; font-size: 13px; white-space: pre-wrap; overflow-wrap: anywhere; } figcaption, summary { font-size: 12px; color: var(--muted); overflow-wrap: anywhere; } summary { cursor: pointer; } details > .exact-text { margin-top: 10px; }
.text-link { justify-self: start; font-size: 12px; } .field-help { overflow-wrap: anywhere; }
@media (max-width: 550px) { .source-identities > div { grid-template-columns: 1fr; gap: 3px; } }
</style>
