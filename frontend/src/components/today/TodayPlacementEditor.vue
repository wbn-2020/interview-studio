<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { NButton, NInput } from 'naive-ui'
import { ApiError, read } from '../../api/client'
import type { TodayContinuation, TodayItem, TodaySourceRef } from '../../api/today'
import { todayErrorText, todaySourceKindLabels } from '../../api/today'
import type { CreatePlacement, Placement, PlacementContent, PlacementHistory, SavePlacement } from '../../api/todayPlan'
import { createdPlacementValid, createPlacementValid, placementChangeLabels, placementContentProblem, placementHistoryPageValid,
  placementHistoryPath, placementLookupPath, placementLookupValid, placementPath, savedPlacementValid, savePlacementValid, todayPlanCommandPaths } from '../../api/todayPlan'
import { useWorkspaceStore } from '../../state/workspace'
import TodayPlacementFields from './TodayPlacementFields.vue'
import { copy, readPlanSource, same, sourceWritable, usePlanCommand } from './planUi'

const props = defineProps<{ source: TodaySourceRef; zoneId: string; localDate: string; disabled: boolean }>()
const emit = defineEmits<{ close: []; changed: []; open: [value: TodayContinuation]; candidate: [source: TodaySourceRef] }>()
const store = useWorkspaceStore(), readable = computed(() => store.sessionActive && !props.disabled)
const defaults = (): PlacementContent => ({ plannedDate: null, planZoneId: null, estimatedMinutes: null, priority: 3, orderValue: 500000, dateLocked: false, visibility: 'VISIBLE', suppressedOn: null })
const content = (value: Placement | null): PlacementContent => value ? { plannedDate: value.plannedDate, planZoneId: value.planZoneId, estimatedMinutes: value.estimatedMinutes,
  priority: value.priority, orderValue: value.orderValue, dateLocked: value.dateLocked, visibility: value.visibility, suppressedOn: copy(value.suppressedOn) } : defaults()
const current = shallowRef<TodayItem>(), latest = shallowRef<TodayItem>(), draft = ref<PlacementContent>(defaults()), baseline = shallowRef<PlacementContent>(defaults())
const reason = ref(''), checked = ref(false), busy = ref(false), error = shallowRef<unknown>(), message = ref('')
const history = shallowRef<PlacementHistory[]>([]), nextBefore = ref<number | null>(null), historyBusy = ref(false), historyError = shallowRef<unknown>()
const dirty = computed(() => !same(draft.value, baseline.value) || Boolean(reason.value))
const writer = usePlanCommand(readable, 'today-placement-editor', dirty)
const editable = computed(() => readable.value && checked.value && !!current.value && sourceWritable(current.value) && !writer.locked.value && !busy.value)
const problem = computed(() => current.value ? placementContentProblem(draft.value, current.value.source.kind) : '')
let alive = true, generation = 0, historyGeneration = 0
watch([readable, () => store.sessionEpoch, () => props.zoneId], () => { generation++; historyGeneration++; latest.value = undefined; checked.value = false; busy.value = false; historyBusy.value = false; if (readable.value) void load() }, { immediate: true, flush: 'sync' })
watch(() => writer.error.value, value => { if (value) checked.value = false })
onUnmounted(() => { alive = false; generation++; historyGeneration++ })
function isCurrent(epoch: number, request: number) { return alive && readable.value && store.sessionEpoch === epoch && request === generation }
async function load() {
  if (!readable.value || writer.locked.value || busy.value) return
  const request = ++generation, epoch = store.sessionEpoch
  busy.value = true; error.value = undefined; checked.value = false; latest.value = undefined
  try {
    const [item, lookup] = await Promise.all([readPlanSource(props.source, props.zoneId), read<unknown>(placementLookupPath(props.source))])
    if (!isCurrent(epoch, request)) return
    if (!placementLookupValid(lookup, props.source) || !same(lookup.placement, item.placement)) throw new ApiError(409, 'PLAN_SOURCE_CHANGED', '来源或安排在读取期间发生变化，请重新核对。')
    latest.value = item
    if (!current.value || !dirty.value) accept(false)
    else message.value = '已读取最新来源，当前输入保留。请明确选择沿用输入或重新载入。'
  } catch (value) { if (isCurrent(epoch, request)) error.value = value }
  finally { if (isCurrent(epoch, request)) busy.value = false }
}
function accept(keepInput: boolean) {
  if (!readable.value || !latest.value || writer.locked.value) return
  current.value = copy(latest.value); baseline.value = content(latest.value.placement)
  if (!keepInput) { draft.value = copy(baseline.value); reason.value = '' }
  checked.value = true; latest.value = undefined; message.value = ''
  historyGeneration++; historyBusy.value = false; history.value = []; nextBefore.value = null; historyError.value = undefined; void loadHistory()
}
async function loadHistory(before?: number) {
  if (!readable.value || !current.value?.placement || historyBusy.value) return
  const request = ++historyGeneration, epoch = store.sessionEpoch, id = current.value.placement.id
  historyBusy.value = true; historyError.value = undefined
  try {
    const value = await read<unknown>(placementHistoryPath(id, { before, limit: 20 }))
    if (!alive || !readable.value || epoch !== store.sessionEpoch || request !== historyGeneration || current.value?.placement?.id !== id) return
    if (!placementHistoryPageValid(value, id, props.source, before, 20)) throw new ApiError(0, 'TODAY_RESPONSE_INVALID', '安排历史无法核对。')
    history.value = before === undefined ? value.items : [...history.value, ...value.items]
    nextBefore.value = value.nextBefore
  } catch (value) { if (alive && epoch === store.sessionEpoch && request === historyGeneration) historyError.value = value }
  finally { if (alive && request === historyGeneration) historyBusy.value = false }
}
function save() {
  if (!editable.value || !current.value || problem.value) return
  const item = copy(current.value), proposed = copy(draft.value), existing = item.placement
  const fields = { expectedSource: item.stamp, content: proposed, reason: reason.value.trim() || null }
  const received = () => {
    baseline.value = copy(proposed); reason.value = ''; checked.value = false; message.value = '保存回执已确认，正在读取当前来源。'; emit('changed'); void load()
  }
  if (existing) {
    const body: SavePlacement = { expectedRevision: existing.revision, ...fields }
    if (!savePlacementValid(body, item.source)) { error.value = new ApiError(400, 'INVALID_REQUEST', '请核对安排内容与修改说明。'); return }
    writer.run(placementPath(existing.id), 'PUT', body, (value): value is Placement => savedPlacementValid(value, existing.id, item.source, body, existing), received)
  } else {
    const body: CreatePlacement = { source: item.source, ...fields }
    if (!createPlacementValid(body)) { error.value = new ApiError(400, 'INVALID_REQUEST', '请核对安排内容与修改说明。'); return }
    writer.run(todayPlanCommandPaths.createPlacement, 'POST', body, (value): value is Placement => createdPlacementValid(value, body), received)
  }
}
function close() { if (store.canLeave(['today-placement-editor'])) emit('close') }
function restoreDefaults() { if (editable.value) { draft.value = defaults(); message.value = '已填入自动安排默认值，保存后生效；历史记录继续保留。' } }
</script>

<template>
  <section class="placement-editor" aria-labelledby="placement-editor-title"><header><div><p class="field-help">{{ todaySourceKindLabels[source.kind] }}</p><h2 id="placement-editor-title">{{ current?.title ?? '核对来源与安排偏好' }}</h2></div><NButton text :disabled="writer.locked.value" @click="close">收起</NButton></header>
    <p class="field-help">偏好只调整推荐与显示，不改变来源状态、原任务日期、回忆到期或面试时间。</p><p v-if="busy" role="status" class="field-help">正在重新读取来源和偏好…</p><p v-if="error" role="alert" class="notice error">{{ todayErrorText(error) }}</p><p v-if="message" class="field-help" role="status">{{ message }}</p>
    <div class="row-actions"><NButton size="small" :disabled="!readable || busy || writer.locked.value" @click="load">重新核对来源</NButton><NButton v-if="current" text size="small" :disabled="!readable || writer.locked.value" @click="emit('open', current.continuation)">打开来源</NButton></div>
    <div v-if="latest && dirty" class="notice"><p>最新状态已读取。保留输入会换用这次读取的来源与偏好修订，之后仍由服务器核对。</p><div class="row-actions"><NButton :disabled="!readable || busy || writer.locked.value" @click="accept(true)">保留输入，使用最新基线</NButton><NButton :disabled="!readable || busy || writer.locked.value" @click="accept(false)">放弃输入，载入最新值</NButton></div></div>
    <template v-if="current"><p v-if="!sourceWritable(current)" class="notice">该来源已归档、结束或没有待处理工作。仍可查看历史和原详情，不能修改或恢复安排。</p><p class="field-help">{{ current.placement ? `已保存偏好 · 修订 ${current.placement.revision}` : '尚无已保存偏好。下方默认值只在明确保存后生效。' }}</p>
      <p v-if="readable && current.placement" class="field-help placement-identity">偏好 ID {{ current.placement.id }}</p>
      <TodayPlacementFields v-model="draft" :item="current" :disabled="!editable" :local-date="localDate" :zone-id="zoneId" />
      <label>修改说明（可选）<NInput v-model:value="reason" type="textarea" :maxlength="2000" :disabled="!editable" :autosize="{ minRows: 2, maxRows: 5 }" /></label><p v-if="problem" class="notice error" role="alert">{{ problem }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ todayErrorText(writer.error.value) }}</p>
      <div class="row-actions"><NButton v-if="writer.pending.value && !writer.busy.value" type="primary" :disabled="!readable" @click="writer.retry">用原请求核对结果</NButton><NButton v-else type="primary" :loading="writer.busy.value" :disabled="!editable || !!problem" @click="save">保存安排偏好</NButton><NButton :disabled="!editable" @click="restoreDefaults">恢复自动安排默认值</NButton><NButton :disabled="!readable || writer.locked.value" @click="emit('candidate', source)">加入本次建议候选</NButton></div>
      <details class="placement-history"><summary>安排历史（{{ history.length }} 条已读取）</summary>
        <p v-if="historyBusy" class="field-help" role="status">正在读取安排历史…</p><p v-if="historyError" class="notice error">{{ todayErrorText(historyError) }}</p><p v-if="!current.placement" class="field-help">尚未保存安排，没有历史记录。</p>
        <article v-for="entry in history" :key="entry.snapshot.revision"><strong>修订 {{ entry.snapshot.revision }} · {{ placementChangeLabels[entry.change] }}</strong><span>{{ entry.createdAt }} · 顺序 {{ entry.snapshot.orderValue }} · 优先级 {{ entry.snapshot.priority }}</span><span>{{ entry.snapshot.plannedDate ?? '沿用来源日期或未安排' }}{{ entry.snapshot.planZoneId ? ` · ${entry.snapshot.planZoneId}` : '' }} · {{ entry.snapshot.visibility === 'PAUSED' ? '安排暂停' : '正常推荐' }} · {{ entry.snapshot.dateLocked ? '日期已锁定' : '未锁定日期' }}</span><span>{{ entry.snapshot.estimatedMinutes === null ? '未单独保存估时' : `已保存估时 ${entry.snapshot.estimatedMinutes} 分钟` }}</span><span v-if="entry.snapshot.suppressedOn">暂不推荐 {{ entry.snapshot.suppressedOn.localDate }} · {{ entry.snapshot.suppressedOn.zoneId }}</span><p v-if="entry.reason">{{ entry.reason }}</p></article>
        <div class="row-actions"><NButton v-if="current.placement" size="small" :disabled="!readable || historyBusy" @click="loadHistory()">刷新历史</NButton><NButton v-if="nextBefore !== null" size="small" :loading="historyBusy" :disabled="!readable || historyBusy" @click="loadHistory(nextBefore ?? undefined)">更早记录</NButton></div>
      </details>
    </template>
  </section>
</template>

<style scoped>
.placement-editor { display: grid; gap: 15px; min-width: 0; padding: 22px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface); }header { display: flex; align-items: start; justify-content: space-between; gap: 15px; }h2 { font-size: 18px; margin: 5px 0; overflow-wrap: anywhere; }label { display: grid; gap: 7px; color: var(--muted); font-size: 12px; }.placement-history { border-top: 1px solid var(--line); padding-top: 15px; font-size: 12px; }.placement-history summary { cursor: pointer; margin-bottom: 12px; }.placement-history article { display: grid; gap: 6px; padding: 13px 0; border-top: 1px solid var(--line); overflow-wrap: anywhere; }.placement-history span { font-size: 11px; color: var(--muted); }
.placement-identity { overflow-wrap: anywhere; }
</style>
