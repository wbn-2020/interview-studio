<script setup lang="ts">
import { computed } from 'vue'
import { NButton } from 'naive-ui'
import type { AiTrainingDraftCitation, AiTrainingDraftInput } from '../../api/ai'
import { aiTrainingDraftFieldLabels, aiTrainingDraftRangeMatches } from '../../api/ai'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ citations?: AiTrainingDraftCitation[] | null; input?: AiTrainingDraftInput | null; disabled?: boolean }>()
const emit = defineEmits<{ refresh: [] }>()
const store = useWorkspaceStore()
const readable = computed(() => store.sessionActive && !props.disabled)

function sourceFor(citation: AiTrainingDraftCitation): { text: string; start: number; feedback: boolean } | undefined {
  const input = props.input
  if (!input || !citation.quote.trim() || citation.quote.length > 1000) return undefined
  if (citation.field.startsWith('ANSWER_')) {
    const matches = input.fragments.filter(fragment => fragment.field === citation.field && fragment.elementId === citation.elementId
      && citation.start >= fragment.start && citation.end <= fragment.end)
    // 回答引用沿用原字段的绝对位置，只能在已选片段内定位，不能把反馈引文当作额外答案授权。
    const fragment = matches.length === 1 ? matches[0] : undefined
    if (!fragment || !aiTrainingDraftRangeMatches(fragment.quote, citation.start - fragment.start, citation.end - fragment.start, citation.quote)) return undefined
    return { text: fragment.quote, start: fragment.start, feedback: false }
  }
  let text: string | undefined
  let feedback = false
  if (citation.field === 'QUESTION_PROMPT' || citation.field === 'QUESTION_CONDITIONS') {
    if (citation.elementId !== null) return undefined
    text = citation.field === 'QUESTION_PROMPT' ? input.question.prompt : input.question.conditions
  } else {
    const matches = input.feedback.filter(item => item.id === citation.elementId)
    const item = matches.length === 1 ? matches[0] : undefined
    if (!item || !input.feedbackTaskId) return undefined
    feedback = true
    switch (citation.field) {
      case 'FEEDBACK_OBSERVATION': text = item.observation; break
      case 'FEEDBACK_CONDITION': text = item.condition; break
      case 'FEEDBACK_BASIS': text = item.basis; break
      case 'FEEDBACK_LIMITATION': text = item.limitation; break
      case 'FEEDBACK_SUGGESTION': text = item.suggestion; break
      default: return undefined
    }
  }
  return text !== undefined && aiTrainingDraftRangeMatches(text, citation.start, citation.end, citation.quote)
    ? { text, start: 0, feedback } : undefined
}
const rows = computed(() => (props.citations ?? []).map(citation => {
  const source = sourceFor(citation)
  return { citation, source, before: source?.text.slice(0, citation.start - source.start), after: source?.text.slice(citation.end - source.start) }
}))
</script>

<template>
  <section v-if="rows.length" class="training-citations" aria-label="候选的训练原文依据">
    <h5>训练原文引用</h5><p class="field-help">引用定位到当次外发字段。原答与 AI 未审反馈只说明整理出处，不证明技术结论、经历真实性或当前掌握程度。</p>
    <figure v-for="({ citation, source, before, after }, index) in rows" :key="`${citation.field}/${citation.elementId}/${citation.start}/${index}`">
      <blockquote>{{ citation.quote }}</blockquote>
      <figcaption><strong>{{ aiTrainingDraftFieldLabels[citation.field] ?? '字段待核对' }}</strong><span v-if="input">第 {{ input.answerNumber }} 版提交 · {{ input.answerId }}</span><span v-if="citation.elementId">{{ citation.field.startsWith('FEEDBACK_') ? '选定反馈' : '图元素' }} {{ citation.elementId }}</span><span>原字段内 UTF-16 [{{ citation.start }}, {{ citation.end }})</span></figcaption>
      <p v-if="!source" class="notice" role="status">本条引用无法逐字匹配唯一的已选片段或外发字段。<NButton text :disabled="!readable" @click="emit('refresh')">重新读取任务</NButton></p>
      <details v-else><summary>{{ citation.field.startsWith('ANSWER_') ? '在本次已选完整片段中定位' : '在本次外发完整字段中定位' }}</summary><pre class="exact-text"><span>{{ before }}</span><mark>{{ citation.quote }}</mark><span>{{ after }}</span></pre><p v-if="citation.field.startsWith('ANSWER_')" class="field-help">片段从原字段 UTF-16 位置 {{ source.start }} 开始；这里只展示本次明确选择的内容。</p><p v-if="source.feedback" class="field-help">这是原评价中的 AI 未审文字，人工接受或争议不会改变其原文。</p></details>
      <div v-if="readable && source && input" class="source-links"><RouterLink class="text-link" :to="{ name: 'training', query: { item: input.sessionId, question: input.questionId, answer: input.answerId } }">查看确切提交</RouterLink><RouterLink v-if="source.feedback && input.feedbackTaskId" class="text-link" :to="{ name: 'ai', query: { item: input.feedbackTaskId } }">查看原评价报告</RouterLink></div>
    </figure>
  </section>
</template>

<style scoped>
.training-citations { display: grid; gap: 12px; min-width: 0; } h5 { margin: 0; color: var(--muted); font-size: 12px; }
figure { display: grid; gap: 10px; min-width: 0; margin: 0; padding-top: 12px; border-top: 1px solid var(--line); } blockquote { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
figcaption { display: grid; gap: 5px; color: var(--muted); font-size: 11px; overflow-wrap: anywhere; } figcaption strong { color: var(--ink); font-size: 12px; font-weight: 550; }
summary { cursor: pointer; color: var(--muted); font-size: 12px; } .exact-text { margin: 10px 0; padding: 13px; border-radius: 5px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); } mark { color: inherit; background: var(--highlight, #fff1b8); }
.source-links { display: flex; flex-wrap: wrap; gap: 10px 17px; font-size: 12px; }
</style>
