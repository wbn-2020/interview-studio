<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NInputNumber } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText } from '../api/client'
import { defaultPreferences, formatPreferenceBytes, preferenceMotions, preferenceReceiptValid, preferenceThemes,
  readPreferences, readPreferenceUsage, samePreferences, savePreferencesProblem } from '../api/preferences'
import type { Preferences, PreferenceUsage, SavePreferences } from '../api/preferences'
import { useCommand } from '../composables/useCommand'
import { usePreferencesStore } from '../state/preferences'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import RIcon from '../replica/RIcon.vue'

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
      <div class="preferences-heading-identity"><p>设置 <span>/</span> 外观与数据</p><h1 id="preferences-title">设置</h1></div>
      <span v-if="baseline && readable" class="status-pill">设置修订 {{ baseline.revision }}{{ dirty ? ' · 有未保存修改' : '' }}</span>
    </header>
    <p v-if="!readable" class="notice" role="status">设置暂不可读取。恢复会话或训练访问条件后继续；当前输入与未决请求保留。</p>
    <div v-show="readable" :inert="!readable || undefined" class="preferences-content">
      <nav class="settings-directory" aria-label="设置分类"><a class="selected" href="#appearance-title"><RIcon name="sun" :size="28" />外观与数据</a><a href="#preference-reading"><RIcon name="book-open" :size="28" />阅读与写作</a><a href="#preference-motion"><RIcon name="zap" :size="28" />动效</a><a href="#preference-timezone"><RIcon name="clock" :size="28" />时区</a><a href="#preference-recovery"><RIcon name="refresh-cw" :size="28" />保存与恢复</a><RouterLink to="/settings/data"><RIcon name="upload" :size="28" />导入 / 导出</RouterLink><a href="#usage-title"><RIcon name="chart-no-axes-combined" :size="28" />数据用量</a><RouterLink to="/ai/authorizations"><RIcon name="shield-check" :size="28" />安全与授权</RouterLink></nav>
      <section class="preference-section preference-appearance" aria-labelledby="appearance-title">
        <header><div><h2 id="appearance-title"><RIcon name="sun" :size="30" />外观</h2></div><NButton text :disabled="blocked" :loading="reading" @click="loadSettings(true)">核对服务器设置</NButton></header>
        <p v-if="!baseline && reading" class="notice" role="status">正在读取个人设置…</p>
        <p v-if="settingsError" class="notice error" role="alert">{{ settingsError }}</p>
        <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }} <NButton v-if="writer.pending.value" text :disabled="!readable || writer.busy.value" :loading="writer.busy.value" @click="retry">用原请求重试</NButton></p>
        <p v-if="message" class="notice" role="status">{{ message }}</p>
        <p v-if="needsReview || stale" class="notice" role="status">设置修订或访问条件已变化，输入已保留。请读取服务器设置后，选择保留输入或采用服务器值。</p>
        <template v-if="baseline">
          <div class="preference-fields">
            <div class="preference-control"><span>主题外观</span><div class="settings-segment" role="group" aria-label="工作台主题"><button v-for="option in [{ value: 'SYSTEM', label: '跟随系统', icon: 'monitor' }, { value: 'DARK', label: '深色', icon: 'moon' }, { value: 'LIGHT', label: '浅色', icon: 'sun' }]" :key="option.value" :class="{ selected: draft.theme === option.value }" :aria-pressed="draft.theme === option.value" :disabled="!editable" @click="updateTheme(option.value)"><RIcon :name="option.icon" :size="23" />{{ option.label }}</button></div></div>
            <div class="preference-control"><span>内容宽度</span><div class="settings-segment" role="group" aria-label="工作区宽度"><button v-for="option in [{ value: 960, label: '舒适' }, { value: 1280, label: '宽' }, { value: 1600, label: '全宽' }]" :key="option.value" :class="{ selected: draft.contentWidth === option.value }" :aria-pressed="draft.contentWidth === option.value" :disabled="!editable" @click="updateNumber('contentWidth', option.value)">{{ option.label }}</button></div></div>
            <div id="preference-reading" class="preference-control"><span>正文字号</span><div class="settings-segment" role="group" aria-label="阅读字号"><button v-for="size in [15,16,17]" :key="size" :class="{ selected: draft.fontSize === size }" :aria-pressed="draft.fontSize === size" :disabled="!editable" @click="updateNumber('fontSize',size)">{{ size }}</button></div></div>
            <div id="preference-motion" class="preference-control"><span>动效</span><div><div class="settings-segment" role="group" aria-label="动态效果偏好"><button v-for="option in preferenceMotions" :key="option.value" :class="{ selected: draft.motion === option.value }" :aria-pressed="draft.motion === option.value" :disabled="!editable" @click="updateMotion(option.value)">{{ option.label }}</button></div><p class="field-help">系统要求减少动态效果时，始终尊重系统设置。</p></div></div>
            <details class="settings-exact-values"><summary>精确字号与宽度</summary><label>阅读字号（12–24）<NInputNumber :value="draft.fontSize" :min="12" :max="24" :step="1" :precision="0" :disabled="!editable" @update:value="updateNumber('fontSize',$event)" /></label><label>最大宽度（720–1600）<NInputNumber :value="draft.contentWidth" :min="720" :max="1600" :step="40" :precision="0" :disabled="!editable" @update:value="updateNumber('contentWidth',$event)" /></label></details>
          </div>
          <div class="preference-reading-sample" :style="{ fontSize: `${draft.fontSize ?? 14}px` }"><strong>阅读预览</strong><p>把面试中的问题整理为自己的知识，在下一次回答中验证理解。</p></div>
          <div class="preference-timezone">
            <label for="preference-timezone"><span>时区</span><NInput id="preference-timezone" :value="draft.timeZone" :disabled="!editable" placeholder="例如 Asia/Shanghai 或 UTC" :input-props="{ spellcheck: false, maxlength: 64 }" @update:value="updateZone" /></label>
            <NButton :disabled="!editable" @click="chooseBrowserZone">使用浏览器时区</NButton>
            <p class="field-help">用于设置与用量记录的时间显示；不会移动历史 UTC 时刻。回忆排程的复习日时区请在回忆设置中单独维护。</p>
          </div>
          <div id="preference-recovery" class="preference-recovery">
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
      <section class="preference-section preference-usage" aria-labelledby="usage-title">
        <header><div><h2 id="usage-title"><RIcon name="database" :size="28" />数据概览</h2></div><NButton text :loading="usageLoading" :disabled="!readable || usageLoading" @click="loadUsage">刷新</NButton></header>
        <p v-if="!usage && usageLoading" class="notice" role="status">正在统计工作区用量…</p>
        <p v-if="usageError" class="notice error" role="alert">{{ usageError }}</p>
        <template v-if="usage">
          <dl class="preference-usage-grid"><div v-for="row in usageRows" :key="row.key"><dt>{{ row.label }}</dt><dd>{{ usage[row.key].toLocaleString('zh-CN') }}</dd></div></dl>
          <div class="preference-storage"><h3>附件与解析配额</h3><p><strong>{{ formatPreferenceBytes(usage.materialReservedBytes) }}</strong> 已计入配额，共 {{ formatPreferenceBytes(usage.materialQuotaBytes) }}</p><p class="field-help">上传与解析任务预留的字节数之和，可能包含失败任务，不等同于实际磁盘占用或数据库大小。附件功能{{ usage.materialsEnabled ? '已启用' : '当前未启用' }}。</p></div>
          <p class="field-help">统计时间：{{ displayTime(usage.checkedAt) }}</p>
        </template>
        <div class="settings-export"><h3>导出数据</h3><p class="field-help">导出你的知识与学习数据</p><RouterLink class="settings-export-primary" to="/settings/data"><RIcon name="download" :size="23" />完整导出</RouterLink><RouterLink to="/knowledge?view=list"><RIcon name="file-text" :size="23" />Markdown 导出</RouterLink><RouterLink to="/knowledge/boards"><RIcon name="braces" :size="23" />JSON Canvas 导出</RouterLink><p class="field-help">笔记凭据不会导出</p></div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.preferences-heading-identity > p { display: flex; align-items: center; gap: 15px; margin: 0 0 6px; color: var(--muted); font-size: 19px; line-height: 1.65; }
/* 工作台为单列设置表单，不能继承旧设置页的两列字段和四列用量网格。 */
.preferences-workspace .preference-fields { grid-template-columns: minmax(0,1fr); gap: 36px; }
.preferences-workspace .preference-usage-grid { display: block; }
.preferences-workspace .preference-usage-grid dt { font-size: 18px; }
.preferences-workspace .preference-timezone { grid-template-columns: minmax(0,1fr); }
.preferences-workspace .preference-timezone > .n-button { justify-self: end; }
.preferences-workspace .preference-control { grid-template-columns: 117px minmax(0,1fr); }
.preferences-workspace .settings-segment { min-height: 53px; }
@media(min-width:1351px) {
  .preferences-workspace > .page-heading { padding: 0; margin-bottom: 22px; }
  .preferences-workspace > .page-heading h1 { font-size: 54px; }
  .preferences-workspace .preferences-content { grid-template-columns: 230px minmax(0,1fr) 352px; gap: 0; }
  .preferences-workspace .preference-appearance { margin: 0 12px 0 14px; padding: 23px 28px 26px; }
  .preferences-workspace .settings-directory { margin-top: 2px; padding: 13px 9px; }
  .preferences-workspace .settings-directory a { min-height: 58px; padding: 0 18px; gap: 20px; font-weight: 400; }
  .preferences-workspace .settings-directory a:first-child { min-height: 64px; margin-bottom: 7px; }
  .preferences-workspace .settings-segment > button.selected { background: radial-gradient(ellipse at 38% 100%, #713c2666, transparent 70%), radial-gradient(ellipse at 0 0, #f8c89b66, transparent 75%), linear-gradient(115deg, #c18a58, #87512f 70%, #b68253); }
}
.preferences-workspace { max-width: none; }.preferences-content { display: grid; grid-template-columns: 230px minmax(0,1fr) 350px; gap: 14px; align-items: start; }.settings-directory { display: grid; align-content: start; padding: 14px 10px; min-height: 667px; border: 1px solid var(--line); border-radius: 25px; background: linear-gradient(120deg,#76513520,transparent),var(--surface); }.settings-directory a { display: flex; gap: 17px; align-items: center; min-height: 60px; font-size: 20px; padding: 14px 18px; border: 1px solid transparent; border-radius: 20px; }.settings-directory a.selected { border-color: #9b6d455e; background: linear-gradient(120deg,#9e6c415f,#51372266); color: #fff3e1; }.preference-section { padding: 26px 27px; border: 1px solid var(--line); border-radius: 25px; background: linear-gradient(115deg,#ffffff03,transparent),var(--surface); min-width: 0; display: grid; gap: 25px; }.preference-section > header { display: flex; justify-content: space-between; align-items: center; gap: 10px; }.preference-section h2 { display: flex; align-items: center; gap: 16px; font-size: 28px; }.preference-section h2 svg { color: var(--brand); }.preference-section h3 { font-size: 21px; }.preference-fields { display: grid; gap: 31px; }.preference-control { display: grid; grid-template-columns: 116px minmax(0,1fr); align-items: center; gap: 16px; font-size: 20px; }.settings-segment { display: flex; padding: 0; border: 1px solid var(--line); border-radius: 24px; background: #ffffff03; min-height: 52px; }.settings-segment > button { display: flex; flex: 1; gap: 9px; align-items: center; justify-content: center; padding: 8px; border-radius: 24px; border: 1px solid transparent; font-size: 18px; white-space: nowrap; }.settings-segment > button.selected { color: #fff4e4; border-color: #e9b382; background: linear-gradient(120deg,#c29466,#8a5838); box-shadow: inset 0 0 12px #f3c59328; }.settings-segment > button:disabled { opacity: .55; cursor: not-allowed; }.settings-exact-values { font-size: 15px; }.settings-exact-values label { display: grid; gap: 8px; margin-top: 12px; }.preference-timezone { display: grid; gap: 13px; padding-top: 20px; border-top: 1px solid var(--line); }.preference-timezone > label { display: grid; grid-template-columns: 116px minmax(0,1fr); gap: 16px; align-items: center; font-size: 20px; }.preference-recovery,.preference-reading-sample { display: grid; gap: 12px; }.preference-reading-sample { padding: 18px; background: #ffffff03; border: 1px solid var(--line); border-radius: 14px; }.preference-usage-grid { margin: 0; }.preference-usage-grid > div { display: flex; justify-content: space-between; gap: 14px; align-items: center; min-height: 49px; border-bottom: 1px solid #ffffff06; font-size: 18px; }.preference-usage-grid dt { color: var(--muted); }.preference-usage-grid dd { margin: 0; font-size: 22px; }.preference-storage { display: grid; gap: 12px; }.settings-export { padding-top: 20px; border-top: 1px solid var(--line); display: grid; gap: 14px; }.settings-export a { display: flex; align-items: center; justify-content: center; gap: 13px; min-height: 52px; border: 1px solid #82776a; border-radius: 21px; font-size: 19px; background: #ffffff04; }.settings-export a.settings-export-primary { border-color: #d4a77a; background: linear-gradient(115deg,#c59568,#754f35); color: #fff5e6; }
@media(max-width:1350px) { .preferences-content { grid-template-columns: 200px minmax(0,1fr); }.preference-usage { grid-column: 2; }.preference-control { grid-template-columns: 100px minmax(0,1fr); }.settings-directory a { font-size: 18px; padding: 12px; } }@media(max-width:800px) { .preferences-content { grid-template-columns: 1fr; }.preference-usage { grid-column: 1; }.settings-directory { min-height: 0; grid-template-columns: repeat(2,minmax(0,1fr)); }.preference-section { padding: 22px; } }@media(max-width:600px) { .preference-control,.preference-timezone > label { grid-template-columns: 1fr; gap: 10px; }.settings-segment > button { font-size: 15px; }.settings-directory a { font-size: 16px; } }
</style>
