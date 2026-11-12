package com.wenjie.studio.ai;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.bank.QuestionMapper;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.training.TrainingDtos;
import com.wenjie.studio.training.TrainingMapper;
import com.wenjie.studio.training.TrainingRows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.ai.AiDtos.*;

@Service
public class TrainingEvaluations {
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private final TrainingMapper training;
    private final QuestionMapper bank;
    private final EvaluationMapper evaluations;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public TrainingEvaluations(TrainingMapper training, QuestionMapper bank, EvaluationMapper evaluations,
                               IdempotencyService commands, JsonMapper json) {
        this.training = training;
        this.bank = bank;
        this.evaluations = evaluations;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EvaluationAccess access(String owner, String sessionId, String questionId, String answerId) {
        var session = session(owner, sessionId);
        answer(owner, sessionId, questionId, answerId);
        var saved = evaluations.access(owner, sessionId, questionId, answerId);
        return new EvaluationAccess(sessionId, questionId, answerId, saved != null,
                saved == null ? early(session) : saved.earlyFeedback(), saved == null ? null : saved.grantedAt());
    }

    public EvaluationAccess grantAccess(String owner, String sessionId, String questionId, String key, EvaluationAccessRequest request) {
        uuid(sessionId); uuid(questionId);
        if (request == null || !Boolean.TRUE.equals(request.confirmed()) || request.expectedSessionRevision() == null
                || request.expectedSessionRevision() < 0 || request.expectedSessionRevision() > MAX_SAFE) throw invalid();
        uuid(request.answerId());
        return commands.execute(owner, key, "POST /api/v1/training/sessions/" + sessionId + "/questions/"
                + questionId + "/evaluations/access", request, EvaluationAccess.class, () -> {
            var session = session(owner, sessionId);
            answer(owner, sessionId, questionId, request.answerId());
            if (evaluations.access(owner, sessionId, questionId, request.answerId()) != null) {
                return access(owner, sessionId, questionId, request.answerId());
            }
            if (session.revision() != request.expectedSessionRevision()) {
                throw new ApiException(409, "REVISION_CONFLICT", "训练已变化，请核对最新状态后重新确认。");
            }
            boolean early = early(session);
            if (early && !Boolean.TRUE.equals(request.earlyFeedbackConfirmed())) {
                throw new ApiException(409, "AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED", "本场约定结束后反馈，请明确确认提前查看及后续辅助条件。");
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            if (!"ENDED".equals(session.status()) && !"LEARNING".equals(session.mode()) && !session.materialAccess()) {
                if (session.eventCount() >= 1999 || session.revision() >= MAX_SAFE) {
                    throw new ApiException(422, "TRAINING_LIMIT", "训练记录已达上限，请结束训练后查看评价。");
                }
                int sequence = session.eventCount() + 1;
                training.insertEvent(owner, sessionId, new TrainingDtos.Event(UUID.randomUUID().toString(), sequence,
                        TrainingDtos.EventKind.MATERIAL_ACCESS, questionId,
                        early ? "已确认提前打开评价区；之后提交按辅助条件记录。" : "已确认打开评价区；之后提交按辅助条件记录。", null, now));
                changed(training.incrementEventCount(owner, sessionId, session.eventCount()));
                changed(training.grantAssistance(owner, sessionId, session.revision(), sequence, now));
            }
            // 结束后仍要保存曝光，使用独立记录，不消耗已经封闭的训练事件额度。
            evaluations.insertAccess(new EvaluationRows.AccessRow(owner, sessionId, questionId, request.answerId(), early, now));
            var question = question(owner, sessionId, questionId);
            var source = "FOLLOW_UP".equals(question.kind()) ? question(owner, sessionId, question.rootQuestionId()) : question;
            if (source.sourceQuestionId() != null) {
                bank.insertExposure(owner, UUID.randomUUID().toString(), source.sourceQuestionId(), source.sourceVersionId(),
                        QuestionDtos.ExposureKind.EVALUATION_ACCESS.name(), now);
            }
            return new EvaluationAccess(sessionId, questionId, request.answerId(), true, early, now);
        });
    }

    public TrainingRows.SessionRow session(String owner, String sessionId) {
        uuid(owner); uuid(sessionId);
        var row = training.session(owner, sessionId);
        if (row == null || !Set.of("RUNNING", "PAUSED", "ENDED").contains(row.status())) throw missing();
        return row;
    }

    public EvaluationInput input(String owner, String sessionId, String questionId, String answerId) {
        var session = session(owner, sessionId);
        var question = question(owner, sessionId, questionId);
        var answer = answer(owner, sessionId, questionId, answerId);
        requireAccess(owner, sessionId, questionId, answerId);
        var snapshot = json.readValue(question.snapshotJson(), TrainingDtos.QuestionSnapshot.class).content();
        var content = json.readValue(answer.contentJson(), TrainingDtos.AnswerContent.class);
        EvaluationStandard standard = null;
        boolean hasBankSource = question.sourceQuestionId() != null;
        if (!Set.of("MAIN", "FOLLOW_UP", "REPLAY").contains(question.kind())
                || ("MAIN".equals(question.kind()) && !hasBankSource)
                || ("FOLLOW_UP".equals(question.kind()) && hasBankSource)
                || (hasBankSource != (question.sourceVersionId() != null))) throw missing();
        // 同题复练只使用自身保留的确切参考；历史追问重做仍无标准，不能借用旧根主问的参考。
        if (hasBankSource) {
            var reference = bank.reference(owner, question.sourceQuestionId(), question.sourceVersionId());
            var version = bank.version(owner, question.sourceQuestionId(), question.sourceVersionId());
            if (reference == null || version == null) throw missing();
            standard = new EvaluationStandard(question.sourceQuestionId(), question.sourceVersionId(), version.standardStatus(),
                    reference.referenceAnswer(), List.copyOf(Arrays.asList(json.readValue(reference.checkpointsJson(), QuestionDtos.Checkpoint[].class))));
        }
        // 不展开训练背景、附件或图引用；只分析用户此刻明确预览的确切题目与提交。
        return new EvaluationInput(sessionId, questionId, answerId, answer.versionNumber(), session.mode(),
                json.readValue(session.configJson(), TrainingDtos.SessionConfig.class).feedbackTiming().name(), answer.condition(),
                new FollowUpQuestion(snapshot.title(), snapshot.prompt(), snapshot.conditions(), snapshot.technology(), snapshot.scenario(), snapshot.type()),
                new FollowUpAnswer(content.text(), content.diagram().nodes().stream()
                        .map(node -> new FollowUpNode(node.id(), node.label(), node.description())).toList(),
                        content.diagram().edges().stream().map(edge -> new FollowUpEdge(edge.id(), edge.source(), edge.target(), edge.label())).toList()), standard);
    }

    public boolean current(String owner, EvaluationInput input) {
        if (input == null) return false;
        try { return input.equals(input(owner, input.sessionId(), input.questionId(), input.answerId())); }
        catch (ApiException exception) { return false; }
    }

    public void requireAvailable(String owner, EvaluationInput input, String excludedTaskId) {
        if (!current(owner, input)) throw new ApiException(409, "AI_SOURCE_CHANGED", "确切评价输入已不可用，请重新核对。");
        if ((excludedTaskId == null && evaluations.taskCount(owner, input.answerId()) >= 3)
                || evaluations.pendingCount(owner, input.answerId(), excludedTaskId) > 0) {
            throw new ApiException(409, "AI_EVALUATION_LIMIT", "这次提交已有未决任务或已达到 3 个评价任务上限，请查看已有记录。");
        }
    }

    public void requireAccess(String owner, String sessionId, String questionId, String answerId) {
        if (evaluations.access(owner, sessionId, questionId, answerId) == null) {
            throw new ApiException(409, "AI_EVALUATION_ACCESS_REQUIRED", "请先从确切提交确认打开评价区，核对反馈时机与辅助条件。");
        }
    }

    public EvaluationLink binding(String owner, String taskId, EvaluationInput input) {
        var row = evaluations.binding(owner, taskId);
        if (row == null || input == null || !row.sessionId().equals(input.sessionId())
                || !row.questionId().equals(input.questionId()) || !row.answerId().equals(input.answerId())) throw missing();
        requireAccess(owner, row.sessionId(), row.questionId(), row.answerId());
        return new EvaluationLink(row.sessionId(), row.questionId(), row.answerId(), input.answerNumber());
    }

    public List<FeedbackDecision> decisions(String owner, String taskId) { return evaluations.decisions(owner, taskId); }

    public void decide(String owner, String taskId, EvaluationReport report, String feedbackId, DecideFeedback request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("反馈决定必须在 owner 锁写事务内追加");
        }
        if (report == null || feedbackId == null || report.observations().stream().noneMatch(item -> item.id().equals(feedbackId))) throw missing();
        if (request == null || request.choice() == null || request.expectedRevision() == null
                || request.expectedRevision() < 0 || request.expectedRevision() > 20) throw invalid();
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.codePointCount(0, reason.length()) > 1000 || (request.choice() == FeedbackChoice.DISPUTED && reason.isBlank())) {
            throw new ApiException(422, "AI_LIMIT", "决定原因最多 1000 个字符；提出争议时请填写原因。");
        }
        int revision = decisions(owner, taskId).stream().filter(item -> feedbackId.equals(item.feedbackId()))
                .mapToInt(FeedbackDecision::revision).max().orElse(0);
        if (revision != request.expectedRevision()) throw new ApiException(409, "FEEDBACK_REVISION_CONFLICT", "此项决定已更新，输入已保留，请核对历史后重试。");
        if (revision >= 20) throw new ApiException(422, "AI_LIMIT", "此项反馈已达 20 次决定记录上限。");
        evaluations.insertDecision(owner, taskId, new FeedbackDecision(UUID.randomUUID().toString(), feedbackId, revision + 1,
                request.choice(), reason, Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private TrainingRows.QuestionRow question(String owner, String sessionId, String questionId) {
        uuid(questionId);
        var row = training.question(owner, sessionId, questionId);
        if (row == null) throw missing();
        return row;
    }

    private TrainingRows.AnswerRow answer(String owner, String sessionId, String questionId, String answerId) {
        uuid(questionId); uuid(answerId);
        var row = training.answer(owner, sessionId, questionId, answerId);
        if (row == null) throw missing();
        return row;
    }

    private boolean early(TrainingRows.SessionRow session) {
        return !"ENDED".equals(session.status()) && json.readValue(session.configJson(), TrainingDtos.SessionConfig.class)
                .feedbackTiming() == TrainingDtos.FeedbackTiming.AFTER_SESSION;
    }
    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static void changed(int count) { if (count != 1) throw new IllegalStateException("评价确认事务中的受锁记录发生变化"); }
    private static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "评价标识、修订或确认字段不符合要求。"); }
    private static ApiException missing() { return new ApiException(404, "AI_EVALUATION_NOT_FOUND", "训练、确切提交或评价记录不存在或不可访问。"); }
}
