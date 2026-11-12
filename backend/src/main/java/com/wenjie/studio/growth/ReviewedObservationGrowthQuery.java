package com.wenjie.studio.growth;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.ReviewedObservationDtos.ObservationProjection;
import com.wenjie.studio.review.ReviewedObservationProjection;
import com.wenjie.studio.review.StandardReviewDtos.EligibilityReason;
import com.wenjie.studio.review.StandardReviewDtos.RecordChange;
import com.wenjie.studio.review.StandardReviewDtos.RevisionRef;
import com.wenjie.studio.training.TrainingGrowthQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.wenjie.studio.growth.GrowthDtos.*;
import static com.wenjie.studio.growth.GrowthRules.*;
import static com.wenjie.studio.growth.ReviewedObservationGrowthRows.*;

/** 成长只消费原域白名单投影；原答、审阅引文与观察自由文本均不进入本域。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
public class ReviewedObservationGrowthQuery {
    private static final EvidenceKind KIND = EvidenceKind.REVIEWED_OBSERVATION;
    private static final Set<ActivityMetric> METRICS = Set.of(ActivityMetric.REVIEWED_OBSERVATIONS_CREATED,
            ActivityMetric.REVIEWED_OBSERVATIONS_MAINTAINED);
    private final ReviewedObservationGrowthMapper mapper;
    private final ReviewedObservationProjection observations;
    private final TrainingGrowthQuery training;
    private final GrowthEvidenceFilter filter;

    public ReviewedObservationGrowthQuery(ReviewedObservationGrowthMapper mapper, ReviewedObservationProjection observations,
                                         TrainingGrowthQuery training, GrowthEvidenceFilter filter) {
        this.mapper = mapper;
        this.observations = observations;
        this.training = training;
        this.filter = filter;
    }

    public DomainEvidenceWindow page(String owner, Scope scope, AssessmentLevel level, ReviewFlag flag,
                                     EvidencePosition after, QueryBudget budget) {
        uuid(owner); scope(scope); budget(budget); position(after, scope, KIND);
        var items = new ArrayList<EvidenceSummary>();
        Scan scan = scan(owner, scope, after, budget.candidateLimit(), budget.resultLimit(), level, flag, items::add);
        return new DomainEvidenceWindow(List.copyOf(items), scan.scanned(), scan.exhausted(), scan.scanLimited(), scan.last());
    }

    public List<EvidenceResolution> resolve(String owner, List<EvidenceRef> refs) {
        uuid(owner); references(refs);
        var answers = new HashMap<ReviewDtos.AnswerLink, EvidenceResolution>();
        var result = new ArrayList<EvidenceResolution>();
        for (EvidenceRef ref : refs) {
            requireKind(ref);
            try {
                List<ObservationProjection> projected = observations.read(owner, List.of(revisionRef(ref)));
                if (projected == null || projected.size() != 1) throw integrity();
                ObservationProjection projection = projected.getFirst();
                validateProjection(ref, projection);
                result.add(project(owner, ref, projection, answers));
            } catch (ApiException exception) {
                // 只有明确的确切来源缺失转墓碑；权限、停用和数据库错误仍使本次解析失败。
                if (missing(exception)) result.add(unavailable(ref, null, null));
                else throw exception;
            }
        }
        return List.copyOf(result);
    }

    public DomainActivityWindow activity(String owner, Scope scope, Grain grain) {
        uuid(owner); scope(scope); required(grain);
        if (scope.filter().targetMode() != TargetMode.RECORDED_ONLY) throw invalid("受审观察活动只归属于原答保存的目标。");
        var collector = GrowthActivitySupport.collector(scope, grain, METRICS);
        Scan scan = scan(owner, scope, null, MAX_CANDIDATES, Integer.MAX_VALUE, null, null, value -> {
            RecordChange change = value.conditions().reviewed().change();
            ActivityMetric metric = change == RecordChange.CREATED ? ActivityMetric.REVIEWED_OBSERVATIONS_CREATED
                    : ActivityMetric.REVIEWED_OBSERVATIONS_MAINTAINED;
            // 事件按观察修订去重；撤回和失效仍保留真实活动，不叠加原答、复练或训练时长。
            collector.add(metric, evidenceKey(value.ref()), value.occurredAt());
        });
        return collector.finish(scan.scanLimited());
    }

    private Scan scan(String owner, Scope scope, EvidencePosition after, int candidateLimit, int resultLimit,
                      AssessmentLevel level, ReviewFlag flag, Consumer<EvidenceSummary> accept) {
        int scanned = 0, found = 0;
        EvidencePosition last = after;
        var answers = new HashMap<ReviewDtos.AnswerLink, EvidenceResolution>();
        if (!scope.window().startAt().isBefore(scope.window().observedUntil())) return new Scan(0, true, false, last);
        while (scanned < candidateLimit && found < resultLimit) {
            int consume = Math.min(49, Math.min(candidateLimit - scanned, resultLimit - found));
            List<Candidate> candidates = candidates(owner, scope, last, consume + 1);
            int count = Math.min(consume, candidates.size());
            if (count == 0) return new Scan(scanned, true, false, last);
            List<Candidate> consumed = candidates.subList(0, count);
            List<RevisionRef> refs = consumed.stream().map(row -> new RevisionRef(row.id(), row.revision())).toList();
            List<ObservationProjection> projected = observations.read(owner, refs);
            if (projected == null || projected.size() != count) throw integrity();
            for (int i = 0; i < count; i++) {
                Candidate row = consumed.get(i);
                EvidenceRef ref = ref(row.id(), row.revision());
                ObservationProjection projection = projected.get(i);
                validateProjection(ref, projection);
                if (!row.occurredAt().equals(projection.recordedAt())) throw integrity();
                EvidenceResolution resolution = project(owner, ref, projection, answers);
                if (resolution.summary() == null) throw GrowthRules.unavailable();
                EvidenceSummary matched = filter.match(owner, resolution.summary(), scope, level, flag);
                scanned++;
                last = candidatePosition(row);
                if (matched != null) { accept.accept(matched); found++; }
            }
            // 多取的一行只探测后续；每次真正消费的 revision 才占 500 预算和推进游标。
            boolean exhausted = candidates.size() <= count;
            if (exhausted || found == resultLimit || scanned == candidateLimit) {
                return new Scan(scanned, exhausted, !exhausted && scanned == candidateLimit, last);
            }
        }
        throw integrity();
    }

    private List<Candidate> candidates(String owner, Scope scope, EvidencePosition after, int limit) {
        List<Candidate> rows = mapper.candidates(owner, scope.window().startAt(), scope.window().observedUntil(),
                after == null ? null : after.occurredAt(), after == null ? null : after.evidenceKey(), limit);
        if (rows == null || rows.size() > limit) throw integrity();
        EvidencePosition previous = after;
        for (Candidate row : rows) {
            if (row == null || !owner.equals(row.ownerId())) throw integrity();
            try {
                uuid(row.id()); revision(row.revision()); time(row.occurredAt());
                if (!evidenceKey(ref(row.id(), row.revision())).equals(row.evidenceKey())
                        || row.occurredAt().isBefore(scope.window().startAt()) || !row.occurredAt().isBefore(scope.window().observedUntil())) throw integrity();
            } catch (ApiException exception) { throw integrity(); }
            EvidencePosition current = candidatePosition(row);
            if (previous != null && (current.occurredAt().isAfter(previous.occurredAt())
                    || current.occurredAt().equals(previous.occurredAt()) && current.evidenceKey().compareTo(previous.evidenceKey()) <= 0)) throw integrity();
            previous = current;
        }
        return rows;
    }

    private EvidenceResolution project(String owner, EvidenceRef ref, ObservationProjection projection,
                                       Map<ReviewDtos.AnswerLink, EvidenceResolution> answers) {
        ReviewDtos.AnswerLink link = projection.answer();
        EvidenceResolution answer = answers.computeIfAbsent(link,
                value -> training.answer(owner, value.sessionId(), value.questionId(), value.answerId()));
        GrowthRules.resolution(answer);
        if (answer.summary() == null) return unavailable(ref, projection.currentHash(), answer.stamp().currentHash());
        AnswerMeta answerMeta = answer.summary().conditions().answer();
        validateAnswer(projection, answerMeta);
        var meta = new ReviewedMeta(answerMeta, new QuestionRef(projection.question().questionId(), projection.question().versionId()),
                projection.checkpointId(), projection.standardReview(), projection.change(), projection.recordedStatus(), projection.recordedAt(),
                projection.observedOn(), projection.observationZoneId(), projection.answerMeta().answerNumber(), projection.answerMeta().answerCreatedAt(),
                projection.coverage(), projection.conclusion(), projection.eligibility());
        Conditions conditions = new Conditions(KIND, null, null, null, null, null, null, meta);
        EvidenceState state = combined(projection, answer.state());
        String origin = hash("GROWTH_REVIEWED_ORIGIN_V1", new Origin(ref, projection.originHash(), answer.stamp().originHash()));
        String current = hash("GROWTH_REVIEWED_CURRENT_V1", new Current(projection.currentHash(), answer.stamp().currentHash(), state));
        EvidenceStamp stamp = new EvidenceStamp(ref, origin, current);
        // 分类与 SAME／VARIANT 取原答窄读取，不能由观察结论、检查项文字或 questionKind 推断。
        EvidenceSummary summary = new EvidenceSummary(ref, projection.recordedAt(), "受审观察 · 修订 " + ref.revision(),
                RecordKind.REVIEWED_CHECK, AssessmentLevel.REVIEWED_OBSERVATION, conditions, answer.summary().facets(), state, stamp,
                new EvidenceRoute(EvidenceRouteKind.REVIEWED_OBSERVATION, ref, link.sessionId(), link.questionId(), link.answerId(), null, null, null));
        return GrowthRules.resolution(new EvidenceResolution(ref, GrowthRules.summary(summary), state, stamp));
    }

    private static void validateProjection(EvidenceRef ref, ObservationProjection projection) {
        if (projection == null || !revisionRef(ref).equals(projection.ref())) throw integrity();
        hashText(projection.originHash()); hashText(projection.currentHash());
        time(projection.recordedAt()); required(projection.answer()); required(projection.answerMeta());
        required(projection.eligibility()); required(projection.change());
        if ((projection.change() == RecordChange.CREATED) != (ref.revision() == 0)
                || (projection.change() == RecordChange.WITHDRAWN)
                != (projection.recordedStatus() == com.wenjie.studio.review.StandardReviewDtos.RecordStatus.WITHDRAWN)) throw integrity();
    }

    private static void validateAnswer(ObservationProjection projection, AnswerMeta actual) {
        var recorded = projection.answerMeta();
        var link = projection.answer();
        var exposure = recorded.priorExposure();
        if (actual == null || !link.sessionId().equals(actual.sessionId()) || !link.questionId().equals(actual.questionId())
                || !link.answerId().equals(actual.answerId()) || recorded.mode() != actual.mode()
                || recorded.answerCondition() != actual.condition() || recorded.assistanceEventSequence() != actual.assistanceEventSequence()
                || recorded.questionKind() != actual.questionKind() || !new QuestionRef(projection.question().questionId(),
                    projection.question().versionId()).equals(actual.sourceQuestion())
                || !new Exposure(exposure.authoredCount(), exposure.referenceViewCount(), exposure.evaluationAccessCount(),
                    exposure.lastExposureAt()).equals(actual.priorExposure())
                || !new RecordedFields(recorded.recordedFields().technology(), recorded.recordedFields().scenario(),
                    recorded.recordedFields().conditions()).equals(actual.recordedFields())) throw integrity();
    }

    private static EvidenceState combined(ObservationProjection projection, EvidenceState answer) {
        var flags = EnumSet.noneOf(ReviewFlag.class);
        flags.addAll(answer.reviewFlags());
        DirectState direct = answer.directState();
        for (EligibilityReason reason : projection.eligibility().reasons()) {
            switch (reason) {
                case OBSERVATION_DISPUTED -> flags.add(ReviewFlag.DISPUTED);
                case BASIS_UNAVAILABLE -> { flags.add(ReviewFlag.SOURCE_UNAVAILABLE); direct = DirectState.UNAVAILABLE; }
                default -> flags.add(ReviewFlag.REVIEW_REQUIRED);
            }
        }
        if (!flags.isEmpty() && direct == DirectState.AVAILABLE) direct = DirectState.REVIEW_REQUIRED;
        return new EvidenceState(direct, projection.eligibility().revisionCurrent() ? RecordCurrency.CURRENT : RecordCurrency.SUPERSEDED,
                List.copyOf(flags), LineageCheck.PARTIAL);
    }

    private static EvidenceResolution unavailable(EvidenceRef ref, String reviewCurrentHash, String answerCurrentHash) {
        EvidenceState state = new EvidenceState(DirectState.UNAVAILABLE, RecordCurrency.NOT_APPLICABLE,
                List.of(ReviewFlag.SOURCE_UNAVAILABLE), LineageCheck.PARTIAL);
        return GrowthRules.resolution(new EvidenceResolution(ref, null, state, new EvidenceStamp(ref, null,
                hash("GROWTH_REVIEWED_MISSING_V1", new Missing(ref, reviewCurrentHash, answerCurrentHash, state)))));
    }

    private static boolean missing(ApiException exception) {
        return exception.status() == 404 && Set.of("REVIEWED_OBSERVATION_NOT_FOUND", "REVIEW_SOURCE_NOT_FOUND").contains(exception.code());
    }
    private static void requireKind(EvidenceRef ref) { if (evidence(ref).kind() != KIND) throw invalid("受审观察投影只接受确切观察修订。"); }
    private static EvidenceRef ref(String id, long revision) { return evidence(new EvidenceRef(KIND, id, null, revision, null)); }
    private static RevisionRef revisionRef(EvidenceRef ref) { return new RevisionRef(ref.objectId(), ref.revision()); }
    private static EvidencePosition candidatePosition(Candidate row) { return new EvidencePosition(row.occurredAt(), row.evidenceKey()); }
    private record Scan(int scanned, boolean exhausted, boolean scanLimited, EvidencePosition last) {}
    private record Origin(EvidenceRef ref, String reviewOriginHash, String answerOriginHash) {}
    private record Current(String reviewCurrentHash, String answerCurrentHash, EvidenceState state) {}
    private record Missing(EvidenceRef ref, String reviewCurrentHash, String answerCurrentHash, EvidenceState state) {}
}
