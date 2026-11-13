<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText } from '../api/client'
import { archiveStates, checkedArchiveCreation, readArchiveJobs, readArchiveStatus } from '../api/dataArchive'
import type { ArchiveJob, ArchivePage, ArchiveStatus } from '../api/dataArchive'
import { formatPreferenceBytes } from '../api/preferences'
import { useCommand } from '../composables/useCommand'
import { usePreferencesStore } from '../state/preferences'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useWorkspaceStore } from '../state/workspace'
const props = defineProps<{ enabled: boolean; recoveryEnabled?: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), preferences = usePreferencesStore(), writer = useCommand()
const element = ref<HTMLElement>(), active = ref(true), elementVisible = ref(false)
const documentVisible = ref(document.visibilityState === 'visible')
const confirmed = ref(false), message = ref(''), cleared = ref(false), loading = ref(false), problem = ref('')
const status = shallowRef<ArchiveStatus>(), jobs = shallowRef<ArchivePage>()
const recoveryReadable = computed(() => active.value && Boolean(props.recoveryEnabled ?? props.enabled)
  && store.sessionActive && documentVisible.value && elementVisible.value)
const trainingAllowed = computed(() => !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const readable = computed(() => recoveryReadable.value && props.enabled && trainingAllowed.value)
const ready = computed(() => readable.value && status.value?.enabled === true && status.value.lifecycleReady === true)
const retryAllowed = computed(() => recoveryReadable.value && trainingAllowed.value && !loading.value && !writer.busy.value)
const changes = reactive({ dirty: false, locked: false })
let alive = true, generation = 0, sequence = 0, observer: MutationObserver | undefined
let commandContext = context()
watch([confirmed, writer.locked], ([dirty, locked]) => {
  changes.dirty = dirty; changes.locked = locked; store.changes.set('data-archive-command', changes)
}, { immediate: true, flush: 'sync' })
function clearSensitive() {
  generation++; sequence++; confirmed.value = false; status.value = undefined; jobs.value = undefined
  message.value = ''; problem.value = ''; loading.value = false
}
watch([readable, recoveryReadable, () => store.sessionEpoch, () => store.sessionActive,
  () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  if (confirmed.value || status.value || jobs.value) cleared.value = true
  // 访问变化撤回列表与确认，但不能清掉可能已经提交的原请求和幂等键。
  clearSensitive()
  if (readable.value && !writer.locked.value) void refresh()
}, { immediate: true, flush: 'sync' })
function elementIsVisible() {
  const node = element.value
  if (!node || !node.getClientRects().length || node.closest('[hidden], [inert], [aria-hidden="true"]')) return false
  for (let ancestor: HTMLElement | null = node; ancestor; ancestor = ancestor.parentElement) {
    const style = getComputedStyle(ancestor)
    if (style.visibility === 'hidden' || style.visibility === 'collapse') return false
  }
  return true
}
function visibilityChanged() { documentVisible.value = document.visibilityState === 'visible' }
function elementVisibilityChanged() { elementVisible.value = elementIsVisible() }
onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged); elementVisibilityChanged()
  observer = new MutationObserver(elementVisibilityChanged)
  for (let node: HTMLElement | null = element.value ?? null; node; node = node.parentElement) {
    observer.observe(node, { attributes: true, attributeFilter: ['style', 'class', 'hidden', 'inert', 'aria-hidden'] })
  }
})
onActivated(() => { active.value = true; visibilityChanged(); elementVisibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; clearSensitive(); observer?.disconnect(); document.removeEventListener('visibilitychange', visibilityChanged)
  if (store.changes.get('data-archive-command') === changes) store.changes.delete('data-archive-command')
})
function context() { return { generation, session: store.sessionEpoch, access: currentTrainingAccessEpoch() } }
function current(ticket: ReturnType<typeof context>, recovery = false) {
  return alive && (recovery ? recoveryReadable.value && trainingAllowed.value : readable.value)
    && document.visibilityState === 'visible' && elementIsVisible() && ticket.generation === generation
    && ticket.session === store.sessionEpoch && ticket.access === currentTrainingAccessEpoch()
}
function syncVisibility() { visibilityChanged(); elementVisibilityChanged() }
async function refresh() {
  syncVisibility()
  if (!current(context()) || loading.value || writer.locked.value) return
  const ticket = context(), request = ++sequence
  confirmed.value = false; status.value = undefined; jobs.value = undefined; problem.value = ''; loading.value = true
  const live = () => current(ticket) && request === sequence && !writer.locked.value
  try {
    const value = await readArchiveStatus()
    if (!live()) return
    status.value = value
    if (!value.enabled || !value.lifecycleReady) return
    const page = await readArchiveJobs()
    if (live()) jobs.value = page
  } catch (error) { if (live()) { status.value = undefined; jobs.value = undefined; problem.value = errorText(error) } }
  finally { if (alive && request === sequence) loading.value = false }
}
function create() {
  syncVisibility()
  if (!current(context()) || !ready.value || !status.value || !confirmed.value || loading.value || writer.locked.value) return
  const expected = status.value
  let original: ArchiveJob | undefined
  commandContext = context(); jobs.value = undefined; problem.value = ''; message.value = ''
  void writer.run<unknown>('/lifecycle/archives', 'POST', { confirmedSensitiveData: true }, value => {
    const job = checkedArchiveCreation(value, expected, original)
    original ??= job
    if (!current(commandContext, true)) {
      throw new ApiError(0, 'ARCHIVE_CONTEXT_CHANGED', '页面、登录或资料权限已变化，请恢复访问后用原请求核对导出登记结果。')
    }
    confirmed.value = false; message.value = '导出作业已登记。可重新读取查看生成结果。'
    if (readable.value) void refresh()
  })
}
function retry() {
  syncVisibility()
  if (!retryAllowed.value || !writer.pending.value || !current(context(), true)) return
  commandContext = context(); sequence++; loading.value = false; jobs.value = undefined; problem.value = ''; message.value = ''
  void writer.retry()
}
function download(event: MouseEvent, job: ArchiveJob) {
  syncVisibility()
  if (!current(context()) || !ready.value || writer.locked.value || loading.value || !job.downloadable
    || job.state !== 'READY' || Date.parse(job.expiresAt) <= Date.now()
    || !jobs.value?.items.some(value => value === job)) {
    event.preventDefault()
    if (readable.value) problem.value = '当前下载条件已变化，请重新读取作业；服务器会再次核对实际下载资格。'
  }
}
</script>
<template>
  <section ref="element" class="archive-panel" aria-label="完整数据导出">
    <header class="row-actions"><h2>完整数据导出</h2><NButton :loading="loading" :disabled="!readable || loading || writer.locked.value" @click="refresh">重新读取</NButton></header>
    <p>业务归档包含正文、对象身份、关系、全部版本与业务记录，并校验私有附件。包内排除凭据、HTTP会话、环境配置、独立生命周期账本原文件、索引、历史导出包与临时文件；具体范围见当前服务器排除清单。</p>
    <p class="field-help">本页登记导出作业并提供浏览器下载入口。完整恢复由服务器独立工具在空恢复环境执行，须先预览格式、身份冲突和缺失附件，再核对最新独立删除／撤权账本；处理清除、撤权和索引后才能开放访问。不能用旧导出覆盖正在使用的数据库。</p>
    <p v-if="!readable" class="notice" role="status">当前页面或资料权限不允许读取导出状态和作业清单。恢复访问后重新读取，旧确认不再用于创建新导出。</p>
    <p v-if="cleared && recoveryReadable" class="field-help">页面、会话或资料权限变化后已撤回旧清单和确认；未知写入仍保留原请求。</p>
    <div v-if="recoveryReadable && writer.pending.value" class="row-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!retryAllowed" @click="retry">用原请求确认登记结果</NButton><span class="field-help">只接续已经确认的原请求；须先恢复当前训练的资料访问条件，不重新创建第二个作业。</span></div>
    <p v-if="readable && writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}</p>
    <template v-if="readable">
      <p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
      <template v-if="status">
        <p v-if="!status.enabled" class="notice">服务器尚未启用完整导出。部署时需配置独立导出目录和生命周期账本。</p>
        <p v-else-if="!status.lifecycleReady" class="notice">独立删除／撤权账本尚未就绪，导出保持关闭。</p>
        <p class="field-help">格式 v{{ status.formatVersion }} · 最多 {{ formatPreferenceBytes(status.maximumBytes) }}／{{ status.maximumRows }} 条记录 · 生成后保留至作业标记的失效时间（配置 {{ status.expiryHours }} 小时）。清除或撤权后旧包失去下载资格，已下载副本需要自行妥善管理。</p>
        <details><summary>导出范围的明确排除项</summary><ul><li v-for="value in status.exclusions" :key="value">{{ value }}</li></ul></details>
      </template>
      <NCheckbox v-model:checked="confirmed" :disabled="!ready || loading || writer.locked.value">我知道完整包包含私人正文与历史记录，将妥善保管下载文件</NCheckbox>
      <div class="row-actions"><NButton type="primary" :disabled="!ready || !confirmed || loading || writer.locked.value" @click="create">生成完整导出包</NButton></div>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="jobs && !jobs.items.length" class="field-help">尚无完整导出作业。</p>
      <article v-for="job in jobs?.items" :key="job.id" class="archive-job">
        <div class="row-actions"><strong>{{ archiveStates[job.state] }}</strong><code>{{ job.id }}</code></div>
        <p class="field-help">创建 {{ preferences.formatDate(job.createdAt) }} · 失效 {{ preferences.formatDate(job.expiresAt) }}</p>
        <p v-if="job.rowCount !== null">{{ job.rowCount }} 条记录 · {{ job.fileCount }} 份文件 · {{ job.byteSize === null ? '—' : formatPreferenceBytes(job.byteSize) }}</p>
        <p v-if="job.sha256" class="field-help">SHA-256 <code>{{ job.sha256 }}</code></p>
        <p v-if="job.errorCode" class="notice error">{{ job.errorCode }}。请检查服务器日志与当前后台作业，修复后重新生成。</p>
        <a v-if="ready && job.downloadable && !writer.locked.value && !loading" class="text-link" :href="`/api/v1/lifecycle/archives/${job.id}/content`" download @click="download($event, job)">下载完整包</a>
        <p v-else-if="job.state === 'READY'" class="field-help">当前不能下载：有效期、操作状态或独立生命周期账本可能已变化，请重新读取。</p>
      </article>
      <p v-if="jobs?.items.some(job => job.downloadable)" class="field-help">下载由浏览器发起，服务器会再次核对资格。请核对浏览器实际下载结果，并使用列出的 SHA-256 校验文件。</p>
    </template>
  </section>
</template>
<style scoped>
.archive-panel { display: grid; gap: 16px; }
.archive-panel h2 { margin-right: auto; }
.archive-job { display: grid; gap: 9px; padding: 18px 0; border-top: 1px solid var(--line); }
code { overflow-wrap: anywhere; }
</style>
