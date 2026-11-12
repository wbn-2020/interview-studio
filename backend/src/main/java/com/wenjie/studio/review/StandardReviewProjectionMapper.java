package com.wenjie.studio.review;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StandardReviewProjectionMapper {
    StandardReviewProjectionRows.Question question(@Param("owner") String owner, @Param("id") String id,
            @Param("versionId") String versionId, @Param("checkpointId") String checkpointId);
    List<StandardReviewProjectionRows.Knowledge> knowledge(@Param("owner") String owner,
            @Param("requests") List<StandardReviewProjectionRows.KnowledgeRequest> requests);
    List<StandardReviewProjectionRows.Material> materials(@Param("owner") String owner,
            @Param("requests") List<StandardReviewProjectionRows.MaterialRequest> requests);
    int insertQuestionStamp(@Param("stamp") StandardReviewProjectionRows.QuestionStamp stamp);
}
