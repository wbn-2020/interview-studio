package com.wenjie.studio.material;

import com.wenjie.studio.common.ApiException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

final class MaterialKnowledgeImpactRules {
    static final long MAX = 9_007_199_254_740_991L;
    static final int REQUEST_BYTES = 16_384;
    static final DateTimeFormatter CURSOR_TIME = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    private MaterialKnowledgeImpactRules() {}

    static boolean storedUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    static void uuid(String value) { if (!storedUuid(value)) throw invalid("标识须为标准小写 UUID。"); }
    static boolean safeRevision(Long value) { return value != null && value >= 0 && value <= MAX; }
    static void revision(Long value) {
        if (!safeRevision(value)) throw invalid("修订须为安全范围内的非负整数。");
    }
    static void reason(String value) {
        if (value == null || value.codePointCount(0, value.length()) > 1000
                || value.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point))
                || value.indexOf('\0') >= 0 || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) {
            throw invalid("处理理由须为 1 至 1000 字，且不能包含无效字符。");
        }
    }
    static boolean validTime(Instant value) {
        return value != null && !value.isBefore(MIN_TIME) && !value.isAfter(MAX_TIME)
                && value.equals(value.truncatedTo(ChronoUnit.MICROS));
    }
    static Instant now() {
        Instant value = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!validTime(value)) throw clock("服务器时间不在可保存范围，本次处理未提交。");
        return value;
    }
    static void writing() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("材料知识影响必须加入持有 owner 锁的写事务");
        }
    }
    static void inserted(int count) { if (count != 1) throw integrity(); }
    static ApiException invalid(String message) { return new ApiException(400, "MATERIAL_KNOWLEDGE_INVALID_REQUEST", message); }
    static ApiException missing() { return new ApiException(404, "MATERIAL_KNOWLEDGE_NOT_FOUND", "材料、知识或采用影响不存在或不可访问。"); }
    static ApiException conflict(String message, long revision) {
        return new ApiException(409, "MATERIAL_KNOWLEDGE_CONFLICT", message, revision);
    }
    static ApiException targetConflict(String message, long revision) {
        return new ApiException(409, "MATERIAL_KNOWLEDGE_TARGET_CONFLICT", message, revision);
    }
    static ApiException clock(String message) { return new ApiException(409, "MATERIAL_KNOWLEDGE_CLOCK_CONFLICT", message); }
    static IllegalStateException integrity() { return new IllegalStateException("材料知识影响的根声明、固定采用身份或处理状态不一致"); }
}
