package com.wenjie.studio.memory;

import java.time.Instant;

public final class MemoryTodayRows {
    private MemoryTodayRows() {}

    public record Candidate(String cardId, Instant sortTime) {}
    public record ProjectionCursor(int version, String purpose, String owner, Instant asOf,
                                   Instant from, Instant until, Instant time, String cardId) {}
    public record OrderedCandidate(String cardId, int orderValue, Integer phaseRank, Instant sortTime, String finalId) {}
    public record OrderedQueueRow(String cardId, String kind, String title, String cardVersionId, int cardVersionNumber,
                                  long cardRevision, long gateRevision, long scheduleRevision, String cycleId,
                                  String phase, Instant effectiveDueAt, String budgetKind, boolean countedToday,
                                  boolean recommended, boolean requiresBudgetConfirmation, boolean archivedConcept,
                                  int orderValue, int phaseRank) {
        public MemoryAttemptRows.QueueRow queueRow() {
            return new MemoryAttemptRows.QueueRow(cardId, kind, title, cardVersionId, cardVersionNumber,
                    cardRevision, gateRevision, scheduleRevision, cycleId, phase, effectiveDueAt, budgetKind,
                    countedToday, recommended, requiresBudgetConfirmation, archivedConcept);
        }
    }
}
