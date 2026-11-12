package com.wenjie.studio.material;

import java.time.Instant;

public final class MaterialRows {
    private MaterialRows() {}
    public record Attachment(String id, String ownerId, String fileName, String kind, long byteSize, String sha256,
                             String description, String status, long revision, String readyUploadId,
                             Instant createdAt, Instant updatedAt) {}
    public record Upload(String id, String ownerId, String attachmentId, String status, long reservedBytes,
                         long receivedBytes, String errorCode, String claimToken, Instant leaseUntil,
                         Instant createdAt, Instant startedAt, Instant finishedAt) {}
    public record Run(String id, String ownerId, String attachmentId, String attachmentSha256, String status,
                      long reservedBytes, String parserVersion, boolean truncated, String warningsJson,
                      String errorCode, int segmentCount, boolean hasPreview, String claimToken, Instant leaseUntil,
                      Instant createdAt, Instant startedAt, Instant finishedAt) {}
    public record Excerpt(String id, String ownerId, String attachmentId, String attachmentSha256,
                          String runId, String segmentId, String origin, String locator, String text,
                          Integer startOffset, Integer endOffset, Instant createdAt) {}
}
