package com.wenjie.studio.schedule;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

import static com.wenjie.studio.schedule.SchedulePreparationDtos.*;
import static com.wenjie.studio.schedule.SchedulePreparationRows.*;

@Mapper
public interface SchedulePreparationMapper {
    EventRow event(@Param("owner") String owner, @Param("eventId") String eventId);
    LinkRow link(@Param("owner") String owner, @Param("eventId") String eventId, @Param("id") String id);
    LinkRow bySource(@Param("owner") String owner, @Param("eventId") String eventId,
                     @Param("kind") LinkKind kind, @Param("sourceId") String sourceId);
    List<LinkRow> links(@Param("owner") String owner, @Param("eventId") String eventId,
                        @Param("status") LinkStatus status, @Param("afterId") String afterId, @Param("limit") int limit);
    CollectionRow collection(@Param("owner") String owner, @Param("id") String id, @Param("versionId") String versionId);
    List<MetadataRow> metadata(@Param("owner") String owner, @Param("eventId") String eventId,
                               @Param("sources") List<SourceIdentity> sources);
    void insert(@Param("row") LinkRow row);
    int update(@Param("row") LinkRow row, @Param("expected") long expected);
    void insertHistory(@Param("owner") String owner, @Param("eventId") String eventId, @Param("id") String id,
                        @Param("revision") long revision, @Param("eventRevision") long eventRevision,
                        @Param("change") LinkChange change, @Param("snapshot") String snapshot,
                        @Param("sourceStamp") String sourceStamp, @Param("now") Instant now);
    List<HistoryRow> history(@Param("owner") String owner, @Param("eventId") String eventId,
                             @Param("id") String id, @Param("before") Long before, @Param("limit") int limit);
}
