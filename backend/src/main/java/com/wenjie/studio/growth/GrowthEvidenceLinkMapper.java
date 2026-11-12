package com.wenjie.studio.growth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GrowthEvidenceLinkMapper {
    boolean hasActiveLink(@Param("owner") String owner, @Param("evidenceKey") String evidenceKey,
                          @Param("targetId") String targetId, @Param("targetVersionId") String targetVersionId);
}
