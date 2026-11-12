<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton, NCheckbox, NInput } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import type { ItemDetail, ItemSummary } from '../api/client'
import type { RelationSupplement, RelationSupplementCreate, RelationSupplementPage, RelationSupplementSource } from '../api/relationSupplement'
import { relationSupplementMatches, relationSupplementPageValid, relationSupplementProblem, relationSupplementSourceValid } from '../api/relationSupplement'
import { aiDate } from '../api/ai'
import { useCommand } from '../composables/useCommand'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useWorkspaceStore } from '../state/workspace'
import KnowledgePicker from './KnowledgePicker.vue'

const props = withDefaults(defineProps<{ relationId: string; canEdit?: boolean; disabled?: boolean; refreshKey?: string | number }>(), { canEdit: false })
const emit = defineEmits<{ changed: []; locked: [value: boolean]; state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), viewId = useId()
const key = `relation-supplements:${viewId}`, panelId = `relation-supplements-panel-${viewId}`
const boundId = ref(props.relationId), active = ref(true), opened = ref(false), formOpen = ref(false)
const reason = ref(''), confirmed = ref(false), selected = ref<RelationSupplementSource[]>([])
const pickerOpen = ref(false), pickerGeneration = ref(0), selecting = ref(false), selectionProblem = ref('')
const page = shallowRef<RelationSupplementPage>(), cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const loading = ref(false), problem = ref(''), message = ref(''), deferred = shallowRef<RelationSupplement>()
const validId = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const identityValid = computed(() => validId(boundId.value) && boundId.value === props.relationId)
const readable = computed(() => active.value && identityValid.value && store.sessionActive && !props.disabled
  && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const dirty = computed(() => Boolean(reason.value || selected.value.length || confirmed.value))
const blocked = computed(() => !readable.value || locked.value || selecting.value)
const editable = computed(() => props.canEdit && page.value?.relationExists === true)
const request = computed(() => ({ reason: reason.value,
  sources: selected.value.map(source => ({ itemId: source.itemId, versionId: source.versionId })), confirmed: confirmed.value }))
const requestProblem = computed(() => relationSupplementProblem(request.value))
const sourceStateLabels: Record<RelationSupplementSource['state'], string> = {
  CURRENT: '当前版本', SUPERSEDED: '已有较新版本', ARCHIVED: '知识已归档', UNAVAILABLE: '当前不可用',
}
const relationLabels: Record<RelationSupplement['type'], string> = {
  PRECEDES: '前置于', PART_OF: '属于（组成部分）', APPLIED_IN: '应用于', RELATED: '相关（无方向）', CONTRASTS: '对比（无方向）',
}
const changeState = reactive({ dirty: false, locked: false })
let alive = true, sequence = 0, selectionSequence = 0, accessGeneration = 0
let commandEpoch = store.sessionEpoch, commandGeneration = 0

watch([dirty, locked], ([unsaved, pending]) => {
  changeState.dirty = unsaved; changeState.locked = pending
  store.changes.set(key, changeState)
  emit('locked', pending); emit('state', { dirty: unsaved, locked: pending })
}, { immediate: true, flush: 'sync' })
watch([reason, () => JSON.stringify(selected.value)], () => { confirmed.value = false }, { flush: 'sync' })
watch(() => props.relationId, value => {
  if (dirty.value || locked.value) return
  boundId.value = value; opened.value = false; formOpen.value = false
  cursor.value = undefined; previous.value = []; page.value = undefined; message.value = ''; problem.value = ''
}, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  accessGeneration++; sequence++; selectionSequence++; loading.value = false; selecting.value = false
  page.value = undefined; confirmed.value = false; pickerOpen.value = false; pickerGeneration.value++
  if (readable.value && opened.value && !locked.value) void load()
}, { flush: 'sync' })
watch(() => props.refreshKey, () => {
  sequence++; loading.value = false; page.value = undefined; confirmed.value = false
  if (readable.value && opened.value && !locked.value) void load()
})
watch(writer.error, value => {
  if (value) confirmed.value = false
  if (value instanceof ApiError && !writer.pending.value && ['SOURCE_CHANGED', 'RELATION_NOT_FOUND', 'RELATION_SOURCE_CHANGED', 'ITEM_ARCHIVED'].includes(value.code)) {
    if (readable.value && opened.value) void load()
  }
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; sequence++; selectionSequence++
  if (store.changes.get(key) === changeState) store.changes.delete(key)
  emit('locked', false); emit('state', { dirty: false, locked: false })
})

async function load(nextCursor = cursor.value) {
  if (!readable.value || !opened.value || locked.value) return
  const id = boundId.value, epoch = store.sessionEpoch, generation = accessGeneration, ticket = ++sequence
  cursor.value = nextCursor; loading.value = true; problem.value = ''; page.value = undefined
  const current = () => alive && readable.value && opened.value && id === boundId.value && epoch === store.sessionEpoch
    && generation === accessGeneration && ticket === sequence && nextCursor === cursor.value
  try {
    const result = await read<RelationSupplementPage>(`/knowledge/relations/${id}/supplements?limit=20${nextCursor ? `&cursor=${encodeURIComponent(nextCursor)}` : ''}`)
    if (!current()) return
    if (!relationSupplementPageValid(result, id, nextCursor)) throw new ApiError(502, 'RELATION_SUPPLEMENT_PAGE_INVALID', '补充记录与当前关系或分页位置不一致，请重新读取。')
    page.value = result
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (current()) loading.value = false }
}
function resetDraft() { reason.value = ''; selected.value = []; confirmed.value = false; pickerOpen.value = false; selectionProblem.value = '' }
function toggle() {
  if (!readable.value || locked.value) return
  if (opened.value) {
    if (!store.canLeave([key])) return
    sequence++; selectionSequence++; loading.value = false; selecting.value = false
    resetDraft(); formOpen.value = false; opened.value = false; page.value = undefined
  } else { opened.value = true; cursor.value = undefined; previous.value = []; void load() }
}
function closeForm() {
  if (blocked.value || !store.canLeave([key])) return
  resetDraft(); formOpen.value = false
}
async function choose(item: ItemSummary) {
  if (blocked.value || !editable.value || !pickerOpen.value || selected.value.length >= 5 || selected.value.some(source => source.itemId === item.id)) return
  if (!validId(item.id) || item.status !== 'ACTIVE' || !['NOTE', 'CONCEPT'].includes(item.kind)
    || !Number.isSafeInteger(item.versionNumber) || item.versionNumber < 1) { selectionProblem.value = '请选择当前已发布的知识。'; return }
  const id = boundId.value, epoch = store.sessionEpoch, generation = accessGeneration, ticket = ++selectionSequence
  selecting.value = true; selectionProblem.value = ''; confirmed.value = false
  const current = () => alive && readable.value && !locked.value && editable.value && pickerOpen.value
    && id === boundId.value && epoch === store.sessionEpoch && generation === accessGeneration && ticket === selectionSequence
  try {
    const value = await read<ItemDetail>(`/knowledge/items/${item.id}`)
    if (!current()) return
    const published = value?.published
    if (!value || value.id !== item.id || value.kind !== item.kind || value.status !== 'ACTIVE' || !published
      || !validId(published.id) || published.id !== value.currentVersionId || published.number !== value.versionNumber
      || published.number !== item.versionNumber || !relationSupplementSourceValid({ itemId: value.id, versionId: published.id,
        number: published.number, title: published.title, kind: value.kind, state: 'CURRENT' })) {
      throw new ApiError(409, 'SOURCE_CHANGED', '所选知识已归档或发布版本已变化。请重新核对，系统不会自动替换为较新版本。')
    }
    // 只保留确切发布版本的身份；详情内草稿和正文不会随补充命令提交。
    selected.value = [...selected.value, { itemId: value.id, versionId: published.id, number: published.number,
      title: published.title, kind: value.kind, state: 'CURRENT' }]
    pickerOpen.value = false
  } catch (error) { if (current()) selectionProblem.value = errorText(error) }
  finally { if (ticket === selectionSequence) selecting.value = false }
}
function removeSource(itemId: string) { if (!blocked.value && editable.value) selected.value = selected.value.filter(source => source.itemId !== itemId) }
function receive(value: RelationSupplement) {
  resetDraft(); formOpen.value = false; message.value = '补充记录已保存，原有关系和既有记录继续保留。'
  cursor.value = undefined; previous.value = []; page.value = undefined
  emit('changed'); void load()
}
function save() {
  if (blocked.value || !editable.value || requestProblem.value) return
  const id = boundId.value, body: RelationSupplementCreate = { ...request.value, confirmed: true }
  commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; pickerOpen.value = false
  sequence++; loading.value = false; problem.value = ''; message.value = ''
  let submitted: typeof writer.pending.value
  void writer.run<RelationSupplement>(`/knowledge/relations/${id}/supplements`, 'POST', body, value => {
    if (!alive) return
    // 回执不匹配可能已经提交；继续保留原请求与键，不能换键追加第二条。
    if (!relationSupplementMatches(value, id, body)) {
      writer.pending.value = submitted
      writer.error.value = new ApiError(502, 'RELATION_SUPPLEMENT_RECEIPT_INVALID', '保存回执与原请求不一致。输入和原请求已保留，请用原请求核对。')
      return
    }
    if (!readable.value || boundId.value !== id || commandEpoch !== store.sessionEpoch || commandGeneration !== accessGeneration) deferred.value = value
    else receive(value)
  })
  submitted = writer.pending.value
}
function retry() {
  if (!readable.value || writer.busy.value) return
  commandEpoch = store.sessionEpoch; commandGeneration = accessGeneration; void writer.retry()
}
function receiveDeferred() {
  if (!readable.value || !deferred.value || deferred.value.relationId !== boundId.value) return
  const value = deferred.value; deferred.value = undefined; receive(value)
}
function next() {
  if (blocked.value || loading.value || !page.value?.nextCursor) return
  previous.value.push(cursor.value); void load(page.value.nextCursor)
}
function back() { if (!blocked.value && !loading.value && previous.value.length) { cursor.value = previous.value.pop(); void load() } }
</script>

<template>
  <section class="relation-supplements" aria-label="关系补充记录">
    <NButton text size="small" :disabled="!readable || locked" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起补充记录' : '查看关系补充记录' }}</NButton>
    <p v-if="!identityValid" class="notice error" role="alert">关系身份已变化，原输入与未决请求继续保留，请返回原关系后处理。</p>
    <p v-if="opened && !readable" class="notice" role="status">资料访问待确认，补充理由与已选版本保留在当前页面。</p>
    <div v-if="opened && readable" :id="panelId" class="supplements-content" :aria-busy="loading">
      <p class="field-help">每条补充单独保留理由和当时选择的知识版本。记录出处不表示关系语义已核实。</p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text size="small" :loading="writer.busy.value" :disabled="!readable" @click="retry">用原请求重试</NButton></p>
      <p v-if="deferred" class="notice" role="status">已保存的回执在访问条件变化后到达。<NButton text size="small" :disabled="!readable" @click="receiveDeferred">接收已保存记录</NButton></p>
      <p v-if="loading" role="status">正在读取补充记录…</p>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}<NButton text size="small" :disabled="blocked || loading" @click="load()">重新读取</NButton></p>
      <template v-if="page">
        <p v-if="!page.relationExists" class="notice" role="status">正式关系已移除，以下补充历史仍保留，当前不能追加。</p>
        <p v-if="!page.items.length" class="field-help">{{ cursor ? '本页没有更多补充记录。' : '还没有补充记录。可为这条关系追加理由及确切知识来源。' }}</p>
        <article v-for="record in page.items" :key="record.id" class="supplement-record">
          <header><strong>{{ record.origin === 'AI' ? 'AI 候选补充 · 人工确认' : '手工补充' }}</strong><time :datetime="record.createdAt">{{ aiDate(record.createdAt) }}</time></header>
          <p class="field-help">{{ relationLabels[record.type] }} · 关系补充，不修改原始理由</p>
          <p class="supplement-reason">{{ record.reason }}</p>
          <ul v-if="record.sources.length" class="source-list"><li v-for="source in record.sources" :key="`${source.itemId}/${source.versionId}`">
            <RouterLink v-if="source.state !== 'UNAVAILABLE'" class="text-link" :to="{ name: 'knowledge', query: { item: source.itemId, version: source.versionId } }">{{ source.title }} · v{{ source.number }}</RouterLink><span v-else>{{ source.title }} · v{{ source.number }}</span>
            <span class="source-state">{{ source.kind === 'CONCEPT' ? '概念' : '笔记' }} · {{ sourceStateLabels[source.state] }}</span>
          </li></ul><p v-else class="field-help">本条只保存理由，没有选择知识来源。</p>
          <RouterLink v-if="record.origin === 'AI' && record.taskId" class="text-link" :to="{ name: 'ai', query: { item: record.taskId } }">查看原 AI 任务、候选 {{ record.candidateId }} 与引用</RouterLink>
          <p v-if="record.origin === 'AI' && record.taskId" class="field-help">原任务的完整清单还保留本次选择的资料、训练、人工／AI 反馈或面后回忆片段（如有），以及当次固定条件、反馈决定与限制、原报告 AI 未审等级和限定或回忆不确定性，可沿任务回看。</p>
        </article>
      </template>
      <div class="row-actions"><NButton v-if="previous.length" size="small" :disabled="blocked || loading" @click="back">上一页</NButton><NButton v-if="page?.nextCursor" size="small" :disabled="blocked || loading" @click="next">下一页</NButton><NButton text size="small" :disabled="blocked || loading" @click="load()">刷新补充与来源状态</NButton><NButton v-if="canEdit && !formOpen" size="small" :disabled="blocked || loading || !editable" @click="formOpen = true">追加补充记录</NButton></div>
      <section v-if="formOpen" class="supplement-form" aria-label="手工追加关系补充">
        <header><h4>追加理由与来源</h4><NButton text size="small" :disabled="blocked" @click="closeForm">取消填写</NButton></header>
        <p v-if="!editable" class="notice">当前关系或编辑条件不可用。已填写内容保留，请核对后再保存。</p>
        <label>补充理由<NInput v-model:value="reason" type="textarea" :maxlength="1000" :autosize="{ minRows: 3, maxRows: 10 }" :disabled="blocked || !editable" placeholder="说明这两个概念为什么存在这条关系，以及成立的条件。" /><span class="field-help">最多 1000 个 UTF-16 码元，按原文保存。</span></label>
        <div class="source-selection"><header><h4>确切知识来源 · {{ selected.length }} / 5</h4><NButton size="small" :disabled="blocked || !editable || selected.length >= 5" :aria-expanded="pickerOpen" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起选择' : '选择来源' }}</NButton></header><p class="field-help">可以只保存理由；选择来源时固定当前发布版本，之后改版仍保留这次出处。</p>
          <ol v-if="selected.length" class="source-list"><li v-for="source in selected" :key="source.itemId"><div><RouterLink class="text-link" :to="{ name: 'knowledge', query: { item: source.itemId, version: source.versionId } }">{{ source.title }} · v{{ source.number }}</RouterLink><span class="source-state">{{ source.versionId }}</span></div><NButton text size="small" :disabled="blocked || !editable" @click="removeSource(source.itemId)">移出</NButton></li></ol>
          <KnowledgePicker v-if="pickerOpen && editable && !locked" :key="pickerGeneration" :disabled="blocked || selected.length >= 5" :exclude-ids="selected.map(source => source.itemId)" @choose="choose" />
          <p v-if="selecting" class="field-help" role="status">正在核对所选发布版本…</p><p v-if="selectionProblem" class="notice error" role="alert">{{ selectionProblem }}</p>
        </div>
        <NCheckbox v-model:checked="confirmed" :disabled="blocked || !editable">我已核对补充理由和所选版本，确认单独追加这条记录</NCheckbox>
        <div class="row-actions"><NButton type="primary" size="small" :loading="writer.busy.value" :disabled="blocked || !editable || !!requestProblem" @click="save">保存补充记录</NButton><span v-if="requestProblem" class="field-help">{{ requestProblem }}</span></div>
        <p class="field-help">保存不会外发模型。未保存输入仅留在当前页面；结果未知时用原请求恢复。</p>
      </section>
    </div>
  </section>
</template>

<style scoped>
.relation-supplements { min-width: 0; margin-top: 8px; }.supplements-content, .supplement-record, .supplement-form, .source-selection { display: grid; gap: 12px; min-width: 0; }.supplements-content { margin-top: 12px; padding-top: 14px; border-top: 1px solid var(--line); }.supplement-record, .supplement-form { padding-top: 15px; border-top: 1px solid var(--line); }
header { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 8px 14px; }h4, p { margin: 0; }h4, header strong { font-size: 13px; }time, .source-state { font-size: 12px; color: var(--muted); }.supplement-reason { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.8; max-width: 75ch; }.source-list { display: grid; gap: 10px; padding-left: 20px; margin: 0; font-size: 13px; }.source-list li { min-width: 0; overflow-wrap: anywhere; }.source-state { display: block; margin-top: 4px; }.source-selection .source-list li { display: flex; flex-wrap: wrap; align-items: start; justify-content: space-between; gap: 8px; }.source-selection .source-list li > div { min-width: 0; flex: 1; }
label { display: grid; gap: 8px; font-size: 13px; }.row-actions { display: flex; flex-wrap: wrap; gap: 9px 13px; align-items: center; }.field-help, .text-link, .notice { overflow-wrap: anywhere; }.text-link { font-size: 13px; }@media (max-width: 560px) { .row-actions { align-items: start; }.source-selection header { align-items: start; } }
</style>
