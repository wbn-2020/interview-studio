<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput } from 'naive-ui'
import { ApiError, errorText } from '../../api/client'
import type { AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate, aiSourceRoute } from '../../api/ai'
import type { AiCardDraftAdopt, AiCardDraftDecision } from '../../api/aiCardDraft'
import { aiCardDraftCandidateValid, aiCardDraftContentProblem, aiCardDraftDecisionValid, aiCardDraftManifestMatches, aiCardDraftTaskValid } from '../../api/aiCardDraft'
import type { MemoryContent } from '../../api/memory'
import { memoryContentSignature, memoryEmptyContent } from '../../api/memory'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MarkdownPreview from '../MarkdownPreview.vue'

const props = defineProps<{ task: AiTask; refreshTask: () => Promise<AiTask | undefined>; disabled?: boolean; peerLocked?: boolean }>()
const emit = defineEmits<{ received: [task: AiTask]; locked: [value: boolean] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const opened = ref(false), initialized = ref(false), content = ref<MemoryContent>(memoryEmptyContent()), initialSignature = ref('')
const confirmed = ref(false), taskRevision = ref<number>(), taskFingerprint = ref<string>(), taskLoading = ref(false)
const taskError = ref(''), message = ref(''), comparisonOpen = ref(false), receivedDecision = shallowRef<AiCardDraftDecision>()
const draftState = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, readSequence = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value)
const blocked = computed(() => !readable.value || Boolean(props.peerLocked) || locked.value)
const decision = computed(() => props.task.cardDecision ?? receivedDecision.value ?? null)
const sourceChecksValid = computed(() => aiCardDraftManifestMatches(props.task.manifest)
  && Boolean(props.task.cardCandidate && aiCardDraftCandidateValid(props.task.cardCandidate, props.task.manifest)))
const manifestFingerprint = computed(() => sourceChecksValid.value ? aiCandidateManifestFingerprint(props.task.manifest) : undefined)
const undecided = computed(() => props.task.task.purpose === 'MEMORY_CARD_DRAFT' && props.task.task.status === 'SUCCEEDED' && Boolean(props.task.cardCandidate) && !decision.value)
const dirty = computed(() => opened.value && (memoryContentSignature(content.value) !== initialSignature.value || confirmed.value))
const taskStale = computed(() => taskRevision.value !== props.task.task.revision || !manifestFingerprint.value || taskFingerprint.value !== manifestFingerprint.value)
const problem = computed(() => aiCardDraftContentProblem(content.value, props.task.manifest))
const canAdopt = computed(() => opened.value && !blocked.value && !taskLoading.value && undecided.value && props.task.sourcesCurrent
  && sourceChecksValid.value && !taskStale.value && !problem.value)
const count = (value: string) => Array.from(value).length
const kindLabel = computed(() => props.task.manifest.cardDraftType === 'CLOZE' ? '填空卡' : '问答卡')

watch([dirty, locked], ([changed, waiting]) => {
  draftState.dirty = changed; commandState.locked = waiting
  store.changes.set('ai-adoption', draftState); store.changes.set('ai-adoption-command', commandState)
  emit('locked', waiting)
}, { immediate: true, flush: 'sync' })
watch(content, () => { confirmed.value = false }, { deep: true, flush: 'sync' })
watch([() => props.task.task.revision, () => props.task.sourcesCurrent, manifestFingerprint, decision], () => { confirmed.value = false }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.peerLocked], () => {
  generation++; readSequence++; taskLoading.value = false; taskRevision.value = undefined; confirmed.value = false
}, { flush: 'sync' })
watch(writer.error, error => {
  if (!error) return
  confirmed.value = false
  if (!writer.pending.value) taskRevision.value = undefined
})
onUnmounted(() => {
  alive = false; generation++; readSequence++
  if (store.changes.get('ai-adoption') === draftState) store.changes.delete('ai-adoption')
  if (store.changes.get('ai-adoption-command') === commandState) store.changes.delete('ai-adoption-command')
  emit('locked', false)
})

function open() {
  if (blocked.value || !undecided.value || !sourceChecksValid.value) return
  const candidate = props.task.cardCandidate!
  // 模型候选仅首次填入表单；后续刷新与重新展开只核对基线，不覆盖人工改稿。
  if (!initialized.value) {
    content.value = { kind: candidate.kind, title: candidate.title, prompt: candidate.prompt, reference: candidate.reference,
      sources: props.task.manifest.sources.map(source => ({ itemId: source.itemId, versionId: source.versionId })), relatedConceptIds: [] }
    initialSignature.value = memoryContentSignature(content.value); initialized.value = true
  }
  taskRevision.value = props.task.task.revision; taskFingerprint.value = manifestFingerprint.value
  opened.value = true; confirmed.value = false; taskError.value = ''
}
function close() {
  if (blocked.value || !store.canLeave(['ai-adoption', 'ai-adoption-command'])) return
  readSequence++; taskLoading.value = false; opened.value = false; confirmed.value = false
}
async function reviewTask() {
  if (blocked.value || taskLoading.value) return
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++readSequence
  const id = props.task.task.id, fingerprint = aiCandidateManifestFingerprint(props.task.manifest)
  taskLoading.value = true; taskError.value = ''; confirmed.value = false
  try {
    const value = await props.refreshTask()
    if (!alive || blocked.value || epoch !== store.sessionEpoch || stamp !== generation || sequence !== readSequence) return
    if (!value || value.task.id !== id || value.task.purpose !== 'MEMORY_CARD_DRAFT' || !aiCardDraftTaskValid(value)
      || aiCandidateManifestFingerprint(value.manifest) !== fingerprint || value.task.revision < props.task.task.revision) {
      taskRevision.value = undefined; taskError.value = '任务或固定来源不一致，人工改稿保留，请重新核对。'; return
    }
    taskRevision.value = value.task.revision; taskFingerprint.value = fingerprint
    message.value = '已核对任务及来源状态，人工改稿保持原样，请重新确认。'
  } catch (error) { if (alive && sequence === readSequence && epoch === store.sessionEpoch) taskError.value = errorText(error) }
  finally { if (alive && sequence === readSequence) taskLoading.value = false }
}
function sameDecision(left: AiCardDraftDecision, right: AiCardDraftDecision): boolean {
  return left.status === right.status && left.targetCardId === right.targetCardId && left.targetVersionId === right.targetVersionId && left.createdAt === right.createdAt
}
function run(action: 'adopt-card' | 'ignore', payload: AiCardDraftAdopt | { expectedRevision: number }) {
  if (blocked.value || taskLoading.value) return
  const id = props.task.task.id, fingerprint = aiCandidateManifestFingerprint(props.task.manifest)
  const candidate = JSON.stringify(props.task.cardCandidate), expectedStatus = action === 'adopt-card' ? 'ADOPTED' : 'IGNORED'
  commandEpoch = store.sessionEpoch; commandGeneration = generation; taskError.value = ''; message.value = ''
  let original: typeof writer.pending.value
  void writer.run<AiTask>(`/ai/tasks/${id}/${action}`, 'POST', payload, value => {
    if (!alive) return
    let matches = false
    try {
      matches = value.task.id === id && value.task.purpose === 'MEMORY_CARD_DRAFT' && aiCardDraftTaskValid(value)
        && value.task.revision === payload.expectedRevision + 1 && aiCandidateManifestFingerprint(value.manifest) === fingerprint
        && JSON.stringify(value.cardCandidate) === candidate && Boolean(value.cardDecision && aiCardDraftDecisionValid(value.cardDecision)
          && value.cardDecision.status === expectedStatus && (!decision.value || sameDecision(value.cardDecision, decision.value)))
    } catch { matches = false }
    // 不一致或跨权限窗口的成功响应仍属未决请求；保留原正文与 key，禁止另发一次创建。
    if (!matches || !readable.value || props.peerLocked || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) {
      writer.pending.value = original
      writer.error.value = new ApiError(0, 'AI_CARD_RECEIPT_UNCERTAIN', '回忆卡决定回执尚未核对，人工改稿和原请求已保留。请恢复资料访问后用原请求核对。')
      return
    }
    receivedDecision.value = value.cardDecision!
    confirmed.value = false; initialSignature.value = memoryContentSignature(content.value); opened.value = false
    message.value = expectedStatus === 'ADOPTED' ? '已保存新卡及首个版本。排程尚未启用，可到卡片管理中继续核对。' : '已忽略回忆卡候选，原候选、引用与费用记录继续保留。'
    emit('received', value)
  })
  original = writer.pending.value
}
function adopt() {
  if (!canAdopt.value || !confirmed.value || taskRevision.value === undefined) return
  run('adopt-card', { expectedRevision: taskRevision.value, content: { ...content.value,
    sources: content.value.sources.map(source => ({ ...source })), relatedConceptIds: [...content.value.relatedConceptIds] }, confirmed: true })
}
function ignore() {
  if (blocked.value || taskLoading.value || !undecided.value) return
  if (!window.confirm(`忽略后这项任务不能再采用，原候选和费用记录保留。${dirty.value ? '当前人工改稿也将结束编辑。' : ''}确认忽略这张候选卡？`)) return
  run('ignore', { expectedRevision: props.task.task.revision })
}
function retryCommand() {
  if (!readable.value || props.peerLocked || writer.busy.value || !writer.pending.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry()
}
</script>

<template>
  <section v-show="readable" class="card-adoption" aria-label="审阅与采用回忆卡候选" :inert="!readable || undefined">
    <header><h3>审阅并保存一张回忆卡</h3><p class="field-help">确认后创建使用中的新卡及首个版本，排程保持未启用。采用不调用模型，AI 停用后仍可保存来源有效的候选。</p></header>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="!readable || !!peerLocked || writer.busy.value" :loading="writer.busy.value" @click="retryCommand">用原请求核对结果</NButton><NButton v-else text :disabled="blocked || taskLoading" @click="reviewTask">核对最新任务状态</NButton></p>
    <p v-if="taskError" class="notice error" role="alert">{{ taskError }}</p>
    <div v-if="decision" class="decision-record"><strong>{{ decision.status === 'ADOPTED' ? '这张候选卡已采用' : '这张候选卡已忽略' }}</strong><span>{{ aiDate(decision.createdAt) }}</span><p class="field-help">原候选与生成引用保留。保存卡片不计入复习次数，也不代表已经掌握。</p><RouterLink v-if="decision.status === 'ADOPTED' && decision.targetCardId && decision.targetVersionId" class="text-link" :to="{ name: 'memory-cards', query: { item: decision.targetCardId, versionId: decision.targetVersionId } }">查看采用时保存的卡片版本</RouterLink><p v-if="opened" class="field-help">任务已有决定，当前人工改稿仍保留供查看。</p></div>
    <p v-if="undecided && !task.sourcesCurrent" class="notice">生成来源已有改版、归档或不可用，当前不能采用。人工改稿继续保留；可核对确切来源或忽略候选。</p>
    <p v-if="undecided && !sourceChecksValid" class="notice">卡片类型、固定来源或候选引用不完整，当前不能采用。<NButton text :disabled="blocked || taskLoading" @click="reviewTask">重新核对任务</NButton></p>
    <div class="row-actions"><NButton v-if="undecided && !opened" type="primary" :disabled="blocked || !sourceChecksValid" @click="open">审阅并改写卡片</NButton><NButton v-if="undecided" :disabled="blocked || taskLoading" @click="ignore">忽略候选卡</NButton></div>
    <form v-if="opened" class="adoption-form" @submit.prevent="adopt">
      <p class="field-help">类型固定为{{ kindLabel }}。下列内容仅保存在当前页面，确认采用才创建卡片；相关概念可在保存后到卡片管理中补充。</p>
      <label :for="`card-title-${task.task.id}`">最终标题 · {{ count(content.title) }} / 200 字符<NInput :id="`card-title-${task.task.id}`" v-model:value="content.title" :disabled="blocked || !!decision" /></label>
      <label :for="`card-prompt-${task.task.id}`">最终题面 · {{ count(content.prompt) }} / 10000 字符<NInput :id="`card-prompt-${task.task.id}`" v-model:value="content.prompt" type="textarea" :autosize="{ minRows: 5, maxRows: 20 }" :disabled="blocked || !!decision" /></label>
      <p v-if="content.kind === 'CLOZE'" class="field-help">请自行核对填空位置及答案线索。卡片按完整题面与参考保存。</p>
      <label :for="`card-reference-${task.task.id}`">最终个人参考 · {{ count(content.reference) }} / 20000 字符<NInput :id="`card-reference-${task.task.id}`" v-model:value="content.reference" type="textarea" :autosize="{ minRows: 6, maxRows: 24 }" :disabled="blocked || !!decision" /></label>
      <section class="fixed-sources"><h4>本次卡片的完整固定来源</h4><p class="field-help">按授权顺序保存全部知识版本。人工改稿不会增加、删减或替换来源；引用只说明模型原候选的出处。</p><ol><li v-for="source in task.manifest.sources" :key="`${source.itemId}/${source.versionId}`"><RouterLink v-if="aiSourceRoute(source)" class="text-link" :to="aiSourceRoute(source)!">{{ source.title }} · v{{ source.number }}</RouterLink><span v-else>{{ source.title }} · v{{ source.number }}</span></li></ol></section>
      <NButton class="preview-toggle" size="small" :aria-expanded="comparisonOpen" @click="comparisonOpen = !comparisonOpen">{{ comparisonOpen ? '收起最终内容预览' : '预览最终题面与参考' }}</NButton>
      <div v-if="comparisonOpen" class="final-preview"><article><h4>最终题面</h4><MarkdownPreview :value="content.prompt" /></article><article><h4>最终个人参考</h4><MarkdownPreview :value="content.reference" /></article></div>
      <p v-if="problem" class="field-help">{{ problem }}</p>
      <p v-if="taskStale && !decision" class="notice">任务或资料访问条件已变化，人工改稿保留。<NButton text :disabled="blocked || taskLoading" :loading="taskLoading" @click="reviewTask">重新核对任务与来源</NButton></p>
      <NCheckbox v-model:checked="confirmed" :disabled="!canAdopt">我已审阅最终题面、个人参考与全部来源，确认创建一张新卡。原引用不证明人工改稿逐句正确，保存后需另行启用排程。</NCheckbox>
      <div class="row-actions"><NButton type="primary" attr-type="submit" :disabled="!canAdopt || !confirmed" :loading="writer.busy.value">确认创建回忆卡</NButton><NButton :disabled="blocked" @click="close">收起改稿</NButton></div>
    </form>
  </section>
</template>

<style scoped>
.card-adoption, header, .adoption-form, .decision-record, .fixed-sources { display: grid; gap: 15px; min-width: 0; }
.card-adoption { padding-top: 21px; border-top: 1px solid var(--line); gap: 18px; } h3 { margin: 0; font-size: 17px; } h4 { margin: 0; font-size: 14px; }
.adoption-form { gap: 20px; }.adoption-form > label { display: grid; gap: 8px; color: var(--muted); font-size: 13px; }
.decision-record { padding: 15px; background: var(--surface-soft); border-radius: 7px; }.decision-record > span { color: var(--muted); font-size: 12px; }
.fixed-sources { border-top: 1px solid var(--line); padding-top: 16px; }.fixed-sources ol { display: grid; gap: 9px; margin: 0; padding-left: 22px; font-size: 13px; overflow-wrap: anywhere; }
.preview-toggle { justify-self: start; }.final-preview { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }.final-preview article { display: grid; align-content: start; gap: 12px; min-width: 0; border-top: 1px solid var(--line); padding-top: 14px; }
@media (max-width: 700px) { .final-preview { grid-template-columns: 1fr; } }
</style>
