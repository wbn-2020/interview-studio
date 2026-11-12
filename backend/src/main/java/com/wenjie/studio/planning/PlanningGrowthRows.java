package com.wenjie.studio.planning;

import java.time.Instant;

/** 只投影完成历史的身份、时间与原目标，任务正文不进入成长活动。 */
public final class PlanningGrowthRows {
    private PlanningGrowthRows() {}

    public record Candidate(String taskId, long revision, Instant occurredAt) {}
    public record CompletionRow(String ownerId, String taskId, long revision, String action, Instant occurredAt,
                                String ownedTaskId, String snapshotId, String snapshotRevision,
                                String snapshotRevisionType, String status, String completedAt,
                                String updatedAt, String createdAt, String startedAt,
                                String targetType, String targetId, String targetVersionId, String ownedTargetVersionId,
                                String previousTaskId, Long previousRevision, String previousStatus,
                                String previousCompletedAt, Boolean metadataTypesValid) {}
}
