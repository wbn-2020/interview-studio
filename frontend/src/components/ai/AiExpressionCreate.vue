<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { AiCreateExpressionDraft, AiExpressionDraftPreviewRequest, AiExpressionType, AiPreparationSelection, AiPreview, AiSettings, AiTask } from '../../api/ai'
import { aiExpressionManifestMatches, aiFactResponsibility, formatAiAmount } from '../../api/ai'
import type { PreparationItem } from '../../api/preparation'
import { expressionOptions, responsibilityLabels } from '../../api/preparation'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'
import AiPreparationSourcePicker from './AiPreparationSourcePicker.vue'

const props = defineProps<{ settings?: AiSettings; disabled?: boolean; initialFact?: AiExpressionDraftPreviewRequest['factSources'][number] }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const type = ref<AiExpressionType>('PROJECT_SHORT')
const instruction = ref('')
const sources = ref<AiPreparationSelection[]>([])
const selectedIds = computed(() => sources.value.map(source => source.itemId))
const pickerOpen = ref(false)
const pickerGeneration = ref(0)
const selecting = ref(false)
const presetLoading = ref(false)
const selectionError = ref('')
const selectionBusy = computed(() => selecting.value || presetLoading.value)
const presetSelected = computed(() => Boolean(props.initialFact && selectedIds.value.includes(props.initialFact.itemId)))
const previewing = ref(false)
const previewError = ref('')
const prepared = shallowRef<AiPreview>()
const preparedRequest = ref('')
const consent = ref(false)
const authorization = ref<AiAuthorizationReference>()
const deferred = shallowRef<AiTask>()
const createdResult = shallowRef<AiTask>()
const message = ref('')
const readable = computed(() => store.sessionActive && !props.disabled)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || Boolean(createdResult.value))
const dirty = computed(() => !createdResult.value && Boolean(instruction.value || sources.value.length || type.value !== 'PROJECT_SHORT'))
const configured = computed(() => Boolean(props.settings?.configuration.enabled && props.settings.configuration.configured))
const request = computed<AiExpressionDraftPreviewRequest>(() => ({ type: type.value, instruction: instruction.value.trim(),
  factSources: sources.value.map(source => ({ itemId: source.itemId, versionId: source.versionId })) }))
const requestSignature = computed(() => JSON.stringify(request.value))
const previewMatches = computed(() => Boolean(prepared.value && preparedRequest.value === requestSignature.value))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const problem = computed(() => {
  if (!sources.value.length || sources.value.length > 5) return '请选择 1–5 个当前由你确认的事实版本。'
  if (instruction.value.trim().length > 1000 || instruction.value.includes('\0')) return '生成要求最多 1000 字符，不能含空字符。'
  return ''
})
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let presetRequest = 0
let previewRequest = 0
let commandEpoch = store.sessionEpoch

watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed
  commandState.locked = waiting
  store.changes.set('ai-create', changeState)
  store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([requestSignature, instruction], invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(props.initialFact), () => { presetRequest++; presetLoading.value = false; selectionError.value = '' }, { flush: 'sync' })
watch([() => store.sessionEpoch, () => props.disabled, () => store.sessionActive], () => {
  presetRequest++
  presetLoading.value = false
  selecting.value = false
  pickerGeneration.value++
  pickerOpen.value = false
  invalidatePreview()
}, { flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['AI_CONFIG_CHANGED', 'AI_MANIFEST_CHANGED', 'AI_SOURCE_CHANGED', 'AI_DISABLED', 'AI_NOT_CONFIGURED', 'AI_BUDGET_EXCEEDED'].includes(error.code)) {
    invalidatePreview()
    emit('refresh')
  }
})
onUnmounted(() => {
  alive = false
  presetRequest++
  previewRequest++
  if (store.changes.get('ai-create') === changeState) store.changes.delete('ai-create')
  if (store.changes.get('ai-command') === commandState) store.changes.delete('ai-command')
})

function invalidatePreview() {
  previewRequest++
  previewing.value = false
  prepared.value = undefined
  preparedRequest.value = ''
  consent.value = false
}
function choose(source: AiPreparationSelection) {
  if (blocked.value || previewing.value || source.kind !== 'FACT' || sources.value.length >= 5 || selectedIds.value.includes(source.itemId)) return
  if (!source.responsibility || source.responsibility === 'UNVERIFIED') { selectionError.value = '该事实的责任边界尚不能确认，请重新读取后选择。'; return }
  sources.value.push(source)
  selectionError.value = ''
  message.value = ''
}
async function choosePreset() {
  if (blocked.value || previewing.value || selectionBusy.value || sources.value.length >= 5 || presetSelected.value || !props.initialFact) return
  const expected = { ...props.initialFact }
  const epoch = store.sessionEpoch
  const generation = ++presetRequest
  presetLoading.value = true
  selectionError.value = ''
  try {
    // 路由仅携带版本身份；用户主动加入后才读取，读取本身也不授权模型调用。
    const value = await read<PreparationItem>(`/prepare/items/${encodeURIComponent(expected.itemId)}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== presetRequest) return
    if (value.id !== expected.itemId || value.kind !== 'FACT' || value.status !== 'ACTIVE'
      || value.currentVersion.id !== expected.versionId || value.verification !== 'CONFIRMED'
      || value.currentVersion.content.responsibility === 'UNVERIFIED') {
      selectionError.value = '入口所指的事实已变化或不可用，请在选择器中重新核对当前事实。'
      return
    }
    choose({ kind: 'FACT', itemId: value.id, versionId: value.currentVersion.id, title: value.currentVersion.content.title,
      number: value.currentVersion.number, responsibility: value.currentVersion.content.responsibility })
  } catch (error) { if (alive && generation === presetRequest && epoch === store.sessionEpoch) selectionError.value = errorText(error) }
  finally { if (alive && generation === presetRequest) presetLoading.value = false }
}
function remove(itemId: string) { if (!blocked.value && !previewing.value) sources.value = sources.value.filter(source => source.itemId !== itemId) }
function matches(manifest: AiPreview['manifest'], snapshot: AiExpressionDraftPreviewRequest): boolean {
  if (!aiExpressionManifestMatches(manifest) || manifest.expressionType !== snapshot.type || manifest.instruction !== snapshot.instruction
    || manifest.sources.length !== snapshot.factSources.length) return false
  return snapshot.factSources.every((link, index) => {
    const source = manifest.sources[index]
    const selected = sources.value[index]
    return Boolean(source && selected && source.itemId === link.itemId && source.versionId === link.versionId
      && source.kind === 'FACT' && source.title === selected.title && source.number === selected.number
      && aiFactResponsibility(source) === selected.responsibility)
  })
}
async function prepare() {
  if (blocked.value || !configured.value || problem.value || selectionBusy.value || previewing.value) return
  invalidatePreview()
  const epoch = store.sessionEpoch
  const generation = ++previewRequest
  const signature = requestSignature.value
  const snapshot = JSON.parse(signature) as AiExpressionDraftPreviewRequest
  previewing.value = true
  previewError.value = ''
  message.value = ''
  try {
    const value = await preview<AiPreview>('/ai/expression-drafts/preview', snapshot)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== previewRequest || signature !== requestSignature.value) return
    if (!matches(value.manifest, snapshot)) { previewError.value = '预览中的表达类型、事实版本、责任边界或授权核对与本次选择不一致，请重新核对。'; return }
    prepared.value = value
    preparedRequest.value = signature
  } catch (error) { if (alive && generation === previewRequest && epoch === store.sessionEpoch) previewError.value = errorText(error) }
  finally { if (alive && generation === previewRequest) previewing.value = false }
}
function receive(value: AiTask) {
  createdResult.value = value
  instruction.value = ''
  sources.value = []
  pickerOpen.value = false
  changeState.dirty = false
  commandState.locked = false
  message.value = '表达任务已保存并授权一次尝试，可在任务记录审阅候选、依据和费用。'
  emit('refresh')
  emit('created', value)
}
function create() {
  if (blocked.value || !configured.value || !previewMatches.value || !prepared.value || !consent.value || overBudget.value || selectionBusy.value || problem.value) return
  pickerOpen.value = false
  presetRequest++
  commandEpoch = store.sessionEpoch
  const snapshot = JSON.parse(preparedRequest.value) as AiExpressionDraftPreviewRequest
  const authorized = prepared.value.manifest
  const body: AiCreateExpressionDraft = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  // 成功回执还须对应本次固定正文与代次；原键恢复不能把新事实替换进已授权的任务。
  void writer.run<AiTask>('/ai/expression-drafts/tasks', 'POST', body, value => {
    if (!alive) return
    const sameAuthorization = value.manifest.sources.every((source, index) => source.body === authorized.sources[index]?.body)
      && value.manifest.preparationChecks?.every((check, index) => check.sourceGeneration === authorized.preparationChecks?.[index]?.sourceGeneration
        && check.projectGeneration === authorized.preparationChecks?.[index]?.projectGeneration)
    if (value.task.purpose !== 'EXPRESSION_DRAFT' || !matches(value.manifest, snapshot) || !sameAuthorization) {
      emit('refresh')
      throw new ApiError(502, 'AI_TASK_MISMATCH', '表达任务回执与本次授权不一致，原请求继续保留，可用原请求重试或从任务记录核对。')
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = value
    else receive(value)
  })
}
function retryCommand() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="expression-create" aria-label="生成表达素材">
    <header class="create-heading"><div><h2>从已确认事实生成表达</h2><p class="field-help">选择事实和表达用途，核对完整外发清单后授权一次生成。候选经你审阅后才能保存为表达素材。</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!configured" class="notice">{{ settings?.configuration.enabled ? '第三方 API 尚未配置完整。生成要求和事实选择可以先保留在本页。' : 'AI 当前未启用，生成要求和事实选择可以先保留在本页。' }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="createdResult" class="notice"><NButton text @click="emit('created', createdResult)">打开已创建表达任务</NButton></p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retryCommand">用原请求重试</NButton></p>
    <p v-if="deferred" class="notice" role="status">服务器已确认创建，登录或资料访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建表达任务</NButton></p>
    <template v-if="!createdResult">
      <label class="form-field">表达用途<NSelect v-model:value="type" :options="expressionOptions" :disabled="blocked || previewing" aria-label="表达用途" /><span class="field-help">用途在本次授权后固定。信息不足时保留不足，不补造经历、指标、技术或职责。</span></label>
      <section class="source-selection">
        <header class="panel-heading"><h3>本次事实 <span>{{ sources.length }} / 5</span></h3><NButton size="small" :disabled="blocked || previewing || presetLoading || (sources.length >= 5 && !pickerOpen)" :aria-expanded="pickerOpen" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起事实选择' : '选择已确认事实' }}</NButton></header>
        <p v-if="initialFact && !presetSelected" class="notice" role="status">准备页带入一个确切事实版本，尚未加入本次外发范围。<NButton text :loading="presetLoading" :disabled="blocked || previewing || selectionBusy || sources.length >= 5" @click="choosePreset">读取并加入该事实</NButton></p>
        <ol v-if="sources.length" class="selected-facts"><li v-for="source in sources" :key="source.itemId"><div><strong>{{ source.title }}</strong><span>v{{ source.number }} · {{ source.responsibility ? responsibilityLabels[source.responsibility] : '责任待核对' }}</span><small>事实 {{ source.itemId }} · 版本 {{ source.versionId }}</small></div><NButton size="small" quaternary :disabled="blocked || previewing" @click="remove(source.itemId)">移出本次选择</NButton></li></ol>
        <p v-else class="empty-choice">先选择至少一个已确认事实。事实及其所属项目的可用性会在预览和发送前复核。</p>
        <AiPreparationSourcePicker v-if="pickerOpen" :key="pickerGeneration" :kinds="['FACT']" :disabled="blocked || previewing || presetLoading || sources.length >= 5" :exclude-ids="selectedIds" @busy="selecting = $event" @choose="choose" />
        <p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p>
        <p class="field-help">“参与”与“仅了解”保持原责任边界。可选择不同项目的事实，生成内容需逐项组织，不能擅自拼成同一项目或添加因果。</p>
      </section>
      <label class="form-field">生成要求<NInput v-model:value="instruction" :disabled="blocked || previewing" type="textarea" :maxlength="1000" :autosize="{ minRows: 4, maxRows: 10 }" placeholder="例如：先说明业务问题，再突出我实际参与的动作；保留结果中尚未核实的部分。" /><span class="field-help">可留空，最多 1000 字符。手工输入的私人文字也会发送；总量超限时需要你调整，系统不静默截断。</span></label>
      <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || !!problem || selectionBusy" @click="prepare">{{ prepared ? '重新核对外发清单' : '预览外发清单' }}</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div>
      <p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
      <section v-if="prepared && previewMatches" class="consent-panel">
        <AiManifest :manifest="prepared.manifest" :budget="prepared.budget" />
        <p v-if="overBudget" class="notice error">本次预留超过当前剩余预算，请核对未决费用或服务器预算后重新预览。</p>
        <AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || !configured">我已核对表达用途、完整要求、事实和责任边界，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，并为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice>
        <NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !consent || overBudget || !configured || selectionBusy" @click="create">授权本次外发并生成表达</NButton>
        <p class="field-help">用途、事实、要求或配置变化后需重新预览授权。结果未知时使用原请求恢复；候选不会自动保存为表达，也不改变事实核实状态。</p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.expression-create { display: grid; gap: 23px; min-width: 0; padding: 25px 27px 30px; }
.create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; }
.create-heading > div { min-width: 0; } h2 { margin: 0 0 9px; font-size: 24px; text-wrap: balance; } h3 { margin: 0; font-size: 16px; }
h3 span { margin-left: 8px; font-size: 12px; font-weight: 400; color: var(--muted); }
.form-field, .source-selection { display: grid; gap: 12px; min-width: 0; } .form-field { color: var(--muted); font-size: 13px; }
.selected-facts { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.selected-facts li { display: flex; justify-content: space-between; align-items: start; gap: 15px; border-bottom: 1px solid var(--line); padding: 13px 0; }
.selected-facts li > div { display: grid; gap: 6px; min-width: 0; } .selected-facts strong { font-size: 13px; overflow-wrap: anywhere; }
.selected-facts span, .selected-facts small { color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.empty-choice { padding: 20px 0; color: var(--muted); font-size: 13px; }
.consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); min-width: 0; }
.consent-panel > .n-button { justify-self: start; } .field-help { max-width: 75ch; }
@media (max-width: 700px) { .expression-create { padding: 22px 17px; } h2 { font-size: 21px; } .panel-heading, .selected-facts li { flex-wrap: wrap; } }
</style>
