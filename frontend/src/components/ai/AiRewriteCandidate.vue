<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { NButton } from 'naive-ui'
import type { AiEvaluationCitation, AiTask } from '../../api/ai'
import { aiRewriteEvidenceText, aiRewriteTaskMatches } from '../../api/aiRewrite'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MarkdownPreview from '../MarkdownPreview.vue'

const props = defineProps<{ task: AiTask; disabled?: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.challenge
  && !access.restricted && !store.changes.get('training-access-command')?.locked)
const valid = computed(() => aiRewriteTaskMatches(props.task))
const input = computed(() => valid.value ? props.task.manifest.evaluation : null)
const candidate = computed(() => valid.value ? props.task.rewriteCandidate : null)
const selected = ref<{ change: number; citation: number }>()
const mark = ref<HTMLElement>()
const answerConditions = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
const names: Record<AiEvaluationCitation['kind'], string> = {
  ANSWER_TEXT: '回答原文', ANSWER_NODE_LABEL: '原答图节点名称', ANSWER_NODE_DESCRIPTION: '原答图节点说明',
  ANSWER_EDGE_LABEL: '原答图连线说明', QUESTION_PROMPT: '固定题干', QUESTION_CONDITIONS: '题目条件',
  REFERENCE: '个人未审参考', CHECKPOINT: '个人未审检查点',
}
const located = computed(() => {
  const citation = selected.value && candidate.value?.changes[selected.value.change]?.citations[selected.value.citation]
  const text = citation && input.value ? aiRewriteEvidenceText(input.value, citation) : undefined
  return citation && typeof text === 'string' ? {
    citation, before: text.slice(0, citation.start), after: text.slice(citation.end),
  } : undefined
})
watch([() => props.task.task.id, () => store.sessionEpoch, readable], () => { selected.value = undefined }, { flush: 'sync' })
async function locate(change: number, citation: number) {
  if (!readable.value || !valid.value) return
  selected.value = { change, citation }
  await nextTick()
  if (readable.value) { mark.value?.focus(); mark.value?.scrollIntoView({ block: 'nearest' }) }
}
</script>

<template>
  <section class="rewrite-candidate" aria-label="原答、个人参考与 AI 改写对照">
    <p v-if="!readable" class="notice" role="status">资料访问条件尚未恢复，改写参考暂不可读。</p>
    <p v-else-if="!valid" class="notice error" role="alert">改写任务、确切提交或候选引用不一致，请重新读取任务。</p>
    <template v-else-if="input && candidate">
      <header><h3>改写参考 · AI 未审</h3><p class="field-help">{{ candidate.validationNote }}</p><p class="field-help">第 {{ input.answerNumber }} 版提交 · {{ answerConditions[input.answerCondition] }}。三栏分别保留当次原文、固定个人参考与模型候选。</p></header>
      <p class="preserve">{{ candidate.summary }}</p>
      <div class="rewrite-columns">
        <section aria-label="已提交原答" class="rewrite-column"><h4>已提交原答</h4><p v-if="!input.answer.text" class="field-help">本次提交没有文字正文，请查看下方原答图。</p><MarkdownPreview v-else :value="input.answer.text" /><details v-if="input.answer.nodes.length || input.answer.edges.length"><summary>原答图的节点与连接</summary><ol><li v-for="node in input.answer.nodes" :key="node.id"><strong>{{ node.label || '未命名节点' }}</strong><p class="preserve">{{ node.description }}</p></li></ol><ul><li v-for="edge in input.answer.edges" :key="edge.id"><span>{{ input.answer.nodes.find(node => node.id === edge.source)?.label || '未命名节点' }} → {{ input.answer.nodes.find(node => node.id === edge.target)?.label || '未命名节点' }}</span><p class="preserve">{{ edge.label }}</p></li></ul></details></section>
        <section aria-label="固定个人未审参考" class="rewrite-column"><h4>个人参考 · 未审</h4><template v-if="input.standard"><MarkdownPreview v-if="input.standard.referenceAnswer" :value="input.standard.referenceAnswer" /><p v-else class="field-help">当时参考正文为空。</p><ul><li v-for="point in input.standard.checkpoints" :key="point.id">{{ point.text }}</li></ul><p class="field-help">题目版本 {{ input.standard.versionId }}。引用或改写不会改变参考的审核身份。</p></template><p v-else class="field-help">这次题实例没有个人参考或检查点。</p></section>
        <section aria-label="AI 只读改写参考" class="rewrite-column"><h4>AI 改写候选</h4><MarkdownPreview :value="candidate.answer" /><p class="field-help">候选供阅读核对，保留于本任务。下一次作答仍从空白回答开始。</p></section>
      </div>
      <section class="rewrite-changes"><h4>改动说明与原文依据</h4><p class="field-help">引文只证明文字定位相符，不能证明改写保留全部原意、技术正确或经历真实。</p><article v-for="(change, changeIndex) in candidate.changes" :key="changeIndex"><p class="preserve">{{ change.explanation }}</p><figure v-for="(citation, citationIndex) in change.citations" :key="citationIndex"><blockquote>{{ citation.quote }}</blockquote><figcaption><NButton text size="small" :disabled="!readable" @click="locate(changeIndex, citationIndex)">定位{{ names[citation.kind] }}</NButton><span v-if="citation.elementId"> · 元素 {{ citation.elementId }}</span><span> · UTF-16 [{{ citation.start }}, {{ citation.end }})</span></figcaption></figure></article></section>
      <section v-if="located" class="rewrite-location" aria-label="确切原文定位"><h4>{{ names[located.citation.kind] }}</h4><pre><span>{{ located.before }}</span><mark ref="mark" tabindex="-1">{{ located.citation.quote }}</mark><span>{{ located.after }}</span></pre></section>
      <section v-if="candidate.questions.length"><h4>需补充或核实的问题</h4><ul><li v-for="(question, index) in candidate.questions" :key="index">{{ question }}</li></ul><p class="field-help">这些内容没有被当作你已说出的事实，核实后再通过独立作答表达。</p></section>
      <section><h4>本次改写的限制</h4><p class="preserve">{{ candidate.limitations }}</p></section>
    </template>
  </section>
</template>

<style scoped>
.rewrite-candidate, header, .rewrite-column, .rewrite-changes, .rewrite-changes article { display: grid; align-content: start; gap: 13px; min-width: 0; }
h3, h4, p { margin: 0; } h3 { font-size: 17px; } h4 { font-size: 14px; } .rewrite-columns { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.rewrite-column { border-top: 1px solid var(--line); padding-top: 15px; } .preserve, li, blockquote, figcaption { white-space: pre-wrap; overflow-wrap: anywhere; } .preserve, li { font-size: 13px; line-height: 1.8; }
ul, ol { padding-left: 20px; margin: 0; } li { margin: 8px 0; } details { min-width: 0; } summary { cursor: pointer; color: var(--brand-strong); font-size: 13px; }
.rewrite-changes { border-top: 1px solid var(--line); padding-top: 20px; } .rewrite-changes article + article { border-top: 1px solid var(--line); padding-top: 15px; } figure { margin: 0; padding: 12px; background: var(--surface-soft); border-radius: 5px; } blockquote { margin: 0 0 7px; font-size: 13px; } figcaption { font-size: 12px; color: var(--muted); }
.rewrite-location { min-width: 0; border: 1px solid var(--line); border-radius: 6px; padding: 15px; } pre { margin: 12px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 350px; overflow-y: auto; font: inherit; font-size: 13px; line-height: 1.8; } mark { background: #f5e6a9; color: var(--ink); } mark:focus { outline: 2px solid var(--brand); outline-offset: 2px; }
@media (max-width: 1280px) { .rewrite-columns { grid-template-columns: 1fr; } }
</style>
