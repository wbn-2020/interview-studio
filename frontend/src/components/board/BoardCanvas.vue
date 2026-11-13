<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { Handle, Position, VueFlow, type Connection, type Edge, type Node, type NodeChange, type ViewportTransform, type VueFlowStore } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import { boardReferenceKey, boardReferenceLabels, type BoardEdge, type BoardNode, type BoardReference, type BoardViewport } from '../../api/boards'

const props = defineProps<{ nodes: BoardNode[]; edges: BoardEdge[]; references: BoardReference[]; viewport: BoardViewport; selectedId: string; disabled: boolean }>()
const emit = defineEmits<{
  select: [id: string]; edge: [id: string]; move: [id: string, x: number, y: number];
  connect: [source: string, target: string]; viewport: [value: BoardViewport]; begin: []
}>()
const flowNodes = shallowRef<Node[]>([])
const flowEdges = shallowRef<Edge[]>([])
const flow = shallowRef<VueFlowStore>()
const index = computed(() => new Map(props.nodes.map(node => [node.id, node])))
const refs = computed(() => new Map(props.references.map(item => [boardReferenceKey(item.itemId, item.referenceKind), item])))
function hidden(node: BoardNode): boolean {
  let parent = node.parentId
  const seen = new Set<string>()
  while (parent) {
    if (seen.has(parent)) return true
    seen.add(parent)
    const value = index.value.get(parent)
    if (!value) return false
    if (value.collapsed) return true
    parent = value.parentId
  }
  return false
}
watch(() => [props.nodes, props.references, props.selectedId], () => {
  flowNodes.value = props.nodes.map(node => {
    const source = node.itemId ? refs.value.get(boardReferenceKey(node.itemId, node.referenceKind)) : undefined
    return { id: node.id, type: 'knowledge', position: { x: node.x, y: node.y }, hidden: hidden(node), selected: node.id === props.selectedId,
      data: { title: source?.title ?? (node.type === 'TEXT' ? '临时文本' : '来源不可用'), text: source?.body ?? node.text, group: node.group,
        referenceLabel: node.type === 'REFERENCE' ? boardReferenceLabels[node.referenceKind ?? 'KNOWLEDGE'] : '临时文本',
        status: source?.status, reference: node.type === 'REFERENCE', collapsed: node.collapsed } }
  })
}, { deep: true, immediate: true })
watch(() => [props.edges, props.nodes], () => {
  const visible = new Set(props.nodes.filter(node => !hidden(node)).map(node => node.id))
  const edges: Edge[] = props.edges.map(edge => ({ id: edge.id, source: edge.source, target: edge.target, label: edge.label,
    hidden: !visible.has(edge.source) || !visible.has(edge.target), style: { stroke: 'var(--brand)' } }))
  for (const node of props.nodes) if (node.parentId) edges.push({ id: `parent-${node.id}`, source: node.parentId, target: node.id,
    hidden: !visible.has(node.parentId) || !visible.has(node.id), selectable: false, style: { stroke: 'var(--faint)', strokeDasharray: '4 4' } })
  flowEdges.value = edges
}, { deep: true, immediate: true })
function changes(values: NodeChange[]) {
  if (props.disabled) return
  for (const value of values) {
    if (value.type === 'position' && !value.dragging) {
      const source = index.value.get(value.id)
      // 拖动结束事件可能只带 dragging=false；最终位置取图库已经应用的节点坐标。
      const position = value.position ?? flow.value?.findNode(value.id)?.position
      if (source && position && (source.x !== position.x || source.y !== position.y)) {
        emit('begin'); emit('move', value.id, position.x, position.y)
      }
    }
    if (value.type === 'select' && value.selected && value.id !== props.selectedId) emit('select', value.id)
  }
}
function connect(connection: Connection) {
  if (!props.disabled && connection.source && connection.target) emit('connect', connection.source, connection.target)
}
function moved(viewport: ViewportTransform) {
  if (!props.disabled) emit('viewport', { x: viewport.x, y: viewport.y, zoom: viewport.zoom })
}
watch(() => props.viewport, value => {
  const current = flow.value?.viewport.value
  if (current && (current.x !== value.x || current.y !== value.y || current.zoom !== value.zoom)) void flow.value?.setViewport(value, { duration: 0 })
}, { deep: true })
</script>

<template>
  <div class="board-canvas" aria-label="画布，可使用右侧节点列表和移动按钮完成等价操作">
    <VueFlow v-model:nodes="flowNodes" v-model:edges="flowEdges" :default-viewport="viewport" :min-zoom="0.05" :max-zoom="10"
      :nodes-draggable="!disabled" :nodes-connectable="!disabled" :elements-selectable="!disabled" :delete-key-code="null"
      :pan-on-drag="!disabled" :zoom-on-scroll="!disabled" :zoom-on-pinch="!disabled" :zoom-on-double-click="false"
      :snap-to-grid="true" :snap-grid="[10, 10]" :only-render-visible-elements="true"
      :multi-selection-key-code="null" :selection-key-code="false" :edges-updatable="false" :auto-connect="false"
      @node-click="({ node }) => emit('select', node.id)" @edge-click="({ edge }) => !edge.id.startsWith('parent-') && emit('edge', edge.id)"
      @init="value => flow = value" @nodes-change="changes" @connect="connect" @viewport-change-end="moved">
      <template #node-knowledge="{ data }">
        <Handle type="target" :position="Position.Top" />
        <article class="board-card" :class="{ reference: data.reference }">
          <small>{{ data.referenceLabel }}<span v-if="data.group"> · {{ data.group }}</span></small>
          <strong>{{ data.title }}</strong>
          <p>{{ data.text || '选择此卡片，开始记录想法。' }}</p>
          <small v-if="data.status === 'ARCHIVED'">来源已归档</small>
          <small v-if="data.collapsed">子节点已折叠</small>
        </article>
        <Handle type="source" :position="Position.Bottom" />
      </template>
    </VueFlow>
    <div v-if="!nodes.length" class="board-canvas-empty"><strong>从一个想法开始</strong><p>添加临时文本，或引用已有知识、题目与附件。卡片连线只属于这张画布。</p></div>
  </div>
</template>

<style scoped>
.board-canvas { position: relative; height: min(72vh, 760px); min-height: 480px; background: var(--surface-soft); }
.board-card { width: 222px; padding: 13px 15px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); color: var(--ink); }
.board-card.reference { border-left: 3px solid var(--brand); }
.board-card strong { display: block; margin: 6px 0; line-height: 1.45; overflow-wrap: anywhere; }
.board-card p { display: -webkit-box; -webkit-line-clamp: 5; -webkit-box-orient: vertical; overflow: hidden; white-space: pre-wrap; font-size: 12px; }
.board-card small { color: var(--muted); font-size: 10px; }
:deep(.vue-flow__node.selected .board-card) { outline: 2px solid var(--brand); outline-offset: 3px; }
:deep(.vue-flow__edge-text) { fill: var(--ink); }
.board-canvas-empty { position: absolute; top: 36%; left: 20%; width: 60%; text-align: center; pointer-events: none; }
.board-canvas-empty p { margin-top: 8px; color: var(--muted); font-size: 12px; }
</style>
