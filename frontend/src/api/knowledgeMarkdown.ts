import type { Version } from './client'

const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
export function knowledgeMarkdownIdentityValid(itemId: string, versionId: string, versionNumber: number): boolean {
  return typeof itemId === 'string' && typeof versionId === 'string' && uuid.test(itemId) && uuid.test(versionId) && Number.isSafeInteger(versionNumber)
    && versionNumber >= 1 && versionNumber <= 2147483647
}

export function createKnowledgeMarkdown(itemId: string, versionId: string, versionNumber: number, response: unknown) {
  const value = response as Partial<Version> | null
  if (!knowledgeMarkdownIdentityValid(itemId, versionId, versionNumber)
    || !value || typeof value !== 'object' || Array.isArray(value)
    || value.id !== versionId || value.number !== versionNumber
    || typeof value.title !== 'string' || !value.title.trim() || value.title.length > 200
    || typeof value.body !== 'string' || value.body.length > 100000
    || typeof value.createdAt !== 'string'
    || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value.createdAt)
    || !Number.isFinite(Date.parse(value.createdAt))) {
    throw new Error('发布版本的身份、正文或元数据无法核对，请重新读取后再导出。')
  }
  const version = value as Version
  const generatedAt = new Date().toISOString()
  const metadata = {
    export_format: 'wenjie.knowledge-markdown/v1',
    export_scope: 'selected_published_version',
    source_item_id: itemId,
    source_version_id: version.id,
    version_number: version.number,
    title: version.title,
    version_created_at: version.createdAt,
    exported_at_client: generatedAt,
  }
  // YAML 双引号字符串采用 JSON 转义，并编码额外换行符及禁用控制字符，防止标题生成额外属性。
  const scalar = (value: string | number) => JSON.stringify(value).replace(/[\u007f-\u009f\u2028\u2029\ufffe\uffff]/g,
    character => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`)
  const header = Object.entries(metadata).map(([key, value]) => `${key}: ${scalar(value)}`).join('\n')
  // 原正文不修剪、不插入标题、不转换内部链接；关闭元数据块后的内容即为完整发布原文。
  return { itemId, version, generatedAt, filename: `wenjie-${itemId}-v${version.number}.md`,
    text: `---\n${header}\n---\n${version.body}` }
}
