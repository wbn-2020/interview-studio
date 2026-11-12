package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static com.wenjie.studio.review.ReviewedObservationDtos.*;
import static com.wenjie.studio.review.StandardReviewDtos.*;
import static com.wenjie.studio.review.StandardReviewRules.*;

/** 观察只定位确切提交及单个检查项；技术结论仍为记录者在所述条件下的判断。 */
public final class ReviewedObservationRules {
    private ReviewedObservationRules() {}

    public static <T> T decode(byte[] raw, Class<T> type) { return StandardReviewRules.decode(raw, type); }

    public static ReviewDtos.AnswerLink answer(ReviewDtos.AnswerLink value) {
        required(value); uuid(value.sessionId()); uuid(value.questionId()); uuid(value.answerId()); return value;
    }
    public static ObservationDraft observationDraft(ObservationDraft value, Instant now) {
        required(value); edit(value.id(), value.expectedRevision(), value.changeReason());
        answer(value.answer()); uuid(value.checkpointId()); content(value.content(), value.checkpointId(), now); return value;
    }
    public static ObservationDraft observationDraft(ObservationDraft value, AiDtos.EvaluationInput input,
                                                     Instant answerCreatedAt, Instant now) {
        observationDraft(value, now); required(input);
        if (!value.answer().equals(new ReviewDtos.AnswerLink(input.sessionId(), input.questionId(), input.answerId()))) throw citationInvalid();
        content(value.content(), value.checkpointId(), input, answerCreatedAt, now); return value;
    }
    public static ObservationContent content(ObservationContent value, String checkpointId, Instant now) {
        required(value); uuid(checkpointId); ref(value.standardReview()); required(value.coverage()); required(value.conclusion());
        text(value.statement(), 2000, true); text(value.conditions(), 2000, true);
        text(value.limitations(), 2000, true); text(value.observerDescription(), 2000, true);
        date(value.observedOn(), value.observationZoneId(), now);
        citations(value.citations(), checkpointId, value.coverage(), value.conclusion()); return value;
    }
    public static ObservationContent content(ObservationContent value, String checkpointId, AiDtos.EvaluationInput input,
                                             Instant answerCreatedAt, Instant now) {
        content(value, checkpointId, now);
        if (value.observedOn().isBefore(localDate(answerCreatedAt, value.observationZoneId()))) throw invalid("观察日期不能早于确切答案提交的当地日期。");
        citations(value.citations(), checkpointId, value.coverage(), value.conclusion(), input); return value;
    }

    public static void citations(List<AiDtos.EvaluationCitation> values, String checkpointId,
                                 AiDtos.EvaluationCoverage coverage, ObservationConclusion conclusion) {
        uuid(checkpointId); required(coverage); required(conclusion); collection(values, 1, 6);
        var seen = new HashSet<String>(); boolean checkpoint = false, answerEvidence = false;
        for (var item : values) {
            required(item.kind()); quoteShape(item.start(), item.end(), item.quote(), 500);
            switch (item.kind()) {
                case CHECKPOINT -> {
                    if (!checkpointId.equals(uuid(item.elementId()))) throw citationInvalid();
                    checkpoint = true;
                }
                case ANSWER_TEXT -> {
                    if (item.elementId() != null) throw citationInvalid();
                    answerEvidence = true;
                }
                case ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION, ANSWER_EDGE_LABEL -> { uuid(item.elementId()); answerEvidence = true; }
                case QUESTION_PROMPT, QUESTION_CONDITIONS, REFERENCE -> throw citationInvalid();
            }
            if (!seen.add(item.kind() + ":" + item.elementId() + ":" + item.start() + ":" + item.end())) throw citationInvalid();
        }
        if (!checkpoint) throw citationInvalid();
        // 未提及只描述缺项，不能用原答引文或正向结论补造不存在的表达。
        if (coverage == AiDtos.EvaluationCoverage.MENTIONED && !answerEvidence
                || coverage == AiDtos.EvaluationCoverage.NOT_MENTIONED && (answerEvidence || conclusion != ObservationConclusion.INSUFFICIENT)
                || coverage == AiDtos.EvaluationCoverage.UNCERTAIN && conclusion != ObservationConclusion.INSUFFICIENT && conclusion != ObservationConclusion.DISPUTED) {
            throw citationInvalid();
        }
    }
    public static void citations(List<AiDtos.EvaluationCitation> values, String checkpointId,
                                 AiDtos.EvaluationCoverage coverage, ObservationConclusion conclusion,
                                 AiDtos.EvaluationInput input) {
        citations(values, checkpointId, coverage, conclusion); required(input); required(input.answer()); required(input.standard());
        for (var item : values) quote(evidenceText(input, item), item.start(), item.end(), item.quote(), 500);
    }
    private static String evidenceText(AiDtos.EvaluationInput input, AiDtos.EvaluationCitation citation) {
        return switch (citation.kind()) {
            case CHECKPOINT -> input.standard().checkpoints().stream()
                    .filter(item -> citation.elementId().equals(item.id())).map(item -> item.text()).findFirst().orElse(null);
            case ANSWER_TEXT -> input.answer().text();
            case ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION -> input.answer().nodes().stream()
                    .filter(item -> citation.elementId().equals(item.id()))
                    .map(item -> citation.kind() == AiDtos.EvaluationEvidenceKind.ANSWER_NODE_LABEL ? item.label() : item.description()).findFirst().orElse(null);
            case ANSWER_EDGE_LABEL -> input.answer().edges().stream().filter(item -> citation.elementId().equals(item.id()))
                    .map(item -> item.label()).findFirst().orElse(null);
            case QUESTION_PROMPT, QUESTION_CONDITIONS, REFERENCE -> throw citationInvalid();
        };
    }

    public static ObservationBinding binding(ObservationBinding value) {
        required(value); answer(value.answer()); question(value.question()); uuid(value.checkpointId());
        hashText(value.answerHash()); hashText(value.questionSnapshotHash()); ref(value.standardReview()); hashText(value.standardBasisHash()); return value;
    }
    public static ReviewedObservation snapshot(ReviewedObservation value) {
        required(value); uuid(value.id()); revision(value.revision()); status(value.status());
        answer(value.answer()); question(value.question()); uuid(value.checkpointId());
        time(value.createdAt()); time(value.revisedAt());
        if (value.revisedAt().isBefore(value.createdAt()) || value.revision() == 0 && (!value.createdAt().equals(value.revisedAt()) || value.status() != RecordStatus.ACTIVE)) throw integrity();
        content(value.content(), value.checkpointId(), value.revisedAt()); binding(value.binding());
        if (!value.answer().equals(value.binding().answer()) || !value.question().equals(value.binding().question())
                || !value.checkpointId().equals(value.binding().checkpointId())
                || !value.content().standardReview().equals(value.binding().standardReview())) throw integrity();
        return value;
    }
    public static ObservationHistory history(ObservationHistory value) {
        required(value); snapshot(value.snapshot());
        historyChange(value.change(), value.snapshot().revision(), value.snapshot().status(), value.changeReason()); return value;
    }
}
