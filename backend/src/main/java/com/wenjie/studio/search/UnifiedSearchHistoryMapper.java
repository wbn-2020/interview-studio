package com.wenjie.studio.search;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UnifiedSearchHistoryMapper {
    UnifiedSearchHistoryRows.AnswerRow answer(@Param("owner") String owner, @Param("id") String id);
    UnifiedSearchHistoryRows.AnswerRow context(@Param("owner") String owner, @Param("id") String id);
    List<UnifiedSearchHistoryRows.AnswerRow> answers(@Param("owner") String owner, @Param("pattern") String pattern,
                                                    @Param("afterId") String afterId, @Param("limit") int limit);
    UnifiedSearchHistoryRows.FeedbackBinding feedbackBinding(@Param("owner") String owner, @Param("id") String id,
                                                             @Param("revision") int revision);
    UnifiedSearchHistoryRows.FeedbackRow feedback(@Param("owner") String owner, @Param("id") String id,
                                                 @Param("revision") int revision);
    List<UnifiedSearchHistoryRows.FeedbackRow> feedbacks(@Param("owner") String owner, @Param("pattern") String pattern,
                                                       @Param("afterId") String afterId, @Param("limit") int limit);
    UnifiedSearchHistoryRows.RecollectionRow recollection(@Param("owner") String owner, @Param("id") String id,
                                                         @Param("versionId") String versionId);
    List<UnifiedSearchHistoryRows.RecollectionRow> recollections(@Param("owner") String owner, @Param("status") String status,
                                                               @Param("pattern") String pattern, @Param("afterId") String afterId,
                                                               @Param("limit") int limit);
}
