import { ApiError, errorText } from './client'
import { memoryUtcMicros } from './memoryReview'
import { todayContinuationValid, todayDateValid, todayIdValid, todaySourceRefValid, todaySourceStampValid, todayTitleValid, todayZoneValid } from './today'
import type { TodayContinuation, TodaySourceRef, TodaySourceStamp } from './today'
import { observationAnswerMetaValid, observationCoverageConclusionValid, observationEligibilityValid, observationProjectionValid } from './reviewedObservations'
import type { ObservationConclusion, ObservationCoverage, ObservationEligibility } from './reviewedObservations'
import { recordChanges, recordStatuses, revisionRefValid } from './standardReviews'
import type { RecordChange, RecordStatus, RevisionRef } from './standardReviews'

export const evidenceKinds = ['TRAINING_ANSWER', 'MANUAL_FEEDBACK', 'AI_OBSERVATION', 'RECOLLECTION', 'REPLAY_JUDGMENT', 'MEMORY_RATING', 'REVIEWED_OBSERVATION'] as const
export type EvidenceKind = typeof evidenceKinds[number]
export type AvailableEvidenceKind = EvidenceKind
export const growthDimensions = ['EXPLAIN', 'DEBUG', 'TRADEOFF', 'OWNERSHIP', 'COMMUNICATION', 'OTHER'] as const
export type Dimension = typeof growthDimensions[number]
export type AssessmentLevel = 'SELF_RECORDED' | 'AI_UNREVIEWED' | 'REVIEWED_OBSERVATION'
export type RecordKind = 'ANSWER' | 'SELF_CHECK' | 'EXTERNAL_FEEDBACK' | 'AI_FEEDBACK' | 'INTERVIEW_RECOLLECTION' | 'REPLAY_ASSESSMENT' | 'MEMORY_SELF_RATING' | 'REVIEWED_CHECK'
export type ReviewFlag = 'DISPUTED' | 'REVIEW_REQUIRED' | 'SOURCE_UNAVAILABLE'
export type LineageCheck = 'NOT_APPLICABLE' | 'PARTIAL' | 'COMPLETE'
export type Availability = 'READY' | 'DEPENDENCY_PENDING' | 'NOT_CONFIGURED' | 'ERROR'
export type CapabilityReason = 'NONE' | 'QUERY_NOT_INTEGRATED' | 'REVIEWED_CHAIN_NOT_INTEGRATED' | 'MEMORY_SETTINGS_REQUIRED' | 'SOURCE_READ_FAILED' | 'SOURCE_INTEGRITY_FAILED'
export type Capability = { status: Availability; reason: CapabilityReason }
export type Count = { value: number; mode: 'EXACT' | 'LOWER_BOUND' } | { value: null; mode: 'UNAVAILABLE' }
export type TargetMode = 'RECORDED_ONLY' | 'INCLUDE_ACTIVE_LINKS'
export type TopicMode = 'RECORDED_ONLY' | 'INCLUDE_CURRENT_CLASSIFICATION'
export type EmptyState = 'HAS_RECORDS' | 'RECOLLECTION_ONLY' | 'NO_RECORD' | 'UNKNOWN'
export type DirectState = 'AVAILABLE' | 'REVIEW_REQUIRED' | 'UNAVAILABLE' | 'UNKNOWN'
export type RecordCurrency = 'CURRENT' | 'SUPERSEDED' | 'NOT_APPLICABLE'
export type Coverage = 'SUPPORTS' | 'PARTIAL' | 'CONTEXT_ONLY' | 'INSUFFICIENT'
export type UseStatus = 'ACTIVE' | 'WITHDRAWN'
export type MatchState = 'MATCH' | 'DIFFERENT' | 'UNKNOWN'
export type Visibility = 'VISIBLE' | 'HIDDEN'
export type TargetRef = { itemId: string; versionId: string }
export type QuestionRef = { questionId: string; versionId: string }
type EvidenceIdentity<K extends AvailableEvidenceKind, P> = { kind: K; objectId: string } & Omit<{ versionId: null; revision: null; partId: null }, keyof P> & P
export type EvidenceRef = EvidenceIdentity<'TRAINING_ANSWER' | 'REPLAY_JUDGMENT' | 'MEMORY_RATING', Record<never, never>>
  | EvidenceIdentity<'MANUAL_FEEDBACK', { revision: number }> | EvidenceIdentity<'AI_OBSERVATION', { partId: string }>
  | EvidenceIdentity<'RECOLLECTION', { versionId: string }> | EvidenceIdentity<'REVIEWED_OBSERVATION', { revision: number }>
export type Context = { asOf: string; checkedAt: string; serverTime: string }
export type DateWindow = { from: string; to: string; zoneId: string; startAt: string; endAt: string; observedUntil: string }
export type Filter = { target: TargetRef | null; targetMode: TargetMode; topicId: string | null; topicMode: TopicMode; dimension: Dimension | null }
export type Scope = { window: DateWindow; filter: Filter }
export type Exposure = { authoredCount: number; referenceViewCount: number; evaluationAccessCount: number; lastExposureAt: string | null }
export type RepracticeContext = { id: string; questionMode: 'SAME' | 'VARIANT'; baselineKind: 'ANSWER' | 'RECOLLECTION' | 'NONE' }
export type RecordedFields = { technology: boolean; scenario: boolean; conditions: boolean }
export type AnswerMeta = { sessionId: string; questionId: string; answerId: string; mode: 'LEARNING' | 'FOCUSED' | 'MOCK'; questionKind: 'MAIN' | 'FOLLOW_UP' | 'REPLAY';
  condition: 'LEARNING' | 'BEFORE_ASSISTANCE' | 'ASSISTED'; assistanceEventSequence: number; priorExposure: Exposure; sourceQuestion: QuestionRef | null;
  repractice: RepracticeContext | null; recordedFields: RecordedFields }
export type DecisionMeta = { id: string; revision: number; choice: 'ACCEPTED' | 'DISPUTED' | 'IGNORED'; createdAt: string }
export type StandardMeta = { question: QuestionRef; status: 'PERSONAL_UNREVIEWED' }
export type ManualMeta = { answer: AnswerMeta; kind: 'SELF_CHECK' | 'EXTERNAL_FEEDBACK'; authorLabel: string; dimension: Dimension;
  coverage: 'MENTIONED' | 'NOT_MENTIONED' | 'UNCERTAIN'; latestDecision: DecisionMeta | null }
export type AiMeta = { answer: AnswerMeta; dimension: Dimension; coverage: ManualMeta['coverage']; latestDecision: DecisionMeta | null; standard: StandardMeta | null }
export type ReviewedMeta = { answer: AnswerMeta; question: QuestionRef; checkpointId: string; standardReview: RevisionRef; change: RecordChange; recordedStatus: RecordStatus;
  recordedAt: string; observedOn: string; observationZoneId: string; answerNumber: number; answerCreatedAt: string; coverage: ObservationCoverage;
  conclusion: ObservationConclusion; eligibility: ObservationEligibility }
export type InterviewMeta = { eventId: string; revision: number; startAt: string; zoneId: string; target: TargetRef | null }
export type RecollectionMeta = { versionNumber: number; baselineKind: 'RECOLLECTION' | 'NONE'; interview: InterviewMeta | null;
  hasAnswerFragment: boolean; hasFeedback: boolean; hasUncertainty: boolean }
export type ReplayMeta = { sourceId: string; actionId: string; judgmentRevision: number; comparisonId: string; answer: AnswerMeta; questionMode: 'SAME' | 'VARIANT';
  baselineKind: 'ANSWER' | 'RECOLLECTION' | 'NONE'; outcome: 'HANDLED' | 'PARTIAL' | 'UNHANDLED' | 'UNCERTAIN'; currentlyApplicable: boolean;
  recordedVerificationHash: string; currentVerificationHash: string | null }
export type RatingDay = { zoneId: string; localDate: string; windowStart: string; windowEnd: string; settingsRevision: number | null }
export type BudgetUse = { kind: 'NEW' | 'REVIEW'; firstForCardToday: boolean; firstCountedEventId: string }
export type MemoryMeta = { cardId: string; cardVersionId: string; attemptId: string; attemptSequence: number; mode: 'REGULAR' | 'CRAM'; countRegular: boolean;
  rating: 'AGAIN' | 'HARD' | 'GOOD' | 'EASY'; ratingDay: RatingDay; budget: BudgetUse | null; scheduleChangeId: string | null; scheduleSequence: number | null;
  thinkingElapsedMs: number; totalElapsedMs: number }
type ConditionPayload<K extends AvailableEvidenceKind, P> = { kind: K } & Omit<{ answer: null; manual: null; ai: null; recollection: null; replay: null; memory: null; reviewed: null }, keyof P> & P
export type Conditions = ConditionPayload<'TRAINING_ANSWER', { answer: AnswerMeta }> | ConditionPayload<'MANUAL_FEEDBACK', { manual: ManualMeta }>
  | ConditionPayload<'AI_OBSERVATION', { ai: AiMeta }> | ConditionPayload<'RECOLLECTION', { recollection: RecollectionMeta }>
  | ConditionPayload<'REPLAY_JUDGMENT', { replay: ReplayMeta }> | ConditionPayload<'MEMORY_RATING', { memory: MemoryMeta }>
  | ConditionPayload<'REVIEWED_OBSERVATION', { reviewed: ReviewedMeta }>
export type FacetBasis = 'RECORDED' | 'CURRENT_CLASSIFICATION' | 'USER_LINK'
export type TargetFacet = { target: TargetRef; basis: FacetBasis }
export type TopicFacet = { topicId: string; basis: FacetBasis }
export type DimensionFacet = { dimension: Dimension; basis: FacetBasis }
export type TargetMatch = { recorded: boolean; activeUserLink: boolean }
export type Facets = { targets: TargetFacet[]; topics: TopicFacet[]; dimensions: DimensionFacet[]; currentTopicClassificationAvailable: boolean; selectedTargetMatch: TargetMatch | null }
export type EvidenceState = { directState: DirectState; recordCurrency: RecordCurrency; reviewFlags: ReviewFlag[]; lineageCheck: LineageCheck }
export type EvidenceStamp = { ref: EvidenceRef; originHash: string | null; currentHash: string }
export type EvidenceRouteKind = 'TRAINING_ANSWER' | 'MANUAL_FEEDBACK' | 'AI_OBSERVATION' | 'RECOLLECTION_VERSION' | 'REPLAY_COMPARISON' | 'MEMORY_ATTEMPT' | 'REVIEWED_OBSERVATION'
export type EvidenceRoute = { route: EvidenceRouteKind; ref: EvidenceRef; sessionId: string | null; questionId: string | null; answerId: string | null;
  comparisonId: string | null; cardId: string | null; attemptId: string | null }
export type EvidenceSummary = { ref: EvidenceRef; occurredAt: string; title: string; recordKind: RecordKind; assessmentLevel: AssessmentLevel;
  conditions: Conditions; facets: Facets; state: EvidenceState; stamp: EvidenceStamp; route: EvidenceRoute }
export type EvidenceResolution = { ref: EvidenceRef; summary: EvidenceSummary | null; state: EvidenceState; stamp: EvidenceStamp }
export type OverviewQuery = { zoneId: string; from?: string; to?: string; targetId?: string; targetVersionId?: string; targetMode?: TargetMode;
  topicId?: string; topicMode?: TopicMode; dimension?: Dimension }
export type EvidenceQuery = OverviewQuery & { kind: EvidenceKind; level?: AssessmentLevel; reviewFlag?: ReviewFlag; cursor?: string; limit?: number }
export type KindCount = { kind: EvidenceKind; capability: Capability; count: Count; scanLimited: boolean }
export type EvidencePage = { context: Context; scope: Scope; kind: EvidenceKind; level: AssessmentLevel | null; reviewFlag: ReviewFlag | null;
  capability: Capability; count: Count; scanLimited: boolean; scanned: number; items: EvidenceSummary[]; nextCursor: string | null }
export type Overview = { context: Context; scope: Scope; evidenceGroups: KindCount[]; emptyState: EmptyState; activity: ActivityTotals;
  sourceProgress: SourceProgressTotals; limitations: Limitation[] }
export type GrowthOverview = Overview
export type Grain = 'DAY' | 'WEEK'
export type PeriodState = 'COMPLETE' | 'PARTIAL_TODAY' | 'FUTURE'
export const activityMetrics = ['ANSWERS_SUBMITTED', 'QUESTION_INSTANCES_ANSWERED', 'ANSWERS_BEFORE_ASSISTANCE', 'ANSWERS_LEARNING', 'ANSWERS_ASSISTED',
  'REPLAYS_STARTED', 'SAME_REPLAYS_STARTED', 'VARIANT_REPLAYS_STARTED', 'REPLAY_JUDGMENTS_RECORDED', 'MEMORY_RATINGS', 'MEMORY_CARDS_RATED',
  'MEMORY_COUNTED_RATINGS', 'MEMORY_UNCOUNTED_RATINGS', 'TASKS_DONE', 'INTERVIEWS_COMPLETED', 'FEEDBACK_CREATED', 'FEEDBACK_REVISED',
  'AI_OBSERVATIONS_PUBLISHED', 'RECOLLECTION_VERSIONS_RECORDED', 'REVIEWED_OBSERVATIONS_CREATED', 'REVIEWED_OBSERVATIONS_MAINTAINED'] as const
export type ActivityMetric = typeof activityMetrics[number]
export type MetricUnit = 'EVENT' | 'DISTINCT_QUESTION' | 'DISTINCT_CARD' | 'REPLAY'
export type ActivityQuery = Omit<OverviewQuery, 'targetMode'> & { grain?: Grain }
export type MetricValue = { metric: ActivityMetric; unit: MetricUnit; capability: Capability; count: Count; additive: boolean }
export type ActivityBucket = { from: string; to: string; startAt: string; endAt: string; state: PeriodState; clipped: boolean; values: MetricValue[] }
export type ActivityTotals = { recordedFilter: Filter; values: MetricValue[] }
export type ActivitySeries = { context: Context; scope: Scope; grain: Grain; buckets: ActivityBucket[]; totals: ActivityTotals; limitations: Limitation[] }
export const sourceDispositions = ['DROPPED', 'SOURCE_UNAVAILABLE', 'REVIEW_REQUIRED', 'UNASSESSED', 'HANDLED', 'PARTIAL', 'UNHANDLED', 'UNCERTAIN'] as const
export type SourceDisposition = typeof sourceDispositions[number]
export type ProgressFilter = Omit<Filter, 'targetMode'>
export type SourceProgressQuery = Omit<OverviewQuery, 'from' | 'to' | 'zoneId' | 'targetMode'> & { disposition?: SourceDisposition; cursor?: string; limit?: number }
export type SourceProgressItem = { sourceId: string; actionId: string; actionRevision: number; tracking: 'TRACKING' | 'DROPPED'; origin: EvidenceRef;
  latestJudgment: EvidenceRef | null; latestOutcome: ReplayMeta['outcome'] | null; domainApplicable: boolean; disposition: SourceDisposition;
  originState: EvidenceState; verificationHash: string; continuation: TodayContinuation }
export type DispositionCount = { disposition: SourceDisposition; count: Count }
export type SourceProgressTotals = { filter: ProgressFilter; capability: Capability; counts: DispositionCount[]; scanLimited: boolean }
export type SourceProgressPage = { context: Context; filter: ProgressFilter; disposition: SourceDisposition | null; capability: Capability; count: Count;
  scanLimited: boolean; scanned: number; items: SourceProgressItem[]; nextCursor: string | null }
export type ComparisonItem = { state: MatchState; reason: string }
export type Comparability = { dimension: ComparisonItem; scenario: ComparisonItem; technology: ComparisonItem; assistance: ComparisonItem; date: ComparisonItem }
export type RequirementMeta = { id: string; ordinal: number; text: string; dimensions: Dimension[]; priority: number }
export type TargetMeta = { ref: TargetRef; title: string; capturedOn: string | null; itemRevision: number; status: 'ACTIVE' | 'ARCHIVED'; currentVersionId: string; sourceGeneration: number }
export type EvidenceUse = { id: string; revision: number; target: TargetRef; requirementId: string; evidence: EvidenceRef; coverage: Coverage;
  comparability: Comparability; reason: string; status: UseStatus; createdAt: string; updatedAt: string }
export type EffectiveUseState = 'EFFECTIVE_POSITIVE' | 'NON_POSITIVE' | 'WITHDRAWN' | 'TARGET_NOT_CURRENT' | 'SOURCE_REVIEW_REQUIRED' | 'SOURCE_UNAVAILABLE' | 'SOURCE_UNKNOWN'
export type UseEntry = { use: EvidenceUse; target: TargetMeta; requirement: RequirementMeta; evidence: EvidenceResolution; effectiveState: EffectiveUseState }
export type UseLookup = { context: Context; entry: UseEntry }
export type CreateUsePreview = { target: TargetRef; requirementId: string; evidence: EvidenceRef }
export type ChangeUsePreview = { expectedRevision: number }
export type UseIdentityQuery = { targetId: string; targetVersionId: string; requirementId: string; kind: AvailableEvidenceKind; objectId: string;
  versionId?: string; revision?: number; partId?: string }
export type UseIdentityLookup = { context: Context; identity: CreateUsePreview; entry: UseEntry | null }
export type UsePreview = { context: Context; intent: 'CREATE' | 'CHANGE'; useId: string | null; useRevision: number | null; target: TargetMeta;
  requirement: RequirementMeta; evidence: EvidenceResolution; comparability: Comparability; previewHash: string; limitations: Limitation[] }
export type CreateUse = { request: CreateUsePreview; expectedPreviewHash: string; coverage: Coverage; comparability: Comparability; reason: string; confirmed: true }
export type ChangeUse = { expectedRevision: number; expectedPreviewHash: string; coverage: Coverage; comparability: Comparability; reason: string; status: UseStatus; confirmed: true }
export type UseDirectoryQuery = { targetId: string; targetVersionId: string; requirementId?: string; status?: UseStatus; cursor?: string; limit?: number }
export type UsePage = { context: Context; target: TargetRef; requirementId: string | null; status: UseStatus | null; items: UseEntry[]; nextCursor: string | null }
export type UseChangeKind = 'CREATED' | 'UPDATED' | 'WITHDRAWN' | 'RESTORED'
export type UseHistory = { change: UseChangeKind; snapshot: EvidenceUse; sourceStamp: EvidenceStamp; targetStamp: string; previewHash: string; createdAt: string }
export type UseHistoryPage = { items: UseHistory[]; nextBefore: number | null }
export type HistoryQuery = { before?: number; limit?: number }
export const coverageMetrics = ['ACTIVE_USES', 'SAVED_SUPPORTS', 'SAVED_PARTIAL', 'CONTEXT_ONLY', 'INSUFFICIENT', 'EFFECTIVE_POSITIVE', 'INVALIDATED_POSITIVE', 'WITHDRAWN_USES'] as const
export type CoverageMetric = typeof coverageMetrics[number]
export type CoverageCount = { metric: CoverageMetric; count: Count }
export type RequirementCoverage = { requirement: RequirementMeta; counts: CoverageCount[]; noActiveLinkedEvidence: boolean | null }
export type TargetCoverage = { context: Context; target: TargetMeta; currentTarget: boolean; capability: Capability; requirements: RequirementCoverage[]; scanLimited: boolean; scanned: number }
export type SubjectKind = 'SOURCE' | 'TARGET_REQUIREMENT'
export type RequirementIdentity = { target: TargetRef; requirementId: string }
export type GrowthSourceRef = TodaySourceRef & { kind: 'REVIEW_ACTION' | 'MEMORY_CARD' | 'PREPARATION_TASK' }
export type GrowthSourceStamp = Extract<TodaySourceStamp, { kind: GrowthSourceRef['kind'] }>
export type SubjectIdentity = { kind: 'SOURCE'; source: GrowthSourceRef; requirement: null } | { kind: 'TARGET_REQUIREMENT'; source: null; requirement: RequirementIdentity }
export type Subject = { kind: 'SOURCE'; source: GrowthSourceRef; sourceStamp: GrowthSourceStamp; requirement: null; targetRevision: null }
  | { kind: 'TARGET_REQUIREMENT'; source: null; sourceStamp: null; requirement: RequirementIdentity; targetRevision: number }
export const nextStepReasons = ['ACTION_PENDING', 'ACTION_REVIEW_REQUIRED', 'MEMORY_DUE', 'MEMORY_REVIEW_REQUIRED', 'TASK_UNSCHEDULED', 'USER_MARKED_INSUFFICIENT'] as const
export type NextStepReason = typeof nextStepReasons[number]
export type PriorityBasis = 'PLACEMENT' | 'TARGET_REQUIREMENT' | 'DEFAULT'
export type NextStepContinuation = { route: 'SOURCE'; source: TodayContinuation; requirement: null } | { route: 'TARGET_REQUIREMENT'; source: null; requirement: RequirementIdentity }
export type NextStep = { subject: Subject; title: string; reasonCodes: NextStepReason[]; effectivePriority: number; priorityBasis: PriorityBasis;
  preferenceId: string | null; preferenceRevision: number | null; continuation: NextStepContinuation }
export type NextStepsQuery = { zoneId: string; targetId?: string; targetVersionId?: string; limit?: number }
export type NextStepScope = { zoneId: string; localDate: string; target: TargetRef | null }
export type NextSteps = { context: Context; scope: NextStepScope; capability: Capability; items: NextStep[]; examinedCandidates: number;
  scanLimited: boolean; matchingNotShown: Count; limitations: Limitation[] }
export type NextStepPreference = { id: string; revision: number; subject: SubjectIdentity; visibility: Visibility; createdAt: string; updatedAt: string }
export type CreatePreference = { subject: Subject; visibility: Visibility }
export type SavePreference = { expectedRevision: number; expectedSubject: Subject | null; visibility: Visibility }
export type PreferenceEntry = { preference: NextStepPreference; currentSubject: Subject | null; title: string; continuation: NextStepContinuation | null; subjectState: DirectState }
export type PreferenceLookup = { context: Context; entry: PreferenceEntry }
export type PreferenceSubjectQuery = { subjectKind: 'SOURCE'; sourceKind: GrowthSourceRef['kind']; sourceId: string }
  | { subjectKind: 'TARGET_REQUIREMENT'; targetId: string; targetVersionId: string; requirementId: string }
export type PreferenceSubjectLookup = { context: Context; subject: SubjectIdentity; entry: PreferenceEntry | null }
export type PreferenceQuery = { visibility?: Visibility; subjectKind?: SubjectKind; cursor?: string; limit?: number }
export type PreferencePage = { context: Context; visibility: Visibility | null; subjectKind: SubjectKind | null; items: PreferenceEntry[]; nextCursor: string | null }
export type PreferenceHistory = { change: 'CREATED' | 'UPDATED'; snapshot: NextStepPreference; createdAt: string }
export type PreferenceHistoryPage = { items: PreferenceHistory[]; nextBefore: number | null }
export const growthLimitations = ['INDIRECT_LINEAGE_NOT_CHECKED', 'REVIEWED_CHAIN_NOT_INTEGRATED', 'NO_LINKED_STANDARD', 'TARGET_SCENARIO_UNSTRUCTURED',
  'TARGET_TECHNOLOGY_UNSTRUCTURED', 'TARGET_ASSISTANCE_UNSTRUCTURED', 'NO_AUTOMATIC_DATE_EXPIRY', 'CURRENT_TOPIC_CLASSIFICATION', 'FILTERED_SCOPE_ONLY',
  'COUNT_SCAN_LIMITED', 'ORIGINAL_TARGET_ACTIVITY_ONLY', 'HISTORICAL_TARGET_VERSION', 'SEMANTICS_NOT_CERTIFIED', 'NO_SINGLE_QUESTION_DURATION', 'NEXT_STEPS_BOUNDED'] as const
export type Limitation = typeof growthLimitations[number]

const fields = (value: unknown, names: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(key => Object.hasOwn(value, key)))
const integer = (value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
const oneOf = <T extends string>(value: unknown, values: readonly T[]): value is T => typeof value === 'string' && values.includes(value as T)
const check = (rule: () => boolean) => { try { return rule() } catch { return false } }
const bounded = (value: unknown, max = 1048576) => new TextEncoder().encode(JSON.stringify(value)).length <= max
const timestamp = (value: unknown): value is string => memoryUtcMicros(value) !== undefined
const nullableTime = (value: unknown): value is string | null => value === null || timestamp(value)
const nullableId = (value: unknown): value is string | null => value === null || todayIdValid(value)
const nullableRevision = (value: unknown): value is number | null => value === null || integer(value)
const beforeOrEqual = (a: string, b: string) => memoryUtcMicros(a)! <= memoryUtcMicros(b)!
const sameTime = (a: string, b: string) => memoryUtcMicros(a) === memoryUtcMicros(b)
const hashValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const nonblank = (value: string) => /[^\p{Z}\u0009-\u000d\u001c-\u001f]/u.test(value)
const textValid = (value: unknown, max: number, required = false): value is string => typeof value === 'string' && Array.from(value).length <= max
  && !/[\u0000\uD800-\uDFFF]/u.test(value) && (!required || nonblank(value))
const sourceTextValid = (value: unknown, max: number, required = false): value is string => typeof value === 'string' && value.length <= max
  && (!required || /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value))
const targetRequirementTitleValid = (value: unknown): value is string => sourceTextValid(value, 400, true) && Array.from(value).length <= 200
function sourceCapturedOnValid(value: unknown): value is string {
  if (typeof value !== 'string') return false
  // 固定材料采集日按 Java LocalDate 原样读取；不用安排日期下界或 JavaScript Date 的有限年份范围。
  const parts = /^(\d{4}|-\d{4}|[+-][1-9]\d{4,8})-(\d{2})-(\d{2})$/.exec(value)
  if (!parts || parts[1] === '-0000') return false
  const year = Number(parts[1]), month = Number(parts[2]), day = Number(parts[3])
  if (month < 1 || month > 12) return false
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return day >= 1 && day <= days[month - 1]!
}
const uniqueEnums = <T extends string>(value: unknown, values: readonly T[]): value is T[] => Array.isArray(value) && value.every(item => oneOf(item, values)) && new Set(value).size === value.length
const cursorValid = (value: unknown): value is string => check(() => {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,8192}$/.test(value)) return false
  const decoded = atob(value.replaceAll('-', '+').replaceAll('_', '/'))
  return btoa(decoded).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '') === value
})
function equal(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) !== Array.isArray(b)) return false
  const keys = Object.keys(a)
  return keys.length === Object.keys(b).length && keys.every(key => Object.hasOwn(b, key) && equal((a as Record<string, unknown>)[key], (b as Record<string, unknown>)[key]))
}

export function growthTargetRefValid(value: unknown): value is TargetRef { return fields(value, ['itemId', 'versionId']) && todayIdValid(value.itemId) && todayIdValid(value.versionId) }
function questionRefValid(value: unknown): value is QuestionRef { return fields(value, ['questionId', 'versionId']) && todayIdValid(value.questionId) && todayIdValid(value.versionId) }
export function evidenceRefValid(value: unknown): value is EvidenceRef {
  return fields(value, ['kind', 'objectId', 'versionId', 'revision', 'partId']) && oneOf(value.kind, evidenceKinds)
    && todayIdValid(value.objectId) && (value.kind === 'RECOLLECTION' ? todayIdValid(value.versionId) : value.versionId === null)
    && (value.kind === 'MANUAL_FEEDBACK' ? integer(value.revision, 1, 100) : value.kind === 'REVIEWED_OBSERVATION' ? integer(value.revision) : value.revision === null)
    && (value.kind === 'AI_OBSERVATION' ? typeof value.partId === 'string' && /^f(?:[1-9]|1[0-2])$/.test(value.partId) : value.partId === null)
}
export function evidenceKey(value: EvidenceRef): string {
  return `${value.kind}:${value.objectId}:${value.versionId ?? '-'}:${value.revision ?? '-'}:${value.partId ?? '-'}`
}
export function growthCountValid(value: unknown): value is Count {
  return fields(value, ['value', 'mode']) && (value.mode === 'UNAVAILABLE' ? value.value === null : oneOf(value.mode, ['EXACT', 'LOWER_BOUND']) && integer(value.value))
}
export function growthCapabilityValid(value: unknown): value is Capability {
  if (!fields(value, ['status', 'reason'])) return false
  return value.status === 'READY' ? value.reason === 'NONE' : value.status === 'DEPENDENCY_PENDING' ? oneOf(value.reason, ['QUERY_NOT_INTEGRATED', 'REVIEWED_CHAIN_NOT_INTEGRATED'])
    : value.status === 'NOT_CONFIGURED' ? value.reason === 'MEMORY_SETTINGS_REQUIRED' : value.status === 'ERROR' && oneOf(value.reason, ['SOURCE_READ_FAILED', 'SOURCE_INTEGRITY_FAILED'])
}
export function growthContextValid(value: unknown): value is Context {
  // checkedAt 说明本次当前性核对时刻，不用它截断来源后来提交的修订或用户决定。
  return fields(value, ['asOf', 'checkedAt', 'serverTime']) && timestamp(value.asOf) && timestamp(value.checkedAt) && timestamp(value.serverTime)
    && beforeOrEqual(value.asOf, value.checkedAt) && beforeOrEqual(value.checkedAt, value.serverTime)
}
function filterValid(value: unknown): value is Filter {
  return fields(value, ['target', 'targetMode', 'topicId', 'topicMode', 'dimension']) && (value.target === null || growthTargetRefValid(value.target))
    && oneOf(value.targetMode, ['RECORDED_ONLY', 'INCLUDE_ACTIVE_LINKS']) && nullableId(value.topicId) && oneOf(value.topicMode, ['RECORDED_ONLY', 'INCLUDE_CURRENT_CLASSIFICATION'])
    && (value.dimension === null || oneOf(value.dimension, growthDimensions))
}
function progressFilterValid(value: unknown): value is ProgressFilter {
  return fields(value, ['target', 'topicId', 'topicMode', 'dimension']) && filterValid({ ...value, targetMode: 'RECORDED_ONLY' })
}
function dateCount(from: string, to: string): number { return (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000 + 1 }
export function growthDateRangeValid(from: unknown, to: unknown): boolean {
  return todayDateValid(from) && todayDateValid(to) && from <= to && to !== '9999-12-31' && dateCount(from, to) <= 366
}
function scopeValid(value: unknown, context: Context): value is Scope {
  if (!fields(value, ['window', 'filter']) || !filterValid(value.filter)) return false
  const window = value.window
  return fields(window, ['from', 'to', 'zoneId', 'startAt', 'endAt', 'observedUntil']) && growthDateRangeValid(window.from, window.to)
    && todayZoneValid(window.zoneId) && timestamp(window.startAt) && timestamp(window.endAt) && timestamp(window.observedUntil)
    && beforeOrEqual(window.startAt, window.endAt) && !sameTime(window.startAt, window.endAt)
    && sameTime(window.observedUntil, beforeOrEqual(window.endAt, context.asOf) ? window.endAt : context.asOf)
}
export function evidenceStateValid(value: unknown): value is EvidenceState {
  if (!fields(value, ['directState', 'recordCurrency', 'reviewFlags', 'lineageCheck']) || !oneOf(value.directState, ['AVAILABLE', 'REVIEW_REQUIRED', 'UNAVAILABLE', 'UNKNOWN'])
    || !oneOf(value.recordCurrency, ['CURRENT', 'SUPERSEDED', 'NOT_APPLICABLE']) || !uniqueEnums(value.reviewFlags, ['DISPUTED', 'REVIEW_REQUIRED', 'SOURCE_UNAVAILABLE'])
    || !oneOf(value.lineageCheck, ['NOT_APPLICABLE', 'PARTIAL'])) return false
  return (value.directState !== 'AVAILABLE' || value.reviewFlags.length === 0)
    && (!value.reviewFlags.includes('SOURCE_UNAVAILABLE') || value.directState === 'UNAVAILABLE')
    && (value.directState !== 'UNAVAILABLE' || value.reviewFlags.includes('SOURCE_UNAVAILABLE'))
}
export function evidenceStampValid(value: unknown, reference?: EvidenceRef): value is EvidenceStamp {
  return fields(value, ['ref', 'originHash', 'currentHash']) && evidenceRefValid(value.ref) && (reference === undefined || equal(value.ref, reference))
    && (value.originHash === null || hashValid(value.originHash)) && hashValid(value.currentHash)
}
function exposureValid(value: unknown): value is Exposure {
  return fields(value, ['authoredCount', 'referenceViewCount', 'evaluationAccessCount', 'lastExposureAt']) && integer(value.authoredCount)
    && integer(value.referenceViewCount) && integer(value.evaluationAccessCount) && nullableTime(value.lastExposureAt)
}
export function answerMetaValid(value: unknown): value is AnswerMeta {
  if (!fields(value, ['sessionId', 'questionId', 'answerId', 'mode', 'questionKind', 'condition', 'assistanceEventSequence', 'priorExposure', 'sourceQuestion', 'repractice', 'recordedFields'])
    || !todayIdValid(value.sessionId) || !todayIdValid(value.questionId) || !todayIdValid(value.answerId) || !oneOf(value.mode, ['LEARNING', 'FOCUSED', 'MOCK'])
    || !oneOf(value.questionKind, ['MAIN', 'FOLLOW_UP', 'REPLAY']) || !oneOf(value.condition, ['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'])
    || !integer(value.assistanceEventSequence, 0, 2000) || !exposureValid(value.priorExposure) || !(value.sourceQuestion === null || questionRefValid(value.sourceQuestion))
    || !fields(value.recordedFields, ['technology', 'scenario', 'conditions']) || !Object.values(value.recordedFields).every(item => typeof item === 'boolean')) return false
  // 成长沿用提交时的作答条件；追问不能借题库来源补出不存在的原题标准。
  if ((value.mode === 'LEARNING') !== (value.condition === 'LEARNING') || value.condition === 'ASSISTED' && value.assistanceEventSequence === 0
    || value.questionKind === 'MAIN' && value.sourceQuestion === null || value.questionKind === 'FOLLOW_UP' && value.sourceQuestion !== null) return false
  return value.repractice === null || fields(value.repractice, ['id', 'questionMode', 'baselineKind']) && todayIdValid(value.repractice.id)
    && oneOf(value.repractice.questionMode, ['SAME', 'VARIANT']) && oneOf(value.repractice.baselineKind, ['ANSWER', 'RECOLLECTION', 'NONE'])
}
function decisionValid(value: unknown): value is DecisionMeta {
  return fields(value, ['id', 'revision', 'choice', 'createdAt']) && todayIdValid(value.id) && integer(value.revision, 1, 20)
    && oneOf(value.choice, ['ACCEPTED', 'DISPUTED', 'IGNORED']) && timestamp(value.createdAt)
}
function manualMetaValid(value: unknown): value is ManualMeta {
  return fields(value, ['answer', 'kind', 'authorLabel', 'dimension', 'coverage', 'latestDecision']) && answerMetaValid(value.answer)
    && oneOf(value.kind, ['SELF_CHECK', 'EXTERNAL_FEEDBACK']) && textValid(value.authorLabel, 120)
    && (value.kind === 'SELF_CHECK' ? value.authorLabel === '' : sourceTextValid(value.authorLabel, 240, true))
    && oneOf(value.dimension, growthDimensions) && oneOf(value.coverage, ['MENTIONED', 'NOT_MENTIONED', 'UNCERTAIN']) && (value.latestDecision === null || decisionValid(value.latestDecision))
}
function aiMetaValid(value: unknown): value is AiMeta {
  return fields(value, ['answer', 'dimension', 'coverage', 'latestDecision', 'standard']) && answerMetaValid(value.answer)
    && oneOf(value.dimension, growthDimensions) && oneOf(value.coverage, ['MENTIONED', 'NOT_MENTIONED', 'UNCERTAIN']) && (value.latestDecision === null || decisionValid(value.latestDecision))
    && (value.standard === null || fields(value.standard, ['question', 'status']) && questionRefValid(value.standard.question) && value.standard.status === 'PERSONAL_UNREVIEWED'
      && value.answer.sourceQuestion !== null && equal(value.standard.question, value.answer.sourceQuestion))
}
function reviewedAnswerMeta(value: ReviewedMeta) {
  const answer = value.answer
  return { mode: answer.mode, answerCondition: answer.condition, assistanceEventSequence: answer.assistanceEventSequence, priorExposure: answer.priorExposure,
    questionKind: answer.questionKind, answerNumber: value.answerNumber, answerCreatedAt: value.answerCreatedAt, recordedFields: answer.recordedFields }
}
export function reviewedMetaValid(value: unknown, reference?: EvidenceRef): value is ReviewedMeta {
  return check(() => {
    if (!fields(value, ['answer', 'question', 'checkpointId', 'standardReview', 'change', 'recordedStatus', 'recordedAt', 'observedOn', 'observationZoneId', 'answerNumber', 'answerCreatedAt', 'coverage', 'conclusion', 'eligibility'])
      || !answerMetaValid(value.answer) || !questionRefValid(value.question) || !equal(value.answer.sourceQuestion, value.question) || value.answer.questionKind === 'FOLLOW_UP'
      || !todayIdValid(value.checkpointId) || !revisionRefValid(value.standardReview) || !oneOf(value.change, recordChanges) || !oneOf(value.recordedStatus, recordStatuses)
      || (value.change === 'WITHDRAWN') !== (value.recordedStatus === 'WITHDRAWN') || !timestamp(value.recordedAt) || !timestamp(value.answerCreatedAt)
      || !beforeOrEqual(value.answerCreatedAt, value.recordedAt) || !integer(value.answerNumber, 1, 100) || !todayDateValid(value.observedOn) || !todayZoneValid(value.observationZoneId)
      || !observationCoverageConclusionValid(value.coverage, value.conclusion) || !observationEligibilityValid(value.eligibility)) return false
    const meta = value as ReviewedMeta, answerMeta = reviewedAnswerMeta(meta)
    if (!observationAnswerMetaValid(answerMeta) || meta.eligibility.standardCurrentRevision < meta.standardReview.revision
      || meta.eligibility.reasons.includes('STANDARD_REVISED') !== (meta.eligibility.standardCurrentRevision > meta.standardReview.revision)
      || meta.eligibility.reasons.includes('OBSERVATION_DISPUTED') !== (meta.conclusion === 'DISPUTED')
      || meta.recordedStatus === 'WITHDRAWN' && (!meta.eligibility.revisionCurrent || meta.eligibility.currentStatus !== 'WITHDRAWN')) return false
    const date = (instant: string) => {
      const parts = new Intl.DateTimeFormat('en-US', { timeZone: meta.observationZoneId, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(instant))
      const part = (kind: string) => parts.find(item => item.type === kind)!.value
      return `${part('year').padStart(4, '0')}-${part('month')}-${part('day')}`
    }
    if (meta.observedOn < date(meta.answerCreatedAt) || meta.observedOn > date(meta.recordedAt)) return false
    if (reference === undefined) return true
    return evidenceRefValid(reference) && reference.kind === 'REVIEWED_OBSERVATION' && observationProjectionValid({ ref: { id: reference.objectId, revision: reference.revision },
      change: meta.change, recordedStatus: meta.recordedStatus, answer: { sessionId: meta.answer.sessionId, questionId: meta.answer.questionId, answerId: meta.answer.answerId },
      question: meta.question, checkpointId: meta.checkpointId, standardReview: meta.standardReview, recordedAt: meta.recordedAt, observedOn: meta.observedOn,
      observationZoneId: meta.observationZoneId, coverage: meta.coverage, conclusion: meta.conclusion, answerMeta, eligibility: meta.eligibility })
  })
}
function recollectionMetaValid(value: unknown): value is RecollectionMeta {
  return fields(value, ['versionNumber', 'baselineKind', 'interview', 'hasAnswerFragment', 'hasFeedback', 'hasUncertainty']) && integer(value.versionNumber, 1, 1000)
    && oneOf(value.baselineKind, ['RECOLLECTION', 'NONE']) && value.hasAnswerFragment === (value.baselineKind === 'RECOLLECTION')
    && typeof value.hasFeedback === 'boolean' && value.hasUncertainty === true
    && (value.interview === null || fields(value.interview, ['eventId', 'revision', 'startAt', 'zoneId', 'target']) && todayIdValid(value.interview.eventId)
      && integer(value.interview.revision) && timestamp(value.interview.startAt) && todayZoneValid(value.interview.zoneId) && (value.interview.target === null || growthTargetRefValid(value.interview.target)))
}
function replayMetaValid(value: unknown): value is ReplayMeta {
  return fields(value, ['sourceId', 'actionId', 'judgmentRevision', 'comparisonId', 'answer', 'questionMode', 'baselineKind', 'outcome', 'currentlyApplicable', 'recordedVerificationHash', 'currentVerificationHash'])
    && todayIdValid(value.sourceId) && todayIdValid(value.actionId) && integer(value.judgmentRevision, 1, 1000) && todayIdValid(value.comparisonId) && answerMetaValid(value.answer)
    && oneOf(value.questionMode, ['SAME', 'VARIANT']) && oneOf(value.baselineKind, ['ANSWER', 'RECOLLECTION', 'NONE']) && oneOf(value.outcome, ['HANDLED', 'PARTIAL', 'UNHANDLED', 'UNCERTAIN'])
    && typeof value.currentlyApplicable === 'boolean' && hashValid(value.recordedVerificationHash) && (value.currentVerificationHash === null || hashValid(value.currentVerificationHash))
    && (!value.currentlyApplicable || value.currentVerificationHash === value.recordedVerificationHash)
}
function ratingDayValid(value: unknown): value is RatingDay {
  return fields(value, ['zoneId', 'localDate', 'windowStart', 'windowEnd', 'settingsRevision']) && todayZoneValid(value.zoneId) && todayDateValid(value.localDate)
    && timestamp(value.windowStart) && timestamp(value.windowEnd) && beforeOrEqual(value.windowStart, value.windowEnd) && !sameTime(value.windowStart, value.windowEnd) && nullableRevision(value.settingsRevision)
}
function memoryMetaValid(value: unknown): value is MemoryMeta {
  if (!fields(value, ['cardId', 'cardVersionId', 'attemptId', 'attemptSequence', 'mode', 'countRegular', 'rating', 'ratingDay', 'budget', 'scheduleChangeId', 'scheduleSequence', 'thinkingElapsedMs', 'totalElapsedMs'])
    || !todayIdValid(value.cardId) || !todayIdValid(value.cardVersionId) || !todayIdValid(value.attemptId) || !integer(value.attemptSequence, 1) || !oneOf(value.mode, ['REGULAR', 'CRAM'])
    || typeof value.countRegular !== 'boolean' || value.mode === 'REGULAR' && !value.countRegular || !oneOf(value.rating, ['AGAIN', 'HARD', 'GOOD', 'EASY']) || !ratingDayValid(value.ratingDay)
    || !integer(value.thinkingElapsedMs) || !integer(value.totalElapsedMs, value.thinkingElapsedMs)) return false
  return value.countRegular ? todayIdValid(value.scheduleChangeId) && integer(value.scheduleSequence, 2) && integer(value.ratingDay.settingsRevision) && fields(value.budget, ['kind', 'firstForCardToday', 'firstCountedEventId'])
    && oneOf(value.budget.kind, ['NEW', 'REVIEW']) && typeof value.budget.firstForCardToday === 'boolean' && todayIdValid(value.budget.firstCountedEventId)
    : value.budget === null && value.scheduleChangeId === null && value.scheduleSequence === null && value.ratingDay.settingsRevision === null
}
const conditionKeys = { TRAINING_ANSWER: 'answer', MANUAL_FEEDBACK: 'manual', AI_OBSERVATION: 'ai', RECOLLECTION: 'recollection', REPLAY_JUDGMENT: 'replay', MEMORY_RATING: 'memory', REVIEWED_OBSERVATION: 'reviewed' } as const
export function evidenceConditionsValid(value: unknown, kind?: AvailableEvidenceKind): value is Conditions {
  if (!fields(value, ['kind', 'answer', 'manual', 'ai', 'recollection', 'replay', 'memory', 'reviewed']) || !oneOf(value.kind, evidenceKinds)
    || kind !== undefined && value.kind !== kind) return false
  const key = conditionKeys[value.kind]
  if (!Object.values(conditionKeys).every(name => name === key ? value[name] !== null : value[name] === null)) return false
  switch (value.kind) {
    case 'TRAINING_ANSWER': return answerMetaValid(value.answer)
    case 'MANUAL_FEEDBACK': return manualMetaValid(value.manual)
    case 'AI_OBSERVATION': return aiMetaValid(value.ai)
    case 'RECOLLECTION': return recollectionMetaValid(value.recollection)
    case 'REPLAY_JUDGMENT': return replayMetaValid(value.replay)
    case 'MEMORY_RATING': return memoryMetaValid(value.memory)
    case 'REVIEWED_OBSERVATION': return reviewedMetaValid(value.reviewed)
  }
}
function answerOf(value: Conditions): AnswerMeta | null {
  return value.kind === 'TRAINING_ANSWER' ? value.answer : value.kind === 'MANUAL_FEEDBACK' ? value.manual.answer
    : value.kind === 'AI_OBSERVATION' ? value.ai.answer : value.kind === 'REPLAY_JUDGMENT' ? value.replay.answer : value.kind === 'REVIEWED_OBSERVATION' ? value.reviewed.answer : null
}
function facetsValid(value: unknown, kind: AvailableEvidenceKind, expected?: Filter): value is Facets {
  if (!fields(value, ['targets', 'topics', 'dimensions', 'currentTopicClassificationAvailable', 'selectedTargetMatch']) || !Array.isArray(value.targets)
    || value.targets.length > (kind === 'MEMORY_RATING' ? 0 : kind === 'RECOLLECTION' ? 1 : 12)
    || !value.targets.every(item => fields(item, ['target', 'basis']) && growthTargetRefValid(item.target) && item.basis === 'RECORDED')
    || new Set(value.targets.map(item => `${item.target.itemId}/${item.target.versionId}`)).size !== value.targets.length || !Array.isArray(value.topics)
    || value.topics.length > (kind === 'MEMORY_RATING' ? 1 : kind === 'RECOLLECTION' ? 0 : 20)
    || !value.topics.every(item => fields(item, ['topicId', 'basis']) && todayIdValid(item.topicId) && item.basis === (kind === 'MEMORY_RATING' ? 'CURRENT_CLASSIFICATION' : 'RECORDED'))
    || new Set(value.topics.map(item => item.topicId)).size !== value.topics.length || !Array.isArray(value.dimensions) || value.dimensions.length > 6
    || !value.dimensions.every(item => fields(item, ['dimension', 'basis']) && oneOf(item.dimension, growthDimensions) && item.basis === 'RECORDED')
    || new Set(value.dimensions.map(item => item.dimension)).size !== value.dimensions.length || (kind === 'MEMORY_RATING' || kind === 'RECOLLECTION') && value.dimensions.length !== 0
    || value.currentTopicClassificationAvailable !== (kind === 'MEMORY_RATING') || !(value.selectedTargetMatch === null || fields(value.selectedTargetMatch, ['recorded', 'activeUserLink'])
      && typeof value.selectedTargetMatch.recorded === 'boolean' && typeof value.selectedTargetMatch.activeUserLink === 'boolean')) return false
  if (expected === undefined) return true
  if (expected.target === null ? value.selectedTargetMatch !== null : value.selectedTargetMatch === null) return false
  const target = expected.target
  if (target !== null && value.selectedTargetMatch !== null && (value.selectedTargetMatch.recorded !== value.targets.some(item => equal(item.target, target))
    || !(value.selectedTargetMatch.recorded || expected.targetMode === 'INCLUDE_ACTIVE_LINKS' && value.selectedTargetMatch.activeUserLink))) return false
  if (expected.topicId !== null && !value.topics.some(item => item.topicId === expected.topicId && (expected.topicMode === 'INCLUDE_CURRENT_CLASSIFICATION' || item.basis === 'RECORDED'))) return false
  return (kind !== 'MEMORY_RATING' || expected.topicId !== null || value.topics.length === 0)
    && (expected.dimension === null || value.dimensions.some(item => item.dimension === expected.dimension))
}
export function evidenceRouteValid(value: unknown, reference?: EvidenceRef, conditions?: Conditions): value is EvidenceRoute {
  if (!fields(value, ['route', 'ref', 'sessionId', 'questionId', 'answerId', 'comparisonId', 'cardId', 'attemptId']) || !evidenceRefValid(value.ref)
    || reference !== undefined && !equal(value.ref, reference)) return false
  const kind = value.ref.kind, answer = conditions === undefined ? null : answerOf(conditions)
  if (kind === 'RECOLLECTION') return value.route === 'RECOLLECTION_VERSION' && ['sessionId', 'questionId', 'answerId', 'comparisonId', 'cardId', 'attemptId'].every(key => value[key] === null)
  if (kind === 'MEMORY_RATING') return value.route === 'MEMORY_ATTEMPT' && value.sessionId === null && value.questionId === null && value.answerId === null && value.comparisonId === null
    && todayIdValid(value.cardId) && todayIdValid(value.attemptId) && (conditions === undefined || conditions.kind === kind && conditions.memory.cardId === value.cardId && conditions.memory.attemptId === value.attemptId)
  return value.route === (kind === 'REPLAY_JUDGMENT' ? 'REPLAY_COMPARISON' : kind) && todayIdValid(value.sessionId) && todayIdValid(value.questionId) && todayIdValid(value.answerId)
    && value.cardId === null && value.attemptId === null && (kind === 'REPLAY_JUDGMENT' ? todayIdValid(value.comparisonId) : value.comparisonId === null)
    && (kind !== 'TRAINING_ANSWER' || value.answerId === value.ref.objectId) && (answer === null || answer.sessionId === value.sessionId && answer.questionId === value.questionId && answer.answerId === value.answerId)
    && (conditions?.kind !== 'REPLAY_JUDGMENT' || value.comparisonId === conditions.replay.comparisonId)
}
export function evidenceSummaryValid(value: unknown, expected?: { kind?: AvailableEvidenceKind; filter?: Filter; window?: DateWindow }): value is EvidenceSummary {
  return check(() => {
    if (!fields(value, ['ref', 'occurredAt', 'title', 'recordKind', 'assessmentLevel', 'conditions', 'facets', 'state', 'stamp', 'route']) || !evidenceRefValid(value.ref)
      || expected?.kind !== undefined && value.ref.kind !== expected.kind || !timestamp(value.occurredAt) || !todayTitleValid(value.title) || !evidenceConditionsValid(value.conditions, value.ref.kind)
      || !facetsValid(value.facets, value.ref.kind, expected?.filter) || !evidenceStateValid(value.state) || !evidenceStampValid(value.stamp, value.ref) || value.stamp.originHash === null
      || !evidenceRouteValid(value.route, value.ref, value.conditions) || !bounded(value)) return false
    const conditions = value.conditions, kind = value.ref.kind
    const record = conditions.kind === 'MANUAL_FEEDBACK' ? conditions.manual.kind : ({ TRAINING_ANSWER: 'ANSWER', AI_OBSERVATION: 'AI_FEEDBACK', RECOLLECTION: 'INTERVIEW_RECOLLECTION', REPLAY_JUDGMENT: 'REPLAY_ASSESSMENT', MEMORY_RATING: 'MEMORY_SELF_RATING', REVIEWED_OBSERVATION: 'REVIEWED_CHECK' } as const)[kind as Exclude<AvailableEvidenceKind, 'MANUAL_FEEDBACK'>]
    if (value.recordKind !== record || value.assessmentLevel !== (kind === 'AI_OBSERVATION' ? 'AI_UNREVIEWED' : kind === 'REVIEWED_OBSERVATION' ? 'REVIEWED_OBSERVATION' : 'SELF_RECORDED')) return false
    if (expected?.window && (!beforeOrEqual(expected.window.startAt, value.occurredAt) || beforeOrEqual(expected.window.observedUntil, value.occurredAt))) return false
    if (conditions.kind === 'TRAINING_ANSWER' && conditions.answer.answerId !== value.ref.objectId) return false
    if ((conditions.kind === 'MANUAL_FEEDBACK' || conditions.kind === 'AI_OBSERVATION') && !equal(value.facets.dimensions,
      [{ dimension: conditions.kind === 'MANUAL_FEEDBACK' ? conditions.manual.dimension : conditions.ai.dimension, basis: 'RECORDED' }])) return false
    if (conditions.kind === 'RECOLLECTION' && !equal(value.facets.targets, conditions.recollection.interview?.target
      ? [{ target: conditions.recollection.interview.target, basis: 'RECORDED' }] : [])) return false
    const decision = conditions.kind === 'MANUAL_FEEDBACK' ? conditions.manual.latestDecision : conditions.kind === 'AI_OBSERVATION' ? conditions.ai.latestDecision : null
    if (decision !== null && !beforeOrEqual(value.occurredAt, decision.createdAt) || decision?.choice === 'DISPUTED' && !value.state.reviewFlags.includes('DISPUTED')) return false
    if (conditions.kind === 'MEMORY_RATING') {
      const memory = conditions.memory
      if (!beforeOrEqual(memory.ratingDay.windowStart, value.occurredAt) || beforeOrEqual(memory.ratingDay.windowEnd, value.occurredAt)
        || memory.budget !== null && memory.budget.firstForCardToday !== (memory.budget.firstCountedEventId === value.ref.objectId)) return false
    }
    if (conditions.kind === 'REVIEWED_OBSERVATION' && (!reviewedMetaValid(conditions.reviewed, value.ref) || !sameTime(value.occurredAt, conditions.reviewed.recordedAt)
      || !reviewedStateValid(value.state, conditions.reviewed))) return false
    return true
  })
}
function reviewedStateValid(state: EvidenceState, meta: ReviewedMeta): boolean {
  const eligibility = meta.eligibility, reasons = eligibility.reasons
  const flagOrder: readonly ReviewFlag[] = ['DISPUTED', 'REVIEW_REQUIRED', 'SOURCE_UNAVAILABLE']
  // 原答自身的直接依赖也会加强失效状态；观察资格只能规定最少应展示的原因。
  return state.lineageCheck === 'PARTIAL' && state.recordCurrency === (eligibility.revisionCurrent ? 'CURRENT' : 'SUPERSEDED')
    && state.reviewFlags.every((flag, index) => index === 0 || flagOrder.indexOf(state.reviewFlags[index - 1]!) < flagOrder.indexOf(flag))
    && (eligibility.eligibleAsReviewedObservation || state.directState !== 'AVAILABLE')
    && (!reasons.includes('OBSERVATION_DISPUTED') || state.reviewFlags.includes('DISPUTED'))
    && (!reasons.includes('BASIS_UNAVAILABLE') || state.directState === 'UNAVAILABLE' && state.reviewFlags.includes('SOURCE_UNAVAILABLE'))
    && (!reasons.some(reason => reason !== 'OBSERVATION_DISPUTED' && reason !== 'BASIS_UNAVAILABLE') || state.reviewFlags.includes('REVIEW_REQUIRED'))
}
export function evidenceResolutionValid(value: unknown, reference?: EvidenceRef, allowUnavailable = false): value is EvidenceResolution {
  return check(() => fields(value, ['ref', 'summary', 'state', 'stamp']) && evidenceRefValid(value.ref) && (reference === undefined || equal(value.ref, reference))
    && evidenceStateValid(value.state) && evidenceStampValid(value.stamp, value.ref)
    && (value.summary === null ? allowUnavailable && value.state.directState === 'UNAVAILABLE' && value.state.reviewFlags.includes('SOURCE_UNAVAILABLE') && value.stamp.originHash === null
      : evidenceSummaryValid(value.summary) && equal(value.summary.ref, value.ref) && equal(value.summary.state, value.state) && equal(value.summary.stamp, value.stamp)) && bounded(value))
}

function queryShape(value: unknown, required: readonly string[], optional: readonly string[]): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value) && required.every(key => Object.hasOwn(value, key))
    && Object.keys(value).every(key => required.includes(key) || optional.includes(key)))
}
const rangeQueryKeys = ['from', 'to', 'targetId', 'targetVersionId', 'targetMode', 'topicId', 'topicMode', 'dimension'] as const
const progressQueryKeys = ['targetId', 'targetVersionId', 'topicId', 'topicMode', 'dimension', 'disposition', 'cursor', 'limit'] as const
function targetQueryValid(value: Record<string, unknown>, required = false): boolean {
  return value.targetId === undefined && value.targetVersionId === undefined ? !required : todayIdValid(value.targetId) && todayIdValid(value.targetVersionId)
}
function classificationQueryValid(value: Record<string, unknown>): boolean {
  return targetQueryValid(value) && (value.topicId === undefined || todayIdValid(value.topicId)) && (value.topicMode === undefined || oneOf(value.topicMode, ['RECORDED_ONLY', 'INCLUDE_CURRENT_CLASSIFICATION']))
    && (value.dimension === undefined || oneOf(value.dimension, growthDimensions))
}
function windowQueryValid(value: Record<string, unknown>): boolean {
  return todayZoneValid(value.zoneId) && (value.from === undefined && value.to === undefined || growthDateRangeValid(value.from, value.to))
    && classificationQueryValid(value) && (value.targetMode === undefined || oneOf(value.targetMode, ['RECORDED_ONLY', 'INCLUDE_ACTIVE_LINKS']))
}
function pageQueryValid(value: Record<string, unknown>, max = 50): boolean {
  return (value.cursor === undefined || cursorValid(value.cursor)) && (value.limit === undefined || integer(value.limit, 1, max))
}
export function growthOverviewQueryValid(value: unknown): value is OverviewQuery { return queryShape(value, ['zoneId'], rangeQueryKeys) && windowQueryValid(value) }
export function evidenceQueryValid(value: unknown): value is EvidenceQuery {
  return queryShape(value, ['zoneId', 'kind'], [...rangeQueryKeys, 'level', 'reviewFlag', 'cursor', 'limit']) && windowQueryValid(value) && oneOf(value.kind, evidenceKinds)
    && (value.level === undefined || oneOf(value.level, ['SELF_RECORDED', 'AI_UNREVIEWED', 'REVIEWED_OBSERVATION']))
    && (value.reviewFlag === undefined || oneOf(value.reviewFlag, ['DISPUTED', 'REVIEW_REQUIRED', 'SOURCE_UNAVAILABLE'])) && pageQueryValid(value)
}
export function activityQueryValid(value: unknown): value is ActivityQuery {
  return queryShape(value, ['zoneId'], [...rangeQueryKeys.filter(key => key !== 'targetMode'), 'grain']) && windowQueryValid(value) && (value.grain === undefined || oneOf(value.grain, ['DAY', 'WEEK']))
}
export function sourceProgressQueryValid(value: unknown): value is SourceProgressQuery {
  return queryShape(value, [], progressQueryKeys) && classificationQueryValid(value) && (value.disposition === undefined || oneOf(value.disposition, sourceDispositions)) && pageQueryValid(value)
}
function targetFor(query: { targetId?: string; targetVersionId?: string }): TargetRef | null {
  return query.targetId === undefined ? null : { itemId: query.targetId, versionId: query.targetVersionId! }
}
function filterFor(query: Pick<OverviewQuery, 'targetId' | 'targetVersionId' | 'targetMode' | 'topicId' | 'topicMode' | 'dimension'>): Filter {
  return { target: targetFor(query), targetMode: query.targetMode ?? 'INCLUDE_ACTIVE_LINKS', topicId: query.topicId ?? null,
    topicMode: query.topicMode ?? 'INCLUDE_CURRENT_CLASSIFICATION', dimension: query.dimension ?? null }
}
function progressFilterFor(query: SourceProgressQuery): ProgressFilter {
  const { target, topicId, topicMode, dimension } = filterFor(query)
  return { target, topicId, topicMode, dimension }
}
function scopeMatches(scope: Scope, query: OverviewQuery | ActivityQuery, recorded = false): boolean {
  return scope.window.zoneId === query.zoneId && (query.from === undefined ? dateCount(scope.window.from, scope.window.to) === 28 : scope.window.from === query.from && scope.window.to === query.to)
    && equal(scope.filter, filterFor({ ...query, ...(recorded ? { targetMode: 'RECORDED_ONLY' } : {}) }))
}
function limitationListValid(value: unknown): value is Limitation[] { return uniqueEnums(value, growthLimitations) }
function capabilityCountValid(capability: Capability, count: Count, scanLimited: boolean, future = false): boolean {
  // 未发生的窗口和读取失败都不伪造零；LOWER_BOUND 仅是已核对下界，不能跨分页累加。
  return capability.status === 'READY' && !future ? count.mode !== 'UNAVAILABLE' && (!scanLimited || count.mode === 'LOWER_BOUND') : count.mode === 'UNAVAILABLE' && !scanLimited
}
function kindCountValid(value: unknown, kind: EvidenceKind, future = false): value is KindCount {
  return fields(value, ['kind', 'capability', 'count', 'scanLimited']) && value.kind === kind && growthCapabilityValid(value.capability) && growthCountValid(value.count)
    && typeof value.scanLimited === 'boolean' && capabilityCountValid(value.capability, value.count, value.scanLimited, future)
}
export function evidencePageValid(value: unknown, expected?: EvidenceQuery): value is EvidencePage {
  return check(() => {
    if (expected !== undefined && !evidenceQueryValid(expected) || !fields(value, ['context', 'scope', 'kind', 'level', 'reviewFlag', 'capability', 'count', 'scanLimited', 'scanned', 'items', 'nextCursor'])
      || !growthContextValid(value.context) || !scopeValid(value.scope, value.context) || !oneOf(value.kind, evidenceKinds) || !growthCapabilityValid(value.capability) || !growthCountValid(value.count)
      || typeof value.scanLimited !== 'boolean' || !integer(value.scanned, 0, 500) || !Array.isArray(value.items) || value.items.length > (expected === undefined ? 50 : expected.limit ?? 20)
      || !(value.nextCursor === null || cursorValid(value.nextCursor)) || !capabilityCountValid(value.capability, value.count, value.scanLimited, beforeOrEqual(value.context.asOf, value.scope.window.startAt))
      || !(value.level === null || oneOf(value.level, ['SELF_RECORDED', 'AI_UNREVIEWED', 'REVIEWED_OBSERVATION']))
      || !(value.reviewFlag === null || oneOf(value.reviewFlag, ['DISPUTED', 'REVIEW_REQUIRED', 'SOURCE_UNAVAILABLE'])) || !bounded(value)) return false
    if (expected !== undefined && (value.kind !== expected.kind || value.level !== (expected.level ?? null) || value.reviewFlag !== (expected.reviewFlag ?? null)
      || !scopeMatches(value.scope, expected) || value.nextCursor !== null && value.nextCursor === expected.cursor)) return false
    if (value.capability.status !== 'READY' || value.count.mode === 'UNAVAILABLE') return value.items.length === 0 && value.nextCursor === null && value.scanned === 0
    if (value.items.length > value.scanned || value.count.value! < value.items.length || value.scanLimited && (value.scanned !== 500 || value.nextCursor === null)) return false
    const kind = value.kind as AvailableEvidenceKind, scope = value.scope, level = value.level, reviewFlag = value.reviewFlag
    if (!value.items.every(item => evidenceSummaryValid(item, { kind, filter: scope.filter, window: scope.window })
      && (level === null || item.assessmentLevel === level) && (reviewFlag === null || item.state.reviewFlags.includes(reviewFlag)))) return false
    const items = value.items as EvidenceSummary[]
    return new Set(items.map(item => evidenceKey(item.ref))).size === items.length && items.every((item, index) => index === 0
      || memoryUtcMicros(items[index - 1]!.occurredAt)! > memoryUtcMicros(item.occurredAt)!
      || sameTime(items[index - 1]!.occurredAt, item.occurredAt) && evidenceKey(items[index - 1]!.ref) < evidenceKey(item.ref))
  })
}
function metricUnit(metric: ActivityMetric): MetricUnit {
  return metric === 'QUESTION_INSTANCES_ANSWERED' ? 'DISTINCT_QUESTION' : metric === 'MEMORY_CARDS_RATED' ? 'DISTINCT_CARD'
    : ['REPLAYS_STARTED', 'SAME_REPLAYS_STARTED', 'VARIANT_REPLAYS_STARTED'].includes(metric) ? 'REPLAY' : 'EVENT'
}
function metricValuesValid(value: unknown, future = false): value is MetricValue[] {
  if (!Array.isArray(value) || value.length !== activityMetrics.length || !value.every((item, index) => fields(item, ['metric', 'unit', 'capability', 'count', 'additive'])
    && item.metric === activityMetrics[index] && item.unit === metricUnit(activityMetrics[index]!) && item.additive === !['QUESTION_INSTANCES_ANSWERED', 'MEMORY_CARDS_RATED'].includes(activityMetrics[index]!)
    && growthCapabilityValid(item.capability) && growthCountValid(item.count) && (future ? item.count.mode === 'UNAVAILABLE'
      : item.capability.status === 'READY' ? item.count.mode !== 'UNAVAILABLE' : item.count.mode === 'UNAVAILABLE'))) return false
  const metrics = value as MetricValue[], exact = (metric: ActivityMetric) => metrics.find(item => item.metric === metric)!.count
  const partitions: ActivityMetric[][] = [['ANSWERS_SUBMITTED', 'ANSWERS_BEFORE_ASSISTANCE', 'ANSWERS_LEARNING', 'ANSWERS_ASSISTED'],
    ['REPLAYS_STARTED', 'SAME_REPLAYS_STARTED', 'VARIANT_REPLAYS_STARTED'], ['MEMORY_RATINGS', 'MEMORY_COUNTED_RATINGS', 'MEMORY_UNCOUNTED_RATINGS']]
  return partitions.every(group => group.some(metric => exact(metric).mode !== 'EXACT') || BigInt(exact(group[0]!).value!) === group.slice(1).reduce((sum, metric) => sum + BigInt(exact(metric).value!), 0n))
}
function activityTotalsValid(value: unknown, filter?: Filter, future = false): value is ActivityTotals {
  return fields(value, ['recordedFilter', 'values']) && filterValid(value.recordedFilter) && value.recordedFilter.targetMode === 'RECORDED_ONLY'
    && (filter === undefined || equal(value.recordedFilter, { ...filter, targetMode: 'RECORDED_ONLY' })) && metricValuesValid(value.values, future)
}
function nextDate(value: string): string { return new Date(Date.parse(`${value}T00:00:00Z`) + 86400000).toISOString().slice(0, 10) }
function bucketValid(value: unknown, context: Context, window: DateWindow, grain: Grain): value is ActivityBucket {
  if (!fields(value, ['from', 'to', 'startAt', 'endAt', 'state', 'clipped', 'values']) || !growthDateRangeValid(value.from, value.to)
    || (value.from as string) < window.from || (value.to as string) > window.to || !timestamp(value.startAt) || !timestamp(value.endAt)
    || !beforeOrEqual(value.startAt, value.endAt) || !oneOf(value.state, ['COMPLETE', 'PARTIAL_TODAY', 'FUTURE']) || typeof value.clipped !== 'boolean') return false
  const from = value.from as string, to = value.to as string, days = dateCount(from, to)
  const state = beforeOrEqual(context.asOf, value.startAt) ? 'FUTURE' : beforeOrEqual(value.endAt, context.asOf) ? 'COMPLETE' : 'PARTIAL_TODAY'
  if (value.state !== state || !metricValuesValid(value.values, state === 'FUTURE')) return false
  if (grain === 'DAY') return days === 1 && !value.clipped
  const weekday = new Date(`${from}T00:00:00Z`).getUTCDay(), lastWeekday = new Date(`${to}T00:00:00Z`).getUTCDay()
  return days <= 7 && (from === window.from || weekday === 1) && (to === window.to || lastWeekday === 0) && value.clipped === (weekday !== 1 || lastWeekday !== 0)
}
export function activitySeriesValid(value: unknown, expected?: ActivityQuery): value is ActivitySeries {
  return check(() => {
    if (expected !== undefined && !activityQueryValid(expected) || !fields(value, ['context', 'scope', 'grain', 'buckets', 'totals', 'limitations'])
      || !growthContextValid(value.context) || !scopeValid(value.scope, value.context) || value.scope.filter.targetMode !== 'RECORDED_ONLY' || !oneOf(value.grain, ['DAY', 'WEEK'])
      || !Array.isArray(value.buckets) || value.buckets.length < 1 || value.buckets.length > (value.grain === 'DAY' ? 366 : 54) || !limitationListValid(value.limitations)
      || !activityTotalsValid(value.totals, value.scope.filter, beforeOrEqual(value.context.asOf, value.scope.window.startAt)) || !bounded(value, 2097152)) return false
    if (expected !== undefined && (value.grain !== (expected.grain ?? 'DAY') || !scopeMatches(value.scope, expected, true))) return false
    const context = value.context, window = value.scope.window, grain = value.grain
    if (!value.buckets.every(item => bucketValid(item, context, window, grain))) return false
    const buckets = value.buckets as ActivityBucket[]
    return buckets[0]!.from === window.from && buckets.at(-1)!.to === window.to && sameTime(buckets[0]!.startAt, window.startAt) && sameTime(buckets.at(-1)!.endAt, window.endAt)
      && buckets.every((item, index) => index === 0 || item.from === nextDate(buckets[index - 1]!.to) && sameTime(buckets[index - 1]!.endAt, item.startAt))
  })
}
function sourceProgressItemValid(value: unknown): value is SourceProgressItem {
  if (!fields(value, ['sourceId', 'actionId', 'actionRevision', 'tracking', 'origin', 'latestJudgment', 'latestOutcome', 'domainApplicable', 'disposition', 'originState', 'verificationHash', 'continuation'])
    || !todayIdValid(value.sourceId) || !todayIdValid(value.actionId) || !integer(value.actionRevision, 0, 1000) || !oneOf(value.tracking, ['TRACKING', 'DROPPED'])
    || !evidenceRefValid(value.origin) || !['MANUAL_FEEDBACK', 'AI_OBSERVATION', 'RECOLLECTION'].includes(value.origin.kind)
    || !(value.latestJudgment === null || evidenceRefValid(value.latestJudgment) && value.latestJudgment.kind === 'REPLAY_JUDGMENT')
    || !(value.latestOutcome === null || oneOf(value.latestOutcome, ['HANDLED', 'PARTIAL', 'UNHANDLED', 'UNCERTAIN']))
    || (value.latestJudgment === null) !== (value.latestOutcome === null) || typeof value.domainApplicable !== 'boolean'
    || value.domainApplicable && (value.latestJudgment === null || value.tracking !== 'TRACKING') || !oneOf(value.disposition, sourceDispositions)
    || !evidenceStateValid(value.originState) || !hashValid(value.verificationHash) || !todayContinuationValid(value.continuation)
    || value.continuation.route !== 'REVIEW' || value.continuation.action?.action !== value.actionId) return false
  const disposition = value.tracking === 'DROPPED' ? 'DROPPED' : value.originState.directState === 'UNAVAILABLE' ? 'SOURCE_UNAVAILABLE'
    : value.originState.directState !== 'AVAILABLE' || value.latestJudgment !== null && !value.domainApplicable ? 'REVIEW_REQUIRED'
      : value.latestJudgment === null ? 'UNASSESSED' : value.latestOutcome
  // 最新判断的新答状态未包含在此投影中，服务端可据其直接依赖将当前结论标为待复核。
  return value.disposition === disposition
    || value.disposition === 'REVIEW_REQUIRED' && value.tracking === 'TRACKING' && value.originState.directState === 'AVAILABLE'
      && value.domainApplicable && value.latestJudgment !== null
}
function sourceProgressTotalsValid(value: unknown, expected?: ProgressFilter): value is SourceProgressTotals {
  if (!fields(value, ['filter', 'capability', 'counts', 'scanLimited']) || !progressFilterValid(value.filter) || expected !== undefined && !equal(value.filter, expected)
    || !growthCapabilityValid(value.capability) || typeof value.scanLimited !== 'boolean' || !Array.isArray(value.counts) || value.counts.length !== sourceDispositions.length) return false
  const capability = value.capability, scanLimited = value.scanLimited
  return value.counts.every((item, index) => fields(item, ['disposition', 'count']) && item.disposition === sourceDispositions[index]
    && growthCountValid(item.count) && capabilityCountValid(capability, item.count, scanLimited))
}
export function sourceProgressPageValid(value: unknown, expected?: SourceProgressQuery): value is SourceProgressPage {
  return check(() => {
    if (expected !== undefined && !sourceProgressQueryValid(expected) || !fields(value, ['context', 'filter', 'disposition', 'capability', 'count', 'scanLimited', 'scanned', 'items', 'nextCursor'])
      || !growthContextValid(value.context) || !progressFilterValid(value.filter) || !(value.disposition === null || oneOf(value.disposition, sourceDispositions))
      || !growthCapabilityValid(value.capability) || !growthCountValid(value.count) || typeof value.scanLimited !== 'boolean' || !integer(value.scanned, 0, 500)
      || !capabilityCountValid(value.capability, value.count, value.scanLimited) || !Array.isArray(value.items) || value.items.length > (expected === undefined ? 50 : expected.limit ?? 20)
      || !(value.nextCursor === null || cursorValid(value.nextCursor)) || !bounded(value)) return false
    if (expected !== undefined && (!equal(value.filter, progressFilterFor(expected)) || value.disposition !== (expected.disposition ?? null) || value.nextCursor !== null && value.nextCursor === expected.cursor)) return false
    if (value.capability.status !== 'READY') return value.items.length === 0 && value.nextCursor === null && value.scanned === 0
    if (value.items.length > value.scanned || value.count.value! < value.items.length || value.scanLimited && (value.scanned !== 500 || value.nextCursor === null)) return false
    const disposition = value.disposition
    if (!value.items.every(item => sourceProgressItemValid(item) && (disposition === null || item.disposition === disposition))) return false
    return (value.items as SourceProgressItem[]).every((item, index, all) => index === 0 || all[index - 1]!.sourceId < item.sourceId)
  })
}
export function growthOverviewValid(value: unknown, expected?: OverviewQuery): value is Overview {
  return check(() => {
    if (expected !== undefined && !growthOverviewQueryValid(expected) || !fields(value, ['context', 'scope', 'evidenceGroups', 'emptyState', 'activity', 'sourceProgress', 'limitations'])
      || !growthContextValid(value.context) || !scopeValid(value.scope, value.context) || !Array.isArray(value.evidenceGroups) || value.evidenceGroups.length !== evidenceKinds.length
      || !oneOf(value.emptyState, ['HAS_RECORDS', 'RECOLLECTION_ONLY', 'NO_RECORD', 'UNKNOWN'])
      || !activityTotalsValid(value.activity, value.scope.filter, beforeOrEqual(value.context.asOf, value.scope.window.startAt))
      || !sourceProgressTotalsValid(value.sourceProgress, (({ target, topicId, topicMode, dimension }) => ({ target, topicId, topicMode, dimension }))(value.scope.filter))
      || !limitationListValid(value.limitations) || !bounded(value)
      || expected !== undefined && !scopeMatches(value.scope, expected)) return false
    const future = beforeOrEqual(value.context.asOf, value.scope.window.startAt)
    if (!value.evidenceGroups.every((item, index) => kindCountValid(item, evidenceKinds[index]!, future))) return false
    const groups = value.evidenceGroups as KindCount[]
    const knownNonRecollection = groups.some(item => item.kind !== 'RECOLLECTION' && item.count.mode !== 'UNAVAILABLE' && item.count.value > 0)
    const unknown = groups.some(item => item.capability.status !== 'READY' || item.scanLimited || item.count.mode !== 'EXACT')
    const recollection = groups.some(item => item.kind === 'RECOLLECTION' && item.count.mode !== 'UNAVAILABLE' && item.count.value > 0)
    return value.emptyState === (knownNonRecollection ? 'HAS_RECORDS' : unknown ? 'UNKNOWN' : recollection ? 'RECOLLECTION_ONLY' : 'NO_RECORD')
  })
}

export function comparabilityValid(value: unknown, positive = false): value is Comparability {
  return fields(value, ['dimension', 'scenario', 'technology', 'assistance', 'date']) && Object.values(value).every(item => fields(item, ['state', 'reason'])
    && oneOf(item.state, ['MATCH', 'DIFFERENT', 'UNKNOWN']) && textValid(item.reason, 500, positive))
}
export function requirementMetaValid(value: unknown): value is RequirementMeta {
  return fields(value, ['id', 'ordinal', 'text', 'dimensions', 'priority']) && todayIdValid(value.id) && integer(value.ordinal, 1, 50) && sourceTextValid(value.text, 2000, true)
    && uniqueEnums(value.dimensions, growthDimensions) && value.dimensions.length >= 1 && integer(value.priority, 1, 5)
}
export function targetMetaValid(value: unknown, target?: TargetRef): value is TargetMeta {
  return fields(value, ['ref', 'title', 'capturedOn', 'itemRevision', 'status', 'currentVersionId', 'sourceGeneration']) && growthTargetRefValid(value.ref)
    && (target === undefined || equal(value.ref, target)) && sourceTextValid(value.title, 200, true) && (value.capturedOn === null || sourceCapturedOnValid(value.capturedOn))
    && integer(value.itemRevision) && oneOf(value.status, ['ACTIVE', 'ARCHIVED']) && todayIdValid(value.currentVersionId) && integer(value.sourceGeneration)
}
const currentTarget = (target: TargetMeta) => target.status === 'ACTIVE' && target.currentVersionId === target.ref.versionId
const positiveCoverage = (coverage: Coverage) => coverage === 'SUPPORTS' || coverage === 'PARTIAL'
export function evidenceUseValid(value: unknown, id?: string): value is EvidenceUse {
  return check(() => fields(value, ['id', 'revision', 'target', 'requirementId', 'evidence', 'coverage', 'comparability', 'reason', 'status', 'createdAt', 'updatedAt'])
    && todayIdValid(value.id) && (id === undefined || value.id === id) && integer(value.revision) && growthTargetRefValid(value.target) && todayIdValid(value.requirementId)
    && evidenceRefValid(value.evidence) && oneOf(value.coverage, ['SUPPORTS', 'PARTIAL', 'CONTEXT_ONLY', 'INSUFFICIENT'])
    && comparabilityValid(value.comparability, positiveCoverage(value.coverage)) && textValid(value.reason, 2000, true) && oneOf(value.status, ['ACTIVE', 'WITHDRAWN'])
    && timestamp(value.createdAt) && timestamp(value.updatedAt) && beforeOrEqual(value.createdAt, value.updatedAt)
    && (value.revision !== 0 || value.status === 'ACTIVE' && sameTime(value.createdAt, value.updatedAt)) && bounded(value, 32768))
}
function useIdentity(value: EvidenceUse): CreateUsePreview { return { target: value.target, requirementId: value.requirementId, evidence: value.evidence } }
function effectiveUseState(use: EvidenceUse, target: TargetMeta, evidence: EvidenceResolution): EffectiveUseState {
  if (use.status === 'WITHDRAWN') return 'WITHDRAWN'
  if (!currentTarget(target)) return 'TARGET_NOT_CURRENT'
  if (evidence.state.directState === 'UNAVAILABLE') return 'SOURCE_UNAVAILABLE'
  if (evidence.state.directState === 'UNKNOWN') return 'SOURCE_UNKNOWN'
  if (evidence.state.directState === 'REVIEW_REQUIRED') return 'SOURCE_REVIEW_REQUIRED'
  return positiveCoverage(use.coverage) ? 'EFFECTIVE_POSITIVE' : 'NON_POSITIVE'
}
export function useEntryValid(value: unknown, id?: string): value is UseEntry {
  return check(() => fields(value, ['use', 'target', 'requirement', 'evidence', 'effectiveState']) && evidenceUseValid(value.use, id) && targetMetaValid(value.target, value.use.target)
    && requirementMetaValid(value.requirement) && value.requirement.id === value.use.requirementId && evidenceResolutionValid(value.evidence, value.use.evidence, true)
    && value.effectiveState === effectiveUseState(value.use, value.target, value.evidence) && bounded(value))
}
export function useLookupValid(value: unknown, id?: string): value is UseLookup {
  return check(() => fields(value, ['context', 'entry']) && growthContextValid(value.context) && useEntryValid(value.entry, id) && bounded(value))
}
export function createUsePreviewValid(value: unknown): value is CreateUsePreview {
  return check(() => fields(value, ['target', 'requirementId', 'evidence']) && growthTargetRefValid(value.target) && todayIdValid(value.requirementId) && evidenceRefValid(value.evidence) && bounded(value, 262144))
}
export function changeUsePreviewValid(value: unknown): value is ChangeUsePreview { return fields(value, ['expectedRevision']) && integer(value.expectedRevision) }
export function useIdentityLookupValid(value: unknown, expected?: CreateUsePreview | UseIdentityQuery): value is UseIdentityLookup {
  return check(() => {
    const identity = expected === undefined ? undefined : 'target' in expected ? expected : identityFromQuery(expected)
    return (identity === undefined || createUsePreviewValid(identity)) && fields(value, ['context', 'identity', 'entry']) && growthContextValid(value.context)
      && createUsePreviewValid(value.identity) && (identity === undefined || equal(value.identity, identity))
      && (value.entry === null || useEntryValid(value.entry) && equal(useIdentity(value.entry.use), value.identity)) && bounded(value)
  })
}
function initialComparisonValid(value: Comparability, evidence: EvidenceResolution, requirement: RequirementMeta): boolean {
  const dimensions = evidence.summary?.facets.dimensions.map(item => item.dimension) ?? []
  const sameDimensions = dimensions.length === requirement.dimensions.length && dimensions.every(item => requirement.dimensions.includes(item))
  const dimension = sameDimensions ? 'MATCH' : dimensions.length > 0 && !dimensions.some(item => requirement.dimensions.includes(item)) ? 'DIFFERENT' : 'UNKNOWN'
  return value.dimension.state === dimension && ['scenario', 'technology', 'assistance', 'date'].every(key => value[key as keyof Comparability].state === 'UNKNOWN')
}
export function usePreviewValid(value: unknown, expected?: CreateUsePreview | ChangeUsePreview, previous?: string | EvidenceUse): value is UsePreview {
  return check(() => {
    if (!fields(value, ['context', 'intent', 'useId', 'useRevision', 'target', 'requirement', 'evidence', 'comparability', 'previewHash', 'limitations'])
      || !growthContextValid(value.context) || !oneOf(value.intent, ['CREATE', 'CHANGE']) || !targetMetaValid(value.target) || !requirementMetaValid(value.requirement)
      || !evidenceResolutionValid(value.evidence, undefined, value.intent === 'CHANGE') || !comparabilityValid(value.comparability)
      || !initialComparisonValid(value.comparability, value.evidence, value.requirement) || !hashValid(value.previewHash) || !limitationListValid(value.limitations)
      || !bounded(value)) return false
    if (value.intent === 'CREATE' ? value.useId !== null || value.useRevision !== null || !currentTarget(value.target) : !todayIdValid(value.useId) || !integer(value.useRevision)) return false
    if (expected !== undefined) {
      if ('target' in expected) {
        if (!createUsePreviewValid(expected) || value.intent !== 'CREATE' || !equal(value.target.ref, expected.target) || value.requirement.id !== expected.requirementId || !equal(value.evidence.ref, expected.evidence)) return false
      } else if (!changeUsePreviewValid(expected) || value.intent !== 'CHANGE' || value.useRevision !== expected.expectedRevision) return false
    }
    if (previous === undefined) return true
    if (typeof previous === 'string') return todayIdValid(previous) && value.intent === 'CHANGE' && value.useId === previous
    return evidenceUseValid(previous) && value.intent === 'CHANGE' && value.useId === previous.id && value.useRevision === previous.revision
      && equal(value.target.ref, previous.target) && value.requirement.id === previous.requirementId && equal(value.evidence.ref, previous.evidence)
  })
}
function useContentValid(value: Record<string, unknown>): boolean {
  return oneOf(value.coverage, ['SUPPORTS', 'PARTIAL', 'CONTEXT_ONLY', 'INSUFFICIENT']) && comparabilityValid(value.comparability, positiveCoverage(value.coverage))
    && textValid(value.reason, 2000, true) && value.confirmed === true && hashValid(value.expectedPreviewHash)
}
export function createUseValid(value: unknown, preview?: UsePreview): value is CreateUse {
  return check(() => fields(value, ['request', 'expectedPreviewHash', 'coverage', 'comparability', 'reason', 'confirmed']) && createUsePreviewValid(value.request)
    && useContentValid(value) && bounded(value, 262144) && (preview === undefined || usePreviewValid(preview, value.request) && preview.intent === 'CREATE'
      && preview.previewHash === value.expectedPreviewHash && (!positiveCoverage(value.coverage as Coverage) || preview.evidence.state.directState === 'AVAILABLE')))
}
export function changeUseValid(value: unknown, preview?: UsePreview): value is ChangeUse {
  return check(() => fields(value, ['expectedRevision', 'expectedPreviewHash', 'coverage', 'comparability', 'reason', 'status', 'confirmed'])
    && integer(value.expectedRevision) && oneOf(value.status, ['ACTIVE', 'WITHDRAWN']) && useContentValid(value) && bounded(value, 262144)
    && (preview === undefined || usePreviewValid(preview, { expectedRevision: value.expectedRevision }) && preview.intent === 'CHANGE' && preview.previewHash === value.expectedPreviewHash
      && (value.status === 'WITHDRAWN' || currentTarget(preview.target) && (!positiveCoverage(value.coverage as Coverage) || preview.evidence.state.directState === 'AVAILABLE'))))
}
export function createdEvidenceUseValid(value: unknown, expected: CreateUse, preview?: UsePreview): value is EvidenceUse {
  return createUseValid(expected, preview) && evidenceUseValid(value) && value.revision === 0 && value.status === 'ACTIVE' && equal(useIdentity(value), expected.request)
    && value.coverage === expected.coverage && equal(value.comparability, expected.comparability) && value.reason === expected.reason
}
export function savedEvidenceUseValid(value: unknown, id: string, expected: ChangeUse, previous?: EvidenceUse, preview?: UsePreview): value is EvidenceUse {
  // 幂等恢复返回原成功快照；只核对原请求和原基线，当前来源有效性由后续 lookup 另读。
  if (!changeUseValid(expected, preview) || !evidenceUseValid(value, id) || value.coverage !== expected.coverage || !equal(value.comparability, expected.comparability)
    || value.reason !== expected.reason || value.status !== expected.status || value.revision !== expected.expectedRevision && value.revision !== expected.expectedRevision + 1) return false
  if (preview !== undefined && (preview.useId !== id || !equal(value.target, preview.target.ref) || value.requirementId !== preview.requirement.id || !equal(value.evidence, preview.evidence.ref))) return false
  if (previous === undefined) return true
  if (!evidenceUseValid(previous, id) || previous.revision !== expected.expectedRevision || !equal(useIdentity(value), useIdentity(previous)) || value.createdAt !== previous.createdAt || !beforeOrEqual(previous.updatedAt, value.updatedAt)) return false
  const unchanged = value.coverage === previous.coverage && equal(value.comparability, previous.comparability) && value.reason === previous.reason && value.status === previous.status
  return unchanged ? equal(value, previous) : value.revision === previous.revision + 1
}
export function useDirectoryQueryValid(value: unknown): value is UseDirectoryQuery {
  return queryShape(value, ['targetId', 'targetVersionId'], ['requirementId', 'status', 'cursor', 'limit']) && targetQueryValid(value, true)
    && (value.requirementId === undefined || todayIdValid(value.requirementId)) && (value.status === undefined || oneOf(value.status, ['ACTIVE', 'WITHDRAWN'])) && pageQueryValid(value)
}
function descendingCreation(rows: { id: string; createdAt: string }[]): boolean {
  return new Set(rows.map(item => item.id)).size === rows.length && rows.every((item, index) => index === 0 || memoryUtcMicros(rows[index - 1]!.createdAt)! > memoryUtcMicros(item.createdAt)!
    || sameTime(rows[index - 1]!.createdAt, item.createdAt) && rows[index - 1]!.id < item.id)
}
export function usePageValid(value: unknown, expected?: UseDirectoryQuery): value is UsePage {
  return check(() => {
    if (expected !== undefined && !useDirectoryQueryValid(expected) || !fields(value, ['context', 'target', 'requirementId', 'status', 'items', 'nextCursor']) || !growthContextValid(value.context)
      || !growthTargetRefValid(value.target) || !nullableId(value.requirementId) || !(value.status === null || oneOf(value.status, ['ACTIVE', 'WITHDRAWN']))
      || !Array.isArray(value.items) || value.items.length > (expected === undefined ? 50 : expected.limit ?? 20) || !(value.nextCursor === null || cursorValid(value.nextCursor)) || !bounded(value)) return false
    if (expected !== undefined && (!equal(value.target, targetFor(expected)) || value.requirementId !== (expected.requirementId ?? null) || value.status !== (expected.status ?? null)
      || value.nextCursor !== null && value.nextCursor === expected.cursor)) return false
    const target = value.target, requirementId = value.requirementId, status = value.status, asOf = value.context.asOf
    if (!value.items.every(item => useEntryValid(item) && equal(item.use.target, target) && (requirementId === null || item.use.requirementId === requirementId)
      && (status === null || item.use.status === status) && beforeOrEqual(item.use.createdAt, asOf))) return false
    const items = value.items as UseEntry[]
    return descendingCreation(items.map(item => item.use)) && new Set(items.map(item => `${item.use.requirementId}/${evidenceKey(item.use.evidence)}`)).size === items.length
      && (value.nextCursor === null || items.length > 0 && (expected === undefined || items.length === (expected.limit ?? 20)))
  })
}
function historyQueryValid(value: unknown): value is HistoryQuery {
  return queryShape(value, [], ['before', 'limit']) && (value.before === undefined || integer(value.before)) && (value.limit === undefined || integer(value.limit, 1, 50))
}
export function useHistoryPageValid(value: unknown, id?: string, expected: HistoryQuery = {}): value is UseHistoryPage {
  return check(() => {
    if (id !== undefined && !todayIdValid(id) || !historyQueryValid(expected) || !fields(value, ['items', 'nextBefore']) || !Array.isArray(value.items)
      || value.items.length > (expected.limit ?? 20) || value.items.length === 0 && expected.before !== 0 || !(value.nextBefore === null || integer(value.nextBefore, 1)) || !bounded(value)) return false
    let previous: UseHistory | undefined
    for (const entry of value.items) {
      if (!fields(entry, ['change', 'snapshot', 'sourceStamp', 'targetStamp', 'previewHash', 'createdAt']) || !evidenceUseValid(entry.snapshot, id)
        || !oneOf(entry.change, ['CREATED', 'UPDATED', 'WITHDRAWN', 'RESTORED']) || !evidenceStampValid(entry.sourceStamp, entry.snapshot.evidence)
        || !hashValid(entry.targetStamp) || !hashValid(entry.previewHash) || !timestamp(entry.createdAt) || !sameTime(entry.createdAt, entry.snapshot.updatedAt)
        || (entry.change === 'CREATED') !== (entry.snapshot.revision === 0) || entry.change === 'CREATED' && entry.sourceStamp.originHash === null
        || entry.change === 'WITHDRAWN' && entry.snapshot.status !== 'WITHDRAWN'
        || (entry.change === 'RESTORED' || entry.change === 'CREATED') && entry.snapshot.status !== 'ACTIVE'
        || expected.before !== undefined && entry.snapshot.revision >= expected.before || !bounded(entry, 32768)) return false
      const item = entry as UseHistory
      if (previous !== undefined && (item.snapshot.revision !== previous.snapshot.revision - 1 || item.snapshot.id !== previous.snapshot.id
        || !equal(useIdentity(item.snapshot), useIdentity(previous.snapshot)) || item.snapshot.createdAt !== previous.snapshot.createdAt || !beforeOrEqual(item.createdAt, previous.createdAt)
        || (previous.change === 'RESTORED' ? item.snapshot.status !== 'WITHDRAWN' : previous.change === 'WITHDRAWN' ? item.snapshot.status !== 'ACTIVE'
          : previous.change !== 'UPDATED' || item.snapshot.status !== previous.snapshot.status))) return false
      previous = item
    }
    return value.nextBefore === null ? previous === undefined || previous.snapshot.revision === 0
      : previous !== undefined && value.items.length === (expected.limit ?? 20) && value.nextBefore === previous.snapshot.revision
  })
}
function requirementCoverageValid(value: unknown, capability: Capability, scanLimited: boolean): value is RequirementCoverage {
  if (!fields(value, ['requirement', 'counts', 'noActiveLinkedEvidence']) || !requirementMetaValid(value.requirement) || !Array.isArray(value.counts)
    || value.counts.length !== coverageMetrics.length || !value.counts.every((item, index) => fields(item, ['metric', 'count']) && item.metric === coverageMetrics[index]
      && growthCountValid(item.count) && capabilityCountValid(capability, item.count, scanLimited)) || !(value.noActiveLinkedEvidence === null || typeof value.noActiveLinkedEvidence === 'boolean')) return false
  const counts = value.counts as CoverageCount[], active = counts[0]!.count
  if (value.noActiveLinkedEvidence !== (active.mode === 'EXACT' && active.value === 0 ? true : active.mode !== 'UNAVAILABLE' && active.value > 0 ? false : null)) return false
  if (counts.some(item => item.count.mode !== 'EXACT')) return true
  const at = (metric: CoverageMetric) => BigInt(counts.find(item => item.metric === metric)!.count.value!)
  return at('ACTIVE_USES') === at('SAVED_SUPPORTS') + at('SAVED_PARTIAL') + at('CONTEXT_ONLY') + at('INSUFFICIENT')
    && at('SAVED_SUPPORTS') + at('SAVED_PARTIAL') === at('EFFECTIVE_POSITIVE') + at('INVALIDATED_POSITIVE')
}
export function targetCoverageValid(value: unknown, target?: TargetRef): value is TargetCoverage {
  return check(() => {
    if (!fields(value, ['context', 'target', 'currentTarget', 'capability', 'requirements', 'scanLimited', 'scanned']) || !growthContextValid(value.context)
      || !targetMetaValid(value.target, target) || value.currentTarget !== currentTarget(value.target) || !growthCapabilityValid(value.capability)
      || typeof value.scanLimited !== 'boolean' || !integer(value.scanned, 0, 500) || !Array.isArray(value.requirements) || value.requirements.length > 50 || !bounded(value)) return false
    const capability = value.capability, scanLimited = value.scanLimited
    if (!value.requirements.every(item => requirementCoverageValid(item, capability, scanLimited))) return false
    const requirements = value.requirements as RequirementCoverage[]
    return new Set(requirements.map(item => item.requirement.id)).size === requirements.length && requirements.every((item, index) => item.requirement.ordinal === index + 1)
      && (value.currentTarget || requirements.every(item => item.counts.find(count => count.metric === 'EFFECTIVE_POSITIVE')!.count.value === null
        || item.counts.find(count => count.metric === 'EFFECTIVE_POSITIVE')!.count.value === 0))
  })
}

function requirementIdentityValid(value: unknown): value is RequirementIdentity {
  return fields(value, ['target', 'requirementId']) && growthTargetRefValid(value.target) && todayIdValid(value.requirementId)
}
function growthSourceRefValid(value: unknown): value is GrowthSourceRef {
  return todaySourceRefValid(value) && oneOf(value.kind, ['REVIEW_ACTION', 'MEMORY_CARD', 'PREPARATION_TASK'])
}
export function subjectIdentityValid(value: unknown): value is SubjectIdentity {
  if (!fields(value, ['kind', 'source', 'requirement'])) return false
  return value.kind === 'SOURCE' ? growthSourceRefValid(value.source) && value.requirement === null
    : value.kind === 'TARGET_REQUIREMENT' && value.source === null && requirementIdentityValid(value.requirement)
}
export function subjectValid(value: unknown): value is Subject {
  if (!fields(value, ['kind', 'source', 'sourceStamp', 'requirement', 'targetRevision'])) return false
  return value.kind === 'SOURCE' ? growthSourceRefValid(value.source) && todaySourceStampValid(value.sourceStamp, value.source.kind) && value.requirement === null && value.targetRevision === null
    : value.kind === 'TARGET_REQUIREMENT' && value.source === null && value.sourceStamp === null && requirementIdentityValid(value.requirement) && integer(value.targetRevision)
}
export function subjectIdentity(value: Subject): SubjectIdentity {
  return value.kind === 'SOURCE' ? { kind: value.kind, source: value.source, requirement: null } : { kind: value.kind, source: null, requirement: value.requirement }
}
export function subjectKey(value: SubjectIdentity | Subject): string {
  return value.kind === 'SOURCE' ? `${value.source.kind}:${value.source.id}` : `${value.requirement.target.itemId}:${value.requirement.target.versionId}:${value.requirement.requirementId}`
}
export function nextStepContinuationValid(value: unknown, subject?: SubjectIdentity | Subject): value is NextStepContinuation {
  if (!fields(value, ['route', 'source', 'requirement'])) return false
  if (value.route === 'TARGET_REQUIREMENT') return value.source === null && requirementIdentityValid(value.requirement)
    && (subject === undefined || subject.kind === 'TARGET_REQUIREMENT' && equal(value.requirement, subject.requirement))
  if (value.route !== 'SOURCE' || value.requirement !== null || !todayContinuationValid(value.source)) return false
  if (subject === undefined) return true
  if (subject.kind !== 'SOURCE') return false
  const source = subject.source, continuation = value.source
  if (source.kind === 'REVIEW_ACTION') return continuation.route === 'REVIEW' && continuation.action?.action === source.id
  if (source.kind === 'PREPARATION_TASK') return continuation.route === 'PREPARATION_TASKS' && continuation.item?.item === source.id
  return continuation.route === 'MEMORY_CARDS' && continuation.item?.item === source.id || continuation.route === 'MEMORY_RECALL'
    && (!('sourceStamp' in subject) || continuation.attempt === null || subject.sourceStamp.kind === 'MEMORY_CARD' && continuation.attempt.attempt === subject.sourceStamp.memory.attemptId)
}
export function nextStepValid(value: unknown): value is NextStep {
  if (!fields(value, ['subject', 'title', 'reasonCodes', 'effectivePriority', 'priorityBasis', 'preferenceId', 'preferenceRevision', 'continuation']) || !subjectValid(value.subject)
    || !(value.subject.kind === 'TARGET_REQUIREMENT' ? targetRequirementTitleValid(value.title) : todayTitleValid(value.title))
    || !uniqueEnums(value.reasonCodes, nextStepReasons) || value.reasonCodes.length < 1 || !integer(value.effectivePriority, 1, 5)
    || !oneOf(value.priorityBasis, ['PLACEMENT', 'TARGET_REQUIREMENT', 'DEFAULT']) || !nullableId(value.preferenceId) || !nullableRevision(value.preferenceRevision)
    || (value.preferenceId === null) !== (value.preferenceRevision === null) || !nextStepContinuationValid(value.continuation, value.subject)) return false
  if (value.subject.kind === 'TARGET_REQUIREMENT') return value.priorityBasis === 'TARGET_REQUIREMENT' && equal(value.reasonCodes, ['USER_MARKED_INSUFFICIENT'])
  const allowed = value.subject.source.kind === 'REVIEW_ACTION' ? ['ACTION_PENDING', 'ACTION_REVIEW_REQUIRED']
    : value.subject.source.kind === 'MEMORY_CARD' ? ['MEMORY_DUE', 'MEMORY_REVIEW_REQUIRED'] : ['TASK_UNSCHEDULED']
  return value.priorityBasis !== 'TARGET_REQUIREMENT' && (value.priorityBasis !== 'DEFAULT' || value.effectivePriority === 3) && value.reasonCodes.every(reason => allowed.includes(reason))
}
const recommendationOrder: readonly NextStepReason[] = ['ACTION_REVIEW_REQUIRED', 'MEMORY_REVIEW_REQUIRED', 'ACTION_PENDING', 'MEMORY_DUE', 'TASK_UNSCHEDULED', 'USER_MARKED_INSUFFICIENT']
function nextStepOrder(left: NextStep, right: NextStep): number {
  if (left.effectivePriority !== right.effectivePriority) return right.effectivePriority - left.effectivePriority
  const leftReason = Math.min(...left.reasonCodes.map(reason => recommendationOrder.indexOf(reason))), rightReason = Math.min(...right.reasonCodes.map(reason => recommendationOrder.indexOf(reason)))
  if (leftReason !== rightReason) return leftReason - rightReason
  const leftKey = subjectKey(left.subject), rightKey = subjectKey(right.subject)
  return leftKey < rightKey ? -1 : leftKey > rightKey ? 1 : 0
}
export function nextStepsQueryValid(value: unknown): value is NextStepsQuery {
  return queryShape(value, ['zoneId'], ['targetId', 'targetVersionId', 'limit']) && todayZoneValid(value.zoneId) && targetQueryValid(value)
    && (value.limit === undefined || integer(value.limit, 1, 20))
}
export function nextStepsValid(value: unknown, expected?: NextStepsQuery): value is NextSteps {
  return check(() => {
    if (expected !== undefined && !nextStepsQueryValid(expected) || !fields(value, ['context', 'scope', 'capability', 'items', 'examinedCandidates', 'scanLimited', 'matchingNotShown', 'limitations'])
      || !growthContextValid(value.context) || !fields(value.scope, ['zoneId', 'localDate', 'target']) || !todayZoneValid(value.scope.zoneId) || !todayDateValid(value.scope.localDate)
      || !(value.scope.target === null || growthTargetRefValid(value.scope.target)) || !growthCapabilityValid(value.capability) || !Array.isArray(value.items)
      || value.items.length > (expected === undefined ? 20 : expected.limit ?? 10) || !integer(value.examinedCandidates, 0, 1550) || typeof value.scanLimited !== 'boolean'
      || !growthCountValid(value.matchingNotShown) || !limitationListValid(value.limitations) || !value.limitations.includes('NEXT_STEPS_BOUNDED') || !bounded(value)) return false
    if (expected !== undefined && (value.scope.zoneId !== expected.zoneId || !equal(value.scope.target, targetFor(expected)))) return false
    if (value.capability.status !== 'READY') return value.items.length === 0 && value.matchingNotShown.mode === 'UNAVAILABLE'
    if (value.matchingNotShown.mode === 'UNAVAILABLE' || value.scanLimited && value.matchingNotShown.mode !== 'LOWER_BOUND' || value.items.length > value.examinedCandidates
      || !value.items.every(nextStepValid)) return false
    const items = value.items as NextStep[], target = value.scope.target
    return new Set(items.map(item => subjectKey(item.subject))).size === items.length && items.every((item, index) => (index === 0 || nextStepOrder(items[index - 1]!, item) < 0)
      && (target === null || (item.subject.kind === 'SOURCE' ? item.subject.source.kind !== 'MEMORY_CARD' : equal(item.subject.requirement.target, target))))
  })
}
export function nextStepPreferenceValid(value: unknown, id?: string): value is NextStepPreference {
  return check(() => fields(value, ['id', 'revision', 'subject', 'visibility', 'createdAt', 'updatedAt']) && todayIdValid(value.id) && (id === undefined || value.id === id)
    && integer(value.revision) && subjectIdentityValid(value.subject) && oneOf(value.visibility, ['VISIBLE', 'HIDDEN']) && timestamp(value.createdAt) && timestamp(value.updatedAt)
    && beforeOrEqual(value.createdAt, value.updatedAt) && (value.revision !== 0 || sameTime(value.createdAt, value.updatedAt)) && bounded(value, 32768))
}
export function createPreferenceValid(value: unknown): value is CreatePreference {
  return check(() => fields(value, ['subject', 'visibility']) && subjectValid(value.subject) && oneOf(value.visibility, ['VISIBLE', 'HIDDEN']) && bounded(value, 262144))
}
export function savePreferenceValid(value: unknown, previous?: NextStepPreference): value is SavePreference {
  return check(() => fields(value, ['expectedRevision', 'expectedSubject', 'visibility']) && integer(value.expectedRevision) && oneOf(value.visibility, ['VISIBLE', 'HIDDEN'])
    && (value.expectedSubject === null ? value.visibility === 'VISIBLE' : subjectValid(value.expectedSubject)) && bounded(value, 262144)
    && (previous === undefined || nextStepPreferenceValid(previous) && previous.revision === value.expectedRevision
      && (value.expectedSubject === null || subjectValid(value.expectedSubject) && equal(subjectIdentity(value.expectedSubject), previous.subject))))
}
export function createdNextStepPreferenceValid(value: unknown, expected: CreatePreference): value is NextStepPreference {
  return createPreferenceValid(expected) && nextStepPreferenceValid(value) && value.revision === 0 && value.visibility === expected.visibility && equal(value.subject, subjectIdentity(expected.subject))
}
export function savedNextStepPreferenceValid(value: unknown, id: string, expected: SavePreference, previous?: NextStepPreference): value is NextStepPreference {
  if (!savePreferenceValid(expected, previous) || !nextStepPreferenceValid(value, id) || value.visibility !== expected.visibility
    || expected.expectedSubject !== null && !equal(value.subject, subjectIdentity(expected.expectedSubject))) return false
  if (previous === undefined) return value.revision === expected.expectedRevision || value.revision === expected.expectedRevision + 1
  return previous.id === id && equal(value.subject, previous.subject) && value.createdAt === previous.createdAt && beforeOrEqual(previous.updatedAt, value.updatedAt)
    && (previous.visibility === expected.visibility ? equal(value, previous) : value.revision === expected.expectedRevision + 1)
}
export function preferenceEntryValid(value: unknown, id?: string): value is PreferenceEntry {
  return check(() => {
    if (!fields(value, ['preference', 'currentSubject', 'title', 'continuation', 'subjectState']) || !nextStepPreferenceValid(value.preference, id)
      || !(value.preference.subject.kind === 'TARGET_REQUIREMENT' ? targetRequirementTitleValid(value.title) : todayTitleValid(value.title))
      || !oneOf(value.subjectState, ['AVAILABLE', 'REVIEW_REQUIRED', 'UNAVAILABLE', 'UNKNOWN'])) return false
    return (value.currentSubject === null ? value.continuation === null && value.subjectState === 'UNAVAILABLE'
      : subjectValid(value.currentSubject) && equal(subjectIdentity(value.currentSubject), value.preference.subject) && nextStepContinuationValid(value.continuation, value.currentSubject)) && bounded(value)
  })
}
export function preferenceLookupValid(value: unknown, id?: string): value is PreferenceLookup {
  return check(() => fields(value, ['context', 'entry']) && growthContextValid(value.context) && preferenceEntryValid(value.entry, id) && bounded(value))
}
export function preferenceSubjectLookupValid(value: unknown, expected?: SubjectIdentity | PreferenceSubjectQuery): value is PreferenceSubjectLookup {
  return check(() => {
    const identity = expected === undefined ? undefined : 'kind' in expected ? expected : subjectFromQuery(expected)
    return (identity === undefined || subjectIdentityValid(identity)) && fields(value, ['context', 'subject', 'entry']) && growthContextValid(value.context) && subjectIdentityValid(value.subject)
      && (identity === undefined || equal(value.subject, identity)) && (value.entry === null || preferenceEntryValid(value.entry) && equal(value.entry.preference.subject, value.subject)) && bounded(value)
  })
}
export function preferenceQueryValid(value: unknown): value is PreferenceQuery {
  return queryShape(value, [], ['visibility', 'subjectKind', 'cursor', 'limit']) && (value.visibility === undefined || oneOf(value.visibility, ['VISIBLE', 'HIDDEN']))
    && (value.subjectKind === undefined || oneOf(value.subjectKind, ['SOURCE', 'TARGET_REQUIREMENT'])) && pageQueryValid(value)
}
export function preferencePageValid(value: unknown, expected?: PreferenceQuery): value is PreferencePage {
  return check(() => {
    if (expected !== undefined && !preferenceQueryValid(expected) || !fields(value, ['context', 'visibility', 'subjectKind', 'items', 'nextCursor']) || !growthContextValid(value.context)
      || !(value.visibility === null || oneOf(value.visibility, ['VISIBLE', 'HIDDEN'])) || !(value.subjectKind === null || oneOf(value.subjectKind, ['SOURCE', 'TARGET_REQUIREMENT']))
      || !Array.isArray(value.items) || value.items.length > (expected === undefined ? 50 : expected.limit ?? 20) || !(value.nextCursor === null || cursorValid(value.nextCursor)) || !bounded(value)) return false
    if (expected !== undefined && (value.visibility !== (expected.visibility ?? null) || value.subjectKind !== (expected.subjectKind ?? null) || value.nextCursor !== null && value.nextCursor === expected.cursor)) return false
    const visibility = value.visibility, kind = value.subjectKind, asOf = value.context.asOf
    if (!value.items.every(item => preferenceEntryValid(item) && (visibility === null || item.preference.visibility === visibility)
      && (kind === null || item.preference.subject.kind === kind) && beforeOrEqual(item.preference.createdAt, asOf))) return false
    const preferences = (value.items as PreferenceEntry[]).map(item => item.preference)
    return descendingCreation(preferences) && new Set(preferences.map(item => subjectKey(item.subject))).size === preferences.length
      && (value.nextCursor === null || preferences.length > 0 && (expected === undefined || preferences.length === (expected.limit ?? 20)))
  })
}
export function preferenceHistoryPageValid(value: unknown, id?: string, expected: HistoryQuery = {}): value is PreferenceHistoryPage {
  return check(() => {
    if (id !== undefined && !todayIdValid(id) || !historyQueryValid(expected) || !fields(value, ['items', 'nextBefore']) || !Array.isArray(value.items)
      || value.items.length > (expected.limit ?? 20) || value.items.length === 0 && expected.before !== 0 || !(value.nextBefore === null || integer(value.nextBefore, 1)) || !bounded(value)) return false
    let previous: PreferenceHistory | undefined
    for (const entry of value.items) {
      if (!fields(entry, ['change', 'snapshot', 'createdAt']) || !oneOf(entry.change, ['CREATED', 'UPDATED']) || !nextStepPreferenceValid(entry.snapshot, id)
        || (entry.change === 'CREATED') !== (entry.snapshot.revision === 0) || !timestamp(entry.createdAt) || !sameTime(entry.createdAt, entry.snapshot.updatedAt)
        || expected.before !== undefined && entry.snapshot.revision >= expected.before || !bounded(entry, 32768)) return false
      const item = entry as PreferenceHistory
      if (previous !== undefined && (item.snapshot.id !== previous.snapshot.id || item.snapshot.revision !== previous.snapshot.revision - 1
        || !equal(item.snapshot.subject, previous.snapshot.subject) || item.snapshot.createdAt !== previous.snapshot.createdAt || !beforeOrEqual(item.createdAt, previous.createdAt)
        || item.snapshot.visibility === previous.snapshot.visibility)) return false
      previous = item
    }
    return value.nextBefore === null ? previous === undefined || previous.snapshot.revision === 0
      : previous !== undefined && value.items.length === (expected.limit ?? 20) && value.nextBefore === previous.snapshot.revision
  })
}

export type EvidenceRefQuery = { kind: AvailableEvidenceKind; objectId: string; versionId?: string; revision?: number; partId?: string }
function refFromQuery(value: EvidenceRefQuery): EvidenceRef {
  return { kind: value.kind, objectId: value.objectId, versionId: value.versionId ?? null, revision: value.revision ?? null, partId: value.partId ?? null } as EvidenceRef
}
export function evidenceRefQueryValid(value: unknown): value is EvidenceRefQuery {
  if (!queryShape(value, ['kind', 'objectId'], ['versionId', 'revision', 'partId']) || !oneOf(value.kind, evidenceKinds) || !todayIdValid(value.objectId)) return false
  return (value.kind === 'RECOLLECTION' ? todayIdValid(value.versionId) : !Object.hasOwn(value, 'versionId'))
    && (value.kind === 'MANUAL_FEEDBACK' ? integer(value.revision, 1, 100) : value.kind === 'REVIEWED_OBSERVATION' ? integer(value.revision) : !Object.hasOwn(value, 'revision'))
    && (value.kind === 'AI_OBSERVATION' ? typeof value.partId === 'string' && /^f(?:[1-9]|1[0-2])$/.test(value.partId) : !Object.hasOwn(value, 'partId'))
}
export function evidenceRefQuery(value: EvidenceRef): EvidenceRefQuery {
  if (!evidenceRefValid(value)) invalidQuery()
  return { kind: value.kind, objectId: value.objectId, ...(value.versionId !== null ? { versionId: value.versionId } : {}),
    ...(value.revision !== null ? { revision: value.revision } : {}), ...(value.partId !== null ? { partId: value.partId } : {}) }
}
export function useIdentityQueryValid(value: unknown): value is UseIdentityQuery {
  if (!queryShape(value, ['targetId', 'targetVersionId', 'requirementId', 'kind', 'objectId'], ['versionId', 'revision', 'partId']) || !targetQueryValid(value, true) || !todayIdValid(value.requirementId)) return false
  const { targetId: _targetId, targetVersionId: _targetVersionId, requirementId: _requirementId, ...reference } = value
  return evidenceRefQueryValid(reference)
}
function identityFromQuery(value: UseIdentityQuery): CreateUsePreview {
  if (!useIdentityQueryValid(value)) invalidQuery()
  return { target: { itemId: value.targetId, versionId: value.targetVersionId }, requirementId: value.requirementId, evidence: refFromQuery(value) }
}
export function preferenceSubjectQueryValid(value: unknown): value is PreferenceSubjectQuery {
  if (!queryShape(value, ['subjectKind'], ['sourceKind', 'sourceId', 'targetId', 'targetVersionId', 'requirementId'])) return false
  return value.subjectKind === 'SOURCE' ? fields(value, ['subjectKind', 'sourceKind', 'sourceId']) && growthSourceRefValid({ kind: value.sourceKind, id: value.sourceId })
    : value.subjectKind === 'TARGET_REQUIREMENT' && fields(value, ['subjectKind', 'targetId', 'targetVersionId', 'requirementId']) && targetQueryValid(value, true) && todayIdValid(value.requirementId)
}
function subjectFromQuery(value: PreferenceSubjectQuery): SubjectIdentity {
  if (!preferenceSubjectQueryValid(value)) invalidQuery()
  return value.subjectKind === 'SOURCE' ? { kind: 'SOURCE', source: { kind: value.sourceKind, id: value.sourceId }, requirement: null }
    : { kind: 'TARGET_REQUIREMENT', source: null, requirement: { target: { itemId: value.targetId, versionId: value.targetVersionId }, requirementId: value.requirementId } }
}
export function preferenceSubjectQuery(value: SubjectIdentity | Subject): PreferenceSubjectQuery {
  if (!subjectIdentityValid(value) && !subjectValid(value)) invalidQuery()
  return value.kind === 'SOURCE' ? { subjectKind: 'SOURCE', sourceKind: value.source.kind, sourceId: value.source.id }
    : { subjectKind: 'TARGET_REQUIREMENT', targetId: value.requirement.target.itemId, targetVersionId: value.requirement.target.versionId, requirementId: value.requirement.requirementId }
}
function invalidQuery(): never { throw new ApiError(400, 'INVALID_REQUEST', '成长查询参数不符合约定，请核对日期、确切版本、来源身份及分页位置。') }
function queryPath(path: string, value: Record<string, unknown>): string {
  const query = new URLSearchParams()
  for (const [key, item] of Object.entries(value)) if (item !== undefined) query.set(key, String(item))
  return query.size === 0 ? path : `${path}?${query}`
}
export function growthOverviewPath(query: OverviewQuery): string { if (!growthOverviewQueryValid(query)) invalidQuery(); return queryPath('/growth/overview', query) }
export function growthEvidencePath(query: EvidenceQuery): string { if (!evidenceQueryValid(query)) invalidQuery(); return queryPath('/growth/evidence', query) }
export function growthEvidenceRefPath(reference: EvidenceRef | EvidenceRefQuery): string {
  const query = evidenceRefValid(reference) ? evidenceRefQuery(reference) : reference
  if (!evidenceRefQueryValid(query)) invalidQuery()
  return queryPath('/growth/evidence/ref', query)
}
export function growthSourceProgressPath(query: SourceProgressQuery = {}): string { if (!sourceProgressQueryValid(query)) invalidQuery(); return queryPath('/growth/source-progress', query) }
export function growthActivityPath(query: ActivityQuery): string { if (!activityQueryValid(query)) invalidQuery(); return queryPath('/growth/activity', query) }
export function growthNextStepsPath(query: NextStepsQuery): string { if (!nextStepsQueryValid(query)) invalidQuery(); return queryPath('/growth/next-steps', query) }
export function growthUsePath(id: string): string { if (!todayIdValid(id)) invalidQuery(); return `/growth/target-evidence/${id}` }
export function growthUsePreviewPath(id?: string): string { return id === undefined ? '/growth/target-evidence/preview' : `${growthUsePath(id)}/preview` }
export function growthUsesPath(query: UseDirectoryQuery): string { if (!useDirectoryQueryValid(query)) invalidQuery(); return queryPath('/growth/target-evidence', query) }
export function growthUseIdentityPath(value: CreateUsePreview | UseIdentityQuery): string {
  if ('target' in value && !createUsePreviewValid(value)) invalidQuery()
  const query: UseIdentityQuery = 'target' in value ? { targetId: value.target.itemId, targetVersionId: value.target.versionId, requirementId: value.requirementId, ...evidenceRefQuery(value.evidence) } : value
  if (!useIdentityQueryValid(query)) invalidQuery()
  return queryPath('/growth/target-evidence/by-identity', query)
}
export function growthUseHistoryPath(id: string, query: HistoryQuery = {}): string {
  if (!historyQueryValid(query)) invalidQuery()
  return queryPath(`${growthUsePath(id)}/history`, query)
}
export function growthTargetCoveragePath(target: TargetRef): string {
  if (!growthTargetRefValid(target)) invalidQuery()
  return queryPath('/growth/target-coverage', { targetId: target.itemId, targetVersionId: target.versionId })
}
export function growthPreferencePath(id: string): string { if (!todayIdValid(id)) invalidQuery(); return `/growth/next-step-preferences/${id}` }
export function growthPreferencesPath(query: PreferenceQuery = {}): string { if (!preferenceQueryValid(query)) invalidQuery(); return queryPath('/growth/next-step-preferences', query) }
export function growthPreferenceSubjectPath(value: SubjectIdentity | Subject | PreferenceSubjectQuery): string {
  const query = 'kind' in value ? preferenceSubjectQuery(value) : value
  if (!preferenceSubjectQueryValid(query)) invalidQuery()
  return queryPath('/growth/next-step-preferences/by-subject', query)
}
export function growthPreferenceHistoryPath(id: string, query: HistoryQuery = {}): string {
  if (!historyQueryValid(query)) invalidQuery()
  return queryPath(`${growthPreferencePath(id)}/history`, query)
}
export const growthCommandPaths = { createUse: '/growth/target-evidence', createPreference: '/growth/next-step-preferences' } as const

export const evidenceKindLabels: Record<EvidenceKind, string> = { TRAINING_ANSWER: '正式文字回答', MANUAL_FEEDBACK: '人工反馈修订', AI_OBSERVATION: 'AI 未审观察', RECOLLECTION: '面后回忆版本', REPLAY_JUDGMENT: '复练判断', MEMORY_RATING: '主动回忆评分', REVIEWED_OBSERVATION: '受审观察' }
export const assessmentLevelLabels: Record<AssessmentLevel, string> = { SELF_RECORDED: '本人保存的记录', AI_UNREVIEWED: 'AI 生成，未经正式审阅', REVIEWED_OBSERVATION: '受审观察' }
export const coverageLabels: Record<Coverage, string> = { SUPPORTS: '个人标注：支持', PARTIAL: '个人标注：部分适用', CONTEXT_ONLY: '仅提供背景', INSUFFICIENT: '个人标注：证据不足' }
export const directStateLabels: Record<DirectState, string> = { AVAILABLE: '直接依据当前可用', REVIEW_REQUIRED: '直接依据待复核', UNAVAILABLE: '来源当前不可用', UNKNOWN: '当前条件未知' }
export const effectiveUseStateLabels: Record<EffectiveUseState, string> = { EFFECTIVE_POSITIVE: '当前个人正向标注', NON_POSITIVE: '背景或不足说明', WITHDRAWN: '已撤回', TARGET_NOT_CURRENT: '目标已非当前版本', SOURCE_REVIEW_REQUIRED: '来源待复核', SOURCE_UNAVAILABLE: '来源不可用', SOURCE_UNKNOWN: '来源当前条件未知' }
export const sourceDispositionLabels: Record<SourceDisposition, string> = { DROPPED: '已停止跟踪', SOURCE_UNAVAILABLE: '来源不可用', REVIEW_REQUIRED: '需要复核', UNASSESSED: '尚未记录判断', HANDLED: '当前判断已处理', PARTIAL: '当前判断部分处理', UNHANDLED: '当前判断未处理', UNCERTAIN: '当前判断不确定' }
export const nextStepReasonLabels: Record<NextStepReason, string> = { ACTION_PENDING: '仍有待处理来源', ACTION_REVIEW_REQUIRED: '动作来源需要复核', MEMORY_DUE: '主动回忆已到期', MEMORY_REVIEW_REQUIRED: '卡片依据需要复核', TASK_UNSCHEDULED: '准备任务尚未安排', USER_MARKED_INSUFFICIENT: '你标注了证据不足' }
export const activityMetricLabels: Record<ActivityMetric, string> = {
  ANSWERS_SUBMITTED: '回答提交次数', QUESTION_INSTANCES_ANSWERED: '作答题目实例数', ANSWERS_BEFORE_ASSISTANCE: '辅助前提交', ANSWERS_LEARNING: '学习条件下提交', ANSWERS_ASSISTED: '辅助后提交',
  REPLAYS_STARTED: '已开始复练', SAME_REPLAYS_STARTED: '已开始同题复练', VARIANT_REPLAYS_STARTED: '已开始变式复练', REPLAY_JUDGMENTS_RECORDED: '复练判断记录',
  MEMORY_RATINGS: '主动回忆评分次数', MEMORY_CARDS_RATED: '有评分的卡片数', MEMORY_COUNTED_RATINGS: '计入常规的评分', MEMORY_UNCOUNTED_RATINGS: '不计入常规的评分',
  TASKS_DONE: '明确完成的准备任务', INTERVIEWS_COMPLETED: '明确标记完成的面试', FEEDBACK_CREATED: '首次反馈记录', FEEDBACK_REVISED: '反馈修订次数',
  AI_OBSERVATIONS_PUBLISHED: 'AI 未审观察条数', RECOLLECTION_VERSIONS_RECORDED: '面后回忆版本数',
  REVIEWED_OBSERVATIONS_CREATED: '首次记录受审观察', REVIEWED_OBSERVATIONS_MAINTAINED: '受审观察修订或撤回次数',
}
export const growthLimitationLabels: Record<Limitation, string> = {
  INDIRECT_LINEAGE_NOT_CHECKED: '间接派生依据尚未完成全面核对', REVIEWED_CHAIN_NOT_INTEGRATED: '受审链尚未接入，不能显示已审证据数量', NO_LINKED_STANDARD: '未关联固定参考标准',
  TARGET_SCENARIO_UNSTRUCTURED: '目标场景尚无结构化对照', TARGET_TECHNOLOGY_UNSTRUCTURED: '目标技术条件尚无结构化对照', TARGET_ASSISTANCE_UNSTRUCTURED: '目标辅助条件尚无结构化对照',
  NO_AUTOMATIC_DATE_EXPIRY: '记录日期没有自动失效期限', CURRENT_TOPIC_CLASSIFICATION: '主题来自当前分类，未改写历史评分', FILTERED_SCOPE_ONLY: '仅统计明确符合当前筛选的记录',
  COUNT_SCAN_LIMITED: '扫描达到上限，计数仅为已核对下界', ORIGINAL_TARGET_ACTIVITY_ONLY: '活动只归属于发生时记录的目标', HISTORICAL_TARGET_VERSION: '正在查看历史目标版本',
  SEMANTICS_NOT_CERTIFIED: '适用性为个人判断，系统未认证语义等价', NO_SINGLE_QUESTION_DURATION: '不提供推算的单题学习时长', NEXT_STEPS_BOUNDED: '建议来自有界核对集合，可继续到原目录查看',
}
export function growthErrorText(error: unknown): string {
  if (!(error instanceof ApiError)) return errorText(error)
  const messages: Record<string, string> = {
    GROWTH_CURSOR_MISMATCH: '分页位置与当前筛选不一致，请保留筛选并从首页重读。', GROWTH_CURSOR_EXPIRED: '分页位置已过期，请保留筛选并从首页重读。',
    EVIDENCE_NOT_FOUND: '原证据不存在或不可访问，请重新核对确切版本。', EVIDENCE_USE_NOT_FOUND: '这条证据关联不存在或不可访问。',
    GROWTH_TARGET_NOT_FOUND: '目标版本或所选要求不存在或不可访问。', GROWTH_PREVIEW_CHANGED: '目标、来源或当前条件已变化，请保留输入并重新预览。',
    EVIDENCE_USE_EXISTS: '该确切证据已有关联，请按原身份找回后明确修改或恢复。', EVIDENCE_USE_REVISION_CONFLICT: '关联已被修改，请保留输入并核对最新修订。',
    NEXT_STEP_PREFERENCE_EXISTS: '该主体已有显示偏好，请找回原偏好后修改。', NEXT_STEP_PREFERENCE_NOT_FOUND: '建议偏好不存在或不可访问。',
    NEXT_STEP_PREFERENCE_REVISION_CONFLICT: '显示偏好已变化，请保留输入并重新读取。', NEXT_STEP_SUBJECT_CHANGED: '建议来源当前条件已变化，请重新核对。',
    NEXT_STEP_SUBJECT_NOT_FOUND: '建议来源或目标要求不存在或不可访问。', GROWTH_POSITIVE_COVERAGE_UNAVAILABLE: '当前依据不允许保存正向标注，可保留背景或不足说明。',
    GROWTH_TARGET_NOT_CURRENT: '目标已非当前有效版本，原判断保留；修改时请明确撤回。', DEPENDENCY_NOT_READY: '所需证据链尚未接入，请保留当前输入。',
    GROWTH_LIMIT: '本次成长请求或响应超过范围上限，请缩小范围后重试。', GROWTH_SOURCE_UNAVAILABLE: '必要来源暂时读取失败，不能将此次结果当作没有记录。',
  }
  return messages[error.code] ?? errorText(error)
}
