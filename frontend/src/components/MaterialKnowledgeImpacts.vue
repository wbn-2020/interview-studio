<script setup lang="ts">
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { RouteLocationRaw } from 'vue-router'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { ItemDetail } from '../api/client'
import type { KnowledgeRevalidationChoice, KnowledgeRevalidationResolve, KnowledgeRevalidationState, KnowledgeRevalidationTarget } from '../api/knowledgeRevalidation'
import { knowledgeRevalidationChoiceLabels, knowledgeRevalidationStateLabels, knowledgeRevalidationTarget, knowledgeRevalidationTargetKey,
  revalidationIdValid, revalidationReasonValid, revalidationStateValid } from '../api/knowledgeRevalidation'
import type { MaterialKnowledgeImpact, MaterialKnowledgeImpactContext, MaterialKnowledgeImpactPage, MaterialKnowledgeImpactSource } from '../api/materialKnowledgeImpacts'
import { materialKnowledgeImpactContextValid, materialKnowledgeImpactPageValid, materialKnowledgeImpactPaths,
  materialKnowledgeImpactReceiptMatches, materialKnowledgeImpactResolveValid, materialKnowledgeImpactTraceValid,
  materialKnowledgeSameImpact } from '../api/materialKnowledgeImpacts'
import { materialCorrectionExcerptValid } from '../api/materialCorrection'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

const props = withDefaults(defineProps<{ itemId: string; context: MaterialKnowledgeImpactContext; current?: ItemDetail;
  editing?: boolean; disabled?: boolean; visible?: boolean }>(), { visible: true })
const emit = defineEmits<{ changed: []; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter(), writer = useCommand(), viewId = useId()
const boundId = props.itemId, boundContext = props.context
const stateKey = `material-knowledge-impact:${viewId}`, panelId = `material-knowledge-impact-${viewId}`
const element = ref<HTMLElement>(), active = ref(true), elementVisible = ref(false)
const opened = ref(false), documentVisible = ref(document.visibilityState === 'visible')
const state = ref<KnowledgeRevalidationState>('PENDING'), page = shallowRef<MaterialKnowledgeImpactPage>()
const cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const loading = ref(false), problem = ref(''), paused = ref(false), message = ref('')
const navigationProblem = ref(''), opening = ref(false)
const traceAnchor = shallowRef<MaterialKnowledgeImpact>(), trace = shallowRef<MaterialKnowledgeImpact[]>([])
const traceLoading = ref(false), traceProblem = ref('')
const selected = shallowRef<MaterialKnowledgeImpact>(), selectedGeneration = ref(-1)
const baseline = shallowRef<KnowledgeRevalidationTarget>(), binding = ref(false), basisProblem = ref('')
const reason = ref(''), choice = ref<KnowledgeRevalidationChoice>('KEEP_CURRENT'), confirmed = ref(false)
const identityValid = computed(() => revalidationIdValid(boundId) && props.itemId === boundId
  && materialKnowledgeImpactContextValid(boundContext) && props.context === boundContext)
const readable = computed(() => identityValid.value && active.value && elementVisible.value && props.visible
  && documentVisible.value && store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
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
            : !confirmed.value ? '请核对材料更正、处理方式和当前发布版，再明确确认。'
              : selected.value && materialKnowledgeImpactResolveValid(request.value, selected.value, target.value) ? ''
                : '处理方式与当前目标状态或发布版本不一致。')
let alive = true, generation = 0, sequence = 0, traceSequence = 0, basisSequence = 0, observer: MutationObserver | undefined
let commandEpoch = store.sessionEpoch, commandGeneration = 0, commandAccessEpoch = currentTrainingAccessEpoch()
let lastRequest: { cursor?: string; previous: (string | undefined)[] } | undefined

watch([dirty, locked], ([unsaved, pending]) => {
  changes.dirty = unsaved; changes.locked = pending; store.changes.set(stateKey, changes)
  emit('state', { dirty: unsaved, locked: pending })
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.itemId, () => props.context, () => props.disabled,
  () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  generation++; clearPage(); invalidateBasis(); selectedGeneration.value = -1; message.value = ''; paused.value = opened.value
  // 访问或其他命令换代只撤销可见目录和核对资格，处理说明与原键仍保留。
}, { flush: 'sync' })
watch([() => knowledgeRevalidationTargetKey(target.value), () => props.editing], invalidateBasis, { flush: 'sync' })
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
onActivated(() => { active.value = true; elementVisibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; generation++; clearPage(); invalidateBasis(); observer?.disconnect()
  document.removeEventListener('visibilitychange', visibilityChanged)
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
  const current = () => alive && readable.value && opened.value && !props.disabled && epoch === store.sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch() && stamp === generation && ticket === sequence && filter === state.value
  try {
    const value = await read<unknown>(materialKnowledgeImpactPaths.page(boundId, boundContext, filter, after))
    if (!current()) return
    if (!materialKnowledgeImpactPageValid(value, boundId, boundContext, filter, after)) {
      throw new ApiError(502, 'MATERIAL_KNOWLEDGE_PAGE_INVALID', '材料知识影响的根身份、来源、历史或分页无法核对，请重新读取。')
    }
    const refreshed = selected.value && value.items.find(impact => impact.id === selected.value!.id)
    if (refreshed && selected.value && !materialKnowledgeSameImpact(refreshed, selected.value)) {
      throw new ApiError(502, 'MATERIAL_KNOWLEDGE_IMPACT_INVALID', '原影响的固定更正声明与目录不一致，处理说明继续保留。')
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
function viewTrace(impact: MaterialKnowledgeImpact) {
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
  const current = () => alive && readable.value && opened.value && !props.disabled && epoch === store.sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch() && stamp === generation && ticket === traceSequence
    && listTicket === sequence && directory === page.value && traceAnchor.value === original
  try {
    const value = await read<unknown>(materialKnowledgeImpactPaths.detail(impactId))
    if (!current()) return
    if (!materialKnowledgeImpactTraceValid(value, original, steps)) {
      throw new ApiError(502, 'MATERIAL_KNOWLEDGE_TRACE_INVALID', '这一跳的更正根、实际来源、父影响、时间或版本范围无法核对。')
    }
    trace.value = [...steps, value]
  } catch (error) { if (current()) traceProblem.value = errorText(error) }
  finally { if (alive && ticket === traceSequence) traceLoading.value = false }
}
function clearInput() { selected.value = undefined; selectedGeneration.value = -1; invalidateBasis(); reason.value = ''; choice.value = 'KEEP_CURRENT' }
function cancel() {
  if (!readable.value || locked.value || !store.canLeave([stateKey])) return
  clearInput(); writer.error.value = undefined
}
function selectImpact(impact: MaterialKnowledgeImpact) {
  if (blocked.value || boundContext !== 'KNOWLEDGE' || !page.value?.items.includes(impact)
    || impact.targetItemId !== boundId || impact.revision !== 0) return
  if (selected.value?.id !== impact.id) {
    if (dirty.value && !store.canLeave([stateKey])) return
    clearInput(); choice.value = target.value?.status === 'ARCHIVED' ? 'ARCHIVED' : 'KEEP_CURRENT'
  } else if (!materialKnowledgeSameImpact(impact, selected.value)) return
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
      throw new ApiError(409, 'MATERIAL_KNOWLEDGE_TARGET_CHANGED', '目标发布版或草稿已变化，请重新读取知识后再核对，理由继续保留。')
    }
    baseline.value = latest
  } catch (error) { if (current()) basisProblem.value = errorText(error) }
  finally { if (alive && ticket === basisSequence) binding.value = false }
}
function save() {
  if (blocked.value || inputProblem.value || !selected.value || !target.value
    || !materialKnowledgeImpactResolveValid(request.value, selected.value, target.value)) return
  const original = JSON.parse(JSON.stringify(selected.value)) as MaterialKnowledgeImpact
  const body = JSON.parse(JSON.stringify(request.value)) as KnowledgeRevalidationResolve
  commandEpoch = store.sessionEpoch; commandGeneration = generation; commandAccessEpoch = currentTrainingAccessEpoch(); message.value = ''
  // 原影响、目标CAS、完整载荷及键一并冻结；恢复回执不重新绑定目标今天的资格。
  void writer.run<unknown>(materialKnowledgeImpactPaths.resolve(boundId, original.id), 'POST', body, value => {
    if (!alive || !readable.value || !opened.value || props.disabled || props.editing || commandEpoch !== store.sessionEpoch
      || commandGeneration !== generation || commandAccessEpoch !== currentTrainingAccessEpoch()
      || !materialKnowledgeImpactReceiptMatches(value, original, body)) {
      throw new ApiError(0, 'MATERIAL_KNOWLEDGE_RECEIPT_UNCERTAIN', '处理回执或访问条件无法核对，原请求与原键已保留，请用原请求确认结果。')
    }
    clearInput(); message.value = `已登记「${knowledgeRevalidationChoiceLabels[value.resolution!.choice]}」。仅处理这一影响；卡片来源及排程仍须单独复核，旧材料祖先仍可能阻止 AI 使用。`
    emit('changed'); void load()
  })
}
function retryCommand() {
  if (!readable.value || !opened.value || props.disabled || props.editing || writer.busy.value || !writer.pending.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; commandAccessEpoch = currentTrainingAccessEpoch(); void writer.retry()
}
async function open(target: RouteLocationRaw) {
  if (!readable.value || !opened.value || props.disabled || locked.value || opening.value) return
  const epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), stamp = generation
  opening.value = true; navigationProblem.value = ''
  try {
    const failure = await router.push(target)
    if (failure && alive && readable.value && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
      && stamp === generation) navigationProblem.value = '页面未切换，请先处理本地输入或资料访问条件。'
  } catch {
    if (alive && readable.value && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
      && stamp === generation) navigationProblem.value = '暂时无法打开此入口，请稍后重试。'
  } finally { opening.value = false }
}
async function openMaterial(excerptId: string) {
  if (!readable.value || !opened.value || props.disabled || locked.value || opening.value || !revalidationIdValid(excerptId)) return
  const epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), stamp = generation
  opening.value = true; navigationProblem.value = ''
  const current = () => alive && readable.value && opened.value && !props.disabled && !locked.value
    && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch() && stamp === generation
  try {
    const value = await read<unknown>(`/materials/excerpts/${encodeURIComponent(excerptId)}`)
    if (!current()) return
    if (!materialCorrectionExcerptValid(value, excerptId)) {
      throw new ApiError(502, 'MATERIAL_KNOWLEDGE_SOURCE_INVALID', '确切摘录与附件身份无法核对，不能拼接猜测入口。')
    }
    const failure = await router.push({ name: 'materials', query: {
      attachment: value.attachmentId, run: value.runId ?? undefined, excerpt: value.id,
    } })
    if (failure && current()) navigationProblem.value = '页面未切换，请先处理本地输入或资料访问条件。'
  } catch (error) { if (current()) navigationProblem.value = errorText(error) }
  finally { opening.value = false }
}
function openKnowledge(itemId: string, versionId?: string) { void open({ name: 'knowledge', query: { item: itemId, version: versionId } }) }
function openSource(source: MaterialKnowledgeImpactSource) {
  if (source.domain === 'KNOWLEDGE' && source.versionId) openKnowledge(source.itemId, source.versionId)
  else if (source.domain === 'MATERIAL_EXCERPT') void openMaterial(source.itemId)
}
function openTask(taskId: string) { void open({ name: 'ai', query: { item: taskId } }) }
function sourceLabel(source: MaterialKnowledgeImpactSource) {
  return source.domain === 'MATERIAL_EXCERPT' ? `固定材料摘录 ${source.itemId}` : `${source.title} v${source.versionNumber}`
}
function date(value: string) { return new Date(value).toLocaleString('zh-CN') }
</script>

<template>
  <section ref="element" class="material-knowledge" aria-label="材料更正知识影响与复核">
    <header><div><h3>{{ context === 'MATERIAL_EXCERPT' ? '本摘录更正引起的知识影响' : '材料更正与知识复核' }}</h3><p class="field-help">{{ dirty ? '有尚未提交的处理说明。' : '核对固定材料声明及其下游知识的实际采用链。' }}</p></div><NButton size="small" :disabled="!readable || locked || disabled" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起材料更正影响' : '查看材料更正影响' }}</NButton></header>
    <p v-if="!identityValid" class="notice error" role="alert">对象身份已变化，请回到原对象处理保留的输入和请求。</p>
    <p v-else-if="opened && !readable" class="notice" role="status">当前查看条件未就绪，目录和旧核对资格已清除，处理说明与原请求保留。</p>
    <div v-if="opened" v-show="readable" :id="panelId" class="content" :inert="!readable || undefined" :aria-busy="loading">
      <p class="field-help">只记录真实材料更正声明造成的知识采用影响；待复核表示需要核对，不认证替代原文正确，不自动改写正文或历史引用。本目录不是完整删除影响清单，也不补造过去未登记的影响。</p>
      <p class="field-help">处理只关闭这一项待复核，不处理后代，不恢复旧评分、审阅、任务授权或卡片排程。即使保留当前或发布更新，旧材料祖先仍可能阻止 AI 使用，须另行建立真实新依据。</p>
      <div class="filters"><label>处理状态<NSelect :value="state" :options="stateOptions" :disabled="!readable || locked || disabled" @update:value="filterState" /></label><NButton size="small" :loading="loading" :disabled="!readable || loading || locked || disabled" @click="refresh">刷新记录</NButton></div>
      <p v-if="paused" class="notice" role="status">查看条件已变化，请主动刷新目录并重新核对。</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text size="small" :loading="writer.busy.value" :disabled="!readable || disabled || editing || writer.busy.value" @click="retryCommand">用原请求核对</NButton><NButton v-else text size="small" :disabled="!readable || loading || disabled" @click="refresh">刷新并保留处理说明</NButton></p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}<NButton text size="small" :disabled="!readable || loading || locked || disabled" @click="retryPage">重读这一页</NButton></p>
      <p v-if="navigationProblem" class="notice error" role="alert">{{ navigationProblem }}</p>
      <p v-if="loading" class="field-help" role="status">正在读取材料更正知识影响元数据…</p>
      <template v-else-if="page">
        <p v-if="!page.items.length" class="field-help">本页没有已登记的{{ state === 'PENDING' ? '待处理' : '' }}影响。目录仅覆盖已接入的实际知识采用链，不能据此判定没有其他依赖。</p>
        <ol v-else class="impact-list">
          <li v-for="impact in page.items" :key="impact.id">
            <header><strong>{{ impact.targetTitle }} · 原采用 v{{ impact.targetVersionNumber }}</strong><span class="status-pill">{{ impact.resolution ? '已处理' : '待复核' }}</span></header>
            <p class="field-help">更正声明 {{ impact.correction.id }} · <time :datetime="impact.createdAt" :title="impact.createdAt">{{ date(impact.createdAt) }}</time></p>
            <p class="resolution-text">{{ impact.correction.reason }}</p>
            <dl><dt>旧固定摘录</dt><dd>{{ impact.correction.excerptId }} · 声明时生命周期修订 {{ impact.correction.sourceRevision }}</dd><dt>固定替代摘录</dt><dd>{{ impact.correction.replacementExcerptId }} · 声明时生命周期修订 {{ impact.correction.replacementRevision }}</dd><dt>影响 / 修订</dt><dd>{{ impact.id }} / {{ impact.revision }}</dd><dt>本跳实际来源</dt><dd>第 {{ impact.depth }} 跳 · {{ sourceLabel(impact.source) }}<br />{{ impact.source.versionId ?? '材料摘录无知识版本字段' }}</dd><dt>原采用版本</dt><dd>{{ impact.targetVersionId }}</dd></dl>
            <div class="row-actions"><NButton :id="`${panelId}-trace-trigger-${impact.id}`" text size="small" :disabled="!readable || locked || disabled || loading" :aria-expanded="traceAnchor?.id === impact.id" :aria-controls="`${panelId}-trace-${impact.id}`" @click="viewTrace(impact)">查看采用路径</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openMaterial(impact.correction.excerptId)">原固定摘录与声明</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openMaterial(impact.correction.replacementExcerptId)">本次固定替代原文</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openSource(impact.source)">本跳实际输入</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openKnowledge(impact.targetItemId, impact.targetVersionId)">原采用 v{{ impact.targetVersionNumber }}</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openTask(impact.taskId)">原 AI 整理任务</NButton></div>
            <section v-if="traceAnchor?.id === impact.id" :id="`${panelId}-trace-${impact.id}`" class="lineage" :aria-labelledby="`${panelId}-trace-heading-${impact.id}`" :aria-busy="traceLoading">
              <header><h4 :id="`${panelId}-trace-heading-${impact.id}`">逐跳回看采用路径</h4><NButton text size="small" @click="closeTrace">关闭采用路径</NButton></header>
              <p class="field-help">显示一条真实、稳定的最短父路径，不代表全部依赖，也不表示正文全部复制自材料。查看路径不会登记处理决定。</p>
              <ol v-if="trace.length" class="lineage-list">
                <li v-for="step in trace" :key="step.id"><h4>第 {{ step.depth }} 跳 · {{ sourceLabel(step.source) }} → {{ step.targetTitle }} v{{ step.targetVersionNumber }}</h4><p class="field-help">影响 {{ step.id }}<br />父影响：{{ step.parentImpactId ?? '无（材料进入知识第一跳）' }}</p><div class="row-actions"><NButton text size="small" :disabled="locked || disabled || opening" @click="openSource(step.source)">本跳实际来源</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openKnowledge(step.targetItemId, step.targetVersionId)">本跳原采用目标</NButton><NButton text size="small" :disabled="locked || disabled || opening" @click="openTask(step.taskId)">本跳原 AI 任务</NButton></div></li>
              </ol>
              <p v-if="traceLoading" class="field-help" role="status">正在读取这一跳…</p>
              <p v-if="traceProblem" class="notice error" role="alert">{{ traceProblem }}<NButton text size="small" :disabled="!readable || locked || disabled || traceLoading" @click="loadTrace">重读这一跳</NButton></p>
              <NButton v-if="trace.at(-1)?.parentImpactId" size="small" :loading="traceLoading" :disabled="!readable || locked || disabled || loading || traceLoading" @click="loadTrace">继续向源头查看</NButton><p v-else-if="trace.length" class="field-help" role="status">已到达旧固定材料进入知识的第一跳；替代摘录不会自动成为本链来源。</p>
            </section>
            <template v-if="impact.resolution"><p class="resolution-text">{{ knowledgeRevalidationChoiceLabels[impact.resolution.choice] }}：{{ impact.resolution.reason }}</p><p class="field-help">处理时目标修订 {{ impact.resolution.targetRevision }} · {{ date(impact.resolution.decidedAt) }}</p><NButton text size="small" :disabled="locked || disabled || opening" @click="openKnowledge(impact.targetItemId, impact.resolution.targetVersionId)">处理时的确切发布版</NButton></template>
            <div v-else class="row-actions"><NButton v-if="context === 'KNOWLEDGE'" size="small" :disabled="blocked || !target || target.unpublished" @click="selectImpact(impact)">{{ selected?.id === impact.id ? '重新核对这一影响' : '处理这一影响' }}</NButton><NButton v-else text size="small" :disabled="locked || disabled || opening" @click="openKnowledge(impact.targetItemId)">打开目标知识处理</NButton></div>
          </li>
        </ol>
        <nav v-if="previous.length || page.nextCursor" class="row-actions" aria-label="材料知识影响分页"><NButton size="small" :disabled="!previous.length || loading || locked || disabled" @click="previousPage">上一页</NButton><NButton size="small" :disabled="!page.nextCursor || loading || locked || disabled" @click="nextPage">下一页</NButton><span class="field-help">第 {{ previous.length + 1 }} 页 · 每页最多20项</span></nav>
      </template>
      <section v-if="selected" class="resolve-form" aria-label="处理材料更正知识影响">
        <header><h4>处理原采用 v{{ selected.targetVersionNumber }} 的影响</h4><NButton text size="small" :disabled="locked || disabled" @click="cancel">取消填写</NButton></header>
        <p class="field-help">影响 {{ selected.id }}。结论绑定当前发布版，原采用及原材料更正声明继续保留。</p>
        <p v-if="selected.resolution" class="notice" role="status">此影响已处理，原输入继续保留，请在全部记录中核对已保存决定。</p><p v-else-if="!selectedReady" class="notice" role="status">原影响需要重新核对。请刷新目录找到这一影响，理由继续保留。</p>
        <p v-if="target" class="field-help">编辑器当前发布 v{{ target.versionNumber }} · 元数据修订 {{ target.revision }} · 确切版本 {{ target.versionId }}</p>
        <p v-if="basisProblem" class="notice error" role="alert">{{ basisProblem }}</p>
        <NButton v-if="!targetReady" size="small" :loading="binding" :disabled="blocked || !selectedReady || !target || target.unpublished" @click="bindTarget">重新读取并核对当前发布版</NButton>
        <label>处理方式<NSelect v-model:value="choice" :options="choiceOptions" :disabled="blocked || !selectedReady || !targetReady" /></label>
        <p class="field-help">{{ choice === 'PUBLISHED_UPDATE' ? '正文须已发布更新且晚于原采用版；服务器仍核验真实正文差异，仅改标题不属于正文更正。' : choice === 'ARCHIVED' ? '先在编辑器归档目标，再登记本次归档处理说明。' : '说明逐项核对后保留当前内容的依据。' }}</p>
        <label :for="`${panelId}-reason`">处理理由<NInput :id="`${panelId}-reason`" v-model:value="reason" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="!readable || locked || disabled" placeholder="说明本次材料更正核对的内容、结论和依据。" /><span class="field-help">{{ Array.from(reason).length }} / 1000 字</span></label>
        <NCheckbox v-model:checked="confirmed" :disabled="blocked || !selectedReady || !targetReady || !revalidationReasonValid(reason)">我已核对本次材料更正影响、处理方式及当前确切发布版，确认保存结论</NCheckbox>
        <div class="row-actions"><NButton type="primary" size="small" :loading="writer.busy.value" :disabled="blocked || !!inputProblem" @click="save">保存处理结论</NButton><span v-if="inputProblem" class="field-help">{{ inputProblem }}</span></div>
        <p class="field-help">只处理这一项。回忆卡仍须显式复核来源与恢复排程；旧评分、审阅及外发授权不恢复，旧材料祖先的 AI 限制继续保留。</p>
      </section>
    </div>
  </section>
</template>

<style scoped>
.material-knowledge, .content, .lineage, .resolve-form { display: grid; gap: 12px; min-width: 0; }
.material-knowledge { margin: 18px 0; padding-top: 17px; border-top: 1px solid var(--line); }
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
