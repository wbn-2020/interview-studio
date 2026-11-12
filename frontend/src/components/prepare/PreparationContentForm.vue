<script setup lang="ts">
import { computed, defineAsyncComponent, reactive, ref } from 'vue'
import { NButton, NInput, NInputNumber, NSelect } from 'naive-ui'
import type { ChosenVersion, ExpressionContent, FactContent, PreparationContent, PreparationKind, PreparationReference, ProjectContent, Source, TargetContent } from '../../api/preparation'
import { dimensionOptions, expressionOptions, referenceLabels, responsibilityLabels } from '../../api/preparation'
import type { MaterialExcerpt } from '../../api/material'
import PreparationPicker from './PreparationPicker.vue'
const MarkdownEditor = defineAsyncComponent(() => import('../MarkdownEditor.vue'))
const MaterialExcerptPicker = defineAsyncComponent(() => import('../material/MaterialExcerptPicker.vue'))
const MaterialExcerptReadout = defineAsyncComponent(() => import('../material/MaterialExcerptReadout.vue'))
const props = defineProps<{ kind: PreparationKind; disabled?: boolean; references?: PreparationReference[] }>()
const model = defineModel<PreparationContent>({ required: true })
const target = computed(() => model.value as TargetContent)
const project = computed(() => model.value as ProjectContent)
const fact = computed(() => model.value as FactContent)
const expression = computed(() => model.value as ExpressionContent)
const source = computed(() => (model.value as ProjectContent | FactContent).source as Source)
const tags = computed({ get: () => (model.value as FactContent | ExpressionContent).tags, set: value => { (model.value as FactContent | ExpressionContent).tags = value } })
const labels = reactive<Record<string, string>>({})
const picker = ref(false)
const excerptPicker = ref(false)
const excerptReadout = ref<string>()
// 旧版本未记录摘录字段时只按空列表展示，不因打开编辑器而改写历史 JSON。
const excerptIds = computed({ get: () => source.value.excerptIds ?? [], set: (value: string[]) => { source.value.excerptIds = value } })
const responsibilityOptions = Object.entries(responsibilityLabels).map(([value, label]) => ({ value, label }))
const priorityOptions = [1, 2, 3, 4, 5].map(value => ({ value, label: `${value}${value === 5 ? ' · 最高' : value === 1 ? ' · 最低' : ''}` }))
const factFields = [{ key: 'scenario', title: '场景', hint: '事情发生在什么业务和约束下' }, { key: 'problem', title: '问题', hint: '当时遇到的具体问题' }, { key: 'action', title: '本人动作', hint: '你实际负责、参与或仅了解哪些部分' }, { key: 'result', title: '结果与待核实处', hint: '有依据的结果；不确定的指标明确写出' }] as const
function addRequirement() { if (target.value.requirements.length < 50) target.value.requirements.push({ id: crypto.randomUUID(), text: '', dimensions: ['OTHER'], priority: 3 }) }
function choose(value: ChosenVersion) {
  if (props.disabled || (props.kind === 'TARGET' && target.value.links.length >= 100) || (props.kind === 'EXPRESSION' && expression.value.factVersionIds.length >= 50)) return
  labels[value.versionId] = `${value.title} · v${value.number}`
  if (props.kind === 'TARGET' && !target.value.links.some(link => link.versionId === value.versionId)) target.value.links.push({ kind: value.kind, itemId: value.itemId, versionId: value.versionId })
  if (props.kind === 'EXPRESSION' && !expression.value.factVersionIds.includes(value.versionId)) expression.value.factVersionIds.push(value.versionId)
}
function referenceName(versionId: string) {
  const item = props.references?.find(reference => reference.versionId === versionId)
  return labels[versionId] ?? (item ? `${item.title} · v${item.number} · ${referenceLabels[item.state]}` : `版本 ${versionId.slice(0, 8)}`)
}
function updateDate(value: string) { target.value.capturedOn = value || null }
function chooseExcerpt(value: MaterialExcerpt) {
  if (props.disabled || excerptIds.value.length >= 20 || excerptIds.value.includes(value.id)) return
  labels[value.id] = `${value.locator} · ${value.text.slice(0, 60)}${value.text.length > 60 ? '…' : ''}`
  excerptIds.value = [...excerptIds.value, value.id]
}
</script>

<template>
  <div class="prepare-content-form">
    <label>标题<NInput v-model:value="model.title" :disabled="disabled" :maxlength="200" placeholder="给材料一个容易找回的标题" /></label>
    <template v-if="kind === 'TARGET'">
      <div class="prepare-form-pair"><label>公司备注<NInput v-model:value="target.company" :disabled="disabled" :maxlength="200" placeholder="可选，仅在个人空间保存" /></label><label>获取日期<input type="date" :value="target.capturedOn ?? ''" :disabled="disabled" @input="updateDate(($event.target as HTMLInputElement).value)" /></label></div>
      <label>JD 来源网址<NInput v-model:value="target.sourceUrl" :disabled="disabled" :maxlength="2000" placeholder="HTTP(S) 地址；系统不会自动抓取" /></label>
      <label>每周可用分钟数（可留空）<NInputNumber v-model:value="target.weeklyMinutes" :disabled="disabled" :min="0" :max="10080" :precision="0" clearable /></label>
      <label>岗位描述 <small>{{ target.jd.length }}/50000</small></label><MarkdownEditor v-model="target.jd" :disabled="disabled" />
      <section class="prepare-form-section"><div class="prepare-section-heading"><h3>手工拆解要求</h3><NButton size="small" :disabled="disabled || target.requirements.length >= 50" @click="addRequirement">添加要求</NButton></div><p class="field-help">把 JD 中的期望写成可观察的考察项。要求随这个目标版本保存。</p>
        <fieldset v-for="(requirement, index) in target.requirements" :key="requirement.id" :disabled="disabled" class="requirement-fields"><legend>要求 {{ index + 1 }}</legend><label>要求内容<NInput v-model:value="requirement.text" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" :maxlength="2000" :disabled="disabled" /></label><div class="prepare-form-pair"><label>考察维度<NSelect v-model:value="requirement.dimensions" multiple :options="dimensionOptions" :disabled="disabled" /></label><label>优先级<NSelect v-model:value="requirement.priority" :options="priorityOptions" :disabled="disabled" /></label></div><NButton text size="small" :disabled="disabled" @click="target.requirements.splice(index, 1)">移除此要求</NButton></fieldset>
      </section>
      <section class="prepare-form-section"><div class="prepare-section-heading"><h3>关联准备材料</h3><NButton size="small" :disabled="disabled || target.links.length >= 100" @click="picker = !picker">{{ picker ? '收起选择' : '选择版本' }}</NButton></div><ul class="prepare-chosen"><li v-for="(link, index) in target.links" :key="`${link.kind}-${link.versionId}`"><span>{{ referenceName(link.versionId) }}</span><NButton text size="small" :disabled="disabled" @click="target.links.splice(index, 1)">移出</NButton></li></ul><PreparationPicker v-if="picker" :kinds="['PROJECT', 'FACT', 'KNOWLEDGE']" :exclude-version-ids="target.links.map(link => link.versionId)" :disabled="disabled || target.links.length >= 100" @choose="choose" /><p class="field-help">复用确切版本，不复制正文。旧来源的变更会保留提示。</p></section>
    </template>
    <template v-if="kind === 'PROJECT'">
      <label>项目概述 <small>{{ project.overview.length }}/20000</small></label><MarkdownEditor v-model="project.overview" :disabled="disabled" />
      <label>技术与适用版本<NInput v-model:value="project.technology" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" :disabled="disabled" :maxlength="2000" placeholder="例如 Java / 框架版本、实际采用的技术" /></label>
    </template>
    <template v-if="kind === 'FACT'">
      <label>责任边界<NSelect v-model:value="fact.responsibility" :options="responsibilityOptions" :disabled="disabled" /></label><p class="field-help">“参与”与“仅了解”不会因为内容已核实而变为“本人负责”。</p>
      <label v-for="field in factFields" :key="field.key">{{ field.title }}<NInput v-model:value="fact[field.key]" type="textarea" :autosize="{ minRows: 3, maxRows: 9 }" :disabled="disabled" :maxlength="10000" :placeholder="field.hint" /></label>
      <label>技术与适用版本<NInput v-model:value="fact.technology" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" :disabled="disabled" :maxlength="2000" /></label>
    </template>
    <section v-if="kind === 'PROJECT' || kind === 'FACT'" class="prepare-form-section"><h3>来源备注</h3><p class="field-help">记录手工提供的出处与待核实处；网址不会被自动读取，也不授予模型外发权限。</p><label>来源标题<NInput v-model:value="source.title" :disabled="disabled" :maxlength="200" /></label><label>来源网址（可选）<NInput v-model:value="source.url" :disabled="disabled" :maxlength="2000" placeholder="HTTP(S) 地址" /></label><label>备注与核实依据<NInput v-model:value="source.note" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="disabled" :maxlength="5000" /></label><div class="prepare-section-heading"><h3>附件原文依据</h3><NButton size="small" :disabled="disabled || excerptIds.length >= 20" @click="excerptPicker = !excerptPicker">{{ excerptPicker ? '收起摘录选择' : '选择保存的摘录' }}</NButton></div><p class="field-help">最多引用 20 个不可变摘录。只保存确切 ID；事实仍需显式核实，原文不会随正文编辑改写。</p><ul class="prepare-chosen"><li v-for="excerptId in excerptIds" :key="excerptId"><span>{{ labels[excerptId] ?? `来源摘录 ${excerptId.slice(0, 8)}` }}</span><div class="row-actions"><NButton text size="small" @click="excerptReadout = excerptReadout === excerptId ? undefined : excerptId">{{ excerptReadout === excerptId ? '收起原文' : '原文' }}</NButton><NButton text size="small" :disabled="disabled" @click="excerptIds = excerptIds.filter(id => id !== excerptId)">移出</NButton></div></li></ul><MaterialExcerptReadout v-if="excerptReadout" :id="excerptReadout" /><MaterialExcerptPicker v-if="excerptPicker" :disabled="disabled || excerptIds.length >= 20" :exclude-ids="excerptIds" @choose="chooseExcerpt" /></section>
    <template v-if="kind === 'EXPRESSION'">
      <label>表达用途<NSelect v-model:value="expression.type" :options="expressionOptions" :disabled="disabled" /></label>
      <section class="prepare-form-section"><div class="prepare-section-heading"><h3>事实依据</h3><NButton size="small" :disabled="disabled || expression.factVersionIds.length >= 50" @click="picker = !picker">{{ picker ? '收起选择' : '选择已核实事实' }}</NButton></div><p class="field-help">至少选择一个当前有效的事实版本。修改或撤回来源后，表达会提示待复核，正文不会自动改写。</p><ul class="prepare-chosen"><li v-for="(versionId, index) in expression.factVersionIds" :key="versionId"><span>{{ referenceName(versionId) }}</span><NButton text size="small" :disabled="disabled" @click="expression.factVersionIds.splice(index, 1)">移出</NButton></li></ul><PreparationPicker v-if="picker" :kinds="['FACT']" :exclude-version-ids="expression.factVersionIds" :disabled="disabled || expression.factVersionIds.length >= 50" @choose="choose" /></section>
      <label>表达正文 <small>{{ expression.body.length }}/50000</small></label><MarkdownEditor v-model="expression.body" :disabled="disabled" />
    </template>
    <label v-if="kind === 'FACT' || kind === 'EXPRESSION'">标签（最多 20 个）<NSelect v-model:value="tags" multiple filterable tag :options="tags.map(value => ({ value, label: value }))" :disabled="disabled" placeholder="输入标签后确认" /></label>
  </div>
</template>

<style scoped>
.prepare-content-form, .prepare-content-form label, .prepare-form-section { display: grid; gap: 8px; min-width: 0; }
.prepare-content-form { gap: 15px; }
.prepare-content-form label { color: var(--muted); font-size: 12px; }
.prepare-content-form label small { font-size: 11px; }
.prepare-content-form input[type=date] { width: 100%; min-height: 34px; padding: 5px 10px; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: 4px; }
.prepare-form-pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.prepare-form-section { padding-top: 16px; border-top: 1px solid var(--line); }
.prepare-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.requirement-fields { display: grid; gap: 10px; margin: 4px 0; padding: 12px 0; border: 0; border-bottom: 1px solid var(--line); min-width: 0; }
.requirement-fields legend { padding: 0; color: var(--muted); font-size: 12px; }
.requirement-fields > button { justify-self: start; }
.prepare-chosen { display: grid; gap: 7px; margin: 0; padding: 0; list-style: none; }
.prepare-chosen li { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: var(--ink); font-size: 12px; }
.prepare-chosen li span { overflow-wrap: anywhere; }
@media (max-width: 600px) { .prepare-form-pair { grid-template-columns: 1fr; } .prepare-section-heading { align-items: start; } }
</style>
