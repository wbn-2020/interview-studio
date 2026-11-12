package com.wenjie.studio.review;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static com.wenjie.studio.review.ReviewedObservationDtos.*;
import static com.wenjie.studio.review.StandardReviewDtos.*;
import static com.wenjie.studio.review.StandardReviewRules.*;

/** 成长仅取得确切修订的元数据和摘要；正文与直接来源核验留在审阅域。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
public class ReviewedObservationProjection {
    private final ReviewedObservationService observations;
    private final ReviewedObservationProjectionSources sources;

    public ReviewedObservationProjection(ReviewedObservationService observations, ReviewedObservationProjectionSources sources) {
        this.observations = observations;
        this.sources = sources;
    }

    public List<ObservationProjection> read(String owner, List<RevisionRef> references) {
        uuid(owner); collection(references, 1, 50);
        var seen = new HashSet<RevisionRef>();
        for (RevisionRef reference : references) {
            ref(reference);
            if (!seen.add(reference)) throw invalid("观察投影不能重复选择同一确切修订。");
        }
        var values = new ArrayList<ObservationProjection>(references.size());
        var context = new ReviewedObservationProjectionSources.ReadContext();
        for (RevisionRef reference : references) {
            var stored = observations.projectionBasis(owner, reference);
            ObservationHistory history = stored.history();
            ReviewedObservation snapshot = history.snapshot();
            if (!reference.id().equals(snapshot.id()) || reference.revision() != snapshot.revision()) throw integrity();
            var reading = sources.read(owner, snapshot, stored.currentRevision(), stored.currentStatus(), context);
            RecordedAnswerConditions conditions = reading.answerConditions();
            var answerMeta = new ObservationAnswerMeta(conditions.mode(), conditions.answerCondition(), conditions.assistanceEventSequence(),
                    conditions.priorExposure(), conditions.questionKind(), conditions.answerNumber(), conditions.answerCreatedAt(), reading.recordedFields());
            String original = hash("reviewed-observation-projection-origin-v1\n", new Origin(history, conditions));
            ObservationEligibility eligibility = reading.eligibility();
            String current = hash("reviewed-observation-projection-current-v1\n", new Current(reference, original,
                    stored.currentRevision(), stored.currentStatus(), eligibility.standardCurrentRevision(), eligibility.standardCurrentStatus(),
                    snapshot.checkpointId(), reading.scopedBasis(), reading.scopedEvidence(), eligibility));
            // 当前核对只影响独立资格；旧修订的记录时间、结论和来源身份保持原值。
            values.add(new ObservationProjection(reference, history.change(), snapshot.status(), snapshot.answer(), snapshot.question(),
                    snapshot.checkpointId(), snapshot.content().standardReview(), snapshot.revisedAt(), snapshot.content().observedOn(),
                    snapshot.content().observationZoneId(), snapshot.content().coverage(), snapshot.content().conclusion(),
                    answerMeta, eligibility, original, current));
        }
        return List.copyOf(values);
    }

    private record Origin(ObservationHistory exactHistory, RecordedAnswerConditions fixedConditions) {}
    private record Current(RevisionRef ref, String originHash, long observationCurrentRevision, RecordStatus observationCurrentStatus,
                           long standardCurrentRevision, RecordStatus standardCurrentStatus, String checkpointId,
                           List<BasisCheck> scopedBasis, List<EvidenceCheck> scopedEvidence, ObservationEligibility eligibility) {}
}
