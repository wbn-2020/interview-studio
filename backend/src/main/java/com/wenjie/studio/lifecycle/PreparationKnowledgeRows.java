package com.wenjie.studio.lifecycle;

import java.time.Instant;

public final class PreparationKnowledgeRows {
    private PreparationKnowledgeRows() {}
    public record ObservationRow(String itemId, String kind, String projectId, String title, String versionId,
                                 int versionNumber, long sourceGeneration, long revision, String status,
                                 String verification) {}
    public record EventRow(String id, String rootItemId, String rootKind, String rootTitle, String rootProjectId,
                           String changeKind, String previousVersionId, int previousVersionNumber,
                           long previousGeneration, long previousRevision, String previousStatus, String previousVerification,
                           String currentVersionId, int currentVersionNumber, long currentGeneration, long currentRevision,
                           String currentStatus, String currentVerification, Instant createdAt) {}
    public record Candidate(String taskId, String manifestJson, String targetItemId,
                            String targetVersionId, int targetVersionNumber) {}
    public record Binding(String itemId, String versionId, String kind, int number, String title, int ordinal) {}
    public record ImpactRow(String id, long revision, String eventId, String sourceDomain, String sourceKind,
                            String sourceItemId, String sourceTitle, String sourceVersionId, int sourceVersionNumber,
                            String dependencyKind, String viaFactItemId, String viaFactTitle, String viaFactVersionId,
                            Integer viaFactVersionNumber, String taskId, String targetItemId, String targetTitle,
                            String targetVersionId, int targetVersionNumber, int depth, String parentImpactId,
                            Instant createdAt, String choice, String reason, String resolutionTargetVersionId,
                            Long resolutionTargetRevision, Instant decidedAt) {}
}
