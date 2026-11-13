import { commonmarkLanguage } from '@codemirror/lang-markdown'
import { inspectImportLinks } from './knowledgeTextLinks'
import type { ImportLinkPlan } from './knowledgeTextLinks'
import { inspectImportMetadata, removeImportFrontmatter } from './knowledgeTextMetadata'
import { suggestImportLinks } from './knowledgeTextCandidates'
import { isTextImportPath } from './knowledgeTextPath'

export type PackageKind = 'NOTE' | 'CONCEPT'
export type PackageDecision = 'IMPORT' | 'SKIP'
export type PackageSource = {
  sourceId: string; source: string; title: string; body: string; kind: PackageKind
  decision: PackageDecision; tags: string[]; aliases: string[]; topicIds: string[]
}
export type PackageAttachment = { sourceId: string; attachmentId: string; sourceSha256: string }
export type PackagePlan = { items: PackageSource[]; sourceAttachments: PackageAttachment[] }
export type PackageIssue = { sourceId: string; code: string; message: string }
export type PackagePreview = {
  packageKey: string; planSha256: string; existingPackageId: string | null
  importCount: number; skipCount: number; issues: PackageIssue[]
}
export type PackageMapping = {
  sourceId: string; sourceSha256: string; sourceBytes: number; sourceCharacters: number
  kind: PackageKind; decision: PackageDecision; title: string; tags: string[]; aliases: string[]
  topicIds: string[]; itemId: string | null; versionId: string | null
}
export type PackageDetail = {
  id: string; revision: number; state: string; packageKey: string; planSha256: string
  createCopy: boolean; createdAt: string; committedAt: string | null
  mappings: PackageMapping[]; sourceAttachments: PackageAttachment[]
}
export type PackageSummary = {
  id: string; revision: number; state: string; packageKey: string; createCopy: boolean
  importCount: number; skipCount: number; createdAt: string; committedAt: string | null
}
export type PackagePage = { items: PackageSummary[]; nextCursor: string | null }
export type PackageLocalFile = {
  sourceId: string; fileName: string; source: string; sourceSha256: string; sourceBytes: number
}
export type PackageLinkTarget = { key: string; target: string; kind: string; reason: string; candidates: string[] }
export type PackageLinkChoice = { target: string; disposition: 'MAP' | 'PRESERVE' }
export type PackageEdits = {
  title: string; kind: PackageKind; decision: PackageDecision; tagsText: string; aliasesText: string
  topicIds: string[]; adoptTitle: boolean; adoptTags: boolean; adoptAliases: boolean
  removeMetadata: boolean; body: string; links: Record<string, PackageLinkChoice>; attachmentLinks: Record<string, PackageLinkChoice>
}

const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
const hash = /^[0-9a-f]{64}$/
const controls = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/
const opaque = new Set(['FencedCode', 'CodeBlock', 'InlineCode', 'HTMLBlock', 'HTMLTag',
  'CommentBlock', 'Comment', 'ProcessingInstructionBlock', 'ProcessingInstruction'])
function strings(value: unknown): value is string[] {
  return Array.isArray(value) && value.length <= 20 && value.every(item => typeof item === 'string')
}

export function packagePath(value: string): boolean {
  return isTextImportPath(value, value.slice(value.lastIndexOf('/') + 1))
}
export async function preparePackageFile(fileName: string, sourceId: string, buffer: ArrayBuffer,
  stillCurrent: () => boolean): Promise<PackageLocalFile> {
  if (!stillCurrent()) throw new Error('文件读取条件已变化，请重新选择。')
  if (!packagePath(sourceId) || sourceId.length > 600 || fileName.length > 200
    || !/\.(md|markdown|txt)$/i.test(fileName) || buffer.byteLength < 1 || buffer.byteLength > 400000) {
    throw new Error(`「${sourceId}」不符合文本文件路径或大小限制。`)
  }
  let source: string
  try { source = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(buffer) }
  catch { throw new Error(`「${sourceId}」不是有效UTF-8，整包未保留。`) }
  if (source.length > 100000 || controls.test(source)) throw new Error(`「${sourceId}」超出100000字符或含不支持的控制字符。`)
  const sourceSha256 = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', buffer)),
    byte => byte.toString(16).padStart(2, '0')).join('')
  if (!stillCurrent()) throw new Error('文件读取条件已变化，请重新选择。')
  return { sourceId, fileName, source, sourceSha256, sourceBytes: buffer.byteLength }
}
export function defaultPackageEdits(file: PackageLocalFile, saved?: PackageMapping): PackageEdits {
  return {
    title: saved?.title ?? file.fileName.replace(/\.(md|markdown|txt)$/i, ''), kind: saved?.kind ?? 'NOTE',
    decision: saved?.decision ?? 'IMPORT', tagsText: saved?.tags.join('\n') ?? '',
    aliasesText: saved?.aliases.join('\n') ?? '', topicIds: [...saved?.topicIds ?? []],
    adoptTitle: false, adoptTags: false, adoptAliases: false, removeMetadata: false, body: file.source, links: {}, attachmentLinks: {},
  }
}
function labels(raw: string, limit: number, name: string): string[] {
  if (!raw) return []
  const values = raw.split(/\r?\n/)
  if (values.length > 20 || values.some(value => !value.trim() || value.length > limit
    || /[\u0000-\u001f\u007f]/.test(value))) throw new Error(`${name}每行一项，最多20项，每项1至${limit}字符，不接受空行或控制字符。`)
  return values
}
function metadataLabels(file: PackageLocalFile, names: string[], limit: number, label: string): string[] {
  const metadata = inspectImportMetadata(file.source)
  const selected = metadata.fields.filter(field => names.includes(field.key))
  if (metadata.problem || !selected.length || selected.some(field => !field.values)) throw new Error(`「${file.sourceId}」没有可完整采用的${label}属性。`)
  const values = selected.flatMap(field => field.values ?? [])
  if (!values.length || values.length > 20 || values.some(value => value.length > limit || !value.trim())) {
    throw new Error(`「${file.sourceId}」的${label}超出20项或单项${limit}字符，不提供部分采用。`)
  }
  return values
}
export function packageSource(file: PackageLocalFile, edits: PackageEdits): PackageSource {
  const metadata = inspectImportMetadata(file.source)
  const title = edits.adoptTitle ? metadata.title : edits.title
  if (!title?.trim() || title.length > 200 || /[\u0000-\u001f\u007f]/.test(title)) throw new Error(`「${file.sourceId}」的标题须为1至200字符。`)
  if (edits.kind === 'NOTE' && (edits.adoptAliases || edits.aliasesText)) throw new Error(`「${file.sourceId}」是笔记，不能采用概念别名。`)
  let body = edits.removeMetadata ? removeImportFrontmatter(metadata, edits.body) : edits.body
  const attachmentLinks = inspectPackageAttachmentLinks(body)
  requireDecisions(attachmentLinks, edits.attachmentLinks, edits.decision)
  body = rewritePackageLinks(attachmentLinks, edits.attachmentLinks, 'package-attachment:')
  const links = inspectImportLinks(body)
  requireDecisions(links, edits.links, edits.decision)
  body = rewritePackageLinks(links, edits.links)
  if (body.length > 100000 || controls.test(body)) throw new Error(`「${file.sourceId}」的处理后正文超出限制，不截断。`)
  return {
    sourceId: file.sourceId, source: file.source, title, body, kind: edits.kind, decision: edits.decision,
    tags: edits.adoptTags ? metadataLabels(file, ['tags', 'tag'], 40, '标签') : labels(edits.tagsText, 40, '标签'),
    aliases: edits.adoptAliases ? metadataLabels(file, ['aliases', 'alias'], 100, '别名') : labels(edits.aliasesText, 100, '别名'),
    topicIds: [...edits.topicIds],
  }
}
export function packageLinks(body: string, sourceId: string, files: readonly PackageLocalFile[]): {
  plan: ImportLinkPlan; targets: PackageLinkTarget[]
} {
  const plan = inspectImportLinks(body)
  const candidates = suggestImportLinks(plan, sourceId, files.map(file => ({
    id: file.sourceId, sourcePath: file.sourceId, fileName: file.fileName, state: 'PENDING',
  })))
  return { plan, targets: plan.links.map(link => ({
    key: link.key, target: link.target, kind: link.kind, reason: (candidates[link.key]?.reason ?? '')
      .replace(/ 唯一匹配.*$/, ' 找到唯一字面路径候选；请人工核对后选择。本包统一分配身份，可包含循环引用。'),
    candidates: candidates[link.key]?.matches.map(file => file.sourcePath) ?? [],
  })) }
}
function requireDecisions(plan: ImportLinkPlan, choices: Record<string, PackageLinkChoice>, decision: PackageDecision) {
  if (decision === 'SKIP') return
  if (plan.problem) throw new Error(plan.problem)
  if (plan.links.some(link => !choices[link.key])) throw new Error('每个识别出的知识或附件地址都须明确映射，或明确确认保留原文。')
}
function rewritePackageLinks(plan: ImportLinkPlan, choices: Record<string, PackageLinkChoice>, scheme = 'package:'): string {
  if (!Object.keys(choices).length) return plan.source
  if (plan.problem || Object.keys(choices).some(key => !plan.links.some(link => link.key === key))) {
    throw new Error('正文已变化，原链接选择失效，请重新核对。')
  }
  const edits: { from: number; to: number; original: string; replacement: string }[] = []
  for (const link of plan.links) {
    const choice = choices[link.key]
    if (!choice || choice.disposition === 'PRESERVE') continue
    if (!packagePath(choice.target)) throw new Error('目标包内路径无效，请重新选择。')
    for (const span of link.occurrences) {
      const path = choice.target.split('/').map(segment => encodeURIComponent(segment)
        .replace(/[!'()*]/g, char => `%${char.charCodeAt(0).toString(16).toUpperCase()}`)).join('/')
      const destination = `<${scheme}${path}>`
      edits.push({ ...span, replacement: span.label === undefined ? destination
        : `${scheme === 'package-attachment:' && span.original.startsWith('![[') ? '!' : ''}[${span.label.replace(/\\/g, '\\\\').replace(/([\[\]`*_{}()<>!#|~&])/g, '\\$1')}](${destination})` })
    }
  }
  let offset = 0, result = ''
  for (const edit of edits.sort((left, right) => left.from - right.from)) {
    if (edit.from < offset || plan.source.slice(edit.from, edit.to) !== edit.original) throw new Error('链接原文坐标不一致，请重新核对。')
    result += plan.source.slice(offset, edit.from) + edit.replacement
    offset = edit.to
  }
  return result + plan.source.slice(offset)
}
export function inspectPackageAttachmentLinks(body: string): ImportLinkPlan {
  const metadata = inspectImportMetadata(body)
  const skip = metadata.span?.bodyStart ?? 0
  const links = new Map<string, ImportLinkPlan['links'][number]>()
  const blocked: { from: number; to: number }[] = skip ? [{ from: 0, to: skip }] : []
  let count = 0
  commonmarkLanguage.parser.parse(body).iterate({ enter(node) {
    if (opaque.has(node.name)) { blocked.push({ from: node.from, to: node.to }); return false }
    if (node.to <= skip) return false
    if (node.name !== 'Image' && node.name !== 'Link') return
    blocked.push({ from: node.from, to: node.to })
    const url = node.node.getChild('URL')
    if (!url) return
    const raw = body.slice(url.from, url.to), target = raw.startsWith('<') && raw.endsWith('>') ? raw.slice(1, -1) : raw
    if (!target || /[:#?]/.test(target) || node.name === 'Link' && /\.(md|markdown|txt)$/i.test(target)) return
    const key = `ATTACHMENT:${target}`
    if (!links.has(key)) links.set(key, { key, kind: 'RELATIVE', target, occurrences: [] })
    links.get(key)!.occurrences.push({ from: url.from, to: url.to, original: raw })
    count++
  } })
  // Wiki嵌入只在代码、HTML和真实Markdown链接之外解释，不把示例字符串转换成附件。
  for (const match of body.matchAll(/!\[\[([^\[\]\r\n|]{1,500})(?:\|([^\[\]\r\n|]{1,500}))?\]\]/g)) {
    const from = match.index, to = from + match[0].length, target = match[1]!
    if (blocked.some(span => span.from < to && span.to > from) || /[:#^\\]/.test(target)
      || body[from - 1] === '[' || body[to] === ']' || !target.trim()) continue
    let escapes = 0
    for (let index = from - 1; index >= 0 && body[index] === '\\'; index--) escapes++
    if (escapes % 2) continue
    const key = `ATTACHMENT:${target}`
    if (!links.has(key)) links.set(key, { key, kind: 'RELATIVE', target, occurrences: [] })
    links.get(key)!.occurrences.push({ from, to, original: match[0], label: match[2] ?? target })
    count++
  }
  if (count > 200) return { source: body, links: [], problem: '附件候选超过200处，不提供部分映射。' }
  return { source: body, links: [...links.values()] }
}
export function packageRequestSize(body: unknown): number {
  const bytes = new Blob([JSON.stringify(body)]).size
  if (bytes > 1_000_000) throw new Error('整包请求超过1000000字节安全上限，请减少文件或正文；不会分块绕过服务器限制。')
  return bytes
}
export function checkedPackagePreview(value: unknown): PackagePreview {
  const result = value as PackagePreview
  if (!result || !hash.test(result.packageKey) || !hash.test(result.planSha256)
    || result.existingPackageId !== null && !uuid.test(result.existingPackageId)
    || !Number.isInteger(result.importCount) || result.importCount < 0
    || !Number.isInteger(result.skipCount) || result.skipCount < 0 || !Array.isArray(result.issues)
    || result.issues.some(issue => typeof issue.sourceId !== 'string' || typeof issue.code !== 'string' || typeof issue.message !== 'string')) {
    throw new Error('服务器知识包预览回执无法核对，请重新预览。')
  }
  return result
}
export function checkedPackageDetail(value: unknown): PackageDetail {
  const result = value as PackageDetail
  if (!result || !uuid.test(result.id) || !Number.isSafeInteger(result.revision) || result.revision < 0
    || !hash.test(result.packageKey) || !hash.test(result.planSha256) || !['REGISTERED', 'COMMITTED'].includes(result.state)
    || typeof result.createCopy !== 'boolean' || typeof result.createdAt !== 'string'
    || result.state === 'COMMITTED' && typeof result.committedAt !== 'string'
    || result.state === 'REGISTERED' && result.committedAt !== null
    || !Array.isArray(result.mappings) || !result.mappings.length || result.mappings.length > 20 || !Array.isArray(result.sourceAttachments)
    || new Set(result.mappings.map(item => item.sourceId)).size !== result.mappings.length
    || result.mappings.some(item => !packagePath(item.sourceId) || !hash.test(item.sourceSha256)
      || !['NOTE', 'CONCEPT'].includes(item.kind) || !['IMPORT', 'SKIP'].includes(item.decision)
      || typeof item.title !== 'string' || !strings(item.tags) || !strings(item.aliases) || !strings(item.topicIds)
      || item.topicIds.some(id => !uuid.test(id)) || item.kind === 'NOTE' && item.aliases.length > 0
      || !Number.isSafeInteger(item.sourceBytes) || item.sourceBytes < 1 || item.sourceBytes > 400000
      || !Number.isSafeInteger(item.sourceCharacters) || item.sourceCharacters < 1 || item.sourceCharacters > 100000
      || item.decision === 'IMPORT' && (!uuid.test(item.itemId ?? '') || !uuid.test(item.versionId ?? ''))
      || item.decision === 'SKIP' && (item.itemId !== null || item.versionId !== null)
      || item.itemId !== null && !uuid.test(item.itemId) || item.versionId !== null && !uuid.test(item.versionId))
    || result.sourceAttachments.length > 20 || result.sourceAttachments.some(item => !packagePath(item.sourceId)
      || !uuid.test(item.attachmentId) || !hash.test(item.sourceSha256))
    || new Set([...result.mappings.map(item => item.sourceId), ...result.sourceAttachments.map(item => item.sourceId)]).size
      !== result.mappings.length + result.sourceAttachments.length) {
    throw new Error('服务器知识包身份或映射无法核对，请重新读取。')
  }
  return result
}
