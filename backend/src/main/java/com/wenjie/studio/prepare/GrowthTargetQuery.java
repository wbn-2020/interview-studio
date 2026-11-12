package com.wenjie.studio.prepare;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.growth.GrowthRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static com.wenjie.studio.growth.GrowthDtos.*;

/** 成长只读取确切目标版本的要求，不借 AI 外发投影读取完整 JD。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
public class GrowthTargetQuery {
    private final GrowthTargetMapper mapper;
    private final JsonMapper json;

    public GrowthTargetQuery(GrowthTargetMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    }

    public record Projection(TargetMeta target, List<RequirementMeta> requirements) {
        public RequirementMeta requirement(String id) {
            GrowthRules.uuid(id);
            return requirements.stream().filter(value -> id.equals(value.id())).findFirst().orElseThrow(GrowthTargetQuery::missing);
        }
        public boolean current() {
            return target.status() == PreparationDtos.Status.ACTIVE
                    && target.ref().versionId().equals(target.currentVersionId());
        }
        public String stamp(String requirementId) {
            return GrowthRules.hash("GROWTH_TARGET_V1", new RequirementStamp(target, requirement(requirementId)));
        }
    }

    private record RequirementStamp(TargetMeta target, RequirementMeta requirement) {}

    public Projection require(String owner, TargetRef ref) {
        GrowthRules.uuid(owner); GrowthRules.target(ref);
        var row = mapper.target(owner, ref.itemId(), ref.versionId());
        if (row == null) throw missing();
        if (!owner.equals(row.ownerId()) || !ref.itemId().equals(row.itemId()) || !ref.versionId().equals(row.versionId())
                || row.requirementsJson() == null) throw GrowthRules.integrity();
        try {
            var target = GrowthRules.targetMeta(new TargetMeta(ref, row.title(),
                    row.capturedOn() == null ? null : LocalDate.parse(row.capturedOn()), row.itemRevision(),
                    PreparationDtos.Status.valueOf(row.status()), row.currentVersionId(), row.sourceGeneration()));
            if (row.requirementsJson().getBytes(StandardCharsets.UTF_8).length > 512 * 1024) throw GrowthRules.integrity();
            var saved = json.readValue(row.requirementsJson(), PreparationDtos.Requirement[].class);
            if (saved == null) throw GrowthRules.integrity();
            if (saved.length > 50) throw GrowthRules.integrity();
            var requirements = new ArrayList<RequirementMeta>();
            var ids = new HashSet<String>();
            for (int index = 0; index < saved.length; index++) {
                var item = saved[index];
                if (item == null || item.priority() == null || !ids.add(item.id())) throw GrowthRules.integrity();
                requirements.add(GrowthRules.requirementMeta(new RequirementMeta(item.id(), index + 1,
                        item.text(), item.dimensions(), item.priority())));
            }
            return new Projection(target, List.copyOf(requirements));
        } catch (IllegalArgumentException | DateTimeException | ApiException exception) { throw GrowthRules.integrity(); }
    }

    public void filter(String owner, TargetRef target, String topicId) {
        GrowthRules.uuid(owner);
        if (target != null) require(owner, target);
        if (topicId != null && !mapper.topicExists(owner, GrowthRules.uuid(topicId))) {
            throw new ApiException(404, "NOT_FOUND", "主题不存在。");
        }
    }

    private static ApiException missing() { return new ApiException(404, "GROWTH_TARGET_NOT_FOUND", "目标版本或要求不存在。"); }
}
