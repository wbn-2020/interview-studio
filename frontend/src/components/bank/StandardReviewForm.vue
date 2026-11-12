<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import type { QuestionReference } from '../../api/bank'
import * as s from '../../api/standardReviews'
import StandardReviewEvidencePicker from './StandardReviewEvidencePicker.vue'

const props = defineProps<{ draft: s.StandardReviewDraft; checkpoints: QuestionReference['checkpoints']; disabled: boolean }>()
const emit = defineEmits<{ 'picker-dirty': [value: boolean] }>()
const picker = ref(false)
const evidenceOptions = computed(() => props.draft.content.evidence.map((row, index) => ({ value: row.id, label: `证据 ${index + 1} · ${s.basisKindLabels[row.kind]} · ${(row.external?.title ?? row.knowledge?.quote ?? row.material?.quote ?? '').slice(0, 45)}` })))
const point = (id: string) => props.draft.content.checkpoints.find(row => row.checkpointId === id)
function togglePoint(id: string, checked: boolean) {
  if (props.disabled) return
  const values = props.draft.content.checkpoints
  props.draft.content.checkpoints = checked ? props.checkpoints.flatMap(row => row.id === id ? [s.emptyStandardCheckpointReview(id)] : values.filter(value => value.checkpointId === row.id)) : values.filter(row => row.checkpointId !== id)
}
function toggleRole(value: s.ReviewEvidence, role: s.EvidenceRole, checked: boolean) {
  if (props.disabled) return
  // 角色顺序是契约的一部分；不能按用户点击顺序发送。
  value.roles = (['REVIEW_RECORD', 'TECHNICAL_BASIS'] as const).filter(item => item === role ? checked : value.roles.includes(item))
}
function removeEvidence(id: string) {
  if (props.disabled) return
  props.draft.content.evidence = props.draft.content.evidence.filter(row => row.id !== id)
  for (const checkpoint of props.draft.content.checkpoints) checkpoint.evidenceIds = checkpoint.evidenceIds.filter(value => value !== id)
}
function addEvidence(value: s.ReviewEvidence) { if (!props.disabled && props.draft.content.evidence.length < 12) props.draft.content.evidence.push(value) }
</script>

<template>
  <div class="standard-review-form">
    <div class="review-fields">
      <label>审阅者称谓<NInput v-model:value="draft.content.reviewer.label" :disabled="disabled" :maxlength="120" placeholder="记录中使用的姓名或称谓" /></label>
      <label>实际审阅日期<input v-model="draft.content.reviewedOn" :disabled="disabled" type="date" min="1000-01-01" max="9999-12-31" /></label>
      <label class="wide">审阅者身份与参与说明<NInput v-model:value="draft.content.reviewer.description" :disabled="disabled" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" placeholder="由你说明其身份、参与方式，以及不能确认的部分" /></label>
      <label>日期所属时区<NInput v-model:value="draft.content.reviewZoneId" :disabled="disabled" :maxlength="100" placeholder="Asia/Shanghai 或 UTC" /></label>
      <p class="field-help">审阅日期由你记录；系统录入时间另行保存。平台不认证审阅者身份、资质或审阅是否实际发生。</p>
      <label class="wide">审阅背景与范围<NInput v-model:value="draft.content.reviewContext" :disabled="disabled" type="textarea" :maxlength="2000" :autosize="{minRows:3,maxRows:7}" placeholder="这次审阅针对什么问题、技术版本和场景，以什么方式进行" /></label>
    </div>

    <section class="form-section" aria-label="审阅来源证据"><header><div><h5>来源证据 <span class="field-help">{{ draft.content.evidence.length }} / 12 条</span></h5><p class="field-help">每个检查项都要有真实审阅记录与技术依据两种角色。同一份材料可承担两种角色，需分别说明取得经过与使用理由。</p></div><NButton size="small" :disabled="disabled || draft.content.evidence.length >= 12" :aria-expanded="picker" @click="picker = !picker">{{ picker ? '收起来源选择' : '添加来源证据' }}</NButton></header>
      <StandardReviewEvidencePicker v-show="picker" :disabled="disabled || !picker" :existing="draft.content.evidence" @add="addEvidence" @dirty="emit('picker-dirty', $event)" />
      <p v-if="!draft.content.evidence.length" class="field-help">选择已发布知识、已保存材料摘录，或填写外部记录摘录。技术资料本身不能证明某位审阅者曾审阅过。</p>
      <article v-for="(evidence, index) in draft.content.evidence" :key="evidence.id" class="evidence-row"><header><h5>证据 {{ index + 1 }} · {{ s.basisKindLabels[evidence.kind] }}</h5><NButton text size="small" :disabled="disabled" @click="removeEvidence(evidence.id)">移除证据及检查项引用</NButton></header>
        <p v-if="evidence.external" class="field-help">{{ evidence.external.title }} · {{ evidence.external.locator }} · 摘录于 {{ evidence.external.capturedOn }}</p>
        <blockquote>{{ evidence.knowledge?.quote ?? evidence.material?.quote ?? evidence.external?.quote }}</blockquote>
        <details v-if="evidence.external"><summary>修改外部来源记录</summary><div class="review-fields">
          <label>来源标题<NInput v-model:value="evidence.external.title" :disabled="disabled" :maxlength="200" /></label><label>公开地址<NInput v-model:value="evidence.external.url" :disabled="disabled" :maxlength="2000" /></label>
          <label>明确定位<NInput v-model:value="evidence.external.locator" :disabled="disabled" :maxlength="500" /></label><label>摘录日期<input v-model="evidence.external.capturedOn" type="date" min="1000-01-01" max="9999-12-31" :disabled="disabled" /></label>
          <label class="wide">摘录原文<NInput v-model:value="evidence.external.quote" :disabled="disabled" type="textarea" :maxlength="2000" :autosize="{minRows:3,maxRows:8}" /></label><label class="wide">来源性质说明<NInput v-model:value="evidence.external.sourceDescription" :disabled="disabled" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" /></label>
        </div><p class="field-help">修改仍只保存你填写的摘录，不会抓取外部页面。</p></details>
        <div class="role-options"><NCheckbox v-for="role in (['REVIEW_RECORD','TECHNICAL_BASIS'] as const)" :key="role" :checked="evidence.roles.includes(role)" :disabled="disabled" @update:checked="toggleRole(evidence, role, $event)">{{ s.evidenceRoleLabels[role] }}</NCheckbox></div>
        <label>取得经过与记录位置<NInput v-model:value="evidence.provenanceNote" :disabled="disabled" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" placeholder="如何取得这次审阅记录，它与所声明审阅者及范围有什么关系" /></label>
        <label>用于这些检查项的理由<NInput v-model:value="evidence.useReason" :disabled="disabled" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" placeholder="说明它如何支持、质疑或限制关联检查项" /></label>
      </article>
    </section>

    <section class="form-section" aria-label="逐检查项审阅"><h5>选择本次实际审阅的检查项</h5><p class="field-help">只使用这个确切题目版本已有的检查项。未选择的检查项不属于本次审阅范围。</p>
      <article v-for="checkpoint in checkpoints" :key="checkpoint.id" class="checkpoint-row"><NCheckbox :checked="Boolean(point(checkpoint.id))" :disabled="disabled" @update:checked="togglePoint(checkpoint.id, $event)">{{ checkpoint.text }}</NCheckbox>
        <div v-if="point(checkpoint.id)" class="checkpoint-fields">
          <label>本项审阅意见<NSelect v-model:value="point(checkpoint.id)!.decision" :disabled="disabled" :options="Object.entries(s.checkpointDecisionLabels).map(([value,label])=>({value,label}))" /></label>
          <label>适用条件<NInput v-model:value="point(checkpoint.id)!.conditions" :disabled="disabled" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" /></label>
          <label>审阅结论<NInput v-model:value="point(checkpoint.id)!.conclusion" :disabled="disabled" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" /></label>
          <label>限制与未确认事项<NInput v-model:value="point(checkpoint.id)!.limitations" :disabled="disabled" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" /></label>
          <label>本项使用的证据<NSelect v-model:value="point(checkpoint.id)!.evidenceIds" multiple :disabled="disabled" :options="evidenceOptions" placeholder="选择同时覆盖审阅记录与技术依据的证据" /></label>
        </div>
      </article>
    </section>
    <label>整份审阅的限制<NInput v-model:value="draft.content.limitations" :disabled="disabled" type="textarea" :maxlength="2000" :autosize="{minRows:3,maxRows:6}" placeholder="说明本记录不能证明什么，以及仍需补充的验证" /></label>
    <label v-if="draft.id">本次修改理由<NInput :value="draft.changeReason ?? ''" :disabled="disabled" type="textarea" :maxlength="2000" :autosize="{minRows:2,maxRows:5}" @update:value="draft.changeReason = $event" /></label>
  </div>
</template>

<style scoped>
.standard-review-form,.form-section,.evidence-row,.checkpoint-fields { display:grid; gap:15px; min-width:0; }.review-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:15px; align-items:start; }.wide { grid-column:1/-1; }label { display:grid; gap:7px; font-size:13px; }h5 { margin:0; font-size:14px; }header { display:flex; justify-content:space-between; align-items:start; gap:14px; flex-wrap:wrap; }header>div { min-width:0; flex:1; }header p { margin-top:7px; }.form-section { border-top:1px solid var(--line); padding-top:20px; }.evidence-row,.checkpoint-row { border-bottom:1px solid var(--line); padding:3px 0 18px; }.checkpoint-fields { margin-top:15px; padding:0 0 0 23px; }.role-options { display:flex; flex-wrap:wrap; gap:10px 20px; }blockquote { margin:0; padding:12px; background:var(--surface-soft); white-space:pre-wrap; overflow-wrap:anywhere; max-width:75ch; font-size:13px; line-height:1.7; }details { font-size:13px; }summary { cursor:pointer; margin-bottom:14px; }input[type=date] { box-sizing:border-box; width:100%; border:1px solid var(--line); border-radius:6px; padding:9px 11px; background:var(--surface); color:var(--ink); font:inherit; }input:focus-visible { outline:2px solid var(--brand); outline-offset:2px; }@media(max-width:620px) { .review-fields { grid-template-columns:1fr; }.checkpoint-fields { padding-left:0; } }
</style>
