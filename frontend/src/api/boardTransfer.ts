import { boardNodeReference, boardReferenceKey, type BoardDetail, type BoardEdge, type BoardNode, type BoardReference, type BoardViewport } from './boards'

export const boardTransferMaxBytes = 1024 * 1024
export type BoardTransferFormat = 'canvas' | 'markdown'
export type BoardTransferNode = { node: BoardNode; file: string | null }
export type BoardTransferPreview = { format: BoardTransferFormat; fileName: string; nodes: BoardTransferNode[]; edges: BoardEdge[]; warnings: string[] }
export type BoardTransferDraft = { nodes: BoardNode[]; edges: BoardEdge[]; viewport: BoardViewport; references: BoardReference[]; warnings: string[] }
export type BoardTransferExport = { text: string; extension: 'canvas' | 'md'; mediaType: string; warnings: string[] }
type JsonObject = Record<string, unknown>
const maximumCoordinate = 1_000_000

function object(value: unknown, context: string): JsonObject {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${context}必须是对象。`)
  return value as JsonObject
}
function text(value: unknown, maximum: number, context: string, nonempty = false): string {
  if (typeof value !== 'string' || value.length > maximum || value.includes('\0') || nonempty && !value.trim()) throw new Error(`${context}需要为${nonempty ? '非空' : ''}文本，最多 ${maximum} 字符。`)
  return value
}
function coordinate(value: unknown, context: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || Math.abs(value) > maximumCoordinate) throw new Error(`${context}需要为 ±1000000 内的有限数值。`)
  return value
}
function canvasCoordinate(value: unknown, context: string): number {
  const result = coordinate(value, context)
  if (!Number.isInteger(result)) throw new Error(`${context}需要使用 JSON Canvas 规定的整数像素。`)
  return result
}
function ignored(value: JsonObject, allowed: string[], context: string, warnings: Set<string>) {
  const keys = Object.keys(value).filter(key => !allowed.includes(key))
  if (keys.length) warnings.add(`${context}未采用的属性：${keys.slice(0, 12).map(key => key.slice(0, 80)).join('、')}${keys.length > 12 ? '等' : ''}。`)
}
function node(textValue: string, x: number, y: number, parentId: string | null = null): BoardNode {
  return { id: crypto.randomUUID(), type: 'TEXT', itemId: null, text: textValue, x, y, parentId, collapsed: false, group: null, referenceKind: null }
}
function bounded(nodes: BoardNode[], edges: BoardEdge[]) {
  if (nodes.length > 100 || edges.length > 200) throw new Error('画布最多包含 100 个节点和 200 条连线。')
  if (nodes.some(value => value.text.length > 5000) || nodes.reduce((sum, value) => sum + value.text.length, 0) > 50000) throw new Error('临时文本每节点最多 5000 字符，全部节点合计最多 50000 字符。')
}

export function previewBoardTransfer(source: string, format: BoardTransferFormat, fileName: string): BoardTransferPreview {
  if (!source.trim() || new TextEncoder().encode(source).byteLength > boardTransferMaxBytes) throw new Error('请选择不超过 1 MiB 的非空 UTF-8 文本文件。')
  if (source.includes('\0')) throw new Error('文件包含 NUL 字符，无法作为画布文本导入。')
  return format === 'canvas' ? parseCanvas(source.replace(/^\uFEFF/, ''), fileName) : parseMarkdown(source.replace(/^\uFEFF/, ''), fileName)
}

function parseCanvas(source: string, fileName: string): BoardTransferPreview {
  let parsed: unknown
  try { parsed = JSON.parse(source) } catch { throw new Error('文件不是有效的 JSON Canvas JSON。') }
  const root = object(parsed, 'Canvas 根')
  const rawNodes = root.nodes === undefined ? [] : root.nodes, rawEdges = root.edges === undefined ? [] : root.edges
  if (!Array.isArray(rawNodes) || !Array.isArray(rawEdges)) throw new Error('JSON Canvas 的 nodes 和 edges 必须是数组；未提供时按空数组处理。')
  if (rawNodes.length > 100 || rawEdges.length > 200) throw new Error('文件最多包含 100 个节点和 200 条连线，不能只截取其中一部分。')
  const warnings = new Set<string>()
  ignored(root, ['nodes', 'edges'], 'Canvas 顶层', warnings)
  const nodes: BoardTransferNode[] = [], edges: BoardEdge[] = []
  const identities = new Map<string, string>(), allIds = new Set<string>(), edgeIds = new Set<string>()
  for (const raw of rawNodes) {
    const value = object(raw, 'Canvas 节点'), id = text(value.id, 200, '节点 ID', true)
    if (allIds.has(id)) throw new Error(`节点 ID 重复：${id}。`)
    allIds.add(id)
    const kind = text(value.type, 40, '节点类型', true)
    const x = canvasCoordinate(value.x, '节点横坐标'), y = canvasCoordinate(value.y, '节点纵坐标')
    if (typeof value.width !== 'number' || !Number.isInteger(value.width) || value.width <= 0 || value.width > maximumCoordinate
      || typeof value.height !== 'number' || !Number.isInteger(value.height) || value.height <= 0 || value.height > maximumCoordinate) throw new Error('每个 Canvas 节点需要大于 0 且不超过 1000000 的整数宽度和高度。')
    if (kind !== 'text' && kind !== 'file') {
      warnings.add(`未导入 ${kind.slice(0, 40)} 类型节点及其相关连线；链接不加载，分组包围框不推断为脑图层级。`)
      continue
    }
    ignored(value, ['id', 'type', 'x', 'y', 'width', 'height', ...(kind === 'text' ? ['text'] : ['file'])], `节点 ${id.slice(0, 40)}`, warnings)
    const file = kind === 'file' ? text(value.file, 2000, '文件引用路径', true) : null
    const current = node(kind === 'text' ? text(value.text, 5000, '节点文本') : `外部文件引用：${file}`, x, y)
    identities.set(id, current.id); nodes.push({ node: current, file })
  }
  for (const raw of rawEdges) {
    const value = object(raw, 'Canvas 连线'), id = text(value.id, 200, '连线 ID', true)
    if (edgeIds.has(id)) throw new Error(`连线 ID 重复：${id}。`)
    edgeIds.add(id)
    const from = text(value.fromNode, 200, '连线起点', true), to = text(value.toNode, 200, '连线终点', true)
    if (!allIds.has(from) || !allIds.has(to) || from === to) throw new Error('连线两端必须是文件内两个不同节点。')
    ignored(value, ['id', 'fromNode', 'toNode', 'label'], `连线 ${id.slice(0, 40)}`, warnings)
    const label = value.label === undefined ? '' : text(value.label, 100, '连线标签')
    const sourceId = identities.get(from), targetId = identities.get(to)
    if (!sourceId || !targetId) { warnings.add('连接未采用节点的连线将一并遗漏。'); continue }
    edges.push({ id: crypto.randomUUID(), source: sourceId, target: targetId, label })
  }
  warnings.add('采用节点坐标与连线；卡片宽高、颜色、连线端点样式和原视口不带入问阶。所有节点、连线重新分配身份。')
  if (nodes.some(value => value.file)) warnings.add('文件路径仅作为待映射标识展示，不读取本机文件、不访问外部地址；每个文件节点须明确映射、保留为文字或跳过。')
  if (!nodes.length) throw new Error('文件内没有可以采用的 text 或 file 节点。')
  bounded(nodes.map(value => value.node), edges)
  return { format: 'canvas', fileName, nodes, edges, warnings: [...warnings] }
}

function parseMarkdown(source: string, fileName: string): BoardTransferPreview {
  const nodes: BoardTransferNode[] = []
  const headings: { level: number; id: string }[] = [], lists: { indent: number; id: string }[] = []
  let last: BoardNode | undefined, fence: { marker: string; size: number } | undefined, characters = 0
  const add = (content: string, parent: string | null, depth: number) => {
    const value = node(content, Math.min(depth * 290 + 40, maximumCoordinate), nodes.length * 220 + 40, parent)
    nodes.push({ node: value, file: null }); last = value; characters += content.length
    bounded(nodes.map(value => value.node), [])
    return value
  }
  const append = (line: string) => {
    if (!last) last = add('', null, 0)
    const extra = `${last.text ? '\n' : ''}${line}`
    if (last.text.length + extra.length > 5000 || characters + extra.length > 50000) throw new Error('临时文本每节点最多 5000 字符，全部节点合计最多 50000 字符。')
    last.text += extra; characters += extra.length
  }
  for (const raw of source.replace(/\r\n?/g, '\n').split('\n')) {
    if (/^\s*\t/.test(raw)) throw new Error('脑图层级使用空格缩进；请先把行首 Tab 明确转换为空格再导入。')
    const marker = /^ {0,3}(`{3,}|~{3,})/.exec(raw)
    if (fence) {
      append(raw)
      if (marker && marker[1]![0] === fence.marker && marker[1]!.length >= fence.size && /^\s*(?:`+|~+)\s*$/.test(raw)) fence = undefined
      continue
    }
    if (marker) {
      append(raw); fence = { marker: marker[1]![0]!, size: marker[1]!.length }; continue
    }
    const heading = /^(#{1,6})[ \t]+(.+?)\s*#*\s*$/.exec(raw)
    if (heading) {
      const level = heading[1]!.length
      while (headings.length && headings.at(-1)!.level >= level) headings.pop()
      const value = add(heading[2]!, headings.at(-1)?.id ?? null, headings.length)
      headings.push({ level, id: value.id }); lists.length = 0; continue
    }
    const list = /^( *)(?:[-+*]|\d+[.)])[ \t]+(.+)$/.exec(raw)
    if (list) {
      const indent = list[1]!.length
      while (lists.length && lists.at(-1)!.indent >= indent) lists.pop()
      const parent = lists.at(-1)?.id ?? headings.at(-1)?.id ?? null
      const value = add(list[2]!, parent, headings.length + lists.length)
      lists.push({ indent, id: value.id }); continue
    }
    if (!last && !raw.trim()) continue
    append(raw)
  }
  if (!nodes.length) throw new Error('Markdown 文件没有可导入的内容。')
  bounded(nodes.map(value => value.node), [])
  return { format: 'markdown', fileName, nodes, edges: [], warnings: [
    '按 ATX 标题和空格缩进列表建立真实父子层级；普通段落、代码块作为所在节点正文，链接与 HTML 只保留文本。',
    'Markdown 不恢复站内对象身份、权限、原坐标、分组、折叠或自由连线；所有内容作为临时文本进入草稿。',
  ] }
}

export function materializeBoardTransfer(preview: BoardTransferPreview, decisions: Record<string, string>, references: BoardReference[]): BoardTransferDraft {
  const available = new Map(references.map(reference => [boardReferenceKey(reference.itemId, reference.referenceKind), reference]))
  const selected = new Map<string, BoardReference>(), nodes: BoardNode[] = []
  let skipped = 0
  for (const value of preview.nodes) {
    if (!value.file) { nodes.push({ ...value.node }); continue }
    const choice = decisions[value.node.id]
    if (!choice) throw new Error('请为每个文件节点选择映射、保留为文字或跳过。')
    if (choice === 'SKIP') { skipped++; continue }
    if (choice === 'TEXT') { nodes.push({ ...value.node }); continue }
    const reference = available.get(choice)
    if (!reference) throw new Error('所选引用不在当前已读取的画布引用中，请重新选择。')
    selected.set(choice, reference)
    nodes.push({ ...value.node, type: 'REFERENCE', itemId: reference.itemId, text: '', referenceKind: reference.referenceKind ?? 'KNOWLEDGE' })
  }
  const ids = new Set(nodes.map(value => value.id))
  const edges = preview.edges.filter(value => ids.has(value.source) && ids.has(value.target)).map(value => ({ ...value }))
  bounded(nodes, edges)
  if (!nodes.length) throw new Error('至少需要保留一个节点。')
  const warnings = [...preview.warnings]
  if (skipped) warnings.push(`明确跳过 ${skipped} 个文件节点和 ${preview.edges.length - edges.length} 条相连连线。`)
  return { nodes, edges, viewport: { x: 0, y: 0, zoom: 1 }, references: [...selected.values()], warnings }
}

function referenceText(reference: BoardReference): string {
  const kind = reference.referenceKind ?? 'KNOWLEDGE'
  const locator = kind === 'ATTACHMENT' ? `attachment:${reference.itemId}；元数据修订 ${reference.attachmentRevision ?? '未知'}`
    : `${kind === 'QUESTION' ? 'question' : 'knowledge'}:${reference.itemId}；版本 ${reference.versionNumber}（${reference.versionId}）`
  return `${reference.title}\n${locator}\n\n${reference.body}\n\n（仅画布中的最多 2000 字预览，非完整原文；不包含题目参考答案或附件二进制。）`
}

export function exportBoardTransfer(board: BoardDetail, format: BoardTransferFormat): BoardTransferExport {
  const warnings = ['引用只包含该画布已读取的标题、来源身份和最多 2000 字预览；不包含完整知识、题目参考答案、附件原件、权限或恢复凭据。']
  if (format === 'canvas') {
    const identities = new Set(board.nodes.map(value => value.id)), pairs = new Set(board.edges.map(value => `${value.source}:${value.target}`))
    const nodes = board.nodes.map(value => {
      const reference = boardNodeReference(board, value)
      if (value.type === 'REFERENCE' && !reference) throw new Error('引用预览不完整，请重新读取画布后导出。')
      return { id: value.id, type: 'text', x: Math.round(value.x), y: Math.round(value.y), width: 260, height: 200, text: reference ? referenceText(reference) : value.text }
    })
    const edges = board.edges.map(value => ({ id: value.id, fromNode: value.source, toNode: value.target, label: value.label }))
    for (const value of board.nodes) if (value.parentId && identities.has(value.parentId) && !pairs.has(`${value.parentId}:${value.id}`)) {
      edges.push({ id: crypto.randomUUID(), fromNode: value.parentId, toNode: value.id, label: '脑图层级' })
    }
    warnings.push('导出符合 JSON Canvas 的文本节点与连线；坐标按规范取整数像素，站内引用转换为明文定位说明，脑图层级转换为带标签的连线。分组、折叠、视口与修订历史不包含在此文件中。')
    if (edges.length > 200) warnings.push(`脑图层级展开后合计 ${edges.length} 条连线，超过问阶单次导入的 200 条限制；若需重新导入，请先在目标画布工具中明确拆分。`)
    return { text: JSON.stringify({ nodes, edges }, null, 2), extension: 'canvas', mediaType: 'application/json;charset=utf-8', warnings }
  }
  const lines = [`# ${board.title.replace(/[\r\n]/g, ' ')}`, '', `画布修订：${board.revision}。下列缩进列表表示父子层级。`, '']
  const visited = new Set<string>()
  const append = (value: BoardNode, depth: number) => {
    if (visited.has(value.id)) throw new Error('画布父子层级存在循环，不能导出为脑图。')
    visited.add(value.id)
    const reference = boardNodeReference(board, value)
    if (value.type === 'REFERENCE' && !reference) throw new Error('引用预览不完整，请重新读取画布后导出。')
    const content = (reference ? referenceText(reference) : value.text || '（空白想法）').replace(/\r\n?/g, '\n').split('\n')
    lines.push(`${'  '.repeat(depth)}- ${content[0]}`, ...content.slice(1).map(line => `${'  '.repeat(depth + 1)}${line}`))
    for (const child of board.nodes.filter(node => node.parentId === value.id)) append(child, depth + 1)
  }
  for (const root of board.nodes.filter(value => !value.parentId)) append(root, 0)
  if (visited.size !== board.nodes.length) throw new Error('画布含失联或循环的父子层级，无法完整导出为脑图。')
  if (board.edges.length) {
    lines.push('', '## 自由连线记录（不属于脑图父子结构）', '', '这些连线仅作为文字记录，重新导入 Markdown 不会还原为画布线。', '')
    for (const edge of board.edges) lines.push(`- ${edge.source} → ${edge.target}${edge.label ? `：${edge.label.replace(/[\r\n]/g, ' ')}` : ''}`)
  }
  warnings.push('Markdown 缩进列表保留父子层级；自由连线只在末尾单列文字记录，不把自由图推断为树。坐标、分组、折叠、视口与修订历史不包含在此文件中。')
  return { text: `${lines.join('\n')}\n`, extension: 'md', mediaType: 'text/markdown;charset=utf-8', warnings }
}
