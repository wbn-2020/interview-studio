<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton, NCheckbox, NInput } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../../api/client'
import type { MaterialExcerpt } from '../../api/material'
import type { MaterialCorrectionCreate, MaterialCorrectionState, MaterialCorrectionStatement } from '../../api/materialCorrection'
import { materialCorrectionCreateValid, materialCorrectionExcerptSame, materialCorrectionExcerptValid, materialCorrectionLifecycleMatches,
  materialCorrectionPath, materialCorrectionReceiptValid, materialCorrectionStateValid } from '../../api/materialCorrection'
import type { MaterialLifecycleState } from '../../api/materialLifecycle'
import { materialAiUseLabels, materialLifecycleBasePath, materialLifecycleReasonValid, materialLifecycleStatusLabels,
  normalizeMaterialLifecycleReason } from '../../api/materialLifecycle'
import { materialReferenceIdValid } from '../../api/materialReferences'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MaterialExcerptPicker from './MaterialExcerptPicker.vue'
import MaterialExcerptReadout from './MaterialExcerptReadout.vue'

const props = defineProps<{ excerptId: string; attachmentId: string; enabled: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const panelId = `material-correction-${useId()}`, element = ref<HTMLElement>(), active = ref(true)
const opened = ref(false), documentVisible = ref(document.visibilityState === 'visible'), elementVisible = ref(false)
const state = shallowRef<MaterialCorrectionState>(), original = shallowRef<MaterialExcerpt>(), lifecycle = shallowRef<MaterialLifecycleState>()
const replacement = shallowRef<MaterialExcerpt>(), replacementLifecycle = shallowRef<MaterialLifecycleState>()
const replacementId = ref<string>(), replacementAttachmentId = ref<string>()
const reason = ref(''), confirmed = ref(false), pickerOpened = ref(false), replacementOpened = ref(false)
const loading = ref(false), replacementLoading = ref(false), problem = ref(''), replacementProblem = ref(''), paused = ref(false)
const receipt = shallowRef<MaterialCorrectionStatement>(), deferred = shallowRef<MaterialCorrectionStatement>()
const commandRoot = ref(''), root = computed(() => `${props.excerptId}/${props.attachmentId}`)
const identityValid = computed(() => materialReferenceIdValid(props.excerptId) && materialReferenceIdValid(props.attachmentId))
const readable = computed(() => active.value && store.sessionActive && props.enabled && identityValid.value && documentVisible.value
  && elementVisible.value && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const otherCommand = computed(() => Boolean(store.changes.get('material-editor-command')?.locked
  || store.changes.get('material-upload')?.locked || store.changes.get('material-lifecycle')?.locked)
  || [...store.changes].some(([key, value]) => key.startsWith('material-dependency-impact:') && value.locked))
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const dirty = computed(() => Boolean(replacementId.value || reason.value || confirmed.value))
const changes = reactive({ dirty: false, locked: false })
const sameCommandRoot = computed(() => !locked.value || commandRoot.value === root.value)
const statement = computed(() => receipt.value ?? state.value?.correction)
const sourceReady = computed(() => Boolean(state.value && original.value && lifecycle.value && !state.value.correction
  && lifecycle.value.status === 'ACTIVE' && state.value.lifecycleRevision === lifecycle.value.revision))
const sameText = computed(() => Boolean(original.value && replacement.value && original.value.text === replacement.value.text))
const replacementReady = computed(() => Boolean(replacement.value && replacementLifecycle.value
  && replacement.value.id === replacementId.value && replacementLifecycle.value.status === 'ACTIVE' && !sameText.value))
const canChoose = computed(() => readable.value && opened.value && sourceReady.value && !statement.value
  && !locked.value && !otherCommand.value && !loading.value && !replacementLoading.value)
const canSubmit = computed(() => canChoose.value && replacementReady.value && materialLifecycleReasonValid(reason.value) && confirmed.value)
let alive = true, generation = 0, sourceSequence = 0, replacementSequence = 0, observer: MutationObserver | undefined

watch([dirty, locked], ([unsaved, pending]) => {
  changes.dirty = unsaved; changes.locked = pending; store.changes.set('material-correction', changes)
}, { immediate: true, flush: 'sync' })
function clearVisible() {
  generation++; sourceSequence++; replacementSequence++; state.value = undefined; original.value = undefined; lifecycle.value = undefined
  replacement.value = undefined; replacementLifecycle.value = undefined; receipt.value = undefined
  loading.value = false; replacementLoading.value = false; problem.value = ''; replacementProblem.value = ''
  pickerOpened.value = false; replacementOpened.value = false; confirmed.value = false
}
function clearInput() {
  replacementId.value = undefined; replacementAttachmentId.value = undefined; reason.value = ''; confirmed.value = false
  replacement.value = undefined; replacementLifecycle.value = undefined; replacementProblem.value = ''; pickerOpened.value = false
}
watch(root, () => {
  clearVisible(); opened.value = false; paused.value = false
  if (!locked.value) clearInput()
}, { flush: 'sync' })
watch([readable, otherCommand, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode,
  () => access.active?.materialAccess], () => { clearVisible(); paused.value = opened.value }, { flush: 'sync' })
watch([reason, replacementId], () => { confirmed.value = false }, { flush: 'sync' })
watch([writer.error, writer.pending], ([error, pending]) => {
  if (error instanceof ApiError && !pending && error.status === 409) { clearVisible(); paused.value = true }
}, { flush: 'sync' })
function visibilityChanged() { documentVisible.value = document.visibilityState === 'visible' }
function elementVisibilityChanged() {
  const node = element.value
  elementVisible.value = Boolean(node && node.getClientRects().length && !node.closest('[hidden], [inert], [aria-hidden="true"]'))
  for (let ancestor: HTMLElement | null = node ?? null; ancestor && elementVisible.value; ancestor = ancestor.parentElement) {
    const style = getComputedStyle(ancestor)
    if (style.visibility === 'hidden' || style.visibility === 'collapse') elementVisible.value = false
  }
}
onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged); elementVisibilityChanged()
  observer = new MutationObserver(elementVisibilityChanged)
  for (let node: HTMLElement | null = element.value ?? null; node; node = node.parentElement) {
    observer.observe(node, { attributes: true, attributeFilter: ['style', 'class', 'hidden', 'inert', 'aria-hidden'] })
  }
})
onActivated(() => { active.value = true; elementVisibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; clearVisible(); observer?.disconnect(); document.removeEventListener('visibilitychange', visibilityChanged)
  if (store.changes.get('material-correction') === changes) store.changes.delete('material-correction')
})
function toggle() {
  if (!readable.value) return
  opened.value = !opened.value; clearVisible(); paused.value = false
  if (opened.value && !locked.value) void load()
}
async function load() {
  if (!readable.value || !opened.value || locked.value || otherCommand.value || loading.value) return
  const ticket = ++sourceSequence, context = generation, expectedRoot = root.value
  const excerptId = props.excerptId, attachmentId = props.attachmentId, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  state.value = undefined; original.value = undefined; lifecycle.value = undefined
  replacementSequence++; replacement.value = undefined; replacementLifecycle.value = undefined; replacementLoading.value = false
  loading.value = true; problem.value = ''; replacementProblem.value = ''; confirmed.value = false; paused.value = false
  pickerOpened.value = false; replacementOpened.value = false
  const current = () => alive && readable.value && opened.value && !otherCommand.value && context === generation
    && ticket === sourceSequence && expectedRoot === root.value && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
  try {
    const [correction, excerpt, status] = await Promise.all([
      read<unknown>(materialCorrectionPath(excerptId)), read<unknown>(`/materials/excerpts/${encodeURIComponent(excerptId)}`),
      read<unknown>(materialLifecycleBasePath(excerptId)),
    ])
    if (!current()) return
    if (!materialCorrectionStateValid(correction, excerptId, attachmentId)
      || !materialCorrectionExcerptValid(excerpt, excerptId, attachmentId) || !materialCorrectionLifecycleMatches(status, excerpt)
      || correction.lifecycleRevision !== status.revision || receipt.value && correction.correction?.id !== receipt.value.id) {
      throw new ApiError(502, 'MATERIAL_CORRECTION_STATE_INVALID', '旧摘录原文、生命周期修订或更正声明无法核对，请重新读取。')
    }
    state.value = correction; original.value = excerpt; lifecycle.value = status
  } catch (error) { if (current()) problem.value = correctionError(error) }
  finally { if (alive && ticket === sourceSequence) loading.value = false }
}
function togglePicker() { if (canChoose.value) pickerOpened.value = !pickerOpened.value }
function choose(value: MaterialExcerpt) {
  if (!canChoose.value || value.id === props.excerptId || !materialCorrectionExcerptValid(value, value.id)) return
  replacementId.value = value.id; replacementAttachmentId.value = value.attachmentId; pickerOpened.value = false
  void loadReplacement(value)
}
async function loadReplacement(selected?: MaterialExcerpt) {
  if (!canChoose.value || !replacementId.value || !replacementAttachmentId.value) return
  const excerptId = replacementId.value, attachmentId = replacementAttachmentId.value
  const ticket = ++replacementSequence, context = generation, expectedRoot = root.value
  const epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  replacement.value = undefined; replacementLifecycle.value = undefined; replacementLoading.value = true
  replacementProblem.value = ''; confirmed.value = false
  const current = () => alive && readable.value && opened.value && !otherCommand.value && context === generation
    && ticket === replacementSequence && expectedRoot === root.value && excerptId === replacementId.value
    && attachmentId === replacementAttachmentId.value && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
  try {
    const [excerpt, status] = await Promise.all([read<unknown>(`/materials/excerpts/${encodeURIComponent(excerptId)}`),
      read<unknown>(materialLifecycleBasePath(excerptId))])
    if (!current()) return
    if (!materialCorrectionExcerptValid(excerpt, excerptId, attachmentId) || !materialCorrectionLifecycleMatches(status, excerpt)
      || status.status !== 'ACTIVE' || selected && !materialCorrectionExcerptSame(excerpt, selected)) {
      throw new ApiError(502, 'MATERIAL_CORRECTION_REPLACEMENT_INVALID', '所选替代的固定原文、附件、有效状态或修订无法核对，请重新选择或读取。')
    }
    replacement.value = excerpt; replacementLifecycle.value = status
  } catch (error) { if (current()) replacementProblem.value = correctionError(error) }
  finally { if (alive && ticket === replacementSequence) replacementLoading.value = false }
}
function acceptReceipt(value: MaterialCorrectionStatement) {
  sourceSequence++; replacementSequence++; loading.value = false; replacementLoading.value = false
  deferred.value = undefined; receipt.value = value; clearInput()
  if (state.value) state.value = { ...state.value, correction: value }
  emit('changed')
  void load()
}
function submit() {
  if (!canSubmit.value || !lifecycle.value || !replacementLifecycle.value || !replacementId.value) return
  const excerptId = props.excerptId, attachmentId = props.attachmentId, expectedRoot = root.value, context = generation
  const epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  const create: MaterialCorrectionCreate = { expectedRevision: lifecycle.value.revision, replacementExcerptId: replacementId.value,
    expectedReplacementRevision: replacementLifecycle.value.revision, reason: normalizeMaterialLifecycleReason(reason.value), confirmed: true }
  if (!materialCorrectionCreateValid(create, excerptId)) return
  commandRoot.value = expectedRoot; receipt.value = undefined
  // 未知结果始终恢复原根、双方修订和原键；遮挡后只暂缓接收，不重新组装一次更正。
  void writer.run<unknown>(materialCorrectionPath(excerptId), 'POST', create, value => {
    if (!materialCorrectionReceiptValid(value, excerptId, attachmentId, create)) {
      throw new ApiError(502, 'MATERIAL_CORRECTION_RECEIPT_INVALID', '更正回执无法与原摘录、替代摘录及双方修订核对；请用原请求恢复。')
    }
    if (!alive || !readable.value || !opened.value || otherCommand.value || expectedRoot !== root.value
      || context !== generation || epoch !== store.sessionEpoch || accessEpoch !== currentTrainingAccessEpoch()) deferred.value = value.correction
    else acceptReceipt(value.correction)
  })
}
function retryCommand() {
  if (readable.value && opened.value && sameCommandRoot.value && !otherCommand.value && !writer.busy.value) void writer.retry()
}
function receiveDeferred() {
  if (deferred.value && readable.value && opened.value && sameCommandRoot.value && !otherCommand.value) acceptReceipt(deferred.value)
}
function cancel() {
  if (!readable.value || locked.value || !store.canLeave(['material-correction'])) return
  replacementSequence++; replacementLoading.value = false; clearInput(); writer.error.value = undefined
}
function toggleReplacement() {
  if (readable.value && opened.value && statement.value) replacementOpened.value = !replacementOpened.value
}
function correctionError(error: unknown): string {
  const detail = errorText(error)
  if (!(error instanceof ApiError)) return detail
  return error.code === 'MATERIAL_HAS_REFERENCES' ? `${detail} 仅支持完整核验的知识链、既有卡片／审阅出口，以及仍保留全部原知识来源的 AI 采用卡；移除原来源的 AI 卡及其他未支持绑定仍阻塞，不会重绑历史。`
    : error.code === 'MATERIAL_CARD_PROOF_LIMIT' ? `${detail} 制卡历史超出本次完整核验额度，未保存部分更正或影响；重试不会自动扩大额度。`
    : error.code === 'MATERIAL_CONTENT_CORRECTED' ? `${detail} 旧摘录已有不可撤销的更正声明，请重新读取原声明。` : detail
}
</script>

<template>
  <section ref="element" class="material-correction" aria-label="不可变材料摘录更正">
    <header><div><h3>更正这条摘录</h3><p class="field-help">固定旧摘录，手选另一条已保存摘录，再明确提交更正声明。</p></div><NButton size="small" :disabled="!readable" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起摘录更正' : '查看更正与声明' }}</NButton></header>
    <p v-if="!identityValid" class="notice error" role="alert">旧摘录与附件身份无法核对，不能读取或提交更正。</p>
    <p v-else-if="opened && !readable" class="notice" role="status">查看条件已变化，原文与旧核对资格已清除，选择、理由和原请求保留。</p>
    <div v-if="opened" v-show="readable" :id="panelId" class="correction-content" :inert="!readable || undefined" :aria-busy="loading || replacementLoading">
      <p class="field-help">声明不可撤销，不改写旧、新原文，也不改变生命周期修订或变更历史。服务器完整核验且无不支持出口的知识链可登记待复核影响；卡片来源及审阅按既有复核机制承接。AI 采用卡还须核实原任务与首版卡，当前卡必须保留全部原知识来源；移除原来源的 AI 卡及题目、训练等其他未支持出口仍阻塞。不会自动替换历史引用。</p>
      <p class="field-help">知识影响须逐项人工处理；处理不恢复旧评分、审阅、授权或卡片排程，也不移除旧材料祖先的 AI 限制。保留当前或发布正文更新后，AI 使用仍可能被拒绝，须另行建立真实新依据。</p>
      <p class="field-help">更正后旧摘录退出新引用、搜索及 AI 选用，原 ID 与“全部摘录”仍可读取历史。替代摘录可以来自另一附件；撤回 AI 用途不妨碍本地选择，也不授予外发许可。若替代以后另有更正，此处仍保留本次固定身份，不自动展开。</p>
      <p v-if="!sameCommandRoot" class="notice error" role="alert">原摘录的更正结果尚未接收，请回到原摘录处理原请求。</p>
      <p v-if="locked" class="notice" role="status">更正结果尚未确认或回执尚未接收，旧、新摘录、修订与理由保持锁定。</p>
      <p v-if="otherCommand" class="notice" role="status">其他材料命令尚未完成，请先确认原命令结果，再重新读取更正基线。</p>
      <p v-if="paused" class="notice" role="status">请主动重新读取旧摘录与所选替代，重新核对完整原文后确认；旧资格不会自动恢复。</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ correctionError(writer.error.value) }}<NButton v-if="writer.pending.value" text size="small" :loading="writer.busy.value" :disabled="!sameCommandRoot || otherCommand || writer.busy.value" @click="retryCommand">用原请求恢复</NButton></p>
      <p v-if="deferred" class="notice" role="status">原更正已有可核对回执，访问条件变化后暂缓接收。<NButton text size="small" :disabled="!sameCommandRoot || otherCommand" @click="receiveDeferred">接收原更正回执</NButton></p>
      <div class="row-actions"><NButton size="small" :loading="loading" :disabled="locked || otherCommand || loading" @click="load">重新读取旧摘录与声明</NButton><NButton v-if="dirty" text size="small" :disabled="locked" @click="cancel">清空未提交选择与理由</NButton></div>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
      <p v-if="loading" class="field-help" role="status">正在核对旧摘录、生命周期修订与原更正声明…</p>
      <article v-if="statement" class="statement" aria-label="已保存的固定更正声明">
        <h4>已保存的更正声明</h4><p class="correction-reason">{{ statement.reason }}</p>
        <p class="correction-identity">声明 {{ statement.id }}<br />旧摘录 {{ statement.excerptId }} · 保存时生命周期修订 {{ statement.sourceRevision }}<br />固定替代 {{ statement.replacementExcerptId }} · 保存时生命周期修订 {{ statement.replacementRevision }}<br /><time :datetime="statement.createdAt">{{ new Date(statement.createdAt).toLocaleString('zh-CN') }}</time></p>
        <NButton text size="small" @click="toggleReplacement">{{ replacementOpened ? '收起固定替代原文' : '查看固定替代原文' }}</NButton>
        <MaterialExcerptReadout v-if="readable && replacementOpened" :id="statement.replacementExcerptId" />
      </article>
      <article v-if="original && lifecycle" class="excerpt-original" aria-label="更正前完整原文">
        <h4>旧摘录完整原文</h4><p class="correction-identity">摘录 {{ original.id }} · 附件 {{ original.attachmentId }}<br />{{ original.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ original.locator }}<br />{{ materialLifecycleStatusLabels[lifecycle.status] }}／{{ materialAiUseLabels[lifecycle.aiUse] }} · 生命周期修订 {{ lifecycle.revision }}</p><blockquote>{{ original.text }}</blockquote>
      </article>
      <p v-if="lifecycle && !statement && lifecycle.status !== 'ACTIVE'" class="notice" role="status">旧摘录须处于有效状态才能声明更正。请先处理生命周期，再重新读取。</p>
      <template v-if="sourceReady && !statement">
        <div class="row-actions"><NButton size="small" :disabled="!canChoose" :aria-expanded="pickerOpened" @click="togglePicker">{{ pickerOpened ? '收起替代选择' : replacementId ? '重新选择固定替代摘录' : '选择固定替代摘录' }}</NButton><NButton v-if="replacementId" text size="small" :disabled="!canChoose" @click="loadReplacement()">重新读取所选替代</NButton></div>
        <MaterialExcerptPicker v-if="pickerOpened && readable && canChoose" :key="`${root}/${generation}`" search-excerpts :exclude-ids="[excerptId]" choice-label="选择为固定替代摘录" :disabled="!canChoose" @choose="choose" />
        <p v-if="replacementId && !replacement && !replacementLoading" class="field-help">已选固定替代 {{ replacementId }}，请重新读取并核对完整原文及当前修订。</p>
        <p v-if="replacementLoading" class="field-help" role="status">正在核对所选替代的固定原文与生命周期修订…</p>
        <p v-if="replacementProblem" class="notice error" role="alert">{{ replacementProblem }}</p>
        <article v-if="replacement && replacementLifecycle" class="excerpt-original" aria-label="更正后完整原文">
          <h4>新摘录完整原文</h4><p class="correction-identity">摘录 {{ replacement.id }} · 附件 {{ replacement.attachmentId }}<br />{{ replacement.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ replacement.locator }}<br />{{ materialLifecycleStatusLabels[replacementLifecycle.status] }}／{{ materialAiUseLabels[replacementLifecycle.aiUse] }} · 生命周期修订 {{ replacementLifecycle.revision }}</p><blockquote>{{ replacement.text }}</blockquote>
        </article>
        <p v-if="sameText" class="notice error" role="alert">两条摘录正文相同，不能声明更正。请选择正文确有差异的固定替代摘录。</p>
        <label :for="`${panelId}-reason`">更正理由<NInput :id="`${panelId}-reason`" v-model:value="reason" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 7 }" :disabled="locked || otherCommand" placeholder="说明为什么用这条固定摘录更正旧摘录。" /></label>
        <NCheckbox v-model:checked="confirmed" :disabled="!canChoose || !replacementReady || !materialLifecycleReasonValid(reason)">我已核对双方完整原文与固定身份，确认提交不可撤销声明，理解历史不会自动替换</NCheckbox>
        <NButton type="primary" size="small" :loading="writer.busy.value" :disabled="!canSubmit" @click="submit">提交不可撤销更正</NButton>
      </template>
    </div>
  </section>
</template>

<style scoped>
.material-correction, .correction-content, .excerpt-original, .statement { display: grid; gap: 12px; min-width: 0; }
.material-correction { padding-top: 14px; border-top: 1px solid var(--line); }
header, .row-actions { display: flex; flex-wrap: wrap; align-items: start; gap: 9px 12px; }
header { justify-content: space-between; } header > div { display: grid; gap: 7px; min-width: 0; }
h3, h4, p, blockquote { margin: 0; } h3 { font-size: 14px; } h4 { font-size: 13px; }
p, label, h4 { overflow-wrap: anywhere; }.correction-identity { font-size: 12px; color: var(--muted); }
.excerpt-original, .statement { padding-top: 12px; border-top: 1px solid var(--line); }
blockquote { padding-left: 12px; border-left: 2px solid var(--line); white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
.correction-reason { white-space: pre-wrap; font-size: 13px; }
label { display: grid; gap: 8px; font-size: 13px; }.correction-content > .n-button, .statement > .n-button { justify-self: start; }
</style>
