package com.wenjie.studio.schedule;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.planning.PlanningRules;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static com.wenjie.studio.schedule.SchedulePreparationDtos.*;

final class SchedulePreparationRules {
    static final int REQUEST_BYTES = 256 * 1024, HISTORY_BYTES = 32 * 1024, PAGE_BYTES = 1024 * 1024;
    static final long MAX = 9_007_199_254_740_991L;
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");
    private static final DateTimeFormatter MICRO_TIME = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private static final Set<String> INSTANT_FIELDS = Set.of("createdAt", "updatedAt", "startedAt", "completedAt", "startAt", "endAt", "remindAt");
    private static final JsonMapper STRICT = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                            .maxStringLength(REQUEST_BYTES).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private SchedulePreparationRules() {}

    static <T> T decode(byte[] raw, Class<T> type) {
        if (raw == null || raw.length == 0) throw invalid("请求须为完整 JSON 对象。");
        if (raw.length > REQUEST_BYTES) throw limit("准备关联请求超过字节上限。");
        try {
            JsonNode value = STRICT.readTree(raw);
            if (value == null || !value.isObject()) throw invalid("请求须为 JSON 对象。");
            dates(value);
            return STRICT.treeToValue(value, type);
        } catch (ApiException exception) { throw exception; }
        catch (RuntimeException exception) { throw invalid("请求字段缺失、重复、类型无效或包含未知字段。"); }
    }

    private static void dates(JsonNode node) {
        if (node.isArray()) { for (JsonNode item : node) dates(item); return; }
        if (!node.isObject()) return;
        for (var entry : node.properties()) {
            JsonNode value = entry.getValue();
            if (entry.getKey().equals("plannedDate") && !value.isNull()) {
                if (!value.isTextual()) throw invalid("安排日期须为 yyyy-MM-dd 字符串。");
                date(value.stringValue());
            }
            dates(value);
        }
    }

    static void create(CreatePreparationLink value) {
        if (value == null || value.kind() == null) throw invalid("关联来源类型必填。");
        revision(value.expectedEventRevision()); uuid(value.sourceId());
        sourceVersion(value.kind(), value.sourceVersionId()); stamp(value.expectedSource(), value.kind());
    }
    static void changeStatus(ChangePreparationLinkStatus value) {
        if (value == null || value.status() == null) throw invalid("关联状态必填。");
        revision(value.expectedEventRevision()); linkRevision(value.expectedRevision());
    }
    static void changeVersion(ChangeCollectionVersion value) {
        if (value == null) throw invalid("题单版本请求必填。");
        revision(value.expectedEventRevision()); linkRevision(value.expectedRevision());
        uuid(value.sourceVersionId()); stamp(value.expectedSource(), LinkKind.COLLECTION);
    }
    static void sourceVersion(LinkKind kind, String versionId) {
        if (kind == LinkKind.COLLECTION) uuid(versionId);
        else if (versionId != null) throw invalid("任务与动作引用稳定身份，不能携带来源版本。");
    }
    static void stamp(PreparationSourceStamp value, LinkKind kind) {
        if (value == null || value.kind() != kind) throw invalid("来源修订类型不匹配。");
        if ((value.task() == null ? 0 : 1) + (value.action() == null ? 0 : 1) + (value.collection() == null ? 0 : 1) != 1) {
            throw invalid("来源修订只能包含对应类型的完整载荷。");
        }
        switch (kind) {
            case PREPARATION_TASK -> { if (value.task() == null) throw invalid("任务修订缺失。"); revision(value.task().revision()); }
            case REVIEW_ACTION -> {
                if (value.action() == null) throw invalid("动作修订缺失。");
                linkRevision(value.action().revision()); hashText(value.action().progressHash());
            }
            case COLLECTION -> {
                if (value.collection() == null) throw invalid("题单修订缺失。");
                revision(value.collection().revision()); uuid(value.collection().versionId());
            }
        }
    }
    static void preview(ReschedulePlanRequest value) {
        if (value == null || value.time() == null) throw invalid("改期时间必填。");
        revision(value.expectedEventRevision()); range(value.remindBeforeMinutes(), 0, 10080, "提醒提前分钟");
        if (value.selectedLinkIds() == null || value.selectedLinkIds().size() > 50 || value.dateOverrides() == null
                || value.dateOverrides().size() > 50) throw invalid("一次联动最多选择50项。");
        Set<String> selected = new HashSet<>();
        for (String id : value.selectedLinkIds()) if (!selected.add(uuid(id))) throw invalid("准备关联不能重复选择。");
        Set<String> overrides = new HashSet<>(); int previous = -1;
        for (DateOverride item : value.dateOverrides()) {
            if (item == null || !overrides.add(uuid(item.linkId()))) throw invalid("改日项不能为空或重复。");
            date(item.plannedDate());
            int index = value.selectedLinkIds().indexOf(item.linkId());
            if (index <= previous) throw invalid("改日项须按选中关联的顺序提供，且只能引用已选项。");
            previous = index;
        }
    }
    static void apply(ApplyReschedulePlan value) {
        if (value == null) throw invalid("采用请求必填。");
        preview(value.request()); hashText(value.expectedPreviewHash()); reason(value.reason());
        if (!Boolean.TRUE.equals(value.confirmed())) throw invalid("须明确确认后保存联动改期。");
    }

    static String uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid("对象标识须为标准小写 UUID。"); }
        return value;
    }
    static void revision(Long value) { if (value == null || value < 0 || value > MAX) throw invalid("修订须为非负安全整数。"); }
    static void linkRevision(Long value) { revision(value); if (value > 1000) throw invalid("关联或动作修订超出范围。"); }
    static long next(long value) { linkRevision(value); if (value == 1000) throw limit("准备关联修订达到上限。"); return value + 1; }
    static void range(Integer value, int min, int max, String label) { if (value == null || value < min || value > max) throw invalid(label + "超出整数范围。"); }
    static void hashText(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid("来源或预览摘要无效。"); }
    static LocalDate date(String value) { LocalDate parsed = PlanningRules.date(value); if (parsed == null) throw invalid("日期必填。"); return parsed; }
    static void date(LocalDate value) { if (value == null) throw invalid("日期必填。"); date(value.toString()); }
    static void time(Instant value) {
        if (value == null || value.isBefore(MIN_TIME) || value.isAfter(MAX_TIME) || value.getNano() % 1000 != 0) throw invalid("时间超出微秒精度或可保存范围。");
    }
    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    static void clock(Instant now, Instant before) {
        time(now); time(before);
        if (now.isBefore(before)) throw conflict("RESCHEDULE_CLOCK_CHANGED", "服务器时间早于最近修改，请保留输入后恢复原请求。", null);
    }
    static void reason(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 1 || value.codePointCount(0, value.length()) > 2000
                || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) throw invalid("改期原因须为1至2000个字符的非空文字。");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) throw invalid("原因不能包含NUL。");
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw invalid("原因包含不完整Unicode。");
                i++;
            } else if (Character.isLowSurrogate(c)) throw invalid("原因包含不完整Unicode。");
        }
    }

    static void parameters(MultiValueMap<String, String> query, Set<String> allowed) {
        for (var entry : query.entrySet()) if (!allowed.contains(entry.getKey()) || entry.getValue().size() != 1
                || entry.getValue().getFirst() == null || entry.getValue().getFirst().isBlank()) throw invalid("查询参数未知、为空或重复。");
    }
    static <T extends Enum<T>> T enumValue(String text, Class<T> type) {
        if (text == null) return null;
        try { return Enum.valueOf(type, text); } catch (IllegalArgumentException exception) { throw invalid("筛选类型无效。"); }
    }
    static int pageSize(String text) {
        if (text == null) return 20;
        if (!text.matches("[1-9][0-9]?")) throw invalid("每页数量须为1至50。");
        int value = Integer.parseInt(text); range(value, 1, 50, "每页数量"); return value;
    }
    static Long before(String text) {
        if (text == null) return null;
        if (!text.matches("0|[1-9][0-9]{0,3}")) throw invalid("历史游标须为0至1000的整数。");
        long value = Long.parseLong(text); linkRevision(value); return value;
    }

    record LinkCursor(int v, String purpose, String ownerId, String eventId, long eventRevision,
                       LinkStatus status, Instant asOf, String afterId) {}
    static String cursor(LinkCursor value) {
        byte[] bytes = STRICT.writeValueAsBytes(value);
        if (bytes.length > 4096) throw invalid("关联游标超限。");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static LinkCursor cursor(String encoded, String owner, EventReference event, LinkStatus status, Instant now) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 5464 || !encoded.matches("[A-Za-z0-9_-]+")) throw invalid("关联游标无效。");
        LinkCursor value;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > 4096) throw invalid("关联游标超限。");
            value = STRICT.readValue(bytes, LinkCursor.class);
            if (value == null || !encoded.equals(cursor(value))) throw invalid("关联游标不是规范编码。");
        } catch (RuntimeException exception) { throw invalid("关联游标无效。"); }
        if (value.v() != 1) throw cursorChanged();
        if (!"interview-preparation-links".equals(value.purpose()) || !owner.equals(value.ownerId())
                || !event.id().equals(value.eventId()) || !Objects.equals(status, value.status())) {
            throw new ApiException(400, "CURSOR_MISMATCH", "关联游标与当前事件或筛选不匹配。");
        }
        uuid(value.ownerId()); uuid(value.eventId()); uuid(value.afterId()); revision(value.eventRevision()); time(value.asOf());
        if (value.asOf().isAfter(now)) throw invalid("关联游标不能来自未来。");
        if (value.eventRevision() != event.revision() || Duration.between(value.asOf(), now).compareTo(Duration.ofMinutes(15)) > 0) throw cursorChanged();
        return value;
    }

    static String hash(Object value) {
        String canonical = STRICT.writeValueAsString(canonical(STRICT.valueToTree(value), null));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                ("interview-reschedule-plan-v1\n" + canonical).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256不可用", exception); }
    }
    private static Object canonical(JsonNode node, String field) {
        if (node.isNull()) return null;
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.properties().forEach(entry -> result.put(entry.getKey(), canonical(entry.getValue(), entry.getKey()))); return result;
        }
        if (node.isArray()) { var values = new ArrayList<Object>(); for (JsonNode item : node) values.add(canonical(item, null)); return values; }
        // 只归一已定义的时间字段；用户标题即使长得像日期，也不能被当作 Instant 解析。
        if (node.isTextual()) return field != null && INSTANT_FIELDS.contains(field) ? MICRO_TIME.format(Instant.parse(node.stringValue())) : node.stringValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.longValue();
        throw new IllegalStateException("联动摘要出现未定义数值类型");
    }
    static <T> T bounded(JsonMapper mapper, T value, int bytes) {
        if (mapper.writeValueAsBytes(value).length > bytes) throw limit("准备关联载荷超过字节上限，请缩小范围。"); return value;
    }
    static ApiException invalid(String text) { return new ApiException(400, "INVALID_REQUEST", text); }
    static ApiException limit(String text) { return new ApiException(422, "PREPARATION_LINK_LIMIT", text); }
    static ApiException conflict(String code, String text, Long revision) { return new ApiException(409, code, text, revision); }
    static ApiException sourceChanged() { return conflict("PREPARATION_LINK_SOURCE_CHANGED", "关联来源已变化或不可用，请保留输入后重新读取。", null); }
    static ApiException cursorChanged() { return conflict("PREPARATION_LINK_CURSOR_CHANGED", "关联目录游标已失效，请从首页重新读取。", null); }
}
