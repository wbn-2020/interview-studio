package com.wenjie.studio.search;

import java.time.Instant;

public final class UnifiedSearchHistoryRows {
    private UnifiedSearchHistoryRows() { }

    public record AnswerRow(String ownerId, String id, String sessionId, String questionId,
                            Integer versionNumber, String latestAnswerId, Integer answerCount, Integer latestNumber,
                            String title, String mode, String sessionStatus, String feedbackTiming, String questionKind,
                            String answerCondition, Integer assistanceEventSequence, String exposureJson,
                            Instant createdAt, String questionContextJson, Boolean metadataValid,
                            String textsJson, Boolean bodyValid) { }

    public record FeedbackBinding(String ownerId, String id, String sessionId, String questionId, String answerId) { }

    public record FeedbackRow(String ownerId, String id, Integer revision, Integer currentRevision,
                              Integer actualHeadRevision, String sessionId, String questionId, String answerId,
                              String kind, String authorLabel, String dimension, String coverage,
                              String observation, String condition, String basis, String limitation, String suggestion,
                              String decisionId, Integer decisionRevision, String decisionChoice, String decisionReason,
                              Instant decisionCreatedAt, Boolean metadataValid) { }

    public record RecollectionRow(String ownerId, String id, String versionId, Integer versionNumber,
                                  Long sourceRevision, String currentVersionId, Integer headVersionNumber,
                                  Integer actualHeadVersionNumber, String status, String title,
                                  String baselineKind, String contentJson) { }
}
