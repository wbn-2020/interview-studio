<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import { aiDate, aiOriginStateLabels, aiSourceKindLabel } from '../api/ai'
import type { RelationGraphCandidateEdge, RelationGraphCandidateIdentity, RelationGraphEdge, RelationGraphProjection, RelationGraphTaskPage, RelationGraphTaskSummary } from '../api/relationGraph'
import { relationGraphCandidateEdges, relationGraphCandidateIdentityValid, relationGraphPageValid, relationGraphPaths, relationGraphProjectionValid } from '../api/relationGraph'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

type PageDirection = 'refresh' | 'next' | 'back'
const props = defineProps<{ nodeIds: string[]; selectedCandidate?: RelationGraphCandidateIdentity; disabled?: boolean }>()
const emit = defineEmits<{ projection: [value: RelationGraphCandidateEdge[]]; select: [value: RelationGraphCandidateIdentity | undefined] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), panelId = `relation-graph-candidates-${useId()}`
const active = ref(true), opened = ref(false)
const page = shallowRef<RelationGraphTaskPage>(), pageLoading = ref(false), pageError = ref('')
const cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const selectedTask = shallowRef<RelationGraphTaskSummary>(), projection = shallowRef<RelationGraphProjection>()
const projectionLoading = ref(false), projectionError = ref(''), selectedId = ref<string>()
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking
  && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const nodeSignature = computed(() => JSON.stringify([...new Set(props.nodeIds)].sort()))
const nodes = computed(() => new Set(props.nodeIds))
const visibleEdges = computed(() => readable.value && opened.value && projection.value
  ? relationGraphCandidateEdges(projection.value).filter(edge => nodes.value.has(edge.sourceId) && nodes.value.has(edge.targetId)) : [])
const selected = computed(() => projection.value?.relations.find(edge => edge.id === selectedId.value))
const outsideCount = computed(() => (projection.value?.relations.length ?? 0) - visibleEdges.value.length)
const relationLabels: Record<RelationGraphEdge['type'], string> = {
  PRECEDES: '前置于', PART_OF: '属于（组成部分）', APPLIED_IN: '应用于', RELATED: '相关（无方向）', CONTRASTS: '对比（无方向）',
}
let alive = true, generation = 0, pageSequence = 0, projectionSequence = 0
let attemptedPage: { cursor?: string; direction: PageDirection } = { direction: 'refresh' }

watch(visibleEdges, value => { emit('projection', value) }, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], reset, { flush: 'sync' })
watch(nodeSignature, clearSelection, { flush: 'sync' })
watch(() => JSON.stringify(props.selectedCandidate ?? null), () => {
  const identity = props.selectedCandidate
  if (!identity) {
    // 范围外候选只有列表详情，没有图上高亮；清空图选中不隐藏用户正在读的范围外理由。
    if (selected.value && inGraph(selected.value)) selectedId.value = undefined
    return
  }
  if (!readable.value || !opened.value || !projection.value || !relationGraphCandidateIdentityValid(identity)
    || identity.taskId !== projection.value.task.id) return
  const edge = projection.value.relations.find(item => item.id === identity.candidateId)
  if (edge && inGraph(edge)) selectedId.value = edge.id
}, { flush: 'sync' })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; pageSequence++; projectionSequence++; emit('projection', []); emit('select', undefined) })

function clearSelection() { selectedId.value = undefined; emit('select', undefined) }
function clearProjection() {
  projectionSequence++; projection.value = undefined; projectionLoading.value = false; projectionError.value = ''; clearSelection()
}
function reset() {
  generation++; pageSequence++; opened.value = false; page.value = undefined; pageLoading.value = false; pageError.value = ''
  cursor.value = undefined; previous.value = []; selectedTask.value = undefined; attemptedPage = { direction: 'refresh' }; clearProjection()
}
function toggle() {
  if (!readable.value) return
  if (opened.value) { reset(); return }
  opened.value = true; void loadPage(undefined, 'refresh')
}
async function loadPage(target?: string, direction: PageDirection = 'refresh') {
  if (!readable.value || !opened.value || pageLoading.value) return
  const epoch = store.sessionEpoch, stamp = generation, ticket = ++pageSequence, originalCursor = cursor.value
  attemptedPage = { cursor: target, direction }; page.value = undefined; pageLoading.value = true; pageError.value = ''
  selectedTask.value = undefined; clearProjection()
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch && stamp === generation && ticket === pageSequence
  try {
    // 专用目录只读取成功关系任务元数据；不扫描其他 AI 用途，也不连带读取任务正文。
    const value = await read<RelationGraphTaskPage>(relationGraphPaths.tasks(target, 20))
    if (!current()) return
    if (!relationGraphPageValid(value, target, 20)) throw new ApiError(502, 'RELATION_GRAPH_DIRECTORY_INVALID', '关系任务目录与分页位置不一致，请重新读取。')
    if (direction === 'next') previous.value.push(originalCursor)
    else if (direction === 'back') previous.value.pop()
    cursor.value = target; page.value = value
  } catch (error) { if (current()) pageError.value = errorText(error) }
  finally { if (current()) pageLoading.value = false }
}
function retryPage() { void loadPage(attemptedPage.cursor, attemptedPage.direction) }
function firstPage() {
  if (!readable.value || !opened.value || pageLoading.value) return
  previous.value = []; cursor.value = undefined; void loadPage(undefined, 'refresh')
}
function chooseTask(task: RelationGraphTaskSummary) {
  if (!readable.value || !opened.value || pageLoading.value || !page.value?.items.includes(task)) return
  selectedTask.value = task; void loadProjection()
}
async function loadProjection() {
  const task = selectedTask.value
  if (!readable.value || !opened.value || !task || pageLoading.value) return
  clearProjection()
  const epoch = store.sessionEpoch, stamp = generation, ticket = ++projectionSequence
  projectionLoading.value = true
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch && stamp === generation
    && ticket === projectionSequence && selectedTask.value?.id === task.id
  try {
    const value = await read<RelationGraphProjection>(relationGraphPaths.projection(task.id))
    if (!current()) return
    if (!relationGraphProjectionValid(value, task.id) || value.task.revision < task.revision || value.task.createdAt !== task.createdAt) {
      throw new ApiError(502, 'RELATION_GRAPH_PROJECTION_INVALID', '候选投影与所选任务或修订不一致，请重新读取后核对。')
    }
    selectedTask.value = value.task; projection.value = value
  } catch (error) { if (current()) projectionError.value = errorText(error) }
  finally { if (current()) projectionLoading.value = false }
}
function inGraph(edge: RelationGraphEdge) { return nodes.value.has(edge.sourceItemId) && nodes.value.has(edge.targetItemId) }
function chooseCandidate(edge: RelationGraphEdge) {
  if (!readable.value || !opened.value || projectionLoading.value || !projection.value?.relations.includes(edge)) return
  if (selectedId.value === edge.id) { clearSelection(); return }
  selectedId.value = edge.id
  emit('select', inGraph(edge) ? { taskId: projection.value.task.id, candidateId: edge.id } : undefined)
}
function sourceName(id: string) {
  const source = projection.value?.sources.find(item => item.itemId === id)
  return source ? `${source.title} · v${source.number}` : '来源待核对'
}
function arrow(edge: RelationGraphEdge) { return edge.type === 'RELATED' || edge.type === 'CONTRASTS' ? '↔' : '→' }
</script>

<template>
  <section class="graph-candidates" aria-label="星图 AI 关系候选">
    <div class="candidate-heading"><div><h3>AI 关系候选</h3><p class="field-help">将待审建议与当前知识节点对照。</p></div><NButton size="small" :disabled="!readable" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起候选' : '读取候选任务' }}</NButton></div>
    <div v-if="opened && readable" :id="panelId" class="candidate-content">
      <p class="field-help">先选择一个已生成的关系任务。候选连线只连接当前图中已有的两个节点；节点位置和正式关系保持原有含义。</p>
      <p v-if="pageLoading" role="status">正在读取关系任务目录…</p>
      <div v-if="pageError" class="notice error" role="alert"><p>{{ pageError }}</p><NButton text size="small" :disabled="!readable || pageLoading" @click="retryPage">重读这页目录</NButton></div>
      <template v-if="page">
        <p v-if="!page.items.length" class="field-help" role="status">{{ cursor ? '这页没有更多已生成的关系任务。' : '尚无已生成的关系任务。可在 AI 工作台中选择知识版本生成关系建议。' }}</p>
        <ul v-else class="task-list" aria-label="已生成的关系任务"><li v-for="task in page.items" :key="task.id">
          <div><strong>{{ task.title }}</strong><time :datetime="task.createdAt">{{ aiDate(task.createdAt) }}</time><span class="field-help">{{ task.provider }} · {{ task.model }}</span></div>
          <NButton size="small" :disabled="!readable || pageLoading" :aria-pressed="selectedTask?.id === task.id" :aria-controls="`${panelId}-projection`" :aria-label="`查看 ${task.title}，${aiDate(task.createdAt)} 的关系候选`" @click="chooseTask(task)">{{ selectedTask?.id === task.id ? '重读这个任务' : '查看候选' }}</NButton>
        </li></ul>
      </template>
      <div class="row-actions"><NButton v-if="previous.length" size="small" :disabled="!readable || pageLoading" @click="loadPage(previous[previous.length - 1], 'back')">上一页</NButton><NButton v-if="page?.nextCursor" size="small" :disabled="!readable || pageLoading" @click="loadPage(page.nextCursor, 'next')">下一页</NButton><NButton text size="small" :disabled="!readable || pageLoading" @click="loadPage(cursor)">刷新这页目录</NButton><NButton v-if="cursor" text size="small" :disabled="!readable || pageLoading" @click="firstPage">回到第一页</NButton></div>
      <section v-if="selectedTask" :id="`${panelId}-projection`" class="task-projection" :aria-busy="projectionLoading" aria-label="当前任务候选投影">
        <header><h4>{{ selectedTask.title }}</h4><NButton text size="small" :disabled="!readable || pageLoading || projectionLoading" @click="loadProjection">刷新本任务候选</NButton></header>
        <p v-if="projectionLoading" role="status">正在读取所选任务的待审关系候选…</p>
        <div v-if="projectionError" class="notice error" role="alert"><p>{{ projectionError }}</p><NButton text size="small" :disabled="!readable || projectionLoading" @click="loadProjection">重读候选</NButton></div>
        <template v-if="projection">
          <dl class="candidate-counts"><div><dt>原始候选</dt><dd>{{ projection.totalCandidates }}</dd></div><div><dt>已决定</dt><dd>{{ projection.decidedCount }}</dd></div><div><dt>待审</dt><dd>{{ projection.relations.length }}</dd></div><div><dt>当前图内</dt><dd>{{ visibleEdges.length }}</dd></div><div><dt>范围外</dt><dd>{{ outsideCount }}</dd></div></dl>
          <p class="field-help">数量属于这一个任务。范围外表示至少一端不在当前显示节点中，可在下方回看原建议；候选保留原始 rN 编号。</p>
          <p :class="projection.sourcesCurrent ? 'field-help' : 'notice'" role="status">{{ projection.sourcesCurrent ? '本次读取时，任务的全部固定知识版本及所含直接原文依据均已核对可用。' : '当前无法确认全部知识版本及所含直接原文依据仍可用于新采用，历史候选与出处仍可回看。' }} 来源状态不证明关系语义正确。</p>
          <section class="candidate-summary" aria-label="任务原始摘要与限制"><h4>模型原摘要</h4><p class="original-text">{{ projection.summary }}</p><h4>适用限制</h4><p class="original-text">{{ projection.limitations }}</p><p class="field-help">{{ projection.validationNote }}</p></section>
          <p v-if="!projection.totalCandidates" class="notice" role="status">这个任务成功返回了 0 条关系候选。</p>
          <p v-else-if="!projection.relations.length" class="notice" role="status">本任务的 {{ projection.totalCandidates }} 条候选都已有决定。可前往原任务回看处理记录。</p>
          <ul v-else class="candidate-list" aria-label="待审关系建议"><li v-for="edge in projection.relations" :key="edge.id" :class="{ selected: selectedId === edge.id }">
            <div><span class="candidate-number">{{ edge.id }} · {{ inGraph(edge) ? '当前图内' : '当前范围外' }}</span><p class="relation-endpoints"><span>{{ sourceName(edge.sourceItemId) }}</span><strong>{{ arrow(edge) }} {{ relationLabels[edge.type] }}</strong><span>{{ sourceName(edge.targetItemId) }}</span></p></div>
            <NButton size="small" :disabled="!readable || projectionLoading" :aria-expanded="selectedId === edge.id" :aria-controls="`${panelId}-selected`" :aria-label="`${selectedId === edge.id ? '收起' : '查看'}候选 ${edge.id} 的原始理由`" @click="chooseCandidate(edge)">{{ selectedId === edge.id ? '收起原建议' : '查看原建议' }}</NButton>
          </li></ul>
          <article v-if="selected" :id="`${panelId}-selected`" class="candidate-detail" aria-label="所选关系候选详情">
            <header><h4>原候选 {{ selected.id }} · 待审</h4><span class="field-help">{{ inGraph(selected) ? '已在当前图内选中' : '至少一端不在当前图内' }}</span></header>
            <p class="relation-endpoints"><span>{{ sourceName(selected.sourceItemId) }}</span><strong>{{ arrow(selected) }} {{ relationLabels[selected.type] }}</strong><span>{{ sourceName(selected.targetItemId) }}</span></p>
            <h4>模型原始理由</h4><p class="original-text">{{ selected.reason }}</p><p class="field-help">原引用 {{ selected.citationCount }} 处。引用片段和逐项审阅在原任务中查看。</p>
            <RouterLink class="text-link" :to="{ name: 'ai', query: { item: projection.task.id } }">前往原任务审阅 {{ selected.id }}：编辑、采用、补充或忽略</RouterLink>
          </article>
          <section class="fixed-sources" aria-label="生成时选择的固定知识版本"><h4>本任务当次选择的全部知识版本</h4><ul><li v-for="source in projection.sources" :key="`${source.itemId}/${source.versionId}`">
            <RouterLink v-if="source.state !== 'UNAVAILABLE'" class="text-link" :to="{ name: 'knowledge', query: { item: source.itemId, version: source.versionId } }">{{ source.title }} · v{{ source.number }}</RouterLink><span v-else>{{ source.title }} · v{{ source.number }}</span><span class="source-state">{{ aiSourceKindLabel(source.kind) }} · {{ aiOriginStateLabels[source.state] }}</span>
          </li></ul><p class="field-help">这里列出固定知识版本及本次读取的状态。任务选中的资料、训练、人工／AI 反馈或面后回忆片段及引用，在原任务的完整清单中核对；其中保留当次固定条件、反馈决定与限制、原报告 AI 未审等级和限定、回忆不确定性。查看确切知识版本会进入知识详情。</p></section>
          <RouterLink class="text-link" :to="{ name: 'ai', query: { item: projection.task.id } }">打开原 AI 任务与处理记录</RouterLink>
        </template>
      </section>
    </div>
  </section>
</template>

<style scoped>
.graph-candidates { min-width: 0; margin-top: 14px; padding: 15px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface); }.candidate-heading, header { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 8px 14px; }.candidate-heading > div { display: grid; gap: 6px; min-width: 0; }.candidate-content, .task-projection, .candidate-summary, .candidate-detail, .fixed-sources { display: grid; gap: 12px; min-width: 0; }.candidate-content { margin-top: 14px; }.task-projection, .candidate-summary, .fixed-sources { padding-top: 14px; border-top: 1px solid var(--line); }h3, h4, p { margin: 0; }h3 { font-size: 14px; }h4, strong { font-size: 13px; }.task-list, .candidate-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }.task-list { max-height: 340px; overflow: auto; }.task-list li, .candidate-list li { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px 14px; padding: 10px; min-width: 0; border: 1px solid var(--line); border-radius: 5px; }.task-list li > div, .candidate-list li > div { display: grid; gap: 6px; min-width: 0; flex: 1 1 200px; }.candidate-list li.selected { border-color: var(--brand); background: var(--surface-soft); }.task-list time, .source-state, .candidate-number { color: var(--muted); font-size: 12px; }.source-state { display: block; margin-top: 4px; }.candidate-counts { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; margin: 0; }.candidate-counts > div { display: grid; gap: 4px; min-width: 0; }.candidate-counts dt { font-size: 12px; color: var(--muted); }.candidate-counts dd { margin: 0; font-size: 19px; font-variant-numeric: tabular-nums; }.relation-endpoints { display: flex; flex-wrap: wrap; align-items: baseline; gap: 5px 8px; font-size: 13px; }.relation-endpoints > * { min-width: 0; }.original-text { white-space: pre-wrap; font-size: 13px; line-height: 1.8; }.candidate-detail { padding: 14px; border-left: 3px solid var(--brand); background: var(--surface-soft); }.fixed-sources ul { display: grid; gap: 10px; margin: 0; padding-left: 20px; font-size: 13px; }.field-help, .notice, .text-link, h3, h4, strong, p, li, dt { overflow-wrap: anywhere; }.text-link { font-size: 13px; }.row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 9px 13px; }@media (max-width: 560px) { .graph-candidates { padding: 12px; }.candidate-counts { grid-template-columns: repeat(3, minmax(0, 1fr)); }.candidate-detail { padding: 11px; } }
</style>
