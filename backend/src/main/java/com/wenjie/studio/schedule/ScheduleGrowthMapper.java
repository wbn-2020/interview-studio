package com.wenjie.studio.schedule;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ScheduleGrowthMapper {
    List<ScheduleGrowthRows.Candidate> candidates(@Param("owner") String owner,
            @Param("from") Instant from, @Param("until") Instant until,
            @Param("after") ScheduleGrowthRows.Candidate after, @Param("limit") int limit);
    List<ScheduleGrowthRows.CompletionRow> completions(@Param("owner") String owner,
            @Param("keys") List<ScheduleGrowthRows.Candidate> keys);
}
