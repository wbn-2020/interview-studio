<script setup lang="ts">
import { computed, defineAsyncComponent, onActivated, onDeactivated, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, read } from '../api/client'
import { memoryIdValid } from '../api/memory'
import type { MemoryCramBatch, MemoryCramBatchPage } from '../api/memoryAttempt'
import { memoryCramBatchPageValid, memoryAttemptErrorText } from '../api/memoryAttempt'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import MemoryNavigation from '../components/memory/MemoryNavigation.vue'
const MemoryCramEditor = defineAsyncComponent(() => import('../components/memory/MemoryCramEditor.vue'))

const route = useRoute(), router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore(), active = ref(true)
const readable = computed(() => active.value && store.sessionActive && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const creating = computed(() => route.query.create === '1')
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const routeProblem = computed(() => Object.keys(route.query).some(key => !['item', 'create'].includes(key))
  || (route.query.create !== undefined && route.query.create !== '1')
  || (route.query.item !== undefined && (!memoryIdValid(route.query.item) || creating.value))
  ? '冲刺入口参数无效，请从列表重新选择或新建。' : '')
const editorKey = computed(() => creating.value ? 'new' : selectedId.value)
const cursor = ref<string>(), cursors = ref<(string | undefined)[]>([]), page = shallowRef<MemoryCramBatchPage>(), verified = ref('')
const key = computed(() => ['memory', store.sessionEpoch, 'cram-batches', cursor.value])
let alive = true, generation = 0
watch([readable, () => store.sessionEpoch, cursor], () => { generation++; page.value = undefined; verified.value = '' }, { flush: 'sync' })
const list = useQuery({ queryKey: key, enabled: readable, staleTime: 0, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, pageCursor = cursor.value
  const parameters = new URLSearchParams({ limit: '20' })
  if (pageCursor) parameters.set('cursor', pageCursor)
  const value = await read<MemoryCramBatchPage>(`/memory/cram-batches?${parameters}`)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation || pageCursor !== cursor.value) throw new ApiError(0, 'MEMORY_CRAM_CONTEXT_CHANGED', '目录或访问条件已变化，请重新读取。')
  if (!memoryCramBatchPageValid(value, 20) || (pageCursor !== undefined && value.nextCursor === pageCursor)) throw new ApiError(502, 'MEMORY_CRAM_PAGE_INVALID', '冲刺目录格式或分页游标不一致，请重新读取。')
  verified.value = `${epoch}/${stamp}`
  return value
} })
watch([list.data, list.dataUpdatedAt, key, readable], () => {
  if (!readable.value || verified.value !== `${store.sessionEpoch}/${generation}`) return
  const value = queryClient.getQueryData<MemoryCramBatchPage>(key.value)
  if (value && memoryCramBatchPageValid(value, 20) && (cursor.value === undefined || value.nextCursor !== cursor.value)) page.value = value
}, { immediate: true })
watch(() => store.sessionEpoch, () => { cursor.value = undefined; cursors.value = [] })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++ })

function open(id: string) { if (readable.value && memoryIdValid(id)) void router.push({ name: 'memory-cram', query: { item: id } }) }
function create() { if (readable.value) void router.push({ name: 'memory-cram', query: { create: '1' } }) }
function close() { if (readable.value) void router.push({ name: 'memory-cram' }) }
function saved(value: MemoryCramBatch) { cursor.value = undefined; cursors.value = []; void queryClient.invalidateQueries({ queryKey: ['memory', store.sessionEpoch, 'cram-batches'] }); open(value.id) }
function next() { if (readable.value && !list.isFetching.value && !list.error.value && page.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = page.value.nextCursor } }
function previous() { if (readable.value && !list.isFetching.value && cursors.value.length) cursor.value = cursors.value.pop() }
function refresh() { if (readable.value && !list.isFetching.value) void list.refetch() }
</script>

<template>
  <MemoryNavigation />
  <header class="page-heading"><div><p class="section-label">知识 / 集中回忆</p><h1>冲刺练习</h1><p class="muted">选定一组卡片，在计划的时间里集中练习。每次开始前决定是否计入常规排程。</p></div><NButton type="primary" :disabled="!readable" @click="create">＋ 新建冲刺批次</NButton></header>
  <p v-if="routeProblem" class="notice error route-error" role="alert">{{ routeProblem }}<NButton text :disabled="!readable" @click="close">返回冲刺目录</NButton></p>
  <div class="cram-workspace">
    <aside class="cram-catalog" aria-label="冲刺批次目录"><header><h2>我的冲刺批次</h2><NButton text size="small" :disabled="!readable || list.isFetching.value" @click="refresh">重读</NButton></header>
      <p v-if="!readable" class="field-help" role="status">资料访问恢复后可读取批次，当前输入继续保留。</p>
      <template v-else><p v-if="list.isPending.value" role="status">正在读取冲刺批次…</p><p v-if="list.error.value" class="notice error" role="alert">{{ memoryAttemptErrorText(list.error.value) }}</p><p v-if="page && !page.items.length && !list.error.value" class="catalog-empty">本页没有冲刺批次。先选定一个想集中回忆的范围。</p>
        <div class="batch-list"><button v-for="batch in page?.items" :key="batch.id" :aria-current="!creating && selectedId === batch.id ? 'true' : undefined" @click="open(batch.id)"><strong>{{ batch.title }}</strong><span>{{ batch.cardCount }} 张卡片</span><small>{{ batch.startDate }} 至 {{ batch.endDate }}<br />{{ batch.zoneId }}</small></button></div>
        <div class="row-actions"><NButton size="small" :disabled="!cursors.length || list.isFetching.value" @click="previous">较新批次</NButton><NButton size="small" :disabled="!page?.nextCursor || list.isFetching.value || !!list.error.value" @click="next">更早批次</NButton></div>
      </template>
    </aside>
    <MemoryCramEditor v-if="!routeProblem && (creating || selectedId)" :key="editorKey" :id="creating ? undefined : selectedId" :disabled="!readable" @saved="saved" @close="close" />
    <section v-else class="cram-welcome"><h2>把这次想练的内容放在一起</h2><p>选择 1–200 张卡片，明确计划日期和时区，保存为一个固定范围。</p><NButton type="primary" :disabled="!readable" @click="create">选择冲刺范围</NButton><div class="welcome-notes"><p>计划区间不会自动开始或结束练习，过去的日期继续按原样保留。</p><p>默认作为额外练习。只有开始前明确选择计入，并满足到期与来源条件，才会在自评后更新常规排程。</p></div></section>
  </div>
</template>

<style scoped>
.cram-workspace, .route-error { max-width: 1280px; margin-left: auto; margin-right: auto; }.route-error { margin-bottom: 18px; }.cram-workspace { display: grid; grid-template-columns: 285px minmax(0, 1fr); min-height: 620px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }.cram-catalog { min-width: 0; padding: 18px 14px; border-right: 1px solid var(--line); background: var(--surface-soft); border-radius: 8px 0 0 8px; }.cram-catalog > header, .row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }.cram-catalog > header { justify-content: space-between; margin-bottom: 15px; }.cram-catalog h2 { margin: 0; font-size: 15px; }.catalog-empty { padding: 16px 0; color: var(--muted); font-size: 13px; }.batch-list { display: grid; gap: 5px; margin: 18px 0; }.batch-list > button { display: grid; gap: 7px; min-width: 0; width: 100%; padding: 12px 10px; border-radius: 5px; text-align: left; overflow-wrap: anywhere; }.batch-list > button:hover, .batch-list > button[aria-current=true] { background: var(--surface-deep); }.batch-list strong { font-size: 13px; }.batch-list span, .batch-list small { color: var(--muted); font-size: 12px; }.batch-list button:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }.cram-welcome { display: grid; gap: 20px; justify-items: start; align-self: center; max-width: 720px; padding: 42px; }.cram-welcome h2, .cram-welcome p { margin: 0; }.cram-welcome h2 { font-size: 25px; text-wrap: balance; }.cram-welcome > p, .welcome-notes { color: var(--muted); line-height: 1.8; }.welcome-notes { display: grid; gap: 12px; margin-top: 10px; padding-top: 20px; border-top: 1px solid var(--line); font-size: 13px; }@media (max-width: 1050px) { .cram-workspace { grid-template-columns: 235px minmax(0, 1fr); } }@media (max-width: 760px) { .cram-workspace { grid-template-columns: 1fr; }.cram-catalog { border-right: 0; border-bottom: 1px solid var(--line); border-radius: 8px 8px 0 0; }.batch-list { max-height: 280px; overflow-y: auto; }.cram-welcome { padding: 34px 22px; } }
</style>
