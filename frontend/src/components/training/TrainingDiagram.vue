<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, useId, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NInputNumber, NSelect } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { BoardPage } from '../../api/boards'
import type { ChosenVersion } from '../../api/preparation'
import type { TrainingDiagram, TrainingDiagramInput } from '../../api/training'
import { useWorkspaceStore } from '../../state/workspace'
import PreparationPicker from '../prepare/PreparationPicker.vue'

const props = defineProps<{ modelValue: TrainingDiagramInput; disabled?: boolean; allowMaterials: boolean; references?: TrainingDiagram['nodes'] }>()
const emit = defineEmits<{ 'update:modelValue': [value: TrainingDiagramInput]; importBoard: [boardId: string, revision: number] }>()
const store = useWorkspaceStore()
const diagram = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const selectedId = ref('')
const selectedEdgeId = ref('')
const sourceId = ref<string | null>(null)
const targetId = ref<string | null>(null)
const edgeLabel = ref('')
const referencePicker = ref(false)
const pickerGeneration = ref(0)
const boardPicker = ref(false)
const boardCursor = ref<string>()
const boardCursors = ref<(string | undefined)[]>([])
const referenceNames = reactive<Record<string, string>>({})
const localError = ref('')
const arrowId = `answer-arrow-${useId().replace(/[^a-zA-Z0-9-]/g, '')}`
const blocked = computed(() => Boolean(props.disabled) || !store.sessionActive)
const selected = computed(() => diagram.value.nodes.find(node => node.id === selectedId.value))
const selectedEdge = computed(() => diagram.value.edges.find(edge => edge.id === selectedEdgeId.value))
const nodeOptions = computed(() => diagram.value.nodes.map((node, index) => ({ value: node.id, label: `${index + 1}. ${node.label || '未命名节点'}` })))
const nodeIndex = computed(() => new Map(diagram.value.nodes.map(node => [node.id, node])))
const edgeLayouts = computed(() => diagram.value.edges.flatMap(edge => {
  const source = nodeIndex.value.get(edge.source)
  const target = nodeIndex.value.get(edge.target)
  if (!source || !target) return []
  const dx = target.x - source.x
  const dy = target.y - source.y
  if (!dx && !dy) return [{ edge, path: `M ${source.x + 200} ${source.y + 20} C ${source.x + 300} ${source.y - 45}, ${source.x + 300} ${source.y + 113}, ${source.x + 200} ${source.y + 48}`, x: source.x + 268, y: source.y + 6 }]
  // 箭头落在节点边界，避免被随后绘制的节点遮住方向。
  const scale = 1 / Math.max(Math.abs(dx) / 100, Math.abs(dy) / 34)
  return [{ edge, path: `M ${source.x + 100 + dx * scale} ${source.y + 34 + dy * scale} L ${target.x + 100 - dx * scale} ${target.y + 34 - dy * scale}`, x: (source.x + target.x) / 2 + 100, y: (source.y + target.y) / 2 + 24 }]
}))
const bounds = computed(() => {
  const nodes = diagram.value.nodes
  if (!nodes.length) return '0 0 800 360'
  const minX = Math.min(0, ...nodes.map(node => node.x)) - 40
  const minY = Math.min(0, ...nodes.map(node => node.y)) - 40
  return `${minX} ${minY} ${Math.max(700, Math.max(...nodes.map(node => node.x)) - minX + 320)} ${Math.max(320, Math.max(...nodes.map(node => node.y)) - minY + 120)}`
})
const boards = useQuery({
  queryKey: computed(() => ['training-board-picker', store.sessionEpoch, boardCursor.value]),
  enabled: computed(() => store.sessionActive && props.allowMaterials && !blocked.value && boardPicker.value),
  queryFn: () => read<BoardPage>(`/knowledge/boards?limit=20${boardCursor.value ? `&cursor=${encodeURIComponent(boardCursor.value)}` : ''}`),
})
watch([blocked, () => props.allowMaterials, () => store.sessionEpoch, () => props.modelValue, selectedId], () => {
  // 关闭或重新定位选择器会卸载旧请求，不能让旧节点的来源读取落到新节点上。
  pickerGeneration.value++
  referencePicker.value = false
  if (blocked.value || !props.allowMaterials) boardPicker.value = false
}, { flush: 'sync' })
onUnmounted(() => { pickerGeneration.value++ })
function selectNode(id: string) { selectedId.value = id; selectedEdgeId.value = ''; localError.value = '' }
function selectEdge(id: string) { selectedEdgeId.value = id; selectedId.value = ''; localError.value = '' }
function addNode() {
  if (blocked.value || diagram.value.nodes.length >= 80) return
  const id = crypto.randomUUID()
  const index = diagram.value.nodes.length
  diagram.value.nodes.push({ id, label: '', description: '', x: 40 + index % 3 * 260, y: 40 + Math.floor(index / 3) * 140, link: null })
  selectNode(id)
}
function moveNode(x: number, y: number) {
  if (blocked.value || !selected.value) return
  selected.value.x = Math.max(-1000000, Math.min(1000000, selected.value.x + x))
  selected.value.y = Math.max(-1000000, Math.min(1000000, selected.value.y + y))
}
function removeNode() {
  if (blocked.value || !selected.value || !window.confirm('移出这个图节点和相连的边？原知识与材料不受影响，当前图的修改需保存后才生效。')) return
  const id = selected.value.id
  diagram.value.nodes = diagram.value.nodes.filter(node => node.id !== id)
  diagram.value.edges = diagram.value.edges.filter(edge => edge.source !== id && edge.target !== id)
  if (sourceId.value === id) sourceId.value = null
  if (targetId.value === id) targetId.value = null
  selectedId.value = ''
}
function addEdge() {
  if (blocked.value || !sourceId.value || !targetId.value || diagram.value.edges.length >= 160) return
  if (!nodeIndex.value.has(sourceId.value) || !nodeIndex.value.has(targetId.value)) { localError.value = '连线端点已变化，请重新选择。'; return }
  const id = crypto.randomUUID()
  diagram.value.edges.push({ id, source: sourceId.value, target: targetId.value, label: edgeLabel.value })
  edgeLabel.value = ''
  selectEdge(id)
}
function removeEdge() {
  if (blocked.value || !selectedEdge.value) return
  diagram.value.edges = diagram.value.edges.filter(edge => edge.id !== selectedEdgeId.value)
  selectedEdgeId.value = ''
}
function chooseReference(value: ChosenVersion) {
  if (blocked.value || !props.allowMaterials || !selected.value || !['KNOWLEDGE', 'PROJECT', 'FACT'].includes(value.kind)) return
  selected.value.link = { kind: value.kind as 'KNOWLEDGE' | 'PROJECT' | 'FACT', itemId: value.itemId, versionId: value.versionId }
  referenceNames[value.versionId] = `${value.title} · v${value.number}`
  referencePicker.value = false
}
function referenceName() {
  const link = selected.value?.link
  if (!link) return ''
  const reference = props.references?.find(node => node.reference?.versionId === link.versionId)?.reference
  return reference ? `${reference.title} · v${reference.number}` : referenceNames[link.versionId] ?? `${link.kind} · 版本 ${link.versionId.slice(0, 8)}`
}
function importBoard(boardId: string, revision: number) {
  if (blocked.value || !props.allowMaterials) return
  emit('importBoard', boardId, revision)
}
function nextBoards() { if (boards.data.value?.nextCursor) { boardCursors.value.push(boardCursor.value); boardCursor.value = boards.data.value.nextCursor } }
</script>

<template>
  <section class="training-diagram" aria-label="答案结构图">
    <header class="diagram-heading"><div><h3>结构图 <span>{{ diagram.nodes.length }} 节点 · {{ diagram.edges.length }} 条边</span></h3><p class="field-help">图与文字一起保存。用节点说明服务边界，用连线说明数据流；约束和取舍写在回答正文。</p></div><div v-if="!disabled" class="row-actions"><NButton size="small" :disabled="blocked || diagram.nodes.length >= 80" @click="addNode">添加节点</NButton><NButton size="small" :disabled="blocked || !allowMaterials" @click="boardPicker = !boardPicker">导入白板候选</NButton></div></header>
    <p v-if="!allowMaterials && !disabled" class="field-help">关联站内资料或导入学习白板前，需要在本次训练中明确确认辅助条件。</p>
    <p v-if="diagram.boardSource" class="field-help">图源自白板 {{ diagram.boardSource.boardId.slice(0, 8) }} · 修订 {{ diagram.boardSource.revision }}，之后的白板变化不会改动本答案。</p>
    <section v-if="boardPicker && allowMaterials && !blocked" class="board-picker"><h4>选择已保存的学习白板</h4><p class="field-help">导入先生成候选；明确采用后再与本题文字一起保存。</p><p v-if="boards.isPending.value" role="status">正在读取白板…</p><p v-if="boards.error.value" class="notice error" role="alert">{{ errorText(boards.error.value) }}<NButton text @click="boards.refetch()">重试</NButton></p><button v-for="board in boards.data.value?.items" :key="board.id" class="board-option" @click="importBoard(board.id, board.revision)"><strong>{{ board.title }}</strong><span>修订 {{ board.revision }}</span></button><p v-if="boards.data.value && !boards.data.value.items.length" class="field-help">没有可选白板。</p><div v-if="boardCursors.length || boards.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!boardCursors.length" @click="boardCursor = boardCursors.pop()">上一页</NButton><NButton size="small" :disabled="!boards.data.value?.nextCursor" @click="nextBoards">下一页</NButton></div></section>
    <div class="diagram-preview"><svg v-if="diagram.nodes.length" :viewBox="bounds" role="img" aria-label="答案结构图，可在下方节点和连线列表查看及编辑等价内容"><defs><marker :id="arrowId" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="var(--brand)" /></marker></defs><g v-for="layout in edgeLayouts" :key="layout.edge.id"><path :d="layout.path" fill="none" stroke="var(--brand)" stroke-width="2" :marker-end="`url(#${arrowId})`" /><text :x="layout.x" :y="layout.y" text-anchor="middle" class="edge-text">{{ layout.edge.label.slice(0, 28) }}</text></g><g v-for="(node, index) in diagram.nodes" :key="node.id" :transform="`translate(${node.x}, ${node.y})`"><rect width="200" height="68" rx="7" fill="var(--surface)" :stroke="node.id === selectedId ? 'var(--brand)' : 'var(--line)'" :stroke-width="node.id === selectedId ? 3 : 1" /><text x="12" y="27" class="node-text">{{ node.label ? node.label.slice(0, 18) : `节点 ${index + 1}` }}</text><text x="12" y="49" class="node-note">{{ node.link ? '已关联确切材料版本' : node.description.slice(0, 22) || '尚未填写说明' }}</text></g></svg><p v-else class="diagram-empty">还没有图节点。纯文字回答也可以保存和提交。</p></div>
    <div v-if="diagram.nodes.length" class="diagram-controls"><div class="diagram-lists"><h4>节点</h4><button v-for="(node, index) in diagram.nodes" :key="node.id" :class="['diagram-list-row', { selected: selectedId === node.id }]" :aria-current="selectedId === node.id ? 'true' : undefined" @click="selectNode(node.id)">{{ index + 1 }}. {{ node.label || '未命名节点' }}</button><h4 v-if="diagram.edges.length">连线</h4><button v-for="edge in diagram.edges" :key="edge.id" :class="['diagram-list-row', { selected: selectedEdgeId === edge.id }]" @click="selectEdge(edge.id)">{{ nodeIndex.get(edge.source)?.label || '节点' }} → {{ nodeIndex.get(edge.target)?.label || '节点' }}{{ edge.label ? ` · ${edge.label}` : '' }}</button></div><div class="diagram-detail"><template v-if="selected"><label>节点名称<NInput v-model:value="selected.label" :disabled="blocked" :maxlength="200" placeholder="例如：订单服务" /></label><label>职责与说明<NInput v-model:value="selected.description" :disabled="blocked" type="textarea" :maxlength="5000" :autosize="{ minRows: 2, maxRows: 6 }" /></label><div class="coordinate-pair"><label>X 坐标<NInputNumber :value="selected.x" :disabled="blocked" :min="-1000000" :max="1000000" @update:value="value => { if (selected && value !== null) selected.x = value }" /></label><label>Y 坐标<NInputNumber :value="selected.y" :disabled="blocked" :min="-1000000" :max="1000000" @update:value="value => { if (selected && value !== null) selected.y = value }" /></label></div><div v-if="!disabled" class="row-actions"><NButton size="small" :disabled="blocked" @click="moveNode(-40, 0)">左移</NButton><NButton size="small" :disabled="blocked" @click="moveNode(40, 0)">右移</NButton><NButton size="small" :disabled="blocked" @click="moveNode(0, -40)">上移</NButton><NButton size="small" :disabled="blocked" @click="moveNode(0, 40)">下移</NButton></div><p v-if="selected.link" class="field-help">{{ referenceName() }}</p><div v-if="!disabled" class="row-actions"><NButton size="small" :disabled="blocked || !allowMaterials" @click="referencePicker = !referencePicker">{{ selected.link ? '更换材料引用' : '关联知识／项目事实' }}</NButton><NButton v-if="selected.link" size="small" :disabled="blocked" @click="selected.link = null">移除引用</NButton><NButton size="small" quaternary :disabled="blocked" @click="removeNode">移出节点</NButton></div><PreparationPicker v-if="referencePicker && allowMaterials && !blocked" :key="pickerGeneration" :kinds="['KNOWLEDGE', 'PROJECT', 'FACT']" :disabled="blocked" @choose="chooseReference" /></template><template v-else-if="selectedEdge"><label>连线说明<NInput v-model:value="selectedEdge.label" :disabled="blocked" :maxlength="200" /></label><p class="field-help">{{ nodeIndex.get(selectedEdge.source)?.label || '节点' }} → {{ nodeIndex.get(selectedEdge.target)?.label || '节点' }}</p><NButton v-if="!disabled" size="small" :disabled="blocked" @click="removeEdge">移出连线</NButton></template><p v-else class="field-help">选择一个节点或连线查看其内容。</p></div></div>
    <form v-if="!disabled && diagram.nodes.length" class="connect-form" @submit.prevent="addEdge"><label>起点<NSelect v-model:value="sourceId" :disabled="blocked" :options="nodeOptions" placeholder="选择节点" /></label><label>终点<NSelect v-model:value="targetId" :disabled="blocked" :options="nodeOptions" placeholder="选择节点" /></label><label>连线说明<NInput v-model:value="edgeLabel" :disabled="blocked" :maxlength="200" placeholder="例如：同步请求" /></label><NButton attr-type="submit" size="small" :disabled="blocked || !sourceId || !targetId || diagram.edges.length >= 160">添加连线</NButton></form><p v-if="localError" class="notice error" role="alert">{{ localError }}</p>
  </section>
</template>

<style scoped>
.training-diagram { display: grid; gap: 15px; min-width: 0; }
.diagram-heading { display: flex; justify-content: space-between; align-items: start; gap: 14px; }
.diagram-heading h3 span { color: var(--muted); font-size: 12px; font-weight: 400; margin-left: 8px; }
.diagram-heading p { margin-top: 6px; max-width: 70ch; }
.diagram-preview { background: var(--surface-soft); border: 1px solid var(--line); border-radius: 7px; min-height: 200px; }
.diagram-preview svg { display: block; width: 100%; max-height: 450px; min-height: 250px; }
.node-text { fill: var(--ink); font-size: 15px; font-weight: 650; }
.node-note, .edge-text { fill: var(--muted); font-size: 11px; }
.edge-text { paint-order: stroke; stroke: var(--surface-soft); stroke-width: 5px; }
.diagram-empty { padding: 70px 20px; color: var(--muted); text-align: center; font-size: 13px; }
.diagram-controls { display: grid; grid-template-columns: minmax(150px, 1fr) minmax(240px, 2fr); gap: 20px; }
.diagram-lists { min-width: 0; max-height: 450px; overflow-y: auto; }
h4 { margin: 0 0 9px; font-size: 13px; } .diagram-lists h4:not(:first-child) { margin-top: 20px; }
.diagram-list-row { display: block; width: 100%; text-align: left; padding: 9px; border-radius: 5px; font-size: 12px; overflow-wrap: anywhere; }
.diagram-list-row.selected, .diagram-list-row:hover { background: var(--surface-deep); }
.diagram-detail { display: grid; align-content: start; gap: 13px; min-width: 0; }
label { display: grid; gap: 6px; font-size: 12px; color: var(--muted); min-width: 0; }
.coordinate-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.connect-form { display: grid; grid-template-columns: 1fr 1fr 1fr auto; gap: 10px; align-items: end; border-top: 1px solid var(--line); padding-top: 15px; }
.board-picker { display: grid; gap: 9px; padding: 15px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 6px; }
.board-option { display: flex; justify-content: space-between; gap: 15px; padding: 9px 0; text-align: left; border-bottom: 1px solid var(--line); }
.board-option strong { overflow-wrap: anywhere; } .board-option span { font-size: 12px; color: var(--muted); white-space: nowrap; }
@media (max-width: 800px) { .diagram-heading { flex-wrap: wrap; } .diagram-controls { grid-template-columns: 1fr; } .diagram-lists { max-height: 200px; } .connect-form { grid-template-columns: 1fr 1fr; } }
</style>
