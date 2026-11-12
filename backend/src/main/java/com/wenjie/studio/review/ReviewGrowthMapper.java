package com.wenjie.studio.review;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ReviewGrowthMapper {
    List<ReviewGrowthRows.Candidate> candidates(@Param("owner") String owner, @Param("kind") String kind,
            @Param("start") Instant start, @Param("end") Instant end, @Param("afterAt") Instant afterAt,
            @Param("afterKey") String afterKey, @Param("limit") int limit);
    List<ReviewGrowthRows.Candidate> activityCandidates(@Param("owner") String owner,
            @Param("start") Instant start, @Param("end") Instant end, @Param("afterAt") Instant afterAt,
            @Param("afterKey") String afterKey, @Param("limit") int limit);
    ReviewGrowthRows.Feedback feedback(@Param("owner") String owner, @Param("id") String id,
                                      @Param("revision") long revision);
    ReviewGrowthRows.Decision latestDecision(@Param("owner") String owner, @Param("id") String id,
                                           @Param("revision") int revision);
    ReviewGrowthRows.Recollection recollection(@Param("owner") String owner, @Param("id") String id,
                                              @Param("versionId") String versionId);
    ReviewGrowthRows.Judgment judgment(@Param("owner") String owner, @Param("id") String id);
    ReviewGrowthRows.Judgment latestJudgment(@Param("owner") String owner, @Param("sourceId") String sourceId);
    ReviewGrowthRows.Comparison comparison(@Param("owner") String owner, @Param("id") String id);
    RepracticeRows.RepracticeRow repractice(@Param("owner") String owner, @Param("id") String id);
    ReviewGrowthRows.Snapshot snapshot(@Param("owner") String owner, @Param("repracticeId") String repracticeId,
                                     @Param("sourceId") String sourceId);
    ReviewGrowthRows.Source source(@Param("owner") String owner, @Param("id") String id);
    List<String> sourceIds(@Param("owner") String owner, @Param("after") String after, @Param("limit") int limit);
    ReviewGrowthRows.ReplayStart replayStart(@Param("owner") String owner, @Param("id") String id);
    String answerSessionStatus(@Param("owner") String owner, @Param("sessionId") String sessionId,
                               @Param("questionId") String questionId, @Param("answerId") String answerId);
}
