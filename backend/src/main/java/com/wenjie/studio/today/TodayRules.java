package com.wenjie.studio.today;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.planning.PlanningRules;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.today.TodayDtos.*;

final class TodayRules {
    static final int PAGE_BYTES = 1024 * 1024, ITEM_BYTES = 32 * 1024, SCAN_LIMIT = 500;
    static final long MAX_INTEGER = 9_007_199_254_740_991L;
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");
    static final DateTimeFormatter MICRO_TIME = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private static final JsonMapper STRICT = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                            .maxStringLength(4096).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private TodayRules() {}

    enum Sort { KNOWLEDGE_SAVED_DESC, TRAINING_ACTIVITY_DESC, TASK_ACTIVITY_DESC, TASK_DATE_ASC,
        MEMORY_OPEN_DESC, MEMORY_DUE_ASC, MEMORY_ATTENTION_DESC, ACTION_UPDATED_DESC,
        INTERVIEW_REMINDER_ASC, INTERVIEW_START_ASC, MEMORY_CALENDAR_ASC,
        PLACEMENT_DATE_ACTIVITY_DESC, PLACEMENT_CALENDAR_ASC }

    record Cursor(int v, String purpose, String ownerId, SourceKind kind, Section section, String targetId,
                  boolean includeOverBudget, String zoneId, LocalDate localDate, Instant asOf,
                  LocalDate from, LocalDate to, Sort sort, Instant afterInstant, LocalDate afterDate,
                  Integer afterRank, String afterId, String delegateCursor, Long memorySettingsRevision,
                  String memoryZoneId, LocalDate memoryLocalDate, long placementGeneration, Integer afterOrderValue) {}

    static void parameters(MultiValueMap<String, String> query, Set<String> allowed) {
        for (var entry : query.entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().size() != 1
                    || entry.getValue().getFirst() == null || entry.getValue().getFirst().isEmpty()) {
                throw invalid("查询参数未知、重复或为空。");
            }
        }
    }

    static String required(MultiValueMap<String, String> query, String name) {
        String value = query.getFirst(name);
        if (value == null) throw invalid("缺少必填查询参数：" + name + "。");
        return value;
    }

    static boolean bool(String value) {
        if (value == null || value.equals("false")) return false;
        if (value.equals("true")) return true;
        throw invalid("布尔查询参数只能为 true 或 false。");
    }

    static int limit(String value) {
        if (value == null) return 20;
        if (!value.matches("[1-9][0-9]?")) throw invalid("每页数量须为 1 至 50 的整数。");
        int number = Integer.parseInt(value);
        if (number > 50) throw invalid("每页数量须为 1 至 50 的整数。");
        return number;
    }

    static <E extends Enum<E>> E enumValue(String value, Class<E> type) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException | NullPointerException exception) { throw invalid("查询枚举无效。"); }
    }

    static String uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw invalid("对象标识须为标准小写 UUID。"); }
        return value;
    }

    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    static void time(Instant value) {
        if (value == null || value.isBefore(MIN_TIME) || value.isAfter(MAX_TIME)
                || !value.equals(value.truncatedTo(ChronoUnit.MICROS))) throw invalid("时间须在 1000 至 9999 年范围内且精确到微秒。");
    }

    static LocalDate date(String value) {
        LocalDate result = PlanningRules.date(value);
        if (result == null) throw invalid("日历起止日期必填。");
        return result;
    }

    static DayContext context(String zone, Instant asOf, Instant serverTime) {
        PlanningRules.zone(zone); time(asOf); time(serverTime);
        try {
            ZoneId id = ZoneId.of(zone); LocalDate local = date(asOf.atZone(id).toLocalDate().toString());
            Instant start = local.atStartOfDay(id).toInstant(), end = local.plusDays(1).atStartOfDay(id).toInstant();
            time(start); time(end);
            if (asOf.isBefore(start) || !asOf.isBefore(end)) throw invalid("显示日期不能形成有效日区间。");
            return new DayContext(zone, local, start, end, asOf, serverTime);
        } catch (DateTimeException exception) { throw invalid("显示日期超出可保存范围。"); }
    }

    static CalendarWindow window(LocalDate from, LocalDate to, String zone) {
        if (from == null || to == null) throw invalid("日历须同时提供起止日期。");
        date(from.toString()); date(to.toString()); PlanningRules.zone(zone);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > 31) throw invalid("日历区间包含首尾须为 1 至 31 天。");
        try {
            ZoneId id = ZoneId.of(zone);
            Instant start = from.atStartOfDay(id).toInstant(), end = to.plusDays(1).atStartOfDay(id).toInstant();
            time(start); time(end);
            if (!start.isBefore(end)) throw invalid("日历区间没有有效的时间范围。");
            return new CalendarWindow(from, to, start, end);
        } catch (DateTimeException exception) { throw invalid("日历边界超出可保存范围。"); }
    }

    static Sort sort(SourceKind kind, Section section, boolean calendar) {
        return switch (kind) {
            case KNOWLEDGE_DRAFT -> calendar ? Sort.PLACEMENT_CALENDAR_ASC
                    : section == Section.ATTENTION || section == Section.TODAY ? Sort.PLACEMENT_DATE_ACTIVITY_DESC : Sort.KNOWLEDGE_SAVED_DESC;
            case TRAINING_SESSION -> calendar ? Sort.PLACEMENT_CALENDAR_ASC
                    : section == Section.ATTENTION || section == Section.TODAY ? Sort.PLACEMENT_DATE_ACTIVITY_DESC : Sort.TRAINING_ACTIVITY_DESC;
            case PREPARATION_TASK -> calendar || section == Section.ATTENTION || section == Section.TODAY
                    ? Sort.TASK_DATE_ASC : Sort.TASK_ACTIVITY_DESC;
            case MEMORY_CARD -> calendar ? Sort.MEMORY_CALENDAR_ASC : switch (section) {
                case CONTINUE -> Sort.MEMORY_OPEN_DESC;
                case ATTENTION -> Sort.MEMORY_ATTENTION_DESC;
                case TODAY, BACKLOG -> Sort.MEMORY_DUE_ASC;
            };
            case REVIEW_ACTION -> calendar ? Sort.PLACEMENT_CALENDAR_ASC : Sort.PLACEMENT_DATE_ACTIVITY_DESC;
            case INTERVIEW_EVENT -> !calendar && section == Section.ATTENTION
                    ? Sort.INTERVIEW_REMINDER_ASC : Sort.INTERVIEW_START_ASC;
        };
    }

    static String encode(Cursor cursor) {
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(STRICT.writeValueAsBytes(cursor));
        if (value.length() > 4096) throw invalid("今日分页游标超过长度上限。");
        return value;
    }

    static Cursor cursor(String encoded, String owner, SourceKind kind, Section section, String target,
                         boolean includeOverBudget, String zone, LocalDate from, LocalDate to, Instant serverTime) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 4096 || !encoded.matches("[A-Za-z0-9_-]+")) throw invalid("今日分页游标无效。");
        tools.jackson.databind.JsonNode header;
        try { header = STRICT.readTree(Base64.getUrlDecoder().decode(encoded)); }
        catch (RuntimeException exception) { throw invalid("今日分页游标无效。"); }
        if (header == null || !header.isObject()) throw invalid("今日分页游标须为对象。");
        // v1 缺少偏好代次和完整排序键，只能要求回首页；不能先绑定 v2 结构后误报普通格式错误。
        if (header.has("v") && header.get("v").isIntegralNumber()
                && header.get("v").canConvertToInt() && header.get("v").intValue() != 2) throw changed();
        Cursor value;
        try {
            value = STRICT.readValue(Base64.getUrlDecoder().decode(encoded), Cursor.class);
            // 规范回编码同时拒绝缺字段、重复字段、浮点修订和非规范日期；不把游标当权限凭据。
            if (value == null || !encoded.equals(encode(value))) throw invalid("今日分页游标不是规范形式。");
        } catch (RuntimeException exception) { throw invalid("今日分页游标无效。"); }
        boolean calendar = from != null;
        if (value.v() != 2) throw changed();
        if (!Objects.equals(value.purpose(), calendar ? "today-calendar" : "today-items")
                || !owner.equals(value.ownerId()) || kind != value.kind() || section != value.section()
                || !Objects.equals(target, value.targetId()) || includeOverBudget != value.includeOverBudget()
                || !zone.equals(value.zoneId()) || !Objects.equals(from, value.from()) || !Objects.equals(to, value.to())) {
            throw invalid("游标与当前来源、区段或筛选不一致，请从首页读取。");
        }
        time(value.asOf());
        if (value.asOf().isAfter(serverTime)) throw invalid("分页游标不能来自未来。");
        DayContext context = context(zone, value.asOf(), serverTime);
        if (!context.localDate().equals(value.localDate())) throw invalid("游标日期与固定时间不一致。");
        if (Duration.between(value.asOf(), serverTime).compareTo(Duration.ofMinutes(15)) > 0
                || !context.localDate().equals(serverTime.atZone(ZoneId.of(zone)).toLocalDate())
                || value.sort() != sort(kind, section, calendar)) throw changed();
        if (value.placementGeneration() < 0 || value.placementGeneration() > MAX_INTEGER
                || value.afterOrderValue() == null || value.afterOrderValue() < 0 || value.afterOrderValue() > 1_000_000
                || value.delegateCursor() != null) throw invalid("安排分页代次或完整排序键无效。");
        uuid(value.afterId());
        if (value.sort() == Sort.TASK_DATE_ASC || value.sort() == Sort.PLACEMENT_CALENDAR_ASC) {
            if (value.afterInstant() != null || value.afterDate() == null || value.afterRank() != null) throw invalid("日期分页排序键无效。");
            date(value.afterDate().toString());
        } else if (value.sort() == Sort.PLACEMENT_DATE_ACTIVITY_DESC) {
            if (value.afterRank() != null) throw invalid("安排分页不接受回忆阶段键。");
            if (value.afterDate() != null) date(value.afterDate().toString());
            time(value.afterInstant());
        } else {
            if (value.afterDate() != null) throw invalid("时间排序不能携带日期键。");
            time(value.afterInstant());
            if (value.sort() == Sort.MEMORY_DUE_ASC) {
                if (value.afterRank() == null || value.afterRank() < 0 || value.afterRank() > 1) throw invalid("回忆队列阶段键无效。");
            } else if (value.afterRank() != null) throw invalid("该来源不接受回忆阶段键。");
        }
        if (kind == SourceKind.MEMORY_CARD) {
            if (value.memorySettingsRevision() == null) {
                if (value.memoryZoneId() != null || value.memoryLocalDate() != null) throw invalid("回忆设置游标不完整。");
            } else {
                if (value.memorySettingsRevision() < 0 || value.memorySettingsRevision() > MAX_INTEGER
                        || value.memoryLocalDate() == null) throw invalid("回忆设置游标无效。");
                PlanningRules.zone(value.memoryZoneId()); date(value.memoryLocalDate().toString());
            }
        } else {
            if (value.memorySettingsRevision() != null
                    || value.memoryZoneId() != null || value.memoryLocalDate() != null) throw invalid("非回忆游标不能携带回忆状态。");
        }
        return value;
    }

    static Count exact(long value) { return count(value, CountMode.EXACT); }
    static Count lower(long value) { return count(value, CountMode.LOWER_BOUND); }
    private static Count count(long value, CountMode mode) {
        if (value < 0 || value > MAX_INTEGER) throw integrity();
        return new Count(value, mode);
    }

    static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(STRICT.writeValueAsBytes(value))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }

    static <T> T bounded(JsonMapper mapper, T value, int bytes) {
        if (mapper.writeValueAsBytes(value).length > bytes) throw new ApiException(422, "TODAY_RESPONSE_LIMIT", "今日摘要超出响应上限，请缩小读取范围。");
        return value;
    }

    static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
    static ApiException changed() { return new ApiException(409, "TODAY_CURSOR_CHANGED", "日期、复习设置或排序已变化，请保留筛选并从首页重新读取。"); }
    static ApiException missing() { return new ApiException(404, "TODAY_SOURCE_NOT_FOUND", "来源不存在或不可访问。"); }
    static ProjectionFailure integrity() { return new ProjectionFailure(); }
    // 只捕获今日摘要的局部一致性错误；数据库及跨服务事务错误必须让整个读取失败。
    static final class ProjectionFailure extends RuntimeException {}
}
