package com.wenjie.studio.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class AiRows {
    private AiRows() {}

    public record TaskRow(String id, String ownerId, String purpose, String status, long revision, String title,
                           String provider, String model, String currency, String manifestJson, String manifestHash,
                           String configurationFingerprint, String authorizationEpoch, String currentAttemptId,
                           int attemptCount, String candidateJson, String errorCode, Instant createdAt, Instant updatedAt) {}
    public record SummaryRow(String id, String purpose, String status, long revision, String title, String provider,
                              String model, String currency, int attemptCount, Instant createdAt, Instant updatedAt,
                              String errorCode) {}
    public record AttemptRow(String id, String ownerId, String taskId, int attemptNumber, String status, String feeStatus,
                              String currency, long reservedMicros, Long accountedMicros, Integer promptTokens,
                              Integer completionTokens, String providerRequestId, String errorCode, String reconciliationNote,
                              LocalDate budgetDate, String claimToken, String runtimeEpoch, Instant leaseUntil,
                              Instant createdAt, Instant sentAt, Instant finishedAt,
                              String scopeAuthorizationId, Long scopeAuthorizationRevision) {}
    public record BudgetRow(BigDecimal reportedMicros, BigDecimal reservedMicros, BigDecimal unknownMicros,
                             int unaccountableCount) {}
    public record DecisionRow(String ownerId, String taskId, String status, String mode, String targetItemId,
                              String targetVersionId, Instant createdAt) {}
    public record QuestionDecisionRow(String ownerId, String taskId, String status, String targetQuestionId,
                                      String targetVersionId, Instant createdAt) {}
    public record ExpressionDecisionRow(String ownerId, String taskId, String status, String mode, String targetItemId,
                                        String targetVersionId, Instant createdAt) {}
    public record PreparationPlanDecisionRow(String ownerId, String taskId, String status, String contentJson,
                                             String tasksJson, Instant createdAt) {}
    public record RelationDecisionRow(String ownerId, String taskId, String candidateId, String status,
                                       String relationId, String contentJson, Instant createdAt, String supplementId) {}
    public record RelationSourceRow(String taskId, String itemId, String versionId, int number, String title,
                                     String kind, int ordinal, String state) {}
    public record OriginRow(String taskId, String targetVersionId, int targetVersionNumber, Instant adoptedAt,
                            String manifestJson) {}
    public record OriginSourceRow(String taskId, String itemId, String versionId, int number, String title,
                                  String state, String kind) {}
    public record PreparationOriginSourceRow(String taskId, String itemId, String versionId, int number, String title,
                                             String kind, int ordinal) {}
    public record MaterialDraftOriginRow(String taskId, String excerptId, int ordinal) {}
    public record TrainingDraftSourceRow(String ownerId, String taskId, String sessionId, String questionId,
                                          String answerId, String evaluationTaskId) {}
    public record ExpressionOriginSourceRow(String taskId, String itemId, String versionId, int number, String title,
                                            String kind, int ordinal) {}
    public record FollowUpRow(String ownerId, String taskId, String sessionId, String questionId, String answerId,
                               String rootQuestionId, long generation, String generatedQuestionId, Instant createdAt) {}
}
