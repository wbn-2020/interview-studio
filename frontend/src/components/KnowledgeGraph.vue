<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import cytoscape from 'cytoscape'
import type { Core, NodeSingular } from 'cytoscape'
import type { GraphResponse, Relation, Schema } from '../api/client'
import { errorText, read } from '../api/client'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import type { RelationGraphCandidateEdge, RelationGraphCandidateIdentity } from '../api/relationGraph'
import { relationGraphEdgeId } from '../api/relationGraph'
import RelationGraphCandidates from './RelationGraphCandidates.vue'

const props = defineProps<{ graph: GraphResponse; selectedId?: string; selectedKind?: 'NOTE' | 'CONCEPT'; topicId?: string; topicIds?: string[]; relationTypes?: Relation['type'][] }>()
const emit = defineEmits<{ select: [id: string]; saved: []; reload: [] }>()
type Position = { id: string; x: number; y: number }
type Viewport = { x: number; y: number; zoom: number }
type GraphState = { layoutRevision: number; viewport: Viewport }
type HistoryPage = { items: { revision: number; createdAt: string; positionCount: number }[]; nextCursor: number | null }
type GraphPath = { nodeIds: string[]; edges: Relation[]; found: boolean; truncated: boolean }
type Preview = { all: Position[]; dirty: Record<string, Position>; viewport: Viewport; viewportDirty: boolean }
const canvas = ref<HTMLDivElement>()
const zoom = ref(100)
const displayed = ref<GraphResponse>(props.graph)
const centerId = ref<string>()
const depth = ref(1)
const positions = ref<Record<string, Position>>({})
const viewportDirty = ref(false)
const savedViewport = ref<Viewport>({ x: 0, y: 0, zoom: 1 })
const stateRevision = ref<number>()
const layoutRevision = ref(props.graph.layoutRevision)
const preview = ref<Preview>()
const historyBefore = ref<number>()
const historyPages = ref<(number | undefined)[]>([])
const pathText = ref('')
const pathSearch = ref('')
const pathTarget = ref<string | null>(null)
const pathType = ref<string | null>(null)
const pathRequest = ref<{ from: string; to: string; type: string | null }>()
const navigation = ref<string[]>([])
const navigationIndex = ref(-1)
const requestedNavigationIndex = ref<number>()
const notice = ref('')
const pairAttempts = ref(0)
const writer = useCommand()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const candidateEdges = ref<RelationGraphCandidateEdge[]>([])
const selectedCandidate = ref<RelationGraphCandidateIdentity>()
const nodeIds = computed(() => displayed.value.nodes.map(node => node.id))
const readable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const dirty = computed(() => Object.keys(positions.value).length > 0 || viewportDirty.value || Boolean(preview.value))
const paired = computed(() => stateRevision.value !== undefined && stateRevision.value === displayed.value.layoutRevision)
const editable = computed(() => (paired.value || dirty.value) && stateRevision.value !== undefined && readable.value && !writer.locked.value)
const selectedConcept = computed(() => props.selectedKind === 'CONCEPT' || displayed.value.nodes.some(node => node.id === props.selectedId))
const relationNames: Record<string, string> = { PRECEDES: '前置', PART_OF: '组成', APPLIED_IN: '应用于', RELATED: '相关', CONTRASTS: '对比' }
const relationOptions = Object.entries(relationNames).map(([value, label]) => ({ value, label }))
const stateQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'graph-state']), enabled: readable, queryFn: () => read<GraphState>('/knowledge/graph/state') })
const historyQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'graph-history', historyBefore.value]), enabled: readable, queryFn: () => read<HistoryPage>(`/knowledge/graph/history?limit=10${historyBefore.value ? `&before=${historyBefore.value}` : ''}`) })
const neighborhoodQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'graph-neighborhood', centerId.value, depth.value]), enabled: computed(() => readable.value && Boolean(centerId.value) && !dirty.value && !writer.locked.value), queryFn: () => read<GraphResponse>(`/knowledge/graph/neighborhood?centerId=${centerId.value}&depth=${depth.value}&limit=200`) })
const candidatesQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'graph-candidates', pathSearch.value]), enabled: computed(() => readable.value && Boolean(pathSearch.value)), queryFn: () => read<Schema['ItemPage']>(`/knowledge/library?kind=CONCEPT&status=ACTIVE&limit=50&q=${encodeURIComponent(pathSearch.value)}`) })
const pathQuery = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'graph-path', pathRequest.value]), enabled: computed(() => readable.value && Boolean(pathRequest.value)), queryFn: () => {
  const value = pathRequest.value!
  return read<GraphPath>(`/knowledge/graph/path?fromId=${value.from}&toId=${value.to}${value.type ? `&type=${value.type}` : ''}`)
} })
const targetOptions = computed(() => {
  const targets = new Map(displayed.value.nodes.map(node => [node.id, { label: node.title, value: node.id }]))
  for (const node of candidatesQuery.data.value?.items ?? []) targets.set(node.id, { label: `${node.title} · ${node.id.slice(0, 8)}`, value: node.id })
  return [...targets.values()]
})
const connectedEdges = computed(() => displayed.value.edges.filter(edge => edge.sourceId === props.selectedId || edge.targetId === props.selectedId))
const visibleCandidateEdges = computed(() => {
  if (!readable.value) return []
  const ids = new Set(nodeIds.value)
  return candidateEdges.value.filter(edge => ids.has(edge.sourceId) && ids.has(edge.targetId))
})
let cy: Core | undefined
let resize: ResizeObserver | undefined
let suppressViewport = false

watch([dirty, writer.locked], ([value, locked]) => store.changes.set('layout', { dirty: value, locked }), { immediate: true })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  // 候选投影属于本次会话与资料权限；恢复权限后必须由面板重新核对，不能复活旧边。
  candidateEdges.value = []; selectedCandidate.value = undefined; refreshCandidateEdges()
}, { flush: 'sync' })
watch(visibleCandidateEdges, refreshCandidateEdges)
watch(selectedCandidate, highlightCandidate)

function candidateIdentityValid(value: unknown): value is RelationGraphCandidateIdentity {
  const identity = value as RelationGraphCandidateIdentity | undefined
  return Boolean(identity && typeof identity.taskId === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(identity.taskId)
    && typeof identity.candidateId === 'string' && /^r([1-9]|10)$/.test(identity.candidateId))
}
function projectCandidates(value: RelationGraphCandidateEdge[]) {
  if (!readable.value || !Array.isArray(value) || value.length > 10) { candidateEdges.value = []; return }
  const ids = new Set<string>(), tasks = new Set<string>()
  const verified = value.filter(edge => {
    if (!candidateIdentityValid(edge) || typeof edge.sourceId !== 'string' || typeof edge.targetId !== 'string'
      || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(edge.sourceId)
      || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(edge.targetId)
      || edge.sourceId === edge.targetId || !Object.hasOwn(relationNames, edge.type)) return false
    const id = relationGraphEdgeId(edge.taskId, edge.candidateId)
    if (ids.has(id)) return false
    ids.add(id); tasks.add(edge.taskId); return true
  })
  candidateEdges.value = tasks.size > 1 ? [] : verified.map(edge => ({ ...edge }))
  if (selectedCandidate.value && !candidateEdges.value.some(edge => edge.taskId === selectedCandidate.value!.taskId && edge.candidateId === selectedCandidate.value!.candidateId)) selectedCandidate.value = undefined
}
function selectCandidate(value: RelationGraphCandidateIdentity | undefined) {
  if (!readable.value) return
  selectedCandidate.value = value && candidateIdentityValid(value)
    && visibleCandidateEdges.value.some(edge => edge.taskId === value.taskId && edge.candidateId === value.candidateId)
    ? { taskId: value.taskId, candidateId: value.candidateId } : undefined
}
function refreshCandidateEdges() {
  if (!cy) return
  // 候选只作为独立边层；替换候选不重建节点、不调整视口，也不写入布局变更集合。
  cy.batch(() => {
    cy!.edges('.candidate-edge').remove()
    for (const edge of visibleCandidateEdges.value) {
      if (!cy!.$id(edge.sourceId).isNode() || !cy!.$id(edge.targetId).isNode()) continue
      cy!.add({ group: 'edges', classes: 'candidate-edge', data: { id: relationGraphEdgeId(edge.taskId, edge.candidateId),
        taskId: edge.taskId, candidateId: edge.candidateId, source: edge.sourceId, target: edge.targetId,
        directed: !['RELATED', 'CONTRASTS'].includes(edge.type), label: `待确认 · ${edge.candidateId} · ${relationNames[edge.type]}` } })
    }
  })
  highlight()
}
function highlightCandidate() {
  if (!cy) return
  cy.edges('.candidate-edge').removeClass('candidate-chosen')
  if (readable.value && selectedCandidate.value) cy.$id(relationGraphEdgeId(selectedCandidate.value.taskId, selectedCandidate.value.candidateId)).addClass('candidate-chosen')
}

function remember(node: NodeSingular) {
  if (!editable.value || preview.value) return
  beginChange()
  const position = { x: Math.max(-1000000, Math.min(1000000, node.position('x'))), y: Math.max(-1000000, Math.min(1000000, node.position('y'))) }
  node.position(position)
  positions.value[node.id()] = { id: node.id(), ...position }
}
function refreshGraph() {
  if (!cy) return
  const ids = new Set(displayed.value.nodes.map(node => node.id))
  cy.batch(() => {
    cy!.edges().remove()
    cy!.nodes().filter(node => !ids.has(node.id())).remove()
    for (const node of displayed.value.nodes) {
      const color = displayed.value.topics.find(topic => node.topicIds.includes(topic.id))?.color ?? '#81c9bd'
      const existing = cy!.$id(node.id)
      if (existing.length) {
        existing.data({ label: node.title, color })
        // 查询失效可能带回旧坐标，不能覆盖用户尚未保存的拖动。
        if (!positions.value[node.id]) existing.position({ x: node.x, y: node.y })
      } else cy!.add({ group: 'nodes', data: { id: node.id, label: node.title, color }, position: { x: node.x, y: node.y } })
    }
    for (const edge of displayed.value.edges) {
      cy!.add({ group: 'edges', data: { id: edge.id, source: edge.sourceId, target: edge.targetId, directed: !['RELATED', 'CONTRASTS'].includes(edge.type), relationType: edge.type, typeLabel: relationNames[edge.type] } })
    }
  })
  if (!dirty.value && paired.value) layoutRevision.value = displayed.value.layoutRevision
  refreshCandidateEdges()
}
function highlight() {
  if (!cy) return
  cy.elements().removeClass('dimmed chosen path')
  const chosen = props.selectedId ? cy.$id(props.selectedId) : undefined
  chosen?.addClass('chosen')
  const topics = props.topicIds?.length ? props.topicIds : props.topicId ? [props.topicId] : []
  if (topics.length) {
    const matching = new Set(displayed.value.nodes.filter(node => node.topicIds.some(id => topics.includes(id))).map(node => node.id))
    cy.nodes().filter(node => !matching.has(node.id())).addClass('dimmed')
    cy.edges().filter(edge => !matching.has(edge.source().id()) && !matching.has(edge.target().id())).addClass('dimmed')
  }
  cy.edges().filter(edge => !edge.hasClass('candidate-edge')).forEach(edge => {
    edge.style('display', props.relationTypes && !props.relationTypes.includes(edge.data('relationType')) ? 'none' : 'element')
  })
  for (const id of pathQuery.data.value?.nodeIds ?? []) cy.$id(id).addClass('path')
  for (const edge of pathQuery.data.value?.edges ?? []) cy.$id(edge.id).addClass('path')
  highlightCandidate()
}
function beginChange() {
  // 新编辑只从同一修订开始；已经变脏的布局保持原基线，重新登录也不能抬升它。
  if (!dirty.value && paired.value) layoutRevision.value = displayed.value.layoutRevision
}
function refreshPair(manual = false) {
  if (dirty.value || writer.locked.value || !readable.value) return
  if (manual) pairAttempts.value = 0
  if (pairAttempts.value >= 3) return
  pairAttempts.value++
  void stateQuery.refetch()
  if (centerId.value) void neighborhoodQuery.refetch()
  else emit('reload')
}
function view(): Viewport { const pan = cy?.pan(); return { x: pan?.x ?? 0, y: pan?.y ?? 0, zoom: cy?.zoom() ?? 1 } }
function applyViewport(value: Viewport) {
  if (!cy) return
  suppressViewport = true
  cy.viewport({ pan: { x: value.x, y: value.y }, zoom: value.zoom })
  zoom.value = Math.round(value.zoom * 100)
  suppressViewport = false
}
function fit() { if (cy && editable.value && !preview.value) cy.fit(cy.elements().filter(element => !element.hasClass('candidate-edge')), 65) }
function magnify(ratio: number) { if (cy && editable.value && !preview.value) cy.zoom({ level: Math.max(.05, Math.min(10, cy.zoom() * ratio)), renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }) }
function focus(id = props.selectedId, distance = 1) {
  if (!readable.value || !id || (id === props.selectedId && !selectedConcept.value)) return
  if (dirty.value || writer.locked.value) { notice.value = '请先保存或放弃当前布局，再切换邻域。'; return }
  centerId.value = id
  depth.value = distance
  notice.value = ''
}
function showAll() {
  if (!readable.value) return
  if (dirty.value || writer.locked.value) { notice.value = '请先保存或放弃当前布局，再切换图谱窗口。'; return }
  centerId.value = undefined
  displayed.value = props.graph
  refreshGraph()
}
function select(id: string) {
  if (!readable.value) return
  requestedNavigationIndex.value = undefined
  emit('select', id)
}
function nudge(dx: number, dy: number) {
  if (!props.selectedId || !cy || !editable.value || preview.value) return
  const node = cy.$id(props.selectedId)
  if (!node.length) return
  node.position({ x: node.position('x') + dx, y: node.position('y') + dy })
  remember(node[0] as NodeSingular)
}
function save() {
  if (!editable.value || preview.value) return
  const nextPositions = Object.values(positions.value)
  const nextViewport = view()
  void writer.run<Schema['LayoutSaved']>('/knowledge/graph/state', 'PUT', { expectedRevision: layoutRevision.value, positions: nextPositions, viewport: nextViewport }, result => {
    const saved = new Map(nextPositions.map(position => [position.id, position]))
    displayed.value = { ...displayed.value, layoutRevision: result.layoutRevision, nodes: displayed.value.nodes.map(node => ({ ...node, ...saved.get(node.id) })) }
    savedViewport.value = nextViewport
    stateRevision.value = result.layoutRevision
    positions.value = {}
    viewportDirty.value = false
    layoutRevision.value = result.layoutRevision
    notice.value = '布局与视口已保存。'
    void stateQuery.refetch()
    void historyQuery.refetch()
    emit('saved')
  })
}
function discard() {
  if (writer.locked.value || !window.confirm('放弃尚未保存的位置和镜头，并重新读取服务器布局？')) return
  preview.value = undefined
  positions.value = {}
  viewportDirty.value = false
  writer.error.value = undefined
  applyViewport(savedViewport.value)
  refreshGraph()
  void stateQuery.refetch()
  if (centerId.value) void neighborhoodQuery.refetch()
  emit('reload')
}
function previewLayout() {
  if (!cy || !editable.value || preview.value || !displayed.value.nodes.length) return
  beginChange()
  preview.value = { all: cy.nodes().map(node => ({ id: node.id(), x: node.position('x'), y: node.position('y') })), dirty: { ...positions.value }, viewport: view(), viewportDirty: viewportDirty.value }
  cy.nodes().layout({ name: 'circle', fit: false, animate: false, spacingFactor: 1.4 }).run()
  for (const node of cy.nodes()) positions.value[node.id()] = { id: node.id(), x: node.position('x'), y: node.position('y') }
}
function cancelPreview() {
  if (!preview.value || !cy) return
  const previous = preview.value
  for (const node of previous.all) cy.$id(node.id).position({ x: node.x, y: node.y })
  positions.value = previous.dirty
  viewportDirty.value = previous.viewportDirty
  applyViewport(previous.viewport)
  preview.value = undefined
}
function acceptPreview() { preview.value = undefined; save() }
function restore(revision: number) {
  if (dirty.value || !editable.value || !window.confirm(`撤销修订 ${revision} 的布局变更？这会恢复该次保存前的位置和镜头，并产生新修订。`)) return
  const expectedRevision = layoutRevision.value
  void writer.run<Schema['LayoutSaved']>(`/knowledge/graph/history/${revision}/restore`, 'POST', { expectedRevision }, () => {
    notice.value = '撤销已保存，正在重新读取布局。'
    void stateQuery.refetch()
    void historyQuery.refetch()
    if (centerId.value) void neighborhoodQuery.refetch()
    emit('saved')
  })
}
function nextHistory() { if (historyQuery.data.value?.nextCursor) { historyPages.value.push(historyBefore.value); historyBefore.value = historyQuery.data.value.nextCursor } }
function previousHistory() { historyBefore.value = historyPages.value.pop() }
function findPath() { if (readable.value && props.selectedId && selectedConcept.value && pathTarget.value) pathRequest.value = { from: props.selectedId, to: pathTarget.value, type: pathType.value } }
function nodeName(id: string) { return displayed.value.nodes.find(node => node.id === id)?.title ?? targetOptions.value.find(node => node.value === id)?.label ?? `概念 ${id.slice(0, 8)}` }
function navigateHistory(direction: number) {
  if (!readable.value) return
  const index = navigationIndex.value + direction
  if (index < 0 || index >= navigation.value.length) return
  requestedNavigationIndex.value = index
  emit('select', navigation.value[index]!)
}
function syncSnapshots() {
  if (!readable.value || dirty.value || writer.locked.value) return
  const graph = centerId.value ? neighborhoodQuery.data.value : props.graph
  if (graph && graph.layoutRevision >= displayed.value.layoutRevision) {
    displayed.value = graph
    refreshGraph()
  }
  const state = stateQuery.data.value
  if (state && state.layoutRevision >= (stateRevision.value ?? 0)) {
    savedViewport.value = state.viewport
    stateRevision.value = state.layoutRevision
  }
  if (paired.value) {
    // 全图、邻域及放弃编辑后的缓存重读，都只从同一修订应用坐标与镜头。
    layoutRevision.value = displayed.value.layoutRevision
    applyViewport(savedViewport.value)
    pairAttempts.value = 0
  } else refreshPair()
}
watch([() => props.graph, neighborhoodQuery.data, stateQuery.data, centerId, dirty, writer.locked, readable], syncSnapshots, { immediate: true })
watch([() => props.selectedId, () => props.selectedKind], ([id, kind]) => {
  if (!id) return
  const requested = requestedNavigationIndex.value
  requestedNavigationIndex.value = undefined
  // 路由守卫可能拒绝切换；只有实际选中的对象变化后才提交探索历史。
  if (requested !== undefined && navigation.value[requested] === id) navigationIndex.value = requested
  else if (navigation.value[navigationIndex.value] !== id) {
    navigation.value = navigation.value.slice(0, navigationIndex.value + 1)
    navigation.value.push(id)
    navigationIndex.value = navigation.value.length - 1
  }
  if (kind === 'CONCEPT' && !displayed.value.nodes.some(node => node.id === id) && !dirty.value && !writer.locked.value) focus(id)
  highlight()
}, { immediate: true })
watch([() => props.topicId, () => props.topicIds, () => props.relationTypes, pathQuery.data], highlight, { deep: true })
watch([editable, preview], ([enabled, pendingPreview]) => { cy?.autoungrabify(!enabled || Boolean(pendingPreview)); cy?.userPanningEnabled(enabled && !pendingPreview); cy?.userZoomingEnabled(enabled && !pendingPreview) })
onMounted(() => {
  cy = cytoscape({ container: canvas.value, elements: [], layout: { name: 'preset' }, minZoom: .05, maxZoom: 10, wheelSensitivity: .2, autoungrabify: !editable.value, userPanningEnabled: editable.value, userZoomingEnabled: editable.value,
    style: [
      { selector: 'node', style: { width: 15, height: 15, 'background-color': 'data(color)', 'border-width': 6, 'border-color': 'data(color)', 'border-opacity': .12, label: 'data(label)', color: '#f4eee4', 'font-size': 20, 'font-family': 'Segoe UI, Microsoft YaHei, sans-serif', 'text-valign': 'center', 'text-halign': 'right', 'text-margin-x': 18, 'text-max-width': '180px', 'text-wrap': 'ellipsis', 'text-background-color': '#15120e', 'text-background-opacity': .85, 'text-background-padding': '10px', 'text-background-shape': 'roundrectangle', 'min-zoomed-font-size': 10 } },
      { selector: 'edge', style: { width: 1.2, 'line-color': '#73c9c3', opacity: .55, 'curve-style': 'bezier', 'target-arrow-color': '#73c9c3', 'arrow-scale': .9 } },
      { selector: 'edge[?directed]', style: { 'target-arrow-shape': 'triangle' } },
      { selector: 'edge.candidate-edge', style: { width: 1.8, 'line-style': 'dashed', 'line-dash-pattern': [7, 5], 'line-color': '#e6aa55', 'target-arrow-color': '#e6aa55', opacity: .85, label: 'data(label)', color: '#f6cf8e', 'font-size': 14, 'text-background-color': '#17120b', 'text-background-opacity': .95, 'text-background-padding': '5px', 'min-zoomed-font-size': 8 } },
      { selector: '.dimmed', style: { opacity: .16 } },
      { selector: 'node.chosen', style: { shape: 'star', 'background-color': '#ffd180', 'border-color': '#e6a43c', 'border-width': 12, 'border-opacity': .16, width: 43, height: 43, 'font-size': 24, 'font-weight': 'bold', 'text-background-color': '#67431f', 'text-background-opacity': .75 } },
      { selector: 'edge.path', style: { width: 3, opacity: 1, 'line-color': '#d9c491', 'target-arrow-color': '#d9c491' } },
      { selector: 'node.path', style: { 'border-color': '#d9c491', 'border-opacity': .8 } },
      { selector: 'edge.candidate-edge.candidate-chosen', style: { width: 3, opacity: 1, 'line-color': '#eadfff', 'target-arrow-color': '#eadfff', 'font-weight': 'bold' } },
    ],
  })
  cy.on('tap', 'node', event => select(event.target.id()))
  cy.on('tap', 'edge.candidate-edge', event => selectCandidate({ taskId: event.target.data('taskId'), candidateId: event.target.data('candidateId') }))
  cy.on('dragfree', 'node', event => remember(event.target))
  cy.on('pan zoom', () => {
    zoom.value = Math.round((cy?.zoom() ?? 1) * 100)
    if (suppressViewport || !editable.value || preview.value) return
    beginChange()
    viewportDirty.value = true
  })
  resize = new ResizeObserver(() => cy?.resize())
  if (canvas.value) resize.observe(canvas.value)
  refreshGraph()
  applyViewport(savedViewport.value)
})
onUnmounted(() => { resize?.disconnect(); cy?.destroy(); store.changes.delete('layout') })
</script>

<template>
  <div class="graph-workspace">
  <section class="galaxy" aria-label="知识星图">
    <div class="galaxy-caption"><span>{{ centerId ? '知识邻域' : '知识星图' }}</span><small>{{ displayed.nodes.length }} 个概念 · {{ displayed.edges.length }} 条真实关系</small><small v-if="visibleCandidateEdges.length" class="candidate-count">{{ visibleCandidateEdges.length }} 条待确认候选 · 虚线层</small></div>
    <div ref="canvas" class="galaxy-canvas" role="img" aria-label="概念关系画布；可通过左侧列表选择节点，通过下方按钮移动位置；开启候选层后可在候选列表审阅虚线关系" />
    <div v-if="!displayed.nodes.length" class="galaxy-empty"><span aria-hidden="true">✧</span><h2>从一个概念开始</h2><p>创建概念，再添加有意义的关系。<br />你的知识会在同一片星空中逐渐相连。</p></div>
    <div class="galaxy-actions">
      <div class="galaxy-legend"><span><i class="legend-core" />核心关系</span><span><i class="legend-related" />关联关系</span><span><i class="legend-candidate" />候选关系</span></div>
      <button :disabled="!editable || !!preview" aria-label="缩小星图" @click="magnify(.8)">−</button><span>{{ zoom }}%</span><button :disabled="!editable || !!preview" aria-label="放大星图" @click="magnify(1.25)">＋</button><button :disabled="!editable || !!preview" @click="fit">全景</button><button :disabled="!selectedConcept || dirty || writer.locked.value" @click="focus()">相邻</button><button v-if="centerId" :disabled="dirty || writer.locked.value" @click="showAll">全图</button>
    </div>
    <div v-if="selectedId && selectedConcept" class="node-movement" aria-label="通过键盘按钮移动选中节点">
      <span>移动节点</span><button :disabled="!editable || !!preview" aria-label="向左移动" @click="nudge(-20, 0)">←</button><button :disabled="!editable || !!preview" aria-label="向上移动" @click="nudge(0, -20)">↑</button><button :disabled="!editable || !!preview" aria-label="向下移动" @click="nudge(0, 20)">↓</button><button :disabled="!editable || !!preview" aria-label="向右移动" @click="nudge(20, 0)">→</button>
    </div>
    <div v-if="dirty || writer.error.value || displayed.truncated || notice || stateQuery.error.value || neighborhoodQuery.error.value || !paired" class="galaxy-status" aria-live="polite">
      <p v-if="!paired && !dirty">正在对齐坐标与镜头的修订，完成后可编辑布局。<button :disabled="!readable || writer.locked.value" @click="refreshPair(true)">重新读取一致布局</button></p>
      <p v-if="displayed.truncated">当前是部分图谱，最多显示 200 个概念和 600 条关系。完整对象关系可在详情查看。</p>
      <p v-if="notice">{{ notice }}</p>
      <p v-if="stateQuery.error.value" role="alert">{{ errorText(stateQuery.error.value) }} <button @click="stateQuery.refetch()">重试读取镜头</button></p>
      <p v-if="centerId && neighborhoodQuery.error.value" role="alert">{{ errorText(neighborhoodQuery.error.value) }} <button :disabled="dirty" @click="showAll">返回全图</button></p>
      <p v-if="writer.error.value" role="alert">{{ errorText(writer.error.value) }}</p>
      <template v-if="preview"><span>重新布局预览</span><NButton size="small" @click="acceptPreview">采用并保存</NButton><NButton size="small" @click="cancelPreview">取消预览</NButton></template>
      <template v-else-if="dirty"><span>布局或镜头尚未保存</span><NButton v-if="writer.pending.value && !writer.busy.value" size="small" @click="writer.retry">用原请求重试</NButton><NButton v-else size="small" :loading="writer.busy.value" :disabled="!editable" @click="save">保存布局</NButton><NButton size="small" :disabled="writer.locked.value" @click="discard">重新读取布局</NButton></template>
      <NButton v-else-if="writer.pending.value && !writer.busy.value" size="small" @click="writer.retry">用原请求重试</NButton>
    </div>
    <details class="graph-explorer"><summary>探索与布局工具</summary><div class="graph-tool-content">
      <div class="graph-tool-row"><NButton size="small" :disabled="navigationIndex <= 0" @click="navigateHistory(-1)">后退</NButton><NButton size="small" :disabled="navigationIndex >= navigation.length - 1" @click="navigateHistory(1)">前进</NButton><NButton size="small" :disabled="!selectedConcept || dirty || writer.locked.value" @click="focus(selectedId, 2)">展开两层邻域</NButton></div>
      <p>选择、世界坐标与镜头分别保存。主题过滤不会重新排列节点。</p>
      <label for="graph-node-list">当前窗口概念（键盘可用）</label><select id="graph-node-list" :value="selectedId ?? ''" @change="select(($event.target as HTMLSelectElement).value)"><option value="" disabled>选择概念</option><option v-for="node in displayed.nodes" :key="node.id" :value="node.id">{{ node.title }}</option></select>
      <ul v-if="connectedEdges.length" class="graph-edge-list"><li v-for="edge in connectedEdges" :key="edge.id"><button @click="select(edge.sourceId)">{{ nodeName(edge.sourceId) }}</button><span>{{ ['RELATED', 'CONTRASTS'].includes(edge.type) ? '↔' : '→' }} {{ relationNames[edge.type] }}</span><button @click="select(edge.targetId)">{{ nodeName(edge.targetId) }}</button></li></ul>
      <p v-else-if="selectedId">当前窗口内没有相连边；完整关系请查看对象详情。</p>
      <h3>查找语义路径</h3><form class="graph-tool-row" @submit.prevent="pathSearch = pathText.trim()"><NInput v-model:value="pathText" :maxlength="100" placeholder="查找目标概念" aria-label="按标题查找路径目标" /><NButton size="small" attr-type="submit">查找</NButton></form>
      <p v-if="candidatesQuery.error.value" role="alert">{{ errorText(candidatesQuery.error.value) }}</p><NSelect v-model:value="pathTarget" :options="targetOptions" placeholder="选择目标概念" aria-label="路径目标概念" filterable /><NSelect v-model:value="pathType" :options="relationOptions" clearable placeholder="全部语义关系" aria-label="路径关系类型" /><NButton size="small" :disabled="!selectedConcept || !pathTarget || !readable" :loading="pathQuery.isFetching.value" @click="findPath">从当前概念查找</NButton>
      <p v-if="pathQuery.error.value" role="alert">{{ errorText(pathQuery.error.value) }}</p><p v-if="pathQuery.data.value && !pathQuery.data.value.found">没有找到符合方向和类型的路径。</p><ol v-if="pathQuery.data.value?.found" class="graph-path"><li v-for="(id, index) in pathQuery.data.value.nodeIds" :key="id"><button @click="select(id)">{{ nodeName(id) }}</button><small v-if="pathQuery.data.value.edges[index]">经由 {{ relationNames[pathQuery.data.value.edges[index]!.type] }}</small></li></ol><p v-if="pathQuery.data.value?.truncated">关系超过查询边界，结果不代表全图结论。</p>
      <h3>布局与撤销</h3><NButton size="small" :disabled="!editable || !!preview || !displayed.nodes.length" @click="previewLayout">预览环形重排</NButton><p>撤销恢复所选保存之前的坐标和镜头，保留正文与后来创建的概念。</p><p v-if="historyQuery.error.value" role="alert">{{ errorText(historyQuery.error.value) }} <button @click="historyQuery.refetch()">重试</button></p><p v-else-if="!historyQuery.data.value?.items.length">还没有布局保存历史。</p><ul class="graph-history"><li v-for="entry in historyQuery.data.value?.items" :key="entry.revision"><span>修订 {{ entry.revision }} · {{ entry.positionCount }} 个位置<br /><small>{{ new Date(entry.createdAt).toLocaleString() }}</small></span><NButton size="small" :disabled="dirty || !editable" @click="restore(entry.revision)">撤销此变更</NButton></li></ul><div class="graph-tool-row"><NButton size="small" :disabled="!historyPages.length" @click="previousHistory">较新</NButton><NButton size="small" :disabled="!historyQuery.data.value?.nextCursor" @click="nextHistory">更早</NButton></div>
    </div></details>
  </section>
  <RelationGraphCandidates :node-ids="nodeIds" :selected-candidate="selectedCandidate" :disabled="!readable" @projection="projectCandidates" @select="selectCandidate" />
  </div>
</template>

<style scoped>
.graph-workspace { display: flex; flex-direction: column; min-height: 100%; min-width: 0; }
.graph-workspace > .galaxy { flex: 1 0 480px; min-height: 480px; }
.galaxy-caption .candidate-count { color: #d8c7ff; }
.graph-explorer { position: absolute; z-index: 4; top: 16px; right: 15px; max-width: min(320px, calc(100% - 30px)); color: #dce9ef; background: #182434; border: 1px solid #36485b; border-radius: 7px; font-size: 12px; }
.graph-explorer > summary { cursor: pointer; padding: 9px 12px; }
.graph-tool-content { display: grid; gap: 10px; width: 300px; max-width: 100%; max-height: min(65vh, 600px); overflow: auto; padding: 12px; border-top: 1px solid #36485b; }
.graph-tool-content p { color: #aebfce; line-height: 1.6; margin: 0; }
.graph-tool-content h3 { font-size: 13px; margin: 8px 0 0; }
.graph-tool-content select { width: 100%; color: #e6eff6; background: #23354a; padding: 8px; border: 1px solid #5c748b; border-radius: 4px; }
.graph-tool-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.graph-tool-row :deep(.n-input) { flex: 1; min-width: 140px; }
.graph-history, .graph-edge-list { display: grid; gap: 9px; list-style: none; padding: 0; margin: 0; }
.graph-history li { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.graph-edge-list li { display: flex; flex-wrap: wrap; gap: 5px; }
.graph-edge-list button, .graph-path button { text-align: left; color: #a8dcd3; text-decoration: underline; text-underline-offset: 3px; }
.graph-path { margin: 0; padding-left: 20px; max-height: 220px; overflow: auto; }
.graph-path li { padding: 4px 0; }
.graph-path small { display: block; color: #b7c4d1; }
.graph-explorer :focus-visible, .galaxy-actions button:focus-visible, .node-movement button:focus-visible { outline: 2px solid #c6e9e1; outline-offset: 2px; }
.graph-explorer button:disabled, .galaxy-actions button:disabled, .node-movement button:disabled { opacity: .5; cursor: not-allowed; }
@media (max-width: 650px) { .graph-explorer { top: 62px; } .galaxy-status { top: 105px; } .graph-tool-content { max-height: 48vh; } }
@media (prefers-reduced-motion: reduce) { .galaxy *, .galaxy *::before, .galaxy *::after { animation: none !important; transition: none !important; } }
</style>

<style scoped>
.graph-workspace > .galaxy { min-height: 755px; background: radial-gradient(ellipse at 45% 50%, #825c2117, transparent 52%); overflow: visible; }.galaxy-caption { top: 15px; left: 25px; color: #b5aa99; font-size: 16px; }.galaxy-caption > span { display: none; }.galaxy-caption small { font-size: 14px; color: #a69c8d; }.galaxy-empty { width: 340px; }.galaxy-empty > span { color: #e8b66d; font-size: 62px; }.galaxy-empty h2 { font-size: 25px; color: #f4e8d5; }.galaxy-empty p { font-size: 17px; color: #a69a87; }
.galaxy-actions { left: 50%; right: auto; bottom: 0; transform: translateX(-50%); gap: 12px; padding: 12px 20px; border: 1px solid #77603b; border-radius: 34px; background: #14110be8; color: #d1c9bc; font-size: 19px; white-space: nowrap; }.galaxy-actions button { min-width: 36px; min-height: 36px; font-size: 20px; border: 1px solid #ffffff0c; border-radius: 50%; }.galaxy-actions button:nth-last-child(-n+3) { font-size: 15px; border-radius: 18px; }.galaxy-legend { display: flex; gap: 20px; padding-right: 20px; border-right: 1px solid #ffffff26; }.galaxy-legend span { display: flex; align-items: center; gap: 10px; font-size: 18px; }.galaxy-legend i { width: 15px; height: 15px; border-radius: 50%; }.legend-core { background: #e6ad51; }.legend-related { background: #72cec7; }.legend-candidate { background: #8b8578; }.node-movement { background: #1d170ee8; border-color: #6c5335; border-radius: 20px; bottom: 77px; left: 22px; font-size: 15px; }.node-movement button { font-size: 19px; }
.graph-explorer { top: 50px; background: #1a150fe8; border-color: #5a4530; border-radius: 15px; color: #e8dcc9; font-size: 15px; }.graph-tool-content { border-color: #5a4530; }.graph-tool-content p { color: #b5a58e; }.graph-tool-content select { background: #241b12; color: #f1e0c7; border-color: #655137; }.galaxy-status { border-radius: 13px; background: #2b2116eb; color: #e6d4b9; }.galaxy-status p { font-size: 14px; color: inherit; }.galaxy-caption .candidate-count { color: #e8b86c; }
@media (max-width: 1250px) { .galaxy-legend { gap: 12px; padding-right: 12px; }.galaxy-legend span { font-size: 14px; gap: 6px; }.galaxy-actions { gap: 6px; padding: 8px 12px; }.galaxy-actions button:nth-last-child(-n+3) { display: none; } }
@media (max-width: 600px) { .graph-workspace > .galaxy { min-height: 600px; }.galaxy-legend { display: none; }.galaxy-actions button:nth-last-child(-n+3) { display: inline-block; }.galaxy-empty { width: 270px; }.graph-explorer { top: 50px; } }
</style>
