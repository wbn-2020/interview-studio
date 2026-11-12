package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CardDraftMapper {
    List<CardDraftRows.SourceRow> sources(@Param("owner") String owner, @Param("taskId") String taskId);
    int insertSource(@Param("source") CardDraftRows.SourceRow source);
    CardDraftRows.DecisionRow decision(@Param("owner") String owner, @Param("taskId") String taskId);
    int insertDecision(@Param("decision") CardDraftRows.DecisionRow decision);
    List<CardDraftRows.OriginRow> origins(@Param("owner") String owner, @Param("cardId") String cardId,
            @Param("through") int through, @Param("before") Integer before, @Param("limit") int limit);
    List<CardDraftRows.OriginSourceRow> originSources(@Param("owner") String owner, @Param("taskIds") List<String> taskIds);
}
