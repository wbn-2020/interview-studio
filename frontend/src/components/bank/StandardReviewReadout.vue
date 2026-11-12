<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { NButton } from 'naive-ui'
import { read, errorText } from '../../api/client'
import type { MaterialExcerpt } from '../../api/material'
import type { QuestionReference } from '../../api/bank'
import { safeQuestionSourceUrl } from '../../api/bank'
import * as s from '../../api/standardReviews'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ value: s.OpenedStandardReview; currentEligibility?: s.StandardEligibility; checkpoints: QuestionReference['checkpoints']; disabled: boolean }>()
const store = useWorkspaceStore(), origins = ref<Record<string, { origin: MaterialExcerpt['origin']; locator: string }>>({}), sourceError = ref('')
const snapshot = computed(() => props.value.history.snapshot)
const eligibility = computed(() => props.currentEligibility ?? props.value.eligibility)
let alive = true, generation = 0
async function loadOrigins() {
  const request = ++generation, epoch = store.sessionEpoch
  origins.value = {}; sourceError.value = ''
  if (props.disabled || !store.sessionActive) return
  const ids = [...new Set(snapshot.value.content.evidence.flatMap(row => row.material ? [row.material.excerptId] : []))]
  const result = await Promise.allSettled(ids.map(id => read<MaterialExcerpt>(`/materials/excerpts/${encodeURIComponent(id)}`)))
  if (!alive || props.disabled || !store.sessionActive || epoch !== store.sessionEpoch || request !== generation) return
  for (let index = 0; index < result.length; index++) {
    const row = result[index]!, id = ids[index]!
    if (row.status === 'fulfilled' && row.value.id === id && ['MANUAL', 'PARSED'].includes(row.value.origin)) origins.value[id] = { origin: row.value.origin, locator: row.value.locator }
    else sourceError.value = row.status === 'rejected' ? errorText(row.reason) : '摘录来源性质响应无法核对。'
  }
}
watch([() => props.value, () => props.disabled, () => store.sessionEpoch], loadOrigins, { immediate: true })
onUnmounted(() => { alive = false; generation++ })
const pointLabel = (id: string) => props.checkpoints.find(row => row.id === id)?.text ?? '这个确切版本的历史检查项'
const evidenceNumber = (id: string) => snapshot.value.content.evidence.findIndex(row => row.id === id) + 1
const reasonText = (id: string) => eligibility.value.checkpoints.find(row => row.checkpointId === id)?.reasons.map(reason => s.eligibilityReasonLabels[reason]).join('、')
const basisStateText = (state: s.BasisState) => props.currentEligibility ? s.basisStateLabels[state] : ({ AVAILABLE: '回执记录时可用', ARCHIVED: '回执记录时已归档', UNAVAILABLE: '回执记录时不可用', EXTERNAL_RECORDED: '回执只记录外部摘录' })[state]
</script>

<template>
  <article class="standard-review-readout" aria-label="已明确打开的审阅修订">
    <header><h5>{{ snapshot.content.reviewer.label }} · 修订 {{ snapshot.revision }}</h5><span class="status-pill">{{ snapshot.status === 'WITHDRAWN' ? '已撤回' : '审阅记录' }}</span></header>
    <p class="field-help">本次打开已记录于 {{ new Date(value.recordedAt).toLocaleString('zh-CN') }}。实际审阅日期：{{ snapshot.content.reviewedOn }}（{{ snapshot.content.reviewZoneId }}）。</p>
    <p v-if="!currentEligibility" class="notice" role="status">本修订当前资格尚未确认。下面的资格与来源状态只表示打开回执记录时的历史情况，不能据此判断当前可用性；当前核对成功前不能编辑。</p>
    <p v-else-if="!currentEligibility.revisionCurrent" class="notice">这是不可变历史修订；本次独立读取的当前修订为 {{ currentEligibility.currentRevision }}。历史内容不会自动替换成后续结论。</p>
    <p v-if="currentEligibility?.currentStatus === 'WITHDRAWN'" class="notice">独立读取确认这份记录已撤回，不能继续作为有效审阅支持；历史原文仍保留。</p>
    <dl><dt>审阅者说明</dt><dd>{{ snapshot.content.reviewer.description }}</dd><dt>背景与范围</dt><dd>{{ snapshot.content.reviewContext }}</dd><dt>整体限制</dt><dd>{{ snapshot.content.limitations }}</dd><template v-if="value.history.changeReason"><dt>{{ value.history.change === 'WITHDRAWN' ? '撤回理由' : '修改理由' }}</dt><dd>{{ value.history.changeReason }}</dd></template></dl>
    <section v-for="checkpoint in snapshot.content.checkpoints" :key="checkpoint.checkpointId" class="review-point"><h5>{{ pointLabel(checkpoint.checkpointId) }}</h5><p><strong>{{ s.checkpointDecisionLabels[checkpoint.decision] }}</strong><span class="field-help"> · {{ currentEligibility ? '本次独立核对：' : '打开回执记录时：' }}{{ eligibility.checkpoints.find(row => row.checkpointId === checkpoint.checkpointId)?.eligible ? '具备本项有限支持资格' : '不具备本项支持资格' }}</span></p><p v-if="reasonText(checkpoint.checkpointId)" class="field-help">{{ currentEligibility ? '本次核对原因：' : '回执历史原因：' }}{{ reasonText(checkpoint.checkpointId) }}</p><dl><dt>适用条件</dt><dd>{{ checkpoint.conditions }}</dd><dt>结论</dt><dd>{{ checkpoint.conclusion }}</dd><dt>限制</dt><dd>{{ checkpoint.limitations }}</dd><dt>引用证据</dt><dd>{{ checkpoint.evidenceIds.map(id => `证据 ${evidenceNumber(id)}`).join('、') }}</dd></dl></section>
    <section class="review-evidence"><h5>本修订保存的证据</h5><article v-for="(evidence,index) in snapshot.content.evidence" :key="evidence.id"><h6>证据 {{ index + 1 }} · {{ s.basisKindLabels[evidence.kind] }}</h6><p class="field-help">{{ evidence.roles.map(role=>s.evidenceRoleLabels[role]).join('、') }}</p>
      <p v-if="evidence.material" class="field-help">{{ origins[evidence.material.excerptId] ? (origins[evidence.material.excerptId]!.origin === 'MANUAL' ? '用户手工摘录，未由机器确认附件原文' : '固定解析片段摘录') : '摘录来源性质尚未读取成功' }}<template v-if="origins[evidence.material.excerptId]"> · {{ origins[evidence.material.excerptId]!.locator }}</template>。本次未重新核验磁盘原件。</p>
      <p v-if="evidence.knowledge" class="field-help">引用确切知识发布版本，后续草稿或发布正文不会替换这里的引文。</p>
      <p v-if="eligibility.evidenceChecks.find(row => row.evidenceId === evidence.id)?.firstUnreviewedUpstreamChange" class="field-help">{{ currentEligibility ? '本次核对' : '打开回执' }}记录了适用于该确切版本的历史采用来源更正；处理来源影响后，原审阅资格仍须重新建立。</p>
      <template v-if="evidence.external"><p>{{ evidence.external.title }} · {{ evidence.external.locator }} · {{ evidence.external.capturedOn }}</p><a v-if="safeQuestionSourceUrl(evidence.external.url)" :href="safeQuestionSourceUrl(evidence.external.url)" target="_blank" rel="noopener noreferrer">{{ evidence.external.url }}</a><p class="preserve">{{ evidence.external.sourceDescription }}</p><p class="field-help">用户记录的外部摘录；未抓取或核验页面。</p></template>
      <blockquote>{{ evidence.knowledge?.quote ?? evidence.material?.quote ?? evidence.external?.quote }}</blockquote><dl><dt>取得经过</dt><dd>{{ evidence.provenanceNote }}</dd><dt>使用理由</dt><dd>{{ evidence.useReason }}</dd></dl>
    </article><p v-if="sourceError" class="notice error" role="alert">{{ sourceError }}<NButton text size="small" :disabled="disabled" @click="loadOrigins">重读摘录来源性质</NButton></p></section>
    <details><summary>{{ currentEligibility ? '本次独立核对的直接来源状态' : '打开回执中的历史直接来源状态' }}</summary><p v-if="!currentEligibility" class="field-help">这些来源状态属于原打开回执，不表示来源此刻仍可用。</p><ul><li v-for="basis in eligibility.basisChecks" :key="`${basis.source.kind}:${basis.source.id}`">{{ {QUESTION:'题目标准',KNOWLEDGE:'知识来源',MATERIAL_ATTACHMENT:'材料附件'}[basis.source.kind] }}：{{ basisStateText(basis.state) }}<span v-if="basis.firstUnreviewedChange"> · 此次状态中存在保存之后的来源变化</span><span v-if="basis.upstreamChangeCount > 0"> · 历史采用来源更正按各证据的确切版本判断</span><span v-if="basis.current && basis.recorded.fingerprint !== basis.current.fingerprint"> · 此次读取的来源内容与保存时不同</span></li><li v-for="limit in eligibility.limitations" :key="limit">{{ s.reviewLimitationLabels[limit] }}</li></ul><p class="field-help">只核对直接依赖，未完成间接来源更正链的全量检查。</p></details>
  </article>
</template>

<style scoped>
.standard-review-readout { display:grid; gap:16px; min-width:0; }header { display:flex; flex-wrap:wrap; justify-content:space-between; gap:10px; }h5,h6 { margin:0; font-size:14px; }h6 { font-size:13px; }dl { display:grid; grid-template-columns:88px minmax(0,1fr); gap:9px 15px; margin:0; font-size:13px; }dt { color:var(--muted); }dd { margin:0; max-width:75ch; }dd,.preserve,blockquote { white-space:pre-wrap; overflow-wrap:anywhere; }.review-point,.review-evidence { display:grid; gap:12px; padding-top:18px; border-top:1px solid var(--line); }.review-evidence>article { display:grid; gap:10px; padding:12px 0; }blockquote { margin:0; padding:12px; background:var(--surface-soft); max-width:75ch; font-size:13px; line-height:1.7; }a { overflow-wrap:anywhere; color:var(--brand-strong); text-decoration:underline; }details { font-size:12px; }summary { cursor:pointer; }li { margin:8px 0; overflow-wrap:anywhere; }@media(max-width:620px) { dl { grid-template-columns:1fr; gap:5px; }dd { margin-bottom:7px; } }
</style>
