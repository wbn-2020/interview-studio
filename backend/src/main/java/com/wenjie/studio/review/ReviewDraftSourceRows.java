package com.wenjie.studio.review;

import java.time.Instant;

/** 外发只由固定输入白名单构造；原作者、决定理由、引用正文和面试安排不进入来源读取。 */
public final class ReviewDraftSourceRows {
    private ReviewDraftSourceRows() {}

    public record Feedback(String ownerId, String id, int revision, int currentRevision, Integer actualHeadRevision,
                           String sessionId, String questionId, String answerId,
                           String kind, String dimension, String coverage, String observation,
                           String condition, String basis, String limitation, String suggestion,
                           String decisionId, Integer decisionRevision, String decisionChoice,
                           Boolean metadataValid) {}
    public record Recollection(String ownerId, String id, String versionId, int versionNumber,
                               long sourceRevision, String currentVersionId, int headVersionNumber,
                               Integer actualHeadVersionNumber, String status, String title,
                               String baselineKind, String contentJson) {}
    public record Access(String ownerId, String sessionId, String questionId, String answerId,
                         int answerNumber, String answerCondition, String mode, String sessionStatus,
                         String feedbackTiming, Boolean configValid, Boolean earlyFeedback, Instant grantedAt) {}
}
