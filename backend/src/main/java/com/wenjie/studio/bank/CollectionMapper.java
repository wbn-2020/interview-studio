package com.wenjie.studio.bank;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface CollectionMapper {
    CollectionRows.CollectionRow collection(@Param("owner") String owner, @Param("id") String id);
    List<CollectionRows.CollectionRow> collections(@Param("owner") String owner, @Param("kind") String kind,
            @Param("status") String status, @Param("pattern") String pattern,
            @Param("cursor") String cursor, @Param("limit") int limit);
    CollectionRows.VersionRow version(@Param("owner") String owner, @Param("collectionId") String collectionId,
                                       @Param("versionId") String versionId);
    List<CollectionDtos.VersionSummary> versions(@Param("owner") String owner, @Param("id") String id,
                                                 @Param("before") Integer before, @Param("limit") int limit);
    List<CollectionRows.ReferenceRow> references(@Param("owner") String owner,
            @Param("collectionId") String collectionId, @Param("versionId") String versionId);
    void insertCollection(@Param("collection") CollectionRows.CollectionRow collection);
    void insertVersion(@Param("version") CollectionRows.VersionRow version);
    void insertStep(@Param("owner") String owner, @Param("collectionId") String collectionId,
            @Param("versionId") String versionId, @Param("step") CollectionDtos.Step step,
            @Param("position") int position);
    void insertDependency(@Param("owner") String owner, @Param("collectionId") String collectionId,
            @Param("versionId") String versionId, @Param("stepId") String stepId,
            @Param("dependsOn") String dependsOn);
    int saveContent(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
            @Param("versionId") String versionId, @Param("number") int number,
            @Param("title") String title, @Param("stepCount") int stepCount, @Param("now") Instant now);
    int changeStatus(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
                     @Param("status") String status, @Param("now") Instant now);
}
