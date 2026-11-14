<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { Attachment, MaterialExcerpt, MaterialExcerptPage, MaterialRun, MaterialRunPage, MaterialSegment, MaterialStatus } from '../../api/material'
import { attachmentLabels, bytesLabel, materialContentUrl, materialKindLabels, materialPreviewUrl, parseLabels, uploadLabels } from '../../api/material'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useRecentVisit } from '../../composables/useRecentVisit'
import { useWorkspaceStore } from '../../state/workspace'
import MaterialKnowledgeImpacts from '../MaterialKnowledgeImpacts.vue'
import MaterialCorrection from './MaterialCorrection.vue'
import MaterialDependencyImpacts from './MaterialDependencyImpacts.vue'
import MaterialExcerptReadout from './MaterialExcerptReadout.vue'
import MaterialKnowledgePreview from './MaterialKnowledgePreview.vue'
import MaterialLifecycle from './MaterialLifecycle.vue'
import MaterialSegments from './MaterialSegments.vue'
import MaterialUpload from './MaterialUpload.vue'

const props = defineProps<{ id: string; status?: MaterialStatus }>()
const emit = defineEmits<{ changed: []; close: [] }>()
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const writer = useCommand()
const current = ref<Attachment>()
const description = ref('')
const savedMessage = ref('')
const localError = ref('')
const serverCopy = ref<Attachment>()
const runBefore = ref<string>()
const runCursors = ref<(string | undefined)[]>([])
const createdRunId = ref<string>()
const previewOpen = ref(false)
const originalPreviewOpen = ref(true)
const excerptCursor = ref<string>()
const excerptCursors = ref<(string | undefined)[]>([])
const excerptStatus = ref<'ACTIVE' | 'ARCHIVED' | 'ALL'>('ACTIVE')
const excerptStatusOptions = [{ value: 'ACTIVE', label: '有效摘录' }, { value: 'ARCHIVED', label: '归档摘录' }, { value: 'ALL', label: '全部摘录（含更正历史）' }]
const shownExcerptId = ref<string>()
const readoutElement = ref<HTMLElement>()
const excerptMode = ref<'PARSED' | 'MANUAL'>('MANUAL')
const manualLocator = ref('')
const manualText = ref('')
const selectedSegment = shallowRef<MaterialSegment>()
const startOffset = ref(0)
const endOffset = ref(0)
let alive = true
const uploadLocked = computed(() => Boolean(store.changes.get('material-upload')?.locked))
const lifecycleChange = computed(() => store.changes.get('material-lifecycle'))
const lifecycleLocked = computed(() => Boolean(lifecycleChange.value?.locked))
const correctionChange = computed(() => store.changes.get('material-correction'))
const correctionLocked = computed(() => Boolean(correctionChange.value?.locked))
const dependencyChanges = computed(() => [...store.changes].filter(([key]) => key.startsWith('material-dependency-impact:'))
  .map(([, change]) => change))
const dependencyDirty = computed(() => dependencyChanges.value.some(change => change.dirty))
const dependencyLocked = computed(() => dependencyChanges.value.some(change => change.locked))
const descriptionDirty = computed(() => description.value !== (current.value?.description ?? ''))
const excerptDirty = computed(() => Boolean(manualLocator.value || manualText.value || selectedSegment.value))
const dirty = computed(() => descriptionDirty.value || excerptDirty.value)
const editable = computed(() => store.sessionActive && props.status?.enabled && !writer.locked.value && !uploadLocked.value
  && !lifecycleLocked.value && !correctionLocked.value && !dependencyLocked.value)
const attachment = useQuery({ queryKey: computed(() => ['material', store.sessionEpoch, 'attachment', props.id]), enabled: computed(() => store.sessionActive), queryFn: () => read<Attachment>(`/materials/attachments/${props.id}`) })
const knowledgePreviewEnabled = computed(() => Boolean(props.status?.enabled && current.value?.id === props.id
  && current.value.status === 'READY' && attachment.data.value?.id === props.id && attachment.data.value.status === 'READY' && !attachment.error.value))
const recordRecentVisit = useRecentVisit('MATERIAL', () => props.id, () => Boolean(props.status?.enabled))
const runId = computed(() => typeof route.query.run === 'string' ? route.query.run : createdRunId.value ?? attachment.data.value?.latestRun?.id ?? current.value?.latestRun?.id)
const runs = useQuery({ queryKey: computed(() => ['material', store.sessionEpoch, 'runs', props.id, runBefore.value]), enabled: computed(() => store.sessionActive && current.value?.status === 'READY'), queryFn: () => read<MaterialRunPage>(`/materials/attachments/${props.id}/runs?limit=20${runBefore.value ? `&before=${encodeURIComponent(runBefore.value)}` : ''}`) })
const run = useQuery({ queryKey: computed(() => ['material', store.sessionEpoch, 'run', runId.value]), enabled: computed(() => store.sessionActive && Boolean(runId.value)), queryFn: () => read<MaterialRun>(`/materials/runs/${runId.value}`), refetchInterval: query => store.sessionActive && ['QUEUED', 'RUNNING'].includes(query.state.data?.status ?? '') ? 3000 : false })
const excerpts = useQuery({ queryKey: computed(() => ['material', store.sessionEpoch, 'excerpts', props.id, excerptStatus.value, excerptCursor.value]), enabled: computed(() => store.sessionActive && current.value?.status === 'READY'), queryFn: () => read<MaterialExcerptPage>(`/materials/attachments/${props.id}/excerpts?limit=20&status=${excerptStatus.value}&aiUse=ALL${excerptCursor.value ? `&cursor=${encodeURIComponent(excerptCursor.value)}` : ''}`) })
const excerptText = computed(() => excerptMode.value === 'MANUAL' ? manualText.value : selectedSegment.value?.text.slice(startOffset.value, endOffset.value) ?? '')
const excerptProblem = computed(() => {
  if (!excerptText.value.trim() || excerptText.value.length > 10000) return '摘录原文应为 1–10000 字符。'
  if (excerptMode.value === 'MANUAL' && (!manualLocator.value.trim() || manualLocator.value.length > 500)) return '手工摘录需要 1–500 字符的原文定位说明。'
  if (excerptMode.value === 'PARSED' && (!selectedSegment.value || startOffset.value < 0 || endOffset.value <= startOffset.value || endOffset.value > selectedSegment.value.text.length)) return '请从成功解析的片段中选择有效范围。'
  return ''
})
function accept(value: Attachment) { current.value = value; description.value = value.description }
watch([attachment.data, () => props.status?.enabled], ([value, enabled]) => {
  if (value && value.id === props.id && (!current.value || (!descriptionDirty.value && !writer.locked.value && !uploadLocked.value && !correctionLocked.value))) {
    accept(value)
    if (enabled) recordRecentVisit(value.id)
  }
}, { immediate: true })
watch(() => route.query.excerpt, value => { shownExcerptId.value = typeof value === 'string' ? value : undefined }, { immediate: true })
watch(runId, () => { previewOpen.value = false })
watch(run.data, (value, previous) => { if (value && value.status !== previous?.status && !['QUEUED', 'RUNNING'].includes(value.status)) refresh() })
watch([dirty, writer.locked, () => lifecycleChange.value?.dirty, lifecycleLocked, () => correctionChange.value?.dirty, correctionLocked,
  dependencyDirty, dependencyLocked],
  ([changed, locked, lifecycleDirty, waiting, correctionDirty, correcting, dependencyUnsaved, dependencyWaiting]) => {
  store.changes.set('material-editor', { dirty: changed || Boolean(lifecycleDirty || correctionDirty || dependencyUnsaved), locked: locked || waiting || correcting || dependencyWaiting })
  store.changes.set('material-editor-command', { dirty: false, locked })
}, { immediate: true, flush: 'sync' })
onBeforeRouteUpdate((to, from) => {
  // 同附件换摘录会更换命令根，生命周期与更正的输入和原请求都须先处理。
  if (to.query.attachment === from.query.attachment && to.query.excerpt !== from.query.excerpt) {
    return store.canLeave(['material-lifecycle', 'material-correction',
      ...[...store.changes.keys()].filter(key => key.startsWith('material-dependency-impact:'))])
  }
})
onUnmounted(() => { alive = false; store.changes.delete('material-editor'); store.changes.delete('material-editor-command') })
function refresh() { void queryClient.invalidateQueries({ queryKey: ['material'] }); emit('changed') }
function uploadReady(value: Attachment) { if (!descriptionDirty.value) accept(value); refresh() }
function saveDescription() {
  if (!current.value || !editable.value || !descriptionDirty.value || description.value.length > 5000) return
  void writer.run<Attachment>(`/materials/attachments/${props.id}/description`, 'PUT', { expectedRevision: current.value.revision, description: description.value }, value => { accept(value); serverCopy.value = undefined; savedMessage.value = '资料说明已保存，原文件和解析版本保持原样。'; refresh() })
}
async function compareServer() {
  if (!store.sessionActive) return
  const sessionEpoch = store.sessionEpoch
  localError.value = ''
  try { const value = await read<Attachment>(`/materials/attachments/${props.id}`); if (alive && store.sessionActive && store.sessionEpoch === sessionEpoch) serverCopy.value = value }
  catch (error) { if (alive && store.sessionEpoch === sessionEpoch) localError.value = errorText(error) }
}
function adoptServer() {
  if (!serverCopy.value || !editable.value || !window.confirm('采用服务器说明并丢弃本地尚未保存的说明？摘录输入继续保留。')) return
  accept(serverCopy.value); serverCopy.value = undefined; writer.error.value = undefined
}
function startRun() {
  if (!editable.value || dirty.value || current.value?.status !== 'READY' || !props.status?.parserEnabled || ['QUEUED', 'RUNNING'].includes(attachment.data.value?.latestRun?.status ?? '')) return
  void writer.run<MaterialRun>(`/materials/attachments/${props.id}/runs`, 'POST', {}, value => {
    createdRunId.value = value.id
    savedMessage.value = '处理请求已保存。每次执行保留独立历史，不覆盖旧片段。'
    refresh()
    store.changes.set('material-editor', { dirty: dirty.value || Boolean(lifecycleChange.value?.dirty || correctionChange.value?.dirty) || dependencyDirty.value,
      locked: lifecycleLocked.value || correctionLocked.value || dependencyLocked.value })
    void router.replace({ name: 'materials', query: { ...route.query, attachment: props.id, run: value.id } })
  })
}
function chooseRun(id: string) { void router.replace({ name: 'materials', query: { ...route.query, attachment: props.id, run: id } }) }
function nextRuns() { if (runs.data.value?.nextCursor) { runCursors.value.push(runBefore.value); runBefore.value = runs.data.value.nextCursor } }
function nextExcerpts() { if (excerpts.data.value?.nextCursor) { excerptCursors.value.push(excerptCursor.value); excerptCursor.value = excerpts.data.value.nextCursor } }
function selectExcerptStatus(value: unknown) {
  if (!store.sessionActive || value !== 'ACTIVE' && value !== 'ARCHIVED' && value !== 'ALL') return
  excerptStatus.value = value; excerptCursor.value = undefined; excerptCursors.value = []
}
function focusExcerptReferences() { readoutElement.value?.scrollIntoView({ block: 'nearest' }); readoutElement.value?.focus() }
function clearExcerpt() { manualText.value = ''; manualLocator.value = ''; selectedSegment.value = undefined; excerptMode.value = 'MANUAL'; startOffset.value = 0; endOffset.value = 0 }
function chooseSegment(segment: MaterialSegment, start: number, end: number) {
  if (!editable.value || segment.attachmentId !== props.id || (excerptDirty.value && !window.confirm('放弃当前尚未保存的摘录输入，改用所选片段？'))) return
  clearExcerpt(); excerptMode.value = 'PARSED'; selectedSegment.value = segment; startOffset.value = start; endOffset.value = end
}
function saveExcerpt() {
  if (!editable.value || current.value?.status !== 'READY' || excerptProblem.value) return
  // 解析摘录只提交片段 ID 和范围；原文必须由服务器从不可变片段截取。
  const body = excerptMode.value === 'PARSED' ? { segmentId: selectedSegment.value!.id, startOffset: startOffset.value, endOffset: endOffset.value, locator: null, text: null } : { segmentId: null, startOffset: null, endOffset: null, locator: manualLocator.value, text: manualText.value }
  void writer.run<MaterialExcerpt>(`/materials/attachments/${props.id}/excerpts`, 'POST', body, value => {
    clearExcerpt()
    savedMessage.value = '来源摘录已保存，可以在项目资料或项目事实中显式引用；事实不会自动核实。'
    store.changes.set('material-editor', { dirty: descriptionDirty.value || Boolean(lifecycleChange.value?.dirty || correctionChange.value?.dirty) || dependencyDirty.value,
      locked: lifecycleLocked.value || correctionLocked.value || dependencyLocked.value })
    refresh()
    void router.replace({ name: 'materials', query: { ...route.query, attachment: props.id, excerpt: value.id } })
  })
}
function showExcerpt(id: string) { void router.replace({ name: 'materials', query: { ...route.query, attachment: props.id, excerpt: id } }) }
</script>

<template>
  <section class="material-detail" aria-label="附件资料详情"><div v-if="!current && attachment.isPending.value" class="loading-block" role="status">正在读取附件…</div><p v-else-if="!current" class="notice error">{{ errorText(attachment.error.value) }}<NButton text @click="attachment.refetch()">重试读取</NButton></p>
    <template v-else>
      <p v-if="attachment.error.value" class="notice error">最新读取未完成，本地输入继续保留：{{ errorText(attachment.error.value) }}<NButton text @click="attachment.refetch()">重试</NButton></p><div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" size="small" :disabled="!store.sessionActive" @click="writer.retry">用原请求重试</NButton><NButton v-if="writer.error.value instanceof ApiError && writer.error.value.status === 409" text :disabled="writer.locked.value" @click="compareServer">查看服务器说明</NButton></div><p v-if="localError" class="notice error">{{ localError }}</p><p v-if="savedMessage" class="success-text" role="status">{{ savedMessage }}</p>
      <div class="material-document-layout">
      <section class="material-document" aria-label="附件原件预览">
        <header><strong>文档预览</strong><NButton size="small" :disabled="current.status !== 'READY'" @click="originalPreviewOpen = !originalPreviewOpen">{{ originalPreviewOpen ? '收起预览' : '打开预览' }}</NButton></header>
        <template v-if="originalPreviewOpen && current.status === 'READY' && store.sessionActive">
          <object v-if="current.kind === 'PDF'" class="material-pdf" :data="materialContentUrl(current.id)" type="application/pdf" :aria-label="`${current.fileName} 原件`"><p>浏览器无法内嵌该 PDF。<a class="text-link" :href="materialContentUrl(current.id)" download>下载原件</a></p></object>
          <img v-else-if="current.kind === 'PNG' || current.kind === 'JPEG'" class="material-original-image" :src="materialContentUrl(current.id)" :alt="current.fileName" />
          <div v-else class="document-unavailable"><h3>{{ current.fileName }}</h3><p>当前格式无法在浏览器内直接预览。</p><a class="text-link" :href="materialContentUrl(current.id)" download>下载原件</a></div>
        </template><p v-else class="document-unavailable">{{ current.status === 'READY' ? '原件预览已收起。' : '原件完成上传后可预览。' }}</p>
      </section>
      <div class="material-processing" aria-label="解析结果与来源摘录">
      <!-- 折叠只改变可见性，说明输入、命令状态和资料权限守卫继续挂载。 -->
      <details class="material-management" :open="descriptionDirty || !!serverCopy || current.status !== 'READY'"><summary>资料信息与说明</summary>
      <header class="detail-header"><div><p class="section-label">{{ materialKindLabels[current.kind] }} · {{ attachmentLabels[current.status] }}</p><h2>{{ current.fileName }}</h2></div><button class="icon-button" aria-label="关闭附件详情" @click="emit('close')">×</button></header><p class="field-help">{{ bytesLabel(current.byteSize) }} · {{ new Date(current.createdAt).toLocaleString('zh-CN') }}<span class="material-digest">SHA-256 {{ current.sha256 }}</span></p>
      <section class="material-section"><h3>资料说明</h3><label class="description-label">仅修改说明，原件和解析历史各自保留<NInput v-model:value="description" type="textarea" :maxlength="5000" :autosize="{ minRows: 2, maxRows: 7 }" :disabled="!editable" /></label><div class="row-actions"><NButton size="small" :disabled="!editable || !descriptionDirty" @click="saveDescription">保存说明</NButton><a v-if="current.status === 'READY' && store.sessionActive" class="text-link" :href="materialContentUrl(current.id)" download>下载鉴权原件</a><span class="field-help">{{ descriptionDirty ? '说明尚未保存' : `服务器修订 ${current.revision}` }}</span></div><p v-if="attachment.data.value && attachment.data.value.revision !== current.revision" class="notice">服务器修订有变化，当前输入仍使用原基线。<NButton text @click="compareServer">查看服务器说明</NButton></p><div v-if="serverCopy" class="comparison-panel"><h4>服务器说明 · 修订 {{ serverCopy.revision }}</h4><p class="preserve">{{ serverCopy.description || '未填写说明' }}</p><div class="row-actions"><NButton size="small" :disabled="!editable" @click="adoptServer">采用服务器说明</NButton><NButton text size="small" @click="serverCopy = undefined">保留本地输入</NButton></div></div></section>
      <MaterialUpload v-if="current.status !== 'READY'" :attachment="attachment.data.value ?? current" :status="status" :disabled="dirty || writer.locked.value" @changed="refresh" @ready="uploadReady" />
      <section class="material-section"><h3>上传尝试与保留容量</h3><p class="field-help">失败、中断和部分接收仍占预留额度；本阶段不自动清除原件或旧结果。</p><article v-for="attempt in (attachment.data.value?.uploads ?? current.uploads)" :key="attempt.id" class="attempt-row"><div><strong>{{ uploadLabels[attempt.status] }}</strong><small>{{ attempt.id }}</small></div><p>预留 {{ bytesLabel(attempt.reservedBytes) }} · 接收 {{ bytesLabel(attempt.receivedBytes) }}</p><p v-if="attempt.errorCode" class="error-text">{{ attempt.errorCode }}</p></article></section>
      </details>
      <template v-if="current.status === 'READY'"><section class="material-section"><div class="material-section-heading"><h3>{{ current.kind === 'PNG' || current.kind === 'JPEG' ? '生成图片预览' : '提取文档文字' }}</h3><NButton size="small" :disabled="!editable || dirty || !status?.parserEnabled || ['QUEUED', 'RUNNING'].includes(attachment.data.value?.latestRun?.status ?? '')" @click="startRun">{{ current.kind === 'PNG' || current.kind === 'JPEG' ? '生成新的 PNG 预览' : '新建文字解析执行' }}</NButton></div><p class="field-help">{{ current.kind === 'PNG' || current.kind === 'JPEG' ? '图片仅生成受控的 PNG 预览，不执行 OCR。' : '提取 PDF 文字层或 DOCX 正文和表格，不自动核实内容。扫描件可能没有文字层。' }}每次执行保留独立结果，最多 10 次。</p><p v-if="status && !status.parserEnabled" class="notice">服务器尚未启用解析，可继续下载原件和保存手工摘录。</p><p v-if="dirty" class="field-help">先保存或明确清空本地输入，再发起新的处理。</p><p v-if="runs.error.value" class="notice error">{{ errorText(runs.error.value) }}<NButton text @click="runs.refetch()">重试历史</NButton></p><div class="run-history"><button v-for="item in runs.data.value?.items" :key="item.id" :class="{ selected: runId === item.id }" @click="chooseRun(item.id)"><strong>{{ parseLabels[item.status] }}</strong><span>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</span><small>{{ item.parserVersion || '等待解析器记录' }} · {{ item.segmentCount }} 个片段</small></button></div><p v-if="runs.data.value && !runs.data.value.items.length" class="field-help">尚未执行处理。点击上方按钮显式开始。</p><div v-if="runCursors.length || runs.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!runCursors.length" @click="runBefore = runCursors.pop()">较新执行</NButton><NButton size="small" :disabled="!runs.data.value?.nextCursor" @click="nextRuns">更早执行</NButton></div>
          <p v-if="run.error.value" class="notice error">{{ errorText(run.error.value) }}<NButton text @click="run.refetch()">重试执行状态</NButton></p><p v-if="run.data.value && run.data.value.attachmentId !== id" class="notice error">当前执行属于另一份附件，请从该附件的执行历史打开。</p><article v-if="run.data.value && run.data.value.attachmentId === id" class="run-result"><h4>{{ parseLabels[run.data.value.status] }} · 执行 {{ run.data.value.id.slice(0, 8) }}</h4><p class="field-help">{{ run.data.value.parserVersion || '解析器版本尚未记录' }} · {{ run.data.value.segmentCount }} 个片段</p><p v-if="run.data.value.truncated" class="notice">本次结果达到处理上限并已截断，不能视为完整文档。</p><p v-for="warning in run.data.value.warnings" :key="warning" class="notice">{{ warning }}</p><p v-if="run.data.value.errorCode" class="notice error">{{ run.data.value.errorCode }}。可查看历史；重试须显式新建执行。</p><p v-if="['QUEUED', 'RUNNING'].includes(run.data.value.status)" class="field-help" role="status">正在等待处理结果，页面会定时查询。关闭页面不会取消已保存任务。</p><template v-if="run.data.value.status === 'SUCCEEDED' && run.data.value.hasPreview"><NButton size="small" @click="previewOpen = !previewOpen">{{ previewOpen ? '收起 PNG 预览' : '展开私有 PNG 预览' }}</NButton><img v-if="previewOpen && store.sessionActive" class="private-preview" :src="materialPreviewUrl(run.data.value.id)" :alt="`${current.fileName} 的已生成 PNG 预览`" /></template><MaterialSegments v-if="run.data.value.status === 'SUCCEEDED' && !run.data.value.hasPreview" :key="run.data.value.id" :run-id="run.data.value.id" :disabled="!editable" @choose="chooseSegment" /></article>
        </section>
        <section class="material-section excerpt-composer"><div class="material-section-heading"><h3>{{ excerptMode === 'PARSED' ? '保存所选解析原文' : '手工保存来源摘录' }}</h3><NButton v-if="excerptDirty" text size="small" :disabled="writer.locked.value" @click="clearExcerpt">清空未保存摘录</NButton></div><template v-if="excerptMode === 'PARSED' && selectedSegment"><p class="field-help">{{ selectedSegment.locator }} · UTF-16 [{{ startOffset }}, {{ endOffset }}) · 执行 {{ selectedSegment.runId.slice(0, 8) }}</p><blockquote>{{ excerptText }}</blockquote><p class="field-help">原文不在此编辑。保存时只提交片段及范围，由服务器截取。</p></template><template v-else><p class="field-help">适合扫描件、图片或未支持的布局。定位说明和原文均由你提供，明确记录为手工摘录。</p><label>原文定位说明<NInput v-model:value="manualLocator" :disabled="!editable" :maxlength="500" placeholder="例如：第 2 页，项目介绍第二段；或图片左下角的职责说明" /></label><label>摘录原文<NInput v-model:value="manualText" type="textarea" :maxlength="10000" :autosize="{ minRows: 4, maxRows: 14 }" :disabled="!editable" /></label></template><p class="field-help">{{ excerptProblem }}</p><NButton type="primary" :disabled="!editable || !!excerptProblem" @click="saveExcerpt">保存不可变摘录</NButton></section>
        <section class="material-section"><h3>已保存的来源摘录</h3><label class="excerpt-status-filter">显示范围<NSelect :value="excerptStatus" :options="excerptStatusOptions" :disabled="!store.sessionActive" @update:value="selectExcerptStatus" /></label><p class="field-help">有效范围排除已更正的旧摘录；“全部摘录”包含更正历史，原 ID 仍可读取旧原文。此处不按 AI 用途过滤。</p><p v-if="excerpts.error.value" class="notice error">{{ errorText(excerpts.error.value) }}<NButton text @click="excerpts.refetch()">重试摘录列表</NButton></p><article v-for="item in excerpts.data.value?.items" :key="item.id" class="saved-excerpt"><p>{{ item.text.slice(0, 160) }}{{ item.text.length > 160 ? '…' : '' }}</p><small>{{ item.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ item.locator }}</small><button class="text-link" :disabled="lifecycleLocked || correctionLocked" @click="showExcerpt(item.id)">查看确切原文与来源</button></article><p v-if="excerpts.data.value && !excerpts.data.value.items.length" class="field-help">{{ excerptStatus === 'ACTIVE' ? '当前没有可新引用的有效摘录，可切换全部范围核对更正历史。单纯上传和解析不会自动创建事实。' : '当前范围没有保存的摘录。' }}</p><div v-if="excerptCursors.length || excerpts.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!excerptCursors.length" @click="excerptCursor = excerptCursors.pop()">上一页</NButton><NButton size="small" :disabled="!excerpts.data.value?.nextCursor" @click="nextExcerpts">下一页</NButton></div><div v-if="shownExcerptId" ref="readoutElement" tabindex="-1" aria-label="确切摘录原文与直接引用入口"><MaterialExcerptReadout :id="shownExcerptId" /></div><MaterialKnowledgePreview v-if="shownExcerptId" :excerpt-id="shownExcerptId" :attachment-id="id" :enabled="knowledgePreviewEnabled" /><MaterialCorrection v-if="shownExcerptId" :excerpt-id="shownExcerptId" :attachment-id="id" :enabled="knowledgePreviewEnabled" @changed="refresh" /><MaterialKnowledgeImpacts v-if="shownExcerptId" :key="shownExcerptId" :item-id="shownExcerptId" context="MATERIAL_EXCERPT" :visible="knowledgePreviewEnabled" :disabled="writer.locked.value || uploadLocked || lifecycleLocked || correctionLocked" /><MaterialLifecycle v-if="shownExcerptId" :excerpt-id="shownExcerptId" :attachment-id="id" :enabled="Boolean(status?.enabled)" @changed="refresh" @references="focusExcerptReferences" /></section>
        <MaterialDependencyImpacts v-if="shownExcerptId" :key="shownExcerptId" :excerpt-id="shownExcerptId"
          :enabled="knowledgePreviewEnabled" @changed="refresh" />
      </template>
      </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.material-detail { display: grid; align-content: start; gap: 17px; min-width: 0; padding: 26px clamp(18px, 3vw, 34px); overflow-wrap: anywhere; }
.material-detail .section-label { letter-spacing: 0; }
.material-detail h2 { font-size: 22px; }
.material-digest { display: block; margin-top: 5px; font-size: 11px; }
.material-section { display: grid; gap: 12px; padding: 19px 0 7px; border-top: 1px solid var(--line); }
.material-section-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 11px; }
.description-label, .excerpt-composer > label { display: grid; gap: 8px; color: var(--muted); font-size: 12px; }
.attempt-row { display: grid; gap: 5px; padding: 9px 0; border-bottom: 1px solid var(--line); font-size: 12px; }
.attempt-row > div { display: flex; flex-wrap: wrap; gap: 8px 14px; }
.attempt-row small { color: var(--muted); }
.run-history { display: grid; gap: 3px; }
.run-history > button { display: grid; grid-template-columns: minmax(80px, 1fr) auto; gap: 5px 15px; text-align: left; padding: 11px; border-radius: 5px; font-size: 12px; }
.run-history > button:hover, .run-history > button.selected { background: var(--surface-soft); }
.run-history small { grid-column: 1 / -1; color: var(--muted); }
.run-result { display: grid; gap: 12px; padding-top: 14px; }
.private-preview { display: block; max-width: 100%; max-height: 600px; object-fit: contain; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-soft); }
.excerpt-composer > blockquote { margin: 0; max-height: 300px; overflow: auto; padding: 12px; border-left: 2px solid var(--brand); background: var(--surface-soft); white-space: pre-wrap; font-size: 13px; }
.excerpt-composer > .n-button { justify-self: start; }
.saved-excerpt { display: grid; gap: 6px; padding: 12px 0; border-bottom: 1px solid var(--line); font-size: 12px; }
.saved-excerpt small { color: var(--muted); }
.saved-excerpt > button { text-align: left; }
.excerpt-status-filter { display: grid; gap: 8px; max-width: 280px; font-size: 12px; }
.preserve { white-space: pre-wrap; }
.material-document-layout { display: grid; grid-template-columns: minmax(0, 1fr) 310px; align-items: start; gap: 14px; min-width: 0; }
.material-management summary { cursor: pointer; padding-block: 12px; font-size: 16px; color: var(--muted); }.material-management .detail-header { margin-block: 16px; }.material-management .material-digest { overflow-wrap: anywhere; }
.material-document, .material-processing { min-width: 0; border: 1px solid var(--line); border-radius: 18px; background: var(--surface); overflow: hidden; }
.material-document > header { display: flex; justify-content: space-between; align-items: center; gap: 12px; min-height: 68px; padding: 16px 20px; border-bottom: 1px solid var(--line); font-size: 22px; }
.material-pdf { display: block; width: 100%; height: 700px; border: 0; background: #ddd8ce; }
.material-original-image { display: block; max-width: 100%; height: 700px; object-fit: contain; margin: auto; }
.document-unavailable { display: grid; place-content: center; gap: 18px; min-height: 600px; padding: 30px; text-align: center; font-size: 20px; color: var(--muted); }
.material-processing { padding: 22px; max-height: 770px; overflow-y: auto; }.material-processing .material-section:first-child { padding-top: 0; border-top: 0; }.material-processing h3 { font-size: 23px; }.material-processing label { font-size: 16px; }.material-processing :is(.attempt-row,.run-history > button,.saved-excerpt) { font-size: 16px; padding-block: 16px; }.material-processing :deep(.material-segments textarea) { font-size: 17px; }.material-processing :deep(.material-segments article > header) { font-size: 16px; }
@media(max-width:1200px) { .material-document-layout { grid-template-columns: minmax(0, 1fr); }.material-processing { max-height: none; }.material-pdf { height: 650px; } }
@media(min-width:1201px) {
  .material-document-layout { grid-template-columns: minmax(0, 1fr) 356px; gap: 13px; }
  .material-document, .material-processing { height: 784px; }
  .material-pdf, .material-original-image { height: 714px; }
  .material-processing { display: flex; flex-direction: column; padding: 18px 17px; max-height: 784px; }
  .material-processing > * { flex: none; order: 5; }.material-processing > .material-section { order: 0; }.material-processing > .material-management { order: 2; }.material-processing > .excerpt-composer { order: 3; }.material-processing > .material-section:last-of-type { order: 4; }
}
@media (max-width: 600px) { .run-history > button { grid-template-columns: 1fr; } }
</style>
