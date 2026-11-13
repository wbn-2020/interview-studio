import { FAILSAFE_SCHEMA, load } from 'js-yaml'

type Frontmatter = { headerStart: number; contentStart: number; contentEnd: number; bodyStart: number }
type MetadataField = { key: string; state: 'TITLE' | 'DISPLAY' | 'UNSUPPORTED' | 'UNKNOWN'; values?: string[] }
export type ImportMetadata = { source: string; span?: Frontmatter; fields: MetadataField[]; title?: string; problem?: string }

export function findImportFrontmatter(source: string): Frontmatter | undefined {
  const opening = /^\uFEFF?---[ \t]*(?:\r?\n|$)/.exec(source)
  if (!opening) return undefined
  const closing = /^(?:---|\.\.\.)[ \t]*(?:\r?\n|$)/gm
  closing.lastIndex = opening[0].length
  const match = closing.exec(source)
  if (!match) throw new Error('开头元数据块没有闭合，无法安全划定正文；仍可保留原文导入。')
  return { headerStart: source.startsWith('\uFEFF') ? 1 : 0, contentStart: opening[0].length,
    contentEnd: match.index, bodyStart: match.index + match[0].length }
}

export function inspectImportMetadata(source: string): ImportMetadata {
  const unavailable = (problem: string): ImportMetadata => ({ source, fields: [], problem })
  if (source.length > 100000 || /\r(?!\n)/.test(source)) return unavailable('元数据预览支持最多100000字符及LF／CRLF；此文件仍可保留原文导入。')
  let span: Frontmatter | undefined
  try { span = findImportFrontmatter(source) } catch (error) {
    return unavailable(error instanceof Error ? error.message : '无法划定元数据范围，请保留原文。')
  }
  if (!span) return unavailable('文件开头没有独立的YAML元数据块，标题及正文仍按文件原文处理。')
  if (span.contentEnd - span.contentStart > 16384) return unavailable('元数据内容超过16384字符，不提供部分解析；仍可保留原文导入。')
  let value: unknown, nodes = 0
  try {
    // 不推断日期、数字或执行自定义类型；锚点与超限结构整体拒绝，不递归展开未知值。
    value = load(source.slice(span.contentStart, span.contentEnd), { schema: FAILSAFE_SCHEMA, json: false, maxDepth: 8,
      listener(event, state) {
        if (event === 'open' && ++nodes > 512 || event === 'close' && state.anchor !== null) throw new Error('UNSUPPORTED_METADATA_STRUCTURE')
      } })
  } catch { return unavailable('YAML无法安全解析：可能含重复属性、锚点、不支持的类型或超出结构限制；未采用任何属性，仍可保留原文。') }
  if (value === undefined || value === null) value = {}
  if (typeof value !== 'object' || Array.isArray(value)) return unavailable('元数据顶层须为属性映射，不能从单值或列表猜测标题；请保留原文。')
  const properties = value as Record<string, unknown>
  const keys = Object.keys(properties)
  if (keys.length > 64 || keys.some(key => key.length > 200 || /[\u0000-\u001f\u007f]/.test(key))) {
    return unavailable('元数据最多64个属性，属性名最多200字符且不含控制字符；不提供截断预览。')
  }
  const text = (item: unknown): item is string => typeof item === 'string' && item.length <= 200
    && Boolean(item.trim()) && !/[\u0000-\u001f\u007f]/.test(item)
  const fields: MetadataField[] = []
  let title: string | undefined
  for (const key of keys) {
    const item = properties[key]
    if (key === 'title') {
      if (text(item)) { title = item; fields.push({ key, state: 'TITLE', values: [item] }) }
      else fields.push({ key, state: 'UNSUPPORTED' })
    } else if (['aliases', 'alias', 'tags', 'tag'].includes(key)) {
      const values = typeof item === 'string' ? [item] : item
      fields.push(Array.isArray(values) && values.length <= 50 && values.every(text)
        ? { key, state: 'DISPLAY', values } : { key, state: 'UNSUPPORTED' })
    } else fields.push({ key, state: 'UNKNOWN' })
  }
  return { source, span, fields, title }
}

export function removeImportFrontmatter(metadata: ImportMetadata, body: string): string {
  const span = metadata.span
  if (!span || metadata.problem || body.slice(0, span.bodyStart) !== metadata.source.slice(0, span.bodyStart)) {
    throw new Error('元数据原文范围已变化，不能移除，请重新预览。')
  }
  // BOM属于文件编码边界；只移除明确确认的元数据块，其后的正文与原换行保持。
  return metadata.source.slice(0, span.headerStart) + body.slice(span.bodyStart)
}
