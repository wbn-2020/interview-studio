package com.wenjie.studio.knowledge;

import java.time.Instant;
import java.util.List;

public final class KnowledgePackageDtos {
    private KnowledgePackageDtos() {}

    public enum Decision { IMPORT, SKIP }
    public record SourceItem(String sourceId, String source, String title, String body, KnowledgeDtos.Kind kind,
                             Decision decision, List<String> tags, List<String> aliases, List<String> topicIds) {}
    public record SourceAttachment(String sourceId, String attachmentId, String sourceSha256) {}
    public record Plan(List<SourceItem> items, List<SourceAttachment> sourceAttachments) {}
    public record Register(String packageKey, Boolean createCopy, List<SourceItem> items,
                           List<SourceAttachment> sourceAttachments) {}
    public record Commit(Long expectedRevision, String expectedPlanSha256, List<SourceItem> items,
                         List<SourceAttachment> sourceAttachments) {}
    public record Issue(String sourceId, String code, String message) {}
    public record Preview(String packageKey, String planSha256, String existingPackageId, int importCount,
                          int skipCount, List<Issue> issues) {}
    public record Mapping(String sourceId, String sourceSha256, int sourceBytes, int sourceCharacters,
                          KnowledgeDtos.Kind kind, Decision decision, String title, List<String> tags,
                          List<String> aliases, List<String> topicIds, String itemId, String versionId) {}
    public record Detail(String id, long revision, String state, String packageKey, String planSha256,
                         boolean createCopy, Instant createdAt, Instant committedAt, List<Mapping> mappings,
                         List<SourceAttachment> sourceAttachments) {}
    public record Summary(String id, long revision, String state, String packageKey, boolean createCopy,
                          int importCount, int skipCount, Instant createdAt, Instant committedAt) {}
    public record Page(List<Summary> items, String nextCursor) {}
}
