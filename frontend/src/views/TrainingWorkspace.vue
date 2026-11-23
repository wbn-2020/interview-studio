<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NSelect } from 'naive-ui'
import { errorText, read } from '../api/client'
import type { TrainingSession, TrainingSessionPage, TrainingStatus } from '../api/training'
import { trainingModeLabels, trainingStatusLabels } from '../api/training'
import { queryClient } from '../api/queryClient'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import RIcon from '../replica/RIcon.vue'

const TrainingSetup = defineAsyncComponent(() => import('../components/training/TrainingSetup.vue'))
const TrainingSessionView = defineAsyncComponent(() => import('../components/training/TrainingSession.vue'))
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const selectedId = computed(() => typeof route.query.item === 'string' ? route.query.item : undefined)
const creating = computed(() => route.query.create === '1')
const status = ref<TrainingStatus | null>(null)
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const canCreate = computed(() => store.sessionActive && !access.checking && !access.restricted)
const statusOptions = Object.entries(trainingStatusLabels).map(([value, label]) => ({ value, label }))
const list = useQuery({
  queryKey: computed(() => ['training', store.sessionEpoch, 'sessions', status.value, cursor.value]),
  enabled: computed(() => store.sessionActive && !access.checking),
  queryFn: () => {
    const parameters = new URLSearchParams({ limit: '20' })
    if (status.value) parameters.set('status', status.value)
    if (cursor.value) parameters.set('cursor', cursor.value)
    return read<TrainingSessionPage>(`/training/sessions?${parameters}`)
  },
})
const changeState = reactive({ dirty: false, locked: false })
store.changes.set('training-workspace', changeState)
watch([status, () => store.sessionEpoch], () => { cursor.value = undefined; cursors.value = [] }, { flush: 'sync' })
onUnmounted(() => { if (store.changes.get('training-workspace') === changeState) store.changes.delete('training-workspace') })
function open(id: string) { void router.push({ name: 'training', query: { item: id } }) }
function close() { void router.push({ name: 'training' }) }
function create() { if (canCreate.value) void router.push({ name: 'training', query: { create: '1' } }) }
function created(session: TrainingSession) { refresh(); open(session.id) }
function refresh() { void queryClient.invalidateQueries({ queryKey: ['training', store.sessionEpoch, 'sessions'] }) }
function next() { if (list.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = list.data.value.nextCursor } }
</script>

<template>
  <header v-if="!selectedId || creating" class="page-heading"><div><h1>文字训练</h1><p class="muted">练习表达，回看思考，接着上一次的进度继续。</p></div><NButton type="primary" :disabled="!canCreate" @click="create"><RIcon name="plus" :size="18" />新建训练</NButton></header>
  <section v-if="access.active && (!selectedId || creating)" class="training-active" aria-label="当前训练"><div><span class="field-help">当前训练</span><strong>{{ access.active.title }}</strong><p>{{ trainingModeLabels[access.active.mode] }} · {{ trainingStatusLabels[access.active.status] }} · {{ access.active.mode === 'LEARNING' ? '学习条件' : access.active.materialAccess ? '已授权资料查阅' : '尚未授权资料查阅' }}</p></div><NButton size="small" :disabled="access.checking" @click="open(access.active.id)">{{ selectedId === access.active.id && !creating ? '当前正在查看' : '返回当前训练' }}</NButton></section>
  <p v-if="access.checking" class="training-status-note field-help" role="status">正在核对当前训练及资料访问条件…</p><p v-if="access.error" class="training-status-note notice error" role="alert">{{ access.error }}<NButton text @click="access.refresh()">重新核对</NButton></p>
  <div class="training-workspace" :class="{ 'session-open': selectedId && !creating, 'setup-open': creating }">
    <aside v-show="!selectedId || creating" class="training-catalog" aria-label="训练记录列表">
      <header class="training-catalog-heading"><h2>训练记录</h2><NSelect v-model:value="status" :options="statusOptions" clearable placeholder="全部状态" aria-label="筛选训练状态" /></header>
      <p v-if="list.isPending.value" class="loading-block" role="status">正在读取训练记录…</p>
      <p v-else-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text :disabled="access.checking" @click="list.refetch()">重试</NButton></p>
      <div v-else-if="!list.data.value?.items.length" class="training-empty"><strong>{{ status ? '没有这一状态的训练' : '还没有训练记录' }}</strong><p>{{ status ? '可以切换状态，查看已保存的预览或历史训练。' : '先选择题目，保存一次固定预览，再明确开始。' }}</p></div>
      <div class="training-session-list"><button v-for="session in list.data.value?.items" :key="session.id" :class="{ selected: selectedId === session.id && !creating }" :aria-current="selectedId === session.id && !creating ? 'true' : undefined" :disabled="access.checking" @click="open(session.id)"><span class="training-record-icon"><RIcon name="play" :size="19" /></span><span class="training-record-content"><strong>{{ session.title }}</strong><span class="training-record-meta"><span>{{ trainingModeLabels[session.mode] }}</span><span>{{ session.questionCount }} 道题</span><span>{{ new Date(session.createdAt).toLocaleDateString('zh-CN') }}</span></span><span v-if="session.materialAccess && session.mode !== 'LEARNING'" class="assistance-label">已授权资料查阅</span></span><span class="status-pill">{{ trainingStatusLabels[session.status] }}</span><RIcon name="chevron-right" :size="16" /></button></div>
      <div v-if="cursors.length || list.data.value?.nextCursor" class="pagination"><NButton size="small" :disabled="!cursors.length || access.checking" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!list.data.value?.nextCursor || access.checking" @click="next">下一页</NButton></div>
    </aside>
    <TrainingSetup v-if="creating" key="training-new" :disabled="access.checking || access.restricted" @created="created" @close="close" />
    <section v-else-if="selectedId" class="training-session-stage"><div class="session-back"><NButton text size="small" @click="close">← 返回训练记录</NButton></div><TrainingSessionView :key="selectedId" :session-id="selectedId" @changed="refresh" /></section>
    <section v-else class="training-welcome"><RIcon name="play" :size="24" /><h2>{{ access.active ? '继续当前练习' : '给自己一次完整的练习' }}</h2><p>{{ access.active ? '回到已保存的草稿与提交记录，接着上次的进度。' : '从学习练习、独立专项或文字模拟开始，逐步把知识变成自己的表达。' }}</p><NButton v-if="access.active" :disabled="access.checking" @click="open(access.active.id)">返回当前训练</NButton><NButton v-else :disabled="!canCreate" @click="create">选择题目开始</NButton><ol class="training-steps"><li><strong>选择题目</strong><span>确认范围与练习条件</span></li><li><strong>独立作答</strong><span>保存草稿，准备好后再提交</span></li><li><strong>回看与复练</strong><span>从具体回答找到改进方向</span></li></ol><details class="training-welcome-notes"><summary>训练与资料使用说明</summary><p>同一时间保留一场进行中或暂停的训练。结束前核对已答、跳过、未答与未提交草稿，结束不会自动提交草稿。</p><p>站内资料查阅通过明确授权记录条件；系统不能监测你已看过、复制或在站外打开的资料。AI 追问从明确选择的已提交回答发起，授权后生成并加入本场训练；评价从确切提交打开，先确认反馈时机，再预览外发清单并单次授权。</p></details></section>
  </div>
  <p class="workspace-footnote">只在收到服务器回执后显示保存成功。未发送的本地输入不会因刷新自动恢复。</p>
</template>

<style scoped>
.training-active, .training-status-note, .training-workspace { max-width: none; }
.training-active { display: flex; gap: 16px; align-items: center; justify-content: space-between; padding: 15px 0; margin-bottom: 19px; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.training-active > div { min-width: 0; display: grid; gap: 5px; } .training-active strong { font-size: 15px; overflow-wrap: anywhere; } .training-active p { color: var(--muted); font-size: 12px; }
.training-status-note { margin-bottom: 14px; }
.training-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 288px; gap: 32px; align-items: start; }
.training-catalog { min-width: 0; }
.training-catalog-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }.training-catalog-heading h2 { font-size: 16px; }.training-catalog-heading .n-select { width: 148px; }
.training-session-list { display: grid; }.training-session-list > button { display: grid; grid-template-columns: 36px minmax(0, 1fr) auto 16px; align-items: center; gap: 16px; min-width: 0; width: 100%; padding: 20px 12px; text-align: left; border-bottom: 1px solid var(--studio-divider); }
.training-session-list > button:hover { background: var(--studio-pane); }.training-session-list > button.selected { background: var(--brand-soft); }
.training-record-icon { display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid var(--studio-divider); border-radius: 8px; color: var(--brand); background: var(--studio-pane); }.training-record-content { display: grid; gap: 7px; min-width: 0; }.training-record-content strong { color: var(--ink); font-size: 14px; line-height: 1.6; font-weight: 500; overflow-wrap: anywhere; }
.training-record-meta { display: flex; flex-wrap: wrap; gap: 6px 12px; color: var(--muted); font-size: 12px; }.training-session-list > button > svg { color: var(--faint); }.assistance-label { color: var(--brand-strong); font-size: 12px; }
.training-empty { display: grid; align-content: center; justify-items: center; min-height: 240px; padding: 36px 24px; border: 1px dashed var(--line); border-radius: 8px; color: var(--muted); text-align: center; font-size: 13px; }.training-empty strong { margin-bottom: 8px; color: var(--ink); font-size: 16px; font-weight: 500; }
.training-session-stage { min-width: 0; }.session-back { display: flex; margin-bottom: 20px; }
.training-welcome { display: grid; justify-items: start; gap: 16px; min-width: 0; padding: 24px; border: 1px solid var(--studio-divider); border-radius: 10px; background: var(--studio-pane); }.training-welcome > svg { color: var(--brand); }.training-welcome h2 { font-size: 17px; }.training-welcome p { color: var(--muted); font-size: 13px; line-height: 1.85; }
.training-steps { display: grid; gap: 20px; width: 100%; margin: 8px 0 0; padding: 20px 0 0; border-top: 1px solid var(--studio-divider); list-style: none; counter-reset: training-step; }.training-steps li { position: relative; display: grid; gap: 4px; padding-left: 32px; counter-increment: training-step; }.training-steps li::before { content: counter(training-step); position: absolute; left: 0; top: 1px; color: var(--faint); font-size: 12px; font-variant-numeric: tabular-nums; }.training-steps strong { font-size: 13px; font-weight: 500; }.training-steps span { font-size: 12px; color: var(--muted); }
.training-welcome-notes { width: 100%; border-top: 1px solid var(--studio-divider); padding-top: 16px; margin-top: 8px; }.training-welcome-notes summary { color: var(--muted); cursor: pointer; font-size: 12px; }.training-welcome-notes p { margin-top: 12px; font-size: 12px; }
.training-workspace.setup-open { grid-template-columns: 248px minmax(0, 1fr); gap: 24px; }.setup-open .training-catalog-heading { flex-wrap: wrap; }.setup-open .training-session-list { max-height: 540px; overflow-y: auto; }.setup-open .training-session-list > button { grid-template-columns: minmax(0, 1fr); gap: 8px; padding: 14px 8px; }.setup-open .training-record-icon, .setup-open .training-session-list > button > svg { display: none; }.setup-open .status-pill { justify-self: start; }
.training-workspace.session-open { display: block; }
@container workspace (max-width: 820px) { .training-workspace { grid-template-columns: minmax(0, 1fr); gap: 24px; }.training-workspace.setup-open { grid-template-columns: 210px minmax(0, 1fr); gap: 20px; } }
@media(max-width:700px) { .training-workspace.setup-open { grid-template-columns: minmax(0, 1fr); }.setup-open .training-session-list { max-height: 210px; }.training-active { flex-wrap: wrap; }.training-welcome { padding: 22px; }.training-session-list > button { grid-template-columns: 30px minmax(0, 1fr) auto; gap: 12px; padding: 18px 0; }.training-record-icon { width: 30px; height: 34px; }.training-session-list > button > svg { display: none; }.training-record-meta { gap: 4px 8px; }.training-catalog-heading .n-select { width: 132px; } }
</style>
