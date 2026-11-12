package com.wenjie.studio.memory;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.planning.PlanningRules;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static com.wenjie.studio.memory.MemoryAttemptDtos.*;
import static com.wenjie.studio.memory.MemoryReviewRules.*;

final class MemoryAttemptRules {
    static final int ATTEMPT_BYTES = 128 * 1024, SNAPSHOT_BYTES = 32 * 1024, EVENT_BYTES = 128 * 1024;
    static final int DETAIL_BYTES = 384 * 1024, REVEAL_BYTES = 512 * 1024, SMALL_PAGE_BYTES = 512 * 1024;
    static final int DIRECTORY_BYTES = 1024 * 1024, CRAM_BYTES = 128 * 1024;
    private static final JsonMapper STRICT = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5)
                            .maxStringLength(128 * 1024).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private MemoryAttemptRules() {}

    static <T> T decode(byte[] raw, Class<T> type) {
        int max = type == SaveResponse.class || type == Reveal.class ? ATTEMPT_BYTES : REQUEST_BYTES;
        if (raw == null || raw.length == 0) throw invalid("请求须为 JSON 对象。");
        if (raw.length > max) throw limit("主动回忆请求超过字节上限。");
        JsonNode body;
        try { body = STRICT.readTree(raw); }
        catch (RuntimeException exception) { throw invalid("JSON 格式无效、字段重复或嵌套超限。"); }
        if (type == StartAttempt.class) fields(body, "expectedCardRevision", "expectedGateRevision", "expectedScheduleRevision", "mode", "countRegular", "cramBatchId", "continueBeyondBudget");
        else if (type == SaveResponse.class) fields(body, "expectedRevision", "responseMode", "response");
        else if (type == Reveal.class) fields(body, "expectedRevision", "responseMode", "response", "thoughtConfirmed");
        else if (type == Rate.class) fields(body, "expectedRevision", "rating");
        else if (type == Skip.class) fields(body, "expectedRevision", "reason");
        else if (type == CreateCramBatch.class) fields(body, "title", "startDate", "endDate", "zoneId", "cardIds");
        else throw new IllegalArgumentException("未定义主动回忆请求类型");
        for (var field : body.properties()) {
            String name = field.getKey(); JsonNode value = field.getValue();
            if (name.equals("expectedScheduleRevision") && value.isNull()) continue;
            if (name.startsWith("expected")) {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid("修订号必须为整数。");
            } else if (Set.of("countRegular", "continueBeyondBudget", "thoughtConfirmed").contains(name)) {
                if (!value.isBoolean()) throw invalid("确认与计入标记必须为布尔值。");
            } else if (name.equals("cramBatchId") && value.isNull()) continue;
            else if (name.equals("cardIds")) {
                if (!value.isArray() || value.size() < 1 || value.size() > 200) throw invalid("冲刺范围须包含 1 至 200 张卡。");
                for (JsonNode item : value) { if (!item.isTextual()) throw invalid("卡标识必须为字符串。"); MemoryRules.uuid(item.stringValue()); }
            } else {
                if (!value.isTextual()) throw invalid("文本、日期与枚举须为字符串。");
                MemoryRules.text(value.stringValue(), name.equals("response") ? 10_000 : 1000, false);
                if (name.equals("startDate") || name.equals("endDate")) date(value.stringValue());
            }
        }
        try { return STRICT.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid("主动回忆字段或枚举不符合契约。"); }
    }

    private static void fields(JsonNode body, String... names) {
        if (body == null || !body.isObject() || body.size() != names.length) throw invalid("请求字段缺失或包含未定义字段。");
        Set<String> allowed = Set.of(names);
        for (var field : body.properties()) if (!allowed.contains(field.getKey())) throw invalid("请求包含未定义字段。");
    }

    static void start(StartAttempt request) {
        MemoryRules.required(request); cardRevision(request.expectedCardRevision()); revision(request.expectedGateRevision());
        MemoryRules.required(request.mode()); MemoryRules.required(request.countRegular()); MemoryRules.required(request.continueBeyondBudget());
        if (request.countRegular()) revision(request.expectedScheduleRevision());
        else if (request.expectedScheduleRevision() != null || request.continueBeyondBudget()) throw invalid("未计入冲刺不接受排程修订或超预算确认。");
        if (request.mode() == Mode.REGULAR) {
            if (!request.countRegular() || request.cramBatchId() != null) throw invalid("常规尝试必须计入排程且不属于冲刺批次。");
        } else MemoryRules.uuid(request.cramBatchId());
    }

    static void response(ResponseMode mode, String text) {
        MemoryRules.required(mode);
        if (mode == ResponseMode.THOUGHT) {
            if (!"".equals(text)) throw invalid("只想模式的文字回答必须为空串。");
        } else MemoryRules.text(text, 10_000, true);
    }

    static void cram(CreateCramBatch request) {
        MemoryRules.required(request); MemoryRules.text(request.title(), 200, true); PlanningRules.zone(request.zoneId());
        if (request.startDate() == null || request.endDate() == null) throw invalid("冲刺起止日期必填。");
        date(request.startDate().toString()); date(request.endDate().toString());
        long days = ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;
        if (days < 1 || days > 90) throw invalid("冲刺计划包含首尾须为 1 至 90 天。");
        if (request.cardIds() == null || request.cardIds().isEmpty() || request.cardIds().size() > 200) throw invalid("冲刺范围须包含 1 至 200 张卡。");
        request.cardIds().forEach(MemoryRules::uuid);
        if (new HashSet<>(request.cardIds()).size() != request.cardIds().size()) throw invalid("冲刺范围不能重复选择同一卡。");
    }

    private static LocalDate date(String value) {
        try {
            if (!value.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}")) throw invalid("日期须为 1000 至 9999 年的 ISO 本地日期。");
            return LocalDate.parse(value);
        } catch (DateTimeException exception) { throw invalid("本地日期无效。"); }
    }

    static RatingDay day(String zone, Instant now, Long settingsRevision) {
        PlanningRules.zone(zone);
        try {
            ZoneId id = ZoneId.of(zone); LocalDate local = now.atZone(id).toLocalDate(); date(local.toString());
            Instant start = local.atStartOfDay(id).toInstant(), end = local.plusDays(1).atStartOfDay(id).toInstant();
            time(start); time(end);
            if (now.isBefore(start) || !now.isBefore(end)) throw new DateTimeException("日区间不包含事件");
            return new RatingDay(zone, local, start, end, settingsRevision);
        } catch (DateTimeException | ApiException exception) {
            throw problem("MEMORY_TIME_CONFLICT", "当前日期和时区无法形成可保存的完整日区间。");
        }
    }

    static boolean open(State state) { return state == State.THINKING || state == State.REVEALED; }
    static void clock(Instant now, Instant previous) {
        time(now); if (previous != null && now.isBefore(previous)) throw problem("MEMORY_CLOCK_ORDER", "服务器时间早于最近操作，本次未提交，请稍后恢复原请求。");
    }
    static long elapsed(Instant from, Instant to) { clock(to, from); return Duration.between(from, to).toMillis(); }
    static long count(long value) { if (value < 0 || value > MAX) throw limit("统计数量超出安全整数范围。"); return value; }
    static ApiException problem(String code, String text) { return new ApiException(409, code, text); }
    static ApiException missingAttempt() { return new ApiException(404, "MEMORY_ATTEMPT_NOT_FOUND", "回忆尝试不存在或不可访问。"); }
    static ApiException missingBatch() { return new ApiException(404, "MEMORY_CRAM_BATCH_NOT_FOUND", "冲刺批次不存在或不可访问。"); }
    static ApiException unavailable() { return problem("MEMORY_ATTEMPT_STATE_UNAVAILABLE", "未决尝试与占位不一致，请保留记录并处理状态异常。"); }
    static void ready(MemoryReviewDtos.Eligibility value) {
        if (value.basisReady()) return;
        var blockers = value.blockers();
        if (blockers.contains(MemoryReviewDtos.BlockReason.CARD_ARCHIVED)) throw problem("ITEM_ARCHIVED", "请先恢复回忆卡。");
        if (blockers.contains(MemoryReviewDtos.BlockReason.GATE_MISSING)) throw problem("MEMORY_GATE_UNAVAILABLE", "来源观察基线缺失。");
        if (blockers.contains(MemoryReviewDtos.BlockReason.SOURCE_UNAVAILABLE)) throw new ApiException(422, "SOURCE_UNAVAILABLE", "回忆卡存在不可用依据。");
        if (blockers.contains(MemoryReviewDtos.BlockReason.SOURCE_UPSTREAM_REVIEW_REQUIRED)) {
            throw problem("KNOWLEDGE_REVALIDATION_REQUIRED", "来源知识的历史采用来源仍待核对，请先处理来源更正。");
        }
        if (blockers.stream().anyMatch(b -> b == MemoryReviewDtos.BlockReason.SOURCE_BODY_CHANGED || b == MemoryReviewDtos.BlockReason.ARCHIVED_NOTE_SOURCE || b == MemoryReviewDtos.BlockReason.NO_VALID_SOURCE)) {
            throw problem("SOURCE_CHANGED", "请先核对并更新卡片来源。");
        }
        throw problem("MEMORY_REVIEW_REQUIRED", "请先显式完成当前来源复核。");
    }

    public record DirectoryCursor(int version, String purpose, Instant time, String id) {}
    public record QueueCursor(int version, String purpose, Instant asOf, long settingsRevision, String zoneId,
                              LocalDate localDate, boolean includeOverBudget, int phaseRank, Instant dueAt, String cardId) {}
    static String cursor(Object value) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(STRICT.writeValueAsBytes(value));
        if (encoded.length() > 2048) throw invalid("分页游标超限。"); return encoded;
    }
    private static <T> T cursor(String source, Class<T> type) {
        if (source == null) return null;
        if (source.isEmpty() || source.length() > 2048 || !source.matches("[A-Za-z0-9_-]+")) throw invalid("分页游标无效。");
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(source);
            T value = STRICT.readValue(bytes, type);
            // 规范化回编码使未知字段、缺字段、重复字段及非规范形式都不能改变查询条件。
            if (!source.equals(cursor(value))) throw invalid("分页游标不是规范形式。");
            return value;
        } catch (RuntimeException exception) { throw invalid("分页游标无效。"); }
    }
    static DirectoryCursor directoryCursor(String source, String purpose, Instant now) {
        DirectoryCursor value = cursor(source, DirectoryCursor.class); if (value == null) return null;
        if (value.version() != 1 || !purpose.equals(value.purpose())) throw invalid("分页游标用途不匹配。");
        time(value.time()); MemoryRules.uuid(value.id());
        if (value.time().isAfter(now)) throw invalid("分页游标不能来自未来。");
        return value;
    }
    static QueueCursor queueCursor(String source, Instant now) {
        QueueCursor value = cursor(source, QueueCursor.class); if (value == null) return null;
        if (value.version() != 1 || !"review-queue".equals(value.purpose()) || value.phaseRank() < 0 || value.phaseRank() > 1) throw invalid("队列游标排序键无效。");
        time(value.asOf()); time(value.dueAt()); revision(value.settingsRevision()); PlanningRules.zone(value.zoneId()); MemoryRules.uuid(value.cardId());
        if (value.localDate() == null) throw invalid("队列游标日期缺失。"); date(value.localDate().toString());
        if (value.asOf().isAfter(now) || value.dueAt().isAfter(value.asOf())) throw invalid("队列游标包含未来到期边界。");
        return value;
    }

    static Attempt copy(Attempt before, State state, ResponseMode mode, String response, Instant saved,
                        Instant now, Instant revealed, Instant rated, Instant closed, Long thinking, Long total,
                        Rating rating, String reason, Invalidation invalidation, boolean event) {
        clock(now, before.updatedAt());
        return new Attempt(before.id(), before.cardId(), before.sequence(), before.start(), next(before.revision()), state,
                mode, response, saved, before.startedAt(), now, revealed, rated, closed, thinking, total, rating, reason,
                invalidation, event ? next(before.lastEventSequence()) : before.lastEventSequence());
    }
}
