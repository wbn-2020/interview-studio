package com.wenjie.studio.review;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import static com.wenjie.studio.review.ReviewedObservationRows.*;

@Mapper
public interface ReviewedObservationMapper {
    ObservationRow observation(@Param("owner") String owner, @Param("id") String id);
    List<ObservationRow> observations(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                      @Param("questionId") String questionId, @Param("answerId") String answerId,
                                      @Param("status") String status, @Param("cursor") String cursor,
                                      @Param("limit") int limit);
    RevisionRow revision(@Param("owner") String owner, @Param("id") String id,
                         @Param("revision") long revision);
    List<RevisionRow> history(@Param("owner") String owner, @Param("id") String id,
                              @Param("before") Long before, @Param("limit") int limit);

    int insertObservation(@Param("row") ObservationRow row);
    int updateObservation(@Param("row") ObservationRow row, @Param("expected") long expected);
    int insertRevision(@Param("row") RevisionRow row);
}
