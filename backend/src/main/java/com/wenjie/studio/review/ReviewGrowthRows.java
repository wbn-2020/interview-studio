package com.wenjie.studio.review;

import java.time.Instant;

/** 成长读取只携带身份、条件和摘要；正文在 SQL 投影边界内排除。 */
public final class ReviewGrowthRows {
    private ReviewGrowthRows() {}

    public record Candidate(String kind, String objectId, String versionId, Long revision,
                            Instant occurredAt, String evidenceKey) {}
    public record Feedback(String ownerId, String id, int revision, Integer currentRevision,
                           String sessionId, String questionId, String answerId, Instant createdAt,
                           String kind, String authorLabel, String dimension, String coverage,
                           String contentHash, Boolean metadataValid) {}
    public record Decision(String id, String ownerId, String feedbackId, int feedbackRevision,
                           int revision, String choice, Instant createdAt) {}
    public record Recollection(String ownerId, String id, String versionId, int versionNumber,
                               String title, String baselineKind, Instant createdAt, String status,
                               Long revision, String currentVersionId, Integer headVersionNumber,
                               Integer actualCurrentVersionNumber, String interviewId, Long interviewRevision,
                               String interviewJson, Boolean hasAnswerFragment, Boolean hasFeedback,
                               Boolean hasUncertainty, String contentHash, Boolean metadataValid) {}
    public record Judgment(String ownerId, String id, String sourceId, int revision, String comparisonId,
                           String repracticeId, String answerId, String verificationHash, String outcome,
                           Instant createdAt, String contentHash, Boolean metadataValid) {}
    public record Comparison(String ownerId, String id, String repracticeId, String sessionId,
                             String questionId, String answerId, int answerNumber,
                             Integer actualAnswerNumber, String sessionStatus) {}
    public record Source(String ownerId, String id, String actionId, String originActionId, String kind,
                         String sessionId, String questionId, String answerId, String feedbackId,
                         Integer feedbackRevision, String taskId, String observationId,
                         String recollectionId, String recollectionVersionId, Integer recollectionVersionNumber,
                         String recollectionBaselineKind, String verificationHash, String tracking,
                         Long actionRevision, String actionStatus, String mergedInto, Boolean metadataValid) {}
    public record Snapshot(String ownerId, String repracticeId, String sourceId, String actionId,
                           long actionRevision, int position, String identityJson,
                           String verificationHash, Boolean metadataValid) {}
    public record FixedIdentity(String sourceId, String actionId, long actionRevision, String id,
                                String originActionId, String kind, String sessionId, String questionId,
                                String answerId, String feedbackId, Integer feedbackRevision, String taskId,
                                String observationId, String recollectionId, String recollectionVersionId,
                                Integer recollectionVersionNumber, String recollectionBaselineKind, String tracking) {}
    public record ReplayStart(String ownerId, String id, String sessionId, String questionId,
                              String questionMode, Instant startedAt, String sessionStatus,
                              Integer questionCount, String questionKind) {}
}
