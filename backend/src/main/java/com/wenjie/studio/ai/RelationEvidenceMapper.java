package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.wenjie.studio.ai.RelationEvidenceRows.*;

@Mapper
@Repository("aiRelationEvidenceMapper")
public interface RelationEvidenceMapper {
    SourceRow source(@Param("owner") String owner, @Param("taskId") String taskId);
    List<MaterialRow> materials(@Param("owner") String owner, @Param("taskId") String taskId);
    int insertSource(@Param("source") SourceRow source);
    int insertMaterial(@Param("source") MaterialRow source);
}
