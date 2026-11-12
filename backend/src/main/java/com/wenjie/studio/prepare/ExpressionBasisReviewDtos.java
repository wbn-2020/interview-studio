package com.wenjie.studio.prepare;

import java.time.Instant;
import java.util.List;

public final class ExpressionBasisReviewDtos {
    private ExpressionBasisReviewDtos() {}

    public enum Kind { SAVED_BASIS, CONFIRMED_BASIS }
    public record Basis(String itemId, String versionId, String title, int versionNumber,
                        String projectId, PreparationDtos.SourceCheck check) {}
    public record Record(String id, String itemId, String versionId, int versionNumber, long revision,
                         Kind kind, String reason, long itemRevision, Instant createdAt, List<Basis> basis) {}
    public record State(String itemId, String versionId, int versionNumber, long itemRevision,
                        PreparationDtos.Status status, long revision, Record latest, List<Basis> basis,
                        boolean reviewRequired, boolean canConfirm) {}
    public record Page(String itemId, List<Record> items, Long nextCursor) {}
    public record Confirm(Long expectedItemRevision, String expectedVersionId, Long expectedRevision,
                          List<PreparationDtos.SourceCheck> basisChecks, String reason, Boolean confirmed) {}
}
