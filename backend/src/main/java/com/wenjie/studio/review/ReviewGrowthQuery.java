package com.wenjie.studio.review;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.AiGrowthQuery;
import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.growth.GrowthActivitySupport;
import com.wenjie.studio.growth.GrowthEvidenceFilter;
import com.wenjie.studio.growth.GrowthRules;
import com.wenjie.studio.today.TodayDtos;
import com.wenjie.studio.training.TrainingGrowthQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.wenjie.studio.growth.GrowthDtos.*;
import static com.wenjie.studio.review.ReviewGrowthRows.*;

/** 历史事件和当前处理进度分别查询；任何当前状态都不能覆盖旧版身份与发生时间。 */
@Component
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
public class ReviewGrowthQuery {
    private static final Set<EvidenceKind> KINDS = Set.of(EvidenceKind.MANUAL_FEEDBACK,
            EvidenceKind.RECOLLECTION, EvidenceKind.REPLAY_JUDGMENT);
    private static final Set<ActivityMetric> METRICS = EnumSet.of(ActivityMetric.FEEDBACK_CREATED,
            ActivityMetric.FEEDBACK_REVISED, ActivityMetric.RECOLLECTION_VERSIONS_RECORDED,
            ActivityMetric.REPLAYS_STARTED, ActivityMetric.SAME_REPLAYS_STARTED,
            ActivityMetric.VARIANT_REPLAYS_STARTED, ActivityMetric.REPLAY_JUDGMENTS_RECORDED);
    private final ReviewGrowthMapper mapper;
    private final TrainingGrowthQuery training;
    private final AiGrowthQuery ai;
    private final GrowthEvidenceFilter filter;

    public ReviewGrowthQuery(ReviewGrowthMapper mapper, TrainingGrowthQuery training,
                             AiGrowthQuery ai, GrowthEvidenceFilter filter) {
        this.mapper = mapper;
        this.training = training;
        this.ai = ai;
        this.filter = filter;
    }

    public DomainEvidenceWindow page(String owner, EvidenceKind kind, Scope scope,
                                     AssessmentLevel level, ReviewFlag flag,
                                     EvidencePosition after, QueryBudget budget) {
        GrowthRules.uuid(owner); ownKind(kind); GrowthRules.scope(scope); GrowthRules.budget(budget);
        GrowthRules.position(after, scope, kind);
        List<EvidenceSummary> items = new ArrayList<>();
        EvidencePosition last = after;
        int scanned = 0;
        boolean exhausted = false;
        ReadContext context = new ReadContext();
        while (scanned < budget.candidateLimit() && items.size() < budget.resultLimit()) {
            int consume = Math.min(49, Math.min(budget.candidateLimit() - scanned, budget.resultLimit() - items.size()));
            var rows = mapper.candidates(owner, kind.name(), scope.window().startAt(), scope.window().observedUntil(),
                    last == null ? null : last.occurredAt(), last == null ? null : last.evidenceKey(), consume + 1);
            checkBatch(rows, consume + 1);
            // 多取的一项只证明仍有后续；不计扫描、不更新游标，也不跳过未返回的合格记录。
            for (int index = 0; index < Math.min(consume, rows.size()); index++) {
                Candidate row = rows.get(index);
                EvidenceRef ref = candidate(row, kind, last);
                EvidenceSummary value = resolveOne(owner, ref, context).summary();
                if (value == null) throw GrowthRules.unavailable();
                if (!row.occurredAt().equals(value.occurredAt())) throw GrowthRules.integrity();
                EvidenceSummary matched = filter.match(owner, value, scope, level, flag);
                scanned++;
                last = new EvidencePosition(row.occurredAt(), row.evidenceKey());
                if (matched != null) items.add(matched);
            }
            exhausted = rows.size() <= consume;
            if (exhausted) break;
        }
        return new DomainEvidenceWindow(List.copyOf(items), scanned, exhausted,
                !exhausted && scanned == budget.candidateLimit(), last);
    }

    public List<EvidenceResolution> resolve(String owner, List<EvidenceRef> refs) {
        GrowthRules.uuid(owner); GrowthRules.references(refs);
        ReadContext context = new ReadContext();
        List<EvidenceResolution> result = new ArrayList<>(refs.size());
        for (EvidenceRef ref : refs) { ownKind(ref.kind()); result.add(resolveOne(owner, ref, context)); }
        return List.copyOf(result);
    }

    public DomainActivityWindow activity(String owner, Scope recordedScope, Grain grain) {
        GrowthRules.uuid(owner); GrowthRules.scope(recordedScope);
        var collector = GrowthActivitySupport.collector(recordedScope, grain, METRICS);
        ProgressFilter recorded = recordedFilter(recordedScope);
        ReadContext context = new ReadContext();
        EvidencePosition last = null;
        int scanned = 0;
        boolean exhausted = false;
        while (scanned < GrowthRules.MAX_CANDIDATES) {
            int consume = Math.min(49, GrowthRules.MAX_CANDIDATES - scanned);
            var rows = mapper.activityCandidates(owner, recordedScope.window().startAt(), recordedScope.window().observedUntil(),
                    last == null ? null : last.occurredAt(), last == null ? null : last.evidenceKey(), consume + 1);
            checkBatch(rows, consume + 1);
            for (int index = 0; index < Math.min(consume, rows.size()); index++) {
                Candidate row = rows.get(index);
                ordered(row, last);
                if ("REPLAY_START".equals(row.kind())) {
                    ReplayStart replay = replayStart(owner, row.objectId());
                    if (!row.occurredAt().equals(replay.startedAt()) || !row.evidenceKey().equals("REPLAY_START:" + replay.id())) {
                        throw GrowthRules.integrity();
                    }
                    Facets facets = training.recordedFacets(owner, replay.sessionId(), replay.questionId());
                    if (facets == null) throw GrowthRules.unavailable();
                    if (filter.matchesRecorded(facets, recorded)) {
                        collector.add(ActivityMetric.REPLAYS_STARTED, replay.id(), replay.startedAt());
                        collector.add("SAME".equals(replay.questionMode()) ? ActivityMetric.SAME_REPLAYS_STARTED
                                : ActivityMetric.VARIANT_REPLAYS_STARTED, replay.id(), replay.startedAt());
                    }
                } else {
                    EvidenceKind kind = enumeration(row.kind(), EvidenceKind.class);
                    EvidenceRef ref = candidate(row, kind, last);
                    EvidenceSummary summary = resolveOne(owner, ref, context).summary();
                    if (summary == null) throw GrowthRules.unavailable();
                    if (!row.occurredAt().equals(summary.occurredAt())) throw GrowthRules.integrity();
                    if (filter.matchesRecorded(summary, recorded)) {
                        ActivityMetric metric = switch (kind) {
                            case MANUAL_FEEDBACK -> ref.revision() == 1 ? ActivityMetric.FEEDBACK_CREATED : ActivityMetric.FEEDBACK_REVISED;
                            case RECOLLECTION -> ActivityMetric.RECOLLECTION_VERSIONS_RECORDED;
                            case REPLAY_JUDGMENT -> ActivityMetric.REPLAY_JUDGMENTS_RECORDED;
                            default -> throw GrowthRules.integrity();
                        };
                        collector.add(metric, row.evidenceKey(), row.occurredAt());
                    }
                }
                scanned++;
                last = new EvidencePosition(row.occurredAt(), row.evidenceKey());
            }
            exhausted = rows.size() <= consume;
            if (exhausted) break;
        }
        return collector.finish(!exhausted);
    }

    public DomainSourceProgressWindow sourceProgress(String owner, ProgressFilter selected,
            SourceDisposition disposition, String afterSourceId, QueryBudget budget) {
        GrowthRules.uuid(owner); GrowthRules.progressScopeHash(selected, disposition); GrowthRules.budget(budget);
        if (afterSourceId != null) GrowthRules.uuid(afterSourceId);
        List<SourceProgressItem> items = new ArrayList<>();
        String last = afterSourceId;
        int scanned = 0;
        boolean exhausted = false;
        ReadContext context = new ReadContext();
        while (scanned < budget.candidateLimit() && items.size() < budget.resultLimit()) {
            int consume = Math.min(49, Math.min(budget.candidateLimit() - scanned, budget.resultLimit() - items.size()));
            List<String> ids = mapper.sourceIds(owner, last, consume + 1);
            checkBatch(ids, consume + 1);
            for (int index = 0; index < Math.min(consume, ids.size()); index++) {
                String id = savedId(ids.get(index));
                if (last != null && id.compareTo(last) <= 0) throw GrowthRules.integrity();
                SourceContext current = sourceContext(owner, id, context);
                if (current == null) throw GrowthRules.integrity();
                EvidenceSummary origin = current.origin().summary();
                boolean matches = origin == null ? unfiltered(selected) : filter.matchesRecorded(origin, selected);
                if (origin == null && !unfiltered(selected)) throw GrowthRules.unavailable();
                SourceProgressItem item = progress(current);
                if (matches && (disposition == null || item.disposition() == disposition)) items.add(item);
                scanned++;
                last = id;
            }
            exhausted = ids.size() <= consume;
            if (exhausted) break;
        }
        return new DomainSourceProgressWindow(List.copyOf(items), scanned, exhausted,
                !exhausted && scanned == budget.candidateLimit(), last);
    }

    private EvidenceResolution resolveOne(String owner, EvidenceRef ref, ReadContext context) {
        GrowthRules.evidence(ref);
        String key = GrowthRules.evidenceKey(ref);
        EvidenceResolution cached = context.resolutions.get(key);
        if (cached != null) return cached;
        EvidenceResolution result;
        try {
            result = switch (ref.kind()) {
                case MANUAL_FEEDBACK -> manual(owner, ref, context);
                case RECOLLECTION -> recollection(owner, ref, context);
                case REPLAY_JUDGMENT -> replay(owner, ref, context);
                case AI_OBSERVATION -> {
                    List<EvidenceResolution> values = ai.resolve(owner, List.of(ref));
                    if (values == null || values.size() != 1 || !ref.equals(values.getFirst().ref())) throw GrowthRules.integrity();
                    yield values.getFirst();
                }
                default -> throw GrowthRules.integrity();
            };
            GrowthRules.resolution(result);
        } catch (ApiException exception) {
            if (exception.status() == 400) throw new IllegalStateException("复盘成长元数据不符合已保存约束", exception);
            throw exception;
        }
        context.resolutions.put(key, result);
        return result;
    }

    private EvidenceResolution manual(String owner, EvidenceRef ref, ReadContext context) {
        Feedback row = mapper.feedback(owner, ref.objectId(), ref.revision());
        if (row == null) return missing(ref);
        if (!owner.equals(row.ownerId()) || !ref.objectId().equals(row.id()) || row.revision() != ref.revision()
                || !Boolean.TRUE.equals(row.metadataValid()) || row.currentRevision() == null
                || row.currentRevision() < row.revision() || row.currentRevision() > 100) throw GrowthRules.integrity();
        var answer = training.answer(owner, savedId(row.sessionId()), savedId(row.questionId()), savedId(row.answerId()));
        if (answer.summary() == null) return missing(ref, answer.stamp().currentHash());
        AnswerMeta answerMeta = answer.summary().conditions().answer();
        var kind = enumeration(row.kind(), ReviewDtos.FeedbackKind.class);
        var dimension = enumeration(row.dimension(), QuestionDtos.Dimension.class);
        var coverage = enumeration(row.coverage(), AiDtos.EvaluationCoverage.class);
        DecisionMeta decision = decision(owner, row);
        ManualMeta meta = new ManualMeta(answerMeta, kind, row.authorLabel(), dimension, coverage, decision);
        Conditions conditions = new Conditions(ref.kind(), null, meta, null, null, null, null, null);
        Facets base = answer.summary().facets();
        Facets facets = new Facets(base.targets(), base.topics(), List.of(new DimensionFacet(dimension, FacetBasis.RECORDED)),
                base.currentTopicClassificationAvailable(), null);
        boolean superseded = row.currentRevision() != row.revision();
        var extras = EnumSet.noneOf(ReviewFlag.class);
        if (superseded) extras.add(ReviewFlag.REVIEW_REQUIRED);
        if (decision != null && decision.choice() == AiDtos.FeedbackChoice.DISPUTED) extras.add(ReviewFlag.DISPUTED);
        EvidenceState state = combined(superseded ? RecordCurrency.SUPERSEDED : RecordCurrency.CURRENT, extras, answer.state());
        RecordKind recordKind = kind == ReviewDtos.FeedbackKind.SELF_CHECK ? RecordKind.SELF_CHECK : RecordKind.EXTERNAL_FEEDBACK;
        ManualMeta fixed = new ManualMeta(answerMeta, kind, row.authorLabel(), dimension, coverage, null);
        String origin = origin(ref, row.createdAt(), recordKind, fixed, facets, answer.ref(), row.contentHash());
        String current = hash("GROWTH_MANUAL_CURRENT_V1", row.id(), row.currentRevision(), decision, answer.stamp().currentHash(), state);
        return summary(ref, row.createdAt(), (kind == ReviewDtos.FeedbackKind.SELF_CHECK ? "自检" : "外部反馈") + " · 第 " + row.revision() + " 版",
                recordKind, conditions, facets, state, origin, current,
                new EvidenceRoute(EvidenceRouteKind.MANUAL_FEEDBACK, ref, row.sessionId(), row.questionId(), row.answerId(), null, null, null));
    }

    private DecisionMeta decision(String owner, Feedback feedback) {
        Decision row = mapper.latestDecision(owner, feedback.id(), feedback.revision());
        if (row == null) return null;
        if (!owner.equals(row.ownerId()) || !feedback.id().equals(row.feedbackId())
                || row.feedbackRevision() != feedback.revision()) throw GrowthRules.integrity();
        GrowthRules.range(row.revision(), 1, 20, "决定修订");
        return new DecisionMeta(savedId(row.id()), row.revision(), enumeration(row.choice(), AiDtos.FeedbackChoice.class), GrowthRules.time(row.createdAt()));
    }

    private EvidenceResolution recollection(String owner, EvidenceRef ref, ReadContext context) {
        Recollection row = mapper.recollection(owner, ref.objectId(), ref.versionId());
        if (row == null) return missing(ref);
        if (!owner.equals(row.ownerId()) || !ref.objectId().equals(row.id()) || !ref.versionId().equals(row.versionId())
                || !Boolean.TRUE.equals(row.metadataValid()) || row.revision() == null || row.revision() < 0 || row.revision() > 1000
                || row.headVersionNumber() == null || row.headVersionNumber() < row.versionNumber()
                || row.headVersionNumber() > row.revision() + 1 || row.headVersionNumber() > 1000
                || !row.headVersionNumber().equals(row.actualCurrentVersionNumber())) throw GrowthRules.integrity();
        savedId(row.currentVersionId()); GrowthRules.range(row.versionNumber(), 1, 1000, "回忆版本");
        var baseline = enumeration(row.baselineKind(), RecollectionDtos.BaselineKind.class);
        if (row.hasAnswerFragment() == null || row.hasFeedback() == null || row.hasUncertainty() == null
                || row.hasAnswerFragment() != (baseline == RecollectionDtos.BaselineKind.RECOLLECTION)) throw GrowthRules.integrity();
        InterviewMeta interview = interview(row);
        RecollectionMeta meta = new RecollectionMeta(row.versionNumber(), baseline, interview,
                row.hasAnswerFragment(), row.hasFeedback(), row.hasUncertainty());
        Facets facets = new Facets(interview == null || interview.target() == null ? List.of()
                : List.of(new TargetFacet(interview.target(), FacetBasis.RECORDED)), List.of(), List.of(), false, null);
        var currentState = sourceState(row);
        EvidenceState state = combined(row.currentVersionId().equals(row.versionId()) ? RecordCurrency.CURRENT : RecordCurrency.SUPERSEDED,
                currentState == RecollectionDtos.SourceState.CURRENT ? Set.of() : Set.of(ReviewFlag.REVIEW_REQUIRED));
        String origin = origin(ref, row.createdAt(), RecordKind.INTERVIEW_RECOLLECTION, meta, facets, row.title(), row.contentHash());
        String current = hash("GROWTH_RECOLLECTION_CURRENT_V1", row.id(), row.revision(), row.status(), row.currentVersionId(), currentState, state);
        context.recollections.put(GrowthRules.evidenceKey(ref), row);
        return summary(ref, row.createdAt(), GrowthRules.text(row.title(), 200, true), RecordKind.INTERVIEW_RECOLLECTION,
                new Conditions(ref.kind(), null, null, null, meta, null, null, null), facets, state, origin, current,
                new EvidenceRoute(EvidenceRouteKind.RECOLLECTION_VERSION, ref, null, null, null, null, null, null));
    }

    private InterviewMeta interview(Recollection row) {
        if (row.interviewId() == null) {
            if (row.interviewRevision() != null || row.interviewJson() != null) throw GrowthRules.integrity();
            return null;
        }
        if (row.interviewRevision() == null || row.interviewJson() == null
                || row.interviewJson().getBytes(StandardCharsets.UTF_8).length > 8192) throw GrowthRules.integrity();
        var fixed = GrowthRules.decode(row.interviewJson().getBytes(StandardCharsets.UTF_8), RecollectionDtos.InterviewSnapshot.class);
        if (!row.interviewId().equals(fixed.eventId()) || row.interviewRevision() != fixed.revision() || fixed.status() == null) throw GrowthRules.integrity();
        GrowthRules.text(fixed.title(), 200, true); GrowthRules.text(fixed.round(), 100, false);
        return new InterviewMeta(savedId(fixed.eventId()), GrowthRules.revision(fixed.revision()), GrowthRules.time(fixed.startAt()),
                GrowthRules.zone(fixed.zoneId()), GrowthRules.target(fixed.targetId(), fixed.targetVersionId()));
    }

    private EvidenceResolution replay(String owner, EvidenceRef ref, ReadContext context) {
        Judgment row = mapper.judgment(owner, ref.objectId());
        if (row == null) return missing(ref);
        if (!ref.objectId().equals(row.id())) throw GrowthRules.integrity();
        SourceContext current = sourceContext(owner, savedId(row.sourceId()), context);
        if (current == null) return missing(ref);
        JudgmentData data = current.latest() != null && row.id().equals(current.latest().row().id())
                ? current.latest() : judgmentData(owner, row, current.source(), current.origin(), context);
        if (data.answer().summary() == null) return missing(ref, data.answer().stamp().currentHash());
        boolean latest = current.latest() != null && row.id().equals(current.latest().row().id());
        boolean applicable = latest && current.domainApplicable() && usable(current.origin().state()) && usable(data.answer().state());
        EvidenceState state = combined(latest ? RecordCurrency.CURRENT : RecordCurrency.SUPERSEDED,
                applicable ? Set.of() : Set.of(ReviewFlag.REVIEW_REQUIRED), current.origin().state(), data.answer().state());
        AnswerMeta answer = data.answer().summary().conditions().answer();
        var mode = enumeration(data.repractice().questionMode(), RepracticeDtos.QuestionMode.class);
        var baseline = RepracticeRules.baselineKind(data.repractice());
        var outcome = enumeration(row.outcome(), RepracticeDtos.Outcome.class);
        ReplayMeta meta = new ReplayMeta(row.sourceId(), data.fixed().actionId(), row.revision(), row.comparisonId(), answer,
                mode, baseline, outcome, applicable, row.verificationHash(), current.source().verificationHash());
        ReplayMeta fixed = new ReplayMeta(row.sourceId(), data.fixed().actionId(), row.revision(), row.comparisonId(), answer,
                mode, baseline, outcome, false, row.verificationHash(), null);
        Facets facets = data.answer().summary().facets();
        String origin = origin(ref, row.createdAt(), RecordKind.REPLAY_ASSESSMENT, fixed, facets,
                Arrays.asList(current.origin().ref(), data.answer().ref(), data.repractice().id(), data.fixed().actionRevision()), row.contentHash());
        Judgment latestRow = current.latest() == null ? null : current.latest().row();
        Source source = current.source();
        String currentHash = hash("GROWTH_REPLAY_CURRENT_V1", source.id(), source.actionId(), source.actionRevision(),
                source.actionStatus(), source.tracking(), source.verificationHash(),
                latestRow == null ? null : latestRow.id(), latestRow == null ? null : latestRow.revision(),
                latestRow == null ? null : latestRow.outcome(), current.domainApplicable(), current.recollectionState(),
                current.origin().stamp().currentHash(), data.answer().stamp().currentHash(), state);
        return summary(ref, row.createdAt(), "复练判断 · 第 " + row.revision() + " 次", RecordKind.REPLAY_ASSESSMENT,
                new Conditions(ref.kind(), null, null, null, null, meta, null, null), facets, state, origin, currentHash,
                new EvidenceRoute(EvidenceRouteKind.REPLAY_COMPARISON, ref, answer.sessionId(), answer.questionId(), answer.answerId(),
                        row.comparisonId(), null, null));
    }

    private SourceContext sourceContext(String owner, String id, ReadContext context) {
        SourceContext cached = context.sources.get(id);
        if (cached != null) return cached;
        Source row = mapper.source(owner, id);
        if (row == null) return null;
        if (!owner.equals(row.ownerId()) || !id.equals(row.id()) || !Boolean.TRUE.equals(row.metadataValid())
                || row.actionRevision() == null || row.actionRevision() < 0 || row.actionRevision() > 1000) throw GrowthRules.integrity();
        savedId(row.actionId()); savedId(row.originActionId()); GrowthRules.hashText(row.verificationHash());
        var actionStatus = enumeration(row.actionStatus(), ReviewDtos.ActionStatus.class);
        var tracking = enumeration(row.tracking(), ReviewDtos.Tracking.class);
        if (actionStatus == ReviewDtos.ActionStatus.MERGED && row.mergedInto() == null
                || actionStatus == ReviewDtos.ActionStatus.OPEN && row.mergedInto() != null) throw GrowthRules.integrity();
        if (row.mergedInto() != null) savedId(row.mergedInto());
        EvidenceRef originRef = sourceRef(row);
        EvidenceResolution origin = resolveOne(owner, originRef, context);
        validateSourceOrigin(row, origin);
        RecollectionDtos.SourceState recollectionState = null;
        if (originRef.kind() == EvidenceKind.RECOLLECTION && origin.summary() != null) {
            recollectionState = sourceState(context.recollections.get(GrowthRules.evidenceKey(originRef)));
        }
        Judgment latest = mapper.latestJudgment(owner, id);
        JudgmentData latestData = latest == null ? null : judgmentData(owner, latest, row, origin, context);
        boolean applicable = RepracticeApplicability.applicable(latest != null, actionStatus, tracking,
                latest != null && row.verificationHash().equals(latest.verificationHash()), recollectionState)
                && (originRef.kind() != EvidenceKind.RECOLLECTION || recollectionState != null);
        SourceContext value = new SourceContext(row, origin, latestData, applicable, recollectionState);
        context.sources.put(id, value);
        return value;
    }

    private JudgmentData judgmentData(String owner, Judgment row, Source source, EvidenceResolution origin, ReadContext context) {
        if (!owner.equals(row.ownerId()) || !source.id().equals(row.sourceId()) || !Boolean.TRUE.equals(row.metadataValid())) throw GrowthRules.integrity();
        savedId(row.id()); GrowthRules.range(row.revision(), 1, 1000, "复练判断"); GrowthRules.time(row.createdAt());
        GrowthRules.hashText(row.verificationHash()); enumeration(row.outcome(), RepracticeDtos.Outcome.class);
        Comparison comparison = mapper.comparison(owner, savedId(row.comparisonId()));
        if (comparison == null || !owner.equals(comparison.ownerId()) || !row.comparisonId().equals(comparison.id())
                || !row.repracticeId().equals(comparison.repracticeId()) || !row.answerId().equals(comparison.answerId())) throw GrowthRules.integrity();
        RepracticeRows.RepracticeRow replay = mapper.repractice(owner, savedId(comparison.repracticeId()));
        if (replay == null || !owner.equals(replay.ownerId()) || !comparison.repracticeId().equals(replay.id())
                || !replay.sessionId().equals(comparison.sessionId()) || !replay.questionId().equals(comparison.questionId())) throw GrowthRules.integrity();
        RepracticeDtos.BaselineKind baseline = RepracticeRules.baselineKind(replay);
        replayStart(owner, replay.id());
        if (comparison.actualAnswerNumber() != null && (comparison.actualAnswerNumber() != comparison.answerNumber()
                || !"ENDED".equals(comparison.sessionStatus()))) throw GrowthRules.integrity();
        Snapshot snapshot = mapper.snapshot(owner, replay.id(), source.id());
        if (snapshot == null || !owner.equals(snapshot.ownerId()) || !replay.id().equals(snapshot.repracticeId())
                || !source.id().equals(snapshot.sourceId()) || !Boolean.TRUE.equals(snapshot.metadataValid())
                || snapshot.position() < 0 || snapshot.position() > 9) throw GrowthRules.integrity();
        FixedIdentity fixed = GrowthRules.decode(snapshot.identityJson().getBytes(StandardCharsets.UTF_8), FixedIdentity.class);
        if (!snapshot.sourceId().equals(fixed.sourceId()) || !snapshot.sourceId().equals(fixed.id())
                || !snapshot.actionId().equals(fixed.actionId()) || snapshot.actionRevision() != fixed.actionRevision()
                || !"TRACKING".equals(fixed.tracking()) || !row.verificationHash().equals(snapshot.verificationHash())
                || !sameIdentity(source, fixed)) throw GrowthRules.integrity();
        savedId(fixed.actionId()); GrowthRules.range(fixed.actionRevision(), 0, 1000, "固定动作修订");
        if (baseline == RepracticeDtos.BaselineKind.ANSWER) {
            if ("RECOLLECTION".equals(source.kind()) || !Objects.equals(replay.baselineSessionId(), source.sessionId())
                    || !Objects.equals(replay.baselineQuestionId(), source.questionId()) || !Objects.equals(replay.baselineAnswerId(), source.answerId())
                    || replay.sessionId().equals(replay.baselineSessionId())) throw GrowthRules.integrity();
            String status = mapper.answerSessionStatus(owner, source.sessionId(), source.questionId(), source.answerId());
            if (status != null && !"ENDED".equals(status)) throw GrowthRules.integrity();
        } else if (!"RECOLLECTION".equals(source.kind()) || !Objects.equals(replay.recollectionId(), source.recollectionId())
                || !Objects.equals(replay.recollectionVersionId(), source.recollectionVersionId())
                || !baseline.name().equals(source.recollectionBaselineKind())) throw GrowthRules.integrity();
        EvidenceResolution answer = training.answer(owner, savedId(comparison.sessionId()), savedId(comparison.questionId()), savedId(comparison.answerId()));
        if ((comparison.actualAnswerNumber() == null) != (answer.summary() == null)) throw GrowthRules.integrity();
        return new JudgmentData(row, replay, fixed, answer);
    }

    private ReplayStart replayStart(String owner, String id) {
        ReplayStart row = mapper.replayStart(owner, savedId(id));
        if (row == null || !owner.equals(row.ownerId()) || !id.equals(row.id()) || row.startedAt() == null
                || row.questionCount() == null || row.questionCount() != 1
                || !Set.of("RUNNING", "PAUSED", "ENDED").contains(row.sessionStatus())) throw GrowthRules.integrity();
        savedId(row.sessionId()); savedId(row.questionId()); GrowthRules.time(row.startedAt());
        var mode = enumeration(row.questionMode(), RepracticeDtos.QuestionMode.class);
        if (!(mode == RepracticeDtos.QuestionMode.SAME ? "REPLAY" : "MAIN").equals(row.questionKind())) throw GrowthRules.integrity();
        return row;
    }

    private EvidenceRef sourceRef(Source value) {
        var kind = enumeration(value.kind(), ReviewDtos.SourceKind.class);
        if (kind != ReviewDtos.SourceKind.RECOLLECTION) {
            savedId(value.sessionId()); savedId(value.questionId()); savedId(value.answerId());
            if (value.recollectionId() != null || value.recollectionVersionId() != null || value.recollectionVersionNumber() != null
                    || value.recollectionBaselineKind() != null) throw GrowthRules.integrity();
        }
        return switch (kind) {
            case MANUAL -> {
                if (value.taskId() != null || value.observationId() != null || value.feedbackRevision() == null) throw GrowthRules.integrity();
                yield GrowthRules.evidence(new EvidenceRef(EvidenceKind.MANUAL_FEEDBACK, value.feedbackId(), null, value.feedbackRevision().longValue(), null));
            }
            case AI_EVALUATION -> {
                if (value.feedbackId() != null || value.feedbackRevision() != null) throw GrowthRules.integrity();
                yield GrowthRules.evidence(new EvidenceRef(EvidenceKind.AI_OBSERVATION, value.taskId(), null, null, value.observationId()));
            }
            case RECOLLECTION -> {
                if (value.sessionId() != null || value.questionId() != null || value.answerId() != null || value.feedbackId() != null
                        || value.feedbackRevision() != null || value.taskId() != null || value.observationId() != null
                        || value.recollectionVersionNumber() == null) throw GrowthRules.integrity();
                GrowthRules.range(value.recollectionVersionNumber(), 1, 1000, "回忆版本");
                enumeration(value.recollectionBaselineKind(), RecollectionDtos.BaselineKind.class);
                yield GrowthRules.evidence(new EvidenceRef(EvidenceKind.RECOLLECTION, value.recollectionId(), value.recollectionVersionId(), null, null));
            }
        };
    }

    private void validateSourceOrigin(Source source, EvidenceResolution origin) {
        if (origin.summary() == null) return;
        if (origin.ref().kind() == EvidenceKind.RECOLLECTION) {
            var meta = origin.summary().conditions().recollection();
            if (meta.versionNumber() != source.recollectionVersionNumber() || !meta.baselineKind().name().equals(source.recollectionBaselineKind())) throw GrowthRules.integrity();
        } else {
            AnswerMeta answer = origin.ref().kind() == EvidenceKind.MANUAL_FEEDBACK
                    ? origin.summary().conditions().manual().answer() : origin.summary().conditions().ai().answer();
            if (!source.sessionId().equals(answer.sessionId()) || !source.questionId().equals(answer.questionId())
                    || !source.answerId().equals(answer.answerId())) throw GrowthRules.integrity();
        }
    }

    private static boolean sameIdentity(Source source, FixedIdentity fixed) {
        return Objects.equals(source.originActionId(), fixed.originActionId()) && Objects.equals(source.kind(), fixed.kind())
                && Objects.equals(source.sessionId(), fixed.sessionId()) && Objects.equals(source.questionId(), fixed.questionId())
                && Objects.equals(source.answerId(), fixed.answerId()) && Objects.equals(source.feedbackId(), fixed.feedbackId())
                && Objects.equals(source.feedbackRevision(), fixed.feedbackRevision()) && Objects.equals(source.taskId(), fixed.taskId())
                && Objects.equals(source.observationId(), fixed.observationId()) && Objects.equals(source.recollectionId(), fixed.recollectionId())
                && Objects.equals(source.recollectionVersionId(), fixed.recollectionVersionId())
                && Objects.equals(source.recollectionVersionNumber(), fixed.recollectionVersionNumber())
                && Objects.equals(source.recollectionBaselineKind(), fixed.recollectionBaselineKind());
    }

    private static SourceProgressItem progress(SourceContext current) {
        Source source = current.source();
        Judgment latest = current.latest() == null ? null : current.latest().row();
        EvidenceState state = current.origin().state();
        SourceDisposition disposition;
        if ("DROPPED".equals(source.tracking())) disposition = SourceDisposition.DROPPED;
        else if (state.directState() == DirectState.UNAVAILABLE) disposition = SourceDisposition.SOURCE_UNAVAILABLE;
        else if (!usable(state) || latest != null && (!current.domainApplicable()
                || !usable(current.latest().answer().state()))) disposition = SourceDisposition.REVIEW_REQUIRED;
        else if (latest == null) disposition = SourceDisposition.UNASSESSED;
        else disposition = SourceDisposition.valueOf(latest.outcome());
        return new SourceProgressItem(source.id(), source.actionId(), source.actionRevision(), ReviewDtos.Tracking.valueOf(source.tracking()),
                current.origin().ref(), latest == null ? null : new EvidenceRef(EvidenceKind.REPLAY_JUDGMENT, latest.id(), null, null, null),
                latest == null ? null : RepracticeDtos.Outcome.valueOf(latest.outcome()), current.domainApplicable(), disposition, state,
                source.verificationHash(), new TodayDtos.Continuation(TodayDtos.Route.REVIEW, null, new TodayDtos.ActionQuery(source.actionId()), null));
    }

    private static RecollectionDtos.SourceState sourceState(Recollection row) {
        if (row == null) throw GrowthRules.integrity();
        var status = enumeration(row.status(), RecollectionDtos.Status.class);
        return status == RecollectionDtos.Status.ARCHIVED ? RecollectionDtos.SourceState.ARCHIVED
                : row.versionId().equals(row.currentVersionId()) ? RecollectionDtos.SourceState.CURRENT : RecollectionDtos.SourceState.SUPERSEDED;
    }

    private static EvidenceResolution summary(EvidenceRef ref, Instant at, String title, RecordKind kind, Conditions conditions,
            Facets facets, EvidenceState state, String origin, String current, EvidenceRoute route) {
        EvidenceStamp stamp = new EvidenceStamp(ref, origin, current);
        EvidenceSummary summary = new EvidenceSummary(ref, at, title, kind, AssessmentLevel.SELF_RECORDED, conditions, facets, state, stamp, route);
        GrowthRules.summary(summary);
        return new EvidenceResolution(ref, summary, state, stamp);
    }

    private static EvidenceResolution missing(EvidenceRef ref, String... dependencies) {
        EvidenceState state = new EvidenceState(DirectState.UNAVAILABLE, RecordCurrency.NOT_APPLICABLE,
                List.of(ReviewFlag.SOURCE_UNAVAILABLE), LineageCheck.PARTIAL);
        return new EvidenceResolution(ref, null, state, new EvidenceStamp(ref, null, hash("GROWTH_REVIEW_MISSING_V1", ref, dependencies, state)));
    }

    private static EvidenceState combined(RecordCurrency currency, Set<ReviewFlag> extra, EvidenceState... dependencies) {
        EnumSet<ReviewFlag> flags = EnumSet.noneOf(ReviewFlag.class);
        flags.addAll(extra);
        DirectState direct = flags.isEmpty() ? DirectState.AVAILABLE : DirectState.REVIEW_REQUIRED;
        LineageCheck lineage = LineageCheck.NOT_APPLICABLE;
        for (EvidenceState dependency : dependencies) {
            GrowthRules.state(dependency); flags.addAll(dependency.reviewFlags());
            if (rank(dependency.directState()) > rank(direct)) direct = dependency.directState();
            if (dependency.lineageCheck() == LineageCheck.PARTIAL) lineage = LineageCheck.PARTIAL;
        }
        if (!flags.isEmpty() && direct == DirectState.AVAILABLE) direct = DirectState.REVIEW_REQUIRED;
        EvidenceState result = new EvidenceState(direct, currency, List.copyOf(flags), lineage);
        GrowthRules.state(result);
        return result;
    }

    private static int rank(DirectState state) {
        return switch (state) { case AVAILABLE -> 0; case REVIEW_REQUIRED -> 1; case UNKNOWN -> 2; case UNAVAILABLE -> 3; };
    }
    private static boolean usable(EvidenceState state) { return state.directState() == DirectState.AVAILABLE && state.reviewFlags().isEmpty(); }
    private static String origin(EvidenceRef ref, Instant at, RecordKind kind, Object fixed, Facets facets, Object references, String contentHash) {
        // 正文仅在源域内计算摘要，成长目录据此绑定确切内容，不读取或返回原文。
        GrowthRules.hashText(contentHash);
        return hash("GROWTH_REVIEW_ORIGIN_V1", ref, at, kind, fixed, facets.targets(), facets.topics().stream()
                .filter(topic -> topic.basis() == FacetBasis.RECORDED).toList(), facets.dimensions(), references, contentHash);
    }
    private static String hash(String protocol, Object... values) { return GrowthRules.hash(protocol, Arrays.asList(values)); }
    private static String savedId(String value) {
        try { return GrowthRules.uuid(value); } catch (ApiException exception) { throw GrowthRules.integrity(); }
    }
    private static <T extends Enum<T>> T enumeration(String value, Class<T> type) {
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException | NullPointerException exception) { throw GrowthRules.integrity(); }
    }
    private static void ownKind(EvidenceKind kind) { if (!KINDS.contains(GrowthRules.required(kind))) throw GrowthRules.invalid("复盘投影不支持此证据类型。"); }
    private static <T> void checkBatch(List<T> rows, int limit) { if (rows == null || rows.size() > limit || rows.stream().anyMatch(Objects::isNull)) throw GrowthRules.integrity(); }
    private static EvidenceRef candidate(Candidate row, EvidenceKind kind, EvidencePosition after) {
        ownKind(kind); ordered(row, after);
        EvidenceRef ref = GrowthRules.evidence(new EvidenceRef(kind, row.objectId(), row.versionId(), row.revision(), null));
        if (!kind.name().equals(row.kind()) || !GrowthRules.evidenceKey(ref).equals(row.evidenceKey())) throw GrowthRules.integrity();
        return ref;
    }
    private static void ordered(Candidate row, EvidencePosition after) {
        if (row == null || row.evidenceKey() == null) throw GrowthRules.integrity();
        GrowthRules.time(row.occurredAt());
        if (after != null && (row.occurredAt().isAfter(after.occurredAt())
                || row.occurredAt().equals(after.occurredAt()) && row.evidenceKey().compareTo(after.evidenceKey()) <= 0)) throw GrowthRules.integrity();
    }
    private static ProgressFilter recordedFilter(Scope scope) {
        if (scope.filter().targetMode() != TargetMode.RECORDED_ONLY) throw GrowthRules.integrity();
        return new ProgressFilter(scope.filter().target(), scope.filter().topicId(), scope.filter().topicMode(), scope.filter().dimension());
    }
    private static boolean unfiltered(ProgressFilter filter) { return filter.target() == null && filter.topicId() == null && filter.dimension() == null; }

    private record JudgmentData(Judgment row, RepracticeRows.RepracticeRow repractice, FixedIdentity fixed, EvidenceResolution answer) {}
    private record SourceContext(Source source, EvidenceResolution origin, JudgmentData latest,
                                 boolean domainApplicable, RecollectionDtos.SourceState recollectionState) {}
    private static final class ReadContext {
        private final Map<String, EvidenceResolution> resolutions = new HashMap<>();
        private final Map<String, Recollection> recollections = new HashMap<>();
        private final Map<String, SourceContext> sources = new HashMap<>();
    }
}
