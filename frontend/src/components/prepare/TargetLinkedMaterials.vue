<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../../api/client'
import type { PreparationReference, PreparationVersion } from '../../api/preparation'
import type { Version } from '../../api/client'
import { referenceLabels } from '../../api/preparation'
import { readPreparationCollection } from '../../api/preparationCollections'
import { memoryUtcMicros } from '../../api/memoryReview'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import RIcon from '../../replica/RIcon.vue'

const props = defineProps<{ targetId: string; versionId: string; references: PreparationReference[] }>()
const emit = defineEmits<{ open: [value: PreparationReference] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), generation = ref(0)
const readable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const identity = computed(() => JSON.stringify(props.references.map(value => [value.kind, value.itemId, value.versionId, value.state])))
watch([readable, () => store.sessionEpoch, () => props.targetId, () => props.versionId, identity,
  () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => generation.value++, { flush: 'sync' })
type Material = { reference: PreparationReference; createdAt: string; count: number | null }
const query = useQuery({
  queryKey: computed(() => ['preparation', store.sessionEpoch, 'target-materials', props.targetId, props.versionId, identity.value, generation.value]),
  enabled: readable,
  queryFn: async () => {
    const stamp = generation.value, epoch = currentTrainingAccessEpoch()
    const live = () => readable.value && generation.value === stamp && epoch === currentTrainingAccessEpoch()
    const items: Material[] = [], failures: string[] = [], references = [...props.references]
    // 仅读取目标已保存的确切关联版本；按该版本时间排序，不用当前正文冒充历史材料。
    for (let index = 0; index < references.length; index += 4) {
      if (!live()) throw new ApiError(0, 'TARGET_MATERIALS_CHANGED', '目标或资料访问已变化。')
      const results = await Promise.allSettled(references.slice(index, index + 4).map(async reference => {
        let id: string, number: number, createdAt: string, body: string | undefined
        if (reference.kind === 'KNOWLEDGE') {
          const value = await read<Version>(`/knowledge/items/${reference.itemId}/versions/${reference.versionId}`)
          ;({ id, number, createdAt, body } = value)
        } else if (reference.kind === 'COLLECTION') {
          const value = await readPreparationCollection(reference.itemId, reference.versionId)
          ;({ id, number, createdAt } = value.version)
        } else {
          const value = await read<PreparationVersion>(`/prepare/items/${reference.itemId}/versions/${reference.versionId}`)
          ;({ id, number, createdAt } = value)
        }
        if (id !== reference.versionId || number !== reference.number || memoryUtcMicros(createdAt) === undefined) {
          throw new ApiError(502, 'TARGET_MATERIAL_VERSION', '关联材料的确切版本无法核对。')
        }
        return { reference, createdAt, count: body === undefined ? null : Array.from(body).length }
      }))
      results.forEach((value, offset) => {
        if (value.status === 'fulfilled') items.push(value.value)
        else failures.push(`${references[index + offset]!.title}：${errorText(value.reason)}`)
      })
    }
    if (!live()) throw new ApiError(0, 'TARGET_MATERIALS_CHANGED', '目标或资料访问已变化。')
    items.sort((a, b) => {
      const left = memoryUtcMicros(a.createdAt)!, right = memoryUtcMicros(b.createdAt)!
      return left === right ? a.reference.versionId.localeCompare(b.reference.versionId) : left > right ? -1 : 1
    })
    return { stamp, items, failures }
  },
})
const data = computed(() => readable.value && query.data.value?.stamp === generation.value ? query.data.value : undefined)
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit' }).format(new Date(value)).replace('/', '.')
const length = (value: number) => value >= 1000 ? `${(value / 1000).toFixed(1)}k 字` : `${value} 字`
</script>

<template>
  <section class="target-materials">
    <h3 title="仅展示目标关联的确切材料版本，按版本保存时间倒序">近期材料</h3>
    <p v-if="!readable" class="field-help">请恢复资料访问后查看材料。</p>
    <p v-else-if="query.isFetching.value && !data" class="field-help" role="status">正在读取确切关联版本…</p>
    <button v-if="readable && query.error.value" class="materials-error" @click="query.refetch()">{{ errorText(query.error.value) }} · 重试</button>
    <button v-for="item in data?.items.slice(0, 3)" :key="item.reference.versionId" class="target-material" :title="`${item.reference.title} · v${item.reference.number} · ${referenceLabels[item.reference.state]}`" @click="emit('open', item.reference)"><RIcon :name="item.reference.kind === 'COLLECTION' ? 'route' : 'file-text'" :size="27" /><span>{{ item.reference.title }}<small v-if="item.reference.state !== 'CURRENT'">{{ referenceLabels[item.reference.state] }}</small></span><time>{{ date(item.createdAt) }}<template v-if="item.count !== null"> · {{ length(item.count) }}</template></time><RIcon name="chevron-right" :size="22" /></button>
    <p v-if="data && !data.items.length && !data.failures.length" class="field-help">目标版本尚未关联材料。</p>
    <details v-if="data?.failures.length" class="materials-failures"><summary>有 {{ data.failures.length }} 项材料未读到，当前排序不完整</summary><p v-for="failure in data.failures" :key="failure">{{ failure }}</p><button @click="query.refetch()">重试读取</button></details>
    <details v-if="data && data.items.length > 3" class="materials-more"><summary>全部关联材料（{{ data.items.length }}）</summary><button v-for="item in data.items.slice(3)" :key="item.reference.versionId" @click="emit('open', item.reference)">{{ item.reference.title }} · v{{ item.reference.number }}</button></details>
  </section>
</template>

<style scoped>
.target-materials { min-width: 0; }.target-materials h3 { margin: 0 0 12px; font-size: 24px; }.target-material { display: flex; align-items: center; gap: 18px; padding: 9px 0; width: 100%; border-bottom: 1px solid #ffffff09; text-align: left; font-size: 19px; line-height: 1.6; }.target-material:last-of-type { border: 0; }.target-material > svg { flex: none; }.target-material > span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.target-material small { display: block; color: var(--warm); font-size: 13px; }.target-material time { color: var(--muted); white-space: nowrap; font-size: 18px; }.target-material > svg:last-child { color: var(--muted); }.materials-error,.materials-failures { font-size: 14px; color: var(--danger); text-align: left; }.materials-failures p { overflow-wrap: anywhere; }.materials-more { font-size: 14px; color: var(--muted); margin-top: 10px; }.materials-more button { display: block; text-align: left; padding: 8px 0; }.materials-more summary,.materials-failures summary { cursor: pointer; }
@media(max-width:600px) { .target-material { gap: 10px; font-size: 16px; }.target-material time { font-size: 14px; } }
</style>
