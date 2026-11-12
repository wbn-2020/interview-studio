package com.wenjie.studio.lifecycle;

import com.wenjie.studio.lifecycle.KnowledgeReferencesDtos.Entry;
import com.wenjie.studio.lifecycle.KnowledgeReferencesDtos.Source;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowledgeReferencesMapper {
    Source source(@Param("owner") String owner, @Param("itemId") String itemId,
                  @Param("versionId") String versionId);

    // 由只读服务先核根对象与确切版本；每次只查一类，limit 包含一条后续探测且至多 21。
    List<Entry> references(@Param("owner") String owner, @Param("itemId") String itemId,
                           @Param("versionId") String versionId, @Param("kind") String kind,
                           @Param("afterId") String afterId, @Param("afterRevision") long afterRevision,
                           @Param("limit") int limit);
}
