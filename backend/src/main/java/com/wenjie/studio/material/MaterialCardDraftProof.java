package com.wenjie.studio.material;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.ai.AiManifestDigest;
import com.wenjie.studio.ai.AiSources;
import com.wenjie.studio.ai.ModelGateway;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.memory.MemoryDtos;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.wenjie.studio.material.MaterialCardDraftProofRows.*;
import static com.wenjie.studio.material.MaterialKnowledgeImpactRules.safeRevision;
import static com.wenjie.studio.material.MaterialKnowledgeImpactRules.storedUuid;
import static com.wenjie.studio.material.MaterialKnowledgeImpactRules.validTime;
import static com.wenjie.studio.material.MaterialKnowledgeImpactRules.writing;

@Component
public class MaterialCardDraftProof {
    private static final int MAX_TASKS = 200;
    private static final int MAX_BINDINGS = 2500;
    private static final int MAX_MANIFEST_BYTES = 131072;
    private static final int MAX_CARD_BYTES = 196608;
    private final MaterialCardDraftProofMapper mapper;
    private final AiManifestDigest digest;
    private final ModelGateway gateway;
    private final JsonMapper json;

    public MaterialCardDraftProof(MaterialCardDraftProofMapper mapper, AiManifestDigest digest, ModelGateway gateway) {
        this.mapper = mapper; this.digest = digest; this.gateway = gateway;
        json = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                                .maxStringLength(MAX_CARD_BYTES).maxNumberLength(32).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void verify(String owner, MaterialKnowledgePreviewDtos.Preview graph) {
        writing();
        if (!storedUuid(owner) || graph == null || graph.nodes() == null || graph.edges() == null
                || graph.nodes().size() > 200 || graph.edges().size() > 500
                || !"REGISTERED_KNOWLEDGE_ADOPTIONS_ONLY".equals(graph.coverage())) throw integrity();
        var nodes = new HashMap<String, MaterialKnowledgePreviewDtos.Node>();
        var itemIds = new TreeSet<String>();
        for (var node : graph.nodes()) {
            if (node == null || !storedUuid(node.itemId()) || !storedUuid(node.versionId()) || node.versionNumber() < 1
                    || nodes.put(node.versionId(), node) != null) throw integrity();
            itemIds.add(node.itemId());
        }
        // 影响下限来自真实采用目标，不把遍历途中较早的输入版本当作新的采用根。
        var minimums = new TreeMap<String, Integer>();
        for (var edge : graph.edges()) {
            if (edge == null) throw integrity();
            var target = nodes.get(edge.targetVersionId());
            if (target == null || !target.itemId().equals(edge.targetItemId())) throw integrity();
            minimums.merge(target.itemId(), target.versionNumber(), Math::min);
        }
        var proof = new Proof(owner, minimums);
        for (String itemId : itemIds) proof.bindings(itemId);
    }

    private final class Proof {
        private final String owner;
        private final Map<String, Integer> minimums;
        private final Map<String, List<Source>> tasks = new HashMap<>();
        private final Map<String, KnowledgeVersion> knowledge = new HashMap<>();
        private final Map<String, Card> cards = new HashMap<>();
        private final Map<String, SavedCard> versions = new HashMap<>();
        private final Map<String, String> targets = new HashMap<>();
        private int bindingCount;

        private Proof(String owner, Map<String, Integer> minimums) {
            this.owner = owner; this.minimums = minimums;
        }

        private void bindings(String itemId) {
            // 从原始登记发现任务；无决定、归档和缺父记录均不能在联查中被过滤掉。
            var rows = mapper.bindings(owner, itemId, remainingPlusOne());
            count(rows);
            String previous = null;
            for (var row : rows) {
                if (row == null || !owner.equals(row.ownerId()) || !itemId.equals(row.itemId())
                        || !storedUuid(row.taskId()) || previous != null && row.taskId().compareTo(previous) <= 0) throw integrity();
                if (!task(row.taskId()).contains(row)) throw integrity();
                previous = row.taskId();
            }
        }

        private List<Source> task(String taskId) {
            var known = tasks.get(taskId);
            if (known != null) return known;
            if (tasks.size() == MAX_TASKS) throw limit();
            Task task = mapper.task(owner, taskId);
            if (task == null || !owner.equals(task.ownerId()) || !taskId.equals(task.id())
                    || !"MEMORY_CARD_DRAFT".equals(task.purpose()) || !safeRevision(task.revision())
                    || !validTime(task.createdAt()) || !validTime(task.updatedAt())
                    || task.updatedAt().isBefore(task.createdAt()) || !Boolean.FALSE.equals(task.otherPurposeUses())) throw integrity();
            try { AiDtos.Status.valueOf(task.status()); }
            catch (IllegalArgumentException | NullPointerException exception) { throw integrity(); }
            AiDtos.Manifest manifest = manifest(task);
            if ("SUCCEEDED".equals(task.status())) {
                try {
                    if (task.candidateJson() == null || task.candidateJson().getBytes(StandardCharsets.UTF_8).length > 524288) {
                        throw integrity();
                    }
                    // 该入口只解析已保存候选和原文定位，不调用模型，也不读取卡片参考正文。
                    gateway.readCardDraftCandidate(task.candidateJson(), manifest);
                } catch (RuntimeException exception) { throw integrity(exception); }
            } else if (task.candidateJson() != null) throw integrity();
            var rows = mapper.sources(owner, taskId, remainingPlusOne());
            count(rows);
            if (rows.size() != manifest.sources().size()) throw integrity();
            for (int index = 0; index < rows.size(); index++) {
                var row = rows.get(index);
                var source = manifest.sources().get(index);
                var expected = new Source(owner, taskId, source.itemId(), source.versionId(), source.number(),
                        source.title(), source.kind(), index + 1);
                if (!expected.equals(row)) throw integrity();
                var fixed = knowledge(source.itemId(), source.versionId());
                if (fixed.number() != source.number() || !fixed.kind().equals(source.kind())
                        || !fixed.title().equals(source.title()) || !fixed.body().equals(source.body())
                        || fixed.createdAt().isAfter(task.createdAt())) throw integrity();
            }
            decision(task, manifest, mapper.decision(owner, taskId));
            var result = List.copyOf(rows);
            tasks.put(taskId, result);
            return result;
        }

        private void decision(Task task, AiDtos.Manifest manifest, Decision decision) {
            if (decision == null) return;
            if (!owner.equals(decision.ownerId()) || !task.id().equals(decision.taskId())
                    || !"SUCCEEDED".equals(task.status()) || !validTime(decision.createdAt())
                    || decision.createdAt().isBefore(task.createdAt()) || decision.createdAt().isAfter(task.updatedAt())) throw integrity();
            if ("IGNORED".equals(decision.status())) {
                if (decision.targetCardId() != null || decision.targetVersionId() != null) throw integrity();
                return;
            }
            if (!"ADOPTED".equals(decision.status()) || !storedUuid(decision.targetCardId())
                    || !storedUuid(decision.targetVersionId())
                    || targets.putIfAbsent(decision.targetVersionId(), task.id()) != null) throw integrity();
            Card card = card(decision.targetCardId());
            SavedCard original = version(card, decision.targetVersionId());
            var fixedSources = manifest.sources().stream()
                    .map(source -> new MemoryDtos.SourceLink(source.itemId(), source.versionId())).toList();
            if (original.row().number() != 1 || original.prompt().kind() != manifest.cardDraftType()
                    || !original.prompt().sources().equals(fixedSources) || !original.row().createdAt().equals(card.createdAt())
                    || original.row().createdAt().isBefore(task.createdAt())
                    || original.row().createdAt().isAfter(decision.createdAt())) throw integrity();
            SavedCard current = version(card, card.currentVersionId());
            if (!current.row().number().equals(card.versionNumber())) throw integrity();
            boolean affected = manifest.sources().stream().anyMatch(source -> {
                Integer minimum = minimums.get(source.itemId());
                return minimum != null && source.number() >= minimum;
            });
            // 现有上游钩子只读当前卡版来源，必须证明原来源仍全部保留，才可让根更正进入同一事务。
            if (affected && !current.prompt().sources().containsAll(fixedSources)) {
                throw new ApiException(409, "MATERIAL_HAS_REFERENCES",
                        "受影响的AI制卡已移除或替换原知识来源，本批不能证明当前卡的依据已重建，正文更正未提交。");
            }
        }

        private KnowledgeVersion knowledge(String itemId, String versionId) {
            var known = knowledge.get(versionId);
            if (known != null) {
                if (!itemId.equals(known.itemId())) throw integrity();
                return known;
            }
            if (!storedUuid(itemId) || !storedUuid(versionId)) throw integrity();
            var row = mapper.knowledgeVersion(owner, versionId);
            if (row == null || !owner.equals(row.ownerId()) || !owner.equals(row.itemOwnerId())
                    || !itemId.equals(row.itemId()) || !itemId.equals(row.actualItemId()) || !versionId.equals(row.id())
                    || row.number() == null || row.number() < 1 || !AiSources.knowledgeKind(row.kind())
                    || !("ACTIVE".equals(row.status()) || "ARCHIVED".equals(row.status()))
                    || !text(row.title(), 200) || row.body() == null || !validTime(row.createdAt())) throw integrity();
            knowledge.put(versionId, row);
            return row;
        }

        private Card card(String cardId) {
            var known = cards.get(cardId);
            if (known != null) return known;
            var row = mapper.card(owner, cardId);
            if (row == null || !owner.equals(row.ownerId()) || !cardId.equals(row.id())
                    || !("ACTIVE".equals(row.status()) || "ARCHIVED".equals(row.status()))
                    || row.revision() == null || row.revision() < 0 || row.revision() > 1000
                    || row.versionNumber() == null || row.versionNumber() < 1 || row.versionNumber() > 1000
                    || row.versionNumber() - 1 > row.revision() || !storedUuid(row.currentVersionId())
                    || !validTime(row.createdAt()) || !validTime(row.updatedAt())
                    || row.updatedAt().isBefore(row.createdAt())) throw integrity();
            cards.put(cardId, row);
            return row;
        }

        private SavedCard version(Card card, String versionId) {
            var known = versions.get(versionId);
            if (known != null) {
                if (!card.id().equals(known.row().cardId())) throw integrity();
                return known;
            }
            var row = mapper.cardVersion(owner, versionId);
            if (row == null || !owner.equals(row.ownerId()) || !card.id().equals(row.cardId()) || !versionId.equals(row.id())
                    || row.number() == null || row.number() < 1 || row.number() > card.versionNumber()
                    || !validTime(row.createdAt()) || row.createdAt().isBefore(card.createdAt())
                    || row.createdAt().isAfter(card.updatedAt()) || row.sourceCount() == null
                    || row.sourceCount() < 1 || row.sourceCount() > 10 || row.relatedConceptCount() == null
                    || row.relatedConceptCount() < 0 || row.relatedConceptCount() > 20) throw integrity();
            MemoryDtos.Prompt prompt = prompt(row);
            var sources = mapper.cardSources(owner, versionId, remainingPlusOne());
            count(sources);
            if (sources.size() != row.sourceCount()) throw integrity();
            for (int index = 0; index < sources.size(); index++) {
                var source = sources.get(index);
                var link = prompt.sources().get(index);
                if (!new CardSource(owner, card.id(), versionId, link.itemId(), link.versionId(), index).equals(source)
                        || knowledge(link.itemId(), link.versionId()).createdAt().isAfter(row.createdAt())) throw integrity();
            }
            var value = new SavedCard(row, prompt);
            versions.put(versionId, value);
            return value;
        }

        private int remainingPlusOne() { return MAX_BINDINGS - bindingCount + 1; }
        private void count(List<?> rows) {
            if (rows == null) throw integrity();
            if (rows.size() > MAX_BINDINGS - bindingCount) throw limit();
            bindingCount += rows.size();
        }
    }

    private AiDtos.Manifest manifest(Task task) {
        try {
            if (task.manifestJson() == null || task.manifestJson().getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES
                    || task.manifestHash() == null || !task.manifestHash().matches("[0-9a-f]{64}")) throw integrity();
            var manifest = json.readValue(task.manifestJson(), AiDtos.Manifest.class);
            if (!AiSources.validCardDraftInput(manifest) || manifest.configuration() == null
                    || manifest.inputTokenAllowance() < 1 || manifest.reservedMicros() < 0 || manifest.materialChecks() != null
                    || !task.manifestHash().equals(digest.hash(manifest))) throw integrity();
            var checks = manifest.knowledgeMaterialChecks();
            if (checks != null) {
                if (checks.size() > 200) throw integrity();
                String previous = null;
                for (var check : checks) {
                    if (check == null || !storedUuid(check.excerptId()) || !safeRevision(check.revision())
                            || previous != null && previous.compareTo(check.excerptId()) >= 0) throw integrity();
                    previous = check.excerptId();
                }
            }
            return manifest;
        } catch (RuntimeException exception) { throw integrity(exception); }
    }

    private MemoryDtos.Prompt prompt(CardVersion row) {
        try {
            if (row.promptJson() == null || row.promptJson().getBytes(StandardCharsets.UTF_8).length > MAX_CARD_BYTES) throw integrity();
            var prompt = json.readValue(row.promptJson(), MemoryDtos.Prompt.class);
            if (prompt == null || prompt.kind() == null || !prompt.kind().name().equals(row.kind())
                    || !text(prompt.title(), 200) || !prompt.title().equals(row.title()) || !text(prompt.prompt(), 10000)
                    || prompt.sources() == null || prompt.sources().size() != row.sourceCount()
                    || prompt.relatedConceptIds() == null || prompt.relatedConceptIds().size() != row.relatedConceptCount()) throw integrity();
            var sources = new HashSet<MemoryDtos.SourceLink>();
            for (var source : prompt.sources()) {
                if (source == null || !storedUuid(source.itemId()) || !storedUuid(source.versionId()) || !sources.add(source)) throw integrity();
            }
            var concepts = new HashSet<String>();
            for (String concept : prompt.relatedConceptIds()) {
                if (!storedUuid(concept) || !concepts.add(concept)) throw integrity();
            }
            return prompt;
        } catch (RuntimeException exception) { throw integrity(exception); }
    }

    private static boolean text(String value, int maximum) {
        return value != null && value.codePointCount(0, value.length()) <= maximum
                && !value.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point))
                && value.indexOf('\0') < 0 && value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
    }
    private static ApiException limit() {
        return new ApiException(409, "MATERIAL_CARD_PROOF_LIMIT", "AI制卡历史证明超过本次任务或绑定预算，正文更正未提交。");
    }
    private static IllegalStateException integrity() {
        return new IllegalStateException("材料更正的AI制卡任务、固定来源、采用决定或卡片版本不一致");
    }
    private static IllegalStateException integrity(RuntimeException cause) {
        return new IllegalStateException("材料更正的AI制卡固定JSON、候选或摘要不一致", cause);
    }
    private record SavedCard(CardVersion row, MemoryDtos.Prompt prompt) {}
}
