package com.wenjie.studio.review;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface RecollectionMapper {
    RecollectionRows.RecollectionRow recollection(@Param("owner") String owner, @Param("id") String id);
    List<RecollectionRows.SummaryRow> list(@Param("owner") String owner, @Param("status") String status,
            @Param("interviewId") String interviewId, @Param("pattern") String pattern,
            @Param("cursor") String cursor, @Param("limit") int limit);
    RecollectionRows.VersionRow version(@Param("owner") String owner, @Param("id") String id,
                                        @Param("versionId") String versionId);
    List<RecollectionDtos.VersionSummary> versions(@Param("owner") String owner, @Param("id") String id,
            @Param("before") Integer before, @Param("limit") int limit);
    RecollectionRows.QuestionRow questionByRecollection(@Param("owner") String owner, @Param("id") String id,
                                                       @Param("versionId") String versionId);
    RecollectionRows.QuestionRow questionSource(@Param("owner") String owner, @Param("questionId") String questionId,
                                                @Param("versionId") String versionId);
    void insertRecollection(@Param("item") RecollectionRows.RecollectionRow item);
    void insertVersion(@Param("version") RecollectionRows.VersionRow version);
    int saveContent(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                    @Param("versionId") String versionId, @Param("number") int number, @Param("now") Instant now);
    int changeStatus(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                     @Param("status") String status, @Param("now") Instant now);
    void insertQuestion(@Param("link") RecollectionRows.QuestionRow link);
}
