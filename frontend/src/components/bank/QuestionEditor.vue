<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { Question, QuestionInput, QuestionReference as Reference, QuestionVersion } from '../../api/bank'
import { cloneQuestion, emptyQuestion, questionProblem, questionWithReference } from '../../api/bank'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useRecentVisit } from '../../composables/useRecentVisit'
import { useWorkspaceStore } from '../../state/workspace'
import AiQuestionOrigins from '../ai/AiQuestionOrigins.vue'
import RecollectionQuestionOrigin from '../review/RecollectionQuestionOrigin.vue'
import QuestionExposureHistory from './QuestionExposureHistory.vue'
import QuestionForm from './QuestionForm.vue'
import QuestionHistory from './QuestionHistory.vue'
import QuestionReadout from './QuestionReadout.vue'
import QuestionReference from './QuestionReference.vue'

const props = defineProps<{ id?: string }>()
const emit = defineEmits<{ changed: []; created: [value: Question]; close: [] }>()
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const writer = useCommand()
const initial = emptyQuestion()
const current = ref<Question>()
const content = ref<QuestionInput>(cloneQuestion(initial))
const baseline = ref<QuestionInput>(cloneQuestion(initial))
const editingVersion = ref<string>()
const reference = ref<Reference>()
const tab = ref<'read' | 'edit' | 'history' | 'exposures'>(props.id ? 'read' : 'edit')
const savedMessage = ref('')
const createdResult = ref<Question>()
const serverCopy = ref<Question>()
const serverLoading = ref(false)
const serverError = ref('')
const deferredReceipt = shallowRef<() => void>()
let alive = true
let serverRequest = 0
let commandEpoch = store.sessionEpoch
const creating = computed(() => !props.id)
const dirty = computed(() => JSON.stringify(content.value) !== JSON.stringify(baseline.value))
const locked = computed(() => writer.locked.value || Boolean(deferredReceipt.value))
const reviewKeys = computed(() => [...store.changes.keys()].filter(key => key.startsWith('standard-review:')))
const reviewProtected = computed(() => reviewKeys.value.some(key => { const state = store.changes.get(key); return state?.dirty || state?.locked }))
const editReady = computed(() => creating.value || (Boolean(current.value) && editingVersion.value === current.value?.currentVersion.id))
const editable = computed(() => store.sessionActive && !locked.value && editReady.value && (creating.value || tab.value === 'edit') && (creating.value || current.value?.status === 'ACTIVE'))
const problem = computed(() => questionProblem(content.value))
const detail = useQuery({ queryKey: computed(() => ['bank', store.sessionEpoch, 'item', props.id]), enabled: computed(() => store.sessionActive && Boolean(props.id)), queryFn: () => read<Question>(`/bank/questions/${props.id}`) })
const recordRecentVisit = useRecentVisit('QUESTION', () => props.id)
const latestReferences = computed(() => detail.data.value?.currentVersion.id === current.value?.currentVersion.id ? detail.data.value?.knowledgeReferences : current.value?.knowledgeReferences)
const staleCount = computed(() => latestReferences.value?.filter(link => link.state !== 'CURRENT').length ?? 0)
const changeState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
watch([dirty, locked], ([changed, waiting]) => {
  changeState.dirty = changed
  commandState.locked = waiting
  store.changes.set('bank-editor', changeState)
  store.changes.set('bank-command', commandState)
}, { immediate: true, flush: 'sync' })
watch(detail.data, value => {
  // 后台刷新只能推进干净正文；已确认回执的修订也不能被迟到查询倒退。
  if (value && value.id === props.id && (!current.value || (!dirty.value && !locked.value && !reviewProtected.value && value.revision >= current.value.revision))) {
    accept(value)
    recordRecentVisit(value.id)
  }
}, { immediate: true })
watch(() => route.query.version, value => { if (typeof value === 'string' && props.id) tab.value = 'history' }, { immediate: true })
watch(() => store.sessionEpoch, () => {
  serverRequest++
  serverCopy.value = undefined
  serverError.value = ''
  serverLoading.value = false
  // 审阅未决请求保留在原确切版本内，重新登录后仍能使用原键恢复。
  if (!reviewProtected.value) reference.value = undefined
})
onUnmounted(() => {
  alive = false
  serverRequest++
  if (store.changes.get('bank-editor') === changeState) store.changes.delete('bank-editor')
  if (store.changes.get('bank-command') === commandState) store.changes.delete('bank-command')
})

function accept(value: Question) {
  const sameVersion = current.value?.currentVersion.id === value.currentVersion.id
  current.value = value
  if (!sameVersion) {
    editingVersion.value = undefined
    content.value = cloneQuestion(initial)
    baseline.value = cloneQuestion(initial)
    reference.value = undefined
  }
}
function refresh() { void queryClient.invalidateQueries({ queryKey: ['bank'] }); void queryClient.invalidateQueries({ queryKey: ['bank-collections'] }); emit('changed') }
function receive(value: Question, message: string) {
  accept(value)
  savedMessage.value = message
  serverCopy.value = undefined
  queryClient.setQueryData(['bank', store.sessionEpoch, 'item', value.id], value)
  refresh()
}
function run<T>(path: string, method: string, body: unknown, apply: (value: T) => void, validate?: (value: T) => void) {
  if (!store.sessionActive || locked.value) return
  serverRequest++
  serverLoading.value = false
  commandEpoch = store.sessionEpoch
  void writer.run<T>(path, method, body, value => {
    if (!alive) return
    // 回执校验先于延迟接收，失败时由写入器保留原请求与曝光事件的幂等键。
    validate?.(value)
    // 写入回执可能在重新登录后才到达。保留已确认结果，显式接收后再更新表单与路由。
    if (!store.sessionActive || commandEpoch !== store.sessionEpoch) deferredReceipt.value = () => apply(value)
    else apply(value)
  })
}
function retry() { if (store.sessionActive && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (!store.sessionActive || !deferredReceipt.value) return; const apply = deferredReceipt.value; deferredReceipt.value = undefined; apply() }
function loadReference(version: QuestionVersion, forEditing = false) {
  if (!current.value || locked.value || !store.sessionActive) return
  if (forEditing && (current.value.status !== 'ACTIVE' || dirty.value)) return
  if (!store.canLeave(reviewKeys.value)) return
  const questionId = current.value.id
  run<Reference>(`/bank/questions/${questionId}/versions/${version.id}/reference`, 'POST', {}, value => {
    reference.value = value
    void queryClient.invalidateQueries({ queryKey: ['bank', store.sessionEpoch, 'exposures', questionId] })
    if (forEditing && current.value?.currentVersion.id === version.id && !dirty.value && current.value.status === 'ACTIVE') {
      const full = questionWithReference(version.content, value)
      content.value = cloneQuestion(full)
      baseline.value = cloneQuestion(full)
      editingVersion.value = version.id
      savedMessage.value = '已打开当前参考并记录查看，可以编辑完整题目'
    }
  }, value => {
    if (value.questionId !== questionId || value.versionId !== version.id) throw new ApiError(502, 'REFERENCE_MISMATCH', '参考回执与所选版本不一致，请用原请求重试。')
  })
}
function save() {
  if (!editable.value || problem.value || (!creating.value && !dirty.value)) return
  const snapshot = cloneQuestion(content.value)
  if (creating.value) {
    run<Question>('/bank/questions', 'POST', snapshot, value => {
      createdResult.value = value
      content.value = cloneQuestion(initial)
      baseline.value = cloneQuestion(initial)
      changeState.dirty = false
      commandState.locked = false
      refresh()
      emit('created', value)
    })
  } else if (current.value) {
    run<Question>(`/bank/questions/${current.value.id}`, 'PUT', { expectedRevision: current.value.revision, content: snapshot }, value => {
      receive(value, `已保存为 v${value.currentVersion.number}，并记录本次创作`)
      const saved = questionWithReference(value.currentVersion.content, snapshot)
      content.value = cloneQuestion(saved)
      baseline.value = cloneQuestion(saved)
      editingVersion.value = value.currentVersion.id
      reference.value = undefined
    })
  }
}
function organize(action: 'favorite' | 'archive' | 'restore') {
  if (!current.value || dirty.value || locked.value || !store.sessionActive) return
  if (!store.canLeave(reviewKeys.value)) return
  if (action === 'archive' && !window.confirm('停用后退出默认题库选择，历史题干、参考和已有题单引用继续保留。确认停用？')) return
  const body = { expectedRevision: current.value.revision, ...(action === 'favorite' ? { favorite: !current.value.favorite } : {}) }
  run<Question>(`/bank/questions/${current.value.id}/${action}`, action === 'favorite' ? 'PUT' : 'POST', body, value => receive(value, action === 'favorite' ? value.favorite ? '已收藏' : '已移出收藏' : action === 'archive' ? '已停用，历史版本保留' : '已恢复，可继续使用'))
}
function changeTab(value: typeof tab.value) {
  if (value !== tab.value && !store.canLeave(reviewKeys.value)) return
  tab.value = value
  if (value !== 'history' && typeof route.query.version === 'string') void router.push({ name: 'bank', query: { item: props.id } })
}
function discardLocal() {
  if (locked.value || !store.sessionActive || !window.confirm('丢弃当前未保存的题目修改，恢复到开始编辑时的内容？')) return
  content.value = cloneQuestion(baseline.value)
  savedMessage.value = '已撤销本地修改'
}
async function compareServer() {
  if (!props.id || !store.sessionActive || locked.value || serverLoading.value) return
  const epoch = store.sessionEpoch
  const request = ++serverRequest
  serverLoading.value = true
  serverError.value = ''
  try {
    const value = await read<Question>(`/bank/questions/${props.id}`)
    if (alive && request === serverRequest && epoch === store.sessionEpoch && store.sessionActive && !locked.value && value.revision >= (current.value?.revision ?? 0)) serverCopy.value = value
  } catch (error) {
    if (alive && request === serverRequest && epoch === store.sessionEpoch && store.sessionActive) serverError.value = errorText(error)
  } finally { if (alive && request === serverRequest) serverLoading.value = false }
}
function adoptServer() {
  if (!serverCopy.value || locked.value || !store.sessionActive) return
  if (current.value && serverCopy.value.revision < current.value.revision) {
    serverCopy.value = undefined
    serverError.value = '这份比较副本已过期，请重新读取服务器内容。'
    return
  }
  if (!store.canLeave(reviewKeys.value)) return
  if (!window.confirm('采用已展示的服务器题目并放弃本地未保存修改？再次编辑前会显式打开该版本参考。')) return
  current.value = undefined
  accept(serverCopy.value)
  serverCopy.value = undefined
  writer.error.value = undefined
  tab.value = 'read'
  savedMessage.value = '已采用服务器副本'
}
function saveSeparate() {
  if (!current.value || !editReady.value || problem.value || locked.value || !store.sessionActive) return
  const snapshot = cloneQuestion(content.value)
  run<Question>('/bank/questions', 'POST', snapshot, value => {
    createdResult.value = value
    savedMessage.value = '本地内容已另存为新题，当前输入仍保留'
    refresh()
  })
}
</script>

<template>
  <section class="question-editor" aria-label="题目详情">
    <p v-if="!creating && !current && detail.isPending.value" class="loading-block" role="status">正在读取题目…</p>
    <div v-else-if="!creating && !current" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text @click="detail.refetch()">重试读取</NButton></div>
    <template v-else>
      <header class="question-detail-header"><div><p class="field-help">{{ creating ? '手工建立题目' : `题目 · v${current?.currentVersion.number}` }}</p><h2>{{ creating ? '写下一道值得练习的问题' : current?.currentVersion.content.title }}</h2></div><button class="icon-button" aria-label="关闭题目详情" @click="emit('close')">×</button></header>
      <div class="question-save-state" aria-live="polite"><span :class="{ unsaved: dirty }">{{ writer.busy.value ? '正在处理请求…' : writer.pending.value ? '结果尚未确认，请用原请求重试' : deferredReceipt ? '回执已确认，等待接收结果' : dirty ? '有未保存输入' : creating ? '填写后保存首个版本' : `已读取服务器修订 ${current?.revision}` }}</span><span class="status-pill">个人参考／未审</span><span v-if="current?.status === 'ARCHIVED'" class="status-pill">已停用</span></div>
      <p v-if="current && detail.error.value" class="notice error" role="alert">最新读取未完成，当前输入保留：{{ errorText(detail.error.value) }}<NButton text @click="detail.refetch()">重试读取</NButton></p>
      <p v-if="current && detail.data.value && detail.data.value.revision > current.revision" class="notice">服务器已有更新，本地输入仍使用原修订。<NButton text :disabled="locked || serverLoading" @click="compareServer">查看服务器副本</NButton></p>
      <p v-if="staleCount" class="question-warning" role="status">{{ staleCount }} 个知识引用已改版或归档。当前题目仍指向原发布版本；需要更新时请明确重新选择。</p>
      <div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" size="small" :disabled="!store.sessionActive" @click="retry">用原请求重试</NButton><template v-if="current && writer.error.value instanceof ApiError && writer.error.value.status === 409"><NButton size="small" :disabled="locked || serverLoading" @click="compareServer">查看服务器副本</NButton><NButton v-if="editReady" size="small" :disabled="locked || !!problem" @click="saveSeparate">将本地内容另存为新题</NButton></template></div>
      <p v-if="deferredReceipt" class="notice" role="status">请求完成时登录会话已有变化，本地输入尚未替换。<NButton size="small" :disabled="!store.sessionActive" @click="receiveDeferred">接收已确认结果</NButton></p>
      <p v-if="savedMessage" :class="dirty ? 'field-help' : 'success-text'" role="status">{{ savedMessage }}</p>
      <p v-if="createdResult" class="notice" role="status">已创建「{{ createdResult.currentVersion.content.title }}」。<button class="text-link" @click="emit('created', createdResult)">打开已创建题目</button></p>
      <p v-if="serverLoading" class="field-help" role="status">正在读取服务器副本…</p><p v-if="serverError" class="notice error" role="alert">{{ serverError }}</p>
      <section v-if="serverCopy" class="question-server-copy"><h3>服务器副本 · 修订 {{ serverCopy.revision }}</h3><p class="field-help">以下仅展示该版本题干，参考未自动打开。采用副本会放弃本地输入；另存可保留当前编辑内容。</p><QuestionReadout :content="serverCopy.currentVersion.content" :references="serverCopy.knowledgeReferences" /><div class="row-actions"><NButton size="small" :disabled="locked" @click="adoptServer">采用服务器内容</NButton><NButton v-if="editReady" size="small" :disabled="locked || !!problem" @click="saveSeparate">另存本地内容</NButton><NButton size="small" @click="serverCopy = undefined">收起比较</NButton></div></section>
      <nav v-if="current" class="detail-tabs" aria-label="题目详情视图"><button v-for="option in ([['read', '题干与参考'], ['edit', '编辑题目'], ['history', '历史对照'], ['exposures', '接触记录']] as const)" :key="option[0]" :class="{ active: tab === option[0] }" :aria-current="tab === option[0] ? 'page' : undefined" @click="changeTab(option[0])">{{ option[1] }}</button></nav>
      <div v-if="tab === 'read' && current" class="question-body"><p v-if="dirty" class="field-help">正在阅读已保存题干，本地未保存输入仍在编辑页保留。</p><QuestionReadout :content="current.currentVersion.content" :references="latestReferences" /><QuestionReference v-if="reference?.versionId === current.currentVersion.id" :reference="reference" :disabled="locked" /><div v-else class="question-reference-gate"><h3>个人参考</h3><p class="field-help">{{ current.currentVersion.hasReference ? '本版本保存有参考正文或检查点。' : '本版本尚未填写参考正文或检查点。' }}显式打开会记录这次查看，供后续训练保留接触背景。</p><NButton :disabled="locked || !store.sessionActive" @click="loadReference(current.currentVersion)">打开参考并记录查看</NButton></div><AiQuestionOrigins :question-id="current.id" :version-id="current.currentVersion.id" :version-number="current.currentVersion.number" /><RecollectionQuestionOrigin :question-id="current.id" :version-id="current.currentVersion.id" :disabled="locked" /></div>
      <div v-show="creating || tab === 'edit'" class="question-body">
        <template v-if="editReady"><QuestionForm v-model="content" :disabled="!editable" :references="latestReferences" /><p v-if="problem && (dirty || content.title)" class="question-warning" role="status">{{ problem }}</p><div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!editable || !!problem || (!creating && !dirty)" @click="save">{{ creating ? '创建并保存' : '保存新版本' }}</NButton><NButton v-if="dirty" :disabled="locked || !store.sessionActive" @click="discardLocal">撤销未保存修改</NButton></div><p class="field-help">题干、条件和参考一起保存为独立版本。未保存输入仅保留在当前页面，刷新会丢失。</p></template>
        <div v-else-if="current" class="question-reference-gate"><h3>{{ current.status === 'ARCHIVED' ? '题目已停用' : '打开完整内容后编辑' }}</h3><p class="field-help">{{ current.status === 'ARCHIVED' ? '恢复后可继续编辑，已有题目版本与引用保持原样。' : '编辑需要读取当前版本的参考与检查点，并记录查看事件，确保保存时保留完整内容。' }}</p><NButton v-if="current.status === 'ACTIVE'" type="primary" :disabled="locked || !store.sessionActive" @click="loadReference(current.currentVersion, true)">打开当前参考并进入编辑</NButton></div>
      </div>
      <QuestionHistory v-if="tab === 'history' && current" :id="current.id" :current-version="current.currentVersion" :reference="reference" :locked="locked" @reference="loadReference" />
      <QuestionExposureHistory v-if="tab === 'exposures' && current" :id="current.id" />
      <footer v-if="current" class="question-organization"><div class="row-actions"><NButton size="small" :disabled="locked || dirty || !store.sessionActive" @click="organize('favorite')">{{ current.favorite ? '移出收藏' : '收藏题目' }}</NButton><NButton size="small" quaternary :disabled="locked || dirty || !store.sessionActive" @click="organize(current.status === 'ACTIVE' ? 'archive' : 'restore')">{{ current.status === 'ACTIVE' ? '停用题目' : '恢复题目' }}</NButton></div><p class="field-help">{{ dirty ? '先保存或撤销正文修改，再调整收藏和使用状态。' : '收藏与停用不改写题干版本。' }}</p></footer>
    </template>
  </section>
</template>

<style scoped>
.question-editor { padding: 24px 27px 28px; min-width: 0; }
.question-detail-header { display: flex; align-items: start; justify-content: space-between; gap: 15px; }
.question-detail-header > div { min-width: 0; } .question-detail-header h2 { font-size: 21px; margin-top: 6px; overflow-wrap: anywhere; }
.question-save-state { display: flex; flex-wrap: wrap; gap: 8px 12px; margin: 15px 0; color: var(--muted); font-size: 12px; }
.question-save-state .unsaved, .question-warning { color: var(--warm); }
.question-warning { font-size: 12px; padding: 10px 0; max-width: 75ch; }
.question-body { display: grid; gap: 17px; min-width: 0; }
.question-reference-gate { display: grid; justify-items: start; gap: 13px; padding: 20px 0; border-top: 1px solid var(--line); }
.question-reference-gate h3 { font-size: 16px; } .question-reference-gate p { max-width: 70ch; }
.question-organization { margin-top: 27px; padding-top: 17px; border-top: 1px solid var(--line); display: grid; gap: 9px; }
.question-server-copy { display: grid; gap: 15px; padding: 16px; border: 1px solid var(--line); border-radius: 7px; margin: 15px 0; background: var(--surface-soft); }
.question-server-copy h3 { font-size: 16px; } .detail-tabs { flex-wrap: wrap; }
@media (max-width: 700px) { .question-editor { padding: 22px 17px; } .question-detail-header h2 { font-size: 20px; } }
</style>
