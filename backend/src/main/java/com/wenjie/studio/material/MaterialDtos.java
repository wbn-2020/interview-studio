package com.wenjie.studio.material;

import java.time.Instant;
import java.util.List;

public final class MaterialDtos {
    private MaterialDtos() {}

    public enum Kind { PDF, DOCX, PNG, JPEG }
    public record Status(boolean enabled, long usedBytes, long quotaBytes, long maxFileBytes, boolean parserEnabled) {}
    public record CreateAttachment(String fileName, Kind kind, Long byteSize, String sha256, String description) {}
    public record ChangeDescription(Long expectedRevision, String description) {}
    public record RetryUpload(Long expectedRevision) {}
    public record EmptyRequest() {}
    public record CreateExcerpt(String segmentId, Integer startOffset, Integer endOffset, String locator, String text) {}

    public record Attachment(String id, String fileName, Kind kind, long byteSize, String sha256, String description,
                             String status, long revision, Instant createdAt, Instant updatedAt,
                             List<Upload> uploads, ParseRun latestRun) {}
    public record AttachmentSummary(String id, String fileName, Kind kind, long byteSize, String description,
                                    String status, long revision, Instant createdAt, Instant updatedAt) {}
    public record AttachmentPage(List<AttachmentSummary> items, String nextCursor) {}
    public record Upload(String id, String attachmentId, String status, long reservedBytes, long receivedBytes,
                         String errorCode, Instant createdAt, Instant startedAt, Instant finishedAt) {}
    public record ParseRun(String id, String attachmentId, String status, String parserVersion, boolean truncated,
                           List<String> warnings, String errorCode, int segmentCount, Instant createdAt,
                           Instant startedAt, Instant finishedAt, boolean hasPreview) {}
    public record RunPage(List<ParseRun> items, String nextCursor) {}
    public record Segment(String id, String attachmentId, String runId, int ordinal, String locator, String text) {}
    public record SegmentPage(List<Segment> items, Integer nextCursor) {}
    public record Excerpt(String id, String attachmentId, String runId, String segmentId, String origin,
                          String locator, String text, Integer startOffset, Integer endOffset, Instant createdAt) {}
    public record ExcerptPage(List<Excerpt> items, String nextCursor) {}
    public record AiExcerpt(String excerptId, String attachmentId, String fileName, String sha256, String origin,
                            String runId, String segmentId, String locator, String text,
                            Integer startOffset, Integer endOffset) {}
}
