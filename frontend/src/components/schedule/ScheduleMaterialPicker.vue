<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { ItemDetail, Schema } from '../../api/client'
import type { PreparationItem, PreparationPage } from '../../api/preparation'
import type { ScheduleChoice } from '../../api/schedule'
import { scheduleMaterialLabels } from '../../api/schedule'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ mode: 'target' | 'materials'; disabled?: boolean; excludedVersionIds?: string[] }>()
const emit = defineEmits<{ choose: [value: ScheduleChoice] }>()
const store = useWorkspaceStore()
const kind = ref<ScheduleChoice['kind']>(props.mode === 'target' ? 'TARGET' : 'PROJECT')
const input = ref('')
const search = ref('')
const cursor = ref<string>()
const previous = ref<(string | undefined)[]>([])
const choosing = ref(false)
const error = ref('')
let alive = true
onUnmounted(() => { alive = false })
const options = ['PROJECT', 'FACT', 'EXPRESSION', 'KNOWLEDGE'].map(value => ({ value, label: scheduleMaterialLabels[value as ScheduleChoice['kind']] }))
const result = useQuery({
  queryKey: computed(() => ['schedule', store.sessionEpoch, 'material-picker', kind.value, search.value, cursor.value]),
  enabled: computed(() => store.sessionActive),
  queryFn: async () => {
    const selectedKind = kind.value
    const parameters = new URLSearchParams({ status: 'ACTIVE', q: search.value, limit: '20' })
    if (cursor.value) parameters.set('cursor', cursor.value)
    if (selectedKind === 'KNOWLEDGE') {
      const page = await read<Schema['LibraryPage']>(`/knowledge/library?${parameters}`)
      return { items: page.items.map(item => ({ kind: selectedKind, id: item.id, title: item.title, number: item.versionNumber })), nextCursor: page.nextCursor }
    }
    parameters.set('kind', selectedKind)
    if (selectedKind === 'FACT') parameters.set('verification', 'CONFIRMED')
    const page = await read<PreparationPage>(`/prepare/items?${parameters}`)
    return { items: page.items.map(item => ({ kind: selectedKind, id: item.id, title: item.title, number: item.versionNumber })), nextCursor: page.nextCursor }
  },
})
watch(kind, () => { cursor.value = undefined; previous.value = [] })
function submit() { search.value = input.value.trim(); cursor.value = undefined; previous.value = [] }
async function choose(item: { kind: ScheduleChoice['kind']; id: string }) {
  if (props.disabled || choosing.value || !store.sessionActive) return
  choosing.value = true
  error.value = ''
  const epoch = store.sessionEpoch
  try {
    let choice: ScheduleChoice
    if (item.kind === 'KNOWLEDGE') {
      const value = await read<ItemDetail>(`/knowledge/items/${item.id}`)
      if (value.status !== 'ACTIVE') throw new Error('该知识已归档，请重新选择。')
      choice = { kind: item.kind, itemId: item.id, versionId: value.published.id, title: value.published.title, number: value.published.number }
    } else {
      const value = await read<PreparationItem>(`/prepare/items/${item.id}`)
      if (value.status !== 'ACTIVE' || (value.kind === 'FACT' && value.verification !== 'CONFIRMED') || value.reviewRequired) throw new Error('这份材料的状态或依据已变化，请先复核后再选择。')
      choice = { kind: item.kind, itemId: item.id, versionId: value.currentVersion.id, title: value.currentVersion.content.title, number: value.currentVersion.number }
    }
    if (!alive || props.disabled || !store.sessionActive || store.sessionEpoch !== epoch || props.excludedVersionIds?.includes(choice.versionId)) return
    emit('choose', choice)
  } catch (reason) { if (alive && store.sessionActive && store.sessionEpoch === epoch) error.value = errorText(reason) }
  finally { if (alive) choosing.value = false }
}
function next() { if (result.data.value?.nextCursor) { previous.value.push(cursor.value); cursor.value = result.data.value.nextCursor } }
</script>

<template>
  <section class="schedule-picker" :aria-label="mode === 'target' ? '选择岗位目标' : '选择准备材料'">
    <NSelect v-if="mode === 'materials'" v-model:value="kind" :options="options" :disabled="disabled || choosing" aria-label="材料类型" />
    <form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :disabled="disabled || choosing" :maxlength="100" placeholder="按已保存标题查找" aria-label="查找材料" /><NButton attr-type="submit" :disabled="disabled || choosing">查找</NButton></form>
    <p v-if="result.isPending.value" class="field-help" role="status">正在读取材料…</p>
    <p v-if="error || result.error.value" class="notice error" role="alert">{{ error || errorText(result.error.value) }}<NButton v-if="result.error.value" text @click="result.refetch()">重试读取</NButton></p>
    <p v-if="result.data.value && !result.data.value.items.length" class="field-help">没有匹配的活跃材料。可先在准备或知识工作台保存内容。</p>
    <div class="picker-results"><button v-for="item in result.data.value?.items" :key="item.id" :disabled="disabled || choosing" @click="choose(item)"><strong>{{ item.title }}</strong><small>{{ scheduleMaterialLabels[item.kind] }} · v{{ item.number }}</small></button></div>
    <div v-if="previous.length || result.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="disabled || choosing || !previous.length" @click="cursor = previous.pop()">上一页</NButton><NButton size="small" :disabled="disabled || choosing || !result.data.value?.nextCursor" @click="next">下一页</NButton></div>
    <p class="field-help">{{ mode === 'target' ? '保存时固定目标的当前版本；后续目标改版会保留提示。' : '选择确切材料版本；来源后续变化会保留提示，保存时由服务器再次核对。' }}</p>
  </section>
</template>

<style scoped>
.schedule-picker { display: grid; gap: 9px; padding: 12px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); }
.schedule-picker .title-search { margin: 0; }
.picker-results { display: grid; gap: 3px; max-height: 250px; overflow: auto; }
.picker-results > button { display: grid; gap: 4px; text-align: left; min-width: 0; padding: 9px; border-radius: 4px; }
.picker-results > button:hover { background: var(--surface-deep); }
.picker-results strong { font-size: 13px; overflow-wrap: anywhere; }
.picker-results small { color: var(--muted); font-size: 11px; }
</style>
