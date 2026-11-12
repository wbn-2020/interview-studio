import type { Schema } from './client'
import type { QuestionInput, QuestionPrompt } from './bank'
import { questionTextLength, questionTypeLabels } from './bank'
import { reviewIdValid } from './review'
import { scheduleStatusLabels } from './schedule'

export type Recollection = Schema['Recollection']
export type RecollectionContent = Schema['RecollectionContent']
export type RecollectionInterviewLink = Schema['RecollectionInterviewLink']
export type RecollectionVersion = Schema['RecollectionVersion']
export type RecollectionPage = Schema['RecollectionPage']
export type RecollectionVersionPage = Schema['RecollectionVersionPage']
export type RecollectionQuestionLookup = Schema['RecollectionQuestionLookup']
export type RecollectionQuestionAdoption = Schema['RecollectionQuestionAdoption']
export type RecollectionQuestionOrigin = Schema['RecollectionQuestionOrigin']
export type RecollectionQuestionLink = NonNullable<RecollectionQuestionLookup['question']>
export type RecollectionInterviewSnapshot = NonNullable<RecollectionVersion['interview']>

export const recollectionStatusLabels: Record<Recollection['status'], string> = { ACTIVE: '使用中', ARCHIVED: '已归档' }
export const recollectionBaselineLabels: Record<RecollectionContent['baselineKind'], string> = { RECOLLECTION: '有回答回忆片段', NONE: '没有旧回答' }
export const recollectionSourceLabels: Record<NonNullable<RecollectionQuestionOrigin['source']>['state'], string> = {
  CURRENT: '仍为当前回忆版本', SUPERSEDED: '回忆已有后续补记', ARCHIVED: '回忆已归档',
}
const contentFields = ['title', 'question', 'type', 'conditions', 'baselineKind', 'answerFragment', 'feedback', 'uncertainty', 'gap']
const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const integer = (value: unknown, minimum: number, maximum: number): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const timestamp = (value: unknown): value is string => typeof value === 'string' && value.endsWith('Z') && Number.isFinite(Date.parse(value))
// 回忆正文与历史来源按后端 Java isBlank 判断必填，避免 JS trim 误拒已保存的原文。
const text = (value: unknown, maximum: number, required = false): value is string => typeof value === 'string'
  && (!required || /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value))
  && questionTextLength(value) <= maximum && !/[\u0000\uD800-\uDFFF]/u.test(value)
const bytes = (value: unknown) => new TextEncoder().encode(JSON.stringify(value)).length

export function recollectionEmptyContent(): RecollectionContent {
  return { title: '', question: '', type: 'EXPLAIN', conditions: '', baselineKind: 'NONE', answerFragment: '', feedback: '', uncertainty: '', gap: '' }
}
export function recollectionContentProblem(content: RecollectionContent): string {
  if (!fields(content, contentFields)) return '回忆字段不完整或含未定义字段，请重新核对。'
  if (!text(content.title, 200, true)) return '标题需为 1–200 个完整字符，不能含 NUL。'
  if (!text(content.question, 10000, true)) return '请填写 1–10000 个完整字符的回忆问题。'
  if (!Object.hasOwn(questionTypeLabels, content.type)) return '请选择问题分类。'
  if (!text(content.conditions, 5000)) return '记得的条件最多 5000 个完整字符。'
  if (!Object.hasOwn(recollectionBaselineLabels, content.baselineKind)) return '请明确选择有回答片段或没有旧回答。'
  if (!text(content.answerFragment, 20000, content.baselineKind === 'RECOLLECTION')) return '回答回忆片段最多 20000 个完整字符；选择有片段时必须填写。'
  if (content.baselineKind === 'NONE' && content.answerFragment !== '') return '选择没有旧回答时，回答片段须为空；已有文字请先明确处理。'
  if (!text(content.feedback, 5000)) return '回忆的反馈最多 5000 个完整字符。'
  if (!text(content.uncertainty, 2000, true)) return '请用 1–2000 个完整字符说明记不清之处或你的确定性判断。'
  if (!text(content.gap, 2000)) return '你认为的待补内容最多 2000 个完整字符。'
  return bytes(content) > 196608 ? '这一版本的回忆内容超过 192 KiB，请缩减后保存；内容不会截断。' : ''
}
export function recollectionInterviewValid(value: RecollectionInterviewSnapshot): boolean {
  return Boolean(fields(value, ['eventId', 'revision', 'title', 'round', 'startAt', 'zoneId', 'status', 'targetId', 'targetVersionId'])
    && reviewIdValid(value.eventId) && integer(value.revision, 0, Number.MAX_SAFE_INTEGER) && text(value.title, 200, true)
    && text(value.round, 100) && timestamp(value.startAt) && text(value.zoneId, 100, true) && Object.hasOwn(scheduleStatusLabels, value.status)
    && ((value.targetId === null && value.targetVersionId === null) || (reviewIdValid(value.targetId) && reviewIdValid(value.targetVersionId)))
    && bytes(value) <= 8192)
}
export function recollectionVersionValid(value: RecollectionVersion): boolean {
  try { return Boolean(fields(value, ['id', 'number', 'content', 'interview', 'createdAt']) && reviewIdValid(value.id) && integer(value.number, 1, 1000)
    && !recollectionContentProblem(value.content) && (value.interview === null || recollectionInterviewValid(value.interview)) && timestamp(value.createdAt)) } catch { return false }
}
export function recollectionValid(value: Recollection, id?: string): boolean {
  try { return Boolean(fields(value, ['id', 'status', 'revision', 'currentVersion', 'createdAt', 'updatedAt']) && reviewIdValid(value.id)
    && (id === undefined || value.id === id) && Object.hasOwn(recollectionStatusLabels, value.status) && integer(value.revision, 0, 1000)
    && recollectionVersionValid(value.currentVersion) && value.currentVersion.number <= value.revision + 1 && timestamp(value.createdAt) && timestamp(value.updatedAt)) } catch { return false }
}
export function recollectionQuestionLinkValid(value: RecollectionQuestionLink, id?: string, versionId?: string): boolean {
  return Boolean(fields(value, ['recollectionId', 'recollectionVersionId', 'questionId', 'questionVersionId', 'createdAt'])
    && reviewIdValid(value.recollectionId) && reviewIdValid(value.recollectionVersionId) && reviewIdValid(value.questionId) && reviewIdValid(value.questionVersionId)
    && (id === undefined || value.recollectionId === id) && (versionId === undefined || value.recollectionVersionId === versionId) && timestamp(value.createdAt))
}
export function recollectionQuestionLookupValid(value: RecollectionQuestionLookup, id: string, versionId: string): boolean {
  try { return fields(value, ['question']) && (value.question === null || recollectionQuestionLinkValid(value.question, id, versionId)) } catch { return false }
}
export function recollectionQuestionOriginValid(value: RecollectionQuestionOrigin, questionId: string, versionId: string): boolean {
  try {
    if (!fields(value, ['source'])) return false
    if (value.source === null) return true
    const source = value.source
    return fields(source, ['link', 'recollection', 'state']) && recollectionQuestionLinkValid(source.link)
      && source.link.questionId === questionId && source.link.questionVersionId === versionId
      && recollectionVersionValid(source.recollection) && source.recollection.id === source.link.recollectionVersionId
      && Object.hasOwn(recollectionSourceLabels, source.state)
  } catch { return false }
}
export function recollectionQuestionAdoptionValid(value: RecollectionQuestionAdoption, id: string, versionId: string, content: QuestionInput): boolean {
  try {
    if (!fields(value, ['question', 'link']) || !recollectionQuestionLinkValid(value.link, id, versionId)) return false
    const question = value.question, version = question.currentVersion
    if (!fields(question, ['id', 'status', 'revision', 'favorite', 'createdAt', 'updatedAt', 'currentVersion', 'knowledgeReferences'])
      || question.id !== value.link.questionId || question.status !== 'ACTIVE' || question.revision !== 0 || question.favorite !== false
      || !timestamp(question.createdAt) || !timestamp(question.updatedAt)
      || !fields(version, ['id', 'number', 'createdAt', 'content', 'standardStatus', 'hasReference'])
      || version.id !== value.link.questionVersionId || version.number !== 1 || !timestamp(version.createdAt)
      || version.standardStatus !== 'PERSONAL_UNREVIEWED' || version.hasReference !== Boolean(content.referenceAnswer.length || content.checkpoints.length)) return false
    const prompt = version.content
    if (!fields(prompt, ['title', 'prompt', 'conditions', 'technology', 'scenario', 'type', 'difficulty', 'dimensions', 'topicIds', 'knowledgeLinks', 'tags', 'source'])
      || !fields(prompt.source, ['title', 'url', 'note']) || !Array.isArray(prompt.knowledgeLinks)
      || prompt.knowledgeLinks.some(link => !fields(link, ['itemId', 'versionId']))
      || promptSignature(prompt) !== promptSignature(content) || !Array.isArray(question.knowledgeReferences)
      || question.knowledgeReferences.length !== content.knowledgeLinks.length) return false
    // 创建回执只含题面与参考存在标记；隐藏参考不通过来源读取绕开显式查看规则。
    return question.knowledgeReferences.every((reference, index) => fields(reference, ['itemId', 'versionId', 'title', 'number', 'state'])
      && reference.itemId === content.knowledgeLinks[index]!.itemId && reference.versionId === content.knowledgeLinks[index]!.versionId
      && text(reference.title, 200, true) && integer(reference.number, 1, Number.MAX_SAFE_INTEGER) && ['CURRENT', 'SUPERSEDED', 'ARCHIVED'].includes(reference.state))
  } catch { return false }
}
function promptSignature(content: QuestionPrompt): string {
  return JSON.stringify([content.title, content.prompt, content.conditions, content.technology, content.scenario, content.type, content.difficulty,
    content.dimensions, content.topicIds, content.knowledgeLinks.map(link => [link.itemId, link.versionId]), content.tags,
    content.source ? [content.source.title, content.source.url, content.source.note] : ['', '', '']])
}
