<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NInput } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { AiPreview, AiSettings, AiSource, AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiPreparationPlanTextValid, formatAiAmount } from '../../api/ai'
import type { AiJdRequirementsCreateRequest, AiJdRequirementsPreviewRequest } from '../../api/aiJdRequirements'
import { aiJdRequirementsPaths, aiJdRequirementsSourceValid, aiJdRequirementsSourcesMatch, aiJdRequirementsTaskValid } from '../../api/aiJdRequirements'
import type { PreparationItem, PreparationPage } from '../../api/preparation'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

type TargetLink = { itemId: string; versionId: string }
type TargetChoice = TargetLink & { title: string; number: number }
const props = defineProps<{ settings?: AiSettings; disabled?: boolean; initialTarget?: TargetLink }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const source = shallowRef<AiSource>()
const instruction = ref('')
const selecting = ref(false)
const selectionError = ref('')
const initialPending = ref(Boolean(props.initialTarget))
const pickerOpen = ref(false)
const searchInput = ref('')
const appliedSearch = ref('')
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
const choices = ref<TargetChoice[]>([])
const nextCursor = ref<string | null>(null)
const listLoading = ref(false)
const listLoaded = ref(false)
const listError = ref('')
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
const sourceIdentity = computed(() => JSON.stringify(props.initialTarget ?? null))
const sourceSignature = computed(() => JSON.stringify(source.value ?? null))
const request = computed<AiJdRequirementsPreviewRequest | undefined>(() => source.value
  ? { targetItemId: source.value.itemId, targetVersionId: source.value.versionId, instruction: instruction.value } : undefined)
const requestSignature = computed(() => JSON.stringify(request.value))
const dirty = computed(() => !createdResult.value && Boolean(source.value || instruction.value))
const previewMatches = computed(() => Boolean(prepared.value && request.value && source.value && preparedRequest.value === requestSignature.value
  && aiJdRequirementsSourcesMatch(prepared.value.manifest, request.value, source.value)))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const problem = computed(() => {
  if (initialPending.value) return '请核对入口所指的确切目标版本，或明确取消预填后重新选择。'
  if (!source.value || !aiJdRequirementsSourceValid(source.value)) return '请选择一个当前有效且已保存原始 JD 的目标版本。'
  if (!aiPreparationPlanTextValid(instruction.value, 1000)) return '整理要求最多 1000 个 UTF-16 码元，不能含 NUL 或不完整字符。'
  return ''
})
const changeState = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
let alive = true, selectionSequence = 0, listSequence = 0, previewSequence = 0, accessGeneration = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0

watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed; commandState.locked = waiting
  store.changes.set('ai-create', changeState); store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([requestSignature, instruction, sourceSignature], invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch([() => store.sessionEpoch, readable, sourceIdentity], () => {
  accessGeneration++; selectionSequence++; listSequence++
  selecting.value = false; listLoading.value = false; choices.value = []; listLoaded.value = false; nextCursor.value = null; pickerOpen.value = false
  invalidatePreview()
  // 访问变化仅使旧授权失效，已选原始 JD 与用户要求保持原样，不自动替换成新版本。
}, { flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['AI_CONFIG_CHANGED', 'AI_MANIFEST_CHANGED', 'AI_SOURCE_CHANGED', 'AI_SOURCE_UNAVAILABLE', 'AI_DISABLED', 'AI_NOT_CONFIGURED', 'AI_BUDGET_EXCEEDED'].includes(error.code)) {
    invalidatePreview(); emit('refresh')
  }
})
onUnmounted(() => {
  alive = false; selectionSequence++; listSequence++; previewSequence++
  if (store.changes.get('ai-create') === changeState) store.changes.delete('ai-create')
  if (store.changes.get('ai-command') === commandState) store.changes.delete('ai-command')
})

function idValid(value: unknown): value is string { return typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value) }
function invalidatePreview() { previewSequence++; previewing.value = false; prepared.value = undefined; preparedRequest.value = ''; consent.value = false }
function targetSource(value: PreparationItem, expected: TargetLink, listed?: TargetChoice): AiSource {
  if (!value || value.kind !== 'TARGET' || value.id !== expected.itemId || value.status !== 'ACTIVE' || value.projectId !== null
    || !value.currentVersion || value.currentVersion.id !== expected.versionId || !value.currentVersion.content
    || listed && (value.currentVersion.number !== listed.number || value.currentVersion.content.title !== listed.title)) {
    throw new ApiError(409, 'AI_SOURCE_CHANGED', '所选目标已改版、归档或读取内容不一致，请重新核对确切版本；不会自动换成新版本。')
  }
  // JD 来源与通用 TARGET 投影不同：只复制标题、原始 JD 和身份，不序列化公司、旧要求或关联材料。
  const selected: AiSource = { itemId: value.id, versionId: value.currentVersion.id, number: value.currentVersion.number,
    kind: 'TARGET', title: value.currentVersion.content.title, body: value.currentVersion.content.jd }
  if (!aiJdRequirementsSourceValid(selected)) throw new ApiError(409, 'AI_JD_SOURCE_INVALID', '该目标尚无有效的原始 JD，或标题和版本身份无效。请先在目标页补充并保存。')
  return selected
}
async function selectTarget(expected: TargetLink, listed?: TargetChoice) {
  if (blocked.value || previewing.value || selecting.value || source.value) return
  selectionError.value = ''
  if (!idValid(expected.itemId) || !idValid(expected.versionId)) { selectionError.value = '目标入口需要完整、有效的对象与确切版本标识，请返回目标页重新进入。'; return }
  const epoch = store.sessionEpoch, generation = accessGeneration, sequence = ++selectionSequence
  selecting.value = true; invalidatePreview()
  try {
    const value = await read<PreparationItem>(`/prepare/items/${expected.itemId}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== accessGeneration || sequence !== selectionSequence) return
    source.value = targetSource(value, expected, listed); initialPending.value = false; pickerOpen.value = false; message.value = ''
    listSequence++; listLoading.value = false
  } catch (error) { if (alive && sequence === selectionSequence && epoch === store.sessionEpoch) selectionError.value = errorText(error) }
  finally { if (alive && sequence === selectionSequence) selecting.value = false }
}
function chooseInitial() { if (props.initialTarget && initialPending.value) void selectTarget({ ...props.initialTarget }) }
function cancelInitial() {
  if (blocked.value || previewing.value) return
  selectionSequence++; selecting.value = false; initialPending.value = false; selectionError.value = ''; invalidatePreview()
}
function removeTarget() {
  if (blocked.value || previewing.value || selecting.value) return
  source.value = undefined; selectionError.value = ''; initialPending.value = false
}
function togglePicker() {
  if (blocked.value || previewing.value || selecting.value || source.value || initialPending.value) return
  pickerOpen.value = !pickerOpen.value
  if (pickerOpen.value) void loadTargets()
  else { listSequence++; listLoading.value = false }
}
async function loadTargets() {
  if (blocked.value || previewing.value || selecting.value || !pickerOpen.value) return
  if (!aiPreparationPlanTextValid(searchInput.value, 100)) { listError.value = '检索标题最多 100 个 UTF-16 码元，须为完整文字。'; return }
  const epoch = store.sessionEpoch, generation = accessGeneration, sequence = ++listSequence
  const parameters = new URLSearchParams({ kind: 'TARGET', status: 'ACTIVE', limit: '20', q: appliedSearch.value })
  if (cursor.value) parameters.set('cursor', cursor.value)
  listLoading.value = true; listError.value = ''; choices.value = []; nextCursor.value = null; listLoaded.value = false
  try {
    const value = await read<PreparationPage>(`/prepare/items?${parameters}`)
    if (!alive || blocked.value || !pickerOpen.value || epoch !== store.sessionEpoch || generation !== accessGeneration || sequence !== listSequence) return
    if (!value || !Array.isArray(value.items) || value.items.length > 20 || value.nextCursor !== null && !idValid(value.nextCursor)
      || value.items.some(item => item.kind !== 'TARGET' || item.status !== 'ACTIVE' || !idValid(item.id) || !idValid(item.versionId)
        || !Number.isSafeInteger(item.versionNumber) || item.versionNumber < 1 || !aiPreparationPlanTextValid(item.title, 200) || !item.title.trim())
      || new Set(value.items.map(item => item.id)).size !== value.items.length) throw new ApiError(502, 'AI_JD_TARGET_LIST_INVALID', '目标列表内容不完整，请重新读取。')
    // 列表只保存选择所需的标题和版本身份；正文需在明确选择后单独读取。
    choices.value = value.items.map(item => ({ itemId: item.id, versionId: item.versionId, title: item.title, number: item.versionNumber }))
    nextCursor.value = value.nextCursor; listLoaded.value = true
  } catch (error) { if (alive && sequence === listSequence && epoch === store.sessionEpoch) listError.value = errorText(error) }
  finally { if (alive && sequence === listSequence) listLoading.value = false }
}
function searchTargets() {
  if (blocked.value || selecting.value || listLoading.value) return
  appliedSearch.value = searchInput.value.trim(); cursor.value = undefined; cursors.value = []; void loadTargets()
}
function nextTargets() { if (!blocked.value && !selecting.value && !listLoading.value && nextCursor.value) { cursors.value.push(cursor.value); cursor.value = nextCursor.value; void loadTargets() } }
function previousTargets() { if (!blocked.value && !selecting.value && !listLoading.value && cursors.value.length) { cursor.value = cursors.value.pop(); void loadTargets() } }
async function prepare() {
  if (blocked.value || !configured.value || problem.value || selecting.value || previewing.value || !request.value || !source.value) return
  invalidatePreview()
  const epoch = store.sessionEpoch, generation = accessGeneration, sequence = ++previewSequence, signature = requestSignature.value!
  const snapshot = JSON.parse(signature) as AiJdRequirementsPreviewRequest, selected = { ...source.value }
  previewing.value = true; previewError.value = ''; message.value = ''
  try {
    const value = await preview<AiPreview>(aiJdRequirementsPaths.preview, snapshot)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== accessGeneration || sequence !== previewSequence || signature !== requestSignature.value) return
    if (!aiJdRequirementsSourcesMatch(value.manifest, snapshot, selected)) { previewError.value = '预览的目标版本、标题、原始 JD 或整理要求与本次选择不一致，请重新核对。'; return }
    if (JSON.stringify(value.manifest.configuration) !== JSON.stringify(props.settings?.configuration)) { previewError.value = '服务器配置与页面配置已不一致，请刷新配置后重新预览。'; emit('refresh'); return }
    prepared.value = value; preparedRequest.value = signature
  } catch (error) { if (alive && sequence === previewSequence && epoch === store.sessionEpoch) previewError.value = errorText(error) }
  finally { if (alive && sequence === previewSequence) previewing.value = false }
}
function receive(value: AiTask) {
  createdResult.value = value; changeState.dirty = false; commandState.locked = false
  message.value = 'JD 要求候选任务已创建，请在任务详情核对依据与解释后再决定采用。'; emit('refresh'); emit('created', value)
}
function create() {
  if (blocked.value || !configured.value || !prepared.value || !previewMatches.value || !consent.value || overBudget.value || selecting.value || problem.value || !source.value) return
  pickerOpen.value = false; listSequence++; commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration
  const snapshot = JSON.parse(preparedRequest.value) as AiJdRequirementsPreviewRequest, selected = { ...source.value }
  // 本地没有 sourceGeneration 的读取字段；服务预览给出的单目标代次随整个授权清单固定，绝不用 revision 猜造。
  const authorizedFingerprint = aiCandidateManifestFingerprint(prepared.value.manifest)
  const body: AiJdRequirementsCreateRequest = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  let submitted: typeof writer.pending.value
  void writer.run<AiTask>(aiJdRequirementsPaths.create, 'POST', body, value => {
    if (!alive) return
    let matches = false
    try { matches = value.task.purpose === 'JD_REQUIREMENT_DRAFT' && aiJdRequirementsTaskValid(value)
      && aiJdRequirementsSourcesMatch(value.manifest, snapshot, selected) && aiCandidateManifestFingerprint(value.manifest) === authorizedFingerprint } catch { matches = false }
    if (!matches) { writer.pending.value = submitted; writer.error.value = new ApiError(502, 'AI_TASK_MISMATCH', '任务回执与本次授权的原始 JD、配置、来源代次或费用不一致，请用原请求恢复；输入继续保留。'); emit('refresh'); return }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) deferred.value = value
    else receive(value)
  })
  submitted = writer.pending.value
}
function retryCommand() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="jd-requirements-create" aria-label="从原始 JD 整理要求候选">
    <header class="create-heading"><div><p class="section-label">新建 AI 任务</p><h2>从原始 JD 整理要求候选</h2><p class="field-help">选择一个当前已保存的目标版本，核对完整原始 JD 和费用，再授权本次整理。</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!configured" class="notice">AI 未启用或配置尚未完整，仍可选择目标并填写要求。你也可以回到<RouterLink class="text-link" :to="{ name: 'prepare', query: { kind: 'TARGET' } }">目标与 JD</RouterLink>手工整理。</p><p v-if="!readable" class="notice" role="status">登录或资料访问待确认，本地选择与要求继续保留。</p><p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="createdResult" class="notice"><NButton text :disabled="!readable" @click="emit('created', createdResult)">打开已创建的 JD 要求任务</NButton></p><p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retryCommand">用原请求重试</NButton></p><p v-if="deferred" class="notice" role="status">服务器已确认创建，访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建任务</NButton></p>
    <template v-if="!createdResult && readable">
      <section class="target-selection"><header class="panel-heading"><h3>本次目标</h3><NButton size="small" :disabled="blocked || previewing || selecting || !!source || initialPending" :aria-expanded="pickerOpen" @click="togglePicker">{{ pickerOpen ? '收起目标选择' : '选择目标版本' }}</NButton></header>
        <div v-if="initialPending" class="prefill-panel"><p class="field-help">入口目标 {{ initialTarget?.itemId }} · 确切版本 {{ initialTarget?.versionId }}</p><div class="row-actions"><NButton size="small" :loading="selecting" :disabled="blocked || previewing" @click="chooseInitial">读取并使用该版本</NButton><NButton text size="small" :disabled="blocked || previewing" @click="cancelInitial">取消预填，手动选择</NButton></div></div>
        <div v-if="source" class="selected-target"><header class="panel-heading"><div><h4>{{ source.title }} · v{{ source.number }}</h4><p class="field-help">目标 {{ source.itemId }} · 版本 {{ source.versionId }}</p><RouterLink class="text-link" :to="{ name: 'prepare', query: { kind: 'TARGET', item: source.itemId, version: source.versionId } }">查看这版目标</RouterLink></div><NButton size="small" quaternary :disabled="blocked || previewing || selecting" @click="removeTarget">重新选择</NButton></header><h4>本次外发的完整原始 JD</h4><pre class="exact-text">{{ source.body }}</pre></div><p v-else class="field-help">原始 JD 来自当前已保存版本；未保存的编辑不会自动进入清单。</p>
        <section v-if="pickerOpen" class="target-picker" aria-label="选择当前目标版本"><form class="title-search" @submit.prevent="searchTargets"><NInput v-model:value="searchInput" :maxlength="100" :disabled="blocked || selecting || listLoading" placeholder="按已保存标题查找" aria-label="查找目标" /><NButton attr-type="submit" :disabled="blocked || selecting || listLoading">查找</NButton></form><p v-if="listLoading" class="field-help" role="status">正在读取使用中的目标…</p><p v-if="listError" class="notice error" role="alert">{{ listError }}<NButton text :disabled="blocked || selecting || listLoading" @click="loadTargets">重新读取</NButton></p><div class="target-options"><button v-for="choice in choices" :key="choice.itemId" type="button" :disabled="blocked || selecting || listLoading || previewing" @click="selectTarget(choice, choice)"><strong>{{ choice.title }}</strong><small>v{{ choice.number }} · {{ choice.itemId }}</small></button></div><p v-if="listLoaded && !choices.length" class="field-help">没有匹配的当前目标，可调整标题检索，或先保存目标与原始 JD。</p><div class="row-actions"><NButton size="small" :disabled="blocked || selecting || listLoading || !cursors.length" @click="previousTargets">上一页</NButton><NButton size="small" :disabled="blocked || selecting || listLoading || !nextCursor" @click="nextTargets">下一页</NButton><NButton text size="small" :disabled="blocked || selecting || listLoading" @click="loadTargets">刷新当前列表</NButton></div></section>
        <p v-if="selecting" class="field-help" role="status">正在核对确切目标版本与原始 JD…</p><p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p>
      </section>
      <p class="field-help">本次仅外发目标标题、原始 JD、版本身份与下方整理要求。公司备注、已有要求、引用材料、投入预算和未保存草稿不会自动发送。</p>
      <label class="form-field">整理要求<NInput v-model:value="instruction" :disabled="blocked || previewing" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="例如：逐项提取可核对的岗位要求，保留原文依据，分清明确要求与解释。" /><span class="field-help">可留空，最多 1000 个 UTF-16 码元，按输入原文发送。候选不证明你已具备对应能力。</span></label><p class="field-help">生成不会自动保存或覆盖目标中的手工要求。请在任务详情核对候选，再明确选择采用。</p>
      <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || !!problem || selecting" @click="prepare">预览完整外发清单</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div><p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
      <section v-if="prepared && previewMatches" class="consent-panel"><AiManifest :manifest="prepared.manifest" :budget="prepared.budget" /><p v-if="overBudget" class="notice error">本次预留超过当前剩余预算，请核对预算后重新预览。</p><AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || !configured || selecting">我已核对完整原始 JD、目标版本和整理要求，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice><NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !consent || overBudget || !configured || !!problem || selecting" @click="create">授权本次外发并生成 JD 要求候选</NButton><p class="field-help">更改目标、要求、配置或访问条件后需重新预览。结果未知时使用原请求和原幂等键恢复；页面输入尚未保存为服务器草稿。</p></section>
    </template>
  </section>
</template>

<style scoped>
.jd-requirements-create { display: grid; gap: 23px; padding: 25px 27px 30px; min-width: 0; }.create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; }.create-heading > div, .panel-heading > div { min-width: 0; }h2 { margin: 7px 0 9px; font-size: 24px; }h3 { margin: 0; font-size: 16px; }h4 { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
.target-selection, .selected-target, .target-picker, .form-field, .prefill-panel { display: grid; gap: 12px; min-width: 0; }.selected-target { padding-top: 14px; border-top: 1px solid var(--line); }.target-picker, .prefill-panel { padding: 15px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); }.target-options { display: grid; gap: 4px; max-height: 280px; overflow-y: auto; }.target-options button { display: grid; gap: 6px; min-width: 0; padding: 11px; text-align: left; border-radius: 5px; }.target-options button:hover { background: var(--surface-deep); }.target-options strong { font-size: 13px; overflow-wrap: anywhere; }.target-options small, .form-field { color: var(--muted); font-size: 12px; }
.exact-text { margin: 0; padding: 13px; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.75 var(--font-body, sans-serif); background: var(--surface-soft); border-radius: 5px; }.consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); min-width: 0; }.consent-panel > .n-button { justify-self: start; }.field-help { overflow-wrap: anywhere; }
@media (max-width: 700px) { .jd-requirements-create { padding: 22px 17px; }h2 { font-size: 21px; }.panel-heading { flex-wrap: wrap; } }
</style>
