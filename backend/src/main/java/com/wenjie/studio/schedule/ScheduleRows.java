package com.wenjie.studio.schedule;

import java.time.Instant;
import java.time.LocalDateTime;

public final class ScheduleRows {
    private ScheduleRows() {}

    public record EventRow(String id, String ownerId, String targetId, String targetVersionId, String title,
                            String round, String location, String meetingUrl, String notes,
                            LocalDateTime localStart, String zoneId, String offset, Instant startAt,
                            Instant endAt, int durationMinutes, int remindBeforeMinutes, Instant remindAt,
                            Instant reminderAcknowledgedAt, String status, long revision,
                            Instant createdAt, Instant updatedAt) {}
    public record MaterialRow(String sourceKind, String sourceItemId, String sourceVersionId) {}
    public record HistoryRow(String ownerId, String eventId, long revision, String action, String targetId,
                              String targetVersionId, String title, Instant startAt, String status,
                              String reason, String snapshotJson, Instant createdAt) {}
}
