<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { PreparationItem, PreparationPage, PreparationSummary } from '../../api/preparation'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ disabled?: boolean; selectedId?: string }>()
const emit = defineEmits<{ choose: [item: PreparationItem]; busy: [value: boolean] }>()
const store = useWorkspaceStore()
const input = ref('')
const search = ref('')
const cursor = ref<string>()
const previous = ref<(string | undefined)[]>([])
const choosing = ref(false)
const chooseError = ref('')
const blocked = computed(() => !store.sessionActive || Boolean(props.disabled))
let alive = true
let selectionRequest = 0

const results = useQuery({
  queryKey: computed(() => ['preparation', store.sessionEpoch, 'ai-expression-targets', search.value, cursor.value]),
  enabled: computed(() => !blocked.value),
  queryFn: async () => {
    const epoch = store.sessionEpoch
    const query = search.value
    const before = cursor.value
    const parameters = new URLSearchParams({ kind: 'EXPRESSION', status: 'ACTIVE', q: query, limit: '20' })
    if (before) parameters.set('cursor', before)
    const value = await read<PreparationPage>(`/prepare/items?${parameters}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || query !== search.value || before !== cursor.value) {
      throw new ApiError(0, 'AI_PICKER_CONTEXT_CHANGED', '选择条件已变化，请重新读取当前范围。')
    }
    return value
  },
})

watch([search, cursor, () => store.sessionEpoch, blocked], () => { selectionRequest++; choosing.value = false }, { flush: 'sync' })
watch(choosing, value => emit('busy', value), { immediate: true, flush: 'sync' })
onUnmounted(() => { alive = false; selectionRequest++; emit('busy', false) })

function submit() {
  if (blocked.value || choosing.value) return
  search.value = input.value.trim()
  cursor.value = undefined
  previous.value = []
  chooseError.value = ''
}
async function choose(summary: PreparationSummary) {
  if (blocked.value || choosing.value || results.isFetching.value || summary.kind !== 'EXPRESSION' || summary.status !== 'ACTIVE') return
  const epoch = store.sessionEpoch
  const request = ++selectionRequest
  choosing.value = true
  chooseError.value = ''
  try {
    const value = await read<PreparationItem>(`/prepare/items/${summary.id}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || request !== selectionRequest) return
    // 采用会保存完整新版本；列表与详情之间发生变化时，不能悄悄替换用户选择的对照基线。
    if (value.id !== summary.id || value.kind !== 'EXPRESSION' || value.status !== 'ACTIVE'
      || value.currentVersion.id !== summary.versionId || value.revision !== summary.revision) {
      chooseError.value = '所选表达已改版、归档或发生其他修改，请重新读取列表后选择。'
      return
    }
    emit('choose', value)
  } catch (error) {
    if (alive && request === selectionRequest && epoch === store.sessionEpoch) chooseError.value = errorText(error)
  } finally { if (alive && request === selectionRequest) choosing.value = false }
}
function nextPage() {
  if (blocked.value || choosing.value || results.isFetching.value || !results.data.value?.nextCursor) return
  previous.value.push(cursor.value)
  cursor.value = results.data.value.nextCursor
}
function previousPage() { if (!blocked.value && !choosing.value && !results.isFetching.value && previous.value.length) cursor.value = previous.value.pop() }
</script>

<template>
  <section class="expression-target-picker" aria-label="选择已有表达">
    <form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" :disabled="blocked || choosing" placeholder="按已保存标题查找" aria-label="查找已有表达" /><NButton attr-type="submit" :disabled="blocked || choosing">查找</NButton></form>
    <p class="field-help">仅显示未归档的表达。选择后可对照当前版本，最终内容会保存为该表达的新版本。</p>
    <p v-if="results.isFetching.value" class="field-help" role="status">正在读取表达列表…</p>
    <p v-if="results.error.value || chooseError" class="notice error" role="alert">{{ chooseError || errorText(results.error.value) }}<NButton text :disabled="blocked || choosing || results.isFetching.value" @click="chooseError = ''; results.refetch()">重新读取列表</NButton></p>
    <div class="expression-target-results"><button v-for="item in results.data.value?.items" :key="item.id" type="button" :disabled="blocked || choosing || results.isFetching.value || item.kind !== 'EXPRESSION' || item.status !== 'ACTIVE'" @click="choose(item)"><strong>{{ item.title }}</strong><small>表达 · v{{ item.versionNumber }} · {{ selectedId === item.id ? '当前选择' : item.id.slice(0, 8) }}</small></button></div>
    <p v-if="choosing" class="field-help" role="status">正在核对所选表达版本…</p>
    <p v-if="results.data.value && !results.data.value.items.length && !results.isFetching.value" class="field-help">没有匹配的表达。可调整关键词，或选择另存为新表达。</p>
    <div v-if="previous.length || results.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="blocked || !previous.length || choosing || results.isFetching.value" @click="previousPage">上一页</NButton><NButton size="small" :disabled="blocked || !results.data.value?.nextCursor || choosing || results.isFetching.value" @click="nextPage">下一页</NButton></div>
  </section>
</template>

<style scoped>
.expression-target-picker { display: grid; gap: 10px; min-width: 0; padding: 14px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
.title-search { margin: 0; }
.expression-target-results { display: grid; gap: 4px; max-height: 250px; overflow-y: auto; }
.expression-target-results button { display: grid; gap: 5px; min-width: 0; padding: 10px; border-radius: 5px; text-align: left; }
.expression-target-results button:hover:not(:disabled) { background: var(--surface-deep); }
.expression-target-results strong { font-size: 13px; overflow-wrap: anywhere; }
.expression-target-results small { font-size: 11px; color: var(--muted); overflow-wrap: anywhere; }
@media (max-width: 550px) { .title-search { flex-wrap: wrap; } }
</style>
