package com.wenjie.studio.schedule;

import java.time.Instant;

/** 面试活动读取完成修订白名单，不加载面试备注、会议信息和材料正文。 */
public final class ScheduleGrowthRows {
    private ScheduleGrowthRows() {}

    public record Candidate(String eventId, long revision, Instant occurredAt) {}
    public record CompletionRow(String ownerId, String eventId, long revision, String action, String status,
                                Instant occurredAt, Instant startAt, String targetId, String targetVersionId,
                                String ownedEventId, String ownedTargetVersionId, String snapshotId,
                                String snapshotRevision, String snapshotRevisionType, String snapshotStatus,
                                String snapshotTargetId, String snapshotTargetVersionId, String snapshotStartAt,
                                String snapshotCreatedAt, String snapshotUpdatedAt, String previousEventId,
                                Long previousRevision, String previousStatus, Boolean metadataTypesValid) {}
}
