<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../../api/client'
import { revalidationIdValid, revalidationReasonValid } from '../../api/knowledgeRevalidation'
import { materialDependencyBindingLabels, materialDependencyChoiceAvailable, materialDependencyChoiceLabels,
  materialDependencyChoices, materialDependencyDomainLabels, materialDependencyImpactPageValid, materialDependencyImpactPaths,
  materialDependencyImpactValid, materialDependencyReceiptMatches, materialDependencyResolveValid, materialDependencySameImpact,
  materialDependencyStateLabels, materialDependencyStateValid, materialDependencyStatusLabels } from '../../api/materialDependencyImpacts'
import type { MaterialDependencyChoice, MaterialDependencyImpact, MaterialDependencyImpactPage,
  MaterialDependencyResolve, MaterialDependencyState, MaterialDependencyTarget } from '../../api/materialDependencyImpacts'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ excerptId: string; enabled: boolean }>()
const emit = defineEmits<{ changed: []; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), router = useRouter(), viewId = useId()
const boundExcerptId = props.excerptId, stateKey = `material-dependency-impact:${viewId}`, panelId = `material-dependency-impact-${viewId}`
const element = ref<HTMLElement>(), active = ref(true), elementVisible = ref(false), opened = ref(false)
const documentVisible = ref(document.visibilityState === 'visible'), filter = ref<MaterialDependencyState>('PENDING')
const page = shallowRef<MaterialDependencyImpactPage>(), cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const loading = ref(false), problem = ref(''), paused = ref(false), message = ref('')
const selected = shallowRef<MaterialDependencyImpact>(), selectedGeneration = ref(-1), baseline = shallowRef<MaterialDependencyImpact>()
const binding = ref(false), detailProblem = ref(''), navigationProblem = ref(''), navigating = ref(false)
const reason = ref(''), choice = ref<MaterialDependencyChoice>('KEEP_CURRENT'), confirmed = ref(false)
const changes = reactive({ dirty: false, locked: false })
const identityValid = computed(() => revalidationIdValid(boundExcerptId) && props.excerptId === boundExcerptId)
const readable = computed(() => identityValid.value && active.value && elementVisible.value && documentVisible.value && props.enabled
  && store.sessionActive && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const otherCommand = computed(() => ['material-editor-command', 'material-upload', 'material-lifecycle', 'material-correction']
  .some(key => store.changes.get(key)?.locked) || [...store.changes].some(([key, state]) =>
    key !== stateKey && key.startsWith('material-dependency-impact:') && state.locked))
const locked = computed(() => writer.locked.value || binding.value)
const dirty = computed(() => Boolean(reason.value || confirmed.value || selected.value?.revision === 0))
const blocked = computed(() => !readable.value || !opened.value || locked.value || otherCommand.value || loading.value || navigating.value)
const selectedReady = computed(() => Boolean(selected.value && selectedGeneration.value === generation))
const stateOptions = Object.entries(materialDependencyStateLabels).map(([value, label]) => ({ value, label }))
const choiceOptions = computed(() => materialDependencyChoices.map(value => ({
  value, label: materialDependencyChoiceLabels[value], disabled: !baseline.value || !materialDependencyChoiceAvailable(baseline.value, value),
})))
const request = computed(() => ({
  expectedRevision: baseline.value?.revision, expectedTargetVersionId: baseline.value?.currentTarget.versionId ?? null,
  expectedTargetRevision: baseline.value?.currentTarget.revision ?? null, choice: choice.value, reason: reason.value, confirmed: confirmed.value,
}))
const inputProblem = computed(() => !selectedReady.value || !baseline.value ? '请重新读取原影响及当前目标，核对本次处理基线。'
  : baseline.value.revision !== 0 ? '这一影响已经处理，历史决定保持原样。'
    : !materialDependencyChoiceAvailable(baseline.value, choice.value) ? '处理方式不符合目标当前状态，请先处理目标并重新读取。'
      : !revalidationReasonValid(reason.value) ? '请填写 1–1000 字处理理由，不能只有空白或含无效字符。'
        : !confirmed.value ? '请核对固定更正、目标当前状态与处理方式，再明确确认。'
          : materialDependencyResolveValid(request.value, baseline.value) ? '' : '影响或目标修订无法核对，请重新读取。')
let alive = true, generation = 0, sequence = 0, detailSequence = 0, observer: MutationObserver | undefined
let commandEpoch = -1, commandGeneration = -1, commandAccessEpoch = -1
let lastRequest: { cursor?: string; previous: (string | undefined)[]; state: MaterialDependencyState } | undefined

watch([dirty, locked], ([unsaved, pending]) => {
  changes.dirty = unsaved; changes.locked = pending; store.changes.set(stateKey, changes)
  emit('state', { dirty: unsaved, locked: pending })
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => props.excerptId, () => access.active?.id,
  () => access.active?.mode, () => access.active?.materialAccess], () => {
  generation++; clearPage(); clearBaseline(); selectedGeneration.value = -1; paused.value = opened.value; message.value = ''
  // 访问换代撤销目录与处理资格；本地理由、固定影响和原幂等请求继续保留。
}, { flush: 'sync' })
watch([reason, choice], () => { confirmed.value = false; message.value = '' }, { flush: 'sync' })
watch(writer.error, value => {
  if (!value) return
  confirmed.value = false
  if (!writer.pending.value) {
    clearBaseline(); selectedGeneration.value = -1
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
  for (let node: HTMLElement | null = element.value ?? null; node; node = node.parentElement)
    observer.observe(node, { attributes: true, attributeFilter: ['style', 'class', 'hidden', 'inert', 'aria-hidden'] })
})
onActivated(() => { active.value = true; elementVisibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; generation++; sequence++; detailSequence++; observer?.disconnect()
  document.removeEventListener('visibilitychange', visibilityChanged)
  if (store.changes.get(stateKey) === changes) store.changes.delete(stateKey)
  emit('state', { dirty: false, locked: false })
})
function clearBaseline() { detailSequence++; baseline.value = undefined; binding.value = false; confirmed.value = false; detailProblem.value = '' }
function clearPage() {
  sequence++; page.value = undefined; loading.value = false; problem.value = ''; cursor.value = undefined
  previous.value = []; lastRequest = undefined; navigationProblem.value = ''
}
function clearInput() {
  clearBaseline(); selected.value = undefined; selectedGeneration.value = -1; reason.value = ''; choice.value = 'KEEP_CURRENT'
}
function toggle() {
  if (!readable.value || locked.value || otherCommand.value) return
  if (opened.value) {
    if (!store.canLeave([stateKey])) return
    clearInput(); clearPage(); opened.value = false
  } else { opened.value = true; paused.value = false; void load() }
}
function changeFilter(value: unknown) {
  if (blocked.value || !materialDependencyStateValid(value) || value === filter.value) return
  filter.value = value; clearPage(); clearBaseline(); selectedGeneration.value = -1; void load()
}
async function load(after?: string, stack: (string | undefined)[] = []) {
  if (blocked.value) return
  const epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), stamp = generation, ticket = ++sequence, state = filter.value
  lastRequest = { cursor: after, previous: [...stack], state }; clearBaseline(); selectedGeneration.value = -1
  loading.value = true; page.value = undefined; problem.value = ''; paused.value = false
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch && stamp === generation
    && accessEpoch === currentTrainingAccessEpoch() && ticket === sequence && state === filter.value
  try {
    const value = await read<unknown>(materialDependencyImpactPaths.page(boundExcerptId, state, after))
    if (!current()) return
    if (!materialDependencyImpactPageValid(value, boundExcerptId, state, after))
      throw new ApiError(502, 'MATERIAL_DEPENDENCY_PAGE_INVALID', '材料依赖影响的固定根、绑定、目标或分页无法核对，请重读。')
    const refreshed = selected.value && value.items.find(item => item.id === selected.value!.id)
    if (refreshed && selected.value && !materialDependencySameImpact(refreshed, selected.value))
      throw new ApiError(502, 'MATERIAL_DEPENDENCY_IMPACT_INVALID', '原影响的固定历史与目录不一致，输入继续保留。')
    page.value = value; cursor.value = after; previous.value = [...stack]
    if (refreshed) { selected.value = refreshed; selectedGeneration.value = generation }
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (alive && ticket === sequence) loading.value = false }
}
function retryPage() {
  const request = lastRequest
  if (request && request.state === filter.value) void load(request.cursor, request.previous)
}
function nextPage() { if (page.value?.nextCursor) void load(page.value.nextCursor, [...previous.value, cursor.value]) }
function previousPage() { if (previous.value.length) void load(previous.value.at(-1), previous.value.slice(0, -1)) }
function selectImpact(value: MaterialDependencyImpact) {
  if (blocked.value || !page.value?.items.includes(value)) return
  if (selected.value?.id !== value.id) {
    if (dirty.value && !store.canLeave([stateKey])) return
    clearInput()
    choice.value = value.currentTarget.status === 'ARCHIVED' ? 'ARCHIVED' : value.currentTarget.status === 'WITHDRAWN'
      ? 'WITHDRAWN' : value.currentTarget.status === 'REMOVED' ? 'REMOVED' : 'KEEP_CURRENT'
  } else if (!materialDependencySameImpact(value, selected.value)) return
  selected.value = value; selectedGeneration.value = generation; writer.error.value = undefined; message.value = ''
  void bindTarget()
}
async function bindTarget() {
  const original = selected.value
  if (blocked.value || !original) return
  clearBaseline()
  const epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), stamp = generation, ticket = ++detailSequence
  binding.value = true
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch && stamp === generation
    && accessEpoch === currentTrainingAccessEpoch() && ticket === detailSequence && selected.value?.id === original.id
  try {
    const value = await read<unknown>(materialDependencyImpactPaths.detail(original.id))
    if (!current()) return
    if (!materialDependencyImpactValid(value, boundExcerptId) || !materialDependencySameImpact(value, original))
      throw new ApiError(502, 'MATERIAL_DEPENDENCY_DETAIL_INVALID', '影响详情与原固定绑定不一致，不能拼接当前目标或处理请求。')
    selected.value = value; selectedGeneration.value = generation; baseline.value = value; paused.value = false
  } catch (error) { if (current()) detailProblem.value = errorText(error) }
  finally { if (alive && ticket === detailSequence) binding.value = false }
}
function changeChoice(value: unknown) {
  if (!blocked.value && typeof value === 'string' && Object.hasOwn(materialDependencyChoiceLabels, value))
    choice.value = value as MaterialDependencyChoice
}
function changeReason(value: string) { if (!blocked.value) reason.value = value }
function cancel() {
  if (blocked.value || !store.canLeave([stateKey])) return
  clearInput(); writer.error.value = undefined; message.value = ''
}
function save() {
  const original = baseline.value
  if (blocked.value || inputProblem.value || !original || !materialDependencyResolveValid(request.value, original)) return
  const snapshot = JSON.parse(JSON.stringify(original)) as MaterialDependencyImpact
  const body = JSON.parse(JSON.stringify(request.value)) as MaterialDependencyResolve
  commandEpoch = store.sessionEpoch; commandGeneration = generation; commandAccessEpoch = currentTrainingAccessEpoch(); message.value = ''
  // 影响、目标 CAS 和六字段请求一并冻结；原键恢复不重新拼装今天的目标。
  void writer.run<unknown>(materialDependencyImpactPaths.resolve(snapshot.id), 'POST', body, value => {
    if (!alive || !readable.value || !opened.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation
      || commandAccessEpoch !== currentTrainingAccessEpoch() || !materialDependencyReceiptMatches(value, snapshot, body))
      throw new ApiError(0, 'MATERIAL_DEPENDENCY_RECEIPT_UNCERTAIN', '回执或访问条件无法核对，原影响、原请求与原键已保留，请恢复原请求。')
    clearInput(); message.value = `已登记「${materialDependencyChoiceLabels[value.resolution!.choice]}」。仅处理这一条直接依赖影响；历史内容和其他待处理影响保持原记录。`
    emit('changed'); void load()
  })
}
function retryCommand() {
  if (!readable.value || !opened.value || otherCommand.value || writer.busy.value || !writer.pending.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; commandAccessEpoch = currentTrainingAccessEpoch(); void writer.retry()
}
function versionLabel(target: MaterialDependencyTarget): string {
  return target.versionId ? `发布版 v${target.versionNumber} · ${target.versionId}`
    : target.revision === null ? '关系无版本／修订' : `审阅修订 ${target.revision}`
}
async function openTarget() {
  const target = baseline.value?.currentTarget
  if (blocked.value || !target || !['PREPARATION', 'QUESTION'].includes(target.domain)) return
  const epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), stamp = generation
  navigating.value = true; navigationProblem.value = ''
  try {
    // 导航仍交给工作区草稿守卫；不从目标 ID 猜测关系端点或标准审阅所属题目。
    const failure = await router.push({ name: target.domain === 'PREPARATION' ? 'prepare' : 'bank', query: { item: target.id } })
    if (failure && alive && readable.value && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
      && stamp === generation) navigationProblem.value = '页面未切换，请先处理本地理由或资料访问条件。'
  } catch {
    if (alive && readable.value && epoch === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
      && stamp === generation) navigationProblem.value = '暂时无法打开目标，请稍后重试。'
  } finally { navigating.value = false }
}
</script>

<template>
  <section ref="element" class="material-dependency-impacts" aria-label="材料更正直接依赖影响">
    <header><div><h3>材料更正 · 其他业务影响</h3><p class="field-help">核对准备材料、题目、知识关系和标准审阅的真实直接绑定。</p></div><NButton size="small" :disabled="!readable || locked || otherCommand" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起影响记录' : '查看并处理影响' }}</NButton></header>
    <p v-if="!identityValid" class="notice error" role="alert">摘录身份已变化，旧处理输入仍保留。请先返回原摘录处理输入或未决请求。</p>
    <p v-else-if="opened && !readable" class="notice" role="status">查看条件变化，目录与目标核对资格已清除；理由和原请求继续保留。</p>
    <div v-if="opened" v-show="readable" :id="panelId" class="dependency-content" :inert="!readable || undefined">
      <p class="field-help">这里处理六类直接绑定。知识采用链另走材料知识影响流程；本目录不是全部派生内容或删除清单。更正保留原文、采用版本和原受审记录。</p>
      <p class="field-help">处理只登记本次判断，不代替目标编辑。请先在对应业务页面完成实际更新、归档、审阅撤回或关系移除，再重读当前目标。其他影响及后续 AI 使用资格仍须各自核对。</p>
      <p v-if="paused" class="notice" role="status">请主动重新读取目录与原影响。旧确认已取消，本地处理理由保留。</p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text size="small" :loading="writer.busy.value" :disabled="!readable || otherCommand || writer.busy.value" @click="retryCommand">用原请求恢复</NButton></p>
      <p v-if="writer.locked.value" class="notice" role="status">原处理请求的结果尚未确认，目标、决定、理由和原键保持固定。</p>
      <div class="dependency-toolbar"><label><span>记录范围</span><NSelect :value="filter" :options="stateOptions" :disabled="blocked" aria-label="材料依赖影响记录范围" @update:value="changeFilter" /></label><NButton size="small" :loading="loading" :disabled="blocked" @click="load()">从首项重新读取</NButton></div>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}<NButton text size="small" :disabled="blocked || !lastRequest" @click="retryPage">重读这一页</NButton></p>
      <p v-if="loading" class="field-help" role="status">正在读取固定更正与当前目标元数据…</p>
      <template v-else-if="page">
        <p v-if="!page.items.length" class="field-help">本页没有{{ filter === 'PENDING' ? '待处理' : '' }}直接依赖影响；不能据此推断没有历史引用或其他派生影响。</p>
        <ol v-else class="dependency-list">
          <li v-for="impact in page.items" :key="impact.id">
            <header><strong>{{ materialDependencyBindingLabels[impact.binding] }}</strong><span class="status-pill">{{ impact.resolution ? '已处理' : '待处理' }}</span></header>
            <p>{{ materialDependencyDomainLabels[impact.target.domain] }} · {{ impact.target.title }}</p>
            <p class="field-help">固定采用：{{ versionLabel(impact.target) }}</p>
            <p class="field-help">本次读取目标：{{ materialDependencyStatusLabels[impact.currentTarget.status] }} · {{ versionLabel(impact.currentTarget) }}</p>
            <p v-if="impact.resolution" class="field-help">{{ materialDependencyChoiceLabels[impact.resolution.choice] }} · {{ new Date(impact.resolution.decidedAt).toLocaleString('zh-CN') }}</p>
            <NButton size="small" :disabled="blocked" :aria-pressed="selected?.id === impact.id" @click="selectImpact(impact)">{{ impact.resolution ? '查看历史决定' : '核对并处理' }}</NButton>
          </li>
        </ol>
        <nav v-if="previous.length || page.nextCursor" class="row-actions" aria-label="材料依赖影响分页"><NButton size="small" :disabled="blocked || !previous.length" @click="previousPage">上一页</NButton><NButton size="small" :disabled="blocked || !page.nextCursor" @click="nextPage">下一页</NButton><span class="field-help">第 {{ previous.length + 1 }} 页 · 每页最多20项，翻页不冻结后续处理</span></nav>
      </template>
      <section v-if="selected" class="dependency-selection" :aria-labelledby="`${panelId}-selection-title`">
        <header><h4 :id="`${panelId}-selection-title`">原影响核对与处理</h4><div class="row-actions"><NButton size="small" :loading="binding" :disabled="blocked" @click="bindTarget">重读原影响与当前目标</NButton><NButton text size="small" :disabled="blocked" @click="cancel">结束本次核对</NButton></div></header>
        <p v-if="!selectedReady || !baseline" class="notice" role="status">原选择与本地理由保留；当前目标尚未重新核对，暂不能提交。</p>
        <p v-if="detailProblem" class="notice error" role="alert">{{ detailProblem }}</p>
        <template v-if="selectedReady && baseline">
          <div class="dependency-correction"><h4>固定更正声明</h4><dl><div><dt>原摘录</dt><dd>{{ baseline.correction.excerptId }}</dd></div><div><dt>替代摘录</dt><dd>{{ baseline.correction.replacementExcerptId }}</dd></div><div><dt>固定修订</dt><dd>原摘录 {{ baseline.correction.sourceRevision }} · 替代摘录 {{ baseline.correction.replacementRevision }}</dd></div><div><dt>声明时间</dt><dd>{{ new Date(baseline.correction.createdAt).toLocaleString('zh-CN') }}</dd></div></dl><p class="dependency-reason">{{ baseline.correction.reason }}</p></div>
          <div class="dependency-targets">
            <section><h4>更正发生时的固定绑定</h4><p>{{ materialDependencyBindingLabels[baseline.binding] }} · {{ baseline.target.title }}</p><p class="field-help">{{ versionLabel(baseline.target) }}</p><p class="field-help">目标：{{ baseline.target.id }}<br>绑定：{{ baseline.bindingId }}<template v-if="baseline.taskId"><br>采用任务：{{ baseline.taskId }}</template></p></section>
            <section><h4>刚读取的当前目标</h4><p>{{ baseline.currentTarget.title }} · {{ materialDependencyStatusLabels[baseline.currentTarget.status] }}</p><p class="field-help">{{ versionLabel(baseline.currentTarget) }}</p><p v-if="baseline.currentTarget.versionId" class="field-help">目标 CAS 修订：{{ baseline.currentTarget.revision }}</p><NButton v-if="['PREPARATION', 'QUESTION'].includes(baseline.target.domain)" text size="small" :disabled="blocked" @click="openTarget">打开目标处理</NButton><p v-else class="field-help">{{ baseline.target.domain === 'RELATION' ? '请在知识关系入口核对这个确切关系；关系没有版本或修订。' : '请在所属题目的标准审阅入口核对这个确切审阅；审阅使用自身真实修订。' }}</p></section>
          </div>
          <section v-if="baseline.resolution" class="dependency-resolution"><h4>已保存的历史决定</h4><p>{{ materialDependencyChoiceLabels[baseline.resolution.choice] }} · {{ new Date(baseline.resolution.decidedAt).toLocaleString('zh-CN') }}</p><p class="dependency-reason">{{ baseline.resolution.reason }}</p><p class="field-help">决定时目标版本：{{ baseline.resolution.targetVersionId ?? '无版本' }} · 修订：{{ baseline.resolution.targetRevision ?? '无修订' }}。此处的当前目标可能已经再次变化，不能改写历史决定。</p></section>
        </template>
        <p v-if="navigationProblem" class="notice error" role="alert">{{ navigationProblem }}</p>
        <div v-if="selected.revision !== 0 && reason" class="notice" role="status"><p>这一影响已经处理，当前本地理由尚未提交，仍予保留：</p><p class="dependency-reason">{{ reason }}</p></div>
        <template v-if="selected.revision === 0">
          <label><span>本次处理方式</span><NSelect :value="choice" :options="choiceOptions" :disabled="blocked || !baseline" aria-label="材料依赖影响处理方式" @update:value="changeChoice" /></label>
          <p class="field-help">准备材料和题目须有实际正文更新，改标题或仅提升版本号不能证明已更新。审阅更新须真实修订上升；最终资格由服务器在本人数据锁内核对。归档、撤回和移除选项只记录已经发生的对应状态。</p>
          <label :for="`${panelId}-reason`"><span>本次处理理由</span><NInput :id="`${panelId}-reason`" :value="reason" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="blocked" placeholder="说明如何核对材料更正，以及为什么采用这一处理方式" @update:value="changeReason" /><span class="field-help">{{ Array.from(reason).length }} / 1000 字</span></label>
          <NCheckbox v-model:checked="confirmed" :disabled="blocked || !baseline || !selectedReady">我已核对固定更正、原绑定和当前目标，确认只登记这条影响的处理</NCheckbox>
          <p v-if="inputProblem" class="field-help" role="status">{{ inputProblem }}</p>
          <NButton type="primary" size="small" :disabled="blocked || Boolean(inputProblem)" :loading="writer.busy.value" @click="save">确认登记处理</NButton>
        </template>
      </section>
    </div>
  </section>
</template>

<style scoped>
.material-dependency-impacts, .dependency-content, .dependency-selection { display: grid; gap: 13px; min-width: 0; }
.material-dependency-impacts { padding: 15px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
header, .row-actions, .dependency-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 9px 12px; }
header { justify-content: space-between; align-items: start; } header > div:first-child { display: grid; gap: 7px; min-width: 0; }
h3, h4, p { margin: 0; } h3 { font-size: 14px; } h4 { font-size: 13px; } p, dd, small { overflow-wrap: anywhere; }
.dependency-toolbar > label { display: flex; align-items: center; gap: 10px; font-size: 12px; }
.dependency-toolbar .n-select { min-width: 120px; }
.dependency-list { display: grid; list-style: none; margin: 0; padding: 0; }
.dependency-list > li { display: grid; gap: 9px; padding: 14px 0; border-top: 1px solid var(--line); min-width: 0; }
.dependency-list > li > .n-button, .dependency-selection > .n-button { justify-self: start; }
.dependency-selection { padding-top: 17px; border-top: 1px solid var(--line); }
.dependency-selection > label { display: grid; gap: 8px; font-size: 12px; }
.dependency-correction, .dependency-resolution { display: grid; gap: 11px; padding-block: 14px; border-block: 1px solid var(--line); }
dl { display: grid; gap: 8px; margin: 0; font-size: 12px; } dl > div { display: grid; grid-template-columns: 80px minmax(0, 1fr); gap: 9px; }
dt { color: var(--muted); } dd { margin: 0; }
.dependency-targets { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; padding-block: 8px; }
.dependency-targets > section { display: grid; align-content: start; gap: 10px; min-width: 0; }
.dependency-targets .n-button { justify-self: start; }
.dependency-reason { white-space: pre-wrap; font-size: 12px; }
@media (max-width: 700px) { .dependency-targets { grid-template-columns: minmax(0, 1fr); } }
</style>
