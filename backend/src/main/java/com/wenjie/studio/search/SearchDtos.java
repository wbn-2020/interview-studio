package com.wenjie.studio.search;

import java.util.List;

public final class SearchDtos {
    private SearchDtos() { }

    public enum State { READY, BUILDING, DEGRADED, DISABLED }

    public record Hit(String id, String kind, String title, String status, String versionId,
                      int versionNumber, String excerpt, List<String> matchedFields,
                      List<String> topicIds, List<String> tags) { }
    public record Results(List<Hit> items, boolean hasMore, State syncStatus, boolean scanLimited) { }
    public record Status(State state, String generation, long pendingEvents, String lastErrorCode) { }
    public record RebuildRequest() { }
    public record RebuildResult(boolean accepted, State state) { }
    public record Criteria(String query, String kind, String status, String topicId, String tag,
                           String source, int limit, int offset) { }
}
