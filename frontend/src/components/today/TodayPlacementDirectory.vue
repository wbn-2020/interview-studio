<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInputNumber, NSelect } from 'naive-ui'
import { ApiError, read } from '../../api/client'
import type { TodayContinuation, TodayItem, TodaySourceKind, TodaySourceRef } from '../../api/today'
import { todayErrorText, todaySourceKey, todaySourceKindLabels, todaySourceKinds } from '../../api/today'
import type { OrderedPlacements, PlacementEntry, PlacementPage } from '../../api/todayPlan'
import { orderedPlacementsValid, orderPlacementsValid, placementPageValid, placementsPath, todayPlanCommandPaths } from '../../api/todayPlan'
import { memoryUtcMicros } from '../../api/memoryReview'
import { useWorkspaceStore } from '../../state/workspace'
import { copy, readPlanSource, sourceWritable, usePlanCommand } from './planUi'

const props = defineProps<{ zoneId: string; disabled: boolean; refreshToken: number }>()
const emit = defineEmits<{ edit: [source: TodaySourceRef]; candidate: [source: TodaySourceRef]; open: [value: TodayContinuation]; changed: [] }>()
const store = useWorkspaceStore(), readable = computed(() => store.sessionActive && !props.disabled)
const input = reactive<{ kind: TodaySourceKind | ''; visibility: 'VISIBLE' | 'PAUSED' | '' }>({ kind: '', visibility: '' }), filter = shallowRef({ ...input })
const entries = shallowRef<PlacementEntry[]>([]), page = shallowRef<PlacementPage>(), busy = ref(false), error = shallowRef<unknown>()
const selected = ref<{ item: TodayItem; orderValue: number }[]>([]), selectionBusy = ref(false), selectionError = ref(''), checked = ref(false), message = ref('')
const writer = usePlanCommand(readable, 'today-placement-order', computed(() => selected.value.length > 0))
const kinds = [{ label: '全部来源', value: '' }, ...todaySourceKinds.map(value => ({ label: todaySourceKindLabels[value], value }))]
const visibilities = [{ label: '全部偏好', value: '' }, { label: '正常显示', value: 'VISIBLE' }, { label: '安排暂停', value: 'PAUSED' }]
let alive = true, generation = 0, selectionGeneration = 0, cursors = new Set<string>()
watch([readable, () => store.sessionEpoch, () => props.zoneId, () => props.refreshToken], () => { generation++; selectionGeneration++; busy.value = false; selectionBusy.value = false; checked.value = false; if (readable.value) refresh() }, { immediate: true, flush: 'sync' })
watch(() => writer.error.value, value => { if (value) checked.value = false })
onUnmounted(() => { alive = false; generation++; selectionGeneration++ })
function refresh() { generation++; checked.value = false; entries.value = []; page.value = undefined; error.value = undefined; busy.value = false; cursors = new Set(); void load() }
async function load(cursor?: string) {
  if (!readable.value || busy.value) return
  const request = ++generation, epoch = store.sessionEpoch
  const query = { zoneId: props.zoneId, ...(filter.value.kind ? { kind: filter.value.kind } : {}), ...(filter.value.visibility ? { visibility: filter.value.visibility } : {}), limit: 20, ...(cursor ? { cursor } : {}) }
  busy.value = true; error.value = undefined
  try {
    const value = await read<unknown>(placementsPath(query))
    if (!alive || !readable.value || request !== generation || epoch !== store.sessionEpoch) return
    if (!placementPageValid(value, query) || value.nextCursor && cursors.has(value.nextCursor)) throw new ApiError(0, 'TODAY_RESPONSE_INVALID', '安排目录分页无法核对。')
    if (cursor && page.value && (memoryUtcMicros(page.value.context.asOf) !== memoryUtcMicros(value.context.asOf) || page.value.context.localDate !== value.context.localDate)) throw new ApiError(409, 'TODAY_CURSOR_CHANGED', '目录条件已变化。')
    entries.value = cursor ? [...new Map([...entries.value, ...value.items].map(item => [item.placement.id, item])).values()] : value.items
    page.value = value; cursors.add(cursor ?? '')
  } catch (value) { if (alive && request === generation && epoch === store.sessionEpoch) { entries.value = []; page.value = undefined; cursors = new Set(); error.value = value } }
  finally { if (alive && request === generation) busy.value = false }
}
function applyFilter() { if (readable.value) { filter.value = { ...input }; refresh() } }
function selectedId(id: string) { return selected.value.some(value => value.item.placement?.id === id) }
async function toggle(entry: PlacementEntry, value: boolean) {
  if (!readable.value || writer.locked.value || selectionBusy.value) return
  if (!value) { selected.value = selected.value.filter(item => item.item.placement?.id !== entry.placement.id); return }
  if (selectedId(entry.placement.id)) return
  if (selected.value.length >= 50) { selectionError.value = '一次原子排序最多选择 50 项。'; return }
  const request = ++selectionGeneration, epoch = store.sessionEpoch
  selectionBusy.value = true; selectionError.value = ''
  try {
    const item = await readPlanSource(entry.placement.source, props.zoneId)
    if (!alive || !readable.value || request !== selectionGeneration || epoch !== store.sessionEpoch) return
    if (!sourceWritable(item) || item.placement?.id !== entry.placement.id) throw new Error('来源当前不可修改，或偏好身份已变化，请核对原来源。')
    selected.value.push({ item: copy(item), orderValue: item.placement.orderValue }); checked.value = false
  } catch (reason) { if (alive && request === selectionGeneration && epoch === store.sessionEpoch) selectionError.value = todayErrorText(reason) }
  finally { if (alive && request === selectionGeneration) selectionBusy.value = false }
}
async function recheck() {
  if (!readable.value || writer.locked.value || selectionBusy.value || !selected.value.length) return
  const request = ++selectionGeneration, epoch = store.sessionEpoch, values = copy(selected.value)
  selectionBusy.value = true; selectionError.value = ''; checked.value = false
  try {
    const items = await Promise.all(values.map(value => readPlanSource(value.item.source, props.zoneId)))
    if (!alive || !readable.value || request !== selectionGeneration || epoch !== store.sessionEpoch) return
    if (items.some((item, index) => !sourceWritable(item) || item.placement?.id !== values[index]!.item.placement?.id)) throw new Error('至少一个来源已不可排序。已选顺序保留，请移除该项或进入来源核对。')
    selected.value = values.map((value, index) => ({ item: items[index]!, orderValue: value.orderValue })); checked.value = true
  } catch (reason) { if (alive && request === selectionGeneration && epoch === store.sessionEpoch) selectionError.value = todayErrorText(reason) }
  finally { if (alive && request === selectionGeneration) selectionBusy.value = false }
}
function order() {
  if (!readable.value || !checked.value || writer.locked.value || selectionBusy.value || !selected.value.length) return
  if (selected.value.some(value => !Number.isInteger(value.orderValue) || value.orderValue < 0 || value.orderValue > 1000000)) { selectionError.value = '每项顺序必须为 0–1000000 的整数。'; return }
  const previous = selected.value.map(value => copy(value.item.placement!))
  const body = { items: selected.value.map(value => ({ placementId: value.item.placement!.id, expectedRevision: value.item.placement!.revision, expectedSource: value.item.stamp, orderValue: value.orderValue })) }
  if (!orderPlacementsValid(body)) { selectionError.value = '排序请求无法核对，请重新读取来源与偏好。'; return }
  writer.run(todayPlanCommandPaths.order, 'POST', body, (value): value is OrderedPlacements => orderedPlacementsValid(value, body, previous), () => {
    selected.value = []; checked.value = false; message.value = '这批顺序已整体保存，正在从目录首页重读。'; refresh(); emit('changed')
  })
}
function state(entry: PlacementEntry) {
  const value = entry.sourceStatus
  if (value.kind === 'KNOWLEDGE_DRAFT') return value.knowledge.status === 'ARCHIVED' ? '来源已归档' : value.knowledge.unpublishedDraft ? '有未发布稿' : '没有未发布稿'
  if (value.kind === 'TRAINING_SESSION') return value.training.status === 'ENDED' ? '训练已结束' : '训练尚未结束'
  if (value.kind === 'PREPARATION_TASK') return ['DONE', 'CANCELLED'].includes(value.task.status) ? '任务已结束' : value.task.status === 'PAUSED' ? '源任务暂停' : '源任务未结束'
  if (value.kind === 'MEMORY_CARD') return value.memory.cardStatus === 'ARCHIVED' ? '卡片已归档' : value.memory.scheduleStatus === 'PAUSED' ? '原排程暂停' : '卡片活跃'
  if (value.kind === 'REVIEW_ACTION') return value.action.status === 'MERGED' ? '动作已合并' : '待处理情况以来源核对为准'
  return value.interview.status === 'PLANNED' ? '面试尚未标记结果' : '面试已处理'
}
</script>

<template>
  <section class="placement-directory" aria-labelledby="placement-directory-title"><header><h2 id="placement-directory-title">安排目录</h2><NButton size="small" :disabled="!readable || busy" @click="refresh">从首页重读</NButton></header><p class="field-help">包含暂停、单日暂不推荐和已退出今日的旧偏好。原记录继续保留；恢复或修改前会重新核对来源是否可写。</p><form class="directory-filter" @submit.prevent="applyFilter"><NSelect v-model:value="input.kind" :options="kinds" :disabled="!readable" aria-label="偏好来源" /><NSelect v-model:value="input.visibility" :options="visibilities" :disabled="!readable" aria-label="偏好显示状态" /><NButton attr-type="submit" :disabled="!readable">应用筛选</NButton></form>
    <p v-if="busy" class="field-help" role="status">正在读取偏好目录…</p><p v-if="error" class="notice error" role="alert">{{ todayErrorText(error) }}</p><p v-if="message" class="field-help" role="status">{{ message }}</p>
    <div v-if="readable" class="directory-rows"><article v-for="entry in entries" :key="entry.placement.id"><NCheckbox :checked="selectedId(entry.placement.id)" :disabled="writer.locked.value || selectionBusy" :aria-label="`选择排序：${entry.title}`" @update:checked="value => toggle(entry, value)" /><div class="entry-text"><strong>{{ entry.title }}</strong><p>{{ todaySourceKindLabels[entry.placement.source.kind] }} · {{ state(entry) }}</p><p>顺序 {{ entry.placement.orderValue }} · 优先级 {{ entry.placement.priority }} · {{ entry.placement.visibility === 'PAUSED' ? '安排暂停' : '正常显示' }} · 修订 {{ entry.placement.revision }}</p><p v-if="entry.placement.plannedDate">安排 {{ entry.placement.plannedDate }} · {{ entry.placement.planZoneId }}</p><p v-if="entry.placement.suppressedOn">暂不推荐 {{ entry.placement.suppressedOn.localDate }} · {{ entry.placement.suppressedOn.zoneId }}</p></div><div class="entry-actions"><NButton size="small" :disabled="writer.locked.value" @click="emit('edit', entry.placement.source)">查看与管理偏好</NButton><NButton text size="small" :disabled="writer.locked.value" @click="emit('open', entry.continuation)">打开原来源</NButton><NButton text size="small" :disabled="writer.locked.value" @click="emit('candidate', entry.placement.source)">加入建议候选</NButton></div></article></div><p v-if="page && !entries.length" class="field-help">{{ page.nextCursor ? '本批没有匹配项，可继续读取。' : '当前范围没有已保存的安排偏好。' }}</p><NButton v-if="page?.nextCursor" :disabled="!readable" :loading="busy" @click="load(page?.nextCursor ?? undefined)">继续读取目录</NButton>
    <section v-if="selected.length" class="order-editor"><h3>调整所选事项顺序</h3><p class="field-help">{{ selected.length }}/50 项。一次全部保存或全部失败，只修改以下项目。目录刷新保留已选顺序，保存前请重新核对全部来源。</p><div v-for="value in selected" :key="todaySourceKey(value.item.source)" class="order-row"><span>{{ value.item.title }}</span><NInputNumber :value="value.orderValue" :input-props="{ 'aria-label': `${value.item.title}：显示顺序` }" :min="0" :max="1000000" :precision="0" :disabled="!readable || writer.locked.value || selectionBusy" @update:value="number => value.orderValue = number ?? Number.NaN" /><NButton text size="small" :disabled="!readable || writer.locked.value || selectionBusy" @click="selected = selected.filter(item => item !== value)">移除</NButton></div><p v-if="selectionError" class="notice error">{{ selectionError }}</p><p v-if="writer.error.value" class="notice error">{{ todayErrorText(writer.error.value) }}</p><div class="row-actions"><NButton :disabled="!readable || writer.locked.value" :loading="selectionBusy" @click="recheck">保留顺序，重新核对全部来源</NButton><NButton v-if="writer.pending.value && !writer.busy.value" type="primary" :disabled="!readable" @click="writer.retry">用原请求核对结果</NButton><NButton v-else type="primary" :disabled="!readable || !checked || writer.locked.value || selectionBusy" :loading="writer.busy.value" @click="order">整体保存顺序</NButton></div></section><p v-else-if="selectionError" class="notice error">{{ selectionError }}</p>
  </section>
</template>

<style scoped>
.placement-directory { display: grid; gap: 16px; padding: 22px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface); }header { display: flex; align-items: center; justify-content: space-between; gap: 15px; }h2 { font-size: 19px; margin: 0; }h3 { font-size: 15px; }.directory-filter { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)) auto; gap: 12px; }.directory-rows article { display: flex; align-items: start; gap: 13px; padding: 17px 0; border-top: 1px solid var(--line); }.entry-text { min-width: 0; flex: 1; overflow-wrap: anywhere; }.entry-text strong { font-size: 13px; }.entry-text p { font-size: 11px; color: var(--muted); margin-top: 6px; }.entry-actions { display: grid; gap: 6px; justify-items: end; }.order-editor { display: grid; gap: 12px; border-top: 1px solid var(--line); padding-top: 17px; }.order-row { display: grid; grid-template-columns: minmax(0, 1fr) 150px auto; align-items: center; gap: 12px; font-size: 12px; }.order-row > span { overflow-wrap: anywhere; }@media (max-width: 650px) { .directory-filter { grid-template-columns: 1fr; }.directory-rows article { flex-wrap: wrap; }.entry-actions { display: flex; flex-wrap: wrap; width: 100%; justify-content: start; }.order-row { grid-template-columns: minmax(0, 1fr) 120px; }.order-row > button { grid-column: 2; justify-self: end; } }
</style>
