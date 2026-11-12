package com.wenjie.studio.review;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import static com.wenjie.studio.review.StandardReviewStoreRows.*;

@Mapper
public interface StandardReviewStoreMapper {
    ReviewRow review(@Param("owner") String owner, @Param("id") String id);
    List<ReviewRow> reviews(@Param("owner") String owner, @Param("questionId") String questionId,
                            @Param("versionId") String versionId, @Param("status") String status,
                            @Param("cursor") String cursor, @Param("limit") int limit);
    RevisionRow revision(@Param("owner") String owner, @Param("id") String id,
                         @Param("revision") long revision);
    List<RevisionRow> history(@Param("owner") String owner, @Param("id") String id,
                              @Param("before") Long before, @Param("limit") int limit);
    List<CheckpointRow> checkpoints(@Param("owner") String owner, @Param("id") String id,
                                    @Param("revision") long revision);
    Long lastCheckpointRevision(@Param("owner") String owner, @Param("reviewId") String reviewId,
                                @Param("checkpointId") String checkpointId,
                                @Param("beforeExclusive") long beforeExclusive);
    List<EvidenceRow> evidence(@Param("owner") String owner, @Param("id") String id,
                               @Param("revision") long revision);

    int insertReview(@Param("row") ReviewRow row);
    int updateReview(@Param("row") ReviewRow row, @Param("expected") long expected);
    int insertRevision(@Param("row") RevisionRow row);
    int insertCheckpoint(@Param("row") CheckpointRow row);
    int insertEvidence(@Param("row") EvidenceRow row);
}
