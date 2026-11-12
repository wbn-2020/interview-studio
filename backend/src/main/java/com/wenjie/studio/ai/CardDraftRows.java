package com.wenjie.studio.ai;

import java.time.Instant;

public final class CardDraftRows {
    private CardDraftRows() {}

    public record SourceRow(String ownerId, String taskId, String itemId, String versionId,
                            int number, String title, String kind, int ordinal) {}
    public record DecisionRow(String ownerId, String taskId, String status, String targetCardId,
                              String targetVersionId, Instant createdAt) {}
    public record OriginRow(String taskId, String targetVersionId, int targetVersionNumber,
                            Instant adoptedAt, String purpose, String manifestJson, String manifestHash) {}
    public record OriginSourceRow(String taskId, String itemId, String versionId, int number,
                                  String title, String kind, int ordinal, String state) {}
}
