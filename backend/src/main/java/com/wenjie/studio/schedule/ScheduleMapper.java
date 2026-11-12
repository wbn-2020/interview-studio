package com.wenjie.studio.schedule;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ScheduleMapper {
    ScheduleRows.EventRow event(@Param("owner") String owner, @Param("id") String id);
    List<ScheduleRows.EventRow> events(@Param("owner") String owner, @Param("from") Instant from,
            @Param("to") Instant to, @Param("targetId") String targetId, @Param("status") String status,
            @Param("cursorTime") Instant cursorTime, @Param("cursorId") String cursorId, @Param("limit") int limit);
    List<ScheduleRows.EventRow> reminders(@Param("owner") String owner, @Param("asOf") Instant asOf,
            @Param("cursorTime") Instant cursorTime, @Param("cursorId") String cursorId, @Param("limit") int limit);
    List<ScheduleRows.MaterialRow> materials(@Param("owner") String owner, @Param("id") String id,
                                            @Param("revision") long revision);
    List<ScheduleDtos.HistorySummary> histories(@Param("owner") String owner, @Param("id") String id,
                                               @Param("before") Long before, @Param("limit") int limit);
    String snapshot(@Param("owner") String owner, @Param("id") String id, @Param("revision") long revision);
    void insertEvent(@Param("event") ScheduleRows.EventRow event);
    int updateEvent(@Param("event") ScheduleRows.EventRow event, @Param("expected") long expected);
    void insertHistory(@Param("history") ScheduleRows.HistoryRow history);
    void insertMaterial(@Param("owner") String owner, @Param("id") String id, @Param("revision") long revision,
            @Param("ordinal") int ordinal, @Param("kind") String kind, @Param("itemId") String itemId,
            @Param("versionId") String versionId);
}
