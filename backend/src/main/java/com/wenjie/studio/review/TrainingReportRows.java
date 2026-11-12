package com.wenjie.studio.review;

import java.time.Instant;

public final class TrainingReportRows {
    private TrainingReportRows() {}

    public record QuestionCountRow(String questionId, int answerCount, String latestAnswerId) {}
    public record SubmissionRow(String answerId, int number, Instant createdAt, String condition,
                                String exposureJson, boolean accessAllowed, int selfCheckCount,
                                int externalFeedbackCount, int actionSourceCount, int trackingSourceCount) {}
    public record TaskRow(String answerId, String id, String status, long revision, Instant updatedAt,
                          String errorCode) {}
}
