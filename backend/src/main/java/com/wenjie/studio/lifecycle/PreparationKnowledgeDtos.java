package com.wenjie.studio.lifecycle;

import com.wenjie.studio.prepare.PreparationDtos;
import java.time.Instant;
import java.util.List;

public final class PreparationKnowledgeDtos {
    private PreparationKnowledgeDtos() {}
    public enum Context { PREPARATION, KNOWLEDGE }
    public enum ChangeKind { CONTENT_SAVED, STATUS_SET, FACT_DECIDED, EXPRESSION_BASIS_CONFIRMED }
    public enum Domain { PREPARATION, KNOWLEDGE }
    public enum DependencyKind { DIRECT, PROJECT_FACT, FACT_EXPRESSION, PROJECT_EXPRESSION, KNOWLEDGE }
    public record Observation(String versionId, int versionNumber, long sourceGeneration, long revision,
                              PreparationDtos.Status status, PreparationDtos.Verification verification) {}
    public record Root(PreparationDtos.Kind kind, String itemId, String title, String projectId, ChangeKind changeKind,
                       Observation before, Observation after) {}
    public record Fact(String itemId, String title, String versionId, int versionNumber) {}
    public record Source(Domain domain, String kind, String itemId, String title, String versionId,
                         int versionNumber, DependencyKind dependencyKind, Fact viaFact) {}
    public record Impact(String id, long revision, String eventId, Root root, Source source, String taskId,
                         String targetItemId, String targetTitle, String targetVersionId, int targetVersionNumber,
                         int depth, String parentImpactId, Instant createdAt,
                         KnowledgeRevalidationDtos.Resolution resolution) {}
    public record Page(Context context, String itemId, KnowledgeRevalidationDtos.State state,
                       List<Impact> items, String nextCursor) {}
}
