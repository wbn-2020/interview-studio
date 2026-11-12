package com.wenjie.studio.knowledge;

import java.time.Instant;

public final class RelationEvidenceRows {
    private RelationEvidenceRows() {}

    public record EntryRow(String ownerId, String id, String relationId, String sourceId, String targetId,
                           String type, String reason, String sourceKind, int sourceCount,
                           String sessionId, String questionId, String answerId, Instant createdAt) {}
    public record SnapshotRow(String ownerId, String id, String snapshotJson) {}
    public record MaterialBindingRow(String ownerId, String evidenceId, String sourceKind,
                                     String excerptId, int ordinal) {}
}
