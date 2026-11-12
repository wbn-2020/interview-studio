package com.wenjie.studio.review;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TrainingReportMapper {
    List<TrainingReportRows.QuestionCountRow> questionCounts(@Param("owner") String owner,
                                                           @Param("sessionId") String sessionId);
    String questionTitle(@Param("owner") String owner, @Param("sessionId") String sessionId,
                         @Param("questionId") String questionId);
    List<TrainingReportRows.SubmissionRow> submissions(@Param("owner") String owner,
                                                      @Param("sessionId") String sessionId,
                                                      @Param("questionId") String questionId,
                                                      @Param("before") Integer before, @Param("limit") int limit);
    List<TrainingReportRows.TaskRow> tasks(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                          @Param("questionId") String questionId,
                                          @Param("answerIds") List<String> answerIds);
}
