package com.wenjie.studio.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface MemoryGrowthMapper {
    List<MemoryGrowthRows.Candidate> candidates(@Param("owner") String owner,
            @Param("from") Instant from, @Param("until") Instant until,
            @Param("afterTime") Instant afterTime, @Param("afterId") String afterId,
            @Param("limit") int limit);
    List<MemoryGrowthRows.RatingRow> ratings(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<MemoryGrowthRows.VersionRow> versions(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<MemoryGrowthRows.SourceRow> sources(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<String> topicVersions(@Param("owner") String owner, @Param("ids") List<String> ids,
                               @Param("topicId") String topicId);
}
