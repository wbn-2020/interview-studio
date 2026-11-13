package com.wenjie.studio.material;

import com.wenjie.studio.lifecycle.KnowledgeRevalidationMapper;
import com.wenjie.studio.memory.MemoryInvalidation;
import com.wenjie.studio.review.ReviewedBasisInvalidation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;

import static com.wenjie.studio.material.MaterialKnowledgeImpactRules.*;

@Component
public class MaterialKnowledgeImpactPropagation {
    private final MaterialKnowledgeImpactMapper mapper;
    private final KnowledgeRevalidationMapper frozen;
    private final MemoryInvalidation memory;
    private final ReviewedBasisInvalidation reviewed;

    public MaterialKnowledgeImpactPropagation(MaterialKnowledgeImpactMapper mapper, KnowledgeRevalidationMapper frozen,
                                             MemoryInvalidation memory, ReviewedBasisInvalidation reviewed) {
        this.mapper = mapper; this.frozen = frozen; this.memory = memory; this.reviewed = reviewed;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkClock(String owner, Instant now) {
        writing(); uuid(owner);
        if (!validTime(now)) throw clock("服务器时间不在可保存范围，本次更正未提交。");
        // 必须在写入 V74 根声明前检查；不能推进到未来时间，也不能把本次根当作历史冻结点。
        Instant latest = frozen.latestFrozenAt(owner);
        if (latest != null && (!validTime(latest) || !now.isAfter(latest))) {
            throw clock("服务器时间未晚于既有任务、回忆证据或来源更正，本次更正未提交，请校正服务器时间后重试。");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void corrected(String owner, MaterialCorrectionDtos.Statement correction, MaterialKnowledgePreviewDtos.Preview graph) {
        writing(); uuid(owner);
        if (correction == null || graph == null || graph.nodes() == null || graph.edges() == null
                || graph.nodes().size() > 200 || graph.edges().size() > 500 || graph.source() == null
                || graph.lifecycle() == null || !"REGISTERED_KNOWLEDGE_ADOPTIONS_ONLY".equals(graph.coverage())
                || !correction.excerptId().equals(graph.source().excerptId())
                || !correction.excerptId().equals(graph.lifecycle().excerptId())
                || !graph.source().attachmentId().equals(graph.lifecycle().attachmentId())
                || correction.sourceRevision() != graph.lifecycle().revision() || !validTime(correction.createdAt())) throw integrity();
        uuid(correction.id()); uuid(correction.excerptId()); uuid(correction.replacementExcerptId());
        var nodes = new HashMap<String, MaterialKnowledgePreviewDtos.Node>();
        for (var node : graph.nodes()) {
            if (node == null || !storedUuid(node.itemId()) || !storedUuid(node.versionId())
                    || node.versionNumber() < 1 || node.title() == null || node.title().isBlank()
                    || nodes.put(node.versionId(), node) != null) throw integrity();
        }
        var ids = new HashMap<String, String>();
        for (var edge : graph.edges()) {
            if (edge == null || edge.id() == null || ids.put(edge.id(), UUID.randomUUID().toString()) != null) throw integrity();
        }
        var insertedEdges = new HashMap<String, MaterialKnowledgePreviewDtos.Edge>();
        var exactEdges = new HashSet<String>();
        int previousDepth = 0;
        String previousId = null;
        // 复用完整预览的最短稳定父路径；此排序不承诺与其他来源传播器的同层平局规则相同。
        for (var edge : graph.edges()) {
            uuid(edge.taskId()); uuid(edge.sourceItemId()); uuid(edge.targetItemId()); uuid(edge.targetVersionId());
            var target = nodes.get(edge.targetVersionId());
            if (target == null || !target.itemId().equals(edge.targetItemId()) || edge.domain() == null
                    || edge.depth() < 1 || edge.depth() > 500 || edge.depth() < previousDepth
                    || edge.depth() == previousDepth && previousId != null && edge.id().compareTo(previousId) <= 0
                    || !exactEdges.add(edge.domain() + "~" + edge.taskId() + "~" + edge.sourceItemId()
                        + "~" + edge.sourceVersionId())) throw integrity();
            String materialId = null, knowledgeId = null, knowledgeVersionId = null, parentId = null;
            if (edge.domain() == MaterialKnowledgePreviewDtos.Domain.MATERIAL_EXCERPT) {
                if (!correction.excerptId().equals(edge.sourceItemId()) || edge.sourceVersionId() != null
                        || edge.depth() != 1 || edge.parentId() != null) throw integrity();
                materialId = edge.sourceItemId();
            } else {
                var source = nodes.get(edge.sourceVersionId());
                var parent = insertedEdges.get(edge.parentId());
                if (source == null || !source.itemId().equals(edge.sourceItemId()) || parent == null
                        || !parent.targetItemId().equals(edge.sourceItemId()) || parent.depth() + 1 != edge.depth()) throw integrity();
                var parentTarget = nodes.get(parent.targetVersionId());
                if (parentTarget == null || source.versionNumber() < parentTarget.versionNumber()) throw integrity();
                knowledgeId = edge.sourceItemId(); knowledgeVersionId = edge.sourceVersionId(); parentId = ids.get(edge.parentId());
            }
            String impactId = ids.get(edge.id());
            inserted(mapper.insert(owner, impactId, correction.id(), edge.domain().name(), materialId, knowledgeId,
                    knowledgeVersionId, edge.taskId(), edge.targetItemId(), edge.targetVersionId(), edge.depth(),
                    parentId, correction.createdAt()));
            // 每条真实采用边先落库，再调用既有钩子；同 owner 事务中任一失败会整体回滚。
            memory.knowledgeUpstreamChanged(owner, edge.targetItemId(), edge.targetVersionId(), impactId, correction.createdAt());
            reviewed.knowledgeUpstreamChanged(owner, edge.targetItemId(), edge.targetVersionId(), correction.createdAt());
            insertedEdges.put(edge.id(), edge); previousDepth = edge.depth(); previousId = edge.id();
        }
    }
}
