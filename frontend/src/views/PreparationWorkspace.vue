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
import WorkspaceTabs from '../components/studio/WorkspaceTabs.vue'
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
  <header class="page-heading"><div><h1>{{ kind === 'TARGET' ? '准备' : kind === 'EXPRESSION' ? '表达素材' : '项目事实' }}</h1></div><NButton type="primary" @click="create"><RIcon name="plus" :size="23" />新建{{ kindLabels[kind] }}</NButton></header>
  <WorkspaceTabs area="prepare" :material="kind !== 'TARGET'" />
  <section class="prepare-focus" aria-label="当前关注目标"><div><span class="field-help">当前关注</span><button v-if="focusedTarget.data.value" class="text-link" @click="open(focusedTarget.data.value.id, 'TARGET')">{{ focusedTarget.data.value.currentVersion.content.title }}</button><strong v-else>{{ focus.data.value?.targetId ? '正在读取目标…' : '尚未指定目标' }}</strong><span class="field-help">关注状态不改变材料版本或历史条件。</span></div><NButton v-if="focus.data.value?.targetId" size="small" :disabled="focusWriter.locked.value || !store.sessionActive" @click="changeFocus(null)">取消关注</NButton></section>
  <p v-if="focus.error.value || focusedTarget.error.value" class="notice error" role="alert">{{ errorText(focus.error.value ?? focusedTarget.error.value) }}<NButton text @click="reloadFocus">重试读取</NButton></p><div v-if="focusWriter.error.value" class="notice error" role="alert"><p>{{ errorText(focusWriter.error.value) }}</p><NButton v-if="focusWriter.pending.value && !focusWriter.busy.value" size="small" @click="focusWriter.retry">用原请求重试</NButton><NButton v-else size="small" @click="focus.refetch()">读取最新关注状态</NButton></div><p v-if="focusMessage" class="prepare-focus-message success-text" role="status">{{ focusMessage }}</p>
  <nav v-if="kind === 'PROJECT'" class="prepare-kind-tabs" aria-label="准备材料类型"><button v-for="itemKind in (['PROJECT', 'FACT'] as const)" :key="itemKind" :aria-current="kind === itemKind ? 'page' : undefined" :class="{ selected: kind === itemKind }" @click="selectKind(itemKind)">{{ kindLabels[itemKind] }}</button></nav>
  <div class="prepare-workspace" :class="{ 'fact-workspace': kind === 'FACT' }">
    <aside class="prepare-catalog" aria-label="准备材料列表"><h2 class="prepare-catalog-heading">{{ kind === 'TARGET' ? '目标列表' : kind === 'EXPRESSION' ? '表达素材' : '项目目录' }}<button class="studio-icon-button" title="新建" aria-label="新建准备材料" @click="create"><RIcon name="plus" :size="24" /></button></h2><details class="prepare-catalog-filters"><summary><RIcon name="search" :size="19" />筛选材料</summary><div class="prepare-list-filters"><NSelect v-model:value="status" :options="statusOptions" aria-label="材料状态" /><label><input v-model="favorites" type="checkbox" /> 仅看收藏</label></div><form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" placeholder="筛选已保存标题" aria-label="筛选准备材料" /><NButton attr-type="submit">查找</NButton></form><p class="field-help">标题筛选不搜索尚未保存的输入。</p></details>
      <p v-if="list.isPending.value" class="loading-block" role="status">正在读取材料…</p><div v-else-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重试</NButton></div><div v-else-if="!list.data.value?.items.length" class="prepare-empty-list"><strong>{{ search ? '没有匹配标题' : '还没有这一类材料' }}</strong><p>{{ search || status === 'ARCHIVED' || favorites ? '调整关键词或筛选条件后再试。' : '从一条手工记录开始，不需要配置模型。' }}</p></div>
      <div v-if="kind === 'FACT'" class="fact-project-directory"><p v-if="projects.error.value" class="notice error">{{ errorText(projects.error.value) }}</p><details v-for="project in factProjects" :key="project.id" open><summary><RIcon name="chevron-down" :size="18" /><RIcon name="building" :size="23" /><button :title="`打开项目资料：${project.title}`" @click.stop.prevent="open(project.id, 'PROJECT')">{{ project.title }}</button></summary><button v-for="fact in project.facts" :key="fact.id" :class="{ selected: selectedId === fact.id && !creating }" :aria-current="selectedId === fact.id && !creating ? 'true' : undefined" @click="open(fact.id, 'FACT')"><RIcon name="file-text" :size="24" /><span>{{ fact.title }}</span></button></details><p v-if="projects.data.value?.nextCursor" class="field-help">项目名称仅覆盖已读目录，可打开项目核对完整内容。</p></div>
      <div v-else class="prepare-item-list"><div v-for="item in list.data.value?.items" :key="item.id" :class="['prepare-list-row', { selected: selectedId === item.id && !creating }]"><button :aria-current="selectedId === item.id && !creating ? 'true' : undefined" @click="open(item.id, item.kind)"><strong>{{ item.favorite ? '★ ' : '' }}{{ item.title }}</strong><small>v{{ item.versionNumber }} · {{ item.verification ? verificationLabels[item.verification] : item.status === 'ARCHIVED' ? '已归档' : kindLabels[item.kind] }}</small></button><button v-if="item.kind === 'TARGET' && item.status === 'ACTIVE'" class="prepare-focus-action" :disabled="focusWriter.locked.value || !focus.data.value || focus.data.value.targetId === item.id || !store.sessionActive" @click="changeFocus(item.id)">{{ focus.data.value?.targetId === item.id ? '正在关注' : '设为关注' }}</button></div></div>
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

<style scoped>
.fact-project-directory details { margin-top: 8px; }.fact-project-directory summary { display: flex; align-items: start; gap: 8px; padding: 15px 7px; list-style: none; color: var(--muted); cursor: pointer; }.fact-project-directory summary > svg { flex: none; margin-top: 4px; }.fact-project-directory summary > button { padding: 0; text-align: left; font-size: 20px; line-height: 1.6; overflow-wrap: anywhere; }.fact-project-directory details:not([open]) summary > svg:first-child { transform: rotate(-90deg); }.fact-project-directory details > button { display: flex; align-items: center; gap: 12px; width: calc(100% - 12px); margin: 5px 0 5px 12px; padding: 16px 10px; border-radius: 15px; border: 1px solid transparent; text-align: left; font-size: 20px; }.fact-project-directory details > button.selected { border-color: var(--brand); background: var(--brand-soft); }.fact-project-directory details > button > svg { flex: none; }.fact-project-directory details > button > span { min-width: 0; overflow-wrap: anywhere; }
.prepare-workspace :deep(.preparation-editor.prepare-reading) { padding: 0; border: 0; background: none; }.prepare-workspace :deep(.prepare-reading > .detail-header),.prepare-workspace :deep(.prepare-reading > .save-state) { padding-inline: 20px; }.prepare-workspace :deep(.prepare-readout .readout-main > h2) { display: none; }.prepare-workspace :deep(.readout-workspace) { font-size: 20px; }.prepare-workspace :deep(.readout-workspace .readout-aside h3) { font-size: 23px; }
</style>

<style scoped>
.prepare-focus { padding: 0; border: 0; margin-bottom: 18px; color: var(--muted); }.prepare-focus > div > strong { font-size: 16px; }.prepare-focus > div > .field-help:last-child { display: none; }.prepare-kind-tabs { max-width: none; width: fit-content; margin-left: 0; margin-bottom: 18px; }.prepare-workspace { max-width: none; background: transparent; gap: 12px; border: 0; grid-template-columns: 295px minmax(0, 1fr); min-height: 690px; }.prepare-catalog { border: 1px solid var(--line); border-radius: 22px; padding: 22px 12px; background: linear-gradient(120deg, #27221b99, #16130fbb); }.prepare-catalog-heading { display: flex; justify-content: space-between; align-items: center; font-size: 24px; padding: 0 9px; margin-bottom: 18px; }.prepare-catalog-filters { margin: 10px 9px 20px; }.prepare-catalog-filters summary { display: flex; align-items: center; gap: 10px; color: var(--muted); cursor: pointer; font-size: 15px; }.prepare-catalog-filters[open] .prepare-list-filters { margin-top: 16px; }.prepare-list-filters > label { font-size: 15px; }.prepare-list-row { padding: 16px 18px; margin-bottom: 8px; border: 1px solid transparent; border-radius: 15px; }.prepare-list-row.selected { border-color: #a9774d; background: linear-gradient(120deg, #95613166, #4b31204d); }.prepare-list-row strong { font-size: 21px; font-weight: 500; }.prepare-list-row small { font-size: 16px; }.prepare-focus-action { font-size: 14px; color: #cda579; }.prepare-empty-list { padding: 45px 18px; font-size: 17px; }.prepare-welcome { align-self: stretch; max-width: none; align-content: center; gap: 23px; padding: 60px; border: 1px solid var(--line); border-radius: 22px; background: linear-gradient(120deg, #25201999, #18140f99); }.prepare-welcome h2 { font-size: 32px; }.prepare-welcome > p { max-width: 43ch; font-size: 20px; }.prepare-welcome-mark { font-size: 48px; }.prepare-welcome .prepare-later { font-size: 15px; }.prepare-workspace :deep(.preparation-editor) { border: 1px solid var(--line); border-radius: 22px; background: linear-gradient(115deg, #27221b99, #17130f99); padding: 30px 28px; }.prepare-workspace :deep(.detail-header h2) { font-size: 30px; }.prepare-workspace :deep(.prepare-editor-body) { gap: 23px; }.prepare-workspace :deep(.prepare-readout) { max-width: none; font-size: 20px; line-height: 1.85; }.prepare-workspace :deep(.prepare-readout > h2) { display: none; }.prepare-workspace :deep(.prepare-readout h3) { font-size: 23px; }.prepare-workspace :deep(.prepare-readout .preserve) { font-size: 20px; line-height: 1.85; }.prepare-workspace :deep(.save-state) { font-size: 14px; }.prepare-workspace :deep(.prepare-readout .markdown-preview) { font-size: 20px; }
@media (max-width: 1000px) { .prepare-workspace { grid-template-columns: 230px minmax(0, 1fr); }.prepare-welcome { padding: 35px; }.prepare-list-row strong { font-size: 18px; }.prepare-catalog-heading { font-size: 22px; } }
@media (max-width: 700px) { .prepare-workspace { grid-template-columns: 1fr; }.prepare-welcome { padding: 30px 23px; }.prepare-welcome h2 { font-size: 25px; }.prepare-welcome p { font-size: 17px; }.prepare-workspace :deep(.preparation-editor) { padding: 24px 20px; } }
</style>
