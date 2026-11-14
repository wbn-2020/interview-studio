<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import RIcon from './RIcon.vue'
import RBrand from './RBrand.vue'
import PreparationScenes from './PreparationScenes.vue'
import KnowledgeScenes from './KnowledgeScenes.vue'
import TrainingScenes from './TrainingScenes.vue'
import ReviewScenes from './ReviewScenes.vue'
import UtilityScenes from './UtilityScenes.vue'
import SpecialScenes from './SpecialScenes.vue'
import { scenes, sidebarWidths } from './scenes'

function readScene() {
  const raw = new URLSearchParams(window.location.search).get('scene')
  return raw && /^(0[1-9]|[12]\d|30|[1-9])$/.test(raw) ? Number(raw) : 1
}
const scene = ref(readScene())
const page = computed(() => scenes[scene.value - 1]!)
const sidebar = computed(() => sidebarWidths[scene.value - 1]!)
const toast = ref('')
const scale = ref(1)
let toastTimer: ReturnType<typeof setTimeout> | undefined
const group = computed(() => {
  const n = scene.value
  return [1, 2, 9, 25, 29, 30].includes(n) ? SpecialScenes : n <= 8 ? PreparationScenes : n <= 13 ? KnowledgeScenes
    : n <= 20 ? TrainingScenes : n <= 24 ? ReviewScenes : UtilityScenes
})
const active = computed(() => {
  const n = scene.value
  return n === 2 || n === 25 ? 2 : n >= 3 && n <= 8 ? 3 : [9, 10, 11, 12, 13, 30].includes(n) ? 10
    : n >= 14 && n <= 20 ? 17 : n >= 21 && n <= 23 ? 21 : n === 24 ? 24 : n
})
const nav = computed(() => {
  if (scene.value === 9) return [
    { id: 2, label: '今日', icon: 'house' },
    { id: 3, label: '准备', icon: 'layers' },
    { id: 10, label: '知识', icon: 'knowledge-network' },
    { id: 17, label: '训练', icon: 'play' },
    { id: 21, label: '复盘', icon: 'chart-no-axes-column' },
    { id: 24, label: '成长', icon: 'trending-up' },
  ]
  if (scene.value === 11) return [
    { id: 2, label: '今日', icon: 'house' }, { id: 3, label: '准备', icon: 'book-open' },
    { id: 10, label: '知识', icon: 'layers' }, { id: 17, label: '训练', icon: 'play' },
    { id: 21, label: '复盘', icon: 'chart-no-axes-column' }, { id: 24, label: '成长', icon: 'sprout' },
  ]
  const ordinary = [
    { id: 2, label: '今日', icon: [2, 10, 15].includes(scene.value) ? 'calendar-days' : 'house' },
    { id: 3, label: '准备', icon: [16, 17, 20, 23].includes(scene.value) ? 'clipboard' : scene.value === 14 ? 'briefcase' : scene.value === 18 ? 'square-check-rounded' : scene.value === 5 ? 'file-text' : scene.value === 13 ? 'clipboard' : [3, 7].includes(scene.value) ? 'target' : [6, 11, 19].includes(scene.value) ? 'book-open' : 'play' },
    { id: 10, label: '知识', icon: scene.value === 19 ? 'box' : 'book-open' },
    { id: 17, label: '训练', icon: scene.value === 20 ? 'zap' : scene.value === 16 ? 'layers' : scene.value === 12 ? 'chart-no-axes-column' : scene.value === 18 ? 'circle-play' : [17, 23].includes(scene.value) ? 'play' : 'dumbbell' },
    { id: 21, label: '复盘', icon: [5, 13, 16, 18, 20, 30].includes(scene.value) ? 'chart-no-axes-column' : 'refresh-cw' },
    { id: 24, label: '成长', icon: [5, 30].includes(scene.value) ? 'leaf' : [3, 7, 12, 13, 16, 18, 26, 27].includes(scene.value) ? 'sprout' : 'chart-no-axes-combined' },
  ]
  if (scene.value === 16) [ordinary[2], ordinary[3]] = [ordinary[3]!, ordinary[2]!]
  if (scene.value === 26) ordinary.splice(4, 0, { id: 26, label: '最近访问', icon: 'clock' })
  if (scene.value === 27) ordinary.splice(4, 0, { id: 27, label: 'AI 任务', icon: 'sparkles' })
  return ordinary
})
const secondary = computed(() => [
  { id: 14, label: '题库', icon: scene.value === 15 ? 'file-text' : scene.value === 11 ? 'layout-grid' : [2, 6, 10, 12, 14, 16, 17, 18, 19, 20, 21, 22, 23, 28].includes(scene.value) ? 'database' : 'layers' },
  { id: 16, label: '回忆卡', icon: scene.value === 18 ? 'memory-cards' : 'credit-card' },
  ...(scene.value === 26 ? [] : [{ id: 26, label: '最近访问', icon: 'clock' }]),
  { id: 27, label: 'AI 任务', icon: scene.value === 11 ? 'zap' : scene.value === 27 ? 'settings' : 'sparkles' },
])
// 18的参考图标限定在复现入口，避免改变正式业务共用图标的发布边界。
const followNavPaths: Record<string, string[]> = {
  'circle-play': ['M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0', 'm10 7 6 5-6 5Z'],
  'square-check-rounded': ['M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3Z', 'm7 12 3 3 7-8'],
  'memory-cards': ['M7 3h12a2 2 0 0 1 2 2v12', 'M5 6h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z', 'M7 11h.01', 'M14 15h.01', 'M8 17h.01'],
}
const headerBox = computed(() => {
  const n = scene.value
  if (n === 2) return { left: 14, width: 718 }
  if (n === 9) return { left: 498, width: 560 }
  const lefts: Record<number, number> = { 3: 437, 4: 431, 5: 446, 6: 420, 7: 396, 8: 593, 10: 430, 11: 566, 12: 362, 13: 427, 14: 529, 15: 364, 16: 385, 18: 515, 19: 456, 20: 435, 21: 545, 22: 414, 23: 352, 24: 507, 25: 398, 26: 438, 27: 497, 28: 300, 30: 364 }
  const widths: Record<number, number> = { 3: 500, 4: 468, 5: 378, 6: 463, 7: 454, 8: 328, 10: 492, 11: 356, 12: 500, 13: 475, 14: 460, 15: 535, 16: 486, 18: 445, 19: 390, 20: 444, 21: 438, 22: 522, 23: 503, 24: 399, 25: 496, 26: 441, 27: 450, 28: 456, 30: 483 }
  return { left: lefts[n] ?? 400, width: widths[n] ?? 450 }
})
const searchHint = computed(() => {
  const hints: Record<number, string> = { 2: '搜索题目、知识点或任务...', 4: '搜索项目、事实、表达素材...', 5: '', 6: '搜索资料...', 9: '搜索知识点、或 ⌘K', 10: '搜索笔记、概念、标签...', 11: '搜索知识、笔记、题目...', 12: '搜索知识、练习或问题...', 13: '⌘K 搜索知识、题目或内容...', 14: '⌘K', 15: '搜索题目、题单或知识点...', 18: '⌘K 搜索知识、题目或功能...', 20: '搜索题目、知识点...', 21: '⌘K 搜索知识、题目或内容...', 22: '⌘K 搜索题目、知识点...', 23: '搜索知识、题目或记录...', 25: '搜索知识、笔记、题目...', 26: '搜索知识、训练、问题...', 27: '搜索...', 28: '搜索知识、概念或问题...', 30: '搜索知识...' }
  return hints[scene.value] ?? '⌘K 搜索...'
})
function resize() { scale.value = Math.min(1, window.innerWidth / 1536) }
function navigate(id: number) {
  if (!Number.isInteger(id) || id < 1 || id > 30) return
  if (id === 25 && scene.value === 25) {
    window.dispatchEvent(new Event('replica:open-command'))
    return
  }
  // URL 是唯一初始场景身份；切换后重建页面，避免旧弹层和选择泄漏到下一页。
  scene.value = id
  const url = new URL(window.location.href)
  url.search = `?scene=${String(id).padStart(2, '0')}`
  history.pushState({}, '', url)
  document.title = `${scenes[id - 1]!.title} · 问阶`
  toast.value = ''
}
function notify(text: string) {
  toast.value = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 3000)
}
function popstate() {
  scene.value = readScene()
  document.title = `${scenes[scene.value - 1]!.title} · 问阶`
}
function keydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); navigate(25) }
}
onMounted(() => { resize(); document.title = `${page.value.title} · 问阶`; window.addEventListener('resize', resize); window.addEventListener('popstate', popstate); window.addEventListener('keydown', keydown) })
onUnmounted(() => { clearTimeout(toastTimer); window.removeEventListener('resize', resize); window.removeEventListener('popstate', popstate); window.removeEventListener('keydown', keydown) })
</script>
<template>
  <div class="r-stage" :style="{ width: `${1536 * scale}px`, height: `${1024 * scale}px` }">
    <div class="r-canvas" :class="`r-scene-${String(scene).padStart(2, '0')}`" :style="{ '--sidebar': `${sidebar}px`, transform: `scale(${scale})` }" :data-scene="String(scene).padStart(2, '0')">
      <template v-if="![1, 29].includes(scene)">
        <aside class="r-sidebar" :class="{ 'r-sidebar-mini': scene === 9 }">
          <a href="replica.html?scene=02" aria-label="问阶今日" @click.prevent="navigate(2)">
            <div v-if="scene === 18" class="r-brand">
              <svg class="r-emblem" viewBox="0 0 100 100" fill="none" aria-hidden="true"><path d="M50 2 61 37 94 50 61 63 50 98 39 63 6 50 39 37Z M50 29 44 44 29 50 44 56 50 72 56 56 71 50 56 44Z" fill="currentColor" fill-rule="evenodd" /></svg>
              <span class="r-brand-word">问阶<small>Interview Studio</small></span>
            </div>
            <RBrand v-else :variant="scene" />
          </a>
          <div v-if="scene === 18" class="r-brand-rule" aria-hidden="true" />
          <nav aria-label="主导航">
            <a v-for="item in nav" :key="item.id" :href="`replica.html?scene=${String(item.id).padStart(2, '0')}`" class="r-nav-item" :class="{ 'r-selected': item.id === active }" @click.prevent="navigate(item.id)">
              <svg v-if="scene === 18 && followNavPaths[item.icon]" class="r-icon" width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path v-for="(d, index) in followNavPaths[item.icon]" :key="index" :d="d" /></svg>
              <RIcon v-else :name="item.icon" :variant="scene === 10 ? 'library' : scene === 11 ? 'note' : scene === 12 ? 'detail' : scene === 14 ? 'bank' : scene === 15 ? 'route' : scene === 16 ? 'recall' : scene === 17 ? 'writing' : scene === 19 ? 'report' : scene === 20 ? 'design' : scene === 21 ? 'review' : scene === 22 ? 'compare' : scene === 23 ? 'recollection' : undefined" :size="scene === 9 ? 34 : [16, 18, 19, 21, 22, 23].includes(scene) ? 30 : 28" :stroke-width="[14, 18, 19].includes(scene) ? 2.1 : 1.7" /><span>{{ item.label }}</span>
            </a>
            <template v-if="scene !== 9">
              <div class="r-nav-separator" />
              <span v-if="scene === 15" class="r-tools-heading">工具</span>
              <a v-for="item in secondary" :key="item.id" :href="`replica.html?scene=${String(item.id).padStart(2, '0')}`" class="r-nav-item r-secondary" @click.prevent="navigate(item.id)">
                <svg v-if="scene === 18 && followNavPaths[item.icon]" class="r-icon" width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path v-for="(d, index) in followNavPaths[item.icon]" :key="index" :d="d" /></svg>
                <RIcon v-else :name="item.icon" :variant="scene === 10 ? 'library' : scene === 11 ? 'note' : scene === 12 ? 'detail' : scene === 14 ? 'bank' : scene === 15 ? 'route' : scene === 16 ? 'recall' : scene === 17 ? 'writing' : scene === 19 ? 'report' : scene === 20 && item.id !== 14 ? 'design' : scene === 21 ? 'review' : scene === 22 ? 'compare' : scene === 23 ? 'recollection' : undefined" :size="[18, 21, 23].includes(scene) ? 30 : scene === 22 ? 28 : 27" :stroke-width="[14, 18, 19].includes(scene) ? 2.1 : 1.7" /><span>{{ item.label }}</span>
              </a>
            </template>
          </nav>
          <button class="r-sidebar-avatar" title="设置与数据" @click="navigate(28)"><span class="r-avatar">林</span><RIcon v-if="scene === 16" name="chevron-right" :size="14" /></button>
        </aside>
        <header v-if="scene !== 17" class="r-topbar">
          <button v-if="scene === 30" class="r-breadcrumb r-inbox-breadcrumb" @click="navigate(10)">知识 / 收件箱<RIcon name="chevron-down" :size="23" /></button>
          <div v-else-if="scene !== 2 && scene !== 28" class="r-breadcrumb">
            <RIcon v-if="![3, 6, 11, 12, 13, 14, 15, 18, 20, 23, 24].includes(scene)" :name="scene === 8 ? 'chevron-left' : 'house'" :variant="scene === 16 ? 'recall' : scene === 19 ? 'report' : undefined" :size="25" />
            <template v-if="scene === 20"><span>训练</span><span>/</span><span>系统设计</span><span>/</span><strong>秒杀发奖</strong></template>
            <template v-else>{{ page.breadcrumb }}</template>
          </div>
          <button class="r-top-search" :style="{ left: `${headerBox.left - 20}px`, width: `${headerBox.width}px` }" @click="navigate(25)"><RIcon name="search" :size="25" /><span>{{ searchHint }}</span><kbd v-if="![3, 7, 9, 13, 16, 18, 19, 21, 22, 24, 26].includes(scene)">⌘K</kbd></button>
          <div class="r-top-status">
            <span class="r-saved"><RIcon :name="[20, 21, 23].includes(scene) ? 'circle-check' : 'check'" :variant="scene === 23 ? 'recollection' : undefined" /><span>已保存<span v-if="scene === 5" class="r-muted"> 10:24</span><span v-if="scene === 11" class="r-muted"> 14:32</span></span></span>
            <button class="r-icon-btn" title="通知" @click="notify('暂无新通知')"><RIcon name="bell" :variant="scene === 10 ? 'library' : scene === 11 ? 'note' : scene === 12 ? 'detail' : scene === 16 ? 'recall' : scene === 19 ? 'report' : scene === 20 ? 'design' : scene === 21 ? 'review' : scene === 22 ? 'compare' : scene === 23 ? 'recollection' : undefined" :size="28" /></button>
            <button v-if="scene === 20" class="r-icon-btn" title="提醒" @click="notify('暂无新提醒')"><RIcon name="bell" variant="design" :size="28" /></button>
            <button title="设置与数据" class="r-avatar" @click="navigate(28)">林</button>
          </div>
        </header>
      </template>
      <main class="r-main" :style="{ left: `${sidebar}px` }">
        <component :is="group" :key="scene" :scene="scene" @navigate="navigate" @notify="notify" />
      </main>
      <div v-if="toast" class="r-toast" role="status">{{ toast }}</div>
    </div>
  </div>
</template>
<style scoped>
.r-stage { position: relative; margin: 0 auto; overflow: hidden; }
.r-canvas { transform-origin: top left; }
.r-scene-02 .r-sidebar { left: 17px; top: 21px; bottom: 20px; width: 218px; border-radius: 24px; }
.r-scene-02 .r-sidebar .r-brand { left: 14px; top: 27px; gap: 8px; }
.r-scene-02 .r-sidebar nav { top: 130px; left: 13px; right: 17px; }
.r-scene-02 .r-nav-item { height: 59px; padding: 0 16px; font-size: 23px; }
.r-scene-02 .r-nav-separator { margin-top: 26px; margin-bottom: 20px; }
.r-scene-02 .r-top-status { gap: 35px; }
.r-scene-03 .r-sidebar { border-radius: 36px; border-color: #242019; bottom: 30px; }
.r-scene-03 .r-sidebar nav { left: 15px; right: 15px; top: 149px; gap: 9px; }
.r-scene-03 .r-nav-item { padding: 0 23px; }
.r-scene-04 .r-sidebar nav { top: 162px; }
.r-scene-04 .r-topbar { left: 267px; top: 14px; }
.r-scene-05 .r-sidebar { left: 16px; top: 19px; bottom: 30px; width: 228px; }
.r-scene-05 .r-sidebar nav { left: 11px; right: 15px; top: 153px; gap: 4px; }
.r-scene-05 .r-sidebar .r-brand { left: 23px; }
.r-scene-05 .r-topbar { top: 8px; left: 274px; }
.r-scene-05 .r-breadcrumb { font-size: 19px; }
.r-scene-06 .r-sidebar { left: 14px; top: 20px; bottom: 44px; width: 219px; border-radius: 20px; }
.r-scene-06 .r-sidebar nav { top: 114px; left: 10px; right: 15px; gap: 0; }
.r-scene-06 .r-nav-item { height: 56px; padding-left: 21px; gap: 21px; font-size: 20px; }
.r-scene-06 .r-nav-separator { margin-block: 20px 10px; }
.r-scene-06 .r-sidebar :deep(.r-brand) { top: 29px; left: 20px; }
.r-scene-06 .r-sidebar :deep(.r-brand-word) { font-size: 20px; }
.r-scene-06 .r-sidebar-avatar { left: 22px; bottom: 16px; }.r-scene-06 .r-sidebar-avatar .r-avatar { font-size: 22px; }
.r-scene-06 .r-topbar { background: #211b1588; border: 1px solid #514334; border-radius: 20px; height: 65px; padding: 0 16px; }
.r-scene-07 .r-sidebar { width: 256px; top: 22px; bottom: 7px; }
.r-scene-07 .r-sidebar nav { top: 144px; left: 22px; right: 18px; }
.r-scene-08 .r-sidebar nav { top: 124px; left: 24px; right: 24px; }
.r-scene-08 .r-nav-item { height: 60px; padding-left: 20px; }
.r-scene-09 .r-sidebar { top: 92px; bottom: 1px; width: 118px; }
.r-scene-09 .r-sidebar .r-brand { position: fixed; left: 30px; top: 23px; gap: 10px; }
.r-scene-09 .r-sidebar .r-brand-word { font-size: 26px; }
.r-scene-09 .r-sidebar nav { top: 40px; left: 13px; right: 13px; gap: 9px; }
.r-scene-09 .r-nav-item { height: 91px; flex-direction: column; justify-content: center; gap: 3px; padding: 0; font-size: 21px; border-radius: 18px; }
.r-scene-09 .r-nav-item.r-selected { height: 83px; margin-bottom: 8px; background: linear-gradient(120deg,#a77a36,#80541f); }
.r-scene-09::before { content: ""; position: absolute; left: 0; top: 0; width: 248px; height: 93px; border: 1px solid #5d503233; border-radius: 26px 0 0; pointer-events: none; }
.r-scene-09::after { content: ""; position: absolute; left: 248px; right: 0; top: 92px; border-top: 1px solid #5d503226; pointer-events: none; }
.r-scene-09 .r-sidebar :deep(.r-brand-word small) { font-size: 16px; }
.r-scene-09 .r-topbar { left: 280px; top: 14px; height: 70px; }
.r-scene-09 .r-top-search { left: 336px !important; top: 11px; height: 49px; }
.r-scene-09 .r-breadcrumb { font-size: 19px; }
.r-scene-09 .r-sidebar-avatar { left: 30px; bottom: 37px; }
.r-scene-10 .r-sidebar { left: 7px; width: 213px; top: 21px; bottom: 34px; border-radius: 20px; background: linear-gradient(115deg, #2b241b90, #1b1814ba); }
.r-scene-10 .r-sidebar nav { top: 113px; left: 14px; right: 14px; gap: 5px; }
.r-scene-10 .r-nav-item { height: 59px; padding: 0 18px; font-size: 22px; }
.r-scene-10 .r-nav-separator { margin-top: 18px; }
.r-scene-10 .r-nav-item.r-secondary { height: 56px; font-size: 21px; }
.r-scene-10 .r-sidebar-avatar { left: 27px; bottom: 24px; }
.r-scene-10 .r-sidebar-avatar::before { content: ""; position: absolute; top: -26px; left: 0; width: 157px; height: 1px; background: #ffffff0b; }
.r-scene-10 .r-avatar { width: 47px; height: 53px; font-size: 25px; border-color: #827b8b66; background: linear-gradient(130deg, #6a6577, #4d4857); }
.r-scene-10 .r-topbar { top: 37px; left: 253px; right: 35px; height: 54px; }
.r-scene-10 .r-breadcrumb { gap: 16px; font-size: 21px; }
.r-scene-10 .r-top-search { left: 396px !important; top: 1px; height: 53px; }
.r-scene-10 .r-top-status { gap: 31px; }
.r-scene-10 .r-saved { color: #b1d7bd; }
.r-scene-10 .r-saved svg { width: 22px; height: 25px; background: #4da46c; fill: #4da46c; }
.r-scene-11 { background: linear-gradient(110deg, #0e0a07, #060503 48%, #0a0705); will-change: transform; }
.r-scene-11 .r-main::before { content: ""; position: absolute; top: 25px; left: -1px; right: 20px; bottom: 41px; border: 1px solid #44382e; border-radius: 26px; background: linear-gradient(115deg, #1c1916b0, #141210 60%, #211a15c9); pointer-events: none; }
.r-scene-11 .r-sidebar { left: 21px; width: 214px; top: 25px; bottom: 41px; border-radius: 22px; background: radial-gradient(ellipse 150px 220px at 0 0, #65452e44, transparent), linear-gradient(160deg, #15110f, #151210 58%, #181311); border-color: #65503e; }
.r-scene-11 .r-sidebar :deep(.r-brand) { left: 25px; top: 30px; gap: 15px; }
.r-scene-11 .r-sidebar :deep(.r-emblem) { width: 44px; height: 48px; color: #e2a875; }
.r-scene-11 .r-sidebar :deep(.r-brand-word) { font-size: 22px; line-height: 28px; }
.r-scene-11 .r-sidebar :deep(.r-brand-word small) { font-size: 13px; line-height: 18px; margin-top: 0; }
.r-scene-11 .r-sidebar nav { top: 120px; left: 12px; right: 12px; gap: 1px; }
.r-scene-11 .r-nav-item { font-size: 21px; height: 59px; padding: 0 20px; gap: 21px; }
.r-scene-11 .r-nav-item.r-selected { height: 56px; margin-block: 2px 1px; border-radius: 17px; border-color: #f0b17e; background: linear-gradient(120deg, #a97145, #67472fc9); }
.r-scene-11 .r-nav-item.r-secondary { height: 56px; font-size: 20px; }
.r-scene-11 .r-nav-separator { margin: 51px 6px 25px; }
.r-scene-11 .r-sidebar-avatar { left: 22px; bottom: 24px; }
.r-scene-11 .r-sidebar-avatar::before { content: ""; position: absolute; left: -3px; top: -29px; width: 175px; height: 1px; background: #69513c55; }
.r-scene-11 .r-sidebar-avatar .r-avatar { width: 54px; height: 58px; font-size: 27px; }
.r-scene-11 .r-avatar { background: linear-gradient(130deg, #6d6360, #47403d); border-color: #8c7e7055; }
.r-scene-11 .r-topbar { top: 33px; left: 270px; right: 45px; }
.r-scene-11 .r-breadcrumb { font-size: 18px; }
.r-scene-11 .r-top-search { left: 532px !important; top: 14px; height: 47px; font-size: 16px; padding-inline: 16px; }
.r-scene-11 .r-top-status { gap: 43.5px; }
.r-scene-11 .r-top-status .r-avatar { width: 42px; height: 46px; font-size: 22px; }
.r-scene-11 .r-saved { position: absolute; left: 930px; font-size: 16px; color: #bdb5ac; gap: 12px; }
.r-scene-11 .r-saved svg { width: 23px; height: 24px; background: #64bf8c; fill: #64bf8c; }
.r-scene-12 { background: radial-gradient(ellipse 250px 650px at 12% 55%, #2c241a33, transparent), linear-gradient(115deg, #0e0c0b, #0b0907 72%, #0e0c0a); will-change: transform; }
.r-scene-12 .r-sidebar { width: 222px; border-color: #30282166; background: radial-gradient(ellipse 150px 240px at 5% 0, #79533322, transparent), linear-gradient(160deg, #151310, #161412 60%, #161311); }
.r-scene-12 .r-sidebar :deep(.r-brand) { left: 26px; top: 44px; }
.r-scene-12 .r-sidebar :deep(.r-emblem) { width: 49px; height: 50px; color: #d89c69; }
.r-scene-12 .r-sidebar :deep(.r-brand-word) { font-size: 26px; line-height: 31px; }
.r-scene-12 .r-sidebar :deep(.r-brand-word small) { font-size: 15.5px; line-height: 23px; margin-top: 0; }
.r-scene-12 .r-sidebar nav { top: 130px; left: 23px; right: 8px; gap: 6.5px; }
.r-scene-12 .r-nav-item { font-size: 22px; gap: 15px; }
.r-scene-12 .r-nav-item.r-selected { background: linear-gradient(120deg, #ac784cbb, #60422dc9); border-color: #a7774eaa; }
.r-scene-12 .r-nav-item.r-secondary { height: 49px; font-size: 21px; }
.r-scene-12 .r-nav-separator { margin: 24px 19px 27px; }
.r-scene-12 .r-sidebar-avatar { left: 43px; bottom: 31px; }
.r-scene-12 .r-sidebar-avatar .r-avatar { width: 54px; height: 54px; }
.r-scene-12 .r-avatar { background: linear-gradient(130deg, #8e7c70, #6c625b); border-color: #a38c7944; }
.r-scene-12 .r-topbar { top: 16px; right: 28px; }
.r-scene-12 .r-breadcrumb { position: relative; left: 14px; top: -6px; }
.r-scene-12 .r-top-search { left: 341px !important; top: 7px; font-size: 17px; background: #25211eee !important; border-color: #3a332b77 !important; }
.r-scene-12 .r-top-search kbd { background: transparent; font-size: 17px; padding: 0; }
.r-scene-12 .r-top-status { gap: 46px; }
.r-scene-12 .r-top-status::before { content: ""; position: absolute; right: 178px; top: 23px; height: 29px; width: 1px; background: #5b4d3d44; }
.r-scene-12 .r-top-status > .r-avatar { width: 52px; height: 54px; }
.r-scene-12 .r-saved { position: absolute; right: 218px; top: 24px; font-size: 19px; }
.r-scene-12 .r-saved svg { width: 24px; height: 24px; }
.r-scene-13 .r-sidebar nav { top: 143px; left: 15px; right: 15px; gap: 8px; }
.r-scene-13 .r-sidebar { width: 212px; bottom: 0; }
.r-scene-13 .r-sidebar .r-brand { left: 25px; top: 38px; }
.r-scene-13 .r-nav-item { height: 59px; padding: 0 24px; gap: 16px; font-size: 18.5px; white-space: nowrap; }
.r-scene-13 .r-nav-item.r-secondary { height: 47px; }
.r-scene-13 .r-nav-separator { margin: 11px 13px 24px; }
.r-scene-13 .r-sidebar-avatar { left: 27px; }
.r-scene-13 .r-top-search { top: -4px; font-size: 18px; }
.r-scene-13 .r-breadcrumb { position: relative; left: 10px; top: -11px; }
.r-scene-13 .r-saved { position: relative; top: -12px; font-size: 18px; }
.r-scene-14 { will-change: transform; }
.r-scene-14::before { content: ""; position: absolute; left: 230px; right: 0; top: 7px; height: 74px; border: 1px solid #51433b66; border-bottom: 0; border-radius: 24px 24px 0 0; background: linear-gradient(110deg, #251f1b77, #1a17145c); pointer-events: none; }
.r-scene-14 .r-sidebar { top: 7px; bottom: 8px; width: 230px; border-color: #62504799; background: radial-gradient(ellipse 180px 560px at -20% 72%, #80563b33, transparent), linear-gradient(150deg, #211c19, #211a17 60%, #191513); }
.r-scene-14 .r-sidebar::before { content: ""; position: absolute; left: 28px; right: 26px; top: 125px; height: 1px; background: #8d736122; }
.r-scene-14 .r-sidebar :deep(.r-brand) { left: 28px; top: 40px; gap: 12px; }
.r-scene-14 .r-sidebar :deep(.r-emblem) { width: 44px; height: 55px; color: #dcb184; filter: none; }
.r-scene-14 .r-sidebar :deep(.r-brand-word) { font-weight: 500; }
.r-scene-14 .r-sidebar :deep(.r-brand-word small) { font-size: 14px; color: #9c9188; }
.r-scene-14 .r-sidebar nav { top: 140px; left: 22px; right: 20px; gap: 4px; }
.r-scene-14 .r-nav-item { height: 55px; padding-left: 17px; font-size: 22px; color: #efe8e1; }
.r-scene-14 .r-nav-item.r-selected { margin-top: 3px; height: 52px; border-radius: 15px; background: linear-gradient(110deg, #9c6d47b8, #6a4b35c9); box-shadow: inset 1px 1px 5px #f7c49216; }
.r-scene-14 .r-nav-separator { margin: 11px 13px 11px; }
.r-scene-14 .r-nav-item.r-secondary { height: 49px; }
.r-scene-14 .r-sidebar-avatar { left: 29px; bottom: 30px; }
.r-scene-14 .r-sidebar-avatar::before { content: ""; position: absolute; left: -1px; top: -25px; width: 174px; height: 1px; background: #8d736122; }
.r-scene-14 .r-sidebar-avatar .r-avatar { width: 57px; height: 57px; }
.r-scene-14 .r-avatar { background: linear-gradient(130deg, #84716d, #645957); border-color: #a28c7333; }
.r-scene-14 .r-topbar { top: 8px; left: 254px; right: 29px; height: 72px; }
.r-scene-14 .r-top-search { left: 504px !important; top: 8px; height: 53px; border-radius: 19px; background: linear-gradient(110deg, #25211fe6, #221e1bd9) !important; }
.r-scene-14 .r-top-search kbd { background: transparent; padding: 0; }
.r-scene-14 .r-top-status { gap: 21px; }
.r-scene-14 .r-top-status > .r-avatar { width: 47px; height: 49px; font-size: 23px; }
.r-scene-14 .r-saved { position: absolute; right: 138px; top: 21px; font-size: 20px; color: #8bd398; }
.r-scene-14 .r-saved svg { background: linear-gradient(#367b46, #296337); fill: none; color: #b3e6b5; border: 1px solid #45914e; }
.r-scene-15 { will-change: transform; background: radial-gradient(ellipse at 0 67%, #3c292033, transparent 37%), radial-gradient(ellipse at 80% 100%, #30211615, transparent 55%), #121110; }
.r-scene-15::before { content: ""; position: absolute; left: 219px; right: 0; top: 85px; height: 1px; background: #ffffff06; }
.r-scene-15 .r-sidebar { top: 22px; left: 18px; bottom: 29px; width: 202px; border-radius: 21px; border-color: #302a27; background: linear-gradient(115deg, #241e1b, #1a1715); }
.r-scene-15 .r-sidebar :deep(.r-brand) { left: 20px; top: 27px; gap: 14px; }
.r-scene-15 .r-sidebar :deep(.r-emblem) { width: 35px; height: 43px; color: #dfa366; filter: drop-shadow(0 0 7px #e4a15c24); }
.r-scene-15 .r-sidebar :deep(.r-brand-word) { font-size: 26px; font-weight: 500; line-height: 30px; }
.r-scene-15 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 21px; }
.r-scene-15 .r-sidebar nav { top: 115px; left: -1px; right: 6px; gap: 12px; }
.r-scene-15 .r-nav-item { padding-left: 23px; font-size: 22px; gap: 20px; color: #d4cec9; }
.r-scene-15 .r-nav-item.r-selected { height: 65px; margin-top: -4px; margin-bottom: -5px; border-radius: 15px; background: linear-gradient(105deg, #bc85549c, #785035b0); box-shadow: inset 1px 1px 8px #efba8630, -3px 0 15px #e49c5533; }
.r-scene-15 .r-nav-separator { margin: 3px 21px 0; }
.r-scene-15 .r-tools-heading { color: #8f8780; font-size: 17px; line-height: 20px; margin: 8px 21px 0; }
.r-scene-15 .r-nav-item.r-secondary { height: 48px; font-size: 20px; color: #bdb6af; }
.r-scene-15 .r-sidebar-avatar { left: 20px; bottom: 21px; }
.r-scene-15 .r-sidebar-avatar .r-avatar { width: 47px; height: 50px; font-size: 23px; }
.r-scene-15 .r-avatar { background: linear-gradient(130deg, #a59e98, #857f7a); border-color: #b0a7a03b; }
.r-scene-15 .r-topbar { top: 12px; left: 244px; right: 36px; height: 75px; }
.r-scene-15 .r-top-search { left: 340px !important; top: 13px; height: 52px; border-radius: 26px; }
.r-scene-15 .r-top-search kbd { border: 1px solid #6c625b55; padding: 1px 5px; font-size: 19px; line-height: 25px; }
.r-scene-15 .r-saved { font-size: 19px; gap: 8px; color: #a6d29e; }
.r-scene-15 .r-saved svg { width: 21px; height: 21px; background: #91c495; fill: none; color: #2d5a37; padding: 3px; }
.r-scene-15 .r-top-status { gap: 30px; }
.r-scene-15 .r-top-status > .r-avatar { width: 43px; height: 45px; font-size: 22px; }
.r-scene-16 { will-change: transform; background: linear-gradient(180deg, #100e0d, #090808 60%, #080707); }
.r-scene-16 .r-sidebar { width: 220px; border-radius: 0; border-color: #272220; bottom: 0; background: linear-gradient(170deg, #121010, #1a1615 75%); }
.r-scene-16 .r-sidebar > a { position: absolute; left: 29px; top: 29px; }
.r-scene-16 .r-sidebar :deep(.r-brand) { position: static; gap: 10px; }
.r-scene-16 .r-sidebar :deep(.r-emblem) { width: 49px; height: 54px; color: #f2ede6; filter: none; }
.r-scene-16 .r-sidebar :deep(.r-brand-word) { font-size: 28px; line-height: 33px; }
.r-scene-16 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 18px; }
.r-scene-16 .r-sidebar::after { content: ""; position: absolute; top: 105px; left: 30px; right: 29px; height: 1px; background: #ffffff06; }
.r-scene-16 .r-sidebar nav { top: 138px; left: 17px; right: 16px; gap: 13px; }
.r-scene-16 .r-nav-item { height: 60px; padding: 0 18px; gap: 18px; }
.r-scene-16 .r-nav-item.r-selected { border-radius: 16px; background: radial-gradient(ellipse at 14% 55%, #d69b6699, transparent 55%), linear-gradient(110deg, #5e3c26, #35261c); }
.r-scene-16 .r-nav-separator { margin: 15px 13px 23px; }
.r-scene-16 .r-nav-item.r-secondary { height: 49px; font-size: 23px; }
.r-scene-16 .r-sidebar-avatar { left: 42px; bottom: 32px; width: 137px; text-align: left; }
.r-scene-16 .r-sidebar-avatar .r-avatar { width: 56px; height: 57px; background: linear-gradient(130deg, #63807a, #3c5c59); }
.r-scene-16 .r-sidebar-avatar > .r-icon { position: absolute; right: 0; top: 23px; color: #6e655f; }
.r-scene-16 .r-sidebar-avatar::before { content: ""; position: absolute; left: -12px; top: -36px; width: 160px; height: 1px; background: #ffffff10; }
.r-scene-16 .r-topbar { left: 268px; top: 14px; right: 31px; }
.r-scene-16 .r-breadcrumb { font-size: 22px; gap: 16px; }
.r-scene-16 .r-breadcrumb .r-icon { width: 30px; height: 30px; }
.r-scene-16 .r-top-search { left: 337px !important; top: 10px; height: 56px; background: linear-gradient(110deg, #25211fe6, #211c19b3) !important; border-color: #4e4540 !important; color: #89827d; }
.r-scene-16 .r-top-status { gap: 35px; }
.r-scene-16 .r-top-status > .r-avatar { width: 47px; height: 49px; font-size: 23px; background: linear-gradient(130deg, #c7957b, #d2a78e); }
.r-scene-16 .r-top-status::before { content: ""; position: absolute; right: 134px; top: 23px; height: 29px; width: 1px; background: #ffffff10; }
.r-scene-16 .r-saved { color: #b1d5bd; }
.r-scene-16 .r-saved svg { background: #60a880; fill: #60a880; }
.r-scene-17 { will-change: transform; background: radial-gradient(ellipse at 70% 35%, #15110f44, transparent 85%), linear-gradient(180deg, #100e0d, #0c0b0a); }
.r-scene-17 .r-sidebar { top: 23px; left: 19px; bottom: 55px; width: 198px; border-radius: 21px; background: linear-gradient(125deg, #25211f, #201b18 80%, #2c241f); border-color: #55493f; }
.r-scene-17 .r-sidebar > a { position: absolute; left: 26px; top: 30px; }
.r-scene-17 .r-sidebar :deep(.r-brand) { position: static; gap: 12px; }
.r-scene-17 .r-sidebar :deep(.r-emblem) { width: 44px; height: 48px; color: #bd8956; filter: none; }
.r-scene-17 .r-sidebar :deep(.r-brand-word) { font-size: 22px; line-height: 26px; font-weight: 500; transform: translateY(5px); }
.r-scene-17 .r-sidebar :deep(.r-brand-word small) { font-size: 12px; line-height: 18px; margin-top: 0; }
.r-scene-17 .r-sidebar nav { top: 123px; left: 6px; right: 6px; gap: 4px; }
.r-scene-17 .r-nav-item { height: 58px; padding-left: 21px; font-size: 22px; gap: 21px; }
.r-scene-17 .r-nav-item.r-selected { background: radial-gradient(ellipse at 50% 80%, #c88b5377, transparent), linear-gradient(120deg, #98653f, #5f412b); border-color: #d79961; box-shadow: inset 0 0 7px #efb57966, 0 0 17px #c98d5022; }
.r-scene-17 .r-nav-separator { margin: 12px 8px 13px; background: #74645755; }
.r-scene-17 .r-nav-item.r-secondary { font-size: 21px; height: 55px; }
.r-scene-17 .r-sidebar-avatar { left: 22px; bottom: 18px; }
.r-scene-17 .r-sidebar-avatar .r-avatar { width: 45px; height: 47px; font-size: 24px; background: linear-gradient(120deg, #806f61, #5e5044); }
.r-scene-17 .r-sidebar-avatar::before { content: ""; position: absolute; left: -7px; top: -25px; width: 166px; height: 1px; background: #7c69564d; }
.r-scene-18 .r-sidebar { top: 8px; bottom: 8px; width: 229px; border-radius: 22px; background: linear-gradient(150deg, #171411, #241b13); border-color: #342b22; }
.r-scene-18 .r-sidebar nav { top: 159px; left: 17px; right: 18px; gap: 3px; }
.r-scene-18 .r-nav-item { height: 59px; padding-left: 16px; }
.r-scene-18 .r-nav-item.r-selected { height: 57px; margin-bottom: 2px; border-color: transparent; background: linear-gradient(100deg, #986331, #372b2080); box-shadow: none; }
.r-scene-18 .r-nav-item.r-secondary { height: 57px; font-size: 24px; }
.r-scene-18 .r-nav-separator { margin: 19px 11px 18px; }
.r-scene-18 .r-brand-rule { position: absolute; left: 29px; top: 137px; right: 29px; height: 1px; background: #ffffff16; }
.r-scene-18 .r-sidebar > a { position: absolute; left: 28px; top: 38px; display: block; }
.r-scene-18 .r-sidebar .r-brand { position: static; gap: 10px; }
.r-scene-18 .r-sidebar :deep(.r-brand-word small) { font-size: 16px; }
.r-scene-18 .r-sidebar :deep(.r-emblem) { width: 44px; height: 52px; transform: translateY(4px); }
.r-scene-18 .r-sidebar-avatar { bottom: 29px; }
.r-scene-18 .r-sidebar-avatar .r-avatar { width: 61px; height: 64px; background: linear-gradient(130deg, #9b6b3c, #6e4423); }
.r-scene-18 .r-topbar { top: 16px; left: 254px; right: 36px; }
.r-scene-18 .r-breadcrumb { font-size: 26px; position: relative; top: -1px; }
.r-scene-18 .r-top-search { left: 490px !important; top: 6px; height: 64px; font-size: 21px; }
.r-scene-18 .r-top-search > .r-icon { width: 32px; height: 32px; }
.r-scene-18 .r-top-status { gap: 32px; }
.r-scene-18 .r-saved { font-size: 24px; color: #64b76f; }
.r-scene-18 .r-saved svg { fill: #64b76f; background: #64b76f; }
.r-scene-18 .r-top-status .r-avatar { width: 50px; height: 54px; }
.r-scene-18 .r-top-status .r-icon-btn > .r-icon { width: 32px; height: 32px; }
.r-scene-19 { will-change: transform; }
.r-scene-19 .r-sidebar { top: 20px; left: 20px; width: 205px; bottom: 28px; border-radius: 22px; background: linear-gradient(120deg, #221d18, #191512 70%, #30231d); border-color: #564638; }
.r-scene-19 .r-sidebar > a { position: absolute; left: 22px; top: 31px; }
.r-scene-19 .r-sidebar :deep(.r-brand) { position: static; gap: 11px; }
.r-scene-19 .r-sidebar :deep(.r-emblem) { width: 43px; height: 50px; color: #d59055; filter: none; }
.r-scene-19 .r-sidebar :deep(.r-brand-word) { font-size: 24px; line-height: 30px; font-weight: 500; }
.r-scene-19 .r-sidebar :deep(.r-brand-word small) { font-size: 14px; line-height: 20px; margin-top: 0; color: #e2d8cc; }
.r-scene-19 .r-sidebar::after { content: ""; position: absolute; top: 118px; left: 20px; right: 20px; height: 1px; background: #c9a27a22; }
.r-scene-19 .r-sidebar nav { top: 146px; left: 6px; right: 6px; gap: 4px; }
.r-scene-19 .r-nav-item { height: 59px; padding: 0 24px; font-size: 22px; gap: 21px; }
.r-scene-19 .r-nav-item.r-selected { height: 56px; margin-block: 1px 2px; background: radial-gradient(ellipse at 20% 40%, #cb935c66, transparent), linear-gradient(125deg, #9e6c43, #4a2f1b); border-color: #dfa166; border-radius: 30px; }
.r-scene-19 .r-nav-separator { margin-top: 22px; margin-bottom: 19px; }
.r-scene-19 .r-nav-item.r-secondary { height: 51px; gap: 24px; }
.r-scene-19 .r-sidebar-avatar { left: 23px; bottom: 26px; }
.r-scene-19 .r-sidebar-avatar .r-avatar { width: 60px; height: 60px; background: linear-gradient(130deg, #a98565, #603c24); }
.r-scene-19 .r-sidebar-avatar::before { content: ""; position: absolute; left: -3px; top: -40px; width: 163px; height: 1px; background: #c9a27a22; }
.r-scene-19 .r-topbar { left: 244px; padding: 0 30px; border: 1px solid #574b3f66; border-radius: 20px; background: linear-gradient(120deg, #2d262099, #241e19cc); top: 20px; height: 71px; }
.r-scene-19 .r-top-search { left: 450px !important; top: 12px; height: 46px; border-color: #665a4b55 !important; font-size: 18px; }
.r-scene-19 .r-top-status { gap: 32px; margin-right: -8px; }
.r-scene-19 .r-top-status > .r-avatar { width: 44px; height: 45px; background: linear-gradient(130deg, #b08260, #603b22); font-size: 24px; }
.r-scene-19 .r-top-status::before { content: ""; position: absolute; right: 159px; top: 22px; width: 1px; height: 28px; background: #cdb79b44; }
.r-scene-19 .r-breadcrumb { font-size: 21px; gap: 20px; }
.r-scene-19 .r-saved { color: #b1b0a1; }
.r-scene-20 .r-sidebar { top: 10px; bottom: 18px; width: 216px; }
.r-scene-20 { background: radial-gradient(ellipse at 0 67%, #2d211820, transparent 37%), #0b0a09; }
.r-scene-20 .r-sidebar { border-color: #302922; border-radius: 24px; background: radial-gradient(ellipse at 0 27%, #65462c44, transparent 75%), linear-gradient(115deg, #171310, #11100f); }
.r-scene-20 .r-sidebar > a { position: absolute; left: 30px; top: 29px; }
.r-scene-20 .r-sidebar :deep(.r-brand) { position: static; gap: 12px; }
.r-scene-20 .r-sidebar :deep(.r-emblem) { width: 42px; height: 50px; color: #d89559; filter: none; }
.r-scene-20 .r-sidebar :deep(.r-brand-word) { font-size: 27px; line-height: 32px; font-weight: 500; }
.r-scene-20 .r-sidebar :deep(.r-brand-word small) { font-size: 14px; line-height: 20px; margin-top: 0; color: #9f9690; }
.r-scene-20 .r-sidebar::after { content: ""; position: absolute; left: 30px; right: 29px; top: 109px; height: 1px; background: #a9876730; }
.r-scene-20 .r-sidebar nav { top: 131px; left: 18px; right: 22px; gap: 8px; }
.r-scene-20 .r-nav-item { font-size: 22px; height: 59px; padding-left: 21px; gap: 13px; white-space: nowrap; }
.r-scene-20 .r-nav-item.r-selected { height: 56px; margin-bottom: 3px; border-radius: 16px; background: linear-gradient(110deg, #b4824c8c, #64472e); border-color: #dc9d5d; }
.r-scene-20 .r-nav-separator { margin: 8px 11px 17px; }
.r-scene-20 .r-nav-item.r-secondary { height: 52px; }
.r-scene-20 .r-sidebar-avatar { left: 42px; bottom: 24px; }
.r-scene-20 .r-sidebar-avatar .r-avatar { width: 58px; height: 60px; font-size: 28px; }
.r-scene-20 .r-avatar { background: linear-gradient(130deg, #777470, #5e5c59); border-color: #827b7144; }
.r-scene-20 .r-topbar { top: 20px; left: 245px; right: 28px; height: 56px; }
.r-scene-20 .r-breadcrumb { font-size: 21px; gap: 14px; color: #b8b3ad; }
.r-scene-20 .r-breadcrumb strong { font-weight: 500; color: #f4f0e9; }
.r-scene-20 .r-top-search { left: 406px !important; top: 4px; height: 49px; border-color: #5e514366 !important; padding-inline: 15px; gap: 12px; }
.r-scene-20 .r-top-search kbd { background: #ffffff08; }
.r-scene-20 .r-top-status { gap: 11px; }
.r-scene-20 .r-top-status > .r-avatar { width: 45px; height: 47px; font-size: 25px; }
.r-scene-20 .r-saved { margin-right: 14px; font-size: 21px; color: #65c58c; }
.r-scene-20 .r-saved svg { width: 28px; height: 28px; padding: 0; fill: none; background: transparent; color: #65d795; }
.r-scene-20 .r-top-status .r-icon-btn { color: #99938d; }
.r-scene-21 .r-sidebar { border-radius: 25px 0 0 25px; width: 221px; bottom: 2px; border-color: #2c241b; background: radial-gradient(ellipse at 0 45%, #6d4b3038, transparent 70%), linear-gradient(110deg, #171410, #14110e); }
.r-scene-21 .r-sidebar > a { position: absolute; left: 29px; top: 35px; }
.r-scene-21 .r-sidebar :deep(.r-brand) { position: static; gap: 13px; }
.r-scene-21 .r-sidebar :deep(.r-emblem) { width: 45px; height: 52px; filter: none; }
.r-scene-21 .r-sidebar :deep(.r-brand-word) { font-size: 25px; font-weight: 500; line-height: 30px; }
.r-scene-21 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 20px; }
.r-scene-21 .r-sidebar nav { top: 139px; left: 20px; right: 6px; gap: 8px; }
.r-scene-21 .r-nav-item { height: 60px; padding-left: 18px; gap: 20px; font-size: 24px; }
.r-scene-21 .r-nav-item.r-selected { height: 62px; background: linear-gradient(115deg, #a36b3c77, #50321f88); border-color: #bb875b; border-radius: 18px; box-shadow: inset 1px 1px 5px #f7c49216; }
.r-scene-21 .r-nav-separator { margin: 28px 27px 22px 17px; }
.r-scene-21 .r-nav-item.r-secondary { height: 52px; }
.r-scene-21 .r-sidebar-avatar { left: 38px; bottom: 39px; }
.r-scene-21 .r-sidebar-avatar .r-avatar { width: 63px; height: 67px; font-size: 30px; background: linear-gradient(130deg, #746a5f, #514a42); }
.r-scene-21 .r-sidebar-avatar::before { content: ""; position: absolute; left: 0; top: -39px; width: 148px; height: 1px; background: #b59d7c22; }
.r-scene-21 .r-sidebar-avatar::after { content: ""; position: absolute; left: 135px; top: 31px; width: 6px; height: 6px; background: #655848; transform: rotate(45deg); }
.r-scene-21 .r-topbar { left: 252px; top: 22px; right: 31px; }
.r-scene-21 .r-breadcrumb { font-size: 21px; gap: 15px; }
.r-scene-21 .r-top-search { left: 515px !important; top: 9px; height: 57px; }
.r-scene-21 .r-top-status { gap: 37px; }
.r-scene-21 .r-saved { color: #36d77d; }
.r-scene-21 .r-saved svg { width: 26px; height: 26px; padding: 0; fill: none; background: transparent; color: #36d77d; }
.r-scene-21 .r-top-status > .r-avatar { width: 53px; height: 57px; background: linear-gradient(130deg, #787168, #504b44); }
.r-scene-22 .r-sidebar { left: 17px; top: 24px; bottom: 27px; width: 205px; }
.r-scene-22 .r-sidebar > a { position: absolute; left: 20px; top: 24px; }
.r-scene-22 .r-sidebar :deep(.r-brand) { position: static; gap: 10px; }
.r-scene-22 .r-sidebar :deep(.r-emblem) { width: 49px; height: 56px; color: #e8a56b; filter: drop-shadow(0 0 8px #dfa06a55); }
.r-scene-22 .r-sidebar :deep(.r-brand-word) { font-size: 30px; font-weight: 500; line-height: 36px; }
.r-scene-22 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 20px; }
.r-scene-22 .r-sidebar::after { content: ""; position: absolute; left: 26px; right: 25px; top: 107px; height: 1px; background: #b59d7c22; }
.r-scene-22 .r-sidebar nav { left: 15px; right: 15px; top: 131px; gap: 4px; }
.r-scene-22 .r-nav-item { padding: 0 15px; height: 60px; gap: 20px; }
.r-scene-22 .r-nav-item.r-selected { height: 62px; background: linear-gradient(120deg, #ae7d4e99, #5c3d26aa); border-color: #bf8b5b77; }
.r-scene-22 .r-nav-item.r-secondary { height: 52px; margin-bottom: 6px; }
.r-scene-22 .r-nav-separator { margin: 24px 11px 22px; }
.r-scene-22 .r-sidebar-avatar { left: 24px; bottom: 24px; }
.r-scene-22 .r-sidebar-avatar .r-avatar { width: 57px; height: 61px; background: linear-gradient(130deg, #bd8e5d, #82532c); font-size: 27px; }
.r-scene-22 .r-topbar { left: 252px; top: 27px; right: 43px; height: 73px; }
.r-scene-22 .r-breadcrumb { font-size: 21px; gap: 17px; }
.r-scene-22 .r-top-search { left: 384px !important; top: 7px; height: 59px; background: linear-gradient(110deg, #26262199, #1c1c1988) !important; border-color: #625f5866 !important; }
.r-scene-22 .r-top-status { gap: 18px; }
.r-scene-22 .r-saved { width: 141px; height: 59px; justify-content: center; gap: 11px; border-radius: 32px; background: linear-gradient(110deg, #2a2924, #171614); font-size: 22px; color: #98e6af; }
.r-scene-22 .r-saved svg { width: 30px; height: 30px; background: #6bde85; fill: #6bde85; color: #172e19; padding: 6px; }
.r-scene-22 .r-top-status .r-icon-btn { width: 74px; height: 59px; border-radius: 31px; background: linear-gradient(110deg, #23221e, #151410); color: #f4f1ea; }
.r-scene-22 .r-top-status > .r-avatar { width: 53px; height: 59px; margin-left: 8px; background: linear-gradient(130deg, #bb8654, #8b582d); }
.r-scene-23 .r-sidebar { width: 244px; bottom: 0; border-radius: 0; background: radial-gradient(ellipse at 0 0, #45312677, transparent 42%), linear-gradient(150deg, #181512, #1b1714 52%, #35291f); border-color: #332a24; }
.r-scene-23 .r-sidebar > a { position: absolute; left: 31px; top: 34px; }
.r-scene-23 .r-sidebar :deep(.r-brand) { position: static; gap: 11px; }
.r-scene-23 .r-sidebar :deep(.r-emblem) { width: 47px; height: 54px; }
.r-scene-23 .r-sidebar :deep(.r-brand-word) { font-size: 28px; font-weight: 500; line-height: 32px; }
.r-scene-23 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 22px; }
.r-scene-23 .r-sidebar nav { top: 142px; left: 15px; right: 34px; gap: 10px; }
.r-scene-23 .r-nav-item { height: 60px; padding-left: 21px; gap: 19px; }
.r-scene-23 .r-nav-item.r-selected { height: 62px; margin-top: -2px; border-radius: 32px; border-color: #e8b180; background: linear-gradient(110deg, #9a6b4699, #68472fd9); }
.r-scene-23 .r-nav-item.r-secondary { height: 54px; font-size: 23px; }
.r-scene-23 .r-nav-separator { margin: 18px 0 20px; }
.r-scene-23 .r-sidebar-avatar { left: 32px; bottom: 34px; }
.r-scene-23 .r-sidebar-avatar .r-avatar { width: 62px; height: 62px; font-size: 27px; }
.r-scene-23 .r-sidebar-avatar::before { content: ""; position: absolute; left: 0; top: -39px; width: 174px; height: 1px; background: #ffffff12; }
.r-scene-23 .r-avatar { background: linear-gradient(130deg, #82716a, #665c57); }
.r-scene-23 .r-topbar { left: 278px; right: 31px; top: 18px; height: 74px; }
.r-scene-23 .r-breadcrumb { font-size: 22px; }
.r-scene-23 .r-top-search { left: 317px !important; top: 9px; height: 56px; background: linear-gradient(110deg, #231e1bcc, #201b18a6) !important; border-color: #62584e88 !important; }
.r-scene-23 .r-top-search kbd { border: 1px solid #ffffff14; border-radius: 19px; padding: 2px 12px; background: #ffffff04; }
.r-scene-23 .r-top-status { gap: 38px; }
.r-scene-23 .r-saved { color: #c0bbb4; font-size: 22px; gap: 12px; }
.r-scene-23 .r-saved svg { background: transparent; fill: none; color: #70cb88; padding: 0; }
.r-scene-23 .r-top-status > .r-avatar { width: 56px; height: 56px; font-size: 25px; }
.r-scene-24 .r-sidebar { width: 208px; bottom: 0; }
.r-scene-24 .r-sidebar nav { top: 141px; left: 20px; right: 19px; gap: 7px; }
.r-scene-24 .r-nav-item { padding-left: 23px; }
.r-scene-25 .r-sidebar { border-radius: 0; width: 212px; bottom: 0; }
.r-scene-25 .r-sidebar nav { left: 24px; right: 20px; top: 151px; gap: 9px; }
.r-scene-25 .r-nav-item { padding-left: 19px; }
.r-scene-26 .r-sidebar { left: 20px; top: 130px; bottom: 26px; width: 203px; border-radius: 22px; }
.r-scene-26 .r-sidebar .r-brand { position: fixed; top: 31px; left: 40px; }
.r-scene-26 .r-sidebar nav { top: 14px; left: 11px; right: 12px; gap: 9px; }
.r-scene-26 .r-nav-item { padding: 0 15px; height: 59px; }
.r-scene-26 .r-nav-separator { margin-top: 22px; margin-bottom: 14px; }
.r-scene-26 .r-topbar { left: 301px; top: 18px; }
.r-scene-26 .r-top-search { left: 360px !important; }
.r-scene-27 .r-sidebar { width: 216px; bottom: 0; }
.r-scene-27 .r-sidebar nav { top: 146px; left: 20px; right: 20px; gap: 5px; }
.r-scene-27 .r-nav-item { height: 60px; padding-left: 19px; font-size: 22px; }
.r-scene-27 .r-nav-separator { margin: 19px 11px 17px; }
.r-scene-28 .r-sidebar { left: 19px; top: 20px; width: 212px; bottom: 28px; }
.r-scene-28 .r-sidebar nav { left: 20px; right: 15px; top: 118px; gap: 4px; }
.r-scene-28 .r-nav-item { height: 59px; padding-left: 12px; font-size: 22px; }
.r-scene-28 .r-topbar { left: 264px; height: 75px; top: 20px; padding-right: 18px; border: 1px solid #3e3024; background: #221b1570; border-radius: 25px; }
.r-scene-30 .r-sidebar { top: 27px; left: 8px; width: 219px; bottom: 25px; border-radius: 20px; }
.r-scene-30 .r-sidebar .r-brand { left: 27px; top: 31px; }
.r-scene-30 .r-sidebar :deep(.r-emblem) { width: 62px; height: 62px; }
.r-scene-30 .r-sidebar :deep(.r-brand-word) { font-size: 36px; font-weight: 500; }
.r-scene-30 .r-sidebar :deep(.r-brand-word small) { font-size: 16px; margin-top: 2px; }
.r-scene-30 .r-sidebar nav { top: 145px; left: 20px; right: 20px; gap: 9px; }
.r-scene-30 .r-nav-item { height: 59px; padding: 0 19px; }
.r-scene-30 .r-topbar { top: 25px; }
.r-scene-30 .r-top-search { height: 62px; }
.r-scene-30 .r-saved { position: relative; left: 14px; font-size: 20px; }
.r-inbox-breadcrumb { height: 62px; width: 336px; margin-left: -6px; padding: 0 12px !important; justify-content: space-between; border: 1px solid #332a20 !important; border-radius: 20px; font-size: 23px; }
</style>
