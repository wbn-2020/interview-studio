<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate } from '../../api/ai'
import type { AiFactDraftAdopt, AiFactDraftContent, AiFactDraftDecision } from '../../api/aiFactDraft'
import { aiFactDraftAdoptValid, aiFactDraftCandidateValid, aiFactDraftContentProblem, aiFactDraftDecisionValid,
  aiFactDraftManifestMatches, aiFactDraftProjectValid, aiFactDraftTaskValid } from '../../api/aiFactDraft'
import type { PreparationItem, PreparationPage, PreparationSummary } from '../../api/preparation'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

type Project = Extract<PreparationItem, { kind: 'PROJECT' }>
type ProjectChoice = Pick<PreparationSummary, 'id' | 'title' | 'versionId' | 'versionNumber' | 'revision'>
const props = defineProps<{ task: AiTask; refreshTask: () => Promise<AiTask | undefined>; disabled?: boolean; peerLocked?: boolean }>()
const emit = defineEmits<{ received: [task: AiTask]; locked: [value: boolean] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const opened = ref(false), initialized = ref(false), confirmed = ref(false), checking = ref(false), listing = ref(false)
const content = ref<AiFactDraftContent>({ title: '', scenario: '', problem: '', action: '', result: '', technology: '', tags: [] })
const initialSignature = ref(''), taskRevision = ref<number>(), taskFingerprint = ref<string>()
const project = shallowRef<Project>(), chosenProject = shallowRef<ProjectChoice>(), choices = shallowRef<ProjectChoice[]>([])
const searchInput = ref(''), search = ref(''), cursor = ref<string>(), cursors = ref<(string | undefined)[]>([]), nextCursor = ref<string | null>(null), listLoaded = ref(false)
const receivedDecision = shallowRef<AiFactDraftDecision>(), problemText = ref(''), listError = ref(''), message = ref('')
const draftState = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, readSequence = 0, listSequence = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value), blocked = computed(() => !readable.value || Boolean(props.peerLocked) || locked.value)
const decision = computed(() => props.task.factDecision ?? receivedDecision.value ?? null)
const candidate = computed(() => props.task.factCandidate)
const sourceChecksValid = computed(() => aiFactDraftManifestMatches(props.task.manifest)
  && Boolean(candidate.value && aiFactDraftCandidateValid(candidate.value, props.task.manifest)))
const manifestFingerprint = computed(() => sourceChecksValid.value ? aiCandidateManifestFingerprint(props.task.manifest) : undefined)
const undecided = computed(() => props.task.task.purpose === 'FACT_DRAFT' && props.task.task.status === 'SUCCEEDED' && Boolean(candidate.value) && !decision.value)
const dirty = computed(() => opened.value && (JSON.stringify(content.value) !== initialSignature.value || Boolean(chosenProject.value) || confirmed.value))
const taskStale = computed(() => taskRevision.value !== props.task.task.revision || !manifestFingerprint.value || taskFingerprint.value !== manifestFingerprint.value)
const problem = computed(() => aiFactDraftContentProblem(content.value))
const canAdopt = computed(() => opened.value && !blocked.value && !checking.value && !listing.value && undecided.value && props.task.sourcesCurrent
  && sourceChecksValid.value && !taskStale.value && Boolean(project.value) && !problem.value)
const fields = [{ key: 'scenario', label: '场景' }, { key: 'problem', label: '问题' }, { key: 'action', label: '动作与责任待核实处' }, { key: 'result', label: '结果与待核实处' }] as const
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)

watch([dirty, locked], ([changed, waiting]) => {
  draftState.dirty = changed; commandState.locked = waiting
  store.changes.set('ai-adoption', draftState); store.changes.set('ai-adoption-command', commandState); emit('locked', waiting)
}, { immediate: true, flush: 'sync' })
watch(content, () => { confirmed.value = false }, { deep: true, flush: 'sync' })
watch([() => props.task.task.revision, () => props.task.sourcesCurrent, manifestFingerprint, decision, project], () => { confirmed.value = false }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.peerLocked], () => {
  generation++; readSequence++; listSequence++; checking.value = false; listing.value = false
  taskRevision.value = undefined; project.value = undefined; choices.value = []; listLoaded.value = false; confirmed.value = false
}, { flush: 'sync' })
watch(writer.error, error => {
  if (!error) return
  confirmed.value = false
  if (!writer.pending.value) { taskRevision.value = undefined; project.value = undefined }
})
onUnmounted(() => {
  alive = false; generation++; readSequence++; listSequence++
  if (store.changes.get('ai-adoption') === draftState) store.changes.delete('ai-adoption')
  if (store.changes.get('ai-adoption-command') === commandState) store.changes.delete('ai-adoption-command')
  emit('locked', false)
})

function open() {
  if (blocked.value || !undecided.value || !sourceChecksValid.value || !candidate.value) return
  // 人工稿只从候选初始化一次；重新核对项目、任务或访问条件均保留原改写。
  if (!initialized.value) { content.value = JSON.parse(JSON.stringify(candidate.value.content)) as AiFactDraftContent; initialSignature.value = JSON.stringify(content.value); initialized.value = true }
  opened.value = true; confirmed.value = false; problemText.value = ''
}
function close() {
  if (blocked.value || !store.canLeave(['ai-adoption', 'ai-adoption-command'])) return
  readSequence++; listSequence++; checking.value = false; listing.value = false; opened.value = false; confirmed.value = false
}
async function loadProjects() {
  if (blocked.value || checking.value || listing.value || !opened.value || !undecided.value) return
  if (search.value.length > 100 || /[\u0000\uD800-\uDFFF]/u.test(search.value)) { listError.value = '查找标题最多 100 个 UTF-16 码元，须为完整文字。'; return }
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++listSequence
  const parameters = new URLSearchParams({ kind: 'PROJECT', status: 'ACTIVE', q: search.value, limit: '20' })
  if (cursor.value) parameters.set('cursor', cursor.value)
  listing.value = true; listError.value = ''; choices.value = []; nextCursor.value = null; listLoaded.value = false
  try {
    const value = await read<PreparationPage>(`/prepare/items?${parameters}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || stamp !== generation || sequence !== listSequence) return
    if (!value || !Array.isArray(value.items) || value.items.length > 20 || value.nextCursor !== null && !uuid(value.nextCursor)
      || value.items.some(item => item.kind !== 'PROJECT' || item.status !== 'ACTIVE' || !uuid(item.id) || !uuid(item.versionId)
        || !Number.isSafeInteger(item.revision) || item.revision < 0 || !Number.isSafeInteger(item.versionNumber) || item.versionNumber < 1
        || typeof item.title !== 'string' || item.title.length > 200 || /[\u0000\uD800-\uDFFF]/u.test(item.title)
        || !/[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(item.title))
      || new Set(value.items.map(item => item.id)).size !== value.items.length) throw new ApiError(502, 'AI_FACT_PROJECT_LIST_INVALID', '项目列表内容不完整，请重新读取。')
    choices.value = value.items.map(({ id, title, versionId, versionNumber, revision }) => ({ id, title, versionId, versionNumber, revision }))
    nextCursor.value = value.nextCursor; listLoaded.value = true
  } catch (error) { if (alive && sequence === listSequence && epoch === store.sessionEpoch) listError.value = errorText(error) }
  finally { if (alive && sequence === listSequence) listing.value = false }
}
function searchProjects() { if (!blocked.value && !checking.value && !listing.value) { search.value = searchInput.value.trim(); cursor.value = undefined; cursors.value = []; void loadProjects() } }
function nextProjects() { if (!blocked.value && !checking.value && !listing.value && nextCursor.value) { cursors.value.push(cursor.value); cursor.value = nextCursor.value; void loadProjects() } }
function previousProjects() { if (!blocked.value && !checking.value && !listing.value && cursors.value.length) { cursor.value = cursors.value.pop(); void loadProjects() } }
async function checkProject(listed?: ProjectChoice) {
  const selected = listed ?? chosenProject.value
  if (blocked.value || checking.value || listing.value || !opened.value || !undecided.value || !sourceChecksValid.value || !selected) return
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++readSequence, id = props.task.task.id
  const fingerprint = aiCandidateManifestFingerprint(props.task.manifest)
  checking.value = true; confirmed.value = false; project.value = undefined; taskRevision.value = undefined; problemText.value = ''
  const current = () => alive && !blocked.value && epoch === store.sessionEpoch && stamp === generation && sequence === readSequence
  try {
    const task = await props.refreshTask()
    if (!current()) return
    if (!task || task.task.id !== id || task.task.purpose !== 'FACT_DRAFT' || !aiFactDraftTaskValid(task)
      || aiCandidateManifestFingerprint(task.manifest) !== fingerprint || task.task.revision < props.task.task.revision) throw new ApiError(502, 'AI_FACT_TASK_MISMATCH', '任务与固定原文不一致，请重新核对。')
    taskRevision.value = task.task.revision; taskFingerprint.value = fingerprint
    if (task.factDecision) { message.value = '这项任务已有决定，人工改稿继续保留。'; return }
    const value = await read<PreparationItem>(`/prepare/items/${selected.id}`)
    if (!current()) return
    if (value.kind !== 'PROJECT' || !aiFactDraftProjectValid(value, selected.id, listed?.versionId)
      || listed && (value.revision !== listed.revision || value.currentVersion.number !== listed.versionNumber || value.currentVersion.content.title !== listed.title)) {
      throw new ApiError(409, 'AI_FACT_PROJECT_CHANGED', '所选项目已改版、归档或发生其他修改，请重新查找或读取项目。人工改稿继续保留。')
    }
    project.value = value
    chosenProject.value = { id: value.id, title: value.currentVersion.content.title, versionId: value.currentVersion.id, versionNumber: value.currentVersion.number, revision: value.revision }
    message.value = '已核对任务与项目当前版本，请确认所属项目及改稿。保存后事实和责任均待核实。'
  } catch (error) { if (current()) problemText.value = errorText(error) }
  finally { if (alive && sequence === readSequence) checking.value = false }
}
function sameDecision(left: AiFactDraftDecision, right: AiFactDraftDecision): boolean {
  return left.status === right.status && left.targetProjectId === right.targetProjectId && left.targetItemId === right.targetItemId
    && left.targetVersionId === right.targetVersionId && left.createdAt === right.createdAt
}
function run(action: 'adopt-fact' | 'ignore', payload: AiFactDraftAdopt | { expectedRevision: number }) {
  if (blocked.value || checking.value || listing.value || !candidate.value) return
  const id = props.task.task.id, fingerprint = aiCandidateManifestFingerprint(props.task.manifest), candidateSignature = JSON.stringify(candidate.value)
  const expectedStatus = action === 'adopt-fact' ? 'ADOPTED' : 'IGNORED', expectedProjectId = 'projectId' in payload ? payload.projectId : undefined
  commandEpoch = store.sessionEpoch; commandGeneration = generation; problemText.value = ''; message.value = ''
  let original: typeof writer.pending.value
  void writer.run<AiTask>(`/ai/tasks/${id}/${action}`, 'POST', payload, value => {
    if (!alive) return
    let matches = false
    try {
      const saved = value.factDecision
      matches = value.task.id === id && value.task.purpose === 'FACT_DRAFT' && aiFactDraftTaskValid(value)
        && value.task.revision === payload.expectedRevision + 1 && aiCandidateManifestFingerprint(value.manifest) === fingerprint
        && JSON.stringify(value.factCandidate) === candidateSignature && Boolean(saved && aiFactDraftDecisionValid(saved, expectedProjectId)
          && saved.status === expectedStatus && (!decision.value || sameDecision(saved, decision.value)))
    } catch { matches = false }
    // 原键结果只核对发出时的任务、候选与项目身份；后续源状态变化不撤销已保存的决定。
    if (!matches || !readable.value || props.peerLocked || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) {
      writer.pending.value = original; writer.error.value = new ApiError(0, 'AI_FACT_RECEIPT_UNCERTAIN', '事实采用决定尚未核对，人工改稿和原请求已保留。请用原请求核对结果。'); return
    }
    receivedDecision.value = value.factDecision!
    confirmed.value = false; initialSignature.value = JSON.stringify(content.value); opened.value = false
    message.value = expectedStatus === 'ADOPTED' ? '已保存新的待核实事实，责任边界仍待核实。' : '已忽略这份事实候选，原候选、引用与费用记录继续保留。'
    emit('received', value)
  })
  original = writer.pending.value
}
function adopt() {
  if (!canAdopt.value || !confirmed.value || taskRevision.value === undefined || !project.value) return
  const payload: AiFactDraftAdopt = { expectedRevision: taskRevision.value, projectId: project.value.id, expectedProjectVersionId: project.value.currentVersion.id,
    expectedProjectRevision: project.value.revision, content: JSON.parse(JSON.stringify(content.value)) as AiFactDraftContent, confirmed: true }
  if (aiFactDraftAdoptValid(payload)) run('adopt-fact', payload)
}
function ignore() {
  if (blocked.value || checking.value || listing.value || !undecided.value) return
  if (window.confirm(`忽略后这项任务不能再采用，原候选和费用记录保留。${dirty.value ? '当前人工改稿也将结束编辑。' : ''}确认忽略这份事实候选？`)) run('ignore', { expectedRevision: props.task.task.revision })
}
function retryCommand() {
  if (!readable.value || props.peerLocked || writer.busy.value || !writer.pending.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry()
}
</script>

<template>
  <section v-show="readable" class="fact-adoption" aria-label="审阅并保存待核实事实" :inert="!readable || undefined">
    <header><h3>保存为待核实事实</h3><p class="field-help">审阅文字并选择所属项目后，将创建一条新事实。责任边界与核实状态均为待核实，之后需在事实页面单独核对。</p></header>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="!readable || !!peerLocked || writer.busy.value" :loading="writer.busy.value" @click="retryCommand">用原请求核对结果</NButton></p>
    <p v-if="problemText" class="notice error" role="alert">{{ problemText }}</p>
    <div v-if="decision" class="decision-record"><strong>{{ decision.status === 'ADOPTED' ? '事实已保存，仍待核实' : '这份事实候选已忽略' }}</strong><span>{{ aiDate(decision.createdAt) }}</span><template v-if="decision.status === 'ADOPTED'"><p class="field-help">采用只保存你审阅的内容与来源记录，不证明经历或本人责任，也不记录训练或准备任务完成。</p><RouterLink v-if="decision.targetItemId && decision.targetVersionId && decision.targetProjectId" class="text-link" :to="{ name: 'prepare', query: { kind: 'FACT', item: decision.targetItemId, version: decision.targetVersionId } }">查看采用时保存的事实版本</RouterLink></template><p v-if="opened" class="field-help">任务已有决定，当前人工改稿保留供查看。</p></div>
    <p v-if="undecided && !task.sourcesCurrent" class="notice">原资料或训练来源当前不可用，不能采用这份候选。人工改稿保留，仍可忽略。</p>
    <div class="row-actions"><NButton v-if="undecided && !opened" type="primary" :disabled="blocked || !sourceChecksValid" @click="open">审阅事实并选择项目</NButton><NButton v-if="undecided" :disabled="blocked || checking || listing" @click="ignore">忽略候选</NButton></div>
    <form v-if="opened" class="adoption-form" @submit.prevent="adopt">
      <label>事实标题 · {{ content.title.length }} / 200<NInput v-model:value="content.title" :maxlength="200" :disabled="blocked || !!decision" /></label>
      <label v-for="field in fields" :key="field.key">{{ field.label }} · {{ content[field.key].length }} / 10000<NInput v-model:value="content[field.key]" type="textarea" :maxlength="10000" :autosize="{ minRows: 3, maxRows: 9 }" :disabled="blocked || !!decision" /></label>
      <label>技术与适用版本 · {{ content.technology.length }} / 2000<NInput v-model:value="content.technology" type="textarea" :maxlength="2000" :autosize="{ minRows: 2, maxRows: 5 }" :disabled="blocked || !!decision" /></label>
      <label>标签 · 最多 20 项，每项 1–40 字符<NSelect v-model:value="content.tags" multiple filterable tag :options="content.tags.map(value => ({ value, label: value }))" :disabled="blocked || !!decision" placeholder="输入标签后确认" /></label>
      <p class="field-help">长度按 UTF-16 码元计算。缺少依据的场景、问题、动作、结果与技术可以留空；采用不确认责任与经历。</p>
      <section class="project-picker" aria-label="选择所属项目"><h4>所属项目</h4><p class="field-help">项目仅用于保存事实的归属。请选择未归档项目并核对其当前版本。</p><div class="project-search"><NInput v-model:value="searchInput" :maxlength="100" :disabled="blocked || checking || listing || !!decision" placeholder="按项目标题查找" aria-label="查找所属项目" @keydown.enter.prevent="searchProjects" /><NButton :disabled="blocked || checking || listing || !!decision" :loading="listing" @click="searchProjects">查找项目</NButton></div><p v-if="listError" class="notice error" role="alert">{{ listError }}</p><div class="project-results"><button v-for="item in choices" :key="item.id" type="button" :disabled="blocked || checking || listing || !!decision" @click="checkProject(item)"><strong>{{ item.title }}</strong><small>v{{ item.versionNumber }} · {{ chosenProject?.id === item.id ? '当前选择' : item.id.slice(0, 8) }} · 读取并使用此版本</small></button></div><p v-if="listLoaded && !choices.length" class="field-help">没有匹配的未归档项目。可调整标题，或先在准备资料中创建项目。</p><div v-if="cursors.length || nextCursor" class="row-actions"><NButton size="small" :disabled="blocked || checking || listing || !cursors.length || !!decision" @click="previousProjects">上一页</NButton><NButton size="small" :disabled="blocked || checking || listing || !nextCursor || !!decision" @click="nextProjects">下一页</NButton></div><div v-if="chosenProject" class="selected-project"><strong>{{ chosenProject.title }} · v{{ chosenProject.versionNumber }}</strong><p class="field-help">项目 {{ chosenProject.id }} · 版本 {{ chosenProject.versionId }} · 修订 {{ chosenProject.revision }}</p><NButton :disabled="blocked || checking || listing || !!decision" :loading="checking" @click="checkProject()">重新读取项目与任务，保留改稿</NButton><p v-if="!project" class="notice">这份项目选择需要重新读取，核对后才能采用。</p></div><p v-else class="field-help">尚未选择所属项目。</p></section>
      <p v-if="problem" class="field-help">{{ problem }}</p><p v-if="taskStale && !decision" class="notice">请读取所选项目与任务，核对本次采用条件；人工输入保留。</p>
      <NCheckbox v-model:checked="confirmed" :disabled="!canAdopt">我已审阅上述内容与所属项目，理解引用不证明人工改写后的每句话，确认新事实和责任边界仍待核实。</NCheckbox>
      <div class="row-actions"><NButton type="primary" attr-type="submit" :disabled="!canAdopt || !confirmed" :loading="writer.busy.value">保存为新的待核实事实</NButton><NButton :disabled="blocked" @click="close">收起改稿</NButton></div>
    </form>
  </section>
</template>

<style scoped>
.fact-adoption, header, .adoption-form, .decision-record, .project-picker, .selected-project { display: grid; gap: 15px; min-width: 0; }.fact-adoption { padding-top: 21px; border-top: 1px solid var(--line); gap: 18px; }
h3 { margin: 0; font-size: 17px; }h4 { margin: 0; font-size: 14px; }label { display: grid; gap: 8px; color: var(--muted); font-size: 13px; }.adoption-form { gap: 20px; }
.project-picker { border-top: 1px solid var(--line); padding-top: 18px; }.project-search { display: flex; gap: 10px; }.project-results { display: grid; gap: 5px; max-height: 260px; overflow-y: auto; }.project-results button { display: grid; gap: 5px; min-width: 0; text-align: left; padding: 10px; border-radius: 5px; }.project-results button:hover:not(:disabled) { background: var(--surface-soft); }.project-results small { color: var(--muted); font-size: 12px; }.selected-project, .decision-record { padding: 15px; background: var(--surface-soft); border-radius: 7px; }.selected-project .n-button { justify-self: start; }.selected-project p, .project-results strong { overflow-wrap: anywhere; }.decision-record > span { color: var(--muted); font-size: 12px; }
@media (max-width: 550px) { .project-search { flex-wrap: wrap; } }
</style>
