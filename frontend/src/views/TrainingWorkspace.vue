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
import WorkspaceTabs from '../components/studio/WorkspaceTabs.vue'

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
  <header v-if="!selectedId || creating" class="page-heading"><div><h1>文字训练</h1><p class="muted">固定题目与练习条件，明确保存草稿和提交答案。</p></div><NButton type="primary" :disabled="!canCreate" @click="create">＋ 新建训练</NButton></header>
  <WorkspaceTabs v-if="!selectedId || creating" area="practice" />
  <section v-if="access.active && (!selectedId || creating)" class="training-active" aria-label="当前训练"><div><span class="field-help">当前训练</span><strong>{{ access.active.title }}</strong><p>{{ trainingModeLabels[access.active.mode] }} · {{ trainingStatusLabels[access.active.status] }} · {{ access.active.mode === 'LEARNING' ? '学习条件' : access.active.materialAccess ? '已授权资料查阅' : '尚未授权资料查阅' }}</p></div><NButton size="small" :disabled="access.checking" @click="open(access.active.id)">{{ selectedId === access.active.id && !creating ? '当前正在查看' : '返回当前训练' }}</NButton></section>
  <p v-if="access.checking" class="training-status-note field-help" role="status">正在核对当前训练及资料访问条件…</p><p v-if="access.error" class="training-status-note notice error" role="alert">{{ access.error }}<NButton text @click="access.refresh()">重新核对</NButton></p>
  <div class="training-workspace" :class="{ 'session-open': selectedId && !creating }">
    <aside v-show="!selectedId || creating" class="training-catalog" aria-label="训练记录列表">
      <NSelect v-model:value="status" :options="statusOptions" clearable placeholder="全部状态" aria-label="筛选训练状态" />
      <p class="field-help catalog-guidance">预览、进行中与已结束记录分别保留；刷新后可继续读取服务器草稿。</p>
      <p v-if="list.isPending.value" class="loading-block" role="status">正在读取训练记录…</p>
      <p v-else-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text :disabled="access.checking" @click="list.refetch()">重试</NButton></p>
      <div v-else-if="!list.data.value?.items.length" class="training-empty"><strong>{{ status ? '没有这一状态的训练' : '还没有训练记录' }}</strong><p>{{ status ? '可以切换状态，查看已保存的预览或历史训练。' : '先选择题目，保存一次固定预览，再明确开始。' }}</p></div>
      <div class="training-session-list"><button v-for="session in list.data.value?.items" :key="session.id" :class="{ selected: selectedId === session.id && !creating }" :aria-current="selectedId === session.id && !creating ? 'true' : undefined" :disabled="access.checking" @click="open(session.id)"><strong>{{ session.title }}</strong><span>{{ trainingModeLabels[session.mode] }} · {{ trainingStatusLabels[session.status] }}</span><small>{{ session.questionCount }} 道题 · {{ new Date(session.createdAt).toLocaleDateString('zh-CN') }}</small><span v-if="session.materialAccess && session.mode !== 'LEARNING'" class="assistance-label">已授权资料查阅</span></button></div>
      <div v-if="cursors.length || list.data.value?.nextCursor" class="pagination"><NButton size="small" :disabled="!cursors.length || access.checking" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!list.data.value?.nextCursor || access.checking" @click="next">下一页</NButton></div>
    </aside>
    <TrainingSetup v-if="creating" key="training-new" :disabled="access.checking || access.restricted" @created="created" @close="close" />
    <section v-else-if="selectedId" class="training-session-stage"><div class="session-back"><NButton text size="small" @click="close">关闭当前详情</NButton></div><TrainingSessionView :key="selectedId" :session-id="selectedId" @changed="refresh" /></section>
    <section v-else class="training-welcome"><h2>{{ access.active ? '接着完成这一次练习' : '先回答，再回看自己如何思考' }}</h2><p>{{ access.active ? '当前训练的已保存草稿和提交记录可继续读取。暂停后可以保存草稿，恢复后再提交。' : '选择学习练习、独立专项或文字模拟。题目与背景固定为快照，保存草稿和正式提交是两个明确动作。' }}</p><NButton v-if="access.active" type="primary" :disabled="access.checking" @click="open(access.active.id)">返回当前训练</NButton><NButton v-else type="primary" :disabled="!canCreate" @click="create">建立训练预览</NButton><div class="training-welcome-notes"><p>同一时间保留一场进行中或暂停的训练。结束前核对已答、跳过、未答与未提交草稿，结束不会自动提交草稿。</p><p>站内资料查阅通过明确授权记录条件；系统不能监测你已看过、复制或在站外打开的资料。AI 追问从明确选择的已提交回答发起，授权后生成并加入本场训练；评价从确切提交打开，先确认反馈时机，再预览外发清单并单次授权。</p></div></section>
  </div>
  <p class="workspace-footnote">只在收到服务器回执后显示保存成功。未发送的本地输入不会因刷新自动恢复。</p>
</template>

<style scoped>
.training-active, .training-status-note, .training-workspace { max-width: 1280px; margin-left: auto; margin-right: auto; }
.training-active { display: flex; gap: 16px; align-items: center; justify-content: space-between; padding: 15px 0; margin-bottom: 19px; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.training-active > div { min-width: 0; display: grid; gap: 5px; } .training-active strong { font-size: 15px; overflow-wrap: anywhere; } .training-active p { color: var(--muted); font-size: 12px; }
.training-status-note { margin-bottom: 14px; }
.training-workspace { display: grid; grid-template-columns: 265px minmax(0, 1fr); min-height: 650px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.training-catalog { min-width: 0; padding: 18px 14px; border-right: 1px solid var(--line); border-radius: 8px 0 0 8px; background: var(--surface-soft); }
.catalog-guidance { margin: 10px 0 17px; }
.training-session-list { display: grid; gap: 4px; } .training-session-list > button { min-width: 0; display: grid; gap: 6px; padding: 12px 9px; text-align: left; border-radius: 5px; } .training-session-list > button:hover, .training-session-list > button.selected { background: var(--surface-deep); }
.training-session-list strong { font-size: 13px; overflow-wrap: anywhere; } .training-session-list span, .training-session-list small { font-size: 12px; color: var(--muted); } .training-session-list .assistance-label { color: var(--brand-strong); }
.training-empty { padding: 30px 5px; color: var(--muted); font-size: 13px; } .training-empty strong { display: block; margin-bottom: 8px; color: var(--ink); }
.training-session-stage { min-width: 0; } .session-back { display: flex; justify-content: end; padding: 13px 24px 0; }
.training-welcome { align-self: center; display: grid; justify-items: start; gap: 20px; max-width: 690px; padding: 46px clamp(24px, 4vw, 58px); } .training-welcome h2 { font-size: 25px; } .training-welcome p { max-width: 65ch; color: var(--muted); }
.training-welcome-notes { display: grid; gap: 12px; border-top: 1px solid var(--line); padding-top: 20px; margin-top: 20px; font-size: 12px; }
@media (max-width: 1100px) { .training-workspace { grid-template-columns: 220px minmax(0, 1fr); } }
@media (max-width: 780px) { .training-workspace { grid-template-columns: 1fr; } .training-catalog { border-right: 0; border-bottom: 1px solid var(--line); border-radius: 8px 8px 0 0; } .training-session-list { max-height: 260px; overflow-y: auto; } .training-active { flex-wrap: wrap; } .training-welcome { padding: 38px 22px; } }
</style>

<style scoped>
.training-workspace { max-width: none; border: 0; background: none; gap: 20px; }.training-catalog, .training-welcome { border: 1px solid var(--line); border-radius: 20px; background: var(--surface); }.training-session-list strong { font-size: 19px; }.training-session-list :is(span, small) { font-size: 15px; }.training-session-list > button { padding: 18px 14px; border-radius: 12px; gap: 10px; }.training-workspace.session-open { display: block; min-height: 0; }.training-workspace.session-open .session-back { padding: 0; margin: -14px 0 8px; justify-content: start; }.training-welcome { align-self: stretch; align-content: center; }.training-active strong { font-size: 21px; }.training-active p { font-size: 16px; }
</style>
