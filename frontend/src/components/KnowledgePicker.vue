<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput } from 'naive-ui'
import { read, errorText } from '../api/client'
import type { ItemSummary, Schema } from '../api/client'
import { useWorkspaceStore } from '../state/workspace'
const props = defineProps<{ kind?: 'NOTE' | 'CONCEPT'; disabled?: boolean; excludeIds?: string[]; includeArchived?: boolean }>()
const emit = defineEmits<{ choose: [item: ItemSummary] }>()
const store = useWorkspaceStore()
const input = ref('')
const search = ref('')
const cursor = ref<string>()
const status = ref<'ACTIVE' | 'ARCHIVED'>('ACTIVE')
const query = useQuery({
  queryKey: computed(() => ['knowledge', store.sessionEpoch, 'picker', props.kind, status.value, search.value, cursor.value]),
  enabled: computed(() => store.sessionActive),
  queryFn: () => {
    const params = new URLSearchParams({ q: search.value, limit: '20', status: status.value })
    if (props.kind) params.set('kind', props.kind)
    if (cursor.value) params.set('cursor', cursor.value)
    return read<Schema['LibraryPage']>(`/knowledge/library?${params}`)
  },
})
watch(status, () => { cursor.value = undefined })
function submit() { search.value = input.value.trim(); cursor.value = undefined }
</script>
<template>
  <div class="knowledge-picker">
    <form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" :disabled="disabled" placeholder="按已发布标题查找" aria-label="查找要关联的知识" /><NButton attr-type="submit" :disabled="disabled">查找</NButton></form>
    <label v-if="includeArchived" class="inline-check"><input v-model="status" type="checkbox" true-value="ARCHIVED" false-value="ACTIVE" />查看已归档内容</label>
    <p v-if="query.isPending.value" class="field-help" role="status">正在读取…</p><p v-if="query.error.value" class="notice error">{{ errorText(query.error.value) }}</p>
    <div class="picker-results"><button v-for="item in query.data.value?.items" :key="item.id" type="button" :disabled="disabled || excludeIds?.includes(item.id)" @click="emit('choose', item)"><strong>{{ item.title }}</strong><small>{{ item.kind === 'NOTE' ? '笔记' : '概念' }} · {{ item.status === 'ARCHIVED' ? '已归档 · ' : '' }}{{ item.id }}</small></button></div>
    <p v-if="query.data.value && !query.data.value.items.length" class="field-help">没有匹配内容，试试其他标题。</p>
    <div class="row-actions"><NButton v-if="cursor" size="small" @click="cursor = undefined">第一页</NButton><NButton v-if="query.data.value?.nextCursor" size="small" @click="cursor = query.data.value.nextCursor">下一页</NButton></div>
  </div>
</template>
