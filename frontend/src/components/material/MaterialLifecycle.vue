<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton, NCheckbox, NInput } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../../api/client'
import type { MaterialLifecycleAction, MaterialLifecycleChange, MaterialLifecycleHistoryPage, MaterialLifecycleState } from '../../api/materialLifecycle'
import { materialAiUseLabels, materialLifecycleActionAvailable, materialLifecycleActionHelp, materialLifecycleActionLabels,
  materialLifecycleActions, materialLifecycleBasePath, materialLifecycleChangeValid, materialLifecycleHistoryPath,
  materialLifecycleHistoryValid, materialLifecycleReasonValid, materialLifecycleReceiptValid, materialLifecycleStateValid,
  materialLifecycleStatusLabels, normalizeMaterialLifecycleReason } from '../../api/materialLifecycle'
import { materialReferenceIdValid } from '../../api/materialReferences'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ excerptId: string; attachmentId: string; enabled: boolean }>()
const emit = defineEmits<{ changed: []; references: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const panelId = `material-lifecycle-${useId()}`, element = ref<HTMLElement>(), active = ref(true)
const opened = ref(false), documentVisible = ref(document.visibilityState === 'visible'), elementVisible = ref(true)
const state = shallowRef<MaterialLifecycleState>(), history = shallowRef<MaterialLifecycleHistoryPage>()
const stateLoading = ref(false), stateProblem = ref(''), historyOpened = ref(false), historyLoading = ref(false), historyProblem = ref('')
const cursor = ref<string>(), previous = ref<(string | undefined)[]>([]), paused = ref(false)
const drafts = reactive<Record<MaterialLifecycleAction, { reason: string; confirmed: boolean }>>({
  ARCHIVE: { reason: '', confirmed: false }, RESTORE: { reason: '', confirmed: false },
  REVOKE_AI: { reason: '', confirmed: false }, ALLOW_AI: { reason: '', confirmed: false },
})
type Receipt = { state: MaterialLifecycleState; change: MaterialLifecycleChange }
type HistoryRequest = { root: string; after?: string; previous: (string | undefined)[] }
const receipt = shallowRef<Receipt>(), deferred = shallowRef<Receipt>(), lastHistory = shallowRef<HistoryRequest>()
const commandRoot = ref(''), root = computed(() => `${props.excerptId}/${props.attachmentId}`)
const identityValid = computed(() => materialReferenceIdValid(props.excerptId) && materialReferenceIdValid(props.attachmentId))
const readable = computed(() => active.value && store.sessionActive && props.enabled && identityValid.value && documentVisible.value
  && elementVisible.value && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const dirty = computed(() => Object.values(drafts).some(draft => Boolean(draft.reason || draft.confirmed)))
const changeState = reactive({ dirty: false, locked: false })
const sameCommandRoot = computed(() => !locked.value || commandRoot.value === root.value)
const otherCommand = computed(() => Boolean(store.changes.get('material-editor-command')?.locked || store.changes.get('material-upload')?.locked))
const canWrite = computed(() => readable.value && opened.value && Boolean(state.value) && !locked.value && !stateLoading.value
  && !otherCommand.value && state.value?.revision !== Number.MAX_SAFE_INTEGER)
const historyRetryReady = computed(() => lastHistory.value?.root === root.value)
let alive = true, generation = 0, stateSequence = 0, historySequence = 0, observer: MutationObserver | undefined

watch([dirty, locked], ([changed, pending]) => {
  changeState.dirty = changed; changeState.locked = pending; store.changes.set('material-lifecycle', changeState)
}, { immediate: true, flush: 'sync' })
function resetConfirmations() { for (const action of materialLifecycleActions) drafts[action].confirmed = false }
function clearVisible() {
  generation++; stateSequence++; historySequence++; state.value = undefined; history.value = undefined; receipt.value = undefined
  stateLoading.value = false; historyLoading.value = false; stateProblem.value = ''; historyProblem.value = ''
  cursor.value = undefined; previous.value = []; resetConfirmations()
}
watch(root, () => {
  clearVisible(); opened.value = false; historyOpened.value = false; paused.value = false
  if (!locked.value) for (const action of materialLifecycleActions) drafts[action].reason = ''
}, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess],
  () => { clearVisible(); paused.value = opened.value }, { flush: 'sync' })
watch([writer.error, writer.pending], ([error, pending]) => {
  if (!(error instanceof ApiError) || pending || error.status !== 409) return
  clearVisible(); paused.value = true
  if (readable.value && opened.value) void loadState()
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
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; clearVisible(); observer?.disconnect(); document.removeEventListener('visibilitychange', visibilityChanged)
  if (store.changes.get('material-lifecycle') === changeState) store.changes.delete('material-lifecycle')
})
function toggle() {
  if (!readable.value) return
  opened.value = !opened.value; clearVisible(); paused.value = false
  if (opened.value) void loadState()
}
async function loadState() {
  if (!readable.value || !opened.value || stateLoading.value) return
  const ticket = ++stateSequence, context = generation, expectedRoot = root.value
  const excerptId = props.excerptId, attachmentId = props.attachmentId, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  state.value = undefined; stateLoading.value = true; stateProblem.value = ''; paused.value = false; resetConfirmations()
  const current = () => alive && readable.value && opened.value && context === generation && ticket === stateSequence
    && expectedRoot === root.value && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
  try {
    const value = await read<unknown>(materialLifecycleBasePath(excerptId))
    if (!current()) return
    if (!materialLifecycleStateValid(value, excerptId, attachmentId)) throw new ApiError(502, 'MATERIAL_LIFECYCLE_INVALID', '摘录生命周期的身份、状态或修订无法核对，请重新读取。')
    state.value = value
  } catch (error) { if (current()) stateProblem.value = errorText(error) }
  finally { if (alive && ticket === stateSequence) stateLoading.value = false }
}
async function loadHistory(after?: string, stack: (string | undefined)[] = []) {
  if (!readable.value || !opened.value || !historyOpened.value || historyLoading.value) return
  const ticket = ++historySequence, context = generation, expectedRoot = root.value
  const excerptId = props.excerptId, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  lastHistory.value = { root: expectedRoot, after, previous: [...stack] }
  history.value = undefined; historyLoading.value = true; historyProblem.value = ''
  const current = () => alive && readable.value && opened.value && historyOpened.value && context === generation
    && ticket === historySequence && expectedRoot === root.value && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
  try {
    const value = await read<unknown>(materialLifecycleHistoryPath(excerptId, after))
    if (!current()) return
    if (!materialLifecycleHistoryValid(value, excerptId, after)) throw new ApiError(502, 'MATERIAL_LIFECYCLE_HISTORY_INVALID', '摘录变更历史的身份、事件或分页无法核对，请重读这一页。')
    history.value = value; cursor.value = after; previous.value = [...stack]
  } catch (error) { if (current()) historyProblem.value = errorText(error) }
  finally { if (alive && ticket === historySequence) historyLoading.value = false }
}
function toggleHistory() {
  if (!readable.value || !opened.value) return
  historyOpened.value = !historyOpened.value; historySequence++; historyLoading.value = false; history.value = undefined
  historyProblem.value = ''; cursor.value = undefined; previous.value = []
  if (historyOpened.value) void loadHistory()
}
function retryHistory() { const request = lastHistory.value; if (request && historyRetryReady.value) void loadHistory(request.after, request.previous) }
function nextHistory() { if (history.value?.nextCursor) void loadHistory(history.value.nextCursor, [...previous.value, cursor.value]) }
function previousHistory() { if (previous.value.length) void loadHistory(previous.value.at(-1), previous.value.slice(0, -1)) }
function acceptReceipt(value: Receipt) {
  // 命令后的当前状态必须重新读取，不能被命令前仍在途的状态请求占用或覆盖。
  stateSequence++; stateLoading.value = false
  deferred.value = undefined; receipt.value = value; drafts[value.change.action].reason = ''; resetConfirmations()
  state.value = undefined; historySequence++; history.value = undefined; historyLoading.value = false
  cursor.value = undefined; previous.value = []; historyProblem.value = ''
  emit('changed'); void loadState()
}
function submit(action: MaterialLifecycleAction) {
  const before = state.value, draft = drafts[action]
  if (!canWrite.value || !before || !materialLifecycleActionAvailable(before, action) || !materialLifecycleReasonValid(draft.reason) || !draft.confirmed) return
  const change: MaterialLifecycleChange = { expectedRevision: before.revision, action, reason: normalizeMaterialLifecycleReason(draft.reason), confirmed: true }
  if (!materialLifecycleChangeValid(change)) return
  const baseline = { ...before }, expectedRoot = root.value, context = generation, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  commandRoot.value = expectedRoot; receipt.value = undefined
  // 未决命令固定根、修订、动作、理由与原键；遮挡和权限变化只延后接收，不能换一个动作覆盖。
  void writer.run<unknown>(`${materialLifecycleBasePath(before.excerptId)}/changes`, 'POST', change, value => {
    if (!materialLifecycleReceiptValid(value, baseline, change)) throw new ApiError(502, 'MATERIAL_LIFECYCLE_RECEIPT_INVALID', '变更回执无法与原摘录、修订和动作核对；原请求继续保留，请用原请求恢复。')
    const result = { state: value, change }
    if (!alive || !readable.value || !opened.value || expectedRoot !== root.value || context !== generation
      || epoch !== store.sessionEpoch || accessEpoch !== currentTrainingAccessEpoch()) deferred.value = result
    else acceptReceipt(result)
  })
}
function retryCommand() {
  if (readable.value && opened.value && sameCommandRoot.value && !otherCommand.value && !writer.busy.value) void writer.retry()
}
function receiveDeferred() {
  const value = deferred.value
  if (value && readable.value && opened.value && sameCommandRoot.value && !otherCommand.value) acceptReceipt(value)
}
function showReferences() { if (readable.value && opened.value && !locked.value) emit('references') }
</script>

<template>
  <section ref="element" class="material-lifecycle" aria-label="确切摘录生命周期">
    <header><div><h3>摘录状态与 AI 用途</h3><p class="field-help">只变更这条确切摘录的选用边界，保留不可变原文。</p></div><NButton size="small" :disabled="!readable" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起生命周期' : '查看状态与变更' }}</NButton></header>
    <p v-if="!identityValid" class="notice error" role="alert">摘录与附件身份无法核对，不能读取或变更生命周期。</p>
    <p v-else-if="opened && !readable" class="notice" role="status">查看条件变化，旧状态与历史已清除；理由和未决原请求继续保留。</p>
    <div v-if="opened" v-show="readable" :id="panelId" class="lifecycle-content" :inert="!readable || undefined">
      <p class="field-help">没有已登记引用，或只有合法 AI 输入登记且尚无正式采用内容及其他本地绑定的摘录，可按当前状态执行四类动作。失败、取消、未发送、进行中或成功未采用的任务，以及合法的忽略决定，不会单独阻止四类动作。</p>
      <p class="field-help">准备任一历史版本、正式关系直接材料证据、标准审阅原始绑定，或相关任务任一已采用的知识／题目／事实／关系仍会阻止变更，关系补充也属于采用；归档、改版、关系移除或审阅撤回不会解除限制。四类动作均遵守此边界，包含“重新允许 AI 用途”。此处不提供删除或已采用内容的更正传播。</p>
      <p class="field-help">归档或撤回生效后，后续资格核对会拒绝旧任务的发送许可、暂态正文读取、结果接纳、候选采用与重试。已获发送许可的网络请求可能继续到达供应商并产生费用，已发送正文及已收到的片段无法收回；不保证立即取消供应商请求或费用归零。恢复摘录或重新允许 AI 用途不会恢复旧清单、预览或授权，须按当前代次重新预览与授权。</p>
      <p v-if="!sameCommandRoot" class="notice error" role="alert">原摘录的变更结果尚未接收。请返回原摘录核对原请求，不可用当前摘录覆盖。</p>
      <p v-if="locked" class="notice" role="status">原变更请求尚未完成确认或回执接收；摘录、动作及理由保持锁定。</p>
      <p v-if="paused" class="notice" role="status">查看条件已变化，请主动重新读取当前状态；确认勾选已取消，理由继续保留。</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text size="small" :loading="writer.busy.value" :disabled="!sameCommandRoot || otherCommand || writer.busy.value" @click="retryCommand">用原请求恢复</NButton></p>
      <p v-if="deferred" class="notice" role="status">原变更已返回可核对回执，访问条件变化后暂缓接收。<NButton text size="small" :disabled="!sameCommandRoot || otherCommand" @click="receiveDeferred">接收原变更回执</NButton></p>
      <article v-if="receipt" class="lifecycle-receipt" role="status"><h4>原请求回执 · {{ materialLifecycleActionLabels[receipt.change.action] }}</h4><p>原修订 {{ receipt.change.expectedRevision }} → 回执修订 {{ receipt.state.revision }} · {{ materialLifecycleStatusLabels[receipt.state.status] }} · {{ materialAiUseLabels[receipt.state.aiUse] }}</p><p class="field-help">回执仅说明原请求已完成，不代表当前状态。请以下方重新读取的当前状态为准；旧清单、预览与授权不会因恢复或重新允许而重新有效。</p></article>
      <div class="row-actions"><NButton size="small" :loading="stateLoading" :disabled="stateLoading" @click="loadState">重新读取当前状态</NButton><NButton text size="small" :disabled="locked" @click="showReferences">在确切原文中查看直接引用</NButton></div>
      <p v-if="stateProblem" class="notice error" role="alert">{{ stateProblem }}</p><p v-if="stateLoading" class="field-help" role="status">正在读取生命周期及已登记引用存在性…</p>
      <template v-else-if="state">
        <dl class="lifecycle-state"><div><dt>当前状态</dt><dd>{{ materialLifecycleStatusLabels[state.status] }}</dd></div><div><dt>AI 用途</dt><dd>{{ materialAiUseLabels[state.aiUse] }}</dd></div><div><dt>当前修订</dt><dd>{{ state.revision }}</dd></div><div><dt>更新时间</dt><dd>{{ new Date(state.updatedAt).toLocaleString('zh-CN') }}</dd></div></dl>
        <p v-if="state.hasReferences" class="notice" role="status">已发现登记引用；此存在性提示不代表四类变更被禁止，也不代表已获执行资格。提交时由服务器原子核对正式采用及其他不支持的绑定，可能返回 409 MATERIAL_HAS_REFERENCES。直接引用目录可帮助核对，但不是完整删除清单。</p>
        <p v-else class="field-help">本次读取未发现已登记引用，仅用于当前提示。提交仍须核对最新修订、实际状态、正式采用及其他不支持的绑定。</p>
        <p v-if="state.revision === Number.MAX_SAFE_INTEGER" class="notice error">修订已达到安全整数上限，不能继续变更，请保留当前记录并联系维护者。</p>
        <div class="lifecycle-actions">
          <form v-for="action in materialLifecycleActions" :key="action" class="lifecycle-action" @submit.prevent="submit(action)">
            <h4>{{ materialLifecycleActionLabels[action] }}</h4><p class="field-help">{{ materialLifecycleActionHelp[action] }}</p>
            <p v-if="!materialLifecycleActionAvailable(state, action)" class="field-help">当前状态不允许此转换。</p>
            <label :for="`${panelId}-${action}`">本次变更理由<NInput :id="`${panelId}-${action}`" v-model:value="drafts[action].reason" type="textarea" :maxlength="1000" :autosize="{ minRows: 2, maxRows: 5 }" :disabled="!canWrite || !materialLifecycleActionAvailable(state, action)" /></label>
            <NCheckbox v-model:checked="drafts[action].confirmed" :disabled="!canWrite || !materialLifecycleActionAvailable(state, action)">我确认仅执行此动作，理解当前限制</NCheckbox>
            <NButton size="small" :disabled="!canWrite || !materialLifecycleActionAvailable(state, action) || !drafts[action].confirmed || !materialLifecycleReasonValid(drafts[action].reason)" @click="submit(action)">{{ materialLifecycleActionLabels[action] }}</NButton>
          </form>
        </div>
      </template>
      <section class="lifecycle-history"><div class="row-actions"><h4>生命周期变更历史</h4><NButton text size="small" :aria-expanded="historyOpened" @click="toggleHistory">{{ historyOpened ? '收起历史' : '读取变更历史' }}</NButton><NButton v-if="historyOpened" text size="small" :disabled="historyLoading" @click="loadHistory()">从首项重读历史</NButton></div><template v-if="historyOpened"><p class="field-help">历史只含状态变更及理由，不含摘录正文。现存摘录的初始基线没有补造事件，翻页也不冻结后续变更。</p><p v-if="historyProblem" class="notice error" role="alert">{{ historyProblem }}<NButton text size="small" :disabled="historyLoading || !historyRetryReady" @click="retryHistory">重读这一页</NButton></p><p v-if="historyLoading" class="field-help" role="status">正在读取变更历史…</p><template v-else-if="history"><p v-if="!history.items.length" class="field-help">这一页没有生命周期变更事件，不能据此推断不存在摘录引用。</p><ol v-else class="lifecycle-events"><li v-for="event in history.items" :key="event.id"><strong>修订 {{ event.previousRevision }} → {{ event.revision }} · {{ materialLifecycleActionLabels[event.action] }}</strong><p>{{ materialLifecycleStatusLabels[event.previousStatus] }}／{{ materialAiUseLabels[event.previousAiUse] }} → {{ materialLifecycleStatusLabels[event.status] }}／{{ materialAiUseLabels[event.aiUse] }}</p><p class="lifecycle-reason">{{ event.reason }}</p><small>{{ new Date(event.createdAt).toLocaleString('zh-CN') }} · {{ event.id }}</small></li></ol><nav v-if="previous.length || history.nextCursor" class="row-actions" aria-label="摘录生命周期历史分页"><NButton size="small" :disabled="historyLoading || !previous.length" @click="previousHistory">上一页</NButton><NButton size="small" :disabled="historyLoading || !history.nextCursor" @click="nextHistory">下一页</NButton><span class="field-help">第 {{ previous.length + 1 }} 页 · 每页最多20项</span></nav></template></template></section>
    </div>
  </section>
</template>

<style scoped>
.material-lifecycle, .lifecycle-content, .lifecycle-history { display: grid; gap: 12px; min-width: 0; }
.material-lifecycle { padding: 15px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
header, .row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 9px 12px; }
header { justify-content: space-between; align-items: start; } header > div { display: grid; gap: 7px; min-width: 0; }
h3, h4, p { margin: 0; } h3 { font-size: 14px; } h4 { font-size: 13px; } p, small, dd { overflow-wrap: anywhere; }
.lifecycle-state { display: grid; gap: 8px; margin: 0; font-size: 12px; }.lifecycle-state > div { display: grid; grid-template-columns: 85px minmax(0, 1fr); gap: 9px; }
dt { color: var(--muted); } dd { margin: 0; }.lifecycle-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.lifecycle-action, .lifecycle-receipt { display: grid; gap: 10px; min-width: 0; padding: 13px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); }
.lifecycle-action > label { display: grid; gap: 7px; font-size: 12px; }.lifecycle-action > .n-button { justify-self: start; }
.lifecycle-receipt { font-size: 12px; }.lifecycle-history { padding-top: 12px; border-top: 1px solid var(--line); }
.lifecycle-events { display: grid; list-style: none; margin: 0; padding: 0; min-width: 0; }.lifecycle-events > li { display: grid; gap: 8px; padding: 13px 0; border-top: 1px solid var(--line); font-size: 12px; }
.lifecycle-events small { color: var(--muted); font-size: 11px; }.lifecycle-reason { white-space: pre-wrap; }
@media (max-width: 700px) { .lifecycle-actions { grid-template-columns: 1fr; } }
</style>
