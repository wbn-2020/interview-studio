package com.wenjie.studio.bank;

import com.wenjie.studio.common.ApiException;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

final class QuestionGuards {
    static final long MAX_REVISION = 9_007_199_254_740_991L;
    private QuestionGuards() {}

    static String uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException exception) { throw invalid("对象标识必须是标准小写 UUID。"); }
    }

    static <T> T required(T value) {
        if (value == null) throw invalid("必填内容不能为空。");
        return value;
    }

    static String text(String value, int max, boolean required) {
        String content = value == null ? "" : value;
        if ((required && content.isBlank()) || content.codePointCount(0, content.length()) > max) {
            throw invalid("文本为空或超过允许长度。");
        }
        return content;
    }

    static <T> List<T> list(List<T> input, int max) {
        if (input == null) return List.of();
        if (input.size() > max || input.stream().anyMatch(value -> value == null)) throw invalid("列表数量或内容无效。");
        return List.copyOf(input);
    }

    static <T> void distinct(List<T> values) {
        if (new HashSet<>(values).size() != values.size()) throw invalid("列表不能包含重复值。");
    }

    static void revision(Long value) {
        if (value == null || value < 0 || value > MAX_REVISION) throw invalid("预期修订号无效。");
    }

    static void pageLimit(int value) {
        if (value < 1 || value > 50) throw invalid("每页数量必须在 1 至 50 之间。");
    }

    static void difficulty(Integer value) {
        if (value != null && (value < 1 || value > 5)) throw invalid("个人难度标记必须在 1 至 5 之间。");
    }

    static String pattern(String value) {
        if (value == null) return null;
        String query = text(value, 100, false).strip();
        return query.isEmpty() ? null : "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    static void url(String value) {
        if (value.isEmpty()) return;
        try {
            URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw invalid("来源链接必须是有效的 http(s) 地址。"); }
    }

    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
}
