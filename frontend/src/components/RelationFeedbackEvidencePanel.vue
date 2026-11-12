<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import { aiDate } from '../api/ai'
import { questionDimensionLabels } from '../api/bank'
import { reviewChoiceLabels, reviewCoverageLabels } from '../api/review'
import type { RelationFeedbackEvidenceDetail, RelationFeedbackEvidenceEntry, RelationFeedbackEvidencePage } from '../api/relationFeedbackEvidence'
import { relationFeedbackEvidenceDetailValid, relationFeedbackEvidenceEntryValid, relationFeedbackEvidenceFieldLabels, relationFeedbackEvidencePageValid, relationFeedbackEvidencePaths } from '../api/relationFeedbackEvidence'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useWorkspaceStore } from '../state/workspace'
import RelationFeedbackEvidenceCreate from './RelationFeedbackEvidenceCreate.vue'

type PageDirection = 'refresh' | 'next' | 'back'
const props = withDefaults(defineProps<{ relationId: string; canEdit?: boolean; disabled?: boolean; refreshKey?: string | number }>(), { canEdit: false })
const emit = defineEmits<{ changed: []; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), viewId = useId()
const boundId = props.relationId, key = `relation-feedback-evidence:${viewId}`, panelId = `relation-feedback-evidence-panel-${viewId}`
const active = ref(true), opened = ref(false), creating = ref(false)
const changes = reactive({ dirty: false, locked: false })
const page = shallowRef<RelationFeedbackEvidencePage>(), cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const loading = ref(false), problem = ref(''), message = ref('')
const selected = shallowRef<RelationFeedbackEvidenceEntry>(), detail = shallowRef<RelationFeedbackEvidenceDetail>()
const detailLoading = ref(false), detailProblem = ref('')
const removedConfirmed = ref(false)
const identityValid = computed(() => /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(boundId) && props.relationId === boundId)
const readable = computed(() => active.value && identityValid.value && store.sessionActive && !props.disabled
  && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const blocked = computed(() => !readable.value || changes.locked)
const editable = computed(() => props.canEdit && page.value?.relationExists === true && !removedConfirmed.value)
const feedback = computed(() => detail.value?.source.feedback ?? undefined)
const fragmentLabels: Readonly<Record<string, string>> = relationFeedbackEvidenceFieldLabels
const answerLabels: Record<string, string> = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
const sourceStateLabels: Record<string, string> = {
  CURRENT: '本次读取已核对反馈修订及当时决定，以下保留保存时的原文。',
  SUPERSEDED: '反馈已有较新修订，以下继续展示当时保存的修订与片段。',
  REVIEW_REQUIRED: '反馈的审阅决定已变化，需要重新审视这条证据；以下保留当时决定与片段。',
  UNAVAILABLE: '当前无法核对原反馈，以下是保存时固定的原文快照。',
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
    const value = await read<RelationFeedbackEvidencePage>(relationFeedbackEvidencePaths.page(boundId, target, 20))
    if (!current()) return
    if (!relationFeedbackEvidencePageValid(value, boundId, target, 20)) throw new ApiError(502, 'RELATION_FEEDBACK_EVIDENCE_PAGE_INVALID', '反馈证据记录与当前关系或分页位置不一致，请重新读取。')
    // 读取失败不推进游标，重试继续核对同一页；创建表单始终留在列表之外。
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
function toggleDetail(entry: RelationFeedbackEvidenceEntry) {
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
    // 原文只随显式展开读取；保存回执和目录刷新不触发详情读取。
    const value = await read<RelationFeedbackEvidenceDetail>(relationFeedbackEvidencePaths.detail(boundId, entry.id))
    if (!current()) return
    if (!relationFeedbackEvidenceDetailValid(value, boundId, entry.id, entry)) throw new ApiError(502, 'RELATION_FEEDBACK_EVIDENCE_DETAIL_INVALID', '反馈快照与所选证据记录不一致，请重新读取。')
    // 已确认移除属于原关系身份，收起详情不能恢复追加；仅新目录回执可重新核对。
    if (!value.relationExists) removedConfirmed.value = true
    detail.value = value
  } catch (error) { if (current()) detailProblem.value = errorText(error) }
  finally { if (current()) detailLoading.value = false }
}
function startCreate() { if (!blocked.value && !loading.value && editable.value) creating.value = true }
function closeCreate() { if (!changes.locked && !changes.dirty) creating.value = false }
function saved(entry: RelationFeedbackEvidenceEntry) {
  if (!alive || !relationFeedbackEvidenceEntryValid(entry, boundId)) return
  // 先解除本表单聚合状态，再卸载；其他关系表单仍由各自状态键保护。
  childState({ dirty: false, locked: false }); creating.value = false
  message.value = '反馈证据已保存，所选片段、限定条件和当时决定已固定。'
  cursor.value = undefined; previous.value = []; invalidatePage()
  emit('changed'); if (readable.value && !changes.locked) void load()
}
function kindLabel(kind: string) { return kind === 'SELF_CHECK' ? '自检' : '手工录入外部意见' }
</script>

<template>
  <section class="feedback-evidence" aria-label="关系反馈证据">
    <NButton text size="small" :disabled="blocked" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起反馈证据' : '查看自检与外部意见证据' }}</NButton>
    <p v-if="!identityValid" class="notice error" role="alert">关系身份已变化，请返回原关系后处理保留的输入和请求。</p>
    <p v-if="opened && !readable" class="notice" role="status">资料访问待确认，反馈证据输入与未决请求继续保留。</p>
    <div v-if="opened" v-show="readable" :id="panelId" class="evidence-content" :inert="!readable || undefined" :aria-busy="loading">
      <p class="field-help">这里保存人工选择的自检或外部意见片段及其限定。保存和回看证据，不代表关系语义正确或个人能力得到认证。</p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="loading" role="status">正在读取反馈证据记录…</p>
      <div v-if="problem" class="notice error" role="alert"><p>{{ problem }}</p><NButton text size="small" :disabled="blocked || loading" @click="retryPage">重新读取这页</NButton></div>
      <template v-if="page">
        <p v-if="!page.relationExists || removedConfirmed" class="notice" role="status">正式关系已移除，已保存的反馈证据仍可回看，当前不能追加。</p>
        <p v-if="!page.items.length" class="field-help" role="status">{{ cursor ? '本页没有更多反馈证据记录。' : '还没有反馈证据。可从自检或手工录入的外部意见中选择原文，说明它与这条关系的联系。' }}</p>
        <article v-for="record in page.items" :key="record.id" class="evidence-record">
          <header><strong>{{ kindLabel(record.sourceKind) }} · {{ record.sourceCount }} 段</strong><time :datetime="record.createdAt">{{ aiDate(record.createdAt) }}</time></header>
          <p class="field-help">反馈修订 {{ record.feedbackRevision }} · {{ record.feedbackId }}</p>
          <p class="exact-text reason">{{ record.reason }}</p>
          <NButton text size="small" :disabled="blocked || loading" :aria-expanded="selected?.id === record.id" :aria-controls="`${panelId}-${record.id}`" @click="toggleDetail(record)">{{ selected?.id === record.id ? '收起反馈快照' : '查看这条证据的反馈快照' }}</NButton>
          <section v-if="selected?.id === record.id" :id="`${panelId}-${record.id}`" class="evidence-detail" :aria-busy="detailLoading" aria-label="固定反馈快照">
            <p v-if="detailLoading" role="status">正在读取这条反馈证据的固定原文…</p>
            <div v-if="detailProblem" class="notice error" role="alert"><p>{{ detailProblem }}</p><NButton text size="small" :disabled="blocked || detailLoading" @click="loadDetail">重新读取快照</NButton></div>
            <template v-if="detail && feedback">
              <p v-if="!detail.relationExists" class="notice" role="status">正式关系已移除；以下历史仍属于原关系。</p>
              <p :class="detail.sourceState === 'CURRENT' ? 'field-help' : 'notice'" role="status">{{ sourceStateLabels[detail.sourceState] }}</p>
              <dl>
                <dt>反馈来源</dt><dd>{{ kindLabel(detail.source.kind) }} · {{ feedback.feedbackId }} · 修订 {{ feedback.feedbackRevision }}</dd>
                <dt>训练会话</dt><dd>{{ feedback.answer.sessionId }}</dd>
                <dt>题目身份</dt><dd>{{ feedback.answer.questionId }}</dd>
                <dt>确切提交</dt><dd>{{ feedback.answer.answerId }} · 第 {{ feedback.answerNumber }} 版</dd>
                <dt>提交条件</dt><dd>{{ answerLabels[feedback.answerCondition] }} · {{ feedback.answerCondition }}</dd>
                <dt>观察维度</dt><dd>{{ questionDimensionLabels[feedback.dimension] }}</dd>
                <dt>观察范围</dt><dd>{{ reviewCoverageLabels[feedback.coverage] }}</dd>
                <dt>当时审阅决定</dt><dd>{{ feedback.decisionChoice ? reviewChoiceLabels[feedback.decisionChoice] : '尚未决定' }} · 决定修订 {{ feedback.decisionRevision }}</dd>
              </dl>
              <RouterLink class="text-link" :to="{ name: 'review', query: { ...feedback.answer, feedbackId: feedback.feedbackId, feedbackRevision: String(feedback.feedbackRevision) } }">前往这个确切反馈修订</RouterLink>
              <section class="fixed-conditions" aria-label="保存时的完整反馈限定">
                <h4>当时适用条件</h4><pre class="exact-text">{{ feedback.condition || '（空）' }}</pre>
                <h4>当时观察依据</h4><pre class="exact-text">{{ feedback.basis || '（空）' }}</pre>
                <h4>当时判断限制</h4><pre class="exact-text">{{ feedback.limitation || '（空）' }}</pre>
              </section>
              <article v-for="(fragment, index) in feedback.fragments" :key="`${fragment.field}/${fragment.start}/${fragment.end}`" class="source-fragment">
                <h4>{{ index + 1 }}. {{ fragmentLabels[fragment.field] }}</h4><p class="field-help">原字段 UTF-16 [{{ fragment.start }}, {{ fragment.end }})</p><pre class="exact-text">{{ fragment.quote }}</pre>
              </article>
              <p class="field-help">这些内容保留反馈提出时的观察和限制，需要结合原场景审阅；来源可核对不代表反馈判断或关系正确。</p>
              <NButton text size="small" :disabled="blocked || detailLoading" @click="loadDetail">重新核对这条证据的出处</NButton>
            </template>
          </section>
        </article>
      </template>
      <div class="row-actions"><NButton v-if="previous.length" size="small" :disabled="blocked || loading" @click="back">上一页</NButton><NButton v-if="page?.nextCursor" size="small" :disabled="blocked || loading" @click="next">下一页</NButton><NButton text size="small" :disabled="blocked || loading" @click="load(cursor)">刷新记录</NButton><NButton v-if="canEdit && !creating" size="small" :disabled="blocked || loading || !editable" @click="startCreate">追加自检或外部意见证据</NButton></div>
      <RelationFeedbackEvidenceCreate v-if="creating" :key="boundId" :relation-id="boundId" :can-edit="editable" :disabled="!readable" @state="childState" @saved="saved" @close="closeCreate" />
    </div>
  </section>
</template>

<style scoped>
.feedback-evidence { min-width: 0; margin-top: 8px; }
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
