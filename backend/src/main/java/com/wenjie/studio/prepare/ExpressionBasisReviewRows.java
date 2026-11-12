package com.wenjie.studio.prepare;

import java.time.Instant;

public final class ExpressionBasisReviewRows {
    private ExpressionBasisReviewRows() {}

    public record ReviewRow(String id, String itemId, String versionId, int versionNumber, long revision,
                            String kind, String reason, long itemRevision, Instant createdAt) {}
    public record BasisRow(int ordinal, String itemId, String versionId, String title, int versionNumber,
                           String projectId, long sourceGeneration, long projectGeneration) {}
}
