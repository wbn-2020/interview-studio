package com.wenjie.studio.training;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.training.TrainingDtos.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TrainingInput {
    public static final long MAX_REVISION = 9_007_199_254_740_991L;
    public static final int MAX_CONTEXT_BYTES = 128 * 1024;
    public static final int MAX_ANSWER_BYTES = 192 * 1024;

    private TrainingInput() {}

    public static CreateSession create(CreateSession request) {
        required(request);
        String title = text(request.title(), 200, true, "训练标题");
        Mode mode = required(request.mode());
        FeedbackTiming timing = request.feedbackTiming() == null
                ? (mode == Mode.MOCK ? FeedbackTiming.AFTER_SESSION : FeedbackTiming.AFTER_ANSWER) : request.feedbackTiming();
        if (request.timeBudgetMinutes() != null && (request.timeBudgetMinutes() < 1 || request.timeBudgetMinutes() > 240)) {
            throw invalid("时间预算必须为 1 至 240 分钟或留空。");
        }
        int followUps = request.followUpLimit() == null ? 0 : request.followUpLimit();
        if (followUps < 0 || followUps > 2) throw invalid("后续追问额度必须为 0 至 2。");
        List<String> stages = list(request.stages(), 8, "训练阶段");
        if (stages.isEmpty()) stages = List.of("主问题");
        for (String stage : stages) text(stage, 80, true, "阶段名称");
        distinct(stages, "训练阶段");

        List<QuestionSelection> selected = list(request.questions(), 20, "主问");
        if (selected.isEmpty()) throw invalid("请至少选择一道题目。");
        Set<String> questionIds = new HashSet<>();
        List<QuestionSelection> questions = new ArrayList<>();
        for (QuestionSelection question : selected) {
            uuid(question.sourceQuestionId()); uuid(question.sourceVersionId());
            if (!questionIds.add(question.sourceQuestionId())) throw invalid("同一训练不能重复选择同一道主问。");
            String stage = question.stage() == null ? stages.getFirst() : text(question.stage(), 80, true, "题目阶段");
            if (!stages.contains(stage)) throw invalid("题目阶段必须属于此次训练的阶段列表。");
            questions.add(new QuestionSelection(question.sourceQuestionId(), question.sourceVersionId(), stage));
        }

        List<ContextLink> context = list(request.contextLinks(), 12, "上下文来源");
        Set<String> contextIds = new HashSet<>();
        int targets = 0, projects = 0, facts = 0;
        for (ContextLink link : context) {
            required(link.kind()); uuid(link.itemId()); uuid(link.versionId());
            if (!contextIds.add(link.itemId())) throw invalid("训练上下文不能重复选择同一对象。");
            switch (link.kind()) {
                case TARGET -> targets++;
                case PROJECT -> projects++;
                case FACT -> facts++;
            }
        }
        if (targets > 1 || projects > 1 || facts > 10) throw invalid("上下文最多包含一个目标、一个项目和十条已确认事实。");
        if ((request.collectionId() == null) != (request.collectionVersionId() == null)) {
            throw invalid("题单标识和题单版本必须成对提供。");
        }
        if (request.collectionId() != null) {
            uuid(request.collectionId()); uuid(request.collectionVersionId());
        }
        return new CreateSession(title, mode, timing, request.timeBudgetMinutes(), followUps, List.copyOf(stages),
                List.copyOf(questions), context, request.collectionId(), request.collectionVersionId());
    }

    public static AnswerInput answer(AnswerInput request, boolean submitting) {
        required(request);
        String text = text(request.text(), 20000, false, "答案文字");
        DiagramInput diagram = diagram(request.diagram());
        if (submitting && text.isBlank()) {
            throw new ApiException(422, "INVALID_DIAGRAM", diagram.nodes().isEmpty()
                    ? "提交答案需要文字内容；暂未作答时可保存草稿或跳过。" : "图文答案提交时必须包含文字解释。");
        }
        return new AnswerInput(text, diagram);
    }

    public static DiagramInput diagram(DiagramInput input) {
        if (input == null) return new DiagramInput(List.of(), List.of(), null);
        List<DiagramNodeInput> nodes = diagramList(input.nodes(), 80, "图节点");
        List<DiagramEdge> edges = diagramList(input.edges(), 160, "图连线");
        Set<String> identifiers = new HashSet<>();
        Set<String> nodeIds = new HashSet<>();
        List<DiagramNodeInput> normalizedNodes = new ArrayList<>();
        for (DiagramNodeInput node : nodes) {
            diagramUuid(node.id());
            if (!identifiers.add(node.id())) throw diagramError("图节点和连线标识不能重复。");
            nodeIds.add(node.id());
            coordinate(node.x()); coordinate(node.y());
            DiagramLink link = node.link();
            if (link != null) {
                if (link.kind() == null) throw diagramError("图节点引用类型不能为空。");
                diagramUuid(link.itemId()); diagramUuid(link.versionId());
            }
            normalizedNodes.add(new DiagramNodeInput(node.id(), diagramText(node.label(), 200, true, "节点标签"),
                    diagramText(node.description(), 5000, false, "节点说明"), node.x(), node.y(), link));
        }
        List<DiagramEdge> normalizedEdges = new ArrayList<>();
        for (DiagramEdge edge : edges) {
            diagramUuid(edge.id()); diagramUuid(edge.source()); diagramUuid(edge.target());
            if (!identifiers.add(edge.id())) throw diagramError("图节点和连线标识不能重复。");
            if (!nodeIds.contains(edge.source()) || !nodeIds.contains(edge.target())) throw diagramError("连线端点必须属于本次答案图。");
            normalizedEdges.add(new DiagramEdge(edge.id(), edge.source(), edge.target(),
                    diagramText(edge.label(), 200, false, "连线标签")));
        }
        if (input.boardSource() != null) {
            diagramUuid(input.boardSource().boardId());
            diagramUuid(input.boardSource().importId());
            Long sourceRevision = input.boardSource().revision();
            if (sourceRevision == null || sourceRevision < 0 || sourceRevision > MAX_REVISION) throw diagramError("白板来源修订号无效。");
        }
        return new DiagramInput(List.copyOf(normalizedNodes), List.copyOf(normalizedEdges), input.boardSource());
    }

    public static String uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException exception) { throw invalid("对象标识必须为标准小写 UUID。"); }
    }

    public static void revision(Long value) {
        if (value == null || value < 0 || value > MAX_REVISION) throw invalid("预期修订号无效。");
    }

    public static void limit(int value) {
        if (value < 1 || value > 50) throw invalid("每页数量必须为 1 至 50。");
    }

    public static <T> T required(T value) {
        if (value == null) throw invalid("请求内容或必填字段不能为空。");
        return value;
    }

    public static String text(String value, int maximum, boolean required, String field) {
        String content = value == null ? "" : value;
        if ((required && content.isBlank()) || content.codePointCount(0, content.length()) > maximum) {
            throw invalid(field + "为空或超过长度上限。");
        }
        return content;
    }

    public static ApiException invalid(String message) { return new ApiException(400, "INVALID_REQUEST", message); }
    public static ApiException diagramError(String message) { return new ApiException(422, "INVALID_DIAGRAM", message); }

    private static <T> List<T> list(List<T> values, int maximum, String field) {
        if (values == null) return List.of();
        if (values.size() > maximum || values.stream().anyMatch(value -> value == null)) throw invalid(field + "数量或内容无效。");
        return List.copyOf(values);
    }

    private static <T> List<T> diagramList(List<T> values, int maximum, String field) {
        try { return list(values, maximum, field); }
        catch (ApiException exception) { throw diagramError(exception.getMessage()); }
    }

    private static <T> void distinct(List<T> values, String field) {
        if (new HashSet<>(values).size() != values.size()) throw invalid(field + "不能重复。");
    }

    private static void diagramUuid(String value) {
        try { uuid(value); }
        catch (ApiException exception) { throw diagramError(exception.getMessage()); }
    }

    private static String diagramText(String value, int maximum, boolean required, String field) {
        try { return text(value, maximum, required, field); }
        catch (ApiException exception) { throw diagramError(exception.getMessage()); }
    }

    private static void coordinate(Double value) {
        if (value == null || !Double.isFinite(value) || Math.abs(value) > 1_000_000) {
            throw diagramError("图节点坐标必须为 ±1000000 范围内的有限数值。");
        }
    }
}
