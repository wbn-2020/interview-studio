<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { RouteLocationRaw } from 'vue-router'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { ItemDetail } from '../api/client'
import type { KnowledgeRevalidationChoice, KnowledgeRevalidationDirection, KnowledgeRevalidationImpact, KnowledgeRevalidationLineage, KnowledgeRevalidationPage,
  KnowledgeRevalidationResolve, KnowledgeRevalidationState, KnowledgeRevalidationTarget } from '../api/knowledgeRevalidation'
import { knowledgeRevalidationChoiceLabels, knowledgeRevalidationDirectionLabels, knowledgeRevalidationLineageValid, knowledgeRevalidationPageValid, knowledgeRevalidationPaths,
  knowledgeRevalidationReceiptMatches, knowledgeRevalidationResolveValid, knowledgeRevalidationSameImpact, knowledgeRevalidationStateLabels,
  knowledgeRevalidationTarget, knowledgeRevalidationTargetKey, revalidationDirectionValid, revalidationIdValid, revalidationReasonValid,
  revalidationStateValid } from '../api/knowledgeRevalidation'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

const props = defineProps<{ itemId: string; current?: ItemDetail; editing?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ changed: []; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter(), writer = useCommand(), viewId = useId()
const boundId = props.itemId, stateKey = `knowledge-revalidation:${viewId}`, panelId = `knowledge-revalidation-${viewId}`
const opened = ref(false), visible = ref(document.visibilityState === 'visible')
const direction = ref<KnowledgeRevalidationDirection>('TARGET'), state = ref<KnowledgeRevalidationState>('PENDING')
const page = shallowRef<KnowledgeRevalidationPage>(), cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const loading = ref(false), problem = ref(''), navigationProblem = ref(''), opening = ref(false), paused = ref(false), message = ref('')
const traceAnchor = shallowRef<KnowledgeRevalidationImpact>(), trace = shallowRef<KnowledgeRevalidationLineage[]>([])
const traceLoading = ref(false), traceProblem = ref('')
const selected = shallowRef<KnowledgeRevalidationImpact>(), selectedGeneration = ref(-1), baseline = shallowRef<KnowledgeRevalidationTarget>()
const reason = ref(''), choice = ref<KnowledgeRevalidationChoice>('KEEP_CURRENT'), confirmed = ref(false)
const identityValid = computed(() => revalidationIdValid(boundId) && props.itemId === boundId)
const readable = computed(() => identityValid.value && store.sessionActive && visible.value && !access.checking && !access.restricted
  && !access.challenge && !store.changes.get('training-access-command')?.locked)
const target = computed(() => knowledgeRevalidationTarget(props.current, boundId))
const locked = computed(() => writer.locked.value)
const dirty = computed(() => Boolean(selected.value || reason.value || confirmed.value))
const changes = reactive({ dirty: false, locked: false })
const selectedReady = computed(() => selected.value?.targetItemId === boundId && selected.value.revision === 0
  && selected.value.resolution === null && selectedGeneration.value === generation)
const targetReady = computed(() => Boolean(target.value && baseline.value && !target.value.unpublished
  && knowledgeRevalidationTargetKey(target.value) === knowledgeRevalidationTargetKey(baseline.value)))
const blocked = computed(() => !readable.value || props.disabled || props.editing || locked.value || loading.value)
const directionOptions = Object.entries(knowledgeRevalidationDirectionLabels).map(([value, label]) => ({ value, label }))
const stateOptions = Object.entries(knowledgeRevalidationStateLabels).map(([value, label]) => ({ value, label }))
const choiceOptions = computed(() => Object.entries(knowledgeRevalidationChoiceLabels).map(([value, label]) => ({
  value, label, disabled: value === 'ARCHIVED' ? target.value?.status !== 'ARCHIVED'
    : target.value?.status !== 'ACTIVE' || value === 'PUBLISHED_UPDATE' && (!selected.value || !target.value
      || target.value.versionNumber <= selected.value.targetVersionNumber),
})))
const request = computed(() => ({ expectedRevision: selected.value?.revision, expectedTargetRevision: baseline.value?.revision,
  expectedTargetVersionId: baseline.value?.versionId, choice: choice.value, reason: reason.value, confirmed: confirmed.value }))
const inputProblem = computed(() => !selectedReady.value ? '请重新读取目录并核对原影响。'
  : props.editing ? '当前有本地修改，请先保存并处理草稿。' : props.disabled ? '请回到当前知识编辑状态后处理。'
    : !target.value ? '当前发布版身份无法核对，请重新读取知识。'
      : target.value.unpublished ? '当前有未发布草稿，请先发布或处理草稿。'
        : !targetReady.value ? '当前知识已变化，请重新核对处理基线。'
          : !revalidationReasonValid(reason.value) ? '请填写 1–1000 字理由，不能只有空白或含无效字符。'
            : !confirmed.value ? '请核对处理方式、理由和当前发布版，再明确确认。'
              : selected.value && knowledgeRevalidationResolveValid(request.value, selected.value, target.value) ? ''
                : '处理方式与当前目标状态或发布版本不一致，请重新核对。')
let alive = true, generation = 0, sequence = 0, traceSequence = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0, commandAccessEpoch = currentTrainingAccessEpoch()
let lastRequest: { cursor?: string; previous: (string | undefined)[] } | undefined

watch([dirty, locked], ([unsaved, pending]) => {
  changes.dirty = unsaved; changes.locked = pending; store.changes.set(stateKey, changes)
  emit('state', { dirty: unsaved, locked: pending })
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.itemId, () => access.active?.id, () => access.active?.mode,
  () => access.active?.materialAccess], () => {
  generation++; clearPage(); selectedGeneration.value = -1; baseline.value = undefined; confirmed.value = false; message.value = ''
  paused.value = opened.value
  // 访问变化清除目录与旧核对资格；处理理由、原影响和未决请求留在组件内恢复。
}, { flush: 'sync' })
watch([() => knowledgeRevalidationTargetKey(target.value), () => props.editing, () => props.disabled], () => {
  baseline.value = undefined; confirmed.value = false
}, { flush: 'sync' })
watch([reason, choice], () => { confirmed.value = false }, { flush: 'sync' })
watch(writer.error, value => {
  if (!value) return
  confirmed.value = false
  if (!writer.pending.value) {
    selectedGeneration.value = -1; baseline.value = undefined
    if (value instanceof ApiError && value.status === 409) emit('changed')
  }
})
function visibilityChanged() { visible.value = document.visibilityState === 'visible' }
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onUnmounted(() => {
  alive = false; generation++; sequence++; clearTrace(); document.removeEventListener('visibilitychange', visibilityChanged)
  if (store.changes.get(stateKey) === changes) store.changes.delete(stateKey)
  emit('state', { dirty: false, locked: false })
})

function clearPage() {
  clearTrace()
  sequence++; page.value = undefined; cursor.value = undefined; previous.value = []; loading.value = false
  problem.value = ''; navigationProblem.value = ''; lastRequest = undefined
}
function clearTrace() {
  traceSequence++; traceAnchor.value = undefined; trace.value = []; traceLoading.value = false; traceProblem.value = ''
}
async function closeTrace() {
  const triggerId = traceAnchor.value ? `${panelId}-lineage-trigger-${traceAnchor.value.id}` : undefined
  clearTrace()
  await nextTick()
  if (alive && readable.value && triggerId) document.getElementById(triggerId)?.focus()
}
function toggle() {
  if (!readable.value || locked.value) return
  opened.value = !opened.value
  if (!opened.value) {
    generation++; clearPage(); selectedGeneration.value = -1; baseline.value = undefined; confirmed.value = false
  }
  if (opened.value && !page.value) void load()
}
function filterDirection(value: unknown) {
  if (!readable.value || locked.value || !revalidationDirectionValid(value) || direction.value === value) return
  direction.value = value; clearPage(); selectedGeneration.value = -1; confirmed.value = false; void load()
}
function filterState(value: unknown) {
  if (!readable.value || locked.value || !revalidationStateValid(value) || state.value === value) return
  state.value = value; clearPage(); selectedGeneration.value = -1; confirmed.value = false; void load()
}
async function load(after?: string, stack: (string | undefined)[] = []) {
  if (!readable.value || !opened.value || locked.value || loading.value) return
  const epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), stamp = generation, ticket = ++sequence
  const filterDirection = direction.value, filterState = state.value
  lastRequest = { cursor: after, previous: [...stack] }
  clearTrace()
  page.value = undefined; loading.value = true; problem.value = ''; navigationProblem.value = ''; paused.value = false
  selectedGeneration.value = -1; confirmed.value = false
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
    && stamp === generation && ticket === sequence && direction.value === filterDirection && state.value === filterState
  try {
    const value = await read<unknown>(knowledgeRevalidationPaths.page(boundId, filterDirection, filterState, after))
    if (!current()) return
    if (!knowledgeRevalidationPageValid(value, boundId, filterDirection, filterState, after)) {
      throw new ApiError(502, 'KNOWLEDGE_REVALIDATION_PAGE_INVALID', '来源更正目录的身份、历史或分页无法核对，请重新读取。')
    }
    const refreshed = selected.value && value.items.find((impact: KnowledgeRevalidationImpact) => impact.id === selected.value!.id)
    if (refreshed && selected.value && !knowledgeRevalidationSameImpact(refreshed, selected.value)) {
      throw new ApiError(502, 'KNOWLEDGE_REVALIDATION_IMPACT_INVALID', '原影响的固定历史与当前目录不一致，输入继续保留，请重新核对。')
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
function viewLineage(impact: KnowledgeRevalidationImpact) {
  if (!readable.value || !opened.value || locked.value || loading.value || !page.value?.items.includes(impact)) return
  clearTrace(); traceAnchor.value = impact; void loadLineage()
}
async function loadLineage() {
  const original = traceAnchor.value, directory = page.value, steps = [...trace.value], child = steps.at(-1)
  if (!readable.value || !opened.value || locked.value || loading.value || traceLoading.value
    || !original || !directory?.items.includes(original) || child?.parentImpactId === null) return
  const itemId = child?.sourceItemId ?? boundId, impactId = child?.parentImpactId ?? original.id
  const epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), stamp = generation
  const listTicket = sequence, ticket = ++traceSequence
  traceLoading.value = true; traceProblem.value = ''
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch() && stamp === generation && ticket === traceSequence
    && listTicket === sequence && directory === page.value && traceAnchor.value === original
  try {
    const value = await read<unknown>(knowledgeRevalidationPaths.lineage(itemId, impactId))
    if (!current()) return
    if (!knowledgeRevalidationLineageValid(value, itemId, impactId, original, steps)) {
      throw new ApiError(502, 'KNOWLEDGE_REVALIDATION_LINEAGE_INVALID', '采用路径的影响身份、父跳或版本范围无法核对，请重新读取这一跳。')
    }
    // 路径回执只用于导航；决定可变化，不能覆盖目录原影响、处理资格或未决原请求。
    trace.value = [...steps, value]
  } catch (error) { if (current()) traceProblem.value = errorText(error) }
  finally { if (alive && ticket === traceSequence) traceLoading.value = false }
}
function clearInput() {
  selected.value = undefined; selectedGeneration.value = -1; baseline.value = undefined
  reason.value = ''; choice.value = 'KEEP_CURRENT'; confirmed.value = false
}
function cancel() {
  if (locked.value || !store.canLeave([stateKey])) return
  clearInput(); writer.error.value = undefined
}
function selectImpact(impact: KnowledgeRevalidationImpact) {
  if (blocked.value || !page.value?.items.includes(impact) || impact.targetItemId !== boundId || impact.revision !== 0) return
  if (selected.value?.id !== impact.id) {
    if (dirty.value && !store.canLeave([stateKey])) return
    clearInput(); choice.value = target.value?.status === 'ARCHIVED' ? 'ARCHIVED' : 'KEEP_CURRENT'
  } else if (!knowledgeRevalidationSameImpact(impact, selected.value)) return
  selected.value = impact; selectedGeneration.value = generation; message.value = ''; writer.error.value = undefined
  bindTarget()
}
function bindTarget() {
  if (blocked.value || !selectedReady.value || !target.value || target.value.unpublished) return
  baseline.value = { ...target.value }; confirmed.value = false
}
function save() {
  if (blocked.value || inputProblem.value || !selected.value || !target.value
    || !knowledgeRevalidationResolveValid(request.value, selected.value, target.value)) return
  const original = JSON.parse(JSON.stringify(selected.value)) as KnowledgeRevalidationImpact
  const body = JSON.parse(JSON.stringify(request.value)) as KnowledgeRevalidationResolve
  commandEpoch = store.sessionEpoch; commandGeneration = generation; commandAccessEpoch = currentTrainingAccessEpoch(); message.value = ''
  void writer.run<unknown>(knowledgeRevalidationPaths.resolve(boundId, original.id), 'POST', body, value => {
    // 回执必须绑定原影响与原发布基线；旧权限窗口或异常回执仍由 useCommand 保留原正文和原键。
    if (!alive || !readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation
      || commandAccessEpoch !== currentTrainingAccessEpoch() || !knowledgeRevalidationReceiptMatches(value, boundId, original, body)) {
      throw new ApiError(0, 'KNOWLEDGE_REVALIDATION_RECEIPT_UNCERTAIN', '访问条件变化或处理回执无法核对。输入与原请求已保留，请用原请求确认结果。')
    }
    clearInput(); message.value = `已登记「${knowledgeRevalidationChoiceLabels[value.resolution!.choice]}」。卡片仍须检查来源、明确复核并显式恢复排程。`
    emit('changed'); void load()
  })
}
function retryCommand() {
  if (!readable.value || writer.busy.value || !writer.pending.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; commandAccessEpoch = currentTrainingAccessEpoch(); void writer.retry()
}
async function open(target: RouteLocationRaw) {
  if (!readable.value || locked.value || opening.value) return
  const epoch = store.sessionEpoch, stamp = generation
  opening.value = true; navigationProblem.value = ''
  try {
    const failure = await router.push(target)
    if (failure && alive && readable.value && epoch === store.sessionEpoch && stamp === generation) {
      navigationProblem.value = '页面未切换，请先处理当前输入或资料访问条件。'
    }
  } catch {
    if (alive && readable.value && epoch === store.sessionEpoch && stamp === generation) navigationProblem.value = '暂时无法打开此入口，请稍后重试。'
  } finally { opening.value = false }
}
function openKnowledge(itemId: string, version?: string) {
  if (revalidationIdValid(itemId) && (version === undefined || revalidationIdValid(version))) {
    void open({ name: 'knowledge', query: { item: itemId, version } })
  }
}
function openTask(taskId: string) { if (revalidationIdValid(taskId)) void open({ name: 'ai', query: { item: taskId } }) }
function date(value: string) { return new Date(value).toLocaleString('zh-CN') }
</script>

<template>
  <section class="knowledge-revalidation" aria-label="知识来源更正与复核">
    <header><div><h3>来源更正与复核</h3><p class="field-help">{{ dirty ? '有尚未提交的处理说明。' : '查看当前知识作为采用目标或变更源头的待复核记录。' }}</p></div><NButton size="small" :disabled="!readable || locked" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起复核记录' : '查看待复核记录' }}</NButton></header>
    <p v-if="!identityValid" class="notice error" role="alert">知识身份已变化，请回到原知识处理保留的输入和请求。</p>
    <p v-else-if="opened && !readable" class="notice" role="status">当前查看条件未就绪，目录已清除，处理说明与原请求保留在此页。</p>
    <div v-if="opened" v-show="readable" :id="panelId" class="revalidation-content" :inert="!readable || undefined" :aria-busy="loading">
      <p class="field-help">记录知识来源发生的正文变更或归档，以及历史 AI 整理采用的目标。后续人工改写须逐项核对；处理不会自动改正文、恢复授权或完成复习。</p>
      <div class="filters"><label>对象方向<NSelect :value="direction" :options="directionOptions" :disabled="!readable || locked" @update:value="filterDirection" /></label><label>处理状态<NSelect :value="state" :options="stateOptions" :disabled="!readable || locked" @update:value="filterState" /></label><NButton size="small" :disabled="!readable || loading || locked" :loading="loading" @click="refresh">刷新记录</NButton></div>
      <p v-if="paused" class="notice" role="status">查看条件已变化，请主动刷新目录后核对原影响。</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text size="small" :loading="writer.busy.value" :disabled="!readable || writer.busy.value" @click="retryCommand">用原请求核对</NButton><NButton v-else text size="small" :disabled="!readable || loading" @click="refresh">刷新并保留处理说明</NButton></p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}<NButton text size="small" :disabled="!readable || loading || locked" @click="retryPage">重读这一页</NButton></p>
      <p v-if="navigationProblem" class="notice error" role="alert">{{ navigationProblem }}</p>
      <p v-if="loading" class="field-help" role="status">正在读取来源更正元数据…</p>
      <template v-else-if="page">
        <p v-if="!page.items.length" class="field-help">{{ state === 'PENDING' ? '本页没有已登记的待处理影响。' : '本页没有已登记的影响。' }}本目录覆盖接入后已登记的知识采用链，不代表全部派生内容或旧历史均已核对。</p>
        <ol v-else class="impact-list">
          <li v-for="impact in page.items" :key="impact.id">
            <header><strong>{{ impact.targetTitle }} · 原采用 v{{ impact.targetVersionNumber }}</strong><span class="status-pill">{{ impact.resolution ? '已处理' : '待复核' }}</span></header>
            <p class="field-help">根知识「{{ impact.rootTitle }}」{{ impact.changeKind === 'ARCHIVED' ? '被归档' : '发布正文发生变化' }} · <time :datetime="impact.createdAt" :title="impact.createdAt">{{ date(impact.createdAt) }}</time></p>
            <dl><dt>影响 / 修订</dt><dd>{{ impact.id }} / {{ impact.revision }}</dd><dt>根事件</dt><dd>{{ impact.eventId }}</dd><dt>实际输入版本</dt><dd>{{ impact.sourceVersionId }}</dd><dt>原采用版本</dt><dd>{{ impact.targetVersionId }}</dd></dl>
            <div class="row-actions"><NButton :id="`${panelId}-lineage-trigger-${impact.id}`" text size="small" :disabled="!readable || locked || loading || traceLoading && traceAnchor?.id === impact.id" :aria-expanded="traceAnchor?.id === impact.id" :aria-controls="`${panelId}-lineage-${impact.id}`" @click="viewLineage(impact)">查看采用路径</NButton><NButton text size="small" :disabled="locked || opening" @click="openKnowledge(impact.rootItemId, impact.rootPreviousVersionId)">根事件前版本</NButton><NButton text size="small" :disabled="locked || opening" @click="openKnowledge(impact.rootItemId, impact.rootCurrentVersionId)">根事件后版本</NButton><NButton text size="small" :disabled="locked || opening" @click="openKnowledge(impact.targetItemId, impact.targetVersionId)">原采用 v{{ impact.targetVersionNumber }}</NButton><NButton text size="small" :disabled="locked || opening" @click="openTask(impact.taskId)">原 AI 整理任务</NButton></div>
            <section v-if="traceAnchor?.id === impact.id" :id="`${panelId}-lineage-${impact.id}`" class="lineage" :aria-labelledby="`${panelId}-lineage-heading-${impact.id}`" :aria-busy="traceLoading">
              <header><h4 :id="`${panelId}-lineage-heading-${impact.id}`">采用路径</h4><NButton text size="small" @click="closeTrace">关闭采用路径</NButton></header>
              <p class="field-help">这是已登记的一条最短采用路径，可能另有等价路径；此处不列出全部依赖。查看路径不会登记处理决定。</p>
              <ol v-if="trace.length" class="lineage-list" aria-label="从所选影响逐跳回看来源">
                <li v-for="step in trace" :key="step.impact.id">
                  <h4>第 {{ step.depth }} 跳 · {{ step.sourceTitle }} v{{ step.sourceVersionNumber }} 采用到 {{ step.impact.targetTitle }} v{{ step.impact.targetVersionNumber }}</h4>
                  <dl><dt>本跳影响</dt><dd>{{ step.impact.id }}</dd><dt>本跳来源</dt><dd>{{ step.sourceItemId }}</dd><dt>实际输入版本</dt><dd>{{ step.impact.sourceVersionId }}</dd><dt>采用目标</dt><dd>{{ step.impact.targetItemId }}</dd><dt>原采用版本</dt><dd>{{ step.impact.targetVersionId }}</dd><dt>父影响</dt><dd>{{ step.parentImpactId ?? '无（根知识直接来源）' }}</dd><dt>原整理任务</dt><dd>{{ step.impact.taskId }}</dd></dl>
                  <div class="row-actions"><NButton text size="small" :disabled="locked || opening" @click="openKnowledge(step.sourceItemId, step.impact.sourceVersionId)">本跳来源 v{{ step.sourceVersionNumber }}</NButton><NButton text size="small" :disabled="locked || opening" @click="openKnowledge(step.impact.targetItemId, step.impact.targetVersionId)">本跳目标 v{{ step.impact.targetVersionNumber }}</NButton><NButton text size="small" :disabled="locked || opening" @click="openTask(step.impact.taskId)">本跳原 AI 整理任务</NButton></div>
                </li>
              </ol>
              <p v-if="traceLoading" class="field-help" role="status">正在读取这一跳的采用路径…</p>
              <p v-if="traceProblem" class="notice error" role="alert">{{ traceProblem }}<NButton text size="small" :disabled="!readable || locked || traceLoading" @click="loadLineage">重读这一跳</NButton></p>
              <NButton v-if="trace.at(-1)?.parentImpactId" size="small" :loading="traceLoading" :disabled="!readable || locked || loading || traceLoading" :aria-controls="`${panelId}-lineage-${impact.id}`" @click="loadLineage">继续向源头查看</NButton>
              <p v-else-if="trace.length" class="field-help" role="status">已到达根知识直接作为来源的第一跳。</p>
            </section>
            <template v-if="impact.resolution"><p class="resolution-text">{{ knowledgeRevalidationChoiceLabels[impact.resolution.choice] }}：{{ impact.resolution.reason }}</p><p class="field-help">处理时目标修订 {{ impact.resolution.targetRevision }} · {{ date(impact.resolution.decidedAt) }}</p><NButton text size="small" :disabled="locked || opening" @click="openKnowledge(impact.targetItemId, impact.resolution.targetVersionId)">处理时的确切发布版</NButton></template>
            <div v-else class="row-actions"><NButton v-if="impact.targetItemId === itemId" size="small" :disabled="blocked || !target || target.unpublished" @click="selectImpact(impact)">{{ selected?.id === impact.id ? '重新核对这一影响' : '处理这一影响' }}</NButton><NButton v-else text size="small" :disabled="locked || opening" @click="openKnowledge(impact.targetItemId)">打开目标知识处理</NButton></div>
          </li>
        </ol>
        <nav v-if="previous.length || page.nextCursor" class="row-actions" aria-label="来源更正记录分页"><NButton size="small" :disabled="!previous.length || loading || locked" @click="previousPage">上一页</NButton><NButton size="small" :disabled="!page.nextCursor || loading || locked" @click="nextPage">下一页</NButton><span class="field-help">第 {{ previous.length + 1 }} 页 · 每页最多 20 项</span></nav>
        <p class="field-help">新事件独立登记。刷新和翻页不冻结影响清单，处理一项不会清除其他待复核记录。</p>
      </template>
      <p v-if="!selected && (editing || target?.unpublished || disabled)" class="field-help">请回到当前知识，先保存本地改写并处理未发布草稿，再确认来源影响。</p>
      <section v-if="selected" class="resolve-form" aria-label="处理所选知识采用影响">
        <header><h4>处理原采用 v{{ selected.targetVersionNumber }} 的影响</h4><NButton text size="small" :disabled="locked" @click="cancel">取消填写</NButton></header>
        <p class="field-help">影响 {{ selected.id }}。此处登记对当前发布内容的处理结论，历史采用来源继续保留。</p>
        <p v-if="selected.resolution" class="notice" role="status">此影响已处理，原输入继续保留。请在全部记录中核对已保存的决定。</p>
        <p v-else-if="!selectedReady" class="notice" role="status">原影响需要重新核对。请刷新或翻页找到这一影响，再选择“重新核对这一影响”；理由继续保留。</p>
        <p v-if="target" class="field-help">编辑器当前发布 v{{ target.versionNumber }} · 元数据修订 {{ target.revision }} · {{ target.status === 'ARCHIVED' ? '已归档' : '有效' }}<br />确切版本 {{ target.versionId }}</p>
        <NButton v-if="!targetReady" size="small" :disabled="blocked || !selectedReady || !target || target.unpublished" @click="bindTarget">核对并使用当前发布版</NButton>
        <label>处理方式<NSelect v-model:value="choice" :options="choiceOptions" :disabled="blocked || !selectedReady || !targetReady" /></label>
        <p class="field-help">{{ choice === 'PUBLISHED_UPDATE' ? '正文须已发布更新且晚于原采用版，仅修改标题不能作为正文更正。' : choice === 'ARCHIVED' ? '须先在编辑器完成目标归档；本次只保存归档处理说明。' : '确认已经核对此次来源影响，说明保留当前内容的理由。' }}</p>
        <label :for="`${panelId}-reason`">处理理由<NInput :id="`${panelId}-reason`" v-model:value="reason" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="!readable || locked" placeholder="说明本次核对结论，以及保留、更新或归档的依据。" /><span class="field-help">{{ Array.from(reason).length }} / 1000 字</span></label>
        <NCheckbox v-model:checked="confirmed" :disabled="blocked || !selectedReady || !targetReady || !revalidationReasonValid(reason)">我已核对本次来源影响、处理方式和当前确切发布版，确认保存处理结论</NCheckbox>
        <div class="row-actions"><NButton type="primary" size="small" :loading="writer.busy.value" :disabled="blocked || !!inputProblem" @click="save">保存处理结论</NButton><span v-if="inputProblem" class="field-help">{{ inputProblem }}</span></div>
        <p class="field-help">处理只解除这一项采用影响。相关卡片仍须检查来源并明确复核，再单独恢复排程；旧审阅、观察和外发授权不会自动恢复。</p>
      </section>
    </div>
  </section>
</template>

<style scoped>
.knowledge-revalidation, .revalidation-content, .resolve-form, .lineage { display: grid; gap: 12px; min-width: 0; }
.knowledge-revalidation { margin: 18px 0; padding-top: 17px; border-top: 1px solid var(--line); }
header { display: flex; align-items: start; justify-content: space-between; flex-wrap: wrap; gap: 9px 14px; }
header > div { display: grid; gap: 7px; min-width: 0; } h3, h4, p { margin: 0; }
h3 { font-size: 15px; } h4, strong { font-size: 13px; } p, strong, h4 { overflow-wrap: anywhere; }
.filters { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: 12px; align-items: end; }
label { display: grid; gap: 8px; min-width: 0; font-size: 13px; }
.impact-list { display: grid; list-style: none; padding: 0; margin: 0; min-width: 0; }
.impact-list > li { display: grid; gap: 10px; min-width: 0; padding: 17px 0; border-top: 1px solid var(--line); }
.lineage { padding-top: 12px; border-top: 1px solid var(--line); }
.lineage-list { display: grid; gap: 14px; padding-left: 22px; margin: 0; min-width: 0; }
.lineage-list > li { display: grid; gap: 10px; min-width: 0; }
.row-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; min-width: 0; }
dl { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 6px 12px; margin: 0; font-size: 12px; }
dt { color: var(--muted); } dd { margin: 0; overflow-wrap: anywhere; }
.resolve-form { padding-top: 16px; border-top: 1px solid var(--line); }
.resolution-text { white-space: pre-wrap; font-size: 13px; line-height: 1.7; }
.field-help, .notice { overflow-wrap: anywhere; } .status-pill { flex: none; }
.filters > .n-button, .resolve-form > .n-button, .impact-list > li > .n-button, .lineage > .n-button { justify-self: start; }
@media (max-width: 650px) { .filters { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }.filters > .n-button { grid-column: 1 / -1; } }
@media (max-width: 450px) { .filters, dl { grid-template-columns: 1fr; } dd { margin-bottom: 5px; } }
</style>
