<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiAdoptExpression, AiAdoptionMode, AiSource, AiTask } from '../../api/ai'
import { aiDate, aiExpressionManifestMatches, aiFactResponsibility, aiSourceRoute } from '../../api/ai'
import type { ExpressionContent, PreparationItem } from '../../api/preparation'
import { expressionOptions, responsibilityLabels } from '../../api/preparation'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import MarkdownPreview from '../MarkdownPreview.vue'
import AiExpressionTargetPicker from './AiExpressionTargetPicker.vue'

const MarkdownEditor = defineAsyncComponent(() => import('../MarkdownEditor.vue'))
type ExpressionItem = Extract<PreparationItem, { kind: 'EXPRESSION' }>
const props = defineProps<{ task: AiTask; disabled?: boolean; peerLocked?: boolean }>()
const emit = defineEmits<{ received: [task: AiTask]; refreshed: [task: AiTask]; locked: [value: boolean] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const opened = ref(false)
const mode = ref<AiAdoptionMode>('NEW')
const content = ref<ExpressionContent>({ title: '', type: 'PROJECT_SHORT', body: '', factVersionIds: [], tags: [] })
const confirmed = ref(false)
const taskRevision = ref<number>()
const taskLoading = ref(false)
const taskError = ref('')
const target = shallowRef<ExpressionItem>()
const targetNeedsReview = ref(false)
const targetLoading = ref(false)
const targetError = ref('')
const pickerOpen = ref(false)
const pickerBusy = ref(false)
const pickerGeneration = ref(0)
const deferred = shallowRef<AiTask>()
const message = ref('')
const draftState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let initialContent = ''
let alive = true
let taskRequest = 0
let targetRequest = 0
let commandEpoch = store.sessionEpoch

const decision = computed(() => props.task.expressionDecision ?? null)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !store.sessionActive || Boolean(props.disabled) || Boolean(props.peerLocked) || locked.value)
const reading = computed(() => taskLoading.value || targetLoading.value || pickerBusy.value)
const undecided = computed(() => props.task.task.purpose === 'EXPRESSION_DRAFT' && props.task.task.status === 'SUCCEEDED'
  && Boolean(props.task.expressionCandidate) && !decision.value)
const sourceChecksValid = computed(() => aiExpressionManifestMatches(props.task.manifest)
  && props.task.expressionCandidate?.type === props.task.manifest.expressionType)
const sources = computed(() => props.task.manifest.sources.filter(source => source.kind === 'FACT'))
const contentSignature = computed(() => JSON.stringify(content.value))
const dirty = computed(() => opened.value && (contentSignature.value !== initialContent || mode.value !== 'NEW' || Boolean(target.value) || confirmed.value))
const taskStale = computed(() => taskRevision.value !== props.task.task.revision)
const targetContent = computed(() => target.value?.currentVersion.content)
const targetProblem = computed(() => {
  if (mode.value === 'NEW') return ''
  if (!target.value) return '请选择要保存新版本的表达。'
  if (targetNeedsReview.value) return '目标状态或访问条件已变化，请重新读取目标并核对。'
  if (target.value.status !== 'ACTIVE') return '目标表达已归档，请选择其他表达或另存为新表达。'
  return ''
})
const contentProblem = computed(() => {
  const value = content.value
  if (!value.title.trim() || value.title.trim().length > 200) return '请填写 1–200 个 UTF-16 码元的标题。'
  if (!value.body.trim() || value.body.trim().length > 12000) return '表达正文不能为空，去除首尾空白后最多 12000 个 UTF-16 码元。'
  if (invalidText(value.title) || invalidText(value.body)) return '标题或正文包含 NUL 或不完整字符，请修正后再保存。'
  if (value.type !== props.task.manifest.expressionType) return '表达类型与本次授权不一致，请重新核对任务。'
  const allowed = new Set(sources.value.map(source => source.versionId))
  if (!value.factVersionIds.length || value.factVersionIds.length > 5 || new Set(value.factVersionIds).size !== value.factVersionIds.length
    || value.factVersionIds.some(id => !allowed.has(id))) return '请选择 1–5 个本次生成使用的事实版本，不能添加任务之外的依据。'
  const tags = value.tags.map(tag => tag.trim())
  if (tags.length > 20 || new Set(tags).size !== tags.length || tags.some(tag => !tag || tag.length > 40 || invalidText(tag))) return '最多 20 个不重复标签，每项为 1–40 字符且不含无效字符。'
  return ''
})
const canAdopt = computed(() => opened.value && !blocked.value && !reading.value && undecided.value
  && props.task.sourcesCurrent && sourceChecksValid.value && !taskStale.value && !targetProblem.value && !contentProblem.value)
const modeOptions = [{ label: '另存为新表达', value: 'NEW' }, { label: '保存为已有表达的新版本', value: 'EXISTING' }]
const expressionLabel = (type: ExpressionContent['type']) => expressionOptions.find(option => option.value === type)?.label ?? '类型待核对'
const responsibilityLabel = (source: AiSource) => responsibilityLabels[aiFactResponsibility(source) ?? 'UNVERIFIED']

watch([dirty, locked], ([changed, waiting]) => {
  draftState.dirty = changed
  commandState.locked = waiting
  // 不同用途的采用表单互斥，共用工作区的离开保护与未知命令恢复入口。
  store.changes.set('ai-adoption', draftState)
  store.changes.set('ai-adoption-command', commandState)
  emit('locked', waiting)
}, { immediate: true, flush: 'sync' })
watch([contentSignature, mode, target, taskRevision, () => props.task.task.revision, () => props.task.sourcesCurrent, sourceChecksValid, decision], () => { confirmed.value = false }, { flush: 'sync' })
watch(mode, () => { targetRequest++; targetLoading.value = false; pickerOpen.value = false; pickerBusy.value = false }, { flush: 'sync' })
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
  if (['REVISION_CONFLICT', 'AI_STATE_CONFLICT', 'AI_CANDIDATE_DECIDED', 'AI_SOURCE_CHANGED', 'SOURCE_CHANGED', 'FACT_NOT_CONFIRMED', 'SOURCE_UNAVAILABLE'].includes(error.code)) taskRevision.value = undefined
  // 同一个修订冲突码可能来自任务或准备对象，两处基线都需明确重读，人工改写不回填。
  if (['REVISION_CONFLICT', 'VERSION_CONFLICT', 'ITEM_ARCHIVED', 'PREPARATION_NOT_FOUND'].includes(error.code)) targetNeedsReview.value = Boolean(target.value)
})
onUnmounted(() => {
  alive = false
  cancelReads()
  if (store.changes.get('ai-adoption') === draftState) store.changes.delete('ai-adoption')
  if (store.changes.get('ai-adoption-command') === commandState) store.changes.delete('ai-adoption-command')
  emit('locked', false)
})

function invalidText(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index)
    if (code === 0) return true
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(++index)
      if (!(next >= 0xdc00 && next <= 0xdfff)) return true
    } else if (code >= 0xdc00 && code <= 0xdfff) return true
  }
  return false
}
function cancelReads() {
  taskRequest++
  targetRequest++
  taskLoading.value = false
  targetLoading.value = false
  pickerGeneration.value++
  pickerOpen.value = false
  pickerBusy.value = false
}
function open() {
  if (blocked.value || !undecided.value || opened.value || !props.task.expressionCandidate) return
  const candidate = props.task.expressionCandidate
  content.value = { title: candidate.title, type: candidate.type, body: candidate.body, tags: [],
    factVersionIds: sources.value.filter(source => candidate.citations.some(citation => citation.itemId === source.itemId && citation.versionId === source.versionId)).map(source => source.versionId) }
  initialContent = JSON.stringify(content.value)
  mode.value = 'NEW'
  target.value = undefined
  targetNeedsReview.value = false
  taskRevision.value = props.task.task.revision
  confirmed.value = false
  taskError.value = ''
  targetError.value = ''
  message.value = ''
  opened.value = true
}
function close() {
  if (blocked.value || !store.canLeave(['ai-adoption', 'ai-adoption-command'])) return
  cancelReads()
  opened.value = false
  confirmed.value = false
}
function choose(value: PreparationItem) {
  if (blocked.value || !opened.value || !undecided.value || !pickerOpen.value || mode.value !== 'EXISTING'
    || value.kind !== 'EXPRESSION' || value.status !== 'ACTIVE') return
  target.value = value
  targetNeedsReview.value = false
  targetError.value = ''
  pickerOpen.value = false
  pickerBusy.value = false
  message.value = '已选择目标表达，请对照当前版本。最终标题、正文、标签和事实依据继续保留。'
}
async function reloadTarget() {
  if (blocked.value || reading.value || !opened.value || !undecided.value || mode.value !== 'EXISTING' || !target.value) return
  const itemId = target.value.id
  const epoch = store.sessionEpoch
  const request = ++targetRequest
  targetLoading.value = true
  targetNeedsReview.value = true
  targetError.value = ''
  confirmed.value = false
  try {
    const value = await read<PreparationItem>(`/prepare/items/${itemId}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || request !== targetRequest || !opened.value || mode.value !== 'EXISTING' || target.value?.id !== itemId) return
    if (value.id !== itemId || value.kind !== 'EXPRESSION') { targetError.value = '目标身份或类型不匹配，请重新选择表达。'; return }
    // 准备域以当前内容版本和对象修订号作双基线，没有可替换人工正文的知识草稿。
    target.value = value
    targetNeedsReview.value = false
    message.value = '已读取目标最新版本。本次最终内容保持原样，请对照后重新确认。'
  } catch (error) {
    if (alive && request === targetRequest && epoch === store.sessionEpoch) targetError.value = errorText(error)
  } finally { if (alive && request === targetRequest) targetLoading.value = false }
}
async function reviewTask() {
  if (blocked.value || reading.value) return
  const epoch = store.sessionEpoch
  const request = ++taskRequest
  taskLoading.value = true
  taskError.value = ''
  confirmed.value = false
  try {
    const value = await read<AiTask>(`/ai/tasks/${props.task.task.id}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || request !== taskRequest) return
    if (value.task.id !== props.task.task.id || value.task.purpose !== 'EXPRESSION_DRAFT' || value.task.revision < props.task.task.revision) {
      taskError.value = '任务状态已继续变化，请重新核对。'
      return
    }
    emit('refreshed', value)
    taskRevision.value = value.task.revision
    message.value = '已重新核对任务及来源状态，本地改写继续保留。'
  } catch (error) {
    if (alive && request === taskRequest && epoch === store.sessionEpoch) taskError.value = errorText(error)
  } finally { if (alive && request === taskRequest) taskLoading.value = false }
}
function toggleBasis(versionId: string, checked: boolean) {
  if (blocked.value || decision.value || !sources.value.some(source => source.versionId === versionId)) return
  const selected = new Set(content.value.factVersionIds)
  if (checked) selected.add(versionId)
  else selected.delete(versionId)
  content.value.factVersionIds = sources.value.filter(source => selected.has(source.versionId)).map(source => source.versionId)
}
function receive(value: AiTask) {
  if (value.task.id !== props.task.task.id || value.task.purpose !== 'EXPRESSION_DRAFT' || !value.expressionDecision) {
    taskRevision.value = undefined
    taskError.value = '服务器回执没有对应的表达候选决定，请核对任务状态后继续。'
    return
  }
  opened.value = false
  confirmed.value = false
  cancelReads()
  message.value = value.expressionDecision.status === 'ADOPTED'
    ? '已保存最终表达及确切事实依据，采用记录和原候选继续保留。'
    : '已忽略这份表达候选，原候选和费用记录继续保留。'
  emit('received', value)
  void queryClient.invalidateQueries({ queryKey: ['preparation', store.sessionEpoch] })
  void queryClient.invalidateQueries({ queryKey: ['ai', store.sessionEpoch, 'expression-origins'] })
}
function run(action: 'adopt-expression' | 'ignore', payload: AiAdoptExpression | { expectedRevision: number }) {
  if (blocked.value || reading.value) return
  commandEpoch = store.sessionEpoch
  message.value = ''
  taskError.value = ''
  const taskId = props.task.task.id
  const adoption = action === 'adopt-expression' ? payload as AiAdoptExpression : undefined
  void writer.run<AiTask>(`/ai/tasks/${taskId}/${action}`, 'POST', payload, value => {
    if (!alive) return
    const saved = value?.expressionDecision
    const decisionMatches = saved && (adoption
      ? saved.status === 'ADOPTED' && saved.mode === adoption.mode && Boolean(saved.targetItemId && saved.targetVersionId)
        && (adoption.mode !== 'EXISTING' || saved.targetItemId === adoption.targetItemId)
      : saved.status === 'IGNORED' && saved.mode === null && saved.targetItemId === null && saved.targetVersionId === null)
    // 原请求的采用方式与目标固定；回执不符时保留原键，不关闭人工改写。
    if (value?.task?.id !== taskId || value.task.purpose !== 'EXPRESSION_DRAFT' || !decisionMatches) {
      taskRevision.value = undefined
      throw new ApiError(502, 'AI_TASK_MISMATCH', '表达决定回执与本次请求不一致，表达改写和原请求继续保留，请用原请求重试。')
    }
    if (commandEpoch !== store.sessionEpoch || !store.sessionActive || props.disabled || props.peerLocked) deferred.value = value
    else receive(value)
  })
}
function adopt() {
  if (!canAdopt.value || !confirmed.value || taskRevision.value === undefined) return
  const existing = mode.value === 'EXISTING' ? target.value : undefined
  run('adopt-expression', { expectedRevision: taskRevision.value, mode: mode.value,
    targetItemId: existing?.id ?? null, expectedTargetVersionId: existing?.currentVersion.id ?? null, expectedTargetRevision: existing?.revision ?? null,
    content: { ...content.value, title: content.value.title.trim(), body: content.value.body.trim(), factVersionIds: [...content.value.factVersionIds], tags: content.value.tags.map(tag => tag.trim()) }, confirmed: true })
}
function ignore() {
  if (blocked.value || reading.value || !undecided.value) return
  if (!window.confirm(`忽略后，这项任务不能再采用；原候选和费用记录保留。${dirty.value ? '当前未保存的改写也会放弃。' : ''}确认忽略这份表达候选？`)) return
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
  <section class="expression-adoption" aria-label="审阅与采用表达候选">
    <header><h3>审阅并保存表达</h3><p class="field-help">确认后保存你审阅的完整内容及事实依据。采用不会发起模型请求；AI 停用后仍可保存来源有效的候选。</p></header>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!store.sessionActive || !!disabled || !!peerLocked" @click="retryCommand">用原请求重试</NButton><NButton v-else text :disabled="blocked || reading" @click="reviewTask">核对最新任务状态</NButton></p>
    <p v-if="deferred" class="notice" role="status">表达决定已由服务器确认，返回时登录或资料条件发生了变化。<NButton text :disabled="!store.sessionActive || !!disabled || !!peerLocked" @click="receiveDeferred">接收已确认决定</NButton></p>
    <p v-if="taskError" class="notice error" role="alert">{{ taskError }}</p>
    <div v-if="decision" class="decision-record">
      <strong>{{ decision.status === 'ADOPTED' ? '这份表达候选已采用' : '这份表达候选已忽略' }}</strong><span>{{ aiDate(decision.createdAt) }}</span>
      <p class="field-help">每项任务只能决定一次。原候选、生成引用及费用记录继续保留，表达以采用时确认的最终内容为准。</p>
      <RouterLink v-if="decision.status === 'ADOPTED' && decision.targetItemId && decision.targetVersionId" class="text-link" :to="{ name: 'prepare', query: { kind: 'EXPRESSION', item: decision.targetItemId, version: decision.targetVersionId } }">查看采用时保存的确切表达版本</RouterLink>
      <p v-if="opened" class="notice">这项任务已有决定，本地改写仍保留供查看；关闭前会提示未保存修改。</p>
    </div>
    <p v-if="undecided && !task.sourcesCurrent" class="notice">原事实或所属项目已变化，当前不能采用。即使最终不保留某条依据，也需核对全部生成来源。改写继续保留，你仍可忽略候选，或复制内容到手工编辑。</p>
    <p v-if="undecided && !sourceChecksValid" class="notice">候选类型或事实授权清单不完整，暂不能采用。<NButton text :disabled="blocked || reading" @click="reviewTask">重新读取任务</NButton></p>
    <div class="row-actions"><NButton v-if="undecided && !opened" type="primary" :disabled="blocked" @click="open">审阅并改写表达</NButton><NButton v-if="undecided" :disabled="blocked || reading" @click="ignore">忽略表达候选</NButton></div>

    <div v-if="opened" class="adoption-form">
      <p class="field-help">改写只保存在当前页面，确认采用才保存。上方保留模型原候选；原引用不作为人工改写后每一句话的证明。</p>
      <label :for="`expression-mode-${task.task.id}`">保存方式<NSelect :id="`expression-mode-${task.task.id}`" v-model:value="mode" :options="modeOptions" :disabled="blocked || !!decision" /></label>
      <p v-if="mode === 'NEW'" class="field-help">创建一份新表达及首个版本。</p>
      <section v-else class="target-selection">
        <div class="row-actions"><NButton :disabled="blocked || !!decision || targetLoading || taskLoading" :aria-expanded="pickerOpen" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起选择器' : target ? '更换目标表达' : '选择目标表达' }}</NButton><NButton v-if="target" :loading="targetLoading" :disabled="blocked || !!decision || reading" @click="reloadTarget">重新读取目标并保留改写</NButton></div>
        <AiExpressionTargetPicker v-if="pickerOpen" :key="pickerGeneration" :selected-id="target?.id" :disabled="blocked || !!decision || targetLoading || taskLoading" @choose="choose" @busy="pickerBusy = $event" />
        <p v-if="targetLoading" class="field-help" role="status">正在读取目标当前版本，最终内容继续保留。</p>
        <p v-if="targetError" class="notice error" role="alert">{{ targetError }}</p><p v-if="targetProblem" class="notice">{{ targetProblem }}</p>
        <template v-if="target && targetContent">
          <p class="field-help">目标「{{ targetContent.title }}」· v{{ target.currentVersion.number }} · 修订 {{ target.revision }}。本次保存完整新版本，不自动拼接旧正文；历史版本保留。</p>
          <p v-if="targetContent.type !== content.type" class="notice">目标当前用途为“{{ expressionLabel(targetContent.type) }}”，新版本将使用本次已授权的“{{ expressionLabel(content.type) }}”。</p>
          <details class="target-comparison" open><summary>对照目标当前版本</summary><h4>{{ targetContent.title }}</h4><MarkdownPreview :value="targetContent.body" /><p class="field-help">标签：{{ targetContent.tags.length ? targetContent.tags.join(' · ') : '无' }}</p><p class="field-help">原事实依据：{{ target.references.map(reference => `${reference.title} · v${reference.number}`).join('、') || '无' }}。新版本使用下方最终勾选的依据。</p></details>
        </template>
      </section>
      <p class="field-help">表达用途：{{ expressionLabel(content.type) }}，与本次授权保持一致。</p>
      <label :for="`expression-title-${task.task.id}`">最终标题<NInput :id="`expression-title-${task.task.id}`" v-model:value="content.title" :disabled="blocked || !!decision" /></label>
      <div class="body-field"><label>最终正文 <span class="field-help">Markdown · 去除首尾空白后 {{ content.body.trim().length }} / 12000 UTF-16 码元</span></label><MarkdownEditor v-model="content.body" :disabled="blocked || !!decision" /></div>
      <label :for="`expression-tags-${task.task.id}`">标签（最多 20 个）<NSelect :id="`expression-tags-${task.task.id}`" v-model:value="content.tags" multiple filterable tag :options="content.tags.map(value => ({ value, label: value }))" :disabled="blocked || !!decision" placeholder="输入标签后确认" /></label>
      <fieldset class="fact-basis"><legend>最终表达的事实依据 · 已选 {{ content.factVersionIds.length }} 项</legend><p class="field-help">下面列出本次生成使用的全部事实，默认勾选候选引用过的版本。可调整本次最终采用的依据，不能增加未参与生成的事实。</p><div v-for="source in sources" :key="`${source.itemId}/${source.versionId}`" class="basis-row"><NCheckbox :checked="content.factVersionIds.includes(source.versionId)" :disabled="blocked || !!decision" @update:checked="toggleBasis(source.versionId, $event)">{{ source.title }} · v{{ source.number }} · {{ responsibilityLabel(source) }}</NCheckbox><RouterLink v-if="aiSourceRoute(source)" class="text-link" :to="aiSourceRoute(source)!">查看确切事实版本</RouterLink></div></fieldset>
      <p v-if="contentProblem" class="field-help">{{ contentProblem }}</p>
      <p v-if="taskStale && !decision" class="notice">任务或访问条件已变化，人工改写继续保留。<NButton text :loading="taskLoading" :disabled="blocked || reading" @click="reviewTask">重新核对任务与来源</NButton></p>
      <NCheckbox v-model:checked="confirmed" :disabled="!canAdopt">我已核对最终表达、本人职责、事实依据和保存目标，确认{{ mode === 'NEW' ? '创建新表达' : '保存为目标表达的完整新版本' }}，并理解原引用不证明改写后每一句话。</NCheckbox>
      <div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!canAdopt || !confirmed" @click="adopt">{{ mode === 'NEW' ? '确认创建表达' : '确认保存新版本' }}</NButton><NButton :disabled="blocked" @click="close">关闭改写表单</NButton></div>
    </div>
  </section>
</template>

<style scoped>
.expression-adoption { display: grid; gap: 18px; padding-top: 21px; border-top: 1px solid var(--line); min-width: 0; }
header, .decision-record, .adoption-form, .target-selection, .body-field { display: grid; gap: 14px; min-width: 0; }
h3 { margin: 0; font-size: 17px; } h4 { margin: 12px 0; font-size: 15px; }
.decision-record { padding: 15px; background: var(--surface-soft); border-radius: 7px; }
.decision-record > span { color: var(--muted); font-size: 12px; }
.adoption-form { gap: 20px; }
.adoption-form > label, .body-field > label { display: grid; gap: 8px; font-size: 13px; color: var(--muted); }
.target-comparison { min-width: 0; padding: 15px; border: 1px solid var(--line); border-radius: 7px; }
summary { cursor: pointer; color: var(--muted); font-size: 13px; }
.fact-basis { display: grid; gap: 13px; min-width: 0; margin: 0; padding: 15px; border: 1px solid var(--line); border-radius: 7px; }
legend { padding: 0 6px; font-size: 13px; } .basis-row { display: grid; gap: 6px; min-width: 0; overflow-wrap: anywhere; }
.basis-row .text-link { justify-self: start; font-size: 12px; }
@media (max-width: 550px) { .target-comparison, .fact-basis { padding: 12px; } }
</style>
