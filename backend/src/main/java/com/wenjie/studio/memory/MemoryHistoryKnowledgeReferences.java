package com.wenjie.studio.memory;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import static com.wenjie.studio.knowledge.KnowledgeDtos.Kind.CONCEPT;
import static com.wenjie.studio.knowledge.KnowledgeDtos.Kind.NOTE;
import static com.wenjie.studio.knowledge.KnowledgeDtos.Status.ACTIVE;
import static com.wenjie.studio.knowledge.KnowledgeDtos.Status.ARCHIVED;
import static com.wenjie.studio.memory.MemoryReviewDtos.*;
import static com.wenjie.studio.memory.MemoryReviewRules.*;

@Component
public class MemoryHistoryKnowledgeReferences {
    private final MemoryReviewMapper mapper;

    public MemoryHistoryKnowledgeReferences(MemoryReviewMapper mapper) { this.mapper = mapper; }

    public record Binding(String itemId, String versionId, Integer ordinal, String kind, Integer versionNumber, int sourceCount) {}

    public void register(String owner, SourceHistoryEntry entry) {
        // 复用持有 owner 锁的历史写事务；登记失败须连同门禁、历史及后续失效操作一起回滚。
        writeTransaction(); MemoryRules.uuid(owner);
        if (entry == null || entry.kind() == null || entry.sequence() < 1 || entry.sequence() > MAX) throw inconsistent();
        if (entry.kind() == SourceHistoryKind.SOURCE_REVIEW) {
            if (entry.impact() != null || entry.decision() == null) throw inconsistent();
            review(owner, entry.sequence(), entry.decision());
        } else {
            if (entry.decision() != null || entry.impact() == null) throw inconsistent();
            impact(owner, entry.sequence(), entry.impact());
        }
    }

    private void review(String owner, long sequence, SourceReviewDecision decision) {
        identity(decision.id(), decision.cardId(), decision.cardVersionId());
        actor(owner, decision.actor()); time(decision.createdAt());
        if (decision.cardRevision() < 0 || decision.cardRevision() > 1000
                || decision.reviewedGateRevision() < 0 || decision.reviewedGateRevision() > MAX
                || decision.resultingGateRevision() < 1 || decision.resultingGateRevision() > MAX
                || decision.resultingGateRevision() - decision.reviewedGateRevision() != 1) throw inconsistent();
        sequence(sequence, decision.resultingGateRevision());
        List<SourceSnapshot> sources = decision.sources();
        if (sources == null || sources.isEmpty() || sources.size() > 10) throw inconsistent();
        List<Binding> bindings = bindings(owner, decision.cardId(), decision.cardVersionId());
        if (bindings.size() != sources.size()) throw inconsistent();
        var distinct = new HashSet<MemoryDtos.SourceLink>();
        boolean usable = false;
        for (int ordinal = 0; ordinal < sources.size(); ordinal++) {
            SourceSnapshot source = sources.get(ordinal);
            if (source == null || source.kind() == null || source.state() == null) throw inconsistent();
            identity(source.itemId(), source.versionId(), source.currentVersionId());
            Binding binding = bindings.get(ordinal);
            if (!source.itemId().equals(binding.itemId()) || !source.versionId().equals(binding.versionId())
                    || !source.kind().name().equals(binding.kind()) || !Objects.equals(source.versionNumber(), binding.versionNumber())
                    || !Boolean.FALSE.equals(source.bodyChanged())
                    || Boolean.TRUE.equals(source.upstreamReviewRequired())
                    || !distinct.add(new MemoryDtos.SourceLink(source.itemId(), source.versionId()))) throw inconsistent();
            boolean current = source.versionId().equals(source.currentVersionId());
            if (source.state() == SourceState.CURRENT && current || source.state() == SourceState.SUPERSEDED && !current) {
                usable = true;
            } else if (source.state() != SourceState.ARCHIVED || source.kind() != CONCEPT) throw inconsistent();
        }
        if (!usable) throw inconsistent();
        for (int ordinal = 0; ordinal < sources.size(); ordinal++) {
            SourceSnapshot source = sources.get(ordinal);
            insert(owner, decision.cardId(), decision.id(), "REVIEW_DEPENDENCY", ordinal, source.itemId(), source.versionId());
            // 同一知识引用多个版本时可重复观察同一当前版；位置保留原快照，不按 pair 去重。
            insert(owner, decision.cardId(), decision.id(), "REVIEW_OBSERVED_CURRENT", ordinal, source.itemId(), source.currentVersionId());
        }
    }

    private void impact(String owner, long sequence, Impact impact) {
        identity(impact.id(), impact.cardId(), impact.cardVersionId()); time(impact.createdAt());
        if (impact.reason() == null) throw inconsistent();
        sequence(sequence, impact.gateRevision());
        if (impact.reason() != ImpactReason.SOURCE_UPSTREAM_CHANGED && impact.upstreamImpactId() != null) throw inconsistent();
        if (impact.reason() == ImpactReason.BASELINE_REVIEW_REQUIRED) {
            if (sequence != 1 || impact.gateRevision() != 0 || !impact.reviewRequired() || impact.source() != null
                    || impact.actor() == null || impact.actor().kind() != ActorKind.SYSTEM || impact.actor().ownerId() != null
                    || !impact.id().equals(impact.cardId())) throw inconsistent();
            return;
        }
        actor(owner, impact.actor());
        if (impact.gateRevision() < 1) throw inconsistent();
        if (impact.reason() == ImpactReason.SOURCE_UPSTREAM_CHANGED) {
            MemoryRules.uuid(impact.upstreamImpactId());
            var upstream = mapper.upstreamImpact(owner, impact.upstreamImpactId());
            if (impact.source() != null || !impact.reviewRequired() || upstream == null
                    || upstream.adoptedVersionNumber() < 1 || !impact.createdAt().equals(upstream.createdAt())
                    || bindings(owner, impact.cardId(), impact.cardVersionId()).stream().noneMatch(binding ->
                        binding.itemId().equals(upstream.itemId()) && binding.versionNumber() >= upstream.adoptedVersionNumber())) throw inconsistent();
            // 上游历史只保存真实采用影响身份，不制造根知识的直接依赖或正文前后观察。
            return;
        }
        SourceChange source = impact.source();
        if (source == null) {
            if (impact.reason() != ImpactReason.CARD_BASIS_CHANGED && impact.reason() != ImpactReason.CARD_ARCHIVED
                    && impact.reason() != ImpactReason.CARD_RESTORED
                    || impact.reason() == ImpactReason.CARD_BASIS_CHANGED && !impact.reviewRequired()) throw inconsistent();
            return;
        }
        identity(source.itemId(), source.previousVersionId(), source.currentVersionId());
        if (source.kind() != NOTE && source.kind() != CONCEPT) throw inconsistent();
        boolean sameVersion = source.previousVersionId().equals(source.currentVersionId());
        boolean valid = switch (impact.reason()) {
            case SOURCE_BODY_CHANGED -> source.previousStatus() == ACTIVE && source.currentStatus() == ACTIVE
                    && !sameVersion && impact.reviewRequired();
            case SOURCE_ARCHIVED -> source.previousStatus() == ACTIVE && source.currentStatus() == ARCHIVED
                    && (source.kind() != NOTE || impact.reviewRequired());
            case SOURCE_RESTORED -> source.previousStatus() == ARCHIVED && source.currentStatus() == ACTIVE;
            default -> false;
        };
        if (!valid || source.referencedVersionIds() == null || source.referencedVersionIds().isEmpty()
                || source.referencedVersionIds().size() > 10) throw inconsistent();
        var distinct = new HashSet<String>();
        for (String versionId : source.referencedVersionIds()) {
            MemoryRules.uuid(versionId);
            if (!distinct.add(versionId)) throw inconsistent();
        }
        List<Binding> bindings = bindings(owner, impact.cardId(), impact.cardVersionId()).stream()
                .filter(binding -> binding.itemId().equals(source.itemId())).toList();
        if (!source.referencedVersionIds().equals(bindings.stream().map(Binding::versionId).toList())
                || bindings.stream().anyMatch(binding -> !source.kind().name().equals(binding.kind()))) throw inconsistent();
        for (int ordinal = 0; ordinal < source.referencedVersionIds().size(); ordinal++) {
            insert(owner, impact.cardId(), impact.id(), "IMPACT_DEPENDENCY", ordinal, source.itemId(), source.referencedVersionIds().get(ordinal));
        }
        // 变更前后指针是观察身份，可能完全不在卡片依赖集合中；归档／恢复时二者可相同。
        insert(owner, impact.cardId(), impact.id(), "IMPACT_PREVIOUS", 0, source.itemId(), source.previousVersionId());
        insert(owner, impact.cardId(), impact.id(), "IMPACT_CURRENT", 0, source.itemId(), source.currentVersionId());
    }

    private List<Binding> bindings(String owner, String cardId, String versionId) {
        List<Binding> bindings = mapper.historySourceBindings(owner, cardId, versionId);
        if (bindings == null || bindings.isEmpty() || bindings.size() > 10) throw inconsistent();
        for (int ordinal = 0; ordinal < bindings.size(); ordinal++) {
            Binding binding = bindings.get(ordinal);
            if (binding == null || binding.sourceCount() != bindings.size() || !Objects.equals(binding.ordinal(), ordinal)
                    || binding.kind() == null || binding.versionNumber() == null || binding.versionNumber() < 1) throw inconsistent();
            identity(binding.itemId(), binding.versionId());
        }
        return bindings;
    }

    private void insert(String owner, String cardId, String historyId, String role, int ordinal, String itemId, String versionId) {
        if (mapper.insertHistoryKnowledgeReference(owner, cardId, historyId, role, ordinal, itemId, versionId) != 1) throw inconsistent();
    }
    private void identity(String... ids) { for (String id : ids) MemoryRules.uuid(id); }
    private void actor(String owner, Actor actor) {
        if (actor == null || actor.kind() != ActorKind.OWNER || !owner.equals(actor.ownerId())) throw inconsistent();
    }
    private void sequence(long sequence, long gateRevision) {
        if (gateRevision < 0 || gateRevision > MAX || sequence < gateRevision || sequence - gateRevision > 1) throw inconsistent();
    }
    private IllegalStateException inconsistent() { return new IllegalStateException("来源历史知识身份与固定快照不一致"); }
}
