<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { read } from '../../api/client'
import type { Count, TargetRef } from '../../api/growth'
import { growthErrorText, growthTargetCoveragePath, growthUsesPath, targetCoverageValid, usePageValid } from '../../api/growth'
import { countText, evidenceRoute, invalidGrowth } from '../growth/growthUi'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import type { TargetContent } from '../../api/preparation'
import RIcon from '../../replica/RIcon.vue'

const props = defineProps<{ targetId: string; versionId: string; requirements: TargetContent['requirements'] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter()
const active = ref(true), visible = ref(document.visibilityState === 'visible')
const target = computed<TargetRef>(() => ({ itemId: props.targetId, versionId: props.versionId }))
const readable = computed(() => active.value && visible.value && store.sessionActive && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const coverage = useQuery({ queryKey: computed(() => ['growth', store.sessionEpoch, 'prepare-coverage', target.value]), enabled: readable,
  queryFn: async () => { const ref = target.value, value = await read<unknown>(growthTargetCoveragePath(ref)); if (!targetCoverageValid(value, ref)) throw invalidGrowth(); return value } })
const links = useQuery({ queryKey: computed(() => ['growth', store.sessionEpoch, 'prepare-uses', target.value]), enabled: readable,
  queryFn: async () => {
    const query = { targetId: target.value.itemId, targetVersionId: target.value.versionId, status: 'ACTIVE' as const, limit: 20 }
    const value = await read<unknown>(growthUsesPath(query)); if (!usePageValid(value, query)) throw invalidGrowth(); return value
  } })
const unknown: Count = { mode: 'UNAVAILABLE', value: null }
const rows = computed(() => props.requirements.map(requirement => {
  const result = coverage.data.value?.requirements.find(entry => entry.requirement.id === requirement.id)
  const count = result?.counts.find(entry => entry.metric === 'ACTIVE_USES')?.count ?? unknown
  const positive = result?.counts.find(entry => entry.metric === 'EFFECTIVE_POSITIVE')?.count ?? unknown
  const latest = links.data.value?.items.filter(entry => entry.requirement.id === requirement.id && entry.evidence.summary)
    .sort((a, b) => b.evidence.summary!.occurredAt.localeCompare(a.evidence.summary!.occurredAt))[0]
  return { requirement, count, positive: positive.mode !== 'UNAVAILABLE' && positive.value > 0, latest,
    state: count.mode === 'UNAVAILABLE' ? '待核对' : count.value === 0 ? '无记录' : positive.mode !== 'UNAVAILABLE' && positive.value > 0 ? '有个人标注' : '待补' }
}))
function open(row: typeof rows.value[number]) {
  if (readable.value && row.latest?.evidence.summary) void router.push(evidenceRoute(row.latest.evidence.summary))
}
function date(value: string) { return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit' }).format(new Date(value)) }
function visibilityChanged() { visible.value = document.visibilityState === 'visible' }
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onActivated(() => { active.value = true; visibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => document.removeEventListener('visibilitychange', visibilityChanged))
</script>

<template>
  <div class="target-evidence-table">
    <p v-if="!readable" class="field-help">请恢复资料访问后查看证据。</p>
    <template v-else>
    <p v-if="coverage.error.value || links.error.value" class="notice error">{{ growthErrorText(coverage.error.value || links.error.value) }}</p>
    <table><thead><tr><th>要求</th><th>关联证据</th><th>状态</th><th title="当前已读关联中的最近发生时间">最近</th></tr></thead><tbody><tr v-for="row in rows" :key="row.requirement.id"><td>{{ row.requirement.text }}</td><td>{{ countText(row.count) }}{{ row.count.mode === 'UNAVAILABLE' ? '' : ' 条' }}</td><td><span :class="['requirement-state', { positive: row.positive, missing: row.state === '无记录' }]"><i />{{ row.state }}</span></td><td><button v-if="row.latest?.evidence.summary" :title="row.latest.evidence.summary.title" @click="open(row)">{{ date(row.latest.evidence.summary.occurredAt) }}</button><span v-else>—</span></td></tr></tbody></table>
    <p v-if="coverage.isFetching.value || links.isFetching.value" class="field-help" role="status">正在核对确切目标版本的证据…</p><p v-if="links.data.value?.nextCursor" class="field-help"><RIcon name="info" :size="18" />最近日期仅覆盖当前已读页，仍有后续关联。</p>
    </template>
  </div>
</template>

<style scoped>
.target-evidence-table { overflow-x: auto; }table { width: 100%; border-collapse: collapse; text-align: left; font-size: 18px; }th { color: var(--muted); font-size: 17px; font-weight: 400; white-space: nowrap; }th,td { padding: 19px 8px; border-bottom: 1px solid var(--line); }td:first-child { width: 44%; overflow-wrap: anywhere; }td:not(:first-child) { white-space: nowrap; }.requirement-state { display: inline-flex; align-items: center; gap: 7px; padding: 4px 10px; font-size: 15px; color: var(--brand); background: var(--brand-soft); border: 1px solid var(--line); border-radius: 20px; }.requirement-state i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }.requirement-state.positive { color: #83c3a5; background: #2f594438; }.requirement-state.missing { color: var(--muted); background: var(--surface-soft); }td button { border-bottom: 1px solid var(--muted); }.field-help { display: flex; align-items: center; gap: 8px; margin-top: 16px; }
</style>
