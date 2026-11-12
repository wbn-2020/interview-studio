package com.wenjie.studio.recent;

import java.time.Instant;
import java.util.List;

public final class RecentDtos {
    private RecentDtos() {}

    public enum Kind {
        KNOWLEDGE, PREPARATION, MATERIAL, QUESTION, COLLECTION, TRAINING, RECOLLECTION,
        MEMORY_CARD, AI_TASK, BOARD, INTERVIEW, PREPARATION_TASK, REPRACTICE
    }

    public record VisitRequest(Kind kind, String id) {}
    public record Visit(Kind kind, String id, Instant visitedAt) {}
    public record Entry(Kind kind, String id, String title, String subtype, String status, Instant visitedAt) {}
    public record Page(List<Entry> items, String nextCursor, boolean scanLimited) {}
}
