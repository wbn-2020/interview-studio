package com.wenjie.studio.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

import static com.wenjie.studio.memory.MemoryReviewDtos.*;
import static com.wenjie.studio.memory.MemoryReviewRows.*;

@Mapper
public interface MemoryReviewMapper {
    Gate gate(@Param("owner") String owner, @Param("id") String id);
    List<SourceRow> sources(@Param("owner") String owner, @Param("id") String id, @Param("versionId") String versionId);
    UpstreamImpactRow upstreamImpact(@Param("owner") String owner, @Param("impactId") String impactId);
    BasisRow basis(@Param("owner") String owner, @Param("id") String id, @Param("versionId") String versionId);
    List<MemoryRows.CardRow> affectedCards(@Param("owner") String owner, @Param("itemId") String itemId,
                                         @Param("after") String after, @Param("limit") int limit);
    boolean pendingBodyImpact(@Param("owner") String owner, @Param("id") String id, @Param("afterRevision") long afterRevision);
    void insertGate(@Param("owner") String owner, @Param("gate") Gate gate);
    int updateGate(@Param("owner") String owner, @Param("gate") Gate gate, @Param("expected") long expected);
    int insertSourceHistory(@Param("owner") String owner, @Param("id") String id, @Param("cardId") String cardId,
                             @Param("versionId") String versionId, @Param("sequence") long sequence,
                             @Param("gateRevision") long gateRevision, @Param("kind") String kind,
                             @Param("reason") String reason, @Param("snapshot") String snapshot, @Param("now") Instant now);
    List<MemoryHistoryKnowledgeReferences.Binding> historySourceBindings(@Param("owner") String owner,
                              @Param("cardId") String cardId, @Param("versionId") String versionId);
    int insertHistoryKnowledgeReference(@Param("owner") String owner, @Param("cardId") String cardId,
                              @Param("historyId") String historyId, @Param("role") String role,
                              @Param("ordinal") int ordinal, @Param("itemId") String itemId,
                              @Param("versionId") String versionId);
    List<HistoryRow> sourceHistory(@Param("owner") String owner, @Param("id") String id,
                                   @Param("before") Long before, @Param("limit") int limit);
    Long decisionRevision(@Param("owner") String owner, @Param("id") String id, @Param("decisionId") String decisionId);
    ScheduleRow schedule(@Param("owner") String owner, @Param("id") String id);
    void insertSchedule(@Param("owner") String owner, @Param("value") Schedule value);
    int updateSchedule(@Param("owner") String owner, @Param("value") Schedule value, @Param("expected") long expected);
    void insertScheduleHistory(@Param("owner") String owner, @Param("id") String id, @Param("cardId") String cardId,
                               @Param("versionId") String versionId, @Param("sequence") long sequence,
                               @Param("revision") long revision, @Param("kind") String kind, @Param("impactId") String impactId,
                               @Param("snapshot") String snapshot, @Param("now") Instant now);
    List<HistoryRow> scheduleHistory(@Param("owner") String owner, @Param("id") String id,
                                     @Param("before") Long before, @Param("limit") int limit);
    Settings settings(@Param("owner") String owner);
    void insertSettings(@Param("owner") String owner, @Param("value") Settings value);
    int updateSettings(@Param("owner") String owner, @Param("value") Settings value, @Param("expected") long expected);
}
