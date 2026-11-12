package com.wenjie.studio.graph;

import com.wenjie.studio.knowledge.KnowledgeDtos.Position;
import com.wenjie.studio.knowledge.KnowledgeDtos.Relation;

import java.time.Instant;
import java.util.List;

public final class GraphDtos {
    private GraphDtos() {}

    public record Viewport(Double x, Double y, Double zoom) {}
    public record State(long layoutRevision, Viewport viewport) {}
    public record SaveState(Long expectedRevision, List<Position> positions, Viewport viewport) {}
    public record Restore(Long expectedRevision) {}
    public record Snapshot(List<Position> positions, Viewport viewport) {}
    public record HistoryEntry(long revision, Instant createdAt, int positionCount) {}
    public record HistoryPage(List<HistoryEntry> items, Long nextCursor) {}
    public record HistoryRow(long revision, String beforeJson, String afterJson) {}
    public record Path(List<String> nodeIds, List<Relation> edges, boolean found, boolean truncated) {}
}
