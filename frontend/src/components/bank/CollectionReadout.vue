<script setup lang="ts">
import { computed } from 'vue'
import { useQueries } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import { collectionKindLabels, collectionReferenceStateLabels } from '../../api/collections'
import type { CollectionInput, CollectionKind, CollectionQuestionReference, CollectionQuestionVersion } from '../../api/collections'
import { useWorkspaceStore } from '../../state/workspace'

const props = withDefaults(defineProps<{ kind: CollectionKind; content: CollectionInput; references?: CollectionQuestionReference[] }>(), { references: () => [] })
const router = useRouter()
const store = useWorkspaceStore()
const referencesByStep = computed(() => new Map(props.references.map(reference => [reference.stepId, reference])))
// 历史集合只持有确切版本 ID；标题从当时题版本读取，不能套用当前题干或当前标题。
const labels = useQueries({ queries: computed(() => props.content.steps.map(step => ({
  queryKey: ['bank-collections', store.sessionEpoch, 'question-version-label', step.questionId, step.questionVersionId],
  enabled: store.sessionActive && !referencesByStep.value.has(step.id),
  queryFn: () => read<CollectionQuestionVersion>(`/bank/questions/${step.questionId}/versions/${step.questionVersionId}`),
}))) })
function reference(index: number) { return referencesByStep.value.get(props.content.steps[index]?.id ?? '') }
function title(index: number): string { return reference(index)?.title ?? labels.value[index]?.data?.content.title ?? `题目 ${props.content.steps[index]?.questionId.slice(0, 8)}` }
function version(index: number): string { const number = reference(index)?.number ?? labels.value[index]?.data?.number; return number ? `v${number}` : `版本 ${props.content.steps[index]?.questionVersionId.slice(0, 8)}` }
function dependencyLabel(id: string): string { const index = props.content.steps.findIndex(step => step.id === id); return index < 0 ? '前置步骤缺失' : `步骤 ${index + 1} · ${title(index)}` }
function open(index: number) { const step = props.content.steps[index]; if (step) void router.push({ name: 'bank', query: { item: step.questionId, version: step.questionVersionId } }) }
</script>

<template>
  <section class="collection-readout" :aria-label="`${collectionKindLabels[kind]}保存内容`">
    <header><h3>{{ content.title }}</h3><p v-if="content.purpose" class="collection-purpose">{{ content.purpose }}</p><p v-else class="field-help">未填写整体用途。</p></header>
    <p v-if="!content.steps.length" class="field-help">这个版本还没有步骤。</p>
    <ol class="collection-readout-steps">
      <li v-for="(step, index) in content.steps" :key="step.id">
        <div class="step-heading"><span class="step-number">{{ index + 1 }}</span><div><button class="text-link" @click="open(index)">{{ title(index) }}</button><p class="step-meta">{{ version(index) }}<span v-if="reference(index)" :class="{ warning: reference(index)?.state !== 'CURRENT' }"> · {{ collectionReferenceStateLabels[reference(index)!.state] }}</span></p></div></div>
        <div v-if="labels[index]?.error" class="notice error" role="alert"><p>题目版本标题暂未读取：{{ errorText(labels[index]?.error) }}</p><NButton size="small" text @click="labels[index]?.refetch()">重试读取</NButton></div>
        <p v-if="labels[index]?.isFetching && !reference(index)" class="field-help" role="status">正在读取确切题目版本…</p>
        <dl><template v-if="step.purpose"><dt>练习目的</dt><dd>{{ step.purpose }}</dd></template><template v-if="kind === 'ROUTE'"><dt>前置步骤</dt><dd>{{ step.dependsOn.length ? step.dependsOn.map(dependencyLabel).join('；') : '无前置，可作为起点' }}</dd></template><template v-if="step.checkpoint"><dt>学习检查点</dt><dd>{{ step.checkpoint }}</dd></template></dl>
      </li>
    </ol>
    <p class="field-help">{{ kind === 'ROUTE' ? '顺序用于阅读，前置关系由每一步单独声明。检查点记录学习目标。' : '题单保存题目顺序与练习目的。' }}引用固定到当时题目版本；查看题目将离开当前页面。</p>
  </section>
</template>

<style scoped>
.collection-readout { display: grid; gap: 20px; min-width: 0; }
.collection-readout h3 { font-size: 20px; overflow-wrap: anywhere; }
.collection-purpose { white-space: pre-wrap; overflow-wrap: anywhere; max-width: 75ch; margin-top: 10px; }
.collection-readout-steps { list-style: none; padding: 0; margin: 0; }
.collection-readout-steps > li { padding: 18px 0; border-top: 1px solid var(--line); }
.step-heading { display: flex; gap: 13px; align-items: baseline; }
.step-heading > div { min-width: 0; }
.step-heading button { text-align: left; overflow-wrap: anywhere; }
.step-number { color: var(--muted); font-size: 13px; width: 20px; flex: none; }
.step-meta { color: var(--muted); font-size: 12px; margin-top: 5px; }
.step-meta .warning { color: var(--danger); }
dl { display: grid; grid-template-columns: 88px minmax(0, 1fr); gap: 8px 12px; margin: 14px 0 0 33px; font-size: 13px; }
dt { color: var(--muted); }
dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; max-width: 75ch; }
@media (max-width: 700px) { dl { grid-template-columns: 1fr; gap: 5px; } dd { margin-bottom: 7px; } }
</style>
