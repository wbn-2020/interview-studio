<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import RBrand from './RBrand.vue'
import RIcon from './RIcon.vue'

const props = defineProps<{ scene: number }>()
const emit = defineEmits<{
  navigate: [scene: number]
  notify: [message: string]
}>()

type TaskFilter = 'all' | 'due' | 'practice'
const loginName = ref('')
const password = ref('')
const passwordVisible = ref(false)
const activeFilter = ref<TaskFilter>('all')
const quickRecordOpen = ref(false)
const quickRecord = ref('')
const interviewNote = ref('')
const savedRecords = ref<string[]>([])
const recordInput = ref<HTMLTextAreaElement | null>(null)
const recordTrigger = ref<HTMLButtonElement | null>(null)
const recordDialog = ref<HTMLElement | null>(null)
const selectedStep = ref(0)

const features = [
  { icon: 'book-open', title: '独立知识库', detail: '概念、笔记与出处', color: '#e99e62' },
  { icon: 'share-2', title: '知识星图', detail: '看见真实关联', color: '#91c5cf' },
  { icon: 'file-text', title: '文字训练', detail: '从空白回答到复盘', color: '#a2d3b5' },
  { icon: 'chart-no-axes-combined', title: '成长证据', detail: '有记录、也有待验证', color: '#ef8959' },
]
const filters: { id: TaskFilter; label: string; count: number }[] = [
  { id: 'all', label: '全部', count: 4 },
  { id: 'due', label: '到期', count: 2 },
  { id: 'practice', label: '待复练', count: 1 },
]
const tasks = [
  { title: '到期回忆 · 缓存一致性', source: '回忆卡', kind: 'recall', icon: 'clock', duration: '3 分钟', due: '现在', overdue: true, practice: false, scene: 16 },
  { title: '整理失败路径 · 分布式系统', source: '知识笔记', kind: 'note', icon: 'file-text', duration: '5 分钟', due: '今天', overdue: true, practice: false, scene: 11 },
  { title: '独立复练 · 系统设计', source: '复练动作', kind: 'practice', icon: 'refresh-cw', duration: '12 分钟', due: '今天', overdue: false, practice: true, scene: 22 },
  { title: '确认面试安排 · 字节跳动', source: '日程', kind: 'schedule', icon: 'calendar-days', duration: '5 分钟', due: '11月12日', overdue: false, practice: false, scene: 8 },
]
const visibleTasks = computed(() => tasks.filter(task => (
  activeFilter.value === 'all'
  || (activeFilter.value === 'due' && task.overdue)
  || (activeFilter.value === 'practice' && task.practice)
)))
const interviewSteps = [
  { title: '复习重点', icon: 'list-checks' },
  { title: '刷题巩固', icon: 'code-2' },
  { title: '模拟面试', icon: 'check' },
]

function enterStudio() {
  // 表单只演示本次页面交互，不校验身份，也不读取或持久化任何凭据。
  loginName.value = ''
  password.value = ''
  passwordVisible.value = false
  emit('navigate', 2)
}

async function openQuickRecord() {
  quickRecordOpen.value = true
  await nextTick()
  recordInput.value?.focus()
}

function closeQuickRecord() {
  quickRecordOpen.value = false
  void nextTick().then(() => recordTrigger.value?.focus({ preventScroll: true }))
}

function recordKeydown(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  if (event.key === 'Escape') {
    event.preventDefault()
    closeQuickRecord()
  } else if (event.key === 'Tab') {
    const targets = [...(recordDialog.value?.querySelectorAll<HTMLElement>('button, textarea') ?? [])]
    const first = targets[0], last = targets.at(-1)
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
  }
}

function filterKeydown(event: KeyboardEvent, index: number) {
  const offset = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? filters.length - 1 : 0
  if (!offset) return
  event.preventDefault()
  const filter = filters[(index + offset) % filters.length]!
  activeFilter.value = filter.id
  document.getElementById(`lt-tab-${filter.id}`)?.focus()
}

function saveQuickRecord() {
  const text = quickRecord.value.trim()
  if (!text) {
    emit('notify', '请先输入记录内容')
    return
  }
  savedRecords.value.push(text)
  quickRecord.value = ''
  closeQuickRecord()
  emit('notify', '快速记录已保留在本次页面内存中')
}

function saveInterviewNote() {
  const text = interviewNote.value.trim()
  if (!text) {
    emit('notify', '请先输入面试准备要点')
    return
  }
  savedRecords.value.push(text)
  interviewNote.value = ''
  emit('notify', '面试准备要点已保留在本次页面内存中')
}

watch(() => props.scene, () => {
  // 父组件即使复用实例，场景切换也不应留下示例密码或打开的记录浮层。
  loginName.value = ''
  password.value = ''
  passwordVisible.value = false
  activeFilter.value = 'all'
  quickRecordOpen.value = false
  quickRecord.value = ''
  interviewNote.value = ''
  savedRecords.value = []
  selectedStep.value = 0
})
</script>

<template>
  <section v-if="scene === 1" class="lt-login" aria-label="登录">
    <div class="lt-dawn" aria-hidden="true">
      <div class="lt-dawn-beam" />
      <div class="lt-dawn-horizon" />
    </div>

    <div class="lt-introduction">
      <RBrand large :variant="0" />
      <p class="lt-brand-description">知识、准备、训练与复盘，留在一个工作台。</p>
      <ul class="lt-features">
        <li v-for="feature in features" :key="feature.title">
          <RIcon :name="feature.icon" :size="49" :stroke-width="1.5" :style="{ color: feature.color }" />
          <div>
            <h2>{{ feature.title }}</h2>
            <p>{{ feature.detail }}</p>
          </div>
        </li>
      </ul>
      <p class="lt-deployment">
        <span class="lt-deployment-rule" />
        <RIcon name="shield-check" :size="28" />
        <span>个人部署 · 数据由你掌控</span>
        <span class="lt-deployment-rule" />
      </p>
    </div>

    <form class="lt-login-form" autocomplete="off" aria-describedby="lt-login-demo" @submit.prevent="enterStudio">
      <h1>登录</h1>
      <p class="lt-login-subtitle">进入你的个人空间</p>
      <p id="lt-login-demo" class="lt-sr-only">此为示例表单，输入仅保留于当前页面内存，请勿输入真实凭据。</p>
      <label class="lt-login-field lt-login-name">
        <RIcon name="user" :size="37" :stroke-width="1.5" />
        <span class="lt-sr-only">示例登录名</span>
        <input v-model="loginName" type="text" placeholder="登录名" autocomplete="off" :spellcheck="false">
      </label>
      <label class="lt-login-field lt-login-password">
        <RIcon name="lock" :size="36" :stroke-width="1.5" />
        <span class="lt-sr-only">示例密码</span>
        <input v-model="password" :type="passwordVisible ? 'text' : 'password'" placeholder="密码" autocomplete="new-password">
        <button
          type="button"
          class="lt-password-toggle"
          :aria-label="passwordVisible ? '隐藏示例密码' : '显示示例密码'"
          :aria-pressed="passwordVisible"
          @click="passwordVisible = !passwordVisible"
        >
          <RIcon :name="passwordVisible ? 'eye' : 'eye-off'" :size="29" />
        </button>
      </label>
      <button type="submit" class="lt-enter">进入工作台</button>
      <button type="button" class="lt-reconnect" @click="emit('notify', '示例页面未发起服务器连接检查或网络请求')">重新检查连接</button>
      <p class="lt-account-note">账号由服务器管理员配置</p>
    </form>
  </section>

  <section v-else-if="scene === 2" class="lt-today" aria-label="今日工作台">
    <header class="lt-today-heading">
      <h1>今日</h1>
      <div class="lt-today-date">
        <p>2026年11月11日 周三</p>
        <p>4 项待处理 · 预计 42 分钟</p>
      </div>
      <div class="lt-today-actions">
        <button ref="recordTrigger" class="lt-quick-button" @click="openQuickRecord">
          <RIcon name="pencil" variant="today" :size="25" />
          快速记录
        </button>
        <button class="lt-focus-button" @click="emit('navigate', 16)">
          <RIcon name="play" variant="today" :size="24" />
          开始专注
        </button>
      </div>
    </header>

    <div class="lt-day-summary">
      <button class="lt-summary-interview" @click="emit('navigate', 8)">
        <RIcon name="calendar-days" variant="today" :size="24" />
        <strong>下一场面试</strong>
        <span>11月12日 14:30 · Java 后端技术一面</span>
      </button>
      <span class="lt-summary-divider" />
      <span class="lt-summary-budget"><RIcon name="clock" :size="24" />今日预算 45 分钟</span>
      <span class="lt-summary-divider" />
      <button class="lt-summary-sources" @click="emit('navigate', 6)"><RIcon name="file-text" :size="23" />来源 4 条</button>
    </div>

    <article class="lt-continue lt-panel">
      <h2><RIcon name="crosshair" :size="30" />继续复练</h2>
      <div class="lt-practice-ring" role="img" aria-label="复练进度，已完成三题中的两题">
        <svg viewBox="0 0 154 154" aria-hidden="true">
          <defs>
            <linearGradient id="lt-ring-gradient" x1="0" y1="1" x2="1" y2="0">
              <stop offset="0" stop-color="#efb784" />
              <stop offset="0.55" stop-color="#cda17d" />
              <stop offset="1" stop-color="#ab8466" />
            </linearGradient>
          </defs>
          <circle class="lt-ring-track" cx="77" cy="77" r="66" />
          <circle class="lt-ring-progress" cx="77" cy="77" r="66" pathLength="4" stroke-dasharray="3 1" transform="rotate(-90 77 77)" />
        </svg>
        <div><strong>2 / 3</strong><span>题</span></div>
      </div>
      <div class="lt-practice-copy">
        <h3>缓存删除失败以后</h3>
        <p>独立复练 · 第 2 次 · 2/3 题</p>
        <div class="lt-practice-actions">
          <button class="lt-resume-button" @click="emit('navigate', 22)"><RIcon name="play" variant="today" :size="23" />继续复练</button>
          <button class="lt-previous-button" @click="emit('navigate', 19)">查看上次回答<RIcon name="chevron-right" :size="23" /></button>
        </div>
      </div>
      <svg class="lt-practice-orbit" viewBox="0 0 795 263" fill="none" aria-hidden="true">
        <defs>
          <linearGradient id="lt-orbit-line"><stop stop-color="#ad743a" stop-opacity="0" /><stop offset=".55" stop-color="#b87d40" stop-opacity=".5" /><stop offset="1" stop-color="#b87d40" stop-opacity=".85" /></linearGradient>
          <radialGradient id="lt-orbit-light"><stop stop-color="#ffcf9c" /><stop offset=".22" stop-color="#efaa65" stop-opacity=".8" /><stop offset=".55" stop-color="#d68a42" stop-opacity=".2" /><stop offset="1" stop-color="#d68a42" stop-opacity="0" /></radialGradient>
        </defs>
        <ellipse cx="615" cy="185" rx="160" ry="55" transform="rotate(-40 615 185)" stroke="url(#lt-orbit-line)" />
        <circle cx="737.6" cy="82.2" r="21" fill="url(#lt-orbit-light)" />
        <ellipse cx="737.6" cy="82.2" rx="4.1" ry="5.2" fill="#fac08b" />
      </svg>
    </article>

    <section class="lt-task-panel lt-panel" aria-labelledby="lt-task-heading">
      <h2 id="lt-task-heading"><span class="lt-heading-check"><RIcon name="check" :size="20" :stroke-width="2.2" /></span>今天要做</h2>
      <div class="lt-task-tabs" role="tablist" aria-label="任务筛选">
        <button
          v-for="(filter, index) in filters"
          :id="`lt-tab-${filter.id}`"
          :key="filter.id"
          type="button"
          role="tab"
          :aria-selected="activeFilter === filter.id"
          :tabindex="activeFilter === filter.id ? 0 : -1"
          aria-controls="lt-task-list"
          :class="{ 'lt-tab-selected': activeFilter === filter.id }"
          @click="activeFilter = filter.id"
          @keydown="filterKeydown($event, index)"
        >{{ filter.label }} {{ filter.count }}</button>
      </div>
      <div id="lt-task-list" role="tabpanel" :aria-labelledby="`lt-tab-${activeFilter}`">
        <table class="lt-task-table">
          <colgroup><col class="lt-task-name-column"><col class="lt-task-source-column"><col class="lt-task-duration-column"><col class="lt-task-due-column"><col class="lt-task-action-column"></colgroup>
          <thead><tr><th scope="col">任务</th><th scope="col">来源</th><th scope="col">时长</th><th scope="col">到期</th><th scope="col">操作</th></tr></thead>
          <tbody>
            <tr v-for="task in visibleTasks" :key="task.title" :class="`lt-task-${task.kind}`">
              <td><button class="lt-task-link" @click="emit('navigate', task.scene)"><RIcon :name="task.icon" variant="today" :size="23" /><span>{{ task.title }}</span></button></td>
              <td><span class="lt-source-tag" :class="`lt-source-${task.kind}`">{{ task.source }}</span></td>
              <td>{{ task.duration }}</td>
              <td :class="{ 'lt-due-today': task.due === '现在' || task.due === '今天' }">{{ task.due }}</td>
              <td><button class="lt-task-more" :aria-label="`${task.title}操作`" @click="emit('navigate', task.scene)"><RIcon name="ellipsis" :size="23" /></button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <aside class="lt-interview-panel lt-panel" aria-labelledby="lt-interview-heading">
      <h2 id="lt-interview-heading"><RIcon name="calendar-days" variant="today" :size="29" />下一场面试</h2>
      <button class="lt-interview-overview" @click="emit('navigate', 8)">
        <span class="lt-interview-calendar"><strong>12</strong><span>NOV</span></span>
        <span class="lt-interview-description">
          <strong>Java 后端技术一面</strong>
          <span>字节跳动</span>
          <span class="lt-interview-time"><RIcon name="clock" :size="23" />北京时间 14:30</span>
        </span>
      </button>
      <div class="lt-interview-rule" />
      <div class="lt-interview-steps" aria-label="面试准备阶段">
        <button
          v-for="(step, index) in interviewSteps"
          :key="step.title"
          :class="{ 'lt-step-selected': selectedStep === index }"
          :aria-pressed="selectedStep === index"
          @click="selectedStep = index; emit('notify', `已选择${step.title}`)"
        >
          <span class="lt-step-symbol"><RIcon :name="step.icon" variant="today" :size="26" :stroke-width="2" /></span>
          <span>{{ step.title }}</span>
        </button>
      </div>
      <div class="lt-interview-capacity">
        <p><span>可用时间</span><strong>45 / 90 分钟</strong></p>
        <div class="lt-capacity-track" role="progressbar" :aria-valuenow="45" :aria-valuemin="0" :aria-valuemax="90" aria-label="面试准备可用时间"><span /></div>
      </div>
      <form class="lt-interview-note" @submit.prevent="saveInterviewNote">
        <RIcon name="pencil" :size="24" />
        <input v-model="interviewNote" aria-label="记录面试准备要点" placeholder="记录面试准备要点..." autocomplete="off">
        <button type="submit" aria-label="保存面试准备要点"><RIcon name="arrow-right" :size="28" /></button>
      </form>
    </aside>

    <div v-if="quickRecordOpen" class="lt-record-backdrop" @click.self="closeQuickRecord" @keydown="recordKeydown">
      <section ref="recordDialog" class="lt-record-dialog" role="dialog" aria-modal="true" aria-labelledby="lt-record-title">
        <div class="lt-record-heading">
          <h2 id="lt-record-title">快速记录</h2>
          <button type="button" class="r-icon-btn" aria-label="关闭快速记录" @click="closeQuickRecord"><RIcon name="x" /></button>
        </div>
        <form @submit.prevent="saveQuickRecord">
          <label for="lt-record-content" class="lt-sr-only">记录内容</label>
          <textarea id="lt-record-content" ref="recordInput" v-model="quickRecord" placeholder="记录此刻的想法..." rows="5" />
          <div class="lt-record-actions">
            <button type="button" class="r-btn" @click="closeQuickRecord">取消</button>
            <button type="submit" class="r-btn r-primary">保存记录</button>
          </div>
        </form>
      </section>
    </div>
  </section>
</template>

<style scoped>
.lt-login,
.lt-today {
  position: absolute;
  inset: 0;
  overflow: hidden;
}

.lt-login *,
.lt-today *,
.lt-login *::before,
.lt-login *::after,
.lt-today *::before,
.lt-today *::after {
  animation: none !important;
  transition: none !important;
}

.lt-login input,
.lt-today input,
.lt-today textarea {
  caret-color: transparent;
}

.lt-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}

.lt-login {
  width: 1536px;
  height: 1024px;
  background: radial-gradient(ellipse at 39% 37%, #4b342025, transparent 43%), #0a0907;
}

.lt-dawn {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  background: radial-gradient(ellipse 360px 190px at 35% 42%, #e3ac6661, #a16c3326 45%, transparent 77%);
}

.lt-dawn-beam {
  position: absolute;
  inset: 0;
  clip-path: polygon(38% 0, 58.3% 0, 34.6% 42.3%, 10% 45%);
  background: linear-gradient(131deg, #24190f00 25%, #7d542c28 48%, #b17d4752 64%, #e5ad6a6e 80%);
}

.lt-dawn-beam::after {
  content: "";
  position: absolute;
  left: 531px;
  top: -145px;
  width: 1px;
  height: 574px;
  background: linear-gradient(0deg, #ebbb7ca3, #95602f44 65%, transparent);
  transform: rotate(40.2deg);
  transform-origin: bottom;
  box-shadow: 0 0 22px 4px #bc834b21;
}

.lt-dawn-horizon {
  position: absolute;
  left: -583px;
  top: 414px;
  width: 1840px;
  height: 1400px;
  border: 1.4px solid #bd966575;
  border-radius: 50%;
  background: radial-gradient(ellipse at 55% 0, #282016e6, #0c0b09 34%, #080807 62%);
  box-shadow: 0 -2px 7px #e8b67b69, 0 -14px 44px #d99d5230, inset 0 6px 17px #edb27413;
}

.lt-introduction {
  position: absolute;
  inset: 0;
}

.lt-introduction :deep(.r-brand) {
  position: absolute;
  left: 105px;
  top: 179px;
  gap: 26px;
}

.lt-introduction :deep(.r-emblem) {
  width: 150px;
  height: 145px;
  color: #dba064;
  filter: none;
}

.lt-introduction :deep(.r-emblem path:nth-of-type(2)),
.lt-introduction :deep(.r-emblem path:nth-of-type(3)) {
  display: none;
}

.lt-introduction :deep(.r-brand-word) {
  font-size: 56px;
  font-weight: 650;
  line-height: 1.18;
}

.lt-introduction :deep(.r-brand-word small) {
  margin-top: 7px;
  font-size: 32px;
  color: #eee5d8;
}

.lt-login .lt-brand-description {
  position: absolute;
  left: 105px;
  top: 345px;
  font-size: 32px;
  font-weight: 500;
  line-height: 1.5;
  white-space: nowrap;
  color: #f7e8cf;
}

.lt-features {
  position: absolute;
  top: 463px;
  left: 105px;
  width: 513px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  list-style: none;
  padding: 0;
  margin: 0;
}

.lt-features li {
  display: flex;
  align-items: center;
  gap: 40px;
  height: 89px;
  padding: 0 27px;
  border-radius: 25px;
  background: linear-gradient(110deg, #28231b9e, #211d164d);
}

.lt-features h2 {
  font-size: 25px;
  line-height: 1.45;
  font-weight: 500;
  color: #f6f0e5;
}

.lt-features p {
  color: #d1cbc1;
  font-size: 21px;
  line-height: 1.5;
}

.lt-login .lt-deployment {
  position: absolute;
  top: 866px;
  left: 105px;
  width: 556px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: #938b80;
  font-size: 20px;
  white-space: nowrap;
}

.lt-deployment-rule {
  flex: 1;
  height: 1px;
  background: #6c6253;
}

.lt-deployment .r-icon {
  margin-left: 4px;
}

.lt-login-form {
  position: absolute;
  left: 838px;
  top: 139px;
  width: 627px;
  height: 745px;
  border: 2px solid transparent;
  border-radius: 38px;
  background: linear-gradient(120deg, #2a241c, #11100e 55%, #211c16) padding-box,
    linear-gradient(135deg, #a29a8d, #4c463d 27%, #2d2924 69%, #373029) border-box;
}

.lt-login-form h1 {
  position: absolute;
  left: 53px;
  top: 39px;
  font-size: 56px;
  line-height: 1.4;
  font-weight: 650;
  color: #fff4e1;
}

.lt-login .lt-login-subtitle {
  position: absolute;
  top: 120px;
  left: 53px;
  font-size: 28px;
  color: #b2aa9e;
}

.lt-login-field {
  position: absolute;
  left: 48px;
  right: 48px;
  height: 85px;
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 26px;
  border: 2px solid transparent;
  border-radius: 44px;
  background: linear-gradient(110deg, #211d17, #151412) padding-box,
    linear-gradient(120deg, #72695c, #35312b 75%) border-box;
  color: #e3d9c8;
}

.lt-login-name {
  top: 205px;
}

.lt-login-password {
  top: 319px;
}

.lt-login-field input {
  min-width: 0;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  outline: none;
  font-size: 27px;
}

.lt-login-field input::placeholder {
  color: #78746c;
  opacity: 1;
}

.lt-login-field:focus-within {
  outline: 1px solid #d2a36e;
  outline-offset: 3px;
}

.lt-login-field input:focus-visible {
  outline: none;
}

.lt-login-form .lt-password-toggle {
  display: grid;
  place-items: center;
  flex: none;
  width: 34px;
  height: 40px;
  color: #6d6a64;
}

.lt-login-form .lt-enter,
.lt-login-form .lt-reconnect {
  position: absolute;
  left: 48px;
  right: 48px;
  height: 76px;
  border-radius: 40px;
  font-size: 27px;
}

.lt-login-form .lt-enter {
  top: 446px;
  background: linear-gradient(170deg, #dca368, #cb8d54 53%, #b37b48);
  border: 1px solid #e6b17c;
  box-shadow: inset 0 1px 1px #f8d0a7a6;
  color: #100a04;
  font-weight: 650;
}

.lt-login-form .lt-reconnect {
  top: 540px;
  border: 2px solid transparent;
  background: linear-gradient(120deg, #191713, #1b1712) padding-box,
    linear-gradient(160deg, #6e665b, #39342d 75%) border-box;
  color: #f0e7d7;
}

.lt-login .lt-account-note {
  position: absolute;
  top: 645px;
  left: 48px;
  right: 48px;
  padding-top: 22px;
  border-top: 1px solid #49403466;
  color: #938b80;
  text-align: center;
  font-size: 20px;
}

.lt-today {
  color: #eeeae3;
}

.lt-today-heading {
  position: absolute;
  top: 115px;
  left: 19px;
  right: 41px;
  height: 88px;
  display: flex;
  align-items: flex-start;
}

.lt-today-heading h1 {
  font-size: 56px;
  line-height: 1.25;
  font-weight: 650;
  color: #f5f0e9;
}

.lt-today-date {
  margin-left: 21px;
  margin-top: 7px;
  color: #a8a39c;
}

.lt-today-date p:first-child {
  font-size: 27.5px;
  font-weight: 600;
  line-height: 1.4;
}

.lt-today-date p:last-child {
  margin-top: 3px;
  font-size: 19px;
  font-weight: 500;
  line-height: 1.5;
}

.lt-today-actions {
  display: flex;
  gap: 20px;
  margin-left: auto;
  padding-top: 18px;
}

.lt-today-actions button {
  height: 62px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  border-radius: 32px;
  font-size: 23px;
  font-weight: 600;
}

.lt-today-actions .lt-quick-button {
  width: 186px;
  background: linear-gradient(130deg, #201b15b3, #171410);
  border: 1px solid #3c3329;
}

.lt-today-actions .lt-focus-button {
  width: 184px;
  background: linear-gradient(155deg, #efae6e, #d38a4c 70%, #eda96c);
  border: 1px solid #efb47d;
  color: #171006;
}

.lt-focus-button .r-icon,
.lt-resume-button .r-icon {
  fill: #805326;
  color: #805326;
  stroke-width: 1px;
}

.lt-day-summary {
  position: absolute;
  left: 14px;
  right: 31px;
  top: 221px;
  height: 71px;
  display: flex;
  align-items: center;
  gap: 33px;
  padding: 0 25px;
  border: 1px solid #4a3828;
  border-radius: 21px;
  background: radial-gradient(ellipse 75px 90px at 0 0, #bc83536b, transparent), linear-gradient(110deg, #2d221aab, #1c1a17c4 26%, #211d19c4);
  white-space: nowrap;
  font-size: 19px;
}

.lt-day-summary button,
.lt-summary-budget {
  display: inline-flex;
  align-items: center;
}

.lt-summary-interview {
  gap: 13px;
}

.lt-summary-interview strong {
  font-size: 19px;
  font-weight: 600;
}

.lt-summary-interview > span {
  margin-left: 0;
  font-size: 19.5px;
}

.lt-summary-budget,
.lt-summary-sources {
  gap: 11px;
}

.lt-day-summary .r-icon {
  color: #dca571;
}

.lt-summary-divider {
  width: 1px;
  height: 23px;
  flex: none;
  background: #65574a80;
}

.lt-panel {
  position: absolute;
  border: 1px solid #3d352c;
  border-radius: 21px;
  background: linear-gradient(120deg, #282421d9, #1c1a18e8 70%);
  overflow: hidden;
}

.lt-continue,
.lt-task-panel {
  left: 14px;
  width: 795px;
}

.lt-continue {
  top: 311px;
  height: 263px;
  background: radial-gradient(ellipse 250px 260px at 0 0, #81502d45, transparent),
    radial-gradient(ellipse at 90% 0, #5a4d4226, transparent 70%), linear-gradient(120deg, #25221fde, #1b1917ed);
}

.lt-continue h2,
.lt-task-panel h2,
.lt-interview-panel h2 {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 27px;
  line-height: 1.4;
  font-weight: 600;
}

.lt-continue h2 {
  position: absolute;
  left: 25px;
  top: 22px;
}

.lt-continue h2 > .r-icon {
  color: #eac5a2;
}

.lt-practice-ring {
  position: absolute;
  top: 84px;
  left: 25px;
  width: 154px;
  height: 154px;
}

.lt-practice-ring svg {
  display: block;
  width: 154px;
  height: 154px;
  overflow: visible;
}

.lt-practice-ring circle {
  fill: none;
  stroke-width: 11px;
}

.lt-ring-track {
  stroke: #644932;
}

.lt-ring-progress {
  stroke: url(#lt-ring-gradient);
  stroke-linecap: round;
}

.lt-practice-ring > div {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  padding-top: 2px;
}

.lt-practice-ring strong {
  font-size: 32px;
  line-height: 1.3;
  font-weight: 600;
}

.lt-practice-ring span {
  font-size: 21px;
  line-height: 1.5;
}

.lt-practice-copy {
  position: absolute;
  left: 211px;
  top: 83px;
  z-index: 1;
}

.lt-practice-copy h3 {
  font-size: 29px;
  font-weight: 600;
  line-height: 1.5;
  white-space: nowrap;
}

.lt-practice-copy p {
  margin-top: 9.5px;
  color: #aaa69f;
  font-size: 23px;
  font-weight: 500;
  line-height: 1.5;
}

.lt-practice-actions {
  display: flex;
  align-items: center;
  gap: 27px;
  margin-top: 20px;
}

.lt-practice-actions .lt-resume-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  height: 55px;
  width: 194px;
  border-radius: 28px;
  border: 1px solid #dca26b;
  background: linear-gradient(150deg, #d99d63, #c17b41 68%, #df9c61);
  font-size: 22px;
  font-weight: 600;
  color: #1c1006;
}

.lt-previous-button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 21px;
  color: #bcb8b1;
  white-space: nowrap;
}

.lt-practice-orbit {
  position: absolute;
  right: 0;
  top: 0;
  width: 795px;
  height: 263px;
  pointer-events: none;
  mask-image: linear-gradient(0deg, transparent, #000 65%);
}

.lt-practice-orbit > ellipse {
  stroke-opacity: .7;
}

.lt-task-panel {
  top: 592px;
  height: 385px;
  padding: 21px 25px 0;
  background: radial-gradient(ellipse at 15% 0, #49444021, transparent 70%), linear-gradient(120deg, #272524db, #1b1917ed);
}

.lt-task-panel h2 {
  gap: 15px;
}

.lt-heading-check {
  display: inline-grid;
  place-items: center;
  width: 27px;
  height: 27px;
  border-radius: 50%;
  background: #bdbcb6;
  color: #27241f;
}

.lt-task-tabs {
  display: flex;
  gap: 13px;
  margin-top: 18px;
}

.lt-task-tabs button {
  height: 39px;
  padding: 0 20px;
  border: 1px solid #3c342a;
  border-radius: 21px;
  color: #aca79f;
  font-size: 20px;
  line-height: 1.4;
  white-space: nowrap;
}

.lt-task-tabs .lt-tab-selected {
  background: linear-gradient(120deg, #987047, #69462b);
  border-color: #ac7c4d;
  color: #fbf1e3;
  font-weight: 600;
}

.lt-task-table {
  width: 100%;
  margin-top: 17px;
  border-collapse: collapse;
  table-layout: fixed;
  text-align: left;
  font-size: 19px;
}

.lt-task-name-column { width: 42.9%; }
.lt-task-source-column { width: 17.2%; }
.lt-task-duration-column { width: 14.3%; }
.lt-task-due-column { width: 17.7%; }
.lt-task-action-column { width: 7.9%; }

.lt-task-table thead tr {
  height: 45px;
  border-bottom: 1px solid #5c514442;
}

.lt-task-table th {
  font-size: 20px;
  font-weight: 500;
  color: #aaa69f;
  padding: 0 4px 1px;
  transform: translateY(-5px);
}

.lt-task-table td {
  height: 47px;
  padding: 0 4px;
  border-bottom: 1px solid #3e36252b;
  vertical-align: middle;
  white-space: nowrap;
}

.lt-task-table tbody tr:last-child td {
  border-bottom: 0;
}

.lt-task-table th:last-child,
.lt-task-table td:last-child {
  text-align: center;
}

.lt-task-link {
  display: inline-flex;
  align-items: center;
  gap: 14px;
  text-align: left;
  font-size: 19px;
  white-space: nowrap;
}

.lt-task-link .r-icon {
  color: #9db4b9;
}

.lt-task-link > span {
  transform: scaleX(.95);
  transform-origin: left center;
}

.lt-task-recall .lt-task-link,
.lt-task-recall .lt-task-link .r-icon {
  color: #e89b4b;
}

.lt-task-note .lt-task-link .r-icon {
  color: #aebbb7;
}

.lt-task-practice .lt-task-link .r-icon {
  color: #559b87;
}

.lt-source-tag {
  display: inline-block;
  padding: 1px 10px;
  border-radius: 16px;
  font-size: 17px;
  line-height: 1.5;
}

.lt-source-recall {
  background: #6f4d386e;
  color: #dac1a9;
}

.lt-source-note,
.lt-source-practice {
  background: #285456b0;
  color: #add2d3;
}

.lt-source-schedule {
  background: #3e6089bd;
  color: #bfd5f0;
}

.lt-due-today {
  color: #e4a05c;
}

.lt-task-more {
  width: 36px;
  height: 36px;
  color: #a9a199;
}

.lt-interview-panel {
  left: 828px;
  top: 311px;
  width: 429px;
  height: 666px;
  background: radial-gradient(ellipse at 0 0, #49403921, transparent 60%), linear-gradient(120deg, #211e1abd, #171512d9 60%);
}

.lt-interview-panel h2 {
  position: absolute;
  left: 28px;
  top: 23px;
  gap: 14px;
  color: #c1b7a9;
}

.lt-interview-panel h2 .r-icon {
  color: #e1a675;
}

.lt-interview-overview {
  position: absolute;
  top: 88px;
  left: 28px;
  right: 24px;
  display: flex;
  align-items: flex-start;
  gap: 29px;
  text-align: left;
}

.lt-interview-calendar {
  display: flex;
  align-items: center;
  flex-direction: column;
  justify-content: center;
  flex: none;
  width: 128px;
  height: 132px;
  padding-bottom: 6px;
  border: 1px solid #b77b43;
  border-radius: 22px;
  background: radial-gradient(ellipse at 15% 0, #9e6b4340, transparent 65%), radial-gradient(ellipse at 70% 70%, #4f39282e, #241d17cc 75%);
  box-shadow: inset 0 1px 3px #e6ac7e50, inset 0 0 24px #a7774b24;
  color: #f6dfc5;
}

.lt-interview-calendar strong {
  font-size: 60px;
  line-height: 1.1;
  font-weight: 650;
}

.lt-interview-calendar > span {
  margin-top: 1px;
  font-size: 25px;
  color: #d2b59b;
  line-height: 1.4;
}

.lt-interview-description {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 9px;
  min-width: 0;
}

.lt-interview-description > strong {
  font-size: 24px;
  font-weight: 600;
  white-space: nowrap;
  line-height: 1.5;
}

.lt-interview-description > span {
  font-size: 21px;
  line-height: 1.5;
  color: #b5afa6;
}

.lt-interview-description .lt-interview-time {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 9px;
  color: #e4ded5;
  font-size: 20px;
  white-space: nowrap;
}

.lt-interview-rule {
  position: absolute;
  left: 27px;
  right: 26px;
  top: 249px;
  height: 1px;
  background: #51422c59;
}

.lt-interview-steps {
  position: absolute;
  top: 279px;
  left: 25px;
  right: 0;
  display: flex;
  justify-content: space-between;
}

.lt-interview-steps::before {
  content: "";
  position: absolute;
  top: 24px;
  left: 68px;
  right: 41px;
  height: 3px;
  background: linear-gradient(90deg, #936c43, #413b32 40%);
}

.lt-interview-steps button {
  position: relative;
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 11px;
  width: 133px;
  flex: none;
  color: #8f8a81;
  font-size: 19px;
  white-space: nowrap;
}

.lt-step-symbol {
  display: inline-grid;
  place-items: center;
  width: 50px;
  height: 50px;
  border: 1px solid #6a6458;
  border-radius: 50%;
  background: linear-gradient(135deg, #494743, #282725);
  color: #a6a39b;
}

.lt-interview-steps .lt-step-selected {
  color: #e7a361;
}

.lt-step-selected .lt-step-symbol {
  border-color: #eda752;
  background: linear-gradient(140deg, #a5784b, #775535);
  color: #f2dfc6;
  box-shadow: 0 0 7px #c9853f36;
}

.lt-interview-capacity {
  position: absolute;
  top: 413px;
  left: 27px;
  right: 26px;
}

.lt-interview-capacity p {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 22px;
  line-height: 1.5;
}

.lt-interview-capacity strong {
  font-size: 21px;
  font-weight: 600;
}

.lt-capacity-track {
  margin-top: 21px;
  height: 16px;
  overflow: hidden;
  border-radius: 9px;
  background: #292522;
}

.lt-capacity-track > span {
  display: block;
  width: 54%;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(0deg, #c88750, #e6a46c);
}

.lt-interview-note {
  position: absolute;
  top: 515px;
  left: 18px;
  right: 16px;
  height: 74px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 7px 8px 7px 21px;
  border: 1px solid #3b352d;
  border-radius: 37px;
  background: linear-gradient(110deg, #312d2ac9, #25221fbf);
  color: #958d81;
}

.lt-interview-note input {
  border: 0;
  outline: none;
  min-width: 0;
  width: 100%;
  background: transparent;
  font-size: 21px;
}

.lt-interview-note input::placeholder {
  color: #948c80;
  opacity: 1;
}

.lt-interview-note button {
  display: grid;
  place-items: center;
  width: 57px;
  height: 57px;
  border: 1px solid #a2764c66;
  border-radius: 50%;
  background: linear-gradient(130deg, #9d734b, #735135);
  color: #edccaa;
  flex: none;
}

.lt-record-backdrop {
  position: absolute;
  inset: 100px 0 0;
  z-index: 20;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 155px;
  background: #0009;
}

.lt-record-dialog {
  width: 570px;
  padding: 24px;
  border: 1px solid #69533b;
  border-radius: 20px;
  background: #211c16;
}

.lt-record-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.lt-record-dialog textarea {
  display: block;
  resize: none;
  width: 100%;
  padding: 15px;
  border: 1px solid #6c5943;
  border-radius: 12px;
  background: #15120e;
  color: #eee7dd;
  font-size: 20px;
  line-height: 1.6;
}

.lt-record-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
}
</style>
