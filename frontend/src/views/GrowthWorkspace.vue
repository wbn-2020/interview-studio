<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
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

type TargetChoice = { ref: g.TargetRef; title: string; number: number }
type Tab = 'evidence' | 'activity' | 'sources' | 'coverage' | 'next'
type Filters = { zoneId: string; from: string; to: string; target: TargetChoice | null; topicId: string | null; dimension: g.Dimension | null; linked: boolean; classified: boolean }
const router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore()
const active = ref(true), visible = ref(document.visibilityState === 'visible')
const readable = computed(() => active.value && visible.value && store.sessionActive && !access.checking && !access.challenge && !access.restricted && !store.changes.get('training-access-command')?.locked)
const input = reactive<Filters>({ zoneId: todayZoneValid(browserZone()) ? browserZone() : 'UTC', from: '', to: '', target: null, topicId: null, dimension: null, linked: false, classified: false })
const applied = shallowRef<Filters>(copy(input)), filterProblem = ref(''), picker = ref(false), tab = ref<Tab>('evidence'), refreshRound = ref(0)
const kind = ref<g.AvailableEvidenceKind>('TRAINING_ANSWER'), level = ref<g.AssessmentLevel | null>(null), flag = ref<g.ReviewFlag | null>(null), grain = ref<g.Grain>('DAY'), disposition = ref<g.SourceDisposition | null>(null)
const useStatus = ref<g.UseStatus | null>(null), requirementId = ref<string | null>(null), notice = ref('')
const scope = computed(() => JSON.stringify([applied.value, refreshRound.value]))
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
const kindOptions = g.evidenceKinds.map(value => ({ value, label: g.evidenceKindLabels[value] }))
const levelOptions = [{ value: 'SELF_RECORDED', label: '本人保存的记录' }, { value: 'AI_UNREVIEWED', label: 'AI 未审观察' }, { value: 'REVIEWED_OBSERVATION', label: '受审观察 · 有限条件' }]
const flagOptions = [{ value: 'DISPUTED', label: '有争议' }, { value: 'REVIEW_REQUIRED', label: '待复核' }, { value: 'SOURCE_UNAVAILABLE', label: '来源不可用' }]
const coverageLabels: Record<g.CoverageMetric, string> = { ACTIVE_USES: '有效状态关联', SAVED_SUPPORTS: '保存为支持', SAVED_PARTIAL: '保存为部分适用', CONTEXT_ONLY: '仅背景', INSUFFICIENT: '证据不足', EFFECTIVE_POSITIVE: '当前正向标注', INVALIDATED_POSITIVE: '正向标注已失效', WITHDRAWN_USES: '已撤回' }
const topicOptions = computed(() => topics.value.map(value => ({ value: value.id, label: value.name })))
const selectedMetric = ref<g.ActivityMetric>('ANSWERS_SUBMITTED')
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
function loadTab() { if (!readable.value) return; if (tab.value === 'evidence') loadEvidence(); if (tab.value === 'activity') loadActivity(); if (tab.value === 'sources') loadSources(); if (tab.value === 'coverage') { loadCoverage(); loadUses() } }
function selectTab(value: Tab): boolean {
  if (!readable.value) return false
  if (value === tab.value) return true
  // 子页持有未决写请求时保留可见恢复入口，不能通过外层标签绕过原请求核对。
  if (tab.value === 'next' && !store.canLeave(['today-placement-editor', 'growth-next-step-preference'])) return false
  tab.value = value
  return true
}
function selectEvidenceKind(value: g.EvidenceKind) { if (selectTab('evidence')) kind.value = value }
function refresh() { refreshRound.value++; notice.value = ''; evidenceCursors.clear(); sourceCursors.clear(); useCursors.clear() }
function applyFilters() {
  filterProblem.value = !todayZoneValid(input.zoneId) ? '请填写 IANA 时区，例如 Asia/Shanghai。' : (input.from || input.to) && !g.growthDateRangeValid(input.from, input.to) ? '请同时填写起止日期，范围最多 366 天。' : ''
  if (filterProblem.value || !readable.value) return
  applied.value = copy(input); picker.value = false; requirementId.value = null; refresh()
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
watch([readable, scope, () => store.sessionEpoch], () => { if (readable.value) { loadOverview(); loadTab() } else { topics.value = []; topicGeneration.value++ } }, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], loadTopics, { immediate: true, flush: 'sync' })
watch(tab, loadTab)
watch([kind, level, flag], () => { evidenceCursors.clear(); if (readable.value && tab.value === 'evidence') loadEvidence() }, { flush: 'sync' })
watch(grain, () => { if (readable.value) loadActivity() }, { flush: 'sync' })
watch(disposition, () => { sourceCursors.clear(); if (readable.value) loadSources() }, { flush: 'sync' })
watch([useStatus, requirementId], () => { useCursors.clear(); if (readable.value && tab.value === 'coverage') loadUses() }, { flush: 'sync' })
function visibility() { visible.value = document.visibilityState === 'visible' }
function focus() { if (readable.value) refresh() }
onMounted(() => { document.addEventListener('visibilitychange', visibility); window.addEventListener('focus', focus); unsubscribe = queryClient.getQueryCache().subscribe(event => {
  if (event.type === 'updated' && event.action.type === 'invalidate' && readable.value) { if (refreshTimer !== undefined) clearTimeout(refreshTimer); refreshTimer = window.setTimeout(refresh, 120) }
}) })
onActivated(() => { active.value = true }); onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; topicGeneration.value++; unsubscribe?.(); if (refreshTimer !== undefined) clearTimeout(refreshTimer); document.removeEventListener('visibilitychange', visibility); window.removeEventListener('focus', focus) })
</script>

<template>
  <main class="growth-workspace">
    <header class="growth-heading"><div><h1>成长</h1><p>从确切记录看见进展，也保留尚未验证的条件。</p></div><NButton :disabled="!readable" @click="refresh">重新读取</NButton></header>
    <p v-if="!readable" class="notice" role="status">成长资料暂不可读，请完成当前训练资料访问确认或恢复会话。</p>
    <div v-show="readable" :inert="!readable || undefined" class="growth-content">
      <form class="growth-filters" @submit.prevent="applyFilters">
        <label>起始日期<input v-model="input.from" type="date" :disabled="!readable" /></label><label>结束日期<input v-model="input.to" type="date" :disabled="!readable" /></label>
        <label>显示时区<NInput v-model:value="input.zoneId" :maxlength="100" :disabled="!readable" /></label>
        <label>目标版本<NButton :disabled="!readable" @click="picker = !picker">{{ input.target ? `${input.target.title} · v${input.target.number}` : '全部目标 · 选择确切版本' }}</NButton></label>
        <label>主题<NSelect v-model:value="input.topicId" :options="topicOptions" clearable :disabled="!readable" placeholder="全部主题" /></label>
        <label>考察项<NSelect v-model:value="input.dimension" :options="dimensionOptions" clearable :disabled="!readable" placeholder="全部考察项" /></label>
        <div class="filter-switches"><NCheckbox v-model:checked="input.linked" :disabled="!readable || !input.target">包含有效跨目标关联</NCheckbox><NCheckbox v-model:checked="input.classified" :disabled="!readable">包含当前主题分类</NCheckbox></div>
        <div class="row-actions"><NButton attr-type="submit" type="primary" :disabled="!readable">应用筛选</NButton><NButton v-if="input.target" :disabled="!readable" @click="input.target = null; input.linked = false">清除目标</NButton></div>
      </form>
      <GrowthTargetPicker v-if="picker" :disabled="!readable" @choose="input.target = $event; picker = false" />
      <p v-if="filterProblem || topicsError" class="notice error" role="alert">{{ filterProblem || topicsError }}</p>
      <p class="field-help">留空日期使用服务器默认范围。活动始终按发生时记录的目标统计；跨目标复用只影响证据发现。当前来源处理与下一步不受活动日期范围影响。</p>
      <NSkeleton v-if="overview.busy.value" text :repeat="3" />
      <p v-if="overview.error.value" class="notice error" role="alert">{{ g.growthErrorText(overview.error.value) }}</p>
      <section v-if="overview.data.value" class="growth-overview" aria-label="记录总览">
        <p class="field-help">{{ overview.data.value.scope.window.from }} 至 {{ overview.data.value.scope.window.to }} · {{ applied.zoneId }} · 核对于 {{ time(overview.data.value.context.checkedAt) }}</p>
        <div class="kind-summary"><button v-for="group in overview.data.value.evidenceGroups" :key="group.kind" type="button" :disabled="!readable" @click="selectEvidenceKind(group.kind)"><span>{{ g.evidenceKindLabels[group.kind] }}</span><strong>{{ countText(group.count) }}</strong><small>{{ group.scanLimited ? '已达扫描上限' : capabilityText(group.capability) }}</small></button></div>
        <div class="coverage-counts" aria-label="当前来源处理汇总"><span v-for="item in overview.data.value.sourceProgress.counts" :key="item.disposition">{{ g.sourceDispositionLabels[item.disposition] }}：{{ countText(item.count) }}</span></div>
        <p v-if="overview.data.value.emptyState === 'RECOLLECTION_ONLY'" class="notice">当前范围只有面后回忆；回忆不等同于当时的正式原答。</p><p v-else-if="overview.data.value.emptyState === 'NO_RECORD'" class="notice">当前范围未查到记录。可以从正式训练、复盘或主动回忆开始积累。</p><p v-else-if="overview.data.value.emptyState === 'UNKNOWN'" class="notice">有来源尚未完整核对，暂不能判断当前范围没有记录。</p>
        <details><summary>统计口径与限制</summary><ul><li v-for="item in overview.data.value.limitations" :key="item">{{ g.growthLimitationLabels[item] }}</li></ul></details>
      </section>
      <nav class="growth-tabs" aria-label="成长工作区"><NButton v-for="item in tabs" :key="item.value" :type="tab === item.value ? 'primary' : 'default'" :disabled="!readable" @click="selectTab(item.value)">{{ item.label }}</NButton></nav>
      <section v-show="tab === 'evidence'" class="growth-section">
        <header><h2>证据目录</h2><div class="compact-filters"><NSelect v-model:value="kind" :options="kindOptions" aria-label="证据类型" /><NSelect v-model:value="level" :options="levelOptions" clearable placeholder="全部记录层级" aria-label="记录层级" /><NSelect v-model:value="flag" :options="flagOptions" clearable placeholder="全部复核状态" aria-label="复核状态" /></div></header>
        <NSkeleton v-if="evidence.busy.value" text :repeat="3" /><p v-if="evidence.error.value" class="notice error" role="alert">{{ g.growthErrorText(evidence.error.value) }} <NButton text @click="loadEvidence()">从首页重读</NButton></p>
        <template v-if="evidence.data.value"><p class="field-help">{{ capabilityText(evidence.data.value.capability) }} · {{ countText(evidence.data.value.count) }} 条{{ evidence.data.value.scanLimited ? ' · 扫描达到上限，仍可继续分页' : '' }}</p>
          <p v-if="kind === 'REVIEWED_OBSERVATION'" class="field-help">每条目录项保留一个不可变观察修订，包括后续修改与撤回。多个修订仍来自同一回答；有限观察不自动代表已掌握，也不会自动标为支持目标。</p>
          <article v-for="item in evidence.data.value.items" :key="g.evidenceKey(item.ref)" class="evidence-row"><div><h3>{{ item.title }}</h3><p class="field-help">{{ time(item.occurredAt) }} · {{ g.assessmentLevelLabels[item.assessmentLevel] }}<template v-if="item.ref.kind === 'REVIEWED_OBSERVATION'"> · 观察修订 {{ item.ref.revision }}</template></p><p>{{ g.directStateLabels[item.state.directState] }}{{ item.state.recordCurrency === 'SUPERSEDED' ? ' · 已有后续版本' : '' }}{{ item.state.reviewFlags.includes('DISPUTED') ? ' · 有争议' : '' }}</p><p v-if="item.conditions.kind === 'REVIEWED_OBSERVATION'" class="field-help">{{ reviewedEvidenceStatus(item) }}</p><p v-if="item.state.lineageCheck === 'PARTIAL'" class="field-help">间接派生依据仅完成部分检查。</p><p v-if="item.facets.selectedTargetMatch" class="field-help">{{ item.facets.selectedTargetMatch.recorded ? '发生时记录于所选目标' : '通过有效个人关联发现' }}</p><details><summary>固定条件与证据身份</summary><p class="identity">{{ g.evidenceKey(item.ref) }}</p><dl v-if="item.conditions.kind === 'REVIEWED_OBSERVATION'" class="reviewed-metadata"><template v-for="row in reviewedEvidenceRows(item)" :key="row.label"><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></template></dl><pre v-else>{{ JSON.stringify(item.conditions, null, 2) }}</pre></details></div><div class="row-actions"><NButton size="small" @click="openEvidence(item)">查看确切记录</NButton><NButton size="small" @click="editEvidence(item)">关联目标要求</NButton></div></article>
          <p v-if="!evidence.data.value.items.length" class="notice">{{ evidence.data.value.nextCursor ? '本页没有匹配项，仍有后续候选可继续核对。' : evidence.data.value.capability.status === 'READY' ? '当前读取范围没有匹配条目。' : '此来源暂未返回可展示条目，数量未知。' }}</p>
          <NButton v-if="evidence.data.value.nextCursor" :loading="evidence.busy.value" @click="loadEvidence(evidence.data.value.nextCursor)">继续读取</NButton>
        </template>
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
      <GrowthNextSteps v-show="tab === 'next'" :disabled="!readable || tab !== 'next'" :zone-id="applied.zoneId" :target="applied.target?.ref" :refresh="refreshRound" @changed="refresh" />
      <GrowthUseEditor v-if="editorIntent" v-show="editorOpen" :disabled="!readable || !editorOpen" :intent="editorIntent" @changed="refresh" @close="closeEditor" />
      <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    </div>
  </main>
</template>

<style scoped>
.reviewed-metadata { display:grid; grid-template-columns:120px minmax(0,1fr); gap:8px 12px; margin:14px 0 0; max-width:85ch; }.reviewed-metadata dt { color:var(--muted); }.reviewed-metadata dd { margin:0; overflow-wrap:anywhere; line-height:1.6; }@media(max-width:560px) { .reviewed-metadata { grid-template-columns:1fr; gap:4px; }.reviewed-metadata dd { margin-bottom:8px; } }
.growth-workspace,.growth-content,.growth-section,.growth-overview { display:grid; gap:20px; min-width:0; }.growth-workspace { padding:30px; }.growth-heading,.growth-section>header { display:flex; align-items:center; justify-content:space-between; gap:18px; flex-wrap:wrap; }.growth-heading p { color:var(--muted); margin-top:9px; }.growth-filters { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:14px; padding:20px; background:var(--surface); border:1px solid var(--line); border-radius:7px; }label { display:grid; gap:7px; font-size:12px; color:var(--muted); }input { width:100%; min-height:34px; padding:5px 10px; border:1px solid var(--line); border-radius:4px; color:var(--ink); background:var(--surface); }.filter-switches { display:flex; gap:16px; flex-wrap:wrap; grid-column:1/-1; }.kind-summary { display:flex; flex-wrap:wrap; border-top:1px solid var(--line); border-bottom:1px solid var(--line); }.kind-summary button { flex:1 1 180px; display:grid; gap:7px; text-align:left; padding:15px 12px; }.kind-summary strong { font-size:20px; }.kind-summary small { color:var(--muted); }.growth-tabs,.row-actions { display:flex; gap:8px; flex-wrap:wrap; }.growth-tabs { padding-bottom:16px; border-bottom:1px solid var(--line); }.compact-filters { display:flex; gap:10px; flex-wrap:wrap; }.compact-filters>* { width:190px; }.growth-section h2 { font-size:21px; }.evidence-row { display:flex; gap:18px; justify-content:space-between; align-items:start; padding:18px 0; border-bottom:1px solid var(--line); }.evidence-row>div:first-child { min-width:0; display:grid; gap:7px; }.evidence-row>.row-actions { flex:none; }.requirement-row { display:grid; gap:12px; padding:18px 0; border-bottom:1px solid var(--line); }.requirement-row>button { justify-self:start; }.coverage-counts { display:flex; gap:10px 20px; flex-wrap:wrap; font-size:12px; }.table-scroll { overflow:auto; border:1px solid var(--line); border-radius:6px; background:var(--surface); }table { width:100%; border-collapse:collapse; font-size:13px; text-align:left; }caption { text-align:left; padding:15px; font-weight:600; }th,td { border-top:1px solid var(--line); padding:11px 14px; }th { font-weight:550; }thead { background:var(--surface-soft); }td { font-variant-numeric:tabular-nums; }.small-select { width:110px; }.medium-select,.metric-picker { width:min(100%,340px); }details { font-size:12px; }summary { cursor:pointer; color:var(--muted); }pre { max-height:260px; overflow:auto; white-space:pre-wrap; overflow-wrap:anywhere; font-size:12px; background:var(--surface-soft); padding:12px; }.identity { overflow-wrap:anywhere; font-family:Consolas,monospace; font-size:12px; }.field-help { overflow-wrap:anywhere; }@media(max-width:900px) { .growth-filters { grid-template-columns:repeat(2,minmax(0,1fr)); }.growth-workspace { padding:22px; }.evidence-row { flex-direction:column; } }@media(max-width:560px) { .growth-filters { grid-template-columns:1fr; }.growth-workspace { padding:16px; }.compact-filters>* { width:100%; }.kind-summary button { flex-basis:140px; } }
</style>
