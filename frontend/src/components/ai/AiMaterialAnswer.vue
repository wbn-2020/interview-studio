<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton } from 'naive-ui'
import type { AiMaterialCitation, AiTask } from '../../api/ai'
import { aiMaterialAnswerLabels, aiMaterialModeLabels } from '../../api/ai'
import { useWorkspaceStore } from '../../state/workspace'
import MarkdownPreview from '../MarkdownPreview.vue'
import MaterialExcerptReadout from '../material/MaterialExcerptReadout.vue'

const props = defineProps<{ task: AiTask; disabled?: boolean }>()
const store = useWorkspaceStore()
const answer = computed(() => props.task.materialAnswer)
const input = computed(() => props.task.manifest.materialQa)
const readoutId = ref('')
const readable = computed(() => store.sessionActive && !props.disabled)
watch([() => store.sessionEpoch, () => props.disabled, () => props.task.task.id], () => { readoutId.value = '' })
function sourceFor(citation: AiMaterialCitation) { return input.value?.excerpts.find(excerpt => excerpt.excerptId === citation.excerptId) }
</script>

<template>
  <section v-if="answer" class="material-answer" aria-label="资料问答结果">
    <header><h3>{{ aiMaterialAnswerLabels[answer.state] }}</h3><p v-if="input" class="field-help">回答依据：{{ aiMaterialModeLabels[input.mode] }} · {{ input.excerpts.length }} 条确切摘录。</p></header>
    <p v-if="answer.state === 'SUPPORTED'" class="field-help">引用区间已核对，只说明引用逐字对应所选摘录。回答是否得到资料支持、技术是否正确、项目事实是否真实，仍需你审阅。</p>
    <p v-else-if="answer.state === 'INSUFFICIENT'" class="notice" role="status">本次回答已完成，模型认为所选资料不足。调用费用仍按尝试记录保留，不会自动重试或切换为通用解释。</p>
    <p v-else class="notice" role="status">这是通用解释，没有资料引用，也不代表查询过你的私人材料。问题里手工提供的内容以本次外发清单为准。</p>
    <p v-if="answer.validationNote" class="field-help">{{ answer.validationNote }}</p>
    <section v-if="input" class="answer-section"><h4>本次问题</h4><p class="exact-question">{{ input.question }}</p></section>
    <section class="answer-section"><h4>AI 回答 · 未审</h4><MarkdownPreview :value="answer.answer" /></section>
    <section class="answer-section"><h4>资料范围与局限</h4><MarkdownPreview :value="answer.limitations" /></section>
    <section v-if="answer.citations.length" class="answer-section citations">
      <h4>引用原文</h4>
      <figure v-for="citation in answer.citations" :key="`${citation.excerptId}/${citation.start}/${citation.end}`">
        <blockquote>{{ citation.quote }}</blockquote>
        <figcaption>
          <template v-if="sourceFor(citation)">
            <strong>{{ sourceFor(citation)!.fileName }} · {{ sourceFor(citation)!.origin === 'MANUAL' ? '用户手工摘录' : '原解析摘录' }}</strong>
            <span>定位：{{ sourceFor(citation)!.locator }}</span>
            <span v-if="sourceFor(citation)!.origin === 'MANUAL'">定位由用户手工提供，不代表自动解析或事实已核实。</span>
            <span v-else>原解析执行 {{ sourceFor(citation)!.runId }} · 片段 {{ sourceFor(citation)!.segmentId }}</span>
          </template>
          <span v-else>当前回执未提供此摘录的完整来源，请重新读取任务核对。</span>
          <span>确切摘录 {{ citation.excerptId }} · 摘录内 UTF-16 [{{ citation.start }}, {{ citation.end }})</span>
        </figcaption>
        <div v-if="sourceFor(citation)" class="row-actions">
          <NButton text size="small" :disabled="!readable" @click="readoutId = readoutId === citation.excerptId ? '' : citation.excerptId">{{ readoutId === citation.excerptId ? '收起确切摘录' : '查看完整确切摘录' }}</NButton>
          <RouterLink v-if="readable" class="text-link" :to="{ name: 'materials', query: { attachment: sourceFor(citation)!.attachmentId, run: sourceFor(citation)!.runId ?? undefined, excerpt: citation.excerptId } }">在附件资料中查看</RouterLink>
        </div>
      </figure>
      <MaterialExcerptReadout v-if="readable && readoutId" :key="readoutId" :id="readoutId" />
    </section>
    <p class="field-help">本结果只保存在问答任务中，不自动写入知识库、题库、项目事实或模型记忆。</p>
  </section>
</template>

<style scoped>
.material-answer { display: grid; gap: 18px; min-width: 0; padding-top: 21px; border-top: 1px solid var(--line); }
.material-answer header { display: grid; gap: 9px; } h3 { margin: 0; font-size: 17px; } h4 { margin: 0; font-size: 14px; }
.answer-section { display: grid; gap: 12px; min-width: 0; } .exact-question, blockquote { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
.citations figure { display: grid; gap: 12px; margin: 0; padding: 16px 0; border-bottom: 1px solid var(--line); min-width: 0; }
figcaption { display: grid; gap: 6px; color: var(--muted); font-size: 12px; overflow-wrap: anywhere; } figcaption strong { font-weight: 550; color: var(--ink); }
.field-help, .exact-question { max-width: 75ch; } .citations .row-actions { flex-wrap: wrap; }
</style>
