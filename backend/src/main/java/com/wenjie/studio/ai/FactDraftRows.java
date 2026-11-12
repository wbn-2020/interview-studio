package com.wenjie.studio.ai;

import java.time.Instant;

public final class FactDraftRows {
    private FactDraftRows() {}

    public record SourceRow(String ownerId, String taskId, String kind, String sessionId,
                            String questionId, String answerId) {}
    public record MaterialRow(String ownerId, String taskId, String excerptId, int ordinal) {}
    public record DecisionRow(String ownerId, String taskId, String status, String targetProjectId,
                              String targetItemId, String targetVersionId, Instant createdAt) {}
    public record TargetVersion(String id, String ownerId, String itemId, String kind, String projectId,
                                String projectKind, int number) {}
    public record TargetSource(String responsibility, String sourceJson) {}
    public record OriginRow(String taskId, String targetProjectId, String targetItemId, String targetVersionId,
                            int targetVersionNumber, Instant adoptedAt, String purpose, String taskStatus,
                            String manifestJson, String manifestHash, boolean candidatePresent,
                            Instant taskCreatedAt, Instant taskUpdatedAt) {}
}
