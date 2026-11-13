import { h, render } from 'vue'
import { StateField } from '@codemirror/state'
import type { EditorState, Range } from '@codemirror/state'
import { Decoration, EditorView, WidgetType } from '@codemirror/view'
import type { DecorationSet } from '@codemirror/view'
import { markdown } from '@codemirror/lang-markdown'
import RIcon from '../replica/RIcon.vue'

const parser = markdown().language.parser

class Bullet extends WidgetType {
  eq() { return true }
  toDOM() {
    const element = document.createElement('span')
    element.className = 'cm-note-bullet'
    element.setAttribute('aria-hidden', 'true')
    return element
  }
}

class CopyCode extends WidgetType {
  constructor(readonly text: string) { super() }
  eq(other: CopyCode) { return other.text === this.text }
  toDOM() {
    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'cm-note-copy'
    button.title = '复制代码'
    button.setAttribute('aria-label', '复制代码')
    const icon = document.createElement('span')
    button.append(icon)
    render(h(RIcon, { name: 'copy', size: 23 }), icon)
    button.addEventListener('click', async () => {
      try { await navigator.clipboard.writeText(this.text); button.title = '代码已复制' }
      catch { button.title = '无法访问剪贴板，请选择代码后复制' }
    })
    return button
  }
  destroy(element: HTMLElement) {
    if (element.firstElementChild) render(null, element.firstElementChild)
  }
}

function decorate(state: EditorState): { visible: DecorationSet; hidden: DecorationSet } {
  const visible: Range<Decoration>[] = [], hidden: Range<Decoration>[] = []
  const lines = new Map<number, { classes: Set<string>; attributes: Record<string, string> }>()
  const line = (position: number, className: string, attributes: Record<string, string> = {}) => {
    const start = state.doc.lineAt(position).from
    const value = lines.get(start) ?? { classes: new Set<string>(), attributes: {} }
    value.classes.add(className); Object.assign(value.attributes, attributes); lines.set(start, value)
  }
  const hide = (from: number, to: number, block = false, widget?: WidgetType) => {
    if (to <= from) return
    const range = Decoration.replace({ block, widget }).range(from, to)
    visible.push(range); hidden.push(range)
  }
  const mark = (from: number, to: number, className: string) => {
    if (to > from) visible.push(Decoration.mark({ class: className }).range(from, to))
  }
  // 使用现有 Markdown 语法树改变呈现，不重写正文，也不把 HTML 或图片地址装入 DOM。
  parser.parse(state.doc.toString()).iterate({
    enter(ref) {
      const node = ref.node, name = node.name
      const heading = /^ATXHeading([1-6])$/.exec(name)
      if (heading) {
        line(node.from, `cm-note-heading cm-note-h${heading[1]}`)
        const marker = node.getChild('HeaderMark')
        if (marker) hide(marker.from, marker.to + (state.sliceDoc(marker.to, marker.to + 1) === ' ' ? 1 : 0))
      } else if (name === 'FencedCode' || name === 'CodeBlock') {
        const code = name === 'FencedCode' ? node.getChild('CodeText') : node
        for (const marker of node.getChildren('CodeMark')) {
          const fence = state.doc.lineAt(marker.from)
          hide(fence.from, Math.min(state.doc.length, fence.to + 1), true)
        }
        if (code) {
          const first = state.doc.lineAt(code.from), last = state.doc.lineAt(code.to)
          for (let number = first.number; number <= last.number; number++) {
            const source = state.doc.line(number)
            line(source.from, `cm-note-code${number === first.number ? ' cm-note-code-first' : ''}${number === last.number ? ' cm-note-code-last' : ''}`,
              { 'data-code-line': String(number - first.number + 1) })
          }
          visible.push(Decoration.widget({ widget: new CopyCode(state.sliceDoc(code.from, code.to)), side: 1 }).range(first.to))
        }
        return false
      } else if (name === 'ListMark') {
        const text = state.sliceDoc(node.from, node.to)
        line(node.from, 'cm-note-list')
        if (/^[-+*]$/.test(text)) hide(node.from, node.to + (state.sliceDoc(node.to, node.to + 1) === ' ' ? 1 : 0), false, new Bullet())
        else mark(node.from, node.to, 'cm-note-number')
      } else if (name === 'StrongEmphasis') mark(node.from, node.to, 'cm-note-strong')
      else if (name === 'Emphasis') mark(node.from, node.to, 'cm-note-emphasis')
      else if (name === 'EmphasisMark' || name === 'CodeMark' && node.parent?.name === 'InlineCode') hide(node.from, node.to)
      else if (name === 'InlineCode') mark(node.from, node.to, 'cm-note-inline-code')
      else if (name === 'QuoteMark') { line(node.from, 'cm-note-quote'); hide(node.from, node.to) }
      else if (name === 'Link') {
        mark(node.from, node.to, 'cm-note-link')
        for (const child of ['LinkMark', 'URL', 'LinkTitle'].flatMap(type => node.getChildren(type))) hide(child.from, child.to)
      } else if (name === 'Image' || name === 'HTMLBlock' || name === 'HTMLTag') return false
    },
  })
  for (const [position, value] of lines) visible.push(Decoration.line({
    class: [...value.classes].join(' '), attributes: value.attributes,
  }).range(position))
  return { visible: Decoration.set(visible, true), hidden: Decoration.set(hidden, true) }
}

export const formattedMarkdown = StateField.define({
  create: decorate,
  update(value, transaction) { return transaction.docChanged ? decorate(transaction.state) : value },
  provide: field => [
    EditorView.decorations.from(field, value => value.visible),
    EditorView.atomicRanges.of(view => view.state.field(field).hidden),
  ],
})
