package com.wenjie.studio.ai;

/** 生成任务只绑定成功评价的确切身份；外发原文保留在任务清单中。 */
public final class RelationAiFeedbackDraftRows {
    private RelationAiFeedbackDraftRows() {}

    public record Binding(String ownerId, String taskId, String evaluationTaskId, String attemptId,
                          String reportHash, String feedbackId, String sessionId, String questionId,
                          String answerId, Integer decisionRevision, String decisionChoice,
                          String level, int sourceCount) {}
}
