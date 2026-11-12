package com.wenjie.studio.knowledge;

import java.time.Instant;
import java.util.List;

import com.wenjie.studio.knowledge.KnowledgeDtos.Kind;
import com.wenjie.studio.knowledge.KnowledgeDtos.RelationType;

public final class RelationSupplementDtos {
    private RelationSupplementDtos() {}

    public enum Origin { MANUAL, AI }
    public enum SourceState { CURRENT, SUPERSEDED, ARCHIVED, UNAVAILABLE }

    public record SourceLink(String itemId, String versionId) {}
    public record Create(String reason, List<SourceLink> sources, Boolean confirmed) {}
    public record Source(String itemId, String versionId, int number, String title, Kind kind, SourceState state) {}
    public record Supplement(String id, String relationId, String sourceId, String targetId, RelationType type,
                             Origin origin, String reason, List<Source> sources, String taskId,
                             String candidateId, Instant createdAt) {}
    public record Page(String relationId, boolean relationExists, List<Supplement> items, String nextCursor) {}
}
