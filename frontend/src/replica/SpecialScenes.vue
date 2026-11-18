<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import RIcon from './RIcon.vue'
import InboxIcon from './InboxIcon.vue'
import GraphScene from './GraphScene.vue'
import MobileScene from './MobileScene.vue'
import LoginTodayScenes from './LoginTodayScenes.vue'

const props = defineProps<{ scene: number }>()
const emit = defineEmits<{ navigate: [id: number]; notify: [text: string] }>()
const commandOpen = ref(true)
const query = ref('缓存')
const commandReferenceCaret = ref(true)
const commandInput = ref<HTMLInputElement>()
const commandPanel = ref<HTMLElement>()
const commandSelected = ref(0)
const commandDomains = [
  { label: '知识', icon: 'book-open' }, { label: '准备', icon: 'square-check-rounded' },
  { label: '摘录', icon: 'share-2' }, { label: '题面', icon: 'file-text' },
  { label: '回答', icon: 'message-square' }, { label: '反馈', icon: 'thumbs-up' },
  { label: '回忆', icon: 'history' }, { label: 'AI 观察', icon: 'sparkles' },
]
let commandComposing = false, commandTrigger: HTMLElement | undefined
const sourceFilter = ref(0)
const inboxSelected = ref(0)
const inboxChecked = ref<number[]>([])
const allRead = ref(false)
const sort = ref('按时间倒序')
const taskDone = ref<number[]>([])
const taskQuery = ref('')
const taskEntries = [
  { title: '深度理解线程池', priority: '高', tone: 'high' }, { title: '分布式事务', priority: '中', tone: 'medium' },
  { title: '缓存一致性', priority: '高', tone: 'high' }, { title: '准备技术面试', priority: '低', tone: 'green' },
  { title: '复习 JVM', priority: '中', tone: 'medium' },
]
const visibleTasks = computed(() => taskEntries.map((task, index) => ({ ...task, index })).filter(task => task.title.includes(taskQuery.value.trim())))
const allTasksDone = computed(() => visibleTasks.value.length > 0 && visibleTasks.value.every(task => taskDone.value.includes(task.index)))
const inboxEntries = [
  { title: '缓存删除失败的三种情况', source: '快速记录 · 今天 14:20', kind: 1, state: '未归类', icon: 'zap' },
  { title: '发奖链路恢复判据', source: '面后回忆 · 11.12', kind: 4, state: '已关联', icon: 'file-text' },
  { title: '一致性哈希摘录', source: '导入 · 11.08', kind: 2, state: '未归类', icon: 'cloud-upload' },
  { title: '对账任务失败案例', source: '训练片段 · 11.06', kind: 3, state: '已关联', icon: 'play' },
]
const visibleInbox = computed(() => {
  const entries = inboxEntries.map((entry, index) => ({ ...entry, index })).filter(entry => sourceFilter.value === 0 || entry.kind === sourceFilter.value)
  return sort.value === '按时间顺序' ? entries.reverse() : entries
})
const currentInbox = computed(() => visibleInbox.value.find(entry => entry.index === inboxSelected.value) ?? visibleInbox.value[0])
function selectInboxSource(value: number) {
  sourceFilter.value = value
  inboxChecked.value = []
}
const commands = computed(() => {
  const entries = [
    { title: '知识星图 · 缓存一致性', sub: 'meta 概念 · 分布式系统', icon: 'knowledge-network', scene: 9, group: '页面', shortcut: '' },
    { title: '今日安排', sub: '今日 · 任务总览', icon: 'calendar', scene: 2, group: '页面', shortcut: '' },
    { title: '新建笔记', sub: '', icon: 'file-plus', scene: 11, group: '新建', shortcut: '⌘N' },
    { title: '新建回忆卡', sub: '', icon: 'credit-card', scene: 16, group: '新建', shortcut: '⇧⌘N' },
  ]
  // 参考面板展示页面与新建命令；查询变化仅筛选固定命令，不读取正文。
  return query.value === '缓存' || !query.value.trim() ? entries : entries.filter(entry => entry.title.includes(query.value.trim()))
})
function go(id: number) { emit('navigate', id) }
function notify(text: string) { emit('notify', text) }
function toggleInbox(index: number) {
  inboxChecked.value = inboxChecked.value.includes(index) ? inboxChecked.value.filter(value => value !== index) : [...inboxChecked.value, index]
}
function toggleAll() {
  inboxChecked.value = visibleInbox.value.every(entry => inboxChecked.value.includes(entry.index)) ? [] : visibleInbox.value.map(entry => entry.index)
}
function taskToggle(index: number) {
  taskDone.value = taskDone.value.includes(index) ? taskDone.value.filter(value => value !== index) : [...taskDone.value, index]
}
function toggleVisibleTasks() {
  // 筛选后的全选只作用于当前可见任务，保留其他任务的勾选。
  const indexes = visibleTasks.value.map(task => task.index)
  taskDone.value = allTasksDone.value ? taskDone.value.filter(index => !indexes.includes(index)) : [...new Set([...taskDone.value, ...indexes])]
}
async function openCommand() {
  if (props.scene !== 25) return
  if (document.activeElement instanceof HTMLElement && !commandPanel.value?.contains(document.activeElement)) commandTrigger = document.activeElement
  commandOpen.value = true
  commandSelected.value = 0
  commandComposing = false
  commandReferenceCaret.value = query.value === '缓存'
  await nextTick()
  if (commandOpen.value) {
    commandInput.value?.focus({ preventScroll: true })
    if (commandReferenceCaret.value) commandInput.value?.setSelectionRange(query.value.length, query.value.length)
  }
}
function closeCommand() {
  commandOpen.value = false
  commandComposing = false
  commandReferenceCaret.value = false
  const trigger = commandTrigger
  commandTrigger = undefined
  void nextTick().then(() => {
    if (!commandOpen.value && trigger?.isConnected && trigger.getClientRects().length) trigger.focus({ preventScroll: true })
  })
}
function executeCommand(index: number) {
  const entry = commands.value[index]
  if (entry) go(entry.scene)
  else {
    const domain = commandDomains[index - commands.value.length]
    if (domain) notify(`${domain.label}正文检索待确认；当前面板仅展示固定命令`)
  }
}
function keydown(event: KeyboardEvent) {
  // 输入法的确认／取消键不能被当成打开命令或关闭面板。
  if (props.scene !== 25 || !commandOpen.value || event.defaultPrevented || event.repeat || event.isComposing || commandComposing || event.keyCode === 229) return
  if (!event.altKey && event.ctrlKey !== event.metaKey && event.key.toLowerCase() === 'n') {
    const index = commands.value.findIndex(entry => entry.title === (event.shiftKey ? '新建回忆卡' : '新建笔记'))
    if (index >= 0) { event.preventDefault(); executeCommand(index) }
    return
  }
  if (event.altKey || event.ctrlKey || event.metaKey) return
  if (event.key === 'Tab' && commandPanel.value) {
    const targets = [...commandPanel.value.querySelectorAll<HTMLElement>('input, button:not([tabindex="-1"])')]
      .filter(element => !element.matches(':disabled') && element.getClientRects().length)
    const first = targets[0], last = targets.at(-1)
    if (event.shiftKey && document.activeElement === first && last) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last && first) { event.preventDefault(); first.focus() }
    return
  }
  if (event.shiftKey) return
  if (event.key === 'Escape') { event.preventDefault(); closeCommand() }
  else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    const count = commands.value.length + commandDomains.length
    commandSelected.value = (commandSelected.value + (event.key === 'ArrowDown' ? 1 : count - 1)) % count
    void nextTick().then(() => document.getElementById(`replica-command-option-${commandSelected.value}`)?.scrollIntoView({ block: 'nearest' }))
  } else if (event.key === 'Enter') {
    if (event.target instanceof HTMLElement && event.target.closest('button')) return
    event.preventDefault()
    executeCommand(commandSelected.value)
  }
}
onMounted(() => {
  if (props.scene === 25) void openCommand()
  window.addEventListener('replica:open-command', openCommand)
})
onUnmounted(() => {
  commandTrigger = undefined
  window.removeEventListener('replica:open-command', openCommand)
})
</script>
<template>
  <LoginTodayScenes v-if="scene === 1 || scene === 2" :scene="scene" @navigate="go" @notify="notify" />
  <GraphScene v-else-if="scene === 9" :scene="scene" @navigate="go" @notify="notify" />
  <MobileScene v-else-if="scene === 29" :scene="scene" @navigate="go" @notify="notify" />

  <section v-else-if="scene === 25" class="sp-command-page">
    <div class="sp-command-heading"><h1>今日</h1><p class="r-muted">专注完成每一步</p></div>
    <div class="r-panel sp-task-intro r-between"><div class="r-row"><span class="sp-task-emblem"><svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><ellipse cx="16" cy="16" rx="9" ry="11" fill="#e7b48d" /><ellipse cx="16" cy="16" rx="6" ry="8" stroke="#f7d5b2" /><ellipse cx="16" cy="16" rx="3" ry="6" stroke="#ad744f" /></svg></span><span>今日任务<p class="r-muted">按计划推进</p></span></div><div class="r-row"><button class="r-btn" @click="notify('新建任务流程待确认')"><RIcon name="plus" />新建任务</button><button class="r-icon-btn" title="更多" @click="notify('任务操作流程待确认')"><RIcon name="ellipsis" /></button></div></div>
    <section class="r-panel sp-task-table"><h3>任务列表</h3><input v-model="taskQuery" class="sp-task-search" aria-label="筛选今日任务" /><table class="r-table"><thead><tr><th><input class="r-checkbox" type="checkbox" aria-label="全选任务" :checked="allTasksDone" :indeterminate="!allTasksDone && visibleTasks.some(task => taskDone.includes(task.index))" @change="toggleVisibleTasks" /></th><th>任务</th><th>优先级</th><th>状态</th></tr></thead><tbody><tr v-for="task in visibleTasks" :key="task.index"><td><input class="r-checkbox" type="checkbox" :aria-label="task.title" :checked="taskDone.includes(task.index)" @change="taskToggle(task.index)" /></td><td>{{ task.title }}</td><td><span class="r-pill" :class="task.tone">{{ task.priority }}</span></td><td><span class="r-pill" :class="task.index === 0 && !taskDone.includes(task.index) ? 'blue' : ''">{{ taskDone.includes(task.index) ? '已完成' : task.index === 0 ? '进行中' : '未开始' }}</span></td></tr></tbody></table></section>
    <div v-if="commandOpen" class="sp-command-scrim" @click.self="closeCommand" />
    <section v-if="commandOpen" ref="commandPanel" class="sp-command-modal" role="dialog" aria-modal="true" aria-label="全局命令面板" @keydown="keydown">
      <label class="sp-command-input r-row" :class="{ 'sp-command-reference': commandReferenceCaret }"><RIcon name="search" :size="29" /><input ref="commandInput" v-model="query" aria-label="搜索命令" autocomplete="off" spellcheck="false"
        role="combobox" aria-autocomplete="list" aria-expanded="true" aria-controls="replica-command-options" :aria-activedescendant="`replica-command-option-${commandSelected}`"
        @input="commandSelected = 0; commandReferenceCaret = false" @beforeinput="commandReferenceCaret = false" @pointerdown="commandReferenceCaret = false" @keydown="commandReferenceCaret = false"
        @compositionstart="commandComposing = true; commandReferenceCaret = false" @compositionend="commandComposing = false" /><kbd><span class="sp-command-query-key">⌘K</span></kbd></label>
      <div id="replica-command-options" class="sp-command-items" role="listbox" aria-label="可用命令">
        <p class="sp-command-section">页面</p>
        <template v-for="(entry, i) in commands" :key="entry.title">
          <p v-if="entry.group === '新建' && commands[i - 1]?.group !== '新建'" class="sp-command-section sp-new-divider">新建</p>
          <button :id="`replica-command-option-${i}`" type="button" role="option" tabindex="-1" :aria-selected="commandSelected === i" class="sp-command-item r-row" :class="{ selected: commandSelected === i, compact: !entry.sub }" @click="executeCommand(i)" @pointermove="commandSelected = i" @mousedown.prevent>
            <span class="sp-command-icon"><RIcon :name="entry.icon" variant="command" :size="38" /><RIcon v-if="entry.scene === 16" class="sp-command-card-plus" name="plus" :size="18" /></span>
            <span class="sp-command-copy">{{ entry.title }}<small v-if="entry.sub">{{ entry.sub }}</small></span><kbd v-if="entry.shortcut"><template v-if="entry.scene === 16"><span class="sp-command-shift">{{ entry.shortcut[0] }}</span><span>{{ entry.shortcut.slice(1) }}</span></template><span v-else class="sp-command-note-key">{{ entry.shortcut }}</span></kbd><span v-else class="sp-command-open"><span><span class="sp-open-text">打开</span></span><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 5v9H4m5-5-5 5 5 5" /></svg></span>
          </button>
        </template>
        <p v-if="!commands.length" class="sp-no-commands r-muted">无匹配命令</p>
        <div class="sp-domain-section"><p>搜索八个域</p><div class="sp-domain-pills"><button v-for="(entry, i) in commandDomains" :id="`replica-command-option-${commands.length + i}`" :key="entry.label" type="button" role="option" tabindex="-1" :aria-selected="commandSelected === commands.length + i" class="r-pill" :class="{ selected: commandSelected === commands.length + i }" @pointermove="commandSelected = commands.length + i" @mousedown.prevent @click="executeCommand(commands.length + i)"><RIcon :name="entry.icon" variant="command" :size="21" />{{ entry.label }}</button></div><p class="sp-domain-note r-row"><RIcon name="info" :size="22" />到达搜索页后才会查询正文</p></div>
      </div>
      <footer class="r-between"><span><button type="button" @click="closeCommand"><kbd>Esc</kbd><span class="sp-footer-close-text"> 关闭</span></button><i /><kbd>↑ ↓</kbd><span class="sp-footer-select-text"> 选择</span></span><span class="sp-command-read-boundary">不自动读取正文<RIcon name="circle" :size="22" /></span></footer>
    </section>
    <button v-if="!commandOpen" class="sp-command-reopen r-btn" @click="openCommand"><RIcon name="search" />打开命令面板</button>
  </section>

  <section v-else-if="scene === 30" class="sp-inbox-page">
    <div class="sp-inbox-heading"><h1>知识收件箱</h1><p class="r-muted">3 条待整理 · 来自快速记录和导入</p></div>
    <div class="sp-inbox-actions r-row"><button class="r-btn" :disabled="allRead" @click="allRead = true"><span class="sp-inbox-action-copy">全部标为已读</span></button><button class="r-btn r-primary" @click="go(11)"><RIcon name="plus" /><span class="sp-inbox-action-copy">新建笔记</span></button></div>
    <aside class="r-panel sp-inbox-sources" aria-label="收件箱来源"><h2>来源筛选</h2><button v-for="(source, i) in [['全部', '7', 'layout-grid'], ['快速记录', '3', 'zap'], ['导入', '2', 'cloud-upload'], ['训练片段', '1', 'play'], ['面后回忆', '1', 'file-text']]" :key="source[0]" class="r-row" :class="{ selected: sourceFilter === i }" :aria-pressed="sourceFilter === i" @click="selectInboxSource(i)"><InboxIcon :name="source[2]!" :data-inbox-icon="source[2]" :size="26" /><span>{{ source[0] }}</span><small>{{ source[1] }}</small></button></aside>
    <section class="r-panel sp-inbox-list" aria-label="待整理内容">
      <header class="r-between"><label class="r-row"><input type="checkbox" class="r-checkbox" aria-label="全选收件箱" :checked="visibleInbox.length > 0 && visibleInbox.every(entry => inboxChecked.includes(entry.index))" :indeterminate="visibleInbox.some(entry => inboxChecked.includes(entry.index)) && !visibleInbox.every(entry => inboxChecked.includes(entry.index))" @change="toggleAll" />全选</label><label class="sp-inbox-order"><select v-model="sort" aria-label="收件箱排序"><option>按时间倒序</option><option>按时间顺序</option></select><RIcon name="chevron-down" :size="18" /></label></header>
      <article v-for="entry in visibleInbox" :key="entry.index" :class="{ selected: currentInbox?.index === entry.index }"><input type="checkbox" class="r-checkbox" :aria-label="entry.title" :checked="inboxChecked.includes(entry.index)" @change="toggleInbox(entry.index)" /><button class="sp-inbox-entry" :aria-pressed="currentInbox?.index === entry.index" @click="inboxSelected = entry.index"><strong>{{ entry.title }}</strong><small class="r-row"><InboxIcon :name="entry.icon" :data-inbox-icon="entry.icon" context="entry" :size="23" /><span class="sp-inbox-source-copy">{{ entry.source }}</span></small></button><span class="r-pill" :class="entry.state === '已关联' ? 'green' : entry.index === 0 ? 'amber' : ''"><span class="sp-inbox-pill-copy">{{ entry.state }}</span></span></article>
    </section>
    <aside class="r-panel sp-inbox-detail" aria-label="内容详情">
      <header class="r-between"><h2>内容详情</h2><button class="r-icon-btn" title="打开内容" @click="go(11)"><InboxIcon name="external-link" :size="23" /></button></header>
      <h3 class="sp-excerpt-heading"><span class="sp-excerpt-heading-copy">原始摘录</span></h3>
      <div class="sp-inbox-excerpt"><template v-if="currentInbox?.index === 0"><p><span class="sp-inbox-excerpt-line">缓存删除失败通常有三种情况：</span></p><p><span class="sp-inbox-excerpt-line">1. key 不存在，删除返回 0；</span><br><span class="sp-inbox-excerpt-line">2. 网络超时，客户端误判；</span><br><span class="sp-inbox-excerpt-line">3. 连接池耗尽，导致删除请求被拒绝。</span><br><span class="sp-inbox-excerpt-line">需要根据业务场景选择合适的重试策略...</span></p></template><template v-else-if="currentInbox"><h3>{{ currentInbox.title }}</h3><p class="r-muted">该条目的原始摘录待确认</p></template></div>
      <div class="r-rule" /><h3 class="r-row sp-suggestions-title"><RIcon name="lightbulb" variant="inbox" :size="27" />建议操作</h3><div class="sp-inbox-buttons"><button class="r-btn" @click="go(11)"><RIcon name="file-plus" variant="inbox" :size="28" /><span class="sp-inbox-action-copy">转为笔记</span></button><button class="r-btn" @click="go(12)"><RIcon name="box" variant="inbox" :size="30" /><span class="sp-inbox-action-copy">新建概念</span></button><button class="r-btn" @click="go(16)"><RIcon name="credit-card" variant="inbox" :size="27" /><span class="sp-inbox-action-copy">创建回忆卡</span></button><button class="r-btn" @click="go(9)"><RIcon name="link" variant="inbox" :size="29" /><span class="sp-inbox-action-copy">关联已有知识</span></button></div><p class="sp-inbox-note r-row r-muted"><RIcon name="info" :size="24" /><span class="sp-inbox-note-copy">来源与候选分开保存，不会自动发布</span></p>
    </aside>
  </section>
</template>
<style scoped>
.sp-command-heading { position: absolute; top: 132px; left: 35px; }.sp-command-heading h1 { font-size: 45px; }.sp-command-heading p { font-size: 26px; margin-top: 7px; }
.sp-task-intro { position: absolute; top: 269px; left: 19px; right: 32px; height: 108px; padding: 20px 27px; border-color: #2c251d; }.sp-task-intro > .r-row { gap: 18px; font-size: 24px; }.sp-task-intro p { font-size: 19px; }.sp-task-emblem { display: grid; place-items: center; height: 39px; width: 39px; border-radius: 50%; background: #915b37; color: #f0caa6; }.sp-task-intro .r-btn { font-size: 21px; min-height: 49px; color: #e0a572; padding: 8px 20px !important; border-radius: 19px; }.sp-task-intro .r-icon-btn { background: #ffffff09; width: 49px; height: 49px; border-radius: 18px; }
.sp-task-table { position: absolute; top: 399px; left: 19px; right: 32px; height: 511px; border-color: #30271e; }.sp-task-table h3 { padding: 23px 25px; font-size: 23px; }.sp-task-search { position: absolute; top: 15px; right: 26px; width: 40%; height: 53px; background: #ffffff03; border: 1px solid #ffffff03; border-radius: 25px; }.sp-task-table table { margin-top: 0; }.sp-task-table th { padding: 14px 8px; font-size: 20px; }.sp-task-table th:first-child { padding-left: 29px; width: 64px; }.sp-task-table th:nth-child(2) { width: 77%; }.sp-task-table th:nth-child(3) { min-width: 110px; }.sp-task-table td { font-size: 22px; border: 0; padding: 14px 8px; white-space: nowrap; }.sp-task-table td:first-child { padding-left: 29px; }.sp-task-table td:nth-child(3) { width: 116px; }.sp-task-table td:nth-child(4) { padding-right: 33px; }.sp-task-table .r-pill { font-size: 19px; min-width: 59px; }.sp-command-scrim { position: absolute; inset: 0 0 0 -212px; background: #0002; z-index: 30; }
.sp-command-modal { position: absolute; left: 184px; top: 135px; width: 847px; height: 728px; border: 1px solid #51483e; border-radius: 32px; background: linear-gradient(110deg, #28241fee, #1e1b17f7 75%); z-index: 45; box-shadow: 0 24px 50px #0008; overflow: hidden; }
.sp-command-input { position: relative; height: 82px; padding: 0 28px; border-bottom: 1px solid #50443755; gap: 15px; background: #ffffff02; }
.sp-command-input::before { content: ""; position: absolute; inset: 12px 13px; background: #00000013; border-radius: 23px; pointer-events: none; }
.sp-command-input > :is(svg, input, kbd) { position: relative; }
.sp-command-input input { background: transparent; border: 0; border-radius: 0; outline: 0; font-size: 29px; width: 100%; min-width: 0; height: 58px; caret-color: transparent; padding: 0; }
.sp-command-input kbd { font: 19px "Segoe UI", sans-serif; background: #ffffff08; padding: 2px 8px; border-radius: 7px; color: #c1b8ad; }
.sp-command-items { height: 577px; overflow-y: auto; overscroll-behavior: contain; scrollbar-width: thin; scrollbar-color: #51483e transparent; }
.sp-command-section { height: 55px; padding: 0 29px; display: flex; align-items: center; color: #cfc6ba; font-size: 23px; line-height: 1.6 !important; }
.sp-command-item { position: relative; width: calc(100% - 23px); height: 92px; margin-left: 10px; padding: 12px 24px !important; text-align: left; gap: 26px; border-radius: 16px; color: #f1e8da; }
.sp-command-item.selected, .sp-domain-pills .r-pill.selected { background: linear-gradient(100deg, #8c5c3b91, #684b3233) !important; }
.sp-command-item.selected::before { content: ""; position: absolute; top: 0; left: -11px; bottom: 0; width: 2px; background: #e6b282; }
.sp-command-copy { flex: 1; min-width: 0; font-size: 24px; }
.sp-command-item small { display: block; font-size: 20px; color: #b7a997; line-height: 1.6; }
.sp-command-icon { position: relative; display: inline-flex; flex: none; color: #d9cec0; }
.sp-command-open { display: inline-flex; align-items: center; flex: none; gap: 5px; font-size: 20px; }
.sp-command-open > span:first-child { padding: 3px 15px; border-radius: 20px; background: #ffffff08; }
.sp-command-item kbd { font: 20px "Segoe UI", "Microsoft YaHei", sans-serif; color: #e8e0d4; background: #ffffff06; border-radius: 7px; padding: 0 4px; white-space: pre; }
.sp-command-item.compact { height: 57px; padding-block: 5px !important; }
.sp-command-item.compact .sp-command-icon > svg:first-child { height: 35px; width: 35px; }
.sp-command-card-plus { position: absolute; right: -2px; bottom: -1px; background: #25211d; }
.sp-new-divider { height: 57px; padding: 7px 0 10px; margin: 4px 29px 0 !important; border-top: 1px solid #50443755; }
.sp-domain-section { margin: 0; }
.sp-domain-section > p:first-child { display: flex; align-items: center; height: 57px; margin: 0 29px; padding: 9px 0 10px; border-top: 1px solid #50443755; font-size: 23px; color: #c9bfb1; }
.sp-domain-pills { display: flex; gap: 8px; padding: 0 29px; }
.sp-domain-pills .r-pill { height: 40px; font-size: 19px; gap: 7px; padding: 3px 10px; background: #ffffff08; }
.sp-domain-note { height: 66px; padding: 14px 29px 0; font-size: 20px; color: #aaa194; gap: 10px; }
.sp-domain-note svg { color: #7eb2cc; fill: #315d7b; }
.sp-command-modal footer { position: absolute; bottom: 0; left: 0; right: 0; height: 67px; border-top: 1px solid #50443755; background: #ffffff02; padding: 0 28px; color: #b7ada0; font-size: 21px; }
.sp-command-modal footer kbd { font: inherit; padding: 0 4px; border-radius: 5px; color: #f1e5d5; background: #ffffff09; }
.sp-command-modal footer i { display: inline-block; height: 24px; width: 1px; background: #6b5c4b; margin: 0 16px; vertical-align: middle; }
.sp-command-read-boundary { display: inline-flex; align-items: center; gap: 10px; }
.sp-no-commands { padding: 20px 30px; }.sp-command-reopen { position: absolute; top: 145px; right: 32px; }
/* 命令面板外框固定；内部偏移不改变八域与底栏的参考位置。 */
.sp-command-heading { left: 38px; top: 133px; }
.sp-command-heading h1 { font-weight: 500; }
.sp-command-heading p { margin-top: 5px; }
.sp-task-intro { padding-left: 23px; padding-right: 6px; background: linear-gradient(110deg, #1b1714, #14120f); }
.sp-task-emblem { background: linear-gradient(125deg, #75543d, #523725); }
.sp-task-emblem svg { width: 28px; height: 28px; }
.sp-task-intro > .r-row:last-child { position: relative; top: -13px; gap: 15px; }
.sp-task-intro .r-btn { min-height: 49px; font-size: 20px; padding-inline: 16px !important; gap: 8px; background: linear-gradient(110deg, #825533, #52331f) !important; border-color: #a7754b !important; }
.sp-task-intro .r-icon-btn { width: 47px; height: 49px; }
/* 左侧上沿比右侧低24px；分界放在命令面板遮挡内，表格内容坐标保持。 */
.sp-task-table { top: 375px; height: 535px; background: none; border-color: transparent; isolation: isolate; overflow: visible; }
.sp-task-table::before, .sp-task-table::after { content: ""; position: absolute; z-index: -1; border: 1px solid #30271e; background: linear-gradient(110deg, #191512, #151210); pointer-events: none; }
.sp-task-table::before { left: -1px; top: 23px; bottom: -1px; width: 181px; border-right: 0; border-radius: 20px 0 0 20px; }
.sp-task-table::after { left: 179px; right: -1px; top: -1px; bottom: -1px; border-left: 0; border-radius: 0 20px 20px 0; }
.sp-task-table h3 { height: 100px; padding-top: 49px; font-weight: 500; }
.sp-task-search { top: 39px; padding: 0 22px; outline: none; }
.sp-task-table th { height: 58px; font-size: 20px; }
.sp-task-table td { height: 64px; padding-block: 11px; font-size: 22px; }
.sp-task-table td:nth-child(3) { padding-left: 12px; }
.sp-task-table td:last-child { padding-right: 32px; }
.sp-task-table .r-pill { font-size: 19px; min-width: 56px; padding-block: 0; }
.sp-task-table .r-pill.high { color: #f0a564; background: #6b351b70; }
.sp-task-table .r-pill.medium { color: #f7bd59; background: #67440a70; }
.sp-task-table .r-checkbox:indeterminate::after { content: ""; width: 11px; height: 2px; background: #e7b279; }
.sp-command-modal { background: linear-gradient(110deg, #1c1916f5, #191615f7); border-color: #44403b; }
.sp-command-input::before { background: #00000020; }
.sp-command-section { position: relative; top: 2px; font-size: 22px; }
.sp-command-copy { font-size: 23px; }
.sp-command-item { width: calc(100% - 21px); padding-right: 33px !important; color: #ede7de; }
.sp-command-item small { color: #bdb5aa; }
.sp-command-item.compact .sp-command-icon, .sp-command-item.compact .sp-command-copy, .sp-command-item.compact > kbd { transform: translateY(-7px); }
.sp-command-item.compact .sp-command-icon > svg:first-child { height: 38px; width: 38px; }
.sp-command-open { gap: 6px; }
.sp-command-open > span:first-child { background: #ffffff0c; }
.sp-command-item kbd { padding: 0 7px; background: #ffffff0a; }
.sp-domain-section > p:first-child { font-size: 22px; }
.sp-domain-pills .r-pill { padding-inline: 11.7px; }
.sp-domain-note { padding-top: 12px; }
.sp-command-modal footer { background: #ffffff03; }
.sp-command-modal footer i { margin-inline: 19px; }
.sp-command-read-boundary { gap: 8px; }
/* 初始查询用固定光标；编辑后交回原生输入，避免画面取决于闪烁时序。 */
.sp-command-input { background: linear-gradient(180deg, #ffffff08, #ffffff04); }
.sp-command-input::before { background: #0000003c; }
.sp-command-input input { font-size: 27.5px; font-weight: 300; -webkit-text-stroke: .11px currentColor; transform-origin: 0 50%; transform: translate(-1px, -1px) scaleX(.965); caret-color: currentColor; }
.sp-command-input.sp-command-reference input { caret-color: transparent; }
.sp-command-input.sp-command-reference:has(input:focus)::after { content: ""; position: absolute; top: 23px; left: 126px; width: 1px; height: 35px; background: #cfc5b399; pointer-events: none; }
.sp-command-input kbd { transform: translateX(4px); }
.sp-command-query-key { display: inline-block; transform-origin: 50% 50%; transform: translate(-1px, .5px) scale(.94, .9); }
.sp-command-heading h1 { font-size: 42px; line-height: 58.5px; font-weight: 500; -webkit-text-stroke: .3px currentColor; transform-origin: 0 0; transform: translate(2px, 0) scale(.965, .955); }
.sp-command-modal { font-weight: 300; }
.sp-command-section, .sp-domain-section > p:first-child { font-weight: 400; }
.sp-command-copy { font-weight: 300; -webkit-text-stroke: .045px currentColor; transform-origin: 0 50%; transform: translateX(2px) scaleX(.961); }
.sp-command-copy small { font-size: 19px; line-height: 32px; font-weight: 300; -webkit-text-stroke: 0; transform-origin: 0 50%; transform: translate(-1px, -2px) scaleX(1.057); color: #d6d2c6; }
.sp-command-item:nth-of-type(2) .sp-command-copy { transform: translate(2px, -2px) scale(.94, .96); }
.sp-command-item:nth-of-type(3) .sp-command-copy { transform: translate(2px, -7px) scale(.942, .96); }
.sp-command-item:nth-of-type(4) .sp-command-copy { transform: translate(2px, -11px) scale(.942, 1); }
.sp-command-item:nth-of-type(4) .sp-command-icon { transform: translateY(-11px); }
.sp-command-open { font-weight: 400; transform: translate(7px, -1px); }
.sp-command-open > span:first-child { display: inline-flex; align-items: center; height: 38px; padding: 0 15px; background: linear-gradient(110deg, #ffffff0c, #ffffff05); }
.sp-command-item.selected .sp-command-open > span:first-child { background: linear-gradient(110deg, #e0ac7d2b, #b7927020); }
.sp-command-open .sp-open-text { display: inline-block; transform-origin: 0 50%; transform: translate(1px, -.25px) scale(.9, 1.06); }
.sp-command-open > svg { transform-origin: 0 50%; transform: translateX(-5px) scaleX(.875); stroke-width: 2px; }
.sp-command-item:nth-of-type(3) > kbd { display: inline-flex; align-items: center; justify-content: center; width: 51px; height: 28px; padding: 0; font-size: 18px; line-height: 28px; transform: translateY(-1px); }
.sp-command-item:nth-of-type(4) > kbd { display: inline-flex; align-items: center; justify-content: center; gap: 10px; width: 72px; height: 28px; padding: 0; font-size: 18px; line-height: 28px; transform: translate(-1px, -7.5px); }
.sp-command-item:nth-of-type(4) > kbd > span:last-child { display: inline-block; transform-origin: 100% 50%; transform: scaleX(1.1); }
.sp-command-shift { font-family: "Segoe UI Symbol", "Segoe UI", sans-serif; font-size: 24px; line-height: 24px; }
.sp-command-note-key { display: inline-block; transform-origin: 50% 0; transform: translateX(.5px) scale(1.07, .94); }
.sp-domain-section > p:first-child { padding-top: 7px; padding-left: 1px; font-size: 22.25px; }
.sp-domain-note { font-size: 18.9px; line-height: 33px; font-weight: 300; padding-top: 8px; gap: 13px; color: #d1ceca; }
.sp-command-modal footer { font-weight: 400; color: #d1ccc7; background: #ffffff05; }
.sp-command-modal footer i { margin-inline: 20px; }
.sp-command-modal footer > span:first-child { display: inline-flex; align-items: center; transform: translateX(1px); }
.sp-command-modal footer button { display: inline-flex; align-items: center; gap: 3px; font-size: 20px; font-weight: 300; line-height: 30px; }
.sp-command-modal footer button kbd { display: inline-flex; align-items: center; justify-content: center; width: 42px; height: 28px; font-size: 21px; font-weight: 500; }
.sp-command-modal footer > span:first-child > kbd { display: inline-flex; align-items: center; justify-content: center; width: 54px; height: 28px; word-spacing: 10px; font-weight: 300; }
.sp-footer-close-text { display: inline-block; }
.sp-footer-select-text { margin-left: 7px; font-size: 20px; font-weight: 300; }
.sp-command-read-boundary { font-size: 20px; font-weight: 300; }
.sp-inbox-heading { position: absolute; top: 109px; left: 26px; }.sp-inbox-heading h1 { font-size: 47px; }.sp-inbox-heading p { margin-top: 3px; font-size: 23px; }.sp-inbox-actions { position: absolute; right: 30px; top: 133px; gap: 17px; }.sp-inbox-actions .r-btn { font-size: 24px; min-height: 58px; border-radius: 17px; padding: 10px 24px !important; }
.sp-inbox-sources { position: absolute; top: 233px; left: 15px; bottom: 90px; width: 233px; border-radius: 20px; padding: 20px 10px; border-color: #393023; }.sp-inbox-sources h2 { padding: 0 15px 17px; font-size: 25px; }.sp-inbox-sources > button { display: flex; width: 100%; min-height: 64px; padding: 12px 17px !important; gap: 13px; text-align: left; font-size: 23px; border-radius: 15px; white-space: nowrap; }.sp-inbox-sources button.selected { background: #7c582f50 !important; color: #faead4; }.sp-inbox-sources small { margin-left: auto; font-size: 23px; }.sp-inbox-sources svg { color: #efc38d; }
.sp-inbox-order { position: relative; border-radius: 23px; background: #ffffff08; }
.sp-inbox-detail { transform: translateZ(0); }
.sp-inbox-order svg { position: absolute; right: 9px; pointer-events: none; }
.sp-inbox-list header .sp-inbox-order select { appearance: none; background: transparent; padding-right: 28px; }
.sp-inbox-list .r-checkbox:indeterminate { background: #b77c3e; border-color: #e7b279; }
.sp-inbox-list .r-checkbox:indeterminate::after { content: ""; width: 11px; height: 2px; background: #fff; }
.sp-inbox-list { position: absolute; top: 233px; left: 258px; bottom: 90px; width: 514px; border-radius: 20px; padding: 9px 5px; border-color: #3d3125; }.sp-inbox-list header { padding: 8px 13px 18px 18px; color: #c9bfb2; font-size: 23px; }.sp-inbox-list header select { background: #ffffff08; border: 0; border-radius: 23px; padding: 4px 13px; font-size: 21px; color-scheme: dark; width: 146px; outline: none; }.sp-inbox-list header label { gap: 14px; }.sp-inbox-list article { display: flex; position: relative; align-items: center; min-height: 110px; margin: 0 13px; padding: 18px 7px 16px 4px; border-bottom: 1px solid #3a3025; gap: 20px; }.sp-inbox-list article.selected { padding: 18px 18px; margin: 0; border: 1px solid #dbab6e; border-radius: 18px; min-height: 114px; background: linear-gradient(100deg, #8e603585, #6e4e2f45); }.sp-inbox-list .r-checkbox { margin: 0 0 0 0; flex: none; }.sp-inbox-entry { flex: 1; min-width: 0; text-align: left; align-self: stretch; }.sp-inbox-entry strong { display: block; font-size: 24px; font-weight: 550; white-space: nowrap; }.sp-inbox-entry small { font-size: 21px; color: #b4a795; margin-top: 7px; gap: 10px; white-space: nowrap; }.sp-inbox-list article > .r-pill { position: absolute; right: 17px; top: 29px; font-size: 19px; padding: 3px 15px; }.sp-inbox-list article.selected > .r-pill { top: 22px; }.sp-inbox-list article.selected .sp-inbox-entry { padding-right: 65px; }.sp-inbox-list article.selected .sp-inbox-entry strong { font-size: 23px; }.sp-inbox-list article:nth-child(n+3) { padding-right: 80px; }
.sp-inbox-detail { position: absolute; top: 233px; left: 783px; right: 24px; bottom: 90px; padding: 15px 23px 22px; border-radius: 20px; border-color: #3d3125; }.sp-inbox-detail header { border-bottom: 1px solid #30281f; padding-bottom: 11px; }.sp-inbox-detail h2 { font-size: 25px; }.sp-excerpt-heading { position: relative; margin-top: 17px !important; padding-left: 18px; font-size: 23px !important; }.sp-excerpt-heading::before { content: ""; width: 3px; height: 27px; background: #e7b77e; position: absolute; left: 0; top: 5px; }.sp-inbox-excerpt { padding: 14px 18px 15px; margin-top: 5px; border-radius: 15px; background: #ffffff02; }.sp-inbox-excerpt p { font-size: 22px; line-height: 1.75; white-space: nowrap; }.sp-inbox-excerpt h3 { font-size: 22px; margin-bottom: 10px; }.sp-inbox-detail .r-rule { margin: 21px 0 16px; }.sp-suggestions-title { color: #f2d7af; font-size: 23px !important; gap: 12px; }.sp-inbox-buttons { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 21px; }.sp-inbox-buttons .r-btn { min-height: 68px; padding: 10px 14px !important; font-size: 23px; border-radius: 14px; background: #ffffff02 !important; border-color: #44372a !important; gap: 14px; }.sp-inbox-note { background: #ffffff03; border-radius: 14px; margin-top: 26px !important; min-height: 56px; padding: 10px 13px; font-size: 18px; white-space: nowrap; gap: 10px; }
/* 收件箱保留四条参考记录的独立行距，详情摘录保持五行。 */
.sp-inbox-heading { left: 28px; }
.sp-inbox-heading h1 { width: max-content; font-size: 46.5px; line-height: 61.09375px; font-weight: 500; -webkit-text-stroke: .45px currentColor; transform-origin: 0 0; transform: translateX(1px) scaleX(.991); }
.sp-inbox-heading p { margin-top: 5px; font-size: 22px; }
.sp-inbox-actions { gap: 15px; top: 134px; }
.sp-inbox-actions .r-btn { font-size: 22px; font-weight: 400; }
.sp-inbox-action-copy { display: inline-block; transform-origin: 0 50%; }
.sp-inbox-actions .sp-inbox-action-copy { font-weight: 300; -webkit-text-stroke: .07px currentColor; transform: translate(1px, -1px) scaleX(.98); }
.sp-inbox-actions > .r-btn:first-child { width: 183px; padding-inline: 12px !important; background: linear-gradient(120deg, #2a251f99, #201b1688) !important; border-color: #62534244 !important; }
.sp-inbox-actions > .r-primary { width: 166px; background: linear-gradient(112deg, #e2ad77, #dca370 45%, #cd996a) !important; border-color: #ffc99680 !important; box-shadow: inset 0 1px 2px #ffe2c080, 0 8px 16px #bd7d3412; transform: translateZ(0); }
.sp-inbox-sources, .sp-inbox-list { background: radial-gradient(ellipse at 30% 45%, #40302512, transparent 80%), linear-gradient(125deg, #181511, #14100e); border-color: #4e40344d; }
.sp-inbox-sources > button:not(.selected) .r-icon { color: #e2ddd5; }
.sp-inbox-sources button.selected { background: radial-gradient(ellipse at 0 100%, #9a714744, transparent 100%), linear-gradient(110deg, #79593855, #54433455) !important; }
.sp-inbox-sources h2 { font-size: 22.5px; font-weight: 500; line-height: 35px; }
.sp-inbox-sources > button { min-height: 63px; font-size: 21px; line-height: 34px; }
.sp-inbox-sources > button > span { display: inline-block; font-size: 21px; font-weight: 300; transform-origin: 0 50%; }
.sp-inbox-sources small { margin-right: 6px; font-size: 22px; }
.sp-inbox-list { padding-left: 4px; padding-right: 8px; background: radial-gradient(ellipse at 20% 10%, #4c352510, transparent 65%), linear-gradient(125deg, #14120f, #14110f); }
.sp-inbox-list header { font-size: 22px; line-height: 33.5px; padding-left: 20px; padding-right: 10px; }
.sp-inbox-list header .r-checkbox { width: 21px; margin-left: 1px; }
.sp-inbox-list header select { line-height: 31.5px; }
.sp-inbox-list article { gap: 20px; margin-inline: 21px 18px; padding-left: 1px; border-bottom-color: #a48c6f12; }
.sp-inbox-list article.selected { min-height: 113px; margin: 0; padding-left: 21px; gap: 20px; border-color: #c4965e; background: radial-gradient(ellipse at 10% 90%, #87634326, transparent 80%), linear-gradient(110deg, #70533959, #65513855); }
.sp-inbox-list article:nth-of-type(2) { min-height: 114px; }
.sp-inbox-list article:nth-of-type(3) { min-height: 109px; padding-bottom: 13px; }
.sp-inbox-list article:nth-of-type(4) { min-height: 107px; padding-bottom: 12px; }
.sp-inbox-list article .r-checkbox { transform: translateY(-6px); border-color: #bfb9b0; box-shadow: inset 0 0 2px #fff4; }
.sp-inbox-list article:nth-of-type(4) .r-checkbox { transform: translateY(-10px); }
.sp-inbox-list article.selected .r-checkbox { border-color: #edbf8d; }
.sp-inbox-entry { position: relative; top: 0; transform: translateY(-2px); }
.sp-inbox-list article:nth-of-type(3) .sp-inbox-entry { transform: translateY(-3px); }
.sp-inbox-list article:nth-of-type(4) .sp-inbox-entry { transform: translateY(-5px); }
.sp-inbox-entry strong, .sp-inbox-list article.selected .sp-inbox-entry strong { font-size: 22px; font-weight: 400; line-height: 36px; }
.sp-inbox-entry strong { transform-origin: 0 50%; transform: translate(1px, 1px) scaleY(1.06); }
.sp-inbox-list article:first-of-type .sp-inbox-entry strong { transform: translate(1px, 1px); }
.sp-inbox-list article:nth-of-type(2) .sp-inbox-entry strong { transform: translate(1px, 0) scale(.995, 1.06); }
.sp-inbox-list article:nth-of-type(3) .sp-inbox-entry strong { transform: translate(1px, 1px) scale(.995, 1.06); }
.sp-inbox-list article:nth-of-type(4) .sp-inbox-entry strong { transform: translate(1px, 2px) scaleY(1.06); }
.sp-inbox-entry small { font-size: 20px; line-height: 32px; gap: 11px; color: #b6b1a8; }
.sp-inbox-source-copy { display: inline-block; transform-origin: 0 50%; transform: translate(-1px, 1px) scaleX(1.025); }
.sp-inbox-list article > .r-pill { right: 2px; top: 24px; font-size: 20px; padding-inline: 13px; }
.sp-inbox-pill-copy { display: inline-block; font-size: 19.3px; font-weight: 400; line-height: 34px; transform: translateY(1px); }
.sp-inbox-list article.selected > .r-pill { right: 20px; top: 24px; }
.sp-inbox-list article:nth-of-type(3) > .r-pill { top: 20px; }
.sp-inbox-list article:nth-of-type(4) > .r-pill { top: 21px; }
.sp-inbox-detail { background: linear-gradient(125deg, #1b1613, #1a1612 55%, #191512); background-clip: border-box; border-color: #58483a47; }
.sp-inbox-detail header { border-bottom-color: #bba1810a; }
.sp-inbox-detail h2 { font-size: 22.5px; font-weight: 500; line-height: 35px; }
.sp-inbox-detail header h2 { position: relative; top: -2px; }
.sp-excerpt-heading { font-size: 22px !important; font-weight: 500; line-height: 33.35px; }
.sp-excerpt-heading-copy { display: inline-block; transform-origin: 0 50%; transform: translate(2px, -1px) scale(.95, 1.04); }
.sp-inbox-excerpt { padding-top: 11px; background: linear-gradient(120deg, #1f1a16, #1c1714); }
.sp-inbox-excerpt p { font-size: 20.75px; font-weight: 300; line-height: 39px; transform: translate(2px, 1px); }
/* 逐行字框保留摘录原文与换行，避免字形缩放改变五行的布局高度。 */
.sp-inbox-excerpt p > br { display: none; }
.sp-inbox-excerpt-line { display: block; line-height: 39px; transform-origin: 0 50%; transform: scale(.994, 1.04); }
.sp-inbox-excerpt p:last-child .sp-inbox-excerpt-line:first-child { font-size: 22px; transform: scale(.996, 1.04); }
.sp-inbox-excerpt p:last-child .sp-inbox-excerpt-line:nth-of-type(2) { font-size: 22px; transform: translateX(-1px) scaleY(1.04); }
.sp-inbox-excerpt p:last-child .sp-inbox-excerpt-line:nth-of-type(3) { font-size: 21px; transform: translate(-1px, -1px) scale(.997, 1.075); }
.sp-inbox-excerpt p:last-child .sp-inbox-excerpt-line:last-of-type { transform: translateY(-.5px) scale(.997, 1.04); }
.sp-inbox-detail .r-rule { margin-bottom: 20px; }
.sp-suggestions-title { font-size: 21.5px !important; font-weight: 500; line-height: 33.35px; gap: 8px; }
.sp-inbox-buttons { margin-top: 20px; grid-template-columns: 227px 1fr; }
.sp-inbox-buttons .r-btn { justify-content: flex-start; font-size: 22px; padding-left: 44px !important; padding-right: 10px !important; border-color: #b6997930 !important; background: linear-gradient(115deg, #2e282333, #39322b22) !important; }
.sp-inbox-buttons .sp-inbox-action-copy { transform: translate(2px, -.5px) scaleX(.93); }
.sp-inbox-buttons .r-btn:last-child .sp-inbox-action-copy { transform: translate(1px, -.5px) scaleX(.915); }
.sp-inbox-buttons .r-btn:nth-child(even) { padding-left: 30px !important; }
.sp-inbox-buttons .r-icon { width: 30px; }
.sp-inbox-note { margin-top: 28px !important; color: #b8b0a6; background: linear-gradient(115deg, #3c352c33, #3f393033); }
.sp-inbox-note-copy { font-weight: 300; -webkit-text-stroke: .13px currentColor; transform-origin: 0 50%; transform: translate(-2px, -1px) scaleX(.99); }
/* 柔和轮廓仅绘制未勾选态，勾选与半选继续由原生 input 状态控制。 */
.sp-inbox-list .r-checkbox:not(:checked):not(:indeterminate) { border-width: 1px; border-color: #b5afa6; box-shadow: 0 0 0 .5px #d8d0c477, inset 0 0 0 .5px #ded8ce66, 0 0 2px #c8c1b755, inset 0 0 2px #ddd6ca33; }
.sp-inbox-list article.selected .r-checkbox:not(:checked):not(:indeterminate) { border-color: #e4ba8a; box-shadow: 0 0 0 .5px #f0c28d99, inset 0 0 0 .5px #f9d3a488, 0 0 2px #e9b47b66, inset 0 0 2px #ffdeb966; }
.sp-inbox-list header .r-checkbox { transform: translateY(1px); }
.sp-inbox-list article .r-checkbox { transform: translateY(-7px); }
.sp-inbox-list article:nth-of-type(4) .r-checkbox { transform: translateY(-9px); }
/* 按来源身份匹配图标，避免过滤或倒序后把另一条内容染成快速记录。 */
.sp-inbox-sources > button .r-icon[data-inbox-icon="zap"] { color: #e5b786; transform: translateY(1px); filter: drop-shadow(0 0 .6px #eec89655); }
.sp-inbox-entry .r-icon[data-inbox-icon="zap"] { color: #e5b786; filter: drop-shadow(0 0 .6px #eec89655); }
.sp-inbox-sources > button .r-icon[data-inbox-icon="file-text"] { transform: translateX(0px); }
.sp-inbox-sources > button .r-icon[data-inbox-icon="cloud-upload"] { transform: translate(0px, 1px); }
.sp-inbox-entry .r-icon[data-inbox-icon="cloud-upload"] { color: #d2cec6; transform: translate(0px, 1px); }
.sp-inbox-detail header .r-icon-btn { transform: translateX(8.5px); }
.sp-inbox-entry .r-icon[data-inbox-icon="file-text"] { color: #b6b1a8; transform: translateY(1.5px); }
</style>
