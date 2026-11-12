package com.wenjie.studio.recent;

import java.time.Instant;

public final class RecentRows {
    private RecentRows() {}

    public record VisitRow(String kind, String id, Instant visitedAt) {}
    public record Metadata(String title, String subtype, String status) {}
}
