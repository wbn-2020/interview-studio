<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { RouterLink } from 'vue-router'
import { NButton } from 'naive-ui'
import { errorText } from '../../api/client'
import { collectionKindLabels } from '../../api/collections'
import { referenceLabels } from '../../api/preparation'
import { readPreparationCollection } from '../../api/preparationCollections'
import { useWorkspaceStore } from '../../state/workspace'
import CollectionReadout from '../bank/CollectionReadout.vue'

const props = defineProps<{ itemId: string; versionId: string }>()
const store = useWorkspaceStore()
const opened = ref(false)
const readable = computed(() => store.sessionActive && opened.value)
const source = useQuery({
  queryKey: computed(() => ['bank-collections', store.sessionEpoch, 'preparation-fixed-reference', props.itemId, props.versionId]),
  enabled: readable,
  queryFn: () => readPreparationCollection(props.itemId, props.versionId),
})
</script>

<template>
  <details class="collection-reference" :open="opened" @toggle="opened = ($event.target as HTMLDetailsElement).open">
    <summary>查看确切题单／路线快照</summary>
    <div v-if="readable" class="reference-content">
      <p v-if="source.isPending.value" class="field-help" role="status">正在读取目标当时引用的集合版本…</p>
      <p v-if="source.error.value" class="notice error" role="alert">{{ errorText(source.error.value) }}<NButton text :disabled="source.isFetching.value" @click="source.refetch()">重试</NButton></p>
      <template v-if="source.data.value">
        <p class="field-help">{{ collectionKindLabels[source.data.value.kind] }} · v{{ source.data.value.version.number }} · {{ referenceLabels[source.data.value.state] }}</p>
        <p v-if="source.data.value.state !== 'CURRENT'" class="notice">来源已经改版或归档。下方继续显示原确切版本；历史关联不会自动升级，新目标关联须重新选择当前活跃版本。</p>
        <CollectionReadout :kind="source.data.value.kind" :content="source.data.value.version.content" />
        <RouterLink class="text-link" :to="{ name: 'collections', query: { kind: source.data.value.kind, item: itemId, version: versionId } }">打开集合的确切版本历史</RouterLink>
        <p class="field-help">该引用仅组织目标准备，不代表已经练习、已掌握或同意向模型发送。查看题目会打开其确切版本，参考答案仍遵循题库的显式查看规则。</p>
      </template>
    </div>
  </details>
</template>

<style scoped>
.collection-reference { width: 100%; min-width: 0; }
summary { cursor: pointer; color: var(--brand-strong); font-size: 12px; }
.reference-content { display: grid; gap: 12px; margin-top: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); }
</style>
