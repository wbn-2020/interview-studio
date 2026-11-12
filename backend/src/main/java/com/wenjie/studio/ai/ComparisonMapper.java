package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ComparisonMapper {
    ComparisonRows.BindingRow binding(@Param("owner") String owner, @Param("taskId") String taskId);
    List<AiRows.TaskRow> tasks(@Param("owner") String owner, @Param("comparisonId") String comparisonId,
                               @Param("cursor") String cursor, @Param("limit") int limit);
    int taskCount(@Param("owner") String owner, @Param("comparisonId") String comparisonId);
    int pendingCount(@Param("owner") String owner, @Param("comparisonId") String comparisonId,
                     @Param("excludedTaskId") String excludedTaskId);
    void insertBinding(@Param("binding") ComparisonRows.BindingRow binding);
}
