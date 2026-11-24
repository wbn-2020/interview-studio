package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiConfigMapper {
    AiConfigRows.RuntimeConfig config(@Param("owner") String owner);
    int pendingTaskCount(@Param("owner") String owner);
    int insertConfig(@Param("config") AiConfigRows.RuntimeConfig config);
    int updateConfig(@Param("config") AiConfigRows.RuntimeConfig config, @Param("expectedRevision") long expectedRevision);
}
