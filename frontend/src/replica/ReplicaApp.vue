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
  const ordinary = [
    { id: 2, label: '今日', icon: scene.value === 2 ? 'calendar-days' : 'house' },
    { id: 3, label: '准备', icon: scene.value === 18 ? 'square-check-rounded' : scene.value === 5 ? 'file-text' : scene.value === 13 ? 'clipboard' : [3, 7].includes(scene.value) ? 'target' : [6, 11].includes(scene.value) ? 'book-open' : 'play' },
    { id: 10, label: '知识', icon: 'book-open' },
    { id: 17, label: '训练', icon: scene.value === 18 ? 'circle-play' : [17, 20, 23].includes(scene.value) ? 'play' : 'dumbbell' },
    { id: 21, label: '复盘', icon: [5, 13, 18, 30].includes(scene.value) ? 'chart-no-axes-column' : 'refresh-cw' },
    { id: 24, label: '成长', icon: [5, 30].includes(scene.value) ? 'leaf' : [3, 7, 12, 13, 16, 18, 20, 23, 26, 27].includes(scene.value) ? 'sprout' : 'chart-no-axes-combined' },
  ]
  if (scene.value === 16) [ordinary[2], ordinary[3]] = [ordinary[3]!, ordinary[2]!]
  if (scene.value === 26) ordinary.splice(4, 0, { id: 26, label: '最近访问', icon: 'clock' })
  if (scene.value === 27) ordinary.splice(4, 0, { id: 27, label: 'AI 任务', icon: 'sparkles' })
  return ordinary
})
const secondary = computed(() => [
  { id: 14, label: '题库', icon: [2, 6, 10, 12, 14, 16, 18, 19, 22, 23, 28].includes(scene.value) ? 'database' : 'layers' },
  { id: 16, label: '回忆卡', icon: scene.value === 18 ? 'memory-cards' : 'credit-card' },
  ...(scene.value === 26 ? [] : [{ id: 26, label: '最近访问', icon: 'clock' }]),
  { id: 27, label: 'AI 任务', icon: scene.value === 27 ? 'settings' : 'sparkles' },
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
  const hints: Record<number, string> = { 2: '搜索题目、知识点或任务...', 4: '搜索项目、事实、表达素材...', 5: '', 6: '搜索资料...', 9: '搜索知识点、或 ⌘K', 10: '搜索笔记、概念、标签...', 11: '搜索知识、笔记、题目...', 12: '搜索知识、练习或问题...', 13: '⌘K 搜索知识、题目或内容...', 14: '⌘K', 15: '搜索题目、题单或知识点...', 18: '⌘K 搜索知识、题目或功能...', 20: '搜索题目、知识点...', 21: '⌘K 搜索知识、题目或内容...', 23: '搜索知识、题目或记录...', 25: '搜索知识、笔记、题目...', 26: '搜索知识、训练、问题...', 27: '搜索...', 28: '搜索知识、概念或问题...', 30: '搜索知识...' }
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
              <RIcon v-else :name="item.icon" :size="scene === 9 ? 34 : scene === 18 ? 30 : 28" :stroke-width="scene === 18 ? 2.1 : 1.7" /><span>{{ item.label }}</span>
            </a>
            <template v-if="scene !== 9">
              <div class="r-nav-separator" />
              <a v-for="item in secondary" :key="item.id" :href="`replica.html?scene=${String(item.id).padStart(2, '0')}`" class="r-nav-item r-secondary" @click.prevent="navigate(item.id)">
                <svg v-if="scene === 18 && followNavPaths[item.icon]" class="r-icon" width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path v-for="(d, index) in followNavPaths[item.icon]" :key="index" :d="d" /></svg>
                <RIcon v-else :name="item.icon" :size="scene === 18 ? 30 : 27" :stroke-width="scene === 18 ? 2.1 : 1.7" /><span>{{ item.label }}</span>
              </a>
            </template>
          </nav>
          <button class="r-sidebar-avatar" title="设置与数据" @click="navigate(28)"><span class="r-avatar">林</span></button>
        </aside>
        <header v-if="scene !== 17" class="r-topbar">
          <button v-if="scene === 30" class="r-breadcrumb r-inbox-breadcrumb" @click="navigate(10)">知识 / 收件箱<RIcon name="chevron-down" :size="23" /></button>
          <div v-else-if="scene !== 2 && scene !== 28" class="r-breadcrumb">
            <RIcon v-if="![3, 6, 11, 12, 13, 14, 15, 18, 20, 23, 24].includes(scene)" :name="scene === 8 ? 'chevron-left' : 'house'" :size="25" />
            {{ page.breadcrumb }}
          </div>
          <button class="r-top-search" :style="{ left: `${headerBox.left - 20}px`, width: `${headerBox.width}px` }" @click="navigate(25)"><RIcon name="search" :size="25" /><span>{{ searchHint }}</span><kbd v-if="![3, 7, 9, 12, 13, 16, 18, 19, 21, 22, 24, 26].includes(scene)">⌘K</kbd></button>
          <div class="r-top-status">
            <span class="r-saved"><RIcon name="check" /><span>已保存<span v-if="scene === 5" class="r-muted"> 10:24</span><span v-if="scene === 11" class="r-muted"> 14:32</span></span></span>
            <button class="r-icon-btn" title="通知" @click="notify('暂无新通知')"><RIcon name="bell" :size="28" /></button>
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
.r-scene-10 .r-sidebar { left: 7px; width: 213px; top: 21px; bottom: 33px; border-radius: 20px; }
.r-scene-10 .r-sidebar nav { top: 113px; left: 14px; right: 14px; gap: 5px; }
.r-scene-10 .r-nav-item { height: 59px; padding: 0 18px; }
.r-scene-10 .r-breadcrumb { position: relative; left: 11px; top: 6px; }
.r-scene-10 .r-top-search { left: 408px !important; top: 18px; }
.r-scene-11 .r-sidebar { left: 21px; width: 214px; top: 25px; bottom: 41px; border-radius: 22px; }
.r-scene-11 .r-sidebar nav { top: 117px; left: 12px; right: 12px; gap: 2px; }
.r-scene-11 .r-nav-item { font-size: 22px; height: 59px; padding: 0 20px; }
.r-scene-11 .r-nav-separator { margin: 42px 10px 20px; }
.r-scene-11 .r-topbar { top: 33px; left: 270px; }
.r-scene-11 .r-breadcrumb { font-size: 18px; }
.r-scene-11 .r-top-search { left: 527px !important; top: 11px; font-size: 16px; }
.r-scene-11 .r-saved { position: absolute; left: 930px; font-size: 16px; }
.r-scene-12 .r-sidebar nav { top: 130px; left: 23px; right: 10px; }
.r-scene-12 { background-color: #0f0d0b; }
.r-scene-12 .r-breadcrumb { position: relative; left: 14px; top: -6px; }
.r-scene-12 .r-top-search { left: 340px !important; top: 4px; font-size: 17px; }
.r-scene-12 .r-saved { position: absolute; right: 224px; top: 18px; font-size: 19px; }
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
.r-scene-14 .r-sidebar { top: 7px; bottom: 8px; width: 230px; }
.r-scene-14 .r-sidebar nav { top: 140px; left: 22px; right: 20px; gap: 4px; }
.r-scene-14 .r-nav-item { height: 55px; padding-left: 17px; font-size: 22px; }
.r-scene-15 .r-sidebar { top: 22px; left: 17px; bottom: 28px; width: 203px; border-color: #302a23; }
.r-scene-15 .r-sidebar .r-brand { left: 21px; top: 30px; gap: 6px; }
.r-scene-15 .r-sidebar nav { top: 115px; left: 0; right: 0; gap: 10px; }
.r-scene-15 .r-nav-item { padding-left: 24px; font-size: 22px; }
.r-scene-16 .r-sidebar { border-radius: 0; border-color: #241e19; bottom: 0; }
.r-scene-16 .r-sidebar nav { top: 141px; left: 18px; right: 17px; gap: 11px; }
.r-scene-17 .r-sidebar { top: 23px; left: 19px; bottom: 55px; width: 198px; border-radius: 21px; }
.r-scene-17 .r-sidebar .r-brand { left: 25px; }
.r-scene-17 .r-sidebar nav { top: 125px; left: 8px; right: 8px; gap: 4px; }
.r-scene-17 .r-nav-item { height: 58px; padding-left: 21px; font-size: 22px; }
.r-scene-17 .r-nav-separator { margin-top: 17px; margin-bottom: 13px; }
.r-scene-17 .r-nav-item.r-secondary { font-size: 21px; height: 54px; }
.r-scene-18 .r-sidebar { top: 8px; bottom: 8px; width: 229px; border-radius: 22px; background: linear-gradient(150deg, #171411, #241b13); border-color: #342b22; }
.r-scene-18 .r-sidebar nav { top: 159px; left: 17px; right: 18px; gap: 3px; }
.r-scene-18 .r-nav-item { height: 59px; padding-left: 16px; }
.r-scene-18 .r-nav-item.r-selected { height: 57px; margin-bottom: 2px; border-color: transparent; background: linear-gradient(100deg, #986331, #372b2080); box-shadow: none; }
.r-scene-18 .r-nav-item.r-secondary { height: 57px; font-size: 24px; }
.r-scene-18 .r-nav-separator { margin: 19px 11px 18px; }
.r-scene-18 .r-brand-rule { position: absolute; left: 29px; top: 137px; right: 29px; height: 1px; background: #ffffff16; }
.r-scene-18 .r-sidebar .r-brand { top: 38px; gap: 10px; }
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
.r-scene-19 .r-sidebar { top: 20px; left: 20px; width: 205px; bottom: 28px; border-radius: 22px; }
.r-scene-19 .r-sidebar nav { top: 146px; left: 6px; right: 6px; gap: 3px; }
.r-scene-19 .r-nav-item { height: 59px; padding: 0 24px; font-size: 22px; }
.r-scene-19 .r-nav-separator { margin-top: 38px; margin-bottom: 11px; }
.r-scene-19 .r-topbar { left: 244px; padding: 0 30px; border: 1px solid #4b3e2e; border-radius: 20px; background: #33281e7a; top: 20px; height: 71px; }
.r-scene-20 .r-sidebar { top: 10px; bottom: 18px; width: 216px; }
.r-scene-20 .r-sidebar nav { top: 140px; left: 18px; right: 22px; gap: 6px; }
.r-scene-20 .r-nav-item { font-size: 22px; height: 59px; padding-left: 22px; }
.r-scene-21 .r-sidebar { border-radius: 25px 0 0 25px; width: 222px; bottom: 2px; border-color: #2c241b; }
.r-scene-21 .r-sidebar nav { top: 139px; gap: 8px; }
.r-scene-22 .r-sidebar { left: 17px; top: 24px; bottom: 27px; width: 205px; }
.r-scene-22 .r-sidebar nav { left: 15px; right: 15px; top: 130px; gap: 6px; }
.r-scene-22 .r-nav-item { padding: 0 16px; height: 59px; }
.r-scene-23 .r-sidebar { width: 244px; bottom: 0; border-radius: 24px 0 0 24px; }
.r-scene-23 .r-sidebar nav { top: 142px; left: 15px; right: 34px; gap: 10px; }
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
