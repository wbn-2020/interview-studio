package com.wenjie.studio.board;

import com.wenjie.studio.knowledge.KnowledgeDtos.Relation;
import com.wenjie.studio.knowledge.KnowledgeDtos.RelationType;
import java.time.Instant;
import java.util.List;

public final class BoardDtos {
    private BoardDtos() {}
    public enum Kind { MIND_MAP, BOARD }
    public enum NodeType { REFERENCE, TEXT }
    public enum ReferenceKind { KNOWLEDGE, QUESTION, ATTACHMENT }
    public record Viewport(Double x, Double y, Double zoom) {}
    public record Node(String id, NodeType type, String itemId, String text, Double x, Double y,
                       String parentId, Boolean collapsed, String group, ReferenceKind referenceKind) {
        public Node {
            // 早期快照只有知识引用；缺失的域只在引用节点上按原含义读取。
            if (type == NodeType.REFERENCE && referenceKind == null) referenceKind = ReferenceKind.KNOWLEDGE;
        }
        public Node(String id, NodeType type, String itemId, String text, Double x, Double y,
                    String parentId, Boolean collapsed, String group) {
            this(id, type, itemId, text, x, y, parentId, collapsed, group, null);
        }
    }
    public record Edge(String id, String source, String target, String label) {}
    public record Reference(String itemId, String kind, String title, String status,
                            String versionId, int versionNumber, String body,
                            ReferenceKind referenceKind, Long attachmentRevision) {
        public Reference {
            if (referenceKind == null) referenceKind = ReferenceKind.KNOWLEDGE;
        }
        public Reference(String itemId, String kind, String title, String status,
                         String versionId, int versionNumber, String body) {
            this(itemId, kind, title, status, versionId, versionNumber, body, ReferenceKind.KNOWLEDGE, null);
        }
    }
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
