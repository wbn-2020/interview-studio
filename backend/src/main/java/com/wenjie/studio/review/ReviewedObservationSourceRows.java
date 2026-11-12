package com.wenjie.studio.review;

import java.time.Instant;

public final class ReviewedObservationSourceRows {
    private ReviewedObservationSourceRows() {}

    public record Access(String ownerId, String sessionId, String questionId, String answerId,
                         String sessionStatus, String configJson, boolean earlyFeedback, Instant grantedAt) {}
    public record Answer(String ownerId, String sessionId, String questionId, String answerId,
                         String mode, String sessionStatus, String configJson, String sourceQuestionId,
                         String sourceVersionId, String questionKind, String snapshotJson, String contentJson,
                         int answerNumber, String answerCondition, int assistanceEventSequence,
                         String exposureJson, Instant answerCreatedAt, boolean earlyFeedback, Instant grantedAt) {}
}
