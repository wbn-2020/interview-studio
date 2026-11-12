package com.wenjie.studio.memory;

import com.wenjie.studio.common.ApiException;
import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Scheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.wenjie.studio.memory.MemoryAttemptDtos.Rating;
import static com.wenjie.studio.memory.MemoryReviewDtos.FsrsPhase;
import static com.wenjie.studio.memory.MemoryReviewDtos.FsrsState;

@Component
public final class MemoryFsrsAdapter {
    public static final String ALGORITHM_VERSION = MemoryReviewRules.ALGORITHM;
    public static final String PARAMETER_VERSION = MemoryReviewRules.PARAMETERS;

    private static final int MAX_INTERVAL_DAYS = 36_500;
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    public FsrsState next(FsrsState before, Rating rating, Instant ratedAt) {
        try {
            require(rating != null);
            time(ratedAt);
            state(before);
            require(before.lastReviewAt() == null || !before.lastReviewAt().isAfter(ratedAt));

            // 显式时间和占位 ID 避免库默认构建路径读取时钟；业务 UUID 由事务层保存。
            Card card = Card.builder().cardId(0).state(libraryPhase(before.phase())).step(before.step())
                    .stability(before.stability()).difficulty(before.difficulty())
                    .due(before.dueAt()).lastReview(before.lastReviewAt()).build();
            var libraryRating = libraryRating(rating);
            var result = scheduler().reviewCard(card, libraryRating, ratedAt, null);
            require(result != null && result.card() != null && result.reviewLog() != null);
            Card reviewed = result.card();
            FsrsState after = new FsrsState(domainPhase(reviewed.getState()), reviewed.getStep(),
                    reviewed.getStability(), reviewed.getDifficulty(), reviewed.getDue(), reviewed.getLastReview());
            state(after);
            require(reviewed.getCardId() == 0 && ratedAt.equals(after.lastReviewAt()) && after.dueAt().isAfter(ratedAt));

            // 算法天数是完整 24 小时；日预算与时区不参与这里的间隔计算。
            if (after.phase() == FsrsPhase.REVIEW) {
                Duration interval = Duration.between(ratedAt, after.dueAt());
                long days = interval.toDays();
                require(days >= 1 && days <= MAX_INTERVAL_DAYS && interval.equals(Duration.ofDays(days)));
            }
            var log = result.reviewLog();
            require(log.cardId() == 0 && log.rating() == libraryRating
                    && ratedAt.equals(log.reviewDatetime()) && log.reviewDuration() == null);
            return after;
        } catch (RuntimeException | LinkageError exception) {
            // 任一输入、库调用或输出异常都让外层评分事务回滚，保留已揭示的尝试。
            throw invalid();
        }
    }

    private static Scheduler scheduler() {
        // 库直接持有参数数组；每次计算独立构建，避免可变参数或随机状态跨调用共享。
        return Scheduler.builder()
                .parameters(new double[] {
                        0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966,
                        0.0069, 1.5261, 0.112, 1.0178, 1.849, 0.1133, 0.3127,
                        2.2934, 0.2191, 3.0004, 0.7536, 0.3332, 0.1437, 0.2
                })
                .desiredRetention(0.9)
                .learningSteps(new Duration[] {Duration.ofSeconds(60), Duration.ofSeconds(600)})
                // 锁版只使用单重学步骤，保持已审定参数并避开上游多步骤分支的数组误用。
                .relearningSteps(new Duration[] {Duration.ofSeconds(600)})
                .maximumInterval(MAX_INTERVAL_DAYS).enableFuzzing(false).randomSeedNumber(42).build();
    }

    private static void state(FsrsState value) {
        require(value != null && value.phase() != null);
        time(value.dueAt());
        boolean validStep = switch (value.phase()) {
            case LEARNING -> value.step() != null && (value.step() == 0 || value.step() == 1);
            case REVIEW -> value.step() == null;
            case RELEARNING -> value.step() != null && value.step() == 0;
        };
        require(validStep);
        if (value.lastReviewAt() == null) {
            require(value.phase() == FsrsPhase.LEARNING && value.step() == 0
                    && value.stability() == null && value.difficulty() == null);
            return;
        }
        time(value.lastReviewAt());
        require(value.dueAt().isAfter(value.lastReviewAt()));
        require(value.stability() != null && Double.isFinite(value.stability()) && value.stability() >= 0.001);
        require(value.difficulty() != null && Double.isFinite(value.difficulty())
                && value.difficulty() >= 1.0 && value.difficulty() <= 10.0);
    }

    private static void time(Instant value) {
        require(value != null && !value.isBefore(MIN_TIME) && !value.isAfter(MAX_TIME)
                && value.equals(value.truncatedTo(ChronoUnit.MICROS)));
    }

    private static io.github.openspacedrepetition.State libraryPhase(FsrsPhase phase) {
        return switch (phase) {
            case LEARNING -> io.github.openspacedrepetition.State.LEARNING;
            case REVIEW -> io.github.openspacedrepetition.State.REVIEW;
            case RELEARNING -> io.github.openspacedrepetition.State.RELEARNING;
        };
    }

    private static FsrsPhase domainPhase(io.github.openspacedrepetition.State phase) {
        require(phase != null);
        return switch (phase) {
            case LEARNING -> FsrsPhase.LEARNING;
            case REVIEW -> FsrsPhase.REVIEW;
            case RELEARNING -> FsrsPhase.RELEARNING;
        };
    }

    private static io.github.openspacedrepetition.Rating libraryRating(Rating rating) {
        return switch (rating) {
            case AGAIN -> io.github.openspacedrepetition.Rating.AGAIN;
            case HARD -> io.github.openspacedrepetition.Rating.HARD;
            case GOOD -> io.github.openspacedrepetition.Rating.GOOD;
            case EASY -> io.github.openspacedrepetition.Rating.EASY;
        };
    }

    private static void require(boolean valid) {
        if (!valid) throw invalid();
    }

    private static ApiException invalid() {
        return new ApiException(409, "MEMORY_SCHEDULER_INVALID", "复习算法状态或计算结果无效，本次评分未提交。");
    }
}
