<script setup lang="ts">
import { NButton } from 'naive-ui'
import * as o from '../../api/reviewedObservations'
import * as s from '../../api/standardReviews'
import ReviewedObservationConditions from './ReviewedObservationConditions.vue'
defineProps<{ snapshot: o.ReviewedObservation; checkpointText: string; conditions?: o.RecordedAnswerConditions; eligibility?: o.ObservationEligibility; changeReason?: string | null; disabled?: boolean }>()
const emit = defineEmits<{ 'open-standard': [value: s.RevisionRef] }>()
</script>

<template>
  <article class="observation-readout" aria-label="已保存的受审观察修订">
    <header><h5>观察修订 {{ snapshot.revision }} · {{ s.recordStatusLabels[snapshot.status] }}</h5><span>{{ snapshot.content.observedOn }}（{{ snapshot.content.observationZoneId }}）</span></header>
    <p v-if="!eligibility" class="notice">这是已保存的不可变内容，当前资格尚未独立确认。历史回执或历史目录不代表这条观察此刻仍可用。</p>
    <p v-else class="notice">{{ eligibility.eligibleAsReviewedObservation ? '本次读取具备限定观察资格' : '本次读取不具备限定观察资格' }}<template v-if="eligibility.reasons.length">：{{ eligibility.reasons.map(reason=>s.eligibilityReasonLabels[reason]).join('、') }}</template>。{{ eligibility.revisionCurrent ? '这是该记录当前修订。' : `记录已有修订 ${eligibility.currentRevision}，本页保留原修订内容。` }}</p>
    <p><strong>{{ checkpointText }}</strong></p><p>{{ o.observationCoverageLabels[snapshot.content.coverage] }} · {{ o.observationConclusionLabels[snapshot.content.conclusion] }}</p>
    <dl><dt>观察陈述</dt><dd>{{ snapshot.content.statement }}</dd><dt>观察条件</dt><dd>{{ snapshot.content.conditions }}</dd><dt>判断限制</dt><dd>{{ snapshot.content.limitations }}</dd><dt>记录者说明</dt><dd>{{ snapshot.content.observerDescription }}</dd><template v-if="changeReason"><dt>{{ snapshot.status === 'WITHDRAWN' ? '撤回理由' : '修改理由' }}</dt><dd>{{ changeReason }}</dd></template></dl>
    <ol><li v-for="(citation,index) in snapshot.content.citations" :key="index"><strong>{{ o.observationCitationKindLabels[citation.kind] }}</strong><blockquote>{{ citation.quote }}</blockquote></li></ol>
    <div class="row-actions"><NButton size="small" :disabled="disabled" @click="emit('open-standard',snapshot.content.standardReview)">打开所引用的标准审阅修订 {{ snapshot.content.standardReview.revision }} 并记录查看</NButton></div>
    <p class="field-help">该引用始终指向保存时的确切审阅修订。后续审阅修订不会替换这里的依据，也不会将本观察改为“已掌握”。</p>
    <ReviewedObservationConditions v-if="conditions" :value="conditions" />
    <details v-if="eligibility"><summary>资格与来源限制</summary><ul><li v-for="limit in eligibility.limitations" :key="limit">{{ s.reviewLimitationLabels[limit] }}</li></ul><p class="field-help">只核对直接依赖，间接更正链仍为部分检查。</p></details>
  </article>
</template>

<style scoped>
.observation-readout { display:grid; gap:15px; min-width:0; }header { display:flex; flex-wrap:wrap; justify-content:space-between; gap:10px; }header>span { font-size:12px; color:var(--muted); }h5 { margin:0; font-size:14px; }dl { display:grid; grid-template-columns:90px minmax(0,1fr); gap:9px 12px; margin:0; font-size:13px; }dt { color:var(--muted); }dd { margin:0; white-space:pre-wrap; overflow-wrap:anywhere; max-width:75ch; }ol { margin:0; padding-left:20px; }li { margin:8px 0; }li strong { font-size:12px; }blockquote { margin:6px 0; white-space:pre-wrap; overflow-wrap:anywhere; font-size:13px; line-height:1.7; max-width:75ch; }details { font-size:12px; }summary { cursor:pointer; }@media(max-width:600px) { dl { grid-template-columns:1fr; gap:5px; }dd { margin-bottom:6px; } }
</style>
