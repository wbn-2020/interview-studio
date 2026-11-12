package com.wenjie.studio.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wenjie.studio.review.RepracticeDtos;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.training.TrainingDtos;

import java.util.List;

public final class ComparisonDtos {
    private ComparisonDtos() {}

    public enum EvidenceSide { BASELINE, CURRENT }
    public record PreviewRequest(List<String> sourceIds) {}
    public record Create(List<String> sourceIds, String expectedManifestHash, Boolean consentConfirmed,
                         @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record Side(ReviewDtos.AnswerLink identity, int answerNumber, TrainingDtos.Mode mode,
                       TrainingDtos.FeedbackTiming feedbackTiming, TrainingDtos.AnswerCondition answerCondition,
                       TrainingDtos.PriorExposure priorExposure, Integer timeBudgetMinutes, TrainingDtos.Timing timing,
                       AiDtos.FollowUpQuestion question, AiDtos.FollowUpAnswer answer) {}
    public record Source(String sourceId, String verification) {}
    public record Differences(boolean questionChanged, boolean referenceChanged, boolean contextChanged,
                              boolean modeChanged, boolean assistanceChanged, boolean exposureChanged,
                              boolean timeBudgetChanged, boolean sessionTimeChanged) {}
    public record Input(String comparisonId, Side baseline, Side current, List<Source> sources, Differences differences) {}
    public record SourceCheck(String sourceId, String actionId, long actionRevision) {}
    // 仅在本地核对授权；不能把关系修订和整个业务对象顺带放进模型输入。
    public record Checks(String comparisonId, long baselineGeneration, long currentGeneration, List<SourceCheck> sources) {}
    public record Citation(EvidenceSide side, AiDtos.EvaluationEvidenceKind kind, String elementId,
                           int start, int end, String quote) {}
    public record Suggestion(String sourceId, RepracticeDtos.Outcome suggestedOutcome, String reason,
                             String limitations, List<Citation> citations) {}
    public record Candidate(AiDtos.EvaluationLevel level, String summary, String limitations,
                            List<Suggestion> suggestions, String validationNote) {}
    public record Link(String comparisonId, List<String> sourceIds) {}
}
