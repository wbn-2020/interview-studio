<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../api/client'
import type { ItemDetail, Schema, Version } from '../api/client'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { usePreferencesStore } from '../state/preferences'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import RIcon from '../replica/RIcon.vue'

type Source = 'ALL' | 'UNCLASSIFIED' | 'IMPORT'
type Entry = { key: string; itemId: string; title: string; source: Exclude<Source, 'ALL'>; time: string; versionId?: string; archived: boolean; fileName?: string }
const route = useRoute(), router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore(), preferences = usePreferencesStore()
const active = ref(true), visible = ref(document.visibilityState === 'visible'), checked = ref<string[]>([])
const readable = computed(() => active.value && visible.value && store.sessionActive && !access.checking && !access.challenge && !access.restricted && !store.changes.get('training-access-command')?.locked)
const source = computed<Source>(() => route.query.source === 'IMPORT' || route.query.source === 'UNCLASSIFIED' ? route.query.source : 'ALL')
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const noteCursor = computed(() => uuid(route.query.notes) ? route.query.notes : undefined)
const importCursor = computed(() => uuid(route.query.imports) ? route.query.imports : undefined)
const notes = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'inbox-notes', noteCursor.value]), enabled: readable,
  queryFn: () => read<Schema['LibraryPage']>(`/knowledge/library?${new URLSearchParams({ collection: 'UNCATEGORIZED', status: 'ACTIVE', kind: 'NOTE', limit: '20', ...(noteCursor.value ? { cursor: noteCursor.value } : {}) })}`) })
const imports = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'inbox-imports', importCursor.value]), enabled: readable,
  queryFn: () => read<Schema['KnowledgeTextImportPage']>(`/knowledge/text-imports?${new URLSearchParams({ limit: '20', ...(importCursor.value ? { cursor: importCursor.value } : {}) })}`) })
// 未分类并不等同快速记录；只有导入账本中存在确切身份时才显示“导入”来源。
const entries = computed<Entry[]>(() => {
  if (!readable.value) return []
  const importedIds = new Set(imports.data.value?.items.map(entry => entry.itemId))
  const unclassified: Entry[] = (notes.data.value?.items ?? []).filter(entry => !importedIds.has(entry.id)).map(entry => ({
    key: entry.id, itemId: entry.id, title: entry.title, source: 'UNCLASSIFIED', time: entry.updatedAt, archived: false,
  }))
  const registered: Entry[] = (imports.data.value?.items ?? []).map(entry => ({ key: entry.id, itemId: entry.itemId, title: entry.title, source: 'IMPORT', time: entry.createdAt, versionId: entry.versionId, archived: entry.itemStatus === 'ARCHIVED', fileName: entry.fileName }))
  return [...unclassified, ...registered].sort((a,b) => b.time.localeCompare(a.time) || a.key.localeCompare(b.key))
})
const rows = computed(() => entries.value.filter(entry => source.value === 'ALL' || entry.source === source.value))
const selected = computed(() => typeof route.query.entry === 'string' ? rows.value.find(entry => entry.key === route.query.entry) : rows.value[0])
const detail = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'inbox-detail', selected.value?.itemId]), enabled: computed(() => readable.value && Boolean(selected.value)),
  queryFn: () => read<ItemDetail>(`/knowledge/items/${selected.value!.itemId}`) })
const original = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'inbox-original', selected.value?.itemId, selected.value?.versionId]), enabled: computed(() => readable.value && Boolean(selected.value?.versionId)),
  queryFn: () => read<Version>(`/knowledge/items/${selected.value!.itemId}/versions/${selected.value!.versionId}`) })
const version = computed(() => selected.value?.versionId ? original.data.value : detail.data.value?.published)
const canCreateCard = computed(() => readable.value && Boolean(detail.data.value && selected.value && !selected.value.archived && detail.data.value.status === 'ACTIVE' && version.value?.id === detail.data.value.currentVersionId))
const sources: { value: Source; label: string; icon: string }[] = [{ value: 'ALL', label: '全部', icon: 'layout-grid' }, { value: 'UNCLASSIFIED', label: '未分类笔记', icon: 'file-text' }, { value: 'IMPORT', label: '导入', icon: 'cloud-upload' }]
function choose(entry: Entry) { if (readable.value) void router.replace({ query: { ...route.query, entry: entry.key } }) }
function selectSource(value: Source) { checked.value = []; void router.replace({ query: { ...route.query, source: value === 'ALL' ? undefined : value, entry: undefined } }) }
function selectAll(event: Event) { checked.value = (event.target as HTMLInputElement).checked ? rows.value.map(entry => entry.key) : [] }
function open(panel: 'preview' | 'organization' | 'relations' = 'preview') {
  if (!readable.value || !selected.value) return
  void router.push({ name: 'knowledge', query: { view: 'list', item: selected.value.itemId, edit: '1', panel, ...(selected.value.versionId && panel === 'preview' ? { version: selected.value.versionId } : {}) } })
}
function createCard() {
  if (canCreateCard.value && selected.value && version.value) void router.push({ name: 'memory-cards', query: { create: '1', sourceItemId: selected.value.itemId, sourceVersionId: version.value.id } })
}
function refresh() { if (readable.value) { void notes.refetch(); void imports.refetch(); if (selected.value) void detail.refetch(); if (selected.value?.versionId) void original.refetch() } }
function next(kind: 'notes' | 'imports', cursor: string) { checked.value = []; void router.replace({ query: { ...route.query, [kind]: cursor, entry: undefined } }) }
function visibility() { visible.value = document.visibilityState === 'visible'; if (!visible.value) checked.value = [] }
onMounted(() => document.addEventListener('visibilitychange', visibility))
onActivated(() => { active.value = true; visibility() })
onDeactivated(() => { active.value = false; checked.value = [] })
onUnmounted(() => document.removeEventListener('visibilitychange', visibility))
</script>

<template>
  <section class="knowledge-inbox">
    <header class="page-heading"><div><h1>知识收件箱</h1><p class="muted">当前已读 {{ rows.length }} 条 · 未分类笔记与导入记录</p></div><div class="row-actions"><NButton disabled title="当前来源没有已读状态，暂不能标记">全部标为已读</NButton><NButton type="primary" :disabled="!readable" @click="router.push({ name: 'knowledge', query: { view: 'list', create: 'NOTE' } })"><RIcon name="plus" />新建笔记</NButton></div></header>
    <p v-if="!readable" class="notice" role="status">资料暂不可读。请恢复会话或完成当前训练的资料访问确认。</p>
    <div v-if="readable" class="inbox-layout">
      <aside class="inbox-filters"><h2>来源筛选</h2><button v-for="item in sources" :key="item.value" :class="{ selected: source === item.value }" @click="selectSource(item.value)"><RIcon :name="item.icon" :size="27" /><span>{{ item.label }}</span><small>{{ item.value === 'ALL' ? entries.length : entries.filter(entry => entry.source === item.value).length }}</small></button><button disabled title="历史快速记录没有独立来源标记，不能与普通未分类笔记区分"><RIcon name="zap" :size="27" /><span>快速记录</span><small>—</small></button><button disabled title="训练片段尚无收件登记"><RIcon name="play" :size="27" /><span>训练片段</span><small>—</small></button><button disabled title="面后回忆尚无收件登记"><RIcon name="file-text" :size="27" /><span>面后回忆</span><small>—</small></button><p class="field-help">数量仅覆盖当前已读页。</p><NButton text @click="refresh"><RIcon name="refresh-cw" :size="19" />重新读取</NButton></aside>
      <section class="inbox-list" aria-label="待整理内容"><header><label><input type="checkbox" :checked="rows.length > 0 && rows.every(entry => checked.includes(entry.key))" :indeterminate="checked.length > 0 && !rows.every(entry => checked.includes(entry.key))" @change="selectAll" />全选</label><span>按时间倒序<RIcon name="chevron-down" :size="18" /></span></header>
        <p v-if="notes.isFetching.value || imports.isFetching.value" class="inbox-message" role="status">正在读取来源…</p><p v-if="notes.error.value || imports.error.value" class="notice error" role="alert">{{ errorText(notes.error.value || imports.error.value) }}</p>
        <article v-for="entry in rows" :key="entry.key" :class="{ selected: selected?.key === entry.key }"><input v-model="checked" type="checkbox" :value="entry.key" :aria-label="`选择${entry.title}`" /><button @click="choose(entry)"><strong>{{ entry.title }}</strong><small><RIcon :name="entry.source === 'IMPORT' ? 'cloud-upload' : 'file-text'" :size="22" />{{ entry.source === 'IMPORT' ? '导入' : '未分类笔记' }} · {{ preferences.formatDate(entry.time) }}</small></button><span class="status-pill">{{ entry.archived ? '已归档' : entry.source === 'IMPORT' ? '已登记' : '未分类' }}</span></article>
        <div v-if="!rows.length && !notes.isFetching.value && !imports.isFetching.value" class="inbox-empty"><RIcon name="inbox" :size="46" /><h2>当前没有匹配内容</h2><p>可新建笔记，或在知识库导入 Markdown／TXT。</p></div>
        <footer v-if="notes.data.value?.nextCursor || imports.data.value?.nextCursor"><NButton v-if="notes.data.value?.nextCursor" @click="next('notes', notes.data.value.nextCursor)">下一页笔记</NButton><NButton v-if="imports.data.value?.nextCursor" @click="next('imports', imports.data.value.nextCursor)">下一页导入</NButton></footer>
      </section>
      <aside class="inbox-detail" aria-label="内容详情"><header><h2>内容详情</h2><button class="studio-icon-button" title="打开确切内容" :disabled="!selected" @click="open()"><RIcon name="external-link" :size="25" /></button></header>
        <template v-if="selected"><section class="inbox-excerpt"><h3>原始摘录</h3><p v-if="detail.isFetching.value || original.isFetching.value" class="field-help" role="status">正在读取确切版本…</p><p v-if="detail.error.value || original.error.value" class="notice error">{{ errorText(detail.error.value || original.error.value) }}</p><MarkdownPreview v-if="version" :value="version.body || '这个版本尚未填写正文。'" /><p v-if="selected.fileName" class="field-help">{{ selected.fileName }} · 导入首版</p></section><section class="inbox-suggestions"><h3><RIcon name="lightbulb" :size="27" />建议操作</h3><div><NButton :disabled="!version" @click="open()"><RIcon name="file-text" />打开笔记</NButton><NButton @click="router.push({ name: 'knowledge', query: { view: 'list', create: 'CONCEPT' } })"><RIcon name="box" />新建概念</NButton><NButton :disabled="!canCreateCard" @click="createCard"><RIcon name="credit-card" />创建回忆卡</NButton><NButton :disabled="selected.archived" @click="open('organization')"><RIcon name="link" />关联已有知识</NButton></div><p class="field-help"><RIcon name="info" :size="22" />来源与知识分别保留，不会自动发布新版本。</p></section></template>
        <div v-else class="inbox-empty"><RIcon name="file-text" :size="42" /><p>{{ route.query.entry ? '当前页没有该条目，请核对来源与分页。' : '从列表选择一条内容。' }}</p></div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.inbox-layout { display: grid; grid-template-columns: 232px minmax(340px,1fr) minmax(390px,1fr); gap: 12px; align-items: stretch; min-height: 700px; }.inbox-filters,.inbox-list,.inbox-detail { min-width: 0; border: 1px solid var(--line); border-radius: 20px; background: var(--surface); }.inbox-filters { padding: 24px 10px; }.inbox-filters h2 { font-size: 24px; padding: 0 14px 18px; margin: 0; }.inbox-filters > button { display: flex; align-items: center; gap: 14px; width: 100%; padding: 15px 16px; min-height: 63px; border-radius: 15px; text-align: left; font-size: 22px; }.inbox-filters > button.selected { background: var(--brand-soft); color: var(--brand-strong); }.inbox-filters > button:disabled { opacity: .45; cursor: not-allowed; }.inbox-filters > button > span { flex: 1; }.inbox-filters small { font-size: 20px; }.inbox-filters > .field-help { margin: 22px 14px 8px; }.inbox-list > header { display: flex; align-items: center; justify-content: space-between; padding: 18px 22px; font-size: 20px; color: var(--muted); gap: 16px; }.inbox-list header label { display: flex; align-items: center; gap: 13px; }.inbox-list header > span { display: inline-flex; align-items: center; gap: 8px; padding: 6px 12px; border-radius: 20px; background: var(--surface-soft); font-size: 17px; }.inbox-list input { width: 23px; height: 23px; accent-color: var(--brand); flex: none; }.inbox-list > article { display: flex; align-items: center; gap: 18px; min-height: 111px; margin: 0 5px; padding: 20px; border: 1px solid transparent; border-bottom-color: var(--line); }.inbox-list > article.selected { border-color: var(--brand); border-radius: 18px; background: var(--brand-soft); }.inbox-list article > button { min-width: 0; flex: 1; display: grid; gap: 10px; text-align: left; }.inbox-list article strong { font-size: 23px; line-height: 1.6; font-weight: 500; overflow-wrap: anywhere; }.inbox-list article small { display: flex; align-items: center; gap: 9px; font-size: 17px; color: var(--muted); }.inbox-list article small svg { flex: none; }.inbox-list .status-pill { flex: none; align-self: start; margin-top: 4px; }.inbox-detail { padding: 0 23px; }.inbox-detail > header { display: flex; justify-content: space-between; align-items: center; padding: 14px 0; border-bottom: 1px solid var(--line); }.inbox-detail h2 { font-size: 25px; margin: 0; }.inbox-excerpt { padding: 24px 0; border-bottom: 1px solid var(--line); }.inbox-detail h3 { font-size: 22px; display: flex; align-items: center; gap: 12px; margin: 0 0 18px; }.inbox-excerpt :deep(.markdown-preview) { font-size: 21px; line-height: 1.85; max-height: 290px; overflow-y: auto; overflow-wrap: anywhere; }.inbox-suggestions { padding: 23px 0; }.inbox-suggestions > div { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 8px; }.inbox-suggestions :deep(.n-button) { height: auto; min-height: 64px; border-radius: 15px; font-size: 20px; }.inbox-suggestions :deep(.n-button__content) { gap: 10px; white-space: normal; }.inbox-suggestions > p { display: flex; align-items: start; gap: 10px; margin-top: 25px; padding: 12px; border-radius: 12px; background: var(--surface-soft); }.inbox-suggestions > p svg { flex: none; }.inbox-empty { display: grid; align-content: center; justify-items: center; text-align: center; gap: 20px; min-height: 410px; padding: 30px; color: var(--muted); }.inbox-empty h2 { font-size: 23px; color: var(--ink); }.inbox-empty p { font-size: 18px; }.inbox-list > footer { display: flex; flex-wrap: wrap; gap: 10px; padding: 22px; }.inbox-message { padding: 16px 22px; font-size: 16px; color: var(--muted); }
@media(max-width:1350px) { .inbox-layout { grid-template-columns: 200px minmax(0,1fr); }.inbox-detail { grid-column: 2; }.inbox-list article strong { font-size: 21px; } }
@media(max-width:800px) { .inbox-layout { grid-template-columns: minmax(0,1fr); }.inbox-detail { grid-column: 1; }.inbox-filters { display: flex; flex-wrap: wrap; gap: 8px; }.inbox-filters h2 { width: 100%; }.inbox-filters > button { width: auto; font-size: 18px; }.inbox-filters > .field-help { width: 100%; }.inbox-list .status-pill { font-size: 13px; }.inbox-suggestions :deep(.n-button) { font-size: 17px; } }
</style>
