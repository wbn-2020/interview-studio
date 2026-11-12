<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { ItemDetail, Schema } from '../../api/client'
import { useWorkspaceStore } from '../../state/workspace'
import type { ChosenVersion, PreparationPage, ReferenceKind } from '../../api/preparation'

const props = defineProps<{ kinds: ReferenceKind[]; disabled?: boolean; excludeVersionIds?: string[] }>()
const emit = defineEmits<{ choose: [value: ChosenVersion] }>()
const store = useWorkspaceStore()
const kind = ref<ReferenceKind>(props.kinds[0] ?? 'FACT')
const input = ref('')
const search = ref('')
const cursor = ref<string>()
const previous = ref<(string | undefined)[]>([])
const choosing = ref(false)
const chooseError = ref('')
let alive = true
onUnmounted(() => { alive = false })
const labels: Record<ReferenceKind, string> = { PROJECT: '项目', FACT: '已核实事实', KNOWLEDGE: '知识笔记／概念' }
const options = computed(() => props.kinds.map(value => ({ value, label: labels[value] })))
const results = useQuery({
  queryKey: computed(() => ['preparation', store.sessionEpoch, 'picker', kind.value, search.value, cursor.value]),
  enabled: computed(() => store.sessionActive),
  queryFn: async () => {
    const parameters = new URLSearchParams({ status: 'ACTIVE', q: search.value, limit: '20' })
    if (cursor.value) parameters.set('cursor', cursor.value)
    if (kind.value === 'KNOWLEDGE') {
      const page = await read<Schema['LibraryPage']>(`/knowledge/library?${parameters}`)
      return { items: page.items.map(item => ({ kind: 'KNOWLEDGE' as const, id: item.id, title: item.title, number: item.versionNumber, versionId: undefined as string | undefined })), nextCursor: page.nextCursor }
    }
    parameters.set('kind', kind.value)
    if (kind.value === 'FACT') parameters.set('verification', 'CONFIRMED')
    const page = await read<PreparationPage>(`/prepare/items?${parameters}`)
    return { items: page.items.map(item => ({ kind: item.kind as 'PROJECT' | 'FACT', id: item.id, title: item.title, number: item.versionNumber, versionId: item.versionId as string | undefined })), nextCursor: page.nextCursor }
  },
})
watch(kind, () => { cursor.value = undefined; previous.value = [] })
function submit() { search.value = input.value.trim(); cursor.value = undefined; previous.value = [] }
async function choose(item: { kind: ReferenceKind; id: string; title: string; number: number; versionId?: string }) {
  if (props.disabled || choosing.value || !store.sessionActive) return
  const sessionEpoch = store.sessionEpoch
  choosing.value = true
  chooseError.value = ''
  try {
    // 知识目录没有版本 ID，必须读取发布版本再固定引用，不能使用标题推断出处。
    const current = item.kind === 'KNOWLEDGE' ? await read<ItemDetail>(`/knowledge/items/${item.id}`) : undefined
    // 读取期间可能开始保存、退出会话或关闭选择器，迟到结果不能再修改已冻结的表单。
    if (!alive || props.disabled || !store.sessionActive || store.sessionEpoch !== sessionEpoch) return
    const value: ChosenVersion = { kind: item.kind, itemId: item.id, versionId: current?.published.id ?? item.versionId!, title: current?.published.title ?? item.title, number: current?.published.number ?? item.number }
    if (props.excludeVersionIds?.includes(value.versionId)) return
    emit('choose', value)
  } catch (error) { if (alive) chooseError.value = errorText(error) }
  finally { if (alive) choosing.value = false }
}
function nextPage() { if (results.data.value?.nextCursor) { previous.value.push(cursor.value); cursor.value = results.data.value.nextCursor } }
</script>

<template>
  <div class="prepare-picker">
    <NSelect v-if="kinds.length > 1" v-model:value="kind" :options="options" :disabled="disabled || choosing" aria-label="选择材料类型" />
    <form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" :disabled="disabled || choosing" placeholder="按已保存标题查找" aria-label="查找引用材料" /><NButton attr-type="submit" :disabled="disabled || choosing">查找</NButton></form>
    <p class="field-help">{{ kind === 'FACT' ? '只提供当前已核实且所属项目活跃的事实。选择后仍由服务器复核。' : '选择固定版本引用，后续改版不会替换既有依据。' }}</p>
    <p v-if="results.isPending.value" class="field-help" role="status">正在读取材料…</p><p v-if="results.error.value || chooseError" class="notice error" role="alert">{{ chooseError || errorText(results.error.value) }}<NButton v-if="results.error.value" text @click="results.refetch()">重试</NButton></p>
    <div class="prepare-picker-results"><button v-for="item in results.data.value?.items" :key="item.id" type="button" :disabled="disabled || choosing || (item.versionId !== undefined && excludeVersionIds?.includes(item.versionId))" @click="choose(item)"><strong>{{ item.title }}</strong><small>{{ labels[item.kind] }} · v{{ item.number }} · {{ item.id.slice(0, 8) }}</small></button></div>
    <p v-if="results.data.value && !results.data.value.items.length" class="field-help">没有匹配的材料。{{ kind === 'FACT' ? '先在项目事实中保存并核实，再返回选择。' : '可以调整关键词，或先创建相应内容。' }}</p>
    <div v-if="previous.length || results.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!previous.length || choosing" @click="cursor = previous.pop()">上一页</NButton><NButton size="small" :disabled="!results.data.value?.nextCursor || choosing" @click="nextPage">下一页</NButton></div>
  </div>
</template>

<style scoped>
.prepare-picker { display: grid; gap: 9px; padding: 12px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
.prepare-picker .title-search { margin: 0; }
.prepare-picker-results { display: grid; gap: 3px; max-height: 240px; overflow-y: auto; }
.prepare-picker-results button { display: grid; gap: 4px; min-width: 0; padding: 9px; text-align: left; border-radius: 5px; }
.prepare-picker-results button:hover { background: var(--surface-deep); }
.prepare-picker-results strong { overflow-wrap: anywhere; font-size: 13px; }
.prepare-picker-results small { color: var(--muted); font-size: 11px; }
</style>
