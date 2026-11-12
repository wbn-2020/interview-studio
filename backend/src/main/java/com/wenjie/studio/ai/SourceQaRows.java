package com.wenjie.studio.ai;

public final class SourceQaRows {
    private SourceQaRows() { }

    public record Stamp(String ownerId, String id, String status, String currentVersionId, Long revision) { }
    public record Fact(String responsibility) { }
    public record Material(String attachmentId, String origin, String sha256, String runId, String segmentId,
                           String locator, Integer startOffset, Integer endOffset) { }
    public record AnswerIdentity(String ownerId, String sessionId, String questionId, String answerId) { }
    public record Binding(String ownerId, String taskId, int ordinal, String domain, String sourceId,
                          String versionId, Integer feedbackRevision, String feedbackId, String reportHash,
                          String sessionId, String questionId, String answerId, Integer decisionRevision,
                          String decisionChoice, int fragmentCount, String snapshotHash) { }
}
