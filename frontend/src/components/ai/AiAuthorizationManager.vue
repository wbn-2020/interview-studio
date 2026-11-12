<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { aiDate, aiPurposeLabels } from '../../api/ai'
import type { AiAuthorizationEntry, AiAuthorizationPage, AiAuthorizationRevocation } from '../../api/aiAuthorization'
import { aiAuthorizationPageValid, aiAuthorizationPaths, aiAuthorizationRevocationValid, aiAuthorizationStateLabels, notifyAiAuthorizationsChanged, observeAiAuthorizationChanges } from '../../api/aiAuthorization'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'

const store = useWorkspaceStore(), writer = useCommand(), viewId = useId(), key = `ai-authorizations:${viewId}`
const opened = ref(false), active = ref(true), loading = ref(false), problem = ref(''), message = ref('')
const page = shallowRef<AiAuthorizationPage>(), cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
const deferred = shallowRef<AiAuthorizationRevocation>(), changes = reactive({ dirty: false, locked: false })
// 此面板只管理安全元数据，独立训练资料限制不能阻止撤回；清单正文仍通过原任务守卫。
const readable = computed(() => active.value && store.sessionActive)
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
const blocked = computed(() => !readable.value || locked.value)
let alive = true, generation = 0, sequence = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
let stopObserving: (() => void) | undefined
let attempted: { cursor?: string; direction: 'refresh' | 'next' | 'back' } = { direction: 'refresh' }
watch(locked, value => { changes.locked = value; store.changes.set(key, changes) }, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], () => { generation++; sequence++; loading.value = false; page.value = undefined; if (readable.value && opened.value && !locked.value) void load() }, { flush: 'sync' })
onMounted(() => { stopObserving = observeAiAuthorizationChanges(changed) })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; sequence++; stopObserving?.(); if (store.changes.get(key) === changes) store.changes.delete(key) })
function changed() { sequence++; loading.value = false; page.value = undefined; if (readable.value && opened.value && !locked.value) void load(cursor.value) }
function toggle() { if (!blocked.value) { opened.value = !opened.value; if (opened.value) { cursor.value = undefined; previous.value = []; void load() } } }
async function load(target?: string, direction: 'refresh' | 'next' | 'back' = 'refresh') {
  if (blocked.value || !opened.value || loading.value) return
  const epoch = store.sessionEpoch, stamp = generation, ticket = ++sequence, original = cursor.value
  attempted = { cursor: target, direction }; loading.value = true; problem.value = ''; page.value = undefined
  const current = () => alive && readable.value && epoch === store.sessionEpoch && stamp === generation && ticket === sequence
  try {
    const value = await read<AiAuthorizationPage>(aiAuthorizationPaths.page(target))
    if (!current()) return
    if (!aiAuthorizationPageValid(value, target)) throw new ApiError(502, 'AI_AUTHORIZATION_PAGE_INVALID', '持续授权列表与分页位置不一致，请重新读取。')
    if (direction === 'next') previous.value.push(original)
    else if (direction === 'back') previous.value.pop()
    cursor.value = target; page.value = value
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (current()) loading.value = false }
}
function receive(value: AiAuthorizationRevocation) {
  message.value = `授权已撤回：停止 ${value.queuedStopped} 次未发送尝试，已为 ${value.runningStopRequested} 次处理中尝试请求停止接纳。费用以各尝试记录为准。`
  notifyAiAuthorizationsChanged()
}
function revoke(entry: AiAuthorizationEntry) {
  if (blocked.value || loading.value || entry.state !== 'ACTIVE' || !page.value?.items.includes(entry)) return
  if (!window.confirm('撤回这条持续授权？引用它的排队尝试将停止；已处理的请求可能仍收费，未知费用保留。其他授权和单次请求不受此操作影响。')) return
  const issued = { ...entry }
  commandEpoch = store.sessionEpoch; commandGeneration = generation
  void writer.run<AiAuthorizationRevocation>(aiAuthorizationPaths.revoke(entry.id), 'POST', { expectedRevision: entry.revision, confirmed: true }, value => {
    if (!alive) return
    if (!aiAuthorizationRevocationValid(value, issued)) throw new ApiError(502, 'AI_AUTHORIZATION_REVOCATION_INVALID', '撤回回执与原授权不一致，原请求已保留，请核对原请求。')
    if (!readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) deferred.value = value
    else receive(value)
  })
}
function retry() { if (readable.value && !writer.busy.value) { commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry() } }
function receiveDeferred() { if (readable.value && deferred.value) { const value = deferred.value; deferred.value = undefined; receive(value) } }
</script>

<template>
  <section class="authorization-manager" aria-label="持续范围授权管理">
    <header><strong>持续范围授权</strong><NButton size="small" :disabled="blocked" :aria-expanded="opened" @click="toggle">{{ opened ? '收起授权记录' : '管理与撤回授权' }}</NButton></header>
    <p class="field-help">授权从已保存任务的完整清单明确创建，仅覆盖这些固定版本与片段。每次请求仍需主动预览和提交。</p>
    <p v-if="!readable" class="notice" role="status">登录待恢复，未决撤回请求继续保留。</p>
    <div v-if="opened" v-show="readable" :inert="!readable || undefined" class="authorization-list">
      <p v-if="message" class="notice" role="status">{{ message }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="!readable || writer.busy.value" :loading="writer.busy.value" @click="retry">用原请求核对</NButton></p>
      <p v-if="deferred" class="notice" role="status">撤回回执已返回，等待接收。<NButton text :disabled="!readable" @click="receiveDeferred">接收原请求结果</NButton></p>
      <p v-if="loading" role="status">正在读取授权元数据…</p>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}<NButton text :disabled="blocked || loading" @click="load(attempted.cursor, attempted.direction)">重读这一页</NButton></p>
      <p v-if="page && !page.items.length" class="field-help">这一页没有授权记录。可从已保存的 AI 任务核对完整清单并创建。</p>
      <article v-for="entry in page?.items" :key="entry.id">
        <header><strong>{{ aiPurposeLabels[entry.purpose] }}</strong><span>{{ aiAuthorizationStateLabels[entry.effectiveState] }}</span></header>
        <p>{{ entry.provider }} · {{ entry.model }} · {{ entry.protocol === 'RESPONSES' ? 'Responses' : 'Chat Completions' }}</p>
        <p class="field-help">{{ aiDate(entry.createdAt) }} 至 {{ aiDate(entry.expiresAt) }} · 配置 {{ entry.configVersion }} / 价格 {{ entry.priceVersion }}</p>
        <p class="field-help">授权 {{ entry.id }}<template v-if="entry.revokedAt"> · 撤回于 {{ aiDate(entry.revokedAt) }}</template></p>
        <div class="row-actions"><RouterLink class="text-link" :to="{ name: 'ai', query: { item: entry.sourceTaskId } }">回原任务核对完整清单</RouterLink><NButton v-if="entry.state === 'ACTIVE'" size="small" :disabled="blocked || loading" @click="revoke(entry)">撤回这条授权</NButton></div>
      </article>
      <div class="row-actions"><NButton size="small" :disabled="blocked || loading" @click="load(cursor)">刷新授权状态</NButton><NButton v-if="previous.length" size="small" :disabled="blocked || loading" @click="load(previous[previous.length - 1], 'back')">上一页</NButton><NButton v-if="page?.nextCursor" size="small" :disabled="blocked || loading" @click="load(page.nextCursor, 'next')">下一页</NButton></div>
      <p class="field-help">撤回不能收回供应商已处理的数据，也不能保证已进入处理边界的请求物理上未发送。未发预留会释放；真实或未知费用继续按尝试保留。服务重启后，旧授权需要从原任务重新明确创建。</p>
    </div>
  </section>
</template>

<style scoped>
.authorization-manager, .authorization-list, article { display: grid; gap: 12px; min-width: 0; }.authorization-manager { padding-top: 16px; border-top: 1px solid var(--line); }
header, .row-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 9px 14px; }.row-actions { justify-content: flex-start; }
article { padding: 16px 0; border-bottom: 1px solid var(--line); }p { margin: 0; font-size: 13px; overflow-wrap: anywhere; }header span, .text-link { font-size: 13px; }
</style>
