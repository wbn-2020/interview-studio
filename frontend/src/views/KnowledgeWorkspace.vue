<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NModal, NSelect } from 'naive-ui'
import { errorText, read } from '../api/client'
import type { GraphResponse, ItemDetail, Schema, Topic } from '../api/client'
import type { OpenKnowledge } from '../api/knowledge'
import { queryClient } from '../api/queryClient'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
const KnowledgeGraph = defineAsyncComponent(() => import('../components/KnowledgeGraph.vue'))
const ItemEditor = defineAsyncComponent(() => import('../components/ItemEditor.vue'))
const MarkdownEditor = defineAsyncComponent(() => import('../components/MarkdownEditor.vue'))
const KnowledgeSearch = defineAsyncComponent(() => import('../components/KnowledgeSearch.vue'))

const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const trainingAccess = useTrainingAccessStore()
const mode = computed<'graph' | 'list' | 'search'>(() => route.query.view === 'list' ? 'list' : route.query.view === 'search' ? 'search' : 'graph')
const filter = ref('ALL')
const collection = ref('ALL')
const search = ref('')
const query = ref('')
const cursor = ref<string>()
const previousCursors = ref<(string | undefined)[]>([])
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const selectedKind = ref<'NOTE' | 'CONCEPT'>()
const selectedTopic = ref<string | null>(null)
const graphSnapshot = ref<GraphResponse>()
const createOpen = ref(false)
const createKind = ref<'NOTE' | 'CONCEPT'>('CONCEPT')
const createTitle = ref('')
const createBody = ref('')
const createTopics = ref<string[]>([])
const createdItem = ref<ItemDetail>()
const createWriter = useCommand()
const topicWriter = useCommand()
const topicName = ref('')
const topicColor = ref('#81c9bd')
const topicMessage = ref('')
const filters = [{ label: '全部知识', value: 'ALL' }, { label: '知识概念', value: 'CONCEPT' }, { label: '独立笔记', value: 'NOTE' }, { label: '已归档', value: 'ARCHIVED' }]
const collections = [{ label: '全部资料', value: 'ALL' }, { label: '我的收藏', value: 'FAVORITES' }, { label: '最近访问', value: 'RECENT' }, { label: '尚未分类', value: 'UNCATEGORIZED' }]

const itemsQuery = useQuery({
  queryKey: computed(() => ['knowledge', store.sessionEpoch, 'items', collection.value, filter.value, query.value, cursor.value]),
  enabled: computed(() => store.sessionActive),
  queryFn: () => {
    const params = new URLSearchParams({ collection: collection.value, status: filter.value === 'ARCHIVED' ? 'ARCHIVED' : 'ACTIVE', limit: '20', q: query.value })
    if (filter.value === 'CONCEPT' || filter.value === 'NOTE') params.set('kind', filter.value)
    if (cursor.value) params.set('cursor', cursor.value)
    return read<Schema['LibraryPage']>(`/knowledge/library?${params}`)
  },
})
const graphQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'graph']), enabled: computed(() => store.sessionActive), queryFn: () => read<GraphResponse>('/knowledge/graph') })
const topicsQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'topics']), enabled: computed(() => store.sessionActive), queryFn: () => read<Schema['TopicList']>('/knowledge/topics') })
const topics = computed(() => topicsQuery.data.value?.items ?? graphSnapshot.value?.topics ?? [])
const topicOptions = computed(() => topics.value.map(topic => ({ label: topic.name, value: topic.id })))
watch(graphQuery.data, value => { if (value) graphSnapshot.value = value })
watch(selectedId, () => { selectedKind.value = undefined })
watch([filter, collection], () => { cursor.value = undefined; previousCursors.value = [] })
watch([createOpen, createTitle, createBody, createTopics, createWriter.locked], () => store.changes.set('create', {
  dirty: createOpen.value && Boolean(createTitle.value || createBody.value || createTopics.value.length), locked: createWriter.locked.value,
}), { immediate: true, deep: true })
watch(topicWriter.locked, locked => store.changes.set('topic', { dirty: false, locked }), { immediate: true })
onUnmounted(() => { store.changes.delete('create'); store.changes.delete('topic') })

function refresh() { void queryClient.invalidateQueries({ queryKey: ['knowledge'] }) }
function submitSearch() { query.value = search.value.trim(); cursor.value = undefined; previousCursors.value = [] }
function nextPage() {
  if (!itemsQuery.data.value?.nextCursor) return
  previousCursors.value.push(cursor.value)
  cursor.value = itemsQuery.data.value.nextCursor
}
function previousPage() { cursor.value = previousCursors.value.pop() }
function openItem(id: string) { void router.push({ name: 'knowledge', query: { ...route.query, item: id, version: undefined, lineStart: undefined, lineEnd: undefined } }) }
function openTarget(target: OpenKnowledge) { void router.push({ name: 'knowledge', query: { ...route.query, item: target.id, version: target.versionId, lineStart: target.lineStart, lineEnd: target.lineEnd } }) }
function closeItem() { void router.push({ name: 'knowledge', query: { ...route.query, item: undefined, version: undefined, lineStart: undefined, lineEnd: undefined } }) }
function changeMode(value: 'graph' | 'list' | 'search') {
  if (mode.value === value) return
  void router.push({ name: 'knowledge', query: { ...route.query, view: value } })
}
function openCreate(kind: 'NOTE' | 'CONCEPT') {
  createKind.value = kind
  createOpen.value = true
}
function closeCreate() {
  if (!store.canLeave(['create'])) return
  createOpen.value = false
  createTitle.value = ''
  createBody.value = ''
  createTopics.value = []
  createWriter.error.value = undefined
}
function createItem() {
  if (!createTitle.value.trim() || createBody.value.length > 100000) return
  void createWriter.run<ItemDetail>('/knowledge/items', 'POST', { kind: createKind.value, title: createTitle.value, body: createBody.value, topicIds: createTopics.value }, value => {
    createdItem.value = value
    createOpen.value = false
    createTitle.value = ''
    createBody.value = ''
    createTopics.value = []
    store.changes.delete('create')
    refresh()
    openItem(value.id)
  })
}
function addTopic() {
  if (!topicName.value.trim()) return
  void topicWriter.run<Topic>('/knowledge/topics', 'POST', { name: topicName.value, color: topicColor.value }, value => {
    topicName.value = ''
    topicMessage.value = `已创建主题「${value.name}」`
    refresh()
  })
}
</script>

<template>
  <header class="page-heading"><div><p class="section-label">持续积累，也不断连接</p><h1>知识空间</h1><p class="muted">把概念放进星图，把思考写进笔记。</p></div><div class="row-actions"><RouterLink class="text-link" :to="{ name: 'memory-cards' }">回忆卡</RouterLink><NButton @click="openCreate('NOTE')">写笔记</NButton><NButton type="primary" @click="openCreate('CONCEPT')">＋ 新建概念</NButton></div></header>
  <div class="knowledge-toolbar"><div class="view-switch" role="group" aria-label="知识视图"><button :aria-pressed="mode === 'graph'" :class="{ active: mode === 'graph' }" @click="changeMode('graph')">✧ 星图</button><button :aria-pressed="mode === 'list'" :class="{ active: mode === 'list' }" @click="changeMode('list')">☷ 列表与编辑</button><button :aria-pressed="mode === 'search'" :class="{ active: mode === 'search' }" @click="changeMode('search')">全文搜索</button><RouterLink :to="{ name: 'boards' }">脑图与白板</RouterLink></div><div v-if="mode === 'graph'" class="topic-filter"><label for="graph-topic">星图主题</label><NSelect id="graph-topic" v-model:value="selectedTopic" :options="topicOptions" clearable placeholder="全部主题" /></div><NButton quaternary size="small" :loading="itemsQuery.isFetching.value || graphQuery.isFetching.value" @click="refresh">重新读取</NButton></div>
  <p v-if="createdItem" class="creation-result" role="status">已创建「{{ createdItem.title }}」<button class="text-link" @click="openItem(createdItem.id)">打开</button><button class="icon-button" aria-label="关闭创建提示" @click="createdItem = undefined">×</button></p>
  <div class="workspace-grid" :class="{ 'with-detail': selectedId, 'list-mode': mode === 'list' }">
    <aside class="knowledge-catalog" aria-label="知识列表">
      <NSelect v-model:value="filter" :options="filters" aria-label="筛选知识类型与状态" />
      <NSelect v-model:value="collection" class="library-collection" :options="collections" aria-label="知识收藏与访问分类" />
      <form class="title-search" @submit.prevent="submitSearch"><NInput v-model:value="search" :maxlength="100" placeholder="筛选已发布标题" aria-label="按标题筛选" /><NButton attr-type="submit" aria-label="执行标题筛选">查找</NButton></form><p class="field-help">目录按已发布标题筛选；正文可用全文搜索。</p>
      <div v-if="itemsQuery.isPending.value" class="loading-block" role="status">正在读取知识…</div>
      <div v-else-if="itemsQuery.error.value" class="notice error" role="alert">{{ errorText(itemsQuery.error.value) }}<NButton text @click="itemsQuery.refetch()">重试</NButton></div>
      <div v-else-if="!itemsQuery.data.value?.items.length" class="catalog-empty"><strong>{{ query ? '没有匹配的标题' : filter === 'ARCHIVED' ? '没有已归档的内容' : '知识从你的第一条记录开始' }}</strong><p>{{ query ? '试试更短的关键词，或调整类型筛选。' : '概念用于建立关系；笔记用于完整记录思考。' }}</p></div>
      <div class="knowledge-list"><button v-for="item in itemsQuery.data.value?.items" :key="item.id" :class="['knowledge-row', { selected: selectedId === item.id }]" :aria-current="selectedId === item.id ? 'true' : undefined" @click="openItem(item.id)"><span class="item-kind" aria-hidden="true">{{ item.kind === 'CONCEPT' ? '✧' : '▤' }}</span><span><strong>{{ item.title }}</strong><small>{{ item.kind === 'CONCEPT' ? '概念' : '笔记' }} · v{{ item.versionNumber }}{{ item.status === 'ARCHIVED' ? ' · 已归档' : '' }}</small></span></button></div>
      <div v-if="previousCursors.length || itemsQuery.data.value?.nextCursor" class="pagination"><NButton size="small" :disabled="!previousCursors.length" @click="previousPage">上一页</NButton><NButton size="small" :disabled="!itemsQuery.data.value?.nextCursor" @click="nextPage">下一页</NButton></div>
      <details class="topic-create"><summary>管理主题</summary><p class="field-help">主题是分类，同一概念可以属于多个主题。</p><label for="topic-name">新主题名称</label><NInput id="topic-name" v-model:value="topicName" :maxlength="64" :disabled="topicWriter.locked.value" placeholder="例如：并发编程" /><div class="topic-color"><label for="topic-color">标记颜色</label><input id="topic-color" v-model="topicColor" type="color" :disabled="topicWriter.locked.value" /></div><NButton size="small" :disabled="topicWriter.locked.value || !topicName.trim()" @click="addTopic">创建主题</NButton><p v-if="topicMessage" role="status" class="success-text">{{ topicMessage }}</p><p v-if="topicWriter.error.value" class="notice error">{{ errorText(topicWriter.error.value) }}</p><NButton v-if="topicWriter.pending.value && !topicWriter.busy.value" size="small" @click="topicWriter.retry">用原请求重试</NButton><p v-if="topicsQuery.error.value" class="notice error">{{ errorText(topicsQuery.error.value) }}</p></details>
    </aside>
    <div class="knowledge-stage">
      <template v-if="mode === 'graph'"><p v-if="graphQuery.error.value" class="notice error" role="alert">{{ errorText(graphQuery.error.value) }}<NButton text @click="graphQuery.refetch()">重试读取</NButton></p><KnowledgeGraph v-if="graphSnapshot" :graph="graphSnapshot" :selected-id="selectedId" :selected-kind="selectedKind" :topic-id="selectedTopic ?? undefined" @select="openItem" @saved="refresh" @reload="graphQuery.refetch()" /><div v-else class="loading-block galaxy-loading" role="status">正在读取知识星图…</div></template>
      <KnowledgeSearch v-else-if="mode === 'search'" :topics="topics" @open="openTarget" />
      <div v-else-if="!selectedId" class="list-empty-detail"><span aria-hidden="true">▤</span><h2>给思考留一个安静的位置</h2><p>从左侧打开一篇笔记或概念。草稿和发布版本分别保存，已有原文始终可以回看。</p><NButton type="primary" @click="openCreate('NOTE')">写第一篇笔记</NButton></div>
    </div>
    <ItemEditor v-if="selectedId" :id="selectedId" :key="selectedId" :topics="topics" :graph="graphSnapshot" @changed="refresh" @open="openItem" @close="closeItem" @loaded="selectedKind = $event" />
  </div>
  <p class="workspace-footnote">星图与目录展示已发布内容；正文草稿、整理信息、主题和布局分别保存。</p>
  <NModal :show="createOpen && !trainingAccess.checking && !trainingAccess.restricted && !trainingAccess.challenge" preset="card" :closable="false" :mask-closable="false" :close-on-esc="false" :z-index="30" :title="createKind === 'CONCEPT' ? '新建知识概念' : '写一篇独立笔记'" class="create-modal" aria-modal="true">
    <form class="creation-form" @submit.prevent="createItem"><p class="field-help">创建时保存第一个发布版本，之后可继续编辑草稿。</p><label for="new-title">标题</label><NInput id="new-title" v-model:value="createTitle" :maxlength="200" :disabled="createWriter.locked.value" placeholder="例如：缓存一致性" /><label>正文（可稍后补充）</label><MarkdownEditor v-model="createBody" :disabled="createWriter.locked.value" /><label for="new-topics">主题（可选，最多 20 个）</label><NSelect id="new-topics" v-model:value="createTopics" multiple :options="topicOptions" :disabled="createWriter.locked.value" /><p v-if="createBody.length > 100000 || createTopics.length > 20" class="notice error">正文最多 100000 字符，主题最多 20 个。</p><p v-if="createWriter.error.value" class="notice error" role="alert">{{ errorText(createWriter.error.value) }}</p><div class="modal-actions"><NButton :disabled="createWriter.locked.value" @click="closeCreate">取消</NButton><NButton v-if="createWriter.pending.value && !createWriter.busy.value" type="primary" @click="createWriter.retry">用原请求重试</NButton><NButton v-else type="primary" attr-type="submit" :loading="createWriter.busy.value" :disabled="createWriter.locked.value || !createTitle.trim() || createBody.length > 100000 || createTopics.length > 20">创建并保存</NButton></div></form>
  </NModal>
</template>
