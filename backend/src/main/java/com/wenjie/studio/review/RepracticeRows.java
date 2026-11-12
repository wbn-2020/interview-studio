package com.wenjie.studio.review;

import java.time.Instant;

public final class RepracticeRows {
    private RepracticeRows() {}

    public record RepracticeRow(String id, String ownerId, String baselineSessionId, String baselineQuestionId,
                                String baselineAnswerId, String sessionId, String questionId,
                                String questionMode, Instant createdAt, String baselineKind,
                                String recollectionId, String recollectionVersionId, Long recollectionRevision) {}
    public record SourceRow(String ownerId, String repracticeId, String sourceId, String actionId,
                            long actionRevision, int position, String snapshotJson) {}
    public record ComparisonRow(String id, String ownerId, String repracticeId, String sessionId,
                                String questionId, String answerId, int answerNumber, Instant createdAt) {}
    public record JudgmentRow(String id, String ownerId, String sourceId, int revision, String comparisonId,
                              String answerId, String verification, String outcome, String reason,
                              String citationsJson, Instant createdAt) {}
}
