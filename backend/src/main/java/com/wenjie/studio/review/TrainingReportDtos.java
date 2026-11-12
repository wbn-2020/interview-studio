package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.training.TrainingDtos;

import java.time.Instant;
import java.util.List;

public final class TrainingReportDtos {
    private TrainingReportDtos() {}

    public record ContextIdentity(TrainingDtos.ContextKind kind, String itemId, String versionId,
                                  String title, int number) {}
    public record Counts(int submittedQuestionCount, int submissionCount, int noSubmissionCount,
                         int draftQuestionCount) {}
    public record Question(String questionId, String title, String stage, TrainingDtos.QuestionKind kind,
                           TrainingDtos.QuestionStatus status, int answerCount, boolean hasDraft,
                           String latestAnswerId, TrainingDtos.PriorExposure priorExposure) {}
    // 报告只提供身份和统计；正文、个人参考与观察仍从确切提交的受控入口读取。
    public record Report(String sessionId, String title, long sessionRevision, TrainingDtos.Mode mode,
                         TrainingDtos.SessionConfig config, Instant startedAt, Instant endedAt,
                         TrainingDtos.Timing timing, TrainingDtos.EndChecklist endChecklist, Counts counts,
                         List<ContextIdentity> context, List<Question> questions, Instant asOf) {}
    public record Task(String id, AiDtos.Status status, long revision, Instant updatedAt, String errorCode) {}
    public record Submission(String answerId, int number, Instant createdAt, TrainingDtos.AnswerCondition condition,
                             TrainingDtos.PriorExposure priorExposure, boolean accessAllowed,
                             int selfCheckCount, int externalFeedbackCount, int actionSourceCount,
                             int trackingSourceCount, List<Task> aiTasks) {}
    public record SubmissionPage(String sessionId, String questionId, String questionTitle, Instant asOf,
                                 List<Submission> items, Integer nextBefore) {}
}
