package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.training.TrainingDtos;
import com.wenjie.studio.training.TrainingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.ai.AiDtos.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TrainingDraftSources {
    private static final Set<String> SOURCE_ERRORS = Set.of(
            "AI_TRAINING_SOURCE_NOT_FOUND", "AI_SOURCE_CHANGED", "AI_SOURCE_UNAVAILABLE", "AI_EVALUATION_ACCESS_REQUIRED");
    private final TrainingMapper training;
    private final AiMapper ai;
    private final EvaluationMapper evaluations;
    private final TrainingEvaluations evaluationAccess;
    private final JsonMapper json;

    public TrainingDraftSources(TrainingMapper training, AiMapper ai, EvaluationMapper evaluations,
                                TrainingEvaluations evaluationAccess, JsonMapper json) {
        this.training = training;
        this.ai = ai;
        this.evaluations = evaluations;
        this.evaluationAccess = evaluationAccess;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
    public TrainingDraftInput input(String owner, TrainingDraftPreviewRequest request) {
        if (!uuid(owner) || request == null || !uuid(request.sessionId()) || !uuid(request.questionId())
                || !uuid(request.answerId()) || !text(request.instruction(), 1000, false)) {
            throw invalid("训练来源标识或整理要求无效。");
        }
        if (!validFragments(request.fragments())) throw invalid("请选择 1 至 8 个合法且不重叠的答案片段。");
        TrainingDraftFeedback selection = request.feedback();
        if (selection != null && (!uuid(selection.taskId()) || selection.feedbackIds() == null
                || selection.feedbackIds().isEmpty() || selection.feedbackIds().size() > 5
                || selection.feedbackIds().stream().anyMatch(id -> !feedbackId(id))
                || new HashSet<>(selection.feedbackIds()).size() != selection.feedbackIds().size())) {
            throw invalid("所选评价须包含同一报告中 1 至 5 个不同反馈标识。");
        }

        var session = training.session(owner, request.sessionId());
        var question = training.question(owner, request.sessionId(), request.questionId());
        var answer = training.answer(owner, request.sessionId(), request.questionId(), request.answerId());
        if (session == null || question == null || answer == null) throw missing();
        if (!Set.of("RUNNING", "PAUSED", "ENDED").contains(session.status())) throw changed();
        boolean hasBankSource = question.sourceQuestionId() != null;
        if (!Set.of("MAIN", "FOLLOW_UP", "REPLAY").contains(question.kind())
                || ("MAIN".equals(question.kind()) && !hasBankSource)
                || ("FOLLOW_UP".equals(question.kind()) && hasBankSource)
                || (hasBankSource ? !uuid(question.sourceQuestionId()) || !uuid(question.sourceVersionId())
                : question.sourceVersionId() != null)) throw broken();
        var snapshot = json.readValue(question.snapshotJson(), TrainingDtos.QuestionSnapshot.class);
        var submitted = json.readValue(answer.contentJson(), TrainingDtos.AnswerContent.class);
        if (snapshot == null || snapshot.content() == null || submitted == null || submitted.diagram() == null
                || submitted.diagram().nodes() == null || submitted.diagram().edges() == null
                || !Objects.equals(snapshot.sourceQuestionId(), question.sourceQuestionId())
                || !Objects.equals(snapshot.sourceVersionId(), question.sourceVersionId())) throw broken();
        var content = snapshot.content();
        var prompt = new FollowUpQuestion(content.title(), content.prompt(), content.conditions(),
                content.technology(), content.scenario(), content.type());
        for (TrainingDraftFragment fragment : request.fragments()) {
            String original = answerText(submitted, fragment.field(), fragment.elementId());
            if (!matches(original, fragment.start(), fragment.end(), fragment.quote())) throw changed();
        }

        EvaluationLevel level = null;
        String limitations = null;
        List<EvaluationObservation> observations = List.of();
        if (selection != null) {
            var task = ai.task(owner, selection.taskId());
            var binding = evaluations.binding(owner, selection.taskId());
            if (task == null || binding == null || !request.sessionId().equals(binding.sessionId())
                    || !request.questionId().equals(binding.questionId()) || !request.answerId().equals(binding.answerId())) {
                throw missing();
            }
            if (!Purpose.ANSWER_EVALUATION.name().equals(task.purpose()) || !Status.SUCCEEDED.name().equals(task.status())
                    || task.candidateJson() == null) throw changed();
            evaluationAccess.requireAccess(owner, request.sessionId(), request.questionId(), request.answerId());
            var report = json.readValue(task.candidateJson(), EvaluationReport.class);
            if (report == null || report.observations() == null || report.level() != EvaluationLevel.AI_UNREVIEWED) {
                throw changed();
            }
            var selected = new ArrayList<EvaluationObservation>();
            for (String id : selection.feedbackIds()) {
                var found = report.observations().stream().filter(item -> item != null && id.equals(item.id())).toList();
                if (found.size() != 1) throw changed();
                selected.add(found.getFirst());
            }
            level = report.level();
            limitations = report.limitations();
            observations = List.copyOf(selected);
        }
        // 只固定历史提交与明确选择的报告正文；会话推进和反馈决定不改变这份来源。
        var input = new TrainingDraftInput(session.id(), question.id(), answer.id(), answer.versionNumber(),
                answer.condition(), question.sourceQuestionId(), question.sourceVersionId(), prompt,
                List.copyOf(request.fragments()), selection == null ? null : selection.taskId(), level, limitations, observations);
        // 请求选区已经独立校验；固定字段或绑定损坏须保留完整性错误，不能被 current 降为来源失效。
        if (!valid(input)) {
            // 旧成功报告允许 NUL；只在同一原文的其余结构均合法时归为不能外发，不能清洗原文或掩盖坏绑定。
            if (valid(input, true)) {
                throw new ApiException(422, "AI_SOURCE_UNAVAILABLE", "所选历史评价含本次整理不支持外发的 NUL 字符，请取消选择该评价或选择其他报告。");
            }
            throw broken();
        }
        return input;
    }

    public boolean current(String owner, TrainingDraftInput input) {
        if (!valid(input)) return false;
        var selection = input.feedbackTaskId() == null ? null : new TrainingDraftFeedback(input.feedbackTaskId(),
                input.feedback().stream().map(EvaluationObservation::id).toList());
        try {
            return input.equals(input(owner, new TrainingDraftPreviewRequest(input.sessionId(), input.questionId(),
                    input.answerId(), "", input.fragments(), selection)));
        } catch (ApiException exception) {
            // 只有可识别的来源失效可转为 false；数据库或反序列化异常必须保留原错误。
            if (SOURCE_ERRORS.contains(exception.code())) return false;
            throw exception;
        }
    }

    public static boolean valid(TrainingDraftInput input) {
        return valid(input, false);
    }

    private static boolean valid(TrainingDraftInput input, boolean allowHistoricalReportNul) {
        if (input == null || !uuid(input.sessionId()) || !uuid(input.questionId()) || !uuid(input.answerId())
                || input.answerNumber() < 1 || input.answerNumber() > 100 || input.answerCondition() == null
                || !Set.of("LEARNING", "BEFORE_ASSISTANCE", "ASSISTED").contains(input.answerCondition())
                || !validFragments(input.fragments()) || input.feedback() == null) return false;
        if (input.sourceQuestionId() == null ? input.sourceVersionId() != null
                : !uuid(input.sourceQuestionId()) || !uuid(input.sourceVersionId())) return false;
        FollowUpQuestion question = input.question();
        if (question == null || question.type() == null || !codePointText(question.title(), 200, true)
                || !codePointText(question.prompt(), 10000, true) || !codePointText(question.conditions(), 5000, false)
                || !codePointText(question.technology(), 2000, false) || !codePointText(question.scenario(), 2000, false)) return false;
        if (input.feedbackTaskId() == null) {
            return input.feedbackLevel() == null && input.feedbackLimitations() == null && input.feedback().isEmpty();
        }
        if (!uuid(input.feedbackTaskId()) || input.feedbackLevel() != EvaluationLevel.AI_UNREVIEWED
                || !codePointText(input.feedbackLimitations(), 2000, true, allowHistoricalReportNul)
                || input.feedback().isEmpty() || input.feedback().size() > 5) return false;
        var ids = new HashSet<String>();
        int total = input.feedbackLimitations().codePointCount(0, input.feedbackLimitations().length());
        for (EvaluationObservation item : input.feedback()) {
            if (!validObservation(item, allowHistoricalReportNul) || !ids.add(item.id())) return false;
            for (String value : List.of(item.observation(), item.condition(), item.basis(), item.limitation(), item.suggestion())) {
                total += value.codePointCount(0, value.length());
            }
            for (EvaluationCitation citation : item.citations()) total += citation.quote().codePointCount(0, citation.quote().length());
        }
        return total <= 24000;
    }

    public static boolean validCitation(TrainingDraftInput input, TrainingDraftCitation citation) {
        if (!valid(input) || citation == null || citation.field() == null || !text(citation.quote(), 1000, true)
                || citation.start() < 0 || citation.end() <= citation.start()) return false;
        if (answerField(citation.field())) {
            // 输出仍用原字段绝对位置；评价自带的引文不会扩大用户选择的答案范围。
            for (TrainingDraftFragment fragment : input.fragments()) {
                if (fragment.field() == citation.field() && Objects.equals(fragment.elementId(), citation.elementId())
                        && citation.start() >= fragment.start() && citation.end() <= fragment.end()) {
                    return matches(fragment.quote(), citation.start() - fragment.start(),
                            citation.end() - fragment.start(), citation.quote());
                }
            }
            return false;
        }
        String original;
        if (citation.field() == TrainingDraftField.QUESTION_PROMPT || citation.field() == TrainingDraftField.QUESTION_CONDITIONS) {
            if (citation.elementId() != null) return false;
            original = citation.field() == TrainingDraftField.QUESTION_PROMPT ? input.question().prompt() : input.question().conditions();
        } else {
            var selected = input.feedback().stream().filter(item -> item.id().equals(citation.elementId())).findFirst().orElse(null);
            if (selected == null) return false;
            original = switch (citation.field()) {
                case FEEDBACK_OBSERVATION -> selected.observation();
                case FEEDBACK_CONDITION -> selected.condition();
                case FEEDBACK_BASIS -> selected.basis();
                case FEEDBACK_LIMITATION -> selected.limitation();
                case FEEDBACK_SUGGESTION -> selected.suggestion();
                default -> null;
            };
        }
        return matches(original, citation.start(), citation.end(), citation.quote());
    }

    private static boolean validFragments(List<TrainingDraftFragment> fragments) {
        if (fragments == null || fragments.isEmpty() || fragments.size() > 8) return false;
        for (int index = 0; index < fragments.size(); index++) {
            TrainingDraftFragment item = fragments.get(index);
            if (item == null || !answerField(item.field()) || item.start() == null || item.end() == null
                    || item.start() < 0 || item.end() <= item.start() || !text(item.quote(), 8000, true)
                    || item.end() - item.start() != item.quote().length()
                    || (item.field() == TrainingDraftField.ANSWER_TEXT ? item.elementId() != null : !uuid(item.elementId()))) return false;
            for (int previous = 0; previous < index; previous++) {
                TrainingDraftFragment other = fragments.get(previous);
                if (item.field() == other.field() && Objects.equals(item.elementId(), other.elementId())
                        && item.start() < other.end() && other.start() < item.end()) return false;
            }
        }
        return true;
    }

    private static boolean validObservation(EvaluationObservation item, boolean allowHistoricalReportNul) {
        if (item == null || !feedbackId(item.id()) || item.dimension() == null || item.coverage() == null
                || !codePointText(item.observation(), 2000, true, allowHistoricalReportNul)
                || !codePointText(item.condition(), 1000, false, allowHistoricalReportNul)
                || !codePointText(item.basis(), 1000, true, allowHistoricalReportNul)
                || !codePointText(item.limitation(), 1000, true, allowHistoricalReportNul)
                || !codePointText(item.suggestion(), 1000, true, allowHistoricalReportNul)
                || item.citations() == null || item.citations().size() > 6) return false;
        var positions = new HashSet<String>();
        boolean answerEvidence = false;
        for (EvaluationCitation citation : item.citations()) {
            if (citation == null || citation.kind() == null || citation.start() < 0 || citation.end() <= citation.start()
                    || !codePointText(citation.quote(), 500, true, allowHistoricalReportNul)
                    || citation.end() - citation.start() != citation.quote().length()) return false;
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
        }
        return switch (item.coverage()) {
            case MENTIONED -> answerEvidence;
            case NOT_MENTIONED -> !answerEvidence && !item.citations().isEmpty();
            case UNCERTAIN -> true;
        };
    }

    private static String answerText(TrainingDtos.AnswerContent content, TrainingDraftField field, String elementId) {
        return switch (field) {
            case ANSWER_TEXT -> content.text();
            case ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION -> content.diagram().nodes().stream()
                    .filter(node -> node != null && elementId.equals(node.id())).findFirst()
                    .map(node -> field == TrainingDraftField.ANSWER_NODE_LABEL ? node.label() : node.description())
                    .orElse(null);
            case ANSWER_EDGE_LABEL -> content.diagram().edges().stream()
                    .filter(edge -> edge != null && elementId.equals(edge.id())).findFirst().map(TrainingDtos.DiagramEdge::label).orElse(null);
            default -> null;
        };
    }

    private static boolean answerField(TrainingDraftField field) {
        return field == TrainingDraftField.ANSWER_TEXT || field == TrainingDraftField.ANSWER_NODE_LABEL
                || field == TrainingDraftField.ANSWER_NODE_DESCRIPTION || field == TrainingDraftField.ANSWER_EDGE_LABEL;
    }

    private static boolean matches(String original, int start, int end, String quote) {
        return original != null && start >= 0 && end > start && end <= original.length()
                && !splitSurrogate(original, start) && !splitSurrogate(original, end)
                && quote.equals(original.substring(start, end));
    }

    private static boolean splitSurrogate(String value, int index) {
        return index > 0 && index < value.length() && Character.isHighSurrogate(value.charAt(index - 1))
                && Character.isLowSurrogate(value.charAt(index));
    }

    private static boolean text(String value, int maximum, boolean nonblank) {
        return value != null && value.length() <= maximum && (!nonblank || !value.isBlank()) && unicode(value);
    }

    private static boolean codePointText(String value, int maximum, boolean nonblank) {
        return codePointText(value, maximum, nonblank, false);
    }

    private static boolean codePointText(String value, int maximum, boolean nonblank, boolean allowHistoricalReportNul) {
        return value != null && value.codePointCount(0, value.length()) <= maximum
                && (!nonblank || !value.isBlank()) && (allowHistoricalReportNul || value.indexOf('\0') < 0)
                && value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
    }

    private static boolean unicode(String value) {
        return value.indexOf('\0') < 0 && value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
    }

    private static boolean feedbackId(String value) { return value != null && value.matches("f([1-9]|1[0-2])"); }

    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
    private static ApiException missing() { return new ApiException(404, "AI_TRAINING_SOURCE_NOT_FOUND", "训练题目、确切提交或所选评价不存在或不可访问。"); }
    private static ApiException changed() { return new ApiException(409, "AI_SOURCE_CHANGED", "所选训练片段或评价已不可用，请重新选择并预览。"); }
    private static IllegalStateException broken() { return new IllegalStateException("确切训练来源的持久结构或固定绑定不完整"); }
}
