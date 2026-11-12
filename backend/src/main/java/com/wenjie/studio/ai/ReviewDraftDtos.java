package com.wenjie.studio.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.review.RecollectionDtos;
import com.wenjie.studio.review.ReviewDtos;

import java.util.List;

public final class ReviewDraftDtos {
    private ReviewDraftDtos() {}

    public enum Kind { SELF_CHECK, EXTERNAL_FEEDBACK, RECOLLECTION }
    public enum Field { OBSERVATION, CONDITION, BASIS, LIMITATION, SUGGESTION,
        QUESTION, CONDITIONS, ANSWER_FRAGMENT, FEEDBACK, UNCERTAINTY, GAP }

    public record Fragment(Field field, Integer start, Integer end, String quote) {}
    public record FeedbackSelection(String feedbackId, Integer feedbackRevision, List<Fragment> fragments) {}
    public record RecollectionSelection(String recollectionId, String versionId, Long expectedRevision,
                                        List<Fragment> fragments) {}
    public record SourceSelection(Kind kind, FeedbackSelection feedback, RecollectionSelection recollection) {}
    public record PreviewRequest(String instruction, SourceSelection source) {}
    public record CreateRequest(String instruction, SourceSelection source, String expectedManifestHash,
                                Boolean consentConfirmed,
                                @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}

    // 人工选区只读取反馈文本与限定，不展开原答、题面或原始引用。
    public record FeedbackText(Kind kind, String feedbackId, int feedbackRevision, ReviewDtos.AnswerLink answer,
                               int answerNumber, String answerCondition, QuestionDtos.Dimension dimension,
                               AiDtos.EvaluationCoverage coverage, int decisionRevision,
                               AiDtos.FeedbackChoice decisionChoice, String observation, String condition,
                               String basis, String limitation, String suggestion) {}

    // 限制、依据和不确定性也是明确预览的外发内容；其他原始引文与安排不随片段展开。
    public record FeedbackInput(String feedbackId, int feedbackRevision, ReviewDtos.AnswerLink answer,
                                int answerNumber, String answerCondition, QuestionDtos.Dimension dimension,
                                AiDtos.EvaluationCoverage coverage, int decisionRevision,
                                AiDtos.FeedbackChoice decisionChoice, String condition, String basis,
                                String limitation, List<Fragment> fragments) {}
    public record RecollectionInput(String recollectionId, String versionId, int versionNumber,
                                    long sourceRevision, String title, QuestionDtos.Type type,
                                    RecollectionDtos.BaselineKind baselineKind, String uncertainty,
                                    List<Fragment> fragments) {}
    public record Input(Kind kind, FeedbackInput feedback, RecollectionInput recollection) {}
    public record Citation(Field field, int start, int end, String quote) {}

    public record FeedbackOrigin(String feedbackId, int feedbackRevision, ReviewDtos.AnswerLink answer,
                                 int answerNumber, int decisionRevision, AiDtos.FeedbackChoice decisionChoice) {}
    public record RecollectionOrigin(String recollectionId, String versionId, int versionNumber,
                                     long sourceRevision, String title, RecollectionDtos.BaselineKind baselineKind) {}
    public record Origin(Kind kind, FeedbackOrigin feedback, RecollectionOrigin recollection,
                         AiDtos.OriginState state) {}
}
