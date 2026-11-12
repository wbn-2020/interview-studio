package com.wenjie.studio.schedule;

import java.time.Instant;
import java.util.List;

public final class ScheduleDtos {
    private ScheduleDtos() {}

    public enum Status { PLANNED, COMPLETED, CANCELLED }
    public enum Timing { UPCOMING, DUE, IN_PROGRESS, PAST, CLOSED }
    public enum Action { CREATED, UPDATED, RESCHEDULED, CANCELLED, COMPLETED, ACKNOWLEDGED }
    public enum MaterialKind { PROJECT, FACT, EXPRESSION, KNOWLEDGE }
    public enum ReferenceState { CURRENT, SUPERSEDED, WITHDRAWN, ARCHIVED, UNVERIFIED, REVIEW_REQUIRED }
    public enum ResolutionState { UNIQUE, OVERLAP, GAP }

    public record TimeInput(String localStart, String zoneId, String offset, Integer durationMinutes) {}
    public record ResolveTime(String localStart, String zoneId) {}
    public record TimeOption(String offset, Instant startAt) {}
    public record TimeResolution(ResolutionState state, List<TimeOption> options) {}
    public record ResolvedTime(String localStart, String zoneId, String offset, Instant startAt,
                               Instant endAt, int durationMinutes) {}
    public record MaterialLink(MaterialKind kind, String itemId, String versionId) {}
    public record MaterialReference(MaterialKind kind, String itemId, String versionId, String title,
                                    int number, ReferenceState state) {}
    public record EventInput(String title, String targetId, String round, String location, String meetingUrl,
                             String notes, TimeInput time, Integer remindBeforeMinutes,
                             List<MaterialLink> materials) {}
    public record EditEvent(Long expectedRevision, String title, String targetId, String round,
                            String location, String meetingUrl, String notes, TimeInput time,
                            Integer remindBeforeMinutes, List<MaterialLink> materials) {}
    public record PreviewReschedule(Long expectedRevision, TimeInput time, Integer remindBeforeMinutes) {}
    public record Reschedule(Long expectedRevision, TimeInput time, Integer remindBeforeMinutes, String reason) {}
    public record DecideEvent(Long expectedRevision, String reason) {}
    public record Acknowledge(Long expectedRevision) {}

    public record InterviewEvent(String id, String targetId, String targetVersionId, ReferenceState targetState,
                                  String title, String round, String location, String meetingUrl, String notes,
                                  ResolvedTime time, List<MaterialLink> materials,
                                  List<MaterialReference> materialReferences, int remindBeforeMinutes,
                                  Instant remindAt, Instant reminderAcknowledgedAt, Status status, Timing timing,
                                  long revision, Instant createdAt, Instant updatedAt) {}
    public record EventPage(List<InterviewEvent> items, String nextCursor, Instant asOf) {}
    public record ReschedulePreview(ResolvedTime from, ResolvedTime to, Instant remindAt,
                                     boolean relatedTaskChangesAvailable) {}
    public record HistorySummary(long revision, Action action, String title, Instant startAt, Status status,
                                  String reason, Instant createdAt) {}
    public record HistoryPage(List<HistorySummary> items, Long nextCursor) {}
}
