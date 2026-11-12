package com.wenjie.studio.bank;

import java.time.Instant;
import java.util.List;

public final class CollectionDtos {
    private CollectionDtos() {}

    public enum CollectionKind { LIST, ROUTE }
    public enum Status { ACTIVE, ARCHIVED }
    public enum ReferenceState { CURRENT, SUPERSEDED, ARCHIVED }

    public record Step(String id, String questionId, String questionVersionId, String purpose,
                       List<String> dependsOn, String checkpoint) {}
    public record CollectionInput(String title, String purpose, List<Step> steps) {}
    public record CreateCollection(CollectionKind kind, CollectionInput content) {}
    public record SaveCollection(Long expectedRevision, CollectionInput content) {}
    public record ChangeStatus(Long expectedRevision) {}

    public record CollectionVersion(String id, int number, Instant createdAt, CollectionInput content) {}
    public record VersionSummary(String id, int number, String title, Instant createdAt) {}
    public record VersionPage(List<VersionSummary> items, Integer nextCursor) {}
    public record QuestionReference(String stepId, String questionId, String versionId, String title,
                                    int number, ReferenceState state) {}
    public record Collection(String id, CollectionKind kind, Status status, long revision,
                              Instant createdAt, Instant updatedAt, CollectionVersion currentVersion,
                              List<QuestionReference> questionReferences) {}
    public record CollectionSummary(String id, CollectionKind kind, String title, Status status, long revision,
                                     String versionId, int versionNumber, int stepCount, Instant updatedAt) {}
    public record CollectionPage(List<CollectionSummary> items, String nextCursor) {}
}
