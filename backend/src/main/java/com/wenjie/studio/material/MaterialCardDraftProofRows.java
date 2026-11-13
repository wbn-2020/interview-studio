package com.wenjie.studio.material;

import java.time.Instant;

public final class MaterialCardDraftProofRows {
    private MaterialCardDraftProofRows() {}

    public record Source(String ownerId, String taskId, String itemId, String versionId, Integer number,
                         String title, String kind, Integer ordinal) {}
    public record Task(String ownerId, String id, String purpose, String status, Long revision,
                       String manifestJson, String manifestHash, String candidateJson,
                       Instant createdAt, Instant updatedAt, Boolean otherPurposeUses) {}
    public record Decision(String ownerId, String taskId, String status, String targetCardId,
                           String targetVersionId, Instant createdAt) {}
    public record KnowledgeVersion(String ownerId, String itemId, String id, Integer number, String title,
                                   String body, Instant createdAt, String itemOwnerId, String actualItemId,
                                   String kind, String status) {}
    public record Card(String ownerId, String id, String status, Long revision, String currentVersionId,
                       Integer versionNumber, Instant createdAt, Instant updatedAt) {}
    public record CardVersion(String ownerId, String cardId, String id, Integer number, String kind,
                              String title, String promptJson, Integer sourceCount, Integer relatedConceptCount,
                              Instant createdAt) {}
    public record CardSource(String ownerId, String cardId, String versionId, String itemId,
                             String knowledgeVersionId, Integer position) {}
}
