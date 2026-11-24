<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput } from 'naive-ui'
import { errorText } from '../api/client'
import { readPrivacyObjects } from '../api/dataPrivacy'
import type { PrivacyActionableKind, PrivacyObjectCandidate } from '../api/dataPrivacy'
import { useWorkspaceStore } from '../state/workspace'

const props = defineProps<{ kind: PrivacyActionableKind; disabled?: boolean }>()
const emit = defineEmits<{ select: [value: PrivacyObjectCandidate] }>()
const store = useWorkspaceStore()
const open = ref(false), input = ref(''), query = ref(''), cursor = ref<string>()
const previous = ref<(string | undefined)[]>([])
const results = useQuery({
  queryKey: computed(() => ['data-privacy', store.sessionEpoch, 'objects', props.kind, query.value, cursor.value]),
  enabled: computed(() => open.value && store.sessionActive && !props.disabled),
  queryFn: () => readPrivacyObjects(props.kind, query.value, cursor.value),
})
watch([open, () => props.kind], () => { cursor.value = undefined; previous.value = [] })
function search() { query.value = input.value.trim(); cursor.value = undefined; previous.value = [] }
function choose(value: PrivacyObjectCandidate) { emit('select', value); open.value = false }
function next() { if (results.data.value?.nextCursor) { previous.value.push(cursor.value); cursor.value = results.data.value.nextCursor } }
</script>

<template>
  <div class="privacy-object-picker">
    <NButton :disabled="disabled" :aria-expanded="open" @click="open = !open">{{ open ? '收起对象列表' : '搜索并选择对象' }}</NButton>
    <div v-if="open" class="privacy-object-results">
      <form class="title-search" @submit.prevent="search"><NInput v-model:value="input" :maxlength="100" :disabled="disabled" placeholder="按标题搜索当前类型" aria-label="搜索隐私处理对象" /><NButton attr-type="submit" :disabled="disabled">查找</NButton></form>
      <p v-if="results.isPending.value" class="field-help" role="status">正在读取对象…</p>
      <p v-if="results.error.value" class="notice error" role="alert">{{ errorText(results.error.value) }}<NButton text :disabled="disabled" @click="results.refetch()">重试</NButton></p>
      <p v-if="results.data.value && !results.data.value.items.length" class="field-help">没有匹配对象。可调整关键词或对象类型。</p>
      <button v-for="item in results.data.value?.items" :key="`${item.objectKind}:${item.objectId}`" type="button" :disabled="disabled" @click="choose(item)"><strong>{{ item.title }}</strong><span>{{ item.subtitle }} · {{ item.privacyState }}</span><code>{{ item.objectId }}</code></button>
      <div v-if="previous.length || results.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="disabled || !previous.length" @click="cursor = previous.pop()">上一页</NButton><NButton size="small" :disabled="disabled || !results.data.value?.nextCursor" @click="next">下一页</NButton></div>
    </div>
  </div>
</template>

<style scoped>
.privacy-object-picker { display: grid; gap: 10px; }
.privacy-object-results { display: grid; gap: 10px; padding: 12px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); }
.privacy-object-results > button { display: grid; gap: 5px; padding: 11px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); text-align: left; }
.privacy-object-results > button:hover { border-color: var(--brand); }
.privacy-object-results strong, .privacy-object-results span { overflow-wrap: anywhere; }
.privacy-object-results span, .privacy-object-results code { color: var(--muted); font-size: 11px; }
.privacy-object-results code { overflow-wrap: anywhere; }
</style>
