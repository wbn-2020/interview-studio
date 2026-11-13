package com.wenjie.studio.material;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wenjie.studio.lifecycle.KnowledgeRevalidationDtos;

import java.time.Instant;
import java.util.List;

public final class MaterialKnowledgeImpactDtos {
    private MaterialKnowledgeImpactDtos() {}

    public enum Context { MATERIAL_EXCERPT, KNOWLEDGE }
    public enum Domain { MATERIAL_EXCERPT, KNOWLEDGE }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Source(Domain domain, String itemId, String versionId, Integer versionNumber, String title) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Impact(String id, long revision, MaterialCorrectionDtos.Statement correction, Source source,
                         String taskId, String targetItemId, String targetTitle, String targetVersionId,
                         int targetVersionNumber, int depth, String parentImpactId, Instant createdAt,
                         KnowledgeRevalidationDtos.Resolution resolution) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(Context context, String itemId, KnowledgeRevalidationDtos.State state,
                       List<Impact> items, String nextCursor) {}
}
