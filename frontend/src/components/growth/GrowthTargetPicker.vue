<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NInput, NSelect, NSkeleton } from 'naive-ui'
import type { PreparationPage, PreparationSummary, PreparationVersionPage } from '../../api/preparation'
import { growthErrorText } from '../../api/growth'
import type { TargetRef } from '../../api/growth'
import { todayIdValid } from '../../api/today'
import { useWorkspaceStore } from '../../state/workspace'
import { useGrowthRead } from './growthUi'
const props = defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ choose: [value: { ref: TargetRef; title: string; number: number }] }>()
const store = useWorkspaceStore(), readable = computed(() => store.sessionActive && !props.disabled)
const input = ref(''), search = ref(''), status = ref('ACTIVE'), selected = ref<PreparationSummary>(), round = ref(0)
const scope = computed(() => JSON.stringify([search.value, status.value, round.value])), versionScope = computed(() => JSON.stringify([scope.value, selected.value?.id]))
const targets = useGrowthRead<PreparationPage>(readable, scope), versions = useGrowthRead<PreparationVersionPage>(readable, versionScope)
function load(cursor?: string) {
  if (!readable.value) return
  const q = new URLSearchParams({ kind: 'TARGET', status: status.value, q: search.value, limit: '20', ...(cursor ? { cursor } : {}) })
  void targets.load(`/prepare/items?${q}`, (v): v is PreparationPage => {
    const page = v as PreparationPage; return !!page && Array.isArray(page.items) && page.items.length <= 20 && page.items.every(item => item.kind === 'TARGET' && todayIdValid(item.id) && todayIdValid(item.versionId) && typeof item.title === 'string') && (page.nextCursor === null || todayIdValid(page.nextCursor))
  }, cursor ? (previous, next) => ({ ...next, items: [...(previous?.items ?? []), ...next.items] }) : undefined)
}
function loadVersions(before?: number) {
  if (!selected.value) return
  void versions.load(`/prepare/items/${selected.value.id}/versions?limit=20${before === undefined ? '' : `&before=${before}`}`, (v): v is PreparationVersionPage => {
    const page = v as PreparationVersionPage; return !!page && Array.isArray(page.items) && page.items.length <= 20 && page.items.every(item => todayIdValid(item.id) && Number.isSafeInteger(item.number) && item.number > 0 && typeof item.title === 'string') && (page.nextCursor === null || Number.isSafeInteger(page.nextCursor) && page.nextCursor > 0)
  }, before === undefined ? undefined : (previous, next) => ({ ...next, items: [...(previous?.items ?? []), ...next.items] }))
}
function select(value: PreparationSummary) { selected.value = value; loadVersions() }
function choose(versionId: string, title: string, number: number) { if (readable.value && selected.value) emit('choose', { ref: { itemId: selected.value.id, versionId }, title, number }) }
function submit() { search.value = input.value.trim(); selected.value = undefined; round.value++ }
watch([readable, scope, () => store.sessionEpoch], () => { selected.value = undefined; if (readable.value) load() }, { immediate: true, flush: 'sync' })
</script>
<template>
  <section class="growth-target-picker" aria-label="选择确切目标版本"><form @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" :disabled="!readable" placeholder="按目标标题查找" aria-label="目标标题" /><NSelect v-model:value="status" :options="[{label:'活跃目标',value:'ACTIVE'},{label:'已归档目标',value:'ARCHIVED'}]" :disabled="!readable" aria-label="目标状态" /><NButton attr-type="submit" :disabled="!readable">查找</NButton></form>
    <p class="field-help">先选择目标，再选择确切版本。历史版本可用于查看原有关联，不自动迁移到当前版本。</p><NSkeleton v-if="targets.busy.value" text :repeat="2" /><p v-if="targets.error.value" class="notice error" role="alert">{{ growthErrorText(targets.error.value) }} <NButton text @click="load()">重读</NButton></p>
    <div class="target-list"><button v-for="target in targets.data.value?.items" :key="target.id" :disabled="!readable" type="button" :aria-pressed="selected?.id === target.id" @click="select(target)"><strong>{{ target.title }}</strong><small>{{ target.status === 'ACTIVE' ? '活跃' : '归档' }} · 当前 v{{ target.versionNumber }}</small></button></div><p v-if="targets.data.value && !targets.data.value.items.length" class="field-help">没有匹配目标，可换一个标题或状态查找。</p><NButton v-if="targets.data.value?.nextCursor" :disabled="!readable || targets.busy.value" @click="load(targets.data.value.nextCursor)">更多目标</NButton>
    <section v-if="selected" class="version-list"><h3>{{ selected.title }} · 选择版本</h3><NSkeleton v-if="versions.busy.value" text :repeat="2" /><p v-if="versions.error.value" class="notice error">{{ growthErrorText(versions.error.value) }}</p><button v-for="version in versions.data.value?.items" :key="version.id" type="button" :disabled="!readable" @click="choose(version.id, version.title, version.number)"><span>v{{ version.number }} · {{ version.title }}</span><small>{{ version.id === selected.versionId ? '当前版本' : '历史版本' }}</small></button><NButton v-if="versions.data.value?.nextCursor" :disabled="!readable || versions.busy.value" @click="loadVersions(versions.data.value.nextCursor)">更早版本</NButton></section>
  </section>
</template>
<style scoped>
.growth-target-picker { display:grid; gap:14px; padding:18px; border:1px solid var(--line); border-radius:7px; background:var(--surface-soft); }form { display:grid; grid-template-columns:minmax(0,1fr) 140px auto; gap:10px; }.target-list { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:5px 16px; }.target-list button,.version-list>button { display:flex; flex-direction:column; gap:6px; padding:12px 4px; text-align:left; border-bottom:1px solid var(--line); overflow-wrap:anywhere; }.target-list button[aria-pressed=true] { color:var(--brand-strong); background:var(--brand-soft); }.version-list { display:grid; gap:10px; }.version-list small,.target-list small { color:var(--muted); font-size:12px; }@media(max-width:620px) { form { grid-template-columns:minmax(0,1fr) 130px; }.target-list { grid-template-columns:1fr; } }
</style>
