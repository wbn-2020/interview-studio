<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NSelect, NSkeleton } from 'naive-ui'
import * as g from '../../api/growth'
import { todayContinuationRoute } from '../../api/today'
import type { TodayContinuation } from '../../api/today'
import { useWorkspaceStore } from '../../state/workspace'
import { capabilityText, copy, countText, invalidGrowth, nextRoute, useGrowthCommand, useGrowthRead } from './growthUi'
import TodayPlacementEditor from '../today/TodayPlacementEditor.vue'

const props = defineProps<{ disabled: boolean; zoneId: string; target?: g.TargetRef; refresh: number }>()
const emit = defineEmits<{ changed: [] }>()
const router = useRouter(), store = useWorkspaceStore(), readable = computed(() => store.sessionActive && !props.disabled)
const scope = computed(() => JSON.stringify([props.zoneId, props.target, props.refresh]))
const steps = useGrowthRead<g.NextSteps>(readable, scope), visibility = ref<g.Visibility>('HIDDEN'), subjectKind = ref<g.SubjectKind | null>(null)
const directoryScope = computed(() => JSON.stringify([props.refresh, visibility.value, subjectKind.value]))
const directory = useGrowthRead<g.PreferencePage>(readable, directoryScope)
const selected = shallowRef<g.SubjectIdentity>(), suggestedSubject = shallowRef<g.Subject>(), selectedTitle = ref(''), desired = ref<g.Visibility>('HIDDEN')
const selectedScope = computed(() => JSON.stringify(selected.value)), current = useGrowthRead<g.PreferenceSubjectLookup>(readable, selectedScope)
const baseline = shallowRef<g.PreferenceEntry | null>(), accepted = ref(false), message = ref(''), problem = ref('')
const dirty = computed(() => !!selected.value && desired.value !== (baseline.value?.preference.visibility ?? 'VISIBLE'))
const writer = useGrowthCommand(readable, 'growth-next-step-preference', dirty)
const historyScope = computed(() => JSON.stringify([selected.value, baseline.value?.preference.id])), history = useGrowthRead<g.PreferenceHistoryPage>(readable, historyScope)
type PrioritySelection = { kind: 'SOURCE'; source: g.GrowthSourceRef; title: string; zoneId: string; localDate: string }
  | { kind: 'TARGET_REQUIREMENT'; requirement: g.RequirementIdentity; title: string }
const prioritySelection = shallowRef<PrioritySelection>(), priorityMessage = ref('')
const placementLocked = computed(() => store.changes.get('today-placement-editor')?.locked === true)
let cursors = new Set<string>()
function loadSteps() {
  if (!readable.value) return
  const query: g.NextStepsQuery = { zoneId: props.zoneId, limit: 20, ...(props.target ? { targetId: props.target.itemId, targetVersionId: props.target.versionId } : {}) }
  void steps.load(g.growthNextStepsPath(query), (v): v is g.NextSteps => g.nextStepsValid(v, query))
}
function loadDirectory(cursor?: string) {
  if (!readable.value || directory.busy.value) return
  const query: g.PreferenceQuery = { visibility: visibility.value, ...(subjectKind.value ? { subjectKind: subjectKind.value } : {}), limit: 20, ...(cursor ? { cursor } : {}) }
  void directory.load(g.growthPreferencesPath(query), (v): v is g.PreferencePage => g.preferencePageValid(v, query), cursor ? (previous, next) => {
    if (next.nextCursor && cursors.has(next.nextCursor) || previous && previous.context.asOf !== next.context.asOf) throw invalidGrowth()
    cursors.add(cursor); return { ...next, items: [...new Map([...(previous?.items ?? []), ...next.items].map(entry => [entry.preference.id, entry])).values()] }
  } : undefined)
}
function lookup() {
  const identity = selected.value; if (!identity || !readable.value) return
  accepted.value = false
  void current.load(g.growthPreferenceSubjectPath(identity), (v): v is g.PreferenceSubjectLookup => g.preferenceSubjectLookupValid(v, identity))
}
function selectStep(value: g.NextStep) {
  if (!readable.value || writer.locked.value || !store.canLeave(['growth-next-step-preference'])) return
  selected.value = copy(g.subjectIdentity(value.subject)); suggestedSubject.value = copy(value.subject); baseline.value = undefined; selectedTitle.value = value.title; desired.value = 'HIDDEN'; message.value = ''; problem.value = ''; history.reset(); lookup()
}
function selectPreference(value: g.PreferenceEntry) {
  if (!readable.value || writer.locked.value || !store.canLeave(['growth-next-step-preference'])) return
  selected.value = copy(value.preference.subject); suggestedSubject.value = value.currentSubject ? copy(value.currentSubject) : undefined; baseline.value = copy(value); selectedTitle.value = value.title; desired.value = value.preference.visibility === 'HIDDEN' ? 'VISIBLE' : 'HIDDEN'; message.value = ''; problem.value = ''; history.reset(); lookup()
}
function acceptCurrent() {
  if (!readable.value || !current.data.value || writer.locked.value) return
  baseline.value = current.data.value.entry ? copy(current.data.value.entry) : null; accepted.value = true
  if (baseline.value) { suggestedSubject.value = baseline.value.currentSubject ? copy(baseline.value.currentSubject) : undefined; loadHistory() }
}
function save() {
  if (!readable.value || !accepted.value || !current.data.value || !selected.value || writer.locked.value) return
  const previous = baseline.value ? copy(baseline.value) : undefined, visibility = desired.value
  const received = (_value: g.NextStepPreference) => { accepted.value = false; message.value = '原请求回执已确认，正在独立核对当前显示偏好。'; lookup(); emit('changed') }
  if (previous) {
    const body: g.SavePreference = { expectedRevision: previous.preference.revision, expectedSubject: previous.currentSubject ? copy(previous.currentSubject) : null, visibility }
    if (!g.savePreferenceValid(body, previous.preference)) { problem.value = '来源当前不可用时，只能恢复为显示，以清除原隐藏意图。'; return }
    writer.run(g.growthPreferencePath(previous.preference.id), 'PUT', body, (v): v is g.NextStepPreference => g.savedNextStepPreferenceValid(v, previous.preference.id, body, previous.preference), received)
  } else {
    if (!suggestedSubject.value) { problem.value = '当前主体状态尚未核对。请从重新读取的建议选择此来源。'; return }
    const body: g.CreatePreference = { subject: copy(suggestedSubject.value), visibility }
    if (!g.createPreferenceValid(body)) { problem.value = '当前主体无法保存显示偏好，请重新核对。'; return }
    writer.run(g.growthCommandPaths.createPreference, 'POST', body, (v): v is g.NextStepPreference => g.createdNextStepPreferenceValid(v, body), received)
  }
}
function loadHistory(before?: number) {
  const id = baseline.value?.preference.id; if (!id || history.busy.value) return
  const query = { limit: 20, ...(before === undefined ? {} : { before }) }
  void history.load(g.growthPreferenceHistoryPath(id, query), (v): v is g.PreferenceHistoryPage => g.preferenceHistoryPageValid(v, id, query), before === undefined ? undefined : (previous, next) => ({ ...next, items: [...(previous?.items ?? []), ...next.items] }))
}
function open(value: g.NextStepContinuation | null) { if (!value || !readable.value) return; const route = nextRoute(value); if (route) void router.push(route) }
function adjustPriority(value: g.NextStep) {
  if (!readable.value || !steps.data.value || !store.canLeave(['today-placement-editor'])) return
  priorityMessage.value = ''
  // 安排编辑器固定来源和本次服务器日界；刷新建议、换筛选或跨日不会替换未完成的原请求。
  prioritySelection.value = value.subject.kind === 'SOURCE'
    ? { kind: 'SOURCE', source: copy(value.subject.source), title: value.title, zoneId: steps.data.value.scope.zoneId, localDate: steps.data.value.scope.localDate }
    : { kind: 'TARGET_REQUIREMENT', requirement: copy(value.subject.requirement), title: value.title }
}
function closePriority() { if (store.canLeave(['today-placement-editor'])) { prioritySelection.value = undefined; priorityMessage.value = '' } }
function placementClosed() {
  // 子编辑器已经完成脏输入确认；仍检查未决命令，避免其他事件绕过锁而卸载原请求。
  if (!placementLocked.value) { prioritySelection.value = undefined; priorityMessage.value = '' }
}
function placementChanged() { priorityMessage.value = '安排保存回执已确认，成长建议会重新读取；今日建议也使用这份来源安排。'; emit('changed') }
function openPlacementSource(value: TodayContinuation) {
  if (!readable.value || !store.canLeave(['today-placement-editor'])) return
  const route = todayContinuationRoute(value); if (route) void router.push(route)
}
function openTargetPriority() {
  const selected = prioritySelection.value
  if (!readable.value || selected?.kind !== 'TARGET_REQUIREMENT' || !store.canLeave(['today-placement-editor', 'growth-next-step-preference'])) return
  // prepare 的 version 参数进入历史只读页；修改要求需进入真实的当前目标编辑入口。
  void router.push({ name: 'prepare', query: { kind: 'TARGET', item: selected.requirement.target.itemId } })
}
function openTodayPlans() {
  if (readable.value && store.canLeave(['today-placement-editor', 'growth-next-step-preference'])) void router.push({ name: 'today' })
}
function close() { if (!writer.locked.value && store.canLeave(['growth-next-step-preference'])) { selected.value = undefined; baseline.value = undefined; suggestedSubject.value = undefined; accepted.value = false; message.value = '' } }
watch([readable, scope, () => store.sessionEpoch], () => { accepted.value = false; if (readable.value) { loadSteps(); if (selected.value) lookup() } }, { immediate: true, flush: 'sync' })
watch([readable, directoryScope, () => store.sessionEpoch], () => { cursors.clear(); if (readable.value) loadDirectory() }, { immediate: true, flush: 'sync' })
watch(() => writer.error.value, value => { if (value) accepted.value = false })
</script>
<template>
  <section class="growth-next-steps"><header><h2>下一步</h2><NButton :disabled="!readable || steps.busy.value" @click="loadSteps">重新读取建议</NButton></header><p class="field-help">建议来自当前真实来源。隐藏仅影响成长页建议；今日安排、来源状态与训练表现保持各自口径。</p>
    <NSkeleton v-if="steps.busy.value" text :repeat="3" /><p v-if="steps.error.value" class="notice error" role="alert">{{ g.growthErrorText(steps.error.value) }}</p>
    <template v-if="steps.data.value"><p class="field-help">{{ capabilityText(steps.data.value.capability) }} · 已核对 {{ steps.data.value.examinedCandidates }} 个候选 · 匹配但未展示 {{ countText(steps.data.value.matchingNotShown) }} 项{{ steps.data.value.scanLimited ? ' · 扫描达到上限' : '' }}</p><article v-for="item in steps.data.value.items" :key="g.subjectKey(item.subject)" class="next-row"><div><h3>{{ item.title }}</h3><p>{{ item.reasonCodes.map(reason=>g.nextStepReasonLabels[reason]).join(' · ') }}</p><p class="field-help">优先级 {{ item.effectivePriority }} · {{ {PLACEMENT:'来自实际安排',TARGET_REQUIREMENT:'来自目标要求',DEFAULT:'默认排序'}[item.priorityBasis] }}</p></div><div class="row-actions"><NButton size="small" @click="open(item.continuation)">{{ item.continuation.route === 'TARGET_REQUIREMENT' ? '打开目标版本' : '继续处理' }}</NButton><NButton size="small" :disabled="!readable || placementLocked" @click="adjustPriority(item)">调整优先级</NButton><NButton size="small" :disabled="writer.locked.value" @click="selectStep(item)">隐藏这项建议</NButton></div></article><p v-if="!steps.data.value.items.length" class="notice">{{ steps.data.value.capability.status !== 'READY' ? '当前建议来源尚未完整读取。' : steps.data.value.scanLimited ? '已核对候选中没有可展示建议；仍有来源未覆盖。' : '当前没有可展示建议，可在下方恢复已隐藏的偏好。' }}</p><details><summary>建议范围与限制</summary><ul><li v-for="limit in steps.data.value.limitations" :key="limit">{{ g.growthLimitationLabels[limit] }}</li></ul></details></template>
    <section v-if="prioritySelection" class="priority-editor" aria-labelledby="growth-priority-title">
      <header><h3 id="growth-priority-title">调整优先级 · {{ prioritySelection.title }}</h3><NButton size="small" :disabled="placementLocked" @click="closePriority">收起优先级设置</NButton></header>
      <template v-if="prioritySelection.kind === 'SOURCE'"><p class="notice">下方修改的是这个来源的今日安排优先级，也会影响今日与成长建议的排序。请核对安排内容后明确保存。</p><p class="field-help">本次安排编辑使用 {{ prioritySelection.localDate }} · {{ prioritySelection.zoneId }}。刷新建议会保留这份编辑输入与待确认请求。</p>
        <TodayPlacementEditor :key="`${prioritySelection.source.kind}:${prioritySelection.source.id}`" :source="prioritySelection.source" :zone-id="prioritySelection.zoneId" :local-date="prioritySelection.localDate" :disabled="!readable" @changed="placementChanged" @close="placementClosed" @open="openPlacementSource" @candidate="priorityMessage = '此入口尚未把来源加入本次建议候选。请到今日的安排管理中选择这个来源，再生成与采用建议。'" />
        <p v-if="priorityMessage" class="notice" role="status">{{ priorityMessage }}</p><NButton text :disabled="!readable || placementLocked" @click="openTodayPlans">打开今日安排管理</NButton>
      </template>
      <template v-else><p class="notice">目标要求的优先级需要在目标编辑器修改。保存会产生新的目标版本；已有证据关联与显示偏好继续保留在原版本，不会自动迁移。</p><p class="field-help">将打开这个目标的当前版本编辑器，请核对对应要求后调整优先级。原建议绑定的确切版本仍可通过“打开目标版本”查看。</p><NButton type="primary" :disabled="!readable || placementLocked || writer.locked.value" @click="openTargetPriority">打开当前目标，编辑要求优先级</NButton></template>
    </section>
    <section class="preference-directory"><header><h3>建议显示偏好</h3><div class="directory-filters"><NSelect v-model:value="visibility" :options="[{value:'HIDDEN',label:'已隐藏'},{value:'VISIBLE',label:'已恢复显示'}]" aria-label="偏好可见性" /><NSelect v-model:value="subjectKind" :options="[{value:'SOURCE',label:'来源事项'},{value:'TARGET_REQUIREMENT',label:'目标要求'}]" clearable placeholder="全部主体" aria-label="偏好主体" /></div></header><p class="field-help">目录保留旧目标与不可用来源的偏好。恢复显示会重新核对当前来源，不自动创建任务或恢复旧建议文案。</p>
      <NSkeleton v-if="directory.busy.value" text :repeat="2" /><p v-if="directory.error.value" class="notice error" role="alert">{{ g.growthErrorText(directory.error.value) }} <NButton text @click="loadDirectory()">从首页重读</NButton></p><article v-for="entry in directory.data.value?.items" :key="entry.preference.id" class="next-row"><div><h3>{{ entry.title }}</h3><p class="field-help">{{ g.directStateLabels[entry.subjectState] }} · {{ entry.preference.visibility === 'HIDDEN' ? '已隐藏' : '显示' }} · 修订 {{ entry.preference.revision }}</p></div><div class="row-actions"><NButton v-if="entry.continuation" size="small" @click="open(entry.continuation)">打开来源</NButton><NButton size="small" :disabled="writer.locked.value" @click="selectPreference(entry)">{{ entry.preference.visibility === 'HIDDEN' ? '核对并恢复显示' : '修改显示偏好' }}</NButton></div></article><p v-if="directory.data.value && !directory.data.value.items.length" class="notice">没有匹配的已保存偏好。</p><NButton v-if="directory.data.value?.nextCursor" :disabled="directory.busy.value" @click="loadDirectory(directory.data.value.nextCursor)">更多偏好</NButton>
    </section>
    <section v-if="selected" class="preference-editor"><header><h3>{{ selectedTitle }}</h3><NButton size="small" :disabled="writer.locked.value" @click="close">收起</NButton></header><p class="field-help">先按稳定主体身份读取当前偏好，再明确采用当前修订。读取不会创建设置。</p><div class="row-actions"><NButton :disabled="!readable || current.busy.value" @click="lookup">按主体读取当前偏好</NButton><NButton :disabled="!readable || !current.data.value || writer.locked.value" @click="acceptCurrent">采用当前修订，保留显示意图</NButton></div><p v-if="current.error.value" class="notice error" role="alert">{{ g.growthErrorText(current.error.value) }}</p><p v-if="current.data.value" class="notice">{{ current.data.value.entry ? `当前${current.data.value.entry.preference.visibility === 'HIDDEN' ? '隐藏' : '显示'}，修订 ${current.data.value.entry.preference.revision}；${g.directStateLabels[current.data.value.entry.subjectState]}` : '当前尚未设置偏好，按显示解释。' }}</p>
      <label>本次显示意图<NSelect v-model:value="desired" :options="[{value:'HIDDEN',label:'在成长建议中隐藏'},{value:'VISIBLE',label:'恢复显示，清除隐藏意图'}]" :disabled="!readable || writer.locked.value" /></label><p v-if="baseline && !baseline.currentSubject" class="field-help">来源当前不可用，允许恢复显示以清除隐藏意图；这不会使原来源重新可用。</p><p v-if="problem" class="notice error">{{ problem }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ g.growthErrorText(writer.error.value) }} 显示意图已保留。</p><p v-if="message" class="notice" role="status">{{ message }}</p>
      <div class="row-actions"><NButton v-if="writer.pending.value" type="primary" :disabled="!readable || writer.busy.value" :loading="writer.busy.value" @click="writer.retry">用原请求核对结果</NButton><NButton v-else type="primary" :disabled="!readable || !accepted || writer.locked.value" :loading="writer.busy.value" @click="save">{{ desired === 'VISIBLE' ? '确认恢复显示' : '确认隐藏建议' }}</NButton></div>
      <details v-if="baseline"><summary>偏好历史（已读取 {{ history.data.value?.items.length ?? 0 }} 条）</summary><NButton size="small" :disabled="!readable || history.busy.value" @click="loadHistory()">读取历史</NButton><p v-if="history.error.value" class="notice error">{{ g.growthErrorText(history.error.value) }}</p><p v-for="entry in history.data.value?.items" :key="entry.snapshot.revision" class="history-row">修订 {{ entry.snapshot.revision }} · {{ entry.snapshot.visibility === 'HIDDEN' ? '隐藏' : '显示' }} · {{ entry.createdAt }}</p><NButton v-if="history.data.value?.nextBefore != null" :disabled="!readable || history.busy.value" @click="loadHistory(history.data.value.nextBefore)">更早历史</NButton></details>
    </section>
  </section>
</template>
<style scoped>
.growth-next-steps,.preference-directory,.preference-editor,.priority-editor { display:grid; gap:18px; min-width:0; }header { display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap; }h2 { font-size:21px; }.next-row { display:flex; gap:18px; align-items:start; justify-content:space-between; padding:17px 0; border-bottom:1px solid var(--line); }.next-row>div:first-child { display:grid; gap:7px; min-width:0; }.next-row h3 { overflow-wrap:anywhere; }.row-actions,.directory-filters { display:flex; flex-wrap:wrap; gap:8px; }.row-actions { flex:none; }.directory-filters>* { width:145px; }.preference-directory { margin-top:15px; padding-top:22px; border-top:1px solid var(--line); }.priority-editor { padding:22px 0; border-top:1px solid var(--line); border-bottom:1px solid var(--line); }.priority-editor>button { justify-self:start; }.preference-editor { padding:22px; border:1px solid var(--line); border-radius:7px; background:var(--surface); }label { display:grid; gap:7px; max-width:370px; font-size:12px; color:var(--muted); }details { font-size:12px; }summary { cursor:pointer; margin-bottom:13px; }.history-row { padding:12px 0; border-bottom:1px solid var(--line); }@media(max-width:700px) { .next-row { flex-direction:column; }.preference-editor { padding:16px; } }
</style>
