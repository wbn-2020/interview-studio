<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NInput, NInputNumber } from 'naive-ui'
import { ApiError, read } from '../../api/client'
import { todayDateValid, todayErrorText, todayZoneValid } from '../../api/today'
import type { BudgetHistory, BudgetLookup, CreateBudget, DayBudget, SaveBudget } from '../../api/todayPlan'
import { budgetChangeLabels, budgetHistoryPageValid, budgetHistoryPath, budgetLookupPath, budgetLookupValid, budgetPath,
  createdBudgetValid, createBudgetValid, savedBudgetValid, saveBudgetValid, todayPlanCommandPaths } from '../../api/todayPlan'
import { useWorkspaceStore } from '../../state/workspace'
import { copy, usePlanCommand } from './planUi'

const props = defineProps<{ localDate: string; zoneId: string; disabled: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const store = useWorkspaceStore(), readable = computed(() => store.sessionActive && !props.disabled)
const input = reactive({ localDate: props.localDate, zoneId: props.zoneId }), scope = shallowRef({ ...input })
const budget = shallowRef<DayBudget | null>(null), latest = shallowRef<BudgetLookup>(), minutes = ref<number | null>(null)
const checked = ref(false), busy = ref(false), error = shallowRef<unknown>(), problem = ref(''), message = ref('')
const history = shallowRef<BudgetHistory[]>([]), nextBefore = ref<number | null>(null), historyBusy = ref(false), historyError = shallowRef<unknown>()
const dirty = computed(() => minutes.value !== (budget.value?.availableMinutes ?? null))
const writer = usePlanCommand(readable, 'today-day-budget', dirty)
let alive = true, generation = 0, historyGeneration = 0
watch([readable, () => store.sessionEpoch], () => { generation++; historyGeneration++; latest.value = undefined; checked.value = false; busy.value = false; historyBusy.value = false; if (readable.value) void load() }, { immediate: true, flush: 'sync' })
watch(() => writer.error.value, value => { if (value) checked.value = false })
onUnmounted(() => { alive = false; generation++; historyGeneration++ })
function current(epoch: number, request: number) { return alive && readable.value && epoch === store.sessionEpoch && request === generation }
async function load() {
  if (!readable.value || busy.value || writer.locked.value) return
  const epoch = store.sessionEpoch, request = ++generation, selected = copy(scope.value)
  busy.value = true; error.value = undefined; checked.value = false; latest.value = undefined
  try {
    const value = await read<unknown>(budgetLookupPath(selected))
    if (!current(epoch, request)) return
    if (!budgetLookupValid(value, selected.localDate, selected.zoneId)) throw new ApiError(0, 'TODAY_RESPONSE_INVALID', '预算读取无法核对。')
    latest.value = value
    if (!dirty.value) accept(false)
  } catch (value) { if (current(epoch, request)) error.value = value }
  finally { if (current(epoch, request)) busy.value = false }
}
function accept(keepInput: boolean) {
  if (!readable.value || !latest.value || writer.locked.value) return
  budget.value = copy(latest.value.budget)
  if (!keepInput) minutes.value = budget.value?.availableMinutes ?? null
  checked.value = true; latest.value = undefined
  historyGeneration++; historyBusy.value = false; history.value = []; nextBefore.value = null; historyError.value = undefined; void loadHistory()
}
function applyScope() {
  if (!readable.value || busy.value || writer.locked.value) return
  problem.value = !todayDateValid(input.localDate) || !todayZoneValid(input.zoneId) ? '请填写有效日期与 IANA 时区或 UTC。' : ''
  if (problem.value || !store.canLeave(['today-day-budget'])) return
  // 日期与区域共同定位预算，旧范围的迟到历史不能进入新编辑器。
  generation++; historyGeneration++; historyBusy.value = false; checked.value = false
  scope.value = { ...input }; budget.value = null; minutes.value = null; latest.value = undefined; history.value = []; nextBefore.value = null; historyError.value = undefined; message.value = ''; void load()
}
async function loadHistory(before?: number) {
  if (!readable.value || !budget.value || historyBusy.value) return
  const epoch = store.sessionEpoch, request = ++historyGeneration, selected = copy(scope.value)
  historyBusy.value = true; historyError.value = undefined
  try {
    const value = await read<unknown>(budgetHistoryPath(selected.localDate, { zoneId: selected.zoneId, before, limit: 20 }))
    if (!alive || !readable.value || epoch !== store.sessionEpoch || request !== historyGeneration) return
    if (!budgetHistoryPageValid(value, selected, before, 20)) throw new ApiError(0, 'TODAY_RESPONSE_INVALID', '预算历史无法核对。')
    history.value = before === undefined ? value.items : [...history.value, ...value.items]
    nextBefore.value = value.nextBefore
  } catch (value) { if (alive && epoch === store.sessionEpoch && request === historyGeneration) historyError.value = value }
  finally { if (alive && request === historyGeneration) historyBusy.value = false }
}
function save() {
  if (!readable.value || !checked.value || writer.locked.value || busy.value || minutes.value === null || !Number.isInteger(minutes.value) || minutes.value < 0 || minutes.value > 1440) return
  const selected = copy(scope.value), amount = minutes.value, existing = copy(budget.value)
  const received = (value: DayBudget) => {
    budget.value = value; minutes.value = amount; checked.value = false; message.value = '预算保存回执已确认，正在核对当前值。'; emit('changed'); void load()
  }
  if (existing) {
    const body: SaveBudget = { zoneId: selected.zoneId, expectedRevision: existing.revision, availableMinutes: amount }
    if (!saveBudgetValid(body)) return
    writer.run(budgetPath(selected.localDate), 'PUT', body, (value): value is DayBudget => savedBudgetValid(value, selected.localDate, body, existing), received)
  } else {
    const body: CreateBudget = { ...selected, availableMinutes: amount }
    if (!createBudgetValid(body)) return
    writer.run(todayPlanCommandPaths.createBudget, 'POST', body, (value): value is DayBudget => createdBudgetValid(value, body), received)
  }
}
</script>

<template>
  <section class="budget-editor" aria-labelledby="today-budget-title"><header><h2 id="today-budget-title">学习分钟预算</h2><NButton size="small" :disabled="!readable || busy || writer.locked.value" @click="load">重新读取预算</NButton></header><p class="notice">本次候选建议上限；不累计扣除全日时间。回忆卡张数预算单独计算，面试时长不会在这里自动扣减。</p>
    <form class="budget-scope" @submit.prevent="applyScope"><label>预算日期<input v-model="input.localDate" type="date" min="1000-01-01" max="9999-12-31" :disabled="!readable || busy || writer.locked.value" /></label><label>预算时区<NInput v-model:value="input.zoneId" :disabled="!readable || busy || writer.locked.value" :maxlength="100" /></label><NButton attr-type="submit" :disabled="!readable || busy || writer.locked.value">读取这一天</NButton></form><p class="field-help">当前编辑 {{ scope.localDate }} · {{ scope.zoneId }}。切换区域不会修改已保存预算；建议预览只接受服务器认定的当前日。</p><p v-if="problem" class="notice error">{{ problem }}</p><p v-if="busy" class="field-help" role="status">正在读取预算…</p><p v-if="error" class="notice error" role="alert">{{ todayErrorText(error) }}</p>
    <div v-if="latest && dirty" class="notice"><p>当前输入已保留。最新预算{{ latest.budget ? `为 ${latest.budget.availableMinutes} 分钟，修订 ${latest.budget.revision}` : '尚未保存' }}。</p><div class="row-actions"><NButton :disabled="!readable || busy || writer.locked.value" @click="accept(true)">保留输入，使用最新修订</NButton><NButton :disabled="!readable || busy || writer.locked.value" @click="accept(false)">载入最新值</NButton></div></div>
    <label class="minutes-field">本次建议可用分钟<NInputNumber v-model:value="minutes" :min="0" :max="1440" :precision="0" clearable :disabled="!readable || !checked || writer.locked.value || busy" /></label><p class="field-help">{{ budget ? `已保存 ${budget.availableMinutes} 分钟 · 修订 ${budget.revision}` : '尚未保存预算，不会生成可采用的时间建议。' }} 0 分钟表示明确没有本次学习时间；留空表示没有填写。</p><p v-if="message" class="field-help" role="status">{{ message }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ todayErrorText(writer.error.value) }}</p><div class="row-actions"><NButton v-if="writer.pending.value && !writer.busy.value" type="primary" :disabled="!readable" @click="writer.retry">用原请求核对结果</NButton><NButton v-else type="primary" :loading="writer.busy.value" :disabled="!readable || !checked || writer.locked.value || busy || minutes === null" @click="save">保存这一天的预算</NButton></div>
    <details><summary>预算历史（{{ history.length }} 条已读取）</summary><p v-if="historyBusy" class="field-help" role="status">正在读取预算历史…</p><p v-if="historyError" class="notice error">{{ todayErrorText(historyError) }}</p><article v-for="entry in history" :key="entry.snapshot.revision"><strong>{{ entry.snapshot.availableMinutes }} 分钟 · 修订 {{ entry.snapshot.revision }}</strong><span>{{ budgetChangeLabels[entry.change] }} · {{ entry.createdAt }}</span></article><div class="row-actions"><NButton v-if="budget" size="small" :disabled="!readable || historyBusy" @click="loadHistory()">刷新历史</NButton><NButton v-if="nextBefore !== null" size="small" :loading="historyBusy" :disabled="!readable || historyBusy" @click="loadHistory(nextBefore ?? undefined)">更早记录</NButton></div></details>
  </section>
</template>

<style scoped>
.budget-editor { display: grid; gap: 16px; padding: 22px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface); }header { display: flex; justify-content: space-between; gap: 14px; align-items: center; }h2 { font-size: 19px; margin: 0; }.budget-scope { display: grid; grid-template-columns: minmax(140px, 1fr) minmax(170px, 1fr) auto; gap: 12px; align-items: end; }label { display: grid; gap: 7px; color: var(--muted); font-size: 12px; }.minutes-field { max-width: 280px; }input { width: 100%; min-height: 34px; padding: 5px 10px; box-sizing: border-box; border: 1px solid var(--line); border-radius: 4px; background: var(--surface); color: var(--ink); }details { padding-top: 15px; border-top: 1px solid var(--line); font-size: 12px; }summary { cursor: pointer; margin-bottom: 12px; }article { display: grid; gap: 5px; padding: 12px 0; border-top: 1px solid var(--line); }article span { font-size: 11px; color: var(--muted); }@media (max-width: 650px) { .budget-scope { grid-template-columns: 1fr; }header { align-items: start; flex-wrap: wrap; } }
</style>
