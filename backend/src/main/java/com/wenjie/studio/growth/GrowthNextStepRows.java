package com.wenjie.studio.growth;

/** 建议候选只读取稳定身份和计数，来源正文留在原领域。 */
public final class GrowthNextStepRows {
    private GrowthNextStepRows() {}

    public record ActionSize(String actionId, int sourceCount) {}
    public record RequirementCandidate(String targetId, String targetVersionId, String requirementId) {}
}
