package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos.EvaluationCitation;
import com.wenjie.studio.ai.AiDtos.EvaluationCoverage;
import com.wenjie.studio.ai.AiDtos.EvaluationObservation;
import com.wenjie.studio.ai.AiDtos.FeedbackChoice;
import com.wenjie.studio.bank.QuestionDtos.Dimension;

import java.time.Instant;
import java.util.List;

public final class ReviewDtos {
    private ReviewDtos() {}

    public enum FeedbackKind { SELF_CHECK, EXTERNAL_FEEDBACK }
    public enum SourceKind { MANUAL, AI_EVALUATION, RECOLLECTION }
    public enum ActionStatus { OPEN, MERGED }
    public enum Tracking { TRACKING, DROPPED }

    public record AnswerLink(String sessionId, String questionId, String answerId) {}
    public record FeedbackContent(FeedbackKind kind, String authorLabel, Dimension dimension,
                                  EvaluationCoverage coverage, String observation, String condition,
                                  String basis, String limitation, String suggestion,
                                  List<EvaluationCitation> citations) {}
    public record CreateFeedback(AnswerLink answer, FeedbackContent content) {}
    public record SaveFeedback(Integer expectedRevision, FeedbackContent content) {}
    public record DecideFeedback(Integer feedbackRevision, Integer expectedRevision, FeedbackChoice choice, String reason) {}
    public record Decision(String id, int revision, FeedbackChoice choice, String reason, Instant createdAt) {}
    public record Feedback(String id, AnswerLink answer, int revision, FeedbackContent content,
                           List<Decision> decisions, Instant createdAt, Instant revisedAt) {}
    public record FeedbackSummary(String id, AnswerLink answer, int revision, FeedbackKind kind,
                                  String observation, Instant updatedAt) {}
    public record FeedbackPage(List<FeedbackSummary> items, String nextCursor) {}
    public record FeedbackHistoryPage(List<Feedback> items, Integer nextBefore) {}

    public record SourceRequest(SourceKind kind, String feedbackId, Integer feedbackRevision,
                                String taskId, String observationId, Integer expectedDecisionRevision,
                                String verification) {}
    public record CreateAction(String title, String detail, List<SourceRequest> sources) {}
    public record SaveAction(Long expectedRevision, String title, String detail) {}
    public record SaveSource(Long expectedRevision, String verification, Tracking tracking, String trackingReason) {}
    public record MergeAction(Long expectedRevision, String sourceActionId, Long sourceExpectedRevision) {}
    public record CreateRecollectionAction(Long expectedRevision, String versionId, String title, String detail,
                                           String observation, String verification) {}
    public record RecollectionSource(String recollectionId, String versionId, int versionNumber,
                                     RecollectionDtos.BaselineKind baselineKind, String observation) {}

    // 来源身份与当时观察保持不变；验证条件和跟踪变更进入动作修订，供后续复练固定引用。
    public record ActionSource(String id, String originActionId, SourceKind kind, AnswerLink answer,
                               String feedbackId, Integer feedbackRevision, String taskId, String observationId,
                               FeedbackKind manualKind, String authorLabel, EvaluationObservation observation,
                               Decision decision, String verification, Tracking tracking, String trackingReason,
                               Instant createdAt, Instant updatedAt, RecollectionSource recollection) {}
    public record Action(String id, long revision, String title, String detail, ActionStatus status,
                         String mergedInto, List<ActionSource> sources, Instant createdAt, Instant updatedAt) {}
    public record ActionSummary(String id, long revision, String title, ActionStatus status, String mergedInto,
                                int sourceCount, int trackingCount, Instant updatedAt) {}
    public record ActionPage(List<ActionSummary> items, String nextCursor) {}
    public record ActionRevision(String change, Action snapshot) {}
    public record ActionHistoryPage(List<ActionRevision> items, Long nextBefore) {}
}
