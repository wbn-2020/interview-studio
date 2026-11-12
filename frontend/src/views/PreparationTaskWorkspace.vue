<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import type { PreparationTask, PreparationTaskPage, PreparationTaskStatus, PreparationTaskTarget } from '../api/preparationTask'
import { preparationTaskDateRangeProblem, preparationTaskStatusLabels } from '../api/preparationTask'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import PreparationTaskTargetPicker from '../components/prepare/PreparationTaskTargetPicker.vue'

const PreparationTaskEditor = defineAsyncComponent(() => import('../components/prepare/PreparationTaskEditor.vue'))
type TargetFilter = Pick<PreparationTaskTarget, 'itemId' | 'title'>
type Filters = { status: PreparationTaskStatus | ''; target: TargetFilter | null; from: string; to: string }
const emptyFilters = (): Filters => ({ status: '', target: null, from: '', to: '' })
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const readable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge)
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const creating = computed(() => route.query.create === '1')
const filterInput = ref<Filters>(emptyFilters())
const applied = shallowRef<Filters>(emptyFilters())
const filterSignature = computed(() => JSON.stringify(applied.value))
const filterProblem = computed(() => preparationTaskDateRangeProblem(filterInput.value.from, filterInput.value.to))
const filterChanged = computed(() => JSON.stringify(filterInput.value) !== filterSignature.value)
const targetPickerOpen = ref(false)
const targetPickerBusy = ref(false)
const targetPickerGeneration = ref(0)
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const statuses: PreparationTaskStatus[] = ['TODO', 'IN_PROGRESS', 'PAUSED', 'DONE', 'CANCELLED']
const statusOptions = [{ label: '全部状态', value: '' }, ...statuses.map(value => ({ label: preparationTaskStatusLabels[value], value }))]
let alive = true
let listGeneration = 0
const list = useQuery({
  queryKey: computed(() => ['preparation-tasks', store.sessionEpoch, 'list', filterSignature.value, cursor.value]),
  enabled: readable,
  queryFn: async () => {
    const epoch = store.sessionEpoch
    const generation = ++listGeneration
    const signature = filterSignature.value
    const pageCursor = cursor.value
    const filters = applied.value
    const parameters = new URLSearchParams({ limit: '20' })
    if (filters.status) parameters.set('status', filters.status)
    if (filters.target) parameters.set('targetId', filters.target.itemId)
    if (filters.from && filters.to) { parameters.set('from', filters.from); parameters.set('to', filters.to) }
    if (pageCursor) parameters.set('cursor', pageCursor)
    const value = await read<PreparationTaskPage>(`/prepare/tasks?${parameters}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || generation !== listGeneration
      || signature !== filterSignature.value || pageCursor !== cursor.value) throw new ApiError(0, 'PREPARATION_TASK_LIST_CHANGED', '筛选或访问条件已变化，请重新读取任务列表。')
    return value
  },
})
watch([readable, () => store.sessionEpoch], () => {
  listGeneration++
  targetPickerGeneration.value++
  targetPickerOpen.value = false
  targetPickerBusy.value = false
}, { flush: 'sync' })
watch(() => store.sessionEpoch, () => { cursor.value = undefined; cursors.value = [] })
onUnmounted(() => { alive = false; listGeneration++ })

function applyFilters() {
  if (!readable.value || filterProblem.value || targetPickerBusy.value) return
  applied.value = { ...filterInput.value, target: filterInput.value.target ? { ...filterInput.value.target } : null }
  cursor.value = undefined
  cursors.value = []
  targetPickerOpen.value = false
}
function clearFilters() {
  if (!readable.value || targetPickerBusy.value) return
  filterInput.value = emptyFilters()
  applyFilters()
}
function chooseFilterTarget(value: TargetFilter) {
  if (!readable.value) return
  filterInput.value.target = { itemId: value.itemId, title: value.title }
  targetPickerOpen.value = false
}
function filterBySavedTarget(value: PreparationTaskTarget) {
  if (!readable.value || targetPickerBusy.value) return
  // 按目标身份筛选可包含旧版本或归档目标；不能把创建时的“当前活跃目标”限制套到历史任务。
  chooseFilterTarget(value)
  applyFilters()
}
function open(id: string) { if (readable.value) void router.push({ name: 'preparation-tasks', query: { item: id } }) }
function close() { if (readable.value) void router.push({ name: 'preparation-tasks' }) }
function create() { if (readable.value) void router.push({ name: 'preparation-tasks', query: { create: '1' } }) }
function createWithAi() { if (readable.value) void router.push({ name: 'ai', query: { create: '1', purpose: 'PREPARATION_PLAN' } }) }
function refresh() { void queryClient.invalidateQueries({ queryKey: ['preparation-tasks'] }) }
function created(task: PreparationTask) { open(task.id) }
function next() {
  if (readable.value && !list.isFetching.value && list.data.value?.nextCursor) {
    cursors.value.push(cursor.value)
    cursor.value = list.data.value.nextCursor
  }
}
</script>

<template>
  <header class="page-heading"><div><p class="section-label">把准备事项落到行动</p><h1>准备任务</h1><p class="muted">写清要做的事、预计工作量和计划日期，保留每次修改与处理记录。</p></div><div class="row-actions"><NButton :disabled="!readable" @click="createWithAi">生成准备建议</NButton><NButton type="primary" :disabled="!readable" @click="create">＋ 新建准备任务</NButton></div></header>
  <form class="task-filters" aria-label="筛选准备任务" @submit.prevent="applyFilters">
    <div class="filter-fields"><label>任务状态<NSelect v-model:value="filterInput.status" :options="statusOptions" :disabled="!readable" aria-label="任务状态" /></label><label>计划日期从<input v-model="filterInput.from" type="date" min="1000-01-01" max="9999-12-31" :disabled="!readable" /></label><label>计划日期到<input v-model="filterInput.to" type="date" min="1000-01-01" max="9999-12-31" :disabled="!readable" /></label><div class="row-actions"><NButton attr-type="submit" :disabled="!readable || !!filterProblem || targetPickerBusy">应用筛选</NButton><NButton text :disabled="!readable || targetPickerBusy" @click="clearFilters">清空筛选</NButton></div></div>
    <div class="filter-target"><span class="field-help">{{ filterInput.target ? `关联目标：${filterInput.target.title}` : '所有目标（包含未关联目标的任务）' }}</span><NButton size="small" :disabled="!readable || targetPickerBusy" @click="targetPickerOpen = !targetPickerOpen">{{ targetPickerOpen ? '收起选择' : '选择目标' }}</NButton><NButton v-if="filterInput.target" text size="small" :disabled="!readable || targetPickerBusy" @click="filterInput.target = null">清除目标条件</NButton></div>
    <p class="field-help">日期区间按每条任务保存的计划日期筛选，包含首尾，最多 366 天；设置日期条件后不包含未安排日期的任务。不同任务的日期不换算到同一时区。</p>
    <p v-if="filterProblem" class="notice error" role="alert">{{ filterProblem }}</p><p v-else-if="filterChanged" class="field-help" role="status">筛选条件尚未应用，列表仍显示上一次结果。</p>
  </form>
  <div v-if="targetPickerOpen" class="filter-picker"><PreparationTaskTargetPicker :key="targetPickerGeneration" :disabled="!readable" @busy="targetPickerBusy = $event" @choose="chooseFilterTarget" /></div>
  <div class="preparation-tasks-workspace">
    <aside class="task-catalog" aria-label="准备任务列表">
      <header><h2>{{ applied.status ? preparationTaskStatusLabels[applied.status] : '全部任务' }}</h2><NButton text size="small" :disabled="!readable || list.isFetching.value" @click="list.refetch()">刷新</NButton></header>
      <p v-if="applied.target" class="field-help">目标：{{ applied.target.title }}；包含该目标的所有历史版本。</p><p v-if="applied.from" class="field-help">计划日期：{{ applied.from }} 至 {{ applied.to }}</p>
      <p v-if="!readable" class="field-help" role="status">登录与资料访问恢复后读取任务。</p><p v-else-if="list.isPending.value" class="loading-block" role="status">正在读取准备任务…</p><p v-else-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text :disabled="!readable || list.isFetching.value" @click="list.refetch()">重新读取</NButton></p>
      <div v-else-if="!list.data.value?.items.length" class="catalog-empty"><strong>当前条件下没有准备任务</strong><p>可以调整筛选，或先写下一项具体准备事项。</p></div>
      <div v-if="readable" class="task-list"><article v-for="task in list.data.value?.items" :key="task.id" :class="{ selected: !creating && selectedId === task.id }"><button class="task-select" :aria-current="!creating && selectedId === task.id ? 'true' : undefined" @click="open(task.id)"><strong>{{ task.content.title }}</strong><span>{{ preparationTaskStatusLabels[task.status] }} · {{ task.content.minutes }} 分钟</span><span>{{ task.content.plannedDate ?? '未安排日期' }} · {{ task.content.zoneId }}</span><small>{{ task.sourceAiTaskId ? '由已采用的 AI 建议创建' : '手工创建' }} · 修订 {{ task.revision }}</small><small v-if="task.target">目标：{{ task.target.title }} · v{{ task.target.number }}</small></button><button v-if="task.target && applied.target?.itemId !== task.target.itemId" class="target-filter-link" :disabled="targetPickerBusy" @click="filterBySavedTarget(task.target)">只看此目标的任务</button></article></div>
      <div v-if="cursors.length || list.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!readable || list.isFetching.value || !cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!readable || list.isFetching.value || !list.data.value?.nextCursor" @click="next">下一页</NButton></div>
    </aside>
    <PreparationTaskEditor v-if="creating || selectedId" :key="creating ? 'new' : selectedId" :id="creating ? undefined : selectedId" :disabled="!readable" @changed="refresh" @created="created" @close="close" />
    <section v-else class="task-welcome"><span class="welcome-mark" aria-hidden="true">☷</span><h2>把下一步写得具体一些</h2><p>例如，花 30 分钟整理一次缓存更新失败的补偿流程。可以先不安排日期，完成后再明确标记。</p><NButton type="primary" :disabled="!readable" @click="create">新建第一项准备任务</NButton><div class="welcome-notes"><p>普通任务只记录准备进度。知识记忆、文字训练和复练分别保留自己的证据。</p><p>AI 准备建议需先预览授权，再由你审阅、调整并采用；未采用的建议不会自动加入任务列表。</p></div></section>
  </div>
  <p class="workspace-footnote">日期不会自动顺延。任务目标与 AI 来源创建后固定；目标后来改版或归档，仍可继续处理已有任务。</p>
</template>

<style scoped>
.task-filters, .filter-picker, .preparation-tasks-workspace { max-width: 1280px; margin-left: auto; margin-right: auto; }
.task-filters { display: grid; gap: 12px; padding: 17px 0; margin-bottom: 22px; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.filter-fields { display: grid; grid-template-columns: minmax(150px, 1fr) repeat(2, minmax(150px, 1fr)) auto; gap: 14px; align-items: end; }
.filter-fields label { display: grid; gap: 7px; color: var(--muted); font-size: 12px; min-width: 0; }
input[type=date] { width: 100%; min-height: 34px; padding: 5px 10px; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: 4px; }
.filter-target { display: flex; gap: 8px 13px; flex-wrap: wrap; align-items: center; } .filter-target > span { overflow-wrap: anywhere; }
.filter-picker { margin-bottom: 20px; }
.preparation-tasks-workspace { display: grid; grid-template-columns: 285px minmax(0, 1fr); min-height: 620px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.task-catalog { min-width: 0; padding: 18px 14px; border-right: 1px solid var(--line); background: var(--surface-soft); border-radius: 8px 0 0 8px; }
.task-catalog > header { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 10px; } .task-catalog h2 { font-size: 15px; margin: 0; }
.task-catalog > .field-help { margin: 6px 0; overflow-wrap: anywhere; }
.task-list { display: grid; gap: 5px; margin: 17px 0; } .task-list > article { min-width: 0; padding: 11px 9px; border-radius: 5px; }
.task-list > article:hover, .task-list > article.selected { background: var(--surface-deep); }
.task-select { display: grid; gap: 6px; width: 100%; text-align: left; } .task-select strong { font-size: 13px; overflow-wrap: anywhere; }
.task-select span, .task-select small { color: var(--muted); font-size: 11px; overflow-wrap: anywhere; }
.target-filter-link { margin-top: 9px; color: var(--brand); text-align: left; font-size: 11px; }
.task-welcome { display: grid; gap: 19px; justify-items: start; align-self: center; max-width: 710px; padding: 42px clamp(22px, 4vw, 58px); }
.welcome-mark { color: var(--brand); font-size: 30px; } .task-welcome h2 { font-size: 25px; margin: 0; } .task-welcome > p { color: var(--muted); }
.welcome-notes { display: grid; gap: 12px; margin-top: 10px; padding-top: 20px; border-top: 1px solid var(--line); font-size: 12px; color: var(--muted); }
@media (max-width: 1050px) { .preparation-tasks-workspace { grid-template-columns: 230px minmax(0, 1fr); } .filter-fields { grid-template-columns: repeat(3, minmax(0, 1fr)); } .filter-fields > .row-actions { grid-column: 1 / -1; } }
@media (max-width: 760px) { .preparation-tasks-workspace { grid-template-columns: 1fr; } .task-catalog { border-right: 0; border-bottom: 1px solid var(--line); border-radius: 8px 8px 0 0; } .task-list { max-height: 270px; overflow-y: auto; } .filter-fields { grid-template-columns: 1fr 1fr; } .filter-fields > label:first-child { grid-column: 1 / -1; } .task-welcome { padding: 34px 22px; } }
</style>
