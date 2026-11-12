package com.wenjie.studio.today;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.wenjie.studio.today.TodayDtos.*;

/** 安排偏好只补充显示与建议，不保存另一份来源状态或完成证据。 */
public final class TodayPlanDtos {
    private TodayPlanDtos() {}

    public enum Visibility { VISIBLE, PAUSED }
    public enum PlacementChangeKind { CREATED, UPDATED, ORDERED, SUGGESTION_APPLIED, INTERVIEW_RESCHEDULED }
    public enum BudgetChangeKind { CREATED, UPDATED }
    public enum SuggestionReason {
        CONTINUE_WORK, SOURCE_DUE, PLACEMENT_DUE, UNSCHEDULED_WORK,
        SOURCE_ARCHIVED, SOURCE_TERMINAL, NO_PENDING_WORK, SOURCE_PAUSED,
        SOURCE_DATE_REQUIRED, SOURCE_DATE_IN_FUTURE, MEMORY_SETTINGS_REQUIRED,
        MEMORY_BLOCKED, MEMORY_NOT_DUE, MEMORY_NOT_RECOMMENDED, MEMORY_BUDGET_CONFIRMATION,
        PLACEMENT_PAUSED, SUPPRESSED_TODAY, DATE_LOCKED, ALREADY_STARTED,
        ESTIMATE_REQUIRED, DAY_BUDGET_REQUIRED, OVER_MINUTES, INTERVIEW_REFERENCE_ONLY
    }
    public enum SuggestionLimitation {
        EXPLICIT_CANDIDATES_ONLY, NO_TIME_RESERVATION, ESTIMATES_ARE_NOT_ACTUAL_DURATION,
        MEMORY_BUDGET_SEPARATE, INTERVIEW_TIME_NOT_DEDUCTED
    }

    public record DayRef(LocalDate localDate, String zoneId) {}
    public record PlacementContent(LocalDate plannedDate, String planZoneId, Integer estimatedMinutes,
                                   Integer priority, Integer orderValue, Boolean dateLocked,
                                   Visibility visibility, DayRef suppressedOn) {}
    public record Placement(String id, SourceRef source, long revision, LocalDate plannedDate,
                            String planZoneId, Integer estimatedMinutes, int priority, int orderValue,
                            boolean dateLocked, Visibility visibility, DayRef suppressedOn,
                            Instant createdAt, Instant updatedAt) {}
    public record CreatePlacement(SourceRef source, SourceStamp expectedSource,
                                  PlacementContent content, String reason) {}
    public record SavePlacement(Long expectedRevision, SourceStamp expectedSource,
                                PlacementContent content, String reason) {}
    public record PlacementLookup(SourceRef source, Placement placement) {}
    public record PlacementDirectoryQuery(String zoneId, SourceKind kind, Visibility visibility,
                                         String cursor, Integer limit) {}
    public record PlacementEntry(Placement placement, String title, SourceStamp currentSource,
                                 SourceStatus sourceStatus, Continuation continuation) {}
    public record PlacementPage(DayContext context, List<PlacementEntry> items, String nextCursor) {}
    public record PlacementHistory(PlacementChangeKind change, Placement snapshot,
                                   SourceStamp sourceStamp, String reason, Instant createdAt) {}
    public record PlacementHistoryPage(List<PlacementHistory> items, Long nextBefore) {}
    public record OrderItem(String placementId, Long expectedRevision, SourceStamp expectedSource,
                            Integer orderValue) {}
    public record OrderPlacements(List<OrderItem> items) {}
    public record OrderedPlacements(List<Placement> placements) {}
    public record ActionDateMove(String placementId, long expectedRevision,
                                 SourceStamp expectedSource, LocalDate plannedDate) {}

    public record DayBudget(LocalDate localDate, String zoneId, long revision, int availableMinutes,
                            Instant createdAt, Instant updatedAt) {}
    public record CreateBudget(LocalDate localDate, String zoneId, Integer availableMinutes) {}
    public record SaveBudget(String zoneId, Long expectedRevision, Integer availableMinutes) {}
    public record BudgetLookup(DayBudget budget) {}
    public record BudgetHistory(BudgetChangeKind change, DayBudget snapshot, Instant createdAt) {}
    public record BudgetHistoryPage(List<BudgetHistory> items, Long nextBefore) {}

    public record SuggestionCandidate(SourceRef source, SourceStamp expectedSource,
                                      Long expectedPlacementRevision, Integer estimatedMinutes) {}
    public record SuggestionRequest(LocalDate localDate, String zoneId, Long expectedBudgetRevision,
                                    List<SuggestionCandidate> items) {}
    public record SuggestionItem(SourceRef source, SourceStamp sourceStamp, Long placementRevision,
                                 Integer estimatedMinutes, boolean recommended,
                                 List<SuggestionReason> reasonCodes, PlacementContent proposedContent) {}
    public record SuggestionRemaining(List<SourceRef> sources, int count) {}
    public record SuggestionPreview(DayContext context, DayBudget budget, List<SuggestionItem> items,
                                    int selectedKnownMinutes, int unknownEstimateCount,
                                    SuggestionRemaining remaining, String previewHash,
                                    List<SuggestionLimitation> limitations) {}
    public record ApplySuggestion(SuggestionRequest request, String expectedPreviewHash,
                                  List<SourceRef> selectedSources, Boolean confirmed) {}
    public record AppliedSuggestion(List<Placement> placements, DayContext context) {}
}
