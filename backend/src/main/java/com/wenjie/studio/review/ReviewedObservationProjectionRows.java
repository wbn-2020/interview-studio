package com.wenjie.studio.review;

import java.time.Instant;

public final class ReviewedObservationProjectionRows {
    private ReviewedObservationProjectionRows() {}

    public record Answer(String ownerId, String sessionId, String questionId, String answerId,
                         String mode, String sourceQuestionId, String sourceVersionId, String questionKind,
                         int answerNumber, String answerCondition, int assistanceEventSequence,
                         String exposureJson, Instant answerCreatedAt, String contentFingerprint,
                         String snapshotFingerprint, String technology, String scenario, String questionConditions,
                         boolean technologyRecorded, boolean scenarioRecorded, boolean conditionsRecorded,
                         boolean metadataValid) {}
}
