<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NInputNumber } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiAdoptPreparationPlan, AiPreparationPlanContent, AiPreparationPlanDecision, AiPreparationPlanStep, AiTask } from '../../api/ai'
import { aiDate, aiPreparationPlanManifestMatches, aiPreparationPlanTextValid } from '../../api/ai'
import { validPreparationTaskDate } from '../../api/preparationTask'
import { dateKey } from '../../api/schedule'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'

type EditableStep = AiPreparationPlanStep & { key: string }
const props = defineProps<{ task: AiTask; disabled?: boolean; peerLocked?: boolean }>()
const emit = defineEmits<{ received: [task: AiTask]; refreshed: [task: AiTask]; locked: [value: boolean] }>()
const store = useWorkspaceStore()
const writer = useCommand()
const opened = ref(false)
const title = ref('')
const summary = ref('')
const steps = ref<EditableStep[]>([])
const confirmed = ref(false)
const taskRevision = ref<number>()
const taskLoading = ref(false)
const taskError = ref('')
const deferred = shallowRef<AiTask>()
const message = ref('')
const now = ref(Date.now())
const draftState = reactive({ dirty: false, locked: false })
const commandState = reactive({ dirty: false, locked: false })
let initialContent = ''
let alive = true
let taskRequest = 0
let commandEpoch = store.sessionEpoch
const clockTimer = window.setInterval(() => { now.value = Date.now() }, 60_000)

const plan = computed(() => props.task.manifest.preparationPlan ?? null)
const decision = computed(() => props.task.preparationPlanDecision ?? null)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !store.sessionActive || Boolean(props.disabled) || Boolean(props.peerLocked) || locked.value)
const editable = computed(() => !blocked.value && !decision.value)
const undecided = computed(() => props.task.task.purpose === 'PREPARATION_PLAN' && props.task.task.status === 'SUCCEEDED'
  && Boolean(props.task.preparationPlanCandidate) && !decision.value)
const sourceChecksValid = computed(() => aiPreparationPlanManifestMatches(props.task.manifest))
// 编辑行的 key 仅维持删除与排序时的输入身份，不进入正式步骤或幂等正文。
const content = computed<AiPreparationPlanContent>(() => ({ title: title.value, summary: summary.value,
  steps: steps.value.map(step => ({ date: step.date, title: step.title, minutes: step.minutes, detail: step.detail })) }))
const contentSignature = computed(() => JSON.stringify(content.value))
const dirty = computed(() => opened.value && (contentSignature.value !== initialContent || confirmed.value))
const taskStale = computed(() => taskRevision.value !== props.task.task.revision)
const totalMinutes = computed(() => steps.value.every(step => validMinutes(step.minutes))
  ? steps.value.reduce((sum, step) => sum + step.minutes, 0) : null)
const remainingMinutes = computed(() => plan.value && totalMinutes.value !== null ? plan.value.totalMinutes - totalMinutes.value : null)
const dailyMinutes = computed(() => {
  if (totalMinutes.value === null || steps.value.some(step => !validPreparationTaskDate(step.date))) return []
  const totals = new Map<string, number>()
  for (const step of steps.value) totals.set(step.date, (totals.get(step.date) ?? 0) + step.minutes)
  return [...totals].sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0).map(([date, minutes]) => ({ date, minutes }))
})
const sorted = computed(() => steps.value.every((step, index) => index === 0 || step.date >= steps.value[index - 1]!.date))
const sortable = computed(() => steps.value.length > 1 && steps.value.every(step => validPreparationTaskDate(step.date)))
const today = computed(() => {
  // 只在授权时区判断过去日期；步骤本身仍使用原日历字符串，绝不按浏览器时区换日。
  if (!plan.value?.zoneId) return ''
  try { return dateKey(now.value, plan.value.zoneId) } catch { return '' }
})
const pastStepCount = computed(() => today.value ? steps.value.filter(step => validPreparationTaskDate(step.date) && step.date < today.value).length : 0)
const problem = computed(() => {
  if (!plan.value || !sourceChecksValid.value) return '准备建议的授权清单不完整，请重新核对任务。'
  if (!title.value.trim() || title.value.length > 200) return '安排标题需为 1–200 个 UTF-16 码元。'
  if (summary.value.length > 2000) return '安排摘要最多 2000 个 UTF-16 码元。'
  if (!aiPreparationPlanTextValid(title.value, 200) || !aiPreparationPlanTextValid(summary.value, 2000)) return '标题和摘要不能包含 NUL 或不完整字符。'
  if (steps.value.length < 1 || steps.value.length > 20) return '请保留 1–20 个准备步骤。'
  for (let index = 0; index < steps.value.length; index++) {
    const step = steps.value[index]!
    if (!validPreparationTaskDate(step.date) || step.date < plan.value.startDate || step.date > plan.value.endDate) return `第 ${index + 1} 步需填写原授权区间内的有效日期，包含开始日与结束日。`
    if (!step.title.trim() || step.title.length > 200) return `第 ${index + 1} 步标题需为 1–200 个 UTF-16 码元。`
    if (step.detail.length > 1000) return `第 ${index + 1} 步说明最多 1000 个 UTF-16 码元。`
    if (!aiPreparationPlanTextValid(step.title, 200) || !aiPreparationPlanTextValid(step.detail, 1000)) return `第 ${index + 1} 步包含 NUL 或不完整字符。`
    if (!validMinutes(step.minutes)) return `第 ${index + 1} 步工作量需为 1–1440 的整数分钟。`
  }
  if (!sorted.value) return '步骤须按日期先后排列；同一天可包含多个步骤。可使用“按日期排序”调整顺序。'
  const exceeded = dailyMinutes.value.find(day => day.minutes > 1440)
  if (exceeded) return `${exceeded.date} 的工作量合计 ${exceeded.minutes} 分钟，超过单日 1440 分钟上限。`
  if (remainingMinutes.value !== null && remainingMinutes.value < 0) return `当前安排超过原总预算 ${-remainingMinutes.value} 分钟，请删减或调整工作量。`
  return ''
})
const canAdopt = computed(() => opened.value && !blocked.value && !taskLoading.value && undecided.value
  && props.task.sourcesCurrent && sourceChecksValid.value && !taskStale.value && !problem.value)
const decisionComplete = computed(() => !decision.value || validDecision(decision.value))
const savedSteps = computed(() => {
  const saved = decision.value
  if (!saved?.content || !Array.isArray(saved.content.steps)) return []
  return saved.content.steps.map((step, index) => {
    const links = Array.isArray(saved.tasks) ? saved.tasks.filter(link => link.step === index + 1) : []
    return { ...step, number: index + 1, taskId: decisionComplete.value && links.length === 1 ? links[0]!.taskId : undefined }
  })
})

watch([dirty, locked], ([changed, waiting]) => {
  draftState.dirty = changed
  commandState.locked = waiting
  store.changes.set('ai-adoption', draftState)
  store.changes.set('ai-adoption-command', commandState)
  emit('locked', waiting)
}, { immediate: true, flush: 'sync' })
watch([contentSignature, taskRevision, () => props.task.task.revision, () => props.task.sourcesCurrent, sourceChecksValid, decision, today], () => { confirmed.value = false }, { flush: 'sync' })
watch([() => store.sessionEpoch, () => store.sessionActive, () => props.disabled], () => {
  cancelRead()
  taskRevision.value = undefined
  confirmed.value = false
}, { flush: 'sync' })
watch(() => props.peerLocked, value => { if (value) { cancelRead(); confirmed.value = false } }, { flush: 'sync' })
watch(writer.error, error => {
  if (!(error instanceof ApiError) || writer.pending.value) return
  confirmed.value = false
  if (['REVISION_CONFLICT', 'AI_STATE_CONFLICT', 'AI_CANDIDATE_DECIDED', 'AI_SOURCE_CHANGED', 'SOURCE_CHANGED', 'SOURCE_UNAVAILABLE'].includes(error.code)) taskRevision.value = undefined
})
onUnmounted(() => {
  alive = false
  cancelRead()
  window.clearInterval(clockTimer)
  if (store.changes.get('ai-adoption') === draftState) store.changes.delete('ai-adoption')
  if (store.changes.get('ai-adoption-command') === commandState) store.changes.delete('ai-adoption-command')
  emit('locked', false)
})

function validMinutes(value: number): boolean { return Number.isInteger(value) && value >= 1 && value <= 1440 }
function validDecision(value: AiPreparationPlanDecision): boolean {
  if (!Array.isArray(value.tasks)) return false
  if (value.status === 'IGNORED') return value.content == null && value.tasks.length === 0
  if (value.status !== 'ADOPTED' || !value.content || !Array.isArray(value.content.steps) || !value.content.steps.length || value.content.steps.length > 20
    || value.tasks.length !== value.content.steps.length || new Set(value.tasks.map(link => link.taskId)).size !== value.tasks.length) return false
  return value.content.steps.every((_, index) => value.tasks.filter(link => link.step === index + 1 && Boolean(link.taskId)).length === 1)
}
function cancelRead() { taskRequest++; taskLoading.value = false }
function open() {
  if (blocked.value || !undecided.value || opened.value || !props.task.preparationPlanCandidate) return
  const candidate = props.task.preparationPlanCandidate
  title.value = candidate.title
  summary.value = candidate.summary
  steps.value = candidate.steps.map(step => ({ ...step, key: crypto.randomUUID() }))
  initialContent = JSON.stringify(content.value)
  taskRevision.value = props.task.task.revision
  confirmed.value = false
  taskError.value = ''
  message.value = ''
  now.value = Date.now()
  opened.value = true
}
function close() {
  if (blocked.value || !store.canLeave(['ai-adoption', 'ai-adoption-command'])) return
  cancelRead()
  opened.value = false
  confirmed.value = false
}
function addStep() {
  if (!editable.value || !opened.value || !plan.value || steps.value.length >= 20) return
  const lastDate = steps.value.at(-1)?.date ?? ''
  const date = validPreparationTaskDate(lastDate) && lastDate >= plan.value.startDate && lastDate <= plan.value.endDate ? lastDate : plan.value.startDate
  steps.value.push({ key: crypto.randomUUID(), date, title: '', minutes: Math.min(30, Math.max(1, remainingMinutes.value ?? 30)), detail: '' })
}
function removeStep(index: number) {
  if (!editable.value || !opened.value || steps.value.length <= 1 || index < 0 || index >= steps.value.length) return
  steps.value.splice(index, 1)
}
function sortSteps() {
  if (!editable.value || !opened.value || !sortable.value) return
  steps.value = [...steps.value].sort((left, right) => left.date < right.date ? -1 : left.date > right.date ? 1 : 0)
}
async function reviewTask() {
  if (blocked.value || taskLoading.value) return
  const epoch = store.sessionEpoch
  const request = ++taskRequest
  taskLoading.value = true
  taskError.value = ''
  confirmed.value = false
  try {
    const value = await read<AiTask>(`/ai/tasks/${props.task.task.id}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || request !== taskRequest) return
    if (value.task.id !== props.task.task.id || value.task.purpose !== 'PREPARATION_PLAN' || value.task.revision < props.task.task.revision) {
      taskError.value = '任务状态已继续变化，请重新核对。'
      return
    }
    emit('refreshed', value)
    taskRevision.value = value.task.revision
    now.value = Date.now()
    message.value = '已重新核对任务及来源状态，你编辑的安排继续保留。'
  } catch (error) {
    if (alive && request === taskRequest && epoch === store.sessionEpoch) taskError.value = errorText(error)
  } finally { if (alive && request === taskRequest) taskLoading.value = false }
}
function receive(value: AiTask) {
  if (value.task.id !== props.task.task.id || value.task.purpose !== 'PREPARATION_PLAN'
    || !value.preparationPlanDecision || !validDecision(value.preparationPlanDecision)) {
    taskRevision.value = undefined
    taskError.value = '服务器回执缺少对应的准备建议决定或完整任务映射，请重新核对任务。本地安排继续保留。'
    return
  }
  opened.value = false
  confirmed.value = false
  cancelRead()
  message.value = value.preparationPlanDecision.status === 'ADOPTED'
    ? `已保存最终安排并创建 ${value.preparationPlanDecision.tasks.length} 个普通准备任务，可逐项打开并开始。`
    : '已忽略这份准备建议，原候选和费用记录继续保留。'
  emit('received', value)
  void queryClient.invalidateQueries({ queryKey: ['preparation-tasks', store.sessionEpoch] })
}
function run(action: 'adopt-preparation-plan' | 'ignore', payload: AiAdoptPreparationPlan | { expectedRevision: number }) {
  if (blocked.value || taskLoading.value) return
  commandEpoch = store.sessionEpoch
  message.value = ''
  taskError.value = ''
  const taskId = props.task.task.id
  const adoption = action === 'adopt-preparation-plan' ? payload as AiAdoptPreparationPlan : undefined
  void writer.run<AiTask>(`/ai/tasks/${taskId}/${action}`, 'POST', payload, value => {
    if (!alive) return
    const saved = value?.preparationPlanDecision
    // 服务器原样保存最终安排；逐字段对照可保留空白、顺序和日期，同时不依赖 JSON 键顺序。
    const decisionMatches = saved && validDecision(saved) && (adoption
      ? saved.status === 'ADOPTED' && saved.content != null
        && saved.content.title === adoption.content.title && saved.content.summary === adoption.content.summary
        && saved.content.steps.length === adoption.content.steps.length
        && saved.content.steps.every((step, index) => {
          const issued = adoption.content.steps[index]!
          return step.date === issued.date && step.title === issued.title && step.minutes === issued.minutes && step.detail === issued.detail
        })
      : saved.status === 'IGNORED')
    if (value?.task?.id !== taskId || value.task.purpose !== 'PREPARATION_PLAN' || !decisionMatches) {
      taskRevision.value = undefined
      throw new ApiError(502, 'AI_TASK_MISMATCH', '准备安排回执与本次决定、最终内容或任务映射不一致，本地安排和原请求继续保留，请用原请求重试。')
    }
    if (commandEpoch !== store.sessionEpoch || !store.sessionActive || props.disabled || props.peerLocked) deferred.value = value
    else receive(value)
  })
}
function adopt() {
  if (!canAdopt.value || !confirmed.value || taskRevision.value === undefined) return
  // 保存用户明确审阅的原文字段和原日历日期；不在提交时裁剪、排序或顺延。
  run('adopt-preparation-plan', { expectedRevision: taskRevision.value, content: content.value, confirmed: true })
}
function ignore() {
  if (blocked.value || taskLoading.value || !undecided.value) return
  if (!window.confirm(`忽略后，这项任务不能再采用；原建议和费用记录保留。${dirty.value ? '当前未保存的改写也会放弃。' : ''}确认忽略这份准备建议？`)) return
  run('ignore', { expectedRevision: props.task.task.revision })
}
function retryCommand() {
  if (!store.sessionActive || props.disabled || props.peerLocked || writer.busy.value) return
  commandEpoch = store.sessionEpoch
  void writer.retry()
}
function receiveDeferred() {
  if (!store.sessionActive || props.disabled || props.peerLocked || !deferred.value) return
  const value = deferred.value
  deferred.value = undefined
  receive(value)
}
</script>

<template>
  <section class="plan-adoption" aria-label="审阅与采用准备建议">
    <header><h3>审阅并创建准备任务</h3><p class="field-help">确认后，按最终步骤创建普通准备任务，由你逐项开始和完成。采用无需再次调用模型，AI 停用后仍可采用来源有效的建议。</p></header>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :loading="writer.busy.value" :disabled="!store.sessionActive || !!disabled || !!peerLocked" @click="retryCommand">用原请求重试</NButton><NButton v-else text :disabled="blocked || taskLoading" @click="reviewTask">核对最新任务状态</NButton></p>
    <p v-if="deferred" class="notice" role="status">准备建议的决定已由服务器确认，返回时登录或资料条件发生了变化。<NButton text :disabled="!store.sessionActive || !!disabled || !!peerLocked" @click="receiveDeferred">接收已确认决定</NButton></p>
    <p v-if="taskError" class="notice error" role="alert">{{ taskError }}</p>

    <section v-if="decision" class="decision-record" aria-label="准备建议采用记录">
      <header><strong>{{ decision.status === 'ADOPTED' ? '这份准备建议已采用' : '这份准备建议已忽略' }}</strong><span class="field-help">{{ aiDate(decision.createdAt) }}</span></header>
      <p class="field-help">每项 AI 任务只能决定一次，原模型建议和费用记录继续保留。</p>
      <p v-if="!decisionComplete" class="notice error" role="alert">采用快照或步骤任务映射不完整，请重新核对。<NButton text :disabled="blocked || taskLoading" @click="reviewTask">重新读取任务</NButton></p>
      <template v-if="decision.status === 'ADOPTED' && decision.content">
        <h4>{{ decision.content.title }}</h4><p v-if="decision.content.summary" class="preserve">{{ decision.content.summary }}</p>
        <p class="field-help">以下是采用时的最终快照，日期按 {{ plan?.zoneId ?? '当时授权时区' }} 记录。新任务后续的内容与状态独立变化，不会倒改此处。采用不自动加入今日计划、修改已有任务、关闭改进行动或证明已掌握。</p>
        <ol class="saved-steps"><li v-for="step in savedSteps" :key="step.number"><div class="step-heading"><strong>{{ step.number }}. {{ step.title }}</strong><span>{{ step.date }} · {{ step.minutes }} 分钟</span></div><p v-if="step.detail" class="preserve">{{ step.detail }}</p><RouterLink v-if="step.taskId" class="text-link" :to="{ name: 'preparation-tasks', query: { item: step.taskId } }">打开第 {{ step.number }} 步对应任务</RouterLink><p v-else class="notice">该步骤暂未取得唯一的任务链接。</p></li></ol>
      </template>
      <p v-if="opened" class="notice">这项建议已有决定，你的本地改写仍保留供查看；关闭前会提示未保存修改。</p>
    </section>

    <p v-if="undecided && !task.sourcesCurrent" class="notice">原目标或使用条件当前已变化，不能采用这份建议。你编辑的安排继续保留，仍可忽略原候选，或回到入口选择当前目标重新生成。</p>
    <p v-if="undecided && !sourceChecksValid" class="notice">目标来源或准备条件清单不完整，暂不能采用。<NButton text :disabled="blocked || taskLoading" @click="reviewTask">重新读取任务</NButton></p>
    <div class="row-actions"><NButton v-if="undecided && !opened" type="primary" :disabled="blocked" @click="open">审阅并调整安排</NButton><NButton v-if="undecided" :disabled="blocked || taskLoading" @click="ignore">忽略准备建议</NButton></div>

    <div v-if="opened" class="adoption-form">
      <p class="field-help">上方保留模型原建议。下面的改写仅保存在当前页面，确认采用后才创建任务；可保留 1–20 步，日期和总工作量须保持在原授权条件内。</p>
      <section v-if="plan" class="workload-panel" aria-label="准备工作量预算">
        <p class="field-help">{{ plan.startDate }} 至 {{ plan.endDate }}，包含首尾 · {{ plan.zoneId }}</p>
        <dl class="workload-totals"><div><dt>原工作量预算</dt><dd>{{ plan.totalMinutes }} 分钟</dd></div><div><dt>当前安排</dt><dd>{{ totalMinutes === null ? '分钟待核对' : `${totalMinutes} 分钟` }}</dd></div><div><dt>{{ remainingMinutes !== null && remainingMinutes < 0 ? '超出预算' : '剩余预算' }}</dt><dd :class="{ exceeded: remainingMinutes !== null && remainingMinutes < 0 }">{{ remainingMinutes === null ? '待核对' : `${Math.abs(remainingMinutes)} 分钟` }}</dd></div></dl>
        <p class="field-help">分钟表示预计工作量，不代表日历占用、夏令时当天实际时长或已完成学习；已有任务与日程没有纳入本次冲突检查。</p>
        <ul v-if="dailyMinutes.length" class="daily-workload"><li v-for="day in dailyMinutes" :key="day.date" :class="{ exceeded: day.minutes > 1440 }"><span>{{ day.date }}</span><span>{{ day.minutes }} / 1440 分钟{{ day.minutes > 1440 ? ' · 超出单日上限' : '' }}</span></li></ul>
        <p v-if="today" class="field-help">按 {{ plan.zoneId }} 核对日期：{{ today }}。允许保留过去日期，不会自动顺延。</p><p v-else class="notice">当前浏览器不能核对授权时区的今天；日期仍保持原值，请自行核对是否已过期。</p>
        <p v-if="pastStepCount" class="notice">有 {{ pastStepCount }} 个步骤早于 {{ today }}，仍会按你确认的日期创建。请核对这些安排是否需要在原区间内调整。</p>
      </section>

      <label :for="`plan-title-${task.task.id}`">最终安排标题<NInput :id="`plan-title-${task.task.id}`" v-model:value="title" :disabled="!editable" /><small>{{ title.length }} / 200 UTF-16 码元</small></label>
      <label :for="`plan-summary-${task.task.id}`">最终摘要（可留空）<NInput :id="`plan-summary-${task.task.id}`" v-model:value="summary" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="!editable" /><small>{{ summary.length }} / 2000 UTF-16 码元</small></label>
      <header class="steps-heading"><h4>最终步骤 · {{ steps.length }} / 20</h4><NButton size="small" :disabled="!editable || !sortable || sorted" @click="sortSteps">按日期排序</NButton></header>
      <section v-for="(step, index) in steps" :key="step.key" class="step-editor" :aria-label="`第 ${index + 1} 步`">
        <header class="step-heading"><h4>第 {{ index + 1 }} 步</h4><NButton text size="small" :disabled="!editable || steps.length <= 1" @click="removeStep(index)">移除此步骤</NButton></header>
        <label :for="`plan-step-title-${step.key}`">步骤标题<NInput :id="`plan-step-title-${step.key}`" v-model:value="step.title" :disabled="!editable" /><small>{{ step.title.length }} / 200 UTF-16 码元</small></label>
        <div class="step-pair"><label :for="`plan-step-date-${step.key}`">计划日期<input :id="`plan-step-date-${step.key}`" type="date" :value="step.date" :min="plan?.startDate ?? '1000-01-01'" :max="plan?.endDate ?? '9999-12-31'" :disabled="!editable" @input="step.date = ($event.target as HTMLInputElement).value" /></label><label :for="`plan-step-minutes-${step.key}`">预计工作量（分钟）<NInputNumber :id="`plan-step-minutes-${step.key}`" :value="step.minutes" :disabled="!editable" :show-button="false" @update:value="value => step.minutes = value ?? 0" /></label></div>
        <label :for="`plan-step-detail-${step.key}`">具体准备事项（可留空）<NInput :id="`plan-step-detail-${step.key}`" v-model:value="step.detail" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="!editable" /><small>{{ step.detail.length }} / 1000 UTF-16 码元</small></label>
      </section>
      <div class="row-actions"><NButton :disabled="!editable || !plan || steps.length >= 20" @click="addStep">增加准备步骤</NButton></div>
      <p v-if="problem" class="notice" role="status">{{ problem }}</p>
      <p v-if="taskStale && !decision" class="notice">任务或访问条件已变化，本地安排继续保留。<NButton text :loading="taskLoading" :disabled="blocked || taskLoading" @click="reviewTask">重新核对任务与来源</NButton></p>
      <NCheckbox v-model:checked="confirmed" :disabled="!canAdopt">我已核对所有步骤的内容、日期与工作量，确认创建 {{ steps.length }} 个新的普通准备任务，并由我后续逐项开始和完成。</NCheckbox>
      <div class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!canAdopt || !confirmed" @click="adopt">确认采用并创建任务</NButton><NButton :disabled="blocked" @click="close">关闭安排编辑</NButton></div>
    </div>
  </section>
</template>

<style scoped>
.plan-adoption { display: grid; gap: 18px; padding-top: 21px; border-top: 1px solid var(--line); min-width: 0; }
header, .decision-record, .adoption-form, .workload-panel, .step-editor { display: grid; gap: 14px; min-width: 0; }
h3 { margin: 0; font-size: 17px; } h4 { margin: 0; font-size: 15px; overflow-wrap: anywhere; }
.decision-record, .workload-panel { padding: 16px; background: var(--surface-soft); border-radius: 7px; }
.adoption-form { gap: 20px; }
label { display: grid; gap: 8px; min-width: 0; font-size: 13px; color: var(--muted); } label small { font-size: 11px; }
.step-editor { padding: 17px; border: 1px solid var(--line); border-radius: 7px; }
.step-heading, .steps-heading { display: flex; justify-content: space-between; align-items: start; gap: 12px; flex-wrap: wrap; }
.step-heading > span { font-size: 12px; color: var(--muted); }
.step-pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
input[type=date] { width: 100%; min-height: 34px; padding: 5px 10px; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: 4px; }
.preserve { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.75; }
.saved-steps { display: grid; gap: 14px; margin: 0; padding: 0; list-style: none; }
.saved-steps > li { display: grid; gap: 9px; padding-top: 13px; border-top: 1px solid var(--line); min-width: 0; }
.saved-steps .text-link { justify-self: start; font-size: 12px; }
.workload-totals { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 15px; margin: 0; }
dt { font-size: 12px; color: var(--muted); } dd { margin: 6px 0 0; font-size: 15px; overflow-wrap: anywhere; }
.daily-workload { display: grid; gap: 7px; list-style: none; padding: 0; margin: 0; font-size: 12px; }
.daily-workload li { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.exceeded { color: var(--warm); }
@media (max-width: 600px) { .step-pair, .workload-totals { grid-template-columns: 1fr; } .step-editor, .workload-panel, .decision-record { padding: 13px; } }
</style>
