<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { RouteLocationRaw } from 'vue-router'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { ItemDetail } from '../api/client'
import type { KnowledgeRevalidationChoice, KnowledgeRevalidationResolve, KnowledgeRevalidationState, KnowledgeRevalidationTarget } from '../api/knowledgeRevalidation'
import { knowledgeRevalidationChoiceLabels, knowledgeRevalidationStateLabels, knowledgeRevalidationTarget, knowledgeRevalidationTargetKey,
  revalidationIdValid, revalidationReasonValid, revalidationStateValid } from '../api/knowledgeRevalidation'
import type { PreparationKnowledgeContext, PreparationKnowledgeImpact, PreparationKnowledgePage, PreparationKnowledgeSource } from '../api/preparationKnowledge'
import { preparationKnowledgeChangeLabels, preparationKnowledgeContextValid, preparationKnowledgeDependencyLabels,
  preparationKnowledgePageValid, preparationKnowledgePaths, preparationKnowledgeReceiptMatches, preparationKnowledgeResolveValid,
  preparationKnowledgeSameImpact, preparationKnowledgeTraceValid } from '../api/preparationKnowledge'
import { kindLabels, verificationLabels } from '../api/preparation'
import type { PreparationKind } from '../api/preparation'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

const props = withDefaults(defineProps<{ itemId: string; context: PreparationKnowledgeContext; current?: ItemDetail;
  editing?: boolean; disabled?: boolean; visible?: boolean }>(), { visible: true })
const emit = defineEmits<{ changed: []; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter(), writer = useCommand(), viewId = useId()
const boundId = props.itemId, boundContext = props.context
const stateKey = `preparation-knowledge:${viewId}`, panelId = `preparation-knowledge-${viewId}`
const opened = ref(false), documentVisible = ref(document.visibilityState === 'visible')
const state = ref<KnowledgeRevalidationState>('PENDING'), page = shallowRef<PreparationKnowledgePage>()
const cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const loading = ref(false), problem = ref(''), paused = ref(false), message = ref('')
const navigationProblem = ref(''), opening = ref(false)
const traceAnchor = shallowRef<PreparationKnowledgeImpact>(), trace = shallowRef<PreparationKnowledgeImpact[]>([])
const traceLoading = ref(false), traceProblem = ref('')
const selected = shallowRef<PreparationKnowledgeImpact>(), selectedGeneration = ref(-1)
const baseline = shallowRef<KnowledgeRevalidationTarget>(), binding = ref(false), basisProblem = ref('')
const reason = ref(''), choice = ref<KnowledgeRevalidationChoice>('KEEP_CURRENT'), confirmed = ref(false)
const identityValid = computed(() => revalidationIdValid(boundId) && props.itemId === boundId
  && preparationKnowledgeContextValid(boundContext) && props.context === boundContext)
const readable = computed(() => identityValid.value && props.visible && documentVisible.value && store.sessionActive
  && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const target = computed(() => boundContext === 'KNOWLEDGE' ? knowledgeRevalidationTarget(props.current, boundId) : undefined)
const locked = computed(() => writer.locked.value || binding.value)
const dirty = computed(() => Boolean(selected.value || reason.value || confirmed.value))
const changes = reactive({ dirty: false, locked: false })
const blocked = computed(() => !readable.value || props.disabled || props.editing || locked.value || loading.value)
const selectedReady = computed(() => boundContext === 'KNOWLEDGE' && selected.value?.targetItemId === boundId
  && selected.value.revision === 0 && selected.value.resolution === null && selectedGeneration.value === generation)
const targetReady = computed(() => Boolean(target.value && baseline.value && !target.value.unpublished
  && knowledgeRevalidationTargetKey(target.value) === knowledgeRevalidationTargetKey(baseline.value)))
const stateOptions = Object.entries(knowledgeRevalidationStateLabels).map(([value, label]) => ({ value, label }))
const choiceOptions = computed(() => Object.entries(knowledgeRevalidationChoiceLabels).map(([value, label]) => ({
  value, label, disabled: value === 'ARCHIVED' ? target.value?.status !== 'ARCHIVED'
    : target.value?.status !== 'ACTIVE' || value === 'PUBLISHED_UPDATE'
      && (!target.value || !selected.value || target.value.versionNumber <= selected.value.targetVersionNumber),
})))
const request = computed(() => ({ expectedRevision: selected.value?.revision, expectedTargetRevision: baseline.value?.revision,
  expectedTargetVersionId: baseline.value?.versionId, choice: choice.value, reason: reason.value, confirmed: confirmed.value }))
const inputProblem = computed(() => !selectedReady.value ? '请刷新目录并重新核对原影响。'
  : props.disabled || props.editing ? '请先处理当前编辑或其他保存，再核对本次影响。'
    : !target.value ? '当前知识发布版无法核对，请重新读取。'
      : target.value.unpublished ? '存在未发布草稿，请先发布或处理草稿。'
        : !targetReady.value ? '请重新读取并核对当前确切发布版。'
          : !revalidationReasonValid(reason.value) ? '请填写 1–1000 字理由，不能只有空白或含无效字符。'
            : !confirmed.value ? '请核对本次来源影响、处理方式和当前发布版，再明确确认。'
              : selected.value && preparationKnowledgeResolveValid(request.value, selected.value, target.value) ? ''
                : '处理方式与当前目标状态或发布版本不一致。')
let alive = true, generation = 0, sequence = 0, traceSequence = 0, basisSequence = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0, commandAccessEpoch = currentTrainingAccessEpoch()
let lastRequest: { cursor?: string; previous: (string | undefined)[] } | undefined

watch([dirty, locked], ([unsaved, pending]) => {
  changes.dirty = unsaved; changes.locked = pending; store.changes.set(stateKey, changes)
  emit('state', { dirty: unsaved, locked: pending })
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.itemId, () => props.context, () => access.active?.id,
  () => access.active?.mode, () => access.active?.materialAccess], () => {
  generation++; clearPage(); invalidateBasis(); selectedGeneration.value = -1; message.value = ''; paused.value = opened.value
  // 失去访问资格只撤销展示和核对基线，理由与未决原请求仍保留供恢复。
}, { flush: 'sync' })
watch([() => knowledgeRevalidationTargetKey(target.value), () => props.editing, () => props.disabled], invalidateBasis, { flush: 'sync' })
watch([reason, choice], () => { confirmed.value = false }, { flush: 'sync' })
watch(writer.error, value => {
  if (!value) return
  confirmed.value = false
  if (!writer.pending.value) {
    selectedGeneration.value = -1; invalidateBasis()
    if (value instanceof ApiError && value.status === 409) emit('changed')
  }
})
function visibilityChanged() { documentVisible.value = document.visibilityState === 'visible' }
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onUnmounted(() => {
  alive = false; generation++; clearPage(); invalidateBasis(); document.removeEventListener('visibilitychange', visibilityChanged)
  if (store.changes.get(stateKey) === changes) store.changes.delete(stateKey)
  emit('state', { dirty: false, locked: false })
})
function invalidateBasis() {
  basisSequence++; baseline.value = undefined; confirmed.value = false; binding.value = false; basisProblem.value = ''
}
function clearTrace() { traceSequence++; traceAnchor.value = undefined; trace.value = []; traceLoading.value = false; traceProblem.value = '' }
function clearPage() {
  sequence++; clearTrace(); page.value = undefined; cursor.value = undefined; previous.value = []
  loading.value = false; problem.value = ''; navigationProblem.value = ''; lastRequest = undefined
}
function toggle() {
  if (!readable.value || locked.value || props.disabled) return
  opened.value = !opened.value
  if (!opened.value) { generation++; clearPage(); invalidateBasis(); selectedGeneration.value = -1 }
  else if (!page.value) void load()
}
function filterState(value: unknown) {
  if (!readable.value || props.disabled || locked.value || !revalidationStateValid(value) || value === state.value) return
  state.value = value; clearPage(); selectedGeneration.value = -1; invalidateBasis(); void load()
}
async function load(after?: string, stack: (string | undefined)[] = []) {
  if (!readable.value || !opened.value || props.disabled || locked.value || loading.value) return
  const epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), stamp = generation, ticket = ++sequence, filter = state.value
  lastRequest = { cursor: after, previous: [...stack] }
  clearTrace(); invalidateBasis(); page.value = undefined; loading.value = true; problem.value = ''; paused.value = false
  selectedGeneration.value = -1
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch() && stamp === generation && ticket === sequence && filter === state.value
  try {
    const value = await read<unknown>(preparationKnowledgePaths.page(boundId, boundContext, filter, after))
    if (!current()) return
    if (!preparationKnowledgePageValid(value, boundId, boundContext, filter, after)) {
      throw new ApiError(502, 'PREPARATION_KNOWLEDGE_PAGE_INVALID', '准备来源影响的身份、历史或分页无法核对，请重新读取。')
    }
    const refreshed = selected.value && value.items.find(impact => impact.id === selected.value!.id)
    if (refreshed && selected.value && !preparationKnowledgeSameImpact(refreshed, selected.value)) {
      throw new ApiError(502, 'PREPARATION_KNOWLEDGE_IMPACT_INVALID', '原影响的固定历史与目录不一致，处理说明继续保留。')
    }
    page.value = value; cursor.value = after; previous.value = [...stack]
    if (refreshed) { selected.value = refreshed; selectedGeneration.value = generation }
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (alive && ticket === sequence) loading.value = false }
}
function refresh() { void load() }
function retryPage() { if (lastRequest) void load(lastRequest.cursor, lastRequest.previous) }
function nextPage() { if (page.value?.nextCursor) void load(page.value.nextCursor, [...previous.value, cursor.value]) }
function previousPage() { if (previous.value.length) void load(previous.value.at(-1), previous.value.slice(0, -1)) }
function viewTrace(impact: PreparationKnowledgeImpact) {
  if (!readable.value || props.disabled || !opened.value || locked.value || loading.value || !page.value?.items.includes(impact)) return
  clearTrace(); traceAnchor.value = impact; void loadTrace()
}
async function closeTrace() {
  const trigger = traceAnchor.value ? `${panelId}-trace-trigger-${traceAnchor.value.id}` : undefined
  clearTrace(); await nextTick()
  if (alive && readable.value && trigger) document.getElementById(trigger)?.focus()
}
async function loadTrace() {
  const original = traceAnchor.value, directory = page.value, steps = [...trace.value], child = steps.at(-1)
  if (!readable.value || props.disabled || !opened.value || locked.value || loading.value || traceLoading.value
    || !original || !directory?.items.includes(original) || child?.parentImpactId === null) return
  const impactId = child?.parentImpactId ?? original.id, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  const stamp = generation, listTicket = sequence, ticket = ++traceSequence
  traceLoading.value = true; traceProblem.value = ''
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
    && stamp === generation && ticket === traceSequence && listTicket === sequence && directory === page.value && traceAnchor.value === original
  try {
    const value = await read<unknown>(preparationKnowledgePaths.detail(impactId))
    if (!current()) return
    if (!preparationKnowledgeTraceValid(value, original, steps)) {
      throw new ApiError(502, 'PREPARATION_KNOWLEDGE_TRACE_INVALID', '这一跳的事件、实际来源、父影响或版本范围无法核对。')
    }
    trace.value = [...steps, value]
  } catch (error) { if (current()) traceProblem.value = errorText(error) }
  finally { if (alive && ticket === traceSequence) traceLoading.value = false }
}
function clearInput() { selected.value = undefined; selectedGeneration.value = -1; invalidateBasis(); reason.value = ''; choice.value = 'KEEP_CURRENT' }
function cancel() {
  if (locked.value || !store.canLeave([stateKey])) return
  clearInput(); writer.error.value = undefined
}
function selectImpact(impact: PreparationKnowledgeImpact) {
  if (blocked.value || boundContext !== 'KNOWLEDGE' || !page.value?.items.includes(impact)
    || impact.targetItemId !== boundId || impact.revision !== 0) return
  if (selected.value?.id !== impact.id) {
    if (dirty.value && !store.canLeave([stateKey])) return
    clearInput(); choice.value = target.value?.status === 'ARCHIVED' ? 'ARCHIVED' : 'KEEP_CURRENT'
  } else if (!preparationKnowledgeSameImpact(impact, selected.value)) return
  selected.value = impact; selectedGeneration.value = generation; message.value = ''; writer.error.value = undefined
  void bindTarget()
}
async function bindTarget() {
  const impact = selected.value
  if (blocked.value || !selectedReady.value || !impact || !target.value || target.value.unpublished) return
  invalidateBasis()
  const epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), stamp = generation, ticket = ++basisSequence
  const observed = knowledgeRevalidationTargetKey(target.value)
  binding.value = true
  const current = () => alive && readable.value && opened.value && !props.disabled && !props.editing
    && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch() && stamp === generation
    && ticket === basisSequence && selected.value === impact && observed === knowledgeRevalidationTargetKey(target.value)
  try {
    const value = await read<ItemDetail>(`/knowledge/items/${encodeURIComponent(boundId)}`)
    if (!current()) return
    const latest = knowledgeRevalidationTarget(value, boundId)
    if (!latest || latest.unpublished || knowledgeRevalidationTargetKey(latest) !== observed) {
      emit('changed')
      throw new ApiError(409, 'PREPARATION_KNOWLEDGE_TARGET_CHANGED', '目标发布版或草稿已变化，请重新读取知识后再核对，理由继续保留。')
    }
    baseline.value = latest
  } catch (error) { if (current()) basisProblem.value = errorText(error) }
  finally { if (alive && ticket === basisSequence) binding.value = false }
}
function save() {
  if (blocked.value || inputProblem.value || !selected.value || !target.value
    || !preparationKnowledgeResolveValid(request.value, selected.value, target.value)) return
  const original = JSON.parse(JSON.stringify(selected.value)) as PreparationKnowledgeImpact
  const body = JSON.parse(JSON.stringify(request.value)) as KnowledgeRevalidationResolve
  commandEpoch = store.sessionEpoch; commandGeneration = generation; commandAccessEpoch = currentTrainingAccessEpoch(); message.value = ''
  void writer.run<unknown>(preparationKnowledgePaths.resolve(boundId, original.id), 'POST', body, value => {
    if (!alive || !readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation
      || commandAccessEpoch !== currentTrainingAccessEpoch() || !preparationKnowledgeReceiptMatches(value, original, body)) {
      throw new ApiError(0, 'PREPARATION_KNOWLEDGE_RECEIPT_UNCERTAIN', '处理回执或访问条件无法核对，原请求与原键已保留，请用原请求确认结果。')
    }
    clearInput(); message.value = `已登记「${knowledgeRevalidationChoiceLabels[value.resolution!.choice]}」。相关回忆卡仍须单独复核来源与恢复排程。`
    emit('changed'); void load()
  })
}
function retryCommand() {
  if (!readable.value || props.disabled || writer.busy.value || !writer.pending.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; commandAccessEpoch = currentTrainingAccessEpoch(); void writer.retry()
}
async function open(target: RouteLocationRaw) {
  if (!readable.value || props.disabled || locked.value || opening.value) return
  const epoch = store.sessionEpoch, stamp = generation
  opening.value = true; navigationProblem.value = ''
  try {
    const failure = await router.push(target)
    if (failure && alive && readable.value && epoch === store.sessionEpoch && stamp === generation) navigationProblem.value = '页面未切换，请先处理本地输入或资料访问条件。'
  } catch {
    if (alive && readable.value && epoch === store.sessionEpoch && stamp === generation) navigationProblem.value = '暂时无法打开此入口，请稍后重试。'
  } finally { opening.value = false }
}
function openPreparation(kind: PreparationKind, itemId: string, versionId: string) {
  void open({ name: 'prepare', query: { kind, item: itemId, version: versionId } })
}
function openKnowledge(itemId: string, versionId?: string) { void open({ name: 'knowledge', query: { item: itemId, version: versionId } }) }
function openSource(source: PreparationKnowledgeSource) {
  if (source.domain === 'KNOWLEDGE') openKnowledge(source.itemId, source.versionId)
  else if (source.kind !== 'KNOWLEDGE') openPreparation(source.kind, source.itemId, source.versionId)
}
function openTask(taskId: string) { void open({ name: 'ai', query: { item: taskId } }) }
function date(value: string) { return new Date(value).toLocaleString('zh-CN') }
</script>

<template>
  <section class="preparation-knowledge" aria-label="准备来源知识更正与复核">
    <header><div><h3>{{ context === 'PREPARATION' ? '本材料引起的知识影响' : '准备来源更正与复核' }}</h3><p class="field-help">{{ dirty ? '有尚未提交的处理说明。' : '核对准备材料及其下游知识的实际采用链。' }}</p></div><NButton size="small" :disabled="!readable || locked || disabled" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起准备来源影响' : '查看准备来源影响' }}</NButton></header>
    <p v-if="!identityValid" class="notice error" role="alert">对象身份已变化，请回到原对象处理保留的输入和请求。</p>
    <p v-else-if="opened && !readable" class="notice" role="status">当前查看条件未就绪，目录和旧核对资格已清除，处理说明与原请求保留。</p>
    <div v-if="opened" v-show="readable" :id="panelId" class="content" :inert="!readable || undefined" :aria-busy="loading">
      <p class="field-help">记录岗位、项目、事实或表达来源代次变化后的知识采用影响。待复核表示需要核对，不代表已确定具体错误；不会自动修改正文或恢复旧授权。</p>
      <div class="filters"><label>处理状态<NSelect :value="state" :options="stateOptions" :disabled="!readable || locked || disabled" @update:value="filterState" /></label><NButton size="small" :loading="loading" :disabled="!readable || loading || locked || disabled" @click="refresh">刷新记录</NButton></div>
      <p v-if="paused" class="notice" role="status">查看条件已变化，请主动刷新目录并重新核对。</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text size="small" :loading="writer.busy.value" :disabled="!readable || disabled || writer.busy.value" @click="retryCommand">用原请求核对</NButton><NButton v-else text size="small" :disabled="!readable || loading || disabled" @click="refresh">刷新并保留处理说明</NButton></p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}<NButton text size="small" :disabled="!readable || loading || locked || disabled" @click="retryPage">重读这一页</NButton></p>
      <p v-if="navigationProblem" class="notice error" role="alert">{{ navigationProblem }}</p>
      <p v-if="loading" class="field-help" role="status">正在读取准备来源影响元数据…</p>
      <template v-else-if="page">
        <p v-if="!page.items.length" class="field-help">本页没有已登记的{{ state === 'PENDING' ? '待处理' : '' }}影响。本目录仅覆盖已接入的实际知识采用链，不代表接入前全部历史均已核对。</p>
        <ol v-else class="impact-list">
          <li v-for="impact in page.items" :key="impact.id">
            <header><strong>{{ impact.targetTitle }} · 原采用 v{{ impact.targetVersionNumber }}</strong><span class="status-pill">{{ impact.resolution ? '已处理' : '待复核' }}</span></header>
            <p class="field-help">{{ kindLabels[impact.root.kind] }}「{{ impact.root.title }}」{{ preparationKnowledgeChangeLabels[impact.root.changeKind] }} · <time :datetime="impact.createdAt" :title="impact.createdAt">{{ date(impact.createdAt) }}</time></p>
            <p class="field-help">根版本 v{{ impact.root.before.versionNumber }} → v{{ impact.root.after.versionNumber }}；来源代次 {{ impact.root.before.sourceGeneration }} → {{ impact.root.after.sourceGeneration }}；状态 {{ impact.root.before.status === 'ACTIVE' ? '有效' : '已归档' }} → {{ impact.root.after.status === 'ACTIVE' ? '有效' : '已归档' }}<template v-if="impact.root.before.verification && impact.root.after.verification">；核实 {{ verificationLabels[impact.root.before.verification] }} → {{ verificationLabels[impact.root.after.verification] }}</template>。</p>
            <p class="field-help">第 {{ impact.depth }} 跳 · {{ preparationKnowledgeDependencyLabels[impact.source.dependencyKind] }}：{{ impact.source.title }} v{{ impact.source.versionNumber }}<template v-if="impact.source.viaFact">；途经事实「{{ impact.source.viaFact.title }}」v{{ impact.source.viaFact.versionNumber }}</template>。</p>
            <dl><dt>影响 / 修订</dt><dd>{{ impact.id }} / {{ impact.revision }}</dd><dt>根事件</dt><dd>{{ impact.eventId }}</dd><dt>实际输入版本</dt><dd>{{ impact.source.versionId }}</dd><dt>原采用版本</dt><dd>{{ impact.targetVersionId }}</dd></dl>
            <div class="row-actions"><NButton :id="`${panelId}-trace-trigger-${impact.id}`" text size="small" :disabled="!readable || locked || disabled || loading" :aria-expanded="traceAnchor?.id === impact.id" :aria-controls="`${panelId}-trace-${impact.id}`" @click="viewTrace(impact)">查看采用路径</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openPreparation(impact.root.kind, impact.root.itemId, impact.root.before.versionId)">根事件前版本</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openPreparation(impact.root.kind, impact.root.itemId, impact.root.after.versionId)">根事件后版本</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openSource(impact.source)">本跳实际输入</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openKnowledge(impact.targetItemId, impact.targetVersionId)">原采用 v{{ impact.targetVersionNumber }}</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openTask(impact.taskId)">原 AI 整理任务</NButton></div>
            <section v-if="traceAnchor?.id === impact.id" :id="`${panelId}-trace-${impact.id}`" class="lineage" :aria-labelledby="`${panelId}-trace-heading-${impact.id}`" :aria-busy="traceLoading">
              <header><h4 :id="`${panelId}-trace-heading-${impact.id}`">逐跳回看采用路径</h4><NButton text size="small" @click="closeTrace">关闭采用路径</NButton></header>
              <p class="field-help">显示一条真实最短父路径及选中的第一条匹配事实，不是全部依赖清单。查看路径不会登记处理决定。</p>
              <ol v-if="trace.length" class="lineage-list">
                <li v-for="step in trace" :key="step.id"><h4>第 {{ step.depth }} 跳 · {{ step.source.title }} v{{ step.source.versionNumber }} → {{ step.targetTitle }} v{{ step.targetVersionNumber }}</h4><p class="field-help">{{ preparationKnowledgeDependencyLabels[step.source.dependencyKind] }} · 影响 {{ step.id }}<br />父影响：{{ step.parentImpactId ?? '无（准备来源第一跳）' }}</p><div class="row-actions"><NButton text size="small" :disabled="locked || disabled || opening" @click="openSource(step.source)">本跳实际来源</NButton><NButton v-if="step.source.viaFact" text size="small" :disabled="locked || disabled || opening" @click="openPreparation('FACT', step.source.viaFact.itemId, step.source.viaFact.versionId)">途经事实 v{{ step.source.viaFact.versionNumber }}</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openKnowledge(step.targetItemId, step.targetVersionId)">本跳原采用目标</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openTask(step.taskId)">本跳原 AI 任务</NButton></div></li>
              </ol>
              <p v-if="traceLoading" class="field-help" role="status">正在读取这一跳…</p>
              <p v-if="traceProblem" class="notice error" role="alert">{{ traceProblem }}<NButton text size="small" :disabled="!readable || locked || disabled || traceLoading" @click="loadTrace">重读这一跳</NButton></p>
              <NButton v-if="trace.at(-1)?.parentImpactId" size="small" :loading="traceLoading" :disabled="!readable || locked || disabled || loading || traceLoading" @click="loadTrace">继续向源头查看</NButton><p v-else-if="trace.length" class="field-help" role="status">已到达准备来源进入知识的第一跳。</p>
            </section>
            <template v-if="impact.resolution"><p class="resolution-text">{{ knowledgeRevalidationChoiceLabels[impact.resolution.choice] }}：{{ impact.resolution.reason }}</p><p class="field-help">处理时目标修订 {{ impact.resolution.targetRevision }} · {{ date(impact.resolution.decidedAt) }}</p><NButton text size="small" :disabled="locked || disabled || opening" @click="openKnowledge(impact.targetItemId, impact.resolution.targetVersionId)">处理时的确切发布版</NButton></template>
            <div v-else class="row-actions"><NButton v-if="context === 'KNOWLEDGE'" size="small" :disabled="blocked || !target || target.unpublished" @click="selectImpact(impact)">{{ selected?.id === impact.id ? '重新核对这一影响' : '处理这一影响' }}</NButton><NButton v-else text size="small" :disabled="locked || disabled || opening" @click="openKnowledge(impact.targetItemId)">打开目标知识处理</NButton></div>
          </li>
        </ol>
        <nav v-if="previous.length || page.nextCursor" class="row-actions" aria-label="准备来源影响分页"><NButton size="small" :disabled="!previous.length || loading || locked || disabled" @click="previousPage">上一页</NButton><NButton size="small" :disabled="!page.nextCursor || loading || locked || disabled" @click="nextPage">下一页</NButton><span class="field-help">第 {{ previous.length + 1 }} 页 · 每页最多20项</span></nav>
      </template>
      <section v-if="selected" class="resolve-form" aria-label="处理准备来源知识影响">
        <header><h4>处理原采用 v{{ selected.targetVersionNumber }} 的影响</h4><NButton text size="small" :disabled="locked || disabled" @click="cancel">取消填写</NButton></header>
        <p class="field-help">影响 {{ selected.id }}。结论绑定当前发布版，原采用及原来源继续保留。</p>
        <p v-if="selected.resolution" class="notice" role="status">此影响已处理，原输入继续保留，请在全部记录中核对已保存决定。</p><p v-else-if="!selectedReady" class="notice" role="status">原影响需要重新核对。请刷新目录找到这一影响，理由继续保留。</p>
        <p v-if="target" class="field-help">编辑器当前发布 v{{ target.versionNumber }} · 元数据修订 {{ target.revision }} · 确切版本 {{ target.versionId }}</p>
        <p v-if="basisProblem" class="notice error" role="alert">{{ basisProblem }}</p>
        <NButton v-if="!targetReady" size="small" :loading="binding" :disabled="blocked || !selectedReady || !target || target.unpublished" @click="bindTarget">重新读取并核对当前发布版</NButton>
        <label>处理方式<NSelect v-model:value="choice" :options="choiceOptions" :disabled="blocked || !selectedReady || !targetReady" /></label>
        <p class="field-help">{{ choice === 'PUBLISHED_UPDATE' ? '正文须已发布更新且晚于原采用版；仅改标题不属于正文更正。' : choice === 'ARCHIVED' ? '先在编辑器归档目标，再登记本次归档处理说明。' : '说明逐项核对后保留当前内容的依据。' }}</p>
        <label :for="`${panelId}-reason`">处理理由<NInput :id="`${panelId}-reason`" v-model:value="reason" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="!readable || locked || disabled" placeholder="说明本次核对的内容、结论和依据。" /><span class="field-help">{{ Array.from(reason).length }} / 1000 字</span></label>
        <NCheckbox v-model:checked="confirmed" :disabled="blocked || !selectedReady || !targetReady || !revalidationReasonValid(reason)">我已核对本次来源影响、处理方式及当前确切发布版，确认保存结论</NCheckbox>
        <div class="row-actions"><NButton type="primary" size="small" :loading="writer.busy.value" :disabled="blocked || !!inputProblem" @click="save">保存处理结论</NButton><span v-if="inputProblem" class="field-help">{{ inputProblem }}</span></div>
        <p class="field-help">只解除这一项采用影响。回忆卡须显式复核并恢复排程；旧审阅、评分和外发授权不会自动恢复。</p>
      </section>
    </div>
  </section>
</template>

<style scoped>
.preparation-knowledge, .content, .lineage, .resolve-form { display: grid; gap: 12px; min-width: 0; }
.preparation-knowledge { margin: 18px 0; padding-top: 17px; border-top: 1px solid var(--line); }
header { display: flex; align-items: start; justify-content: space-between; flex-wrap: wrap; gap: 9px 14px; }
header > div { display: grid; gap: 7px; min-width: 0; } h3, h4, p { margin: 0; } h3 { font-size: 15px; } h4, strong { font-size: 13px; }
label { display: grid; gap: 8px; min-width: 0; font-size: 13px; } .filters { display: flex; gap: 12px; align-items: end; flex-wrap: wrap; }
.filters > label { flex: 1 1 140px; max-width: 280px; }
.impact-list { display: grid; list-style: none; padding: 0; margin: 0; min-width: 0; }
.impact-list > li { display: grid; gap: 10px; min-width: 0; padding: 17px 0; border-top: 1px solid var(--line); }
.lineage, .resolve-form { padding-top: 14px; border-top: 1px solid var(--line); }
.lineage-list { display: grid; gap: 14px; padding-left: 22px; margin: 0; min-width: 0; }
.lineage-list > li { display: grid; gap: 10px; min-width: 0; }
.row-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; min-width: 0; }
dl { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 6px 12px; margin: 0; font-size: 12px; }
dt { color: var(--muted); } dd { margin: 0; overflow-wrap: anywhere; } .resolution-text { white-space: pre-wrap; font-size: 13px; line-height: 1.7; }
h4, p, strong, .notice, .field-help { overflow-wrap: anywhere; } .status-pill { flex: none; }
.lineage > .n-button, .resolve-form > .n-button, .impact-list > li > .n-button { justify-self: start; }
@media (max-width: 450px) { dl { grid-template-columns: 1fr; } dd { margin-bottom: 5px; } }
</style>
