package com.wenjie.studio.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface MaterialLifecycleMapper {
    record Row(String excerptId, String attachmentId, String baselineExcerptId, String baselineAttachmentId,
               String status, String aiUse, Long revision, Instant createdAt, Instant updatedAt,
               Instant excerptCreatedAt) {}

    Row source(@Param("owner") String owner, @Param("id") String id);
    boolean hasReferences(@Param("owner") String owner, @Param("id") String id);
    boolean hasUnsupportedReferences(@Param("owner") String owner, @Param("id") String id);
    boolean missingBaseline(@Param("owner") String owner, @Param("attachmentId") String attachmentId);
    void initialize(@Param("owner") String owner, @Param("id") String id,
                    @Param("attachmentId") String attachmentId, @Param("now") Instant now);
    int change(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
               @Param("status") String status, @Param("aiUse") String aiUse, @Param("now") Instant now);
    void insertEvent(@Param("owner") String owner, @Param("event") MaterialLifecycleDtos.Event event);
    List<MaterialLifecycleDtos.Event> history(@Param("owner") String owner, @Param("id") String id,
                                            @Param("after") long after, @Param("limit") int limit);
}
