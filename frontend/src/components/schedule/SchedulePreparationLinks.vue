<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NSelect } from 'naive-ui'
import { ApiError } from '../../api/client'
import type { InterviewEvent } from '../../api/schedule'
import type { ChangeCollectionVersion, ChangePreparationLinkStatus, CreatePreparationLink, LinkEntry, LinkStatus, PreparationLink } from '../../api/schedulePreparation'
import { changedCollectionVersionValid, changedPreparationLinkStatusValid, changeCollectionVersionValid, changePreparationLinkStatusValid,
  createdPreparationLinkValid, createPreparationLinkValid, preparationLinkKindLabels, preparationLinkStatusLabels, schedulePreparationErrorText as errorText } from '../../api/schedulePreparation'
import { useWorkspaceStore } from '../../state/workspace'
import { copy, usePlanCommand } from '../today/planUi'
import type { PreparationChoice } from './schedulePreparationUi'
import { preparationPath, preparationSourceRoute, preparationStateLabel, preparationTargetsLabel, readPreparationChoice, usePreparationLinks } from './schedulePreparationUi'
import SchedulePreparationLinkHistory from './SchedulePreparationLinkHistory.vue'
import SchedulePreparationPicker from './SchedulePreparationPicker.vue'

const props = defineProps<{ event: InterviewEvent; active: boolean; disabled: boolean; refreshToken: number }>()
const emit = defineEmits<{ changed: [] }>()
const store = useWorkspaceStore(), router = useRouter(), readable = computed(() => store.sessionActive && !props.disabled)
const enabled = computed(() => readable.value && props.active), filter = ref<LinkStatus | ''>('ACTIVE'), localRefresh = ref(0)
const directory = usePreparationLinks(computed(() => props.event), enabled, filter, computed(() => props.refreshToken + localRefresh.value))
const choice = shallowRef<PreparationChoice>(), upgrading = shallowRef<LinkEntry>(), choiceChecked = ref(false), pickerOpen = ref(false), history = shallowRef<LinkEntry>()
const sourceBusy = ref(false), sourceError = ref(''), message = ref('')
const writer = usePlanCommand(readable, 'schedule-preparation-links', computed(() => Boolean(choice.value)))
const canWrite = computed(() => enabled.value && !writer.locked.value && !sourceBusy.value
  && (!directory.page.value || directory.page.value.event.revision === props.event.revision))
const planned = computed(() => props.event.status === 'PLANNED')
const options = [{ value: 'ACTIVE', label: '有效关联' }, { value: 'REMOVED', label: '已移除' }, { value: '', label: '全部关联' }]
let alive = true, sourceGeneration = 0
watch([enabled, () => store.sessionEpoch, () => props.event.revision], () => { sourceGeneration++; sourceBusy.value = false; choiceChecked.value = false }, { flush: 'sync' })
watch(() => writer.error.value, value => {
  if (!value) return
  choiceChecked.value = false
  if (!writer.pending.value && value instanceof ApiError && value.status === 409) { localRefresh.value++; emit('changed') }
})
onUnmounted(() => { alive = false; sourceGeneration++ })
function changed(text: string) {
  choice.value = undefined; upgrading.value = undefined; choiceChecked.value = false; pickerOpen.value = false; sourceError.value = ''; message.value = text
  localRefresh.value++; emit('changed')
}
function choose(value: PreparationChoice) { if (canWrite.value && planned.value) { choice.value = copy(value); upgrading.value = undefined; choiceChecked.value = true; sourceError.value = ''; message.value = '' } }
function discard() { if (!writer.locked.value && readable.value) { choice.value = undefined; upgrading.value = undefined; choiceChecked.value = false; sourceError.value = '' } }
async function recheck(entry?: LinkEntry) {
  if (!canWrite.value || !planned.value || !entry && !choice.value) return
  const kind = entry?.link.kind ?? choice.value!.kind, id = entry?.link.sourceId ?? choice.value!.sourceId
  const request = ++sourceGeneration, epoch = store.sessionEpoch
  sourceBusy.value = true; sourceError.value = ''; choiceChecked.value = false
  try {
    const value = await readPreparationChoice(kind, id, props.event.time.zoneId)
    if (!alive || !enabled.value || epoch !== store.sessionEpoch || request !== sourceGeneration) return
    const existing = entry ?? upgrading.value
    if (existing && value.sourceVersionId === existing.link.sourceVersionId) { sourceError.value = '这条关联已使用当前版本，无需换版。'; return }
    choice.value = copy(value); if (entry) upgrading.value = copy(entry); choiceChecked.value = true
  } catch (reason) { if (alive && epoch === store.sessionEpoch && request === sourceGeneration) sourceError.value = errorText(reason) }
  finally { if (alive && request === sourceGeneration) sourceBusy.value = false }
}
function create() {
  if (!canWrite.value || !planned.value || !choice.value || !choiceChecked.value || upgrading.value) return
  const selected = copy(choice.value), id = props.event.id
  const body: CreatePreparationLink = { expectedEventRevision: props.event.revision, kind: selected.kind, sourceId: selected.sourceId, sourceVersionId: selected.sourceVersionId, expectedSource: selected.currentSource }
  if (!createPreparationLinkValid(body)) { sourceError.value = '关联请求无法核对，请重新选择来源。'; return }
  writer.run(`${preparationPath(id)}/preparation-links`, 'POST', body, (value): value is PreparationLink => createdPreparationLinkValid(value, id, body),
  () => changed('准备关联已保存，正在重读当前关联。'))
}
function changeStatus(entry: LinkEntry, status: LinkStatus) {
  if (!canWrite.value || choice.value || entry.link.status === status || status === 'ACTIVE' && !planned.value) return
  if (!window.confirm(status === 'REMOVED' ? '移除这条准备关联？来源内容和关联历史会保留。' : '恢复原关联？题单会保留原来固定的版本，采用新版需要另外确认。')) return
  const before = copy(entry.link), id = props.event.id
  const body: ChangePreparationLinkStatus = { expectedEventRevision: props.event.revision, expectedRevision: before.revision, status }
  if (!changePreparationLinkStatusValid(body)) { sourceError.value = '关联修订无法核对，请重新读取目录。'; return }
  writer.run(`${preparationPath(id)}/preparation-links/${before.id}/status`, 'POST', body,
    (value): value is PreparationLink => changedPreparationLinkStatusValid(value, before, body),
    () => changed(status === 'REMOVED' ? '关联已移除，仍可从已移除目录查看历史。' : '原关联已恢复，固定版本保持原样。'))
}
function updateVersion() {
  if (!canWrite.value || !planned.value || !choiceChecked.value || !choice.value?.sourceVersionId || !upgrading.value) return
  const before = copy(upgrading.value.link), selected = copy(choice.value), id = props.event.id
  const body: ChangeCollectionVersion = { expectedEventRevision: props.event.revision, expectedRevision: before.revision, sourceVersionId: selected.sourceVersionId!, expectedSource: selected.currentSource }
  if (!changeCollectionVersionValid(body)) { sourceError.value = '题单换版请求无法核对，请重新读取来源。'; return }
  writer.run(`${preparationPath(id)}/preparation-links/${before.id}/collection-version`, 'PUT', body,
    (value): value is PreparationLink => changedCollectionVersionValid(value, before, body),
    () => changed('已明确采用题单当前版本，原版本留在关联历史中。'))
}
</script>

<template>
  <section class="preparation-links" aria-label="面试准备关联">
    <header><div><h3>这次面试的准备事项</h3><p class="field-help">任务、改进动作和题单分别保留原来源。关联不会自动改日期、完成任务或启动训练。</p></div><NButton size="small" :disabled="!canWrite || !planned || !!upgrading" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起来源选择' : '添加准备关联' }}</NButton></header>
    <p v-if="disabled" class="notice">请先处理其他分区的未保存输入或未确认请求，再修改准备关联。</p>
    <p v-if="!planned" class="field-help">面试已关闭，仍可查看历史或移除关联；新增、恢复和换版需要待进行的面试。</p>
    <SchedulePreparationPicker v-if="pickerOpen" :zone-id="event.time.zoneId" :disabled="!enabled || writer.locked.value || sourceBusy || !planned" @choose="choose" />
    <section v-if="choice" class="choice-review"><h4>{{ upgrading ? '确认采用题单新版' : '确认加入准备关联' }}</h4><strong>{{ choice.title }}</strong><p>{{ preparationStateLabel(choice.sourceStatus, choice.pendingCount) }}</p><p class="field-help">{{ preparationTargetsLabel(choice.targetRefs, event) }}</p>
      <p v-if="upgrading" class="field-help">原关联：{{ upgrading.title }} · v{{ upgrading.sourceStatus.collection?.linkedVersionNumber }}。本次只替换这条关联的固定版本。</p>
      <div class="row-actions"><NButton text size="small" :disabled="!readable || writer.locked.value" @click="router.push(preparationSourceRoute(choice, choice.sourceStatus.collection?.kind))">查看待采用来源</NButton><NButton v-if="upgrading" text size="small" :disabled="!readable || writer.locked.value" @click="router.push(preparationSourceRoute(upgrading.link, upgrading.sourceStatus.collection?.kind))">查看原确切版本</NButton></div>
      <p v-if="!choiceChecked" class="field-help">来源或访问条件可能已变化，保留当前选择，请重新核对后再提交。</p>
      <div class="row-actions"><NButton size="small" :disabled="!canWrite || !planned" :loading="sourceBusy" @click="recheck()">重新核对来源</NButton><NButton type="primary" size="small" :disabled="!canWrite || !planned || !choiceChecked" :loading="writer.busy.value" @click="upgrading ? updateVersion() : create()">{{ upgrading ? '确认采用这个版本' : '确认添加关联' }}</NButton><NButton text size="small" :disabled="!readable || writer.locked.value" @click="discard">放弃这次选择</NButton></div>
    </section>
    <p v-if="sourceError" class="notice error" role="alert">{{ sourceError }}</p><div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" size="small" :disabled="!readable" @click="writer.retry">用原请求核对结果</NButton><p v-else class="field-help">输入保留。可从首页重读关联，并重新核对来源。</p></div><p v-if="message" class="success-text" role="status">{{ message }}</p>
    <div class="directory-tools"><NSelect v-model:value="filter" :options="options" :disabled="!enabled || writer.locked.value" aria-label="准备关联状态" /><NButton size="small" :disabled="!enabled || directory.busy.value" @click="directory.refresh">从首页重读</NButton></div>
    <p v-if="directory.busy.value" class="field-help" role="status">正在读取准备关联…</p><p v-if="directory.error.value" class="notice error" role="alert">{{ errorText(directory.error.value) }}</p>
    <p v-if="directory.page.value && directory.page.value.event.revision !== event.revision" class="notice">面试修订已变化，请回到安排详情核对服务器副本后再修改关联。</p>
    <div v-if="enabled" class="link-list"><article v-for="entry in directory.entries.value" :key="entry.link.id"><div><strong>{{ entry.title }}</strong><p>{{ preparationLinkKindLabels[entry.link.kind] }} · {{ preparationLinkStatusLabels[entry.link.status] }} · 关联修订 {{ entry.link.revision }}</p><p>{{ preparationStateLabel(entry.sourceStatus, entry.pendingCount) }}</p><p>{{ preparationTargetsLabel(entry.targetRefs, directory.page.value?.event ?? event) }}</p><div v-if="entry.targetRefs.length" class="row-actions"><NButton v-for="target in entry.targetRefs" :key="`${target.itemId}/${target.versionId}`" text size="tiny" :disabled="writer.locked.value" @click="router.push({ name: 'prepare', query: { kind: 'TARGET', item: target.itemId, version: target.versionId } })">查看来源目标版本</NButton></div></div><div class="link-actions"><NButton text size="small" :disabled="writer.locked.value" @click="router.push(preparationSourceRoute(entry.link, entry.sourceStatus.collection?.kind))">{{ entry.link.kind === 'COLLECTION' ? '查看确切版本' : '查看来源' }}</NButton><NButton text size="small" @click="history = entry">关联历史</NButton><NButton v-if="entry.link.status === 'ACTIVE' && entry.sourceStatus.kind === 'COLLECTION' && entry.sourceStatus.collection.status === 'ACTIVE' && entry.sourceStatus.collection.versionState === 'SUPERSEDED'" size="small" :disabled="!canWrite || !planned || !!choice" @click="recheck(entry)">核对当前新版</NButton><NButton size="small" :disabled="!canWrite || !!choice || entry.link.status === 'REMOVED' && !planned" @click="changeStatus(entry, entry.link.status === 'ACTIVE' ? 'REMOVED' : 'ACTIVE')">{{ entry.link.status === 'ACTIVE' ? '移除关联' : '恢复原关联' }}</NButton></div></article></div>
    <p v-if="directory.page.value && !directory.entries.value.length" class="field-help">这个筛选范围没有准备关联。</p><NButton v-if="directory.page.value?.nextCursor" size="small" :disabled="!enabled || directory.busy.value" @click="directory.load(directory.page.value?.nextCursor ?? undefined)">继续读取关联</NButton>
    <p class="field-help">分页读取的是各次请求的当前状态；已显示内容不代表全部关联。写入后从首页重新读取。</p>
    <SchedulePreparationLinkHistory v-if="history" :entry="history" :disabled="!enabled" :refresh-token="refreshToken + localRefresh" @close="history = undefined" />
  </section>
</template>

<style scoped>
.preparation-links { display: grid; gap: 15px; }.preparation-links header { display: flex; justify-content: space-between; gap: 18px; align-items: start; }h3 { font-size: 15px; }h4 { font-size: 13px; margin: 0; }.choice-review { display: grid; gap: 10px; padding: 16px; border: 1px solid var(--brand); border-radius: 6px; background: var(--surface-soft); }.choice-review > strong { font-size: 14px; overflow-wrap: anywhere; }.choice-review > p { font-size: 12px; }.directory-tools { display: flex; gap: 12px; align-items: center; }.directory-tools > :first-child { max-width: 200px; }.link-list article { display: flex; justify-content: space-between; gap: 16px; padding: 16px 0; border-top: 1px solid var(--line); }.link-list article > div:first-child { min-width: 0; }.link-list strong { font-size: 13px; overflow-wrap: anywhere; }.link-list p { color: var(--muted); font-size: 11px; margin-top: 7px; }.link-actions { display: flex; flex-wrap: wrap; gap: 7px; align-content: start; justify-content: end; flex-shrink: 0; max-width: 160px; }@media (max-width: 650px) { .preparation-links header, .link-list article { flex-direction: column; }.link-actions { max-width: none; justify-content: start; } }
</style>
