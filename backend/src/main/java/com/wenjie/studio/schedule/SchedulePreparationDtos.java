package com.wenjie.studio.schedule;

import com.wenjie.studio.bank.CollectionDtos;
import com.wenjie.studio.planning.PlanningDtos;
import com.wenjie.studio.review.TodayActionProgress.TargetRef;
import com.wenjie.studio.today.TodayDtos;
import com.wenjie.studio.today.TodayPlanDtos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 准备关联保留原领域身份；本联合不扩展今日来源或改写其旧摘要。 */
public final class SchedulePreparationDtos {
    private SchedulePreparationDtos() {}

    public enum LinkKind { PREPARATION_TASK, REVIEW_ACTION, COLLECTION }
    public enum LinkStatus { ACTIVE, REMOVED }
    public enum LinkChange { CREATED, REMOVED, RESTORED, VERSION_CHANGED }
    public enum MoveReason {
        MOVABLE, STARTED, DATE_LOCKED, NO_DATE, PAUSED, TERMINAL,
        NO_PENDING_WORK, SOURCE_CHANGED, REFERENCE_ONLY, MULTIPLE_EVENTS
    }
    public enum MoveWarning { AFTER_INTERVIEW_DATE }
    public enum RescheduleLimitation {
        EXPLICIT_SELECTION_ONLY, ORIGINAL_ZONES_PRESERVED, NO_TIME_RESERVATION, COLLECTION_REFERENCE_ONLY
    }

    public record CollectionStamp(long revision, String versionId) {}
    public record PreparationSourceStamp(LinkKind kind, TodayDtos.TaskStamp task,
                                         TodayDtos.ActionStamp action, CollectionStamp collection) {}
    public record CollectionState(CollectionDtos.CollectionKind kind, CollectionDtos.Status status,
                                  String currentVersionId, int linkedVersionNumber, CollectionDtos.ReferenceState versionState) {}
    public record PreparationSourceStatus(LinkKind kind, TodayDtos.TaskState task,
                                          TodayDtos.ActionState action, CollectionState collection) {}
    public record EventReference(String id, long revision, ScheduleDtos.Status status, String targetId, String targetVersionId) {}
    public record PreparationLink(String id, String eventId, long revision, LinkKind kind,
                                  String sourceId, String sourceVersionId, LinkStatus status, Instant createdAt, Instant updatedAt) {}
    public record LinkEntry(PreparationLink link, String title, PreparationSourceStamp currentSource,
                            PreparationSourceStatus sourceStatus, List<TargetRef> targetRefs, Integer pendingCount) {}
    public record LinkPage(EventReference event, List<LinkEntry> items, String nextCursor, Instant asOf, Instant serverTime) {}
    public record CreatePreparationLink(Long expectedEventRevision, LinkKind kind, String sourceId,
                                        String sourceVersionId, PreparationSourceStamp expectedSource) {}
    public record ChangePreparationLinkStatus(Long expectedEventRevision, Long expectedRevision, LinkStatus status) {}
    public record ChangeCollectionVersion(Long expectedEventRevision, Long expectedRevision,
                                          String sourceVersionId, PreparationSourceStamp expectedSource) {}
    public record LinkHistory(LinkChange change, PreparationLink snapshot, long eventRevision,
                              PreparationSourceStamp sourceStamp, Instant createdAt) {}
    public record LinkHistoryPage(List<LinkHistory> items, Long nextBefore) {}

    public record PlanDate(LocalDate plannedDate, String zoneId) {}
    public record DateOverride(String linkId, LocalDate plannedDate) {}
    public record ReschedulePlanRequest(Long expectedEventRevision, ScheduleDtos.TimeInput time,
                                        Integer remindBeforeMinutes, List<String> selectedLinkIds, List<DateOverride> dateOverrides) {}
    public record OtherInterview(String linkId, long linkRevision, String eventId, long eventRevision,
                                  ScheduleDtos.Status status, String title, Instant startAt, String zoneId) {}
    public record RescheduleLinkCheck(PreparationLink link, PreparationSourceStamp sourceStamp,
                                      PreparationSourceStatus sourceStatus, Integer pendingCount, TodayPlanDtos.Placement placement,
                                      PlanDate from, PlanDate proposed, boolean started, List<OtherInterview> otherInterviews,
                                      List<MoveReason> reasons, List<MoveWarning> warnings) {}
    public record PreparationDateChange(String linkId, PlanDate from, PlanDate to) {}
    public record ReschedulePlanPreview(ScheduleDtos.ResolvedTime from, ScheduleDtos.ResolvedTime to,
                                        Instant remindAt, List<RescheduleLinkCheck> linkChecks, List<PreparationDateChange> changes,
                                        String previewHash, List<RescheduleLimitation> limitations) {}
    public record ApplyReschedulePlan(ReschedulePlanRequest request, String expectedPreviewHash, Boolean confirmed, String reason) {}
    public record AppliedReschedulePlan(ScheduleDtos.InterviewEvent event, List<PlanningDtos.PreparationTask> changedTasks,
                                        List<TodayPlanDtos.Placement> changedPlacements, List<RescheduleLinkCheck> linkChecks) {}

    // 只供服务间解析／保存使用，HTTP 仍接收 TimeInput，不信任客户端构造的 Instant。
    public record RescheduleBasis(EventReference event, ScheduleDtos.ResolvedTime from,
                                   ScheduleDtos.ResolvedTime to, Instant remindAt) {}
}
