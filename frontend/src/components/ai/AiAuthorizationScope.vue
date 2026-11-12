<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton, NCheckbox, NInput } from 'naive-ui'
import { ApiError, errorText, preview, read } from '../../api/client'
import type { AiManifest as Manifest } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate, aiPurposeLabels } from '../../api/ai'
import type { AiAuthorizationCreate, AiAuthorizationEntry, AiAuthorizationMatches, AiAuthorizationUsagePage } from '../../api/aiAuthorization'
import { aiAuthorizationCreateReceiptValid, aiAuthorizationIdValid, aiAuthorizationMatchesValid, aiAuthorizationPaths, aiAuthorizationStateLabels, aiAuthorizationUsagePageValid, notifyAiAuthorizationsChanged, observeAiAuthorizationChanges } from '../../api/aiAuthorization'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import AiManifest from './AiManifest.vue'

const props = defineProps<{ taskId: string; manifest?: Manifest; manifestHash?: string; sourceQa?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ state: [value: { dirty: boolean; locked: boolean }] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand(), viewId = useId(), key = `ai-authorization-scope:${viewId}`
const active = ref(true), opened = ref(false), days = ref('30'), confirmed = ref(false), loading = ref(false), problem = ref(''), message = ref('')
const matched = shallowRef<AiAuthorizationMatches>(), deferred = shallowRef<AiAuthorizationEntry>()
const usages = shallowRef<AiAuthorizationUsagePage>(), usageLoading = ref(false), usageProblem = ref('')
const changes = reactive({ dirty: false, locked: false })
const identity = computed(() => JSON.stringify([props.taskId, props.sourceQa ? [props.manifest != null, props.manifestHash ?? null]
  : props.manifest ? aiCandidateManifestFingerprint(props.manifest) : null]))
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked && aiAuthorizationIdValid(props.taskId) && Boolean(props.manifest))
const metadataReadable = computed(() => active.value && store.sessionActive && aiAuthorizationIdValid(props.taskId))
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value || loading.value)
const validDays = computed(() => /^\d+$/.test(days.value) && Number.isSafeInteger(Number(days.value)) && Number(days.value) >= 1 && Number(days.value) <= 90)
let alive = true, generation = 0, sequence = 0, usageSequence = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
let stopObserving: (() => void) | undefined
watch([() => opened.value && (days.value !== '30' || confirmed.value), locked], ([dirty, pending]) => {
  changes.dirty = dirty; changes.locked = pending; store.changes.set(key, changes); emit('state', { dirty, locked: pending })
}, { immediate: true, flush: 'sync' })
watch(days, () => { confirmed.value = false }, { flush: 'sync' })
function invalidate() { generation++; sequence++; loading.value = false; matched.value = undefined; confirmed.value = false }
watch([identity, readable, () => store.sessionEpoch], invalidate, { flush: 'sync' })
watch([metadataReadable, () => store.sessionEpoch, () => props.taskId], () => { usageSequence++; usages.value = undefined; usageLoading.value = false; usageProblem.value = '' }, { flush: 'sync' })
function changed() { invalidate(); usageSequence++; usages.value = undefined; usageLoading.value = false }
onMounted(() => { stopObserving = observeAiAuthorizationChanges(changed) })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; sequence++; usageSequence++; stopObserving?.(); if (store.changes.get(key) === changes) store.changes.delete(key); emit('state', { dirty: false, locked: false }) })

function toggle() {
  if (locked.value || !readable.value) return
  if (opened.value) { if (!store.canLeave([key])) return; opened.value = false; days.value = '30'; confirmed.value = false; matched.value = undefined }
  else { opened.value = true; void check() }
}
async function check() {
  if (blocked.value || !opened.value || !props.manifest) return
  invalidate()
  const epoch = store.sessionEpoch, stamp = identity.value, ticket = ++sequence, manifest = JSON.parse(JSON.stringify(props.manifest)) as Manifest
  loading.value = true; problem.value = ''
  const current = () => alive && readable.value && opened.value && epoch === store.sessionEpoch && stamp === identity.value && ticket === sequence
  try {
    const value = await preview<AiAuthorizationMatches>(aiAuthorizationPaths.matches, { manifest })
    if (!current()) return
    if (!aiAuthorizationMatchesValid(value, manifest, props.sourceQa ? props.manifestHash : undefined)) throw new ApiError(502, 'AI_AUTHORIZATION_MATCHES_INVALID', '清单核对结果不完整，请重新读取原任务。')
    matched.value = value
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (current()) loading.value = false }
}
function receive(value: AiAuthorizationEntry) {
  days.value = '30'; confirmed.value = false; opened.value = false
  message.value = `已保存持续授权 ${value.id}，有效至 ${aiDate(value.expiresAt)}。当前状态请在授权管理中刷新核对；后续请求仍须明确选择并手动提交。`
  notifyAiAuthorizationsChanged()
}
function create() {
  if (blocked.value || !opened.value || !matched.value || !confirmed.value || !validDays.value || !props.manifest) return
  if (props.sourceQa) { createSourceQa(); return }
  const body: AiAuthorizationCreate = { taskId: props.taskId, expectedManifestHash: matched.value.manifestHash, validDays: Number(days.value), confirmed: true }
  const manifest = JSON.parse(JSON.stringify(props.manifest)) as Manifest, scopeHash = matched.value.scopeHash
  commandEpoch = store.sessionEpoch; commandGeneration = generation
  void writer.run<AiAuthorizationEntry>(aiAuthorizationPaths.create, 'POST', body, value => {
    if (!alive) return
    // 原键恢复核对不可变范围与创建期限；到期、重启或后来撤回不能使旧成功被误判为可重新创建。
    if (!aiAuthorizationCreateReceiptValid(value, body, manifest, scopeHash)) throw new ApiError(502, 'AI_AUTHORIZATION_RECEIPT_INVALID', '授权回执与原请求不一致，原请求已保留，请沿原请求核对。')
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) deferred.value = value
    else receive(value)
  })
}
function createSourceQa() {
  if (!props.manifest || !matched.value) return
  const body: AiAuthorizationCreate = { taskId: props.taskId, expectedManifestHash: matched.value.manifestHash, validDays: Number(days.value), confirmed: true }
  const identity: Pick<Manifest, 'purpose' | 'configuration'> = { purpose: props.manifest.purpose, configuration: { ...props.manifest.configuration } }
  const scopeHash = matched.value.scopeHash
  commandEpoch = store.sessionEpoch; commandGeneration = generation
  // 新用途原键恢复只需公开配置和固定摘要；来源全文失权后可清除，不进入长期命令闭包。
  void writer.run<AiAuthorizationEntry>(aiAuthorizationPaths.create, 'POST', body, value => {
    if (!alive) throw new ApiError(0, 'AI_AUTHORIZATION_CONTEXT_CHANGED', '授权界面已变化，请恢复原请求。')
    if (!aiAuthorizationCreateReceiptValid(value, body, identity, scopeHash)) throw new ApiError(502, 'AI_AUTHORIZATION_RECEIPT_INVALID', '授权回执与原请求不一致，原请求已保留。')
    if (!metadataReadable.value || commandEpoch !== store.sessionEpoch) throw new ApiError(0, 'AI_AUTHORIZATION_CONTEXT_CHANGED', '登录会话变化，原请求继续保留；恢复后核对授权回执。')
    receive(value)
  })
}
function retry() { if (metadataReadable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry() } }
function receiveDeferred() { if (metadataReadable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
async function loadUses() {
  if (!metadataReadable.value || usageLoading.value) return
  const id = props.taskId, epoch = store.sessionEpoch, ticket = ++usageSequence
  usageLoading.value = true; usageProblem.value = ''; usages.value = undefined
  const current = () => alive && metadataReadable.value && epoch === store.sessionEpoch && id === props.taskId && ticket === usageSequence
  try {
    const value = await read<AiAuthorizationUsagePage>(aiAuthorizationPaths.uses(id))
    if (!current()) return
    if (!aiAuthorizationUsagePageValid(value)) throw new ApiError(502, 'AI_AUTHORIZATION_USES_INVALID', '尝试授权留痕格式不一致，请重新读取。')
    usages.value = value
  } catch (error) { if (current()) usageProblem.value = errorText(error) }
  finally { if (current()) usageLoading.value = false }
}
</script>

<template>
  <section class="authorization-scope" aria-label="固定清单持续授权与使用留痕">
    <header><h3>这些固定版本与片段的持续授权</h3><NButton size="small" :disabled="!readable || locked" :aria-expanded="opened" @click="toggle">{{ opened ? '收起范围确认' : '核对清单并创建授权' }}</NButton></header>
    <p v-if="manifest && readable" class="field-help">{{ aiPurposeLabels[manifest.purpose] }} · {{ manifest.configuration.provider }} · {{ manifest.configuration.model }}。问题和整理要求每次可重新输入；用途、供应商配置、来源版本与片段保持限定。新增或替换来源需要重新确认。</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="!metadataReadable || writer.busy.value" :loading="writer.busy.value" @click="retry">用原请求核对授权</NButton></p>
    <p v-if="deferred" class="notice" role="status">原授权请求已有回执。<NButton text :disabled="!metadataReadable" @click="receiveDeferred">接收原请求结果</NButton></p>
    <p v-if="opened && !readable" class="notice" role="status">完整清单访问待确认，期限输入与原请求继续保留。</p>
    <div v-if="opened" v-show="readable" :inert="!readable || undefined" class="scope-form">
      <AiManifest v-if="manifest && readable" :manifest="manifest" />
      <p v-if="loading" class="field-help" role="status">正在核对这份固定清单…</p><p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
      <NButton text size="small" :disabled="blocked" @click="check">重新核对完整清单</NButton>
      <label>有效天数<NInput v-model:value="days" :disabled="blocked" :maxlength="2" :input-props="{ inputmode: 'numeric', 'aria-label': '持续授权有效天数' }" /><span class="field-help">1–90 天，默认 30 天。服务重启、配置改变或撤回后不再适用；到期不会自动续期。</span></label>
      <NCheckbox v-model:checked="confirmed" :disabled="blocked || !matched || !validDays">我已重新核对完整清单，允许在有效期内明确选择这条授权，向上述供应商使用这些固定版本与片段；每次仍由我主动预览和提交</NCheckbox>
      <NButton type="primary" :disabled="blocked || !matched || !validDays || !confirmed" :loading="writer.busy.value" @click="create">保存持续范围授权</NButton>
      <p class="field-help">保存授权不会调用模型或预留费用。独立训练辅助确认、提前反馈确认和未知费用确认仍分别执行。</p>
    </div>
    <div class="usage-records"><NButton text size="small" :disabled="!metadataReadable || usageLoading" :loading="usageLoading" @click="loadUses">查看／刷新每次尝试的授权留痕</NButton><p v-if="usageProblem" class="notice error" role="alert">{{ usageProblem }}</p><p v-if="usages && !usages.items.length" class="field-help">尚无尝试授权记录。</p><p v-for="usage in usages?.items" :key="usage.attemptId" class="field-help">第 {{ usage.attemptNumber }} 次尝试 · {{ usage.mode === 'ONCE' ? '单次授权' : '持续范围授权' }} · {{ aiDate(usage.createdAt) }}<template v-if="usage.authorizationId"> · {{ usage.authorizationId }} · 当时授权修订 {{ usage.authorizationRevision }} · {{ usage.effectiveState ? aiAuthorizationStateLabels[usage.effectiveState] : '' }}</template></p></div>
  </section>
</template>

<style scoped>
.authorization-scope, .scope-form, .usage-records { display: grid; gap: 14px; min-width: 0; }.authorization-scope { padding-top: 20px; border-top: 1px solid var(--line); }
header { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 10px; }h3 { margin: 0; font-size: 15px; }p { margin: 0; overflow-wrap: anywhere; }
label { display: grid; gap: 8px; max-width: 35rem; font-size: 13px; }.scope-form > .n-button, .usage-records > .n-button { justify-self: start; }
</style>
