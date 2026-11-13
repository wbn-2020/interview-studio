<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NSelect } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText } from '../../api/client'
import { cloneQuestion } from '../../api/bank'
import type { Question, QuestionInput } from '../../api/bank'
import { prepareQuestionFile, questionCandidateSignature, questionFileCandidateProblem } from '../../api/questionFileImport'
import type { QuestionFileCandidate, QuestionFileDuplicatePolicy, QuestionFilePreview } from '../../api/questionFileImport'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import { useWorkspaceStore } from '../../state/workspace'
import QuestionForm from './QuestionForm.vue'

type Row = QuestionFileCandidate & { state: 'PENDING' | 'SKIPPED' | 'CREATED'; result?: Question }
type FileContext = { session: number; identity: string }
const props = defineProps<{ enabled?: boolean }>()
const emit = defineEmits<{ created: [question: Question]; changed: []; cancel: []; state: [dirty: boolean, locked: boolean] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const element = ref<HTMLElement>(), elementVisible = ref(false), active = ref(true)
const documentVisible = ref(document.visibilityState === 'visible')
const file = shallowRef<Omit<QuestionFilePreview, 'candidates'>>()
const rows = ref<Row[]>([]), selectedId = ref(''), reading = ref(false), problem = ref(''), message = ref('')
const confirmed = ref(false), needsReselect = ref(false)
const duplicatePolicy = ref<QuestionFileDuplicatePolicy>('SKIP_IDENTICAL')
const accessIdentity = computed(() => JSON.stringify([access.active?.id ?? null, access.active?.mode ?? null,
  access.active?.materialAccess ?? null]))
const fileContext = shallowRef<FileContext>({ session: -1, identity: '' })
const incoming = shallowRef<{ value: QuestionFilePreview; context: FileContext }>()
const filePicking = ref(false)
const readable = computed(() => props.enabled !== false && active.value && elementVisible.value && documentVisible.value
  && store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const ready = computed(() => readable.value && !needsReselect.value && fileContext.value.session === store.sessionEpoch
  && fileContext.value.identity === accessIdentity.value)
const selected = computed(() => rows.value.find(row => row.id === selectedId.value))
const locked = computed(() => reading.value || writer.locked.value)
const dirty = computed(() => reading.value || Boolean(incoming.value) || rows.value.some(row => row.state === 'PENDING'))
const progress = computed(() => ({
  created: rows.value.filter(row => row.state === 'CREATED').length,
  skipped: rows.value.filter(row => row.state === 'SKIPPED').length,
  pending: rows.value.filter(row => row.state === 'PENDING').length,
}))
const choices = computed(() => rows.value.map(row => ({
  value: row.id, label: `${row.ordinal}. ${row.content.title || '未填写标题'} · ${row.state === 'CREATED' ? '已创建' : row.state === 'SKIPPED' ? '已跳过' : '待确认'}`,
})))
const validationProblem = computed(() => selected.value
  ? selected.value.problem || questionFileCandidateProblem(selected.value.content) : '')
const duplicate = computed(() => {
  const row = selected.value
  if (!row || row.problem || row.state !== 'PENDING') return undefined
  const signature = questionCandidateSignature(row.content)
  return rows.value.find(other => other.id !== row.id && (other.state === 'CREATED' || other.ordinal < row.ordinal) && other.state !== 'SKIPPED'
    && !other.problem && questionCandidateSignature(other.content) === signature)
})
const duplicateBlocked = computed(() => Boolean(duplicate.value && duplicatePolicy.value === 'SKIP_IDENTICAL'))
const changeKey = 'question-file-import', changeState = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, observer: MutationObserver | undefined
let commandContext = { session: -1, access: -1 }
let pickerContext: (FileContext & { generation: number }) | undefined

watch([dirty, locked], ([dirty, locked]) => {
  changeState.dirty = dirty; changeState.locked = locked
  store.changes.set(changeKey, changeState); emit('state', dirty, locked)
}, { immediate: true, flush: 'sync' })
watch([selectedId, duplicatePolicy, () => selected.value?.content], () => { confirmed.value = false }, { deep: true, flush: 'sync' })
watch(writer.error, value => { if (value) confirmed.value = false }, { flush: 'sync' })
watch(readable, () => { confirmed.value = false }, { flush: 'sync' })
watch([() => store.sessionEpoch, () => store.sessionActive, accessIdentity], () => {
  generation++; reading.value = false; confirmed.value = false
  incoming.value = undefined; filePicking.value = false; pickerContext = undefined
  problem.value = ''; message.value = ''
  if (file.value) needsReselect.value = true
}, { flush: 'sync' })
function visibilityChanged() { documentVisible.value = document.visibilityState === 'visible' }
function elementVisibilityChanged() {
  const node = element.value
  elementVisible.value = Boolean(node && node.getClientRects().length && !node.closest('[hidden], [inert], [aria-hidden="true"]'))
  for (let parent: HTMLElement | null = node ?? null; parent && elementVisible.value; parent = parent.parentElement) {
    if (['hidden', 'collapse'].includes(getComputedStyle(parent).visibility)) elementVisible.value = false
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
  alive = false; generation++; observer?.disconnect()
  document.removeEventListener('visibilitychange', visibilityChanged)
  if (store.changes.get(changeKey) === changeState) store.changes.delete(changeKey)
  emit('state', false, false)
})
async function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement, files = Array.from(input.files ?? [])
  const pickedContext = pickerContext
  filePicking.value = false; pickerContext = undefined
  input.value = ''; visibilityChanged(); elementVisibilityChanged()
  const context = pickedContext && pickedContext.generation === generation && contextCurrent(pickedContext) ? pickedContext : undefined
  if (!files.length || locked.value) return
  if (!context) { if (readable.value) problem.value = '无法核对此次文件选择的当前会话与训练身份，请从当前页面点击文件选择器重新选择。'; return }
  const picked = files[0]!
  if (files.length !== 1 || !Number.isSafeInteger(picked.size) || picked.size < 1 || picked.size > 2_000_000) {
    problem.value = '请选择一份1至2000000字节的UTF-8题目文件。'; return
  }
  const ticket = ++generation
  // 原生文件选择返回时会触发焦点核验；本地字节读取只绑定真实身份，不因 checking 或遮挡丢结果。
  const current = () => alive && ticket === generation && contextCurrent(context)
  reading.value = true; problem.value = ''; message.value = ''
  try {
    const buffer = await picked.arrayBuffer()
    if (!current()) return
    if (buffer.byteLength !== picked.size) throw new Error('文件实际大小发生变化，请重新选择。')
    const value = await prepareQuestionFile(picked.name, buffer, current)
    if (!current()) return
    installFile(value, context)
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (alive && ticket === generation) reading.value = false }
}
function contextCurrent(context: FileContext) {
  return alive && store.sessionActive && context.session === store.sessionEpoch && context.identity === accessIdentity.value
}
function beginFilePick(event: Event) {
  if (!readable.value || locked.value) { event.preventDefault(); return }
  pickerContext = { session: store.sessionEpoch, identity: accessIdentity.value, generation }
  filePicking.value = true
}
function cancelFilePick() { filePicking.value = false; pickerContext = undefined }
function installFile(value: QuestionFilePreview, context: FileContext) {
  if (!contextCurrent(context)) return
  const same = file.value?.sha256 === value.sha256 && file.value.fileName === value.fileName
    && rows.value.length === value.candidates.length
    && rows.value.every((row, index) => row.ordinal === value.candidates[index]?.ordinal && row.original === value.candidates[index]?.original)
  if (!same && rows.value.some(row => row.state === 'PENDING')) {
    if (!readable.value) { incoming.value = { value, context }; return }
    if (!window.confirm('更换文件会放弃当前尚未处理的本地候选及编辑；已创建题目仍保留。确认更换？')) return
  }
  if (!contextCurrent(context)) return
  if (!same) {
    rows.value = value.candidates.map(row => ({ ...row, state: 'PENDING' }))
    selectedId.value = rows.value[0]?.id ?? ''; duplicatePolicy.value = 'SKIP_IDENTICAL'
  }
  file.value = { fileName: value.fileName, sha256: value.sha256, byteSize: value.byteSize }
  fileContext.value = { session: context.session, identity: context.identity }
  incoming.value = undefined; needsReselect.value = false; confirmed.value = false
  message.value = same ? '已核对为同一原始文件，保留本批已创建、已跳过和本地编辑记录。' : '候选已在本地生成；逐题核对并明确确认后才写入服务器。'
}
function useIncoming() {
  if (!readable.value || locked.value || !incoming.value) return
  installFile(incoming.value.value, incoming.value.context)
}
function updateContent(value: QuestionInput) {
  if (ready.value && !locked.value && selected.value?.state === 'PENDING' && !selected.value.problem) selected.value.content = value
}
function next() {
  if (!ready.value || locked.value) return
  const index = rows.value.findIndex(row => row.id === selectedId.value)
  const remaining = [...rows.value.slice(index + 1), ...rows.value.slice(0, index + 1)]
  selectedId.value = remaining.find(row => row.state === 'PENDING')?.id ?? selectedId.value
}
function skip() {
  const row = selected.value
  if (!ready.value || locked.value || !row || row.state !== 'PENDING') return
  row.state = 'SKIPPED'; confirmed.value = false; message.value = `已跳过第${row.ordinal}项，未向服务器写入。`
}
function restore() {
  if (ready.value && !locked.value && selected.value?.state === 'SKIPPED') { selected.value.state = 'PENDING'; confirmed.value = false }
}
function receive(value: Question, rowId: string, snapshot: QuestionInput) {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
  const { referenceAnswer: _reference, checkpoints: _checkpoints, ...prompt } = snapshot
  if (!value || !uuid.test(value.id) || value.status !== 'ACTIVE' || value.revision !== 0
    || !value.currentVersion || !uuid.test(value.currentVersion.id) || value.currentVersion.number !== 1
    || value.currentVersion.standardStatus !== 'PERSONAL_UNREVIEWED'
    || !value.currentVersion.content || Object.entries(prompt).some(([key, expected]) =>
      JSON.stringify(value.currentVersion.content[key as keyof typeof prompt]) !== JSON.stringify(expected))) {
    throw new ApiError(502, 'QUESTION_IMPORT_RECEIPT_MISMATCH', '新建题回执与本项完整题干不一致，请保留原请求与原键恢复。')
  }
  if (!alive || !readable.value || commandContext.session !== store.sessionEpoch || commandContext.access !== currentTrainingAccessEpoch()) {
    throw new ApiError(0, 'QUESTION_IMPORT_RECEIPT_DEFERRED', '资料读取条件已变化，请恢复后使用原请求与原键读取回执。')
  }
  const row = rows.value.find(row => row.id === rowId)
  if (!row) throw new ApiError(0, 'QUESTION_IMPORT_ROW_UNAVAILABLE', '本项候选上下文暂不可用，请保留原键恢复。')
  row.state = 'CREATED'; row.result = value; confirmed.value = false
  message.value = `第${row.ordinal}项已创建为个人未审题目；其他候选仍须分别确认。`
  void queryClient.invalidateQueries({ queryKey: ['bank'] })
  emit('created', value); emit('changed')
}
function create() {
  const row = selected.value
  if (!ready.value || locked.value || !row || row.state !== 'PENDING' || validationProblem.value || duplicateBlocked.value || !confirmed.value) return
  const rowId = row.id, snapshot = cloneQuestion(row.content)
  commandContext = { session: store.sessionEpoch, access: currentTrainingAccessEpoch() }
  void writer.run<Question>('/bank/questions', 'POST', snapshot, value => receive(value, rowId, snapshot))
}
function retry() {
  if (!readable.value || writer.busy.value || !writer.pending.value) return
  commandContext = { session: store.sessionEpoch, access: currentTrainingAccessEpoch() }
  void writer.retry()
}
function clear() {
  if (locked.value || !store.canLeave([changeKey])) return
  generation++; file.value = undefined; rows.value = []; selectedId.value = ''
  incoming.value = undefined; cancelFilePick()
  needsReselect.value = false; confirmed.value = false; problem.value = ''; message.value = ''; writer.error.value = undefined
}
function cancel() { if (!locked.value && store.canLeave([changeKey])) emit('cancel') }
</script>

<template>
  <section ref="element" class="question-file-import" aria-label="从本地文件整理题目候选">
    <header><div><p class="section-label">文件候选导入</p><h2>逐题核对后加入个人题库</h2></div><NButton :disabled="locked" @click="cancel">关闭</NButton></header>
    <p class="notice">文件选择、解析和候选编辑仅在浏览器内存进行；点击当前题目的“确认新建”才发送该项完整内容。每项独立保存，不会自动批量写入，也不会发送模型。</p>
    <p class="field-help">支持UTF-8的JSON、Markdown和TXT，单文件1至2000000字节、全文最多1000000字符。JSON可为一个题目对象、1至50项的数组或仅含questions数组的对象；也可用content包装题目字段。Markdown／TXT整份内容只形成一道候选，不猜测题目或答案分隔。</p>
    <p class="field-help">本地进度刷新后清空，已创建题目保留在服务器。这里不提供持久文件任务、全题库查重或按标题覆盖；重选同名且原始字节一致的文件仅能恢复本次页面内保留的进度。</p>
    <p v-if="!readable" class="notice" role="status">当前资料访问或页面可见性未就绪，候选暂不展示。未确认的写入请求仍保留原路径、原正文和幂等键。</p>
    <div v-show="readable" class="import-body" :inert="!readable || undefined">
      <label class="file-field">选择题目文件<input type="file" accept=".json,.md,.markdown,.txt" :disabled="locked || !readable && !filePicking" @click="beginFilePick" @cancel="cancelFilePick" @change="chooseFile" /></label>
      <p v-if="reading" role="status">正在本地读取文件并核对格式…</p>
      <p v-if="needsReselect" class="notice" role="status">会话或实际训练身份已变化，请重新选择原始文件后继续查看本批候选。已有本地选择暂存；未决请求应先使用下方原键恢复，不能另建请求。</p>
      <div v-if="incoming" class="notice" role="status"><p>已读取新文件 {{ incoming.value.fileName }}。原候选和编辑仍保留，请明确确认是否更换。</p><div class="row-actions"><NButton :disabled="locked" @click="useIncoming">核对并使用已读取文件</NButton><NButton :disabled="locked" @click="incoming = undefined">放弃新文件，保留原候选</NButton></div></div>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
      <p v-if="message && ready" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="writer.busy.value" @click="retry">用原正文与原键恢复本项</NButton></p>
      <p v-if="writer.pending.value" class="notice" role="status">当前题目的保存结果尚未确认，文件、内容、选择和重复策略均已冻结。恢复请求复用原始完整内容及幂等键。</p>
      <template v-if="file && ready">
        <strong>{{ file.fileName }} · {{ file.byteSize }}字节 · {{ rows.length }}项候选</strong>
        <p class="field-help file-hash">原始文件SHA-256：<code>{{ file.sha256 }}</code></p>
        <p role="status">已创建 {{ progress.created }} · 已跳过 {{ progress.skipped }} · 待处理 {{ progress.pending }}</p>
        <div class="import-options">
          <label>当前候选<NSelect v-model:value="selectedId" :disabled="locked" :options="choices" filterable /></label>
          <label>本批相同完整内容的处理<NSelect v-model:value="duplicatePolicy" :disabled="locked" :options="[{ value: 'SKIP_IDENTICAL', label: '遇到相同内容需跳过（默认）' }, { value: 'CREATE_COPY', label: '明确允许另建副本，仍逐题确认' }]" /></label>
        </div>
        <p class="field-help">相同内容只比较本批已创建题目及更早、未跳过的候选，包含题干、条件、参考、来源及关联等完整可写字段，忽略检查点ID。此比较不证明服务器中没有重复题目，也不判断两道语义相近的题目相同。</p>
        <article v-if="selected" class="candidate">
          <div class="candidate-heading"><h3>第{{ selected.ordinal }}项 · {{ selected.content.title || '未填写标题' }}</h3><span>{{ selected.state === 'CREATED' ? '已创建' : selected.state === 'SKIPPED' ? '已跳过' : '待确认' }}</span></div>
          <ul v-if="selected.notices.length" class="notices"><li v-for="notice in selected.notices" :key="notice">{{ notice }}</li></ul>
          <p class="notice">可写字段逐项保留；外部对象ID、版本、收藏、已审标记和审阅者只展示原文，不作为当前系统身份或审核依据。保存创建首版个人未审题目，并记录编写曝光；主题及确切知识版本仍由服务器核对。</p>
          <details><summary>核对本项原始内容（未渲染、未执行）</summary><pre tabindex="0">{{ selected.original }}</pre></details>
          <p v-if="selected.problem" class="notice error" role="alert">{{ selected.problem }}本项结构无法安全编辑，请跳过，或修正原文件后作为新文件重新选择。</p>
          <QuestionForm v-else :key="selected.id" :model-value="selected.content" :disabled="locked || selected.state !== 'PENDING'" @update:model-value="updateContent" />
          <p v-if="selected.state === 'PENDING' && validationProblem" class="notice error" role="alert">{{ validationProblem }}</p>
          <p v-if="duplicate" class="notice" :class="{ error: duplicateBlocked }" role="status">本项完整内容与第{{ duplicate.ordinal }}项相同。{{ duplicateBlocked ? '默认阻止重复创建，请明确跳过本项，或改为允许副本后重新确认。' : '你已明确允许副本；本次确认会创建另一道独立题目。' }}</p>
          <template v-if="selected.state === 'PENDING'">
            <NCheckbox v-model:checked="confirmed" :disabled="locked || !!validationProblem || duplicateBlocked">我已核对本项全部题干、条件、参考、来源和关联，确认作为新的个人未审题目保存。</NCheckbox>
            <div class="row-actions"><NButton type="primary" :disabled="locked || !confirmed || !!validationProblem || duplicateBlocked" :loading="writer.busy.value" @click="create">确认新建当前题目</NButton><NButton :disabled="locked" @click="skip">明确跳过当前项</NButton></div>
          </template>
          <p v-if="selected.state === 'CREATED' && selected.result" class="notice" role="status">已确认首版创建回执：{{ selected.result.id }}。本项不会再次保存；本地原文与选项保留供核对。</p>
          <NButton v-if="selected.state === 'SKIPPED'" :disabled="locked" @click="restore">恢复本项为待处理</NButton>
        </article>
        <div class="row-actions"><NButton :disabled="locked || !progress.pending" @click="next">定位下一项待处理候选</NButton><NButton :disabled="locked" @click="clear">结束本批并清除本地候选</NButton></div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.question-file-import { display: grid; gap: 16px; min-width: 0; padding: 22px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.import-body { display: grid; gap: 16px; min-width: 0; }
header { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 16px; align-items: start; }
h2 { font-size: 21px; } h3 { font-size: 16px; overflow-wrap: anywhere; }
.file-field, .import-options > label { display: grid; gap: 7px; color: var(--muted); font-size: 12px; min-width: 0; }
.file-field input { max-width: 100%; }
.import-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.candidate { display: grid; gap: 16px; min-width: 0; padding-top: 18px; border-top: 1px solid var(--line); }
.candidate-heading { display: flex; gap: 16px; align-items: start; justify-content: space-between; }
.candidate-heading span { flex-shrink: 0; color: var(--muted); font-size: 12px; }
.notices { margin: 0; padding-left: 20px; color: var(--muted); font-size: 12px; } .notices li { margin: 5px 0; }
strong, .file-hash { overflow-wrap: anywhere; } details { min-width: 0; } summary { cursor: pointer; padding: 8px 0; }
pre { max-height: 440px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; border: 1px solid var(--line); padding: 14px; font-size: 12px; }
@media (max-width: 700px) { .import-options { grid-template-columns: 1fr; } .question-file-import { padding: 14px; } }
</style>
