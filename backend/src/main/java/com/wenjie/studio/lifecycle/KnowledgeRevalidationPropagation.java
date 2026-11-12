package com.wenjie.studio.lifecycle;

import com.wenjie.studio.knowledge.KnowledgeDtos;
import com.wenjie.studio.memory.MemoryInvalidation;
import com.wenjie.studio.review.ReviewedBasisInvalidation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.wenjie.studio.lifecycle.KnowledgeRevalidationDtos.*;
import static com.wenjie.studio.lifecycle.KnowledgeRevalidationRows.*;
import static com.wenjie.studio.lifecycle.KnowledgeRevalidationRules.*;

@Component
public class KnowledgeRevalidationPropagation {
    private final KnowledgeRevalidationMapper mapper;
    private final MemoryInvalidation memory;
    private final ReviewedBasisInvalidation reviewed;

    public KnowledgeRevalidationPropagation(KnowledgeRevalidationMapper mapper, MemoryInvalidation memory,
                                           ReviewedBasisInvalidation reviewed) {
        this.mapper = mapper; this.memory = memory; this.reviewed = reviewed;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void knowledgePublished(String owner, String itemId, KnowledgeDtos.Version previous,
                                   KnowledgeDtos.Version current, Instant now) {
        writing(); uuid(owner); uuid(itemId); time(now);
        if (previous == null || current == null || previous.body() == null || current.body() == null) throw integrity();
        if (previous.body().equals(current.body())) return;
        uuid(previous.id()); uuid(current.id());
        Integer before = mapper.versionNumber(owner, itemId, previous.id());
        Integer after = mapper.versionNumber(owner, itemId, current.id());
        if (before == null || after == null || before != previous.number() || after != current.number() || after <= before) throw integrity();
        propagate(owner, itemId, previous.id(), current.id(), ChangeKind.BODY_CHANGED, now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void knowledgeArchived(String owner, String itemId, String versionId, Instant now) {
        writing(); uuid(owner); uuid(itemId); uuid(versionId); time(now);
        if (mapper.versionNumber(owner, itemId, versionId) == null) throw integrity();
        propagate(owner, itemId, versionId, versionId, ChangeKind.ARCHIVED, now);
    }

    private void propagate(String owner, String itemId, String previousVersionId, String currentVersionId,
                           ChangeKind kind, Instant now) {
        Instant frozenAt = mapper.latestFrozenAt(owner);
        if (frozenAt != null) {
            time(frozenAt);
            // 旧任务与评分以冻结时间核对历史影响；同 owner 锁内严格前进才能防止时钟回拨恢复旧基线。
            if (!now.isAfter(frozenAt)) {
                throw new com.wenjie.studio.common.ApiException(409, "KNOWLEDGE_REVALIDATION_CLOCK_CONFLICT",
                        "服务器时间未晚于既有任务或回忆证据，本次知识变更未提交，请校正服务器时间后重试。");
            }
        }
        String eventId = UUID.randomUUID().toString();
        inserted(mapper.insertEvent(owner, eventId, itemId, previousVersionId, currentVersionId, kind.name(), now));
        // 根先登记全部一跳，后代才开始展开，避免间接路径抢占同一条边的更短父路径。
        expand(owner, eventId, itemId, itemId, 1, null, now);
        while (true) {
            var parents = mapper.pendingImpacts(owner, eventId, 100);
            if (parents.size() > 100) throw integrity();
            if (parents.isEmpty()) {
                // 不因固定身份联查丢行而提交尚未展开的事件；私有队列必须全部归零。
                if (mapper.propagationPending(owner, eventId)) throw integrity();
                return;
            }
            int depth = parents.getFirst().depth();
            for (var parent : parents) {
                // 查询只允许当前最小待展开层；这一层清空后，下一层才按任务／来源身份稳定展开。
                if (parent.depth() != depth) throw integrity();
                lineageIdentity(parent);
                if (!eventId.equals(parent.eventId()) || !itemId.equals(parent.rootItemId())
                        || !previousVersionId.equals(parent.rootPreviousVersionId())
                        || !currentVersionId.equals(parent.rootCurrentVersionId()) || !kind.name().equals(parent.changeKind())
                        || !now.equals(parent.createdAt())) throw integrity();
                if (parent.parentImpactId() != null) {
                    var previous = mapper.lineage(owner, parent.sourceItemId(), parent.parentImpactId());
                    if (previous == null) throw integrity();
                    parentIdentity(parent, previous);
                }
                expand(owner, eventId, itemId, parent.targetItemId(), parent.targetVersionNumber(), parent, now);
                inserted(mapper.completePropagation(owner, eventId, parent.id()));
            }
        }
    }

    private void expand(String owner, String eventId, String rootItemId, String sourceItemId, int minimumVersion,
                        ImpactRow parent, Instant now) {
        String after = null;
        while (true) {
            // 下限来自父采用目标的真实版本；同对象可因不同采用版再次展开，不能按对象截断循环或菱形。
            var rows = mapper.adoptions(owner, sourceItemId, minimumVersion, after, 100);
            if (rows.size() > 100) throw integrity();
            for (var row : rows) {
                uuid(row.taskId()); uuid(row.sourceItemId()); uuid(row.sourceVersionId());
                uuid(row.targetItemId()); uuid(row.targetVersionId());
                if (!sourceItemId.equals(row.sourceItemId()) || row.sourceVersionNumber() < minimumVersion
                        || row.targetVersionNumber() < 1 || after != null && row.taskId().compareTo(after) <= 0) throw integrity();
                if (!mapper.edgeExists(owner, eventId, row.taskId(), sourceItemId, row.sourceVersionId())) {
                    if (parent != null && parent.depth() == Integer.MAX_VALUE) {
                        throw new com.wenjie.studio.common.ApiException(409, "KNOWLEDGE_REVALIDATION_DEPTH_LIMIT",
                                "知识采用链跳数已达到保存上限，本次知识变更未提交。");
                    }
                    int depth = parent == null ? 1 : parent.depth() + 1;
                    String impactId = UUID.randomUUID().toString();
                    inserted(mapper.insertImpact(owner, impactId, eventId, rootItemId, sourceItemId, row.sourceVersionId(),
                            row.taskId(), row.targetItemId(), row.targetVersionId(), parent == null ? null : parent.id(), depth, now));
                    // owner 写锁下每条实际采用边只登记一次，并在同一事务调用一次各下游钩子。
                    memory.knowledgeUpstreamChanged(owner, row.targetItemId(), row.targetVersionId(), impactId, now);
                    reviewed.knowledgeUpstreamChanged(owner, row.targetItemId(), row.targetVersionId(), now);
                }
                after = row.taskId();
            }
            if (rows.size() < 100) return;
        }
    }
}
