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
  if (scene.value === 28) return [
    { id: 2, label: '今日', icon: 'house' }, { id: 3, label: '准备', icon: 'file-text' },
    { id: 10, label: '知识', icon: 'book-open' }, { id: 17, label: '训练', icon: 'play' },
    { id: 21, label: '复盘', icon: 'history' }, { id: 24, label: '成长', icon: 'trending-up' },
  ]
  if (scene.value === 25) return [
    { id: 2, label: '今日', icon: 'calendar-days' }, { id: 3, label: '准备', icon: 'square-check-rounded' },
    { id: 10, label: '知识', icon: 'book-open' }, { id: 17, label: '训练', icon: 'play' },
    { id: 21, label: '复盘', icon: 'chart-no-axes-column' }, { id: 24, label: '成长', icon: 'trending-up' },
  ]
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
  if (scene.value === 8) return [
    { id: 2, label: '今日', icon: 'house' }, { id: 3, label: '准备', icon: 'calendar' },
    { id: 10, label: '知识', icon: 'book-open' }, { id: 17, label: '训练', icon: 'dumbbell' },
    { id: 21, label: '复盘', icon: 'chart-no-axes-column' }, { id: 24, label: '成长', icon: 'sprout' },
  ]
  const ordinary = [
    { id: 2, label: '今日', icon: [2, 10, 15].includes(scene.value) ? 'calendar-days' : 'house' },
    { id: 3, label: '准备', icon: [16, 17, 20, 23].includes(scene.value) ? 'clipboard' : scene.value === 14 ? 'briefcase' : [18, 24].includes(scene.value) ? 'square-check-rounded' : [5, 6].includes(scene.value) ? 'file-text' : scene.value === 13 ? 'clipboard' : [3, 7].includes(scene.value) ? 'target' : [11, 19].includes(scene.value) ? 'book-open' : 'play' },
    { id: 10, label: '知识', icon: scene.value === 19 ? 'box' : 'book-open' },
    { id: 17, label: '训练', icon: scene.value === 20 ? 'zap' : scene.value === 16 ? 'layers' : scene.value === 12 ? 'chart-no-axes-column' : scene.value === 18 ? 'circle-play' : [5, 6, 7, 17, 23, 24].includes(scene.value) ? 'play' : 'dumbbell' },
    { id: 21, label: '复盘', icon: scene.value === 2 ? 'review-loop' : [3, 5, 6, 7, 13, 16, 18, 20, 26, 27, 30].includes(scene.value) ? 'chart-no-axes-column' : 'refresh-cw' },
    { id: 24, label: '成长', icon: [5, 6, 30].includes(scene.value) ? 'leaf' : [3, 7, 12, 13, 16, 18, 26, 27].includes(scene.value) ? 'sprout' : 'chart-no-axes-combined' },
  ]
  if (scene.value === 16) [ordinary[2], ordinary[3]] = [ordinary[3]!, ordinary[2]!]
  if (scene.value === 26) ordinary.splice(4, 0, { id: 26, label: '最近访问', icon: 'clock' })
  if (scene.value === 27) ordinary.splice(4, 0, { id: 27, label: 'AI 任务', icon: 'sparkles' })
  return ordinary
})
const secondary = computed(() => [
  { id: 14, label: '题库', icon: scene.value === 15 ? 'file-text' : [11, 26].includes(scene.value) ? 'layout-grid' : [2, 6, 10, 12, 14, 16, 17, 18, 19, 20, 21, 22, 23, 28].includes(scene.value) ? 'database' : 'layers' },
  { id: 16, label: '回忆卡', icon: scene.value === 18 ? 'memory-cards' : 'credit-card' },
  ...(scene.value === 26 ? [] : [{ id: 26, label: '最近访问', icon: 'clock' }]),
  { id: 27, label: 'AI 任务', icon: [11, 26].includes(scene.value) ? 'zap' : scene.value === 27 ? 'settings' : 'sparkles' },
])
// 18的参考图标限定在复现入口，避免改变正式业务共用图标的发布边界。
const followNavPaths: Record<string, string[]> = {
  'circle-play': ['M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0', 'm10 7 6 5-6 5Z'],
  'square-check-rounded': ['M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3Z', 'm7 12 3 3 7-8'],
  'memory-cards': ['M7 3h12a2 2 0 0 1 2 2v12', 'M5 6h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z', 'M7 11h.01', 'M14 15h.01', 'M8 17h.01'],
}
// 14的四个导航轮廓仅在复现入口绘制；共用RIcon保持正式业务的发布边界。
const bankNavPaths: Record<string, string[]> = {
  'refresh-cw': ['M4.6 19.6a9.2 9.2 0 1 0-2.4-3.1', 'm1 20 1.2-3.5 3.5 1.3', 'M12 12a1.4 1.4 0 1 1-2.8 0 1.4 1.4 0 0 1 2.8 0'],
  'chart-no-axes-combined': ['M1.5 15h4v7h-4ZM9 10h4v12H9ZM17 5.5h4V22h-4Z', 'm2 13 6.5-6 5 2 6-6M16 3h4v4'],
  database: ['M20 7c0 4-18 4-18 0s18-4 18 0Z', 'M2 7v6c0 4 18 4 18 0V7M2 15v6c0 4 18 4 18 0v-6'],
  'credit-card': ['M3 4.5h16a2 2 0 0 1 2 2V21a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V6.5a2 2 0 0 1 2-2ZM1 10h20', 'M4 13h.25M7 13h.4M10 13h.25M13 13h.4M16 13h.25M18 13h.3M4 15.5h.3M7 15.5h.25M10 15.5h.5M13 15.5h.25M16 15.5h.4M18 15.5h.25M4 18h.25M7 18h.5M10 18h.25M13 18h.3M16 18h.25M18 18h.4M4 20h.3M7 20h.25M10 20h.3M13 20h.25M16 20h.4M18 20h.25'],
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
function drawTaskHorizon(canvas: HTMLCanvasElement | null) {
  if (!canvas) return
  canvas.width = 254
  canvas.height = 181
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  const rim = (x: number) => 71 + x * .247 + x * x * .00032
  const pixels = ctx.createImageData(254, 181)
  // 自有纹理使用固定整数噪声，不依赖图片切片、时钟、随机数或动画时序。
  for (let y = 0; y < 181; y++) for (let x = 0; x < 254; x++) {
    const depth = y - rim(x)
    const glow = Math.exp(-Math.abs(depth) / 12) * (1 - x / 700)
    let seed = Math.imul(x + 7, 374761393) ^ Math.imul(y + 71, 668265263)
    seed = Math.imul(seed ^ seed >>> 13, 1274126177)
    const grain = ((seed ^ seed >>> 16) >>> 0) / 4294967295
    const bands = Math.sin(x * .06 + depth * .42 + Math.sin(x * .04) * 2) * .5 + .5
    const ground = depth > 0 ? (grain * .55 + bands * .45) * Math.exp(-depth / 44) : 0
    const offset = (y * 254 + x) * 4
    pixels.data[offset] = 24 + glow * 88 + ground * 31
    pixels.data[offset + 1] = 18 + glow * 41 + ground * 17
    pixels.data[offset + 2] = 14 + glow * 19 + ground * 10
    pixels.data[offset + 3] = Math.round(255 * Math.min(1, y / 52))
  }
  ctx.putImageData(pixels, 0, 0)
  ctx.beginPath()
  ctx.moveTo(0, rim(0))
  for (let x = 1; x < 254; x++) ctx.lineTo(x, rim(x))
  const edge = ctx.createLinearGradient(0, 0, 254, 0)
  edge.addColorStop(0, '#ffd3a5')
  edge.addColorStop(.55, '#d58b57')
  edge.addColorStop(1, '#a66a42')
  ctx.strokeStyle = edge
  ctx.lineWidth = 1.1
  ctx.shadowColor = '#f5a363'
  ctx.shadowBlur = 5
  ctx.stroke()
}
onMounted(() => { resize(); document.title = `${page.value.title} · 问阶`; window.addEventListener('resize', resize); window.addEventListener('popstate', popstate); window.addEventListener('keydown', keydown) })
onUnmounted(() => { clearTimeout(toastTimer); window.removeEventListener('resize', resize); window.removeEventListener('popstate', popstate); window.removeEventListener('keydown', keydown) })
</script>
<template>
  <div class="r-stage" :style="{ width: `${1536 * scale}px`, height: `${1024 * scale}px` }">
    <div class="r-canvas" :class="`r-scene-${String(scene).padStart(2, '0')}`" :style="{ '--sidebar': `${sidebar}px`, transform: `scale(${scale})` }" :data-scene="String(scene).padStart(2, '0')">
      <template v-if="![1, 29].includes(scene)">
        <aside class="r-sidebar" :class="{ 'r-sidebar-mini': scene === 9 }">
          <canvas v-if="scene === 7" :ref="element => drawTaskHorizon(element as HTMLCanvasElement | null)" class="r-task-horizon" aria-hidden="true" />
          <a href="replica.html?scene=02" aria-label="问阶今日" @click.prevent="navigate(2)">
            <div v-if="scene === 9" class="r-brand">
              <svg class="r-emblem graph-brand-emblem" viewBox="0 0 100 100" fill="none" aria-hidden="true">
                <circle cx="50" cy="50" r="41" stroke="#d3a65f" stroke-width="1.8" />
                <path d="m30 30 21 15 22-17-17 23 12 20-21-16-19 15 16-23Z" fill="#d6a965" />
                <path d="M50 0C53 34 58 42 100 50C59 54 54 62 50 100C46 62 40 55 0 50C39 45 45 36 50 0Z" fill="#e3b873" />
                <path d="M50 0 51 46 100 50 55 52 50 100 48 55 0 50 44 48Z" fill="#f5d58d" opacity=".5" />
                <path d="m50 40 8 10-8 10-8-10Z" fill="#73542e" />
                <path d="m50 44 3 6-3 6-3-6Z" fill="#e8bf76" />
              </svg>
              <span class="r-brand-word">问阶<small>Interview Studio</small></span>
            </div>
            <div v-else-if="scene === 14 || scene === 15" class="r-brand">
              <svg class="r-emblem" viewBox="0 0 44 55" fill="none" aria-hidden="true">
                <path d="M23 5C26 22 27 24 44 28 27 32 26 35 23 52 20 34 18 32 2 28 19 24 20 22 23 5Z" :fill="scene === 15 ? '#e4ac77' : '#d3ab85'" />
                <path d="M23 5C23 20 24 24 23 28 21 27 18 28 2 28 19 24 20 22 23 5Z" :fill="scene === 15 ? '#f0bb89' : '#e0bd9c'" />
                <path d="M23 28C23 32 23 37 23 52 20 34 18 32 2 28 18 29 20 29 23 28Z" :fill="scene === 15 ? '#d59862' : '#c69c74'" />
                <path d="M23 20C24 25 26 27 31 28 25 29 25 32 23 37 21 32 20 31 14 28 20 27 21 25 23 20Z" fill="#4d4033" />
                <path d="m10.5 17.5 3 1.2.4 2.5-2.6-1.7Zm22.5.2 2.5-.9-.3 2.4-2.5 1.7Zm-21.6 19.5 2.8-1.7-.3 2.7-3.2 1Zm22.1-1.5 1.8 2.8-2.3-.5-1-2.8Z" :fill="scene === 15 ? '#c99a6c' : '#b69675'" />
              </svg>
              <span class="r-brand-word">问阶<small>Interview Studio</small></span>
            </div>
            <div v-else-if="scene === 18" class="r-brand">
              <svg class="r-emblem" viewBox="0 0 100 100" fill="none" aria-hidden="true"><path d="M50 2 61 37 94 50 61 63 50 98 39 63 6 50 39 37Z M50 29 44 44 29 50 44 56 50 72 56 56 71 50 56 44Z" fill="currentColor" fill-rule="evenodd" /></svg>
              <span class="r-brand-word">问阶<small>Interview Studio</small></span>
            </div>
            <RBrand v-else :variant="scene" />
          </a>
          <div v-if="scene === 18" class="r-brand-rule" aria-hidden="true" />
          <nav aria-label="主导航">
            <a v-for="item in nav" :key="item.id" :href="`replica.html?scene=${String(item.id).padStart(2, '0')}`" class="r-nav-item" :class="{ 'r-selected': item.id === active }" @click.prevent="navigate(item.id)">
              <svg v-if="scene === 18 && followNavPaths[item.icon]" class="r-icon" width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path v-for="(d, index) in followNavPaths[item.icon]" :key="index" :d="d" /></svg>
              <svg v-else-if="scene === 14 && bankNavPaths[item.icon]" class="r-icon" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" :stroke-width="item.icon === 'refresh-cw' ? 1.9 : 1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path v-for="(d, index) in bankNavPaths[item.icon]" :key="index" :d="d" :fill="(item.icon === 'refresh-cw' && index === 2) || (item.icon === 'chart-no-axes-combined' && index === 0) ? 'currentColor' : undefined" :stroke-width="item.icon === 'refresh-cw' && index === 2 ? .4 : undefined" /></svg>
              <RIcon v-else-if="[3, 4, 5, 6, 7, 27, 28, 30].includes(scene)" :name="item.icon" :variant="scene === 3 ? 'target' : scene === 4 ? 'facts' : scene === 5 ? 'expression' : scene === 6 ? 'materials' : scene === 7 ? 'tasks' : scene === 30 ? 'inbox' : scene === 27 ? 'authorization' : 'settings'" :size="[4, 5, 6].includes(scene) ? 28 : 30" :stroke-width="scene === 30 ? 1.7 : 1.9" />
              <RIcon v-else :name="item.icon" :variant="scene === 2 ? 'today' : scene === 8 ? 'schedule' : scene === 10 ? 'library' : scene === 11 ? 'note' : scene === 12 ? 'detail' : scene === 14 ? 'bank' : scene === 15 ? 'route' : scene === 16 ? 'recall' : scene === 17 ? 'writing' : scene === 19 ? 'report' : scene === 20 ? 'design' : scene === 21 ? 'review' : scene === 22 ? 'compare' : scene === 23 ? 'recollection' : scene === 24 ? 'growth' : scene === 25 ? 'command' : scene === 26 ? 'recent' : undefined" :size="scene === 9 ? 34 : [2, 8, 16, 18, 19, 21, 22, 23, 24, 25].includes(scene) ? 30 : 28" :stroke-width="[8, 14, 18, 19].includes(scene) ? 2.1 : 1.7" /><span>{{ item.label }}</span>
            </a>
            <template v-if="scene !== 9">
              <div class="r-nav-separator" />
              <span v-if="scene === 15" class="r-tools-heading">工具</span>
              <a v-for="item in secondary" :key="item.id" :href="`replica.html?scene=${String(item.id).padStart(2, '0')}`" class="r-nav-item r-secondary" @click.prevent="navigate(item.id)">
                <svg v-if="scene === 18 && followNavPaths[item.icon]" class="r-icon" width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path v-for="(d, index) in followNavPaths[item.icon]" :key="index" :d="d" /></svg>
                <svg v-else-if="scene === 14 && bankNavPaths[item.icon]" class="r-icon" width="27" height="27" viewBox="0 0 24 24" fill="none" stroke="currentColor" :stroke-width="item.icon === 'credit-card' ? 1.9 : 1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path v-for="(d, index) in bankNavPaths[item.icon]" :key="index" :d="d" :stroke-width="item.icon === 'credit-card' && index === 1 ? .7 : undefined" :stroke-opacity="item.icon === 'credit-card' && index === 1 ? .45 : undefined" /></svg>
                <RIcon v-else-if="[3, 4, 5, 6, 7, 27, 28, 30].includes(scene)" :name="item.icon" :variant="scene === 3 ? 'target' : scene === 4 ? 'facts' : scene === 5 ? 'expression' : scene === 6 ? 'materials' : scene === 7 ? 'tasks' : scene === 30 ? 'inbox' : scene === 27 ? 'authorization' : 'settings'" :size="[4, 5, 6].includes(scene) ? 28 : 29" :stroke-width="scene === 30 ? 1.7 : 1.9" />
                <RIcon v-else :name="item.icon" :variant="scene === 2 ? 'today' : scene === 8 ? 'schedule' : scene === 10 ? 'library' : scene === 11 ? 'note' : scene === 12 ? 'detail' : scene === 14 ? 'bank' : scene === 15 ? 'route' : scene === 16 ? 'recall' : scene === 17 ? 'writing' : scene === 19 ? 'report' : scene === 20 && item.id !== 14 ? 'design' : scene === 21 ? 'review' : scene === 22 ? 'compare' : scene === 23 ? 'recollection' : scene === 24 ? 'growth' : scene === 25 ? 'command' : scene === 26 ? 'recent' : undefined" :size="[2, 8, 18, 21, 23, 24, 25].includes(scene) ? 30 : scene === 22 ? 28 : 27" :stroke-width="[8, 14, 18, 19].includes(scene) ? 2.1 : 1.7" /><span>{{ item.label }}</span>
              </a>
            </template>
          </nav>
          <button class="r-sidebar-avatar" title="设置与数据" @click="navigate(28)"><span class="r-avatar">林</span><RIcon v-if="scene === 16" name="chevron-right" :size="14" /></button>
        </aside>
        <header v-if="scene !== 17" class="r-topbar">
          <button v-if="scene === 30" class="r-breadcrumb r-inbox-breadcrumb" @click="navigate(10)">知识 / 收件箱<RIcon name="chevron-down" :size="23" /></button>
          <div v-else-if="scene !== 2 && scene !== 28" class="r-breadcrumb">
            <span v-if="scene === 6" class="r-material-breadcrumb-icon"><RIcon name="breadcrumb" variant="materials" :size="24" /></span>
            <RIcon v-if="![3, 6, 11, 12, 13, 14, 15, 18, 20, 23, 24, 25].includes(scene)" :name="scene === 8 ? 'chevron-left' : 'house'" :variant="scene === 4 ? 'facts' : scene === 5 ? 'expression' : scene === 7 ? 'tasks' : scene === 16 ? 'recall' : scene === 19 ? 'report' : scene === 26 ? 'recent' : scene === 27 ? 'authorization' : undefined" :size="scene === 7 ? 30 : [5, 27].includes(scene) ? 28 : 25" />
            <template v-if="scene === 20"><span>训练</span><span>/</span><span>系统设计</span><span>/</span><strong>秒杀发奖</strong></template>
            <template v-else-if="scene === 22"><span>复盘</span><span>/</span><span>独立复练</span><span>/</span><strong>新旧对照</strong></template>
            <template v-else-if="scene === 23"><span>复盘</span><span>/</span><strong>面后回忆</strong></template>
            <template v-else-if="scene === 24"><strong>成长</strong><span>/</span><span>Java 后端 · 技术一面</span></template>
            <template v-else-if="scene === 25"><RIcon name="calendar-days" variant="command" :size="28" /><span>今日</span><RIcon name="chevron-right" :size="15" /><span class="r-faint">任务总览</span></template>
            <template v-else-if="scene === 26"><RIcon class="r-recent-back" name="chevron-left" :size="19" /><span class="r-muted">工作台</span><span>/</span><span>最近访问</span></template>
            <template v-else-if="scene === 27"><span>AI</span><span>/</span><span>任务与授权</span></template>
            <template v-else-if="scene === 5"><span class="r-muted">准备</span><span class="r-muted">/</span><span>字节跳动</span><span>/</span><span>表达素材</span></template>
            <template v-else-if="scene === 8"><span>准备</span><span class="r-faint">/</span><span>面试安排</span></template>
            <template v-else>{{ page.breadcrumb }}</template>
          </div>
          <button class="r-top-search" :style="{ left: `${headerBox.left - 20}px`, width: `${headerBox.width}px` }" @click="navigate(25)"><RIcon name="search" :size="25" /><span>{{ searchHint }}</span><kbd v-if="![3, 7, 8, 9, 13, 16, 18, 19, 21, 22, 24, 26].includes(scene)">⌘K</kbd></button>
          <div class="r-top-status">
            <span class="r-saved"><RIcon :name="scene === 26 ? 'bookmark' : [3, 4, 20, 21, 23].includes(scene) ? 'circle-check' : 'check'" :variant="scene === 23 ? 'recollection' : undefined" /><span>已保存<span v-if="scene === 5" class="r-muted"> 10:24</span><span v-if="scene === 11" class="r-muted"> 14:32</span></span></span>
            <button class="r-icon-btn" title="通知" @click="notify('暂无新通知')"><RIcon name="bell" :variant="scene === 2 ? 'today' : scene === 30 ? 'inbox' : scene === 6 ? 'materials' : scene === 7 ? 'tasks' : scene === 8 ? 'schedule' : scene === 10 ? 'library' : scene === 11 ? 'note' : scene === 12 ? 'detail' : scene === 16 ? 'recall' : scene === 19 ? 'report' : scene === 20 ? 'design' : scene === 21 ? 'review' : scene === 22 ? 'compare' : scene === 23 ? 'recollection' : scene === 24 ? 'growth' : scene === 25 ? 'command' : scene === 26 ? 'recent' : undefined" :size="[2, 7, 8, 24, 26, 30].includes(scene) ? 32 : 28" /></button>
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
.r-scene-02 { will-change: transform; background: radial-gradient(ellipse 440px 470px at 0 39%, #72503528, transparent), radial-gradient(ellipse 400px 550px at 100% 70%, #3b2b1a1c, transparent), #0b0907; }
.r-scene-02 .r-sidebar { left: 17px; top: 21px; bottom: 20px; width: 218px; border-radius: 24px; border-color: #6a605340; background: radial-gradient(ellipse 210px 360px at 0 45%, #65503a22, transparent), linear-gradient(115deg, #171513a3, #191714e6); }
.r-scene-02 .r-sidebar > a { position: absolute; left: 14px; top: 25px; width: 183px; height: 54px; }
.r-scene-02 .r-sidebar .r-brand { left: 0; top: 0; gap: 10px; }
.r-scene-02 .r-sidebar :deep(.r-emblem) { width: 45px; height: 54px; flex-shrink: 0; filter: none; }
.r-scene-02 .r-sidebar :deep(.r-brand-word) { font-size: 27px; line-height: 33px; font-weight: 500; }
.r-scene-02 .r-sidebar :deep(.r-brand-word small) { font-size: 16px; line-height: 22px; margin-top: 0; color: #c8c3bc; }
.r-scene-02 .r-sidebar nav { top: 131px; left: 13px; right: 17px; gap: 9px; }
.r-scene-02 .r-nav-item { height: 59px; padding: 0 16px; gap: 16px; font-size: 23px; white-space: nowrap; color: #b8b5af; }
.r-scene-02 .r-nav-item .r-icon { color: #a3a29c; }
.r-scene-02 .r-nav-item.r-selected { color: #fff1df; border-color: #bb885a50; background: linear-gradient(110deg, #bd885bd9, #64452d99 65%, #49332299); box-shadow: inset 1px 0 2px #f0bb8d80, -5px 0 18px #dd965320; }
.r-scene-02 .r-nav-item.r-selected .r-icon { color: #fae4ca; }
.r-scene-02 .r-nav-separator { margin: 26px 13px 24px; background: #b6a1841c; }
.r-scene-02 .r-nav-item.r-secondary { height: 50px; }
.r-scene-02 .r-sidebar-avatar { left: 20px; bottom: 21px; }
.r-scene-02 .r-sidebar-avatar .r-avatar { width: 56px; height: 57px; background: radial-gradient(ellipse at 27% 14%, #a79582, #6e6153 45%, #453b32 72%, #93816c); border-color: #d9c4a340; }
.r-scene-02 .r-topbar { top: 18px; right: 41px; height: 76px; }
.r-scene-02 .r-top-search { top: 10px; height: 55px; gap: 15px; font-size: 21px; border-color: #84766538 !important; background: linear-gradient(110deg, #28242088, #24201c66) !important; }
.r-scene-02 .r-top-search kbd { padding: 0 4px; background: none; font-size: 22px; color: #99948d; }
.r-scene-02 .r-top-status { gap: 29px; }
.r-scene-02 .r-saved { margin-right: 9px; gap: 12px; color: #c0c4b9; }
.r-scene-02 .r-saved svg { background: linear-gradient(130deg, #60c59e, #389a80); color: #123f31; fill: none; padding: 5px; }
.r-scene-02 .r-top-status .r-icon-btn { width: 56px; height: 56px; background: #1a1712b3; color: #e9e2d8; }
.r-scene-02 .r-top-status > .r-avatar { width: 54px; height: 56px; background: radial-gradient(ellipse at 26% 12%, #baaa96, #8c7763 43%, #5b4a3b 73%, #b7a18a); border-color: #cfb89e80; }
.r-scene-03 { will-change: transform; font-weight: 300; background: radial-gradient(ellipse 470px 800px at 0 50%, #33261a22, transparent), radial-gradient(ellipse at 95% 100%, #2b21151a, transparent 55%), #090807; }
.r-scene-03 .r-sidebar { width: 208px; border-radius: 36px; border-color: #24201966; bottom: 30px; background: linear-gradient(110deg, #191613d9, #151310e8 70%, #1b1713); }
.r-scene-03 .r-sidebar > a { position: absolute; left: 33px; top: 33px; width: 160px; height: 56px; }
.r-scene-03 .r-sidebar :deep(.r-brand) { top: 0; left: 0; gap: 10px; }
.r-scene-03 .r-sidebar :deep(.r-emblem) { width: 43px; height: 52px; flex-shrink: 0; filter: drop-shadow(0 0 9px #d99d6640); }
.r-scene-03 .r-sidebar :deep(.r-brand-word) { font-size: 30px; font-weight: 500; line-height: 35px; }
.r-scene-03 .r-sidebar :deep(.r-brand-word small) { font-size: 14px; line-height: 21px; margin-top: 0; }
.r-scene-03 .r-sidebar nav { left: 15px; right: 15px; top: 149px; gap: 9px; }
.r-scene-03 .r-nav-item { height: 60px; padding: 0 0 0 21px; gap: 19px; white-space: nowrap; font-size: 23px; }
.r-scene-03 .r-nav-item.r-selected { height: 64px; border-radius: 22px; border-color: #bd875b8c; background: linear-gradient(110deg, #a7734ca6, #694b34ab); box-shadow: inset 1px 0 3px #ffd0a370, -4px 0 20px #e6a05d33; }
.r-scene-03 .r-nav-separator { margin: 18px 17px 27px; }
.r-scene-03 .r-nav-item.r-secondary { height: 49px; font-size: 23px; }
.r-scene-03 .r-sidebar-avatar { left: 31px; bottom: 17px; }
.r-scene-03 .r-sidebar-avatar .r-avatar { width: 53px; height: 57px; background: linear-gradient(120deg, #7d766d, #504b43); border-color: #a3988740; }
.r-scene-03 .r-topbar { top: 0; left: 257px; right: 34px; height: 100px; }
.r-scene-03 .r-topbar::before { content: ""; position: absolute; z-index: -1; left: -49px; right: -34px; top: 0; bottom: 0; border: 1px solid #322b2250; border-radius: 36px; background: linear-gradient(120deg, #14110e9c, #0c0b0999); pointer-events: none; }
.r-scene-03 .r-breadcrumb { font-size: 21px; }
.r-scene-03 .r-top-search { left: 388px !important; top: 26px; height: 53px; border-color: #6a605038 !important; background: linear-gradient(110deg, #282420ad, #211d19c7) !important; }
.r-scene-03 .r-top-status { gap: 64px; transform: translateY(2px); }
.r-scene-03 .r-saved { color: #8bd9b3; font-size: 21px; }
.r-scene-03 .r-saved svg { padding: 1px; background: none; fill: none; color: #8bd9b3; }
.r-scene-03 .r-top-status > .r-icon-btn { position: relative; }
.r-scene-03 .r-top-status > .r-icon-btn::before { content: ""; position: absolute; left: -31px; top: 6px; height: 33px; width: 1px; background: #a99a8340; }
.r-scene-03 .r-top-status > .r-avatar { width: 47px; height: 52px; font-size: 24px; margin-left: -40px; background: linear-gradient(120deg, #b0a497, #81776d); border-color: #c9b8a74d; }
.r-scene-04 { will-change: transform; font-weight: 300; background: radial-gradient(ellipse 700px 550px at 0 55%, #30271d18, transparent), radial-gradient(ellipse at 95% 100%, #2d20141c, transparent 55%), #0b0907; }
.r-scene-04 .r-sidebar { width: 239px; bottom: 11px; border-radius: 36px; border-color: #302b26; background: linear-gradient(115deg, #181614, #141210 65%, #171411); }
.r-scene-04 .r-sidebar > a { position: absolute; left: 36px; top: 35px; width: 174px; height: 65px; }
.r-scene-04 .r-sidebar .r-brand { top: 0; left: 0; gap: 11px; }
.r-scene-04 .r-sidebar :deep(.r-emblem) { width: 44px; height: 60px; flex-shrink: 0; }
.r-scene-04 .r-sidebar :deep(.r-brand-word) { font-size: 28px; font-weight: 500; line-height: 34px; }
.r-scene-04 .r-sidebar :deep(.r-brand-word small) { font-size: 16px; line-height: 22px; margin-top: 0; }
.r-scene-04 .r-sidebar > a::after { content: ""; position: absolute; top: 96px; left: -4px; width: 175px; height: 1px; background: #aca08f14; }
.r-scene-04 .r-sidebar nav { top: 162px; left: 23px; right: 21px; gap: 6px; }
.r-scene-04 .r-nav-item { height: 64px; padding: 0 23px; gap: 17px; font-size: 24px; white-space: nowrap; color: #c5c1ba; }
.r-scene-04 .r-nav-item :deep(svg) { color: #999993; }
.r-scene-04 .r-nav-item.r-selected { color: #fff3e3; border-radius: 15px; border-color: #c4935b9c; background: linear-gradient(110deg, #9166449c, #50382599); box-shadow: inset 1px 0 2px #edb87c91, -5px 0 17px #d08c3f20; }
.r-scene-04 .r-nav-item.r-selected :deep(svg) { color: #edb382; }
.r-scene-04 .r-nav-separator { margin: 8px 10px 21px; background: #c8b79b17; }
.r-scene-04 .r-nav-item.r-secondary { height: 53px; font-size: 22px; padding-left: 24px; }
.r-scene-04 .r-sidebar-avatar { left: 39px; bottom: 29px; }
.r-scene-04 .r-sidebar-avatar::before { content: ""; position: absolute; left: -7px; top: -40px; width: 175px; height: 1px; background: #c8b79b12; }
.r-scene-04 .r-avatar { background: linear-gradient(120deg, #707b92, #555e70); border-color: #8390a955; }
.r-scene-04 .r-sidebar-avatar .r-avatar { width: 58px; height: 66px; }
.r-scene-04 .r-topbar { left: 267px; top: 17px; right: 32px; height: 75px; }
.r-scene-04 .r-breadcrumb { font-size: 22px; gap: 13px; }
.r-scene-04 .r-top-search { left: 404px !important; height: 61px; background: linear-gradient(110deg, #24211d55, #201b1755) !important; border-color: #55504a80 !important; }
.r-scene-04 .r-top-status { gap: 36px; }
.r-scene-04 .r-saved { gap: 10px; font-size: 21px; color: #8ce2cb; }
.r-scene-04 .r-saved svg { width: 24px; height: 28px; padding: 0; background: none; fill: none; color: #73dbc0; }
.r-scene-04 .r-top-status > .r-avatar { width: 49px; height: 58px; }
.r-scene-05 .r-sidebar { left: 16px; top: 19px; bottom: 30px; width: 228px; }
.r-scene-05 .r-sidebar nav { left: 11px; right: 15px; top: 153px; gap: 4px; }
.r-scene-05 .r-sidebar .r-brand { left: 23px; }
.r-scene-05 .r-topbar { top: 8px; left: 274px; }
.r-scene-05 .r-breadcrumb { font-size: 19px; }
.r-scene-05 { will-change: transform; font-weight: 300; background: radial-gradient(ellipse 420px 900px at 0 0, #4c342d55, transparent 80%), radial-gradient(ellipse 1100px 550px at 30% 100%, #30231b22, transparent), #120f0d; }
.r-scene-05 .r-sidebar { border-radius: 23px; border-color: #50473b99; background: linear-gradient(110deg, #211c1677, #1c18145c); }
.r-scene-05 .r-sidebar > a { position: absolute; left: 23px; top: 27px; width: 178px; height: 60px; }
.r-scene-05 .r-sidebar :deep(.r-brand) { left: 0; top: 0; gap: 11px; }
.r-scene-05 .r-sidebar :deep(.r-emblem) { width: 47px; height: 55px; flex-shrink: 0; }
.r-scene-05 .r-sidebar :deep(.r-brand-word) { font-size: 30px; line-height: 38px; font-weight: 500; }
.r-scene-05 .r-sidebar :deep(.r-brand-word small) { font-size: 16px; line-height: 22px; margin-top: 0; color: #a9a19a; }
.r-scene-05 .r-sidebar > a::after { content: ""; position: absolute; top: 92px; left: 2px; width: 176px; height: 1px; background: #b9a48b26; }
.r-scene-05 .r-sidebar nav { left: 10px; }
.r-scene-05 .r-nav-item { font-size: 22px; gap: 20px; color: #bcb6ae; }
.r-scene-05 .r-nav-item.r-selected { color: #fff3e5; border-radius: 22px; background: linear-gradient(110deg, #a2704bcc, #69472f99); border-color: #d0a17c66; box-shadow: inset 1px 1px 4px #ffd1a488, -4px 0 12px #d19a6260; }
.r-scene-05 .r-nav-separator { margin-top: 22px; margin-left: 14px; margin-right: 13px; margin-bottom: 26px; }
.r-scene-05 .r-nav-item.r-secondary { height: 52px; font-size: 20px; }
.r-scene-05 .r-sidebar-avatar { left: 25px; bottom: 18px; }
.r-scene-05 .r-avatar { background: linear-gradient(120deg, #887c73, #6b625b); border-color: #b9a28c22; font-size: 23px; }
.r-scene-05 .r-sidebar-avatar .r-avatar { width: 49px; height: 52px; }
.r-scene-05 .r-topbar { top: 10px; right: 44px; height: 74px; }
.r-scene-05 .r-topbar::after { content: ""; position: absolute; left: -4px; right: -17px; top: 69px; height: 1px; background: #ffffff06; pointer-events: none; }
.r-scene-05 .r-breadcrumb { gap: 7px; }
.r-scene-05 .r-breadcrumb > svg { margin-right: 4px; }
.r-scene-05 .r-top-search { left: 422px !important; top: 11px; height: 53px; border-color: #675e5266 !important; background: linear-gradient(110deg, #25211de6, #211e1bde) !important; }
.r-scene-05 .r-top-search kbd { background: none; padding-right: 4px; color: #928a80; }
.r-scene-05 .r-top-status { gap: 46px; }
.r-scene-05 .r-saved { font-size: 20px; gap: 9px; }
.r-scene-05 .r-saved svg { width: 27px; height: 27px; }
.r-scene-05 .r-top-status > .r-avatar { width: 41px; height: 44px; font-size: 21px; }
.r-scene-06 { will-change: transform; font-weight: 300; background: radial-gradient(ellipse 500px 650px at 0 20%, #392b2226, transparent), #0b0907; }
.r-scene-06 .r-sidebar { left: 14px; top: 20px; bottom: 44px; width: 219px; border-radius: 20px; border-color: #6b5542; background: linear-gradient(112deg, #25201bd9, #1c1814 60%, #29211b); }
.r-scene-06 .r-sidebar nav { top: 116px; left: 10px; right: 15px; gap: 0; }
.r-scene-06 .r-nav-item { height: 56px; padding-left: 21px; gap: 21px; font-size: 20px; }
.r-scene-06 .r-nav-item.r-selected { font-weight: 500; border-radius: 28px; border-color: #e5ad76; background: radial-gradient(ellipse at 80% 10%, #e6a16f66, transparent 75%), linear-gradient(115deg, #825333, #98633f); box-shadow: inset 0 1px 6px #ffdfb987, 0 0 16px #d78b433d; }
.r-scene-06 .r-nav-separator { margin-block: 14px; }
.r-scene-06 .r-sidebar > a { position: absolute; left: 20px; top: 29px; width: 174px; height: 55px; }
.r-scene-06 .r-sidebar :deep(.r-brand) { position: static; }
.r-scene-06 .r-sidebar :deep(.r-brand-word) { font-size: 20px; line-height: 26px; font-weight: 500; }
.r-scene-06 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; line-height: 20px; color: #eee6dd; }
.r-scene-06 .r-sidebar-avatar { left: 21px; bottom: 21px; }
.r-scene-06 .r-sidebar-avatar .r-avatar { width: 47px; height: 48px; font-size: 24px; background: linear-gradient(120deg, #6b5545, #40362e); }
.r-scene-06 .r-sidebar-avatar::before { content: ""; position: absolute; left: 0; top: -21px; width: 170px; height: 1px; background: #8b73552e; }
.r-scene-06 .r-topbar { top: 21px; left: 254px; right: 16px; height: 65px; padding: 0 20px; border: 1px solid #51433455; border-radius: 20px; background: linear-gradient(110deg, #241c1666, #211b15b3); }
.r-scene-06 .r-breadcrumb { gap: 18px; font-size: 19px; }
.r-scene-06 .r-material-breadcrumb-icon { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 50%; background: #6254473d; color: #c9c1b7; }
.r-scene-06 .r-top-search { left: 405px !important; top: 7px; height: 50px; padding-inline: 17px; font-size: 18px; border-radius: 20px; border-color: #96775b88 !important; background: linear-gradient(110deg, #30271f99, #28211b99) !important; box-shadow: inset 0 1px 6px #ffffff08; }
.r-scene-06 .r-top-search kbd { padding: 0 7px; background: none; }
.r-scene-06 .r-top-status { gap: 42px; }
.r-scene-06 .r-saved { gap: 14px; color: #6dba80; font-size: 20px; }
.r-scene-06 .r-saved svg { width: 27px; height: 28px; padding: 5px; background: linear-gradient(120deg, #397c3f, #315e35); fill: none; color: #97d292; }
.r-scene-06 .r-top-status .r-icon-btn { transform: translateX(6px); }
.r-scene-06 .r-top-status > .r-avatar { position: relative; width: 42px; height: 44px; font-size: 23px; border-color: #78645344; background: linear-gradient(125deg, #6b5746, #44372f); }
.r-scene-06 .r-top-status > .r-avatar::before { content: ""; position: absolute; left: -23px; top: 15px; width: 1px; height: 13px; background: #8b735535; }
.r-scene-07 { will-change: transform; background: radial-gradient(ellipse 540px 700px at 0 50%, #32221833, transparent), #080706; }
.r-scene-07 .r-sidebar { width: 256px; top: 22px; bottom: 7px; overflow: hidden; border-color: #55453766; background: linear-gradient(115deg, #211b16bb, #1b1814 65%, #241c16); }
.r-scene-07 .r-task-horizon { position: absolute; left: 0; bottom: 0; width: 254px; height: 181px; pointer-events: none; }
.r-scene-07 .r-sidebar > a { position: absolute; left: 34px; top: 30px; width: 185px; height: 62px; display: flex; align-items: center; }
.r-scene-07 .r-sidebar :deep(.r-brand) { position: static; gap: 10px; }
.r-scene-07 .r-sidebar :deep(.r-emblem) { width: 54px; height: 60px; filter: none; flex-shrink: 0; }
.r-scene-07 .r-sidebar :deep(.r-brand-word) { font-size: 28px; line-height: 35px; font-weight: 500; }
.r-scene-07 .r-sidebar :deep(.r-brand-word small) { font-size: 16px; line-height: 24px; margin-top: 0; color: #aaa29a; }
.r-scene-07 .r-sidebar nav { top: 144px; left: 22px; right: 18px; gap: 10px; }
.r-scene-07 .r-nav-item { height: 56px; padding-left: 23px; gap: 20px; font-size: 23px; white-space: nowrap; color: #c7c1b9; }
.r-scene-07 .r-nav-item.r-selected { border-radius: 18px; color: #fff1dd; font-weight: 600; border-color: #d5a075a8; background: linear-gradient(110deg, #b77e55a8, #714e37bf); box-shadow: inset 1px 1px 5px #ffcd9b8c, -4px 0 15px #e29d6044; }
.r-scene-07 .r-nav-separator { margin: 18px 15px 17px; background: #a38a6d26; }
.r-scene-07 .r-nav-item.r-secondary { height: 52px; margin-top: -1px; font-size: 22px; }
.r-scene-07 .r-sidebar-avatar { left: 37px; bottom: 25px; }
.r-scene-07 .r-sidebar-avatar::before { content: ""; position: absolute; top: -69px; left: -1px; width: 184px; height: 1px; background: #a38a6d1a; }
.r-scene-07 .r-avatar { color: #20160e; border-color: #ead2b744; background: linear-gradient(120deg, #ead2b8, #c5a58b); }
.r-scene-07 .r-sidebar-avatar .r-avatar { width: 47px; height: 48px; font-size: 24px; }
.r-scene-07 .r-topbar { top: 25px; right: 36px; height: 70px; }
.r-scene-07 .r-breadcrumb { font-size: 22px; gap: 15px; }
.r-scene-07 .r-top-search { top: 6px; height: 50px; border-radius: 22px; border-color: #4a40341a !important; background: linear-gradient(110deg, #24211ee6, #211e1bd9) !important; padding-left: 17px; gap: 9px; }
.r-scene-07 .r-top-status { gap: 47px; }
.r-scene-07 .r-saved { color: #81dbc5; }
.r-scene-07 .r-saved svg { width: 26px; height: 27px; padding: 0; color: #70dbc0; fill: none; background: none; }
.r-scene-07 .r-top-status > .r-avatar { width: 49px; height: 50px; font-size: 24px; }
.r-scene-07 .r-top-status .r-icon-btn { color: #e0dad2; }
.r-scene-08 .r-sidebar nav { top: 124px; left: 24px; right: 24px; }
.r-scene-08 .r-nav-item { height: 60px; padding-left: 20px; }
.r-scene-08 { will-change: transform; background: radial-gradient(ellipse at 0 78%, #36291d26, transparent 45%), radial-gradient(ellipse at 96% 100%, #2d1b1014, transparent 60%), #0b0907; }
.r-scene-08::before { content: ""; position: absolute; inset: 0; border: 1px solid #59453138; border-radius: 26px; pointer-events: none; }
.r-scene-08 .r-sidebar { width: 235px; bottom: 4px; border-color: #42372e66; border-radius: 26px; background: linear-gradient(110deg, #201b16de, #171410e8 65%, #211b17); }
.r-scene-08 .r-sidebar > a { position: absolute; left: 34px; top: 35px; width: 177px; height: 55px; display: flex; align-items: center; }
.r-scene-08 .r-sidebar :deep(.r-brand) { position: static; gap: 10px; }
.r-scene-08 .r-sidebar :deep(.r-emblem) { width: 46px; height: 51px; flex-shrink: 0; filter: none; }
.r-scene-08 .r-sidebar :deep(.r-brand-word) { font-size: 30px; line-height: 36px; font-weight: 500; }
.r-scene-08 .r-sidebar :deep(.r-brand-word small) { font-size: 15px; line-height: 20px; margin-top: 0; color: #bbb4ad; }
.r-scene-08 .r-sidebar nav { top: 121px; left: 24px; right: 24px; }
.r-scene-08 .r-nav-item { gap: 18px; padding-left: 18px; color: #b8b1a8; white-space: nowrap; }
.r-scene-08 .r-nav-item.r-selected { height: 62px; margin-bottom: -2px; font-size: 23px; color: #f8c18f; border-radius: 22px; border-color: #ab79465c; background: linear-gradient(110deg, #8a5c37aa, #543b28a6); box-shadow: inset 1px 0 2px #ffce9dc7, -5px 0 16px #dc914638; }
.r-scene-08 .r-nav-separator { margin: 12px 7px 18px; background: #a9957830; }
.r-scene-08 .r-nav-item.r-secondary { height: 56px; font-size: 23px; }
.r-scene-08 .r-sidebar-avatar { left: 39px; bottom: 35px; }
.r-scene-08 .r-sidebar-avatar .r-avatar { width: 62px; height: 64px; font-size: 28px; background: linear-gradient(120deg, #94746c, #67524f); border-color: #b28e8166; }
.r-scene-08 .r-topbar { top: 17px; left: 258px; right: 30px; height: 73px; }
.r-scene-08 .r-topbar::after { content: ""; position: absolute; left: -23px; top: 84px; right: -30px; height: 1px; background: #a3917a0b; pointer-events: none; }
.r-scene-08 .r-breadcrumb { gap: 12px; font-size: 22px; }
.r-scene-08 .r-breadcrumb > svg { margin-right: -7px; }
.r-scene-08 .r-top-search { left: 571px !important; top: 11px; height: 51px; gap: 10px; padding: 0 17px; background: linear-gradient(110deg, #322b24bf, #29241ee0) !important; border-color: #5b50456b !important; }
.r-scene-08 .r-top-status { gap: 64px; }
.r-scene-08 .r-saved { color: #cdc5bc; font-size: 22px; gap: 12px; }
.r-scene-08 .r-saved svg { width: 27px; height: 27px; padding: 5px; color: #06341b; fill: none; background: linear-gradient(115deg, #68c88b, #3c985c); }
.r-scene-08 .r-top-status > .r-avatar { width: 51px; height: 53px; font-size: 24px; margin-left: -18px; background: linear-gradient(120deg, #997b74, #715b58); border-color: #ab918955; }
.r-scene-08 .r-top-status .r-icon-btn { color: #f2e9de; }
.r-scene-09 { will-change: transform; font-weight: 300; }
.r-scene-09 .r-sidebar { top: 92px; bottom: 1px; width: 117px; }
.r-scene-09 .r-sidebar .r-brand { position: fixed; left: 30px; top: 23px; gap: 10px; }
.r-scene-09 .graph-brand-emblem { transform: translateX(3px); filter: drop-shadow(0 0 2px #e4b87133); }
.r-scene-09 .r-sidebar :deep(.r-brand-word) { font-size: 26px; line-height: 31px; font-weight: 500; }
.r-scene-09 .r-sidebar nav { top: 40px; left: 13px; right: 13px; gap: 9px; }
.r-scene-09 .r-nav-item { height: 91px; flex-direction: column; justify-content: center; gap: 3px; padding: 0; font-size: 21px; border-radius: 18px; }
.r-scene-09 .r-nav-item.r-selected { height: 83px; margin-bottom: 8px; background: linear-gradient(120deg,#a77a36,#80541f); }
.r-scene-09::before { content: ""; position: absolute; left: 0; top: 0; width: 248px; height: 93px; border: 1px solid #5d503233; border-radius: 26px 0 0; pointer-events: none; }
.r-scene-09::after { content: ""; position: absolute; left: 248px; right: 0; top: 92px; border-top: 1px solid #5d503226; pointer-events: none; }
.r-scene-09 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 16px; line-height: 22px; }
.r-scene-09 .r-topbar { left: 280px; top: 14px; right: 36px; height: 70px; }
.r-scene-09 .r-top-search { left: 336px !important; top: 11px; height: 49px; font-size: 18px; }
.r-scene-09 .r-breadcrumb { font-size: 19px; gap: 22px; }
.r-scene-09 .r-top-status { gap: 21px; }
.r-scene-09 .r-saved { margin-right: 23px; }
.r-scene-09 .r-saved svg { color: #fff7e7; fill: none; background: linear-gradient(130deg, #71be8b, #398557); }
.r-scene-09 .r-top-status > .r-avatar { width: 49px; height: 51px; font-size: 24px; background: linear-gradient(120deg, #776956, #4e4438); }
.r-scene-09 .r-top-status > .r-icon-btn { position: relative; }
.r-scene-09 .r-top-status > .r-icon-btn::before { content: ""; position: absolute; left: -22px; top: 9px; width: 1px; height: 26px; background: #8e7c6066; }
.r-scene-09 .r-sidebar-avatar { left: 32px; bottom: 37px; }
.r-scene-09 .r-sidebar-avatar .r-avatar { width: 54px; height: 56px; font-size: 24px; }
.r-scene-10 { font-weight: 400; }
.r-scene-10 .r-sidebar { left: 7px; width: 213px; top: 21px; bottom: 34px; border-radius: 20px; background: linear-gradient(115deg, #2b241b90, #1b1814ba); }
.r-scene-10 .r-sidebar nav { top: 113px; left: 14px; right: 14px; gap: 5px; }
.r-scene-10 .r-nav-item { height: 59px; padding: 0 18px; font-size: 22px; }
.r-scene-10 .r-nav-item.r-selected { background: linear-gradient(120deg, #a2653188, #50321fa6); border-color: #ae7b4a77; box-shadow: inset 1px 1px 7px #f7c49212, -3px 0 15px #e49c550b; }
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
.r-scene-11 { font-weight: 300; background: linear-gradient(110deg, #0e0a07, #060503 48%, #0a0705); will-change: transform; }
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
.r-scene-12 { font-weight: 300; background: radial-gradient(ellipse 250px 650px at 12% 55%, #2c241a33, transparent), linear-gradient(115deg, #0e0c0b, #0b0907 72%, #0e0c0a); will-change: transform; }
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
.r-scene-14 .r-sidebar :deep(.r-brand) { left: 28px; top: 40px; gap: 13px; }
.r-scene-14 .r-sidebar :deep(.r-emblem) { width: 44px; height: 55px; color: #dcb184; filter: none; }
.r-scene-14 .r-sidebar :deep(.r-brand-word) { font-weight: 500; }
.r-scene-14 .r-sidebar :deep(.r-brand-word small) { font-size: 16px; line-height: 20px; margin-top: 4px; font-weight: 400; color: #918982; }
.r-scene-14 .r-sidebar nav { top: 140px; left: 22px; right: 20px; gap: 4px; }
.r-scene-14 .r-nav-item { height: 55px; padding-left: 17px; font-size: 20px; font-weight: 300; color: #efe8e1; }
.r-scene-14 .r-nav-item span { position: relative; left: -2px; }
.r-scene-14 .r-nav-item.r-secondary { font-size: 20px; }
.r-scene-14 .r-nav-item.r-secondary span { left: -1px; }
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
.r-scene-15 .r-sidebar :deep(.r-brand) { left: 20px; top: 27px; gap: 15px; }
.r-scene-15 .r-sidebar :deep(.r-emblem) { width: 35px; height: 43px; color: #dfa366; transform: translateY(-5px); filter: drop-shadow(0 0 7px #e4a15c24); }
.r-scene-15 .r-sidebar :deep(.r-brand-word) { position: relative; top: -3px; font-size: 26px; font-weight: 500; line-height: 30px; }
.r-scene-15 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 21px; }
.r-scene-15 .r-sidebar nav { top: 115px; left: -1px; right: 6px; gap: 12px; }
.r-scene-15 .r-nav-item { padding-left: 23px; font-size: 22px; font-weight: 300; gap: 20px; color: #d4cec9; }
.r-scene-15 .r-nav-item.r-selected { height: 65px; margin-top: -4px; margin-bottom: -5px; border-radius: 15px; background: linear-gradient(105deg, #bc85549c, #785035b0); box-shadow: inset 1px 1px 8px #efba8630, -3px 0 15px #e49c5533; }
.r-scene-15 .r-nav-separator { margin: 3px 21px 0; }
.r-scene-15 .r-tools-heading { color: #8f8780; font-size: 17px; line-height: 20px; margin: 8px 21px 0; }
.r-scene-15 .r-nav-item.r-secondary { height: 48px; font-size: 20px; color: #bdb6af; }
.r-scene-15 .r-sidebar-avatar { left: 20px; bottom: 21px; }
.r-scene-15 .r-sidebar-avatar .r-avatar { width: 47px; height: 50px; font-size: 23px; }
.r-scene-15 .r-avatar { background: linear-gradient(130deg, #a59e98, #857f7a); border-color: #b0a7a03b; }
.r-scene-15 .r-topbar { top: 12px; left: 244px; right: 36px; height: 75px; }
.r-scene-15 .r-breadcrumb { white-space: pre; word-spacing: 4px; }
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
.r-scene-16 .r-sidebar :deep(.r-brand-word) { font-size: 28px; line-height: 33px; font-weight: 500; }
.r-scene-16 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 18px; }
.r-scene-16 .r-sidebar::after { content: ""; position: absolute; top: 105px; left: 30px; right: 29px; height: 1px; background: #ffffff06; }
.r-scene-16 .r-sidebar nav { top: 138px; left: 17px; right: 16px; gap: 13px; }
.r-scene-16 .r-nav-item { height: 60px; padding: 0 18px; gap: 18px; font-weight: 400; }
.r-scene-16 .r-nav-item.r-selected { border-radius: 16px; background: radial-gradient(ellipse at 14% 55%, #d69b6699, transparent 55%), linear-gradient(110deg, #5e3c26, #2d2119); border-color: #74533477; box-shadow: inset 1px 0 4px #f5be7c55, -4px 0 12px #e8974330; }
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
.r-scene-17 .r-sidebar { top: 23px; left: 19px; bottom: 55px; width: 198px; border-radius: 21px; background: radial-gradient(ellipse 140px 700px at 0 85%, #6b432b22, transparent 85%), linear-gradient(110deg, #211c19, #1b1715 80%, #201b17); border-color: #55493f; }
.r-scene-17 .r-sidebar > a { position: absolute; left: 26px; top: 30px; }
.r-scene-17 .r-sidebar :deep(.r-brand) { position: static; gap: 12px; }
.r-scene-17 .r-sidebar :deep(.r-emblem) { width: 44px; height: 48px; color: #bd8956; filter: none; }
.r-scene-17 .r-sidebar :deep(.r-brand-word) { font-size: 22px; line-height: 26px; font-weight: 500; transform: translateY(5px); }
.r-scene-17 .r-sidebar :deep(.r-brand-word small) { font-size: 12px; line-height: 18px; margin-top: 0; }
.r-scene-17 .r-sidebar nav { top: 123px; left: 6px; right: 6px; gap: 4px; }
.r-scene-17 .r-nav-item { height: 58px; padding-left: 21px; font-size: 20px; line-height: 33px; gap: 21px; }
.r-scene-17 .r-nav-item span { transform: translateY(-1px); }
.r-scene-17 .r-nav-item.r-selected { background: radial-gradient(ellipse at 50% 80%, #c88b5377, transparent), linear-gradient(120deg, #98653f, #5f412b); border-color: #d79961; box-shadow: inset 0 0 7px #efb57966, 0 0 17px #c98d5022; }
.r-scene-17 .r-nav-separator { margin: 12px 8px 13px; background: #74645755; }
.r-scene-17 .r-nav-item.r-secondary { font-size: 20px; height: 55px; }
.r-scene-17 .r-sidebar-avatar { left: 22px; bottom: 18px; }
.r-scene-17 .r-sidebar-avatar .r-avatar { width: 45px; height: 47px; font-size: 24px; background: linear-gradient(120deg, #806f61, #5e5044); }
.r-scene-17 .r-sidebar-avatar::before { content: ""; position: absolute; left: -7px; top: -25px; width: 166px; height: 1px; background: #7c69564d; }
.r-scene-18 { will-change: transform; font-weight: 300; }
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
.r-scene-19 .r-sidebar { top: 20px; left: 20px; width: 205px; bottom: 28px; border-radius: 22px; background: radial-gradient(ellipse 260px 440px at 0 80%, #85563944, transparent 85%), linear-gradient(120deg, #1d1814, #17120f 75%, #2b211b); border-color: #564638; }
.r-scene-19 .r-sidebar > a { position: absolute; left: 22px; top: 31px; }
.r-scene-19 .r-sidebar :deep(.r-brand) { position: static; gap: 11px; }
.r-scene-19 .r-sidebar :deep(.r-emblem) { width: 43px; height: 50px; color: #d59055; filter: none; }
.r-scene-19 .r-sidebar :deep(.r-brand-word) { font-size: 24px; line-height: 30px; font-weight: 500; }
.r-scene-19 .r-sidebar :deep(.r-brand-word small) { font-size: 14px; line-height: 20px; margin-top: 0; color: #e2d8cc; }
.r-scene-19 .r-sidebar::after { content: ""; position: absolute; top: 118px; left: 20px; right: 20px; height: 1px; background: #c9a27a22; }
.r-scene-19 .r-sidebar nav { top: 146px; left: 6px; right: 6px; gap: 4px; }
.r-scene-19 .r-nav-item { height: 59px; padding: 0 24px; font-size: 21px; font-weight: 400; line-height: 33px; gap: 21px; }
.r-scene-19 .r-nav-item.r-selected { height: 56px; margin-block: 1px 2px; background: radial-gradient(ellipse at 2% 30%, #f3bd8666, transparent 85%), radial-gradient(ellipse at 96% 70%, #16100d99, transparent 80%), linear-gradient(120deg, #9e673f, #704223); box-shadow: inset 1px 1px 7px #ffd7ac55, 0 0 10px #ebad6c22; border-color: #dfa166; border-radius: 30px; }
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
.r-scene-19 .r-saved { color: #b1b0a1; font-size: 20px; gap: 10px; }
.r-scene-19 .r-saved svg { width: 24px; height: 24px; background: #79b26d; fill: #79b26d; color: #203e20; }
.r-scene-20 .r-sidebar { top: 10px; bottom: 18px; width: 216px; }
.r-scene-20 { will-change: transform; background: radial-gradient(ellipse at 0 67%, #2d211820, transparent 37%), #070504; }
.r-scene-20 .r-sidebar { border-color: #302922; border-radius: 24px; background: radial-gradient(ellipse at 0 27%, #65462c44, transparent 75%), linear-gradient(115deg, #161313, #11100f); }
.r-scene-20 .r-sidebar > a { position: absolute; left: 30px; top: 29px; }
.r-scene-20 .r-sidebar :deep(.r-brand) { position: static; gap: 12px; }
.r-scene-20 .r-sidebar :deep(.r-emblem) { width: 42px; height: 50px; color: #d89559; filter: none; }
.r-scene-20 .r-sidebar :deep(.r-brand-word) { font-size: 27px; line-height: 32px; font-weight: 500; transform: translateY(2px); }
.r-scene-20 .r-sidebar :deep(.r-brand-word small) { font-size: 14px; line-height: 20px; margin-top: 3px; color: #9f9690; }
.r-scene-20 .r-sidebar::after { content: ""; position: absolute; left: 30px; right: 29px; top: 109px; height: 1px; background: #a9876730; }
.r-scene-20 .r-sidebar nav { top: 131px; left: 18px; right: 22px; gap: 8px; }
.r-scene-20 .r-nav-item { font-size: 21px; line-height: 33px; font-weight: 400; height: 59px; padding-left: 21px; gap: 13px; white-space: nowrap; }
.r-scene-20 .r-nav-item.r-selected { height: 56px; margin-bottom: 3px; border-radius: 16px; font-weight: 500; -webkit-text-stroke: .2px currentColor; background: radial-gradient(ellipse at 0 40%, #c38b5660, transparent 90%), linear-gradient(110deg, #765535, #503924); border-color: #c28c56; box-shadow: inset 1px 1px 3px #f7c49226, -3px 0 15px #e49c5516; }
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
.r-scene-21 { will-change: transform; font-weight: 300; }
.r-scene-21 .r-sidebar { border-radius: 25px 0 0 25px; width: 221px; bottom: 2px; border-color: #2c241b; background: radial-gradient(ellipse at 60% 72%, #61534230, transparent 75%), linear-gradient(110deg, #13100c, #100e0b); }
.r-scene-21 .r-sidebar > a { position: absolute; left: 29px; top: 35px; }
.r-scene-21 .r-sidebar :deep(.r-brand) { position: static; gap: 13px; }
.r-scene-21 .r-sidebar :deep(.r-emblem) { width: 45px; height: 52px; filter: none; }
.r-scene-21 .r-sidebar :deep(.r-brand-word) { font-size: 25px; font-weight: 500; line-height: 30px; }
.r-scene-21 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 20px; }
.r-scene-21 .r-sidebar nav { top: 138px; left: 21px; right: 6px; gap: 8px; }
.r-scene-21 .r-nav-item { height: 60px; padding-left: 18px; gap: 17px; font-size: 23px; line-height: 36px; font-weight: 400; }
.r-scene-21 .r-nav-item.r-selected { height: 62px; background: radial-gradient(ellipse at 0 50%, #c78c5260, transparent 100%), linear-gradient(115deg, #6c482d, #352519); border-color: #bb875b; border-radius: 18px; box-shadow: inset 1px 1px 4px #f7c49220, -3px 0 15px #e49c5516; font-weight: 500; -webkit-text-stroke: .2px currentColor; }
.r-scene-21 .r-nav-separator { margin: 24px 27px 29px 17px; }
.r-scene-21 .r-nav-item.r-secondary { height: 52px; font-size: 23px; }
.r-scene-21 .r-sidebar-avatar { left: 38px; bottom: 39px; }
.r-scene-21 .r-sidebar-avatar .r-avatar { width: 63px; height: 67px; font-size: 30px; background: linear-gradient(130deg, #746a5f, #514a42); }
.r-scene-21 .r-sidebar-avatar::before { content: ""; position: absolute; left: 0; top: -39px; width: 148px; height: 1px; background: #b59d7c22; }
.r-scene-21 .r-sidebar-avatar::after { content: ""; position: absolute; left: 135px; top: 31px; width: 6px; height: 6px; background: #655848; transform: rotate(45deg); }
.r-scene-21 .r-topbar { left: 252px; top: 22px; right: 31px; }
.r-scene-21 .r-breadcrumb { font-size: 21px; gap: 15px; word-spacing: 2.5px; }
.r-scene-21 .r-top-search { left: 515px !important; top: 9px; height: 57px; }
.r-scene-21 .r-top-status { gap: 37px; }
.r-scene-21 .r-saved { color: #36d77d; }
.r-scene-21 .r-saved svg { width: 26px; height: 26px; padding: 0; fill: none; background: transparent; color: #36d77d; }
.r-scene-21 .r-top-status > .r-avatar { width: 53px; height: 57px; background: linear-gradient(130deg, #787168, #504b44); }
.r-scene-22 { will-change: transform; font-weight: 300; background: #0b0a09; }
.r-scene-22 .r-sidebar { left: 17px; top: 24px; bottom: 27px; width: 205px; background: linear-gradient(115deg, #1e1a1690, #231f1aba); }
.r-scene-22 .r-sidebar > a { position: absolute; left: 20px; top: 24px; }
.r-scene-22 .r-sidebar :deep(.r-brand) { position: static; gap: 10px; }
.r-scene-22 .r-sidebar :deep(.r-emblem) { width: 49px; height: 56px; color: #e8a56b; filter: drop-shadow(0 0 8px #dfa06a55); }
.r-scene-22 .r-sidebar :deep(.r-brand-word) { font-size: 30px; font-weight: 500; line-height: 36px; }
.r-scene-22 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 20px; }
.r-scene-22 .r-sidebar::after { content: ""; position: absolute; left: 26px; right: 25px; top: 107px; height: 1px; background: #b59d7c22; }
.r-scene-22 .r-sidebar nav { left: 15px; right: 15px; top: 131px; gap: 4px; }
.r-scene-22 .r-nav-item { padding: 0 15px; height: 60px; gap: 14px; white-space: nowrap; font-size: 23px; -webkit-text-stroke: .04px currentColor; }
.r-scene-22 .r-nav-item.r-selected { height: 62px; background: linear-gradient(120deg, #ae7d4e99, #5c3d26aa); border-color: #bf8b5b77; font-weight: 500; -webkit-text-stroke: .1px currentColor; }
.r-scene-22 .r-nav-item.r-secondary { height: 52px; margin-bottom: 6px; }
.r-scene-22 .r-nav-separator { margin: 24px 11px 22px; }
.r-scene-22 .r-sidebar-avatar { left: 22px; bottom: 24px; }
.r-scene-22 .r-sidebar-avatar .r-avatar { width: 57px; height: 61px; background: linear-gradient(130deg, #bd8e5d, #82532c); font-size: 27px; }
.r-scene-22 .r-topbar { left: 252px; top: 27px; right: 43px; height: 73px; }
.r-scene-22 .r-breadcrumb { font-size: 20px; gap: 12px; color: #bbb7af; font-weight: 300; }
.r-scene-22 .r-breadcrumb > .r-icon { margin-right: 4px; }
.r-scene-22 .r-breadcrumb strong { margin-left: -3px; font-weight: 500; color: #f4f0e9; }
.r-scene-22 .r-top-search { left: 384px !important; top: 7px; height: 59px; background: linear-gradient(110deg, #26262199, #1c1c1988) !important; border-color: #625f5866 !important; }
.r-scene-22 .r-top-status { gap: 18px; }
.r-scene-22 .r-saved { width: 141px; height: 59px; justify-content: center; gap: 11px; border-radius: 32px; background: linear-gradient(110deg, #2a2924, #171614); font-size: 22px; color: #98e6af; }
.r-scene-22 .r-saved svg { width: 30px; height: 30px; background: #6bde85; fill: #6bde85; color: #172e19; padding: 6px; }
.r-scene-22 .r-top-status .r-icon-btn { width: 74px; height: 59px; border-radius: 31px; background: linear-gradient(110deg, #23221e, #151410); color: #f4f1ea; }
.r-scene-22 .r-top-status > .r-avatar { width: 53px; height: 59px; margin-left: 8px; background: linear-gradient(130deg, #bb8654, #8b582d); }
.r-scene-23 { will-change: transform; }
.r-scene-23 .r-sidebar { width: 244px; bottom: 0; border-radius: 0 0 0 24px; background: radial-gradient(ellipse 200px 400px at 0 68%, #704d3b75, transparent), radial-gradient(ellipse at 0 0, #45312655, transparent 42%), linear-gradient(150deg, #161210, #1b1714 52%, #2a201b); border-color: #332a24; }
.r-scene-23 .r-sidebar > a { position: absolute; left: 31px; top: 34px; }
.r-scene-23 .r-sidebar :deep(.r-brand) { position: static; gap: 11px; }
.r-scene-23 .r-sidebar :deep(.r-emblem) { width: 47px; height: 54px; }
.r-scene-23 .r-sidebar :deep(.r-brand-word) { font-size: 28px; font-weight: 500; line-height: 32px; }
.r-scene-23 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 22px; }
.r-scene-23 .r-sidebar nav { top: 142px; left: 15px; right: 36px; gap: 10px; }
.r-scene-23 .r-nav-item { height: 60px; padding-left: 21px; gap: 15px; white-space: nowrap; }
.r-scene-23 .r-nav-item.r-selected { height: 62px; margin-top: -2px; border-radius: 32px; border-color: #e8b180; background: linear-gradient(110deg, #9a6b4699, #68472fd9); }
.r-scene-23 .r-nav-item.r-secondary { height: 53px; font-size: 23px; }
.r-scene-23 .r-nav-separator { margin: 16px 0 20px; }
.r-scene-23 .r-sidebar-avatar { left: 32px; bottom: 34px; }
.r-scene-23 .r-sidebar-avatar .r-avatar { width: 62px; height: 62px; font-size: 27px; }
.r-scene-23 .r-sidebar-avatar::before { content: ""; position: absolute; left: 0; top: -39px; width: 174px; height: 1px; background: #ffffff12; }
.r-scene-23 .r-avatar { background: linear-gradient(130deg, #82716a, #665c57); }
.r-scene-23 .r-topbar { left: 278px; right: 31px; top: 18px; height: 74px; }
.r-scene-23 .r-breadcrumb { font-size: 22px; gap: 14px; font-weight: 400; }
.r-scene-23 .r-breadcrumb strong { font-weight: 500; }
.r-scene-23 .r-top-search { left: 317px !important; top: 9px; height: 56px; gap: 14px; padding-right: 13px; background: linear-gradient(110deg, #231e1bcc, #201b18a6) !important; border-color: #62584e88 !important; }
.r-scene-23 .r-top-search kbd { border: 1px solid #ffffff14; border-radius: 19px; padding: 2px 12px; background: #ffffff04; }
.r-scene-23 .r-top-status { gap: 38px; }
.r-scene-23 .r-saved { color: #c0bbb4; font-size: 22px; gap: 12px; }
.r-scene-23 .r-saved svg { background: transparent; fill: none; color: #70cb88; padding: 0; }
.r-scene-23 .r-top-status > .r-avatar { width: 56px; height: 56px; font-size: 25px; }
.r-scene-24 { background: radial-gradient(ellipse 340px 360px at 0 0, #483d334d, transparent), radial-gradient(ellipse at 35% 0, #19150e55, transparent 45%), #080704; will-change: transform; font-weight: 300; border-radius: 34px; box-shadow: inset 0 0 0 1px #39332760; }
.r-scene-24 .r-sidebar { width: 208px; top: 106px; bottom: 0; border-color: #28211a; background: linear-gradient(120deg, #1a1611, #151210); }
.r-scene-24 .r-sidebar > a { position: absolute; left: 34px; top: -74px; }
.r-scene-24 .r-sidebar :deep(.r-brand) { position: static; gap: 12px; }
.r-scene-24 .r-sidebar :deep(.r-emblem) { width: 43px; height: 53px; color: #c5a88c; filter: none; }
.r-scene-24 .r-sidebar :deep(.r-brand-word) { font-size: 28px; font-weight: 500; line-height: 32px; }
.r-scene-24 .r-sidebar :deep(.r-brand-word small) { font-size: 14px; margin-top: 0; line-height: 22px; }
.r-scene-24 .r-sidebar nav { top: 34px; left: 20px; right: 19px; gap: 7px; }
.r-scene-24 .r-nav-item { padding-left: 23px; color: #c9bdac; gap: 16px; font-size: 21.5px; line-height: 34.5px; font-weight: 400; white-space: nowrap; }
.r-scene-24 .r-nav-item:not(.r-secondary) > span { display: inline-block; transform-origin: 0 50%; transform: translate(1px, -1px) scaleX(.94); }
.r-scene-24 .r-nav-item.r-selected { border-radius: 32px; color: #fff1dd; background: radial-gradient(ellipse at 58% 76%, #b67c473b, transparent 78%), linear-gradient(110deg, #945c2db5, #654321bd); border-color: #a96c38; box-shadow: inset 0 1px 3px #f2bf8a30, -3px 0 15px #e49c5526; }
.r-scene-24 .r-nav-item.r-secondary { height: 58px; padding-right: 0; padding-left: 21px; font-size: 21px; line-height: 33px; white-space: nowrap; }
.r-scene-24 .r-nav-item.r-secondary > span { display: inline-block; transform: translate(2px, -1px); }
.r-scene-24 .r-nav-separator { margin: 33px 13px 20px; }
.r-scene-24 .r-sidebar-avatar { left: 35px; bottom: 36px; }
.r-scene-24 .r-sidebar-avatar .r-avatar { width: 61px; height: 61px; font-size: 25px; background: linear-gradient(130deg, #62513e, #3f372c); }
.r-scene-24 .r-sidebar-avatar::before { content: ""; position: absolute; top: -40px; left: 0; width: 140px; height: 1px; background: #ffffff10; }
.r-scene-24 .r-topbar { left: 260px; right: 37px; top: 22px; height: 76px; }
.r-scene-24 .r-topbar::before { content: ""; position: absolute; z-index: -1; left: -29px; right: -37px; top: -3px; bottom: -3px; border: 1px solid #211c1659; border-radius: 30px; background: linear-gradient(115deg, #14141166, #0d0c0b88); pointer-events: none; }
.r-scene-24 .r-breadcrumb { font-size: 20px; gap: 16px; }
.r-scene-24 .r-breadcrumb strong { font-weight: 500; }
.r-scene-24 .r-top-search { left: 455px !important; top: 10px; height: 56px; background: linear-gradient(110deg, #25231f, #1d1c19) !important; border-color: #6f685366 !important; }
.r-scene-24 .r-top-status { gap: 36px; }
.r-scene-24 .r-saved { margin-right: 40px; font-size: 22px; }
.r-scene-24 .r-saved svg { width: 23px; height: 23px; padding: 5px; color: #edfff3; background: linear-gradient(120deg, #6bc9aa, #49a58c); fill: none; }
.r-scene-24 .r-top-status .r-icon-btn { position: relative; }
.r-scene-24 .r-top-status .r-icon-btn::before { content: ""; position: absolute; left: -39px; top: 6px; height: 32px; width: 1px; background: #77716899; }
.r-scene-24 .r-top-status > .r-avatar { width: 53px; height: 55px; font-size: 25px; background: linear-gradient(120deg, #ad775d, #7b4e39); }
.r-scene-25 { will-change: transform; }
.r-scene-25::before { content: ""; position: absolute; top: 0; left: 212px; right: 0; height: 94px; background: linear-gradient(110deg, #1c181466, #15110e33); border-bottom: 1px solid #5c493222; pointer-events: none; }
.r-scene-25 .r-sidebar { border-radius: 0; width: 212px; bottom: 0; border-width: 0 1px 0 0; border-color: #302821; background: linear-gradient(180deg, #171411, #14110f 120px, #201a16 310px, #1b1614 600px, #1e1916 850px, #1d1815); }
.r-scene-25 .r-sidebar > a::after { content: ""; position: absolute; left: 0; right: 0; top: 120px; height: 1px; background: #ffffff09; }
.r-scene-25 .r-sidebar :deep(.r-brand) { left: 34px; top: 40px; gap: 11px; }
.r-scene-25 .r-sidebar :deep(.r-emblem) { width: 45px; height: 53px; color: #d6c7b5; filter: none; }
.r-scene-25 .r-sidebar :deep(.r-brand-word) { font-size: 28px; font-weight: 500; line-height: 32px; }
.r-scene-25 .r-sidebar :deep(.r-brand-word small) { font-size: 14px; line-height: 20px; margin-top: 0; color: #a49c94; }
.r-scene-25 .r-sidebar nav { left: 24px; right: 20px; top: 153px; gap: 9px; }
.r-scene-25 .r-nav-item { padding-left: 19px; padding-right: 0; gap: 17px; color: #c6bfb6; font-size: 21.5px; font-weight: 400; }
.r-scene-25 .r-nav-item:not(.r-secondary) > span { display: inline-block; transform-origin: 0 50%; transform: translate(1px, -1px) scaleX(.925); }
.r-scene-25 .r-nav-item.r-selected { color: #fff1dc; background: radial-gradient(ellipse 42px 95px at 101% 67%, #ddb38342, transparent), radial-gradient(ellipse 80px 68px at 0 62%, #eac39f26, transparent), linear-gradient(110deg, #745037, #593d2b 60%, #493427); border-color: #bd8c66; box-shadow: inset 1px 1px 7px #f7c49233, 0 0 18px #eeb88350; }
.r-scene-25 .r-nav-item.r-secondary { height: 51px; font-size: 21px; white-space: nowrap; }
.r-scene-25 .r-nav-item.r-secondary > span { display: inline-block; transform-origin: 0 50%; transform: translateY(-2px) scaleX(.945); }
.r-scene-25 .r-nav-separator { margin: 24px 8px 23px; }
.r-scene-25 .r-sidebar-avatar { left: 45px; bottom: 37px; }
.r-scene-25 .r-sidebar-avatar .r-avatar { width: 57px; height: 59px; font-size: 26px; background: linear-gradient(125deg, #74695e, #51483f); }
.r-scene-25 .r-topbar { left: 253px; right: 36px; top: 21px; height: 55px; }
.r-scene-25 .r-breadcrumb { gap: 9px; font-size: 20px; color: #d3cdc6; }
.r-scene-25 .r-breadcrumb > svg:first-child { margin-right: 3px; }
.r-scene-25 .r-breadcrumb > svg:nth-child(3) { width: 11px; height: 15px; }
.r-scene-25 .r-breadcrumb > span:last-child { position: relative; left: -1px; font-size: 19px; transform: translate(1px, -1px); }
.r-scene-25 .r-top-search { left: 356px !important; top: 0; height: 55px; padding: 0 17px; }
.r-scene-25 .r-top-status { gap: 26px; }
.r-scene-25 .r-saved { margin-right: 14px; color: #73cbb1; font-size: 22px; }
.r-scene-25 .r-saved svg { width: 25px; height: 25px; background: linear-gradient(120deg, #38b89b, #238c79); fill: none; color: #b1e1d4; padding: 6px; }
.r-scene-25 .r-top-status .r-icon-btn { position: relative; }
.r-scene-25 .r-top-status .r-icon-btn::before { content: ""; position: absolute; left: -21px; top: 7px; height: 30px; width: 1px; background: #ffffff16; }
.r-scene-25 .r-top-status > .r-avatar { height: 53px; width: 49px; font-size: 25px; background: linear-gradient(125deg, #665c54, #49433d); }
.r-scene-26 { will-change: transform; font-weight: 300; background: radial-gradient(ellipse at 0 63%, #402a193b, transparent 35%), #0b0a09; }
.r-scene-26::before { content: ""; position: absolute; left: 21px; right: 21px; top: 14px; height: 94px; border: 1px solid #5e514233; border-radius: 23px; background: linear-gradient(110deg, #10101044, #1e17113d); pointer-events: none; z-index: 1; }
.r-scene-26 .r-sidebar { left: 21px; top: 130px; bottom: 27px; width: 201px; border-radius: 22px; z-index: 2; background: radial-gradient(ellipse at 0 65%, #3a281c70, transparent 80%), linear-gradient(115deg, #191818de, #181411ed); border-color: #62584b55; }
.r-scene-26 .r-sidebar > a { position: fixed; top: 32px; left: 41px; width: 199px; height: 57px; display: flex; align-items: center; }
.r-scene-26 .r-sidebar :deep(.r-brand) { position: static; gap: 15px; }
.r-scene-26 .r-sidebar :deep(.r-emblem) { width: 56px; height: 57px; color: #eabc92; filter: none; }
.r-scene-26 .r-sidebar :deep(.r-brand-word) { font-size: 28px; font-weight: 500; line-height: 34px; }
.r-scene-26 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 21px; color: #f3efed; }
.r-scene-26 .r-sidebar nav { top: 14px; left: 11px; right: 12px; gap: 9px; }
.r-scene-26 .r-nav-item { padding: 0 0 0 15px; height: 59px; font-size: 22px; gap: 20px; white-space: nowrap; color: #d2cfca; }
.r-scene-26 .r-nav-item.r-selected { margin-top: -5px; border-color: #c593632b; background: linear-gradient(110deg, #996b4399, #503522bb); box-shadow: inset 1px 0 2px #edb17a8a, -5px 0 17px #d09b4a20; }
.r-scene-26 .r-nav-separator { margin: 14px 13px 18px; background: #b69a7230; }
.r-scene-26 .r-nav-item.r-secondary { height: 57px; }
.r-scene-26 .r-sidebar-avatar { left: 19px; bottom: 18px; }
.r-scene-26 .r-sidebar-avatar .r-avatar { width: 49px; height: 51px; background: linear-gradient(120deg, #efbd89, #b97843); font-size: 24px; }
.r-scene-26 .r-topbar { left: 301px; top: 31px; right: 42px; height: 58px; }
.r-scene-26 .r-breadcrumb { gap: 17px; }
.r-scene-26 .r-recent-back { position: absolute; left: -37px; color: #28211b; }
.r-scene-26 .r-top-search { left: 361px !important; top: 0; height: 58px; background: linear-gradient(110deg, #282625a6, #352b24b3) !important; border-color: #685b4c99 !important; }
.r-scene-26 .r-top-status { gap: 50px; }
.r-scene-26 .r-saved { gap: 12px; color: #f4f0ec; font-size: 21px; }
.r-scene-26 .r-saved svg { background: transparent; fill: none; padding: 0; color: #ddd8d1; width: 28px; height: 28px; border-radius: 0; }
.r-scene-26 .r-top-status > .r-avatar { width: 52px; height: 54px; background: linear-gradient(120deg, #5474da, #3c50b2); border-color: #7184da66; font-size: 25px; }
.r-scene-27 { will-change: transform; background: radial-gradient(ellipse at 33% 0, #29201830, transparent 62%), radial-gradient(ellipse at 100% 100%, #40231129, transparent 65%), #0b0907; }
.r-scene-27::before { content: ""; position: absolute; inset: 0 0 0 216px; border: 1px solid #30261c55; border-radius: 26px; pointer-events: none; }
.r-scene-27 .r-sidebar { width: 216px; bottom: 0; border-color: #30281f66; background: radial-gradient(ellipse at 0 55%, #392a1b3b, transparent 80%), linear-gradient(115deg, #15120fde, #100e0be8); }
.r-scene-27 .r-sidebar > a { position: absolute; left: 27px; top: 43px; width: 166px; height: 54px; display: flex; align-items: center; }
.r-scene-27 .r-sidebar :deep(.r-brand) { position: static; gap: 10px; }
.r-scene-27 .r-sidebar :deep(.r-emblem) { width: 50px; height: 52px; filter: none; }
.r-scene-27 .r-sidebar :deep(.r-brand-word) { font-size: 29px; font-weight: 500; line-height: 33px; color: #faf9f7; }
.r-scene-27 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 21px; color: #c6c3bf; }
.r-scene-27 .r-sidebar nav { top: 139px; left: 20px; right: 20px; gap: 5px; }
.r-scene-27 .r-nav-item { height: 60px; padding: 0 0 0 15px; gap: 16px; font-size: 21px; color: #c1bcb5; white-space: nowrap; }
.r-scene-27 .r-nav-item.r-selected { height: 56px; margin-top: 3px; margin-bottom: 1px; border-radius: 15px; border-color: #b281574f; color: #fff1dc; background: linear-gradient(110deg, #ac734b99, #503420aa); box-shadow: inset 1px 0 1px #e4a67999, -3px 0 15px #e49c5516; }
.r-scene-27 .r-nav-item.r-secondary { height: 54px; font-size: 21px; }
.r-scene-27 .r-nav-item.r-secondary:last-child { margin-top: -3px; }
.r-scene-27 .r-nav-separator { margin: 20px 7px 22px; background: #b3a08a21; }
.r-scene-27 .r-sidebar-avatar { left: 35px; bottom: 38px; }
.r-scene-27 .r-sidebar-avatar::before { content: ""; position: absolute; left: -7px; top: -33px; width: 159px; height: 1px; background: #b3a08a21; }
.r-scene-27 .r-sidebar-avatar .r-avatar { width: 52px; height: 54px; font-size: 24px; border-color: #656b8c44; background: linear-gradient(115deg, #505b83, #35384f); }
.r-scene-27 .r-topbar { left: 248px; right: 31px; top: 18px; height: 74px; }
.r-scene-27 .r-topbar::before { content: ""; position: absolute; z-index: -1; left: -31px; right: -31px; top: -6px; height: 82px; border-radius: 26px 26px 0 0; background: linear-gradient(110deg, #1b181440, #1b181488); }
.r-scene-27 .r-breadcrumb { gap: 12px; color: #bdb9b4; }
.r-scene-27 .r-breadcrumb .r-icon { margin-right: 6px; }
.r-scene-27 .r-top-search { left: 465px !important; top: 9px; width: 449px !important; height: 55px; background: linear-gradient(110deg, #27231fe6, #231f1ab3) !important; border-color: #4c453a77 !important; }
.r-scene-27 .r-top-status { gap: 29px; }
.r-scene-27 .r-saved { margin-right: 19px; color: #52d475; }
.r-scene-27 .r-saved svg { width: 27px; height: 27px; background: #54d675; fill: none; color: #0b3a18; padding: 5px; }
.r-scene-27 .r-top-status > .r-avatar { position: relative; width: 51px; height: 54px; font-size: 24px; border-color: #467ebc55; background: linear-gradient(120deg, #3d7db8, #21517d); }
.r-scene-27 .r-top-status > .r-avatar::after { content: ""; position: absolute; width: 9px; height: 13px; right: 1px; bottom: 0; border-radius: 50%; background: #eab47b; filter: blur(3px); z-index: -1; }
.r-scene-28 { will-change: transform; background: radial-gradient(ellipse 420px 440px at 0 0, #523b2559, transparent), radial-gradient(ellipse at 0 100%, #33271d29, transparent 65%), #090705; }
.r-scene-28 .r-sidebar { left: 19px; top: 20px; width: 212px; bottom: 28px; border-color: #70563e99; background: radial-gradient(ellipse at 0 0, #50382525, transparent 65%), radial-gradient(ellipse at 100% 100%, #6a392722, transparent 75%), linear-gradient(115deg, #211b16, #211913 60%, #211a16); }
.r-scene-28 .r-sidebar::before { content: ""; position: absolute; left: 0; right: 0; top: 91px; height: 1px; background: #a78d6e38; }
.r-scene-28 .r-sidebar > a { position: absolute; left: 22px; top: 20px; width: 166px; height: 49px; display: flex; align-items: center; }
.r-scene-28 .r-sidebar :deep(.r-brand) { position: static; gap: 11px; }
.r-scene-28 .r-sidebar :deep(.r-emblem) { width: 43px; height: 47px; filter: none; }
.r-scene-28 .r-sidebar :deep(.r-brand-word) { font-size: 24px; font-weight: 600; line-height: 29px; color: #f6f4f0; }
.r-scene-28 .r-sidebar :deep(.r-brand-word small) { margin-top: 0; font-size: 14px; line-height: 20px; color: #c6c1bc; }
.r-scene-28 .r-sidebar nav { left: 20px; right: 15px; top: 116px; gap: 3px; }
.r-scene-28 .r-nav-item { height: 59px; padding-left: 9px; gap: 22px; font-size: 21px; font-weight: 300; color: #ccc7c2; white-space: nowrap; }
.r-scene-28 .r-nav-item.r-secondary { height: 57px; font-size: 20px; }
.r-scene-28 .r-nav-separator { margin: 14px 5px 22px 0; background: #baa58b38; }
.r-scene-28 .r-sidebar-avatar { left: 28px; bottom: 31px; }
.r-scene-28 .r-sidebar-avatar::before { content: ""; position: absolute; left: -7px; top: -39px; width: 169px; height: 1px; background: #baa58b2d; }
.r-scene-28 .r-avatar { width: 53px; height: 54px; font-size: 25px; border-color: #af988244; background: linear-gradient(115deg, #aa917c, #776151); }
.r-scene-28 .r-topbar { left: 264px; right: 20px; height: 75px; top: 20px; padding-right: 18px; border: 1px solid #55453777; background: linear-gradient(110deg, #221a144d, #211b16a6); border-radius: 25px; }
.r-scene-28 .r-top-search { left: 275px !important; top: 11px; width: 456px !important; height: 52px; font-size: 19px; padding: 0 17px; color: #bab3ad; background: linear-gradient(110deg, #32292085, #28221d99) !important; border-color: #bca28866 !important; }
.r-scene-28 .r-top-status { gap: 18px; }
.r-scene-28 .r-saved { margin-right: 10px; gap: 13px; color: #4bd68d; font-size: 20px; }
.r-scene-28 .r-saved svg { width: 22px; height: 22px; color: #0b3923; background: #4bc98c; fill: none; padding: 4px; }
.r-scene-28 .r-top-status .r-icon-btn { width: 58px; height: 52px; background: #ffffff04; }
.r-scene-28 .r-top-status > .r-avatar { width: 50px; height: 52px; }
.r-scene-30 { will-change: transform; font-weight: 300; background: radial-gradient(ellipse 310px 290px at 0 0, #533a284d, transparent), radial-gradient(ellipse at 100% 100%, #39241318, transparent 60%), #080604; }
.r-scene-30::after { content: ""; position: absolute; inset: 0; border: 1px solid #5d4b362b; border-radius: 24px; pointer-events: none; }
.r-scene-30 .r-sidebar { top: 27px; left: 8px; width: 219px; bottom: 25px; border-radius: 20px; border-color: #73583e8c; background: radial-gradient(ellipse 260px 300px at 0 0, #79533833, transparent), linear-gradient(130deg, #201a16, #1b1613 64%, #211a14); }
.r-scene-30 .r-sidebar .r-brand { left: 27px; top: 29px; gap: 8px; }
.r-scene-30 .r-sidebar :deep(.r-emblem) { width: 62px; height: 62px; }
.r-scene-30 .r-sidebar :deep(.r-brand-word) { position: relative; top: 4px; font-size: 34px; font-weight: 500; line-height: 41px; }
.r-scene-30 .r-sidebar :deep(.r-brand-word small) { position: relative; left: -24px; font-size: 16px; margin-top: 0; line-height: 22px; color: #ded6ce; }
.r-scene-30 .r-sidebar nav { top: 141px; left: 20px; right: 22px; gap: 9px; }
.r-scene-30 .r-nav-item { height: 59px; padding: 0 18px; gap: 13px; font-size: 22px; font-weight: 300; color: #e3ded7; white-space: nowrap; }
.r-scene-30 .r-nav-item.r-selected { height: 65px; margin-top: -2px; margin-bottom: -2px; border-radius: 15px; background: radial-gradient(ellipse at 0 70%, #e0ad7440, transparent 90%), linear-gradient(110deg, #96704665, #6d4e3580); border-color: #bb8e62b3; box-shadow: inset 1px 1px 8px #edbb8122, -5px 0 18px #dc9f5a1c; }
.r-scene-30 .r-nav-separator { margin: 18px 17px 15px; background: #a68b6e2b; }
.r-scene-30 .r-nav-item.r-secondary { height: 48px; font-size: 22px; gap: 13px; white-space: nowrap; }
.r-scene-30 .r-sidebar-avatar { left: 29px; bottom: 20px; }
.r-scene-30 .r-sidebar-avatar .r-avatar { width: 57px; height: 61px; font-size: 27px; background: linear-gradient(120deg, #7c6b5b, #5c4d40); }
.r-scene-30 .r-topbar { top: 25px; right: 30px; }
.r-scene-30 .r-top-search { height: 62px; background: linear-gradient(110deg, #302e29a3, #29251f99) !important; }
.r-scene-30 .r-top-status { gap: 29px; }
.r-scene-30 .r-saved { font-size: 22px; color: #c5e9c5; gap: 12px; }
.r-scene-30 .r-saved svg { color: #16321a; fill: none; background: linear-gradient(120deg, #a8e3a5, #79cb89); padding: 5px; height: 28px; }
.r-scene-30 .r-top-status .r-icon-btn { color: #f4f0e9; }
.r-scene-30 .r-top-status > .r-avatar { width: 57px; height: 62px; background: linear-gradient(130deg, #746a5f, #514a40); }
.r-inbox-breadcrumb { height: 62px; width: 336px; margin-left: -6px; padding: 0 12px !important; justify-content: space-between; border: 1px solid #332a20 !important; border-radius: 20px; font-size: 23px; }
</style>
