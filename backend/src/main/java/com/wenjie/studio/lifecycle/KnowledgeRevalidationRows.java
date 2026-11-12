package com.wenjie.studio.lifecycle;

import java.time.Instant;

public final class KnowledgeRevalidationRows {
    private KnowledgeRevalidationRows() {}

    public record Adoption(String taskId, String sourceItemId, String sourceVersionId, int sourceVersionNumber,
                           String targetItemId, String targetVersionId, int targetVersionNumber) {}
    public record ImpactRow(String id, long revision, String eventId, String rootItemId, String rootTitle,
                            String rootPreviousVersionId, String rootCurrentVersionId, String changeKind,
                            String sourceVersionId, String taskId, String targetItemId, String targetTitle,
                            String targetVersionId, int targetVersionNumber, Instant createdAt,
                            String choice, String reason, String resolutionTargetVersionId,
                            Long resolutionTargetRevision, Instant decidedAt, String sourceItemId, String sourceTitle,
                            int sourceVersionNumber, int depth, String parentImpactId, Instant eventCreatedAt) {}
    public record Target(String id, String status, long revision, String currentVersionId, Integer currentVersionNumber,
                         String publishedTitle, String publishedBody, String draftTitle, String draftBody,
                         Integer adoptedVersionNumber, String adoptedBody) {}
}
