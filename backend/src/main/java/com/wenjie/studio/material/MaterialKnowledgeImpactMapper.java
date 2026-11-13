package com.wenjie.studio.material;

import com.wenjie.studio.lifecycle.KnowledgeRevalidationRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface MaterialKnowledgeImpactMapper {
    boolean knowledgeExists(@Param("owner") String owner, @Param("itemId") String itemId);
    List<MaterialKnowledgeImpactRows.ImpactRow> impacts(@Param("owner") String owner,
            @Param("context") String context, @Param("itemId") String itemId,
            @Param("correctionId") String correctionId, @Param("state") String state,
            @Param("beforeTime") Instant beforeTime, @Param("beforeId") String beforeId, @Param("limit") int limit);
    MaterialKnowledgeImpactRows.ImpactRow impact(@Param("owner") String owner, @Param("impactId") String impactId);
    int insert(@Param("owner") String owner, @Param("id") String id, @Param("correctionId") String correctionId,
            @Param("domain") String domain, @Param("materialSourceExcerptId") String materialSourceExcerptId,
            @Param("knowledgeSourceItemId") String knowledgeSourceItemId,
            @Param("knowledgeSourceVersionId") String knowledgeSourceVersionId, @Param("taskId") String taskId,
            @Param("targetItemId") String targetItemId, @Param("targetVersionId") String targetVersionId,
            @Param("depth") int depth, @Param("parentImpactId") String parentImpactId, @Param("now") Instant now);
    KnowledgeRevalidationRows.Target target(@Param("owner") String owner, @Param("itemId") String itemId,
                                           @Param("adoptedVersionId") String adoptedVersionId);
    int resolve(@Param("owner") String owner, @Param("itemId") String itemId, @Param("impactId") String impactId,
            @Param("expected") long expected, @Param("choice") String choice, @Param("reason") String reason,
            @Param("targetVersionId") String targetVersionId, @Param("targetRevision") long targetRevision,
            @Param("now") Instant now);
}
