<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { PreparationPage, PreparationSummary } from '../../api/preparation'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ choose: [value: { itemId: string; title: string }] }>()
const store = useWorkspaceStore()
const input = ref(''), search = ref(''), status = ref('ACTIVE'), cursor = ref<string>()
const previous = ref<(string | undefined)[]>([]), round = ref(0)
const readable = computed(() => store.sessionActive && !props.disabled)
const key = computed(() => ['today-target-picker', store.sessionEpoch, round.value, status.value, search.value, cursor.value])
const statuses = [{ label: '活跃目标', value: 'ACTIVE' }, { label: '已归档目标', value: 'ARCHIVED' }]
let alive = true, generation = 0
watch([readable, () => store.sessionEpoch], () => { generation++; cursor.value = undefined; previous.value = []; round.value++ }, { flush: 'sync' })
watch(status, () => { cursor.value = undefined; previous.value = [] }, { flush: 'sync' })
const query = useQuery({ queryKey: key, enabled: readable, queryFn: async () => {
  const epoch = store.sessionEpoch, request = generation, signature = JSON.stringify(key.value)
  const parameters = new URLSearchParams({ kind: 'TARGET', status: status.value, q: search.value, limit: '20' })
  if (cursor.value) parameters.set('cursor', cursor.value)
  const value = await read<PreparationPage>(`/prepare/items?${parameters}`)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || request !== generation || signature !== JSON.stringify(key.value)) {
    throw new ApiError(0, 'TODAY_TARGET_CHANGED', '目标筛选条件已变化，请重新读取。')
  }
  return value
} })
onUnmounted(() => { alive = false; generation++ })
function submit() { if (readable.value) { search.value = input.value.trim(); refresh() } }
function refresh() { cursor.value = undefined; previous.value = []; round.value++ }
function next() {
  if (readable.value && !query.isFetching.value && !query.error.value && query.data.value?.nextCursor) {
    previous.value.push(cursor.value); cursor.value = query.data.value.nextCursor
  }
}
function choose(value: PreparationSummary) {
  // 这里只筛选目标身份；归档或旧版本关联也应可查，不把创建任务的当前版本限制带入筛选。
  if (readable.value && !query.isFetching.value && !query.error.value && value.kind === 'TARGET') emit('choose', { itemId: value.id, title: value.title })
}
</script>

<template>
  <section class="today-target-picker" aria-label="选择今日筛选目标">
    <form class="picker-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" :disabled="!readable" placeholder="按目标标题查找" aria-label="目标标题" /><NSelect v-model:value="status" :options="statuses" :disabled="!readable" aria-label="目标状态" /><NButton attr-type="submit" :disabled="!readable">查找</NButton></form>
    <p class="field-help">按目标身份查找实际关联记录，包含其历史版本；不会把相关知识或笔记推定为这个目标的材料。</p>
    <p v-if="query.isFetching.value" class="field-help" role="status">正在读取目标…</p>
    <p v-if="query.error.value" class="notice error" role="alert">{{ errorText(query.error.value) }}<NButton text :disabled="!readable || query.isFetching.value" @click="refresh">从首页重读</NButton></p>
    <div v-else-if="readable" class="target-results"><button v-for="target in query.data.value?.items" :key="target.id" type="button" :disabled="query.isFetching.value" @click="choose(target)"><strong>{{ target.title }}</strong><small>{{ target.status === 'ARCHIVED' ? '已归档' : '活跃目标' }} · 当前 v{{ target.versionNumber }}</small></button></div>
    <p v-if="query.data.value && !query.error.value && !query.data.value.items.length" class="field-help">没有匹配的目标，可以更换状态或标题条件。</p>
    <div v-if="previous.length || query.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!readable || query.isFetching.value || !previous.length" @click="cursor = previous.pop()">上一页</NButton><NButton size="small" :disabled="!readable || query.isFetching.value || !!query.error.value || !query.data.value?.nextCursor" @click="next">下一页</NButton></div>
  </section>
</template>

<style scoped>
.today-target-picker { display: grid; gap: 12px; padding: 17px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
.picker-search { display: grid; grid-template-columns: minmax(0, 1fr) 140px auto; gap: 9px; }
.target-results { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px 14px; }
.target-results button { display: grid; gap: 5px; padding: 10px 5px; text-align: left; border-bottom: 1px solid var(--line); overflow-wrap: anywhere; }
.target-results strong { font-size: 13px; }.target-results small { font-size: 11px; color: var(--muted); }
@media (max-width: 620px) { .picker-search { grid-template-columns: minmax(0, 1fr) 128px; }.picker-search > button { justify-self: start; }.target-results { grid-template-columns: 1fr; } }
</style>
