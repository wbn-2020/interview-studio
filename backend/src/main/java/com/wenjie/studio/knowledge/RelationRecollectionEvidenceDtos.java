package com.wenjie.studio.knowledge;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.ReviewDraftDtos;

import java.time.Instant;
import java.util.List;

/** 手工回忆证据固定个人补记来源，不生成模型授权或正式训练答案。 */
public final class RelationRecollectionEvidenceDtos {
    private RelationRecollectionEvidenceDtos() {}

    public record Create(String reason, ReviewDraftDtos.RecollectionSelection source, Boolean confirmed) {}
    public record Entry(String id, String relationId, String sourceId, String targetId,
                        KnowledgeDtos.RelationType type, String reason, String recollectionId,
                        String versionId, int versionNumber, long sourceRevision, int sourceCount,
                        Instant createdAt) {}
    public record Page(String relationId, boolean relationExists, List<Entry> items, String nextCursor) {}
    public record Detail(Entry entry, boolean relationExists, ReviewDraftDtos.Input source,
                         AiDtos.OriginState sourceState) {}
}
