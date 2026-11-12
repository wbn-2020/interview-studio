package com.wenjie.studio.memory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static com.wenjie.studio.memory.MemoryAttemptDtos.*;
import static com.wenjie.studio.memory.MemoryAttemptRules.*;
import static com.wenjie.studio.memory.MemoryReviewDtos.*;
import static com.wenjie.studio.memory.MemoryReviewRules.*;

@Component
public class MemoryAttemptInvalidation {
    private final MemoryAttemptMapper mapper;
    private final JsonMapper json;

    public MemoryAttemptInvalidation(MemoryAttemptMapper mapper, JsonMapper json) { this.mapper = mapper; this.json = json; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidate(String owner, MemoryRows.CardRow card, Gate gate, Schedule schedule,
                           InvalidationReason reason, SourceHistoryKind sourceKind, String sourceId,
                           String scheduleChangeId, boolean regularOnly, Instant now) {
        writeTransaction();
        if (mapper.slotProblems(owner, card.id())) throw unavailable();
        String id = mapper.slot(owner, card.id());
        if (id == null) return;
        var row = mapper.attempt(owner, id);
        if (row == null) throw unavailable();
        Attempt before = json.readValue(row.snapshotJson(), Attempt.class);
        if (!open(before.state()) || !card.id().equals(before.cardId())) throw unavailable();
        if (regularOnly && !before.start().countRegular()) return;
        Invalidation cause = new Invalidation(reason, card.currentVersionId(), card.revision(), gate.revision(),
                schedule == null ? null : schedule.revision(), sourceKind, sourceId, scheduleChangeId);
        // 使用来源事务的同一个 now 和已生效的新门禁／排程；终结失败会回滚原知识变更。
        Attempt after = copy(before, State.INVALIDATED, before.responseMode(), before.response(), before.responseSavedAt(), now,
                before.revealedAt(), null, now, before.thinkingElapsedMs(), null, null, null, cause, true);
        AttemptEvent event = new AttemptEvent(UUID.randomUUID().toString(), id, after.lastEventSequence(), after.revision(),
                EventKind.INVALIDATE, before.state(), after.state(), actor(owner), now, null, null, null, null, new Invalidated(cause));
        String snapshot = encode(json, after, ATTEMPT_BYTES), eventJson = encode(json, event, EVENT_BYTES);
        changed(mapper.releaseSlot(owner, card.id(), id));
        changed(mapper.updateAttempt(owner, after, snapshot, before.revision()));
        mapper.insertEvent(owner, after, event, null, eventJson);
    }
}
