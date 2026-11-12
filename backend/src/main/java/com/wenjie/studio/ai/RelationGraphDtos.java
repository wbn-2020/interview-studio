package com.wenjie.studio.ai;

import com.wenjie.studio.knowledge.KnowledgeDtos.RelationType;

import java.util.List;

public final class RelationGraphDtos {
    private RelationGraphDtos() {}

    public record Edge(String id, String sourceItemId, String targetItemId, RelationType type,
                       String reason, int citationCount) {}
    public record Projection(AiDtos.Summary task, boolean sourcesCurrent, List<AiDtos.OriginSource> sources,
                             String summary, String limitations, String validationNote, int totalCandidates,
                             int decidedCount, List<Edge> relations) {}
}
