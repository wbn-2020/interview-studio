export type KnowledgeSource = {
  title: string; url: string; author: string; acquiredAt: string | null
  applicableVersion: string; rights: string; note: string
}
export type KnowledgeSourceDocument = {
  itemId: string; versionId: string; revision: number; sources: KnowledgeSource[]; savedAt: string | null
}
export type KnowledgeSourceSave = { expectedRevision: number; sources: KnowledgeSource[] }

const sourceFields = ['title', 'url', 'author', 'acquiredAt', 'applicableVersion', 'rights', 'note'] as const
const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
export const knowledgeSourceIdValid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const revisionValid = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const textValid = (value: unknown, limit: number, required = false): value is string =>
  typeof value === 'string' && value.length <= limit && !/[\u0000\uD800-\uDFFF]/u.test(value) && (!required || javaStrip(value).length > 0)
// 与 Java String.strip 使用相同空白集合，核对回执时不能把服务端规范化误判为另一份来源。
const javaStrip = (value: string) => value.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g, '')
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(value)) return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19)
}
const micros = (value: string) => `${value.slice(0, 19)}.${value.slice(19, -1).replace('.', '').padEnd(6, '0')}Z`
function sourceValid(value: unknown): value is KnowledgeSource {
  const source = value as KnowledgeSource
  return fields(source, sourceFields) && textValid(source.title, 200, true) && textValid(source.url, 2000)
    && textValid(source.author, 200) && (source.acquiredAt === null || timestamp(source.acquiredAt))
    && textValid(source.applicableVersion, 200) && textValid(source.rights, 2000) && textValid(source.note, 4000)
}
export const knowledgeSourcesPath = (itemId: string, versionId: string) =>
  `/knowledge/items/${encodeURIComponent(itemId)}/versions/${encodeURIComponent(versionId)}/sources`
export function normalizedKnowledgeSources(sources: KnowledgeSource[]): KnowledgeSource[] {
  return sources.map(source => ({ title: javaStrip(source.title), url: javaStrip(source.url), author: javaStrip(source.author),
    acquiredAt: source.acquiredAt, applicableVersion: javaStrip(source.applicableVersion),
    rights: javaStrip(source.rights), note: javaStrip(source.note) }))
}
const sourceKeys = (sources: KnowledgeSource[]) => JSON.stringify(normalizedKnowledgeSources(sources).map(source =>
  [source.title, source.url, source.author, source.acquiredAt === null ? null : micros(source.acquiredAt),
    source.applicableVersion, source.rights, source.note]))
export function knowledgeSourceDocumentValid(value: unknown, itemId: string, versionId: string,
  expectedRevision?: number): value is KnowledgeSourceDocument {
  const record = value as KnowledgeSourceDocument
  return knowledgeSourceIdValid(itemId) && knowledgeSourceIdValid(versionId)
    && fields(record, ['itemId', 'versionId', 'revision', 'sources', 'savedAt'])
    && record.itemId === itemId && record.versionId === versionId && revisionValid(record.revision)
    && (expectedRevision === undefined || record.revision === expectedRevision)
    && Array.isArray(record.sources) && record.sources.length <= 20 && record.sources.every(sourceValid)
    && (record.revision === 0 ? record.savedAt === null && record.sources.length === 0 : timestamp(record.savedAt))
}
export function knowledgeSourceSameDocument(left: KnowledgeSourceDocument, right: KnowledgeSourceDocument): boolean {
  return knowledgeSourceDocumentValid(left, right.itemId, right.versionId, right.revision)
    && sourceKeys(left.sources) === sourceKeys(right.sources)
    && (left.savedAt === null ? right.savedAt === null : right.savedAt !== null && micros(left.savedAt) === micros(right.savedAt))
}
export function knowledgeSourceReceiptMatches(value: unknown, itemId: string, versionId: string,
  request: KnowledgeSourceSave): value is KnowledgeSourceDocument {
  return knowledgeSourceDocumentValid(value, itemId, versionId, request.expectedRevision + 1)
    && sourceKeys(value.sources) === sourceKeys(request.sources)
}
export function knowledgeSourceSaveProblem(request: KnowledgeSourceSave): string {
  if (!revisionValid(request.expectedRevision) || request.expectedRevision === Number.MAX_SAFE_INTEGER) return '当前来源修订无法继续保存，请重新读取。'
  if (!Array.isArray(request.sources) || request.sources.length > 20 || !request.sources.every(sourceValid)) return '请填写来源标题并检查字段长度和获取时间，最多记录20项来源。'
  for (const source of request.sources) {
    if (source.acquiredAt && new Date(source.acquiredAt).getTime() > Date.now() + 300000) return '获取时间不能晚于当前时间五分钟。'
    const url = javaStrip(source.url)
    if (!url) continue
    try {
      const parsed = new URL(url)
      if (!/^https?:\/\//.test(url) || !parsed.hostname || parsed.username || parsed.password) return '来源地址只支持不含账号信息的 HTTP／HTTPS 链接。'
    } catch { return '请检查来源地址，或暂时留空。' }
  }
  return ''
}

export function emptyKnowledgeSource(): KnowledgeSource {
  return { title: '', url: '', author: '', acquiredAt: null, applicableVersion: '', rights: '', note: '' }
}
