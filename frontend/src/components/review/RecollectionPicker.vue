<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { NButton, NInput } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { RecollectionPage } from '../../api/recollection'
import { recollectionBaselineLabels } from '../../api/recollection'
import { reviewIdValid } from '../../api/review'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

type Summary = RecollectionPage['items'][number]
type Page = { cursor?: string; value: RecollectionPage }
const props = defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ select: [value: Summary] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), viewId = useId()
const active = ref(true), query = ref(''), applied = ref(''), loading = ref(false), problem = ref('')
const currentPage = shallowRef<Page>(), previous = shallowRef<Page[]>([])
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.restricted
  && !access.challenge && !store.changes.get('training-access-command')?.locked)
const queryValid = computed(() => Array.from(query.value).length <= 100 && !/[\u0000\uD800-\uDFFF]/u.test(query.value))
const page = computed(() => currentPage.value?.value)
const selectable = computed(() => readable.value && !loading.value && applied.value === query.value)
let alive = true, generation = 0, sequence = 0

function reset() { generation++; sequence++; currentPage.value = undefined; previous.value = []; loading.value = false; problem.value = ''; applied.value = '' }
watch([query, readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], reset, { flush: 'sync' })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; sequence++ })

function pageValid(value: RecollectionPage, cursor?: string): boolean {
  const text = (value: unknown, maximum: number, required = true): value is string => typeof value === 'string'
    && Array.from(value).length <= maximum && !/[\u0000\uD800-\uDFFF]/u.test(value)
    && (!required || /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value))
  if (!value || !Array.isArray(value.items) || value.items.length > 20 || value.nextCursor !== null && !reviewIdValid(value.nextCursor)) return false
  return value.items.every((item, index) => item && reviewIdValid(item.id) && reviewIdValid(item.versionId) && item.status === 'ACTIVE'
    && text(item.title, 200) && Object.hasOwn(recollectionBaselineLabels, item.baselineKind)
    && Number.isSafeInteger(item.revision) && item.revision >= 0 && item.revision <= 1000
    && Number.isSafeInteger(item.versionNumber) && item.versionNumber >= 1 && item.versionNumber <= 1000 && item.versionNumber <= item.revision + 1
    && (item.interviewId === null ? item.interviewTitle === null : reviewIdValid(item.interviewId) && text(item.interviewTitle, 200))
    && typeof item.updatedAt === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(item.updatedAt)
    && Number.isFinite(Date.parse(item.updatedAt)) && (index ? item.id > value.items[index - 1]!.id : !cursor || item.id > cursor))
    && (value.nextCursor === null || value.items.length === 20 && value.nextCursor === value.items.at(-1)?.id)
}
async function load(next = false) {
  if (!readable.value || loading.value || !queryValid.value || next && (!page.value?.nextCursor || applied.value !== query.value)) return
  const origin = currentPage.value, cursor = next ? page.value?.nextCursor ?? undefined : undefined
  const signature = query.value, epoch = store.sessionEpoch, stamp = generation, ticket = ++sequence
  const parameters = new URLSearchParams({ status: 'ACTIVE', limit: '20' })
  if (signature) parameters.set('query', signature)
  if (cursor) parameters.set('cursor', cursor)
  loading.value = true; problem.value = ''
  if (!next) { currentPage.value = undefined; previous.value = []; applied.value = '' }
  const current = () => alive && readable.value && epoch === store.sessionEpoch && stamp === generation && ticket === sequence && signature === query.value
  try {
    const result = await read<RecollectionPage>(`/review/recollections?${parameters}`)
    if (!current()) return
    if (!pageValid(result, cursor)) throw new ApiError(502, 'RELATION_RECOLLECTION_DIRECTORY_INVALID', '回忆目录的当前版本或分页信息无法核对，请重新查询。')
    if (next && origin) previous.value = [...previous.value, origin]
    currentPage.value = { cursor, value: result }; applied.value = signature
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (current()) loading.value = false }
}
function back() {
  if (!selectable.value || !previous.value.length) return
  currentPage.value = previous.value.at(-1); previous.value = previous.value.slice(0, -1); problem.value = ''
}
function choose(value: Summary) { if (selectable.value && page.value?.items.includes(value)) emit('select', { ...value }) }
</script>

<template>
  <section class="recollection-picker" aria-label="选择当前面后回忆">
    <p class="field-help">先查询使用中的回忆目录。选择后才读取当前版本的原文字段，并由你明确选取片段。</p>
    <form class="search-form" @submit.prevent="load()"><label :for="`${viewId}-query`">回忆标题<NInput v-model:value="query" :disabled="!readable || loading" :input-props="{ id: `${viewId}-query`, 'aria-label': '回忆标题筛选' }" placeholder="留空查询全部使用中的回忆" /></label><NButton attr-type="submit" :loading="loading" :disabled="!readable || loading || !queryValid">查询回忆</NButton></form>
    <p v-if="!queryValid" class="notice error" role="alert">标题筛选最多 100 个完整字符。</p><p v-if="loading" role="status">正在读取回忆目录…</p><p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
    <template v-if="page && readable && !loading"><p v-if="!page.items.length" class="field-help" role="status">当前条件下没有可选回忆，可调整标题后查询。</p>
      <ul class="recollection-results"><li v-for="item in page.items" :key="item.id"><div><strong>{{ item.title }}</strong><span>v{{ item.versionNumber }} · 来源修订 {{ item.revision }} · {{ recollectionBaselineLabels[item.baselineKind] }}</span><small>{{ new Date(item.updatedAt).toLocaleString('zh-CN') }}</small></div><NButton size="small" :disabled="!selectable" :aria-label="`选择 ${item.title} 的当前版本 ${item.versionNumber}`" @click="choose(item)">选择当前版本</NButton></li></ul>
      <div class="row-actions"><NButton v-if="previous.length" size="small" :disabled="!selectable" @click="back">上一页</NButton><NButton v-if="page.nextCursor" size="small" :disabled="!selectable" @click="load(true)">下一页</NButton></div>
    </template>
  </section>
</template>

<style scoped>
.recollection-picker { display: grid; gap: 12px; min-width: 0; padding: 14px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }p { margin: 0; }.search-form { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; }.search-form label { display: grid; gap: 7px; min-width: 0; flex: 1 1 220px; font-size: 13px; }.recollection-results { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }.recollection-results li { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--line); min-width: 0; }.recollection-results li > div { display: grid; gap: 6px; min-width: 0; flex: 1 1 220px; }.recollection-results strong { font-size: 13px; overflow-wrap: anywhere; }.recollection-results span, small { font-size: 12px; color: var(--muted); overflow-wrap: anywhere; }.field-help, .notice { overflow-wrap: anywhere; }.row-actions { display: flex; flex-wrap: wrap; gap: 10px; }
</style>
