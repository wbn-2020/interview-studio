package com.wenjie.studio.review;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.training.TrainingDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.wenjie.studio.review.ReviewedObservationDtos.*;
import static com.wenjie.studio.review.StandardReviewDtos.*;
import static com.wenjie.studio.review.StandardReviewRules.*;

/** 投影只读取固定答案的白名单条件及摘要，来源全文校验由保存时的不可变绑定承载。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
public class ReviewedObservationProjectionSources {
    private final ReviewedObservationProjectionMapper mapper;
    private final ReviewedObservationSources access;
    private final StandardReviewProjectionSources standards;
    private final JsonMapper json;

    public ReviewedObservationProjectionSources(ReviewedObservationProjectionMapper mapper, ReviewedObservationSources access,
                                                 StandardReviewProjectionSources standards, JsonMapper json) {
        this.mapper = mapper;
        this.access = access;
        this.standards = standards;
        this.json = json;
    }

    public Reading read(String owner, ReviewedObservation snapshot, long currentRevision, RecordStatus currentStatus) {
        return read(owner, snapshot, currentRevision, currentStatus, new ReadContext());
    }

    public Reading read(String owner, ReviewedObservation snapshot, long currentRevision, RecordStatus currentStatus, ReadContext context) {
        uuid(owner); ReviewedObservationRules.snapshot(snapshot); revision(currentRevision); status(currentStatus);
        required(context).owner(owner);
        if (currentRevision < snapshot.revision()) throw integrity();
        ReviewDtos.AnswerLink answer = snapshot.answer();
        AnswerData data = context.answers.get(answer);
        if (data == null) {
            access.requireAnswerAccess(owner, answer);
            var found = mapper.answer(owner, answer.sessionId(), answer.questionId(), answer.answerId());
            if (found == null) throw missing();
            data = new AnswerData(found, conditions(owner, answer, found));
            context.answers.put(answer, data);
        }
        var row = data.row();
        RecordedAnswerConditions conditions = data.conditions();
        QuestionVersionRef question = question(new QuestionVersionRef(row.sourceQuestionId(), row.sourceVersionId()));
        StandardKey standardKey = new StandardKey(snapshot.content().standardReview(), snapshot.checkpointId());
        var standard = context.standards.get(standardKey);
        if (standard == null) {
            standard = standard(owner, standardKey.reference(), standardKey.checkpointId(), context.standardSources);
            context.standards.put(standardKey, standard);
        }
        if (!question.equals(snapshot.question()) || !question.equals(standard.question())) throw integrity();
        if (!snapshot.binding().equals(binding(answer, question, snapshot.checkpointId(), snapshot.content().standardReview(),
                row, conditions, standard.standardBasisHash()))) throw integrity();
        if (snapshot.content().observedOn().isBefore(localDate(row.answerCreatedAt(), snapshot.content().observationZoneId()))
                || snapshot.revisedAt().isBefore(row.answerCreatedAt())) throw integrity();

        // 原答、题面及确切标准摘要一致，才沿用该不可变修订在保存时完成的引文核对。
        boolean revisionCurrent = snapshot.revision() == currentRevision;
        var reasons = EnumSet.noneOf(EligibilityReason.class);
        if (!revisionCurrent) reasons.add(EligibilityReason.RECORD_REVISED);
        if (currentStatus != RecordStatus.ACTIVE) reasons.add(EligibilityReason.RECORD_WITHDRAWN);
        if (standard.currentRevision() != snapshot.content().standardReview().revision()) reasons.add(EligibilityReason.STANDARD_REVISED);
        if (standard.currentStatus() != RecordStatus.ACTIVE) reasons.add(EligibilityReason.STANDARD_WITHDRAWN);
        for (EligibilityReason reason : standard.checkpoint().reasons()) reasons.add(switch (reason) {
            case RECORD_REVISED -> EligibilityReason.STANDARD_REVISED;
            case RECORD_WITHDRAWN -> EligibilityReason.STANDARD_WITHDRAWN;
            default -> reason;
        });
        if (snapshot.content().conclusion() == ObservationConclusion.DISPUTED) reasons.add(EligibilityReason.OBSERVATION_DISPUTED);
        ObservationEligibility eligibility = new ObservationEligibility(currentRevision, currentStatus, revisionCurrent,
                standard.currentRevision(), standard.currentStatus(), reasons.isEmpty(), List.copyOf(reasons),
                LineageCheck.PARTIAL, standard.limitations());
        RecordedFields fields = new RecordedFields(row.technologyRecorded(), row.scenarioRecorded(), row.conditionsRecorded());
        return new Reading(conditions, eligibility, standard.basisChecks(), standard.evidenceChecks(), fields);
    }

    private RecordedAnswerConditions conditions(String owner, ReviewDtos.AnswerLink answer, ReviewedObservationProjectionRows.Answer row) {
        try {
            if (!owner.equals(row.ownerId()) || !answer.sessionId().equals(row.sessionId()) || !answer.questionId().equals(row.questionId())
                    || !answer.answerId().equals(row.answerId()) || !row.metadataValid()) throw integrity();
            hashText(row.contentFingerprint()); hashText(row.snapshotFingerprint()); time(row.answerCreatedAt());
            TrainingDtos.Mode mode = TrainingDtos.Mode.valueOf(row.mode());
            TrainingDtos.QuestionKind kind = TrainingDtos.QuestionKind.valueOf(row.questionKind());
            TrainingDtos.AnswerCondition condition = TrainingDtos.AnswerCondition.valueOf(row.answerCondition());
            if (!(kind == TrainingDtos.QuestionKind.MAIN || kind == TrainingDtos.QuestionKind.REPLAY)
                    || row.sourceQuestionId() == null || row.sourceVersionId() == null
                    || row.answerNumber() < 1 || row.answerNumber() > 100
                    || row.assistanceEventSequence() < 0 || row.assistanceEventSequence() > 2000
                    || condition == TrainingDtos.AnswerCondition.ASSISTED && row.assistanceEventSequence() == 0
                    || (condition == TrainingDtos.AnswerCondition.LEARNING) != (mode == TrainingDtos.Mode.LEARNING)
                    || condition == TrainingDtos.AnswerCondition.BEFORE_ASSISTANCE && row.assistanceEventSequence() != 0) throw integrity();
            var exposure = json.readValue(row.exposureJson(), TrainingDtos.PriorExposure.class);
            required(exposure); revision(exposure.authoredCount()); revision(exposure.referenceViewCount()); revision(exposure.evaluationAccessCount());
            if (exposure.lastExposureAt() != null) time(exposure.lastExposureAt());
            // SQL先按原题库上限取短条件；超界不会以截断后的文字参与旧绑定摘要。
            storedQuestionText(row.technology(), 2000); storedQuestionText(row.scenario(), 2000); storedQuestionText(row.questionConditions(), 5000);
            return new RecordedAnswerConditions(mode, condition, row.assistanceEventSequence(), exposure, kind,
                    row.answerNumber(), row.answerCreatedAt(), row.technology(), row.scenario(), row.questionConditions());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("观察投影的固定答案元数据与保存约束不一致", exception);
        }
    }

    private static void storedQuestionText(String value, int maximum) {
        // 题库旧文本允许 NUL；这里只核原字段的非空和码点上限，保留原文参与历史摘要。
        if (value == null || value.codePointCount(0, value.length()) > maximum) throw integrity();
    }

    private StandardReviewProjectionSources.ScopedStandard standard(String owner, RevisionRef reference, String checkpointId,
                                                                     StandardReviewProjectionSources.ReadContext context) {
        StandardReviewProjectionSources.ScopedStandard standard;
        try { standard = standards.read(owner, reference, checkpointId, context); }
        catch (ApiException exception) {
            if (exception.status() == 404 && "STANDARD_REVIEW_NOT_FOUND".equals(exception.code())) throw missing();
            throw exception;
        }
        required(standard); hashText(standard.standardBasisHash()); revision(standard.currentRevision()); status(standard.currentStatus());
        if (standard.currentRevision() < reference.revision() || !checkpointId.equals(standard.checkpoint().checkpointId())
                || standard.checkpoint().eligible() != standard.checkpoint().reasons().isEmpty()) throw integrity();
        return standard;
    }

    private static ObservationBinding binding(ReviewDtos.AnswerLink answer, QuestionVersionRef question, String checkpointId,
                                               RevisionRef standard, ReviewedObservationProjectionRows.Answer row,
                                               RecordedAnswerConditions conditions, String standardBasisHash) {
        String answerHash = hash("reviewed-observation-answer-v1\n", new AnswerFacts(answer, row.answerNumber(), row.answerCreatedAt(),
                row.contentFingerprint(), conditions.mode(), conditions.answerCondition(), row.assistanceEventSequence(), conditions.priorExposure()));
        String questionHash = hash("reviewed-observation-question-v1\n", new QuestionFacts(row.sessionId(), row.questionId(), question,
                conditions.questionKind(), row.snapshotFingerprint()));
        return ReviewedObservationRules.binding(new ObservationBinding(answer, question, checkpointId, answerHash, questionHash, standard, standardBasisHash));
    }

    private static ApiException missing() {
        return new ApiException(404, "REVIEW_SOURCE_NOT_FOUND", "确切提交、题目或审阅来源不存在或不可访问。");
    }

    public record Reading(RecordedAnswerConditions answerConditions, ObservationEligibility eligibility,
                          List<BasisCheck> scopedBasis, List<EvidenceCheck> scopedEvidence, RecordedFields recordedFields) {}
    /** 仅由一次投影批次创建；缓存不跨请求或 owner，也不参与来源摘要。 */
    public static final class ReadContext {
        private String owner;
        private final Map<ReviewDtos.AnswerLink, AnswerData> answers = new HashMap<>();
        private final Map<StandardKey, StandardReviewProjectionSources.ScopedStandard> standards = new HashMap<>();
        private final StandardReviewProjectionSources.ReadContext standardSources = new StandardReviewProjectionSources.ReadContext();

        private void owner(String value) {
            if (owner == null) owner = value;
            else if (!owner.equals(value)) throw integrity();
        }
    }
    private record AnswerData(ReviewedObservationProjectionRows.Answer row, RecordedAnswerConditions conditions) {}
    private record StandardKey(RevisionRef reference, String checkpointId) {}
    private record AnswerFacts(ReviewDtos.AnswerLink answer, int answerNumber, Instant answerCreatedAt, String contentFingerprint,
                               TrainingDtos.Mode mode, TrainingDtos.AnswerCondition answerCondition, int assistanceEventSequence,
                               TrainingDtos.PriorExposure priorExposure) {}
    private record QuestionFacts(String sessionId, String questionId, QuestionVersionRef question,
                                 TrainingDtos.QuestionKind kind, String snapshotFingerprint) {}
}
