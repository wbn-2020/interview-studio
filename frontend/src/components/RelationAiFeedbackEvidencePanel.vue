<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import { aiDate } from '../api/ai'
import { questionDimensionLabels } from '../api/bank'
import { reviewChoiceLabels, reviewCoverageLabels } from '../api/review'
import type { RelationAiFeedbackEvidenceDetail, RelationAiFeedbackEvidenceEntry, RelationAiFeedbackEvidencePage } from '../api/relationAiFeedbackEvidence'
import { relationAiFeedbackEvidenceDetailValid, relationAiFeedbackEvidenceEntryValid, relationAiFeedbackEvidenceFieldLabels, relationAiFeedbackEvidencePageValid, relationAiFeedbackEvidencePaths } from '../api/relationAiFeedbackEvidence'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useWorkspaceStore } from '../state/workspace'
import RelationAiFeedbackEvidenceCreate from './RelationAiFeedbackEvidenceCreate.vue'

type PageDirection = 'refresh' | 'next' | 'back'
const props = withDefaults(defineProps<{ relationId: string; canEdit?: boolean; disabled?: boolean; refreshKey?: string | number }>(), { canEdit: false })
const emit = defineEmits<{ changed: []; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), viewId = useId()
const boundId = props.relationId, key = `relation-ai-feedback-evidence:${viewId}`, panelId = `relation-ai-feedback-evidence-panel-${viewId}`
const active = ref(true), opened = ref(false), creating = ref(false)
const changes = reactive({ dirty: false, locked: false })
const page = shallowRef<RelationAiFeedbackEvidencePage>(), cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const loading = ref(false), problem = ref(''), message = ref('')
const selected = shallowRef<RelationAiFeedbackEvidenceEntry>(), detail = shallowRef<RelationAiFeedbackEvidenceDetail>()
const detailLoading = ref(false), detailProblem = ref(''), removedConfirmed = ref(false)
const identityValid = computed(() => /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(boundId) && props.relationId === boundId)
const readable = computed(() => active.value && identityValid.value && store.sessionActive && !props.disabled
  && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const blocked = computed(() => !readable.value || changes.locked)
const editable = computed(() => props.canEdit && page.value?.relationExists === true && !removedConfirmed.value)
const source = computed(() => detail.value?.source)
const fragmentLabels: Readonly<Record<string, string>> = relationAiFeedbackEvidenceFieldLabels
const answerLabels: Record<string, string> = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
const sourceStateLabels: Record<string, string> = {
  CURRENT: '本次读取中，原 AI 报告与记录时的单项决定一致。',
  REVIEW_REQUIRED: '该观察的审阅决定已变化，需要重新审视这条证据；以下保留记录时的决定与片段。',
  UNAVAILABLE: '当前无法核对原 AI 观察，以下是保存时固定的报告与片段。',
}
let alive = true, generation = 0, pageSequence = 0, detailSequence = 0
let attemptedPage: { cursor?: string; direction: PageDirection } = { direction: 'refresh' }

watch([() => changes.dirty, () => changes.locked], ([dirty, locked]) => {
  store.changes.set(key, changes); emit('state', { dirty, locked })
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  generation++; invalidatePage()
  if (readable.value && opened.value && !changes.locked) void load(cursor.value)
}, { flush: 'sync' })
watch(() => props.refreshKey, () => {
  invalidatePage()
  if (readable.value && opened.value && !changes.locked) void load(cursor.value)
}, { flush: 'sync' })
watch(() => changes.locked, locked => { if (!locked && readable.value && opened.value && !page.value && !loading.value) void load(cursor.value) })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; generation++; pageSequence++; detailSequence++
  if (store.changes.get(key) === changes) store.changes.delete(key)
  emit('state', { dirty: false, locked: false })
})

function clearDetail() { detailSequence++; selected.value = undefined; detail.value = undefined; detailLoading.value = false; detailProblem.value = '' }
function invalidatePage() { pageSequence++; loading.value = false; page.value = undefined; problem.value = ''; clearDetail() }
function childState(value: { dirty: boolean; locked: boolean }) { if (alive) { changes.dirty = value.dirty; changes.locked = value.locked } }
function toggle() {
  if (blocked.value) return
  if (opened.value) {
    if (!store.canLeave([key])) return
    invalidatePage(); childState({ dirty: false, locked: false }); creating.value = false; opened.value = false; message.value = ''
  } else { opened.value = true; cursor.value = undefined; previous.value = []; void load() }
}
async function load(target?: string, direction: PageDirection = 'refresh') {
  if (blocked.value || !opened.value || loading.value) return
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++pageSequence, originalCursor = cursor.value
  attemptedPage = { cursor: target, direction }; loading.value = true; problem.value = ''; page.value = undefined; clearDetail()
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch && stamp === generation && sequence === pageSequence
  try {
    const value = await read<RelationAiFeedbackEvidencePage>(relationAiFeedbackEvidencePaths.page(boundId, target, 20))
    if (!current()) return
    if (!relationAiFeedbackEvidencePageValid(value, boundId, target, 20)) throw new ApiError(502, 'RELATION_AI_FEEDBACK_EVIDENCE_PAGE_INVALID', 'AI 反馈证据记录与当前关系或分页位置不一致，请重新读取。')
    // 读取失败不推进游标；创建表单保留在目录之外，刷新不卸载本地输入。
    if (direction === 'next') previous.value.push(originalCursor)
    else if (direction === 'back') previous.value.pop()
    removedConfirmed.value = !value.relationExists
    cursor.value = target; page.value = value
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (current()) loading.value = false }
}
function retryPage() { void load(attemptedPage.cursor, attemptedPage.direction) }
function next() { if (!blocked.value && !loading.value && page.value?.nextCursor) void load(page.value.nextCursor, 'next') }
function back() { if (!blocked.value && !loading.value && previous.value.length) void load(previous.value[previous.value.length - 1], 'back') }
function toggleDetail(entry: RelationAiFeedbackEvidenceEntry) {
  if (blocked.value || loading.value || !page.value?.items.includes(entry)) return
  if (selected.value?.id === entry.id) { clearDetail(); return }
  clearDetail(); selected.value = entry; void loadDetail()
}
async function loadDetail() {
  const entry = selected.value
  if (blocked.value || !opened.value || !entry) return
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++detailSequence
  detailLoading.value = true; detail.value = undefined; detailProblem.value = ''
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch && stamp === generation
    && sequence === detailSequence && selected.value?.id === entry.id
  try {
    // 单条快照只在主动展开时读取，元数据回执不能触发原文或完整任务读取。
    const value = await read<RelationAiFeedbackEvidenceDetail>(relationAiFeedbackEvidencePaths.detail(boundId, entry.id))
    if (!current()) return
    if (!relationAiFeedbackEvidenceDetailValid(value, boundId, entry.id, entry)) throw new ApiError(502, 'RELATION_AI_FEEDBACK_EVIDENCE_DETAIL_INVALID', 'AI 反馈快照与所选证据记录不一致，请重新读取。')
    // 关系已移除的确认持续到下一次有效目录回执，收起详情不会恢复追加。
    if (!value.relationExists) removedConfirmed.value = true
    detail.value = value
  } catch (error) { if (current()) detailProblem.value = errorText(error) }
  finally { if (current()) detailLoading.value = false }
}
function startCreate() { if (!blocked.value && !loading.value && editable.value) creating.value = true }
function closeCreate() { if (!changes.locked && !changes.dirty) creating.value = false }
function saved(entry: RelationAiFeedbackEvidenceEntry) {
  if (!alive || !relationAiFeedbackEvidenceEntryValid(entry, boundId)) return
  // 先解除本表单聚合状态，再卸载；其他证据表单继续使用各自的状态键。
  childState({ dirty: false, locked: false }); creating.value = false
  message.value = 'AI 反馈证据已保存，报告身份、所选片段、限定条件与当时决定已固定。'
  cursor.value = undefined; previous.value = []; invalidatePage()
  emit('changed'); if (readable.value && !changes.locked) void load()
}
</script>

<template>
  <section class="ai-feedback-evidence" aria-label="关系 AI 反馈证据">
    <NButton text size="small" :disabled="blocked" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起 AI 反馈证据' : '查看 AI 观察证据' }}</NButton>
    <p v-if="!identityValid" class="notice error" role="alert">关系身份已变化，请返回原关系后处理保留的输入和请求。</p>
    <p v-if="opened && !readable" class="notice" role="status">资料访问待确认，AI 反馈证据输入与未决请求继续保留。</p>
    <div v-if="opened" v-show="readable" :id="panelId" class="evidence-content" :inert="!readable || undefined" :aria-busy="loading">
      <p class="field-help">这里保留人工选择的 AI 观察片段及限定，始终标记为 AI 未审。保存和回看证据，不代表关系语义正确或个人能力得到认证。</p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="loading" role="status">正在读取 AI 反馈证据记录…</p>
      <div v-if="problem" class="notice error" role="alert"><p>{{ problem }}</p><NButton text size="small" :disabled="blocked || loading" @click="retryPage">重新读取这页</NButton></div>
      <template v-if="page">
        <p v-if="!page.relationExists || removedConfirmed" class="notice" role="status">正式关系已移除，已保存的 AI 反馈证据仍可回看，当前不能追加。</p>
        <p v-if="!page.items.length" class="field-help" role="status">{{ cursor ? '本页没有更多 AI 反馈证据记录。' : '还没有 AI 反馈证据。可从已生成的评价观察中选择原文，说明它与这条关系的联系。' }}</p>
        <article v-for="record in page.items" :key="record.id" class="evidence-record">
          <header><strong>AI 未审观察 {{ record.feedbackId }} · {{ record.sourceCount }} 段</strong><time :datetime="record.createdAt">{{ aiDate(record.createdAt) }}</time></header>
          <p class="field-help">评价任务 {{ record.taskId }} · 成功尝试 {{ record.attemptId }}</p>
          <p class="exact-text reason">{{ record.reason }}</p>
          <NButton text size="small" :disabled="blocked || loading" :aria-expanded="selected?.id === record.id" :aria-controls="`${panelId}-${record.id}`" @click="toggleDetail(record)">{{ selected?.id === record.id ? '收起 AI 观察快照' : '查看这条证据的 AI 观察快照' }}</NButton>
          <section v-if="selected?.id === record.id" :id="`${panelId}-${record.id}`" class="evidence-detail" :aria-busy="detailLoading" aria-label="固定 AI 观察快照">
            <p v-if="detailLoading" role="status">正在读取这条 AI 反馈证据的固定原文…</p>
            <div v-if="detailProblem" class="notice error" role="alert"><p>{{ detailProblem }}</p><NButton text size="small" :disabled="blocked || detailLoading" @click="loadDetail">重新读取快照</NButton></div>
            <template v-if="detail && source">
              <p v-if="!detail.relationExists" class="notice" role="status">正式关系已移除；以下历史仍属于原关系。</p>
              <p class="notice" role="status"><strong>AI 未审 · {{ source.level }}</strong></p>
              <p :class="detail.sourceState === 'CURRENT' ? 'field-help' : 'notice'" role="status">{{ sourceStateLabels[detail.sourceState] }} 来源状态仅用于核对原报告和决定，不证明直接依据仍为当前版本或技术结论正确。</p>
              <p v-if="source.decisionChoice === 'DISPUTED'" class="notice" role="status"><strong>记录时已提出争议。</strong>以下片段保留争议条件，需要结合原报告重新审视。</p>
              <p v-else-if="source.decisionChoice === 'IGNORED'" class="notice" role="status"><strong>记录时已忽略此观察。</strong>保存片段不会撤销原决定，也不表示已接受其判断。</p>
              <dl>
                <dt>评价任务</dt><dd>{{ source.taskId }}</dd>
                <dt>成功尝试</dt><dd>{{ source.attemptId }}</dd>
                <dt>原观察</dt><dd>{{ source.feedbackId }}</dd>
                <dt>报告 SHA-256</dt><dd>{{ source.reportHash }}</dd>
                <dt>训练会话</dt><dd>{{ source.answer.sessionId }}</dd>
                <dt>题目身份</dt><dd>{{ source.answer.questionId }}</dd>
                <dt>确切提交</dt><dd>{{ source.answer.answerId }} · 第 {{ source.answerNumber }} 版</dd>
                <dt>提交条件</dt><dd>{{ answerLabels[source.answerCondition] }} · {{ source.answerCondition }}</dd>
                <dt>观察维度</dt><dd>{{ questionDimensionLabels[source.dimension] }}</dd>
                <dt>观察范围</dt><dd>{{ reviewCoverageLabels[source.coverage] }}</dd>
                <dt>当时审阅决定</dt><dd>{{ source.decisionChoice ? reviewChoiceLabels[source.decisionChoice] : '尚未决定' }} · 决定修订 {{ source.decisionRevision }}</dd>
              </dl>
              <div class="row-actions"><RouterLink class="text-link" :to="{ name: 'review', query: { ...source.answer, taskId: source.taskId, observationId: source.feedbackId } }">前往原评价任务的这条观察</RouterLink><RouterLink class="text-link" :to="{ name: 'ai', query: { item: source.taskId } }">查看原 AI 任务历史</RouterLink></div>
              <section class="fixed-conditions" aria-label="原报告与观察的完整限定">
                <h4>原报告限制</h4><pre class="exact-text">{{ source.reportLimitations }}</pre>
                <h4>原报告校验说明</h4><pre class="exact-text">{{ source.validationNote }}</pre>
                <h4>当时适用条件</h4><pre class="exact-text">{{ source.condition || '（空）' }}</pre>
                <h4>当时观察依据</h4><pre class="exact-text">{{ source.basis || '（空）' }}</pre>
                <h4>当时判断限制</h4><pre class="exact-text">{{ source.limitation || '（空）' }}</pre>
              </section>
              <article v-for="(fragment, index) in source.fragments" :key="`${fragment.field}/${fragment.start}/${fragment.end}`" class="source-fragment">
                <h4>{{ index + 1 }}. {{ fragmentLabels[fragment.field] }}</h4><p class="field-help">原字段 UTF-16 [{{ fragment.start }}, {{ fragment.end }})</p><pre class="exact-text">{{ fragment.quote }}</pre>
              </article>
              <p class="field-help">原报告、成功尝试与提交条件固定留存。后续新回答或新评价任务不会替换这里的历史来源。</p>
              <NButton text size="small" :disabled="blocked || detailLoading" @click="loadDetail">重新核对这条证据的出处</NButton>
            </template>
          </section>
        </article>
      </template>
      <div class="row-actions"><NButton v-if="previous.length" size="small" :disabled="blocked || loading" @click="back">上一页</NButton><NButton v-if="page?.nextCursor" size="small" :disabled="blocked || loading" @click="next">下一页</NButton><NButton text size="small" :disabled="blocked || loading" @click="load(cursor)">刷新记录</NButton><NButton v-if="canEdit && !creating" size="small" :disabled="blocked || loading || !editable" @click="startCreate">追加 AI 观察证据</NButton></div>
      <RelationAiFeedbackEvidenceCreate v-if="creating" :key="boundId" :relation-id="boundId" :can-edit="editable" :disabled="!readable" @state="childState" @saved="saved" @close="closeCreate" />
    </div>
  </section>
</template>

<style scoped>
.ai-feedback-evidence { min-width: 0; margin-top: 8px; }
.evidence-content, .evidence-record, .evidence-detail, .fixed-conditions, .source-fragment { display: grid; gap: 12px; min-width: 0; }
.evidence-content { margin-top: 12px; padding-top: 14px; border-top: 1px solid var(--line); }
.evidence-record, .fixed-conditions, .source-fragment { padding-top: 15px; border-top: 1px solid var(--line); }
.evidence-detail { padding: 14px; background: var(--surface-soft); border-radius: 5px; }
header { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 8px 14px; }
h4, p, pre { margin: 0; }h4, header strong { font-size: 13px; }time { color: var(--muted); font-size: 12px; }
.exact-text { white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.8 var(--font-body, sans-serif); }.reason { max-width: 75ch; }
dl { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 7px 12px; margin: 0; font-size: 12px; }dt { color: var(--muted); }dd { margin: 0; overflow-wrap: anywhere; }
.row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 9px 13px; }.field-help, .notice, .text-link, h4, strong { overflow-wrap: anywhere; }.text-link { font-size: 13px; }.evidence-record > .n-button, .evidence-detail > .n-button { justify-self: start; }
@media (max-width: 560px) { dl { grid-template-columns: 1fr; gap: 5px; }dd { margin-bottom: 6px; }.evidence-detail { padding: 10px; } }
</style>
