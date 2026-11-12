<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { PreparationKind, PreparationVersion, PreparationVersionPage } from '../../api/preparation'
import { useWorkspaceStore } from '../../state/workspace'
import AiExpressionOrigins from '../ai/AiExpressionOrigins.vue'
import AiJdRequirementsOrigins from '../ai/AiJdRequirementsOrigins.vue'
import AiFactOrigins from './AiFactOrigins.vue'
import PreparationReadout from './PreparationReadout.vue'
const props = defineProps<{ id: string; kind: PreparationKind; currentVersion: PreparationVersion; projectId?: string }>()
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const before = ref<number>()
const cursors = ref<(number | undefined)[]>([])
const selectedId = ref(props.currentVersion.id)
const comparisonId = ref<string>()
const list = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'versions', props.id, before.value]), enabled: computed(() => store.sessionActive), queryFn: () => read<PreparationVersionPage>(`/prepare/items/${props.id}/versions?limit=20${before.value ? `&before=${before.value}` : ''}`) })
const selected = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'version', props.id, selectedId.value]), enabled: computed(() => store.sessionActive && Boolean(selectedId.value)), queryFn: () => read<PreparationVersion>(`/prepare/items/${props.id}/versions/${selectedId.value}`) })
const comparison = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'version', props.id, comparisonId.value]), enabled: computed(() => store.sessionActive && Boolean(comparisonId.value)), queryFn: () => read<PreparationVersion>(`/prepare/items/${props.id}/versions/${comparisonId.value}`) })
const changedFields = computed(() => {
  if (!selected.data.value || !comparison.data.value) return []
  const first = new Map(Object.entries(selected.data.value.content))
  const second = new Map(Object.entries(comparison.data.value.content))
  return [...new Set([...first.keys(), ...second.keys()])].filter(key => JSON.stringify(first.get(key)) !== JSON.stringify(second.get(key)))
})
watch([() => route.query.version, () => props.currentVersion.id], ([versionId, currentVersionId]) => {
  selectedId.value = typeof versionId === 'string' ? versionId : currentVersionId
}, { immediate: true })
function choose(id: string) { void router.push({ name: 'prepare', query: { ...route.query, item: props.id, version: id } }) }
function older() { if (list.data.value?.nextCursor) { cursors.value.push(before.value); before.value = list.data.value.nextCursor } }
</script>

<template>
  <section class="prepare-history">
    <p class="field-help">历史只读，显示当时保存的内容和确切引用版本。查看历史不会修改编辑输入或当前核实状态。</p>
    <p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重试</NButton></p><p v-if="list.isPending.value" role="status">正在读取版本…</p>
    <div v-for="version in list.data.value?.items" :key="version.id" class="version-row"><button class="text-link" @click="choose(version.id)">v{{ version.number }} · {{ version.title }}<small>{{ new Date(version.createdAt).toLocaleString('zh-CN') }}</small></button><NButton size="small" text :disabled="version.id === selectedId" @click="comparisonId = version.id">设为对照</NButton></div>
    <div class="row-actions"><NButton size="small" :disabled="!cursors.length" @click="before = cursors.pop()">较新</NButton><NButton size="small" :disabled="!list.data.value?.nextCursor" @click="older">更早</NButton><NButton v-if="comparisonId" size="small" @click="comparisonId = undefined">关闭对照</NButton></div>
    <p v-if="selected.error.value || comparison.error.value" class="notice error" role="alert">{{ errorText(selected.error.value ?? comparison.error.value) }}</p><p v-if="selected.isFetching.value || comparison.isFetching.value" class="field-help" role="status">正在读取确切版本…</p>
    <p v-if="comparison.data.value && selected.data.value" class="prepare-difference">{{ changedFields.length ? `变化字段：${changedFields.join('、')}` : '两个版本的内容相同。' }}<span>只比较保存内容，不据此判定技术正确性或核实状态。</span></p>
    <div class="prepare-version-columns" :class="{ paired: comparisonId }"><article v-if="selected.data.value"><p class="section-label">所选版本 v{{ selected.data.value.number }}</p><PreparationReadout :kind="kind" :content="selected.data.value.content" /><AiExpressionOrigins v-if="kind === 'EXPRESSION'" :expression-id="id" :version-id="selected.data.value.id" :version-number="selected.data.value.number" /><AiJdRequirementsOrigins v-if="kind === 'TARGET' && selected.data.value.id === selectedId" :target-id="id" :version-id="selected.data.value.id" :version-number="selected.data.value.number" :disabled="selected.isFetching.value || !!selected.error.value" /><AiFactOrigins v-if="kind === 'FACT' && projectId && selected.data.value.id === selectedId" :fact-id="id" :project-id="projectId" :version-id="selected.data.value.id" :version-number="selected.data.value.number" :disabled="selected.isFetching.value || !!selected.error.value" /></article><article v-if="comparison.data.value && comparisonId"><p class="section-label">对照版本 v{{ comparison.data.value.number }}</p><PreparationReadout :kind="kind" :content="comparison.data.value.content" /><AiExpressionOrigins v-if="kind === 'EXPRESSION'" :expression-id="id" :version-id="comparison.data.value.id" :version-number="comparison.data.value.number" /><AiJdRequirementsOrigins v-if="kind === 'TARGET' && comparison.data.value.id === comparisonId" :target-id="id" :version-id="comparison.data.value.id" :version-number="comparison.data.value.number" :disabled="comparison.isFetching.value || !!comparison.error.value" /><AiFactOrigins v-if="kind === 'FACT' && projectId && comparison.data.value.id === comparisonId" :fact-id="id" :project-id="projectId" :version-id="comparison.data.value.id" :version-number="comparison.data.value.number" :disabled="comparison.isFetching.value || !!comparison.error.value" /></article></div>
  </section>
</template>

<style scoped>
.prepare-history { display: grid; gap: 12px; }
.version-row > button { min-width: 0; text-align: left; overflow-wrap: anywhere; }
.version-row small { display: block; font-weight: 400; font-size: 11px; margin-top: 3px; }
.prepare-version-columns { display: grid; gap: 24px; }
.prepare-version-columns.paired { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.prepare-version-columns article { min-width: 0; }
.prepare-version-columns .section-label { margin-bottom: 13px; letter-spacing: 0; }
.prepare-difference { padding: 12px; background: var(--surface-soft); border-radius: 6px; font-size: 12px; }
.prepare-difference span { display: block; margin-top: 6px; color: var(--muted); }
@media (max-width: 1050px) { .prepare-version-columns.paired { grid-template-columns: 1fr; } }
</style>
