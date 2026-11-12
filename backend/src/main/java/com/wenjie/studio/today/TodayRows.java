package com.wenjie.studio.today;

import java.time.Instant;
import java.time.LocalDate;

/** 今日只投影原对象的摘要，不保存第二份完成状态，也不读取答案或参考正文。 */
public final class TodayRows {
    private TodayRows() {}

    public record KnowledgeRow(String id, String kind, String title, String status, long itemRevision,
                               String currentVersionId, Long draftRevision, Instant draftSavedAt,
                               boolean unpublishedDraft, boolean complete, int orderValue, LocalDate placementDate) {}
    public record TrainingRow(String id, String title, String mode, String status, long revision, long generation,
                              Instant updatedAt, int questionCount, int savedDraftCount, Instant lastDraftSavedAt,
                              Instant lastActivityAt, String repracticeId, int orderValue, LocalDate placementDate) {}
    public record QuestionRow(String sessionId, String id, long revision, String status, boolean hasDraft,
                              Instant draftSavedAt, String latestAnswerId, int answerCount, int positionIndex) {}
    public record TargetRow(String sourceId, String itemId, String versionId) {}
    public record TaskRow(String id, long revision, String title, int minutes, LocalDate plannedDate, String zoneId,
                          String status, String targetItemId, String targetVersionId, Instant startedAt,
                          Instant completedAt, Instant updatedAt, int orderValue) {}
    public record ActionRow(String id, long revision, String title, String status, String mergedInto, Instant updatedAt,
                            int orderValue, LocalDate placementDate) {}
    public record InterviewRow(String id, long revision, String title, String targetId, String targetVersionId,
                               Instant startAt, Instant endAt, String zoneId, Instant remindAt,
                               Instant reminderAcknowledgedAt, String status, int orderValue) {}

    public record Key(int orderValue, Integer rank, Instant instant, LocalDate date, String id) {}
    // 日期候选只用 UTC 的最大合法偏移收窄范围；最终归属仍由 Java 按任务原 IANA 时区核对。
    public record Query(String owner, String targetId, String section, Instant asOf, Instant dayStart,
                        Instant dayEnd, Instant upcomingEnd, LocalDate from, LocalDate to,
                        Instant windowStart, Instant windowEnd, LocalDate earliestDate, LocalDate latestDate,
                        Integer afterOrderValue, Instant afterInstant, LocalDate afterDate, String afterId, int limit) {
        public Query after(Key key, int size) {
            return new Query(owner, targetId, section, asOf, dayStart, dayEnd, upcomingEnd, from, to,
                    windowStart, windowEnd, earliestDate, latestDate,
                    key == null ? null : key.orderValue(),
                    key == null ? null : key.instant(), key == null ? null : key.date(),
                    key == null ? null : key.id(), size);
        }
    }
}
