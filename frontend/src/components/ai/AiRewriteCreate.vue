<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { AiPreview, AiSettings, AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate, aiPendingStatuses, aiTaskStatusLabel, formatAiAmount } from '../../api/ai'
import type { AiCreateRewrite, AiRewritePage, AiRewritePreviewRequest } from '../../api/aiRewrite'
import { aiRewriteManifestMatches, aiRewritePageValid, aiRewritePaths, aiRewriteTaskMatches } from '../../api/aiRewrite'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

type AnswerIdentity = { sessionId: string; questionId: string; answerId: string }
const props = defineProps<{ settings?: AiSettings; disabled?: boolean; initialSource?: AnswerIdentity; routeError?: string }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const prepared = shallowRef<AiPreview>()
const preparedIdentity = ref('')
const previewing = ref(false), previewError = ref(''), consent = ref(false)
const authorization = ref<AiAuthorizationReference>()
const deferred = shallowRef<AiTask>(), createdResult = shallowRef<AiTask>()
const message = ref('')
const history = shallowRef<AiRewritePage>()
const historyLoading = ref(false), historyError = ref('')
const cursor = ref<string>(), cursors = ref<(string | undefined)[]>([])
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || Boolean(createdResult.value))
const configured = computed(() => Boolean(props.settings?.configuration.enabled && props.settings.configuration.configured))
const sourceIdentity = computed(() => JSON.stringify([props.initialSource ?? null, props.routeError ?? '']))
const sourceValid = computed(() => !props.routeError && Boolean(props.initialSource
  && [props.initialSource.sessionId, props.initialSource.questionId, props.initialSource.answerId].every(idValid)))
const previewMatches = computed(() => Boolean(prepared.value && sourceValid.value && props.initialSource
  && preparedIdentity.value === sourceIdentity.value && matches(prepared.value.manifest, props.initialSource)))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const pendingTask = computed(() => history.value?.items.some(item => aiPendingStatuses.includes(item.task.status)) ?? false)
const dirty = computed(() => !createdResult.value && consent.value)
const changeState = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
let alive = true, accessGeneration = 0, previewSequence = 0, historySequence = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0

watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed; commandState.locked = waiting
  store.changes.set('ai-create', changeState); store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch([() => store.sessionEpoch, readable, sourceIdentity], () => {
  accessGeneration++; historySequence++; historyLoading.value = false
  history.value = undefined; cursor.value = undefined; cursors.value = []
  invalidatePreview()
  if (readable.value && sourceValid.value) void loadHistory()
}, { immediate: true, flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['AI_CONFIG_CHANGED', 'AI_MANIFEST_CHANGED', 'AI_SOURCE_CHANGED', 'AI_DISABLED', 'AI_NOT_CONFIGURED', 'AI_BUDGET_EXCEEDED', 'AI_REWRITE_LIMIT', 'AI_EVALUATION_ACCESS_REQUIRED'].includes(error.code)) {
    invalidatePreview(); emit('refresh')
    if (!writer.pending.value) void loadHistory()
  }
})
onUnmounted(() => {
  alive = false; previewSequence++; historySequence++
  if (store.changes.get('ai-create') === changeState) store.changes.delete('ai-create')
  if (store.changes.get('ai-command') === commandState) store.changes.delete('ai-command')
})

function idValid(value: unknown): value is string { return typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value) }
function invalidatePreview() { previewSequence++; previewing.value = false; prepared.value = undefined; preparedIdentity.value = ''; consent.value = false }
function matches(manifest: AiPreview['manifest'], source: AnswerIdentity): boolean {
  return aiRewriteManifestMatches(manifest) && manifest.evaluation?.sessionId === source.sessionId
    && manifest.evaluation.questionId === source.questionId && manifest.evaluation.answerId === source.answerId
}
async function loadHistory() {
  if (!readable.value || !sourceValid.value || !props.initialSource || writer.busy.value) return
  const selected = { ...props.initialSource }, epoch = store.sessionEpoch, generation = accessGeneration
  const identity = sourceIdentity.value, currentCursor = cursor.value, sequence = ++historySequence
  historyLoading.value = true; historyError.value = ''
  try {
    const value = await read<AiRewritePage>(aiRewritePaths.tasks(selected.sessionId, selected.questionId, selected.answerId, currentCursor))
    if (!alive || !readable.value || sequence !== historySequence || epoch !== store.sessionEpoch
      || generation !== accessGeneration || identity !== sourceIdentity.value || currentCursor !== cursor.value) return
    if (!aiRewritePageValid(value, selected.sessionId, selected.questionId, selected.answerId, currentCursor)) throw new ApiError(502, 'AI_REWRITE_HISTORY_MISMATCH', '任务记录与这次确切提交不一致，请重新读取。')
    history.value = value
  } catch (error) { if (alive && sequence === historySequence && epoch === store.sessionEpoch) historyError.value = errorText(error) }
  finally { if (alive && sequence === historySequence) historyLoading.value = false }
}
function previousHistory() { if (!blocked.value && !historyLoading.value && cursors.value.length) { cursor.value = cursors.value.pop(); history.value = undefined; void loadHistory() } }
function nextHistory() { if (!blocked.value && !historyLoading.value && history.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = history.value.nextCursor; history.value = undefined; void loadHistory() } }
async function prepare() {
  if (blocked.value || previewing.value || !configured.value || !sourceValid.value || !props.initialSource || pendingTask.value) return
  invalidatePreview()
  const snapshot: AiRewritePreviewRequest = { ...props.initialSource }
  const epoch = store.sessionEpoch, generation = accessGeneration, identity = sourceIdentity.value, sequence = ++previewSequence
  previewing.value = true; previewError.value = ''; message.value = ''
  try {
    const value = await preview<AiPreview>(aiRewritePaths.preview, snapshot)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== accessGeneration
      || sequence !== previewSequence || identity !== sourceIdentity.value) return
    if (!value || !matches(value.manifest, snapshot) || !/^[0-9a-f]{64}$/.test(value.manifestHash)
      || !value.budget || value.budget.currency !== value.manifest.configuration.currency
      || !/^\d{4}-\d{2}-\d{2}$/.test(value.budget.date)
      || [value.budget.limitMicros, value.budget.reportedMicros, value.budget.reservedMicros, value.budget.unknownMicros, value.budget.remainingMicros]
        .some(amount => !Number.isSafeInteger(amount) || amount < 0)) throw new ApiError(502, 'AI_REWRITE_PREVIEW_MISMATCH', '预览的确切提交、配置或预算无法核对，请重新读取。')
    if (JSON.stringify(value.manifest.configuration) !== JSON.stringify(props.settings?.configuration)) {
      previewError.value = '服务器配置与页面配置已变化，请刷新后重新预览。'; emit('refresh'); return
    }
    prepared.value = value; preparedIdentity.value = identity
  } catch (error) { if (alive && sequence === previewSequence && epoch === store.sessionEpoch) previewError.value = errorText(error) }
  finally { if (alive && sequence === previewSequence) previewing.value = false }
}
function receive(value: AiTask) {
  createdResult.value = value; consent.value = false; changeState.dirty = false; commandState.locked = false
  message.value = '改写任务已创建，原答与个人参考继续保留。可在任务中查看并排对照、每次尝试与费用。'
  emit('refresh'); emit('created', value)
}
function create() {
  if (blocked.value || previewing.value || !configured.value || !sourceValid.value || !props.initialSource
    || !prepared.value || !previewMatches.value || !consent.value || overBudget.value || pendingTask.value) return
  const snapshot = { ...props.initialSource }, authorizedFingerprint = aiCandidateManifestFingerprint(prepared.value.manifest)
  const body: AiCreateRewrite = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration
  let submitted: typeof writer.pending.value
  void writer.run<AiTask>(aiRewritePaths.create, 'POST', body, value => {
    if (!alive) return
    let valid = false
    try { valid = aiRewriteTaskMatches(value, snapshot.sessionId, snapshot.questionId, snapshot.answerId)
      && matches(value.manifest, snapshot) && aiCandidateManifestFingerprint(value.manifest) === authorizedFingerprint } catch { valid = false }
    // 无法核对的回执也可能已经创建任务，保留原请求与原键，避免再次付费创建。
    if (!valid) { writer.pending.value = submitted; writer.error.value = new ApiError(502, 'AI_TASK_MISMATCH', '改写回执与本次授权不一致，请用原请求恢复。'); emit('refresh'); return }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) deferred.value = value
    else receive(value)
  })
  submitted = writer.pending.value
}
function retryCommand() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="rewrite-create" aria-label="确切提交的改写参考">
    <header class="create-heading"><div><p class="section-label">新建 AI 任务</p><h2>生成改写参考</h2><p class="field-help">以这次已提交回答为依据，生成可与原答、个人参考并排阅读的 AI 未审候选。需要补充的内容单独列为待核实问题。</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!readable" class="notice" role="status">登录或资料访问待确认，未决请求继续保留。</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retryCommand">用原请求重试</NButton></p>
    <p v-if="deferred" class="notice" role="status">服务器已确认创建，访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建任务</NButton></p>
    <p v-if="createdResult" class="notice"><NButton text :disabled="!readable" @click="emit('created', createdResult)">打开已创建的改写任务</NButton></p>
    <p v-if="!sourceValid" class="notice error" role="alert">{{ routeError || '改写入口需要完整、有效的训练、题目和已提交回答标识。' }}<RouterLink class="text-link" :to="{ name: 'training' }">从训练提交记录重新进入</RouterLink></p>
    <template v-else-if="readable && initialSource">
      <section class="source-panel"><h3>本次确切提交</h3><p class="field-help">回答 {{ initialSource.answerId }}<template v-if="previewMatches && prepared?.manifest.evaluation"> · 第 {{ prepared.manifest.evaluation.answerNumber }} 版</template></p><RouterLink class="text-link" :to="{ name: 'training', query: { item: initialSource.sessionId, question: initialSource.questionId, answer: initialSource.answerId } }">返回这次提交与评价区</RouterLink><p class="field-help">请先在原训练确认打开评价区；该访问记录也覆盖区内的个人参考与改写。尚未确认时，本页不会自动确认或调用模型。原答条件按提交时保留。</p></section>
      <template v-if="!createdResult">
        <p v-if="!configured" class="notice">AI 未启用或配置尚未完整。已有改写记录仍可读取，也可以继续手工复盘。</p>
        <p class="field-help">本次外发完整固定题目、这版回答及图中文字、原作答条件和个人未审参考。预览会逐字列出；改写保存为独立参考，不写回原答，也不记为新一次训练。</p>
        <p v-if="pendingTask" class="notice">这次提交已有未决改写任务，请先查看下方记录。服务器还会检查每次提交最多 3 个改写任务的限制。</p>
        <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || pendingTask" @click="prepare">预览完整外发清单</NButton></div>
        <p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
        <section v-if="prepared && previewMatches" class="consent-panel"><AiManifest :manifest="prepared.manifest" :budget="prepared.budget" /><p v-if="overBudget" class="notice error">本次预留超过当前剩余预算，请核对预算后重新预览。</p><AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || !configured || pendingTask">我已核对原答、个人参考、供应商与费用，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，生成并展示 AI 改写参考，为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice><div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !consent || overBudget || !configured || pendingTask" @click="create">授权本次外发并生成改写参考</NButton><NButton :disabled="blocked" @click="invalidatePreview">关闭预览</NButton></div><p class="field-help">配置或访问条件变化后需重新预览。结果未知时使用原请求和原键恢复；任务成功不代表改写技术正确或已经读过全文。</p></section>
      </template>
      <section class="rewrite-history" aria-label="这次提交的改写任务"><header class="panel-heading"><h3>这次提交的改写记录</h3><NButton text size="small" :disabled="blocked || historyLoading" @click="loadHistory">刷新记录</NButton></header><p v-if="historyLoading" class="field-help" role="status">正在读取改写任务…</p><p v-if="historyError" class="notice error" role="alert">{{ historyError }}</p><p v-if="history && !history.items.length" class="field-help">本页还没有改写任务。可先预览完整输入，再明确授权生成。</p><ul><li v-for="item in history?.items" :key="item.task.id"><div><strong>第 {{ item.answerNumber }} 版原答的改写</strong><span>{{ aiTaskStatusLabel(item.task.status, item.task.purpose) }} · {{ aiDate(item.task.createdAt) }}</span></div><RouterLink v-if="!locked" class="text-link" :to="{ name: 'ai', query: { item: item.task.id } }">打开改写与任务记录</RouterLink><NButton v-else text size="small" disabled>打开改写与任务记录</NButton></li></ul><div v-if="cursors.length || history?.nextCursor" class="row-actions"><NButton size="small" :disabled="blocked || historyLoading || !cursors.length" @click="previousHistory">上一页</NButton><NButton size="small" :disabled="blocked || historyLoading || !history?.nextCursor" @click="nextHistory">下一页</NButton></div></section>
    </template>
  </section>
</template>

<style scoped>
.rewrite-create { display: grid; align-content: start; gap: 22px; min-width: 0; padding: 25px 27px 30px; }
.create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; flex-wrap: wrap; }.create-heading > div { min-width: 0; flex: 1; }
h2 { margin: 7px 0 9px; font-size: 24px; }h3 { margin: 0; font-size: 16px; }.field-help { overflow-wrap: anywhere; }
.source-panel, .consent-panel, .rewrite-history { display: grid; gap: 14px; min-width: 0; padding-top: 18px; border-top: 1px solid var(--line); }.consent-panel { gap: 20px; }
ul { list-style: none; padding: 0; margin: 0; }li { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--line); }li > div { display: grid; gap: 6px; min-width: 0; }li strong { font-size: 13px; overflow-wrap: anywhere; }li span { color: var(--muted); font-size: 12px; }
@media (max-width: 700px) { .rewrite-create { padding: 22px 17px; }h2 { font-size: 21px; } }
</style>
