<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import type { RelationEvidenceDetail, RelationEvidenceEntry, RelationEvidencePage } from '../api/relationEvidence'
import { relationEvidenceDetailValid, relationEvidencePageValid, relationEvidencePaths } from '../api/relationEvidence'
import { aiDate, aiTrainingDraftFieldLabels } from '../api/ai'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useWorkspaceStore } from '../state/workspace'
import RelationEvidenceCreate from './RelationEvidenceCreate.vue'

const props = withDefaults(defineProps<{ relationId: string; canEdit?: boolean; disabled?: boolean; refreshKey?: string | number }>(), { canEdit: false })
const emit = defineEmits<{ changed: []; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), viewId = useId()
const boundId = props.relationId, key = `relation-evidence:${viewId}`, panelId = `relation-evidence-panel-${viewId}`
const active = ref(true), opened = ref(false), creating = ref(false)
const changes = reactive({ dirty: false, locked: false })
const page = shallowRef<RelationEvidencePage>(), cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const loading = ref(false), problem = ref(''), message = ref('')
const selected = shallowRef<RelationEvidenceEntry>(), detail = shallowRef<RelationEvidenceDetail>()
const detailLoading = ref(false), detailProblem = ref('')
const identityValid = computed(() => /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(boundId) && props.relationId === boundId)
const readable = computed(() => active.value && identityValid.value && store.sessionActive && !props.disabled
  && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const blocked = computed(() => !readable.value || changes.locked)
const editable = computed(() => props.canEdit && page.value?.relationExists === true && detail.value?.relationExists !== false)
const answerLabels: Record<string, string> = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
let alive = true, generation = 0, pageSequence = 0, detailSequence = 0

watch([() => changes.dirty, () => changes.locked], ([dirty, locked]) => {
  store.changes.set(key, changes); emit('state', { dirty, locked })
}, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  generation++; pageSequence++; loading.value = false; page.value = undefined; problem.value = ''; clearDetail()
  if (readable.value && opened.value && !changes.locked) void load()
}, { flush: 'sync' })
watch(() => props.refreshKey, () => {
  pageSequence++; loading.value = false; page.value = undefined; clearDetail()
  if (readable.value && opened.value && !changes.locked) void load()
})
watch(() => changes.locked, locked => { if (!locked && readable.value && opened.value && !page.value && !loading.value) void load() })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; generation++; pageSequence++; detailSequence++
  if (store.changes.get(key) === changes) store.changes.delete(key)
  emit('state', { dirty: false, locked: false })
})

function clearDetail() { detailSequence++; selected.value = undefined; detail.value = undefined; detailLoading.value = false; detailProblem.value = '' }
function childState(value: { dirty: boolean; locked: boolean }) { changes.dirty = value.dirty; changes.locked = value.locked }
function toggle() {
  if (blocked.value) return
  if (opened.value) {
    if (!store.canLeave([key])) return
    pageSequence++; loading.value = false; clearDetail(); creating.value = false; opened.value = false; page.value = undefined
  } else { opened.value = true; cursor.value = undefined; previous.value = []; void load() }
}
async function load() {
  if (blocked.value || !opened.value) return
  const epoch = store.sessionEpoch, stamp = generation, position = cursor.value, sequence = ++pageSequence
  loading.value = true; problem.value = ''; page.value = undefined; clearDetail()
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch && stamp === generation
    && sequence === pageSequence && position === cursor.value
  try {
    const value = await read<RelationEvidencePage>(relationEvidencePaths.page(boundId, position))
    if (!current()) return
    if (!relationEvidencePageValid(value, boundId, position)) throw new ApiError(502, 'RELATION_EVIDENCE_PAGE_INVALID', '证据记录与当前关系或分页位置不一致，请重新读取。')
    page.value = value
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (current()) loading.value = false }
}
function toggleDetail(entry: RelationEvidenceEntry) {
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
    // 只有用户展开的单条证据读取固定快照；列表读取不会连带请求其他原文。
    const value = await read<RelationEvidenceDetail>(relationEvidencePaths.detail(boundId, entry.id))
    if (!current()) return
    if (!relationEvidenceDetailValid(value, boundId, entry.id, entry)) {
      throw new ApiError(502, 'RELATION_EVIDENCE_DETAIL_INVALID', '证据原文与所选历史记录不一致，请重新读取。')
    }
    detail.value = value
  } catch (error) { if (current()) detailProblem.value = errorText(error) }
  finally { if (current()) detailLoading.value = false }
}
function next() {
  if (blocked.value || loading.value || !page.value?.nextCursor) return
  previous.value.push(cursor.value); cursor.value = page.value.nextCursor; void load()
}
function back() { if (!blocked.value && !loading.value && previous.value.length) { cursor.value = previous.value.pop(); void load() } }
function startCreate() { if (!blocked.value && !loading.value && editable.value) creating.value = true }
function closeCreate() { if (!changes.locked && !changes.dirty) creating.value = false }
function saved(entry: RelationEvidenceEntry) {
  if (!alive || entry.relationId !== boundId) return
  creating.value = false; message.value = '证据记录已保存，所选原文与提交条件已固定。'
  cursor.value = undefined; previous.value = []; page.value = undefined; clearDetail()
  emit('changed'); if (readable.value && !changes.locked) void load()
}
</script>

<template>
  <section class="relation-evidence" aria-label="关系资料与训练证据">
    <NButton text size="small" :disabled="blocked" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起关系证据' : '查看资料与训练证据' }}</NButton>
    <p v-if="!identityValid" class="notice error" role="alert">关系身份已变化，请返回原关系后处理保留的输入和请求。</p>
    <p v-if="opened && !readable" class="notice" role="status">资料访问待确认，当前证据输入和未决请求继续保留。</p>
    <div v-if="opened" v-show="readable" :id="panelId" class="evidence-content" :inert="!readable || undefined" :aria-busy="loading">
      <p class="field-help">这里保留人工选择的资料摘录或确切训练提交片段。回看原文与出处，不表示关系语义或训练自述已经核实。</p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="loading" role="status">正在读取证据记录…</p>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}<NButton text size="small" :disabled="blocked || loading" @click="load">重新读取</NButton></p>
      <template v-if="page">
        <p v-if="!page.relationExists" class="notice" role="status">正式关系已移除，已保存的证据历史仍可回看，当前不能追加。</p>
        <p v-if="!page.items.length" class="field-help">{{ cursor ? '本页没有更多证据记录。' : '还没有资料或训练证据。可选择原文并说明它与这条关系的联系。' }}</p>
        <article v-for="record in page.items" :key="record.id" class="evidence-record">
          <header><strong>{{ record.sourceKind === 'MATERIAL' ? '资料摘录' : '训练提交片段' }} · {{ record.sourceCount }} 段</strong><time :datetime="record.createdAt">{{ aiDate(record.createdAt) }}</time></header>
          <p class="evidence-reason">{{ record.reason }}</p>
          <NButton text size="small" :disabled="blocked || loading" :aria-expanded="selected?.id === record.id" :aria-controls="`${panelId}-${record.id}`" @click="toggleDetail(record)">{{ selected?.id === record.id ? '收起固定原文' : '查看这条证据的固定原文' }}</NButton>
          <section v-if="selected?.id === record.id" :id="`${panelId}-${record.id}`" class="evidence-detail" :aria-busy="detailLoading" aria-label="确切证据快照">
            <p v-if="detailLoading" role="status">正在读取这条证据的原文快照…</p>
            <p v-if="detailProblem" class="notice error" role="alert">{{ detailProblem }}<NButton text size="small" :disabled="blocked || detailLoading" @click="loadDetail">重新读取原文</NButton></p>
            <template v-if="detail">
              <p v-if="!detail.relationExists" class="notice" role="status">正式关系已移除；以下保留当时选择的原文。</p>
              <p class="field-help">{{ detail.sourceAvailable ? '本次读取已核对原出处与保存的片段。' : '当前无法核对全部原出处，以下是保存时固定的原文快照。' }} 来源可核对不代表内容或关系正确。</p>
              <template v-if="detail.source.kind === 'MATERIAL'">
                <article v-for="(material, index) in detail.source.materials" :key="material.excerptId" class="source-block">
                  <h4>{{ index + 1 }}. {{ material.fileName }}</h4><p class="field-help">{{ material.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ material.locator }}</p>
                  <dl><dt>确切摘录</dt><dd>{{ material.excerptId }}</dd><dt>来源附件</dt><dd>{{ material.attachmentId }}</dd><dt>原件 SHA-256</dt><dd>{{ material.sha256 }}</dd><dt>解析执行</dt><dd>{{ material.runId ?? '无（手工摘录）' }}</dd><dt>解析片段</dt><dd>{{ material.segmentId ?? '无（手工摘录）' }}</dd><dt>原片段位置</dt><dd>{{ material.startOffset === null ? '手工摘录无原片段区间' : `UTF-16 [${material.startOffset}, ${material.endOffset})` }}</dd></dl>
                  <pre class="exact-text">{{ material.text }}</pre>
                  <RouterLink class="text-link" :to="{ name: 'materials', query: { attachment: material.attachmentId, run: material.runId ?? undefined, excerpt: material.excerptId } }">前往这个确切摘录</RouterLink>
                </article>
              </template>
              <template v-else-if="detail.source.training">
                <dl><dt>训练会话</dt><dd>{{ detail.source.training.sessionId }}</dd><dt>题目身份</dt><dd>{{ detail.source.training.questionId }}</dd><dt>确切提交</dt><dd>{{ detail.source.training.answerId }} · 第 {{ detail.source.training.answerNumber }} 版</dd><dt>提交条件</dt><dd>{{ answerLabels[detail.source.training.answerCondition] }} · {{ detail.source.training.answerCondition }}</dd></dl>
                <RouterLink class="text-link" :to="{ name: 'training', query: { item: detail.source.training.sessionId, question: detail.source.training.questionId, answer: detail.source.training.answerId } }">前往这次确切提交</RouterLink>
                <article v-for="(fragment, index) in detail.source.training.fragments" :key="`${fragment.field}/${fragment.elementId}/${fragment.start}/${index}`" class="source-block"><h4>{{ index + 1 }}. {{ aiTrainingDraftFieldLabels[fragment.field] }}</h4><p class="field-help">{{ fragment.elementId ? `图元素 ${fragment.elementId} · ` : '' }}原字段 UTF-16 [{{ fragment.start }}, {{ fragment.end }})</p><pre class="exact-text">{{ fragment.quote }}</pre></article>
                <p class="field-help">这里只展示保存时选择的回答片段；训练自述、假设和辅助条件仍需结合实际核实。</p>
              </template>
              <NButton text size="small" :disabled="blocked || detailLoading" @click="loadDetail">重新核对这条证据的出处</NButton>
            </template>
          </section>
        </article>
      </template>
      <div class="row-actions"><NButton v-if="previous.length" size="small" :disabled="blocked || loading" @click="back">上一页</NButton><NButton v-if="page?.nextCursor" size="small" :disabled="blocked || loading" @click="next">下一页</NButton><NButton text size="small" :disabled="blocked || loading" @click="load">刷新记录</NButton><NButton v-if="canEdit && !creating" size="small" :disabled="blocked || loading || !editable" @click="startCreate">追加资料或训练证据</NButton></div>
      <RelationEvidenceCreate v-if="creating" :key="boundId" :relation-id="boundId" :can-edit="editable" :disabled="!readable" @state="childState" @saved="saved" @close="closeCreate" />
    </div>
  </section>
</template>

<style scoped>
.relation-evidence { min-width: 0; margin-top: 8px; }.evidence-content, .evidence-record, .evidence-detail, .source-block { display: grid; gap: 12px; min-width: 0; }.evidence-content { margin-top: 12px; padding-top: 14px; border-top: 1px solid var(--line); }.evidence-record, .source-block { padding-top: 15px; border-top: 1px solid var(--line); }.evidence-detail { padding: 14px; background: var(--surface-soft); border-radius: 5px; }
header { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 8px 14px; }h4, p { margin: 0; }h4, header strong { font-size: 13px; }time { font-size: 12px; color: var(--muted); }.evidence-reason, .exact-text { white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.8 var(--font-body, sans-serif); }.exact-text { margin: 0; padding: 12px; border: 1px solid var(--line); border-radius: 4px; }
dl { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 7px 12px; margin: 0; font-size: 12px; }dt { color: var(--muted); }dd { margin: 0; overflow-wrap: anywhere; }.row-actions { display: flex; flex-wrap: wrap; gap: 9px 13px; align-items: center; }.field-help, .text-link, .notice, h4 { overflow-wrap: anywhere; }.text-link { font-size: 13px; }.evidence-record > .n-button, .evidence-detail > .n-button { justify-self: start; }@media (max-width: 560px) { dl { grid-template-columns: 1fr; gap: 5px; }dd { margin-bottom: 6px; }.evidence-detail { padding: 10px; } }
</style>
