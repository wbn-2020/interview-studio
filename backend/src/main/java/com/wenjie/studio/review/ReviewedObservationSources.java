package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.TrainingEvaluations;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.training.TrainingDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import static com.wenjie.studio.review.ReviewedObservationDtos.*;
import static com.wenjie.studio.review.StandardReviewDtos.*;
import static com.wenjie.studio.review.StandardReviewRules.*;

@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
public class ReviewedObservationSources {
    private final ReviewedObservationSourceMapper mapper;
    private final ReviewService reviews;
    private final TrainingEvaluations evaluations;
    private final StandardReviewService standards;
    private final ReviewedBasisInvalidation generations;
    private final JsonMapper json;

    public ReviewedObservationSources(ReviewedObservationSourceMapper mapper, ReviewService reviews,
                                      TrainingEvaluations evaluations, StandardReviewService standards,
                                      ReviewedBasisInvalidation generations, JsonMapper json) {
        this.mapper = mapper;
        this.reviews = reviews;
        this.evaluations = evaluations;
        this.standards = standards;
        this.generations = generations;
        this.json = json;
    }

    public Inspection inspect(String owner, ObservationDraft draft, Instant now) {
        ReviewedObservationRules.observationDraft(draft, now);
        Loaded answer = load(owner, draft.answer());
        QuestionVersionRef question = observationQuestion(answer);
        var standard = standard(owner, draft.content().standardReview());
        CheckpointEligibility checkpoint = checkpoint(standard, question, draft.checkpointId());
        if (!standard.eligibility().revisionCurrent() || standard.eligibility().currentStatus() != RecordStatus.ACTIVE
                || !checkpoint.eligible()) {
            throw new ApiException(409, "STANDARD_REVIEW_NOT_ELIGIBLE", "所选审阅修订及检查项当前不能作为受审观察依据。");
        }
        ReviewedObservationRules.observationDraft(draft, answer.input(), answer.row().answerCreatedAt(), now);
        clock(now, answer.row().answerCreatedAt());
        clock(now, standard.snapshot().revisedAt());
        for (DirectStamp source : standard.snapshot().basis().directSources()) generations.checkClock(owner, source.source(), now);
        return new Inspection(binding(answer, question, draft.checkpointId(), standard.snapshot()), answer.conditions(),
                standard.eligibility(), citationChecks(draft.answer(), question, draft.content().citations(), answer.input()),
                List.of(new ValidationDate(draft.content().observationZoneId(), localDate(now, draft.content().observationZoneId()))),
                standard.eligibility().limitations());
    }

    public Reading read(String owner, ReviewedObservation snapshot, long currentRevision, RecordStatus currentStatus) {
        ReviewedObservationRules.snapshot(snapshot);
        revision(currentRevision); status(currentStatus);
        if (currentRevision < snapshot.revision()) throw integrity();
        Loaded answer = load(owner, snapshot.answer());
        QuestionVersionRef question = observationQuestion(answer);
        var standard = standard(owner, snapshot.content().standardReview());
        CheckpointEligibility checkpoint = checkpoint(standard, question, snapshot.checkpointId());
        if (!snapshot.question().equals(question)
                || !snapshot.binding().equals(binding(answer, question, snapshot.checkpointId(), standard.snapshot()))) throw integrity();
        // 历史日期相对于其保存时刻校验，读取当天不能重写当时的观察合法性。
        try {
            ReviewedObservationRules.content(snapshot.content(), snapshot.checkpointId(), answer.input(),
                    answer.row().answerCreatedAt(), snapshot.revisedAt());
        } catch (ApiException exception) { throw new IllegalStateException("受审观察历史引文或声明日期与确切来源不一致", exception); }

        var reasons = EnumSet.noneOf(EligibilityReason.class);
        boolean revisionCurrent = snapshot.revision() == currentRevision;
        if (!revisionCurrent) reasons.add(EligibilityReason.RECORD_REVISED);
        if (currentStatus != RecordStatus.ACTIVE) reasons.add(EligibilityReason.RECORD_WITHDRAWN);
        if (!standard.eligibility().revisionCurrent()) reasons.add(EligibilityReason.STANDARD_REVISED);
        if (standard.eligibility().currentStatus() != RecordStatus.ACTIVE) reasons.add(EligibilityReason.STANDARD_WITHDRAWN);
        for (EligibilityReason reason : checkpoint.reasons()) reasons.add(switch (reason) {
            case RECORD_REVISED -> EligibilityReason.STANDARD_REVISED;
            case RECORD_WITHDRAWN -> EligibilityReason.STANDARD_WITHDRAWN;
            default -> reason;
        });
        if (snapshot.content().conclusion() == ObservationConclusion.DISPUTED) reasons.add(EligibilityReason.OBSERVATION_DISPUTED);
        var eligibility = new ObservationEligibility(currentRevision, currentStatus, revisionCurrent,
                standard.eligibility().currentRevision(), standard.eligibility().currentStatus(), reasons.isEmpty(),
                List.copyOf(reasons), LineageCheck.PARTIAL, standard.eligibility().limitations());
        return new Reading(answer.conditions(), eligibility);
    }

    public void requireAnswerAccess(String owner, ReviewDtos.AnswerLink answer) {
        uuid(owner); ReviewedObservationRules.answer(answer);
        evaluations.requireAccess(owner, answer.sessionId(), answer.questionId(), answer.answerId());
        var row = mapper.access(owner, answer.sessionId(), answer.questionId(), answer.answerId());
        if (row == null) throw missing();
        identity(owner, answer, row.ownerId(), row.sessionId(), row.questionId(), row.answerId());
        // 撤回和回执恢复只要求原答访问，不读取可能已失效的标准、知识或材料链。
        access(row.sessionStatus(), row.configJson(), row.earlyFeedback(), row.grantedAt());
    }

    private Loaded load(String owner, ReviewDtos.AnswerLink answer) {
        uuid(owner); ReviewedObservationRules.answer(answer);
        AiDtos.EvaluationInput input;
        try { input = reviews.context(owner, answer); }
        catch (ApiException exception) {
            if (exception.status() == 404 && "AI_EVALUATION_NOT_FOUND".equals(exception.code())) throw missing();
            throw exception;
        }
        var row = mapper.answer(owner, answer.sessionId(), answer.questionId(), answer.answerId());
        if (row == null) throw missing();
        identity(owner, answer, row.ownerId(), row.sessionId(), row.questionId(), row.answerId());
        TrainingDtos.SessionConfig config = access(row.sessionStatus(), row.configJson(), row.earlyFeedback(), row.grantedAt());
        try {
            var snapshot = json.readValue(row.snapshotJson(), TrainingDtos.QuestionSnapshot.class);
            var content = json.readValue(row.contentJson(), TrainingDtos.AnswerContent.class);
            var exposure = json.readValue(row.exposureJson(), TrainingDtos.PriorExposure.class);
            TrainingDtos.Mode mode = TrainingDtos.Mode.valueOf(row.mode());
            TrainingDtos.AnswerCondition condition = TrainingDtos.AnswerCondition.valueOf(row.answerCondition());
            TrainingDtos.QuestionKind kind = TrainingDtos.QuestionKind.valueOf(row.questionKind());
            required(snapshot); required(snapshot.content()); required(content); required(content.diagram()); required(exposure);
            required(content.text()); required(content.diagram().nodes()); required(content.diagram().edges());
            time(row.answerCreatedAt());
            if (row.answerNumber() < 1 || row.answerNumber() > 100
                    || row.assistanceEventSequence() < 0 || row.assistanceEventSequence() > 2000
                    || condition == TrainingDtos.AnswerCondition.ASSISTED && row.assistanceEventSequence() == 0
                    || (condition == TrainingDtos.AnswerCondition.LEARNING) != (mode == TrainingDtos.Mode.LEARNING)
                    || condition == TrainingDtos.AnswerCondition.BEFORE_ASSISTANCE && row.assistanceEventSequence() != 0
                    || !Objects.equals(row.sourceQuestionId(), snapshot.sourceQuestionId())
                    || !Objects.equals(row.sourceVersionId(), snapshot.sourceVersionId())) throw integrity();
            revision(exposure.authoredCount()); revision(exposure.referenceViewCount()); revision(exposure.evaluationAccessCount());
            if (exposure.lastExposureAt() != null) time(exposure.lastExposureAt());
            var prompt = snapshot.content();
            AiDtos.FollowUpQuestion questionInput = new AiDtos.FollowUpQuestion(prompt.title(), prompt.prompt(), prompt.conditions(),
                    prompt.technology(), prompt.scenario(), prompt.type());
            AiDtos.FollowUpAnswer answerInput = new AiDtos.FollowUpAnswer(content.text(), content.diagram().nodes().stream()
                    .map(node -> new AiDtos.FollowUpNode(node.id(), node.label(), node.description())).toList(),
                    content.diagram().edges().stream().map(edge -> new AiDtos.FollowUpEdge(edge.id(), edge.source(), edge.target(), edge.label())).toList());
            if (!answer.equals(new ReviewDtos.AnswerLink(input.sessionId(), input.questionId(), input.answerId()))
                    || input.answerNumber() != row.answerNumber() || !mode.name().equals(input.mode())
                    || !condition.name().equals(input.answerCondition()) || !config.feedbackTiming().name().equals(input.feedbackTiming())
                    || !questionInput.equals(input.question()) || !answerInput.equals(input.answer())) throw integrity();
            var conditions = new RecordedAnswerConditions(mode, condition, row.assistanceEventSequence(), exposure, kind,
                    row.answerNumber(), row.answerCreatedAt(), required(prompt.technology()), required(prompt.scenario()), required(prompt.conditions()));
            return new Loaded(answer, row, input, conditions);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("确切答案与固定题面、当时条件或曝光记录不一致", exception);
        }
    }

    private TrainingDtos.SessionConfig access(String status, String configJson, boolean earlyFeedback, Instant grantedAt) {
        if (!List.of("RUNNING", "PAUSED", "ENDED").contains(status)) throw missing();
        TrainingDtos.SessionConfig config = json.readValue(configJson, TrainingDtos.SessionConfig.class);
        if (config == null || config.feedbackTiming() == null || grantedAt == null) throw integrity();
        time(grantedAt);
        if (!"ENDED".equals(status) && config.feedbackTiming() == TrainingDtos.FeedbackTiming.AFTER_SESSION && !earlyFeedback) {
            throw new ApiException(409, "AI_EARLY_FEEDBACK_CONFIRMATION_REQUIRED", "本场约定结束后反馈，请先在原评价入口确认提前查看。");
        }
        return config;
    }

    private static QuestionVersionRef observationQuestion(Loaded answer) {
        var row = answer.row();
        if (!(answer.conditions().questionKind() == TrainingDtos.QuestionKind.MAIN
                || answer.conditions().questionKind() == TrainingDtos.QuestionKind.REPLAY)
                || row.sourceQuestionId() == null || row.sourceVersionId() == null) throw mismatch();
        QuestionVersionRef question = question(new QuestionVersionRef(row.sourceQuestionId(), row.sourceVersionId()));
        var standard = answer.input().standard();
        if (standard == null || !question.questionId().equals(standard.questionId()) || !question.versionId().equals(standard.versionId())) {
            throw mismatch();
        }
        return question;
    }

    private StandardReviewService.ObservationBasis standard(String owner, RevisionRef ref) {
        StandardReviewService.ObservationBasis standard;
        try { standard = standards.observationBasis(owner, ref); }
        catch (ApiException exception) {
            if (exception.status() == 404 && "STANDARD_REVIEW_NOT_FOUND".equals(exception.code())) throw missing();
            throw exception;
        }
        if (!ref.id().equals(standard.snapshot().id()) || ref.revision() != standard.snapshot().revision()
                || standard.eligibility().currentRevision() < ref.revision()
                || standard.eligibility().revisionCurrent() != (standard.eligibility().currentRevision() == ref.revision())) throw integrity();
        return standard;
    }

    private static CheckpointEligibility checkpoint(StandardReviewService.ObservationBasis standard,
                                                     QuestionVersionRef question, String checkpointId) {
        if (!question.equals(standard.snapshot().question()) || standard.snapshot().content().checkpoints().stream()
                .noneMatch(item -> checkpointId.equals(item.checkpointId()))) throw mismatch();
        CheckpointEligibility result = standard.eligibility().checkpoints().stream()
                .filter(item -> checkpointId.equals(item.checkpointId())).findFirst().orElseThrow(StandardReviewRules::integrity);
        if (result.eligible() != result.reasons().isEmpty()) throw integrity();
        return result;
    }

    private static ObservationBinding binding(Loaded answer, QuestionVersionRef question, String checkpointId,
                                               StandardReviewSnapshot standard) {
        var row = answer.row();
        String answerHash = hash("reviewed-observation-answer-v1\n", new AnswerFacts(answer.answer(), row.answerNumber(),
                row.answerCreatedAt(), digest(row.contentJson()), answer.conditions().mode(), answer.conditions().answerCondition(),
                row.assistanceEventSequence(), answer.conditions().priorExposure()));
        String questionHash = hash("reviewed-observation-question-v1\n", new QuestionFacts(row.sessionId(), row.questionId(),
                question, answer.conditions().questionKind(), digest(row.snapshotJson())));
        String standardHash = hash("reviewed-observation-standard-v1\n", standard);
        return ReviewedObservationRules.binding(new ObservationBinding(answer.answer(), question, checkpointId, answerHash, questionHash,
                new RevisionRef(standard.id(), standard.revision()), standardHash));
    }

    private static List<CitationCheck> citationChecks(ReviewDtos.AnswerLink answer, QuestionVersionRef question,
                                                     List<AiDtos.EvaluationCitation> citations, AiDtos.EvaluationInput input) {
        return citations.stream().map(citation -> {
            String original = switch (citation.kind()) {
                case CHECKPOINT -> input.standard().checkpoints().stream().filter(item -> citation.elementId().equals(item.id()))
                        .map(item -> item.text()).findFirst().orElseThrow(StandardReviewRules::integrity);
                case ANSWER_TEXT -> input.answer().text();
                case ANSWER_NODE_LABEL, ANSWER_NODE_DESCRIPTION -> input.answer().nodes().stream()
                        .filter(item -> citation.elementId().equals(item.id()))
                        .map(item -> citation.kind() == AiDtos.EvaluationEvidenceKind.ANSWER_NODE_LABEL ? item.label() : item.description())
                        .findFirst().orElseThrow(StandardReviewRules::integrity);
                case ANSWER_EDGE_LABEL -> input.answer().edges().stream().filter(item -> citation.elementId().equals(item.id()))
                        .map(item -> item.label()).findFirst().orElseThrow(StandardReviewRules::integrity);
                case QUESTION_PROMPT, QUESTION_CONDITIONS, REFERENCE -> throw citationInvalid();
            };
            boolean checkpoint = citation.kind() == AiDtos.EvaluationEvidenceKind.CHECKPOINT;
            return new CitationCheck(checkpoint ? null : answer, checkpoint ? question : null, citation.kind(), citation.elementId(),
                    citation.start(), citation.end(), digest(citation.quote()), digest(original), true);
        }).toList();
    }

    private static void identity(String owner, ReviewDtos.AnswerLink answer, String actualOwner,
                                 String actualSession, String actualQuestion, String actualAnswer) {
        if (!owner.equals(actualOwner) || !answer.sessionId().equals(actualSession)
                || !answer.questionId().equals(actualQuestion) || !answer.answerId().equals(actualAnswer)) throw integrity();
    }

    private static String digest(String value) { return sha256(required(value).getBytes(StandardCharsets.UTF_8)); }
    private static ApiException mismatch() {
        return new ApiException(409, "OBSERVATION_STANDARD_MISMATCH", "确切题目实例没有自身标准，或所选标准版本、检查项不匹配。");
    }
    private static ApiException missing() {
        return new ApiException(404, "REVIEW_SOURCE_NOT_FOUND", "确切提交、题目或审阅来源不存在或不可访问。");
    }

    public record Inspection(ObservationBinding binding, RecordedAnswerConditions answerConditions,
                             StandardEligibility standardEligibility, List<CitationCheck> citationChecks,
                             List<ValidationDate> validationDates, List<ReviewLimitation> limitations) {}
    public record Reading(RecordedAnswerConditions answerConditions, ObservationEligibility eligibility) {}
    public record ValidationDate(String zoneId, LocalDate localDate) {}
    public record CitationCheck(ReviewDtos.AnswerLink answer, QuestionVersionRef question, AiDtos.EvaluationEvidenceKind kind,
                                String elementId, int start, int end, String quoteFingerprint, String fieldFingerprint,
                                boolean quoteMatches) {}
    private record Loaded(ReviewDtos.AnswerLink answer, ReviewedObservationSourceRows.Answer row,
                          AiDtos.EvaluationInput input, RecordedAnswerConditions conditions) {}
    private record AnswerFacts(ReviewDtos.AnswerLink answer, int answerNumber, Instant answerCreatedAt, String contentFingerprint,
                               TrainingDtos.Mode mode, TrainingDtos.AnswerCondition answerCondition, int assistanceEventSequence,
                               TrainingDtos.PriorExposure priorExposure) {}
    private record QuestionFacts(String sessionId, String questionId, QuestionVersionRef question,
                                 TrainingDtos.QuestionKind kind, String snapshotFingerprint) {}
}
