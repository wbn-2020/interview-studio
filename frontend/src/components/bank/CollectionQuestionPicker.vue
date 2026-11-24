<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { errorText, read } from '../../api/client'
import { questionTypeLabels, readQuestionPage } from '../../api/collections'
import type { CollectionQuestionChoice, CollectionQuestionDetail, QuestionSummary, QuestionType } from '../../api/collections'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ disabled?: boolean; excludeQuestionIds: string[]; excludeQuestionVersions?: { id: string; versionId: string }[] }>()
const emit = defineEmits<{ choose: [value: CollectionQuestionChoice]; close: [] }>()
const store = useWorkspaceStore()
const input = ref('')
const search = ref('')
const type = ref<QuestionType | null>(null)
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const choosing = ref(false)
const chooseError = ref('')
let alive = true
let selectionRequest = 0
const typeOptions = Object.entries(questionTypeLabels).map(([value, label]) => ({ value, label }))
const locked = computed(() => props.disabled || choosing.value || !store.sessionActive)
const results = useQuery({
  queryKey: computed(() => ['bank-collections', store.sessionEpoch, 'question-picker', search.value, type.value, cursor.value]),
  enabled: computed(() => store.sessionActive),
  queryFn: () => {
    const parameters = new URLSearchParams({ status: 'ACTIVE', q: search.value, limit: '20' })
    if (type.value) parameters.set('type', type.value)
    if (cursor.value) parameters.set('cursor', cursor.value)
    return readQuestionPage(parameters)
  },
})
watch(type, () => { cursor.value = undefined; cursors.value = [] })
watch([() => props.disabled, () => store.sessionEpoch, () => store.sessionActive], () => {
  // 即使保存很快结束，保存前启动的选择也不能在解锁后悄悄写入新草稿。
  selectionRequest++
  choosing.value = false
}, { flush: 'sync' })
onUnmounted(() => { alive = false; selectionRequest++ })
function searchTitles() { search.value = input.value.trim(); cursor.value = undefined; cursors.value = [] }
function next() { if (results.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = results.data.value.nextCursor } }
function exclusion(item: { id: string; versionId: string }) {
  if (props.excludeQuestionIds.includes(item.id)) return '已在集合内'
  return props.excludeQuestionVersions?.some(version => version.id === item.id && version.versionId === item.versionId)
    ? '原题版本，请选择同题复练' : ''
}
async function choose(item: QuestionSummary) {
  if (locked.value || exclusion(item)) return
  const epoch = store.sessionEpoch
  const request = ++selectionRequest
  choosing.value = true
  chooseError.value = ''
  try {
    const current = await read<CollectionQuestionDetail>(`/bank/questions/${item.id}`)
    // 选择期间可能切会话或开始保存；迟到结果不能再进入已冻结的表单。
    if (!alive || props.disabled || !store.sessionActive || store.sessionEpoch !== epoch || request !== selectionRequest) return
    if (current.status !== 'ACTIVE' || current.currentVersion.id !== item.versionId) {
      chooseError.value = '这道题目已经改版或归档，请刷新列表后重新选择。'
      void results.refetch()
      return
    }
    if (exclusion({ id: current.id, versionId: current.currentVersion.id })) return
    emit('choose', { id: current.id, versionId: current.currentVersion.id, versionNumber: current.currentVersion.number, title: current.currentVersion.content.title, type: current.currentVersion.content.type, difficulty: current.currentVersion.content.difficulty })
  } catch (error) {
    if (alive && store.sessionActive && store.sessionEpoch === epoch && request === selectionRequest) chooseError.value = errorText(error)
  } finally { if (alive && request === selectionRequest) choosing.value = false }
}
</script>

<template>
  <section class="collection-picker" aria-label="选择题目">
    <header><h3>选择题目当前版本</h3><NButton text size="small" :disabled="choosing" @click="emit('close')">关闭选择</NButton></header>
    <form class="title-search" @submit.prevent="searchTitles"><NInput v-model:value="input" :maxlength="100" :disabled="locked" placeholder="按已保存标题筛选" aria-label="题目标题" /><NButton attr-type="submit" :disabled="locked">查找</NButton></form>
    <NSelect v-model:value="type" :options="typeOptions" clearable placeholder="全部题型" :disabled="locked" aria-label="筛选题型" />
    <p class="field-help">选择会固定题目版本。同一题目在本集合中只出现一次；这里不读取参考答案。</p>
    <p v-if="results.isPending.value" role="status" class="field-help">正在读取题目…</p>
    <div v-if="results.error.value || chooseError" class="notice error" role="alert"><p>{{ chooseError || errorText(results.error.value) }}</p><NButton size="small" text :disabled="locked" @click="results.refetch()">刷新题目</NButton></div>
    <div class="collection-picker-results"><button v-for="item in results.data.value?.items" :key="item.id" type="button" :disabled="locked || !!exclusion(item)" @click="choose(item)"><strong>{{ item.title }}</strong><span>{{ questionTypeLabels[item.type] }} · v{{ item.versionNumber }}{{ exclusion(item) ? ` · ${exclusion(item)}` : '' }}</span></button></div>
    <p v-if="results.data.value && !results.data.value.items.length" class="field-help">没有匹配的活跃题目。可调整标题或题型，或先到题库保存一道题目。</p>
    <div v-if="results.data.value && !results.data.value.items.length && !search && !type" class="row-actions"><RouterLink class="text-link" :to="{ name: 'bank', query: { create: '1' } }">新建第一道题</RouterLink><RouterLink class="text-link" :to="{ name: 'ai', query: { create: '1', purpose: 'QUESTION_DRAFT' } }">用 AI 生成候选</RouterLink></div>
    <div v-if="cursors.length || results.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="locked || !cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="locked || !results.data.value?.nextCursor" @click="next">下一页</NButton></div>
  </section>
</template>

<style scoped>
.collection-picker { display: grid; gap: 12px; padding: 16px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 7px; }
.collection-picker header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.collection-picker .title-search { margin: 0; }
.collection-picker-results { display: grid; max-height: 270px; overflow-y: auto; }
.collection-picker-results button { display: grid; gap: 5px; padding: 12px 6px; text-align: left; border-bottom: 1px solid var(--line); }
.collection-picker-results button:hover { background: var(--surface-deep); }
.collection-picker-results strong { font-size: 13px; overflow-wrap: anywhere; }
.collection-picker-results span { color: var(--muted); font-size: 12px; }
</style>
