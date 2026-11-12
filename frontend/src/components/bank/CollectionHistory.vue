<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { CollectionKind, CollectionVersion, CollectionVersionPage } from '../../api/collections'
import { useWorkspaceStore } from '../../state/workspace'
import CollectionReadout from './CollectionReadout.vue'

const props = defineProps<{ id: string; kind: CollectionKind; currentVersion: CollectionVersion }>()
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const before = ref<number>()
const cursors = ref<(number | undefined)[]>([])
const selectedId = ref(props.currentVersion.id)
const comparisonId = ref<string>()
const list = useQuery({
  queryKey: computed(() => ['bank-collections', store.sessionEpoch, 'versions', props.id, before.value]),
  enabled: computed(() => store.sessionActive),
  queryFn: () => read<CollectionVersionPage>(`/bank/collections/${props.id}/versions?limit=20${before.value ? `&before=${before.value}` : ''}`),
})
const selected = useQuery({
  queryKey: computed(() => ['bank-collections', store.sessionEpoch, 'version', props.id, selectedId.value]),
  enabled: computed(() => store.sessionActive && Boolean(selectedId.value)),
  queryFn: () => read<CollectionVersion>(`/bank/collections/${props.id}/versions/${selectedId.value}`),
})
const comparison = useQuery({
  queryKey: computed(() => ['bank-collections', store.sessionEpoch, 'version', props.id, comparisonId.value]),
  enabled: computed(() => store.sessionActive && Boolean(comparisonId.value)),
  queryFn: () => read<CollectionVersion>(`/bank/collections/${props.id}/versions/${comparisonId.value}`),
})
const changed = computed(() => {
  if (!selected.data.value || !comparison.data.value) return []
  const a = selected.data.value.content
  const b = comparison.data.value.content
  return [a.title !== b.title ? '标题' : '', a.purpose !== b.purpose ? '整体用途' : '', JSON.stringify(a.steps) !== JSON.stringify(b.steps) ? '步骤内容、顺序或前置关系' : ''].filter(Boolean)
})
watch([() => route.query.version, () => props.currentVersion.id], ([versionId, currentVersionId]) => {
  selectedId.value = typeof versionId === 'string' ? versionId : currentVersionId
}, { immediate: true })
function choose(id: string) { void router.push({ name: 'collections', query: { ...route.query, item: props.id, version: id } }) }
function older() { if (list.data.value?.nextCursor) { cursors.value.push(before.value); before.value = list.data.value.nextCursor } }
function retryVersions() { if (selected.error.value) void selected.refetch(); if (comparison.error.value) void comparison.refetch() }
</script>

<template>
  <section class="collection-history" aria-label="集合历史版本">
    <p class="field-help">历史显示当时保存的步骤和题目版本。切换历史保留当前编辑输入。</p>
    <p v-if="list.isPending.value" class="field-help" role="status">正在读取历史版本…</p>
    <div v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重试</NButton></div>
    <div class="history-list"><div v-for="version in list.data.value?.items" :key="version.id" class="version-row"><button class="text-link" :aria-current="version.id === selectedId ? 'true' : undefined" @click="choose(version.id)">v{{ version.number }} · {{ version.title }}<small>{{ new Date(version.createdAt).toLocaleString('zh-CN') }}</small></button><NButton text size="small" :disabled="version.id === selectedId" @click="comparisonId = version.id">设为对照</NButton></div></div>
    <div class="row-actions"><NButton size="small" :disabled="!cursors.length" @click="before = cursors.pop()">较新</NButton><NButton size="small" :disabled="!list.data.value?.nextCursor" @click="older">更早</NButton><NButton v-if="comparisonId" size="small" @click="comparisonId = undefined">关闭对照</NButton></div>
    <div v-if="selected.error.value || comparison.error.value" class="notice error" role="alert"><p>{{ errorText(selected.error.value ?? comparison.error.value) }}</p><NButton size="small" text @click="retryVersions">重试读取版本</NButton></div>
    <p v-if="selected.isFetching.value || comparison.isFetching.value" class="field-help" role="status">正在读取确切版本…</p>
    <p v-if="selected.data.value && comparison.data.value" class="difference">{{ changed.length ? `变化：${changed.join('、')}。` : '两个版本的保存内容相同。' }}<span>比较内容变化，不据此判断学习完成度。</span></p>
    <div class="version-columns" :class="{ paired: comparisonId }"><article v-if="selected.data.value"><h3 class="version-label">所选版本 v{{ selected.data.value.number }}</h3><CollectionReadout :kind="kind" :content="selected.data.value.content" /></article><article v-if="comparisonId && comparison.data.value"><h3 class="version-label">对照版本 v{{ comparison.data.value.number }}</h3><CollectionReadout :kind="kind" :content="comparison.data.value.content" /></article></div>
  </section>
</template>

<style scoped>
.collection-history { display: grid; gap: 16px; }
.history-list { display: grid; }
.version-row { display: flex; justify-content: space-between; gap: 16px; padding: 12px 0; border-bottom: 1px solid var(--line); }
.version-row > button:first-child { text-align: left; min-width: 0; overflow-wrap: anywhere; }
.version-row small { display: block; font-size: 11px; color: var(--muted); font-weight: 400; margin-top: 4px; }
.version-columns { display: grid; gap: 25px; }
.version-columns.paired { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.version-columns article { min-width: 0; }
.version-label { margin-bottom: 18px; color: var(--brand); font-size: 13px; }
.difference { padding: 12px; background: var(--surface-soft); border-radius: 6px; font-size: 13px; }
.difference span { display: block; color: var(--muted); font-size: 12px; margin-top: 5px; }
@media (max-width: 850px) { .version-columns.paired { grid-template-columns: 1fr; } }
</style>
