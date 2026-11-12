package com.wenjie.studio.training;

import java.time.Instant;

public final class TrainingRows {
    private TrainingRows() {}

    public record SessionRow(String id, String ownerId, String title, String mode, String status, long revision,
                              boolean materialAccess, int assistanceEventSequence, int eventCount, String configJson,
                              String contextJson, int questionCount, long observedMillis, long unobservedMillis,
                              long pausedMillis, Instant observationAt, Instant pauseStartedAt, Instant createdAt,
                              Instant updatedAt, Instant startedAt, Instant endedAt, String endChecklistJson, long generation) {}
    public record QuestionRow(String id, String ownerId, String sessionId, String sourceQuestionId,
                               String sourceVersionId, String stage, String title, String status, long revision,
                               String snapshotJson, String draftJson, Instant draftSavedAt, String latestAnswerId,
                               int answerCount, String exposureJson, int positionIndex, String kind, String rootQuestionId,
                               String parentQuestionId, String parentAnswerId, String aiTaskId) {}
    public record AnswerRow(String id, String ownerId, String sessionId, String questionId, int versionNumber,
                             String contentJson, String condition, int assistanceEventSequence,
                             String exposureJson, Instant createdAt) {}
    public record BoardImportRow(String id, String ownerId, String sessionId, String boardId,
                                  long boardRevision, Instant createdAt) {}
}
