<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NInputNumber, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { Collection, CollectionPage, CollectionQuestionChoice, CollectionSummary } from '../../api/collections'
import type { PreparationItem, PreparationPage, PreparationSummary } from '../../api/preparation'
import type { TrainingContextKind, TrainingCreateSession, TrainingSession } from '../../api/training'
import { emptyTrainingSetup, notifyTrainingChanged, trainingContextLabels, trainingFeedbackOptions, trainingModeLabels, trainingModeOptions, trainingSetupProblem } from '../../api/training'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import CollectionQuestionPicker from '../bank/CollectionQuestionPicker.vue'

const props = defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ created: [session: TrainingSession]; close: [] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const initial = emptyTrainingSetup()
const input = ref<TrainingCreateSession>(structuredClone(initial))
const source = ref<'DIRECT' | 'COLLECTION'>('DIRECT')
const questionPicker = ref(false)
const collection = ref<Collection>()
const collectionKind = ref<'LIST' | 'ROUTE'>('LIST')
const collectionTitle = ref('')
const collectionSearch = ref('')
const collectionCursor = ref<string>()
const collectionCursors = ref<(string | undefined)[]>([])
const collectionBusy = ref(false)
const collectionError = ref('')
const contextPicker = ref(false)
const contextKind = ref<TrainingContextKind>('TARGET')
const contextTitle = ref('')
const contextSearch = ref('')
const contextCursor = ref<string>()
const contextCursors = ref<(string | undefined)[]>([])
const contextBusy = ref(false)
const contextError = ref('')
const questionNames = reactive<Record<string, { title: string; number: number; warning?: string }>>({})
const contextNames = reactive<Record<string, { title: string; number: number }>>({})
const deferred = shallowRef<TrainingSession>()
const createdResult = ref<TrainingSession>()
const modeGuidance = computed(() => input.value.mode === 'LEARNING' ? '学习练习允许查阅资料，提交按学习条件记录。' : input.value.mode === 'MOCK' ? '建议安排 3 道主问，先独立作答；结束前不自动展示参考。' : '建议聚焦 1 道主问，先独立作答，再决定是否查看参考。')
const dirty = computed(() => JSON.stringify(input.value) !== JSON.stringify(initial))
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !store.sessionActive || Boolean(props.disabled) || locked.value)
const problem = computed(() => trainingSetupProblem(input.value))
const stageOptions = computed(() => input.value.stages.filter(stage => stage.trim()).map(stage => ({ value: stage, label: stage })))
const contextOptions = Object.entries(trainingContextLabels).map(([value, label]) => ({ value, label }))
let alive = true
let collectionRequest = 0
let contextRequest = 0
let commandEpoch = store.sessionEpoch
const changeState = reactive({ dirty: false, locked: false })
const collections = useQuery({
  queryKey: computed(() => ['bank-collections', store.sessionEpoch, 'training-picker', collectionKind.value, collectionSearch.value, collectionCursor.value]),
  enabled: computed(() => store.sessionActive && source.value === 'COLLECTION' && !props.disabled),
  queryFn: () => {
    const parameters = new URLSearchParams({ kind: collectionKind.value, status: 'ACTIVE', q: collectionSearch.value, limit: '20' })
    if (collectionCursor.value) parameters.set('cursor', collectionCursor.value)
    return read<CollectionPage>(`/bank/collections?${parameters}`)
  },
})
const materials = useQuery({
  queryKey: computed(() => ['preparation', store.sessionEpoch, 'training-picker', contextKind.value, contextSearch.value, contextCursor.value]),
  enabled: computed(() => store.sessionActive && contextPicker.value && !props.disabled),
  queryFn: () => {
    const parameters = new URLSearchParams({ kind: contextKind.value, status: 'ACTIVE', q: contextSearch.value, limit: '20' })
    if (contextKind.value === 'FACT') parameters.set('verification', 'CONFIRMED')
    if (contextCursor.value) parameters.set('cursor', contextCursor.value)
    return read<PreparationPage>(`/prepare/items?${parameters}`)
  },
})
watch([dirty, locked], ([changed, waiting]) => { changeState.dirty = changed; changeState.locked = waiting; store.changes.set('training-setup', changeState) }, { immediate: true, flush: 'sync' })
watch([blocked, () => store.sessionEpoch], () => { collectionRequest++; contextRequest++; collectionBusy.value = false; contextBusy.value = false }, { flush: 'sync' })
watch(collectionKind, () => { collectionCursor.value = undefined; collectionCursors.value = [] })
watch(contextKind, () => { contextCursor.value = undefined; contextCursors.value = []; contextRequest++; contextBusy.value = false })
watch(contextPicker, () => { contextRequest++; contextBusy.value = false }, { flush: 'sync' })
watch(() => input.value.mode, mode => { input.value.feedbackTiming = mode === 'MOCK' ? 'AFTER_SESSION' : 'AFTER_ANSWER' })
onUnmounted(() => {
  alive = false
  collectionRequest++
  contextRequest++
  if (store.changes.get('training-setup') === changeState) store.changes.delete('training-setup')
})

function chooseSource(value: 'DIRECT' | 'COLLECTION') {
  if (blocked.value || value === source.value) return
  if (input.value.questions.length && !window.confirm('切换题目来源会清空已选择的主问题，其他训练条件保留。继续？')) return
  collectionRequest++
  collectionBusy.value = false
  source.value = value
  input.value.questions = []
  input.value.collectionId = null
  input.value.collectionVersionId = null
  collection.value = undefined
  questionPicker.value = false
}
function chooseQuestion(value: CollectionQuestionChoice) {
  if (blocked.value || source.value !== 'DIRECT' || input.value.questions.length >= 20 || input.value.questions.some(question => question.sourceQuestionId === value.id)) return
  questionNames[value.versionId] = { title: value.title, number: value.versionNumber }
  input.value.questions.push({ sourceQuestionId: value.id, sourceVersionId: value.versionId, stage: input.value.stages[0] ?? '' })
}
async function chooseCollection(summary: CollectionSummary) {
  if (blocked.value || collectionBusy.value) return
  if (input.value.questions.length && !window.confirm('载入这份集合会替换已选主问题，训练模式、阶段和材料保留。继续？')) return
  const epoch = store.sessionEpoch
  const selectedBefore = JSON.stringify(input.value.questions)
  const request = ++collectionRequest
  collectionBusy.value = true
  collectionError.value = ''
  try {
    const value = await read<Collection>(`/bank/collections/${summary.id}`)
    if (!alive || blocked.value || request !== collectionRequest || epoch !== store.sessionEpoch || source.value !== 'COLLECTION') return
    if (selectedBefore !== JSON.stringify(input.value.questions)) { collectionError.value = '读取期间你调整了题目或阶段，已保留这些修改；请重新选择集合。'; return }
    if (value.status !== 'ACTIVE' || value.currentVersion.id !== summary.versionId) { collectionError.value = '集合已改版或归档，请刷新后重新选择。'; void collections.refetch(); return }
    collection.value = value
    input.value.collectionId = value.id
    input.value.collectionVersionId = value.currentVersion.id
    for (const reference of value.questionReferences) questionNames[reference.versionId] = { title: reference.title, number: reference.number, warning: reference.state === 'CURRENT' ? undefined : reference.state === 'SUPERSEDED' ? '题目已改版，本次保留集合原版本' : '题目已停用，开始前由服务器校验' }
    input.value.questions = value.currentVersion.content.steps.length <= 20 ? value.currentVersion.content.steps.map(step => ({ sourceQuestionId: step.questionId, sourceVersionId: step.questionVersionId, stage: input.value.stages[0] ?? '' })) : []
  } catch (error) { if (alive && request === collectionRequest && epoch === store.sessionEpoch) collectionError.value = errorText(error) }
  finally { if (alive && request === collectionRequest) collectionBusy.value = false }
}
function toggleCollectionQuestion(questionId: string, versionId: string) {
  if (blocked.value) return
  const index = input.value.questions.findIndex(question => question.sourceQuestionId === questionId)
  if (index >= 0) input.value.questions.splice(index, 1)
  else if (input.value.questions.length < 20) {
    input.value.questions.push({ sourceQuestionId: questionId, sourceVersionId: versionId, stage: input.value.stages[0] ?? '' })
    const order = collection.value?.currentVersion.content.steps.map(step => step.questionId) ?? []
    input.value.questions.sort((first, second) => order.indexOf(first.sourceQuestionId) - order.indexOf(second.sourceQuestionId))
  }
}
function removeQuestion(id: string) { if (!blocked.value) input.value.questions = input.value.questions.filter(question => question.sourceQuestionId !== id) }
function moveQuestion(index: number, direction: number) {
  if (blocked.value || source.value !== 'DIRECT' || index + direction < 0 || index + direction >= input.value.questions.length) return
  const [question] = input.value.questions.splice(index, 1)
  if (question) input.value.questions.splice(index + direction, 0, question)
}
function renameStage(index: number, value: string) {
  if (blocked.value) return
  const previous = input.value.stages[index]
  input.value.stages[index] = value
  for (const question of input.value.questions) if (question.stage === previous) question.stage = value
}
function addStage() { if (!blocked.value && input.value.stages.length < 8) { let index = input.value.stages.length + 1; while (input.value.stages.includes(`阶段 ${index}`)) index++; input.value.stages.push(`阶段 ${index}`) } }
function removeStage(index: number) {
  if (blocked.value || input.value.stages.length <= 1) return
  const previous = input.value.stages[index]
  const replacement = input.value.stages[index === 0 ? 1 : 0] ?? ''
  if (input.value.questions.some(question => question.stage === previous) && !window.confirm(`此阶段的题目将移入“${replacement}”。继续移除阶段？`)) return
  input.value.stages.splice(index, 1)
  for (const question of input.value.questions) if (question.stage === previous) question.stage = replacement
}
async function chooseContext(summary: PreparationSummary) {
  if (blocked.value || contextBusy.value || !['TARGET', 'PROJECT', 'FACT'].includes(summary.kind)) return
  const kind = summary.kind as TrainingContextKind
  if (input.value.contextLinks.some(link => link.itemId === summary.id)) return
  const limit = kind === 'FACT' ? 10 : 1
  if (input.value.contextLinks.filter(link => link.kind === kind).length >= limit) { contextError.value = `最多选择 ${limit} 项${trainingContextLabels[kind]}，请先移除已有选择。`; return }
  const epoch = store.sessionEpoch
  const request = ++contextRequest
  contextBusy.value = true
  contextError.value = ''
  try {
    const value = await read<PreparationItem>(`/prepare/items/${summary.id}`)
    if (!alive || blocked.value || request !== contextRequest || epoch !== store.sessionEpoch) return
    if (value.status !== 'ACTIVE' || value.kind !== kind || value.currentVersion.id !== summary.versionId || (kind === 'FACT' && value.verification !== 'CONFIRMED')) { contextError.value = '材料版本或核实状态已有变化，请刷新后重新选择。'; void materials.refetch(); return }
    input.value.contextLinks.push({ kind, itemId: value.id, versionId: value.currentVersion.id })
    contextNames[value.currentVersion.id] = { title: value.currentVersion.content.title, number: value.currentVersion.number }
  } catch (error) { if (alive && request === contextRequest && epoch === store.sessionEpoch) contextError.value = errorText(error) }
  finally { if (alive && request === contextRequest) contextBusy.value = false }
}
function removeContext(versionId: string) { if (!blocked.value) input.value.contextLinks = input.value.contextLinks.filter(link => link.versionId !== versionId) }
function searchCollections() { collectionSearch.value = collectionTitle.value.trim(); collectionCursor.value = undefined; collectionCursors.value = [] }
function nextCollections() { if (collections.data.value?.nextCursor) { collectionCursors.value.push(collectionCursor.value); collectionCursor.value = collections.data.value.nextCursor } }
function searchMaterials() { contextSearch.value = contextTitle.value.trim(); contextCursor.value = undefined; contextCursors.value = [] }
function nextMaterials() { if (materials.data.value?.nextCursor) { contextCursors.value.push(contextCursor.value); contextCursor.value = materials.data.value.nextCursor } }
function acceptCreated(value: TrainingSession) {
  createdResult.value = value
  input.value = structuredClone(initial)
  changeState.dirty = false
  changeState.locked = false
  void queryClient.invalidateQueries({ queryKey: ['training'] })
  notifyTrainingChanged()
  emit('created', value)
}
function createPreview() {
  if (blocked.value || problem.value || collectionBusy.value || contextBusy.value) return
  commandEpoch = store.sessionEpoch
  const snapshot = JSON.parse(JSON.stringify(input.value)) as TrainingCreateSession
  void writer.run<TrainingSession>('/training/sessions', 'POST', snapshot, value => {
    if (!alive) return
    if (!store.sessionActive || commandEpoch !== store.sessionEpoch) deferred.value = value
    else acceptCreated(value)
  })
}
function retry() { if (store.sessionActive && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function acceptDeferred() { if (!deferred.value || !store.sessionActive) return; const value = deferred.value; deferred.value = undefined; acceptCreated(value) }
</script>

<template>
  <section class="training-setup" aria-label="训练条件设置">
    <header class="setup-heading"><div><p class="field-help">新训练</p><h2>先固定练习条件</h2><p class="field-help">保存后得到一份预览，核对题目和材料再开始计时。</p></div><button class="icon-button" aria-label="关闭训练设置" @click="emit('close')">×</button></header>
    <p v-if="disabled" class="notice">请先返回当前训练处理资料访问条件，或结束后再建立新的训练。</p>
    <div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" size="small" :disabled="!store.sessionActive" @click="retry">用原请求重试</NButton><p v-if="writer.error.value instanceof ApiError && writer.error.value.status === 409" class="field-help">当前选择继续保留。来源变化时请移除旧选择并重新载入，再建立预览。</p></div>
    <p v-if="deferred" class="notice" role="status">预览创建已确认，但登录会话发生变化。<NButton :disabled="!store.sessionActive" size="small" @click="acceptDeferred">接收已创建预览</NButton></p><p v-if="createdResult" class="notice">预览已保存。<button class="text-link" @click="emit('created', createdResult)">打开预览</button></p>
    <div class="setup-form">
      <label>训练名称<NInput v-model:value="input.title" :disabled="blocked" :maxlength="200" placeholder="例如：事务隔离与并发更新专项" /></label>
      <div class="setup-pair"><label>练习模式<NSelect v-model:value="input.mode" :disabled="blocked" :options="trainingModeOptions" /></label><label>时间预算（分钟）<NInputNumber v-model:value="input.timeBudgetMinutes" :disabled="blocked" :min="1" :max="240" :precision="0" clearable placeholder="不设预算" /></label></div>
      <p class="mode-guidance">{{ modeGuidance }}模式说明本次练习条件，不能证明没有使用外部资料。时间到只提示，不自动提交。</p>
       <details class="setup-advanced"><summary>阶段与反馈约定</summary><div class="setup-advanced-fields"><div class="setup-pair"><label>反馈时机<NSelect v-model:value="input.feedbackTiming" :disabled="blocked || input.mode === 'MOCK'" :options="trainingFeedbackOptions" /></label><label>每个主问的追问额度<NSelect v-model:value="input.followUpLimit" :disabled="blocked" :options="[{ value: 0, label: '0 次' }, { value: 1, label: '最多 1 次' }, { value: 2, label: '最多 2 次' }]" /></label></div><p class="field-help">提交回答后可按此额度授权 AI 追问；每个主问及其后续追问共用次数。反馈时机保留为本场约定；打开评价区会记录接触背景，AI 评价需另行预览并明确授权。</p><div class="stage-heading"><h3>自定义阶段</h3><NButton size="small" :disabled="blocked || input.stages.length >= 8" @click="addStage">添加阶段</NButton></div><div v-for="(stage, index) in input.stages" :key="index" class="stage-row"><NInput :value="stage" :disabled="blocked" :maxlength="80" :aria-label="`阶段 ${index + 1} 名称`" @update:value="renameStage(index, $event)" /><NButton size="small" :disabled="blocked || input.stages.length <= 1" @click="removeStage(index)">移除</NButton></div></div></details>
      <section class="setup-section"><header><div><h3>主问题 <span>{{ input.questions.length }} / 20</span></h3><p class="field-help">题目固定到确切版本，默认建议{{ input.mode === 'MOCK' ? '三道' : '一道' }}；可按本次需要调整。</p></div><div class="row-actions"><NButton size="small" :type="source === 'DIRECT' ? 'primary' : 'default'" :disabled="blocked" @click="chooseSource('DIRECT')">从题库选</NButton><NButton size="small" :type="source === 'COLLECTION' ? 'primary' : 'default'" :disabled="blocked" @click="chooseSource('COLLECTION')">从题单／路线选</NButton></div></header>
        <template v-if="source === 'DIRECT'"><NButton size="small" :disabled="blocked || input.questions.length >= 20" @click="questionPicker = !questionPicker">{{ questionPicker ? '收起题目选择' : '添加主问题' }}</NButton><CollectionQuestionPicker v-if="questionPicker" :disabled="blocked" :exclude-question-ids="input.questions.map(question => question.sourceQuestionId)" @choose="chooseQuestion" @close="questionPicker = false" /></template>
        <div v-else class="setup-picker"><div class="setup-pair"><NSelect v-model:value="collectionKind" :disabled="blocked || collectionBusy" :options="[{ value: 'LIST', label: '题单' }, { value: 'ROUTE', label: '学习路线' }]" aria-label="集合类型" /><form class="title-search" @submit.prevent="searchCollections"><NInput v-model:value="collectionTitle" :disabled="blocked || collectionBusy" :maxlength="100" placeholder="按保存标题筛选" aria-label="集合标题" /><NButton attr-type="submit" :disabled="blocked || collectionBusy">查找</NButton></form></div><p v-if="collections.isPending.value" class="field-help" role="status">正在读取集合…</p><p v-if="collections.error.value || collectionError" class="notice error" role="alert">{{ collectionError || errorText(collections.error.value) }}<NButton text @click="collections.refetch()">刷新列表</NButton></p><div class="setup-picker-results"><button v-for="item in collections.data.value?.items" :key="item.id" :disabled="blocked || collectionBusy" @click="chooseCollection(item)"><strong>{{ item.title }}</strong><span>v{{ item.versionNumber }} · {{ item.stepCount }} 道题{{ collection?.id === item.id ? ' · 已载入' : '' }}</span></button></div><p v-if="collections.data.value && !collections.data.value.items.length" class="field-help">没有匹配的使用中集合。可调整筛选或从题库直接选择。</p><div v-if="collectionCursors.length || collections.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="blocked || collectionBusy || !collectionCursors.length" @click="collectionCursor = collectionCursors.pop()">上一页</NButton><NButton size="small" :disabled="blocked || collectionBusy || !collections.data.value?.nextCursor" @click="nextCollections">下一页</NButton></div><section v-if="collection" class="collection-options"><h4>{{ collection.currentVersion.content.title }} · v{{ collection.currentVersion.number }}</h4><p class="field-help">保留集合中的原题版本和阅读顺序，不自动重排前置依赖。最多选择 20 道；路线检查点不作为题干。</p><label v-for="step in collection.currentVersion.content.steps" :key="step.id" class="collection-check"><input type="checkbox" :checked="input.questions.some(question => question.sourceQuestionId === step.questionId)" :disabled="blocked || (!input.questions.some(question => question.sourceQuestionId === step.questionId) && input.questions.length >= 20)" @change="toggleCollectionQuestion(step.questionId, step.questionVersionId)" /><span>{{ questionNames[step.questionVersionId]?.title ?? '题目版本' }} · v{{ questionNames[step.questionVersionId]?.number }}</span></label><p v-if="!collection.currentVersion.content.steps.length" class="field-help">本集合没有题目，请选择其他集合。</p></section></div>
        <ol class="selected-questions"><li v-for="(question, index) in input.questions" :key="question.sourceQuestionId"><header><span class="question-number">{{ index + 1 }}</span><div><strong>{{ questionNames[question.sourceVersionId]?.title ?? '已选题目' }}</strong><p class="field-help">v{{ questionNames[question.sourceVersionId]?.number ?? '?' }}</p><p v-if="questionNames[question.sourceVersionId]?.warning" class="source-warning">{{ questionNames[question.sourceVersionId]?.warning }}</p></div><div class="row-actions"><NButton v-if="source === 'DIRECT'" size="tiny" :disabled="blocked || index === 0" @click="moveQuestion(index, -1)">上移</NButton><NButton v-if="source === 'DIRECT'" size="tiny" :disabled="blocked || index === input.questions.length - 1" @click="moveQuestion(index, 1)">下移</NButton><NButton size="tiny" :disabled="blocked" @click="removeQuestion(question.sourceQuestionId)">移除</NButton></div></header><label>所属阶段<NSelect v-model:value="question.stage" :disabled="blocked" :options="stageOptions" /></label></li></ol><p v-if="!input.questions.length" class="field-help">尚未选择主问题。预览和开始训练至少需要一道题。</p>
      </section>
      <section class="setup-section"><header><div><h3>固定背景 <span>可选</span></h3><p class="field-help">最多 1 个目标、1 个项目与 10 条已核实事实。不自动带入关联知识、附件或表达稿。</p></div><NButton size="small" :disabled="blocked" @click="contextPicker = !contextPicker">{{ contextPicker ? '收起材料选择' : '选择背景材料' }}</NButton></header><div v-for="link in input.contextLinks" :key="link.versionId" class="selected-context"><div><strong>{{ contextNames[link.versionId]?.title ?? trainingContextLabels[link.kind] }}</strong><p>{{ trainingContextLabels[link.kind] }} · v{{ contextNames[link.versionId]?.number ?? '?' }}</p></div><NButton size="small" :disabled="blocked" @click="removeContext(link.versionId)">移除</NButton></div><p v-if="!input.contextLinks.length" class="field-help">不指定目标或项目，也可以独立开始训练。</p><div v-if="contextPicker" class="setup-picker"><NSelect v-model:value="contextKind" :disabled="blocked || contextBusy" :options="contextOptions" aria-label="背景材料类型" /><form class="title-search" @submit.prevent="searchMaterials"><NInput v-model:value="contextTitle" :disabled="blocked || contextBusy" :maxlength="100" placeholder="按已保存标题查找" aria-label="背景材料标题" /><NButton attr-type="submit" :disabled="blocked || contextBusy">查找</NButton></form><p v-if="materials.isPending.value" class="field-help" role="status">正在读取材料…</p><p v-if="materials.error.value || contextError" class="notice error" role="alert">{{ contextError || errorText(materials.error.value) }}<NButton text @click="materials.refetch()">刷新列表</NButton></p><div class="setup-picker-results"><button v-for="item in materials.data.value?.items" :key="item.id" :disabled="blocked || contextBusy || input.contextLinks.some(link => link.itemId === item.id)" @click="chooseContext(item)"><strong>{{ item.title }}</strong><span>v{{ item.versionNumber }}{{ input.contextLinks.some(link => link.itemId === item.id) ? ' · 已选择' : '' }}</span></button></div><p v-if="materials.data.value && !materials.data.value.items.length" class="field-help">没有匹配的有效材料；事实须先核实后才能选择。</p><div v-if="contextCursors.length || materials.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="blocked || contextBusy || !contextCursors.length" @click="contextCursor = contextCursors.pop()">上一页</NButton><NButton size="small" :disabled="blocked || contextBusy || !materials.data.value?.nextCursor" @click="nextMaterials">下一页</NButton></div></div></section>
      <p v-if="problem && dirty" class="source-warning" role="status">{{ problem }}</p><div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !!problem || collectionBusy || contextBusy" @click="createPreview">保存并查看固定预览</NButton><span class="field-help">{{ trainingModeLabels[input.mode] }} · {{ input.questions.length }} 道主问</span></div><p class="field-help">尚未保存的设置只保留在当前页面，刷新会丢失。预览保存后仍需明确点击开始。</p>
    </div>
  </section>
</template>

<style scoped>
.training-setup { min-width: 0; padding: 25px 27px 30px; }
.setup-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; margin-bottom: 23px; } .setup-heading h2 { font-size: 22px; margin: 5px 0 8px; }
.setup-form { display: grid; gap: 19px; } .setup-form label { display: grid; gap: 7px; color: var(--muted); font-size: 12px; min-width: 0; }
.setup-pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; align-items: end; }
.mode-guidance { padding: 13px; background: var(--surface-soft); border-radius: 6px; font-size: 13px; max-width: 75ch; }
.setup-advanced { border-top: 1px solid var(--line); padding-top: 15px; } summary { cursor: pointer; font-size: 14px; } .setup-advanced-fields { display: grid; gap: 13px; margin-top: 18px; }
.stage-heading, .stage-row { display: flex; align-items: center; gap: 10px; justify-content: space-between; } .stage-heading h3 { font-size: 14px; }
.setup-section { display: grid; gap: 14px; border-top: 1px solid var(--line); padding-top: 20px; min-width: 0; } .setup-section > header { display: flex; justify-content: space-between; align-items: start; gap: 15px; } h3 { font-size: 16px; } h3 span { font-size: 12px; color: var(--muted); margin-left: 7px; font-weight: 400; } .setup-section header p { margin-top: 7px; max-width: 65ch; }
.setup-picker { display: grid; gap: 12px; padding: 14px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 7px; } .setup-picker .title-search { margin: 0; }
.setup-picker-results { display: grid; max-height: 260px; overflow: auto; } .setup-picker-results > button { display: grid; gap: 6px; padding: 11px 5px; border-bottom: 1px solid var(--line); text-align: left; } .setup-picker-results strong { font-size: 13px; overflow-wrap: anywhere; } .setup-picker-results span { font-size: 12px; color: var(--muted); }
.collection-options { display: grid; gap: 10px; padding-top: 13px; border-top: 1px solid var(--line); } .collection-options h4 { font-size: 14px; overflow-wrap: anywhere; } .collection-options .collection-check { display: flex; align-items: start; gap: 8px; padding: 5px 0; color: var(--ink); } .collection-check input { margin-top: 4px; }
.selected-questions { list-style: none; margin: 0; padding: 0; } .selected-questions li { display: grid; gap: 11px; padding: 15px 0; border-bottom: 1px solid var(--line); } .selected-questions header { display: flex; gap: 12px; align-items: start; } .selected-questions header > div:nth-child(2) { flex: 1; min-width: 0; } .selected-questions strong { font-size: 14px; overflow-wrap: anywhere; } .question-number { width: 20px; flex: none; color: var(--muted); } .selected-questions label { max-width: 300px; margin-left: 32px; }
.source-warning { color: var(--danger); font-size: 12px; max-width: 75ch; margin-top: 5px; }
.selected-context { display: flex; justify-content: space-between; align-items: center; gap: 15px; padding: 10px 0; border-bottom: 1px solid var(--line); } .selected-context strong { font-size: 13px; overflow-wrap: anywhere; } .selected-context p { font-size: 12px; color: var(--muted); margin-top: 5px; }
@media (max-width: 800px) { .setup-pair { grid-template-columns: 1fr; } .setup-section > header { flex-wrap: wrap; } .selected-questions header { flex-wrap: wrap; } .selected-questions header > .row-actions { padding-left: 32px; } }
@media (max-width: 560px) { .training-setup { padding: 22px 17px; } }
</style>
