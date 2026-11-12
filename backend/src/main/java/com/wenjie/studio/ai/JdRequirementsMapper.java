package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface JdRequirementsMapper {
    List<JdRequirementsRows.SourceRow> sources(@Param("owner") String owner, @Param("taskId") String taskId);
    JdRequirementsRows.DecisionRow decision(@Param("owner") String owner, @Param("taskId") String taskId);
    int insertDecision(@Param("decision") JdRequirementsRows.DecisionRow decision);
    JdRequirementsRows.TargetVersion targetVersion(@Param("owner") String owner, @Param("itemId") String itemId,
                                                  @Param("versionId") String versionId);
    List<String> requirementIds(@Param("owner") String owner, @Param("itemId") String itemId, @Param("versionId") String versionId);
    List<JdRequirementsRows.OriginRow> origins(@Param("owner") String owner, @Param("itemId") String itemId,
            @Param("through") int through, @Param("before") Integer before, @Param("limit") int limit);
}
