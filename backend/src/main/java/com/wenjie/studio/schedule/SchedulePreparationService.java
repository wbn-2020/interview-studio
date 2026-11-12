package com.wenjie.studio.schedule;

import com.wenjie.studio.bank.CollectionDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.planning.PlanningDtos;
import com.wenjie.studio.planning.PlanningRules;
import com.wenjie.studio.planning.PlanningService;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.review.TodayActionProgress.TargetRef;
import com.wenjie.studio.today.TodayDtos;
import com.wenjie.studio.today.TodayPlanDtos;
import com.wenjie.studio.today.TodayPlanMapper;
import com.wenjie.studio.today.TodayPlanService;
import com.wenjie.studio.today.TodayService;
import com.wenjie.studio.training.TrainingAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.wenjie.studio.schedule.SchedulePreparationDtos.*;
import static com.wenjie.studio.schedule.SchedulePreparationRows.*;
import static com.wenjie.studio.schedule.SchedulePreparationRules.*;

@Service
public class SchedulePreparationService {
    private final SchedulePreparationMapper mapper;
    private final ScheduleService schedule;
    private final PlanningService planning;
    private final TodayService today;
    private final TodayPlanMapper planReads;
    private final TodayPlanService plans;
    private final IdempotencyService commands;
    private final TrainingAccessGuard access;
    private final JsonMapper json;

    public SchedulePreparationService(SchedulePreparationMapper mapper, ScheduleService schedule, PlanningService planning,
                                       TodayService today, TodayPlanMapper planReads, TodayPlanService plans,
                                       IdempotencyService commands, TrainingAccessGuard access, JsonMapper json) {
        this.mapper = mapper; this.schedule = schedule; this.planning = planning; this.today = today;
        this.planReads = planReads; this.plans = plans; this.commands = commands; this.access = access; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LinkPage list(String owner, String eventId, LinkStatus status, String cursor, int limit) {
        guard(owner, eventId, "GET", "/preparation-links"); range(limit, 1, 50, "每页数量");
        EventReference event = event(requireEvent(owner, eventId)); Instant now = now();
        LinkCursor position = cursor(cursor, owner, event, status, now);
        var rows = mapper.links(owner, eventId, status, position == null ? null : position.afterId(), limit + 1);
        var page = rows.stream().limit(limit).map(row -> {
            checkedRow(owner, eventId, row); var source = project(owner, row, now);
            return new LinkEntry(link(row), source.title(), source.stamp(), source.status(), source.targets(), source.pending());
        }).toList();
        Instant asOf = position == null ? now : position.asOf();
        String next = rows.size() > limit ? cursor(new LinkCursor(1, "interview-preparation-links", owner, eventId,
                event.revision(), status, asOf, page.getLast().link().id())) : null;
        return bounded(json, new LinkPage(event, page, next, asOf, now), PAGE_BYTES);
    }

    public PreparationLink create(String owner, String eventId, String key, CreatePreparationLink request) {
        uuid(owner); uuid(eventId); SchedulePreparationRules.create(request);
        return commands.execute(owner, key, operation("POST", eventId, "/preparation-links"), request, PreparationLink.class, () -> {
            EventRow event = expectedEvent(owner, eventId, request.expectedEventRevision()); planned(event);
            LinkRow existing = mapper.bySource(owner, eventId, request.kind(), request.sourceId());
            if (existing != null) throw conflict("PREPARATION_LINK_EXISTS", "该来源已有准备关联，请读取原身份后恢复或明确换版。", existing.revision());
            Instant now = now(); Projection source = project(owner, request.kind(), request.sourceId(), request.sourceVersionId(), now);
            expectSource(source, request.expectedSource()); available(source, true);
            var row = new LinkRow(UUID.randomUUID().toString(), owner, eventId, 0, request.kind().name(), request.sourceId(),
                    request.sourceVersionId(), LinkStatus.ACTIVE.name(), now, now);
            return persist(event, null, row, LinkChange.CREATED, source.stamp());
        });
    }

    public PreparationLink status(String owner, String eventId, String id, String key, ChangePreparationLinkStatus request) {
        uuid(owner); uuid(eventId); uuid(id); changeStatus(request);
        return commands.execute(owner, key, operation("POST", eventId, "/preparation-links/" + id + "/status"), request, PreparationLink.class, () -> {
            EventRow event = expectedEvent(owner, eventId, request.expectedEventRevision());
            LinkRow before = expectedLink(owner, eventId, id, request.expectedRevision());
            if (before.status().equals(request.status().name())) return link(before);
            Instant now = now(); PreparationSourceStamp sourceStamp = null;
            if (request.status() == LinkStatus.ACTIVE) {
                planned(event); Projection source = project(owner, before, now); available(source, false); sourceStamp = source.stamp();
            }
            // 移除不重新授权来源；恢复保留确切旧题单版本，换版必须另走明确命令。
            LinkRow after = nextRow(before, before.sourceVersionId(), request.status(), now);
            return persist(event, before, after, request.status() == LinkStatus.ACTIVE ? LinkChange.RESTORED : LinkChange.REMOVED, sourceStamp);
        });
    }

    public PreparationLink version(String owner, String eventId, String id, String key, ChangeCollectionVersion request) {
        uuid(owner); uuid(eventId); uuid(id); changeVersion(request);
        return commands.execute(owner, key, operation("PUT", eventId, "/preparation-links/" + id + "/collection-version"), request, PreparationLink.class, () -> {
            EventRow event = expectedEvent(owner, eventId, request.expectedEventRevision()); planned(event);
            LinkRow before = expectedLink(owner, eventId, id, request.expectedRevision()); active(before);
            if (!LinkKind.COLLECTION.name().equals(before.sourceKind())) throw invalid("只有题单或路线关联可以换版。");
            Instant now = now(); Projection source = project(owner, LinkKind.COLLECTION, before.sourceId(), request.sourceVersionId(), now);
            expectSource(source, request.expectedSource()); available(source, true);
            if (request.sourceVersionId().equals(before.sourceVersionId())) return link(before);
            return persist(event, before, nextRow(before, request.sourceVersionId(), LinkStatus.ACTIVE, now), LinkChange.VERSION_CHANGED, source.stamp());
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LinkHistoryPage history(String owner, String eventId, String id, Long before, int limit) {
        guard(owner, eventId, "GET", "/preparation-links/" + uuid(id) + "/history");
        requireEvent(owner, eventId); requireLink(owner, eventId, id); range(limit, 1, 50, "每页数量");
        if (before != null) linkRevision(before);
        var rows = mapper.history(owner, eventId, id, before, limit + 1);
        var page = rows.stream().limit(limit).map(row -> {
            LinkHistory value = json.readValue(row.snapshotJson(), LinkHistory.class);
            PreparationSourceStamp stamp = row.sourceStampJson() == null ? null : json.readValue(row.sourceStampJson(), PreparationSourceStamp.class);
            if (value == null || value.snapshot() == null || !id.equals(value.snapshot().id()) || !eventId.equals(value.snapshot().eventId())
                    || value.snapshot().revision() != row.revision() || !value.change().name().equals(row.changeKind())
                    || value.eventRevision() != row.eventRevision() || !value.createdAt().equals(row.createdAt())
                    || !value.createdAt().equals(value.snapshot().updatedAt()) || !Objects.equals(value.sourceStamp(), stamp)) throw integrity();
            return value;
        }).toList();
        return bounded(json, new LinkHistoryPage(page, rows.size() > limit ? page.getLast().snapshot().revision() : null), PAGE_BYTES);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReschedulePlanPreview preview(String owner, String eventId, ReschedulePlanRequest request) {
        guard(owner, eventId, "POST", "/reschedule-plan-preview"); SchedulePreparationRules.preview(request);
        return calculate(owner, eventId, request, now()).preview();
    }

    public AppliedReschedulePlan apply(String owner, String eventId, String key, ApplyReschedulePlan request) {
        uuid(owner); uuid(eventId); SchedulePreparationRules.apply(request);
        return commands.execute(owner, key, operation("POST", eventId, "/reschedule-plan"), request, AppliedReschedulePlan.class, () -> {
            Instant now = now(); Calculation calculated = calculate(owner, eventId, request.request(), now);
            ReschedulePlanPreview preview = calculated.preview();
            if (!preview.previewHash().equals(request.expectedPreviewHash())) {
                throw conflict("RESCHEDULE_PLAN_CHANGED", "准备关联、来源或改期条件已变化，请重新预览。", null);
            }
            if (preview.linkChecks().stream().anyMatch(check -> !check.reasons().equals(List.of(MoveReason.MOVABLE)))) {
                throw conflict("PREPARATION_LINK_NOT_MOVABLE", "选中项包含不能联动改日的来源，请调整选择后重新预览。", null);
            }
            clock(now, calculated.event().updatedAt());
            List<TodayPlanDtos.ActionDateMove> actionMoves = new ArrayList<>();
            Map<String, RescheduleLinkCheck> checks = new HashMap<>();
            for (var check : preview.linkChecks()) checks.put(check.link().id(), check);
            // 先核对全批再进入写阶段；领域写入口仍执行自身CAS，任何失败由同一owner事务回滚。
            for (var change : preview.changes()) {
                var check = checks.get(change.linkId()); Projection source = calculated.sources().get(change.linkId());
                if (check.link().kind() == LinkKind.PREPARATION_TASK) clock(now, source.updatedAt());
                else {
                    clock(now, check.placement().updatedAt());
                    actionMoves.add(new TodayPlanDtos.ActionDateMove(check.placement().id(), check.placement().revision(), source.todayStamp(), change.to().plannedDate()));
                }
            }
            var event = schedule.rescheduleWithinCommand(owner, eventId, request.request().expectedEventRevision(), preview.to(),
                    request.request().remindBeforeMinutes(), request.reason(), now);
            List<PlanningDtos.PreparationTask> tasks = new ArrayList<>();
            for (var change : preview.changes()) {
                var check = checks.get(change.linkId());
                if (check.link().kind() == LinkKind.PREPARATION_TASK) tasks.add(planning.rescheduleDateWithinCommand(owner,
                        check.link().sourceId(), check.sourceStamp().task().revision(), change.to().plannedDate(), now));
            }
            var placements = plans.rescheduleActionsWithinCommand(owner, List.copyOf(actionMoves), request.reason(), now);
            return new AppliedReschedulePlan(event, List.copyOf(tasks), placements, preview.linkChecks());
        });
    }

    private Calculation calculate(String owner, String eventId, ReschedulePlanRequest request, Instant now) {
        EventRow currentEvent = expectedEvent(owner, eventId, request.expectedEventRevision()); planned(currentEvent);
        RescheduleBasis basis = schedule.rescheduleBasis(owner, eventId, request.expectedEventRevision(), request.time(), request.remindBeforeMinutes());
        if (!event(currentEvent).equals(basis.event())) throw integrity();
        List<LinkRow> selected = request.selectedLinkIds().stream().map(id -> { LinkRow row = requireLink(owner, eventId, id); active(row); return row; }).toList();
        var identities = selected.stream().filter(row -> !row.sourceKind().equals(LinkKind.COLLECTION.name()))
                .map(row -> new SourceIdentity(row.sourceKind(), row.sourceId())).distinct().toList();
        var metadata = mapper.metadata(owner, eventId, identities);
        if (metadata.size() > 500) throw limit("本次联动涉及超过500条关联元数据，请缩小范围或仅改面试时间。");
        Map<String, LinkRow> relationRows = new HashMap<>(); String previous = null;
        for (var row : metadata) {
            checkedRow(owner, row.eventId(), row.row()); active(row.row()); revision(row.eventRevision());
            time(row.eventStartAt()); PlanningRules.zone(row.eventZoneId()); ScheduleDtos.Status.valueOf(row.eventStatus());
            if (relationRows.put(row.id(), row.row()) != null || previous != null && previous.compareTo(row.id()) >= 0) throw integrity();
            previous = row.id();
        }
        Map<String, LocalDate> overrides = new HashMap<>();
        for (var override : request.dateOverrides()) overrides.put(override.linkId(), override.plannedDate());
        Map<String, Projection> sources = new HashMap<>(); List<RescheduleLinkCheck> checks = new ArrayList<>();
        List<PreparationDateChange> changes = new ArrayList<>();
        for (LinkRow row : selected) {
            if (!row.equals(relationRows.get(row.id()))) throw integrity();
            Projection source = project(owner, row, now); sources.put(row.id(), source);
            var others = metadata.stream().filter(other -> !eventId.equals(other.eventId()) && row.sourceKind().equals(other.sourceKind())
                    && row.sourceId().equals(other.sourceId()) && ScheduleDtos.Status.PLANNED.name().equals(other.eventStatus()))
                    .map(other -> new OtherInterview(other.id(), other.revision(), other.eventId(), other.eventRevision(), ScheduleDtos.Status.PLANNED,
                            other.eventTitle(), other.eventStartAt(), other.eventZoneId()))
                    .sorted(Comparator.comparing(OtherInterview::eventId)).toList();
            RescheduleLinkCheck check = evaluate(row, source, others, basis, overrides.get(row.id())); checks.add(check);
            if (check.proposed() != null && !check.proposed().equals(check.from())) changes.add(new PreparationDateChange(row.id(), check.from(), check.proposed()));
        }
        List<RescheduleLimitation> limitations = List.of(RescheduleLimitation.values());
        List<RelationMetadata> relations = metadata.stream().map(row -> new RelationMetadata(link(row.row()), row.eventRevision(), ScheduleDtos.Status.valueOf(row.eventStatus()))).toList();
        String digest = hash(new HashBasis(eventId, request, basis.event(), basis.from(), basis.to(), basis.remindAt(), relations, List.copyOf(checks), List.copyOf(changes), limitations));
        var preview = new ReschedulePlanPreview(basis.from(), basis.to(), basis.remindAt(), List.copyOf(checks), List.copyOf(changes), digest, limitations);
        return new Calculation(currentEvent, bounded(json, preview, PAGE_BYTES), Map.copyOf(sources));
    }

    private RescheduleLinkCheck evaluate(LinkRow row, Projection source, List<OtherInterview> others, RescheduleBasis basis, LocalDate override) {
        EnumSet<MoveReason> reasons = EnumSet.noneOf(MoveReason.class); List<MoveWarning> warnings = new ArrayList<>();
        LinkKind kind = LinkKind.valueOf(row.sourceKind()); PlanDate proposed = null;
        if (kind == LinkKind.COLLECTION) {
            reasons.add(MoveReason.REFERENCE_ONLY);
            if (source.status().collection().versionState() != CollectionDtos.ReferenceState.CURRENT) reasons.add(MoveReason.SOURCE_CHANGED);
        } else {
            if (kind == LinkKind.PREPARATION_TASK) {
                switch (source.status().task().status()) {
                    case TODO -> { }
                    case IN_PROGRESS -> reasons.add(MoveReason.STARTED);
                    case PAUSED -> reasons.add(MoveReason.PAUSED);
                    case DONE, CANCELLED -> reasons.add(MoveReason.TERMINAL);
                }
            } else {
                if (source.status().action().status() != ReviewDtos.ActionStatus.OPEN) reasons.add(MoveReason.SOURCE_CHANGED);
                if (source.pending() == null || source.pending() == 0) reasons.add(MoveReason.NO_PENDING_WORK);
                if (source.placement() != null && source.placement().visibility() == TodayPlanDtos.Visibility.PAUSED) reasons.add(MoveReason.PAUSED);
            }
            if (source.started()) reasons.add(MoveReason.STARTED);
            if (source.date() == null) reasons.add(MoveReason.NO_DATE);
            if (source.placement() != null && source.placement().dateLocked()) reasons.add(MoveReason.DATE_LOCKED);
            if (!others.isEmpty()) reasons.add(MoveReason.MULTIPLE_EVENTS);
            if (reasons.isEmpty()) {
                reasons.add(MoveReason.MOVABLE);
                try {
                    ZoneId zone = ZoneId.of(source.date().zoneId());
                    LocalDate fromEvent = basis.from().startAt().atZone(zone).toLocalDate(), toEvent = basis.to().startAt().atZone(zone).toLocalDate();
                    date(fromEvent); date(toEvent);
                    LocalDate selectedDate = override == null ? source.date().plannedDate().plusDays(ChronoUnit.DAYS.between(fromEvent, toEvent)) : override;
                    date(selectedDate); proposed = new PlanDate(selectedDate, source.date().zoneId());
                    if (selectedDate.isAfter(toEvent)) warnings.add(MoveWarning.AFTER_INTERVIEW_DATE);
                } catch (DateTimeException | ArithmeticException exception) { throw invalid("按原时区计算的准备日期超出可保存范围。"); }
            }
        }
        return new RescheduleLinkCheck(link(row), source.stamp(), source.status(), source.pending(), source.placement(), source.date(), proposed,
                source.started(), others, List.copyOf(reasons), List.copyOf(warnings));
    }

    private Projection project(String owner, LinkRow row, Instant now) {
        return project(owner, LinkKind.valueOf(row.sourceKind()), row.sourceId(), row.sourceVersionId(), now);
    }
    private Projection project(String owner, LinkKind kind, String sourceId, String versionId, Instant now) {
        if (kind == LinkKind.COLLECTION) {
            CollectionRow row = mapper.collection(owner, sourceId, versionId);
            if (row == null) throw new ApiException(404, "COLLECTION_NOT_FOUND", "关联的题单版本不存在或不可访问。");
            if (!owner.equals(row.ownerId()) || !sourceId.equals(row.id()) || !versionId.equals(row.versionId()) || row.versionNumber() < 1) throw integrity();
            var status = CollectionDtos.Status.valueOf(row.status());
            var state = status == CollectionDtos.Status.ARCHIVED ? CollectionDtos.ReferenceState.ARCHIVED
                    : row.versionId().equals(row.currentVersionId()) ? CollectionDtos.ReferenceState.CURRENT : CollectionDtos.ReferenceState.SUPERSEDED;
            var stamp = new PreparationSourceStamp(kind, null, null, new CollectionStamp(row.revision(), row.currentVersionId())); stamp(stamp, kind);
            return new Projection(row.title(), stamp, new PreparationSourceStatus(kind, null, null,
                    new CollectionState(CollectionDtos.CollectionKind.valueOf(row.kind()), status, row.currentVersionId(), row.versionNumber(), state)),
                    List.of(), null, null, null, false, null, null);
        }
        TodayDtos.SourceKind sourceKind = TodayDtos.SourceKind.valueOf(kind.name());
        var reference = new TodayDtos.SourceRef(sourceKind, sourceId);
        var source = today.sourceForPlan(owner, reference, "UTC", now);
        if (!reference.equals(source.source()) || source.stamp().kind() != sourceKind || source.sourceStatus().kind() != sourceKind) throw integrity();
        var facts = planReads.startedFacts(owner, sourceKind, sourceId);
        if (facts == null || facts.sourceUpdatedAt() == null) throw integrity();
        var placement = source.placement();
        if (placement != null && !reference.equals(placement.source())) throw integrity();
        PreparationSourceStamp stamp; PreparationSourceStatus status; PlanDate date = null; boolean started;
        if (kind == LinkKind.PREPARATION_TASK) {
            stamp = new PreparationSourceStamp(kind, source.stamp().task(), null, null);
            status = new PreparationSourceStatus(kind, source.sourceStatus().task(), null, null);
            started = status.task().startedAt() != null;
            if (source.time().kind() == TodayDtos.TimeKind.LOCAL_DATE) date = new PlanDate(source.time().localDate(), source.time().zoneId());
        } else {
            stamp = new PreparationSourceStamp(kind, null, source.stamp().action(), null);
            status = new PreparationSourceStatus(kind, null, source.sourceStatus().action(), null);
            started = facts.actionStarted();
            if (placement != null && placement.plannedDate() != null) date = new PlanDate(placement.plannedDate(), placement.planZoneId());
        }
        stamp(stamp, kind);
        if (date != null) { date(date.plannedDate()); PlanningRules.zone(date.zoneId()); }
        var targets = source.targetRefs().stream().map(target -> new TargetRef(target.itemId(), target.versionId())).toList();
        return new Projection(source.title(), stamp, status, targets, source.pendingCount(), placement, date, started, facts.sourceUpdatedAt(), source.stamp());
    }

    private static void available(Projection source, boolean currentCollectionRequired) {
        boolean allowed = switch (source.stamp().kind()) {
            case PREPARATION_TASK -> source.status().task().status() != PlanningDtos.Status.DONE && source.status().task().status() != PlanningDtos.Status.CANCELLED;
            case REVIEW_ACTION -> source.status().action().status() == ReviewDtos.ActionStatus.OPEN && source.pending() != null && source.pending() > 0;
            case COLLECTION -> source.status().collection().status() == CollectionDtos.Status.ACTIVE
                    && (!currentCollectionRequired || source.status().collection().versionState() == CollectionDtos.ReferenceState.CURRENT);
        };
        if (!allowed) throw sourceChanged();
    }
    private static void expectSource(Projection source, PreparationSourceStamp expected) { if (!source.stamp().equals(expected)) throw sourceChanged(); }
    private PreparationLink persist(EventRow event, LinkRow before, LinkRow after, LinkChange change, PreparationSourceStamp stamp) {
        var snapshot = link(after);
        var history = bounded(json, new LinkHistory(change, snapshot, event.revision(), stamp, after.updatedAt()), HISTORY_BYTES);
        if (before == null) mapper.insert(after);
        else if (mapper.update(after, before.revision()) != 1) throw conflict("PREPARATION_LINK_REVISION_CONFLICT", "准备关联已变化，本次修改未提交。", null);
        mapper.insertHistory(after.ownerId(), after.eventId(), after.id(), after.revision(), event.revision(), change,
                json.writeValueAsString(history), stamp == null ? null : json.writeValueAsString(stamp), after.updatedAt());
        return snapshot;
    }
    private static LinkRow nextRow(LinkRow before, String versionId, LinkStatus status, Instant now) {
        clock(now, before.updatedAt());
        return new LinkRow(before.id(), before.ownerId(), before.eventId(), next(before.revision()), before.sourceKind(), before.sourceId(),
                versionId, status.name(), before.createdAt(), now);
    }
    private EventRow requireEvent(String owner, String eventId) {
        EventRow row = mapper.event(owner, eventId);
        if (row == null) throw new ApiException(404, "SCHEDULE_NOT_FOUND", "面试安排不存在或不可访问。");
        if (!owner.equals(row.ownerId()) || !eventId.equals(row.id())) throw integrity();
        return row;
    }
    private EventRow expectedEvent(String owner, String eventId, long expected) {
        EventRow row = requireEvent(owner, eventId);
        if (row.revision() != expected) throw conflict("REVISION_CONFLICT", "面试安排已变化，请保留输入后重新读取。", row.revision());
        return row;
    }
    private LinkRow requireLink(String owner, String eventId, String id) {
        LinkRow row = mapper.link(owner, eventId, id);
        if (row == null) throw new ApiException(404, "PREPARATION_LINK_NOT_FOUND", "准备关联不存在或不属于当前面试。");
        checkedRow(owner, eventId, row); if (!id.equals(row.id())) throw integrity(); return row;
    }
    private LinkRow expectedLink(String owner, String eventId, String id, long expected) {
        LinkRow row = requireLink(owner, eventId, id);
        if (row.revision() != expected) throw conflict("PREPARATION_LINK_REVISION_CONFLICT", "准备关联已变化，请保留输入后重新读取。", row.revision());
        return row;
    }
    private static void checkedRow(String owner, String eventId, LinkRow row) {
        if (row == null || !owner.equals(row.ownerId()) || !eventId.equals(row.eventId())) throw integrity();
        uuid(row.id()); uuid(row.eventId()); uuid(row.sourceId()); linkRevision(row.revision());
        sourceVersion(LinkKind.valueOf(row.sourceKind()), row.sourceVersionId()); LinkStatus.valueOf(row.status()); time(row.createdAt()); time(row.updatedAt());
        if (row.updatedAt().isBefore(row.createdAt())) throw integrity();
    }
    private static void planned(EventRow row) {
        if (!ScheduleDtos.Status.PLANNED.name().equals(row.status())) throw conflict("EVENT_CLOSED", "已完成或取消的面试不能新增、恢复准备关联或改期。", null);
    }
    private static void active(LinkRow row) {
        if (!LinkStatus.ACTIVE.name().equals(row.status())) throw conflict("PREPARATION_LINK_NOT_ACTIVE", "该关联已移除，请先明确恢复。", row.revision());
    }
    private void guard(String owner, String eventId, String method, String suffix) {
        uuid(owner); uuid(eventId); access.checkCommand(owner, operation(method, eventId, suffix));
    }
    private static String operation(String method, String eventId, String suffix) { return method + " /api/v1/schedule/interviews/" + eventId + suffix; }
    private static IllegalStateException integrity() { return new IllegalStateException("面试准备关联或来源投影不完整"); }

    private record Projection(String title, PreparationSourceStamp stamp, PreparationSourceStatus status, List<TargetRef> targets,
                               Integer pending, TodayPlanDtos.Placement placement, PlanDate date, boolean started, Instant updatedAt,
                               TodayDtos.SourceStamp todayStamp) {}
    private record RelationMetadata(PreparationLink link, long eventRevision, ScheduleDtos.Status eventStatus) {}
    private record HashBasis(String eventId, ReschedulePlanRequest request, EventReference event, ScheduleDtos.ResolvedTime from,
                             ScheduleDtos.ResolvedTime to, Instant remindAt, List<RelationMetadata> activeLinks,
                             List<RescheduleLinkCheck> linkChecks, List<PreparationDateChange> changes, List<RescheduleLimitation> limitations) {}
    private record Calculation(EventRow event, ReschedulePlanPreview preview, Map<String, Projection> sources) {}
}
