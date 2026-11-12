import type { Schema } from './client'

export type MemoryKind = Schema['MemoryKind']
export type MemoryStatus = Schema['MemoryStatus']
export type MemorySourceState = Schema['MemorySourceState']
export type MemorySourceLink = Schema['MemorySourceLink']
export type MemoryContent = Schema['MemoryContent']
export type MemoryPrompt = Schema['MemoryPrompt']
export type MemoryVersion = Schema['MemoryVersion']
export type MemorySourceReference = Schema['MemorySourceReference']
export type MemoryRelatedConcept = Schema['MemoryRelatedConcept']
export type MemoryCard = Schema['MemoryCard']
export type MemoryVersionDetail = Schema['MemoryVersionDetail']
export type MemorySummary = Schema['MemorySummary']
export type MemoryPage = Schema['MemoryPage']
export type MemoryVersionSummary = Schema['MemoryVersionSummary']
export type MemoryVersionPage = Schema['MemoryVersionPage']
export type MemoryReference = Schema['MemoryReference']

export const memoryKindLabels: Record<MemoryKind, string> = { QA: '问答卡', CLOZE: '手工填空卡' }
export const memoryStatusLabels: Record<MemoryStatus, string> = { ACTIVE: '使用中', ARCHIVED: '已归档' }
export const memorySourceStateLabels: Record<MemorySourceState, string> = { CURRENT: '当前发布版本', SUPERSEDED: '来源已有新版本', ARCHIVED: '来源已归档' }
export const memoryIdValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown, minimum: number, maximum: number): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const timestamp = (value: unknown): value is string => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const text = (value: unknown, maximum: number): value is string => typeof value === 'string' && /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value)
  && Array.from(value).length <= maximum && !/[\u0000\uD800-\uDFFF]/u.test(value)
const knowledgeTitle = (value: unknown): value is string => typeof value === 'string' && value.length >= 1 && value.length <= 200
const bytes = (value: unknown) => new TextEncoder().encode(JSON.stringify(value)).length
const promptFields = ['kind', 'title', 'prompt', 'sources', 'relatedConceptIds']

export function memoryEmptyContent(): MemoryContent {
  return { kind: 'QA', title: '', prompt: '', reference: '', sources: [], relatedConceptIds: [] }
}
export function memoryContentProblem(content: MemoryContent): string {
  try {
    if (!fields(content, [...promptFields, 'reference'])) return '回忆卡字段不完整或含未定义字段，请重新核对。'
    if (!Object.hasOwn(memoryKindLabels, content.kind)) return '请选择问答卡或手工填空卡。'
    if (!text(content.title, 200)) return '标题需为 1–200 个完整字符，不能全为空白或包含 NUL。'
    if (!text(content.prompt, 10000)) return '请填写 1–10000 个完整字符的题面。'
    if (!text(content.reference, 20000)) return '请填写 1–20000 个完整字符的个人参考。'
    if (!sourcesValid(content.sources)) return '请选择 1–10 个确切知识版本作为来源，同一版本不能重复。'
    if (!conceptIdsValid(content.relatedConceptIds)) return '最多关联 20 个不同的概念，请核对概念标识。'
    return bytes(content) > 196608 ? '完整卡片内容编码后超过 192 KiB，请缩减后保存；内容不会截断。' : ''
  } catch { return '回忆卡字段无法读取，请重新核对完整内容。' }
}
export function memoryPromptValid(value: MemoryPrompt): boolean {
  try { return fields(value, promptFields) && Object.hasOwn(memoryKindLabels, value.kind) && text(value.title, 200) && text(value.prompt, 10000)
    && sourcesValid(value.sources) && conceptIdsValid(value.relatedConceptIds) && bytes(value) <= 196608 } catch { return false }
}
export function memoryCardValid(value: MemoryCard, id?: string): boolean {
  try { return fields(value, ['id', 'status', 'revision', 'currentVersion', 'sourceReferences', 'relatedConcepts', 'createdAt', 'updatedAt'])
    && memoryIdValid(value.id) && (id === undefined || value.id === id) && Object.hasOwn(memoryStatusLabels, value.status)
    && integer(value.revision, 0, 1000) && versionValid(value.currentVersion) && value.currentVersion.number <= value.revision + 1
    && projectionsValid(value.currentVersion.content, value.sourceReferences, value.relatedConcepts) && timestamp(value.createdAt) && timestamp(value.updatedAt) } catch { return false }
}
export function memoryVersionDetailValid(value: MemoryVersionDetail, versionId?: string): boolean {
  try { return fields(value, ['version', 'sourceReferences', 'relatedConcepts']) && versionValid(value.version)
    && (versionId === undefined || value.version.id === versionId) && projectionsValid(value.version.content, value.sourceReferences, value.relatedConcepts) } catch { return false }
}
export function memoryReferenceValid(value: MemoryReference, cardId: string, versionId: string, versionNumber: number): boolean {
  try { return fields(value, ['cardId', 'versionId', 'versionNumber', 'reference']) && memoryIdValid(value.cardId) && value.cardId === cardId
    && memoryIdValid(value.versionId) && value.versionId === versionId && integer(value.versionNumber, 1, 1000) && value.versionNumber === versionNumber
    && text(value.reference, 20000) && bytes(value) <= 196608 } catch { return false }
}
export function memoryPromptSignature(prompt: MemoryPrompt): string {
  return JSON.stringify([prompt.kind, prompt.title, prompt.prompt, prompt.sources.map(source => [source.itemId, source.versionId]), prompt.relatedConceptIds])
}
export function memoryContentSignature(content: MemoryContent): string {
  return JSON.stringify([memoryPromptSignature(content), content.reference])
}
function sourcesValid(values: MemorySourceLink[]): boolean {
  return Array.isArray(values) && values.length >= 1 && values.length <= 10
    && values.every(value => fields(value, ['itemId', 'versionId']) && memoryIdValid(value.itemId) && memoryIdValid(value.versionId))
    && new Set(values.map(value => `${value.itemId}/${value.versionId}`)).size === values.length
}
function conceptIdsValid(values: string[]): boolean { return Array.isArray(values) && values.length <= 20 && values.every(memoryIdValid) && new Set(values).size === values.length }
function versionValid(value: MemoryVersion): boolean {
  return fields(value, ['id', 'number', 'content', 'createdAt']) && memoryIdValid(value.id) && integer(value.number, 1, 1000)
    && memoryPromptValid(value.content) && timestamp(value.createdAt)
}
function projectionsValid(prompt: MemoryPrompt, references: MemorySourceReference[], concepts: MemoryRelatedConcept[]): boolean {
  // 动态投影只补充状态与标签，身份和顺序必须与该不可变卡版本完全对应。
  return Array.isArray(references) && references.length === prompt.sources.length && references.every((reference, index) => {
    const source = prompt.sources[index]!
    return fields(reference, ['itemId', 'versionId', 'kind', 'title', 'number', 'state', 'bodyChanged']) && reference.itemId === source.itemId
      && reference.versionId === source.versionId && ['NOTE', 'CONCEPT'].includes(reference.kind) && knowledgeTitle(reference.title)
      && integer(reference.number, 1, 2147483647) && Object.hasOwn(memorySourceStateLabels, reference.state) && typeof reference.bodyChanged === 'boolean'
      && (reference.state !== 'CURRENT' || !reference.bodyChanged)
  }) && Array.isArray(concepts) && concepts.length === prompt.relatedConceptIds.length && concepts.every((concept, index) =>
    fields(concept, ['itemId', 'title', 'status']) && concept.itemId === prompt.relatedConceptIds[index] && knowledgeTitle(concept.title) && ['ACTIVE', 'ARCHIVED'].includes(concept.status))
}
