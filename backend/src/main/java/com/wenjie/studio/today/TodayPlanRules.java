package com.wenjie.studio.today;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.planning.PlanningRules;
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
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.wenjie.studio.today.TodayDtos.*;
import static com.wenjie.studio.today.TodayPlanDtos.*;

final class TodayPlanRules {
    static final int REQUEST_BYTES = 256 * 1024, HISTORY_BYTES = 32 * 1024, PAGE_BYTES = 1024 * 1024;
    static final long MAX = 9_007_199_254_740_991L;
    private static final JsonMapper STRICT = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                            .maxStringLength(REQUEST_BYTES).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private TodayPlanRules() {}

    static <T> T decode(byte[] raw, Class<T> type) {
        if (raw == null || raw.length == 0) throw invalid("请求须为完整 JSON 对象。");
        if (raw.length > REQUEST_BYTES) throw limit("安排请求超过字节上限。");
        try {
            JsonNode node = STRICT.readTree(raw);
            if (node == null || !node.isObject()) throw invalid("请求须为 JSON 对象。");
            dateShapes(node);
            return STRICT.treeToValue(node, type);
        } catch (ApiException exception) { throw exception; }
        catch (RuntimeException exception) { throw invalid("请求字段缺失、重复、类型无效或包含未知字段。"); }
    }

    private static void dateShapes(JsonNode node) {
        if (node.isArray()) { for (JsonNode item : node) dateShapes(item); return; }
        if (!node.isObject()) return;
        for (var entry : node.properties()) {
            JsonNode value = entry.getValue();
            if (Set.of("localDate", "plannedDate").contains(entry.getKey()) && !value.isNull()) {
                if (!value.isTextual()) throw invalid("日期须为 yyyy-MM-dd 字符串。");
                date(value.stringValue());
            }
            dateShapes(value);
        }
    }

    static void source(SourceRef value) {
        if (value == null || value.kind() == null) throw invalid("来源身份必填。");
        TodayRules.uuid(value.id());
    }

    static void stamp(SourceStamp value, SourceKind expected) {
        if (value == null || value.kind() == null || (expected != null && value.kind() != expected)) throw invalid("来源修订类型不匹配。");
        int present = (value.knowledge() == null ? 0 : 1) + (value.training() == null ? 0 : 1) + (value.task() == null ? 0 : 1)
                + (value.memory() == null ? 0 : 1) + (value.action() == null ? 0 : 1) + (value.interview() == null ? 0 : 1);
        if (present != 1) throw invalid("来源修订只能包含对应类型的完整载荷。");
        switch (value.kind()) {
            case KNOWLEDGE_DRAFT -> {
                var item = value.knowledge(); if (item == null) throw invalid("知识修订载荷缺失。");
                revision(item.itemRevision()); revision(item.draftRevision()); TodayRules.uuid(item.currentVersionId());
            }
            case TRAINING_SESSION -> {
                var item = value.training(); if (item == null) throw invalid("训练修订载荷缺失。");
                revision(item.revision()); revision(item.generation()); hashText(item.questionsHash());
            }
            case PREPARATION_TASK -> { if (value.task() == null) throw invalid("任务修订载荷缺失。"); revision(value.task().revision()); }
            case REVIEW_ACTION -> {
                var item = value.action(); if (item == null) throw invalid("动作修订载荷缺失。");
                revision(item.revision()); if (item.revision() > 1000) throw invalid("动作修订超限。");
                hashText(item.progressHash());
            }
            case INTERVIEW_EVENT -> { if (value.interview() == null) throw invalid("面试修订载荷缺失。"); revision(value.interview().revision()); }
            case MEMORY_CARD -> {
                var item = value.memory(); if (item == null) throw invalid("回忆修订载荷缺失。");
                revision(item.cardRevision()); if (item.cardRevision() > 1000) throw invalid("回忆卡修订超限。");
                TodayRules.uuid(item.cardVersionId()); nullableRevision(item.gateRevision()); nullableRevision(item.scheduleRevision());
                nullableRevision(item.settingsRevision());
                if ((item.attemptId() == null) != (item.attemptRevision() == null)) throw invalid("未决尝试身份不完整。");
                if (item.attemptId() != null) { TodayRules.uuid(item.attemptId()); revision(item.attemptRevision()); }
            }
        }
    }

    static boolean dated(SourceKind kind) {
        return kind == SourceKind.KNOWLEDGE_DRAFT || kind == SourceKind.TRAINING_SESSION || kind == SourceKind.REVIEW_ACTION;
    }
    static boolean estimated(SourceKind kind) { return dated(kind) || kind == SourceKind.MEMORY_CARD; }

    static void content(SourceKind kind, PlacementContent value) {
        if (value == null || value.visibility() == null || value.dateLocked() == null) throw invalid("安排内容和标记必填。");
        range(value.priority(), 1, 5, "优先级"); range(value.orderValue(), 0, 1_000_000, "排序值");
        if ((value.plannedDate() == null) != (value.planZoneId() == null)) throw invalid("安排日期和时区须同时填写。");
        if (value.plannedDate() != null) { date(value.plannedDate()); PlanningRules.zone(value.planZoneId()); }
        if (value.estimatedMinutes() != null) range(value.estimatedMinutes(), 1, 1440, "预计分钟");
        if (!dated(kind) && value.plannedDate() != null) throw invalid("该来源的日期须在原工作区修改。");
        if (!estimated(kind) && value.estimatedMinutes() != null) throw invalid("该来源不能覆盖原预计分钟。");
        if (!dated(kind) && value.visibility() != Visibility.VISIBLE) throw invalid("该来源的暂停须在原工作区处理。");
        if (value.dateLocked() && (dated(kind) ? value.plannedDate() == null : kind != SourceKind.PREPARATION_TASK)) {
            throw invalid("只有具有安排日期的来源可以锁定日期。");
        }
        if (value.suppressedOn() != null) { date(value.suppressedOn().localDate()); PlanningRules.zone(value.suppressedOn().zoneId()); }
    }

    static void contentCurrent(TodayItem source, PlacementContent value, Placement before, Instant now) {
        content(source.source().kind(), value);
        if (source.source().kind() == SourceKind.PREPARATION_TASK && value.dateLocked()
                && source.time().localDate() == null && (before == null || !before.dateLocked())) throw invalid("任务尚无原安排日期，不能新设日期锁。");
        if (value.suppressedOn() != null && (before == null || !value.suppressedOn().equals(before.suppressedOn()))
                && !value.suppressedOn().localDate().equals(now.atZone(ZoneId.of(value.suppressedOn().zoneId())).toLocalDate())) throw dayChanged();
    }

    static PlacementContent content(Placement value) {
        return new PlacementContent(value.plannedDate(), value.planZoneId(), value.estimatedMinutes(), value.priority(),
                value.orderValue(), value.dateLocked(), value.visibility(), value.suppressedOn());
    }
    static PlacementContent defaults() { return new PlacementContent(null, null, null, 3, 500000, false, Visibility.VISIBLE, null); }

    static void candidateRequest(SuggestionRequest request) {
        if (request == null) throw invalid("建议请求必填。");
        date(request.localDate()); PlanningRules.zone(request.zoneId()); nullableRevision(request.expectedBudgetRevision());
        if (request.items() == null || request.items().isEmpty() || request.items().size() > 100) throw invalid("建议须包含1至100个不同来源。");
        Set<SourceRef> seen = new HashSet<>();
        for (SuggestionCandidate candidate : request.items()) {
            if (candidate == null) throw invalid("建议候选不能为空。");
            source(candidate.source()); stamp(candidate.expectedSource(), candidate.source().kind()); nullableRevision(candidate.expectedPlacementRevision());
            if (!seen.add(candidate.source())) throw invalid("建议不能重复选择同一来源。");
            if (candidate.estimatedMinutes() != null) {
                range(candidate.estimatedMinutes(), 1, 1440, "本次预计分钟");
                if (!estimated(candidate.source().kind())) throw invalid("任务和面试不能传本次预计分钟。");
            }
        }
    }

    static void apply(ApplySuggestion value) {
        if (value == null) throw invalid("采用请求必填。");
        candidateRequest(value.request()); hashText(value.expectedPreviewHash());
        if (!Boolean.TRUE.equals(value.confirmed())) throw invalid("须明确确认后采用建议。");
        if (value.selectedSources() == null || value.selectedSources().isEmpty() || value.selectedSources().size() > 50) throw invalid("一次采用须选择1至50项。");
        Set<SourceRef> seen = new HashSet<>();
        for (SourceRef item : value.selectedSources()) { source(item); if (!seen.add(item)) throw invalid("采用来源不能重复。"); }
    }

    static void order(OrderPlacements value) {
        if (value == null || value.items() == null || value.items().isEmpty() || value.items().size() > 50) throw invalid("一次排序须包含1至50项。");
        Set<String> seen = new HashSet<>();
        for (OrderItem item : value.items()) {
            if (item == null) throw invalid("排序项不能为空。");
            TodayRules.uuid(item.placementId()); revision(item.expectedRevision()); stamp(item.expectedSource(), null);
            range(item.orderValue(), 0, 1_000_000, "排序值");
            if (!seen.add(item.placementId())) throw invalid("排序不能重复选择同一偏好。");
        }
    }

    static void reason(String value) {
        if (value == null) return;
        if (value.codePointCount(0, value.length()) < 1 || value.codePointCount(0, value.length()) > 2000
                || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) throw invalid("理由须为1至2000个字符的非空文字。");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) throw invalid("理由不能包含NUL。");
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw invalid("理由包含不完整Unicode。");
                i++;
            } else if (Character.isLowSurrogate(c)) throw invalid("理由包含不完整Unicode。");
        }
    }

    static LocalDate date(String value) { LocalDate result = PlanningRules.date(value); if (result == null) throw invalid("日期必填。"); return result; }
    static void date(LocalDate value) { if (value == null) throw invalid("日期必填。"); date(value.toString()); }
    static void revision(Long value) { if (value == null || value < 0 || value > MAX) throw invalid("修订须为非负安全整数。"); }
    static void nullableRevision(Long value) { if (value != null) revision(value); }
    static long next(long value) { revision(value); if (value == MAX) throw limit("修订达到安全上限。"); return value + 1; }
    static void range(Integer value, int min, int max, String label) { if (value == null || value < min || value > max) throw invalid(label + "超出整数范围。"); }
    static void hashText(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid("预览或来源摘要无效。"); }
    static void clock(Instant now, Instant before) { TodayRules.time(now); if (before != null && now.isBefore(before)) throw conflict("PLAN_CLOCK_ORDER", "服务器时间早于最近安排修改，请稍后恢复原请求。", null); }
    static void page(Long before, int limit) { nullableRevision(before); range(limit, 1, 50, "每页数量"); }

    record DirectoryCursor(int v, String purpose, String ownerId, String zoneId, LocalDate localDate, Instant asOf,
                           SourceKind kind, Visibility visibility, long placementGeneration, Instant afterUpdatedAt, String afterId) {}
    static String cursor(DirectoryCursor value) {
        String result = Base64.getUrlEncoder().withoutPadding().encodeToString(STRICT.writeValueAsBytes(value));
        if (result.length() > 4096) throw invalid("安排目录游标超限。"); return result;
    }
    static DirectoryCursor cursor(String encoded, String owner, PlacementDirectoryQuery query, long generation, Instant now) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 4096 || !encoded.matches("[A-Za-z0-9_-]+")) throw invalid("安排目录游标无效。");
        DirectoryCursor value;
        try {
            value = STRICT.readValue(Base64.getUrlDecoder().decode(encoded), DirectoryCursor.class);
            if (value == null || !encoded.equals(cursor(value))) throw invalid("安排目录游标不是规范编码。");
        } catch (RuntimeException exception) { throw invalid("安排目录游标无效。"); }
        if (value.v() != 1) throw TodayRules.changed();
        if (!"today-placements".equals(value.purpose()) || !owner.equals(value.ownerId()) || !query.zoneId().equals(value.zoneId())
                || query.kind() != value.kind() || query.visibility() != value.visibility()) throw invalid("安排目录游标与当前筛选不匹配。");
        revision(value.placementGeneration()); TodayRules.time(value.asOf()); TodayRules.time(value.afterUpdatedAt()); TodayRules.uuid(value.afterId()); date(value.localDate());
        // asOf 固定本次分类时刻；首次一致性读取可能看见稍后提交的修改，不能拿它限制真实排序时间。
        if (value.asOf().isAfter(now)
                || !value.localDate().equals(value.asOf().atZone(ZoneId.of(value.zoneId())).toLocalDate())) throw invalid("安排目录游标时间无效。");
        if (value.placementGeneration() != generation || Duration.between(value.asOf(), now).compareTo(Duration.ofMinutes(15)) > 0
                || !value.localDate().equals(now.atZone(ZoneId.of(value.zoneId())).toLocalDate())) throw TodayRules.changed();
        return value;
    }

    static String hash(Object value) {
        // JSON 时间统一到微秒，排除在服务中未进入摘要的读取时钟，合法重复预览才可稳定比较。
        JsonNode tree = STRICT.valueToTree(value);
        String canonical = STRICT.writeValueAsString(canonical(tree));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("today-plan-v1\n" + canonical).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256不可用", exception); }
    }
    private static Object canonical(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.properties().forEach(entry -> result.put(entry.getKey(), canonical(entry.getValue()))); return result;
        }
        if (node.isArray()) { var result = new java.util.ArrayList<Object>(); for (JsonNode item : node) result.add(canonical(item)); return result; }
        if (node.isTextual()) {
            String text = node.stringValue();
            if (text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.*Z")) return TodayRules.MICRO_TIME.format(Instant.parse(text));
            return text;
        }
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.longValue();
        throw new IllegalStateException("安排摘要出现未定义数值类型");
    }
    static <T> T bounded(JsonMapper mapper, T value, int bytes) {
        if (mapper.writeValueAsBytes(value).length > bytes) throw limit("安排载荷超过字节上限，请缩小范围。"); return value;
    }
    static ApiException invalid(String text) { return new ApiException(400, "INVALID_REQUEST", text); }
    static ApiException limit(String text) { return new ApiException(422, "TODAY_LIMIT", text); }
    static ApiException conflict(String code, String text, Long revision) { return new ApiException(409, code, text, revision); }
    static ApiException sourceChanged() { return conflict("PLAN_SOURCE_CHANGED", "来源已变化或当前不可安排，请保留输入并重新读取。", null); }
    static ApiException previewChanged() { return conflict("PLAN_PREVIEW_CHANGED", "预算、来源或安排建议已变化，请重新预览后明确采用。", null); }
    static ApiException dayChanged() { return conflict("PLAN_DAY_CHANGED", "所选区域已跨日，请保留输入并重新核对当天安排。", null); }
}
