<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { NButton } from 'naive-ui'
import type { PrivacyResult } from '../api/dataPrivacy'
import { privacyActions } from '../api/dataPrivacy'
import { errorText } from '../api/client'
import { queryClient } from '../api/queryClient'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useWorkspaceStore } from '../state/workspace'
const DataPrivacyPanel = defineAsyncComponent(() => import('../components/DataPrivacyPanel.vue'))
const DataArchivePanel = defineAsyncComponent(() => import('../components/DataArchivePanel.vue'))
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const element = ref<HTMLElement>(), active = ref(true), elementVisible = ref(false)
const documentVisible = ref(document.visibilityState === 'visible'), selected = ref<'privacy' | 'archive'>('privacy')
const refreshing = ref(false), message = ref(''), refreshError = ref('')
const shellReadable = computed(() => active.value && store.sessionActive && documentVisible.value && elementVisible.value && !refreshing.value)
const readable = computed(() => shellReadable.value && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const showPrivacy = computed(() => shellReadable.value && selected.value === 'privacy')
const showArchive = computed(() => shellReadable.value && selected.value === 'archive')
let alive = true, generation = 0, observer: MutationObserver | undefined
watch([() => store.sessionEpoch, () => store.sessionActive], () => {
  generation++; message.value = ''; refreshError.value = ''; refreshing.value = false
}, { flush: 'sync' })
function visibilityChanged() { documentVisible.value = document.visibilityState === 'visible' }
function elementVisibilityChanged() {
  const node = element.value
  elementVisible.value = Boolean(node && node.getClientRects().length && !node.closest('[hidden], [inert], [aria-hidden="true"]'))
  for (let ancestor: HTMLElement | null = node ?? null; ancestor && elementVisible.value; ancestor = ancestor.parentElement) {
    const style = getComputedStyle(ancestor)
    if (style.visibility === 'hidden' || style.visibility === 'collapse') elementVisible.value = false
  }
}
onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged); elementVisibilityChanged()
  observer = new MutationObserver(elementVisibilityChanged)
  for (let node: HTMLElement | null = element.value ?? null; node; node = node.parentElement) {
    observer.observe(node, { attributes: true, attributeFilter: ['style', 'class', 'hidden', 'inert', 'aria-hidden'] })
  }
})
onActivated(() => { active.value = true; visibilityChanged(); elementVisibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; observer?.disconnect(); document.removeEventListener('visibilitychange', visibilityChanged) })
function selectPanel(value: 'privacy' | 'archive') {
  if (!shellReadable.value || selected.value === value
    || !store.canLeave(['data-privacy', 'data-archive-command'])) return
  selected.value = value
}
async function changed(result: PrivacyResult | null) {
  const ticket = ++generation, session = store.sessionEpoch
  message.value = result ? `${privacyActions[result.action]}已记录：${result.objects.length}个对象，独立账本序号${result.ledgerSequence}。正在刷新访问条件和相关清单。`
    : '独立账本已建立，正在重新核对访问条件和导出资格。'
  refreshError.value = ''; refreshing.value = true
  // 隐私变更后先失活旧资料响应，再清空查询快照；不能短暂展示已清除正文或旧导出资格。
  access.invalidate()
  try {
    await nextTick()
    await queryClient.cancelQueries()
    await queryClient.resetQueries()
    if (!alive || ticket !== generation || session !== store.sessionEpoch || !store.sessionActive) return
    const accepted = await access.refresh()
    if (!alive || ticket !== generation || session !== store.sessionEpoch) return
    if (!accepted) refreshError.value = '操作已记录，但访问条件尚未重新核对成功，请重新检查连接与账本状态。'
    else message.value = result ? `${privacyActions[result.action]}已记录：${result.objects.length}个对象，独立账本序号${result.ledgerSequence}。相关资料已要求重新读取。`
      : '独立账本已建立。回收站操作与完整导出仍按各自当前资格显式执行。'
  } catch (error) {
    if (alive && ticket === generation && session === store.sessionEpoch) refreshError.value = `操作已记录，清单刷新未完成：${errorText(error)}`
  } finally { if (alive && ticket === generation && session === store.sessionEpoch) refreshing.value = false }
}
</script>

<template>
  <section ref="element" class="data-management" aria-label="个人数据管理工作台">
    <header class="page-heading"><div><p class="section-label">个人 / 数据管理</p><h1>管理自己的数据与保留边界</h1><p class="muted">回收站、彻底清除、独立删除账本和完整导出，在同一个工作台明确处理。</p></div></header>
    <p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="refreshError" class="notice error" role="alert">{{ refreshError }}</p>
    <p v-if="!readable" class="notice" role="status">{{ refreshing ? '正在刷新隐私状态与资料资格，敏感详情暂时遮挡。' : '当前页面、登录或训练资料权限不允许展示数据管理详情。恢复条件后将重新读取敏感清单。' }} <NButton v-if="access.error && store.sessionActive" text :disabled="refreshing || !documentVisible" @click="access.refresh()">重新核对资料访问</NButton></p>
    <nav class="data-tabs" role="tablist" aria-label="数据管理分区"><NButton id="privacy-tab" role="tab" aria-controls="privacy-panel" :aria-selected="selected === 'privacy'" :type="selected === 'privacy' ? 'primary' : 'default'" :disabled="!shellReadable" @click="selectPanel('privacy')">回收站与隐私清除</NButton><NButton id="archive-tab" role="tab" aria-controls="archive-panel" :aria-selected="selected === 'archive'" :type="selected === 'archive' ? 'primary' : 'default'" :disabled="!shellReadable" @click="selectPanel('archive')">完整导出与恢复说明</NButton></nav>
    <div id="privacy-panel" v-show="showPrivacy" role="tabpanel" aria-labelledby="privacy-tab" :aria-hidden="!showPrivacy" :inert="!showPrivacy"><DataPrivacyPanel :enabled="readable && selected === 'privacy'" :recovery-enabled="showPrivacy" @changed="changed" /></div>
    <div id="archive-panel" v-show="showArchive" role="tabpanel" aria-labelledby="archive-tab" :aria-hidden="!showArchive" :inert="!showArchive"><DataArchivePanel :enabled="readable && selected === 'archive'" :recovery-enabled="showArchive" /></div>
  </section>
</template>

<style scoped>
.data-management { max-width: 1280px; margin: 0 auto; }
.data-tabs { display: flex; flex-wrap: wrap; gap: 12px; margin: 20px 0 24px; }
#archive-panel { padding: 22px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
@media (max-width: 700px) { #archive-panel { padding: 15px; } }
</style>
