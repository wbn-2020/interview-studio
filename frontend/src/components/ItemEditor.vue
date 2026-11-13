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
import type { ConceptMergeResolution } from '../api/knowledgeMerge'

const props = defineProps<{ id: string; topics: Topic[]; graph?: GraphResponse }>()
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
const tab = ref<'edit' | 'preview' | 'versions' | 'relations' | 'organization' | 'references' | 'sources'>('edit')
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
watch(() => route.query.version, (value, previousValue) => {
  if (typeof value === 'string') tab.value = 'versions'
  else if (typeof previousValue === 'string' && tab.value === 'versions') tab.value = 'preview'
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
  <section class="item-editor" aria-label="知识详情">
    <div v-if="itemQuery.isPending.value && !current" class="loading-block" role="status">正在读取正文…</div>
    <div v-else-if="!current" class="notice error" role="alert">{{ errorText(itemQuery.error.value) }}<NButton text @click="itemQuery.refetch()">重试读取</NButton></div>
    <template v-if="current">
      <header class="detail-header"><div><span class="section-label">{{ current.kind === 'CONCEPT' ? '知识概念' : '独立笔记' }} · v{{ current.versionNumber }}</span><h2>{{ current.title }}</h2></div><button class="icon-button" aria-label="关闭知识详情" @click="emit('close')">×</button></header>
      <div class="save-state" aria-live="polite"><span :class="{ 'unsaved': dirty || unpublished }">{{ writer.busy.value ? '正在保存…' : dirty ? '有本地修改，尚未保存' : unpublished ? '草稿已保存，尚未发布' : '与发布版本一致' }}</span><span v-if="current.status === 'ARCHIVED'" class="status-pill">已归档</span></div>
      <p v-if="mergeInfo.data.value?.path.length" class="notice">此概念已合并，当前仍展示原身份和原文。<NButton text @click="emit('open', mergeInfo.data.value!.resolvedId)">打开合并目标</NButton></p>
      <p v-if="mergeInfo.error.value" class="notice error">{{ errorText(mergeInfo.error.value) }}</p>
      <RouterLink v-if="current.kind === 'CONCEPT' && current.status === 'ACTIVE'" class="text-link" :to="{ name: 'knowledge-merge', query: { source: id } }">预览与其他概念合并</RouterLink>
      <KnowledgeMarkdownExport v-if="tab !== 'versions'" :item-id="id" :version-id="current.published.id" :version-number="current.published.number"
        :enabled="readable && !editorLocked && current.id === id && current.published.id === current.currentVersionId" />
      <div v-if="tab !== 'versions'" class="row-actions"><NButton size="small" :disabled="!store.sessionActive || editorLocked || dirty || unpublished || current.status !== 'ACTIVE'" @click="createMemoryCard">从已发布 v{{ current.published.number }} 创建回忆卡</NButton><NButton size="small" :disabled="!cardDraftReady" @click="createAiMemoryCard">从此发布版生成 AI 回忆卡候选</NButton><span class="field-help">手工创建先选择题面和参考；AI 生成先核对完整知识正文与外发清单，采用前都保留这一版依据。</span></div>
      <div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" size="small" @click="writer.retry">用原请求重试</NButton><template v-if="writer.error.value instanceof ApiError && writer.error.value.status === 409"><NButton size="small" @click="compareServer">查看服务器副本</NButton><NButton size="small" :disabled="editorLocked || !title.trim()" @click="saveAsNote">将本地内容另存笔记</NButton></template></div>
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
      <div class="detail-tabs" role="tablist" aria-label="知识详情内容">
        <button v-for="option in ([['edit','编辑'],['preview','阅读'],['organization','整理'],['sources','资料来源'],['versions','版本'],['relations','关联'],['references','引用影响']] as const)" :key="option[0]" role="tab" :aria-selected="tab === option[0]" :class="{active: tab === option[0]}" @click="tab = option[0]">{{ option[1] }}</button>
      </div>
      <div v-show="tab === 'edit'" class="editor-fields">
        <label :for="`title-${id}`">标题</label><NInput :id="`title-${id}`" v-model:value="title" :maxlength="200" :disabled="!editable" placeholder="给这次思考一个名字" />
        <label>正文 <span class="muted">Markdown · {{ body.length }}/100000</span></label><MarkdownEditor v-model="body" :disabled="!editable" />
        <NButton size="small" :disabled="!editable" @click="referencePicker = !referencePicker">选择知识并插入正文引用</NButton><KnowledgePicker v-if="referencePicker" :disabled="!editable" include-archived @choose="insertReference" />
        <div class="editor-actions"><NButton type="primary" :disabled="!editable || !textDirty || !title.trim() || body.length > 100000" :loading="writer.busy.value" @click="saveDraft">保存草稿</NButton><NButton :disabled="!editable || dirty || !unpublished" @click="publish">发布新版本</NButton></div>
        <p class="field-help">保存草稿保留编辑进度；发布后产生可引用的新版本，历史原文保持不变。</p>
        <label :for="`topics-${id}`">所属主题</label><NSelect :id="`topics-${id}`" v-model:value="topicIds" multiple :options="topicOptions" :disabled="!editable" :max-tag-count="4" placeholder="可属于多个主题，也可以暂不归类" />
        <NButton size="small" :disabled="!editable || !topicsDirty || topicIds.length > 20" @click="saveTopics">保存主题</NButton>
        <p v-if="sourceState.dirty" class="field-help">请先保存或明确放弃资料来源修改，再保存正文或变更此知识版本。</p>
        <div class="secondary-actions"><span class="muted">服务器保存于 {{ formatDate(current.draft.savedAt) }}</span><NButton quaternary size="small" :disabled="editorLocked || dirty || sourceState.dirty || (current.status === 'ACTIVE' && unpublished)" @click="changeStatus">{{ current.status === 'ACTIVE' ? '归档' : '恢复' }}</NButton></div>
      </div>
      <div v-if="tab === 'preview'" class="reading-panel"><p class="field-help">当前编辑内容预览{{ dirty ? ' · 含尚未保存的修改' : unpublished ? ' · 尚未发布' : '' }}</p><h2>{{ title }}</h2><MarkdownPreview :value="body" /><AiKnowledgeOrigins :item-id="id" :version-id="current.published.id" :version-number="current.published.number" :editing="textDirty || unpublished" /></div>
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
    </template>
  </section>
</template>
