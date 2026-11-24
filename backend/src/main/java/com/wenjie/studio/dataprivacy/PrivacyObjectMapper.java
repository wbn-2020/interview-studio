package com.wenjie.studio.dataprivacy;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PrivacyObjectMapper {
    record Row(String objectKind, String objectId, String title, String subtitle,
               LocalDateTime updatedAt, String privacyState) { }

    List<Row> objects(@Param("owner") String owner, @Param("kind") String kind,
                      @Param("pattern") String pattern,
                      @Param("afterUpdatedAt") LocalDateTime afterUpdatedAt,
                      @Param("afterKind") String afterKind, @Param("afterId") String afterId,
                      @Param("ids") List<String> ids, @Param("limit") int limit);
}
