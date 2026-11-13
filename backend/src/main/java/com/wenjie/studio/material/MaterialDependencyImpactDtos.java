package com.wenjie.studio.material;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public final class MaterialDependencyImpactDtos {
    private MaterialDependencyImpactDtos() {}

    public enum State { PENDING, ALL }
    public enum Domain { PREPARATION, QUESTION, RELATION, STANDARD_REVIEW, KNOWLEDGE }
    public enum Binding { PREPARATION_REFERENCE, QUESTION_ADOPTION, FACT_ADOPTION, RELATION_ADOPTION, RELATION_EVIDENCE, REVIEW_EVIDENCE, SOURCE_DEPENDENCY }
    public enum Choice { KEEP_CURRENT, UPDATED, ARCHIVED, WITHDRAWN, REMOVED }

    // 关系没有版本／修订；审阅以其真实修订为准，均不制造替代标识。
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Target(Domain domain, String id, String versionId, Integer versionNumber, Long revision, String title) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CurrentTarget(Domain domain, String id, String versionId, Integer versionNumber, Long revision,
                                String status, String title) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Resolution(Choice choice, String reason, String targetVersionId, Long targetRevision, Instant decidedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Impact(String id, long revision, MaterialCorrectionDtos.Statement correction, Binding binding,
                         String bindingId, String taskId, Target target, CurrentTarget currentTarget,
                         Instant createdAt, Resolution resolution) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(String excerptId, State state, List<Impact> items, String nextCursor) {}
    public record Resolve(Long expectedRevision, String expectedTargetVersionId, Long expectedTargetRevision,
                          Choice choice, String reason, Boolean confirmed) {}
}
