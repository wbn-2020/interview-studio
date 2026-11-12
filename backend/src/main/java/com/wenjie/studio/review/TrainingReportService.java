package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.training.TrainingDtos;
import com.wenjie.studio.training.TrainingMapper;
import com.wenjie.studio.training.TrainingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.wenjie.studio.review.TrainingReportDtos.*;
import static com.wenjie.studio.review.ReviewRules.*;

@Service
public class TrainingReportService {
    private final TrainingReportMapper mapper;
    private final TrainingMapper training;
    private final TrainingService sessions;
    private final JsonMapper json;

    public TrainingReportService(TrainingReportMapper mapper, TrainingMapper training,
                                 TrainingService sessions, JsonMapper json) {
        this.mapper = mapper;
        this.training = training;
        this.sessions = sessions;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report report(String owner, String sessionId) {
        requireEnded(owner, sessionId);
        TrainingDtos.Session session = sessions.detail(owner, sessionId);
        Map<String, TrainingReportRows.QuestionCountRow> countsByQuestion = new HashMap<>();
        for (TrainingReportRows.QuestionCountRow row : mapper.questionCounts(owner, sessionId)) {
            if (countsByQuestion.put(row.questionId(), row) != null) throw broken();
        }
        List<Question> questions = new ArrayList<>();
        int submittedQuestions = 0, submissions = 0, drafts = 0;
        for (TrainingDtos.QuestionSummary question : session.questions()) {
            TrainingReportRows.QuestionCountRow count = countsByQuestion.remove(question.id());
            if (count == null || count.answerCount() < 0 || count.answerCount() > 100
                    || ((count.answerCount() == 0) != (count.latestAnswerId() == null))) throw broken();
            if (count.answerCount() > 0) submittedQuestions++;
            submissions += count.answerCount();
            if (question.hasDraft()) drafts++;
            questions.add(new Question(question.id(), question.title(), question.stage(), question.kind(),
                    question.status(), count.answerCount(), question.hasDraft(), count.latestAnswerId(), question.priorExposure()));
        }
        if (!countsByQuestion.isEmpty()) throw broken();
        // 仅投影结束目录的白名单；内部会话携带的背景正文和动态来源检查不进入报告。
        List<ContextIdentity> context = session.context().stream().map(item -> new ContextIdentity(item.kind(),
                item.itemId(), item.versionId(), item.title(), item.number())).toList();
        Counts counts = new Counts(submittedQuestions, submissions, questions.size() - submittedQuestions, drafts);
        return new Report(session.id(), session.title(), session.revision(), session.mode(), session.config(),
                session.startedAt(), session.endedAt(), session.timing(), session.endChecklist(), counts,
                context, List.copyOf(questions), now());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SubmissionPage submissions(String owner, String sessionId, String questionId, Integer before, int limit) {
        uuid(questionId); page(limit, 20);
        if (before != null && (before < 1 || before > 101)) throw invalid("提交游标必须为 1 至 101 的整数。");
        requireEnded(owner, sessionId);
        String title = mapper.questionTitle(owner, sessionId, questionId);
        if (title == null) throw notFound();
        List<TrainingReportRows.SubmissionRow> rows = mapper.submissions(owner, sessionId, questionId, before, limit + 1);
        List<TrainingReportRows.SubmissionRow> selected = rows.stream().limit(limit).toList();
        Map<String, List<Task>> tasksByAnswer = new HashMap<>();
        for (TrainingReportRows.SubmissionRow row : selected) tasksByAnswer.put(row.answerId(), new ArrayList<>());
        if (!selected.isEmpty()) {
            for (TrainingReportRows.TaskRow row : mapper.tasks(owner, sessionId, questionId,
                    selected.stream().map(TrainingReportRows.SubmissionRow::answerId).toList())) {
                List<Task> tasks = tasksByAnswer.get(row.answerId());
                if (tasks == null) throw broken();
                // 原始任务状态只描述执行进度；不在报告读取时恢复任务或推导能力结果。
                tasks.add(new Task(row.id(), AiDtos.Status.valueOf(row.status()), row.revision(), row.updatedAt(), row.errorCode()));
            }
        }
        List<Submission> items = selected.stream().map(row -> new Submission(row.answerId(), row.number(), row.createdAt(),
                TrainingDtos.AnswerCondition.valueOf(row.condition()),
                json.readValue(row.exposureJson(), TrainingDtos.PriorExposure.class), row.accessAllowed(),
                row.selfCheckCount(), row.externalFeedbackCount(), row.actionSourceCount(), row.trackingSourceCount(),
                List.copyOf(tasksByAnswer.get(row.answerId())))).toList();
        return new SubmissionPage(sessionId, questionId, title, now(), items,
                rows.size() > limit ? items.getLast().number() : null);
    }

    private void requireEnded(String owner, String sessionId) {
        var session = training.session(uuid(owner), uuid(sessionId));
        if (session == null) throw notFound();
        if (!TrainingDtos.Status.ENDED.name().equals(session.status())) {
            throw new ApiException(409, "REVIEW_REPORT_NOT_ENDED", "训练结束后才能读取结束报告。");
        }
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static ApiException notFound() { return new ApiException(404, "REVIEW_NOT_FOUND", "训练或题目不存在或不可访问。"); }
    private static IllegalStateException broken() { return new IllegalStateException("结束报告的训练题目与提交记录不一致"); }
}
