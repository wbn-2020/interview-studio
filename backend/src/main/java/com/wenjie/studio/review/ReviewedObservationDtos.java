package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.training.TrainingDtos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.wenjie.studio.review.StandardReviewDtos.*;

public final class ReviewedObservationDtos {
    private ReviewedObservationDtos() {}

    public enum ObservationConclusion { SUPPORTS_CHECKPOINT, CONTRADICTS_CHECKPOINT, INSUFFICIENT, DISPUTED }
    public record ObservationContent(RevisionRef standardReview,
        AiDtos.EvaluationCoverage coverage, ObservationConclusion conclusion,
        String statement, String conditions, String limitations, String observerDescription,
        LocalDate observedOn, String observationZoneId, List<AiDtos.EvaluationCitation> citations) {}
    public record ObservationDraft(String id, Long expectedRevision, ReviewDtos.AnswerLink answer,
        String checkpointId, ObservationContent content, String changeReason) {}
    public record SaveObservation(ObservationDraft draft, String expectedPreviewHash) {}
    public record RecordedAnswerConditions(TrainingDtos.Mode mode,
        TrainingDtos.AnswerCondition answerCondition, int assistanceEventSequence,
        TrainingDtos.PriorExposure priorExposure, TrainingDtos.QuestionKind questionKind,
        int answerNumber, Instant answerCreatedAt, String technology, String scenario,
        String questionConditions) {}
    public record ObservationBinding(ReviewDtos.AnswerLink answer, QuestionVersionRef question,
        String checkpointId, String answerHash, String questionSnapshotHash,
        RevisionRef standardReview, String standardBasisHash) {}
    public record ReviewedObservation(String id, long revision, RecordStatus status,
        ReviewDtos.AnswerLink answer, QuestionVersionRef question, String checkpointId,
        ObservationContent content, ObservationBinding binding, Instant createdAt, Instant revisedAt) {}
    public record ObservationEligibility(long currentRevision, RecordStatus currentStatus,
        boolean revisionCurrent, long standardCurrentRevision, RecordStatus standardCurrentStatus,
        boolean eligibleAsReviewedObservation, List<EligibilityReason> reasons,
        LineageCheck lineageCheck, List<ReviewLimitation> limitations) {}
    public record ObservationPreview(ObservationDraft draft, ObservationBinding binding,
        RecordedAnswerConditions answerConditions, StandardEligibility standardEligibility,
        String previewHash, List<ReviewLimitation> limitations) {}
    public record ObservationDetail(ReviewedObservation snapshot,
        RecordedAnswerConditions answerConditions, ObservationEligibility eligibility) {}
    public record ObservationHistory(RecordChange change, ReviewedObservation snapshot, String changeReason) {}
    public record ObservationSummary(String id, long revision, RecordStatus status,
        ReviewDtos.AnswerLink answer, QuestionVersionRef question, String checkpointId,
        RevisionRef standardReview, AiDtos.EvaluationCoverage coverage,
        ObservationConclusion conclusion, LocalDate observedOn, String observationZoneId,
        Instant createdAt, Instant revisedAt, ObservationEligibility eligibility) {}
    public record ObservationPage(List<ObservationSummary> items, String nextCursor) {}
    public record ObservationHistoryPage(List<ObservationHistory> items, Long nextBefore) {}

    public record RecordedFields(boolean technology, boolean scenario, boolean conditions) {}
    public record ObservationAnswerMeta(TrainingDtos.Mode mode,
        TrainingDtos.AnswerCondition answerCondition, int assistanceEventSequence,
        TrainingDtos.PriorExposure priorExposure, TrainingDtos.QuestionKind questionKind,
        int answerNumber, Instant answerCreatedAt, RecordedFields recordedFields) {}
    public record ObservationProjection(RevisionRef ref, RecordChange change,
        RecordStatus recordedStatus, ReviewDtos.AnswerLink answer, QuestionVersionRef question,
        String checkpointId, RevisionRef standardReview, Instant recordedAt,
        LocalDate observedOn, String observationZoneId, AiDtos.EvaluationCoverage coverage,
        ObservationConclusion conclusion, ObservationAnswerMeta answerMeta,
        ObservationEligibility eligibility, String originHash, String currentHash) {}
}
