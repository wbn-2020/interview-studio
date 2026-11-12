import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import type { ComputedRef } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { ApiError, read } from '../../api/client'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'
import { todayContinuationRoute } from '../../api/today'
import type { Capability, Count, EvidenceSummary, NextStepContinuation } from '../../api/growth'
import { eligibilityReasonLabels, recordChangeLabels, recordStatusLabels, reviewLimitationLabels } from '../../api/standardReviews'
import { observationConclusionLabels, observationCoverageLabels } from '../../api/reviewedObservations'
import { trainingModeLabels } from '../../api/training'

export const copy = <T>(value: T): T => value === undefined ? value : JSON.parse(JSON.stringify(value)) as T
export const invalidGrowth = () => new ApiError(0, 'GROWTH_RESPONSE_INVALID', '本次响应无法按成长契约核对，请重新读取。')
export const countText = (value: Count) => value.mode === 'UNAVAILABLE' ? '未知' : `${value.mode === 'LOWER_BOUND' ? '至少 ' : ''}${value.value}`
export function capabilityText(value: Capability): string {
  return value.status === 'READY' ? '当前查询可用' : value.status === 'DEPENDENCY_PENDING' ? '此来源查询尚未接入，数量未知'
    : value.status === 'NOT_CONFIGURED' ? '尚未配置所需设置' : '此来源本次读取失败，不能解释为没有记录'
}
export function evidenceRoute(value: EvidenceSummary): RouteLocationRaw {
  const route = value.route, ref = value.ref
  const answer = { sessionId: route.sessionId!, questionId: route.questionId!, answerId: route.answerId! }
  switch (route.route) {
    case 'TRAINING_ANSWER': return { name: 'review', query: answer }
    case 'MANUAL_FEEDBACK': return { name: 'review', query: { ...answer, feedbackId: ref.objectId, feedbackRevision: ref.revision! } }
    case 'AI_OBSERVATION': return { name: 'review', query: { ...answer, taskId: ref.objectId, observationId: ref.partId! } }
    case 'REVIEWED_OBSERVATION': {
      if (ref.kind !== 'REVIEWED_OBSERVATION' || value.conditions.kind !== 'REVIEWED_OBSERVATION') throw invalidGrowth()
      return { name: 'review', query: { ...answer, reviewedObservationId: ref.objectId, reviewedRevision: ref.revision } }
    }
    case 'RECOLLECTION_VERSION': return { name: 'recollections', query: { item: ref.objectId, versionId: ref.versionId! } }
    case 'MEMORY_ATTEMPT': return { name: 'memory-recall', query: { attempt: route.attemptId! } }
    case 'REPLAY_COMPARISON': {
      if (value.conditions.kind !== 'REPLAY_JUDGMENT') throw invalidGrowth()
      return { name: 'repractice', query: { comparison: route.comparisonId!, judgment: ref.objectId,
        sourceId: value.conditions.replay.sourceId, judgmentRevision: value.conditions.replay.judgmentRevision } }
    }
  }
}
export function reviewedEvidenceStatus(value: EvidenceSummary): string {
  if (value.conditions.kind !== 'REVIEWED_OBSERVATION') return ''
  const eligibility = value.conditions.reviewed.eligibility
  return eligibility.eligibleAsReviewedObservation ? '本次核对具备有限条件观察资格'
    : `本次核对不具备当前资格：${eligibility.reasons.map(reason => eligibilityReasonLabels[reason]).join('、')}`
}
// 成长只渲染受审观察的封闭元数据；原文、自由文本与引文留在确切观察入口。
export function reviewedEvidenceRows(value: EvidenceSummary): { label: string; value: string }[] {
  if (value.conditions.kind !== 'REVIEWED_OBSERVATION') return []
  const meta = value.conditions.reviewed, answer = meta.answer
  const conditionLabels = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
  const questionKindLabels = { MAIN: '题库主问', FOLLOW_UP: '同场追问', REPLAY: '独立复练' }
  const time = (instant: string) => `${new Intl.DateTimeFormat('zh-CN', { timeZone:meta.observationZoneId, dateStyle:'medium', timeStyle:'short' }).format(new Date(instant))}（${meta.observationZoneId}）`
  return [
    { label: '确切观察修订', value: `修订 ${value.ref.revision} · ${recordChangeLabels[meta.change]} · ${recordStatusLabels[meta.recordedStatus]}` },
    { label: '保存时间', value: time(meta.recordedAt) },
    { label: '声明观察日期', value: `${meta.observedOn}（${meta.observationZoneId}）` },
    { label: '原文覆盖与结论', value: `${observationCoverageLabels[meta.coverage]} · ${observationConclusionLabels[meta.conclusion]}` },
    { label: '确切回答', value: `第 ${meta.answerNumber} 版 · 提交于 ${time(meta.answerCreatedAt)}` },
    { label: '训练与辅助条件', value: `${trainingModeLabels[answer.mode]} · ${questionKindLabels[answer.questionKind]} · ${conditionLabels[answer.condition]}` },
    { label: '此前接触记录', value: `编写题目 ${answer.priorExposure.authoredCount} 次 · 查看参考 ${answer.priorExposure.referenceViewCount} 次 · 打开评价 ${answer.priorExposure.evaluationAccessCount} 次` },
    { label: '原题条件记录情况', value: `技术${answer.recordedFields.technology ? '已记录' : '未记录'} · 场景${answer.recordedFields.scenario ? '已记录' : '未记录'} · 适用条件${answer.recordedFields.conditions ? '已记录' : '未记录'}` },
    { label: '题库确切版本', value: `${meta.question.questionId} / ${meta.question.versionId}` },
    { label: '固定检查项', value: meta.checkpointId },
    { label: '引用标准审阅', value: `${meta.standardReview.id} · 修订 ${meta.standardReview.revision}` },
    { label: '当前记录状态', value: `修订 ${meta.eligibility.currentRevision} · ${recordStatusLabels[meta.eligibility.currentStatus]}${meta.eligibility.revisionCurrent ? ' · 此修订为当前修订' : ' · 本条为历史修订'}` },
    { label: '当前标准状态', value: `修订 ${meta.eligibility.standardCurrentRevision} · ${recordStatusLabels[meta.eligibility.standardCurrentStatus]}` },
    { label: '来源限制', value: meta.eligibility.limitations.map(limit => reviewLimitationLabels[limit]).join('；') },
  ]
}
export function nextRoute(value: NextStepContinuation): RouteLocationRaw | undefined {
  return value.route === 'SOURCE' ? todayContinuationRoute(value.source) ?? undefined
    : { name: 'prepare', query: { kind: 'TARGET', item: value.requirement.target.itemId, version: value.requirement.target.versionId } }
}

// 每个读取槽有独立请求序号；范围、会话或资料窗口改变后清空旧结果，迟到响应不能重新呈现。
export function useGrowthRead<T>(readable: ComputedRef<boolean>, scope: ComputedRef<string>) {
  const store = useWorkspaceStore(), data = shallowRef<T>(), error = shallowRef<unknown>(), busy = ref(false)
  let alive = true, generation = 0
  function reset() { generation++; data.value = undefined; error.value = undefined; busy.value = false }
  watch([readable, scope, () => store.sessionEpoch], reset, { flush: 'sync' })
  onUnmounted(() => { alive = false; reset() })
  async function load(path: string, valid: (value: unknown) => value is T, merge?: (previous: T | undefined, next: T) => T) {
    if (!readable.value) return
    const request = ++generation, epoch = store.sessionEpoch, signature = scope.value, previous = data.value
    busy.value = true; error.value = undefined
    if (!merge) data.value = undefined
    const current = () => alive && readable.value && request === generation && epoch === store.sessionEpoch && signature === scope.value
    try {
      const value = await read<unknown>(path)
      if (!current()) return
      if (!valid(value)) throw invalidGrowth()
      data.value = merge ? merge(previous, value) : value
      return value
    } catch (reason) { if (current()) { data.value = undefined; error.value = reason } }
    finally { if (current()) busy.value = false }
  }
  return { data, error, busy, load, reset }
}

export function useGrowthCommand(readable: ComputedRef<boolean>, stateKey: string, dirty: ComputedRef<boolean>) {
  const store = useWorkspaceStore(), writer = useCommand(), state = reactive({ dirty: false, locked: false })
  const saved = shallowRef<NonNullable<typeof writer.pending.value>>()
  let alive = true, generation = 0, commandEpoch = store.sessionEpoch, commandGeneration = 0
  const locked = computed(() => writer.locked.value)
  watch([readable, () => store.sessionEpoch], () => { generation++ }, { flush: 'sync' })
  watch([dirty, locked], ([changed, waiting]) => { state.dirty = changed; state.locked = waiting; store.changes.set(stateKey, state) }, { immediate: true, flush: 'sync' })
  onUnmounted(() => { alive = false; generation++; if (store.changes.get(stateKey) === state) store.changes.delete(stateKey) })
  function run<T>(path: string, method: string, body: unknown, valid: (value: unknown) => value is T, apply: (value: T) => void) {
    if (!readable.value || locked.value) return
    commandEpoch = store.sessionEpoch; commandGeneration = generation
    void writer.run<unknown>(path, method, body, value => {
      let accepted = false
      try { accepted = alive && readable.value && commandEpoch === store.sessionEpoch && commandGeneration === generation && valid(value) } catch { accepted = false }
      // 成功 HTTP 也可能丢失权限窗口或带来无效回执，此时仍只重试原 key 与正文。
      if (!accepted) { writer.pending.value = saved.value; writer.error.value = new ApiError(0, 'GROWTH_RECEIPT_UNCERTAIN', '回执尚未核对，原请求与输入已保留。'); return }
      const acceptedCommand = saved.value
      // 应用失败仍保留原请求；回调若发起新命令，不清除新命令的恢复记录。
      apply(value as T)
      if (saved.value === acceptedCommand) saved.value = undefined
    })
    saved.value = writer.pending.value
  }
  function retry() { if (readable.value && !writer.busy.value && writer.pending.value) { commandEpoch = store.sessionEpoch; commandGeneration = generation; void writer.retry() } }
  return { ...writer, locked, run, retry }
}
