package com.wenjie.studio.bank;

import java.time.Instant;

public final class CollectionRows {
    private CollectionRows() {}

    public record CollectionRow(String id, String ownerId, String kind, String title, String status,
                                 long revision, String currentVersionId, int versionNumber, int stepCount,
                                 Instant createdAt, Instant updatedAt) {}
    public record VersionRow(String id, String collectionId, String ownerId, int versionNumber,
                             String title, String contentJson, Instant createdAt) {}
    public record ReferenceRow(String stepId, String questionId, String versionId, String title,
                               int versionNumber, String status, String currentVersionId) {}
}
