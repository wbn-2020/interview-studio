package com.wenjie.studio.memory;

import java.time.Instant;

public final class MemoryReviewRows {
    private MemoryReviewRows() {}

    public record SourceRow(String itemId, String versionId, String kind, Integer versionNumber,
                            String status, String currentVersionId, Boolean bodyChanged, Boolean upstreamReviewRequired) {}
    public record UpstreamImpactRow(String itemId, String adoptedVersionId, int adoptedVersionNumber, Instant createdAt) {}
    public record BasisRow(String promptJson, String referenceText) {}
    public record HistoryRow(long sequence, String snapshotJson) {}
    public record ScheduleRow(String cardId, long revision, String cycleId, String status, String pauseReason,
                              String algorithmVersion, String parameterVersion, String phase, Integer step,
                              Double stability, Double difficulty, Instant suggestedDueAt, Instant lastReviewAt,
                              Instant deferUntil, String lastRatedEventId, long lastHistorySequence,
                              Instant createdAt, Instant updatedAt) {}
}
