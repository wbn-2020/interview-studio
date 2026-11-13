package com.wenjie.studio.knowledge;

import java.time.Instant;
import java.util.List;

public final class KnowledgeTextImportDtos {
    private KnowledgeTextImportDtos() {}

    public record Create(String fileName, String source, String title, String body,
                         List<String> topicIds, Boolean createCopy) {}
    public record CreateWithTags(String fileName, String source, String title, String body,
                                 List<String> topicIds, Boolean createCopy, List<String> tags) {}
    public record Entry(String id, String fileName, String sourceSha256, int sourceBytes, int sourceCharacters,
                        String itemId, String versionId, String title, String itemStatus,
                        boolean createCopy, Instant createdAt) {}
    public record Saved(KnowledgeDtos.ItemDetail item, Entry record) {}
    public record SavedWithTags(KnowledgeDtos.ItemDetail item, Entry record,
                                OrganizationDtos.Organization organization) {}
    public record Page(List<Entry> items, String nextCursor) {}
}
