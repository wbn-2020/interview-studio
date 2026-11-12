package com.wenjie.studio.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class MemoryAttemptDtos {
    private MemoryAttemptDtos() {}

    public enum Mode { REGULAR, CRAM }
    public enum State { THINKING, REVEALED, RATED, SKIPPED, INVALIDATED }
    public enum ResponseMode { THOUGHT, WRITTEN }
    public enum Rating { AGAIN, HARD, GOOD, EASY }
    public enum EventKind { START, REVEAL, RATE, SKIP, INVALIDATE }
    public enum BudgetKind { NEW, REVIEW }
    public enum InvalidationReason {
        CARD_VERSION_CHANGED, CARD_ARCHIVED, CARD_RESTORED,
        SOURCE_BODY_CHANGED, SOURCE_UPSTREAM_CHANGED, SOURCE_ARCHIVED, SOURCE_RESTORED, SOURCE_REVIEWED,
        SCHEDULE_DEFERRED, SCHEDULE_PAUSED, SCHEDULE_RESUMED, SCHEDULE_RESET
    }

    public record StartAttempt(Long expectedCardRevision, Long expectedGateRevision, Long expectedScheduleRevision,
                               Mode mode, Boolean countRegular, String cramBatchId, Boolean continueBeyondBudget) {}
    public record SaveResponse(Long expectedRevision, ResponseMode responseMode, String response) {}
    public record Reveal(Long expectedRevision, ResponseMode responseMode, String response, Boolean thoughtConfirmed) {}
    public record Rate(Long expectedRevision, Rating rating) {}
    public record Skip(Long expectedRevision, String reason) {}
    public record CreateCramBatch(String title, LocalDate startDate, LocalDate endDate, String zoneId, List<String> cardIds) {}

    public record DailyBudget(long settingsRevision, String zoneId, LocalDate localDate,
                              Instant windowStart, Instant windowEnd, int newLimit, int reviewLimit,
                              long newCards, long reviewCards, long ratedEvents) {}
    public record BudgetAdmission(DailyBudget budget, BudgetKind kind, boolean countedToday,
                                  boolean overBudget, boolean continueBeyondBudget) {}

    // schedule 和 admission 仅在计入常规时固定；不计入的冲刺不借用当时排程作为更新前态。
    public record StartSnapshot(String cardVersionId, int cardVersionNumber, long cardRevision, long gateRevision,
                                Mode mode, boolean countRegular, String cramBatchId, String zoneId,
                                MemoryReviewDtos.Schedule schedule, List<MemoryReviewDtos.SourceSnapshot> sources,
                                BudgetAdmission admission) {}
    public record Invalidation(InvalidationReason reason, String currentCardVersionId, long currentCardRevision,
                               long currentGateRevision, Long currentScheduleRevision,
                               MemoryReviewDtos.SourceHistoryKind sourceHistoryKind, String sourceHistoryId,
                               String scheduleChangeId) {}
    public record Attempt(String id, String cardId, long sequence, StartSnapshot start, long revision, State state,
                          ResponseMode responseMode, String response, Instant responseSavedAt,
                          Instant startedAt, Instant updatedAt, Instant revealedAt, Instant ratedAt, Instant closedAt,
                          Long thinkingElapsedMs, Long totalElapsedMs, Rating rating, String skipReason,
                          Invalidation invalidation, long lastEventSequence) {}

    // 普通详情始终没有参考字段；揭示命令或受揭示状态约束的参考恢复接口才能返回参考。
    public record AttemptDetail(Attempt attempt, MemoryDtos.Version version) {}
    public record RevealedAttempt(AttemptDetail detail, MemoryDtos.Reference reference) {}
    public record RatedAttempt(Attempt attempt, AttemptEvent event) {}
    public record AttemptSummary(String id, String cardId, long sequence, String cardVersionId, int cardVersionNumber,
                                 MemoryDtos.Kind kind, String title, Mode mode, boolean countRegular, String cramBatchId,
                                 State state, long revision, Instant startedAt, Instant updatedAt, Instant revealedAt,
                                 Instant ratedAt, Instant closedAt, Rating rating, InvalidationReason invalidationReason) {}
    public record ActiveAttempt(String cardId, AttemptSummary attempt, Instant asOf) {}
    public record AttemptPage(List<AttemptSummary> items, Long nextBefore) {}
    public record OpenAttemptPage(List<AttemptSummary> items, String nextCursor, Instant asOf) {}

    public record Started(StartSnapshot snapshot) {}
    public record Revealed(ResponseMode responseMode, String response, long thinkingElapsedMs) {}
    public record RatingDay(String zoneId, LocalDate localDate, Instant windowStart, Instant windowEnd, Long settingsRevision) {}
    public record BudgetUse(BudgetKind kind, boolean firstForCardToday, String firstCountedEventId) {}
    public record ScheduledRating(String changeId, long sequence,
                                  MemoryReviewDtos.Schedule before, MemoryReviewDtos.Schedule after) {}
    public record Rated(Rating rating, long thinkingElapsedMs, long totalElapsedMs, RatingDay day,
                        BudgetUse budget, ScheduledRating schedule) {}
    public record Skipped(String reason) {}
    public record Invalidated(Invalidation cause) {}

    // kind 只允许对应一个非空载荷；草稿保存推进 attempt 修订，不伪造 START/REVEAL/RATE 学习事件。
    public record AttemptEvent(String id, String attemptId, long sequence, long attemptRevision,
                               EventKind kind, State fromState, State toState, MemoryReviewDtos.Actor actor,
                               Instant occurredAt, Started started, Revealed revealed, Rated rated,
                               Skipped skipped, Invalidated invalidated) {}
    public record EventPage(List<AttemptEvent> items, Long nextBefore) {}

    // recommended 表示本次推荐集合；真正的预算确认、来源和到期条件仍在开始命令内重查。
    public record QueueItem(String cardId, MemoryDtos.Kind kind, String title, String cardVersionId, int cardVersionNumber,
                            long cardRevision, long gateRevision, long scheduleRevision, String cycleId,
                            MemoryReviewDtos.FsrsPhase phase, Instant effectiveDueAt, BudgetKind budgetKind,
                            boolean countedToday, boolean recommended, boolean requiresBudgetConfirmation,
                            List<MemoryReviewDtos.Warning> warnings) {}
    public record ReviewQueue(List<QueueItem> items, DailyBudget budget, Instant asOf, Instant serverTime,
                              long eligibleDueCards, long recommendedCards, long openAttempts,
                              Instant nextDueAt, boolean includeOverBudget, String nextCursor) {}

    public record CramSelection(String cardId, String cardVersionId, int cardVersionNumber, long cardRevision) {}
    public record CramBatch(String id, String title, LocalDate startDate, LocalDate endDate, String zoneId,
                            List<CramSelection> cards, Instant createdAt) {}
    public record CramSummary(String id, String title, LocalDate startDate, LocalDate endDate,
                              String zoneId, int cardCount, Instant createdAt) {}
    public record CramBatchPage(List<CramSummary> items, String nextCursor) {}
    public record CramSchedule(long revision, MemoryReviewDtos.ScheduleStatus status,
                               MemoryReviewDtos.PauseReason pauseReason, MemoryReviewDtos.FsrsPhase phase,
                               Instant effectiveDueAt) {}
    public record CramMember(int position, CramSelection selection, MemoryDtos.Summary currentCard, Long gateRevision,
                             MemoryReviewDtos.Eligibility eligibility, CramSchedule schedule, AttemptSummary openAttempt) {}
    public record CramMemberPage(List<CramMember> items, Integer nextAfter, Instant asOf) {}
}
