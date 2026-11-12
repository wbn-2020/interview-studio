package com.wenjie.studio.review;

import java.time.Instant;

/** 当前行只指向修订；审阅正文、检查项和依据随修订追加，不能覆盖历史。 */
public final class StandardReviewStoreRows {
    private StandardReviewStoreRows() {}

    public record ReviewRow(String id, String ownerId, String questionId, String versionId,
                            long revision, String status, Instant createdAt, Instant updatedAt) {}

    public record RevisionRow(String ownerId, String reviewId, long revision, String questionId,
                              String versionId, String changeKind, String snapshotJson,
                              String changeReason, Instant createdAt) {}

    public record CheckpointRow(String ownerId, String reviewId, long revision, String checkpointId,
                                int positionIndex, String decision) {}

    public record EvidenceRow(String ownerId, String reviewId, long revision, String evidenceId,
                              int positionIndex, int roleMask, String basisKind, String knowledgeItemId,
                              String knowledgeVersionId, String excerptId) {}
}
