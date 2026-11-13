package com.wenjie.studio.knowledge;

import java.time.Instant;
import java.util.List;

public final class OrganizationDtos {
    private OrganizationDtos() {}

    public enum ConnectionKind { ASSOCIATION, SEMANTIC, OUTGOING, BACKLINK }
    public enum Collection { ALL, FAVORITES, RECENT, UNCATEGORIZED, BROKEN_LINKS }
    public record Organization(long revision, List<String> aliases, List<String> tags, boolean favorite,
                               List<String> noteIds, String mainNoteId, Instant visitedAt) {}
    public record OrganizationRequest(Long expectedRevision, List<String> aliases, List<String> tags,
                                      Boolean favorite, List<String> noteIds, String mainNoteId) {}
    public record EmptyRequest() {}
    public record VisitResult(Instant visitedAt) {}
    public record ReferencesRebuildResult(int processedVersions, boolean hasMore, int unresolvedReferences) {}
    public record Connection(String id, ConnectionKind kind, String itemId, String title, String status,
                             String relationType, String direction, String sourceVersionId,
                             Integer sourceVersionNumber, Integer lineStart, Integer lineEnd, String excerpt) {}
    public record ConnectionPage(List<Connection> items, String nextCursor) {}
}
