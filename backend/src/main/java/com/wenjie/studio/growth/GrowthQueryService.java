package com.wenjie.studio.growth;

import com.wenjie.studio.ai.AiGrowthQuery;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.memory.MemoryGrowthQuery;
import com.wenjie.studio.planning.PlanningGrowthQuery;
import com.wenjie.studio.prepare.GrowthTargetQuery;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.ReviewGrowthQuery;
import com.wenjie.studio.schedule.ScheduleGrowthQuery;
import com.wenjie.studio.today.TodayDtos;
import com.wenjie.studio.training.TrainingGrowthQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.wenjie.studio.growth.GrowthDtos.*;
import static com.wenjie.studio.growth.GrowthRules.*;

/** 同请求固定事实水位和读取快照；领域失败只使对应证据或指标不可用。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GrowthQueryService {
    private static final Logger LOG = LoggerFactory.getLogger(GrowthQueryService.class);
    private static final Capability READY = new Capability(Availability.READY, CapabilityReason.NONE);
    private static final Count UNAVAILABLE = new Count(null, CountMode.UNAVAILABLE);
    private static final Comparator<EvidencePosition> EVIDENCE_ORDER = Comparator
            .comparing(EvidencePosition::occurredAt, Comparator.reverseOrder()).thenComparing(EvidencePosition::evidenceKey);
    private static final Set<ActivityMetric> TRAINING_METRICS = EnumSet.of(ActivityMetric.ANSWERS_SUBMITTED,
            ActivityMetric.QUESTION_INSTANCES_ANSWERED, ActivityMetric.ANSWERS_BEFORE_ASSISTANCE,
            ActivityMetric.ANSWERS_LEARNING, ActivityMetric.ANSWERS_ASSISTED);
    private static final Set<ActivityMetric> REVIEW_METRICS = EnumSet.of(ActivityMetric.REPLAYS_STARTED,
            ActivityMetric.SAME_REPLAYS_STARTED, ActivityMetric.VARIANT_REPLAYS_STARTED,
            ActivityMetric.REPLAY_JUDGMENTS_RECORDED, ActivityMetric.FEEDBACK_CREATED,
            ActivityMetric.FEEDBACK_REVISED, ActivityMetric.RECOLLECTION_VERSIONS_RECORDED);
    private static final Set<ActivityMetric> MEMORY_METRICS = EnumSet.of(ActivityMetric.MEMORY_RATINGS,
            ActivityMetric.MEMORY_CARDS_RATED, ActivityMetric.MEMORY_COUNTED_RATINGS, ActivityMetric.MEMORY_UNCOUNTED_RATINGS);
    private static final Set<ActivityMetric> REVIEWED_METRICS = EnumSet.of(ActivityMetric.REVIEWED_OBSERVATIONS_CREATED,
            ActivityMetric.REVIEWED_OBSERVATIONS_MAINTAINED);

    private final GrowthEvidenceQuery evidence;
    private final GrowthTargetQuery targets;
    private final TrainingGrowthQuery training;
    private final ReviewGrowthQuery review;
    private final AiGrowthQuery ai;
    private final MemoryGrowthQuery memory;
    private final PlanningGrowthQuery planning;
    private final ScheduleGrowthQuery schedule;
    private final ReviewedObservationGrowthQuery reviewed;
    private final JsonMapper json;

    public GrowthQueryService(GrowthEvidenceQuery evidence, GrowthTargetQuery targets,
                              TrainingGrowthQuery training, ReviewGrowthQuery review, AiGrowthQuery ai,
                              MemoryGrowthQuery memory, PlanningGrowthQuery planning, ScheduleGrowthQuery schedule,
                              ReviewedObservationGrowthQuery reviewed, JsonMapper json) {
        this.evidence = evidence; this.targets = targets; this.training = training; this.review = review;
        this.ai = ai; this.memory = memory; this.planning = planning; this.schedule = schedule; this.json = json;
        this.reviewed = reviewed;
    }

    public Overview overview(String owner, OverviewQuery request) {
        uuid(owner); Instant asOf = now(); Scope scope = scope(request, asOf);
        selection(owner, scope.filter().target(), scope.filter().topicId());
        var groups = new ArrayList<KindCount>();
        for (var kind : EvidenceKind.values()) groups.add(kindCount(owner, kind, scope));
        Scope recorded = recorded(scope);
        DomainActivityWindow activity = activities(owner, recorded, Grain.DAY);
        var progressFilter = new ProgressFilter(scope.filter().target(), scope.filter().topicId(),
                scope.filter().topicMode(), scope.filter().dimension());
        SourceProgressTotals progress = progressTotals(owner, progressFilter);
        boolean limited = groups.stream().anyMatch(KindCount::scanLimited) || activity.scanLimited() || progress.scanLimited();
        var limitations = limitations(scope.filter(), limited);
        limitations.add(Limitation.INDIRECT_LINEAGE_NOT_CHECKED);
        limitations.add(Limitation.NO_AUTOMATIC_DATE_EXPIRY);
        return readResponse(json, new Overview(context(asOf), scope, List.copyOf(groups), emptyState(groups),
                new ActivityTotals(recorded.filter(), activity.totals()), progress, List.copyOf(limitations)));
    }

    public EvidencePage evidence(String owner, EvidenceQuery request) {
        uuid(owner); required(request); Instant checked = now();
        EvidenceCursor cursor = evidenceCursor(request.cursor(), owner, checked);
        Instant asOf = cursor == null ? checked : cursor.asOf();
        Scope scope = scope(request, asOf);
        String scopeHash = evidenceScopeHash(scope, request.kind(), request.level(), request.reviewFlag());
        if (cursor != null) {
            cursorScope(cursor.scopeHash(), scopeHash);
            position(cursor.position(), scope, request.kind());
        }
        selection(owner, scope.filter().target(), scope.filter().topicId());
        Capability capability = READY; Count count = UNAVAILABLE;
        List<EvidenceSummary> items = List.of(); int scanned = 0; boolean limited = false; String nextCursor = null;
        if (future(scope)) count = UNAVAILABLE;
        else if (request.level() != null && request.level() != level(request.kind())) count = new Count(0L, CountMode.EXACT);
        else {
            try {
                var after = cursor == null ? null : cursor.position();
                var budget = new QueryBudget(MAX_CANDIDATES, pageSize(request.limit()));
                var page = evidence.page(owner, request.kind(), scope, request.level(), request.reviewFlag(), after, budget);
                evidenceWindow(page, request.kind(), scope, request.level(), request.reviewFlag(), after, budget);
                items = page.items(); scanned = page.scanned(); limited = page.scanLimited();
                count = new Count((long) items.size(), cursor == null && page.exhausted() ? CountMode.EXACT : CountMode.LOWER_BOUND);
                if (!page.exhausted()) nextCursor = cursor(new EvidenceCursor(1, CursorPurpose.EVIDENCE, ownerFingerprint(owner),
                        scopeHash, asOf, cursor == null ? checked : cursor.issuedAt(), page.lastConsumed()));
            } catch (RuntimeException exception) {
                capability = failed(exception); count = UNAVAILABLE; items = List.of(); scanned = 0; limited = false; nextCursor = null;
            }
        }
        return readResponse(json, new EvidencePage(context(asOf), scope, request.kind(), request.level(), request.reviewFlag(),
                capability, count, limited, scanned, items, nextCursor));
    }

    public EvidenceResolution evidenceRef(String owner, EvidenceRef ref) {
        uuid(owner); GrowthRules.evidence(ref);
        EvidenceResolution result;
        try { result = resolution(evidence.require(owner, ref)); }
        catch (ApiException exception) {
            if (exception.status() == 404) throw new ApiException(404, "EVIDENCE_NOT_FOUND", "所选证据不存在或不属于当前工作区。");
            failed(exception); throw unavailable();
        } catch (RuntimeException exception) { failed(exception); throw unavailable(); }
        return readResponse(json, result);
    }

    public ActivitySeries activity(String owner, ActivityQuery request) {
        uuid(owner); required(request); Instant asOf = now(); Scope scope = scope(request, asOf);
        selection(owner, scope.filter().target(), scope.filter().topicId());
        Grain grain = request.grain() == null ? Grain.DAY : request.grain();
        DomainActivityWindow result = activities(owner, scope, grain);
        return activityResponse(json, new ActivitySeries(context(asOf), scope, grain, result.buckets(),
                new ActivityTotals(scope.filter(), result.totals()), List.copyOf(limitations(scope.filter(), result.scanLimited()))));
    }

    public SourceProgressPage sourceProgress(String owner, SourceProgressQuery request) {
        uuid(owner); required(request); Instant checked = now();
        ProgressCursor cursor = progressCursor(request.cursor(), owner, checked);
        Instant asOf = cursor == null ? checked : cursor.asOf();
        ProgressFilter filter = progressFilter(request);
        String scopeHash = progressScopeHash(filter, request.disposition());
        if (cursor != null) cursorScope(cursor.scopeHash(), scopeHash);
        selection(owner, filter.target(), filter.topicId());
        Capability capability = READY; Count count = UNAVAILABLE;
        List<SourceProgressItem> items = List.of(); int scanned = 0; boolean limited = false; String nextCursor = null;
        try {
            String after = cursor == null ? null : cursor.position().sourceId();
            var budget = new QueryBudget(MAX_CANDIDATES, pageSize(request.limit()));
            var page = review.sourceProgress(owner, filter, request.disposition(), after, budget);
            progressWindow(page, request.disposition(), after, budget);
            items = page.items(); scanned = page.scanned(); limited = page.scanLimited();
            count = new Count((long) items.size(), cursor == null && page.exhausted() ? CountMode.EXACT : CountMode.LOWER_BOUND);
            if (!page.exhausted()) nextCursor = cursor(new ProgressCursor(1, CursorPurpose.SOURCE_PROGRESS, ownerFingerprint(owner),
                    scopeHash, asOf, cursor == null ? checked : cursor.issuedAt(), new SourcePosition(page.lastConsumedSourceId())));
        } catch (RuntimeException exception) {
            capability = failed(exception); count = UNAVAILABLE; items = List.of(); scanned = 0; limited = false; nextCursor = null;
        }
        return readResponse(json, new SourceProgressPage(context(asOf), filter, request.disposition(), capability,
                count, limited, scanned, items, nextCursor));
    }

    private KindCount kindCount(String owner, EvidenceKind kind, Scope scope) {
        if (future(scope)) return new KindCount(kind, READY, UNAVAILABLE, false);
        long matched = 0; int scanned = 0; EvidencePosition after = null; boolean exhausted = false;
        try {
            // 每批最多返回 50 项，所有批次共用本类型的 500 候选预算；空匹配页仍按消费位置继续。
            while (scanned < MAX_CANDIDATES && !exhausted) {
                var budget = new QueryBudget(MAX_CANDIDATES - scanned, MAX_RESULTS);
                var page = evidence.page(owner, kind, scope, null, null, after, budget);
                evidenceWindow(page, kind, scope, null, null, after, budget);
                matched += page.items().size(); scanned += page.scanned();
                after = page.lastConsumed(); exhausted = page.exhausted();
            }
            return new KindCount(kind, READY, new Count(matched, exhausted ? CountMode.EXACT : CountMode.LOWER_BOUND), !exhausted);
        } catch (RuntimeException exception) { return new KindCount(kind, failed(exception), UNAVAILABLE, false); }
    }

    private SourceProgressTotals progressTotals(String owner, ProgressFilter filter) {
        var counts = new EnumMap<SourceDisposition, Long>(SourceDisposition.class);
        for (var disposition : SourceDisposition.values()) counts.put(disposition, 0L);
        int scanned = 0; String after = null; boolean exhausted = false; Capability capability = READY;
        try {
            // 同一稳定来源只落入一个处置分组；不能为八个分组各扫描 500 条再相加。
            while (scanned < MAX_CANDIDATES && !exhausted) {
                var budget = new QueryBudget(MAX_CANDIDATES - scanned, MAX_RESULTS);
                var page = review.sourceProgress(owner, filter, null, after, budget);
                progressWindow(page, null, after, budget);
                for (var item : page.items()) counts.compute(item.disposition(), (key, value) -> value + 1);
                scanned += page.scanned(); after = page.lastConsumedSourceId(); exhausted = page.exhausted();
            }
        } catch (RuntimeException exception) { capability = failed(exception); }
        var result = new ArrayList<DispositionCount>();
        for (var disposition : SourceDisposition.values()) result.add(new DispositionCount(disposition,
                capability.status() == Availability.READY
                        ? new Count(counts.get(disposition), exhausted ? CountMode.EXACT : CountMode.LOWER_BOUND) : UNAVAILABLE));
        return new SourceProgressTotals(filter, capability, List.copyOf(result), capability.status() == Availability.READY && !exhausted);
    }

    private DomainActivityWindow activities(String owner, Scope scope, Grain grain) {
        var domains = List.of(
                domain(scope, grain, TRAINING_METRICS, () -> training.activity(owner, scope, grain)),
                domain(scope, grain, REVIEW_METRICS, () -> review.activity(owner, scope, grain)),
                domain(scope, grain, Set.of(ActivityMetric.AI_OBSERVATIONS_PUBLISHED), () -> ai.activity(owner, scope, grain)),
                domain(scope, grain, MEMORY_METRICS, () -> memory.activity(owner, scope, grain)),
                domain(scope, grain, Set.of(ActivityMetric.TASKS_DONE), () -> planning.activity(owner, scope, grain)),
                domain(scope, grain, Set.of(ActivityMetric.INTERVIEWS_COMPLETED), () -> schedule.activity(owner, scope, grain)),
                domain(scope, grain, REVIEWED_METRICS, () -> reviewed.activity(owner, scope, grain)));
        var totals = new EnumMap<ActivityMetric, MetricValue>(ActivityMetric.class);
        var buckets = new ArrayList<ActivityBucket>();
        for (var domain : domains) putMetrics(totals, domain.totals());
        for (int index = 0; index < domains.getFirst().buckets().size(); index++) {
            ActivityBucket bucket = domains.getFirst().buckets().get(index);
            var values = new EnumMap<ActivityMetric, MetricValue>(ActivityMetric.class);
            for (var domain : domains) putMetrics(values, domain.buckets().get(index).values());
            List<MetricValue> ordered = List.copyOf(values.values()); metrics(ordered);
            buckets.add(new ActivityBucket(bucket.from(), bucket.to(), bucket.startAt(), bucket.endAt(),
                    bucket.state(), bucket.clipped(), ordered));
        }
        List<MetricValue> orderedTotals = List.copyOf(totals.values()); metrics(orderedTotals);
        return new DomainActivityWindow(orderedTotals, List.copyOf(buckets), domains.stream().anyMatch(DomainActivityWindow::scanLimited));
    }

    private DomainActivityWindow domain(Scope scope, Grain grain, Set<ActivityMetric> expected,
                                         Supplier<DomainActivityWindow> query) {
        var template = GrowthActivitySupport.collector(scope, grain, expected).finish(false);
        try {
            DomainActivityWindow result = required(query.get());
            checkMetrics(result.totals(), expected, future(scope), result.scanLimited());
            if (result.buckets() == null || result.buckets().size() != template.buckets().size()) throw integrity();
            for (int index = 0; index < result.buckets().size(); index++) {
                ActivityBucket actual = required(result.buckets().get(index)); ActivityBucket shape = template.buckets().get(index);
                if (!shape.from().equals(actual.from()) || !shape.to().equals(actual.to())
                        || !shape.startAt().equals(actual.startAt()) || !shape.endAt().equals(actual.endAt())
                        || shape.state() != actual.state() || shape.clipped() != actual.clipped()) throw integrity();
                checkMetrics(actual.values(), expected, actual.state() == PeriodState.FUTURE, result.scanLimited());
            }
            return result;
        } catch (RuntimeException exception) {
            // 领域结果先完整校验再参与合并；任一桶损坏时该领域全部指标都不可用。
            return GrowthActivitySupport.unavailable(scope, grain, expected, failed(exception));
        }
    }

    private static void checkMetrics(List<MetricValue> values, Set<ActivityMetric> expected, boolean future, boolean limited) {
        domainMetrics(values);
        var found = EnumSet.noneOf(ActivityMetric.class);
        for (var value : values) {
            found.add(value.metric());
            if (future && value.count().mode() != CountMode.UNAVAILABLE
                    || !future && value.capability().status() == Availability.READY && value.count().mode() == CountMode.UNAVAILABLE
                    || limited && value.count().mode() == CountMode.EXACT
                    || !limited && value.count().mode() == CountMode.LOWER_BOUND) throw integrity();
        }
        if (!found.equals(expected)) throw integrity();
    }

    private static void putMetrics(Map<ActivityMetric, MetricValue> destination, List<MetricValue> values) {
        for (var value : values) if (destination.put(value.metric(), value) != null) throw integrity();
    }

    private static void evidenceWindow(DomainEvidenceWindow page, EvidenceKind kind, Scope scope,
                                       AssessmentLevel level, ReviewFlag flag, EvidencePosition after, QueryBudget budget) {
        required(page); required(page.items());
        windowCounts(page.items().size(), page.scanned(), page.exhausted(), page.scanLimited(), budget);
        EvidencePosition last = page.lastConsumed();
        if (page.scanned() == 0) {
            if (!Objects.equals(last, after)) throw integrity();
        } else {
            if (last == null) throw integrity();
            position(last, scope, kind);
            if (after != null && EVIDENCE_ORDER.compare(after, last) >= 0) throw integrity();
        }
        EvidencePosition previous = after;
        for (var item : page.items()) {
            summary(item);
            EvidencePosition current = new EvidencePosition(item.occurredAt(), evidenceKey(item.ref()));
            position(current, scope, kind);
            if (previous != null && EVIDENCE_ORDER.compare(previous, current) >= 0
                    || last == null || EVIDENCE_ORDER.compare(current, last) > 0
                    || level != null && item.assessmentLevel() != level
                    || flag != null && !item.state().reviewFlags().contains(flag)) throw integrity();
            previous = current;
        }
    }

    private static void progressWindow(DomainSourceProgressWindow page, SourceDisposition disposition,
                                       String after, QueryBudget budget) {
        required(page); required(page.items());
        windowCounts(page.items().size(), page.scanned(), page.exhausted(), page.scanLimited(), budget);
        String last = page.lastConsumedSourceId();
        if (page.scanned() == 0) {
            if (!Objects.equals(last, after)) throw integrity();
        } else {
            uuid(last);
            if (after != null && after.compareTo(last) >= 0) throw integrity();
        }
        String previous = after;
        for (var item : page.items()) {
            progressItem(item);
            if (previous != null && previous.compareTo(item.sourceId()) >= 0 || last == null
                    || item.sourceId().compareTo(last) > 0 || disposition != null && item.disposition() != disposition) throw integrity();
            previous = item.sourceId();
        }
    }

    private static void windowCounts(int size, int scanned, boolean exhausted, boolean limited, QueryBudget budget) {
        if (size > budget.resultLimit() || scanned < size || scanned < 0 || scanned > budget.candidateLimit()
                || !exhausted && scanned == 0 || exhausted && limited
                || limited && scanned != budget.candidateLimit()
                || !exhausted && size < budget.resultLimit() && !limited) throw integrity();
    }

    private static void progressItem(SourceProgressItem item) {
        required(item); uuid(item.sourceId()); uuid(item.actionId()); range(item.actionRevision(), 0, 1000, "动作修订");
        required(item.tracking()); GrowthRules.evidence(item.origin()); required(item.disposition()); state(item.originState());
        if (item.origin().kind() != EvidenceKind.MANUAL_FEEDBACK && item.origin().kind() != EvidenceKind.AI_OBSERVATION
                && item.origin().kind() != EvidenceKind.RECOLLECTION) throw integrity();
        if ((item.latestJudgment() == null) != (item.latestOutcome() == null)) throw integrity();
        if (item.latestJudgment() != null && GrowthRules.evidence(item.latestJudgment()).kind() != EvidenceKind.REPLAY_JUDGMENT) throw integrity();
        if (item.domainApplicable() && (item.latestJudgment() == null || item.tracking() != ReviewDtos.Tracking.TRACKING)
                || (item.tracking() == ReviewDtos.Tracking.DROPPED) != (item.disposition() == SourceDisposition.DROPPED)) throw integrity();
        hashText(item.verificationHash());
        var route = required(item.continuation());
        if (route.route() != TodayDtos.Route.REVIEW || route.item() != null || route.attempt() != null
                || route.action() == null || !item.actionId().equals(route.action().action())) throw integrity();
    }

    private void selection(String owner, TargetRef target, String topicId) {
        try { targets.filter(owner, target, topicId); }
        catch (ApiException exception) {
            if (exception.status() == 404) throw exception;
            failed(exception); throw unavailable();
        } catch (RuntimeException exception) { failed(exception); throw unavailable(); }
    }

    private static Capability failed(RuntimeException exception) {
        LOG.warn("event=growth_source_failed exceptionType={} traceId={}", exception.getClass().getSimpleName(), MDC.get("traceId"));
        boolean broken = !(exception instanceof DataAccessException)
                && (exception instanceof IllegalStateException || exception instanceof IllegalArgumentException
                || exception instanceof NullPointerException || exception instanceof ApiException api && api.status() == 400);
        return new Capability(Availability.ERROR, broken ? CapabilityReason.SOURCE_INTEGRITY_FAILED : CapabilityReason.SOURCE_READ_FAILED);
    }

    private static Scope recorded(Scope scope) {
        var filter = scope.filter();
        return new Scope(scope.window(), new Filter(filter.target(), TargetMode.RECORDED_ONLY, filter.topicId(), filter.topicMode(), filter.dimension()));
    }

    private static AssessmentLevel level(EvidenceKind kind) {
        return switch (kind) {
            case AI_OBSERVATION -> AssessmentLevel.AI_UNREVIEWED;
            case REVIEWED_OBSERVATION -> AssessmentLevel.REVIEWED_OBSERVATION;
            default -> AssessmentLevel.SELF_RECORDED;
        };
    }

    private static boolean future(Scope scope) {
        return !scope.window().startAt().isBefore(scope.window().observedUntil());
    }

    private static EmptyState emptyState(List<KindCount> groups) {
        boolean recollection = false;
        for (var group : groups) {
            if (group.count().value() != null && group.count().value() > 0) {
                if (group.kind() != EvidenceKind.RECOLLECTION) return EmptyState.HAS_RECORDS;
                recollection = true;
            }
        }
        if (groups.stream().anyMatch(group -> group.capability().status() != Availability.READY
                || group.scanLimited() || group.count().mode() != CountMode.EXACT)) return EmptyState.UNKNOWN;
        return recollection ? EmptyState.RECOLLECTION_ONLY : EmptyState.NO_RECORD;
    }

    private static EnumSet<Limitation> limitations(Filter filter, boolean limited) {
        var result = EnumSet.of(Limitation.ORIGINAL_TARGET_ACTIVITY_ONLY, Limitation.SEMANTICS_NOT_CERTIFIED,
                Limitation.NO_SINGLE_QUESTION_DURATION);
        if (filter.target() != null || filter.topicId() != null || filter.dimension() != null) result.add(Limitation.FILTERED_SCOPE_ONLY);
        if (filter.topicMode() == TopicMode.INCLUDE_CURRENT_CLASSIFICATION) result.add(Limitation.CURRENT_TOPIC_CLASSIFICATION);
        if (limited) result.add(Limitation.COUNT_SCAN_LIMITED);
        return result;
    }

    private static Context context(Instant asOf) {
        Instant checkedAt = now();
        if (checkedAt.isBefore(asOf)) throw unavailable();
        return new Context(asOf, checkedAt, checkedAt);
    }
}
