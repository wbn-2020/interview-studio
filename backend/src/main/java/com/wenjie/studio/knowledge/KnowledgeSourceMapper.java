package com.wenjie.studio.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface KnowledgeSourceMapper {
    KnowledgeSourceDtos.Row find(@Param("owner") String owner, @Param("versionId") String versionId,
                                 @Param("revision") Long revision);
    void insert(@Param("owner") String owner, @Param("itemId") String itemId,
                @Param("versionId") String versionId, @Param("revision") long revision,
                @Param("sourcesJson") String sourcesJson, @Param("now") Instant now);
}
