<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { ItemDetail, ItemSummary } from '../../api/client'
import type { AiPreview, AiSettings, AiSource, AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiPreparationPlanTextValid, aiSourceKindLabel, aiSourceRoute, formatAiAmount } from '../../api/ai'
import type { AiCardDraftCreateRequest, AiCardDraftPreviewRequest } from '../../api/aiCardDraft'
import { aiCardDraftKindLabels, aiCardDraftPaths, aiCardDraftSourceValid, aiCardDraftSourcesMatch, aiCardDraftTaskValid } from '../../api/aiCardDraft'
import type { MemoryKind } from '../../api/memory'
import { memoryIdValid } from '../../api/memory'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import KnowledgePicker from '../KnowledgePicker.vue'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

const props = defineProps<{ settings?: AiSettings; disabled?: boolean; initialKind?: MemoryKind; initialSource?: { itemId: string; versionId: string } }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const kind = ref<MemoryKind | null>(props.initialKind ?? null)
const instruction = ref('')
const sources = ref<AiSource[]>([])
const selectedIds = computed(() => sources.value.map(source => source.itemId))
const picker = ref<'CONCEPT' | 'NOTE' | null>(null)
const pickerGeneration = ref(0)
const selecting = ref(false)
const selectionError = ref('')
const prefillPending = ref(Boolean(props.initialSource))
const prefilling = ref(false)
const prefillError = ref('')
const previewing = ref(false)
const previewError = ref('')
const prepared = shallowRef<AiPreview>()
const preparedRequest = ref('')
const consent = ref(false)
const authorization = ref<AiAuthorizationReference>()
const deferred = shallowRef<AiTask>()
const createdResult = shallowRef<AiTask>()
const message = ref('')
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.restricted && !access.challenge)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || Boolean(createdResult.value))
const configured = computed(() => Boolean(props.settings?.configuration.enabled && props.settings.configuration.configured))
const sourceIdentity = computed(() => JSON.stringify(props.initialSource ?? null))
const sourcesSignature = computed(() => JSON.stringify(sources.value))
const request = computed<AiCardDraftPreviewRequest | undefined>(() => kind.value ? { kind: kind.value, instruction: instruction.value,
  sources: sources.value.map(source => ({ itemId: source.itemId, versionId: source.versionId })) } : undefined)
const requestSignature = computed(() => JSON.stringify(request.value))
const dirty = computed(() => !createdResult.value && Boolean(kind.value || instruction.value || sources.value.length))
const previewMatches = computed(() => Boolean(prepared.value && request.value && preparedRequest.value === requestSignature.value
  && aiCardDraftSourcesMatch(prepared.value.manifest, request.value, sources.value)))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const problem = computed(() => {
  if (!kind.value || !Object.hasOwn(aiCardDraftKindLabels, kind.value)) return '请明确选择本次生成问答卡或手工填空卡。'
  if (prefillPending.value) return '请核对入口所指的确切发布版本，或明确取消这项预填来源。'
  if (sources.value.length < 1 || sources.value.length > 5 || sources.value.some(source => !aiCardDraftSourceValid(source))
    || new Set(selectedIds.value).size !== sources.value.length) return '请选择 1–5 个当前发布的笔记或概念。'
  if (!aiPreparationPlanTextValid(instruction.value, 1000)) return '整理要求最多 1000 个 UTF-16 码元，不能含 NUL 或不完整字符。'
  return ''
})
const kindOptions = Object.entries(aiCardDraftKindLabels).map(([value, label]) => ({ value, label }))
const changeState = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
let alive = true, selectionSequence = 0, prefillSequence = 0, previewSequence = 0, accessGeneration = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0

watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed; commandState.locked = waiting
  store.changes.set('ai-create', changeState); store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([requestSignature, instruction, kind, sourcesSignature], invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch(picker, () => { selectionSequence++; selecting.value = false; selectionError.value = '' }, { flush: 'sync' })
watch([() => store.sessionEpoch, readable, sourceIdentity], () => {
  accessGeneration++; selectionSequence++; prefillSequence++
  selecting.value = false; prefilling.value = false; pickerGeneration.value++; picker.value = null
  invalidatePreview()
  // 权限变化只撤销授权与在途读取，已填写要求和已选发布正文继续留在表单内。
  if (readable.value && prefillPending.value && !locked.value) void loadInitialSource()
}, { immediate: true, flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['AI_CONFIG_CHANGED', 'AI_MANIFEST_CHANGED', 'AI_SOURCE_CHANGED', 'AI_SOURCE_UNAVAILABLE', 'AI_DISABLED', 'AI_NOT_CONFIGURED', 'AI_BUDGET_EXCEEDED'].includes(error.code)) {
    invalidatePreview(); emit('refresh')
  }
})
onUnmounted(() => {
  alive = false; selectionSequence++; prefillSequence++; previewSequence++
  if (store.changes.get('ai-create') === changeState) store.changes.delete('ai-create')
  if (store.changes.get('ai-command') === commandState) store.changes.delete('ai-command')
})

function invalidatePreview() { previewSequence++; previewing.value = false; prepared.value = undefined; preparedRequest.value = ''; consent.value = false }
function publishedSource(value: ItemDetail, itemId: string, versionId?: string, summary?: ItemSummary): AiSource {
  const published = value?.published
  if (!value || value.id !== itemId || value.status !== 'ACTIVE' || !['NOTE', 'CONCEPT'].includes(value.kind) || !published
    || published.id !== value.currentVersionId || published.number !== value.versionNumber || versionId !== undefined && published.id !== versionId
    || summary && (value.kind !== summary.kind || published.number !== summary.versionNumber)) {
    throw new ApiError(409, 'AI_SOURCE_CHANGED', '知识已归档、发布版本已变化或当前版本不可用。请回到知识页核对；不会替换为另一个版本。')
  }
  // 只从 published 白名单构造来源，ItemDetail 中的草稿、主题、关系不会随请求外发。
  const source: AiSource = { itemId: value.id, versionId: published.id, number: published.number, kind: value.kind, title: published.title, body: published.body }
  if (!aiCardDraftSourceValid(source)) throw new ApiError(502, 'AI_CARD_SOURCE_INVALID', '该发布版本的身份或完整正文无效，请重新核对。')
  return source
}
async function loadInitialSource() {
  if (blocked.value || selecting.value || prefilling.value || !prefillPending.value || !props.initialSource) return
  const initial = { ...props.initialSource }, epoch = store.sessionEpoch, generation = accessGeneration, sequence = ++prefillSequence
  prefillError.value = ''
  if (!memoryIdValid(initial.itemId) || !memoryIdValid(initial.versionId)) { prefillError.value = '入口需要同一条知识的确切发布版本，请回到知识页重新进入，或取消这项预填。'; return }
  prefilling.value = true; invalidatePreview()
  try {
    const value = await read<ItemDetail>(`/knowledge/items/${initial.itemId}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== accessGeneration || sequence !== prefillSequence || !prefillPending.value) return
    const source = publishedSource(value, initial.itemId, initial.versionId)
    if (sources.value.length >= 5 || selectedIds.value.includes(source.itemId)) { prefillError.value = '这项预填已在选择中或本次已选满，请核对后取消这项预填。'; return }
    sources.value.push(source); prefillPending.value = false
  } catch (error) { if (alive && sequence === prefillSequence && epoch === store.sessionEpoch) prefillError.value = errorText(error) }
  finally { if (alive && sequence === prefillSequence) prefilling.value = false }
}
function cancelInitialSource() {
  if (blocked.value || previewing.value) return
  prefillSequence++; prefilling.value = false; prefillPending.value = false; prefillError.value = ''; invalidatePreview()
}
async function choose(summary: ItemSummary) {
  if (blocked.value || previewing.value || selecting.value || prefilling.value || !picker.value || summary.kind !== picker.value
    || summary.status !== 'ACTIVE' || !memoryIdValid(summary.id) || sources.value.length >= 5 || selectedIds.value.includes(summary.id)) return
  const epoch = store.sessionEpoch, generation = accessGeneration, sequence = ++selectionSequence, selectedKind = picker.value
  selecting.value = true; selectionError.value = ''
  try {
    const value = await read<ItemDetail>(`/knowledge/items/${summary.id}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== accessGeneration || sequence !== selectionSequence || picker.value !== selectedKind) return
    const source = publishedSource(value, summary.id, undefined, summary)
    if (sources.value.length >= 5 || selectedIds.value.includes(source.itemId)) return
    sources.value.push(source); message.value = ''
  } catch (error) { if (alive && sequence === selectionSequence && epoch === store.sessionEpoch) selectionError.value = errorText(error) }
  finally { if (alive && sequence === selectionSequence) selecting.value = false }
}
function remove(itemId: string) { if (!blocked.value && !previewing.value && !selecting.value && !prefilling.value) sources.value = sources.value.filter(source => source.itemId !== itemId) }
function refreshPicker() {
  if (blocked.value || previewing.value || selecting.value || prefilling.value || !picker.value) return
  void queryClient.invalidateQueries({ queryKey: ['knowledge', store.sessionEpoch, 'picker', picker.value] })
  pickerGeneration.value++; selectionError.value = ''
}
async function prepare() {
  if (blocked.value || !configured.value || problem.value || selecting.value || prefilling.value || previewing.value || !request.value) return
  invalidatePreview()
  const epoch = store.sessionEpoch, generation = accessGeneration, sequence = ++previewSequence, signature = requestSignature.value!
  const snapshot = JSON.parse(signature) as AiCardDraftPreviewRequest
  const selected = sources.value.map(source => ({ ...source }))
  previewing.value = true; previewError.value = ''; message.value = ''
  try {
    const value = await preview<AiPreview>(aiCardDraftPaths.preview, snapshot)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== accessGeneration || sequence !== previewSequence || signature !== requestSignature.value) return
    if (!aiCardDraftSourcesMatch(value.manifest, snapshot, selected)) { previewError.value = '预览的卡片类型、知识版本、完整正文或整理要求与本次选择不一致，请重新核对。'; return }
    if (JSON.stringify(value.manifest.configuration) !== JSON.stringify(props.settings?.configuration)) { previewError.value = '服务器配置与页面配置已不一致，请刷新配置后重新预览。'; emit('refresh'); return }
    prepared.value = value; preparedRequest.value = signature
  } catch (error) { if (alive && sequence === previewSequence && epoch === store.sessionEpoch) previewError.value = errorText(error) }
  finally { if (alive && sequence === previewSequence) previewing.value = false }
}
function receive(value: AiTask) {
  createdResult.value = value; changeState.dirty = false; commandState.locked = false
  message.value = '回忆卡候选任务已创建，可在任务详情核对内容后采用。'; emit('refresh'); emit('created', value)
}
function create() {
  if (blocked.value || !configured.value || !prepared.value || !previewMatches.value || !consent.value || overBudget.value || selecting.value || prefilling.value || problem.value) return
  picker.value = null; commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration
  const snapshot = JSON.parse(preparedRequest.value) as AiCardDraftPreviewRequest
  const selected = sources.value.map(source => ({ ...source }))
  const authorizedFingerprint = aiCandidateManifestFingerprint(prepared.value.manifest)
  const body: AiCardDraftCreateRequest = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  let submitted: typeof writer.pending.value
  void writer.run<AiTask>(aiCardDraftPaths.create, 'POST', body, value => {
    if (!alive) return
    let matches = false
    try { matches = value.task.purpose === 'MEMORY_CARD_DRAFT' && aiCardDraftTaskValid(value)
      && aiCardDraftSourcesMatch(value.manifest, snapshot, selected) && aiCandidateManifestFingerprint(value.manifest) === authorizedFingerprint } catch { matches = false }
    // 不一致回执可能对应已提交任务；保留原 body/key，不能放开表单换键再建。
    if (!matches) { writer.pending.value = submitted; writer.error.value = new ApiError(502, 'AI_TASK_MISMATCH', '任务回执与授权的卡片类型、知识正文、配置或费用不一致，请用原请求恢复；输入继续保留。'); emit('refresh'); return }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) deferred.value = value
    else receive(value)
  })
  submitted = writer.pending.value
}
function retryCommand() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="card-draft-create" aria-label="从知识生成回忆卡候选">
    <header class="create-heading"><div><p class="section-label">新建 AI 任务</p><h2>从所选知识生成回忆卡候选</h2><p class="field-help">先选择卡片类型和确切发布知识，核对完整正文与费用，再授权本次生成。候选由你审阅后采用。</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!configured" class="notice">AI 未启用或配置尚未完整，仍可选择知识与填写要求。也可到<RouterLink class="text-link" :to="{ name: 'memory-cards', query: { create: '1' } }">回忆卡手工创建</RouterLink>。</p>
    <p v-if="!readable" class="notice" role="status">登录或资料访问待确认，本地选择与要求继续保留。</p><p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="createdResult" class="notice"><NButton text :disabled="!readable" @click="emit('created', createdResult)">打开已创建的回忆卡任务</NButton></p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retryCommand">用原请求重试</NButton></p>
    <p v-if="deferred" class="notice" role="status">服务器已确认创建，访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建任务</NButton></p>
    <template v-if="!createdResult && readable">
      <label class="form-field">本次卡片类型<NSelect v-model:value="kind" :options="kindOptions" :disabled="blocked || previewing" placeholder="请选择问答卡或手工填空卡" /><span class="field-help">每次只生成一种类型。{{ kind === 'CLOZE' ? '填空使用题面中的手写空位，需人工检查题面与参考；现有卡片不会自动解析或遮蔽文字。' : '问答卡将题面与个人参考分别保存，采用前需核对回答依据。' }}</span></label>
      <section v-if="prefillPending" class="prefill-panel"><h3>核对入口知识</h3><p class="field-help">知识 {{ initialSource?.itemId }} · 发布版本 {{ initialSource?.versionId }}</p><p v-if="prefilling" class="field-help" role="status">正在读取这版知识的当前发布状态与完整正文…</p><p v-if="prefillError" class="notice error" role="alert">{{ prefillError }}</p><div class="row-actions"><NButton size="small" :disabled="blocked || prefilling || selecting || previewing" @click="loadInitialSource">重新核对入口版本</NButton><NButton text size="small" :disabled="blocked || previewing" @click="cancelInitialSource">取消这项预填，手动选择</NButton></div></section>
      <section class="source-selection"><header class="panel-heading"><h3>本次知识 <span>{{ sources.length }} / 5</span></h3><div class="row-actions"><NButton size="small" :disabled="blocked || previewing || prefilling || (sources.length >= 5 && picker !== 'CONCEPT')" :aria-expanded="picker === 'CONCEPT'" @click="picker = picker === 'CONCEPT' ? null : 'CONCEPT'">{{ picker === 'CONCEPT' ? '收起概念选择' : '添加概念' }}</NButton><NButton size="small" :disabled="blocked || previewing || prefilling || (sources.length >= 5 && picker !== 'NOTE')" :aria-expanded="picker === 'NOTE'" @click="picker = picker === 'NOTE' ? null : 'NOTE'">{{ picker === 'NOTE' ? '收起笔记选择' : '添加笔记' }}</NButton></div></header><p class="field-help">选择 1–5 个使用中的当前发布知识。完整标题与正文都会外发，草稿、未选择的关联内容和其他卡片不会自动加入。</p>
        <ol v-if="sources.length" class="selected-sources"><li v-for="source in sources" :key="source.itemId"><header class="panel-heading"><div><RouterLink class="text-link" :to="aiSourceRoute(source)!">{{ source.title }} · v{{ source.number }}</RouterLink><p class="field-help">{{ aiSourceKindLabel(source.kind) }} · {{ source.body.length }} 个 UTF-16 码元 · {{ source.versionId }}</p></div><NButton size="small" quaternary :disabled="blocked || previewing || selecting || prefilling" @click="remove(source.itemId)">移出</NButton></header><details><summary>核对所选发布版本的完整正文</summary><pre class="exact-text">{{ source.body }}</pre></details></li></ol><p v-else class="empty-choice">添加一篇笔记或一个概念作为本次卡片的依据。</p>
        <template v-if="picker"><KnowledgePicker :key="`${pickerGeneration}:${picker}`" :kind="picker" :disabled="blocked || previewing || selecting || prefilling || sources.length >= 5" :exclude-ids="selectedIds" @choose="choose" /><NButton class="refresh-picker" text size="small" :disabled="blocked || previewing || selecting || prefilling" @click="refreshPicker">刷新当前选择范围</NButton></template><p v-if="selecting" class="field-help" role="status">正在核对所选知识的确切发布版本…</p><p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p>
      </section>
      <label class="form-field">整理要求<NInput v-model:value="instruction" :disabled="blocked || previewing" type="textarea" :maxlength="1000" :autosize="{ minRows: 4, maxRows: 10 }" placeholder="例如：围绕适用条件生成一张便于回想的卡片，保留来源中的限制，并给出可核对的引用。" /><span class="field-help">可留空，最多 1000 个 UTF-16 码元；按输入原文发送。正文与要求超出额度时由服务器拒绝，不会截断。</span></label>
      <p class="field-help">生成成功后在 AI 任务详情审阅候选。采用才会创建普通回忆卡；创建候选不启用排程，也不记录复习。</p>
      <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || !!problem || selecting || prefilling" @click="prepare">预览完整外发清单</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div><p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
      <section v-if="prepared && previewMatches" class="consent-panel"><AiManifest :manifest="prepared.manifest" :budget="prepared.budget" /><p v-if="overBudget" class="notice error">本次预留超过当前剩余预算，请核对预算后重新预览。</p><AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || !configured || selecting || prefilling">我已核对卡片类型、完整知识正文和整理要求，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，并为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice><NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !consent || overBudget || !configured || !!problem || selecting || prefilling" @click="create">授权本次外发并生成回忆卡候选</NButton><p class="field-help">更改类型、来源、要求、配置或访问条件会撤销预览与同意。结果未知时保留原请求和原幂等键恢复；页面输入尚未保存为服务器草稿。</p></section>
    </template>
  </section>
</template>

<style scoped>
.card-draft-create { display: grid; gap: 23px; padding: 25px 27px 30px; min-width: 0; }.create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; }.create-heading > div, .panel-heading > div { min-width: 0; }h2 { margin: 7px 0 9px; font-size: 24px; }h3 { margin: 0; font-size: 16px; }h3 span { color: var(--muted); font-size: 12px; font-weight: 400; margin-left: 8px; }
.source-selection, .prefill-panel { display: grid; gap: 13px; }.prefill-panel { padding: 16px; border: 1px solid var(--line); border-radius: 5px; }.panel-heading { flex-wrap: wrap; }.selected-sources { display: grid; gap: 12px; margin: 0; padding: 0; list-style: none; }.selected-sources li { display: grid; gap: 12px; padding: 13px 0; border-bottom: 1px solid var(--line); min-width: 0; }.selected-sources a { font-size: 13px; overflow-wrap: anywhere; }.refresh-picker { justify-self: start; }.empty-choice { padding: 24px 16px; text-align: center; color: var(--muted); font-size: 13px; background: var(--surface-soft); border: 1px dashed var(--line); border-radius: 6px; }.form-field { display: grid; gap: 9px; font-size: 13px; color: var(--muted); }
.consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); min-width: 0; }.consent-panel > .n-button { justify-self: start; }.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }summary { cursor: pointer; color: var(--muted); font-size: 12px; margin-bottom: 9px; }.field-help { overflow-wrap: anywhere; }
@media (max-width: 700px) { .card-draft-create { padding: 22px 17px; }h2 { font-size: 21px; }.selected-sources .panel-heading { align-items: start; } }
</style>
