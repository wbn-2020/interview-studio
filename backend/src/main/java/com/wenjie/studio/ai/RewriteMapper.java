package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RewriteMapper {
    RewriteRows.BindingRow binding(@Param("owner") String owner, @Param("taskId") String taskId);

    void insertBinding(@Param("row") RewriteRows.BindingRow row);

    int taskCount(@Param("owner") String owner, @Param("answerId") String answerId);

    int pendingCount(@Param("owner") String owner, @Param("answerId") String answerId,
                     @Param("excludedTaskId") String excludedTaskId);

    List<EvaluationRows.SummaryRow> tasks(@Param("owner") String owner, @Param("sessionId") String sessionId,
            @Param("questionId") String questionId, @Param("answerId") String answerId,
            @Param("cursor") String cursor, @Param("limit") int limit);
}
