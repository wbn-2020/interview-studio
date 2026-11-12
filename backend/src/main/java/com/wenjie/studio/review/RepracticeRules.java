package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos.EvaluationCoverage;
import com.wenjie.studio.ai.AiDtos.EvaluationInput;
import com.wenjie.studio.bank.QuestionDtos.Dimension;
import com.wenjie.studio.training.TrainingDtos;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.review.RepracticeDtos.*;
import static com.wenjie.studio.review.ReviewRules.*;

public final class RepracticeRules {
    static final int MAX_JUDGMENT_REVISION = 1000;
    static final int MAX_SOURCE_BYTES = 196_608;
    static final int MAX_SOURCES_BYTES = 1_048_576;
    static final int MAX_CITATION_BYTES = 32_768;

    private RepracticeRules() {}

    static <T> T decode(JsonMapper json, JsonNode body, Class<T> type) {
        if (type == Create.class || type == CreateRecollection.class) {
            if (type == Create.class) {
                fields(body, "baseline", "title", "timeBudgetMinutes", "questionMode", "variant", "contextLinks", "sources");
                fields(body.get("baseline"), "sessionId", "questionId", "answerId");
                strings(body.get("baseline"), "sessionId", "questionId", "answerId");
            } else {
                fields(body, "expectedRevision", "versionId", "title", "timeBudgetMinutes", "questionMode", "variant", "contextLinks", "sources");
                strings(body, "versionId"); integer(body, "expectedRevision", false, true);
            }
            strings(body, "title", "questionMode"); integer(body, "timeBudgetMinutes", true, false);
            JsonNode variant = body.get("variant");
            if (!variant.isNull()) {
                fields(variant, "sourceQuestionId", "sourceVersionId", "stage");
                strings(variant, "sourceQuestionId", "sourceVersionId", "stage");
            }
            JsonNode context = body.get("contextLinks");
            if (!context.isArray() || context.size() > 12) throw invalid("复练背景须为最多 12 项的数组。");
            for (JsonNode entry : context) {
                fields(entry, "kind", "itemId", "versionId"); strings(entry, "kind", "itemId", "versionId");
            }
            JsonNode sources = body.get("sources");
            if (!sources.isArray() || sources.size() > 10) throw invalid("复练来源须为最多 10 项的数组。");
            for (JsonNode source : sources) {
                fields(source, "actionId", "expectedActionRevision", "sourceId");
                strings(source, "actionId", "sourceId"); integer(source, "expectedActionRevision", false, true);
            }
        } else if (type == Compare.class) {
            fields(body, "answerId"); strings(body, "answerId");
        } else if (type == Judge.class) {
            fields(body, "expectedRevision", "currentActionId", "expectedActionRevision", "outcome", "reason", "citations");
            strings(body, "currentActionId", "outcome", "reason");
            integer(body, "expectedRevision", false, false); integer(body, "expectedActionRevision", false, true);
            JsonNode citations = body.get("citations");
            if (!citations.isArray() || citations.size() > 6) throw invalid("判断引用须为最多 6 项的数组。");
            for (JsonNode citation : citations) {
                fields(citation, "kind", "elementId", "start", "end", "quote"); strings(citation, "kind", "quote");
                if (!citation.get("elementId").isNull() && !citation.get("elementId").isTextual()) throw invalid("图元素标识须为字符串或 null。");
                integer(citation, "start", false, false); integer(citation, "end", false, false);
            }
        } else throw new IllegalArgumentException("未定义复练请求类型");
        try { return json.treeToValue(body, type); }
        catch (RuntimeException exception) { throw invalid("请求字段、类型或枚举不符合复练接口要求。"); }
    }

    static void create(Create request) {
        if (request == null) throw invalid("请选择复练题目模式。");
        answer(request.baseline());
        create(request.title(), request.timeBudgetMinutes(), request.questionMode(), request.variant(), request.contextLinks(), request.sources());
    }

    static void create(CreateRecollection request) {
        if (request == null) throw invalid("请选择回忆版本与复练题目模式。");
        RecollectionRules.revision(request.expectedRevision()); uuid(request.versionId());
        create(request.title(), request.timeBudgetMinutes(), request.questionMode(), request.variant(), request.contextLinks(), request.sources());
    }

    private static void create(String title, Integer timeBudgetMinutes, QuestionMode mode, TrainingDtos.QuestionSelection variant,
                               List<TrainingDtos.ContextLink> contextLinks, List<Pick> picks) {
        if (mode == null) throw invalid("请选择复练题目模式。");
        text(title, 200, true);
        if (timeBudgetMinutes != null && (timeBudgetMinutes < 1 || timeBudgetMinutes > 180)) {
            throw invalid("时间预算须为 1 至 180 分钟或 null。");
        }
        if (mode == QuestionMode.SAME) {
            if (variant != null) throw invalid("同题复练不接收变式题目。");
        } else {
            if (variant == null) throw invalid("变式复练须明确选择题库版本。");
            uuid(variant.sourceQuestionId()); uuid(variant.sourceVersionId());
            if (!"独立复练".equals(variant.stage())) throw invalid("复练阶段固定为独立复练。");
        }
        if (contextLinks == null || contextLinks.size() > 12) throw invalid("最多选择 12 项本次背景。");
        Set<String> contexts = new HashSet<>();
        for (var context : contextLinks) {
            if (context == null || context.kind() == null) throw invalid("背景类型必填。");
            uuid(context.itemId()); uuid(context.versionId());
            if (!contexts.add(context.kind() + ":" + context.itemId())) throw invalid("同一背景不能重复选择。");
        }
        if (picks == null || picks.size() > 10) throw invalid("最多选择 10 条复练来源。");
        Set<String> sources = new HashSet<>();
        for (Pick source : picks) {
            if (source == null) throw invalid("来源不能为空。");
            uuid(source.actionId()); uuid(source.sourceId()); actionRevision(source.expectedActionRevision());
            if (!sources.add(source.sourceId())) throw invalid("同一稳定来源不能重复选择。");
        }
    }

    public static BaselineKind baselineKind(RepracticeRows.RepracticeRow row) {
        if (row == null) throw new IllegalStateException("复练缺少固定基线");
        boolean answer = validUuid(row.baselineSessionId()) && validUuid(row.baselineQuestionId()) && validUuid(row.baselineAnswerId());
        boolean noRecollection = row.recollectionId() == null && row.recollectionVersionId() == null && row.recollectionRevision() == null;
        // 只有完整正式回答身份允许兼容旧行，缺失字段不能推断为无旧答。
        if ((row.baselineKind() == null || BaselineKind.ANSWER.name().equals(row.baselineKind())) && answer && noRecollection) {
            return BaselineKind.ANSWER;
        }
        if ((BaselineKind.RECOLLECTION.name().equals(row.baselineKind()) || BaselineKind.NONE.name().equals(row.baselineKind()))
                && row.baselineSessionId() == null && row.baselineQuestionId() == null && row.baselineAnswerId() == null
                && validUuid(row.recollectionId()) && validUuid(row.recollectionVersionId())
                && row.recollectionRevision() != null && row.recollectionRevision() >= 0 && row.recollectionRevision() <= RecollectionRules.MAX_REVISION) {
            return BaselineKind.valueOf(row.baselineKind());
        }
        throw new IllegalStateException("复练基线类型与确切身份不一致");
    }

    public static boolean matchesBaseline(RepracticeRows.RepracticeRow row, ReviewDtos.ActionSource source) {
        BaselineKind kind = baselineKind(row);
        if (source == null || source.kind() == null) return false;
        if (kind == BaselineKind.ANSWER) {
            return source.kind() != ReviewDtos.SourceKind.RECOLLECTION && source.recollection() == null
                    && new ReviewDtos.AnswerLink(row.baselineSessionId(), row.baselineQuestionId(), row.baselineAnswerId()).equals(source.answer());
        }
        ReviewDtos.RecollectionSource recollection = source.recollection();
        return source.kind() == ReviewDtos.SourceKind.RECOLLECTION && source.answer() == null
                && source.feedbackId() == null && source.feedbackRevision() == null && source.taskId() == null && source.observationId() == null
                && source.manualKind() == null && source.authorLabel() == null && source.observation() == null && source.decision() == null
                && recollection != null && row.recollectionId().equals(recollection.recollectionId())
                && row.recollectionVersionId().equals(recollection.versionId()) && recollection.baselineKind() != null
                && kind.name().equals(recollection.baselineKind().name());
    }

    public static boolean sameSource(ReviewRows.SourceRow current, ReviewDtos.ActionSource fixed, JsonMapper json) {
        if (current == null || fixed == null || fixed.kind() == null || !Objects.equals(current.id(), fixed.id())
                || !Objects.equals(current.originActionId(), fixed.originActionId()) || !fixed.kind().name().equals(current.kind())
                || !Objects.equals(current.feedbackId(), fixed.feedbackId()) || !Objects.equals(current.feedbackRevision(), fixed.feedbackRevision())
                || !Objects.equals(current.taskId(), fixed.taskId()) || !Objects.equals(current.observationId(), fixed.observationId())) return false;
        if (fixed.kind() != ReviewDtos.SourceKind.RECOLLECTION) {
            return fixed.recollection() == null && current.recollectionId() == null && current.recollectionVersionId() == null
                    && current.recollectionJson() == null
                    && new ReviewDtos.AnswerLink(current.sessionId(), current.questionId(), current.answerId()).equals(fixed.answer());
        }
        ReviewDtos.RecollectionSource recollection = fixed.recollection();
        return fixed.answer() == null && recollection != null && current.sessionId() == null && current.questionId() == null
                && current.answerId() == null && current.manualKind() == null && current.authorLabel() == null
                && current.observationJson() == null && current.decisionJson() == null
                && Objects.equals(current.recollectionId(), recollection.recollectionId())
                && Objects.equals(current.recollectionVersionId(), recollection.versionId()) && current.recollectionJson() != null
                && recollection.equals(json.readValue(current.recollectionJson(), ReviewDtos.RecollectionSource.class));
    }

    private static boolean validUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }

    static void judge(Judge request) {
        if (request == null || request.outcome() == null) throw invalid("请选择来源判断。");
        if (request.expectedRevision() == null || request.expectedRevision() < 0 || request.expectedRevision() >= MAX_JUDGMENT_REVISION) {
            throw invalid("判断预期修订须为 0 至 999 的整数。");
        }
        uuid(request.currentActionId()); actionRevision(request.expectedActionRevision()); text(request.reason(), 2000, true);
        if (request.citations() == null || request.citations().size() > 6) throw invalid("判断最多包含 6 处引用。");
        for (var citation : request.citations()) {
            if (citation == null || citation.kind() == null) throw ReviewRules.evidence();
            switch (citation.kind()) {
                case ANSWER_TEXT, ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION, ANSWER_EDGE_LABEL -> { }
                default -> throw ReviewRules.evidence();
            }
        }
    }

    static void evidence(Judge request, EvaluationInput input) {
        // 只复用原文定位与代理对边界规则；判断结果仍是用户结论，不生成评分或整份反馈。
        EvaluationCoverage coverage = request.outcome() == Outcome.HANDLED || request.outcome() == Outcome.PARTIAL
                ? EvaluationCoverage.MENTIONED : EvaluationCoverage.UNCERTAIN;
        ReviewRules.content(new ReviewDtos.FeedbackContent(ReviewDtos.FeedbackKind.SELF_CHECK, "", Dimension.OTHER,
                coverage, request.reason(), "", "逐来源判断", "仅对应所选确切提交", "依据固定验证条件复核", request.citations()), input);
    }

    private static void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject()) throw invalid("请求及嵌套内容必须为 JSON 对象。");
        Set<String> expected = Set.of(names);
        if (node.size() != expected.size()) throw invalid("请求字段缺失或包含未定义字段。");
        for (var field : node.properties()) if (!expected.contains(field.getKey())) throw invalid("请求包含未定义字段。");
        for (String name : names) if (!node.has(name)) throw invalid("请求缺少必填字段。");
    }

    private static void strings(JsonNode node, String... names) {
        for (String name : names) if (!node.get(name).isTextual()) throw invalid("文本与枚举字段必须为字符串。");
    }

    private static void integer(JsonNode node, String name, boolean nullable, boolean longValue) {
        JsonNode value = node.get(name);
        if (nullable && value.isNull()) return;
        if (!value.isIntegralNumber() || (longValue ? !value.canConvertToLong() : !value.canConvertToInt())) {
            throw invalid("预算、修订与引用位置必须为有效整数。");
        }
    }
}
