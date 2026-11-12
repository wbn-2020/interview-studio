package com.wenjie.studio.search;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.review.RecollectionDtos;
import com.wenjie.studio.review.ReviewDraftSources;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.ReviewedObservationDtos;
import com.wenjie.studio.review.StandardReviewRules;
import com.wenjie.studio.training.TrainingDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.search.UnifiedSearchDtos.*;

@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = ApiException.class)
public class UnifiedSearchHistorySources {
    private static final long MAX_COUNT = 9_007_199_254_740_991L;
    private final UnifiedSearchHistoryMapper mapper;
    private final ReviewDraftSources access;
    private final JsonMapper json;

    public UnifiedSearchHistorySources(UnifiedSearchHistoryMapper mapper, ReviewDraftSources access) {
        this.mapper = mapper;
        this.access = access;
        json = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                                .maxStringLength(393216).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }

    public Source read(String owner, Domain domain, String id, String versionId, Integer revision) {
        requestId(owner); requestId(id); requestId(versionId);
        if (domain == Domain.REVIEW_FEEDBACK) {
            if (!range(revision, 1, 100)) throw invalid();
            if (!id.equals(versionId)) throw missing();
            // 先读无正文的确切绑定并验证访问，历史修订也不能借用其他提交的许可。
            var binding = mapper.feedbackBinding(owner, id, revision);
            if (binding == null) throw missing();
            if (!owner.equals(binding.ownerId()) || !id.equals(binding.id())) throw broken();
            requireAccess(owner, new ReviewDtos.AnswerLink(binding.sessionId(), binding.questionId(), binding.answerId()));
            var row = mapper.feedback(owner, id, revision);
            if (row == null) throw missing();
            if (!id.equals(row.id()) || !revision.equals(row.revision())) throw broken();
            return feedback(owner, row);
        }
        if (revision != null) throw invalid();
        if (domain == Domain.TRAINING_ANSWER) {
            if (!id.equals(versionId)) throw missing();
            var row = mapper.answer(owner, id);
            if (row == null) throw missing();
            if (!id.equals(row.id())) throw broken();
            return answer(owner, row);
        }
        if (domain == Domain.RECOLLECTION) {
            var row = mapper.recollection(owner, id, versionId);
            if (row == null) throw missing();
            if (!id.equals(row.id()) || !versionId.equals(row.versionId())) throw broken();
            return recollection(owner, row);
        }
        throw invalid();
    }

    public List<Source> candidates(String owner, Domain domain, String status, String query, String afterId, int limit) {
        requestId(owner);
        if (afterId != null) requestId(afterId);
        if (domain == null || !("ACTIVE".equals(status) || "ARCHIVED".equals(status)) || query == null || query.isBlank()
                || query.length() > 100 || limit < 1 || limit > 100) throw invalid();
        if ((domain == Domain.TRAINING_ANSWER || domain == Domain.REVIEW_FEEDBACK) && "ARCHIVED".equals(status)) return List.of();
        String pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        // 批内损坏来源须显式失败；静默过滤会把 seek 游标和“是否耗尽”的含义改掉。
        return switch (domain) {
            case TRAINING_ANSWER -> mapper.answers(owner, pattern, afterId, limit).stream().map(row -> answer(owner, row)).toList();
            case REVIEW_FEEDBACK -> mapper.feedbacks(owner, pattern, afterId, limit).stream().map(row -> feedback(owner, row)).toList();
            case RECOLLECTION -> mapper.recollections(owner, status, pattern, afterId, limit).stream().map(row -> recollection(owner, row)).toList();
            default -> throw invalid();
        };
    }

    public void requireFeedbackAccess(String owner, TrainingContext context) {
        requestId(owner);
        if (context == null) throw broken();
        requireAccess(owner, context.answer());
    }

    public TrainingContext trainingContext(String owner, ReviewDtos.AnswerLink answer) {
        requestId(owner);
        if (answer == null || !uuid(answer.sessionId()) || !uuid(answer.questionId()) || !uuid(answer.answerId())) throw broken();
        TrainingContext context = training(owner, mapper.context(owner, answer.answerId()));
        if (!answer.equals(context.answer())) throw broken();
        return context;
    }

    private Source answer(String owner, UnifiedSearchHistoryRows.AnswerRow row) {
        TrainingContext context = training(owner, row);
        if (!Boolean.TRUE.equals(row.bodyValid())) throw broken();
        JsonNode texts = object(row.textsJson(), 393216);
        shape(texts, "text", "nodeLabels", "nodeDescriptions", "edgeLabels");
        var fields = new ArrayList<TextField>();
        fields.add(new TextField("text", "回答文字", storedText(texts, "text", 20000, true)));
        JsonNode labels = array(texts, "nodeLabels", 80), descriptions = array(texts, "nodeDescriptions", 80);
        JsonNode edges = array(texts, "edgeLabels", 160);
        if (labels.size() != descriptions.size()) throw broken();
        for (int i = 0; i < labels.size(); i++) {
            fields.add(new TextField("diagram.nodes." + i + ".label", "节点 " + (i + 1) + " 标签", storedText(labels.get(i), 200, true)));
            fields.add(new TextField("diagram.nodes." + i + ".description", "节点 " + (i + 1) + " 说明", storedText(descriptions.get(i), 5000, false)));
        }
        for (int i = 0; i < edges.size(); i++) {
            fields.add(new TextField("diagram.edges." + i + ".label", "连线 " + (i + 1) + " 标签", storedText(edges.get(i), 200, false)));
        }
        return new Source(new Hit(Domain.TRAINING_ANSWER, row.id(), row.id(), row.versionNumber(), "ANSWER", row.title(),
                "SUBMITTED", row.id().equals(row.latestAnswerId()), null, null, null, null, List.of(), context, null, null), List.copyOf(fields));
    }

    private TrainingContext training(String owner, UnifiedSearchHistoryRows.AnswerRow row) {
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !uuid(row.id()) || !uuid(row.sessionId()) || !uuid(row.questionId())
                || !Boolean.TRUE.equals(row.metadataValid()) || !range(row.versionNumber(), 1, 100)
                || !uuid(row.latestAnswerId()) || !range(row.answerCount(), row.versionNumber(), 100)
                || !Objects.equals(row.latestNumber(), row.answerCount())
                || row.id().equals(row.latestAnswerId()) != row.versionNumber().equals(row.answerCount())
                || !range(row.assistanceEventSequence(), 0, 2000)) throw broken();
        var mode = enumeration(row.mode(), TrainingDtos.Mode.class);
        var condition = enumeration(row.answerCondition(), TrainingDtos.AnswerCondition.class);
        var status = enumeration(row.sessionStatus(), TrainingDtos.Status.class);
        if (status == TrainingDtos.Status.DRAFT) throw missing();
        var timing = enumeration(row.feedbackTiming(), TrainingDtos.FeedbackTiming.class);
        var kind = enumeration(row.questionKind(), TrainingDtos.QuestionKind.class);
        if ((mode == TrainingDtos.Mode.LEARNING) != (condition == TrainingDtos.AnswerCondition.LEARNING)
                || condition == TrainingDtos.AnswerCondition.ASSISTED && row.assistanceEventSequence() == 0
                || condition == TrainingDtos.AnswerCondition.BEFORE_ASSISTANCE && row.assistanceEventSequence() != 0) throw broken();
        storedText(row.title(), 200, true); time(row.createdAt());
        JsonNode question = object(row.questionContextJson(), 65536);
        shape(question, "technology", "scenario", "conditions");
        // 模式来自已开始会话，辅助条件与曝光来自固定提交，不按当前资料查看状态重新推算。
        var conditions = new ReviewedObservationDtos.RecordedAnswerConditions(mode, condition, row.assistanceEventSequence(),
                exposure(row.exposureJson()), kind, row.versionNumber(), row.createdAt(),
                storedText(question, "technology", 2000, false), storedText(question, "scenario", 2000, false),
                storedText(question, "conditions", 5000, false));
        return new TrainingContext(new ReviewDtos.AnswerLink(row.sessionId(), row.questionId(), row.id()), status, timing, conditions);
    }

    private Source feedback(String owner, UnifiedSearchHistoryRows.FeedbackRow row) {
        if (!owner.equals(row.ownerId()) || !uuid(row.id()) || !range(row.revision(), 1, 100)
                || !range(row.currentRevision(), row.revision(), 100) || !Objects.equals(row.actualHeadRevision(), row.currentRevision())
                || !Boolean.TRUE.equals(row.metadataValid())) throw broken();
        var answer = new ReviewDtos.AnswerLink(row.sessionId(), row.questionId(), row.answerId());
        requireAccess(owner, answer);
        var answerRow = mapper.context(owner, row.answerId());
        TrainingContext context = training(owner, answerRow);
        if (!answer.equals(context.answer())) throw broken();
        var kind = enumeration(row.kind(), ReviewDtos.FeedbackKind.class);
        text(row.authorLabel(), 120, kind == ReviewDtos.FeedbackKind.EXTERNAL_FEEDBACK);
        if (kind == ReviewDtos.FeedbackKind.SELF_CHECK && !row.authorLabel().isEmpty()) throw broken();
        text(row.observation(), 2000, true); text(row.condition(), 1000, false); text(row.basis(), 1000, true);
        text(row.limitation(), 1000, true); text(row.suggestion(), 1000, true);
        var feedback = new FeedbackContext(kind, row.authorLabel(), enumeration(row.dimension(), QuestionDtos.Dimension.class),
                enumeration(row.coverage(), AiDtos.EvaluationCoverage.class), row.condition(), row.basis(), row.limitation(), decision(row));
        return new Source(new Hit(Domain.REVIEW_FEEDBACK, row.id(), row.id(), row.revision(), row.kind(),
                answerRow.title(), "RECORDED", row.revision().equals(row.currentRevision()), null, null, null, null,
                List.of(), context, feedback, null), List.of(new TextField("observation", "观察", row.observation()),
                new TextField("condition", "条件", row.condition()), new TextField("basis", "依据", row.basis()),
                new TextField("limitation", "限制", row.limitation()), new TextField("suggestion", "改进建议", row.suggestion())));
    }

    private static ReviewDtos.Decision decision(UnifiedSearchHistoryRows.FeedbackRow row) {
        if (row.decisionId() == null) {
            if (row.decisionRevision() != null || row.decisionChoice() != null || row.decisionReason() != null || row.decisionCreatedAt() != null) throw broken();
            return null;
        }
        if (!uuid(row.decisionId()) || !range(row.decisionRevision(), 1, 20)) throw broken();
        var choice = enumeration(row.decisionChoice(), AiDtos.FeedbackChoice.class);
        text(row.decisionReason(), 1000, choice == AiDtos.FeedbackChoice.DISPUTED); time(row.decisionCreatedAt());
        return new ReviewDtos.Decision(row.decisionId(), row.decisionRevision(), choice, row.decisionReason(), row.decisionCreatedAt());
    }

    private Source recollection(String owner, UnifiedSearchHistoryRows.RecollectionRow row) {
        if (!owner.equals(row.ownerId()) || !uuid(row.id()) || !uuid(row.versionId()) || !uuid(row.currentVersionId())
                || !range(row.sourceRevision(), 0, 1000) || !range(row.versionNumber(), 1, 1000)
                || !range(row.headVersionNumber(), row.versionNumber(), 1000) || row.headVersionNumber() > row.sourceRevision() + 1
                || !Objects.equals(row.actualHeadVersionNumber(), row.headVersionNumber())
                || row.versionId().equals(row.currentVersionId()) != row.versionNumber().equals(row.headVersionNumber())) throw broken();
        enumeration(row.status(), RecollectionDtos.Status.class);
        JsonNode content = object(row.contentJson(), 196608);
        shape(content, "title", "question", "type", "conditions", "baselineKind", "answerFragment", "feedback", "uncertainty", "gap");
        var baseline = enumeration(storedText(content, "baselineKind", 16, true), RecollectionDtos.BaselineKind.class);
        var type = enumeration(storedText(content, "type", 40, true), QuestionDtos.Type.class);
        var fields = new ArrayList<TextField>();
        add(fields, content, "title", "标题", 200, true);
        add(fields, content, "question", "回忆题目", 10000, true);
        add(fields, content, "conditions", "回忆条件", 5000, false);
        String fragment = add(fields, content, "answerFragment", "回答回忆片段", 20000, baseline == RecollectionDtos.BaselineKind.RECOLLECTION);
        add(fields, content, "feedback", "事后补记反馈", 5000, false);
        String uncertainty = add(fields, content, "uncertainty", "完整不确定性", 2000, true);
        add(fields, content, "gap", "待补缺口", 2000, false);
        if (!fields.getFirst().text().equals(row.title()) || !baseline.name().equals(row.baselineKind())
                || baseline == RecollectionDtos.BaselineKind.NONE && !fragment.isEmpty()) throw broken();
        return new Source(new Hit(Domain.RECOLLECTION, row.id(), row.versionId(), row.versionNumber(), "RECOLLECTION", row.title(),
                row.status(), row.versionId().equals(row.currentVersionId()), null, null, null, null, List.of(), null, null,
                new RecollectionContext(baseline, type, uncertainty)), List.copyOf(fields));
    }

    private void requireAccess(String owner, ReviewDtos.AnswerLink answer) {
        if (answer == null || !uuid(answer.sessionId()) || !uuid(answer.questionId()) || !uuid(answer.answerId())) throw broken();
        try { access.requireAnswerAccess(owner, answer); }
        catch (ApiException exception) {
            if ("AI_REVIEW_SOURCE_NOT_FOUND".equals(exception.code())) throw missing();
            throw exception;
        } catch (IllegalStateException exception) { throw broken(); }
    }

    private TrainingDtos.PriorExposure exposure(String value) {
        JsonNode root = object(value, 65536);
        if (root.has("evaluationAccessCount")) shape(root, "authoredCount", "referenceViewCount", "lastExposureAt", "evaluationAccessCount");
        else shape(root, "authoredCount", "referenceViewCount", "lastExposureAt");
        long authored = count(root.get("authoredCount")), viewed = count(root.get("referenceViewCount"));
        JsonNode evaluation = root.get("evaluationAccessCount"), last = root.get("lastExposureAt");
        long evaluated = evaluation == null || evaluation.isNull() ? 0 : count(evaluation);
        Instant at = null;
        if (!last.isNull()) {
            if (!last.isTextual()) throw broken();
            try { at = Instant.parse(last.stringValue()); }
            catch (DateTimeParseException exception) { throw broken(); }
            time(at);
        }
        return new TrainingDtos.PriorExposure(authored, viewed, at, evaluated);
    }

    private JsonNode object(String value, int maximumBytes) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) throw broken();
        try {
            JsonNode node = json.readTree(value);
            if (node == null || !node.isObject()) throw broken();
            return node;
        } catch (JacksonException exception) { throw broken(); }
    }

    private static void shape(JsonNode node, String... names) {
        Set<String> expected = Set.of(names);
        if (node.size() != expected.size()) throw broken();
        for (var property : node.properties()) if (!expected.contains(property.getKey())) throw broken();
        for (String name : names) if (!node.has(name)) throw broken();
    }

    private static JsonNode array(JsonNode root, String field, int maximum) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray() || node.size() > maximum) throw broken();
        return node;
    }

    private static long count(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || !range(node.longValue(), 0, MAX_COUNT)) throw broken();
        return node.longValue();
    }

    private static String add(List<TextField> fields, JsonNode content, String key, String label, int maximum, boolean required) {
        String value = storedText(content, key, maximum, required);
        text(value, maximum, required); fields.add(new TextField(key, label, value));
        return value;
    }

    private static String storedText(JsonNode root, String field, int maximum, boolean required) { return storedText(root.get(field), maximum, required); }
    private static String storedText(JsonNode node, int maximum, boolean required) {
        if (node == null || !node.isTextual()) throw broken();
        return storedText(node.stringValue(), maximum, required);
    }
    private static String storedText(String value, int maximum, boolean required) {
        if (value == null || value.codePointCount(0, value.length()) > maximum || required && value.isBlank()) throw broken();
        return value;
    }
    private static void text(String value, int maximum, boolean required) {
        storedText(value, maximum, required);
        if (value.indexOf('\0') >= 0 || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) throw broken();
    }
    private static void time(Instant value) {
        try { StandardReviewRules.time(value); }
        catch (ApiException exception) { throw broken(); }
    }
    private static boolean range(Number value, long minimum, long maximum) { return value != null && value.longValue() >= minimum && value.longValue() <= maximum; }
    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    private static void requestId(String value) { if (!uuid(value)) throw invalid(); }
    private static <E extends Enum<E>> E enumeration(String value, Class<E> type) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException | NullPointerException exception) { throw broken(); }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "请检查来源身份、修订、检索词和分页范围。"); }
    private static ApiException missing() { return new ApiException(404, "SEARCH_SOURCE_NOT_FOUND", "该来源或确切版本不存在或不可访问。"); }
    private static ApiException broken() { return new ApiException(409, "SEARCH_SOURCE_INVALID", "检索来源完整性无法确认，请在原模块检查资料后重试。"); }
}
