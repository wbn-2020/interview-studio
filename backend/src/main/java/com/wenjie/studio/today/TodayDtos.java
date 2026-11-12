package com.wenjie.studio.today;

import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.memory.MemoryAttemptDtos;
import com.wenjie.studio.memory.MemoryDtos;
import com.wenjie.studio.memory.MemoryReviewDtos;
import com.wenjie.studio.planning.PlanningDtos;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.schedule.ScheduleDtos;
import com.wenjie.studio.training.TrainingDtos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 今日只读聚合契约；来源事实沿用原领域，C2a 安排偏好与来源状态分别返回。 */
public final class TodayDtos {
    private TodayDtos() {}

    public enum SourceKind { KNOWLEDGE_DRAFT, TRAINING_SESSION, PREPARATION_TASK, MEMORY_CARD, REVIEW_ACTION, INTERVIEW_EVENT }
    public enum Section { CONTINUE, ATTENTION, TODAY, BACKLOG }
    public enum Availability { READY, DEPENDENCY_PENDING, NOT_CONFIGURED, ERROR }
    public enum AvailabilityReason { NONE, QUERY_NOT_INTEGRATED, MEMORY_SETTINGS_REQUIRED, SOURCE_READ_FAILED, SOURCE_INTEGRITY_FAILED }
    public enum CountMode { EXACT, LOWER_BOUND, UNAVAILABLE }
    public enum TimeKind { NONE, INSTANT, LOCAL_DATE }
    public enum ZoneBasis { SOURCE, DISPLAY, PLACEMENT }
    public enum EstimateBasis { SOURCE, UNKNOWN, USER }
    public enum Reason {
        UNPUBLISHED_DRAFT, TRAINING_DRAFT, TRAINING_RUNNING, TRAINING_PAUSED, SAVED_ANSWER_DRAFT,
        TASK_IN_PROGRESS, TASK_OVERDUE, TASK_TODAY, TASK_UNSCHEDULED,
        MEMORY_OPEN_ATTEMPT, MEMORY_DUE, MEMORY_NOT_RECOMMENDED, MEMORY_BUDGET_CONFIRMATION,
        MEMORY_SOURCE_REVIEW, ACTION_PENDING, INTERVIEW_REMINDER, INTERVIEW_IN_PROGRESS,
        INTERVIEW_TODAY, INTERVIEW_UPCOMING, INTERVIEW_TIME_PASSED, PLACEMENT_TODAY, PLACEMENT_OVERDUE
    }
    public enum Warning { SOURCE_ZONE_DIFFERS, ARCHIVED_CONCEPT_WITH_OTHER_SOURCE, MEMORY_QUEUE_RECHECK_REQUIRED, PLACEMENT_CONTINUATION_RETAINED }
    public enum ExclusionReason {
        KNOWLEDGE_ARCHIVED, NO_UNPUBLISHED_DRAFT, TRAINING_ENDED,
        TASK_PAUSED, TASK_DONE, TASK_CANCELLED, TASK_FUTURE,
        MEMORY_NO_SCHEDULE, MEMORY_PAUSED, MEMORY_BLOCKED, MEMORY_FUTURE_DUE, MEMORY_NOT_RECOMMENDED,
        ACTION_MERGED, ACTION_NO_PENDING, INTERVIEW_CLOSED, INTERVIEW_OUTSIDE_WINDOW,
        PLACEMENT_PAUSED, PLACEMENT_SUPPRESSED_TODAY, PLACEMENT_FUTURE
    }
    public enum Route { KNOWLEDGE, TRAINING, PREPARATION_TASKS, MEMORY_CARDS, MEMORY_RECALL, REVIEW, SCHEDULE }
    public enum ActionKind { OPEN_SOURCE, CONTINUE_TRAINING, CONTINUE_MEMORY_ATTEMPT, OPEN_MEMORY_QUEUE, REVIEW_SOURCE, OPEN_REVIEW_ACTION, OPEN_INTERVIEW }

    public record OverviewQuery(String zoneId, String targetId, Boolean includeOverBudget) {}
    public record ItemsQuery(String zoneId, SourceKind kind, Section section, String targetId,
                             Boolean includeOverBudget, String cursor, Integer limit) {}
    public record CalendarQuery(LocalDate from, LocalDate to, String zoneId, SourceKind kind,
                                String targetId, String cursor, Integer limit) {}
    public record SourceQuery(String zoneId) {}

    public record SourceRef(SourceKind kind, String id) {}
    public record TargetRef(String itemId, String versionId) {}
    public record Filter(String targetId, boolean includeOverBudget) {}
    public record DayContext(String zoneId, LocalDate localDate, Instant startAt, Instant endAt,
                             Instant asOf, Instant serverTime) {}
    public record CalendarWindow(LocalDate from, LocalDate to, Instant startAt, Instant endAt) {}
    public record Capability(Availability status, AvailabilityReason reason) {}
    public record Count(Long value, CountMode mode) {}

    public record KnowledgeStamp(long itemRevision, long draftRevision, String currentVersionId) {}
    // 保存题目草稿不递增会话 revision；questionsHash 固定所有题目的身份、修订和草稿状态。
    public record TrainingStamp(long revision, long generation, String questionsHash) {}
    public record TaskStamp(long revision) {}
    public record MemoryStamp(long cardRevision, String cardVersionId, Long gateRevision, Long scheduleRevision,
                              String attemptId, Long attemptRevision, Long settingsRevision) {}
    public record ActionStamp(long revision, String progressHash) {}
    public record InterviewStamp(long revision) {}

    // 所有联合载荷字段都显式序列化；kind 对应的一项非空，其余为 null，不用开放 Map 承载领域状态。
    public record SourceStamp(SourceKind kind, KnowledgeStamp knowledge, TrainingStamp training, TaskStamp task,
                              MemoryStamp memory, ActionStamp action, InterviewStamp interview) {}
    public record KnowledgeState(KnowledgeDtos.Kind itemKind, KnowledgeDtos.Status status,
                                 boolean unpublishedDraft, Instant draftSavedAt) {}
    public record TrainingState(TrainingDtos.Mode mode, TrainingDtos.Status status, int savedDraftCount,
                                Instant lastDraftSavedAt, Instant lastActivityAt, String repracticeId) {}
    public record TaskState(PlanningDtos.Status status, Instant startedAt, Instant completedAt) {}
    public record OpenMemoryAttempt(String id, long revision, MemoryAttemptDtos.Mode mode, boolean countRegular,
                                    MemoryAttemptDtos.State state, Instant startedAt, Instant updatedAt) {}
    public record QueueRecommendation(MemoryAttemptDtos.BudgetKind budgetKind, boolean countedToday,
                                       boolean recommended, boolean requiresBudgetConfirmation) {}
    public record MemoryState(MemoryDtos.Status cardStatus, MemoryReviewDtos.ScheduleStatus scheduleStatus,
                              MemoryReviewDtos.PauseReason pauseReason, MemoryReviewDtos.FsrsPhase phase,
                              MemoryReviewDtos.Eligibility eligibility, OpenMemoryAttempt openAttempt,
                              QueueRecommendation recommendation) {}
    public record ActionState(ReviewDtos.ActionStatus status, String mergedInto, int sourceCount, int trackingCount) {}
    public record InterviewState(ScheduleDtos.Status status, ScheduleDtos.Timing timing, boolean reminderPending) {}
    public record SourceStatus(SourceKind kind, KnowledgeState knowledge, TrainingState training, TaskState task,
                               MemoryState memory, ActionState action, InterviewState interview) {}

    // 日期级任务保留来源日期；memory 的 UTC due 使用显示时区，不冒充评分时区或原安排时区。
    public record SourceTime(TimeKind kind, Instant instant, LocalDate localDate, String zoneId, ZoneBasis zoneBasis) {}
    public record EventWindow(Instant startAt, Instant endAt, String zoneId, Instant remindAt) {}
    public record Estimate(Integer minutes, EstimateBasis basis) {}
    public record ItemQuery(String item) {}
    public record ActionQuery(String action) {}
    public record AttemptQuery(String attempt) {}
    // route 由客户端映射到固定路由名；仅允许该路由的一个参数载荷，队列入口三个载荷均空。
    public record Continuation(Route route, ItemQuery item, ActionQuery action, AttemptQuery attempt) {}
    public record AvailableAction(ActionKind kind, Continuation continuation) {}

    // null 只表示尚未保存偏好；安排不复制来源完成状态，也不改变源日期或回忆算法排程。
    public record TodayItem(SourceRef source, SourceStamp stamp, String title, Section section, SourceStatus sourceStatus,
                            List<Reason> reasons, List<TargetRef> targetRefs, SourceTime time, EventWindow eventWindow,
                            Estimate estimate, TodayPlanDtos.Placement placement, Continuation continuation, Integer pendingCount,
                            List<AvailableAction> availableActions, List<Warning> warnings) {}

    public record SourceSectionCapability(SourceKind kind, Section section, Capability capability,
                                          Count count, boolean scanLimited) {}
    public record SectionPreview(Section section, List<TodayItem> items) {}
    public record MemoryQueueInfo(MemoryAttemptDtos.DailyBudget budget, Count eligibleDueCards,
                                  Count recommendedCards, Instant nextDueAt, boolean includeOverBudget) {}
    public record TodayOverview(DayContext context, Filter filter, List<SourceSectionCapability> capabilities,
                                List<SectionPreview> sections, MemoryQueueInfo memoryQueue) {}
    public record TodayPage(DayContext context, Filter filter, SourceKind kind, Section section, Capability capability,
                            Count count, boolean scanLimited, List<TodayItem> items, String nextCursor,
                            MemoryQueueInfo memoryQueue) {}
    public record CalendarPage(DayContext context, CalendarWindow window, SourceKind kind, String targetId,
                               Capability capability, Count count, boolean scanLimited,
                               List<TodayItem> items, String nextCursor) {}
    public record SourceDetail(DayContext context, SourceRef source, Capability capability,
                               TodayItem item, ExclusionReason exclusionReason) {}
}
