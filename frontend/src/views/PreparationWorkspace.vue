<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { errorText, read } from '../api/client'
import type { PreparationFocus, PreparationItem, PreparationKind, PreparationPage } from '../api/preparation'
import { kindLabels, verificationLabels } from '../api/preparation'
import { queryClient } from '../api/queryClient'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
const PreparationEditor = defineAsyncComponent(() => import('../components/prepare/PreparationEditor.vue'))

const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const focusWriter = useCommand()
const kinds: PreparationKind[] = ['TARGET', 'PROJECT', 'FACT', 'EXPRESSION']
const kind = computed<PreparationKind>(() => kinds.includes(route.query.kind as PreparationKind) ? route.query.kind as PreparationKind : 'TARGET')
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const creating = computed(() => route.query.create === '1')
const status = ref('ACTIVE')
const favorites = ref(false)
const input = ref('')
const search = ref('')
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const focusMessage = ref('')
const statusOptions = [{ label: '活跃材料', value: 'ACTIVE' }, { label: '已归档', value: 'ARCHIVED' }]
const list = useQuery({
  queryKey: computed(() => ['preparation', store.sessionEpoch, 'items', kind.value, status.value, favorites.value, search.value, cursor.value]),
  enabled: computed(() => store.sessionActive),
  queryFn: () => {
    const parameters = new URLSearchParams({ kind: kind.value, status: status.value, q: search.value, limit: '20' })
    if (favorites.value) parameters.set('favorite', 'true')
    if (cursor.value) parameters.set('cursor', cursor.value)
    return read<PreparationPage>(`/prepare/items?${parameters}`)
  },
})
const focus = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'focus']), enabled: computed(() => store.sessionActive), queryFn: () => read<PreparationFocus>('/prepare/focus') })
const focusedTarget = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'focus-target', focus.data.value?.targetId]), enabled: computed(() => store.sessionActive && Boolean(focus.data.value?.targetId)), queryFn: () => read<PreparationItem>(`/prepare/items/${focus.data.value!.targetId}`) })
watch([kind, status, favorites], () => { cursor.value = undefined; cursors.value = [] })
watch(focusWriter.locked, locked => store.changes.set('prepare-focus', { dirty: false, locked }), { immediate: true })
onUnmounted(() => store.changes.delete('prepare-focus'))

function open(id: string, itemKind: PreparationKind = kind.value, versionId?: string) { void router.push({ name: 'prepare', query: { kind: itemKind, item: id, version: versionId } }) }
function close() { void router.push({ name: 'prepare', query: { kind: kind.value } }) }
function selectKind(value: PreparationKind) { void router.push({ name: 'prepare', query: { kind: value } }) }
function create() { void router.push({ name: 'prepare', query: { kind: kind.value, create: '1' } }) }
function created(value: PreparationItem) { open(value.id, value.kind) }
function refresh() { void queryClient.invalidateQueries({ queryKey: ['preparation'] }) }
function reloadFocus() { void focus.refetch(); if (focus.data.value?.targetId) void focusedTarget.refetch() }
function submit() { search.value = input.value.trim(); cursor.value = undefined; cursors.value = [] }
function next() { if (list.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = list.data.value.nextCursor } }
function changeFocus(targetId: string | null) {
  if (!focus.data.value || focusWriter.locked.value || !store.sessionActive) return
  void focusWriter.run<PreparationFocus>('/prepare/focus', 'PUT', { expectedRevision: focus.data.value.revision, targetId }, value => {
    queryClient.setQueryData(['preparation', store.sessionEpoch, 'focus'], value)
    focusMessage.value = value.targetId ? '当前关注目标已保存，既有材料与历史不移动。' : '已取消当前关注，仍可独立准备和训练。'
    refresh()
  })
}
const guidance: Record<PreparationKind, { title: string; text: string }> = {
  TARGET: { title: '把准备落到具体要求', text: '保存岗位与 JD，拆出需要解释、排错、取舍或表达的考察项，再关联现有项目、事实和知识。' },
  PROJECT: { title: '先记录项目，再整理本人事实', text: '项目概述保存共同背景。本人负责过什么、参与过什么以及仍需核实的指标，分别记为项目事实。' },
  FACT: { title: '让每一段经历都有边界', text: '选择所属项目，写清场景、问题、本人动作与结果。保存后显式核实；核实不会授予模型外发权限。' },
  EXPRESSION: { title: '从真实依据组织表达', text: '选择当前已核实的事实版本，组织长短项目介绍、STAR 或技术取舍。来源更正时保留旧文，提醒你复核。' },
}
</script>

<template>
  <header class="page-heading"><div><p class="section-label">从目标到真实经历</p><h1>准备工作台</h1><p class="muted">整理岗位要求、项目依据和可复用的表达。</p></div><NButton type="primary" @click="create">＋ 新建{{ kindLabels[kind] }}</NButton></header>
  <section class="prepare-focus" aria-label="当前关注目标"><div><span class="field-help">当前关注</span><button v-if="focusedTarget.data.value" class="text-link" @click="open(focusedTarget.data.value.id, 'TARGET')">{{ focusedTarget.data.value.currentVersion.content.title }}</button><strong v-else>{{ focus.data.value?.targetId ? '正在读取目标…' : '尚未指定目标' }}</strong><span class="field-help">关注状态不改变材料版本或历史条件。</span></div><NButton v-if="focus.data.value?.targetId" size="small" :disabled="focusWriter.locked.value || !store.sessionActive" @click="changeFocus(null)">取消关注</NButton></section>
  <p v-if="focus.error.value || focusedTarget.error.value" class="notice error" role="alert">{{ errorText(focus.error.value ?? focusedTarget.error.value) }}<NButton text @click="reloadFocus">重试读取</NButton></p><div v-if="focusWriter.error.value" class="notice error" role="alert"><p>{{ errorText(focusWriter.error.value) }}</p><NButton v-if="focusWriter.pending.value && !focusWriter.busy.value" size="small" @click="focusWriter.retry">用原请求重试</NButton><NButton v-else size="small" @click="focus.refetch()">读取最新关注状态</NButton></div><p v-if="focusMessage" class="prepare-focus-message success-text" role="status">{{ focusMessage }}</p>
  <nav class="prepare-kind-tabs" aria-label="准备材料类型"><button v-for="itemKind in kinds" :key="itemKind" :aria-current="kind === itemKind ? 'page' : undefined" :class="{ selected: kind === itemKind }" @click="selectKind(itemKind)">{{ kindLabels[itemKind] }}</button></nav>
  <div class="prepare-workspace">
    <aside class="prepare-catalog" aria-label="准备材料列表"><div class="prepare-list-filters"><NSelect v-model:value="status" :options="statusOptions" aria-label="材料状态" /><label><input v-model="favorites" type="checkbox" /> 仅看收藏</label></div><form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" placeholder="筛选已保存标题" aria-label="筛选准备材料" /><NButton attr-type="submit">查找</NButton></form><p class="field-help">标题筛选不搜索尚未保存的输入。</p>
      <p v-if="list.isPending.value" class="loading-block" role="status">正在读取材料…</p><div v-else-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重试</NButton></div><div v-else-if="!list.data.value?.items.length" class="prepare-empty-list"><strong>{{ search ? '没有匹配标题' : '还没有这一类材料' }}</strong><p>{{ search || status === 'ARCHIVED' || favorites ? '调整关键词或筛选条件后再试。' : '从一条手工记录开始，不需要配置模型。' }}</p></div>
      <div class="prepare-item-list"><div v-for="item in list.data.value?.items" :key="item.id" :class="['prepare-list-row', { selected: selectedId === item.id && !creating }]"><button :aria-current="selectedId === item.id && !creating ? 'true' : undefined" @click="open(item.id, item.kind)"><strong>{{ item.favorite ? '★ ' : '' }}{{ item.title }}</strong><small>v{{ item.versionNumber }} · {{ item.verification ? verificationLabels[item.verification] : item.status === 'ARCHIVED' ? '已归档' : kindLabels[item.kind] }}</small></button><button v-if="item.kind === 'TARGET' && item.status === 'ACTIVE'" class="prepare-focus-action" :disabled="focusWriter.locked.value || !focus.data.value || focus.data.value.targetId === item.id || !store.sessionActive" @click="changeFocus(item.id)">{{ focus.data.value?.targetId === item.id ? '正在关注' : '设为关注' }}</button></div></div>
      <div v-if="cursors.length || list.data.value?.nextCursor" class="pagination"><NButton size="small" :disabled="!cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!list.data.value?.nextCursor" @click="next">下一页</NButton></div>
    </aside>
    <PreparationEditor v-if="selectedId || creating" :key="selectedId && !creating ? selectedId : `new-${kind}`" :id="creating ? undefined : selectedId" :kind="kind" @changed="refresh" @created="created" @open="open" @close="close" />
    <section v-else class="prepare-welcome"><span class="prepare-welcome-mark" aria-hidden="true">{{ kind === 'TARGET' ? '◎' : kind === 'PROJECT' ? '▤' : kind === 'FACT' ? '✓' : '✎' }}</span><h2>{{ guidance[kind].title }}</h2><p>{{ guidance[kind].text }}</p><NButton type="primary" @click="create">新建{{ kindLabels[kind] }}</NButton><p class="prepare-later">在“附件资料”保留原件与摘录，在“面试安排”关联准备材料和具体时间。项目与事实可以引用确切的来源原文。</p></section>
  </div>
  <p class="workspace-footnote">内容版本、核实决定和模型用途授权分别记录；手工材料和已核实经历默认不外发。</p>
</template>

<style scoped>
.prepare-focus, .prepare-kind-tabs, .prepare-workspace, .prepare-focus-message { max-width: 1280px; margin-left: auto; margin-right: auto; }
.prepare-focus { display: flex; justify-content: space-between; align-items: center; gap: 14px; margin-bottom: 22px; padding: 13px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.prepare-focus > div { display: flex; flex-wrap: wrap; gap: 7px 15px; align-items: baseline; }
.prepare-focus > div > strong { font-size: 13px; }
.prepare-focus > div > button { text-align: left; }
.prepare-focus-message { margin-top: -12px; margin-bottom: 16px; }
.prepare-kind-tabs { display: flex; gap: 24px; overflow-x: auto; margin-bottom: 14px; border-bottom: 1px solid var(--line); }
.prepare-kind-tabs > button { min-height: 39px; white-space: nowrap; border-bottom: 2px solid transparent; color: var(--muted); font-size: 13px; }
.prepare-kind-tabs > button.selected { border-bottom-color: var(--brand); color: var(--brand-strong); font-weight: 650; }
.prepare-workspace { display: grid; grid-template-columns: 265px minmax(0, 1fr); min-height: 650px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.prepare-catalog { min-width: 0; padding: 18px 13px; border-right: 1px solid var(--line); background: var(--surface-soft); border-radius: 8px 0 0 8px; }
.prepare-catalog > .field-help { margin: 7px 0; }
.prepare-list-filters { display: grid; gap: 11px; }
.prepare-list-filters > label { color: var(--muted); font-size: 12px; display: flex; align-items: center; gap: 5px; }
.prepare-item-list { display: grid; gap: 3px; margin-top: 16px; }
.prepare-list-row { min-width: 0; padding: 9px; border-radius: 5px; }
.prepare-list-row.selected { background: var(--surface-deep); }
.prepare-list-row > button:first-child { display: grid; gap: 5px; width: 100%; text-align: left; }
.prepare-list-row strong { font-size: 13px; overflow-wrap: anywhere; }
.prepare-list-row small { color: var(--muted); font-size: 11px; }
.prepare-list-row:hover { background: var(--surface-deep); }
.prepare-focus-action { margin-top: 8px; padding: 2px 0; color: var(--brand); font-size: 11px; }
.prepare-empty-list { padding: 32px 5px; color: var(--muted); font-size: 12px; }
.prepare-empty-list strong { color: var(--ink); display: block; margin-bottom: 8px; }
.prepare-welcome { align-self: center; display: grid; justify-items: start; gap: 17px; max-width: 620px; padding: 46px clamp(24px, 4vw, 58px); }
.prepare-welcome h2 { font-size: 24px; }
.prepare-welcome > p { color: var(--muted); }
.prepare-welcome-mark { color: var(--brand); font-size: 31px; }
.prepare-welcome > .prepare-later { margin-top: 28px; padding-top: 18px; border-top: 1px solid var(--line); font-size: 11px; }
@media (max-width: 1000px) { .prepare-workspace { grid-template-columns: 220px minmax(0, 1fr); } }
@media (max-width: 700px) { .prepare-workspace { grid-template-columns: 1fr; } .prepare-catalog { border-right: 0; border-bottom: 1px solid var(--line); border-radius: 8px 8px 0 0; } .prepare-item-list { max-height: 250px; overflow: auto; } .prepare-list-filters { grid-template-columns: 1fr auto; align-items: center; } .prepare-focus > div { display: grid; } .prepare-kind-tabs { gap: 22px; } .prepare-welcome { padding: 40px 22px; } }
</style>
