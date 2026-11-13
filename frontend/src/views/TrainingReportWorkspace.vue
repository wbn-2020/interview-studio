<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import { queryClient } from '../api/queryClient'
import { reviewDate, reviewIdValid } from '../api/review'
import { formatTrainingDuration, trainingContextLabels, trainingFeedbackLabels, trainingModeLabels, trainingQuestionStatusLabels } from '../api/training'
import type { TrainingReport, TrainingReportSubmissionPage } from '../api/trainingReport'
import { reportAnswerConditionLabels, reportQuestionKindLabels, reportTaskDescriptions, reportTaskLabels, trainingReportPageValid, trainingReportValid } from '../api/trainingReport'
import { useRecentVisit } from '../composables/useRecentVisit'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import RIcon from '../replica/RIcon.vue'

const route = useRoute(), router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore()
const routeText = (key: string) => typeof route.query[key] === 'string' ? route.query[key] as string : undefined
const sessionId = computed(() => routeText('sessionId')), questionId = computed(() => routeText('questionId'))
const routeProblem = computed(() => {
  if (route.query.sessionId === undefined) return '请从一场已结束的训练打开报告。'
  if (!reviewIdValid(route.query.sessionId)) return '训练标识无效，请从训练历史重新打开报告。'
  if (route.query.questionId !== undefined && !reviewIdValid(route.query.questionId)) return '题目标识无效，请从这场训练重新打开报告。'
  return ''
})
const readable = computed(() => store.sessionActive && !access.checking && !access.challenge && !access.restricted && !store.changes.get('training-access-command')?.locked)
const recordRecentVisit = useRecentVisit('TRAINING', sessionId, readable)
const before = ref<number>(), previous = ref<(number | undefined)[]>([])
const navigating = ref(false), message = ref('')
const acceptedReport = shallowRef<{ epoch: number; value: TrainingReport }>()
const acceptedPage = shallowRef<{ epoch: number; before: number | undefined; value: TrainingReportSubmissionPage }>()
let alive = true, generation = 0
watch([readable, () => store.sessionEpoch], () => { generation++ }, { flush: 'sync' })
watch([sessionId, questionId, () => store.sessionEpoch], () => { before.value = undefined; previous.value = []; message.value = '' }, { flush: 'sync' })

const reportKey = computed(() => ['training-report', store.sessionEpoch, 'session', sessionId.value])
const reportQuery = useQuery({
  queryKey: reportKey, enabled: computed(() => readable.value && !routeProblem.value),
  queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, id = sessionId.value!
    const value = await read<TrainingReport>(`/review/sessions/${id}/report`)
    requireCurrent(epoch, stamp)
    if (id !== sessionId.value || !trainingReportValid(value, id)) throw new ApiError(502, 'REVIEW_REPORT_MISMATCH', '结束报告与所选训练或提交计数不一致。')
    return value
  },
})
const report = computed(() => readable.value && !routeProblem.value && acceptedReport.value?.epoch === store.sessionEpoch
  && acceptedReport.value.value.sessionId === sessionId.value ? acceptedReport.value.value : undefined)
const selectedQuestion = computed(() => report.value?.questions.find(item => item.questionId === questionId.value))
const reviewEntry = computed(() => report.value?.questions.find(item => item.latestAnswerId !== null))
const unknownQuestion = computed(() => Boolean(report.value && questionId.value && !selectedQuestion.value))
const pageKey = computed(() => ['training-report', store.sessionEpoch, 'submissions', sessionId.value, questionId.value, before.value])
const pageQuery = useQuery({
  queryKey: pageKey, enabled: computed(() => readable.value && !routeProblem.value && Boolean(questionId.value) && !unknownQuestion.value),
  queryFn: async () => {
    const epoch = store.sessionEpoch, stamp = generation, sid = sessionId.value!, qid = questionId.value!, cursor = before.value
    const value = await read<TrainingReportSubmissionPage>(`/review/sessions/${sid}/questions/${qid}/report?limit=10${cursor === undefined ? '' : `&before=${cursor}`}`)
    requireCurrent(epoch, stamp)
    if (sid !== sessionId.value || qid !== questionId.value || cursor !== before.value) throw changedContext()
    if (!trainingReportPageValid(value, sid, qid, cursor)) throw new ApiError(502, 'REVIEW_REPORT_PAGE_MISMATCH', '提交目录与所选题目或分页不一致。')
    return value
  },
})
const page = computed(() => readable.value && !routeProblem.value && !unknownQuestion.value && acceptedPage.value?.epoch === store.sessionEpoch
  && acceptedPage.value.before === before.value && acceptedPage.value.value.sessionId === sessionId.value
  && acceptedPage.value.value.questionId === questionId.value ? acceptedPage.value.value : undefined)

// 权限恢复和缓存复用未必改变 data 引用；从当前 epoch 与确切身份的缓存恢复已读目录。
watch([reportQuery.data, reportQuery.dataUpdatedAt, readable, sessionId, () => store.sessionEpoch], ([, , canRead, id, epoch]) => {
  if (!alive || !canRead || routeProblem.value || !id) return
  const value = queryClient.getQueryData<TrainingReport>(['training-report', epoch, 'session', id])
  if (value && trainingReportValid(value, id)) {
    acceptedReport.value = { epoch, value }
    // 全场报告沿训练会话身份记录访问，单题提交分页独立接纳。
    recordRecentVisit(value.sessionId)
  }
}, { immediate: true })
watch([pageQuery.data, pageQuery.dataUpdatedAt, readable, sessionId, questionId, before, () => store.sessionEpoch], ([, , canRead, sid, qid, cursor, epoch]) => {
  if (!alive || !canRead || routeProblem.value || !sid || !qid) return
  const value = queryClient.getQueryData<TrainingReportSubmissionPage>(['training-report', epoch, 'submissions', sid, qid, cursor])
  if (value && trainingReportPageValid(value, sid, qid, cursor)) acceptedPage.value = { epoch, before: cursor, value }
}, { immediate: true })
onUnmounted(() => { alive = false; generation++ })
function changedContext() { return new ApiError(0, 'REVIEW_REPORT_CONTEXT_CHANGED', '报告的训练、题目或访问条件已变化，请重新读取。') }
function requireCurrent(epoch: number, stamp: number) {
  if (!alive || !readable.value || epoch !== store.sessionEpoch || stamp !== generation || routeProblem.value) throw changedContext()
}
function readError(error: unknown) {
  if (error instanceof ApiError && error.code === 'CONNECTION_UNCERTAIN') return '读取中断或超时。可以重新读取，已保存的训练记录不受影响。'
  return errorText(error)
}
async function selectQuestion(id: string) {
  if (!readable.value || navigating.value || id === questionId.value || !report.value?.questions.some(item => item.questionId === id)) return
  navigating.value = true; message.value = ''
  try {
    // 选中状态由成功导航后的路由决定；守卫拒绝时保留当前题目和分页。
    const failure = await router.push({ name: 'training-report', query: { sessionId: sessionId.value, questionId: id } })
    if (alive && failure) message.value = '题目切换未完成，仍保留当前选择。请先处理页面提示后重试。'
  } catch (error) { if (alive) message.value = errorText(error) }
  finally { if (alive) navigating.value = false }
}
function nextPage() {
  if (!readable.value || pageQuery.isFetching.value || pageQuery.error.value || page.value?.nextBefore == null) return
  previous.value.push(before.value); before.value = page.value.nextBefore
}
function previousPage() { if (readable.value && !pageQuery.isFetching.value && previous.value.length) before.value = previous.value.pop() }
function exportSummary() {
  if (!readable.value || !report.value) return
  const value = report.value
  const text = [`# ${value.title}`, '', `结束时间：${reviewDate(value.endedAt)}`,
    `正式提交 ${value.counts.submittedQuestionCount} 题，跳过 ${value.endChecklist.skipped} 题，保留草稿 ${value.counts.draftQuestionCount} 题。`, '',
    ...value.questions.map((question, index) => `${index + 1}. ${question.title} · ${trainingQuestionStatusLabels[question.status]} · ${question.answerCount} 次提交${question.hasDraft ? ' · 保留草稿' : ''}`)].join('\n')
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }))
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = `training-${value.sessionId}-summary.md`; anchor.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <header class="page-heading">
    <div><h1>本次训练</h1><p class="muted">{{ report ? `${trainingModeLabels[report.mode]} · ${reviewDate(report.endedAt)}` : '训练结束报告' }}</p></div>
    <nav class="row-actions" aria-label="报告导航">
      <RouterLink v-if="reviewIdValid(sessionId)" class="text-link" :to="{ name: 'training', query: { item: sessionId } }">返回这场训练</RouterLink>
      <RouterLink class="report-return" :to="{ name: 'today' }"><RIcon name="arrow-left" />返回今日</RouterLink>
      <RouterLink v-if="reviewEntry" class="report-primary" :to="{ name: 'review', query: { sessionId, questionId: reviewEntry.questionId, answerId: reviewEntry.latestAnswerId } }"><RIcon name="play" />开始复盘</RouterLink>
    </nav>
  </header>
  <section class="report-workspace" aria-label="训练结束报告内容">
    <p v-if="!readable" class="notice" role="status">正在核对登录与资料访问条件。报告暂不可读，条件恢复后会读取当前训练的记录。</p>
    <p v-if="routeProblem" class="notice error" role="alert">{{ routeProblem }} <RouterLink class="text-link" :to="{ name: 'training' }">查看训练历史</RouterLink></p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <template v-if="readable && !routeProblem">
      <p v-if="reportQuery.isPending.value && !report" class="loading-copy" role="status">正在读取结束事实与题目目录…</p>
      <div v-if="reportQuery.error.value" class="notice error" role="alert">
        {{ readError(reportQuery.error.value) }}
        <span v-if="report">下方保留上次成功读取的全场目录。</span>
        <NButton text size="small" :disabled="!readable || reportQuery.isFetching.value" @click="reportQuery.refetch()">重新读取全场目录</NButton>
      </div>
      <section v-if="report" class="report-overview" aria-label="结束事实">
        <details class="report-record-identity"><summary>训练记录与统计口径</summary>
        <header class="section-heading">
          <div><h2>{{ report.title }}</h2><p class="field-help">{{ trainingModeLabels[report.mode] }} · 已结束 · 训练记录修订 {{ report.sessionRevision }}</p></div>
          <NButton size="small" :loading="reportQuery.isFetching.value" :disabled="!readable" @click="reportQuery.refetch()">刷新全场目录</NButton>
        </header>
        <p class="report-dates">{{ report.startedAt ? `${reviewDate(report.startedAt)} 开始` : '未正式开始，已结束预览' }} · {{ reviewDate(report.endedAt) }} 结束</p>
        <p class="field-help">正式提交总次数 {{ report.counts.submissionCount }}；没有正式提交 {{ report.counts.noSubmissionCount }} 题。</p>
        <p class="field-help">提交后再跳过仍计入正式提交；草稿独立保留，不计入提交次数。</p>
        <p v-if="report.counts.submissionCount === 0" class="notice">本场没有正式提交。已保存的草稿仍在训练记录中，没有可用于提交复盘或新旧对照的答案。</p>
        <details class="session-facts">
          <summary>结束清单、计时与训练配置</summary>
          <dl class="detail-facts">
            <div><dt>结束时的题目状态</dt><dd>已答 {{ report.endChecklist.answered }} · 跳过 {{ report.endChecklist.skipped }} · 未答 {{ report.endChecklist.unanswered }} · 未提交草稿 {{ report.endChecklist.unsubmittedDrafts }}</dd></div>
            <div><dt>服务器观察到的整场区间</dt><dd>{{ formatTrainingDuration(report.timing.observedSeconds) }}</dd></div>
            <div><dt>整场未观察区间</dt><dd>{{ formatTrainingDuration(report.timing.unobservedSeconds + report.timing.pendingUnknownSeconds) }}</dd></div>
            <div><dt>整场暂停区间</dt><dd>{{ formatTrainingDuration(report.timing.pausedSeconds + report.timing.pendingPausedSeconds) }}</dd></div>
            <div><dt>时间预算</dt><dd>{{ report.config.timeBudgetMinutes === null ? '未设置' : `${report.config.timeBudgetMinutes} 分钟` }}{{ report.timing.budgetReached ? ' · 已到预算提醒点' : '' }}</dd></div>
            <div><dt>反馈时机</dt><dd>{{ trainingFeedbackLabels[report.config.feedbackTiming] }}</dd></div>
            <div><dt>训练阶段</dt><dd>{{ report.config.stages.join(' → ') }}</dd></div>
            <div><dt>每个根主问的追问额度</dt><dd>{{ report.config.followUpLimit }} 次</dd></div>
          </dl>
          <p class="field-help">计时覆盖整场训练，不表示单题净作答时间、专注度或是否使用辅助。结束清单保留当时状态，不代替实际提交计数。</p>
        </details>
        <details class="session-facts">
          <summary>固定背景身份 · {{ report.context.length }} 项</summary>
          <ul v-if="report.context.length" class="context-identities"><li v-for="context in report.context" :key="`${context.kind}/${context.versionId}`">{{ trainingContextLabels[context.kind] }} · {{ context.title }} · 当时版本 v{{ context.number }}</li></ul>
          <p v-else class="field-help">这次训练没有选择岗位、项目或事实背景。</p>
          <p class="field-help">这里只列出当时选入的资料身份。背景原文在训练记录中按访问条件读取。</p>
        </details>
        <p class="as-of">全场目录读取于 {{ reviewDate(report.asOf) }}</p>
        </details>
        <dl class="report-facts">
          <div><RIcon name="circle-check" :size="52" /><span><dt>已提交</dt><dd>{{ report.counts.submittedQuestionCount }} <small>题</small></dd></span></div>
          <div><RIcon name="skip-forward" :size="52" /><span><dt>跳过</dt><dd>{{ report.endChecklist.skipped }} <small>题</small></dd></span></div>
          <div><RIcon name="file-text" :size="52" /><span><dt>未提交草稿</dt><dd>{{ report.counts.draftQuestionCount }} <small>题</small></dd></span></div>
          <div class="report-note"><RIcon name="info" :size="22" /><span>不把缺答算作失败</span></div>
        </dl>
      </section>

      <div class="report-columns">
        <div class="report-main">
        <aside class="question-directory" aria-labelledby="report-questions-heading">
          <h2 id="report-questions-heading"><RIcon name="list" />题目报告</h2>
          <p v-if="!report" class="field-help">全场目录读取成功后会列出题目；已选题目的提交仍可单独读取。</p>
          <button v-for="(question, index) in report?.questions ?? []" :key="question.questionId" type="button" class="question-row"
            :class="{ selected: questionId === question.questionId }" :aria-current="questionId === question.questionId ? 'true' : undefined"
            :disabled="!readable || navigating" @click="selectQuestion(question.questionId)">
            <span class="question-number">题目 {{ String(index + 1).padStart(2, '0') }}</span>
            <strong>{{ question.title }}</strong>
            <span class="question-state">{{ trainingQuestionStatusLabels[question.status] }} · {{ question.answerCount }} 次提交{{ question.hasDraft ? ' · 保留草稿' : '' }}</span><RIcon name="chevron-down" :size="23" />
          </button>
        </aside>

        <section v-if="questionId" class="submission-directory" aria-labelledby="report-submissions-heading">
          <header class="section-heading">
            <div><h2 id="report-submissions-heading">{{ page?.questionTitle || selectedQuestion?.title || '提交与复盘记录' }}</h2><p class="field-help">按提交版本从新到旧排列；每份提交各自保留作答条件。</p></div>
            <NButton v-if="questionId && !unknownQuestion" size="small" :loading="pageQuery.isFetching.value" :disabled="!readable" @click="pageQuery.refetch()">刷新本页记录</NButton>
          </header>
          <p v-if="!questionId" class="empty-copy">先从题目目录选一题。这里会列出它的全部正式提交，可逐页查看早期版本。</p>
          <p v-else-if="unknownQuestion" class="notice error" role="alert">这道题不属于当前训练，请从题目目录重新选择。</p>
          <template v-else>
            <p v-if="selectedQuestion" class="field-help">{{ reportQuestionKindLabels[selectedQuestion.kind] }} · 结束状态：{{ trainingQuestionStatusLabels[selectedQuestion.status] }} · 共 {{ selectedQuestion.answerCount }} 次提交{{ selectedQuestion.hasDraft ? ' · 保留未提交草稿' : '' }}</p>
            <p v-if="selectedQuestion" class="field-help">开始前站内接触记录：创作 {{ selectedQuestion.priorExposure.authoredCount }} 次、打开参考 {{ selectedQuestion.priorExposure.referenceViewCount }} 次、打开评价区 {{ selectedQuestion.priorExposure.evaluationAccessCount ?? 0 }} 次{{ selectedQuestion.priorExposure.lastExposureAt ? `，最近记录于 ${reviewDate(selectedQuestion.priorExposure.lastExposureAt)}` : '' }}。</p>
            <p v-if="pageQuery.isPending.value && !page" class="loading-copy" role="status">正在读取这道题的提交目录…</p>
            <div v-if="pageQuery.error.value" class="notice error" role="alert">
              {{ readError(pageQuery.error.value) }} <span v-if="page">下方保留本页上次成功读取的记录。</span>
              <NButton text size="small" :disabled="!readable || pageQuery.isFetching.value" @click="pageQuery.refetch()">重新读取本页</NButton>
            </div>
            <template v-if="page">
              <p class="as-of">本页反馈、动作来源与 AI 状态读取于 {{ reviewDate(page.asOf) }}，可能与全场目录的读取时间不同。</p>
              <p v-if="!page.items.length" class="empty-copy">{{ before === undefined ? '这道题没有正式提交。已保存的草稿可返回训练查看，不会自动成为复盘答案。' : '这一页没有更早的提交，可返回上一页。' }}</p>
              <article v-for="submission in page.items" :key="submission.answerId" class="submission-row">
                <header class="section-heading">
                  <div><h3>第 {{ submission.number }} 版提交</h3><p class="field-help">{{ reportAnswerConditionLabels[submission.condition] }} · {{ reviewDate(submission.createdAt) }}</p></div>
                  <RouterLink class="text-link" :to="{ name: 'review', query: { sessionId: page.sessionId, questionId: page.questionId, answerId: submission.answerId } }">复盘本次提交</RouterLink>
                </header>
                <p class="field-help">本次保存的站内接触记录：创作 {{ submission.priorExposure.authoredCount }} 次、打开参考 {{ submission.priorExposure.referenceViewCount }} 次、打开评价区 {{ submission.priorExposure.evaluationAccessCount ?? 0 }} 次{{ submission.priorExposure.lastExposureAt ? `，最近记录于 ${reviewDate(submission.priorExposure.lastExposureAt)}` : '' }}。</p>
                <p class="field-help">{{ submission.accessAllowed ? '已记录本次提交的评价区访问确认。' : '本次提交尚未确认评价区访问，进入复盘后按提示确认。' }}</p>
                <dl class="submission-counts">
                  <div><dt>当前自检条目</dt><dd>{{ submission.selfCheckCount }}</dd></div>
                  <div><dt>当前外部意见条目</dt><dd>{{ submission.externalFeedbackCount }}</dd></div>
                  <div><dt>改进动作来源</dt><dd>{{ submission.actionSourceCount }}</dd></div>
                  <div><dt>其中仍在跟踪</dt><dd>{{ submission.trackingSourceCount }}</dd></div>
                </dl>
                <div class="evaluation-records">
                  <h4>AI 评价记录</h4>
                  <p v-if="!submission.aiTasks.length" class="field-help">尚未生成 AI 评价。仍可进入本次提交，进行自检或记录外部意见。</p>
                  <ul v-else class="task-list">
                    <li v-for="task in submission.aiTasks" :key="task.id">
                      <div><strong>{{ reportTaskLabels[task.status] }}</strong><p>{{ reportTaskDescriptions[task.status] }}</p><time>{{ reviewDate(task.updatedAt) }} 更新</time></div>
                      <div class="task-links">
                        <RouterLink v-if="task.status === 'SUCCEEDED'" class="text-link" :to="{ name: 'review', query: { sessionId: page.sessionId, questionId: page.questionId, answerId: submission.answerId, taskId: task.id } }">阅读评价</RouterLink>
                        <RouterLink class="text-link" :to="{ name: 'ai', query: { item: task.id } }">任务与费用记录</RouterLink>
                      </div>
                    </li>
                  </ul>
                </div>
              </article>
            </template>
            <nav v-if="previous.length || page?.nextBefore != null" class="row-actions submission-pagination" aria-label="提交版本分页">
              <NButton size="small" :disabled="!readable || pageQuery.isFetching.value || !previous.length" @click="previousPage">上一页</NButton>
              <span class="field-help">第 {{ previous.length + 1 }} 页</span>
              <NButton size="small" :disabled="!readable || pageQuery.isFetching.value || !!pageQuery.error.value || page?.nextBefore == null" @click="nextPage">更早提交</NButton>
            </nav>
          </template>
        </section>
        </div>
        <aside v-if="report" class="report-end-conditions" aria-label="结束条件">
          <section><h2><RIcon name="flag" />结束条件</h2><dl>
            <div><dt><RIcon name="clock" />已观察区间</dt><dd>{{ formatTrainingDuration(report.timing.observedSeconds) }}</dd></div>
            <div><dt><RIcon name="pause" />暂停区间</dt><dd>{{ formatTrainingDuration(report.timing.pausedSeconds) }}</dd></div>
            <div><dt><RIcon name="eye" />反馈时机</dt><dd>{{ trainingFeedbackLabels[report.config.feedbackTiming] }}</dd></div>
            <div><dt><RIcon name="timer" />时间预算</dt><dd>{{ report.config.timeBudgetMinutes === null ? '未设置' : `${report.config.timeBudgetMinutes} 分钟` }}</dd></div>
          </dl><p class="field-help">服务器区间含观察间隔，不代表单题净作答时间。</p></section>
          <section><h2><RIcon name="robot" />AI 评价</h2><p v-if="!page" class="muted">选择题目，查看对应提交的评价记录。</p><template v-else><p v-for="task in page.items.flatMap(item => item.aiTasks)" :key="task.id"><RouterLink :to="{ name: 'ai', query: { item: task.id } }">{{ reportTaskLabels[task.status] }}</RouterLink></p><p v-if="!page.items.some(item => item.aiTasks.length)" class="muted">当前页提交尚无 AI 评价。</p></template></section>
          <NButton round type="primary" :disabled="!readable" @click="exportSummary"><RIcon name="download" />导出摘要</NButton>
        </aside>
      </div>
      <p class="report-footnote">人工反馈按当前条目计数，记录存在不代表已接受。动作按来源计数，停止跟踪不代表完成。AI 执行状态不代表能力结论；提交原文、反馈依据和逐来源判断在对应复盘入口读取，仍受当前资料访问条件限制。</p>
    </template>
  </section>
</template>

<style scoped>
.report-workspace { display: grid; gap: 24px; max-width: 1460px; margin: 0 auto; min-width: 0; }
h2, h3, h4, p { margin: 0; } h2 { font-size: 20px; overflow-wrap: anywhere; } h3 { font-size: 16px; } h4 { font-size: 13px; }
.report-overview { display: grid; gap: 18px; padding: 24px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.section-heading { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: start; gap: 12px; } .section-heading > div { min-width: 0; } .section-heading .field-help { margin-top: 6px; }
.report-dates, .as-of { color: var(--muted); font-size: 12px; line-height: 1.65; }
.report-facts { display: flex; flex-wrap: wrap; gap: 20px 36px; margin: 0; padding: 18px 0; border-block: 1px solid var(--line); }
.report-facts > div, .submission-counts > div { display: grid; gap: 6px; } dt { color: var(--muted); font-size: 12px; } dd { margin: 0; font-variant-numeric: tabular-nums; } .report-facts dd { font-size: 17px; font-weight: 550; }
.session-facts { display: grid; min-width: 0; } summary { cursor: pointer; font-size: 13px; font-weight: 550; padding-block: 4px; } summary:focus-visible, .question-row:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; } .session-facts[open] > .field-help { margin-top: 12px; }
.detail-facts { display: grid; gap: 12px; margin: 16px 0 0; } .detail-facts > div { display: grid; grid-template-columns: minmax(130px, 190px) minmax(0, 1fr); gap: 16px; } .detail-facts dd { font-size: 13px; overflow-wrap: anywhere; }
.context-identities { display: grid; gap: 8px; padding-left: 18px; font-size: 13px; overflow-wrap: anywhere; } .session-facts > .field-help { margin-top: 10px; }
.report-columns { display: grid; grid-template-columns: minmax(220px, 280px) minmax(0, 1fr); gap: 28px; align-items: start; }
.question-directory { display: grid; align-content: start; gap: 10px; padding-right: 22px; border-right: 1px solid var(--line); min-width: 0; } .question-directory h2 { font-size: 16px; }
.question-row { display: grid; gap: 8px; width: 100%; padding: 13px; text-align: left; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); cursor: pointer; } .question-row:not(:disabled):hover { background: var(--surface-soft); } .question-row.selected { border-color: var(--brand); background: var(--surface-soft); } .question-row:disabled { cursor: default; opacity: .7; } .question-row strong { font-size: 13px; font-weight: 550; overflow-wrap: anywhere; } .question-row span { color: var(--muted); font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.submission-directory { display: grid; gap: 16px; min-width: 0; } .submission-directory > .section-heading h2 { font-size: 18px; }
.submission-row { display: grid; gap: 16px; padding-block: 22px; border-top: 1px solid var(--line); } .submission-row .text-link { font-size: 12px; }
.submission-counts { display: flex; flex-wrap: wrap; gap: 16px 28px; margin: 0; } .submission-counts dd { font-size: 14px; }
.evaluation-records { display: grid; gap: 10px; } .task-list { display: grid; gap: 12px; list-style: none; margin: 0; padding: 0; } .task-list li { display: flex; flex-wrap: wrap; gap: 12px; justify-content: space-between; padding: 12px 14px; background: var(--surface-soft); border-radius: 6px; } .task-list strong { font-size: 13px; font-weight: 550; } .task-list p, .task-list time { display: block; margin-top: 5px; color: var(--muted); font-size: 12px; line-height: 1.65; } .task-links { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.empty-copy, .loading-copy { max-width: 68ch; color: var(--muted); font-size: 13px; line-height: 1.8; padding: 12px 0; } .report-footnote { padding-top: 16px; border-top: 1px solid var(--line); color: var(--muted); font-size: 12px; line-height: 1.8; } .submission-pagination { padding-top: 12px; }
@media (max-width: 900px) { .report-columns { grid-template-columns: 1fr; } .question-directory { padding: 0 0 22px; border-right: 0; border-bottom: 1px solid var(--line); } .report-overview { padding: 18px; } }
@media (max-width: 560px) { .report-facts { gap: 18px; } .report-facts > div { flex: 1 1 150px; } .detail-facts > div { grid-template-columns: 1fr; gap: 5px; } .report-overview { padding: 16px; } .submission-counts { gap: 14px 22px; } .task-list li { padding: 12px; } }
</style>

<style scoped>
.report-return, .report-primary { display: inline-flex; align-items: center; gap: 12px; padding: 15px 25px; border: 1px solid var(--line); border-radius: 32px; font-size: 21px; color: var(--ink); }.report-primary { color: #24160c; background: linear-gradient(120deg,#f3c88e,#d99760); border-color: #d9ab78; }
.report-workspace { max-width: none; gap: 28px; }.report-overview { padding: 20px 25px; border-radius: 20px; gap: 12px; }.report-record-identity > summary { color: var(--muted); font-size: 16px; }.report-record-identity[open] > :not(summary) { margin-top: 16px; }
.report-facts { flex-wrap: nowrap; gap: 0; padding: 0; border: 0; }.report-facts > div { display: flex; align-items: center; gap: 27px; flex: 1; padding: 5px 18px; border-right: 1px solid var(--line); }.report-facts > div:last-child { border: 0; }.report-facts dt { font-size: 21px; }.report-facts dd { font-size: 38px; line-height: 1.5; }.report-facts dd small { font-size: 23px; font-weight: 400; }.report-facts > div:first-child > svg { color: #7bd7b9; }.report-facts > div:nth-child(2) > svg { color: #81c9e3; }.report-facts > div:nth-child(3) > svg { color: var(--brand); }.report-facts .report-note { font-size: 18px; color: var(--muted); gap: 12px; }
.report-columns { grid-template-columns: minmax(0, 1fr) 36%; gap: 24px; }.report-main { display: grid; gap: 24px; min-width: 0; }
.question-directory, .submission-directory, .report-end-conditions > section { padding: 24px; border: 1px solid var(--line); border-radius: 20px; background: var(--surface); }
.question-directory { gap: 0; }.question-directory h2, .report-end-conditions h2 { display: flex; align-items: center; gap: 18px; font-size: 26px; padding-bottom: 23px; border-bottom: 1px solid var(--line); }
.question-row { grid-template-columns: 115px minmax(0, 1fr) 25px; column-gap: 20px; row-gap: 6px; padding: 24px 8px; border: 0; border-bottom: 1px solid var(--line); border-radius: 0; background: transparent; }.question-row strong { font-size: 24px; grid-column: 2; }.question-row span { font-size: 19px; }.question-row .question-number { grid-column: 1; grid-row: 1 / 3; }.question-row .question-state { grid-column: 2; font-size: 20px; }.question-row > svg { grid-column: 3; grid-row: 1 / 3; align-self: center; }.question-row.selected { background: var(--brand-soft); border-radius: 12px; }
.report-end-conditions { display: grid; gap: 20px; min-width: 0; }.report-end-conditions dl { margin: 24px 0; display: grid; gap: 24px; }.report-end-conditions dl > div { display: flex; align-items: center; justify-content: space-between; gap: 20px; }.report-end-conditions dt { display: flex; gap: 16px; align-items: center; font-size: 20px; }.report-end-conditions dd { font-size: 21px; text-align: right; }.report-end-conditions :is(p,a) { font-size: 18px; line-height: 1.7; }.report-end-conditions :deep(.n-button) { justify-self: end; min-height: 58px; font-size: 22px; }
.submission-directory > .section-heading h2 { font-size: 25px; }.submission-row h3 { font-size: 22px; }.submission-row .text-link { font-size: 17px; }.task-list strong { font-size: 18px; }.task-list :is(p,time) { font-size: 16px; }
@media(max-width:1100px) { .report-columns { grid-template-columns: minmax(0, 1fr); }.report-facts { flex-wrap: wrap; row-gap: 20px; }.report-facts > div { flex-basis: 40%; }.report-end-conditions { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media(max-width:650px) { .report-end-conditions { grid-template-columns: minmax(0,1fr); }.report-facts > div { gap: 15px; padding: 5px; }.report-facts dt { font-size: 17px; }.report-facts > div > svg { width: 32px; }.question-row { grid-template-columns: 60px minmax(0,1fr) 20px; gap: 9px; }.question-row strong { font-size: 20px; }.question-row span { font-size: 15px; } }
</style>
