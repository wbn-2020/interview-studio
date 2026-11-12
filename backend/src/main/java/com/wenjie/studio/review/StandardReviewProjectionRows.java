package com.wenjie.studio.review;

import java.time.Instant;

/** 成长投影只接收身份、摘要与定位核对结果；来源正文留在数据库内。 */
public final class StandardReviewProjectionRows {
    private StandardReviewProjectionRows() {}

    public record Question(String id, String ownerId, String versionId, String currentVersionId,
                           String status, String sourceContentHash, boolean checkpointPresent,
                           String sealedContentHash, String fingerprint, Instant sealedAt) {}
    public record QuestionStamp(String ownerId, String questionId, String versionId,
                                String sourceContentHash, String fingerprint, Instant createdAt) {}
    public record KnowledgeRequest(String evidenceId, String itemId, String versionId, int start, int end, String quote) {}
    public record Knowledge(String evidenceId, String id, String ownerId, String kind, String status,
                            String currentVersionId, String versionId, String currentFingerprint, boolean quoteMatches) {}
    public record MaterialRequest(String evidenceId, String excerptId, int start, int end, String quote) {}
    public record Material(String evidenceId, String excerptId, String ownerId, String attachmentId,
                           String attachmentStatus, String attachmentFingerprint, boolean chainValid, boolean quoteMatches) {}
}
