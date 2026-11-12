<script setup lang="ts">
import type { RecordedAnswerConditions } from '../../api/reviewedObservations'
import { trainingModeLabels } from '../../api/training'
import { reviewDate } from '../../api/review'
defineProps<{ value: RecordedAnswerConditions }>()
const conditionLabels = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
const kindLabels = { MAIN: '题库主问', FOLLOW_UP: '同场追问', REPLAY: '独立复练' }
</script>

<template>
  <section class="observation-conditions" aria-label="确切回答保存时的条件">
    <h5>这次回答保存时的条件</h5>
    <dl><dt>确切提交</dt><dd>第 {{ value.answerNumber }} 版 · {{ reviewDate(value.answerCreatedAt) }}</dd><dt>训练模式</dt><dd>{{ trainingModeLabels[value.mode] }} · {{ kindLabels[value.questionKind] }}</dd><dt>辅助条件</dt><dd>{{ conditionLabels[value.answerCondition] }} · 当时辅助事件序号 {{ value.assistanceEventSequence }}</dd><dt>此前接触</dt><dd>编写题目 {{ value.priorExposure.authoredCount }} 次 · 查看参考 {{ value.priorExposure.referenceViewCount }} 次 · 打开评价 {{ value.priorExposure.evaluationAccessCount ?? 0 }} 次</dd><dt>最近曝光</dt><dd>{{ value.priorExposure.lastExposureAt ? reviewDate(value.priorExposure.lastExposureAt) : '未保存题库曝光时间' }}</dd><dt>技术与版本</dt><dd>{{ value.technology || '当时未记录' }}</dd><dt>场景</dt><dd>{{ value.scenario || '当时未记录' }}</dd><dt>题面条件</dt><dd>{{ value.questionConditions || '当时未记录' }}</dd></dl>
    <p class="field-help">这些条件来自确切提交记录。本次查看或补录审阅不会回改当时条件；受审观察也不证明首次独立表现或整体岗位能力。</p>
  </section>
</template>

<style scoped>
.observation-conditions { display:grid; gap:12px; min-width:0; padding:15px; background:var(--surface-soft); border-radius:6px; }h5 { margin:0; font-size:14px; }dl { display:grid; grid-template-columns:90px minmax(0,1fr); gap:9px 12px; margin:0; font-size:13px; }dt { color:var(--muted); }dd { margin:0; white-space:pre-wrap; overflow-wrap:anywhere; max-width:75ch; }@media(max-width:600px) { dl { grid-template-columns:1fr; gap:5px; }dd { margin-bottom:6px; } }
</style>
