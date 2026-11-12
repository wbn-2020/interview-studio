package com.wenjie.studio.review;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReviewedObservationProjectionMapper {
    ReviewedObservationProjectionRows.Answer answer(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                                    @Param("questionId") String questionId, @Param("answerId") String answerId);
}
