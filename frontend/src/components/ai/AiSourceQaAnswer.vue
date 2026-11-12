<script setup lang="ts">
import { computed } from 'vue'
import type { AiSourceQaAnswer, AiSourceQaInput } from '../../api/aiSourceQa'
import { aiSourceQaAnswerValid, aiSourceQaCitationFragment, aiSourceQaInputValid, aiSourceQaSourceRoute } from '../../api/aiSourceQa'
const props = defineProps<{ answer: AiSourceQaAnswer; input: AiSourceQaInput }>()
const valid = computed(() => aiSourceQaInputValid(props.input) && aiSourceQaAnswerValid(props.answer, props.input))
const citations = computed(() => valid.value ? props.answer.citations.map(citation => ({ citation, ...aiSourceQaCitationFragment(citation, props.input)! })) : [])
</script>

<template>
  <section class="source-qa-answer" aria-label="AI 未审来源回答">
    <p v-if="!valid" class="notice error" role="alert">回答状态或引用没有通过固定片段核对，请重新读取任务记录。</p>
    <template v-else>
      <header><p class="section-label">AI 未审</p><h3>{{ answer.state === 'SUPPORTED' ? '基于所选片段的回答' : '所选片段不足以支持回答' }}</h3></header>
      <p class="notice">{{ answer.validationNote }}</p>
      <pre class="answer-text">{{ answer.answer }}</pre>
      <section><h4>回答限制</h4><pre>{{ answer.limitations }}</pre></section>
      <p v-if="answer.state === 'INSUFFICIENT'" class="field-help">这是本次已完成的回答。需要更多依据时，由你重新选择来源并预览新的问题。</p>
      <ol v-if="citations.length" class="citations"><li v-for="({ citation, source, fragment }, index) in citations" :key="index">
        <header><strong>{{ source.sourceKey }} · {{ source.title }}</strong><RouterLink v-if="aiSourceQaSourceRoute(source)" :to="aiSourceQaSourceRoute(source)!" class="text-link">查看确切来源</RouterLink></header>
        <p class="field-help">{{ fragment.label }} · 原字段 [{{ citation.start }}, {{ citation.end }})</p>
        <blockquote>{{ citation.quote }}</blockquote>
        <details><summary>在本次已选原文中核对</summary><pre>{{ fragment.text.slice(0, citation.start - fragment.start) }}<mark>{{ citation.quote }}</mark>{{ fragment.text.slice(citation.end - fragment.start) }}</pre></details>
      </li></ol>
      <p class="field-help">回答仅供理解所选材料；不会自动发布知识、采用反馈或记录训练／复练完成。</p>
    </template>
  </section>
</template>

<style scoped>
.source-qa-answer { display: grid; gap: 20px; min-width: 0; } h3, h4 { margin: 0; } h4 { margin-bottom: 8px; font-size: 13px; }
pre, blockquote { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.9; }
.answer-text { font-size: 14px; } .citations { display: grid; gap: 18px; padding-left: 22px; } .citations li { min-width: 0; }
.citations header { display: flex; justify-content: space-between; align-items: start; gap: 12px; flex-wrap: wrap; }
blockquote { margin: 9px 0; padding: 8px 14px; border-left: 3px solid var(--brand); background: var(--surface-soft); font-size: 13px; }
summary { cursor: pointer; color: var(--muted); font-size: 12px; } details pre { padding: 12px; margin-top: 8px; border: 1px solid var(--line); border-radius: 6px; font-size: 12px; }
mark { background: var(--brand-soft); color: var(--brand-strong); }
</style>
