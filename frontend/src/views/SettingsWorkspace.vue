<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NInput, NInputNumber } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText } from '../api/client'
import { defaultPreferences, formatPreferenceBytes, preferenceMotions, preferenceThemes,
  readPreferenceUsage, samePreferences, savePreferencesProblem } from '../api/preferences'
import type { PreferenceValues, Preferences, PreferenceUsage } from '../api/preferences'
import { usePreferencesStore } from '../state/preferences'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import RIcon from '../replica/RIcon.vue'

type Draft = Omit<PreferenceValues, 'fontSize' | 'contentWidth'> & { fontSize: number | null; contentWidth: number | null }
const workspace = useWorkspaceStore(), access = useTrainingAccessStore(), preferences = usePreferencesStore()
const draft = reactive<Draft>(formOf(defaultPreferences))
const baseline = shallowRef<Preferences>(), serverCopy = shallowRef<Preferences>()
const usage = shallowRef<PreferenceUsage>(), usageLoading = ref(false)
const usageError = ref(''), message = ref(''), needsReview = ref(false)
const replaceConfirmed = ref(false), visible = ref(document.visibilityState === 'visible')
let alive = true, generation = 0, usageSequence = 0
const readable = computed(() => workspace.sessionActive && visible.value && !access.checking && !access.challenge && !access.restricted
  && !workspace.changes.get('training-access-command')?.locked)
const reading = computed(() => preferences.loading), readReady = computed(() => preferences.loaded)
const settingsError = computed(() => preferences.error)
const dirty = computed(() => Boolean(baseline.value && signature(draft) !== signature(formOf(baseline.value))))
const blocked = computed(() => !readable.value || reading.value || preferences.saving || preferences.requestPending)
const editable = computed(() => Boolean(baseline.value) && readable.value)
const problem = computed(() => {
  const issue = baseline.value ? savePreferencesProblem({ expectedRevision: baseline.value.revision, ...draft }) : undefined
  return issue ?? (baseline.value && !previewZone(draft.timeZone) ? '请填写有效的时区名称，例如 Asia/Shanghai 或 UTC。' : undefined)
})
const serverMatches = computed(() => Boolean(serverCopy.value && preferences.server && samePreferences(serverCopy.value, preferences.server)))
const autoStatus = computed(() => {
  if (!baseline.value) return reading.value ? '正在读取' : '等待读取'
  if (preferences.syncError) return '同步失败'
  if (settingsError.value) return '读取失败'
  if (preferences.saving) return '自动保存中'
  if (problem.value) return '等待有效输入'
  if (dirty.value || preferences.hasPending) return '等待自动保存'
  return baseline.value.updatedAt ? '已自动保存' : '默认设置'
})
const saveSummary = computed(() => {
  if (!baseline.value) return '正在读取个人设置。'
  const saved = baseline.value.updatedAt ? `最近自动保存：${displayTime(baseline.value.updatedAt)}。` : '尚未保存，当前为默认设置。'
  return `${saved}修改后会自动同步，无需手动提交。`
})
const usageRows: { key: keyof Pick<PreferenceUsage, 'notes' | 'concepts' | 'knowledgeVersions' | 'boards' | 'preparationItems'
  | 'questions' | 'trainingSessions' | 'submittedAnswers' | 'memoryCards' | 'attachments' | 'aiTasks'>; label: string }[] = [
  { key: 'notes', label: '笔记' }, { key: 'concepts', label: '知识点' }, { key: 'knowledgeVersions', label: '知识历史版本' },
  { key: 'boards', label: '脑图与白板' }, { key: 'preparationItems', label: '准备材料' }, { key: 'questions', label: '题目' },
  { key: 'trainingSessions', label: '训练会话' }, { key: 'submittedAnswers', label: '正式回答' },
  { key: 'memoryCards', label: '回忆卡片' }, { key: 'attachments', label: '附件记录' }, { key: 'aiTasks', label: 'AI 任务' },
]

watch([readable, () => workspace.sessionEpoch], () => {
  generation++; usageSequence++; usageLoading.value = false
  serverCopy.value = undefined; replaceConfirmed.value = false
  if (baseline.value && dirty.value) needsReview.value = true
  if (readable.value) { void preferences.refresh(); void loadUsage() }
}, { immediate: true, flush: 'sync' })
watch(() => preferences.server, value => {
  if (!value) {
    baseline.value = undefined; serverCopy.value = undefined; needsReview.value = false; replaceConfirmed.value = false
    Object.assign(draft, formOf(preferences.current))
    return
  }
  const wasDirty = dirty.value, first = !baseline.value
  baseline.value = { ...value }
  // 首次读取优先展示标签页恢复的本地最终值；后续服务器回执不能覆盖期间的新输入。
  if (first) Object.assign(draft, formOf(preferences.current))
  else if (!wasDirty && !needsReview.value) Object.assign(draft, formOf(value))
  if (!problem.value && !dirty.value) needsReview.value = false
}, { immediate: true, flush: 'sync' })
watch(draft, () => {
  replaceConfirmed.value = false; message.value = ''
  if (baseline.value && !dirty.value) needsReview.value = false
}, { deep: true, flush: 'sync' })
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onUnmounted(() => {
  alive = false; generation++; usageSequence++
  document.removeEventListener('visibilitychange', visibilityChanged)
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
    throw new ApiError(0, 'PREFERENCES_CONTEXT_CHANGED', '会话或训练访问条件已变化。')
}
function previewZone(value: string): boolean {
  try { new Intl.DateTimeFormat('zh-CN', { timeZone: value }); return true }
  catch { return false }
}
function preview(patch: Partial<PreferenceValues> = {}) {
  if (!editable.value || !baseline.value) return
  const value: PreferenceValues = { theme: preferences.current.theme, fontSize: preferences.current.fontSize,
    contentWidth: preferences.current.contentWidth, motion: preferences.current.motion,
    timeZone: preferences.current.timeZone, saveRecoveryNotice: preferences.current.saveRecoveryNotice, ...patch }
  if (!savePreferencesProblem({ expectedRevision: baseline.value.revision, ...value })) preferences.update(value)
}
function updateTheme(value: unknown) { if (editable.value && (value === 'SYSTEM' || value === 'LIGHT' || value === 'DARK')) { draft.theme = value; preview({ theme: value }) } }
function updateMotion(value: unknown) { if (editable.value && (value === 'SYSTEM' || value === 'REDUCED' || value === 'FULL')) { draft.motion = value; preview({ motion: value }) } }
function updateNumber(field: 'fontSize' | 'contentWidth', value: number | null) {
  if (!editable.value) return
  draft[field] = value
  if (value !== null) preview(field === 'fontSize' ? { fontSize: value } : { contentWidth: value })
}
function updateZone(value: string) { if (editable.value) { draft.timeZone = value; if (previewZone(value)) preview({ timeZone: value }) } }
function chooseBrowserZone() {
  if (!editable.value) return
  try { draft.timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone; preview({ timeZone: draft.timeZone }) }
  catch { message.value = '当前浏览器无法提供时区，请手动填写。' }
}
async function loadSettings(compare: boolean) {
  if (!readable.value || reading.value) return
  const epoch = workspace.sessionEpoch, stamp = generation, training = currentTrainingAccessEpoch()
  const value = await preferences.refresh()
  if (!alive || !readable.value || epoch !== workspace.sessionEpoch || stamp !== generation
    || training !== currentTrainingAccessEpoch() || !value) return
  if (compare) {
    serverCopy.value = { ...value }
    needsReview.value = dirty.value && signature(draft) !== signature(formOf(value))
  }
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
function retry() { if (readable.value) preferences.retry() }
function adoptServer(keepLocal: boolean) {
  const value = serverCopy.value
  if (blocked.value || !readReady.value || !value || !preferences.server || !samePreferences(value, preferences.server)
    || !keepLocal && dirty.value && !replaceConfirmed.value) return
  if (keepLocal) preview()
  if (!preferences.adopt(value, keepLocal)) return
  if (!keepLocal) Object.assign(draft, formOf(value))
  serverCopy.value = undefined; needsReview.value = false
  message.value = keepLocal ? '本地输入已保留，将按最新服务器修订自动保存。' : '已采用服务器设置。'
}
function displayTime(value: string) { return preferences.formatDate(value) }
</script>

<template>
  <section class="preferences-workspace" aria-labelledby="preferences-title">
    <header class="page-heading">
      <div class="preferences-heading-identity"><p>设置 <span>/</span> 外观与阅读</p><h1 id="preferences-title">设置</h1></div>
      <span v-if="baseline && readable" class="status-pill" role="status" aria-live="polite">{{ autoStatus }}</span>
    </header>
    <p v-if="!readable" class="notice" role="status">设置暂不可读取。恢复会话或训练访问条件后继续；当前输入与未决同步会保留。</p>
    <nav class="settings-navigation" aria-label="设置分类">
      <RouterLink to="/settings" :class="{ selected: $route.path === '/settings' }" :aria-current="$route.path === '/settings' ? 'page' : undefined">外观与阅读</RouterLink>
      <RouterLink to="/settings/ai" :class="{ selected: $route.path === '/settings/ai' }" :aria-current="$route.path === '/settings/ai' ? 'page' : undefined">AI 模型</RouterLink>
      <RouterLink to="/settings/data" :class="{ selected: $route.path === '/settings/data' }" :aria-current="$route.path === '/settings/data' ? 'page' : undefined">数据与隐私</RouterLink>
      <RouterLink to="/settings/security" :class="{ selected: $route.path === '/settings/security' }" :aria-current="$route.path === '/settings/security' ? 'page' : undefined">安全与授权</RouterLink>
    </nav>
    <div v-show="readable" :inert="!readable || undefined" class="preferences-content">
      <section class="preference-section preference-appearance" aria-labelledby="appearance-title">
        <header><div><h2 id="appearance-title"><RIcon name="sun" :size="30" />外观</h2></div></header>
        <p v-if="!baseline && reading" class="notice" role="status">正在读取个人设置…</p>
        <p v-if="settingsError" class="notice error" role="alert">{{ settingsError }}</p>
        <p v-if="preferences.syncError" class="notice error" role="alert">同步失败：{{ errorText(preferences.syncError) }} <NButton text :disabled="!readable || preferences.saving" :loading="preferences.saving" @click="retry">重试</NButton></p>
        <p v-if="message" class="notice" role="status">{{ message }}</p>
        <p v-if="needsReview" class="notice" role="status">设置修订或访问条件已变化，输入已保留。系统会按最新修订继续自动同步；也可核对服务器设置后选择保留输入或采用服务器值。</p>
        <template v-if="baseline">
          <div class="preference-fields">
            <div class="preference-control"><span>主题外观</span><div class="settings-segment" role="group" aria-label="工作台主题"><button v-for="option in [{ value: 'SYSTEM', label: '跟随系统', icon: 'monitor' }, { value: 'DARK', label: '深色', icon: 'moon' }, { value: 'LIGHT', label: '浅色', icon: 'sun' }]" :key="option.value" :class="{ selected: draft.theme === option.value }" :aria-pressed="draft.theme === option.value" :disabled="!editable" @click="updateTheme(option.value)"><RIcon :name="option.icon" :size="23" />{{ option.label }}</button></div></div>
            <div class="preference-control"><span>正文阅读宽度</span><div><div class="settings-segment" role="group" aria-label="正文阅读宽度"><button v-for="option in [{ value: 960, label: '舒适' }, { value: 1280, label: '宽' }, { value: 1600, label: '宽屏' }]" :key="option.value" :class="{ selected: draft.contentWidth === option.value }" :aria-pressed="draft.contentWidth === option.value" :disabled="!editable" @click="updateNumber('contentWidth', option.value)">{{ option.label }}</button></div><p class="field-help">控制正文的阅读宽度。列表、日历和工作区始终使用可用空间。</p></div></div>
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
          <p v-if="problem" class="field-help" role="status">{{ problem }}</p>
          <div class="row-actions"><span class="field-help">{{ saveSummary }}</span></div>
        </template>
        <details class="preference-advanced">
          <summary>高级：服务器设置与修订</summary>
          <div class="preference-advanced-content">
            <p v-if="baseline" class="field-help">当前设置修订：{{ baseline.revision }}</p>
            <div class="row-actions"><NButton text :disabled="!readable || reading" :loading="reading" @click="loadSettings(true)">核对服务器设置</NButton></div>
            <section v-if="serverCopy" class="preference-server-copy" aria-labelledby="preference-server-title">
              <h3 id="preference-server-title">服务器设置 · 修订 {{ serverCopy.revision }}</h3>
              <dl><div><dt>主题与动态效果</dt><dd>{{ preferenceThemes.find(item => item.value === serverCopy?.theme)?.label }} · {{ preferenceMotions.find(item => item.value === serverCopy?.motion)?.label }}</dd></div><div><dt>字号与宽度</dt><dd>{{ serverCopy.fontSize }} px · {{ serverCopy.contentWidth }} px</dd></div><div><dt>显示时区</dt><dd>{{ serverCopy.timeZone }}</dd></div><div><dt>待保存提醒</dt><dd>{{ serverCopy.saveRecoveryNotice ? '开启' : '关闭' }}</dd></div></dl>
              <NCheckbox v-if="dirty" v-model:checked="replaceConfirmed" :disabled="blocked">允许用服务器设置替换当前本地输入</NCheckbox>
              <div class="row-actions"><NButton :disabled="blocked || !readReady || !serverMatches" @click="adoptServer(true)">保留输入，采用服务器修订</NButton><NButton :disabled="blocked || !readReady || !serverMatches || (dirty && !replaceConfirmed)" @click="adoptServer(false)">采用服务器设置</NButton></div>
            </section>
          </div>
        </details>
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
.preferences-heading-identity > p { display: none; }
.preferences-workspace { max-width: none; }
.settings-navigation { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-bottom: 20px; }
.settings-navigation a { display: flex; align-items: center; justify-content: center; min-height: 40px; padding: 8px 16px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); font-size: 13px; }
.settings-navigation a:hover { background: var(--brand-soft); color: var(--brand-strong); }
.settings-navigation a.selected { border-color: var(--brand); color: var(--brand-strong); background: var(--brand-soft); }
.preferences-content { display: grid; grid-template-columns: minmax(0, 1fr) minmax(280px, 300px); gap: 20px; align-items: start; }
.preference-section { padding: 22px; border-radius: 10px; background: var(--surface); min-width: 0; display: grid; gap: 22px; }
.preference-section h2 { display: flex; align-items: center; gap: 9px; font-size: 18px; }
.preference-section h2 svg { width: 20px; color: var(--brand); }
.preference-section h3 { font-size: 15px; }
.preferences-workspace .preference-fields { grid-template-columns: minmax(0,1fr); gap: 22px; }
.preference-control { display: grid; grid-template-columns: 85px minmax(0,1fr); align-items: center; gap: 12px; font-size: 13px; }
.settings-segment { display: flex; padding: 3px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); }
.settings-segment > button { display: flex; flex: 1; gap: 6px; align-items: center; justify-content: center; min-height: 30px; padding: 4px 8px; border-radius: 4px; font-size: 12px; white-space: nowrap; }
.settings-segment > button svg { width: 15px; height: 15px; }
.settings-segment > button.selected { color: var(--brand-strong); background: var(--brand-soft); }
.settings-exact-values { font-size: 12px; }
.settings-exact-values label { display: grid; gap: 7px; margin-top: 10px; }
.preference-timezone { display: grid; grid-template-columns: minmax(0,1fr); gap: 12px; padding-top: 18px; border-top: 1px solid var(--line); }
.preference-timezone > label { display: grid; gap: 8px; font-size: 13px; }
.preference-recovery,.preference-reading-sample { display: grid; gap: 10px; }
.preference-reading-sample { padding: 16px 0; }
.preference-usage-grid { display: block; margin: 0; }
.preference-usage-grid > div { display: flex; justify-content: space-between; align-items: center; gap: 12px; min-height: 36px; border-bottom: 1px solid var(--line); }
.preference-usage-grid dt { font-size: 12px; color: var(--muted); }
.preference-usage-grid dd { margin: 0; font-size: 15px; }
.settings-export { padding-top: 16px; border-top: 1px solid var(--line); display: grid; gap: 10px; }
.settings-export a { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 34px; border: 1px solid var(--line); border-radius: 6px; font-size: 13px; }
.settings-export a.settings-export-primary { color: var(--brand-strong); background: var(--brand-soft); }
.preference-advanced { border-top: 1px solid var(--line); padding-top: 18px; }
.preference-advanced > summary { cursor: pointer; font-size: 13px; }
.preference-advanced-content { display: grid; gap: 16px; padding-top: 16px; }
.preference-server-copy { display: grid; gap: 16px; padding: 16px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); }
.preference-server-copy dl { display: grid; gap: 10px; margin: 0; }
.preference-server-copy dl > div { display: grid; gap: 4px; }
.preference-server-copy dt { font-size: 12px; color: var(--muted); }
.preference-server-copy dd { margin: 0; font-size: 13px; }
.preference-section :is([id],label) { scroll-margin-top: 124px; }
@media(max-width:900px) {
  .preferences-content { grid-template-columns: minmax(0,1fr); }
}
@media(max-width:750px) {
  .settings-navigation { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .settings-navigation a { padding-inline: 8px; }
  .preference-control { grid-template-columns: minmax(0,1fr); gap: 8px; }
  .preference-section { padding: 18px; }
}
</style>
