<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { ItemDetail, ItemSummary } from '../../api/client'
import type { AiMaterialDraftPreviewRequest, AiPreparationDraftPreviewRequest, AiPreparationSelection, AiPreview, AiPreviewRequest, AiSettings, AiSource, AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDraftPromptVersion, aiExpressionSourceContent, aiMaterialDraftSourcesMatch, aiPreparationChecksMatch, aiPurposeLabels, aiSourceKindLabel, formatAiAmount, isAiPreparationKind } from '../../api/ai'
import type { MaterialExcerpt, MaterialStatus } from '../../api/material'
import type { PreparationItem } from '../../api/preparation'
import { expressionOptions, referenceLabels } from '../../api/preparation'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import KnowledgePicker from '../KnowledgePicker.vue'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'
import AiPreparationSourcePicker from './AiPreparationSourcePicker.vue'
import MaterialExcerptPicker from '../material/MaterialExcerptPicker.vue'

const props = defineProps<{ settings?: AiSettings; disabled?: boolean; initialPurpose?: AiPreviewRequest['purpose']; initialPreparation?: { itemId: string; versionId: string } }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore()
const writer = useCommand()
// 路由只提供初始用途；后台刷新和查询参数不能覆盖用户已选择的任务范围。
const initialPurpose = props.initialPurpose === 'QUESTION_DRAFT' ? 'QUESTION_DRAFT' : 'KNOWLEDGE_DRAFT'
const purpose = ref<AiPreviewRequest['purpose']>(initialPurpose)
const purposeOptions = (['KNOWLEDGE_DRAFT', 'QUESTION_DRAFT'] as const).map(value => ({ value, label: aiPurposeLabels[value] }))
const isQuestion = computed(() => purpose.value === 'QUESTION_DRAFT')
const instruction = ref('')
const sources = ref<AiSource[]>([])
const preparationSources = ref<AiPreparationSelection[]>([])
const excerpts = ref<MaterialExcerpt[]>([])
const selectedSources = computed(() => [...sources.value, ...preparationSources.value])
const selectedIds = computed(() => selectedSources.value.map(source => source.itemId))
const sourceCount = computed(() => selectedSources.value.length + excerpts.value.length)
const picker = ref<'KNOWLEDGE' | 'PREPARATION' | 'MATERIAL' | null>(null)
const pickerGeneration = ref(0)
const selecting = ref(false)
const preparationSelecting = ref(false)
const selectionBusy = computed(() => selecting.value || preparationSelecting.value)
const selectionError = ref('')
const previewing = ref(false)
const previewError = ref('')
const prepared = ref<AiPreview>()
const preparedRequest = ref('')
const preparedCommandPath = ref('')
const consent = ref(false)
const authorization = ref<AiAuthorizationReference>()
const deferred = shallowRef<AiTask>()
const createdResult = shallowRef<AiTask>()
const receiptMismatch = ref(false)
const message = ref('')
const dirty = computed(() => !createdResult.value && Boolean(instruction.value || sourceCount.value || purpose.value !== initialPurpose))
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !store.sessionActive || Boolean(props.disabled) || locked.value || Boolean(createdResult.value) || receiptMismatch.value)
const configured = computed(() => props.settings?.configuration.enabled && props.settings.configuration.configured)
const materialStatus = useQuery({
  queryKey: computed(() => ['material', store.sessionEpoch, 'status']),
  enabled: computed(() => store.sessionActive && !props.disabled && (picker.value === 'MATERIAL' || excerpts.value.length > 0)),
  queryFn: () => read<MaterialStatus>('/materials/status'),
})
const materialReady = computed(() => !excerpts.value.length || materialStatus.data.value?.enabled === true)
const initialPreparation = useQuery({
  queryKey: computed(() => ['preparation', store.sessionEpoch, 'ai-initial-source', props.initialPreparation?.itemId, props.initialPreparation?.versionId]),
  enabled: computed(() => store.sessionActive && !props.disabled && Boolean(props.initialPreparation)),
  queryFn: () => read<PreparationItem>(`/prepare/items/${props.initialPreparation!.itemId}`),
})
watch(initialPreparation.error, error => { if (props.initialPreparation && error) selectionError.value = errorText(error) })
watch(initialPreparation.data, value => {
  const target = props.initialPreparation
  if (!target || !value || value.status !== 'ACTIVE' || value.currentVersion.id !== target.versionId
    || !(value.kind === 'TARGET' || value.kind === 'PROJECT' || value.kind === 'FACT')) {
    if (target && value) selectionError.value = '预选准备材料已改版、归档或类型不符，请重新选择来源。'
    return
  }
  if (selectedIds.value.includes(value.id)) return
  if (sourceCount.value >= 5) { selectionError.value = '预选材料加入后超过 5 份来源上限，请先移出其他来源。'; return }
  preparationSources.value.push({ kind: value.kind, itemId: value.id, versionId: value.currentVersion.id,
    title: value.currentVersion.content.title, number: value.currentVersion.number })
}, { immediate: true })
type CandidateRequest = AiPreviewRequest | AiPreparationDraftPreviewRequest | AiMaterialDraftPreviewRequest
const request = computed<CandidateRequest>(() => {
  const knowledge = { purpose: purpose.value, instruction: instruction.value.trim(), sources: sources.value.map(source => ({ itemId: source.itemId, versionId: source.versionId })) }
  const preparation = preparationSources.value.map(({ kind, itemId, versionId }) => ({ kind, itemId, versionId }))
  // 只有显式选入摘录才使用第三条路径；原知识／准备请求不追加空字段，保留原键摘要。
  if (excerpts.value.length) return { ...knowledge, preparationSources: preparation, excerptIds: excerpts.value.map(source => source.id) }
  return preparation.length ? { ...knowledge, preparationSources: preparation } : knowledge
})
const requestSignature = computed(() => JSON.stringify(request.value))
const previewMatches = computed(() => Boolean(prepared.value && preparedCommandPath.value && preparedRequest.value === requestSignature.value))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const problem = computed(() => !sourceCount.value || sourceCount.value > 5 ? '请选择合计 1–5 份知识、准备版本或附件摘录。' : instruction.value.trim().length > 1000 ? '生成要求最多 1000 个字符。' : instruction.value.includes('\0') ? '生成要求不能包含空字符。' : '')
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let alive = true
let selectionRequest = 0
let previewRequest = 0
let commandEpoch = store.sessionEpoch

watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed
  commandState.locked = waiting
  store.changes.set('ai-create', changeState)
  store.changes.set('ai-command', commandState)
}, { immediate: true, flush: 'sync' })
watch([requestSignature, instruction], invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(props.settings?.configuration), (value, previous) => { if (value !== previous) invalidatePreview() }, { flush: 'sync' })
watch(() => materialStatus.data.value?.enabled, () => { if (excerpts.value.length) invalidatePreview() }, { flush: 'sync' })
watch([() => store.sessionEpoch, () => props.disabled, () => store.sessionActive], () => {
  selectionRequest++
  selecting.value = false
  preparationSelecting.value = false
  pickerGeneration.value++
  picker.value = null
  invalidatePreview()
}, { flush: 'sync' })
watch(picker, () => { selectionRequest++; selecting.value = false; preparationSelecting.value = false; selectionError.value = '' }, { flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['AI_CONFIG_CHANGED', 'AI_MANIFEST_CHANGED', 'AI_SOURCE_CHANGED', 'AI_DISABLED', 'AI_NOT_CONFIGURED', 'AI_BUDGET_EXCEEDED'].includes(error.code)) {
    invalidatePreview()
    emit('refresh')
    if (excerpts.value.length && store.sessionActive && !props.disabled) void materialStatus.refetch()
  }
})
onUnmounted(() => {
  alive = false
  selectionRequest++
  previewRequest++
  if (store.changes.get('ai-create') === changeState) store.changes.delete('ai-create')
  if (store.changes.get('ai-command') === commandState) store.changes.delete('ai-command')
})

function invalidatePreview() {
  previewRequest++
  previewing.value = false
  prepared.value = undefined
  preparedRequest.value = ''
  preparedCommandPath.value = ''
  consent.value = false
}
async function choose(summary: ItemSummary) {
  if (blocked.value || previewing.value || picker.value !== 'KNOWLEDGE' || selectionBusy.value || sourceCount.value >= 5 || selectedIds.value.includes(summary.id)) return
  const epoch = store.sessionEpoch
  const generation = ++selectionRequest
  selecting.value = true
  selectionError.value = ''
  try {
    const value = await read<ItemDetail>(`/knowledge/items/${summary.id}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== selectionRequest || picker.value !== 'KNOWLEDGE') return
    // 目录摘要和未发布草稿都不能代替外发版本；只取确切发布正文。
    if (value.id !== summary.id || value.kind !== summary.kind || value.status !== 'ACTIVE' || value.published.id !== value.currentVersionId || !value.published.body.trim()) {
      selectionError.value = '该知识没有可用的当前发布正文，请核对发布状态后再选择。'
      return
    }
    if (sourceCount.value >= 5 || selectedIds.value.includes(value.id)) return
    sources.value.push({ itemId: value.id, versionId: value.published.id, number: value.published.number, kind: value.kind, title: value.published.title, body: value.published.body })
    message.value = ''
  } catch (error) { if (alive && generation === selectionRequest && epoch === store.sessionEpoch) selectionError.value = errorText(error) }
  finally { if (alive && generation === selectionRequest) selecting.value = false }
}
function choosePreparation(source: AiPreparationSelection) {
  if (blocked.value || previewing.value || picker.value !== 'PREPARATION' || sourceCount.value >= 5 || selectedIds.value.includes(source.itemId)) return
  if (source.kind === 'EXPRESSION' && !source.expression) { selectionError.value = '表达的正文或依据核对信息缺失，请重新选择。'; return }
  preparationSources.value.push(source)
  message.value = ''
}
function chooseExcerpt(source: MaterialExcerpt) {
  if (blocked.value || previewing.value || picker.value !== 'MATERIAL' || materialStatus.data.value?.enabled !== true
    || sourceCount.value >= 5 || excerpts.value.some(value => value.id === source.id)) return
  excerpts.value.push(source)
  message.value = ''
}
function removeExcerpt(id: string) { if (!blocked.value && !previewing.value) excerpts.value = excerpts.value.filter(source => source.id !== id) }
function remove(id: string) {
  if (blocked.value || previewing.value) return
  sources.value = sources.value.filter(source => source.itemId !== id)
  preparationSources.value = preparationSources.value.filter(source => source.itemId !== id)
}
function matchesSelection(manifest: AiPreview['manifest'], snapshot: CandidateRequest): boolean {
  const requestedPreparation = 'preparationSources' in snapshot ? snapshot.preparationSources : []
  const requestedExcerpts = 'excerptIds' in snapshot ? snapshot.excerptIds : []
  const materialSources = manifest.materialDraftSources ?? []
  if (manifest.purpose !== snapshot.purpose || manifest.instruction !== snapshot.instruction
    || manifest.promptVersion !== aiDraftPromptVersion(manifest)
    || manifest.sources.length !== snapshot.sources.length + requestedPreparation.length
    || materialSources.length !== requestedExcerpts.length || !aiMaterialDraftSourcesMatch(manifest, true)
    || !aiPreparationChecksMatch(manifest) || manifest.training != null || manifest.evaluation != null
    || manifest.contextChecks != null || manifest.materialQa != null || manifest.expressionType != null
    || manifest.preparationPlan != null || manifest.trainingDraft != null) return false
  // 三组身份分别逐项核对；摘录仍用独立身份，不能混入知识／准备的对象版本映射。
  return snapshot.sources.every((link, index) => {
    const source = manifest.sources[index]
    const selected = sources.value[index]
    if (!source || !selected) return false
    return source.itemId === link.itemId && source.versionId === link.versionId
      && (source.kind === 'NOTE' || source.kind === 'CONCEPT') && source.kind === selected.kind
      && source.title === selected.title && source.number === selected.number && source.body === selected.body
  }) && requestedPreparation.every((link, index) => {
    const source = manifest.sources[snapshot.sources.length + index]
    const selected = preparationSources.value[index]
    if (!source || !selected) return false
    if (source.itemId !== link.itemId || source.versionId !== link.versionId || source.kind !== link.kind
      || !isAiPreparationKind(source.kind) || source.title !== selected.title || source.number !== selected.number) return false
    if (source.kind !== 'EXPRESSION') return true
    const expression = selected.expression
    const projection = aiExpressionSourceContent(source)
    const checks = manifest.expressionChecks?.find(check => check.expression.itemId === source.itemId && check.expression.versionId === source.versionId)
    // 表达正文和依据的原顺序都须对应用户所选版本；未选中的事实不能成为模型输入。
    return Boolean(expression && projection && checks && projection.type === expression.type && projection.body === expression.body
      && checks.factChecks.length === expression.facts.length
      && checks.factChecks.every((check, factIndex) => check.itemId === expression.facts[factIndex]?.itemId && check.versionId === expression.facts[factIndex]?.versionId))
  }) && requestedExcerpts.every((id, index) => {
    const source = materialSources[index]
    const selected = excerpts.value[index]
    if (!source || !selected || source.excerptId !== id || selected.id !== id) return false
    return source.attachmentId === selected.attachmentId && source.origin === selected.origin && source.runId === selected.runId
      && source.segmentId === selected.segmentId && source.locator === selected.locator && source.text === selected.text
      && source.startOffset === selected.startOffset && source.endOffset === selected.endOffset
  })
}
async function prepare() {
  if (blocked.value || !configured.value || !materialReady.value || problem.value || selectionBusy.value || previewing.value) return
  invalidatePreview()
  const epoch = store.sessionEpoch
  const generation = ++previewRequest
  const signature = requestSignature.value
  const snapshot = JSON.parse(signature) as CandidateRequest
  const containsPreparation = 'preparationSources' in snapshot
  const containsMaterials = 'excerptIds' in snapshot
  previewing.value = true
  previewError.value = ''
  message.value = ''
  try {
    const value = await preview<AiPreview>(containsMaterials ? '/ai/material-drafts/preview' : containsPreparation ? '/ai/preparation-drafts/preview' : '/ai/preview', snapshot)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== previewRequest || signature !== requestSignature.value) return
    if (!matchesSelection(value.manifest, snapshot)) { previewError.value = '服务器预览与用途、所选版本、摘录正文、来源核对或生成要求不一致，请重新核对。'; return }
    prepared.value = value
    preparedRequest.value = signature
    preparedCommandPath.value = containsMaterials ? '/ai/material-drafts/tasks' : containsPreparation ? '/ai/preparation-drafts/tasks' : '/ai/tasks'
  } catch (error) { if (alive && generation === previewRequest && epoch === store.sessionEpoch) previewError.value = errorText(error) }
  finally { if (alive && generation === previewRequest) previewing.value = false }
}
function receive(value: AiTask) {
  receiptMismatch.value = false
  createdResult.value = value
  instruction.value = ''
  sources.value = []
  preparationSources.value = []
  excerpts.value = []
  picker.value = null
  changeState.dirty = false
  commandState.locked = false
  message.value = '任务已保存并授权一次尝试，可以从记录继续查看结果和费用。'
  emit('refresh')
  emit('created', value)
}
function create() {
  if (blocked.value || !configured.value || !materialReady.value || !previewMatches.value || !prepared.value || !consent.value || overBudget.value || selectionBusy.value || problem.value) return
  selectionRequest++
  picker.value = null
  commandEpoch = store.sessionEpoch
  const snapshot = JSON.parse(preparedRequest.value) as CandidateRequest
  const authorizedFingerprint = aiCandidateManifestFingerprint(prepared.value.manifest)
  void writer.run<AiTask>(preparedCommandPath.value, 'POST', { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }, value => {
    if (!alive) return
    let receiptMatches: boolean
    try {
      receiptMatches = value.task.purpose === snapshot.purpose && matchesSelection(value.manifest, snapshot)
        && aiCandidateManifestFingerprint(value.manifest) === authorizedFingerprint
    } catch { receiptMatches = false }
    // 成功响应可能已创建任务；清单异常时保留输入并阻止换键重建。
    if (!receiptMatches) {
      receiptMismatch.value = true
      emit('refresh')
      throw new ApiError(502, 'AI_TASK_MISMATCH', '任务回执与已授权的正文、依据状态、配置或费用不一致，原请求继续保留，可用原请求重试或从任务记录核对。')
    }
    if (!store.sessionActive || commandEpoch !== store.sessionEpoch || props.disabled) deferred.value = value
    else receive(value)
  })
}
function retryCommand() {
  if (!store.sessionActive || props.disabled || writer.busy.value) return
  commandEpoch = store.sessionEpoch
  void writer.retry()
}
function receiveDeferred() { if (store.sessionActive && !props.disabled && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="ai-create" aria-label="创建 AI 候选任务">
    <header class="create-heading"><div><p class="section-label">新建 AI 任务</p><h2>{{ isQuestion ? '从所选资料生成一道题' : '从所选资料整理知识候选' }}</h2><p class="field-help">选择知识、准备版本或已保存摘录，先预览，再授权本次外发。候选保留在任务记录中，由你审阅。</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!configured" class="notice">{{ settings?.configuration.enabled ? '第三方 API 尚未配置完整。服务器配置就绪后可预览外发清单。' : 'AI 当前未启用。可保留知识记录，启用后再创建候选任务。' }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="createdResult" class="notice"><NButton text @click="emit('created', createdResult)">打开已创建任务</NButton></p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!store.sessionActive || !!disabled" @click="retryCommand">用原请求重试</NButton></p><p v-if="deferred" class="notice" role="status">任务创建已由服务器确认，但登录或资料访问条件发生变化。<NButton text :disabled="!store.sessionActive || !!disabled" @click="receiveDeferred">接收已创建任务</NButton></p>
    <template v-if="!createdResult">
      <label class="instruction-field">任务用途<NSelect v-model:value="purpose" :options="purposeOptions" :disabled="blocked || previewing" aria-label="任务用途" /><span class="field-help">{{ isQuestion ? '每次生成一道候选题及个人参考。审阅改写后才能存入题库，再通过现有训练入口选用。' : '整理为带原文引用的知识候选，审阅后可创建知识或追加版本。' }}</span></label>
      <section class="source-selection">
        <header class="panel-heading"><h3>选择本次依据 <span>{{ sourceCount }} / 5</span></h3><div class="row-actions"><NButton size="small" :disabled="blocked || previewing || (sourceCount >= 5 && picker !== 'KNOWLEDGE')" :aria-expanded="picker === 'KNOWLEDGE'" @click="picker = picker === 'KNOWLEDGE' ? null : 'KNOWLEDGE'">{{ picker === 'KNOWLEDGE' ? '收起知识选择' : '添加知识' }}</NButton><NButton size="small" :disabled="blocked || previewing || (sourceCount >= 5 && picker !== 'PREPARATION')" :aria-expanded="picker === 'PREPARATION'" @click="picker = picker === 'PREPARATION' ? null : 'PREPARATION'">{{ picker === 'PREPARATION' ? '收起准备选择' : '添加准备来源' }}</NButton><NButton size="small" :disabled="blocked || previewing || (sourceCount >= 5 && picker !== 'MATERIAL')" :aria-expanded="picker === 'MATERIAL'" @click="picker = picker === 'MATERIAL' ? null : 'MATERIAL'">{{ picker === 'MATERIAL' ? '收起摘录选择' : '添加附件摘录' }}</NButton></div></header>
        <p class="field-help">可混选知识、目标与 JD、项目概况、用户确认的事实、已有表达及已保存摘录，合计最多 5 份。切换选择器保留已选内容；同一附件的不同摘录分别计数，检索命中需逐条选择。</p>
        <ol v-if="selectedSources.length" class="selected-sources"><li v-for="source in selectedSources" :key="source.itemId"><div><strong>{{ source.title }}</strong><span>{{ aiSourceKindLabel(source.kind) }} · v{{ source.number }}{{ 'body' in source ? ` · ${source.body.length} 字符` : ' · 外发正文待预览核对' }}</span></div><NButton size="small" quaternary :disabled="blocked || previewing" @click="remove(source.itemId)">移出本次选择</NButton></li></ol>
        <details v-for="source in preparationSources.filter(value => value.kind === 'EXPRESSION' && value.expression)" :key="`basis-${source.itemId}`" class="selected-expression"><summary>{{ source.title }} · {{ expressionOptions.find(option => option.value === source.expression?.type)?.label }} · {{ source.expression?.facts.length }} 项事实依据</summary><p class="field-help">表达是已保存的写作素材，保存不表示已经逐句核实。以下状态仅表示选择时的核对结果；预览和发送前会再次核对。关联事实只参与授权核对，原文需另行选择才会外发并计入来源数。</p><ul><li v-for="fact in source.expression?.facts" :key="fact.versionId"><RouterLink class="text-link" :to="{ name: 'prepare', query: { kind: 'FACT', item: fact.itemId, version: fact.versionId } }">{{ fact.title }} · v{{ fact.number }}</RouterLink><span>{{ referenceLabels[fact.state] }}</span></li></ul></details>
        <ol v-if="excerpts.length" class="selected-sources" aria-label="本次选择的附件摘录"><li v-for="source in excerpts" :key="source.id"><div><strong>{{ source.locator }}</strong><span>{{ source.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ source.text.length }} 字符 · 附件 {{ source.attachmentId.slice(0, 8) }}</span><p class="excerpt-summary">{{ source.text.slice(0, 160) }}{{ source.text.length > 160 ? '…' : '' }}</p></div><NButton size="small" quaternary :disabled="blocked || previewing" @click="removeExcerpt(source.id)">移出本次选择</NButton></li></ol>
        <p v-if="!sourceCount" class="empty-choice">先选择一份知识、准备资料或保存的摘录，作为本次候选的依据。</p>
        <KnowledgePicker v-if="picker === 'KNOWLEDGE'" :key="pickerGeneration" :disabled="blocked || previewing || selecting || sourceCount >= 5" :exclude-ids="selectedIds" @choose="choose" />
        <AiPreparationSourcePicker v-else-if="picker === 'PREPARATION'" :key="pickerGeneration" :disabled="blocked || previewing || sourceCount >= 5" :exclude-ids="selectedIds" @busy="preparationSelecting = $event" @choose="choosePreparation" />
        <template v-if="picker === 'MATERIAL' || excerpts.length">
          <p v-if="materialStatus.isPending.value" class="field-help" role="status">正在读取附件资料可用状态…</p>
          <p v-if="materialStatus.error.value" class="notice error" role="alert">{{ errorText(materialStatus.error.value) }}</p>
          <p v-else-if="materialStatus.data.value && !materialStatus.data.value.enabled" class="notice">附件资料当前未启用。已有摘录选择继续保留，暂不能授权含摘录的新任务；可以移出摘录后使用其他来源。</p>
          <div class="row-actions"><NButton text size="small" :loading="materialStatus.isFetching.value" :disabled="blocked" @click="materialStatus.refetch()">重新读取资料状态</NButton></div>
          <MaterialExcerptPicker v-if="picker === 'MATERIAL' && materialStatus.data.value?.enabled" :key="pickerGeneration" search-excerpts for-ai :disabled="blocked || previewing || sourceCount >= 5" :exclude-ids="excerpts.map(source => source.id)" @choose="chooseExcerpt" />
          <p v-if="excerpts.length" class="field-help">预览只发送这些确切摘录及其他已选来源，不附带整份原件、附件说明或其他解析片段。手工摘录不等于自动解析或已核实事实。</p>
        </template>
        <p v-if="selecting" class="field-help" role="status">正在读取确切发布版本…</p><p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p>
      </section>
      <label class="instruction-field">{{ isQuestion ? '出题要求' : '整理要求' }} <NInput v-model:value="instruction" :disabled="blocked || previewing" type="textarea" :maxlength="1000" :autosize="{ minRows: 4, maxRows: 10 }" :placeholder="isQuestion ? '例如：围绕缓存更新失败出一道排错题，说明假设场景和考察维度，附待核实的个人参考。' : '例如：整理事务隔离的适用条件、易混淆点和需要继续核实的问题，并引用原文。'" /><span class="field-help">可留空。最多 1000 字符；你手工输入的私人文字也会发送。来源正文和要求的总量由服务器校验，不会静默截断。</span></label>
      <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || !materialReady || !!problem || selectionBusy" @click="prepare">{{ prepared ? '重新核对外发清单' : '预览外发清单' }}</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div><p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
      <section v-if="prepared && previewMatches" class="consent-panel"><AiManifest :manifest="prepared.manifest" :budget="prepared.budget" /><p v-if="overBudget" class="notice error">本次预留超过当前剩余预算。请先处理未决费用或调整服务器预算，再重新预览。</p><AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || !materialReady || overBudget || selectionBusy">我已核对上方完整正文和{{ aiPurposeLabels[purpose] }}用途，同意将这些确切版本、摘录与生成要求发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，并为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice><NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !materialReady || !consent || overBudget || selectionBusy" @click="create">授权本次外发并创建任务</NButton><p class="field-help">更改用途、来源、要求或配置会废弃本次预览与勾选；服务器发现来源或表达依据变化时也会拒绝旧授权。连接结果未知时保留原命令，使用“用原请求重试”恢复，不重复创建任务。</p></section>
    </template>
  </section>
</template>

<style scoped>
.ai-create { display: grid; gap: 23px; padding: 25px 27px 30px; min-width: 0; } .create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; } .create-heading > div { min-width: 0; } h2 { margin: 7px 0 9px; font-size: 24px; } h3 { margin: 0; font-size: 16px; } h3 span { color: var(--muted); font-size: 12px; font-weight: 400; margin-left: 8px; }
.source-selection { display: grid; gap: 13px; } .selected-sources { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; } .selected-sources li { display: flex; justify-content: space-between; align-items: center; gap: 15px; border-bottom: 1px solid var(--line); padding: 11px 0; } .selected-sources li > div { display: grid; gap: 5px; min-width: 0; } .selected-sources strong { font-size: 13px; overflow-wrap: anywhere; } .selected-sources span { font-size: 12px; color: var(--muted); } .empty-choice { padding: 24px 16px; text-align: center; color: var(--muted); font-size: 13px; background: var(--surface-soft); border: 1px dashed var(--line); border-radius: 6px; }
.instruction-field { display: grid; gap: 9px; font-size: 13px; color: var(--muted); } .consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); } .consent-panel > .n-button { justify-self: start; }
.excerpt-summary { margin: 3px 0 0; color: var(--muted); font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; } .source-selection > .panel-heading { flex-wrap: wrap; }
.selected-expression { padding: 12px; border: 1px solid var(--line); border-radius: 5px; font-size: 12px; overflow-wrap: anywhere; } .selected-expression summary { cursor: pointer; } .selected-expression[open] summary { margin-bottom: 10px; } .selected-expression ul { display: grid; gap: 8px; margin: 10px 0 0; padding-left: 18px; } .selected-expression li > span { display: block; margin-top: 3px; color: var(--muted); }
@media (max-width: 700px) { .ai-create { padding: 22px 17px; } h2 { font-size: 21px; } .source-selection > .panel-heading { flex-wrap: wrap; } .selected-sources li { align-items: start; flex-wrap: wrap; } }
</style>
