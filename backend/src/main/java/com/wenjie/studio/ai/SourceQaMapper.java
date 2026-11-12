package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SourceQaMapper {
    SourceQaRows.Stamp stamp(@Param("owner") String owner, @Param("domain") String domain, @Param("id") String id);
    SourceQaRows.Fact fact(@Param("owner") String owner, @Param("id") String id, @Param("versionId") String versionId);
    SourceQaRows.Material material(@Param("owner") String owner, @Param("id") String id);
    SourceQaRows.AnswerIdentity answer(@Param("owner") String owner, @Param("ref") SourceQaDtos.Ref ref);
    int exists(@Param("owner") String owner, @Param("ref") SourceQaDtos.Ref ref);
    String purpose(@Param("owner") String owner, @Param("taskId") String taskId);
    List<SourceQaRows.Binding> bindings(@Param("owner") String owner, @Param("taskId") String taskId);
    void insert(@Param("row") SourceQaRows.Binding row);
}
