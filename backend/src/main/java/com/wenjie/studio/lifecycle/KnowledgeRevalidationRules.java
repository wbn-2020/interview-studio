package com.wenjie.studio.lifecycle;

import com.wenjie.studio.common.ApiException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

final class KnowledgeRevalidationRules {
    static final long MAX = 9_007_199_254_740_991L;
    static final int REQUEST_BYTES = 16_384;
    static final DateTimeFormatter CURSOR_TIME = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    private KnowledgeRevalidationRules() {}

    static void uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw invalid("标识须为标准小写 UUID。");
        }
    }
    static void revision(Long value) {
        if (value == null || value < 0 || value > MAX) throw invalid("修订须为安全范围内的非负整数。");
    }
    static void reason(String value) {
        if (value == null || value.codePointCount(0, value.length()) > 1000
                || value.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point))
                || value.indexOf('\0') >= 0 || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) {
            throw invalid("处理理由须为 1 至 1000 字，且不能包含无效字符。");
        }
    }
    static void time(Instant value) {
        if (value == null || value.isBefore(MIN_TIME) || value.isAfter(MAX_TIME)
                || !value.equals(value.truncatedTo(ChronoUnit.MICROS))) throw invalid("时间须为可保存的微秒 UTC 时间。");
    }
    static Instant now() {
        Instant value = Instant.now().truncatedTo(ChronoUnit.MICROS); time(value); return value;
    }
    static void writing() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("知识来源更正必须加入持有 owner 锁的写事务");
        }
    }
    static void lineageIdentity(KnowledgeRevalidationRows.ImpactRow row) {
        if (row == null || row.sourceTitle() == null || row.sourceVersionNumber() < 1 || row.depth() < 1
                || row.targetVersionNumber() < 1 || row.createdAt() == null || row.eventCreatedAt() == null
                || !row.createdAt().equals(row.eventCreatedAt())) throw integrity();
        uuid(row.id()); uuid(row.eventId()); uuid(row.rootItemId()); uuid(row.sourceItemId());
        uuid(row.sourceVersionId()); uuid(row.taskId()); uuid(row.targetItemId()); uuid(row.targetVersionId());
        time(row.createdAt()); time(row.eventCreatedAt());
        if (row.depth() == 1) {
            if (row.parentImpactId() != null || !row.sourceItemId().equals(row.rootItemId())) throw integrity();
        } else {
            uuid(row.parentImpactId());
            if (row.id().equals(row.parentImpactId())) throw integrity();
        }
    }
    static void parentIdentity(KnowledgeRevalidationRows.ImpactRow child, KnowledgeRevalidationRows.ImpactRow parent) {
        lineageIdentity(child); lineageIdentity(parent);
        if (!parent.id().equals(child.parentImpactId()) || !parent.eventId().equals(child.eventId())
                || !parent.rootItemId().equals(child.rootItemId()) || !parent.createdAt().equals(child.createdAt())
                || !parent.rootPreviousVersionId().equals(child.rootPreviousVersionId())
                || !parent.rootCurrentVersionId().equals(child.rootCurrentVersionId())
                || !parent.changeKind().equals(child.changeKind()) || !parent.targetItemId().equals(child.sourceItemId())
                || child.sourceVersionNumber() < parent.targetVersionNumber()
                || (long) parent.depth() + 1 != child.depth()) throw integrity();
    }
    static void inserted(int count) { if (count != 1) throw integrity(); }
    static ApiException invalid(String message) { return new ApiException(400, "INVALID_KNOWLEDGE_REVALIDATION_REQUEST", message); }
    static ApiException missing() { return new ApiException(404, "KNOWLEDGE_REVALIDATION_NOT_FOUND", "知识、版本或采用影响不存在或不可访问。"); }
    static IllegalStateException integrity() { return new IllegalStateException("知识采用来源更正的固定身份或处理状态不一致"); }
}
