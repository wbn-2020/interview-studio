package com.wenjie.studio.knowledge;

import java.time.Instant;

public final class KnowledgeRows {
    private KnowledgeRows() {}

    public record Item(String id, String ownerId, String kind, String status, long metadataRevision,
                       String currentVersionId, int versionNumber, Instant createdAt, Instant updatedAt) {}
    public record Summary(String id, String kind, String title, String status, long revision,
                          int versionNumber, Instant updatedAt) {}
    public record TopicLink(String itemId, String topicId) {}
    public record Node(String id, String title, double x, double y) {}
}
