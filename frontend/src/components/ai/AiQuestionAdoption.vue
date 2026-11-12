<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiAdoptQuestion, AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate, aiDraftPromptVersion, aiMaterialDraftSourcesMatch, aiPreparationChecksMatch } from '../../api/ai'
import type { QuestionInput } from '../../api/bank'
import { cloneQuestion, emptyQuestion, questionProblem } from '../../api/bank'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'

const QuestionForm = defineAsyncComponent(() => import('../bank/QuestionForm.vue'))
const props = defineProps<{ task: AiTask; disabled?: boolean; peerLocked?: boolean }>()
const emit = defineEmits<{ received: [task: AiTask]; refreshed: [task: AiTask]; locked: [value: boolean] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const opened = ref(false)
const content = ref<QuestionInput>(emptyQuestion())
const confirmed = ref(false)
const taskRevision = ref<number>()
const taskFingerprint = ref<string>()
const taskLoading = ref(false)
const taskError = ref('')
const deferred = shallowRef<AiTask>()
const message = ref('')
const draftState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let initialContent = ''
let alive = true
let taskRequest = 0
let commandEpoch = store.sessionEpoch

const decision = computed(() => props.task.questionDecision ?? null)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !store.sessionActive || Boolean(props.disabled) || Boolean(props.peerLocked) || locked.value)
const undecided = computed(() => props.task.task.purpose === 'QUESTION_DRAFT' && props.task.task.status === 'SUCCEEDED'
  && Boolean(props.task.questionCandidate) && !decision.value)
const sourceChecksValid = computed(() => validManifest(props.task.manifest))
const manifestFingerprint = computed(() => sourceChecksValid.value ? aiCandidateManifestFingerprint(props.task.manifest) : undefined)
const contentSignature = computed(() => JSON.stringify(content.value))
const dirty = computed(() => opened.value && (contentSignature.value !== initialContent || confirmed.value))
const taskStale = computed(() => taskRevision.value !== props.task.task.revision || !manifestFingerprint.value
  || taskFingerprint.value !== manifestFingerprint.value)
const contentProblem = computed(() => questionProblem(content.value))
const canAdopt = computed(() => opened.value && !blocked.value && !taskLoading.value && undecided.value
  && props.task.sourcesCurrent && sourceChecksValid.value && !taskStale.value && !contentProblem.value)

watch([dirty, locked], ([changed, waiting]) => {
  draftState.dirty = changed
  commandState.locked = waiting
  // 两种采用表单按用途互斥，共用现有路由离开与刷新保护。
  store.changes.set('ai-adoption', draftState)
  store.changes.set('ai-adoption-command', commandState)
  emit('locked', waiting)
}, { immediate: true, flush: 'sync' })
watch([contentSignature, taskRevision, () => props.task.task.revision, () => props.task.sourcesCurrent, manifestFingerprint, decision], () => { confirmed.value = false }, { flush: 'sync' })
watch([() => store.sessionEpoch, () => store.sessionActive, () => props.disabled], () => {
  cancelRead()
  taskRevision.value = undefined
  confirmed.value = false
}, { flush: 'sync' })
watch(() => props.peerLocked, value => { if (value) { cancelRead(); confirmed.value = false } }, { flush: 'sync' })
watch(writer.error, error => {
  if (!(error instanceof ApiError) || writer.pending.value) return
  confirmed.value = false
  if (['REVISION_CONFLICT', 'AI_STATE_CONFLICT', 'AI_CANDIDATE_DECIDED', 'AI_SOURCE_CHANGED'].includes(error.code)) taskRevision.value = undefined
})
onUnmounted(() => {
  alive = false
  cancelRead()
  if (store.changes.get('ai-adoption') === draftState) store.changes.delete('ai-adoption')
  if (store.changes.get('ai-adoption-command') === commandState) store.changes.delete('ai-adoption-command')
  emit('locked', false)
})

function validManifest(manifest: AiTask['manifest']): boolean {
  const count = manifest.sources.length + (manifest.materialDraftSources?.length ?? 0)
  return manifest.purpose === 'QUESTION_DRAFT' && count >= 1 && count <= 5
    && manifest.promptVersion === aiDraftPromptVersion(manifest)
    && manifest.training == null && manifest.evaluation == null && manifest.contextChecks == null
    && manifest.materialQa == null && manifest.expressionType == null && manifest.preparationPlan == null && manifest.trainingDraft == null
    && aiPreparationChecksMatch(manifest) && aiMaterialDraftSourcesMatch(manifest)
}
function cancelRead() { taskRequest++; taskLoading.value = false }
function open() {
  if (blocked.value || !undecided.value || opened.value || !props.task.questionCandidate) return
  const candidate = props.task.questionCandidate
  // 检查点身份只在打开改写表单时生成；恢复原命令使用同一正文和同一组标识。
  content.value = {
    ...emptyQuestion(), title: candidate.title, prompt: candidate.prompt, conditions: candidate.conditions,
    technology: candidate.technology, scenario: candidate.scenario, type: candidate.type,
    difficulty: candidate.difficulty, dimensions: [...candidate.dimensions], referenceAnswer: candidate.referenceAnswer,
    checkpoints: candidate.checkpoints.map(text => ({ id: crypto.randomUUID(), text })),
  }
  initialContent = JSON.stringify(content.value)
  taskRevision.value = props.task.task.revision
  taskFingerprint.value = manifestFingerprint.value
  confirmed.value = false
  taskError.value = ''
  message.value = ''
  opened.value = true
}
function close() {
  if (blocked.value || !store.canLeave(['ai-adoption', 'ai-adoption-command'])) return
  cancelRead()
  opened.value = false
  confirmed.value = false
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
    if (value.task.id !== props.task.task.id || value.task.purpose !== 'QUESTION_DRAFT' || value.task.revision < props.task.task.revision
      || !validManifest(value.manifest)) {
      taskRevision.value = undefined
      taskError.value = '任务状态或来源清单不一致，请重新核对；本地改写继续保留。'
      return
    }
    emit('refreshed', value)
    taskRevision.value = value.task.revision
    taskFingerprint.value = aiCandidateManifestFingerprint(value.manifest)
    message.value = '已重新核对任务状态，你改写的题目内容继续保留。'
  } catch (error) {
    if (alive && request === taskRequest && epoch === store.sessionEpoch) taskError.value = errorText(error)
  } finally { if (alive && request === taskRequest) taskLoading.value = false }
}
function receive(value: AiTask) {
  if (value.task.id !== props.task.task.id || value.task.purpose !== 'QUESTION_DRAFT' || !value.questionDecision) {
    taskRevision.value = undefined
    taskError.value = '服务器回执没有对应的题目候选决定，请核对任务状态后继续。'
    return
  }
  opened.value = false
  confirmed.value = false
  cancelRead()
  message.value = value.questionDecision.status === 'ADOPTED'
    ? '已按最终内容创建题库首版，采用记录和当时来源已保留。'
    : '已忽略这道候选题，原候选和费用记录继续保留。'
  emit('received', value)
  void queryClient.invalidateQueries({ queryKey: ['bank'] })
  void queryClient.invalidateQueries({ queryKey: ['ai', store.sessionEpoch, 'question-origins'] })
}
function run(action: 'adopt-question' | 'ignore', payload: AiAdoptQuestion | { expectedRevision: number }) {
  if (blocked.value || taskLoading.value) return
  const authorizedFingerprint = action === 'adopt-question' ? manifestFingerprint.value : undefined
  if (action === 'adopt-question' && !authorizedFingerprint) return
  commandEpoch = store.sessionEpoch
  message.value = ''
  taskError.value = ''
  const taskId = props.task.task.id
  void writer.run<AiTask>(`/ai/tasks/${taskId}/${action}`, 'POST', payload, value => {
    if (!alive) return
    const saved = value?.questionDecision
    const decisionMatches = saved && (action === 'adopt-question'
      ? saved.status === 'ADOPTED' && Boolean(saved.targetQuestionId && saved.targetVersionId)
      : saved.status === 'IGNORED' && saved.targetQuestionId === null && saved.targetVersionId === null)
    // 在接收或延后前核对原命令；失败仍保留同一幂等键和题目改写。
    if (value?.task?.id !== taskId || value.task.purpose !== 'QUESTION_DRAFT' || !decisionMatches
      || action === 'adopt-question' && (!validManifest(value.manifest) || aiCandidateManifestFingerprint(value.manifest) !== authorizedFingerprint)) {
      taskRevision.value = undefined
      throw new ApiError(502, 'AI_TASK_MISMATCH', '题目决定回执与本次请求或授权清单不一致，题目改写和原请求继续保留，请用原请求重试。')
    }
    if (commandEpoch !== store.sessionEpoch || !store.sessionActive || props.disabled || props.peerLocked) deferred.value = value
    else receive(value)
  })
}
function adopt() {
  if (!canAdopt.value || !confirmed.value || taskRevision.value === undefined) return
  run('adopt-question', { expectedRevision: taskRevision.value, content: cloneQuestion(content.value), confirmed: true })
}
function ignore() {
  if (blocked.value || taskLoading.value || !undecided.value) return
  if (!window.confirm(`忽略后，这项任务不能再采用；原候选和费用记录保留。${dirty.value ? '当前未保存的改写也会放弃。' : ''}确认忽略这道候选题？`)) return
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
  <section class="question-adoption" aria-label="审阅与采用题目候选">
    <header><h3>审阅并存入题库</h3><p class="field-help">确认后创建一道新题及首个版本，记录你参与了编写。个人参考保持“未审”；采用不发起模型调用。</p></header>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!store.sessionActive || !!disabled || !!peerLocked" @click="retryCommand">用原请求重试</NButton><NButton v-else text :disabled="blocked || taskLoading" @click="reviewTask">核对最新任务状态</NButton></p>
    <p v-if="deferred" class="notice" role="status">题目决定已由服务器确认，返回时登录或资料条件发生了变化。<NButton text :disabled="!store.sessionActive || !!disabled || !!peerLocked" @click="receiveDeferred">接收已确认决定</NButton></p>
    <p v-if="taskError" class="notice error" role="alert">{{ taskError }}</p>
    <div v-if="decision" class="decision-record">
      <strong>{{ decision.status === 'ADOPTED' ? '这道候选题已存入题库' : '这道候选题已忽略' }}</strong><span>{{ aiDate(decision.createdAt) }}</span>
      <p class="field-help">原候选与引用继续保留。题库内容以采用时确认的最终字段为准。</p>
      <RouterLink v-if="decision.status === 'ADOPTED' && decision.targetQuestionId" class="text-link" :to="{ name: 'bank', query: { item: decision.targetQuestionId } }">查看题库题目</RouterLink>
      <p v-if="decision.status === 'ADOPTED'" class="field-help">可在训练入口从题库选择这道题；已有训练会话的题目保持原快照。</p>
      <p v-if="opened" class="notice">这项任务已有决定，你的本地改写仍保留供查看；关闭前会提示未保存修改。</p>
    </div>
    <p v-if="undecided && !task.sourcesCurrent" class="notice">原来源已改版、归档或不可用，不能直接采用这道旧候选题。可查看并忽略，或重新选择来源生成。</p>
    <p v-if="undecided && !sourceChecksValid" class="notice">候选来源清单不完整或与授权不一致，暂不能采用。题目改写继续保留，仍可忽略候选。<NButton text :disabled="blocked || taskLoading" @click="reviewTask">重新核对任务</NButton></p>
    <div class="row-actions"><NButton v-if="undecided && !opened" type="primary" :disabled="blocked" @click="open">审阅并改写题目</NButton><NButton v-if="undecided" :disabled="blocked || taskLoading" @click="ignore">忽略候选题</NButton></div>
    <div v-if="opened" class="adoption-form">
      <header><h4>最终题目内容</h4><p class="field-help">上方保留模型原候选。下方改写只保存在当前页面，确认采用才保存到题库；刷新或离开前请保存或明确放弃。</p><p class="field-help">引用片段是模型的生成依据。需要作为正式题目关联的知识，请在下方显式选择；主题、标签和来源记录均可自行补充。</p></header>
      <QuestionForm v-model="content" :disabled="blocked || !!decision" />
      <p v-if="contentProblem" class="field-help">{{ contentProblem }}</p>
      <p v-if="taskStale && !decision" class="notice">任务或访问条件已变化，本地改写继续保留。<NButton text :loading="taskLoading" :disabled="blocked || taskLoading" @click="reviewTask">重新核对任务状态</NButton></p>
      <NCheckbox v-model:checked="confirmed" :disabled="!canAdopt">我已核对最终题干、个人参考和检查点，确认创建新题及首个版本，并理解采用不代表技术审阅通过。</NCheckbox>
      <div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!canAdopt || !confirmed" @click="adopt">确认存入题库</NButton><NButton :disabled="blocked" @click="close">关闭改写表单</NButton></div>
    </div>
  </section>
</template>

<style scoped>
.question-adoption { display: grid; gap: 18px; padding-top: 21px; border-top: 1px solid var(--line); min-width: 0; }
header, .decision-record, .adoption-form { display: grid; gap: 14px; min-width: 0; }
h3 { margin: 0; font-size: 17px; } h4 { margin: 0; font-size: 15px; }
.decision-record { padding: 15px; background: var(--surface-soft); border-radius: 7px; }
.decision-record > span { color: var(--muted); font-size: 12px; }
.adoption-form { gap: 20px; }
</style>
