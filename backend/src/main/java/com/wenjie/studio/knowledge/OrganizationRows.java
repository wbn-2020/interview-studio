package com.wenjie.studio.knowledge;

import java.time.Instant;

public final class OrganizationRows {
    private OrganizationRows() {}
    public record OrganizationRow(long revision, String aliasesJson, String tagsJson, boolean favorite,
                                  String mainNoteId, Instant visitedAt) {}
    public record Association(String id, String noteId) {}
    public record LibraryItem(String id, String kind, String title, String status, long revision,
                              int versionNumber, Instant updatedAt, Instant visitedAt) {}
    public record UnscannedVersion(String id, String itemId, int number, String body) {}
}
