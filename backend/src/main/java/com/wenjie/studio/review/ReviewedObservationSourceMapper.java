package com.wenjie.studio.review;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReviewedObservationSourceMapper {
    ReviewedObservationSourceRows.Access access(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                                @Param("questionId") String questionId, @Param("answerId") String answerId);
    ReviewedObservationSourceRows.Answer answer(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                                @Param("questionId") String questionId, @Param("answerId") String answerId);
}
