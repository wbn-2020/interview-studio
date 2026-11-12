<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, preview, read } from '../../api/client'
import type { AiPreview, AiSettings, AiTask } from '../../api/ai'
import { formatAiAmount } from '../../api/ai'
import type { SearchSource } from '../../api/search'
import { searchDomains } from '../../api/search'
import type { AiSourceQaCreateRequest, AiSourceQaPreviewRequest, AiSourceQaSelection } from '../../api/aiSourceQa'
import { aiSourceQaFieldAllowed, aiSourceQaQuestion, aiSourceQaRangeValid, aiSourceQaRef, aiSourceQaRefKey, aiSourceQaRequestMatches, aiSourceQaRequestValid, aiSourceQaTaskValid, aiSourceQaTextValid } from '../../api/aiSourceQa'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import AiManifest from './AiManifest.vue'
import AiAuthorizationChoice from './AiAuthorizationChoice.vue'

const props = defineProps<{ source?: SearchSource; disabled?: boolean }>()
const emit = defineEmits<{ sourceRejected: [] }>()
const store = useWorkspaceStore(), writer = useCommand()
const question = ref(''), selections = ref<AiSourceQaSelection[]>([])
const display = shallowRef<Record<string, { title: string; fragments: { label: string; text: string }[] }>>({})
const fieldId = ref<string>(), fieldElement = ref<HTMLTextAreaElement>()
const prepared = shallowRef<AiPreview>(), preparedRequest = ref('')
const previewing = ref(false), previewError = ref(''), message = ref(''), createdId = ref('')
const consent = ref(false), authorization = ref<AiAuthorizationReference>()
const readable = computed(() => store.sessionActive && !props.disabled)
const blocked = computed(() => !readable.value || writer.locked.value || Boolean(createdId.value))
const settings = useQuery({ queryKey: computed(() => ['ai', store.sessionEpoch, 'settings']), enabled: readable,
  queryFn: () => read<AiSettings>('/ai/settings') })
const configured = computed(() => Boolean(settings.data.value?.configuration.enabled && settings.data.value?.configuration.configured))
const availableFields = computed(() => readable.value ? props.source?.fields.filter(field => aiSourceQaFieldAllowed(props.source!.item.domain, props.source!.item.kind, field.id)) ?? [] : [])
const field = computed(() => availableFields.value.find(value => value.id === fieldId.value))
const totalRanges = computed(() => selections.value.reduce((sum, selected) => sum + selected.ranges.length, 0))
const request = computed<AiSourceQaPreviewRequest>(() => ({ question: aiSourceQaQuestion(question.value), selections: selections.value }))
const signature = computed(() => JSON.stringify(request.value))
const dirty = computed(() => !createdId.value && Boolean(question.value || selections.value.length))
const valid = computed(() => aiSourceQaRequestValid(request.value))
const previewMatches = computed(() => Boolean(prepared.value && preparedRequest.value === signature.value))
const overBudget = computed(() => Boolean(prepared.value && prepared.value.manifest.reservedMicros > prepared.value.budget.remainingMicros))
const draftState = reactive({ dirty: false, locked: false }), commandState = reactive({ dirty: false, locked: false })
let alive = true, previewGeneration = 0, commandEpoch = store.sessionEpoch, commandAccess = currentTrainingAccessEpoch()

watch([dirty, writer.locked], ([changed, locked]) => {
  draftState.dirty = changed; commandState.locked = locked
  store.changes.set('search-qa-draft', draftState); store.changes.set('search-qa-command', commandState)
}, { immediate: true, flush: 'sync' })
watch(signature, invalidatePreview, { flush: 'sync' })
watch(() => JSON.stringify(settings.data.value?.configuration), invalidatePreview, { flush: 'sync' })
watch(() => props.source, () => { fieldId.value = undefined }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => {
  // 失权只保留固定引用、范围与用户问题；原文、来源标题和授权预览不能随恢复自动回填。
  display.value = {}; fieldId.value = undefined; invalidatePreview(); previewError.value = ''; message.value = ''
}, { flush: 'sync' })
watch(writer.error, error => {
  if (sourceReadRejected(error)) clearRejectedSource()
  else if (error instanceof ApiError && ['AI_CONFIG_CHANGED', 'AI_MANIFEST_CHANGED', 'AI_SOURCE_CHANGED', 'AI_DISABLED', 'AI_NOT_CONFIGURED', 'AI_BUDGET_EXCEEDED'].includes(error.code)) invalidatePreview()
})
onUnmounted(() => {
  alive = false; previewGeneration++
  if (store.changes.get('search-qa-draft') === draftState) store.changes.delete('search-qa-draft')
  if (store.changes.get('search-qa-command') === commandState) store.changes.delete('search-qa-command')
})
function invalidatePreview() {
  previewGeneration++; previewing.value = false; prepared.value = undefined; preparedRequest.value = ''; consent.value = false; authorization.value = undefined
}
function sourceReadRejected(error: unknown): boolean {
  return error instanceof ApiError && ([401, 403, 404].includes(error.status)
    || ['AI_EVALUATION_ACCESS_REQUIRED', 'AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED', 'AI_REVIEW_SOURCE_NOT_FOUND',
      'SEARCH_SOURCE_NOT_FOUND', 'RELATION_AI_FEEDBACK_SOURCE_NOT_FOUND', 'RELATION_AI_FEEDBACK_SOURCE_CHANGED',
      'SOURCE_QA_SOURCE_INVALID', 'AI_SOURCE_QA_BINDING_INVALID', 'AI_SOURCE_QA_RECEIPT_UNAVAILABLE', 'AI_TASK_MISMATCH', 'AI_PREVIEW_MISMATCH'].includes(error.code))
}
function clearRejectedSource() {
  // 专用原答许可拒读不一定改变全局资料状态；同时清父搜索原文，保留固定选择与原键。
  display.value = {}; fieldId.value = undefined; invalidatePreview()
  message.value = '来源读取权限或固定身份暂无法核实，已清除来源正文与预览。固定选择和问题保留，请恢复后主动核对。'
  emit('sourceRejected')
}
function addRange(whole = false) {
  if (blocked.value || !props.source || !field.value || !fieldElement.value) return
  const original = field.value.text, element = fieldElement.value
  // textarea 会把 CRLF 规范化为 LF；回到原字段坐标后再保存，不能把显示偏移直接当证据偏移。
  if (element.value !== original.replace(/\r\n?/g, '\n')) { message.value = '当前原文显示与来源不一致，请重新打开来源。'; return }
  const offset = (at: number) => {
    let raw = 0, displayed = 0
    while (displayed < at && raw < original.length) { raw += original[raw] === '\r' && original[raw + 1] === '\n' ? 2 : 1; displayed++ }
    return raw
  }
  const range = { field: field.value.id, start: whole ? 0 : offset(element.selectionStart), end: whole ? original.length : offset(element.selectionEnd) }
  if (!aiSourceQaRangeValid(range, original)) { message.value = '请选中 1–8000 个 UTF-16 码元的原文，不切开字符。含 NUL 的历史文字不适合本次外发，原记录仍保留。'; return }
  const ref = aiSourceQaRef(props.source.item), key = aiSourceQaRefKey(ref)
  const existing = selections.value.find(value => aiSourceQaRefKey(value.source) === key)
  if (totalRanges.value >= 8 || !existing && selections.value.length >= 5) { message.value = '每次最多 5 个固定来源、合计 8 段原文。'; return }
  if (existing?.ranges.some(value => value.field === range.field && value.start < range.end && range.start < value.end)) { message.value = '这段原文与已选范围重叠，请明确移除旧范围后重新选择。'; return }
  const next = { label: field.value.label, text: original.slice(range.start, range.end) }
  if (existing) {
    existing.ranges.push(range)
    const old = display.value[key]?.fragments ?? existing.ranges.slice(0, -1).map(() => ({ label: '', text: '' }))
    display.value = { ...display.value, [key]: { title: props.source.item.title, fragments: [...old, next] } }
  } else {
    selections.value.push({ source: ref, ranges: [range] })
    display.value = { ...display.value, [key]: { title: props.source.item.title, fragments: [next] } }
  }
  message.value = '已加入本次问答。可以继续搜索其他来源，最后统一预览全部外发内容。'
}
function remove(sourceIndex: number, rangeIndex: number) {
  if (blocked.value) return
  const selection = selections.value[sourceIndex]
  if (!selection) return
  const key = aiSourceQaRefKey(selection.source)
  selection.ranges.splice(rangeIndex, 1)
  const shown = display.value[key]
  if (shown) display.value = { ...display.value, [key]: { ...shown, fragments: shown.fragments.filter((_, index) => index !== rangeIndex) } }
  if (!selection.ranges.length) { selections.value.splice(sourceIndex, 1); const next = { ...display.value }; delete next[key]; display.value = next }
}
async function prepare() {
  if (blocked.value || !configured.value || !valid.value || previewing.value) return
  invalidatePreview(); previewError.value = ''; message.value = ''
  const generation = ++previewGeneration, epoch = store.sessionEpoch, access = currentTrainingAccessEpoch(), issuedSignature = signature.value
  const snapshot = JSON.parse(issuedSignature) as AiSourceQaPreviewRequest
  previewing.value = true
  try {
    const value = await preview<AiPreview>('/ai/source-qa/preview', snapshot)
    if (!alive || blocked.value || generation !== previewGeneration || epoch !== store.sessionEpoch || access !== currentTrainingAccessEpoch() || issuedSignature !== signature.value) return
    if (!/^[0-9a-f]{64}$/.test(value.manifestHash) || !aiSourceQaRequestMatches(value.manifest, snapshot)) throw new ApiError(502, 'AI_PREVIEW_MISMATCH', '完整来源清单与本次问题或选择不一致，请重新核对。')
    prepared.value = value; preparedRequest.value = issuedSignature
    display.value = Object.fromEntries(value.manifest.sourceQa!.sources.map(source => [aiSourceQaRefKey(source.ref), { title: source.title, fragments: source.fragments.map(fragment => ({ label: fragment.label, text: fragment.text })) }]))
  } catch (error) {
    if (alive && generation === previewGeneration && epoch === store.sessionEpoch && access === currentTrainingAccessEpoch() && readable.value) {
      if (sourceReadRejected(error)) clearRejectedSource()
      previewError.value = errorText(error)
    }
  }
  finally { if (alive && generation === previewGeneration) previewing.value = false }
}
function create() {
  if (blocked.value || !configured.value || !valid.value || !previewMatches.value || !prepared.value || !consent.value || overBudget.value) return
  commandEpoch = store.sessionEpoch; commandAccess = currentTrainingAccessEpoch()
  const snapshot = JSON.parse(preparedRequest.value) as AiSourceQaPreviewRequest, manifestHash = prepared.value.manifestHash
  const body: AiSourceQaCreateRequest = { ...snapshot, expectedManifestHash: manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }
  // 未决命令仅捕获引用／范围、用户问题和服务器摘要；不能持有含来源全文的清单或任务回执。
  void writer.run<AiTask>('/ai/source-qa/tasks', 'POST', body, value => {
    if (!alive || !readable.value || commandEpoch !== store.sessionEpoch || commandAccess !== currentTrainingAccessEpoch()) {
      throw new ApiError(0, 'AI_SOURCE_QA_ACCESS_CHANGED', '访问条件发生变化，未接收任务正文。恢复后请用原请求核对回执。')
    }
    if (!aiSourceQaTaskValid(value) || value.task.purpose !== 'SOURCE_QA' || value.sourceQaManifestHash !== manifestHash || !aiSourceQaRequestMatches(value.manifest, snapshot)) {
      throw new ApiError(502, 'AI_TASK_MISMATCH', '任务回执与原问题、固定来源或服务器摘要不一致，原请求继续保留。')
    }
    createdId.value = value.task.id; question.value = ''; selections.value = []; display.value = {}; invalidatePreview()
    message.value = '问答任务已保存，可打开任务查看 AI 未审回答、出处与费用。'
  })
}
function retryCommand() {
  if (!readable.value || writer.busy.value) return
  commandEpoch = store.sessionEpoch; commandAccess = currentTrainingAccessEpoch(); void writer.retry()
}
function startAnother() {
  if (!readable.value || writer.locked.value) return
  createdId.value = ''; message.value = ''; previewError.value = ''
}
const domainLabel = (selection: AiSourceQaSelection) => searchDomains.find(value => value.value === selection.source.domain)?.label
</script>

<template>
  <section class="source-qa-create" aria-label="向所选来源提问">
    <header><div><p class="section-label">依据你的原文</p><h2>向所选来源提问</h2><p class="field-help">先打开搜索命中的确切来源，主动选取原文，再跨领域组合一个问题。</p></div><span class="selection-count">{{ selections.length }} / 5 来源 · {{ totalRanges }} / 8 片段</span></header>
    <p v-if="!readable" class="notice">资料暂不可读，来源标题、原文与预览已清除。固定选择和你的问题保留；恢复后需主动重新预览。</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }} <NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable" @click="retryCommand">用原请求核对</NButton></p>
    <div v-if="createdId" class="row-actions"><RouterLink :to="{ name: 'ai', query: { item: createdId } }" class="text-link">打开已创建问答</RouterLink><NButton text :disabled="!readable" @click="startAnother">开始另一个问题</NButton></div>
    <template v-else>
      <section v-if="source && readable" class="field-picker">
        <h3>从当前来源选取 · {{ source.item.title }}</h3>
        <label>原文字段<NSelect v-model:value="fieldId" :options="availableFields.map(value => ({ label: value.label, value: value.id }))" :disabled="blocked" placeholder="明确选择一个原文字段" /></label>
        <template v-if="field"><label>拖选或用键盘选择原文<textarea ref="fieldElement" :value="field.text" readonly :disabled="blocked" rows="7" spellcheck="false" /></label>
          <div class="row-actions"><NButton :disabled="blocked || totalRanges >= 8" @click="addRange()">加入选中原文</NButton><NButton text :disabled="blocked || totalRanges >= 8 || !field.text.length || field.text.length > 8000 || !aiSourceQaTextValid(field.text, 8000)" @click="addRange(true)">选择整个字段</NButton></div>
          <p class="field-help">保留原字段位置和换行，单段最多 8000 个 UTF-16 码元。搜索摘要和未选择字段不加入问答。</p>
        </template>
      </section>
      <p v-else-if="readable" class="field-help">在上方打开来源后可选择原文字段。切换搜索条件不会清空本次选择。</p>
      <ol v-if="selections.length" class="selections"><li v-for="(selection, sourceIndex) in selections" :key="aiSourceQaRefKey(selection.source)">
        <h3>{{ sourceIndex + 1 }}. {{ display[aiSourceQaRefKey(selection.source)]?.title || domainLabel(selection) + ' · 标题待重新核对' }}</h3>
        <p class="identity">{{ domainLabel(selection) }} · {{ selection.source.id }} · {{ selection.source.versionId }}<span v-if="selection.source.revision !== null"> · r{{ selection.source.revision }}</span><span v-if="selection.source.feedbackId"> · {{ selection.source.feedbackId }} · {{ selection.source.reportHash }}</span></p>
        <div v-for="(range, rangeIndex) in selection.ranges" :key="rangeIndex" class="selected-range"><div><p class="field-help">{{ display[aiSourceQaRefKey(selection.source)]?.fragments[rangeIndex]?.label || range.field }} · [{{ range.start }}, {{ range.end }})</p><pre v-if="display[aiSourceQaRefKey(selection.source)]?.fragments[rangeIndex]?.text">{{ display[aiSourceQaRefKey(selection.source)]!.fragments[rangeIndex]!.text }}</pre><p v-else class="field-help">原文已清除，固定范围保留；恢复后主动预览重新核对。</p></div><NButton text size="small" :disabled="blocked" @click="remove(sourceIndex, rangeIndex)">移除此段</NButton></div>
      </li></ol>
      <label class="question-field">你的问题<NInput v-model:value="question" type="textarea" :disabled="blocked" :autosize="{ minRows: 3, maxRows: 10 }" placeholder="例如：这些材料对缓存失效后的补偿描述是否一致？请说明依据与仍缺少的信息。" /><span class="field-help">去首尾 Java 空白后最多 2000 个 UTF-16 码元。你手工输入到问题中的文字也会外发。</span></label>
      <p v-if="settings.error.value" class="notice error" role="alert">{{ errorText(settings.error.value) }}<NButton text :disabled="!readable" @click="settings.refetch()">重新读取 AI 配置</NButton></p>
      <p v-else-if="!configured" class="field-help">AI 尚未启用或第三方 API 配置不完整，问题与选择可先保留在本页。</p>
      <div class="row-actions"><NButton :disabled="blocked || !configured || !valid" :loading="previewing" @click="prepare">{{ prepared ? '重新核对外发清单' : '预览全部外发内容' }}</NButton><span v-if="!valid" class="field-help">填写有效问题，并选择 1–5 个固定来源、合计 1–8 段原文。</span></div>
      <p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
      <section v-if="prepared && previewMatches && readable" class="consent-panel">
        <AiManifest :manifest="prepared.manifest" :budget="prepared.budget" />
        <p v-if="overBudget" class="notice error">本次预留超过当前剩余预算，请处理费用或预算后重新预览。</p>
        <AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="prepared.manifest" :manifest-hash="prepared.manifestHash" :disabled="blocked || overBudget || !configured">我已核对完整问题、全部选中片段和来源限定，同意发送给 {{ prepared.manifest.configuration.provider }} 的 {{ prepared.manifest.configuration.model }}，并预留 {{ formatAiAmount(prepared.manifest.reservedMicros, prepared.manifest.configuration.currency) }}</AiAuthorizationChoice>
        <NButton type="primary" :loading="writer.busy.value" :disabled="blocked || !consent || overBudget || !configured" @click="create">授权本次外发并提问</NButton>
        <p class="field-help">来源不足会保存为一次完成的回答。不会自动扩大检索、发送其他资料或再次调用。</p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.source-qa-create { display: grid; gap: 20px; min-width: 0; margin-top: 36px; padding: 26px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
header { display: flex; align-items: start; justify-content: space-between; gap: 18px; flex-wrap: wrap; } h2 { margin: 4px 0 8px; font-size: 23px; } h3 { margin: 0; font-size: 14px; overflow-wrap: anywhere; }
.selection-count { color: var(--muted); font-size: 12px; } .field-picker, label, .question-field { display: grid; gap: 10px; min-width: 0; } label { color: var(--muted); font-size: 13px; }
textarea { box-sizing: border-box; width: 100%; resize: vertical; padding: 12px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); color: var(--text); font: 12px/1.8 Consolas, "Microsoft YaHei", monospace; }
textarea:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; } .selections { display: grid; gap: 20px; list-style: none; padding: 0; margin: 0; }
.selections li { padding-top: 18px; border-top: 1px solid var(--line); } .identity { margin-top: 6px; color: var(--muted); overflow-wrap: anywhere; font-size: 11px; }
.selected-range { display: flex; align-items: start; justify-content: space-between; gap: 12px; margin-top: 12px; } .selected-range > div { flex: 1; min-width: 0; }
pre { max-height: 240px; overflow: auto; margin: 6px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.8 Consolas, "Microsoft YaHei", monospace; }
.consent-panel { display: grid; gap: 20px; padding-top: 20px; border-top: 1px solid var(--line); min-width: 0; } .consent-panel > .n-button { justify-self: start; }
@media (max-width: 600px) { .source-qa-create { padding: 17px; } .selected-range { flex-wrap: wrap; } .selected-range > div { flex-basis: 100%; } }
</style>
