package com.wenjie.studio.memory;

import com.wenjie.studio.knowledge.KnowledgeDtos;

import java.time.Instant;
import java.util.List;

public final class MemoryReviewDtos {
    private MemoryReviewDtos() {}

    public enum TimeMode { NOW, AT }
    public enum ResumePolicy { KEEP, RESET_AS_NEW }
    public enum ScheduleStatus { ACTIVE, PAUSED }
    public enum PauseReason { MANUAL, SOURCE_REVIEW, CARD_ARCHIVED }
    public enum FsrsPhase { LEARNING, REVIEW, RELEARNING }
    public enum SourceState { CURRENT, SUPERSEDED, ARCHIVED, UNAVAILABLE }
    public enum ActorKind { OWNER, SYSTEM }
    public enum SourceHistoryKind { IMPACT, SOURCE_REVIEW }
    public enum ImpactReason {
        BASELINE_REVIEW_REQUIRED, SOURCE_BODY_CHANGED, SOURCE_UPSTREAM_CHANGED, SOURCE_ARCHIVED, SOURCE_RESTORED,
        CARD_BASIS_CHANGED, CARD_ARCHIVED, CARD_RESTORED
    }
    public enum ScheduleChangeKind { ENABLE, RATE, DEFER, PAUSE, RESUME, RESET, SOURCE_PAUSE, CARD_PAUSE }
    public enum BlockReason {
        CARD_ARCHIVED, GATE_MISSING, REVIEW_REQUIRED, SOURCE_UNAVAILABLE,
        SOURCE_BODY_CHANGED, SOURCE_UPSTREAM_REVIEW_REQUIRED, ARCHIVED_NOTE_SOURCE, NO_VALID_SOURCE
    }
    public enum Warning { ARCHIVED_CONCEPT_WITH_OTHER_SOURCE }

    public record SourceReview(Long expectedCardRevision, Long expectedGateRevision, Boolean confirmed, String reason) {}
    public record EnableSchedule(Long expectedCardRevision, Long expectedGateRevision,
                                 TimeMode timeMode, Instant at, Boolean confirmed) {}
    public record DeferSchedule(Long expectedScheduleRevision, Instant deferUntil, String reason) {}
    public record PauseSchedule(Long expectedScheduleRevision, String reason) {}
    public record ResumeSchedule(Long expectedCardRevision, Long expectedGateRevision, Long expectedScheduleRevision,
                                 ResumePolicy policy, TimeMode timeMode, Instant at, Boolean confirmed, String reason) {}
    public record CreateSettings(String zoneId, Integer dailyNewBudget, Integer dailyReviewBudget) {}
    public record SaveSettings(Long expectedRevision, String zoneId, Integer dailyNewBudget, Integer dailyReviewBudget) {}

    public record Actor(ActorKind kind, String ownerId) {}
    public record Gate(String cardId, long revision, boolean reviewRequired, String lastReviewDecisionId,
                       long lastHistorySequence, Instant updatedAt) {}
    public record SourceSnapshot(String itemId, String versionId, KnowledgeDtos.Kind kind, Integer versionNumber,
                                 SourceState state, String currentVersionId, Boolean bodyChanged, Boolean upstreamReviewRequired) {}

    // basisReady 只说明卡片与来源基线可用；不表示已有正式尝试、已到期或具备评分条件。
    public record Eligibility(boolean sourceReviewAllowed, boolean basisReady,
                              List<BlockReason> blockers, List<Warning> warnings) {}
    public record RecallState(String cardId, long cardRevision, String cardVersionId, MemoryDtos.Status cardStatus,
                              Gate gate, List<SourceSnapshot> sources, Schedule schedule,
                              Eligibility eligibility, Instant asOf) {}

    public record SourceChange(String itemId, KnowledgeDtos.Kind kind, List<String> referencedVersionIds,
                               String previousVersionId, String currentVersionId,
                               KnowledgeDtos.Status previousStatus, KnowledgeDtos.Status currentStatus) {}
    public record Impact(String id, String cardId, String cardVersionId, long gateRevision,
                         ImpactReason reason, SourceChange source, boolean reviewRequired, Actor actor, Instant createdAt,
                         String upstreamImpactId) {}
    public record SourceReviewDecision(String id, String cardId, String cardVersionId, long cardRevision,
                                       long reviewedGateRevision, long resultingGateRevision,
                                       List<SourceSnapshot> sources, String reason, Actor actor, Instant createdAt) {}
    public record SourceHistoryEntry(long sequence, SourceHistoryKind kind, Impact impact, SourceReviewDecision decision) {}
    public record ImpactPage(List<SourceHistoryEntry> items, Long nextBefore) {}

    // 保存领域状态，不使用库类型；A 批只写新卡初态，延期不改 dueAt 或 lastReviewAt。
    public record FsrsState(FsrsPhase phase, Integer step, Double stability, Double difficulty,
                            Instant dueAt, Instant lastReviewAt) {}
    public record Schedule(String cardId, long revision, String cycleId, ScheduleStatus status, PauseReason pauseReason,
                           String algorithmVersion, String parameterVersion, FsrsState state,
                           Instant suggestedDueAt, Instant deferUntil, Instant effectiveDueAt,
                           String lastRatedEventId, long lastHistorySequence, Instant createdAt, Instant updatedAt) {}
    public record ScheduleChange(String id, long sequence, String cardId, String cardVersionId, long cardRevision,
                                 long gateRevision, ScheduleChangeKind kind, Schedule before, Schedule after,
                                 String impactId, Actor actor, String reason, Instant createdAt) {}
    public record ScheduleHistoryPage(List<ScheduleChange> items, Long nextBefore) {}

    public record Settings(boolean configured, Long revision, String zoneId, int dailyNewBudget, int dailyReviewBudget,
                           Instant createdAt, Instant updatedAt) {}
}
