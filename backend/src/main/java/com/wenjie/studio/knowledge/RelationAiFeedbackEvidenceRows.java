package com.wenjie.studio.knowledge;

import java.time.Instant;

public final class RelationAiFeedbackEvidenceRows {
    private RelationAiFeedbackEvidenceRows() {}

    public record EntryRow(String ownerId, String id, String relationId, String sourceId, String targetId,
                           String type, String reason, String taskId, String attemptId, String feedbackId,
                           String reportHash, int sourceCount, String sessionId, String questionId, String answerId,
                           Integer decisionRevision, String decisionChoice, Instant createdAt,
                           Integer actualDecisionRevision, String actualDecisionChoice) {}
    public record SnapshotRow(String ownerId, String id, String snapshotJson) {}
}
