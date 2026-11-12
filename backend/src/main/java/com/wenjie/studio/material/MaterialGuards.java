package com.wenjie.studio.material;

import com.wenjie.studio.common.ApiException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

final class MaterialGuards {
    static final long MAX_REVISION = 9_007_199_254_740_991L;
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private MaterialGuards() {}
    static ApiException invalid(String message) { return new ApiException(400, "INVALID_REQUEST", message); }
    static ApiException missing() { return new ApiException(404, "MATERIAL_NOT_FOUND", "资料或来源不存在，或不属于当前工作区。"); }
    static String uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid("标识必须是标准小写 UUID。"); }
        return value;
    }
    static String text(String value, int maximum, boolean blank) {
        if (value == null || value.length() > maximum || (!blank && value.isBlank()) || value.indexOf('\0') >= 0) throw invalid("文本为空、超出长度或包含无效字符。");
        return value;
    }
    static void limit(int value) { if (value < 1 || value > 50) throw invalid("每页数量必须为 1–50。"); }
    static void revision(Long value) { if (value == null || value < 0 || value > MAX_REVISION) throw invalid("修订号无效。"); }
    static void changeable(MaterialRows.Attachment attachment, Long expected) {
        revision(expected);
        if (attachment.revision() != expected) throw new ApiException(409, "REVISION_CONFLICT", "资料已经变化，请保留当前输入并读取最新状态。", attachment.revision());
        if (attachment.revision() == MAX_REVISION) throw new ApiException(409, "REVISION_LIMIT", "资料修订已达到上限。");
    }
    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    static String id() { return UUID.randomUUID().toString(); }
    static Instant cursorTime(String value) {
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value) || parsed.getNano() % 1000 != 0
                    || parsed.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                    || parsed.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException exception) { throw invalid("历史游标无效。"); }
    }
}
