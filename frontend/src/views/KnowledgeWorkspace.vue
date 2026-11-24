<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NModal, NPopover, NSelect } from 'naive-ui'
import { currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { GraphResponse, ItemDetail, Schema, Topic } from '../api/client'
import type { OpenKnowledge, TextImport } from '../api/knowledge'
import { queryClient } from '../api/queryClient'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceNavigation } from '../navigation/workspaceNavigation'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import RIcon from '../replica/RIcon.vue'
const KnowledgeGraph = defineAsyncComponent(() => import('../components/KnowledgeGraph.vue'))
const ItemEditor = defineAsyncComponent(() => import('../components/ItemEditor.vue'))
const KnowledgeDetail = defineAsyncComponent(() => import('../components/KnowledgeDetail.vue'))
const MarkdownEditor = defineAsyncComponent(() => import('../components/MarkdownEditor.vue'))
const KnowledgeSearch = defineAsyncComponent(() => import('../components/KnowledgeSearch.vue'))
const KnowledgeTextImport = defineAsyncComponent(() => import('../components/KnowledgeTextImport.vue'))
const KnowledgeImportRecords = defineAsyncComponent(() => import('../components/KnowledgeImportRecords.vue'))
const KnowledgeTextImportBatch = defineAsyncComponent(() => import('../components/KnowledgeTextImportBatch.vue'))

const route = useRoute()
const router = useRouter()
const { returnTo, withReturn } = useWorkspaceNavigation()
const store = useWorkspaceStore()
const trainingAccess = useTrainingAccessStore()
const mode = computed<'graph' | 'list' | 'search' | 'detail'>(() => route.query.create === 'NOTE' || route.query.create === 'CONCEPT' ? 'list' : route.query.view === 'detail' ? 'detail' : route.query.view === 'list' ? 'list' : route.query.view === 'search' ? 'search' : route.query.view === 'graph' ? 'graph' : 'list')
const filter = ref('ALL')
const collection = ref('ALL')
const search = ref('')
const query = ref('')
const cursor = ref<string>()
const previousCursors = ref<(string | undefined)[]>([])
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const selectedKind = ref<'NOTE' | 'CONCEPT'>()
const selectedTopic = ref<string | null>(null)
const graphTopics = ref<string[]>([])
const relationTypes = ref<Schema['Relation']['type'][]>(['RELATED', 'CONTRASTS', 'PRECEDES', 'PART_OF', 'APPLIED_IN'])
const relationFilters: { value: Schema['Relation']['type']; label: string }[] = [
  { value: 'PART_OF', label: '包含' }, { value: 'PRECEDES', label: '前置' },
  { value: 'RELATED', label: '相关' }, { value: 'CONTRASTS', label: '对比' }, { value: 'APPLIED_IN', label: '应用' },
]
const graphSnapshot = ref<GraphResponse>()
const graphController = ref<{ openTools: () => void }>()
const createOpen = ref(false)
const createFromFile = ref(false)
const importDirty = ref(false)
const importedFileName = ref('')
const importedSource = shallowRef<Pick<TextImport, 'source' | 'sourceSha256'>>()
const importedTags = ref<string[]>([])
const importCopy = ref(false), importHistoryOpen = ref(false)
const batchImportOpen = ref(false)
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
const createReadable = computed(() => store.sessionActive && !trainingAccess.checking && !trainingAccess.restricted
  && !trainingAccess.challenge && !store.changes.get('training-access-command')?.locked)
const filters = [{ label: '全部知识', value: 'ALL' }, { label: '知识概念', value: 'CONCEPT' }, { label: '独立笔记', value: 'NOTE' }, { label: '已归档', value: 'ARCHIVED' }]
const collections = [{ label: '全部资料', value: 'ALL' }, { label: '我的收藏', value: 'FAVORITES' }, { label: '最近访问', value: 'RECENT' }, { label: '尚未分类', value: 'UNCATEGORIZED' }, { label: '断链待整理', value: 'BROKEN_LINKS' }]

const itemsQuery = useQuery({
  queryKey: computed(() => ['knowledge', store.sessionEpoch, 'items', collection.value, selectedTopic.value, filter.value, query.value, cursor.value]),
  enabled: computed(() => store.sessionActive),
  queryFn: () => {
    const params = new URLSearchParams({ collection: collection.value, status: filter.value === 'ARCHIVED' ? 'ARCHIVED' : 'ACTIVE', limit: '20', q: query.value })
    if (filter.value === 'CONCEPT' || filter.value === 'NOTE') params.set('kind', filter.value)
    if (selectedTopic.value) params.set('topicId', selectedTopic.value)
    if (cursor.value) params.set('cursor', cursor.value)
    return read<Schema['LibraryPage']>(`/knowledge/library?${params}`)
  },
})
const graphQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'graph']), enabled: computed(() => store.sessionActive), queryFn: () => read<GraphResponse>('/knowledge/graph') })
const topicsQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'topics']), enabled: computed(() => store.sessionActive), queryFn: () => read<Schema['TopicList']>('/knowledge/topics') })
const topics = computed(() => topicsQuery.data.value?.items ?? graphSnapshot.value?.topics ?? [])
const displayedItems = computed(() => (itemsQuery.data.value?.items ?? []).filter(item =>
  route.query.edit !== '1' || selectedKind.value !== 'NOTE' || item.kind === 'NOTE'))
const allGraphTopics = computed(() => graphTopics.value.length === 0)
const topicOptions = computed(() => topics.value.map(topic => ({ label: topic.name, value: topic.id })))
watch(graphQuery.data, value => { if (value) graphSnapshot.value = value })
watch(selectedId, () => { selectedKind.value = undefined })
watch([filter, collection, selectedTopic], () => { cursor.value = undefined; previousCursors.value = [] })
// 文件预览也属于create守卫；尚未填入字段时，离开仍须明确放弃。
watch([createOpen, createTitle, createBody, createTopics, importedTags, importDirty, createWriter.locked], () => store.changes.set('create', {
  dirty: createOpen.value && Boolean(createTitle.value || createBody.value || createTopics.value.length || importedTags.value.length || importDirty.value), locked: createWriter.locked.value,
}), { immediate: true, deep: true, flush: 'sync' })
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
function openItem(id: string, keepEditor = false) { void router.push({ name: 'knowledge', query: withReturn({ ...route.query, item: id, edit: keepEditor ? '1' : undefined, panel: undefined, create: undefined, version: undefined, lineStart: undefined, lineEnd: undefined }) }) }
function openTarget(target: OpenKnowledge) { void router.push({ name: 'knowledge', query: withReturn({ ...route.query, item: target.id, edit: undefined, panel: undefined, create: undefined, version: target.versionId, lineStart: target.lineStart, lineEnd: target.lineEnd }) }) }
function closeItem() { returnTo({ name: 'knowledge', query: { view: 'list' } }) }
function changeMode(value: 'graph' | 'list' | 'search') {
  if (mode.value === value && route.query.edit !== '1') return
  void router.push({ name: 'knowledge', query: { ...route.query, view: value, edit: undefined, panel: undefined } })
}
function openCreate(kind: 'NOTE' | 'CONCEPT', fromFile = false) {
  if (createWriter.locked.value) return
  createKind.value = kind
  createFromFile.value = fromFile
  importedFileName.value = ''
  importedTags.value = []
  importedSource.value = undefined; importCopy.value = false
  createOpen.value = true
}
watch([() => route.query.create, createReadable], ([kind, readable]) => {
  if (readable && (kind === 'NOTE' || kind === 'CONCEPT') && !createOpen.value && !createWriter.locked.value) openCreate(kind)
}, { immediate: true, flush: 'post' })
function applyImport(value: TextImport) {
  if (!createReadable.value || document.visibilityState !== 'visible' || createWriter.locked.value || !createOpen.value || !createFromFile.value) return
  const sessionEpoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  if ((createTitle.value || createBody.value || importedTags.value.length) && !window.confirm('用文件所确认的标题、全文和标签选择替换当前新建表单？主题选择保留，现有服务器知识不会被覆盖。')) return
  if (!createReadable.value || document.visibilityState !== 'visible' || createWriter.locked.value || !createOpen.value || !createFromFile.value
    || sessionEpoch !== store.sessionEpoch || accessEpoch !== currentTrainingAccessEpoch()) return
  createTitle.value = value.title
  createBody.value = value.body
  importedFileName.value = value.fileName
  importedSource.value = { source: value.source, sourceSha256: value.sourceSha256 }
  importedTags.value = [...(value.tags ?? [])]
  importCopy.value = false
  createFromFile.value = false
}
function cancelImport() {
  if (!createReadable.value || createWriter.locked.value) return
  if (importDirty.value && !window.confirm('放弃本次文件预览？已有表单输入会保留。')) return
  createFromFile.value = false
}
function closeCreate() {
  if (!store.canLeave(['create'])) return
  createOpen.value = false
  createFromFile.value = false
  importedFileName.value = ''
  importedTags.value = []
  importedSource.value = undefined; importCopy.value = false
  createTitle.value = ''
  createBody.value = ''
  createTopics.value = []
  createWriter.error.value = undefined
  if (route.query.create === 'NOTE' || route.query.create === 'CONCEPT') void router.replace({ query: { ...route.query, create: undefined } })
}
function createItem() {
  if (!createReadable.value || createFromFile.value || !createTitle.value.trim() || createBody.value.length > 100000) return
  if (importedSource.value) {
    if (importedTags.value.length) {
      void createWriter.run<Schema['KnowledgeTextImportSavedWithTags']>('/knowledge/text-imports/with-tags', 'POST',
        { fileName: importedFileName.value, source: importedSource.value.source, title: createTitle.value,
          body: createBody.value, topicIds: createTopics.value, createCopy: importCopy.value, tags: [...importedTags.value] },
        value => finishCreate(value.item))
      return
    }
    void createWriter.run<Schema['KnowledgeTextImportSaved']>('/knowledge/text-imports', 'POST',
      { fileName: importedFileName.value, source: importedSource.value.source, title: createTitle.value,
        body: createBody.value, topicIds: createTopics.value, createCopy: importCopy.value }, value => finishCreate(value.item))
    return
  }
  void createWriter.run<ItemDetail>('/knowledge/items', 'POST', { kind: createKind.value, title: createTitle.value, body: createBody.value, topicIds: createTopics.value }, value => {
    finishCreate(value)
  })
}
function finishCreate(value: ItemDetail) {
    createdItem.value = value
    createOpen.value = false
    importedFileName.value = ''
    importedTags.value = []
    importedSource.value = undefined; importCopy.value = false
    createTitle.value = ''
    createBody.value = ''
    createTopics.value = []
    store.changes.delete('create')
    refresh()
    openItem(value.id)
}
function openImportRecord(target: OpenKnowledge) {
  if (createOpen.value) { closeCreate(); if (createOpen.value) return }
  importHistoryOpen.value = false
  openTarget(target)
}
function closeBatchImport() {
  if (store.canLeave(['text-import-batch'])) batchImportOpen.value = false
}
function openBatchTarget(target: OpenKnowledge) {
  batchImportOpen.value = false
  refresh()
  openTarget(target)
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
  <section class="knowledge-product" :class="[mode === 'list' ? 'knowledge-library' : `knowledge-${mode}`, { 'knowledge-editor-open': route.query.edit === '1', 'knowledge-note-editor': route.query.edit === '1' && selectedKind === 'NOTE' }]">
  <KnowledgeDetail v-if="mode === 'detail' && selectedId" :key="selectedId" :item-id="selectedId" :topics="topics" @open="openItem" @edit="router.push({ name: 'knowledge', query: withReturn({ view: 'list', item: selectedId, edit: '1' }) })" />
  <p v-else-if="mode === 'detail'" class="notice">请从知识目录选择一个对象。<RouterLink class="text-link" :to="{ name: 'knowledge', query: { view: 'list' } }">打开目录</RouterLink></p>
  <template v-else>
  <header class="page-heading"><div><h1>{{ mode === 'graph' ? '知识星图' : mode === 'search' ? '知识搜索' : '知识目录' }}</h1><p class="muted">{{ mode === 'graph' ? '探索知识之间的关系，选择节点查看原文与关联。' : '在同一份知识中整理笔记、概念与来源。' }}</p></div><div class="row-actions"><NPopover v-if="mode === 'list'" trigger="click" placement="bottom-end" :show-arrow="false"><template #trigger><NButton :disabled="!createReadable || createWriter.locked.value">导入<RIcon name="chevron-down" :size="16" /></NButton></template><div class="library-import-menu"><NButton text :disabled="!createReadable || createWriter.locked.value" @click="openCreate('NOTE', true)">导入 Markdown／TXT</NButton><NButton text :disabled="!createReadable || createWriter.locked.value" @click="batchImportOpen = true">多文件导入</NButton><NButton text :disabled="!createReadable || createWriter.locked.value" @click="importHistoryOpen = true">查看导入记录</NButton></div></NPopover><NButton :disabled="!createReadable || createWriter.locked.value" @click="openCreate('NOTE')"><RIcon name="file-plus" />新建笔记</NButton><NButton type="primary" :disabled="!createReadable || createWriter.locked.value" @click="openCreate('CONCEPT')"><RIcon name="plus" />新建知识点</NButton></div></header>
  <p v-if="createdItem" class="creation-result" role="status">已创建「{{ createdItem.title }}」<button class="text-link" @click="openItem(createdItem.id)">打开</button><button class="icon-button" aria-label="关闭创建提示" @click="createdItem = undefined">×</button></p>
  <div class="workspace-grid" :class="{ 'with-detail': selectedId, 'list-mode': mode === 'list', 'graph-mode': mode === 'graph' }">
    <aside class="knowledge-catalog" aria-label="知识列表">
      <template v-if="mode === 'list'">
        <p class="library-directory-label">知识分类</p>
        <button class="library-folder" :class="{ selected: !selectedTopic && collection === 'ALL' }" @click="selectedTopic = null; collection = 'ALL'"><RIcon name="layout-grid" /><span>全部知识</span><small title="当前页已载入数量">本页 {{ itemsQuery.data.value?.items.length ?? 0 }}</small></button>
        <button v-for="topic in topics" :key="topic.id" class="library-folder" :class="{ selected: selectedTopic === topic.id }" @click="selectedTopic = topic.id"><RIcon name="folder" /><span>{{ topic.name }}</span><small>本页 {{ itemsQuery.data.value?.items.filter(item => item.topicIds.includes(topic.id)).length ?? 0 }}</small></button>
        <hr class="library-rule">
        <button class="library-folder" :class="{ selected: collection === 'UNCATEGORIZED' }" @click="selectedTopic = null; collection = 'UNCATEGORIZED'"><RIcon name="tag" /><span>未归类</span></button>
        <button class="library-folder" :class="{ selected: collection === 'FAVORITES' }" @click="selectedTopic = null; collection = 'FAVORITES'"><RIcon name="star" /><span>我的收藏</span></button>
      </template>
      <template v-if="mode === 'graph'">
        <form class="graph-title-filter" @submit.prevent="submitSearch"><RIcon name="search" :size="22" /><input v-model="search" aria-label="搜索知识点" placeholder="搜索知识点..." :maxlength="100"></form>
        <h3>主题筛选</h3><label class="graph-check"><input type="checkbox" :checked="allGraphTopics" @change="graphTopics = []">全部主题</label><label v-for="topic in topics" :key="topic.id" class="graph-check"><input v-model="graphTopics" type="checkbox" :value="topic.id">{{ topic.name }}</label>
        <hr class="library-rule"><h3>关系类型</h3><label v-for="relation in relationFilters" :key="relation.value" class="graph-check"><input v-model="relationTypes" type="checkbox" :value="relation.value">{{ relation.label }}</label>
        <hr class="library-rule"><NButton :disabled="!createReadable" block @click="openCreate('CONCEPT')"><RIcon name="plus" />新增知识点</NButton>
      </template>
      <details v-if="mode !== 'list'" class="library-management"><summary><RIcon name="sliders-horizontal" :size="20" />筛选与管理</summary>
      <NButton v-if="mode === 'graph'" block @click="graphController?.openTools()">探索与布局工具</NButton>
      <NSelect v-model:value="filter" :options="filters" aria-label="筛选知识类型与状态" />
      <NSelect v-model:value="collection" class="library-collection" :options="collections" aria-label="知识收藏与访问分类" />
      <form class="title-search" @submit.prevent="submitSearch"><NInput v-model:value="search" :maxlength="100" placeholder="筛选已发布标题" aria-label="按标题筛选" /><NButton attr-type="submit" aria-label="执行标题筛选">查找</NButton></form><p class="field-help">目录按已发布标题筛选；正文可用全文搜索。</p>
      <div v-if="itemsQuery.isPending.value" class="loading-block" role="status">正在读取知识…</div>
      <div v-else-if="itemsQuery.error.value" class="notice error" role="alert">{{ errorText(itemsQuery.error.value) }}<NButton text @click="itemsQuery.refetch()">重试</NButton></div>
      <div v-else-if="!itemsQuery.data.value?.items.length" class="catalog-empty"><strong>{{ query ? '没有匹配的标题' : filter === 'ARCHIVED' ? '没有已归档的内容' : '知识从你的第一条记录开始' }}</strong><p>{{ query ? '试试更短的关键词，或调整类型筛选。' : '概念用于建立关系；笔记用于完整记录思考。' }}</p></div>
      <div class="row-actions library-imports"><NButton :disabled="!createReadable || createWriter.locked.value" @click="importHistoryOpen = true">导入记录</NButton><NButton :disabled="!createReadable || createWriter.locked.value" @click="batchImportOpen = true">多文件导入</NButton><NButton :disabled="!createReadable || createWriter.locked.value" @click="openCreate('NOTE', true)">导入 Markdown／TXT</NButton><NButton @click="changeMode('search')">全文搜索</NButton><NButton :loading="itemsQuery.isFetching.value || graphQuery.isFetching.value" @click="refresh">重新读取</NButton></div>
      </details>
      <div v-if="mode !== 'list' || route.query.edit === '1'" class="knowledge-list"><button v-for="item in displayedItems" :key="item.id" :class="['knowledge-row', { selected: selectedId === item.id }]" :aria-current="selectedId === item.id ? 'true' : undefined" @click="openItem(item.id, route.query.edit === '1')"><RIcon :name="item.kind === 'CONCEPT' ? 'star' : 'file-text'" :size="20" /><span><strong>{{ item.title }}</strong><small>{{ item.kind === 'CONCEPT' ? '概念' : '笔记' }} · v{{ item.versionNumber }}</small></span></button></div>
      <div v-if="(mode !== 'list' || route.query.edit === '1') && (previousCursors.length || itemsQuery.data.value?.nextCursor)" class="pagination"><NButton size="small" :disabled="!previousCursors.length" @click="previousPage">上一页</NButton><NButton size="small" :disabled="!itemsQuery.data.value?.nextCursor" @click="nextPage">下一页</NButton></div>
      <details class="topic-create"><summary>管理主题</summary><p class="field-help">主题是分类，同一概念可以属于多个主题。</p><label for="topic-name">新主题名称</label><NInput id="topic-name" v-model:value="topicName" :maxlength="64" :disabled="topicWriter.locked.value" placeholder="例如：并发编程" /><div class="topic-color"><label for="topic-color">标记颜色</label><input id="topic-color" v-model="topicColor" type="color" :disabled="topicWriter.locked.value" /></div><NButton size="small" :disabled="topicWriter.locked.value || !topicName.trim()" @click="addTopic">创建主题</NButton><p v-if="topicMessage" role="status" class="success-text">{{ topicMessage }}</p><p v-if="topicWriter.error.value" class="notice error">{{ errorText(topicWriter.error.value) }}</p><NButton v-if="topicWriter.pending.value && !topicWriter.busy.value" size="small" @click="topicWriter.retry">用原请求重试</NButton><p v-if="topicsQuery.error.value" class="notice error">{{ errorText(topicsQuery.error.value) }}</p></details>
    </aside>
    <div v-if="route.query.edit !== '1'" class="knowledge-stage">
      <template v-if="mode === 'graph'"><p v-if="graphQuery.error.value" class="notice error graph-load-error" role="alert">{{ errorText(graphQuery.error.value) }}<NButton text @click="graphQuery.refetch()">重试读取</NButton></p><KnowledgeGraph v-if="graphSnapshot" ref="graphController" :graph="graphSnapshot" :selected-id="selectedId" :selected-kind="selectedKind" :topic-ids="graphTopics" :relation-types="relationTypes" :title-query="query" @select="openItem" @saved="refresh" @reload="graphQuery.refetch()" /><div v-else class="loading-block galaxy-loading" role="status">正在读取知识星图…</div></template>
      <KnowledgeSearch v-else-if="mode === 'search'" :topics="topics" @open="openTarget" />
      <template v-else>
        <div class="library-table-filters">
          <form class="library-search" @submit.prevent="submitSearch"><NInput v-model:value="search" :maxlength="100" placeholder="搜索知识标题" aria-label="搜索已发布知识标题" clearable><template #prefix><RIcon name="search" :size="16" /></template></NInput><NButton attr-type="submit">查找</NButton></form>
          <NSelect v-model:value="filter" class="library-type-filter" :options="filters" aria-label="筛选知识类型与状态" />
          <NPopover trigger="click" placement="bottom-start" :width="280" :show-arrow="false">
            <template #trigger><NButton class="library-filter-trigger"><RIcon name="filter" :size="22" />筛选<RIcon name="chevron-down" :size="18" /></NButton></template>
            <form class="library-filter-form" @submit.prevent="submitSearch">
              <NSelect v-model:value="collection" :options="collections" aria-label="知识收藏与访问分类" />
              <NButton attr-type="submit">应用筛选</NButton>
            </form>
          </NPopover>
          <span class="library-order muted">已显示 {{ displayedItems.length }} 条</span>
          <NButton text title="全文搜索" aria-label="打开全文搜索" @click="changeMode('search')"><RIcon name="search" :size="17" /></NButton><NButton text :loading="itemsQuery.isFetching.value" title="刷新知识目录" aria-label="刷新知识目录" @click="refresh"><RIcon name="refresh-cw" :size="17" /></NButton>
        </div>
        <p v-if="itemsQuery.isPending.value" class="loading-block" role="status">正在读取知识目录…</p>
        <p v-else-if="itemsQuery.error.value" class="notice error" role="alert">{{ errorText(itemsQuery.error.value) }}<NButton text @click="itemsQuery.refetch()">重新读取</NButton></p>
        <template v-else>
        <table class="library-table"><thead><tr><th scope="col">标题</th><th scope="col">主题</th><th scope="col">类型</th><th scope="col">版本</th><th scope="col">更新</th></tr></thead><tbody><tr v-for="item in displayedItems" :key="item.id" :class="{ selected: selectedId === item.id }" @click="openItem(item.id)"><td><button @click.stop="openItem(item.id)"><RIcon :name="item.kind === 'CONCEPT' ? 'book-open' : 'file-text'" :size="18" /><span>{{ item.title }}</span></button></td><td><span v-for="topicId in item.topicIds.slice(0, 2)" :key="topicId" class="library-topic">{{ topics.find(topic => topic.id === topicId)?.name ?? '主题' }}</span><span v-if="!item.topicIds.length" class="faint">—</span></td><td>{{ item.kind === 'CONCEPT' ? '概念' : '笔记' }}</td><td>v{{ item.versionNumber }}</td><td>{{ new Date(item.updatedAt).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) }}</td></tr></tbody></table>
        <div v-if="!displayedItems.length" class="list-empty-detail"><RIcon name="book-open" :size="28" /><h2>{{ query ? '没有匹配的知识' : '从第一条知识开始积累' }}</h2><p>记录概念、笔记与出处，让下一次查找更容易。</p><NButton type="primary" :disabled="!createReadable" @click="openCreate('NOTE')">新建笔记</NButton></div>
        <div v-if="previousCursors.length || itemsQuery.data.value?.nextCursor" class="library-pagination"><span>第 {{ previousCursors.length + 1 }} 页</span><NButton size="small" :disabled="!previousCursors.length || itemsQuery.isFetching.value" @click="previousPage">上一页</NButton><NButton size="small" :disabled="!itemsQuery.data.value?.nextCursor || itemsQuery.isFetching.value" @click="nextPage">下一页</NButton></div>
        </template>
      </template>
    </div>
    <ItemEditor v-if="selectedId" :id="selectedId" :key="selectedId" :compact="route.query.edit !== '1'" :graph-overview="mode === 'graph'" :topics="topics" :graph="graphSnapshot" @changed="refresh" @open="openItem" @close="closeItem" @loaded="selectedKind = $event" />
  </div>
  <p class="workspace-footnote">星图与目录展示已发布内容；正文草稿、整理信息、主题和布局分别保存。</p>
  <NModal :show="createOpen && !trainingAccess.checking && !trainingAccess.restricted && !trainingAccess.challenge" preset="card" :closable="false" :mask-closable="false" :close-on-esc="false" :z-index="30" :title="createFromFile ? '导入文本为新笔记' : createKind === 'CONCEPT' ? '新建知识概念' : '写一篇独立笔记'" class="create-modal" aria-modal="true">
    <KnowledgeTextImport v-if="createOpen && createFromFile" :enabled="createReadable && !createWriter.locked.value"
      @apply="applyImport" @cancel="cancelImport" @state="importDirty = $event" />
    <p v-if="importedFileName" class="notice" role="status">「{{ importedFileName }}」所确认的标题、全文与标签选择已填入，可以继续编辑。尚未保存；创建时发送原始文本及最终表单，由服务器计算指纹并同事务登记首版及明确采用的标签，不额外保存原文件副本。别名和未采用属性不另外保存，所选站内引用仍按原发布规则校验。未编辑正文保留确认时换行，主动编辑沿原编辑器规则。</p>
    <template v-if="importedSource">
      <div v-if="importedTags.length">
        <p class="field-help">明确采用的标签原始声明（创建时去首尾空白、忽略大小写去重；不创建主题或概念）：</p>
        <ul><li v-for="(tag, index) in importedTags" :key="index"><code>「{{ tag }}」</code></li></ul>
        <NButton :disabled="!createReadable || createWriter.locked.value" @click="importedTags = []">取消标签映射，保留当前标题与正文</NButton>
      </div>
      <KnowledgeImportRecords :source-sha256="importedSource.sourceSha256" :enabled="createOpen && createReadable && !createWriter.locked.value" @open="openImportRecord" />
      <NCheckbox v-model:checked="importCopy" :disabled="!createReadable || createWriter.locked.value">明确新建副本：即使这份原文已有登记，也另建独立笔记和首版，不覆盖旧内容</NCheckbox>
      <p class="field-help">不勾选时服务器在提交事务中检查已登记的相同原文；改名、编辑标题或最终正文不会绕过该检查。未知结果期间请用原请求重试。</p>
    </template>
    <form class="creation-form" @submit.prevent="createItem"><p class="field-help">创建时保存第一个发布版本，之后可继续编辑草稿。</p><label for="new-title">标题</label><NInput id="new-title" v-model:value="createTitle" :maxlength="200" :disabled="createWriter.locked.value" placeholder="例如：缓存一致性" /><label>正文（可稍后补充）</label><MarkdownEditor v-model="createBody" :disabled="createWriter.locked.value" /><label for="new-topics">主题（可选，最多 20 个）</label><NSelect id="new-topics" v-model:value="createTopics" multiple :options="topicOptions" :disabled="createWriter.locked.value" /><p v-if="createBody.length > 100000 || createTopics.length > 20" class="notice error">正文最多 100000 字符，主题最多 20 个。</p><p v-if="createWriter.error.value" class="notice error" role="alert">{{ errorText(createWriter.error.value) }}</p><div class="modal-actions"><NButton :disabled="createWriter.locked.value" @click="closeCreate">取消</NButton><NButton v-if="createWriter.pending.value && !createWriter.busy.value" type="primary" @click="createWriter.retry">用原请求重试</NButton><NButton v-else type="primary" attr-type="submit" :loading="createWriter.busy.value" :disabled="!createReadable || createFromFile || createWriter.locked.value || !createTitle.trim() || createBody.length > 100000 || createTopics.length > 20">创建并保存</NButton></div></form>
  </NModal>
  <NModal :show="importHistoryOpen && createReadable" preset="card" title="文本导入记录" class="create-modal" @update:show="importHistoryOpen = $event">
    <KnowledgeImportRecords v-if="importHistoryOpen" :enabled="importHistoryOpen && createReadable" @open="openImportRecord" />
  </NModal>
  <NModal :show="batchImportOpen && createReadable" preset="card" title="多文件文本导入" class="create-modal" :mask-closable="false" :close-on-esc="false" @update:show="closeBatchImport">
    <KnowledgeTextImportBatch v-if="batchImportOpen" :enabled="batchImportOpen && createReadable" :topics="topics" @changed="refresh" @open="openBatchTarget" />
  </NModal>
  </template>
  </section>
</template>

<style scoped>
.knowledge-product { min-width: 0; }
.knowledge-product .workspace-grid { display: grid; grid-template-columns: 210px minmax(0,1fr); min-height: 500px; max-width: none; margin: 0; gap: 0; border: 1px solid var(--line); border-radius: 10px; overflow: clip; background: var(--surface); }
.knowledge-product .workspace-grid.with-detail { grid-template-columns: 210px minmax(0,1fr); }
.knowledge-product .workspace-grid.with-detail .knowledge-stage { display: none; }
.knowledge-library .workspace-grid { min-height: 0; grid-template-columns: 184px minmax(0, 1fr); gap: 0; border: 1px solid var(--studio-divider); border-radius: 8px; background: var(--studio-pane); overflow: clip; align-items: start; }
.knowledge-library .workspace-grid.with-detail { gap: 0; }
.knowledge-product .knowledge-catalog { padding: 16px 10px; min-width: 0; background: var(--surface-soft); border-right: 1px solid var(--line); }
.knowledge-product .knowledge-stage { display: block; position: relative; min-width: 0; overflow-x: auto; padding: 0; }
.knowledge-library .knowledge-catalog { position: sticky; top: calc(var(--topbar) + 56px); padding: 16px 10px; max-height: calc(100dvh - var(--topbar) - 72px); background: none; border: 0; border-right: 1px solid var(--studio-divider); }
.knowledge-library .knowledge-stage { border: 0; border-radius: 0; background: transparent; align-self: start; }
.knowledge-product :deep(.item-editor) { min-width: 0; padding: 20px; border-left: 1px solid var(--line); border-radius: 0; background: var(--surface); }
.library-directory-label { padding: 0 10px 12px; font-size: 12px; color: var(--faint); }.library-folder { display: flex; align-items: center; gap: 10px; width: 100%; min-height: 40px; padding: 9px 10px; border-radius: 6px; text-align: left; font-size: 13px; }.library-folder > svg { width: 17px; flex: none; }.library-folder > span { flex: 1; min-width: 0; overflow-wrap: anywhere; }.library-folder small { font-size: 11px; color: var(--muted); }.library-folder:hover { background: var(--studio-pane); }.library-folder.selected { background: var(--brand-soft); color: var(--brand-strong); }.library-rule { height: 1px; border: 0; background: var(--studio-divider); margin: 20px 8px; }
.library-management { margin: 18px 4px 0; }.library-management > summary { display: flex; align-items: center; gap: 6px; color: var(--muted); cursor: pointer; font-size: 12px; }.library-management > :not(summary) { margin-top: 10px; }.library-imports { display: grid; }
.library-filter-form, .library-import-menu { display: grid; gap: 12px; }.library-import-menu { padding: 4px; justify-items: start; }.library-table-filters { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 12px 16px; border-bottom: 1px solid var(--studio-divider); }.library-table-filters > span { font-size: 12px; color: var(--muted); }.library-order { margin-left: auto; white-space: nowrap; }.library-search { display: flex; flex: 1; min-width: 180px; max-width: 520px; gap: 8px; }.library-search > .n-input { min-width: 0; }.library-type-filter { width: 150px; }.library-pagination { display: flex; align-items: center; justify-content: end; gap: 8px; padding: 12px 16px; border-top: 1px solid var(--studio-divider); }.library-pagination > span { margin-right: auto; color: var(--faint); font-size: 12px; }
.library-table { width: 100%; border-collapse: collapse; font-size: 12px; text-align: left; }.library-table th { font-size: 12px; font-weight: 400; color: var(--faint); padding: 12px 16px; white-space: nowrap; }.library-table td { padding: 17px 16px; border-top: 1px solid var(--studio-divider); color: var(--muted); }.library-table td:first-child { width: 48%; color: var(--ink); }.library-table :is(th,td):first-child { padding-left: 20px; }.library-table td > button { display: flex; align-items: center; gap: 10px; width: 100%; padding: 0; text-align: left; font-size: 14px; line-height: 1.6; overflow-wrap: anywhere; }.library-table td > button > svg { color: var(--faint); flex: none; }.library-table td > button > span { min-width: 0; }.library-table tbody tr { cursor: pointer; }.library-table tr:hover td { background: var(--surface-soft); }.library-table tr.selected td { background: var(--brand-soft); }.library-topic { display: inline-block; margin: 2px; border-radius: 4px; padding: 2px 6px; color: var(--muted); background: var(--surface-soft); }
.knowledge-library .library-table td { padding-block: 12px; }.knowledge-library .library-table th { background: var(--studio-pane); }.knowledge-product .list-empty-detail { min-height: 220px; padding: 28px 24px; }.knowledge-product .list-empty-detail p { font-size: 14px; }
.knowledge-product .knowledge-list { max-height: 320px; }.knowledge-list strong { font-size: 13px; font-weight: 500; }.knowledge-list .r-icon { width: 17px; flex: none; color: var(--brand); }
.knowledge-graph .workspace-grid { min-height: 570px; }.knowledge-graph .knowledge-list { display: none; }.knowledge-graph .knowledge-catalog { align-self: stretch; }.knowledge-graph .topic-create { margin-top: 16px; }
.graph-title-filter { display: flex; align-items: center; gap: 6px; padding: 7px 9px; border: 1px solid var(--line); border-radius: 6px; margin-bottom: 18px; color: var(--muted); background: var(--surface); }.graph-title-filter input { min-width: 0; width: 100%; background: transparent; border: 0; color: inherit; font-size: 13px; }
.knowledge-product .knowledge-catalog h3 { font-size: 13px; margin: 14px 0 9px; }.graph-check { display: flex; align-items: center; gap: 9px; margin: 9px 0; font-size: 13px; }.graph-check input { accent-color: var(--brand); width: 15px; height: 15px; margin: 0; }
.knowledge-graph :deep(.graph-workspace) { min-height: 570px; }.knowledge-graph :deep(.galaxy) { min-height: 570px; }.knowledge-graph :deep(.galaxy-canvas) { min-height: 570px; }
.knowledge-editor-open .workspace-grid.with-detail { grid-template-columns: 210px minmax(0,1fr); }.knowledge-editor-open .knowledge-list { display: grid; }
.knowledge-library :deep(.item-editor) { border: 1px solid var(--studio-divider); border-radius: 10px; background: var(--studio-pane); padding: 24px; }
@container workspace (max-width: 1080px) { .knowledge-product .workspace-grid.with-detail { grid-template-columns: 190px minmax(0,1fr); } }
@media(max-width:840px) { .knowledge-product :deep(.item-editor) { position: static; display: block; inset: auto; z-index: auto; overflow-y: visible; } }
@media(max-width:700px) {
  .knowledge-product .workspace-grid, .knowledge-product .workspace-grid.with-detail, .knowledge-editor-open .workspace-grid.with-detail { grid-template-columns: minmax(0,1fr); }
  .knowledge-product .knowledge-catalog { max-height: 210px; overflow-y: auto; border-right: 0; border-bottom: 1px solid var(--line); }
  .knowledge-product .workspace-grid.with-detail .knowledge-catalog { display: none; }
  .knowledge-library .workspace-grid { gap: 0; min-height: 0; }.knowledge-library .knowledge-catalog { position: static; display: flex; flex-wrap: wrap; gap: 6px; padding: 12px; border-right: 0; border-bottom: 1px solid var(--studio-divider); max-height: none; }.knowledge-library .library-folder { width: auto; min-height: 34px; padding: 6px 9px; }.knowledge-library .library-directory-label, .knowledge-library .library-rule { display: none; }.knowledge-library .topic-create { flex-basis: 100%; margin-top: 4px; padding-top: 8px; }.library-table-filters { padding: 12px; gap: 8px; }.library-search { max-width: none; flex-basis: 100%; }.library-type-filter { width: 140px; }.library-order { margin-left: auto; }.library-directory-label { padding-left: 10px; }
  .knowledge-product :deep(.item-editor) { border-left: 0; padding: 18px; }
  .library-table th:nth-child(3), .library-table td:nth-child(3), .library-table th:last-child, .library-table td:last-child { display: none; }
  .library-table th, .library-table td { padding: 11px 9px; }.library-table td > button { font-size: 13px; }
}
</style>
