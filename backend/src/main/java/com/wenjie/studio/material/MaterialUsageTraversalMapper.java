package com.wenjie.studio.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface MaterialUsageTraversalMapper {
    record Edge(String key, Integer sourceNumber, String domain, String id, String versionId,
                Integer number, Long revision, String title, String taskId, Instant createdAt) {}
    record Saved(String ownerId, String impactId, String excerptId, String pathJson) {}
    List<Edge> edges(@Param("owner") String owner, @Param("domain") String domain,
                     @Param("id") String id, @Param("minimum") int minimum, @Param("limit") int limit);
    boolean versionWitness(@Param("owner") String owner, @Param("domain") String domain,
                           @Param("itemId") String itemId, @Param("versionId") String versionId,
                           @Param("number") int number);
    int insertPath(@Param("owner") String owner, @Param("impactId") String impactId,
                   @Param("excerptId") String excerptId, @Param("pathJson") String pathJson);
    Saved path(@Param("owner") String owner, @Param("impactId") String impactId);
    boolean legacyKnowledge(@Param("owner") String owner, @Param("correctionId") String correctionId,
                            @Param("itemId") String itemId, @Param("versionId") String versionId);
    int insertStatus(@Param("owner") String owner, @Param("event") MaterialLifecycleDtos.Event event,
                     @Param("id") String id, @Param("hash") String hash,
                     @Param("target") Edge target, @Param("pathJson") String pathJson);
}
