package com.wenjie.studio.review;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.knowledge.KnowledgeDtos;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import static com.wenjie.studio.review.ReviewedBasisRows.*;
import static com.wenjie.studio.review.StandardReviewDtos.*;
import static com.wenjie.studio.review.StandardReviewRules.*;

/** 直接来源的追加式变更账本；源命令持有 owner 锁并与本账本一起提交。 */
@Component
public class ReviewedBasisInvalidation {
    private final ReviewedBasisMapper mapper;

    public ReviewedBasisInvalidation(ReviewedBasisMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
    public long generation(String owner, DirectRef source) {
        readTransaction(); uuid(owner); direct(source);
        GenerationRow row = checked(owner, source, mapper.generation(owner, source.kind().name(), source.id()));
        // 从未发生直接更正的来源以零为基线；读取不能制造持久化状态。
        return row == null ? 0 : row.generation();
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
    public DirectChange firstAfter(String owner, DirectRef source, long baseline) {
        readTransaction(); uuid(owner); direct(source); revision(baseline);
        return change(owner, source, baseline, mapper.firstAfter(owner, source.kind().name(), source.id(), baseline));
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
    public long upstreamCountAfter(String owner, DirectRef source, long baseline) {
        readTransaction(); uuid(owner); direct(source); revision(baseline);
        if (source.kind() != DirectKind.KNOWLEDGE) return 0;
        long count = mapper.upstreamCountAfter(owner, source.id(), baseline);
        if (count < 0 || count > MAX - baseline) throw integrity();
        return count;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
    public DirectChange firstUpstreamAfter(String owner, String itemId, String versionId, long baseline) {
        readTransaction(); uuid(owner); uuid(itemId); uuid(versionId); revision(baseline);
        DirectChange change = change(owner, new DirectRef(DirectKind.KNOWLEDGE, itemId), baseline,
                mapper.firstUpstreamAfter(owner, itemId, versionId, baseline));
        if (change != null && change.kind() != DirectChangeKind.KNOWLEDGE_UPSTREAM_CHANGED) throw integrity();
        return change;
    }

    private static DirectChange change(String owner, DirectRef source, long baseline, ChangeRow row) {
        if (row == null) return null;
        if (!owner.equals(row.ownerId()) || !source.kind().name().equals(row.sourceKind())
                || !source.id().equals(row.sourceId()) || row.generation() <= baseline || row.generation() > MAX) {
            throw integrity();
        }
        time(row.createdAt());
        DirectChangeKind kind = DirectChangeKind.valueOf(row.changeKind());
        if (kind == DirectChangeKind.KNOWLEDGE_UPSTREAM_CHANGED) {
            if (source.kind() != DirectKind.KNOWLEDGE) throw integrity();
            uuid(row.upstreamVersionId());
        } else if (row.upstreamVersionId() != null) throw integrity();
        return new DirectChange(source, row.generation(), kind,
                row.previousVersionId(), row.currentVersionId(), row.previousStatus(), row.currentStatus(), row.createdAt());
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
    public void checkClock(String owner, DirectRef source, Instant now) {
        readTransaction(); uuid(owner); direct(source); time(now);
        GenerationRow row = checked(owner, source, mapper.generation(owner, source.kind().name(), source.id()));
        if (row != null) clock(now, row.updatedAt());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void questionVersionChanged(String owner, String id, String previousVersionId,
                                       String currentVersionId, Instant now) {
        writeTransaction(); uuid(previousVersionId); uuid(currentVersionId);
        if (previousVersionId.equals(currentVersionId)) return;
        append(owner, new DirectRef(DirectKind.QUESTION, id), DirectChangeKind.QUESTION_VERSION_CHANGED,
                previousVersionId, currentVersionId, QuestionDtos.Status.ACTIVE.name(), QuestionDtos.Status.ACTIVE.name(), now, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void questionStatusChanged(String owner, String id, String versionId,
                                      QuestionDtos.Status previous, QuestionDtos.Status current, Instant now) {
        writeTransaction(); uuid(versionId); required(previous); required(current);
        if (previous == current) return;
        append(owner, new DirectRef(DirectKind.QUESTION, id), current == QuestionDtos.Status.ARCHIVED
                        ? DirectChangeKind.QUESTION_ARCHIVED : DirectChangeKind.QUESTION_RESTORED,
                versionId, versionId, previous.name(), current.name(), now, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void knowledgePublished(String owner, String id, KnowledgeDtos.Kind kind,
                                   KnowledgeDtos.Version previous, KnowledgeDtos.Version current, Instant now) {
        writeTransaction(); required(kind); required(previous); required(current);
        uuid(previous.id()); uuid(current.id()); required(previous.body()); required(current.body());
        // 只比较原始发布正文，标题发布及草稿编辑不使旧审阅失效。
        if (previous.body().equals(current.body())) return;
        if (previous.id().equals(current.id())) throw integrity();
        append(owner, new DirectRef(DirectKind.KNOWLEDGE, id), DirectChangeKind.KNOWLEDGE_BODY_CHANGED,
                previous.id(), current.id(), KnowledgeDtos.Status.ACTIVE.name(), KnowledgeDtos.Status.ACTIVE.name(), now, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void knowledgeStatusChanged(String owner, String id, KnowledgeDtos.Kind kind, String versionId,
                                       KnowledgeDtos.Status previous, KnowledgeDtos.Status current, Instant now) {
        writeTransaction(); required(kind); uuid(versionId); required(previous); required(current);
        if (previous == current) return;
        append(owner, new DirectRef(DirectKind.KNOWLEDGE, id), current == KnowledgeDtos.Status.ARCHIVED
                        ? DirectChangeKind.KNOWLEDGE_ARCHIVED : DirectChangeKind.KNOWLEDGE_RESTORED,
                versionId, versionId, previous.name(), current.name(), now, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void knowledgeUpstreamChanged(String owner, String id, String adoptedVersionId, Instant now) {
        writeTransaction(); uuid(owner); uuid(id); uuid(adoptedVersionId); time(now);
        KnowledgeRow row = mapper.knowledge(owner, id, adoptedVersionId);
        if (row == null || !owner.equals(row.ownerId()) || !id.equals(row.itemId())
                || !("ACTIVE".equals(row.status()) || "ARCHIVED".equals(row.status()))) {
            throw integrity();
        }
        uuid(row.versionId());
        // 采用来源的更正推进独立事件；相同版本和状态指针不能冒充目标正文已经变化。
        append(owner, new DirectRef(DirectKind.KNOWLEDGE, id), DirectChangeKind.KNOWLEDGE_UPSTREAM_CHANGED,
                row.versionId(), row.versionId(), row.status(), row.status(), now, adoptedVersionId);
    }

    private void append(String owner, DirectRef source, DirectChangeKind kind, String previousVersionId,
                        String currentVersionId, String previousStatus, String currentStatus, Instant now, String upstreamVersionId) {
        uuid(owner); direct(source); time(now);
        GenerationRow before = checked(owner, source, mapper.lockGeneration(owner, source.kind().name(), source.id()));
        long generation = before == null ? 0 : before.generation();
        if (generation == MAX) throw basisLimit("直接来源变更号已达到安全上限，本次来源变更未提交。");
        if (before != null) clock(now, before.updatedAt());
        GenerationRow after = new GenerationRow(owner, source.kind().name(), source.id(), generation + 1, now);
        // 首次真实事件才建行；后续以 CAS 推进，改后改回也追加事件，不能重置旧审阅基线。
        changed(before == null ? mapper.insertGeneration(after) : mapper.updateGeneration(after, generation));
        changed(mapper.insertChange(new ChangeRow(owner, source.kind().name(), source.id(), after.generation(),
                kind.name(), previousVersionId, currentVersionId, previousStatus, currentStatus, now, upstreamVersionId)));
    }

    private static GenerationRow checked(String owner, DirectRef source, GenerationRow row) {
        if (row == null) return null;
        if (!owner.equals(row.ownerId()) || !source.kind().name().equals(row.sourceKind())
                || !source.id().equals(row.sourceId()) || row.generation() < 1 || row.generation() > MAX) {
            throw integrity();
        }
        time(row.updatedAt());
        return row;
    }

    private static void changed(int count) {
        if (count != 1) throw conflict("REVISION_CONFLICT", "直接来源基线已变化，本次来源变更未提交。", null);
    }

    private static void readTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("直接来源账本读取必须处于来源读取事务中");
        }
    }

    private static void writeTransaction() {
        readTransaction();
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("直接来源账本写入必须处于持有 owner 锁的命令事务中");
        }
    }
}
