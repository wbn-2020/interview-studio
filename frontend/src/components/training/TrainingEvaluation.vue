<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox } from 'naive-ui'
import { ApiError, errorText, preview as previewRequest, read } from '../../api/client'
import type { AiEvaluationAccess, AiEvaluationPage, AiPreview, AiSettings, AiTask } from '../../api/ai'
import { aiDate, aiKnowledgeMaterialChecksMatch, aiPendingStatuses, aiTaskStatusLabel } from '../../api/ai'
import type { TrainingAnswer, TrainingSession } from '../../api/training'
import { notifyTrainingChanged } from '../../api/training'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import AiManifest from '../ai/AiManifest.vue'
import AiAuthorizationChoice from '../ai/AiAuthorizationChoice.vue'
import type { AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationRequestFields } from '../../api/aiAuthorization'

const props = defineProps<{ session: TrainingSession; questionId: string; answer?: TrainingAnswer | null; disabled?: boolean }>()
const emit = defineEmits<{ state: [value: { dirty: boolean; locked: boolean }]; changed: [] }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const writer = useCommand()
const active = ref(true)
const currentAccess = shallowRef<AiEvaluationAccess>()
const accessConfirmed = ref(false)
const earlyConfirmed = ref(false)
const consent = ref(false)
const authorization = ref<AiAuthorizationReference>()
const manifest = shallowRef<AiPreview>()
const previewing = ref(false)
const previewError = ref('')
const message = ref('')
const createdId = ref('')
const deferred = shallowRef<() => void>()
const cursor = ref<string>()
const cursors = ref<(string | undefined)[]>([])
let alive = true
let previewSequence = 0
let accessSequence = 0
let commandEpoch = store.sessionEpoch
const path = computed(() => `/training/sessions/${props.session.id}/questions/${props.questionId}/evaluations`)
const readable = computed(() => active.value && store.sessionActive && !access.checking && !access.challenge
  && (!access.restricted || access.active?.id === props.session.id))
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || Boolean(props.disabled) || locked.value || previewing.value)
const allowed = computed(() => Boolean(props.answer && currentAccess.value?.answerId === props.answer.id && currentAccess.value.allowed))
const early = computed(() => props.session.status !== 'ENDED' && props.session.config.feedbackTiming === 'AFTER_SESSION')
const assistance = computed(() => props.session.status !== 'ENDED' && props.session.mode !== 'LEARNING' && !props.session.materialAccess)
const dirty = computed(() => accessConfirmed.value || earlyConfirmed.value || consent.value)
const accessKey = () => ['training-evaluation-access', store.sessionEpoch, props.session.id, props.questionId, props.answer?.id]
const accessQuery = useQuery({
  queryKey: computed(accessKey),
  enabled: computed(() => readable.value && !props.disabled && Boolean(props.answer)),
  queryFn: () => read<AiEvaluationAccess>(`${path.value}/access?answerId=${props.answer!.id}`),
})
const settings = useQuery({
  queryKey: computed(() => ['ai', store.sessionEpoch, 'settings']),
  enabled: computed(() => readable.value && !props.disabled && allowed.value && !access.restricted),
  queryFn: () => read<AiSettings>('/ai/settings'),
})
const tasks = useQuery({
  queryKey: computed(() => ['training-evaluations', store.sessionEpoch, props.session.id, props.questionId, cursor.value]),
  enabled: computed(() => readable.value && !props.disabled),
  queryFn: () => read<AiEvaluationPage>(`/training/sessions/${props.session.id}/evaluations?questionId=${props.questionId}&limit=20${cursor.value ? `&cursor=${encodeURIComponent(cursor.value)}` : ''}`),
})
const ready = computed(() => Boolean(settings.data.value?.configuration.enabled && settings.data.value.configuration.configured))
const pendingForAnswer = computed(() => tasks.data.value?.items.some(item => item.answerId === props.answer?.id && aiPendingStatuses.includes(item.task.status)) ?? false)

watch([dirty, locked], ([changed, waiting]) => emit('state', { dirty: changed, locked: waiting }), { immediate: true, flush: 'sync' })
watch(accessQuery.data, () => {
  const value = queryClient.getQueryData<AiEvaluationAccess>(accessKey())
  if (value && readable.value && !props.disabled && value.answerId === props.answer?.id) currentAccess.value = value
}, { immediate: true })
watch([() => props.answer?.id, () => store.sessionEpoch], () => {
  currentAccess.value = undefined
  accessConfirmed.value = false
  earlyConfirmed.value = false
  createdId.value = ''
  clearPreview()
}, { flush: 'sync' })
watch([() => props.answer?.id, () => store.sessionEpoch, readable, () => props.disabled], () => {
  accessSequence++
  if (readable.value && !props.disabled && props.answer) void restoreAccess()
}, { immediate: true, flush: 'post' })
watch(() => props.session.revision, () => { accessConfirmed.value = false; earlyConfirmed.value = false })
watch([readable, () => props.disabled], ([canRead, disabled]) => {
  clearPreview()
  accessConfirmed.value = false
  earlyConfirmed.value = false
  if (canRead && !disabled && props.answer) void accessQuery.refetch()
}, { flush: 'sync' })
watch(writer.error, reason => {
  if (!(reason instanceof ApiError) || writer.pending.value) return
  accessConfirmed.value = false
  earlyConfirmed.value = false
  clearPreview()
  if (['REVISION_CONFLICT', 'AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED'].includes(reason.code)) emit('changed')
  void accessQuery.refetch()
  void tasks.refetch()
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false; clearPreview() })
onUnmounted(() => { alive = false; previewSequence++; emit('state', { dirty: false, locked: false }) })

async function restoreAccess() {
  const answerId = props.answer?.id
  if (!answerId) return
  const epoch = store.sessionEpoch
  const sequence = ++accessSequence
  try {
    const value = await queryClient.fetchQuery({ queryKey: accessKey(), staleTime: 0,
      queryFn: () => read<AiEvaluationAccess>(`${path.value}/access?answerId=${answerId}`) })
    // 缓存结构未变化也须恢复当前答案的确认状态，不能只等待 data watcher。
    if (alive && readable.value && !props.disabled && epoch === store.sessionEpoch && sequence === accessSequence
        && answerId === props.answer?.id && value.answerId === answerId) currentAccess.value = value
  } catch { /* 查询状态统一显示错误，保留原键和用户输入。 */ }
}

function clearPreview() { previewSequence++; previewing.value = false; manifest.value = undefined; consent.value = false; previewError.value = '' }
function run<T>(target: string, body: unknown, validate: (value: T) => void, apply: (value: T) => void) {
  if (blocked.value) return
  const answerId = props.answer?.id
  commandEpoch = store.sessionEpoch
  message.value = ''
  void writer.run<T>(target, 'POST', body, value => {
    if (!alive) return
    // 校验先于页面条件判断，迟到的不匹配回执也要保留原请求恢复入口。
    validate(value)
    const receive = () => {
      // 回执始终属于原请求；答案切换后只恢复状态，不把清单套到另一次提交上。
      if (answerId !== props.answer?.id) { message.value = '原请求已由服务器确认，请在本题任务历史中查看对应提交。'; emit('changed'); void tasks.refetch(); return }
      apply(value)
    }
    if (commandEpoch !== store.sessionEpoch || !store.sessionActive || props.disabled || !readable.value) deferred.value = receive
    else receive()
  })
}
function grantAccess() {
  if (!props.answer || blocked.value || !accessConfirmed.value || (early.value && !earlyConfirmed.value)) return
  const answerId = props.answer.id
  run<AiEvaluationAccess>(`${path.value}/access`, { answerId, expectedSessionRevision: props.session.revision, confirmed: true, earlyFeedbackConfirmed: earlyConfirmed.value }, value => {
    if (value.sessionId !== props.session.id || value.questionId !== props.questionId || value.answerId !== answerId || !value.allowed) {
      throw new ApiError(502, 'AI_EVALUATION_ACCESS_MISMATCH', '评价访问回执与当前提交不一致，请用原请求重试。')
    }
  }, value => {
    currentAccess.value = value
    queryClient.setQueryData(accessKey(), value)
    accessConfirmed.value = false
    earlyConfirmed.value = false
    message.value = '已记录打开此提交的评价区；尚未授权任何模型请求。'
    // 访问确认可能改变训练修订及辅助条件，后续提交必须先看到新的会话状态。
    emit('changed')
    notifyTrainingChanged()
  })
}
async function prepare() {
  if (blocked.value || !props.answer || !allowed.value || !ready.value || pendingForAnswer.value) return
  const answerId = props.answer.id
  const epoch = store.sessionEpoch
  clearPreview()
  const request = ++previewSequence
  previewing.value = true
  try {
    const value = await previewRequest<AiPreview>(`${path.value}/preview`, { answerId })
    if (!alive || !readable.value || props.disabled || request !== previewSequence || epoch !== store.sessionEpoch || answerId !== props.answer?.id) return
    const input = value.manifest.evaluation
    if (value.manifest.purpose !== 'ANSWER_EVALUATION' || value.manifest.trainingDraft != null || value.manifest.expressionChecks != null
      || !Array.isArray(value.manifest.sources) || value.manifest.sources.length !== 0 || !aiKnowledgeMaterialChecksMatch(value.manifest)
      || value.manifest.relationAiFeedback != null || !input || input.sessionId !== props.session.id || input.questionId !== props.questionId || input.answerId !== answerId) {
      previewError.value = '服务器预览与所选确切提交不一致，请重新读取。'
      return
    }
    manifest.value = value
  } catch (reason) { if (alive && request === previewSequence && epoch === store.sessionEpoch) previewError.value = errorText(reason) }
  finally { if (alive && request === previewSequence) previewing.value = false }
}
function create() {
  if (blocked.value || !props.answer || !allowed.value || !manifest.value || manifest.value.manifest.expressionChecks != null
    || manifest.value.manifest.sources.length !== 0 || !aiKnowledgeMaterialChecksMatch(manifest.value.manifest)
    || manifest.value.manifest.relationAiFeedback != null || !consent.value || !ready.value || pendingForAnswer.value) return
  const answerId = props.answer.id
  run<AiTask>(path.value, { answerId, expectedManifestHash: manifest.value.manifestHash, consentConfirmed: true, ...aiAuthorizationRequestFields(authorization.value) }, value => {
    if (value.task.purpose !== 'ANSWER_EVALUATION' || value.manifest.purpose !== 'ANSWER_EVALUATION'
      || value.manifest.expressionChecks != null || value.manifest.relationAiFeedback != null || !Array.isArray(value.manifest.sources)
      || value.manifest.sources.length !== 0 || !aiKnowledgeMaterialChecksMatch(value.manifest) || value.evaluation?.answerId !== answerId
      || value.evaluation.questionId !== props.questionId || value.evaluation.sessionId !== props.session.id) {
      throw new ApiError(502, 'AI_EVALUATION_TASK_MISMATCH', '评价任务回执与本次提交不一致，请用原请求重试。')
    }
  }, value => {
    createdId.value = value.task.id
    clearPreview()
    message.value = '评价任务已保存。可打开任务查看报告、取消、再次授权或核对费用。'
    void tasks.refetch()
    void settings.refetch()
    emit('changed')
  })
}
function retryCommand() { if (readable.value && !props.disabled && !writer.busy.value) { commandEpoch = store.sessionEpoch; void writer.retry() } }
function receiveDeferred() { if (readable.value && !props.disabled && deferred.value) { const receive = deferred.value; deferred.value = undefined; receive() } }
function nextPage() { if (tasks.data.value?.nextCursor) { cursors.value.push(cursor.value); cursor.value = tasks.data.value.nextCursor } }
</script>

<template>
  <section class="training-evaluation" aria-label="确切提交的评价">
    <header><h4>评价与改写参考</h4><p class="field-help">{{ answer ? `当前选择第 ${answer.number} 版提交，评价与改写均绑定这次原文与结构图。` : '提交回答后，可为确切版本授权评价或改写。' }}结果保持 AI 未审层级，引用有效也不等于技术结论正确。</p></header>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!readable || !!disabled" @click="retryCommand">用原请求重试</NButton></p>
    <p v-if="deferred" class="notice" role="status">服务器已确认的结果在登录或训练条件变化后到达。<NButton text :disabled="!readable || !!disabled" @click="receiveDeferred">接收已确认结果</NButton></p>
    <template v-if="answer">
      <p v-if="accessQuery.error.value" class="notice error" role="alert">{{ errorText(accessQuery.error.value) }}<NButton text :disabled="blocked" @click="accessQuery.refetch()">重读访问状态</NButton></p>
      <div v-if="!allowed" class="access-form">
        <p class="field-help">评价区包含个人参考、评价报告与改写参考。打开时会记录这次提交的接触背景，不会自动付费调用模型，也不表示已读过某份改写。{{ assistance ? '确认后，本场之后的提交将记录辅助条件，已提交原答的条件保留。' : '访问记录保存后保留，已有提交的条件不会倒改。' }}</p>
        <NCheckbox v-model:checked="accessConfirmed" :disabled="blocked">我确认打开第 {{ answer.number }} 版提交的评价区，并记录本次接触及后续辅助条件</NCheckbox>
        <NCheckbox v-if="early" v-model:checked="earlyConfirmed" :disabled="blocked">本场约定结束后反馈，我确认提前打开评价区并保留提前反馈记录</NCheckbox>
        <NButton :disabled="blocked || !accessConfirmed || (early && !earlyConfirmed)" :loading="writer.busy.value" @click="grantAccess">确认打开评价区</NButton>
      </div>
      <div v-else class="evaluation-create">
        <p class="field-help">已于 {{ currentAccess?.grantedAt ? aiDate(currentAccess.grantedAt) : '此前' }} 确认访问{{ currentAccess?.earlyFeedback ? '，已记录提前反馈' : '' }}。预览和访问确认不会请求模型。</p>
        <RouterLink class="text-link" :to="{ name: 'review', query: { sessionId: session.id, questionId, answerId: answer.id } }">进入这次提交的自检、手工反馈与改进动作</RouterLink>
        <div class="row-actions"><RouterLink v-if="!blocked" class="text-link" :to="{ name: 'ai', query: { create: '1', purpose: 'ANSWER_REWRITE', sessionId: session.id, questionId, answerId: answer.id } }">生成或查看这次提交的改写参考</RouterLink><NButton v-else text size="small" disabled>生成或查看这次提交的改写参考</NButton></div>
        <p class="field-help">改写需单独预览和授权，与原答、个人参考并排展示；不会替换原答或计为新的训练提交。</p>
        <p v-if="settings.error.value" class="notice error" role="alert">{{ errorText(settings.error.value) }}<NButton text :disabled="blocked || access.restricted" @click="settings.refetch()">重读 AI 配置</NButton></p>
        <p v-if="!ready" class="field-help">AI 未启用、配置尚未完整或正在读取配置。已有报告与访问记录继续保留。</p>
        <p v-if="pendingForAnswer" class="notice">此提交已有未决评价任务，请先查看任务记录。服务器还会检查每个提交最多 3 个任务的限制。</p>
        <NButton :loading="previewing" :disabled="blocked || !ready || pendingForAnswer" @click="prepare">预览本次完整外发清单</NButton>
        <p v-if="previewError" class="notice error" role="alert">{{ previewError }}</p>
        <template v-if="manifest"><AiManifest :manifest="manifest.manifest" :budget="manifest.budget" /><AiAuthorizationChoice v-model:checked="consent" v-model:authorization="authorization" :manifest="manifest.manifest" :manifest-hash="manifest.manifestHash" :disabled="blocked">我已核对确切提交、个人参考、供应商和费用预留，同意本次外发生成 AI 未审报告</AiAuthorizationChoice><div class="row-actions"><NButton type="primary" :disabled="blocked || !ready || !consent || pendingForAnswer" :loading="writer.busy.value" @click="create">授权本次评价</NButton><NButton :disabled="blocked" @click="clearPreview">关闭预览</NButton></div></template>
      </div>
    </template>
    <RouterLink v-if="createdId" class="text-link" :to="{ name: 'ai', query: { item: createdId } }">打开刚创建的评价任务</RouterLink>
    <section class="evaluation-history"><header class="history-heading"><h5>本题各次提交的评价任务</h5><NButton text size="small" :disabled="!readable || !!disabled" @click="tasks.refetch()">刷新记录</NButton></header><p v-if="tasks.error.value" class="notice error" role="alert">{{ errorText(tasks.error.value) }}</p><p v-if="tasks.data.value && !tasks.data.value.items.length" class="field-help">本页没有评价任务。</p><ul><li v-for="item in tasks.data.value?.items" :key="item.task.id"><div><strong>第 {{ item.answerNumber }} 版提交{{ item.answerId === answer?.id ? ' · 当前所选' : '' }}</strong><span>{{ aiTaskStatusLabel(item.task.status, item.task.purpose) }} · {{ aiDate(item.task.createdAt) }}</span></div><RouterLink class="text-link" :to="{ name: 'ai', query: { item: item.task.id } }">报告与任务记录</RouterLink></li></ul><div v-if="cursors.length || tasks.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!readable || !!disabled || !cursors.length" @click="cursor = cursors.pop()">上一页</NButton><NButton size="small" :disabled="!readable || !!disabled || !tasks.data.value?.nextCursor" @click="nextPage">下一页</NButton></div></section>
  </section>
</template>

<style scoped>
.training-evaluation { display: grid; gap: 16px; border-top: 1px solid var(--line); padding-top: 21px; min-width: 0; }
header, .access-form, .evaluation-create, .evaluation-history { display: grid; gap: 13px; min-width: 0; } h4 { margin: 0; font-size: 16px; } h5 { margin: 0; font-size: 13px; }
.access-form { padding: 16px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 6px; } .access-form > .n-button, .evaluation-create > .n-button { justify-self: start; }
.history-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; } ul { list-style: none; margin: 0; padding: 0; } li { display: flex; gap: 12px; flex-wrap: wrap; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--line); padding: 12px 0; } li > div { display: grid; gap: 5px; } li strong { font-size: 13px; } li span { color: var(--muted); font-size: 12px; }
</style>
