<script setup lang="ts">
import { computed } from 'vue'
import type { ExpressionContent, FactContent, PreparationContent, PreparationKind, ProjectContent, TargetContent } from '../../api/preparation'
import { dimensionOptions, expressionOptions, responsibilityLabels } from '../../api/preparation'
import MarkdownPreview from '../MarkdownPreview.vue'
import MaterialExcerptReadout from '../material/MaterialExcerptReadout.vue'
const props = defineProps<{ kind: PreparationKind; content: PreparationContent }>()
const target = computed(() => props.content as TargetContent)
const project = computed(() => props.content as ProjectContent)
const fact = computed(() => props.content as FactContent)
const expression = computed(() => props.content as ExpressionContent)
const source = computed(() => (props.content as ProjectContent | FactContent).source)
const dimensionName = (value: string) => dimensionOptions.find(option => option.value === value)?.label ?? value
const validUrl = (value: string) => { try { return ['http:', 'https:'].includes(new URL(value).protocol) } catch { return false } }
</script>

<template>
  <article class="prepare-readout">
    <h2>{{ content.title }}</h2>
    <template v-if="kind === 'TARGET'"><p v-if="target.company" class="muted">{{ target.company }}</p><p class="field-help">{{ target.capturedOn ? `资料日期 ${target.capturedOn}` : '未填写资料日期' }}{{ target.weeklyMinutes !== null ? ` · 每周 ${target.weeklyMinutes} 分钟` : '' }}</p><a v-if="validUrl(target.sourceUrl)" class="text-link" :href="target.sourceUrl" target="_blank" rel="noopener noreferrer">查看用户记录的 JD 来源</a><MarkdownPreview :value="target.jd" /><h3>当时的要求</h3><p v-if="!target.requirements.length" class="field-help">该版本没有拆解要求。</p><ol class="readout-requirements"><li v-for="requirement in target.requirements" :key="requirement.id"><p>{{ requirement.text }}</p><small>{{ requirement.dimensions.map(dimensionName).join(' / ') }} · 优先级 {{ requirement.priority }}</small></li></ol><h3>当时关联的版本</h3><p v-if="!target.links.length" class="field-help">该版本没有关联材料。</p><ul class="readout-identities"><li v-for="link in target.links" :key="link.versionId">{{ link.kind }} · 对象 {{ link.itemId }} · 版本 {{ link.versionId }}</li></ul></template>
    <template v-else-if="kind === 'PROJECT'"><MarkdownPreview :value="project.overview" /><h3>技术与适用版本</h3><p class="preserve">{{ project.technology || '未记录' }}</p></template>
    <template v-else-if="kind === 'FACT'"><p class="status-pill">{{ responsibilityLabels[fact.responsibility] }}</p><template v-for="field in ([['scenario', '场景'], ['problem', '问题'], ['action', '本人动作'], ['result', '结果与待核实处'], ['technology', '技术与适用版本']] as const)" :key="field[0]"><h3>{{ field[1] }}</h3><p class="preserve">{{ fact[field[0]] || '未记录' }}</p></template></template>
    <template v-else-if="kind === 'EXPRESSION'"><p class="field-help">{{ expressionOptions.find(option => option.value === expression.type)?.label }}</p><MarkdownPreview :value="expression.body" /><h3>当时的事实版本</h3><ul class="readout-identities"><li v-for="versionId in expression.factVersionIds" :key="versionId">{{ versionId }}</li></ul></template>
    <template v-if="kind === 'PROJECT' || kind === 'FACT'"><h3>当时的来源备注</h3><p>{{ source.title || '用户手工记录，未填写出处标题' }}</p><a v-if="validUrl(source.url)" class="text-link" :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.url }}</a><p class="preserve">{{ source.note || '未填写来源备注' }}</p><template v-if="source.excerptIds?.length"><h3>当时引用的附件原文</h3><MaterialExcerptReadout v-for="id in source.excerptIds" :key="id" :id="id" /></template></template>
    <p v-if="'tags' in content && content.tags.length" class="field-help">标签：{{ content.tags.join(' · ') }}</p>
  </article>
</template>

<style scoped>
.prepare-readout { display: grid; gap: 12px; min-width: 0; max-width: 75ch; }
.prepare-readout > h2, .prepare-readout > p, .prepare-readout > a { overflow-wrap: anywhere; }
.prepare-readout > h3 { margin-top: 12px; }
.prepare-readout > .status-pill { justify-self: start; }
.preserve { white-space: pre-wrap; }
.readout-requirements { display: grid; gap: 14px; padding-left: 22px; }
.readout-requirements small { color: var(--muted); }
.readout-identities { display: grid; gap: 8px; margin: 0; padding-left: 20px; overflow-wrap: anywhere; font: 11px/1.6 Consolas, monospace; }
</style>
