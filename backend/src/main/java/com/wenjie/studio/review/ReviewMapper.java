package com.wenjie.studio.review;

import com.wenjie.studio.review.ReviewRows.ActionRow;
import com.wenjie.studio.review.ReviewRows.ActionSummaryRow;
import com.wenjie.studio.review.ReviewRows.DecisionRow;
import com.wenjie.studio.review.ReviewRows.FeedbackRevisionRow;
import com.wenjie.studio.review.ReviewRows.FeedbackRow;
import com.wenjie.studio.review.ReviewRows.HistoryRow;
import com.wenjie.studio.review.ReviewRows.SourceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReviewMapper {
    FeedbackRow feedback(@Param("owner") String owner, @Param("id") String id);
    List<FeedbackRow> feedbacks(@Param("owner") String owner, @Param("sessionId") String sessionId,
                               @Param("questionId") String questionId, @Param("answerId") String answerId,
                               @Param("cursor") String cursor, @Param("limit") int limit);
    FeedbackRevisionRow feedbackRevision(@Param("owner") String owner, @Param("id") String id,
                                         @Param("revision") int revision);
    List<FeedbackRevisionRow> feedbackHistory(@Param("owner") String owner, @Param("id") String id,
                                              @Param("before") Integer before, @Param("limit") int limit);
    List<DecisionRow> decisions(@Param("owner") String owner, @Param("id") String id,
                               @Param("feedbackRevision") int feedbackRevision);
    void insertFeedback(@Param("feedback") FeedbackRow feedback);
    int updateFeedback(@Param("feedback") FeedbackRow feedback, @Param("expected") int expected);
    void insertFeedbackRevision(@Param("revision") FeedbackRevisionRow revision);
    void insertDecision(@Param("decision") DecisionRow decision);

    ActionRow action(@Param("owner") String owner, @Param("id") String id);
    List<ActionSummaryRow> actions(@Param("owner") String owner, @Param("status") String status,
                                   @Param("cursor") String cursor, @Param("limit") int limit);
    List<SourceRow> sources(@Param("owner") String owner, @Param("actionId") String actionId);
    SourceRow source(@Param("owner") String owner, @Param("actionId") String actionId, @Param("id") String id);
    List<HistoryRow> actionHistory(@Param("owner") String owner, @Param("id") String id,
                                  @Param("before") Long before, @Param("limit") int limit);
    void insertAction(@Param("action") ActionRow action);
    int updateAction(@Param("action") ActionRow action, @Param("expected") long expected);
    void insertSource(@Param("source") SourceRow source);
    int updateSource(@Param("source") SourceRow source);
    int moveSources(@Param("owner") String owner, @Param("sourceActionId") String sourceActionId,
                    @Param("targetActionId") String targetActionId);
    void insertHistory(@Param("history") HistoryRow history);
}
