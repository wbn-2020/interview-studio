package com.wenjie.studio.growth;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.memory.MemoryAttemptDtos;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.review.RecollectionDtos;
import com.wenjie.studio.review.RepracticeDtos;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.ReviewedObservationDtos;
import com.wenjie.studio.review.StandardReviewDtos;
import com.wenjie.studio.today.TodayDtos;
import com.wenjie.studio.training.TrainingDtos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 成长目录只传固定身份与白名单元数据；当前适用性与原始记录分开表达。 */
public final class GrowthDtos {
    private GrowthDtos() {}

    public enum EvidenceKind {
        TRAINING_ANSWER, MANUAL_FEEDBACK, AI_OBSERVATION,
        RECOLLECTION, REPLAY_JUDGMENT, MEMORY_RATING, REVIEWED_OBSERVATION
    }
    public enum AssessmentLevel { SELF_RECORDED, AI_UNREVIEWED, REVIEWED_OBSERVATION }
    public enum RecordKind {
        ANSWER, SELF_CHECK, EXTERNAL_FEEDBACK, AI_FEEDBACK,
        INTERVIEW_RECOLLECTION, REPLAY_ASSESSMENT, MEMORY_SELF_RATING, REVIEWED_CHECK
    }
    public enum ReviewFlag { DISPUTED, REVIEW_REQUIRED, SOURCE_UNAVAILABLE }
    public enum LineageCheck { NOT_APPLICABLE, PARTIAL, COMPLETE }
    public enum Availability { READY, DEPENDENCY_PENDING, NOT_CONFIGURED, ERROR }
    public enum CapabilityReason {
        NONE, QUERY_NOT_INTEGRATED, REVIEWED_CHAIN_NOT_INTEGRATED,
        MEMORY_SETTINGS_REQUIRED, SOURCE_READ_FAILED, SOURCE_INTEGRITY_FAILED
    }
    public enum CountMode { EXACT, LOWER_BOUND, UNAVAILABLE }
    public enum FacetBasis { RECORDED, CURRENT_CLASSIFICATION, USER_LINK }
    public enum TargetMode { RECORDED_ONLY, INCLUDE_ACTIVE_LINKS }
    public enum TopicMode { RECORDED_ONLY, INCLUDE_CURRENT_CLASSIFICATION }
    public enum EmptyState { HAS_RECORDS, RECOLLECTION_ONLY, NO_RECORD, UNKNOWN }
    public enum DirectState { AVAILABLE, REVIEW_REQUIRED, UNAVAILABLE, UNKNOWN }
    public enum RecordCurrency { CURRENT, SUPERSEDED, NOT_APPLICABLE }
    public enum Coverage { SUPPORTS, PARTIAL, CONTEXT_ONLY, INSUFFICIENT }
    public enum UseStatus { ACTIVE, WITHDRAWN }
    public enum MatchState { MATCH, DIFFERENT, UNKNOWN }
    public enum Visibility { VISIBLE, HIDDEN }

    public record TargetRef(String itemId, String versionId) {}
    public record QuestionRef(String questionId, String versionId) {}
    public record EvidenceRef(EvidenceKind kind, String objectId, String versionId, Long revision, String partId) {}
    public record Capability(Availability status, CapabilityReason reason) {}
    public record Count(Long value, CountMode mode) {}
    public record Context(Instant asOf, Instant checkedAt, Instant serverTime) {}
    public record DateWindow(LocalDate from, LocalDate to, String zoneId, Instant startAt, Instant endAt, Instant observedUntil) {}
    public record Filter(TargetRef target, TargetMode targetMode, String topicId,
                         TopicMode topicMode, QuestionDtos.Dimension dimension) {}
    public record Scope(DateWindow window, Filter filter) {}

    public record Exposure(long authoredCount, long referenceViewCount, long evaluationAccessCount, Instant lastExposureAt) {}
    public record RepracticeContext(String id, RepracticeDtos.QuestionMode questionMode, RepracticeDtos.BaselineKind baselineKind) {}
    public record RecordedFields(boolean technology, boolean scenario, boolean conditions) {}
    public record AnswerMeta(String sessionId, String questionId, String answerId,
                             TrainingDtos.Mode mode, TrainingDtos.QuestionKind questionKind,
                             TrainingDtos.AnswerCondition condition, int assistanceEventSequence,
                             Exposure priorExposure, QuestionRef sourceQuestion,
                             RepracticeContext repractice, RecordedFields recordedFields) {}
    public record DecisionMeta(String id, int revision, AiDtos.FeedbackChoice choice, Instant createdAt) {}
    public record StandardMeta(QuestionRef question, QuestionDtos.StandardStatus status) {}
    public record ManualMeta(AnswerMeta answer, ReviewDtos.FeedbackKind kind, String authorLabel,
                             QuestionDtos.Dimension dimension, AiDtos.EvaluationCoverage coverage, DecisionMeta latestDecision) {}
    public record AiMeta(AnswerMeta answer, QuestionDtos.Dimension dimension, AiDtos.EvaluationCoverage coverage,
                         DecisionMeta latestDecision, StandardMeta standard) {}
    public record InterviewMeta(String eventId, long revision, Instant startAt, String zoneId, TargetRef target) {}
    public record RecollectionMeta(int versionNumber, RecollectionDtos.BaselineKind baselineKind, InterviewMeta interview,
                                   boolean hasAnswerFragment, boolean hasFeedback, boolean hasUncertainty) {}
    public record ReplayMeta(String sourceId, String actionId, int judgmentRevision, String comparisonId,
                             AnswerMeta answer, RepracticeDtos.QuestionMode questionMode,
                             RepracticeDtos.BaselineKind baselineKind, RepracticeDtos.Outcome outcome,
                             boolean currentlyApplicable, String recordedVerificationHash, String currentVerificationHash) {}
    public record MemoryMeta(String cardId, String cardVersionId, String attemptId, long attemptSequence,
                             MemoryAttemptDtos.Mode mode, boolean countRegular, MemoryAttemptDtos.Rating rating,
                             MemoryAttemptDtos.RatingDay ratingDay, MemoryAttemptDtos.BudgetUse budget,
                             String scheduleChangeId, Long scheduleSequence, long thinkingElapsedMs, long totalElapsedMs) {}
    public record ReviewedMeta(AnswerMeta answer, QuestionRef question, String checkpointId,
                               StandardReviewDtos.RevisionRef standardReview, StandardReviewDtos.RecordChange change,
                               StandardReviewDtos.RecordStatus recordedStatus, Instant recordedAt, LocalDate observedOn,
                               String observationZoneId, int answerNumber, Instant answerCreatedAt,
                               AiDtos.EvaluationCoverage coverage, ReviewedObservationDtos.ObservationConclusion conclusion,
                               ReviewedObservationDtos.ObservationEligibility eligibility) {}
    // 受审观察公开固定身份、条件是否已记录及封闭资格，不透传原答或审阅正文。
    public record Conditions(EvidenceKind kind, AnswerMeta answer, ManualMeta manual, AiMeta ai,
                             RecollectionMeta recollection, ReplayMeta replay, MemoryMeta memory, ReviewedMeta reviewed) {}

    public record TargetFacet(TargetRef target, FacetBasis basis) {}
    public record TopicFacet(String topicId, FacetBasis basis) {}
    public record DimensionFacet(QuestionDtos.Dimension dimension, FacetBasis basis) {}
    public record TargetMatch(boolean recorded, boolean activeUserLink) {}
    public record Facets(List<TargetFacet> targets, List<TopicFacet> topics, List<DimensionFacet> dimensions,
                         boolean currentTopicClassificationAvailable, TargetMatch selectedTargetMatch) {}
    public record EvidenceState(DirectState directState, RecordCurrency recordCurrency,
                                List<ReviewFlag> reviewFlags, LineageCheck lineageCheck) {}
    public record EvidenceStamp(EvidenceRef ref, String originHash, String currentHash) {}
    public enum EvidenceRouteKind {
        TRAINING_ANSWER, MANUAL_FEEDBACK, AI_OBSERVATION, RECOLLECTION_VERSION, REPLAY_COMPARISON, MEMORY_ATTEMPT,
        REVIEWED_OBSERVATION
    }
    public record EvidenceRoute(EvidenceRouteKind route, EvidenceRef ref, String sessionId, String questionId,
                                String answerId, String comparisonId, String cardId, String attemptId) {}
    public record EvidenceSummary(EvidenceRef ref, Instant occurredAt, String title, RecordKind recordKind,
                                  AssessmentLevel assessmentLevel, Conditions conditions, Facets facets,
                                  EvidenceState state, EvidenceStamp stamp, EvidenceRoute route) {}
    public record EvidenceResolution(EvidenceRef ref, EvidenceSummary summary, EvidenceState state, EvidenceStamp stamp) {}

    public record OverviewQuery(LocalDate from, LocalDate to, String zoneId, String targetId, String targetVersionId,
                                TargetMode targetMode, String topicId, TopicMode topicMode, QuestionDtos.Dimension dimension) {}
    public record EvidenceQuery(LocalDate from, LocalDate to, String zoneId, EvidenceKind kind,
                                String targetId, String targetVersionId, TargetMode targetMode,
                                String topicId, TopicMode topicMode, QuestionDtos.Dimension dimension,
                                AssessmentLevel level, ReviewFlag reviewFlag, String cursor, Integer limit) {}
    public record KindCount(EvidenceKind kind, Capability capability, Count count, boolean scanLimited) {}
    public record EvidencePage(Context context, Scope scope, EvidenceKind kind, AssessmentLevel level, ReviewFlag reviewFlag,
                               Capability capability, Count count, boolean scanLimited, int scanned,
                               List<EvidenceSummary> items, String nextCursor) {}
    public record Overview(Context context, Scope scope, List<KindCount> evidenceGroups, EmptyState emptyState,
                           ActivityTotals activity, SourceProgressTotals sourceProgress, List<Limitation> limitations) {}

    public enum Grain { DAY, WEEK }
    public enum PeriodState { COMPLETE, PARTIAL_TODAY, FUTURE }
    public enum ActivityMetric {
        ANSWERS_SUBMITTED, QUESTION_INSTANCES_ANSWERED,
        ANSWERS_BEFORE_ASSISTANCE, ANSWERS_LEARNING, ANSWERS_ASSISTED,
        REPLAYS_STARTED, SAME_REPLAYS_STARTED, VARIANT_REPLAYS_STARTED,
        REPLAY_JUDGMENTS_RECORDED, MEMORY_RATINGS, MEMORY_CARDS_RATED,
        MEMORY_COUNTED_RATINGS, MEMORY_UNCOUNTED_RATINGS,
        TASKS_DONE, INTERVIEWS_COMPLETED,
        FEEDBACK_CREATED, FEEDBACK_REVISED, AI_OBSERVATIONS_PUBLISHED, RECOLLECTION_VERSIONS_RECORDED,
        REVIEWED_OBSERVATIONS_CREATED, REVIEWED_OBSERVATIONS_MAINTAINED
    }
    public enum MetricUnit { EVENT, DISTINCT_QUESTION, DISTINCT_CARD, REPLAY }
    public record ActivityQuery(LocalDate from, LocalDate to, String zoneId, String targetId, String targetVersionId,
                                String topicId, TopicMode topicMode, QuestionDtos.Dimension dimension, Grain grain) {}
    public record MetricValue(ActivityMetric metric, MetricUnit unit, Capability capability, Count count, boolean additive) {}
    public record ActivityBucket(LocalDate from, LocalDate to, Instant startAt, Instant endAt,
                                 PeriodState state, boolean clipped, List<MetricValue> values) {}
    public record ActivityTotals(Filter recordedFilter, List<MetricValue> values) {}
    public record ActivitySeries(Context context, Scope scope, Grain grain, List<ActivityBucket> buckets,
                                 ActivityTotals totals, List<Limitation> limitations) {}

    public enum SourceDisposition { DROPPED, SOURCE_UNAVAILABLE, REVIEW_REQUIRED, UNASSESSED, HANDLED, PARTIAL, UNHANDLED, UNCERTAIN }
    public record ProgressFilter(TargetRef target, String topicId, TopicMode topicMode, QuestionDtos.Dimension dimension) {}
    public record SourceProgressQuery(String targetId, String targetVersionId, String topicId, TopicMode topicMode,
                                      QuestionDtos.Dimension dimension, SourceDisposition disposition, String cursor, Integer limit) {}
    public record SourceProgressItem(String sourceId, String actionId, long actionRevision, ReviewDtos.Tracking tracking,
                                     EvidenceRef origin, EvidenceRef latestJudgment, RepracticeDtos.Outcome latestOutcome,
                                     boolean domainApplicable, SourceDisposition disposition, EvidenceState originState,
                                     String verificationHash, TodayDtos.Continuation continuation) {}
    public record DispositionCount(SourceDisposition disposition, Count count) {}
    public record SourceProgressTotals(ProgressFilter filter, Capability capability, List<DispositionCount> counts, boolean scanLimited) {}
    public record SourceProgressPage(Context context, ProgressFilter filter, SourceDisposition disposition,
                                     Capability capability, Count count, boolean scanLimited, int scanned,
                                     List<SourceProgressItem> items, String nextCursor) {}

    public record ComparisonItem(MatchState state, String reason) {}
    public record Comparability(ComparisonItem dimension, ComparisonItem scenario, ComparisonItem technology,
                                ComparisonItem assistance, ComparisonItem date) {}
    public record RequirementMeta(String id, int ordinal, String text, List<String> dimensions, int priority) {}
    public record TargetMeta(TargetRef ref, String title, LocalDate capturedOn, long itemRevision,
                             PreparationDtos.Status status, String currentVersionId, long sourceGeneration) {}
    public record EvidenceUse(String id, long revision, TargetRef target, String requirementId, EvidenceRef evidence,
                              Coverage coverage, Comparability comparability, String reason, UseStatus status,
                              Instant createdAt, Instant updatedAt) {}
    public enum EffectiveUseState {
        EFFECTIVE_POSITIVE, NON_POSITIVE, WITHDRAWN, TARGET_NOT_CURRENT,
        SOURCE_REVIEW_REQUIRED, SOURCE_UNAVAILABLE, SOURCE_UNKNOWN
    }
    public record UseEntry(EvidenceUse use, TargetMeta target, RequirementMeta requirement,
                           EvidenceResolution evidence, EffectiveUseState effectiveState) {}
    public record UseLookup(Context context, UseEntry entry) {}
    public record UseIdentityQuery(String targetId, String targetVersionId, String requirementId,
                                   EvidenceKind kind, String objectId, String versionId, Long revision, String partId) {}
    public record UseIdentityLookup(Context context, CreateUsePreview identity, UseEntry entry) {}
    public enum PreviewIntent { CREATE, CHANGE }
    public record CreateUsePreview(TargetRef target, String requirementId, EvidenceRef evidence) {}
    public record ChangeUsePreview(Long expectedRevision) {}
    public record UsePreview(Context context, PreviewIntent intent, String useId, Long useRevision,
                             TargetMeta target, RequirementMeta requirement, EvidenceResolution evidence,
                             Comparability comparability, String previewHash, List<Limitation> limitations) {}
    public record CreateUse(CreateUsePreview request, String expectedPreviewHash, Coverage coverage,
                            Comparability comparability, String reason, Boolean confirmed) {}
    public record ChangeUse(Long expectedRevision, String expectedPreviewHash, Coverage coverage,
                            Comparability comparability, String reason, UseStatus status, Boolean confirmed) {}
    public record UseDirectoryQuery(String targetId, String targetVersionId, String requirementId,
                                    UseStatus status, String cursor, Integer limit) {}
    public record UsePage(Context context, TargetRef target, String requirementId, UseStatus status,
                          List<UseEntry> items, String nextCursor) {}
    public enum UseChangeKind { CREATED, UPDATED, WITHDRAWN, RESTORED }
    public record UseHistory(UseChangeKind change, EvidenceUse snapshot, EvidenceStamp sourceStamp,
                             String targetStamp, String previewHash, Instant createdAt) {}
    public record UseHistoryPage(List<UseHistory> items, Long nextBefore) {}
    public enum CoverageMetric {
        ACTIVE_USES, SAVED_SUPPORTS, SAVED_PARTIAL, CONTEXT_ONLY, INSUFFICIENT,
        EFFECTIVE_POSITIVE, INVALIDATED_POSITIVE, WITHDRAWN_USES
    }
    public record CoverageCount(CoverageMetric metric, Count count) {}
    public record RequirementCoverage(RequirementMeta requirement, List<CoverageCount> counts, Boolean noActiveLinkedEvidence) {}
    public record TargetCoverage(Context context, TargetMeta target, boolean currentTarget, Capability capability,
                                 List<RequirementCoverage> requirements, boolean scanLimited, int scanned) {}

    public enum SubjectKind { SOURCE, TARGET_REQUIREMENT }
    public record RequirementIdentity(TargetRef target, String requirementId) {}
    public record SubjectIdentity(SubjectKind kind, TodayDtos.SourceRef source, RequirementIdentity requirement) {}
    public record Subject(SubjectKind kind, TodayDtos.SourceRef source, TodayDtos.SourceStamp sourceStamp,
                          RequirementIdentity requirement, Long targetRevision) {}
    public enum NextStepReason { ACTION_PENDING, ACTION_REVIEW_REQUIRED, MEMORY_DUE, MEMORY_REVIEW_REQUIRED, TASK_UNSCHEDULED, USER_MARKED_INSUFFICIENT }
    public enum NextStepRoute { SOURCE, TARGET_REQUIREMENT }
    public enum PriorityBasis { PLACEMENT, TARGET_REQUIREMENT, DEFAULT }
    public record NextStepContinuation(NextStepRoute route, TodayDtos.Continuation source, RequirementIdentity requirement) {}
    public record NextStep(Subject subject, String title, List<NextStepReason> reasonCodes, int effectivePriority,
                           PriorityBasis priorityBasis, String preferenceId, Long preferenceRevision, NextStepContinuation continuation) {}
    public record NextStepsQuery(String zoneId, String targetId, String targetVersionId, Integer limit) {}
    public record NextStepScope(String zoneId, LocalDate localDate, TargetRef target) {}
    public record NextSteps(Context context, NextStepScope scope, Capability capability, List<NextStep> items,
                            int examinedCandidates, boolean scanLimited, Count matchingNotShown, List<Limitation> limitations) {}
    public record NextStepPreference(String id, long revision, SubjectIdentity subject, Visibility visibility,
                                     Instant createdAt, Instant updatedAt) {}
    public record CreatePreference(Subject subject, Visibility visibility) {}
    public record SavePreference(Long expectedRevision, Subject expectedSubject, Visibility visibility) {}
    public record PreferenceEntry(NextStepPreference preference, Subject currentSubject, String title,
                                  NextStepContinuation continuation, DirectState subjectState) {}
    public record PreferenceLookup(Context context, PreferenceEntry entry) {}
    public record PreferenceSubjectQuery(SubjectKind subjectKind, TodayDtos.SourceKind sourceKind, String sourceId,
                                         String targetId, String targetVersionId, String requirementId) {}
    public record PreferenceSubjectLookup(Context context, SubjectIdentity subject, PreferenceEntry entry) {}
    public record PreferenceQuery(Visibility visibility, SubjectKind subjectKind, String cursor, Integer limit) {}
    public record PreferencePage(Context context, Visibility visibility, SubjectKind subjectKind,
                                 List<PreferenceEntry> items, String nextCursor) {}
    public enum PreferenceChangeKind { CREATED, UPDATED }
    public record PreferenceHistory(PreferenceChangeKind change, NextStepPreference snapshot, Instant createdAt) {}
    public record PreferenceHistoryPage(List<PreferenceHistory> items, Long nextBefore) {}

    // 内部领域窗口使用同一有界预算；lastConsumed 仅标记已经核对的候选，不能越过未返回的合格项。
    public record EvidencePosition(Instant occurredAt, String evidenceKey) {}
    public record QueryBudget(int candidateLimit, int resultLimit) {}
    public record DomainEvidenceWindow(List<EvidenceSummary> items, int scanned, boolean exhausted,
                                       boolean scanLimited, EvidencePosition lastConsumed) {}
    public record DomainSourceProgressWindow(List<SourceProgressItem> items, int scanned, boolean exhausted,
                                             boolean scanLimited, String lastConsumedSourceId) {}
    public record DomainActivityWindow(List<MetricValue> totals, List<ActivityBucket> buckets, boolean scanLimited) {}

    public enum Limitation {
        INDIRECT_LINEAGE_NOT_CHECKED, REVIEWED_CHAIN_NOT_INTEGRATED,
        NO_LINKED_STANDARD, TARGET_SCENARIO_UNSTRUCTURED, TARGET_TECHNOLOGY_UNSTRUCTURED, TARGET_ASSISTANCE_UNSTRUCTURED,
        NO_AUTOMATIC_DATE_EXPIRY, CURRENT_TOPIC_CLASSIFICATION, FILTERED_SCOPE_ONLY, COUNT_SCAN_LIMITED,
        ORIGINAL_TARGET_ACTIVITY_ONLY, HISTORICAL_TARGET_VERSION, SEMANTICS_NOT_CERTIFIED,
        NO_SINGLE_QUESTION_DURATION, NEXT_STEPS_BOUNDED
    }
}
