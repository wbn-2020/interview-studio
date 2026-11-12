package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos.EvaluationCitation;
import com.wenjie.studio.ai.AiDtos.EvaluationStandard;
import com.wenjie.studio.training.TrainingDtos;

import java.time.Instant;
import java.util.List;

public final class RepracticeDtos {
    private RepracticeDtos() {}

    public enum QuestionMode { SAME, VARIANT }
    public enum Outcome { HANDLED, PARTIAL, UNHANDLED, UNCERTAIN }
    public enum BaselineKind { ANSWER, RECOLLECTION, NONE }

    public record Pick(String actionId, Long expectedActionRevision, String sourceId) {}
    public record Create(ReviewDtos.AnswerLink baseline, String title, Integer timeBudgetMinutes,
                         QuestionMode questionMode, TrainingDtos.QuestionSelection variant,
                         List<TrainingDtos.ContextLink> contextLinks, List<Pick> sources) {}
    public record CreateRecollection(Long expectedRevision, String versionId, String title, Integer timeBudgetMinutes,
                                     QuestionMode questionMode, TrainingDtos.QuestionSelection variant,
                                     List<TrainingDtos.ContextLink> contextLinks, List<Pick> sources) {}
    public record Compare(String answerId) {}
    public record Judge(Integer expectedRevision, String currentActionId, Long expectedActionRevision,
                        Outcome outcome, String reason, List<EvaluationCitation> citations) {}

    // 复练按创建时来源固定验证条件；动作后来的合并、改写和判断不能重写这份依据。
    public record SourceSnapshot(String sourceId, String actionId, long actionRevision,
                                 String actionTitle, ReviewDtos.ActionSource source) {}
    public record Summary(String id, String title, ReviewDtos.AnswerLink baseline, String sessionId,
                          String questionId, QuestionMode questionMode, TrainingDtos.Status status,
                          int sourceCount, Instant createdAt, BaselineKind baselineKind,
                          String recollectionId, String recollectionVersionId) {}
    public record Page(List<Summary> items, String nextCursor) {}
    public record Repractice(String id, ReviewDtos.AnswerLink baseline, String sessionId, String questionId,
                             QuestionMode questionMode, List<SourceSnapshot> sources,
                             TrainingDtos.SessionSummary session, List<ComparisonSummary> comparisons, Instant createdAt,
                             BaselineKind baselineKind, RecollectionBaseline recollectionBaseline) {}
    public record RecollectionBaseline(String recollectionId, long revision, RecollectionDtos.Version version,
                                        RecollectionDtos.SourceState state) {}
    public record ComparisonSummary(String id, String repracticeId, String answerId, int answerNumber, Instant createdAt) {}
    public record Side(ReviewDtos.AnswerLink answer, TrainingDtos.QuestionSnapshot question,
                       TrainingDtos.Answer submission, TrainingDtos.Mode mode, TrainingDtos.SessionConfig config,
                       List<TrainingDtos.ContextSnapshot> context, TrainingDtos.Timing timing, EvaluationStandard standard) {}
    public record Difference(String kind, boolean changed, String explanation) {}
    public record Comparison(ComparisonSummary comparison, Repractice repractice, Side baseline,
                             Side current, List<Difference> differences, List<SourceProgress> progress) {}
    public record Judgment(String id, String sourceId, int revision, String comparisonId, String answerId,
                           String verification, Outcome outcome, String reason,
                           List<EvaluationCitation> citations, Instant createdAt) {}
    public record JudgmentPage(List<Judgment> items, Integer nextBefore) {}
    public record SourceProgress(String sourceId, String actionId, long actionRevision,
                                 String verification, ReviewDtos.Tracking tracking,
                                 Judgment latest, boolean applicable, RecollectionDtos.SourceState recollectionState) {}
    public record ProgressPage(List<SourceProgress> items) {}
}
