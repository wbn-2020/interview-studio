package com.wenjie.studio.review;

import java.time.Instant;

public final class RecollectionRows {
    private RecollectionRows() {}

    public record RecollectionRow(String id, String ownerId, String status, long revision,
                                  String currentVersionId, int versionNumber, Instant createdAt, Instant updatedAt) {}
    public record VersionRow(String id, String ownerId, String recollectionId, int versionNumber,
                             String title, String baselineKind, String contentJson, String interviewId,
                             Long interviewRevision, String interviewJson, Instant createdAt) {}
    public record SummaryRow(String id, String title, String baselineKind, String status, long revision,
                             String versionId, int versionNumber, String interviewId, String interviewTitle,
                             Instant updatedAt) {}
    public record QuestionRow(String ownerId, String recollectionId, String recollectionVersionId,
                              String questionId, String questionVersionId, Instant createdAt) {}
}
