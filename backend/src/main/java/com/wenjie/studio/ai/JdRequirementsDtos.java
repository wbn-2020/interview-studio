package com.wenjie.studio.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public final class JdRequirementsDtos {
    private JdRequirementsDtos() {}

    public record PreviewRequest(String targetItemId, String targetVersionId, String instruction) {}
    public record CreateRequest(String targetItemId, String targetVersionId, String instruction,
                                String expectedManifestHash, Boolean consentConfirmed,
                                @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record Suggestion(int number, String text, List<String> dimensions, Integer priority,
                             List<AiDtos.Citation> citations) {}
    public record Candidate(List<Suggestion> requirements, String limitations, String validationNote) {}
    public record Selection(Integer candidateNumber, String text, List<String> dimensions, Integer priority) {}
    public record Adopt(Long expectedRevision, Long expectedTargetRevision, List<Selection> requirements,
                        Boolean confirmed) {}
    public record RequirementLink(Integer candidateNumber, String requirementId) {}
    public record Decision(AiDtos.DecisionStatus status, String targetItemId, String targetVersionId,
                           List<RequirementLink> requirements, Instant createdAt) {}
    public record Origin(String taskId, String targetVersionId, int targetVersionNumber, Instant adoptedAt,
                         boolean direct, AiDtos.OriginSource source, List<RequirementLink> requirements) {}
    public record OriginPage(List<Origin> items, Integer nextCursor) {}
}
