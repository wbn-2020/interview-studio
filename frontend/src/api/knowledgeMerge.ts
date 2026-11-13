import { preview, read, write } from './client'
import type { ItemDetail, Relation, Schema } from './client'
import type { Organization } from './knowledge'

export type ConceptMergePair = { sourceId: string; targetId: string }
export type ConceptMergeSide = { item: ItemDetail; organization: Organization; position: Schema['Position']; relations: Relation[] }
export type ConceptMergePreview = {
  digest: string; layoutRevision: number; source: ConceptMergeSide; target: ConceptMergeSide
  suggestedAliases: string[]; suggestedTags: string[]; suggestedTopicIds: string[]
  suggestedNoteIds: string[]; impacts: { kind: string; count: number }[]
}
export type ConceptMergeCommit = ConceptMergePair & {
  expectedDigest: string; aliases: string[]; tags: string[]; topicIds: string[]; noteIds: string[]
  mainNoteId: string | null; favorite: boolean; useSourcePosition: boolean; confirmed: boolean
}
export type ConceptMergeRecord = ConceptMergePair & {
  id: string; sourceVersionId: string; targetVersionId: string; targetRevisionAfter: number; createdAt: string
}
export type ConceptMergeResult = { merge: ConceptMergeRecord; target: ItemDetail; organization: Organization }
export type ConceptMergeResolution = { requestedId: string; resolvedId: string; path: ConceptMergeRecord[] }

export const conceptMergePath = '/knowledge/concept-merges'
export const previewConceptMerge = (pair: ConceptMergePair) => preview<ConceptMergePreview>(`${conceptMergePath}/preview`, pair)
export const commitConceptMerge = (body: ConceptMergeCommit, key: string) => write<ConceptMergeResult>(conceptMergePath, 'POST', body, key)
export const readConceptMergeResolution = (id: string) => read<ConceptMergeResolution>(`/knowledge/items/${encodeURIComponent(id)}/merge`)

export function conceptMergeProblem(body: ConceptMergeCommit): string {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
  if (!uuid.test(body.sourceId) || !uuid.test(body.targetId) || body.sourceId === body.targetId) return '请选择两个不同的活跃概念。'
  if (!/^[0-9a-f]{64}$/.test(body.expectedDigest)) return '请先读取有效的合并预览。'
  for (const [label, values, limit] of [['别名', body.aliases, 100], ['标签', body.tags, 40]] as const) {
    if (values.length > 20 || values.some(value => !value.trim() || value.trim().length > limit || /[\u0000-\u001f\u007f]/.test(value))) return `${label}最多20项，每项1至${limit}字符。`
    if (new Set(values.map(value => value.trim().toLowerCase())).size !== values.length) return `${label}不能在忽略大小写后重复。`
  }
  for (const [label, values, limit] of [['主题', body.topicIds, 20], ['关联笔记', body.noteIds, 100]] as const) {
    if (values.length > limit || values.some(value => !uuid.test(value)) || new Set(values).size !== values.length) return `${label}最多${limit}个不同的有效标识。`
  }
  if (body.mainNoteId !== null && !body.noteIds.includes(body.mainNoteId)) return '主笔记必须属于最终关联笔记；也可明确不设置。'
  return ''
}

const impactNames: Record<string, string> = {
  BOARD_VERSIONS: '白板历史引用', MEMORY_SOURCES: '记忆卡片来源', MEMORY_CONCEPTS: '记忆卡片概念',
  QUESTION_VERSIONS: '题目版本引用', PREPARATION_VERSIONS: '准备材料版本引用', BACKLINKS: '正文反向引用',
  OUTGOING_REFERENCES: '正文向外引用', TRAINING_QUESTIONS: '训练题面快照', TRAINING_DRAFTS: '训练草稿引用',
  TRAINING_ANSWERS: '训练回答引用', MEMORY_ATTEMPTS: '记忆作答来源', INTERVIEW_MATERIALS: '面试日程材料',
  AI_DRAFT_INPUTS: 'AI任务固定输入', RELATION_SUPPLEMENT_SOURCES: '关系补充来源',
  RELATIONS_REPLACED: '将迁移的语义关系', RELATIONS_DEDUPLICATED: '将合并的重复语义关系',
  RELATIONS_SELF_REMOVED: '将移除的自指语义关系',
}
export function conceptMergeImpactLabel(kind: string): string {
  const prefix = kind.startsWith('SOURCE_') ? '源概念 · ' : kind.startsWith('TARGET_') ? '目标概念 · ' : ''
  const name = kind.replace(/^(SOURCE|TARGET)_/, '')
  return prefix + (impactNames[name] ?? name)
}
