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
const KnowledgeGraph = defineAsyncComponent(() => import('../components/KnowledgeGraph.vue'))
const ItemEditor = defineAsyncComponent(() => import('../components/ItemEditor.vue'))
const MarkdownEditor = defineAsyncComponent(() => import('../components/MarkdownEditor.vue'))
const KnowledgeSearch = defineAsyncComponent(() => import('../components/KnowledgeSearch.vue'))
const KnowledgeTextImport = defineAsyncComponent(() => import('../components/KnowledgeTextImport.vue'))
const KnowledgeImportRecords = defineAsyncComponent(() => import('../components/KnowledgeImportRecords.vue'))
const KnowledgeTextImportBatch = defineAsyncComponent(() => import('../components/KnowledgeTextImportBatch.vue'))

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
function openItem(id: string) { void router.push({ name: 'knowledge', query: { ...route.query, item: id, version: undefined, lineStart: undefined, lineEnd: undefined } }) }
function openTarget(target: OpenKnowledge) { void router.push({ name: 'knowledge', query: { ...route.query, item: target.id, version: target.versionId, lineStart: target.lineStart, lineEnd: target.lineEnd } }) }
function closeItem() { void router.push({ name: 'knowledge', query: { ...route.query, item: undefined, version: undefined, lineStart: undefined, lineEnd: undefined } }) }
function changeMode(value: 'graph' | 'list' | 'search') {
  if (mode.value === value) return
  void router.push({ name: 'knowledge', query: { ...route.query, view: value } })
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
  <header class="page-heading"><div><p class="section-label">持续积累，也不断连接</p><h1>知识空间</h1><p class="muted">把概念放进星图，把思考写进笔记。</p></div><div class="row-actions"><RouterLink class="text-link" :to="{ name: 'memory-cards' }">回忆卡</RouterLink><NButton :disabled="!createReadable || createWriter.locked.value" @click="importHistoryOpen = true">导入记录</NButton><NButton :disabled="!createReadable || createWriter.locked.value" @click="batchImportOpen = true">多文件导入</NButton><NButton :disabled="!createReadable || createWriter.locked.value" @click="openCreate('NOTE', true)">导入 Markdown／TXT</NButton><NButton @click="openCreate('NOTE')">写笔记</NButton><NButton type="primary" @click="openCreate('CONCEPT')">＋ 新建概念</NButton></div></header>
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
