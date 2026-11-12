<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NInput } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { AiCreatePreparationPlan, AiPreparationPlanPreviewRequest, AiPreparationSelection, AiPreview, AiSettings, AiTask } from '../../api/ai'
import { aiPreparationPlanDays, aiPreparationPlanInputProblem, aiPreparationPlanManifestMatches, aiPreparationPlanTextValid, formatAiAmount } from '../../api/ai'
import type { PreparationItem } from '../../api/preparation'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'
import AiPreparationSourcePicker from './AiPreparationSourcePicker.vue'

const props = defineProps<{ settings?: AiSettings; disabled?: boolean; initialTarget?: AiPreparationPlanPreviewRequest['target'] }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const target = ref<AiPreparationSelection>()
const startDate = ref('')
const endDate = ref('')
const zoneId = ref('Asia/Shanghai')
const totalMinutes = ref('')
const gapSummary = ref('')
const instruction = ref('')
const pickerOpen = ref(false)
const pickerGeneration = ref(0)
const selecting = ref(false)
const presetLoading = ref(false)
const selectionError = ref('')
const selectionBusy = computed(() => selecting.value || presetLoading.value)
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
const dirty = computed(() => !createdResult.value && Boolean(target.value || startDate.value || endDate.value || totalMinutes.value
  || zoneId.value !== 'Asia/Shanghai' || gapSummary.value || instruction.value))
const configured = computed(() => Boolean(props.settings?.configuration.enabled && props.settings.configuration.configured))
const request = computed<AiPreparationPlanPreviewRequest>(() => ({
  target: { itemId: target.value?.itemId ?? '', versionId: target.value?.versionId ?? '' },
  plan: { startDate: startDate.value, endDate: endDate.value, zoneId: zoneId.value, totalMinutes: Number(totalMinutes.value), gapSummary: gapSummary.value },
  instruction: instruction.value,
}))
const days = computed(() => aiPreparationPlanDays(startDate.value, endDate.value))
const requestSignature = computed(() => JSON.stringify(request.value))
const previewMatches = computed(() => Boolean(prepared.value && preparedRequest.value === requestSignature.value))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const problem = computed(() => {
  if (!target.value) return '请选择一个当前有效的目标版本。'
  if (!/^\d+$/.test(totalMinutes.value)) return '请明确填写整个准备区间的整数分钟预算。'
  return aiPreparationPlanInputProblem(request.value.plan)
    || (aiPreparationPlanTextValid(instruction.value, 1000) ? '' : '附加要求最多 1000 个 UTF-16 码元，不能含 NUL 或不完整的 Unicode 字符。')
})
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let presetRequest = 0
let previewRequest = 0
let accessGeneration = 0
let commandEpoch = store.sessionEpoch
let commandGeneration = 0

watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed
  commandState.locked = waiting
  store.changes.set('ai-create', changeState)
  store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([requestSignature, totalMinutes], invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(props.initialTarget), () => { presetRequest++; presetLoading.value = false; selectionError.value = '' }, { flush: 'sync' })
watch([() => store.sessionEpoch, () => props.disabled, () => store.sessionActive], () => {
  accessGeneration++
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
  if (blocked.value || previewing.value || source.kind !== 'TARGET' || target.value) return
  target.value = source
  pickerOpen.value = false
  selectionError.value = ''
  message.value = ''
}
async function choosePreset() {
  if (blocked.value || previewing.value || selectionBusy.value || target.value || !props.initialTarget) return
  const expected = { ...props.initialTarget }
  const epoch = store.sessionEpoch
  const generation = ++presetRequest
  presetLoading.value = true
  selectionError.value = ''
  try {
    // 入口只携带已保存版本身份，用户主动读取后仍须预览并授权外发。
    const value = await read<PreparationItem>(`/prepare/items/${encodeURIComponent(expected.itemId)}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== presetRequest) return
    if (value.id !== expected.itemId || value.kind !== 'TARGET' || value.status !== 'ACTIVE' || value.currentVersion.id !== expected.versionId) {
      selectionError.value = '入口所指目标已改版或不可用，请重新选择当前目标版本。'
      return
    }
    choose({ kind: 'TARGET', itemId: value.id, versionId: value.currentVersion.id, title: value.currentVersion.content.title, number: value.currentVersion.number })
  } catch (error) { if (alive && generation === presetRequest && epoch === store.sessionEpoch) selectionError.value = errorText(error) }
  finally { if (alive && generation === presetRequest) presetLoading.value = false }
}
function removeTarget() { if (!blocked.value && !previewing.value) target.value = undefined }
function matches(manifest: AiPreview['manifest'], snapshot: AiPreparationPlanPreviewRequest, selected: AiPreparationSelection): boolean {
  const source = manifest.sources[0]
  const plan = manifest.preparationPlan
  return aiPreparationPlanManifestMatches(manifest) && manifest.instruction === snapshot.instruction
    && source?.itemId === snapshot.target.itemId && source.versionId === snapshot.target.versionId
    && source.title === selected.title && source.number === selected.number
    && plan?.startDate === snapshot.plan.startDate && plan.endDate === snapshot.plan.endDate && plan.zoneId === snapshot.plan.zoneId
    && plan.totalMinutes === snapshot.plan.totalMinutes && plan.gapSummary === snapshot.plan.gapSummary
}
async function prepare() {
  if (blocked.value || !configured.value || problem.value || selectionBusy.value || previewing.value || !target.value) return
  invalidatePreview()
  const epoch = store.sessionEpoch
  const generation = ++previewRequest
  const signature = requestSignature.value
  const snapshot = JSON.parse(signature) as AiPreparationPlanPreviewRequest
  const selected = { ...target.value }
  previewing.value = true
  previewError.value = ''
  message.value = ''
  try {
    const value = await preview<AiPreview>('/ai/preparation-plans/preview', snapshot)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== previewRequest || signature !== requestSignature.value) return
    if (!matches(value.manifest, snapshot, selected)) { previewError.value = '预览中的目标版本、日期、时区、投入或自述与本次选择不一致，请重新核对。'; return }
    prepared.value = value
    preparedRequest.value = signature
  } catch (error) { if (alive && generation === previewRequest && epoch === store.sessionEpoch) previewError.value = errorText(error) }
  finally { if (alive && generation === previewRequest) previewing.value = false }
}
function receive(value: AiTask) {
  createdResult.value = value
  pickerOpen.value = false
  changeState.dirty = false
  commandState.locked = false
  message.value = '准备建议任务已创建并授权一次尝试。请审阅候选后，再决定是否创建普通准备任务。'
  emit('refresh')
  emit('created', value)
}
function create() {
  if (blocked.value || !configured.value || !previewMatches.value || !prepared.value || !consent.value || overBudget.value || selectionBusy.value || problem.value || !target.value) return
  pickerOpen.value = false
  presetRequest++
  commandEpoch = store.sessionEpoch
  commandGeneration = accessGeneration
  const snapshot = JSON.parse(preparedRequest.value) as AiPreparationPlanPreviewRequest
  const selected = { ...target.value }
  const authorized = prepared.value.manifest
  const body: AiCreatePreparationPlan = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  let submitted: typeof writer.pending.value
  void writer.run<AiTask>('/ai/preparation-plans/tasks', 'POST', body, value => {
    if (!alive) return
    const sameAuthorization = value.manifest.sources[0]?.body === authorized.sources[0]?.body
      && value.manifest.preparationChecks?.[0]?.sourceGeneration === authorized.preparationChecks?.[0]?.sourceGeneration
      && value.manifest.reservedMicros === authorized.reservedMicros && value.manifest.inputTokenAllowance === authorized.inputTokenAllowance
      && JSON.stringify(value.manifest.configuration) === JSON.stringify(authorized.configuration)
    if (value.task.purpose !== 'PREPARATION_PLAN' || !matches(value.manifest, snapshot, selected) || !sameAuthorization) {
      // 无法核对的成功回执仍需原键恢复，不能允许另建任务重复外发。
      writer.pending.value = submitted
      writer.error.value = new ApiError(502, 'AI_TASK_MISMATCH', '任务回执与本次授权不一致，请用原请求恢复；输入继续保留。')
      emit('refresh')
      return
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) deferred.value = value
    else receive(value)
  })
  submitted = writer.pending.value
}
function retryCommand() {
  if (readable.value && !writer.busy.value) {
    commandEpoch = store.sessionEpoch
    commandGeneration = accessGeneration
    void writer.retry()
  }
}
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="preparation-plan-create" aria-label="生成准备建议">
    <header class="create-heading"><div><h2>从目标安排准备事项</h2><p class="field-help">选择一个目标版本，明确日期、预计投入和自述缺口，核对外发清单后授权一次生成。</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!configured" class="notice">{{ settings?.configuration.enabled ? '第三方 API 尚未配置完整，目标和安排输入可以先保留在本页。' : 'AI 当前未启用，目标和安排输入可以先保留在本页。' }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="createdResult" class="notice"><NButton text @click="emit('created', createdResult)">打开已创建的准备建议</NButton></p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retryCommand">用原请求重试</NButton></p>
    <p v-if="deferred" class="notice" role="status">服务器已确认创建，登录或资料访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建的准备建议</NButton></p>
    <template v-if="!createdResult">
      <section class="target-selection">
        <header class="panel-heading"><h3>本次目标</h3><NButton size="small" :disabled="blocked || previewing || presetLoading || !!target" :aria-expanded="pickerOpen" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起目标选择' : '选择目标版本' }}</NButton></header>
        <p v-if="initialTarget && !target" class="notice" role="status">准备页带入一个确切目标版本，尚未加入外发范围。<NButton text :loading="presetLoading" :disabled="blocked || previewing || selectionBusy" @click="choosePreset">读取并使用该目标</NButton></p>
        <div v-if="target" class="selected-target"><div><strong>{{ target.title }} · v{{ target.number }}</strong><small>目标 {{ target.itemId }} · 版本 {{ target.versionId }}</small></div><NButton size="small" quaternary :disabled="blocked || previewing" @click="removeTarget">重新选择</NButton></div>
        <p v-else class="field-help">仅使用一个当前有效的目标与 JD，岗位要求不会被当作你的现有能力。</p>
        <AiPreparationSourcePicker v-if="pickerOpen" :key="pickerGeneration" :kinds="['TARGET']" :disabled="blocked || previewing || presetLoading || !!target" @busy="selecting = $event" @choose="choose" />
        <p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p>
      </section>
      <div class="plan-fields">
        <label class="form-field">开始日期<input v-model="startDate" type="date" min="1000-01-01" max="9999-12-31" :disabled="blocked || previewing" /></label>
        <label class="form-field">结束日期<input v-model="endDate" type="date" min="1000-01-01" max="9999-12-31" :disabled="blocked || previewing" /></label>
        <label class="form-field">安排时区<NInput v-model:value="zoneId" :disabled="blocked || previewing" placeholder="Asia/Shanghai 或 UTC" /></label>
        <label class="form-field">整个区间的预计投入（分钟）<NInput v-model:value="totalMinutes" :disabled="blocked || previewing" :input-props="{ inputmode: 'numeric' }" placeholder="例如 600" /></label>
      </div>
      <p class="field-help">{{ days >= 1 && days <= 90 ? `当前包含首尾 ${days} 天。` : '包含首尾最多 90 天。' }}分钟数是整个区间的工作量预算；每周投入和 AI 调用费用分别记录。日期可以在过去，按所填日期与时区保存，不自动顺延。</p>
      <label class="form-field">自述准备缺口<NInput v-model:value="gapSummary" :disabled="blocked || previewing" type="textarea" :maxlength="2000" :autosize="{ minRows: 4, maxRows: 10 }" placeholder="例如：希望重新梳理缓存失效的排查过程，并练习说明自己参与过的项目取舍。" /><span class="field-help">可留空，最多 2000 个 UTF-16 码元。这是你的自述，不是系统测出的能力结论；填写内容将完整发送。</span></label>
      <label class="form-field">附加要求<NInput v-model:value="instruction" :disabled="blocked || previewing" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="例如：先安排梳理，再安排独立回答；每天的步骤尽量集中。" /><span class="field-help">可留空，最多 1000 个 UTF-16 码元，保留原文。手工输入的私人文字也会发送。</span></label>
      <p class="field-help">本次未读取你的日历、训练记录、回忆卡或改进行动；生成建议不表示日历无冲突，也不会自动重排已有任务。</p>
      <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || !!problem || selectionBusy" @click="prepare">{{ prepared ? '重新核对外发清单' : '预览外发清单' }}</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div>
      <p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
      <section v-if="prepared && previewMatches" class="consent-panel">
        <AiManifest :manifest="prepared.manifest" :budget="prepared.budget" />
        <p v-if="overBudget" class="notice error">本次调用预留超过当前剩余费用预算，请核对未决费用或服务器预算后重新预览。</p>
        <AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || !configured">我已核对目标原文、日期、时区、整个区间的投入、自述缺口及附加要求，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，并为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice>
        <NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !consent || overBudget || !configured || selectionBusy" @click="create">授权本次外发并生成准备建议</NButton>
        <p class="field-help">更改任意输入或配置后需重新预览授权。结果未知时使用原请求恢复；候选经你审阅并采用后才创建普通准备任务。</p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.preparation-plan-create { display: grid; gap: 23px; min-width: 0; padding: 25px 27px 30px; }
.create-heading, .panel-heading, .selected-target { display: flex; justify-content: space-between; align-items: start; gap: 15px; }
.create-heading > div, .selected-target > div { min-width: 0; } h2 { margin: 0 0 9px; font-size: 24px; text-wrap: balance; } h3 { margin: 0; font-size: 16px; }
.form-field, .target-selection { display: grid; gap: 12px; min-width: 0; } .form-field { color: var(--muted); font-size: 13px; }
.plan-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.form-field > input { width: 100%; min-width: 0; min-height: 34px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 4px; background: var(--surface); color: var(--ink); font: inherit; }
.selected-target { padding: 13px 0; border-bottom: 1px solid var(--line); }
.selected-target > div { display: grid; gap: 6px; } .selected-target strong { font-size: 13px; overflow-wrap: anywhere; }
.selected-target small { color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); min-width: 0; }
.consent-panel > .n-button { justify-self: start; } .field-help { max-width: 75ch; }
@media (max-width: 700px) { .preparation-plan-create { padding: 22px 17px; } h2 { font-size: 21px; } .panel-heading, .selected-target { flex-wrap: wrap; } .plan-fields { grid-template-columns: 1fr; } }
</style>
