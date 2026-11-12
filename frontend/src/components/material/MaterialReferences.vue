<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NSelect } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../../api/client'
import type { MaterialReferenceEntry, MaterialReferenceKind, MaterialReferencePage, MaterialReferenceSource } from '../../api/materialReferences'
import { materialReferenceAiPurpose, materialReferenceKindValid, materialReferenceLabels, materialReferenceOptions,
  materialReferencePageValid, materialReferencePath, materialReferenceRoute, materialReferenceSourceKey,
  materialReferenceSourceValid, materialReferenceStatusLabel, materialReferenceTitle } from '../../api/materialReferences'
import { aiPurposeLabels } from '../../api/ai'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ excerptId: string; attachmentId: string; source: MaterialReferenceSource }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter()
const panelId = `material-references-${useId()}`, element = ref<HTMLElement>()
const opened = ref(false), documentVisible = ref(document.visibilityState === 'visible'), elementVisible = ref(true)
const kind = ref<MaterialReferenceKind>(), page = shallowRef<MaterialReferencePage>()
const cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const loading = ref(false), problem = ref(''), navigationProblem = ref(''), opening = ref(false), paused = ref(false)
const validSource = computed(() => materialReferenceSourceValid(props.source, props.excerptId, props.attachmentId))
const sourceKey = computed(() => validSource.value ? materialReferenceSourceKey(props.source) : '')
const readable = computed(() => store.sessionActive && documentVisible.value && elementVisible.value && validSource.value
  && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const selectedAiPurpose = computed(() => materialReferenceAiPurpose(kind.value))
type Request = { excerptId: string; attachmentId: string; sourceKey: string; kind: MaterialReferenceKind;
  cursor?: string; previous: (string | undefined)[] }
const lastRequest = shallowRef<Request>()
const retryReady = computed(() => Boolean(lastRequest.value && lastRequest.value.excerptId === props.excerptId
  && lastRequest.value.attachmentId === props.attachmentId && lastRequest.value.sourceKey === sourceKey.value
  && lastRequest.value.kind === kind.value))
let alive = true, generation = 0, observer: MutationObserver | undefined

function clear() {
  generation++; page.value = undefined; cursor.value = undefined; previous.value = []
  loading.value = false; problem.value = ''; navigationProblem.value = ''
}
watch(sourceKey, () => { clear(); kind.value = undefined; paused.value = false }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode,
  () => access.active?.materialAccess], () => { clear(); paused.value = Boolean(kind.value) }, { flush: 'sync' })
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
  document.addEventListener('visibilitychange', visibilityChanged)
  elementVisibilityChanged()
  // 上层 v-show 不卸载摘录；只观察自身与祖先的可见性属性，隐藏时也必须撤销旧目录。
  observer = new MutationObserver(elementVisibilityChanged)
  for (let node: HTMLElement | null = element.value ?? null; node; node = node.parentElement) {
    observer.observe(node, { attributes: true, attributeFilter: ['style', 'class', 'hidden', 'inert', 'aria-hidden'] })
  }
})
onUnmounted(() => {
  alive = false; clear(); observer?.disconnect(); document.removeEventListener('visibilitychange', visibilityChanged)
})
function toggle() {
  if (!readable.value) return
  opened.value = !opened.value
  if (!opened.value) { clear(); paused.value = Boolean(kind.value) }
}
function selectKind(value: unknown) {
  if (!readable.value || !opened.value) return
  const next = materialReferenceKindValid(value) ? value : undefined
  if (next === kind.value) return
  clear(); kind.value = next; paused.value = false
  if (next) void load()
}
async function load(after?: string, stack: (string | undefined)[] = []) {
  if (!readable.value || !opened.value || !kind.value || loading.value) return
  const ticket = ++generation, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  const excerptId = props.excerptId, attachmentId = props.attachmentId, expectedSource = { ...props.source }
  const expectedKey = sourceKey.value, filter = kind.value
  lastRequest.value = { excerptId, attachmentId, sourceKey: expectedKey, kind: filter, cursor: after, previous: [...stack] }
  page.value = undefined; loading.value = true; problem.value = ''; navigationProblem.value = ''; paused.value = false
  const current = () => alive && readable.value && opened.value && ticket === generation && epoch === store.sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch() && excerptId === props.excerptId && attachmentId === props.attachmentId
    && expectedKey === sourceKey.value && filter === kind.value
  try {
    const result = await read<unknown>(materialReferencePath(excerptId, filter, after))
    if (!current()) return
    if (!materialReferencePageValid(result, excerptId, attachmentId, filter, after, expectedSource)) {
      throw new ApiError(502, 'MATERIAL_REFERENCES_INVALID', '摘录引用目录的根身份、分类、条目或分页无法核对，请重读这一页。')
    }
    page.value = result; cursor.value = after; previous.value = [...stack]
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (alive && ticket === generation) loading.value = false }
}
function refresh() { void load() }
function retry() {
  const request = lastRequest.value
  if (request && retryReady.value) void load(request.cursor, request.previous)
}
function nextPage() { if (page.value?.nextCursor) void load(page.value.nextCursor, [...previous.value, cursor.value]) }
function previousPage() { if (previous.value.length) void load(previous.value.at(-1), previous.value.slice(0, -1)) }
async function open(entry: MaterialReferenceEntry) {
  const target = materialReferenceRoute(entry)
  if (!target || !readable.value || !opened.value || opening.value || !page.value?.items.includes(entry)) return
  const ticket = generation, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  opening.value = true; navigationProblem.value = ''
  try {
    const failure = await router.push(target)
    if (failure && alive && readable.value && ticket === generation && epoch === store.sessionEpoch
      && accessEpoch === currentTrainingAccessEpoch()) navigationProblem.value = '页面未切换，请先处理当前草稿或资料访问条件。'
  } catch {
    if (alive && readable.value && ticket === generation && epoch === store.sessionEpoch
      && accessEpoch === currentTrainingAccessEpoch()) navigationProblem.value = '暂时无法打开此入口，请稍后重试。'
  } finally { opening.value = false }
}
function locationLabel(entry: MaterialReferenceEntry): string {
  return entry.targetKind === 'AI_TASK' ? '打开原任务' : entry.targetKind === 'PREPARATION' ? '打开准备确切版本'
    : entry.targetKind === 'RELATION' ? '打开原关系源端点' : '打开所属题目确切版本'
}
function pointerLabel(entry: MaterialReferenceEntry): string {
  return entry.targetKind === 'AI_TASK' ? '固定任务身份' : entry.targetKind === 'PREPARATION'
    ? entry.targetCurrent ? '准备当前版本' : '准备历史版本'
    : entry.targetKind === 'STANDARD_REVIEW' ? entry.targetCurrent ? '审阅当前修订' : '审阅历史修订' : '原关系证据身份'
}
</script>

<template>
  <section ref="element" class="material-references" aria-label="摘录已登记直接引用">
    <header><div><h3>已登记直接引用</h3><p class="field-help">展开后选择一个分类，仅查询这条摘录的登记元数据。</p></div><NButton size="small" :disabled="!readable" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起直接引用' : '查看直接引用' }}</NButton></header>
    <p v-if="!validSource" class="notice error" role="alert">摘录与附件身份无法核对，请重新读取确切摘录。</p>
    <p v-else-if="opened && !readable" class="notice" role="status">当前查看条件未就绪，旧引用目录已清除。</p>
    <div v-if="opened" v-show="readable" :id="panelId" class="reference-content" :inert="!readable || undefined" :aria-busy="loading">
      <p class="field-help">此处仅覆盖已登记的直接绑定，不是完整删除影响清单。目录不读取目标正文，不证明外发资格、实际发送、费用、内容采用或人工核实。</p>
      <label class="reference-filter"><span>引用分类</span><NSelect :value="kind" :options="materialReferenceOptions" clearable :disabled="!readable" placeholder="选择一个分类后读取" @update:value="selectKind" /></label>
      <div v-if="kind" class="row-actions"><NButton size="small" :loading="loading" :disabled="!readable || loading" @click="refresh">从首项重新读取</NButton><NButton v-if="paused && retryReady" size="small" :disabled="!readable || loading" @click="retry">按上次条件重读</NButton></div>
      <p v-if="paused" class="notice" role="status">查看条件或展开状态已变化，请主动重新读取；旧页面不会自动恢复。</p>
      <p v-if="!kind" class="field-help">每次只读取一个分类，其他分类与间接派生关系不在当前结果中。</p>
      <template v-if="kind === 'PREPARATION_VERSIONS'"><p class="field-help">每个确切准备版本分别登记；归档对象和历史版本保留。当前／历史只比较正文版本指针，不判断内容是否仍有效。</p></template>
      <template v-if="selectedAiPurpose"><p class="field-help">任务用途：{{ aiPurposeLabels[selectedAiPurpose] }}。登记发生于任务创建时；未发、在途、失败与成功任务均可能列出。成功不表示候选已采用，固定任务身份也不表示来源或授权仍有效。</p><p class="field-help">打开原任务继续经过资料守卫，不自动发送、重试、授权或调用模型。</p></template>
      <template v-if="kind === 'RELATION_EVIDENCE'"><p class="field-help">每条证据保留原关系及端点身份，关系移除后仍可显示。正式边存在不表示证据已核实。</p><p class="field-help">入口只打开原源端点；到达后请按下列原关系 ID 和证据 ID 手选核对，不会自动定位证据。</p></template>
      <template v-if="kind === 'STANDARD_REVIEW_EVIDENCE'"><p class="field-help">同一审阅修订的多份本摘录证据合为一项，不合并不同修订。状态是审阅当前状态，当前／历史只比较修订指针，不作为资格结论。</p><p class="field-help">入口只打开所属题目确切版本；请在题库按审阅 ID 和修订手选历史，不会自动展开参考或定位审阅修订。</p></template>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}<NButton text size="small" :disabled="!readable || loading || !retryReady" @click="retry">重读这一页</NButton></p>
      <p v-if="navigationProblem" class="notice error" role="alert">{{ navigationProblem }}</p>
      <p v-if="loading" class="field-help" role="status">正在读取所选分类的直接绑定元数据…</p>
      <template v-else-if="page && readable">
        <p class="reference-identity">摘录 {{ page.source.excerptId }} · 附件 {{ page.source.attachmentId }} · {{ page.source.origin === 'MANUAL' ? '手工摘录' : '解析片段摘录' }}</p>
        <p v-if="!page.items.length" class="reference-empty">此分类在本页没有已登记的直接引用，不表示其他分类或间接内容没有影响。</p>
        <ol v-else class="reference-list">
          <li v-for="entry in page.items" :key="`${entry.referenceId}/${entry.referenceRevision}`">
            <div class="reference-heading"><strong>{{ materialReferenceTitle(entry, page.kind) }}</strong><span>{{ pointerLabel(entry) }} · {{ materialReferenceStatusLabel(entry, page.kind) }}</span></div>
            <p v-if="entry.targetKind === 'PREPARATION'" class="reference-identity">准备对象 {{ entry.targetId }}<br />确切版本 {{ entry.targetVersionId }}</p>
            <p v-else-if="entry.targetKind === 'AI_TASK'" class="reference-identity">原任务 {{ entry.targetId }}</p>
            <template v-else-if="entry.targetKind === 'RELATION'"><p class="reference-identity">原关系 {{ entry.targetId }}<br />证据 {{ entry.referenceId }}</p><p class="reference-identity">原端点 {{ entry.anchorItemId }} → {{ entry.otherItemId }}</p></template>
            <template v-else><p class="reference-identity">审阅 {{ entry.targetId }} · 修订 {{ entry.referenceRevision }}</p><p class="reference-identity">所属题目 {{ entry.anchorItemId }}<br />确切题目版本 {{ entry.anchorVersionId }}</p></template>
            <NButton text size="small" :disabled="!readable || opening" @click="open(entry)">{{ locationLabel(entry) }}</NButton>
          </li>
        </ol>
        <nav v-if="previous.length || page.nextCursor" class="row-actions" aria-label="材料摘录引用分页"><NButton size="small" :disabled="!readable || loading || !previous.length" @click="previousPage">上一页</NButton><NButton size="small" :disabled="!readable || loading || !page.nextCursor" @click="nextPage">下一页</NButton><span class="field-help">{{ materialReferenceLabels[page.kind] }} · 第 {{ previous.length + 1 }} 页 · 每页最多20项</span></nav>
        <p class="field-help">每页按读取时的登记数据返回，翻页不冻结影响范围。需要重新核对时请从首项读取。</p>
      </template>
    </div>
  </section>
</template>

<style scoped>
.material-references, .reference-content { display: grid; gap: 12px; min-width: 0; }
.material-references { margin-top: 12px; padding-top: 14px; border-top: 1px solid var(--line); }
header, .reference-heading { display: flex; align-items: start; justify-content: space-between; flex-wrap: wrap; gap: 10px 12px; }
header > div { display: grid; gap: 7px; min-width: 0; } h3, p { margin: 0; } h3 { font-size: 14px; }
p, strong { overflow-wrap: anywhere; } .reference-filter { display: grid; gap: 8px; max-width: 360px; font-size: 13px; }
.reference-list { display: grid; list-style: none; padding: 0; margin: 0; min-width: 0; }
.reference-list > li { display: grid; gap: 8px; justify-items: start; min-width: 0; padding: 15px 0; border-top: 1px solid var(--line); }
.reference-heading { width: 100%; } .reference-heading strong { font-size: 13px; }
.reference-heading span, .reference-identity { color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.reference-empty { padding: 15px 0; color: var(--muted); font-size: 13px; }
.row-actions { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; }
@media (max-width: 650px) { .reference-filter { max-width: none; } }
</style>
