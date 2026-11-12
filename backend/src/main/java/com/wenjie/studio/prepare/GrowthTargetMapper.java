package com.wenjie.studio.prepare;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GrowthTargetMapper {
    record TargetRow(String ownerId, String itemId, String versionId, String title, String capturedOn,
                     long itemRevision, String status, String currentVersionId, long sourceGeneration,
                     String requirementsJson) {}

    TargetRow target(@Param("owner") String owner, @Param("itemId") String itemId,
                     @Param("versionId") String versionId);
    boolean topicExists(@Param("owner") String owner, @Param("id") String id);
}
