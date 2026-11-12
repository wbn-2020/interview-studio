import { read } from './client'
import type { Schema } from './client'

export type CollectionKind = Schema['BankCollectionKind']
export type CollectionStatus = Schema['BankStatus']
export type CollectionReferenceState = Schema['BankReferenceState']
export type CollectionStep = Schema['BankStep']
export type CollectionInput = Schema['BankCollectionInput']
export type CollectionVersion = Schema['BankCollectionVersion']
export type CollectionQuestionReference = Schema['BankQuestionReference']
export type Collection = Schema['BankCollection']
export type CollectionSummary = Schema['BankCollectionSummary']
export type CollectionPage = Schema['BankCollectionPage']
export type CollectionVersionSummary = Schema['BankVersionSummary']
export type CollectionVersionPage = Schema['BankCollectionVersionPage']
export type QuestionType = Schema['BankQuestionType']
export type QuestionSummary = Schema['BankQuestionSummary']
export type QuestionPage = Schema['BankQuestionPage']
export type CollectionQuestionDetail = Schema['BankQuestion']
export type CollectionQuestionVersion = Schema['BankQuestionVersion']

export type CollectionQuestionChoice = {
  id: string
  title: string
  type: QuestionType
  versionId: string
  versionNumber: number
  difficulty: number | null
}

export const collectionKindLabels: Record<CollectionKind, string> = {
  LIST: '题单',
  ROUTE: '学习路线',
}

export const collectionStatusLabels: Record<CollectionStatus, string> = {
  ACTIVE: '使用中',
  ARCHIVED: '已归档',
}

export const collectionReferenceStateLabels: Record<CollectionReferenceState, string> = {
  CURRENT: '题目当前版本',
  SUPERSEDED: '题目已改版',
  ARCHIVED: '题目已归档',
}

export const questionTypeLabels: Record<QuestionType, string> = {
  EXPLAIN: '原理解释',
  CODE_READING: '代码阅读',
  DEBUG: '排错',
  PROJECT: '项目经历',
  TRADEOFF: '技术取舍',
  SYSTEM_DESIGN: '系统设计',
  BEHAVIOR: '行为表达',
}

export function emptyCollection(): CollectionInput {
  return { title: '', purpose: '', steps: [] }
}

export function cloneCollectionContent(content: CollectionInput): CollectionInput {
  return JSON.parse(JSON.stringify(content)) as CollectionInput
}

function textProblem(value: string, max: number, label: string, required = false): string {
  if (required && !value.trim()) return `${label}不能为空。`
  if (value.length > max) return `${label}不能超过 ${max} 个字符。`
  return ''
}

/**
 * 前端先给出可操作的校验提示；服务端仍是最终裁决者，尤其要重新验证题目版本和并发修订号。
 */
export function collectionProblem(kind: CollectionKind, content: CollectionInput): string {
  let problem = textProblem(content.title, 200, '标题', true)
  if (problem) return problem
  problem = textProblem(content.purpose, 5000, '用途说明')
  if (problem) return problem
  if (content.steps.length > 50) return '一个题单或学习路线最多包含 50 个步骤。'

  const questionIds = new Set<string>()
  const stepIds = new Set<string>()
  for (const step of content.steps) {
    if (!step.id || stepIds.has(step.id)) return '步骤 ID 必须稳定且不能重复。'
    stepIds.add(step.id)
    if (!step.questionId || !step.questionVersionId) return '每个步骤必须固定一条题目及其版本。'
    if (questionIds.has(step.questionId)) return '同一集合不能重复添加同一题目；同一道题可以出现在不同集合中。'
    questionIds.add(step.questionId)
    problem = textProblem(step.purpose, 1000, '步骤目的')
    if (problem) return problem
    problem = textProblem(step.checkpoint, 1000, '检查点')
    if (problem) return problem
    if (new Set(step.dependsOn).size !== step.dependsOn.length) return '同一步骤不能重复填写同一个前置步骤。'
    if (kind === 'LIST' && step.dependsOn.length) return '题单不设置前置依赖；需要依赖关系时请选择学习路线。'
    if (step.dependsOn.includes(step.id)) return '步骤不能依赖自身。'
  }
  for (const step of content.steps) {
    if (step.dependsOn.some(id => !stepIds.has(id))) return '前置步骤必须来自同一版本中的其他步骤。'
  }
  if (kind === 'ROUTE' && hasDependencyCycle(content.steps)) return '前置关系形成了循环，请调整依赖后再保存。'
  return ''
}

export function hasDependencyCycle(steps: CollectionStep[]): boolean {
  const edges = new Map(steps.map(step => [step.id, step.dependsOn]))
  const visiting = new Set<string>()
  const visited = new Set<string>()
  function visit(id: string): boolean {
    if (visiting.has(id)) return true
    if (visited.has(id)) return false
    visiting.add(id)
    for (const dependency of edges.get(id) ?? []) {
      if (visit(dependency)) return true
    }
    visiting.delete(id)
    visited.add(id)
    return false
  }
  return steps.some(step => visit(step.id))
}

export async function readQuestionPage(parameters: URLSearchParams): Promise<QuestionPage> {
  return read<QuestionPage>(`/bank/questions?${parameters.toString()}`)
}
