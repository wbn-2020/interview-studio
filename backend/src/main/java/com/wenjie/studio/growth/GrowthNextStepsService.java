package com.wenjie.studio.growth;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.memory.MemoryDtos;
import com.wenjie.studio.memory.MemoryReviewDtos;
import com.wenjie.studio.planning.PlanningDtos;
import com.wenjie.studio.prepare.GrowthTargetQuery;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.ReviewGrowthQuery;
import com.wenjie.studio.today.TodayDtos;
import com.wenjie.studio.today.TodayPlanDtos;
import com.wenjie.studio.today.TodayService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import static com.wenjie.studio.growth.GrowthDtos.*;
import static com.wenjie.studio.growth.GrowthNextStepRows.*;
import static com.wenjie.studio.growth.GrowthRules.*;

/** 有界建议只汇合已核对的原域事实；读取本身不创建任务或改变复习排程。 */
@Service
public class GrowthNextStepsService {
    private static final int QUALIFIED_LIMIT = 100;
    private static final List<NextStepReason> REASON_ORDER = List.of(NextStepReason.ACTION_REVIEW_REQUIRED,
            NextStepReason.MEMORY_REVIEW_REQUIRED, NextStepReason.ACTION_PENDING, NextStepReason.MEMORY_DUE,
            NextStepReason.TASK_UNSCHEDULED, NextStepReason.USER_MARKED_INSUFFICIENT);
    private final GrowthNextStepMapper mapper;
    private final GrowthPreferenceService preferences;
    private final GrowthTargetQuery targets;
    private final ReviewGrowthQuery review;
    private final TodayService today;
    private final JsonMapper json;

    public GrowthNextStepsService(GrowthNextStepMapper mapper, GrowthPreferenceService preferences, GrowthTargetQuery targets,
                                 ReviewGrowthQuery review, TodayService today, JsonMapper json) {
        this.mapper = mapper; this.preferences = preferences; this.targets = targets;
        this.review = review; this.today = today; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NextSteps nextSteps(String owner, NextStepsQuery query) {
        uuid(owner); required(query); String zone = zone(query.zoneId());
        TargetRef target = target(query.targetId(), query.targetVersionId()); int limit = nextStepSize(query.limit());
        Instant asOf = now();
        try {
            if (target != null) targets.require(owner, target);
            var action = actions(owner, target, zone, asOf);
            var memory = target == null ? sources(owner, TodayDtos.SourceKind.MEMORY_CARD, null, zone, asOf) : Slice.empty();
            var task = sources(owner, TodayDtos.SourceKind.PREPARATION_TASK, target, zone, asOf);
            var requirements = requirements(owner, target);
            var slices = List.of(action, memory, task, requirements);
            var candidates = slices.stream().flatMap(value -> value.items().stream()).toList();
            var shown = candidates.stream().filter(value -> !value.hidden()).map(Candidate::step)
                    .sorted(Comparator.comparingInt(NextStep::effectivePriority).reversed()
                            .thenComparingInt(value -> reasonRank(value.reasonCodes()))
                            .thenComparing(value -> subjectKey(identity(value.subject()))))
                    .limit(limit).toList();
            boolean limited = slices.stream().anyMatch(Slice::limited);
            int examined = slices.stream().mapToInt(Slice::examined).sum();
            Instant checked = now();
            return readResponse(json, new NextSteps(new Context(asOf, checked, checked),
                    new NextStepScope(zone, asOf.atZone(ZoneId.of(zone)).toLocalDate(), target),
                    new Capability(Availability.READY, CapabilityReason.NONE), shown, examined, limited,
                    new Count((long) candidates.size() - shown.size(), limited ? CountMode.LOWER_BOUND : CountMode.EXACT),
                    List.of(Limitation.NEXT_STEPS_BOUNDED, Limitation.INDIRECT_LINEAGE_NOT_CHECKED)));
        } catch (ApiException exception) {
            // 内层只读投影异常可能已标记回滚；整个请求失败，不将失败域折算成零项。
            if (exception.status() == 400 || exception.status() == 503 || "TODAY_SOURCE_NOT_FOUND".equals(exception.code())) throw unavailable();
            throw exception;
        } catch (RuntimeException exception) { throw unavailable(); }
    }

    private record Candidate(NextStep step, boolean hidden) {}
    private record Slice(List<Candidate> items, int examined, boolean limited) {
        static Slice empty() { return new Slice(List.of(), 0, false); }
    }

    private Slice actions(String owner, TargetRef target, String zone, Instant asOf) {
        var filter = new ProgressFilter(target, null, TopicMode.RECORDED_ONLY, null);
        var projected = new HashMap<String, TodayDtos.TodayItem>();
        var accepted = new LinkedHashMap<String, EnumSet<NextStepReason>>();
        var skipped = new HashSet<String>();
        int examined = 0; String after = null; boolean exhausted = false; boolean limited = false;
        while (examined < MAX_CANDIDATES && accepted.size() < QUALIFIED_LIMIT && !exhausted) {
            int size = Math.min(49, MAX_CANDIDATES - examined);
            var page = review.sourceProgress(owner, filter, null, after, new QueryBudget(size, size));
            if (page == null || page.items() == null || page.scanned() < 0 || page.scanned() > size
                    || page.items().size() > page.scanned() || page.scanned() == 0 && !page.exhausted()) throw integrity();
            if (page.scanned() > 0 && (page.lastConsumedSourceId() == null
                    || after != null && page.lastConsumedSourceId().compareTo(after) <= 0)) throw integrity();
            examined += page.scanned(); after = page.lastConsumedSourceId(); exhausted = page.exhausted();
            var newIds = page.items().stream().filter(value -> actionReason(value) != null).map(SourceProgressItem::actionId)
                    .distinct().filter(id -> !projected.containsKey(id) && !skipped.contains(id)).toList();
            var sizes = new HashMap<String, Integer>();
            if (!newIds.isEmpty()) {
                var rows = mapper.actionSizes(owner, newIds); requireRows(rows, newIds.size());
                if (rows.size() != newIds.size()) throw integrity();
                for (var row : rows) {
                    uuid(row.actionId()); range(row.sourceCount(), 1, 20, "动作来源数量");
                    if (!newIds.contains(row.actionId()) || sizes.put(row.actionId(), row.sourceCount()) != null) throw integrity();
                }
            }
            var selected = new ArrayList<TodayDtos.SourceRef>();
            for (String id : newIds) {
                int cost = sizes.get(id);
                // 原动作 stamp 会核对该动作全部来源，必须与前面的逐 source 扫描共用 500 额度。
                if (cost > MAX_CANDIDATES - examined) { skipped.add(id); limited = true; continue; }
                examined += cost; selected.add(new TodayDtos.SourceRef(TodayDtos.SourceKind.REVIEW_ACTION, id));
            }
            if (!selected.isEmpty()) {
                var items = today.sourcesForPlan(owner, selected, zone, asOf); validateProjection(selected, items);
                for (var item : items) {
                    if (item.sourceStatus().action() == null || item.sourceStatus().action().sourceCount() != sizes.get(item.source().id())) throw integrity();
                    projected.put(item.source().id(), item);
                }
            }
            for (var value : page.items()) {
                NextStepReason reason = actionReason(value); if (reason == null) continue;
                var item = projected.get(value.actionId()); if (item == null) continue;
                if (item.stamp().action().revision() != value.actionRevision()) throw integrity();
                if (item.sourceStatus().action().status() != ReviewDtos.ActionStatus.OPEN
                        || item.sourceStatus().action().trackingCount() == 0 || !placementAllows(item, zone, asOf)
                        || !matches(item, target)) continue;
                if (!accepted.containsKey(value.actionId()) && accepted.size() == QUALIFIED_LIMIT) { limited = true; continue; }
                accepted.computeIfAbsent(value.actionId(), ignored -> EnumSet.noneOf(NextStepReason.class)).add(reason);
            }
        }
        var result = new ArrayList<Candidate>();
        for (var entry : accepted.entrySet()) result.add(sourceCandidate(owner, projected.get(entry.getKey()), ordered(entry.getValue())));
        return new Slice(List.copyOf(result), examined, limited || !exhausted);
    }

    private static NextStepReason actionReason(SourceProgressItem value) {
        if (value == null || value.tracking() == null || value.disposition() == null) throw integrity();
        uuid(value.sourceId()); uuid(value.actionId());
        if (value.tracking() != ReviewDtos.Tracking.TRACKING) return null;
        return switch (value.disposition()) {
            case REVIEW_REQUIRED -> NextStepReason.ACTION_REVIEW_REQUIRED;
            case UNASSESSED, PARTIAL, UNHANDLED, UNCERTAIN -> NextStepReason.ACTION_PENDING;
            case DROPPED, SOURCE_UNAVAILABLE, HANDLED -> null;
        };
    }

    private Slice sources(String owner, TodayDtos.SourceKind kind, TargetRef target, String zone, Instant asOf) {
        var result = new ArrayList<Candidate>(); String after = null; int examined = 0; boolean exhausted = false;
        while (examined < MAX_CANDIDATES && result.size() < QUALIFIED_LIMIT && !exhausted) {
            int size = Math.min(49, Math.min(MAX_CANDIDATES - examined, QUALIFIED_LIMIT - result.size()));
            var ids = mapper.sourceCandidates(owner, kind.name(), target == null ? null : target.itemId(),
                    target == null ? null : target.versionId(), after, size + 1);
            validateIds(ids, after, size + 1);
            var selected = ids.stream().limit(size).map(id -> new TodayDtos.SourceRef(kind, id)).toList();
            if (!selected.isEmpty()) {
                var items = today.sourcesForPlan(owner, selected, zone, asOf); validateProjection(selected, items);
                for (var item : items) {
                    examined++; after = item.source().id();
                    if (!placementAllows(item, zone, asOf) || !matches(item, target)) continue;
                    List<NextStepReason> reasons = kind == TodayDtos.SourceKind.MEMORY_CARD ? memoryReasons(item, asOf) : taskReasons(item);
                    if (!reasons.isEmpty()) result.add(sourceCandidate(owner, item, reasons));
                }
            }
            exhausted = ids.size() <= size;
        }
        return new Slice(List.copyOf(result), examined, !exhausted);
    }

    private static List<NextStepReason> taskReasons(TodayDtos.TodayItem item) {
        var state = item.sourceStatus().task(); if (state == null) throw integrity();
        return state.status() == PlanningDtos.Status.TODO && item.time().kind() == TodayDtos.TimeKind.NONE
                && item.reasons().contains(TodayDtos.Reason.TASK_UNSCHEDULED) ? List.of(NextStepReason.TASK_UNSCHEDULED) : List.of();
    }

    private static List<NextStepReason> memoryReasons(TodayDtos.TodayItem item, Instant asOf) {
        var state = item.sourceStatus().memory(); if (state == null || state.eligibility() == null) throw integrity();
        if (state.cardStatus() != MemoryDtos.Status.ACTIVE || state.openAttempt() != null) return List.of();
        if (state.eligibility().blockers().contains(MemoryReviewDtos.BlockReason.REVIEW_REQUIRED)
                && item.reasons().contains(TodayDtos.Reason.MEMORY_SOURCE_REVIEW)) return List.of(NextStepReason.MEMORY_REVIEW_REQUIRED);
        if (state.eligibility().basisReady() && state.scheduleStatus() == MemoryReviewDtos.ScheduleStatus.ACTIVE
                && item.time().kind() == TodayDtos.TimeKind.INSTANT && item.time().instant() != null
                && !item.time().instant().isAfter(asOf)) return List.of(NextStepReason.MEMORY_DUE);
        return List.of();
    }

    private Slice requirements(String owner, TargetRef selectedTarget) {
        var rows = mapper.insufficientRequirements(owner, selectedTarget == null ? null : selectedTarget.itemId(),
                selectedTarget == null ? null : selectedTarget.versionId(), null, 50);
        validateRequirements(rows, selectedTarget, null, 50);
        var result = new ArrayList<Candidate>(); var projections = new HashMap<TargetRef, GrowthTargetQuery.Projection>();
        for (var row : rows) {
            var target = new TargetRef(row.targetId(), row.targetVersionId());
            var projection = projections.computeIfAbsent(target, ref -> {
                try { return targets.require(owner, ref); }
                catch (ApiException exception) { if (exception.status() == 404) throw integrity(); throw exception; }
            });
            if (!projection.current()) throw integrity();
            RequirementMeta requirement;
            try { requirement = projection.requirement(row.requirementId()); }
            catch (ApiException exception) { if (exception.status() == 404) throw integrity(); throw exception; }
            var identity = new RequirementIdentity(target, requirement.id());
            var subject = new Subject(SubjectKind.TARGET_REQUIREMENT, null, null, identity, projection.target().itemRevision());
            result.add(candidate(owner, subject, GrowthPreferenceService.requirementTitle(projection.target(), requirement),
                    List.of(NextStepReason.USER_MARKED_INSUFFICIENT), requirement.priority(), PriorityBasis.TARGET_REQUIREMENT,
                    new NextStepContinuation(NextStepRoute.TARGET_REQUIREMENT, null, identity)));
        }
        boolean limited = false;
        if (rows.size() == 50) {
            var tail = mapper.insufficientRequirements(owner, selectedTarget == null ? null : selectedTarget.itemId(),
                    selectedTarget == null ? null : selectedTarget.versionId(), rows.getLast(), 1);
            validateRequirements(tail, selectedTarget, rows.getLast(), 1); limited = !tail.isEmpty();
        }
        return new Slice(List.copyOf(result), rows.size(), limited);
    }

    private Candidate sourceCandidate(String owner, TodayDtos.TodayItem item, List<NextStepReason> reasons) {
        var placement = item.placement();
        return candidate(owner, new Subject(SubjectKind.SOURCE, item.source(), item.stamp(), null, null), item.title(), reasons,
                placement == null ? 3 : placement.priority(), placement == null ? PriorityBasis.DEFAULT : PriorityBasis.PLACEMENT,
                new NextStepContinuation(NextStepRoute.SOURCE, item.continuation(), null));
    }

    private Candidate candidate(String owner, Subject subject, String title, List<NextStepReason> reasons, int priority,
                                PriorityBasis basis, NextStepContinuation continuation) {
        var preference = preferences.find(owner, identity(subject));
        var step = nextStep(new NextStep(subject, title, reasons, priority, basis,
                preference == null ? null : preference.id(), preference == null ? null : preference.revision(), continuation));
        // 隐藏发生在资格核对之后，仍计入扫描与合格候选预算，只影响成长页的展示。
        return new Candidate(step, preference != null && preference.visibility() == Visibility.HIDDEN);
    }

    private static boolean placementAllows(TodayDtos.TodayItem item, String zone, Instant asOf) {
        var placement = item.placement();
        if (placement == null) return true;
        if (!item.source().equals(placement.source())) throw integrity();
        range(placement.priority(), 1, 5, "来源安排优先级");
        if (placement.visibility() != TodayPlanDtos.Visibility.VISIBLE || placement.dateLocked()) return false;
        if (placement.suppressedOn() != null && placement.suppressedOn().zoneId().equals(zone)
                && placement.suppressedOn().localDate().equals(asOf.atZone(ZoneId.of(zone)).toLocalDate())) return false;
        return placement.plannedDate() == null
                || !placement.plannedDate().isAfter(asOf.atZone(ZoneId.of(placement.planZoneId())).toLocalDate());
    }

    private static boolean matches(TodayDtos.TodayItem item, TargetRef target) {
        if (target == null) return true;
        if (item.source().kind() == TodayDtos.SourceKind.MEMORY_CARD) return false;
        return item.targetRefs().stream().anyMatch(ref -> target.itemId().equals(ref.itemId()) && target.versionId().equals(ref.versionId()));
    }

    private static void validateProjection(List<TodayDtos.SourceRef> refs, List<TodayDtos.TodayItem> items) {
        requireRows(items, refs.size()); if (refs.size() != items.size()) throw integrity();
        for (int i = 0; i < refs.size(); i++) {
            var item = items.get(i);
            if (!refs.get(i).equals(item.source()) || item.sourceStatus() == null || item.time() == null
                    || item.reasons() == null || item.targetRefs() == null || item.continuation() == null) throw integrity();
            subject(new Subject(SubjectKind.SOURCE, item.source(), item.stamp(), null, null));
        }
    }
    private static void validateIds(List<String> rows, String after, int maximum) {
        requireRows(rows, maximum); String previous = after;
        for (String id : rows) { uuid(id); if (previous != null && id.compareTo(previous) <= 0) throw integrity(); previous = id; }
    }
    private static void validateRequirements(List<RequirementCandidate> rows, TargetRef target, RequirementCandidate after, int maximum) {
        requireRows(rows, maximum); String previous = after == null ? null : requirementKey(after);
        for (var row : rows) {
            String key = requirementKey(row);
            if (target != null && (!target.itemId().equals(row.targetId()) || !target.versionId().equals(row.targetVersionId()))
                    || previous != null && key.compareTo(previous) <= 0) throw integrity();
            previous = key;
        }
    }
    private static String requirementKey(RequirementCandidate row) {
        return uuid(row.targetId()) + ":" + uuid(row.targetVersionId()) + ":" + uuid(row.requirementId());
    }
    private static void requireRows(List<?> rows, int maximum) {
        if (rows == null || rows.size() > maximum || rows.stream().anyMatch(Objects::isNull)) throw integrity();
    }
    private static List<NextStepReason> ordered(EnumSet<NextStepReason> reasons) { return REASON_ORDER.stream().filter(reasons::contains).toList(); }
    private static int reasonRank(List<NextStepReason> reasons) { return reasons.stream().mapToInt(REASON_ORDER::indexOf).min().orElseThrow(GrowthRules::integrity); }
}
