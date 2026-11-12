package com.wenjie.studio.ai;

import java.time.Instant;

/** 成长查询只携带评价固定输入及必要核对结果，不读取供应商配置或决定原因。 */
public final class AiGrowthRows {
    private AiGrowthRows() {}

    public record Candidate(String taskId, String partId, Instant occurredAt) {}
    public record TaskRow(String id, String ownerId, String purpose, String status, String title,
                          String currentAttemptId, String manifestPurpose, String evaluationJson, String candidateJson,
                          String attemptId, String attemptOwnerId, String attemptTaskId, String attemptStatus,
                          Instant finishedAt, String bindingTaskId, String sessionId, String questionId,
                          String answerId, String accessAnswerId) {}
    public record InputRow(String sessionId, String questionId, String answerId, String sessionStatus,
                           String mode, String feedbackTiming, Integer answerNumber, String answerCondition,
                           String sourceQuestionId, String sourceVersionId, Boolean questionMatches,
                           Boolean answerMatches, String standardVersionId, Boolean standardMatches) {}
    public record DecisionRow(String id, String ownerId, String taskId, String feedbackId,
                              int revision, String choice, Instant createdAt) {}
}
