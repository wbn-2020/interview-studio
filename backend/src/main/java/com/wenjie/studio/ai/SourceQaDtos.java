package com.wenjie.studio.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.ReviewedObservationDtos;
import com.wenjie.studio.search.UnifiedSearchDtos;
import com.wenjie.studio.training.TrainingDtos;

import java.util.List;

public final class SourceQaDtos {
    private SourceQaDtos() { }

    public record Ref(UnifiedSearchDtos.Domain domain, String id, String versionId, Integer revision,
                      String feedbackId, String reportHash) { }
    public record Range(String field, int start, int end) { }
    public record Selection(Ref source, List<Range> ranges) { }
    public record PreviewRequest(String question, List<Selection> selections) { }
    public record CreateRequest(String question, List<Selection> selections, String expectedManifestHash,
                                Boolean consentConfirmed,
                                @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) { }

    public record Fragment(String field, String label, int start, int end, String text) { }
    public record TrainingContext(ReviewDtos.AnswerLink answer, TrainingDtos.FeedbackTiming feedbackTiming,
                                  ReviewedObservationDtos.RecordedAnswerConditions conditions) { }
    public record FeedbackContext(ReviewDtos.FeedbackKind kind, String authorLabel, QuestionDtos.Dimension dimension,
                                  AiDtos.EvaluationCoverage coverage, String condition, String basis, String limitation,
                                  int decisionRevision, AiDtos.FeedbackChoice decisionChoice) { }
    public record QuestionContext(String conditions, String technology, String scenario) { }
    public record FactContext(String responsibility, String verification, String projectStatus) { }
    public record ExpressionContext(boolean basisReviewed) { }
    public record MaterialContext(String attachmentId, String origin, String sha256, String runId, String segmentId,
                                  String locator, Integer startOffset, Integer endOffset) { }

    // 选区和必要限定同属明确外发正文；状态代次只保存在 Input.checks，不能传给模型。
    public record Source(String sourceKey, Ref ref, String kind, String title, Integer versionNumber,
                         List<Fragment> fragments,
                         @JsonInclude(JsonInclude.Include.NON_NULL) TrainingContext training,
                         @JsonInclude(JsonInclude.Include.NON_NULL) FeedbackContext feedback,
                         @JsonInclude(JsonInclude.Include.NON_NULL) UnifiedSearchDtos.RecollectionContext recollection,
                         @JsonInclude(JsonInclude.Include.NON_NULL) UnifiedSearchDtos.AiFeedbackContext aiFeedback,
                         @JsonInclude(JsonInclude.Include.NON_NULL) QuestionContext question,
                         @JsonInclude(JsonInclude.Include.NON_NULL) FactContext fact,
                         @JsonInclude(JsonInclude.Include.NON_NULL) ExpressionContext expression,
                         @JsonInclude(JsonInclude.Include.NON_NULL) MaterialContext material) { }
    public record Check(String sourceKey, Long metadataRevision, Long sourceRevision,
                        PreparationDtos.SourceCheck preparation, PreparationDtos.ExpressionCheck expression) { }
    public record Input(String question, List<Source> sources, List<Check> checks) { }
    public record Projection(String question, List<Source> sources) { }

    public record Citation(String sourceKey, String field, int start, int end, String quote) { }
    public record Answer(AiDtos.MaterialAnswerState state, String answer, String limitations,
                         List<Citation> citations, String validationNote) { }
}
