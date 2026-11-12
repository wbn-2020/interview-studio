package com.wenjie.studio.ai;

/** 来源元数据与报告原文分次读取，评价权限检查不加载报告、原答或题面。 */
public final class RelationAiFeedbackSourceRows {
    private RelationAiFeedbackSourceRows() {}

    public record Metadata(String ownerId, String taskId, String purpose, String status,
                           String currentAttemptId, int attemptCount,
                           String bindingOwnerId, String bindingTaskId, String sessionId, String questionId, String answerId,
                           String attemptOwnerId, String attemptTaskId, String attemptId, Integer attemptNumber, String attemptStatus,
                           String answerOwnerId, String answerSessionId, String answerQuestionId, String actualAnswerId,
                           Integer answerNumber, String answerCondition) {}
    public record Candidate(String ownerId, String taskId, String candidateJson) {}
    public record Decision(String ownerId, String taskId, String feedbackId, String id, int revision, String choice) {}
}
