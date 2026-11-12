package com.wenjie.studio.memory;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.memory.MemoryReviewDtos.*;
import static com.wenjie.studio.memory.MemoryReviewRules.*;

@Service
public class MemoryReviewService {
    private final MemoryMapper cards;
    private final MemoryReviewMapper mapper;
    private final IdempotencyService commands;
    private final MemoryAttemptInvalidation attempts;
    private final MemoryHistoryKnowledgeReferences knowledgeReferences;
    private final JsonMapper json;

    public MemoryReviewService(MemoryMapper cards, MemoryReviewMapper mapper, IdempotencyService commands, MemoryAttemptInvalidation attempts,
                               MemoryHistoryKnowledgeReferences knowledgeReferences, JsonMapper json) {
        this.cards = cards; this.mapper = mapper; this.commands = commands; this.attempts = attempts;
        this.knowledgeReferences = knowledgeReferences; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecallState recallState(String owner, String id) { return state(owner, card(owner, id), now()); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ImpactPage impacts(String owner, String id, Long before, int limit) {
        card(owner, id); page(before, limit);
        var rows = mapper.sourceHistory(owner, id, before, limit + 1);
        List<SourceHistoryEntry> items = rows.stream().limit(limit).map(row -> json.readValue(row.snapshotJson(), SourceHistoryEntry.class)).toList();
        return bounded(json, new ImpactPage(items, rows.size() > limit ? items.getLast().sequence() : null), PAGE_BYTES);
    }

    public RecallState sourceReview(String owner, String id, String key, SourceReview request) {
        MemoryRules.uuid(id); MemoryRules.required(request); cardRevision(request.expectedCardRevision()); revision(request.expectedGateRevision());
        confirmed(request.confirmed()); reason(request.reason());
        return commands.execute(owner, key, path(id, "/source-review"), request, RecallState.class, () -> {
            Instant now = now();
            var card = card(owner, id); expected(card.revision(), request.expectedCardRevision(), "REVISION_CONFLICT");
            Gate gate = gate(owner, id); expected(gate.revision(), request.expectedGateRevision(), "MEMORY_GATE_CONFLICT");
            List<SourceSnapshot> sources = sources(mapper.sources(owner, id, card.currentVersionId()));
            Eligibility eligibility = eligibility(card, gate, sources);
            if (!eligibility.sourceReviewAllowed()) throw unavailable(eligibility);
            String decisionId = UUID.randomUUID().toString();
            Gate after = new Gate(id, next(gate.revision()), false, decisionId, next(gate.lastHistorySequence()), now);
            SourceReviewDecision decision = new SourceReviewDecision(decisionId, id, card.currentVersionId(), card.revision(),
                    gate.revision(), after.revision(), sources, request.reason(), actor(owner), now);
            SourceHistoryEntry entry = new SourceHistoryEntry(after.lastHistorySequence(), SourceHistoryKind.SOURCE_REVIEW, null, decision);
            String snapshot = encode(json, entry, HISTORY_BYTES);
            changed(mapper.updateGate(owner, after, gate.revision()));
            changed(mapper.insertSourceHistory(owner, decisionId, id, card.currentVersionId(), entry.sequence(), after.revision(),
                    entry.kind().name(), null, snapshot, now));
            knowledgeReferences.register(owner, entry);
            attempts.invalidate(owner, card, after, schedule(mapper.schedule(owner, id)), MemoryAttemptDtos.InvalidationReason.SOURCE_REVIEWED,
                    SourceHistoryKind.SOURCE_REVIEW, decisionId, null, false, now);
            return state(owner, card, now);
        });
    }

    public Schedule enable(String owner, String id, String key, EnableSchedule request) {
        MemoryRules.uuid(id); MemoryRules.required(request); cardRevision(request.expectedCardRevision()); revision(request.expectedGateRevision());
        timing(request.timeMode(), request.at()); confirmed(request.confirmed());
        return commands.execute(owner, key, path(id, "/schedule"), request, Schedule.class, () -> {
            Instant now = now();
            var card = card(owner, id); expected(card.revision(), request.expectedCardRevision(), "REVISION_CONFLICT");
            Gate gate = gate(owner, id); expected(gate.revision(), request.expectedGateRevision(), "MEMORY_GATE_CONFLICT");
            Schedule existing = schedule(mapper.schedule(owner, id));
            if (existing != null) throw new ApiException(409, "MEMORY_SCHEDULE_CONFLICT", "卡片已有排程，请读取当前排程。", existing.revision());
            ready(owner, card, gate); configured(owner);
            Schedule after = initial(id, 0, 1, UUID.randomUUID().toString(), effective(request.timeMode(), request.at(), now), now, now);
            bounded(json, after, RESPONSE_BYTES);
            mapper.insertSchedule(owner, after);
            history(owner, card, gate, ScheduleChangeKind.ENABLE, null, after, "", now);
            return after;
        });
    }

    public Schedule defer(String owner, String id, String key, DeferSchedule request) {
        MemoryRules.uuid(id); MemoryRules.required(request); revision(request.expectedScheduleRevision()); time(request.deferUntil()); reason(request.reason());
        return commands.execute(owner, key, path(id, "/schedule/defer"), request, Schedule.class, () -> {
            Instant now = now(); var card = card(owner, id); Gate gate = gate(owner, id);
            Schedule before = requireSchedule(owner, id); expected(before.revision(), request.expectedScheduleRevision(), "MEMORY_SCHEDULE_CONFLICT");
            if (before.status() != ScheduleStatus.ACTIVE) throw new ApiException(409, "MEMORY_SCHEDULE_PAUSED", "排程已暂停，请先显式恢复。");
            if (!request.deferUntil().isAfter(now) || !request.deferUntil().isAfter(before.effectiveDueAt())) {
                throw new ApiException(409, "MEMORY_TIME_CONFLICT", "延期时间必须晚于现在及当前有效到期时间。");
            }
            Schedule after = transition(before, before.status(), before.pauseReason(), request.deferUntil(), now);
            return saveSchedule(owner, card, gate, ScheduleChangeKind.DEFER, before, after, request.reason(), now);
        });
    }

    public Schedule pause(String owner, String id, String key, PauseSchedule request) {
        MemoryRules.uuid(id); MemoryRules.required(request); revision(request.expectedScheduleRevision()); reason(request.reason());
        return commands.execute(owner, key, path(id, "/schedule/pause"), request, Schedule.class, () -> {
            Instant now = now(); var card = card(owner, id); Gate gate = gate(owner, id);
            Schedule before = requireSchedule(owner, id); expected(before.revision(), request.expectedScheduleRevision(), "MEMORY_SCHEDULE_CONFLICT");
            if (before.status() == ScheduleStatus.PAUSED) return bounded(json, before, RESPONSE_BYTES);
            Schedule after = transition(before, ScheduleStatus.PAUSED, PauseReason.MANUAL, before.deferUntil(), now);
            return saveSchedule(owner, card, gate, ScheduleChangeKind.PAUSE, before, after, request.reason(), now);
        });
    }

    public Schedule resume(String owner, String id, String key, ResumeSchedule request) {
        MemoryRules.uuid(id); MemoryRules.required(request); cardRevision(request.expectedCardRevision()); revision(request.expectedGateRevision());
        revision(request.expectedScheduleRevision()); MemoryRules.required(request.policy()); timing(request.timeMode(), request.at());
        confirmed(request.confirmed()); reason(request.reason());
        return commands.execute(owner, key, path(id, "/schedule/resume"), request, Schedule.class, () -> {
            Instant now = now(); var card = card(owner, id); expected(card.revision(), request.expectedCardRevision(), "REVISION_CONFLICT");
            Gate gate = gate(owner, id); expected(gate.revision(), request.expectedGateRevision(), "MEMORY_GATE_CONFLICT");
            Schedule before = requireSchedule(owner, id); expected(before.revision(), request.expectedScheduleRevision(), "MEMORY_SCHEDULE_CONFLICT");
            if (before.status() != ScheduleStatus.PAUSED) throw new ApiException(409, "MEMORY_SCHEDULE_CONFLICT", "仅暂停的排程可以恢复。", before.revision());
            ready(owner, card, gate); configured(owner);
            Instant at = effective(request.timeMode(), request.at(), now);
            boolean reset = request.policy() == ResumePolicy.RESET_AS_NEW;
            // KEEP 保留算法 due；RESET 仅建立新周期初态，旧历史不可被重算或覆盖。
            Schedule after = reset ? initial(id, next(before.revision()), next(before.lastHistorySequence()), UUID.randomUUID().toString(), at, before.createdAt(), now)
                    : transition(before, ScheduleStatus.ACTIVE, null, at, now);
            return saveSchedule(owner, card, gate, reset ? ScheduleChangeKind.RESET : ScheduleChangeKind.RESUME, before, after, request.reason(), now);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScheduleHistoryPage scheduleHistory(String owner, String id, Long before, int limit) {
        card(owner, id); requireSchedule(owner, id); page(before, limit);
        var rows = mapper.scheduleHistory(owner, id, before, limit + 1);
        List<ScheduleChange> items = rows.stream().limit(limit).map(row -> json.readValue(row.snapshotJson(), ScheduleChange.class)).toList();
        return bounded(json, new ScheduleHistoryPage(items, rows.size() > limit ? items.getLast().sequence() : null), PAGE_BYTES);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Settings settings(String owner) {
        MemoryRules.uuid(owner); Settings value = mapper.settings(owner);
        return bounded(json, value == null ? new Settings(false, null, null, 10, 50, null, null) : value, RESPONSE_BYTES);
    }

    public Settings createSettings(String owner, String key, CreateSettings request) {
        MemoryRules.required(request); MemoryReviewRules.settings(request.zoneId(), request.dailyNewBudget(), request.dailyReviewBudget());
        return commands.execute(owner, key, "POST /api/v1/memory/review-settings", request, Settings.class, () -> {
            Settings existing = mapper.settings(owner);
            if (existing != null) throw new ApiException(409, "MEMORY_SETTINGS_CONFLICT", "复习设置已经初始化，请读取后再修改。", existing.revision());
            Instant now = now(); Settings after = new Settings(true, 0L, request.zoneId(), request.dailyNewBudget(), request.dailyReviewBudget(), now, now);
            bounded(json, after, RESPONSE_BYTES); mapper.insertSettings(owner, after); return after;
        });
    }

    public Settings saveSettings(String owner, String key, SaveSettings request) {
        MemoryRules.required(request); revision(request.expectedRevision()); MemoryReviewRules.settings(request.zoneId(), request.dailyNewBudget(), request.dailyReviewBudget());
        return commands.execute(owner, key, "PUT /api/v1/memory/review-settings", request, Settings.class, () -> {
            Settings before = configured(owner); expected(before.revision(), request.expectedRevision(), "MEMORY_SETTINGS_CONFLICT");
            Settings after = new Settings(true, next(before.revision()), request.zoneId(), request.dailyNewBudget(), request.dailyReviewBudget(), before.createdAt(), now());
            bounded(json, after, RESPONSE_BYTES); changed(mapper.updateSettings(owner, after, before.revision())); return after;
        });
    }

    private RecallState state(String owner, MemoryRows.CardRow card, Instant now) {
        Gate gate = mapper.gate(owner, card.id());
        List<SourceSnapshot> sources = sources(mapper.sources(owner, card.id(), card.currentVersionId()));
        return bounded(json, new RecallState(card.id(), card.revision(), card.currentVersionId(), MemoryDtos.Status.valueOf(card.status()),
                gate, sources, schedule(mapper.schedule(owner, card.id())), eligibility(card, gate, sources), now), RESPONSE_BYTES);
    }
    private MemoryRows.CardRow card(String owner, String id) {
        MemoryRules.uuid(owner); MemoryRules.uuid(id); var card = cards.card(owner, id);
        if (card == null) throw MemoryRules.missing(); return card;
    }
    private Gate gate(String owner, String id) {
        Gate gate = mapper.gate(owner, id);
        if (gate == null) throw new ApiException(409, "MEMORY_GATE_UNAVAILABLE", "来源观察基线缺失，请先处理初始化状态。");
        return gate;
    }
    private Schedule requireSchedule(String owner, String id) {
        Schedule value = schedule(mapper.schedule(owner, id));
        if (value == null) throw new ApiException(404, "MEMORY_SCHEDULE_NOT_FOUND", "回忆卡尚无排程。"); return value;
    }
    private Settings configured(String owner) {
        Settings value = mapper.settings(owner);
        if (value == null) throw new ApiException(409, "MEMORY_SETTINGS_REQUIRED", "请先明确保存时区和每日建议数量。"); return value;
    }
    private void ready(String owner, MemoryRows.CardRow card, Gate gate) {
        Eligibility eligibility = eligibility(card, gate, sources(mapper.sources(owner, card.id(), card.currentVersionId())));
        if (!eligibility.basisReady()) throw unavailable(eligibility);
    }
    private ApiException unavailable(Eligibility eligibility) {
        if (eligibility.blockers().contains(BlockReason.CARD_ARCHIVED)) return new ApiException(409, "ITEM_ARCHIVED", "请先恢复回忆卡。");
        if (eligibility.blockers().contains(BlockReason.SOURCE_UNAVAILABLE)) return new ApiException(422, "SOURCE_UNAVAILABLE", "回忆卡存在不可用依据。");
        if (eligibility.blockers().contains(BlockReason.SOURCE_UPSTREAM_REVIEW_REQUIRED)) {
            return new ApiException(409, "KNOWLEDGE_REVALIDATION_REQUIRED", "来源知识的历史采用来源仍待核对，请先处理来源更正。");
        }
        if (eligibility.blockers().stream().anyMatch(b -> b == BlockReason.SOURCE_BODY_CHANGED || b == BlockReason.ARCHIVED_NOTE_SOURCE || b == BlockReason.NO_VALID_SOURCE)) {
            return new ApiException(409, "SOURCE_CHANGED", "请先核对并更新卡片的来源依据。");
        }
        return new ApiException(409, "MEMORY_REVIEW_REQUIRED", "请先显式完成当前来源复核。");
    }
    private Schedule initial(String id, long revision, long sequence, String cycle, Instant at, Instant createdAt, Instant now) {
        return new Schedule(id, revision, cycle, ScheduleStatus.ACTIVE, null, ALGORITHM, PARAMETERS,
                new FsrsState(FsrsPhase.LEARNING, 0, null, null, at, null), at, null, at, null, sequence, createdAt, now);
    }
    private Schedule saveSchedule(String owner, MemoryRows.CardRow card, Gate gate, ScheduleChangeKind kind,
                                  Schedule before, Schedule after, String reason, Instant now) {
        bounded(json, after, RESPONSE_BYTES); changed(mapper.updateSchedule(owner, after, before.revision()));
        ScheduleChange change = history(owner, card, gate, kind, before, after, reason, now);
        MemoryAttemptDtos.InvalidationReason invalidationReason = switch (kind) {
            case DEFER -> MemoryAttemptDtos.InvalidationReason.SCHEDULE_DEFERRED;
            case PAUSE -> MemoryAttemptDtos.InvalidationReason.SCHEDULE_PAUSED;
            case RESUME -> MemoryAttemptDtos.InvalidationReason.SCHEDULE_RESUMED;
            case RESET -> MemoryAttemptDtos.InvalidationReason.SCHEDULE_RESET;
            default -> throw new IllegalArgumentException("此入口只处理手工排程变更");
        };
        attempts.invalidate(owner, card, gate, after, invalidationReason, null, null, change.id(), true, now);
        return after;
    }
    private ScheduleChange history(String owner, MemoryRows.CardRow card, Gate gate, ScheduleChangeKind kind,
                         Schedule before, Schedule after, String reason, Instant now) {
        ScheduleChange change = new ScheduleChange(UUID.randomUUID().toString(), after.lastHistorySequence(), card.id(), card.currentVersionId(),
                card.revision(), gate.revision(), kind, before, after, null, actor(owner), reason, now);
        mapper.insertScheduleHistory(owner, change.id(), card.id(), card.currentVersionId(), change.sequence(), after.revision(), kind.name(),
                null, encode(json, change, HISTORY_BYTES), now);
        return change;
    }
    private String path(String id, String suffix) { return "POST /api/v1/memory/cards/" + id + suffix; }
}
