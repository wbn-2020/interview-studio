package com.wenjie.studio.knowledge;

import java.time.Instant;
import java.util.List;

public final class KnowledgeDtos {
    private KnowledgeDtos() {}

    public enum Kind { NOTE, CONCEPT }
    public enum Status { ACTIVE, ARCHIVED }
    public enum RelationType { PRECEDES, PART_OF, APPLIED_IN, RELATED, CONTRASTS }

    public record CreateItem(Kind kind, String title, String body, List<String> topicIds) {}
    public record SaveDraft(Long expectedRevision, String title, String body) {}
    public record Publish(Long expectedRevision, Integer expectedVersionNumber) {}
    public record SetTopics(Long expectedRevision, List<String> topicIds) {}
    public record ChangeStatus(Long expectedRevision) {}
    public record CreateTopic(String name, String color) {}
    public record CreateRelation(String sourceId, String targetId, RelationType type) {}
    public record Position(String id, Double x, Double y) {}
    public record SaveLayout(Long expectedRevision, List<Position> positions) {}
    public record RemoveRelation() {}

    public record Draft(String title, String body, long revision, Instant savedAt) {}
    public record Version(String id, int number, String title, String body, Instant createdAt) {}
    public record VersionSummary(String id, int number, String title, Instant createdAt) {}
    public record ItemSummary(String id, Kind kind, String title, Status status, long revision,
                              int versionNumber, Instant updatedAt, List<String> topicIds) {}
    public record ItemDetail(String id, Kind kind, String title, Status status, long revision,
                             int versionNumber, Instant updatedAt, List<String> topicIds,
                             String currentVersionId, Draft draft, Version published) {}
    public record ItemPage(List<ItemSummary> items, String nextCursor) {}
    public record VersionPage(List<VersionSummary> items, Integer nextCursor) {}
    public record Topic(String id, String name, String color) {}
    public record TopicList(List<Topic> items) {}
    public record Relation(String id, String sourceId, String targetId, RelationType type) {}
    public record Removed(boolean removed) {}
    public record GraphNode(String id, String title, List<String> topicIds, double x, double y) {}
    public record Graph(List<GraphNode> nodes, List<Relation> edges, List<Topic> topics,
                        long layoutRevision, boolean truncated) {}
    public record LayoutSaved(long layoutRevision) {}
}
