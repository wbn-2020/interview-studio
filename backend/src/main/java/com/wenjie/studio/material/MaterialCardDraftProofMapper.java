package com.wenjie.studio.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import static com.wenjie.studio.material.MaterialCardDraftProofRows.*;

@Mapper
public interface MaterialCardDraftProofMapper {
    List<Source> bindings(@Param("owner") String owner, @Param("itemId") String itemId, @Param("limit") int limit);
    Task task(@Param("owner") String owner, @Param("taskId") String taskId);
    List<Source> sources(@Param("owner") String owner, @Param("taskId") String taskId, @Param("limit") int limit);
    Decision decision(@Param("owner") String owner, @Param("taskId") String taskId);
    KnowledgeVersion knowledgeVersion(@Param("owner") String owner, @Param("versionId") String versionId);
    Card card(@Param("owner") String owner, @Param("cardId") String cardId);
    CardVersion cardVersion(@Param("owner") String owner, @Param("versionId") String versionId);
    List<CardSource> cardSources(@Param("owner") String owner, @Param("versionId") String versionId,
                                 @Param("limit") int limit);
}
