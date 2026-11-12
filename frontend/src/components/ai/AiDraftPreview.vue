<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { NButton } from 'naive-ui'
import type { AiAttempt, AiStatus } from '../../api/ai'
import { currentTrainingAccessEpoch } from '../../api/client'
import { aiDraftFrameFrom, aiDraftIdValid, aiDraftPath, aiDraftTextBytes, aiDraftTextLimit } from '../../api/aiDraftStream'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ taskId: string; attempt?: AiAttempt; taskStatus: AiStatus; streaming?: boolean | null; disabled?: boolean }>()
const emit = defineEmits<{ refresh: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const active = ref(true), visible = ref(document.visibilityState === 'visible'), focused = ref(document.hasFocus())
const status = ref<'idle' | 'connecting' | 'live' | 'stopped' | 'error'>('idle'), text = ref(''), message = ref('')
const identityValid = computed(() => aiDraftIdValid(props.taskId) && aiDraftIdValid(props.attempt?.id)
  && Number.isSafeInteger(props.attempt?.number) && props.attempt!.number >= 1 && props.attempt!.number <= 3)
const hasAccess = computed(() => store.sessionActive && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const readable = computed(() => active.value && visible.value && focused.value && hasAccess.value && !props.disabled && identityValid.value)
const running = computed(() => props.taskStatus === 'RUNNING' && props.attempt?.status === 'RUNNING')
const enabled = computed(() => readable.value && props.streaming === true && running.value)
const connected = computed(() => status.value === 'connecting' || status.value === 'live')
const gateMessage = computed(() => !hasAccess.value ? '实时正文属于辅助资料。请先恢复登录并确认资料访问；这里不会代你改变辅助或提前反馈条件。'
  : props.streaming !== true ? '这次任务未启用实时预览，仍可通过正式任务记录查看结果。'
  : !identityValid.value ? '尚未取得当前尝试，重新读取任务后再查看。'
  : !running.value ? props.taskStatus === 'QUEUED' ? '任务尚在排队，开始处理后可主动查看实时预览。'
    : props.taskStatus === 'VALIDATING' ? '生成已结束，正在校验。校验后的结果由下方正式记录展示。'
    : '这次尝试已停止或完成，请查看正式结果与费用记录。'
  : !readable.value ? '当前页面或任务暂不可读，实时正文已清空。恢复后需要重新点击查看。' : '')
let alive = true, generation = 0, source: EventSource | undefined, timer: number | undefined
let receivedSnapshot = false, sequence = 0, bytes = 0, lastActivity = 0, startedAt = 0

function stop(notice: string, failed = false) {
  generation++
  const previous = source; source = undefined; previous?.close()
  if (timer !== undefined) { window.clearInterval(timer); timer = undefined }
  text.value = ''; bytes = 0; sequence = 0; receivedSnapshot = false
  status.value = failed ? 'error' : 'stopped'; message.value = notice
}
function contextChanged() { if (connected.value || text.value) stop('查看条件已变化，实时正文已清空；恢复后请重新点击查看。') }
watch([readable, () => store.sessionEpoch, () => props.taskId, () => props.attempt?.id, () => props.taskStatus, () => props.attempt?.status,
  () => props.streaming, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], contextChanged, { flush: 'sync' })
function visibilityChanged() { visible.value = document.visibilityState === 'visible'; if (!visible.value) stop('页面已隐藏，已停止查看并清空实时正文。') }
function pageHidden() { visible.value = false; stop('页面已离开，已停止查看并清空实时正文。') }
function pageShown() { visible.value = document.visibilityState === 'visible'; focused.value = document.hasFocus() }
function lostFocus() { focused.value = false; stop('页面已失去焦点，已停止查看并清空实时正文。') }
function gainedFocus() { focused.value = document.hasFocus() }
onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged)
  window.addEventListener('pagehide', pageHidden)
  window.addEventListener('pageshow', pageShown)
  window.addEventListener('blur', lostFocus); window.addEventListener('focus', gainedFocus)
})
onActivated(() => { active.value = true; visible.value = document.visibilityState === 'visible'; focused.value = document.hasFocus() })
onDeactivated(() => { active.value = false; stop('页面已切换，已停止查看并清空实时正文。') })
onUnmounted(() => {
  alive = false; stop('')
  document.removeEventListener('visibilitychange', visibilityChanged); window.removeEventListener('pagehide', pageHidden)
  window.removeEventListener('pageshow', pageShown)
  window.removeEventListener('blur', lostFocus); window.removeEventListener('focus', gainedFocus)
})

function start() {
  if (!enabled.value || connected.value || !props.attempt) return
  stop('')
  const taskId = props.taskId, attemptId = props.attempt.id, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), ticket = generation
  let connection: EventSource
  try { connection = new EventSource(aiDraftPath(taskId, attemptId), { withCredentials: true }) }
  catch { stop('实时预览连接未建立，可稍后重新查看或刷新正式状态。', true); return }
  source = connection; status.value = 'connecting'; startedAt = performance.now(); lastActivity = startedAt
  // 每次连接从 snapshot 重新开始；不复用旧前缀，不保存 SSE 游标或触发任何模型重发。
  const current = () => alive && enabled.value && source === connection && generation === ticket && store.sessionEpoch === epoch
    && currentTrainingAccessEpoch() === accessEpoch && props.taskId === taskId && props.attempt?.id === attemptId
  const fail = (notice: string) => { connection.close(); if (source === connection) stop(notice, true) }
  const receive = (kind: string, event: MessageEvent) => {
    if (!current()) { connection.close(); if (source === connection) stop('访问或任务身份已变化，实时正文已清空。'); return }
    const frame = event.lastEventId === '' ? aiDraftFrameFrom(kind, event.data, taskId, attemptId) : undefined
    if (!frame) { fail('实时预览事件无法核对，已清空正文。请重新查看或刷新正式状态。'); return }
    lastActivity = performance.now()
    if (frame.kind === 'closed') {
      stop(frame.reason === 'FINISHED' ? '实时生成已结束，正在刷新正式任务状态。'
        : frame.reason === 'ACCESS_CHANGED' ? '来源或访问条件已变化，实时正文已清空，请重新核对资料权限。'
        : frame.reason === 'RECONNECT' ? '本次查看连接已结束；需要继续时，请重新点击查看，从新快照开始。'
        : '此尝试当前没有可读取的暂态正文，可刷新正式状态。')
      if (frame.reason === 'FINISHED') emit('refresh')
      return
    }
    if (frame.kind === 'snapshot') {
      if (receivedSnapshot) { fail('同一连接收到重复快照，已停止查看。请重新连接读取完整快照。'); return }
      text.value = frame.text; bytes = aiDraftTextBytes(frame.text); sequence = 0; receivedSnapshot = true; status.value = 'live'
      return
    }
    if (frame.kind === 'heartbeat') return
    const deltaBytes = aiDraftTextBytes(frame.text)
    if (!receivedSnapshot || frame.sequence !== sequence + 1 || frame.offset !== text.value.length || bytes + deltaBytes > aiDraftTextLimit) {
      fail('实时片段顺序、位置或大小无法核对，已清空正文。请重新查看获取新快照。'); return
    }
    text.value += frame.text; bytes += deltaBytes; sequence = frame.sequence
  }
  connection.addEventListener('snapshot', event => receive('snapshot', event as MessageEvent))
  connection.addEventListener('delta', event => receive('delta', event as MessageEvent))
  connection.addEventListener('heartbeat', event => receive('heartbeat', event as MessageEvent))
  connection.addEventListener('closed', event => receive('closed', event as MessageEvent))
  connection.onmessage = () => fail('收到未定义的实时预览事件，已停止查看。')
  connection.onerror = () => {
    // 先关闭原生自动重连；失败只影响暂态预览，任务、费用和原请求保持各自状态。
    connection.close()
    if (source === connection) stop('实时预览连接中断，已清空正文。可主动重新查看或刷新正式状态；不会自动重发模型请求。', true)
  }
  if (timer !== undefined) window.clearInterval(timer)
  timer = window.setInterval(() => {
    if (source !== connection) return
    if (!current()) { connection.close(); stop('登录、资料权限或当前任务已变化，实时正文已清空。'); return }
    const now = performance.now()
    if (!receivedSnapshot && now - startedAt > 15000 || now - lastActivity > 30000) fail('实时预览暂未响应，已停止等待并清空正文。请刷新任务状态或稍后重新查看。')
  }, 500)
}
</script>

<template>
  <section class="draft-preview" aria-label="尚未校验的模型实时预览">
    <header><h3>实时预览 · 尚未校验</h3><span v-if="attempt" class="field-help">第 {{ attempt.number }} 次尝试</span></header>
    <p class="field-help">这里按纯文本显示尚未完整生成的原始 JSON，可能不完整或不正确。正式结果通过校验后在原有结果区展示；预览内容不能直接采用。</p>
    <p v-if="gateMessage" class="notice" role="status">{{ gateMessage }}</p>
    <div class="row-actions"><NButton v-if="!connected" size="small" :disabled="!enabled" @click="start">查看实时预览</NButton><NButton v-else size="small" @click="stop('已停止查看并清空实时正文。模型任务继续按原流程处理。')">停止查看</NButton><NButton text size="small" :disabled="!store.sessionActive || disabled" @click="emit('refresh')">刷新正式任务状态</NButton></div>
    <p v-if="connected" class="field-help" role="status">{{ status === 'connecting' ? '正在连接这次尝试…' : text ? '正在接收未校验的原始输出…' : '已连接，等待原始输出…' }}</p>
    <p v-else-if="message" :class="status === 'error' ? 'notice error' : 'field-help'" role="status">{{ message }}</p>
    <pre v-if="readable && connected && text" class="draft-text" tabindex="0" aria-label="未校验的原始 JSON 文本">{{ text }}</pre>
    <p class="field-help">停止查看或连接中断只清空预览。是否完成、是否收费，以任务和每次尝试记录为准；页面隐藏或失焦后需要主动重新查看。</p>
  </section>
</template>

<style scoped>
.draft-preview { display: grid; gap: 12px; min-width: 0; padding: 18px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
header, .row-actions { display: flex; flex-wrap: wrap; align-items: baseline; gap: 10px 16px; }h3 { margin: 0; font-size: 15px; }p { margin: 0; overflow-wrap: anywhere; }
.draft-text { margin: 0; padding: 14px; max-height: 360px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 5px; color: var(--ink); font: 13px/1.75 var(--font-mono, monospace); }
.draft-text:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
</style>
