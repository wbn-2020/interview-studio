package com.wenjie.studio.review;

/** 来源正文只在本域内部完成定位与摘要，不作为普通读取响应。 */
public final class StandardReviewSourceRows {
    private StandardReviewSourceRows() {}

    public record Question(String id, String ownerId, String status, String currentVersionId,
                           String versionId, String promptJson, String referenceAnswer, String checkpointsJson) {}
    public record Knowledge(String id, String ownerId, String kind, String status, String currentVersionId,
                            String versionId, String body, String currentBody) {}
    public record Attachment(String id, String ownerId, String status, String sha256) {}
}
