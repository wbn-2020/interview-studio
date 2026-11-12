import type { Schema } from './client'

export type TrainingMode = Schema['TrainingMode']
export type TrainingStatus = Schema['TrainingStatus']
export type TrainingFeedbackTiming = Schema['TrainingFeedbackTiming']
export type TrainingContextKind = Schema['TrainingContextKind']
export type TrainingCreateSession = Schema['TrainingCreateSession']
export type TrainingQuestionSelection = Schema['TrainingQuestionSelection']
export type TrainingContextLink = Schema['TrainingContextLink']
export type TrainingSession = Schema['TrainingSession']
export type TrainingSessionSummary = Schema['TrainingSessionSummary']
export type TrainingSessionPage = Schema['TrainingSessionPage']
export type TrainingQuestion = Schema['TrainingQuestion']
export type TrainingQuestionSummary = Schema['TrainingQuestionSummary']
export type TrainingContextSnapshot = Schema['TrainingContextSnapshot']
export type TrainingContextCheck = Schema['TrainingContextCheck']
export type TrainingContextState = Schema['PrepareSourceState']
export type TrainingTiming = Schema['TrainingTiming']
export type TrainingEndChecklist = Schema['TrainingEndChecklist']
export type TrainingEventPage = Schema['TrainingEventPage']
export type TrainingAnswerInput = Schema['TrainingAnswerInput']
export type TrainingAnswerContent = Schema['TrainingAnswerContent']
export type TrainingAnswerCondition = Schema['TrainingAnswerCondition']
export type TrainingAnswer = Schema['TrainingAnswer']
export type TrainingAnswerPage = Schema['TrainingAnswerPage']
export type TrainingDiagramInput = Schema['TrainingDiagramInput']
export type TrainingDiagram = Schema['TrainingDiagram']
export type TrainingDiagramLink = Schema['TrainingDiagramLink']
export type TrainingDiagramReference = Schema['TrainingDiagramReference']
export type TrainingDiagramNodeInput = Schema['TrainingDiagramNodeInput']
export type TrainingDiagramNode = Schema['TrainingDiagramNode']
export type TrainingDiagramEdge = Schema['TrainingDiagramEdge']
export type TrainingBoardSource = Schema['TrainingBoardSource']
export type TrainingBoardImportCandidate = Schema['TrainingBoardImportCandidate']
export type TrainingDraft = Schema['TrainingDraft']
export type TrainingQuestionSnapshot = Schema['TrainingQuestionSnapshot']
export type TrainingPriorExposure = Schema['TrainingPriorExposure']
export type TrainingEvent = Schema['TrainingEvent']
export type TrainingFollowUpOrigin = Schema['TrainingFollowUpOrigin']

export const trainingModeLabels: Record<TrainingMode, string> = { LEARNING: '学习练习', FOCUSED: '独立专项', MOCK: '文字模拟' }
export const trainingStatusLabels: Record<TrainingStatus, string> = { DRAFT: '待确认预览', RUNNING: '进行中', PAUSED: '已暂停', ENDED: '已结束' }
export const trainingFeedbackLabels: Record<TrainingFeedbackTiming, string> = { AFTER_ANSWER: '每题提交后', AFTER_SESSION: '训练结束后' }
export const trainingContextLabels: Record<TrainingContextKind, string> = { TARGET: '岗位目标', PROJECT: '项目背景', FACT: '项目事实' }
export const trainingContextStateLabels: Record<TrainingContextState, string> = {
  CURRENT: '当前可用于新追问', SUPERSEDED: '来源已改版', WITHDRAWN: '当前事实已撤回核实',
  ARCHIVED: '来源已归档', PROJECT_ARCHIVED: '所属项目已归档', UNVERIFIED: '当前事实待核实', UNAVAILABLE: '来源不可用',
}
export const trainingQuestionStatusLabels: Record<TrainingQuestionSummary['status'], string> = { PENDING: '待答', ANSWERED: '已提交', SKIPPED: '已跳过' }
export const trainingModeOptions = Object.entries(trainingModeLabels).map(([value, label]) => ({ value, label }))
export const trainingFeedbackOptions = Object.entries(trainingFeedbackLabels).map(([value, label]) => ({ value, label }))

export function trainingContextFingerprint(checks: readonly TrainingContextCheck[] | null | undefined): string | null {
  if (checks == null) return null
  return JSON.stringify(checks.map(check => [check.kind, check.itemId, check.versionId, check.title, check.number,
    check.state, check.sourceGeneration, check.projectGeneration]))
}

export function emptyTrainingSetup(): TrainingCreateSession {
  return { title: '', mode: 'FOCUSED', feedbackTiming: 'AFTER_ANSWER', timeBudgetMinutes: null, followUpLimit: 0, stages: ['主问'], questions: [], contextLinks: [], collectionId: null, collectionVersionId: null }
}

export function trainingSetupProblem(value: TrainingCreateSession): string {
  if (!value.title.trim() || value.title.length > 200) return '请填写 1–200 个字符的训练名称。'
  if (!value.questions.length || value.questions.length > 20) return '请选择 1–20 道主问题。'
  if (new Set(value.questions.map(question => question.sourceQuestionId)).size !== value.questions.length) return '同一道题在一次训练中只出现一次。'
  if (!value.stages.length || value.stages.length > 8 || value.stages.some(stage => !stage.trim() || stage.length > 80) || new Set(value.stages).size !== value.stages.length) return '请填写 1–8 个不同阶段，每个名称为 1–80 字符。'
  if (value.questions.some(question => !value.stages.includes(question.stage))) return '每道题都需要选择一个已定义的阶段。'
  if (value.timeBudgetMinutes !== null && (!Number.isInteger(value.timeBudgetMinutes) || value.timeBudgetMinutes < 1 || value.timeBudgetMinutes > 240)) return '时间预算应为空或 1–240 的整数分钟。'
  if (!Number.isInteger(value.followUpLimit) || value.followUpLimit < 0 || value.followUpLimit > 2) return '后续追问额度应为 0–2。'
  if (value.contextLinks.filter(link => link.kind === 'TARGET').length > 1 || value.contextLinks.filter(link => link.kind === 'PROJECT').length > 1 || value.contextLinks.filter(link => link.kind === 'FACT').length > 10) return '最多选择 1 个目标、1 个项目和 10 条已核实事实。'
  return ''
}

export function formatTrainingDuration(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(whole / 3600)
  const minutes = Math.floor((whole % 3600) / 60)
  return hours ? `${hours} 小时 ${minutes} 分` : `${minutes} 分 ${whole % 60} 秒`
}

export function notifyTrainingChanged() {
  window.dispatchEvent(new Event('studio:training-changed'))
}
