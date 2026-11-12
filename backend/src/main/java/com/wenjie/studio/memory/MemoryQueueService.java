package com.wenjie.studio.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static com.wenjie.studio.memory.MemoryAttemptDtos.*;
import static com.wenjie.studio.memory.MemoryAttemptRules.*;
import static com.wenjie.studio.memory.MemoryReviewRules.*;

@Service
public class MemoryQueueService {
    private final MemoryAttemptMapper mapper;
    private final MemoryReviewMapper reviews;
    private final JsonMapper json;

    public MemoryQueueService(MemoryAttemptMapper mapper, MemoryReviewMapper reviews, JsonMapper json) {
        this.mapper = mapper; this.reviews = reviews; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewQueue queue(String owner, String cursor, int limit, boolean includeOverBudget) {
        Instant serverTime = now();
        QueueCursor key = queueCursor(cursor, serverTime);
        return readQueue(owner, key == null ? serverTime : key.asOf(), serverTime, key, limit, includeOverBudget);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewQueue queueAt(String owner, Instant asOf, Instant serverTime, String cursor, int limit, boolean includeOverBudget) {
        time(asOf); time(serverTime);
        QueueCursor key = queueCursor(cursor, serverTime);
        if (key != null && !key.asOf().equals(asOf)) {
            throw problem("MEMORY_QUEUE_CHANGED", "队列时间边界已变化，请从队列首页重新读取。");
        }
        return readQueue(owner, asOf, serverTime, key, limit, includeOverBudget);
    }

    private ReviewQueue readQueue(String owner, Instant asOf, Instant serverTime, QueueCursor key, int limit, boolean includeOverBudget) {
        MemoryRules.uuid(owner); page(null, limit);
        DailyBudget budget = budgetAt(owner, asOf, serverTime);
        if (key != null) {
            if (!day(key.zoneId(), key.asOf(), key.settingsRevision()).localDate().equals(key.localDate())) {
                throw invalid("队列游标日期与到期边界不一致。");
            }
            if (key.settingsRevision() != budget.settingsRevision() || !key.zoneId().equals(budget.zoneId())
                    || !key.localDate().equals(budget.localDate()) || key.includeOverBudget() != includeOverBudget) {
                throw problem("MEMORY_QUEUE_CHANGED", "复习日期、设置或筛选已变化，请从队列首页重新读取。");
            }
        }
        if (mapper.slotProblems(owner, null)) throw unavailable();
        long newRemaining = Math.max(budget.newLimit() - budget.newCards(), 0);
        long reviewRemaining = Math.max(budget.reviewLimit() - budget.reviewCards(), 0);
        // 推荐名额在 SQL 完整集合内分配，最后才应用游标与 LIMIT；每页不会重新获得一份名额。
        var totals = mapper.queueTotals(owner, budget.windowStart(), budget.windowEnd(), asOf, newRemaining, reviewRemaining);
        var rows = mapper.queue(owner, budget.windowStart(), budget.windowEnd(), asOf, newRemaining, reviewRemaining,
                budget.newCards() >= budget.newLimit(), budget.reviewCards() >= budget.reviewLimit(), includeOverBudget, key, limit + 1);
        List<QueueItem> items = rows.stream().limit(limit).map(MemoryQueueService::queueItem).toList();
        String nextCursor = null;
        if (rows.size() > limit) {
            QueueItem last = items.getLast();
            nextCursor = cursor(new QueueCursor(1, "review-queue", asOf, budget.settingsRevision(), budget.zoneId(), budget.localDate(),
                    includeOverBudget, last.phase() == MemoryReviewDtos.FsrsPhase.REVIEW ? 1 : 0, last.effectiveDueAt(), last.cardId()));
        }
        return bounded(json, new ReviewQueue(items, budget, asOf, serverTime, count(totals.eligibleDueCards()),
                count(totals.recommendedCards()), count(mapper.openCount(owner)), mapper.nextDue(owner, asOf), includeOverBudget, nextCursor), DIRECTORY_BYTES);
    }

    static QueueItem queueItem(MemoryAttemptRows.QueueRow row) {
        return new QueueItem(row.cardId(), MemoryDtos.Kind.valueOf(row.kind()), row.title(), row.cardVersionId(), row.cardVersionNumber(),
                row.cardRevision(), row.gateRevision(), row.scheduleRevision(), row.cycleId(), MemoryReviewDtos.FsrsPhase.valueOf(row.phase()),
                row.effectiveDueAt(), BudgetKind.valueOf(row.budgetKind()), row.countedToday(), row.recommended(), row.requiresBudgetConfirmation(),
                row.archivedConcept() ? List.of(MemoryReviewDtos.Warning.ARCHIVED_CONCEPT_WITH_OTHER_SOURCE) : List.of());
    }

    DailyBudget budgetAt(String owner, Instant asOf, Instant serverTime) {
        time(asOf); time(serverTime);
        if (asOf.isAfter(serverTime)) throw invalid("队列时间边界不能晚于服务器时间。");
        DailyBudget budget = budget(owner, serverTime);
        // 到期边界与当前复习日必须属于同一天；跨页不能拿新一天的预算继续旧一天的队列。
        if (!day(budget.zoneId(), asOf, budget.settingsRevision()).localDate().equals(budget.localDate())) {
            throw problem("MEMORY_QUEUE_CHANGED", "复习日期已变化，请从队列首页重新读取。");
        }
        return budget;
    }

    MemoryReviewDtos.Settings configured(String owner) {
        var settings = reviews.settings(owner);
        if (settings == null) throw problem("MEMORY_SETTINGS_REQUIRED", "请先明确保存时区和每日建议数量。");
        return settings;
    }

    DailyBudget budget(String owner, Instant now) {
        var settings = configured(owner);
        RatingDay day = day(settings.zoneId(), now, settings.revision());
        var totals = mapper.budget(owner, day.windowStart(), day.windowEnd());
        return new DailyBudget(settings.revision(), settings.zoneId(), day.localDate(), day.windowStart(), day.windowEnd(),
                settings.dailyNewBudget(), settings.dailyReviewBudget(), count(totals.newCards()), count(totals.reviewCards()), count(totals.ratedEvents()));
    }

    BudgetAdmission admission(String owner, String cardId, MemoryReviewDtos.Schedule schedule, Instant now, boolean confirmed) {
        DailyBudget budget = budget(owner, now);
        var first = mapper.firstRating(owner, cardId, budget.windowStart(), budget.windowEnd());
        BudgetKind kind = first != null ? BudgetKind.valueOf(first.kind()) : basis(schedule);
        boolean over = first == null && (kind == BudgetKind.NEW ? budget.newCards() >= budget.newLimit() : budget.reviewCards() >= budget.reviewLimit());
        if (over && !confirmed) throw problem("MEMORY_BUDGET_CONFIRMATION_REQUIRED", "本类今日建议数量已用完，请明确确认继续。");
        return new BudgetAdmission(budget, kind, first != null, over, confirmed);
    }

    static BudgetKind basis(MemoryReviewDtos.Schedule schedule) {
        return schedule.state().lastReviewAt() == null ? BudgetKind.NEW : BudgetKind.REVIEW;
    }
}
