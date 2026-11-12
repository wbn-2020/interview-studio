package com.wenjie.studio.memory;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.planning.PlanningRules;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.wenjie.studio.memory.MemoryReviewDtos.*;

final class MemoryReviewRules {
    static final long MAX = 9_007_199_254_740_991L;
    static final int REQUEST_BYTES = 16 * 1024, RESPONSE_BYTES = 64 * 1024, HISTORY_BYTES = 16 * 1024, PAGE_BYTES = 1024 * 1024;
    static final String ALGORITHM = "java-fsrs:1.0.0@c561ee6c56239b621a0a45b16a6c2e688441eae5";
    static final String PARAMETERS = "fsrs-java-1.0.0-default-unfuzzed-1";
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    private MemoryReviewRules() {}

    static <T> T decode(JsonMapper json, byte[] raw, Class<T> type) {
        if (raw == null || raw.length == 0) throw invalid("请求须为 JSON 对象。");
        if (raw.length > REQUEST_BYTES) throw limit("回忆管理请求不能超过 16 KiB。");
        JsonNode body;
        try { body = json.readTree(raw); }
        catch (RuntimeException exception) { throw invalid("JSON 格式无效、字段重复或嵌套超限。"); }
        if (type == SourceReview.class) {
            fields(body, "expectedCardRevision", "expectedGateRevision", "confirmed", "reason");
        } else if (type == EnableSchedule.class) {
            fields(body, "expectedCardRevision", "expectedGateRevision", "timeMode", "at", "confirmed");
        } else if (type == DeferSchedule.class) {
            fields(body, "expectedScheduleRevision", "deferUntil", "reason");
        } else if (type == PauseSchedule.class) {
            fields(body, "expectedScheduleRevision", "reason");
        } else if (type == ResumeSchedule.class) {
            fields(body, "expectedCardRevision", "expectedGateRevision", "expectedScheduleRevision", "policy", "timeMode", "at", "confirmed", "reason");
        } else if (type == CreateSettings.class) {
            fields(body, "zoneId", "dailyNewBudget", "dailyReviewBudget");
        } else if (type == SaveSettings.class) {
            fields(body, "expectedRevision", "zoneId", "dailyNewBudget", "dailyReviewBudget");
        } else throw new IllegalArgumentException("未定义回忆管理请求类型");
        for (var field : body.properties()) {
            String name = field.getKey(); JsonNode value = field.getValue();
            if (name.startsWith("expected") || name.endsWith("Budget")) {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid("修订号和预算必须为整数。");
            } else if (name.equals("confirmed")) {
                if (!value.isBoolean() || !value.booleanValue()) throw invalid("须明确确认本次操作。");
            } else if (name.equals("at") && value.isNull()) {
                continue;
            } else {
                if (!value.isTextual()) throw invalid("文本、枚举和时间字段必须为字符串。");
                MemoryRules.text(value.stringValue(), 1000, false);
                if (name.equals("at") || name.equals("deferUntil")) {
                    String source = value.stringValue();
                    if (!source.endsWith("Z") || !source.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z")) {
                        throw invalid("时间须为可精确表示为微秒的 UTC Z 时间。");
                    }
                    try { time(Instant.parse(source)); }
                    catch (java.time.DateTimeException exception) { throw invalid("时间格式无效。"); }
                }
            }
        }
        try { return json.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid("请求字段、类型或枚举不符合回忆管理要求。"); }
    }

    private static void fields(JsonNode body, String... fields) {
        if (body == null || !body.isObject() || body.size() != fields.length) throw invalid("请求字段缺失或包含未定义字段。");
        Set<String> names = Set.of(fields);
        for (var field : body.properties()) if (!names.contains(field.getKey())) throw invalid("请求包含未定义字段。");
    }

    static void revision(Long value) { if (value == null || value < 0 || value > MAX) throw invalid("修订号超出安全整数范围。"); }
    static void cardRevision(Long value) { MemoryRules.revision(value); }
    static long next(long value) { if (value < 0 || value >= MAX) throw new ApiException(409, "REVISION_LIMIT", "修订或历史序号已到上限。"); return value + 1; }
    static void reason(String value) { MemoryRules.text(value, 1000, true); }
    static void confirmed(Boolean value) { if (!Boolean.TRUE.equals(value)) throw invalid("须明确确认本次操作。"); }
    static void expected(long actual, long expected, String code) {
        if (actual != expected) throw new ApiException(409, code, "对象已变化，请保留输入并重新读取。", actual);
    }
    static void changed(int count) { if (count != 1) throw new IllegalStateException("回忆管理 CAS 更新未匹配锁内状态"); }
    static void page(Long before, int limit) {
        if (before != null && (before < 1 || before > MAX)) throw invalid("历史游标超出安全整数范围。");
        if (limit < 1 || limit > 50) throw invalid("每页条数须为 1 至 50。");
    }
    static void settings(String zone, Integer fresh, Integer review) {
        PlanningRules.zone(zone);
        if (fresh == null || review == null || fresh < 0 || fresh > 1000 || review < 0 || review > 1000) throw invalid("每日建议数量须为 0 至 1000。");
    }
    static void time(Instant value) {
        if (value == null || value.isBefore(MIN_TIME) || value.isAfter(MAX_TIME) || !value.equals(value.truncatedTo(ChronoUnit.MICROS))) {
            throw invalid("时间超出持久化范围或不是微秒精度。");
        }
    }
    static void timing(TimeMode mode, Instant at) {
        if (mode == null || (mode == TimeMode.NOW && at != null) || (mode == TimeMode.AT && at == null)) throw invalid("NOW 须 at=null，AT 须指定未来时间。");
        if (at != null) time(at);
    }
    static Instant effective(TimeMode mode, Instant at, Instant now) {
        timing(mode, at);
        if (mode == TimeMode.NOW) return now;
        if (!at.isAfter(now)) throw new ApiException(409, "MEMORY_TIME_CONFLICT", "指定时间已不在未来，请保留输入并重新选择。");
        return at;
    }
    static Instant now() { Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); time(now); return now; }
    static Actor actor(String owner) { return new Actor(ActorKind.OWNER, owner); }
    static void writeTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("来源失效必须加入已取得 owner 锁的写事务");
        }
    }

    static List<SourceSnapshot> sources(List<MemoryReviewRows.SourceRow> rows) {
        return rows.stream().map(row -> {
            if (row.kind() == null || row.versionNumber() == null || row.status() == null || row.currentVersionId() == null
                    || row.bodyChanged() == null || row.upstreamReviewRequired() == null) {
                return new SourceSnapshot(row.itemId(), row.versionId(), null, null, SourceState.UNAVAILABLE, null, null, null);
            }
            SourceState state = row.status().equals("ARCHIVED") ? SourceState.ARCHIVED
                    : row.versionId().equals(row.currentVersionId()) ? SourceState.CURRENT : SourceState.SUPERSEDED;
            return new SourceSnapshot(row.itemId(), row.versionId(), KnowledgeDtos.Kind.valueOf(row.kind()), row.versionNumber(),
                    state, row.currentVersionId(), row.bodyChanged(), row.upstreamReviewRequired());
        }).toList();
    }

    static Eligibility eligibility(MemoryRows.CardRow card, Gate gate, List<SourceSnapshot> sources) {
        List<BlockReason> blockers = new ArrayList<>();
        if (!card.status().equals("ACTIVE")) blockers.add(BlockReason.CARD_ARCHIVED);
        if (gate == null) blockers.add(BlockReason.GATE_MISSING);
        if (gate != null && gate.reviewRequired()) blockers.add(BlockReason.REVIEW_REQUIRED);
        if (sources.isEmpty() || sources.stream().anyMatch(s -> s.state() == SourceState.UNAVAILABLE)) blockers.add(BlockReason.SOURCE_UNAVAILABLE);
        if (sources.stream().anyMatch(s -> Boolean.TRUE.equals(s.bodyChanged()))) blockers.add(BlockReason.SOURCE_BODY_CHANGED);
        if (sources.stream().anyMatch(s -> Boolean.TRUE.equals(s.upstreamReviewRequired()))) blockers.add(BlockReason.SOURCE_UPSTREAM_REVIEW_REQUIRED);
        if (sources.stream().anyMatch(s -> s.kind() == KnowledgeDtos.Kind.NOTE && s.state() == SourceState.ARCHIVED)) blockers.add(BlockReason.ARCHIVED_NOTE_SOURCE);
        boolean usable = sources.stream().anyMatch(MemoryReviewRules::usable);
        if (!usable) blockers.add(BlockReason.NO_VALID_SOURCE);
        boolean reviewAllowed = blockers.stream().allMatch(b -> b == BlockReason.REVIEW_REQUIRED);
        boolean archivedConcept = sources.stream().anyMatch(s -> s.kind() == KnowledgeDtos.Kind.CONCEPT && s.state() == SourceState.ARCHIVED);
        List<Warning> warnings = archivedConcept && usable ? List.of(Warning.ARCHIVED_CONCEPT_WITH_OTHER_SOURCE) : List.of();
        return new Eligibility(reviewAllowed, blockers.isEmpty(), List.copyOf(blockers), warnings);
    }

    static boolean usable(SourceSnapshot source) {
        return (source.state() == SourceState.CURRENT || source.state() == SourceState.SUPERSEDED)
                && Boolean.FALSE.equals(source.bodyChanged()) && !Boolean.TRUE.equals(source.upstreamReviewRequired());
    }

    static Schedule schedule(MemoryReviewRows.ScheduleRow row) {
        if (row == null) return null;
        Instant effective = row.deferUntil() != null && row.deferUntil().isAfter(row.suggestedDueAt()) ? row.deferUntil() : row.suggestedDueAt();
        return new Schedule(row.cardId(), row.revision(), row.cycleId(), ScheduleStatus.valueOf(row.status()),
                row.pauseReason() == null ? null : PauseReason.valueOf(row.pauseReason()), row.algorithmVersion(), row.parameterVersion(),
                new FsrsState(FsrsPhase.valueOf(row.phase()), row.step(), row.stability(), row.difficulty(), row.suggestedDueAt(), row.lastReviewAt()),
                row.suggestedDueAt(), row.deferUntil(), effective, row.lastRatedEventId(), row.lastHistorySequence(), row.createdAt(), row.updatedAt());
    }

    static Schedule transition(Schedule previous, ScheduleStatus status, PauseReason pauseReason, Instant defer, Instant now) {
        Instant effective = defer != null && defer.isAfter(previous.suggestedDueAt()) ? defer : previous.suggestedDueAt();
        return new Schedule(previous.cardId(), next(previous.revision()), previous.cycleId(), status, pauseReason,
                previous.algorithmVersion(), previous.parameterVersion(), previous.state(), previous.suggestedDueAt(), defer, effective,
                previous.lastRatedEventId(), next(previous.lastHistorySequence()), previous.createdAt(), now);
    }
    static <T> T bounded(JsonMapper json, T value, int maximum) {
        encode(json, value, maximum); return value;
    }
    static String encode(JsonMapper json, Object value, int maximum) {
        String text = json.writeValueAsString(value);
        if (text.getBytes(StandardCharsets.UTF_8).length > maximum) throw limit("回忆管理响应或历史摘要超出上限，操作未提交。");
        return text;
    }
    static ApiException invalid(String message) { return new ApiException(400, "INVALID_REQUEST", message); }
    static ApiException limit(String message) { return new ApiException(422, "MEMORY_LIMIT", message); }
}
