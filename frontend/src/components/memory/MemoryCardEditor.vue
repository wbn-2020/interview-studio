<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { MemoryCard, MemoryContent, MemoryPrompt, MemoryReference, MemoryVersion } from '../../api/memory'
import { memoryCardValid, memoryContentProblem, memoryContentSignature, memoryEmptyContent, memoryIdValid,
  memoryKindLabels, memoryPromptSignature, memoryReferenceValid, memoryStatusLabels } from '../../api/memory'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useRecentVisit } from '../../composables/useRecentVisit'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import MarkdownPreview from '../MarkdownPreview.vue'
import MemorySourcePicker from './MemorySourcePicker.vue'
import MemorySources from './MemorySources.vue'
import MemoryCardHistory from './MemoryCardHistory.vue'
import MemoryRecallState from './MemoryRecallState.vue'
import AiCardDraftOrigins from '../ai/AiCardDraftOrigins.vue'

const props = defineProps<{ id?: string; sourceItemId?: string; sourceVersionId?: string; disabled?: boolean }>()
const emit = defineEmits<{ saved: [value: MemoryCard]; close: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), route = useRoute(), router = useRouter(), writer = useCommand()
const active = ref(true), current = shallowRef<MemoryCard>(), editBase = shallowRef<MemoryCard>(), serverCopy = shallowRef<MemoryCard>()
const initial = memoryEmptyContent()
if (!props.id && memoryIdValid(props.sourceItemId) && memoryIdValid(props.sourceVersionId)) initial.sources = [{ itemId: props.sourceItemId, versionId: props.sourceVersionId }]
const content = ref<MemoryContent>(copyContent(initial)), initialSignature = ref(memoryContentSignature(initial))
const editing = ref(!props.id), tab = ref<'read' | 'edit' | 'history' | 'review'>(props.id ? 'read' : 'edit')
const visibleReference = shallowRef<MemoryReference>(), serverReference = shallowRef<MemoryReference>()
const referenceEpoch = ref(-1), serverReferenceEpoch = ref(-1), verifiedEpoch = ref(-1)
const readError = ref(''), serverError = ref(''), message = ref(''), needsReview = ref(false), replaceConfirmed = ref(false), serverLoading = ref(false)
const receiptMismatch = ref(false), receiptCommand = shallowRef<NonNullable<typeof writer.pending.value>>()
const deferred = shallowRef<() => void>(), created = shallowRef<MemoryCard>()
const state = reactive({ dirty: false, locked: false }), stateKey = `memory-card-editor-${props.id ?? 'new'}`
let alive = true, generation = 0, serverSequence = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
const creating = computed(() => !props.id)
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked && (props.id === undefined || memoryIdValid(props.id)))
const recordRecentVisit = useRecentVisit('MEMORY_CARD', () => props.id, readable)
const locked = computed(() => writer.locked.value || Boolean(deferred.value) || (receiptMismatch.value && Boolean(receiptCommand.value)))
const otherLocked = computed(() => [...store.changes.entries()].some(([key, value]) => key !== stateKey && key.startsWith('memory-') && value.locked))
const managementDirty = computed(() => [`memory-source-review-${props.id}`, `memory-schedule-editor-${props.id}`].some(key => store.changes.get(key)?.dirty))
const blocked = computed(() => !readable.value || locked.value || otherLocked.value || managementDirty.value || serverLoading.value || receiptMismatch.value)
const dirty = computed(() => editing.value && memoryContentSignature(content.value) !== initialSignature.value)
const stale = computed(() => Boolean(editBase.value && (!current.value || !sameCard(editBase.value, current.value))))
const editingReady = computed(() => creating.value || Boolean(editBase.value && editing.value))
const editable = computed(() => !blocked.value && editingReady.value && !created.value && (creating.value || editBase.value?.status === 'ACTIVE'))
const historyVersion = computed(() => typeof route.query.versionId === 'string' && memoryIdValid(route.query.versionId) ? route.query.versionId : undefined)
const initialSourceProblem = computed(() => creating.value && (props.sourceItemId !== undefined || props.sourceVersionId !== undefined)
  && (!memoryIdValid(props.sourceItemId) || !memoryIdValid(props.sourceVersionId)) ? '知识入口需要完整的来源对象与确切发布版本，请从知识页重新进入。' : '')
const limitReached = computed(() => Boolean(editBase.value && (editBase.value.revision >= 1000 || editBase.value.currentVersion.number >= 1000)))
const problem = computed(() => initialSourceProblem.value || memoryContentProblem(content.value))
const currentReady = computed(() => Boolean(current.value && !readError.value && !detail.error.value && !detail.isFetching.value && verifiedEpoch.value === store.sessionEpoch))
const serverReferenceReady = computed(() => Boolean(serverCopy.value && serverReference.value && serverReferenceEpoch.value === store.sessionEpoch
  && referenceMatches(serverReference.value, serverCopy.value.id, serverCopy.value.currentVersion)))
const key = computed(() => ['memory', store.sessionEpoch, 'card', props.id])
const detail = useQuery({ queryKey: key, enabled: computed(() => readable.value && Boolean(props.id)), staleTime: 0, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, id = props.id!
  const value = await read<MemoryCard>(`/memory/cards/${id}`)
  requireCurrent(epoch, stamp)
  if (id !== props.id || !cardReadValid(value, id)) throw mismatch('卡片修订或不可变题面与已读取记录不一致，请重新核对。')
  verifiedEpoch.value = epoch
  return value
} })

watch([detail.data, detail.dataUpdatedAt, readable, key], () => {
  if (!alive || !readable.value || !props.id) return
  const value = queryClient.getQueryData<MemoryCard>(key.value)
  if (!value) return
  if (!cardReadValid(value, props.id)) { readError.value = '缓存记录与已读取的卡片不一致，输入保留，请重读。'; return }
  current.value = copyCard(value); readError.value = ''
  recordRecentVisit(value.id)
}, { immediate: true })
watch([dirty, locked], ([changed, waiting]) => { state.dirty = changed; state.locked = waiting; store.changes.set(stateKey, state) }, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => {
  generation++; serverSequence++; serverLoading.value = false; verifiedEpoch.value = -1
  // 浏览参考的可见缓存按登录代次失效；编辑草稿只留在内存，遮挡时不向表单回填正文。
  visibleReference.value = undefined; serverReference.value = undefined; referenceEpoch.value = -1; serverReferenceEpoch.value = -1
  serverCopy.value = undefined; serverError.value = ''; replaceConfirmed.value = false
  if (editBase.value) needsReview.value = true
}, { flush: 'sync' })
watch(() => route.query.versionId, value => { if (props.id && typeof value === 'string' && memoryIdValid(value)) tab.value = 'history' }, { immediate: true })
watch(content, () => { replaceConfirmed.value = false }, { deep: true, flush: 'sync' })
watch(writer.error, error => {
  if (!(error instanceof ApiError) || writer.pending.value || receiptMismatch.value) return
  if (error.status === 409 && editBase.value) needsReview.value = true
  if (readable.value && props.id && error.status === 409) void detail.refetch()
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; serverSequence++; if (store.changes.get(stateKey) === state) store.changes.delete(stateKey) })

function copyCard(value: MemoryCard): MemoryCard { return JSON.parse(JSON.stringify(value)) as MemoryCard }
function copyContent(value: MemoryContent): MemoryContent { return { ...value, sources: value.sources.map(source => ({ ...source })), relatedConceptIds: [...value.relatedConceptIds] } }
function promptOf(value: MemoryContent): MemoryPrompt { return { kind: value.kind, title: value.title, prompt: value.prompt,
  sources: value.sources.map(source => ({ ...source })), relatedConceptIds: [...value.relatedConceptIds] } }
function fullContent(card: MemoryCard, reference: MemoryReference): MemoryContent { return copyContent({ ...card.currentVersion.content, reference: reference.reference }) }
function sameVersion(left: MemoryVersion, right: MemoryVersion): boolean { return left.id === right.id && left.number === right.number
  && left.createdAt === right.createdAt && memoryPromptSignature(left.content) === memoryPromptSignature(right.content) }
function sameCard(left: MemoryCard, right: MemoryCard): boolean { return left.id === right.id && left.revision === right.revision && left.status === right.status
  && left.createdAt === right.createdAt && left.updatedAt === right.updatedAt && sameVersion(left.currentVersion, right.currentVersion) }
function cardReadValid(value: MemoryCard, id: string): boolean {
  if (!memoryCardValid(value) || value.id !== id) return false
  const known = current.value
  return !known || known.id !== id || (value.revision >= known.revision && (value.revision !== known.revision || sameCard(value, known))
    && (value.currentVersion.id !== known.currentVersion.id || sameVersion(value.currentVersion, known.currentVersion)))
}
function referenceMatches(value: MemoryReference, id: string, version: MemoryVersion): boolean { return memoryReferenceValid(value, id, version.id, version.number) }
function changed() { return new ApiError(0, 'MEMORY_CONTEXT_CHANGED', '卡片或资料访问条件已变化，请恢复访问后重新核对。') }
function mismatch(text: string) { return new ApiError(502, 'MEMORY_RECEIPT_MISMATCH', text) }
function requireCurrent(epoch: number, stamp: number) { if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation) throw changed() }
function run<T>(path: string, method: string, body: unknown, validate: (value: T) => boolean, apply: (value: T) => void) {
  if (blocked.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation
  let original: typeof writer.pending.value
  void writer.run<T>(path, method, body, value => {
    if (!alive) return
    let valid = false
    try { valid = validate(value) } catch { valid = false }
    if (!valid) {
      receiptCommand.value = original; receiptMismatch.value = true
      writer.error.value = mismatch('回执与本次确切卡片、版本或内容不一致。输入保留，请用原请求核对结果。'); return
    }
    const accept = () => { receiptMismatch.value = false; receiptCommand.value = undefined; writer.error.value = undefined; apply(value) }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) deferred.value = accept
    else accept()
  })
  original = writer.pending.value
}
function receiveCard(value: MemoryCard) {
  if (!current.value || current.value.id !== value.id || value.revision >= current.value.revision) current.value = copyCard(value)
  readError.value = ''; serverCopy.value = undefined; serverReference.value = undefined; serverError.value = ''; replaceConfirmed.value = false
  visibleReference.value = undefined; referenceEpoch.value = -1
  // 新卡片的首次访问须由导航后的详情 GET 接纳；已有卡片继续回填当前修订。
  if (!creating.value && current.value && current.value.id === value.id && current.value.revision === value.revision) queryClient.setQueryData(['memory', store.sessionEpoch, 'card', value.id], value)
  void queryClient.invalidateQueries({ queryKey: ['memory'] }); emit('saved', value)
}
function requestReference(selected: MemoryCard, purpose: 'view' | 'edit' | 'compare') {
  if (blocked.value || (purpose === 'edit' && (selected.status !== 'ACTIVE' || editing.value || dirty.value))) return
  const fixed = copyCard(selected)
  run<MemoryReference>(`/memory/cards/${fixed.id}/versions/${fixed.currentVersion.id}/reference`, 'POST', {},
    value => referenceMatches(value, fixed.id, fixed.currentVersion), value => {
      if (purpose === 'compare') {
        if (!serverCopy.value || !sameCard(serverCopy.value, fixed)) { message.value = '服务器对照版本已变化，未拼接这份参考，请重新核对。'; return }
        serverReference.value = value; serverReferenceEpoch.value = store.sessionEpoch; message.value = '已读取服务器这份确切版本的参考，可以对照后明确选择。'
      } else if (purpose === 'edit') {
        // 只有显式参考回执成功，才把同一题面版本与参考组合为可保存的完整内容。
        const full = fullContent(fixed, value)
        editBase.value = fixed; content.value = full; initialSignature.value = memoryContentSignature(full); editing.value = true; tab.value = 'edit'
        needsReview.value = !current.value || !sameCard(current.value, fixed) || verifiedEpoch.value !== store.sessionEpoch
        message.value = needsReview.value ? '参考已读取，但卡片或访问条件已变化；编辑内容保留，保存前请对照服务器版本。' : '已读取同一版本的完整参考，可以编辑。此操作没有产生复习或评分记录。'
      } else if (current.value && sameVersion(current.value.currentVersion, fixed.currentVersion)) {
        visibleReference.value = value; referenceEpoch.value = store.sessionEpoch; message.value = '已查看这份确切版本的个人参考；未产生复习或评分记录。'
      } else message.value = '当前卡片已有新版本，未把旧版本参考拼到新题面，请重新选择查看。'
    })
}
function startEdit() {
  if (blocked.value) return
  if (editing.value && editBase.value) { tab.value = 'edit'; return }
  const selected = current.value
  if (!selected || !currentReady.value || selected.status !== 'ACTIVE' || selected.revision >= 1000 || selected.currentVersion.number >= 1000) return
  requestReference(selected, 'edit')
}
function viewReference() { if (!blocked.value && currentReady.value && current.value) requestReference(current.value, 'view') }
function updateText(field: 'title' | 'prompt' | 'reference', value: string) { if (editable.value) content.value[field] = value }
function updateKind(value: MemoryContent['kind']) { if (editable.value && Object.hasOwn(memoryKindLabels, value)) content.value.kind = value }
function updateSources(value: MemoryContent['sources']) { if (editable.value) content.value.sources = value.map(source => ({ ...source })) }
function updateConcepts(value: string[]) { if (editable.value) content.value.relatedConceptIds = [...value] }
function save() {
  if (!editable.value || problem.value || created.value || (!creating.value && (!dirty.value || stale.value || needsReview.value || limitReached.value || !currentReady.value))) return
  const snapshot = copyContent(content.value), base = editBase.value ? copyCard(editBase.value) : undefined
  if (!creating.value && !base) return
  const path = base ? `/memory/cards/${base.id}` : '/memory/cards', body = base ? { expectedRevision: base.revision, content: snapshot } : { content: snapshot }
  run<MemoryCard>(path, base ? 'PUT' : 'POST', body, value => memoryCardValid(value) && value.status === 'ACTIVE'
    && memoryPromptSignature(value.currentVersion.content) === memoryPromptSignature(promptOf(snapshot))
    && (base ? value.id === base.id && value.createdAt === base.createdAt && value.revision === base.revision + 1
      && value.currentVersion.number === base.currentVersion.number + 1 && value.currentVersion.id !== base.currentVersion.id
      : value.revision === 0 && value.currentVersion.number === 1), value => {
    content.value = copyContent(snapshot); initialSignature.value = memoryContentSignature(snapshot); editBase.value = copyCard(value); needsReview.value = false
    if (!base) created.value = value
    message.value = `已保存为 v${value.currentVersion.number}。卡片内容与来源一起成版，未产生作答或评分记录。来源与排程状态可在管理页签核对。`
    receiveCard(value)
  })
}
function changeStatus() {
  const selected = current.value
  if (blocked.value || !selected || !currentReady.value || selected.revision >= 1000 || (selected.status === 'ACTIVE' && dirty.value)
    || (editBase.value && (stale.value || needsReview.value))) return
  const fixed = copyCard(selected), status = fixed.status === 'ACTIVE' ? 'ARCHIVED' : 'ACTIVE'
  run<MemoryCard>(`/memory/cards/${fixed.id}/${status === 'ARCHIVED' ? 'archive' : 'restore'}`, 'POST', { expectedRevision: fixed.revision },
    value => memoryCardValid(value) && value.id === fixed.id && value.status === status && value.revision === fixed.revision + 1
      && value.createdAt === fixed.createdAt && sameVersion(value.currentVersion, fixed.currentVersion), value => {
      if (editBase.value && sameCard(editBase.value, fixed)) editBase.value = copyCard(value)
      message.value = status === 'ARCHIVED' ? '卡片已归档，内容版本和历史继续保留。' : '卡片已恢复到管理列表；没有启用排程或加入到期队列。'
      receiveCard(value)
    })
}
async function compareServer() {
  if (blocked.value || !props.id) return
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++serverSequence, id = props.id
  serverLoading.value = true; serverError.value = ''; serverReference.value = undefined; serverReferenceEpoch.value = -1; replaceConfirmed.value = false
  try {
    const value = await read<MemoryCard>(`/memory/cards/${id}`)
    requireCurrent(epoch, stamp)
    if (sequence !== serverSequence || id !== props.id) return
    if (!cardReadValid(value, id)) throw mismatch('服务器卡片修订或不可变内容异常，编辑草稿保留。')
    current.value = copyCard(value); serverCopy.value = copyCard(value); verifiedEpoch.value = epoch; readError.value = ''
    queryClient.setQueryData(key.value, value)
    message.value = '服务器题面已读取；如需替换编辑基线，请明确读取这份版本参考后再选择。'
  } catch (error) { if (alive && sequence === serverSequence && epoch === store.sessionEpoch) serverError.value = errorText(error) }
  finally { if (alive && sequence === serverSequence) serverLoading.value = false }
}
function useServer(keepLocal: boolean) {
  const selected = serverCopy.value, reference = serverReference.value
  if (blocked.value || !selected || !reference || !serverReferenceReady.value || !current.value || !sameCard(selected, current.value)
    || verifiedEpoch.value !== store.sessionEpoch || (!keepLocal && dirty.value && !replaceConfirmed.value) || (keepLocal && !editing.value)) return
  const full = fullContent(selected, reference)
  // CAS 基线与可保留旧来源一起切换；历史上出现过的来源不能因此变成当前卡允许保留的引用。
  editBase.value = copyCard(selected); initialSignature.value = memoryContentSignature(full)
  if (!keepLocal) content.value = full
  editing.value = true; tab.value = 'edit'; needsReview.value = false; replaceConfirmed.value = false
  serverCopy.value = undefined; serverReference.value = undefined; serverReferenceEpoch.value = -1
  message.value = keepLocal ? '本地完整题面与参考保留，已明确采用服务器修订作为保存基线；请核对来源后保存。' : '已采用服务器同一版本的完整题面与参考，尚未保存新版本。'
}
function finishEditing() {
  if (blocked.value || creating.value || !store.canLeave([stateKey])) return
  content.value = memoryEmptyContent(); initialSignature.value = memoryContentSignature(content.value); editBase.value = undefined; editing.value = false
  needsReview.value = false; serverCopy.value = undefined; serverReference.value = undefined; replaceConfirmed.value = false; tab.value = 'read'
}
function canChangeTab(value: typeof tab.value) {
  if (locked.value || !readable.value || serverLoading.value) return false
  if (!otherLocked.value) return true
  // 同卡历史地址可能在请求途中变化；须允许返回持有原命令的子页签，不能被该命令自身锁死。
  if (value === 'review') return [`memory-source-review-${props.id}`, `memory-schedule-editor-${props.id}`].some(key => store.changes.get(key)?.locked)
  return value === 'history' && Boolean(store.changes.get(`memory-history-reference-${props.id}`)?.locked)
}
function changeTab(value: typeof tab.value) { if (canChangeTab(value)) tab.value = value }
function selectVersion(versionId: string) {
  // 历史组件也会请求返回未决参考的原版本；该入口必须保留，不能被其自身命令锁堵住。
  if (readable.value && !locked.value && props.id && memoryIdValid(versionId)) void router.push({ name: 'memory-cards', query: { item: props.id, versionId } })
}
function retry() {
  if (!readable.value || writer.busy.value) return
  if (receiptMismatch.value && receiptCommand.value) writer.pending.value = receiptCommand.value
  commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry()
}
function receiveDeferred() { if (readable.value && deferred.value) { const accept = deferred.value; deferred.value = undefined; accept() } }
function refresh() { if (readable.value && !locked.value && props.id) void detail.refetch() }
function displayTime(value: string) { return new Date(value).toLocaleString('zh-CN') }
</script>

<template>
  <section class="memory-card-editor" aria-label="回忆卡管理">
    <header class="editor-heading"><div><p class="section-label">知识 / 回忆卡</p><h2>{{ !readable ? '回忆卡' : creating ? '创建一张回忆卡' : current?.currentVersion.content.title ?? '读取卡片' }}</h2><p v-if="readable && current" class="field-help">{{ memoryStatusLabels[current.status] }} · v{{ current.currentVersion.number }} · 对象修订 {{ current.revision }}</p></div><NButton text size="small" :disabled="locked || otherLocked" @click="emit('close')">关闭</NButton></header>
    <p v-if="!readable" class="notice" role="status">资料访问暂未恢复，卡片内容和参考已遮挡。编辑草稿与未决请求继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="editor-content">
      <p class="field-help">本页管理手工问答卡和填空卡。内容版本、来源复核与排程分别记录，保存或浏览参考不会产生复习评分。</p>
      <p v-if="!creating && !current && detail.isPending.value" role="status">正在读取卡片题面…</p>
      <p v-if="detail.error.value || readError" class="notice error" role="alert">{{ readError || errorText(detail.error.value) }}<NButton text :disabled="blocked || detail.isFetching.value" @click="refresh">重新读取</NButton></p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value || (receiptMismatch && receiptCommand)" text :disabled="!readable" :loading="writer.busy.value" @click="retry">{{ receiptMismatch ? '用原请求核对已保存结果' : '用原请求重试' }}</NButton></p>
      <p v-if="deferred" class="notice">服务器已返回结果，访问变化后暂缓接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收原请求结果</NButton></p>
      <p v-if="created" class="notice">卡片已经创建。<NButton text :disabled="blocked" @click="emit('saved', created)">打开已保存卡片</NButton></p>
      <nav v-if="!creating" class="editor-tabs" aria-label="卡片内容与历史"><button :aria-pressed="tab === 'read'" :disabled="!canChangeTab('read')" @click="changeTab('read')">题面与来源</button><button v-if="editing" :aria-pressed="tab === 'edit'" :disabled="!canChangeTab('edit')" @click="changeTab('edit')">编辑草稿{{ dirty ? ' · 未保存' : '' }}</button><button :aria-pressed="tab === 'history'" :disabled="!canChangeTab('history')" @click="changeTab('history')">历史版本</button><button :aria-pressed="tab === 'review'" :disabled="!canChangeTab('review')" @click="changeTab('review')">来源复核与排程{{ managementDirty ? ' · 未保存' : '' }}</button></nav>
      <p v-if="managementDirty && tab !== 'review'" class="notice">来源复核或排程调整还有未保存输入，请回到对应页签处理后再修改卡片。</p>
      <section v-if="current && !creating" v-show="tab === 'read'" class="card-readout">
        <p class="field-help">{{ memoryKindLabels[current.currentVersion.content.kind] }} · 保存于 {{ displayTime(current.currentVersion.createdAt) }}</p>
        <MarkdownPreview v-if="readable" :value="current.currentVersion.content.prompt" />
        <MemorySources :references="current.sourceReferences" :concepts="current.relatedConcepts" :disabled="!readable || locked || otherLocked" />
        <AiCardDraftOrigins :card-id="current.id" :version-id="current.currentVersion.id" :version-number="current.currentVersion.number" :editing="editing" :disabled="!readable || tab !== 'read' || locked || otherLocked || serverLoading || !currentReady" />
        <div class="row-actions"><NButton :disabled="blocked || !currentReady" :loading="writer.busy.value" @click="viewReference">查看本版本参考</NButton><NButton :disabled="blocked || !currentReady || current.status !== 'ACTIVE' || current.revision >= 1000 || current.currentVersion.number >= 1000" :loading="writer.busy.value" @click="startEdit">{{ editing ? '继续编辑草稿' : '读取参考并编辑' }}</NButton><NButton text :disabled="blocked || detail.isFetching.value" @click="refresh">重读卡片与来源</NButton></div>
        <section v-if="readable && visibleReference && referenceEpoch === store.sessionEpoch && visibleReference.cardId === current.id && visibleReference.versionId === current.currentVersion.id" class="reference-readout"><header><h3>个人参考 · v{{ visibleReference.versionNumber }}</h3><NButton text size="small" :disabled="locked" @click="visibleReference = undefined">收起参考</NButton></header><p class="field-help">由用户填写，尚不能据此认定技术内容已经审核。</p><MarkdownPreview :value="visibleReference.reference" /></section>
        <p v-if="current.status === 'ARCHIVED'" class="notice">这张卡片已归档。历史和参考仍可查看；恢复后可以继续编辑。</p>
        <div class="row-actions"><NButton :disabled="blocked || !currentReady || current.revision >= 1000 || (current.status === 'ACTIVE' && dirty) || (!!editBase && (stale || needsReview))" :loading="writer.busy.value" @click="changeStatus">{{ current.status === 'ACTIVE' ? '归档卡片' : '恢复卡片' }}</NButton><NButton text :disabled="blocked" :loading="serverLoading" @click="compareServer">核对服务器版本</NButton></div>
      </section>
      <section v-show="tab === 'edit'" v-if="editing" class="card-form">
        <p v-if="editBase" class="field-help">编辑基线：v{{ editBase.currentVersion.number }} · 对象修订 {{ editBase.revision }}。题面、参考、来源与相关概念将一起保存为新版本。</p>
        <p v-if="editBase?.status === 'ARCHIVED'" class="notice">编辑基线已归档，草稿继续保留；请恢复卡片后再保存。</p>
        <p v-if="stale || needsReview" class="notice">卡片修订或访问条件已变化，未保存内容保留。<NButton text :disabled="blocked" :loading="serverLoading" @click="compareServer">读取服务器版本并对照</NButton></p>
        <p v-if="limitReached" class="notice">卡片已达到版本或对象修订上限，无法继续追加内容版本。</p>
        <label>卡片类型<NSelect :value="content.kind" :options="Object.entries(memoryKindLabels).map(([value, label]) => ({ value, label }))" :disabled="!editable" @update:value="updateKind" /></label>
        <p v-if="content.kind === 'CLOZE'" class="field-help">请在题面手工写出空位，例如“HashMap 的默认负载因子为 ____”。本页不会解释挖空语法、自动遮蔽文字或拆成多张子卡。</p>
        <label>卡片标题<NInput :value="readable ? content.title : ''" :disabled="!editable" placeholder="用一句话说明这张卡要回忆什么" @update:value="updateText('title', $event)" /></label>
        <label>{{ content.kind === 'CLOZE' ? '带有手工空位的题面' : '问题题面' }}<NInput :value="readable ? content.prompt : ''" type="textarea" :autosize="{ minRows: 4, maxRows: 14 }" :disabled="!editable" placeholder="支持 Markdown 和代码文字；请把需要回忆的提示写在这里。" @update:value="updateText('prompt', $event)" /></label>
        <label>个人参考<NInput :value="readable ? content.reference : ''" type="textarea" :autosize="{ minRows: 5, maxRows: 16 }" :disabled="!editable" placeholder="填写你采用的参考，并保留必要条件与限制。" @update:value="updateText('reference', $event)" /><span class="field-help">参考不会默认出现在卡片题面和历史列表中。编辑保存会提交这一份完整参考。</span></label>
        <MemorySourcePicker :sources="content.sources" :related-concept-ids="content.relatedConceptIds" :retained-sources="editBase?.sourceReferences" :retained-concepts="editBase?.relatedConcepts" :disabled="!editable" @update:sources="updateSources" @update:related-concept-ids="updateConcepts" />
        <p v-if="problem" class="field-help" role="status">{{ problem }}</p>
        <div class="row-actions"><NButton type="primary" :disabled="!editable || !!problem || (!creating && (!dirty || stale || needsReview || limitReached || !currentReady))" :loading="writer.busy.value" @click="save">{{ creating ? '保存卡片' : '保存为新版本' }}</NButton><NButton v-if="!creating" text :disabled="blocked" @click="finishEditing">结束编辑</NButton><NButton v-if="editBase?.status === 'ARCHIVED'" :disabled="blocked || stale || needsReview || !currentReady || editBase.revision >= 1000" @click="changeStatus">恢复卡片并保留草稿</NButton></div>
      </section>
      <p v-if="serverError" class="notice error" role="alert">{{ serverError }}</p>
      <section v-if="serverCopy" class="server-comparison"><header><h3>服务器 v{{ serverCopy.currentVersion.number }} · 修订 {{ serverCopy.revision }}</h3><span class="field-help">{{ memoryStatusLabels[serverCopy.status] }}</span></header><strong>{{ serverCopy.currentVersion.content.title }}</strong><MarkdownPreview v-if="readable" :value="serverCopy.currentVersion.content.prompt" /><MemorySources :references="serverCopy.sourceReferences" :concepts="serverCopy.relatedConcepts" :disabled="!readable || locked || otherLocked" /><NButton v-if="!serverReferenceReady" :disabled="blocked" :loading="writer.busy.value" @click="requestReference(serverCopy, 'compare')">读取这份服务器版本的参考并对照</NButton><template v-else><section v-if="readable && serverReference" class="reference-readout"><h4>服务器这份版本的个人参考</h4><MarkdownPreview :value="serverReference.reference" /></section><p class="field-help">保留本地改写会保留当前表单的完整题面和参考，同时采用这里的对象修订；来源是否可保留以服务器这个卡片版本为准。</p><NCheckbox v-if="dirty" v-model:checked="replaceConfirmed" :disabled="blocked">允许用服务器完整内容替换当前尚未保存的编辑草稿</NCheckbox><div class="row-actions"><NButton v-if="editing" :disabled="blocked || !current || !sameCard(serverCopy, current)" @click="useServer(true)">保留本地改写，采用服务器修订</NButton><NButton :disabled="blocked || !current || !sameCard(serverCopy, current) || (dirty && !replaceConfirmed)" @click="useServer(false)">采用服务器完整内容</NButton></div></template></section>
      <MemoryCardHistory v-if="current && !creating" v-show="tab === 'history'" :id="current.id" :current-version="current.currentVersion" :version-id="historyVersion" :disabled="!readable || tab !== 'history' || locked || serverLoading" @select="selectVersion" />
      <MemoryRecallState v-if="id && !creating" v-show="tab === 'review'" :id="id" :card="current" :disabled="!readable || tab !== 'review' || locked || serverLoading" :mutation-disabled="dirty || locked || serverLoading || receiptMismatch" @changed="refresh" />
    </div>
  </section>
</template>

<style scoped>
.memory-card-editor { display: grid; align-content: start; gap: 18px; min-width: 0; padding: 24px 27px 30px; }
.editor-heading, .reference-readout header, .server-comparison header { display: flex; justify-content: space-between; align-items: start; flex-wrap: wrap; gap: 14px; }
.editor-heading > div { min-width: 0; } h2 { margin: 7px 0; overflow-wrap: anywhere; } h3, h4, p { margin: 0; } h3 { font-size: 16px; } h4 { font-size: 14px; }
.editor-content, .card-readout, .card-form, .reference-readout, .server-comparison { display: grid; gap: 16px; min-width: 0; }
.editor-tabs { display: flex; flex-wrap: wrap; gap: 20px; border-bottom: 1px solid var(--line); } .editor-tabs button { padding: 9px 0; border-bottom: 2px solid transparent; color: var(--muted); font-size: 13px; } .editor-tabs button[aria-pressed=true] { color: var(--brand-strong); border-color: var(--brand); font-weight: 650; }
.card-form > label { display: grid; gap: 8px; font-size: 13px; } .reference-readout, .server-comparison { padding: 18px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); overflow-wrap: anywhere; }
.row-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; } .server-comparison > .n-button { justify-self: start; }
@media (max-width: 700px) { .memory-card-editor { padding: 22px 16px; } .editor-tabs { gap: 16px; } .reference-readout, .server-comparison { padding: 14px; } }
</style>
