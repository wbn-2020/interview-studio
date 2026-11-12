<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { errorText, read } from '../api/client'
import { collectionKindLabels } from '../api/collections'
import type { Collection, CollectionKind, CollectionPage, CollectionStatus } from '../api/collections'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'

const CollectionEditor = defineAsyncComponent(() => import('../components/bank/CollectionEditor.vue'))
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const kind = computed<CollectionKind>(() => route.query.kind === 'ROUTE' ? 'ROUTE' : 'LIST')
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const creating = computed(() => route.query.create === '1')
const status = ref<CollectionStatus>('ACTIVE')
const input = ref('')
const search = ref('')
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const statusOptions = [{ label: '使用中的集合', value: 'ACTIVE' }, { label: '已归档', value: 'ARCHIVED' }]
const list = useQuery({
  queryKey: computed(() => ['bank-collections', store.sessionEpoch, 'items', kind.value, status.value, search.value, cursor.value]),
  enabled: computed(() => store.sessionActive),
  queryFn: () => {
    const parameters = new URLSearchParams({ kind: kind.value, status: status.value, q: search.value, limit: '20' })
    if (cursor.value) parameters.set('cursor', cursor.value)
    return read<CollectionPage>(`/bank/collections?${parameters}`)
  },
})
watch([kind, status], () => { cursor.value = undefined; cursors.value = [] })
function open(id: string, valueKind: CollectionKind = kind.value) { void router.push({ name: 'collections', query: { kind: valueKind, item: id } }) }
function close() { void router.push({ name: 'collections', query: { kind: kind.value } }) }
function selectKind(value: CollectionKind) { void router.push({ name: 'collections', query: { kind: value } }) }
function create() { void router.push({ name: 'collections', query: { kind: kind.value, create: '1' } }) }
function created(value: Collection) { open(value.id, value.kind) }
function refresh() { void queryClient.invalidateQueries({ queryKey: ['bank-collections'] }) }
function submit() { search.value = input.value.trim(); cursor.value = undefined; cursors.value = [] }
function next() { if (list.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = list.data.value.nextCursor } }
</script>

<template>
  <header class="page-heading"><div><p class="section-label">把练习组织起来</p><h1>题单与学习路线</h1><p class="muted">保存练习顺序、明确学习目的，并保留每一道题的确切版本。</p></div><NButton type="primary" :disabled="!store.sessionActive" @click="create">＋ 新建{{ collectionKindLabels[kind] }}</NButton></header>
  <nav class="collection-kind-tabs" aria-label="集合类型"><button v-for="value in (['LIST', 'ROUTE'] as const)" :key="value" :aria-current="kind === value ? 'page' : undefined" :class="{ selected: kind === value }" @click="selectKind(value)">{{ collectionKindLabels[value] }}</button></nav>
  <div class="collections-workspace">
    <aside class="collection-catalog" :aria-label="`${collectionKindLabels[kind]}列表`">
      <NSelect v-model:value="status" :options="statusOptions" aria-label="集合状态" />
      <form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" placeholder="筛选已保存标题" :aria-label="`筛选${collectionKindLabels[kind]}标题`" /><NButton attr-type="submit">查找</NButton></form>
      <p class="field-help">只筛选保存标题，不搜索步骤正文。</p>
      <p v-if="list.isPending.value" class="loading-block" role="status">正在读取{{ collectionKindLabels[kind] }}…</p>
      <div v-else-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重试</NButton></div>
      <div v-else-if="!list.data.value?.items.length" class="collection-empty-list"><strong>{{ search ? '没有匹配标题' : status === 'ARCHIVED' ? '没有已归档集合' : `还没有${collectionKindLabels[kind]}` }}</strong><p>{{ search || status === 'ARCHIVED' ? '可调整关键词或状态后再试。' : '先记录用途，再从题库挑选希望练习的题目。' }}</p></div>
      <div class="collection-item-list"><button v-for="item in list.data.value?.items" :key="item.id" :class="{ selected: selectedId === item.id && !creating }" :aria-current="selectedId === item.id && !creating ? 'true' : undefined" @click="open(item.id, item.kind)"><strong>{{ item.title }}</strong><span>{{ item.stepCount }} 个步骤 · v{{ item.versionNumber }}{{ item.status === 'ARCHIVED' ? ' · 已归档' : '' }}</span></button></div>
      <div v-if="cursors.length || list.data.value?.nextCursor" class="pagination"><NButton size="small" :disabled="!cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!list.data.value?.nextCursor" @click="next">下一页</NButton></div>
    </aside>
    <CollectionEditor v-if="selectedId || creating" :key="creating ? `new-${kind}` : selectedId" :id="creating ? undefined : selectedId" :kind="kind" @changed="refresh" @created="created" @close="close" />
    <section v-else class="collection-welcome"><h2>{{ kind === 'ROUTE' ? '从先学什么，到为什么先学' : '为下一次练习挑一组题' }}</h2><p>{{ kind === 'ROUTE' ? '把题目排成步骤，写下前置关系与学习检查点。可以保留多条分支，不把显示顺序误当作依赖。' : '围绕一次面试或一个知识主题组织题目。题单保存每道题的固定版本、练习顺序和目的。' }}</p><NButton type="primary" :disabled="!store.sessionActive" @click="create">新建{{ collectionKindLabels[kind] }}</NButton><p class="collection-welcome-note">题目改版后，已保存的集合仍保留原版本。实际训练与复练将分别记录回答和依据。</p></section>
  </div>
  <p class="workspace-footnote">本页组织题目与学习步骤。可在文字训练中选择已保存集合；检查点不代表完成记录。</p>
</template>

<style scoped>
.collection-kind-tabs, .collections-workspace { max-width: 1280px; margin-left: auto; margin-right: auto; }
.collection-kind-tabs { display: flex; gap: 25px; margin-bottom: 15px; border-bottom: 1px solid var(--line); }
.collection-kind-tabs button { min-height: 40px; border-bottom: 2px solid transparent; color: var(--muted); font-size: 13px; }
.collection-kind-tabs button.selected { color: var(--brand-strong); border-bottom-color: var(--brand); font-weight: 650; }
.collections-workspace { display: grid; grid-template-columns: 265px minmax(0, 1fr); min-height: 630px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.collection-catalog { padding: 18px 13px; border-right: 1px solid var(--line); min-width: 0; background: var(--surface-soft); border-radius: 8px 0 0 8px; }
.collection-catalog > .field-help { margin: 8px 0; }
.collection-item-list { display: grid; gap: 4px; margin-top: 18px; }
.collection-item-list button { display: grid; gap: 6px; padding: 12px 9px; border-radius: 5px; text-align: left; min-width: 0; }
.collection-item-list button:hover, .collection-item-list button.selected { background: var(--surface-deep); }
.collection-item-list strong { font-size: 13px; overflow-wrap: anywhere; }
.collection-item-list span { color: var(--muted); font-size: 12px; }
.collection-empty-list { padding: 30px 6px; font-size: 13px; color: var(--muted); }
.collection-empty-list strong { display: block; color: var(--ink); margin-bottom: 8px; }
.collection-welcome { align-self: center; display: grid; justify-items: start; gap: 20px; max-width: 650px; padding: 46px clamp(24px, 4vw, 58px); }
.collection-welcome h2 { font-size: 25px; }
.collection-welcome > p { color: var(--muted); max-width: 65ch; }
.collection-welcome .collection-welcome-note { margin-top: 20px; padding-top: 20px; border-top: 1px solid var(--line); font-size: 12px; }
@media (max-width: 1000px) { .collections-workspace { grid-template-columns: 225px minmax(0, 1fr); } }
@media (max-width: 700px) { .collections-workspace { grid-template-columns: 1fr; } .collection-catalog { border-right: 0; border-bottom: 1px solid var(--line); border-radius: 8px 8px 0 0; } .collection-item-list { max-height: 260px; overflow-y: auto; } .collection-welcome { padding: 38px 22px; } .page-heading { align-items: start; flex-wrap: wrap; } }
</style>
