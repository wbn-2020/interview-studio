package com.wenjie.studio.knowledge;

import java.time.Instant;
import java.util.List;

public final class KnowledgeMergeDtos {
    private KnowledgeMergeDtos() {}

    public record PreviewRequest(String sourceId, String targetId) {}
    public record Side(KnowledgeDtos.ItemDetail item, OrganizationDtos.Organization organization,
                       KnowledgeDtos.Position position, List<KnowledgeDtos.Relation> relations) {}
    public record ImpactCount(String kind, long count) {}
    public record Preview(String digest, long layoutRevision, Side source, Side target,
                          List<String> suggestedAliases, List<String> suggestedTags,
                          List<String> suggestedTopicIds, List<String> suggestedNoteIds,
                          List<ImpactCount> impacts) {}
    public record Commit(String sourceId, String targetId, String expectedDigest,
                         List<String> aliases, List<String> tags, List<String> topicIds,
                         List<String> noteIds, String mainNoteId, Boolean favorite,
                         Boolean useSourcePosition, Boolean confirmed) {}
    public record MergeRecord(String id, String sourceId, String targetId, String sourceVersionId,
                              String targetVersionId, long targetRevisionAfter, Instant createdAt) {}
    public record Result(MergeRecord merge, KnowledgeDtos.ItemDetail target,
                         OrganizationDtos.Organization organization) {}
    public record Resolution(String requestedId, String resolvedId, List<MergeRecord> path) {}
}
