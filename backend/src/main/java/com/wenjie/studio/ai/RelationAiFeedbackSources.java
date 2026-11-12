package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceDtos.Selection;
import com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceDtos.Snapshot;
import com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceDtos.Source;
import com.wenjie.studio.review.ReviewDraftSources;
import com.wenjie.studio.review.ReviewDtos.AnswerLink;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.ai.AiDtos.*;

/** 固定成功评价的单条选区；报告解析仅核结构，不重新认证引文与原答的逐字对应。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
public class RelationAiFeedbackSources {
    private static final int MAX_REPORT_BYTES = 1_048_576;
    private static final String NOT_FOUND = "RELATION_AI_FEEDBACK_SOURCE_NOT_FOUND";
    private static final Set<ReviewDraftDtos.Field> FIELDS = Set.of(ReviewDraftDtos.Field.OBSERVATION,
            ReviewDraftDtos.Field.CONDITION, ReviewDraftDtos.Field.BASIS, ReviewDraftDtos.Field.LIMITATION,
            ReviewDraftDtos.Field.SUGGESTION);
    private static final Set<String> REPORT_FIELDS = Set.of("level", "summary", "limitations", "observations", "validationNote");
    private static final Set<String> OBSERVATION_FIELDS = Set.of("id", "dimension", "coverage", "observation",
            "condition", "basis", "limitation", "suggestion", "citations");
    private static final Set<String> CITATION_FIELDS = Set.of("kind", "elementId", "start", "end", "quote");
    private final RelationAiFeedbackSourceMapper mapper;
    private final ReviewDraftSources access;
    private final JsonMapper json;

    public RelationAiFeedbackSources(RelationAiFeedbackSourceMapper mapper, ReviewDraftSources access) {
        this.mapper = mapper;
        this.access = access;
        json = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                                .maxStringLength(MAX_REPORT_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    public Source read(String owner, String taskId, String feedbackId) {
        if (!uuid(owner) || !uuid(taskId) || !feedbackId(feedbackId)) throw invalid();
        return load(owner, taskId, feedbackId, null);
    }

    public List<Source> readObservations(String owner, String taskId) {
        if (!uuid(owner) || !uuid(taskId)) throw invalid();
        LoadedReport loaded = loadReport(owner, taskId, null);
        // 报告按保存的 f1…fN 顺序一次解析、哈希；独立决定仍读取每条观察的当前修订。
        return loaded.report().observations().stream().map(item -> source(owner, loaded, item)).toList();
    }

    public void requireReadAccess(String owner, String taskId, String attemptId, AnswerLink expectedAnswer) {
        if (!uuid(owner) || !uuid(taskId) || !uuid(attemptId) || !validAnswer(expectedAnswer)) throw invalid();
        var metadata = metadata(owner, taskId, false);
        AnswerLink answer = answer(metadata);
        if (!attemptId.equals(metadata.currentAttemptId()) || !expectedAnswer.equals(answer)) throw changed();
        // 返回前只复核成功尝试与原答许可；报告字节已在读取事务中固定，不再加载正文。
        access.requireAnswerAccess(owner, answer);
    }

    public Snapshot capture(String owner, Selection selection) {
        if (!uuid(owner) || !validSelection(selection)) throw invalid();
        Source source;
        try { source = load(owner, selection.taskId(), selection.feedbackId(), null); }
        catch (ApiException exception) {
            if (NOT_FOUND.equals(exception.code()) || "AI_SOURCE_CHANGED".equals(exception.code())) throw changed();
            throw exception;
        }
        if (!source.attemptId().equals(selection.attemptId()) || !source.reportHash().equals(selection.reportHash())
                || !source.answer().equals(selection.answer()) || source.decisionRevision() != selection.expectedDecisionRevision()
                || !matches(source, selection.fragments())) throw changed();
        Snapshot snapshot = snapshot(source, selection.fragments(), source.decisionRevision(), source.decisionChoice());
        if (!validSnapshot(snapshot)) throw broken();
        return snapshot;
    }

    public OriginState state(String owner, Snapshot snapshot) {
        if (!uuid(owner)) throw invalid();
        if (!validSnapshot(snapshot)) throw broken();
        Source source;
        try { source = load(owner, snapshot.taskId(), snapshot.feedbackId(), snapshot); }
        catch (ApiException exception) {
            // 只有明确的原任务缺失可降为历史不可用；权限、数据库和报告损坏保留原错误。
            if (NOT_FOUND.equals(exception.code())) return OriginState.UNAVAILABLE;
            throw exception;
        }
        if (!matches(source, snapshot.fragments())
                || !snapshot.equals(snapshot(source, snapshot.fragments(), snapshot.decisionRevision(), snapshot.decisionChoice()))
                || source.decisionRevision() < snapshot.decisionRevision()) throw broken();
        if (snapshot.decisionRevision() > 0) {
            var decision = mapper.decision(owner, snapshot.taskId(), snapshot.feedbackId(), snapshot.decisionRevision());
            verifyDecision(owner, snapshot.taskId(), snapshot.feedbackId(), decision);
            if (decision.revision() != snapshot.decisionRevision()
                    || !decision.choice().equals(snapshot.decisionChoice().name())) throw broken();
        }
        if (source.decisionRevision() == snapshot.decisionRevision()) {
            if (source.decisionChoice() != snapshot.decisionChoice()) throw broken();
            return OriginState.CURRENT;
        }
        return OriginState.REVIEW_REQUIRED;
    }

    public void requireAccess(String owner, Selection selection) {
        if (!uuid(owner) || !validSelection(selection)) throw invalid();
        var metadata = metadata(owner, selection.taskId(), false);
        AnswerLink answer = answer(metadata);
        if (!selection.attemptId().equals(metadata.currentAttemptId()) || !selection.answer().equals(answer)) throw changed();
        // 幂等回执守卫只核实际提交与访问条件，报告及其引用原文留待 capture 读取。
        access.requireAnswerAccess(owner, answer);
    }

    public void requireAccess(String owner, Snapshot snapshot) {
        if (!uuid(owner)) throw invalid();
        if (!validSnapshot(snapshot)) throw broken();
        // 原任务缺失时 state 可返回不可用，固定原答的查看条件仍须先独立核对。
        access.requireAnswerAccess(owner, snapshot.answer());
        state(owner, snapshot);
    }

    public boolean current(String owner, Snapshot snapshot) {
        try { return state(owner, snapshot) == OriginState.CURRENT; }
        catch (ApiException exception) {
            // 后台权限阻断可停止新执行；历史报告、尝试或决定损坏必须继续报错。
            if ("AI_EVALUATION_ACCESS_REQUIRED".equals(exception.code())
                    || "AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED".equals(exception.code())) return false;
            throw exception;
        }
    }

    public static boolean validCitation(Snapshot snapshot, ReviewDraftDtos.Citation citation) {
        if (!validSnapshot(snapshot) || citation == null || citation.field() == null || !FIELDS.contains(citation.field())
                || citation.start() < 0 || citation.end() <= citation.start()
                || !unicode(citation.quote()) || citation.quote().isBlank()
                || citation.quote().indexOf('\0') >= 0 || citation.quote().length() > 1000) return false;
        // 完整附带限定只提供上下文；引用必须完全落在同字段的一段明确选区内。
        for (ReviewDraftDtos.Fragment fragment : snapshot.fragments()) {
            if (fragment.field() != citation.field() || citation.start() < fragment.start() || citation.end() > fragment.end()) continue;
            int start = citation.start() - fragment.start(), end = citation.end() - fragment.start();
            if (!split(fragment.quote(), start) && !split(fragment.quote(), end)
                    && citation.quote().equals(fragment.quote().substring(start, end))) return true;
        }
        return false;
    }

    private Source load(String owner, String taskId, String feedbackId, Snapshot fixed) {
        LoadedReport loaded = loadReport(owner, taskId, fixed);
        EvaluationObservation selected = loaded.report().observations().stream().filter(item -> feedbackId.equals(item.id())).findFirst().orElse(null);
        if (selected == null) {
            if (fixed != null) throw broken();
            throw missing();
        }
        return source(owner, loaded, selected);
    }

    private LoadedReport loadReport(String owner, String taskId, Snapshot fixed) {
        var metadata = metadata(owner, taskId, fixed != null);
        AnswerLink answer = answer(metadata);
        if (fixed != null && (!fixed.attemptId().equals(metadata.currentAttemptId()) || !fixed.answer().equals(answer)
                || fixed.answerNumber() != metadata.answerNumber() || !fixed.answerCondition().equals(metadata.answerCondition()))) throw broken();
        access.requireAnswerAccess(owner, answer);
        // candidate_json 自带引用片段，必须在访问守卫后读取；固定清单和原答正文不参与此出口。
        var saved = mapper.candidate(owner, taskId);
        if (saved == null || !owner.equals(saved.ownerId()) || !taskId.equals(saved.taskId())) throw broken();
        EvaluationReport report = report(saved.candidateJson());
        String reportHash = reportHash(saved.candidateJson());
        if (fixed != null && !fixed.reportHash().equals(reportHash)) throw broken();
        return new LoadedReport(metadata, answer, report, reportHash);
    }

    private Source source(String owner, LoadedReport loaded, EvaluationObservation selected) {
        var metadata = loaded.metadata();
        String taskId = metadata.taskId(), feedbackId = selected.id();
        var decision = mapper.latestDecision(owner, taskId, feedbackId);
        if (decision != null) verifyDecision(owner, taskId, feedbackId, decision);
        return new Source(taskId, metadata.currentAttemptId(), feedbackId, loaded.reportHash(), loaded.answer(),
                metadata.answerNumber(), metadata.answerCondition(), loaded.report().level(), loaded.report().limitations(), loaded.report().validationNote(),
                selected.dimension(), selected.coverage(), decision == null ? 0 : decision.revision(),
                decision == null ? null : enumeration(decision.choice(), FeedbackChoice.class), selected.observation(),
                selected.condition(), selected.basis(), selected.limitation(), selected.suggestion());
    }

    private record LoadedReport(RelationAiFeedbackSourceRows.Metadata metadata, AnswerLink answer,
                                EvaluationReport report, String reportHash) { }

    private RelationAiFeedbackSourceRows.Metadata metadata(String owner, String taskId, boolean fixed) {
        var row = mapper.metadata(owner, taskId);
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !taskId.equals(row.taskId())) throw broken();
        Purpose purpose = enumeration(row.purpose(), Purpose.class);
        Status status = enumeration(row.status(), Status.class);
        if (purpose != Purpose.ANSWER_EVALUATION || status != Status.SUCCEEDED) {
            if (fixed) throw broken();
            throw missing();
        }
        if (!uuid(row.currentAttemptId()) || !range(row.attemptCount(), 1, 3)
                || !owner.equals(row.bindingOwnerId()) || !taskId.equals(row.bindingTaskId()) || !validAnswer(answer(row))
                || !owner.equals(row.attemptOwnerId()) || !taskId.equals(row.attemptTaskId())
                || !row.currentAttemptId().equals(row.attemptId()) || row.attemptNumber() == null
                || row.attemptNumber() != row.attemptCount() || !Status.SUCCEEDED.name().equals(row.attemptStatus())
                || !owner.equals(row.answerOwnerId()) || !row.sessionId().equals(row.answerSessionId())
                || !row.questionId().equals(row.answerQuestionId()) || !row.answerId().equals(row.actualAnswerId())
                || row.answerNumber() == null || !range(row.answerNumber(), 1, 100) || !answerCondition(row.answerCondition())) throw broken();
        return row;
    }

    private static void verifyDecision(String owner, String taskId, String feedbackId, RelationAiFeedbackSourceRows.Decision row) {
        if (row == null || !owner.equals(row.ownerId()) || !taskId.equals(row.taskId()) || !feedbackId.equals(row.feedbackId())
                || !uuid(row.id()) || !range(row.revision(), 1, 20)) throw broken();
        enumeration(row.choice(), FeedbackChoice.class);
    }

    private EvaluationReport report(String value) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) throw broken();
        try {
            JsonNode root = json.readTree(value);
            fields(root, REPORT_FIELDS);
            for (String name : Set.of("level", "summary", "limitations", "validationNote")) if (!root.get(name).isTextual()) throw broken();
            JsonNode observations = root.get("observations");
            if (!observations.isArray() || observations.isEmpty() || observations.size() > 12) throw broken();
            for (JsonNode item : observations) {
                fields(item, OBSERVATION_FIELDS);
                for (String name : Set.of("id", "dimension", "coverage", "observation", "condition", "basis", "limitation", "suggestion")) {
                    if (!item.get(name).isTextual()) throw broken();
                }
                JsonNode citations = item.get("citations");
                if (!citations.isArray() || citations.size() > 6) throw broken();
                for (JsonNode citation : citations) {
                    fields(citation, CITATION_FIELDS);
                    if (!citation.get("kind").isTextual() || !citation.get("quote").isTextual()
                            || !(citation.get("elementId").isNull() || citation.get("elementId").isTextual())
                            || !integer(citation.get("start")) || !integer(citation.get("end"))) throw broken();
                }
            }
            EvaluationReport result = json.treeToValue(root, EvaluationReport.class);
            if (!validReport(result)) throw broken();
            return result;
        } catch (RuntimeException exception) { throw new IllegalStateException("已保存 AI 评价报告的结构或固定字段无效", exception); }
    }

    private static boolean validReport(EvaluationReport report) {
        if (report == null || report.level() != EvaluationLevel.AI_UNREVIEWED
                || !ModelGateway.EVALUATION_VALIDATION_NOTE.equals(report.validationNote())
                || !codePoints(report.summary(), 2000, true) || !codePoints(report.limitations(), 2000, true)
                || report.observations() == null || report.observations().isEmpty() || report.observations().size() > 12) return false;
        int total = count(report.summary()) + count(report.limitations());
        for (int index = 0; index < report.observations().size(); index++) {
            var item = report.observations().get(index);
            if (item == null || !("f" + (index + 1)).equals(item.id()) || item.dimension() == null || item.coverage() == null
                    || !codePoints(item.observation(), 2000, true) || !codePoints(item.condition(), 1000, false)
                    || !codePoints(item.basis(), 1000, true) || !codePoints(item.limitation(), 1000, true)
                    || !codePoints(item.suggestion(), 1000, true) || item.citations() == null || item.citations().size() > 6) return false;
            boolean answerEvidence = false;
            Set<String> positions = new HashSet<>();
            for (EvaluationCitation citation : item.citations()) {
                if (citation == null || citation.kind() == null || citation.start() < 0 || citation.end() <= citation.start()
                        || !codePoints(citation.quote(), 500, true) || (long) citation.end() - citation.start() != citation.quote().length()) return false;
                boolean element = switch (citation.kind()) {
                    case ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION, ANSWER_EDGE_LABEL, CHECKPOINT -> true;
                    case ANSWER_TEXT, QUESTION_PROMPT, QUESTION_CONDITIONS, REFERENCE -> false;
                };
                if ((element ? !uuid(citation.elementId()) : citation.elementId() != null)
                        || !positions.add(citation.kind() + ":" + citation.elementId() + ":" + citation.start() + ":" + citation.end())) return false;
                answerEvidence |= switch (citation.kind()) {
                    case ANSWER_TEXT, ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION, ANSWER_EDGE_LABEL -> true;
                    case QUESTION_PROMPT, QUESTION_CONDITIONS, REFERENCE, CHECKPOINT -> false;
                };
                total += count(citation.quote());
            }
            if (item.coverage() == EvaluationCoverage.MENTIONED && !answerEvidence
                    || item.coverage() == EvaluationCoverage.NOT_MENTIONED && (answerEvidence || item.citations().isEmpty())) return false;
            total += count(item.observation()) + count(item.condition()) + count(item.basis()) + count(item.limitation()) + count(item.suggestion());
            if (total > 24000) return false;
        }
        return true;
    }

    public static boolean validSnapshot(Snapshot value) {
        return value != null && uuid(value.taskId()) && uuid(value.attemptId()) && feedbackId(value.feedbackId()) && hash(value.reportHash())
                && validAnswer(value.answer()) && range(value.answerNumber(), 1, 100) && answerCondition(value.answerCondition())
                && value.level() == EvaluationLevel.AI_UNREVIEWED && ModelGateway.EVALUATION_VALIDATION_NOTE.equals(value.validationNote())
                && codePoints(value.reportLimitations(), 2000, true) && value.dimension() != null && value.coverage() != null
                && range(value.decisionRevision(), 0, 20) && (value.decisionRevision() == 0) == (value.decisionChoice() == null)
                && codePoints(value.condition(), 1000, false) && codePoints(value.basis(), 1000, true)
                && codePoints(value.limitation(), 1000, true) && fragments(value.fragments())
                && value.fragments().stream().allMatch(fragment -> switch (fragment.field()) {
                    case CONDITION -> matches(value.condition(), fragment);
                    case BASIS -> matches(value.basis(), fragment);
                    case LIMITATION -> matches(value.limitation(), fragment);
                    default -> true;
                });
    }

    private static boolean validSelection(Selection value) {
        return value != null && uuid(value.taskId()) && uuid(value.attemptId()) && feedbackId(value.feedbackId()) && hash(value.reportHash())
                && value.expectedDecisionRevision() != null && range(value.expectedDecisionRevision(), 0, 20)
                && validAnswer(value.answer()) && fragments(value.fragments());
    }

    private static boolean fragments(List<ReviewDraftDtos.Fragment> values) {
        if (values == null || values.isEmpty() || values.size() > 8) return false;
        for (int index = 0; index < values.size(); index++) {
            var item = values.get(index);
            if (item == null || item.field() == null || !FIELDS.contains(item.field()) || item.start() == null || item.end() == null
                    || item.start() < 0 || item.end() <= item.start() || !unicode(item.quote()) || item.quote().isBlank()
                    || item.quote().indexOf('\0') >= 0 || item.quote().length() > 8000
                    || (long) item.end() - item.start() != item.quote().length()) return false;
            for (int previous = 0; previous < index; previous++) {
                var other = values.get(previous);
                if (other.field() == item.field() && item.start() < other.end() && other.start() < item.end()) return false;
            }
        }
        return true;
    }

    private static boolean matches(Source source, List<ReviewDraftDtos.Fragment> fragments) {
        return fragments.stream().allMatch(fragment -> matches(switch (fragment.field()) {
            case OBSERVATION -> source.observation(); case CONDITION -> source.condition(); case BASIS -> source.basis();
            case LIMITATION -> source.limitation(); case SUGGESTION -> source.suggestion(); default -> null;
        }, fragment));
    }

    private static boolean matches(String value, ReviewDraftDtos.Fragment fragment) {
        return value != null && fragment.start() >= 0 && fragment.end() > fragment.start() && fragment.end() <= value.length()
                && !split(value, fragment.start()) && !split(value, fragment.end())
                && fragment.quote().equals(value.substring(fragment.start(), fragment.end()));
    }

    private static Snapshot snapshot(Source source, List<ReviewDraftDtos.Fragment> fragments, int revision, FeedbackChoice choice) {
        return new Snapshot(source.taskId(), source.attemptId(), source.feedbackId(), source.reportHash(), source.answer(),
                source.answerNumber(), source.answerCondition(), source.level(), source.reportLimitations(), source.validationNote(),
                source.dimension(), source.coverage(), revision, choice, source.condition(), source.basis(), source.limitation(), List.copyOf(fragments));
    }

    private static AnswerLink answer(RelationAiFeedbackSourceRows.Metadata row) { return new AnswerLink(row.sessionId(), row.questionId(), row.answerId()); }
    private static boolean validAnswer(AnswerLink value) { return value != null && uuid(value.sessionId()) && uuid(value.questionId()) && uuid(value.answerId()); }
    private static boolean answerCondition(String value) { return value != null && Set.of("LEARNING", "BEFORE_ASSISTANCE", "ASSISTED").contains(value); }
    private static boolean range(int value, int min, int max) { return value >= min && value <= max; }
    private static boolean feedbackId(String value) { return value != null && value.matches("f([1-9]|1[0-2])"); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static int count(String value) { return value.codePointCount(0, value.length()); }
    // 历史报告完整字段沿用生成时的码点与 isBlank 规则；NUL 仅在新选区中禁止。
    private static boolean codePoints(String value, int max, boolean required) { return unicode(value) && count(value) <= max && (!required || !value.isBlank()); }
    private static boolean unicode(String value) { return value != null && value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF); }
    private static boolean split(String value, int index) {
        return index > 0 && index < value.length() && Character.isHighSurrogate(value.charAt(index - 1)) && Character.isLowSurrogate(value.charAt(index));
    }
    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    private static void fields(JsonNode value, Set<String> names) {
        if (value == null || !value.isObject() || value.size() != names.size() || names.stream().anyMatch(name -> !value.has(name))) throw broken();
    }
    private static boolean integer(JsonNode value) { return value != null && value.isIntegralNumber() && value.canConvertToInt(); }
    private static <E extends Enum<E>> E enumeration(String value, Class<E> type) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException | NullPointerException exception) { throw broken(); }
    }
    private static String reportHash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("当前运行时不支持报告摘要", exception); }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "AI 反馈来源身份、决定修订或原文选区无效。"); }
    private static ApiException missing() { return new ApiException(404, NOT_FOUND, "确切成功 AI 评价或所选反馈不存在或不可访问。"); }
    private static ApiException changed() { return new ApiException(409, "RELATION_AI_FEEDBACK_SOURCE_CHANGED", "所选 AI 反馈尝试、报告、确切提交、决定或原文选区已变化，请重新核对。"); }
    private static IllegalStateException broken() { return new IllegalStateException("AI 反馈来源的固定身份、报告或决定不完整"); }
}
