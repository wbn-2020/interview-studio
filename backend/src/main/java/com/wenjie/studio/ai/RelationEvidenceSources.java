package com.wenjie.studio.ai;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static com.wenjie.studio.ai.FactDraftDtos.*;
import static com.wenjie.studio.ai.RelationEvidenceRows.*;

@Service("aiRelationEvidenceSources")
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
public class RelationEvidenceSources {
    private final FactDraftSources sources;
    private final RelationEvidenceMapper mapper;

    public RelationEvidenceSources(FactDraftSources sources, RelationEvidenceMapper mapper) {
        this.sources = sources;
        this.mapper = mapper;
    }

    public Input input(String owner, Selection selection) {
        // 首次捕获失败向写命令传出；状态复核走 current，不捕获跨 Bean 的 input 失败继续提交。
        return sources.input(owner, selection);
    }

    public boolean current(String owner, Input input) {
        uuid(owner); fixed(input);
        return sources.current(owner, input);
    }

    public AiDtos.OriginState state(String owner, Input input) {
        return current(owner, input) ? AiDtos.OriginState.CURRENT : AiDtos.OriginState.UNAVAILABLE;
    }

    public void insert(String owner, String taskId, Input input) {
        uuid(owner); uuid(taskId); fixed(input);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw broken();
        requireBinding(owner, taskId, null);
        if (mapper.insertSource(source(owner, taskId, input)) != 1) throw broken();
        for (int index = 0; index < input.materials().size(); index++) {
            if (mapper.insertMaterial(material(owner, taskId, input, index)) != 1) throw broken();
        }
        requireBinding(owner, taskId, input);
    }

    public void requireBinding(String owner, String taskId, Input input) {
        uuid(owner); uuid(taskId);
        if (input != null) fixed(input);
        SourceRow row = mapper.source(owner, taskId);
        var materials = mapper.materials(owner, taskId);
        if (materials == null) throw broken();
        // 旧清单没有此来源分支时也核空台账，避免孤立身份绕过来源互斥检查。
        if (input == null) {
            if (row != null || !materials.isEmpty()) throw broken();
            return;
        }
        if (!source(owner, taskId, input).equals(row) || materials.size() != input.materials().size()) throw broken();
        for (int index = 0; index < materials.size(); index++) {
            if (!material(owner, taskId, input, index).equals(materials.get(index))) throw broken();
        }
    }

    private static SourceRow source(String owner, String taskId, Input input) {
        TrainingInput training = input.training();
        return new SourceRow(owner, taskId, input.kind().name(), training == null ? null : training.sessionId(),
                training == null ? null : training.questionId(), training == null ? null : training.answerId());
    }

    private static MaterialRow material(String owner, String taskId, Input input, int index) {
        return new MaterialRow(owner, taskId, Kind.MATERIAL.name(), input.materials().get(index).excerptId(), index + 1);
    }

    private static void fixed(Input input) { if (!FactDraftSources.valid(input)) throw broken(); }
    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw broken(); }
        catch (IllegalArgumentException exception) { throw broken(); }
    }
    private static IllegalStateException broken() { return new IllegalStateException("关系候选直接来源台账与固定清单不一致"); }
}
