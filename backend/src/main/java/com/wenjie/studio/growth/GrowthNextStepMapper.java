package com.wenjie.studio.growth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import static com.wenjie.studio.growth.GrowthNextStepRows.*;

@Mapper
public interface GrowthNextStepMapper {
    boolean sourceExists(@Param("owner") String owner, @Param("kind") String kind, @Param("id") String id);
    List<String> sourceCandidates(@Param("owner") String owner, @Param("kind") String kind,
                                  @Param("targetId") String targetId, @Param("targetVersionId") String targetVersionId,
                                  @Param("after") String after, @Param("limit") int limit);
    List<ActionSize> actionSizes(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<RequirementCandidate> insufficientRequirements(@Param("owner") String owner,
            @Param("targetId") String targetId, @Param("targetVersionId") String targetVersionId,
            @Param("after") RequirementCandidate after, @Param("limit") int limit);
}
