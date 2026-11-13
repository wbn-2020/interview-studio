package com.wenjie.studio.material;

import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.lifecycle.KnowledgeMaterialLineage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.material.MaterialKnowledgePreviewDtos.*;
import static com.wenjie.studio.material.MaterialKnowledgePreviewMapper.*;

@Component
public class MaterialKnowledgeAdoptions {
    private static final int MAX_NODES = 200;
    private static final int MAX_EDGES = 500;
    private static final int MAX_BINDINGS = 2500;
    private static final Set<String> TASK_STATUSES = Set.of("QUEUED", "RUNNING", "VALIDATING", "SUCCEEDED",
            "FAILED", "UNKNOWN", "CANCEL_REQUESTED", "CANCELED");
    private final MaterialKnowledgePreviewMapper mapper;
    private final MaterialReferencesMapper references;
    private final KnowledgeMaterialLineage lineage;

    public MaterialKnowledgeAdoptions(MaterialKnowledgePreviewMapper mapper, MaterialReferencesMapper references,
                                     KnowledgeMaterialLineage lineage) {
        this.mapper = mapper; this.references = references; this.lineage = lineage;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Preview collect(String owner, String excerptId, MaterialLifecycleDtos.State state) {
        MaterialGuards.uuid(owner); MaterialGuards.uuid(excerptId);
        if (state == null || !excerptId.equals(state.excerptId())) throw integrity();
        var source = references.source(owner, excerptId);
        if (source == null) throw MaterialGuards.missing();
        source(source, excerptId, state);
        var graph = new Traversal(owner, excerptId);
        graph.collect();
        var nodes = graph.nodes.values().stream().sorted(Comparator.comparing(Node::versionId)).toList();
        // 采用链只描述历史身份；统一证明原清单、摘要和原始绑定，不以今天的许可筛掉历史。
        var ancestors = lineage.excerptIds(owner, nodes.stream()
                .map(node -> new AiDtos.SourceLink(node.itemId(), node.versionId())).toList());
        if (ancestors == null || !graph.edges.isEmpty() && !ancestors.contains(excerptId)) throw integrity();
        var edges = graph.edges.values().stream().sorted(Comparator.comparingInt(Edge::depth).thenComparing(Edge::id)).toList();
        return new Preview("REGISTERED_KNOWLEDGE_ADOPTIONS_ONLY", source, state, nodes, edges);
    }

    private final class Traversal {
        private final String owner;
        private final String excerptId;
        private final Map<String, Node> nodes = new HashMap<>();
        private final Map<String, Edge> edges = new LinkedHashMap<>();
        private final Map<String, List<Binding>> bindings = new HashMap<>();
        private final Map<String, Integer> expandedMinimum = new HashMap<>();
        private final ArrayDeque<Edge> pending = new ArrayDeque<>();
        private int bindingCount;

        private Traversal(String owner, String excerptId) {
            this.owner = owner; this.excerptId = excerptId;
        }

        private void collect() {
            var roots = mapper.materialBindings(owner, excerptId, MAX_BINDINGS + 1);
            count(roots);
            String previous = null;
            for (var row : roots) {
                task(row, true);
                if (!excerptId.equals(row.excerptId()) || row.ordinal() > 5 || row.sourceItemId() != null
                        || row.sourceVersionId() != null || row.sourceVersionNumber() != null || row.sourceTitle() != null
                        || row.sourceItemOwnerId() != null || row.sourceVersionOwnerId() != null
                        || row.actualSourceItemId() != null || row.actualSourceVersionId() != null
                        || row.actualSourceVersionNumber() != null || row.actualSourceTitle() != null
                        || previous != null && row.taskId().compareTo(previous) <= 0) throw integrity();
                previous = row.taskId();
                if (adopted(row)) {
                    target(row);
                    add(Domain.MATERIAL_EXCERPT, excerptId, null, row, null);
                }
            }
            while (!pending.isEmpty()) {
                Edge parent = pending.removeFirst();
                Node adopted = nodes.get(parent.targetVersionId());
                if (adopted == null || !parent.targetItemId().equals(adopted.itemId())) throw integrity();
                Integer expanded = expandedMinimum.get(adopted.itemId());
                if (expanded != null && expanded <= adopted.versionNumber()) continue;
                // 同对象后来可由更低采用版到达；缓存原始全量行，按更低下限重新展开而非按对象永久去重。
                expandedMinimum.put(adopted.itemId(), adopted.versionNumber());
                for (var row : children(adopted.itemId())) {
                    if (row.sourceVersionNumber() < adopted.versionNumber() || !"ADOPTED".equals(row.decisionStatus())) continue;
                    Node actualSource = node(row.sourceItemId(), row.sourceVersionId());
                    if (actualSource.versionNumber() != row.sourceVersionNumber()
                            || !actualSource.title().equals(row.sourceTitle())) throw integrity();
                    target(row);
                    add(Domain.KNOWLEDGE, row.sourceItemId(), row.sourceVersionId(), row, parent);
                }
            }
        }

        private List<Binding> children(String itemId) {
            var cached = bindings.get(itemId);
            if (cached != null) return cached;
            var rows = mapper.knowledgeBindings(owner, itemId, MAX_BINDINGS - bindingCount + 1);
            count(rows);
            String previous = null;
            for (var row : rows) {
                task(row, false);
                if (row.excerptId() != null || !itemId.equals(row.sourceItemId()) || !storedUuid(row.sourceVersionId())
                        || row.sourceVersionNumber() == null || row.sourceVersionNumber() < 1
                        || !owner.equals(row.sourceItemOwnerId()) || !owner.equals(row.sourceVersionOwnerId())
                        || !itemId.equals(row.actualSourceItemId()) || !row.sourceVersionId().equals(row.actualSourceVersionId())
                        || !row.sourceVersionNumber().equals(row.actualSourceVersionNumber())
                        || row.sourceTitle() == null || row.sourceTitle().isBlank()
                        || !row.sourceTitle().equals(row.actualSourceTitle())
                        || previous != null && row.taskId().compareTo(previous) <= 0
                        || row.decisionTaskId() == null) throw integrity();
                adopted(row);
                previous = row.taskId();
            }
            cached = List.copyOf(rows);
            bindings.put(itemId, cached);
            return cached;
        }

        private void task(Binding row, boolean material) {
            if (row == null || !owner.equals(row.ownerId()) || !storedUuid(row.taskId())
                    || !owner.equals(row.taskOwnerId()) || !row.taskId().equals(row.actualTaskId())
                    || row.ordinal() == null || row.ordinal() < 1 || row.taskStatus() == null || !TASK_STATUSES.contains(row.taskStatus())
                    || !("KNOWLEDGE_DRAFT".equals(row.purpose())
                        || material && "QUESTION_DRAFT".equals(row.purpose()))) throw integrity();
            questionDecision(row);
        }

        private void questionDecision(Binding row) {
            if (row.questionDecisionTaskId() == null) {
                if (row.questionDecisionOwnerId() != null || row.questionDecisionStatus() != null
                        || row.questionTargetItemId() != null || row.questionTargetVersionId() != null) throw integrity();
                return;
            }
            if (!"QUESTION_DRAFT".equals(row.purpose()) || !owner.equals(row.questionDecisionOwnerId())
                    || !row.taskId().equals(row.questionDecisionTaskId()) || !"SUCCEEDED".equals(row.taskStatus())) throw integrity();
            if ("ADOPTED".equals(row.questionDecisionStatus())) {
                if (!storedUuid(row.questionTargetItemId()) || !storedUuid(row.questionTargetVersionId())) throw integrity();
            } else if (!"IGNORED".equals(row.questionDecisionStatus()) || row.questionTargetItemId() != null
                    || row.questionTargetVersionId() != null) throw integrity();
        }

        private boolean adopted(Binding row) {
            if (row.decisionTaskId() == null) {
                if (row.decisionOwnerId() != null || row.decisionStatus() != null || row.mode() != null
                        || row.targetItemId() != null || row.targetVersionId() != null || targetIdentityPresent(row)) throw integrity();
                return false;
            }
            if (!"KNOWLEDGE_DRAFT".equals(row.purpose()) || !owner.equals(row.decisionOwnerId())
                    || !row.taskId().equals(row.decisionTaskId()) || !"SUCCEEDED".equals(row.taskStatus())) throw integrity();
            if ("IGNORED".equals(row.decisionStatus())) {
                if (row.mode() != null || row.targetItemId() != null || row.targetVersionId() != null
                        || targetIdentityPresent(row)) throw integrity();
                return false;
            }
            if (!"ADOPTED".equals(row.decisionStatus()) || !storedUuid(row.targetItemId()) || !storedUuid(row.targetVersionId())
                    || !owner.equals(row.targetItemOwnerId()) || !owner.equals(row.targetVersionOwnerId())
                    || !row.targetItemId().equals(row.actualTargetItemId())
                    || !row.targetVersionId().equals(row.actualTargetVersionId())
                    || row.actualTargetVersionNumber() == null || row.actualTargetVersionNumber() < 1) throw integrity();
            if ("NEW".equals(row.mode())) {
                if (row.actualTargetVersionNumber() != 1) throw integrity();
            } else if (!"EXISTING".equals(row.mode()) || row.actualTargetVersionNumber() <= 1) throw integrity();
            return true;
        }

        private void target(Binding row) {
            if (node(row.targetItemId(), row.targetVersionId()).versionNumber() != row.actualTargetVersionNumber()) throw integrity();
        }

        private Node node(String itemId, String versionId) {
            Node known = nodes.get(versionId);
            if (known != null) {
                if (!known.itemId().equals(itemId)) throw integrity();
                return known;
            }
            if (nodes.size() == MAX_NODES) throw limit();
            var row = mapper.node(owner, itemId, versionId);
            if (row == null || !owner.equals(row.ownerId()) || !itemId.equals(row.itemId()) || !versionId.equals(row.versionId())
                    || row.versionNumber() == null || row.versionNumber() < 1 || row.title() == null || row.title().isBlank()
                    || !owner.equals(row.itemOwnerId()) || !storedUuid(row.currentVersionId())
                    || row.currentVersionNumber() == null || row.currentVersionNumber() < row.versionNumber()
                    || !owner.equals(row.currentOwnerId()) || !itemId.equals(row.currentItemId())
                    || !row.currentVersionId().equals(row.actualCurrentVersionId())
                    || !row.currentVersionNumber().equals(row.actualCurrentVersionNumber())) throw integrity();
            KnowledgeDtos.Kind kind;
            KnowledgeDtos.Status status;
            try {
                kind = KnowledgeDtos.Kind.valueOf(row.itemKind());
                status = KnowledgeDtos.Status.valueOf(row.itemStatus());
            } catch (IllegalArgumentException | NullPointerException exception) { throw integrity(); }
            Node value = new Node(itemId, versionId, row.versionNumber(), kind, row.title(), status,
                    versionId.equals(row.currentVersionId()));
            nodes.put(versionId, value);
            return value;
        }

        private void add(Domain domain, String sourceItemId, String sourceVersionId, Binding row, Edge parent) {
            String id = domain + "~" + row.taskId() + "~" + (sourceVersionId == null ? excerptId : sourceVersionId);
            Edge existing = edges.get(id);
            if (existing != null) {
                if (existing.domain() != domain || !existing.sourceItemId().equals(sourceItemId)
                        || !Objects.equals(existing.sourceVersionId(), sourceVersionId)
                        || !existing.taskId().equals(row.taskId()) || !existing.targetItemId().equals(row.targetItemId())
                        || !existing.targetVersionId().equals(row.targetVersionId())) throw integrity();
                return;
            }
            if (edges.size() == MAX_EDGES) throw limit();
            Edge value = new Edge(id, parent == null ? null : parent.id(), parent == null ? 1 : parent.depth() + 1,
                    domain, sourceItemId, sourceVersionId, row.taskId(), row.targetItemId(), row.targetVersionId());
            edges.put(id, value);
            pending.addLast(value);
        }

        private void count(List<?> rows) {
            if (rows == null) throw integrity();
            if (rows.size() > MAX_BINDINGS - bindingCount) throw limit();
            bindingCount += rows.size();
        }
    }

    private static boolean targetIdentityPresent(Binding row) {
        return row.targetItemOwnerId() != null || row.targetVersionOwnerId() != null || row.actualTargetItemId() != null
                || row.actualTargetVersionId() != null || row.actualTargetVersionNumber() != null;
    }
    private static void source(MaterialReferencesDtos.Source source, String excerptId, MaterialLifecycleDtos.State state) {
        Instant created = source.createdAt();
        if (!excerptId.equals(source.excerptId()) || !storedUuid(source.attachmentId())
                || !source.attachmentId().equals(state.attachmentId())
                || created == null || !created.equals(state.createdAt())
                || created.getNano() % 1000 != 0 || created.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || created.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) throw integrity();
        if ("MANUAL".equals(source.origin())) {
            if (source.runId() != null || source.segmentId() != null) throw integrity();
        } else if (!"PARSED".equals(source.origin()) || !storedUuid(source.runId()) || !storedUuid(source.segmentId())) throw integrity();
    }
    private static boolean storedUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    private static ApiException limit() {
        return new ApiException(409, "MATERIAL_KNOWLEDGE_PREVIEW_LIMIT", "知识采用链超过本次预览预算，未返回截断结果。");
    }
    private static IllegalStateException integrity() {
        return new IllegalStateException("材料知识采用链的原始绑定、任务决定或固定版本不一致");
    }
}
