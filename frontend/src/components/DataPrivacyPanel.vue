<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { currentTrainingAccessEpoch, errorText } from '../api/client'
import { checkedPrivacyResult, checkedPrivacyStatus, previewPrivacy, privacyActionableKindValid, privacyActions, privacyIdValid, privacyImpacts,
  privacyKinds, privacyStates, privacyStorageHandling, privacyUtcDate, readPrivacyObjects, readPrivacyStatus, readPrivacyTrash } from '../api/dataPrivacy'
import type { PrivacyAction, PrivacyActionableKind, PrivacyObjectCandidate, PrivacyPreview, PrivacyResult, PrivacyStatus, PrivacyTrashEntry, PrivacyTrashPage } from '../api/dataPrivacy'
import { useCommand } from '../composables/useCommand'
import { usePreferencesStore } from '../state/preferences'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useWorkspaceStore } from '../state/workspace'
import PrivacyObjectPicker from './PrivacyObjectPicker.vue'

const props = defineProps<{ enabled: boolean; recoveryEnabled?: boolean }>()
const emit = defineEmits<{ changed: [result: PrivacyResult | null] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), preferences = usePreferencesStore(), writer = useCommand()
const element = ref<HTMLElement>(), active = ref(true), elementVisible = ref(false)
const documentVisible = ref(document.visibilityState === 'visible')
const status = shallowRef<PrivacyStatus>(), trash = shallowRef<PrivacyTrashPage>(), impact = shallowRef<PrivacyPreview>()
const receipt = shallowRef<PrivacyResult>(), objectKind = ref<PrivacyActionableKind>('KNOWLEDGE'), objectId = ref(''), selectedTitle = ref('')
const trashTitles = ref<Record<string, string>>({})
const action = ref<PrivacyAction>('TRASH'), cursor = ref<string>(), cursors = ref<(string | undefined)[]>([])
const statusLoading = ref(false), trashLoading = ref(false), previewLoading = ref(false)
const statusError = ref(''), trashError = ref(''), problem = ref(''), message = ref(''), cleared = ref(false)
const acknowledgeBaseline = ref(false), confirmed = ref(false), confirmWholeMixed = ref(false)
const recoveryReadable = computed(() => active.value && Boolean(props.recoveryEnabled ?? props.enabled)
  && store.sessionActive && documentVisible.value && elementVisible.value)
const readable = computed(() => recoveryReadable.value && props.enabled && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const loading = computed(() => statusLoading.value || trashLoading.value || previewLoading.value)
const locked = computed(() => writer.locked.value || loading.value)
// 只有已冻结的原命令可走无正文回执通道；服务端继续核对原键与范围，不据此放行新读取或新命令。
const retryAllowed = computed(() => recoveryReadable.value && Boolean(writer.pending.value) && !writer.busy.value && !loading.value)
const otherWrite = computed(() => [...store.changes].some(([key, value]) => key !== 'data-privacy' && value.locked))
const lifecycleReady = computed(() => readable.value && status.value?.state === 'READY')
const canInitialize = computed(() => readable.value && !locked.value && !otherWrite.value
  && status.value?.configured && !status.value.initialized && status.value.appliedSequence === null
  && status.value.appliedHash === null && acknowledgeBaseline.value)
const dirty = computed(() => acknowledgeBaseline.value || confirmed.value || confirmWholeMixed.value
  || Boolean(objectId.value && !receipt.value))
const kindOptions = Object.entries(privacyKinds).filter(([value]) => privacyActionableKindValid(value)).map(([value, label]) => ({ value, label }))
const actionOptions = Object.entries(privacyActions).map(([value, label]) => ({ value, label }))
const canCommit = computed(() => lifecycleReady.value && !locked.value && !otherWrite.value && confirmed.value
  && privacyActionableKindValid(objectKind.value)
  && impact.value?.action === action.value && impact.value.objectKind === objectKind.value && impact.value.objectId === objectId.value
  && (action.value !== 'PURGE' || confirmWholeMixed.value))
let alive = true, generation = 0, previewGeneration = 0, observer: MutationObserver | undefined
let writeContext = { generation: 0, session: 0, access: 0 }
watch([dirty, writer.locked], () => store.changes.set('data-privacy', { dirty: dirty.value, locked: writer.locked.value }), { immediate: true, flush: 'sync' })
function discardPreview() { previewGeneration++; impact.value = undefined; confirmed.value = false; confirmWholeMixed.value = false }
watch(status, value => {
  if (value?.state !== 'READY') { discardPreview(); trash.value = undefined }
}, { flush: 'sync' })
watch([objectKind, objectId, action], () => { discardPreview(); receipt.value = undefined; problem.value = ''; message.value = '' }, { flush: 'sync' })
watch([writer.error, writer.pending], () => {
  if (writer.error.value && !writer.pending.value) discardPreview()
}, { flush: 'sync' })
function clearPrivate() {
  generation++; discardPreview(); status.value = undefined; trash.value = undefined; trashTitles.value = {}; receipt.value = undefined
  objectId.value = ''; selectedTitle.value = ''; objectKind.value = 'KNOWLEDGE'; action.value = 'TRASH'
  cursor.value = undefined; cursors.value = []; acknowledgeBaseline.value = false
  statusLoading.value = false; trashLoading.value = false; previewLoading.value = false
  statusError.value = ''; trashError.value = ''; problem.value = ''; message.value = ''
}
watch([readable, recoveryReadable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode,
  () => access.active?.materialAccess], () => {
  if (dirty.value || impact.value || trash.value || status.value) cleared.value = true
  clearPrivate()
  if (recoveryReadable.value) void refreshStatus()
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
onActivated(() => { active.value = true; visibilityChanged(); elementVisibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; clearPrivate(); observer?.disconnect()
  document.removeEventListener('visibilitychange', visibilityChanged); store.changes.delete('data-privacy')
})
function context() { return { generation, session: store.sessionEpoch, access: currentTrainingAccessEpoch() } }
function current(ticket: ReturnType<typeof context>) {
  elementVisibilityChanged()
  return alive && readable.value && document.visibilityState === 'visible' && ticket.generation === generation
    && ticket.session === store.sessionEpoch && ticket.access === currentTrainingAccessEpoch()
}
function currentMetadata(ticket: ReturnType<typeof context>) {
  elementVisibilityChanged()
  return alive && recoveryReadable.value && document.visibilityState === 'visible' && ticket.generation === generation
    && ticket.session === store.sessionEpoch && ticket.access === currentTrainingAccessEpoch()
}
function readReady() { visibilityChanged(); elementVisibilityChanged(); return readable.value && !loading.value && !writer.busy.value }
async function refreshStatus() {
  visibilityChanged(); elementVisibilityChanged()
  if (!recoveryReadable.value || loading.value || writer.busy.value) return
  const ticket = context()
  status.value = undefined; statusError.value = ''; statusLoading.value = true
  try {
    const value = await readPrivacyStatus()
    if (currentMetadata(ticket)) status.value = value
  } catch (error) { if (currentMetadata(ticket)) statusError.value = errorText(error) }
  finally { if (alive && ticket.generation === generation) statusLoading.value = false }
}
async function loadTrash(direction: 'RESET' | 'NEXT' | 'PREVIOUS' = 'RESET') {
  if (!readReady() || !lifecycleReady.value) return
  let after: string | undefined
  if (direction === 'NEXT') { if (!trash.value?.nextCursor) return; after = trash.value.nextCursor }
  else if (direction === 'PREVIOUS') { if (!cursors.value.length) return; after = cursors.value.at(-1) }
  const ticket = context()
  trashLoading.value = true; trashError.value = ''
  try {
    const value = await readPrivacyTrash(after)
    if (!current(ticket)) return
    if (direction === 'NEXT') cursors.value.push(cursor.value)
    else if (direction === 'PREVIOUS') cursors.value.pop()
    else cursors.value = []
    cursor.value = after; trash.value = value
    const ids = value.items.filter(item => privacyActionableKindValid(item.objectKind)).map(item => item.objectId)
    if (ids.length) {
      try {
        const objects = await readPrivacyObjects(undefined, '', undefined, ids)
        if (!current(ticket)) return
        trashTitles.value = Object.fromEntries(objects.items.map(item => [`${item.objectKind}:${item.objectId}`, item.title]))
      } catch { /* 标题补充失败不阻断回收站身份操作。 */ }
    } else trashTitles.value = {}
  } catch (error) { if (current(ticket)) { trash.value = undefined; trashError.value = errorText(error) } }
  finally { if (alive && ticket.generation === generation) trashLoading.value = false }
}
function initialize() {
  if (!readReady() || !canInitialize.value) return
  writeContext = context(); problem.value = ''; message.value = ''
  void writer.run<unknown>('/data-privacy/ledger/initialize', 'POST', { acknowledgeCurrentBaseline: true }, value => {
    if (!currentMetadata(writeContext)) throw new Error('页面或登录状态已变化，请恢复后用原请求核对账本初始化。')
    status.value = checkedPrivacyStatus(value); acknowledgeBaseline.value = false
    if (status.value.state !== 'READY') { message.value = '初始化回执显示账本尚未就绪，请核对下方状态；不会继续隐私操作。'; return }
    message.value = '已从当前工作区基线建立独立账本。'; emit('changed', null)
  })
}
function chooseObject(value: PrivacyObjectCandidate) {
  if (!privacyActionableKindValid(value.objectKind) || value.objectKind !== objectKind.value || !privacyIdValid(value.objectId)) return
  selectedTitle.value = value.title
  objectId.value = value.objectId
}
async function inspect() {
  if (!readReady() || !lifecycleReady.value || writer.locked.value || otherWrite.value) return
  if (!privacyActionableKindValid(objectKind.value)) { problem.value = '模型范围授权请在模型授权管理中主动撤回。'; return }
  if (!privacyIdValid(objectId.value)) { problem.value = '请输入准确的小写标准对象UUID，不接受标题、路径或批量范围。'; return }
  const selected = { objectKind: objectKind.value, objectId: objectId.value }, selectedAction = action.value
  discardPreview(); receipt.value = undefined; problem.value = ''; message.value = ''
  const ticket = context(), previewTicket = previewGeneration
  previewLoading.value = true
  try {
    const value = await previewPrivacy(selectedAction, selected)
    if (current(ticket) && previewTicket === previewGeneration && action.value === selectedAction
      && objectKind.value === selected.objectKind && objectId.value === selected.objectId) impact.value = value
  } catch (error) { if (current(ticket) && previewTicket === previewGeneration) problem.value = errorText(error) }
  finally { if (alive && ticket.generation === generation) previewLoading.value = false }
}
function chooseTrash(item: PrivacyTrashEntry, selectedAction: 'RESTORE' | 'PURGE') {
  if (!readReady() || writer.locked.value) return
  if (!privacyActionableKindValid(item.objectKind)) { problem.value = '模型范围授权请在模型授权管理中主动撤回。'; return }
  if (dirty.value && !window.confirm('替换当前尚未执行的对象选择和影响预览？')) return
  if (!readReady() || writer.locked.value) return
  objectKind.value = item.objectKind; objectId.value = item.objectId; action.value = selectedAction
  discardPreview(); receipt.value = undefined; message.value = '已选择回收站对象，尚未执行任何恢复或清除；请先生成影响预览。'
}
function commit() {
  if (!readReady() || !canCommit.value || !impact.value) return
  const expected = impact.value
  if (!privacyActionableKindValid(expected.objectKind)) return
  const selectedKind = expected.objectKind
  writeContext = context(); problem.value = ''; message.value = ''
  void writer.run<unknown>(`/data-privacy/${expected.action.toLowerCase()}`, 'POST', {
    objectKind: expected.objectKind, objectId: expected.objectId, previewDigest: expected.digest,
    confirmWholeMixedDocuments: expected.action === 'PURGE' && confirmWholeMixed.value,
  }, value => {
    if (!currentMetadata(writeContext)) throw new Error('页面、登录或访问条件已变化，操作回执暂不展示；请恢复后用原请求确认结果。')
    const result = checkedPrivacyResult(value, expected)
    objectKind.value = selectedKind; objectId.value = expected.objectId; action.value = expected.action
    discardPreview(); receipt.value = result; trash.value = undefined; status.value = undefined
    message.value = expected.action === 'RESTORE' ? '已恢复访问身份；归档、暂停和模型用途保持关闭，需要另行确认。'
      : expected.action === 'PURGE' ? '已清除确认范围中的正文副本，保留无正文身份及独立账本记录。' : '已移入回收站，正文与关联身份保留。'
    emit('changed', result)
  })
}
function retry() {
  visibilityChanged(); elementVisibilityChanged()
  if (!retryAllowed.value) return
  // 原请求保留已确认的对象范围和摘要，不能因新预览或权限恢复而换键重复执行。
  writeContext = context(); void writer.retry()
}
function discardSelection() {
  if (!readReady() || writer.locked.value) return
  if (dirty.value && !window.confirm('放弃本地对象选择和未执行的确认？')) return
  objectId.value = ''; selectedTitle.value = ''; discardPreview(); receipt.value = undefined; acknowledgeBaseline.value = false
  problem.value = ''; message.value = ''
}
</script>

<template>
  <section ref="element" class="privacy-panel" aria-label="回收站与隐私管理">
    <header><h2>回收站与彻底删除</h2><p class="field-help">先从列表选择对象，再预览会影响哪些版本和关联内容。移入回收站可以恢复；彻底删除不可恢复。</p></header>
    <p v-if="!readable" class="notice" role="status">当前页面或资料权限不允许展示敏感影响清单。恢复访问条件后重新读取，旧预览不能用于新操作。</p>
    <p v-if="cleared" class="notice" role="status">页面、登录或训练资料权限变化后，敏感清单与确认已清除；未知写入仍保留原请求。</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}</p>
    <div v-if="writer.pending.value" class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!retryAllowed" @click="retry">用原请求确认结果</NButton><span class="field-help">只接续已确认的原命令并核对无正文回执；服务器会再次核对原操作范围。确认前不能发起第二个隐私命令。</span></div>
    <section v-if="!readable && recoveryReadable" class="privacy-ledger" aria-label="仅恢复账本元数据"><div class="row-actions"><h3>账本恢复状态</h3><NButton :loading="statusLoading" :disabled="loading || writer.busy.value" @click="refreshStatus">读取账本元数据</NButton></div><p class="field-help">这里仅显示账本状态及接续原命令，不展示对象标题、正文、回收站清单或生成新的清除预览。</p><p v-if="statusError" class="notice error">{{ statusError }}</p><p v-if="status">{{ privacyStates[status.state] }} · 独立账本序号{{ status.latestHead.sequence }} · 数据库检查点{{ status.appliedSequence ?? '未建立' }}</p><p v-if="status?.state === 'RECONCILIATION_REQUIRED'" class="notice error">请优先接续本页保留的原请求；没有原请求时，须在封闭恢复环境核对最新账本，不能覆盖或重建账本。</p></section>
    <template v-if="readable">
      <section class="privacy-ledger"><div class="row-actions"><h3>删除记录</h3><NButton :loading="statusLoading" :disabled="loading || writer.busy.value" @click="refreshStatus">读取当前账本状态</NButton></div><p v-if="statusError" class="notice error" role="alert">{{ statusError }}</p><p v-if="!status && !statusLoading" class="field-help">尚未读取可核对的状态，所有变更保持关闭。</p><template v-if="status"><strong>{{ privacyStates[status.state] }}</strong><p v-if="status.state === 'NOT_CONFIGURED'" class="notice">部署时须配置独立持久化的隐私账本目录；账本不能随业务库旧备份一起回滚。此页面不会代为修改服务器。</p><p v-if="status.state === 'RECONCILIATION_REQUIRED'" class="notice error">账本与业务检查点未对齐。若本页有原请求，请先用原键接续；否则须在封闭恢复环境核对最新账本。不能重建或覆盖账本继续操作。</p><p v-if="status.state === 'NOT_INITIALIZED' && status.appliedSequence !== null" class="notice error">数据库已有账本检查点，独立文件却不可用。初始化入口保持关闭，请恢复并核对原账本。</p><details><summary>最新头与业务检查点</summary><dl class="privacy-head"><div><dt>独立账本序号</dt><dd>{{ status.latestHead.sequence }}</dd></div><div><dt>独立账本SHA</dt><dd>{{ status.latestHead.hash }}</dd></div><div><dt>数据库检查点</dt><dd>{{ status.appliedSequence ?? '未建立' }}</dd></div><div><dt>数据库SHA</dt><dd>{{ status.appliedHash ?? '未建立' }}</dd></div></dl></details><template v-if="status.configured && !status.initialized && status.appliedSequence === null"><p class="field-help">初始化以当前工作区为基线。此前没有独立账本的旧备份，不能据此直接开放恢复；该操作不会覆盖已有账本或追回历史删除事件。</p><NCheckbox v-model:checked="acknowledgeBaseline" :disabled="locked || otherWrite">我明确确认从当前工作区状态建立独立账本基线</NCheckbox><div><NButton type="primary" :disabled="!canInitialize" @click="initialize">初始化当前基线账本</NButton></div></template></template></section>
      <p v-if="otherWrite" class="notice">其他保存或导出请求仍在进行或结果未确认。先完成原请求，再执行隐私变更。</p>
      <section class="privacy-selection"><h3>明确处理对象</h3><div class="privacy-fields"><label>对象类型<NSelect v-model:value="objectKind" :options="kindOptions" :disabled="locked" @update:value="selectedTitle = ''" /></label><label>处理动作<NSelect v-model:value="action" :options="actionOptions" :disabled="locked" /></label></div><PrivacyObjectPicker :kind="objectKind" :disabled="locked" @select="chooseObject" /><p v-if="selectedTitle && objectId" class="privacy-selected-object" role="status">已选择：{{ selectedTitle }}<code>{{ objectId }}</code></p><details class="privacy-advanced-id"><summary>按 UUID 精确选择（高级）</summary><label>准确对象UUID<NInput v-model:value="objectId" :maxlength="36" :disabled="locked" placeholder="从对象详情地址或回收站选择准确标识" @update:value="selectedTitle = ''" /></label></details><p class="field-help">不按标题推断对象，不执行不明确的批量清除。运行或暂停中的训练须先结束。恢复后不会自动启用归档内容、复习排程或模型授权。</p><p class="field-help">模型范围授权请前往<RouterLink :to="{ name: 'ai-authorizations' }" class="text-link">模型授权管理</RouterLink>主动撤回；关联范围清单仍会列明受影响的授权身份。</p><div class="row-actions"><NButton type="primary" :loading="previewLoading" :disabled="!lifecycleReady || locked || otherWrite || !privacyIdValid(objectId)" @click="inspect">生成{{ privacyActions[action] }}影响预览</NButton><NButton :disabled="locked" @click="discardSelection">清空本地选择</NButton></div></section>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}</p><p v-if="message" class="notice" role="status">{{ message }}</p>
      <section v-if="impact" class="privacy-impact" aria-label="已选择的完整影响范围"><header><h3>{{ privacyActions[impact.action] }} · {{ impact.objects.length }}个对象</h3><p class="field-help">根对象：{{ privacyKinds[impact.objectKind] }} · {{ impact.objectId }}</p></header><p v-if="impact.action === 'PURGE'" class="notice error">这是不可从回收站恢复的正文清除。混合派生文档按整份正文处理；请先独立保留确需保留的部分，再确认下面全部范围。</p><div class="privacy-object-list"><article v-for="item in impact.objects" :key="`${item.objectKind}:${item.objectId}`"><div><strong>{{ item.title }}</strong><span class="status-pill">{{ privacyImpacts[item.impact] ?? item.impact }}</span></div><p>{{ privacyKinds[item.objectKind] }} · {{ item.status }} · 修订{{ item.revision }}</p><code>{{ item.objectId }}</code></article></div><details v-if="impact.storage.length"><summary>存储副本与快照影响 · {{ impact.storage.length }}类</summary><div class="privacy-table-scroll"><table><thead><tr><th scope="col">存储范围</th><th scope="col">记录数</th><th scope="col">字段</th><th scope="col">处理</th></tr></thead><tbody><tr v-for="(row, index) in impact.storage" :key="index"><td>{{ row.table }}</td><td>{{ row.rows }}</td><td>{{ row.fields.join('、') }}</td><td>{{ privacyStorageHandling[row.handling] ?? row.handling }}</td></tr></tbody></table></div></details><ul v-if="impact.limitations.length" class="privacy-limitations"><li v-for="(value, index) in impact.limitations" :key="index">{{ value }}</li></ul><details><summary>本次确认的范围摘要</summary><p class="privacy-digest">{{ impact.digest }}</p><p class="field-help">账本字段清除定位：{{ impact.ledgerRedactions }}项。对象、版本或依赖变化后，服务器会拒绝旧摘要，必须重新预览。</p></details><NCheckbox v-model:checked="confirmed" :disabled="locked || !lifecycleReady || otherWrite">我已逐项核对根对象、全部关联影响与限制，确认执行当前范围</NCheckbox><NCheckbox v-if="impact.action === 'PURGE'" v-model:checked="confirmWholeMixed" :disabled="locked || !lifecycleReady || otherWrite">我明确同意清除列出的全部版本、整份混合派生正文、任务输入、索引及历史完整导出副本，并了解无法从回收站恢复</NCheckbox><div><NButton :type="impact.action === 'PURGE' ? 'error' : 'primary'" :disabled="!canCommit" :loading="writer.busy.value" @click="commit">确认{{ privacyActions[impact.action] }}{{ impact.action === 'PURGE' ? '及全部所列派生正文' : '' }}</NButton></div></section>
      <section v-if="receipt" class="privacy-receipt" aria-label="隐私操作回执"><h3>{{ privacyActions[receipt.action] }}已记录</h3><p>处理{{ receipt.objects.length }}个对象 · 账本序号{{ receipt.ledgerSequence }} · {{ receipt.state }}</p><p class="privacy-digest">{{ receipt.ledgerHash }}</p></section>
      <section class="privacy-trash"><div class="row-actions"><h3>回收站</h3><NButton :loading="trashLoading" :disabled="!lifecycleReady || loading || writer.busy.value" @click="loadTrash('RESET')">读取回收站首页</NButton></div><p class="field-help">此处保留身份、状态和处理时间，不展示正文。30天只是保留提示，不会自动彻底清除。选择恢复或清除后，仍须单独生成并确认影响预览。</p><p v-if="trashError" class="notice error" role="alert">{{ trashError }}</p><p v-if="trash && !trash.items.length" class="field-help">本页没有待处理的回收站对象。</p><article v-for="item in trash?.items" :key="`${item.objectKind}:${item.objectId}`" class="privacy-trash-row"><div><strong>{{ trashTitles[`${item.objectKind}:${item.objectId}`] ?? privacyKinds[item.objectKind] }}</strong><code>{{ item.objectId }}</code><small>移入 {{ preferences.formatDate(privacyUtcDate(item.trashedAt)) }} · 原状态{{ item.previousStatus ?? '未记录' }} · 隐私修订{{ item.revision }}</small></div><div v-if="privacyActionableKindValid(item.objectKind)" class="row-actions"><NButton size="small" :disabled="locked" @click="chooseTrash(item, 'RESTORE')">选择恢复</NButton><NButton size="small" type="error" secondary :disabled="locked" @click="chooseTrash(item, 'PURGE')">选择彻底清除</NButton></div><RouterLink v-else :to="{ name: 'ai-authorizations' }" class="text-link">前往模型授权管理</RouterLink></article><div v-if="cursors.length || trash?.nextCursor" class="row-actions"><NButton size="small" :disabled="!lifecycleReady || loading || writer.busy.value || !cursors.length" @click="loadTrash('PREVIOUS')">上一页</NButton><NButton size="small" :disabled="!lifecycleReady || loading || writer.busy.value || !trash?.nextCursor" @click="loadTrash('NEXT')">下一页</NButton></div></section>
    </template>
  </section>
</template>

<style scoped>
.privacy-panel { display: grid; gap: 20px; }
.privacy-panel h2, .privacy-panel h3 { margin: 0; }
.privacy-panel h2 { font-size: 19px; }
.privacy-panel h3 { font-size: 15px; }
.privacy-panel .row-actions h3 { margin-right: auto; }
.privacy-ledger, .privacy-selection, .privacy-impact, .privacy-trash, .privacy-receipt { display: grid; gap: 14px; border: 1px solid var(--line); border-radius: 8px; padding: 20px; background: var(--surface); }
.privacy-selection label { display: grid; gap: 7px; font-size: 12px; }
.privacy-selected-object { display: grid; gap: 5px; padding: 11px 12px; border: 1px solid var(--brand); border-radius: 6px; background: var(--brand-soft); color: var(--brand-strong); }
.privacy-advanced-id { display: grid; gap: 8px; }.privacy-advanced-id > summary { width: fit-content; }
.privacy-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.privacy-head { display: grid; gap: 10px; margin-top: 12px; font-size: 12px; }
.privacy-head > div { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 10px; }
.privacy-head dd { margin: 0; overflow-wrap: anywhere; font-family: monospace; }
.privacy-object-list { display: grid; gap: 10px; max-height: 460px; overflow: auto; }
.privacy-object-list article { display: grid; gap: 7px; padding: 13px; border: 1px solid var(--line); border-radius: 6px; overflow-wrap: anywhere; }
.privacy-object-list article > div { display: flex; flex-wrap: wrap; gap: 9px; align-items: center; font-size: 13px; }
.privacy-object-list p, .privacy-object-list code { font-size: 11px; color: var(--muted); }
.privacy-panel code, .privacy-digest { overflow-wrap: anywhere; font-family: monospace; font-size: 11px; }
.privacy-table-scroll { overflow: auto; margin-top: 12px; }
.privacy-table-scroll table { width: 100%; border-collapse: collapse; font-size: 12px; text-align: left; }
.privacy-table-scroll th, .privacy-table-scroll td { padding: 10px; border-bottom: 1px solid var(--line); overflow-wrap: anywhere; min-width: 90px; vertical-align: top; }
.privacy-limitations { display: grid; gap: 8px; padding-left: 20px; font-size: 12px; line-height: 1.7; }
.privacy-trash-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 0; border-top: 1px solid var(--line); }
.privacy-trash-row > div:first-child { display: grid; gap: 7px; font-size: 13px; }
.privacy-trash-row small { color: var(--muted); font-size: 11px; }
summary { cursor: pointer; font-size: 12px; color: var(--muted); }
@media (max-width: 700px) { .privacy-fields { grid-template-columns: 1fr; } .privacy-ledger, .privacy-selection, .privacy-impact, .privacy-trash, .privacy-receipt { padding: 15px; } .privacy-head > div { grid-template-columns: 1fr; gap: 5px; } }
</style>
