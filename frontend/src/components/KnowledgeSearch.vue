<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { read, errorText } from '../api/client'
import type { Topic } from '../api/client'
import type { SearchResponse, SearchStatus, OpenKnowledge } from '../api/knowledge'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'
import { useCommand } from '../composables/useCommand'
const props = defineProps<{ topics: Topic[] }>()
const emit = defineEmits<{ open: [target: OpenKnowledge] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const input = ref('')
const kind = ref<string | null>(null)
const topicId = ref<string | null>(null)
const status = ref('ACTIVE')
const tag = ref('')
const source = ref('ALL')
const sources = [{ label: '全部来源', value: 'ALL' }, { label: '手工记录', value: 'USER' }, { label: '有 AI 采用记录', value: 'AI_ADOPTED' }, { label: '文本导入', value: 'TEXT_IMPORT' }, { label: '知识包导入', value: 'PACKAGE_IMPORT' }]
const submitted = ref<{ q: string; kind: string | null; topicId: string | null; status: string; tag: string; source: string }>()
const offset = ref(0)
const previousOffsets = ref<number[]>([])
const message = ref('')
const labels: Record<string,string> = { READY: '索引可用', BUILDING: '正在重建', DEGRADED: '索引降级', DISABLED: '全文检索未启用' }
const kinds = [{ label: '概念', value: 'CONCEPT' }, { label: '笔记', value: 'NOTE' }]
const states = [{ label: '活跃内容', value: 'ACTIVE' }, { label: '已归档', value: 'ARCHIVED' }]
const topics = computed(() => props.topics.map(topic => ({ label: topic.name, value: topic.id })))
const statusQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'search-status']), enabled: computed(() => store.sessionActive), queryFn: () => read<SearchStatus>('/knowledge/search/status') })
const results = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'search', submitted.value, offset.value]), enabled: computed(() => store.sessionActive && Boolean(submitted.value?.q)), queryFn: () => {
  const filter = submitted.value!
  const params = new URLSearchParams({ q: filter.q, status: filter.status, source: filter.source, limit: '20', offset: String(offset.value) })
  if (filter.kind) params.set('kind', filter.kind)
  if (filter.topicId) params.set('topicId', filter.topicId)
  if (filter.tag) params.set('tag', filter.tag)
  return read<SearchResponse>(`/knowledge/search?${params}`)
} })
watch(writer.locked, locked => store.changes.set('search', { dirty: false, locked }), { immediate: true })
onUnmounted(() => store.changes.delete('search'))
function search() { if (!input.value.trim()) return; offset.value = 0; previousOffsets.value = []; submitted.value = { q: input.value.trim(), kind: kind.value, topicId: topicId.value, status: status.value, tag: tag.value.trim(), source: source.value } }
function next() {
  const count = results.data.value?.items.length ?? 0
  if (!count || offset.value + count > 1000) return
  previousOffsets.value.push(offset.value)
  offset.value += count
}
function rebuild() {
  if (!window.confirm('登记一次全文索引重建？已保存的知识正文不会改变，重建期间可能仍展示旧索引中通过校验的结果。')) return
  void writer.run<{ accepted: boolean; state: string }>('/knowledge/search/rebuild', 'POST', {}, result => {
    message.value = result.accepted ? '重建任务已登记，请稍后刷新状态。' : `当前状态：${labels[result.state] ?? result.state}`
    void queryClient.invalidateQueries({ queryKey: ['knowledge', store.sessionEpoch, 'search-status'] })
  })
}
</script>
<template>
  <section class="search-workspace"><div class="search-heading"><h2>搜索已发布知识</h2><span class="status-pill">{{ statusQuery.data.value ? labels[statusQuery.data.value.state] : '正在读取索引状态' }}</span></div><p class="field-help">搜索正文、标题、别名、标签与代码标识符；未发布草稿不进入全文检索。</p>
    <form class="search-form" @submit.prevent="search"><NInput v-model:value="input" :maxlength="100" placeholder="例如：缓存一致性、ConcurrentHashMap" aria-label="全文检索关键词" /><div class="search-filters"><NSelect v-model:value="kind" :options="kinds" clearable placeholder="全部类型" aria-label="知识类型" /><NSelect v-model:value="topicId" :options="topics" clearable placeholder="全部主题" aria-label="主题筛选" /><NSelect v-model:value="status" :options="states" aria-label="知识状态" /><NInput v-model:value="tag" :maxlength="40" placeholder="按标签筛选" aria-label="标签筛选" /></div><NButton type="primary" attr-type="submit" :disabled="!input.trim()" :loading="results.isFetching.value">搜索</NButton></form>
    <label class="field-help">来源记录 <NSelect v-model:value="source" :options="sources" aria-label="知识来源筛选" /></label>
    <p class="field-help">来源依据实际采用／导入登记分类；有 AI 采用记录不表示整篇正文由 AI 创作，之后的独立修改仍保留原版本。</p>
    <p v-if="results.error.value || statusQuery.error.value" class="notice error" role="alert">{{ errorText(results.error.value ?? statusQuery.error.value) }}</p><p v-if="results.data.value && results.data.value.syncStatus !== 'READY'" class="notice">{{ labels[results.data.value.syncStatus] }}。结果可能尚未包含刚刚发布或修改整理信息的内容。</p>
    <p v-if="results.data.value?.scanLimited" class="notice">本次检索已达到安全扫描上限，当前结果可能不完整。请缩小检索范围；如果索引正在同步，也可稍后刷新。本页结束不代表没有其他匹配内容。</p>
    <p v-if="submitted && !results.isFetching.value && results.data.value?.items.length === 0" class="empty-inline">没有找到匹配的已发布内容。可以调整关键词或筛选条件；索引不同步时可稍后刷新。</p>
    <article v-for="hit in results.data.value?.items" :key="hit.id" class="search-result"><button class="text-link" @click="emit('open', { id: hit.id })">{{ hit.title }}</button><small>{{ hit.kind === 'CONCEPT' ? '概念' : '笔记' }} · v{{ hit.versionNumber }} · {{ hit.status === 'ARCHIVED' ? '已归档' : '活跃' }}</small><p>{{ hit.excerpt }}</p><div class="row-actions"><span v-for="label in hit.tags" :key="label" class="status-pill">{{ label }}</span><NButton text size="small" @click="emit('open', { id: hit.id, versionId: hit.versionId })">查看本次命中的原版本</NButton></div></article>
    <div v-if="submitted" class="row-actions"><NButton size="small" :disabled="!previousOffsets.length" @click="offset = previousOffsets.pop() ?? 0">上一页</NButton><NButton size="small" :disabled="!results.data.value?.hasMore || !results.data.value.items.length || offset + results.data.value.items.length > 1000" @click="next">下一页</NButton><span class="field-help">第 {{ previousOffsets.length + 1 }} 页 · 不显示未经验证的命中总数</span></div>
    <details class="search-maintenance"><summary>索引同步与恢复</summary><p class="field-help">{{ statusQuery.data.value?.pendingEvents ?? '—' }} 个待同步事件；索引代次 {{ statusQuery.data.value?.generation ?? '尚未建立' }}。{{ statusQuery.data.value?.lastErrorCode ? `最近状态码：${statusQuery.data.value.lastErrorCode}` : '' }}</p><div class="row-actions"><NButton size="small" @click="statusQuery.refetch(); submitted && results.refetch()">刷新检索状态</NButton><NButton size="small" :disabled="writer.locked.value || statusQuery.data.value?.state === 'DISABLED'" @click="rebuild">显式重建索引</NButton></div><p v-if="statusQuery.data.value?.state === 'DISABLED'" class="field-help">服务器尚未启用索引任务，仍可使用标题筛选与知识编辑。</p></details>
    <p v-if="writer.error.value" class="notice error">{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" @click="writer.retry">用原请求重试</NButton><p v-if="message" class="success-text" role="status">{{ message }}</p>
  </section>
</template>
