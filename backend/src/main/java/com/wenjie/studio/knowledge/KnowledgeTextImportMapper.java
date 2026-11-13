package com.wenjie.studio.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

import static com.wenjie.studio.knowledge.KnowledgeTextImportDtos.*;

@Mapper
public interface KnowledgeTextImportMapper {
    boolean exists(@Param("owner") String owner, @Param("sha256") String sha256);
    Entry find(@Param("owner") String owner, @Param("id") String id);
    List<Entry> list(@Param("owner") String owner, @Param("sha256") String sha256,
                     @Param("cursorId") String cursorId, @Param("cursorTime") Instant cursorTime,
                     @Param("limit") int limit);
    void insert(@Param("owner") String owner, @Param("entry") Entry entry);
}
