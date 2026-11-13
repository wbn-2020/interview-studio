import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import * as g from '../../api/growth'
import { todayIdValid, todayZoneValid } from '../../api/today'

export const matrixColumns = [
  { kind: 'MEMORY_RATING', label: '回忆', heading: '回忆', icon: 'rotate-ccw' },
  { kind: 'TRAINING_ANSWER', label: '作答', heading: '独立作答', icon: 'square-pen' },
  { kind: 'REPLAY_JUDGMENT', label: '复练', heading: '复练', icon: 'refresh-cw' },
  { kind: 'REVIEWED_OBSERVATION', label: '受审观察', heading: '受审观察', icon: 'eye' },
] as const
export type MatrixKind = typeof matrixColumns[number]['kind']
export type GrowthPanel = 'evidence' | 'activity' | 'sources' | 'coverage' | 'next'
export type GrowthLocation = {
  zoneId: string; from: string; to: string; target: g.TargetRef | null
  topicId: string | null; dimension: g.Dimension | null; linked: boolean; classified: boolean
  panel: GrowthPanel; kind: g.EvidenceKind; level: g.AssessmentLevel | null; flag: g.ReviewFlag | null
  grain: g.Grain; disposition: g.SourceDisposition | null; useStatus: g.UseStatus | null
  requirementId: string | null; metric: g.ActivityMetric; columns: MatrixKind[]
  filtersOpen: boolean; directoryOpen: boolean
}
const keys = ['zoneId', 'from', 'to', 'targetId', 'targetVersionId', 'topicId', 'dimension', 'linked', 'classified',
  'panel', 'kind', 'level', 'flag', 'grain', 'disposition', 'useStatus', 'requirementId', 'metric', 'columns', 'filters', 'directory']

export function readGrowthLocation(query: LocationQuery, defaultZone: string): { state: GrowthLocation; problem: string } {
  const state: GrowthLocation = {
    zoneId: defaultZone, from: '', to: '', target: null, topicId: null, dimension: null, linked: false, classified: false,
    panel: 'evidence', kind: 'TRAINING_ANSWER', level: null, flag: null, grain: 'DAY', disposition: null, useStatus: null,
    requirementId: null, metric: 'ANSWERS_SUBMITTED', columns: matrixColumns.map(column => column.kind), filtersOpen: false, directoryOpen: false,
  }
  let problem = Object.keys(query).some(key => !keys.includes(key)) ? '成长地址含不支持的筛选参数。' : ''
  function invalid() { problem ||= '成长地址中的目标、日期或筛选参数无效，请重新选择范围。' }
  function choice<T extends string>(key: string, allowed: readonly T[], fallback: T | null): T | null {
    const value = query[key]
    if (value === undefined) return fallback
    if (typeof value === 'string' && allowed.includes(value as T)) return value as T
    invalid(); return fallback
  }
  function id(key: string): string | null {
    const value = query[key]
    if (value === undefined) return null
    if (todayIdValid(value)) return value
    invalid(); return null
  }
  function toggle(key: string): boolean { return choice(key, ['1'] as const, null) === '1' }
  if (query.zoneId !== undefined) { if (todayZoneValid(query.zoneId)) state.zoneId = query.zoneId as string; else invalid() }
  if (query.from !== undefined || query.to !== undefined) {
    if (typeof query.from === 'string' && typeof query.to === 'string' && g.growthDateRangeValid(query.from, query.to)) {
      state.from = query.from; state.to = query.to
    } else invalid()
  }
  if (query.targetId !== undefined || query.targetVersionId !== undefined) {
    const itemId = id('targetId'), versionId = id('targetVersionId')
    if (itemId && versionId) state.target = { itemId, versionId }; else invalid()
  }
  state.topicId = id('topicId'); state.requirementId = id('requirementId')
  state.dimension = choice('dimension', g.growthDimensions, null)
  state.linked = toggle('linked'); state.classified = toggle('classified')
  if (state.linked && !state.target || state.requirementId && !state.target) invalid()
  state.panel = choice('panel', ['evidence', 'activity', 'sources', 'coverage', 'next'] as const, 'evidence')!
  state.kind = choice('kind', g.evidenceKinds, 'TRAINING_ANSWER')!
  state.level = choice('level', ['SELF_RECORDED', 'AI_UNREVIEWED', 'REVIEWED_OBSERVATION'] as const, null)
  state.flag = choice('flag', ['DISPUTED', 'REVIEW_REQUIRED', 'SOURCE_UNAVAILABLE'] as const, null)
  state.grain = choice('grain', ['DAY', 'WEEK'] as const, 'DAY')!
  state.disposition = choice('disposition', g.sourceDispositions, null)
  state.useStatus = choice('useStatus', ['ACTIVE', 'WITHDRAWN'] as const, null)
  state.metric = choice('metric', g.activityMetrics, 'ANSWERS_SUBMITTED')!
  state.filtersOpen = toggle('filters'); state.directoryOpen = toggle('directory')
  if (query.columns !== undefined) {
    const values = Array.isArray(query.columns) ? query.columns : [query.columns]
    if (values.length === 1 && values[0] === 'none') state.columns = []
    else if (values.length <= matrixColumns.length && new Set(values).size === values.length
      && values.every(value => matrixColumns.some(column => column.kind === value))) {
      state.columns = matrixColumns.filter(column => values.includes(column.kind)).map(column => column.kind)
    } else invalid()
  }
  return { state, problem }
}

export function growthLocationQuery(value: GrowthLocation, defaultZone: string): LocationQueryRaw {
  return {
    zoneId: value.zoneId === defaultZone ? undefined : value.zoneId,
    from: value.from || undefined, to: value.to || undefined,
    targetId: value.target?.itemId, targetVersionId: value.target?.versionId, topicId: value.topicId ?? undefined,
    dimension: value.dimension ?? undefined, linked: value.linked ? '1' : undefined, classified: value.classified ? '1' : undefined,
    panel: value.panel === 'evidence' ? undefined : value.panel, kind: value.kind === 'TRAINING_ANSWER' ? undefined : value.kind,
    level: value.level ?? undefined, flag: value.flag ?? undefined, grain: value.grain === 'DAY' ? undefined : value.grain,
    disposition: value.disposition ?? undefined, useStatus: value.useStatus ?? undefined, requirementId: value.requirementId ?? undefined,
    metric: value.metric === 'ANSWERS_SUBMITTED' ? undefined : value.metric,
    columns: value.columns.length === matrixColumns.length ? undefined : value.columns.length ? [...value.columns] : 'none',
    filters: value.filtersOpen ? '1' : undefined, directory: value.directoryOpen ? '1' : undefined,
  }
}
