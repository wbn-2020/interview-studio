<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { QuestionExposurePage } from '../../api/bank'
import { useWorkspaceStore } from '../../state/workspace'
const props = defineProps<{ id: string }>()
const router = useRouter()
const route = useRoute()
const store = useWorkspaceStore()
const before = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const list = useQuery({ queryKey: computed(() => ['bank', store.sessionEpoch, 'exposures', props.id, before.value]), enabled: computed(() => store.sessionActive), queryFn: () => read<QuestionExposurePage>(`/bank/questions/${props.id}/exposures?limit=20${before.value ? `&before=${encodeURIComponent(before.value)}` : ''}`) })
watch(() => store.sessionEpoch, () => { before.value = undefined; cursors.value = [] })
function older() { if (list.data.value?.nextCursor) { cursors.value.push(before.value); before.value = list.data.value.nextCursor } }
function openVersion(versionId: string) { void router.push({ name: 'bank', query: { ...route.query, item: props.id, version: versionId } }) }
</script>

<template>
  <section class="question-exposures"><h3>创作与参考查看记录</h3><p class="field-help">创作事件记录你保存过该题；查看事件记录你显式打开过参考。它们保留接触背景，不认证实际阅读，也不据此判断能力。</p><p v-if="list.isPending.value" class="field-help" role="status">正在读取记录…</p><p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重试</NButton></p><p v-if="list.data.value && !list.data.value.items.length" class="field-help">本页没有记录。</p><ol><li v-for="exposure in list.data.value?.items" :key="exposure.id"><div><strong>{{ exposure.kind === 'AUTHORED' ? '保存自建版本' : exposure.kind === 'EVALUATION_ACCESS' ? '请求打开评价区' : '显式打开参考' }}</strong><time :datetime="exposure.createdAt">{{ new Date(exposure.createdAt).toLocaleString('zh-CN') }}</time></div><button class="text-link" @click="openVersion(exposure.versionId)">查看对应题干版本</button></li></ol><div v-if="cursors.length || list.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!cursors.length" @click="before = cursors.pop()">较新</NButton><NButton size="small" :disabled="!list.data.value?.nextCursor" @click="older">更早</NButton></div></section>
</template>

<style scoped>
.question-exposures { display: grid; gap: 15px; } h3 { font-size: 16px; }
ol { list-style: none; padding: 0; margin: 0; } li { display: flex; flex-wrap: wrap; gap: 10px 16px; justify-content: space-between; align-items: center; padding: 13px 0; border-bottom: 1px solid var(--line); }
li > div { display: grid; gap: 6px; } strong { font-size: 13px; } time { color: var(--muted); font-size: 12px; }
</style>
