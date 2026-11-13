import { commonmarkLanguage } from '@codemirror/lang-markdown'
import { findImportFrontmatter } from './knowledgeTextMetadata'

type Occurrence = { from: number; to: number; original: string; label?: string }
export type ImportLink = {
  key: string
  kind: 'WIKI' | 'RELATIVE' | 'KNOWLEDGE'
  target: string
  occurrences: Occurrence[]
}
export type ImportLinkPlan = { source: string; links: ImportLink[]; problem?: string }

const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
const opaqueNodes = new Set(['FencedCode', 'CodeBlock', 'InlineCode', 'HTMLBlock', 'HTMLTag',
  'CommentBlock', 'Comment', 'ProcessingInstructionBlock', 'ProcessingInstruction', 'Image', 'LinkReference', 'Autolink'])

export function inspectImportLinks(source: string): ImportLinkPlan {
  const unavailable = (problem: string): ImportLinkPlan => ({ source, links: [], problem })
  if (source.length > 100000) return unavailable('正文超过100000字符，不能解析链接。')
  if (/\r(?!\n)/.test(source)) return unavailable('当前链接映射支持LF／CRLF换行；此文件含单独CR，只能保留原文，不会自动改换行。')
  let metadataEnd = 0
  try { metadataEnd = findImportFrontmatter(source)?.bodyStart ?? 0 }
  catch (error) { return unavailable(error instanceof Error ? error.message : '无法安全划定元数据范围，请保留原文。') }
  const blocked: { from: number; to: number }[] = metadataEnd ? [{ from: 0, to: metadataEnd }] : []
  const links = new Map<string, ImportLink>()
  let count = 0
  function add(kind: ImportLink['kind'], target: string, from: number, to: number, label?: string) {
    count++
    if (count > 200) return
    const key = `${kind}:${target}`
    let link = links.get(key)
    if (!link) { link = { key, kind, target, occurrences: [] }; links.set(key, link) }
    link.occurrences.push({ from, to, original: source.slice(from, to), label })
  }
  // 使用原文坐标，仅替换用户选中的片段；不能通过渲染或重序列化改写其他正文和换行。
  commonmarkLanguage.parser.parse(source).iterate({ enter(node) {
    if (node.name === 'Document') return
    if (node.to <= metadataEnd) return false
    if (opaqueNodes.has(node.name)) { blocked.push({ from: node.from, to: node.to }); return false }
    if (node.name !== 'Link') return
    const url = node.node.getChild('URL')
    if (!url && !node.node.getChild('LinkLabel')) return
    blocked.push({ from: node.from, to: node.to })
    if (url && node.from >= metadataEnd && source[node.from] === '[') {
      const raw = source.slice(url.from, url.to)
      const target = raw.startsWith('<') && raw.endsWith('>') ? raw.slice(1, -1) : raw
      if (target.length <= 500 && !/[\s<>\\]/.test(target)) {
        if (/^knowledge:[^:#?^]+$/i.test(target)) add('KNOWLEDGE', target, url.from, url.to)
        else if (!/[:#?]/.test(target) && !target.startsWith('/') && /\.(md|markdown|txt)$/i.test(target)) {
          add('RELATIVE', target, url.from, url.to)
        }
      }
    }
    return false
  } })
  if (count > 200) return unavailable('可映射链接超过200处，本次不提供部分映射；可保留全文，或先在原编辑器中拆分。')
  blocked.sort((a, b) => a.from - b.from)
  let region = 0, depth = 0, openingAt = 0, openingRegion = 0, nested = false
  for (const token of source.matchAll(/\[\[|\]\]|\n/g)) {
    if (token[0] === '\n') { depth = 0; continue }
    const position = token.index, end = position + token[0].length
    while (blocked[region] && blocked[region]!.to <= position) region++
    if (blocked[region] && blocked[region]!.from < end) continue
    if (token[0] === '[[') {
      if (!depth) { openingAt = position; openingRegion = region; nested = false }
      else nested = true
      depth++; continue
    }
    if (!depth) continue
    depth--
    if (depth || nested) continue
    const from = openingAt, to = end
    if (blocked[openingRegion] && blocked[openingRegion]!.from < to) continue
    // 嵌套或跨代码片段的Wiki结构整体保留，不能只把其中一个内层链接改写。
    const match = /^\[\[([^[\]\r\n|]{1,500})(?:\|([^[\]\r\n|]{1,500}))?\]\]$/.exec(source.slice(from, to))
    if (!match) continue
    const target = match[1]!
    if (source[from - 1] === '!' || source[from - 1] === '[' || source[to] === ']'
      || /[#^\\]/.test(target) || !target.trim() || match[2] !== undefined && !match[2].trim()) continue
    let escapes = 0
    for (let index = from - 1; index >= 0 && source[index] === '\\'; index--) escapes++
    if (escapes % 2) continue
    add('WIKI', target, from, to, match[2] ?? target)
    if (count > 200) return unavailable('可映射链接超过200处，本次不提供部分映射；可保留全文，或先在原编辑器中拆分。')
  }
  return { source, links: [...links.values()].sort((a, b) => a.occurrences[0]!.from - b.occurrences[0]!.from) }
}

export function rewriteImportLinks(plan: ImportLinkPlan, targets: Readonly<Record<string, string>>): string {
  if (plan.problem || Object.keys(targets).some(key => !plan.links.some(link => link.key === key))) {
    throw new Error('链接预览已失效，请重新解析。')
  }
  const edits: (Occurrence & { replacement: string })[] = []
  for (const link of plan.links) {
    const id = targets[link.key]
    if (id === undefined) continue
    if (!uuid.test(id)) throw new Error('所选知识标识无效，请重新选择。')
    for (const occurrence of link.occurrences) {
      // Wiki显示名按纯文本转义；原Markdown只换目的地址，显示名和title属性保持原样。
      const replacement = occurrence.label === undefined ? `knowledge:${id}`
        : `[${occurrence.label.replace(/\\/g, '\\\\').replace(/([\[\]`*_{}()<>!#|~&])/g, '\\$1')}](knowledge:${id})`
      edits.push({ ...occurrence, replacement })
    }
  }
  edits.sort((a, b) => a.from - b.from)
  let offset = 0, result = ''
  for (const edit of edits) {
    if (edit.from < offset || edit.from < 0 || edit.to > plan.source.length || edit.to <= edit.from
      || plan.source.slice(edit.from, edit.to) !== edit.original) throw new Error('链接原文位置无法核对，请重新解析。')
    result += plan.source.slice(offset, edit.from) + edit.replacement
    offset = edit.to
  }
  return result + plan.source.slice(offset)
}
