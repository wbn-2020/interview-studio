package com.wenjie.studio.planning;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface PlanningGrowthMapper {
    List<PlanningGrowthRows.Candidate> candidates(@Param("owner") String owner,
            @Param("from") Instant from, @Param("until") Instant until,
            @Param("after") PlanningGrowthRows.Candidate after, @Param("limit") int limit);
    List<PlanningGrowthRows.CompletionRow> completions(@Param("owner") String owner,
            @Param("keys") List<PlanningGrowthRows.Candidate> keys);
}
