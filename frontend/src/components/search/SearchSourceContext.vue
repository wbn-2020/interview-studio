<script setup lang="ts">
import type { SearchHit } from '../../api/search'
import { questionDimensionLabels, questionTypeLabels } from '../../api/bank'
import { reviewChoiceLabels, reviewCoverageLabels, reviewDate, reviewKindLabels } from '../../api/review'
import { trainingFeedbackLabels, trainingModeLabels, trainingStatusLabels } from '../../api/training'
import { reportAnswerConditionLabels, reportQuestionKindLabels } from '../../api/trainingReport'

defineProps<{ hit: SearchHit; compact?: boolean }>()
</script>

<template>
  <div v-if="hit.training || hit.feedback || hit.recollection || hit.aiFeedback" class="search-context">
    <section v-if="hit.training" aria-label="当次提交条件">
      <p class="context-summary">{{ trainingModeLabels[hit.training.conditions.mode] }} · {{ reportAnswerConditionLabels[hit.training.conditions.answerCondition] }} · {{ reportQuestionKindLabels[hit.training.conditions.questionKind] }}</p>
      <p class="field-help">正式提交 #{{ hit.training.conditions.answerNumber }} · {{ reviewDate(hit.training.conditions.answerCreatedAt) }}</p>
      <p class="field-help">先前曝光：编写 {{ hit.training.conditions.priorExposure.authoredCount }} 次 · 查阅参考 {{ hit.training.conditions.priorExposure.referenceViewCount }} 次 · 进入评价 {{ hit.training.conditions.priorExposure.evaluationAccessCount }} 次</p>
      <p v-if="hit.training.conditions.answerCondition === 'BEFORE_ASSISTANCE'" class="field-help">“辅助确认前”仅指本次提交记录的条件，不表示从未接触这道题。</p>
      <details :open="!compact">
        <summary>查看当时条件与当前会话状态</summary>
        <dl>
          <dt>辅助事件序列</dt><dd>{{ hit.training.conditions.assistanceEventSequence }}</dd>
          <dt>最近先前曝光</dt><dd>{{ hit.training.conditions.priorExposure.lastExposureAt ? reviewDate(hit.training.conditions.priorExposure.lastExposureAt) : '没有记录时间' }}</dd>
          <dt>技术与版本</dt><dd>{{ hit.training.conditions.technology || '当时未填写' }}</dd>
          <dt>场景</dt><dd>{{ hit.training.conditions.scenario || '当时未填写' }}</dd>
          <dt>题目条件</dt><dd>{{ hit.training.conditions.questionConditions || '当时未填写' }}</dd>
          <dt>当前会话</dt><dd>{{ trainingStatusLabels[hit.training.sessionStatus] }}</dd>
          <dt>约定反馈时机</dt><dd>{{ trainingFeedbackLabels[hit.training.feedbackTiming] }}</dd>
        </dl>
        <p class="field-help">作答条件与曝光取自当次提交，不随之后查看资料或会话状态变化重算。</p>
      </details>
    </section>
    <section v-if="hit.feedback" aria-label="人工反馈限定">
      <p class="context-summary">{{ reviewKindLabels[hit.feedback.kind] }} · {{ questionDimensionLabels[hit.feedback.dimension] }} · {{ reviewCoverageLabels[hit.feedback.coverage] }}</p>
      <p v-if="hit.feedback.authorLabel" class="field-help">{{ hit.feedback.authorLabel }}（用户录入称呼）</p>
      <p v-if="hit.feedback.coverage === 'NOT_MENTIONED'" class="field-help">“可能未提及”描述这次回答的覆盖情况，不代表技术错误或能力缺陷。</p>
      <p class="field-help">此修订的当前决定：{{ hit.feedback.decision ? reviewChoiceLabels[hit.feedback.decision.choice] : '尚未作出决定' }}。用户接受不等于技术认证。</p>
      <details :open="!compact">
        <summary>适用条件、依据与限制</summary>
        <dl>
          <dt>适用条件</dt><dd>{{ hit.feedback.condition || '未填写适用条件' }}</dd>
          <dt>观察依据</dt><dd>{{ hit.feedback.basis }}</dd>
          <dt>判断限制</dt><dd>{{ hit.feedback.limitation }}</dd>
          <template v-if="hit.feedback.decision">
            <dt>决定记录</dt><dd>r{{ hit.feedback.decision.revision }} · {{ reviewDate(hit.feedback.decision.createdAt) }}</dd>
            <dt>决定理由</dt><dd>{{ hit.feedback.decision.reason || '未填写理由' }}</dd>
          </template>
        </dl>
      </details>
      <p class="field-help">完整引文请回原复盘查看。</p>
    </section>
    <section v-if="hit.recollection" aria-label="个人回忆限定">
      <p class="context-summary">个人回忆 · {{ hit.recollection.baselineKind === 'RECOLLECTION' ? '有回答回忆片段' : '无旧回答' }} · {{ questionTypeLabels[hit.recollection.questionType] }}</p>
      <dl><dt>不确定性说明</dt><dd>{{ hit.recollection.uncertainty }}</dd></dl>
      <p class="field-help">这是用户事后补记，旧作答模式、计时和辅助条件未知；保存时间不代表当时作答时间，补记的反馈也未因此获得核实。</p>
    </section>
    <section v-if="hit.aiFeedback" aria-label="AI 评价限定">
      <p class="context-summary">AI 未审 · {{ hit.aiFeedback.feedbackId }} · {{ questionDimensionLabels[hit.aiFeedback.dimension] }} · {{ reviewCoverageLabels[hit.aiFeedback.coverage] }}</p>
      <p v-if="hit.aiFeedback.coverage === 'NOT_MENTIONED'" class="field-help">“可能未提及”描述这次回答的覆盖情况，不代表技术错误或能力缺陷。</p>
      <p class="field-help">当前决定：{{ hit.aiFeedback.decisionChoice ? reviewChoiceLabels[hit.aiFeedback.decisionChoice] : '尚未作出决定' }}<template v-if="hit.aiFeedback.decisionRevision"> · 第 {{ hit.aiFeedback.decisionRevision }} 次</template>。用户接受不等于技术认证。</p>
      <details :open="!compact">
        <summary>报告限制、校验说明与观察限定</summary>
        <dl>
          <dt>报告限制</dt><dd>{{ hit.aiFeedback.reportLimitations }}</dd>
          <dt>校验说明</dt><dd>{{ hit.aiFeedback.validationNote }}</dd>
          <dt>适用条件</dt><dd>{{ hit.aiFeedback.condition || '未填写适用条件' }}</dd>
          <dt>观察依据</dt><dd>{{ hit.aiFeedback.basis }}</dd>
          <dt>判断限制</dt><dd>{{ hit.aiFeedback.limitation }}</dd>
        </dl>
      </details>
      <p class="field-help">此观察固定于报告的成功执行；当前决定单独更新，完整引用请回原复盘查看。</p>
    </section>
    <p v-if="hit.domain === 'TRAINING_ANSWER' && !compact" class="field-help">图节点和连线在此按文字字段展示，完整图结构请回原提交查看。</p>
  </div>
</template>

<style scoped>
.search-context, section { display: grid; gap: 8px; min-width: 0; }
.search-context { gap: 12px; padding: 12px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
p, dl, dd { margin: 0; }
.context-summary { font-size: 12px; color: var(--ink); overflow-wrap: anywhere; }
details { min-width: 0; }
summary { width: fit-content; cursor: pointer; color: var(--muted); font-size: 12px; }
summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }
details[open] summary { margin-bottom: 10px; }
details > .field-help { margin-top: 10px; }
dl { display: grid; grid-template-columns: 84px minmax(0, 1fr); gap: 7px 10px; font-size: 12px; }
dt { color: var(--muted); }
dd { white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 600px) { dl { grid-template-columns: 1fr; gap: 4px; } dd { margin-bottom: 6px; } }
</style>
