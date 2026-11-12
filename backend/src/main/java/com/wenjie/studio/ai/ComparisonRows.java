package com.wenjie.studio.ai;

import java.time.Instant;

public final class ComparisonRows {
    private ComparisonRows() {}

    public record BindingRow(String ownerId, String taskId, String comparisonId, Instant createdAt) {}
}
