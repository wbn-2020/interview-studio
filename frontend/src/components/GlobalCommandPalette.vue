<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { isNavigationFailure, NavigationFailureType, useRoute, useRouter } from 'vue-router'
import { NModal } from 'naive-ui'
import type { WorkspaceCommand } from '../navigation/commands'
import { filterWorkspaceCommands, readSearchPrefill, SEARCH_PREFILL_EVENT, searchPrefillCommands, workspaceCommands } from '../navigation/commands'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import RIcon from '../replica/RIcon.vue'

const props = withDefaults(defineProps<{ enabled: boolean; showTrigger?: boolean }>(), { showTrigger: true })
const router = useRouter(), route = useRoute(), workspace = useWorkspaceStore(), access = useTrainingAccessStore()
const visible = ref(false), closing = ref(false), input = ref(''), selectedIndex = ref(-1), navigating = ref(false), message = ref('')
const panel = ref<HTMLElement>(), filterInput = ref<HTMLInputElement>()
const optionElements = new Map<string, HTMLElement>()
const accessCommandLocked = computed(() => Boolean(workspace.changes.get('training-access-command')?.locked))
const canOpen = computed(() => props.enabled && workspace.sessionActive && !access.checking && !access.challenge && !accessCommandLocked.value && !navigating.value && !closing.value)
const canExecute = computed(() => props.enabled && workspace.sessionActive && !access.checking && !access.challenge && !accessCommandLocked.value && !navigating.value)
const searchRestricted = computed(() => access.checking || access.challenge || access.restricted || accessCommandLocked.value)
const commands = computed(() => {
  const matches = filterWorkspaceCommands(input.value)
  const pages = matches.filter(command => command.group === 'PAGE')
  const create = matches.filter(command => command.group === 'CREATE')
  return [...(pages.length || !input.value.trim() ? pages : ['knowledge', 'today'].flatMap(id => workspaceCommands.filter(command => command.id === id))),
    ...(input.value.trim() && !create.length ? workspaceCommands.filter(command => command.id === 'create-note' || command.id === 'create-memory-card') : create),
    ...searchPrefillCommands(input.value)]
})
const domainCommands = computed(() => commands.value.filter(command => command.group === 'SEARCH'))
const navigationCommands = computed(() => commands.value.filter(command => command.group !== 'SEARCH'))
const selected = computed(() => commands.value[selectedIndex.value])
const groupLabels = { PAGE: '页面', CREATE: '新建', SEARCH: '搜索八个域' }
const commandIcons: Record<string, string> = { today: 'calendar-days', knowledge: 'knowledge-network', prepare: 'clipboard', training: 'play', review: 'refresh-cw', growth: 'chart-no-axes-combined', 'create-note': 'file-plus', 'create-memory-card': 'credit-card', recent: 'history', search: 'search' }
const commandShortcuts: Record<string, string> = { 'create-note': '⌘N', 'create-memory-card': '⇧⌘N' }
const domainLabels: Record<string, string> = { KNOWLEDGE: '知识', PREPARATION: '准备', MATERIAL_EXCERPT: '摘录', QUESTION: '题面', TRAINING_ANSWER: '回答', REVIEW_FEEDBACK: '反馈', RECOLLECTION: '回忆', AI_FEEDBACK: 'AI 观察' }
const domainIcons = ['book-open', 'calendar-check', 'share-2', 'file-text', 'message-square', 'thumbs-up', 'history', 'sparkles']
const searchProblem = computed(() => {
  if (!input.value.trim()) return ''
  const prefill = readSearchPrefill({ q: input.value })
  return prefill.kind === 'INVALID' ? prefill.message : ''
})
let trigger: HTMLElement | undefined, openingPath = '', restoreOnClose = false, composing = false, alive = true, generation = 0, consumedEntry = ''

function usable(element: HTMLElement): boolean {
  return element.isConnected && element.getClientRects().length > 0 && !element.closest('[inert], [aria-hidden="true"]')
    && !element.matches(':disabled, [aria-disabled="true"]') && getComputedStyle(element).visibility !== 'hidden'
}
function otherModalOpen(): boolean {
  return [...document.querySelectorAll<HTMLElement>('.n-modal, [aria-modal="true"], [role="alertdialog"], dialog[open]')]
    .some(element => element !== panel.value && !panel.value?.contains(element) && !element.contains(panel.value ?? null) && usable(element))
}
function commandDisabled(command: WorkspaceCommand): boolean { return command.group === 'SEARCH' && searchRestricted.value }
function firstEnabledIndex(): number { return commands.value.findIndex(command => !commandDisabled(command)) }
function rememberOption(id: string, element: unknown) {
  if (element instanceof HTMLElement) optionElements.set(id, element)
  else optionElements.delete(id)
}
function open() {
  if (visible.value || !canOpen.value || document.visibilityState !== 'visible' || otherModalOpen()) return
  trigger = document.activeElement instanceof HTMLElement ? document.activeElement : undefined
  openingPath = route.fullPath
  const initial = route.name === 'today' && route.query.command === '1' && route.fullPath !== consumedEntry && typeof route.query.commandQuery === 'string' ? route.query.commandQuery : ''
  restoreOnClose = false; composing = false; message.value = ''; input.value = readSearchPrefill({ q: initial }).kind === 'VALID' ? initial : ''; generation++
  selectedIndex.value = firstEnabledIndex()
  visible.value = true
  consumedEntry = route.fullPath
}
function openFromEntry() {
  if (route.name === 'today' && route.query.command === '1' && route.fullPath !== consumedEntry && canOpen.value) void nextTick().then(() => open())
}
function close(restore = true) {
  if (!visible.value) return
  restoreOnClose = restore
  closing.value = true; visible.value = false; input.value = ''; message.value = ''; composing = false; selectedIndex.value = -1; generation++
}
function focusInput() {
  if (!visible.value || !alive || !props.enabled || !workspace.sessionActive || access.challenge || accessCommandLocked.value
    || document.visibilityState !== 'visible' || otherModalOpen() || !panel.value) return
  const inputElement = panel.value.querySelector<HTMLInputElement>('#command-filter')
  if (inputElement && usable(inputElement)) filterInput.value?.focus()
  if (inputElement && document.activeElement === inputElement) return
  const fallback = [...panel.value.querySelectorAll<HTMLElement>('input, button, [tabindex="0"]')]
    .find(element => element.tabIndex >= 0 && usable(element))
  const target = fallback ?? panel.value
  target.focus({ preventScroll: true })
}
function afterClose() {
  const original = trigger, restore = restoreOnClose
  trigger = undefined; restoreOnClose = false; closing.value = false
  if (restore && !visible.value && alive && props.enabled && workspace.sessionActive && !access.checking && !accessCommandLocked.value
    && document.visibilityState === 'visible' && route.fullPath === openingPath && original && usable(original) && !otherModalOpen()) original.focus({ preventScroll: true })
}
function editingTarget(event: KeyboardEvent): boolean {
  return event.composedPath().some(target => target instanceof HTMLElement && (target.isContentEditable
    || Boolean(target.closest('input, textarea, select, [contenteditable]:not([contenteditable="false"]), [role="textbox"], .cm-editor, .cm-content'))))
}
function ignoredKey(event: KeyboardEvent): boolean {
  return event.defaultPrevented || event.repeat || event.isComposing || composing || event.keyCode === 229 || event.altKey || event.shiftKey
}
function shortcut(event: KeyboardEvent) {
  if (ignoredKey(event) || event.ctrlKey === event.metaKey || event.key.toLowerCase() !== 'k' || editingTarget(event)
    || visible.value || !canOpen.value || otherModalOpen()) return
  event.preventDefault()
  open()
}
async function moveSelection(direction: number) {
  const length = commands.value.length
  if (!length || navigating.value) return
  let index = selectedIndex.value
  for (let count = 0; count < length; count++) {
    index = (index + direction + length) % length
    if (!commandDisabled(commands.value[index]!)) { selectedIndex.value = index; break }
  }
  await nextTick()
  if (visible.value && selected.value) optionElements.get(selected.value.id)?.scrollIntoView({ block: 'nearest' })
}
function inputKeydown(event: KeyboardEvent) {
  if (ignoredKey(event) || event.ctrlKey || event.metaKey) return
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault(); void moveSelection(event.key === 'ArrowDown' ? 1 : -1)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    if (selected.value) void execute(selected.value)
  }
}
function panelKeydown(event: KeyboardEvent) {
  if (event.defaultPrevented || event.repeat || event.isComposing || composing || event.keyCode === 229) return
  if (!event.altKey && event.ctrlKey !== event.metaKey && event.key.toLowerCase() === 'n') {
    const command = commands.value.find(item => item.id === (event.shiftKey ? 'create-memory-card' : 'create-note'))
    if (command) { event.preventDefault(); void execute(command) }
  } else if (event.key === 'Escape' && !event.altKey && !event.shiftKey && !event.ctrlKey && !event.metaKey) {
    event.preventDefault(); event.stopPropagation(); close()
  } else if (event.key === 'Tab' && !event.altKey && !event.ctrlKey && !event.metaKey && panel.value) {
    const focusable = [...panel.value.querySelectorAll<HTMLElement>('input, button, [tabindex="0"]')].filter(element => element.tabIndex >= 0 && usable(element))
    const first = focusable[0], last = focusable.at(-1)
    if (!first || !last) { event.preventDefault(); return }
    if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }
}
function containFocus(event: FocusEvent) {
  if (!visible.value || !panel.value || document.visibilityState !== 'visible') return
  if (otherModalOpen()) { close(false); return }
  if (event.target instanceof Node && !panel.value.contains(event.target)) focusInput()
}
async function execute(command: WorkspaceCommand) {
  if (!visible.value || composing || !canExecute.value || commandDisabled(command) || !commands.value.some(item => item.id === command.id)) return
  const request = generation, epoch = workspace.sessionEpoch, destination = router.resolve(command.target)
  navigating.value = true; message.value = ''
  try {
    // 所有目录项沿现有路由守卫；这里不重复询问保稿，也不代替资料授权。
    const failure = await router.push(command.target)
    if (!alive || request !== generation || epoch !== workspace.sessionEpoch || !props.enabled || !workspace.sessionActive || !visible.value) return
    if (isNavigationFailure(failure, NavigationFailureType.duplicated)) {
      if (destination.name === 'search' && route.fullPath === destination.fullPath) window.dispatchEvent(new Event(SEARCH_PREFILL_EVENT))
      close(false)
    } else if (failure) {
      if (access.challenge) close(false)
      else message.value = '导航已取消，筛选词继续保留。请处理当前页面的未保存内容或待确认请求，再选择命令。'
    } else close(false)
  } catch {
    if (alive && request === generation && visible.value) message.value = '页面未能打开，筛选词继续保留。请稍后重新选择命令。'
  } finally { navigating.value = false }
}
function leavePage() { close(false); trigger = undefined; restoreOnClose = false }
function visibilityChanged() { if (document.visibilityState !== 'visible') leavePage() }
defineExpose({ open, openFromEntry })

watch([input, searchRestricted], () => { selectedIndex.value = firstEnabledIndex(); message.value = '' })
watch([() => props.enabled, () => workspace.sessionActive, () => workspace.sessionEpoch], ([enabled, active], previous) => {
  if (!enabled || !active || previous && previous[2] !== workspace.sessionEpoch) leavePage()
}, { flush: 'sync' })
watch([() => access.challenge, accessCommandLocked], ([challenge, locked], previous) => {
  if (locked || challenge && previous && !previous[0]) close(false)
}, { flush: 'sync' })
watch(() => route.fullPath, () => { if (visible.value) close(false) }, { flush: 'sync' })
watch([canOpen, () => route.fullPath], openFromEntry, { immediate: true, flush: 'post' })
onMounted(() => {
  window.addEventListener('keydown', shortcut)
  window.addEventListener('pagehide', leavePage)
  document.addEventListener('visibilitychange', visibilityChanged)
  document.addEventListener('focusin', containFocus)
})
onBeforeUnmount(() => {
  alive = false; generation++; visible.value = false; input.value = ''; trigger = undefined; optionElements.clear()
  window.removeEventListener('keydown', shortcut)
  window.removeEventListener('pagehide', leavePage)
  document.removeEventListener('visibilitychange', visibilityChanged)
  document.removeEventListener('focusin', containFocus)
})
</script>

<template>
  <button v-if="showTrigger" type="button" class="command-trigger" :disabled="!canOpen" aria-haspopup="dialog" :aria-expanded="visible" aria-controls="global-command-panel" @click="open">
    <span>命令面板</span><kbd aria-label="Control K 或 Command K">Ctrl / ⌘ K</kbd>
  </button>
  <!-- 自行管理焦点进出，避免 NModal 在导航卸载或页面隐藏后自动聚焦旧节点。 -->
  <NModal :show="visible" class="studio-command-overlay" :trap-focus="false" :auto-focus="false" :close-on-esc="false" :mask-closable="false" :z-index="40"
    @mask-click="close()" @after-enter="focusInput" @after-leave="afterClose">
    <section id="global-command-panel" ref="panel" tabindex="-1" class="command-panel" role="dialog" aria-modal="true" aria-labelledby="command-title" aria-describedby="command-description" @keydown="panelKeydown">
      <header class="sr-only"><h2 id="command-title">命令面板</h2><p id="command-description">页面 · 新建 · 搜索。Enter 打开所选命令；检索词会写入地址和历史。</p></header>
      <div class="command-filter">
        <label for="command-filter" class="sr-only">查找命令或输入检索词</label>
        <RIcon name="search" :size="29" />
        <input id="command-filter" ref="filterInput" v-model="input" :disabled="navigating" placeholder="搜索知识、笔记、题目..."
          autocomplete="off" spellcheck="false" role="combobox" aria-autocomplete="list" aria-expanded="true" aria-controls="command-options"
          :aria-activedescendant="selected ? `command-option-${selected.id}` : undefined" aria-describedby="command-search-notice"
          @keydown="inputKeydown" @compositionstart="composing = true" @compositionend="composing = false" />
        <kbd>⌘K</kbd>
      </div>
      <div class="command-content">
        <p v-if="searchProblem" class="command-notice" role="status">{{ searchProblem }}</p>
        <p v-if="searchRestricted" class="command-notice" role="status">资料条件尚未允许搜索预填。可浏览静态页面目录，跳转仍需经过原页面的资料确认。</p>
        <p v-if="navigating || access.checking" class="command-notice" role="status">{{ navigating ? '正在核对离开条件并打开页面…' : '正在核对资料条件，筛选词继续保留。' }}</p>
        <p v-if="message" class="command-notice" role="status">{{ message }}</p>
        <div id="command-options" class="command-options" role="listbox" aria-label="可用命令" :aria-busy="navigating">
          <template v-for="(command, index) in navigationCommands" :key="command.id">
            <p v-if="index === 0 || navigationCommands[index - 1]?.group !== command.group" class="command-group" :class="{ 'command-create-heading': command.group === 'CREATE' }" role="presentation">{{ groupLabels[command.group] }}</p>
            <button :id="`command-option-${command.id}`" :ref="element => rememberOption(command.id, element)" type="button" role="option" tabindex="-1"
              :aria-selected="selected?.id === command.id" :aria-disabled="commandDisabled(command) || !canExecute" :disabled="commandDisabled(command) || !canExecute"
              class="command-item" :class="{ selected: selected?.id === command.id, compact: command.group === 'CREATE' }"
              @pointermove="!commandDisabled(command) && (selectedIndex = commands.findIndex(item => item.id === command.id))" @mousedown.prevent @click="execute(command)">
              <span class="command-icon"><RIcon :name="commandIcons[command.id] || (command.group === 'CREATE' ? 'file-plus' : 'file-text')" :size="38" /><RIcon v-if="command.id === 'create-memory-card'" class="command-card-plus" name="plus" :size="18" /></span>
              <span class="command-copy"><span class="command-label">{{ command.label }}</span><span v-if="command.group === 'PAGE'" class="command-detail">{{ command.description }}</span></span>
              <kbd v-if="commandShortcuts[command.id]" class="command-shortcut">{{ commandShortcuts[command.id] }}</kbd>
              <span v-else class="command-open"><span>{{ command.group === 'CREATE' ? '新建' : '打开' }}</span><span aria-hidden="true">↵</span></span>
            </button>
          </template>
          <template v-if="domainCommands.length">
            <p class="command-group command-domain-heading" role="presentation">{{ groupLabels.SEARCH }}</p>
            <div class="command-domains">
              <button v-for="(command, index) in domainCommands" :id="`command-option-${command.id}`" :key="command.id" :ref="element => rememberOption(command.id, element)" type="button" role="option" tabindex="-1"
                :aria-selected="selected?.id === command.id" :disabled="commandDisabled(command) || !canExecute" :class="{ selected: selected?.id === command.id }"
                @pointermove="!commandDisabled(command) && (selectedIndex = commands.findIndex(value => value.id === command.id))" @mousedown.prevent @click="execute(command)">
                <RIcon :name="domainIcons[index]!" :size="21" />{{ domainLabels[command.id.replace('search-', '')] }}
              </button>
            </div>
          </template>
          <p v-if="!commands.length" class="command-empty" role="status">没有匹配的固定命令。试试页面名称，或输入有效检索词预填搜索。</p>
        </div>
        <p id="command-search-notice" class="command-help"><RIcon name="info" :size="22" />到达搜索页后才会查询正文</p>
      </div>
      <footer><button type="button" @click="close()"><kbd>Esc</kbd> 关闭</button><i aria-hidden="true" /><span class="command-key-hint"><kbd>↑ ↓</kbd> 选择</span><span class="command-read-boundary">不自动读取正文<RIcon name="circle" :size="20" /></span></footer>
    </section>
  </NModal>
</template>

<style scoped>
.command-trigger { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; padding: 9px 10px; border: 1px solid var(--line); border-radius: 6px; text-align: left; font-size: 12px; }
.command-trigger:hover:not(:disabled) { background: var(--surface-soft); }
.command-trigger:disabled { opacity: .55; cursor: not-allowed; }
/* 1536×1024 下按25号参考定位；长目录和权限提示仅在内容区滚动。 */
.command-panel {
  --command-background: linear-gradient(110deg, #28241fee, #1e1b17f7 75%);
  --command-border: #51483e; --command-divider: #50443755; --command-text: #f1e8da;
  --command-muted: #b7a997; --command-group: #cfc6ba; --command-soft: #ffffff08;
  --command-selected: linear-gradient(100deg, #8c5c3b91, #684b3233); --command-marker: #e6b282;
  width: min(847px, calc(100vw - 32px)); height: min(728px, calc(100dvh - 32px));
  margin: clamp(16px, calc((100dvh - 754px) / 2), 135px) auto auto; left: 51.5px; align-self: flex-start;
  display: flex; flex-direction: column; overflow: hidden; padding: 0; border: 1px solid var(--command-border); border-radius: 32px;
  background: var(--command-background); color: var(--command-text); box-shadow: 0 24px 50px #0008;
  font-size: 20px; line-height: 1.5; letter-spacing: 0; transition: none !important;
}
.command-panel p { margin: 0; text-wrap: initial; }
.command-panel kbd { font-family: inherit; font-weight: 400; white-space: nowrap; }
.command-filter { position: relative; display: flex; align-items: center; gap: 15px; height: 82px; flex: none; padding: 0 28px; border-bottom: 1px solid var(--command-divider); background: #ffffff02; }
.command-filter::before { content: ""; position: absolute; inset: 12px 13px; background: #00000013; border-radius: 23px; pointer-events: none; }
.command-filter > :is(svg, input, kbd) { position: relative; }
.command-filter > svg { flex: none; color: var(--command-group); }
.command-filter input { flex: 1; min-width: 0; height: 58px; padding: 0; border: 0; border-radius: 0; outline: 0; color: inherit; background: transparent; font-size: 29px; }
.command-filter input::placeholder { color: var(--command-muted); opacity: 1; }
.command-filter > kbd { padding: 2px 8px; border-radius: 7px; background: var(--command-soft); color: var(--command-group); font-size: 19px; }
.command-content { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-width: thin; scrollbar-color: var(--command-border) transparent; }
.command-group { height: 55px; padding: 0 29px; display: flex; align-items: center; color: var(--command-group); font-size: 23px; line-height: 1.6; }
.command-group.command-create-heading { height: 57px; margin: 4px 29px 0; padding: 7px 0 10px; border-top: 1px solid var(--command-divider); }
.command-item { position: relative; display: flex; align-items: center; gap: 26px; width: calc(100% - 23px); height: 92px; margin-left: 10px; padding: 12px 24px; border-radius: 16px; text-align: left; }
.command-icon { position: relative; display: inline-flex; flex: none; color: var(--command-group); }
.command-copy { display: grid; flex: 1; min-width: 0; }
.command-label { font-size: 24px; line-height: 1.5; }
.command-detail { font-size: 20px; line-height: 1.6; color: var(--command-muted); }
.command-label, .command-detail { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.command-open { display: inline-flex; align-items: center; gap: 5px; flex: none; font-size: 20px; }
.command-open > span:first-child { padding: 3px 15px; border-radius: 20px; background: var(--command-soft); }
.command-item.compact { height: 57px; padding-block: 5px; }
.command-item.compact .command-icon > svg:first-child { width: 35px; height: 35px; }
.command-card-plus { position: absolute; right: -2px; bottom: -1px; background: var(--surface); }
.command-shortcut { flex: none; padding: 0 4px; border-radius: 7px; background: var(--command-soft); font-size: 20px; }
.command-item.selected, .command-domains > button.selected { background: var(--command-selected); }
.command-item.selected::before { content: ""; position: absolute; left: -11px; top: 0; bottom: 0; width: 2px; background: var(--command-marker); }
.command-group.command-domain-heading { height: 57px; margin: 0 29px; padding: 9px 0 10px; border-top: 1px solid var(--command-divider); }
.command-domains { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 29px; }
.command-domains > button { display: inline-flex; align-items: center; justify-content: center; flex: none; gap: 7px; height: 40px; padding: 3px 10px; border-radius: 22px; background: var(--command-soft); font-size: 19px; white-space: nowrap; }
.command-help { display: flex; align-items: center; gap: 10px; min-height: 66px; padding: 14px 29px 0; font-size: 20px; line-height: 1.6; color: var(--command-muted); }
.command-help > svg { flex: none; color: #7eb2cc; fill: #315d7b; }
.command-notice { margin: 10px 29px !important; padding: 10px 12px; border-radius: 8px; background: var(--command-soft); font-size: 15px; overflow-wrap: anywhere; }
.command-empty { padding: 20px 29px; color: var(--command-muted); font-size: 18px; }
.command-panel footer { display: flex; align-items: center; flex: none; gap: 0; height: 67px; padding: 0 28px; border-top: 1px solid var(--command-divider); background: #ffffff02; color: var(--command-muted); font-size: 21px; white-space: nowrap; }
.command-panel footer > button { padding: 0; color: inherit; }
.command-panel footer > i { height: 24px; width: 1px; margin: 0 16px; background: var(--command-border); }
.command-panel footer kbd { padding: 0 4px; border-radius: 5px; background: var(--command-soft); color: var(--command-text); font-size: inherit; }
.command-read-boundary { display: inline-flex; align-items: center; gap: 10px; margin-left: auto; }
.command-read-boundary > svg { flex: none; }
button:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
:global(.n-modal-container:has(.studio-command-overlay) > .n-modal-mask) { background: #0002; transition: none !important; }
:global(html[data-studio-theme="light"] #global-command-panel) {
  --command-background: var(--surface); --command-border: var(--line); --command-divider: var(--line);
  --command-text: var(--ink); --command-muted: var(--muted); --command-group: var(--ink); --command-soft: var(--surface-soft);
  --command-selected: var(--brand-soft); --command-marker: var(--brand);
}
@media (max-width: 1100px) { .command-panel { left: 0; } }
@media (max-width: 600px) {
  .command-panel { border-radius: 24px; }
  .command-filter { padding-inline: 18px; gap: 10px; }
  .command-filter input { font-size: 24px; }
  .command-item { gap: 14px; padding-inline: 14px; }
  .command-label { font-size: 19px; }
  .command-detail { font-size: 14px; }
  .command-open { display: none; }
  .command-group, .command-domains { padding-inline: 19px; }
  .command-group.command-create-heading, .command-group.command-domain-heading { margin-inline: 19px; padding-inline: 0; }
  .command-domains > button { font-size: 16px; }
  .command-help { padding-inline: 19px; font-size: 16px; }
  .command-panel footer { padding-inline: 19px; font-size: 16px; }
  .command-read-boundary { display: none; }
}
</style>
