package com.wenjie.studio.ai;

import java.time.Instant;

public final class EvaluationRows {
    private EvaluationRows() {}
    public record AccessRow(String ownerId, String sessionId, String questionId, String answerId,
                            boolean earlyFeedback, Instant grantedAt) {}
    public record BindingRow(String ownerId, String taskId, String sessionId, String questionId,
                             String answerId, Instant createdAt) {}
    public record SummaryRow(String id, String purpose, String status, long revision, String title,
                             String provider, String model, String currency, int attemptCount,
                             Instant createdAt, Instant updatedAt, String errorCode,
                             String questionId, String answerId, int answerNumber) {}
}
