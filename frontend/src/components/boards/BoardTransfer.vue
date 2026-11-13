<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { NButton, NSelect } from 'naive-ui'
import { boardPayload, boardReferenceKey, boardReferenceLabels, type BoardDetail } from '../../api/boards'
import { boardTransferMaxBytes, exportBoardTransfer, materializeBoardTransfer, previewBoardTransfer,
  type BoardTransferDraft, type BoardTransferExport, type BoardTransferFormat, type BoardTransferPreview } from '../../api/boardTransfer'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

type FileContext = { session: number; identity: string; boardId: string; revision: number }
const props = defineProps<{ board: BoardDetail; disabled: boolean }>()
const emit = defineEmits<{ apply: [value: BoardTransferDraft]; pending: [value: boolean] }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const preview = ref<BoardTransferPreview>()
const incoming = ref<{ value: BoardTransferPreview; context: FileContext }>()
const decisions = ref<Record<string, string>>({})
const reading = ref(false), error = ref(''), notice = ref(''), acceptOmissions = ref(false), replaceConfirmed = ref(false)
const exportFormat = ref<BoardTransferFormat>('canvas'), exportPreview = ref<BoardTransferExport>()
const previewRevision = ref(props.board.revision), filePicking = ref(false)
const accessIdentity = computed(() => JSON.stringify([access.active?.id ?? null, access.active?.mode ?? null,
  access.active?.materialAccess ?? null]))
const readable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const boardChanged = computed(() => Boolean(preview.value) && previewRevision.value !== props.board.revision)
let alive = true, generation = 0
let pickerContext: (FileContext & { generation: number }) | undefined
const prefix = `board-transfer-${crypto.randomUUID()}`
const pending = computed(() => reading.value || Boolean(preview.value) || Boolean(incoming.value))
const mappingOptions = computed(() => [
  { label: '保留为临时文本（仅文件路径）', value: 'TEXT' },
  { label: '跳过此节点及相连线', value: 'SKIP' },
  ...props.board.references.map(reference => ({ label: `${boardReferenceLabels[reference.referenceKind ?? 'KNOWLEDGE']} · ${reference.title}`,
    value: boardReferenceKey(reference.itemId, reference.referenceKind) })),
])
const materialized = computed(() => {
  if (!preview.value) return { value: undefined, error: '' }
  try { return { value: materializeBoardTransfer(preview.value, decisions.value, props.board.references), error: '' } }
  catch (failure) { return { value: undefined, error: failure instanceof Error ? failure.message : '无法采用导入文件。' } }
})
watch(pending, value => emit('pending', value), { immediate: true, flush: 'sync' })
watch([() => props.board.id, () => store.sessionEpoch, () => store.sessionActive, accessIdentity], () => reset(), { flush: 'sync' })
watch([() => props.disabled, readable], () => { acceptOmissions.value = false; replaceConfirmed.value = false }, { flush: 'sync' })
watch(() => JSON.stringify([boardPayload(props.board), props.board.references]), () => {
  exportPreview.value = undefined; notice.value = ''; acceptOmissions.value = false; replaceConfirmed.value = false
}, { flush: 'sync' })
watch(exportFormat, () => { exportPreview.value = undefined; notice.value = '' })
onUnmounted(() => { alive = false; generation++; emit('pending', false) })
function reset() {
  generation++; preview.value = undefined; incoming.value = undefined; decisions.value = {}; reading.value = false
  cancelFilePick(); previewRevision.value = props.board.revision
  acceptOmissions.value = false; replaceConfirmed.value = false; exportPreview.value = undefined; error.value = ''; notice.value = ''
}
function contextCurrent(context: FileContext) {
  return alive && store.sessionActive && context.session === store.sessionEpoch && context.identity === accessIdentity.value
    && context.boardId === props.board.id
}
function beginFilePick(event: Event) {
  if (props.disabled || !readable.value || reading.value) { event.preventDefault(); return }
  pickerContext = { session: store.sessionEpoch, identity: accessIdentity.value, boardId: props.board.id,
    revision: props.board.revision, generation }
  filePicking.value = true
}
function cancelFilePick() { filePicking.value = false; pickerContext = undefined }
async function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  const pickedContext = pickerContext
  cancelFilePick()
  input.value = ''
  const context = pickedContext && pickedContext.generation === generation && contextCurrent(pickedContext) ? pickedContext : undefined
  if (!file || reading.value) return
  if (!context) { if (readable.value) error.value = '无法核对此次文件选择的当前会话、训练身份与画布，请从当前画布点击文件选择器重新选择。'; return }
  if (file.size < 1 || file.size > boardTransferMaxBytes) { error.value = '请选择不超过 1 MiB 的非空 UTF-8 文件。'; return }
  const extension = file.name.split('.').pop()?.toLowerCase()
  const format = ['canvas', 'json'].includes(extension ?? '') ? 'canvas' : ['md', 'markdown', 'txt'].includes(extension ?? '') ? 'markdown' : undefined
  if (!format) { error.value = '支持 .canvas／.json 的 JSON Canvas，以及 .md／.markdown／.txt 的 Markdown 脑图。'; return }
  const request = ++generation
  reading.value = true; error.value = ''; notice.value = ''
  // 焦点核验只遮挡内容；本地文件绑定实际身份和画布，不因 checking 或同画布改版丢弃已读字节。
  const current = () => request === generation && contextCurrent(context)
  try {
    const buffer = await file.arrayBuffer()
    if (!current()) return
    if (buffer.byteLength !== file.size) throw new Error('文件实际大小发生变化，请重新选择。')
    const value = previewBoardTransfer(new TextDecoder('utf-8', { fatal: true }).decode(buffer), format, file.name)
    if (current()) installFile(value, context)
  } catch (failure) {
    if (current()) error.value = failure instanceof TypeError ? '无法按 UTF-8 完整读取文件，请检查编码。' : failure instanceof Error ? failure.message : '文件读取失败。'
  } finally { if (alive && request === generation) reading.value = false }
}
function installFile(value: BoardTransferPreview, context: FileContext) {
  if (!contextCurrent(context)) return
  if (preview.value) {
    if (props.disabled || !readable.value) { incoming.value = { value, context }; return }
    if (!window.confirm('替换当前导入预览？尚未采用的选择会丢弃。')) return
  }
  if (!contextCurrent(context)) return
  preview.value = value; incoming.value = undefined; decisions.value = {}; previewRevision.value = context.revision
  acceptOmissions.value = false; replaceConfirmed.value = false; exportPreview.value = undefined
}
function useIncoming() {
  if (props.disabled || !readable.value || reading.value || !incoming.value) return
  installFile(incoming.value.value, incoming.value.context)
}
function reviewBoardChange() {
  if (props.disabled || !readable.value || reading.value || !boardChanged.value
    || !window.confirm(`画布已从修订 ${previewRevision.value} 变为 ${props.board.revision}。保留本地文件与逐项选择，按当前画布重新核对映射和替换范围？`)) return
  previewRevision.value = props.board.revision; acceptOmissions.value = false; replaceConfirmed.value = false
}
function apply() {
  if (props.disabled || !readable.value || reading.value || incoming.value || boardChanged.value
    || !preview.value || !materialized.value.value || !acceptOmissions.value || !replaceConfirmed.value) return
  // 只把核对过的内容交给父画布草稿；服务器修订和幂等键仍由原保存命令负责。
  emit('apply', materialized.value.value)
  reset(); notice.value = '已填入当前画布草稿，请检查节点和连线后主动保存；服务器尚未写入。'
}
function prepareExport() {
  if (props.disabled || !readable.value) return
  error.value = ''; notice.value = ''
  try { exportPreview.value = exportBoardTransfer(props.board, exportFormat.value) }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法准备导出。' }
}
function download() {
  const value = exportPreview.value
  if (!value || props.disabled || !readable.value) return
  const url = URL.createObjectURL(new Blob([value.text], { type: value.mediaType }))
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = `board-${props.board.id}-r${props.board.revision}.${value.extension}`; anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  notice.value = '已请求浏览器下载；请在浏览器中确认文件已保存。'
}
</script>

<template>
  <details v-show="readable" class="board-transfer" :inert="!readable || undefined">
    <summary>JSON Canvas 与 Markdown 脑图交换</summary>
    <p class="field-help">单文件最多 1 MiB、100 个节点、200 条连线。先本地预览并确认，再替换当前画布草稿；不会自动保存或访问文件内路径。</p>
    <label :for="`${prefix}-file`">选择导入文件</label>
    <input :id="`${prefix}-file`" type="file" accept=".canvas,.json,.md,.markdown,.txt" :disabled="reading || (disabled || !readable) && !filePicking" @click="beginFilePick" @cancel="cancelFilePick" @change="chooseFile" />
    <p v-if="reading" role="status">正在本地读取文件…</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <div v-if="incoming" class="notice" role="status"><p>新文件 {{ incoming.value.fileName }} 已在本地读取。原导入预览和逐项选择仍保留，请明确决定是否替换。</p><div class="row-actions"><NButton :disabled="disabled || reading" @click="useIncoming">核对并使用新文件</NButton><NButton :disabled="disabled || reading" @click="incoming = undefined">放弃新文件，保留原选择</NButton></div></div>
    <section v-if="preview" class="transfer-preview" aria-label="导入预览">
      <h3>{{ preview.fileName }}</h3>
      <p v-if="boardChanged" class="notice" role="status">当前画布已从修订 {{ previewRevision }} 变为 {{ board.revision }}。导入文件与逐项选择仍保留；请先明确按新修订重新核对，再确认替换。<NButton :disabled="disabled || reading" @click="reviewBoardChange">按当前修订重新核对</NButton></p>
      <p>{{ preview.nodes.length }} 个候选节点，{{ preview.edges.length }} 条候选连线；保留 {{ materialized.value?.nodes.length ?? '待选择' }} 个节点。</p>
      <ul><li v-for="warning in materialized.value?.warnings ?? preview.warnings" :key="warning">{{ warning }}</li></ul>
      <p v-if="preview.nodes.some(value => value.file)" class="field-help">文件节点必须逐个选择。要映射其他站内对象，可先用上方引用工具把它加入当前画布；这里不会按标题或路径猜测身份。</p>
      <div class="transfer-node-list">
        <article v-for="value in preview.nodes" :key="value.node.id">
          <strong>{{ value.file ? '文件引用' : '临时文本' }} · {{ value.node.x }}, {{ value.node.y }}{{ value.node.parentId ? ' · 有父节点' : '' }}</strong>
          <pre>{{ value.file ?? value.node.text }}</pre>
          <NSelect v-if="value.file" v-model:value="decisions[value.node.id]" :options="mappingOptions" :disabled="disabled" aria-label="文件节点处理方式" placeholder="明确选择映射、保留为文字或跳过" @update:value="acceptOmissions = false; replaceConfirmed = false" />
        </article>
      </div>
      <p v-if="materialized.error" class="field-help" role="status">{{ materialized.error }}</p>
      <label class="confirm"><input v-model="acceptOmissions" type="checkbox" :disabled="disabled" />我已核对全部节点、映射和上述遗漏，接受该范围。</label>
      <label class="confirm"><input v-model="replaceConfirmed" type="checkbox" :disabled="disabled" />用这些节点和连线替换当前画布草稿；原节点、连线可在保存前撤销，已保存历史保留。</label>
      <div class="row-actions"><NButton type="primary" :disabled="disabled || reading || !!incoming || boardChanged || !materialized.value || !acceptOmissions || !replaceConfirmed" @click="apply">确认填入草稿</NButton><NButton :disabled="disabled || reading" @click="reset">放弃此导入预览</NButton></div>
    </section>
    <section class="transfer-export" aria-label="兼容格式导出">
      <h3>导出当前画布</h3>
      <p class="field-help">导出当前本地内容，可能含未保存修改。问阶修订快照仍可用上方“导出问阶快照”；兼容文件不用于恢复权限或历史。</p>
      <div class="row-actions"><NSelect v-model:value="exportFormat" :disabled="disabled" :options="[{ label: 'JSON Canvas（.canvas）', value: 'canvas' }, { label: 'Markdown 层级脑图（.md）', value: 'markdown' }]" aria-label="兼容导出格式" /><NButton :disabled="disabled" @click="prepareExport">预览导出文件</NButton></div>
      <template v-if="exportPreview">
        <ul><li v-for="warning in exportPreview.warnings" :key="warning">{{ warning }}</li></ul>
        <pre class="export-source" tabindex="0" aria-label="导出文件全文">{{ exportPreview.text }}</pre>
        <NButton :disabled="disabled" @click="download">下载已预览文件</NButton>
      </template>
    </section>
  </details>
</template>

<style scoped>
.board-transfer { margin: 0 14px 16px; padding: 14px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); font-size: 12px; }
summary { cursor: pointer; font-weight: 600; }.board-transfer p { margin: 10px 0; }.board-transfer > label { display: block; margin: 10px 0 6px; }
.transfer-preview, .transfer-export { display: grid; gap: 11px; margin-top: 18px; }.transfer-node-list { max-height: 420px; overflow: auto; display: grid; gap: 8px; }
.transfer-node-list article { display: grid; gap: 8px; padding: 12px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface); }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; }.export-source { max-height: 400px; overflow: auto; padding: 12px; border: 1px solid var(--line); background: var(--surface); }
ul { padding-left: 20px; line-height: 1.8; }.confirm { display: flex; gap: 8px; align-items: flex-start; line-height: 1.7; }.confirm input { margin-top: 4px; }.transfer-export .n-select { min-width: 240px; flex: 1; }
</style>
