<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NInputNumber, NSelect } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText } from '../api/client'
import { defaultPreferences, formatPreferenceBytes, preferenceMotions, preferenceReceiptValid, preferenceThemes,
  readPreferences, readPreferenceUsage, samePreferences, savePreferencesProblem } from '../api/preferences'
import type { Preferences, PreferenceUsage, SavePreferences } from '../api/preferences'
import { useCommand } from '../composables/useCommand'
import { usePreferencesStore } from '../state/preferences'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

type Draft = Omit<SavePreferences, 'expectedRevision' | 'fontSize' | 'contentWidth'> & { fontSize: number | null; contentWidth: number | null }
const workspace = useWorkspaceStore(), access = useTrainingAccessStore(), preferences = usePreferencesStore(), writer = useCommand()
const draft = reactive<Draft>(formOf(defaultPreferences))
const baseline = shallowRef<Preferences>(), current = shallowRef<Preferences>(), serverCopy = shallowRef<Preferences>()
const usage = shallowRef<PreferenceUsage>(), reading = ref(false), usageLoading = ref(false)
const settingsError = ref(''), usageError = ref(''), message = ref(''), readReady = ref(false), needsReview = ref(false)
const replaceConfirmed = ref(false), visible = ref(document.visibilityState === 'visible')
const changes = reactive({ dirty: false, locked: false }), stateKey = 'preferences'
let alive = true, generation = 0, readSequence = 0, usageSequence = 0
let commandEpoch = -1, commandGeneration = -1, commandTraining = -1
const readable = computed(() => workspace.sessionActive && visible.value && !access.checking && !access.challenge && !access.restricted
  && !workspace.changes.get('training-access-command')?.locked)
const dirty = computed(() => Boolean(baseline.value && signature(draft) !== signature(formOf(baseline.value))))
const blocked = computed(() => !readable.value || writer.locked.value || reading.value)
const editable = computed(() => Boolean(baseline.value) && !blocked.value)
const problem = computed(() => baseline.value ? savePreferencesProblem({ expectedRevision: baseline.value.revision, ...draft }) : undefined)
const stale = computed(() => Boolean(baseline.value && current.value && !samePreferences(baseline.value, current.value)))
const canSave = computed(() => editable.value && dirty.value && readReady.value && !needsReview.value && !stale.value && !problem.value && !settingsError.value)
const usageRows: { key: keyof Pick<PreferenceUsage, 'notes' | 'concepts' | 'knowledgeVersions' | 'boards' | 'preparationItems'
  | 'questions' | 'trainingSessions' | 'submittedAnswers' | 'memoryCards' | 'attachments' | 'aiTasks'>; label: string }[] = [
  { key: 'notes', label: '笔记' }, { key: 'concepts', label: '知识点' }, { key: 'knowledgeVersions', label: '知识历史版本' },
  { key: 'boards', label: '脑图与白板' }, { key: 'preparationItems', label: '准备材料' }, { key: 'questions', label: '题目' },
  { key: 'trainingSessions', label: '训练会话' }, { key: 'submittedAnswers', label: '正式回答' },
  { key: 'memoryCards', label: '回忆卡片' }, { key: 'attachments', label: '附件记录' }, { key: 'aiTasks', label: 'AI 任务' },
]

watch([readable, () => workspace.sessionEpoch], () => {
  generation++; readSequence++; usageSequence++; reading.value = false; usageLoading.value = false; readReady.value = false
  serverCopy.value = undefined; replaceConfirmed.value = false
  if (baseline.value && (dirty.value || writer.locked.value)) needsReview.value = true
  if (readable.value) { void loadSettings(false); void loadUsage() }
}, { immediate: true, flush: 'sync' })
watch([dirty, writer.locked], ([changed, locked]) => {
  changes.dirty = changed; changes.locked = locked; workspace.changes.set(stateKey, changes)
}, { immediate: true, flush: 'sync' })
watch(draft, () => { replaceConfirmed.value = false; message.value = '' }, { deep: true, flush: 'sync' })
watch(writer.error, reason => {
  if (reason instanceof ApiError && reason.status === 409 && !writer.pending.value) {
    needsReview.value = true; readReady.value = false
    if (readable.value) void loadSettings(true)
  }
})
watch(writer.locked, locked => { if (!locked && readable.value && !readReady.value && !reading.value) void loadSettings(true) })
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onUnmounted(() => {
  alive = false; generation++; readSequence++; usageSequence++
  document.removeEventListener('visibilitychange', visibilityChanged)
  if (workspace.changes.get(stateKey) === changes) workspace.changes.delete(stateKey)
})

function formOf(value: Readonly<Preferences>): Draft {
  return { theme: value.theme, fontSize: value.fontSize, contentWidth: value.contentWidth, motion: value.motion,
    timeZone: value.timeZone, saveRecoveryNotice: value.saveRecoveryNotice }
}
function signature(value: Draft): string {
  return JSON.stringify([value.theme, value.fontSize, value.contentWidth, value.motion, value.timeZone, value.saveRecoveryNotice])
}
function visibilityChanged() { visible.value = document.visibilityState === 'visible' }
function requireCurrent(epoch: number, stamp: number, training: number) {
  if (!alive || !readable.value || epoch !== workspace.sessionEpoch || stamp !== generation || training !== currentTrainingAccessEpoch())
    throw new ApiError(0, 'PREFERENCES_CONTEXT_CHANGED', '会话或训练访问条件已变化。输入与原请求保留，请恢复访问后重试。')
}
function adopt(value: Preferences, keepLocal: boolean) {
  baseline.value = { ...value }
  if (!keepLocal) Object.assign(draft, formOf(value))
  needsReview.value = false; replaceConfirmed.value = false
}
function updateTheme(value: unknown) { if (editable.value && (value === 'SYSTEM' || value === 'LIGHT' || value === 'DARK')) draft.theme = value }
function updateMotion(value: unknown) { if (editable.value && (value === 'SYSTEM' || value === 'REDUCED' || value === 'FULL')) draft.motion = value }
function updateNumber(field: 'fontSize' | 'contentWidth', value: number | null) { if (editable.value) draft[field] = value }
function updateZone(value: string) { if (editable.value) draft.timeZone = value }
function updateNotice(value: boolean) { if (editable.value) draft.saveRecoveryNotice = value }
function chooseBrowserZone() {
  if (!editable.value) return
  try { draft.timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone }
  catch { message.value = '当前浏览器无法提供时区，请手动填写。' }
}

async function loadSettings(compare: boolean) {
  if (!readable.value || writer.locked.value) return
  const epoch = workspace.sessionEpoch, stamp = generation, training = currentTrainingAccessEpoch(), sequence = ++readSequence
  reading.value = true; settingsError.value = ''; replaceConfirmed.value = false
  try {
    const value = await readPreferences()
    requireCurrent(epoch, stamp, training)
    if (sequence !== readSequence) return
    if (current.value && (value.revision < current.value.revision
      || value.revision === current.value.revision && !samePreferences(value, current.value)))
      throw new ApiError(502, 'PREFERENCES_RESPONSE_INVALID', '服务器设置修订与已读取内容不一致，已保留本地输入。')
    if (!preferences.apply(value)) throw new ApiError(502, 'PREFERENCES_RESPONSE_INVALID', '服务器返回较旧修订，请重新核对设置。')
    current.value = { ...value }; readReady.value = true
    // 重读只刷新服务器对照；已有本地改动不能被后台响应覆盖。
    if (!baseline.value || !dirty.value && !needsReview.value && !compare) adopt(value, false)
    else {
      if (!samePreferences(baseline.value, value)) needsReview.value = true
      serverCopy.value = { ...value }
    }
    if (compare) serverCopy.value = { ...value }
  } catch (reason) {
    if (alive && sequence === readSequence && epoch === workspace.sessionEpoch && stamp === generation && readable.value) {
      settingsError.value = errorText(reason); readReady.value = false
    }
  } finally { if (alive && sequence === readSequence) reading.value = false }
}
async function loadUsage() {
  if (!readable.value || usageLoading.value) return
  const epoch = workspace.sessionEpoch, stamp = generation, training = currentTrainingAccessEpoch(), sequence = ++usageSequence
  usageLoading.value = true; usageError.value = ''
  try {
    const value = await readPreferenceUsage()
    requireCurrent(epoch, stamp, training)
    if (sequence === usageSequence) usage.value = value
  } catch (reason) {
    if (alive && sequence === usageSequence && epoch === workspace.sessionEpoch && stamp === generation && readable.value) usageError.value = errorText(reason)
  } finally { if (alive && sequence === usageSequence) usageLoading.value = false }
}
function save() {
  if (!canSave.value || !baseline.value || draft.fontSize === null || draft.contentWidth === null) return
  const body: SavePreferences = { ...draft, fontSize: draft.fontSize, contentWidth: draft.contentWidth, expectedRevision: baseline.value.revision }
  if (savePreferencesProblem(body)) return
  commandEpoch = workspace.sessionEpoch; commandGeneration = generation; commandTraining = currentTrainingAccessEpoch(); message.value = ''
  void writer.run<unknown>('/preferences', 'PUT', body, value => {
    requireCurrent(commandEpoch, commandGeneration, commandTraining)
    if (!preferenceReceiptValid(value, body)) throw new ApiError(502, 'PREFERENCES_RECEIPT_INVALID', '保存回执与原请求不一致。请保留输入，用原请求核对结果。')
    if (current.value?.revision === value.revision && !samePreferences(current.value, value))
      throw new ApiError(502, 'PREFERENCES_RECEIPT_INVALID', '同一修订的保存回执出现不同内容，请用原请求核对。')
    const applied = preferences.apply(value)
    if (!current.value || value.revision >= current.value.revision) {
      current.value = { ...value }
    }
    adopt(value, false); settingsError.value = ''; serverCopy.value = undefined; readReady.value = applied
    needsReview.value = Boolean(current.value && !samePreferences(value, current.value)) || !applied
    message.value = `个人设置已保存（修订 ${value.revision}）。${needsReview.value ? '服务器已有更新，请再核对最新设置。' : ''}`
    if (needsReview.value) void loadSettings(true)
  })
}
function retry() {
  if (!readable.value || writer.busy.value || !writer.pending.value) return
  commandEpoch = workspace.sessionEpoch; commandGeneration = generation; commandTraining = currentTrainingAccessEpoch()
  void writer.retry()
}
function adoptServer(keepLocal: boolean) {
  if (blocked.value || !readReady.value || !serverCopy.value || !current.value || !samePreferences(serverCopy.value, current.value)
    || !keepLocal && dirty.value && !replaceConfirmed.value) return
  adopt(serverCopy.value, keepLocal); serverCopy.value = undefined; writer.error.value = undefined
  message.value = keepLocal ? '本地输入已保留，保存基线已更新；请核对后保存。' : '已采用服务器设置。'
}
function displayTime(value: string) { return preferences.formatDate(value) }
</script>

<template>
  <section class="preferences-workspace" aria-labelledby="preferences-title">
    <header class="page-heading">
      <div><h1 id="preferences-title">个人设置</h1><p class="muted">调整阅读与工作习惯，查看个人工作区用量。</p></div>
      <span v-if="baseline && readable" class="status-pill">设置修订 {{ baseline.revision }}{{ dirty ? ' · 有未保存修改' : '' }}</span>
    </header>
    <p v-if="!readable" class="notice" role="status">设置暂不可读取。恢复会话或训练访问条件后继续；当前输入与未决请求保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="preferences-content">
      <section class="preference-section" aria-labelledby="appearance-title">
        <header><div><h2 id="appearance-title">显示与阅读</h2><p class="field-help">保存后应用到当前工作台。系统主题变化会自动跟随。</p></div><NButton text :disabled="blocked" :loading="reading" @click="loadSettings(true)">核对服务器设置</NButton></header>
        <p v-if="!baseline && reading" class="notice" role="status">正在读取个人设置…</p>
        <p v-if="settingsError" class="notice error" role="alert">{{ settingsError }}</p>
        <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }} <NButton v-if="writer.pending.value" text :disabled="!readable || writer.busy.value" :loading="writer.busy.value" @click="retry">用原请求重试</NButton></p>
        <p v-if="message" class="notice" role="status">{{ message }}</p>
        <p v-if="needsReview || stale" class="notice" role="status">设置修订或访问条件已变化，输入已保留。请读取服务器设置后，选择保留输入或采用服务器值。</p>
        <template v-if="baseline">
          <div class="preference-fields">
            <label><span>主题</span><NSelect :value="draft.theme" :options="preferenceThemes" :disabled="!editable" aria-label="工作台主题" @update:value="updateTheme" /></label>
            <label><span>动态效果</span><NSelect :value="draft.motion" :options="preferenceMotions" :disabled="!editable" aria-label="动态效果偏好" @update:value="updateMotion" /><small class="field-help">系统要求减少动态效果时，始终尊重系统设置。</small></label>
            <label><span>阅读字号（像素）</span><NInputNumber :value="draft.fontSize" :min="12" :max="24" :step="1" :precision="0" :disabled="!editable" aria-label="阅读字号" @update:value="updateNumber('fontSize', $event)" /></label>
            <label><span>工作区最大宽度（像素）</span><NInputNumber :value="draft.contentWidth" :min="720" :max="1600" :step="40" :precision="0" :disabled="!editable" aria-label="工作区最大宽度" @update:value="updateNumber('contentWidth', $event)" /><small class="field-help">小屏幕始终按可用空间缩小，不强制横向撑开。</small></label>
          </div>
          <div class="preference-reading-sample" :style="{ fontSize: `${draft.fontSize ?? 14}px` }"><strong>阅读预览</strong><p>把面试中的问题整理为自己的知识，在下一次回答中验证理解。</p></div>
          <div class="preference-timezone">
            <label for="preference-timezone"><span>个人显示时区</span><NInput id="preference-timezone" :value="draft.timeZone" :disabled="!editable" placeholder="例如 Asia/Shanghai 或 UTC" :input-props="{ spellcheck: false, maxlength: 64 }" @update:value="updateZone" /></label>
            <NButton :disabled="!editable" @click="chooseBrowserZone">使用浏览器时区</NButton>
            <p class="field-help">用于设置与用量记录的时间显示；不会移动历史 UTC 时刻。回忆排程的复习日时区请在回忆设置中单独维护。</p>
          </div>
          <div class="preference-recovery">
            <h3>保存与恢复提醒</h3>
            <NCheckbox :checked="draft.saveRecoveryNotice" :disabled="!editable" @update:checked="updateNotice">显示全局待保存与未决请求提醒</NCheckbox>
            <p class="field-help">各编辑器仍需按原流程明确保存。开启提醒不代表自动保存或刷新恢复；关闭提醒也不关闭离开页面时的草稿与未决请求保护。</p>
          </div>
          <p v-if="problem" class="field-help" role="status">{{ problem }}</p>
          <div class="row-actions"><NButton type="primary" :disabled="!canSave" :loading="writer.busy.value" @click="save">保存个人设置</NButton><span class="field-help">{{ baseline.updatedAt ? `最近保存：${displayTime(baseline.updatedAt)}` : '尚未保存，当前为默认设置。' }}</span></div>
        </template>
        <section v-if="serverCopy" class="preference-server-copy" aria-labelledby="preference-server-title">
          <h3 id="preference-server-title">服务器设置 · 修订 {{ serverCopy.revision }}</h3>
          <dl><div><dt>主题与动态效果</dt><dd>{{ preferenceThemes.find(item => item.value === serverCopy?.theme)?.label }} · {{ preferenceMotions.find(item => item.value === serverCopy?.motion)?.label }}</dd></div><div><dt>字号与宽度</dt><dd>{{ serverCopy.fontSize }} px · {{ serverCopy.contentWidth }} px</dd></div><div><dt>显示时区</dt><dd>{{ serverCopy.timeZone }}</dd></div><div><dt>待保存提醒</dt><dd>{{ serverCopy.saveRecoveryNotice ? '开启' : '关闭' }}</dd></div></dl>
          <NCheckbox v-if="dirty" v-model:checked="replaceConfirmed" :disabled="blocked">允许用服务器设置替换当前未保存输入</NCheckbox>
          <div class="row-actions"><NButton :disabled="blocked || !readReady" @click="adoptServer(true)">保留输入，采用服务器修订</NButton><NButton :disabled="blocked || !readReady || (dirty && !replaceConfirmed)" @click="adoptServer(false)">采用服务器设置</NButton></div>
        </section>
      </section>
      <section class="preference-section" aria-labelledby="usage-title">
        <header><div><h2 id="usage-title">工作区用量</h2><p class="field-help">只读取数量与配额信息，包含归档对象和历史记录。</p></div><NButton text :loading="usageLoading" :disabled="!readable || usageLoading" @click="loadUsage">刷新用量</NButton></header>
        <p v-if="!usage && usageLoading" class="notice" role="status">正在统计工作区用量…</p>
        <p v-if="usageError" class="notice error" role="alert">{{ usageError }}</p>
        <template v-if="usage">
          <dl class="preference-usage-grid"><div v-for="row in usageRows" :key="row.key"><dt>{{ row.label }}</dt><dd>{{ usage[row.key].toLocaleString('zh-CN') }}</dd></div></dl>
          <div class="preference-storage"><h3>附件与解析配额</h3><p><strong>{{ formatPreferenceBytes(usage.materialReservedBytes) }}</strong> 已计入配额，共 {{ formatPreferenceBytes(usage.materialQuotaBytes) }}</p><p class="field-help">上传与解析任务预留的字节数之和，可能包含失败任务，不等同于实际磁盘占用或数据库大小。附件功能{{ usage.materialsEnabled ? '已启用' : '当前未启用' }}。</p></div>
          <p class="field-help">统计时间：{{ displayTime(usage.checkedAt) }}</p>
        </template>
      </section>
    </div>
  </section>
</template>
