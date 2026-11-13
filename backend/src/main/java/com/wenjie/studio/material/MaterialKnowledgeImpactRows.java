package com.wenjie.studio.material;

import java.time.Instant;

public final class MaterialKnowledgeImpactRows {
    private MaterialKnowledgeImpactRows() {}

    public record ImpactRow(String ownerId, String id, Long revision, String correctionId, String rootExcerptId,
                            Instant correctionCreatedAt, String sourceDomain, String materialSourceExcerptId,
                            String knowledgeSourceItemId, String knowledgeSourceVersionId,
                            Integer sourceVersionNumber, String sourceTitle, String taskId,
                            String taskPurpose, String taskStatus, String targetItemId, String targetTitle,
                            String targetVersionId, Integer targetVersionNumber, Integer depth,
                            String parentImpactId, Instant createdAt, String choice, String reason,
                            String resolutionTargetVersionId, Long resolutionTargetRevision, Instant decidedAt,
                            String adoptionStatus, String materialBindingTaskId, String knowledgeBindingTaskId) {}
}
