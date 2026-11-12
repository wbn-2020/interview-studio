package com.wenjie.studio.review;

import com.wenjie.studio.review.RepracticeRows.ComparisonRow;
import com.wenjie.studio.review.RepracticeRows.JudgmentRow;
import com.wenjie.studio.review.RepracticeRows.RepracticeRow;
import com.wenjie.studio.review.RepracticeRows.SourceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RepracticeMapper {
    RepracticeRow repractice(@Param("owner") String owner, @Param("id") String id);
    RepracticeRow bySession(@Param("owner") String owner, @Param("sessionId") String sessionId);
    List<RepracticeRow> list(@Param("owner") String owner, @Param("cursor") String cursor, @Param("limit") int limit);
    List<SourceRow> sources(@Param("owner") String owner, @Param("repracticeId") String repracticeId);
    ComparisonRow comparison(@Param("owner") String owner, @Param("id") String id);
    List<ComparisonRow> comparisons(@Param("owner") String owner, @Param("repracticeId") String repracticeId);
    ComparisonRow comparisonByAnswer(@Param("owner") String owner, @Param("repracticeId") String repracticeId,
                                     @Param("answerId") String answerId);
    JudgmentRow latestJudgment(@Param("owner") String owner, @Param("sourceId") String sourceId);
    List<JudgmentRow> judgments(@Param("owner") String owner, @Param("sourceId") String sourceId,
                                @Param("before") Integer before, @Param("limit") int limit);
    ReviewRows.SourceRow currentSource(@Param("owner") String owner, @Param("sourceId") String sourceId);
    void insertRepractice(@Param("repractice") RepracticeRow repractice);
    void insertSource(@Param("source") SourceRow source);
    void insertComparison(@Param("comparison") ComparisonRow comparison);
    void insertJudgment(@Param("judgment") JudgmentRow judgment);
}
