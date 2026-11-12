package com.wenjie.studio.knowledge;

import java.time.Instant;

public final class RelationSupplementRows {
    private RelationSupplementRows() {}

    public record RelationRow(String ownerId, String id, String sourceId, String targetId, String type,
                              String sourceKind, String sourceStatus, String targetKind, String targetStatus) {}
    public record EntryRow(String ownerId, String id, String relationId, String sourceId, String targetId,
                           String type, String origin, String reason, int sourceCount, String taskId, String candidateId, Instant createdAt) {}
    public record SourceVersion(String ownerId, String itemId, String versionId, int number, String title,
                                String kind, String status, String currentVersionId) {}
    public record SourceRow(String ownerId, String supplementId, int ordinal, String itemId, String versionId,
                            int number, String title, String kind, Integer actualNumber, String actualTitle,
                            String actualKind, String actualStatus, String currentVersionId) {}
}
