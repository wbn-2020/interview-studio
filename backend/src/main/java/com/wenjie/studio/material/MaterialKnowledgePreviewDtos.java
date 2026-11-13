package com.wenjie.studio.material;

import com.wenjie.studio.knowledge.KnowledgeDtos;

import java.util.List;

public final class MaterialKnowledgePreviewDtos {
    private MaterialKnowledgePreviewDtos() {}

    public enum Domain { MATERIAL_EXCERPT, KNOWLEDGE }
    public record Node(String itemId, String versionId, int versionNumber, KnowledgeDtos.Kind kind,
                       String title, KnowledgeDtos.Status status, boolean current) {}
    public record Edge(String id, String parentId, int depth, Domain domain, String sourceItemId,
                       String sourceVersionId, String taskId, String targetItemId, String targetVersionId) {}
    public record Preview(String coverage, MaterialReferencesDtos.Source source, MaterialLifecycleDtos.State lifecycle,
                          List<Node> nodes, List<Edge> edges) {}
}
