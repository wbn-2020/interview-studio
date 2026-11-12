<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate } from '../../api/ai'
import type { AiJdRequirementsAdopt, AiJdRequirementsDecision, AiJdRequirementsSelection } from '../../api/aiJdRequirements'
import { aiJdRequirementsAdoptValid, aiJdRequirementsCandidateValid, aiJdRequirementsDecisionValid, aiJdRequirementsManifestMatches,
  aiJdRequirementsSelectionProblem, aiJdRequirementsTargetMatches, aiJdRequirementsTaskValid } from '../../api/aiJdRequirements'
import type { PreparationItem } from '../../api/preparation'
import { dimensionOptions } from '../../api/preparation'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

type Target = Extract<PreparationItem, { kind: 'TARGET' }>
type Draft = AiJdRequirementsSelection & { selected: boolean }
const props = defineProps<{ task: AiTask; refreshTask: () => Promise<AiTask | undefined>; disabled?: boolean; peerLocked?: boolean }>()
const emit = defineEmits<{ received: [task: AiTask]; locked: [value: boolean] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const opened = ref(false), initialized = ref(false), drafts = ref<Draft[]>([]), initialSignature = ref('')
const confirmed = ref(false), taskRevision = ref<number>(), taskFingerprint = ref<string>(), checking = ref(false)
const target = shallowRef<Target>(), receivedDecision = shallowRef<AiJdRequirementsDecision>(), problemText = ref(''), message = ref('')
const draftState = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, readSequence = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value), blocked = computed(() => !readable.value || Boolean(props.peerLocked) || locked.value)
const decision = computed(() => props.task.jdRequirementsDecision ?? receivedDecision.value ?? null)
const candidate = computed(() => props.task.jdRequirementsCandidate)
const sourceChecksValid = computed(() => aiJdRequirementsManifestMatches(props.task.manifest)
  && Boolean(candidate.value && aiJdRequirementsCandidateValid(candidate.value, props.task.manifest)))
const manifestFingerprint = computed(() => sourceChecksValid.value ? aiCandidateManifestFingerprint(props.task.manifest) : undefined)
const undecided = computed(() => props.task.task.purpose === 'JD_REQUIREMENT_DRAFT' && props.task.task.status === 'SUCCEEDED' && Boolean(candidate.value) && !decision.value)
const selected = computed<AiJdRequirementsSelection[]>(() => drafts.value.filter(row => row.selected).map(({ candidateNumber, text, dimensions, priority }) => ({ candidateNumber, text, dimensions: [...dimensions], priority })))
const dirty = computed(() => opened.value && (JSON.stringify(drafts.value) !== initialSignature.value || confirmed.value))
const taskStale = computed(() => taskRevision.value !== props.task.task.revision || !manifestFingerprint.value || taskFingerprint.value !== manifestFingerprint.value)
const problem = computed(() => candidate.value ? aiJdRequirementsSelectionProblem(selected.value, candidate.value, target.value?.currentVersion.content.requirements.length) : '当前没有可核对的要求候选。')
const canAdopt = computed(() => opened.value && !blocked.value && !checking.value && undecided.value && props.task.sourcesCurrent && sourceChecksValid.value
  && !taskStale.value && Boolean(target.value) && !problem.value)
const priorityOptions = [{ value: 5, label: '5 · 最高' }, { value: 4, label: '4 · 较高' }, { value: 3, label: '3 · 一般' }, { value: 2, label: '2 · 较低' }, { value: 1, label: '1 · 最低' }]
const dimensionLabel = (value: string) => dimensionOptions.find(option => option.value === value)?.label ?? value

watch([dirty, locked], ([changed, waiting]) => {
  draftState.dirty = changed; commandState.locked = waiting
  store.changes.set('ai-adoption', draftState); store.changes.set('ai-adoption-command', commandState); emit('locked', waiting)
}, { immediate: true, flush: 'sync' })
watch(drafts, () => { confirmed.value = false }, { deep: true, flush: 'sync' })
watch([() => props.task.task.revision, () => props.task.sourcesCurrent, manifestFingerprint, decision], () => { confirmed.value = false }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.peerLocked], () => {
  generation++; readSequence++; checking.value = false; taskRevision.value = undefined; target.value = undefined; confirmed.value = false
}, { flush: 'sync' })
watch(writer.error, error => {
  if (!error) return
  confirmed.value = false
  if (!writer.pending.value) { taskRevision.value = undefined; target.value = undefined }
})
onUnmounted(() => {
  alive = false; generation++; readSequence++
  if (store.changes.get('ai-adoption') === draftState) store.changes.delete('ai-adoption')
  if (store.changes.get('ai-adoption-command') === commandState) store.changes.delete('ai-adoption-command')
  emit('locked', false)
})

function open() {
  if (blocked.value || !undecided.value || !sourceChecksValid.value || !candidate.value) return
  // 选择与人工改写只初始化一次；任务刷新或重新展开均不覆盖本地输入。
  if (!initialized.value) {
    drafts.value = candidate.value.requirements.map(row => ({ selected: false, candidateNumber: row.number, text: row.text, dimensions: [...row.dimensions], priority: row.priority }))
    initialSignature.value = JSON.stringify(drafts.value); initialized.value = true
  }
  opened.value = true; confirmed.value = false; problemText.value = ''
}
function close() {
  if (blocked.value || !store.canLeave(['ai-adoption', 'ai-adoption-command'])) return
  readSequence++; checking.value = false; opened.value = false; confirmed.value = false
}
async function checkTarget() {
  if (blocked.value || checking.value || !sourceChecksValid.value) return
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++readSequence, id = props.task.task.id
  const manifest = props.task.manifest, fingerprint = aiCandidateManifestFingerprint(manifest), source = manifest.sources[0]!
  checking.value = true; confirmed.value = false; target.value = undefined; taskRevision.value = undefined; problemText.value = ''
  const current = () => alive && !blocked.value && epoch === store.sessionEpoch && stamp === generation && sequence === readSequence
  try {
    const task = await props.refreshTask()
    if (!current()) return
    if (!task || task.task.id !== id || task.task.purpose !== 'JD_REQUIREMENT_DRAFT' || !aiJdRequirementsTaskValid(task)
      || aiCandidateManifestFingerprint(task.manifest) !== fingerprint || task.task.revision < props.task.task.revision) throw new ApiError(502, 'AI_JD_TASK_MISMATCH', '任务与固定 JD 来源不一致，请重新核对。')
    taskRevision.value = task.task.revision; taskFingerprint.value = fingerprint
    if (task.jdRequirementsDecision) { message.value = '这项任务已有决定，人工改稿继续保留。'; return }
    const value = await read<PreparationItem>(`/prepare/items/${source.itemId}`)
    if (!current()) return
    if (value.kind !== 'TARGET' || !aiJdRequirementsTargetMatches(value, manifest)) throw new ApiError(409, 'AI_SOURCE_CHANGED', '当前目标的版本或 JD 已变化，不能追加这份旧候选。人工改稿继续保留。')
    target.value = value
    message.value = '已读取当前目标及既有要求，采用将追加到此目标的新版本；人工改稿保持原样。'
  } catch (error) { if (current()) problemText.value = errorText(error) }
  finally { if (alive && sequence === readSequence) checking.value = false }
}
function sameDecision(left: AiJdRequirementsDecision, right: AiJdRequirementsDecision): boolean {
  return left.status === right.status && left.targetItemId === right.targetItemId && left.targetVersionId === right.targetVersionId
    && left.createdAt === right.createdAt && JSON.stringify(left.requirements) === JSON.stringify(right.requirements)
}
function run(action: 'adopt-requirements' | 'ignore', payload: AiJdRequirementsAdopt | { expectedRevision: number }) {
  if (blocked.value || checking.value || !candidate.value) return
  const id = props.task.task.id, manifest = props.task.manifest, fingerprint = aiCandidateManifestFingerprint(manifest)
  const fixedCandidate = candidate.value, candidateSignature = JSON.stringify(fixedCandidate), previousIds = new Set(target.value?.currentVersion.content.requirements.map(row => row.id) ?? [])
  const expectedStatus = action === 'adopt-requirements' ? 'ADOPTED' : 'IGNORED'
  commandEpoch = store.sessionEpoch; commandGeneration = generation; problemText.value = ''; message.value = ''
  let original: typeof writer.pending.value
  void writer.run<AiTask>(`/ai/tasks/${id}/${action}`, 'POST', payload, value => {
    if (!alive) return
    let matches = false
    try {
      const saved = value.jdRequirementsDecision
      matches = value.task.id === id && value.task.purpose === 'JD_REQUIREMENT_DRAFT' && aiJdRequirementsTaskValid(value)
        && value.task.revision === payload.expectedRevision + 1 && aiCandidateManifestFingerprint(value.manifest) === fingerprint
        && JSON.stringify(value.jdRequirementsCandidate) === candidateSignature && Boolean(saved && aiJdRequirementsDecisionValid(saved, manifest, fixedCandidate)
          && saved.status === expectedStatus && (!decision.value || sameDecision(saved, decision.value))
          && (action !== 'adopt-requirements' || 'requirements' in payload && saved.requirements.length === payload.requirements.length
            && saved.requirements.every((link, index) => link.candidateNumber === payload.requirements[index]!.candidateNumber && !previousIds.has(link.requirementId))))
    } catch { matches = false }
    // 采用会生成新目标版本，使旧清单 sourcesCurrent=false；回执核对固定身份与决定，不重做采用前的当前性门槛。
    if (!matches || !readable.value || props.peerLocked || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) {
      writer.pending.value = original; writer.error.value = new ApiError(0, 'AI_JD_RECEIPT_UNCERTAIN', '要求采用决定尚未核对，人工改稿和原请求已保留。请用原请求核对结果。'); return
    }
    receivedDecision.value = value.jdRequirementsDecision!
    confirmed.value = false; initialSignature.value = JSON.stringify(drafts.value); opened.value = false
    message.value = expectedStatus === 'ADOPTED' ? '选定要求已追加为目标的新版本，原 JD 与既有要求保留。' : '已忽略这份要求候选，原候选、引用与费用记录继续保留。'
    emit('received', value)
  })
  original = writer.pending.value
}
function adopt() {
  if (!canAdopt.value || !confirmed.value || taskRevision.value === undefined || !target.value || !candidate.value) return
  const payload: AiJdRequirementsAdopt = { expectedRevision: taskRevision.value, expectedTargetRevision: target.value.revision, requirements: selected.value, confirmed: true }
  if (!aiJdRequirementsAdoptValid(payload, candidate.value, target.value.currentVersion.content.requirements.length)) return
  run('adopt-requirements', payload)
}
function ignore() {
  if (blocked.value || checking.value || !undecided.value) return
  if (!window.confirm(`忽略后这项任务不能再采用，原候选和费用记录保留。${dirty.value ? '当前人工改稿也将结束编辑。' : ''}确认忽略这份要求候选？`)) return
  run('ignore', { expectedRevision: props.task.task.revision })
}
function retryCommand() {
  if (!readable.value || props.peerLocked || writer.busy.value || !writer.pending.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry()
}
</script>

<template>
  <section v-show="readable" class="jd-adoption" aria-label="审阅与追加 JD 要求" :inert="!readable || undefined">
    <header><h3>选择并追加要求</h3><p class="field-help">勾选并审阅后，所选要求将追加到同一目标的新版本。采用不调用模型，既有要求与 JD 原文保留。</p></header>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="!readable || !!peerLocked || writer.busy.value" :loading="writer.busy.value" @click="retryCommand">用原请求核对结果</NButton><NButton v-else text :disabled="blocked || checking" @click="checkTarget">核对任务与目标</NButton></p>
    <p v-if="problemText" class="notice error" role="alert">{{ problemText }}</p>
    <div v-if="decision" class="decision-record"><strong>{{ decision.status === 'ADOPTED' ? '选定要求已采用' : '这份要求候选已忽略' }}</strong><span>{{ aiDate(decision.createdAt) }}</span><template v-if="decision.status === 'ADOPTED'"><p class="field-help">以下映射保留候选编号与正式要求身份，技术含义仍由你审阅。</p><ul><li v-for="link in decision.requirements" :key="link.requirementId">候选 {{ link.candidateNumber }} → {{ link.requirementId }}</li></ul><RouterLink v-if="decision.targetItemId && decision.targetVersionId" class="text-link" :to="{ name: 'prepare', query: { kind: 'TARGET', item: decision.targetItemId, version: decision.targetVersionId } }">查看采用时保存的目标版本</RouterLink></template><p v-if="opened" class="field-help">任务已有决定，当前人工改稿保留供查看。</p></div>
    <p v-if="undecided && !task.sourcesCurrent" class="notice">目标版本或来源条件已变化，当前不能采用这份候选。人工改稿保留；可重新核对或忽略候选。</p>
    <p v-if="undecided && !candidate?.requirements.length" class="notice">本次没有可追加的要求。可查看模型说明，或忽略这份零项候选后重新选择 JD 与指令。</p>
    <div class="row-actions"><NButton v-if="undecided && !opened && candidate?.requirements.length" type="primary" :disabled="blocked || !sourceChecksValid" @click="open">勾选并审阅要求</NButton><NButton v-if="undecided" :disabled="blocked || checking" @click="ignore">忽略候选</NButton></div>
    <form v-if="opened" class="adoption-form" @submit.prevent="adopt">
      <p class="field-help">已选 {{ selected.length }} 项，最多 20 项。每项内容、维度与优先级均可修改；没有勾选的候选不会保存。</p>
      <article v-for="row in drafts" :key="row.candidateNumber" class="requirement-editor"><NCheckbox v-model:checked="row.selected" :disabled="blocked || !!decision">追加候选 {{ row.candidateNumber }}</NCheckbox><label :for="`jd-text-${task.task.id}-${row.candidateNumber}`">要求内容 · {{ row.text.length }} / 2000 UTF-16 码元<NInput :id="`jd-text-${task.task.id}-${row.candidateNumber}`" v-model:value="row.text" type="textarea" :maxlength="2000" :autosize="{ minRows: 3, maxRows: 9 }" :disabled="blocked || !!decision" /></label><div class="requirement-controls"><label>考察维度 · 选择 1–6 项<NSelect v-model:value="row.dimensions" multiple :options="dimensionOptions" :disabled="blocked || !!decision" :aria-label="`候选 ${row.candidateNumber} 的考察维度`" /></label><label>优先级<NSelect v-model:value="row.priority" :options="priorityOptions" :disabled="blocked || !!decision" :aria-label="`候选 ${row.candidateNumber} 的优先级`" /></label></div><details><summary>对照模型原候选</summary><template v-for="original in candidate?.requirements.filter(item => item.number === row.candidateNumber) ?? []" :key="original.number"><p class="exact-text">{{ original.text }}</p><p class="field-help">AI 未审维度：{{ original.dimensions.map(dimensionLabel).join('、') }} · AI 未审优先级：{{ original.priority }}</p></template></details></article>
      <section class="target-check"><h4>采用目标与数量核对</h4><NButton :disabled="blocked || checking || !!decision" :loading="checking" @click="checkTarget">显式读取当前目标并保留改稿</NButton><template v-if="target"><p>「{{ target.currentVersion.content.title }}」· v{{ target.currentVersion.number }} · 对象修订 {{ target.revision }}</p><p class="field-help">已有 {{ target.currentVersion.content.requirements.length }} 项要求；本次追加 {{ selected.length }} 项，保存后共 {{ target.currentVersion.content.requirements.length + selected.length }} / 50 项。</p><details v-if="target.currentVersion.content.requirements.length"><summary>查看保留的既有要求</summary><ol><li v-for="item in target.currentVersion.content.requirements" :key="item.id">{{ item.text }}</li></ol></details></template><p v-else class="field-help">采用前请读取当前目标，核对源版本、修订及既有要求数量。</p></section>
      <p v-if="problem" class="field-help">{{ problem }}</p><p v-if="taskStale && !decision" class="notice">任务或访问条件需要重新核对，人工输入保留。请使用上方目标读取入口更新本次基线。</p>
      <NCheckbox v-model:checked="confirmed" :disabled="!canAdopt">我已核对所选要求的内容、维度、优先级及当前目标，确认仅追加这些要求，并理解模型引用不证明人工改写后的每句话。</NCheckbox>
      <div class="row-actions"><NButton type="primary" attr-type="submit" :disabled="!canAdopt || !confirmed" :loading="writer.busy.value">确认追加 {{ selected.length }} 项要求</NButton><NButton :disabled="blocked" @click="close">收起改稿</NButton></div>
    </form>
  </section>
</template>

<style scoped>
.jd-adoption, header, .adoption-form, .decision-record, .target-check, .requirement-editor { display: grid; gap: 15px; min-width: 0; }.jd-adoption { padding-top: 21px; border-top: 1px solid var(--line); gap: 18px; }
h3 { margin: 0; font-size: 17px; }h4 { margin: 0; font-size: 14px; }.adoption-form { gap: 22px; }label { display: grid; gap: 8px; color: var(--muted); font-size: 13px; }
.requirement-editor, .target-check { border-top: 1px solid var(--line); padding-top: 18px; }.requirement-controls { display: grid; grid-template-columns: minmax(0, 2fr) minmax(150px, 1fr); gap: 16px; }.target-check > .n-button { justify-self: start; }
.decision-record { padding: 15px; background: var(--surface-soft); border-radius: 7px; }.decision-record > span, summary { color: var(--muted); font-size: 12px; }summary { cursor: pointer; }
.exact-text { margin: 10px 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.75; }.decision-record ul, .target-check ol { display: grid; gap: 9px; margin: 0; padding-left: 22px; font-size: 13px; overflow-wrap: anywhere; }
@media (max-width: 650px) { .requirement-controls { grid-template-columns: 1fr; } }
</style>
