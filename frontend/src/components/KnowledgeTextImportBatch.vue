<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { Schema, Topic } from '../api/client'
import type { OpenKnowledge } from '../api/knowledge'
import { inspectImportLinks, rewriteImportLinks } from '../api/knowledgeTextLinks'
import type { ImportLinkPlan } from '../api/knowledgeTextLinks'
import { suggestImportLinks } from '../api/knowledgeTextCandidates'
import type { ImportLinkCandidate } from '../api/knowledgeTextCandidates'
import { isTextImportPath as validPath } from '../api/knowledgeTextPath'
import { readTextZip } from '../api/knowledgeTextZip'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import KnowledgeImportRecords from './KnowledgeImportRecords.vue'

type Batch = Schema['KnowledgeTextImportBatchDetail']
type BatchPage = Schema['KnowledgeTextImportBatchPage']
type DirectoryDetail = Schema['KnowledgeTextImportBatchDirectoryDetail']
type LocalFile = { fileName: string; sourcePath: string; sourceSha256: string; sourceBytes: number; sourceCharacters: number; source: string }
const props = defineProps<{ enabled: boolean; topics: Topic[] }>()
const emit = defineEmits<{ open: [target: OpenKnowledge]; changed: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const element = ref<HTMLElement>(), elementVisible = ref(false), active = ref(true)
const documentVisible = ref(document.visibilityState === 'visible')
const files = shallowRef<LocalFile[]>([]), batch = shallowRef<Batch>(), history = shallowRef<BatchPage>()
const directorySelected = ref(false), pathBatchId = ref(''), paths = shallowRef<Record<string, string>>({})
const archiveName = ref('')
const repair = shallowRef<Schema['ItemDetail']>(), repairMessage = ref('')
const linkPlan = shallowRef<ImportLinkPlan>(), linkTargets = ref<Record<string, string>>({}), useMapped = ref(false)
const batchId = ref(''), selectedId = ref(''), loading = ref(false), reading = ref(false)
const problem = ref(''), cleared = ref(false), title = ref(''), topicIds = ref<string[]>([])
const createCopy = ref(false), confirmed = ref(false)
const readable = computed(() => active.value && props.enabled && store.sessionActive && documentVisible.value
  && elementVisible.value && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const entry = computed(() => batch.value?.entries.find(value => value.id === selectedId.value))
const selectedFile = computed(() => files.value.find(file => entry.value && file.fileName === entry.value.fileName
  && pathBatchId.value === batch.value?.id && file.sourcePath === paths.value[entry.value.id]
  && file.sourceSha256 === entry.value.sourceSha256 && file.sourceBytes === entry.value.sourceBytes
  && file.sourceCharacters === entry.value.sourceCharacters))
const pathsReady = computed(() => Boolean(batch.value && pathBatchId.value === batch.value.id
  && Object.keys(paths.value).length === batch.value.entries.length
  && batch.value.entries.every(item => validPath(paths.value[item.id], item.fileName, true))))
const duplicateInBatch = computed(() => Boolean(entry.value && batch.value?.entries.some(value =>
  value.id !== entry.value!.id && value.sourceSha256 === entry.value!.sourceSha256)))
const formChanged = computed(() => Boolean(entry.value?.state === 'IMPORTED' && repair.value
  && (confirmed.value || useMapped.value || Object.keys(linkTargets.value).length))
  || Boolean(entry.value?.state === 'PENDING' && selectedFile.value
  && (title.value !== (selectedFile.value.fileName.replace(/\.(md|markdown|txt)$/i, '').trim() || '导入笔记')
    || topicIds.value.length || createCopy.value || confirmed.value || useMapped.value || Object.keys(linkTargets.value).length)))
const topicOptions = computed(() => props.topics.map(topic => ({ label: topic.name, value: topic.id })))
const linkOptions = computed(() => pathsReady.value ? batch.value!.entries.filter(item =>
  item.id !== selectedId.value && item.state === 'IMPORTED' && item.record).map(item =>
  ({ value: item.id, label: `${paths.value[item.id]} · ${item.record!.title}` })) : [])
const candidates = computed(() => {
  const links: Record<string, ImportLinkCandidate> = {}, plan = linkPlan.value
  if (!plan || plan.problem || !pathsReady.value || !batch.value || !entry.value) return { links, problem: '' }
  const source = entry.value.state === 'IMPORTED' ? repair.value?.published.body : selectedFile.value?.source
  if (source === undefined || plan.source !== source) return { links, problem: '候选正文已变化，请重新读取并解析；原人工映射仍需核对全文。' }
  try {
    return { links: suggestImportLinks(plan, entry.value.id, batch.value.entries.map(item => ({
      id: item.id, sourcePath: paths.value[item.id]!, fileName: item.fileName,
      state: item.state, itemId: item.record?.itemId,
    }))), problem: '' }
  } catch (error) { return { links, problem: errorText(error) } }
})
const rewritten = computed(() => {
  const plan = linkPlan.value
  if (!plan || !Object.keys(linkTargets.value).length) return undefined
  try {
    const source = entry.value?.state === 'IMPORTED' ? repair.value?.published.body : selectedFile.value?.source
    if (source === undefined || plan.source !== source || !pathsReady.value) throw new Error('正文或路径清单已变化，请重新读取并解析链接。')
    const targets: Record<string, string> = {}
    for (const [key, id] of Object.entries(linkTargets.value)) {
      const target = batch.value!.entries.find(item => item.id === id && item.state === 'IMPORTED' && item.record)
      if (!target || target.id === selectedId.value) throw new Error('目标尚未成功导入，请重新选择或保留原文。')
      targets[key] = target.record!.itemId
    }
    const body = rewriteImportLinks(plan, targets)
    if (body.length > 100000) throw new Error('改写正文超过100000字符，不能截断；请减少映射或保留原文。')
    return { body, error: '' }
  } catch (error) { return { body: '', error: errorText(error) } }
})
const locked = computed(() => writer.locked.value || loading.value || reading.value)
const dirty = computed(() => files.value.length > 0 || reading.value || formChanged.value)
let alive = true, generation = 0, observer: MutationObserver | undefined
let writeContext = { session: 0, access: 0, generation: 0 }
const base = '/knowledge/text-import-batches'

watch([dirty, writer.locked], () => store.changes.set('text-import-batch', {
  dirty: dirty.value, locked: writer.locked.value,
}), { immediate: true, flush: 'sync' })
watch([selectedFile, selectedId], () => {
  title.value = selectedFile.value?.fileName.replace(/\.(md|markdown|txt)$/i, '').trim() || '导入笔记'
  topicIds.value = []; createCopy.value = false; confirmed.value = false
  linkPlan.value = undefined; linkTargets.value = {}; useMapped.value = false
  repair.value = undefined; repairMessage.value = ''
}, { flush: 'sync' })
watch([title, topicIds, createCopy, linkTargets, useMapped], () => { confirmed.value = false }, { deep: true, flush: 'sync' })
function clearPrivate() {
  generation++; files.value = []; batch.value = undefined; history.value = undefined
  selectedId.value = ''; title.value = ''; topicIds.value = []; confirmed.value = false
  createCopy.value = false; reading.value = false; loading.value = false; problem.value = ''
  directorySelected.value = false; pathBatchId.value = ''; paths.value = {}
  archiveName.value = ''
  repair.value = undefined; repairMessage.value = ''
  linkPlan.value = undefined; linkTargets.value = {}; useMapped.value = false
}
watch([writer.error, writer.pending], () => {
  if (writer.error.value && !writer.pending.value && repair.value) {
    // 确定失败后不能用旧版本预览换一个请求键重发，须重新读取并确认当前正文。
    repair.value = undefined; linkPlan.value = undefined; linkTargets.value = {}
    useMapped.value = false; confirmed.value = false
  }
}, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode,
  () => access.active?.materialAccess], () => {
  if (dirty.value || batch.value || history.value) cleared.value = true
  clearPrivate()
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
  document.removeEventListener('visibilitychange', visibilityChanged); store.changes.delete('text-import-batch')
})
function context() { return { session: store.sessionEpoch, access: currentTrainingAccessEpoch(), generation } }
function current(value: ReturnType<typeof context>) {
  return alive && readable.value && value.session === store.sessionEpoch
    && value.access === currentTrainingAccessEpoch() && value.generation === generation
    && document.visibilityState === 'visible'
}
function ready() {
  visibilityChanged(); elementVisibilityChanged()
  return readable.value && !locked.value
}
async function prepareFile(fileName: string, sourcePath: string, buffer: ArrayBuffer, ticket: ReturnType<typeof context>): Promise<LocalFile> {
  elementVisibilityChanged()
  if (!current(ticket)) throw new Error('读取条件已变化，请重新选择文件。')
  let source: string
  try { source = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(buffer) }
  catch { throw new Error(`「${sourcePath}」不是有效UTF-8，整批选择未保留。请另存为UTF-8后重选。`) }
  if (buffer.byteLength < 1 || buffer.byteLength > 400000 || source.length > 100000
    || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(source)) {
    throw new Error(`「${sourcePath}」超出文本大小／字符限制或含不支持的控制字符，整批选择未保留，不截断。`)
  }
  const sourceSha256 = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', buffer)),
    byte => byte.toString(16).padStart(2, '0')).join('')
  elementVisibilityChanged()
  if (!current(ticket)) throw new Error('读取条件已变化，请重新选择文件。')
  return { fileName, sourcePath, sourceSha256, sourceBytes: buffer.byteLength, sourceCharacters: source.length, source }
}
async function chooseFiles(event: Event, directory = false) {
  const input = event.target as HTMLInputElement, selected = Array.from(input.files ?? [])
  input.value = ''
  if (!ready() || !selected.length) return
  if ((files.value.length || formChanged.value) && !window.confirm('替换本地文件选择？尚未提交的标题、主题、副本及链接映射会清除，服务器批次进度保留。')) return
  if (!ready()) return
  files.value = []; archiveName.value = ''; problem.value = ''; cleared.value = false; confirmed.value = false
  repair.value = undefined; repairMessage.value = ''; linkPlan.value = undefined; linkTargets.value = {}; useMapped.value = false
  const ticket = context()
  const sourcePaths = new Set<string>()
  if (selected.length > 20 || selected.some(file => {
    const sourcePath = directory ? file.webkitRelativePath : file.name
    const valid = validPath(sourcePath, file.name, !directory) && !sourcePaths.has(sourcePath) && /\.(md|markdown|txt)$/i.test(file.name)
      && file.name.length <= 200 && !/[\u0000-\u001f\u007f/\\]/.test(file.name)
      && Number.isSafeInteger(file.size) && file.size >= 1 && file.size <= 400000
    sourcePaths.add(sourcePath); return !valid
  })) {
    problem.value = '每次选择1至20份UTF-8 Markdown／TXT，单份1至400000字节、文件名最多200字符；路径须唯一、最多600字符／32段且无空段、.、..、反斜线、冒号或控制字符。目录内附件、不支持文件或超限会拒绝整批，不静默跳过。'
    return
  }
  reading.value = true
  try {
    const prepared: LocalFile[] = []
    for (const file of selected) {
      const buffer = await file.arrayBuffer()
      elementVisibilityChanged()
      if (!current(ticket)) return
      if (buffer.byteLength !== file.size) throw new Error('实际读取大小无法核对，整批选择未保留，请重新选择。')
      prepared.push(await prepareFile(file.name, directory ? file.webkitRelativePath : file.name, buffer, ticket))
      elementVisibilityChanged()
      if (!current(ticket)) return
    }
    directorySelected.value = directory; files.value = prepared
  } catch (error) { if (current(ticket)) problem.value = errorText(error) }
  finally { if (alive && ticket.generation === generation) reading.value = false }
}
async function chooseZip(event: Event) {
  const input = event.target as HTMLInputElement, selected = Array.from(input.files ?? [])
  input.value = ''
  if (!ready() || !selected.length) return
  if ((files.value.length || formChanged.value) && !window.confirm('替换本地文件选择？尚未提交的标题、主题、副本及链接映射会清除，服务器批次进度保留。')) return
  if (!ready()) return
  files.value = []; archiveName.value = ''; problem.value = ''; cleared.value = false; confirmed.value = false
  repair.value = undefined; repairMessage.value = ''; linkPlan.value = undefined; linkTargets.value = {}; useMapped.value = false
  const file = selected[0]
  if (selected.length !== 1 || !file || !/\.zip$/i.test(file.name) || file.name.length > 200
    || !validPath(file.name, file.name, true) || !Number.isSafeInteger(file.size) || file.size < 1 || file.size > 8388608) {
    problem.value = '请选择一份1至8388608字节、文件名最多200字符的普通ZIP文本包；不会上传或写入磁盘。'
    return
  }
  const ticket = context()
  reading.value = true
  try {
    const buffer = await file.arrayBuffer()
    elementVisibilityChanged()
    if (!current(ticket)) return
    if (buffer.byteLength !== file.size) throw new Error('ZIP实际大小无法核对，整包选择未保留，请重选。')
    const entries = await readTextZip(buffer, () => { elementVisibilityChanged(); return current(ticket) })
    const prepared: LocalFile[] = []
    for (const item of entries) {
      prepared.push(await prepareFile(item.fileName, item.sourcePath, item.buffer, ticket))
    }
    elementVisibilityChanged()
    if (!current(ticket)) return
    directorySelected.value = true; archiveName.value = file.name; files.value = prepared
  } catch (error) { if (current(ticket)) problem.value = errorText(error) }
  finally { if (alive && ticket.generation === generation) reading.value = false }
}
async function load(path: string, kind: 'detail' | 'history') {
  if (!ready()) return
  if (kind === 'detail' && formChanged.value && !window.confirm('重新读取会清除本份尚未提交的标题、主题、副本及链接映射，确认继续？')) return
  if (!ready()) return
  generation++; loading.value = true; problem.value = ''
  const ticket = context()
  if (kind === 'detail') { batch.value = undefined; selectedId.value = ''; paths.value = {}; pathBatchId.value = '' }
  else history.value = undefined
  try {
    const value = await read<DirectoryDetail | BatchPage>(path)
    elementVisibilityChanged()
    if (current(ticket)) {
      if (kind === 'detail') acceptDirectory(value as DirectoryDetail)
      else history.value = value as BatchPage
    }
  } catch (error) { if (current(ticket)) problem.value = errorText(error) }
  finally { if (alive && ticket.generation === generation) loading.value = false }
}
async function loadRepair() {
  if (!ready() || !pathsReady.value || entry.value?.state !== 'IMPORTED' || !entry.value.record) return
  if (formChanged.value && !window.confirm('重新读取将清除尚未发布的本份链接映射，确认继续？')) return
  if (!ready() || entry.value?.state !== 'IMPORTED' || !entry.value.record) return
  const itemId = entry.value.record.itemId
  generation++; loading.value = true; problem.value = ''; repairMessage.value = ''
  repair.value = undefined; linkPlan.value = undefined; linkTargets.value = {}
  useMapped.value = false; confirmed.value = false
  const ticket = context()
  try {
    const value = await read<Schema['ItemDetail']>(`/knowledge/items/${itemId}`)
    elementVisibilityChanged()
    if (!current(ticket)) return
    if (value.id !== itemId || value.currentVersionId !== value.published.id
      || value.versionNumber !== value.published.number) throw new Error('当前发布版身份无法核对，请重新读取。')
    if (value.status !== 'ACTIVE') throw new Error('此知识已归档，请先在详情处理状态；不会自动恢复。')
    if (value.draft.title !== value.published.title || value.draft.body !== value.published.body) {
      throw new Error('此知识有未发布草稿，请先在详情处理草稿；不会覆盖或自动发布它。')
    }
    repair.value = value; linkPlan.value = inspectImportLinks(value.published.body)
  } catch (error) { if (current(ticket)) { repair.value = undefined; problem.value = errorText(error) } }
  finally { if (alive && ticket.generation === generation) loading.value = false }
}
function publishLinks() {
  if (!ready() || !pathsReady.value || !batch.value || entry.value?.state !== 'IMPORTED' || !entry.value.record
    || !repair.value || repair.value.id !== entry.value.record.itemId || !confirmed.value || !useMapped.value
    || !rewritten.value || rewritten.value.error || rewritten.value.body === repair.value.published.body) return
  const item = repair.value, body = rewritten.value.body, entryId = entry.value.id
  writeContext = context()
  void writer.run<Schema['ItemDetail']>(`${base}/${batch.value.id}/entries/${entryId}/publish-links`, 'POST', {
    expectedVersionId: item.currentVersionId, expectedDraftRevision: item.draft.revision, body,
  }, value => {
    elementVisibilityChanged()
    if (!current(writeContext)) throw new Error('读取条件已变化，请恢复权限后用原请求确认发布结果。')
    if (value.id !== item.id || value.currentVersionId !== value.published.id
      || value.versionNumber !== item.versionNumber + 1 || value.published.number !== value.versionNumber
      || value.published.title !== item.published.title || value.published.body !== body
      || value.draft.title !== value.published.title || value.draft.body !== body) {
      throw new Error('发布回执与已确认正文无法核对，请用原请求恢复。')
    }
    // 原回执可能在重新进入后恢复；只在当前仍选中同一项时保留正文，其余只显示完成提示。
    repair.value = entry.value?.id === entryId && entry.value.record?.itemId === item.id ? value : undefined
    linkPlan.value = undefined; linkTargets.value = {}; useMapped.value = false; confirmed.value = false
    repairMessage.value = `已确认发布 v${value.versionNumber}。首版导入记录保持；继续补齐前请重新读取当前版。`
    emit('changed')
  })
}
function loadHistory(cursor?: string) {
  const query = new URLSearchParams({ limit: '20' })
  if (cursor) query.set('cursor', cursor)
  void load(`${base}?${query}`, 'history')
}
function accept(value: Batch) {
  if (pathBatchId.value !== value.id || value.entries.some(item => !validPath(paths.value[item.id], item.fileName, true))) {
    paths.value = {}; pathBatchId.value = ''
  }
  batch.value = value; batchId.value = value.id
  if (!value.entries.some(item => item.id === selectedId.value && item.state === 'PENDING')) {
    selectedId.value = value.entries.find(item => item.state === 'PENDING')?.id ?? value.entries[0]?.id ?? ''
  }
  confirmed.value = false; createCopy.value = false; history.value = undefined
}
function acceptDirectory(value: DirectoryDetail) {
  if (value.paths.length !== value.batch.entries.length || new Set(value.paths.map(item => item.entryId)).size !== value.paths.length
    || new Set(value.paths.map(item => item.sourcePath)).size !== value.paths.length
    || value.paths.some(item => !value.batch.entries.some(target => target.id === item.entryId && validPath(item.sourcePath, target.fileName, true)))) {
    throw new Error('批次目录路径无法核对，请重新读取；不会按文件名猜匹配。')
  }
  paths.value = Object.fromEntries(value.paths.map(item => [item.entryId, item.sourcePath]))
  pathBatchId.value = value.batch.id
  accept(value.batch)
}
function receive(value: Batch, expected?: string) {
  elementVisibilityChanged()
  if (!current(writeContext)) throw new Error('读取条件已变化，结果暂不展示。恢复权限后请用原请求确认结果。')
  if (expected && value.id !== expected) throw new Error('响应批次无法核对，请用原请求恢复。')
  accept(value); emit('changed')
}
function createBatch() {
  if (!ready() || !files.value.length || batchId.value) return
  writeContext = context()
  if (directorySelected.value) {
    void writer.run<DirectoryDetail>(`${base}/directories`, 'POST',
      { files: files.value.map(({ source: _source, ...metadata }) => metadata) }, value => {
        elementVisibilityChanged()
        if (!current(writeContext)) throw new Error('读取条件已变化，恢复权限后请用原请求确认目录批次。')
        acceptDirectory(value)
      })
  } else {
    void writer.run<Batch>(base, 'POST',
      { files: files.value.map(({ fileName, sourceSha256, sourceBytes, sourceCharacters }) => ({ fileName, sourceSha256, sourceBytes, sourceCharacters })) },
      value => {
        elementVisibilityChanged()
        if (!current(writeContext)) throw new Error('读取条件已变化，恢复权限后请用原请求确认批次。')
        paths.value = Object.fromEntries(value.entries.map(item => [item.id, item.fileName]))
        pathBatchId.value = value.id
        receive(value)
      })
  }
}
function importEntry() {
  if (!ready() || !batch.value || !entry.value || entry.value.state !== 'PENDING'
    || !selectedFile.value || !pathsReady.value || !confirmed.value || !title.value.trim() || title.value.length > 200
    || topicIds.value.length > 20 || useMapped.value && (!rewritten.value || rewritten.value.error)) return
  const id = batch.value.id, entryId = entry.value.id
  writeContext = context()
  void writer.run<Batch>(`${base}/${id}/entries/${entryId}/import`, 'POST', {
    revision: batch.value.revision, source: selectedFile.value.source, title: title.value,
    body: useMapped.value ? rewritten.value!.body : selectedFile.value.source, topicIds: topicIds.value, createCopy: createCopy.value,
  }, value => receive(value, id))
}
function skipEntry() {
  if (!ready() || !batch.value || !pathsReady.value || !entry.value || entry.value.state !== 'PENDING') return
  const id = batch.value.id, entryId = entry.value.id, revision = batch.value.revision
  if (!window.confirm(`确认跳过「${paths.value[entryId]}」？该项不能在本批次重新打开；不会删除原文件或已有知识。`)) return
  if (!ready() || batch.value?.id !== id || entry.value?.id !== entryId || batch.value.revision !== revision) return
  writeContext = context()
  void writer.run<Batch>(`${base}/${id}/entries/${entryId}/skip`, 'POST', { revision }, value => receive(value, id))
}
function retry() {
  visibilityChanged(); elementVisibilityChanged()
  if (!readable.value || writer.busy.value || !writer.pending.value) return
  writeContext = context(); void writer.retry()
}
function openBatch(id: string) {
  if (!ready()) return
  void load(`${base}/${id}/directory`, 'detail')
}
function chooseEntry(id: string) {
  if (!ready() || id === selectedId.value) return
  if (formChanged.value && !window.confirm('切换文件会清除本份尚未提交的标题、主题、副本及链接映射，确认继续？')) return
  if (ready()) selectedId.value = id
}
function inspectLinks() {
  if (!ready() || !pathsReady.value || !selectedFile.value || entry.value?.state !== 'PENDING') return
  linkTargets.value = {}; useMapped.value = false; confirmed.value = false
  try { linkPlan.value = inspectImportLinks(selectedFile.value.source) }
  catch { linkPlan.value = { source: selectedFile.value.source, links: [], problem: '链接无法安全解析，请保留原文；没有改写正文。' } }
}
function chooseLink(key: string, value: unknown) {
  if (!ready() || !linkPlan.value?.links.some(item => item.key === key) || !pathsReady.value) return
  if (value === null) { delete linkTargets.value[key]; useMapped.value = false; return }
  if (typeof value === 'string' && linkOptions.value.some(option => option.value === value)) {
    linkTargets.value = { ...linkTargets.value, [key]: value }; useMapped.value = false
  }
}
function reset() {
  if (!ready() || !store.canLeave(['text-import-batch'])) return
  clearPrivate(); batchId.value = ''; cleared.value = false; writer.error.value = undefined
}
function openRecord(target: OpenKnowledge) {
  if (!ready() || !store.canLeave(['text-import-batch'])) return
  emit('open', target)
}
</script>

<template>
  <section ref="element" class="text-import-batch" aria-label="多文件文本导入批次">
    <p class="notice">一次选择最多20份文本、一个符合上限的文本目录或一份受支持ZIP文本包，逐份核对后导入或跳过。ZIP只在浏览器内存检查；创建批次只发送文件名、相对路径、指纹与大小，提交某一份时才发送其原文和最终正文；不调用模型。服务器保留批次进度，刷新后重读批次并重选原文件继续。</p>
    <p class="field-help">目录路径包含所选根目录名；ZIP保留包内路径，不额外加压缩包名。它们作为不可变身份区分同名文件，不创建系统文件夹。可人工映射到本批已成功导入的知识；双方先导入后，可逐份读取当前版并补齐相互链接。批次YAML选项未接入，单份YAML或全库人工链接处理仍可用原“导入 Markdown／TXT”入口；不恢复外部ID、版本、已审状态或AI许可。</p>
    <p v-if="cleared" class="notice" role="status">可见性或访问条件已变化，本地文件及读取结果已清除。服务器进度保留，请主动重读并重选；未决请求仍须原键确认。</p>
    <div class="row-actions">
      <NButton :disabled="!readable || locked" @click="loadHistory()">读取批次历史</NButton>
      <NButton v-if="batchId" :disabled="!readable || locked" @click="load(`${base}/${batchId}/directory`, 'detail')">重新读取当前批次</NButton>
      <NButton :disabled="!readable || locked" @click="reset">开始另一批／清除本地选择</NButton>
    </div>
    <p v-if="loading || reading" role="status">{{ reading ? '正在本地逐份读取与核对，整批成功后才保留选择…' : '正在读取服务器批次元数据…' }}</p>
    <p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
    <p v-if="repairMessage" class="notice" role="status">{{ repairMessage }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}</p>
    <NButton v-if="writer.pending.value" :disabled="!readable || writer.busy.value" type="primary" @click="retry">用原请求确认结果</NButton>
    <template v-if="readable">
      <div v-if="history" class="batch-history">
        <p v-if="!history.items.length" role="status">没有已保存的批次。</p>
        <article v-for="item in history.items" :key="item.id">
          <strong>{{ item.state === 'COMPLETE' ? '已处理完毕' : '待继续' }} · {{ item.total }}份</strong>
          <p>已导入{{ item.imported }}份，已跳过{{ item.skipped }}份 · 创建时间（UTC）：{{ item.createdAt }}</p>
          <code>{{ item.id }}</code>
          <NButton size="small" :disabled="locked" @click="openBatch(item.id)">读取这个批次</NButton>
        </article>
        <NButton v-if="history.nextCursor" :disabled="locked" @click="loadHistory(history.nextCursor)">下一页</NButton>
      </div>
      <label class="file-field">选择原始文本（1至20份）<input type="file" multiple accept=".md,.markdown,.txt" :disabled="locked" @change="chooseFiles" /></label>
      <label class="file-field">选择文本目录（含子目录，总计1至20份）<input type="file" webkitdirectory="" multiple :disabled="locked" @change="chooseFiles($event, true)" /></label>
      <p class="field-help">目录选择依赖浏览器的文件夹选择能力；不支持时仍可使用普通文件选择。目录返回的文件必须全部为受支持文本，附件／不支持文件或数量超限会拒绝整批，不静默跳过。空目录不会形成笔记；不会读取所选范围之外的关联文件。</p>
      <label class="file-field">选择普通ZIP文本包（最多8MiB）<input type="file" accept=".zip" :disabled="locked" @change="chooseZip" /></label>
      <p class="field-help">ZIP限1至20份Markdown／TXT、最多128个含目录条目；单份解压最多400000字节、全部最多8000000字节、压缩比最多200倍。只支持普通单卷存储／Deflate、UTF-8或纯ASCII名称；显式目录须为空的存储记录。加密、ZIP64、特殊条目、附件、坏CRC或不支持的结构整包拒绝，不自动跳过。Deflate依赖浏览器原生能力；不支持时请先在外部解压并使用目录／普通文件入口。本页不写入磁盘。</p>
      <p v-if="archiveName" class="field-help">本地ZIP：{{ archiveName }}。仅保留本次通过检查的文本选择，ZIP原包、包名和压缩参数不登记为服务器来源身份。</p>
      <p v-if="files.length" class="field-help">本地已选{{ files.length }}份{{ archiveName ? 'ZIP文本' : directorySelected ? '目录文本' : '平面文本' }}，合计{{ files.reduce((sum, file) => sum + file.sourceBytes, 0) }}字节。严格UTF-8，单份最多400000字节／100000字符；保留BOM和换行。目录路径最多600字符／32段且互不重复；平面文件名互不重复。选择本身不发送网络请求。</p>
      <details v-if="files.length"><summary>核对本地相对路径、文件清单与指纹</summary><ol><li v-for="file in files" :key="file.sourcePath">{{ file.sourcePath }} · {{ file.sourceBytes }}字节／{{ file.sourceCharacters }}字符<br /><code>{{ file.sourceSha256 }}</code></li></ol></details>
      <NButton v-if="!batchId" type="primary" :disabled="locked || !files.length" @click="createBatch">确认文件清单并保存批次（尚不创建笔记）</NButton>
      <template v-if="batch">
        <p><strong>{{ batch.state === 'COMPLETE' ? '本批次全部处理完毕' : '逐份确认导入' }}</strong> · 修订{{ batch.revision }} · {{ batch.entries.filter(item => item.state === 'IMPORTED').length }}份已导入／{{ batch.entries.filter(item => item.state === 'SKIPPED').length }}份已跳过</p>
        <p class="field-help">批次号：{{ batch.id }}。进度读取只包含元数据，不读取正文；已完成状态不代表知识通过技术审阅。指纹匹配只是文件选择核对，不认证磁盘来源或作者。</p>
        <p v-if="!pathsReady" class="notice">本次只确认了处理回执，完整路径清单尚未读取。请主动“重新读取当前批次”后再导入、跳过或映射；不会按同名文件猜目标。</p>
        <div class="entry-list" role="group" aria-label="批次文件">
          <NButton v-for="item in batch.entries" :key="item.id" :disabled="locked" :aria-pressed="selectedId === item.id" :type="selectedId === item.id ? 'primary' : 'default'" @click="chooseEntry(item.id)">{{ item.ordinal }}. {{ paths[item.id] ?? item.fileName }} · {{ item.state === 'IMPORTED' ? '已导入' : item.state === 'SKIPPED' ? '已跳过' : '待处理' }}</NButton>
        </div>
        <article v-if="entry">
          <strong>{{ entry.fileName }} · {{ entry.sourceBytes }}字节／{{ entry.sourceCharacters }}字符</strong>
          <p v-if="paths[entry.id]">清单相对路径：{{ paths[entry.id] }}</p>
          <details><summary>查看批次声明的原文指纹</summary><code>{{ entry.sourceSha256 }}</code></details>
          <template v-if="entry.state === 'PENDING'">
            <p v-if="entry.hasRegisteredSource || duplicateInBatch" class="notice">存在已登记的相同原文或本批次同源文件。可以检查记录、跳过，或核对全文后明确创建独立副本；本次预览不代替提交事务中的最新查重。</p>
            <KnowledgeImportRecords :source-sha256="entry.sourceSha256" :enabled="readable && !locked" @open="openRecord" />
            <p v-if="!selectedFile" class="notice">请重新选择相对路径、文件名、原文指纹、字节数与字符数全部相同的原文件，才能预览并导入。目录需保留本次根目录名；旧平面批次用普通文件选择重选。服务器不保存未提交原文，不能恢复或猜测匹配。</p>
            <template v-else>
              <label>本份新笔记标题<NInput v-model:value="title" :maxlength="200" :disabled="locked" /></label>
              <label>本份主题（可选，最多20个）<NSelect v-model:value="topicIds" multiple :options="topicOptions" :disabled="locked" /></label>
              <pre tabindex="0" aria-label="本份待导入的完整原文">{{ selectedFile.source }}</pre>
              <NButton :disabled="locked || !pathsReady" @click="inspectLinks">{{ linkPlan ? '重新解析并清除本份映射' : '预览批次内链接映射（可选）' }}</NButton>
              <template v-if="linkPlan">
                <p v-if="linkPlan.problem" class="notice error" role="alert">{{ linkPlan.problem }}</p>
                <p v-else-if="!linkPlan.links.length" class="field-help">没有识别到本批支持的链接，保留完整原文。</p>
                <p class="field-help">只列出支持的普通Wiki、相对文本和旧站内地址。候选仅按批次字面文件身份预览，匹配不代表已选用；不按标题或别名猜配。仍可按完整路径人工选择本批已导入目标，相同类型／原地址的一组一同改写。前向或循环关系可先保留原文，双方导入后再逐份补齐。</p>
                <p v-if="candidates.problem" class="notice error" role="alert">{{ candidates.problem }}</p>
                <div v-for="link in linkPlan.links" :key="link.key" class="link-mapping">
                  <strong>{{ link.target }} · {{ link.occurrences.length }}处</strong>
                  <details><summary>核对本组原文片段</summary><pre v-for="occurrence in link.occurrences" :key="occurrence.from">{{ occurrence.original }}</pre></details>
                  <div v-if="candidates.links[link.key]" class="candidate-preview">
                    <p class="field-help">{{ candidates.links[link.key]?.reason }}</p>
                    <ul v-if="candidates.links[link.key]?.matches.length">
                      <li v-for="match in candidates.links[link.key]?.matches" :key="match.id">{{ match.sourcePath }} · {{ match.id === selectedId ? '当前文件' : match.state === 'IMPORTED' ? '已导入' : match.state === 'SKIPPED' ? '已跳过' : '待处理' }}</li>
                    </ul>
                    <NButton v-if="candidates.links[link.key]?.entryId" size="small" :disabled="locked" :aria-label="`选用${link.target}的唯一批次文件候选`" @click="chooseLink(link.key, candidates.links[link.key]?.entryId)">选用这个候选（仍需核对全文）</NButton>
                  </div>
                  <NSelect :value="linkTargets[link.key] ?? null" :options="linkOptions" clearable :disabled="locked" :aria-label="`为${link.target}选择本批已导入知识`" placeholder="保留原文，或选择已导入目标" @update:value="chooseLink(link.key, $event)" />
                </div>
                <p v-if="!linkOptions.length" class="notice">当前没有可选的已导入目标，可先导入被引用的文件，再回到本份重新读取并映射；不会为待处理项创建空知识。</p>
                <p v-if="rewritten?.error" class="notice error" role="alert">{{ rewritten.error }}</p>
                <template v-else-if="rewritten">
                  <p class="field-help">映射后的完整正文 · {{ rewritten.body.length }}字符。只改明确选中的片段，其他文本和换行保留；最终创建仍由服务器核知识引用归属，不固定或采用目标正文版本。</p>
                  <pre tabindex="0" aria-label="本份链接映射后的完整正文">{{ rewritten.body }}</pre>
                  <NCheckbox v-model:checked="useMapped" :disabled="locked">明确采用这份映射后的完整正文；不勾选则导入上方完整原文</NCheckbox>
                </template>
              </template>
              <NCheckbox v-model:checked="createCopy" :disabled="locked">明确新建副本：已有同源登记时仍另建独立笔记，不覆盖旧内容</NCheckbox>
              <NCheckbox v-model:checked="confirmed" :disabled="locked">已核对{{ useMapped ? '明确采用的映射后全文' : '完整原文' }}、标题与主题，确认创建新笔记及首版</NCheckbox>
              <NButton type="primary" :disabled="locked || !confirmed || !title.trim() || topicIds.length > 20 || useMapped && (!rewritten || !!rewritten.error)" @click="importEntry">确认并导入这一份</NButton>
            </template>
            <NButton :disabled="locked || !pathsReady" @click="skipEntry">明确跳过这一份</NButton>
          </template>
          <template v-else-if="entry.record">
            <p>导入首版：{{ entry.record.title }} · 知识当前状态：{{ entry.record.itemStatus === 'ARCHIVED' ? '已归档' : '可用' }}</p>
            <NButton :disabled="locked" @click="openRecord({ id: entry.record.itemId, versionId: entry.record.versionId })">打开确切导入首版</NButton>
            <NButton :disabled="locked || !pathsReady" @click="loadRepair">读取当前发布版并补齐本批链接</NButton>
            <template v-if="repair">
              <p class="field-help">本次读取／确认的发布版 v{{ repair.published.number }} · {{ repair.published.title }}。以下基于此次读取的正文；继续修订须重新读取当前版。标题、主题和导入首版保持，未发布草稿或版本冲突会阻止发布。</p>
              <pre tabindex="0" aria-label="链接补齐的当前发布版完整正文">{{ repair.published.body }}</pre>
              <p v-if="linkPlan?.problem" class="notice error" role="alert">{{ linkPlan.problem }}</p>
              <p v-else-if="linkPlan && !linkPlan.links.length" class="field-help">没有识别到可映射链接，保持当前版本。</p>
              <p v-if="candidates.problem" class="notice error" role="alert">{{ candidates.problem }}</p>
              <div v-for="link in linkPlan?.links ?? []" :key="link.key" class="link-mapping">
                <strong>{{ link.target }} · {{ link.occurrences.length }}处</strong>
                <details><summary>核对本组当前原文片段</summary><pre v-for="occurrence in link.occurrences" :key="occurrence.from">{{ occurrence.original }}</pre></details>
                <div v-if="candidates.links[link.key]" class="candidate-preview">
                  <p class="field-help">{{ candidates.links[link.key]?.reason }}</p>
                  <ul v-if="candidates.links[link.key]?.matches.length">
                    <li v-for="match in candidates.links[link.key]?.matches" :key="match.id">{{ match.sourcePath }} · {{ match.id === selectedId ? '当前文件' : match.state === 'IMPORTED' ? '已导入' : match.state === 'SKIPPED' ? '已跳过' : '待处理' }}</li>
                  </ul>
                  <NButton v-if="candidates.links[link.key]?.entryId" size="small" :disabled="locked" :aria-label="`选用${link.target}的唯一批次文件候选`" @click="chooseLink(link.key, candidates.links[link.key]?.entryId)">选用这个候选（仍需核对全文）</NButton>
                </div>
                <NSelect :value="linkTargets[link.key] ?? null" :options="linkOptions" clearable :disabled="locked" :aria-label="`为${link.target}选择本批已导入知识`" placeholder="保留原文，或人工选择已导入目标" @update:value="chooseLink(link.key, $event)" />
              </div>
              <p class="field-help">只选同批已导入的其他笔记，不按标题猜配。可先发布A指向B，再发布B指向A；每份是独立事务。发布新版本会沿原规则使相关回忆、受审依据和采用来源进入重新核验，不恢复外部资格。</p>
              <p v-if="rewritten?.error" class="notice error" role="alert">{{ rewritten.error }}</p>
              <template v-else-if="rewritten">
                <pre tabindex="0" aria-label="链接补齐后的完整待发布正文">{{ rewritten.body }}</pre>
                <NCheckbox v-model:checked="useMapped" :disabled="locked">明确采用这份链接改写后的完整正文</NCheckbox>
                <NCheckbox v-model:checked="confirmed" :disabled="locked || !useMapped">已核对当前版与完整改写，确认追加新版本及相关失效传播</NCheckbox>
                <NButton type="primary" :disabled="locked || !useMapped || !confirmed || rewritten.body === repair.published.body" @click="publishLinks">确认发布链接补齐新版本</NButton>
              </template>
            </template>
          </template>
          <p v-else>本份已明确跳过，不修改原文件或已有知识；需要导入时另建新批次。</p>
        </article>
      </template>
    </template>
  </section>
</template>

<style scoped>
.text-import-batch, .batch-history, article, label, .link-mapping, .candidate-preview { display: grid; gap: 10px; min-width: 0; }
.text-import-batch { justify-items: stretch; }
article { padding: 12px; border: 1px solid var(--line); }
p { margin: 0; }
strong, p, code, label, li { overflow-wrap: anywhere; }
.link-mapping { padding: 10px; border: 1px solid var(--line); }
input { max-width: 100%; }
.entry-list { display: flex; flex-wrap: wrap; gap: 8px; min-width: 0; }
.entry-list :deep(button) { max-width: 100%; height: auto; white-space: normal; overflow-wrap: anywhere; }
pre { box-sizing: border-box; width: 100%; max-height: 320px; overflow: auto; margin: 0; padding: 12px; border: 1px solid var(--line); white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
</style>
