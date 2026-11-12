<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { NButton, NCheckbox } from 'naive-ui'
import type { AiTask } from '../../api/ai'
import type { AiComparisonCitation, AiComparisonDraft, AiComparisonSuggestion } from '../../api/aiComparison'
import { aiComparisonEvidenceLabels, aiComparisonSideLabels, aiComparisonTaskValid } from '../../api/aiComparison'
import { repracticeOutcomeLabels } from '../../api/repractice'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ task: AiTask; allowFill?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ fill: [draft: AiComparisonDraft] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const selections = reactive(new Map<string, string[]>())
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted && !store.changes.get('training-access-command')?.locked)
const valid = computed(() => aiComparisonTaskValid(props.task))
const candidate = computed(() => valid.value ? props.task.comparisonCandidate : null)
const input = computed(() => valid.value ? props.task.manifest.comparisonInput : null)
const canFill = computed(() => readable.value && props.allowFill && valid.value && props.task.task.status === 'SUCCEEDED' && props.task.sourcesCurrent)
const sideOrder = ['BASELINE', 'CURRENT'] as const

watch([() => JSON.stringify([props.task.task.id, props.task.task.revision, props.task.manifest.comparisonInput,
  props.task.manifest.comparisonChecks, props.task.comparisonCandidate, props.task.sourcesCurrent]), () => store.sessionEpoch, readable], () => selections.clear(), { flush: 'sync' })
const citationKey = (citation: AiComparisonCitation) => JSON.stringify([citation.side, citation.kind, citation.elementId, citation.start, citation.end])
const selected = (sourceId: string, citation: AiComparisonCitation) => Boolean(selections.get(sourceId)?.includes(citationKey(citation)))
function toggle(sourceId: string, citation: AiComparisonCitation, checked: boolean) {
  if (!canFill.value || citation.side !== 'CURRENT') return
  const chosen = new Set(selections.get(sourceId) ?? []), key = citationKey(citation)
  if (checked) chosen.add(key); else chosen.delete(key)
  selections.set(sourceId, [...chosen])
}
function selectedCitations(suggestion: AiComparisonSuggestion) {
  return suggestion.citations.filter(citation => citation.side === 'CURRENT' && selected(suggestion.sourceId, citation))
}
function needsEvidence(suggestion: AiComparisonSuggestion) { return ['HANDLED', 'PARTIAL'].includes(suggestion.suggestedOutcome) && !selectedCitations(suggestion).length }
function fill(suggestion: AiComparisonSuggestion) {
  if (!canFill.value || !aiComparisonTaskValid(props.task) || !input.value || !candidate.value || needsEvidence(suggestion)
    || !candidate.value.suggestions.includes(suggestion)) return
  const reason = `${suggestion.reason}\n\n限制：${suggestion.limitations}`
  if (reason.length > 2000) return
  // 旧答引文只保留在候选展示；人工判断必须引用确切新答，并由原表单另行确认保存。
  const citations = selectedCitations(suggestion).map(citation => ({ kind: citation.kind, elementId: citation.elementId,
    start: citation.start, end: citation.end, quote: citation.quote }))
  emit('fill', { taskId: props.task.task.id, comparisonId: input.value.comparisonId, answerId: input.value.current.identity.answerId,
    sourceId: suggestion.sourceId, outcome: suggestion.suggestedOutcome, reason, citations })
}
</script>

<template>
  <section class="comparison-candidate" aria-label="AI 未审的逐来源比较候选">
    <p v-if="!readable" class="notice" role="status">当前资料访问条件尚未恢复，比较候选暂不可读。</p>
    <p v-else-if="!valid" class="notice error" role="alert">比较任务、确切输入或候选引用不一致。请重新读取任务后再核对。</p>
    <template v-else-if="candidate && input">
      <header><h3>逐来源比较建议 · AI 未审</h3><p class="field-help">{{ candidate.validationNote }}</p><p class="field-help">原文定位相符只说明引用有效，比较是否公平、判断是否成立仍需你核对。</p></header>
      <section class="candidate-summary"><h4>总体观察</h4><p class="preserve">{{ candidate.summary }}</p><h4>整体限制</h4><p class="preserve">{{ candidate.limitations }}</p></section>
      <p v-if="!task.sourcesCurrent" class="notice" role="status">来源归属、验证条件或训练授权已变化。原候选保留供阅读，当前不能填入判断草稿；请回到对照核对来源。</p>
      <p v-if="allowFill" class="field-help">每次只填入一条来源。选中的新答引文、建议判断、原因和本条限制会进入可编辑草稿，随后仍需人工确认保存。</p>
      <article v-for="suggestion in candidate.suggestions" :key="suggestion.sourceId" class="suggestion">
        <header><h4>建议：{{ repracticeOutcomeLabels[suggestion.suggestedOutcome] }} · AI 未审</h4><p class="field-help">来源 {{ suggestion.sourceId }}</p></header>
        <div><h5>固定验证条件</h5><p class="preserve">{{ input.sources.find(source => source.sourceId === suggestion.sourceId)?.verification }}</p></div>
        <div><h5>建议依据</h5><p class="preserve">{{ suggestion.reason }}</p></div>
        <div><h5>本条限制</h5><p class="preserve">{{ suggestion.limitations }}</p></div>
        <section v-for="side in sideOrder" :key="side" class="citation-side" :aria-label="`${aiComparisonSideLabels[side]}引文`">
          <h5>{{ aiComparisonSideLabels[side] }}引文</h5>
          <p v-if="!suggestion.citations.some(citation => citation.side === side)" class="field-help">本条建议没有{{ aiComparisonSideLabels[side] }}引文。</p>
          <figure v-for="citation in suggestion.citations.filter(item => item.side === side)" :key="citationKey(citation)">
            <blockquote>{{ citation.quote }}</blockquote>
            <figcaption>{{ aiComparisonSideLabels[citation.side] }} · {{ aiComparisonEvidenceLabels[citation.kind] }}{{ citation.elementId ? ` · 元素 ${citation.elementId}` : '' }} · UTF-16 [{{ citation.start }}, {{ citation.end }})</figcaption>
            <NCheckbox v-if="allowFill && side === 'CURRENT'" :checked="selected(suggestion.sourceId, citation)" :disabled="!canFill" @update:checked="toggle(suggestion.sourceId, citation, $event)">将这处新答引文填入判断草稿</NCheckbox>
          </figure>
        </section>
        <div v-if="allowFill" class="fill-actions"><p v-if="needsEvidence(suggestion)" class="field-help">“已处理”或“部分处理”需至少选择一处新答引文。</p><NButton :disabled="!canFill || needsEvidence(suggestion)" @click="fill(suggestion)">填入本条判断草稿</NButton></div>
      </article>
    </template>
    <p v-else class="field-help">本任务尚无可读取的比较候选。现有人工对照和判断仍可继续。</p>
  </section>
</template>

<style scoped>
.comparison-candidate, .candidate-summary, .suggestion, .citation-side, header { display: grid; gap: 13px; min-width: 0; }
h3, h4, h5, p { margin: 0; } h3 { font-size: 17px; } h4 { font-size: 14px; } h5 { color: var(--muted); font-size: 12px; margin-bottom: 6px; }
.suggestion { border-top: 1px solid var(--line); padding-top: 22px; } .candidate-summary { padding: 15px; border-radius: 6px; background: var(--surface-soft); }
.preserve, blockquote { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.8; } figure { display: grid; gap: 9px; margin: 0; padding: 13px; border: 1px solid var(--line); border-radius: 6px; } blockquote { margin: 0; } figcaption { color: var(--muted); font-size: 11px; overflow-wrap: anywhere; }
.fill-actions { display: grid; justify-items: start; gap: 9px; } .field-help { overflow-wrap: anywhere; } .citation-side { gap: 10px; }
</style>
