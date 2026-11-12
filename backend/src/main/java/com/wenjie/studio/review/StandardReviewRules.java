package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.planning.PlanningRules;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static com.wenjie.studio.review.StandardReviewDtos.*;

/** 只校验结构和不可变关系；来源归属、访问权限与当前资格由同一事务中的服务核对。 */
public final class StandardReviewRules {
    public static final long MAX = 9_007_199_254_740_991L;
    public static final int REQUEST_BYTES = 256 * 1024, HISTORY_BYTES = 256 * 1024, PAGE_BYTES = 1024 * 1024;
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");
    private static final DateTimeFormatter MICRO_TIME = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private static final Set<String> DATE_FIELDS = Set.of("reviewedOn", "capturedOn", "observedOn", "localDate");
    private static final Set<String> TIME_FIELDS = Set.of("createdAt", "revisedAt", "updatedAt", "recordedAt",
            "answerCreatedAt", "lastExposureAt", "checkedAt", "grantedAt", "asOf");
    private static final JsonMapper STRICT = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20)
                            .maxStringLength(REQUEST_BYTES).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // Textual 不受标量开关约束，须显式拒绝把数字或布尔值改写成证据文字。
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private StandardReviewRules() {}

    public static <T> T decode(byte[] raw, Class<T> type) {
        if (raw == null || raw.length == 0) throw invalid("请求须为完整 JSON 对象。");
        if (raw.length > REQUEST_BYTES) throw limit("审阅请求超过 256 KiB。");
        try {
            JsonNode node = STRICT.readTree(raw);
            if (node == null || !node.isObject()) throw invalid("请求须为 JSON 对象。");
            shapes(node);
            return STRICT.treeToValue(node, type);
        } catch (ApiException exception) { throw exception; }
        catch (RuntimeException exception) { throw invalid("请求字段缺失、重复、类型无效或包含未知字段。"); }
    }

    private static void shapes(JsonNode node) {
        if (node.isArray()) { for (var child : node) shapes(child); return; }
        if (!node.isObject()) return;
        for (var field : node.properties()) {
            JsonNode value = field.getValue();
            if (DATE_FIELDS.contains(field.getKey()) && !value.isNull()) {
                if (!value.isTextual()) throw invalid("日期须为 yyyy-MM-dd 字符串。");
                date(value.stringValue());
            }
            if (TIME_FIELDS.contains(field.getKey()) && !value.isNull()) {
                if (!value.isTextual()) throw invalid("时间须为 ISO-8601 字符串。");
                time(Instant.parse(value.stringValue()));
            }
            shapes(value);
        }
    }

    public static <T> T required(T value) { if (value == null) throw invalid("必填字段不能为空。"); return value; }
    public static String uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid("对象标识须为标准小写 UUID。"); }
        return value;
    }
    public static long revision(Long value) {
        if (value == null || value < 0 || value > MAX) throw invalid("修订须为非负安全整数。");
        return value;
    }
    public static long next(long value) {
        revision(value); if (value == MAX) throw limit("审阅修订已达到安全上限。"); return value + 1;
    }
    public static String text(String value, int max, boolean nonblank) {
        if (value == null || value.codePointCount(0, value.length()) > max
                || nonblank && value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || value.indexOf('\0') >= 0 || value.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) {
            throw invalid("文字为空、超长或包含无效 Unicode 字符。");
        }
        return value;
    }
    public static String hashText(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid("摘要须为小写 SHA-256。"); return value;
    }
    public static LocalDate date(String value) { return required(PlanningRules.date(value)); }
    public static LocalDate date(LocalDate value) { required(value); return date(value.toString()); }
    public static String zone(String value) { return PlanningRules.zone(value); }
    public static LocalDate localDate(Instant now, String zoneId) { return time(now).atZone(ZoneId.of(zone(zoneId))).toLocalDate(); }
    public static void date(LocalDate value, String zoneId, Instant now) {
        if (date(value).isAfter(localDate(now, zoneId))) throw invalid("声明日期不能晚于所选时区的服务器当天。");
    }
    public static Instant time(Instant value) {
        if (value == null || value.isBefore(MIN_TIME) || value.isAfter(MAX_TIME) || value.getNano() % 1000 != 0) {
            throw invalid("时间须在 1000 至 9999 年范围内且精确到微秒。");
        }
        return value;
    }
    public static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    public static void clock(Instant now, Instant before) {
        if (time(now).isBefore(time(before))) throw conflict("REVIEW_CLOCK_CHANGED", "服务器时间早于最近修改，请稍后重新读取。", null);
    }
    public static QuestionVersionRef question(QuestionVersionRef value) {
        required(value); uuid(value.questionId()); uuid(value.versionId()); return value;
    }
    public static RevisionRef ref(RevisionRef value) { required(value); uuid(value.id()); revision(value.revision()); return value; }
    public static RecordStatus status(RecordStatus value) { return required(value); }
    public static RecordStatus status(String value) {
        try { return RecordStatus.valueOf(required(value)); }
        catch (IllegalArgumentException exception) { throw invalid("记录状态无效。"); }
    }

    public static StandardReviewDraft draft(StandardReviewDraft value, Instant now) {
        required(value); edit(value.id(), value.expectedRevision(), value.changeReason()); question(value.question());
        uuid(value.referenceExposureId()); content(value.content(), now); return value;
    }
    public static void edit(String id, Long expectedRevision, String changeReason) {
        if ((id == null) != (expectedRevision == null)) throw invalid("编辑标识与预期修订须同时为空或同时提供。");
        if (id == null) {
            if (changeReason != null) throw invalid("新建记录的修改理由须为 null。");
        } else { uuid(id); revision(expectedRevision); text(changeReason, 2000, true); }
    }
    public static WithdrawRecord withdraw(WithdrawRecord value) {
        required(value); revision(value.expectedRevision()); text(value.reason(), 2000, true); return value;
    }
    public static OpenStandardReview open(OpenStandardReview value) { required(value); revision(value.revision()); return value; }

    public static StandardReviewContent content(StandardReviewContent value, Instant now) {
        required(value); required(value.reviewer()); text(value.reviewer().label(), 120, true);
        text(value.reviewer().description(), 2000, true); text(value.reviewContext(), 2000, true);
        text(value.limitations(), 2000, true); date(value.reviewedOn(), value.reviewZoneId(), now);
        collection(value.checkpoints(), 1, 30); collection(value.evidence(), 1, 12);
        var evidence = new HashMap<String, ReviewEvidence>();
        var locations = new HashSet<String>(); var excerpts = new HashSet<String>();
        for (var item : value.evidence()) {
            evidence(item, value.reviewZoneId(), now);
            if (evidence.put(item.id(), item) != null || !locations.add(location(item))) throw invalid("依据标识或确切来源定位重复。");
            if (item.kind() == BasisKind.MATERIAL_EXCERPT) excerpts.add(item.material().excerptId());
        }
        if (excerpts.size() > 5) throw basisLimit("一次最多引用 5 个不同材料摘录。");
        var checkpointIds = new HashSet<String>(); var used = new HashSet<String>();
        for (var checkpoint : value.checkpoints()) {
            required(checkpoint); uuid(checkpoint.checkpointId()); required(checkpoint.decision());
            if (!checkpointIds.add(checkpoint.checkpointId())) throw invalid("检查项不能重复。");
            text(checkpoint.conditions(), 2000, true); text(checkpoint.conclusion(), 2000, true);
            text(checkpoint.limitations(), 2000, true); collection(checkpoint.evidenceIds(), 1, 12);
            var selected = new HashSet<String>(); var roles = new HashSet<EvidenceRole>();
            for (String id : checkpoint.evidenceIds()) {
                uuid(id); var item = evidence.get(id);
                if (item == null || !selected.add(id)) throw invalid("检查项引用必须存在且不能重复。");
                used.add(id); roles.addAll(item.roles());
            }
            if (roles.size() != EvidenceRole.values().length) throw invalid("每个检查项须同时有实际审阅记录与技术依据。");
        }
        if (used.size() != evidence.size()) throw invalid("每条依据至少须被一个检查项使用。");
        return value;
    }

    public static ReviewEvidence evidence(ReviewEvidence value, String zoneId, Instant now) {
        required(value); uuid(value.id()); required(value.kind()); enumOrder(value.roles(), 1, 2);
        text(value.provenanceNote(), 2000, true); text(value.useReason(), 2000, true);
        if ((value.knowledge() == null ? 0 : 1) + (value.material() == null ? 0 : 1) + (value.external() == null ? 0 : 1) != 1) {
            throw invalid("依据须且只能提供对应来源的一个完整载荷。");
        }
        switch (value.kind()) {
            case KNOWLEDGE -> citation(required(value.knowledge()));
            case MATERIAL_EXCERPT -> citation(required(value.material()));
            case EXTERNAL_QUOTE -> external(required(value.external()), zoneId, now);
        }
        return value;
    }
    private static String location(ReviewEvidence value) {
        return switch (value.kind()) {
            case KNOWLEDGE -> value.kind() + ":" + value.knowledge().itemId() + ":" + value.knowledge().versionId()
                    + ":" + value.knowledge().start() + ":" + value.knowledge().end();
            case MATERIAL_EXCERPT -> value.kind() + ":" + value.material().excerptId() + ":" + value.material().start() + ":" + value.material().end();
            case EXTERNAL_QUOTE -> canonicalJson(List.of(value.kind(), value.external().url(), value.external().locator()));
        };
    }
    public static ExternalQuote external(ExternalQuote value, String zoneId, Instant now) {
        required(value); text(value.url(), 2000, true); text(value.title(), 200, true); text(value.locator(), 500, true);
        text(value.quote(), 2000, true); text(value.sourceDescription(), 2000, true); date(value.capturedOn(), zoneId, now);
        try {
            URI uri = new URI(value.url());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                    || value.url().codePoints().anyMatch(Character::isISOControl)) throw new URISyntaxException(value.url(), "shape");
        } catch (URISyntaxException exception) { throw invalid("外部 URL 须为含主机且不含用户信息或控制字符的 HTTP(S) 地址。"); }
        return value;
    }
    public static AiDtos.Citation citation(AiDtos.Citation value) {
        required(value); uuid(value.itemId()); uuid(value.versionId()); quoteShape(value.start(), value.end(), value.quote(), 2000); return value;
    }
    public static AiDtos.MaterialCitation citation(AiDtos.MaterialCitation value) {
        required(value); uuid(value.excerptId()); quoteShape(value.start(), value.end(), value.quote(), 2000); return value;
    }
    public static void quoteShape(int start, int end, String quote, int max) {
        text(quote, max, true);
        if (start < 0 || end <= start || (long) end - start != quote.length()) throw citationInvalid();
    }
    public static void quote(String original, int start, int end, String quote, int max) {
        quoteShape(start, end, quote, max);
        if (original == null || end > original.length() || splitSurrogate(original, start) || splitSurrogate(original, end)
                || !quote.equals(original.substring(start, end))) throw citationInvalid();
    }
    public static boolean splitSurrogate(String value, int position) {
        return position > 0 && position < value.length() && Character.isHighSurrogate(value.charAt(position - 1))
                && Character.isLowSurrogate(value.charAt(position));
    }
    public static <T> List<T> collection(List<T> values, int min, int max) {
        required(values); if (values.size() < min || values.size() > max) throw limit("集合条数超出允许范围。");
        for (var value : values) required(value); return values;
    }
    public static <E extends Enum<E>> void enumOrder(List<E> values, int min, int max) {
        collection(values, min, max); int previous = -1;
        for (E value : values) { if (value.ordinal() <= previous) throw invalid("枚举集合须按声明顺序且不能重复。"); previous = value.ordinal(); }
    }

    public static DirectRef direct(DirectRef value) { required(value); required(value.kind()); uuid(value.id()); return value; }
    public static DirectStamp stamp(DirectStamp value) {
        required(value); direct(value.source()); revision(value.generation()); hashText(value.fingerprint());
        if (value.source().kind() == DirectKind.MATERIAL_ATTACHMENT) {
            if (value.currentVersionId() != null || !("READY".equals(value.status()) || "RESERVED".equals(value.status()))) throw invalid("材料基线状态或版本无效。");
        } else {
            uuid(value.currentVersionId());
            if (!("ACTIVE".equals(value.status()) || "ARCHIVED".equals(value.status()))) throw invalid("题目或知识基线状态无效。");
        }
        return value;
    }
    public static BasisSeal basis(BasisSeal value) {
        required(value); question(value.question()); collection(value.directSources(), 1, 13);
        DirectRef previous = null; boolean foundQuestion = false;
        for (var item : value.directSources()) {
            stamp(item); DirectRef current = item.source();
            if (previous != null && (previous.kind().ordinal() > current.kind().ordinal()
                    || previous.kind() == current.kind() && previous.id().compareTo(current.id()) >= 0)) throw invalid("直接来源须按类型和标识排序且不能重复。");
            if (current.kind() == DirectKind.QUESTION) {
                if (foundQuestion || !current.id().equals(value.question().questionId())) throw invalid("题目基线身份不一致。");
                foundQuestion = true;
            }
            previous = current;
        }
        if (!foundQuestion) throw invalid("直接基线缺少确切题目。"); return value;
    }
    public static StandardReviewSnapshot snapshot(StandardReviewSnapshot value) {
        required(value); uuid(value.id()); question(value.question()); revision(value.revision()); status(value.status());
        time(value.createdAt()); time(value.revisedAt());
        if (value.revisedAt().isBefore(value.createdAt()) || value.revision() == 0 && (!value.createdAt().equals(value.revisedAt()) || value.status() != RecordStatus.ACTIVE)) throw integrity();
        content(value.content(), value.revisedAt()); basis(value.basis());
        if (!value.question().equals(value.basis().question())) throw integrity();
        var knowledge = new HashSet<String>(); var excerpts = new HashSet<String>();
        for (var evidence : value.content().evidence()) {
            if (evidence.kind() == BasisKind.KNOWLEDGE) knowledge.add(evidence.knowledge().itemId());
            if (evidence.kind() == BasisKind.MATERIAL_EXCERPT) excerpts.add(evidence.material().excerptId());
        }
        var recordedKnowledge = new HashSet<String>(); int attachments = 0;
        for (var source : value.basis().directSources()) {
            if (source.source().kind() == DirectKind.KNOWLEDGE) recordedKnowledge.add(source.source().id());
            if (source.source().kind() == DirectKind.MATERIAL_ATTACHMENT) attachments++;
        }
        if (!knowledge.equals(recordedKnowledge) || excerpts.isEmpty() != (attachments == 0) || attachments > excerpts.size()) throw integrity();
        return value;
    }
    public static StandardReviewHistory history(StandardReviewHistory value) {
        required(value); snapshot(value.snapshot()); historyChange(value.change(), value.snapshot().revision(), value.snapshot().status(), value.changeReason()); return value;
    }
    public static void historyChange(RecordChange change, long revision, RecordStatus status, String reason) {
        required(change); revision(revision); status(status);
        if (change == RecordChange.CREATED) {
            if (revision != 0 || status != RecordStatus.ACTIVE || reason != null) throw integrity();
        } else {
            text(reason, 2000, true);
            if (revision == 0 || (change == RecordChange.WITHDRAWN) != (status == RecordStatus.WITHDRAWN)) throw integrity();
        }
    }

    public static <T> T readStored(String raw, Class<T> type) {
        try {
            T value = decode(required(raw).getBytes(StandardCharsets.UTF_8), type);
            if (value instanceof StandardReviewSnapshot snapshot) snapshot(snapshot);
            else if (value instanceof StandardReviewHistory history) history(history);
            else if (value instanceof ReviewedObservationDtos.ReviewedObservation snapshot) ReviewedObservationRules.snapshot(snapshot);
            else if (value instanceof ReviewedObservationDtos.ObservationHistory history) ReviewedObservationRules.history(history);
            else throw new IllegalArgumentException("未定义的审阅历史类型");
            return value;
        } catch (RuntimeException exception) { throw new IllegalStateException("审阅历史结构或不可变身份不完整", exception); }
    }
    public static <T> T readResponse(JsonMapper mapper, T value) { return bounded(mapper, value, PAGE_BYTES); }
    public static <T> T history(JsonMapper mapper, T value) { return bounded(mapper, value, HISTORY_BYTES); }
    public static <T> T bounded(JsonMapper mapper, T value, int maximum) {
        required(value); if (mapper.writeValueAsBytes(value).length > maximum) throw limit("审阅响应或历史超过载荷上限。"); return value;
    }
    public static String canonicalJson(Object value) { return STRICT.writeValueAsString(canonical(STRICT.valueToTree(value), null)); }
    public static String hash(String prefix, Object value) {
        return sha256((required(prefix) + canonicalJson(value)).getBytes(StandardCharsets.UTF_8));
    }
    public static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(required(value))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256不可用", exception); }
    }
    private static Object canonical(JsonNode node, String field) {
        if (node.isNull()) return null;
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.properties().forEach(entry -> result.put(entry.getKey(), canonical(entry.getValue(), entry.getKey()))); return result;
        }
        if (node.isArray()) { var result = new ArrayList<Object>(); for (var item : node) result.add(canonical(item, null)); return result; }
        // 只规范协议内时间字段，证据原文即使形似时间也保持原字面值。
        if (node.isTextual()) return field != null && TIME_FIELDS.contains(field) ? MICRO_TIME.format(time(Instant.parse(node.stringValue()))) : node.stringValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber() && node.canConvertToLong()) return node.longValue();
        throw new IllegalStateException("审阅摘要出现未定义数值类型");
    }

    public static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
    public static ApiException limit(String detail) { return new ApiException(422, "REVIEW_RECORD_LIMIT", detail); }
    public static ApiException basisLimit(String detail) { return new ApiException(422, "REVIEW_BASIS_LIMIT", detail); }
    public static ApiException citationInvalid() { return new ApiException(422, "REVIEW_CITATION_INVALID", "引文与确切来源、元素、区间或观察范围不一致。"); }
    public static ApiException conflict(String code, String detail, Long revision) { return new ApiException(409, code, detail, revision); }
    public static IllegalStateException integrity() { return new IllegalStateException("审阅历史结构或不可变身份不一致"); }
}
