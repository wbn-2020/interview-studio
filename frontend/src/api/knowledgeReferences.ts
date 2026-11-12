import type { RouteLocationRaw } from 'vue-router'
import type { Schema } from './client'
import { aiStatusLabels, aiTaskStatusLabel } from './ai'
import type { AiPurpose, AiStatus } from './ai'
import { recordStatusLabels } from './standardReviews'
import { scheduleStatusLabels } from './schedule'
import { trainingQuestionStatusLabels } from './training'
import { memoryAttemptStateLabels } from './memoryAttempt'

export type KnowledgeReferenceKind = Schema['KnowledgeReferenceKind']
export type KnowledgeReferenceEntry = Schema['KnowledgeReferenceEntry']
export type KnowledgeReferencePage = Schema['KnowledgeReferencePage']

export const knowledgeReferenceLabels: Record<KnowledgeReferenceKind, string> = {
  RELATIONS: '当前正式关系',
  CONCEPT_NOTES: '概念与笔记关联',
  MAIN_NOTES: '主笔记关联',
  BODY_LINKS: '已登记正文链接',
  BOARD_SNAPSHOTS: '白板保存时引用',
  PREPARATION_VERSIONS: '准备版本引用',
  QUESTION_VERSIONS: '题目版本引用',
  MEMORY_VERSIONS: '回忆卡版本依据',
  MEMORY_CONCEPTS: '回忆卡相关概念',
  RELATION_SUPPLEMENTS: '关系补充说明来源',
  AI_KNOWLEDGE_ADOPTIONS: 'AI 知识整理采用来源',
  AI_QUESTION_ADOPTIONS: 'AI 出题采用来源',
  AI_RELATION_INPUTS: 'AI 关系建议任务输入',
  AI_CARD_INPUTS: 'AI 制卡任务输入',
  AI_SOURCE_QA_INPUTS: 'AI 来源问答任务输入',
  STANDARD_REVIEW_EVIDENCE: '标准审阅知识证据',
  INTERVIEW_MATERIALS: '面试安排材料',
  TODAY_PLACEMENTS: '知识安排偏好',
  AI_KNOWLEDGE_INPUTS: 'AI 知识整理任务输入',
  AI_QUESTION_INPUTS: 'AI 出题任务输入',
  TRAINING_QUESTION_SNAPSHOTS: '训练题面知识快照',
  TRAINING_DRAFT_DIAGRAMS: '当前训练图解草稿',
  TRAINING_ANSWER_DIAGRAMS: '正式提交图解',
  MEMORY_ATTEMPT_SOURCES: '回忆尝试开始来源',
  TODAY_PLACEMENT_HISTORY: '知识安排偏好历史',
  MEMORY_REVIEW_DEPENDENCIES: '复核实际依赖',
  MEMORY_REVIEW_OBSERVATIONS: '复核当时观察的当前指针',
  MEMORY_IMPACT_DEPENDENCIES: '影响时固定依赖',
  MEMORY_IMPACT_PREVIOUS: '变更前观察',
  MEMORY_IMPACT_CURRENT: '变更后观察',
}
const aiReferencePurposes = {
  AI_KNOWLEDGE_ADOPTIONS: 'KNOWLEDGE_DRAFT',
  AI_QUESTION_ADOPTIONS: 'QUESTION_DRAFT',
  AI_RELATION_INPUTS: 'RELATION_DRAFT',
  AI_CARD_INPUTS: 'MEMORY_CARD_DRAFT',
  AI_SOURCE_QA_INPUTS: 'SOURCE_QA',
  AI_KNOWLEDGE_INPUTS: 'KNOWLEDGE_DRAFT',
  AI_QUESTION_INPUTS: 'QUESTION_DRAFT',
} satisfies Partial<Record<KnowledgeReferenceKind, AiPurpose>>
export function referenceAiPurpose(kind: KnowledgeReferenceKind | undefined): AiPurpose | undefined {
  return kind && Object.hasOwn(aiReferencePurposes, kind) ? aiReferencePurposes[kind as keyof typeof aiReferencePurposes] : undefined
}
const memoryHistoryStatuses = {
  MEMORY_REVIEW_DEPENDENCIES: 'SOURCE_REVIEW',
  MEMORY_REVIEW_OBSERVATIONS: 'SOURCE_REVIEW',
  MEMORY_IMPACT_DEPENDENCIES: 'IMPACT',
  MEMORY_IMPACT_PREVIOUS: 'IMPACT',
  MEMORY_IMPACT_CURRENT: 'IMPACT',
} satisfies Partial<Record<KnowledgeReferenceKind, 'SOURCE_REVIEW' | 'IMPACT'>>
export function referenceMemoryHistoryStatus(kind: KnowledgeReferenceKind | undefined): 'SOURCE_REVIEW' | 'IMPACT' | undefined {
  return kind && Object.hasOwn(memoryHistoryStatuses, kind) ? memoryHistoryStatuses[kind as keyof typeof memoryHistoryStatuses] : undefined
}
export const referenceAiAdoption = (kind: KnowledgeReferenceKind | undefined) => kind === 'AI_KNOWLEDGE_ADOPTIONS' || kind === 'AI_QUESTION_ADOPTIONS'
export const knowledgeReferenceOptions = Object.entries(knowledgeReferenceLabels).map(([value, label]) => ({ value: value as KnowledgeReferenceKind, label }))
export const referenceIdValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
export const referenceKindValid = (value: unknown): value is KnowledgeReferenceKind => typeof value === 'string' && Object.hasOwn(knowledgeReferenceLabels, value)
export const referenceVersionFiltered = (kind: KnowledgeReferenceKind) => Boolean(referenceAiPurpose(kind) || referenceMemoryHistoryStatus(kind))
  || ['BOARD_SNAPSHOTS', 'PREPARATION_VERSIONS', 'QUESTION_VERSIONS', 'MEMORY_VERSIONS', 'RELATION_SUPPLEMENTS', 'STANDARD_REVIEW_EVIDENCE', 'INTERVIEW_MATERIALS',
    'TRAINING_QUESTION_SNAPSHOTS', 'TRAINING_DRAFT_DIAGRAMS', 'TRAINING_ANSWER_DIAGRAMS', 'MEMORY_ATTEMPT_SOURCES'].includes(kind)
const fields = (value: unknown, names: readonly string[]) => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const positive = (value: unknown): value is number => integer(value) && value > 0
// 已保存标题只作纯文本；历史 NUL、换行和表情不重新套用新建输入的规则。
const title = (value: unknown): value is string => typeof value === 'string' && Array.from(value).length <= 200 && !/[\uD800-\uDFFF]/u.test(value)
const relationLabels: Record<string, string> = { PRECEDES: '前置于', PART_OF: '组成部分', APPLIED_IN: '应用于', RELATED: '相关', CONTRASTS: '对比' }

type ReferencePosition = { referenceId: string; referenceRevision: number }
export function knowledgeReferenceCursor(value: unknown, itemId: string, versionId: string, kind: KnowledgeReferenceKind): ReferencePosition | undefined {
  if (typeof value !== 'string' || value.length > 200) return undefined
  const parts = value.split('~')
  if (parts.length !== 5 || parts[0] !== itemId || parts[1] !== versionId || parts[2] !== kind || !referenceIdValid(parts[3])
    || !/^(?:0|[1-9]\d{0,18})$/.test(parts[4] ?? '')) return undefined
  const revision = Number(parts[4])
  if (!integer(revision) || !['BOARD_SNAPSHOTS', 'STANDARD_REVIEW_EVIDENCE', 'INTERVIEW_MATERIALS', 'TODAY_PLACEMENT_HISTORY'].includes(kind) && revision !== 0) return undefined
  return { referenceId: parts[3], referenceRevision: revision }
}
function compare(left: ReferencePosition, right: ReferencePosition): number {
  return left.referenceId < right.referenceId ? -1 : left.referenceId > right.referenceId ? 1 : left.referenceRevision - right.referenceRevision
}
function entryValid(value: unknown, kind: KnowledgeReferenceKind, itemId: string): value is KnowledgeReferenceEntry {
  const entry = value as KnowledgeReferenceEntry
  if (!fields(entry, ['referenceId', 'referenceRevision', 'targetKind', 'targetId', 'targetVersionId', 'targetVersionNumber',
    'targetRevision', 'title', 'status', 'targetCurrent', 'anchorItemId', 'otherItemId', 'anchorVersionId'])
    || !referenceIdValid(entry.referenceId) || !integer(entry.referenceRevision) || !referenceIdValid(entry.targetId)
    || !title(entry.title) || typeof entry.status !== 'string' || typeof entry.targetCurrent !== 'boolean') return false
  // 审阅的所属题目版本只作导航锚点，不能混作审阅自身的版本。
  if (kind === 'STANDARD_REVIEW_EVIDENCE') return entry.targetKind === 'STANDARD_REVIEW' && entry.referenceId === entry.targetId
    && entry.targetRevision === entry.referenceRevision && entry.targetVersionId === null && entry.targetVersionNumber === null
    && Object.hasOwn(recordStatusLabels, entry.status) && referenceIdValid(entry.anchorItemId)
    && referenceIdValid(entry.anchorVersionId) && entry.otherItemId === null
  if (kind === 'MEMORY_ATTEMPT_SOURCES') return entry.targetKind === 'MEMORY_ATTEMPT' && entry.referenceId === entry.targetId
    && entry.referenceRevision === 0 && entry.targetRevision === null && entry.targetVersionId === null && entry.targetVersionNumber === null
    && entry.targetCurrent && Object.hasOwn(memoryAttemptStateLabels, entry.status)
    && referenceIdValid(entry.anchorItemId) && referenceIdValid(entry.anchorVersionId) && entry.otherItemId === null
  // 固定卡片版本仅作历史导航锚点，不是来源历史事件自身的版本。
  if (referenceMemoryHistoryStatus(kind)) return entry.targetKind === 'MEMORY_SOURCE_HISTORY' && entry.referenceId === entry.targetId
    && entry.referenceRevision === 0 && entry.targetRevision === null && entry.targetVersionId === null && entry.targetVersionNumber === null
    && entry.targetCurrent && entry.status === referenceMemoryHistoryStatus(kind)
    && referenceIdValid(entry.anchorItemId) && referenceIdValid(entry.anchorVersionId) && entry.otherItemId === null
  if (entry.anchorVersionId !== null) return false
  if (kind === 'INTERVIEW_MATERIALS') return entry.targetKind === 'INTERVIEW_EVENT' && entry.referenceId === entry.targetId
    && entry.targetRevision === entry.referenceRevision && entry.targetVersionId === null && entry.targetVersionNumber === null
    && Object.hasOwn(scheduleStatusLabels, entry.status) && entry.anchorItemId === null && entry.otherItemId === null
  if (kind === 'BOARD_SNAPSHOTS') return entry.targetKind === 'BOARD' && entry.referenceId === entry.targetId
    && integer(entry.referenceRevision) && entry.targetRevision === entry.referenceRevision && entry.targetVersionId === null
    && entry.targetVersionNumber === null && entry.status === 'AVAILABLE' && entry.anchorItemId === null && entry.otherItemId === null
  if (kind === 'TODAY_PLACEMENT_HISTORY') return entry.targetKind === 'TODAY_PLACEMENT' && entry.referenceId === entry.targetId
    && entry.targetRevision === entry.referenceRevision && entry.targetVersionId === null && entry.targetVersionNumber === null
    && entry.title === '知识安排偏好历史' && (entry.status === 'VISIBLE' || entry.status === 'PAUSED')
    && referenceIdValid(entry.anchorItemId) && entry.anchorItemId === itemId && entry.otherItemId === null
  if (entry.referenceRevision !== 0 || entry.targetRevision !== null) return false
  if (kind === 'TODAY_PLACEMENTS') return entry.targetKind === 'TODAY_PLACEMENT' && entry.referenceId === entry.targetId
    && entry.targetVersionId === null && entry.targetVersionNumber === null && entry.title === '知识安排偏好'
    && (entry.status === 'VISIBLE' || entry.status === 'PAUSED') && entry.targetCurrent
    && referenceIdValid(entry.anchorItemId) && entry.anchorItemId === itemId && entry.otherItemId === null
  if (kind === 'TRAINING_QUESTION_SNAPSHOTS' || kind === 'TRAINING_DRAFT_DIAGRAMS') return entry.targetKind === 'TRAINING_QUESTION'
    && entry.referenceId === entry.targetId && entry.targetVersionId === null && entry.targetVersionNumber === null
    && entry.targetCurrent && Object.hasOwn(trainingQuestionStatusLabels, entry.status)
    && referenceIdValid(entry.anchorItemId) && entry.otherItemId === null
  if (kind === 'TRAINING_ANSWER_DIAGRAMS') return entry.targetKind === 'TRAINING_ANSWER' && entry.referenceId === entry.targetId
    && entry.targetVersionId === null && entry.targetVersionNumber === null && entry.targetCurrent && entry.status === 'SUBMITTED'
    && referenceIdValid(entry.anchorItemId) && referenceIdValid(entry.otherItemId)
  if (referenceAiPurpose(kind)) return entry.targetKind === 'AI_TASK' && entry.referenceId === entry.targetId
    && entry.targetVersionId === null && entry.targetVersionNumber === null && entry.anchorItemId === null && entry.otherItemId === null
    && entry.targetCurrent && typeof entry.status === 'string' && Object.hasOwn(aiStatusLabels, entry.status)
  if (kind === 'RELATIONS' || kind === 'RELATION_SUPPLEMENTS') return entry.targetKind === 'RELATION' && Object.hasOwn(relationLabels, entry.title)
    && (kind === 'RELATIONS' ? entry.status === 'ACTIVE' && entry.targetCurrent && entry.referenceId === entry.targetId
      : ['ACTIVE', 'REMOVED'].includes(entry.status) && entry.targetCurrent === (entry.status === 'ACTIVE'))
    && entry.targetVersionId === null && entry.targetVersionNumber === null && referenceIdValid(entry.anchorItemId)
    && referenceIdValid(entry.otherItemId) && entry.anchorItemId !== entry.otherItemId
    && (kind === 'RELATION_SUPPLEMENTS' || entry.anchorItemId === itemId || entry.otherItemId === itemId)
  if (entry.anchorItemId !== null || entry.otherItemId !== null || !['ACTIVE', 'ARCHIVED'].includes(entry.status)) return false
  const expectedKind = kind === 'PREPARATION_VERSIONS' ? 'PREPARATION' : kind === 'QUESTION_VERSIONS' ? 'QUESTION'
    : kind === 'MEMORY_VERSIONS' || kind === 'MEMORY_CONCEPTS' ? 'MEMORY_CARD' : 'KNOWLEDGE'
  if (entry.targetKind !== expectedKind) return false
  if (kind === 'CONCEPT_NOTES' || kind === 'MAIN_NOTES') return entry.targetVersionId === null && entry.targetVersionNumber === null && entry.targetCurrent
  return referenceIdValid(entry.targetVersionId) && positive(entry.targetVersionNumber)
}
export function knowledgeReferencePageValid(value: unknown, itemId: string, versionId: string, versionNumber: number,
  kind: KnowledgeReferenceKind, cursor?: string): value is KnowledgeReferencePage {
  try {
    const page = value as KnowledgeReferencePage
    const after = cursor === undefined ? undefined : knowledgeReferenceCursor(cursor, itemId, versionId, kind)
    if (!fields(page, ['kind', 'versionFilterApplied', 'coverage', 'source', 'items', 'nextCursor'])
      || page.kind !== kind || page.versionFilterApplied !== referenceVersionFiltered(kind) || page.coverage !== 'REGISTERED_DIRECT_ONLY'
      || !fields(page.source, ['id', 'kind', 'status', 'revision', 'currentVersionId', 'versionId', 'versionNumber', 'title', 'selectedVersionCurrent'])
      || page.source.id !== itemId || page.source.versionId !== versionId || page.source.versionNumber !== versionNumber
      || !['NOTE', 'CONCEPT'].includes(page.source.kind) || !['ACTIVE', 'ARCHIVED'].includes(page.source.status)
      || !integer(page.source.revision) || !referenceIdValid(page.source.currentVersionId) || !title(page.source.title)
      || page.source.selectedVersionCurrent !== (page.source.currentVersionId === versionId)
      || !Array.isArray(page.items) || page.items.length > 20 || cursor !== undefined && !after) return false
    let previous = after
    for (const entry of page.items) {
      if (!entryValid(entry, kind, itemId) || previous && compare(previous, entry) >= 0) return false
      previous = entry
    }
    if (page.nextCursor === null) return true
    const next = knowledgeReferenceCursor(page.nextCursor, itemId, versionId, kind)
    return page.items.length === 20 && Boolean(next && previous && compare(previous, next) === 0)
  } catch { return false }
}
export function knowledgeReferenceTitle(entry: KnowledgeReferenceEntry): string {
  if (entry.targetKind === 'RELATION') return `${relationLabels[entry.title] ?? entry.title}关系`
  if (entry.targetKind === 'MEMORY_SOURCE_HISTORY') return `所属卡片：${entry.title}`
  return entry.targetKind === 'STANDARD_REVIEW' ? `所属题目：${entry.title}` : entry.title
}
export function knowledgeReferenceStatusLabel(entry: KnowledgeReferenceEntry, kind: KnowledgeReferenceKind): string {
  const purpose = referenceAiPurpose(kind)
  if (entry.targetKind === 'AI_TASK' && purpose && Object.hasOwn(aiStatusLabels, entry.status)) return aiTaskStatusLabel(entry.status as AiStatus, purpose)
  if (entry.targetKind === 'STANDARD_REVIEW' && (entry.status === 'ACTIVE' || entry.status === 'WITHDRAWN')) return `审阅当前状态：${recordStatusLabels[entry.status]}`
  if (entry.targetKind === 'INTERVIEW_EVENT' && (entry.status === 'PLANNED' || entry.status === 'COMPLETED' || entry.status === 'CANCELLED')) return `安排当时状态：${scheduleStatusLabels[entry.status]}`
  if (entry.targetKind === 'TODAY_PLACEMENT' && (entry.status === 'VISIBLE' || entry.status === 'PAUSED')) {
    return `${kind === 'TODAY_PLACEMENT_HISTORY' ? '偏好当时状态' : '偏好'}：${entry.status === 'PAUSED' ? '安排暂停' : '正常显示'}`
  }
  if (entry.targetKind === 'TRAINING_QUESTION' && Object.hasOwn(trainingQuestionStatusLabels, entry.status)) return trainingQuestionStatusLabels[entry.status as keyof typeof trainingQuestionStatusLabels]
  if (entry.targetKind === 'TRAINING_ANSWER' && entry.status === 'SUBMITTED') return '已正式提交'
  if (entry.targetKind === 'MEMORY_ATTEMPT' && Object.hasOwn(memoryAttemptStateLabels, entry.status)) return memoryAttemptStateLabels[entry.status as keyof typeof memoryAttemptStateLabels]
  if (entry.targetKind === 'MEMORY_SOURCE_HISTORY' && (entry.status === 'SOURCE_REVIEW' || entry.status === 'IMPACT')) return entry.status === 'SOURCE_REVIEW' ? '来源复核事件' : '来源影响事件'
  return entry.status === 'ARCHIVED' ? '已归档' : entry.status === 'REMOVED' ? '保留历史身份' : entry.status === 'AVAILABLE' ? '可打开' : '未归档'
}
export function knowledgeReferenceRoute(entry: KnowledgeReferenceEntry): RouteLocationRaw | undefined {
  if (!referenceIdValid(entry.targetId)) return undefined
  if (entry.targetKind === 'KNOWLEDGE') return { name: 'knowledge', query: { item: entry.targetId, version: entry.targetVersionId ?? undefined } }
  if (entry.targetKind === 'PREPARATION') return { name: 'prepare', query: { item: entry.targetId, version: entry.targetVersionId ?? undefined } }
  if (entry.targetKind === 'QUESTION') return { name: 'bank', query: { item: entry.targetId, version: entry.targetVersionId ?? undefined } }
  if (entry.targetKind === 'MEMORY_CARD') return { name: 'memory-cards', query: { item: entry.targetId, versionId: entry.targetVersionId ?? undefined } }
  if (entry.targetKind === 'BOARD') return { name: 'boards', query: { board: entry.targetId } }
  if (entry.targetKind === 'AI_TASK') return { name: 'ai', query: { item: entry.targetId } }
  if (entry.targetKind === 'TODAY_PLACEMENT') return { name: 'today' }
  if (entry.targetKind === 'TRAINING_QUESTION' && referenceIdValid(entry.anchorItemId)) return { name: 'training', query: { item: entry.anchorItemId, question: entry.targetId } }
  if (entry.targetKind === 'TRAINING_ANSWER' && referenceIdValid(entry.anchorItemId) && referenceIdValid(entry.otherItemId)) {
    return { name: 'training', query: { item: entry.anchorItemId, question: entry.otherItemId, answer: entry.targetId } }
  }
  if (entry.targetKind === 'MEMORY_ATTEMPT') return { name: 'memory-recall', query: { attempt: entry.targetId } }
  if (entry.targetKind === 'MEMORY_SOURCE_HISTORY' && referenceIdValid(entry.anchorItemId) && referenceIdValid(entry.anchorVersionId)) {
    return { name: 'memory-cards', query: { item: entry.anchorItemId, versionId: entry.anchorVersionId } }
  }
  if (entry.targetKind === 'STANDARD_REVIEW' && referenceIdValid(entry.anchorItemId) && referenceIdValid(entry.anchorVersionId)) {
    return { name: 'bank', query: { item: entry.anchorItemId, version: entry.anchorVersionId } }
  }
  if (entry.targetKind === 'INTERVIEW_EVENT' && integer(entry.targetRevision)) return { name: 'schedule', query: { item: entry.targetId, version: String(entry.targetRevision) } }
  if (entry.targetKind === 'RELATION' && referenceIdValid(entry.anchorItemId)) return { name: 'knowledge', query: { item: entry.anchorItemId } }
  return undefined
}
