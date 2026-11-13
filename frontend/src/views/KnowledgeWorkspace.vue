<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NModal, NSelect } from 'naive-ui'
import { currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { GraphResponse, ItemDetail, Schema, Topic } from '../api/client'
import type { OpenKnowledge, TextImport } from '../api/knowledge'
import { queryClient } from '../api/queryClient'
import { useCommand } from '../composables/useCommand'
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
const store = useWorkspaceStore()
const trainingAccess = useTrainingAccessStore()
const mode = computed<'graph' | 'list' | 'search' | 'detail'>(() => route.query.create === 'NOTE' || route.query.create === 'CONCEPT' ? 'list' : route.query.view === 'detail' ? 'detail' : route.query.view === 'list' ? 'list' : route.query.view === 'search' ? 'search' : 'graph')
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
const displayedItems = computed(() => (itemsQuery.data.value?.items ?? []).filter(item =>
  (!selectedTopic.value || item.topicIds.includes(selectedTopic.value))
  && (route.query.edit !== '1' || selectedKind.value !== 'NOTE' || item.kind === 'NOTE')))
const allGraphTopics = computed(() => graphTopics.value.length === 0)
const topicOptions = computed(() => topics.value.map(topic => ({ label: topic.name, value: topic.id })))
watch(graphQuery.data, value => { if (value) graphSnapshot.value = value })
watch(selectedId, () => { selectedKind.value = undefined })
watch([filter, collection], () => { cursor.value = undefined; previousCursors.value = [] })
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
function openItem(id: string) { void router.push({ name: 'knowledge', query: { ...route.query, item: id, edit: undefined, panel: undefined, create: undefined, version: undefined, lineStart: undefined, lineEnd: undefined } }) }
function openTarget(target: OpenKnowledge) { void router.push({ name: 'knowledge', query: { ...route.query, item: target.id, edit: undefined, panel: undefined, create: undefined, version: target.versionId, lineStart: target.lineStart, lineEnd: target.lineEnd } }) }
function closeItem() { void router.push({ name: 'knowledge', query: { ...route.query, item: undefined, edit: undefined, panel: undefined, version: undefined, lineStart: undefined, lineEnd: undefined } }) }
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
  <KnowledgeDetail v-if="mode === 'detail' && selectedId" :key="selectedId" :item-id="selectedId" :topics="topics" @open="openItem" @edit="router.push({ name: 'knowledge', query: { view: 'list', item: selectedId, edit: '1' } })" />
  <p v-else-if="mode === 'detail'" class="notice">请从知识目录选择一个对象。<RouterLink class="text-link" :to="{ name: 'knowledge', query: { view: 'list' } }">打开目录</RouterLink></p>
  <template v-else>
  <header v-if="mode !== 'graph'" class="page-heading"><div><h1>知识</h1><p class="muted">沉淀技术知识，构建你的面试知识体系</p></div><div class="row-actions"><NButton :disabled="!createReadable || createWriter.locked.value" @click="openCreate('NOTE')"><RIcon name="file-plus" />新建笔记</NButton><NButton type="primary" :disabled="!createReadable || createWriter.locked.value" @click="openCreate('CONCEPT')"><RIcon name="plus" />新增知识点</NButton></div></header>
  <div class="knowledge-toolbar"><div class="view-switch" role="group" aria-label="知识视图"><button :aria-pressed="mode === 'graph'" :class="{ active: mode === 'graph' }" @click="changeMode('graph')"><RIcon name="star" />星图</button><button :aria-pressed="mode === 'list'" :class="{ active: mode === 'list' }" @click="changeMode('list')"><RIcon name="list" />列表</button><RouterLink :to="{ name: 'boards' }"><RIcon name="square" />白板</RouterLink></div></div>
  <p v-if="createdItem" class="creation-result" role="status">已创建「{{ createdItem.title }}」<button class="text-link" @click="openItem(createdItem.id)">打开</button><button class="icon-button" aria-label="关闭创建提示" @click="createdItem = undefined">×</button></p>
  <div class="workspace-grid" :class="{ 'with-detail': selectedId, 'list-mode': mode === 'list', 'graph-mode': mode === 'graph' }">
    <aside class="knowledge-catalog" aria-label="知识列表">
      <template v-if="mode === 'list'">
        <button class="library-folder" :class="{ selected: !selectedTopic && collection === 'ALL' }" @click="selectedTopic = null; collection = 'ALL'"><RIcon name="layout-grid" /><span>全部笔记</span><small>{{ itemsQuery.data.value?.items.length ?? 0 }}</small></button>
        <button v-for="topic in topics" :key="topic.id" class="library-folder" :class="{ selected: selectedTopic === topic.id }" @click="selectedTopic = topic.id"><RIcon name="folder" /><span>{{ topic.name }}</span><small>{{ itemsQuery.data.value?.items.filter(item => item.topicIds.includes(topic.id)).length ?? 0 }}</small></button>
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
      <details class="library-management"><summary><RIcon name="sliders-horizontal" :size="20" />筛选与管理</summary>
      <NButton v-if="mode === 'graph'" block @click="graphController?.openTools()">探索与布局工具</NButton>
      <NSelect v-model:value="filter" :options="filters" aria-label="筛选知识类型与状态" />
      <NSelect v-model:value="collection" class="library-collection" :options="collections" aria-label="知识收藏与访问分类" />
      <form class="title-search" @submit.prevent="submitSearch"><NInput v-model:value="search" :maxlength="100" placeholder="筛选已发布标题" aria-label="按标题筛选" /><NButton attr-type="submit" aria-label="执行标题筛选">查找</NButton></form><p class="field-help">目录按已发布标题筛选；正文可用全文搜索。</p>
      <div v-if="itemsQuery.isPending.value" class="loading-block" role="status">正在读取知识…</div>
      <div v-else-if="itemsQuery.error.value" class="notice error" role="alert">{{ errorText(itemsQuery.error.value) }}<NButton text @click="itemsQuery.refetch()">重试</NButton></div>
      <div v-else-if="!itemsQuery.data.value?.items.length" class="catalog-empty"><strong>{{ query ? '没有匹配的标题' : filter === 'ARCHIVED' ? '没有已归档的内容' : '知识从你的第一条记录开始' }}</strong><p>{{ query ? '试试更短的关键词，或调整类型筛选。' : '概念用于建立关系；笔记用于完整记录思考。' }}</p></div>
      <div class="row-actions library-imports"><NButton :disabled="!createReadable || createWriter.locked.value" @click="importHistoryOpen = true">导入记录</NButton><NButton :disabled="!createReadable || createWriter.locked.value" @click="batchImportOpen = true">多文件导入</NButton><NButton :disabled="!createReadable || createWriter.locked.value" @click="openCreate('NOTE', true)">导入 Markdown／TXT</NButton><NButton @click="changeMode('search')">全文搜索</NButton><NButton :loading="itemsQuery.isFetching.value || graphQuery.isFetching.value" @click="refresh">重新读取</NButton></div>
      </details>
      <div v-if="mode !== 'list'" class="knowledge-list"><button v-for="item in displayedItems" :key="item.id" :class="['knowledge-row', { selected: selectedId === item.id }]" :aria-current="selectedId === item.id ? 'true' : undefined" @click="openItem(item.id)"><RIcon :name="item.kind === 'CONCEPT' ? 'star' : 'file-text'" :size="20" /><span><strong>{{ item.title }}</strong><small>{{ item.kind === 'CONCEPT' ? '概念' : '笔记' }} · v{{ item.versionNumber }}</small></span></button></div>
      <div v-if="previousCursors.length || itemsQuery.data.value?.nextCursor" class="pagination"><NButton size="small" :disabled="!previousCursors.length" @click="previousPage">上一页</NButton><NButton size="small" :disabled="!itemsQuery.data.value?.nextCursor" @click="nextPage">下一页</NButton></div>
      <details class="topic-create"><summary>管理主题</summary><p class="field-help">主题是分类，同一概念可以属于多个主题。</p><label for="topic-name">新主题名称</label><NInput id="topic-name" v-model:value="topicName" :maxlength="64" :disabled="topicWriter.locked.value" placeholder="例如：并发编程" /><div class="topic-color"><label for="topic-color">标记颜色</label><input id="topic-color" v-model="topicColor" type="color" :disabled="topicWriter.locked.value" /></div><NButton size="small" :disabled="topicWriter.locked.value || !topicName.trim()" @click="addTopic">创建主题</NButton><p v-if="topicMessage" role="status" class="success-text">{{ topicMessage }}</p><p v-if="topicWriter.error.value" class="notice error">{{ errorText(topicWriter.error.value) }}</p><NButton v-if="topicWriter.pending.value && !topicWriter.busy.value" size="small" @click="topicWriter.retry">用原请求重试</NButton><p v-if="topicsQuery.error.value" class="notice error">{{ errorText(topicsQuery.error.value) }}</p></details>
    </aside>
    <div class="knowledge-stage">
      <section v-if="route.query.edit === '1'" class="editor-note-directory" aria-label="当前知识目录"><header><h2>{{ topics.find(topic => topic.id === selectedTopic)?.name || '知识目录' }}</h2><button class="studio-icon-button" title="新建笔记" @click="openCreate('NOTE')"><RIcon name="plus" :size="22" /></button></header><button v-for="item in displayedItems" :key="item.id" :class="{ selected: item.id === selectedId }" @click="router.push({ name: 'knowledge', query: { view: 'list', item: item.id, edit: '1', panel: route.query.panel } })"><strong>{{ item.title }}</strong><small>{{ new Date(item.updatedAt).toLocaleDateString('zh-CN') }} · v{{ item.versionNumber }}</small></button><p v-if="!displayedItems.length" class="field-help">当前已读目录中没有匹配项。</p></section>
      <template v-else-if="mode === 'graph'"><p v-if="graphQuery.error.value" class="notice error graph-load-error" role="alert">{{ errorText(graphQuery.error.value) }}<NButton text @click="graphQuery.refetch()">重试读取</NButton></p><KnowledgeGraph v-if="graphSnapshot" ref="graphController" :graph="graphSnapshot" :selected-id="selectedId" :selected-kind="selectedKind" :topic-ids="graphTopics" :relation-types="relationTypes" :title-query="query" overlay @select="openItem" @saved="refresh" @reload="graphQuery.refetch()" /><div v-else class="loading-block galaxy-loading" role="status">正在读取知识星图…</div></template>
      <KnowledgeSearch v-else-if="mode === 'search'" :topics="topics" @open="openTarget" />
      <template v-else>
        <form class="library-table-filters" @submit.prevent="submitSearch"><RIcon name="search" /><NInput v-model:value="search" :maxlength="100" placeholder="筛选已发布标题" aria-label="按标题筛选" clearable /><NButton attr-type="submit">筛选</NButton><span class="muted">更新时间</span></form>
        <table class="library-table"><thead><tr><th>标题</th><th>主题</th><th>类型</th><th>版本</th><th>更新</th></tr></thead><tbody><tr v-for="item in displayedItems" :key="item.id" :class="{ selected: selectedId === item.id }" @click="openItem(item.id)"><td><button @click.stop="openItem(item.id)">{{ item.title }}</button></td><td><span v-for="topicId in item.topicIds.slice(0, 2)" :key="topicId" class="library-topic">{{ topics.find(topic => topic.id === topicId)?.name ?? '主题' }}</span></td><td>{{ item.kind === 'CONCEPT' ? '概念' : '笔记' }}</td><td>v{{ item.versionNumber }}</td><td>{{ new Date(item.updatedAt).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) }}</td></tr></tbody></table>
        <div v-if="!displayedItems.length" class="list-empty-detail"><RIcon name="book-open" :size="42" /><h2>{{ query ? '没有匹配的笔记' : '给思考留一个安静的位置' }}</h2><p>概念、笔记与出处，留在你的知识库。</p><NButton type="primary" :disabled="!createReadable" @click="openCreate('NOTE')">新建笔记</NButton></div>
      </template>
    </div>
    <ItemEditor v-if="selectedId" :id="selectedId" :key="selectedId" :compact="route.query.edit !== '1'" :topics="topics" :graph="graphSnapshot" @changed="refresh" @open="openItem" @close="closeItem" @loaded="selectedKind = $event" />
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
.knowledge-product { position: relative; }.knowledge-toolbar { justify-content: center; max-width: none; margin: 0; }.knowledge-list .r-icon { flex: none; color: var(--brand); }.knowledge-list strong { font-weight: 500; }
.knowledge-list-mode { min-height: 700px; }.library-management { margin-top: 22px; }.library-management > summary { display: flex; align-items: center; gap: 9px; color: var(--muted); cursor: pointer; font-size: 15px; }.library-management > :not(summary) { margin-top: 12px; }.library-imports { display: grid; }
.library-folder { display: flex; align-items: center; gap: 14px; width: 100%; padding: 14px; border: 1px solid transparent; border-radius: 13px; text-align: left; font-size: 20px; }.library-folder > span { flex: 1; }.library-folder small { font-size: 18px; color: var(--muted); }.library-folder.selected { background: linear-gradient(110deg, #935b2e6b, #5f3e263b); border-color: #9e724647; }.library-rule { height: 1px; border: 0; background: #ffffff10; margin: 25px 5px; }
.knowledge-product .workspace-grid { margin-top: 24px; display: grid; max-width: none; min-height: 675px; border: 0; gap: 12px; background: transparent; overflow: visible; grid-template-columns: 248px minmax(0, 1fr); }.knowledge-product .workspace-grid.with-detail { grid-template-columns: 248px minmax(0, 1fr) 374px; }.knowledge-product .knowledge-catalog, .knowledge-product .knowledge-stage, .knowledge-product :deep(.item-editor) { border: 1px solid var(--line); border-radius: 20px; background: linear-gradient(120deg, #25211c90, #18161280 70%); min-width: 0; }
.knowledge-product .knowledge-catalog { padding: 24px 7px; }.knowledge-product .knowledge-stage { display: block; overflow: hidden; }.knowledge-product .workspace-grid.list-mode.with-detail .knowledge-stage { display: block; }.knowledge-product :deep(.item-editor) { padding: 35px 22px; }.knowledge-product .knowledge-list { max-height: 280px; }.knowledge-product .knowledge-row { font-size: 16px; }.knowledge-product .knowledge-row small { font-size: 13px; }
.knowledge-library .knowledge-toolbar { position: absolute; top: 20px; left: 40%; transform: translateX(-50%); }.knowledge-product .list-empty-detail { min-height: 450px; padding: 50px 22px; }.knowledge-product .list-empty-detail p { font-size: 17px; }
.library-table-filters { display: flex; align-items: center; gap: 12px; padding: 15px 18px; border-bottom: 1px solid var(--line); }.library-table-filters .n-input { flex: 1; min-width: 0; }.library-table-filters > span { font-size: 16px; white-space: nowrap; }.library-table { width: 100%; border-collapse: separate; border-spacing: 0; font-size: 18px; text-align: left; }.library-table th { font-size: 18px; color: var(--muted); font-weight: 400; padding: 16px 14px; }.library-table td { padding: 20px 14px; border-block: 1px solid #ffffff09; }.library-table td:first-child { width: 38%; }.library-table td > button { padding: 0; text-align: left; font-size: 20px; line-height: 1.5; }.library-table tr { cursor: pointer; }.library-table tr.selected td { background: #985c2a33; border-block-color: #ac7949; }.library-table tr.selected td:first-child { border-left: 1px solid #ac7949; border-radius: 12px 0 0 12px; }.library-table tr.selected td:last-child { border-right: 1px solid #ac7949; border-radius: 0 12px 12px 0; }.library-topic { display: inline-block; margin: 2px; border-radius: 20px; padding: 3px 11px; color: #acd5d5; background: #365f5c77; white-space: nowrap; }
.knowledge-graph .knowledge-toolbar { position: absolute; left: 440px; top: 0; z-index: 5; }.knowledge-graph .workspace-grid { margin-top: 10px; min-height: 890px; grid-template-columns: 254px minmax(0, 1fr); }.knowledge-graph .workspace-grid.with-detail { grid-template-columns: 254px minmax(0, 1fr) 415px; }.knowledge-graph .knowledge-stage { border: 0; border-radius: 0; background: transparent; padding-top: 65px; }.knowledge-graph .knowledge-catalog { align-self: start; padding: 20px; }.knowledge-graph .knowledge-list { display: none; }.knowledge-graph .topic-create { margin-top: 20px; }.graph-title-filter { display: flex; align-items: center; gap: 9px; padding: 10px 12px; border: 1px solid #ffffff0c; border-radius: 24px; background: #ffffff03; margin-bottom: 20px; color: var(--muted); }.graph-title-filter input { min-width: 0; width: 100%; background: transparent; border: 0; color: inherit; font-size: 18px; outline: 0; }.knowledge-product .knowledge-catalog h3 { font-size: 20px; margin: 12px 0; }.graph-check { display: flex; align-items: center; gap: 12px; margin: 12px 0; font-size: 19px; }.graph-check input { accent-color: #cc914b; width: 23px; height: 23px; margin: 0; }.knowledge-graph :deep(.item-editor) { margin-top: 10px; align-self: start; min-height: 775px; }
@media (min-width: 1251px) {
  .knowledge-graph .knowledge-toolbar { left: 436px; }
  .knowledge-graph .workspace-grid { position: relative; display: block; margin-top: 0; min-height: max(890px,calc(100dvh - 134px)); }
  /* 面板覆盖同一幅图；切换详情不会改变画布宽度或重排已保存的节点。 */
  .knowledge-graph .knowledge-stage { position: absolute; inset: -18px -27px -24px -18px; padding: 0; overflow: visible; }
  .knowledge-graph .knowledge-catalog { position: relative; z-index: 2; top: 11px; width: 254px; min-height: 707px; max-height: calc(100dvh - 170px); overflow-y: auto; background: linear-gradient(120deg,#1b1913f2,#12110ef2); border-color: #4d4437; border-radius: 22px; }
  .knowledge-graph :deep(.item-editor) { position: absolute; z-index: 2; right: 0; top: 21px; width: 415px; height: 777px; min-height: 0; max-height: calc(100dvh - 180px); margin: 0; overflow-y: auto; border-color: #796447; background: linear-gradient(120deg,#312714e8,#14120ef5 25%,#171510f5); }
  .knowledge-graph .graph-load-error { position: absolute; z-index: 3; left: 290px; right: 460px; top: 92px; }
  .knowledge-graph .galaxy-loading { position: absolute; inset: 90px 440px 90px 275px; }
  .knowledge-graph > .workspace-footnote { display: none; }
}
.knowledge-editor-open > .page-heading { display: none; }.knowledge-editor-open > .knowledge-toolbar { position: static; transform: none; justify-content: end; }.knowledge-editor-open .workspace-grid.with-detail { grid-template-columns: 175px 245px minmax(0, 1fr); margin-top: 18px; }.knowledge-editor-open .knowledge-stage { display: block; }.knowledge-editor-open :deep(.item-editor) { border: 0; background: none; padding: 0; }.knowledge-editor-open .library-folder { padding: 12px 8px; font-size: 18px; gap: 9px; }.knowledge-editor-open .library-folder small { font-size: 14px; }.editor-note-directory { padding: 20px 10px; }.editor-note-directory header { display: flex; justify-content: space-between; align-items: center; padding: 0 5px 18px; }.editor-note-directory h2 { font-size: 21px; }.editor-note-directory > button { width: 100%; display: grid; gap: 10px; padding: 18px 15px; text-align: left; border: 1px solid transparent; border-radius: 15px; }.editor-note-directory > button.selected { border-color: var(--brand); background: var(--brand-soft); }.editor-note-directory strong { font-size: 19px; font-weight: 500; line-height: 1.6; }.editor-note-directory small { color: var(--muted); font-size: 15px; }
@media(min-width:1001px) {
  .knowledge-note-editor > .knowledge-toolbar { display: none; }.knowledge-note-editor .workspace-grid.with-detail { grid-template-columns: 169px 246px minmax(0,1fr) 290px; align-items: start; column-gap: 8px; row-gap: 8px; min-height: 0; margin-top: 0; }
  .knowledge-note-editor :deep(.item-note-editor),.knowledge-note-editor :deep(.item-full-editor),.knowledge-note-editor :deep(.item-document-layout) { display: contents; }
  .knowledge-note-editor :deep(.item-document-heading) { grid-column: 1 / -1; grid-row: 1; }.knowledge-note-editor .knowledge-catalog { grid-column: 1; grid-row: 2; min-height: 705px; margin-right: -8px; border-radius: 22px 22px 0 22px; padding: 15px 7px; }.knowledge-note-editor .knowledge-stage { grid-column: 2; grid-row: 2; min-height: 705px; }.knowledge-note-editor :deep(.item-document-main) { grid-column: 3; grid-row: 2; }.knowledge-note-editor :deep(.note-context) { grid-column: 4; grid-row: 2; }
  .knowledge-note-editor .library-folder { font-size: 18px; gap: 9px; padding: 11px 8px; }.knowledge-note-editor .library-folder > .r-icon { display: none; }.knowledge-note-editor .library-folder.selected { position: relative; border: 0; background: #7c55302b; }.knowledge-note-editor .library-folder.selected::before { position: absolute; content: ''; left: 0; top: 5px; bottom: 5px; width: 5px; border-radius: 5px; background: var(--brand); }.knowledge-note-editor .library-management,.knowledge-note-editor .topic-create { margin-inline: 8px; }.knowledge-note-editor .editor-note-directory { padding: 14px 6px; }.knowledge-note-editor .editor-note-directory > button { padding: 13px 15px; border-radius: 16px; }.knowledge-note-editor .editor-note-directory strong { font-size: 19px; }
}
@media (max-width: 1250px) { .knowledge-library .knowledge-toolbar { position: static; transform: none; }.knowledge-product .workspace-grid.with-detail, .knowledge-graph .workspace-grid.with-detail { grid-template-columns: 210px minmax(0, 1fr) 320px; }.knowledge-graph .knowledge-toolbar { left: 240px; }.library-table th:nth-child(3), .library-table td:nth-child(3) { display: none; } }
@media (max-width: 1000px) { .knowledge-product .workspace-grid, .knowledge-product .workspace-grid.with-detail, .knowledge-graph .workspace-grid.with-detail { grid-template-columns: 200px minmax(0, 1fr); }.knowledge-product :deep(.item-editor) { position: fixed; inset: 100px 20px 25px 108px; z-index: 25; background: var(--surface); display: block; overflow-y: auto; margin: 0; }.knowledge-graph .knowledge-toolbar { position: static; }.knowledge-graph .knowledge-stage { padding-top: 0; } }
@media (max-width: 600px) { .knowledge-product .workspace-grid, .knowledge-product .workspace-grid.with-detail { grid-template-columns: 1fr; }.knowledge-product .knowledge-catalog { max-height: none; }.knowledge-library .knowledge-catalog { display: none; }.knowledge-graph .knowledge-catalog { max-height: 260px; overflow-y: auto; }.knowledge-graph .knowledge-stage { min-height: 600px; }.knowledge-product :deep(.item-editor) { inset: 80px 12px 90px; }.library-table-filters > span { display: none; }.library-table td, .library-table th { padding: 12px 9px; font-size: 14px; }.library-table td > button { font-size: 16px; }.library-table th:last-child, .library-table td:last-child { display: none; } }
</style>
