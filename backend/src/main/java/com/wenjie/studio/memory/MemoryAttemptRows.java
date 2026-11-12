package com.wenjie.studio.memory;

import java.time.Instant;

public final class MemoryAttemptRows {
    private MemoryAttemptRows() {}

    public record SnapshotRow(String snapshotJson) {}
    public record LastAttempt(long sequence, Instant updatedAt) {}
    public record FirstRating(String eventId, String kind) {}
    public record BudgetTotals(long newCards, long reviewCards, long ratedEvents) {}
    public record QueueTotals(long eligibleDueCards, long recommendedCards) {}
    public record QueueRow(String cardId, String kind, String title, String cardVersionId, int cardVersionNumber,
                           long cardRevision, long gateRevision, long scheduleRevision, String cycleId,
                           String phase, Instant effectiveDueAt, String budgetKind, boolean countedToday,
                           boolean recommended, boolean requiresBudgetConfirmation, boolean archivedConcept) {}
    public record SelectionRow(int position, String cardId, String cardVersionId, int cardVersionNumber, long cardRevision) {}
    public record CardSourceRow(String cardId, String itemId, String versionId, String kind, Integer versionNumber,
                                String status, String currentVersionId, Boolean bodyChanged, Boolean upstreamReviewRequired) {}
}
