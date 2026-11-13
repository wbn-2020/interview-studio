<script setup lang="ts">
import { computed, ref } from 'vue'
import { effectiveUseStateLabels } from '../../api/growth'
import type { AssessmentLevel, EvidenceSummary, ReviewFlag, Scope, TargetCoverage, UseEntry, UsePage } from '../../api/growth'
import { memoryUtcMicros } from '../../api/memoryReview'
import RIcon from '../../replica/RIcon.vue'

const props = defineProps<{ coverage?: TargetCoverage; uses?: UsePage; scope?: Scope; level: AssessmentLevel | null; flag: ReviewFlag | null; busy: boolean; zoneId: string }>()
const emit = defineEmits<{ open: [entry: EvidenceSummary]; chooseTarget: [] }>()
const columns = [
  { kind: 'MEMORY_RATING', label: '回忆', icon: 'rotate-ccw' },
  { kind: 'TRAINING_ANSWER', label: '作答', icon: 'square-pen' },
  { kind: 'REPLAY_JUDGMENT', label: '复练', icon: 'refresh-cw' },
  { kind: 'REVIEWED_OBSERVATION', label: '受审观察', icon: 'eye' },
] as const
const enabled = ref<string[]>(columns.map(column => column.kind))
const visibleColumns = computed(() => columns.filter(column => enabled.value.includes(column.kind)))
const timeWindow = computed(() => props.scope ? {
  start: memoryUtcMicros(props.scope.window.startAt),
  end: memoryUtcMicros(props.scope.window.observedUntil),
} : undefined)
function inScope(entry: UseEntry): boolean {
  const value = entry.evidence.summary, scope = props.scope
  if (!value || !scope || entry.use.status !== 'ACTIVE') return false
  // 服务端窗口精确到微秒，不能用毫秒截断或字面排序判定边界及最新记录。
  const instant = memoryUtcMicros(value.occurredAt), bounds = timeWindow.value, filter = scope.filter
  if (instant === undefined || bounds?.start === undefined || bounds.end === undefined
    || instant < bounds.start || instant >= bounds.end
    || props.level && value.assessmentLevel !== props.level || props.flag && !value.state.reviewFlags.includes(props.flag)) return false
  if (filter.dimension && !value.facets.dimensions.some(facet => facet.basis === 'RECORDED' && facet.dimension === filter.dimension)) return false
  if (filter.topicId && !value.facets.topics.some(facet => facet.topicId === filter.topicId
    && (facet.basis === 'RECORDED' || filter.topicMode === 'INCLUDE_CURRENT_CLASSIFICATION' && facet.basis === 'CURRENT_CLASSIFICATION'))) return false
  // 本矩阵的 ACTIVE 关联本身可证明跨目标链接，但不能冒充记录发生时的目标。
  return !filter.target || filter.targetMode === 'INCLUDE_ACTIVE_LINKS' || value.facets.targets.some(facet =>
    facet.basis === 'RECORDED' && facet.target.itemId === filter.target!.itemId && facet.target.versionId === filter.target!.versionId)
}
const rows = computed(() => (props.coverage?.requirements ?? [])
  .filter(row => !props.scope?.filter.dimension || row.requirement.dimensions.includes(props.scope.filter.dimension))
  .map(row => ({ requirement: row.requirement, cells: visibleColumns.value.map(column => ({
    column, entry: props.uses?.items.filter(entry => entry.requirement.id === row.requirement.id
      && entry.evidence.summary?.ref.kind === column.kind && inScope(entry))
      .sort((a, b) => {
        const left = memoryUtcMicros(a.evidence.summary!.occurredAt)!, right = memoryUtcMicros(b.evidence.summary!.occurredAt)!
        return left === right ? a.use.id.localeCompare(b.use.id) : left > right ? -1 : 1
      })[0],
  })) })))
function date(value: string) { return new Intl.DateTimeFormat('zh-CN', { timeZone: props.zoneId, month: '2-digit', day: '2-digit' }).format(new Date(value)) }
</script>

<template>
  <section class="evidence-matrix" aria-label="要求与证据">
    <header><h2>要求与证据</h2><fieldset><legend>证据类型</legend><label v-for="column in columns" :key="column.kind"><input v-model="enabled" type="checkbox" :value="column.kind" />{{ column.label }}</label></fieldset></header>
    <p v-if="busy" class="matrix-note" role="status">正在读取目标要求与关联证据…</p>
    <div v-if="coverage && scope" class="matrix-scroll"><table><thead><tr><th scope="col"><span class="sr-only">目标要求</span></th><th v-for="column in visibleColumns" :key="column.kind" scope="col"><RIcon :name="column.icon" :size="30" /><span>{{ column.label }}</span></th></tr></thead>
      <tbody><tr v-for="row in rows" :key="row.requirement.id"><th scope="row">{{ row.requirement.text }}</th><td v-for="cell in row.cells" :key="cell.column.kind"><button v-if="cell.entry?.evidence.summary" :class="[cell.column.kind.toLowerCase(), { 'is-positive': cell.entry.effectiveState === 'EFFECTIVE_POSITIVE' }]" :title="`${cell.entry.evidence.summary.title} · ${effectiveUseStateLabels[cell.entry.effectiveState]}`" @click="emit('open', cell.entry.evidence.summary)"><span><i />{{ date(cell.entry.evidence.summary.occurredAt) }}</span><small v-if="cell.entry.effectiveState !== 'EFFECTIVE_POSITIVE'">{{ effectiveUseStateLabels[cell.entry.effectiveState] }}</small></button><span v-else class="matrix-missing" title="当前已读关联中没有符合筛选的这一类记录；不代表全库没有证据。">—</span></td></tr></tbody>
    </table><p v-if="!rows.length" class="matrix-note">此目标版本没有符合当前方向的要求。</p></div>
    <p v-else-if="coverage && !busy" class="matrix-note">筛选范围尚未读取成功，请重新读取记录总览。</p>
    <div v-else-if="!busy" class="matrix-empty"><RIcon name="target" :size="42" /><h3>选择一个目标版本</h3><p>要求与证据会按确切目标版本展示。</p><button @click="emit('chooseTarget')">选择目标</button></div>
    <footer v-if="coverage">按当前日期、主题、考察项与记录条件筛选；仅展示已读关联{{ uses?.nextCursor ? '，仍有后续页' : '' }}。日期表示记录发生时间，不表示能力结论。</footer>
  </section>
</template>

<style scoped>
.evidence-matrix { min-width: 0; min-height: 615px; border: 1px solid var(--line); border-radius: 18px; background: var(--surface); overflow: hidden; }
header { padding: 25px; border-bottom: 1px solid var(--line); }h2 { margin: 0 0 20px; font-size: 26px; }fieldset { display: flex; flex-wrap: wrap; gap: 12px 20px; border: 0; padding: 0; margin: 0; }legend { position: absolute; width: 1px; height: 1px; overflow: hidden; }label { display: flex; align-items: center; gap: 8px; font-size: 16px; color: var(--muted); }input { width: 18px; height: 18px; accent-color: var(--brand); }
.matrix-scroll { overflow-x: auto; }table { width: 100%; border-collapse: collapse; text-align: center; table-layout: fixed; }thead th { height: 105px; font-size: 18px; font-weight: 400; }thead th > svg { display: block; margin: 0 auto 12px; }thead th:first-child { width: 29%; }th,td { border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); padding: 18px 12px; }th:last-child,td:last-child { border-right: 0; }tbody th { font-size: 19px; font-weight: 500; text-align: left; line-height: 1.7; padding: 22px; overflow-wrap: anywhere; }td button { display: inline-flex; align-items: center; gap: 8px; padding: 5px 0; font-size: 17px; white-space: nowrap; }td i { width: 10px; height: 10px; border-radius: 50%; background: #60af94; }.training_answer i,.reviewed_observation i { background: #529aca; }.matrix-missing { color: var(--faint); font-size: 23px; }
.matrix-empty { display: grid; justify-items: center; align-content: center; gap: 20px; min-height: 410px; padding: 30px; color: var(--muted); }.matrix-empty h3 { font-size: 25px; margin: 0; color: var(--ink); }.matrix-empty p { margin: 0; font-size: 18px; }.matrix-empty button { padding: 12px 25px; border: 1px solid var(--brand); border-radius: 28px; color: var(--brand-strong); font-size: 19px; }.matrix-note,footer { padding: 18px 24px; font-size: 14px; line-height: 1.7; color: var(--muted); }
td button { flex-direction: column; white-space: normal; }td button > span { display: flex; align-items: center; gap: 8px; }td button small { font-size: 12px; line-height: 1.5; color: var(--muted); }td button i { background: var(--faint); }td button.is-positive i { background: #60af94; }td button.is-positive.training_answer i,td button.is-positive.reviewed_observation i { background: #529aca; }
</style>
