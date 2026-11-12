package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos.EvaluationCitation;
import com.wenjie.studio.ai.AiDtos.EvaluationCoverage;
import com.wenjie.studio.ai.AiDtos.EvaluationEvidenceKind;
import com.wenjie.studio.ai.AiDtos.EvaluationInput;
import com.wenjie.studio.common.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.review.ReviewDtos.*;

final class ReviewRules {
    static final int MAX_FEEDBACK_REVISION = 100;
    static final long MAX_ACTION_REVISION = 1000;

    private ReviewRules() {}

    static <T> T decode(JsonMapper json, JsonNode body, Class<T> type) {
        if (type == CreateFeedback.class) {
            fields(body, "answer", "content");
            answerShape(body.get("answer")); contentShape(body.get("content"));
        } else if (type == SaveFeedback.class) {
            fields(body, "expectedRevision", "content");
            integer(body, "expectedRevision", false); contentShape(body.get("content"));
        } else if (type == DecideFeedback.class) {
            fields(body, "feedbackRevision", "expectedRevision", "choice", "reason");
            integer(body, "feedbackRevision", false); integer(body, "expectedRevision", false);
            strings(body, "choice", "reason");
        } else if (type == CreateAction.class) {
            fields(body, "title", "detail", "sources"); strings(body, "title", "detail");
            JsonNode sources = body.get("sources");
            if (!sources.isArray() || sources.isEmpty() || sources.size() > 10) throw invalid("动作须选择 1 至 10 个来源。");
            for (JsonNode source : sources) {
                fields(source, "kind", "feedbackId", "feedbackRevision", "taskId", "observationId", "expectedDecisionRevision", "verification");
                strings(source, "kind", "verification");
                nullableString(source, "feedbackId"); nullableString(source, "taskId"); nullableString(source, "observationId");
                integer(source, "feedbackRevision", true); integer(source, "expectedDecisionRevision", false);
            }
        } else if (type == CreateRecollectionAction.class) {
            fields(body, "expectedRevision", "versionId", "title", "detail", "observation", "verification");
            longInteger(body, "expectedRevision"); strings(body, "versionId", "title", "detail", "observation", "verification");
        } else if (type == SaveAction.class) {
            fields(body, "expectedRevision", "title", "detail");
            longInteger(body, "expectedRevision"); strings(body, "title", "detail");
        } else if (type == SaveSource.class) {
            fields(body, "expectedRevision", "verification", "tracking", "trackingReason");
            longInteger(body, "expectedRevision"); strings(body, "verification", "tracking", "trackingReason");
        } else if (type == MergeAction.class) {
            fields(body, "expectedRevision", "sourceActionId", "sourceExpectedRevision");
            longInteger(body, "expectedRevision"); longInteger(body, "sourceExpectedRevision"); strings(body, "sourceActionId");
        } else throw new IllegalArgumentException("未定义复盘请求类型");
        try { return json.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid("请求字段、类型或枚举不符合复盘接口要求。"); }
    }

    private static void answerShape(JsonNode body) {
        fields(body, "sessionId", "questionId", "answerId"); strings(body, "sessionId", "questionId", "answerId");
    }

    private static void contentShape(JsonNode body) {
        fields(body, "kind", "authorLabel", "dimension", "coverage", "observation", "condition", "basis", "limitation", "suggestion", "citations");
        strings(body, "kind", "authorLabel", "dimension", "coverage", "observation", "condition", "basis", "limitation", "suggestion");
        JsonNode citations = body.get("citations");
        if (!citations.isArray() || citations.size() > 6) throw invalid("引用须为最多 6 项的数组。");
        for (JsonNode citation : citations) {
            fields(citation, "kind", "elementId", "start", "end", "quote");
            strings(citation, "kind", "quote"); nullableString(citation, "elementId");
            integer(citation, "start", false); integer(citation, "end", false);
        }
    }

    private static void fields(JsonNode body, String... names) {
        if (body == null || !body.isObject()) throw invalid("请求及嵌套内容必须为 JSON 对象。");
        Set<String> expected = Set.of(names);
        if (body.size() != expected.size()) throw invalid("请求字段缺失或包含未定义字段。");
        for (var field : body.properties()) if (!expected.contains(field.getKey())) throw invalid("请求包含未定义字段。");
        for (String name : names) if (!body.has(name)) throw invalid("请求缺少必填字段。");
    }

    private static void strings(JsonNode body, String... names) {
        for (String name : names) if (!body.get(name).isTextual()) throw invalid("文本与枚举字段必须为字符串。");
    }

    private static void nullableString(JsonNode body, String name) {
        if (!body.get(name).isNull() && !body.get(name).isTextual()) throw invalid("来源标识必须为字符串或 null。");
    }

    private static void integer(JsonNode body, String name, boolean nullable) {
        JsonNode value = body.get(name);
        if (nullable && value.isNull()) return;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid("修订和引用位置必须为整数。");
    }

    private static void longInteger(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid("动作修订必须为整数。");
    }

    static String uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid("标识必须为标准小写 UUID。"); }
        return value;
    }

    static AnswerLink answer(AnswerLink answer) {
        if (answer == null) throw invalid("请选择确切提交。");
        uuid(answer.sessionId()); uuid(answer.questionId()); uuid(answer.answerId());
        return answer;
    }

    static String text(String value, int maximum, boolean required) {
        if (value == null || value.codePointCount(0, value.length()) > maximum || (required && value.isBlank())
                || value.indexOf('\0') >= 0 || value.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) {
            throw invalid("文字为空、超过长度限制或包含无效 Unicode 字符。");
        }
        return value;
    }

    static void feedbackRevision(Integer revision) {
        if (revision == null || revision < 1 || revision > MAX_FEEDBACK_REVISION) throw invalid("反馈修订必须为 1 至 100 的整数。");
    }

    static void decisionRevision(Integer revision) {
        if (revision == null || revision < 0 || revision > 20) throw invalid("决定修订必须为 0 至 20 的整数。");
    }

    static void actionRevision(Long revision) {
        if (revision == null || revision < 0 || revision > MAX_ACTION_REVISION) throw invalid("动作修订超出有效范围。");
    }

    static void page(int limit, int maximum) {
        if (limit < 1 || limit > maximum) throw invalid("分页条数超出允许范围。");
    }

    static void content(FeedbackContent content, EvaluationInput input) {
        if (content == null || content.kind() == null || content.dimension() == null || content.coverage() == null) throw invalid("反馈类型、维度和观察范围必填。");
        text(content.authorLabel(), 120, content.kind() == FeedbackKind.EXTERNAL_FEEDBACK);
        if (content.kind() == FeedbackKind.SELF_CHECK && !content.authorLabel().isEmpty()) throw invalid("自检不填写外部作者称呼。");
        text(content.observation(), 2000, true); text(content.condition(), 1000, false);
        text(content.basis(), 1000, true); text(content.limitation(), 1000, true); text(content.suggestion(), 1000, true);
        List<EvaluationCitation> citations = content.citations();
        if (citations == null || citations.size() > 6) throw invalid("引用须为最多 6 项的数组。");
        Set<String> locations = new HashSet<>();
        boolean answerEvidence = false;
        for (EvaluationCitation citation : citations) {
            if (citation == null || citation.kind() == null) throw evidence();
            text(citation.quote(), 500, true);
            boolean element = switch (citation.kind()) {
                case ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION, ANSWER_EDGE_LABEL, CHECKPOINT -> true;
                case ANSWER_TEXT, QUESTION_PROMPT, QUESTION_CONDITIONS, REFERENCE -> false;
            };
            if (element) uuid(citation.elementId());
            else if (citation.elementId() != null) throw evidence();
            String original = evidenceText(input, citation);
            if (original == null || citation.start() < 0 || citation.end() <= citation.start() || citation.end() > original.length()
                    || splitSurrogate(original, citation.start()) || splitSurrogate(original, citation.end())
                    || !citation.quote().equals(original.substring(citation.start(), citation.end()))
                    || !locations.add(citation.kind() + ":" + citation.elementId() + ":" + citation.start() + ":" + citation.end())) throw evidence();
            answerEvidence |= switch (citation.kind()) {
                case ANSWER_TEXT, ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION, ANSWER_EDGE_LABEL -> true;
                case QUESTION_PROMPT, QUESTION_CONDITIONS, REFERENCE, CHECKPOINT -> false;
            };
        }
        // 引用只能证明定位合法；缺项观察不能拿一段已提及答案充当不存在的证据。
        if ((content.coverage() == EvaluationCoverage.MENTIONED && !answerEvidence)
                || (content.coverage() == EvaluationCoverage.NOT_MENTIONED && (answerEvidence || citations.isEmpty()))) throw evidence();
    }

    private static String evidenceText(EvaluationInput input, EvaluationCitation citation) {
        return switch (citation.kind()) {
            case ANSWER_TEXT -> input.answer().text();
            case QUESTION_PROMPT -> input.question().prompt();
            case QUESTION_CONDITIONS -> input.question().conditions();
            case REFERENCE -> input.standard() == null ? null : input.standard().referenceAnswer();
            case CHECKPOINT -> input.standard() == null ? null : input.standard().checkpoints().stream()
                    .filter(item -> citation.elementId().equals(item.id())).map(item -> item.text()).findFirst().orElse(null);
            case ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION -> input.answer().nodes().stream()
                    .filter(item -> citation.elementId().equals(item.id()))
                    .map(item -> citation.kind() == EvaluationEvidenceKind.ANSWER_NODE_LABEL ? item.label() : item.description()).findFirst().orElse(null);
            case ANSWER_EDGE_LABEL -> input.answer().edges().stream().filter(item -> citation.elementId().equals(item.id())).map(item -> item.label()).findFirst().orElse(null);
        };
    }

    private static boolean splitSurrogate(String value, int position) {
        return position > 0 && position < value.length() && Character.isHighSurrogate(value.charAt(position - 1))
                && Character.isLowSurrogate(value.charAt(position));
    }

    static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
    static ApiException missing() { return new ApiException(404, "REVIEW_NOT_FOUND", "反馈、动作或确切来源不存在或不可访问。"); }
    static ApiException limit(String detail) { return new ApiException(422, "REVIEW_LIMIT", detail); }
    static ApiException evidence() { return new ApiException(422, "REVIEW_EVIDENCE_INVALID", "引用与确切提交原文、图元素、参考或观察范围不一致。"); }
}
