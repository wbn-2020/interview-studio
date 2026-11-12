package com.wenjie.studio.today;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.wenjie.studio.today.TodayDtos.*;
import static com.wenjie.studio.today.TodayPlanDtos.*;
import static com.wenjie.studio.today.TodayPlanRows.*;

@Mapper
public interface TodayPlanMapper {
    PlacementRow placement(@Param("owner") String owner, @Param("id") String id);
    PlacementRow placementBySource(@Param("owner") String owner, @Param("kind") SourceKind kind, @Param("sourceId") String sourceId);
    List<PlacementRow> placementsBySources(@Param("owner") String owner, @Param("sources") List<SourceRef> sources);
    long placementGeneration(@Param("owner") String owner);
    List<PlacementRow> directory(@Param("owner") String owner, @Param("kind") SourceKind kind, @Param("visibility") Visibility visibility,
                                 @Param("afterUpdatedAt") Instant afterUpdatedAt, @Param("afterId") String afterId, @Param("limit") int limit);
    StartedFacts startedFacts(@Param("owner") String owner, @Param("kind") SourceKind kind, @Param("sourceId") String sourceId);
    void insertPlacement(@Param("row") PlacementRow row);
    int updatePlacement(@Param("row") PlacementRow row, @Param("expected") long expected);
    void insertPlacementHistory(@Param("owner") String owner, @Param("id") String id, @Param("revision") long revision,
                                @Param("snapshot") String snapshot, @Param("visibility") Visibility visibility, @Param("now") Instant now);
    List<HistoryRow> placementHistory(@Param("owner") String owner, @Param("id") String id, @Param("before") Long before, @Param("limit") int limit);
    void ensureGeneration(@Param("owner") String owner, @Param("now") Instant now);
    int advanceGeneration(@Param("owner") String owner, @Param("expected") long expected, @Param("now") Instant now);
    BudgetRow budget(@Param("owner") String owner, @Param("localDate") LocalDate localDate, @Param("zoneId") String zoneId);
    void insertBudget(@Param("owner") String owner, @Param("value") DayBudget value);
    int updateBudget(@Param("owner") String owner, @Param("value") DayBudget value, @Param("expected") long expected);
    void insertBudgetHistory(@Param("owner") String owner, @Param("value") DayBudget value, @Param("snapshot") String snapshot);
    List<HistoryRow> budgetHistory(@Param("owner") String owner, @Param("localDate") LocalDate localDate,
                                   @Param("zoneId") String zoneId, @Param("before") Long before, @Param("limit") int limit);
}
