package com.wenjie.studio.growth;

import java.time.Instant;

/** 一个候选就是一个不可变观察修订；不按当前指针折叠历史。 */
public final class ReviewedObservationGrowthRows {
    private ReviewedObservationGrowthRows() {}

    public record Candidate(String ownerId, String id, long revision, Instant occurredAt, String evidenceKey) {}
}
