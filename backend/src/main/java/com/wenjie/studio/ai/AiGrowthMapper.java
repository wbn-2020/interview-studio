package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AiGrowthMapper {
    List<AiGrowthRows.Candidate> candidates(@Param("owner") String owner,
            @Param("startAt") Instant startAt, @Param("observedUntil") Instant observedUntil,
            @Param("afterAt") Instant afterAt, @Param("afterTaskId") String afterTaskId,
            @Param("afterPartId") String afterPartId, @Param("limit") int limit);
    List<AiGrowthRows.TaskRow> tasks(@Param("owner") String owner, @Param("ids") List<String> ids);
    AiGrowthRows.InputRow input(@Param("owner") String owner, @Param("taskId") String taskId);
    List<AiGrowthRows.DecisionRow> decisions(@Param("owner") String owner, @Param("taskId") String taskId);
}
