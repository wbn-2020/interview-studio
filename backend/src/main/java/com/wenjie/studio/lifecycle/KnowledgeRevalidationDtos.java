package com.wenjie.studio.lifecycle;

import java.time.Instant;
import java.util.List;

public final class KnowledgeRevalidationDtos {
    private KnowledgeRevalidationDtos() {}

    public enum Direction { SOURCE, TARGET }
    public enum State { PENDING, ALL }
    public enum ChangeKind { BODY_CHANGED, ARCHIVED }
    public enum Choice { KEEP_CURRENT, PUBLISHED_UPDATE, ARCHIVED }

    public record Resolve(Long expectedRevision, Long expectedTargetRevision, String expectedTargetVersionId,
                          Choice choice, String reason, Boolean confirmed) {}
    public record Resolution(Choice choice, String reason, String targetVersionId, long targetRevision, Instant decidedAt) {}
    public record Impact(String id, long revision, String eventId, String rootItemId, String rootTitle,
                         String rootPreviousVersionId, String rootCurrentVersionId, ChangeKind changeKind,
                         String sourceVersionId, String taskId, String targetItemId, String targetTitle,
                         String targetVersionId, int targetVersionNumber, Instant createdAt, Resolution resolution) {}
    public record Page(String itemId, Direction direction, State state, List<Impact> items, String nextCursor) {}
    public record Lineage(Impact impact, String sourceItemId, String sourceTitle, int sourceVersionNumber,
                          int depth, String parentImpactId) {}
}
