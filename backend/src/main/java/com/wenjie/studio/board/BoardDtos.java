package com.wenjie.studio.board;

import com.wenjie.studio.knowledge.KnowledgeDtos.Relation;
import com.wenjie.studio.knowledge.KnowledgeDtos.RelationType;
import java.time.Instant;
import java.util.List;

public final class BoardDtos {
    private BoardDtos() {}
    public enum Kind { MIND_MAP, BOARD }
    public enum NodeType { REFERENCE, TEXT }
    public record Viewport(Double x, Double y, Double zoom) {}
    public record Node(String id, NodeType type, String itemId, String text, Double x, Double y,
                       String parentId, Boolean collapsed, String group) {}
    public record Edge(String id, String source, String target, String label) {}
    public record Reference(String itemId, String kind, String title, String status,
                            String versionId, int versionNumber, String body) {}
    public record Create(Kind kind, String title) {}
    public record Save(Long expectedRevision, String title, List<Node> nodes, List<Edge> edges,
                       Viewport viewport) {}
    public record Restore(Long expectedRevision) {}
    public record ConvertText(Long expectedRevision, String title) {}
    public record ConvertEdge(Long expectedRevision, RelationType type) {}
    public record Summary(String id, Kind kind, String title, long revision, Instant updatedAt) {}
    public record Detail(String id, Kind kind, String title, long revision, Instant updatedAt,
                         List<Node> nodes, List<Edge> edges, Viewport viewport, List<Reference> references) {}
    public record Page(List<Summary> items, String nextCursor) {}
    public record HistoryEntry(long revision, String title, Instant createdAt) {}
    public record HistoryPage(List<HistoryEntry> items, Long nextCursor) {}
    public record ConvertedText(Detail board, String itemId) {}
    public record ConvertedEdge(Relation relation) {}
}
