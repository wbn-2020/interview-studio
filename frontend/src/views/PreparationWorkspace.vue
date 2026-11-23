<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { errorText, read } from '../api/client'
import type { PreparationFocus, PreparationItem, PreparationKind, PreparationPage, PreparationSummary } from '../api/preparation'
import { kindLabels, verificationLabels } from '../api/preparation'
import { queryClient } from '../api/queryClient'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import RIcon from '../replica/RIcon.vue'
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
const projects = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'fact-project-directory', status.value]), enabled: computed(() => store.sessionActive && kind.value === 'FACT'),
  queryFn: () => read<PreparationPage>(`/prepare/items?${new URLSearchParams({ kind: 'PROJECT', status: status.value, limit: '50' })}`) })
const factProjects = computed(() => {
  const grouped = new Map<string, { id: string; title: string; facts: PreparationSummary[] }>()
  for (const fact of list.data.value?.items ?? []) {
    if (fact.kind !== 'FACT') continue
    let group = grouped.get(fact.projectId)
    if (!group) {
      group = { id: fact.projectId, title: projects.data.value?.items.find(project => project.id === fact.projectId)?.title ?? `项目 ${fact.projectId.slice(0, 8)}`, facts: [] }
      grouped.set(fact.projectId, group)
    }
    group.facts.push(fact)
  }
  return [...grouped.values()]
})
const focusedTarget = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'focus-target', focus.data.value?.targetId]), enabled: computed(() => store.sessionActive && Boolean(focus.data.value?.targetId)), queryFn: () => read<PreparationItem>(`/prepare/items/${focus.data.value!.targetId}`) })
watch([kind, status, favorites], () => { cursor.value = undefined; cursors.value = [] })
watch(focusWriter.locked, locked => store.changes.set('prepare-focus', { dirty: false, locked }), { immediate: true })
onUnmounted(() => store.changes.delete('prepare-focus'))

function open(id: string, itemKind: PreparationKind = kind.value, versionId?: string) { void router.push({ name: 'prepare', query: { kind: itemKind, item: id, version: versionId } }) }
function close() { void router.push({ name: 'prepare', query: { kind: kind.value } }) }
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
const guidance: Record<PreparationKind, { title: string; intro: string; text: string }> = {
  TARGET: { title: '从一个岗位开始', intro: '把岗位要求整理成明确的准备方向。', text: '保存岗位与 JD，拆出需要解释、排错、取舍或表达的考察项，再关联现有项目、事实和知识。' },
  PROJECT: { title: '先整理项目背景', intro: '集中整理项目背景，以及你在其中的工作。', text: '项目概述保存共同背景。本人负责过什么、参与过什么以及仍需核实的指标，分别记为项目事实。' },
  FACT: { title: '写清自己的贡献', intro: '记录真实经历，让每一段表达都有依据。', text: '选择所属项目，写清场景、问题、本人动作与结果。保存后显式核实；核实不会授予模型外发权限。' },
  EXPRESSION: { title: '把经历讲清楚', intro: '基于已核实的事实，打磨不同场景下的表达。', text: '选择当前已核实的事实版本，组织长短项目介绍、STAR 或技术取舍。来源更正时保留旧文，提醒你复核。' },
}
</script>

<template>
  <header class="page-heading"><div><h1>{{ kind === 'TARGET' ? '目标与岗位' : kindLabels[kind] }}</h1><p class="muted">{{ guidance[kind].intro }}</p></div><NButton type="primary" @click="create"><RIcon name="plus" :size="18" />新建{{ kindLabels[kind] }}</NButton></header>
  <section class="prepare-focus" aria-label="当前关注目标"><div><span class="field-help">当前关注</span><button v-if="focusedTarget.data.value" class="text-link" @click="open(focusedTarget.data.value.id, 'TARGET')">{{ focusedTarget.data.value.currentVersion.content.title }}</button><strong v-else>{{ focus.data.value?.targetId ? '正在读取目标…' : '尚未指定目标' }}</strong><span class="field-help">关注状态不改变材料版本或历史条件。</span></div><NButton v-if="focus.data.value?.targetId" size="small" :disabled="focusWriter.locked.value || !store.sessionActive" @click="changeFocus(null)">取消关注</NButton></section>
  <p v-if="focus.error.value || focusedTarget.error.value" class="notice error" role="alert">{{ errorText(focus.error.value ?? focusedTarget.error.value) }}<NButton text @click="reloadFocus">重试读取</NButton></p><div v-if="focusWriter.error.value" class="notice error" role="alert"><p>{{ errorText(focusWriter.error.value) }}</p><NButton v-if="focusWriter.pending.value && !focusWriter.busy.value" size="small" @click="focusWriter.retry">用原请求重试</NButton><NButton v-else size="small" @click="focus.refetch()">读取最新关注状态</NButton></div><p v-if="focusMessage" class="prepare-focus-message success-text" role="status">{{ focusMessage }}</p>
  <div class="prepare-workspace" :class="{ 'fact-workspace': kind === 'FACT', 'has-detail': selectedId || creating }">
    <aside class="prepare-catalog" aria-label="准备材料列表"><h2 class="prepare-catalog-heading">{{ kind === 'TARGET' ? '目标列表' : kind === 'EXPRESSION' ? '表达素材' : '项目目录' }}<button class="studio-icon-button" title="新建" aria-label="新建准备材料" @click="create"><RIcon name="plus" :size="18" /></button></h2><details class="prepare-catalog-filters" :open="!selectedId && !creating"><summary><RIcon name="search" :size="16" />筛选材料</summary><div class="prepare-list-filters"><NSelect v-model:value="status" :options="statusOptions" aria-label="材料状态" /><label><input v-model="favorites" type="checkbox" /> 仅看收藏</label></div><form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" placeholder="按材料标题查找" aria-label="筛选准备材料" /><NButton attr-type="submit">查找</NButton></form></details>
      <p v-if="list.isPending.value" class="loading-block" role="status">正在读取材料…</p><div v-else-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重试</NButton></div><div v-else-if="!list.data.value?.items.length" class="prepare-empty-list"><strong>{{ search ? '没有匹配标题' : '还没有这一类材料' }}</strong><p>{{ search || status === 'ARCHIVED' || favorites ? '调整关键词或筛选条件后再试。' : '从一条手工记录开始，不需要配置模型。' }}</p></div>
      <div v-if="kind === 'FACT'" class="fact-project-directory"><p v-if="projects.error.value" class="notice error">{{ errorText(projects.error.value) }}</p><details v-for="project in factProjects" :key="project.id" open><summary><RIcon name="chevron-down" :size="18" /><RIcon name="building" :size="23" /><button :title="`打开项目资料：${project.title}`" @click.stop.prevent="open(project.id, 'PROJECT')">{{ project.title }}</button></summary><button v-for="fact in project.facts" :key="fact.id" :class="{ selected: selectedId === fact.id && !creating }" :aria-current="selectedId === fact.id && !creating ? 'true' : undefined" @click="open(fact.id, 'FACT')"><RIcon name="file-text" :size="24" /><span>{{ fact.title }}</span></button></details><p v-if="projects.data.value?.nextCursor" class="field-help">项目名称仅覆盖已读目录，可打开项目核对完整内容。</p></div>
      <div v-else class="prepare-item-list"><div v-for="item in list.data.value?.items" :key="item.id" :class="['prepare-list-row', { selected: selectedId === item.id && !creating }]"><button :aria-current="selectedId === item.id && !creating ? 'true' : undefined" @click="open(item.id, item.kind)"><RIcon :name="item.kind === 'TARGET' ? 'target' : item.kind === 'PROJECT' ? 'building' : 'file-text'" :size="20" /><span><strong>{{ item.favorite ? '★ ' : '' }}{{ item.title }}</strong><small>v{{ item.versionNumber }} · {{ item.verification ? verificationLabels[item.verification] : item.status === 'ARCHIVED' ? '已归档' : kindLabels[item.kind] }}</small></span><RIcon name="chevron-right" :size="16" /></button><button v-if="item.kind === 'TARGET' && item.status === 'ACTIVE'" class="prepare-focus-action" :disabled="focusWriter.locked.value || !focus.data.value || focus.data.value.targetId === item.id || !store.sessionActive" @click="changeFocus(item.id)">{{ focus.data.value?.targetId === item.id ? '正在关注' : '设为关注' }}</button></div></div>
      <div v-if="cursors.length || list.data.value?.nextCursor" class="pagination"><NButton size="small" :disabled="!cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!list.data.value?.nextCursor" @click="next">下一页</NButton></div>
    </aside>
    <PreparationEditor v-if="selectedId || creating" :key="selectedId && !creating ? selectedId : `new-${kind}`" :id="creating ? undefined : selectedId" :kind="kind" @changed="refresh" @created="created" @open="open" @close="close" />
    <details v-else class="workspace-help"><summary>{{ guidance[kind].title }} · 使用说明</summary><div class="workspace-help-body"><p>{{ guidance[kind].text }}</p><div class="workspace-help-actions"><RouterLink to="/prepare/materials"><RIcon name="file-text" :size="16" />整理附件资料</RouterLink><RouterLink to="/prepare/schedule"><RIcon name="calendar-days" :size="16" />管理面试安排</RouterLink></div></div></details>
  </div>
  <p class="workspace-footnote">内容版本、核实决定和模型用途授权分别记录；手工材料和已核实经历默认不外发。</p>
</template>

<style scoped>
.prepare-focus { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 20px; padding: 10px 0; border-top: 1px solid var(--studio-divider); border-bottom: 1px solid var(--studio-divider); }
.prepare-focus > div { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.prepare-focus > div > .field-help:last-child { display: none; }
.prepare-focus strong { font-size: 13px; }.prepare-focus-message { margin-bottom: 12px; }
.prepare-workspace { display: grid; grid-template-columns: minmax(0, 1fr); gap: 20px; align-items: start; }
.prepare-workspace.has-detail { grid-template-columns: 248px minmax(0, 1fr); gap: 24px; }
.prepare-catalog { min-width: 0; }
.prepare-catalog-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; font-size: 16px; }
.prepare-catalog-filters { margin-bottom: 20px; }.prepare-catalog-filters summary { display: flex; align-items: center; gap: 8px; min-height: 32px; color: var(--muted); cursor: pointer; font-size: 13px; }
.prepare-list-filters { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-top: 12px; }.prepare-list-filters > .n-select { width: 160px; }.prepare-list-filters > label { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted); }
.prepare-catalog-filters .title-search { margin: 12px 0 0; }
.has-detail :is(.prepare-item-list, .fact-project-directory) { max-height: 560px; overflow-y: auto; }
.prepare-list-row { padding: 16px 12px; border-bottom: 1px solid var(--studio-divider); }.prepare-list-row:hover { background: var(--studio-pane); }.prepare-list-row.selected { background: var(--brand-soft); }
.prepare-list-row > button:first-child { display: flex; align-items: center; gap: 12px; width: 100%; padding: 0; text-align: left; }
.prepare-list-row > button:first-child > svg { flex: none; color: var(--muted); }.prepare-list-row > button:first-child > span { display: grid; flex: 1; min-width: 0; gap: 5px; }
.prepare-list-row strong { font-size: 14px; font-weight: 500; overflow-wrap: anywhere; }.prepare-list-row small { font-size: 12px; color: var(--muted); }
.prepare-focus-action { min-height: 28px; padding: 3px 0; margin: 6px 0 0 32px; font-size: 12px; color: var(--brand); }
.prepare-empty-list { display: grid; align-content: center; justify-items: center; min-height: 240px; padding: 36px 24px; border: 1px dashed var(--line); border-radius: 8px; color: var(--muted); text-align: center; font-size: 13px; }.prepare-empty-list strong { margin-bottom: 8px; color: var(--ink); font-size: 16px; font-weight: 500; }
.prepare-workspace :deep(.preparation-editor) { min-width: 0; padding: 28px; border: 1px solid var(--studio-divider); border-radius: 10px; background: var(--studio-pane); }
.prepare-workspace :deep(.detail-header h2) { font-size: 20px; }.prepare-workspace :deep(.prepare-readout) { font-size: var(--studio-font-size); line-height: 1.8; }
.prepare-workspace :deep(.prepare-readout h3) { font-size: 16px; }.prepare-workspace :deep(.prepare-readout .preserve), .prepare-workspace :deep(.prepare-readout .markdown-preview), .prepare-workspace :deep(.readout-workspace) { font-size: var(--studio-font-size); }
.fact-project-directory details { margin-top: 6px; }.fact-project-directory summary { display: flex; align-items: start; gap: 6px; padding: 10px 6px; list-style: none; color: var(--muted); cursor: pointer; }
.fact-project-directory summary > svg { flex: none; width: 17px; margin-top: 3px; }.fact-project-directory summary > button { padding: 0; text-align: left; font-size: 13px; overflow-wrap: anywhere; }
.fact-project-directory details > button { display: flex; align-items: center; gap: 8px; width: calc(100% - 12px); margin: 4px 0 4px 12px; padding: 9px; border-radius: 6px; text-align: left; font-size: 13px; }
.fact-project-directory details > button.selected { background: var(--brand-soft); }.fact-project-directory details > button > svg { flex: none; width: 17px; }.fact-project-directory details > button > span { min-width: 0; overflow-wrap: anywhere; }
@container workspace (max-width: 820px) { .prepare-workspace { grid-template-columns: minmax(0, 1fr); gap: 24px; }.prepare-workspace.has-detail { grid-template-columns: 210px minmax(0, 1fr); gap: 20px; } }
@media(max-width:700px) { .prepare-workspace.has-detail { grid-template-columns: minmax(0, 1fr); }.has-detail .prepare-catalog { padding-bottom: 20px; border-bottom: 1px solid var(--studio-divider); }.has-detail :is(.prepare-item-list, .fact-project-directory) { max-height: 210px; }.prepare-workspace :deep(.preparation-editor) { padding: 22px 18px; }.prepare-focus { margin-bottom: 24px; } }
</style>
