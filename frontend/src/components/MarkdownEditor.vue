<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { Compartment, EditorState } from '@codemirror/state'
import { EditorView, drawSelection, keymap } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
import { formattedMarkdown } from './formattedMarkdown'
import RIcon from '../replica/RIcon.vue'
const props = defineProps<{ modelValue: string; disabled?: boolean; toolbar?: boolean; formatted?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const element = ref<HTMLDivElement>()
const editable = new Compartment()
const appearance = new Compartment()
const rawMode = ref(false), moreOpen = ref(false)
let view: EditorView | undefined
let applyingModel = false
const tools = [
  { icon: 'bold', label: '加粗', before: '**', after: '**' }, { icon: 'italic', label: '斜体', before: '*', after: '*' },
  { icon: 'code', label: '代码', before: '`', after: '`' }, { icon: 'quote', label: '引用', before: '> ', after: '' },
  { icon: 'list', label: '项目列表', before: '- ', after: '' }, { icon: 'list-ordered', label: '编号列表', before: '1. ', after: '' },
  { icon: 'link', label: '插入链接', before: '[', after: '](https://)' }, { icon: 'image', label: '插入图片', before: '![', after: '](https://)' },
  { icon: 'table', label: '插入表格', before: '\n| 列 | 列 |\n| --- | --- |\n| ', after: ' |  |\n' },
]
const moreTools = [
  { icon: 'heading-1', label: '一级标题', before: '# ', after: '' },
  { icon: 'heading-2', label: '二级标题', before: '## ', after: '' },
  { icon: 'minus', label: '分隔线', before: '\n---\n', after: '' },
]
function format(tool: typeof tools[number]) {
  if (!view || props.disabled) return
  const selection = view.state.selection.main, selected = view.state.sliceDoc(selection.from, selection.to)
  view.dispatch({ changes: { from: selection.from, to: selection.to, insert: tool.before + selected + tool.after },
    selection: { anchor: selection.from + tool.before.length, head: selection.from + tool.before.length + selected.length } })
  view.focus()
}
onMounted(() => {
  view = new EditorView({ parent: element.value, state: EditorState.create({
    doc: props.modelValue,
    extensions: [markdown(), appearance.of(props.formatted ? formattedMarkdown : []), history(), drawSelection(), keymap.of([...defaultKeymap, ...historyKeymap]), EditorView.lineWrapping,
      EditorView.contentAttributes.of({ 'aria-label': 'Markdown 正文' }), editable.of([EditorView.editable.of(!props.disabled), EditorState.readOnly.of(Boolean(props.disabled))]),
      EditorView.updateListener.of(update => { if (update.docChanged && !applyingModel) emit('update:modelValue', update.state.doc.toString()) }),
    ],
  }) })
})
watch(() => props.modelValue, value => {
  if (!view || value === view.state.doc.toString()) return
  // 外部原文只同步视图，不能回传编辑器的LF表示改写CRLF；主动编辑后再按现有规则输出。
  applyingModel = true
  try { view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } }) }
  finally { applyingModel = false }
})
watch(() => props.disabled, value => view?.dispatch({ effects: editable.reconfigure([EditorView.editable.of(!value), EditorState.readOnly.of(Boolean(value))]) }))
watch([() => props.formatted, rawMode], ([formatted, raw]) => view?.dispatch({ effects: appearance.reconfigure(formatted && !raw ? formattedMarkdown : []) }))
onUnmounted(() => view?.destroy())
</script>
<template><div class="markdown-editor-frame" :class="{ 'with-toolbar': toolbar, 'formatted-note': formatted && !rawMode }"><div v-if="toolbar" class="markdown-toolbar" role="toolbar" aria-label="正文格式"><button v-for="tool in tools" :key="tool.label" type="button" :title="tool.label" :aria-label="tool.label" :disabled="disabled" @mousedown.prevent @click="format(tool)"><RIcon :name="tool.icon" :size="23" /></button><div class="markdown-more"><button type="button" title="更多格式" aria-label="更多格式" :aria-expanded="moreOpen" @click="moreOpen = !moreOpen"><RIcon name="ellipsis" :size="23" /></button><div v-if="moreOpen" class="markdown-more-menu" @keydown.esc.stop="moreOpen = false"><button v-for="tool in moreTools" :key="tool.label" type="button" :disabled="disabled" @mousedown.prevent @click="format(tool); moreOpen = false">{{ tool.label }}</button><button v-if="formatted" type="button" :aria-pressed="rawMode" @click="rawMode = !rawMode; moreOpen = false">{{ rawMode ? '排版编辑' : '源码编辑' }}</button></div></div></div><div ref="element" class="markdown-editor" /></div></template>

<style scoped>
.markdown-editor-frame { min-width: 0; }.markdown-toolbar { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; padding: 13px 14px; border-bottom: 1px solid var(--line); }.markdown-toolbar button { display: inline-grid; place-items: center; width: 38px; height: 38px; padding: 0; border-radius: 7px; color: var(--muted); }.markdown-toolbar button:hover:not(:disabled) { background: var(--surface-deep); color: var(--ink); }.markdown-toolbar button:disabled { opacity: .4; }.markdown-toolbar button:focus-visible { outline: 2px solid var(--brand); }.with-toolbar .markdown-editor { border: 0; border-radius: 0; }.with-toolbar :deep(.cm-content) { padding: 25px 20px; font-size: 21px; line-height: 1.85; min-height: 480px; }.with-toolbar :deep(.cm-editor) { background: transparent; }
.markdown-more { position: relative; margin-left: auto; }.markdown-more-menu { position: absolute; right: 0; top: 42px; z-index: 20; display: grid; min-width: 145px; padding: 6px; border: 1px solid var(--line); border-radius: 9px; background: var(--surface); }.markdown-more-menu button { display: block; width: 100%; height: auto; min-height: 38px; padding: 8px 12px; text-align: left; font-size: 15px; white-space: nowrap; }
.formatted-note :deep(.cm-scroller) { font-family: inherit; }.formatted-note :deep(.cm-content) { font-family: inherit; font-size: 21px; line-height: 1.65; padding: 24px; }.formatted-note :deep(.cm-line) { padding-inline: 0; }.formatted-note :deep(.cm-note-heading) { font-weight: 600; line-height: 1.45; padding-block: 0 16px; }.formatted-note :deep(.cm-note-h1) { font-size: 34px; }.formatted-note :deep(.cm-note-h2) { font-size: 30px; }.formatted-note :deep(.cm-note-h3) { font-size: 25px; }.formatted-note :deep(.cm-note-strong) { font-weight: 700; }.formatted-note :deep(.cm-note-emphasis) { font-style: italic; }.formatted-note :deep(.cm-note-inline-code) { color: #a8d3e2; font-family: Consolas,monospace; }.formatted-note :deep(.cm-note-list) { padding-block: 5px; }.formatted-note :deep(.cm-note-bullet) { display: inline-block; width: 7px; height: 7px; vertical-align: .15em; border-radius: 50%; margin-right: 23px; background: var(--brand); }.formatted-note :deep(.cm-note-number) { color: var(--brand-strong); }.formatted-note :deep(.cm-note-link) { color: #a8d3e2; text-decoration: underline; text-underline-offset: 4px; }.formatted-note :deep(.cm-note-quote) { border-left: 1px solid var(--brand); padding-left: 18px; color: var(--muted); }
.formatted-note :deep(.cm-note-code) { position: relative; min-height: 38px; padding: 4px 52px 4px 60px; border-inline: 1px solid var(--line); background: var(--bg); font-family: Consolas,monospace; font-size: 20px; line-height: 1.6; color: #a8d3e2; }.formatted-note :deep(.cm-note-code::before) { content: attr(data-code-line); position: absolute; left: 0; top: 4px; width: 44px; padding-right: 12px; text-align: right; border-right: 1px solid var(--line); color: var(--faint); }.formatted-note :deep(.cm-note-code-first) { padding-top: 14px; border-top: 1px solid var(--line); border-radius: 9px 9px 0 0; }.formatted-note :deep(.cm-note-code-first::before) { top: 14px; }.formatted-note :deep(.cm-note-code-last) { padding-bottom: 14px; border-bottom: 1px solid var(--line); border-radius: 0 0 9px 9px; }.formatted-note :deep(.cm-note-code-first.cm-note-code-last) { border-radius: 9px; }.formatted-note :deep(.cm-note-copy) { position: absolute; right: 12px; top: 12px; display: grid; place-items: center; width: 27px; height: 30px; padding: 0; color: var(--muted); }.formatted-note :deep(.cm-note-copy:focus-visible) { outline: 2px solid var(--brand); }
</style>
