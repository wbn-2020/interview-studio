package com.wenjie.studio.prepare;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PreparationMaterialEligibilityMapper {
    Source source(@Param("owner") String owner, @Param("kind") String kind,
                  @Param("itemId") String itemId, @Param("versionId") String versionId);
    List<String> excerpts(@Param("owner") String owner, @Param("itemId") String itemId,
                          @Param("versionId") String versionId);

    record Source(String itemId, String kind, String versionId, String projectId, String projectVersionId) {}
}
