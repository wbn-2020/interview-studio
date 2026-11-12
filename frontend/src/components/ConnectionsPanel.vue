<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NSelect } from 'naive-ui'
import { ApiError, read, errorText } from '../api/client'
import type { Schema } from '../api/client'
import type { Connection, ConnectionPage, ConnectionType, OpenKnowledge } from '../api/knowledge'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useCommand } from '../composables/useCommand'
import AiRelationOrigin from './ai/AiRelationOrigin.vue'
import RelationSupplements from './RelationSupplements.vue'
import RelationEvidencePanel from './RelationEvidencePanel.vue'
import RelationFeedbackEvidencePanel from './RelationFeedbackEvidencePanel.vue'
import RelationAiFeedbackEvidencePanel from './RelationAiFeedbackEvidencePanel.vue'
import RelationRecollectionEvidencePanel from './RelationRecollectionEvidencePanel.vue'
const props = defineProps<{ id: string; canEdit: boolean }>()
const emit = defineEmits<{ open: [target: OpenKnowledge]; changed: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), active = ref(true)
const type = ref<ConnectionType>('ASSOCIATION')
const cursor = ref<string>()
const previous = ref<(string | undefined)[]>([])
const writer = useCommand()
const status = ref('')
const rows = shallowRef<ConnectionPage>(), latest = shallowRef<ConnectionPage>(), supplementsRevision = ref(0)
const children = reactive(new Map<string, { dirty: boolean; locked: boolean }>())
const changes = reactive({ dirty: false, locked: false }), deferred = shallowRef<() => void>()
const boundId = props.id
const readable = computed(() => active.value && props.id === boundId && store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const childDirty = computed(() => [...children.values()].some(value => value.dirty))
const childLocked = computed(() => [...children.values()].some(value => value.locked))
const locked = computed(() => writer.locked.value || childLocked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value)
let alive = true, generation = 0, verifiedEpoch = -1, verifiedGeneration = -1
let commandEpoch = store.sessionEpoch, commandGeneration = 0
const options = [ { label: '笔记与概念关联', value: 'ASSOCIATION' }, { label: '完整语义关系', value: 'SEMANTIC' }, { label: '正文出链', value: 'OUTGOING' }, { label: '当前反向引用', value: 'BACKLINK' } ]
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  generation++; verifiedEpoch = -1; verifiedGeneration = -1; latest.value = undefined
}, { flush: 'sync' })
const query = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'connections', props.id, type.value, cursor.value]), enabled: readable, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation, kind = type.value, position = cursor.value
  const result = await read<ConnectionPage>(`/knowledge/items/${boundId}/connections?type=${kind}&limit=20${position ? `&cursor=${encodeURIComponent(position)}` : ''}`)
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation || kind !== type.value || position !== cursor.value) throw new ApiError(0, 'CONNECTION_CONTEXT_CHANGED', '关联读取条件已变化，请恢复访问后重新读取。')
  verifiedEpoch = epoch; verifiedGeneration = stamp
  return result
} })
watch([query.data, query.dataUpdatedAt, readable], () => {
  if (!readable.value || verifiedEpoch !== store.sessionEpoch || verifiedGeneration !== generation || !query.data.value) return
  latest.value = query.data.value; applyRows()
}, { immediate: true })
watch([childDirty, locked], ([dirty, waiting]) => {
  changes.dirty = dirty; changes.locked = waiting; store.changes.set('connections', changes)
  if (!dirty && !waiting) applyRows()
}, { immediate: true, flush: 'sync' })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; if (store.changes.get('connections') === changes) store.changes.delete('connections') })
function applyRows() {
  // 后台重读不能卸载仍有草稿或未决命令的关系行。
  if (readable.value && latest.value && !childDirty.value && !locked.value) rows.value = latest.value
}
function childState(id: string, state: { dirty: boolean; locked: boolean }) {
  if (state.dirty || state.locked) children.set(id, state); else children.delete(id)
}
function changeType(value: ConnectionType) {
  if (blocked.value || value === type.value || !store.canLeave(['connections'])) return
  rows.value = undefined; latest.value = undefined; children.clear(); cursor.value = undefined; previous.value = []; type.value = value
}
function next() {
  if (blocked.value || query.isFetching.value || !rows.value?.nextCursor || !store.canLeave(['connections'])) return
  previous.value.push(cursor.value); cursor.value = rows.value.nextCursor; rows.value = undefined; latest.value = undefined; children.clear()
}
function back() {
  if (blocked.value || query.isFetching.value || !previous.value.length || !store.canLeave(['connections'])) return
  cursor.value = previous.value.pop(); rows.value = undefined; latest.value = undefined; children.clear()
}
function open(target: OpenKnowledge) { if (!blocked.value) emit('open', target) }
function source(connection: Connection) {
  if (blocked.value || !connection.sourceVersionId) return
  open({ id: type.value === 'OUTGOING' ? props.id : connection.itemId, versionId: connection.sourceVersionId, lineStart: connection.lineStart ?? undefined, lineEnd: connection.lineEnd ?? undefined })
}
function remove(id: string) {
  if (blocked.value || childDirty.value || !props.canEdit || !rows.value?.items.some(value => value.id === id) || !window.confirm('移除这条语义关系？两端知识内容、已保存补充及证据记录保持不变。')) return
  run<Schema['RemoveRelationResult']>(`/knowledge/relations/${id}/remove`, result => Boolean(result && result.removed === true), () => '关系已移除，已保存补充及证据记录继续保留。')
}
function rebuild() {
  if (blocked.value || childDirty.value) return
  run<Schema['ReferenceRebuildResult']>('/knowledge/references/rebuild', result => Boolean(result && Number.isSafeInteger(result.processedVersions) && result.processedVersions >= 0 && result.processedVersions <= 100
    && Number.isSafeInteger(result.unresolvedReferences) && result.unresolvedReferences >= 0 && typeof result.hasMore === 'boolean'),
  result => `本批补齐 ${result.processedVersions} 个历史版本，${result.unresolvedReferences} 个引用仍不可解析。${result.hasMore ? '仍有待处理版本，可继续补齐。' : '当前批次处理完成。'}`)
}
function run<T>(path: string, valid: (value: T) => boolean, describe: (value: T) => string) {
  commandEpoch = store.sessionEpoch; commandGeneration = generation; status.value = ''
  let submitted: typeof writer.pending.value
  void writer.run<T>(path, 'POST', {}, result => {
    if (!alive) return
    if (!valid(result)) { writer.pending.value = submitted; writer.error.value = new ApiError(502, 'CONNECTION_RECEIPT_INVALID', '操作回执不完整，原请求已保留，请核对原请求结果。'); return }
    const accept = () => { status.value = describe(result); changed() }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) deferred.value = accept
    else accept()
  })
  submitted = writer.pending.value
}
function retry() { if (readable.value && !writer.busy.value && !childLocked.value) { commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value && !childLocked.value) { const accept = deferred.value; deferred.value = undefined; accept() } }
function changed() { supplementsRevision.value++; emit('changed'); if (readable.value) void query.refetch() }
function direction(value: string | null) { return value === 'IN' ? '←' : value === 'OUT' ? '→' : value === 'BOTH' ? '↔' : '' }
</script>
<template>
  <section class="connections-panel"><h3>关联与出处</h3><NSelect :value="type" :options="options" :disabled="blocked" aria-label="关联类型" @update:value="changeType" />
    <p v-if="!readable" class="notice" role="status">资料访问待确认，当前补充输入和未决请求保留。</p>
    <div v-show="readable" :inert="!readable || undefined">
    <p class="field-help">{{ type === 'BACKLINK' ? '来自其他知识当前发布版本的正文引用。每条出处固定源版本。' : type === 'OUTGOING' ? '当前发布正文中的真实引用，代码块和普通文字不计为链接。' : '按对象分页读取完整关系，包含已归档关联。' }}</p>
    <p v-if="query.isPending.value" role="status">正在读取关联…</p><p v-if="query.error.value" class="notice error">{{ errorText(query.error.value) }}</p>
    <div v-for="connection in rows?.items" :key="`${type}:${connection.id}`" class="connection-row"><button class="text-link" :disabled="blocked" @click="open({ id: connection.itemId })">{{ direction(connection.direction) }} {{ connection.title }} <small v-if="connection.status === 'ARCHIVED'">（已归档）</small></button><span v-if="connection.relationType" class="status-pill">{{ connection.relationType }}</span><p v-if="connection.excerpt">{{ connection.excerpt }}</p><div class="row-actions"><NButton v-if="connection.sourceVersionId" text size="small" :disabled="blocked" @click="source(connection)">源版本 v{{ connection.sourceVersionNumber }} · 第 {{ connection.lineStart }}–{{ connection.lineEnd }} 行</NButton><NButton v-if="type === 'SEMANTIC'" text size="small" :disabled="!canEdit || blocked || childDirty" @click="remove(connection.id)">移除关系</NButton></div><AiRelationOrigin v-if="type === 'SEMANTIC'" :relation-id="connection.id" />
      <RelationSupplements v-if="type === 'SEMANTIC'" :relation-id="connection.id" :can-edit="canEdit" :disabled="!readable || writer.locked.value || !!deferred" :refresh-key="supplementsRevision" @state="childState(`supplements:${connection.id}`, $event)" @changed="changed" />
      <RelationEvidencePanel v-if="type === 'SEMANTIC'" :relation-id="connection.id" :can-edit="canEdit" :disabled="!readable || writer.locked.value || !!deferred" :refresh-key="supplementsRevision" @state="childState(`evidence:${connection.id}`, $event)" @changed="changed" />
      <RelationFeedbackEvidencePanel v-if="type === 'SEMANTIC'" :relation-id="connection.id" :can-edit="canEdit" :disabled="!readable || writer.locked.value || !!deferred" :refresh-key="supplementsRevision" @state="childState(`feedback-evidence:${connection.id}`, $event)" @changed="changed" />
      <RelationAiFeedbackEvidencePanel v-if="type === 'SEMANTIC'" :relation-id="connection.id" :can-edit="canEdit" :disabled="!readable || writer.locked.value || !!deferred" :refresh-key="supplementsRevision" @state="childState(`ai-feedback-evidence:${connection.id}`, $event)" @changed="changed" />
      <RelationRecollectionEvidencePanel v-if="type === 'SEMANTIC'" :relation-id="connection.id" :can-edit="canEdit" :disabled="!readable || writer.locked.value || !!deferred" :refresh-key="supplementsRevision" @state="childState(`recollection-evidence:${connection.id}`, $event)" @changed="changed" />
    </div>
    <p v-if="rows && !rows.items.length" class="empty-inline">没有这一类关联。</p><p v-if="childDirty" class="field-help">请先保存或取消补充、证据输入，再移除关系；离开当前关联页会确认是否丢弃输入。</p><div class="row-actions"><NButton v-if="previous.length" size="small" :disabled="blocked || query.isFetching.value" @click="back">上一页</NButton><NButton v-if="rows?.nextCursor" size="small" :disabled="blocked || query.isFetching.value" @click="next">下一页</NButton></div>
    <details v-if="type === 'OUTGOING' || type === 'BACKLINK'"><summary>历史引用维护</summary><p class="field-help">导入升级前的旧版本可能尚未建立引用索引。每次最多补齐 100 个版本，不改写正文。</p><NButton size="small" :disabled="blocked || childDirty" @click="rebuild">补齐一批历史引用</NButton></details>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" size="small" :disabled="!readable || childLocked" @click="retry">用原请求重试</NButton><p v-if="deferred" class="notice" role="status">操作已返回，待恢复访问后接收。<NButton text size="small" :disabled="!readable || childLocked" @click="receiveDeferred">接收原请求结果</NButton></p><p v-if="status" class="success-text" role="status">{{ status }}</p>
    </div>
  </section>
</template>
