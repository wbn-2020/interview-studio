<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NSelect } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { KnowledgeReferenceEntry, KnowledgeReferenceKind, KnowledgeReferencePage } from '../api/knowledgeReferences'
import { knowledgeReferenceLabels, knowledgeReferenceOptions, knowledgeReferencePageValid, knowledgeReferenceRoute,
  knowledgeReferenceStatusLabel, knowledgeReferenceTitle, referenceAiAdoption, referenceAiPurpose, referenceIdValid,
  referenceKindValid, referenceMemoryHistoryStatus, referenceVersionFiltered } from '../api/knowledgeReferences'
import { aiPurposeLabels } from '../api/ai'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

const props = defineProps<{ itemId: string; versionId: string; versionNumber: number; editing?: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter()
const kind = ref<KnowledgeReferenceKind>(), page = shallowRef<KnowledgeReferencePage>(), cursor = ref<string>()
const previous = ref<(string | undefined)[]>([])
const loading = ref(false), problem = ref(''), navigationProblem = ref(''), opening = ref(false)
const paused = ref(false), visible = ref(document.visibilityState === 'visible')
const readable = computed(() => store.sessionActive && visible.value && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const validSource = computed(() => referenceIdValid(props.itemId) && referenceIdValid(props.versionId)
  && Number.isSafeInteger(props.versionNumber) && props.versionNumber > 0)
const selectedAiPurpose = computed(() => referenceAiPurpose(kind.value))
const selectedMemoryHistory = computed(() => referenceMemoryHistoryStatus(kind.value))
let alive = true, generation = 0
let lastRequest: { cursor?: string; previous: (string | undefined)[] } | undefined

function clear() {
  generation++; page.value = undefined; cursor.value = undefined; previous.value = []
  loading.value = false; problem.value = ''; navigationProblem.value = ''; lastRequest = undefined
}
watch([() => props.itemId, () => props.versionId, () => props.versionNumber], () => {
  clear(); kind.value = undefined; paused.value = false
}, { flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => { clear(); paused.value = Boolean(kind.value) }, { flush: 'sync' })
watch(kind, () => {
  clear(); paused.value = false
  if (kind.value && readable.value && validSource.value) void load()
}, { flush: 'sync' })
function visibilityChanged() { visible.value = document.visibilityState === 'visible' }
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onUnmounted(() => { alive = false; clear(); document.removeEventListener('visibilitychange', visibilityChanged) })

function selectKind(value: unknown) { kind.value = referenceKindValid(value) ? value : undefined }
async function load(after?: string, stack: (string | undefined)[] = []) {
  if (!readable.value || !validSource.value || !kind.value || loading.value) return
  const ticket = ++generation, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  const itemId = props.itemId, versionId = props.versionId, versionNumber = props.versionNumber, filter = kind.value
  lastRequest = { cursor: after, previous: [...stack] }
  page.value = undefined; loading.value = true; problem.value = ''; navigationProblem.value = ''; paused.value = false
  const query = new URLSearchParams({ versionId, kind: filter, limit: '20' })
  if (after) query.set('cursor', after)
  const current = () => alive && readable.value && ticket === generation && epoch === store.sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch() && itemId === props.itemId && versionId === props.versionId
    && versionNumber === props.versionNumber && filter === kind.value
  try {
    const result = await read<unknown>(`/knowledge/items/${itemId}/references?${query}`)
    if (!current()) return
    // 目录身份和分页必须对应当前所选版本，不能把旧响应接纳为新分类的影响清单。
    if (!knowledgeReferencePageValid(result, itemId, versionId, versionNumber, filter, after)) {
      throw new ApiError(502, 'KNOWLEDGE_REFERENCES_INVALID', '引用目录的对象、版本或分页无法核对，请重新读取。')
    }
    page.value = result; cursor.value = after; previous.value = [...stack]
  } catch (error) { if (current()) { page.value = undefined; problem.value = errorText(error) } }
  finally { if (alive && ticket === generation) loading.value = false }
}
function refresh() { void load() }
function retry() { if (lastRequest) void load(lastRequest.cursor, lastRequest.previous) }
function nextPage() { if (page.value?.nextCursor) void load(page.value.nextCursor, [...previous.value, cursor.value]) }
function previousPage() { if (previous.value.length) void load(previous.value.at(-1), previous.value.slice(0, -1)) }
async function open(entry: KnowledgeReferenceEntry) {
  const target = knowledgeReferenceRoute(entry)
  if (!target || !readable.value || opening.value || !page.value?.items.includes(entry)) return
  const ticket = generation, epoch = store.sessionEpoch
  opening.value = true; navigationProblem.value = ''
  try {
    // 跳转继续沿用全局保稿和资料守卫；目录本身不恢复正文或授予访问权限。
    const failure = await router.push(target)
    if (failure && alive && readable.value && ticket === generation && epoch === store.sessionEpoch) {
      navigationProblem.value = '页面未切换，请先处理当前草稿或资料访问条件。'
    }
  } catch {
    if (alive && readable.value && ticket === generation && epoch === store.sessionEpoch) navigationProblem.value = '暂时无法打开此对象，请稍后重试。'
  } finally { opening.value = false }
}
function locationLabel(entry: KnowledgeReferenceEntry) {
  if (entry.targetKind === 'BOARD') return '打开当前白板'
  if (entry.targetKind === 'RELATION') return '打开关系端点'
  if (entry.targetKind === 'AI_TASK') return '打开原任务'
  if (entry.targetKind === 'STANDARD_REVIEW') return '打开所属题目确切版本'
  if (entry.targetKind === 'INTERVIEW_EVENT') return '打开确切安排修订'
  if (entry.targetKind === 'TODAY_PLACEMENT') return '打开今日入口'
  if (entry.targetKind === 'TRAINING_QUESTION') return '打开训练题目'
  if (entry.targetKind === 'TRAINING_ANSWER') return '打开确切训练提交'
  if (entry.targetKind === 'MEMORY_ATTEMPT') return '打开这次回忆'
  if (entry.targetKind === 'MEMORY_SOURCE_HISTORY') return '打开所属卡片确切版本'
  return entry.targetVersionId ? '打开确切版本' : '打开当前对象'
}
function currentLabel(entry: KnowledgeReferenceEntry, kind: KnowledgeReferenceKind) {
  if (entry.targetKind === 'RELATION') return entry.targetCurrent ? '正式关系仍存在' : '正式关系已移除'
  if (entry.targetKind === 'AI_TASK') return '固定任务身份'
  if (entry.targetKind === 'STANDARD_REVIEW') return entry.targetCurrent ? '审阅当前修订' : '审阅历史修订'
  if (entry.targetKind === 'INTERVIEW_EVENT') return entry.targetCurrent ? '安排当前修订' : '安排历史修订'
  if (entry.targetKind === 'TODAY_PLACEMENT') {
    if (kind === 'TODAY_PLACEMENT_HISTORY') return entry.targetCurrent ? '偏好当前修订' : '偏好历史修订'
    return '当前稳定偏好'
  }
  if (entry.targetKind === 'TRAINING_QUESTION') return kind === 'TRAINING_DRAFT_DIAGRAMS' ? '当前服务器草稿' : '固定训练题面'
  if (entry.targetKind === 'TRAINING_ANSWER') return '固定提交身份'
  if (entry.targetKind === 'MEMORY_ATTEMPT') return '固定尝试身份'
  if (entry.targetKind === 'MEMORY_SOURCE_HISTORY') return '固定历史事件身份'
  if (entry.targetRevision !== null) return entry.targetCurrent ? '当前保存修订' : '历史保存修订'
  if (entry.targetVersionId !== null) return entry.targetCurrent ? '目标当前版本' : '目标历史版本'
  return '稳定对象关联'
}
</script>

<template>
  <section class="knowledge-references" aria-label="知识已登记直接引用">
    <header><div><h3>引用影响 · 知识 v{{ versionNumber }}</h3><p class="field-help">选择分类查看已登记的直接引用与关联。</p></div><NButton size="small" :disabled="!readable || !validSource || !kind || loading" :loading="loading" @click="refresh">重新读取</NButton></header>
    <p class="notice">本目录只覆盖已登记的直接引用、训练／回忆快照及七类 AI 来源，其他输入、训练上下文和间接派生链仍未完整覆盖，不能作为完整删除影响清单。</p>
    <p v-if="editing" class="field-help">当前含草稿或本地修改。下列目录属于已发布 v{{ versionNumber }}，不代表未发布内容的引用范围。</p>
    <label class="reference-filter"><span>引用分类</span><NSelect :value="kind" :options="knowledgeReferenceOptions" clearable :disabled="!readable || !validSource" placeholder="选择一个分类后读取" @update:value="selectKind" /></label>
    <p v-if="!validSource" class="notice error" role="alert">知识版本身份无效，请重新打开一个已发布版本。</p>
    <p v-else-if="!readable || paused" class="notice" role="status">{{ !readable ? '当前查看条件未就绪，引用目录已清除。' : '查看条件已变化，请主动重新读取。' }}</p>
    <p v-else-if="!kind" class="field-help">每次只读取一个分类，切换分类会清除上一页结果。</p>
    <template v-if="kind">
      <p class="field-help">{{ referenceVersionFiltered(kind) ? `仅查询明确固定知识 v${versionNumber} 的来源绑定。` : '此分类关联稳定知识对象，所选知识版本不参与筛选。' }}当前／历史只比较版本或修订指针，不判定语义失效。</p>
      <p v-if="kind === 'BODY_LINKS'" class="field-help">只含已解析的发布版链接；草稿、未扫描历史及未解析链接不在本目录中。</p>
      <p v-if="kind === 'BOARD_SNAPSHOTS'" class="field-help">这是白板保存时固定的知识版本。打开入口进入当前白板，不恢复原修订，也不推断当前画布动态显示的知识版本。</p>
      <p v-if="kind === 'RELATIONS' || kind === 'RELATION_SUPPLEMENTS'" class="field-help">仅展示原关系身份和类型，不读取说明正文。端点入口进入当前知识对象；已移除关系的补充来源仍保留历史身份。</p>
      <template v-if="kind === 'STANDARD_REVIEW_EVIDENCE'">
        <p class="field-help">同一审阅修订的多份知识证据合为一项。标题来自所属题目确切版本；状态为审阅当前根状态，不代表支持资格、来源有效性或知识结论已核实。</p>
        <p class="field-help">入口仅打开所属题目确切版本；在题库中显式选择对应审阅记录及修订后查看原文。跳转不会自动展开参考或记录参考查看。</p>
      </template>
      <p v-if="kind === 'INTERVIEW_MATERIALS'" class="field-help">材料按安排修订保留，标题与状态属于该修订当时；当前／历史只比较安排修订指针。打开确切安排修订不会标记准备完成或知识掌握。</p>
      <template v-if="kind === 'TODAY_PLACEMENTS'">
        <p class="field-help">这里只显示本知识对象当前保存的安排偏好，不绑定所选发布版本，也不读取草稿或偏好历史。暂停或来源归档后仍可能保留旧偏好；存在偏好不代表仍有草稿、会进入当日或已经完成，空目录也不代表没有自动推荐。</p>
        <p class="field-help">入口进入今日工作台；到达后请手动选择“安排与建议”，再查找所属知识对应的来源。本入口不会自动定位这条偏好。</p>
      </template>
      <template v-if="kind === 'TODAY_PLACEMENT_HISTORY'">
        <p class="field-help">历史关联稳定知识对象，不按所选发布版本筛选。状态取当时的显示偏好，当前／历史只比较偏好修订，不表示当日入队或完成。</p>
        <p class="field-help">入口进入今日工作台；请手动进入“安排与建议”，找到所属知识对应来源后查看历史，并核对偏好ID与修订。入口不会自动定位这次修订。</p>
      </template>
      <p v-if="kind === 'TRAINING_QUESTION_SNAPSHOTS'" class="field-help">只含题面保存时登记的知识版本，不表示已查看参考或已掌握知识。</p>
      <p v-if="kind === 'TRAINING_DRAFT_DIAGRAMS'" class="field-help">只查询服务器当前图解草稿；替换或正式提交后旧草稿绑定会移除，历史正式提交另行保留。</p>
      <p v-if="kind === 'TRAINING_ANSWER_DIAGRAMS'" class="field-help">每次正式提交分别保留图解绑定；固定提交不代表最新回答，也不证明独立作答能力。</p>
      <p v-if="kind === 'MEMORY_ATTEMPT_SOURCES'" class="field-help">这是尝试开始时固定的知识来源，与模型上下文记忆分开。入口只打开已有尝试，不会自动揭示参考或开始新复习。</p>
      <template v-if="selectedMemoryHistory">
        <p class="field-help">本分类展示“{{ knowledgeReferenceLabels[kind] }}”的固定历史记录。观察指针与实际依赖分别登记，不能据此判断来源有效性；当时观察也不代表今天的当前版本。</p>
        <p class="field-help">入口只打开所属卡片确切版本；请手动进入“来源复核与排程”的历史，按事件ID核对。入口不会定位事件，也不会自动读取参考、评分、复核或调整排程。</p>
      </template>
      <template v-if="selectedAiPurpose">
        <p class="field-help">任务用途：{{ aiPurposeLabels[selectedAiPurpose] }}。标题和任务状态以本次读取为准；标题可能含用户问题，目录不读取完整清单、原文或结果。</p>
        <p v-if="referenceAiAdoption(kind)" class="field-help">此分类仅含已有候选采用记录的知识来源，不覆盖未形成采用记录的输入，不能作为全部外发清单。采用记录也不证明知识结论已核实。</p>
        <p v-else class="field-help">来源在创建任务时登记，输入登记不证明已经发送；成功也不代表已经采用或知识结论已经核实。</p>
        <p v-if="kind === 'AI_KNOWLEDGE_INPUTS' || kind === 'AI_QUESTION_INPUTS'" class="field-help">此分类读取已登记的任务知识输入，包含未采用、在途和失败等任务；未登记的历史输入不在范围内。</p>
        <p v-if="kind === 'AI_RELATION_INPUTS'" class="field-help">这是整个关系任务的知识输入，不能替代某条关系候选的逐条引文，也不能据此断定正式关系使用了哪项知识。</p>
        <p class="field-help">固定任务身份不表示来源当前可用或授权仍有效，也不能判断是否发生费用。打开原任务后仍须重新核验完整来源权限，不会自动重试或调用模型。</p>
      </template>
    </template>
    <p v-if="problem" class="notice error" role="alert">{{ problem }}<NButton text size="small" :disabled="!readable || loading" @click="retry">重读这一页</NButton></p>
    <p v-if="navigationProblem" class="notice error" role="alert">{{ navigationProblem }}</p>
    <p v-if="loading" role="status" class="field-help">正在核对所选知识与引用元数据…</p>
    <template v-else-if="page && readable">
      <p class="field-help">所选知识「{{ page.source.title }}」· v{{ page.source.versionNumber }} · {{ page.source.selectedVersionCurrent ? '当前发布版本' : '历史发布版本' }}{{ page.source.status === 'ARCHIVED' ? ' · 知识已归档' : '' }}</p>
      <p v-if="!page.items.length" class="reference-empty">此分类在本页没有已登记的直接引用，不代表其他分类或间接内容没有影响。</p>
      <ol v-else class="reference-list">
        <li v-for="entry in page.items" :key="`${entry.referenceId}/${entry.referenceRevision}`">
          <div class="reference-heading"><strong>{{ knowledgeReferenceTitle(entry) }}</strong><span>{{ currentLabel(entry, page.kind) }} · {{ knowledgeReferenceStatusLabel(entry, page.kind) }}</span></div>
          <p v-if="entry.targetVersionNumber !== null" class="field-help">目标版本 v{{ entry.targetVersionNumber }} · {{ entry.targetVersionId }}</p>
          <p v-if="entry.targetRevision !== null" class="field-help">{{ entry.targetKind === 'STANDARD_REVIEW' ? '审阅修订' : entry.targetKind === 'INTERVIEW_EVENT' ? '安排修订' : entry.targetKind === 'TODAY_PLACEMENT' ? '偏好修订' : '目标保存修订' }} {{ entry.targetRevision }}</p>
          <p class="reference-identity">{{ entry.targetKind === 'TODAY_PLACEMENT' ? '偏好' : entry.targetKind === 'MEMORY_SOURCE_HISTORY' ? '历史事件' : '对象' }} {{ entry.targetId }}<span v-if="entry.targetKind === 'RELATION'"> · 绑定 {{ entry.referenceId }}</span></p>
          <p v-if="entry.targetKind === 'RELATION'" class="reference-identity">端点 {{ entry.anchorItemId }} → {{ entry.otherItemId }}</p>
          <p v-if="entry.targetKind === 'STANDARD_REVIEW'" class="reference-identity">所属题目 {{ entry.anchorItemId }} · 确切版本 {{ entry.anchorVersionId }}</p>
          <p v-if="entry.targetKind === 'TODAY_PLACEMENT'" class="reference-identity">所属知识 {{ entry.anchorItemId }}</p>
          <p v-if="entry.targetKind === 'TRAINING_QUESTION' || entry.targetKind === 'TRAINING_ANSWER'" class="reference-identity">训练会话 {{ entry.anchorItemId }}<span v-if="entry.targetKind === 'TRAINING_ANSWER'"> · 题目 {{ entry.otherItemId }}</span></p>
          <p v-if="entry.targetKind === 'MEMORY_ATTEMPT'" class="reference-identity">回忆卡 {{ entry.anchorItemId }} · 开始时卡片版本 {{ entry.anchorVersionId }}</p>
          <p v-if="entry.targetKind === 'MEMORY_SOURCE_HISTORY'" class="reference-identity">所属卡片 {{ entry.anchorItemId }} · 固定卡片版本 {{ entry.anchorVersionId }} · {{ knowledgeReferenceLabels[page.kind] }}</p>
          <NButton size="small" text :disabled="!readable || opening" @click="open(entry)">{{ locationLabel(entry) }}</NButton>
        </li>
      </ol>
      <nav v-if="previous.length || page.nextCursor" class="row-actions" aria-label="引用目录分页"><NButton size="small" :disabled="!previous.length || !readable || loading" @click="previousPage">上一页</NButton><NButton size="small" :disabled="!page.nextCursor || !readable || loading" @click="nextPage">下一页</NButton><span class="field-help">{{ knowledgeReferenceLabels[page.kind] }} · 第 {{ previous.length + 1 }} 页</span></nav>
      <p class="field-help">分页按各次读取时的登记数据返回。新增、改版或移除后可重新读取；翻页不构成冻结的影响确认。</p>
    </template>
  </section>
</template>

<style scoped>
.knowledge-references { display: grid; gap: 14px; min-width: 0; margin-top: 20px; }
header, .reference-heading { display: flex; align-items: start; justify-content: space-between; gap: 12px; }
header > div { display: grid; gap: 7px; min-width: 0; } header > button { flex: none; }
h3 { margin: 0; font-size: 16px; } p { margin: 0; overflow-wrap: anywhere; }
.reference-filter { display: grid; gap: 8px; max-width: 360px; font-size: 13px; }
.reference-list { display: grid; gap: 0; list-style: none; padding: 0; margin: 0; min-width: 0; }
.reference-list > li { display: grid; justify-items: start; gap: 8px; min-width: 0; padding: 17px 0; border-top: 1px solid var(--line); }
.reference-heading { width: 100%; flex-wrap: wrap; } .reference-heading strong { font-size: 14px; overflow-wrap: anywhere; }
.reference-heading span, .reference-identity { color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.reference-empty { padding: 20px 0; color: var(--muted); font-size: 13px; }
@media (max-width: 650px) { header { flex-wrap: wrap; } .reference-filter { max-width: none; } }
</style>
