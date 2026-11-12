import { onUnmounted, ref, shallowRef, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { ApiError, read } from '../../api/client'
import type { InterviewEvent } from '../../api/schedule'
import type { EventReference, LinkEntry, LinkKind, LinkPage, LinkStatus, PreparationLink, PreparationSourceStatus } from '../../api/schedulePreparation'
import { preparationLinkPageValid, preparationSourceStampValid, preparationSourceStatusValid } from '../../api/schedulePreparation'
import { todayIdValid, todayTitleValid } from '../../api/today'
import { preparationTaskStatusLabels } from '../../api/preparationTask'
import { memoryUtcMicros } from '../../api/memoryReview'
import { useWorkspaceStore } from '../../state/workspace'
import { readPlanSource, same } from '../today/planUi'

export type PreparationChoice = Omit<LinkEntry, 'link'> & { kind: LinkKind; sourceId: string; sourceVersionId: string | null }
export const preparationPath = (eventId: string) => `/schedule/interviews/${eventId}`
export function preparationSourceRoute(link: Pick<PreparationLink, 'kind' | 'sourceId' | 'sourceVersionId'>, collectionKind?: 'LIST' | 'ROUTE'): RouteLocationRaw {
  if (link.kind === 'PREPARATION_TASK') return { name: 'preparation-tasks', query: { item: link.sourceId } }
  if (link.kind === 'REVIEW_ACTION') return { name: 'review', query: { action: link.sourceId } }
  return { name: 'collections', query: { item: link.sourceId, kind: collectionKind, version: link.sourceVersionId ?? undefined } }
}
export function preparationStateLabel(status: PreparationSourceStatus, pendingCount: number | null): string {
  if (status.kind === 'PREPARATION_TASK') return preparationTaskStatusLabels[status.task.status]
  if (status.kind === 'REVIEW_ACTION') return status.action.status === 'MERGED' ? '原动作已合并' : `待处理来源 ${pendingCount ?? 0} 项`
  return `${status.collection.kind === 'LIST' ? '题单' : '学习路线'} · v${status.collection.linkedVersionNumber} · ${status.collection.versionState === 'CURRENT' ? '当前版本' : status.collection.versionState === 'SUPERSEDED' ? '已有更新版本' : '已归档'}`
}
export function preparationTargetsLabel(refs: LinkEntry['targetRefs'], event: EventReference | InterviewEvent): string {
  if (!refs.length) return '来源未绑定目标'
  const labels = refs.map(value => !event.targetId ? '本次面试未绑定目标' : value.itemId !== event.targetId ? '与面试目标不同'
    : value.versionId !== event.targetVersionId ? '同一目标的其他版本' : '与面试目标版本一致')
  return `${refs.length > 1 ? `包含 ${refs.length} 个目标版本：` : ''}${[...new Set(labels)].join('、')}`
}

const record = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value))
const integer = (value: unknown, min = 0): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min
export async function readPreparationChoice(kind: LinkKind, id: string, zoneId: string): Promise<PreparationChoice> {
  if (!todayIdValid(id)) throw new Error('来源标识无效，请从目录重新选择。')
  let choice: PreparationChoice
  if (kind === 'COLLECTION') {
    const value = await read<unknown>(`/bank/collections/${id}`)
    if (!record(value) || value.id !== id || !['LIST', 'ROUTE'].includes(String(value.kind)) || value.status !== 'ACTIVE' || !integer(value.revision)
      || !record(value.currentVersion) || !todayIdValid(value.currentVersion.id) || !integer(value.currentVersion.number, 1)
      || !record(value.currentVersion.content) || !todayTitleValid(value.currentVersion.content.title)) throw new Error('题单当前版本或状态无法核对，请重新读取。')
    choice = { kind, sourceId: id, sourceVersionId: value.currentVersion.id, title: value.currentVersion.content.title, pendingCount: null, targetRefs: [],
      currentSource: { kind, task: null, action: null, collection: { revision: value.revision, versionId: value.currentVersion.id } },
      sourceStatus: { kind, task: null, action: null, collection: { kind: value.kind as 'LIST' | 'ROUTE', status: 'ACTIVE', currentVersionId: value.currentVersion.id, linkedVersionNumber: value.currentVersion.number, versionState: 'CURRENT' } } }
  } else {
    const item = await readPlanSource({ kind, id }, zoneId)
    if (kind === 'PREPARATION_TASK' && item.stamp.kind === kind && item.sourceStatus.kind === kind) {
      if (!['TODO', 'IN_PROGRESS', 'PAUSED'].includes(item.sourceStatus.task.status)) throw new Error('任务已结束，请选择仍在准备中的任务。')
      choice = { kind, sourceId: id, sourceVersionId: null, title: item.title, pendingCount: null, targetRefs: item.targetRefs,
        currentSource: { kind, task: item.stamp.task, action: null, collection: null }, sourceStatus: { kind, task: item.sourceStatus.task, action: null, collection: null } }
    } else if (kind === 'REVIEW_ACTION' && item.stamp.kind === kind && item.sourceStatus.kind === kind) {
      if (item.sourceStatus.action.status !== 'OPEN' || !(item.pendingCount && item.pendingCount > 0)) throw new Error('动作已合并或没有待处理来源，请重新选择。')
      choice = { kind, sourceId: id, sourceVersionId: null, title: item.title, pendingCount: item.pendingCount, targetRefs: item.targetRefs,
        currentSource: { kind, task: null, action: item.stamp.action, collection: null }, sourceStatus: { kind, task: null, action: item.sourceStatus.action, collection: null } }
    } else throw new Error('来源类型无法核对，请重新读取。')
  }
  if (!preparationSourceStampValid(choice.currentSource, kind) || !preparationSourceStatusValid(choice.sourceStatus, choice.currentSource)) throw new Error('来源修订无法核对，请重新读取。')
  return choice
}

export type PreparationPickerRow = { id: string; title: string; description: string }
export async function readPreparationPickerPage(kind: LinkKind, taskStatus: string, collectionKind: 'LIST' | 'ROUTE', cursor?: string) {
  const query = new URLSearchParams({ limit: '20' })
  if (cursor) query.set('cursor', cursor)
  if (kind === 'COLLECTION') { query.set('kind', collectionKind); query.set('status', 'ACTIVE') }
  else query.set('status', kind === 'PREPARATION_TASK' ? taskStatus : 'OPEN')
  const path = kind === 'COLLECTION' ? '/bank/collections' : kind === 'PREPARATION_TASK' ? '/prepare/tasks' : '/review/actions'
  const value = await read<unknown>(`${path}?${query}`)
  if (!record(value) || !Array.isArray(value.items) || value.items.length > 20 || !(value.nextCursor === null || typeof value.nextCursor === 'string' && value.nextCursor.length > 0 && value.nextCursor.length <= 5462 && value.nextCursor !== cursor)) throw new Error('来源目录分页无法核对。')
  const items = value.items.map((item): PreparationPickerRow => {
    if (!record(item) || !todayIdValid(item.id) || !integer(item.revision)) throw new Error('来源目录标识无法核对。')
    if (kind === 'PREPARATION_TASK') {
      if (!record(item.content) || !todayTitleValid(item.content.title) || item.status !== taskStatus) throw new Error('任务目录状态无法核对。')
      return { id: item.id, title: item.content.title, description: preparationTaskStatusLabels[item.status as 'TODO' | 'IN_PROGRESS' | 'PAUSED'] }
    }
    if (!todayTitleValid(item.title)) throw new Error('来源标题无法核对。')
    if (kind === 'REVIEW_ACTION') {
      if (item.status !== 'OPEN' || !integer(item.trackingCount)) throw new Error('动作目录状态无法核对。')
      return { id: item.id, title: item.title, description: '选择时重新核对待处理来源' }
    }
    if (item.kind !== collectionKind || item.status !== 'ACTIVE' || !todayIdValid(item.versionId) || !integer(item.versionNumber, 1)) throw new Error('题单目录版本无法核对。')
    return { id: item.id, title: item.title, description: `${collectionKind === 'LIST' ? '题单' : '学习路线'} · v${item.versionNumber}` }
  })
  if (new Set(items.map(item => item.id)).size !== items.length) throw new Error('来源目录包含重复标识。')
  return { items, nextCursor: value.nextCursor as string | null }
}

export function usePreparationLinks(event: ComputedRef<InterviewEvent>, enabled: ComputedRef<boolean>, status: Ref<LinkStatus | ''>, refreshToken: ComputedRef<number>) {
  const store = useWorkspaceStore(), entries = shallowRef<LinkEntry[]>([]), page = shallowRef<LinkPage>(), busy = ref(false), error = shallowRef<unknown>()
  let alive = true, generation = 0, cursors = new Set<string>()
  function reset() { generation++; entries.value = []; page.value = undefined; busy.value = false; cursors = new Set() }
  function refresh() { reset(); error.value = undefined; if (enabled.value) void load() }
  watch([enabled, () => store.sessionEpoch, () => event.value.id, () => event.value.revision, status, refreshToken], refresh, { immediate: true, flush: 'sync' })
  onUnmounted(() => { alive = false; generation++ })
  async function load(cursor?: string) {
    if (!enabled.value || busy.value) return
    const request = ++generation, epoch = store.sessionEpoch, id = event.value.id
    const query = { limit: 20, ...(status.value ? { status: status.value } : {}), ...(cursor ? { cursor } : {}) }
    const parameters = new URLSearchParams({ limit: '20' }); if (query.status) parameters.set('status', query.status); if (cursor) parameters.set('cursor', cursor)
    busy.value = true; error.value = undefined
    try {
      const value = await read<unknown>(`${preparationPath(id)}/preparation-links?${parameters}`)
      if (!alive || !enabled.value || epoch !== store.sessionEpoch || request !== generation) return
      if (!preparationLinkPageValid(value, id, query) || value.nextCursor && cursors.has(value.nextCursor)) throw new Error('准备关联目录无法核对，请从首页重读。')
      if (cursor && page.value && (!same(value.event, page.value.event) || memoryUtcMicros(value.asOf) !== memoryUtcMicros(page.value.asOf)
        || value.items.some(item => entries.value.some(old => old.link.id === item.link.id || old.link.kind === item.link.kind && old.link.sourceId === item.link.sourceId))
        || value.items.length > 0 && entries.value.length > 0 && value.items[0]!.link.id <= entries.value.at(-1)!.link.id)) throw new ApiError(409, 'PREPARATION_LINK_CURSOR_CHANGED', '关联目录已变化，请从首页重新读取。')
      entries.value = cursor ? [...entries.value, ...value.items] : value.items; page.value = value; cursors.add(cursor ?? '')
    } catch (reason) { if (alive && request === generation && epoch === store.sessionEpoch) { reset(); error.value = reason } }
    finally { if (alive && request === generation) busy.value = false }
  }
  return { entries, page, busy, error, refresh, load }
}
