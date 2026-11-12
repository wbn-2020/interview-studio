package com.wenjie.studio.planning;

import java.time.Instant;
import java.time.LocalDate;

public final class PlanningRows {
    private PlanningRows() {}

    public record TaskRow(String id, String ownerId, long revision, String title, String detail,
                          int minutes, LocalDate plannedDate, String zoneId, String status,
                          String targetItemId, String targetVersionId, Integer targetVersionNumber,
                          String targetTitle, String sourceAiTaskId, Integer sourceStep,
                          Instant startedAt, Instant completedAt, Instant createdAt, Instant updatedAt) {}
    public record HistoryRow(String ownerId, String taskId, long revision, String action,
                             String snapshotJson, Instant createdAt) {}
}
