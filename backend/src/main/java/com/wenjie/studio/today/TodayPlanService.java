package com.wenjie.studio.today;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.memory.MemoryDtos;
import com.wenjie.studio.memory.MemoryReviewDtos;
import com.wenjie.studio.planning.PlanningDtos;
import com.wenjie.studio.planning.PlanningRules;
import com.wenjie.studio.review.ReviewDtos;
import com.wenjie.studio.schedule.ScheduleDtos;
import com.wenjie.studio.training.TrainingAccessGuard;
import com.wenjie.studio.training.TrainingDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.wenjie.studio.today.TodayDtos.*;
import static com.wenjie.studio.today.TodayPlanDtos.*;
import static com.wenjie.studio.today.TodayPlanRows.*;
import static com.wenjie.studio.today.TodayPlanRules.*;

@Service
public class TodayPlanService {
    private final TodayPlanMapper mapper;
    private final TodayService today;
    private final IdempotencyService commands;
    private final TrainingAccessGuard access;
    private final JsonMapper json;

    public TodayPlanService(TodayPlanMapper mapper, TodayService today, IdempotencyService commands,
                            TrainingAccessGuard access, JsonMapper json) {
        this.mapper = mapper; this.today = today; this.commands = commands; this.access = access; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlacementLookup lookup(String owner, SourceRef source) {
        TodayRules.uuid(owner); source(source);
        today.sourceForPlan(owner, source, "UTC", TodayRules.now());
        return bounded(json, new PlacementLookup(source, placement(mapper.placementBySource(owner, source.kind(), source.id()))), PAGE_BYTES);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlacementPage directory(String owner, PlacementDirectoryQuery request) {
        TodayRules.uuid(owner);
        if (request == null) throw invalid("安排目录查询必填。");
        PlanningRules.zone(request.zoneId()); range(request.limit(), 1, 50, "每页数量");
        Instant now = TodayRules.now(); long generation = mapper.placementGeneration(owner); revision(generation);
        var position = cursor(request.cursor(), owner, request, generation, now);
        var day = TodayRules.context(request.zoneId(), position == null ? now : position.asOf(), now);
        var rows = mapper.directory(owner, request.kind(), request.visibility(), position == null ? null : position.afterUpdatedAt(),
                position == null ? null : position.afterId(), request.limit() + 1);
        var selected = rows.stream().limit(request.limit()).toList();
        var sources = selected.stream().map(row -> placement(row).source()).toList();
        var current = sources.isEmpty() ? List.<TodayItem>of() : today.sourcesForPlan(owner, sources, request.zoneId(), now);
        List<PlacementEntry> items = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            var value = placement(selected.get(i)); var source = current.get(i);
            if (!source.source().equals(value.source())) throw TodayRules.integrity();
            items.add(new PlacementEntry(value, source.title(), source.stamp(), source.sourceStatus(), source.continuation()));
        }
        String next = rows.size() > request.limit() ? cursor(new DirectoryCursor(1, "today-placements", owner, request.zoneId(),
                day.localDate(), day.asOf(), request.kind(), request.visibility(), generation, selected.getLast().updatedAt(), selected.getLast().id())) : null;
        return bounded(json, new PlacementPage(day, List.copyOf(items), next), PAGE_BYTES);
    }

    public Placement create(String owner, String key, CreatePlacement request) {
        if (request == null) throw invalid("安排请求必填。");
        source(request.source()); stamp(request.expectedSource(), request.source().kind()); content(request.source().kind(), request.content()); reason(request.reason());
        return commands.execute(owner, key, "POST /api/v1/today/placements", request, Placement.class, () -> {
            Instant now = TodayRules.now(); var source = checked(owner, request.source(), request.expectedSource(), now);
            var before = placement(mapper.placementBySource(owner, request.source().kind(), request.source().id()));
            if (before != null) throw conflict("PLACEMENT_EXISTS", "该来源已有安排偏好，请重新读取后编辑。", before.revision());
            contentCurrent(source, request.content(), null, now);
            var after = persist(owner, null, source.source(), request.content(), source.stamp(), PlacementChangeKind.CREATED, request.reason(), now);
            advance(owner, now); return after;
        });
    }

    public Placement save(String owner, String id, String key, SavePlacement request) {
        TodayRules.uuid(id);
        if (request == null) throw invalid("安排请求必填。");
        revision(request.expectedRevision()); stamp(request.expectedSource(), null); reason(request.reason());
        content(request.expectedSource().kind(), request.content());
        return commands.execute(owner, key, "PUT /api/v1/today/placements/" + id, request, Placement.class, () -> {
            var before = requirePlacement(owner, id); placementRevision(before, request.expectedRevision());
            Instant now = TodayRules.now(); var source = checked(owner, before.source(), request.expectedSource(), now);
            contentCurrent(source, request.content(), before, now);
            var after = persist(owner, before, before.source(), request.content(), source.stamp(), PlacementChangeKind.UPDATED, request.reason(), now);
            if (after.revision() != before.revision()) advance(owner, now); return after;
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlacementHistoryPage history(String owner, String id, Long before, int limit) {
        TodayRules.uuid(owner); TodayRules.uuid(id); page(before, limit);
        var value = requirePlacement(owner, id); today.sourceForPlan(owner, value.source(), "UTC", TodayRules.now());
        var rows = mapper.placementHistory(owner, id, before, limit + 1);
        var items = rows.stream().limit(limit).map(row -> {
            var item = json.readValue(row.snapshotJson(), PlacementHistory.class);
            if (!item.snapshot().id().equals(id) || item.snapshot().revision() != row.revision() || !item.snapshot().source().equals(value.source())) throw TodayRules.integrity();
            return item;
        }).toList();
        return bounded(json, new PlacementHistoryPage(items, rows.size() > limit ? items.getLast().snapshot().revision() : null), PAGE_BYTES);
    }

    public OrderedPlacements order(String owner, String key, OrderPlacements request) {
        TodayPlanRules.order(request);
        return commands.execute(owner, key, "POST /api/v1/today/order", request, OrderedPlacements.class, () -> {
            Instant now = TodayRules.now(); List<Placement> before = new ArrayList<>();
            for (var change : request.items()) {
                var value = requirePlacement(owner, change.placementId()); placementRevision(value, change.expectedRevision()); before.add(value);
            }
            var sources = today.sourcesForPlan(owner, before.stream().map(Placement::source).toList(), "UTC", now);
            for (int i = 0; i < sources.size(); i++) check(sources.get(i), request.items().get(i).expectedSource());
            List<Placement> result = new ArrayList<>(); boolean changed = false;
            for (int i = 0; i < before.size(); i++) {
                var value = before.get(i); var old = content(value);
                var proposed = new PlacementContent(old.plannedDate(), old.planZoneId(), old.estimatedMinutes(), old.priority(),
                        request.items().get(i).orderValue(), old.dateLocked(), old.visibility(), old.suppressedOn());
                var after = persist(owner, value, value.source(), proposed, sources.get(i).stamp(), PlacementChangeKind.ORDERED, null, now);
                changed |= after.revision() != value.revision(); result.add(after);
            }
            if (changed) advance(owner, now);
            return bounded(json, new OrderedPlacements(List.copyOf(result)), PAGE_BYTES);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Placement> rescheduleActionsWithinCommand(String owner, List<ActionDateMove> moves,
                                                           String reason, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("动作安排联动改期必须处于命令事务中");
        }
        TodayRules.uuid(owner); TodayRules.time(now);
        if (reason == null) throw invalid("联动改期原因必填。");
        reason(reason);
        if (moves == null || moves.size() > 50) throw invalid("一次联动改期最多包含 50 个动作安排。");
        if (moves.isEmpty()) return List.of();
        var ids = new HashSet<String>();
        for (var move : moves) {
            if (move == null) throw invalid("动作改期项不能为空。");
            TodayRules.uuid(move.placementId()); revision(move.expectedRevision());
            stamp(move.expectedSource(), SourceKind.REVIEW_ACTION); date(move.plannedDate());
            if (!ids.add(move.placementId())) throw invalid("动作改期不能重复选择同一安排。");
        }
        var before = moves.stream().map(move -> requirePlacement(owner, move.placementId())).toList();
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i).source().kind() != SourceKind.REVIEW_ACTION) throw invalid("联动动作改期须指向动作安排。");
            placementRevision(before.get(i), moves.get(i).expectedRevision());
        }
        var sources = today.sourcesForPlan(owner, before.stream().map(Placement::source).toList(), "UTC", now);
        // 全部来源、真实开始、锁定和时钟先核对，避免在本批检查完成前写入任何安排。
        for (int i = 0; i < before.size(); i++) {
            var value = before.get(i); var source = sources.get(i); var move = moves.get(i);
            if (!value.source().equals(source.source())) throw TodayRules.integrity();
            if (!move.expectedSource().equals(source.stamp())) {
                throw conflict("RESCHEDULE_PLAN_CHANGED", "动作来源已变化，请重新预览改期计划。", null);
            }
            var facts = mapper.startedFacts(owner, SourceKind.REVIEW_ACTION, value.source().id());
            if (facts == null) throw TodayRules.integrity();
            if (source.sourceStatus().action().status() != ReviewDtos.ActionStatus.OPEN
                    || source.pendingCount() == null || source.pendingCount() <= 0 || facts.actionStarted()
                    || value.plannedDate() == null || value.dateLocked() || value.visibility() != Visibility.VISIBLE) {
                throw conflict("PREPARATION_LINK_NOT_MOVABLE", "动作已开始、无待处理事项、暂停、锁定或没有原日期，不能联动改期。", null);
            }
            date(value.plannedDate()); PlanningRules.zone(value.planZoneId());
            if (!move.plannedDate().equals(value.plannedDate())) {
                next(value.revision());
                if (now.isBefore(value.updatedAt())) {
                    throw conflict("RESCHEDULE_CLOCK_CHANGED", "服务器时间早于最近修改，请重新核对改期计划。", null);
                }
            }
        }
        List<Placement> result = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            var value = before.get(i); var move = moves.get(i);
            if (move.plannedDate().equals(value.plannedDate())) continue;
            var content = new PlacementContent(move.plannedDate(), value.planZoneId(), value.estimatedMinutes(), value.priority(),
                    value.orderValue(), value.dateLocked(), value.visibility(), value.suppressedOn());
            result.add(persist(owner, value, value.source(), content, sources.get(i).stamp(),
                    PlacementChangeKind.INTERVIEW_RESCHEDULED, reason, now));
        }
        if (!result.isEmpty()) advance(owner, now);
        return List.copyOf(result);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BudgetLookup budget(String owner, LocalDate date, String zone) {
        TodayRules.uuid(owner); date(date); PlanningRules.zone(zone);
        return new BudgetLookup(TodayPlanRows.budget(mapper.budget(owner, date, zone)));
    }

    public DayBudget createBudget(String owner, String key, CreateBudget request) {
        if (request == null) throw invalid("每日预算必填。");
        date(request.localDate()); PlanningRules.zone(request.zoneId()); range(request.availableMinutes(), 0, 1440, "可用分钟");
        return commands.execute(owner, key, "POST /api/v1/today/budgets", request, DayBudget.class, () -> {
            var before = TodayPlanRows.budget(mapper.budget(owner, request.localDate(), request.zoneId()));
            if (before != null) throw conflict("DAY_BUDGET_EXISTS", "该日期和时区已有学习预算。", before.revision());
            Instant now = TodayRules.now(); var after = new DayBudget(request.localDate(), request.zoneId(), 0, request.availableMinutes(), now, now);
            mapper.insertBudget(owner, after); budgetHistory(owner, after, BudgetChangeKind.CREATED); return after;
        });
    }

    public DayBudget saveBudget(String owner, LocalDate date, String key, SaveBudget request) {
        date(date); if (request == null) throw invalid("每日预算必填。");
        PlanningRules.zone(request.zoneId()); revision(request.expectedRevision()); range(request.availableMinutes(), 0, 1440, "可用分钟");
        return commands.execute(owner, key, "PUT /api/v1/today/budgets/" + date, request, DayBudget.class, () -> {
            var before = requireBudget(owner, date, request.zoneId());
            if (before.revision() != request.expectedRevision()) throw conflict("DAY_BUDGET_REVISION_CONFLICT", "每日学习预算已经修改，请重新读取。", before.revision());
            if (before.availableMinutes() == request.availableMinutes()) return before;
            Instant now = TodayRules.now(); clock(now, before.updatedAt());
            var after = new DayBudget(date, request.zoneId(), next(before.revision()), request.availableMinutes(), before.createdAt(), now);
            if (mapper.updateBudget(owner, after, before.revision()) != 1) throw conflict("DAY_BUDGET_REVISION_CONFLICT", "预算保存冲突，请重新读取。", null);
            budgetHistory(owner, after, BudgetChangeKind.UPDATED); return after;
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BudgetHistoryPage budgetHistory(String owner, LocalDate date, String zone, Long before, int limit) {
        TodayRules.uuid(owner); date(date); PlanningRules.zone(zone); page(before, limit); requireBudget(owner, date, zone);
        var rows = mapper.budgetHistory(owner, date, zone, before, limit + 1);
        var items = rows.stream().limit(limit).map(row -> {
            var item = json.readValue(row.snapshotJson(), BudgetHistory.class);
            if (!item.snapshot().localDate().equals(date) || !item.snapshot().zoneId().equals(zone) || item.snapshot().revision() != row.revision()) throw TodayRules.integrity();
            return item;
        }).toList();
        return bounded(json, new BudgetHistoryPage(items, rows.size() > limit ? items.getLast().snapshot().revision() : null), PAGE_BYTES);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SuggestionPreview preview(String owner, SuggestionRequest request) {
        TodayRules.uuid(owner); candidateRequest(request);
        access.checkCommand(owner, "POST /api/v1/today/suggestions/preview");
        try { return calculate(owner, request, TodayRules.now()).preview(); }
        catch (ApiException exception) {
            if (exception.status() == 409 && !exception.code().equals("PLAN_DAY_CHANGED") && !exception.code().equals("DEPENDENCY_NOT_READY")) throw previewChanged();
            throw exception;
        }
    }

    public AppliedSuggestion apply(String owner, String key, ApplySuggestion request) {
        TodayPlanRules.apply(request);
        return commands.execute(owner, key, "POST /api/v1/today/suggestions/apply", request, AppliedSuggestion.class, () -> {
            Instant now = TodayRules.now(); Calculation calculated;
            try { calculated = calculate(owner, request.request(), now); }
            catch (ApiException exception) {
                if (exception.status() == 409 && !exception.code().equals("PLAN_DAY_CHANGED") && !exception.code().equals("DEPENDENCY_NOT_READY")) throw previewChanged();
                throw exception;
            }
            var preview = calculated.preview();
            if (!preview.previewHash().equals(request.expectedPreviewHash()) || preview.budget() == null) throw previewChanged();
            Map<SourceRef, Integer> positions = new HashMap<>();
            for (int i = 0; i < preview.items().size(); i++) positions.put(preview.items().get(i).source(), i);
            int previous = -1, total = 0;
            for (SourceRef source : request.selectedSources()) {
                Integer index = positions.get(source);
                if (index == null || index <= previous) throw invalid("采用项须为预览中的不同来源并保持预览顺序。");
                var item = preview.items().get(index);
                if (!item.recommended() || item.estimatedMinutes() == null || item.proposedContent() == null) throw invalid("只能采用本次明确推荐的已估时项。");
                total += item.estimatedMinutes(); previous = index;
            }
            if (total > preview.budget().availableMinutes()) throw previewChanged();
            List<Placement> result = new ArrayList<>(); boolean changed = false;
            // 全部候选和所选分钟已重核；以下偏好、历史、代次与同一回执共享 owner 事务。
            for (SourceRef source : request.selectedSources()) {
                var item = preview.items().get(positions.get(source)); var evaluated = calculated.bySource().get(source);
                var before = evaluated.placement(); contentCurrent(evaluated.source(), item.proposedContent(), before, now);
                var after = persist(owner, before, source, item.proposedContent(), item.sourceStamp(), PlacementChangeKind.SUGGESTION_APPLIED, null, now);
                changed |= before == null || before.revision() != after.revision(); result.add(after);
            }
            if (changed) advance(owner, now);
            return bounded(json, new AppliedSuggestion(List.copyOf(result), preview.context()), PAGE_BYTES);
        });
    }

    private Calculation calculate(String owner, SuggestionRequest request, Instant now) {
        var day = TodayRules.context(request.zoneId(), now, now);
        if (!day.localDate().equals(request.localDate())) throw dayChanged();
        var budget = TodayPlanRows.budget(mapper.budget(owner, request.localDate(), request.zoneId()));
        if (!Objects.equals(request.expectedBudgetRevision(), budget == null ? null : budget.revision())) throw previewChanged();
        List<SourceRef> ids = request.items().stream().map(SuggestionCandidate::source).toList();
        var current = today.sourcesForPlan(owner, ids, request.zoneId(), now);
        var saved = mapper.placementsBySources(owner, ids).stream().map(TodayPlanRows::placement).collect(Collectors.toMap(Placement::source, Function.identity()));
        List<Evaluated> evaluated = new ArrayList<>();
        for (int i = 0; i < request.items().size(); i++) {
            var candidate = request.items().get(i); var source = current.get(i); var placement = saved.get(candidate.source());
            if (!candidate.source().equals(source.source())) throw TodayRules.integrity();
            if (!candidate.expectedSource().equals(source.stamp())) throw sourceChanged();
            if (!Objects.equals(candidate.expectedPlacementRevision(), placement == null ? null : placement.revision())) throw previewChanged();
            var facts = mapper.startedFacts(owner, candidate.source().kind(), candidate.source().id());
            if (facts == null) throw TodayRules.integrity();
            evaluated.add(evaluate(candidate, source, placement, facts, day));
        }
        evaluated.sort(this::compare);
        List<SuggestionItem> items = new ArrayList<>(); List<SourceRef> remaining = new ArrayList<>();
        int selectedMinutes = 0, unknown = 0;
        for (int i = 0; i < evaluated.size(); i++) {
            var value = evaluated.get(i); var reasons = EnumSet.copyOf(value.reasons());
            boolean recommended = value.eligible() && value.minutes() != null && budget != null
                    && value.minutes() <= budget.availableMinutes() - selectedMinutes;
            if (value.eligible() && value.minutes() == null) { reasons.add(SuggestionReason.ESTIMATE_REQUIRED); unknown++; }
            if (value.eligible() && budget == null) reasons.add(SuggestionReason.DAY_BUDGET_REQUIRED);
            if (value.eligible() && value.minutes() != null && budget != null && !recommended) reasons.add(SuggestionReason.OVER_MINUTES);
            if (recommended) selectedMinutes += value.minutes(); else remaining.add(value.source().source());
            var proposed = value.eligible() ? proposal(value, request, (i + 1) * 1000) : null;
            items.add(new SuggestionItem(value.source().source(), value.source().stamp(), value.placement() == null ? null : value.placement().revision(),
                    value.minutes(), recommended, List.copyOf(reasons), proposed));
        }
        var remainder = new SuggestionRemaining(List.copyOf(remaining), remaining.size());
        var hashFacts = evaluated.stream().map(value -> new CandidateFacts(value.source().source(), value.source().stamp(), value.placement(),
                value.source().sourceStatus(), value.source().time(), value.source().pendingCount(), value.started(), value.group(),
                value.eligible(), value.minutes(), value.sortDate(), value.sortInstant(), value.sortRank(), value.sortId(), List.copyOf(value.reasons()))).toList();
        String digest = hash(new PreviewBasis(request, new HashDay(day.zoneId(), day.localDate(), day.startAt(), day.endAt()), budget,
                hashFacts, List.copyOf(items), selectedMinutes, unknown, remainder));
        var preview = bounded(json, new SuggestionPreview(day, budget, List.copyOf(items), selectedMinutes, unknown,
                remainder, digest, List.of(SuggestionLimitation.values())), PAGE_BYTES);
        return new Calculation(preview, evaluated.stream().collect(Collectors.toMap(value -> value.source().source(), Function.identity())));
    }

    private Evaluated evaluate(SuggestionCandidate candidate, TodayItem source, Placement placement, StartedFacts facts, DayContext day) {
        EnumSet<SuggestionReason> reasons = EnumSet.noneOf(SuggestionReason.class);
        boolean eligible = true, continuing = false, due = false, placementDue = false;
        boolean started = switch (source.source().kind()) {
            case TRAINING_SESSION -> facts.trainingStartedAt() != null;
            case PREPARATION_TASK -> source.sourceStatus().task().startedAt() != null;
            case REVIEW_ACTION -> facts.actionStarted();
            default -> false;
        };
        Instant sortInstant = null; LocalDate sortDate = null; int sortRank = 0; String sortId = source.source().id();
        switch (source.source().kind()) {
            case KNOWLEDGE_DRAFT -> {
                var state = source.sourceStatus().knowledge(); sortInstant = state.draftSavedAt();
                if (state.status() == KnowledgeDtos.Status.ARCHIVED) { reasons.add(SuggestionReason.SOURCE_ARCHIVED); eligible = false; }
                if (!state.unpublishedDraft()) { reasons.add(SuggestionReason.NO_PENDING_WORK); eligible = false; }
            }
            case TRAINING_SESSION -> {
                var state = source.sourceStatus().training(); sortInstant = state.lastActivityAt();
                continuing = state.status() == TrainingDtos.Status.RUNNING || state.status() == TrainingDtos.Status.PAUSED;
                if (state.status() == TrainingDtos.Status.ENDED) { reasons.add(SuggestionReason.SOURCE_TERMINAL); eligible = false; }
            }
            case PREPARATION_TASK -> {
                var state = source.sourceStatus().task(); continuing = state.status() == PlanningDtos.Status.IN_PROGRESS;
                sortInstant = continuing ? facts.sourceUpdatedAt() : null; sortDate = continuing ? null : source.time().localDate();
                if (state.status() == PlanningDtos.Status.DONE || state.status() == PlanningDtos.Status.CANCELLED) { reasons.add(SuggestionReason.SOURCE_TERMINAL); eligible = false; }
                else if (state.status() == PlanningDtos.Status.PAUSED) { reasons.add(SuggestionReason.SOURCE_PAUSED); eligible = false; }
                else if (!continuing && source.time().localDate() == null) { reasons.add(SuggestionReason.SOURCE_DATE_REQUIRED); eligible = false; }
                else if (!continuing && source.time().localDate().isAfter(day.asOf().atZone(ZoneId.of(source.time().zoneId())).toLocalDate())) {
                    reasons.add(SuggestionReason.SOURCE_DATE_IN_FUTURE); eligible = false;
                } else due = !continuing;
            }
            case MEMORY_CARD -> {
                var state = source.sourceStatus().memory();
                if (state.cardStatus() == MemoryDtos.Status.ARCHIVED) { reasons.add(SuggestionReason.SOURCE_ARCHIVED); eligible = false; }
                if (state.openAttempt() != null) {
                    continuing = true; sortInstant = state.openAttempt().startedAt(); sortId = state.openAttempt().id();
                } else {
                    sortInstant = source.time().instant(); sortRank = state.phase() == MemoryReviewDtos.FsrsPhase.REVIEW ? 1 : 0;
                    if (state.scheduleStatus() == MemoryReviewDtos.ScheduleStatus.PAUSED) { reasons.add(SuggestionReason.SOURCE_PAUSED); eligible = false; }
                    if (!state.eligibility().basisReady()) { reasons.add(SuggestionReason.MEMORY_BLOCKED); eligible = false; }
                    if (source.stamp().memory().settingsRevision() == null) { reasons.add(SuggestionReason.MEMORY_SETTINGS_REQUIRED); eligible = false; }
                    if (sortInstant == null || sortInstant.isAfter(day.asOf()) || state.scheduleStatus() == null) { reasons.add(SuggestionReason.MEMORY_NOT_DUE); eligible = false; }
                    else due = true;
                    if (state.recommendation() == null || !state.recommendation().recommended()) { reasons.add(SuggestionReason.MEMORY_NOT_RECOMMENDED); eligible = false; }
                    if (state.recommendation() != null && state.recommendation().requiresBudgetConfirmation()) { reasons.add(SuggestionReason.MEMORY_BUDGET_CONFIRMATION); eligible = false; }
                }
            }
            case REVIEW_ACTION -> {
                var state = source.sourceStatus().action(); sortInstant = facts.sourceUpdatedAt();
                if (state.status() == ReviewDtos.ActionStatus.MERGED) { reasons.add(SuggestionReason.SOURCE_TERMINAL); eligible = false; }
                if (source.pendingCount() == null || source.pendingCount() == 0) { reasons.add(SuggestionReason.NO_PENDING_WORK); eligible = false; }
            }
            case INTERVIEW_EVENT -> { reasons.add(SuggestionReason.INTERVIEW_REFERENCE_ONLY); eligible = false; sortInstant = source.time().instant(); }
        }
        if (placement != null) {
            if (placement.visibility() == Visibility.PAUSED) { reasons.add(SuggestionReason.PLACEMENT_PAUSED); eligible = false; }
            if (placement.suppressedOn() != null && placement.suppressedOn().localDate().equals(day.localDate()) && placement.suppressedOn().zoneId().equals(day.zoneId())) {
                reasons.add(SuggestionReason.SUPPRESSED_TODAY); eligible = false;
            }
            if (placement.dateLocked()) reasons.add(SuggestionReason.DATE_LOCKED);
            if (dated(source.source().kind()) && placement.plannedDate() != null) {
                LocalDate sourceToday = day.asOf().atZone(ZoneId.of(placement.planZoneId())).toLocalDate();
                sortDate = continuing ? null : placement.plannedDate();
                if (!placement.plannedDate().isAfter(sourceToday)) { placementDue = true; reasons.add(SuggestionReason.PLACEMENT_DUE); }
                if ((placement.dateLocked() || started) && !continuing && !placement.plannedDate().equals(sourceToday)) eligible = false;
            }
        }
        if (started) reasons.add(SuggestionReason.ALREADY_STARTED);
        if (continuing) reasons.add(SuggestionReason.CONTINUE_WORK);
        else if (due) reasons.add(SuggestionReason.SOURCE_DUE);
        else if (!placementDue) reasons.add(SuggestionReason.UNSCHEDULED_WORK);
        Integer minutes = source.source().kind() == SourceKind.PREPARATION_TASK ? source.estimate().minutes()
                : !estimated(source.source().kind()) ? null : candidate.estimatedMinutes() != null ? candidate.estimatedMinutes()
                : placement == null ? null : placement.estimatedMinutes();
        return new Evaluated(source, placement, started, eligible, continuing ? 0 : due || placementDue ? 1 : 2, minutes,
                sortDate, sortInstant, sortRank, sortId, reasons);
    }

    private int compare(Evaluated left, Evaluated right) {
        int value = Integer.compare(left.group(), right.group()); if (value != 0) return value;
        value = Integer.compare(right.placement() == null ? 3 : right.placement().priority(), left.placement() == null ? 3 : left.placement().priority()); if (value != 0) return value;
        value = Integer.compare(left.placement() == null ? 500000 : left.placement().orderValue(), right.placement() == null ? 500000 : right.placement().orderValue()); if (value != 0) return value;
        value = left.source().source().kind().compareTo(right.source().source().kind()); if (value != 0) return value;
        value = Comparator.<LocalDate>nullsLast(Comparator.naturalOrder()).compare(left.sortDate(), right.sortDate()); if (value != 0) return value;
        value = Integer.compare(left.sortRank(), right.sortRank()); if (value != 0) return value;
        boolean asc = left.source().source().kind() == SourceKind.MEMORY_CARD && left.group() != 0 || left.source().source().kind() == SourceKind.INTERVIEW_EVENT;
        value = Comparator.nullsLast(asc ? Comparator.<Instant>naturalOrder() : Comparator.<Instant>reverseOrder()).compare(left.sortInstant(), right.sortInstant());
        if (value != 0) return value;
        return left.source().source().kind() == SourceKind.MEMORY_CARD && left.group() == 0 ? right.sortId().compareTo(left.sortId()) : left.sortId().compareTo(right.sortId());
    }

    private PlacementContent proposal(Evaluated value, SuggestionRequest request, int order) {
        var base = value.placement() == null ? defaults() : content(value.placement());
        boolean move = dated(value.source().source().kind()) && !base.dateLocked() && !value.started();
        return new PlacementContent(move ? request.localDate() : base.plannedDate(), move ? request.zoneId() : base.planZoneId(),
                estimated(value.source().source().kind()) ? value.minutes() : null, base.priority(), order,
                base.dateLocked(), base.visibility(), base.suppressedOn());
    }

    private TodayItem checked(String owner, SourceRef source, SourceStamp expected, Instant now) {
        var item = today.sourceForPlan(owner, source, "UTC", now); check(item, expected); return item;
    }
    private void check(TodayItem item, SourceStamp expected) {
        if (!item.stamp().equals(expected)) throw sourceChanged();
        boolean allowed = switch (item.source().kind()) {
            case KNOWLEDGE_DRAFT -> item.sourceStatus().knowledge().status() == KnowledgeDtos.Status.ACTIVE && item.sourceStatus().knowledge().unpublishedDraft();
            case TRAINING_SESSION -> item.sourceStatus().training().status() != TrainingDtos.Status.ENDED;
            case PREPARATION_TASK -> item.sourceStatus().task().status() != PlanningDtos.Status.DONE && item.sourceStatus().task().status() != PlanningDtos.Status.CANCELLED;
            case MEMORY_CARD -> item.sourceStatus().memory().cardStatus() == MemoryDtos.Status.ACTIVE;
            case REVIEW_ACTION -> item.sourceStatus().action().status() == ReviewDtos.ActionStatus.OPEN && item.pendingCount() != null && item.pendingCount() > 0;
            case INTERVIEW_EVENT -> item.sourceStatus().interview().status() == ScheduleDtos.Status.PLANNED;
        };
        if (!allowed) throw sourceChanged();
    }
    private Placement requirePlacement(String owner, String id) {
        var value = placement(mapper.placement(owner, id));
        if (value == null) throw new ApiException(404, "PLACEMENT_NOT_FOUND", "安排偏好不存在或不可访问。"); return value;
    }
    private void placementRevision(Placement value, long expected) {
        if (value.revision() != expected) throw conflict("PLACEMENT_REVISION_CONFLICT", "安排偏好已经修改，请重新读取。", value.revision());
    }
    private DayBudget requireBudget(String owner, LocalDate date, String zone) {
        var value = TodayPlanRows.budget(mapper.budget(owner, date, zone));
        if (value == null) throw new ApiException(404, "DAY_BUDGET_NOT_FOUND", "该日期与时区尚未保存学习预算。"); return value;
    }
    private Placement persist(String owner, Placement before, SourceRef source, PlacementContent content,
                              SourceStamp stamp, PlacementChangeKind change, String reason, Instant now) {
        if (before != null && content(before).equals(content)) return before;
        clock(now, before == null ? null : before.updatedAt());
        var value = new Placement(before == null ? UUID.randomUUID().toString() : before.id(), source, before == null ? 0 : next(before.revision()),
                content.plannedDate(), content.planZoneId(), content.estimatedMinutes(), content.priority(), content.orderValue(),
                content.dateLocked(), content.visibility(), content.suppressedOn(), before == null ? now : before.createdAt(), now);
        var history = bounded(json, new PlacementHistory(change, value, stamp, reason, now), HISTORY_BYTES);
        if (before == null) mapper.insertPlacement(placementRow(owner, value));
        else if (mapper.updatePlacement(placementRow(owner, value), before.revision()) != 1) throw conflict("PLACEMENT_REVISION_CONFLICT", "安排保存冲突，请重新读取。", null);
        // 历史目录读取本次快照的状态窄列，不能借后续偏好覆盖过去。
        mapper.insertPlacementHistory(owner, value.id(), value.revision(), json.writeValueAsString(history), value.visibility(), now); return value;
    }
    private void advance(String owner, Instant now) {
        mapper.ensureGeneration(owner, now); long before = mapper.placementGeneration(owner); next(before);
        if (mapper.advanceGeneration(owner, before, now) != 1) throw conflict("PLAN_GENERATION_CONFLICT", "安排目录已变化，请重新读取。", null);
    }
    private void budgetHistory(String owner, DayBudget value, BudgetChangeKind change) {
        var history = bounded(json, new BudgetHistory(change, value, value.updatedAt()), HISTORY_BYTES);
        mapper.insertBudgetHistory(owner, value, json.writeValueAsString(history));
    }

    private record Evaluated(TodayItem source, Placement placement, boolean started, boolean eligible, int group,
                              Integer minutes, LocalDate sortDate, Instant sortInstant, int sortRank, String sortId,
                              EnumSet<SuggestionReason> reasons) {}
    private record Calculation(SuggestionPreview preview, Map<SourceRef, Evaluated> bySource) {}
    private record HashDay(String zoneId, LocalDate localDate, Instant startAt, Instant endAt) {}
    private record CandidateFacts(SourceRef source, SourceStamp stamp, Placement placement, SourceStatus status,
                                   SourceTime time, Integer pendingCount, boolean started, int group, boolean eligible,
                                   Integer minutes, LocalDate sortDate, Instant sortInstant, int sortRank, String sortId,
                                   List<SuggestionReason> reasons) {}
    private record PreviewBasis(SuggestionRequest request, HashDay day, DayBudget budget, List<CandidateFacts> facts,
                                List<SuggestionItem> items, int selectedKnownMinutes, int unknownEstimateCount,
                                SuggestionRemaining remaining) {}
}
