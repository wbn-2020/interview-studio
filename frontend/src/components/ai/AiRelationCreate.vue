<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NInput } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { ItemDetail, ItemSummary } from '../../api/client'
import type { AiCreateRelationDraft, AiPreview, AiRelationDraftPreviewRequest, AiSettings, AiSource, AiTask } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiPreparationPlanTextValid, aiRelationManifestMatches, aiSourceKindLabel, aiSourceRoute, formatAiAmount } from '../../api/ai'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import KnowledgePicker from '../KnowledgePicker.vue'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

const props = defineProps<{ settings?: AiSettings; disabled?: boolean }>()
const emit = defineEmits<{ created: [task: AiTask]; close: []; refresh: [] }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const writer = useCommand()
const instruction = ref('')
const sources = ref<AiSource[]>([])
const selectedIds = computed(() => sources.value.map(source => source.itemId))
const conceptCount = computed(() => sources.value.filter(source => source.kind === 'CONCEPT').length)
const picker = ref<'CONCEPT' | 'NOTE' | null>(null)
const pickerGeneration = ref(0)
const selecting = ref(false)
const selectionError = ref('')
const previewing = ref(false)
const previewError = ref('')
const prepared = shallowRef<AiPreview>()
const preparedRequest = ref('')
const consent = ref(false)
const authorization = ref<AiAuthorizationReference>()
const deferred = shallowRef<AiTask>()
const createdResult = shallowRef<AiTask>()
const receiptMismatch = ref(false)
const message = ref('')
const readable = computed(() => store.sessionActive && !props.disabled && !access.checking && !access.restricted && !access.challenge)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || Boolean(createdResult.value) || receiptMismatch.value)
const dirty = computed(() => !createdResult.value && Boolean(instruction.value || sources.value.length))
const configured = computed(() => Boolean(props.settings?.configuration.enabled && props.settings.configuration.configured))
const request = computed<AiRelationDraftPreviewRequest>(() => ({ instruction: instruction.value.trim(),
  sources: sources.value.map(source => ({ itemId: source.itemId, versionId: source.versionId })) }))
const requestSignature = computed(() => JSON.stringify(request.value))
const previewMatches = computed(() => Boolean(prepared.value && preparedRequest.value === requestSignature.value))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const problem = computed(() => {
  if (sources.value.length < 2 || sources.value.length > 5) return '请选择 2–5 个知识版本，其中至少两个为概念。'
  if (conceptCount.value < 2) return '至少需要两个概念作为关系端点；笔记只补充依据。'
  if (!aiPreparationPlanTextValid(instruction.value, 1000)) return '关系整理要求最多 1000 个 UTF-16 码元，不能含 NUL 或不完整字符。'
  return ''
})
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
watch(() => JSON.stringify(props.settings?.configuration), invalidatePreview, { flush: 'sync' })
watch([() => store.sessionEpoch, readable], () => {
  selectionRequest++
  selecting.value = false
  pickerGeneration.value++
  picker.value = null
  invalidatePreview()
}, { flush: 'sync' })
watch(picker, () => { selectionRequest++; selecting.value = false; selectionError.value = '' }, { flush: 'sync' })
watch(writer.error, error => {
  if (error instanceof ApiError && ['AI_CONFIG_CHANGED', 'AI_MANIFEST_CHANGED', 'AI_SOURCE_CHANGED', 'AI_SOURCE_UNAVAILABLE', 'AI_DISABLED', 'AI_NOT_CONFIGURED', 'AI_BUDGET_EXCEEDED'].includes(error.code)) {
    invalidatePreview()
    emit('refresh')
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
  consent.value = false
}
async function choose(summary: ItemSummary) {
  if (blocked.value || previewing.value || selecting.value || !picker.value || summary.kind !== picker.value
    || summary.status !== 'ACTIVE' || sources.value.length >= 5 || selectedIds.value.includes(summary.id)) return
  const epoch = store.sessionEpoch
  const generation = ++selectionRequest
  const selectedKind = picker.value
  selecting.value = true
  selectionError.value = ''
  try {
    const value = await read<ItemDetail>(`/knowledge/items/${encodeURIComponent(summary.id)}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== selectionRequest || picker.value !== selectedKind) return
    // 列表只用来点选；固定当前已发布正文，不能把草稿或列表读取后新增的版本悄悄替换进来。
    const published = value.published
    const uuid = (id: string) => typeof id === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)
    if (value.id !== summary.id || value.kind !== selectedKind || value.status !== 'ACTIVE' || !published
      || !uuid(value.id) || !uuid(published.id) || published.id !== value.currentVersionId
      || published.number !== summary.versionNumber || published.number !== value.versionNumber
      || !Number.isSafeInteger(published.number) || published.number < 1
      || !aiPreparationPlanTextValid(published.title, 200) || !published.title.trim()
      || !aiPreparationPlanTextValid(published.body, 100000) || !published.body.trim()) {
      selectionError.value = '该知识的发布版本已变化，或没有有效的发布正文。请刷新选择范围后重新核对。'
      return
    }
    if (sources.value.length >= 5 || selectedIds.value.includes(value.id)) return
    sources.value.push({ itemId: value.id, versionId: published.id, number: published.number, kind: value.kind, title: published.title, body: published.body })
    message.value = ''
  } catch (error) { if (alive && generation === selectionRequest && epoch === store.sessionEpoch) selectionError.value = errorText(error) }
  finally { if (alive && generation === selectionRequest) selecting.value = false }
}
function remove(itemId: string) { if (!blocked.value && !previewing.value) sources.value = sources.value.filter(source => source.itemId !== itemId) }
function refreshPicker() {
  if (blocked.value || previewing.value || selecting.value || !picker.value) return
  void queryClient.invalidateQueries({ queryKey: ['knowledge', store.sessionEpoch, 'picker', picker.value] })
  pickerGeneration.value++
  selectionError.value = ''
}
function matchesSelection(manifest: AiPreview['manifest'], snapshot: AiRelationDraftPreviewRequest, requireChecks = false): boolean {
  if (!aiRelationManifestMatches(manifest, requireChecks) || manifest.instruction !== snapshot.instruction || manifest.sources.length !== snapshot.sources.length) return false
  return snapshot.sources.every((link, index) => {
    const source = manifest.sources[index]
    const selected = sources.value[index]
    return Boolean(source && selected && source.itemId === link.itemId && source.versionId === link.versionId
      && source.kind === selected.kind && source.number === selected.number && source.title === selected.title && source.body === selected.body)
  })
}
async function prepare() {
  if (blocked.value || !configured.value || problem.value || selecting.value || previewing.value) return
  invalidatePreview()
  const epoch = store.sessionEpoch
  const generation = ++previewRequest
  const signature = requestSignature.value
  const snapshot = JSON.parse(signature) as AiRelationDraftPreviewRequest
  previewing.value = true
  previewError.value = ''
  message.value = ''
  try {
    const value = await preview<AiPreview>('/ai/relation-drafts/preview', snapshot)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== previewRequest || signature !== requestSignature.value) return
    if (!matchesSelection(value.manifest, snapshot, true)) {
      previewError.value = '关系建议预览与本次选择的版本、完整正文或要求不一致，请重新核对。'
      return
    }
    prepared.value = value
    preparedRequest.value = signature
  } catch (error) { if (alive && generation === previewRequest && epoch === store.sessionEpoch) previewError.value = errorText(error) }
  finally { if (alive && generation === previewRequest) previewing.value = false }
}
function receive(value: AiTask) {
  receiptMismatch.value = false
  createdResult.value = value
  instruction.value = ''
  sources.value = []
  picker.value = null
  changeState.dirty = false
  commandState.locked = false
  message.value = '关系建议任务已保存并授权一次尝试，可从记录逐条审阅候选。'
  emit('refresh')
  emit('created', value)
}
function create() {
  if (blocked.value || !configured.value || !previewMatches.value || !prepared.value || !aiRelationManifestMatches(prepared.value.manifest, true)
    || !consent.value || overBudget.value || selecting.value || problem.value) return
  picker.value = null
  commandEpoch = store.sessionEpoch
  const snapshot = JSON.parse(preparedRequest.value) as AiRelationDraftPreviewRequest
  const authorizedFingerprint = aiCandidateManifestFingerprint(prepared.value.manifest)
  const body: AiCreateRelationDraft = { ...snapshot, expectedManifestHash: prepared.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  void writer.run<AiTask>('/ai/relation-drafts/tasks', 'POST', body, value => {
    if (!alive) return
    let receiptMatches: boolean
    try {
      receiptMatches = value.task.purpose === 'RELATION_DRAFT' && matchesSelection(value.manifest, snapshot)
        && aiCandidateManifestFingerprint(value.manifest) === authorizedFingerprint
    } catch { receiptMatches = false }
    // 原键返回可能已经写入；回执不符时保留输入并阻止换键再建一个任务。
    if (!receiptMatches) {
      receiptMismatch.value = true
      emit('refresh')
      throw new ApiError(502, 'AI_TASK_MISMATCH', '任务回执与已授权的知识正文、配置或费用不一致，原请求继续保留，可用原请求重试或从任务记录核对。')
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch) deferred.value = value
    else receive(value)
  })
}
function retryCommand() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="relation-create" aria-label="生成知识关系建议">
    <header class="create-heading"><div><p class="section-label">新建 AI 任务</p><h2>从所选知识生成关系建议</h2><p class="field-help">先选择概念与依据，核对完整外发正文，再授权一次生成。候选由你逐条审阅后采用或忽略。</p></div><NButton text size="small" @click="emit('close')">关闭</NButton></header>
    <p v-if="!configured" class="notice">{{ settings?.configuration.enabled ? '第三方 API 尚未配置完整。关系整理要求和知识选择可以先保留在本页。' : 'AI 当前未启用，关系整理要求和知识选择可以先保留在本页。' }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="createdResult" class="notice"><NButton text @click="emit('created', createdResult)">打开已创建关系任务</NButton></p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retryCommand">用原请求重试</NButton></p>
    <p v-if="deferred" class="notice" role="status">服务器已确认创建，登录或资料访问条件变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收已创建关系任务</NButton></p>
    <template v-if="!createdResult && readable">
      <section class="source-selection">
        <header class="panel-heading"><h3>选择本次知识 <span>{{ sources.length }} / 5 · {{ conceptCount }} 个概念</span></h3><div class="row-actions"><NButton size="small" :disabled="blocked || previewing || (sources.length >= 5 && picker !== 'CONCEPT')" :aria-expanded="picker === 'CONCEPT'" @click="picker = picker === 'CONCEPT' ? null : 'CONCEPT'">{{ picker === 'CONCEPT' ? '收起概念选择' : '添加概念' }}</NButton><NButton size="small" :disabled="blocked || previewing || (sources.length >= 5 && picker !== 'NOTE')" :aria-expanded="picker === 'NOTE'" @click="picker = picker === 'NOTE' ? null : 'NOTE'">{{ picker === 'NOTE' ? '收起笔记选择' : '添加笔记依据' }}</NButton></div></header>
        <p class="field-help">选择 2–5 个当前已发布知识，至少两个为概念；笔记可补充依据。关系端点仅限所选概念，未选中的关联笔记、主题和现有关系不会自动加入。</p>
        <ol v-if="sources.length" class="selected-sources"><li v-for="source in sources" :key="source.itemId"><div><RouterLink class="text-link" :to="aiSourceRoute(source)!">{{ source.title }} · v{{ source.number }}</RouterLink><span>{{ aiSourceKindLabel(source.kind) }} · {{ source.body.length }} 字符{{ source.kind === 'NOTE' ? ' · 仅作依据' : ' · 可作为关系端点' }}</span></div><NButton size="small" quaternary :disabled="blocked || previewing" @click="remove(source.itemId)">移出本次选择</NButton></li></ol>
        <p v-else class="empty-choice">先添加两个概念，例如“事务隔离”与“可重复读”，再选择支持它们关系的笔记。</p>
        <template v-if="picker && readable">
          <KnowledgePicker :key="`${pickerGeneration}:${picker}`" :kind="picker" :disabled="blocked || previewing || selecting || sources.length >= 5" :exclude-ids="selectedIds" @choose="choose" />
          <NButton class="refresh-picker" text size="small" :disabled="blocked || previewing || selecting" @click="refreshPicker">刷新当前选择范围</NButton>
        </template>
        <p v-if="selecting" class="field-help" role="status">正在核对所选知识的确切发布版本…</p>
        <p v-if="selectionError" class="notice error" role="alert">{{ selectionError }}</p>
      </section>
      <label class="form-field">关系整理要求<NInput v-model:value="instruction" :disabled="blocked || previewing" type="textarea" :maxlength="1000" :autosize="{ minRows: 4, maxRows: 10 }" placeholder="例如：只依据所选知识说明前置、组成或应用关系，引用支持它们的原文；依据不足时不要补造连接。" /><span class="field-help">可留空。最多 1000 个 UTF-16 码元；手工填写的文字也会发送。正文与要求超出本次额度时由服务器拒绝，不会截断。</span></label>
      <p class="field-help">没有足够依据时可以得到零条建议。候选不会直接出现在星图中；你确认采用后才创建正式关系。</p>
      <div class="row-actions"><NButton :loading="previewing" :disabled="blocked || !configured || !!problem || selecting" @click="prepare">{{ prepared ? '重新核对外发清单' : '预览外发清单' }}</NButton><span v-if="problem" class="field-help">{{ problem }}</span></div>
      <p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
      <section v-if="prepared && previewMatches && readable" class="consent-panel"><AiManifest :manifest="prepared.manifest" :budget="prepared.budget" /><p v-if="overBudget" class="notice error">本次预留超过当前剩余预算。请处理未决费用或调整服务器预算，再重新预览。</p><AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || selecting">我已核对上方完整知识正文和关系建议用途，同意发送这些确切版本与整理要求给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，并为本次尝试预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice><NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !consent || overBudget || selecting" @click="create">授权本次外发并创建任务</NButton><p class="field-help">更改知识选择、要求或配置会撤销预览与同意。来源失效时保留本页输入，需重新核对；结果未知时使用原请求重试。当前未提交输入只保留在本页，刷新或离开不会保存为服务器草稿。</p></section>
    </template>
  </section>
</template>

<style scoped>
.relation-create { display: grid; gap: 23px; padding: 25px 27px 30px; min-width: 0; }
.create-heading, .panel-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; } .create-heading > div { min-width: 0; }
h2 { margin: 7px 0 9px; font-size: 24px; } h3 { margin: 0; font-size: 16px; } h3 span { color: var(--muted); font-size: 12px; font-weight: 400; margin-left: 8px; }
.source-selection { display: grid; gap: 13px; } .panel-heading { flex-wrap: wrap; } .selected-sources { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.selected-sources li { display: flex; justify-content: space-between; align-items: center; gap: 15px; padding: 11px 0; border-bottom: 1px solid var(--line); } .selected-sources li > div { display: grid; gap: 5px; min-width: 0; }
.selected-sources a { font-size: 13px; overflow-wrap: anywhere; } .selected-sources span { font-size: 12px; color: var(--muted); } .refresh-picker { justify-self: start; }
.empty-choice { padding: 24px 16px; text-align: center; color: var(--muted); font-size: 13px; background: var(--surface-soft); border: 1px dashed var(--line); border-radius: 6px; }
.form-field { display: grid; gap: 9px; font-size: 13px; color: var(--muted); } .consent-panel { display: grid; gap: 20px; padding-top: 22px; border-top: 1px solid var(--line); } .consent-panel > .n-button { justify-self: start; }
@media (max-width: 700px) { .relation-create { padding: 22px 17px; } h2 { font-size: 21px; } .selected-sources li { align-items: start; flex-wrap: wrap; } }
</style>
