package com.wenjie.studio.knowledge;

import com.wenjie.studio.ai.AiDtos.TrainingDraftPreviewRequest;
import com.wenjie.studio.ai.FactDraftDtos;
import com.wenjie.studio.ai.FactDraftSources;
import com.wenjie.studio.ai.TrainingDraftSources;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.material.MaterialSources;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.knowledge.RelationEvidenceDtos.*;

@Service
// 历史读路径可将明确的来源失效转为不可用，跨 Bean 校验不能提前把共享事务标记为回滚。
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
public class RelationEvidenceSources {
    private static final Set<String> SOURCE_ERRORS = Set.of(
            "AI_TRAINING_SOURCE_NOT_FOUND", "AI_SOURCE_CHANGED", "AI_EVALUATION_ACCESS_REQUIRED");
    private final MaterialSources materials;
    private final TrainingDraftSources training;

    public RelationEvidenceSources(MaterialSources materials, TrainingDraftSources training) {
        this.materials = materials;
        this.training = training;
    }

    public Snapshot capture(String owner, Selection selection) {
        if (!uuid(owner) || !validSelection(selection)) {
            throw new ApiException(400, "INVALID_REQUEST", "请选择 1–5 个不同资料摘录，或同一确切提交的 1–8 个合法答案片段。");
        }
        Snapshot result = selection.kind() == Kind.MATERIAL
                ? new Snapshot(Kind.MATERIAL, materials.read(owner, selection.excerptIds()), null)
                : new Snapshot(Kind.TRAINING, List.of(), trainingInput(owner, selection.training()));
        if (!validSnapshot(result)) throw new IllegalStateException("关系证据的固定来源结构无效");
        return result;
    }

    public boolean current(String owner, Snapshot snapshot) {
        if (!uuid(owner) || !validSnapshot(snapshot)) return false;
        if (snapshot.kind() == Kind.MATERIAL) return materials.current(owner, snapshot.materials());
        TrainingInput saved = snapshot.training();
        try {
            // 后续提交和会话推进不改变原证据；只复核原答案身份、条件及用户当时选中的原文。
            return saved.equals(trainingInput(owner, new TrainingSelection(saved.sessionId(), saved.questionId(),
                    saved.answerId(), saved.fragments())));
        } catch (ApiException exception) {
            // 只把明确的来源失效降为不可用；数据库或 JSON 损坏不能伪装成普通状态变化。
            if (SOURCE_ERRORS.contains(exception.code())) return false;
            throw exception;
        }
    }

    private TrainingInput trainingInput(String owner, TrainingSelection selection) {
        // 复用内部选区校验，不经过 AI 预览、任务或授权；题面和反馈不进入本地证据快照。
        var fixed = training.input(owner, new TrainingDraftPreviewRequest(selection.sessionId(),
                selection.questionId(), selection.answerId(), "", selection.fragments(), null));
        return new TrainingInput(fixed.sessionId(), fixed.questionId(), fixed.answerId(), fixed.answerNumber(),
                fixed.answerCondition(), List.copyOf(fixed.fragments()));
    }

    public static boolean validSelection(Selection selection) {
        if (selection == null) return false;
        TrainingSelection training = selection.training();
        return FactDraftSources.validSelection(new FactDraftDtos.Selection(kind(selection.kind()), selection.excerptIds(),
                training == null ? null : new FactDraftDtos.TrainingSelection(training.sessionId(), training.questionId(),
                        training.answerId(), training.fragments())));
    }

    public static boolean validSnapshot(Snapshot snapshot) {
        if (snapshot == null) return false;
        TrainingInput training = snapshot.training();
        // 这里只复用已有原文形状规则，不读取 AI 配置，也不构造外发清单。
        return FactDraftSources.valid(new FactDraftDtos.Input(kind(snapshot.kind()), snapshot.materials(),
                training == null ? null : new FactDraftDtos.TrainingInput(training.sessionId(), training.questionId(),
                        training.answerId(), training.answerNumber(), training.answerCondition(), training.fragments())));
    }

    private static FactDraftDtos.Kind kind(Kind kind) {
        return kind == null ? null : FactDraftDtos.Kind.valueOf(kind.name());
    }

    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
}
