<script setup lang="ts">
import { computed } from 'vue'
import type { AiSourceQaInput, AiSourceQaSource } from '../../api/aiSourceQa'
import { aiSourceQaInputValid, aiSourceQaSourceRoute } from '../../api/aiSourceQa'
import { searchDomains, searchKindLabels } from '../../api/search'
const props = defineProps<{ input: AiSourceQaInput }>()
const valid = computed(() => aiSourceQaInputValid(props.input))
const domainLabel = (source: AiSourceQaSource) => searchDomains.find(item => item.value === source.ref.domain)?.label
const contexts = (source: AiSourceQaSource) => [
  { name: '原答条件与反馈时机', value: source.training }, { name: '人工反馈限定与决定', value: source.feedback },
  { name: '个人回忆与不确定性', value: source.recollection }, { name: 'AI 未审观察与报告限制', value: source.aiFeedback },
  { name: '完整题目条件', value: source.question }, { name: '用户事实与责任边界', value: source.fact },
  { name: '表达依据复核', value: source.expression }, { name: '固定摘录定位', value: source.material },
].filter(item => item.value != null)
</script>

<template>
  <section class="source-qa-input" aria-label="所选来源问答完整输入">
    <p v-if="!valid" class="notice error" role="alert">来源身份、选区或必要限定不完整，不能使用这份清单。</p>
    <template v-else>
      <section><h4>你的完整问题</h4><pre>{{ input.question }}</pre></section>
      <p class="field-help">只发送下列片段与明确列出的必要限定。它们保留用户材料、个人回忆或 AI 未审身份，不作为独立认证。</p>
      <article v-for="source in input.sources" :key="source.sourceKey">
        <header><h4>{{ source.sourceKey }} · {{ source.title }}</h4><RouterLink v-if="aiSourceQaSourceRoute(source)" :to="aiSourceQaSourceRoute(source)!" class="text-link">查看确切来源</RouterLink></header>
        <p class="field-help">{{ domainLabel(source) }} · {{ searchKindLabels[source.kind] ?? source.kind }}<span v-if="source.versionNumber !== null"> · 版本／提交 {{ source.versionNumber }}</span></p>
        <section v-for="(fragment, index) in source.fragments" :key="index" class="source-fragment"><h5>{{ fragment.label }} · 原字段 [{{ fragment.start }}, {{ fragment.end }})</h5><pre>{{ fragment.text }}</pre></section>
        <section v-for="context in contexts(source)" :key="context.name" class="source-context"><h5>{{ context.name }} · 随本次问题外发</h5><pre>{{ JSON.stringify(context.value, null, 2) }}</pre></section>
        <details><summary>固定来源身份</summary><pre>{{ JSON.stringify(source.ref, null, 2) }}</pre></details>
      </article>
      <details><summary>核对实际外发的完整内容</summary><pre>{{ JSON.stringify({ question: input.question, sources: input.sources }, null, 2) }}</pre></details>
      <details><summary>本地资格核对记录 · 不发给模型</summary><p class="field-help">下列代次和状态用于验证来源是否仍可发送。它们不扩大所选正文范围。</p><pre>{{ JSON.stringify(input.checks, null, 2) }}</pre></details>
    </template>
  </section>
</template>

<style scoped>
.source-qa-input, article, .source-fragment, .source-context { display: grid; gap: 12px; min-width: 0; }
.source-qa-input { gap: 20px; } article { padding: 18px 0; border-top: 1px solid var(--line); }
header { display: flex; align-items: start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
h4, h5 { margin: 0; overflow-wrap: anywhere; } h5 { color: var(--muted); font-size: 12px; font-weight: 500; }
pre { margin: 0; padding: 12px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.8 Consolas, "Microsoft YaHei", monospace; }
summary { cursor: pointer; color: var(--muted); font-size: 12px; } details > pre { margin-top: 10px; }
</style>
