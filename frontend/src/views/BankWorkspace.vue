<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { errorText, read } from '../api/client'
import type { ItemSummary, Schema } from '../api/client'
import type { Question, QuestionDimension, QuestionPage, QuestionType } from '../api/bank'
import { questionDifficultyOptions, questionDimensionOptions, questionTypeLabels, questionTypeOptions } from '../api/bank'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'
import KnowledgePicker from '../components/KnowledgePicker.vue'

const QuestionEditor = defineAsyncComponent(() => import('../components/bank/QuestionEditor.vue'))
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const creating = computed(() => route.query.create === '1')
const status = ref<'ACTIVE' | 'ARCHIVED'>('ACTIVE')
const favorite = ref(false)
const type = ref<QuestionType | null>(null)
const difficulty = ref<number | null>(null)
const dimension = ref<QuestionDimension | null>(null)
const topicId = ref<string | null>(null)
const knowledge = ref<Pick<ItemSummary, 'id' | 'title'>>()
const knowledgePicker = ref(false)
const titleInput = ref('')
const search = ref('')
const sceneInput = ref('')
const scene = ref('')
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const statusOptions = [{ label: '使用中的题目', value: 'ACTIVE' }, { label: '已停用题目', value: 'ARCHIVED' }]
const topics = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'topics']), enabled: computed(() => store.sessionActive), queryFn: () => read<Schema['TopicList']>('/knowledge/topics') })
const topicOptions = computed(() => topics.data.value?.items.map(topic => ({ value: topic.id, label: topic.name })) ?? [])
const hasFilters = computed(() => Boolean(search.value || scene.value || favorite.value || type.value || difficulty.value || dimension.value || topicId.value || knowledge.value || status.value === 'ARCHIVED'))
const list = useQuery({
  queryKey: computed(() => ['bank', store.sessionEpoch, 'items', status.value, favorite.value, type.value, difficulty.value, dimension.value, topicId.value, knowledge.value?.id, search.value, scene.value, cursor.value]),
  enabled: computed(() => store.sessionActive),
  queryFn: () => {
    const parameters = new URLSearchParams({ status: status.value, q: search.value, scene: scene.value, limit: '20' })
    if (favorite.value) parameters.set('favorite', 'true')
    if (type.value) parameters.set('type', type.value)
    if (difficulty.value !== null) parameters.set('difficulty', String(difficulty.value))
    if (dimension.value) parameters.set('dimension', dimension.value)
    if (topicId.value) parameters.set('topicId', topicId.value)
    if (knowledge.value) parameters.set('knowledgeId', knowledge.value.id)
    if (cursor.value) parameters.set('cursor', cursor.value)
    return read<QuestionPage>(`/bank/questions?${parameters}`)
  },
})
watch([status, favorite, type, difficulty, dimension, topicId, knowledge, search, scene], resetPage, { flush: 'sync' })
watch(() => store.sessionEpoch, resetPage)
function resetPage() { cursor.value = undefined; cursors.value = [] }
function submitTitle() { search.value = titleInput.value.trim(); resetPage() }
function submitScene() { scene.value = sceneInput.value.trim(); resetPage() }
function clearFilters() { status.value = 'ACTIVE'; favorite.value = false; type.value = null; difficulty.value = null; dimension.value = null; topicId.value = null; knowledge.value = undefined; titleInput.value = ''; search.value = ''; sceneInput.value = ''; scene.value = ''; resetPage() }
function selectKnowledge(value: ItemSummary) { knowledge.value = { id: value.id, title: value.title }; knowledgePicker.value = false }
function open(id: string) { void router.push({ name: 'bank', query: { item: id } }) }
function close() { void router.push({ name: 'bank' }) }
function create() { void router.push({ name: 'bank', query: { create: '1' } }) }
function generate() { if (store.sessionActive) void router.push({ name: 'ai', query: { create: '1', purpose: 'QUESTION_DRAFT' } }) }
function created(value: Question) { open(value.id) }
function refresh() { void queryClient.invalidateQueries({ queryKey: ['bank'] }) }
function next() { if (list.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = list.data.value.nextCursor } }
</script>

<template>
  <header class="page-heading"><div><p class="section-label">为独立回答准备问题</p><h1>题库</h1><p class="muted">按场景组织题目，保留适用条件、知识依据和每次修订。</p></div><div class="row-actions"><NButton :disabled="!store.sessionActive" @click="generate">AI 出题</NButton><NButton type="primary" :disabled="!store.sessionActive" @click="create">＋ 新建题目</NButton></div></header>
  <div class="bank-workspace">
    <aside class="bank-catalog" aria-label="题库目录与筛选">
      <div class="bank-primary-filters"><NSelect v-model:value="status" :options="statusOptions" aria-label="题目状态" /><label class="bank-checkbox"><input v-model="favorite" type="checkbox" /> 仅看收藏</label></div>
      <form class="title-search" @submit.prevent="submitTitle"><NInput v-model:value="titleInput" :maxlength="100" placeholder="筛选已保存标题" aria-label="题目标题关键词" /><NButton attr-type="submit">查找</NButton></form>
      <details class="bank-more-filters"><summary>更多筛选 <span v-if="type || difficulty || dimension || topicId || knowledge || scene" class="filter-indicator">已选</span></summary><div class="bank-filter-fields"><label>题型<NSelect v-model:value="type" :options="questionTypeOptions" clearable placeholder="全部题型" /></label><label>个人难度<NSelect v-model:value="difficulty" :options="questionDifficultyOptions" clearable placeholder="全部难度" /></label><label>考察维度<NSelect v-model:value="dimension" :options="questionDimensionOptions" clearable placeholder="全部维度" /></label><label>知识主题<NSelect v-model:value="topicId" :options="topicOptions" clearable filterable placeholder="全部主题" /></label><p v-if="topics.error.value" class="notice error" role="alert">{{ errorText(topics.error.value) }}<NButton text @click="topics.refetch()">重试主题</NButton></p><section class="bank-knowledge-filter"><h2>关联知识</h2><p v-if="knowledge" class="selected-knowledge">{{ knowledge.title }}<NButton text size="small" @click="knowledge = undefined">清除</NButton></p><NButton size="small" @click="knowledgePicker = !knowledgePicker">{{ knowledgePicker ? '收起知识列表' : knowledge ? '更换知识筛选' : '选择笔记或概念' }}</NButton><KnowledgePicker v-if="knowledgePicker" include-archived @choose="selectKnowledge" /></section><form class="bank-scene-filter" @submit.prevent="submitScene"><label>场景关键词<NInput v-model:value="sceneInput" :maxlength="100" placeholder="按已保存场景筛选" /></label><NButton size="small" attr-type="submit">应用场景筛选</NButton></form></div></details>
      <p class="field-help">标题与场景按保存文本筛选，不搜索参考答案。</p><p v-if="search || scene" class="bank-filter-summary">{{ search ? `标题：${search}` : '' }}{{ search && scene ? '；' : '' }}{{ scene ? `场景：${scene}` : '' }}</p><NButton v-if="hasFilters" text size="small" @click="clearFilters">清除筛选</NButton>
      <p v-if="list.isPending.value" class="loading-block" role="status">正在读取题目…</p>
      <div v-else-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重试</NButton></div>
      <div v-else-if="!list.data.value?.items.length" class="bank-empty-list"><strong>{{ hasFilters ? '没有符合条件的题目' : '题库从一道问题开始' }}</strong><p>{{ hasFilters ? '试着减少筛选条件，或回到使用中的题目。' : '可以手工记录题干和适用条件，再逐步补充参考与知识关联。' }}</p></div>
      <div class="bank-item-list"><button v-for="item in list.data.value?.items" :key="item.id" :class="{ selected: selectedId === item.id && !creating }" :aria-current="selectedId === item.id && !creating ? 'true' : undefined" @click="open(item.id)"><strong><span v-if="item.favorite" aria-label="已收藏">★ </span>{{ item.title }}</strong><span>{{ questionTypeLabels[item.type] }} · v{{ item.versionNumber }}{{ item.status === 'ARCHIVED' ? ' · 已停用' : '' }}</span><small>{{ item.difficulty === null ? '未标个人难度' : `个人难度 ${item.difficulty}` }} · 个人参考／未审</small></button></div>
      <div v-if="cursors.length || list.data.value?.nextCursor" class="pagination"><NButton size="small" :disabled="!cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!list.data.value?.nextCursor" @click="next">下一页</NButton></div>
    </aside>
    <QuestionEditor v-if="selectedId || creating" :key="creating ? 'new' : selectedId" :id="creating ? undefined : selectedId" @changed="refresh" @created="created" @close="close" />
    <section v-else class="bank-welcome"><h2>把值得练习的问题留下来</h2><p>记录题干、适用条件与考察目的，再关联知识库里的笔记或概念。每次修改保留独立版本，题单继续引用它选定的那一版。</p><div class="row-actions"><NButton type="primary" :disabled="!store.sessionActive" @click="create">新建题目</NButton><NButton :disabled="!store.sessionActive" @click="generate">从知识生成候选题</NButton></div><div class="bank-welcome-notes"><p>先读题干，再决定是否打开个人参考。显式查看会留下接触记录，方便以后区分独立回答的条件。</p><p>可以在“题单与路线”组织练习顺序，在 <RouterLink class="text-link" :to="{ name: 'training' }">文字训练</RouterLink> 中选择已保存的题目。AI 候选需审阅并采用后才进入题库。</p></div></section>
  </div>
  <p class="workspace-footnote">题目、个人参考与知识引用按版本保存。难度是个人标记，接触记录不代表已掌握。</p>
</template>

<style scoped>
.bank-workspace { display: grid; grid-template-columns: 280px minmax(0, 1fr); min-height: 650px; max-width: 1280px; margin: 0 auto; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.bank-catalog { min-width: 0; padding: 18px 14px; border-right: 1px solid var(--line); border-radius: 8px 0 0 8px; background: var(--surface-soft); }
.bank-primary-filters { display: grid; gap: 12px; }
.bank-checkbox { display: flex; align-items: center; gap: 6px; color: var(--muted); font-size: 12px; }
.bank-more-filters { margin: 15px 0 10px; border-bottom: 1px solid var(--line); padding-bottom: 12px; }
.bank-more-filters > summary { cursor: pointer; font-size: 13px; }
.filter-indicator { margin-left: 8px; color: var(--brand-strong); font-size: 12px; }
.bank-filter-fields { display: grid; gap: 13px; padding-top: 15px; }
.bank-filter-fields label { display: grid; gap: 6px; font-size: 12px; color: var(--muted); }
.bank-knowledge-filter { display: grid; gap: 8px; min-width: 0; }
.bank-knowledge-filter h2 { font-size: 12px; font-weight: 400; color: var(--muted); }
.selected-knowledge { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; overflow-wrap: anywhere; }
.bank-scene-filter { display: grid; gap: 8px; }
.bank-catalog > .field-help { margin: 8px 0; }
.bank-filter-summary { color: var(--muted); font-size: 12px; overflow-wrap: anywhere; margin-bottom: 7px; }
.bank-item-list { display: grid; gap: 4px; margin-top: 18px; }
.bank-item-list > button { display: grid; gap: 6px; padding: 12px 9px; text-align: left; min-width: 0; border-radius: 5px; }
.bank-item-list > button:hover, .bank-item-list > button.selected { background: var(--surface-deep); }
.bank-item-list strong { font-size: 13px; overflow-wrap: anywhere; }
.bank-item-list > button > span, .bank-item-list small { color: var(--muted); font-size: 12px; }
.bank-empty-list { padding: 30px 6px; color: var(--muted); font-size: 13px; }
.bank-empty-list strong { display: block; color: var(--ink); margin-bottom: 8px; }
.bank-welcome { align-self: center; display: grid; justify-items: start; gap: 20px; max-width: 690px; padding: 46px clamp(24px, 4vw, 58px); }
.bank-welcome h2 { font-size: 25px; } .bank-welcome p { color: var(--muted); max-width: 65ch; }
.bank-welcome-notes { display: grid; gap: 12px; margin-top: 20px; padding-top: 20px; border-top: 1px solid var(--line); font-size: 12px; }
@media (max-width: 1000px) { .bank-workspace { grid-template-columns: 235px minmax(0, 1fr); } }
@media (max-width: 700px) { .bank-workspace { grid-template-columns: 1fr; } .bank-catalog { border-right: 0; border-bottom: 1px solid var(--line); border-radius: 8px 8px 0 0; } .bank-item-list { max-height: 260px; overflow-y: auto; } .bank-primary-filters { grid-template-columns: minmax(0, 1fr) auto; align-items: center; } .bank-welcome { padding: 38px 22px; } .page-heading { align-items: start; flex-wrap: wrap; } }
</style>
