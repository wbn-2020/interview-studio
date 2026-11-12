package com.wenjie.studio.ai;

import com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceDtos.Selection;
import com.wenjie.studio.knowledge.RelationAiFeedbackEvidenceDtos.Snapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/** 关系候选复用固定 AI 反馈选区，并独立保存新任务与原评价的身份绑定。 */
@Service("aiRelationAiFeedbackDraftSources")
@Transactional(propagation = Propagation.MANDATORY, readOnly = true, noRollbackFor = RuntimeException.class)
public class RelationAiFeedbackDraftSources {
    private final RelationAiFeedbackSources sources;
    private final RelationAiFeedbackDraftMapper mapper;

    public RelationAiFeedbackDraftSources(RelationAiFeedbackSources sources, RelationAiFeedbackDraftMapper mapper) {
        this.sources = sources;
        this.mapper = mapper;
    }

    public Snapshot input(String owner, Selection selection) { return sources.capture(owner, selection); }

    public void requireAccess(String owner, Selection selection) { sources.requireAccess(owner, selection); }

    public void requireAccess(String owner, Snapshot snapshot) { sources.requireAccess(owner, snapshot); }

    public boolean current(String owner, Snapshot snapshot) { return sources.current(owner, snapshot); }

    public AiDtos.OriginState state(String owner, Snapshot snapshot) { return sources.state(owner, snapshot); }

    public static boolean valid(Snapshot snapshot) { return RelationAiFeedbackSources.validSnapshot(snapshot); }

    public static boolean validCitation(Snapshot snapshot, ReviewDraftDtos.Citation citation) {
        return RelationAiFeedbackSources.validCitation(snapshot, citation);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(String owner, String taskId, Snapshot snapshot) {
        uuid(owner); uuid(taskId); fixed(snapshot);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw broken();
        requireBinding(owner, taskId, null);
        if (mapper.insert(binding(owner, taskId, snapshot)) != 1) throw broken();
        requireBinding(owner, taskId, snapshot);
    }

    public void requireBinding(String owner, String taskId, Snapshot snapshot) {
        uuid(owner); uuid(taskId);
        if (snapshot != null) fixed(snapshot);
        var row = mapper.binding(owner, taskId);
        // 台账恢复只核固定身份，动态权限与决定变化由独立来源复核处理。
        if (snapshot == null) {
            if (row != null) throw broken();
            return;
        }
        if (!binding(owner, taskId, snapshot).equals(row)) throw broken();
    }

    private static RelationAiFeedbackDraftRows.Binding binding(String owner, String taskId, Snapshot snapshot) {
        return new RelationAiFeedbackDraftRows.Binding(owner, taskId, snapshot.taskId(), snapshot.attemptId(),
                snapshot.reportHash(), snapshot.feedbackId(), snapshot.answer().sessionId(), snapshot.answer().questionId(),
                snapshot.answer().answerId(), snapshot.decisionRevision() == 0 ? null : snapshot.decisionRevision(),
                snapshot.decisionChoice() == null ? null : snapshot.decisionChoice().name(), snapshot.level().name(),
                snapshot.fragments().size());
    }

    private static void fixed(Snapshot snapshot) { if (!valid(snapshot)) throw broken(); }

    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw broken(); }
        catch (IllegalArgumentException exception) { throw broken(); }
    }

    private static IllegalStateException broken() { return new IllegalStateException("AI 反馈关系候选来源台账与固定清单不一致"); }
}
