package com.wenjie.studio.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface MemoryAttemptMapper {
    MemoryAttemptRows.SnapshotRow attempt(@Param("owner") String owner, @Param("id") String id);
    MemoryAttemptRows.LastAttempt lastAttempt(@Param("owner") String owner, @Param("cardId") String cardId);
    String slot(@Param("owner") String owner, @Param("cardId") String cardId);
    boolean slotProblems(@Param("owner") String owner, @Param("cardId") String cardId);
    long openCount(@Param("owner") String owner);
    MemoryAttemptDtos.AttemptSummary activeSummary(@Param("owner") String owner, @Param("cardId") String cardId);
    List<MemoryAttemptDtos.AttemptSummary> openAttempts(@Param("owner") String owner, @Param("asOf") Instant asOf,
            @Param("cursor") MemoryAttemptRules.DirectoryCursor cursor, @Param("limit") int limit);
    List<MemoryAttemptDtos.AttemptSummary> attempts(@Param("owner") String owner, @Param("cardId") String cardId,
            @Param("before") Long before, @Param("limit") int limit);
    List<MemoryReviewRows.HistoryRow> events(@Param("owner") String owner, @Param("id") String id,
            @Param("before") Long before, @Param("limit") int limit);
    void insertAttempt(@Param("owner") String owner, @Param("a") MemoryAttemptDtos.Attempt attempt,
            @Param("start") String start, @Param("snapshot") String snapshot);
    int updateAttempt(@Param("owner") String owner, @Param("a") MemoryAttemptDtos.Attempt attempt,
            @Param("snapshot") String snapshot, @Param("expected") long expected);
    void insertSlot(@Param("owner") String owner, @Param("cardId") String cardId, @Param("attemptId") String attemptId,
            @Param("state") MemoryAttemptDtos.State state);
    int releaseSlot(@Param("owner") String owner, @Param("cardId") String cardId, @Param("attemptId") String attemptId);
    void insertEvent(@Param("owner") String owner, @Param("a") MemoryAttemptDtos.Attempt attempt,
            @Param("e") MemoryAttemptDtos.AttemptEvent event, @Param("basisKind") String basisKind, @Param("snapshot") String snapshot);
    int insertKnowledgeSource(@Param("owner") String owner, @Param("cardId") String cardId, @Param("attemptId") String attemptId,
            @Param("ordinal") int ordinal, @Param("itemId") String itemId, @Param("versionId") String versionId);
    MemoryAttemptRows.FirstRating firstRating(@Param("owner") String owner, @Param("cardId") String cardId,
            @Param("from") Instant from, @Param("until") Instant until);
    MemoryAttemptRows.BudgetTotals budget(@Param("owner") String owner, @Param("from") Instant from, @Param("until") Instant until);
    MemoryAttemptRows.QueueTotals queueTotals(@Param("owner") String owner, @Param("from") Instant from, @Param("until") Instant until,
            @Param("asOf") Instant asOf, @Param("newRemaining") long newRemaining, @Param("reviewRemaining") long reviewRemaining);
    List<MemoryAttemptRows.QueueRow> queue(@Param("owner") String owner, @Param("from") Instant from, @Param("until") Instant until,
            @Param("asOf") Instant asOf, @Param("newRemaining") long newRemaining, @Param("reviewRemaining") long reviewRemaining,
            @Param("newOver") boolean newOver, @Param("reviewOver") boolean reviewOver, @Param("includeOverBudget") boolean includeOverBudget,
            @Param("cursor") MemoryAttemptRules.QueueCursor cursor, @Param("limit") int limit);
    Instant nextDue(@Param("owner") String owner, @Param("asOf") Instant asOf);
    MemoryAttemptRows.SnapshotRow batch(@Param("owner") String owner, @Param("id") String id);
    boolean member(@Param("owner") String owner, @Param("batchId") String batchId, @Param("cardId") String cardId);
    List<MemoryAttemptDtos.CramSummary> batches(@Param("owner") String owner,
            @Param("cursor") MemoryAttemptRules.DirectoryCursor cursor, @Param("limit") int limit);
    List<MemoryAttemptRows.SelectionRow> selections(@Param("owner") String owner, @Param("id") String id,
            @Param("after") int after, @Param("limit") int limit);
    void insertBatch(@Param("owner") String owner, @Param("b") MemoryAttemptDtos.CramBatch batch,
            @Param("count") int count, @Param("snapshot") String snapshot);
    void insertMember(@Param("owner") String owner, @Param("batchId") String batchId,
            @Param("position") int position, @Param("selection") MemoryAttemptDtos.CramSelection selection);
    List<MemoryRows.CardRow> cards(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<MemoryRows.SummaryRow> summaries(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<MemoryReviewDtos.Gate> gates(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<MemoryReviewRows.ScheduleRow> schedules(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<MemoryAttemptRows.CardSourceRow> sources(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<MemoryAttemptDtos.AttemptSummary> activeSummaries(@Param("owner") String owner, @Param("ids") List<String> ids);
}
