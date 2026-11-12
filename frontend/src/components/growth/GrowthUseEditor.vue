<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCheckbox, NInput, NSelect, NSkeleton } from 'naive-ui'
import { preview as postPreview, read } from '../../api/client'
import * as g from '../../api/growth'
import { useWorkspaceStore } from '../../state/workspace'
import { copy, evidenceRoute, invalidGrowth, reviewedEvidenceRows, reviewedEvidenceStatus, useGrowthCommand, useGrowthRead } from './growthUi'
import GrowthTargetPicker from './GrowthTargetPicker.vue'

const props = defineProps<{ disabled: boolean; intent: { sequence: number; evidence?: g.EvidenceRef; entry?: g.UseEntry; target?: g.TargetRef; requirementId?: string } }>()
const emit = defineEmits<{ changed: []; close: [] }>()
const store = useWorkspaceStore(), router = useRouter(), readable = computed(() => store.sessionActive && !props.disabled)
const target = shallowRef<g.TargetRef>(), evidence = shallowRef<g.EvidenceRef>(), requirementId = ref<string | null>(null), picker = ref(false)
const existing = shallowRef<g.UseEntry | null>(null), latest = shallowRef<g.UseEntry | null>(), lookupChecked = ref(false), lookupBusy = ref(false), lookupError = shallowRef<unknown>()
const preview = shallowRef<g.UsePreview>(), previewBusy = ref(false), previewError = shallowRef<unknown>(), confirmed = ref(false), message = ref(''), problem = ref('')
const pairs = [{ key: 'dimension', label: '考察项' }, { key: 'scenario', label: '场景' }, { key: 'technology', label: '技术与版本' }, { key: 'assistance', label: '辅助条件' }, { key: 'date', label: '日期' }] as const
function emptyComparison(): g.Comparability { const item = () => ({ state: 'UNKNOWN' as const, reason: '' }); return { dimension: item(), scenario: item(), technology: item(), assistance: item(), date: item() } }
const draft = reactive<{ coverage: g.Coverage; status: g.UseStatus; reason: string; comparability: g.Comparability }>({ coverage: 'CONTEXT_ONLY', status: 'ACTIVE', reason: '', comparability: emptyComparison() })
const baseline = ref(''), dirty = computed(() => JSON.stringify(draft) !== baseline.value)
const writer = useGrowthCommand(readable, 'growth-evidence-use', dirty)
const scope = computed(() => JSON.stringify([props.intent.sequence, target.value])), targetCoverage = useGrowthRead<g.TargetCoverage>(readable, scope)
const historyScope = computed(() => JSON.stringify([props.intent.sequence, existing.value?.use.id])), history = useGrowthRead<g.UseHistoryPage>(readable, historyScope)
const identity = computed<g.CreateUsePreview | undefined>(() => target.value && evidence.value && requirementId.value ? { target: target.value, evidence: evidence.value, requirementId: requirementId.value } : undefined)
const identityScope = computed(() => JSON.stringify(identity.value))
const reviewedSummary = computed(() => {
  const value = preview.value?.evidence.summary ?? latest.value?.evidence.summary
  return value?.conditions.kind === 'REVIEWED_OBSERVATION' ? value : undefined
})
let alive = true, generation = 0, lookupSequence = 0, previewSequence = 0
function invalidate() { generation++; lookupSequence++; previewSequence++; preview.value = undefined; latest.value = undefined; lookupChecked.value = false; lookupBusy.value = false; previewBusy.value = false; confirmed.value = false }
watch([readable, () => store.sessionEpoch], invalidate, { flush: 'sync' })
watch(identityScope, () => { invalidate(); lookupError.value = undefined; previewError.value = undefined }, { flush: 'sync' })
watch(() => props.intent.sequence, () => {
  const entry = props.intent.entry
  target.value = copy(entry?.use.target ?? props.intent.target); evidence.value = copy(entry?.use.evidence ?? props.intent.evidence)
  requirementId.value = entry?.use.requirementId ?? props.intent.requirementId ?? null; existing.value = entry ? copy(entry) : null
  draft.coverage = entry?.use.coverage ?? 'CONTEXT_ONLY'; draft.status = entry?.use.status ?? 'ACTIVE'; draft.reason = entry?.use.reason ?? ''; draft.comparability = entry ? copy(entry.use.comparability) : emptyComparison()
  baseline.value = JSON.stringify(draft); message.value = ''; problem.value = ''; picker.value = !target.value; invalidate()
  if (readable.value) { loadTarget(); if (identity.value) void lookup(); if (entry) loadHistory() }
}, { immediate: true })
watch([readable, () => store.sessionEpoch], () => { if (readable.value) { loadTarget(); if (identity.value) void lookup() } }, { flush: 'sync' })
watch(target, () => { if (readable.value) loadTarget() })
watch(() => writer.error.value, value => { if (value) { preview.value = undefined; confirmed.value = false } })
onUnmounted(() => { alive = false; invalidate() })
function current(epoch: number, stamp: number) { return alive && readable.value && epoch === store.sessionEpoch && stamp === generation }
function loadTarget() { const selected = target.value; if (selected) void targetCoverage.load(g.growthTargetCoveragePath(selected), (v): v is g.TargetCoverage => g.targetCoverageValid(v, selected)) }
function chooseTarget(value: { ref: g.TargetRef }) { if (writer.locked.value || existing.value) return; target.value = copy(value.ref); requirementId.value = null; picker.value = false }
async function lookup() {
  if (!readable.value || !identity.value || lookupBusy.value) return
  const epoch = store.sessionEpoch, stamp = generation, request = ++lookupSequence, selected = copy(identity.value), id = existing.value?.use.id
  lookupBusy.value = true; lookupError.value = undefined; latest.value = undefined; lookupChecked.value = false
  try {
    // 独立 GET 显示当前事实，即使写请求结果未知也不丢掉原请求或把当前值当成原回执。
    const value = await read<unknown>(id ? g.growthUsePath(id) : g.growthUseIdentityPath(selected))
    if (!current(epoch, stamp) || request !== lookupSequence) return
    if (id) { if (!g.useLookupValid(value, id)) throw invalidGrowth(); latest.value = value.entry }
    else { if (!g.useIdentityLookupValid(value, selected)) throw invalidGrowth(); latest.value = value.entry }
    lookupChecked.value = true
  } catch (reason) { if (current(epoch, stamp) && request === lookupSequence) lookupError.value = reason }
  finally { if (current(epoch, stamp) && request === lookupSequence) lookupBusy.value = false }
}
function acceptLatest(keepInput: boolean) {
  if (!readable.value || !lookupChecked.value || latest.value === undefined || writer.locked.value) return
  existing.value = latest.value ? copy(latest.value) : null
  if (!keepInput && latest.value) { draft.coverage = latest.value.use.coverage; draft.status = latest.value.use.status; draft.reason = latest.value.use.reason; draft.comparability = copy(latest.value.use.comparability); baseline.value = JSON.stringify(draft) }
  preview.value = undefined; confirmed.value = false; message.value = '已明确采用当前修订，请重新预览再提交。'; history.reset(); if (existing.value) loadHistory()
}
async function makePreview() {
  if (!readable.value || writer.locked.value || previewBusy.value || !identity.value) return
  problem.value = ''
  if (!lookupChecked.value || latest.value === undefined) { problem.value = '请先按唯一身份读取当前关联。'; return }
  if (latest.value?.use.id !== existing.value?.use.id || latest.value?.use.revision !== existing.value?.use.revision) { problem.value = '当前关联与编辑基线不同，请明确采用当前修订，输入可保留。'; return }
  const epoch = store.sessionEpoch, stamp = generation, request = ++previewSequence, previous = existing.value ? copy(existing.value.use) : undefined
  const body: g.CreateUsePreview | g.ChangeUsePreview = previous ? { expectedRevision: previous.revision } : copy(identity.value)
  previewBusy.value = true; previewError.value = undefined; preview.value = undefined; confirmed.value = false
  try {
    const value = await postPreview<unknown>(g.growthUsePreviewPath(previous?.id), body)
    if (!current(epoch, stamp) || request !== previewSequence) return
    if (!g.usePreviewValid(value, body, previous)) throw invalidGrowth()
    preview.value = value
    // 初次空表单可采用机器比较起点；重新预览不得覆盖用户已经填写的五项比较。
    if (!dirty.value && !existing.value) { draft.comparability = copy(value.comparability); baseline.value = JSON.stringify(draft) }
  } catch (reason) { if (current(epoch, stamp) && request === previewSequence) previewError.value = reason }
  finally { if (current(epoch, stamp) && request === previewSequence) previewBusy.value = false }
}
function save() {
  if (!readable.value || !preview.value || !confirmed.value || writer.locked.value || !identity.value) return
  const selected = copy(preview.value), previous = existing.value ? copy(existing.value.use) : undefined
  const content = { expectedPreviewHash: selected.previewHash, coverage: draft.coverage, comparability: copy(draft.comparability), reason: draft.reason, confirmed: true as const }
  const received = (_value: g.EvidenceUse) => { preview.value = undefined; confirmed.value = false; baseline.value = JSON.stringify(draft); message.value = '原请求保存回执已核对。请读取并确认当前关联状态，来源可能已继续变化。'; void lookup(); emit('changed') }
  if (previous) {
    const body: g.ChangeUse = { ...content, expectedRevision: previous.revision, status: draft.status }
    if (!g.changeUseValid(body, selected)) { problem.value = '请填写有效理由；支持或部分适用须说明五项对照。旧目标或不可用来源仅允许契约支持的撤回／非正向操作。'; return }
    writer.run(g.growthUsePath(previous.id), 'PUT', body, (v): v is g.EvidenceUse => g.savedEvidenceUseValid(v, previous.id, body, previous, selected), received)
  } else {
    const body: g.CreateUse = { ...content, request: copy(identity.value) }
    if (!g.createUseValid(body, selected)) { problem.value = '请填写有效理由；支持或部分适用须说明五项对照，且来源需当前可用。'; return }
    writer.run(g.growthCommandPaths.createUse, 'POST', body, (v): v is g.EvidenceUse => g.createdEvidenceUseValid(v, body, selected), received)
  }
}
function loadHistory(before?: number) {
  const id = existing.value?.use.id; if (!id || history.busy.value) return
  const query = { limit: 20, ...(before === undefined ? {} : { before }) }
  void history.load(g.growthUseHistoryPath(id, query), (v): v is g.UseHistoryPage => g.useHistoryPageValid(v, id, query), before === undefined ? undefined : (previous, next) => ({ ...next, items: [...(previous?.items ?? []), ...next.items] }))
}
function openSource() { const value = preview.value?.evidence.summary ?? latest.value?.evidence.summary; if (readable.value && value) void router.push(evidenceRoute(value)) }
</script>

<template>
  <section class="growth-use-editor" aria-labelledby="growth-use-title"><header><div><h2 id="growth-use-title">{{ existing ? '核对与修改证据关联' : '关联一个目标要求' }}</h2><p class="field-help">保存的是你的适用性判断；系统不据此认证能力或补记训练次数。</p></div><NButton :disabled="writer.locked.value" @click="emit('close')">收起</NButton></header>
    <p v-if="evidence" class="identity">{{ g.evidenceKey(evidence) }}</p>
    <p v-if="evidence?.kind === 'REVIEWED_OBSERVATION'" class="field-help">此关联固定到观察修订 {{ evidence.revision }}，不会随观察或标准后续修订自动替换。是否适用于目标由你逐项说明；受审观察不会自动成为支持标注。</p>
    <div class="identity-fields"><label>确切目标版本<NButton :disabled="!readable || writer.locked.value || !!existing" @click="picker = !picker">{{ targetCoverage.data.value?.target.title ?? (target ? '读取所选目标版本' : '选择目标与版本') }}</NButton></label><label>目标要求<NSelect v-model:value="requirementId" :options="targetCoverage.data.value?.requirements.map(row=>({value:row.requirement.id,label:row.requirement.text})) ?? []" :disabled="!readable || writer.locked.value || !!existing" placeholder="选择一个明确要求" /></label></div>
    <GrowthTargetPicker v-if="picker" :disabled="!readable || writer.locked.value" @choose="chooseTarget" />
    <p v-if="targetCoverage.error.value" class="notice error" role="alert">{{ g.growthErrorText(targetCoverage.error.value) }}</p>
    <div class="row-actions"><NButton :disabled="!readable || !identity || lookupBusy" :loading="lookupBusy" @click="lookup">按唯一身份读取当前关联</NButton><NButton :disabled="!readable || !identity || !lookupChecked || writer.locked.value || previewBusy" :loading="previewBusy" @click="makePreview">重新预览适用条件</NButton></div>
    <p v-if="lookupError || previewError" class="notice error" role="alert">{{ g.growthErrorText(lookupError ?? previewError) }}</p>
    <div v-if="lookupChecked && latest !== undefined" class="current-read"><p v-if="latest">当前关联：{{ g.coverageLabels[latest.use.coverage] }} · {{ g.effectiveUseStateLabels[latest.effectiveState] }} · 修订 {{ latest.use.revision }}</p><p v-else>这个确切目标要求与证据身份尚未保存关联。</p><div v-if="latest" class="row-actions"><NButton size="small" :disabled="writer.locked.value || !readable" @click="acceptLatest(true)">保留输入，采用当前修订</NButton><NButton size="small" :disabled="writer.locked.value || !readable" @click="acceptLatest(false)">载入当前保存内容</NButton></div></div>
    <NSkeleton v-if="previewBusy" text :repeat="3" />
    <section v-if="reviewedSummary" class="reviewed-context" aria-label="所选受审观察的条件元数据"><h3>有限条件观察 · 修订 {{ reviewedSummary.ref.revision }}</h3><p>{{ reviewedEvidenceStatus(reviewedSummary) }}</p><p class="field-help">上方资格来自本次直接受审链读取，间接依赖仍为部分检查。这里仅显示身份与条件记录情况，完整观察和引文需打开确切来源。</p><dl class="reviewed-metadata"><template v-for="row in reviewedEvidenceRows(reviewedSummary)" :key="row.label"><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></template></dl><NButton text size="small" :disabled="!readable" @click="openSource">打开确切观察修订</NButton></section>
    <template v-if="preview"><div class="preview-context"><h3>{{ preview.requirement.text }}</h3><p>{{ preview.target.title }} · {{ preview.target.currentVersionId === preview.target.ref.versionId && preview.target.status === 'ACTIVE' ? '当前活跃版本' : '历史或归档版本' }}</p><p>{{ g.directStateLabels[preview.evidence.state.directState] }}{{ preview.evidence.state.recordCurrency === 'SUPERSEDED' ? ' · 已有后续记录' : '' }}</p><NButton v-if="preview.evidence.summary" text @click="openSource">查看确切来源记录</NButton><ul><li v-for="limit in preview.limitations" :key="limit">{{ g.growthLimitationLabels[limit] }}</li></ul></div></template>
    <form class="use-form" @submit.prevent="save"><div class="identity-fields"><label>个人适用性标注<NSelect v-model:value="draft.coverage" :options="Object.entries(g.coverageLabels).map(([value,label])=>({value,label}))" :disabled="!readable || writer.locked.value" /></label><label v-if="existing">关联状态<NSelect v-model:value="draft.status" :options="[{value:'ACTIVE',label:existing.use.status==='WITHDRAWN'?'恢复为活跃关联':'保持活跃关联'},{value:'WITHDRAWN',label:'撤回关联'}]" :disabled="!readable || writer.locked.value" /></label></div>
      <p class="field-help">五项比较可以保留“未知”。选择支持或部分适用时，每一项都需写明理由；不同与未知条件不会被系统自动推断为等价。</p>
      <fieldset v-for="pair in pairs" :key="pair.key" class="comparison-field"><legend>{{ pair.label }}</legend><p v-if="preview" class="field-help">预览起点：{{ {MATCH:'一致',DIFFERENT:'不同',UNKNOWN:'未知'}[preview.comparability[pair.key].state] }} · {{ preview.comparability[pair.key].reason || '无自动判断理由' }}</p><NSelect v-model:value="draft.comparability[pair.key].state" :options="[{value:'MATCH',label:'一致'},{value:'DIFFERENT',label:'不同'},{value:'UNKNOWN',label:'未知'}]" :disabled="!readable || writer.locked.value" :aria-label="`${pair.label}比较结果`" /><NInput v-model:value="draft.comparability[pair.key].reason" type="textarea" :maxlength="500" :autosize="{minRows:2,maxRows:5}" :disabled="!readable || writer.locked.value" :placeholder="`说明${pair.label}的比较依据与未知条件`" :aria-label="`${pair.label}比较理由`" /></fieldset>
      <label>本次判断或撤回理由<NInput v-model:value="draft.reason" type="textarea" :maxlength="2000" :autosize="{minRows:3,maxRows:7}" :disabled="!readable || writer.locked.value" placeholder="说明这份确切证据如何适用于这个要求，以及仍需验证什么" /></label>
      <NCheckbox v-model:checked="confirmed" :disabled="!readable || !preview || writer.locked.value">我已核对本次预览、五项比较及要保存的状态</NCheckbox>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ g.growthErrorText(writer.error.value) }} 输入保留；请重新读取并明确采用当前修订后再预览。</p><p v-if="message" class="notice" role="status">{{ message }}</p>
      <div class="row-actions"><NButton v-if="writer.pending.value" type="primary" :disabled="!readable || writer.busy.value" :loading="writer.busy.value" @click="writer.retry">用原请求核对保存结果</NButton><NButton v-else attr-type="submit" type="primary" :disabled="!readable || !preview || !confirmed || writer.locked.value" :loading="writer.busy.value">{{ draft.status === 'WITHDRAWN' ? '确认撤回关联' : existing?.use.status === 'WITHDRAWN' ? '确认恢复关联' : '确认保存关联' }}</NButton></div>
    </form>
    <details v-if="existing"><summary>不可变关联历史（已读取 {{ history.data.value?.items.length ?? 0 }} 条）</summary><NButton size="small" :disabled="!readable || history.busy.value" @click="loadHistory()">读取历史</NButton><p v-if="history.error.value" class="notice error">{{ g.growthErrorText(history.error.value) }}</p><article v-for="entry in history.data.value?.items" :key="entry.snapshot.revision" class="history-row"><h3>修订 {{ entry.snapshot.revision }} · {{ {CREATED:'创建',UPDATED:'修改',WITHDRAWN:'撤回',RESTORED:'恢复'}[entry.change] }}</h3><p>{{ g.coverageLabels[entry.snapshot.coverage] }} · {{ entry.createdAt }}</p><p class="preserve">{{ entry.snapshot.reason }}</p><ul><li v-for="pair in pairs" :key="pair.key">{{ pair.label }}：{{ {MATCH:'一致',DIFFERENT:'不同',UNKNOWN:'未知'}[entry.snapshot.comparability[pair.key].state] }} · {{ entry.snapshot.comparability[pair.key].reason }}</li></ul></article><NButton v-if="history.data.value?.nextBefore != null" :disabled="!readable || history.busy.value" @click="loadHistory(history.data.value.nextBefore)">更早历史</NButton></details>
  </section>
</template>
<style scoped>
.reviewed-context { display:grid; gap:12px; padding:16px 0; border-top:1px solid var(--line); border-bottom:1px solid var(--line); min-width:0; }.reviewed-context h3 { font-size:15px; }.reviewed-context>.n-button { justify-self:start; }.reviewed-metadata { display:grid; grid-template-columns:120px minmax(0,1fr); gap:8px 12px; margin:0; font-size:12px; }.reviewed-metadata dt { color:var(--muted); }.reviewed-metadata dd { margin:0; overflow-wrap:anywhere; line-height:1.6; }@media(max-width:650px) { .reviewed-metadata { grid-template-columns:1fr; gap:4px; }.reviewed-metadata dd { margin-bottom:8px; } }
.growth-use-editor { display:grid; gap:20px; padding:24px; border:1px solid var(--line); border-radius:7px; background:var(--surface); min-width:0; }header { display:flex; gap:18px; align-items:start; justify-content:space-between; }h2 { font-size:21px; }.identity-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:15px; }label { display:grid; gap:8px; font-size:12px; color:var(--muted); }.use-form { display:grid; gap:18px; }.comparison-field { margin:0; padding:14px; display:grid; grid-template-columns:140px minmax(0,1fr); gap:10px; border:1px solid var(--line); border-radius:5px; }.comparison-field>p { grid-column:1/-1; }legend { padding:0 6px; font-size:13px; font-weight:600; }.preview-context,.current-read { display:grid; gap:10px; background:var(--surface-soft); border-radius:5px; padding:16px; }.preview-context>button { justify-self:start; }.row-actions { display:flex; flex-wrap:wrap; gap:9px; }.identity { overflow-wrap:anywhere; font-family:Consolas,monospace; font-size:12px; color:var(--muted); }details { font-size:12px; }summary { cursor:pointer; margin-bottom:13px; }.history-row { display:grid; gap:8px; padding:16px 0; border-bottom:1px solid var(--line); }.preserve { white-space:pre-wrap; overflow-wrap:anywhere; }@media(max-width:650px) { .growth-use-editor { padding:17px; }.identity-fields,.comparison-field { grid-template-columns:1fr; }header { flex-wrap:wrap; } }
</style>
