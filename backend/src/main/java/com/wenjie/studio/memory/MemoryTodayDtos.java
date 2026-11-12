package com.wenjie.studio.memory;

import java.time.Instant;
import java.util.List;

public final class MemoryTodayDtos {
    private MemoryTodayDtos() {}

    // 聚合只接收管理摘要；题面、参考和作答留在各自受守卫保护的领域读取中。
    public record CardProjection(MemoryDtos.Summary card, MemoryReviewDtos.Gate gate,
                                 MemoryReviewDtos.Schedule schedule, MemoryReviewDtos.Eligibility eligibility,
                                 MemoryAttemptDtos.AttemptSummary openAttempt, MemoryAttemptDtos.QueueItem queueItem) {}

    public record SourceProjection(List<CardProjection> items, MemoryReviewDtos.Settings settings, Instant asOf) {}

    // exactTotal 仅在整个范围已核实后有值；后续页走到末尾不能把本页数量当作全量。
    public record ProjectionPage(List<CardProjection> items, String nextCursor, boolean scanLimited,
                                 int scannedCount, Long exactTotal, Instant asOf) {}

    // 未决流的最终身份是 attemptId，其余是 cardId；仅到期队列使用 phaseRank。
    public record OrderKey(int orderValue, Integer phaseRank, Instant time, String id) {}
    public record OrderedCard(CardProjection projection, OrderKey key) {}
    public record QueueInfo(MemoryAttemptDtos.DailyBudget budget, long eligibleDueCards,
                            long recommendedCards, Instant nextDueAt, boolean includeOverBudget) {}

    // items 只含本批候选；lookahead 不进入 items，今日层按实际消费推进位置并核实偏好排除后的数量。
    public record OrderedPage(List<OrderedCard> items, boolean hasMore, Long exactTotal, QueueInfo queueInfo) {}
}
