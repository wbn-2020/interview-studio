package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FactDraftMapper {
    FactDraftRows.SourceRow source(@Param("owner") String owner, @Param("taskId") String taskId);
    int insertSource(@Param("source") FactDraftRows.SourceRow source);
    List<FactDraftRows.MaterialRow> materials(@Param("owner") String owner, @Param("taskId") String taskId);
    int insertMaterial(@Param("source") FactDraftRows.MaterialRow source);
    FactDraftRows.DecisionRow decision(@Param("owner") String owner, @Param("taskId") String taskId);
    int insertDecision(@Param("decision") FactDraftRows.DecisionRow decision);
    FactDraftRows.TargetVersion targetVersion(@Param("owner") String owner, @Param("itemId") String itemId,
                                            @Param("versionId") String versionId);
    FactDraftRows.TargetSource targetSource(@Param("owner") String owner, @Param("itemId") String itemId,
                                          @Param("versionId") String versionId);
    List<String> targetMaterials(@Param("owner") String owner, @Param("itemId") String itemId,
                                @Param("versionId") String versionId);
    List<FactDraftRows.OriginRow> origins(@Param("owner") String owner, @Param("itemId") String itemId,
                                        @Param("through") int through, @Param("before") Integer before, @Param("limit") int limit);
}
