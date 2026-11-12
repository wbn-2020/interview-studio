<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect, NSkeleton } from 'naive-ui'
import { ApiError, preview as postPreview, read } from '../../api/client'
import type { QuestionReference } from '../../api/bank'
import * as s from '../../api/standardReviews'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import StandardReviewForm from './StandardReviewForm.vue'
import StandardReviewReadout from './StandardReviewReadout.vue'

const props = defineProps<{ reference: QuestionReference; disabled?: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const readable = computed(() => !props.disabled && store.sessionActive && !access.checking && !access.restricted && !access.challenge)
const scope = computed(() => `${props.reference.questionId}:${props.reference.versionId}`)
const stateKey = computed(() => `standard-review:${scope.value}`)
const question = computed<s.QuestionVersionRef>(() => ({ questionId: props.reference.questionId, versionId: props.reference.versionId }))
const checkpointOrder = computed(() => props.reference.checkpoints.map(row => row.id))
const filter = ref<'ALL' | s.RecordStatus>('ALL'), catalog = shallowRef<s.StandardReviewPage>(), catalogBusy = ref(false), catalogError = shallowRef<unknown>()
const selectedId = ref<string>(), latest = shallowRef<s.StandardReviewDetail>(), latestBusy = ref(false), latestError = shallowRef<unknown>()
const history = shallowRef<s.StandardHistoryPage>(), historyBusy = ref(false), historyError = shallowRef<unknown>()
const opened = shallowRef<s.OpenedStandardReview>(), editingBase = shallowRef<s.StandardReviewSnapshot>()
const openedEligibility = shallowRef<s.StandardEligibility>(), openedEligibilityBusy = ref(false), openedEligibilityError = shallowRef<unknown>()
const draft = ref<s.StandardReviewDraft>(), baseline = ref(''), pickerDirty = ref(false), editorGeneration = ref(0)
const preview = shallowRef<s.StandardReviewPreview>(), previewBusy = ref(false), previewError = shallowRef<unknown>(), confirmed = ref(false)
const withdrawReason = ref(''), withdrawConfirmed = ref(false), withdrawing = ref(false), message = ref(''), problem = ref('')
const dirty = computed(() => Boolean(draft.value && JSON.stringify(draft.value) !== baseline.value) || pickerDirty.value || Boolean(withdrawReason.value))
const changeState = reactive({ dirty: false, locked: false })
const savedCommand = shallowRef<NonNullable<typeof writer.pending.value>>()
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const invalid = () => new ApiError(0, 'STANDARD_REVIEW_RESPONSE_INVALID', '本次响应无法按确切审阅契约核对，请重新读取；未决命令须使用原请求恢复。')
let alive = true, generation = 0, catalogSequence = 0, latestSequence = 0, historySequence = 0, previewSequence = 0, openedEligibilitySequence = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0, commandScope = scope.value
const current = (epoch: number, stamp: number) => alive && readable.value && epoch === store.sessionEpoch && stamp === generation
const exactQuestion = (value: s.QuestionVersionRef) => value.questionId === question.value.questionId && value.versionId === question.value.versionId
const editableOpened = computed(() => Boolean(opened.value && opened.value.history.snapshot.status === 'ACTIVE' && openedEligibility.value?.currentStatus === 'ACTIVE' && openedEligibility.value.revisionCurrent
  && latest.value?.summary.id === opened.value.history.snapshot.id && latest.value.summary.revision === opened.value.history.snapshot.revision && latest.value.summary.status === 'ACTIVE'))
const canAdoptOpened = computed(() => Boolean(editableOpened.value && draft.value?.id === opened.value?.history.snapshot.id && latest.value?.summary.id === draft.value?.id && latest.value?.summary.revision === opened.value?.history.snapshot.revision && latest.value?.summary.status === 'ACTIVE'))
const mismatch = computed(() => Boolean(draft.value?.id && latest.value && (latest.value.summary.id !== draft.value.id || latest.value.summary.revision !== draft.value.expectedRevision || latest.value.summary.status !== 'ACTIVE')))
const pointLabel = (id: string) => props.reference.checkpoints.find(row => row.id === id)?.text ?? '历史检查项'

watch([dirty, () => writer.locked.value, stateKey], ([changed, locked, key], previous) => {
  if (previous?.[2] && previous[2] !== key && store.changes.get(previous[2]) === changeState) store.changes.delete(previous[2])
  changeState.dirty = changed; changeState.locked = locked; store.changes.set(key, changeState)
}, { immediate: true, flush: 'sync' })
function invalidateReads() {
  generation++; catalogSequence++; latestSequence++; historySequence++; previewSequence++; openedEligibilitySequence++
  catalog.value = undefined; latest.value = undefined; history.value = undefined; opened.value = undefined; preview.value = undefined
  openedEligibility.value = undefined; openedEligibilityError.value = undefined; openedEligibilityBusy.value = false
  catalogBusy.value = false; latestBusy.value = false; historyBusy.value = false; previewBusy.value = false; confirmed.value = false
}
watch([readable, () => store.sessionEpoch, scope], () => {
  invalidateReads()
  if (readable.value) { void loadCatalog(); if (selectedId.value) { void loadCurrent(); void loadHistory() } }
}, { flush: 'sync' })
watch(() => JSON.stringify(draft.value), () => { previewSequence++; preview.value = undefined; confirmed.value = false; previewBusy.value = false; previewError.value = undefined }, { flush: 'sync' })
watch(pickerDirty, () => { previewSequence++; preview.value = undefined; confirmed.value = false; previewBusy.value = false }, { flush: 'sync' })
watch(opened, () => { openedEligibilitySequence++; openedEligibility.value = undefined; openedEligibilityError.value = undefined; openedEligibilityBusy.value = false }, { flush: 'sync' })
watch(() => writer.error.value, value => { if (value) { preview.value = undefined; confirmed.value = false } })
watch(filter, () => { if (readable.value) void loadCatalog() })
onUnmounted(() => { alive = false; invalidateReads(); if (store.changes.get(stateKey.value) === changeState) store.changes.delete(stateKey.value) })

// 回执校验失败或权限代次变化仍属结果未知，保留原 key 与原正文，不能换键再创建。
function command<T>(path: string, method: string, body: unknown, valid: (value: unknown) => value is T, apply: (value: T) => void) {
  if (!readable.value || writer.locked.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; commandScope = scope.value
  void writer.run<unknown>(path, method, body, value => {
    let accepted = false
    try { accepted = current(commandEpoch, commandGeneration) && commandScope === scope.value && valid(value) } catch { accepted = false }
    if (!accepted) { writer.pending.value = savedCommand.value; writer.error.value = invalid(); return }
    savedCommand.value = undefined; apply(value as T)
  })
  savedCommand.value = writer.pending.value
}
function retry() {
  if (!readable.value || writer.busy.value || !writer.pending.value || commandScope !== scope.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry()
}
async function loadCatalog(cursor?: string) {
  if (!readable.value || catalogBusy.value) return
  const query: s.StandardReviewQuery = { questionId: question.value.questionId, versionId: question.value.versionId, limit: 20, ...(filter.value === 'ALL' ? {} : { status: filter.value }), ...(cursor === undefined ? {} : { cursor }) }
  const epoch = store.sessionEpoch, stamp = generation, request = ++catalogSequence, previous = catalog.value
  catalogBusy.value = true; catalogError.value = undefined; if (!cursor) catalog.value = undefined
  try {
    const value = await read<unknown>(s.standardReviewPaths.list(query))
    if (!current(epoch, stamp) || request !== catalogSequence) return
    if (!s.standardReviewPageValid(value, query)) throw invalid()
    catalog.value = cursor ? { ...value, items: [...(previous?.items ?? []), ...value.items] } : value
  } catch (reason) { if (current(epoch, stamp) && request === catalogSequence) catalogError.value = reason }
  finally { if (current(epoch, stamp) && request === catalogSequence) catalogBusy.value = false }
}
async function loadCurrent() {
  const id = selectedId.value
  if (!readable.value || !id || latestBusy.value) return
  const epoch = store.sessionEpoch, stamp = generation, request = ++latestSequence
  latestBusy.value = true; latestError.value = undefined; latest.value = undefined
  try {
    // 独立 GET 只确认当前状态，不释放未决命令，也不把当前状态当作旧命令回执。
    const value = await read<unknown>(s.standardReviewPaths.detail(id))
    if (!current(epoch, stamp) || request !== latestSequence || id !== selectedId.value) return
    if (!s.standardReviewDetailValid(value, id) || !exactQuestion(value.summary.question)) throw invalid()
    latest.value = value
    const openedState = openedEligibility.value
    if (opened.value && openedState && (openedState.currentRevision !== value.eligibility.currentRevision || openedState.currentStatus !== value.eligibility.currentStatus
      || opened.value.history.snapshot.revision === value.summary.revision && JSON.stringify(openedState) !== JSON.stringify(value.eligibility))) {
      // 两个独立读取若观察到不同状态，重新核对确切修订，不能继续展示旧资格。
      void loadOpenedEligibility()
    }
  } catch (reason) { if (current(epoch, stamp) && request === latestSequence) latestError.value = reason }
  finally { if (current(epoch, stamp) && request === latestSequence) latestBusy.value = false }
}
async function loadOpenedEligibility() {
  const receipt = opened.value
  if (!readable.value || !receipt) return
  const snapshot = receipt.history.snapshot, epoch = store.sessionEpoch, stamp = generation, request = ++openedEligibilitySequence
  openedEligibilityBusy.value = true; openedEligibilityError.value = undefined; openedEligibility.value = undefined
  const stillCurrent = () => current(epoch, stamp) && request === openedEligibilitySequence && opened.value === receipt
  try {
    // 原键恢复的是打开时回执；当前资格必须针对其确切修订单独 GET，不能覆盖历史正文。
    const value = await read<unknown>(s.standardReviewPaths.detail(snapshot.id, snapshot.revision))
    if (!stillCurrent()) return
    if (!s.standardReviewDetailValid(value, snapshot.id, snapshot.revision) || !exactQuestion(value.summary.question) || !s.standardEligibilityValid(value.eligibility, snapshot)) throw invalid()
    const knownDetail = latest.value, knownCurrent = knownDetail?.summary
    // 确切修订查询可能先于另一个标签的修订/撤回；不能让迟到资格倒退已知当前状态。
    if (knownCurrent?.id === snapshot.id && (value.eligibility.currentRevision < knownCurrent.revision
      || value.eligibility.currentRevision === knownCurrent.revision && value.eligibility.currentStatus !== knownCurrent.status
      || knownCurrent.status === 'WITHDRAWN' && value.eligibility.currentStatus !== 'WITHDRAWN')) {
      throw new ApiError(0, 'STANDARD_REVIEW_ELIGIBILITY_STALE', '资格响应早于已读取的当前修订或撤回状态，请重新核对本修订当前资格。')
    }
    if (knownDetail && knownCurrent?.id === snapshot.id && knownCurrent.revision === snapshot.revision
      && JSON.stringify(value.eligibility) !== JSON.stringify(knownDetail.eligibility)) {
      throw new ApiError(0, 'STANDARD_REVIEW_ELIGIBILITY_STALE', '本修订的来源资格与已读取的当前状态不一致，请重新读取当前状态并核对本修订资格。')
    }
    openedEligibility.value = value.eligibility
  } catch (reason) { if (stillCurrent()) openedEligibilityError.value = reason }
  finally { if (stillCurrent()) openedEligibilityBusy.value = false }
}
function refreshSelectedState() { void loadCurrent(); if (opened.value) void loadOpenedEligibility() }
async function loadHistory(before?: number) {
  const id = selectedId.value
  if (!readable.value || !id || historyBusy.value) return
  const epoch = store.sessionEpoch, stamp = generation, request = ++historySequence, previous = history.value
  const query: s.StandardHistoryQuery = { limit: 20, ...(before === undefined ? {} : { before }) }
  historyBusy.value = true; historyError.value = undefined; if (before === undefined) history.value = undefined
  try {
    const value = await read<unknown>(s.standardReviewPaths.history(id, query))
    if (!current(epoch, stamp) || request !== historySequence || id !== selectedId.value) return
    if (!s.standardHistoryPageValid(value, query)) throw invalid()
    history.value = before === undefined ? value : { ...value, items: [...(previous?.items ?? []), ...value.items] }
  } catch (reason) { if (current(epoch, stamp) && request === historySequence) historyError.value = reason }
  finally { if (current(epoch, stamp) && request === historySequence) historyBusy.value = false }
}
function resetEditor() {
  editorGeneration.value++
  draft.value = undefined; editingBase.value = undefined; baseline.value = ''; pickerDirty.value = false
  preview.value = undefined; confirmed.value = false; withdrawReason.value = ''; withdrawConfirmed.value = false; withdrawing.value = false; problem.value = ''
}
function refreshAfterSave() {
  // 提交前发出的查询不能覆盖提交后的独立读取结果。
  latestSequence++; historySequence++; catalogSequence++
  latestBusy.value = false; historyBusy.value = false; catalogBusy.value = false
  void loadCurrent(); void loadCatalog(); void loadHistory()
}
function selectRecord(id: string) {
  if (!readable.value || writer.locked.value || !store.canLeave([stateKey.value])) return
  resetEditor(); opened.value = undefined; selectedId.value = id; message.value = ''; latestSequence++; historySequence++; latestBusy.value = false; historyBusy.value = false
  void loadCurrent(); void loadHistory()
}
function create() {
  if (!readable.value || writer.locked.value || !checkpointOrder.value.length || !s.standardReviewIdValid(props.reference.exposureId) || !store.canLeave([stateKey.value])) return
  resetEditor(); opened.value = undefined; selectedId.value = undefined; latest.value = undefined; history.value = undefined; latestSequence++; historySequence++
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  draft.value = s.emptyStandardReviewDraft(question.value, props.reference.exposureId, zone); baseline.value = JSON.stringify(draft.value); message.value = ''
}
function edit() {
  const value = opened.value
  if (!readable.value || writer.locked.value || !value || !editableOpened.value || !store.canLeave([stateKey.value])) return
  resetEditor(); const snapshot = value.history.snapshot
  editingBase.value = clone(snapshot)
  draft.value = { id: snapshot.id, expectedRevision: snapshot.revision, question: clone(snapshot.question), referenceExposureId: props.reference.exposureId, content: clone(snapshot.content), changeReason: '' }
  baseline.value = JSON.stringify(draft.value); message.value = ''; void loadCurrent()
}
function closeEditor() { if (readable.value && !writer.locked.value && store.canLeave([stateKey.value])) resetEditor() }
function openRevision(revision: number, keepInput = false) {
  const id = selectedId.value
  if (!readable.value || writer.locked.value || !id || !s.standardReviewRevisionValid(revision)) return
  if (!keepInput && !store.canLeave([stateKey.value])) return
  if (keepInput && (!draft.value || draft.value.id !== id || latest.value?.summary.revision !== revision || latest.value.summary.status !== 'ACTIVE')) return
  const body = { revision }
  command(s.standardReviewPaths.open(id), 'POST', body, (value): value is s.OpenedStandardReview => s.openedStandardReviewValid(value, id, body) && exactQuestion(value.history.snapshot.question), value => {
    opened.value = value
    if (!keepInput) { resetEditor(); message.value = '已打开所选确切修订，并记录本次参考查看；当前资格正在独立核对。' }
    else message.value = '所选修订的历史原文已打开，输入仍保留。当前资格核对成功后，可明确采用该修订作为编辑基线。'
    refreshSelectedState()
  })
}
function adoptOpened() {
  const receipt = opened.value
  if (!readable.value || writer.locked.value || !canAdoptOpened.value || !receipt || !draft.value) return
  editingBase.value = clone(receipt.history.snapshot)
  draft.value = { ...draft.value, expectedRevision: receipt.history.snapshot.revision, referenceExposureId: receipt.exposureId }
  message.value = '已采用独立核对的修订作为编辑基线；本地内容保留，请核对差异并重新预览。'
}
function draftProblem(): string {
  const value = draft.value
  if (!value) return '请先创建或编辑审阅。'
  if (!s.standardReviewTextValid(value.content.reviewer.label, 120) || !s.standardReviewTextValid(value.content.reviewer.description)) return '请补全审阅者称谓与身份说明。'
  if (!s.standardReviewDateValid(value.content.reviewedOn) || !s.standardReviewZoneValid(value.content.reviewZoneId)) return '请填写合法审阅日期和 IANA 时区（如 Asia/Shanghai 或 UTC）。'
  if (!s.standardReviewTextValid(value.content.reviewContext) || !s.standardReviewTextValid(value.content.limitations)) return '请补全审阅背景与整份审阅限制，每项 1–2000 个字符。'
  if (!value.content.checkpoints.length) return '请选择至少一个本次实际审阅的检查项。'
  if (!value.content.evidence.length || value.content.evidence.length > 12) return '请添加 1–12 条来源证据。'
  if (value.content.evidence.some(row => !s.reviewEvidenceValid(row, value.content.reviewZoneId))) return '请补全每条证据的角色、取得经过、使用理由及合法来源内容。'
  if (value.content.checkpoints.some(row => !s.standardCheckpointReviewValid(row))) return '请补全每个检查项的条件、结论、限制，并选择至少一条证据。'
  for (const row of value.content.checkpoints) { const roles = new Set(row.evidenceIds.flatMap(id => value.content.evidence.find(item => item.id === id)?.roles ?? [])); if (roles.size !== 2) return `“${pointLabel(row.checkpointId)}”的证据需同时覆盖真实审阅记录与技术依据。` }
  if (value.content.evidence.some(row => !value.content.checkpoints.some(point => point.evidenceIds.includes(row.id)))) return '每条证据都需至少关联一个检查项，请关联或移除未使用证据。'
  if (value.id && !s.standardReviewTextValid(value.changeReason)) return '修改已有审阅需填写本次修改理由。'
  return s.standardReviewDraftValid(value, checkpointOrder.value) ? '' : '请核对重复来源、材料摘录数量、文字长度与检查项顺序；输入尚不符合审阅契约。'
}
async function makePreview() {
  if (!readable.value || writer.locked.value || previewBusy.value || !draft.value) return
  if (pickerDirty.value) { problem.value = '来源选择中还有尚未加入的输入，请先加入证据或清空本次来源输入。'; return }
  problem.value = draftProblem(); if (problem.value) return
  if (draft.value.id && (!latest.value || mismatch.value)) { problem.value = '请读取当前摘要。若当前修订已变化，明确打开当前修订后再采用编辑基线。'; return }
  const body = clone(draft.value), epoch = store.sessionEpoch, stamp = generation, request = ++previewSequence
  previewBusy.value = true; previewError.value = undefined; preview.value = undefined; confirmed.value = false
  try {
    const value = await postPreview<unknown>(s.standardReviewPaths.preview, body)
    if (!current(epoch, stamp) || request !== previewSequence || JSON.stringify(draft.value) !== JSON.stringify(body)) return
    if (!s.standardReviewPreviewValid(value, body, checkpointOrder.value)) throw invalid()
    preview.value = value
  } catch (reason) { if (current(epoch, stamp) && request === previewSequence) previewError.value = reason }
  finally { if (current(epoch, stamp) && request === previewSequence) previewBusy.value = false }
}
function save() {
  if (pickerDirty.value) { problem.value = '来源选择中还有尚未加入的输入，请先加入证据或清空本次来源输入，然后重新预览。'; return }
  if (!readable.value || writer.locked.value || !preview.value || !draft.value || !confirmed.value) return
  const selected = clone(preview.value), body: s.SaveStandardReview = { draft: clone(draft.value), expectedPreviewHash: selected.previewHash }, previous = editingBase.value ? clone(editingBase.value) : undefined
  if (!s.saveStandardReviewValid(body, selected)) { problem.value = '预览后输入已经变化，请重新预览。'; return }
  command(body.draft.id ? s.standardReviewPaths.update(body.draft.id) : s.standardReviewPaths.create, body.draft.id ? 'PUT' : 'POST', body,
    (value): value is s.StandardReviewSnapshot => s.savedStandardReviewValid(value, body, previous, selected), value => {
      selectedId.value = value.id; editingBase.value = clone(value); opened.value = undefined
      draft.value = { id: value.id, expectedRevision: value.revision, question: clone(value.question), referenceExposureId: body.draft.referenceExposureId, content: clone(value.content), changeReason: '' }
      baseline.value = JSON.stringify(draft.value); preview.value = undefined; confirmed.value = false
      message.value = `原请求保存回执已核对（修订 ${value.revision}）。下方独立读取当前状态，回执不代表记录此刻仍是最新修订。`
      refreshAfterSave()
    })
}
function beginWithdraw() {
  if (!readable.value || writer.locked.value || !latest.value || latest.value.summary.status !== 'ACTIVE' || !store.canLeave([stateKey.value])) return
  resetEditor(); withdrawing.value = true
}
function withdraw() {
  const selected = latest.value?.summary
  if (!readable.value || writer.locked.value || !selected || selected.status !== 'ACTIVE' || !withdrawConfirmed.value) return
  const body: s.WithdrawRecord = { expectedRevision: selected.revision, reason: withdrawReason.value }
  if (!s.withdrawRecordValid(body)) { problem.value = '撤回理由需为 1–2000 个字符。'; return }
  command(s.standardReviewPaths.withdraw(selected.id), 'POST', body, (value): value is s.StandardRevisionSummary => s.withdrawnStandardReviewValid(value, body, selected), value => {
    resetEditor(); opened.value = undefined; message.value = `撤回回执已核对（修订 ${value.revision}）；记录撤回后不能恢复。`
    refreshAfterSave()
  })
}
if (readable.value) void loadCatalog()
</script>

<template>
  <section class="standard-review-workspace" aria-label="确切标准版本的审阅记录">
    <header><div><h4>标准审阅记录</h4><p class="field-help">针对上方确切版本保存真实审阅经过、逐项意见与依据。各份记录独立保留，不合并成平台认证结论。</p></div><NButton :disabled="!readable || writer.locked.value || !reference.checkpoints.length" @click="create">记录一次实际审阅</NButton></header>
    <p v-if="!reference.checkpoints.length" class="notice">本版本没有检查项。需要先正常编辑题目生成含检查项的新版本，再记录对应审阅。</p>
    <p v-if="!readable" class="notice" role="status">{{ disabled ? '父级正在处理参考请求，审阅操作暂时禁用。' : '当前资料访问尚不可用，请先恢复登录或明确取得资料访问权限。' }}本地输入和未决命令已保留。</p>
    <div v-show="readable" class="workspace-content">
      <div class="catalog-toolbar"><NSelect v-model:value="filter" :disabled="!readable || catalogBusy" :options="[{value:'ALL',label:'全部审阅记录'},{value:'ACTIVE',label:'未撤回记录'},{value:'WITHDRAWN',label:'已撤回记录'}]" aria-label="审阅记录状态" /><NButton :disabled="!readable || catalogBusy" :loading="catalogBusy" @click="loadCatalog()">刷新目录</NButton></div>
      <NSkeleton v-if="catalogBusy && !catalog" text :repeat="3" /><p v-if="catalogError" class="notice error" role="alert">{{ s.standardReviewErrorText(catalogError) }}</p>
      <p v-if="catalog && !catalog.items.length" class="field-help">这个确切版本在当前筛选下没有审阅记录。有实际审阅材料时，可从“记录一次实际审阅”开始。</p>
      <div class="review-catalog"><article v-for="entry in catalog?.items" :key="entry.summary.id" :class="['summary-row',{selected:selectedId===entry.summary.id}]"><div><strong>{{ entry.summary.reviewerLabel }}</strong><p class="field-help">{{ entry.summary.reviewedOn }} · 修订 {{ entry.summary.revision }} · {{ entry.summary.status === 'WITHDRAWN' ? '已撤回' : '未撤回' }} · {{ entry.summary.checkpointIds.length }} 个检查项</p><p class="field-help">{{ entry.eligibility.checkpoints.some(row=>row.eligible) ? '部分检查项当前具备有限支持资格' : '当前没有具备支持资格的检查项' }}</p></div><NButton size="small" :disabled="!readable || writer.locked.value" @click="selectRecord(entry.summary.id)">查看摘要与修订目录</NButton></article></div>
      <NButton v-if="catalog?.nextCursor" :disabled="!readable || catalogBusy" @click="loadCatalog(catalog.nextCursor)">读取更多记录</NButton>
      <section v-if="selectedId" class="selected-record" aria-label="选定记录的当前摘要"><header><h5>当前状态与不可变历史</h5><NButton size="small" :disabled="!readable || latestBusy || openedEligibilityBusy" :loading="latestBusy || openedEligibilityBusy" @click="refreshSelectedState">独立读取当前状态</NButton></header>
        <p v-if="latestError" class="notice error" role="alert">{{ s.standardReviewErrorText(latestError) }}</p><NSkeleton v-if="latestBusy" text :repeat="2" />
        <template v-if="latest"><p>{{ latest.summary.reviewerLabel }} · 当前修订 {{ latest.summary.revision }} · {{ latest.summary.status === 'WITHDRAWN' ? '已撤回，终态' : '未撤回' }}</p><div class="row-actions"><NButton :disabled="!readable || writer.locked.value" @click="openRevision(latest.summary.revision)">打开当前修订并记录查看</NButton><NButton v-if="draft?.id && mismatch && latest.summary.status === 'ACTIVE'" :disabled="!readable || writer.locked.value" @click="openRevision(latest.summary.revision,true)">打开当前修订并保留输入</NButton><NButton v-if="latest.summary.status === 'ACTIVE'" :disabled="!readable || writer.locked.value" @click="beginWithdraw">撤回这份记录</NButton></div><p v-if="mismatch" class="notice">当前状态与编辑基线不同。本地内容已保留；继续编辑前需明确打开并采用当前修订。</p></template>
        <details><summary>修订目录（仅元数据）</summary><NButton size="small" :disabled="!readable || historyBusy" @click="loadHistory()">刷新修订目录</NButton><p v-if="historyError" class="notice error" role="alert">{{ s.standardReviewErrorText(historyError) }}</p><div v-for="row in history?.items" :key="row.revision" class="history-row"><p>修订 {{ row.revision }} · {{ s.recordChangeLabels[row.change] }} · {{ new Date(row.revisedAt).toLocaleString('zh-CN') }}</p><NButton text size="small" :disabled="!readable || writer.locked.value" @click="openRevision(row.revision)">打开这一修订并记录查看</NButton></div><NButton v-if="history?.nextBefore != null" size="small" :disabled="!readable || historyBusy" @click="loadHistory(history.nextBefore)">读取更早修订</NButton></details>
      </section>
      <section v-if="opened" class="opened-record"><div class="row-actions"><NButton size="small" :disabled="!readable || openedEligibilityBusy" :loading="openedEligibilityBusy" @click="loadOpenedEligibility">独立核对本修订当前资格</NButton><NButton v-if="draft?.id === opened.history.snapshot.id && draft?.expectedRevision !== opened.history.snapshot.revision" :disabled="!readable || writer.locked.value || !canAdoptOpened" @click="adoptOpened">保留输入，采用已核对修订</NButton></div><p v-if="openedEligibilityError" class="notice error" role="alert">{{ s.standardReviewErrorText(openedEligibilityError) }} 本修订当前资格尚未确认，打开回执中的资格仅为历史状态。</p><StandardReviewReadout :value="opened" :current-eligibility="openedEligibility" :checkpoints="reference.checkpoints" :disabled="!readable" /><NButton v-if="!draft" :disabled="!readable || writer.locked.value || !editableOpened" @click="edit">编辑这份当前审阅</NButton></section>
      <section v-if="draft" class="editor-section" aria-label="编辑审阅记录"><header><h5>{{ draft.id ? `修改审阅 · 基于修订 ${draft.expectedRevision}` : '记录实际审阅' }}</h5><NButton size="small" :disabled="writer.locked.value" @click="closeEditor">取消本地编辑</NButton></header>
        <StandardReviewForm :key="editorGeneration" :draft="draft" :checkpoints="reference.checkpoints" :disabled="!readable || writer.locked.value || previewBusy" @picker-dirty="pickerDirty = $event" />
        <div class="row-actions"><NButton :disabled="!readable || writer.locked.value || previewBusy || mismatch" :loading="previewBusy" @click="makePreview">预览依据与检查项资格</NButton></div>
        <p v-if="previewError" class="notice error" role="alert">{{ s.standardReviewErrorText(previewError) }} 输入已保留；修订冲突请读取当前状态后核对。</p>
        <NSkeleton v-if="previewBusy" text :repeat="3" />
        <div v-if="preview" class="preview-result"><h5>本次保存预览</h5><ul><li v-for="row in preview.checkpoints" :key="row.checkpointId"><strong>{{ pointLabel(row.checkpointId) }}</strong>：{{ row.eligible ? '可形成限定范围内的支持记录' : row.reasons.map(reason=>s.eligibilityReasonLabels[reason]).join('、') }}</li></ul><ul><li v-for="(row,index) in preview.evidenceChecks" :key="row.evidenceId">证据 {{ index + 1 }}：{{ s.basisStateLabels[row.state] }} · {{ row.quoteMatches === true ? '保存引文与原文一致' : row.state === 'EXTERNAL_RECORDED' ? '外部摘录未抓取核验' : '当前未核对引文匹配' }}</li></ul><ul><li v-for="limit in preview.limitations" :key="limit">{{ s.reviewLimitationLabels[limit] }}</li></ul><NCheckbox v-model:checked="confirmed" :disabled="!readable || writer.locked.value">我已核对本次预览、审阅范围、证据角色与限制</NCheckbox><NButton type="primary" :disabled="!readable || writer.locked.value || !confirmed" @click="save">确认保存这份审阅</NButton></div>
      </section>
      <section v-if="withdrawing" class="withdraw-section"><h5>撤回审阅记录</h5><p class="field-help">撤回追加不可变修订，保留历史；撤回后不能恢复，也不会改写题目、训练或旧评价。</p><label>撤回理由<NInput v-model:value="withdrawReason" type="textarea" :maxlength="2000" :autosize="{minRows:3,maxRows:6}" :disabled="!readable || writer.locked.value" /></label><NCheckbox v-model:checked="withdrawConfirmed" :disabled="!readable || writer.locked.value">我确认撤回这份记录，并保留原历史</NCheckbox><div class="row-actions"><NButton type="error" :disabled="!readable || writer.locked.value || !withdrawConfirmed" @click="withdraw">确认撤回</NButton><NButton :disabled="writer.locked.value" @click="closeEditor">取消撤回</NButton></div></section>
    </div>
    <p v-if="problem && readable" class="notice error" role="alert">{{ problem }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ s.standardReviewErrorText(writer.error.value) }}{{ writer.pending.value ? ' 原请求与输入已保留；独立读取当前状态不会替代原请求恢复。' : ' 输入保留，请核对当前状态并重新预览。' }}</p><p v-if="message && readable" class="notice" role="status">{{ message }}</p>
    <NButton v-if="writer.pending.value" type="primary" :disabled="!readable || writer.busy.value" :loading="writer.busy.value" @click="retry">用原请求核对结果</NButton>
  </section>
</template>

<style scoped>
.standard-review-workspace,.workspace-content { display:grid; gap:18px; min-width:0; }.standard-review-workspace { border-top:1px solid var(--line); margin-top:10px; padding-top:24px; }header { display:flex; align-items:start; justify-content:space-between; flex-wrap:wrap; gap:14px; }header>div { min-width:0; flex:1; }header p { margin-top:8px; max-width:75ch; }h4 { margin:0; font-size:16px; }h5 { margin:0; font-size:14px; }.catalog-toolbar { display:flex; align-items:center; gap:10px; }.catalog-toolbar>.n-select { width:190px; }.summary-row { display:flex; align-items:center; justify-content:space-between; gap:15px; padding:15px 0; border-bottom:1px solid var(--line); }.summary-row>div { min-width:0; }.summary-row strong { font-size:14px; }.summary-row p { margin-top:6px; }.summary-row.selected { background:var(--surface-soft); padding-inline:10px; }.selected-record,.opened-record,.editor-section,.withdraw-section { display:grid; gap:16px; min-width:0; padding-top:20px; border-top:1px solid var(--line); }.history-row { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:8px; padding:10px 0; border-bottom:1px solid var(--line); }details { font-size:12px; }summary { cursor:pointer; margin-bottom:12px; }.preview-result { display:grid; gap:14px; background:var(--surface-soft); border-radius:6px; padding:16px; }.preview-result ul { padding-left:20px; margin:0; }.preview-result li { margin:6px 0; font-size:13px; overflow-wrap:anywhere; }.preview-result>.n-button,.opened-record>.n-button,.workspace-content>.n-button { justify-self:start; }label { display:grid; gap:7px; font-size:13px; }@media(max-width:620px) { .summary-row { align-items:start; flex-direction:column; }.catalog-toolbar { flex-wrap:wrap; }.catalog-toolbar>.n-select { width:100%; } }
</style>
