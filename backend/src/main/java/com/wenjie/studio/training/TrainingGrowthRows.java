package com.wenjie.studio.training;

import java.time.Instant;

/** 成长查询的窄投影；正文只在数据库内计算摘要或比较，不传入成长目录。 */
public final class TrainingGrowthRows {
    private TrainingGrowthRows() {}

    public record Candidate(String id, Instant occurredAt) {}
    public record AnswerRow(String id, String ownerId, String sessionId, String questionId,
                            int versionNumber, String condition, int assistanceEventSequence,
                            String exposureJson, Instant createdAt, String contentHash, String referencesJson) {}
    public record QuestionRow(String id, String ownerId, String sessionId, String sourceQuestionId,
                              String sourceVersionId, String questionKind, String metadataJson,
                              String parentSessionId, String sessionOwnerId, String mode, Long generation, Instant sessionCreatedAt,
                              String contextJson, String repracticeId, String repracticeQuestionMode,
                              String repracticeBaselineKind, String sourceHeadId, String sourceStatus,
                              String sourceCurrentVersionId, String sourceFixedVersionId,
                              String sourceCurrentVersionExistsId) {}
    public record KnowledgeRef(String itemId, String versionId) {}
    public record KnowledgeRow(String itemId, String versionId, String kind, String status,
                               String currentVersionId, String fixedVersionId,
                               String currentVersionExistsId, Boolean bodyChanged, Boolean upstreamPending,
                               Instant knowledgeChangedAt, Instant preparationChangedAt, Instant materialChangedAt) {}
}
