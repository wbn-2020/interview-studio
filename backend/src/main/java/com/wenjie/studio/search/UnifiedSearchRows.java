package com.wenjie.studio.search;

public final class UnifiedSearchRows {
    private UnifiedSearchRows() { }

    public record VersionRow(String id, String versionId, Integer versionNumber, String kind, String title,
                             String status, String currentVersionId, String verification, String projectStatus,
                             String contentJson, String body) { }

    public record ExcerptRow(String id, String attachmentId, String fileName, String origin, String locator,
                             String text, String runId, String segmentId, Integer startOffset, Integer endOffset,
                             String segmentLocator, String segmentText) { }
}
