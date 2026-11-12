<script setup lang="ts">
import { computed, ref } from 'vue'
import RIcon from './RIcon.vue'
import RBrand from './RBrand.vue'

withDefaults(defineProps<{ scene?: number }>(), { scene: 29 })
const emit = defineEmits<{ navigate: [scene: number]; notify: [message: string] }>()

const phones = [
  { id: 'today', label: '今日', time: '9:41' },
  { id: 'detail', label: '缓存一致性', time: '9:41' },
  { id: 'recall', label: '回忆卡', time: '9:41' },
]
const navigation = [
  { label: '今日', icon: 'house', scene: 2 },
  { label: '知识', icon: 'book-open', scene: 10 },
  { label: '训练', icon: 'calendar-check', scene: 17 },
  { label: '复盘', icon: 'credit-card', scene: 21 },
  { label: '成长', icon: 'sprout', scene: 24 },
]
const tasks = ref([
  { id: 'review', title: '复习：缓存一致性', subtitle: '预计 5 分钟 · 知识', icon: 'file-text', done: false },
  { id: 'interview', title: '进行一次模拟面试', subtitle: '预计 15 分钟 · 训练', icon: 'play', done: false },
])
const ratings = [
  { id: 'forgot', label: '忘记', icon: 'help-circle' },
  { id: 'hard', label: '困难', icon: 'chart-no-axes-column' },
  { id: 'remembered', label: '记得', icon: 'check' },
  { id: 'easy', label: '熟悉', icon: 'star' },
]
const activeTab = ref('概览')
const answer = ref('')
const rating = ref('')
const drawerOpen = ref(true)
const completed = ref(false)
const moreOpen = ref(false)
const taskCount = computed(() => tasks.value.filter(task => !task.done).length)
const canComplete = computed(() => rating.value !== '' && !completed.value)

function openRecall() {
  drawerOpen.value = true
  completed.value = false
}
function resetRecall() {
  answer.value = ''
  rating.value = ''
  completed.value = false
}
function completeRecall() {
  if (!canComplete.value) return
  completed.value = true
  const task = tasks.value.find(item => item.id === 'review')
  if (task) task.done = true
  emit('notify', '回忆已完成')
}
function selectRating(id: string) {
  rating.value = id
  completed.value = false
}
function openTask(id: string) {
  if (id === 'review') {
    openRecall()
    activeTab.value = '概览'
  } else {
    emit('navigate', 17)
  }
}
function showNote() { emit('navigate', 11) }
</script>

<template>
  <section class="mobile-scene" aria-label="移动端三手机并列展示">
    <!-- 三台手机是参考图的三个同时可见状态，不随容器宽度折叠或轮播。 -->
    <article v-for="phone in phones" :key="phone.id" class="mobile-phone" :class="`phone-${phone.id}`" :aria-label="`手机：${phone.label}`">
      <span class="phone-button phone-mute" aria-hidden="true" />
      <span class="phone-button phone-volume-up" aria-hidden="true" />
      <span class="phone-button phone-volume-down" aria-hidden="true" />
      <span class="phone-button phone-power" aria-hidden="true" />
      <div class="phone-screen">
        <div class="phone-status" :class="{ dimmed: phone.id === 'recall' && drawerOpen }" aria-label="9点41分，网络及电量正常">
          <span class="phone-time">{{ phone.time }}</span>
          <div class="phone-status-icons">
            <span class="phone-signal" aria-hidden="true"><i /><i /><i /><i /></span>
            <RIcon name="wifi" :size="21" :stroke-width="2.7" />
            <span class="phone-battery" aria-hidden="true"><i /></span>
          </div>
        </div>

        <template v-if="phone.id === 'today'">
          <header class="mobile-home-header">
            <RBrand :variant="1" />
            <button class="mobile-avatar" aria-label="设置与数据" @click="emit('navigate', 28)">林</button>
          </header>
          <div class="mobile-home-title"><h1>今日</h1><p>持续练习，向更好的自己进发</p></div>
          <button class="mobile-continue" @click="openRecall">
            <span class="mobile-play"><RIcon name="play" :size="31" :stroke-width="1.7" /></span>
            <span class="mobile-continue-copy"><span>继续学习</span><strong>缓存删除失败以后</strong></span>
            <RIcon name="chevron-right" :size="26" />
          </button>
          <section class="mobile-tasks">
            <div class="mobile-section-heading"><h2>待办任务 <span class="mobile-count">{{ taskCount }}</span></h2><button @click="emit('navigate', 2)">全部<RIcon name="chevron-right" :size="19" /></button></div>
            <div class="mobile-task-list">
              <div v-for="task in tasks" :key="task.id" class="mobile-task-row" :class="{ 'is-done': task.done }">
                <button class="mobile-task-check" :class="{ checked: task.done }" :aria-label="`${task.done ? '取消完成' : '完成'}${task.title}`"
                  :aria-pressed="task.done" @click="task.done = !task.done"><RIcon v-if="task.done" name="check" :size="20" /></button>
                <button class="mobile-task-copy" @click="openTask(task.id)"><span>{{ task.title }}</span><small>{{ task.subtitle }}</small></button>
                <button class="mobile-task-open" :aria-label="task.title" @click="openTask(task.id)"><RIcon :name="task.icon" :size="22" /></button>
              </div>
            </div>
          </section>
          <section class="mobile-recent">
            <div class="mobile-section-heading"><h2>最近学习</h2><button @click="emit('navigate', 26)">查看全部<RIcon name="chevron-right" :size="19" /></button></div>
            <button class="mobile-recent-note" @click="showNote">
              <span class="mobile-note-icon blue-note"><RIcon name="file-text" :size="28" /></span>
              <span><strong>分布式缓存策略</strong><small>昨天 21:30 · 知识</small></span>
              <RIcon name="chevron-right" :size="25" />
            </button>
          </section>
          <nav class="mobile-bottom-nav" aria-label="移动端主导航">
            <button v-for="item in navigation" :key="item.label" :class="{ selected: item.label === '今日' }" @click="emit('navigate', item.scene)">
              <svg v-if="item.label === '成长'" class="mobile-growth-icon" width="27" height="27" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m2 8 3 13h14l3-13-6 4-4-9-4 9Z" /><path d="M6 17h12" /></svg>
              <RIcon v-else :name="item.icon" :size="27" /><span>{{ item.label }}</span>
            </button>
          </nav>
        </template>

        <template v-else-if="phone.id === 'detail'">
          <header class="mobile-detail-header">
            <button class="mobile-circle-button" aria-label="返回知识星图" @click="emit('navigate', 9)"><RIcon name="arrow-left" :size="29" /></button>
            <button class="mobile-circle-button" aria-label="知识点更多操作" :aria-expanded="moreOpen" @click="moreOpen = !moreOpen"><RIcon name="ellipsis" :size="28" :stroke-width="3.3" /></button>
          </header>
          <div v-if="moreOpen" class="mobile-detail-menu"><button @click="emit('navigate', 12)">打开知识点<RIcon name="external-link" :size="17" /></button><button @click="openRecall(); moreOpen = false">回忆卡<RIcon name="credit-card" :size="18" /></button></div>
          <h1 class="mobile-detail-title">缓存一致性</h1>
          <nav class="mobile-detail-tabs" aria-label="知识点标签">
            <button v-for="tab in ['概览', '关系', '练习']" :key="tab" :class="{ selected: activeTab === tab }"
              :aria-pressed="activeTab === tab" @click="activeTab = tab">{{ tab }}</button>
          </nav>
          <div v-if="activeTab === '概览'" class="mobile-detail-content">
            <section class="mobile-definition mobile-content-section">
              <h2><span class="mobile-definition-icon"><RIcon name="file-text" :size="22" /></span>定义</h2>
              <p>缓存一致性是指在分布式系统中，多个缓存实例之间的数据保持一致的能力，避免出现脏数据或数据不一致的问题。</p>
            </section>
            <section class="mobile-linked-note mobile-content-section">
              <h2><RIcon name="link" :size="23" />关联笔记</h2>
              <button @click="showNote"><span class="mobile-note-icon"><RIcon name="file-text" :size="27" /></span><span><strong>分布式缓存策略</strong><small>昨天 21:30</small></span><RIcon name="chevron-right" :size="23" /></button>
            </section>
            <section class="mobile-key-points mobile-content-section">
              <h2><RIcon name="lightbulb" :size="25" />核心要点</h2>
              <ul>
                <li>常见方案：Cache Aside、Cache Through</li>
                <li>一致性策略：先更新数据库，再删除缓存</li>
                <li>避免缓存击穿和雪崩</li>
              </ul>
            </section>
          </div>
          <div v-else-if="activeTab === '关系'" class="mobile-detail-content mobile-related-content">
            <section class="mobile-content-section">
              <h2><RIcon name="share-2" :size="23" />关系</h2>
              <button v-for="label in ['分布式', '数据库', 'JVM', 'Spring']" :key="label" @click="emit('navigate', 9)">{{ label }}<RIcon name="chevron-right" :size="21" /></button>
            </section>
          </div>
          <div v-else class="mobile-detail-content mobile-exercise-content">
            <section class="mobile-content-section"><h2><RIcon name="credit-card" :size="23" />回忆卡</h2><button @click="openRecall">缓存删除失败后，<br />如何确认恢复？<RIcon name="chevron-right" :size="23" /></button></section>
          </div>
          <button class="mobile-practice" @click="openRecall"><RIcon name="play" :size="24" />练这一点</button>
        </template>

        <template v-else>
          <header class="mobile-recall-background-header" :class="{ dimmed: drawerOpen }">
            <button aria-label="返回" @click="drawerOpen ? drawerOpen = false : emit('navigate', 16)"><RIcon name="arrow-left" :size="27" /></button>
            <button aria-label="打开回忆卡" @click="drawerOpen = true"><RIcon name="plus" :size="24" /></button>
          </header>
          <button v-if="!drawerOpen" class="mobile-reopen-recall" @click="drawerOpen = true"><RIcon name="credit-card" :size="29" />回忆卡</button>
          <section v-if="drawerOpen" class="mobile-recall-drawer" aria-label="回忆卡抽屉">
            <button class="mobile-drawer-handle" aria-label="收起回忆卡" @click="drawerOpen = false"><span /></button>
            <h2 class="mobile-recall-heading"><RIcon name="copy" :size="31" />回忆卡</h2>
            <h1 class="mobile-recall-question">缓存删除失败后，<br />如何确认恢复？</h1>
            <textarea v-model="answer" class="mobile-recall-answer" placeholder="写下你的答案..." aria-label="回忆卡答案" :readonly="completed" />
            <div class="mobile-rating-options" aria-label="回忆程度">
              <button v-for="item in ratings" :key="item.id" :class="[item.id, { selected: rating === item.id }]"
                :aria-pressed="rating === item.id" @click="selectRating(item.id)">
                <span v-if="item.id === 'forgot'" class="mobile-question-symbol" aria-hidden="true">?</span>
                <RIcon v-else :name="item.icon" :size="29" :stroke-width="item.id === 'remembered' ? 3.4 : 2.3" />
                <span>{{ item.label }}</span>
              </button>
            </div>
            <footer class="mobile-recall-footer">
              <button class="mobile-reset" aria-label="重置回忆卡" @click="resetRecall"><RIcon name="rotate-ccw" :size="26" /></button>
              <button class="mobile-complete" :disabled="!canComplete" @click="completeRecall">{{ completed ? '已完成' : '完成' }}</button>
            </footer>
          </section>
        </template>
        <div class="phone-home-indicator" :class="{ bright: phone.id === 'detail' }" aria-hidden="true" />
      </div>
    </article>
  </section>
</template>

<style scoped>
.mobile-scene { position: absolute; inset: 0; width: 1536px; height: 1024px; overflow: hidden; color: #f4f4f1; background: radial-gradient(ellipse at -9% 3%, #6953426e, transparent 34%), radial-gradient(ellipse at 105% 26%, #9d622f5c, transparent 25%), radial-gradient(ellipse at 8% 106%, #9b653b61, transparent 33%), radial-gradient(ellipse at 72% 104%, #73513924, transparent 38%), #0b0a09; font-size: 20px; }
.mobile-phone { position: absolute; border: 2px solid #605b52; border-radius: 82px; background: linear-gradient(105deg, #66615a, #151412 4%, #070707 49%, #24211d 97%, #6c6458); box-shadow: 0 7px 8px #000b, 0 0 0 1px #161615, inset 0 0 0 2px #22201c, inset 4px 1px 3px #9d968c85, inset -4px 0 3px #b8ab9070; }
.phone-today { left: 61px; top: 62px; width: 447px; height: 916px; transform: rotate(-.15deg); }
.phone-detail { left: 541px; top: 42px; width: 459px; height: 951px; border-radius: 86px; }
.phone-recall { left: 1033px; top: 65px; width: 451px; height: 912px; }
.phone-screen { position: absolute; inset: 13px 12px; overflow: hidden; border: 1px solid #080807; border-radius: 66px; background: radial-gradient(ellipse at 68% 52%, #45302432, transparent 58%), linear-gradient(160deg, #0a0908, #13100d 78%, #151411); box-shadow: 0 0 0 5px #030303; }
.phone-detail .phone-screen { inset: 14px 13px; border-radius: 69px; background: radial-gradient(ellipse at 72% 67%, #49312338, transparent 66%), #0c0b09; }
.phone-recall .phone-screen { inset: 13px 12px; background: #0a0908; }
.phone-button { position: absolute; z-index: -1; width: 3px; border-radius: 3px; background: linear-gradient(90deg, #b9afa1, #38342d); }
.phone-mute { left: -5px; top: 186px; height: 29px; }
.phone-volume-up { left: -5px; top: 245px; height: 55px; }
.phone-volume-down { left: -5px; top: 317px; height: 57px; }
.phone-power { right: -5px; top: 260px; height: 106px; }
.phone-status { position: absolute; z-index: 4; top: 18px; left: 41px; right: 24px; height: 27px; display: flex; align-items: center; justify-content: space-between; }
.phone-detail .phone-status { top: 16px; left: 43px; right: 26px; }
.phone-recall .phone-status { top: 17px; left: 43px; right: 25px; }
.phone-time { font-size: 20px; font-weight: 650; line-height: 25px; }
.phone-status-icons { display: flex; align-items: center; gap: 7px; }
.phone-status-icons > .r-icon { margin-top: -1px; height: 19px; }
.phone-signal { height: 17px; display: flex; gap: 2px; align-items: flex-end; }
.phone-signal i { width: 4px; border-radius: 1px; height: 6px; background: currentColor; }
.phone-signal i:nth-child(2) { height: 10px; }
.phone-signal i:nth-child(3) { height: 14px; }
.phone-signal i:nth-child(4) { height: 17px; }
.phone-battery { position: relative; display: inline-block; width: 27px; height: 14px; border: 1px solid #d8d8d3b3; border-radius: 4px; }
.phone-battery::after { content: ""; position: absolute; right: -3px; top: 4px; width: 2px; height: 5px; border-radius: 0 2px 2px 0; background: #d8d8d3b3; }
.phone-battery i { position: absolute; inset: 1.3px; background: currentColor; border-radius: 2px; }
.dimmed { opacity: .48; }
.phone-home-indicator { position: absolute; z-index: 8; bottom: 7px; left: 50%; width: 145px; height: 6px; transform: translateX(-50%); background: #7d797336; border-radius: 5px; }
.phone-home-indicator.bright { bottom: 10px; width: 150px; background: #a9a5a4aa; }
.mobile-home-header { position: absolute; left: 21px; right: 21px; top: 71px; height: 50px; display: flex; align-items: center; justify-content: space-between; }
.mobile-home-header :deep(.r-brand) { gap: 8px; }
.mobile-home-header :deep(.r-emblem) { width: 41px; height: 45px; color: #dba774; filter: none; }
.mobile-home-header :deep(.r-brand-word) { font-size: 26px; line-height: 29px; color: #ffe4bf; }
.mobile-home-header :deep(.r-brand-word small) { font-size: 13px; line-height: 16px; margin-top: 0; color: #e6e2dc; }
.mobile-avatar { display: grid; place-items: center; width: 49px; height: 49px; border: 1px solid #ffffff06 !important; border-radius: 50%; background: linear-gradient(120deg, #312c25, #24211d) !important; font-size: 25px; font-weight: 600; }
.mobile-home-title { position: absolute; top: 152px; left: 20px; right: 18px; }
.mobile-home-title h1 { font-size: 47px; font-weight: 650; line-height: 63px; }
.mobile-home-title p { margin-top: 1px; font-size: 19px; color: #b5b2ac; line-height: 31px; white-space: nowrap; }
.mobile-continue { position: absolute; left: 20px; right: 20px; top: 267px; height: 124px; display: flex; align-items: center; padding: 0 18px 0 20px !important; gap: 18px; border: 1px solid #b9824b !important; border-radius: 23px; background: radial-gradient(ellipse at 5% 95%, #cc8c4f38, transparent 56%), radial-gradient(ellipse at 100% 10%, #d69b655e, transparent 43%), linear-gradient(120deg, #302418, #241a12 58%, #4b3524) !important; box-shadow: inset 0 0 12px #edbe8b0f; overflow: hidden; text-align: left; }
.mobile-continue::before, .mobile-continue::after { content: ""; position: absolute; pointer-events: none; left: -90px; top: -122px; width: 520px; height: 230px; border: 1px solid #d5a06712; border-radius: 50%; transform: rotate(17deg); box-shadow: 0 6px 0 #d5a0670b, 0 12px 0 #d5a0670b, 0 18px 0 #d5a0670b, 0 24px 0 #d5a0670b, 0 30px 0 #d5a0670b, 0 36px 0 #d5a0670b; }
.mobile-continue::after { left: -145px; top: 44px; transform: rotate(19deg); }
.mobile-play { display: grid; place-items: center; flex: none; width: 64px; height: 65px; border: 1.6px solid #e9ac6d; border-radius: 50%; color: #ffd7a6; background: #5b3d2540; }
.mobile-play .r-icon { fill: currentColor; margin-left: 4px; }
.mobile-continue-copy { position: relative; z-index: 1; display: flex; flex-direction: column; gap: 1px; }
.mobile-continue-copy > span { color: #f8eee1; font-size: 21px; line-height: 31px; }
.mobile-continue-copy strong { color: #fff8ef; font-size: 23px; font-weight: 650; line-height: 35px; white-space: nowrap; }
.mobile-continue > .r-icon { margin-left: auto; color: #d7a172; flex: none; }
.mobile-tasks { position: absolute; top: 421px; left: 20px; right: 20px; }
.mobile-section-heading { display: flex; align-items: center; justify-content: space-between; height: 35px; }
.mobile-section-heading h2 { display: flex; align-items: center; gap: 9px; font-size: 21px; font-weight: 650; line-height: 30px; }
.mobile-count { display: grid; place-items: center; min-width: 33px; height: 26px; padding: 0 10px; border-radius: 13px; background: linear-gradient(150deg, #433c33, #2a2723); font-size: 17px; font-weight: 400; }
.mobile-section-heading > button { display: flex; align-items: center; gap: 1px; color: #b8b2a8; font-size: 17px; line-height: 26px; white-space: nowrap; }
.mobile-section-heading > button .r-icon { color: #999184; }
.mobile-task-list { margin-top: 9px; height: 160px; border: 1px solid #ffffff08; border-radius: 20px; background: linear-gradient(120deg, #25221edb, #24201bda); overflow: hidden; }
.mobile-task-row { position: relative; display: flex; align-items: center; gap: 17px; height: 80px; padding: 0 15px 0 17px; }
.mobile-task-row + .mobile-task-row::before { content: ""; position: absolute; top: 0; left: 14px; right: 0; height: 1px; background: #a7917010; }
.mobile-task-check { display: grid; place-items: center; flex: none; width: 31px; height: 32px; border: 2px solid #7f7c748c !important; border-radius: 50%; }
.mobile-task-check.checked { border-color: #ddaa70 !important; color: #22180e; background: #dbac77 !important; }
.mobile-task-copy { display: flex; flex: 1; min-width: 0; flex-direction: column; align-items: flex-start; gap: 2px; text-align: left; }
.mobile-task-copy > span { color: #f1eee6; font-size: 19px; line-height: 28px; white-space: nowrap; }
.mobile-task-copy small { color: #918b81; font-size: 15px; line-height: 23px; white-space: nowrap; }
.mobile-task-row.is-done .mobile-task-copy > span { color: #aaa59b; text-decoration: line-through; }
.mobile-task-open { display: grid; place-items: center; flex: none; width: 37px; height: 38px; border-radius: 50%; background: #ffffff03 !important; color: #c4baaa; }
.mobile-task-row:first-child .mobile-task-open .r-icon { fill: #bfb4a3; stroke: #40382e; }
.mobile-task-row:last-child .mobile-task-open .r-icon { fill: #c9c3b8; stroke: #c9c3b8; width: 19px; }
.mobile-recent { position: absolute; top: 646px; left: 20px; right: 20px; }
.mobile-recent-note { display: flex; align-items: center; gap: 11px; width: 100%; height: 84px; padding: 0 15px !important; margin-top: 7px; border: 1px solid #ffffff05 !important; border-radius: 20px; background: linear-gradient(120deg, #25231fd9, #23211ed9) !important; text-align: left; }
.mobile-note-icon { display: grid; place-items: center; flex: none; width: 46px; height: 47px; border-radius: 13px; background: #3b3025; color: #d0ae87; }
.mobile-note-icon .r-icon { fill: #c2a17e; stroke: #403326; }
.blue-note { width: 43px; height: 46px; color: #b1d2ed; background: linear-gradient(140deg, #343d46, #262d31); }
.blue-note .r-icon { fill: #aacde9; stroke: #263543; }
.mobile-recent-note > span:nth-child(2), .mobile-linked-note > button > span:nth-child(2) { display: flex; flex: 1; min-width: 0; flex-direction: column; }
.mobile-recent-note strong { color: #f1eee7; font-size: 19px; line-height: 29px; font-weight: 400; white-space: nowrap; }
.mobile-recent-note small { color: #8a847b; font-size: 15px; line-height: 24px; }
.mobile-recent-note > .r-icon { color: #a59e91; }
.mobile-bottom-nav { position: absolute; bottom: 10px; left: 18px; right: 15px; height: 91px; display: flex; border-top: 1px solid #9b846014; background: linear-gradient(110deg, #29241d85, #211e1a85); }
.mobile-bottom-nav > button { display: flex; position: relative; flex: 1; flex-direction: column; align-items: center; justify-content: flex-start; padding-top: 18px; gap: 5px; color: #969186; font-size: 16px; line-height: 22px; }
.mobile-bottom-nav > button.selected { color: #e9ae71; background: linear-gradient(130deg, #b4854d20, #6b4b2930); border-radius: 24px; }
.mobile-bottom-nav > button.selected::before { content: ""; position: absolute; top: 0; left: 22px; right: 22px; height: 1px; background: #c9945c; }
.mobile-bottom-nav > button.selected::after { content: ""; position: absolute; bottom: 0; left: 18px; right: 18px; height: 4px; border-radius: 4px; background: #f2b57c; box-shadow: 0 -1px 8px #eca36559; }
.mobile-bottom-nav > button.selected .r-icon { fill: #af7949; stroke: #f2bc85; }
.mobile-growth-icon { flex: none; }
.mobile-detail-header { position: absolute; top: 66px; left: 21px; right: 22px; display: flex; align-items: center; justify-content: space-between; height: 54px; }
.mobile-circle-button { display: grid; place-items: center; width: 53px; height: 54px; border-radius: 50%; border: 1px solid #ffffff07 !important; background: linear-gradient(135deg, #352e25, #24211e) !important; color: #f2e8db; }
.mobile-detail-title { position: absolute; top: 148px; left: 21px; font-size: 42px; font-weight: 650; line-height: 60px; }
.mobile-detail-tabs { position: absolute; top: 222px; left: 20px; right: 21px; height: 56px; display: flex; padding: 3px; border: 1px solid #ffffff14; border-radius: 31px; background: linear-gradient(120deg, #29251f, #2c2721); }
.mobile-detail-tabs > button { flex: 1; color: #bdb7ac; font-size: 20px; line-height: 29px; border-radius: 27px; }
.mobile-detail-tabs > button.selected { background: linear-gradient(170deg, #765238, #b68056 74%, #e5b080) !important; border: 1px solid #e7af76 !important; color: #fff4e8; font-weight: 600; box-shadow: 0 0 0 2px #f2b77915; }
.mobile-detail-content { position: absolute; top: 304px; left: 20px; right: 20px; }
.mobile-content-section { border: 1px solid #ffffff07; border-radius: 20px; background: linear-gradient(115deg, #25221c99, #251f1988); }
.mobile-content-section h2 { display: flex; align-items: center; gap: 10px; color: #f4d7ad; font-size: 20px; font-weight: 600; line-height: 30px; }
.mobile-content-section h2 > .r-icon { color: #e6a369; }
.mobile-definition { height: 178px; padding: 20px 17px 15px; }
.mobile-definition-icon { display: grid; place-items: center; width: 24px; height: 26px; color: #e9b27e; }
.mobile-definition-icon .r-icon { fill: #edb681; stroke: #edb681; }
.mobile-definition p { margin-top: 13px; font-size: 18.5px; color: #f1eee7; line-height: 31px; }
.mobile-linked-note { height: 130px; margin-top: 13px; padding: 16px 16px 10px; }
.mobile-linked-note > button { display: flex; gap: 9px; align-items: center; width: 100%; height: 56px; margin-top: 13px; text-align: left; }
.mobile-linked-note > button .mobile-note-icon { width: 45px; height: 47px; border-radius: 11px; }
.mobile-linked-note strong { color: #eee8df; font-size: 18px; font-weight: 400; line-height: 28px; white-space: nowrap; }
.mobile-linked-note small { color: #898278; font-size: 15px; line-height: 22px; }
.mobile-linked-note > button > .r-icon { margin-left: auto; color: #8f877b; }
.mobile-key-points { height: 173px; margin-top: 19px; padding: 17px 15px; }
.mobile-key-points ul { list-style: none; margin: 12px 0 0; padding: 0; color: #e8e1d6; font-size: 17px; line-height: 34px; }
.mobile-key-points li { white-space: nowrap; }
.mobile-key-points li::before { content: ""; display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: #e6a269; margin: 0 9px 3px 1px; }
.mobile-practice { position: absolute; bottom: 35px; left: 21px; right: 21px; height: 59px; display: flex; align-items: center; justify-content: center; gap: 13px; border: 1px solid #e6b889 !important; border-radius: 31px; background: linear-gradient(115deg, #d69d6b, #bc8254 60%, #a66b43) !important; color: #241609; font-size: 23px; font-weight: 600; box-shadow: inset 0 1px 2px #f2ceaa50; }
.mobile-practice .r-icon { fill: currentColor; stroke-width: 1; }
.mobile-detail-menu { position: absolute; z-index: 5; top: 126px; right: 22px; width: 181px; padding: 5px 13px; border: 1px solid #796047; border-radius: 14px; background: #292019; }
.mobile-detail-menu > button { display: flex; justify-content: space-between; align-items: center; width: 100%; padding: 12px 0; font-size: 17px; color: #ecddc8; }
.mobile-detail-menu > button + button { border-top: 1px solid #ffffff0b; }
.mobile-related-content .mobile-content-section, .mobile-exercise-content .mobile-content-section { padding: 20px 17px; }
.mobile-related-content .mobile-content-section > button { display: flex; justify-content: space-between; width: 100%; padding: 18px 0; border-bottom: 1px solid #ffffff10; font-size: 20px; }
.mobile-exercise-content .mobile-content-section > button { display: flex; align-items: center; justify-content: space-between; width: 100%; padding-top: 18px; font-size: 21px; line-height: 35px; text-align: left; }
.mobile-recall-background-header { position: absolute; top: 79px; left: 31px; right: 29px; height: 32px; display: flex; align-items: center; justify-content: space-between; color: #8d8377; }
.mobile-recall-background-header > button { width: 34px; height: 34px; display: grid; place-items: center; }
.mobile-recall-drawer { position: absolute; top: 109px; left: 0; right: 0; bottom: 0; border: 1px solid #77604729; border-radius: 35px 35px 0 0; background: radial-gradient(ellipse at 100% 4%, #53392429, transparent 52%), linear-gradient(120deg, #1b1815f5, #191612f5 65%, #1d1914f5); }
.mobile-drawer-handle { position: absolute; top: 5px; left: calc(50% - 42px); width: 84px; height: 25px; display: grid; place-items: center; }
.mobile-drawer-handle span { display: block; width: 53px; height: 7px; border-radius: 5px; background: #67615b8a; }
.mobile-recall-heading { position: absolute; top: 65px; left: 23px; display: flex; align-items: center; gap: 15px; font-size: 31px; font-weight: 600; line-height: 43px; }
.mobile-recall-heading .r-icon { color: #e6aa77; fill: #d49a6870; stroke-width: 1.5; }
.mobile-recall-question { position: absolute; top: 134px; left: 23px; right: 19px; font-size: 36px; font-weight: 650; line-height: 52px; white-space: nowrap; }
.mobile-recall-answer { position: absolute; top: 272px; left: 22px; right: 22px; width: calc(100% - 44px); height: 237px; resize: none; padding: 18px 19px; border: 1px solid #9a8a7724; border-radius: 20px; outline: none; background: #ffffff03; color: #f1e7d9; font-size: 20px; line-height: 31px; }
.mobile-recall-answer::placeholder { color: #8b857b; opacity: 1; }
.mobile-recall-answer:focus { border-color: #c59a6b; }
.mobile-rating-options { position: absolute; top: 552px; left: 22px; right: 22px; height: 92px; display: flex; gap: 11px; }
.mobile-rating-options > button { flex: 1; min-width: 0; display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 7px; border: 1px solid #8f7b6233 !important; border-radius: 21px; background: linear-gradient(135deg, #29251f88, #231f1988) !important; color: #e9c29a; }
.mobile-rating-options > button > span:last-child { font-size: 18px; line-height: 25px; white-space: nowrap; }
.mobile-rating-options > button.forgot > span:last-child { color: #e8d9bd; }
.mobile-question-symbol { display: inline-block; height: 31px; font-family: "Segoe UI", sans-serif; font-size: 35px; font-weight: 750; line-height: 31px; }
.mobile-rating-options .hard .r-icon { fill: #d69b63; stroke: #d69b63; }
.mobile-rating-options .remembered { color: #eaae69; }
.mobile-rating-options > button.easy { color: #eea84f; background: linear-gradient(135deg, #42301fa6, #35281c99) !important; border-color: #a275473b !important; }
.mobile-rating-options .easy .r-icon { fill: #f3aa51; stroke: #f3aa51; }
.mobile-rating-options > button.selected { border-color: #dfad76 !important; background: #6c492a8c !important; box-shadow: inset 0 0 0 1px #e0a96c4d; }
.mobile-recall-footer { position: absolute; bottom: 26px; left: 23px; right: 22px; display: flex; justify-content: space-between; align-items: center; }
.mobile-reset { width: 65px; height: 56px; display: grid; place-items: center; border: 1px solid #ffffff04 !important; border-radius: 50%; background: linear-gradient(140deg, #312b24a3, #28231d80) !important; color: #91897d; }
.mobile-complete { min-width: 153px; height: 57px; padding: 0 30px !important; border-radius: 31px; background: linear-gradient(120deg, #ce9864, #b87d49) !important; color: #24160a; font-size: 22px; font-weight: 600; }
.mobile-complete:disabled { opacity: 1; color: #795b3b; background: #3b2c1ec2 !important; }
.mobile-reopen-recall { position: absolute; left: 22px; right: 22px; top: 173px; display: flex; align-items: center; justify-content: center; gap: 12px; height: 70px; border: 1px solid #b48652 !important; border-radius: 18px; color: #edbd8b; background: #46301d88 !important; }
.mobile-scene button:hover { filter: brightness(1.1); }
.mobile-scene button:disabled:hover { filter: none; }
</style>
