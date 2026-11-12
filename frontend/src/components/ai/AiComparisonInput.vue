<script setup lang="ts">
import { computed } from 'vue'
import type { AiComparisonInput } from '../../api/aiComparison'
import { aiComparisonDifferenceLabels } from '../../api/aiComparison'
import { questionTypeLabels } from '../../api/bank'
import { repracticeConditionLabels } from '../../api/repractice'
import { trainingFeedbackLabels, trainingModeLabels } from '../../api/training'

const props = defineProps<{ input: AiComparisonInput }>()
const sides = computed(() => [{ key: 'baseline', label: '旧答', value: props.input.baseline }, { key: 'current', label: '新答', value: props.input.current }] as const)
const questionFields = [['title', '题目标题'], ['prompt', '固定题干'], ['conditions', '适用条件'], ['technology', '技术与版本'], ['scenario', '场景']] as const
</script>

<template>
  <section class="comparison-input" aria-label="双答案比较的完整外发输入">
    <header><h4>比较 {{ input.comparisonId }}</h4><p class="field-help">以下双端提交、题面、作答条件、图中文字及选定的验证条件都会发送。题目和回答中已写入的私人文字也包含在内，请逐字核对。</p></header>
    <section class="difference-summary" aria-label="发送的条件差异标记">
      <h4>条件差异</h4>
      <dl class="difference-grid"><div v-for="(label, key) in aiComparisonDifferenceLabels" :key="key"><dt>{{ label }}</dt><dd>{{ input.differences[key] ? '有变化 · true' : '无变化 · false' }}</dd></div></dl>
      <p class="field-help">个人参考和背景只发送“是否变化”的标记。它们的正文、原反馈、用户决定、动作详情、图坐标、知识链接与附件不在比较输入中。条件相同也不证明两份回答可直接比较。</p>
    </section>
    <article v-for="side in sides" :key="side.key" class="answer-side" :aria-label="`${side.label}的完整外发内容`">
      <header><h4>{{ side.label }} · 第 {{ side.value.answerNumber }} 版提交</h4><RouterLink class="text-link" :to="{ name: 'review', query: { ...side.value.identity } }">查看这次确切提交</RouterLink></header>
      <dl class="identity-list">
        <div><dt>训练会话</dt><dd>{{ side.value.identity.sessionId }}</dd></div><div><dt>训练题目</dt><dd>{{ side.value.identity.questionId }}</dd></div><div><dt>确切提交</dt><dd>{{ side.value.identity.answerId }}</dd></div>
        <div><dt>训练模式</dt><dd>{{ trainingModeLabels[side.value.mode] }} · {{ side.value.mode }}</dd></div>
        <div><dt>反馈时机</dt><dd>{{ trainingFeedbackLabels[side.value.feedbackTiming] }} · {{ side.value.feedbackTiming }}</dd></div>
        <div><dt>提交时辅助条件</dt><dd>{{ repracticeConditionLabels[side.value.answerCondition] }} · {{ side.value.answerCondition }}</dd></div>
        <div><dt>此前创作次数</dt><dd>{{ side.value.priorExposure.authoredCount }}</dd></div><div><dt>此前打开参考次数</dt><dd>{{ side.value.priorExposure.referenceViewCount }}</dd></div>
        <div><dt>此前打开评价区次数</dt><dd>{{ side.value.priorExposure.evaluationAccessCount }}</dd></div><div><dt>最近站内接触时间</dt><dd>{{ side.value.priorExposure.lastExposureAt ?? '未记录 · null' }}</dd></div>
        <div><dt>整场时间预算</dt><dd>{{ side.value.timeBudgetMinutes === null ? '未设置 · null' : `${side.value.timeBudgetMinutes} 分钟` }}</dd></div>
        <div><dt>整场已观察区间</dt><dd>{{ side.value.timing.observedSeconds }} 秒</dd></div><div><dt>整场未观察区间</dt><dd>{{ side.value.timing.unobservedSeconds }} 秒</dd></div>
        <div><dt>整场暂停区间</dt><dd>{{ side.value.timing.pausedSeconds }} 秒</dd></div><div><dt>尚未归类的尾段</dt><dd>{{ side.value.timing.pendingUnknownSeconds }} 秒</dd></div><div><dt>尚未结算的暂停</dt><dd>{{ side.value.timing.pendingPausedSeconds }} 秒</dd></div>
        <div><dt>是否到达预算提醒点</dt><dd>{{ side.value.timing.budgetReached ? '是 · true' : '否 · false' }}</dd></div><div><dt>计时记录时刻</dt><dd>{{ side.value.timing.asOf }}</dd></div>
      </dl>
      <p class="field-help">接触次数只表示站内记录；整场时间不表示单题净作答时长、专注度或无辅助证明。</p>
      <section class="source-fields">
        <h4>{{ side.label }}题面</h4><p class="field-help">题目类型：{{ questionTypeLabels[side.value.question.type] }} · {{ side.value.question.type }}</p>
        <div v-for="field in questionFields" :key="field[0]"><h5>{{ field[1] }}</h5><pre v-if="side.value.question[field[0]]" class="exact-text">{{ side.value.question[field[0]] }}</pre><p v-else class="field-help">该字段为空字符串。</p></div>
      </section>
      <section class="source-fields"><h4>{{ side.label }}回答文字</h4><pre class="exact-text">{{ side.value.answer.text }}</pre></section>
      <section class="source-fields">
        <h4>{{ side.label }}图节点 · {{ side.value.answer.nodes.length }} 个</h4><p v-if="!side.value.answer.nodes.length" class="field-help">没有图节点，发送空数组。</p>
        <article v-for="node in side.value.answer.nodes" :key="node.id" class="diagram-item"><h5>节点 {{ node.id }}</h5><h5>名称</h5><pre class="exact-text">{{ node.label }}</pre><h5>说明</h5><pre v-if="node.description" class="exact-text">{{ node.description }}</pre><p v-else class="field-help">说明为空字符串。</p></article>
        <h4>{{ side.label }}图连线 · {{ side.value.answer.edges.length }} 条</h4><p v-if="!side.value.answer.edges.length" class="field-help">没有图连线，发送空数组。</p>
        <article v-for="edge in side.value.answer.edges" :key="edge.id" class="diagram-item"><h5>连线 {{ edge.id }}</h5><p class="identity-copy">{{ edge.source }} → {{ edge.target }}</p><h5>说明</h5><pre v-if="edge.label" class="exact-text">{{ edge.label }}</pre><p v-else class="field-help">说明为空字符串。</p></article>
      </section>
    </article>
    <section class="source-fields" aria-label="选中来源的固定验证条件"><h4>需要比较的来源 · {{ input.sources.length }} 条</h4><article v-for="source in input.sources" :key="source.sourceId" class="diagram-item"><h5>来源 {{ source.sourceId }}</h5><pre class="exact-text">{{ source.verification }}</pre></article></section>
    <details><summary>核对完整 comparisonInput JSON</summary><pre class="exact-text">{{ JSON.stringify(input, null, 2) }}</pre></details>
  </section>
</template>

<style scoped>
.comparison-input, .answer-side, .source-fields, .difference-summary, .diagram-item { display: grid; gap: 13px; min-width: 0; }
header { display: grid; gap: 9px; } h4, h5, p { margin: 0; } h4 { font-size: 14px; overflow-wrap: anywhere; } h5 { margin-bottom: 7px; color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.answer-side, .source-fields, .difference-summary { padding-top: 18px; border-top: 1px solid var(--line); }
.identity-list, .difference-grid { display: grid; gap: 9px; margin: 0; font-size: 12px; } .identity-list > div { display: grid; grid-template-columns: minmax(120px, 170px) minmax(0, 1fr); gap: 12px; } dt { color: var(--muted); } dd { margin: 0; overflow-wrap: anywhere; }
.difference-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; } .difference-grid > div { display: grid; gap: 5px; }
.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }
.identity-copy { font-size: 12px; overflow-wrap: anywhere; } .text-link { justify-self: start; font-size: 12px; } .field-help { overflow-wrap: anywhere; }
summary { cursor: pointer; font-size: 12px; color: var(--muted); } summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; } details > .exact-text { margin-top: 10px; }
@media (max-width: 550px) { .identity-list > div { grid-template-columns: 1fr; gap: 3px; } .difference-grid { grid-template-columns: 1fr; } }
</style>
