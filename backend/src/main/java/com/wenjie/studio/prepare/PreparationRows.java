package com.wenjie.studio.prepare;

import java.time.Instant;

public final class PreparationRows {
    private PreparationRows() {}

    public record ItemRow(String id, String ownerId, String kind, String projectId, String title, String status,
                          long revision, String verification, boolean favorite, String currentVersionId,
                          int versionNumber, Instant createdAt, Instant updatedAt) {}
    public record VersionRow(String id, String itemId, String ownerId, int versionNumber, String title,
                             String contentJson, Instant createdAt) {}
    public record ReferenceRow(String sourceKind, String sourceItemId, String sourceVersionId) {}
    public record ExpressionBasisRow(String sourceKind, String sourceItemId, String sourceVersionId,
                                     String useType, String versionItemId, String itemKind, String title,
                                     Integer versionNumber, String projectId) {}
    public record DecisionRow(String id, String versionId, String decision, String reason, Instant createdAt) {}
}
