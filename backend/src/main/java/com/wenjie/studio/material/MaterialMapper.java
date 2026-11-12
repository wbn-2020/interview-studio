package com.wenjie.studio.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

@Mapper
public interface MaterialMapper {
    MaterialRows.Attachment attachment(@Param("owner") String owner, @Param("id") String id);
    List<MaterialRows.Attachment> attachments(@Param("owner") String owner, @Param("cursor") String cursor,
                                            @Param("pattern") String pattern, @Param("limit") int limit);
    void insertAttachment(@Param("row") MaterialRows.Attachment row);
    int description(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                    @Param("description") String description, @Param("now") Instant now);
    int touchAttachment(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                        @Param("now") Instant now);
    int ready(@Param("owner") String owner, @Param("id") String id, @Param("uploadId") String uploadId,
              @Param("now") Instant now);
    long usedBytes(@Param("owner") String owner);
    MaterialRows.Upload upload(@Param("owner") String owner, @Param("id") String id);
    List<MaterialRows.Upload> uploads(@Param("owner") String owner, @Param("id") String id);
    void insertUpload(@Param("row") MaterialRows.Upload row);
    int claimUpload(@Param("owner") String owner, @Param("id") String id, @Param("token") String token,
                    @Param("now") Instant now, @Param("lease") Instant lease);
    int finishUpload(@Param("owner") String owner, @Param("id") String id, @Param("token") String token,
                     @Param("status") String status, @Param("received") long received,
                     @Param("error") String error, @Param("now") Instant now);
    int recoverUploads(@Param("owner") String owner, @Param("now") Instant now);
    MaterialRows.Run run(@Param("owner") String owner, @Param("id") String id);
    List<MaterialRows.Run> runs(@Param("owner") String owner, @Param("id") String id,
                               @Param("beforeTime") Instant beforeTime, @Param("beforeId") String beforeId,
                               @Param("limit") int limit);
    int runCount(@Param("owner") String owner, @Param("id") String id);
    int pendingRuns(@Param("owner") String owner, @Param("id") String id);
    int runningCount(@Param("owner") String owner);
    void insertRun(@Param("row") MaterialRows.Run row);
    MaterialRows.Run queued(@Param("owner") String owner);
    int claimRun(@Param("owner") String owner, @Param("id") String id, @Param("token") String token,
                 @Param("now") Instant now, @Param("lease") Instant lease);
    int finishRun(@Param("owner") String owner, @Param("id") String id, @Param("token") String token,
                  @Param("status") String status, @Param("parser") String parser,
                  @Param("truncated") boolean truncated, @Param("warnings") String warnings,
                  @Param("error") String error, @Param("count") int count, @Param("preview") boolean preview,
                  @Param("now") Instant now);
    int recoverRuns(@Param("owner") String owner, @Param("now") Instant now);
    void insertSegment(@Param("owner") String owner, @Param("row") MaterialDtos.Segment row);
    MaterialDtos.Segment segment(@Param("owner") String owner, @Param("id") String id);
    List<MaterialDtos.Segment> segments(@Param("owner") String owner, @Param("id") String id,
                                       @Param("after") int after, @Param("limit") int limit);
    void insertExcerpt(@Param("row") MaterialRows.Excerpt row);
    MaterialRows.Excerpt excerpt(@Param("owner") String owner, @Param("id") String id);
    List<MaterialRows.Excerpt> excerpts(@Param("owner") String owner, @Param("id") String id,
                                      @Param("cursor") String cursor, @Param("pattern") String pattern,
                                      @Param("status") String status, @Param("aiUse") String aiUse,
                                      @Param("limit") int limit);
    void insertPreparationSource(@Param("owner") String owner, @Param("itemId") String itemId,
                                 @Param("versionId") String versionId, @Param("excerptId") String excerptId);
}
