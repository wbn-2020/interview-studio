<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import type { ItemDetail, GraphResponse, Schema, Topic } from '../api/client'
import type { OpenKnowledge } from '../api/knowledge'
import { queryClient } from '../api/queryClient'
import { useCommand } from '../composables/useCommand'
import { useRecentVisit } from '../composables/useRecentVisit'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import MarkdownEditor from './MarkdownEditor.vue'
import MarkdownPreview from './MarkdownPreview.vue'
import ItemOrganization from './ItemOrganization.vue'
import ConnectionsPanel from './ConnectionsPanel.vue'
import KnowledgePicker from './KnowledgePicker.vue'
import VersionHistory from './VersionHistory.vue'
import AiKnowledgeOrigins from './AiKnowledgeOrigins.vue'
import KnowledgeReferences from './KnowledgeReferences.vue'
import KnowledgeRevalidation from './KnowledgeRevalidation.vue'
import PreparationKnowledgeImpacts from './PreparationKnowledgeImpacts.vue'
import MaterialKnowledgeImpacts from './MaterialKnowledgeImpacts.vue'
import KnowledgeMarkdownExport from './KnowledgeMarkdownExport.vue'
import KnowledgeSources from './KnowledgeSources.vue'
import NoteContextSidebar from './NoteContextSidebar.vue'
import type { ConceptMergeResolution } from '../api/knowledgeMerge'
import RIcon from '../replica/RIcon.vue'

const props = defineProps<{ id: string; topics: Topic[]; graph?: GraphResponse; compact?: boolean }>()
const emit = defineEmits<{ changed: []; open: [id: string]; close: []; loaded: [kind: 'NOTE' | 'CONCEPT'] }>()
const writer = useCommand()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const route = useRoute()
const router = useRouter()
const current = ref<ItemDetail>()
const title = ref('')
const body = ref('')
const topicIds = ref<string[]>([])
type EditorTab = 'edit' | 'preview' | 'versions' | 'relations' | 'organization' | 'references' | 'sources'
const editorTabs: EditorTab[] = ['edit', 'preview', 'versions', 'relations', 'organization', 'references', 'sources']
const tab = ref<EditorTab>('edit')
const referencePicker = ref(false)
const revalidationState = ref({ dirty: false, locked: false })
const preparationImpactState = ref({ dirty: false, locked: false })
const materialImpactState = ref({ dirty: false, locked: false })
const sourceState = ref({ dirty: false, locked: false })
const editorLocked = computed(() => writer.locked.value || revalidationState.value.locked
  || preparationImpactState.value.locked || materialImpactState.value.locked || sourceState.value.locked)
const savedMessage = ref('')
const serverCopy = ref<ItemDetail>()
const serverCopyError = ref('')
const separateCopy = ref<ItemDetail>()
const targetSearch = ref('')
const targetId = ref<string | null>(null)
const relationType = ref<Schema['CreateRelationRequest']['type']>('RELATED')
const itemQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'item', props.id]), enabled: computed(() => store.sessionActive), queryFn: () => read<ItemDetail>(`/knowledge/items/${props.id}`) })
const targetsQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'targets', targetSearch.value]), enabled: computed(() => store.sessionActive && tab.value === 'relations'), queryFn: () => read<Schema['ItemPage']>(`/knowledge/items?kind=CONCEPT&limit=100&q=${encodeURIComponent(targetSearch.value)}`) })
const textDirty = computed(() => Boolean(current.value) && (title.value !== current.value!.draft.title || body.value !== current.value!.draft.body))
const topicsDirty = computed(() => Boolean(current.value) && [...topicIds.value].sort().join() !== [...current.value!.topicIds].sort().join())
const dirty = computed(() => textDirty.value || topicsDirty.value)
const unpublished = computed(() => Boolean(current.value) && (current.value!.draft.title !== current.value!.published.title || current.value!.draft.body !== current.value!.published.body))
const readable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const mergeInfo = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'merge', props.id]),
  enabled: computed(() => readable.value && current.value?.kind === 'CONCEPT' && current.value?.status === 'ARCHIVED'),
  queryFn: () => read<ConceptMergeResolution>(`/knowledge/items/${props.id}/merge`) })
const recordRecentVisit = useRecentVisit('KNOWLEDGE', () => props.id, readable)
const editable = computed(() => current.value?.status === 'ACTIVE' && !editorLocked.value && !sourceState.value.dirty)
const cardDraftReady = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !editorLocked.value && !dirty.value && !unpublished.value && tab.value !== 'versions'
  && current.value?.status === 'ACTIVE' && current.value.id === props.id && current.value.published.id === current.value.currentVersionId)
const topicOptions = computed(() => props.topics.map(topic => ({ label: topic.name, value: topic.id })))
const linkedRelations = computed(() => props.graph?.edges.filter(edge => edge.sourceId === props.id || edge.targetId === props.id) ?? [])
const relatedConcepts = computed(() => {
  const ids = new Set(linkedRelations.value.map(edge => edge.sourceId === props.id ? edge.targetId : edge.sourceId))
  return props.graph?.nodes.filter(node => ids.has(node.id)) ?? []
})
function openEditor(panel: EditorTab = 'edit') { void router.push({ name: 'knowledge', query: { ...route.query, view: 'list', edit: '1', panel } }) }
const relationOptions = [ { label: '相关（无方向）', value: 'RELATED' }, { label: '对比（无方向）', value: 'CONTRASTS' }, { label: '前置于 →', value: 'PRECEDES' }, { label: '组成部分 →', value: 'PART_OF' }, { label: '应用于 →', value: 'APPLIED_IN' } ]
const targetOptions = computed(() => targetsQuery.data.value?.items.filter(item => item.id !== props.id).map(item => ({ label: `${item.title} · ${item.id.slice(0, 6)}`, value: item.id })) ?? [])

function accept(value: ItemDetail, keepText = false, keepTopics = false) {
  const previous = current.value
  const retainDraftBase = keepText && textDirty.value && previous
  const retainMetadataBase = keepTopics && topicsDirty.value && previous
  // 部分保存不能提升另一处脏编辑的 CAS 基线，否则会把他处的新修改当成已读。
  current.value = {
    ...value,
    ...(retainDraftBase ? { draft: previous.draft } : {}),
    ...(retainMetadataBase ? { revision: previous.revision, topicIds: previous.topicIds } : {}),
  }
  if (!retainDraftBase) { title.value = value.draft.title; body.value = value.draft.body }
  if (!retainMetadataBase) topicIds.value = [...value.topicIds]
}
watch(itemQuery.data, value => {
  // 后台重新读取不覆盖尚未保存的编辑，冲突必须由用户显式处理。
  if (value && (!current.value || (!dirty.value && !sourceState.value.dirty && !editorLocked.value))) {
    accept(value)
    recordRecentVisit(value.id)
  }
  if (value) emit('loaded', value.kind)
}, { immediate: true })
watch([() => route.query.panel, () => route.query.version], ([panel, version]) => {
  tab.value = typeof version === 'string' ? 'versions'
    : typeof panel === 'string' && editorTabs.includes(panel as EditorTab) ? panel as EditorTab : 'edit'
}, { immediate: true })
watch([dirty, editorLocked, revalidationState, preparationImpactState, materialImpactState, sourceState],
  ([value, locked, revalidation, preparation, material, sources]) => store.changes.set('editor', {
  dirty: value || revalidation.dirty || preparation.dirty || material.dirty || sources.dirty, locked,
}), { immediate: true, flush: 'sync' })
onUnmounted(() => { store.changes.delete('editor') })

function update(value: ItemDetail, message: string, keepText = false, keepTopics = false) {
  accept(value, keepText, keepTopics)
  queryClient.setQueryData(['knowledge', store.sessionEpoch, 'item', props.id], value)
  savedMessage.value = message
  serverCopy.value = undefined
  emit('changed')
}
function saveDraft() {
  if (!editable.value || !current.value || !title.value.trim() || body.value.length > 100000) return
  void writer.run<ItemDetail>(`/knowledge/items/${props.id}/draft`, 'PUT', { expectedRevision: current.value.draft.revision, title: title.value, body: body.value }, value => update(value, '草稿已保存到服务器', false, true))
}
function publish() {
  if (!editable.value || !current.value || dirty.value || sourceState.value.dirty) return
  void writer.run<ItemDetail>(`/knowledge/items/${props.id}/publish`, 'POST', { expectedRevision: current.value.draft.revision, expectedVersionNumber: current.value.versionNumber }, value => update(value, `已发布版本 ${value.versionNumber}`))
}
function saveTopics() {
  if (!editable.value || !current.value) return
  void writer.run<ItemDetail>(`/knowledge/items/${props.id}/topics`, 'PUT', { expectedRevision: current.value.revision, topicIds: topicIds.value }, value => update(value, '主题已保存', true))
}
function changeStatus() {
  if (!current.value || editorLocked.value || dirty.value || sourceState.value.dirty) return
  const action = current.value.status === 'ACTIVE' ? 'archive' : 'restore'
  if (action === 'archive' && !window.confirm('归档后将退出默认列表和星图，已有版本与关系继续保留。确定归档？')) return
  void writer.run<ItemDetail>(`/knowledge/items/${props.id}/${action}`, 'POST', { expectedRevision: current.value.revision }, value => update(value, action === 'archive' ? '已归档' : '已恢复'))
}
async function compareServer() {
  serverCopyError.value = ''
  try { serverCopy.value = await read<ItemDetail>(`/knowledge/items/${props.id}`) }
  catch (reason) { serverCopyError.value = errorText(reason) }
}
function loadServer() {
  // 未决保存仍依赖原请求恢复，采用对照副本不能清掉它的错误与重试入口。
  if (!readable.value || editorLocked.value || sourceState.value.dirty) return
  if (serverCopy.value && window.confirm('用服务器草稿替换当前输入？尚未保存的本地修改将丢弃。')) {
    accept(serverCopy.value)
    writer.error.value = undefined
    serverCopy.value = undefined
    savedMessage.value = '已读取服务器草稿'
  }
}
function saveAsNote() {
  if (editorLocked.value || !readable.value) return
  void writer.run<ItemDetail>('/knowledge/items', 'POST', { kind: 'NOTE', title: title.value, body: body.value, topicIds: topicIds.value }, value => {
    // 另存创建新身份；当前路由可能因另一处脏编辑拒绝跳转，不能把新修订套到旧对象上。
    separateCopy.value = value
    emit('changed')
  })
}
function addRelation() {
  if (!editable.value || !targetId.value) return
  void writer.run(`/knowledge/relations`, 'POST', { sourceId: props.id, targetId: targetId.value, type: relationType.value }, () => { targetId.value = null; savedMessage.value = '关系已保存'; emit('changed') })
}
function formatDate(value: string) { return new Date(value).toLocaleString('zh-CN') }
function selectPanel(panel: EditorTab) {
  void router.push({ query: { ...route.query, panel, version: undefined, lineStart: undefined, lineEnd: undefined } })
}
function openTarget(target: OpenKnowledge) {
  void router.push({ name: 'knowledge', query: { ...route.query, item: target.id, version: target.versionId, lineStart: target.lineStart, lineEnd: target.lineEnd } })
}
function createMemoryCard() {
  if (!store.sessionActive || editorLocked.value || dirty.value || unpublished.value || current.value?.status !== 'ACTIVE' || tab.value === 'versions') return
  void router.push({ name: 'memory-cards', query: { create: '1', sourceItemId: current.value.id, sourceVersionId: current.value.published.id } })
}
function createAiMemoryCard() {
  if (!cardDraftReady.value || !current.value) return
  void router.push({ name: 'ai', query: { create: '1', purpose: 'MEMORY_CARD_DRAFT', sourceItemId: current.value.id, sourceVersionId: current.value.published.id } })
}
function insertReference(item: Schema['ItemSummary']) {
  if (!editable.value) return
  const label = item.title.replace(/[\\[\]()*_`]/g, '\\$&').replace(/\r?\n/g, ' ')
  body.value += `${body.value.endsWith('\n') || !body.value ? '' : '\n'}[${label}](knowledge:${item.id})\n`
  referencePicker.value = false
}
</script>

<template>
  <section class="item-editor" :class="{ 'item-note-editor': current?.kind === 'NOTE' && !compact }" aria-label="知识详情">
    <div v-if="itemQuery.isPending.value && !current" class="loading-block" role="status">正在读取正文…</div>
    <div v-else-if="!current" class="notice error" role="alert">{{ errorText(itemQuery.error.value) }}<NButton text @click="itemQuery.refetch()">重试读取</NButton></div>
    <template v-if="current">
      <div v-if="compact" class="item-overview">
        <header class="item-overview-heading"><RIcon v-if="current.kind === 'CONCEPT'" name="sparkles" :size="42" /><h2>{{ current.published.title }}</h2><button class="studio-icon-button" title="打开完整详情" aria-label="打开完整详情" @click="router.push({ name: 'knowledge', query: { view: 'detail', item: id } })"><RIcon name="external-link" :size="25" /></button></header>
        <p class="item-overview-meta">{{ current.kind === 'CONCEPT' ? '概念' : '笔记' }} · v{{ current.published.number }}<span v-if="current.kind === 'CONCEPT' && graph"> · 当前图中 {{ linkedRelations.length }} 条关系</span></p>
        <div class="detail-tabs item-overview-tabs" role="group" aria-label="知识概览"><button class="active" @click="openEditor('preview')">概览</button><button @click="openEditor()">笔记</button><button @click="openEditor('relations')">关系</button><button @click="openEditor('sources')">证据</button></div>
        <section class="item-overview-body"><h3 v-if="current.kind === 'CONCEPT'"><RIcon name="file-text" />定义</h3><MarkdownPreview :value="current.published.body || '尚未填写正文。'" /></section>
        <section v-if="current.topicIds.length" class="item-overview-section"><h3>所属主题</h3><div class="item-overview-tags"><span v-for="topicId in current.topicIds" :key="topicId">{{ topics.find(topic => topic.id === topicId)?.name ?? '主题' }}</span></div></section>
        <section v-if="relatedConcepts.length" class="item-overview-section"><h3>关联概念</h3><div class="item-overview-tags"><button v-for="concept in relatedConcepts" :key="concept.id" @click="emit('open', concept.id)">{{ concept.title }}</button></div></section>
        <div class="item-overview-footer"><NButton type="primary" block @click="openEditor()"><RIcon name="external-link" />{{ current.kind === 'NOTE' ? '打开笔记' : '编辑知识' }}</NButton><NButton block :disabled="!cardDraftReady" @click="createMemoryCard"><RIcon name="credit-card" />创建回忆卡</NButton><RouterLink v-if="current.kind === 'CONCEPT' && current.status === 'ACTIVE'" class="text-link" :to="{ name: 'knowledge-merge', query: { source: id } }">合并重复概念</RouterLink></div>
      </div>
      <!-- 编辑器仍挂载，保持来源复核、草稿和未知写入的保护状态。 -->
      <div v-show="!compact" class="item-full-editor">
      <div class="item-document-heading">
      <header class="detail-header"><div><span class="section-label">{{ current.kind === 'CONCEPT' ? '知识概念' : '独立笔记' }} · v{{ current.versionNumber }}</span><h2>{{ current.kind === 'NOTE' ? title : current.title }}</h2></div><button class="icon-button" aria-label="关闭知识详情" @click="emit('close')">×</button></header>
      <div class="save-state" aria-live="polite"><template v-if="current.kind === 'NOTE'"><span class="note-kind"><RIcon name="file-text" :size="23" />笔记 v{{ current.published.number }}</span><span>·</span></template><RIcon v-if="current.kind === 'NOTE' && !dirty && !unpublished && !writer.busy.value" class="note-saved-check" name="check" :size="24" /><span :class="{ 'unsaved': dirty || unpublished }">{{ writer.busy.value ? '正在保存…' : dirty ? '有本地修改，尚未保存' : unpublished ? '草稿已保存，尚未发布' : current.kind === 'NOTE' ? `已保存 ${new Date(current.draft.savedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })}` : '与发布版本一致' }}</span><span v-if="current.status === 'ARCHIVED'" class="status-pill">已归档</span></div>
      <div class="detail-tabs item-document-tabs" role="tablist" aria-label="知识详情内容">
        <button v-for="option in (current.kind === 'NOTE' ? [['edit','编辑'],['preview','阅读']] as const : [['edit','编辑'],['preview','阅读'],['organization','整理'],['sources','资料来源'],['versions','版本'],['relations','关联'],['references','引用影响']] as const)" :key="option[0]" role="tab" :aria-selected="tab === option[0]" :class="{active: tab === option[0]}" @click="selectPanel(option[0])">{{ option[1] }}</button>
      </div>
      <button v-if="current.kind === 'NOTE'" class="note-history studio-icon-button" title="版本历史" aria-label="版本历史" :aria-pressed="tab === 'versions'" @click="selectPanel('versions')"><RIcon name="history" :size="28" /></button>
      </div>
      <div class="item-document-layout">
      <div class="item-document-main">
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value && !writer.busy.value" text @click="writer.retry">用原请求重试</NButton><NButton v-if="writer.error.value instanceof ApiError && writer.error.value.status === 409" text @click="compareServer">查看服务器副本</NButton></p>
      <details class="item-version-operations" :open="editorLocked || revalidationState.dirty || preparationImpactState.dirty || materialImpactState.dirty || !!serverCopy || !!separateCopy || undefined"><summary :aria-label="current.kind === 'NOTE' ? '笔记更多操作' : undefined"><RIcon v-if="current.kind === 'NOTE'" name="ellipsis" :size="25" /><span>版本操作与来源检查</span></summary>
      <template v-if="current.kind === 'NOTE'">
        <div class="note-properties">
          <label :for="`note-title-${id}`">标题</label><NInput :id="`note-title-${id}`" v-model:value="title" :maxlength="200" :disabled="!editable" />
          <div class="editor-actions"><NButton type="primary" :disabled="!editable || !textDirty || !title.trim() || body.length > 100000" :loading="writer.busy.value" @click="saveDraft">保存草稿</NButton><NButton :disabled="!editable || dirty || !unpublished" @click="publish">发布新版本</NButton></div>
          <label :for="`note-topics-${id}`">所属主题</label><NSelect :id="`note-topics-${id}`" v-model:value="topicIds" multiple :options="topicOptions" :disabled="!editable" :max-tag-count="4" />
          <NButton size="small" :disabled="!editable || !topicsDirty || topicIds.length > 20" @click="saveTopics">保存主题</NButton>
          <div class="row-actions"><NButton size="small" @click="selectPanel('organization')">整理笔记</NButton><NButton size="small" @click="selectPanel('references')">引用影响</NButton><NButton size="small" :disabled="!editable" @click="referencePicker = !referencePicker">插入知识引用</NButton><NButton size="small" :disabled="editorLocked || dirty || sourceState.dirty || (current.status === 'ACTIVE' && unpublished)" @click="changeStatus">{{ current.status === 'ACTIVE' ? '归档' : '恢复' }}</NButton></div>
          <KnowledgePicker v-if="referencePicker" :disabled="!editable" include-archived @choose="insertReference" />
          <p class="field-help">草稿与发布版本分别保存；发布后产生可引用的新版本，历史原文保留。</p>
        </div>
      </template>
      <p v-if="mergeInfo.data.value?.path.length" class="notice">此概念已合并，当前仍展示原身份和原文。<NButton text @click="emit('open', mergeInfo.data.value!.resolvedId)">打开合并目标</NButton></p>
      <p v-if="mergeInfo.error.value" class="notice error">{{ errorText(mergeInfo.error.value) }}</p>
      <RouterLink v-if="current.kind === 'CONCEPT' && current.status === 'ACTIVE'" class="text-link" :to="{ name: 'knowledge-merge', query: { source: id } }">预览与其他概念合并</RouterLink>
      <KnowledgeMarkdownExport v-if="tab !== 'versions'" :item-id="id" :version-id="current.published.id" :version-number="current.published.number"
        :enabled="readable && !editorLocked && current.id === id && current.published.id === current.currentVersionId" />
      <div v-if="tab !== 'versions'" class="row-actions"><NButton size="small" :disabled="!store.sessionActive || editorLocked || dirty || unpublished || current.status !== 'ACTIVE'" @click="createMemoryCard">从已发布 v{{ current.published.number }} 创建回忆卡</NButton><NButton size="small" :disabled="!cardDraftReady" @click="createAiMemoryCard">从此发布版生成 AI 回忆卡候选</NButton><span class="field-help">手工创建先选择题面和参考；AI 生成先核对完整知识正文与外发清单，采用前都保留这一版依据。</span></div>
      <NButton v-if="writer.error.value instanceof ApiError && writer.error.value.status === 409" size="small" :disabled="editorLocked || !title.trim()" @click="saveAsNote">将本地内容另存笔记</NButton>
      <p v-if="savedMessage && !dirty" class="success-text" role="status">{{ savedMessage }}</p>
      <p v-if="separateCopy" class="notice" role="status">本地内容已另存为「{{ separateCopy.title }}」，当前对象仍保持原来的编辑状态。<NButton text size="small" @click="emit('open', separateCopy.id)">打开新笔记</NButton></p>
      <div v-if="serverCopy" class="comparison-panel"><h3>服务器草稿 · 修订 {{ serverCopy.draft.revision }}</h3><p>{{ serverCopy.draft.title }}</p><pre>{{ serverCopy.draft.body }}</pre><NButton size="small" :disabled="!readable || editorLocked" @click="loadServer">采用服务器草稿</NButton><NButton size="small" @click="serverCopy = undefined">保留本地输入</NButton></div>
      <p v-if="serverCopyError" class="notice error">{{ serverCopyError }}</p>
      <KnowledgeRevalidation :item-id="id" :current="current" :editing="dirty" :disabled="writer.locked.value || preparationImpactState.locked || materialImpactState.locked || tab === 'versions'" @state="revalidationState = $event" @changed="emit('changed')" />
      <PreparationKnowledgeImpacts :item-id="id" context="KNOWLEDGE" :current="current" :editing="dirty"
        :disabled="writer.locked.value || revalidationState.locked || materialImpactState.locked || tab === 'versions'"
        @state="preparationImpactState = $event" @changed="emit('changed')" />
      <MaterialKnowledgeImpacts :item-id="id" context="KNOWLEDGE" :current="current" :editing="dirty"
        :disabled="writer.locked.value || revalidationState.locked || preparationImpactState.locked || tab === 'versions'"
        @state="materialImpactState = $event" @changed="emit('changed')" />
      </details>
      <div v-show="tab === 'edit'" class="editor-fields">
        <template v-if="current.kind !== 'NOTE'">
        <label :for="`title-${id}`">标题</label><NInput :id="`title-${id}`" v-model:value="title" :maxlength="200" :disabled="!editable" placeholder="给这次思考一个名字" />
        </template>
        <label class="sr-only">正文</label><MarkdownEditor v-model="body" :disabled="!editable" :formatted="current.kind === 'NOTE'" toolbar />
        <template v-if="current.kind !== 'NOTE'">
        <NButton size="small" :disabled="!editable" @click="referencePicker = !referencePicker">选择知识并插入正文引用</NButton><KnowledgePicker v-if="referencePicker" :disabled="!editable" include-archived @choose="insertReference" />
        <div class="editor-actions"><NButton type="primary" :disabled="!editable || !textDirty || !title.trim() || body.length > 100000" :loading="writer.busy.value" @click="saveDraft">保存草稿</NButton><NButton :disabled="!editable || dirty || !unpublished" @click="publish">发布新版本</NButton></div>
        <p class="field-help">保存草稿保留编辑进度；发布后产生可引用的新版本，历史原文保持不变。</p>
        <label :for="`topics-${id}`">所属主题</label><NSelect :id="`topics-${id}`" v-model:value="topicIds" multiple :options="topicOptions" :disabled="!editable" :max-tag-count="4" placeholder="可属于多个主题，也可以暂不归类" />
        <NButton size="small" :disabled="!editable || !topicsDirty || topicIds.length > 20" @click="saveTopics">保存主题</NButton>
        <p v-if="sourceState.dirty" class="field-help">请先保存或明确放弃资料来源修改，再保存正文或变更此知识版本。</p>
        <div class="secondary-actions"><span class="muted">服务器保存于 {{ formatDate(current.draft.savedAt) }}</span><NButton quaternary size="small" :disabled="editorLocked || dirty || sourceState.dirty || (current.status === 'ACTIVE' && unpublished)" @click="changeStatus">{{ current.status === 'ACTIVE' ? '归档' : '恢复' }}</NButton></div>
        </template>
        <div v-if="current.kind === 'NOTE' && (textDirty || unpublished)" class="note-draft-actions"><NButton type="primary" :disabled="!editable || !textDirty || !title.trim() || body.length > 100000" :loading="writer.busy.value" @click="saveDraft">保存草稿</NButton><NButton :disabled="!editable || dirty || !unpublished" @click="publish">发布新版本</NButton></div>
      </div>
      <div v-if="tab === 'preview'" class="reading-panel"><p v-if="dirty || unpublished" class="field-help">当前编辑内容预览{{ dirty ? ' · 含尚未保存的修改' : ' · 尚未发布' }}</p><h3 v-if="current.kind === 'CONCEPT'"><RIcon name="book-open" />定义</h3><MarkdownPreview :value="body" /><AiKnowledgeOrigins :item-id="id" :version-id="current.published.id" :version-number="current.published.number" :editing="textDirty || unpublished" /></div>
      <ItemOrganization v-show="tab === 'organization'" :id="id" :kind="current.kind" :active="current.status === 'ACTIVE'" @changed="emit('changed')" @open="emit('open', $event)" />
      <KnowledgeSources v-show="tab === 'sources'" :key="current.published.id" :item-id="id" :version-id="current.published.id"
        :version-number="current.published.number" :enabled="readable && !writer.locked.value" :active="current.status === 'ACTIVE'" @state="sourceState = $event" />
      <VersionHistory v-if="tab === 'versions'" :id="id" :published="current.published" />
      <KnowledgeReferences v-if="tab === 'references'" :item-id="id" :version-id="current.published.id" :version-number="current.published.number" :editing="textDirty || unpublished" />
      <div v-show="tab === 'relations'" class="relations-panel">
        <template v-if="current.kind === 'CONCEPT'">
          <p class="field-help">关系从当前概念出发。主题归属与语义关系分别保存。</p>
          <label>关系含义</label><NSelect v-model:value="relationType" :options="relationOptions" :disabled="!editable" />
          <label>另一个概念</label><NSelect v-model:value="targetId" filterable remote :options="targetOptions" :loading="targetsQuery.isFetching.value" :disabled="!editable" placeholder="按标题查找概念" @search="targetSearch = $event" />
          <p v-if="targetsQuery.error.value" class="notice error">{{ errorText(targetsQuery.error.value) }}</p>
          <NButton :disabled="!editable || !targetId" @click="addRelation">添加关系</NButton>
        </template>
        <ConnectionsPanel :id="id" :can-edit="current.status === 'ACTIVE' && !editorLocked" @open="openTarget" @changed="emit('changed')" />
      </div>
      </div>
      <NoteContextSidebar v-if="current.kind === 'NOTE'" :item-id="id" :version-id="current.published.id" :version-number="current.published.number" :enabled="readable && !compact" @open="openTarget" @panel="selectPanel" />
      <aside v-else class="item-document-aside" aria-label="知识关联与版本">
        <section><h3>关联概念<span v-if="graph">{{ relatedConcepts.length }}</span></h3><div class="item-overview-tags"><button v-for="concept in relatedConcepts" :key="concept.id" @click="emit('open', concept.id)">{{ concept.title }}</button></div><p v-if="!relatedConcepts.length" class="field-help">{{ graph ? '当前图窗口没有相邻概念。' : '当前没有读取关系图窗口。' }}</p><NButton text @click="tab = 'relations'">查看完整关联<RIcon name="arrow-right" :size="18" /></NButton></section>
        <section><h3>所属主题</h3><div class="item-overview-tags"><span v-for="topicId in current.topicIds" :key="topicId">{{ topics.find(topic => topic.id === topicId)?.name ?? '主题' }}</span></div><p v-if="!current.topicIds.length" class="field-help">未归类</p><NButton text @click="tab = 'organization'">整理知识<RIcon name="chevron-right" :size="18" /></NButton></section>
        <section><h3>引用与来源</h3><button class="item-aside-link" @click="tab = 'references'"><RIcon name="link" :size="23" />引用影响<RIcon name="chevron-right" :size="20" /></button><button class="item-aside-link" @click="tab = 'sources'"><RIcon name="file-text" :size="23" />资料来源<RIcon name="chevron-right" :size="20" /></button></section>
        <section><h3>版本</h3><button class="item-current-version" @click="tab = 'versions'"><i />v{{ current.published.number }} 当前发布版</button><time>{{ formatDate(current.published.createdAt) }}</time><NButton block :disabled="!cardDraftReady" @click="createMemoryCard"><RIcon name="credit-card" :size="22" />创建回忆卡</NButton></section>
      </aside>
      </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.item-overview { display: grid; gap: 22px; }.item-overview-heading { display: flex; gap: 15px; align-items: start; }.item-overview-heading > svg { color: #f5c572; filter: drop-shadow(0 0 8px #eaa64199); flex: none; }.item-overview-heading h2 { font-size: 28px; flex: 1; line-height: 1.45; overflow-wrap: anywhere; }.item-overview-heading > button { width: 30px; height: 35px; }.item-overview-meta { color: var(--muted); font-size: 17px; margin-top: -12px; }.item-overview-tabs { margin: 0 -8px; display: flex; }.item-overview-tabs > button { font-size: 18px !important; padding: 4px 16px !important; flex: 1; }.item-overview-body { max-height: 270px; overflow: auto; font-size: 19px; line-height: 1.8; }.item-overview-body h3 { display: flex; align-items: center; gap: 10px; margin-bottom: 13px; font-size: 22px; }.item-overview-body :deep(.markdown-preview) { font-size: 19px; }.item-overview-section { border-top: 1px solid var(--line); padding-top: 22px; }.item-overview-section h3 { font-size: 21px; margin-bottom: 15px; }.item-overview-tags { display: flex; flex-wrap: wrap; gap: 10px; }.item-overview-tags > :is(span, button) { font-size: 17px; padding: 5px 14px; border-radius: 22px; border: 1px solid #6da49b44; color: #b1ded6; background: #345e5680; }.item-overview-footer { display: grid; gap: 14px; padding-top: 8px; }.item-overview-footer :deep(.n-button__content) { gap: 10px; }.item-overview-footer a { text-align: center; font-size: 15px; }.item-full-editor { min-width: 0; }.item-full-editor .detail-tabs { overflow-x: auto; }.item-full-editor .editor-fields { gap: 13px; }.item-full-editor .detail-header h2 { font-size: 32px; }.item-full-editor .detail-tabs button { font-size: 17px; }.item-full-editor .editor-fields > label { font-size: 16px; }.item-full-editor .secondary-actions { font-size: 14px; }.item-full-editor .save-state { font-size: 15px; }
</style>

<style scoped>
.item-document-tabs { margin: 20px 0; width: fit-content; max-width: 100%; }.item-document-layout { display: grid; grid-template-columns: minmax(0,1fr) 270px; gap: 12px; align-items: start; }.item-document-main { min-width: 0; display: grid; align-content: start; gap: 16px; }.item-document-main .editor-fields { display: grid; border: 1px solid var(--line); border-radius: 18px; padding: 20px; background: var(--surface); }.item-version-operations { border: 1px solid var(--line); border-radius: 13px; padding: 12px 18px; font-size: 15px; }.item-version-operations > summary { cursor: pointer; color: var(--muted); }.item-version-operations[open] > :not(summary) { margin-top: 16px; }.item-document-main .reading-panel { padding: 26px; border: 1px solid var(--line); border-radius: 18px; background: var(--surface); min-height: 530px; }.reading-panel > h3 { display: flex; gap: 12px; align-items: center; margin: 0 0 18px; font-size: 25px; }.reading-panel :deep(.markdown-preview) { font-size: 21px; line-height: 1.85; }.item-document-main :deep(.with-toolbar) { margin: 0 -20px; border-block: 1px solid var(--line); }.item-document-aside { padding: 25px 20px; border: 1px solid var(--line); border-radius: 18px; background: var(--surface); min-height: 665px; display: grid; align-content: start; gap: 25px; min-width: 0; }.item-document-aside > section { display: grid; gap: 16px; padding-bottom: 24px; border-bottom: 1px solid var(--line); }.item-document-aside > section:last-child { padding-bottom: 0; border: 0; }.item-document-aside h3 { display: flex; align-items: center; gap: 14px; font-size: 22px; margin: 0; }.item-document-aside h3 span { font-size: 19px; font-weight: 400; color: var(--muted); }.item-document-aside time { font-size: 15px; color: var(--muted); }.item-aside-link { display: flex; gap: 13px; align-items: center; font-size: 18px; text-align: left; padding: 9px 0; }.item-aside-link > svg:last-child { margin-left: auto; }.item-current-version { display: flex; align-items: center; gap: 10px; color: var(--brand-strong); font-size: 18px; text-align: left; }.item-current-version i { width: 12px; height: 12px; border-radius: 50%; background: var(--brand); }
.item-document-heading { display: contents; }.item-note-editor .item-document-heading { position: relative; display: grid; grid-template-columns: minmax(0,1fr) auto 46px; column-gap: 24px; padding: 0 87px 18px 20px; }.item-note-editor .detail-header { grid-column: 1; grid-row: 1; }.item-note-editor .detail-header h2 { font-size: 42px; line-height: 1.3; margin: 0; }.item-note-editor .detail-header .section-label,.item-note-editor .detail-header > .icon-button { display: none; }.item-note-editor .save-state { grid-column: 1; grid-row: 2; margin: 9px 0 0; font-size: 20px; gap: 15px; min-height: 28px; }.note-kind { display: inline-flex; align-items: center; gap: 10px; }.note-saved-check { border-radius: 50%; padding: 4px; color: #16432f; background: #6fc894; flex: none; }.item-note-editor .item-document-tabs { grid-column: 2; grid-row: 1 / 3; align-self: start; margin: 15px 0 0; }.item-note-editor .note-history { grid-column: 3; grid-row: 1 / 3; margin-top: 15px; }.item-note-editor .item-document-main { position: static; gap: 0; }.item-note-editor .item-version-operations { position: absolute; top: 15px; right: 12px; z-index: 15; border: 0; padding: 0; border-radius: 14px; background: transparent; }.item-note-editor .item-version-operations > summary { display: grid; place-items: center; width: 46px; height: 46px; padding: 0; margin-left: auto; list-style: none; }.item-note-editor .item-version-operations > summary::-webkit-details-marker { display: none; }.item-note-editor .item-version-operations > summary > span { display: none; }.item-note-editor .item-version-operations[open] { width: min(520px,calc(100vw - 70px)); max-height: calc(100dvh - 195px); overflow: auto; padding: 0 18px 18px; border: 1px solid var(--line); background: var(--surface); box-shadow: 0 18px 45px #0006; }.note-properties { display: grid; gap: 12px; }.note-properties > label { font-size: 15px; color: var(--muted); }.item-note-editor .editor-fields { display: block; min-height: 705px; padding: 0; overflow: hidden; border-radius: 22px; background: linear-gradient(120deg,#25211c90,#18161280 70%); }.item-note-editor .editor-fields :deep(.with-toolbar) { margin: 0; border: 0; }.note-draft-actions { display: flex; gap: 12px; padding: 16px 24px; border-top: 1px solid var(--line); }.item-note-editor .item-document-main .reading-panel { min-height: 705px; border-radius: 22px; }
@media(max-width:1150px) { .item-document-layout { grid-template-columns: minmax(0,1fr); }.item-document-aside { min-height: 0; grid-template-columns: repeat(2,minmax(0,1fr)); }.item-document-aside > section { border-bottom: 0; } }
@media(max-width:1000px) { .item-note-editor .item-document-heading { padding: 0 50px 20px 0; grid-template-columns: minmax(0,1fr) 46px; }.item-note-editor .detail-header { grid-column: 1 / -1; }.item-note-editor .detail-header h2 { font-size: 32px; }.item-note-editor .save-state { grid-column: 1 / -1; font-size: 16px; flex-wrap: wrap; }.item-note-editor .item-document-tabs { grid-row: 3; grid-column: 1; justify-self: start; }.item-note-editor .note-history { grid-row: 3; grid-column: 2; }.item-note-editor .item-version-operations { top: 20px; right: 15px; } }
@media(max-width:600px) { .item-document-aside { grid-template-columns: minmax(0,1fr); }.item-document-main .reading-panel { padding: 22px; }.reading-panel :deep(.markdown-preview) { font-size: 18px; } }
</style>
