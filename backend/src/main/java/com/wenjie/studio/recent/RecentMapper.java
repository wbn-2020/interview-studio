package com.wenjie.studio.recent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface RecentMapper {
    List<RecentRows.VisitRow> visits(@Param("owner") String owner, @Param("kind") String kind,
                                      @Param("cursorTime") Instant cursorTime, @Param("cursorKind") String cursorKind,
                                      @Param("cursorId") String cursorId, @Param("limit") int limit);
    RecentRows.Metadata metadata(@Param("owner") String owner, @Param("kind") String kind, @Param("id") String id);
    Instant visitTime(@Param("owner") String owner, @Param("kind") String kind, @Param("id") String id);
    Instant knowledgeVisitTime(@Param("owner") String owner, @Param("id") String id);
    void upsertVisit(@Param("owner") String owner, @Param("kind") String kind,
                     @Param("id") String id, @Param("now") Instant now);
}
