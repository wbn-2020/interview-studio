package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EvaluationMapper {
    EvaluationRows.AccessRow access(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                    @Param("questionId") String questionId, @Param("answerId") String answerId);
    void insertAccess(@Param("row") EvaluationRows.AccessRow row);
    EvaluationRows.BindingRow binding(@Param("owner") String owner, @Param("taskId") String taskId);
    void insertBinding(@Param("row") EvaluationRows.BindingRow row);
    int taskCount(@Param("owner") String owner, @Param("answerId") String answerId);
    int pendingCount(@Param("owner") String owner, @Param("answerId") String answerId,
                     @Param("excludedTaskId") String excludedTaskId);
    List<EvaluationRows.SummaryRow> tasks(@Param("owner") String owner, @Param("sessionId") String sessionId,
            @Param("questionId") String questionId, @Param("cursor") String cursor, @Param("limit") int limit);
    List<AiDtos.FeedbackDecision> decisions(@Param("owner") String owner, @Param("taskId") String taskId);
    void insertDecision(@Param("owner") String owner, @Param("taskId") String taskId,
                        @Param("decision") AiDtos.FeedbackDecision decision);
    List<AiDtos.EvaluationCoverageItem> coverage(@Param("owner") String owner, @Param("sessionId") String sessionId);
}
