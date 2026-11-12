package com.wenjie.studio.memory;

import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.wenjie.studio.memory.MemoryAttemptDtos.*;
import static com.wenjie.studio.memory.MemoryAttemptRules.*;
import static com.wenjie.studio.memory.MemoryReviewRules.*;

@Service
public class MemoryCramService {
    private final MemoryAttemptMapper mapper;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public MemoryCramService(MemoryAttemptMapper mapper, IdempotencyService commands, JsonMapper json) {
        this.mapper = mapper; this.commands = commands; this.json = json;
    }

    public CramBatch create(String owner, String key, CreateCramBatch request) {
        cram(request);
        return commands.execute(owner, key, "POST /api/v1/memory/cram-batches", request, CramBatch.class, () -> {
            Instant now = now();
            var cards = mapper.cards(owner, request.cardIds()).stream().collect(Collectors.toMap(MemoryRows.CardRow::id, Function.identity()));
            List<CramSelection> selections = new ArrayList<>();
            for (String id : request.cardIds()) {
                var card = cards.get(id);
                if (card == null) throw MemoryRules.missing();
                if (!"ACTIVE".equals(card.status())) throw problem("ITEM_ARCHIVED", "冲刺范围包含归档卡，请重新选择。");
                selections.add(new CramSelection(id, card.currentVersionId(), card.versionNumber(), card.revision()));
            }
            CramBatch value = new CramBatch(UUID.randomUUID().toString(), request.title(), request.startDate(), request.endDate(),
                    request.zoneId(), List.copyOf(selections), now);
            String snapshot = encode(json, value, CRAM_BYTES);
            mapper.insertBatch(owner, value, selections.size(), snapshot);
            for (int i = 0; i < selections.size(); i++) mapper.insertMember(owner, value.id(), i + 1, selections.get(i));
            return value;
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CramBatch detail(String owner, String id) { return bounded(json, requireBatch(owner, id), CRAM_BYTES); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CramBatchPage list(String owner, String cursor, int limit) {
        MemoryRules.uuid(owner); page(null, limit); Instant now = now();
        var rows = mapper.batches(owner, directoryCursor(cursor, "cram-batches", now), limit + 1);
        List<CramSummary> items = rows.stream().limit(limit).toList();
        String nextCursor = rows.size() > limit
                ? cursor(new DirectoryCursor(1, "cram-batches", items.getLast().createdAt(), items.getLast().id())) : null;
        return bounded(json, new CramBatchPage(items, nextCursor), DIRECTORY_BYTES);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CramMemberPage members(String owner, String id, Integer after, int limit) {
        requireBatch(owner, id); page(null, limit);
        if (after != null && (after < 0 || after > 200)) throw invalid("冲刺成员游标须为 0 至 200。");
        Instant now = now();
        var rows = mapper.selections(owner, id, after == null ? 0 : after, limit + 1);
        var selected = rows.stream().limit(limit).toList();
        if (selected.isEmpty()) return new CramMemberPage(List.of(), null, now);
        if (mapper.slotProblems(owner, null)) throw unavailable();
        List<String> ids = selected.stream().map(MemoryAttemptRows.SelectionRow::cardId).toList();
        // 页内各类条件分别一次集合读取，保持同一快照，避免逐成员展开来源与排程的 N+1。
        var cards = mapper.cards(owner, ids).stream().collect(Collectors.toMap(MemoryRows.CardRow::id, Function.identity()));
        var summaries = mapper.summaries(owner, ids).stream().collect(Collectors.toMap(MemoryRows.SummaryRow::id, Function.identity()));
        var gates = mapper.gates(owner, ids).stream().collect(Collectors.toMap(MemoryReviewDtos.Gate::cardId, Function.identity()));
        var schedules = mapper.schedules(owner, ids).stream().collect(Collectors.toMap(MemoryReviewRows.ScheduleRow::cardId, MemoryReviewRules::schedule));
        var active = mapper.activeSummaries(owner, ids).stream().collect(Collectors.toMap(AttemptSummary::cardId, Function.identity()));
        var sources = mapper.sources(owner, ids).stream().collect(Collectors.groupingBy(MemoryAttemptRows.CardSourceRow::cardId));
        List<CramMember> items = new ArrayList<>();
        for (var row : selected) {
            CramSelection selection = new CramSelection(row.cardId(), row.cardVersionId(), row.cardVersionNumber(), row.cardRevision());
            var card = cards.get(row.cardId()); var summary = summaries.get(row.cardId());
            if (card == null || summary == null) {
                items.add(new CramMember(row.position(), selection, null, null, null, null, null)); continue;
            }
            var gate = gates.get(row.cardId()); var schedule = schedules.get(row.cardId());
            var sourceRows = sources.getOrDefault(row.cardId(), List.of()).stream().map(s -> new MemoryReviewRows.SourceRow(
                    s.itemId(), s.versionId(), s.kind(), s.versionNumber(), s.status(), s.currentVersionId(), s.bodyChanged(), s.upstreamReviewRequired())).toList();
            MemoryDtos.Summary current = new MemoryDtos.Summary(summary.id(), MemoryDtos.Kind.valueOf(summary.kind()), summary.title(),
                    MemoryDtos.Status.valueOf(summary.status()), summary.revision(), summary.versionId(), summary.versionNumber(),
                    summary.sourceCount(), summary.relatedConceptCount(), summary.updatedAt());
            CramSchedule scheduling = schedule == null ? null : new CramSchedule(schedule.revision(), schedule.status(),
                    schedule.pauseReason(), schedule.state().phase(), schedule.effectiveDueAt());
            items.add(new CramMember(row.position(), selection, current, gate == null ? null : gate.revision(),
                    eligibility(card, gate, MemoryReviewRules.sources(sourceRows)), scheduling, active.get(row.cardId())));
        }
        return bounded(json, new CramMemberPage(List.copyOf(items), rows.size() > limit ? selected.getLast().position() : null, now), SMALL_PAGE_BYTES);
    }

    private CramBatch requireBatch(String owner, String id) {
        MemoryRules.uuid(owner); MemoryRules.uuid(id);
        var row = mapper.batch(owner, id); if (row == null) throw missingBatch();
        return json.readValue(row.snapshotJson(), CramBatch.class);
    }
}
