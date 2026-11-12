package com.wenjie.studio.memory;

import java.time.Instant;

/** 成长查询只读取评分元数据；尝试的作答快照不进入这些行对象。 */
public final class MemoryGrowthRows {
    private MemoryGrowthRows() {}

    public record Candidate(String id, Instant occurredAt) {}

    public record RatingRow(String id, String cardId, String attemptId, long attemptSequence,
                            long eventSequence, long attemptRevision, boolean countRegular,
                            String basisKind, Instant occurredAt, String eventJson,
                            String storedAttemptId, String cardVersionId, Long storedAttemptSequence,
                            Long storedAttemptRevision, String state, String mode, Boolean storedCountRegular,
                            String cramBatchId, String startJson, Instant startedAt, Instant revealedAt,
                            Instant ratedAt, Instant closedAt, String rating, Long lastEventSequence, Boolean upstreamChanged) {}

    public record VersionRow(String id, String cardId, int versionNumber, String kind, String title,
                             int sourceCount, int relatedConceptCount, String currentVersionId,
                             Boolean basisChanged) {}

    public record SourceRow(String cardVersionId, String itemId, String versionId, String kind,
                            Integer versionNumber, String status, String currentVersionId,
                            Boolean bodyChanged, int positionIndex, Boolean upstreamReviewRequired) {
        MemoryReviewRows.SourceRow source() {
            return new MemoryReviewRows.SourceRow(itemId, versionId, kind, versionNumber,
                    status, currentVersionId, bodyChanged, upstreamReviewRequired);
        }
    }
}
