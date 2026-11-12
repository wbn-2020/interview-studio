package com.wenjie.studio.review;

import com.wenjie.studio.common.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static com.wenjie.studio.review.RecollectionDtos.*;
import static com.wenjie.studio.review.ReviewRules.text;
import static com.wenjie.studio.review.ReviewRules.uuid;

final class RecollectionRules {
    static final long MAX_REVISION = 1000;
    static final int MAX_VERSION = 1000;
    static final int MAX_CONTENT_BYTES = 196_608;
    static final int MAX_INTERVIEW_BYTES = 8192;
    static final long MAX_INTERVIEW_REVISION = 9_007_199_254_740_991L;

    private RecollectionRules() {}

    static <T> T decode(JsonMapper json, JsonNode body, Class<T> type) {
        if (type == Create.class) {
            fields(body, "interview", "content");
            interviewShape(body.get("interview")); contentShape(body.get("content"));
        } else if (type == Save.class) {
            fields(body, "expectedRevision", "interview", "content");
            integer(body.get("expectedRevision"), false, true);
            interviewShape(body.get("interview")); contentShape(body.get("content"));
        } else if (type == ChangeStatus.class) {
            fields(body, "expectedRevision"); integer(body.get("expectedRevision"), false, true);
        } else if (type == CreateQuestion.class) {
            fields(body, "expectedRevision", "versionId", "content");
            integer(body.get("expectedRevision"), false, true); strings(body, "versionId");
            questionShape(body.get("content"));
        } else throw new IllegalArgumentException("未定义面后回忆请求类型");
        try { return json.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid("请求字段、类型或枚举不符合面后回忆接口要求。"); }
    }

    static void content(Content content) {
        if (content == null || content.type() == null || content.baselineKind() == null) {
            throw invalid("回忆内容、题目分类和基线类型必填。");
        }
        text(content.title(), 200, true); text(content.question(), 10_000, true);
        text(content.conditions(), 5000, false);
        text(content.answerFragment(), 20_000, content.baselineKind() == BaselineKind.RECOLLECTION);
        if (content.baselineKind() == BaselineKind.NONE && !content.answerFragment().isEmpty()) {
            throw invalid("无旧答基线的回答片段必须为空字符串；有片段时请明确选择回忆片段。");
        }
        text(content.feedback(), 5000, false); text(content.uncertainty(), 2000, true); text(content.gap(), 2000, false);
    }

    static void interview(InterviewLink link) {
        if (link == null) return;
        uuid(link.eventId());
        if (link.revision() == null || link.revision() < 0 || link.revision() > MAX_INTERVIEW_REVISION) {
            throw invalid("请选择面试安排的确切历史修订。");
        }
    }

    static void revision(Long value) {
        if (value == null || value < 0 || value > MAX_REVISION) throw invalid("预期回忆修订须为 0 至 1000 的整数。");
    }

    static String pattern(String value) {
        if (value == null) return null;
        String query = text(value, 100, false).strip();
        return query.isEmpty() ? null : "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static void contentShape(JsonNode body) {
        fields(body, "title", "question", "type", "conditions", "baselineKind", "answerFragment", "feedback", "uncertainty", "gap");
        strings(body, "title", "question", "type", "conditions", "baselineKind", "answerFragment", "feedback", "uncertainty", "gap");
    }

    private static void interviewShape(JsonNode body) {
        if (body.isNull()) return;
        fields(body, "eventId", "revision"); strings(body, "eventId"); integer(body.get("revision"), false, true);
    }

    private static void questionShape(JsonNode body) {
        // 跨模块仍严格检查原始 JSON 形状；字段业务限制及来源归属由题库公共命令统一核验。
        fields(body, "title", "prompt", "conditions", "technology", "scenario", "type", "difficulty", "dimensions",
                "topicIds", "knowledgeLinks", "tags", "source", "referenceAnswer", "checkpoints");
        strings(body, "title", "prompt", "conditions", "technology", "scenario", "type", "referenceAnswer");
        integer(body.get("difficulty"), true, false);
        textArray(body.get("dimensions"), 1, 6); textArray(body.get("topicIds"), 0, 20); textArray(body.get("tags"), 0, 20);
        JsonNode links = body.get("knowledgeLinks"); array(links, 0, 20);
        for (JsonNode link : links) { fields(link, "itemId", "versionId"); strings(link, "itemId", "versionId"); }
        JsonNode source = body.get("source");
        if (!source.isNull()) { fields(source, "title", "url", "note"); strings(source, "title", "url", "note"); }
        JsonNode checkpoints = body.get("checkpoints"); array(checkpoints, 0, 30);
        for (JsonNode checkpoint : checkpoints) { fields(checkpoint, "id", "text"); strings(checkpoint, "id", "text"); }
    }

    private static void fields(JsonNode body, String... names) {
        if (body == null || !body.isObject()) throw invalid("请求及嵌套内容必须是 JSON 对象。");
        Set<String> expected = Set.of(names);
        if (body.size() != expected.size()) throw invalid("请求字段缺失或包含未定义字段。");
        for (var field : body.properties()) if (!expected.contains(field.getKey())) throw invalid("请求包含未定义字段。");
        for (String name : names) if (!body.has(name)) throw invalid("请求缺少必填字段。");
    }

    private static void strings(JsonNode body, String... names) {
        for (String name : names) {
            JsonNode value = body.get(name);
            if (!value.isTextual()) throw invalid("文本和枚举必须为字符串，空文本请显式传空字符串。");
            text(value.stringValue(), Integer.MAX_VALUE, false);
        }
    }

    private static void integer(JsonNode value, boolean nullable, boolean longValue) {
        if (nullable && value.isNull()) return;
        if (!value.isIntegralNumber() || (longValue ? !value.canConvertToLong() : !value.canConvertToInt())) {
            throw invalid("修订号和难度须为有效整数，不接受字符串或浮点数。");
        }
    }

    private static void array(JsonNode value, int minimum, int maximum) {
        if (!value.isArray() || value.size() < minimum || value.size() > maximum) throw invalid("列表数量或类型不符合要求。");
    }

    private static void textArray(JsonNode value, int minimum, int maximum) {
        array(value, minimum, maximum);
        for (JsonNode entry : value) {
            if (!entry.isTextual()) throw invalid("分类和标识列表只接受字符串。");
            text(entry.stringValue(), Integer.MAX_VALUE, false);
        }
    }

    static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
    static ApiException missing() { return new ApiException(404, "RECOLLECTION_NOT_FOUND", "回忆、确切版本或所选来源不存在或不可访问。"); }
    static ApiException limit() { return new ApiException(409, "REVISION_LIMIT", "回忆修订或内容版本已达到上限。"); }
}
