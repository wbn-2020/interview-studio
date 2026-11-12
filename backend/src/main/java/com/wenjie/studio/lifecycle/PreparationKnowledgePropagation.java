package com.wenjie.studio.lifecycle;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.AiSources;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.memory.MemoryInvalidation;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.prepare.PreparationMapper;
import com.wenjie.studio.prepare.PreparationRows;
import com.wenjie.studio.review.ReviewedBasisInvalidation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.wenjie.studio.lifecycle.KnowledgeRevalidationRules.*;
import static com.wenjie.studio.lifecycle.PreparationKnowledgeDtos.*;
import static com.wenjie.studio.lifecycle.PreparationKnowledgeRows.*;
import static com.wenjie.studio.lifecycle.PreparationKnowledgeRules.*;

@Component
public class PreparationKnowledgePropagation {
    private final PreparationKnowledgeMapper mapper;
    private final KnowledgeRevalidationMapper knowledge;
    private final PreparationMapper preparation;
    private final MemoryInvalidation memory;
    private final ReviewedBasisInvalidation reviewed;
    private final JsonMapper json;

    public PreparationKnowledgePropagation(PreparationKnowledgeMapper mapper, KnowledgeRevalidationMapper knowledge,
                                           PreparationMapper preparation, MemoryInvalidation memory,
                                           ReviewedBasisInvalidation reviewed, JsonMapper json) {
        this.mapper = mapper; this.knowledge = knowledge; this.preparation = preparation;
        this.memory = memory; this.reviewed = reviewed;
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ObservationRow observe(String owner, String itemId) {
        writing(); uuid(owner); uuid(itemId);
        var value = mapper.observation(owner, itemId);
        observation(value);
        return value;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(String owner, ObservationRow before, ChangeKind kind, Instant now) {
        writing(); uuid(owner); observation(before); time(now);
        if (kind == null) throw integrity();
        var after = observe(owner, before.itemId());
        if (!before.kind().equals(after.kind()) || !Objects.equals(before.projectId(), after.projectId())) throw integrity();
        var event = new EventRow(UUID.randomUUID().toString(), before.itemId(), before.kind(), before.title(), before.projectId(),
                kind.name(), before.versionId(), before.versionNumber(), before.sourceGeneration(), before.revision(),
                before.status(), before.verification(), after.versionId(), after.versionNumber(), after.sourceGeneration(),
                after.revision(), after.status(), after.verification(), now);
        Root root = root(event);
        Instant frozen = knowledge.latestFrozenAt(owner);
        if (frozen != null) {
            time(frozen);
            if (!now.isAfter(frozen)) throw new ApiException(409, "PREPARATION_KNOWLEDGE_CLOCK_CONFLICT",
                    "服务器时间未晚于既有任务或回忆证据，本次准备变更未提交，请校正服务器时间后重试。");
        }
        inserted(mapper.insertEvent(owner, event));
        firstHops(owner, event, root);
        while (true) {
            var parents = mapper.pendingImpacts(owner, event.id(), 100);
            if (parents.size() > 100) throw integrity();
            if (parents.isEmpty()) {
                if (mapper.propagationPending(owner, event.id())) throw integrity();
                return;
            }
            int depth = parents.getFirst().depth();
            for (var parent : parents) {
                source(parent, root);
                if (parent.depth() != depth || !parent.eventId().equals(event.id()) || !parent.createdAt().equals(now)) throw integrity();
                if (parent.parentImpactId() != null) parent(parent, mapper.impact(owner, parent.parentImpactId()));
                descendants(owner, event, parent);
                inserted(mapper.completePropagation(owner, event.id(), parent.id()));
            }
        }
    }

    private void firstHops(String owner, EventRow event, Root root) {
        String after = null;
        while (true) {
            var candidates = mapper.candidates(owner, root.itemId(), root.kind().name(), after, 100);
            if (candidates.size() > 100) throw integrity();
            for (var candidate : candidates) {
                uuid(candidate.taskId()); uuid(candidate.targetItemId()); uuid(candidate.targetVersionId());
                if (candidate.targetVersionNumber() < 1 || after != null && candidate.taskId().compareTo(after) <= 0) throw integrity();
                var manifest = json.readValue(candidate.manifestJson(), AiDtos.Manifest.class);
                if (manifest.purpose() != AiDtos.Purpose.KNOWLEDGE_DRAFT || !AiSources.validDraftInput(manifest)) throw integrity();
                var bindings = mapper.bindings(owner, candidate.taskId());
                if (bindings.size() > 5) throw integrity();
                int preparationIndex = 0, expressionIndex = 0;
                for (int index = 0; index < manifest.sources().size(); index++) {
                    var fixed = manifest.sources().get(index);
                    if (AiSources.knowledgeKind(fixed.kind())) continue;
                    if (preparationIndex >= bindings.size()) throw integrity();
                    var binding = bindings.get(preparationIndex);
                    var captured = manifest.preparationChecks().get(preparationIndex++);
                    if (binding.ordinal() != index + 1 || !binding.itemId().equals(fixed.itemId())
                            || !binding.versionId().equals(fixed.versionId()) || !binding.kind().equals(fixed.kind())
                            || binding.number() != fixed.number() || !binding.title().equals(fixed.title())) throw integrity();
                    var input = preparation.item(owner, fixed.itemId());
                    var version = preparation.version(owner, fixed.itemId(), fixed.versionId());
                    if (input == null || version == null || !input.kind().equals(fixed.kind())
                            || version.versionNumber() != fixed.number() || !version.title().equals(fixed.title())) throw integrity();
                    requireGeneration(captured.sourceGeneration(), observe(owner, fixed.itemId()).sourceGeneration());
                    if ("FACT".equals(fixed.kind())) {
                        var project = observe(owner, input.projectId());
                        if (!"PROJECT".equals(project.kind())) throw integrity();
                        requireGeneration(captured.projectGeneration(), project.sourceGeneration());
                    }
                    List<FixedFact> facts = List.of();
                    if ("EXPRESSION".equals(fixed.kind())) {
                        facts = fixedFacts(owner, version, manifest.expressionChecks().get(expressionIndex++));
                    }
                    Bridge bridge = bridge(root, fixed, captured, input, facts);
                    if (bridge != null) {
                        var fact = bridge.fact();
                        add(owner, event, new ImpactRow(UUID.randomUUID().toString(), 0, event.id(), "PREPARATION", fixed.kind(),
                                fixed.itemId(), fixed.title(), fixed.versionId(), fixed.number(), bridge.kind().name(),
                                fact == null ? null : fact.itemId(), fact == null ? null : fact.title(),
                                fact == null ? null : fact.versionId(), fact == null ? null : fact.versionNumber(),
                                candidate.taskId(), candidate.targetItemId(), null, candidate.targetVersionId(),
                                candidate.targetVersionNumber(), 1, null, event.createdAt(), null, null, null, null, null));
                    }
                }
                if (preparationIndex != bindings.size()) throw integrity();
                after = candidate.taskId();
            }
            if (candidates.size() < 100) return;
        }
    }

    private List<FixedFact> fixedFacts(String owner, PreparationRows.VersionRow version, PreparationDtos.ExpressionCheck captured) {
        var content = json.readTree(version.contentJson());
        var ids = content == null ? null : content.get("factVersionIds");
        if (ids == null || !ids.isArray() || ids.size() < 1 || ids.size() > 50 || captured == null
                || captured.factChecks() == null || captured.factChecks().size() != ids.size()) throw integrity();
        var rows = preparation.expressionBasis(owner, version.itemId(), version.id());
        if (rows.size() != ids.size()) throw integrity();
        var byVersion = new HashMap<String, PreparationRows.ExpressionBasisRow>();
        for (var row : rows) {
            if (!"FACT".equals(row.sourceKind()) || !"FACT".equals(row.itemKind()) || !"FACT_BASIS".equals(row.useType())
                    || !Objects.equals(row.sourceItemId(), row.versionItemId()) || row.title() == null
                    || row.versionNumber() == null || row.versionNumber() < 1
                    || byVersion.put(row.sourceVersionId(), row) != null) throw integrity();
            uuid(row.sourceItemId()); uuid(row.sourceVersionId()); uuid(row.projectId());
        }
        var result = new ArrayList<FixedFact>();
        var seen = new HashSet<String>();
        for (int index = 0; index < ids.size(); index++) {
            var id = ids.get(index);
            if (!id.isTextual() || !seen.add(id.stringValue())) throw integrity();
            var fixed = byVersion.get(id.stringValue());
            var check = captured.factChecks().get(index);
            if (fixed == null || check == null || check.kind() != PreparationDtos.Kind.FACT
                    || check.state() != PreparationDtos.SourceState.CURRENT
                    || !fixed.sourceItemId().equals(check.itemId()) || !fixed.sourceVersionId().equals(check.versionId())) throw integrity();
            requireGeneration(check.sourceGeneration(), observe(owner, fixed.sourceItemId()).sourceGeneration());
            var project = observe(owner, fixed.projectId());
            if (!"PROJECT".equals(project.kind())) throw integrity();
            requireGeneration(check.projectGeneration(), project.sourceGeneration());
            result.add(new FixedFact(new Fact(fixed.sourceItemId(), fixed.title(), fixed.sourceVersionId(), fixed.versionNumber()),
                    fixed.projectId(), check));
        }
        return List.copyOf(result);
    }

    private Bridge bridge(Root root, AiDtos.Source input, PreparationDtos.SourceCheck captured,
                          PreparationRows.ItemRow item, List<FixedFact> facts) {
        if (root.itemId().equals(input.itemId())) {
            requireGeneration(captured.sourceGeneration(), root.before().sourceGeneration());
            return new Bridge(DependencyKind.DIRECT, null);
        }
        if (root.kind() == PreparationDtos.Kind.PROJECT && "FACT".equals(input.kind()) && root.itemId().equals(item.projectId())) {
            requireGeneration(captured.projectGeneration(), root.before().sourceGeneration());
            return new Bridge(DependencyKind.PROJECT_FACT, null);
        }
        // 依赖来自采用时固定表达的完整有序事实清单；今日重新确认或换版不能抹去历史采用。
        for (var fact : facts) {
            if (root.kind() == PreparationDtos.Kind.FACT && root.itemId().equals(fact.fact().itemId())) {
                requireGeneration(fact.check().sourceGeneration(), root.before().sourceGeneration());
                return new Bridge(DependencyKind.FACT_EXPRESSION, fact.fact());
            }
            if (root.kind() == PreparationDtos.Kind.PROJECT && root.itemId().equals(fact.projectId())) {
                requireGeneration(fact.check().projectGeneration(), root.before().sourceGeneration());
                return new Bridge(DependencyKind.PROJECT_EXPRESSION, fact.fact());
            }
        }
        return null;
    }

    private void descendants(String owner, EventRow event, ImpactRow parent) {
        String after = null;
        while (true) {
            var rows = knowledge.adoptions(owner, parent.targetItemId(), parent.targetVersionNumber(), after, 100);
            if (rows.size() > 100) throw integrity();
            for (var row : rows) {
                uuid(row.taskId()); uuid(row.sourceItemId()); uuid(row.sourceVersionId());
                uuid(row.targetItemId()); uuid(row.targetVersionId());
                if (!parent.targetItemId().equals(row.sourceItemId()) || row.sourceVersionNumber() < parent.targetVersionNumber()
                        || row.targetVersionNumber() < 1 || after != null && row.taskId().compareTo(after) <= 0) throw integrity();
                if (!mapper.edgeExists(owner, event.id(), "KNOWLEDGE", row.taskId(), row.sourceItemId(), row.sourceVersionId())) {
                    if (parent.depth() == Integer.MAX_VALUE) throw new ApiException(409, "PREPARATION_KNOWLEDGE_DEPTH_LIMIT",
                            "知识采用链跳数已达到保存上限，本次准备变更未提交。");
                    add(owner, event, new ImpactRow(UUID.randomUUID().toString(), 0, event.id(), "KNOWLEDGE", "KNOWLEDGE",
                            row.sourceItemId(), null, row.sourceVersionId(), row.sourceVersionNumber(), "KNOWLEDGE",
                            null, null, null, null, row.taskId(), row.targetItemId(), null, row.targetVersionId(),
                            row.targetVersionNumber(), parent.depth() + 1, parent.id(), event.createdAt(), null, null, null, null, null));
                }
                after = row.taskId();
            }
            if (rows.size() < 100) return;
        }
    }

    private void add(String owner, EventRow event, ImpactRow row) {
        if (mapper.edgeExists(owner, event.id(), row.sourceDomain(), row.taskId(), row.sourceItemId(), row.sourceVersionId())) return;
        inserted(mapper.insertImpact(owner, row));
        // 影响身份先持久化，下游在同事务以真实采用版核验；任何失败使原准备命令整体回滚。
        memory.knowledgeUpstreamChanged(owner, row.targetItemId(), row.targetVersionId(), row.id(), event.createdAt());
        reviewed.knowledgeUpstreamChanged(owner, row.targetItemId(), row.targetVersionId(), event.createdAt());
    }
    private static void requireGeneration(Long captured, long current) {
        if (captured == null || captured < 0 || captured > MAX || captured > current) throw integrity();
    }
    private record FixedFact(Fact fact, String projectId, PreparationDtos.SourceCheck check) {}
    private record Bridge(DependencyKind kind, Fact fact) {}
}
