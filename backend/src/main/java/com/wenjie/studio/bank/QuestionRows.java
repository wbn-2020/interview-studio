package com.wenjie.studio.bank;

import java.time.Instant;

public final class QuestionRows {
    private QuestionRows() {}

    public record QuestionRow(String id, String ownerId, String status, long revision, boolean favorite,
                              String currentVersionId, int versionNumber, Instant createdAt, Instant updatedAt) {}
    public record SummaryRow(String id, String title, String type, Integer difficulty, String status,
                             long revision, boolean favorite, String versionId, int versionNumber,
                             String standardStatus, Instant updatedAt) {}
    public record VersionRow(String id, String questionId, String ownerId, int versionNumber,
                             String title, String promptJson, String standardStatus,
                             boolean hasReference, Instant createdAt) {}
    public record ReferenceRow(String referenceAnswer, String checkpointsJson) {}
}
