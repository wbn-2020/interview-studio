<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import type { Collection, CollectionKind, CollectionQuestionChoice, CollectionStep } from '../../api/collections'
import { cloneCollectionContent, collectionKindLabels, collectionProblem, collectionReferenceStateLabels, emptyCollection } from '../../api/collections'
import { useCommand } from '../../composables/useCommand'
import { useRecentVisit } from '../../composables/useRecentVisit'
import { useWorkspaceStore } from '../../state/workspace'
import CollectionHistory from './CollectionHistory.vue'
import CollectionQuestionPicker from './CollectionQuestionPicker.vue'
import CollectionReadout from './CollectionReadout.vue'

const props = defineProps<{ id?: string; kind: CollectionKind }>()
const emit = defineEmits<{ changed: []; created: [value: Collection]; close: [] }>()
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const writer = useCommand()
const initial = emptyCollection()
const current = ref<Collection>()
const content = ref(cloneCollectionContent(initial))
const tab = ref<'edit' | 'read' | 'history'>(props.id ? 'read' : 'edit')
const pickerOpen = ref(false)
const replacingStepId = ref<string>()
const choiceNames = reactive<Record<string, { title: string; number: number }>>({})
const savedMessage = ref('')
const createdResult = ref<Collection>()
const serverCopy = ref<Collection>()
const serverError = ref('')
const serverLoading = ref(false)
const deferredReceipt = shallowRef<() => void>()
let alive = true
let serverRequest = 0
let commandEpoch = store.sessionEpoch
const creating = computed(() => !props.id)
const kind = computed(() => current.value?.kind ?? props.kind)
const dirty = computed(() => JSON.stringify(content.value) !== JSON.stringify(current.value?.currentVersion.content ?? initial))
const locked = computed(() => writer.locked.value || Boolean(deferredReceipt.value))
const editable = computed(() => store.sessionActive && !locked.value && (creating.value || current.value?.status === 'ACTIVE'))
const problem = computed(() => collectionProblem(kind.value, content.value))
const excludedQuestions = computed(() => content.value.steps.filter(step => step.id !== replacingStepId.value).map(step => step.questionId))
const detail = useQuery({
  queryKey: computed(() => ['bank-collections', store.sessionEpoch, 'item', props.id]),
  enabled: computed(() => store.sessionActive && Boolean(props.id)),
  queryFn: () => read<Collection>(`/bank/collections/${props.id}`),
})
const latestReferences = computed(() => detail.data.value?.currentVersion.id === current.value?.currentVersion.id ? detail.data.value?.questionReferences : current.value?.questionReferences)
const recordRecentVisit = useRecentVisit('COLLECTION', () => props.id)
const staleReferences = computed(() => latestReferences.value?.filter(reference => reference.state !== 'CURRENT') ?? [])
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })

watch([dirty, locked], ([changed, locked]) => {
  changeState.dirty = changed
  commandState.locked = locked
  store.changes.set('collection-editor', changeState)
  store.changes.set('collection-command', commandState)
}, { immediate: true, flush: 'sync' })
watch(detail.data, value => {
  // 保存回执是已确认的新基线；迟到的旧查询不能让修订号倒退，更不能覆盖未保存输入。
  if (value && value.id === props.id && (!current.value || (!dirty.value && !locked.value && value.revision >= current.value.revision))) {
    accept(value)
    recordRecentVisit(value.id)
  }
}, { immediate: true })
watch(() => route.query.version, value => { if (typeof value === 'string' && props.id) tab.value = 'history' }, { immediate: true })
watch(() => store.sessionEpoch, () => { serverRequest++; serverCopy.value = undefined; serverError.value = ''; serverLoading.value = false })
onUnmounted(() => {
  alive = false
  serverRequest++
  if (store.changes.get('collection-editor') === changeState) store.changes.delete('collection-editor')
  if (store.changes.get('collection-command') === commandState) store.changes.delete('collection-command')
})

function accept(value: Collection) {
  for (const reference of value.questionReferences) choiceNames[reference.versionId] = { title: reference.title, number: reference.number }
  current.value = value
  content.value = cloneCollectionContent(value.currentVersion.content)
  pickerOpen.value = false
  replacingStepId.value = undefined
}
function refresh() { void queryClient.invalidateQueries({ queryKey: ['bank-collections'] }); emit('changed') }
function update(value: Collection, message: string) {
  if (!alive) return
  serverRequest++
  serverLoading.value = false
  accept(value)
  savedMessage.value = message
  serverCopy.value = undefined
  queryClient.setQueryData(['bank-collections', store.sessionEpoch, 'item', value.id], value)
  refresh()
}
function run(path: string, method: string, body: unknown, apply: (value: Collection) => void) {
  if (!store.sessionActive || locked.value) return
  serverRequest++
  serverLoading.value = false
  commandEpoch = store.sessionEpoch
  void writer.run<Collection>(path, method, body, value => {
    if (!alive) return
    // 登录切换后仍保留确定成功的回执，先锁住编辑，等用户接收后再替换输入或导航。
    if (!store.sessionActive || commandEpoch !== store.sessionEpoch) deferredReceipt.value = () => apply(value)
    else apply(value)
  })
}
function retry() { if (store.sessionActive && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (!store.sessionActive || !deferredReceipt.value) return; const apply = deferredReceipt.value; deferredReceipt.value = undefined; apply() }
function save() {
  if (!editable.value || problem.value || (!creating.value && !dirty.value)) return
  if (creating.value) {
    run('/bank/collections', 'POST', { kind: kind.value, content: cloneCollectionContent(content.value) }, value => {
      if (!alive) return
      createdResult.value = value
      content.value = cloneCollectionContent(initial)
      pickerOpen.value = false
      replacingStepId.value = undefined
      changeState.dirty = false
      commandState.locked = false
      refresh()
      emit('created', value)
    })
  } else if (current.value) {
    run(`/bank/collections/${props.id}`, 'PUT', { expectedRevision: current.value.revision, content: cloneCollectionContent(content.value) }, value => update(value, `已保存为 v${value.currentVersion.number}`))
  }
}
function organize(action: 'archive' | 'restore') {
  if (!current.value || dirty.value || locked.value || !store.sessionActive) return
  if (action === 'archive' && !window.confirm('归档后退出默认列表，已有步骤、题目引用和历史版本继续保留。确认归档？')) return
  run(`/bank/collections/${props.id}/${action}`, 'POST', { expectedRevision: current.value.revision }, value => update(value, action === 'archive' ? '已归档，历史版本保留' : '已恢复，题目引用版本保持原样'))
}
function openPicker(stepId?: string) {
  if (!editable.value || (!stepId && content.value.steps.length >= 50)) return
  replacingStepId.value = stepId
  pickerOpen.value = true
}
function chooseQuestion(value: CollectionQuestionChoice) {
  if (!editable.value || excludedQuestions.value.includes(value.id)) return
  const existing = content.value.steps.find(step => step.id === replacingStepId.value)
  choiceNames[value.versionId] = { title: value.title, number: value.versionNumber }
  if (existing) {
    existing.questionId = value.id
    existing.questionVersionId = value.versionId
  } else {
    if (replacingStepId.value || content.value.steps.length >= 50) return
    content.value.steps.push({ id: crypto.randomUUID(), questionId: value.id, questionVersionId: value.versionId, purpose: '', dependsOn: [], checkpoint: '' })
  }
  pickerOpen.value = false
  replacingStepId.value = undefined
}
function stepReference(step: CollectionStep) { return latestReferences.value?.find(reference => reference.stepId === step.id && reference.questionId === step.questionId && reference.versionId === step.questionVersionId) }
function stepTitle(step: CollectionStep): string { return stepReference(step)?.title ?? choiceNames[step.questionVersionId]?.title ?? `题目 ${step.questionId.slice(0, 8)}` }
function stepVersion(step: CollectionStep): string { const number = stepReference(step)?.number ?? choiceNames[step.questionVersionId]?.number; return number ? `v${number}` : `版本 ${step.questionVersionId.slice(0, 8)}` }
function dependencyOptions(stepId: string) { return content.value.steps.flatMap((step, index) => step.id === stepId ? [] : [{ value: step.id, label: `步骤 ${index + 1} · ${stepTitle(step)}` }]) }
function moveStep(index: number, direction: -1 | 1) {
  if (!editable.value || index + direction < 0 || index + direction >= content.value.steps.length) return
  const [step] = content.value.steps.splice(index, 1)
  if (step) content.value.steps.splice(index + direction, 0, step)
}
function removeStep(stepId: string) {
  if (!editable.value) return
  const index = content.value.steps.findIndex(step => step.id === stepId)
  if (index < 0) return
  const dependents = content.value.steps.filter(step => step.dependsOn.includes(stepId))
  const impact = dependents.length ? `另外 ${dependents.length} 个步骤引用了它作为前置，这些前置引用也会移除。` : '其他步骤保持原样。'
  if (!window.confirm(`移出步骤 ${index + 1}？${impact}这只修改当前输入，保存后形成新版本。`)) return
  // 同一编辑动作同时移除入边，不能让界面留下隐藏的悬空依赖再交给服务器猜测。
  for (const step of content.value.steps) step.dependsOn = step.dependsOn.filter(id => id !== stepId)
  content.value.steps.splice(index, 1)
  if (replacingStepId.value === stepId) { pickerOpen.value = false; replacingStepId.value = undefined }
}
function openQuestion(step: CollectionStep) { void router.push({ name: 'bank', query: { item: step.questionId, version: step.questionVersionId } }) }
function changeTab(value: 'edit' | 'read' | 'history') {
  tab.value = value
  if (value !== 'history' && typeof route.query.version === 'string') void router.push({ name: 'collections', query: { ...route.query, version: undefined } })
}
async function compareServer() {
  if (!props.id || !store.sessionActive || locked.value || serverLoading.value) return
  const epoch = store.sessionEpoch
  const request = ++serverRequest
  serverError.value = ''
  serverLoading.value = true
  try {
    const value = await read<Collection>(`/bank/collections/${props.id}`)
    if (alive && store.sessionActive && epoch === store.sessionEpoch && request === serverRequest && !locked.value && value.revision >= (current.value?.revision ?? 0)) serverCopy.value = value
  } catch (error) {
    if (alive && store.sessionActive && epoch === store.sessionEpoch && request === serverRequest) serverError.value = errorText(error)
  } finally { if (alive && request === serverRequest) serverLoading.value = false }
}
function adoptServer(keepLocal: boolean) {
  if (!serverCopy.value || locked.value || !store.sessionActive) return
  if (current.value && serverCopy.value.revision < current.value.revision) {
    serverCopy.value = undefined
    serverError.value = '这份比较副本已过期，请重新读取服务器内容。'
    return
  }
  if (keepLocal && serverCopy.value.status !== 'ACTIVE') return
  const question = keepLocal ? '继续保留当前本地内容，并以已查看的服务器修订作为下次保存基线？保存会生成包含本地内容的新版本，请先确认合并取舍。' : '采用服务器内容并放弃当前未保存的输入？'
  if (!window.confirm(question)) return
  const local = cloneCollectionContent(content.value)
  accept(serverCopy.value)
  if (keepLocal) content.value = local
  serverCopy.value = undefined
  writer.error.value = undefined
  savedMessage.value = keepLocal ? '已对齐服务器修订；本地内容仍需保存，题目版本会重新校验' : '已采用服务器副本'
}
</script>

<template>
  <section class="collection-editor" :aria-label="`${collectionKindLabels[kind]}详情`">
    <p v-if="!creating && !current && detail.isPending.value" class="loading-block" role="status">正在读取集合…</p>
    <div v-else-if="!creating && !current" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text @click="detail.refetch()">重试读取</NButton></div>
    <template v-else>
      <header class="collection-detail-header"><div><p class="field-help">{{ creating ? '新建' : `${collectionKindLabels[kind]} · v${current?.currentVersion.number}` }}</p><h2>{{ creating ? `组织一份${collectionKindLabels[kind]}` : current?.currentVersion.content.title }}</h2></div><button class="icon-button" aria-label="关闭集合详情" @click="emit('close')">×</button></header>
      <div class="collection-save-state" aria-live="polite"><span :class="{ unsaved: dirty }">{{ writer.busy.value ? '正在保存…' : writer.pending.value ? '结果尚未确认，请用原请求重试' : deferredReceipt ? '回执已确认，等待接收结果' : dirty ? '有未保存输入' : creating ? '填写后保存首个版本' : `已读取服务器修订 ${current?.revision}` }}</span><span v-if="current?.status === 'ARCHIVED'" class="status-pill">已归档</span></div>
      <p v-if="current && detail.error.value" class="notice error" role="alert">最新读取未完成，当前输入保留：{{ errorText(detail.error.value) }}<NButton text @click="detail.refetch()">重试读取</NButton></p>
      <p v-if="current && detail.data.value && detail.data.value.revision > current.revision" class="notice">服务器已有更新，本地内容仍使用原修订。<NButton text :disabled="locked || serverLoading" @click="compareServer">查看服务器副本</NButton></p>
      <p v-if="staleReferences.length" class="collection-warning" role="status">{{ staleReferences.length }} 个已保存题目引用发生改版或归档。既有引用仍指向原版本；如需更新，请在编辑中明确更换。</p>
      <div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" size="small" :disabled="!store.sessionActive" @click="retry">用原请求重试</NButton><NButton v-if="current && writer.error.value instanceof ApiError && writer.error.value.status === 409" size="small" :disabled="locked || serverLoading" @click="compareServer">查看服务器副本</NButton></div>
      <p v-if="deferredReceipt" class="notice" role="status">请求完成时登录会话已有变化，本地输入尚未替换。<NButton size="small" :disabled="!store.sessionActive" @click="receiveDeferred">接收已确认结果</NButton></p>
      <p v-if="savedMessage" :class="dirty ? 'field-help' : 'success-text'" role="status">{{ savedMessage }}</p>
      <p v-if="createdResult && creating" class="notice" role="status">{{ collectionKindLabels[createdResult.kind] }}已创建。<button class="text-link" @click="emit('created', createdResult)">打开已创建内容</button></p>
      <p v-if="serverLoading" class="field-help" role="status">正在读取服务器副本…</p><p v-if="serverError" class="notice error" role="alert">{{ serverError }}</p>
      <section v-if="serverCopy" class="collection-server-copy"><h3>服务器副本 · 修订 {{ serverCopy.revision }}</h3><CollectionReadout :kind="serverCopy.kind" :content="serverCopy.currentVersion.content" :references="serverCopy.questionReferences" /><div class="row-actions"><NButton size="small" :disabled="locked" @click="adoptServer(false)">采用服务器内容</NButton><NButton size="small" :disabled="locked || serverCopy.status !== 'ACTIVE'" @click="adoptServer(true)">保留本地并对齐修订</NButton><NButton size="small" @click="serverCopy = undefined">收起比较</NButton></div><p class="field-help">对齐修订不会自动保存；之后保存仍会校验确切题目引用和并发状态。</p></section>
      <nav v-if="current" class="detail-tabs" aria-label="集合详情视图"><button v-for="option in ([['edit', '编辑步骤'], ['read', '已保存内容'], ['history', '历史对照']] as const)" :key="option[0]" :class="{ active: tab === option[0] }" :aria-current="tab === option[0] ? 'page' : undefined" @click="changeTab(option[0])">{{ option[1] }}</button></nav>
      <div v-show="creating || tab === 'edit'" class="collection-form">
        <p v-if="current?.status === 'ARCHIVED'" class="field-help">本集合已归档。恢复后可继续编辑，已有题目版本保持不变。</p>
        <label>标题<NInput v-model:value="content.title" :disabled="!editable" :maxlength="200" :placeholder="kind === 'ROUTE' ? '例如 Java 并发进阶路线' : '例如 后端一面复习题单'" /></label>
        <label>整体用途<NInput v-model:value="content.purpose" type="textarea" :disabled="!editable" :maxlength="5000" :autosize="{ minRows: 2, maxRows: 7 }" placeholder="这组题目适合准备什么，练习时关注什么" /></label>
        <header class="steps-heading"><div><h3>练习步骤 <span>{{ content.steps.length }} / 50</span></h3><p class="field-help">{{ kind === 'ROUTE' ? '前置关系单独声明；上下移动只调整阅读顺序。' : '按顺序组织题目，记录每一步的练习目的。' }}</p></div><NButton size="small" :disabled="!editable || content.steps.length >= 50" @click="openPicker()">添加题目</NButton></header>
        <CollectionQuestionPicker v-if="pickerOpen" :key="replacingStepId ?? 'new'" :disabled="!editable" :exclude-question-ids="excludedQuestions" @choose="chooseQuestion" @close="pickerOpen = false; replacingStepId = undefined" />
        <p v-if="!content.steps.length" class="empty-steps">还没有步骤。可以先保存用途说明，再逐步补充题目。</p>
        <ol class="collection-steps"><li v-for="(step, index) in content.steps" :key="step.id"><header><span class="step-position">{{ index + 1 }}</span><div class="step-title"><button class="text-link" @click="openQuestion(step)">{{ stepTitle(step) }}</button><p>{{ stepVersion(step) }}<span v-if="stepReference(step)" :class="{ 'reference-warning': stepReference(step)?.state !== 'CURRENT' }"> · {{ collectionReferenceStateLabels[stepReference(step)!.state] }}</span><span v-else> · 待保存引用</span></p></div><div class="row-actions"><NButton size="tiny" :disabled="!editable || index === 0" :aria-label="`上移步骤 ${index + 1}`" @click="moveStep(index, -1)">上移</NButton><NButton size="tiny" :disabled="!editable || index === content.steps.length - 1" :aria-label="`下移步骤 ${index + 1}`" @click="moveStep(index, 1)">下移</NButton></div></header><div class="step-fields"><label>练习目的<NInput v-model:value="step.purpose" :disabled="!editable" type="textarea" :maxlength="1000" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="例如：不看资料说明事务隔离与可见性的关系" /></label><label v-if="kind === 'ROUTE'">前置步骤<NSelect v-model:value="step.dependsOn" :options="dependencyOptions(step.id)" multiple :disabled="!editable" placeholder="无前置，可作为路线起点" /></label><label>学习检查点<NInput v-model:value="step.checkpoint" :disabled="!editable" type="textarea" :maxlength="1000" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="写下应能解释的要点；这里不记录完成状态" /></label><div class="row-actions"><NButton size="small" :disabled="!editable" @click="openPicker(step.id)">更换题目／版本</NButton><NButton size="small" quaternary :disabled="!editable" @click="removeStep(step.id)">移出步骤</NButton></div></div></li></ol>
        <p v-if="problem && (dirty || content.title)" class="collection-warning" role="status">{{ problem }}</p>
        <div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!editable || !!problem || (!creating && !dirty)" @click="save">{{ creating ? '保存首个版本' : '保存新版本' }}</NButton><NButton v-if="current" :disabled="dirty || locked || !store.sessionActive" @click="organize(current.status === 'ARCHIVED' ? 'restore' : 'archive')">{{ current.status === 'ARCHIVED' ? '恢复集合' : '归档集合' }}</NButton></div>
        <p class="field-help">未提交输入只保留在当前页面，刷新会丢失。归档／恢复只改变整理状态；检查点是学习目标，完成情况由后续训练与复练记录提供。</p>
      </div>
      <div v-if="current && tab === 'read'" class="collection-saved-layout"><CollectionReadout :kind="kind" :content="current.currentVersion.content" :references="latestReferences" /><aside class="collection-route-detail"><h3>{{ kind === 'ROUTE' ? '路线详情' : '题单详情' }}</h3><h2>{{ current.currentVersion.content.title }}</h2><p class="field-help">已保存 v{{ current.currentVersion.number }} · {{ current.currentVersion.content.steps.length }} 个步骤</p><p class="collection-detail-purpose">{{ current.currentVersion.content.purpose || '未填写整体用途' }}</p><h3>关联题目</h3><button v-for="step in current.currentVersion.content.steps" :key="step.id" class="collection-related-question" @click="openQuestion(step)">{{ stepTitle(step) }}<span>›</span></button><NButton :disabled="!editable" @click="changeTab('edit')">调整顺序与检查点</NButton></aside></div>
      <CollectionHistory v-if="current && tab === 'history'" :id="current.id" :kind="kind" :current-version="current.currentVersion" />
    </template>
  </section>
</template>

<style scoped>
.collection-editor { display: grid; align-content: start; gap: 18px; min-width: 0; padding: 25px clamp(18px, 2.5vw, 32px); }
.collection-detail-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 15px; }
.collection-detail-header > div { min-width: 0; }
.collection-detail-header h2 { margin-top: 5px; overflow-wrap: anywhere; }
.collection-save-state { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; color: var(--muted); font-size: 12px; }
.collection-save-state .unsaved { color: var(--brand-strong); }
.collection-form { display: grid; gap: 22px; }
.collection-form label { display: grid; gap: 7px; font-size: 13px; color: var(--muted); }
.steps-heading { display: flex; justify-content: space-between; gap: 15px; align-items: center; padding-top: 7px; }
.steps-heading h3 span { margin-left: 7px; color: var(--muted); font-size: 12px; font-weight: 400; }
.steps-heading .field-help { margin-top: 5px; }
.collection-warning { padding: 12px; border: 1px solid var(--line); border-radius: 6px; color: var(--danger); font-size: 13px; }
.collection-steps { list-style: none; margin: 0; padding: 0; }
.collection-steps > li { padding: 20px 0 24px; border-top: 1px solid var(--line); }
.collection-steps > li:last-child { padding-bottom: 0; }
.collection-steps > li > header { display: flex; gap: 13px; align-items: start; }
.step-position { width: 20px; flex: none; color: var(--muted); font-size: 14px; }
.step-title { min-width: 0; flex: 1; }
.step-title button { text-align: left; overflow-wrap: anywhere; }
.step-title p { margin-top: 5px; font-size: 12px; color: var(--muted); }
.step-title .reference-warning { color: var(--danger); }
.step-fields { display: grid; gap: 16px; margin: 17px 0 0 33px; }
.collection-server-copy { display: grid; gap: 18px; border: 1px solid var(--line); border-radius: 7px; padding: 18px; }
.empty-steps { padding: 26px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); color: var(--muted); font-size: 13px; }
@media (max-width: 800px) { .collection-steps > li > header { flex-wrap: wrap; } .collection-steps > li > header > .row-actions { margin-left: 33px; } .step-title { flex-basis: calc(100% - 40px); } }
@media (max-width: 500px) { .steps-heading { align-items: start; } .step-fields { margin-left: 0; } }
</style>

<style scoped>
.collection-saved-layout { display: grid; grid-template-columns: minmax(0, 1fr) 265px; gap: 25px; min-width: 0; }.collection-route-detail { padding-left: 22px; border-left: 1px solid var(--line); display: grid; align-content: start; gap: 22px; min-width: 0; }.collection-route-detail h3 { font-size: 22px; }.collection-route-detail h2 { font-size: 25px; overflow-wrap: anywhere; }.collection-detail-purpose { color: var(--muted); white-space: pre-wrap; line-height: 1.8; }.collection-related-question { display: flex; justify-content: space-between; gap: 14px; text-align: left; font-size: 18px; }.collection-related-question span { color: var(--muted); }.collection-saved-layout :deep(.collection-readout-steps) { padding-left: 10px; }.collection-saved-layout :deep(.collection-readout-steps > li) { position: relative; margin-left: 18px; border: 0; border-left: 3px solid #8baba7; padding: 0 0 32px 24px; }.collection-saved-layout :deep(.step-heading) { border: 1px solid var(--line); border-radius: 35px; background: var(--surface-soft); padding: 15px; }.collection-saved-layout :deep(.step-number) { position: absolute; top: 18px; left: -20px; display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid #bf9464; background: #765337; color: #f5e1c7; border-radius: 50%; font-size: 17px; }.collection-saved-layout :deep(.step-heading button) { font-size: 21px; color: var(--ink); }.collection-saved-layout :deep(.step-meta) { font-size: 15px; }.collection-saved-layout :deep(dl) { margin-left: 15px; font-size: 17px; grid-template-columns: 86px minmax(0,1fr); }.collection-saved-layout :deep(.collection-readout > header) { border-bottom: 1px solid var(--line); padding-bottom: 18px; }
@media(max-width:1350px) { .collection-saved-layout { grid-template-columns: 1fr; }.collection-route-detail { border-left: 0; border-top: 1px solid var(--line); padding: 22px 0 0; } }
</style>
