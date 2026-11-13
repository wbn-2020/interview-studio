<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { isNavigationFailure, NavigationFailureType, useRoute, useRouter } from 'vue-router'
import { NInput, NModal } from 'naive-ui'
import type { InputInst } from 'naive-ui'
import type { WorkspaceCommand } from '../navigation/commands'
import { filterWorkspaceCommands, readSearchPrefill, SEARCH_PREFILL_EVENT, searchPrefillCommands, workspaceCommands } from '../navigation/commands'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import RIcon from '../replica/RIcon.vue'

const props = withDefaults(defineProps<{ enabled: boolean; showTrigger?: boolean }>(), { showTrigger: true })
const router = useRouter(), route = useRoute(), workspace = useWorkspaceStore(), access = useTrainingAccessStore()
const visible = ref(false), closing = ref(false), input = ref(''), selectedIndex = ref(-1), navigating = ref(false), message = ref('')
const panel = ref<HTMLElement>(), filterInput = ref<InputInst>()
const optionElements = new Map<string, HTMLElement>()
const accessCommandLocked = computed(() => Boolean(workspace.changes.get('training-access-command')?.locked))
const canOpen = computed(() => props.enabled && workspace.sessionActive && !access.checking && !access.challenge && !accessCommandLocked.value && !navigating.value && !closing.value)
const canExecute = computed(() => props.enabled && workspace.sessionActive && !access.checking && !access.challenge && !accessCommandLocked.value && !navigating.value)
const searchRestricted = computed(() => access.checking || access.challenge || access.restricted || accessCommandLocked.value)
const commands = computed(() => {
  const matches = filterWorkspaceCommands(input.value)
  const pages = matches.filter(command => command.group === 'PAGE')
  const create = matches.filter(command => command.group === 'CREATE')
  return [...(pages.length || !input.value.trim() ? pages : workspaceCommands.filter(command => command.id === 'knowledge' || command.id === 'today')),
    ...(input.value.trim() && !create.length ? workspaceCommands.filter(command => command.id === 'create-note' || command.id === 'create-memory-card') : create),
    ...searchPrefillCommands(input.value)]
})
const domainCommands = computed(() => commands.value.filter(command => command.group === 'SEARCH'))
const selected = computed(() => commands.value[selectedIndex.value])
const groupLabels = { PAGE: '页面', CREATE: '新建', SEARCH: '搜索八个域' }
const commandIcons: Record<string, string> = { today: 'calendar-days', knowledge: 'network', prepare: 'clipboard', training: 'play', review: 'refresh-cw', growth: 'chart-no-axes-combined', 'create-note': 'file-plus', 'create-memory-card': 'credit-card', recent: 'history', search: 'search' }
const domainLabels: Record<string, string> = { KNOWLEDGE: '知识', PREPARATION: '准备', MATERIAL_EXCERPT: '摘录', QUESTION: '题面', TRAINING_ANSWER: '回答', REVIEW_FEEDBACK: '反馈', RECOLLECTION: '回忆', AI_FEEDBACK: 'AI 观察' }
const domainIcons = ['book-open', 'list-checks', 'network', 'file-text', 'message-square', 'thumbs-up', 'history', 'sparkles']
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
  if (event.key === 'Escape' && !event.altKey && !event.shiftKey && !event.ctrlKey && !event.metaKey) {
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
defineExpose({ open })

watch([input, searchRestricted], () => { selectedIndex.value = firstEnabledIndex(); message.value = '' })
watch([() => props.enabled, () => workspace.sessionActive, () => workspace.sessionEpoch], ([enabled, active], previous) => {
  if (!enabled || !active || previous && previous[2] !== workspace.sessionEpoch) leavePage()
}, { flush: 'sync' })
watch([() => access.challenge, accessCommandLocked], ([challenge, locked], previous) => {
  if (locked || challenge && previous && !previous[0]) close(false)
}, { flush: 'sync' })
watch(() => route.fullPath, () => { if (visible.value) close(false) }, { flush: 'sync' })
watch([canOpen, () => route.fullPath], () => {
  if (route.name === 'today' && route.query.command === '1' && route.fullPath !== consumedEntry && canOpen.value) void nextTick().then(() => open())
}, { immediate: true, flush: 'post' })
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
  <NModal :show="visible" :trap-focus="false" :auto-focus="false" :close-on-esc="false" :mask-closable="false" :z-index="40"
    @mask-click="close()" @after-enter="focusInput" @after-leave="afterClose">
    <section id="global-command-panel" ref="panel" tabindex="-1" class="command-panel" role="dialog" aria-modal="true" aria-labelledby="command-title" aria-describedby="command-description" @keydown="panelKeydown">
      <header class="sr-only"><h2 id="command-title">命令面板</h2><p id="command-description">页面 · 新建 · 搜索</p></header>
      <div class="command-filter">
        <label for="command-filter" class="sr-only">查找命令或输入检索词</label>
        <RIcon name="search" :size="28" />
        <NInput ref="filterInput" v-model:value="input" :disabled="navigating" placeholder="搜索知识、笔记、题目..."
          :input-props="{ id: 'command-filter', autocomplete: 'off', role: 'combobox', 'aria-autocomplete': 'list', 'aria-expanded': true, 'aria-controls': 'command-options',
            'aria-activedescendant': selected ? `command-option-${selected.id}` : undefined, 'aria-describedby': 'command-search-notice',
            onKeydown: inputKeydown, onCompositionstart: () => composing = true, onCompositionend: () => composing = false }" />
        <kbd>⌘K</kbd>
      </div>
      <p v-if="searchProblem" class="command-notice" role="status">{{ searchProblem }}</p>
      <p v-if="searchRestricted" class="command-notice" role="status">资料条件尚未允许搜索预填。可浏览静态页面目录，跳转仍需经过原页面的资料确认。</p>
      <p v-if="navigating || access.checking" class="command-notice" role="status">{{ navigating ? '正在核对离开条件并打开页面…' : '正在核对资料条件，筛选词继续保留。' }}</p>
      <p v-if="message" class="command-notice" role="status">{{ message }}</p>
      <div id="command-options" class="command-options" role="listbox" aria-label="可用命令" :aria-busy="navigating">
        <template v-for="(command, index) in commands.filter(command => command.group !== 'SEARCH')" :key="command.id">
          <p v-if="index === 0 || commands[index - 1]?.group !== command.group" class="command-group" role="presentation">{{ groupLabels[command.group] }}</p>
          <button :id="`command-option-${command.id}`" :ref="element => rememberOption(command.id, element)" type="button" role="option" tabindex="-1"
            :aria-selected="selectedIndex === index" :aria-disabled="commandDisabled(command) || !canExecute" :disabled="commandDisabled(command) || !canExecute"
            :class="{ selected: selectedIndex === index }" @mouseenter="!commandDisabled(command) && (selectedIndex = index)" @mousedown.prevent @click="execute(command)">
            <RIcon :name="commandIcons[command.id] || (command.group === 'CREATE' ? 'file-plus' : 'file-text')" :size="35" /><span class="command-copy"><span class="command-label">{{ command.label }}</span><span class="command-detail">{{ command.description }}</span></span><span class="command-open">{{ command.group === 'CREATE' ? '新建' : '打开' }}<RIcon name="arrow-right" :size="18" /></span>
          </button>
        </template>
        <template v-if="domainCommands.length"><p class="command-group command-domain-heading" role="presentation">{{ groupLabels.SEARCH }}</p><div class="command-domains"><button v-for="(command, index) in domainCommands" :id="`command-option-${command.id}`" :key="command.id" :ref="element => rememberOption(command.id, element)" type="button" role="option" tabindex="-1" :aria-selected="selected?.id === command.id" :disabled="commandDisabled(command) || !canExecute" :class="{ selected: selected?.id === command.id }" @mouseenter="!commandDisabled(command) && (selectedIndex = commands.findIndex(value => value.id === command.id))" @mousedown.prevent @click="execute(command)"><RIcon :name="domainIcons[index]!" :size="21" />{{ domainLabels[command.id.replace('search-', '')] }}</button></div></template>
        <p v-if="!commands.length" class="command-empty" role="status">没有匹配的固定命令。试试页面名称，或输入有效检索词预填搜索。</p>
      </div>
      <p id="command-search-notice" class="command-help"><RIcon name="info" :size="22" />到达搜索页后才会查询正文。检索词会写入地址和历史。</p>
      <footer><button @click="close()"><kbd>Esc</kbd> 关闭</button><span>↑ ↓ 选择</span><span>Enter 打开</span><span class="command-read-boundary">不自动读取正文<RIcon name="circle" :size="20" /></span></footer>
    </section>
  </NModal>
</template>

<style scoped>
.command-trigger { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; padding: 9px 10px; border: 1px solid var(--line); border-radius: 6px; text-align: left; font-size: 12px; }
.command-trigger:hover:not(:disabled) { background: var(--surface-soft); }
.command-trigger:disabled { opacity: .55; cursor: not-allowed; }
kbd { color: var(--muted); font-family: inherit; font-size: 10px; line-height: 1.5; white-space: nowrap; }
.command-panel { width: min(620px, calc(100vw - 28px)); max-height: calc(100dvh - 48px); overflow-y: auto; display: flex; flex-direction: column; gap: 12px; padding: 22px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); color: var(--ink); }
header { display: flex; align-items: start; justify-content: space-between; gap: 16px; } h2, p { margin: 0; } h2 { font-size: 19px; } header p { margin-top: 5px; color: var(--muted); font-size: 12px; } header > button { flex: none; }
.command-filter { display: grid; gap: 6px; } .command-filter label, .command-help { font-size: 12px; color: var(--muted); }
.command-help, .command-notice, .command-detail { overflow-wrap: anywhere; }
.command-notice { padding: 8px 10px; border-radius: 5px; background: var(--surface-soft); font-size: 12px; }
.command-options { min-height: 90px; overflow-y: auto; overscroll-behavior: contain; padding: 0 3px 0 0; }
.command-group { padding: 13px 10px 7px; color: var(--muted); font-size: 11px; }
.command-options button { display: grid; gap: 4px; width: 100%; padding: 10px; border-radius: 5px; text-align: left; }
.command-options button.selected { background: var(--brand-soft); color: var(--brand-strong); }
.command-options button:disabled { opacity: .5; cursor: not-allowed; }
.command-label { font-size: 13px; font-weight: 600; }.command-detail { color: var(--muted); font-size: 12px; }
.command-empty { padding: 18px 10px; color: var(--muted); font-size: 13px; }
footer { display: flex; flex-wrap: wrap; gap: 16px; padding-top: 10px; border-top: 1px solid var(--line); font-size: 11px; color: var(--muted); }
button:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
@media (max-width: 600px) { .command-panel { max-height: calc(100dvh - 24px); padding: 16px; gap: 10px; } .command-options button { padding: 12px 9px; } footer { gap: 12px; } }
</style>

<style scoped>
.command-panel { width: min(790px, calc(100vw - 32px)); max-height: min(820px, calc(100dvh - 72px)); padding: 28px; gap: 16px; border: 1px solid #8b6a48; border-radius: 28px; background: linear-gradient(120deg, #2c241b, #15120e 75%); color: #f3eadc; box-shadow: 0 24px 80px #0009; }
.command-panel header h2 { font-size: 30px; }.command-panel header p { font-size: 16px; color: #b2a695; }.command-panel .command-filter { position: relative; display: block; }.command-filter > svg { position: absolute; left: 18px; top: 13px; z-index: 1; color: #bdab93; }.command-filter :deep(.n-input) { border-radius: 17px; padding-left: 38px; font-size: 22px; }.command-filter :deep(.n-input__input-el) { height: 54px; }.command-panel .command-help { font-size: 13px; color: #b1a38f; }.command-panel .command-group { padding: 18px 12px 10px; font-size: 16px; }.command-panel .command-options button { padding: 14px 18px; border-radius: 15px; gap: 6px; }.command-panel .command-options button.selected { background: linear-gradient(110deg, #a06d3b99, #67442177); box-shadow: inset 0 0 0 1px #c9945c99; color: #fff0db; }.command-panel .command-label { font-size: 22px; font-weight: 500; }.command-panel .command-detail { font-size: 15px; color: #c3b7a6; }.command-panel footer { font-size: 15px; padding-top: 18px; gap: 28px; }.command-panel .command-notice { font-size: 15px; }
</style>

<style scoped>
.command-panel { width: min(848px,calc(100vw - 32px)); padding: 12px 12px 0; gap: 0; border-color: var(--line); border-radius: 28px; color: var(--ink); background: var(--surface); }.command-panel .command-filter { padding: 0 0 12px; border-bottom: 1px solid var(--line); }.command-filter > svg { top: 16px; left: 18px; }.command-filter :deep(.n-input) { padding-right: 66px; border-radius: 24px; font-size: 27px; }.command-filter :deep(.n-input__input-el) { height: 58px; }.command-filter > kbd { position: absolute; top: 15px; right: 12px; padding: 2px 8px; border-radius: 6px; background: var(--surface-deep); color: var(--muted); font-size: 20px; }.command-panel .command-options { min-height: 0; max-height: min(550px,calc(100dvh - 230px)); padding: 0; }.command-panel .command-group { padding: 14px 17px 7px; font-size: 22px; color: var(--muted); }.command-panel .command-options > button { display: flex; align-items: center; gap: 24px; min-height: 84px; padding: 15px 22px; border-radius: 15px; }.command-options > button > svg { flex: none; color: var(--muted); }.command-copy { display: grid; gap: 5px; flex: 1; min-width: 0; }.command-panel .command-label { font-size: 23px; }.command-panel .command-detail { font-size: 17px; color: var(--muted); }.command-open { display: flex; gap: 10px; align-items: center; font-size: 18px; color: var(--muted); }.command-panel .command-domain-heading { border-top: 1px solid var(--line); margin: 12px 16px 0; padding-left: 0; }.command-domains { display: flex; flex-wrap: wrap; gap: 8px; padding: 9px 16px 13px; }.command-panel .command-domains button { display: flex; align-items: center; width: auto; gap: 6px; border-radius: 24px; padding: 7px 10px; font-size: 17px; background: var(--surface-soft); color: var(--ink); }.command-panel .command-options button.selected { background: var(--brand-soft); color: var(--brand-strong); box-shadow: inset 0 0 0 1px var(--brand); }.command-panel .command-help { display: flex; align-items: center; gap: 10px; font-size: 16px; color: var(--muted); padding: 15px 16px; }.command-panel .command-notice { padding: 10px 16px; margin: 10px; }.command-panel footer { align-items: center; padding: 18px 16px; border-top: 1px solid var(--line); font-size: 18px; gap: 22px; }.command-panel footer > button { padding: 0; }.command-panel footer kbd { font-size: 18px; background: var(--surface-deep); border-radius: 6px; padding: 2px 4px; }.command-read-boundary { display: inline-flex; align-items: center; gap: 10px; margin-left: auto; color: var(--muted); font-size: 17px; }
@media(max-width:600px) { .command-panel .command-options > button { gap: 12px; padding: 12px; }.command-panel .command-label { font-size: 19px; }.command-panel .command-detail { font-size: 14px; }.command-open { display: none; }.command-panel .command-domains button { font-size: 15px; }.command-panel footer { font-size: 15px; gap: 14px; }.command-read-boundary { display: none; } }
</style>
