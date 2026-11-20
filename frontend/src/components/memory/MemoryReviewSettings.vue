<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NInputNumber } from 'naive-ui'
import { ApiError, read } from '../../api/client'
import type { MemoryCreateSettings, MemoryReviewSettings } from '../../api/memoryReview'
import { memoryCreateSettingsProblem, memoryReviewErrorText, memoryReviewSettingsValid, memorySaveSettingsProblem } from '../../api/memoryReview'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ saved: [value: MemoryReviewSettings] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), active = ref(true)
type Draft = { zoneId: string; dailyNewBudget: number | null; dailyReviewBudget: number | null }
const draft = reactive<Draft>({ zoneId: '', dailyNewBudget: 10, dailyReviewBudget: 50 })
const deviceTimeZone = (() => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone } catch { return '' } })()
const initialSignature = ref(signature(draft)), current = shallowRef<MemoryReviewSettings>(), editBase = shallowRef<MemoryReviewSettings>()
const serverCopy = shallowRef<MemoryReviewSettings>(), readError = ref(''), serverError = ref(''), message = ref('')
const needsReview = ref(false), replaceConfirmed = ref(false), serverLoading = ref(false), verifiedEpoch = ref(-1), verifiedGeneration = ref(-1)
const receiptMismatch = ref(false), receiptCommand = shallowRef<NonNullable<typeof writer.pending.value>>(), deferred = shallowRef<() => void>()
const changes = reactive({ dirty: false, locked: false }), stateKey = 'memory-review-settings'
let alive = true, generation = 0, serverSequence = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value || receiptMismatch.value || Boolean(deferred.value))
const otherLocked = computed(() => [...store.changes].some(([key, value]) => key.startsWith('memory-') && key !== stateKey && value.locked))
const blocked = computed(() => !readable.value || locked.value || otherLocked.value || serverLoading.value)
const dirty = computed(() => signature(draft) !== initialSignature.value)
const stale = computed(() => Boolean(editBase.value && (!current.value || !sameSettings(editBase.value, current.value))))
const editable = computed(() => Boolean(editBase.value) && !blocked.value)
const problem = computed(() => editBase.value?.configured ? memorySaveSettingsProblem({ expectedRevision: editBase.value.revision, ...draft }) : memoryCreateSettingsProblem({ ...draft }))
const limitReached = computed(() => editBase.value?.revision === Number.MAX_SAFE_INTEGER)
const key = computed(() => ['memory', store.sessionEpoch, 'review-settings'])
const currentReady = computed(() => Boolean(current.value && !readError.value && !detail.error.value && !detail.isFetching.value
  && verifiedEpoch.value === store.sessionEpoch && verifiedGeneration.value === generation))
const canSave = computed(() => editable.value && currentReady.value && !stale.value && !needsReview.value && !problem.value && !limitReached.value
  && (!editBase.value?.configured || dirty.value))

watch([readable, () => store.sessionEpoch], () => {
  generation++; serverSequence++; serverLoading.value = false; verifiedEpoch.value = -1; verifiedGeneration.value = -1
  serverCopy.value = undefined; serverError.value = ''; replaceConfirmed.value = false
  if (editBase.value) needsReview.value = true
}, { flush: 'sync' })
const detail = useQuery({ queryKey: key, enabled: readable, staleTime: 0, queryFn: async () => {
  const epoch = store.sessionEpoch, stamp = generation
  verifiedEpoch.value = -1; verifiedGeneration.value = -1
  const value = await read<MemoryReviewSettings>('/memory/review-settings')
  requireCurrent(epoch, stamp)
  if (!readValid(value)) throw mismatch('服务器设置的字段或修订不一致，请重新读取。')
  verifiedEpoch.value = epoch; verifiedGeneration.value = stamp
  return value
} })
watch([detail.data, detail.dataUpdatedAt, readable, key], () => {
  if (!alive || !readable.value || verifiedEpoch.value !== store.sessionEpoch || verifiedGeneration.value !== generation) return
  const value = queryClient.getQueryData<MemoryReviewSettings>(key.value)
  if (!value) return
  if (!readValid(value)) { readError.value = '缓存设置与已读取修订不一致，草稿保留，请重新读取。'; return }
  current.value = { ...value }; readError.value = ''
  // 后台重读只能更新服务器对照；已修改的表单和未决请求始终保留原保存基线。
  if (!editBase.value || (!dirty.value && !locked.value && !needsReview.value)) adopt(value, false)
  else if (!sameSettings(editBase.value, value)) needsReview.value = true
}, { immediate: true })
watch([dirty, locked], ([changed, waiting]) => { changes.dirty = changed; changes.locked = waiting; store.changes.set(stateKey, changes) }, { immediate: true, flush: 'sync' })
watch(draft, () => { replaceConfirmed.value = false }, { deep: true, flush: 'sync' })
watch(writer.error, error => {
  if (!(error instanceof ApiError) || writer.pending.value || receiptMismatch.value) return
  if (error.status === 409) { needsReview.value = true; if (readable.value) void detail.refetch() }
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; serverSequence++; if (store.changes.get(stateKey) === changes) store.changes.delete(stateKey) })

function signature(value: Draft): string { return JSON.stringify([value.zoneId, value.dailyNewBudget, value.dailyReviewBudget]) }
function formOf(value: MemoryReviewSettings): Draft { return { zoneId: value.zoneId ?? '', dailyNewBudget: value.dailyNewBudget, dailyReviewBudget: value.dailyReviewBudget } }
function sameSettings(left: MemoryReviewSettings, right: MemoryReviewSettings): boolean {
  return left.configured === right.configured && left.revision === right.revision && left.zoneId === right.zoneId && left.dailyNewBudget === right.dailyNewBudget
    && left.dailyReviewBudget === right.dailyReviewBudget && left.createdAt === right.createdAt && left.updatedAt === right.updatedAt
}
function readValid(value: unknown): value is MemoryReviewSettings {
  if (!memoryReviewSettingsValid(value)) return false
  const known = current.value
  if (!known?.configured) return true
  return value.configured && value.revision !== null && known.revision !== null && value.revision >= known.revision && value.createdAt === known.createdAt
    && (value.revision !== known.revision || sameSettings(value, known))
}
function mismatch(text: string) { return new ApiError(502, 'MEMORY_SETTINGS_RECEIPT_INVALID', text) }
function requireCurrent(epoch: number, stamp: number) {
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation) throw new ApiError(0, 'MEMORY_CONTEXT_CHANGED', '会话或资料访问条件已变化，请恢复访问后重新核对设置。')
}
function adopt(value: MemoryReviewSettings, keepLocal: boolean) {
  const serverDraft = formOf(value)
  editBase.value = { ...value }; initialSignature.value = signature(serverDraft)
  if (!keepLocal) Object.assign(draft, serverDraft)
  needsReview.value = false; replaceConfirmed.value = false
}
function updateZone(value: string) { if (editable.value) draft.zoneId = value }
function updateBudget(field: 'dailyNewBudget' | 'dailyReviewBudget', value: number | null) { if (editable.value) draft[field] = value }
function save() {
  if (!canSave.value || !editBase.value || draft.dailyNewBudget === null || draft.dailyReviewBudget === null) return
  const base = { ...editBase.value }, snapshot: MemoryCreateSettings = { zoneId: draft.zoneId, dailyNewBudget: draft.dailyNewBudget, dailyReviewBudget: draft.dailyReviewBudget }
  const body = base.configured ? { expectedRevision: base.revision, ...snapshot } : snapshot
  if (base.configured ? memorySaveSettingsProblem(body) : memoryCreateSettingsProblem(body)) return
  commandEpoch = store.sessionEpoch; commandGeneration = generation; message.value = ''
  let original: typeof writer.pending.value
  void writer.run<MemoryReviewSettings>('/memory/review-settings', base.configured ? 'PUT' : 'POST', body, value => {
    if (!alive) return
    if (!receiptValid(value, base, snapshot)) {
      receiptMismatch.value = true; receiptCommand.value = original
      writer.error.value = mismatch('设置回执与本次保存不一致。输入与原请求保留，请核对原请求结果。'); return
    }
    const accept = () => {
      receiptMismatch.value = false; receiptCommand.value = undefined; writer.error.value = undefined; deferred.value = undefined
      if (!current.value?.configured || current.value.revision === null || (value.revision !== null && value.revision >= current.value.revision)) current.value = { ...value }
      adopt(value, false); serverCopy.value = undefined; serverError.value = ''; readError.value = ''
      needsReview.value = Boolean(current.value && !sameSettings(value, current.value))
      verifiedEpoch.value = -1; verifiedGeneration.value = -1
      if (current.value && sameSettings(value, current.value)) queryClient.setQueryData(key.value, value)
      message.value = `回忆设置已保存（修订 ${value.revision}）。既有卡片的到期时间和历史保持原记录。`
      void queryClient.invalidateQueries({ queryKey: ['memory'] }); emit('saved', value)
    }
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) deferred.value = accept
    else accept()
  })
  original = writer.pending.value
}
function receiptValid(value: unknown, base: MemoryReviewSettings, snapshot: MemoryCreateSettings): value is MemoryReviewSettings {
  if (!memoryReviewSettingsValid(value) || !value.configured || value.zoneId !== snapshot.zoneId || value.dailyNewBudget !== snapshot.dailyNewBudget
    || value.dailyReviewBudget !== snapshot.dailyReviewBudget) return false
  if (base.configured ? base.revision === null || value.revision !== base.revision + 1 || value.createdAt !== base.createdAt : value.revision !== 0 || value.createdAt !== value.updatedAt) return false
  return !current.value?.configured || current.value.revision !== value.revision || sameSettings(current.value, value)
}
async function compareServer() {
  if (blocked.value) return
  const epoch = store.sessionEpoch, stamp = generation, sequence = ++serverSequence
  serverLoading.value = true; serverError.value = ''; replaceConfirmed.value = false
  try {
    const value = await read<MemoryReviewSettings>('/memory/review-settings')
    requireCurrent(epoch, stamp)
    if (sequence !== serverSequence) return
    if (!readValid(value)) throw mismatch('服务器设置的字段或修订异常，编辑草稿保留。')
    current.value = { ...value }; serverCopy.value = { ...value }; verifiedEpoch.value = epoch; verifiedGeneration.value = stamp; readError.value = ''
    queryClient.setQueryData(key.value, value)
  } catch (error) { if (alive && sequence === serverSequence && epoch === store.sessionEpoch && stamp === generation) serverError.value = memoryReviewErrorText(error) }
  finally { if (alive && sequence === serverSequence) serverLoading.value = false }
}
function useServer(keepLocal: boolean) {
  const selected = serverCopy.value
  if (blocked.value || !selected || !current.value || !sameSettings(selected, current.value) || verifiedEpoch.value !== store.sessionEpoch
    || verifiedGeneration.value !== generation || (!keepLocal && dirty.value && !replaceConfirmed.value)) return
  adopt(selected, keepLocal); serverCopy.value = undefined; writer.error.value = undefined
  message.value = keepLocal ? '本地输入保留，已采用服务器修订作为保存基线；请核对后保存。' : '已采用服务器设置。'
}
function retry() {
  if (!readable.value || writer.busy.value || otherLocked.value) return
  if (receiptMismatch.value && receiptCommand.value) writer.pending.value = receiptCommand.value
  commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry()
}
function receiveDeferred() { if (readable.value && !otherLocked.value && deferred.value) { const accept = deferred.value; deferred.value = undefined; accept() } }
function refresh() { if (!blocked.value) void detail.refetch() }
</script>

<template>
  <section class="memory-settings" aria-labelledby="memory-settings-title">
    <header><div><h2 id="memory-settings-title">回忆设置</h2><p class="field-help">保存时区和每日建议数量后，可以在卡片中明确启用排程。</p></div><NButton text size="small" :disabled="blocked || detail.isFetching.value" @click="refresh">重新读取</NButton></header>
    <p v-if="!readable" class="notice" role="status">资料访问暂未恢复，设置已遮挡。未保存输入与未决请求继续保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="settings-content">
      <p v-if="!current && detail.isPending.value" role="status">正在读取回忆设置…</p>
      <p v-if="readError || detail.error.value" class="notice error" role="alert">{{ readError || memoryReviewErrorText(detail.error.value) }}</p>
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ memoryReviewErrorText(writer.error.value) }}<NButton v-if="writer.pending.value || (receiptMismatch && receiptCommand)" text :loading="writer.busy.value" :disabled="!readable || otherLocked" @click="retry">{{ receiptMismatch ? '核对原请求结果' : '用原请求重试' }}</NButton></p>
      <p v-if="deferred" class="notice" role="status">保存结果已返回，等待恢复访问后接收。<NButton text :disabled="!readable || otherLocked" @click="receiveDeferred">接收原请求结果</NButton></p>
      <template v-if="editBase">
        <p v-if="!editBase.configured" class="notice">尚未保存设置。每天 10 张新卡、50 张复习卡是建议值；请明确选择时区后再保存。</p>
        <p v-else class="field-help">编辑基线：设置修订 {{ editBase.revision }}{{ dirty ? ' · 有未保存输入' : '' }}</p>
        <p v-if="stale || needsReview" class="notice">设置修订或访问条件已变化，输入已保留。<NButton text :disabled="blocked" :loading="serverLoading" @click="compareServer">读取服务器设置并对照</NButton></p>
        <label>复习日时区<NInput :value="readable ? draft.zoneId : ''" :disabled="!editable" placeholder="例如 Asia/Shanghai 或 UTC" :input-props="{ 'aria-label': '复习日时区' }" @update:value="updateZone" /><span class="field-help">按此时区计算每天的新卡和复习数量。修改时区不移动已经保存的到期时刻。</span></label>
        <div v-if="deviceTimeZone" class="row-actions"><NButton size="small" :disabled="!editable || draft.zoneId === deviceTimeZone" @click="updateZone(deviceTimeZone)">使用设备时区（{{ deviceTimeZone }}）</NButton><span class="field-help">选择后保存才会生效，也可以手动填写其他 IANA 时区。</span></div>
        <div class="budget-fields">
          <label>每日新卡建议<NInputNumber :value="readable ? draft.dailyNewBudget : null" :disabled="!editable" :min="0" :max="1000" :step="1" :show-button="false" @update:value="updateBudget('dailyNewBudget', $event)" /></label>
          <label>每日复习建议<NInputNumber :value="readable ? draft.dailyReviewBudget : null" :disabled="!editable" :min="0" :max="1000" :step="1" :show-button="false" @update:value="updateBudget('dailyReviewBudget', $event)" /></label>
        </div>
        <p class="field-help">每日建议数量用于后续复习推荐；填写 0 表示默认不推荐这一类卡片，不限制你主动复习。</p>
        <p v-if="problem" class="field-help" role="status">{{ problem }}</p>
        <p v-if="limitReached" class="notice">设置修订已达到上限，无法继续保存。</p>
        <div class="row-actions"><NButton type="primary" :disabled="!canSave" :loading="writer.busy.value" @click="save">{{ editBase.configured ? '保存设置' : '确认并保存设置' }}</NButton><NButton text :disabled="blocked" :loading="serverLoading" @click="compareServer">核对服务器设置</NButton></div>
      </template>
      <p v-if="serverError" class="notice error" role="alert">{{ serverError }}</p>
      <section v-if="serverCopy" class="server-comparison" aria-label="服务器设置对照">
        <h3>{{ serverCopy.configured ? `服务器设置 · 修订 ${serverCopy.revision}` : '服务器尚未保存设置' }}</h3>
        <dl><div><dt>时区</dt><dd>{{ serverCopy.zoneId ?? '尚未选择' }}</dd></div><div><dt>每日新卡建议</dt><dd>{{ serverCopy.dailyNewBudget }}</dd></div><div><dt>每日复习建议</dt><dd>{{ serverCopy.dailyReviewBudget }}</dd></div></dl>
        <NCheckbox v-if="dirty" v-model:checked="replaceConfirmed" :disabled="blocked">允许用服务器设置替换当前未保存输入</NCheckbox>
        <div class="row-actions"><NButton :disabled="blocked || !current || !sameSettings(serverCopy, current)" @click="useServer(true)">保留本地输入，采用服务器修订</NButton><NButton :disabled="blocked || !current || !sameSettings(serverCopy, current) || (dirty && !replaceConfirmed)" @click="useServer(false)">采用服务器设置</NButton></div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.memory-settings, .settings-content { display: grid; gap: 16px; min-width: 0; }
.memory-settings { padding: 22px 26px; border-bottom: 1px solid var(--line); }
header { display: flex; align-items: start; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
header > div { display: grid; gap: 8px; min-width: 0; } h2, h3, p, dl { margin: 0; } h2 { font-size: 18px; } h3 { font-size: 15px; }
.settings-content > label, .budget-fields > label { display: grid; gap: 8px; font-size: 13px; min-width: 0; }
.budget-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; max-width: 560px; }
.row-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.server-comparison { display: grid; gap: 14px; padding: 16px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); overflow-wrap: anywhere; }
dl { display: grid; gap: 8px; } dl > div { display: flex; gap: 18px; flex-wrap: wrap; } dt { min-width: 100px; color: var(--muted); } dd { margin: 0; }
@media (max-width: 700px) { .memory-settings { padding: 20px 16px; } .budget-fields { grid-template-columns: 1fr; } }
</style>
