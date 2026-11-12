package com.wenjie.studio.memory;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.knowledge.KnowledgeDtos;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import static com.wenjie.studio.memory.MemoryReviewDtos.*;
import static com.wenjie.studio.memory.MemoryReviewRules.*;

@Component
public class MemoryInvalidation {
    private final MemoryReviewMapper mapper;
    private final MemoryMapper cards;
    private final MemoryAttemptInvalidation attempts;
    private final MemoryHistoryKnowledgeReferences knowledgeReferences;
    private final JsonMapper json;

    public MemoryInvalidation(MemoryReviewMapper mapper, MemoryMapper cards, MemoryAttemptInvalidation attempts,
                              MemoryHistoryKnowledgeReferences knowledgeReferences, JsonMapper json) {
        this.mapper = mapper; this.cards = cards; this.attempts = attempts;
        this.knowledgeReferences = knowledgeReferences; this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void initializeCard(String owner, String cardId, String versionId, Instant now) {
        writeTransaction(); requireCard(owner, cardId, versionId);
        mapper.insertGate(owner, new Gate(cardId, 0, false, null, 0, now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void knowledgePublished(String owner, String itemId, KnowledgeDtos.Kind kind,
                                   KnowledgeDtos.Version previous, KnowledgeDtos.Version current, Instant now) {
        writeTransaction();
        // 比较原始已发布正文，标题发布及草稿变化不制造正文影响。
        if (previous.body().equals(current.body())) return;
        affected(owner, itemId, card -> {
            List<String> versions = references(owner, card, itemId);
            SourceChange source = new SourceChange(itemId, kind, versions, previous.id(), current.id(), KnowledgeDtos.Status.ACTIVE, KnowledgeDtos.Status.ACTIVE);
            impact(owner, card, ImpactReason.SOURCE_BODY_CHANGED, source, true, PauseReason.SOURCE_REVIEW, now);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void knowledgeUpstreamChanged(String owner, String itemId, String adoptedVersionId, String upstreamImpactId, Instant now) {
        writeTransaction(); MemoryRules.uuid(owner); MemoryRules.uuid(itemId);
        MemoryRules.uuid(adoptedVersionId); MemoryRules.uuid(upstreamImpactId); time(now);
        var upstream = mapper.upstreamImpact(owner, upstreamImpactId);
        if (upstream == null || !itemId.equals(upstream.itemId()) || !adoptedVersionId.equals(upstream.adoptedVersionId())
                || upstream.adoptedVersionNumber() < 1 || !now.equals(upstream.createdAt())) throw unavailable();
        affected(owner, itemId, card -> {
            var referenced = mapper.sources(owner, card.id(), card.currentVersionId()).stream()
                    .filter(source -> source.itemId().equals(itemId)).toList();
            if (referenced.stream().anyMatch(source -> source.versionNumber() == null)) throw unavailable();
            // 采用前版本不受影响；后续人工版本保守待核对，来源正文相同也不能消除此上游原因。
            if (referenced.stream().anyMatch(source -> source.versionNumber() >= upstream.adoptedVersionNumber())) {
                impact(owner, card, ImpactReason.SOURCE_UPSTREAM_CHANGED, null, true, PauseReason.SOURCE_REVIEW, now, upstreamImpactId);
            }
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void knowledgeStatusChanged(String owner, String itemId, KnowledgeDtos.Kind kind, String currentVersionId,
                                       KnowledgeDtos.Status previous, KnowledgeDtos.Status current, Instant now) {
        writeTransaction();
        if (previous == current) return;
        affected(owner, itemId, card -> {
            List<SourceSnapshot> sources = sources(mapper.sources(owner, card.id(), card.currentVersionId()));
            SourceChange source = new SourceChange(itemId, kind, sources.stream().filter(s -> s.itemId().equals(itemId)).map(SourceSnapshot::versionId).toList(),
                    currentVersionId, currentVersionId, previous, current);
            if (current == KnowledgeDtos.Status.ACTIVE) {
                impact(owner, card, ImpactReason.SOURCE_RESTORED, source, false, null, now);
                return;
            }
            Gate gate = gate(owner, card.id());
            long reviewed = -1;
            if (gate.lastReviewDecisionId() != null) {
                Long revision = mapper.decisionRevision(owner, card.id(), gate.lastReviewDecisionId());
                if (revision == null) throw unavailable();
                reviewed = revision;
            }
            boolean otherBasis = sources.stream().anyMatch(s -> !s.itemId().equals(itemId) && usable(s));
            // 概念归档仅在仍有有效依据且无未确认正文影响时保留排程；历史改后改回不能绕过。
            boolean blocking = kind == KnowledgeDtos.Kind.NOTE || !otherBasis || mapper.pendingBodyImpact(owner, card.id(), reviewed);
            impact(owner, card, ImpactReason.SOURCE_ARCHIVED, source, blocking, blocking ? PauseReason.SOURCE_REVIEW : null, now);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cardVersionChanged(String owner, String cardId, String previousVersionId, String currentVersionId,
                                   long currentCardRevision, Instant now) {
        writeTransaction(); var card = requireCard(owner, cardId, currentVersionId);
        if (card.revision() != currentCardRevision) throw new IllegalStateException("卡版本钩子修订不匹配");
        var previous = mapper.basis(owner, cardId, previousVersionId); var current = mapper.basis(owner, cardId, currentVersionId);
        if (previous == null || current == null) throw MemoryRules.missing();
        MemoryDtos.Prompt left = json.readValue(previous.promptJson(), MemoryDtos.Prompt.class);
        MemoryDtos.Prompt right = json.readValue(current.promptJson(), MemoryDtos.Prompt.class);
        boolean changed = left.kind() != right.kind() || !left.prompt().equals(right.prompt())
                || !previous.referenceText().equals(current.referenceText())
                || !new HashSet<>(left.sources()).equals(new HashSet<>(right.sources()));
        Gate gate = gate(owner, cardId);
        if (changed) impact(owner, card, ImpactReason.CARD_BASIS_CHANGED, null, true, PauseReason.SOURCE_REVIEW, now);
        else attempts.invalidate(owner, card, gate, schedule(mapper.schedule(owner, cardId)),
                MemoryAttemptDtos.InvalidationReason.CARD_VERSION_CHANGED, null, null, null, false, now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cardStatusChanged(String owner, String cardId, String versionId, long cardRevision,
                                  MemoryDtos.Status previous, MemoryDtos.Status current, Instant now) {
        writeTransaction(); if (previous == current) return;
        var card = requireCard(owner, cardId, versionId);
        if (card.revision() != cardRevision || !card.status().equals(current.name())) throw new IllegalStateException("卡状态钩子修订不匹配");
        impact(owner, card, current == MemoryDtos.Status.ARCHIVED ? ImpactReason.CARD_ARCHIVED : ImpactReason.CARD_RESTORED,
                null, false, current == MemoryDtos.Status.ARCHIVED ? PauseReason.CARD_ARCHIVED : null, now);
    }

    private void affected(String owner, String itemId, Consumer<MemoryRows.CardRow> action) {
        // 分页仅限制一次载入数量，不截断扇出；所有页仍在原 owner 事务中，超时整体失败。
        String after = null;
        while (true) {
            var batch = mapper.affectedCards(owner, itemId, after, 100);
            if (batch.isEmpty()) return;
            batch.forEach(action); after = batch.getLast().id();
            if (batch.size() < 100) return;
        }
    }
    private List<String> references(String owner, MemoryRows.CardRow card, String itemId) {
        return mapper.sources(owner, card.id(), card.currentVersionId()).stream().filter(row -> row.itemId().equals(itemId)).map(MemoryReviewRows.SourceRow::versionId).toList();
    }
    private void impact(String owner, MemoryRows.CardRow card, ImpactReason reason, SourceChange source,
                        boolean requireReview, PauseReason pause, Instant now) {
        impact(owner, card, reason, source, requireReview, pause, now, null);
    }
    private void impact(String owner, MemoryRows.CardRow card, ImpactReason reason, SourceChange source,
                        boolean requireReview, PauseReason pause, Instant now, String upstreamImpactId) {
        Gate before = gate(owner, card.id());
        Gate after = new Gate(card.id(), next(before.revision()), before.reviewRequired() || requireReview,
                before.lastReviewDecisionId(), next(before.lastHistorySequence()), now);
        String impactId = UUID.randomUUID().toString();
        Impact impact = new Impact(impactId, card.id(), card.currentVersionId(), after.revision(), reason, source,
                after.reviewRequired(), actor(owner), now, upstreamImpactId);
        SourceHistoryEntry entry = new SourceHistoryEntry(after.lastHistorySequence(), SourceHistoryKind.IMPACT, impact, null);
        String snapshot = encode(json, entry, HISTORY_BYTES);
        changed(mapper.updateGate(owner, after, before.revision()));
        changed(mapper.insertSourceHistory(owner, impactId, card.id(), card.currentVersionId(), entry.sequence(), after.revision(), "IMPACT", reason.name(), snapshot, now));
        knowledgeReferences.register(owner, entry);
        ScheduleChange scheduleChange = pause == null ? null : pause(owner, card, after, impactId, pause, now);
        Schedule currentSchedule = scheduleChange == null ? schedule(mapper.schedule(owner, card.id())) : scheduleChange.after();
        MemoryAttemptDtos.InvalidationReason invalidationReason = switch (reason) {
            case SOURCE_BODY_CHANGED -> MemoryAttemptDtos.InvalidationReason.SOURCE_BODY_CHANGED;
            case SOURCE_UPSTREAM_CHANGED -> MemoryAttemptDtos.InvalidationReason.SOURCE_UPSTREAM_CHANGED;
            case SOURCE_ARCHIVED -> MemoryAttemptDtos.InvalidationReason.SOURCE_ARCHIVED;
            case SOURCE_RESTORED -> MemoryAttemptDtos.InvalidationReason.SOURCE_RESTORED;
            case CARD_BASIS_CHANGED -> MemoryAttemptDtos.InvalidationReason.CARD_VERSION_CHANGED;
            case CARD_ARCHIVED -> MemoryAttemptDtos.InvalidationReason.CARD_ARCHIVED;
            case CARD_RESTORED -> MemoryAttemptDtos.InvalidationReason.CARD_RESTORED;
            case BASELINE_REVIEW_REQUIRED -> throw new IllegalStateException("部署基线不由运行时来源钩子创建");
        };
        attempts.invalidate(owner, card, after, currentSchedule, invalidationReason, SourceHistoryKind.IMPACT,
                impactId, scheduleChange == null ? null : scheduleChange.id(), false, now);
    }
    private ScheduleChange pause(String owner, MemoryRows.CardRow card, Gate gate, String impactId, PauseReason reason, Instant now) {
        Schedule before = schedule(mapper.schedule(owner, card.id()));
        if (before == null || (before.status() == ScheduleStatus.PAUSED && before.pauseReason() == reason)) return null;
        Schedule after = transition(before, ScheduleStatus.PAUSED, reason, before.deferUntil(), now);
        ScheduleChangeKind kind = reason == PauseReason.CARD_ARCHIVED ? ScheduleChangeKind.CARD_PAUSE : ScheduleChangeKind.SOURCE_PAUSE;
        ScheduleChange change = new ScheduleChange(UUID.randomUUID().toString(), after.lastHistorySequence(), card.id(), card.currentVersionId(),
                card.revision(), gate.revision(), kind, before, after, impactId, actor(owner), "", now);
        String snapshot = encode(json, change, HISTORY_BYTES);
        changed(mapper.updateSchedule(owner, after, before.revision()));
        mapper.insertScheduleHistory(owner, change.id(), card.id(), card.currentVersionId(), change.sequence(), after.revision(), kind.name(), impactId, snapshot, now);
        return change;
    }
    private MemoryRows.CardRow requireCard(String owner, String id, String versionId) {
        MemoryRules.uuid(owner); MemoryRules.uuid(id); MemoryRules.uuid(versionId);
        var card = cards.card(owner, id);
        if (card == null || !Objects.equals(card.currentVersionId(), versionId)) throw MemoryRules.missing(); return card;
    }
    private Gate gate(String owner, String id) { Gate gate = mapper.gate(owner, id); if (gate == null) throw unavailable(); return gate; }
    private ApiException unavailable() { return new ApiException(409, "MEMORY_GATE_UNAVAILABLE", "来源观察基线缺失，变更未提交。"); }
}
