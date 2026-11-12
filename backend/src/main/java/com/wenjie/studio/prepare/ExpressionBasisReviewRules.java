package com.wenjie.studio.prepare;

import com.wenjie.studio.common.ApiException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

final class ExpressionBasisReviewRules {
    static final long MAX = 9_007_199_254_740_991L;
    static final int REQUEST_BYTES = 65_536;
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    private ExpressionBasisReviewRules() {}

    static void uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw invalid("标识必须为标准小写 UUID。"); }
    }
    static void revision(Long value) {
        if (value == null || value < 0 || value > MAX) throw invalid("修订须为安全范围内的非负整数。");
    }
    static String reason(String value) {
        if (value == null || value.indexOf('\0') >= 0
                || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) {
            throw invalid("复核理由不能包含 NUL 或不完整的 Unicode 字符。");
        }
        String normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) < 1
                || normalized.codePointCount(0, normalized.length()) > 1000
                || normalized.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point))) {
            throw invalid("复核理由须为去除首尾空白后的 1 至 1000 字。");
        }
        return normalized;
    }
    static boolean current(PreparationDtos.SourceCheck check) {
        return check != null && check.kind() == PreparationDtos.Kind.FACT && check.state() == PreparationDtos.SourceState.CURRENT
                && safe(check.sourceGeneration()) && safe(check.projectGeneration());
    }
    static boolean safe(Long value) { return value != null && value >= 0 && value <= MAX; }
    static void checks(List<PreparationDtos.SourceCheck> checks) {
        if (checks == null || checks.isEmpty() || checks.size() > 50) throw invalid("必须完整提供 1 至 50 项事实检查。");
        var versions = new java.util.HashSet<String>();
        for (var check : checks) {
            if (!current(check)) throw invalid("确认时的每项事实须为 CURRENT 且代次完整。");
            uuid(check.itemId()); uuid(check.versionId());
            if (!versions.add(check.versionId())) throw invalid("事实检查不能重复。");
        }
    }
    static void time(Instant value) {
        if (value == null || value.isBefore(MIN_TIME) || value.isAfter(MAX_TIME)
                || !value.equals(value.truncatedTo(ChronoUnit.MICROS))) throw integrity();
    }
    static Instant now() { Instant result = Instant.now().truncatedTo(ChronoUnit.MICROS); time(result); return result; }
    static void writing() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw integrity();
    }
    static void inserted(int count) { if (count != 1) throw integrity(); }
    static ApiException invalid(String message) { return new ApiException(400, "INVALID_EXPRESSION_BASIS_REVIEW", message); }
    static ApiException missing() { return new ApiException(404, "EXPRESSION_BASIS_REVIEW_NOT_FOUND", "表达或依据复核记录不存在或不可访问。"); }
    static ApiException conflict(String message, long revision) { return new ApiException(409, "EXPRESSION_BASIS_REVIEW_CONFLICT", message, revision); }
    static ApiException limit() { return new ApiException(409, "REVISION_LIMIT", "表达或依据复核修订已达到上限，本次操作未提交。"); }
    static IllegalStateException integrity() { return new IllegalStateException("表达依据复核的固定身份、顺序或代次不一致"); }
}
