package com.wenjie.studio.bank;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface QuestionMapper {
    QuestionRows.QuestionRow question(@Param("owner") String owner, @Param("id") String id);
    List<QuestionRows.SummaryRow> questions(@Param("owner") String owner, @Param("status") String status,
            @Param("pattern") String pattern, @Param("type") String type, @Param("difficulty") Integer difficulty,
            @Param("dimension") String dimension, @Param("topicId") String topicId,
            @Param("knowledgeId") String knowledgeId, @Param("scenePattern") String scenePattern,
            @Param("favorite") Boolean favorite, @Param("cursor") String cursor, @Param("limit") int limit);
    QuestionRows.VersionRow version(@Param("owner") String owner, @Param("id") String id,
                                    @Param("versionId") String versionId);
    List<QuestionDtos.VersionSummary> versions(@Param("owner") String owner, @Param("id") String id,
                                              @Param("before") Integer before, @Param("limit") int limit);
    List<String> topics(@Param("owner") String owner, @Param("versionId") String versionId);
    List<QuestionDtos.KnowledgeLink> knowledgeLinks(@Param("owner") String owner, @Param("versionId") String versionId);
    QuestionRows.ReferenceRow reference(@Param("owner") String owner, @Param("id") String id,
                                        @Param("versionId") String versionId);
    List<QuestionDtos.Exposure> exposures(@Param("owner") String owner, @Param("id") String id,
            @Param("beforeTime") Instant beforeTime, @Param("beforeId") String beforeId, @Param("limit") int limit);
    void insertQuestion(@Param("question") QuestionRows.QuestionRow question);
    void insertVersion(@Param("version") QuestionRows.VersionRow version,
            @Param("type") String type, @Param("difficulty") Integer difficulty, @Param("scenario") String scenario,
            @Param("referenceAnswer") String referenceAnswer, @Param("checkpointsJson") String checkpointsJson);
    void insertTopic(@Param("owner") String owner, @Param("id") String id, @Param("versionId") String versionId,
                     @Param("topicId") String topicId, @Param("position") int position);
    void insertDimension(@Param("owner") String owner, @Param("id") String id,
                         @Param("versionId") String versionId, @Param("dimension") String dimension);
    void insertKnowledgeLink(@Param("owner") String owner, @Param("id") String id, @Param("versionId") String versionId,
            @Param("itemId") String itemId, @Param("knowledgeVersionId") String knowledgeVersionId, @Param("position") int position);
    int saveContent(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                    @Param("versionId") String versionId, @Param("number") int number, @Param("now") Instant now);
    int changeStatus(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                     @Param("status") String status, @Param("now") Instant now);
    int setFavorite(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                    @Param("favorite") boolean favorite, @Param("now") Instant now);
    void insertExposure(@Param("owner") String owner, @Param("id") String id, @Param("questionId") String questionId,
                        @Param("versionId") String versionId, @Param("kind") String kind, @Param("now") Instant now);
}
