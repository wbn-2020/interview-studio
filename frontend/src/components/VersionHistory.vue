<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { read, errorText } from '../api/client'
import type { Schema, Version } from '../api/client'
import { useWorkspaceStore } from '../state/workspace'
import MarkdownPreview from './MarkdownPreview.vue'
import AiKnowledgeOrigins from './AiKnowledgeOrigins.vue'
import KnowledgeReferences from './KnowledgeReferences.vue'
const props = defineProps<{ id: string; published: Version }>()
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const cursor = ref<number>()
const selectedId = ref<string>(props.published.id)
const comparisonId = ref<string>()
const sourceMode = ref(false)
const sourcePage = ref(0)
const comparisonPage = ref(0)
const referencesOpen = ref(false)
const requestedVersion = computed(() => typeof route.query.version === 'string' ? route.query.version : undefined)
const lineStart = computed(() => Math.max(1, Number(route.query.lineStart) || 1))
const lineEnd = computed(() => Math.max(lineStart.value, Number(route.query.lineEnd) || lineStart.value))
const list = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'versions', props.id, cursor.value]), enabled: computed(() => store.sessionActive), queryFn: () => read<Schema['VersionPage']>(`/knowledge/items/${props.id}/versions?limit=20${cursor.value ? `&before=${cursor.value}` : ''}`) })
const selected = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'version', props.id, selectedId.value]), enabled: computed(() => store.sessionActive && Boolean(selectedId.value)), queryFn: () => read<Version>(`/knowledge/items/${props.id}/versions/${selectedId.value}`) })
const comparison = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'version', props.id, comparisonId.value]), enabled: computed(() => store.sessionActive && Boolean(comparisonId.value)), queryFn: () => read<Version>(`/knowledge/items/${props.id}/versions/${comparisonId.value}`) })
const lines = computed(() => (selected.data.value?.body ?? '').split(/\r\n|\r|\n/))
const otherLines = computed(() => (comparison.data.value?.body ?? '').split(/\r\n|\r|\n/))
const displayLines = computed(() => lines.value.slice(sourcePage.value * 100, sourcePage.value * 100 + 100))
const comparisonLines = computed(() => otherLines.value.slice(comparisonPage.value * 100, comparisonPage.value * 100 + 100))
const difference = computed(() => {
  if (!selected.data.value || !comparison.data.value || !comparisonId.value) return undefined
  const left = lines.value
  const right = otherLines.value
  let prefix = 0
  while (prefix < left.length && prefix < right.length && left[prefix] === right[prefix]) prefix++
  let suffix = 0
  // 仅线性寻找共同首尾，避免长文最小差异算法的平方级开销。
  while (suffix < left.length - prefix && suffix < right.length - prefix && left[left.length - suffix - 1] === right[right.length - suffix - 1]) suffix++
  return { prefix, leftCount: left.length - prefix - suffix, rightCount: right.length - prefix - suffix, titleChanged: selected.data.value.title !== comparison.data.value.title }
})
watch([requestedVersion, lineStart], ([version]) => { if (version) { selectedId.value = version; sourceMode.value = true; sourcePage.value = Math.floor((lineStart.value - 1) / 100) } }, { immediate: true })
watch(selectedId, () => { referencesOpen.value = false })
function choose(versionId: string) {
  selectedId.value = versionId; sourcePage.value = 0
  void router.push({ name: 'knowledge', query: { ...route.query, version: versionId, lineStart: undefined, lineEnd: undefined } })
}
function compare(versionId: string) { comparisonId.value = versionId; sourceMode.value = true; comparisonPage.value = 0 }
function locateDifference() {
  if (!difference.value) return
  sourceMode.value = true
  sourcePage.value = Math.floor(Math.min(difference.value.prefix, Math.max(0, lines.value.length - 1)) / 100)
  comparisonPage.value = Math.floor(Math.min(difference.value.prefix, Math.max(0, otherLines.value.length - 1)) / 100)
}
function isChanged(index: number, side: 'left' | 'right') {
  const change = difference.value
  if (!change) return false
  const count = side === 'left' ? change.leftCount : change.rightCount
  return index >= change.prefix && index < change.prefix + count
}
</script>
<template>
  <section class="history-panel"><p class="field-help">只读版本保持当时原文。对照以两份原文并排呈现，不自动判断内容优劣。</p><p v-if="list.error.value" class="notice error">{{ errorText(list.error.value) }}</p>
    <div v-for="version in list.data.value?.items" :key="version.id" class="version-row"><button class="text-link" @click="choose(version.id)">v{{ version.number }} · {{ version.title }}</button><NButton text size="small" :disabled="version.id === selectedId" @click="compare(version.id)">设为对照</NButton></div>
    <div class="row-actions"><NButton v-if="cursor" size="small" @click="cursor = undefined">最新版本</NButton><NButton v-if="list.data.value?.nextCursor" size="small" @click="cursor = Number(list.data.value.nextCursor)">更早版本</NButton></div>
    <p v-if="selected.error.value || comparison.error.value" class="notice error">{{ errorText(selected.error.value ?? comparison.error.value) }}</p><p v-if="selected.isFetching.value" role="status">正在读取原版本…</p>
    <div v-if="selected.data.value" class="row-actions"><strong>v{{ selected.data.value.number }} · {{ selected.data.value.title }}</strong><NButton size="small" @click="sourceMode = !sourceMode">{{ sourceMode ? '阅读排版' : '原文行号' }}</NButton><NButton v-if="comparisonId" size="small" @click="comparisonId = undefined">关闭对照</NButton></div>
    <div v-if="difference" class="difference-summary"><h3>变化区段</h3><p v-if="difference.titleChanged">标题有变化：「{{ comparison.data.value?.title }}」 → 「{{ selected.data.value?.title }}」</p><p v-else class="field-help">标题相同。</p><template v-if="difference.leftCount || difference.rightCount"><p>共同前缀 {{ difference.prefix }} 行之后，当前所选版本含 {{ difference.leftCount }} 行变化区段，对照版本含 {{ difference.rightCount }} 行变化区段。</p><p class="field-help">按共同首尾定位变化范围，中间可能仍含相同行；这不是逐行最小差异。纯插入或删除时一侧区段为 0 行。</p><NButton size="small" @click="locateDifference">跳到变化区段</NButton></template><p v-else>正文完全相同。</p></div>
    <div :class="['version-comparison', { paired: comparisonId }]">
      <article><template v-if="sourceMode"><p v-if="requestedVersion && route.query.lineStart" class="field-help">引用定位：源版本固定，第 {{ lineStart }}–{{ lineEnd }} 行。</p><div class="source-lines"><div v-for="(line, index) in displayLines" :key="sourcePage * 100 + index" :class="{ changed: isChanged(sourcePage * 100 + index, 'left'), highlighted: requestedVersion === selectedId && route.query.lineStart && sourcePage * 100 + index + 1 >= lineStart && sourcePage * 100 + index + 1 <= lineEnd }"><span>{{ sourcePage * 100 + index + 1 }}</span><code>{{ line || ' ' }}</code></div></div><div class="row-actions"><NButton size="small" :disabled="!sourcePage" @click="sourcePage--">前 100 行</NButton><NButton size="small" :disabled="(sourcePage + 1) * 100 >= lines.length" @click="sourcePage++">后 100 行</NButton><small>{{ lines.length }} 行</small></div></template><MarkdownPreview v-else-if="selected.data.value" :value="selected.data.value.body" /></article>
      <article v-if="comparison.data.value && comparisonId"><h3>对照 · v{{ comparison.data.value.number }} · {{ comparison.data.value.title }}</h3><div class="source-lines"><div v-for="(line,index) in comparisonLines" :key="comparisonPage * 100 + index" :class="{ changed: isChanged(comparisonPage * 100 + index, 'right') }"><span>{{ comparisonPage * 100 + index + 1 }}</span><code>{{ line || ' ' }}</code></div></div><div class="row-actions"><NButton size="small" :disabled="!comparisonPage" @click="comparisonPage--">前 100 行</NButton><NButton size="small" :disabled="(comparisonPage + 1) * 100 >= otherLines.length" @click="comparisonPage++">后 100 行</NButton><small>{{ otherLines.length }} 行</small></div></article>
    </div>
    <AiKnowledgeOrigins v-if="selected.data.value" :item-id="id" :version-id="selected.data.value.id" :version-number="selected.data.value.number" />
    <div v-if="selected.data.value?.id === selectedId" class="row-actions"><NButton size="small" :aria-expanded="referencesOpen" @click="referencesOpen = !referencesOpen">{{ referencesOpen ? '收起此版本引用影响' : '查看此版本引用影响' }}</NButton></div>
    <KnowledgeReferences v-if="referencesOpen && selected.data.value?.id === selectedId" :item-id="id" :version-id="selected.data.value.id" :version-number="selected.data.value.number" />
  </section>
</template>
