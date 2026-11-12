<script setup lang="ts">
import { computed } from 'vue'
import { NButton } from 'naive-ui'
import type { AiMaterialCitation, MaterialAiExcerpt } from '../../api/ai'
import { aiMaterialSourceRoute } from '../../api/ai'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ citations?: AiMaterialCitation[] | null; sources?: MaterialAiExcerpt[] | null; disabled?: boolean }>()
const emit = defineEmits<{ refresh: [] }>()
const store = useWorkspaceStore()
const readable = computed(() => store.sessionActive && !props.disabled)
const rows = computed(() => (props.citations ?? []).map(citation => {
  const matches = (props.sources ?? []).filter(source => source.excerptId === citation.excerptId)
  // 只认本次固定清单中的唯一摘录，不用当前解析结果或相似文字补造出处。
  const source = matches.length === 1 ? matches[0] : undefined
  return { citation, source, route: aiMaterialSourceRoute(source) }
}))
</script>

<template>
  <section v-if="rows.length" class="material-citations" aria-label="候选的资料原文依据">
    <h5>资料摘录引用</h5>
    <p class="field-help">区间对应本次已选摘录的原文，不是原文件偏移；定位相符不代表结论正确或经历已核实。</p>
    <figure v-for="({ citation, source, route }, index) in rows" :key="`${citation.excerptId}/${citation.start}/${citation.end}/${index}`">
      <blockquote>{{ citation.quote }}</blockquote>
      <figcaption>
        <template v-if="source">
          <strong>{{ source.fileName }} · {{ source.origin === 'MANUAL' ? '用户手工摘录' : '原解析摘录' }}</strong>
          <span>定位：{{ source.locator }}</span>
          <span v-if="source.origin === 'MANUAL'">原文和定位由用户手工提供，不表示自动解析或事实确认。</span>
          <span v-else>原解析执行 {{ source.runId }} · 片段 {{ source.segmentId }}</span>
        </template>
        <span>摘录 {{ citation.excerptId }} · 摘录内 UTF-16 [{{ citation.start }}, {{ citation.end }})</span>
      </figcaption>
      <p v-if="!source || !route" class="notice" role="status">本条引用无法匹配清单中的唯一、完整资料来源。<NButton text :disabled="!readable" @click="emit('refresh')">重新读取任务</NButton></p>
      <details v-if="source">
        <summary>查看本次外发的完整确切摘录</summary>
        <pre class="exact-text">{{ source.text }}</pre>
        <dl><div><dt>原件 SHA-256</dt><dd>{{ source.sha256 }}</dd></div><div><dt>附件</dt><dd>{{ source.attachmentId }}</dd></div><div v-if="source.origin === 'PARSED'"><dt>原片段内范围</dt><dd>UTF-16 [{{ source.startOffset }}, {{ source.endOffset }})</dd></div></dl>
        <p class="field-help">上方为当次任务的固定摘录，新解析不会替换它。</p>
      </details>
      <RouterLink v-if="readable && route" class="text-link" :to="route">在附件资料中查看确切来源</RouterLink>
    </figure>
  </section>
</template>

<style scoped>
.material-citations { display: grid; gap: 12px; min-width: 0; }
h5 { margin: 0; font-size: 12px; color: var(--muted); }
figure { display: grid; gap: 10px; min-width: 0; margin: 0; padding-left: 13px; border-left: 2px solid var(--brand); }
blockquote { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
figcaption { display: grid; gap: 5px; color: var(--muted); font-size: 11px; overflow-wrap: anywhere; }
figcaption strong { font-size: 12px; color: var(--ink); font-weight: 550; }
summary { cursor: pointer; color: var(--muted); font-size: 12px; }
.exact-text { margin: 10px 0; padding: 13px; border-radius: 5px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); }
dl { display: grid; gap: 8px; margin: 12px 0; font-size: 11px; } dl > div { display: grid; grid-template-columns: 105px minmax(0, 1fr); gap: 10px; } dt { color: var(--muted); } dd { margin: 0; overflow-wrap: anywhere; }
.text-link { justify-self: start; font-size: 12px; }
@media (max-width: 550px) { dl > div { grid-template-columns: 1fr; gap: 3px; } }
</style>
