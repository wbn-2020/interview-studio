package com.wenjie.studio.today;

import java.time.Instant;
import java.time.LocalDate;

import static com.wenjie.studio.today.TodayDtos.*;
import static com.wenjie.studio.today.TodayPlanDtos.*;

/** 读取侧直接复用 Mapper 与行投影，不依赖包含命令的 TodayPlanService。 */
public final class TodayPlanRows {
    private TodayPlanRows() {}

    public record PlacementRow(String ownerId, String id, String sourceKind, String sourceId, long revision,
                               LocalDate plannedDate, String planZoneId, Integer estimatedMinutes,
                               int priority, int orderValue, boolean dateLocked, String visibility,
                               LocalDate suppressedDate, String suppressedZoneId,
                               Instant createdAt, Instant updatedAt) {}
    public record BudgetRow(String ownerId, LocalDate localDate, String zoneId, long revision,
                            int availableMinutes, Instant createdAt, Instant updatedAt) {}
    public record HistoryRow(long revision, String snapshotJson) {}
    public record StartedFacts(Instant trainingStartedAt, boolean actionStarted, Instant sourceUpdatedAt) {}

    public static Placement placement(PlacementRow row) {
        if (row == null) return null;
        if ((row.suppressedDate() == null) != (row.suppressedZoneId() == null)) {
            throw new IllegalStateException("安排偏好抑制日身份不完整");
        }
        return new Placement(row.id(), new SourceRef(SourceKind.valueOf(row.sourceKind()), row.sourceId()),
                row.revision(), row.plannedDate(), row.planZoneId(), row.estimatedMinutes(), row.priority(),
                row.orderValue(), row.dateLocked(), Visibility.valueOf(row.visibility()),
                row.suppressedDate() == null ? null : new DayRef(row.suppressedDate(), row.suppressedZoneId()),
                row.createdAt(), row.updatedAt());
    }

    public static PlacementRow placementRow(String owner, Placement value) {
        return new PlacementRow(owner, value.id(), value.source().kind().name(), value.source().id(), value.revision(),
                value.plannedDate(), value.planZoneId(), value.estimatedMinutes(), value.priority(), value.orderValue(),
                value.dateLocked(), value.visibility().name(), value.suppressedOn() == null ? null : value.suppressedOn().localDate(),
                value.suppressedOn() == null ? null : value.suppressedOn().zoneId(), value.createdAt(), value.updatedAt());
    }

    public static DayBudget budget(BudgetRow row) {
        return row == null ? null : new DayBudget(row.localDate(), row.zoneId(), row.revision(), row.availableMinutes(), row.createdAt(), row.updatedAt());
    }
}
