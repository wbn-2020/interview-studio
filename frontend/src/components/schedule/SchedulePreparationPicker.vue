<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { NButton, NSelect } from 'naive-ui'
import { errorText } from '../../api/client'
import type { LinkKind } from '../../api/schedulePreparation'
import { preparationLinkKindLabels, preparationLinkKinds } from '../../api/schedulePreparation'
import { useWorkspaceStore } from '../../state/workspace'
import type { PreparationChoice, PreparationPickerRow } from './schedulePreparationUi'
import { readPreparationChoice, readPreparationPickerPage } from './schedulePreparationUi'

const props = defineProps<{ zoneId: string; disabled: boolean }>()
const emit = defineEmits<{ choose: [value: PreparationChoice] }>()
const store = useWorkspaceStore(), readable = computed(() => store.sessionActive && !props.disabled)
const kind = ref<LinkKind>('PREPARATION_TASK'), taskStatus = ref('TODO'), collectionKind = ref<'LIST' | 'ROUTE'>('LIST')
const items = shallowRef<PreparationPickerRow[]>([]), nextCursor = ref<string | null>(null), cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const busy = ref(false), choosing = ref(false), error = ref(''), choiceError = ref(''), loaded = ref(false)
const kinds = preparationLinkKinds.map(value => ({ value, label: preparationLinkKindLabels[value] }))
const taskStatuses = [{ value: 'TODO', label: '待开始任务' }, { value: 'IN_PROGRESS', label: '进行中任务' }, { value: 'PAUSED', label: '已暂停任务' }]
let alive = true, generation = 0, choiceGeneration = 0
watch([readable, kind, taskStatus, collectionKind, () => store.sessionEpoch, () => props.zoneId], () => {
  generation++; choiceGeneration++; busy.value = false; choosing.value = false; items.value = []; nextCursor.value = null; cursor.value = undefined; previous.value = []; loaded.value = false
  if (readable.value) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { alive = false; generation++; choiceGeneration++ })
async function load(next?: string) {
  if (!readable.value || busy.value) return
  const request = ++generation, epoch = store.sessionEpoch
  busy.value = true; error.value = ''; items.value = []; loaded.value = false; nextCursor.value = null; cursor.value = next
  try {
    const value = await readPreparationPickerPage(kind.value, taskStatus.value, collectionKind.value, next)
    if (!alive || !readable.value || request !== generation || epoch !== store.sessionEpoch) return
    if (value.nextCursor && previous.value.includes(value.nextCursor)) throw new Error('来源分页已变化，请重新选择筛选条件。')
    cursor.value = next; items.value = value.items; nextCursor.value = value.nextCursor; loaded.value = true
  } catch (reason) { if (alive && request === generation && epoch === store.sessionEpoch) error.value = errorText(reason) }
  finally { if (alive && request === generation) busy.value = false }
}
function next() { if (nextCursor.value) { previous.value.push(cursor.value); void load(nextCursor.value) } }
function back() { if (previous.value.length) void load(previous.value.pop()) }
async function choose(id: string) {
  if (!readable.value || choosing.value) return
  const request = ++choiceGeneration, epoch = store.sessionEpoch, sourceKind = kind.value
  choosing.value = true; choiceError.value = ''
  try {
    const value = await readPreparationChoice(sourceKind, id, props.zoneId)
    if (alive && readable.value && request === choiceGeneration && epoch === store.sessionEpoch) emit('choose', value)
  } catch (reason) { if (alive && request === choiceGeneration && epoch === store.sessionEpoch) choiceError.value = errorText(reason) }
  finally { if (alive && request === choiceGeneration) choosing.value = false }
}
</script>

<template>
  <section class="preparation-picker" aria-label="选择面试准备来源">
    <div class="picker-filters"><NSelect v-model:value="kind" :options="kinds" :disabled="!readable || choosing" aria-label="准备来源类型" /><NSelect v-if="kind === 'PREPARATION_TASK'" v-model:value="taskStatus" :options="taskStatuses" :disabled="!readable || choosing" aria-label="任务状态" /><NSelect v-if="kind === 'COLLECTION'" v-model:value="collectionKind" :options="[{ value: 'LIST', label: '题单' }, { value: 'ROUTE', label: '学习路线' }]" :disabled="!readable || choosing" aria-label="集合类型" /></div>
    <p class="field-help">请明确选择要用于这次面试的来源。题单会关联选择时的确切版本；同一目标的其他内容不会自动加入。</p>
    <p v-if="busy || choosing" class="field-help" role="status">{{ choosing ? '正在核对来源当前状态…' : '正在读取来源目录…' }}</p>
    <p v-if="error || choiceError" class="notice error" role="alert">{{ error || choiceError }}<NButton v-if="error" text :disabled="!readable || busy" @click="load(cursor)">重试当前页</NButton></p>
    <div v-if="readable" class="picker-items"><button v-for="item in items" :key="item.id" :disabled="choosing || busy" @click="choose(item.id)"><strong>{{ item.title }}</strong><small>{{ item.description }}</small></button></div>
    <p v-if="loaded && !items.length" class="field-help">当前范围没有可选来源。可切换类型，或先在对应工作台创建内容。</p>
    <div class="row-actions"><NButton v-if="previous.length" size="small" :disabled="!readable || busy || choosing" @click="back">上一页</NButton><NButton v-if="nextCursor" size="small" :disabled="!readable || busy || choosing" @click="next">下一页</NButton></div>
  </section>
</template>

<style scoped>
.preparation-picker { display: grid; gap: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); }
.picker-filters { display: flex; flex-wrap: wrap; gap: 10px; }.picker-filters > * { flex: 1; min-width: 170px; }
.picker-items { display: grid; gap: 4px; max-height: 300px; overflow: auto; }.picker-items > button { display: grid; gap: 5px; padding: 11px; text-align: left; border-radius: 4px; }.picker-items > button:hover { background: var(--surface-deep); }.picker-items strong { font-size: 13px; overflow-wrap: anywhere; }.picker-items small { font-size: 11px; color: var(--muted); }
</style>
