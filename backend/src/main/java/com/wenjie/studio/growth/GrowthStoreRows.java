package com.wenjie.studio.growth;

import java.time.Instant;

/** 成长表只保存个人关联和显示意图，原证据正文始终留在原领域。 */
public final class GrowthStoreRows {
    private GrowthStoreRows() {}

    public record UseRow(String id, String ownerId, String targetId, String targetVersionId, String requirementId,
                         String evidenceKind, String evidenceObjectId, String evidenceVersionId, Long evidenceRevision,
                         String evidencePartId, String evidenceKey, long revision, String coverage,
                         String comparabilityJson, String reason, String status, Instant createdAt, Instant updatedAt) {}
    public record UseHistoryRow(String ownerId, String useId, long revision, String changeKind, String snapshotJson,
                                String sourceStampJson, String targetStamp, String previewHash, Instant createdAt) {}
    public record PreferenceRow(String id, String ownerId, String subjectKind, String subjectKey,
                                String sourceKind, String sourceId, String targetId, String targetVersionId,
                                String requirementId, long revision, String visibility, Instant createdAt, Instant updatedAt) {}
    public record PreferenceHistoryRow(String ownerId, String preferenceId, long revision, String changeKind,
                                       String snapshotJson, Instant createdAt) {}
}
