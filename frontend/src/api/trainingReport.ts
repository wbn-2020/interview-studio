import type { Schema } from './client'
import { reviewIdValid } from './review'
import type { TrainingPriorExposure } from './training'
import { trainingContextLabels, trainingFeedbackLabels, trainingModeLabels, trainingQuestionStatusLabels } from './training'

export type TrainingReport = Schema['TrainingReport']
export type TrainingReportQuestion = TrainingReport['questions'][number]
export type TrainingReportSubmissionPage = Schema['TrainingReportSubmissionPage']
export type TrainingReportSubmission = TrainingReportSubmissionPage['items'][number]
export type TrainingReportTask = TrainingReportSubmission['aiTasks'][number]

export const reportQuestionKindLabels: Record<Schema['TrainingQuestionKind'], string> = { MAIN: '主问', FOLLOW_UP: '追问', REPLAY: '同题复练' }
export const reportAnswerConditionLabels: Record<Schema['TrainingAnswerCondition'], string> = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
export const reportTaskLabels: Record<Schema['AiStatus'], string> = {
  QUEUED: '等待执行', RUNNING: '执行中', VALIDATING: '校验中', SUCCEEDED: '评价已生成',
  FAILED: '执行失败', UNKNOWN: '结果未知', CANCEL_REQUESTED: '已请求取消', CANCELED: '已取消',
}
export const reportTaskDescriptions: Record<Schema['AiStatus'], string> = {
  QUEUED: '任务等待执行，尚无可读评价。',
  RUNNING: '任务正在执行，请稍后刷新记录。',
  VALIDATING: '返回结果正在校验，尚未形成可读评价。',
  SUCCEEDED: '任务流程已完成；评价仍需结合原文核对。',
  FAILED: '本次执行未完成，原因和重试入口在任务详情。',
  UNKNOWN: '无法确认本次结果，请到任务详情核对状态与费用。',
  CANCEL_REQUESTED: '取消请求已记录，最终状态与费用仍待确认。',
  CANCELED: '本任务已取消，执行和费用记录可在任务详情核对。',
}

const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const timestamp = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
function exposureValid(value: TrainingPriorExposure): boolean {
  return Boolean(value && count(value.authoredCount) && count(value.referenceViewCount)
    && (value.evaluationAccessCount == null || count(value.evaluationAccessCount))
    && (value.lastExposureAt === null || timestamp(value.lastExposureAt)))
}

// 目录身份和计数必须一起匹配；不能把另一场训练的缓存或损坏分页绑定到当前复盘入口。
export function trainingReportValid(value: TrainingReport, sessionId: string): boolean {
  try {
    if (!value || value.sessionId !== sessionId || !reviewIdValid(value.sessionId) || typeof value.title !== 'string'
      || !count(value.sessionRevision) || value.sessionRevision < 1 || !Object.hasOwn(trainingModeLabels, value.mode)
      || (value.startedAt !== null && !timestamp(value.startedAt)) || !timestamp(value.endedAt) || !timestamp(value.asOf)
      || !value.config || !Object.hasOwn(trainingFeedbackLabels, value.config.feedbackTiming)
      || !Array.isArray(value.config.stages) || value.config.stages.some(stage => typeof stage !== 'string')
      || !count(value.config.followUpLimit)
      || (value.config.timeBudgetMinutes !== null && (!count(value.config.timeBudgetMinutes) || value.config.timeBudgetMinutes < 1))
      || !value.timing || ![value.timing.observedSeconds, value.timing.unobservedSeconds, value.timing.pausedSeconds,
        value.timing.pendingUnknownSeconds, value.timing.pendingPausedSeconds].every(count)
      || value.timing.pendingUnknownSeconds !== 0 || value.timing.pendingPausedSeconds !== 0
      || typeof value.timing.budgetReached !== 'boolean' || !timestamp(value.timing.asOf)
      || !value.endChecklist || ![value.endChecklist.answered, value.endChecklist.skipped, value.endChecklist.unanswered, value.endChecklist.unsubmittedDrafts].every(count)
      || !Array.isArray(value.context) || value.context.length > 12 || value.context.some(item => !item || !Object.hasOwn(trainingContextLabels, item.kind)
        || !reviewIdValid(item.itemId) || !reviewIdValid(item.versionId) || typeof item.title !== 'string' || !count(item.number) || item.number < 1)
      || !Array.isArray(value.questions) || value.questions.length < 1 || value.questions.length > 60 || value.questions.some(item => !item || !reviewIdValid(item.questionId)
        || typeof item.title !== 'string' || typeof item.stage !== 'string' || !Object.hasOwn(reportQuestionKindLabels, item.kind)
        || !Object.hasOwn(trainingQuestionStatusLabels, item.status) || !count(item.answerCount) || item.answerCount > 100
        || typeof item.hasDraft !== 'boolean' || !exposureValid(item.priorExposure)
        || (item.answerCount === 0 ? item.latestAnswerId !== null : !reviewIdValid(item.latestAnswerId)))
      || new Set(value.questions.map(item => item.questionId)).size !== value.questions.length) return false
    const counts = value.counts
    return Boolean(counts && [counts.submittedQuestionCount, counts.submissionCount, counts.noSubmissionCount, counts.draftQuestionCount].every(count)
      && counts.submittedQuestionCount === value.questions.filter(item => item.answerCount > 0).length
      && counts.submissionCount === value.questions.reduce((total, item) => total + item.answerCount, 0)
      && counts.noSubmissionCount === value.questions.filter(item => item.answerCount === 0).length
      && counts.draftQuestionCount === value.questions.filter(item => item.hasDraft).length)
  } catch { return false }
}

export function trainingReportPageValid(value: TrainingReportSubmissionPage, sessionId: string, questionId: string, before?: number): boolean {
  try {
    if (!value || value.sessionId !== sessionId || value.questionId !== questionId || !reviewIdValid(value.sessionId)
      || !reviewIdValid(value.questionId) || typeof value.questionTitle !== 'string' || !timestamp(value.asOf)
      || !Array.isArray(value.items) || value.items.length > 20
      || value.items.some((item, index) => !item || !reviewIdValid(item.answerId) || !count(item.number) || item.number < 1 || item.number > 100
        || item.number >= (index ? value.items[index - 1]!.number : before ?? 101)
        || !timestamp(item.createdAt) || !Object.hasOwn(reportAnswerConditionLabels, item.condition) || !exposureValid(item.priorExposure)
        || typeof item.accessAllowed !== 'boolean'
        || ![item.selfCheckCount, item.externalFeedbackCount, item.actionSourceCount, item.trackingSourceCount].every(count)
        || item.trackingSourceCount > item.actionSourceCount || !Array.isArray(item.aiTasks) || item.aiTasks.length > 3
        || item.aiTasks.some(task => !task || !reviewIdValid(task.id) || !Object.hasOwn(reportTaskLabels, task.status)
          || !count(task.revision) || !timestamp(task.updatedAt)
          || (task.errorCode !== null && typeof task.errorCode !== 'string'))
        || new Set(item.aiTasks.map(task => task.id)).size !== item.aiTasks.length)
      || new Set(value.items.map(item => item.answerId)).size !== value.items.length) return false
    return value.nextBefore === null || (value.items.length > 0 && count(value.nextBefore) && value.nextBefore > 1
      && value.nextBefore === value.items[value.items.length - 1]!.number && value.nextBefore < (before ?? 101))
  } catch { return false }
}
