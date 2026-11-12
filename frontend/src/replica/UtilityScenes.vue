<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import RIcon from './RIcon.vue'

const props = defineProps<{ scene: number }>()
const emit = defineEmits<{ navigate: [scene: number]; notify: [message: string] }>()
const horizonCanvas = ref<HTMLCanvasElement | null>(null)

const recentCategories = [
  { name: '全部', count: 18, icon: '' },
  { name: '知识', count: 6, icon: 'book-open' },
  { name: '训练', count: 4, icon: 'dumbbell' },
  { name: '复盘', count: 3, icon: 'refresh-cw' },
  { name: '准备', count: 3, icon: 'square' },
  { name: '面试', count: 2, icon: 'video' },
]
const recentObjects = [
  { title: '缓存一致性', type: '概念', category: '知识', state: '当前版本 v4', time: '2分钟前', icon: 'layers', color: 'blue', typeColor: 'blue', stateColor: 'teal', scene: 12 },
  { title: '缓存删除失败以后', type: '独立复练', category: '复盘', state: '待复盘', time: '18分钟前', icon: 'refresh-cw', color: 'purple', typeColor: 'purple', stateColor: 'amber', scene: 21 },
  { title: '字节跳动 · 技术一面', type: '面试安排', category: '面试', state: '11月12日', time: '今天 09:20', icon: 'calendar-days', color: 'indigo', typeColor: 'cyan', stateColor: 'neutral', scene: 8 },
  { title: '营销交易平台-项目复盘.pdf', type: '资料', category: '准备', state: '已解析', time: '昨天 18:40', icon: 'file-text', color: 'red', typeColor: 'neutral', stateColor: 'green', scene: 6 },
  { title: '系统设计取舍', type: '知识笔记', category: '知识', state: 'v2', time: '11.09', icon: 'file-text', color: 'blue', typeColor: 'indigo', stateColor: 'neutral', scene: 11 },
]
const recentCategory = ref('全部')
const recentType = ref('全部类型')
const currentOnly = ref(true)
const selectedRecent = ref(0)
const recentCleared = ref(false)
const recentMenuOpen = ref(false)
const currentObject = computed(() => recentObjects[selectedRecent.value] ?? recentObjects[0]!)
const visibleObjects = computed(() => recentCleared.value ? [] : recentObjects
  .map((item, index) => ({ ...item, index }))
  .filter((item) => (recentCategory.value === '全部' || item.category === recentCategory.value)
    && (recentType.value === '全部类型' || item.type === recentType.value)))

function selectRecentCategory(name: string) {
  recentCategory.value = name
  const first = visibleObjects.value[0]
  if (first) selectedRecent.value = first.index
}
function selectRecentType(type: string) {
  recentType.value = type
  recentMenuOpen.value = false
  const first = visibleObjects.value[0]
  if (first) selectedRecent.value = first.index
}
function clearRecent() {
  recentCleared.value = true
  emit('notify', '已清除本次页面中的访问历史')
}

const taskItems = [
  { name: '知识整理', subject: '缓存一致性', status: '处理中', color: 'amber', time: '今天 20:18' },
  { name: '出题候选', subject: '算法与并发', status: '成功', color: 'green', time: '今天 19:47' },
  { name: '来源问答', subject: 'Spring 源码', status: '失败', color: 'red', time: '今天 19:32' },
  { name: '关系候选', subject: '微服务架构', status: '费用待核对', color: 'blue', time: '' },
]
const selectedTask = ref(0)
const taskFilter = ref('全部状态')
const taskMenuOpen = ref(false)
const continuousAuthorization = ref(false)
const taskCancelled = ref(false)
const sendConfirmed = ref(false)
const currentTask = computed(() => taskItems[selectedTask.value] ?? taskItems[0]!)
const currentTaskStatus = computed(() => selectedTask.value === 0
  ? taskCancelled.value ? '已取消' : sendConfirmed.value ? '已确认发送' : currentTask.value.status
  : currentTask.value.status)
const visibleTasks = computed(() => taskItems.map((item, index) => ({ ...item, index }))
  .filter((item) => taskFilter.value === '全部状态' || item.status === taskFilter.value))

function chooseTaskFilter(status: string) {
  taskFilter.value = status
  taskMenuOpen.value = false
  const first = visibleTasks.value[0]
  if (first) selectedTask.value = first.index
}
function cancelTask() {
  taskCancelled.value = true
  sendConfirmed.value = false
  emit('notify', '本次页面中的任务已取消')
}
function confirmSend() {
  sendConfirmed.value = true
  emit('notify', '已记录本次发送确认，仅保存在当前页面')
}

const settingSections = [
  { name: '外观与数据', icon: 'sun', target: 'appearance' },
  { name: '阅读与写作', icon: 'book-open', target: 'font' },
  { name: '动效', icon: 'zap', target: 'motion' },
  { name: '时区', icon: 'clock', target: 'timezone' },
  { name: '保存与恢复', icon: 'rotate-ccw', target: 'save' },
  { name: '导入 / 导出', icon: 'upload', target: 'export' },
  { name: '数据用量', icon: 'chart-no-axes-column', target: 'usage' },
  { name: '安全', icon: 'shield-check', target: 'security' },
]
const settings = reactive({ theme: '深色', width: '舒适', font: '15', motion: '完整', reducedMotion: false, timezone: 'Asia/Shanghai', saveInterval: '2 分钟' })
const selectedSetting = ref('外观与数据')
const settingFocus = ref('')
const timezoneOpen = ref(false)
const saveIntervalOpen = ref(false)
const dataOverview = [
  { name: '笔记', value: '18', icon: 'file-text', color: '#80d2eb' },
  { name: '概念', value: '42', icon: 'lightbulb', color: '#ffe429' },
  { name: '关系', value: '68', icon: 'share-2', color: '#4fe1d8' },
  { name: '附件', value: '2.4 GB', icon: 'paperclip', color: '#a5e8c9' },
]

function selectSetting(name: string, target: string) {
  if (target === 'security') {
    emit('notify', '安全设置的详细状态尚待补充参考')
    return
  }
  selectedSetting.value = name
  settingFocus.value = target
}
function exportData(format: string) {
  emit('notify', `已选择${format}，当前复刻页面不生成或导出数据`)
}

// 原图未提供独立行星素材。用固定种子的 Canvas 绘制地平线，保持 DPR 1 初态稳定。
function drawHorizon() {
  const canvas = horizonCanvas.value
  if (!canvas || props.scene !== 26) return
  canvas.width = 1312
  canvas.height = 1024
  const context = canvas.getContext('2d')
  if (!context) return
  const ctx = context
  let seed = 260112
  const random = () => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0
    return seed / 4294967296
  }
  const planetPath = () => {
    ctx.beginPath()
    ctx.moveTo(-100, 263)
    ctx.bezierCurveTo(320, 174, 731, 111, 1007, 135)
    ctx.bezierCurveTo(1135, 145, 1255, 179, 1415, 244)
  }
  ctx.clearRect(0, 0, 1312, 1024)
  const atmosphere = ctx.createRadialGradient(750, 156, 20, 750, 156, 620)
  atmosphere.addColorStop(0, '#4f779636')
  atmosphere.addColorStop(0.65, '#1e4b7020')
  atmosphere.addColorStop(1, '#16314900')
  ctx.fillStyle = atmosphere
  ctx.fillRect(0, 100, 1312, 180)
  ctx.save()
  planetPath()
  ctx.lineTo(1415, 365)
  ctx.lineTo(-100, 365)
  ctx.closePath()
  ctx.clip()
  const surface = ctx.createLinearGradient(0, 140, 0, 325)
  surface.addColorStop(0, '#42444a')
  surface.addColorStop(0.28, '#1c2730')
  surface.addColorStop(0.62, '#101317dd')
  surface.addColorStop(1, '#0b0a0800')
  ctx.fillStyle = surface
  ctx.fillRect(0, 125, 1312, 240)
  for (let i = 0; i < 5500; i++) {
    const x = random() * 1312
    const y = 134 + random() * 180
    const opacity = Math.max(0, (1 - (y - 134) / 180) * random() * 0.19)
    ctx.strokeStyle = `rgba(${122 + Math.floor(random() * 55)},${132 + Math.floor(random() * 40)},151,${opacity})`
    ctx.lineWidth = 0.3 + random() * 1.7
    ctx.beginPath()
    ctx.moveTo(x, y)
    ctx.lineTo(x + random() * 28 + 2, y - random() * 4)
    ctx.stroke()
  }
  const terminator = ctx.createLinearGradient(750, 0, 1312, 0)
  terminator.addColorStop(0, '#9b694700')
  terminator.addColorStop(1, '#b7814a42')
  ctx.fillStyle = terminator
  ctx.fillRect(700, 130, 612, 170)
  ctx.restore()
  const rim = ctx.createLinearGradient(0, 0, 1312, 0)
  rim.addColorStop(0, '#3e6a8655')
  rim.addColorStop(0.4, '#7395b7bb')
  rim.addColorStop(0.73, '#ffe0c9')
  rim.addColorStop(1, '#df9660a0')
  ctx.save()
  planetPath()
  ctx.strokeStyle = rim
  ctx.lineWidth = 2
  ctx.shadowColor = '#e8b48d'
  ctx.shadowBlur = 11
  ctx.stroke()
  ctx.restore()
  const sunlight = ctx.createRadialGradient(1006, 135, 0, 1006, 135, 82)
  sunlight.addColorStop(0, '#fff5dd')
  sunlight.addColorStop(0.06, '#fff1d2eb')
  sunlight.addColorStop(0.2, '#ffd3a78a')
  sunlight.addColorStop(1, '#c7884500')
  ctx.fillStyle = sunlight
  ctx.fillRect(923, 53, 166, 166)
  ctx.save()
  ctx.translate(1006, 135)
  for (let i = 0; i < 8; i++) {
    ctx.rotate(Math.PI / 4)
    const beam = ctx.createLinearGradient(0, 0, 0, 52)
    beam.addColorStop(0, '#ffeed77d')
    beam.addColorStop(1, '#ffe6c000')
    ctx.fillStyle = beam
    ctx.beginPath()
    ctx.moveTo(-2, 0)
    ctx.lineTo(0, 55)
    ctx.lineTo(2, 0)
    ctx.fill()
  }
  ctx.restore()
  ctx.beginPath()
  ctx.moveTo(-80, 1070)
  ctx.bezierCurveTo(500, 930, 940, 904, 1400, 1040)
  ctx.lineTo(1400, 1100)
  ctx.lineTo(-80, 1100)
  ctx.closePath()
  const lower = ctx.createLinearGradient(0, 948, 0, 1040)
  lower.addColorStop(0, '#75553e55')
  lower.addColorStop(0.16, '#2c201777')
  lower.addColorStop(1, '#090807')
  ctx.fillStyle = lower
  ctx.fill()
  ctx.beginPath()
  ctx.moveTo(-80, 1070)
  ctx.bezierCurveTo(500, 930, 940, 904, 1400, 1040)
  ctx.strokeStyle = '#d4a07336'
  ctx.lineWidth = 1.5
  ctx.stroke()
}

watch(() => props.scene, async () => {
  recentCategory.value = '全部'
  recentType.value = '全部类型'
  currentOnly.value = true
  selectedRecent.value = 0
  recentCleared.value = false
  recentMenuOpen.value = false
  selectedTask.value = 0
  taskFilter.value = '全部状态'
  taskMenuOpen.value = false
  continuousAuthorization.value = false
  taskCancelled.value = false
  sendConfirmed.value = false
  Object.assign(settings, { theme: '深色', width: '舒适', font: '15', motion: '完整', reducedMotion: false, timezone: 'Asia/Shanghai', saveInterval: '2 分钟' })
  selectedSetting.value = '外观与数据'
  settingFocus.value = ''
  timezoneOpen.value = false
  saveIntervalOpen.value = false
  await nextTick()
  drawHorizon()
})
onMounted(drawHorizon)
</script>

<template>
  <section class="utility-scene" :class="`utility-scene-${scene}`">
    <template v-if="scene === 26">
      <canvas ref="horizonCanvas" class="recent-horizon" aria-hidden="true" />
      <svg class="utility-svg-defs" aria-hidden="true">
        <defs>
          <linearGradient id="utility-stack-fill" x1="0" x2="1" y1="0" y2="1"><stop stop-color="#c1efff" /><stop offset="1" stop-color="#79c7ef" /></linearGradient>
          <symbol id="utility-stack" viewBox="0 0 64 64">
            <path d="m9 22 23-12 23 12-23 12Z" fill="url(#utility-stack-fill)" stroke="#c6f0ff" stroke-width="1.2" stroke-linejoin="round" />
            <path d="m11 34 21 11 21-11M11 45l21 11 21-11" fill="none" stroke="#8dd6f9" stroke-width="5" stroke-linecap="round" stroke-linejoin="round" />
          </symbol>
        </defs>
      </svg>
      <div class="recent-heading">
        <h1>最近访问</h1>
        <p>13 类对象 · 按对象去重 · 仅记录可见详情</p>
      </div>
      <div class="recent-actions">
        <div class="utility-dropdown">
          <button class="utility-button recent-type-button" :aria-expanded="recentMenuOpen" @click="recentMenuOpen = !recentMenuOpen"><RIcon name="layout-grid" :size="24" />{{ recentType }}<RIcon name="chevron-down" :size="17" /></button>
          <div v-if="recentMenuOpen" class="utility-menu" role="menu">
            <button v-for="type in ['全部类型', ...recentObjects.map(item => item.type)]" :key="type" role="menuitemradio" :aria-checked="recentType === type" @click="selectRecentType(type)">{{ type }}<RIcon v-if="recentType === type" name="check" :size="17" /></button>
          </div>
        </div>
        <button class="utility-button recent-clear-button" @click="clearRecent"><RIcon name="trash-2" :size="24" />清除历史</button>
      </div>

      <aside class="utility-panel recent-filters" aria-label="访问分类">
        <button v-for="category in recentCategories" :key="category.name" class="recent-category" :class="{ selected: recentCategory === category.name }" :aria-pressed="recentCategory === category.name" @click="selectRecentCategory(category.name)">
          <span v-if="category.name === '全部'" class="recent-all-label">全　部</span>
          <template v-else>
            <svg v-if="category.icon === 'video'" class="r-icon" width="27" height="27" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><rect x="1" y="5" width="15" height="14" rx="3" /><path d="m17 9 6-4v14l-6-4Z" /></svg>
            <svg v-else-if="category.name === '准备'" class="r-icon" width="27" height="27" viewBox="0 0 24 24" aria-hidden="true"><rect x="2" y="2" width="20" height="20" rx="4" fill="currentColor" /><path d="m6 12 4 4 8-9" fill="none" stroke="#29251f" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
            <RIcon v-else :name="category.icon" :size="27" />
            <span>{{ category.name }}</span>
          </template>
          <span class="recent-count">{{ recentCleared ? 0 : category.count }}</span>
        </button>
        <div class="recent-filter-rule" />
        <label class="recent-accessibility"><input v-model="currentOnly" type="checkbox" /><span>只显示当前可访问</span></label>
      </aside>

      <section class="utility-panel recent-table" aria-label="最近访问对象">
        <div class="recent-table-head"><span>对象</span><span>类型</span><span>当前状态</span><span>最近访问</span><span /></div>
        <button v-for="item in visibleObjects" :key="item.title" class="recent-table-row" :class="{ selected: selectedRecent === item.index }" :aria-pressed="selectedRecent === item.index" @click="selectedRecent = item.index">
          <span class="recent-object-name">
            <span class="recent-object-icon" :class="item.color">
              <svg v-if="item.icon === 'layers'" width="24" height="25" aria-hidden="true"><use href="#utility-stack" /></svg>
              <RIcon v-else :name="item.icon" :size="item.icon === 'refresh-cw' ? 27 : 23" />
            </span>
            <span :class="{ 'recent-pdf-name': item.color === 'red' }">{{ item.title }}</span>
          </span>
          <span><span class="utility-tag" :class="item.typeColor">{{ item.type }}</span></span>
          <span><span class="utility-tag" :class="item.stateColor">{{ item.state }}</span></span>
          <span class="recent-visit-time">{{ item.time }}</span>
          <RIcon name="chevron-right" :size="20" class="recent-row-chevron" />
        </button>
        <p v-if="!visibleObjects.length" class="utility-empty">{{ recentCleared ? '暂无最近访问记录' : '当前分类没有可显示的访问记录' }}</p>
      </section>

      <aside v-if="!recentCleared && visibleObjects.length" class="utility-panel recent-detail" aria-label="当前访问对象">
        <div class="recent-detail-art" :class="currentObject.color">
          <svg v-if="currentObject.icon === 'layers'" width="64" height="64" aria-hidden="true"><use href="#utility-stack" /></svg>
          <RIcon v-else :name="currentObject.icon" :size="52" />
        </div>
        <h2 :title="currentObject.title">{{ currentObject.title }}</h2>
        <span class="utility-tag recent-detail-type" :class="currentObject.typeColor">{{ currentObject.type }}</span>
        <div class="recent-detail-rule" />
        <p class="recent-state-label">当前状态</p>
        <div class="recent-state-box"><span class="recent-state-dot" /><span class="utility-tag" :class="currentObject.stateColor">{{ currentObject.state }}</span></div>
        <p class="recent-learning-note"><RIcon name="info" :size="23" />访问不构成学习完成</p>
        <button class="utility-primary recent-open" @click="emit('navigate', currentObject.scene)"><RIcon name="play" :size="20" />打开当前对象</button>
        <p class="recent-version-note"><RIcon name="info" :size="21" />不恢复历史版本或滚动位置</p>
      </aside>
    </template>

    <template v-else-if="scene === 27">
      <div class="task-heading"><h1>任务与授权</h1><p><RIcon name="shield-check" :size="25" />所有外发都由你确认</p></div>
      <div class="task-actions">
        <div class="utility-dropdown">
          <button class="utility-button task-filter-button" :aria-expanded="taskMenuOpen" @click="taskMenuOpen = !taskMenuOpen"><RIcon name="filter" :size="23" />{{ taskFilter }}<RIcon name="chevron-down" :size="17" /></button>
          <div v-if="taskMenuOpen" class="utility-menu" role="menu">
            <button v-for="status in ['全部状态', ...taskItems.map(item => item.status)]" :key="status" role="menuitemradio" :aria-checked="taskFilter === status" @click="chooseTaskFilter(status)">{{ status }}<RIcon v-if="taskFilter === status" name="check" :size="17" /></button>
          </div>
        </div>
        <button class="utility-button task-refresh-button" @click="emit('notify', '当前任务状态已刷新，仅使用页面内数据')"><RIcon name="refresh-cw" :size="23" />刷新</button>
      </div>

      <aside class="utility-panel task-queue">
        <h2>任务队列 (4)</h2>
        <button v-for="task in visibleTasks" :key="task.name" class="task-queue-item" :class="{ selected: selectedTask === task.index }" :aria-pressed="selectedTask === task.index" @click="selectedTask = task.index">
          <span class="task-dot" :class="task.color" />
          <span class="task-item-name">{{ task.name }}</span>
          <span class="task-status" :class="task.color">{{ task.index === 0 && taskCancelled ? '已取消' : task.index === 0 && sendConfirmed ? '已确认发送' : task.status }}</span>
          <span class="task-item-subject">{{ task.subject }}</span>
          <!-- 27_AI任务与授权.png：首条任务 x280..376 / y386..402 的小字无法可靠辨认，保留空白。 -->
          <span class="task-item-time" :class="{ dim: task.index === 0 }">{{ task.time }}</span>
        </button>
      </aside>

      <section class="utility-panel task-detail">
        <div class="task-detail-heading">
          <span class="task-progress-ring" :class="{ finished: sendConfirmed, cancelled: taskCancelled }" aria-hidden="true" />
          <div><h2>{{ currentTask.name }} · {{ currentTask.subject }}</h2><p>{{ selectedTask === 0 && !taskCancelled && !sendConfirmed ? '当前阶段：校验引用中 · 预计 1–2 分钟' : currentTaskStatus }}</p></div>
        </div>
        <template v-if="selectedTask === 0">
          <div class="task-steps" aria-label="任务处理进度">
            <div class="task-step-line" />
            <div class="task-step"><span class="task-step-marker"><RIcon name="check" :size="22" /></span><span>任务接收</span></div>
            <div class="task-step current"><span class="task-step-marker"><span /></span><span>校验引用</span></div>
            <div class="task-step"><span class="task-step-marker"><RIcon name="check" :size="22" /></span><span>发送中</span></div>
          </div>
          <div class="task-input-section">
            <h3>输入清单</h3>
            <div class="task-input-list"><p>将发送 2 条固定原文</p><div><RIcon name="file-text" :size="27" /><span>知识</span><span class="task-version">v4</span></div><div><RIcon name="file-text" :size="27" /><span>笔记</span><span class="task-version">v3</span></div></div>
          </div>
          <div class="task-preview-section"><h3>结果预览 <RIcon name="info" :size="19" /></h3><div class="task-preview-chips"><span><RIcon name="file-text" :size="21" />知识 <b>v4</b> · 1 条引用</span><span><RIcon name="file-text" :size="21" />笔记 <b>v3</b> · 1 条引用</span></div></div>
          <div class="task-cost-note"><span>!</span><p><strong>费用待核对</strong> · 不按 0 显示</p></div>
          <div class="task-detail-actions"><button class="utility-button" :disabled="taskCancelled || sendConfirmed" @click="cancelTask">取消任务</button><button class="utility-button task-record-button" @click="emit('notify', `${currentTask.name} · ${currentTask.subject} · ${currentTaskStatus}`)">查看任务记录</button></div>
        </template>
        <div v-else class="task-alternate-summary"><h3>当前状态</h3><span class="task-status" :class="currentTask.color">{{ currentTaskStatus }}</span><p>{{ currentTask.subject }}</p><button class="utility-button" @click="emit('notify', `${currentTask.name} · ${currentTask.subject} · ${currentTaskStatus}`)">查看任务记录</button></div>
      </section>

      <aside class="utility-panel task-authorization">
        <h2><RIcon name="shield-check" :size="33" />本次授权</h2>
        <div class="task-configured"><span><RIcon name="check" :size="20" /></span>已配置 · 服务端保管</div>
        <dl class="task-authorization-list">
          <div><dt><RIcon name="layout-grid" :size="27" />用途</dt><dd>{{ currentTask.name }}</dd></div>
          <template v-if="selectedTask === 0"><div><dt><RIcon name="database" :size="27" />来源</dt><dd>知识 v4 · 笔记 v3</dd></div><div><dt><RIcon name="scan" :size="27" />范围</dt><dd>2 条原文</dd></div></template>
        </dl>
        <div class="task-authorization-rule" />
        <div class="task-continuous"><h3>持续授权</h3><button class="utility-switch" role="switch" :aria-checked="continuousAuthorization" aria-label="持续授权" @click="continuousAuthorization = !continuousAuthorization" /></div>
        <p class="task-authorization-note"><RIcon name="info" :size="20" />范围或供应商变化将重新确认</p>
        <button class="utility-primary task-confirm" :disabled="selectedTask !== 0 || taskCancelled || sendConfirmed" @click="confirmSend">{{ sendConfirmed ? '已确认发送' : taskCancelled ? '任务已取消' : '确认并发送' }}</button>
      </aside>
    </template>

    <template v-else-if="scene === 28">
      <div class="settings-heading"><p>设置 <span>/</span> 外观与数据</p><h1>设置</h1></div>
      <nav class="utility-panel settings-navigation" aria-label="设置分类">
        <button v-for="section in settingSections" :key="section.name" :class="{ selected: selectedSetting === section.name }" :aria-pressed="selectedSetting === section.name" @click="selectSetting(section.name, section.target)"><RIcon :name="section.icon" :size="29" /><span>{{ section.name }}</span></button>
      </nav>
      <section class="utility-panel settings-appearance" :class="{ 'utility-focused': settingFocus === 'appearance' }">
        <h2><RIcon name="sun" :size="35" />外观</h2>
        <div class="settings-row settings-theme-row"><h3>主题外观</h3><div class="utility-segment" role="group" aria-label="主题外观"><button v-for="theme in [{ name: '跟随系统', icon: 'monitor' }, { name: '深色', icon: 'moon' }, { name: '浅色', icon: 'sun' }]" :key="theme.name" :class="{ selected: settings.theme === theme.name }" :aria-pressed="settings.theme === theme.name" @click="settings.theme = theme.name"><RIcon :name="theme.icon" :size="26" />{{ theme.name }}</button></div></div>
        <div class="settings-row settings-width-row"><h3>内容宽度</h3><div class="utility-segment" role="group" aria-label="内容宽度"><button v-for="width in ['舒适', '宽', '全宽']" :key="width" :class="{ selected: settings.width === width }" :aria-pressed="settings.width === width" @click="settings.width = width">{{ width }}</button></div></div>
        <div class="settings-row settings-font-row" :class="{ 'utility-focused': settingFocus === 'font' }"><h3>正文字号</h3><div class="utility-segment" role="group" aria-label="正文字号"><button v-for="font in ['15', '16', '17']" :key="font" :class="{ selected: settings.font === font }" :aria-pressed="settings.font === font" @click="settings.font = font">{{ font }}</button></div></div>
        <div class="settings-row settings-motion-row" :class="{ 'utility-focused': settingFocus === 'motion' }"><h3>动效</h3><div class="utility-segment" role="group" aria-label="动效"><button v-for="motion in ['轻量', '完整', '静止']" :key="motion" :class="{ selected: settings.motion === motion }" :aria-pressed="settings.motion === motion" @click="settings.motion = motion">{{ motion }}</button></div></div>
        <div class="settings-motion-note"><span>减少动效可提升流畅度</span><label><button class="utility-switch" role="switch" :aria-checked="settings.reducedMotion" aria-label="减少动效" @click="settings.reducedMotion = !settings.reducedMotion" /><span>减少动效</span></label></div>
        <div class="settings-appearance-rule" />
        <div class="settings-row settings-timezone-row" :class="{ 'utility-focused': settingFocus === 'timezone' }"><h3>时区</h3><div class="utility-dropdown"><button class="settings-select" :aria-expanded="timezoneOpen" @click="timezoneOpen = !timezoneOpen; saveIntervalOpen = false"><RIcon name="globe" :size="25" /><span>{{ settings.timezone }}</span><RIcon name="chevron-down" :size="21" /></button><div v-if="timezoneOpen" class="utility-menu settings-select-menu" role="menu"><button v-for="timezone in ['Asia/Shanghai', 'Asia/Tokyo', 'UTC']" :key="timezone" role="menuitemradio" :aria-checked="settings.timezone === timezone" @click="settings.timezone = timezone; timezoneOpen = false">{{ timezone }}<RIcon v-if="settings.timezone === timezone" name="check" :size="18" /></button></div></div></div>
        <div class="settings-row settings-save-row" :class="{ 'utility-focused': settingFocus === 'save' }"><h3>自动保存间隔</h3><div class="utility-dropdown"><button class="settings-select" :aria-expanded="saveIntervalOpen" @click="saveIntervalOpen = !saveIntervalOpen; timezoneOpen = false"><span>{{ settings.saveInterval }}</span><RIcon name="chevron-down" :size="21" /></button><div v-if="saveIntervalOpen" class="utility-menu settings-select-menu" role="menu"><button v-for="interval in ['1 分钟', '2 分钟', '5 分钟']" :key="interval" role="menuitemradio" :aria-checked="settings.saveInterval === interval" @click="settings.saveInterval = interval; saveIntervalOpen = false">{{ interval }}<RIcon v-if="settings.saveInterval === interval" name="check" :size="18" /></button></div></div></div>
      </section>
      <aside class="utility-panel settings-data" :class="{ 'utility-focused': settingFocus === 'usage' || settingFocus === 'export' }">
        <h2><RIcon name="database" :size="29" />数据概览</h2>
        <dl class="settings-data-overview"><div v-for="item in dataOverview" :key="item.name"><dt><RIcon :name="item.icon" :size="30" :style="{ color: item.color }" />{{ item.name }}</dt><dd>{{ item.value }}</dd></div></dl>
        <div class="settings-export-section"><h3>导出数据</h3><p>导出你的知识与学习数据</p><button class="utility-primary settings-export-primary" @click="exportData('完整导出')"><RIcon name="download" :size="25" />完整导出</button><button class="utility-button settings-export-secondary" @click="exportData('Markdown 导出')"><RIcon name="file-text" :size="25" />Markdown 导出</button><button class="utility-button settings-export-secondary" @click="exportData('JSON Canvas 导出')"><span class="settings-braces">{ }</span>JSON Canvas 导出</button><p class="settings-export-note"><RIcon name="info" :size="24" />笔记凭据不会导出</p></div>
      </aside>
    </template>
  </section>
</template>

<style scoped>
.utility-scene { position: absolute; inset: 0; color: #f4f0ea; font-size: 20px; line-height: 1.5; }
.utility-scene button { font-weight: 400; }
.utility-panel { position: absolute; border: 1px solid #3b332bd9; border-radius: 20px; background: linear-gradient(125deg, #24201bc9, #191612d9 72%); }
.utility-button { display: inline-flex; align-items: center; justify-content: center; gap: 10px; height: 53px; padding: 0 18px !important; border: 1px solid #4c4238 !important; border-radius: 19px; background: linear-gradient(130deg, #302b24a6, #1d1a16d9) !important; white-space: nowrap; }
.utility-button:hover { background-color: #ffffff0c !important; }
.utility-primary { display: inline-flex; align-items: center; justify-content: center; gap: 13px; height: 57px; padding: 0 19px !important; border: 1px solid #f0b57dbb !important; border-radius: 17px; color: #170d06; background: linear-gradient(130deg, #edb681, #ca8e5d 60%, #b47a50) !important; font-size: 21px; font-weight: 600 !important; }
.utility-primary:hover { filter: brightness(1.08); }
.utility-dropdown { position: relative; }
.utility-menu { position: absolute; right: 0; top: calc(100% + 8px); z-index: 10; min-width: 180px; padding: 7px; border: 1px solid #796047; border-radius: 14px; background: #28211a; }
.utility-menu button { display: flex; align-items: center; justify-content: space-between; gap: 16px; width: 100%; padding: 9px 12px !important; text-align: left; border-radius: 8px; font-size: 18px; white-space: nowrap; }
.utility-menu button:hover, .utility-menu button[aria-checked="true"] { background: #a76c343d; }
.utility-tag { display: inline-flex; align-items: center; justify-content: center; padding: 5px 14px; border-radius: 24px; font-size: 19px; white-space: nowrap; line-height: 1.7; }
.utility-tag.blue { color: #a5e4fb; background: #2d465c8c; }
.utility-tag.purple { color: #d8b6fa; background: #48305c7d; }
.utility-tag.cyan { color: #a7e5f6; background: #2846536e; }
.utility-tag.indigo { color: #b9b9ff; background: #2d2d5f94; }
.utility-tag.teal { color: #83e9df; background: #274337a8; }
.utility-tag.amber { color: #f4c184; background: #67421f8f; }
.utility-tag.green { color: #7be3bd; background: #23452e83; }
.utility-tag.neutral { background: #ffffff10; }
.utility-switch { position: relative; flex: none; width: 57px; height: 34px; border: 1px solid #5d554c !important; border-radius: 30px; background: #4d4740 !important; }
.utility-switch::after { content: ""; position: absolute; top: 3px; left: 3px; width: 26px; height: 26px; border-radius: 50%; background: linear-gradient(135deg, #c8c5c1, #a7a39e); }
.utility-switch[aria-checked="true"] { background: #b7804b !important; border-color: #dfad77 !important; }
.utility-switch[aria-checked="true"]::after { left: 26px; background: #fff0de; }
.utility-focused { outline: 1px solid #c28d5c; outline-offset: 4px; border-radius: 12px; }
.utility-empty { padding: 60px 22px; color: #aaa299; font-size: 19px; text-align: center; }
.utility-svg-defs { position: absolute; width: 0; height: 0; overflow: hidden; }

/* 26：坐标均从原图 x 减去 224；行星为确定性程序绘制，缺少原始纹理素材。 */
.recent-horizon { position: absolute; top: 0; left: 0; width: 1312px; height: 1024px; pointer-events: none; }
.recent-heading { position: absolute; top: 146px; left: 40px; }
.recent-heading h1 { font-size: 56px; line-height: 1.3; font-weight: 650; color: #faf9f6; }
.recent-heading p { margin-top: 6px; font-size: 23px; color: #d8c1aa; }
.recent-actions { position: absolute; top: 196px; left: 970px; display: flex; gap: 20px; z-index: 2; }
.recent-actions .utility-button { height: 55px; border-radius: 21px; }
.recent-type-button { width: 161px; gap: 7px; padding: 0 12px !important; }
.recent-clear-button { width: 140px; padding: 0 12px !important; gap: 9px; }
.recent-filters { top: 287px; left: 15px; width: 209px; height: 601px; padding: 19px 12px; background: linear-gradient(115deg, #26211bdb, #151310e3); }
.recent-category { display: flex; align-items: center; gap: 19px; width: 183px; height: 60px; padding: 0 13px !important; border-radius: 15px; text-align: left; font-size: 22px; color: #c0bbb4; }
.recent-category:first-child { height: 56px; margin-bottom: 5px; }
.recent-category.selected { color: #fff8ef; background: linear-gradient(120deg, #795435bf, #573c29cf) !important; box-shadow: inset 0 0 0 1px #9c754b3d; }
.recent-category:hover { background-color: #ffffff06; }
.recent-all-label { padding-left: 1px; font-size: 23px; }
.recent-count { display: inline-flex; align-items: center; justify-content: center; margin-left: auto; min-width: 29px; height: 30px; padding: 0 7px; border-radius: 50%; background: #ffffff12; font-size: 19px; color: #d5ccc3; }
.recent-category.selected .recent-count { background: #b58a6633; color: #f8eee2; }
.recent-filter-rule { margin: 13px 10px 21px; height: 1px; background: #ffffff12; }
.recent-accessibility { display: flex; align-items: center; gap: 8px; padding-left: 13px; font-size: 18px; white-space: nowrap; }
.recent-accessibility input { appearance: none; display: inline-grid; place-items: center; width: 23px; height: 23px; margin: 0; border: 1px solid #a8a29b; border-radius: 5px; background: #c5c1ba; cursor: pointer; }
.recent-accessibility input:checked::after { content: ""; width: 12px; height: 7px; border-left: 3px solid #292722; border-bottom: 3px solid #292722; transform: translateY(-2px) rotate(-45deg); }
.recent-accessibility input:not(:checked) { background: transparent; }
.recent-table { top: 287px; left: 241px; width: 721px; height: 613px; background: linear-gradient(120deg, #221e19d9, #171411e6 70%); }
.recent-table-head, .recent-table-row { display: grid; grid-template-columns: 260px 118px 160px 109px 19px; align-items: center; text-align: left; }
.recent-table-head { height: 74px; padding-left: 21px; color: #d1ccc4; font-size: 20px; }
.recent-table-head > span:not(:first-child) { position: relative; }
.recent-table-head > span:not(:first-child)::before { content: ""; position: absolute; left: -15px; top: 0; height: 31px; width: 1px; background: #ffffff04; }
.recent-table-row { position: relative; width: 706px; height: 77px; padding: 0 21px !important; border-top: 1px solid transparent !important; border-bottom: 1px solid transparent !important; font-size: 20px; }
.recent-table-row::after { content: ""; position: absolute; left: 21px; right: 8px; bottom: -1px; height: 1px; background: #ffffff09; }
.recent-table-row.selected { border: 1px solid #ad7749 !important; border-right-color: #8a60383d !important; background: linear-gradient(120deg, #6a452b82, #37291d80) !important; border-radius: 15px; }
.recent-table-row.selected::after { display: none; }
.recent-table-row:hover { background-color: #ffffff05; }
.recent-object-name { display: flex; align-items: center; gap: 12px; white-space: nowrap; font-weight: 550; }
.recent-object-name .recent-pdf-name { font-size: 18px; }
.recent-object-icon { display: inline-flex; align-items: center; justify-content: center; flex: none; width: 26px; height: 28px; border-radius: 6px; }
.recent-object-icon.blue { color: #e0f7ff; background: linear-gradient(135deg, #75bfe7, #438ac2); }
.recent-object-icon.purple { color: #c994ee; }
.recent-object-icon.indigo { color: #d5dbff; background: linear-gradient(135deg, #8092ff, #4b58bc); }
.recent-object-icon.red { color: #fff3ef; background: linear-gradient(135deg, #ff9082, #df413d); }
.recent-visit-time { white-space: nowrap; }
.recent-row-chevron { color: #aaa59d; }
.recent-detail { top: 287px; left: 978px; width: 313px; height: 613px; padding: 28px 28px 23px; background: linear-gradient(125deg, #211d18de, #171410eb); }
.recent-detail-art { display: flex; align-items: center; justify-content: center; width: 94px; height: 94px; border: 1px solid #45728d7a; border-radius: 23px; color: #a7dcf5; background: linear-gradient(130deg, #34586c, #233b4b); }
.recent-detail-art.purple { color: #cc9cf5; background: #392a4a; border-color: #6b4b86; }
.recent-detail-art.indigo { color: #c6d4ff; background: #303e70; border-color: #4f6097; }
.recent-detail-art.red { color: #ffc7ba; background: #6e3830; border-color: #ae6855; }
.recent-detail h2 { height: 42px; margin-top: 18px; font-size: 29px; font-weight: 650; line-height: 1.45; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.recent-detail-type { margin-top: 9px; padding: 4px 17px; font-size: 20px; }
.recent-detail-type.blue { background: #303758ab; color: #d2e5ff; }
.recent-detail-rule { height: 1px; margin: 20px 0 18px; background: #ffffff13; }
.recent-state-label { font-size: 20px; color: #ded7ce; }
.recent-state-box { display: flex; align-items: center; height: 64px; margin: 9px -6px 0; padding: 0 20px; gap: 12px; border: 1px solid #ffffff03; border-radius: 17px; background: linear-gradient(100deg, #39332c73, #3b342d66); }
.recent-state-dot { flex: none; width: 18px; height: 19px; border: 1px solid #a9f4e1a3; border-radius: 50%; background: linear-gradient(130deg, #8aeddb, #57b9b0); }
.recent-state-box .utility-tag { padding: 2px 4px; background: #25403753; font-size: 22px; font-weight: 550; }
.recent-learning-note { display: flex; align-items: center; gap: 9px; height: 55px; margin: 16px -6px 0 !important; padding: 0 15px; border: 1px solid #ffffff04; border-radius: 15px; font-size: 19px; white-space: nowrap; }
.recent-open { width: 265px; margin: 23px -6px 0; }
.recent-open .r-icon { fill: #86552a; color: #86552a; }
.recent-version-note { display: flex; align-items: center; gap: 8px; height: 46px; margin: 16px -6px 0 !important; padding: 0 9px; border: 1px solid #ffffff07; border-radius: 15px; font-size: 17px; white-space: nowrap; color: #aba195; }

/* 27：坐标均从原图 x 减去 216。 */
.task-heading { position: absolute; top: 114px; left: 36px; }
.task-heading h1 { font-size: 45px; font-weight: 650; line-height: 1.3; }
.task-heading p { display: flex; align-items: center; gap: 10px; margin-top: 8px; font-size: 21px; color: #bdb9b3; }
.task-actions { position: absolute; top: 140px; left: 1006px; z-index: 2; display: flex; gap: 15px; }
.task-actions .utility-button { height: 51px; border-radius: 16px; border-color: #ffffff04 !important; }
.task-filter-button { width: 164px; gap: 8px; font-weight: 550 !important; }
.task-refresh-button { width: 105px; }
.task-queue { top: 235px; left: 19px; width: 334px; height: 710px; background: linear-gradient(120deg, #25211bc4, #1b1713db); }
.task-queue h2 { margin: 23px 21px 11px; font-size: 25px; font-weight: 550; }
.task-queue-item { position: relative; display: block; width: 332px; height: 109px; text-align: left; padding: 15px 21px 12px 45px !important; border: 1px solid transparent !important; color: #f2eeea; }
.task-queue-item::after { content: ""; position: absolute; left: 21px; right: 21px; bottom: 0; height: 1px; background: #ffffff14; }
.task-queue-item:last-child::after { display: none; }
.task-queue-item.selected { height: 107px; border-color: #e4aa6b !important; border-radius: 16px; background: linear-gradient(120deg, #825a3378, #533c2582) !important; }
.task-queue-item.selected::after { display: none; }
.task-queue-item:hover { background-color: #ffffff05; }
.task-dot { position: absolute; left: 21px; top: 27px; width: 12px; height: 12px; border-radius: 50%; }
.task-dot.amber { background: #f2b54d; }
.task-dot.green { background: #46d16a; }
.task-dot.red { background: #ef453c; }
.task-dot.blue { background: #509ad2; }
.task-item-name { font-size: 21px; font-weight: 600; }
.task-status { display: inline-flex; justify-content: center; align-items: center; padding: 3px 12px; border-radius: 22px; font-size: 17px; line-height: 1.5; white-space: nowrap; }
.task-queue-item > .task-status { position: absolute; right: 21px; top: 17px; }
.task-status.amber { color: #fff2db; background: linear-gradient(120deg, #986515df, #69430ebf); }
.task-status.green { color: #dcffe4; background: #255a2fd9; }
.task-status.red { color: #ffe6e0; background: #81271fd9; }
.task-status.blue { color: #d7eaff; background: #304454de; }
.task-item-subject { display: block; margin-top: 4px; font-size: 17px; color: #bcb6ae; }
.task-item-time { position: absolute; right: 21px; bottom: 9px; color: #c2bbb2; font-size: 17px; }
.task-item-time.dim { color: #76674f6e; }
.task-detail { top: 235px; left: 371px; width: 516px; height: 710px; padding: 27px 23px 22px; background: linear-gradient(125deg, #211d17cb, #1a1712e8); }
.task-detail-heading { display: flex; align-items: flex-start; gap: 20px; }
.task-detail-heading h2 { font-size: 26px; font-weight: 650; white-space: nowrap; }
.task-detail-heading p { margin-top: 6px; font-size: 18px; white-space: nowrap; color: #bcb7b0; }
.task-progress-ring { flex: none; width: 47px; height: 47px; margin-top: 2px; border: 7px solid #6b4b2c; border-top-color: #f6bc73; border-right-color: #f6bc73; border-bottom-color: #d49a52; border-radius: 50%; transform: rotate(19deg); }
.task-progress-ring.finished { border-color: #7cc99e; }
.task-progress-ring.cancelled { border-color: #74675b; }
.task-steps { position: absolute; top: 135px; left: 23px; width: 469px; display: flex; justify-content: space-between; padding: 0 0; }
.task-step-line { position: absolute; top: 15px; left: 38px; right: 38px; height: 4px; background: linear-gradient(90deg, #6c655a 0%, #7c6448 49%, #3a342d 50%, #28251f 100%); }
.task-step { position: relative; display: flex; flex-direction: column; align-items: center; gap: 9px; width: 76px; color: #bcb7b0; font-size: 20px; }
.task-step-marker { display: inline-flex; align-items: center; justify-content: center; width: 33px; height: 33px; border-radius: 50%; background: linear-gradient(130deg, #67655f, #393630); color: #c6c2bb; }
.task-step.current { color: #faf4ec; font-weight: 600; }
.task-step.current .task-step-marker { border: 3px solid #f0ad68; background: #39291b; }
.task-step.current .task-step-marker > span { width: 19px; height: 19px; border-radius: 50%; background: #efb57b; }
.task-input-section { position: absolute; left: 23px; right: 23px; top: 244px; }
.task-detail h3 { font-size: 21px; font-weight: 600; }
.task-input-list { height: 161px; margin-top: 5px; padding: 12px 17px; border: 1px solid #ffffff02; border-radius: 16px; background: linear-gradient(110deg, #342f2882, #302b2563); }
.task-input-list > p { font-size: 19px; }
.task-input-list > div { display: flex; align-items: center; gap: 12px; height: 50px; font-size: 21px; }
.task-input-list .r-icon { color: #d8d5ce; }
.task-version { display: inline-flex; align-items: center; justify-content: center; min-width: 44px; height: 31px; margin-left: 7px; border-radius: 20px; font-size: 18px; background: #ffffff10; }
.task-preview-section { position: absolute; top: 456px; left: 23px; right: 23px; }
.task-preview-section h3 { display: flex; align-items: center; gap: 8px; }
.task-preview-section h3 .r-icon { color: #79756c; }
.task-preview-chips { display: flex; gap: 8px; margin-top: 5px; }
.task-preview-chips > span { display: inline-flex; align-items: center; gap: 5px; width: 219px; height: 45px; padding: 0 16px; border-radius: 24px; background: #ffffff08; font-size: 18px; color: #c3bdb4; white-space: nowrap; }
.task-preview-chips > span:last-child { width: 218px; }
.task-preview-chips .r-icon { color: #dedad2; }
.task-preview-chips b { font-size: 17px; font-weight: 400; padding: 1px 5px; border-radius: 10px; background: #ffffff08; color: #f0ebe4; }
.task-cost-note { position: absolute; top: 551px; left: 23px; right: 23px; display: flex; align-items: center; gap: 14px; height: 59px; padding: 0 17px; border: 1px solid #6b4c242e; border-radius: 14px; background: linear-gradient(110deg, #7044164d, #62422557); }
.task-cost-note > span { display: inline-grid; place-items: center; width: 24px; height: 25px; border-radius: 50%; background: #f1b777; color: #3c2612; font-size: 21px; font-weight: 650; }
.task-cost-note p { font-size: 19px; color: #ddc9b4; white-space: nowrap; }
.task-cost-note strong { color: #efb66e; font-weight: 600; }
.task-detail-actions { position: absolute; left: 23px; right: 23px; bottom: 21px; display: flex; gap: 23px; }
.task-detail-actions .utility-button { flex: 1; height: 58px; border-radius: 16px; }
.task-detail-actions .utility-button:first-child { flex: 1.06; border-color: transparent !important; }
.task-detail-actions .task-record-button { border-color: #b89a79 !important; }
.task-alternate-summary { position: absolute; top: 180px; left: 23px; right: 23px; }
.task-alternate-summary .task-status { margin-top: 18px; }
.task-alternate-summary p { margin-top: 20px; color: #c5beb4; }
.task-alternate-summary .utility-button { margin-top: 30px; }
.task-authorization { top: 235px; left: 905px; width: 386px; height: 710px; padding: 25px 23px; background: linear-gradient(125deg, #211d17c9, #1a1612e8); }
.task-authorization h2 { display: flex; align-items: center; gap: 10px; font-size: 26px; font-weight: 600; }
.task-authorization h2 .r-icon { color: #d6d5d0; }
.task-configured { display: flex; align-items: center; gap: 12px; height: 69px; margin-top: 29px; padding: 0 15px; border: 1px solid #31552b24; border-radius: 15px; color: #cef3d0; background: #2844215e; font-size: 21px; white-space: nowrap; }
.task-configured > span { display: inline-grid; place-items: center; width: 27px; height: 27px; border-radius: 50%; color: #11371b; background: #7dde7c; }
.task-authorization-list { margin: 30px 0 0; }
.task-authorization-list > div { display: flex; align-items: center; height: 59px; }
.task-authorization-list dt { display: flex; align-items: center; gap: 17px; width: 159px; flex: none; color: #bdb7ae; }
.task-authorization-list dd { margin: 0; white-space: nowrap; font-size: 20px; }
.task-authorization-rule { position: absolute; top: 411px; left: 23px; right: 23px; height: 1px; background: #ffffff12; }
.task-continuous { position: absolute; top: 457px; left: 23px; right: 23px; display: flex; align-items: center; justify-content: space-between; }
.task-continuous h3 { font-size: 23px; font-weight: 550; }
.task-authorization-note { position: absolute; top: 500px; left: 23px; right: 23px; display: flex; align-items: center; gap: 9px; white-space: nowrap; font-size: 18px; color: #a9a299; }
.task-authorization-note .r-icon { color: #7b746b; }
.task-confirm { position: absolute; left: 23px; right: 23px; bottom: 44px; height: 60px; border-radius: 16px; }

/* 28：坐标均从原图 x 减去 240；分段设置只改变当前组件内存。 */
.settings-heading { position: absolute; top: 116px; left: 24px; }
.settings-heading > p { display: flex; gap: 15px; align-items: center; color: #c9c2b9; font-size: 20px; }
.settings-heading > p > span { color: #e1d6c8; }
.settings-heading h1 { margin-top: 11px; font-size: 54px; font-weight: 650; line-height: 1.3; }
.settings-navigation { top: 247px; left: 24px; width: 230px; height: 666px; padding: 13px 9px; background: linear-gradient(130deg, #392a1cc7, #1b1713db 50%, #30231ad6); border-color: #7150387a; border-radius: 25px; }
.settings-navigation > button { display: flex; align-items: center; gap: 20px; width: 208px; height: 58px; padding: 0 18px !important; border: 1px solid transparent !important; border-radius: 21px; color: #d6d0c8; font-size: 20px; text-align: left; white-space: nowrap; }
.settings-navigation > button:first-child { height: 64px; margin-bottom: 7px; }
.settings-navigation > button.selected { color: #fff3e5; border-color: #93643bb3 !important; background: linear-gradient(115deg, #93633bb3, #4d321fc9) !important; font-weight: 550; }
.settings-navigation > button:hover { background-color: #ffffff06; }
.settings-appearance { top: 245px; left: 268px; width: 644px; height: 691px; border-radius: 24px; background: linear-gradient(120deg, #261f18c4, #211c17e0); }
.settings-appearance > h2 { position: absolute; top: 20px; left: 27px; display: flex; align-items: center; gap: 16px; font-size: 29px; font-weight: 600; }
.settings-appearance > h2 .r-icon { color: #f3b275; }
.settings-row { position: absolute; left: 29px; right: 25px; display: flex; align-items: center; min-height: 53px; }
.settings-row > h3 { width: 133px; flex: none; font-size: 20px; font-weight: 550; white-space: nowrap; }
.settings-theme-row { top: 88px; }
.settings-width-row { top: 178px; }
.settings-font-row { top: 267px; }
.settings-motion-row { top: 356px; }
.utility-segment { display: grid; grid-template-columns: repeat(3, 1fr); height: 53px; width: 456px; border: 1px solid #5c504350; border-radius: 23px; background: linear-gradient(120deg, #302a2359, #3b332966); }
.utility-segment > button { display: flex; align-items: center; justify-content: center; gap: 10px; min-width: 0; height: 53px; margin-top: -1px; border: 1px solid #51483b3d !important; border-radius: 23px; font-size: 20px; white-space: nowrap; }
.utility-segment > button:first-child { margin-left: -1px; }
.utility-segment > button:last-child { margin-right: -1px; }
.utility-segment > button.selected { border-color: #f0b77f !important; color: #fff7ec; background: radial-gradient(ellipse at 30% 5%, #ecbd8cb0, transparent 62%), linear-gradient(125deg, #cd9666, #96623ed9 70%, #ad8055) !important; box-shadow: inset 0 0 0 1px #f3c99e3d; font-weight: 550; }
.utility-segment > button:hover { background-color: #ffffff07; }
.settings-theme-row .utility-segment { gap: 4px; background: transparent; border-color: transparent; }
.settings-theme-row .utility-segment > button { border-color: #67594b85 !important; }
.settings-theme-row .utility-segment > button.selected { border-color: #f0b77f !important; }
.settings-theme-row .utility-segment > button:first-child { font-size: 18px; gap: 9px; }
.settings-motion-note { position: absolute; top: 416px; left: 162px; right: 25px; display: flex; align-items: flex-start; justify-content: space-between; color: #a39c93; font-size: 18px; white-space: nowrap; }
.settings-motion-note > label { display: flex; align-items: center; gap: 14px; margin-top: 11px; color: #d3cdc4; }
.settings-motion-note .utility-switch { width: 48px; height: 28px; border-color: #898078 !important; background: #5d5751 !important; box-shadow: inset 0 0 0 1px #aaa49e29; }
.settings-motion-note .utility-switch::after { top: 2px; left: 2px; width: 23px; height: 23px; background: linear-gradient(140deg, #f8f7f4, #d8d6d2); }
.settings-motion-note .utility-switch[aria-checked="true"] { background: #b7804b !important; }
.settings-motion-note .utility-switch[aria-checked="true"]::after { left: 21px; }
.settings-appearance-rule { position: absolute; top: 486px; left: 29px; right: 25px; height: 1px; background: #ffffff14; }
.settings-timezone-row { top: 509px; }
.settings-save-row { top: 592px; }
.settings-save-row > h3 { width: 137px; }
.settings-row > .utility-dropdown { flex: 1; min-width: 0; }
.settings-select { display: flex; align-items: center; width: 100%; height: 53px; gap: 16px; padding: 0 23px !important; border: 1px solid #67574780 !important; border-radius: 22px; background: linear-gradient(120deg, #312a235e, #342d266b) !important; text-align: left; font-size: 20px; }
.settings-select > span { flex: 1; }
.settings-select > .r-icon:last-child { margin-left: auto; }
.settings-select-menu { top: auto; bottom: calc(100% + 8px); min-width: 100%; }
.settings-data { top: 245px; left: 924px; width: 352px; height: 691px; padding: 23px 24px; border-radius: 24px; background: linear-gradient(125deg, #262019c4, #211c17df); }
.settings-data > h2 { display: flex; align-items: center; gap: 18px; font-size: 23px; font-weight: 600; }
.settings-data > h2 .r-icon { color: #f0ba82; }
.settings-data-overview { height: 256px; margin: 19px 0 0; border: 1px solid #ffffff03; border-bottom-color: #ffffff13; border-radius: 18px 18px 0 0; }
.settings-data-overview > div { display: flex; align-items: center; justify-content: space-between; height: 60px; padding: 0 9px 0 6px; border-bottom: 1px solid #ffffff06; }
.settings-data-overview > div:first-child { height: 65px; }
.settings-data-overview > div:last-child { border-bottom: 0; }
.settings-data-overview dt { display: flex; align-items: center; gap: 20px; font-size: 21px; }
.settings-data-overview > div:nth-child(3) dt .r-icon { transform: rotate(90deg); }
.settings-data-overview dd { margin: 0; font-size: 23px; font-weight: 600; }
.settings-export-section { margin-top: 17px; }
.settings-export-section h3 { font-size: 23px; font-weight: 600; }
.settings-export-section > p { color: #b6aea4; font-size: 18px; margin-top: 1px; white-space: nowrap; }
.settings-export-section > button { width: 300px; font-size: 20px; }
.settings-export-primary { margin-top: 18px; height: 53px; border-radius: 21px; color: #fff5e9; font-weight: 550 !important; background: radial-gradient(ellipse at 15% 5%, #eec29599, transparent 70%), linear-gradient(130deg, #b77b4c, #7b4c2fe3) !important; }
.settings-export-secondary { height: 52px; margin-top: 18px; gap: 16px; border-radius: 22px; border-color: #7b716794 !important; background: linear-gradient(120deg, #49423b75, #49423b59) !important; }
.settings-export-secondary + .settings-export-secondary { margin-top: 16px; }
.settings-braces { font-family: Consolas, monospace; font-size: 24px; }
.settings-export-section > .settings-export-note { display: flex; align-items: center; gap: 13px; margin-top: 18px; padding-left: 8px; font-size: 18px; color: #b6afa6; }
.settings-export-note .r-icon { color: #a09a92; }
</style>
