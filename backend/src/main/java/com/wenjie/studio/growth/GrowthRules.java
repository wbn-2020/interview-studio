package com.wenjie.studio.growth;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.planning.PlanningRules;
import com.wenjie.studio.review.RecollectionDtos;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.ReviewedObservationDtos;
import com.wenjie.studio.review.StandardReviewDtos;
import com.wenjie.studio.today.TodayDtos;
import com.wenjie.studio.training.TrainingDtos;
import org.springframework.util.MultiValueMap;
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

import java.nio.charset.StandardCharsets;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static com.wenjie.studio.growth.GrowthDtos.*;

/** 公共边界校验不替代原领域归属、当前适用性及资料权限检查。 */
public final class GrowthRules {
    public static final long MAX = 9_007_199_254_740_991L;
    public static final int REQUEST_BYTES = 256 * 1024, HISTORY_BYTES = 32 * 1024;
    public static final int PAGE_BYTES = 1024 * 1024, ACTIVITY_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CANDIDATES = 500, MAX_RESULTS = 50;
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");
    private static final DateTimeFormatter MICRO_TIME = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private static final Set<String> INSTANT_FIELDS = Set.of("asOf", "checkedAt", "serverTime", "issuedAt", "occurredAt",
            "createdAt", "updatedAt", "startAt", "endAt", "observedUntil", "lastExposureAt", "windowStart", "windowEnd");
    private static final Set<String> DATE_FIELDS = Set.of("from", "to", "localDate", "capturedOn", "plannedDate", "observedOn");
    private static final JsonMapper STRICT = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20)
                            .maxStringLength(REQUEST_BYTES).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // Textual 需单独关闭强转，不能把数字或布尔值改写为用户记录的文字。
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private GrowthRules() {}

    public static <T> T decode(byte[] raw, Class<T> type) {
        if (raw == null || raw.length == 0) throw invalid("请求须为完整 JSON 对象。");
        if (raw.length > REQUEST_BYTES) throw limit("成长请求超过 256 KiB。");
        try {
            JsonNode node = STRICT.readTree(raw);
            if (node == null || !node.isObject()) throw invalid("请求须为 JSON 对象。");
            shapes(node);
            return STRICT.treeToValue(node, type);
        } catch (ApiException exception) { throw exception; }
        catch (RuntimeException exception) { throw invalid("请求字段缺失、重复、类型无效或包含未知字段。"); }
    }

    private static void shapes(JsonNode node) {
        if (node.isArray()) { for (JsonNode item : node) shapes(item); return; }
        if (!node.isObject()) return;
        for (var field : node.properties()) {
            JsonNode value = field.getValue();
            if (DATE_FIELDS.contains(field.getKey()) && !value.isNull()) {
                if (!value.isTextual()) throw invalid("日期须为 yyyy-MM-dd 字符串。");
                date(value.stringValue());
            }
            shapes(value);
        }
    }

    public static String uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid("对象标识须为标准小写 UUID。"); }
        return value;
    }
    public static <T> T required(T value) { if (value == null) throw invalid("必填字段不能为空。"); return value; }
    public static long revision(Long value) {
        if (value == null || value < 0 || value > MAX) throw invalid("修订与计数须为非负安全整数。"); return value;
    }
    public static long next(long value) { revision(value); if (value == MAX) throw limit("成长记录修订已达到安全上限。"); return value + 1; }
    public static void nullableRevision(Long value) { if (value != null) revision(value); }
    public static void range(long value, long min, long max, String field) {
        if (value < min || value > max) throw invalid(field + "超出允许范围。");
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
    public static LocalDate date(LocalDate value) { required(value); date(value.toString()); return value; }
    public static String zone(String value) { return PlanningRules.zone(value); }
    public static Instant time(Instant value) {
        if (value == null || value.isBefore(MIN_TIME) || value.isAfter(MAX_TIME) || value.getNano() % 1000 != 0) {
            throw invalid("时间须在 1000 至 9999 年范围内且精确到微秒。");
        }
        return value;
    }
    public static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    public static void clock(Instant now, Instant before) {
        time(now); time(before);
        if (now.isBefore(before)) throw conflict("GROWTH_PREVIEW_CHANGED", "服务器时间早于最近修改，请保留输入后重新读取。", null);
    }

    public static TargetRef target(String id, String versionId) {
        if ((id == null) != (versionId == null)) throw invalid("目标与目标版本须同时提供。");
        return id == null ? null : new TargetRef(uuid(id), uuid(versionId));
    }
    public static TargetRef target(TargetRef value) { required(value); uuid(value.itemId()); uuid(value.versionId()); return value; }
    public static QuestionRef question(QuestionRef value) { required(value); uuid(value.questionId()); uuid(value.versionId()); return value; }

    public static EvidenceRef evidence(EvidenceRef value) {
        required(value); required(value.kind()); uuid(value.objectId());
        boolean version = false, revision = false, part = false;
        switch (value.kind()) {
            case TRAINING_ANSWER, REPLAY_JUDGMENT, MEMORY_RATING -> { }
            case MANUAL_FEEDBACK -> { revision = true; range(revision(value.revision()), 1, 100, "反馈修订"); }
            case AI_OBSERVATION -> {
                part = true;
                if (value.partId() == null || !value.partId().matches("f([1-9]|1[0-2])")) throw invalid("观察身份须为 f1 至 f12。");
            }
            case RECOLLECTION -> { version = true; uuid(value.versionId()); }
            case REVIEWED_OBSERVATION -> { revision = true; revision(value.revision()); }
        }
        if ((!version && value.versionId() != null) || (!revision && value.revision() != null) || (!part && value.partId() != null)) {
            throw invalid("证据身份包含不适用于该类型的字段。");
        }
        return value;
    }
    public static String evidenceKey(EvidenceRef value) {
        evidence(value);
        String result = value.kind() + ":" + value.objectId() + ":" + component(value.versionId()) + ":"
                + (value.revision() == null ? "-" : Long.toString(value.revision())) + ":" + component(value.partId());
        if (result.length() > 192) throw invalid("证据身份键超限。"); return result;
    }
    public static EvidenceRef evidenceKey(String value) {
        if (value == null || value.length() > 192) throw invalid("证据身份键无效。");
        String[] parts = value.split(":", -1);
        if (parts.length != 5) throw invalid("证据身份键无效。");
        var ref = new EvidenceRef(enumValue(parts[0], EvidenceKind.class), parts[1], uncomponent(parts[2]),
                parts[3].equals("-") ? null : integer(parts[3]), uncomponent(parts[4]));
        if (!evidenceKey(ref).equals(value)) throw invalid("证据身份键不是规范编码。"); return ref;
    }
    private static String component(String value) { return value == null ? "-" : value; }
    private static String uncomponent(String value) { return value.equals("-") ? null : value; }

    public static SubjectIdentity identity(SubjectIdentity value) {
        required(value); required(value.kind());
        if (value.kind() == SubjectKind.SOURCE) {
            source(value.source()); if (value.requirement() != null) throw invalid("来源主体不能携带目标要求。");
        } else {
            requirement(value.requirement()); if (value.source() != null) throw invalid("目标要求不能携带来源身份。");
        }
        return value;
    }
    public static SubjectIdentity identity(Subject value) {
        subject(value); return new SubjectIdentity(value.kind(), value.source(), value.requirement());
    }
    public static Subject subject(Subject value) {
        required(value); identity(new SubjectIdentity(value.kind(), value.source(), value.requirement()));
        if (value.kind() == SubjectKind.SOURCE) {
            sourceStamp(value.sourceStamp(), value.source().kind());
            if (value.targetRevision() != null) throw invalid("来源主体不能携带目标修订。");
        } else {
            revision(value.targetRevision());
            if (value.sourceStamp() != null) throw invalid("目标要求不能携带来源修订。");
        }
        return value;
    }
    public static RequirementIdentity requirement(RequirementIdentity value) {
        required(value); target(value.target()); uuid(value.requirementId()); return value;
    }
    public static TodayDtos.SourceRef source(TodayDtos.SourceRef value) {
        required(value); required(value.kind()); uuid(value.id());
        if (value.kind() != TodayDtos.SourceKind.PREPARATION_TASK && value.kind() != TodayDtos.SourceKind.REVIEW_ACTION
                && value.kind() != TodayDtos.SourceKind.MEMORY_CARD) throw invalid("下一步主体只允许任务、动作或回忆卡。");
        return value;
    }
    public static void sourceStamp(TodayDtos.SourceStamp value, TodayDtos.SourceKind kind) {
        required(value); required(kind);
        if (value.kind() != kind || value.knowledge() != null || value.training() != null || value.interview() != null
                || (value.task() == null ? 0 : 1) + (value.action() == null ? 0 : 1) + (value.memory() == null ? 0 : 1) != 1) {
            throw invalid("来源修订须为对应类型的单一完整载荷。");
        }
        switch (kind) {
            case PREPARATION_TASK -> revision(required(value.task()).revision());
            case REVIEW_ACTION -> { var item = required(value.action()); range(item.revision(), 0, 1000, "动作修订"); hashText(item.progressHash()); }
            case MEMORY_CARD -> {
                var item = required(value.memory()); range(item.cardRevision(), 0, 1000, "卡片修订"); uuid(item.cardVersionId());
                nullableRevision(item.gateRevision()); nullableRevision(item.scheduleRevision()); nullableRevision(item.settingsRevision());
                if ((item.attemptId() == null) != (item.attemptRevision() == null)) throw invalid("未决尝试身份与修订须同时提供。");
                if (item.attemptId() != null) { uuid(item.attemptId()); revision(item.attemptRevision()); }
            }
            default -> throw invalid("下一步主体来源类型无效。");
        }
    }
    public static String subjectKey(SubjectIdentity value) {
        identity(value);
        String result = value.kind() == SubjectKind.SOURCE ? value.source().kind() + ":" + value.source().id()
                : value.requirement().target().itemId() + ":" + value.requirement().target().versionId() + ":" + value.requirement().requirementId();
        if (result.length() > 128) throw invalid("建议主体身份键超限。"); return result;
    }

    public static void createPreview(CreateUsePreview value) {
        required(value); target(value.target()); uuid(value.requirementId()); evidence(value.evidence());
    }
    public static void changePreview(ChangeUsePreview value) { required(value); revision(value.expectedRevision()); }
    public static void createUse(CreateUse value) {
        required(value); createPreview(value.request()); hashText(value.expectedPreviewHash());
        comparability(value.comparability(), required(value.coverage())); text(value.reason(), 2000, true); confirmed(value.confirmed());
    }
    public static void changeUse(ChangeUse value) {
        required(value); revision(value.expectedRevision()); hashText(value.expectedPreviewHash()); required(value.status());
        comparability(value.comparability(), required(value.coverage())); text(value.reason(), 2000, true); confirmed(value.confirmed());
    }
    public static void comparability(Comparability value, Coverage coverage) {
        required(value); boolean positive = positive(required(coverage));
        for (ComparisonItem item : new ComparisonItem[]{value.dimension(), value.scenario(), value.technology(), value.assistance(), value.date()}) {
            required(item); required(item.state()); text(item.reason(), 500, positive);
        }
    }
    public static boolean positive(Coverage value) { return value == Coverage.SUPPORTS || value == Coverage.PARTIAL; }
    public static void createPreference(CreatePreference value) { required(value); subject(value.subject()); required(value.visibility()); }
    public static void savePreference(SavePreference value) {
        required(value); revision(value.expectedRevision()); required(value.visibility());
        if (value.expectedSubject() != null) subject(value.expectedSubject());
        else if (value.visibility() != Visibility.VISIBLE) throw invalid("仅恢复显示可使用不可用的旧主体；保存前仍须核对来源。");
    }
    private static void confirmed(Boolean value) { if (!Boolean.TRUE.equals(value)) throw invalid("须明确确认本次个人适用性判断。"); }

    public static void conditions(Conditions value) {
        required(value); required(value.kind());
        int present = (value.answer() == null ? 0 : 1) + (value.manual() == null ? 0 : 1) + (value.ai() == null ? 0 : 1)
                + (value.recollection() == null ? 0 : 1) + (value.replay() == null ? 0 : 1) + (value.memory() == null ? 0 : 1)
                + (value.reviewed() == null ? 0 : 1);
        if (present != 1) throw invalid("证据条件须为对应种类的单一完整载荷。");
        switch (value.kind()) {
            case TRAINING_ANSWER -> answer(value.answer());
            case MANUAL_FEEDBACK -> {
                var item = required(value.manual()); answer(item.answer()); required(item.kind()); required(item.dimension()); required(item.coverage());
                text(item.authorLabel(), 120, item.kind() == ReviewDtos.FeedbackKind.EXTERNAL_FEEDBACK);
                if (item.kind() == ReviewDtos.FeedbackKind.SELF_CHECK && !item.authorLabel().isEmpty()) throw invalid("自检不能伪造外部记录者。");
                decision(item.latestDecision());
            }
            case AI_OBSERVATION -> {
                var item = required(value.ai()); answer(item.answer()); required(item.dimension()); required(item.coverage()); decision(item.latestDecision());
                if (item.standard() != null) { question(item.standard().question()); required(item.standard().status()); }
            }
            case RECOLLECTION -> {
                var item = required(value.recollection()); range(item.versionNumber(), 1, 1000, "回忆版本号"); required(item.baselineKind());
                // 成长摘要沿用原回忆的不确定性与旧答基线约束，不能把缺失片段解释为可比较的旧答。
                if (!item.hasUncertainty() || item.hasAnswerFragment() != (item.baselineKind() == RecollectionDtos.BaselineKind.RECOLLECTION)) {
                    throw invalid("回忆摘要的不确定性或旧答基线不完整。");
                }
                if (item.interview() != null) {
                    var event = item.interview(); uuid(event.eventId()); revision(event.revision()); time(event.startAt()); zone(event.zoneId());
                    if (event.target() != null) target(event.target());
                }
            }
            case REPLAY_JUDGMENT -> {
                var item = required(value.replay()); uuid(item.sourceId()); uuid(item.actionId()); uuid(item.comparisonId()); answer(item.answer());
                range(item.judgmentRevision(), 1, 1000, "复练判断修订"); required(item.questionMode()); required(item.baselineKind()); required(item.outcome());
                hashText(item.recordedVerificationHash()); if (item.currentVerificationHash() != null) hashText(item.currentVerificationHash());
                if (item.currentlyApplicable() && item.currentVerificationHash() == null) throw invalid("适用判断不能缺少当前验证依据。");
            }
            case MEMORY_RATING -> memory(value.memory());
            case REVIEWED_OBSERVATION -> reviewed(value.reviewed());
        }
    }
    private static void answer(AnswerMeta value) {
        required(value); uuid(value.sessionId()); uuid(value.questionId()); uuid(value.answerId());
        required(value.mode()); required(value.questionKind()); required(value.condition()); revision((long) value.assistanceEventSequence());
        var exposure = required(value.priorExposure()); revision(exposure.authoredCount()); revision(exposure.referenceViewCount()); revision(exposure.evaluationAccessCount());
        if (exposure.lastExposureAt() != null) time(exposure.lastExposureAt());
        if (value.sourceQuestion() != null) question(value.sourceQuestion());
        if (value.repractice() != null) { uuid(value.repractice().id()); required(value.repractice().questionMode()); required(value.repractice().baselineKind()); }
        required(value.recordedFields());
    }
    private static void decision(DecisionMeta value) {
        if (value == null) return; uuid(value.id()); range(value.revision(), 1, 20, "反馈决定修订"); required(value.choice()); time(value.createdAt());
    }
    private static void reviewed(ReviewedMeta value) {
        required(value); answer(value.answer()); question(value.question()); uuid(value.checkpointId());
        var standard = required(value.standardReview()); uuid(standard.id()); revision(standard.revision());
        required(value.change()); required(value.recordedStatus()); time(value.recordedAt());
        date(value.observedOn()); zone(value.observationZoneId()); range(value.answerNumber(), 1, 100, "原答提交序号");
        time(value.answerCreatedAt()); required(value.coverage()); required(value.conclusion());
        if (!value.question().equals(value.answer().sourceQuestion())
                || value.answer().questionKind() != TrainingDtos.QuestionKind.MAIN && value.answer().questionKind() != TrainingDtos.QuestionKind.REPLAY) {
            throw invalid("受审观察须指向该题实例自身的确切题库版本。");
        }
        ZoneId zone = ZoneId.of(value.observationZoneId());
        if (value.answerCreatedAt().isAfter(value.recordedAt())
                || value.observedOn().isBefore(value.answerCreatedAt().atZone(zone).toLocalDate())
                || value.observedOn().isAfter(value.recordedAt().atZone(zone).toLocalDate())) throw invalid("受审观察的记录时间、声明日期与原答时间不一致。");
        if (value.coverage() == AiDtos.EvaluationCoverage.NOT_MENTIONED && value.conclusion() != ReviewedObservationDtos.ObservationConclusion.INSUFFICIENT
                || value.coverage() == AiDtos.EvaluationCoverage.UNCERTAIN && value.conclusion() != ReviewedObservationDtos.ObservationConclusion.INSUFFICIENT
                && value.conclusion() != ReviewedObservationDtos.ObservationConclusion.DISPUTED) throw invalid("受审观察范围与结论不一致。");
        if ((value.change() == StandardReviewDtos.RecordChange.WITHDRAWN) != (value.recordedStatus() == StandardReviewDtos.RecordStatus.WITHDRAWN)) {
            throw invalid("受审观察修订事件与当时状态不一致。");
        }
        var eligibility = required(value.eligibility());
        revision(eligibility.currentRevision()); required(eligibility.currentStatus());
        revision(eligibility.standardCurrentRevision()); required(eligibility.standardCurrentStatus());
        orderedEnums(eligibility.reasons()); orderedEnums(eligibility.limitations());
        if (eligibility.lineageCheck() != StandardReviewDtos.LineageCheck.PARTIAL
                || eligibility.eligibleAsReviewedObservation() != eligibility.reasons().isEmpty()
                || eligibility.standardCurrentRevision() < standard.revision()) throw invalid("受审观察资格或确切标准修订不一致。");
        for (var limitation : List.of(StandardReviewDtos.ReviewLimitation.USER_RECORDED_REVIEW,
                StandardReviewDtos.ReviewLimitation.REVIEWER_IDENTITY_NOT_VERIFIED,
                StandardReviewDtos.ReviewLimitation.TECHNICAL_SEMANTICS_NOT_VERIFIED,
                StandardReviewDtos.ReviewLimitation.DIRECT_DEPENDENCIES_ONLY)) {
            if (!eligibility.limitations().contains(limitation)) throw invalid("受审观察缺少必要证据限制。");
        }
        reviewedReason(eligibility, StandardReviewDtos.EligibilityReason.RECORD_REVISED, !eligibility.revisionCurrent());
        reviewedReason(eligibility, StandardReviewDtos.EligibilityReason.RECORD_WITHDRAWN, eligibility.currentStatus() == StandardReviewDtos.RecordStatus.WITHDRAWN);
        reviewedReason(eligibility, StandardReviewDtos.EligibilityReason.STANDARD_REVISED, eligibility.standardCurrentRevision() != standard.revision());
        reviewedReason(eligibility, StandardReviewDtos.EligibilityReason.STANDARD_WITHDRAWN, eligibility.standardCurrentStatus() == StandardReviewDtos.RecordStatus.WITHDRAWN);
        reviewedReason(eligibility, StandardReviewDtos.EligibilityReason.OBSERVATION_DISPUTED, value.conclusion() == ReviewedObservationDtos.ObservationConclusion.DISPUTED);
    }
    private static void reviewedReason(ReviewedObservationDtos.ObservationEligibility value, StandardReviewDtos.EligibilityReason reason, boolean expected) {
        if (value.reasons().contains(reason) != expected) throw invalid("受审观察资格原因与当前状态不一致。");
    }
    private static <E extends Enum<E>> void orderedEnums(List<E> values) {
        required(values); int previous = -1;
        for (E value : values) {
            if (required(value).ordinal() <= previous) throw invalid("枚举集合须按声明顺序且不能重复。");
            previous = value.ordinal();
        }
    }
    private static void memory(MemoryMeta value) {
        required(value); uuid(value.cardId()); uuid(value.cardVersionId()); uuid(value.attemptId()); range(value.attemptSequence(), 1, MAX, "回忆尝试序号");
        required(value.mode()); required(value.rating()); var day = required(value.ratingDay()); zone(day.zoneId()); date(day.localDate());
        time(day.windowStart()); time(day.windowEnd()); nullableRevision(day.settingsRevision());
        if (!day.windowStart().isBefore(day.windowEnd())) throw invalid("原评分日窗口无效。");
        revision(value.thinkingElapsedMs()); revision(value.totalElapsedMs());
        if (value.thinkingElapsedMs() > value.totalElapsedMs()) throw invalid("回忆经过时长不一致。");
        if (value.countRegular()) {
            var budget = required(value.budget()); required(budget.kind()); uuid(budget.firstCountedEventId());
            uuid(value.scheduleChangeId()); range(revision(value.scheduleSequence()), 1, MAX, "原排程变更序号");
        } else if (value.budget() != null || value.scheduleChangeId() != null || value.scheduleSequence() != null) {
            throw invalid("未计入常规预算的评分不能附带预算或排程变更依据。");
        }
    }
    public static void state(EvidenceState value) {
        required(value); required(value.directState()); required(value.recordCurrency()); required(value.lineageCheck());
        required(value.reviewFlags()); if (value.reviewFlags().size() != new HashSet<>(value.reviewFlags()).size()
                || value.reviewFlags().stream().anyMatch(Objects::isNull)) throw invalid("复核标记不能重复或为空。");
        if (value.lineageCheck() == LineageCheck.COMPLETE) throw invalid("当前成长实现不能声明间接依赖已全部检查。");
        if (value.directState() == DirectState.AVAILABLE && !value.reviewFlags().isEmpty()
                || value.directState() == DirectState.UNAVAILABLE && !value.reviewFlags().contains(ReviewFlag.SOURCE_UNAVAILABLE)
                || value.reviewFlags().contains(ReviewFlag.SOURCE_UNAVAILABLE) && value.directState() != DirectState.UNAVAILABLE) {
            throw invalid("证据直接状态与复核标记不一致。");
        }
    }
    public static void stamp(EvidenceStamp value) {
        required(value); evidence(value.ref()); if (value.originHash() != null) hashText(value.originHash()); hashText(value.currentHash());
    }
    private static void facets(Facets value, EvidenceKind kind) {
        required(value); required(value.targets()); required(value.topics()); required(value.dimensions());
        range(value.targets().size(), 0, kind == EvidenceKind.RECOLLECTION ? 1 : 12, "原目标数量");
        range(value.topics().size(), 0, 20, "主题数量"); range(value.dimensions().size(), 0, 6, "考察维度数量");
        Set<TargetRef> targets = new HashSet<>(); Set<String> topics = new HashSet<>(); Set<QuestionDtos.Dimension> dimensions = new HashSet<>();
        int currentTopics = 0;
        for (var item : value.targets()) {
            required(item); target(item.target());
            if (item.basis() != FacetBasis.RECORDED || !targets.add(item.target())) throw invalid("目标分类须为互不重复的原记录关系。");
        }
        for (var item : value.topics()) {
            required(item); uuid(item.topicId()); required(item.basis());
            if (item.basis() == FacetBasis.USER_LINK || !topics.add(item.topicId())) throw invalid("主题分类依据无效或重复。");
            if (item.basis() == FacetBasis.CURRENT_CLASSIFICATION) currentTopics++;
        }
        if (currentTopics > 1 || currentTopics > 0 && kind != EvidenceKind.MEMORY_RATING
                || kind == EvidenceKind.MEMORY_RATING && currentTopics != value.topics().size()) throw invalid("当前主题分类只能表示本次评分主题匹配。");
        for (var item : value.dimensions()) {
            required(item); required(item.dimension());
            if (item.basis() != FacetBasis.RECORDED || !dimensions.add(item.dimension())) throw invalid("考察维度须来自互不重复的原记录。");
        }
        if ((kind == EvidenceKind.RECOLLECTION || kind == EvidenceKind.MEMORY_RATING) && !dimensions.isEmpty()) throw invalid("回忆和评分不能推造考察维度。");
        if (kind == EvidenceKind.MEMORY_RATING && !targets.isEmpty()) throw invalid("评分没有原生目标关系，不能从后来的复用关系回填。");
    }
    private static AnswerMeta answerOf(Conditions value) {
        return switch (value.kind()) {
            case TRAINING_ANSWER -> value.answer();
            case MANUAL_FEEDBACK -> value.manual().answer();
            case AI_OBSERVATION -> value.ai().answer();
            case REPLAY_JUDGMENT -> value.replay().answer();
            case REVIEWED_OBSERVATION -> value.reviewed().answer();
            default -> null;
        };
    }
    private static void route(EvidenceRoute route, EvidenceRef ref, Conditions conditions) {
        required(route); required(route.route());
        if (!ref.equals(route.ref())) throw invalid("原域入口与证据身份不一致。");
        EvidenceRouteKind expected = switch (ref.kind()) {
            case TRAINING_ANSWER -> EvidenceRouteKind.TRAINING_ANSWER;
            case MANUAL_FEEDBACK -> EvidenceRouteKind.MANUAL_FEEDBACK;
            case AI_OBSERVATION -> EvidenceRouteKind.AI_OBSERVATION;
            case RECOLLECTION -> EvidenceRouteKind.RECOLLECTION_VERSION;
            case REPLAY_JUDGMENT -> EvidenceRouteKind.REPLAY_COMPARISON;
            case MEMORY_RATING -> EvidenceRouteKind.MEMORY_ATTEMPT;
            case REVIEWED_OBSERVATION -> EvidenceRouteKind.REVIEWED_OBSERVATION;
        };
        if (route.route() != expected) throw invalid("原域入口类型不一致。");
        var answer = answerOf(conditions);
        if (answer != null) {
            if (!answer.sessionId().equals(route.sessionId()) || !answer.questionId().equals(route.questionId()) || !answer.answerId().equals(route.answerId())) {
                throw invalid("原域入口不能指向其他回答。");
            }
            if (route.cardId() != null || route.attemptId() != null) throw invalid("回答入口不能携带评分身份。");
            if (ref.kind() == EvidenceKind.REPLAY_JUDGMENT) {
                if (!conditions.replay().comparisonId().equals(route.comparisonId())) throw invalid("复练对照身份不一致。");
            } else if (route.comparisonId() != null) throw invalid("该证据不是复练对照入口。");
        } else {
            if (route.sessionId() != null || route.questionId() != null || route.answerId() != null || route.comparisonId() != null) throw invalid("该入口不能携带回答身份。");
            if (ref.kind() == EvidenceKind.MEMORY_RATING) {
                if (!conditions.memory().cardId().equals(route.cardId()) || !conditions.memory().attemptId().equals(route.attemptId())) throw invalid("原评分入口身份不一致。");
            } else if (route.cardId() != null || route.attemptId() != null) throw invalid("回忆版本不能携带评分身份。");
        }
    }
    public static EvidenceSummary summary(EvidenceSummary value) {
        try {
            required(value); evidence(value.ref()); time(value.occurredAt()); text(value.title(), 200, true);
            required(value.recordKind()); required(value.assessmentLevel()); conditions(value.conditions());
            if (value.conditions().kind() != value.ref().kind()) throw invalid("证据条件与身份类型不一致。");
            facets(value.facets(), value.ref().kind()); state(value.state()); stamp(value.stamp());
            if (!value.ref().equals(value.stamp().ref()) || value.stamp().originHash() == null) throw invalid("可读证据不能缺少原始摘要。");
            AssessmentLevel level = switch (value.ref().kind()) {
                case AI_OBSERVATION -> AssessmentLevel.AI_UNREVIEWED;
                case REVIEWED_OBSERVATION -> AssessmentLevel.REVIEWED_OBSERVATION;
                default -> AssessmentLevel.SELF_RECORDED;
            };
            RecordKind record = switch (value.ref().kind()) {
                case TRAINING_ANSWER -> RecordKind.ANSWER;
                case MANUAL_FEEDBACK -> value.conditions().manual().kind() == ReviewDtos.FeedbackKind.SELF_CHECK ? RecordKind.SELF_CHECK : RecordKind.EXTERNAL_FEEDBACK;
                case AI_OBSERVATION -> RecordKind.AI_FEEDBACK;
                case RECOLLECTION -> RecordKind.INTERVIEW_RECOLLECTION;
                case REPLAY_JUDGMENT -> RecordKind.REPLAY_ASSESSMENT;
                case MEMORY_RATING -> RecordKind.MEMORY_SELF_RATING;
                case REVIEWED_OBSERVATION -> RecordKind.REVIEWED_CHECK;
            };
            if (value.assessmentLevel() != level || value.recordKind() != record) throw invalid("证据记录不能升级为另一评价层级。");
            if (value.ref().kind() == EvidenceKind.TRAINING_ANSWER && !value.ref().objectId().equals(value.conditions().answer().answerId())) throw invalid("原答身份不一致。");
            if (value.ref().kind() == EvidenceKind.REVIEWED_OBSERVATION) reviewedSummary(value);
            route(value.route(), value.ref(), value.conditions()); return value;
        } catch (ApiException exception) { throw integrity(); }
    }
    private static void reviewedSummary(EvidenceSummary value) {
        var meta = value.conditions().reviewed(); var eligibility = meta.eligibility(); long recordedRevision = value.ref().revision();
        if (!value.occurredAt().equals(meta.recordedAt()) || eligibility.currentRevision() < recordedRevision
                || eligibility.revisionCurrent() != (eligibility.currentRevision() == recordedRevision)
                || (meta.change() == StandardReviewDtos.RecordChange.CREATED) != (recordedRevision == 0)) {
            throw invalid("受审观察目录身份、修订事件或发生时间不一致。");
        }
        if (eligibility.revisionCurrent() && meta.recordedStatus() != eligibility.currentStatus()
                || meta.recordedStatus() == StandardReviewDtos.RecordStatus.WITHDRAWN && !eligibility.revisionCurrent()) {
            throw invalid("受审观察终态或当时状态与当前修订不一致。");
        }
        RecordCurrency currency = eligibility.revisionCurrent() ? RecordCurrency.CURRENT : RecordCurrency.SUPERSEDED;
        if (value.state().recordCurrency() != currency || value.state().lineageCheck() != LineageCheck.PARTIAL) {
            throw invalid("受审观察的新旧修订与依赖检查状态不一致。");
        }
        orderedEnums(value.state().reviewFlags());
        // 原答依赖可追加标记；受审链自身的失效原因必须同时反映到成长状态。
        for (var reason : eligibility.reasons()) {
            ReviewFlag flag = switch (reason) {
                case OBSERVATION_DISPUTED -> ReviewFlag.DISPUTED;
                case BASIS_UNAVAILABLE -> ReviewFlag.SOURCE_UNAVAILABLE;
                default -> ReviewFlag.REVIEW_REQUIRED;
            };
            if (!value.state().reviewFlags().contains(flag)) throw invalid("成长状态缺少受审链的当前失效标记。");
        }
        if ((!eligibility.eligibleAsReviewedObservation() || value.state().reviewFlags().contains(ReviewFlag.DISPUTED))
                && value.state().directState() == DirectState.AVAILABLE) throw invalid("失效或争议观察不能声明直接可用。");
    }
    public static EvidenceResolution resolution(EvidenceResolution value) {
        try {
            required(value); evidence(value.ref()); state(value.state()); stamp(value.stamp());
            if (!value.ref().equals(value.stamp().ref())) throw invalid("解析摘要与证据身份不一致。");
            if (value.summary() == null) {
                if (value.state().directState() != DirectState.UNAVAILABLE || value.stamp().originHash() != null) throw invalid("不可读旧证据的状态不完整。");
            } else {
                summary(value.summary());
                if (!value.ref().equals(value.summary().ref()) || !value.state().equals(value.summary().state()) || !value.stamp().equals(value.summary().stamp())) {
                    throw invalid("证据解析与摘要不一致。");
                }
            }
            return value;
        } catch (ApiException exception) { throw integrity(); }
    }
    public static void storedUse(EvidenceUse value) {
        try {
            required(value); uuid(value.id()); revision(value.revision()); target(value.target()); uuid(value.requirementId()); evidence(value.evidence());
            required(value.status()); comparability(value.comparability(), required(value.coverage())); text(value.reason(), 2000, true);
            time(value.createdAt()); time(value.updatedAt()); if (value.updatedAt().isBefore(value.createdAt())) throw invalid("记录时钟倒退。");
        } catch (ApiException exception) { throw integrity(); }
    }
    public static void storedPreference(NextStepPreference value) {
        try {
            required(value); uuid(value.id()); revision(value.revision()); identity(value.subject()); required(value.visibility());
            time(value.createdAt()); time(value.updatedAt()); if (value.updatedAt().isBefore(value.createdAt())) throw invalid("记录时钟倒退。");
        } catch (ApiException exception) { throw integrity(); }
    }
    public static TargetMeta targetMeta(TargetMeta value) {
        try {
            // 采集日是固定材料的 LocalDate 元数据，不套用安排日期范围；历史标题保留准备域原文。
            required(value); target(value.ref()); preparationText(value.title(), 200);
            revision(value.itemRevision()); required(value.status()); uuid(value.currentVersionId()); revision(value.sourceGeneration()); return value;
        } catch (ApiException exception) { throw integrity(); }
    }
    private static void preparationText(String value, int max) {
        // 老版本按 UTF-16 长度和 Java isBlank 接受文字，不能在只读投影中清洗 NUL 或改用新输入规则。
        if (value == null || value.length() > max || value.isBlank()) throw invalid("准备材料原文字段为空或超出来源长度上限。");
    }
    public static RequirementMeta requirementMeta(RequirementMeta value) {
        try {
            required(value); uuid(value.id()); range(value.ordinal(), 1, 50, "要求序号"); preparationText(value.text(), 2000);
            required(value.dimensions()); range(value.dimensions().size(), 1, 6, "要求维度数量");
            Set<QuestionDtos.Dimension> dimensions = new HashSet<>();
            for (String dimension : value.dimensions()) if (!dimensions.add(required(enumValue(dimension, QuestionDtos.Dimension.class)))) throw invalid("要求维度重复。");
            range(value.priority(), 1, 5, "要求优先级"); return value;
        } catch (ApiException exception) { throw integrity(); }
    }
    public static NextStep nextStep(NextStep value) {
        try {
            required(value); subject(value.subject());
            if (value.subject().kind() == SubjectKind.TARGET_REQUIREMENT) {
                // 要求标题由固定岗位标题派生，沿用原文字符；现有展示截断仍按码点计数。
                if (value.title() == null || value.title().isBlank() || value.title().codePointCount(0, value.title().length()) > 200) {
                    throw invalid("岗位要求建议标题为空或超出长度上限。");
                }
            } else text(value.title(), 200, true);
            required(value.reasonCodes());
            if (value.reasonCodes().isEmpty() || value.reasonCodes().stream().anyMatch(Objects::isNull)
                    || new HashSet<>(value.reasonCodes()).size() != value.reasonCodes().size()) throw invalid("建议须保留不同的已核对原因。");
            range(value.effectivePriority(), 1, 5, "建议优先级"); required(value.priorityBasis());
            if ((value.preferenceId() == null) != (value.preferenceRevision() == null)) throw invalid("偏好身份与修订须同时存在。");
            if (value.preferenceId() != null) { uuid(value.preferenceId()); revision(value.preferenceRevision()); }
            var continuation = required(value.continuation());
            if (value.subject().kind() == SubjectKind.SOURCE) {
                if (value.priorityBasis() == PriorityBasis.TARGET_REQUIREMENT || continuation.route() != NextStepRoute.SOURCE
                        || continuation.source() == null || continuation.requirement() != null) throw invalid("来源建议入口不一致。");
            } else if (value.priorityBasis() != PriorityBasis.TARGET_REQUIREMENT || continuation.route() != NextStepRoute.TARGET_REQUIREMENT
                    || continuation.source() != null || !value.subject().requirement().equals(continuation.requirement())) throw invalid("目标要求建议入口不一致。");
            return value;
        } catch (ApiException exception) { throw integrity(); }
    }

    public static void parameters(MultiValueMap<String, String> query, Set<String> allowed) {
        required(query);
        for (var entry : query.entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().size() != 1) throw invalid("查询参数未知或重复。");
            String value = entry.getValue().getFirst();
            if (value == null || value.isBlank() || value.equals("null")) throw invalid("查询参数不能使用空值或字面 null。");
        }
    }
    public static <T extends Enum<T>> T enumValue(String value, Class<T> type) {
        if (value == null) return null;
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException exception) { throw invalid("枚举筛选值无效。"); }
    }
    private static <T> T defaulted(T value, T fallback) { return value == null ? fallback : value; }
    public static Long integer(String value) {
        if (value == null) return null;
        if (!value.matches("0|[1-9][0-9]{0,15}")) throw invalid("数字须为规范的非负安全整数。");
        try { return revision(Long.parseLong(value)); } catch (NumberFormatException exception) { throw invalid("数字超出安全整数范围。"); }
    }
    public static int pageSize(Integer value) { int actual = value == null ? 20 : value; range(actual, 1, 50, "每页数量"); return actual; }
    public static int nextStepSize(Integer value) { int actual = value == null ? 10 : value; range(actual, 1, 20, "建议数量"); return actual; }
    private static Integer integerSize(String value, int fallback, int max) {
        if (value == null) return fallback; long parsed = integer(value); range(parsed, 1, max, "每页数量"); return (int) parsed;
    }
    private static LocalDate optionalDate(String value) { return value == null ? null : date(value); }
    private static String optionalId(String value) { return value == null ? null : uuid(value); }
    private static void dates(LocalDate from, LocalDate to) {
        if ((from == null) != (to == null)) throw invalid("日期范围须同时提供起止日期。");
        if (from != null) { date(from); date(to); range(ChronoUnit.DAYS.between(from, to), 0, 365, "日期窗口天数"); }
    }

    public static OverviewQuery overviewQuery(MultiValueMap<String, String> query) {
        parameters(query, Set.of("from", "to", "zoneId", "targetId", "targetVersionId", "targetMode", "topicId", "topicMode", "dimension"));
        var value = new OverviewQuery(optionalDate(query.getFirst("from")), optionalDate(query.getFirst("to")), zone(query.getFirst("zoneId")),
                optionalId(query.getFirst("targetId")), optionalId(query.getFirst("targetVersionId")),
                defaulted(enumValue(query.getFirst("targetMode"), TargetMode.class), TargetMode.INCLUDE_ACTIVE_LINKS), optionalId(query.getFirst("topicId")),
                defaulted(enumValue(query.getFirst("topicMode"), TopicMode.class), TopicMode.INCLUDE_CURRENT_CLASSIFICATION), enumValue(query.getFirst("dimension"), QuestionDtos.Dimension.class));
        dates(value.from(), value.to()); target(value.targetId(), value.targetVersionId()); return value;
    }
    public static EvidenceQuery evidenceQuery(MultiValueMap<String, String> query) {
        parameters(query, Set.of("from", "to", "zoneId", "kind", "targetId", "targetVersionId", "targetMode", "topicId", "topicMode", "dimension", "level", "reviewFlag", "cursor", "limit"));
        var value = new EvidenceQuery(optionalDate(query.getFirst("from")), optionalDate(query.getFirst("to")), zone(query.getFirst("zoneId")),
                required(enumValue(query.getFirst("kind"), EvidenceKind.class)), optionalId(query.getFirst("targetId")), optionalId(query.getFirst("targetVersionId")),
                defaulted(enumValue(query.getFirst("targetMode"), TargetMode.class), TargetMode.INCLUDE_ACTIVE_LINKS), optionalId(query.getFirst("topicId")),
                defaulted(enumValue(query.getFirst("topicMode"), TopicMode.class), TopicMode.INCLUDE_CURRENT_CLASSIFICATION), enumValue(query.getFirst("dimension"), QuestionDtos.Dimension.class),
                enumValue(query.getFirst("level"), AssessmentLevel.class), enumValue(query.getFirst("reviewFlag"), ReviewFlag.class), query.getFirst("cursor"), integerSize(query.getFirst("limit"), 20, 50));
        dates(value.from(), value.to()); target(value.targetId(), value.targetVersionId()); return value;
    }
    public static EvidenceRef evidenceQueryRef(MultiValueMap<String, String> query) {
        parameters(query, Set.of("kind", "objectId", "versionId", "revision", "partId"));
        return evidence(new EvidenceRef(required(enumValue(query.getFirst("kind"), EvidenceKind.class)), query.getFirst("objectId"),
                query.getFirst("versionId"), integer(query.getFirst("revision")), query.getFirst("partId")));
    }
    public static ActivityQuery activityQuery(MultiValueMap<String, String> query) {
        parameters(query, Set.of("from", "to", "zoneId", "targetId", "targetVersionId", "topicId", "topicMode", "dimension", "grain"));
        var value = new ActivityQuery(optionalDate(query.getFirst("from")), optionalDate(query.getFirst("to")), zone(query.getFirst("zoneId")),
                optionalId(query.getFirst("targetId")), optionalId(query.getFirst("targetVersionId")), optionalId(query.getFirst("topicId")),
                defaulted(enumValue(query.getFirst("topicMode"), TopicMode.class), TopicMode.INCLUDE_CURRENT_CLASSIFICATION),
                enumValue(query.getFirst("dimension"), QuestionDtos.Dimension.class), defaulted(enumValue(query.getFirst("grain"), Grain.class), Grain.DAY));
        dates(value.from(), value.to()); target(value.targetId(), value.targetVersionId()); return value;
    }
    public static SourceProgressQuery sourceProgressQuery(MultiValueMap<String, String> query) {
        parameters(query, Set.of("targetId", "targetVersionId", "topicId", "topicMode", "dimension", "disposition", "cursor", "limit"));
        var value = new SourceProgressQuery(optionalId(query.getFirst("targetId")), optionalId(query.getFirst("targetVersionId")), optionalId(query.getFirst("topicId")),
                defaulted(enumValue(query.getFirst("topicMode"), TopicMode.class), TopicMode.INCLUDE_CURRENT_CLASSIFICATION), enumValue(query.getFirst("dimension"), QuestionDtos.Dimension.class),
                enumValue(query.getFirst("disposition"), SourceDisposition.class), query.getFirst("cursor"), integerSize(query.getFirst("limit"), 20, 50));
        target(value.targetId(), value.targetVersionId()); return value;
    }
    public static UseIdentityQuery useIdentityQuery(MultiValueMap<String, String> query) {
        parameters(query, Set.of("targetId", "targetVersionId", "requirementId", "kind", "objectId", "versionId", "revision", "partId"));
        var value = new UseIdentityQuery(uuid(query.getFirst("targetId")), uuid(query.getFirst("targetVersionId")), uuid(query.getFirst("requirementId")),
                required(enumValue(query.getFirst("kind"), EvidenceKind.class)), uuid(query.getFirst("objectId")),
                query.getFirst("versionId"), integer(query.getFirst("revision")), query.getFirst("partId"));
        evidence(new EvidenceRef(value.kind(), value.objectId(), value.versionId(), value.revision(), value.partId())); return value;
    }
    public static UseDirectoryQuery useDirectoryQuery(MultiValueMap<String, String> query) {
        parameters(query, Set.of("targetId", "targetVersionId", "requirementId", "status", "cursor", "limit"));
        return new UseDirectoryQuery(uuid(query.getFirst("targetId")), uuid(query.getFirst("targetVersionId")), optionalId(query.getFirst("requirementId")),
                enumValue(query.getFirst("status"), UseStatus.class), query.getFirst("cursor"), integerSize(query.getFirst("limit"), 20, 50));
    }
    public static PreferenceSubjectQuery preferenceSubjectQuery(MultiValueMap<String, String> query) {
        parameters(query, Set.of("subjectKind", "sourceKind", "sourceId", "targetId", "targetVersionId", "requirementId"));
        var value = new PreferenceSubjectQuery(required(enumValue(query.getFirst("subjectKind"), SubjectKind.class)), enumValue(query.getFirst("sourceKind"), TodayDtos.SourceKind.class),
                optionalId(query.getFirst("sourceId")), optionalId(query.getFirst("targetId")), optionalId(query.getFirst("targetVersionId")), optionalId(query.getFirst("requirementId")));
        if (value.subjectKind() == SubjectKind.SOURCE) {
            source(new TodayDtos.SourceRef(value.sourceKind(), value.sourceId()));
            if (value.targetId() != null || value.targetVersionId() != null || value.requirementId() != null) throw invalid("来源主体不能提供目标字段。");
        } else {
            target(new TargetRef(value.targetId(), value.targetVersionId())); uuid(value.requirementId());
            if (value.sourceKind() != null || value.sourceId() != null) throw invalid("目标要求主体不能提供来源字段。");
        }
        return value;
    }
    public static PreferenceQuery preferenceQuery(MultiValueMap<String, String> query) {
        parameters(query, Set.of("visibility", "subjectKind", "cursor", "limit"));
        return new PreferenceQuery(enumValue(query.getFirst("visibility"), Visibility.class), enumValue(query.getFirst("subjectKind"), SubjectKind.class),
                query.getFirst("cursor"), integerSize(query.getFirst("limit"), 20, 50));
    }
    public static NextStepsQuery nextStepsQuery(MultiValueMap<String, String> query) {
        parameters(query, Set.of("zoneId", "targetId", "targetVersionId", "limit"));
        var value = new NextStepsQuery(zone(query.getFirst("zoneId")), optionalId(query.getFirst("targetId")), optionalId(query.getFirst("targetVersionId")), integerSize(query.getFirst("limit"), 10, 20));
        target(value.targetId(), value.targetVersionId()); return value;
    }
    public static TargetRef targetQuery(MultiValueMap<String, String> query) {
        parameters(query, Set.of("targetId", "targetVersionId")); return target(new TargetRef(query.getFirst("targetId"), query.getFirst("targetVersionId")));
    }
    public record HistoryQuery(Long before, int limit) {}
    public static HistoryQuery historyQuery(MultiValueMap<String, String> query) {
        parameters(query, Set.of("before", "limit")); return new HistoryQuery(integer(query.getFirst("before")), integerSize(query.getFirst("limit"), 20, 50));
    }

    public static DateWindow window(LocalDate from, LocalDate to, String zoneId, Instant asOf) {
        zone(zoneId); time(asOf); dates(from, to);
        try {
            ZoneId zone = ZoneId.of(zoneId);
            LocalDate actualTo = to == null ? asOf.atZone(zone).toLocalDate() : to;
            LocalDate actualFrom = from == null ? actualTo.minusDays(27) : from;
            date(actualFrom); date(actualTo); date(actualTo.plusDays(1));
            Instant start = time(actualFrom.atStartOfDay(zone).toInstant());
            Instant end = time(actualTo.plusDays(1).atStartOfDay(zone).toInstant());
            if (!start.isBefore(end)) throw invalid("日期窗口没有有效时间范围。");
            return new DateWindow(actualFrom, actualTo, zoneId, start, end, end.isBefore(asOf) ? end : asOf);
        } catch (DateTimeException | ArithmeticException exception) { throw invalid("日期窗口超出可保存范围。"); }
    }
    public static Filter filter(String targetId, String targetVersionId, TargetMode targetMode, String topicId,
                                TopicMode topicMode, QuestionDtos.Dimension dimension) {
        return new Filter(target(targetId, targetVersionId), defaulted(targetMode, TargetMode.INCLUDE_ACTIVE_LINKS), optionalId(topicId),
                defaulted(topicMode, TopicMode.INCLUDE_CURRENT_CLASSIFICATION), dimension);
    }
    public static Scope scope(OverviewQuery query, Instant asOf) {
        required(query); return new Scope(window(query.from(), query.to(), query.zoneId(), asOf),
                filter(query.targetId(), query.targetVersionId(), query.targetMode(), query.topicId(), query.topicMode(), query.dimension()));
    }
    public static Scope scope(EvidenceQuery query, Instant asOf) {
        required(query); required(query.kind()); pageSize(query.limit());
        return new Scope(window(query.from(), query.to(), query.zoneId(), asOf),
                filter(query.targetId(), query.targetVersionId(), query.targetMode(), query.topicId(), query.topicMode(), query.dimension()));
    }
    public static Scope scope(ActivityQuery query, Instant asOf) {
        required(query); return new Scope(window(query.from(), query.to(), query.zoneId(), asOf),
                filter(query.targetId(), query.targetVersionId(), TargetMode.RECORDED_ONLY, query.topicId(), query.topicMode(), query.dimension()));
    }
    public static ProgressFilter progressFilter(SourceProgressQuery query) {
        required(query); pageSize(query.limit());
        return new ProgressFilter(target(query.targetId(), query.targetVersionId()), optionalId(query.topicId()),
                defaulted(query.topicMode(), TopicMode.INCLUDE_CURRENT_CLASSIFICATION), query.dimension());
    }
    public static void scope(Scope value) {
        required(value); var window = required(value.window()); dates(window.from(), window.to()); zone(window.zoneId());
        time(window.startAt()); time(window.endAt()); time(window.observedUntil());
        if (!window.startAt().isBefore(window.endAt()) || window.observedUntil().isAfter(window.endAt())) throw invalid("日期窗口边界无效。");
        DateWindow expected = window(window.from(), window.to(), window.zoneId(), window.observedUntil());
        if (!expected.equals(window)) throw invalid("日期窗口与区域边界不一致。");
        var filter = required(value.filter()); if (filter.target() != null) target(filter.target());
        required(filter.targetMode()); required(filter.topicMode()); optionalId(filter.topicId());
    }
    public static void budget(QueryBudget value) {
        required(value); range(value.candidateLimit(), 1, MAX_CANDIDATES, "候选预算"); range(value.resultLimit(), 1, MAX_RESULTS, "结果上限");
    }
    public static void references(List<EvidenceRef> values) {
        required(values); range(values.size(), 1, 50, "证据解析批次"); Set<String> keys = new HashSet<>();
        for (var value : values) if (!keys.add(evidenceKey(value))) throw invalid("证据解析身份不能重复。");
    }

    public enum CursorPurpose { EVIDENCE, SOURCE_PROGRESS, USE_DIRECTORY, PREFERENCE_DIRECTORY }
    public record DirectoryPosition(Instant createdAt, String id) {}
    public record SourcePosition(String sourceId) {}
    public record EvidenceCursor(int v, CursorPurpose purpose, String ownerFingerprint, String scopeHash,
                                  Instant asOf, Instant issuedAt, EvidencePosition position) {}
    public record DirectoryCursor(int v, CursorPurpose purpose, String ownerFingerprint, String scopeHash,
                                   Instant asOf, Instant issuedAt, DirectoryPosition position) {}
    public record ProgressCursor(int v, CursorPurpose purpose, String ownerFingerprint, String scopeHash,
                                  Instant asOf, Instant issuedAt, SourcePosition position) {}

    public static String cursor(EvidenceCursor value) { return encode(required(value)); }
    public static String cursor(DirectoryCursor value) { return encode(required(value)); }
    public static String cursor(ProgressCursor value) { return encode(required(value)); }
    private static String encode(Object value) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(STRICT.writeValueAsBytes(value));
        if (encoded.length() > 8192) throw limit("成长游标超过上限。"); return encoded;
    }
    private static <T> T decodeCursor(String encoded, Class<T> type) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 8192 || !encoded.matches("[A-Za-z0-9_-]+")) throw invalid("成长游标无效。");
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded); T value = STRICT.readValue(raw, type);
            if (value == null || !encoded.equals(encode(value))) throw invalid("成长游标不是规范编码。");
            return value;
        } catch (ApiException exception) { throw exception; }
        catch (RuntimeException exception) { throw invalid("成长游标字段或编码无效。"); }
    }
    private static void cursorHeader(int version, CursorPurpose purpose, CursorPurpose expected, String fingerprint,
                                      String scopeHash, Instant asOf, Instant issuedAt, String owner, Instant now) {
        if (version != 1) throw invalid("成长游标版本不受支持。");
        hashText(fingerprint); hashText(scopeHash); time(asOf); time(issuedAt); time(now);
        if (purpose != expected || !ownerFingerprint(owner).equals(fingerprint)) throw cursorMismatch();
        if (asOf.isAfter(now) || issuedAt.isAfter(now) || asOf.isAfter(issuedAt)) throw invalid("成长游标时刻无效。");
        if (Duration.between(issuedAt, now).compareTo(Duration.ofHours(24)) > 0) {
            throw conflict("GROWTH_CURSOR_EXPIRED", "成长游标已超过 24 小时，请保留筛选并从首页重新读取。", null);
        }
    }
    // 先恢复 asOf，再用它计算默认窗口与 scopeHash；续页不得改用今天重新解释默认 28 天。
    public static EvidenceCursor evidenceCursor(String encoded, String owner, Instant now) {
        var value = decodeCursor(encoded, EvidenceCursor.class); if (value == null) return null;
        cursorHeader(value.v(), value.purpose(), CursorPurpose.EVIDENCE, value.ownerFingerprint(), value.scopeHash(), value.asOf(), value.issuedAt(), owner, now);
        required(value.position()); time(value.position().occurredAt()); evidenceKey(value.position().evidenceKey());
        if (!value.position().occurredAt().isBefore(value.asOf())) throw invalid("证据游标位置必须早于事实水位。"); return value;
    }
    public static DirectoryCursor directoryCursor(String encoded, String owner, CursorPurpose purpose, Instant now) {
        if (purpose != CursorPurpose.USE_DIRECTORY && purpose != CursorPurpose.PREFERENCE_DIRECTORY) throw invalid("目录游标用途无效。");
        var value = decodeCursor(encoded, DirectoryCursor.class); if (value == null) return null;
        cursorHeader(value.v(), value.purpose(), purpose, value.ownerFingerprint(), value.scopeHash(), value.asOf(), value.issuedAt(), owner, now);
        required(value.position()); time(value.position().createdAt()); uuid(value.position().id());
        if (!value.position().createdAt().isBefore(value.asOf())) throw invalid("目录游标位置必须早于事实水位。"); return value;
    }
    public static ProgressCursor progressCursor(String encoded, String owner, Instant now) {
        var value = decodeCursor(encoded, ProgressCursor.class); if (value == null) return null;
        cursorHeader(value.v(), value.purpose(), CursorPurpose.SOURCE_PROGRESS, value.ownerFingerprint(), value.scopeHash(), value.asOf(), value.issuedAt(), owner, now);
        required(value.position()); uuid(value.position().sourceId()); return value;
    }
    public static void cursorScope(String actual, String expected) { if (!hashText(actual).equals(hashText(expected))) throw cursorMismatch(); }
    public static void position(EvidencePosition value, Scope scope, EvidenceKind kind) {
        if (value == null) return; scope(scope); time(value.occurredAt());
        if (evidenceKey(value.evidenceKey()).kind() != kind || value.occurredAt().isBefore(scope.window().startAt())
                || !value.occurredAt().isBefore(scope.window().observedUntil())) throw cursorMismatch();
    }
    public static String ownerFingerprint(String owner) { return hash("GROWTH_OWNER_V1", uuid(owner)); }
    private record EvidenceScope(Scope scope, EvidenceKind kind, AssessmentLevel level, ReviewFlag reviewFlag) {}
    private record UseDirectoryScope(TargetRef target, String requirementId, UseStatus status) {}
    private record PreferenceDirectoryScope(Visibility visibility, SubjectKind subjectKind) {}
    private record ProgressScope(ProgressFilter filter, SourceDisposition disposition) {}
    public static String evidenceScopeHash(Scope scope, EvidenceKind kind, AssessmentLevel level, ReviewFlag flag) {
        scope(scope); required(kind); return hash("GROWTH_EVIDENCE_SCOPE_V1", new EvidenceScope(scope, kind, level, flag));
    }
    public static String useDirectoryScopeHash(TargetRef target, String requirementId, UseStatus status) {
        target(target); optionalId(requirementId); return hash("GROWTH_USE_DIRECTORY_V1", new UseDirectoryScope(target, requirementId, status));
    }
    public static String preferenceDirectoryScopeHash(Visibility visibility, SubjectKind kind) {
        return hash("GROWTH_PREFERENCE_DIRECTORY_V1", new PreferenceDirectoryScope(visibility, kind));
    }
    public static String progressScopeHash(ProgressFilter filter, SourceDisposition disposition) {
        required(filter); if (filter.target() != null) target(filter.target()); optionalId(filter.topicId()); required(filter.topicMode());
        return hash("GROWTH_SOURCE_PROGRESS_V1", new ProgressScope(filter, disposition));
    }
    private record HashInput(String protocol, Object value) {}
    public static String hash(String protocol, Object value) {
        if (protocol == null || !protocol.matches("[A-Z][A-Z0-9_]{0,79}")) throw new IllegalArgumentException("成长摘要协议标签无效");
        String canonical = STRICT.writeValueAsString(canonical(STRICT.valueToTree(new HashInput(protocol, value)), null));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256不可用", exception); }
    }
    private static Object canonical(JsonNode node, String field) {
        if (node.isNull()) return null;
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.properties().forEach(entry -> result.put(entry.getKey(), canonical(entry.getValue(), entry.getKey()))); return result;
        }
        if (node.isArray()) { var result = new ArrayList<Object>(); for (var item : node) result.add(canonical(item, null)); return result; }
        // 仅规范时间字段，标题和用户理由即使长得像时间也保持原文。
        if (node.isTextual()) return field != null && INSTANT_FIELDS.contains(field)
                ? MICRO_TIME.format(time(Instant.parse(node.stringValue()))) : node.stringValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) {
            if (!node.canConvertToLong()) throw new IllegalStateException("成长摘要数值溢出");
            return revision(node.longValue());
        }
        throw new IllegalStateException("成长摘要出现未定义数值类型");
    }

    public static <T> T readResponse(JsonMapper mapper, T value) { return bounded(mapper, value, PAGE_BYTES); }
    public static <T> T history(JsonMapper mapper, T value) { return bounded(mapper, value, HISTORY_BYTES); }
    public static ActivitySeries activityResponse(JsonMapper mapper, ActivitySeries value) {
        required(value); required(value.grain()); required(value.buckets());
        range(value.buckets().size(), 1, value.grain() == Grain.DAY ? 366 : 54, "活动桶数量");
        for (var bucket : value.buckets()) metrics(required(bucket).values());
        metrics(required(value.totals()).values()); return bounded(mapper, value, ACTIVITY_BYTES);
    }
    private static <T> T bounded(JsonMapper mapper, T value, int max) {
        if (mapper.writeValueAsBytes(value).length > max) throw limit("成长载荷超过字节上限，请缩小查询范围。"); return value;
    }
    public static void count(Count value) {
        required(value); required(value.mode());
        if (value.mode() == CountMode.UNAVAILABLE) { if (value.value() != null) throw invalid("不可用计数必须为空。"); }
        else revision(value.value());
    }
    public static void capability(Capability value) {
        required(value); required(value.status()); required(value.reason());
        if ((value.status() == Availability.READY) != (value.reason() == CapabilityReason.NONE)) throw invalid("能力状态与原因不一致。");
    }
    public static MetricUnit metricUnit(ActivityMetric metric) {
        return switch (required(metric)) {
            case QUESTION_INSTANCES_ANSWERED -> MetricUnit.DISTINCT_QUESTION;
            case MEMORY_CARDS_RATED -> MetricUnit.DISTINCT_CARD;
            case REPLAYS_STARTED, SAME_REPLAYS_STARTED, VARIANT_REPLAYS_STARTED -> MetricUnit.REPLAY;
            default -> MetricUnit.EVENT;
        };
    }
    public static void metrics(List<MetricValue> values) {
        required(values); if (values.size() != ActivityMetric.values().length) throw invalid("活动指标须完整返回固定 21 项。");
        domainMetrics(values);
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).metric() != ActivityMetric.values()[i]) throw invalid("活动指标顺序不一致。");
        }
    }
    // 各域只返回自己负责的指标；固定 21 项及顺序由聚合后的公共响应再校验。
    public static void domainMetrics(List<MetricValue> values) {
        required(values); Set<ActivityMetric> seen = new HashSet<>();
        for (var value : values) {
            var item = required(value); var metric = required(item.metric());
            if (!seen.add(metric)) throw invalid("领域活动指标不能重复。");
            if (item.unit() != metricUnit(metric)
                    || item.additive() != (item.unit() != MetricUnit.DISTINCT_CARD && item.unit() != MetricUnit.DISTINCT_QUESTION)) throw invalid("活动指标单位或可加性不一致。");
            capability(item.capability()); count(item.count());
            if (item.capability().status() != Availability.READY && item.count().mode() != CountMode.UNAVAILABLE) throw invalid("不可用活动不能返回真实计数。");
        }
    }

    public static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
    public static ApiException limit(String detail) { return new ApiException(422, "GROWTH_LIMIT", detail); }
    public static ApiException conflict(String code, String detail, Long revision) { return new ApiException(409, code, detail, revision); }
    public static ApiException dependency() { return new ApiException(409, "DEPENDENCY_NOT_READY", "受审证据链尚未接入，不能把其他记录当作正式受审观察。"); }
    public static ApiException unavailable() { return new ApiException(503, "GROWTH_SOURCE_UNAVAILABLE", "必要来源暂时无法读取，请保留输入后重试。"); }
    public static ApiException cursorMismatch() { return new ApiException(400, "GROWTH_CURSOR_MISMATCH", "成长游标与当前身份、范围或筛选不匹配。"); }
    public static IllegalStateException integrity() { return new IllegalStateException("成长来源元数据或持久化身份不完整"); }
}
