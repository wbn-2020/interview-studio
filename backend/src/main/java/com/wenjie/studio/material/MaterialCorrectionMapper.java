package com.wenjie.studio.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface MaterialCorrectionMapper {
    record Row(String ownerId, String id, String excerptId, String replacementExcerptId, Long sourceRevision,
               Long replacementRevision, String reason, Instant createdAt) {}

    Row statement(@Param("owner") String owner, @Param("excerptId") String excerptId);
    boolean corrected(@Param("owner") String owner, @Param("excerptId") String excerptId);
    int insert(@Param("owner") String owner, @Param("statement") MaterialCorrectionDtos.Statement statement);
}
