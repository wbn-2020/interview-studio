package com.wenjie.studio.review;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface StandardReviewSourceMapper {
    StandardReviewSourceRows.Question question(@Param("owner") String owner, @Param("id") String id,
                                               @Param("versionId") String versionId);
    boolean referenceExposure(@Param("owner") String owner, @Param("id") String id,
                              @Param("questionId") String questionId, @Param("versionId") String versionId);
    StandardReviewSourceRows.Knowledge knowledge(@Param("owner") String owner, @Param("id") String id,
                                                @Param("versionId") String versionId);
    StandardReviewSourceRows.Attachment attachment(@Param("owner") String owner, @Param("id") String id);
    int insertReferenceExposure(@Param("owner") String owner, @Param("id") String id,
                                 @Param("questionId") String questionId, @Param("versionId") String versionId,
                                 @Param("now") Instant now);
}
