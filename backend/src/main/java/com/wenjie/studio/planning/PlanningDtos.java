package com.wenjie.studio.planning;

import com.wenjie.studio.prepare.PreparationDtos.SourceCheck;

import java.time.Instant;
import java.util.List;

public final class PlanningDtos {
    private PlanningDtos() {}

    public enum Status { TODO, IN_PROGRESS, PAUSED, DONE, CANCELLED }

    public record TargetLink(String itemId, String versionId) {}
    public record TargetSnapshot(String itemId, String versionId, int number, String title) {}
    public record Content(String title, String detail, Integer minutes, String plannedDate, String zoneId) {}
    public record CreateTask(TargetLink target, Content content) {}
    public record SaveTask(Long expectedRevision, Content content) {}
    public record ChangeStatus(Long expectedRevision, Status status) {}

    public record PreparationTask(String id, long revision, Content content, Status status,
                                  TargetSnapshot target, String sourceAiTaskId, Integer sourceStep,
                                  Instant startedAt, Instant completedAt, Instant createdAt, Instant updatedAt) {}
    public record TaskDetail(PreparationTask task, SourceCheck targetCheck) {}
    public record TaskPage(List<PreparationTask> items, String nextCursor) {}
    public record Revision(String action, PreparationTask snapshot) {}
    public record HistoryPage(List<Revision> items, Long nextBefore) {}
}
