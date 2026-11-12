<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { PreparationItem, PreparationPage, PreparationSummary } from '../../api/preparation'
import type { PreparationTaskTarget } from '../../api/preparationTask'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ choose: [target: PreparationTaskTarget]; busy: [value: boolean] }>()
const store = useWorkspaceStore()
const input = ref('')
const search = ref('')
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const selecting = ref(false)
const error = ref('')
const readable = computed(() => store.sessionActive && !props.disabled)
let alive = true
let generation = 0
const targets = useQuery({
  queryKey: computed(() => ['preparation', store.sessionEpoch, 'task-target-picker', search.value, cursor.value]),
  enabled: readable,
  queryFn: async () => {
    const epoch = store.sessionEpoch
    const signature = JSON.stringify([search.value, cursor.value])
    const parameters = new URLSearchParams({ kind: 'TARGET', status: 'ACTIVE', q: search.value, limit: '20' })
    if (cursor.value) parameters.set('cursor', cursor.value)
    const value = await read<PreparationPage>(`/prepare/items?${parameters}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || signature !== JSON.stringify([search.value, cursor.value])) throw new ApiError(0, 'TASK_PICKER_CHANGED', '目标选择条件已变化，请重新读取。')
    return value
  },
})
watch([readable, () => store.sessionEpoch, search, cursor], () => { generation++; selecting.value = false }, { flush: 'sync' })
watch(selecting, value => emit('busy', value), { immediate: true, flush: 'sync' })
onUnmounted(() => { alive = false; generation++; emit('busy', false) })
function submit() { if (readable.value && !selecting.value) { search.value = input.value.trim(); cursor.value = undefined; cursors.value = []; error.value = '' } }
async function choose(item: PreparationSummary) {
  if (!readable.value || selecting.value || targets.isFetching.value || item.kind !== 'TARGET' || item.status !== 'ACTIVE') return
  const request = ++generation
  const epoch = store.sessionEpoch
  selecting.value = true
  error.value = ''
  try {
    const value = await read<PreparationItem>(`/prepare/items/${item.id}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || request !== generation) return
    if (value.id !== item.id || value.kind !== 'TARGET' || value.status !== 'ACTIVE' || value.currentVersion.id !== item.versionId) {
      error.value = '该目标已改版或归档，请重新读取列表后选择。'
      return
    }
    emit('choose', { itemId: value.id, versionId: value.currentVersion.id, number: value.currentVersion.number, title: value.currentVersion.content.title })
  } catch (reason) { if (alive && request === generation && epoch === store.sessionEpoch) error.value = errorText(reason) }
  finally { if (alive && request === generation) selecting.value = false }
}
function next() { if (readable.value && !selecting.value && !targets.isFetching.value && targets.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = targets.data.value.nextCursor } }
</script>

<template>
  <section class="task-target-picker" aria-label="选择准备任务的目标">
    <form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" :disabled="!readable || selecting" placeholder="按目标标题查找" aria-label="查找目标" /><NButton attr-type="submit" :disabled="!readable || selecting">查找</NButton></form>
    <p class="field-help">选择本人活跃目标的确切版本。任务创建后保留该目标版本，后续不能改绑。</p>
    <p v-if="targets.isFetching.value || selecting" class="field-help" role="status">正在读取并核对目标…</p>
    <p v-if="error || targets.error.value" class="notice error" role="alert">{{ error || errorText(targets.error.value) }}<NButton text :disabled="!readable || selecting || targets.isFetching.value" @click="error = ''; targets.refetch()">重新读取列表</NButton></p>
    <div class="picker-results"><button v-for="target in targets.data.value?.items" :key="target.id" type="button" :disabled="!readable || selecting || targets.isFetching.value || target.kind !== 'TARGET' || target.status !== 'ACTIVE'" @click="choose(target)"><strong>{{ target.title }}</strong><small>目标 · v{{ target.versionNumber }}</small></button></div>
    <p v-if="targets.data.value && !targets.data.value.items.length" class="field-help">没有匹配的活跃目标。手工任务也可以不关联目标。</p>
    <div v-if="cursors.length || targets.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!readable || selecting || targets.isFetching.value || !cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!readable || selecting || targets.isFetching.value || !targets.data.value?.nextCursor" @click="next">下一页</NButton></div>
  </section>
</template>

<style scoped>
.task-target-picker { display: grid; gap: 10px; min-width: 0; padding: 13px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
.title-search { margin: 0; }
</style>
