package com.wenjie.studio.planning;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.planning.PlanningDtos.Content;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

public final class PlanningRules {
    static final long MAX_REVISION = 9_007_199_254_740_991L;
    private static final Set<String> ZONE_IDS = ZoneId.getAvailableZoneIds();

    private PlanningRules() {}

    public static void content(Content value) {
        if (value == null) throw invalid("准备任务内容不能为空。");
        text(value.title(), 200, true, "任务标题");
        text(value.detail(), 1000, false, "任务说明");
        if (value.minutes() == null || value.minutes() < 1 || value.minutes() > 1440) {
            throw invalid("预计工作量须为 1 至 1440 的整数分钟。");
        }
        // 分钟数表示名义工作量，日期与时区只描述安排，不推断实际可用时段或 DST 日长度。
        date(value.plannedDate());
        zone(value.zoneId());
    }

    public static LocalDate date(String value) {
        if (value == null) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeException("format");
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1000 || parsed.getYear() > 9999) throw new DateTimeException("range");
            return parsed;
        } catch (DateTimeException exception) {
            throw invalid("安排日期须为有效的 yyyy-MM-dd，年份在 1000 至 9999 之间。");
        }
    }

    public static String zone(String value) {
        if (value == null || (!value.equals("UTC") && !ZONE_IDS.contains(value))) {
            throw invalid("请选择有效的 IANA 时区或 UTC，不能用固定偏移替代时区。");
        }
        return value;
    }

    static String uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw invalid("对象标识须为标准小写 UUID。");
        }
        return value;
    }

    static void revision(Long value) {
        if (value == null || value < 0 || value > MAX_REVISION) throw invalid("预期修订号无效。");
    }

    static void limit(int value) {
        if (value < 1 || value > 50) throw invalid("每页数量须为 1 至 50。");
    }

    static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }

    private static void text(String value, int max, boolean nonblank, String field) {
        if (value == null || value.length() > max || (nonblank && value.isBlank())) {
            throw invalid(field + "为空或超过长度上限。");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\0') throw invalid(field + "不能包含 NUL 字符。");
            if (Character.isHighSurrogate(character)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw invalid(field + "包含不完整的 Unicode 字符。");
                }
                i++;
            } else if (Character.isLowSurrogate(character)) {
                throw invalid(field + "包含不完整的 Unicode 字符。");
            }
        }
    }
}
