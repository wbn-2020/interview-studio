package com.wenjie.studio.knowledge;

import java.time.Instant;

public final class RelationRecollectionEvidenceRows {
    private RelationRecollectionEvidenceRows() {}

    public record EntryRow(String ownerId, String id, String relationId, String sourceId, String targetId,
                           String type, String reason, String recollectionId, String versionId,
                           int versionNumber, long sourceRevision, int sourceCount, Instant createdAt,
                           Integer actualVersionNumber) {}
    public record SnapshotRow(String ownerId, String id, String snapshotJson) {}
}
