<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read, type ItemDetail, type ItemSummary } from '../api/client'
import { boardNodeReference, boardPayload, boardReferenceKey, boardReferenceLabels, type BoardDetail, type BoardHistoryPage, type BoardNode, type BoardPage, type BoardReference, type BoardReferenceKind, type BoardRelationType, type BoardSave, type BoardViewport } from '../api/boards'
import { exportBoardTransfer, type BoardTransferDraft } from '../api/boardTransfer'
import type { Question, QuestionPage } from '../api/bank'
import type { Attachment, AttachmentPage } from '../api/material'
import { queryClient } from '../api/queryClient'
import { useCommand } from '../composables/useCommand'
import { useRecentVisit } from '../composables/useRecentVisit'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import BoardCanvas from '../components/board/BoardCanvas.vue'
import KnowledgePicker from '../components/KnowledgePicker.vue'
import BoardTransfer from '../components/boards/BoardTransfer.vue'
import RIcon from '../replica/RIcon.vue'

const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const writer = useCommand()
const creator = useCommand()
const boardId = computed(() => typeof route.query.board === 'string' ? route.query.board : '')
const recordRecentVisit = useRecentVisit('BOARD', boardId)
const board = ref<BoardDetail>()
const baseline = ref('')
const cursor = ref<string>()
const selectedId = ref('')
const selectedEdgeId = ref('')
const picker = ref(false)
const referenceKind = ref<BoardReferenceKind>('KNOWLEDGE')
const referenceInput = ref(''), referenceSearch = ref(''), referenceCursor = ref<string>()
const selectingReference = ref(false)
const transferPending = ref(false)
const showCreate = ref(false)
const libraryOpen = ref(false)
const createTitle = ref('')
const createKind = ref<'MIND_MAP' | 'BOARD'>('MIND_MAP')
const message = ref('')
const localError = ref('')
const undoStack = ref<string[]>([])
const redoStack = ref<string[]>([])
const historyBefore = ref<number>()
const historyPreview = ref<BoardDetail>()
const serverCopy = ref<BoardDetail>()
const sourceId = ref<string | null>(null)
const targetId = ref<string | null>(null)
const linkLabel = ref('')
const conversion = ref<'text' | 'edge' | null>(null)
const convertTitle = ref('')
const relationType = ref<BoardRelationType>('RELATED')
const payload = computed(() => board.value ? JSON.stringify(boardPayload(board.value)) : '')
const dirty = computed(() => Boolean(board.value) && payload.value !== baseline.value)
const locked = computed(() => writer.locked.value || !store.sessionActive)
const referenceReadable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const selected = computed(() => board.value?.nodes.find(node => node.id === selectedId.value))
const selectedEdge = computed(() => board.value?.edges.find(edge => edge.id === selectedEdgeId.value))
const references = computed(() => new Map(board.value?.references.map(item => [boardReferenceKey(item.itemId, item.referenceKind), item]) ?? []))
const nodeOptions = computed(() => board.value?.nodes.map(node => ({ label: `${label(node)} · ${node.id.slice(0, 6)}`, value: node.id })) ?? [])
const parentOptions = computed(() => nodeOptions.value.filter(option => option.value !== selectedId.value))
const canConvertEdge = computed(() => {
  const edge = selectedEdge.value
  if (!edge || !board.value) return false
  return [edge.source, edge.target].every(id => {
    const node = board.value!.nodes.find(value => value.id === id)
    const source = node?.itemId ? references.value.get(boardReferenceKey(node.itemId, node.referenceKind)) : undefined
    return (node?.referenceKind ?? 'KNOWLEDGE') === 'KNOWLEDGE' && source?.kind === 'CONCEPT' && source.status === 'ACTIVE'
  })
})
const relationOptions = [{ label: '相关（无方向）', value: 'RELATED' }, { label: '对比（无方向）', value: 'CONTRASTS' }, { label: '前置于 →', value: 'PRECEDES' }, { label: '组成部分 →', value: 'PART_OF' }, { label: '应用于 →', value: 'APPLIED_IN' }]
const listQuery = useQuery({ queryKey: computed(() => ['boards', store.sessionEpoch, 'list', cursor.value]), enabled: computed(() => store.sessionActive),
  queryFn: () => read<BoardPage>(`/knowledge/boards?limit=20${cursor.value ? `&cursor=${cursor.value}` : ''}`) })
const boardQuery = useQuery({ queryKey: computed(() => ['boards', store.sessionEpoch, 'detail', boardId.value]), enabled: computed(() => store.sessionActive && Boolean(boardId.value)),
  queryFn: () => read<BoardDetail>(`/knowledge/boards/${boardId.value}`) })
const serverUpdateAvailable = computed(() => Boolean(board.value && boardQuery.data.value
  && boardQuery.data.value.id === board.value.id && boardQuery.data.value.revision !== board.value.revision))
const historyQuery = useQuery({ queryKey: computed(() => ['boards', store.sessionEpoch, 'history', boardId.value, historyBefore.value]), enabled: computed(() => store.sessionActive && Boolean(boardId.value)),
  queryFn: () => read<BoardHistoryPage>(`/knowledge/boards/${boardId.value}/history?limit=20${historyBefore.value !== undefined ? `&before=${historyBefore.value}` : ''}`) })
const externalReferences = useQuery({
  queryKey: computed(() => ['boards', store.sessionEpoch, 'reference-picker', referenceKind.value, referenceSearch.value, referenceCursor.value]),
  enabled: computed(() => picker.value && referenceKind.value !== 'KNOWLEDGE' && referenceReadable.value && !locked.value),
  queryFn: async () => {
    const parameters = new URLSearchParams({ limit: '20', q: referenceSearch.value })
    if (referenceCursor.value) parameters.set('cursor', referenceCursor.value)
    if (referenceKind.value === 'QUESTION') {
      parameters.set('status', 'ACTIVE')
      const result = await read<QuestionPage>(`/bank/questions?${parameters}`)
      return { items: result.items.map(value => ({ id: value.id, title: value.title, version: `题面版本 ${value.versionNumber}` })), nextCursor: result.nextCursor }
    }
    const result = await read<AttachmentPage>(`/materials/attachments?${parameters}`)
    return { items: result.items.filter(value => value.status === 'READY').map(value => ({ id: value.id, title: value.fileName, version: `元数据修订 ${value.revision}` })), nextCursor: result.nextCursor }
  },
})

function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) }
let alive = true, referenceGeneration = 0, historyGeneration = 0, serverGeneration = 0
watch([boardId, () => store.sessionEpoch, referenceReadable, picker, locked, referenceKind], () => { referenceGeneration++; selectingReference.value = false }, { flush: 'sync' })
watch([boardId, () => store.sessionEpoch, referenceReadable], () => { historyGeneration++; serverGeneration++ }, { flush: 'sync' })
watch(referenceKind, () => { referenceCursor.value = undefined; referenceInput.value = ''; referenceSearch.value = '' })
function accept(value: BoardDetail) {
  board.value = clone(value); baseline.value = JSON.stringify(boardPayload(value)); undoStack.value = []; redoStack.value = []
  conversion.value = null; historyPreview.value = undefined; serverCopy.value = undefined
}
watch(boardId, () => { board.value = undefined; baseline.value = ''; selectedId.value = ''; selectedEdgeId.value = ''; historyBefore.value = undefined; message.value = ''; localError.value = ''; historyPreview.value = undefined; serverCopy.value = undefined })
watch(boardQuery.data, value => {
  // 重新登录或后台刷新不能提升脏画布的 CAS 基线，也不能替换未保存的节点。
  if (value && value.id === boardId.value && (!board.value || (!dirty.value && !transferPending.value && !writer.locked.value))) {
    accept(value)
    recordRecentVisit(value.id)
  }
}, { immediate: true })
watch([dirty, writer.locked, transferPending], ([dirtyValue, lockedValue, transferValue]) => store.changes.set('board', { dirty: dirtyValue || transferValue, locked: lockedValue }), { immediate: true })
watch([createTitle, creator.locked], ([title, busy]) => store.changes.set('board-create', { dirty: Boolean(title), locked: busy }), { immediate: true })
onUnmounted(() => { alive = false; referenceGeneration++; store.changes.delete('board'); store.changes.delete('board-create') })
function checkpoint() {
  if (locked.value || !board.value) return
  const value = payload.value
  if (undoStack.value.at(-1) !== value) undoStack.value = [...undoStack.value.slice(-49), value]
  redoStack.value = []
}
function mutate(change: (value: BoardDetail) => void, remember = true) {
  if (locked.value || !board.value) return
  if (remember) checkpoint()
  change(board.value); message.value = ''; conversion.value = null
}
function undo() {
  if (locked.value || !board.value || !undoStack.value.length) return
  redoStack.value.push(payload.value)
  Object.assign(board.value, JSON.parse(undoStack.value.pop()!) as BoardSave)
}
function redo() {
  if (locked.value || !board.value || !redoStack.value.length) return
  undoStack.value.push(payload.value)
  Object.assign(board.value, JSON.parse(redoStack.value.pop()!) as BoardSave)
}
function select(id: string) { selectedId.value = id; selectedEdgeId.value = ''; conversion.value = null }
function reference(node: BoardNode, value = board.value) { return value ? boardNodeReference(value, node) : undefined }
function nodeTypeLabel(node: BoardNode) { return node.type === 'TEXT' ? '临时文本' : boardReferenceLabels[node.referenceKind ?? 'KNOWLEDGE'] }
function label(node: BoardNode) { return node.type === 'REFERENCE' ? reference(node)?.title ?? nodeTypeLabel(node) : node.text.slice(0, 32) || '临时文本' }
function referenceLocation(node: BoardNode, value = board.value, historical = false) {
  const source = reference(node, value), kind = node.referenceKind ?? 'KNOWLEDGE'
  if (kind === 'QUESTION') return { name: 'bank', query: { item: node.itemId } }
  if (kind === 'ATTACHMENT') return { name: 'materials', query: { attachment: node.itemId } }
  return { name: 'knowledge', query: { item: node.itemId, ...(historical && source?.versionId ? { version: source.versionId } : {}) } }
}
function referenceVersion(node: BoardNode, value = board.value) {
  const source = reference(node, value)
  return source?.referenceKind === 'ATTACHMENT' ? `附件元数据修订 ${source.attachmentRevision}` : source ? `版本 ${source.versionNumber} · ${source.versionId}` : '来源不可用'
}
function navigate(id: string) {
  if (id === boardId.value || !store.canLeave(['board', 'board-create'])) return
  void router.push({ name: 'boards', query: { board: id } })
}
function created(value: BoardDetail) {
  createTitle.value = ''; showCreate.value = false
  void queryClient.invalidateQueries({ queryKey: ['boards', store.sessionEpoch, 'list'] })
  void router.push({ name: 'boards', query: { board: value.id } })
}
function createBoard() {
  if (!createTitle.value.trim() || !store.canLeave(['board'])) return
  void creator.run<BoardDetail>('/knowledge/boards', 'POST', { kind: createKind.value, title: createTitle.value }, created)
}
function update(value: BoardDetail, text: string) {
  accept(value); message.value = text
  queryClient.setQueryData(['boards', store.sessionEpoch, 'detail', value.id], value)
  void queryClient.invalidateQueries({ queryKey: ['boards', store.sessionEpoch, 'list'] })
  void queryClient.invalidateQueries({ queryKey: ['boards', store.sessionEpoch, 'history', value.id] })
}
function save() {
  if (!board.value || !dirty.value || transferPending.value || !board.value.title.trim()) return
  void writer.run<BoardDetail>(`/knowledge/boards/${board.value.id}`, 'PUT', boardPayload(board.value), value => update(value, '画布已保存到服务器。'))
}
function addText() {
  if (!board.value || board.value.nodes.length >= 100) return
  const id = crypto.randomUUID()
  mutate(value => value.nodes.push({ id, type: 'TEXT', itemId: null, text: '', x: 40 + value.nodes.length % 5 * 260, y: 60 + Math.floor(value.nodes.length / 5) * 230,
    parentId: value.kind === 'MIND_MAP' ? selectedId.value || null : null, collapsed: false, group: null }))
  select(id)
}
async function addReference(item: ItemSummary) {
  return addTypedReference('KNOWLEDGE', item.id)
}
async function addTypedReference(kind: BoardReferenceKind, itemId: string) {
  if (locked.value || !referenceReadable.value || selectingReference.value || !picker.value || !board.value || board.value.nodes.length >= 100) return
  const originalBoard = board.value, epoch = store.sessionEpoch, generation = ++referenceGeneration
  const parentId = originalBoard.kind === 'MIND_MAP' ? selectedId.value || null : null
  selectingReference.value = true
  localError.value = ''
  const current = () => alive && referenceReadable.value && !locked.value && picker.value && board.value === originalBoard
    && epoch === store.sessionEpoch && generation === referenceGeneration
  try {
    let chosen: BoardReference
    if (kind === 'KNOWLEDGE') {
      const detail = await read<ItemDetail>(`/knowledge/items/${itemId}`)
      chosen = { itemId: detail.id, kind: detail.kind, title: detail.published.title, status: detail.status,
        versionId: detail.published.id, versionNumber: detail.published.number, body: detail.published.body.slice(0, 2000), referenceKind: kind, attachmentRevision: null }
    } else if (kind === 'QUESTION') {
      const detail = await read<Question>(`/bank/questions/${itemId}`)
      if (detail.status !== 'ACTIVE') throw new Error('该题目已归档，请重新选择。')
      chosen = { itemId: detail.id, kind, title: detail.currentVersion.content.title, status: detail.status,
        versionId: detail.currentVersion.id, versionNumber: detail.currentVersion.number, body: detail.currentVersion.content.prompt.slice(0, 2000), referenceKind: kind, attachmentRevision: null }
    } else {
      const detail = await read<Attachment>(`/materials/attachments/${itemId}`)
      if (detail.status !== 'READY') throw new Error('该附件尚未完成上传，不能引用。')
      chosen = { itemId: detail.id, kind, title: detail.fileName, status: 'READY', versionId: null, versionNumber: 0,
        body: detail.description.slice(0, 2000), referenceKind: kind, attachmentRevision: detail.revision }
    }
    if (!current()) return
    // 读取期间仍可编辑画布；容量和点击时的父节点必须在落入本地草稿前再次核对。
    if (originalBoard.nodes.length >= 100) { localError.value = '画布已达到 100 个节点，请移出节点后再引用。'; return }
    if (parentId && !originalBoard.nodes.some(node => node.id === parentId)) { localError.value = '引用时选择的父节点已移出，请重新选择父节点后再引用。'; return }
    const id = crypto.randomUUID()
    mutate(value => {
      value.references = [...value.references.filter(value => boardReferenceKey(value.itemId, value.referenceKind) !== boardReferenceKey(itemId, kind)), chosen]
      value.nodes.push({ id, type: 'REFERENCE', itemId, text: '', x: 40 + value.nodes.length % 5 * 260, y: 60 + Math.floor(value.nodes.length / 5) * 230,
        parentId, collapsed: false, group: null, referenceKind: kind })
    })
    picker.value = false; select(id)
  } catch (error) { if (current()) localError.value = errorText(error) }
  finally { if (alive && generation === referenceGeneration) selectingReference.value = false }
}
function removeSelected() {
  const id = selectedId.value
  if (!id || !window.confirm('将此卡片移出画布？来源原件会保留，相连画布线会一并移出。')) return
  mutate(value => {
    value.nodes = value.nodes.filter(node => node.id !== id).map(node => node.parentId === id ? { ...node, parentId: null } : node)
    value.edges = value.edges.filter(edge => edge.source !== id && edge.target !== id)
  })
  selectedId.value = ''
}
function move(id: string, x: number, y: number, remember = true) {
  mutate(value => { const node = value.nodes.find(node => node.id === id); if (node) { node.x = x; node.y = y } }, remember)
}
function connect(source: string, target: string) {
  if (!board.value || board.value.edges.length >= 200 || source === target) { localError.value = '连线需要两个不同节点，且总数不能超过 200。'; return }
  mutate(value => value.edges.push({ id: crypto.randomUUID(), source, target, label: linkLabel.value.trim() }))
  linkLabel.value = ''
}
function setParent(value: string | null) {
  if (!selected.value || !board.value) return
  let parent = value
  const seen = new Set([selected.value.id])
  while (parent) {
    if (seen.has(parent)) { localError.value = '父子层级不能形成循环。'; return }
    seen.add(parent); parent = board.value.nodes.find(node => node.id === parent)?.parentId ?? null
  }
  mutate(() => { if (selected.value) selected.value.parentId = value })
}
function align(axis: 'x' | 'y') {
  if (!board.value || !selected.value) return
  const group = selected.value.group
  const value = selected.value[axis]
  mutate(board => board.nodes.filter(node => group ? node.group === group : true).forEach(node => { node[axis] = value }))
}
function arrangeTree() {
  if (!board.value || !window.confirm('根据当前父子层级排列所有卡片？可以在保存前撤销。')) return
  mutate(value => {
    let row = 0
    const visit = (node: BoardNode, depth: number) => {
      node.x = depth * 290 + 40; node.y = row++ * 220 + 40
      value.nodes.filter(child => child.parentId === node.id).forEach(child => visit(child, depth + 1))
    }
    value.nodes.filter(node => !node.parentId).forEach(node => visit(node, 0))
  })
}
function viewport(value: BoardViewport) {
  if (board.value && JSON.stringify(board.value.viewport) !== JSON.stringify(value)) mutate(board => { board.viewport = value })
}
function zoom(factor: number) {
  if (!board.value) return
  viewport({ ...board.value.viewport, zoom: Math.min(10, Math.max(0.05, board.value.viewport.zoom * factor)) })
}
async function preview(revision: number) {
  localError.value = ''
  const id = boardId.value, epoch = store.sessionEpoch, generation = ++historyGeneration
  historyPreview.value = undefined
  try { const result = await read<BoardDetail>(`/knowledge/boards/${id}/history/${revision}`); if (alive && referenceReadable.value && boardId.value === id && epoch === store.sessionEpoch && generation === historyGeneration) historyPreview.value = result }
  catch (error) { if (alive && boardId.value === id && epoch === store.sessionEpoch && generation === historyGeneration) localError.value = errorText(error) }
}
function restore() {
  if (!board.value || !historyPreview.value || dirty.value || transferPending.value || !window.confirm(`恢复修订 ${historyPreview.value.revision} 的画布内容？将生成新修订，来源原件保持独立。`)) return
  void writer.run<BoardDetail>(`/knowledge/boards/${board.value.id}/history/${historyPreview.value.revision}/restore`, 'POST', { expectedRevision: board.value.revision }, value => update(value, '历史布局已恢复为新修订。'))
}
async function loadServerCopy() {
  const id = boardId.value, epoch = store.sessionEpoch, generation = ++serverGeneration
  serverCopy.value = undefined
  try { const result = await read<BoardDetail>(`/knowledge/boards/${id}`); if (alive && referenceReadable.value && boardId.value === id && epoch === store.sessionEpoch && generation === serverGeneration) serverCopy.value = result }
  catch (error) { if (alive && boardId.value === id && epoch === store.sessionEpoch && generation === serverGeneration) localError.value = errorText(error) }
}
function adoptServerCopy() {
  if (serverCopy.value && !writer.locked.value && window.confirm(transferPending.value
    ? '用服务器版本替换本地画布？可先导出本地 JSON 留存。导入文件与逐项选择会保留，采用前须按新修订重新核对。'
    : '用服务器版本替换本地画布？可先导出本地 JSON 留存。')) accept(serverCopy.value)
}
function convertText() {
  if (!board.value || !selected.value || dirty.value || transferPending.value || !convertTitle.value.trim()) return
  void writer.run<{ board: BoardDetail; itemId: string }>(`/knowledge/boards/${board.value.id}/nodes/${selected.value.id}/convert`, 'POST', { expectedRevision: board.value.revision, title: convertTitle.value }, value => {
    update(value.board, '已创建笔记，并将此卡片转为知识引用。')
    void queryClient.invalidateQueries({ queryKey: ['knowledge', store.sessionEpoch] })
  })
}
function convertEdge() {
  if (!board.value || !selectedEdge.value || dirty.value || transferPending.value || !canConvertEdge.value) return
  void writer.run(`/knowledge/boards/${board.value.id}/edges/${selectedEdge.value.id}/convert`, 'POST', { expectedRevision: board.value.revision, type: relationType.value }, () => {
    conversion.value = null; message.value = '正式语义关系已保存，画布线继续保留。'
    void queryClient.invalidateQueries({ queryKey: ['knowledge', store.sessionEpoch] })
  })
}
function applyTransfer(value: BoardTransferDraft) {
  if (locked.value || !referenceReadable.value || !board.value) return
  mutate(board => {
    board.nodes = value.nodes; board.edges = value.edges; board.viewport = value.viewport
    const merged = new Map(board.references.map(reference => [boardReferenceKey(reference.itemId, reference.referenceKind), reference]))
    value.references.forEach(reference => merged.set(boardReferenceKey(reference.itemId, reference.referenceKind), reference))
    board.references = [...merged.values()]
  })
  selectedId.value = ''; selectedEdgeId.value = ''
}
function download(format: 'json' | 'md', value = board.value) {
  if (!value || !referenceReadable.value) return
  let text: string
  try {
    const exported = format === 'md' ? exportBoardTransfer(value, 'markdown') : undefined
    if (exported && !window.confirm(`${exported.warnings.join('\n')}\n\n按此范围下载历史脑图？`)) return
    const used = new Set(value.nodes.filter(node => node.type === 'REFERENCE' && node.itemId).map(node => boardReferenceKey(node.itemId!, node.referenceKind)))
    text = exported?.text ?? JSON.stringify({ ...value, references: value.references.filter(reference => used.has(boardReferenceKey(reference.itemId, reference.referenceKind))) }, null, 2)
  } catch (error) { localError.value = errorText(error); return }
  const url = URL.createObjectURL(new Blob([text], { type: format === 'json' ? 'application/json;charset=utf-8' : 'text/markdown;charset=utf-8' }))
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = `board-${value.id}-r${value.revision}.${format}`; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <section class="boards-workspace" :class="{ 'board-focused': boardId }">
    <header class="page-heading"><div><h1>{{ board?.title || '知识白板' }}</h1><p class="muted">{{ board ? `${board.kind === 'MIND_MAP' ? '脑图' : '学习白板'} v${board.revision} · ${dirty ? '有未保存修改' : '已保存'}` : '把知识放在一起思考' }}</p></div><div class="row-actions"><NButton @click="libraryOpen = !libraryOpen"><RIcon name="panel-left" />画布目录</NButton><NButton v-if="board" @click="download('json')"><RIcon name="download" />导出</NButton><NButton type="primary" @click="showCreate = !showCreate"><RIcon name="plus" />新建画布</NButton></div></header>
    <div v-if="showCreate" class="board-create">
      <NSelect v-model:value="createKind" :options="[{ label: '脑图', value: 'MIND_MAP' }, { label: '自由白板', value: 'BOARD' }]" :disabled="creator.locked.value" aria-label="画布类型" />
      <NInput v-model:value="createTitle" :maxlength="200" placeholder="为这张画布命名" :disabled="creator.locked.value" aria-label="画布名称" />
      <NButton type="primary" :disabled="!createTitle.trim() || creator.locked.value || !store.sessionActive" @click="createBoard">创建</NButton>
      <p v-if="creator.error.value" class="notice error">{{ errorText(creator.error.value) }}</p><NButton v-if="creator.pending.value" @click="creator.retry">用原请求重试创建</NButton>
    </div>
    <div class="board-layout">
      <aside v-show="!boardId || libraryOpen" class="board-library" aria-label="画布列表">
        <h2>我的画布</h2><p v-if="listQuery.isPending.value" class="loading-block">正在读取画布…</p>
        <p v-if="listQuery.error.value" class="notice error">{{ errorText(listQuery.error.value) }}</p>
        <p v-if="listQuery.data.value && !listQuery.data.value.items.length" class="empty-inline">创建一张脑图整理层级，或用自由白板连接想法。</p>
        <button v-for="item in listQuery.data.value?.items" :key="item.id" class="board-list-item" :class="{ active: item.id === boardId }" @click="navigate(item.id)"><strong>{{ item.title }}</strong><small>{{ item.kind === 'MIND_MAP' ? '脑图' : '白板' }} · 修订 {{ item.revision }}</small></button>
        <div class="row-actions"><NButton v-if="cursor" size="small" @click="cursor = undefined">第一页</NButton><NButton v-if="listQuery.data.value?.nextCursor" size="small" @click="cursor = listQuery.data.value.nextCursor">下一页</NButton></div>
      </aside>
      <main class="board-main">
        <p v-if="boardId && boardQuery.isPending.value && !board" class="loading-block">正在读取画布与知识引用…</p>
        <p v-if="boardQuery.error.value" class="notice error">{{ errorText(boardQuery.error.value) }}</p>
        <div v-if="!boardId" class="list-empty-detail"><h2>让知识形成可编辑的结构</h2><p>从左侧打开画布，或创建一个新的思考空间。画布卡片可以引用同一份知识原文。</p></div>
        <p v-if="board && !referenceReadable" class="notice" role="status">当前训练尚未允许查阅资料。画布草稿保留，请先回到训练确认查阅条件。</p>
        <div v-if="board" v-show="referenceReadable" class="board-body" :inert="!referenceReadable || undefined">
          <div class="board-title"><NInput v-model:value="board.title" :maxlength="200" :disabled="locked" aria-label="画布标题" @focus="checkpoint" /><span class="status-pill">{{ dirty ? '有未保存修改' : `已保存 · 修订 ${board.revision}` }}</span><NButton type="primary" :disabled="locked || transferPending || !dirty || !board.title.trim()" @click="save">保存画布</NButton></div>
          <div class="board-tools"><NButton size="small" :disabled="locked || board.nodes.length >= 100" @click="addText">添加想法</NButton><NButton size="small" :disabled="locked || board.nodes.length >= 100" @click="picker = !picker">引用知识／题目／附件</NButton><NButton size="small" :disabled="locked || !undoStack.length" @click="undo">撤销</NButton><NButton size="small" :disabled="locked || !redoStack.length" @click="redo">重做</NButton><NButton v-if="board.kind === 'MIND_MAP'" size="small" :disabled="locked" @click="arrangeTree">按层级排列</NButton><NButton size="small" @click="download('json')">导出问阶快照</NButton></div>
          <p v-if="message" class="notice success-text" role="status">{{ message }}</p><p v-if="localError" class="notice error" role="alert">{{ localError }}</p>
          <p v-if="serverUpdateAvailable && (dirty || transferPending)" class="notice" role="status">服务器已有修订 {{ boardQuery.data.value?.revision }}。本地画布和未采用的导入选择继续保留；可先读取服务器版本作对照，再明确决定是否采用。<NButton size="small" :disabled="locked" @click="loadServerCopy">读取服务器版本作对照</NButton></p>
          <div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><div class="row-actions"><NButton v-if="writer.pending.value" size="small" @click="writer.retry">用原请求重试</NButton><NButton v-if="writer.error.value instanceof ApiError && writer.error.value.status === 409" size="small" @click="loadServerCopy">读取服务器版本作对照</NButton></div></div>
          <div v-if="serverCopy" class="comparison-panel"><strong>服务器：修订 {{ serverCopy.revision }} · {{ serverCopy.title }}</strong><p>{{ serverCopy.nodes.length }} 个节点，{{ serverCopy.edges.length }} 条线；本地输入仍保留。</p><div class="row-actions"><NButton size="small" @click="download('json', serverCopy)">导出服务器版本</NButton><NButton size="small" :disabled="writer.locked.value" @click="adoptServerCopy">采用服务器版本</NButton></div></div>
          <section v-if="picker" class="board-reference-picker" aria-label="选择画布引用">
            <NSelect v-model:value="referenceKind" :disabled="locked || selectingReference" :options="[{ label: '知识笔记／概念', value: 'KNOWLEDGE' }, { label: '题库题面（不读取参考答案）', value: 'QUESTION' }, { label: '已上传附件（元数据与原件入口）', value: 'ATTACHMENT' }]" aria-label="引用来源类型" />
            <KnowledgePicker v-if="referenceKind === 'KNOWLEDGE'" :disabled="locked || !referenceReadable || selectingReference || board.nodes.length >= 100" @choose="addReference" />
            <template v-else>
              <form class="title-search" @submit.prevent="referenceSearch = referenceInput.trim(); referenceCursor = undefined"><NInput v-model:value="referenceInput" :maxlength="100" :disabled="locked || selectingReference" placeholder="按标题或文件名查找" aria-label="来源筛选" /><NButton attr-type="submit" :disabled="locked || selectingReference">查找</NButton></form>
              <p v-if="externalReferences.isFetching.value" role="status">正在读取引用目录…</p>
              <p v-if="externalReferences.error.value" class="notice error" role="alert">{{ errorText(externalReferences.error.value) }}</p>
              <p v-if="externalReferences.data.value && !externalReferences.data.value.items.length" class="field-help">本页没有可选来源；题目须处于活跃状态，附件须已完成上传。</p>
              <div class="external-reference-list"><button v-for="item in externalReferences.data.value?.items" :key="item.id" type="button" :disabled="locked || selectingReference || board.nodes.length >= 100" @click="addTypedReference(referenceKind, item.id)"><strong>{{ item.title }}</strong><small>{{ item.version }}</small></button></div>
              <div class="row-actions"><NButton v-if="referenceCursor" size="small" :disabled="locked || selectingReference" @click="referenceCursor = undefined">第一页</NButton><NButton v-if="externalReferences.data.value?.nextCursor" size="small" :disabled="locked || selectingReference" @click="referenceCursor = externalReferences.data.value.nextCursor">下一页</NButton></div>
            </template>
          </section>
          <p v-if="selectingReference" class="field-help" role="status">正在读取所选来源…</p>
          <BoardTransfer :board="board" :disabled="locked || !referenceReadable" @apply="applyTransfer" @pending="value => transferPending = value" />
          <div class="board-editing"><div><BoardCanvas :nodes="board.nodes" :edges="board.edges" :references="board.references" :viewport="board.viewport" :selected-id="selectedId" :disabled="locked"
            @select="select" @edge="id => { selectedEdgeId = id; selectedId = ''; conversion = null }" @begin="checkpoint" @move="(id, x, y) => move(id, x, y, false)" @connect="connect" @viewport="viewport" />
            <div class="board-tools"><NButton size="small" :disabled="locked" @click="zoom(.8)">缩小</NButton><span>{{ Math.round(board.viewport.zoom * 100) }}%</span><NButton size="small" :disabled="locked" @click="zoom(1.25)">放大</NButton><NButton size="small" :disabled="locked" @click="viewport({ x: 0, y: 0, zoom: 1 })">初始视口</NButton><small class="muted">{{ board.nodes.length }}/100 节点 · {{ board.edges.length }}/200 连线</small></div></div>
            <aside class="board-inspector" aria-label="节点与连线编辑">
              <h3>{{ selectedEdge ? '连线' : selected ? '节点' : '画布检查器' }}</h3>
              <details class="inspector-node-directory" :open="!selected && !selectedEdge"><summary>节点列表 · {{ board.nodes.length }}</summary><p class="field-help">包括已折叠节点；选中后可用按钮移动。</p>
              <div class="board-node-list"><button v-for="node in board.nodes" :key="node.id" :class="{ active: node.id === selectedId }" @click="select(node.id)">{{ label(node) }}<small>{{ node.group || nodeTypeLabel(node) }}</small></button></div></details>
              <div v-if="selected" class="node-properties">
                <h3>{{ selected.type === 'REFERENCE' ? nodeTypeLabel(selected) : '编辑想法' }}</h3>
                <template v-if="selected.type === 'TEXT'"><label :for="`node-text-${selected.id}`">临时文本（最多 5000 字）</label><textarea :id="`node-text-${selected.id}`" v-model="selected.text" :disabled="locked" maxlength="5000" rows="7" @focus="checkpoint" /></template>
                <template v-else><p>{{ reference(selected)?.title }}</p><p class="field-help">{{ referenceVersion(selected) }}。最多 2000 字预览；知识读取发布版，题目只读取题面，附件只读取描述并提供原件入口。</p><RouterLink class="text-link" :to="referenceLocation(selected)">打开{{ selected.referenceKind === 'QUESTION' ? '题目当前版' : selected.referenceKind === 'ATTACHMENT' ? '附件详情与原件' : '知识原文' }}</RouterLink><pre class="reference-preview">{{ reference(selected)?.body }}</pre></template>
                <label>父节点（仅表达画布层级）</label><NSelect :value="selected.parentId" :options="parentOptions" clearable :disabled="locked" aria-label="父节点" @update:value="setParent" />
                <label for="node-group">分组名称</label><input id="node-group" :value="selected.group ?? ''" maxlength="40" :disabled="locked" @change="mutate(() => { if (selected) selected.group = ($event.target as HTMLInputElement).value.trim() || null })" />
                <label class="collapse-check"><input :checked="selected.collapsed" type="checkbox" :disabled="locked" @change="mutate(() => { if (selected) selected.collapsed = !selected.collapsed })" />折叠子节点</label>
                <div class="node-directions"><button :disabled="locked" aria-label="节点向左移动 20" @click="move(selected.id, selected.x - 20, selected.y)">←</button><button :disabled="locked" aria-label="节点向上移动 20" @click="move(selected.id, selected.x, selected.y - 20)">↑</button><button :disabled="locked" aria-label="节点向下移动 20" @click="move(selected.id, selected.x, selected.y + 20)">↓</button><button :disabled="locked" aria-label="节点向右移动 20" @click="move(selected.id, selected.x + 20, selected.y)">→</button></div>
                <p class="field-help">对齐作用于同分组；未分组时作用于所有节点。</p><div class="row-actions"><NButton size="small" :disabled="locked" @click="align('x')">对齐横坐标</NButton><NButton size="small" :disabled="locked" @click="align('y')">对齐纵坐标</NButton></div>
                <NButton size="small" :disabled="locked" @click="removeSelected">移出画布</NButton>
                <NButton v-if="selected.type === 'TEXT'" size="small" :disabled="locked || dirty" @click="conversion = 'text'; convertTitle = selected.text.slice(0, 40)">转为独立笔记…</NButton>
                <div v-if="conversion === 'text'" class="conversion-preview"><p>创建一篇新笔记，并把当前卡片替换为它的引用。其他卡片保持独立。</p><label>新笔记标题</label><NInput v-model:value="convertTitle" :maxlength="200" :disabled="locked" aria-label="新笔记标题" /><NButton size="small" type="primary" :disabled="locked || dirty || !convertTitle.trim()" @click="convertText">确认创建与替换</NButton></div>
              </div>
              <details class="board-links"><summary>添加与管理画布连线</summary><NSelect v-model:value="sourceId" :options="nodeOptions" :disabled="locked" placeholder="起点" aria-label="连线起点" /><NSelect v-model:value="targetId" :options="nodeOptions" :disabled="locked" placeholder="终点" aria-label="连线终点" /><NInput v-model:value="linkLabel" :maxlength="100" :disabled="locked" placeholder="连线说明" aria-label="连线说明" /><NButton size="small" :disabled="locked || !sourceId || !targetId" @click="sourceId && targetId && connect(sourceId, targetId)">添加画布连线</NButton><button v-for="edge in board.edges" :key="edge.id" class="board-edge-row" :class="{ active: edge.id === selectedEdgeId }" @click="selectedEdgeId = edge.id; selectedId = ''; conversion = null">{{ nodeOptions.find(value => value.value === edge.source)?.label }} → {{ nodeOptions.find(value => value.value === edge.target)?.label }}<small>{{ edge.label || '无标签' }}</small></button></details>
              <div v-if="selectedEdge" class="node-properties"><h3>画布连线</h3><NInput v-model:value="selectedEdge.label" :maxlength="100" :disabled="locked" aria-label="修改连线说明" @focus="checkpoint" /><NButton size="small" :disabled="locked" @click="mutate(value => { value.edges = value.edges.filter(edge => edge.id !== selectedEdgeId) }); selectedEdgeId = ''">移除这条画布线</NButton><p class="field-help">两端都是有效概念时，可以显式创建正式语义关系。</p><NButton size="small" :disabled="locked || dirty || !canConvertEdge" @click="conversion = 'edge'">转为正式关系…</NButton><div v-if="conversion === 'edge'" class="conversion-preview"><p>将按画布起点 → 终点创建以下关系。相关与对比没有方向；画布线本身会保留。</p><NSelect v-model:value="relationType" :options="relationOptions" :disabled="locked" aria-label="正式关系类型" /><NButton type="primary" size="small" :disabled="locked || dirty" @click="convertEdge">确认创建正式关系</NButton></div></div>
              <p v-if="dirty || transferPending" class="field-help">请先处理导入预览并保存画布，再执行转笔记、转关系或恢复历史。带题目／附件引用的画布用于资料整理；当前训练答案只支持知识引用，请使用仅含知识与文字的画布导入训练。</p>
            </aside>
          </div>
          <details class="board-history"><summary>已保存的历史修订</summary><p class="field-help">历史引用保留当时的发布版本。恢复将生成新画布修订，当前引用重新显示最新发布内容。</p><p v-if="historyQuery.error.value" class="notice error">{{ errorText(historyQuery.error.value) }}</p><div class="board-history-list"><button v-for="entry in historyQuery.data.value?.items" :key="entry.revision" @click="preview(entry.revision)">修订 {{ entry.revision }} · {{ entry.title }}<small>{{ new Date(entry.createdAt).toLocaleString() }}</small></button></div><div class="row-actions"><NButton v-if="historyBefore !== undefined" size="small" @click="historyBefore = undefined">最新修订</NButton><NButton v-if="historyQuery.data.value?.nextCursor != null" size="small" @click="historyBefore = historyQuery.data.value.nextCursor">更早修订</NButton></div>
            <div v-if="historyPreview" class="comparison-panel"><h3>{{ historyPreview.title }} · 修订 {{ historyPreview.revision }}</h3><p>{{ historyPreview.nodes.length }} 个节点，{{ historyPreview.edges.length }} 条线。以下为当时引用预览，来源当前可读性仍受服务器检查。</p><div class="row-actions"><NButton size="small" @click="download('json', historyPreview)">导出此历史快照</NButton><NButton size="small" @click="download('md', historyPreview)">导出历史脑图</NButton><NButton size="small" :disabled="locked || dirty || transferPending" @click="restore">恢复为新修订</NButton></div><div v-for="node in historyPreview.nodes" :key="node.id" class="history-node"><strong>{{ node.type === 'TEXT' ? '临时文本' : reference(node, historyPreview)?.title }}</strong><p v-if="node.type === 'REFERENCE'" class="field-help">{{ nodeTypeLabel(node) }} · {{ referenceVersion(node, historyPreview) }}</p><pre>{{ node.type === 'TEXT' ? node.text : reference(node, historyPreview)?.body }}</pre><RouterLink v-if="node.itemId" class="text-link" :to="referenceLocation(node, historyPreview, true)">{{ node.referenceKind === 'QUESTION' ? '打开题目当前版；旧版请按上方版本 ID 在题目历史中核对' : node.referenceKind === 'ATTACHMENT' ? '打开附件详情与原件' : '打开当时的完整知识版本' }}</RouterLink></div></div>
          </details>
        </div>
      </main>
    </div>
  </section>
</template>

<style scoped>
.boards-workspace { max-width: 1700px; margin: auto; }
.board-layout { display: grid; grid-template-columns: 190px minmax(0, 1fr); border: 1px solid var(--line); border-radius: 10px; overflow: hidden; background: var(--surface); }
.board-library { padding: 17px 12px; border-right: 1px solid var(--line); background: var(--surface-soft); }
.board-library h2 { margin-bottom: 15px; font-size: 15px; }
.board-list-item, .board-node-list button, .board-edge-row { display: block; width: 100%; padding: 10px 8px; border-radius: 5px; text-align: left; overflow-wrap: anywhere; }
.board-list-item:hover, .board-node-list button:hover, .board-edge-row:hover, .active { background: var(--brand-soft); }
.board-list-item strong { display: block; font-size: 12px; }
.board-list-item small, .board-node-list small, .board-edge-row small { display: block; margin-top: 4px; color: var(--muted); font-size: 10px; }
.board-main { min-width: 0; }
.board-title, .board-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 12px 14px; }
.board-title .n-input { flex: 1; min-width: 160px; }.board-tools { padding-top: 0; }
.board-editing { display: grid; grid-template-columns: minmax(0, 1fr) 270px; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.board-inspector { padding: 16px 12px; border-left: 1px solid var(--line); overflow: auto; max-height: 850px; }
.board-node-list { max-height: 190px; overflow: auto; margin: 9px 0 16px; }
.node-properties { display: grid; gap: 9px; padding-top: 15px; border-top: 1px solid var(--line); }
.node-properties label { font-size: 11px; color: var(--muted); }
.node-properties textarea, .node-properties input:not([type=checkbox]) { width: 100%; padding: 8px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface); color: var(--ink); resize: vertical; }
.reference-preview, .history-node pre { max-height: 190px; overflow: auto; white-space: pre-wrap; font: 12px/1.7 inherit; overflow-wrap: anywhere; }
.node-directions { display: flex; gap: 6px; }.node-directions button { width: 35px; height: 32px; border: 1px solid var(--line); border-radius: 5px; }
.board-links { display: grid; gap: 8px; margin-top: 20px; }.board-links > *:not(summary) { margin-top: 8px; }
summary { cursor: pointer; font-weight: 600; font-size: 12px; }.conversion-preview { display: grid; gap: 9px; padding: 12px; background: var(--surface-soft); border-radius: 6px; font-size: 12px; }
.board-history { padding: 18px; }.board-history > p { margin: 10px 0; }.board-history-list { display: flex; gap: 8px; flex-wrap: wrap; margin: 12px 0; }.board-history-list button { padding: 9px 11px; border: 1px solid var(--line); border-radius: 5px; font-size: 12px; text-align: left; }.board-history-list small { display: block; margin-top: 4px; color: var(--muted); font-size: 10px; }.history-node { margin-top: 16px; border-top: 1px solid var(--line); padding-top: 13px; }
.board-create { display: flex; flex-wrap: wrap; gap: 10px; margin: 0 0 18px; padding: 14px; background: var(--surface); border: 1px solid var(--line); border-radius: 8px; }.board-create .n-select { width: 140px; }.board-create .n-input { flex: 1; min-width: 190px; }
.board-main > .notice, .board-body > .notice, .board-body > .comparison-panel { margin: 10px 14px; }.board-main > .knowledge-picker { padding: 0 14px 14px; }
.board-reference-picker { display: grid; gap: 10px; margin: 0 14px 16px; }.external-reference-list { display: grid; max-height: 240px; overflow: auto; }.external-reference-list button { display: grid; gap: 5px; padding: 10px; text-align: left; border-bottom: 1px solid var(--line); }.external-reference-list small { color: var(--muted); }
.boards-workspace { max-width: none; }.board-layout { grid-template-columns: 210px minmax(0,1fr); border-radius: 10px; }.board-focused .board-layout { display: grid; }.board-library { padding: 16px 12px; }.board-library h2 { font-size: 15px; }.board-list-item strong { font-size: 13px; }.board-list-item small { font-size: 11px; }.board-main { min-width: 0; }.board-body { position: relative; }.board-editing { grid-template-columns: minmax(0,1fr) 240px; }.board-editing > div { min-width: 0; overflow: hidden; }.board-inspector { padding: 16px; max-height: 640px; }.board-inspector h3 { font-size: 15px; margin-bottom: 12px; }.board-inspector summary { font-size: 12px; }.board-node-list button { font-size: 13px; }.board-node-list small { font-size: 11px; }.board-inspector input:not([type=checkbox]), .board-inspector textarea { font-size: 13px; }.inspector-node-directory { margin-bottom: 16px; }
@media(max-width:1200px) { .board-editing { grid-template-columns: minmax(0,1fr); }.board-inspector { border-left: 0; border-top: 1px solid var(--line); max-height: none; } }
@media(max-width:700px) { .board-layout { grid-template-columns: minmax(0,1fr); }.board-library { max-height: 230px; overflow-y: auto; border-right: 0; border-bottom: 1px solid var(--line); }.board-title .status-pill { order: 3; }.board-create .n-select { width: 100%; } }
</style>
