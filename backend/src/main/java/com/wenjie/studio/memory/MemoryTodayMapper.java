package com.wenjie.studio.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface MemoryTodayMapper {
    List<MemoryTodayRows.Candidate> attentionCandidates(@Param("owner") String owner,
            @Param("cursor") MemoryTodayRows.ProjectionCursor cursor, @Param("limit") int limit);
    List<MemoryTodayRows.Candidate> calendarCandidates(@Param("owner") String owner,
            @Param("from") Instant from, @Param("until") Instant until,
            @Param("cursor") MemoryTodayRows.ProjectionCursor cursor, @Param("limit") int limit);
    long calendarCount(@Param("owner") String owner, @Param("from") Instant from, @Param("until") Instant until);
    List<MemoryAttemptRows.QueueRow> queueSources(@Param("owner") String owner, @Param("ids") List<String> ids,
            @Param("from") Instant from, @Param("until") Instant until, @Param("asOf") Instant asOf,
            @Param("newRemaining") long newRemaining, @Param("reviewRemaining") long reviewRemaining,
            @Param("newOver") boolean newOver, @Param("reviewOver") boolean reviewOver);
    List<MemoryTodayRows.OrderedCandidate> orderedOpen(@Param("owner") String owner, @Param("asOf") Instant asOf,
            @Param("after") MemoryTodayDtos.OrderKey after, @Param("limit") int limit);
    List<MemoryTodayRows.OrderedCandidate> orderedAttention(@Param("owner") String owner,
            @Param("after") MemoryTodayDtos.OrderKey after, @Param("limit") int limit);
    List<MemoryTodayRows.OrderedCandidate> orderedCalendar(@Param("owner") String owner,
            @Param("from") Instant from, @Param("until") Instant until,
            @Param("after") MemoryTodayDtos.OrderKey after, @Param("limit") int limit);
    List<MemoryTodayRows.OrderedQueueRow> orderedQueue(@Param("owner") String owner,
            @Param("from") Instant from, @Param("until") Instant until, @Param("asOf") Instant asOf,
            @Param("newRemaining") long newRemaining, @Param("reviewRemaining") long reviewRemaining,
            @Param("newOver") boolean newOver, @Param("reviewOver") boolean reviewOver,
            @Param("includeOverBudget") boolean includeOverBudget,
            @Param("after") MemoryTodayDtos.OrderKey after, @Param("limit") int limit);
}
