package com.wenjie.studio.review;

import java.time.Instant;

/** 回答与检查项身份固定；每次观察修订保留当时确切审阅，正文只追加到历史。 */
public final class ReviewedObservationRows {
    private ReviewedObservationRows() {}

    public record ObservationRow(String id, String ownerId, String sessionId, String questionId,
                                 String answerId, String checkpointId, long revision, String status,
                                 Instant createdAt, Instant updatedAt) {}

    public record RevisionRow(String ownerId, String observationId, long revision, String checkpointId,
                              String standardReviewId, long standardReviewRevision, String changeKind,
                              String snapshotJson, String changeReason, Instant createdAt) {}
}
