package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;

import java.util.UUID;

final class KnowledgeGuards {
    static final long MAX_REVISION = 9_007_199_254_740_991L;
    private KnowledgeGuards() {}

    static String uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException exception) {
            throw invalid("对象标识必须是标准小写 UUID。");
        }
    }
    static void revision(Long revision) {
        if (revision == null || revision < 0 || revision > MAX_REVISION) throw invalid("预期修订号无效。");
    }
    static void pageLimit(int limit) {
        if (limit < 1 || limit > 50) throw invalid("每页数量必须在 1 至 50 之间。");
    }
    static <T> T required(T value) {
        if (value == null) throw invalid("请求内容不能为空。");
        return value;
    }
    static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
}
