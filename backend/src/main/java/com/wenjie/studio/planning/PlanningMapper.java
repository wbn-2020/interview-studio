package com.wenjie.studio.planning;

import com.wenjie.studio.planning.PlanningRows.HistoryRow;
import com.wenjie.studio.planning.PlanningRows.TaskRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface PlanningMapper {
    TaskRow task(@Param("owner") String owner, @Param("id") String id);
    List<TaskRow> tasks(@Param("owner") String owner, @Param("status") String status,
                       @Param("targetId") String targetId, @Param("from") LocalDate from,
                       @Param("to") LocalDate to, @Param("cursor") String cursor, @Param("limit") int limit);
    List<HistoryRow> history(@Param("owner") String owner, @Param("id") String id,
                             @Param("before") Long before, @Param("limit") int limit);
    void insertTask(@Param("task") TaskRow task);
    int updateTask(@Param("task") TaskRow task, @Param("expected") long expected);
    void insertHistory(@Param("history") HistoryRow history);
}
