import type { RouteLocationRaw } from 'vue-router'
import type { Schema } from './client'
import { read } from './client'
import { questionDimensionLabels, questionTypeLabels } from './bank'
import { reviewAnswerMatches, reviewChoiceLabels, reviewCoverageLabels, reviewIdValid, reviewKindLabels } from './review'
import { trainingFeedbackLabels, trainingModeLabels } from './training'
import { reportAnswerConditionLabels, reportQuestionKindLabels } from './trainingReport'

export type SearchDomain = Schema['UnifiedSearchDomain']
export type SearchFragment = Schema['UnifiedSearchFragment']
export type SearchHit = Schema['UnifiedSearchHit']
export type SearchPage = Schema['UnifiedSearchPage']
export type SearchSource = Schema['UnifiedSearchSource']
export type SearchField = Schema['UnifiedSearchTextField']
export type SearchCriteria = { domain: SearchDomain; q: string; status: 'ACTIVE' | 'ARCHIVED' }

export const searchDomains: { value: SearchDomain; label: string; scope: string }[] = [
  { value: 'KNOWLEDGE', label: '知识库', scope: '搜索当前已发布的笔记与概念，支持分词、别名和标签；未发布草稿不在范围内。' },
  { value: 'PREPARATION', label: '准备材料', scope: '按完整检索词查找目标、项目、事实和表达的当前版本。事实核实状态单独显示。' },
  { value: 'MATERIAL_EXCERPT', label: '资料摘录', scope: '按完整检索词查找已保存摘录的定位说明和原文；附件名仅作标题展示。' },
  { value: 'QUESTION', label: '题库题面', scope: '按完整检索词查找当前题面、条件、技术、场景和标签；参考答案与检查项不参与搜索。' },
  { value: 'TRAINING_ANSWER', label: '训练回答', scope: '查找每次正式提交的文字与答案图标签，保留当时作答条件；未提交草稿不参与搜索。' },
  { value: 'REVIEW_FEEDBACK', label: '人工反馈', scope: '查找已开放人工反馈的当前修订，保留适用条件、观察依据和判断限制。' },
  { value: 'RECOLLECTION', label: '面后回忆', scope: '查找个人回忆的当前版本，保留有无旧回答和完整不确定性；回忆不代表正式训练提交。' },
  { value: 'AI_FEEDBACK', label: 'AI 评价', scope: '按完整检索词查找已开放报告中的逐项观察，保留 AI 未审身份、原答条件和完整报告限制。' },
]

export const searchKindLabels: Record<string, string> = {
  NOTE: '笔记', CONCEPT: '概念', TARGET: '目标与 JD', PROJECT: '项目概况', FACT: '项目事实', EXPRESSION: '表达素材',
  EXCERPT: '资料摘录', QUESTION: '题库题目', EXPLAIN: '原理解释', CODE_READING: '代码阅读', DEBUG: '排查问题',
  TRADEOFF: '方案取舍', SYSTEM_DESIGN: '系统设计', BEHAVIOR: '行为面试',
  ANSWER: '正式训练回答', SELF_CHECK: '自检', EXTERNAL_FEEDBACK: '手工录入外部意见', RECOLLECTION: '个人回忆', AI_FEEDBACK: 'AI 评价观察',
}
export const searchStatusLabels: Record<string, string> = { ACTIVE: '未归档', ARCHIVED: '已归档', READY: '已保存摘录', SUBMITTED: '已正式提交', RECORDED: '已记录反馈', AI_UNREVIEWED: 'AI 未审' }
export const searchVerificationLabels: Record<string, string> = { UNVERIFIED: '待核实', CONFIRMED: '用户已确认', WITHDRAWN: '确认已撤回' }
export const searchProjectLabels: Record<string, string> = { ACTIVE: '未归档', ARCHIVED: '已归档', UNAVAILABLE: '不可用' }

export function getSearchPage(criteria: SearchCriteria, offset: number): Promise<SearchPage> {
  return read<SearchPage>(`/search?${new URLSearchParams({ ...criteria, limit: '20', offset: String(offset) })}`)
}

export function getSearchSource(hit: SearchHit, q: string): Promise<SearchSource> {
  const query = new URLSearchParams({ versionId: hit.versionId, q })
  if (hit.domain === 'REVIEW_FEEDBACK') query.set('revision', String(hit.versionNumber))
  if (hit.domain === 'AI_FEEDBACK' && hit.aiFeedback) {
    query.set('feedbackId', hit.aiFeedback.feedbackId)
    query.set('reportHash', hit.aiFeedback.reportHash)
  }
  return read<SearchSource>(`/search/sources/${hit.domain}/${encodeURIComponent(hit.id)}?${query}`)
}

export function searchHitKey(hit: SearchHit): string {
  // 人工反馈的 UUID 不随修订变化；修订必须同时参与请求、选中态和迟到响应身份。
  const suffix = hit.domain === 'REVIEW_FEEDBACK' ? `/r${hit.versionNumber}`
    : hit.domain === 'AI_FEEDBACK' ? `/${hit.aiFeedback?.reportHash}/${hit.aiFeedback?.feedbackId}` : ''
  return `${hit.domain}/${hit.id}/${hit.versionId}${suffix}`
}
export function searchKindLabel(hit: SearchHit): string {
  if (hit.domain === 'RECOLLECTION') return '个人回忆'
  return hit.domain === 'QUESTION' && hit.kind === 'PROJECT' ? '项目题' : searchKindLabels[hit.kind] ?? hit.kind
}
export function searchVersionLabel(hit: SearchHit): string {
  if (hit.domain === 'TRAINING_ANSWER') return `正式提交 #${hit.versionNumber}`
  if (hit.domain === 'REVIEW_FEEDBACK') return `反馈 r${hit.versionNumber}`
  if (hit.domain === 'RECOLLECTION') return `个人回忆 v${hit.versionNumber}`
  if (hit.domain === 'AI_FEEDBACK') return `AI 观察 ${hit.aiFeedback?.feedbackId}`
  return hit.domain === 'MATERIAL_EXCERPT' ? '固定摘录' : hit.versionNumber == null ? '确切版本' : `v${hit.versionNumber}`
}
export function searchSupersededLabel(hit: SearchHit): string {
  return hit.domain === 'TRAINING_ANSWER' ? '同题已有更新提交' : hit.domain === 'REVIEW_FEEDBACK' ? '反馈已有新修订' : '已有新版本'
}

const integer = (value: unknown, min = 0, max = 2147483647): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
const text = (value: unknown): value is string => typeof value === 'string' && !/[\u0000\uD800-\uDFFF]/u.test(value)
const unicode = (value: unknown): value is string => typeof value === 'string' && !/[\uD800-\uDFFF]/u.test(value)
const hasJavaText = (value: string): boolean => /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value)
// 与来源写入的 Java isBlank 保持一致，避免 JS trim 误拒已保存的 NBSP 等原文。
const nonBlank = (value: unknown): value is string => text(value) && hasJavaText(value)
// 历史 AI 报告允许 NUL；只按原报告 Unicode、码点长度和 Java 必填规则读取。
const aiText = (value: unknown, maximum: number, required = true): value is string => unicode(value)
  && value.length <= maximum * 2 && Array.from(value).length <= maximum && (!required || hasJavaText(value))
const timestamp = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const feedbackFields = ['observation', 'condition', 'basis', 'limitation', 'suggestion']
const recollectionFields = ['title', 'question', 'conditions', 'answerFragment', 'feedback', 'uncertainty', 'gap']
function historyFieldAllowed(domain: SearchDomain, field: string): boolean {
  if (domain === 'TRAINING_ANSWER') return field === 'text' || /^diagram\.(?:nodes\.(?:0|[1-9]\d*)\.(?:label|description)|edges\.(?:0|[1-9]\d*)\.label)$/.test(field)
  if (domain === 'REVIEW_FEEDBACK' || domain === 'AI_FEEDBACK') return feedbackFields.includes(field)
  if (domain === 'RECOLLECTION') return recollectionFields.includes(field)
  return true
}
function trainingContextValid(value: SearchHit['training']): boolean {
  if (!value || !value.answer || ![value.answer.sessionId, value.answer.questionId, value.answer.answerId].every(reviewIdValid)
    || !['RUNNING', 'PAUSED', 'ENDED'].includes(value.sessionStatus) || !Object.hasOwn(trainingFeedbackLabels, value.feedbackTiming)) return false
  const conditions = value.conditions, exposure = conditions?.priorExposure
  return Boolean(conditions && Object.hasOwn(trainingModeLabels, conditions.mode) && Object.hasOwn(reportAnswerConditionLabels, conditions.answerCondition)
    && Object.hasOwn(reportQuestionKindLabels, conditions.questionKind) && integer(conditions.answerNumber, 1, 100)
    && integer(conditions.assistanceEventSequence, 0, 2000) && timestamp(conditions.answerCreatedAt)
    && (conditions.mode === 'LEARNING') === (conditions.answerCondition === 'LEARNING')
    && (conditions.answerCondition !== 'BEFORE_ASSISTANCE' || conditions.assistanceEventSequence === 0)
    && (conditions.answerCondition !== 'ASSISTED' || conditions.assistanceEventSequence > 0)
    && exposure && integer(exposure.authoredCount) && integer(exposure.referenceViewCount) && integer(exposure.evaluationAccessCount)
    && (exposure.lastExposureAt === null || timestamp(exposure.lastExposureAt) && Date.parse(exposure.lastExposureAt) <= Date.parse(conditions.answerCreatedAt))
    && [conditions.technology, conditions.scenario, conditions.questionConditions].every(text))
}

export function searchHitValid(hit: SearchHit): boolean {
  try {
    const sourceText = hit?.domain === 'AI_FEEDBACK' ? unicode : text
    if (!hit || !searchDomains.some(option => option.value === hit.domain) || !reviewIdValid(hit.id) || !reviewIdValid(hit.versionId)
      || !sourceText(hit.title) || !text(hit.kind) || !Object.hasOwn(searchStatusLabels, hit.status) || typeof hit.currentVersion !== 'boolean'
      || !Array.isArray(hit.fragments) || hit.fragments.length > 3
      || hit.fragments.some(fragment => !fragment || !text(fragment.field) || !text(fragment.label) || !sourceText(fragment.text)
        || !historyFieldAllowed(hit.domain, fragment.field) || !integer(fragment.lineStart, 1) || !integer(fragment.lineEnd, fragment.lineStart)
        || !searchFragmentParts(fragment).match)) return false
    if (hit.domain === 'TRAINING_ANSWER') return hit.kind === 'ANSWER' && hit.status === 'SUBMITTED' && hit.id === hit.versionId
      && trainingContextValid(hit.training) && hit.training!.answer.answerId === hit.id && hit.versionNumber === hit.training!.conditions.answerNumber
      && hit.feedback == null && hit.recollection == null && hit.aiFeedback == null
    if (hit.domain === 'REVIEW_FEEDBACK') {
      const feedback = hit.feedback, decision = feedback?.decision
      return Boolean(hit.status === 'RECORDED' && hit.id === hit.versionId && integer(hit.versionNumber, 1, 100) && trainingContextValid(hit.training)
        && feedback && hit.kind === feedback.kind && Object.hasOwn(reviewKindLabels, feedback.kind) && text(feedback.authorLabel)
        && (feedback.kind !== 'EXTERNAL_FEEDBACK' || nonBlank(feedback.authorLabel)) && Object.hasOwn(questionDimensionLabels, feedback.dimension)
        && Object.hasOwn(reviewCoverageLabels, feedback.coverage) && text(feedback.condition) && nonBlank(feedback.basis)
        && nonBlank(feedback.limitation) && hit.recollection == null && hit.aiFeedback == null
        && (decision === null || decision && reviewIdValid(decision.id) && integer(decision.revision, 1, 20)
          && Object.hasOwn(reviewChoiceLabels, decision.choice) && text(decision.reason) && timestamp(decision.createdAt)
          && (decision.choice !== 'DISPUTED' || nonBlank(decision.reason))))
    }
    if (hit.domain === 'RECOLLECTION') return Boolean(['ACTIVE', 'ARCHIVED'].includes(hit.status) && integer(hit.versionNumber, 1)
      && hit.recollection && ['RECOLLECTION', 'NONE'].includes(hit.recollection.baselineKind)
      && Object.hasOwn(questionTypeLabels, hit.recollection.questionType) && nonBlank(hit.recollection.uncertainty)
      && hit.training == null && hit.feedback == null && hit.aiFeedback == null)
    if (hit.domain === 'AI_FEEDBACK') {
      const ai = hit.aiFeedback
      return Boolean(hit.kind === 'AI_FEEDBACK' && hit.status === 'AI_UNREVIEWED' && hit.versionNumber === null && hit.currentVersion
        && trainingContextValid(hit.training) && hit.feedback == null && hit.recollection == null && ai && ai.level === 'AI_UNREVIEWED'
        && typeof ai.feedbackId === 'string' && /^f([1-9]|1[0-2])$/.test(ai.feedbackId)
        && typeof ai.reportHash === 'string' && /^[0-9a-f]{64}$/.test(ai.reportHash)
        && aiText(ai.reportLimitations, 2000) && aiText(ai.validationNote, 2000)
        && Object.hasOwn(questionDimensionLabels, ai.dimension) && Object.hasOwn(reviewCoverageLabels, ai.coverage)
        && aiText(ai.condition, 1000, false) && aiText(ai.basis, 1000) && aiText(ai.limitation, 1000)
        && integer(ai.decisionRevision, 0, 20) && (ai.decisionRevision === 0 ? ai.decisionChoice === null
          : typeof ai.decisionChoice === 'string' && Object.hasOwn(reviewChoiceLabels, ai.decisionChoice)))
    }
    return hit.training == null && hit.feedback == null && hit.recollection == null && hit.aiFeedback == null
  } catch { return false }
}

export function searchSourceValid(source: SearchSource, selected: SearchHit): boolean {
  try {
    const sourceText = source?.item?.domain === 'AI_FEEDBACK' ? unicode : text
    if (!source || !searchHitValid(source.item) || searchHitKey(source.item) !== searchHitKey(selected)
      || source.item.versionNumber !== selected.versionNumber || source.item.kind !== selected.kind
      || source.item.training && !reviewAnswerMatches(source.item.training.answer, selected.training?.answer)
      || !Array.isArray(source.fields)
      || new Set(source.fields.map(field => field.id)).size !== source.fields.length
      || source.fields.some(field => !field || !text(field.id) || !text(field.label) || !sourceText(field.text) || !historyFieldAllowed(source.item.domain, field.id))) return false
    const fields = new Map(source.fields.map(field => [field.id, field.text])), item = source.item
    if (item.domain === 'TRAINING_ANSWER') return fields.has('text')
    if (item.domain === 'REVIEW_FEEDBACK') return feedbackFields.every(field => fields.has(field))
      && fields.get('condition') === item.feedback!.condition && fields.get('basis') === item.feedback!.basis && fields.get('limitation') === item.feedback!.limitation
    if (item.domain === 'RECOLLECTION') return recollectionFields.every(field => fields.has(field)) && fields.get('uncertainty') === item.recollection!.uncertainty
      && (item.recollection!.baselineKind === 'NONE' ? fields.get('answerFragment') === '' : nonBlank(fields.get('answerFragment')))
    if (item.domain === 'AI_FEEDBACK') return feedbackFields.every(field => fields.has(field))
      && aiText(fields.get('observation'), 2000) && aiText(fields.get('suggestion'), 1000)
      && fields.get('condition') === item.aiFeedback!.condition && fields.get('basis') === item.aiFeedback!.basis && fields.get('limitation') === item.aiFeedback!.limitation
      && selected.aiFeedback !== undefined && item.aiFeedback!.decisionRevision >= selected.aiFeedback.decisionRevision
      && (item.aiFeedback!.decisionRevision !== selected.aiFeedback.decisionRevision || item.aiFeedback!.decisionChoice === selected.aiFeedback.decisionChoice)
    return true
  } catch { return false }
}

function splitSurrogate(text: string, offset: number): boolean {
  return offset > 0 && offset < text.length && /[\uD800-\uDBFF]/.test(text[offset - 1]) && /[\uDC00-\uDFFF]/.test(text[offset])
}

export function searchTextParts(text: string, start: number, end: number) {
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end <= start || end > text.length
    || splitSurrogate(text, start) || splitSurrogate(text, end)) return { before: text, match: '', after: '' }
  return { before: text.slice(0, start), match: text.slice(start, end), after: text.slice(end) }
}

export function searchFragmentParts(fragment: SearchFragment) {
  // 服务端偏移属于字段原文；展示子串只在明确边界内减去片段起点，不重新搜索转换后的文本。
  if (!Number.isSafeInteger(fragment.startOffset) || !Number.isSafeInteger(fragment.endOffset)
    || fragment.startOffset < 0 || fragment.endOffset - fragment.startOffset !== fragment.text.length) {
    return { before: fragment.text, match: '', after: '' }
  }
  return searchTextParts(fragment.text, fragment.matchStart - fragment.startOffset, fragment.matchEnd - fragment.startOffset)
}

export function searchFieldFragment(field: SearchField, fragments: SearchFragment[]): SearchFragment | undefined {
  return fragments.find(fragment => fragment.field === field.id && fragment.startOffset >= 0
    && fragment.endOffset <= field.text.length
    && field.text.slice(fragment.startOffset, fragment.endOffset) === fragment.text
    && Boolean(searchFragmentParts(fragment).match))
}

export function searchSourceRoute(hit: SearchHit): RouteLocationRaw | undefined {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
  if (!uuid.test(hit.id) || !uuid.test(hit.versionId)) return undefined
  if (hit.domain === 'KNOWLEDGE') {
    const body = hit.fragments.find(fragment => fragment.field === 'body' && Boolean(searchFragmentParts(fragment).match)
      && Number.isSafeInteger(fragment.lineStart) && Number.isSafeInteger(fragment.lineEnd)
      && fragment.lineStart >= 1 && fragment.lineEnd >= fragment.lineStart)
    return { name: 'knowledge', query: { item: hit.id, version: hit.versionId, lineStart: body?.lineStart, lineEnd: body?.lineEnd } }
  }
  if (hit.domain === 'PREPARATION' && ['TARGET', 'PROJECT', 'FACT', 'EXPRESSION'].includes(hit.kind)) {
    return { name: 'prepare', query: { kind: hit.kind, item: hit.id, version: hit.versionId } }
  }
  if (hit.domain === 'QUESTION') return { name: 'bank', query: { item: hit.id, version: hit.versionId } }
  if (hit.domain === 'RECOLLECTION' && searchHitValid(hit)) return { name: 'recollections', query: { item: hit.id, versionId: hit.versionId } }
  if (hit.domain === 'AI_FEEDBACK' && searchHitValid(hit)) {
    const answer = hit.training!.answer
    return { name: 'review', query: { sessionId: answer.sessionId, questionId: answer.questionId, answerId: answer.answerId,
      taskId: hit.id, observationId: hit.aiFeedback!.feedbackId, aiAttemptId: hit.versionId, aiReportHash: hit.aiFeedback!.reportHash } }
  }
  if ((hit.domain === 'TRAINING_ANSWER' || hit.domain === 'REVIEW_FEEDBACK') && searchHitValid(hit)) {
    const answer = hit.training!.answer
    return hit.domain === 'TRAINING_ANSWER'
      ? { name: 'training', query: { item: answer.sessionId, question: answer.questionId, answer: answer.answerId } }
      : { name: 'review', query: { sessionId: answer.sessionId, questionId: answer.questionId, answerId: answer.answerId,
        feedbackId: hit.id, feedbackRevision: hit.versionNumber } }
  }
  if (hit.domain === 'MATERIAL_EXCERPT' && hit.attachmentId && uuid.test(hit.attachmentId)
    && hit.versionId === hit.id && (hit.runId === null || uuid.test(hit.runId))) {
    return { name: 'materials', query: { attachment: hit.attachmentId, excerpt: hit.id, run: hit.runId ?? undefined } }
  }
  return undefined
}
