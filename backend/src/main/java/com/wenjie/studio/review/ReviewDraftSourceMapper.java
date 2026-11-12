package com.wenjie.studio.review;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReviewDraftSourceMapper {
    ReviewDraftSourceRows.Feedback feedback(@Param("owner") String owner, @Param("id") String id,
                                            @Param("revision") int revision);
    ReviewDraftSourceRows.Recollection recollection(@Param("owner") String owner, @Param("id") String id,
                                                    @Param("versionId") String versionId);
    ReviewDraftSourceRows.Access access(@Param("owner") String owner, @Param("sessionId") String sessionId,
                                        @Param("questionId") String questionId, @Param("answerId") String answerId);
}
