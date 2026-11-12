package com.wenjie.studio.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import static com.wenjie.studio.material.MaterialReferencesDtos.*;

@Mapper
public interface MaterialReferencesMapper {
    Source source(@Param("owner") String owner, @Param("excerptId") String excerptId);
    List<Entry> references(@Param("owner") String owner, @Param("excerptId") String excerptId,
                           @Param("kind") String kind, @Param("afterId") String afterId,
                           @Param("afterRevision") long afterRevision, @Param("limit") int limit);
}
