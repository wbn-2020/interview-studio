package com.wenjie.studio.schedule;

import java.time.Instant;

import static com.wenjie.studio.schedule.SchedulePreparationDtos.*;

public final class SchedulePreparationRows {
    private SchedulePreparationRows() {}

    public record LinkRow(String id, String ownerId, String eventId, long revision, String sourceKind,
                          String sourceId, String sourceVersionId, String status, Instant createdAt, Instant updatedAt) {}
    public record EventRow(String id, String ownerId, String targetId, String targetVersionId, String status,
                           long revision, Instant updatedAt) {}
    public record CollectionRow(String id, String ownerId, String kind, String status, long revision,
                                String currentVersionId, String versionId, int versionNumber, String title) {}
    public record SourceIdentity(String kind, String sourceId) {}
    public record MetadataRow(String id, String ownerId, String eventId, long revision, String sourceKind,
                               String sourceId, String sourceVersionId, String status, Instant createdAt, Instant updatedAt,
                               long eventRevision, String eventStatus, String eventTitle, Instant eventStartAt, String eventZoneId) {
        public LinkRow row() { return new LinkRow(id, ownerId, eventId, revision, sourceKind, sourceId, sourceVersionId, status, createdAt, updatedAt); }
    }
    public record HistoryRow(long revision, String changeKind, long eventRevision, String snapshotJson,
                              String sourceStampJson, Instant createdAt) {}

    public static PreparationLink link(LinkRow row) {
        return new PreparationLink(row.id(), row.eventId(), row.revision(), LinkKind.valueOf(row.sourceKind()),
                row.sourceId(), row.sourceVersionId(), LinkStatus.valueOf(row.status()), row.createdAt(), row.updatedAt());
    }
    public static EventReference event(EventRow row) {
        return new EventReference(row.id(), row.revision(), ScheduleDtos.Status.valueOf(row.status()), row.targetId(), row.targetVersionId());
    }
}
