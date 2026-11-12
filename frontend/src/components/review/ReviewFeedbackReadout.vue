<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { NButton } from 'naive-ui'
import type { AiEvaluationInput } from '../../api/ai'
import type { ReviewFeedbackContent } from '../../api/review'
import { reviewCitationValid, reviewCoverageLabels, reviewEvidenceLabels, reviewEvidenceText, reviewKindLabels } from '../../api/review'
import { questionDimensionLabels } from '../../api/bank'

const props = defineProps<{ content: ReviewFeedbackContent; input?: AiEvaluationInput }>()
const selected = ref<number>()
const mark = ref<HTMLElement>()
const located = computed(() => {
  const citation = selected.value !== undefined ? props.content.citations[selected.value] : undefined
  if (!citation || !props.input) return undefined
  const text = reviewEvidenceText(props.input, citation)
  if (text === undefined || !reviewCitationValid(props.input, citation)) return { valid: false, citation, before: '', quote: '', after: '' }
  return { valid: true, citation, before: text.slice(0, citation.start), quote: citation.quote, after: text.slice(citation.end) }
})
async function locate(index: number) { selected.value = index; await nextTick(); mark.value?.focus({ preventScroll: true }); mark.value?.scrollIntoView({ block: 'center' }) }
</script>

<template>
  <article class="feedback-readout">
    <p class="field-help">{{ reviewKindLabels[content.kind] }}{{ content.kind === 'EXTERNAL_FEEDBACK' ? ` · ${content.authorLabel}（用户录入称呼）` : '' }} · {{ questionDimensionLabels[content.dimension] }} · {{ reviewCoverageLabels[content.coverage] }}</p>
    <p class="observation">{{ content.observation }}</p>
    <p v-if="content.coverage === 'NOT_MENTIONED'" class="field-help">本项描述这次回答可能未覆盖的内容，不代表技术错误或能力缺陷。</p>
    <dl><template v-if="content.condition"><dt>适用条件</dt><dd>{{ content.condition }}</dd></template><dt>观察依据</dt><dd>{{ content.basis }}</dd><dt>判断限制</dt><dd>{{ content.limitation }}</dd><dt>建议行动</dt><dd>{{ content.suggestion }}</dd></dl>
    <p v-if="!content.citations.length" class="field-help">没有可定位引文，请结合判断限制阅读。</p>
    <figure v-for="(citation, index) in content.citations" :key="index"><blockquote>{{ citation.quote }}</blockquote><figcaption><NButton v-if="input" text size="small" @click="locate(index)">定位{{ reviewEvidenceLabels[citation.kind] }}</NButton><span v-else>{{ reviewEvidenceLabels[citation.kind] }}</span></figcaption></figure>
    <section v-if="located" class="evidence-location" aria-label="原文引用定位"><h4>{{ reviewEvidenceLabels[located.citation.kind] }}</h4><p v-if="!located.valid" class="notice error" role="alert">引文与原文不一致，请重新读取当前记录。</p><pre v-else><span>{{ located.before }}</span><mark ref="mark" tabindex="-1">{{ located.quote }}</mark><span>{{ located.after }}</span></pre></section>
  </article>
</template>

<style scoped>
.feedback-readout { display: grid; gap: 13px; min-width: 0; } .observation, dd, blockquote { white-space: pre-wrap; overflow-wrap: anywhere; }
dl { display: grid; grid-template-columns: 78px minmax(0, 1fr); gap: 10px 14px; margin: 0; font-size: 13px; } dt { color: var(--muted); } dd { margin: 0; }
figure { margin: 0; padding: 10px 12px; background: var(--surface-soft); border-radius: 5px; } blockquote { margin: 0 0 6px; font-size: 13px; } figcaption { font-size: 12px; color: var(--muted); }
.evidence-location { min-width: 0; border: 1px solid var(--line); border-radius: 6px; padding: 14px; } h4 { margin: 0 0 9px; font-size: 13px; } pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 350px; overflow-y: auto; font: inherit; font-size: 13px; line-height: 1.8; } mark { background: #f5e6a9; color: var(--ink); } mark:focus { outline: 2px solid var(--brand); outline-offset: 2px; }
@media (max-width: 560px) { dl { grid-template-columns: 1fr; gap: 5px; } dd { margin-bottom: 8px; } }
</style>
