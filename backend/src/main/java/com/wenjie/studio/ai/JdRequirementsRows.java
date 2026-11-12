package com.wenjie.studio.ai;

import java.time.Instant;

public final class JdRequirementsRows {
    private JdRequirementsRows() {}

    public record SourceRow(String ownerId, String taskId, String itemId, String versionId,
                            int number, String title, String kind, int ordinal) {}
    public record DecisionRow(String ownerId, String taskId, String status, String targetItemId,
                              String targetVersionId, String requirementsJson, Instant createdAt) {}
    public record TargetVersion(String id, String ownerId, String itemId, String kind, int number) {}
    public record OriginRow(String taskId, String targetVersionId, int targetVersionNumber, Instant adoptedAt,
                            String purpose, String taskStatus, String manifestJson, String manifestHash,
                            String candidateNumbersJson, String requirementsJson) {}
}
