package com.wenjie.studio.search;

import java.util.List;

public final class SearchRows {
    private SearchRows() { }

    public record Projection(String ownerId, String activeGeneration, String buildGeneration,
                             String phase, String scanCursor, long boundaryEventId, String lastErrorCode) { }
    public record Event(long id, String itemId) { }
    public record TopicLink(String itemId, String topicId) { }
    public record Content(String id, String ownerId, String kind, String status, long metadataRevision,
                          String versionId, int versionNumber, String title, String body, String source,
                          long organizationRevision, String aliasesJson, String tagsJson) { }
    public record DocumentData(Content content, List<String> aliases, List<String> tags, List<String> topicIds) { }
    public record Candidate(String id, String versionId, long metadataRevision, long organizationRevision) { }
    public record Window(List<Candidate> candidates, boolean hasMore) { }
}
