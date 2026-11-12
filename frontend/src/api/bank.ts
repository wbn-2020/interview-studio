import type { Schema } from './client'

export type Question = Schema['BankQuestion']
export type QuestionInput = Schema['BankQuestionInput']
export type QuestionPrompt = Schema['BankQuestionPrompt']
export type QuestionVersion = Schema['BankQuestionVersion']
export type QuestionSummary = Schema['BankQuestionSummary']
export type QuestionPage = Schema['BankQuestionPage']
export type QuestionVersionPage = Schema['BankVersionPage']
export type QuestionReference = Schema['BankReference']
export type QuestionExposurePage = Schema['BankExposurePage']
export type QuestionType = Schema['BankQuestionType']
export type QuestionDimension = Schema['BankDimension']
export type QuestionKnowledgeReference = Schema['BankKnowledgeReference']

export const questionTypeLabels: Record<QuestionType, string> = {
  EXPLAIN: '原理解释', CODE_READING: '代码阅读', DEBUG: '排错', PROJECT: '项目经历',
  TRADEOFF: '技术取舍', SYSTEM_DESIGN: '系统设计', BEHAVIOR: '行为表达',
}
export const questionDimensionLabels: Record<QuestionDimension, string> = {
  EXPLAIN: '解释原理', DEBUG: '定位问题', TRADEOFF: '分析取舍',
  OWNERSHIP: '责任边界', COMMUNICATION: '表达沟通', OTHER: '其他',
}
export const knowledgeReferenceLabels: Record<QuestionKnowledgeReference['state'], string> = {
  CURRENT: '当前发布版本', SUPERSEDED: '知识已改版', ARCHIVED: '知识已归档',
}
export const questionTypeOptions = Object.entries(questionTypeLabels).map(([value, label]) => ({ value, label }))
export const questionDimensionOptions = Object.entries(questionDimensionLabels).map(([value, label]) => ({ value, label }))
export const questionDifficultyOptions = [1, 2, 3, 4, 5].map(value => ({ value, label: `个人难度 ${value}` }))
export const questionFieldLabels: Record<keyof QuestionPrompt, string> = {
  title: '标题', prompt: '题干', conditions: '适用条件', technology: '技术与版本', scenario: '场景',
  type: '题型', difficulty: '个人难度', dimensions: '考察维度', topicIds: '主题', knowledgeLinks: '知识引用',
  tags: '标签', source: '来源记录',
}

export function emptyQuestion(): QuestionInput {
  return {
    title: '', prompt: '', conditions: '', technology: '', scenario: '', type: 'EXPLAIN', difficulty: null,
    dimensions: ['EXPLAIN'], topicIds: [], knowledgeLinks: [], tags: [], source: { title: '', url: '', note: '' },
    referenceAnswer: '', checkpoints: [],
  }
}

export function cloneQuestion(content: QuestionInput): QuestionInput { return JSON.parse(JSON.stringify(content)) as QuestionInput }

export function questionWithReference(prompt: QuestionPrompt, reference: Pick<QuestionReference, 'referenceAnswer' | 'checkpoints'>): QuestionInput {
  return cloneQuestion({ ...prompt, referenceAnswer: reference.referenceAnswer, checkpoints: reference.checkpoints })
}

export function safeQuestionSourceUrl(value: string): string | undefined {
  if (!value.trim()) return undefined
  try {
    const url = new URL(value)
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : undefined
  } catch { return undefined }
}

// 与后端 codePointCount 一致，补充字符计一个码点，组合字符按其各码点计数。
export function questionTextLength(value: string): number { return Array.from(value).length }

export function questionProblem(value: QuestionInput): string {
  if (!value.title.trim() || questionTextLength(value.title) > 200) return '标题需填写 1–200 个字符。'
  if (!value.prompt.trim() || questionTextLength(value.prompt) > 10000) return '题干需填写 1–10000 个字符。'
  if (questionTextLength(value.conditions) > 5000 || questionTextLength(value.technology) > 2000 || questionTextLength(value.scenario) > 2000) return '适用条件最多 5000 字符，技术与版本、场景各最多 2000 字符。'
  if (!(value.type in questionTypeLabels)) return '请选择有效题型。'
  if (value.difficulty !== null && (!Number.isInteger(value.difficulty) || value.difficulty < 1 || value.difficulty > 5)) return '个人难度应为 1–5 的整数，也可以不标记。'
  if (!value.dimensions.length || value.dimensions.length > 6 || new Set(value.dimensions).size !== value.dimensions.length || value.dimensions.some(dimension => !(dimension in questionDimensionLabels))) return '请选择 1–6 个不同的考察维度。'
  if (value.topicIds.length > 20 || new Set(value.topicIds).size !== value.topicIds.length) return '最多选择 20 个不同主题。'
  if (value.knowledgeLinks.length > 20 || new Set(value.knowledgeLinks.map(link => `${link.itemId}/${link.versionId}`)).size !== value.knowledgeLinks.length) return '最多引用 20 个确切知识版本，同一对象与版本组合不能重复。'
  const tags = value.tags
  if (tags.length > 20 || tags.some(tag => !tag.trim() || questionTextLength(tag) > 40) || new Set(tags).size !== tags.length) return '最多填写 20 个不同标签，每项需为 1–40 字符。'
  if (questionTextLength(value.source.title) > 200 || questionTextLength(value.source.url) > 2000 || questionTextLength(value.source.note) > 5000) return '来源标题、网址、备注分别最多 200、2000、5000 字符。'
  if (value.source.url.trim() && !safeQuestionSourceUrl(value.source.url)) return '来源须为不含账号或密码的 HTTP(S) 网址。'
  if (questionTextLength(value.referenceAnswer) > 20000) return '个人参考最多 20000 字符。'
  if (value.checkpoints.length > 30 || new Set(value.checkpoints.map(point => point.id)).size !== value.checkpoints.length || value.checkpoints.some(point => !point.text.trim() || questionTextLength(point.text) > 500)) return '最多填写 30 个检查点，每项需为 1–500 字符且标识不重复。'
  return ''
}
