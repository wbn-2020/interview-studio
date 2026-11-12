package com.wenjie.studio.growth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ReviewedObservationGrowthMapper {
    List<ReviewedObservationGrowthRows.Candidate> candidates(@Param("owner") String owner,
            @Param("start") Instant start, @Param("end") Instant end, @Param("afterAt") Instant afterAt,
            @Param("afterKey") String afterKey, @Param("limit") int limit);
}
