package com.wenjie.studio.knowledge;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.ReviewDraftDtos;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.review.ReviewDtos;

import java.time.Instant;
import java.util.List;

/** AI 报告没有正文修订号；证据固定成功尝试、报告摘要和单项决定，保留 AI 未审身份。 */
public final class RelationAiFeedbackEvidenceDtos {
    private RelationAiFeedbackEvidenceDtos() {}

    public record Choice(String taskId, String feedbackId, ReviewDtos.AnswerLink answer) {}
    public record Selection(String taskId, String attemptId, String feedbackId, String reportHash,
                            Integer expectedDecisionRevision, ReviewDtos.AnswerLink answer,
                            List<ReviewDraftDtos.Fragment> fragments) {}
    public record Create(String reason, Selection source, Boolean confirmed) {}
    public record Source(String taskId, String attemptId, String feedbackId, String reportHash,
                         ReviewDtos.AnswerLink answer, int answerNumber, String answerCondition,
                         AiDtos.EvaluationLevel level, String reportLimitations, String validationNote,
                         QuestionDtos.Dimension dimension, AiDtos.EvaluationCoverage coverage,
                         int decisionRevision, AiDtos.FeedbackChoice decisionChoice,
                         String observation, String condition, String basis, String limitation, String suggestion) {}
    public record Snapshot(String taskId, String attemptId, String feedbackId, String reportHash,
                           ReviewDtos.AnswerLink answer, int answerNumber, String answerCondition,
                           AiDtos.EvaluationLevel level, String reportLimitations, String validationNote,
                           QuestionDtos.Dimension dimension, AiDtos.EvaluationCoverage coverage,
                           int decisionRevision, AiDtos.FeedbackChoice decisionChoice,
                           String condition, String basis, String limitation, List<ReviewDraftDtos.Fragment> fragments) {}
    public record Entry(String id, String relationId, String sourceId, String targetId,
                        KnowledgeDtos.RelationType type, String reason, String taskId, String attemptId,
                        String feedbackId, int sourceCount, Instant createdAt) {}
    public record Page(String relationId, boolean relationExists, List<Entry> items, String nextCursor) {}
    public record Detail(Entry entry, boolean relationExists, Snapshot source, AiDtos.OriginState sourceState) {}
}
