package com.wenjie.studio.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wenjie.studio.memory.MemoryDtos;

import java.time.Instant;
import java.util.List;

public final class CardDraftDtos {
    private CardDraftDtos() {}

    public record PreviewRequest(MemoryDtos.Kind kind, String instruction, List<AiDtos.SourceLink> sources) {}
    public record CreateRequest(MemoryDtos.Kind kind, String instruction, List<AiDtos.SourceLink> sources,
                                String expectedManifestHash, Boolean consentConfirmed,
                                @JsonInclude(JsonInclude.Include.NON_NULL) AiAuthorizationDtos.Use authorization) {}
    public record Candidate(MemoryDtos.Kind kind, String title, String prompt, String reference,
                            List<AiDtos.Citation> citations, String validationNote) {}
    public record Adopt(Long expectedRevision, MemoryDtos.Content content, Boolean confirmed) {}
    public record Decision(AiDtos.DecisionStatus status, String targetCardId, String targetVersionId,
                           Instant createdAt) {}
}
