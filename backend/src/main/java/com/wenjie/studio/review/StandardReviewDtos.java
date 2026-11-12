package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class StandardReviewDtos {
    private StandardReviewDtos() {}

    public enum RecordStatus { ACTIVE, WITHDRAWN }
    public enum RecordChange { CREATED, REVISED, WITHDRAWN }
    public enum CheckpointDecision { SUPPORTED_WITHIN_SCOPE, DISPUTED, INSUFFICIENT }
    public enum EvidenceRole { REVIEW_RECORD, TECHNICAL_BASIS }
    public enum BasisKind { KNOWLEDGE, MATERIAL_EXCERPT, EXTERNAL_QUOTE }
    public enum DirectKind { QUESTION, KNOWLEDGE, MATERIAL_ATTACHMENT }
    public enum DirectChangeKind {
        QUESTION_VERSION_CHANGED, QUESTION_ARCHIVED, QUESTION_RESTORED,
        KNOWLEDGE_BODY_CHANGED, KNOWLEDGE_ARCHIVED, KNOWLEDGE_RESTORED, MATERIAL_BASIS_CHANGED,
        KNOWLEDGE_UPSTREAM_CHANGED
    }
    public enum BasisState { AVAILABLE, ARCHIVED, UNAVAILABLE, EXTERNAL_RECORDED }
    public enum EligibilityReason {
        RECORD_WITHDRAWN, RECORD_REVISED, STANDARD_WITHDRAWN, STANDARD_REVISED,
        CHECKPOINT_NOT_SUPPORTED, QUESTION_ARCHIVED, DIRECT_BASIS_CHANGED,
        BASIS_UNAVAILABLE, OBSERVATION_DISPUTED
    }
    public enum ReviewLimitation {
        USER_RECORDED_REVIEW, REVIEWER_IDENTITY_NOT_VERIFIED,
        TECHNICAL_SEMANTICS_NOT_VERIFIED, DIRECT_DEPENDENCIES_ONLY,
        EXTERNAL_CONTENT_NOT_FETCHED, MATERIAL_BYTES_NOT_RECHECKED
    }
    public enum LineageCheck { PARTIAL }
    public record QuestionVersionRef(String questionId, String versionId) {}
    public record RevisionRef(String id, long revision) {}
    public record ReviewerInfo(String label, String description) {}
    public record ExternalQuote(String url, String title, String locator, String quote,
                                LocalDate capturedOn, String sourceDescription) {}
    public record ReviewEvidence(String id, List<EvidenceRole> roles, BasisKind kind,
        AiDtos.Citation knowledge, AiDtos.MaterialCitation material, ExternalQuote external,
        String provenanceNote, String useReason) {}
    public record StandardCheckpointReview(String checkpointId, CheckpointDecision decision,
        String conditions, String conclusion, String limitations, List<String> evidenceIds) {}
    public record StandardReviewContent(ReviewerInfo reviewer, LocalDate reviewedOn,
        String reviewZoneId, String reviewContext, List<StandardCheckpointReview> checkpoints,
        List<ReviewEvidence> evidence, String limitations) {}

    public record DirectRef(DirectKind kind, String id) {}
    public record DirectStamp(DirectRef source, long generation, String currentVersionId,
                              String status, String fingerprint) {}
    public record BasisSeal(QuestionVersionRef question, List<DirectStamp> directSources) {}
    public record StandardReviewSnapshot(String id, QuestionVersionRef question, long revision,
        RecordStatus status, StandardReviewContent content, BasisSeal basis,
        Instant createdAt, Instant revisedAt) {}
    public record StandardReviewHistory(RecordChange change, StandardReviewSnapshot snapshot,
                                         String changeReason) {}
    public record StandardReviewDraft(String id, Long expectedRevision, QuestionVersionRef question,
        String referenceExposureId, StandardReviewContent content, String changeReason) {}
    public record SaveStandardReview(StandardReviewDraft draft, String expectedPreviewHash) {}
    public record WithdrawRecord(Long expectedRevision, String reason) {}
    public record OpenStandardReview(Long revision) {}
    public record OpenedStandardReview(StandardReviewHistory history,
        StandardEligibility eligibility, String exposureId, Instant recordedAt) {}

    public record DirectChange(DirectRef source, long generation, DirectChangeKind kind,
        String previousVersionId, String currentVersionId, String previousStatus,
        String currentStatus, Instant createdAt) {}
    public record BasisCheck(DirectRef source, DirectStamp recorded, DirectStamp current,
        BasisState state, DirectChange firstUnreviewedChange, long upstreamChangeCount) {}
    public record EvidenceCheck(String evidenceId, BasisKind kind, BasisState state,
                                Boolean quoteMatches, DirectChange firstUnreviewedUpstreamChange) {}
    public record CheckpointEligibility(String checkpointId, boolean eligible,
                                         List<EligibilityReason> reasons) {}
    public record StandardEligibility(long currentRevision, RecordStatus currentStatus,
        boolean revisionCurrent, List<BasisCheck> basisChecks, List<EvidenceCheck> evidenceChecks,
        List<CheckpointEligibility> checkpoints, LineageCheck lineageCheck,
        List<ReviewLimitation> limitations) {}
    public record StandardReviewPreview(StandardReviewDraft draft, BasisSeal basis,
        List<EvidenceCheck> evidenceChecks, List<CheckpointEligibility> checkpoints, String previewHash,
        List<ReviewLimitation> limitations) {}
    public record StandardReviewSummary(String id, QuestionVersionRef question, long revision,
        RecordStatus status, String reviewerLabel, LocalDate reviewedOn, String reviewZoneId,
        List<String> checkpointIds, Instant createdAt, Instant revisedAt) {}
    public record StandardReviewDetail(StandardReviewSummary summary, StandardEligibility eligibility) {}
    public record StandardReviewPage(List<StandardReviewDetail> items, String nextCursor) {}
    public record StandardRevisionSummary(long revision, RecordChange change,
        RecordStatus status, Instant revisedAt) {}
    public record StandardHistoryPage(List<StandardRevisionSummary> items, Long nextBefore) {}
}
