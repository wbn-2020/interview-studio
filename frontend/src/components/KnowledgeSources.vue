<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, useId, watch } from 'vue'
import { NButton, NInput } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import { emptyKnowledgeSource, knowledgeSourceDocumentValid, knowledgeSourceIdValid, knowledgeSourceReceiptMatches,
  knowledgeSourceSameDocument, knowledgeSourceSaveProblem, knowledgeSourcesPath, normalizedKnowledgeSources } from '../api/knowledgeSources'
import type { KnowledgeSource, KnowledgeSourceDocument, KnowledgeSourceSave } from '../api/knowledgeSources'
import { useCommand } from '../composables/useCommand'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useWorkspaceStore } from '../state/workspace'

const props = defineProps<{ itemId: string; versionId: string; versionNumber: number; enabled: boolean; active: boolean }>()
const emit = defineEmits<{ state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), viewId = useId()
const boundItemId = props.itemId, boundVersionId = props.versionId, boundNumber = props.versionNumber
const stateKey = `knowledge-sources:${viewId}`, element = ref<HTMLElement>()
const active = ref(true), elementVisible = ref(false), documentVisible = ref(document.visibilityState === 'visible')
const current = ref<KnowledgeSourceDocument>(), sources = ref<KnowledgeSource[]>([])
const error = ref<unknown>(), loading = ref(false), message = ref(''), baselineReady = ref(false)
const serverCopy = ref<KnowledgeSourceDocument>(), writeConflict = ref(false)
const historyRevision = ref(''), historyOpen = ref(false), history = ref<KnowledgeSourceDocument>()
const historyError = ref<unknown>(), historyLoading = ref(false)
const historyDetails = ref<HTMLDetailsElement>()
const identityValid = computed(() => knowledgeSourceIdValid(boundItemId) && knowledgeSourceIdValid(boundVersionId)
  && props.itemId === boundItemId && props.versionId === boundVersionId && props.versionNumber === boundNumber
  && Number.isSafeInteger(boundNumber) && boundNumber > 0)
const readable = computed(() => active.value && identityValid.value && props.enabled && elementVisible.value
  && documentVisible.value && store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const path = knowledgeSourcesPath(boundItemId, boundVersionId)
const dirty = computed(() => Boolean(current.value) && JSON.stringify(sources.value) !== JSON.stringify(current.value!.sources))
const editable = computed(() => readable.value && props.active && baselineReady.value && !writeConflict.value
  && !serverCopy.value && !writer.locked.value && !loading.value)
const request = computed<KnowledgeSourceSave>(() => ({ expectedRevision: current.value?.revision ?? 0, sources: sources.value }))
const inputProblem = computed(() => knowledgeSourceSaveProblem(request.value))
const historyNumber = computed(() => /^[1-9][0-9]{0,15}$/.test(historyRevision.value)
  && Number.isSafeInteger(Number(historyRevision.value)) ? Number(historyRevision.value) : undefined)
const changes = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, sequence = 0, historySequence = 0, observer: MutationObserver | undefined
let commandContext = context()

watch([dirty, writer.locked], ([unsaved, locked]) => {
  changes.dirty = unsaved; changes.locked = locked; store.changes.set(stateKey, changes)
  emit('state', { dirty: unsaved, locked })
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.itemId, () => props.versionId, () => props.versionNumber,
  () => props.active, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  generation++; sequence++; clearHistory()
  loading.value = false; baselineReady.value = false; serverCopy.value = undefined; message.value = ''; error.value = undefined
  // 失效只撤回展示和读资格；本地编辑、独立来源 CAS 与未决原请求留在原版本上。
  if (readable.value && !writer.locked.value) void load()
}, { immediate: true, flush: 'sync' })
watch(historyRevision, clearHistory, { flush: 'sync' })
watch(writer.error, value => {
  if (!(value instanceof ApiError) || writer.pending.value) return
  if (value.status === 409) baselineReady.value = false
  if (['KNOWLEDGE_SOURCE_VERSION_CHANGED', 'KNOWLEDGE_ARCHIVED'].includes(value.code)) writeConflict.value = true
})
function context() { return { generation, epoch: store.sessionEpoch, accessEpoch: currentTrainingAccessEpoch() } }
function currentContext(stamp: ReturnType<typeof context>): boolean {
  return alive && readable.value && document.visibilityState === 'visible' && elementIsVisible()
    && stamp.generation === generation && stamp.epoch === store.sessionEpoch
    && stamp.accessEpoch === currentTrainingAccessEpoch()
}
function clearHistory() {
  historySequence++; history.value = undefined; historyError.value = undefined; historyLoading.value = false
}
function accept(value: KnowledgeSourceDocument) {
  current.value = value; sources.value = structuredClone(value.sources); baselineReady.value = true
  serverCopy.value = undefined; error.value = undefined; clearHistory()
}
async function load(discard = false) {
  if (!currentContext(context()) || writer.locked.value) return
  if (discard && dirty.value && !window.confirm('重新读取会丢弃此版本尚未保存的来源修改，继续？')) return
  const stamp = context(), ticket = ++sequence
  loading.value = true; error.value = undefined; clearHistory()
  const live = () => currentContext(stamp) && ticket === sequence && !writer.locked.value
  try {
    const value = await read<unknown>(path)
    if (!live()) return
    if (!knowledgeSourceDocumentValid(value, boundItemId, boundVersionId)) {
      throw new ApiError(502, 'KNOWLEDGE_SOURCES_INVALID', '来源记录的版本、修订或字段无法核对，请重新读取。')
    }
    if (!discard && dirty.value && current.value) {
      if (!knowledgeSourceSameDocument(value, current.value)) {
        serverCopy.value = value; baselineReady.value = false
        throw new ApiError(409, 'REVISION_CONFLICT', '来源记录已在其他页面变化。本地输入继续保留；请核对服务器副本后再决定是否重新读取。')
      }
      current.value = value; baselineReady.value = true
    } else accept(value)
  } catch (reason) { if (live()) error.value = reason }
  finally { if (alive && ticket === sequence) loading.value = false }
}
function save() {
  if (!editable.value || !currentContext(context()) || !current.value || !dirty.value || inputProblem.value) return
  const body: KnowledgeSourceSave = { expectedRevision: current.value.revision, sources: normalizedKnowledgeSources(sources.value) }
  sequence++; clearHistory(); error.value = undefined; message.value = ''; commandContext = context()
  void writer.run<unknown>(path, 'PUT', body, value => {
    if (!knowledgeSourceReceiptMatches(value, boundItemId, boundVersionId, body)) {
      throw new ApiError(502, 'KNOWLEDGE_SOURCE_RECEIPT_INVALID', '来源保存回执与原请求不一致，请保留输入并使用原请求恢复。')
    }
    // 隐藏／授权变化后的成功回执也可能已经提交；拒绝写回界面，由 useCommand 保留原键恢复。
    if (!currentContext(commandContext)) throw new ApiError(0, 'KNOWLEDGE_SOURCE_CONTEXT_CHANGED', '资料访问或页面状态已变化，请恢复访问后用原请求核对保存结果。')
    accept(value); message.value = `已保存来源修订 ${value.revision}`
  })
}
function retry() {
  if (!currentContext(context()) || writer.busy.value || !writer.pending.value) return
  sequence++; loading.value = false; clearHistory(); commandContext = context(); void writer.retry()
}
function addSource() { if (editable.value && sources.value.length < 20) sources.value.push(emptyKnowledgeSource()) }
function removeSource(index: number) { if (editable.value) sources.value.splice(index, 1) }
function setTime(source: KnowledgeSource, value: string) {
  if (!editable.value) return
  if (!value) { source.acquiredAt = null; return }
  const instant = new Date(value)
  if (Number.isFinite(instant.getTime())) source.acquiredAt = instant.toISOString()
}
function localTime(value: string | null) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isFinite(date.getTime()) ? new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19) : ''
}
function historyToggled(event: Event) {
  historyOpen.value = (event.target as HTMLDetailsElement).open
  if (!historyOpen.value) clearHistory()
}
async function readHistory() {
  const revision = historyNumber.value
  if (!currentContext(context()) || !historyDetails.value?.open || !baselineReady.value || writer.locked.value || revision === undefined) return
  const stamp = context(), ticket = ++historySequence
  history.value = undefined; historyError.value = undefined; historyLoading.value = true
  const live = () => currentContext(stamp) && historyDetails.value?.open === true && baselineReady.value && !writer.locked.value
    && ticket === historySequence && revision === historyNumber.value
  try {
    const value = await read<unknown>(`${path}?revision=${revision}`)
    if (!live()) return
    if (!knowledgeSourceDocumentValid(value, boundItemId, boundVersionId, revision)) {
      throw new ApiError(502, 'KNOWLEDGE_SOURCE_HISTORY_INVALID', '历史来源记录与所选版本或修订不一致，请重新读取。')
    }
    history.value = value
  } catch (reason) { if (live()) historyError.value = reason }
  finally { if (alive && ticket === historySequence) historyLoading.value = false }
}
function visibilityChanged() { documentVisible.value = document.visibilityState === 'visible' }
function elementIsVisible(): boolean {
  const node = element.value
  if (!node || !node.getClientRects().length || node.closest('[hidden], [inert], [aria-hidden="true"]')) return false
  for (let ancestor: HTMLElement | null = node; ancestor; ancestor = ancestor.parentElement) {
    const style = getComputedStyle(ancestor)
    if (style.visibility === 'hidden' || style.visibility === 'collapse') return false
  }
  return true
}
function elementVisibilityChanged() { elementVisible.value = elementIsVisible() }
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
  alive = false; generation++; sequence++; clearHistory(); observer?.disconnect()
  document.removeEventListener('visibilitychange', visibilityChanged)
  if (store.changes.get(stateKey) === changes) store.changes.delete(stateKey)
  emit('state', { dirty: false, locked: false })
})
</script>
<template>
  <section ref="element" aria-label="知识资料来源">
    <h3>资料来源 · 正文 v{{ versionNumber }}</h3>
    <p v-if="!identityValid" class="notice error" role="alert">版本身份已变化。原输入与未决请求继续保留，请回到原版本核对。</p>
    <p v-else-if="!readable" class="notice" role="status">资料访问待确认，来源内容暂不展示，本地修改和原请求继续保留。</p>
    <template v-if="readable">
      <p class="field-help">记录参考资料、作者和适用版本。来源记录单独追加修订，不改原文、不继承外部审阅或 AI 发送许可；链接失效也不会删除你的独立笔记。</p>
      <p v-if="loading" role="status">正在读取来源…</p>
      <p v-if="error" class="notice error" role="alert">{{ errorText(error) }}</p>
      <p v-if="dirty && !baselineReady" class="notice" role="status">本地来源修改继续保留。请先重新核对来源基线；采用服务器副本会先确认是否放弃输入。</p>
      <p v-if="writeConflict" class="notice" role="status">知识已归档或已有新的发布版本。原版来源保持不变，请回到当前可编辑发布版后再补充来源。</p>
      <p v-if="current && !sources.length" class="field-help">这份发布版本尚无资料来源记录。</p>
      <fieldset v-for="(source, index) in sources" :key="index" :disabled="!editable" class="source-entry">
        <legend>来源 {{ index + 1 }}</legend>
        <label>资料标题 <NInput v-model:value="source.title" :maxlength="200" :disabled="!editable" /></label>
        <label>地址（可选）<NInput v-model:value="source.url" :maxlength="2000" :disabled="!editable" placeholder="填写公开资料地址，可留空" /></label>
        <label>作者／机构 <NInput v-model:value="source.author" :maxlength="200" :disabled="!editable" /></label>
        <label>获取时间 <input type="datetime-local" step="1" :value="localTime(source.acquiredAt)" :disabled="!editable" @input="setTime(source, ($event.target as HTMLInputElement).value)" /></label>
        <label>适用技术版本 <NInput v-model:value="source.applicableVersion" :maxlength="200" :disabled="!editable" /></label>
        <label>权利／使用范围说明 <NInput v-model:value="source.rights" type="textarea" :maxlength="2000" :disabled="!editable" /></label>
        <label>备注 <NInput v-model:value="source.note" type="textarea" :maxlength="4000" :disabled="!editable" /></label>
        <NButton size="small" :disabled="!editable" @click="removeSource(index)">移除此项</NButton>
      </fieldset>
      <p v-if="inputProblem && dirty" class="field-help">{{ inputProblem }}</p>
      <div class="row-actions">
        <NButton :disabled="!editable || !current || sources.length >= 20" @click="addSource">添加来源</NButton>
        <NButton type="primary" :disabled="!editable || !dirty || Boolean(inputProblem)" :loading="writer.busy.value" @click="save">保存来源记录</NButton>
        <NButton :disabled="writer.locked.value || loading" @click="load(false)">核对来源基线</NButton>
        <NButton :disabled="writer.locked.value || loading" @click="load(true)">采用服务器记录</NButton>
      </div>
      <div v-if="serverCopy" class="notice">
        <h4>服务器来源副本 · 修订 {{ serverCopy.revision }}</h4>
        <p>当前表单仍保留本地输入，尚未切换独立来源 CAS 基线。</p>
        <pre>{{ JSON.stringify(serverCopy.sources, null, 2) }}</pre>
      </div>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}</p>
      <NButton v-if="writer.pending.value && !writer.busy.value" @click="retry">用原请求重试</NButton>
      <p v-if="message" role="status">{{ message }}</p>
      <details v-if="current?.revision && baselineReady" ref="historyDetails" @toggle="historyToggled">
        <summary @click="clearHistory">查看来源历史修订（当前 {{ current.revision }}）</summary>
        <label>修订号 <NInput v-model:value="historyRevision" :maxlength="16" :disabled="writer.locked.value" /></label>
        <NButton :disabled="writer.locked.value || historyNumber === undefined" :loading="historyLoading" @click="readHistory">读取历史</NButton>
        <p v-if="historyError" class="notice error" role="alert">{{ errorText(historyError) }}</p>
        <pre v-if="history && historyOpen">{{ JSON.stringify(history, null, 2) }}</pre>
      </details>
    </template>
  </section>
</template>
<style scoped>
.source-entry { margin: 1rem 0; border: 1px solid var(--line, #dce2dc); border-radius: 7px; padding: 1rem; }
.source-entry label { display: block; margin-bottom: .75rem; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
