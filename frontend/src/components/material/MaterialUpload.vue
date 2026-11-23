<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { NButton, NInput } from 'naive-ui'
import { errorText, read, upload } from '../../api/client'
import type { Attachment, MaterialKind, MaterialStatus, MaterialUpload } from '../../api/material'
import { bytesLabel, materialFileKind, maxLocalFileBytes, uploadLabels } from '../../api/material'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ attachment?: Attachment; status?: MaterialStatus; disabled?: boolean }>()
const emit = defineEmits<{ changed: []; ready: [value: Attachment] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const current = ref<Attachment | undefined>(props.attachment)
const attempt = ref<MaterialUpload>()
const file = shallowRef<File>()
const sha256 = ref('')
const kind = ref<MaterialKind>()
const description = ref('')
const hashing = ref(false)
const transferring = ref(false)
const recovering = ref(false)
const uncertain = ref(false)
const localError = ref('')
const message = ref('')
let alive = true
let selection = 0
const locked = computed(() => writer.locked.value || hashing.value || transferring.value || recovering.value || uncertain.value)
const dirty = computed(() => current.value?.status !== 'READY' && (Boolean(file.value) || (!current.value && Boolean(description.value))))
const enabled = computed(() => props.status?.enabled && store.sessionActive && !props.disabled)
const matching = computed(() => Boolean(file.value && sha256.value && (!current.value || (file.value.size === current.value.byteSize && sha256.value === current.value.sha256 && kind.value === current.value.kind))))
const canSend = computed(() => enabled.value && matching.value && attempt.value?.status === 'RESERVED' && !locked.value)
const canNewAttempt = computed(() => enabled.value && current.value?.status === 'RESERVED' && !locked.value && current.value.uploads.length < 3 && !current.value.uploads.some(item => ['RESERVED', 'RECEIVING'].includes(item.status)))

function chooseAttempt(value: Attachment) {
  return value.uploads.find(item => ['RESERVED', 'RECEIVING'].includes(item.status)) ?? [...value.uploads].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]
}
watch(() => props.attachment, value => {
  if (value && !transferring.value && !recovering.value && !writer.locked.value) {
    if (value.status === 'READY' && current.value?.status !== 'READY') { finish(value); return }
    current.value = value
    attempt.value = value.uploads.find(item => item.id === attempt.value?.id) ?? chooseAttempt(value)
  }
}, { immediate: true })
watch([dirty, locked], ([changed, busy]) => store.changes.set('material-upload', { dirty: changed, locked: busy }), { immediate: true })
onUnmounted(() => { alive = false; selection += 1; store.changes.delete('material-upload') })

async function chooseFile(event: Event) {
  if (locked.value || !enabled.value) return
  const input = event.target as HTMLInputElement
  const selected = input.files?.[0]
  if (!selected) return
  const selectedKind = materialFileKind(selected.name)
  if (!selectedKind || selected.size < 1 || selected.size > Math.min(maxLocalFileBytes, props.status?.maxFileBytes ?? maxLocalFileBytes) || selected.name.length > 200 || /[\u0000-\u001f\u007f/\\]/.test(selected.name)) {
    localError.value = '请选择 1 字节至 20 MiB 的 PDF、DOCX、PNG 或 JPEG 文件，文件名最多 200 字符。'
    input.value = ''
    return
  }
  const token = ++selection
  file.value = selected
  kind.value = selectedKind
  sha256.value = ''
  hashing.value = true
  localError.value = ''
  try {
    if (!window.crypto?.subtle) throw new Error('当前页面无法计算文件摘要。请在 HTTPS 安全上下文中上传。')
    const digest = await crypto.subtle.digest('SHA-256', await selected.arrayBuffer())
    if (!alive || token !== selection) return
    sha256.value = [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, '0')).join('')
    if (current.value && !matching.value) localError.value = '所选文件与这个上传意图的大小、格式或 SHA-256 不一致。请重新选择原文件。'
  } catch (error) { if (alive && token === selection) localError.value = errorText(error) }
  finally { if (alive && token === selection) hashing.value = false }
}

function finish(value: Attachment) {
  current.value = value
  uncertain.value = false
  transferring.value = false
  recovering.value = false
  file.value = undefined
  sha256.value = ''
  description.value = ''
  message.value = '原件已保存，接下来可以显式提取文字或生成图片预览。'
  store.changes.set('material-upload', { dirty: false, locked: false })
  emit('changed')
  emit('ready', value)
}
function createIntent() {
  if (!enabled.value || locked.value || current.value || !matching.value || !file.value || !kind.value || description.value.length > 5000) return
  const sessionEpoch = store.sessionEpoch
  void writer.run<Attachment>('/materials/attachments', 'POST', { fileName: file.value.name, kind: kind.value, byteSize: file.value.size, sha256: sha256.value, description: description.value }, value => {
    current.value = value
    attempt.value = chooseAttempt(value)
    emit('changed')
    // 意图回执只固定这份 File 和 uploadId；结果未知时不得重新创建意图。
    if (value.status === 'READY') finish(value)
    else if (alive && store.sessionActive && store.sessionEpoch === sessionEpoch && attempt.value?.status === 'RESERVED') void send()
  })
}
async function send() {
  if (!canSend.value || !file.value || !attempt.value) return
  const sessionEpoch = store.sessionEpoch
  const uploadId = attempt.value.id
  const original = file.value
  transferring.value = true
  uncertain.value = true
  localError.value = ''
  let needsRecovery = false
  try {
    const value = await upload<Attachment>(`/materials/uploads/${uploadId}/content`, original, sha256.value)
    if (!alive || !store.sessionActive || store.sessionEpoch !== sessionEpoch) return
    finish(value)
  } catch (error) {
    needsRecovery = true
    if (alive) localError.value = `${errorText(error)} 请先查询本次上传状态，再决定是否重传同一尝试。`
  } finally {
    if (alive) {
      transferring.value = false
      if (store.sessionEpoch !== sessionEpoch) message.value = '会话已变化；原文件和上传尝试仍保留，请重新查询本次状态。'
    }
  }
  if (needsRecovery && alive && store.sessionActive && store.sessionEpoch === sessionEpoch) await recover()
}
async function recover() {
  if (!attempt.value || !current.value || transferring.value || recovering.value || writer.locked.value || !store.sessionActive) return
  const sessionEpoch = store.sessionEpoch
  const uploadId = attempt.value.id
  const attachmentId = current.value.id
  recovering.value = true
  localError.value = ''
  try {
    const value = await read<MaterialUpload>(`/materials/uploads/${uploadId}`)
    if (!alive || !store.sessionActive || store.sessionEpoch !== sessionEpoch) return
    attempt.value = value
    uncertain.value = value.status === 'RECEIVING' || value.status === 'SUCCEEDED'
    const attachment = await read<Attachment>(`/materials/attachments/${attachmentId}`)
    if (!alive || !store.sessionActive || store.sessionEpoch !== sessionEpoch) return
    current.value = attachment
    if (attachment.status === 'READY') finish(attachment)
    else {
      uncertain.value = value.status === 'RECEIVING' || value.status === 'SUCCEEDED'
      message.value = value.status === 'RESERVED' ? '服务器尚未接收这次内容，可以使用同一尝试重新传输。' : value.status === 'RECEIVING' ? '服务器仍在接收，请稍后再次查询；不会开启第二个写者。' : value.status === 'SUCCEEDED' ? '尝试已成功，但附件就绪状态尚未确认。请再次查询，不创建新尝试。' : '本次尝试已终止。可显式创建新尝试，保留文件仍计入容量。'
      emit('changed')
    }
  } catch (error) { if (alive && store.sessionEpoch === sessionEpoch) { uncertain.value = true; localError.value = errorText(error) } }
  finally { if (alive) recovering.value = false }
}
function newAttempt() {
  if (!canNewAttempt.value || !current.value) return
  const attachmentId = current.value.id
  void writer.run<MaterialUpload>(`/materials/attachments/${attachmentId}/uploads`, 'POST', { expectedRevision: current.value.revision }, value => {
    attempt.value = value
    if (current.value) current.value = { ...current.value, uploads: [...current.value.uploads, value] }
    message.value = '新尝试已创建。确认原文件后，点击传输；旧尝试和容量保留。'
    emit('changed')
  })
}
function selectExisting(value: MaterialUpload) {
  if (locked.value || !enabled.value || !current.value?.uploads.some(item => item.id === value.id) || !['RESERVED', 'RECEIVING'].includes(value.status)) return
  attempt.value = value
  uncertain.value = value.status === 'RECEIVING'
  message.value = '已选择服务器上已有的上传尝试，原文件和附件摘要保持原样。'
}
function startAnother() {
  if (locked.value || props.attachment || !window.confirm('开始另一份附件并清空页面里的文件选择？现有上传意图和尝试仍保留，可从目录恢复。')) return
  current.value = undefined; attempt.value = undefined; file.value = undefined; sha256.value = ''; kind.value = undefined; description.value = ''; localError.value = ''; message.value = ''
}
</script>

<template>
  <section class="material-upload" aria-label="附件上传">
    <header><div><p class="section-label">{{ current ? '恢复同一附件' : '保留真实出处' }}</p><h2>{{ current ? current.fileName : '上传附件资料' }}</h2></div><span v-if="current" class="status-pill">{{ current.status === 'READY' ? '原件已保存' : '上传尚未完成' }}</span></header>
    <p class="field-help">PDF / DOCX / PNG / JPEG，每份最多 20 MiB。原件保存后不可替换；上传不会核实项目事实，也不会外发模型。</p>
    <template v-if="current?.status !== 'READY'">
      <label class="file-field">{{ current ? '选择与原意图一致的文件' : '选择文件' }}<input type="file" accept=".pdf,.docx,.png,.jpg,.jpeg" :disabled="locked || !enabled" @change="chooseFile" /></label>
      <p v-if="file" class="file-summary">{{ file.name }} · {{ bytesLabel(file.size) }}<span v-if="hashing">正在计算 SHA-256…</span><code v-else-if="sha256">SHA-256 {{ sha256 }}</code></p>
      <label v-if="!current">资料说明（可选）<NInput v-model:value="description" type="textarea" :maxlength="5000" :autosize="{ minRows: 2, maxRows: 6 }" :disabled="locked || !enabled" /></label>
      <template v-if="current"><p class="field-help">附件 {{ current.id }} · 已声明 {{ bytesLabel(current.byteSize) }} · {{ current.sha256 }}</p><p v-if="attempt" class="attempt-status"><strong>{{ uploadLabels[attempt.status] }}</strong><span>尝试 {{ attempt.id }}</span><span>已接收 {{ bytesLabel(attempt.receivedBytes) }} / 预留 {{ bytesLabel(attempt.reservedBytes) }}</span><span v-if="attempt.errorCode">{{ attempt.errorCode }}</span></p></template>
      <p v-if="localError" class="notice error" role="alert">{{ localError }}</p><div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" size="small" :disabled="!store.sessionActive" @click="writer.retry">用原请求重试</NButton></div>
      <p v-if="message" class="field-help" role="status">{{ message }}</p><p v-if="uncertain" class="notice" role="status">本次上传结果尚未确认，原文件保留在页面内存中。先查询本次状态；不要刷新页面。</p>
      <div class="row-actions"><NButton v-if="!current" type="primary" :loading="writer.busy.value || hashing" :disabled="!enabled || locked || !matching" @click="createIntent">创建意图并上传</NButton><NButton v-else-if="attempt?.status === 'RESERVED' && !uncertain" type="primary" :disabled="!canSend" :loading="transferring" @click="send">传输到本次尝试</NButton><NButton v-if="attempt" :loading="recovering" :disabled="transferring || recovering || writer.locked.value || !store.sessionActive" @click="recover">查询本次上传状态</NButton><NButton v-if="current && !current.uploads.some(item => ['RESERVED', 'RECEIVING'].includes(item.status))" :disabled="!canNewAttempt" @click="newAttempt">创建新上传尝试</NButton></div>
      <div v-if="current" class="row-actions"><NButton v-for="candidate in current.uploads.filter(item => item.id !== attempt?.id && ['RESERVED', 'RECEIVING'].includes(item.status))" :key="candidate.id" size="small" :disabled="locked || !enabled" @click="selectExisting(candidate)">继续已有尝试 {{ candidate.id.slice(0, 8) }}</NButton></div><p v-if="current" class="field-help">上传尝试 {{ current.uploads.length }} / 3。失败和中断的尝试仍保留并占用预留容量。</p><NButton v-if="current && !attachment" text size="small" :disabled="locked" @click="startAnother">开始另一份附件</NButton>
    </template>
    <p v-else class="success-text" role="status">{{ message || '原件已保存，可以在资料详情继续处理。' }}</p>
  </section>
</template>

<style scoped>
.material-upload { display: grid; gap: 20px; padding: 28px; min-width: 0; }
.material-upload > header { display: flex; justify-content: space-between; align-items: start; gap: 12px; }
.material-upload h2 { font-size: 18px; overflow-wrap: anywhere; }.material-upload .section-label { margin-bottom: 6px; color: var(--muted); font-size: 12px; font-weight: 400; letter-spacing: 0; }
.material-upload > label { display: grid; gap: 8px; color: var(--muted); font-size: 12px; }
.file-field { align-content: center; min-height: 120px; padding: 24px; border: 1px dashed var(--line); border-radius: 8px; background: var(--bg); }
.file-field input { width: 100%; min-width: 0; color: var(--muted); font-size: 13px; }.file-field input::file-selector-button { margin-right: 12px; padding: 8px 14px; border: 1px solid var(--line); border-radius: 6px; color: var(--ink); background: var(--surface); font: inherit; cursor: pointer; }.file-field input:disabled::file-selector-button { cursor: not-allowed; opacity: .5; }
.file-summary, .attempt-status { display: grid; gap: 5px; font-size: 12px; overflow-wrap: anywhere; }
.file-summary code { color: var(--muted); font-size: 11px; }
.attempt-status { padding: 13px; border-left: 2px solid var(--brand); background: var(--surface-soft); }
</style>
