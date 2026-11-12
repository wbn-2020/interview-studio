package com.wenjie.studio.growth;

import com.wenjie.studio.ai.AiGrowthQuery;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.memory.MemoryGrowthQuery;
import com.wenjie.studio.review.ReviewGrowthQuery;
import com.wenjie.studio.training.TrainingGrowthQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.wenjie.studio.growth.GrowthDtos.*;

/** 所有解析仍由原领域核对；缺失墓碑只供已保存关联，不作为新引用的授权。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
public class GrowthEvidenceQuery {
    private final TrainingGrowthQuery training;
    private final ReviewGrowthQuery review;
    private final AiGrowthQuery ai;
    private final MemoryGrowthQuery memory;
    private final ReviewedObservationGrowthQuery reviewed;

    public GrowthEvidenceQuery(TrainingGrowthQuery training, ReviewGrowthQuery review,
                               AiGrowthQuery ai, MemoryGrowthQuery memory, ReviewedObservationGrowthQuery reviewed) {
        this.training = training; this.review = review; this.ai = ai; this.memory = memory;
        this.reviewed = reviewed;
    }

    public EvidenceResolution resolve(String owner, EvidenceRef ref) {
        return resolve(owner, List.of(GrowthRules.evidence(ref))).getFirst();
    }

    public EvidenceResolution require(String owner, EvidenceRef ref) {
        var value = resolve(owner, ref);
        if (value.summary() == null) throw new ApiException(404, "EVIDENCE_NOT_FOUND", "所选证据不存在或不属于当前工作区。");
        return value;
    }

    public List<EvidenceResolution> resolve(String owner, List<EvidenceRef> references) {
        GrowthRules.uuid(owner); GrowthRules.references(references);
        var results = new java.util.HashMap<EvidenceRef, EvidenceResolution>();
        for (var kind : EvidenceKind.values()) {
            var group = references.stream().filter(ref -> ref.kind() == kind).toList();
            if (group.isEmpty()) continue;
            var resolved = switch (kind) {
                case TRAINING_ANSWER -> training.resolve(owner, group);
                case MANUAL_FEEDBACK, RECOLLECTION, REPLAY_JUDGMENT -> review.resolve(owner, group);
                case AI_OBSERVATION -> ai.resolve(owner, group);
                case MEMORY_RATING -> memory.resolve(owner, group);
                case REVIEWED_OBSERVATION -> reviewed.resolve(owner, group);
            };
            if (resolved == null || resolved.size() != group.size()) throw GrowthRules.integrity();
            for (int index = 0; index < group.size(); index++) {
                var value = GrowthRules.resolution(resolved.get(index));
                if (!group.get(index).equals(value.ref()) || results.put(value.ref(), value) != null) throw GrowthRules.integrity();
            }
        }
        return references.stream().map(results::get).toList();
    }

    public DomainEvidenceWindow page(String owner, EvidenceKind kind, Scope scope, AssessmentLevel level,
                                     ReviewFlag flag, EvidencePosition after, QueryBudget budget) {
        GrowthRules.uuid(owner); GrowthRules.required(kind); GrowthRules.scope(scope); GrowthRules.budget(budget);
        GrowthRules.position(after, scope, kind);
        return switch (kind) {
            case TRAINING_ANSWER -> training.page(owner, kind, scope, level, flag, after, budget);
            case MANUAL_FEEDBACK, RECOLLECTION, REPLAY_JUDGMENT -> review.page(owner, kind, scope, level, flag, after, budget);
            case AI_OBSERVATION -> ai.page(owner, kind, scope, level, flag, after, budget);
            case MEMORY_RATING -> memory.page(owner, kind, scope, level, flag, after, budget);
            case REVIEWED_OBSERVATION -> reviewed.page(owner, scope, level, flag, after, budget);
        };
    }
}
