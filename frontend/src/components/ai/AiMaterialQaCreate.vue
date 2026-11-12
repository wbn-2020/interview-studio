<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { AiCreateMaterialQa, AiMaterialQaMode, AiMaterialQaPreviewRequest, AiPreview, AiSettings, AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiKnowledgeMaterialChecksMatch, aiMaterialChecksMatch, aiMaterialModeLabels, formatAiAmount } from '../../api/ai'
import type { MaterialExcerpt, MaterialStatus } from '../../api/material'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import MaterialExcerptPicker from '../material/MaterialExcerptPicker.vue'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

const props = defineProps<{ settings?: AiSettings; disabled?: boolean }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const mode = ref<AiMaterialQaMode>('MATERIAL')
const modeOptions = Object.entries(aiMaterialModeLabels).map(([value, label]) => ({ value, label }))
const question = ref('')
const excerpts = ref<MaterialExcerpt[]>([])
const pickerOpen = ref(false)
const pickerGeneration = ref(0)
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
const dirty = computed(() => !createdResult.value && Boolean(question.value || excerpts.value.length || mode.value !== 'MATERIAL'))
const configured = computed(() => Boolean(props.settings?.configuration.enabled && props.settings.configuration.configured))
const materialStatus = useQuery({
  queryKey: computed(() => ['material', store.sessionEpoch, 'status']),
  enabled: computed(() => readable.value && mode.value === 'MATERIAL'),
  queryFn: () => read<MaterialStatus>('/materials/status'),
})
const materialReady = computed(() => mode.value === 'GENERAL' || materialStatus.data.value?.enabled === true)
const request = computed<AiMaterialQaPreviewRequest>(() => ({ mode: mode.value, question: question.value.trim(),
  excerptIds: mode.value === 'MATERIAL' ? excerpts.value.map(excerpt => excerpt.id) : [] }))
const requestSignature = computed(() => JSON.stringify(request.value))
const previewMatches = computed(() => Boolean(prepared.value && preparedRequest.value === requestSignature.value))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const problem = computed(() => {
  if (!question.value.trim() || question.value.trim().length > 2000 || question.value.includes('\0')) return '请填写 1–2000 字符的问题，不含空字符。'
  if (mode.value === 'MATERIAL' && (!excerpts.value.length || excerpts.value.length > 5)) return '请选择 1–5 条已保存的确切摘录。'
  return ''
})
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let previewRequest = 0
let commandEpoch = store.sessionEpoch

watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed
  commandState.locked = waiting
  store.changes.set('ai-create', changeState)
  store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([mode, question, () => excerpts.value.map(excerpt => excerpt.id).join(',')], invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch(() => materialStatus.data.value?.enabled, () => { if (mode.value === 'MATERIAL') invalidatePreview() }, { flush: 'sync' })
watch([() => store.sessionEpoch, () => props.disabled, () => store.sessionActive], () => {
  pickerGeneration.value++
  pickerOpen.value = false
  invalidatePreview()
}, { flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['AI_CONFIG_CHANGED', 'AI_MANIFEST_CHANGED', 'AI_SOURCE_CHANGED', 'AI_DISABLED', 'AI_NOT_CONFIGURED', 'AI_BUDGET_EXCEEDED'].includes(error.code)) {
    invalidatePreview()
    emit('refresh')
    if (mode.value === 'MATERIAL' && readable.value) void materialStatus.refetch()
  }
})
onUnmounted(() => {
  alive = false
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
function choose(value: MaterialExcerpt) {
  if (blocked.value || mode.value !== 'MATERIAL' || !materialReady.value || excerpts.value.length >= 5 || excerpts.value.some(item => item.id === value.id)) return
  excerpts.value.push(value)
  message.value = ''
}
function remove(id: string) { if (!blocked.value) excerpts.value = excerpts.value.filter(item => item.id !== id) }
function matches(value: AiPreview['manifest'], snapshot: AiMaterialQaPreviewRequest): boolean {
  const input = value.materialQa
  if (value.purpose !== 'MATERIAL_QA' || !input || value.sources.length || value.training != null || value.evaluation != null || value.contextChecks != null
    || value.trainingDraft != null || value.expressionChecks != null || value.relationAiFeedback != null || value.instruction
    || !aiMaterialChecksMatch(value, true) || !aiKnowledgeMaterialChecksMatch(value)) return false
  return JSON.stringify({ mode: input.mode, question: input.question, excerptIds: input.excerpts.map(excerpt => excerpt.excerptId) }) === JSON.stringify(snapshot)
}
async function prepare() {
  if (blocked.value || !configured.value || !materialReady.value || problem.value || previewing.value) return
  invalidatePreview()
  const epoch = store.sessionEpoch
  const generation = ++previewRequest
  const signature = requestSignature.value
  const snapshot = JSON.parse(signature) as AiMaterialQaPreviewRequest
  previewing.value = true
  previewError.value = ''
  message.value = ''
  try {
    const value = await preview<AiPreview>('/ai/material-qa/preview', snapshot)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== previewRequest || signature !== requestSignature.value) return
    if (!matches(value.manifest, snapshot)) { previewError.value = '服务器预览与本次模式、问题或所选摘录不一致，请重新核对。'; return }
    prepared.value = value
    preparedRequest.value = signature
  } catch (error) { if (alive && generation === previewRequest && epoch === store.sessionEpoch) previewError.value = errorText(error) }
  finally { if (alive && generation === previewRequest) previewing.value = false }
}
function receive(value: AiTask) {
  createdResult.value = value
  question.value = ''
  excerpts.value = []
  pickerOpen.value = false
  changeState.dirty = false
  commandState.locked = false
  message.value = '问答任务已保存并授权一次尝试，可从任务记录查看回答、引用和费用。'
  emit('refresh')
  emit('created', value)
}
function create() {
  if (blocked.value || !configured.value || !materialReady.value || !previewMatches.value || !prepared.value || prepared.value.manifest.expressionChecks != null
    || !aiKnowledgeMaterialChecksMatch(prepared.value.manifest)
    || prepared.value.manifest.relationAiFeedback != null || !consent.value || overBudget.value || problem.value) return
  pickerOpen.value = false
  commandEpoch = store.sessionEpoch
  const snapshot = JSON.parse(preparedRequest.value) as AiMaterialQaPreviewRequest
  const authorizedFingerprint = aiCandidateManifestFingerprint(prepared.value.manifest)
  const body: AiCreateMaterialQa = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  // 写命令固定本次快照与原幂等键；登录或资料守卫变化只延后接收，不另建问答。
  void writer.run<AiTask>('/ai/material-qa/tasks', 'POST', body, value => {
    if (!alive) return
    let receiptMatches: boolean
    try {
      receiptMatches = value.task.purpose === 'MATERIAL_QA' && matches(value.manifest, snapshot)
        && aiCandidateManifestFingerprint(value.manifest) === authorizedFingerprint
    } catch { receiptMatches = false }
    if (!receiptMatches) {
      emit('refresh')
      throw new ApiError(502, 'AI_TASK_MISMATCH', '问答回执与本次请求不一致，原请求继续保留，可用原请求重试或从任务记录核对。')
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = value
    else receive(value)
  })
}
function retryCommand() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="material-qa-create" aria-label="新建资料问答">
    <header class="create-heading"><div><h2>向资料提问</h2><p class="field-help">选定确切摘录和问题，预览完整外发内容与费用，再授权这一次问答。</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!configured" class="notice">{{ settings?.configuration.enabled ? '第三方 API 尚未配置完整。问题和选择可以先保留在本页。' : 'AI 当前未启用，问题和选择可以先保留在本页。' }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="createdResult" class="notice"><NButton text @click="emit('created', createdResult)">打开已创建问答</NButton></p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retryCommand">用原请求重试</NButton></p>
    <p v-if="deferred" class="notice" role="status">服务器已确认创建，登录或资料访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建问答</NButton></p>
    <template v-if="!createdResult">
      <label class="form-field">回答依据<NSelect v-model:value="mode" :options="modeOptions" :disabled="blocked" aria-label="回答依据" /><span class="field-help">{{ mode === 'MATERIAL' ? '仅依据你选中的摘录回答；资料不足时明确说明，不自动改为通用解释。' : '通用解释只发送问题，不附带摘录，也不声称查阅过你的私人材料。' }}</span></label>
      <section v-if="mode === 'MATERIAL'" class="source-selection">
        <header class="panel-heading"><h3>本次摘录 <span>{{ excerpts.length }} / 5</span></h3><NButton size="small" :disabled="blocked || !materialReady || excerpts.length >= 5" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起选择器' : '选择已保存摘录' }}</NButton></header>
        <p v-if="materialStatus.isPending.value" class="field-help" role="status">正在读取资料模块状态…</p>
        <p v-else-if="materialStatus.error.value" class="notice error" role="alert">{{ errorText(materialStatus.error.value) }}<NButton text :disabled="!readable" @click="materialStatus.refetch()">重读资料状态</NButton></p>
        <p v-else-if="!materialReady" class="notice">资料模块当前未启用。已选摘录保留；可等模块恢复，或由你明确选择“通用解释”。</p>
        <ol v-if="excerpts.length" class="selected-excerpts"><li v-for="excerpt in excerpts" :key="excerpt.id"><div><strong>{{ excerpt.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ excerpt.locator }}</strong><p>{{ excerpt.text.slice(0, 180) }}{{ excerpt.text.length > 180 ? '…' : '' }}</p><small>摘录 {{ excerpt.id }} · {{ excerpt.text.length }} 字符，完整原文在外发预览中核对</small></div><NButton size="small" quaternary :disabled="blocked" @click="remove(excerpt.id)">移出本次选择</NButton></li></ol>
        <p v-else class="empty-choice">先选附件，再选择 1–5 条已保存摘录。选择器中的其他命中不会发送。</p>
        <MaterialExcerptPicker v-if="pickerOpen" :key="pickerGeneration" search-excerpts for-ai :disabled="blocked || !materialReady || excerpts.length >= 5" :exclude-ids="excerpts.map(excerpt => excerpt.id)" @choose="choose" />
        <p class="field-help">没有合适的已保存摘录时，可以换词、选择其他附件，或自行把回答依据切换为“通用解释”。本次问答不会自动读取整份附件。</p>
      </section>
      <p v-else class="notice" role="status">本次为通用解释，摘录来源为 0 条。{{ excerpts.length ? `本页已选的 ${excerpts.length} 条摘录继续保留，切回资料模式后可使用。` : '' }}手工粘贴进问题的资料文字仍会发送，请在预览中核对。</p>
      <label class="form-field">你的问题<NInput v-model:value="question" :disabled="blocked" type="textarea" :maxlength="2000" :autosize="{ minRows: 4, maxRows: 12 }" :placeholder="mode === 'MATERIAL' ? '例如：这些摘录能否说明缓存更新失败后如何补偿？请指出依据和仍缺少的信息。' : '例如：请解释缓存更新失败时常见的补偿方式及适用条件。'" /><span class="field-help">最多 2000 字符；问题内手工输入的文字也会外发。检索词和历史问答不会自动附带。</span></label>
      <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || !materialReady || !!problem" @click="prepare">{{ prepared ? '重新核对外发清单' : '预览外发清单' }}</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div>
      <p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
      <section v-if="prepared && previewMatches" class="consent-panel">
        <AiManifest :manifest="prepared.manifest" :budget="prepared.budget" />
        <p v-if="overBudget" class="notice error">本次预留超过当前剩余预算，请核对未决费用或服务器预算后重新预览。</p>
        <AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || !configured || !materialReady">我已核对“{{ aiMaterialModeLabels[mode] }}”模式、完整问题和{{ mode === 'MATERIAL' ? '所选摘录' : '空摘录清单' }}，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，并为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice>
        <NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !consent || overBudget || !configured || !materialReady" @click="create">授权本次外发并提问</NButton>
        <p class="field-help">模式、问题、摘录或配置变化会清除本次预览与同意。连接结果未知时只恢复原请求；资料不足也会保存为一次完成的回答，不自动再次调用。</p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.material-qa-create { display: grid; gap: 23px; min-width: 0; padding: 25px 27px 30px; }
.create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; }
.create-heading > div { min-width: 0; } h2 { margin: 0 0 9px; font-size: 24px; text-wrap: balance; } h3 { margin: 0; font-size: 16px; }
h3 span { margin-left: 8px; font-size: 12px; font-weight: 400; color: var(--muted); }
.form-field, .source-selection { display: grid; gap: 12px; min-width: 0; } .form-field { color: var(--muted); font-size: 13px; }
.selected-excerpts { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.selected-excerpts li { display: flex; justify-content: space-between; align-items: start; gap: 15px; border-bottom: 1px solid var(--line); padding: 13px 0; }
.selected-excerpts li > div { display: grid; gap: 6px; min-width: 0; } .selected-excerpts strong, .selected-excerpts p { font-size: 13px; overflow-wrap: anywhere; }
.selected-excerpts small { color: var(--muted); font-size: 11px; overflow-wrap: anywhere; }
.empty-choice { padding: 20px 0; color: var(--muted); font-size: 13px; }
.consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); min-width: 0; }
.consent-panel > .n-button { justify-self: start; } .field-help { max-width: 75ch; }
@media (max-width: 700px) { .material-qa-create { padding: 22px 17px; } h2 { font-size: 21px; } .panel-heading, .selected-excerpts li { flex-wrap: wrap; } }
</style>
