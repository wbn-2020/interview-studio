<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { currentTrainingAccessEpoch, errorText, preview, read } from '../api/client'
import type { Topic } from '../api/client'
import type { Attachment } from '../api/material'
import { checkedPackageDetail, checkedPackagePreview, defaultPackageEdits, inspectPackageAttachmentLinks, packageLinks, packagePath,
  packageRequestSize, packageSource, preparePackageFile } from '../api/knowledgePackage'
import type { PackageAttachment, PackageDetail, PackageEdits, PackageLocalFile, PackagePage, PackagePlan, PackagePreview } from '../api/knowledgePackage'
import { inspectImportMetadata, removeImportFrontmatter } from '../api/knowledgeTextMetadata'
import { readTextZip } from '../api/knowledgeTextZip'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

const props = defineProps<{ enabled: boolean; topics: Topic[]; initialId?: string }>()
const emit = defineEmits<{ changed: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const root = ref<HTMLElement>(), active = ref(true), elementVisible = ref(false)
const documentVisible = ref(document.visibilityState === 'visible')
const files = shallowRef<PackageLocalFile[]>([]), edits = ref<Record<string, PackageEdits>>({})
const attachments = ref<PackageAttachment[]>([]), detail = shallowRef<PackageDetail>()
const history = shallowRef<PackagePage>(), cursor = ref<string>(), cursors = ref<(string | undefined)[]>([])
const packageId = ref(props.initialId ?? ''), selectedId = ref(''), createCopy = ref(false)
const loading = ref(false), reading = ref(false), previewing = ref(false), problem = ref(''), message = ref('')
const archiveName = ref(''), confirmed = ref(false), recheckRequired = ref(false)
const attachmentPath = ref(''), attachmentId = ref(''), checkedAttachment = shallowRef<Attachment>()
const frozen = shallowRef<{ request: PackagePlan; preview: PackagePreview; json: string }>()
const base = '/knowledge/packages', uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
let alive = true, generation = 0, observer: MutationObserver | undefined
let writeContext = { generation: 0, session: 0, access: 0 }
const readable = computed(() => active.value && props.enabled && store.sessionActive && documentVisible.value
  && elementVisible.value && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || loading.value || reading.value || previewing.value)
const registered = computed(() => Boolean(detail.value))
const committed = computed(() => Boolean(detail.value?.committedAt))
const dirty = computed(() => files.value.length > 0 || !registered.value
  && Boolean(attachments.value.length || attachmentPath.value || attachmentId.value))
const selectedFile = computed(() => files.value.find(file => file.sourceId === selectedId.value))
const selectedEdits = computed(() => edits.value[selectedId.value])
const metadata = computed(() => selectedFile.value ? inspectImportMetadata(selectedFile.value.source) : undefined)
const topicOptions = computed(() => props.topics.map(topic => ({ label: topic.name, value: topic.id })))
const targetOptions = computed(() => files.value.filter(file => edits.value[file.sourceId]?.decision === 'IMPORT')
  .map(file => ({ label: file.sourceId, value: file.sourceId })))
const attachmentOptions = computed(() => attachments.value.map(item => ({ label: item.sourceId, value: item.sourceId })))
const selectedLinks = computed(() => {
  const file = selectedFile.value, form = selectedEdits.value
  if (!file || !form) return undefined
  try {
    const body = form.removeMetadata ? removeImportFrontmatter(inspectImportMetadata(file.source), form.body) : form.body
    return { ...packageLinks(body, file.sourceId, files.value), problem: '' }
  } catch (error) { return { plan: undefined, targets: [], problem: errorText(error) } }
})
const selectedAttachmentLinks = computed(() => {
  const file = selectedFile.value, form = selectedEdits.value
  if (!file || !form) return undefined
  try {
    const body = form.removeMetadata ? removeImportFrontmatter(inspectImportMetadata(file.source), form.body) : form.body
    return inspectPackageAttachmentLinks(body)
  } catch { return undefined }
})
const finalSource = computed(() => {
  if (!selectedFile.value || !selectedEdits.value) return undefined
  try { return { item: packageSource(selectedFile.value, selectedEdits.value), problem: '' } }
  catch (error) { return { item: undefined, problem: errorText(error) } }
})
const recoverMatched = computed(() => !detail.value || detail.value.mappings.length === files.value.length
  && detail.value.mappings.every(item => files.value.some(file => file.sourceId === item.sourceId
    && file.sourceSha256 === item.sourceSha256 && file.sourceBytes === item.sourceBytes
    && file.source.length === item.sourceCharacters)))
const canCommit = computed(() => Boolean(readable.value && !locked.value && confirmed.value && detail.value && !committed.value
  && frozen.value && !frozen.value.preview.issues.length && recoverMatched.value
  && frozen.value.preview.packageKey === detail.value.packageKey && frozen.value.preview.planSha256 === detail.value.planSha256))

watch([dirty, writer.locked], () => store.changes.set('knowledge-package-import', { dirty: dirty.value, locked: writer.locked.value }), { immediate: true, flush: 'sync' })
watch([edits, attachments, createCopy], () => { frozen.value = undefined; confirmed.value = false }, { deep: true, flush: 'sync' })
watch([attachmentPath, attachmentId], () => { checkedAttachment.value = undefined }, { flush: 'sync' })
watch([writer.error, writer.pending], () => {
  if (writer.error.value && !writer.pending.value) { frozen.value = undefined; confirmed.value = false }
}, { flush: 'sync' })
watch(() => props.initialId, value => { if (!writer.locked.value) packageId.value = value ?? '' })
function clearPrivate() {
  generation++; files.value = []; edits.value = {}; attachments.value = []; detail.value = undefined
  history.value = undefined; selectedId.value = ''; frozen.value = undefined; confirmed.value = false
  cursor.value = undefined; cursors.value = []
  createCopy.value = false; loading.value = false; reading.value = false; previewing.value = false
  attachmentPath.value = ''; attachmentId.value = ''; checkedAttachment.value = undefined
  problem.value = ''; message.value = ''; archiveName.value = ''
}
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode,
  () => access.active?.materialAccess], () => {
  // 可见性和访问核对只撤销读资格，原文件及尚未保存的处理选择仍留在本组件内。
  if (dirty.value || detail.value || history.value) recheckRequired.value = true
  generation++; frozen.value = undefined; confirmed.value = false; checkedAttachment.value = undefined
  loading.value = false; reading.value = false; previewing.value = false
  history.value = undefined; cursor.value = undefined; cursors.value = []
  problem.value = ''; message.value = ''
}, { flush: 'sync' })
function visibilityChanged() { documentVisible.value = document.visibilityState === 'visible' }
function elementVisibilityChanged() {
  const node = root.value
  elementVisible.value = Boolean(node && node.getClientRects().length && !node.closest('[hidden], [inert], [aria-hidden="true"]'))
  for (let ancestor: HTMLElement | null = node ?? null; ancestor && elementVisible.value; ancestor = ancestor.parentElement) {
    const style = getComputedStyle(ancestor)
    if (style.visibility === 'hidden' || style.visibility === 'collapse') elementVisible.value = false
  }
}
onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged); elementVisibilityChanged()
  observer = new MutationObserver(elementVisibilityChanged)
  for (let node: HTMLElement | null = root.value ?? null; node; node = node.parentElement) {
    observer.observe(node, { attributes: true, attributeFilter: ['style', 'class', 'hidden', 'inert', 'aria-hidden'] })
  }
})
onActivated(() => { active.value = true; visibilityChanged(); elementVisibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; clearPrivate(); observer?.disconnect()
  document.removeEventListener('visibilitychange', visibilityChanged); store.changes.delete('knowledge-package-import')
})
function context() { return { generation, session: store.sessionEpoch, access: currentTrainingAccessEpoch() } }
function current(ticket: ReturnType<typeof context>) {
  elementVisibilityChanged()
  return alive && readable.value && document.visibilityState === 'visible' && ticket.generation === generation
    && ticket.session === store.sessionEpoch && ticket.access === currentTrainingAccessEpoch()
}
function ready() { visibilityChanged(); elementVisibilityChanged(); return readable.value && !locked.value }
function replaceAllowed() { return !dirty.value || window.confirm('替换本地文件及处理选择？服务器已登记的知识包保持，可重新选择原文件恢复。') }
async function choose(event: Event, mode: 'FILES' | 'DIRECTORY' | 'ZIP') {
  const input = event.target as HTMLInputElement, selected = Array.from(input.files ?? [])
  input.value = ''
  const session = store.sessionEpoch
  if (!selected.length) return
  // 原生文件选择器返回时可能先触发窗口聚焦；等待同一次访问核对，不能静默丢掉所选文件。
  if (access.checking && store.sessionActive && active.value && document.visibilityState === 'visible') {
    if (!await access.refresh() || !alive || session !== store.sessionEpoch) return
  }
  if (!ready() || !selected.length || !replaceAllowed() || !ready()) return
  const ticket = context()
  reading.value = true; problem.value = ''; message.value = ''; frozen.value = undefined; confirmed.value = false
  try {
    const prepared: PackageLocalFile[] = []
    if (mode === 'ZIP') {
      const file = selected[0]!
      if (selected.length !== 1 || !/\.zip$/i.test(file.name) || file.size < 1 || file.size > 8388608) throw new Error('请选择一份不超过8MiB的普通文本ZIP。')
      const buffer = await file.arrayBuffer()
      if (!current(ticket)) return
      if (buffer.byteLength !== file.size) throw new Error('ZIP实际字节数不一致，请重新选择。')
      const entries = await readTextZip(buffer, () => current(ticket))
      for (const item of entries) prepared.push(await preparePackageFile(item.fileName, item.sourcePath, item.buffer, () => current(ticket)))
    } else {
      if (selected.length > 20) throw new Error('每包最多20份文本；目录中的非文本文件也不能静默忽略。')
      for (const file of selected) {
        if (file.size < 1 || file.size > 400000) throw new Error(`「${file.name}」须为1至400000字节。`)
        const buffer = await file.arrayBuffer()
        if (!current(ticket)) return
        if (buffer.byteLength !== file.size) throw new Error('实际字节数不一致，请重新选择。')
        prepared.push(await preparePackageFile(file.name, mode === 'DIRECTORY' ? file.webkitRelativePath : file.name, buffer, () => current(ticket)))
      }
    }
    if (!current(ticket)) return
    if (!prepared.length || prepared.length > 20 || new Set(prepared.map(file => file.sourceId)).size !== prepared.length) throw new Error('知识包须有1至20份路径完全唯一的文本。')
    const saved = detail.value
    if (saved && (prepared.length !== saved.mappings.length || saved.mappings.some(item => !prepared.some(file =>
      file.sourceId === item.sourceId && file.sourceSha256 === item.sourceSha256 && file.sourceBytes === item.sourceBytes
      && file.source.length === item.sourceCharacters)))) throw new Error('重选文件与已登记包的路径、原文SHA或长度不一致，整批未替换；请使用相同选择入口和原文件。')
    files.value = prepared; edits.value = Object.fromEntries(prepared.map(file => [file.sourceId,
      defaultPackageEdits(file, saved?.mappings.find(item => item.sourceId === file.sourceId))]))
    selectedId.value = prepared[0]!.sourceId; archiveName.value = mode === 'ZIP' ? selected[0]!.name : ''
  } catch (error) { if (current(ticket)) problem.value = errorText(error) }
  finally { if (alive && ticket.generation === generation) reading.value = false }
}
function requestPlan(): PackagePlan {
  if (!files.value.length || !recoverMatched.value) throw new Error('请先选择与登记清单一致的完整原文件。')
  const request = { items: files.value.map(file => {
    const form = edits.value[file.sourceId]
    if (!form) throw new Error('文件处理选择缺失，请重新选择。')
    return packageSource(file, form)
  }), sourceAttachments: attachments.value.map(item => ({ ...item })) }
  packageRequestSize(request)
  return request
}
async function inspect() {
  if (!ready() || committed.value) return
  problem.value = ''; message.value = ''; confirmed.value = false; frozen.value = undefined
  let request: PackagePlan
  try { request = requestPlan() } catch (error) { problem.value = errorText(error); return }
  const ticket = context()
  previewing.value = true
  try {
    const value = checkedPackagePreview(await preview<unknown>(`${base}/preview`, request))
    if (!current(ticket)) return
    if (value.importCount + value.skipCount !== request.items.length) throw new Error('服务器预览文件数量无法核对。')
    frozen.value = { request, preview: value, json: JSON.stringify(request) }
    recheckRequired.value = false
    if (detail.value && (value.packageKey !== detail.value.packageKey || value.planSha256 !== detail.value.planSha256)) {
      problem.value = '原文件已匹配，但处理方案与登记指纹不同。请还原登记时的正文、移除元数据及链接选择后重新预览；不能改写登记方案。'
    }
  } catch (error) { if (current(ticket)) problem.value = errorText(error) }
  finally { if (alive && ticket.generation === generation) previewing.value = false }
}
function checkedFrozen() {
  const value = frozen.value
  if (!value || value.preview.issues.length || value.preview.importCount < 1 || !confirmed.value) throw new Error('请先完成无阻断问题的整包预览并明确确认。')
  if (JSON.stringify(requestPlan()) !== value.json) throw new Error('处理方案已变化，请重新预览。')
  return value
}
function accept(value: unknown, key?: string, planSha?: string) {
  const saved = checkedPackageDetail(value)
  if (key && saved.packageKey !== key || planSha && saved.planSha256 !== planSha) throw new Error('知识包回执与确认方案不一致，请用原请求重试。')
  detail.value = saved; packageId.value = saved.id; confirmed.value = false
  return saved
}
function register() {
  if (!ready() || registered.value) return
  try {
    const value = checkedFrozen()
    writeContext = context()
    const request = { packageKey: value.preview.packageKey, createCopy: createCopy.value, ...value.request }
    packageRequestSize(request)
    void writer.run<unknown>(base, 'POST', request, result => {
      if (!current(writeContext)) throw new Error('资料权限或页面状态已变化，请恢复后用原请求确认登记结果。')
      // 预览后另一标签也可能先登记同包；默认复用其不可变方案，不能把合法复用误作未知失败。
      const saved = accept(result, value.preview.packageKey, request.createCopy ? value.preview.planSha256 : undefined)
      const reused = !request.createCopy && (Boolean(value.preview.existingPackageId) || saved.planSha256 !== value.preview.planSha256)
      if (value.preview.existingPackageId && !request.createCopy && saved.id !== value.preview.existingPackageId) throw new Error('复用回执的知识包身份不一致，请用原请求核对。')
      attachments.value = saved.sourceAttachments.map(item => ({ ...item }))
      if (saved.committedAt) { files.value = []; edits.value = {}; frozen.value = undefined; message.value = '已复用此包的持久映射，没有再次创建知识。' }
      else if (reused && saved.planSha256 !== value.preview.planSha256) {
        files.value = []; edits.value = {}; frozen.value = undefined
        message.value = '已复用原有登记。此次不同的处理选择没有覆盖原方案，请重新选择原文件并恢复登记方案；或开始新包并明确创建副本。'
      } else message.value = '方案已登记，尚未创建知识。请再次核对并确认提交；原文件和正文没有写入登记记录。'
    })
  } catch (error) { problem.value = errorText(error) }
}
function commit() {
  if (!canCommit.value || !detail.value) return
  try {
    const value = checkedFrozen(), before = detail.value
    writeContext = context()
    const request = { expectedRevision: before.revision, expectedPlanSha256: before.planSha256, ...value.request }
    packageRequestSize(request)
    void writer.run<unknown>(`${base}/${before.id}/commit`, 'POST', request, result => {
      if (!current(writeContext)) throw new Error('资料权限或页面状态已变化，请恢复后用原请求确认提交结果。')
      const saved = accept(result, before.packageKey, before.planSha256)
      if (saved.id !== before.id || !saved.committedAt || saved.revision !== before.revision + 1
        || saved.mappings.length !== before.mappings.length || saved.mappings.some(item => {
          const original = before.mappings.find(value => value.sourceId === item.sourceId)
          return !original || item.itemId !== original.itemId || item.versionId !== original.versionId
            || item.sourceSha256 !== original.sourceSha256 || item.decision !== original.decision
        })) throw new Error('提交回执缺少完整或一致的知识映射，请使用原请求恢复。')
      files.value = []; edits.value = {}; frozen.value = undefined; selectedId.value = ''
      message.value = '整包已提交。来源到知识首版的映射已保存，可从下面逐项查看。'; emit('changed')
    })
  } catch (error) { problem.value = errorText(error) }
}
async function retry() {
  visibilityChanged(); elementVisibilityChanged()
  if (!readable.value || writer.busy.value || !writer.pending.value) return
  // 未知结果始终重试原键和原正文；恢复后回执只能在当前同一会话与资料权限下展示。
  writeContext = context(); await writer.retry()
}
async function loadPackage(id = packageId.value) {
  if (!ready() || !uuid.test(id)) { if (!uuid.test(id)) problem.value = '请输入准确的知识包标识。'; return }
  if (!replaceAllowed() || !ready()) return
  generation++; const ticket = context()
  loading.value = true; files.value = []; edits.value = {}; selectedId.value = ''; frozen.value = undefined
  problem.value = ''; message.value = ''; confirmed.value = false
  try {
    const value = await read<unknown>(`${base}/${id}`)
    if (!current(ticket)) return
    const saved = accept(value)
    if (saved.id !== id) throw new Error('服务器返回的知识包身份不一致。')
    attachments.value = saved.sourceAttachments.map(item => ({ ...item })); createCopy.value = saved.createCopy
    recheckRequired.value = false
  } catch (error) { if (current(ticket)) { detail.value = undefined; attachments.value = []; problem.value = errorText(error) } }
  finally { if (alive && ticket.generation === generation) loading.value = false }
}
async function loadHistory(direction: 'RESET' | 'NEXT' | 'PREVIOUS' = 'RESET') {
  if (!ready()) return
  let next = cursor.value
  if (direction === 'NEXT') { if (!history.value?.nextCursor) return; next = history.value.nextCursor }
  else if (direction === 'PREVIOUS') { if (!cursors.value.length) return; next = cursors.value.at(-1) }
  else next = undefined
  const ticket = context()
  loading.value = true; problem.value = ''
  try {
    const value = await read<PackagePage>(`${base}?limit=20${next ? `&cursor=${encodeURIComponent(next)}` : ''}`)
    if (!current(ticket)) return
    if (!Array.isArray(value.items) || value.items.length > 20 || value.items.some(item => !uuid.test(item.id))) throw new Error('知识包目录回执无法核对。')
    if (direction === 'NEXT') cursors.value.push(cursor.value)
    else if (direction === 'PREVIOUS') cursors.value.pop()
    else cursors.value = []
    cursor.value = next; history.value = value
  } catch (error) { if (current(ticket)) problem.value = errorText(error) }
  finally { if (alive && ticket.generation === generation) loading.value = false }
}
function newPackage() {
  if (!ready() || !replaceAllowed() || !ready()) return
  clearPrivate(); packageId.value = ''; cursor.value = undefined; cursors.value = []; recheckRequired.value = false
}
function kindChanged(value: 'NOTE' | 'CONCEPT') {
  const form = selectedEdits.value
  if (!form || locked.value || registered.value) return
  if (value === 'NOTE' && (form.aliasesText || form.adoptAliases) && !window.confirm('改为笔记会清空尚未登记的概念别名选择，继续？')) return
  form.kind = value
  if (value === 'NOTE') { form.aliasesText = ''; form.adoptAliases = false }
}
function mapLink(key: string, target: string | null) {
  if (!ready() || !selectedEdits.value) return
  if (target) selectedEdits.value.links[key] = { disposition: 'MAP', target }
  else delete selectedEdits.value.links[key]
}
function preserveLink(key: string, checked: boolean, attachment = false) {
  if (!ready() || !selectedEdits.value) return
  const choices = attachment ? selectedEdits.value.attachmentLinks : selectedEdits.value.links
  if (checked) choices[key] = { disposition: 'PRESERVE', target: '' }
  else delete choices[key]
}
function mapAttachmentLink(key: string, target: string | null) {
  if (!ready() || !selectedEdits.value) return
  if (target) selectedEdits.value.attachmentLinks[key] = { disposition: 'MAP', target }
  else delete selectedEdits.value.attachmentLinks[key]
}
async function checkAttachment() {
  if (!ready() || registered.value || !packagePath(attachmentPath.value) || !uuid.test(attachmentId.value)) {
    problem.value = '请输入包内准确附件路径和已上传附件的准确UUID。'; return
  }
  const ticket = context(), id = attachmentId.value
  loading.value = true; problem.value = ''; checkedAttachment.value = undefined
  try {
    const value = await read<Attachment>(`/materials/attachments/${id}`)
    if (!current(ticket)) return
    if (value.id !== id || value.status !== 'READY' || !/^[0-9a-f]{64}$/.test(value.sha256)) throw new Error('该附件尚未就绪或指纹无法核对。')
    checkedAttachment.value = value
  } catch (error) { if (current(ticket)) problem.value = errorText(error) }
  finally { if (alive && ticket.generation === generation) loading.value = false }
}
function addAttachment() {
  const value = checkedAttachment.value
  if (!ready() || registered.value || !value || value.id !== attachmentId.value || !packagePath(attachmentPath.value)) return
  if (attachments.value.length >= 20 || attachments.value.some(item => item.sourceId === attachmentPath.value)
    || files.value.some(item => item.sourceId === attachmentPath.value)) { problem.value = '附件路径不能重复或与文本路径相同，每包最多20份附件映射。'; return }
  attachments.value.push({ sourceId: attachmentPath.value, attachmentId: value.id, sourceSha256: value.sha256 })
  attachmentPath.value = ''; attachmentId.value = ''; checkedAttachment.value = undefined
}
</script>

<template>
  <section ref="root" class="package-workspace" aria-label="知识包导入">
    <p class="field-help">每包1–20份UTF-8 Markdown／TXT。原始source、处理后正文和用户选择分别核对；默认不采用YAML属性，不更改审核状态或模型授权。</p>
    <p v-if="!readable" class="notice" role="status">当前资料访问或页面状态不允许读取知识包，请先恢复登录及训练资料权限。</p>
    <div v-show="readable" class="package-content" :inert="!readable" :aria-hidden="!readable || undefined">
    <p v-if="recheckRequired" class="notice">资料访问或页面状态已变化，原文件和处理选择仍保留。请重新预览并确认后再提交；未知写入继续使用原请求重试。</p>
    <p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }} <NButton v-if="writer.pending.value" text :disabled="!readable || writer.busy.value" @click="retry">使用原请求重试</NButton></p>
    <div class="row-actions"><NButton :disabled="!readable || locked" @click="newPackage">开始新包</NButton><NButton :disabled="!readable || locked" @click="loadHistory('RESET')">读取已登记知识包</NButton></div>
    <form class="package-resume" @submit.prevent="loadPackage()"><NInput v-model:value="packageId" :disabled="!readable || locked" :maxlength="36" placeholder="按准确知识包UUID恢复" aria-label="知识包UUID" /><NButton attr-type="submit" :disabled="!readable || locked">读取</NButton></form>
    <div v-if="history" class="package-history"><button v-for="item in history.items" :key="item.id" :disabled="!readable || locked" @click="loadPackage(item.id)"><strong>{{ item.committedAt ? '已提交' : '已登记待提交' }} · {{ item.importCount }}导入 / {{ item.skipCount }}跳过</strong><span>{{ item.id }}</span><small>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}{{ item.createCopy ? ' · 明确创建副本' : '' }}</small></button><p v-if="!history.items.length" class="field-help">尚无知识包记录。</p><div class="row-actions"><NButton size="small" :disabled="!readable || locked || !cursors.length" @click="loadHistory('PREVIOUS')">较新记录</NButton><NButton size="small" :disabled="!readable || locked || !history.nextCursor" @click="loadHistory('NEXT')">较早记录</NButton></div></div>
    <section v-if="detail" class="package-registered"><h2>{{ committed ? '知识包已提交' : '已登记，等待原文件和提交确认' }}</h2><p class="field-help">{{ detail.id }} · 修订{{ detail.revision }} · {{ detail.state }}</p><details><summary>包身份及登记方案指纹</summary><p class="fingerprint">包：{{ detail.packageKey }}<br>方案：{{ detail.planSha256 }}</p></details><p v-if="!committed" class="notice">刷新恢复须重选完全相同的原文件。标题、类型、标签、别名、主题和导入／跳过选择从登记记录恢复；正文编辑、移除元数据和链接映射需重新还原，并使预览方案指纹完全一致后才能提交。</p><div class="package-mappings"><article v-for="item in detail.mappings" :key="item.sourceId"><strong>{{ item.sourceId }}</strong><span>{{ item.decision === 'SKIP' ? '明确跳过' : item.kind === 'CONCEPT' ? '知识概念' : '独立笔记' }} · {{ item.title }}</span><RouterLink v-if="committed && item.itemId" class="text-link" :to="{ name: 'knowledge', query: { item: item.itemId, version: item.versionId ?? undefined } }">查看导入首版</RouterLink></article></div></section>
    <section v-if="committed && detail?.sourceAttachments.length" class="package-attachments"><h2>已保存的包内附件映射</h2><article v-for="item in detail.sourceAttachments" :key="item.sourceId"><strong>{{ item.sourceId }}</strong><RouterLink class="text-link" :to="{ name: 'materials', query: { attachment: item.attachmentId } }">查看私有原件</RouterLink><span class="fingerprint">{{ item.sourceSha256 }}</span></article></section>
    <template v-if="!committed">
      <div class="package-selectors"><label>选择文本文件<input type="file" accept=".md,.markdown,.txt" multiple :disabled="!readable || locked" @change="choose($event, 'FILES')"></label><label>选择纯文本目录<input type="file" webkitdirectory multiple :disabled="!readable || locked" @change="choose($event, 'DIRECTORY')"></label><label>选择文本ZIP<input type="file" accept=".zip" :disabled="!readable || locked" @change="choose($event, 'ZIP')"></label></div>
      <p class="field-help">目录／ZIP保持完整相对路径，全部读取成功才替换选择，不扫描磁盘或上传压缩包。单文件最多400000字节／100000字符；整包JSON请求最多1000000字节。附件请先在<RouterLink class="text-link" :to="{ name: 'materials' }">附件资料</RouterLink>上传，再引用准确ID。</p>
      <p v-if="reading" role="status" class="field-help">正在读取并计算原文指纹…</p>
      <div v-if="files.length" class="package-editor"><aside aria-label="包内文本"><p class="field-help">{{ files.length }}份{{ archiveName ? ` · ${archiveName}` : '' }}</p><button v-for="file in files" :key="file.sourceId" :class="{ selected: selectedId === file.sourceId }" :disabled="!readable || locked" @click="selectedId = file.sourceId"><strong>{{ file.sourceId }}</strong><small>{{ edits[file.sourceId]?.decision === 'SKIP' ? '明确跳过' : '导入' }} · {{ file.sourceBytes }}字节</small></button></aside>
        <div v-if="selectedFile && selectedEdits" class="package-form"><h2>{{ selectedFile.sourceId }}</h2><details><summary>原始文件指纹</summary><p class="fingerprint">{{ selectedFile.sourceSha256 }} · {{ selectedFile.source.length }}字符</p></details><div class="package-two-columns"><label>本份处理<NSelect v-model:value="selectedEdits.decision" :disabled="!readable || locked || registered" :options="[{ label: '导入', value: 'IMPORT' }, { label: '明确跳过', value: 'SKIP' }]" /></label><label>知识类型<NSelect :value="selectedEdits.kind" :disabled="!readable || locked || registered" :options="[{ label: '独立笔记', value: 'NOTE' }, { label: '知识概念', value: 'CONCEPT' }]" @update:value="kindChanged" /></label></div>
          <label>最终标题<NInput v-model:value="selectedEdits.title" :maxlength="200" :disabled="!readable || locked || registered || selectedEdits.adoptTitle" /></label>
          <div class="package-options"><NCheckbox v-model:checked="selectedEdits.adoptTitle" :disabled="!readable || locked || registered || !metadata?.title">明确采用YAML title</NCheckbox><NCheckbox v-model:checked="selectedEdits.adoptTags" :disabled="!readable || locked || registered">明确采用YAML tags／tag</NCheckbox><NCheckbox v-model:checked="selectedEdits.adoptAliases" :disabled="!readable || locked || registered || selectedEdits.kind !== 'CONCEPT'">明确采用YAML aliases／alias（仅概念）</NCheckbox><NCheckbox v-model:checked="selectedEdits.removeMetadata" :disabled="!readable || locked || !metadata?.span || Boolean(metadata?.problem)">从最终正文移除完整YAML块</NCheckbox></div>
          <p v-if="metadata?.problem" class="field-help">{{ metadata.problem }}</p><details v-if="metadata?.fields.length"><summary>元数据预览（未勾选不会采用）</summary><p v-for="field in metadata.fields" :key="field.key" class="field-help">{{ field.key }} · {{ field.values?.join(' / ') ?? '未知或不支持，保留原文' }}</p></details>
          <div class="package-two-columns"><label>标签（每行一项）<NInput v-model:value="selectedEdits.tagsText" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" :disabled="!readable || locked || registered || selectedEdits.adoptTags" /></label><label v-if="selectedEdits.kind === 'CONCEPT'">概念别名（每行一项）<NInput v-model:value="selectedEdits.aliasesText" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" :disabled="!readable || locked || registered || selectedEdits.adoptAliases" /></label></div><label>映射现有主题<NSelect v-model:value="selectedEdits.topicIds" multiple filterable :options="topicOptions" :disabled="!readable || locked || registered" /></label>
          <label>待导入正文（原始source单独保持）<NInput v-model:value="selectedEdits.body" type="textarea" :maxlength="100000" :autosize="{ minRows: 8, maxRows: 20 }" :disabled="!readable || locked" /></label><p class="field-help">修改正文或处理选择会使整包预览失效；提交前须重新核对完整方案。原始source和指纹不受正文编辑影响。</p>
          <section class="package-links"><h3>整包引用映射</h3><p class="field-help">候选只按原文件路径／文件名计算，不按标题或别名猜测。包内待导入项可相互引用；选择目标后由服务器统一分配知识身份。每个地址须主动选择目标，或明确保留原文。</p><p v-if="selectedLinks?.problem || selectedLinks?.plan?.problem" class="notice error">{{ selectedLinks.problem || selectedLinks.plan?.problem }}</p><article v-for="link in selectedLinks?.targets" :key="link.key"><strong>{{ link.target }}</strong><p class="field-help">{{ link.reason }}</p><NSelect :value="selectedEdits.links[link.key]?.disposition === 'MAP' ? selectedEdits.links[link.key]?.target : null" clearable filterable :options="targetOptions" placeholder="主动选择完整包内路径" :disabled="!readable || locked" @update:value="value => mapLink(link.key, value)" /><NCheckbox :checked="selectedEdits.links[link.key]?.disposition === 'PRESERVE'" :disabled="!readable || locked" @update:checked="value => preserveLink(link.key, value)">明确保留此地址原文（不创建包内关系）</NCheckbox><p v-if="link.candidates.length > 1" class="notice">存在{{ link.candidates.length }}个候选，不能自动选定。</p><p v-if="link.candidates.length === 1" class="field-help">路径候选：{{ link.candidates[0] }}</p></article><p v-if="selectedLinks && !selectedLinks.targets.length && !selectedLinks.problem" class="field-help">没有可映射的Wiki／相对文本引用。其他地址由服务器完整预览核对。</p><h3 v-if="selectedAttachmentLinks?.links.length">本份附件地址</h3><p v-if="selectedAttachmentLinks?.problem" class="notice error">{{ selectedAttachmentLinks.problem }}</p><article v-for="link in selectedAttachmentLinks?.links" :key="link.key"><strong>{{ link.target }}</strong><p class="field-help">请先在下方登记准确包内附件路径，再明确选择。没有附件时可保留原文，此地址不会生成可访问的私有附件关系。</p><NSelect :value="selectedEdits.attachmentLinks[link.key]?.disposition === 'MAP' ? selectedEdits.attachmentLinks[link.key]?.target : null" :options="attachmentOptions" clearable filterable placeholder="选择已核对的包内附件路径" :disabled="!readable || locked" @update:value="value => mapAttachmentLink(link.key, value)" /><NCheckbox :checked="selectedEdits.attachmentLinks[link.key]?.disposition === 'PRESERVE'" :disabled="!readable || locked" @update:checked="value => preserveLink(link.key, value, true)">明确保留附件地址原文</NCheckbox></article></section>
          <details><summary>本份最终处理结果</summary><p v-if="finalSource?.problem" class="notice error">{{ finalSource.problem }}</p><template v-else-if="finalSource?.item"><p class="field-help">{{ finalSource.item.title }} · {{ finalSource.item.kind }} · {{ finalSource.item.decision }}<br>标签：{{ finalSource.item.tags.join(' / ') || '无' }}<br>别名：{{ finalSource.item.aliases.join(' / ') || '无' }}</p><pre>{{ finalSource.item.body }}</pre></template></details>
        </div>
      </div>
      <section class="package-attachments"><h2>已上传附件的包内映射</h2><p class="field-help">只引用当前用户已READY的准确附件。不会在此上传二进制、猜测同名文件或自动授予模型权限。正文引用须在完整预览中通过服务端解析核对。</p><div v-if="!registered" class="package-two-columns"><label>包内附件完整路径<NInput v-model:value="attachmentPath" :maxlength="600" :disabled="!readable || locked" placeholder="assets/架构图.png" /></label><label>私有附件UUID<NInput v-model:value="attachmentId" :maxlength="36" :disabled="!readable || locked" /></label></div><div v-if="!registered" class="row-actions"><NButton :disabled="!readable || locked" @click="checkAttachment">核对附件身份和指纹</NButton><NButton :disabled="!readable || locked || !checkedAttachment" @click="addAttachment">加入这份附件映射</NButton></div><p v-if="checkedAttachment" class="field-help">{{ checkedAttachment.fileName }} · {{ checkedAttachment.status }} · {{ checkedAttachment.sha256 }}</p><article v-for="item in attachments" :key="item.sourceId"><strong>{{ item.sourceId }}</strong><span>{{ item.attachmentId }}</span><NButton v-if="!registered" text size="small" :disabled="!readable || locked" @click="attachments = attachments.filter(value => value.sourceId !== item.sourceId)">移除映射</NButton></article></section>
      <div v-if="!registered && frozen?.preview.existingPackageId" class="row-actions"><NButton :disabled="!readable || locked" @click="loadPackage(frozen.preview.existingPackageId)">读取已有包及持久映射</NButton><span class="field-help">不同处理选择不会覆盖已有登记；要另建一套知识，先明确选择副本并重新预览。</span></div>
      <section class="package-confirmation"><div class="row-actions"><NCheckbox v-if="!registered" v-model:checked="createCopy" :disabled="!readable || locked">明确创建新副本（默认复用同一包映射）</NCheckbox><NButton type="primary" :loading="previewing" :disabled="!readable || locked || !files.length || !recoverMatched" @click="inspect">预览完整知识包</NButton></div><template v-if="frozen"><p>{{ frozen.preview.importCount }}份导入 · {{ frozen.preview.skipCount }}份明确跳过 · {{ frozen.preview.issues.length }}项阻断问题</p><p v-if="frozen.preview.existingPackageId && !registered" class="notice">已存在相同来源包：{{ frozen.preview.existingPackageId }}。默认读取并复用映射，只有明确勾选副本才另建。</p><ul v-if="frozen.preview.issues.length"><li v-for="(issue, index) in frozen.preview.issues" :key="index">{{ issue.sourceId }} · {{ issue.code }}：{{ issue.message }}</li></ul><details><summary>当前预览方案指纹</summary><p class="fingerprint">{{ frozen.preview.planSha256 }}</p></details><NCheckbox v-model:checked="confirmed" :disabled="!readable || locked || Boolean(frozen.preview.issues.length)">{{ registered ? '我已核对相同原文件与登记方案，确认原子创建全部所选知识及映射' : '我已核对类型、元数据、跳过项、引用及附件，确认登记当前方案' }}</NCheckbox><div class="row-actions"><NButton v-if="!registered" type="primary" :loading="writer.busy.value" :disabled="!readable || locked || !confirmed || Boolean(frozen.preview.issues.length)" @click="register">登记方案（暂不创建知识）</NButton><NButton v-else type="primary" :loading="writer.busy.value" :disabled="!canCommit" @click="commit">确认提交整个知识包</NButton></div></template></section>
    </template>
    </div>
  </section>
</template>

<style scoped>
.package-workspace { display: grid; gap: 18px; max-width: 1280px; margin: 0 auto; }
.package-content { display: grid; gap: 18px; min-width: 0; }
.package-workspace h2 { margin: 0 0 12px; font-size: 17px; overflow-wrap: anywhere; }
.package-workspace h3 { margin: 0 0 10px; font-size: 14px; }
.package-workspace label { display: grid; gap: 7px; font-size: 12px; }
.package-workspace details { font-size: 12px; }
.package-workspace summary { cursor: pointer; padding: 5px 0; color: var(--muted); }
.package-workspace pre { max-height: 360px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; padding: 12px; background: var(--surface-soft); }
.package-resume { display: flex; max-width: 640px; gap: 10px; }
.package-history, .package-mappings { display: grid; gap: 8px; }
.package-history > button, .package-mappings > article { display: grid; gap: 5px; text-align: left; padding: 10px; border: 1px solid var(--line); border-radius: 6px; }
.package-history span, .package-history small, .package-mappings span { font-size: 11px; color: var(--muted); overflow-wrap: anywhere; }
.package-selectors { display: flex; flex-wrap: wrap; gap: 18px; padding: 18px; border: 1px dashed var(--line); border-radius: 8px; }
.package-selectors input { max-width: 240px; font-size: 12px; }
.package-editor { display: grid; grid-template-columns: 240px minmax(0, 1fr); border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.package-editor aside { min-width: 0; border-right: 1px solid var(--line); padding: 14px; background: var(--surface-soft); }
.package-editor aside button { display: grid; gap: 5px; width: 100%; text-align: left; padding: 10px 8px; border-radius: 5px; overflow-wrap: anywhere; font-size: 12px; }
.package-editor aside button.selected { background: var(--surface-deep); }
.package-editor aside small { color: var(--muted); font-size: 11px; }
.package-form { display: grid; gap: 16px; padding: 22px; min-width: 0; }
.package-two-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px; }
.package-options, .package-links { display: grid; gap: 10px; }
.package-links article { padding: 12px; border: 1px solid var(--line); border-radius: 5px; font-size: 12px; overflow-wrap: anywhere; }
.package-links .field-help { margin: 7px 0; }
.package-registered, .package-attachments, .package-confirmation { padding: 20px; border: 1px solid var(--line); border-radius: 8px; display: grid; gap: 13px; background: var(--surface); }
.package-attachments article { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; font-size: 12px; overflow-wrap: anywhere; }
.fingerprint { font-family: monospace; overflow-wrap: anywhere; line-height: 1.7; font-size: 11px; }
@media (max-width: 800px) { .package-editor { grid-template-columns: 1fr; } .package-editor aside { border-right: 0; border-bottom: 1px solid var(--line); max-height: 250px; overflow: auto; } .package-two-columns { grid-template-columns: 1fr; } .package-form { padding: 15px; } .package-resume { flex-wrap: wrap; } }
</style>
