<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCheckbox, NInput } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../../api/client'
import type { ExpressionBasisReviewBasis, ExpressionBasisReviewConfirm, ExpressionBasisReviewItem, ExpressionBasisReviewPage,
  ExpressionBasisReviewState } from '../../api/expressionBasisReview'
import { expressionBasisReviewConfirmValid, expressionBasisReviewIdValid, expressionBasisReviewItemKey, expressionBasisReviewKindLabels,
  expressionBasisReviewNormalizeReason, expressionBasisReviewPageValid, expressionBasisReviewPaths, expressionBasisReviewReasonValid, expressionBasisReviewReceiptMatches,
  expressionBasisReviewSourceLabels, expressionBasisReviewStateValid } from '../../api/expressionBasisReview'
import { useCommand } from '../../composables/useCommand'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ item: ExpressionBasisReviewItem; visible: boolean; editing: boolean; parentLocked: boolean; stale?: boolean }>()
const emit = defineEmits<{ changed: []; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter(), writer = useCommand(), viewId = useId()
const boundId = props.item.id, stateKey = `expression-basis-review:${viewId}`, panelId = `expression-basis-review-${viewId}`
const documentVisible = ref(document.visibilityState === 'visible')
const current = shallowRef<ExpressionBasisReviewState>(), history = shallowRef<ExpressionBasisReviewPage>()
const stateLoading = ref(false), historyLoading = ref(false), historyOpened = ref(false)
const stateProblem = ref(''), historyProblem = ref(''), navigationProblem = ref(''), message = ref('')
const reason = ref(''), agreed = ref(false), opening = ref(false)
const normalizedReason = computed(() => expressionBasisReviewNormalizeReason(reason.value))
const before = ref<number>(), cursors = ref<(number | undefined)[]>([])
const itemKey = computed(() => expressionBasisReviewItemKey(props.item))
const readable = computed(() => Boolean(itemKey.value) && props.item.id === boundId && props.visible && documentVisible.value
  && store.sessionActive && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const dirty = computed(() => Boolean(reason.value || agreed.value))
const inputProblem = computed(() => !readable.value ? '查看条件未就绪，请回到本表达确认资料访问条件。'
  : props.parentLocked ? '材料保存尚未确认，请先等待或恢复原保存请求。'
    : props.editing ? '正文有未保存修改，请先保存或处理本地草稿。'
      : props.stale ? '材料修订尚未核对，请先重新读取材料详情。'
        : !current.value ? '请先主动读取当前依据并逐项核对。'
          : current.value.status !== 'ACTIVE' ? '表达已归档，恢复后须重新读取并核对。'
            : !current.value.canConfirm ? '当前依据不可确认，请先核对事实及所属项目，必要时编辑并保存新的表达版本。'
              : !expressionBasisReviewReasonValid(normalizedReason.value) ? '请填写 1–1000 字理由，不能含 NUL 或无效字符。'
                : !agreed.value ? '请核对完整事实依据和当前正文，再勾选本次确认。' : '')
const pending = computed(() => writer.locked.value)
let alive = true, generation = 0, stateSequence = 0, historySequence = 0
let commandEpoch = store.sessionEpoch, commandAccessEpoch = currentTrainingAccessEpoch(), commandGeneration = 0

watch([dirty, pending], ([unsaved, locked]) => {
  store.changes.set(stateKey, { dirty: unsaved, locked }); emit('state', { dirty: unsaved, locked })
}, { immediate: true, flush: 'sync' })
watch([readable, itemKey, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode,
  () => access.active?.materialAccess], invalidate, { flush: 'sync' })
watch([() => props.editing, () => props.parentLocked, () => props.stale], () => {
  generation++; clearCurrent(); agreed.value = false
}, { flush: 'sync' })
watch(reason, () => { agreed.value = false }, { flush: 'sync' })
watch(writer.error, value => {
  if (!value) return
  clearCurrent(); agreed.value = false
  if (value instanceof ApiError && value.status === 409 && !writer.pending.value) emit('changed')
})
function visibilityChanged() { documentVisible.value = document.visibilityState === 'visible' }
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onUnmounted(() => {
  alive = false; invalidate(); document.removeEventListener('visibilitychange', visibilityChanged)
  store.changes.delete(stateKey); emit('state', { dirty: false, locked: false })
})
function clearCurrent() { stateSequence++; current.value = undefined; stateLoading.value = false; stateProblem.value = '' }
function invalidate() {
  generation++; clearCurrent(); historySequence++; history.value = undefined; historyLoading.value = false
  before.value = undefined; cursors.value = []; historyProblem.value = ''; navigationProblem.value = ''; message.value = ''; agreed.value = false
  // 访问或对象变化清展示和资格；理由与 useCommand 中的原正文、原幂等键持续保留。
}
async function loadCurrent() {
  if (!readable.value || props.parentLocked || writer.locked.value || stateLoading.value) return
  clearCurrent(); agreed.value = false; message.value = ''
  const item = props.item, key = itemKey.value, epoch = store.sessionEpoch, accessStamp = currentTrainingAccessEpoch()
  const stamp = generation, ticket = ++stateSequence
  stateLoading.value = true
  const active = () => alive && readable.value && epoch === store.sessionEpoch && accessStamp === currentTrainingAccessEpoch()
    && stamp === generation && ticket === stateSequence && key === itemKey.value
  try {
    const value = await read<unknown>(expressionBasisReviewPaths.state(boundId))
    if (!active()) return
    if (!expressionBasisReviewStateValid(value, item)) {
      throw new ApiError(502, 'EXPRESSION_BASIS_REVIEW_STATE_INVALID', '表达修订、事实依据或当前状态无法核对，请重新读取材料后再试。')
    }
    current.value = value
  } catch (error) { if (active()) stateProblem.value = errorText(error) }
  finally { if (alive && ticket === stateSequence) stateLoading.value = false }
}
async function loadHistory(nextBefore?: number, stack: (number | undefined)[] = []) {
  if (!readable.value || props.parentLocked || writer.locked.value || historyLoading.value) return
  historySequence++; history.value = undefined; historyLoading.value = true; historyProblem.value = ''; agreed.value = false
  const epoch = store.sessionEpoch, accessStamp = currentTrainingAccessEpoch(), stamp = generation, ticket = historySequence, key = itemKey.value
  before.value = nextBefore; cursors.value = [...stack]
  const active = () => alive && readable.value && historyOpened.value && epoch === store.sessionEpoch
    && accessStamp === currentTrainingAccessEpoch() && stamp === generation && ticket === historySequence && key === itemKey.value
  try {
    const value = await read<unknown>(expressionBasisReviewPaths.page(boundId, nextBefore))
    if (!active()) return
    if (!expressionBasisReviewPageValid(value, boundId, nextBefore)) {
      throw new ApiError(502, 'EXPRESSION_BASIS_REVIEW_PAGE_INVALID', '依据复核历史的身份、顺序或分页无法核对，请重读这一页。')
    }
    history.value = value
  } catch (error) { if (active()) historyProblem.value = errorText(error) }
  finally { if (alive && ticket === historySequence) historyLoading.value = false }
}
function toggleHistory() {
  if (!readable.value || props.parentLocked || writer.locked.value) return
  historyOpened.value = !historyOpened.value
  historySequence++; history.value = undefined; historyLoading.value = false; historyProblem.value = ''
  if (historyOpened.value) void loadHistory()
}
function older() {
  if (history.value?.nextCursor !== null && history.value?.nextCursor !== undefined) {
    void loadHistory(history.value.nextCursor, [...cursors.value, before.value])
  }
}
function newer() { if (cursors.value.length) void loadHistory(cursors.value.at(-1), cursors.value.slice(0, -1)) }
function save() {
  const state = current.value
  if (inputProblem.value || writer.locked.value || stateLoading.value || !state) return
  const original = JSON.parse(JSON.stringify(state)) as ExpressionBasisReviewState
  const basisChecks: ExpressionBasisReviewConfirm['basisChecks'] = []
  for (const { check } of state.basis) {
    if (check.state !== 'CURRENT' || check.sourceGeneration === null || check.projectGeneration === null) return
    basisChecks.push({ ...check, kind: 'FACT', state: 'CURRENT', sourceGeneration: check.sourceGeneration, projectGeneration: check.projectGeneration })
  }
  const body: ExpressionBasisReviewConfirm = { expectedItemRevision: state.itemRevision, expectedVersionId: state.versionId,
    expectedRevision: state.revision, basisChecks, reason: normalizedReason.value, confirmed: true }
  if (!expressionBasisReviewConfirmValid(body, original)) return
  commandEpoch = store.sessionEpoch; commandAccessEpoch = currentTrainingAccessEpoch(); commandGeneration = generation
  agreed.value = false; message.value = ''
  void writer.run<unknown>(expressionBasisReviewPaths.confirm(boundId), 'POST', body, value => {
    if (!alive || !readable.value || commandEpoch !== store.sessionEpoch || commandAccessEpoch !== currentTrainingAccessEpoch()
      || commandGeneration !== generation || !expressionBasisReviewReceiptMatches(value, boundId, original, body)) {
      throw new ApiError(0, 'EXPRESSION_BASIS_REVIEW_RECEIPT_UNCERTAIN', '访问条件变化或确认回执无法核对。理由、原请求和原键已保留，请用原请求核对结果。')
    }
    invalidate(); reason.value = ''
    message.value = `已保存表达 v${value.versionNumber} 的依据确认（记录 ${value.revision}）。这只证明当时操作成功；请重新读取当前依据。正文未改写，旧 AI 任务和授权不会恢复。`
    emit('changed')
  })
}
function retry() {
  if (!readable.value || props.parentLocked || writer.busy.value || !writer.pending.value) return
  commandEpoch = store.sessionEpoch; commandAccessEpoch = currentTrainingAccessEpoch(); commandGeneration = generation
  void writer.retry()
}
async function open(itemId: string, kind: 'FACT' | 'PROJECT' | 'EXPRESSION', versionId?: string) {
  if (!readable.value || writer.locked.value || props.parentLocked || opening.value || !expressionBasisReviewIdValid(itemId)
    || versionId !== undefined && !expressionBasisReviewIdValid(versionId)) return
  const epoch = store.sessionEpoch, stamp = generation, accessStamp = currentTrainingAccessEpoch()
  opening.value = true; navigationProblem.value = ''
  try {
    const failure = await router.push({ name: 'prepare', query: { kind, item: itemId, version: versionId } })
    if (failure && alive && readable.value && epoch === store.sessionEpoch && stamp === generation && accessStamp === currentTrainingAccessEpoch()) {
      navigationProblem.value = '页面未切换，请先处理保留的输入或资料访问条件。'
    }
  } catch {
    if (alive && readable.value && epoch === store.sessionEpoch && stamp === generation && accessStamp === currentTrainingAccessEpoch()) {
      navigationProblem.value = '暂时无法打开此版本，请稍后重试。'
    }
  } finally { opening.value = false }
}
function checkText(basis: ExpressionBasisReviewBasis) {
  return `${expressionBasisReviewSourceLabels[basis.check.state]} · 事实代次 ${basis.check.sourceGeneration ?? '未知'} · 项目代次 ${basis.check.projectGeneration ?? '未知'}`
}
function date(value: string) { return new Date(value).toLocaleString('zh-CN') }
</script>

<template>
  <section v-show="visible" class="expression-basis-review" aria-label="表达事实依据复核" :inert="!readable || undefined">
    <header><div><h3>表达事实依据复核</h3><p class="field-help">核对事实与所属项目的变化；保留正文的确认不认证表达内容正确，也不恢复旧模型授权。</p></div><NButton size="small" :loading="stateLoading" :disabled="!readable || parentLocked || pending || stateLoading" :aria-controls="`${panelId}-current`" @click="loadCurrent">{{ current ? '刷新当前依据' : '读取当前依据' }}</NButton></header>
    <p v-if="!readable" class="notice" role="status">查看条件未就绪，已读依据与历史已清除；处理理由和未决原请求继续保留。</p>
    <template v-if="readable">
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text size="small" :disabled="parentLocked || writer.busy.value" :loading="writer.busy.value" @click="retry">用原请求核对结果</NButton></p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="stateProblem" class="notice error" role="alert">{{ stateProblem }}<NButton text size="small" :disabled="parentLocked || pending || stateLoading" @click="loadCurrent">重读当前依据</NButton></p>
      <p v-if="navigationProblem" class="notice error" role="alert">{{ navigationProblem }}</p>
      <p v-if="stateLoading" class="field-help" role="status">正在核对固定事实版本与当前状态…</p>
      <section v-if="current" :id="`${panelId}-current`" class="basis-current" aria-label="保存基线与当前事实对照">
        <p role="status">{{ current.reviewRequired ? '表达依据待复核。即使事实已恢复有效，旧基线也不会自动恢复。' : '本次读取的事实与已登记基线一致；后续变化仍需重新核对。' }}</p>
        <p class="field-help">表达 v{{ current.versionNumber }} · 元数据修订 {{ current.itemRevision }} · 最后复核序号 {{ current.revision }} · {{ current.status === 'ACTIVE' ? '有效' : '已归档' }}</p>
        <p v-if="!current.latest" class="notice">本表达版本没有可证明的历史基线。未补造过去已核对的记录；可核对当前有效事实后确认，或编辑并保存新表达版本。</p>
        <p v-else class="field-help">最近基线：{{ expressionBasisReviewKindLabels[current.latest.kind] }} · 记录 {{ current.latest.revision }} · <time :datetime="current.latest.createdAt">{{ date(current.latest.createdAt) }}</time></p>
        <ol class="basis-list">
          <li v-for="(basis, index) in current.basis" :key="basis.versionId">
            <strong>{{ index + 1 }}. {{ basis.title }} · v{{ basis.versionNumber }}</strong>
            <p class="identity-text">事实 {{ basis.itemId }}<br />固定版本 {{ basis.versionId }}<br />所属项目 {{ basis.projectId }}</p>
            <dl><dt>最近基线</dt><dd>{{ current.latest?.basis[index] ? checkText(current.latest.basis[index]!) : '尚无基线' }}</dd><dt>当前检查</dt><dd>{{ checkText(basis) }}</dd></dl>
            <div class="row-actions"><NButton text size="small" :disabled="pending || parentLocked || opening" @click="open(basis.itemId, 'FACT', basis.versionId)">查看固定事实 v{{ basis.versionNumber }}</NButton><NButton text size="small" :disabled="pending || parentLocked || opening" @click="open(basis.projectId, 'PROJECT')">查看所属项目</NButton></div>
          </li>
        </ol>
      </section>
      <section class="basis-confirm" aria-label="保留当前表达正文并确认依据">
        <h4>保留当前正文并确认依据</h4>
        <p class="field-help">逐项核对表达与确切事实。确认只新增依据记录并推进材料修订，不创建正文版本。来源不可用时，先处理事实或项目，再编辑表达选择当前事实。</p>
        <label :for="`${panelId}-reason`">复核理由<NInput :id="`${panelId}-reason`" v-model:value="reason" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="pending" placeholder="说明本次核对结论，以及保留当前表达正文的依据。" /><span class="field-help">{{ Array.from(normalizedReason).length }} / 1000 字</span></label>
        <NCheckbox v-model:checked="agreed" :disabled="pending || parentLocked || editing || stale || !current?.canConfirm || stateLoading || !expressionBasisReviewReasonValid(normalizedReason)">我已逐项核对当前事实依据和表达正文，确认保存本次依据复核</NCheckbox>
        <div class="row-actions"><NButton type="primary" size="small" :loading="writer.busy.value" :disabled="pending || stateLoading || !!inputProblem" @click="save">确认并保留正文</NButton><NButton v-if="reason" text size="small" :disabled="pending" @click="reason = ''; agreed = false">清空未保存理由</NButton></div>
        <p v-if="inputProblem" class="field-help" role="status">{{ inputProblem }}</p>
      </section>
      <section class="basis-history" aria-label="表达依据复核历史">
        <header><h4>依据复核历史</h4><NButton size="small" :disabled="parentLocked || pending" :aria-expanded="historyOpened" :aria-controls="`${panelId}-history`" @click="toggleHistory">{{ historyOpened ? '收起复核历史' : '查看复核历史' }}</NButton></header>
        <div v-if="historyOpened" :id="`${panelId}-history`" class="history-content" :aria-busy="historyLoading">
          <p class="field-help">历史保留当时的标题、事实和项目代次；历史成功不代表当前依据有效。</p>
          <p v-if="historyLoading" class="field-help" role="status">正在读取依据复核历史…</p>
          <p v-if="historyProblem" class="notice error" role="alert">{{ historyProblem }}<NButton text size="small" :disabled="parentLocked || pending || historyLoading" @click="loadHistory(before, cursors)">重读这一页</NButton></p>
          <template v-if="history">
            <p v-if="!history.items.length" class="field-help">尚无已登记的依据基线。升级前的历史不会补造为已核对记录。</p>
            <article v-for="record in history.items" :key="record.id" class="history-record">
              <header><strong>{{ expressionBasisReviewKindLabels[record.kind] }} · 表达 v{{ record.versionNumber }} · 记录 {{ record.revision }}</strong><time :datetime="record.createdAt">{{ date(record.createdAt) }}</time></header>
              <p class="identity-text">记录 {{ record.id }} · 当时材料修订 {{ record.itemRevision }}</p>
              <p v-if="record.reason !== null" class="reason-text">{{ record.reason }}</p>
              <NButton text size="small" :disabled="pending || parentLocked || opening" @click="open(record.itemId, 'EXPRESSION', record.versionId)">查看当时表达 v{{ record.versionNumber }}</NButton>
              <ol class="basis-list"><li v-for="basis in record.basis" :key="basis.versionId"><strong>{{ basis.title }} · v{{ basis.versionNumber }}</strong><p class="field-help">{{ checkText(basis) }}</p><p class="identity-text">事实 {{ basis.itemId }} · 版本 {{ basis.versionId }}<br />所属项目 {{ basis.projectId }}</p><div class="row-actions"><NButton text size="small" :disabled="pending || parentLocked || opening" @click="open(basis.itemId, 'FACT', basis.versionId)">查看当时事实 v{{ basis.versionNumber }}</NButton><NButton text size="small" :disabled="pending || parentLocked || opening" @click="open(basis.projectId, 'PROJECT')">查看所属项目</NButton></div></li></ol>
            </article>
          </template>
          <nav v-if="cursors.length || history?.nextCursor != null" class="row-actions" aria-label="依据复核历史分页"><NButton size="small" :disabled="!cursors.length || parentLocked || pending || historyLoading" @click="newer">较新记录</NButton><NButton size="small" :disabled="history?.nextCursor == null || parentLocked || pending || historyLoading" @click="older">更早记录</NButton><span class="field-help">第 {{ cursors.length + 1 }} 页</span></nav>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.expression-basis-review, .basis-current, .basis-confirm, .basis-history, .history-content { display: grid; gap: 12px; min-width: 0; }
.expression-basis-review { padding: 18px 0; border-bottom: 1px solid var(--line); }
header { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: start; gap: 9px 14px; }
header > div { display: grid; gap: 7px; min-width: 0; }
h3, h4, p { margin: 0; overflow-wrap: anywhere; } h3 { font-size: 15px; } h4, strong { font-size: 13px; }
label { display: grid; gap: 8px; min-width: 0; font-size: 13px; }
.basis-list { display: grid; gap: 12px; list-style: none; padding: 0; margin: 0; min-width: 0; }
.basis-list > li, .history-record { display: grid; gap: 8px; min-width: 0; padding: 12px 0; border-top: 1px solid var(--line); }
.basis-confirm, .basis-history { padding-top: 14px; border-top: 1px solid var(--line); }
.row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; min-width: 0; }
dl { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 7px 12px; margin: 0; font-size: 12px; }
dt, time, .identity-text { color: var(--muted); font-size: 12px; } dd { margin: 0; overflow-wrap: anywhere; }
.reason-text { white-space: pre-wrap; font-size: 13px; } .history-record > .n-button { justify-self: start; }
@media (max-width: 450px) { dl { grid-template-columns: minmax(0, 1fr); } dd { margin-bottom: 5px; } }
</style>
