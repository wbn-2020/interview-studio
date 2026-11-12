<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { ItemDetail, ItemSummary } from '../../api/client'
import type { AiAdoptCandidate, AiAdoptionMode, AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate, aiDraftPromptVersion, aiMaterialDraftSourcesMatch, aiPreparationChecksMatch, aiTrainingDraftManifestMatches } from '../../api/ai'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import KnowledgePicker from '../KnowledgePicker.vue'
import MarkdownPreview from '../MarkdownPreview.vue'
import { aiReviewDraftCandidateValid, aiReviewDraftKindLabels, aiReviewDraftManifestMatches, aiReviewDraftTaskValid } from '../../api/aiReviewDraft'

const MarkdownEditor = defineAsyncComponent(() => import('../MarkdownEditor.vue'))
const props = defineProps<{ task: AiTask; disabled?: boolean; peerLocked?: boolean }>()
const emit = defineEmits<{ received: [task: AiTask]; refreshed: [task: AiTask]; locked: [value: boolean] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const opened = ref(false)
const mode = ref<AiAdoptionMode>('NEW')
const kind = ref<'NOTE' | 'CONCEPT'>('NOTE')
const title = ref('')
const body = ref('')
const confirmed = ref(false)
const taskRevision = ref<number>()
const taskFingerprint = ref<string>()
const target = shallowRef<ItemDetail>()
const targetNeedsReview = ref(false)
const targetLoading = ref(false)
const taskLoading = ref(false)
const targetError = ref('')
const taskError = ref('')
const pickerOpen = ref(false)
const pickerGeneration = ref(0)
const comparisonOpen = ref(false)
const deferred = shallowRef<AiTask>()
const message = ref('')
const draftState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let initialTitle = ''
let initialBody = ''
let alive = true
let targetRequest = 0
let taskRequest = 0
let commandEpoch = store.sessionEpoch

const decision = computed(() => props.task.decision ?? null)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !store.sessionActive || Boolean(props.disabled) || Boolean(props.peerLocked) || locked.value)
const reading = computed(() => targetLoading.value || taskLoading.value)
const undecided = computed(() => props.task.task.purpose === 'KNOWLEDGE_DRAFT' && props.task.task.status === 'SUCCEEDED' && Boolean(props.task.candidate) && !decision.value)
const trainingDraft = computed(() => props.task.manifest.trainingDraft != null || props.task.manifest.promptVersion === 'training-knowledge-draft-1')
const reviewDraft = computed(() => props.task.manifest.reviewDraft != null || props.task.manifest.promptVersion === 'review-knowledge-draft-1')
const sourceChecksValid = computed(() => validManifest(props.task.manifest) && (!reviewDraft.value
  || Boolean(props.task.manifest.reviewDraft && aiReviewDraftCandidateValid(props.task.candidate, props.task.manifest.reviewDraft))))
const manifestFingerprint = computed(() => sourceChecksValid.value ? aiCandidateManifestFingerprint(props.task.manifest) : undefined)
const candidateBody = computed(() => props.task.candidate?.sections.map(section => `## ${section.heading}\n\n${section.body}`).join('\n\n') ?? '')
const dirty = computed(() => opened.value && (title.value !== initialTitle || body.value !== initialBody
  || mode.value !== 'NEW' || kind.value !== 'NOTE' || Boolean(target.value) || confirmed.value))
const taskStale = computed(() => taskRevision.value !== props.task.task.revision || !manifestFingerprint.value
  || taskFingerprint.value !== manifestFingerprint.value)
const targetUnpublished = computed(() => Boolean(target.value && (target.value.draft.title !== target.value.published.title || target.value.draft.body !== target.value.published.body)))
const targetProblem = computed(() => {
  if (mode.value === 'NEW') return ''
  if (!target.value) return '请选择要追加发布版本的知识。'
  if (targetNeedsReview.value) return '请重新读取目标，核对发布版本与草稿。'
  if (target.value.status !== 'ACTIVE') return '该目标已归档，请选择其他知识或另存为新知识。'
  if (targetUnpublished.value) return '目标有尚未发布的草稿。请先处理该草稿，或将本次改写另存为新知识。'
  return ''
})
const contentProblem = computed(() => !title.value.trim() || title.value.length > 200 ? '请填写 1–200 字符的标题。'
  : !body.value.trim() || body.value.length > 100000 ? '正文不能为空，且不能超过 100000 字符。' : '')
const canAdopt = computed(() => opened.value && !blocked.value && !reading.value && undecided.value
  && props.task.sourcesCurrent && sourceChecksValid.value && !taskStale.value && !targetProblem.value && !contentProblem.value)
const modeOptions = [{ label: '另存为新知识', value: 'NEW' }, { label: '追加到已有知识', value: 'EXISTING' }]
const kindOptions = [{ label: '独立笔记', value: 'NOTE' }, { label: '知识概念', value: 'CONCEPT' }]

watch([dirty, locked], ([changed, waiting]) => {
  draftState.dirty = changed
  commandState.locked = waiting
  store.changes.set('ai-adoption', draftState)
  store.changes.set('ai-adoption-command', commandState)
  emit('locked', waiting)
}, { immediate: true, flush: 'sync' })
watch([title, body, mode, kind, target, taskRevision, () => props.task.task.revision, () => props.task.sourcesCurrent, manifestFingerprint, decision], () => { confirmed.value = false }, { flush: 'sync' })
watch(mode, () => { targetRequest++; targetLoading.value = false; pickerOpen.value = false }, { flush: 'sync' })
watch(pickerOpen, () => { targetRequest++; targetLoading.value = false }, { flush: 'sync' })
watch([() => store.sessionEpoch, () => store.sessionActive, () => props.disabled], () => {
  cancelReads()
  taskRevision.value = undefined
  targetNeedsReview.value = Boolean(target.value)
  confirmed.value = false
}, { flush: 'sync' })
watch(() => props.peerLocked, value => { if (value) { cancelReads(); confirmed.value = false } }, { flush: 'sync' })
watch(writer.error, error => {
  if (!(error instanceof ApiError) || writer.pending.value) return
  confirmed.value = false
  if (['REVISION_CONFLICT', 'AI_STATE_CONFLICT', 'AI_CANDIDATE_DECIDED', 'AI_SOURCE_CHANGED'].includes(error.code)) taskRevision.value = undefined
  // 修订冲突可能来自任务或目标草稿，两处基线都需明确重读，不能只提升任务 CAS。
  if (['REVISION_CONFLICT', 'VERSION_CONFLICT', 'DRAFT_UNPUBLISHED', 'KNOWLEDGE_ARCHIVED', 'KNOWLEDGE_NOT_FOUND', 'VERSION_NOT_FOUND'].includes(error.code)) targetNeedsReview.value = Boolean(target.value)
})
onUnmounted(() => {
  alive = false
  cancelReads()
  if (store.changes.get('ai-adoption') === draftState) store.changes.delete('ai-adoption')
  if (store.changes.get('ai-adoption-command') === commandState) store.changes.delete('ai-adoption-command')
  emit('locked', false)
})

function validManifest(manifest: AiTask['manifest']): boolean {
  if (manifest.purpose !== 'KNOWLEDGE_DRAFT') return false
  if (manifest.reviewDraft != null || manifest.promptVersion === 'review-knowledge-draft-1') return aiReviewDraftManifestMatches(manifest)
  if (manifest.trainingDraft != null || manifest.promptVersion === 'training-knowledge-draft-1') return aiTrainingDraftManifestMatches(manifest)
  const count = manifest.sources.length + (manifest.materialDraftSources?.length ?? 0)
  return count >= 1 && count <= 5 && manifest.promptVersion === aiDraftPromptVersion(manifest)
    && manifest.training == null && manifest.evaluation == null && manifest.contextChecks == null
    && manifest.materialQa == null && manifest.expressionType == null && manifest.preparationPlan == null
    && aiPreparationChecksMatch(manifest) && aiMaterialDraftSourcesMatch(manifest)
}
function cancelReads() {
  targetRequest++
  taskRequest++
  targetLoading.value = false
  taskLoading.value = false
  pickerGeneration.value++
  pickerOpen.value = false
}
function open() {
  if (blocked.value || !undecided.value || opened.value) return
  initialTitle = props.task.candidate!.title
  initialBody = candidateBody.value
  title.value = initialTitle
  body.value = initialBody
  mode.value = 'NEW'
  kind.value = 'NOTE'
  target.value = undefined
  targetNeedsReview.value = false
  taskRevision.value = props.task.task.revision
  taskFingerprint.value = manifestFingerprint.value
  confirmed.value = false
  targetError.value = ''
  taskError.value = ''
  message.value = ''
  comparisonOpen.value = false
  opened.value = true
}
function close() {
  if (blocked.value || !store.canLeave(['ai-adoption', 'ai-adoption-command'])) return
  cancelReads()
  opened.value = false
  confirmed.value = false
}
function choose(summary: ItemSummary) { void loadTarget(summary.id, true) }
async function loadTarget(itemId: string, fromPicker = false) {
  if (blocked.value || !opened.value || !undecided.value || mode.value !== 'EXISTING' || targetLoading.value || (fromPicker && !pickerOpen.value)) return
  const epoch = store.sessionEpoch
  const request = ++targetRequest
  targetLoading.value = true
  targetNeedsReview.value = Boolean(target.value)
  targetError.value = ''
  confirmed.value = false
  try {
    const value = await read<ItemDetail>(`/knowledge/items/${itemId}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || request !== targetRequest || !opened.value || mode.value !== 'EXISTING'
      || (fromPicker ? !pickerOpen.value : target.value?.id !== itemId)) return
    if (value.id !== itemId || value.currentVersionId !== value.published.id) { targetError.value = '目标对象或发布版本不匹配，请重新读取。'; return }
    // 目标读取只更新对照与双基线；等待期间用户改写的标题、正文不回填。
    target.value = value
    targetNeedsReview.value = false
    message.value = '已读取目标发布版本和草稿。本次改写保持原样，请对照后再确认。'
    if (fromPicker) pickerOpen.value = false
  } catch (error) {
    if (alive && request === targetRequest && epoch === store.sessionEpoch) targetError.value = errorText(error)
  } finally { if (alive && request === targetRequest) targetLoading.value = false }
}
async function reviewTask() {
  if (blocked.value || taskLoading.value) return
  const epoch = store.sessionEpoch
  const request = ++taskRequest
  taskLoading.value = true
  taskError.value = ''
  confirmed.value = false
  try {
    const value = await read<AiTask>(`/ai/tasks/${props.task.task.id}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || request !== taskRequest) return
    if (value.task.id !== props.task.task.id || value.task.purpose !== 'KNOWLEDGE_DRAFT' || value.task.revision < props.task.task.revision
      || !validManifest(value.manifest) || !aiReviewDraftTaskValid(value)
      || aiCandidateManifestFingerprint(value.manifest) !== aiCandidateManifestFingerprint(props.task.manifest)) {
      taskRevision.value = undefined
      taskError.value = '任务状态或来源清单不一致，请重新核对；本地改写继续保留。'
      return
    }
    emit('refreshed', value)
    taskRevision.value = value.task.revision
    taskFingerprint.value = aiCandidateManifestFingerprint(value.manifest)
    message.value = '已重新核对任务状态，本地改写继续保留。'
  } catch (error) {
    if (alive && request === taskRequest && epoch === store.sessionEpoch) taskError.value = errorText(error)
  } finally { if (alive && request === taskRequest) taskLoading.value = false }
}
function receive(value: AiTask) {
  if (value.task.id !== props.task.task.id || value.task.purpose !== 'KNOWLEDGE_DRAFT' || !value.decision) {
    taskRevision.value = undefined
    taskError.value = '服务器回执没有对应的候选决定，请核对任务状态后继续。'
    return
  }
  opened.value = false
  confirmed.value = false
  cancelReads()
  message.value = value.decision.status === 'ADOPTED' ? '已将最终正文发布到知识库，采用记录和当时来源已保留。' : '已忽略这份候选，原候选和费用记录继续保留。'
  emit('received', value)
  void queryClient.invalidateQueries({ queryKey: ['knowledge', store.sessionEpoch] })
  void queryClient.invalidateQueries({ queryKey: ['ai', store.sessionEpoch, 'origins'] })
}
function run(action: 'adopt' | 'ignore', payload: AiAdoptCandidate | { expectedRevision: number }) {
  if (blocked.value || reading.value) return
  const authorizedFingerprint = action === 'adopt' ? manifestFingerprint.value : undefined
  if (action === 'adopt' && !authorizedFingerprint) return
  commandEpoch = store.sessionEpoch
  message.value = ''
  taskError.value = ''
  const taskId = props.task.task.id
  const adoption = action === 'adopt' ? payload as AiAdoptCandidate : undefined
  void writer.run<AiTask>(`/ai/tasks/${taskId}/${action}`, 'POST', payload, value => {
    if (!alive) return
    const saved = value?.decision
    const decisionMatches = saved && (adoption
      ? saved.status === 'ADOPTED' && saved.mode === adoption.mode && Boolean(saved.targetItemId && saved.targetVersionId)
        && (adoption.mode !== 'EXISTING' || saved.targetItemId === adoption.targetItemId)
      : saved.status === 'IGNORED' && saved.mode === null && saved.targetItemId === null && saved.targetVersionId === null)
    // 先核对原命令的决定与目标，再延后接收；异常回执由命令层保留原键和人工改写。
    if (value?.task?.id !== taskId || value.task.purpose !== 'KNOWLEDGE_DRAFT' || !decisionMatches
      || action === 'adopt' && (!validManifest(value.manifest) || !aiReviewDraftTaskValid(value) || aiCandidateManifestFingerprint(value.manifest) !== authorizedFingerprint)) {
      taskRevision.value = undefined
      throw new ApiError(502, 'AI_TASK_MISMATCH', '候选决定回执与本次请求或授权清单不一致，改写和原请求继续保留，请用原请求重试。')
    }
    if (commandEpoch !== store.sessionEpoch || !store.sessionActive || props.disabled || props.peerLocked) deferred.value = value
    else receive(value)
  })
}
function adopt() {
  if (!canAdopt.value || !confirmed.value || taskRevision.value === undefined) return
  const existing = mode.value === 'EXISTING' ? target.value : undefined
  const payload: AiAdoptCandidate = {
    expectedRevision: taskRevision.value, mode: mode.value, kind: mode.value === 'NEW' ? kind.value : null,
    targetItemId: existing?.id ?? null, expectedTargetVersionId: existing?.currentVersionId ?? null,
    expectedTargetDraftRevision: existing?.draft.revision ?? null, title: title.value, body: body.value, confirmed: true,
  }
  run('adopt', payload)
}
function ignore() {
  if (blocked.value || reading.value || !undecided.value) return
  if (!window.confirm(`忽略后，这项任务不能再采用；知识和费用保持原记录。${dirty.value ? '当前未保存的改写也会放弃。' : ''}确认忽略这份候选？`)) return
  run('ignore', { expectedRevision: props.task.task.revision })
}
function retryCommand() {
  if (!store.sessionActive || props.disabled || props.peerLocked || writer.busy.value) return
  commandEpoch = store.sessionEpoch
  void writer.retry()
}
function receiveDeferred() {
  if (!store.sessionActive || props.disabled || props.peerLocked || !deferred.value) return
  const value = deferred.value
  deferred.value = undefined
  receive(value)
}
</script>

<template>
  <section class="candidate-adoption" aria-label="审阅与采用知识候选">
    <header><h3>审阅与采用</h3><p class="field-help">采用将你确认的最终正文发布到本应用知识库，不会发起模型请求或增加模型费用。采用后仍需自行核实技术结论。</p></header>
    <p v-if="trainingDraft" class="field-help">此次采用保存最终知识版本及确切训练来源。原答、评价和反馈决定保持原记录；项目事实、知识关系、回忆排程与复练状态需另行处理。原引用不作为人工最终正文的逐句证明。</p>
    <p v-if="reviewDraft && task.manifest.reviewDraft" class="field-help">此次来源为{{ aiReviewDraftKindLabels[task.manifest.reviewDraft.kind] }}。采用保存最终知识版本与确切来源身份；原反馈决定及面后回忆保持原记录，个人自述、外部意见和回忆均不升级为已核实事实。原引用只定位模型原候选，不能证明人工改写后的每句话。</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!store.sessionActive || !!disabled || !!peerLocked" @click="retryCommand">用原请求重试</NButton><NButton v-else text :disabled="blocked || taskLoading" @click="reviewTask">核对最新任务状态</NButton></p>
    <p v-if="deferred" class="notice" role="status">候选决定已由服务器确认，返回时登录或资料条件发生了变化。<NButton text :disabled="!store.sessionActive || !!disabled || !!peerLocked" @click="receiveDeferred">接收已确认决定</NButton></p>
    <p v-if="taskError" class="notice error" role="alert">{{ taskError }}</p>
    <div v-if="decision" class="decision-record">
      <strong>{{ decision.status === 'ADOPTED' ? '这份候选已采用' : '这份候选已忽略' }}</strong><span>{{ aiDate(decision.createdAt) }}</span>
      <p class="field-help">每项任务只能决定一次。原候选、引用和费用记录继续保留。</p>
      <RouterLink v-if="decision.status === 'ADOPTED' && decision.targetItemId && decision.targetVersionId" class="text-link" :to="{ name: 'knowledge', query: { item: decision.targetItemId, version: decision.targetVersionId } }">查看采用时发布的确切版本</RouterLink>
      <p v-if="opened" class="notice">这项任务已有决定，当前改写仍保留供你查看；关闭表单前会提示未保存修改。</p>
    </div>
    <p v-if="undecided && !task.sourcesCurrent" class="notice">{{ reviewDraft ? '复盘来源、反馈决定或回忆修订已变化，当前不能采用这份候选。人工改写继续保留；请核对确切来源，或重新选择片段创建新任务。' : trainingDraft ? '当时固定的题面、所选回答片段或反馈当前不可用，暂不能采用。人工改写继续保留，仍可忽略候选；追加新答案本身不会使这次历史提交失效。' : '原来源已改版、归档或不可用，不能直接采用这份旧候选。可查看并忽略，或回到创建入口重新选择来源。' }}</p>
    <p v-if="undecided && !sourceChecksValid" class="notice">候选来源清单不完整或与授权不一致，暂不能采用。请重新读取任务核对；改写继续保留，仍可忽略候选。<NButton text :disabled="blocked || taskLoading" @click="reviewTask">重新核对任务</NButton></p>
    <div class="row-actions"><NButton v-if="undecided && !opened" type="primary" :disabled="blocked" @click="open">改写并采用</NButton><NButton v-if="undecided" :disabled="blocked || reading" @click="ignore">忽略候选</NButton></div>

    <div v-if="opened" class="adoption-form">
      <p class="field-help">改写只保存在当前页面。采用会立即发布新版本；刷新或离开前请完成保存或明确放弃。</p>
      <div class="destination-fields"><label :for="`adoption-mode-${task.task.id}`">保存方式<NSelect :id="`adoption-mode-${task.task.id}`" v-model:value="mode" :options="modeOptions" :disabled="blocked || !!decision" /></label><label v-if="mode === 'NEW'" :for="`adoption-kind-${task.task.id}`">新知识类型<NSelect :id="`adoption-kind-${task.task.id}`" v-model:value="kind" :options="kindOptions" :disabled="blocked || !!decision" /></label></div>
      <p v-if="mode === 'NEW'" class="field-help">创建首个发布版本，主题暂时留空，之后可到知识工作区整理。</p>
      <section v-else class="target-selection">
        <div class="row-actions"><NButton :disabled="blocked || !!decision || targetLoading" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起选择器' : target ? '更换目标知识' : '选择目标知识' }}</NButton><NButton v-if="target" :loading="targetLoading" :disabled="blocked || !!decision || targetLoading" @click="loadTarget(target.id)">重新读取目标并保留改写</NButton></div>
        <KnowledgePicker v-if="pickerOpen" :key="pickerGeneration" :disabled="blocked || !!decision || targetLoading" @choose="choose" />
        <p v-if="targetLoading" class="field-help" role="status">正在读取目标发布版本和草稿；你可以继续修改下方最终正文。</p>
        <p v-if="targetError" class="notice error" role="alert">{{ targetError }}</p><p v-if="targetProblem" class="notice">{{ targetProblem }}</p>
        <template v-if="target">
          <p class="field-help">目标：{{ target.kind === 'CONCEPT' ? '概念' : '笔记' }}「{{ target.published.title }}」· 发布 v{{ target.published.number }} · 草稿修订 {{ target.draft.revision }}。最终正文会作为该知识的新发布版本，并同步草稿；原版本保留。</p>
          <div class="target-comparison"><details open><summary>目标发布原文 · v{{ target.published.number }}</summary><h4>{{ target.published.title }}</h4><MarkdownPreview :value="target.published.body" /></details><details :open="targetUnpublished"><summary>目标草稿 · 修订 {{ target.draft.revision }}{{ targetUnpublished ? ' · 有未发布修改' : ' · 与发布一致' }}</summary><h4>{{ target.draft.title }}</h4><MarkdownPreview :value="target.draft.body" /></details></div>
        </template>
      </section>
      <label :for="`adoption-title-${task.task.id}`">最终标题<NInput :id="`adoption-title-${task.task.id}`" v-model:value="title" :maxlength="200" :disabled="blocked || !!decision" /></label>
      <div class="body-field"><label>最终正文 <span class="field-help">Markdown · {{ body.length }} / 100000 字符</span></label><MarkdownEditor v-model="body" :disabled="blocked || !!decision" /></div>
      <NButton class="comparison-toggle" size="small" :aria-expanded="comparisonOpen" @click="comparisonOpen = !comparisonOpen">{{ comparisonOpen ? '收起正文对照' : '对照原候选与最终正文' }}</NButton>
      <div v-if="comparisonOpen" class="content-comparison"><article><h4>模型原候选 · {{ task.candidate?.title }}</h4><MarkdownPreview :value="candidateBody" /></article><article><h4>待发布正文 · {{ title }}</h4><MarkdownPreview :value="body" /></article></div>
      <p v-if="contentProblem" class="field-help">{{ contentProblem }}</p>
      <p v-if="taskStale && !decision" class="notice">任务或访问条件已变化，本地改写继续保留。<NButton text :loading="taskLoading" :disabled="blocked || taskLoading" @click="reviewTask">重新核对任务状态</NButton></p>
      <NCheckbox v-model:checked="confirmed" :disabled="!canAdopt">我已对照候选、最终正文{{ mode === 'EXISTING' ? '和目标发布内容及草稿' : '' }}，确认{{ mode === 'NEW' ? '创建新知识并发布首个版本' : '将最终正文发布为目标知识的新版本并同步草稿' }}。本任务仅可采用一次，采用不代表技术内容已核实。</NCheckbox>
      <div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!canAdopt || !confirmed" @click="adopt">{{ mode === 'NEW' ? '确认创建并发布知识' : '确认追加发布版本' }}</NButton><NButton :disabled="blocked" @click="close">关闭改写表单</NButton></div>
    </div>
  </section>
</template>

<style scoped>
.candidate-adoption { display: grid; gap: 18px; padding-top: 21px; border-top: 1px solid var(--line); min-width: 0; }
header, .decision-record, .adoption-form, .target-selection, .body-field { display: grid; gap: 14px; min-width: 0; }
h3 { margin: 0; font-size: 17px; } h4 { margin: 16px 0 12px; font-size: 14px; overflow-wrap: anywhere; }
.decision-record { padding: 15px; background: var(--surface-soft); border-radius: 7px; } .decision-record > span { color: var(--muted); font-size: 12px; }
.adoption-form { gap: 20px; } label { display: grid; gap: 8px; color: var(--muted); font-size: 13px; }
.destination-fields, .target-comparison, .content-comparison { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.target-comparison details, .content-comparison article { min-width: 0; padding-top: 13px; border-top: 1px solid var(--line); }
summary { cursor: pointer; font-size: 13px; overflow-wrap: anywhere; } .comparison-toggle { justify-self: start; }
@media (max-width: 850px) { .destination-fields, .target-comparison, .content-comparison { grid-template-columns: 1fr; } }
</style>
