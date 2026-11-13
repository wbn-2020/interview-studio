<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { collectionKindLabels } from '../../api/collections'
import type { CollectionKind, CollectionPage, CollectionSummary } from '../../api/collections'
import { preparationCollectionPageValid, readPreparationCollection } from '../../api/preparationCollections'
import type { PreparationCollectionChoice } from '../../api/preparationCollections'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ disabled?: boolean; excludeVersionIds?: string[] }>()
const emit = defineEmits<{ choose: [value: PreparationCollectionChoice] }>()
const store = useWorkspaceStore()
const kind = ref<CollectionKind>('LIST')
const input = ref('')
const search = ref('')
const cursor = ref<string>()
const previous = ref<(string | undefined)[]>([])
const choosing = ref(false)
const chooseError = ref('')
const readable = computed(() => store.sessionActive)
const blocked = computed(() => !readable.value || !!props.disabled || choosing.value)
let alive = true
let generation = 0
const changed = () => new ApiError(0, 'PREPARATION_COLLECTION_CONTEXT_CHANGED', '题单选择条件已变化，请重新读取。')
const list = useQuery({
  queryKey: computed(() => ['bank-collections', store.sessionEpoch, 'preparation-picker', kind.value, search.value, cursor.value]),
  enabled: readable,
  queryFn: async () => {
    const selectedKind = kind.value, epoch = store.sessionEpoch, stamp = generation
    const parameters = new URLSearchParams({ kind: selectedKind, status: 'ACTIVE', q: search.value, limit: '20' })
    if (cursor.value) parameters.set('cursor', cursor.value)
    const value = await read<CollectionPage>(`/bank/collections?${parameters}`)
    if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation) throw changed()
    if (!preparationCollectionPageValid(value, selectedKind)) throw new ApiError(502, 'PREPARATION_COLLECTION_LIST_MISMATCH', '题单或路线目录无效，请重新读取。')
    return value
  },
})
watch([kind, search], () => { generation++; cursor.value = undefined; previous.value = []; chooseError.value = '' }, { flush: 'sync' })
watch([cursor, () => store.sessionEpoch], () => { generation++ }, { flush: 'sync' })
onUnmounted(() => { alive = false; generation++ })
function submit() { if (!blocked.value) { search.value = input.value.trim(); cursor.value = undefined; previous.value = [] } }
async function choose(item: CollectionSummary) {
  if (blocked.value || props.excludeVersionIds?.includes(item.versionId)) return
  const epoch = store.sessionEpoch, stamp = generation
  choosing.value = true; chooseError.value = ''
  try {
    const value = await readPreparationCollection(item.id, item.versionId)
    if (!alive || !readable.value || props.disabled || epoch !== store.sessionEpoch || stamp !== generation) return
    if (value.state !== 'CURRENT' || value.kind !== item.kind || value.version.number !== item.versionNumber
      || value.version.content.title !== item.title) throw new ApiError(409, 'SOURCE_CHANGED', '所选题单或路线已经改版或归档，请刷新目录后重新选择。')
    if (props.excludeVersionIds?.includes(value.version.id)) return
    emit('choose', { kind: 'COLLECTION', itemId: value.id, versionId: value.version.id,
      title: `${collectionKindLabels[value.kind]} · ${value.version.content.title}`, number: value.version.number })
  } catch (error) { if (alive && epoch === store.sessionEpoch && stamp === generation) chooseError.value = errorText(error) }
  finally { if (alive) choosing.value = false }
}
function next() { if (!blocked.value && !list.isFetching.value && list.data.value?.nextCursor) { previous.value.push(cursor.value); cursor.value = list.data.value.nextCursor } }
</script>

<template>
  <section class="collection-picker" aria-label="选择题单或学习路线">
    <p v-if="!readable" class="notice">会话已变化，题单目录暂时遮挡。</p>
    <template v-else>
      <NSelect v-model:value="kind" :options="[{ value: 'LIST', label: '题单' }, { value: 'ROUTE', label: '学习路线' }]" :disabled="blocked" aria-label="集合类型" />
      <form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" :disabled="blocked" placeholder="按已保存标题查找" aria-label="查找题单或路线" /><NButton attr-type="submit" :disabled="blocked">查找</NButton></form>
      <p class="field-help">只提供活跃集合。选择和保存时都重新核对当前版本，不会读取或显示题目参考答案。</p>
      <p v-if="list.isPending.value" role="status" class="field-help">正在读取{{ collectionKindLabels[kind] }}…</p>
      <p v-if="list.error.value || chooseError" role="alert" class="notice error">{{ chooseError || errorText(list.error.value) }}<NButton text :disabled="blocked || list.isFetching.value" @click="list.refetch()">重新读取目录</NButton></p>
      <div class="collection-choices"><button v-for="item in list.data.value?.items ?? []" :key="`${item.id}:${item.versionId}`" type="button" :disabled="blocked || list.isFetching.value || !!list.error.value || excludeVersionIds?.includes(item.versionId)" @click="choose(item)"><strong>{{ item.title }}</strong><span>{{ collectionKindLabels[item.kind] }} · v{{ item.versionNumber }} · {{ item.stepCount }} 步</span></button></div>
      <p v-if="list.data.value && !list.data.value.items.length" class="field-help">没有符合条件的{{ collectionKindLabels[kind] }}，可调整标题或先去题库整理集合。</p>
      <div class="row-actions"><NButton size="small" :disabled="blocked || list.isFetching.value || !previous.length" @click="cursor = previous.pop()">上一页</NButton><NButton size="small" :disabled="blocked || list.isFetching.value || !list.data.value?.nextCursor" @click="next">下一页</NButton><NButton text size="small" :disabled="blocked || list.isFetching.value" @click="list.refetch()">刷新</NButton></div>
    </template>
  </section>
</template>

<style scoped>
.collection-picker { display: grid; gap: 10px; padding: 12px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
.collection-picker .title-search { margin: 0; }
.collection-choices { display: grid; gap: 4px; max-height: 260px; overflow-y: auto; }
.collection-choices button { display: grid; gap: 4px; padding: 10px; text-align: left; border-radius: 5px; }
.collection-choices button:hover { background: var(--surface-deep); }
.collection-choices strong { font-size: 13px; overflow-wrap: anywhere; }
.collection-choices span { color: var(--muted); font-size: 11px; }
</style>
