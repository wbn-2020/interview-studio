package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.ai.AiDtos.*;

/**
 * 改写只读取一次确切提交，并通过独立绑定保证任务不会借用旧评价记录的身份。
 * 来源失效可以重新预览；持久化损坏必须作为完整性错误继续抛出。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class RewriteSources {
    private static final Set<String> SOURCE_ERRORS = Set.of(
            "AI_EVALUATION_NOT_FOUND", "AI_SOURCE_CHANGED", "AI_EVALUATION_ACCESS_REQUIRED");

    private final TrainingEvaluations evaluations;
    private final RewriteMapper mapper;

    public RewriteSources(TrainingEvaluations evaluations, RewriteMapper mapper) {
        this.evaluations = evaluations;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
    public EvaluationInput input(String owner, String sessionId, String questionId, String answerId) {
        requireUuid(owner);
        requireUuid(sessionId);
        requireUuid(questionId);
        requireUuid(answerId);
        EvaluationInput input = evaluations.input(owner, sessionId, questionId, answerId);
        if (!valid(input)) {
            throw new ApiException(422, "AI_SOURCE_UNAVAILABLE",
                    "原答、题面或个人参考包含不支持外发的字符或结构，请先更正并选择新的确切提交。");
        }
        return input;
    }

    public boolean current(String owner, EvaluationInput input) {
        if (!valid(input)) return false;
        try {
            return input.equals(input(owner, input.sessionId(), input.questionId(), input.answerId()));
        } catch (ApiException exception) {
            // 仅来源当前性失败可以转为 false，数据库、JSON 或绑定损坏不能伪装成普通失效。
            if (SOURCE_ERRORS.contains(exception.code())) return false;
            throw exception;
        }
    }

    public void requireAvailable(String owner, EvaluationInput input, String excludedTaskId) {
        if (!current(owner, input)) {
            throw new ApiException(409, "AI_SOURCE_CHANGED", "确切改写输入已不可用，请重新核对题目、提交与个人参考。");
        }
        requireUuid(owner);
        if (excludedTaskId != null) {
            requireUuid(excludedTaskId);
            RewriteRows.BindingRow binding = mapper.binding(owner, excludedTaskId);
            if (binding == null || !owner.equals(binding.ownerId()) || !excludedTaskId.equals(binding.taskId())
                    || !input.sessionId().equals(binding.sessionId()) || !input.questionId().equals(binding.questionId())
                    || !input.answerId().equals(binding.answerId())) throw bindingBroken();
        }
        if ((excludedTaskId == null && mapper.taskCount(owner, input.answerId()) >= 3)
                || mapper.pendingCount(owner, input.answerId(), excludedTaskId) > 0) {
            throw new ApiException(409, "AI_REWRITE_LIMIT", "这份提交已有在途改写任务或已达到 3 个改写任务上限，请查看已有记录。");
        }
    }

    public EvaluationLink binding(String owner, String taskId, EvaluationInput input) {
        requireUuid(owner);
        requireUuid(taskId);
        if (!valid(input)) throw bindingBroken();
        RewriteRows.BindingRow saved = mapper.binding(owner, taskId);
        if (saved == null || !owner.equals(saved.ownerId()) || !taskId.equals(saved.taskId())
                || !input.sessionId().equals(saved.sessionId()) || !input.questionId().equals(saved.questionId())
                || !input.answerId().equals(saved.answerId()) || saved.createdAt() == null) throw bindingBroken();
        evaluations.requireAccess(owner, saved.sessionId(), saved.questionId(), saved.answerId());
        return new EvaluationLink(saved.sessionId(), saved.questionId(), saved.answerId(), input.answerNumber());
    }

    public void assertBinding(String owner, String taskId, Manifest manifest) {
        RewriteRows.BindingRow saved = mapper.binding(owner, taskId);
        if (manifest == null || manifest.purpose() != Purpose.ANSWER_REWRITE) {
            if (saved != null) throw bindingBroken();
            return;
        }
        if (saved == null || !owner.equals(saved.ownerId()) || !taskId.equals(saved.taskId())
                || manifest.instruction() == null || !manifest.instruction().isEmpty()
                || manifest.sources() == null || !manifest.sources().isEmpty()
                || manifest.training() != null || manifest.contextChecks() != null || manifest.materialQa() != null
                || manifest.preparationChecks() != null || manifest.materialDraftSources() != null
                || manifest.expressionType() != null || manifest.preparationPlan() != null
                || manifest.trainingDraft() != null || manifest.expressionChecks() != null
                || manifest.comparisonInput() != null || manifest.comparisonChecks() != null
                || manifest.reviewDraft() != null || manifest.cardDraftType() != null || manifest.factDraft() != null
                || !"answer-rewrite-1".equals(manifest.promptVersion())
                || !valid(manifest.evaluation())) throw bindingBroken();
        EvaluationLink link = binding(owner, taskId, manifest.evaluation());
        if (!saved.sessionId().equals(link.sessionId()) || !saved.questionId().equals(link.questionId())
                || !saved.answerId().equals(link.answerId())) throw bindingBroken();
    }

    public static boolean valid(EvaluationInput input) {
        if (input == null || !uuid(input.sessionId()) || !uuid(input.questionId()) || !uuid(input.answerId())
                || input.answerNumber() < 1 || input.answerNumber() > 100
                || input.mode() == null || input.feedbackTiming() == null || input.answerCondition() == null
                || !Set.of("LEARNING", "FOCUSED", "MOCK").contains(input.mode())
                || !Set.of("AFTER_ANSWER", "AFTER_SESSION").contains(input.feedbackTiming())
                || !Set.of("LEARNING", "BEFORE_ASSISTANCE", "ASSISTED").contains(input.answerCondition())
                || ("LEARNING".equals(input.mode()) != "LEARNING".equals(input.answerCondition()))) return false;
        FollowUpQuestion question = input.question();
        if (question == null || question.type() == null || !text(question.title(), 200, true)
                || !text(question.prompt(), 10000, true) || !text(question.conditions(), 5000, false)
                || !text(question.technology(), 2000, false) || !text(question.scenario(), 2000, false)) return false;
        FollowUpAnswer answer = input.answer();
        if (answer == null || !text(answer.text(), 20000, true) || answer.nodes() == null || answer.nodes().size() > 80
                || answer.edges() == null || answer.edges().size() > 160) return false;
        Set<String> ids = new HashSet<>(), nodeIds = new HashSet<>();
        for (FollowUpNode node : answer.nodes()) {
            if (node == null || !uuid(node.id()) || !ids.add(node.id()) || !text(node.label(), 200, true)
                    || !text(node.description(), 5000, false)) return false;
            nodeIds.add(node.id());
        }
        for (FollowUpEdge edge : answer.edges()) {
            if (edge == null || !uuid(edge.id()) || !ids.add(edge.id()) || !uuid(edge.source())
                    || !uuid(edge.target()) || !nodeIds.contains(edge.source()) || !nodeIds.contains(edge.target())
                    || !text(edge.label(), 200, false)) return false;
        }
        EvaluationStandard standard = input.standard();
        if (standard == null) return true;
        if (!uuid(standard.questionId()) || !uuid(standard.versionId())
                || !"PERSONAL_UNREVIEWED".equals(standard.status()) || !text(standard.referenceAnswer(), 20000, false)
                || standard.checkpoints() == null || standard.checkpoints().size() > 30) return false;
        Set<String> checkpointIds = new HashSet<>();
        for (var checkpoint : standard.checkpoints()) {
            if (checkpoint == null || !uuid(checkpoint.id()) || !checkpointIds.add(checkpoint.id())
                    || !text(checkpoint.text(), 500, true)) return false;
        }
        return true;
    }

    private static boolean text(String value, int maximum, boolean required) {
        return value != null && value.codePointCount(0, value.length()) <= maximum
                && (!required || !value.isBlank()) && value.indexOf('\0') < 0
                && value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
    }

    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private static void requireUuid(String value) {
        if (!uuid(value)) throw new ApiException(400, "INVALID_REQUEST", "改写来源标识必须为标准小写 UUID。");
    }

    private static IllegalStateException bindingBroken() {
        return new IllegalStateException("改写任务与确切评价来源绑定不一致");
    }
}
