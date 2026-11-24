<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCheckbox, NInput, NSelect, NSkeleton } from 'naive-ui'
import { read } from '../api/client'
import type { Schema } from '../api/client'
import * as g from '../api/growth'
import { browserZone } from '../api/schedule'
import { todayContinuationRoute, todayZoneValid } from '../api/today'
import { dimensionOptions } from '../api/preparation'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { capabilityText, countText, copy, evidenceRoute, invalidGrowth, reviewedEvidenceRows, reviewedEvidenceStatus, useGrowthRead } from '../components/growth/growthUi'
import GrowthTargetPicker from '../components/growth/GrowthTargetPicker.vue'
import GrowthUseEditor from '../components/growth/GrowthUseEditor.vue'
import GrowthNextSteps from '../components/growth/GrowthNextSteps.vue'
import GrowthEvidenceMatrix from '../components/growth/GrowthEvidenceMatrix.vue'
import { growthLocationQuery, matrixColumns, readGrowthLocation } from '../components/growth/growthLocation'
import type { GrowthLocation, GrowthPanel, MatrixKind } from '../components/growth/growthLocation'
import RIcon from '../replica/RIcon.vue'

type TargetChoice = { ref: g.TargetRef; title: string; number: number | null }
type Tab = GrowthPanel
type Filters = { zoneId: string; from: string; to: string; target: TargetChoice | null; topicId: string | null; dimension: g.Dimension | null; linked: boolean; classified: boolean }
const route = useRoute(), router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore()
const defaultZone = todayZoneValid(browserZone()) ? browserZone() : 'UTC'
const address = computed(() => readGrowthLocation(route.query, defaultZone))
const addressProblem = computed(() => address.value.problem)
function addressFilters(value: GrowthLocation, previous?: TargetChoice | null): Filters {
  const target = value.target ? previous && previous.ref.itemId === value.target.itemId && previous.ref.versionId === value.target.versionId
    ? previous : { ref: value.target, title: '目标信息待核对', number: null } : null
  return { zoneId: value.zoneId, from: value.from, to: value.to, target, topicId: value.topicId,
    dimension: value.dimension, linked: value.linked, classified: value.classified }
}
function filterIdentity(value: Filters) { return JSON.stringify({ ...value, target: value.target?.ref ?? null }) }
const active = ref(true), visible = ref(document.visibilityState === 'visible')
const readable = computed(() => route.name === 'growth' && !addressProblem.value && active.value && visible.value && store.sessionActive && !access.checking && !access.challenge && !access.restricted && !store.changes.get('training-access-command')?.locked)
const input = reactive<Filters>(addressFilters(address.value.state))
const applied = shallowRef<Filters>(copy(input)), filterProblem = ref(''), picker = ref(false), tab = ref<Tab>(address.value.state.panel), refreshRound = ref(0)
const kind = ref<g.AvailableEvidenceKind>(address.value.state.kind), level = ref(address.value.state.level), flag = ref(address.value.state.flag)
const grain = ref(address.value.state.grain), disposition = ref(address.value.state.disposition)
const useStatus = ref(address.value.state.useStatus), requirementId = ref(address.value.state.requirementId), notice = ref('')
const columns = ref<MatrixKind[]>([...address.value.state.columns]), filtersOpen = ref(address.value.state.filtersOpen), directoryOpen = ref(address.value.state.directoryOpen)
// 偏好目录只按需显示，不条件挂载，避免切换时丢弃未完成的偏好编辑。
const preferencesOpen = ref(false)
const scope = computed(() => JSON.stringify([overviewQuery(), refreshRound.value]))
const evidenceScope = computed(() => JSON.stringify([scope.value, kind.value, level.value, flag.value]))
const sourceScope = computed(() => JSON.stringify([scope.value, disposition.value]))
const activityScope = computed(() => JSON.stringify([scope.value, grain.value]))
const usesScope = computed(() => JSON.stringify([scope.value, requirementId.value, useStatus.value]))
const overview = useGrowthRead<g.Overview>(readable, scope), evidence = useGrowthRead<g.EvidencePage>(readable, evidenceScope)
const activity = useGrowthRead<g.ActivitySeries>(readable, activityScope), sources = useGrowthRead<g.SourceProgressPage>(readable, sourceScope)
const coverage = useGrowthRead<g.TargetCoverage>(readable, scope), uses = useGrowthRead<g.UsePage>(readable, usesScope)
const topics = shallowRef<Schema['Topic'][]>([]), topicsError = ref(''), topicGeneration = ref(0)
const editorIntent = shallowRef<{ sequence: number; evidence?: g.EvidenceRef; entry?: g.UseEntry; target?: g.TargetRef; requirementId?: string }>()
const editorOpen = ref(false)
let intentSequence = 0, alive = true, unsubscribe: (() => void) | undefined, refreshTimer: number | undefined
let evidenceCursors = new Set<string>(), sourceCursors = new Set<string>(), useCursors = new Set<string>()
const tabs: { value: Tab; label: string }[] = [{ value: 'evidence', label: '证据目录' }, { value: 'activity', label: '活动记录' }, { value: 'sources', label: '当前来源处理' }, { value: 'coverage', label: '目标要求与复用' }, { value: 'next', label: '下一步' }]
// 只跟随会改变成长证据的来源写入，避免设置等无关缓存失效重置当前游标。
const sourceQueryRoots = new Set(['knowledge', 'training', 'training-question', 'training-questions', 'training-session', 'preparation', 'preparation-tasks', 'schedule', 'memory', 'review', 'review-actions', 'repractice', 'recollections'])
const kindOptions = g.evidenceKinds.map(value => ({ value, label: g.evidenceKindLabels[value] }))
const levelOptions = [{ value: 'SELF_RECORDED', label: '本人保存的记录' }, { value: 'AI_UNREVIEWED', label: 'AI 未审观察' }, { value: 'REVIEWED_OBSERVATION', label: '受审观察 · 有限条件' }]
const flagOptions = [{ value: 'DISPUTED', label: '有争议' }, { value: 'REVIEW_REQUIRED', label: '待复核' }, { value: 'SOURCE_UNAVAILABLE', label: '来源不可用' }]
const coverageLabels: Record<g.CoverageMetric, string> = { ACTIVE_USES: '有效状态关联', SAVED_SUPPORTS: '保存为支持', SAVED_PARTIAL: '保存为部分适用', CONTEXT_ONLY: '仅背景', INSUFFICIENT: '证据不足', EFFECTIVE_POSITIVE: '当前正向标注', INVALIDATED_POSITIVE: '正向标注已失效', WITHDRAWN_USES: '已撤回' }
const topicOptions = computed(() => topics.value.map(value => ({ value: value.id, label: value.name })))
const selectedMetric = ref<g.ActivityMetric>(address.value.state.metric)
let addressRequest = 0
function restoreAddress(value: GrowthLocation) {
  const next = addressFilters(value, input.target ?? applied.value.target)
  // 显示标题不属于筛选身份；只切换证据勾选或记录页时，保留尚未应用的日期输入。
  if (filterIdentity(next) !== filterIdentity(applied.value)) {
    Object.assign(input, copy(next)); applied.value = copy(next)
    evidenceCursors.clear(); sourceCursors.clear(); useCursors.clear(); picker.value = false
  }
  tab.value = value.panel; kind.value = value.kind; level.value = value.level; flag.value = value.flag
  grain.value = value.grain; disposition.value = value.disposition; useStatus.value = value.useStatus
  requirementId.value = value.requirementId; selectedMetric.value = value.metric
  columns.value = [...value.columns]; filtersOpen.value = value.filtersOpen; directoryOpen.value = value.directoryOpen
  filterProblem.value = ''
}
async function pushLocation(patch: Partial<GrowthLocation>) {
  if (!readable.value) return
  const next = { ...address.value.state, ...patch }
  const query = growthLocationQuery(next, defaultZone)
  if (JSON.stringify(query) === JSON.stringify(growthLocationQuery(address.value.state, defaultZone))) return
  const request = ++addressRequest
  try {
    const failure = await router.push({ name: 'growth', query })
    if (failure && alive && request === addressRequest && route.name === 'growth') restoreAddress(address.value.state)
  } catch {
    if (alive && request === addressRequest && route.name === 'growth') {
      restoreAddress(address.value.state); filterProblem.value = '筛选地址未能更新，请重试。'
    }
  }
}
function detailsToggled(event: Event, key: 'filtersOpen' | 'directoryOpen') {
  const open = (event.currentTarget as HTMLDetailsElement).open
  if (key === 'filtersOpen') filtersOpen.value = open; else directoryOpen.value = open
}
function overviewQuery(): g.OverviewQuery {
  const f = applied.value
  return { zoneId: f.zoneId, ...(f.from && f.to ? { from: f.from, to: f.to } : {}), ...(f.target ? { targetId: f.target.ref.itemId, targetVersionId: f.target.ref.versionId } : {}),
    ...(f.topicId ? { topicId: f.topicId } : {}), ...(f.dimension ? { dimension: f.dimension } : {}), targetMode: f.linked ? 'INCLUDE_ACTIVE_LINKS' : 'RECORDED_ONLY', topicMode: f.classified ? 'INCLUDE_CURRENT_CLASSIFICATION' : 'RECORDED_ONLY' }
}
function currentQuery(): g.SourceProgressQuery {
  const { from: _from, to: _to, zoneId: _zone, targetMode: _mode, ...query } = overviewQuery(); return query
}
function sameAsOf(a: g.Context, b: g.Context) { if (a.asOf !== b.asOf) throw invalidGrowth() }
function loadOverview() { const q = overviewQuery(); void overview.load(g.growthOverviewPath(q), (v): v is g.Overview => g.growthOverviewValid(v, q)) }
function loadEvidence(cursor?: string) {
  if (evidence.busy.value) return
  const q: g.EvidenceQuery = { ...overviewQuery(), kind: kind.value, ...(level.value ? { level: level.value } : {}), ...(flag.value ? { reviewFlag: flag.value } : {}), limit: 20, ...(cursor ? { cursor } : {}) }
  void evidence.load(g.growthEvidencePath(q), (v): v is g.EvidencePage => g.evidencePageValid(v, q), cursor ? (previous, next) => {
    if (next.nextCursor && evidenceCursors.has(next.nextCursor)) throw invalidGrowth()
    if (previous) sameAsOf(previous.context, next.context)
    evidenceCursors.add(cursor); return { ...next, items: [...new Map([...(previous?.items ?? []), ...next.items].map(item => [g.evidenceKey(item.ref), item])).values()] }
  } : undefined)
}
function loadActivity() {
  const { targetMode: _mode, ...query } = overviewQuery(), q = { ...query, grain: grain.value }
  void activity.load(g.growthActivityPath(q), (v): v is g.ActivitySeries => g.activitySeriesValid(v, q))
}
function loadSources(cursor?: string) {
  if (sources.busy.value) return
  const q: g.SourceProgressQuery = { ...currentQuery(), ...(disposition.value ? { disposition: disposition.value } : {}), limit: 20, ...(cursor ? { cursor } : {}) }
  void sources.load(g.growthSourceProgressPath(q), (v): v is g.SourceProgressPage => g.sourceProgressPageValid(v, q), cursor ? (previous, next) => {
    if (next.nextCursor && sourceCursors.has(next.nextCursor)) throw invalidGrowth()
    if (previous) sameAsOf(previous.context, next.context)
    sourceCursors.add(cursor); return { ...next, items: [...new Map([...(previous?.items ?? []), ...next.items].map(item => [item.sourceId, item])).values()] }
  } : undefined)
}
function loadCoverage() { const target = applied.value.target?.ref; if (target) void coverage.load(g.growthTargetCoveragePath(target), (v): v is g.TargetCoverage => g.targetCoverageValid(v, target)) }
function loadUses(cursor?: string) {
  const target = applied.value.target?.ref; if (!target || uses.busy.value) return
  const q: g.UseDirectoryQuery = { targetId: target.itemId, targetVersionId: target.versionId, ...(requirementId.value ? { requirementId: requirementId.value } : {}), ...(useStatus.value ? { status: useStatus.value } : {}), limit: 20, ...(cursor ? { cursor } : {}) }
  void uses.load(g.growthUsesPath(q), (v): v is g.UsePage => g.usePageValid(v, q), cursor ? (previous, next) => {
    if (next.nextCursor && useCursors.has(next.nextCursor)) throw invalidGrowth()
    if (previous) sameAsOf(previous.context, next.context)
    useCursors.add(cursor); return { ...next, items: [...new Map([...(previous?.items ?? []), ...next.items].map(item => [item.use.id, item])).values()] }
  } : undefined)
}
function loadTab() { if (!readable.value) return; if (tab.value === 'evidence') { loadEvidence(); loadUses() } if (tab.value === 'activity') loadActivity(); if (tab.value === 'sources') loadSources(); if (tab.value === 'coverage') loadUses() }
function selectTab(value: Tab) { void pushLocation({ panel: value, ...(value === 'evidence' ? { useStatus: null, requirementId: null } : {}) }) }
function selectEvidenceKind(value: g.EvidenceKind) { void pushLocation({ panel: 'evidence', kind: value, directoryOpen: true, useStatus: null, requirementId: null }) }
function refresh() { refreshRound.value++; notice.value = ''; evidenceCursors.clear(); sourceCursors.clear(); useCursors.clear() }
function applyFilters() {
  filterProblem.value = !todayZoneValid(input.zoneId) ? '请填写 IANA 时区，例如 Asia/Shanghai。' : (input.from || input.to) && !g.growthDateRangeValid(input.from, input.to) ? '请同时填写起止日期，范围最多 366 天。' : ''
  if (filterProblem.value || !readable.value) return
  void pushLocation({ zoneId: input.zoneId, from: input.from, to: input.to, target: input.target?.ref ?? null,
    topicId: input.topicId, dimension: input.dimension, linked: Boolean(input.target && input.linked), classified: input.classified, requirementId: null })
}
function editEvidence(value: g.EvidenceSummary) {
  if (!readable.value || !store.canLeave(['growth-evidence-use'])) return
  editorIntent.value = { sequence: ++intentSequence, evidence: copy(value.ref), ...(applied.value.target ? { target: copy(applied.value.target.ref) } : {}) }; editorOpen.value = true
}
function editUse(value: g.UseEntry) { if (readable.value && store.canLeave(['growth-evidence-use'])) { editorIntent.value = { sequence: ++intentSequence, entry: copy(value) }; editorOpen.value = true } }
function closeEditor() { if (store.canLeave(['growth-evidence-use'])) editorOpen.value = false }
function openEvidence(value: g.EvidenceSummary) { if (readable.value) void router.push(evidenceRoute(value)) }
function openSource(value: g.SourceProgressItem) { const destination = todayContinuationRoute(value.continuation); if (destination && readable.value) void router.push(destination) }
function time(value: string) { return new Intl.DateTimeFormat('zh-CN', { timeZone: applied.value.zoneId, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }
async function loadTopics() {
  const epoch = store.sessionEpoch, request = ++topicGeneration.value; topics.value = []; topicsError.value = ''
  if (!readable.value) return
  try { const value = await read<Schema['TopicList']>('/knowledge/topics'); if (alive && readable.value && epoch === store.sessionEpoch && request === topicGeneration.value) topics.value = value.items }
  catch (reason) { if (alive && readable.value && epoch === store.sessionEpoch && request === topicGeneration.value) topicsError.value = g.growthErrorText(reason) }
}
watch(() => route.query, () => { if (route.name === 'growth' && !addressProblem.value) restoreAddress(address.value.state) }, { flush: 'sync' })
watch([tab, kind, level, flag, grain, disposition, useStatus, requirementId, selectedMetric, columns, filtersOpen, directoryOpen], () => {
  void pushLocation({ panel: tab.value, kind: kind.value, level: level.value, flag: flag.value, grain: grain.value,
    disposition: disposition.value, useStatus: useStatus.value, requirementId: requirementId.value, metric: selectedMetric.value,
    columns: matrixColumns.filter(column => columns.value.includes(column.kind)).map(column => column.kind),
    filtersOpen: filtersOpen.value, directoryOpen: directoryOpen.value })
}, { deep: true })
watch(() => coverage.data.value?.target, value => {
  if (!value || !applied.value.target || value.ref.itemId !== applied.value.target.ref.itemId || value.ref.versionId !== applied.value.target.ref.versionId) return
  applied.value = { ...applied.value, target: { ...applied.value.target, title: value.title } }
  if (input.target?.ref.itemId === value.ref.itemId && input.target.ref.versionId === value.ref.versionId) input.target = { ...input.target, title: value.title }
})
watch([readable, () => store.sessionEpoch], ([allowed, epoch], previous) => {
  if (allowed && (!previous || previous[1] === epoch)) return
  // 地址仅保留身份；权限或会话变化后，标题须重新经确切目标元数据核对。
  if (applied.value.target) applied.value = { ...applied.value, target: { ...applied.value.target, title: '目标信息待核对', number: null } }
  if (input.target) input.target = { ...input.target, title: '目标信息待核对', number: null }
}, { flush: 'sync' })
watch([readable, scope, () => store.sessionEpoch], () => { if (readable.value) { loadOverview(); loadCoverage(); loadTab() } else { topics.value = []; topicGeneration.value++ } }, { immediate: true })
watch([readable, () => store.sessionEpoch], loadTopics, { immediate: true, flush: 'sync' })
watch(tab, loadTab)
watch([kind, level, flag], () => { evidenceCursors.clear(); if (readable.value && tab.value === 'evidence') loadEvidence() })
watch(grain, () => { if (readable.value && tab.value === 'activity') loadActivity() })
watch(disposition, () => { sourceCursors.clear(); if (readable.value && tab.value === 'sources') loadSources() })
watch([useStatus, requirementId], () => { useCursors.clear(); if (readable.value && (tab.value === 'coverage' || tab.value === 'evidence')) loadUses() })
function visibility() { visible.value = document.visibilityState === 'visible' }
function focus() { if (readable.value) refresh() }
onMounted(() => { document.addEventListener('visibilitychange', visibility); window.addEventListener('focus', focus); unsubscribe = queryClient.getQueryCache().subscribe(event => {
  if (event.type === 'updated' && event.action.type === 'invalidate' && readable.value && sourceQueryRoots.has(String(event.query.queryKey[0]))) { if (refreshTimer !== undefined) clearTimeout(refreshTimer); refreshTimer = window.setTimeout(refresh, 120) }
}) })
onActivated(() => { active.value = true }); onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; topicGeneration.value++; unsubscribe?.(); if (refreshTimer !== undefined) clearTimeout(refreshTimer); document.removeEventListener('visibilitychange', visibility); window.removeEventListener('focus', focus) })
</script>

<template>
  <section class="growth-workspace">
    <header class="page-heading growth-heading"><div><h1>成长</h1><p class="muted"><RIcon name="shield-check" :size="23" />证据矩阵 · {{ applied.target ? `按 ${applied.target.title} 筛选` : '全部目标' }}</p></div><div class="row-actions"><NButton :disabled="!readable" :aria-expanded="picker" @click="picker = !picker"><RIcon name="target" />{{ input.target?.title || '选择目标' }}<RIcon name="chevron-down" :size="20" /></NButton><NButton :disabled="!readable" :aria-expanded="filtersOpen" @click="filtersOpen = !filtersOpen"><RIcon name="filter" />筛选</NButton></div></header>
    <p v-if="addressProblem" class="notice error" role="alert">{{ addressProblem }}<NButton text @click="router.replace({ name: 'growth' })">清除地址筛选</NButton></p>
    <p v-else-if="!readable" class="notice" role="status">成长资料暂不可读，请完成当前训练资料访问确认或恢复会话。</p>
    <div v-show="readable" :inert="!readable || undefined" class="growth-content">
      <details v-show="filtersOpen" class="growth-filter-details" :open="filtersOpen" @toggle="detailsToggled($event, 'filtersOpen')"><summary>筛选范围与考察方向</summary><form class="growth-filters" @submit.prevent="applyFilters">
        <label>起始日期<input v-model="input.from" type="date" :disabled="!readable" /></label><label>结束日期<input v-model="input.to" type="date" :disabled="!readable" /></label>
        <label>显示时区<NInput v-model:value="input.zoneId" :maxlength="100" :disabled="!readable" /></label>
        <label>目标版本<NButton :disabled="!readable" @click="picker = !picker">{{ input.target ? `${input.target.title} · ${input.target.number === null ? '确切版本' : `v${input.target.number}`}` : '全部目标 · 选择确切版本' }}</NButton></label>
        <label>主题<NSelect v-model:value="input.topicId" :options="topicOptions" clearable :disabled="!readable" placeholder="全部主题" /></label>
        <label>考察项<NSelect v-model:value="input.dimension" :options="dimensionOptions" clearable :disabled="!readable" placeholder="全部考察项" /></label>
        <label>记录视图<NSelect :value="tab" :options="tabs.map(item => ({ value: item.value, label: item.label }))" :disabled="!readable" @update:value="selectTab" /></label>
        <div class="filter-switches"><NCheckbox v-model:checked="input.linked" :disabled="!readable || !input.target">包含有效跨目标关联</NCheckbox><NCheckbox v-model:checked="input.classified" :disabled="!readable">包含当前主题分类</NCheckbox></div>
        <div class="filter-switches"><NCheckbox v-model:checked="directoryOpen" :disabled="!readable">展开确切证据目录</NCheckbox></div>
        <div class="row-actions"><NButton attr-type="submit" type="primary" :disabled="!readable">应用筛选</NButton><NButton v-if="input.target" :disabled="!readable" @click="input.target = null; input.linked = false">清除目标</NButton><NButton :disabled="!readable" @click="refresh"><RIcon name="refresh-cw" :size="20" />刷新记录</NButton></div>
      </form>
      <section class="growth-directions" aria-label="目标方向"><div class="growth-direction-options"><h2>考察方向</h2><button type="button" :class="{ selected: !applied.dimension }" :aria-pressed="!applied.dimension" :disabled="!readable" @click="input.dimension = null; applyFilters()">全部</button><button v-for="dimension in dimensionOptions" :key="dimension.value" type="button" :class="{ selected: applied.dimension === dimension.value }" :aria-pressed="applied.dimension === dimension.value" :disabled="!readable" @click="input.dimension = dimension.value; applyFilters()">{{ dimension.label }}</button></div><fieldset class="growth-column-filters"><legend>显示证据</legend><label v-for="column in matrixColumns" :key="column.kind"><input v-model="columns" type="checkbox" :value="column.kind" :disabled="!readable" />{{ column.label }}</label></fieldset></section>
      </details>
      <GrowthTargetPicker v-if="picker" :disabled="!readable" @choose="input.target = $event; picker = false; applyFilters()" />
      <p v-if="filterProblem || topicsError" class="notice error" role="alert">{{ filterProblem || topicsError }}</p>
      <div class="growth-layout">
      <div class="growth-primary">
      <NSkeleton v-if="overview.busy.value" text :repeat="3" />
      <p v-if="overview.error.value" class="notice error" role="alert">{{ g.growthErrorText(overview.error.value) }}</p>
      <section v-show="tab === 'evidence'" class="growth-section">
        <div v-if="overview.data.value" class="growth-key-metrics" aria-label="关键指标">
          <header><h2>关键指标</h2><p class="field-help">{{ overview.data.value.scope.window.from }} 至 {{ overview.data.value.scope.window.to }} · {{ applied.zoneId }}</p></header>
          <div class="kind-summary"><button v-for="group in overview.data.value.evidenceGroups" :key="group.kind" type="button" :disabled="!readable" @click="selectEvidenceKind(group.kind)"><span>{{ g.evidenceKindLabels[group.kind] }}</span><strong>{{ countText(group.count) }}</strong><small v-if="group.scanLimited || group.capability.status !== 'READY'">{{ group.scanLimited ? '已达扫描上限' : capabilityText(group.capability) }}</small></button></div>
        </div>
        <GrowthEvidenceMatrix :coverage="coverage.data.value" :uses="uses.data.value" :scope="overview.data.value?.scope" :level="level" :flag="flag" :busy="overview.busy.value || coverage.busy.value || uses.busy.value" :zone-id="applied.zoneId" :columns="columns" @open="openEvidence" @choose-target="picker = true" />
        <p v-if="coverage.error.value || uses.error.value" class="notice error">{{ g.growthErrorText(coverage.error.value || uses.error.value) }}</p>
        <details v-show="directoryOpen" class="growth-evidence-directory" :open="directoryOpen" @toggle="detailsToggled($event, 'directoryOpen')"><summary>确切证据记录 · {{ g.evidenceKindLabels[kind] }}</summary>
        <header><h2>证据目录</h2><div class="compact-filters"><NSelect v-model:value="kind" :options="kindOptions" aria-label="证据类型" /><NSelect v-model:value="level" :options="levelOptions" clearable placeholder="全部记录层级" aria-label="记录层级" /><NSelect v-model:value="flag" :options="flagOptions" clearable placeholder="全部复核状态" aria-label="复核状态" /></div></header>
        <NSkeleton v-if="evidence.busy.value" text :repeat="3" /><p v-if="evidence.error.value" class="notice error" role="alert">{{ g.growthErrorText(evidence.error.value) }} <NButton text @click="loadEvidence()">从首页重读</NButton></p>
        <template v-if="evidence.data.value"><p class="field-help">{{ capabilityText(evidence.data.value.capability) }} · {{ countText(evidence.data.value.count) }} 条{{ evidence.data.value.scanLimited ? ' · 扫描达到上限，仍可继续分页' : '' }}</p>
          <p v-if="kind === 'REVIEWED_OBSERVATION'" class="field-help">每条目录项保留一个不可变观察修订，包括后续修改与撤回。多个修订仍来自同一回答；有限观察不自动代表已掌握，也不会自动标为支持目标。</p>
          <article v-for="item in evidence.data.value.items" :key="g.evidenceKey(item.ref)" class="evidence-row"><div><h3>{{ item.title }}</h3><p class="field-help">{{ time(item.occurredAt) }} · {{ g.assessmentLevelLabels[item.assessmentLevel] }}<template v-if="item.ref.kind === 'REVIEWED_OBSERVATION'"> · 观察修订 {{ item.ref.revision }}</template></p><p>{{ g.directStateLabels[item.state.directState] }}{{ item.state.recordCurrency === 'SUPERSEDED' ? ' · 已有后续版本' : '' }}{{ item.state.reviewFlags.includes('DISPUTED') ? ' · 有争议' : '' }}</p><p v-if="item.conditions.kind === 'REVIEWED_OBSERVATION'" class="field-help">{{ reviewedEvidenceStatus(item) }}</p><p v-if="item.state.lineageCheck === 'PARTIAL'" class="field-help">间接派生依据仅完成部分检查。</p><p v-if="item.facets.selectedTargetMatch" class="field-help">{{ item.facets.selectedTargetMatch.recorded ? '发生时记录于所选目标' : '通过有效个人关联发现' }}</p><details><summary>固定条件与证据身份</summary><p class="identity">{{ g.evidenceKey(item.ref) }}</p><dl v-if="item.conditions.kind === 'REVIEWED_OBSERVATION'" class="reviewed-metadata"><template v-for="row in reviewedEvidenceRows(item)" :key="row.label"><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></template></dl><pre v-else>{{ JSON.stringify(item.conditions, null, 2) }}</pre></details></div><div class="row-actions"><NButton size="small" @click="openEvidence(item)">查看确切记录</NButton><NButton size="small" @click="editEvidence(item)">关联目标要求</NButton></div></article>
          <p v-if="!evidence.data.value.items.length" class="notice">{{ evidence.data.value.nextCursor ? '本页没有匹配项，仍有后续候选可继续核对。' : evidence.data.value.capability.status === 'READY' ? '当前读取范围没有匹配条目。' : '此来源暂未返回可展示条目，数量未知。' }}</p>
          <NButton v-if="evidence.data.value.nextCursor" :loading="evidence.busy.value" @click="loadEvidence(evidence.data.value.nextCursor)">继续读取</NButton>
        </template>
        </details>
      </section>
      <section v-show="tab === 'activity'" class="growth-section">
        <header><h2>活动记录</h2><NSelect v-model:value="grain" :options="[{label:'按日',value:'DAY'},{label:'按周',value:'WEEK'}]" class="small-select" aria-label="活动粒度" /></header><p class="field-help">{{ g.activityMetrics.length }} 项指标保持各自口径。卡片与题目去重数不能直接累加；下界表示至少已核对这些记录。</p><p class="field-help">受审观察的首次录入与后续修订／撤回分别计为事件。维护记录不增加回答次数、独立复练数或训练时长。</p>
        <NSkeleton v-if="activity.busy.value" text :repeat="3" /><p v-if="activity.error.value" class="notice error" role="alert">{{ g.growthErrorText(activity.error.value) }}</p>
        <template v-if="activity.data.value"><div class="table-scroll"><table><caption>当前范围活动汇总</caption><thead><tr><th scope="col">指标</th><th scope="col">数量</th><th scope="col">口径</th></tr></thead><tbody><tr v-for="metric in activity.data.value.totals.values" :key="metric.metric"><th scope="row">{{ g.activityMetricLabels[metric.metric] }}</th><td>{{ countText(metric.count) }}</td><td>{{ metric.capability.status !== 'READY' ? capabilityText(metric.capability) : metric.additive ? '事件记录，可按桶相加' : '范围内去重，不累加日期桶' }}</td></tr></tbody></table></div>
          <label class="metric-picker">查看一项指标的日期记录<NSelect v-model:value="selectedMetric" :options="g.activityMetrics.map(value => ({value,label:g.activityMetricLabels[value]}))" /></label>
          <div class="table-scroll"><table><caption>{{ g.activityMetricLabels[selectedMetric] }} · {{ grain === 'DAY' ? '按日' : '按周' }}</caption><thead><tr><th scope="col">日期范围</th><th scope="col">数量</th><th scope="col">期间状态</th></tr></thead><tbody><tr v-for="bucket in activity.data.value.buckets" :key="bucket.startAt"><th scope="row">{{ bucket.from }}{{ bucket.to !== bucket.from ? ` — ${bucket.to}` : '' }}</th><td>{{ bucket.state === 'FUTURE' ? '尚未发生' : countText(bucket.values.find(value => value.metric === selectedMetric)!.count) }}</td><td>{{ bucket.state === 'FUTURE' ? '未来期间' : bucket.state === 'PARTIAL_TODAY' ? '截至本次读取' : '已结束期间' }}{{ bucket.clipped ? ' · 按筛选边界截取' : '' }}</td></tr></tbody></table></div>
        </template>
      </section>
      <section v-show="tab === 'sources'" class="growth-section"><header><h2>当前来源处理</h2><NSelect v-model:value="disposition" :options="g.sourceDispositions.map(value => ({value,label:g.sourceDispositionLabels[value]}))" clearable placeholder="全部处理状态" class="medium-select" /></header><p class="field-help">每条动作来源独立核对。旧判断仍保留在活动记录中，当前来源变化可能使原判断不再适用。</p>
        <NSkeleton v-if="sources.busy.value" text :repeat="3" /><p v-if="sources.error.value" class="notice error" role="alert">{{ g.growthErrorText(sources.error.value) }} <NButton text @click="loadSources()">从首页重读</NButton></p>
        <template v-if="sources.data.value"><p class="field-help">{{ capabilityText(sources.data.value.capability) }} · {{ countText(sources.data.value.count) }} 条{{ sources.data.value.scanLimited ? ' · 扫描达到上限' : '' }}</p><article v-for="item in sources.data.value.items" :key="item.sourceId" class="evidence-row"><div><h3>{{ g.sourceDispositionLabels[item.disposition] }}</h3><p>{{ g.evidenceKindLabels[item.origin.kind] }} · {{ g.directStateLabels[item.originState.directState] }}</p><p class="field-help">{{ item.domainApplicable ? '当前判断适用' : '当前判断未确认适用' }} · {{ item.tracking === 'DROPPED' ? '停止跟踪' : '继续跟踪' }}</p><details><summary>来源身份</summary><p class="identity">{{ item.sourceId }}</p><p class="identity">{{ g.evidenceKey(item.origin) }}</p></details></div><NButton size="small" @click="openSource(item)">打开来源动作</NButton></article><p v-if="!sources.data.value.items.length" class="notice">{{ sources.data.value.nextCursor ? '本页没有匹配来源，可继续核对后续候选。' : sources.data.value.capability.status === 'READY' ? '当前范围没有匹配来源。' : '当前来源状态尚不能完整读取。' }}</p><NButton v-if="sources.data.value.nextCursor" :loading="sources.busy.value" @click="loadSources(sources.data.value.nextCursor)">继续读取</NButton></template>
      </section>
      <section v-show="tab === 'coverage'" class="growth-section"><h2>目标要求与证据复用</h2><p v-if="!applied.target" class="notice">先在上方选择一个确切目标版本并应用筛选。旧版本关联不会迁移到新版目标。</p>
        <NSkeleton v-if="coverage.busy.value" text :repeat="3" /><p v-if="coverage.error.value" class="notice error" role="alert">{{ g.growthErrorText(coverage.error.value) }}</p>
        <template v-if="coverage.data.value"><p>{{ coverage.data.value.target.title }} · {{ coverage.data.value.currentTarget ? '当前目标版本' : '历史或归档版本' }} · {{ capabilityText(coverage.data.value.capability) }}</p><p v-if="coverage.data.value.scanLimited" class="notice">关联核对达到扫描上限，不能据此断言没有覆盖。</p><article v-for="row in coverage.data.value.requirements" :key="row.requirement.id" class="requirement-row"><h3>{{ row.requirement.ordinal }}. {{ row.requirement.text }}</h3><div class="coverage-counts"><span v-for="count in row.counts" :key="count.metric">{{ coverageLabels[count.metric] }}：{{ countText(count.count) }}</span></div><p class="field-help">{{ row.noActiveLinkedEvidence === true ? '尚无活跃关联；这不等同于能力不足。' : row.noActiveLinkedEvidence === null ? '当前关联完整性尚不能确认。' : '已有关联；个人标注不等同于能力认证。' }}</p><NButton size="small" @click="requirementId = row.requirement.id">查看这项要求的关联</NButton></article></template>
        <div v-if="applied.target" class="compact-filters"><NSelect v-model:value="requirementId" :options="coverage.data.value?.requirements.map(row=>({value:row.requirement.id,label:row.requirement.text})) ?? []" clearable placeholder="全部要求" aria-label="关联要求" /><NSelect v-model:value="useStatus" :options="[{label:'活跃关联',value:'ACTIVE'},{label:'已撤回关联',value:'WITHDRAWN'}]" clearable placeholder="全部关联状态" aria-label="关联状态" /></div>
        <p v-if="uses.error.value" class="notice error" role="alert">{{ g.growthErrorText(uses.error.value) }} <NButton text @click="loadUses()">从首页重读</NButton></p><NSkeleton v-if="uses.busy.value" text :repeat="2" />
        <template v-if="uses.data.value"><article v-for="entry in uses.data.value.items" :key="entry.use.id" class="evidence-row"><div><h3>{{ entry.evidence.summary?.title ?? '来源当前不可用' }}</h3><p>{{ g.coverageLabels[entry.use.coverage] }} · {{ g.effectiveUseStateLabels[entry.effectiveState] }}</p><p class="field-help">{{ entry.requirement.text }} · 关联修订 {{ entry.use.revision }}<template v-if="entry.use.evidence.kind === 'REVIEWED_OBSERVATION'"> · 固定引用观察修订 {{ entry.use.evidence.revision }}</template></p><p v-if="entry.evidence.summary?.conditions.kind === 'REVIEWED_OBSERVATION'" class="field-help">{{ reviewedEvidenceStatus(entry.evidence.summary) }}</p></div><NButton size="small" @click="editUse(entry)">查看、修改或恢复</NButton></article><p v-if="!uses.data.value.items.length" class="notice">这个目标版本下尚无匹配的个人关联。可从证据目录选取确切记录。</p><NButton v-if="uses.data.value.nextCursor" :loading="uses.busy.value" @click="loadUses(uses.data.value.nextCursor)">继续读取关联</NButton></template>
      </section>
      </div>
      <aside v-show="tab === 'evidence' || tab === 'next'" class="growth-suggestions" :class="{ 'preferences-open': preferencesOpen }"><GrowthNextSteps :disabled="!readable || (tab !== 'next' && tab !== 'evidence')" :zone-id="applied.zoneId" :target="applied.target?.ref" :refresh="refreshRound" @changed="refresh" /><NButton text class="growth-preferences-toggle" :aria-expanded="preferencesOpen" @click="preferencesOpen = !preferencesOpen"><RIcon name="chevron-down" :size="18" />{{ preferencesOpen ? '收起建议偏好' : '建议偏好与隐藏管理' }}</NButton></aside>
      </div>
      <details v-if="overview.data.value" class="growth-overview" aria-label="记录总览"><summary>记录总览与统计口径</summary>
        <p class="field-help">核对于 {{ time(overview.data.value.context.checkedAt) }}</p>
        <div class="coverage-counts" aria-label="当前来源处理汇总"><span v-for="item in overview.data.value.sourceProgress.counts" :key="item.disposition">{{ g.sourceDispositionLabels[item.disposition] }}：{{ countText(item.count) }}</span></div>
        <p v-if="overview.data.value.emptyState === 'RECOLLECTION_ONLY'" class="notice">当前范围只有面后回忆；回忆不等同于当时的正式原答。</p><p v-else-if="overview.data.value.emptyState === 'NO_RECORD'" class="notice">当前范围未查到记录。可以从正式训练、复盘或主动回忆开始积累。</p><p v-else-if="overview.data.value.emptyState === 'UNKNOWN'" class="notice">有来源尚未完整核对，暂不能判断当前范围没有记录。</p>
        <details><summary>统计口径与限制</summary><ul><li v-for="item in overview.data.value.limitations" :key="item">{{ g.growthLimitationLabels[item] }}</li></ul></details>
      </details>
      <footer class="growth-boundary"><RIcon name="info" :size="23" /><span><strong>证据可追溯</strong> · 不输出能力总分</span></footer>
      <GrowthUseEditor v-if="editorIntent" v-show="editorOpen" :disabled="!readable || !editorOpen" :intent="editorIntent" @changed="refresh" @close="closeEditor" />
      <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    </div>
  </section>
</template>

<style scoped>
.reviewed-metadata { display:grid; grid-template-columns:120px minmax(0,1fr); gap:8px 12px; margin:14px 0 0; max-width:85ch; }.reviewed-metadata dt { color:var(--muted); }.reviewed-metadata dd { margin:0; overflow-wrap:anywhere; line-height:1.6; }@media(max-width:560px) { .reviewed-metadata { grid-template-columns:1fr; gap:4px; }.reviewed-metadata dd { margin-bottom:8px; } }
.growth-workspace,.growth-content,.growth-section,.growth-overview { display:grid; gap:20px; min-width:0; }.growth-workspace { padding:30px; }.growth-heading,.growth-section>header { display:flex; align-items:center; justify-content:space-between; gap:18px; flex-wrap:wrap; }.growth-heading p { color:var(--muted); margin-top:9px; }.growth-filters { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:14px; padding:20px; background:var(--surface); border:1px solid var(--line); border-radius:7px; }label { display:grid; gap:7px; font-size:12px; color:var(--muted); }input { width:100%; min-height:34px; padding:5px 10px; border:1px solid var(--line); border-radius:4px; color:var(--ink); background:var(--surface); }.filter-switches { display:flex; gap:16px; flex-wrap:wrap; grid-column:1/-1; }.kind-summary { display:flex; flex-wrap:wrap; border-top:1px solid var(--line); border-bottom:1px solid var(--line); }.kind-summary button { flex:1 1 180px; display:grid; gap:7px; text-align:left; padding:15px 12px; }.kind-summary strong { font-size:20px; }.kind-summary small { color:var(--muted); }.growth-tabs,.row-actions { display:flex; gap:8px; flex-wrap:wrap; }.growth-tabs { padding-bottom:16px; border-bottom:1px solid var(--line); }.compact-filters { display:flex; gap:10px; flex-wrap:wrap; }.compact-filters>* { width:190px; }.growth-section h2 { font-size:21px; }.evidence-row { display:flex; gap:18px; justify-content:space-between; align-items:start; padding:18px 0; border-bottom:1px solid var(--line); }.evidence-row>div:first-child { min-width:0; display:grid; gap:7px; }.evidence-row>.row-actions { flex:none; }.requirement-row { display:grid; gap:12px; padding:18px 0; border-bottom:1px solid var(--line); }.requirement-row>button { justify-self:start; }.coverage-counts { display:flex; gap:10px 20px; flex-wrap:wrap; font-size:12px; }.table-scroll { overflow:auto; border:1px solid var(--line); border-radius:6px; background:var(--surface); }table { width:100%; border-collapse:collapse; font-size:13px; text-align:left; }caption { text-align:left; padding:15px; font-weight:600; }th,td { border-top:1px solid var(--line); padding:11px 14px; }th { font-weight:550; }thead { background:var(--surface-soft); }td { font-variant-numeric:tabular-nums; }.small-select { width:110px; }.medium-select,.metric-picker { width:min(100%,340px); }details { font-size:12px; }summary { cursor:pointer; color:var(--muted); }pre { max-height:260px; overflow:auto; white-space:pre-wrap; overflow-wrap:anywhere; font-size:12px; background:var(--surface-soft); padding:12px; }.identity { overflow-wrap:anywhere; font-family:Consolas,monospace; font-size:12px; }.field-help { overflow-wrap:anywhere; }@media(max-width:900px) { .growth-filters { grid-template-columns:repeat(2,minmax(0,1fr)); }.growth-workspace { padding:22px; }.evidence-row { flex-direction:column; } }@media(max-width:560px) { .growth-filters { grid-template-columns:1fr; }.growth-workspace { padding:16px; }.compact-filters>* { width:100%; }.kind-summary button { flex-basis:140px; } }
</style>

<style scoped>
.growth-workspace { padding: 0; gap: 18px; }.growth-heading .muted { display: flex; align-items: center; gap: 8px; }.growth-heading .muted > svg { width: 17px; color: var(--brand); }.growth-filter-details > summary { font-size: 13px; }.growth-filter-details[open] .growth-filters { margin-top: 14px; }.growth-filter-details .growth-directions { padding: 0 20px 20px; border: 0; }
.growth-layout { display: grid; grid-template-columns: minmax(0,1fr) 320px; gap: 28px 32px; align-items: start; }
.growth-directions { display: grid; gap: 16px; }.growth-direction-options { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }.growth-direction-options h2 { margin: 0 14px 0 0; font-size: 12px; font-weight: 400; color: var(--muted); }.growth-direction-options button { min-height: 32px; padding: 5px 12px; border-radius: 5px; font-size: 13px; text-align: left; color: var(--muted); }.growth-direction-options button:hover { color: var(--ink); background: var(--studio-pane); }.growth-direction-options button.selected { background: var(--brand-soft); color: var(--brand-strong); }
.growth-column-filters { min-width: 0; padding: 0; margin: 0; border: 0; }.growth-column-filters legend { float: left; padding: 2px 0; margin-right: 24px; font-size: 12px; color: var(--muted); }.growth-column-filters label { display: inline-flex; align-items: center; gap: 8px; margin: 0 20px 4px 0; color: var(--muted); font-size: 12px; }.growth-column-filters input { flex: 0 0 15px; width: 15px; height: 15px; min-height: 15px; margin: 0; padding: 0; accent-color: var(--brand); }
.growth-boundary { display: flex; align-items: center; gap: 9px; padding: 12px 16px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); color: var(--muted); font-size: 13px; }.growth-boundary strong { color: var(--ink); font-weight: 500; }
.growth-primary { display: grid; gap: 16px; min-width: 0; }.growth-key-metrics { display: grid; gap: 14px; padding: 18px 20px; border: 1px solid var(--studio-divider); border-radius: 10px; background: var(--studio-pane); }.growth-key-metrics > header { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap; }.growth-key-metrics h2 { margin: 0; font-size: 16px; }.growth-key-metrics .field-help { margin: 0; font-size: 12px; }.growth-key-metrics .kind-summary { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 10px; border: 0; }.growth-key-metrics .kind-summary button { min-width: 0; gap: 5px; padding: 12px; border: 1px solid var(--studio-divider); border-radius: 7px; background: var(--surface); }.growth-key-metrics .kind-summary button:hover { border-color: var(--brand); }.growth-key-metrics .kind-summary strong { color: var(--brand-strong); font-size: 22px; }.growth-key-metrics .kind-summary small { min-height: 18px; font-size: 11px; }
.growth-overview { display: block; border: 1px solid var(--line); border-radius: 8px; padding: 12px 16px; background: var(--surface); }.growth-overview > summary, .growth-evidence-directory > summary { font-size: 13px; cursor: pointer; }.growth-overview[open] > :not(summary), .growth-evidence-directory[open] > :not(summary) { margin-top: 14px; }
.growth-tabs { padding: 0; gap: 6px; border: 0; }.growth-section { gap: 16px; }.growth-evidence-directory { border: 1px solid var(--line); border-radius: 8px; background: var(--surface); padding: 16px; }
.growth-suggestions { min-width: 0; display: grid; gap: 14px; padding: 20px; border: 1px solid var(--studio-divider); border-radius: 10px; background: var(--studio-pane); }.growth-suggestions :deep(.growth-next-steps) { gap: 16px; }.growth-suggestions :deep(.growth-next-steps > header) { padding-bottom: 16px; border-bottom: 1px solid var(--studio-divider); }.growth-suggestions :deep(h2) { font-size: 18px; }.growth-suggestions :deep(.next-row) { display: grid; gap: 12px; padding: 16px 0; border: 0; border-bottom: 1px solid var(--studio-divider); border-radius: 0; }.growth-suggestions :deep(.next-row h3) { font-size: 14px; }.growth-suggestions :deep(.next-row p) { font-size: 12px; }.growth-suggestions :deep(.directory-filters > *) { width: 100%; }
.growth-suggestions :deep(.growth-next-steps > .preference-directory) { display: none; }.growth-suggestions.preferences-open :deep(.growth-next-steps > .preference-directory) { display: grid; }.growth-preferences-toggle { justify-self: start; color: var(--muted); }.growth-preferences-toggle svg { transition: transform 160ms ease; }.growth-preferences-toggle[aria-expanded="true"] svg { transform: rotate(180deg); }
@container workspace (max-width: 1100px) { .growth-layout { grid-template-columns: minmax(0,1fr); }.growth-suggestions { padding: 24px; } }
@media(max-width:700px) { .growth-layout { gap: 24px; }.growth-direction-options { gap: 4px; }.growth-direction-options h2 { width: 100%; margin-bottom: 4px; }.growth-column-filters legend { float: none; margin: 0 0 10px; }.growth-column-filters label { margin-right: 16px; }.growth-key-metrics .kind-summary { grid-template-columns: repeat(2,minmax(0,1fr)); } }
</style>
