package com.wenjie.studio.ai;

public final class ReviewDraftBindingRows {
    private ReviewDraftBindingRows() {}

    public record Binding(String ownerId, String taskId, String kind, String feedbackId, Integer feedbackRevision,
                          String sessionId, String questionId, String answerId, Integer decisionRevision,
                          String decisionChoice, String recollectionId, String recollectionVersionId,
                          Long recollectionRevision) {}
}
