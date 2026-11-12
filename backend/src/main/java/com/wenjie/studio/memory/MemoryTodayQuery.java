package com.wenjie.studio.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.wenjie.studio.memory.MemoryAttemptRules.*;
import static com.wenjie.studio.memory.MemoryReviewRules.*;
import static com.wenjie.studio.memory.MemoryTodayDtos.*;
import static com.wenjie.studio.memory.MemoryTodayRows.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MemoryTodayQuery {
    private final MemoryTodayMapper mapper;
    private final MemoryAttemptMapper attempts;
    private final MemoryQueueService queues;
    private final MemoryReviewService reviews;
    private final JsonMapper json;

    public MemoryTodayQuery(MemoryTodayMapper mapper, MemoryAttemptMapper attempts, MemoryQueueService queues,
                            MemoryReviewService reviews, JsonMapper json) {
        this.mapper = mapper; this.attempts = attempts; this.queues = queues; this.reviews = reviews; this.json = json;
    }

    public SourceProjection sources(String owner, List<String> cardIds, Instant asOf, Instant serverTime) {
        MemoryRules.uuid(owner); time(asOf); time(serverTime); ids(cardIds);
        if (asOf.isAfter(serverTime)) throw invalid("回忆聚合时间不能晚于服务器时间。");
        var settings = reviews.settings(owner);
        if (cardIds.isEmpty()) return new SourceProjection(List.of(), settings, asOf);
        if (attempts.slotProblems(owner, null)) throw unavailable();
        var items = projections(owner, cardIds);
        if (settings.configured()) {
            var budget = queues.budgetAt(owner, asOf, serverTime);
            var recommendations = mapper.queueSources(owner, cardIds, budget.windowStart(), budget.windowEnd(), asOf,
                    Math.max(budget.newLimit() - budget.newCards(), 0), Math.max(budget.reviewLimit() - budget.reviewCards(), 0),
                    budget.newCards() >= budget.newLimit(), budget.reviewCards() >= budget.reviewLimit())
                    .stream().map(MemoryQueueService::queueItem)
                    .collect(Collectors.toMap(MemoryAttemptDtos.QueueItem::cardId, Function.identity()));
            items = items.stream().map(item -> new CardProjection(item.card(), item.gate(), item.schedule(), item.eligibility(),
                    item.openAttempt(), recommendations.get(item.card().id()))).toList();
        }
        return bounded(json, new SourceProjection(items, settings, asOf), DIRECTORY_BYTES);
    }

    public ProjectionPage attention(String owner, Instant asOf, String cursor, int limit, int scanLimit) {
        MemoryRules.uuid(owner); time(asOf); page(null, limit);
        if (scanLimit < 1 || scanLimit > 500) throw invalid("回忆提醒每次扫描上限须为 1 至 500。");
        ProjectionCursor position = projectionCursor(cursor, "memory-attention", owner, asOf, null, null);
        if (attempts.slotProblems(owner, null)) throw unavailable();
        List<CardProjection> items = new ArrayList<>();
        int scanned = 0;
        boolean more = false;
        while (scanned < scanLimit && items.size() < limit) {
            // 不预消费可能落在下一页的匹配行；lookahead 只确认还有候选，不展开其来源。
            int batchSize = Math.min(Math.min(50, scanLimit - scanned), limit - items.size());
            var rows = mapper.attentionCandidates(owner, position, batchSize + 1);
            var selected = rows.stream().limit(batchSize).toList();
            if (selected.isEmpty()) { more = false; break; }
            var projected = projections(owner, selected.stream().map(Candidate::cardId).toList());
            for (int index = 0; index < selected.size(); index++) {
                Candidate row = selected.get(index);
                CardProjection item = projected.get(index);
                position = new ProjectionCursor(1, "memory-attention", owner, asOf, null, null, row.sortTime(), row.cardId());
                scanned++;
                if (item.card().status() == MemoryDtos.Status.ACTIVE && item.openAttempt() == null && !item.eligibility().blockers().isEmpty()) {
                    items.add(item);
                }
            }
            more = rows.size() > batchSize;
            if (!more) break;
        }
        // 只在首页完整消费候选集合时知道全范围计数；后续末页仍保持未知总数。
        Long exactTotal = cursor == null && !more ? count(items.size()) : null;
        return bounded(json, new ProjectionPage(List.copyOf(items), more ? cursor(position) : null,
                more && scanned == scanLimit, scanned, exactTotal, asOf), DIRECTORY_BYTES);
    }

    public ProjectionPage calendarWindow(String owner, Instant fromInclusive, Instant toExclusive, Instant asOf, String cursor, int limit) {
        MemoryRules.uuid(owner); time(fromInclusive); time(toExclusive); time(asOf); page(null, limit);
        if (!fromInclusive.isBefore(toExclusive)) throw invalid("回忆日历开始时间须早于结束时间。");
        var key = projectionCursor(cursor, "memory-calendar", owner, asOf, fromInclusive, toExclusive);
        if (attempts.slotProblems(owner, null)) throw unavailable();
        var rows = mapper.calendarCandidates(owner, fromInclusive, toExclusive, key, limit + 1);
        var selected = rows.stream().limit(limit).toList();
        var items = projections(owner, selected.stream().map(Candidate::cardId).toList());
        String next = rows.size() > limit ? cursor(new ProjectionCursor(1, "memory-calendar", owner, asOf, fromInclusive, toExclusive,
                selected.getLast().sortTime(), selected.getLast().cardId())) : null;
        return bounded(json, new ProjectionPage(items, next, false, selected.size(),
                count(mapper.calendarCount(owner, fromInclusive, toExclusive)), asOf), DIRECTORY_BYTES);
    }

    public OrderedPage orderedOpen(String owner, Instant asOf, OrderKey after, int limit) {
        orderedRequest(owner, asOf, after, limit, false);
        if (after != null && after.time().isAfter(asOf)) throw invalid("未决回忆排序位置不能晚于读取边界。");
        var page = orderedPage(owner, mapper.orderedOpen(owner, asOf, after, limit + 1), limit, null);
        for (OrderedCard item : page.items()) {
            var attempt = item.projection().openAttempt();
            if (attempt == null || !attempt.id().equals(item.key().id()) || !attempt.startedAt().equals(item.key().time())) {
                throw unavailable();
            }
        }
        return page;
    }

    public OrderedPage orderedAttention(String owner, Instant asOf, OrderKey after, int limit) {
        orderedRequest(owner, asOf, after, limit, false);
        // 这里保留有序候选，今日层依据实际 Eligibility 和安排偏好逐项消费，才能诚实计量扫描量。
        return orderedPage(owner, mapper.orderedAttention(owner, after, limit + 1), limit, null);
    }

    public OrderedPage orderedCalendar(String owner, Instant fromInclusive, Instant toExclusive, Instant asOf, OrderKey after, int limit) {
        orderedRequest(owner, asOf, after, limit, false); time(fromInclusive); time(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw invalid("回忆日历开始时间须早于结束时间。");
        if (after != null && (after.time().isBefore(fromInclusive) || !after.time().isBefore(toExclusive))) {
            throw invalid("日历排序位置不在请求区间。");
        }
        return orderedPage(owner, mapper.orderedCalendar(owner, fromInclusive, toExclusive, after, limit + 1), limit,
                count(mapper.calendarCount(owner, fromInclusive, toExclusive)));
    }

    public OrderedPage orderedQueue(String owner, Instant asOf, Instant serverTime, OrderKey after, int limit, boolean includeOverBudget) {
        orderedRequest(owner, asOf, after, limit, true);
        if (after != null && after.time().isAfter(asOf)) throw invalid("到期回忆排序位置不能晚于读取边界。");
        var budget = queues.budgetAt(owner, asOf, serverTime);
        long newRemaining = Math.max(budget.newLimit() - budget.newCards(), 0);
        long reviewRemaining = Math.max(budget.reviewLimit() - budget.reviewCards(), 0);
        var totals = attempts.queueTotals(owner, budget.windowStart(), budget.windowEnd(), asOf, newRemaining, reviewRemaining);
        var rows = mapper.orderedQueue(owner, budget.windowStart(), budget.windowEnd(), asOf, newRemaining, reviewRemaining,
                budget.newCards() >= budget.newLimit(), budget.reviewCards() >= budget.reviewLimit(), includeOverBudget, after, limit + 1);
        var selected = rows.stream().limit(limit).toList();
        var projected = projections(owner, selected.stream().map(OrderedQueueRow::cardId).toList());
        List<OrderedCard> items = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            var row = selected.get(index);
            var item = projected.get(index);
            var queue = MemoryQueueService.queueItem(row.queueRow());
            items.add(new OrderedCard(new CardProjection(item.card(), item.gate(), item.schedule(), item.eligibility(),
                    item.openAttempt(), queue), new OrderKey(row.orderValue(), row.phaseRank(), row.effectiveDueAt(), row.cardId())));
        }
        // 原队列总数只描述 B 的资格与推荐；今日抑制可能继续减少展示数，因此 exactTotal 保持未知。
        var info = new QueueInfo(budget, count(totals.eligibleDueCards()), count(totals.recommendedCards()),
                attempts.nextDue(owner, asOf), includeOverBudget);
        return bounded(json, new OrderedPage(List.copyOf(items), rows.size() > limit, null, info), DIRECTORY_BYTES);
    }

    private OrderedPage orderedPage(String owner, List<OrderedCandidate> rows, int limit, Long exactTotal) {
        var selected = rows.stream().limit(limit).toList();
        var projected = projections(owner, selected.stream().map(OrderedCandidate::cardId).toList());
        List<OrderedCard> items = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            var row = selected.get(index);
            items.add(new OrderedCard(projected.get(index), new OrderKey(row.orderValue(), row.phaseRank(), row.sortTime(), row.finalId())));
        }
        return bounded(json, new OrderedPage(List.copyOf(items), rows.size() > limit, exactTotal, null), DIRECTORY_BYTES);
    }

    private void orderedRequest(String owner, Instant asOf, OrderKey after, int limit, boolean withPhase) {
        MemoryRules.uuid(owner); time(asOf); page(null, limit);
        if (after != null) {
            if (after.orderValue() < 0 || after.orderValue() > 1_000_000 || withPhase != (after.phaseRank() != null)
                    || after.phaseRank() != null && (after.phaseRank() < 0 || after.phaseRank() > 1)) {
                throw invalid("回忆安排排序位置无效。");
            }
            time(after.time()); MemoryRules.uuid(after.id());
        }
        if (attempts.slotProblems(owner, null)) throw unavailable();
    }

    private List<CardProjection> projections(String owner, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        var cards = attempts.cards(owner, ids).stream().collect(Collectors.toMap(MemoryRows.CardRow::id, Function.identity()));
        var summaries = attempts.summaries(owner, ids).stream().collect(Collectors.toMap(MemoryRows.SummaryRow::id, Function.identity()));
        var gates = attempts.gates(owner, ids).stream().collect(Collectors.toMap(MemoryReviewDtos.Gate::cardId, Function.identity()));
        var schedules = attempts.schedules(owner, ids).stream()
                .collect(Collectors.toMap(MemoryReviewRows.ScheduleRow::cardId, MemoryReviewRules::schedule));
        var active = attempts.activeSummaries(owner, ids).stream()
                .collect(Collectors.toMap(MemoryAttemptDtos.AttemptSummary::cardId, Function.identity()));
        var sources = attempts.sources(owner, ids).stream().collect(Collectors.groupingBy(MemoryAttemptRows.CardSourceRow::cardId));
        List<CardProjection> result = new ArrayList<>();
        for (String id : ids) {
            var card = cards.get(id);
            if (card == null) throw MemoryRules.missing();
            var summary = summaries.get(id);
            if (summary == null || !card.currentVersionId().equals(summary.versionId()) || card.versionNumber() != summary.versionNumber()) {
                throw new IllegalStateException("回忆卡当前版本与管理摘要不一致");
            }
            var sourceRows = sources.getOrDefault(id, List.of()).stream().map(source -> new MemoryReviewRows.SourceRow(source.itemId(),
                    source.versionId(), source.kind(), source.versionNumber(), source.status(), source.currentVersionId(), source.bodyChanged(), source.upstreamReviewRequired())).toList();
            var current = new MemoryDtos.Summary(summary.id(), MemoryDtos.Kind.valueOf(summary.kind()), summary.title(),
                    MemoryDtos.Status.valueOf(summary.status()), summary.revision(), summary.versionId(), summary.versionNumber(),
                    summary.sourceCount(), summary.relatedConceptCount(), summary.updatedAt());
            result.add(new CardProjection(current, gates.get(id), schedules.get(id),
                    eligibility(card, gates.get(id), MemoryReviewRules.sources(sourceRows)), active.get(id), null));
        }
        return List.copyOf(result);
    }

    private ProjectionCursor projectionCursor(String source, String purpose, String owner, Instant asOf, Instant from, Instant until) {
        if (source == null) return null;
        if (source.isEmpty() || source.length() > 2048 || !source.matches("[A-Za-z0-9_-]+")) throw invalid("回忆聚合游标无效。");
        ProjectionCursor value;
        try {
            value = json.readValue(Base64.getUrlDecoder().decode(source), ProjectionCursor.class);
            // 沿用 B 的规范编码；重编码必须完全一致，拒绝未知、缺失、重复和强制转换字段。
            if (value == null || !source.equals(cursor(value))) throw invalid("回忆聚合游标不是规范形式。");
        } catch (RuntimeException exception) { throw invalid("回忆聚合游标无效。"); }
        if (value.version() != 1 || !purpose.equals(value.purpose()) || !owner.equals(value.owner())
                || !asOf.equals(value.asOf()) || !Objects.equals(from, value.from()) || !Objects.equals(until, value.until())) {
            throw invalid("回忆聚合游标与当前来源、时间或区间不匹配。");
        }
        time(value.time()); MemoryRules.uuid(value.cardId());
        if (from != null && (value.time().isBefore(from) || !value.time().isBefore(until))) throw invalid("日历游标排序键不在请求区间。");
        return value;
    }

    private static void ids(List<String> values) {
        if (values == null || values.size() > 50) throw invalid("回忆聚合每批最多读取 50 张卡。");
        values.forEach(MemoryRules::uuid);
        if (new HashSet<>(values).size() != values.size()) throw invalid("回忆聚合卡标识不能重复。");
    }
}
