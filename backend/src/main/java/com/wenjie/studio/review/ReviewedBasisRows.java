package com.wenjie.studio.review;

import java.time.Instant;

public final class ReviewedBasisRows {
    private ReviewedBasisRows() {}

    public record GenerationRow(String ownerId, String sourceKind, String sourceId,
                                long generation, Instant updatedAt) {}
    public record ChangeRow(String ownerId, String sourceKind, String sourceId, long generation,
                            String changeKind, String previousVersionId, String currentVersionId,
                            String previousStatus, String currentStatus, Instant createdAt, String upstreamVersionId) {}
    public record KnowledgeRow(String ownerId, String itemId, String versionId, String status) {}
}
