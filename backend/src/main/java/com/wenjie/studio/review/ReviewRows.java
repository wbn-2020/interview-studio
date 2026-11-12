package com.wenjie.studio.review;

import java.time.Instant;

public final class ReviewRows {
    private ReviewRows() {}

    public record FeedbackRow(String id, String ownerId, String sessionId, String questionId, String answerId,
                              int revision, Instant createdAt, Instant updatedAt) {}
    public record FeedbackRevisionRow(String ownerId, String feedbackId, int revision,
                                      String contentJson, Instant createdAt) {}
    public record DecisionRow(String id, String ownerId, String feedbackId, int feedbackRevision,
                              int revision, String choice, String reason, Instant createdAt) {}
    public record ActionRow(String id, String ownerId, long revision, String title, String detail,
                            String status, String mergedInto, Instant createdAt, Instant updatedAt) {}
    public record ActionSummaryRow(String id, long revision, String title, String status, String mergedInto,
                                   int sourceCount, int trackingCount, Instant updatedAt) {}

    // 原动作与确切反馈身份不会随合并改变；仅 actionId 表示来源当前归属。
    public record SourceRow(String id, String ownerId, String actionId, String originActionId, String kind,
                            String sessionId, String questionId, String answerId, String feedbackId,
                            Integer feedbackRevision, String taskId, String observationId,
                            String manualKind, String authorLabel, String observationJson, String decisionJson,
                            String verification, String tracking, String trackingReason,
                            Instant createdAt, Instant updatedAt,
                            String recollectionId, String recollectionVersionId, String recollectionJson) {}
    public record HistoryRow(String ownerId, String actionId, long revision, String change,
                             String snapshotJson, Instant createdAt) {}
}
