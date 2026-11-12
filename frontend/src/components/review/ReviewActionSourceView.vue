<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import type { ReviewActionSource, ReviewAnswerLink, ReviewDecision } from '../../api/review'
import { reviewChoiceLabels, reviewCoverageLabels, reviewDate, reviewEvidenceLabels, reviewKindLabels } from '../../api/review'
import { recollectionBaselineLabels } from '../../api/recollection'
import { questionDimensionLabels } from '../../api/bank'
import type { ActionObservation, ActionSourceIdentity } from './reviewActionData'

const props = defineProps<{ observation: ActionObservation | null; answer: ReviewAnswerLink | null; decision: ReviewDecision | null;
  source: ActionSourceIdentity;
  saved?: ReviewActionSource; label?: string; disabled?: boolean }>()
const router = useRouter()
const recollection = computed(() => props.source.kind === 'RECOLLECTION' ? props.source.recollection : null)
const label = computed(() => props.source.kind === 'RECOLLECTION' ? '个人回忆中的人工观察' : props.source.kind === 'AI_EVALUATION' ? 'AI 未审观察' : props.saved?.manualKind
  ? `${reviewKindLabels[props.saved.manualKind]}${props.saved.authorLabel ? ` · ${props.saved.authorLabel}（用户录入）` : ''}` : props.label ?? '人工反馈')
function openSource() {
  if (props.disabled) return
  if (recollection.value) {
    void router.push({ name: 'recollections', query: { item: recollection.value.recollectionId, versionId: recollection.value.versionId } })
    return
  }
  if (!props.answer || !['MANUAL', 'AI_EVALUATION'].includes(props.source.kind)) return
  void router.push({ path: '/review', query: { sessionId: props.answer.sessionId, questionId: props.answer.questionId, answerId: props.answer.answerId,
    ...(props.source.kind === 'MANUAL' ? { feedbackId: props.source.feedbackId!, feedbackRevision: String(props.source.feedbackRevision) }
      : { taskId: props.source.taskId!, observationId: props.source.observationId! }) } })
}
</script>

<template>
  <section class="action-source-view" aria-label="动作来源快照">
    <template v-if="recollection">
      <p class="field-help">{{ label }} · 回忆 v{{ recollection.versionNumber }} · {{ recollectionBaselineLabels[recollection.baselineKind] }}</p>
      <p class="source-observation">{{ recollection.observation }}</p>
      <p class="field-help">这段观察由你在创建动作时填写，保留当时的回忆版本。它没有正式提交引文或接受决定；回忆后续补记不会改写此处。</p>
      <details><summary>确切回忆身份</summary><p class="field-help">回忆 {{ recollection.recollectionId }}<br>版本 {{ recollection.versionId }} · v{{ recollection.versionNumber }}</p></details>
      <NButton text size="small" :disabled="disabled" @click="openSource">回看确切回忆版本</NButton>
    </template>
    <template v-else-if="observation && answer && source.kind !== 'RECOLLECTION'">
    <p class="field-help">{{ label }} · {{ questionDimensionLabels[observation.dimension] }} · {{ reviewCoverageLabels[observation.coverage] }}</p>
    <p class="source-observation">{{ observation.observation }}</p>
    <p v-if="observation.coverage === 'NOT_MENTIONED'" class="field-help">这是本次回答可能未覆盖的内容，不代表技术错误或能力缺陷。</p>
    <dl><template v-if="observation.condition"><dt>适用条件</dt><dd>{{ observation.condition }}</dd></template><dt>观察依据</dt><dd>{{ observation.basis }}</dd><dt>判断限制</dt><dd>{{ observation.limitation }}</dd><dt>建议行动</dt><dd>{{ observation.suggestion }}</dd></dl>
    <details v-if="observation.citations.length"><summary>查看 {{ observation.citations.length }} 处原文依据</summary><figure v-for="(citation, index) in observation.citations" :key="index"><blockquote>{{ citation.quote }}</blockquote><figcaption>{{ reviewEvidenceLabels[citation.kind] }} · {{ citation.elementId ? `元素 ${citation.elementId} · ` : '' }}UTF-16 [{{ citation.start }}, {{ citation.end }})</figcaption></figure></details>
    <p v-else class="field-help">没有可定位引文，请结合判断限制阅读。</p>
    <div class="source-decision"><p class="field-help">{{ saved ? '采用时的用户决定' : '当前已保存的用户决定' }}：{{ decision ? `${reviewChoiceLabels[decision.choice]} · 第 ${decision.revision} 次` : '待处理' }}</p><p v-if="decision?.reason" class="reason">{{ decision.reason }}</p><time v-if="decision">{{ reviewDate(decision.createdAt) }}</time></div>
    <details><summary>确切来源身份</summary><p class="field-help">训练 {{ answer.sessionId }}<br>题目 {{ answer.questionId }}<br>提交 {{ answer.answerId }}<br>{{ source.kind === 'MANUAL' ? `反馈 ${source.feedbackId} · 修订 ${source.feedbackRevision}` : `AI 任务 ${source.taskId} · 观察 ${source.observationId}` }}</p></details>
    <NButton text size="small" :disabled="disabled" @click="openSource">回看确切提交与来源记录</NButton>
    </template>
    <p v-else class="notice error" role="alert">来源快照不完整，请重新读取动作。</p>
    <template v-if="saved"><dl><dt>验证条件</dt><dd>{{ saved.verification }}</dd><dt>来源跟踪</dt><dd>{{ saved.tracking === 'TRACKING' ? '继续跟踪' : '停止跟踪' }}{{ saved.trackingReason ? `：${saved.trackingReason}` : '' }}</dd></dl><p class="field-help">来源 {{ saved.id }} · 最初动作 {{ saved.originActionId }}</p></template>
  </section>
</template>

<style scoped>
.action-source-view { display: grid; gap: 11px; min-width: 0; } p { margin: 0; } .source-observation, dd, blockquote, .reason { white-space: pre-wrap; overflow-wrap: anywhere; }
dl { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 9px 14px; margin: 0; font-size: 13px; } dt { color: var(--muted); } dd { margin: 0; }
figure { margin: 12px 0 0; padding: 10px 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); } blockquote { margin: 0 0 6px; font-size: 13px; }
figcaption, time { font-size: 11px; color: var(--muted); overflow-wrap: anywhere; } summary { cursor: pointer; font-size: 13px; color: var(--muted); } details > p { margin-top: 8px; }
.source-decision { display: grid; gap: 6px; } .reason { font-size: 13px; } .n-button { justify-self: start; }
@media (max-width: 560px) { dl { grid-template-columns: 1fr; gap: 5px; } dd { margin-bottom: 6px; } }
</style>
