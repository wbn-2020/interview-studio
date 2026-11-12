package com.wenjie.studio.memory;

import java.time.Instant;

public final class MemoryRows {
    private MemoryRows() {}

    public record CardRow(String id, String ownerId, String status, long revision,
                          String currentVersionId, int versionNumber, Instant createdAt, Instant updatedAt) {}
    public record SummaryRow(String id, String kind, String title, String status, long revision,
                             String versionId, int versionNumber, int sourceCount, int relatedConceptCount, Instant updatedAt) {}
    public record VersionRow(String id, String cardId, String ownerId, int versionNumber,
                             String kind, String title, String promptJson, Instant createdAt) {}
    public record ReferenceRow(int versionNumber, String referenceText) {}
}
