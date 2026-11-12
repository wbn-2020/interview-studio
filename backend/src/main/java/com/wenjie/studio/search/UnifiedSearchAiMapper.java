package com.wenjie.studio.search;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UnifiedSearchAiMapper {
    List<UnifiedSearchAiRows.Report> reports(@Param("owner") String owner, @Param("afterId") String afterId,
                                            @Param("limit") int limit);
}
