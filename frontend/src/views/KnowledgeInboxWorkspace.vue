<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { ItemDetail, Schema, Version } from '../api/client'
import { referenceIdValid } from '../api/knowledgeReferences'
import { memoryUtcMicros } from '../api/memoryReview'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { usePreferencesStore } from '../state/preferences'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import RIcon from '../replica/RIcon.vue'

type Source = 'ALL' | 'UNCLASSIFIED' | 'IMPORT'
type Entry = { key: string; itemId: string; title: string; source: Exclude<Source, 'ALL'>; time: string; versionId?: string; archived: boolean; fileName?: string }
const route = useRoute(), router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore(), preferences = usePreferencesStore()
const readScope = useId()
const active = ref(true), visible = ref(document.visibilityState === 'visible'), checked = ref<string[]>([]), generation = ref(0)
const listBody = ref<HTMLElement>(), detailBody = ref<HTMLElement>()
const routeProblem = computed(() => {
  const q = route.query
  if (Object.keys(q).some(key => !['source', 'entry', 'notes', 'imports', 'order'].includes(key))
    || q.source !== undefined && !['ALL', 'UNCLASSIFIED', 'IMPORT'].includes(q.source as string)
    || q.order !== undefined && q.order !== 'asc' && q.order !== 'desc') return '收件箱筛选或排序参数无效，请返回初始列表。'
  if (['entry', 'notes', 'imports'].some(key => q[key] !== undefined && !referenceIdValid(q[key]))) return '收件箱条目或分页参数无效，请返回初始列表。'
  return ''
})
const readable = computed(() => active.value && visible.value && !routeProblem.value && store.sessionActive && !access.checking && !access.challenge && !access.restricted && !store.changes.get('training-access-command')?.locked)
const source = computed<Source>(() => route.query.source === 'IMPORT' || route.query.source === 'UNCLASSIFIED' ? route.query.source : 'ALL')
const order = computed(() => route.query.order === 'asc' ? 'asc' : 'desc')
const noteCursor = computed(() => referenceIdValid(route.query.notes) ? route.query.notes : undefined)
const importCursor = computed(() => referenceIdValid(route.query.imports) ? route.query.imports : undefined)
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  generation.value++
  checked.value = []
}, { flush: 'sync' })
watch([source, noteCursor, importCursor], () => { checked.value = [] }, { flush: 'sync' })
watch([source, order, noteCursor, importCursor], () => { if (listBody.value) listBody.value.scrollTop = 0 }, { flush: 'post' })
function preview<T>(key: string, path: () => string | undefined, valid: (value: T) => boolean) {
  const query = useQuery({
    queryKey: computed(() => ['knowledge', store.sessionEpoch, 'inbox', readScope, key, path(), generation.value]),
    enabled: computed(() => readable.value && Boolean(path())),
    queryFn: async () => {
      const url = path(), stamp = generation.value, epoch = currentTrainingAccessEpoch()
      if (!url) throw new ApiError(0, 'INBOX_NOT_READY', '收件箱条目尚未就绪。')
      const result = await read<T>(url)
      if (!readable.value || stamp !== generation.value || epoch !== currentTrainingAccessEpoch() || url !== path()) {
        throw new ApiError(0, 'INBOX_CONTEXT_CHANGED', '资料读取条件已变化，请恢复访问后重新读取。')
      }
      if (!valid(result)) throw new ApiError(502, 'INBOX_RESPONSE_INVALID', '收件箱条目或确切版本无法核对，请重新读取。')
      return { stamp, epoch, url, result }
    },
  })
  // 标题、正文缓存须绑定本次可见性与资料授权，解除遮挡后重新读取。
  return {
    data: computed(() => readable.value && query.data.value?.stamp === generation.value && query.data.value.epoch === currentTrainingAccessEpoch()
      && query.data.value.url === path() ? query.data.value.result : undefined),
    pending: query.isFetching, error: query.error,
  }
}
const pageValid = (page: { items: { id: string }[]; nextCursor: string | null }) => Boolean(page && Array.isArray(page.items) && page.items.length <= 20
  && page.items.every(entry => entry && referenceIdValid(entry.id)) && new Set(page.items.map(entry => entry.id)).size === page.items.length
  && (page.nextCursor === null || referenceIdValid(page.nextCursor)))
const notes = preview<Schema['LibraryPage']>('notes',
  () => `/knowledge/library?${new URLSearchParams({ collection: 'UNCATEGORIZED', status: 'ACTIVE', kind: 'NOTE', limit: '20', ...(noteCursor.value ? { cursor: noteCursor.value } : {}) })}`,
  page => pageValid(page) && page.items.every(entry => entry.kind === 'NOTE' && entry.status === 'ACTIVE'
    && typeof entry.title === 'string' && memoryUtcMicros(entry.updatedAt) !== undefined))
const imports = preview<Schema['KnowledgeTextImportPage']>('imports',
  () => `/knowledge/text-imports?${new URLSearchParams({ limit: '20', ...(importCursor.value ? { cursor: importCursor.value } : {}) })}`,
  page => pageValid(page) && page.items.every(entry => referenceIdValid(entry.itemId) && referenceIdValid(entry.versionId)
    && typeof entry.title === 'string' && typeof entry.fileName === 'string' && ['ACTIVE', 'ARCHIVED'].includes(entry.itemStatus)
    && memoryUtcMicros(entry.createdAt) !== undefined))
// 未分类并不等同快速记录；只有导入账本中存在确切身份时才显示“导入”来源。
const entries = computed<Entry[]>(() => {
  if (!readable.value) return []
  const importedIds = new Set(imports.data.value?.items.map(entry => entry.itemId))
  const unclassified: Entry[] = (notes.data.value?.items ?? []).filter(entry => !importedIds.has(entry.id)).map(entry => ({
    key: entry.id, itemId: entry.id, title: entry.title, source: 'UNCLASSIFIED', time: entry.updatedAt, archived: false,
  }))
  const registered: Entry[] = (imports.data.value?.items ?? []).map(entry => ({ key: entry.id, itemId: entry.itemId, title: entry.title, source: 'IMPORT', time: entry.createdAt, versionId: entry.versionId, archived: entry.itemStatus === 'ARCHIVED', fileName: entry.fileName }))
  return [...unclassified, ...registered]
})
const rows = computed(() => entries.value.filter(entry => source.value === 'ALL' || entry.source === source.value).sort((a, b) => {
  const left = memoryUtcMicros(a.time)!, right = memoryUtcMicros(b.time)!
  const time = left < right ? -1 : left > right ? 1 : 0
  return (order.value === 'asc' ? time : -time) || a.key.localeCompare(b.key)
}))
const selected = computed(() => typeof route.query.entry === 'string' ? rows.value.find(entry => entry.key === route.query.entry) : rows.value[0])
watch(rows, values => { checked.value = checked.value.filter(key => values.some(entry => entry.key === key)) }, { flush: 'sync' })
watch(() => selected.value?.key, () => { if (detailBody.value) detailBody.value.scrollTop = 0 }, { flush: 'post' })
const detail = preview<ItemDetail>('detail', () => selected.value ? `/knowledge/items/${selected.value.itemId}` : undefined,
  item => Boolean(item && item.id === selected.value?.itemId && item.kind === 'NOTE' && ['ACTIVE', 'ARCHIVED'].includes(item.status)
    && referenceIdValid(item.currentVersionId) && item.published?.id === item.currentVersionId
    && typeof item.published.title === 'string' && typeof item.published.body === 'string'))
const original = preview<Version>('original',
  () => selected.value?.versionId ? `/knowledge/items/${selected.value.itemId}/versions/${selected.value.versionId}` : undefined,
  version => Boolean(version && version.id === selected.value?.versionId && version.number === 1 && typeof version.title === 'string' && typeof version.body === 'string'))
const version = computed(() => selected.value?.versionId ? original.data.value : detail.data.value?.published)
const canCreateCard = computed(() => readable.value && Boolean(detail.data.value && selected.value && !selected.value.archived && detail.data.value.status === 'ACTIVE' && version.value?.id === detail.data.value.currentVersionId))
const sources: { value: Source; label: string; icon: string }[] = [{ value: 'ALL', label: '全部', icon: 'layout-grid' }, { value: 'UNCLASSIFIED', label: '未分类笔记', icon: 'file-text' }, { value: 'IMPORT', label: '导入', icon: 'cloud-upload' }]
function sourceCount(value: Source) {
  const unavailable = imports.error.value || imports.pending.value || value !== 'IMPORT' && (notes.error.value || notes.pending.value)
  return unavailable ? '—' : value === 'ALL' ? entries.value.length : entries.value.filter(entry => entry.source === value).length
}
function choose(entry: Entry) { if (readable.value) void router.replace({ query: { ...route.query, entry: entry.key } }) }
function selectSource(value: Source) { checked.value = []; void router.replace({ query: { ...route.query, source: value === 'ALL' ? undefined : value, entry: undefined } }) }
function selectOrder(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (readable.value && (value === 'asc' || value === 'desc')) void router.replace({ query: { ...route.query, order: value === 'desc' ? undefined : value, entry: selected.value?.key ?? route.query.entry } })
}
function selectAll(event: Event) { checked.value = (event.target as HTMLInputElement).checked ? rows.value.map(entry => entry.key) : [] }
function open(panel: 'preview' | 'organization' | 'relations' = 'preview') {
  if (!readable.value || !selected.value) return
  void router.push({ name: 'knowledge', query: { view: 'list', item: selected.value.itemId, edit: '1', panel, ...(selected.value.versionId && panel === 'preview' ? { version: selected.value.versionId } : {}) } })
}
function createCard() {
  if (canCreateCard.value && selected.value && version.value) void router.push({ name: 'memory-cards', query: { create: '1', sourceItemId: selected.value.itemId, sourceVersionId: version.value.id } })
}
function refresh() { if (readable.value) { checked.value = []; generation.value++ } }
function next(kind: 'notes' | 'imports', cursor: string) { checked.value = []; void router.replace({ query: { ...route.query, [kind]: cursor, entry: undefined } }) }
function visibility() { visible.value = document.visibilityState === 'visible'; if (!visible.value) checked.value = [] }
onMounted(() => document.addEventListener('visibilitychange', visibility))
onActivated(() => { active.value = true; visibility() })
onDeactivated(() => { active.value = false; checked.value = [] })
onUnmounted(() => document.removeEventListener('visibilitychange', visibility))
</script>

<template>
  <section class="knowledge-inbox">
    <header class="page-heading"><div><h1>知识收件箱</h1><p class="muted">当前已读取 {{ rows.length }} 条 · 未分类笔记与导入记录</p></div><div class="row-actions"><NButton type="primary" :disabled="!readable" @click="router.push({ name: 'knowledge', query: { view: 'list', create: 'NOTE' } })"><RIcon name="plus" />新建笔记</NButton></div></header>
    <p v-if="routeProblem" class="notice error" role="alert">{{ routeProblem }}<RouterLink class="text-link" :to="{ name: 'knowledge-inbox' }">打开初始列表</RouterLink></p>
    <p v-else-if="!readable" class="notice" role="status">资料暂不可读。请恢复会话或完成当前训练的资料访问确认。</p>
    <div v-if="readable" class="inbox-layout">
      <aside class="inbox-filters"><h2>来源筛选</h2><button v-for="item in sources" :key="item.value" :class="{ selected: source === item.value }" :aria-pressed="source === item.value" @click="selectSource(item.value)"><RIcon :name="item.icon" :size="27" /><span>{{ item.label }}</span><small>{{ sourceCount(item.value) }}</small></button><button disabled title="历史快速记录没有独立来源标记，不能与普通未分类笔记区分"><RIcon name="zap" :size="27" /><span>快速记录</span><small>—</small></button><button disabled title="训练片段尚无收件登记"><RIcon name="play" :size="27" /><span>训练片段</span><small>—</small></button><button disabled title="面后回忆尚无收件登记"><RIcon name="file-text" :size="27" /><span>面后回忆</span><small>—</small></button><p class="field-help">数量与排序仅覆盖当前已读取页。</p><NButton text @click="refresh"><RIcon name="refresh-cw" :size="19" />重新读取</NButton></aside>
      <section class="inbox-list" aria-label="待整理内容">
        <header><label><input type="checkbox" :checked="rows.length > 0 && rows.every(entry => checked.includes(entry.key))" :indeterminate="checked.length > 0 && !rows.every(entry => checked.includes(entry.key))" @change="selectAll" />全选</label><label class="inbox-order"><select :value="order" aria-label="收件箱排序" @change="selectOrder"><option value="desc">按时间倒序</option><option value="asc">按时间顺序</option></select><RIcon name="chevron-down" :size="18" /></label></header>
        <div ref="listBody" class="inbox-rows">
          <p v-if="notes.pending.value || imports.pending.value" class="inbox-message" role="status">正在读取来源…</p><p v-if="notes.error.value || imports.error.value" class="notice error" role="alert">{{ errorText(notes.error.value || imports.error.value) }}</p>
          <article v-for="entry in rows" :key="entry.key" :class="{ selected: selected?.key === entry.key }"><input v-model="checked" type="checkbox" :value="entry.key" :aria-label="`选择${entry.title}`" /><button :aria-pressed="selected?.key === entry.key" @click="choose(entry)"><strong>{{ entry.title }}</strong><small><RIcon :name="entry.source === 'IMPORT' ? 'cloud-upload' : 'file-text'" :size="22" />{{ entry.source === 'IMPORT' ? '导入' : '未分类笔记' }} · {{ preferences.formatDate(entry.time) }}</small></button><span class="status-pill">{{ entry.archived ? '已归档' : entry.source === 'IMPORT' ? '已登记' : '未分类' }}</span></article>
          <div v-if="!rows.length && !notes.pending.value && !imports.pending.value && !notes.error.value && !imports.error.value" class="inbox-empty"><RIcon name="inbox" :size="46" /><h2>当前没有匹配内容</h2><p>可新建笔记，或在知识库导入 Markdown／TXT。</p></div>
        </div>
        <footer v-if="notes.data.value?.nextCursor || imports.data.value?.nextCursor"><NButton v-if="notes.data.value?.nextCursor" @click="next('notes', notes.data.value.nextCursor)">下一页笔记</NButton><NButton v-if="imports.data.value?.nextCursor" @click="next('imports', imports.data.value.nextCursor)">下一页导入</NButton><NButton v-if="noteCursor || importCursor" @click="router.replace({ query: { ...route.query, notes: undefined, imports: undefined, entry: undefined } })">回到首页</NButton></footer>
        <footer v-else-if="noteCursor || importCursor"><NButton @click="router.replace({ query: { ...route.query, notes: undefined, imports: undefined, entry: undefined } })">回到首页</NButton></footer>
      </section>
      <aside ref="detailBody" class="inbox-detail" aria-label="内容详情"><header><h2>内容详情</h2><button class="studio-icon-button" title="打开确切内容" :disabled="!selected" @click="open()"><RIcon name="external-link" :size="25" /></button></header>
        <template v-if="selected"><section class="inbox-excerpt"><h3>原始摘录</h3><p v-if="detail.pending.value || original.pending.value" class="field-help" role="status">正在读取确切版本…</p><p v-if="detail.error.value || original.error.value" class="notice error" role="alert">{{ errorText(detail.error.value || original.error.value) }}</p><MarkdownPreview v-if="version" :value="version.body || '这个版本尚未填写正文。'" /><p v-if="selected.fileName" class="field-help">{{ selected.fileName }} · 导入首版</p></section><section class="inbox-suggestions"><h3><RIcon name="lightbulb" :size="27" />建议操作</h3><div><NButton :disabled="!version" @click="open()"><RIcon name="file-text" :size="28" />打开笔记</NButton><NButton @click="router.push({ name: 'knowledge', query: { view: 'list', create: 'CONCEPT' } })"><RIcon name="box" :size="30" />新建概念</NButton><NButton :disabled="!canCreateCard" @click="createCard"><RIcon name="credit-card" :size="27" />创建回忆卡</NButton><NButton :disabled="selected.archived" @click="open('organization')"><RIcon name="link" :size="29" />关联已有知识</NButton></div><p class="field-help"><RIcon name="info" :size="24" />来源与知识分别保留，不会自动发布新版本。</p></section></template>
        <div v-else class="inbox-empty"><RIcon name="file-text" :size="42" /><p>{{ route.query.entry ? '当前页没有该条目，请核对来源与分页。' : '从列表选择一条内容。' }}</p></div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.inbox-layout { display: grid; grid-template-columns: 180px minmax(0,1fr) minmax(280px,.8fr); gap: 16px; align-items: start; }
.inbox-filters,.inbox-list,.inbox-detail { min-width: 0; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); }
.inbox-filters { padding: 16px 10px; }.inbox-filters h2 { font-size: 14px; padding: 0 8px 12px; }.inbox-filters > button { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px; min-height: 36px; border-radius: 5px; text-align: left; font-size: 12px; }.inbox-filters > button.selected { background: var(--brand-soft); color: var(--brand-strong); }.inbox-filters > button > span { flex: 1; }.inbox-filters svg { width: 17px; height: 17px; color: var(--brand); }.inbox-filters small { font-size: 12px; }.inbox-filters > .field-help { margin: 14px 8px 0; }
.inbox-list { display: flex; flex-direction: column; min-height: 440px; }.inbox-list > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 12px 16px; border-bottom: 1px solid var(--line); font-size: 12px; color: var(--muted); }.inbox-list header label { display: flex; align-items: center; gap: 8px; }.inbox-order { position: relative; }.inbox-order select { appearance: none; padding: 5px 24px 5px 8px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); color: var(--ink); font-size: 12px; }.inbox-order svg { position: absolute; right: 5px; width: 14px; pointer-events: none; }.inbox-list input { width: 15px; height: 15px; accent-color: var(--brand); flex: none; }
.inbox-rows { min-height: 0; max-height: 560px; overflow-y: auto; padding: 0 8px; }.inbox-rows > article { display: grid; grid-template-columns: 16px minmax(0,1fr) auto; align-items: start; gap: 10px; padding: 14px 8px; border-bottom: 1px solid var(--line); }.inbox-rows > article.selected { background: var(--brand-soft); }.inbox-rows article > button { display: grid; min-width: 0; gap: 5px; text-align: left; }.inbox-rows article strong { font-size: 14px; line-height: 1.6; font-weight: 500; overflow-wrap: anywhere; }.inbox-rows article small { display: flex; align-items: start; gap: 5px; font-size: 11px; color: var(--muted); overflow-wrap: anywhere; }.inbox-rows article small svg { width: 14px; height: 16px; flex: none; }
.inbox-detail { padding: 18px; }.inbox-detail > header { display: flex; align-items: center; justify-content: space-between; padding-bottom: 12px; border-bottom: 1px solid var(--line); }.inbox-detail h2 { font-size: 16px; }.inbox-detail h3 { display: flex; align-items: center; gap: 7px; font-size: 14px; margin-bottom: 10px; }.inbox-excerpt { padding: 18px 0; border-bottom: 1px solid var(--line); }.inbox-excerpt :deep(.markdown-preview) { font-size: var(--studio-font-size); max-height: 300px; overflow-y: auto; }.inbox-excerpt :deep(.markdown-preview > :first-child) { margin-top: 0; }.inbox-excerpt :deep(.markdown-preview > :last-child) { margin-bottom: 0; }
.inbox-suggestions { padding-top: 18px; }.inbox-suggestions > div { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }.inbox-suggestions > p { display: flex; align-items: start; gap: 6px; margin-top: 16px; color: var(--muted); font-size: 12px; }.inbox-suggestions > p svg { width: 16px; flex: none; }
.inbox-empty { display: grid; align-content: start; justify-items: start; gap: 12px; min-height: 180px; padding: 28px 16px; color: var(--muted); }.inbox-empty > svg { width: 25px; height: 25px; }.inbox-empty h2 { font-size: 16px; color: var(--ink); }.inbox-empty p { font-size: 13px; }.inbox-list > footer { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px; border-top: 1px solid var(--line); }.inbox-message { padding: 14px; font-size: 13px; color: var(--muted); }
@media(max-width:1200px) { .inbox-layout { grid-template-columns: 170px minmax(0,1fr); }.inbox-detail { grid-column: 2; }.inbox-filters { grid-row: span 2; } }
@media(max-width:700px) { .inbox-layout { grid-template-columns: minmax(0,1fr); }.inbox-detail { grid-column: 1; }.inbox-filters { grid-row: auto; display: flex; flex-wrap: wrap; gap: 5px; }.inbox-filters h2, .inbox-filters > .field-help { width: 100%; }.inbox-filters > button { width: auto; }.inbox-list { min-height: 240px; }.inbox-rows { max-height: 330px; } }
</style>
