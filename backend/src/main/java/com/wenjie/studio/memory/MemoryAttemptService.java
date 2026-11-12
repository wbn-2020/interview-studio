package com.wenjie.studio.memory;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.wenjie.studio.memory.MemoryAttemptDtos.*;
import static com.wenjie.studio.memory.MemoryAttemptRules.*;
import static com.wenjie.studio.memory.MemoryReviewDtos.*;
import static com.wenjie.studio.memory.MemoryReviewRules.*;

@Service
public class MemoryAttemptService {
    private final MemoryAttemptMapper mapper;
    private final MemoryMapper cards;
    private final MemoryReviewMapper reviews;
    private final MemoryQueueService budgets;
    private final MemoryFsrsAdapter fsrs;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public MemoryAttemptService(MemoryAttemptMapper mapper, MemoryMapper cards, MemoryReviewMapper reviews,
                                MemoryQueueService budgets, MemoryFsrsAdapter fsrs, IdempotencyService commands, JsonMapper json) {
        this.mapper = mapper; this.cards = cards; this.reviews = reviews; this.budgets = budgets;
        this.fsrs = fsrs; this.commands = commands; this.json = json;
    }

    public AttemptDetail start(String owner, String cardId, String key, StartAttempt request) {
        MemoryRules.uuid(cardId); MemoryAttemptRules.start(request);
        return commands.execute(owner, key, "POST /api/v1/memory/cards/" + cardId + "/attempts", request, AttemptDetail.class, () -> {
            Instant now = now();
            var card = card(owner, cardId); expected(card.revision(), request.expectedCardRevision(), "REVISION_CONFLICT");
            Gate gate = gate(owner, cardId); expected(gate.revision(), request.expectedGateRevision(), "MEMORY_GATE_CONFLICT");
            Schedule schedule = request.countRegular() ? requireSchedule(owner, cardId) : null;
            if (schedule != null) expected(schedule.revision(), request.expectedScheduleRevision(), "MEMORY_SCHEDULE_CONFLICT");
            if (mapper.slotProblems(owner, cardId)) throw unavailable();
            if (mapper.slot(owner, cardId) != null) throw problem("MEMORY_ATTEMPT_OPEN", "此卡已有未完成尝试，请先恢复或跳过。");
            List<SourceSnapshot> sources = sources(reviews.sources(owner, cardId, card.currentVersionId()));
            ready(eligibility(card, gate, sources));
            if (request.countRegular()) { budgets.configured(owner); due(schedule, now); }
            CramBatch batch = null;
            if (request.mode() == Mode.CRAM) {
                var row = mapper.batch(owner, request.cramBatchId()); if (row == null) throw missingBatch();
                batch = json.readValue(row.snapshotJson(), CramBatch.class);
                if (!mapper.member(owner, batch.id(), cardId)) throw problem("MEMORY_CRAM_SCOPE_CONFLICT", "卡片不属于这个固定冲刺范围。");
            }
            BudgetAdmission admission = request.countRegular()
                    ? budgets.admission(owner, cardId, schedule, now, request.continueBeyondBudget()) : null;
            String zone = admission == null ? Objects.requireNonNull(batch).zoneId() : admission.budget().zoneId();
            StartSnapshot snapshot = new StartSnapshot(card.currentVersionId(), card.versionNumber(), card.revision(), gate.revision(),
                    request.mode(), request.countRegular(), request.cramBatchId(), zone, schedule, sources, admission);
            var last = mapper.lastAttempt(owner, cardId);
            clock(now, last == null ? null : last.updatedAt());
            Attempt value = new Attempt(UUID.randomUUID().toString(), cardId, last == null ? 1 : next(last.sequence()), snapshot, 0,
                    State.THINKING, ResponseMode.THOUGHT, "", null, now, now, null, null, null, null, null, null, null, null, 1);
            AttemptEvent event = new AttemptEvent(UUID.randomUUID().toString(), value.id(), 1, 0, EventKind.START, null,
                    State.THINKING, actor(owner), now, new Started(snapshot), null, null, null, null);
            String startJson = encode(json, snapshot, SNAPSHOT_BYTES), attemptJson = encode(json, value, ATTEMPT_BYTES);
            String eventJson = encode(json, event, EVENT_BYTES);
            AttemptDetail result = detail(owner, value);
            mapper.insertAttempt(owner, value, startJson, attemptJson); mapper.insertSlot(owner, cardId, value.id(), value.state());
            mapper.insertEvent(owner, value, event, null, eventJson);
            registerKnowledgeSources(owner, value);
            return result;
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AttemptDetail detail(String owner, String id) {
        Attempt value = requireAttempt(owner, id); checkSlot(owner, value);
        if (open(value.state())) current(owner, value, now());
        return detail(owner, value);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActiveAttempt active(String owner, String cardId) {
        card(owner, cardId);
        if (mapper.slotProblems(owner, cardId)) throw unavailable();
        return bounded(json, new ActiveAttempt(cardId, mapper.activeSummary(owner, cardId), now()), DIRECTORY_BYTES);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OpenAttemptPage openAttempts(String owner, String cursor, int limit) {
        return readOpenAttempts(owner, now(), null, cursor, limit);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OpenAttemptPage openAttemptsAt(String owner, Instant asOf, String cursor, int limit) {
        time(asOf);
        return readOpenAttempts(owner, asOf, asOf, cursor, limit);
    }

    private OpenAttemptPage readOpenAttempts(String owner, Instant asOf, Instant startedThrough, String cursor, int limit) {
        MemoryRules.uuid(owner); page(null, limit); time(asOf);
        var key = directoryCursor(cursor, "open-attempts", asOf);
        if (mapper.slotProblems(owner, null)) throw unavailable();
        var rows = mapper.openAttempts(owner, startedThrough, key, limit + 1);
        List<AttemptSummary> items = rows.stream().limit(limit).toList();
        String nextCursor = rows.size() > limit ? cursor(new DirectoryCursor(1, "open-attempts",
                items.getLast().startedAt(), items.getLast().id())) : null;
        return bounded(json, new OpenAttemptPage(items, nextCursor, asOf), DIRECTORY_BYTES);
    }

    public Attempt saveResponse(String owner, String id, String key, SaveResponse request) {
        MemoryRules.uuid(id); MemoryRules.required(request); revision(request.expectedRevision()); response(request.responseMode(), request.response());
        return commands.execute(owner, key, "PUT /api/v1/memory/attempts/" + id + "/response", request, Attempt.class, () -> {
            Instant now = now(); Attempt before = writable(owner, id, request.expectedRevision(), now);
            if (before.state() != State.THINKING) throw problem("MEMORY_RESPONSE_FROZEN", "揭示后的回答已固定，不能覆盖。");
            current(owner, before, now);
            if (before.responseMode() == request.responseMode() && before.response().equals(request.response())) {
                return bounded(json, before, ATTEMPT_BYTES);
            }
            Attempt after = copy(before, State.THINKING, request.responseMode(), request.response(), now, now,
                    null, null, null, null, null, null, null, null, false);
            changed(mapper.updateAttempt(owner, after, encode(json, after, ATTEMPT_BYTES), before.revision()));
            return after;
        });
    }

    public RevealedAttempt reveal(String owner, String id, String key, Reveal request) {
        MemoryRules.uuid(id); MemoryRules.required(request); revision(request.expectedRevision()); response(request.responseMode(), request.response());
        confirmed(request.thoughtConfirmed());
        return commands.execute(owner, key, "POST /api/v1/memory/attempts/" + id + "/reveal", request, RevealedAttempt.class, () -> {
            Instant now = now(); Attempt before = writable(owner, id, request.expectedRevision(), now);
            if (before.state() != State.THINKING) throw problem("MEMORY_ALREADY_REVEALED", "这个尝试已经揭示，请恢复当前参考。");
            current(owner, before, now);
            long thinking = elapsed(before.startedAt(), now);
            Attempt after = copy(before, State.REVEALED, request.responseMode(), request.response(), now, now,
                    now, null, null, thinking, null, null, null, null, true);
            AttemptEvent event = new AttemptEvent(UUID.randomUUID().toString(), id, after.lastEventSequence(), after.revision(),
                    EventKind.REVEAL, before.state(), after.state(), actor(owner), now, null,
                    new Revealed(after.responseMode(), after.response(), thinking), null, null, null);
            RevealedAttempt result = bounded(json, new RevealedAttempt(detail(owner, after), reference(owner, after)), REVEAL_BYTES);
            persist(owner, before, after, event, null);
            return result;
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MemoryDtos.Reference reference(String owner, String id) {
        Attempt value = requireAttempt(owner, id); checkSlot(owner, value);
        if (value.revealedAt() == null) throw problem("MEMORY_NOT_REVEALED", "这个尝试尚未显式揭示参考。");
        return bounded(json, reference(owner, value), DETAIL_BYTES);
    }

    public RatedAttempt rate(String owner, String id, String key, Rate request) {
        MemoryRules.uuid(id); MemoryRules.required(request); revision(request.expectedRevision()); MemoryRules.required(request.rating());
        return commands.execute(owner, key, "POST /api/v1/memory/attempts/" + id + "/rate", request, RatedAttempt.class, () -> {
            Instant now = now(); Attempt before = writable(owner, id, request.expectedRevision(), now);
            if (before.state() != State.REVEALED) throw problem("MEMORY_NOT_REVEALED", "请先独立回忆并显式揭示，再自评。");
            Schedule schedule = current(owner, before, now);
            String eventId = UUID.randomUUID().toString();
            RatingDay day;
            BudgetUse budget = null;
            ScheduledRating scheduling = null;
            String basisKind = null;
            if (before.start().countRegular()) {
                var settings = budgets.configured(owner);
                day = day(settings.zoneId(), now, settings.revision());
                var first = mapper.firstRating(owner, before.cardId(), day.windowStart(), day.windowEnd());
                BudgetKind basis = MemoryQueueService.basis(schedule);
                budget = new BudgetUse(first == null ? basis : BudgetKind.valueOf(first.kind()), first == null, first == null ? eventId : first.eventId());
                basisKind = basis.name();
                // 预算只用于 START 准入。跨日或设置变化不否定已经揭示的真实评分。
                scheduling = rateSchedule(owner, before, Objects.requireNonNull(schedule), request.rating(), eventId, now);
            } else day = day(before.start().zoneId(), now, null);
            long total = elapsed(before.startedAt(), now);
            Attempt after = copy(before, State.RATED, before.responseMode(), before.response(), before.responseSavedAt(), now,
                    before.revealedAt(), now, now, before.thinkingElapsedMs(), total, request.rating(), null, null, true);
            AttemptEvent event = new AttemptEvent(eventId, id, after.lastEventSequence(), after.revision(), EventKind.RATE,
                    before.state(), after.state(), actor(owner), now, null, null,
                    new Rated(request.rating(), after.thinkingElapsedMs(), total, day, budget, scheduling), null, null);
            RatedAttempt result = bounded(json, new RatedAttempt(after, event), DETAIL_BYTES);
            persist(owner, before, after, event, basisKind);
            return result;
        });
    }

    public Attempt skip(String owner, String id, String key, Skip request) {
        MemoryRules.uuid(id); MemoryRules.required(request); revision(request.expectedRevision()); reason(request.reason());
        return commands.execute(owner, key, "POST /api/v1/memory/attempts/" + id + "/skip", request, Attempt.class, () -> {
            Instant now = now(); Attempt before = writable(owner, id, request.expectedRevision(), now);
            Attempt after = copy(before, State.SKIPPED, before.responseMode(), before.response(), before.responseSavedAt(), now,
                    before.revealedAt(), null, now, before.thinkingElapsedMs(), null, null, request.reason(), null, true);
            AttemptEvent event = new AttemptEvent(UUID.randomUUID().toString(), id, after.lastEventSequence(), after.revision(),
                    EventKind.SKIP, before.state(), after.state(), actor(owner), now, null, null, null, new Skipped(request.reason()), null);
            persist(owner, before, after, event, null);
            return after;
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AttemptPage history(String owner, String cardId, Long before, int limit) {
        card(owner, cardId); page(before, limit);
        if (mapper.slotProblems(owner, cardId)) throw unavailable();
        var rows = mapper.attempts(owner, cardId, before, limit + 1);
        List<AttemptSummary> items = rows.stream().limit(limit).toList();
        return bounded(json, new AttemptPage(items, rows.size() > limit ? items.getLast().sequence() : null), DIRECTORY_BYTES);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventPage events(String owner, String id, Long before, int limit) {
        Attempt attempt = requireAttempt(owner, id); checkSlot(owner, attempt); page(before, limit);
        var rows = mapper.events(owner, id, before, limit + 1);
        List<AttemptEvent> items = rows.stream().limit(limit).map(row -> json.readValue(row.snapshotJson(), AttemptEvent.class)).toList();
        return bounded(json, new EventPage(items, rows.size() > limit ? items.getLast().sequence() : null), SMALL_PAGE_BYTES);
    }

    private void registerKnowledgeSources(String owner, Attempt value) {
        // 与三份开始快照共用原列表；只登记实际引用版本，不能用观察到的当前指针替换。
        List<SourceSnapshot> fixed = value.start().sources();
        if (fixed == null || fixed.isEmpty() || fixed.size() > 10) throw sourceBindingInvalid();
        var identities = new HashSet<MemoryDtos.SourceLink>();
        for (int ordinal = 0; ordinal < fixed.size(); ordinal++) {
            SourceSnapshot source = fixed.get(ordinal);
            if (source == null) throw sourceBindingInvalid();
            try {
                MemoryRules.uuid(source.itemId()); MemoryRules.uuid(source.versionId());
            } catch (ApiException exception) {
                throw sourceBindingInvalid();
            }
            if (!identities.add(new MemoryDtos.SourceLink(source.itemId(), source.versionId()))) throw sourceBindingInvalid();
            if (mapper.insertKnowledgeSource(owner, value.cardId(), value.id(), ordinal, source.itemId(), source.versionId()) != 1) {
                throw sourceBindingInvalid();
            }
        }
    }
    private static ApiException sourceBindingInvalid() {
        return problem("MEMORY_ATTEMPT_SOURCE_INVALID", "回忆尝试的固定知识来源无法登记，本次开始未提交。");
    }
    private Attempt requireAttempt(String owner, String id) {
        MemoryRules.uuid(owner); MemoryRules.uuid(id);
        var row = mapper.attempt(owner, id); if (row == null) throw missingAttempt();
        return json.readValue(row.snapshotJson(), Attempt.class);
    }
    private Attempt writable(String owner, String id, long expected, Instant now) {
        Attempt attempt = requireAttempt(owner, id); expected(attempt.revision(), expected, "MEMORY_ATTEMPT_CONFLICT");
        if (!open(attempt.state())) throw problem("MEMORY_ATTEMPT_CLOSED", "此尝试已经终结，请读取当前记录。");
        checkSlot(owner, attempt); clock(now, attempt.updatedAt());
        return attempt;
    }
    private void checkSlot(String owner, Attempt value) {
        if (mapper.slotProblems(owner, value.cardId())) throw unavailable();
        String slot = mapper.slot(owner, value.cardId());
        if (open(value.state()) != Objects.equals(slot, value.id())) throw unavailable();
    }
    private MemoryRows.CardRow card(String owner, String id) {
        MemoryRules.uuid(owner); MemoryRules.uuid(id); var value = cards.card(owner, id);
        if (value == null) throw MemoryRules.missing(); return value;
    }
    private Gate gate(String owner, String id) {
        Gate value = reviews.gate(owner, id);
        if (value == null) throw problem("MEMORY_GATE_UNAVAILABLE", "来源观察基线缺失。"); return value;
    }
    private Schedule requireSchedule(String owner, String id) {
        Schedule value = schedule(reviews.schedule(owner, id));
        if (value == null) throw new ApiException(404, "MEMORY_SCHEDULE_NOT_FOUND", "回忆卡尚无排程。"); return value;
    }
    private Schedule current(String owner, Attempt value, Instant now) {
        StartSnapshot fixed = value.start(); var card = card(owner, value.cardId());
        expected(card.revision(), fixed.cardRevision(), "REVISION_CONFLICT");
        if (!card.currentVersionId().equals(fixed.cardVersionId())) throw new ApiException(409, "REVISION_CONFLICT", "当前卡版本已变化。", card.revision());
        Gate gate = gate(owner, card.id()); expected(gate.revision(), fixed.gateRevision(), "MEMORY_GATE_CONFLICT");
        Schedule schedule = fixed.countRegular() ? requireSchedule(owner, card.id()) : null;
        if (schedule != null) {
            expected(schedule.revision(), fixed.schedule().revision(), "MEMORY_SCHEDULE_CONFLICT");
            if (!schedule.equals(fixed.schedule())) throw problem("MEMORY_SCHEDULER_INVALID", "排程前态与开始快照不一致，评分未提交。");
        }
        ready(eligibility(card, gate, sources(reviews.sources(owner, card.id(), card.currentVersionId()))));
        if (schedule != null) due(schedule, now);
        return schedule;
    }
    private void due(Schedule value, Instant now) {
        if (value.status() != ScheduleStatus.ACTIVE) throw problem("MEMORY_SCHEDULE_PAUSED", "排程已暂停，请先显式恢复。");
        if (value.effectiveDueAt().isAfter(now)) throw problem("MEMORY_NOT_DUE", "此卡尚未到期，可另选不计入排程的冲刺。");
        clock(now, value.updatedAt());
    }
    private AttemptDetail detail(String owner, Attempt value) {
        var row = cards.version(owner, value.cardId(), value.start().cardVersionId());
        if (row == null) throw MemoryRules.missing();
        MemoryDtos.Version version = new MemoryDtos.Version(row.id(), row.versionNumber(), json.readValue(row.promptJson(), MemoryDtos.Prompt.class), row.createdAt());
        return bounded(json, new AttemptDetail(bounded(json, value, ATTEMPT_BYTES), version), DETAIL_BYTES);
    }
    private MemoryDtos.Reference reference(String owner, Attempt value) {
        var row = cards.reference(owner, value.cardId(), value.start().cardVersionId());
        if (row == null) throw MemoryRules.missing();
        return new MemoryDtos.Reference(value.cardId(), value.start().cardVersionId(), row.versionNumber(), row.referenceText());
    }
    private ScheduledRating rateSchedule(String owner, Attempt attempt, Schedule before, Rating rating, String eventId, Instant now) {
        if (!ALGORITHM.equals(before.algorithmVersion()) || !PARAMETERS.equals(before.parameterVersion())) {
            throw problem("MEMORY_SCHEDULER_INVALID", "不支持这个排程的算法或参数版本。");
        }
        FsrsState state = fsrs.next(before.state(), rating, now);
        Schedule after = new Schedule(before.cardId(), next(before.revision()), before.cycleId(), ScheduleStatus.ACTIVE, null,
                before.algorithmVersion(), before.parameterVersion(), state, state.dueAt(), null, state.dueAt(), eventId,
                next(before.lastHistorySequence()), before.createdAt(), now);
        ScheduleChange change = new ScheduleChange(UUID.randomUUID().toString(), after.lastHistorySequence(), attempt.cardId(),
                attempt.start().cardVersionId(), attempt.start().cardRevision(), attempt.start().gateRevision(), ScheduleChangeKind.RATE,
                before, after, null, actor(owner), "", now);
        String snapshot = encode(json, change, HISTORY_BYTES);
        changed(reviews.updateSchedule(owner, after, before.revision()));
        reviews.insertScheduleHistory(owner, change.id(), attempt.cardId(), attempt.start().cardVersionId(), change.sequence(),
                after.revision(), "RATE", null, snapshot, now);
        return new ScheduledRating(change.id(), change.sequence(), before, after);
    }
    private void persist(String owner, Attempt before, Attempt after, AttemptEvent event, String basisKind) {
        String snapshot = encode(json, after, ATTEMPT_BYTES), eventJson = encode(json, event, EVENT_BYTES);
        // 占位外键同时约束所属卡与开放状态；状态迁移在同一 owner 事务内先释放再按新状态建立。
        changed(mapper.releaseSlot(owner, after.cardId(), after.id()));
        changed(mapper.updateAttempt(owner, after, snapshot, before.revision()));
        mapper.insertEvent(owner, after, event, basisKind, eventJson);
        if (open(after.state())) mapper.insertSlot(owner, after.cardId(), after.id(), after.state());
    }
}
