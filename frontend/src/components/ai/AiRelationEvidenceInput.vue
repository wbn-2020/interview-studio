<script setup lang="ts">
import type { AiFactDraftInput } from '../../api/aiFactDraft'
import { aiTrainingDraftFieldLabels } from '../../api/ai'

defineProps<{ input: AiFactDraftInput }>()
const answerLabels = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
</script>

<template>
  <section class="relation-evidence-input" aria-label="关系候选的完整固定原文依据">
    <header><h4>{{ input.kind === 'MATERIAL' ? '资料摘录' : '训练回答片段' }} · 完整固定依据</h4><p class="field-help">下面保留当时选中的完整原文及元数据，用于核对关系候选的出处。原文、自述或训练假设不证明技术正确性或真实经历。</p></header>
    <template v-if="input.kind === 'MATERIAL'">
      <article v-for="(material, index) in input.materials" :key="material.excerptId" class="source-block"><h4>{{ index + 1 }}. {{ material.fileName }}</h4><p class="field-help">{{ material.origin === 'MANUAL' ? '用户手工摘录' : '解析摘录' }} · {{ material.locator }}</p><dl><dt>确切摘录</dt><dd>{{ material.excerptId }}</dd><dt>来源附件</dt><dd>{{ material.attachmentId }}</dd><dt>原件 SHA-256</dt><dd>{{ material.sha256 }}</dd><dt>解析执行</dt><dd>{{ material.runId ?? '无（手工摘录）' }}</dd><dt>解析片段</dt><dd>{{ material.segmentId ?? '无（手工摘录）' }}</dd><dt>原片段范围</dt><dd>{{ material.startOffset === null ? '无（手工摘录）' : `UTF-16 [${material.startOffset}, ${material.endOffset})` }}</dd></dl><pre class="exact-text">{{ material.text }}</pre><RouterLink class="text-link" :to="{ name: 'materials', query: { attachment: material.attachmentId, run: material.runId ?? undefined, excerpt: material.excerptId } }">查看这个确切摘录</RouterLink></article>
    </template>
    <template v-else-if="input.training">
      <dl><dt>训练会话</dt><dd>{{ input.training.sessionId }}</dd><dt>训练题目身份</dt><dd>{{ input.training.questionId }}</dd><dt>确切提交</dt><dd>{{ input.training.answerId }} · 第 {{ input.training.answerNumber }} 次提交</dd><dt>作答条件</dt><dd>{{ answerLabels[input.training.answerCondition] }} · {{ input.training.answerCondition }}</dd></dl><RouterLink class="text-link" :to="{ name: 'training', query: { item: input.training.sessionId, question: input.training.questionId, answer: input.training.answerId } }">查看这次确切提交</RouterLink>
      <article v-for="(fragment, index) in input.training.fragments" :key="`${fragment.field}/${fragment.elementId}/${fragment.start}/${index}`" class="source-block"><h4>{{ index + 1 }}. {{ aiTrainingDraftFieldLabels[fragment.field] }}</h4><p class="field-help">{{ fragment.elementId ? `图元素 ${fragment.elementId} · ` : '' }}原字段 UTF-16 [{{ fragment.start }}, {{ fragment.end }})</p><pre class="exact-text">{{ fragment.quote }}</pre></article>
      <p class="field-help">训练来源仅包含这次提交的身份、作答条件与手选片段。</p>
    </template>
    <p class="field-help">这些出处不证明关系语义正确；候选与后续人工决定需要分别核对。</p>
    <details><summary>核对完整固定依据数据</summary><pre class="exact-text">{{ JSON.stringify(input, null, 2) }}</pre></details>
  </section>
</template>

<style scoped>
.relation-evidence-input, header, .source-block { display: grid; gap: 12px; min-width: 0; }.relation-evidence-input { gap: 17px; }.source-block { padding-top: 16px; border-top: 1px solid var(--line); }h4 { margin: 0; font-size: 14px; overflow-wrap: anywhere; }dl { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 9px 12px; margin: 0; font-size: 12px; }dt { color: var(--muted); }dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }summary { cursor: pointer; color: var(--muted); font-size: 12px; }details > pre { margin-top: 10px; }.text-link { justify-self: start; font-size: 12px; }.field-help { overflow-wrap: anywhere; }@media (max-width: 550px) { dl { grid-template-columns: 1fr; gap: 5px; }dd { margin-bottom: 7px; } }
</style>
