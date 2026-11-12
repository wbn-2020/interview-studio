<script setup lang="ts">
import type { RepracticeSide } from '../../api/repractice'
import { repracticeConditionLabels, repracticeDiagram } from '../../api/repractice'
import { reviewDate } from '../../api/review'
import { formatTrainingDuration, trainingContextLabels, trainingFeedbackLabels, trainingModeLabels } from '../../api/training'
import { questionDimensionLabels, questionTypeLabels } from '../../api/bank'
import type { FactContent, ProjectContent, TargetContent } from '../../api/preparation'
import { responsibilityLabels } from '../../api/preparation'
import MarkdownPreview from '../MarkdownPreview.vue'
import TrainingDiagram from '../training/TrainingDiagram.vue'

defineProps<{ side: RepracticeSide; label: string }>()
const target = (value: unknown) => value as TargetContent
const project = (value: unknown) => value as ProjectContent
const fact = (value: unknown) => value as FactContent
</script>

<template>
  <article class="comparison-side">
    <header><p class="section-label">{{ label }}</p><h3>{{ side.question.title }}</h3><p class="field-help">第 {{ side.submission.number }} 版提交 · {{ reviewDate(side.submission.createdAt) }}</p></header>
    <div class="condition-tags"><span>{{ trainingModeLabels[side.mode] }}</span><span>{{ repracticeConditionLabels[side.submission.condition] }}</span><span>{{ questionTypeLabels[side.question.content.type] }}</span></div>
    <section><h4>固定题面</h4><MarkdownPreview :value="side.question.content.prompt" /><dl><template v-if="side.question.content.conditions"><dt>条件</dt><dd>{{ side.question.content.conditions }}</dd></template><template v-if="side.question.content.technology"><dt>技术／版本</dt><dd>{{ side.question.content.technology }}</dd></template><template v-if="side.question.content.scenario"><dt>场景</dt><dd>{{ side.question.content.scenario }}</dd></template><dt>考察维度</dt><dd>{{ side.question.content.dimensions.map(item => questionDimensionLabels[item]).join('、') }}</dd></dl></section>
    <section><h4>提交原文</h4><MarkdownPreview :value="side.submission.content.text" /><details v-if="side.submission.content.diagram.nodes.length"><summary>查看当时图文方案（{{ side.submission.content.diagram.nodes.length }} 节点／{{ side.submission.content.diagram.edges.length }} 连线）</summary><TrainingDiagram :model-value="repracticeDiagram(side)" disabled :allow-materials="false" :references="side.submission.content.diagram.nodes" /></details><p v-else class="field-help">这次提交没有图节点。</p></section>
    <details><summary>个人参考与检查点</summary><div v-if="side.standard" class="detail-content"><p class="field-help">个人未审参考 · 确切题库版本；不是确定技术判错的依据。</p><MarkdownPreview :value="side.standard.referenceAnswer" /><ol><li v-for="checkpoint in side.standard.checkpoints" :key="checkpoint.id">{{ checkpoint.text }}</li></ol></div><p v-else class="field-help">这道题没有题库参考。不会使用原追问根主问的参考替代。</p></details>
    <details><summary>当时背景（{{ side.context.length }} 项）</summary><p v-if="!side.context.length" class="field-help">本场没有选择背景材料。</p><article v-for="item in side.context" :key="item.versionId" class="context"><h4>{{ trainingContextLabels[item.kind] }} · {{ item.title }} · v{{ item.number }}</h4><template v-if="item.kind === 'TARGET'"><p v-if="target(item.content).company">{{ target(item.content).company }}</p><MarkdownPreview :value="target(item.content).jd" /><ul><li v-for="requirement in target(item.content).requirements" :key="requirement.id">{{ requirement.text }}</li></ul></template><template v-else-if="item.kind === 'PROJECT'"><MarkdownPreview :value="project(item.content).overview" /><p v-if="project(item.content).technology">技术／版本：{{ project(item.content).technology }}</p></template><template v-else><dl><dt>场景</dt><dd>{{ fact(item.content).scenario }}</dd><dt>问题</dt><dd>{{ fact(item.content).problem }}</dd><dt>动作</dt><dd>{{ fact(item.content).action }}</dd><dt>结果</dt><dd>{{ fact(item.content).result }}</dd><dt>职责</dt><dd>{{ responsibilityLabels[fact(item.content).responsibility] }}</dd><dt>技术／版本</dt><dd>{{ fact(item.content).technology || '未填写' }}</dd></dl></template><p class="field-help">保存时的确切版本，不自动替换为当前材料。</p></article></details>
    <details><summary>辅助、曝光与时间条件</summary><dl class="detail-content"><dt>反馈时机</dt><dd>{{ trainingFeedbackLabels[side.config.feedbackTiming] }}</dd><dt>时间预算</dt><dd>{{ side.config.timeBudgetMinutes === null ? '未设置' : `${side.config.timeBudgetMinutes} 分钟` }}</dd><dt>整场观察时间</dt><dd>{{ formatTrainingDuration(side.timing.observedSeconds) }}</dd><dt>整场未知间隔</dt><dd>{{ formatTrainingDuration(side.timing.unobservedSeconds + side.timing.pendingUnknownSeconds) }}</dd><dt>整场暂停时间</dt><dd>{{ formatTrainingDuration(side.timing.pausedSeconds + side.timing.pendingPausedSeconds) }}</dd><dt>此前编写题目</dt><dd>{{ side.submission.priorExposure.authoredCount }} 次</dd><dt>此前查看参考</dt><dd>{{ side.submission.priorExposure.referenceViewCount }} 次</dd><dt>此前打开评价</dt><dd>{{ side.submission.priorExposure.evaluationAccessCount ?? 0 }} 次</dd><dt>最近曝光</dt><dd>{{ side.submission.priorExposure.lastExposureAt ? reviewDate(side.submission.priorExposure.lastExposureAt) : '没有题库曝光时间记录' }}</dd></dl><p class="field-help">计时来自整场页面观察记录，不等于这次提交的净答题时长。复练已有原答，不是第一次见题。</p></details>
  </article>
</template>

<style scoped>
.comparison-side { display: grid; gap: 20px; align-content: start; min-width: 0; padding: 22px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
h3, h4, p { margin: 0; } h3 { margin: 7px 0; font-size: 20px; overflow-wrap: anywhere; } h4 { margin-bottom: 12px; font-size: 14px; } section { display: grid; gap: 12px; } section + section { border-top: 1px solid var(--line); padding-top: 18px; }
.condition-tags { display: flex; flex-wrap: wrap; gap: 7px; } .condition-tags span { padding: 4px 8px; border: 1px solid var(--line); border-radius: 4px; font-size: 11px; color: var(--muted); }
dl { display: grid; grid-template-columns: 112px minmax(0, 1fr); gap: 8px 12px; margin: 0; font-size: 13px; } dt { color: var(--muted); } dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
summary { cursor: pointer; color: var(--muted); font-size: 13px; } .detail-content, .context { margin-top: 14px; } .context { padding: 14px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 5px; display: grid; gap: 12px; } .context h4 { margin: 0; } details > .field-help { margin-top: 12px; } li { font-size: 13px; margin-bottom: 7px; overflow-wrap: anywhere; }
@media (max-width: 600px) { .comparison-side { padding: 14px; } dl { grid-template-columns: 88px minmax(0, 1fr); } }
</style>
